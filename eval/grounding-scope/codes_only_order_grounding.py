#!/usr/bin/env python3
"""Live grounding harness for a codes-only `active_drug_order` record (issue #294).

Answers the question #294 asks — does a real query publish `grounded=false` for an
injected active-order record whose display names no drug, and how often — on the RUNNING
standalone, over the full production path (real querystore retrieval, real LLM answer,
real Tier-1 e5 cosine and real Tier-2 entailment). No stub can answer it: whether such a
record's real embedding clears `chartsearchai.grounding.minCosine`, and whether a real
model makes a medication claim about a record that names no drug, are both properties of
systems this repo does not implement.

It reuses `grounding_scope_ab.py`'s `get_gp`/`set_gp`/`req`. It does NOT use that module's
`search`, and the reason is worth stating because the obvious reading is that it should:
`search` returns verdicts keyed by citation INDEX, and this measurement has to find one
record by `resourceUuid` — the index is whatever the injector happened to number it. So the
body is read here, and `verdict_of` below applies that reader's tagging rules verbatim:
`attached` for `attachedByTheModule`, `withheld` for `group == "reference"`, never None for
either, which is the misreading the root CLAUDE.md's capture-scorer rule exists for (#305).
If you change the tagging there, change it here; a second SPELLING of those rules is the
cost of needing the uuid, and it is the only thing duplicated.

## The arrangement has to be built, and the natural count is zero

Measured on the RefApp 3.7.1 pool database: 53 active drug orders across 30 patients, of
which 0 were codes-only. Such an order needs BOTH halves, and neither occurs naturally
there:

  * nameless in all three sources `addDrugName` reads — no coded drug, no `drug_non_coded`
    free text, and no unvoided name on its concept — AND carrying at least one ATC code,
    since an order with neither name nor code is skipped entirely; and
  * UNREPRESENTED: the retrieved chart is complete for `drug_order` yet holds no record for
    that order, i.e. the querystore index is behind for it. That is what
    `DrugReferenceInjector.unrepresentedActiveOrders` WARNs about, and it is environmental.

**Build it as a NEW order on a concept carrying no other order, obs or drug row.** The
obvious shortcut — change an existing order's uuid so its own indexed record stops
matching — leaves that record in the chart as a NAMED TWIN for the same prescription, and
the twin changes the outcome: with it the model cited the injected record (in a sentence
that names no drug, which the record entails, so `true`), and without it the model did not
cite the record at all. ADR Decision 38's owed-measurement section records both runs. A
run that does not say which arrangement it used is not interpretable.

**Confirm the injection from the server log, not from the citation.** An uncited record is
not an unrejected one, and the two are indistinguishable on the wire. The reconciliation
WARN ("Active-order reconciliation: N of M ... Unrepresented: [[ATC ...]") is the evidence
that the record reached the prompt at all.

Usage:
    BASE=http://localhost:8082/openmrs/ws/rest/v1 OMRS_USER=admin OMRS_PASS=... \
        PATIENT=<uuid> ORDER_UUID=<uuid of the codes-only order> \
        python3 codes_only_order_grounding.py [regimes|probes|all]

The regime grid is `entailment.enabled` on/off x `minCosine` 0.40 (shipped) and 0.82 (the
value this module's own global-property text advises for e5), one question held fixed. The
probes are ordinary questions a clinician would ask — what the patient is on, how many orders
there are, whether a drug is safe to add. They are the half that tests whether the injected
record reaches the answer at all.
"""
import importlib.util
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "grounding_scope_ab", os.path.join(HERE, "grounding_scope_ab.py"))
gsab = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gsab)

PATIENT = os.environ.get("PATIENT")
ORDER_UUID = os.environ.get("ORDER_UUID")
FLOOR_GP = "chartsearchai.grounding.minCosine"

# The regime grid holds this fixed, and it must be a question that gets the model to SAY
# something about the record: on a twin-free arrangement an ordinary medication question leaves
# the record uncited, so a grid built on one compares four cells that published no verdict at
# all. This is the question on which a published `grounded=false` was first observed.
REGIME_QUESTION = ("Does the patient have any active drug order whose drug the chart does"
                   " not name?")

# Run beside the grid, because what the module is FOR is that an ordinary medication question
# cannot deny a prescription the chart holds. On the run in ADR Decision 38 it still can.
MEDICATION_QUESTION = "What medications is this patient currently taking?"

REGIMES = [("entailment-on  floor-0.40", True, "0.40"),
           ("entailment-on  floor-0.82", True, "0.82"),
           ("tier1-only     floor-0.40", False, "0.40"),
           ("tier1-only     floor-0.82", False, "0.82")]

# The CONTROL, not the exposure. None of these elicits #294's antecedent — the question that does
# is REGIME_QUESTION above, where the grid needs it held fixed. What these measure is the thing
# the injected record exists for: that an ordinary clinical question cannot deny a prescription
# the chart holds. On the run recorded in ADR Decision 38 they show it still can.
#
# Kept SUBSTANCE-NEUTRAL on purpose. An earlier run carried over two probes naming the drug of a
# PREVIOUS arrangement's order ("which antiretrovirals…", "is the patient taking lamivudine?")
# after the arrangement had moved to a different concept, so those cells could not have elicited
# anything and that run's "cited in none" was weaker than it read. A drug-specific probe belongs
# in SUBSTANCE_PROBE below, naming what THIS arrangement's order actually carries.
PROBES = [
    ("medication", MEDICATION_QUESTION),
    ("name-each-order", "List each active drug order and name its drug."),
    ("full-med-list", "Give the patient's complete medication list, naming every drug."),
    ("how-many", "How many active drug orders does this patient have?"),
    ("safety", "Is it safe to start her on clarithromycin?"),
]

# Set SUBSTANCE_PROBE to a question naming what THIS arrangement's order actually is — e.g. for
# an order carrying P01BD51, "Is the patient taking any antimalarial medication?". It is not
# hardcoded because an earlier run carried over probes naming a previous arrangement's drug and
# those cells could not have elicited anything. It is the probe that showed the answer DENYING a
# prescription the chart holds a record for, which is the sharper of the two findings on #294.
SUBSTANCE_PROBE = os.environ.get("SUBSTANCE_PROBE")


def set_regime(entailment, floor):
    """Set the regime and ASSERT it took.

    `set_gp` takes a GP NAME and resolves the uuid itself. An earlier version of this
    driver passed it the uuid, so the lookup found nothing, the write silently did nothing,
    and three cells labelled as different regimes were all the install's pre-existing one —
    producing plausible, identical numbers. Never trust the write; read it back.
    """
    want = ((gsab.GROUNDING_GP, "true"),
            (gsab.ENTAILMENT_GP, "true" if entailment else "false"),
            (FLOOR_GP, str(floor)))
    for name, value in want:
        gsab.set_gp(name, value)
    time.sleep(1)
    for name, value in want:
        _, got = gsab.get_gp(name)
        if str(got) != value:
            raise AssertionError("regime did not take: %s is %r, wanted %r" % (name, got, value))


def verdict_of(reference):
    """What the wire published for one citation, tagged the way `grounding_scope_ab.search` tags it.

    A STRING for the two cases that are not verdicts, never None: a module-attached citation
    (#305) and a reference-group one (#201) both carry `grounded: null` for reasons that are
    not "unverified", and printing None for them is what lets a tally be quoted over citations
    it is structurally blind to.
    """
    if reference.get("attachedByTheModule"):
        return "attached"
    if reference.get("group") == "reference":
        return "withheld"
    return reference.get("grounded")


def cell(label, question, entailment, floor):
    set_regime(entailment, floor)
    started = time.time()
    body = gsab.req("/chartsearchai/search",
                    {"patient": PATIENT, "question": question}, "POST")
    references = body.get("references") or []
    ours = [r for r in references if r.get("resourceUuid") == ORDER_UUID]
    out = {
        "cell": label,
        "entailment": entailment,
        "floor": floor,
        "secs": round(time.time() - started, 1),
        "question": question,
        "answer": (body.get("answer") or "").strip(),
        "verdicts": {str(r.get("index")): verdict_of(r) for r in references},
        # "NOT CITED" is a RESULT and not a gap: an uncited record got no verdict, which is a
        # different measurement from a verdict that came back true. Keep them distinguishable.
        "codes_only_record": ([
            {"index": r.get("index"), "resourceType": r.get("resourceType"),
             "group": r.get("group"), "grounded": r.get("grounded"),
             "attachedByTheModule": r.get("attachedByTheModule"),
             "verdict": verdict_of(r)} for r in ours]
            or "NOT CITED"),
    }
    print(json.dumps(out, indent=2), flush=True)
    return out


def main():
    if not PATIENT or not ORDER_UUID:
        sys.exit("set PATIENT and ORDER_UUID (the codes-only order's uuid); see the module docstring")
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    print("# BASE=%s patient=%s order=%s" % (gsab.BASE, PATIENT, ORDER_UUID), flush=True)
    print("# confirm injection in the server log: 'Active-order reconciliation'", flush=True)

    # Save and restore, in a finally, like the sibling harness. These are SHARED standalones and
    # the shipped defaults are false/false/0.40, so a run that returned leaving grounding and
    # entailment ON would silently put every later probe on that box into a non-stock regime —
    # and a run that CRASHED mid-grid would do it without even a line saying so.
    baseline = [(name, gsab.get_gp(name)[1])
                for name in (gsab.GROUNDING_GP, gsab.ENTAILMENT_GP, FLOOR_GP)]
    print("# baseline: %s" % baseline, flush=True)
    try:
        if which in ("regimes", "all"):
            for label, entailment, floor in REGIMES:
                cell("regime " + label, REGIME_QUESTION, entailment, floor)
        if which in ("probes", "all"):
            for tag, question in PROBES:
                cell("probe " + tag, question, True, "0.40")
            if SUBSTANCE_PROBE:
                cell("probe substance", SUBSTANCE_PROBE, True, "0.40")
            else:
                print("# SUBSTANCE_PROBE unset: the arrangement-specific probe was NOT run,"
                      " so this run says nothing about whether the answer denies the drug",
                      flush=True)
    finally:
        for name, value in baseline:
            if value is None:
                # The row exists with no value; writing str(None) would set the literal "None".
                print("# NOT restoring %s: it had no value to restore" % name, flush=True)
                continue
            gsab.set_gp(name, value)
        print("# restored: %s" % baseline, flush=True)


if __name__ == "__main__":
    main()
