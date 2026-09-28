#!/usr/bin/env python3
"""Live grounding-scope A/B harness for chartsearchai (measure-first gate).

Measures how citation grounding verdicts change between scoping modes on the
RUNNING standalone, for a fixed (patient, query) set. This is the measurement
gate for any change to CitationGroundingVerifier's sentence/clause scoping —
it exercises the FULL production grounding path (real querystore retrieval, the
real LLM answer, and the real batched Tier-2 entailment), which is essential
because the clause-scoping regression is an emergent BATCH-COUPLING effect that
no stubbed unit test can reproduce.

For each (patient, query) case it runs POST /chartsearchai/search under each
value of the `chartsearchai.grounding.clauseScoped` GP and records, per cited
index, whatever the wire published for it. Sentence-scope (clauseScoped=false) is the SAFE
baseline. Versus it:
  * a True->False flip is a REGRESSION candidate (e.g. patient 165497e8
    "any feeding problems?" cite [5], a provisional-diagnosis false negative);
  * a False->True flip is a WIN candidate (e.g. "any ear problems?" cite [89]).

Since issue #302 a chart citation's null has a NEW CAUSE on the sentence-scope
side (null was always publishable -- see the caveat below -- so what changed is
why), and a gate that reads a verdict has to know about it or it fails open. A COMPOUND claim unit -- more than one citation
with claim text between two of its markers -- publishes no verdict at all: it
skips Tier-2, no Tier-1 cosine is computed for publication, and the cell reads
null whichever way either tier would have answered. So the sentence column now carries null for
exactly the population clause-scoping targets. That includes this harness's own
named win case, cite [89] on "any ear problems?", if the model answers it in the
compound-sentence form the clauseScoped setting is documented against -- run it
and read the cell rather than assuming, since the answer is regenerated each
time. Read for True/False flips alone, a tally would go to zero wherever that
happens and the gate would print a pass it had not measured. Hence the null-side
classes below -- three counted and a fourth deliberately not -- printed separately
rather than folded in:
  * null->True is a WIN of the demoted kind (sentence scope could not certify
    the citation, clause scope can);
  * True->null is a REGRESSION of the demoted kind;
  * null->False is a REGRESSION too, and it is the one that matters most here:
    it is the candidate scoping PUBLISHING the unsupported badge that #302
    removed. Expect it on the two medication cases below, whose clause-scoped
    cumulative prefix for the second citation still names the first drug -- so
    without this class a scoping that reinstates #302's own symptom scores
    demoted_wins=1, demoted_regressions=0 and the gate prints a pass.
  * False->null is left UNCOUNTED on purpose: sentence scope published a flag
    and clause scope withheld it, which is a loss of signal rather than a wrong
    verdict. Note it can no longer arise from a COMPOUND unit -- that cell is
    null on the sentence side now, not False -- so what remains here is the
    other causes of a sentence-side False.

The #302 withholding is gated on entailment, and the module ships
chartsearchai.grounding.entailment.enabled=false. With it off, none of the three
null-side classes can fire for that reason at all and the demoted tallies are
measuring something else, so the harness reads both grounding properties at
startup, prints them beside the baseline line, and says so in the output rather
than leaving a reader to assume which regime produced the numbers.

What the wire cannot tell you even with entailment on: a chart null may also mean
"not checked" -- no record text, an embedding failure, Tier-2 cap overflow with no
Tier-1 verdict. This harness does not separate those from a #302 withholding, so read
a null-side count as an upper bound on the demoted kind, not as a measurement of
it.

Issue #294 adds a cause that is NOT on the entailment gate and so is present in
both arms: a citation of an injected active-order record the module could read
no drug name for publishes no verdict at all. It reads on the wire as a plain
`None`, indistinguishable from "not checked" -- no wire field marks the shape --
so it is one more unnamed contributor to a null-side count. It moves no A/B class,
both arms being null. Do NOT discount it the way the paragraph below discounts
#284: most of the cases below are condition-shaped, but the last two ask what
medications the patient is taking, which is the question shape that cites an
injected active-order record. So this cause is MORE likely to appear here than
that one, not less.

Issue #284 adds one more cause of a sentence-side null, on the same entailment
gate: a chart citation whose claim also rests on a module-supplied safety
finding has the judge's NEGATIVE withheld. So a null-side count is an upper
bound over that too, and this harness cannot attribute a cell between the two.
Most of its cases are condition-shaped rather than drug-safety questions, so a
finding is unlikely to be injected for those -- but that has not been re-measured,
and "unlikely" is not "cannot". It said "six cases" until the #294 sweep counted
them: CASES holds EIGHT, and the last two are medication questions, which is the
shape this discount is weakest for. Do not restate the count; read it off CASES. Do not quote a tally here over a change to the
#284 rule.

Only the MODEL's own CHART-group citations are measurable here. A reference-group
citation publishes no verdict at all (issue #201), so its cells read `withheld`;
and since issue #305 a chart-group citation the MODULE attached carries none
either, so its cells read `attached` (both tags are set in `verdict_tag`,
whose docstring says why neither may be printed as None). A scoping flip on
either cannot be seen from the wire, so the gate below is a statement about
the model's own chart citations.

The GP is saved before and restored after. Answers are grounding-independent,
so a differing answer between modes signals LLM nondeterminism (reported).

Before any GP is read, every cohort patient is looked up on the instance, and the run REFUSES
when one does not resolve (#240). The arms are switched by writing a GP, so without that check an
absent cohort changed the instance's configuration and measured nothing, with only the
best-effort restore in `run`'s finally to put it back. `--selftest` pins that ordering offline.

Usage:  python3 eval/grounding-scope/grounding_scope_ab.py [--selftest]
Env:    BASE (default http://localhost:8081/openmrs/ws/rest/v1), OMRS_USER, OMRS_PASS,
        CONDITION_PATIENT, MEDICATION_PATIENTS (comma-separated) — see the CASES comment for
        why the defaults do not resolve on the RefApp 3.7.1 dev standalone. An unset one takes
        its default; one set to no patient is refused (`parse_cohort`).
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("BASE", "http://localhost:8081/openmrs/ws/rest/v1")
AUTH = base64.b64encode(
    ("%s:%s" % (os.environ.get("OMRS_USER", "admin"),
               os.environ.get("OMRS_PASS", "Admin123"))).encode()).decode()
GP = "chartsearchai.grounding.clauseScoped"
GROUNDING_GP = "chartsearchai.grounding.enabled"
ENTAILMENT_GP = "chartsearchai.grounding.entailment.enabled"

# A uuid names a patient on ONE install, so the cohort is overridable (#240) — and `run` refuses,
# before it touches any GP, when a patient here does not resolve on the instance at BASE.
#
# 165497e8 = Sarah Taylor on the install this harness was written against: malnutrition recorded
# as BOTH an active condition AND a provisional primary diagnosis (the compound-sentence shape
# clause-scoping targets), microstomia, etc. It does not exist on the RefApp 3.7.1 dev standalone,
# and that install has NO drop-in substitute. Its own Sarah Taylor (dc8560c9-…) is a different
# chart, with no malnutrition condition or diagnosis. A search of the coded names in
# `conditions` and `encounter_diagnosis` for "malnutrition" found two patients carrying it as both
# an active condition and a provisional rank-1 diagnosis, and neither has a coded condition or
# diagnosis naming ears, mouth, swallowing or feeding (SQL, 2026-09-28; non-coded rows unsearched).
# So neither the uuid nor the name was swapped: either would make the gate measure a
# different chart under GATE criteria keyed to this one's citation indexes. Pick a patient with
# the shape above and pass it as CONDITION_PATIENT.
DEFAULT_CONDITION_PATIENT = "165497e8-13e0-4fa4-8190-8e6fa067c4b7"
# Issue #302's own population and question: two patients whose medication answer is a
# colon-less list of two active orders, which is a compound claim unit in sentence scope.
DEFAULT_MEDICATION_PATIENTS = ["83f95445-d471-4e9c-b10e-a89b6632dbe8",
                               "e30bc8f0-08bb-406c-986a-2b153a495603"]


def parse_cohort(environ):
    """The cohort `environ` names, as (condition patient, medication patients, problems).

    One rule for both overrides: a variable that is UNSET takes its default, and one that is set
    is stripped and must name at least one patient. A set-but-empty value (`CONDITION_PATIENT=`,
    `MEDICATION_PATIENTS=','`) is a PROBLEM, never a fallback: falling back would measure a cohort
    the operator did not ask for, and passing it through would either drop cases from the TALLY
    or request `/patient/`, which is a 400 rather than the refusal. `run` refuses on any problem
    before it sends a request. They are returned rather than raised because
    `codes_only_order_grounding.py` imports this module and uses no cohort.
    """
    problems = []
    condition = environ.get("CONDITION_PATIENT", DEFAULT_CONDITION_PATIENT).strip()
    if not condition:
        problems.append("CONDITION_PATIENT is set but names no patient")
        condition = DEFAULT_CONDITION_PATIENT
    medications = DEFAULT_MEDICATION_PATIENTS
    if "MEDICATION_PATIENTS" in environ:
        medications = [p.strip() for p in environ["MEDICATION_PATIENTS"].split(",") if p.strip()]
        if not medications:
            problems.append("MEDICATION_PATIENTS is set but names no patient")
            medications = DEFAULT_MEDICATION_PATIENTS
    return condition, medications, problems


CONDITION_PATIENT, MEDICATION_PATIENTS, COHORT_PROBLEMS = parse_cohort(os.environ)

# (patient, question).
CASES = [(CONDITION_PATIENT, q) for q in (
    "any ear problems?",
    "any feeding problems?",
    "any nutritional problems?",
    "what are the patient's diagnoses?",
    "any mouth or swallowing problems?",
    "what active conditions does the patient have?",
)] + [(patient, "What medications is this patient currently taking?")
      for patient in MEDICATION_PATIENTS]


def req(path, data=None, method="GET"):
    r = urllib.request.Request(
        BASE + path,
        data=(json.dumps(data).encode() if data is not None else None),
        method=method)
    r.add_header("Authorization", "Basic " + AUTH)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(r, timeout=300) as resp:
        b = resp.read()
        return json.loads(b) if b else {}


def unresolved_patients():
    """Every distinct CASES patient the instance at BASE answers 404 for.

    Only a 404 means the patient is absent. Any other HTTP error — a 401 from wrong credentials, a
    5xx — propagates, because telling the operator to pick a different cohort would send them after
    the wrong fault. Either way it is raised before `run` touches a GP.
    """
    unresolved = []
    for patient in sorted({patient for patient, _ in CASES}):
        try:
            req("/patient/" + patient)
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
            unresolved.append(patient)
    return unresolved


def get_gp(name):
    rows = req("/systemsetting?q=%s&v=full" % name).get("results", [])
    return (rows[0]["uuid"], rows[0].get("value")) if rows else (None, None)


def set_gp(name, value):
    uuid, _ = get_gp(name)
    if uuid:
        req("/systemsetting/" + uuid, {"value": str(value)}, "POST")


def verdict_tag(reference):
    """What the wire published for ONE citation, or a STRING for the two cases that are not verdicts.

    Both callers in this directory tag through it — this module's `search`, and
    `codes_only_order_grounding.py`, which reads the body itself but does not respell these
    rules — so a rename of either wire key below lands in one place.

    A reference-group citation's `grounded` is always null on the wire, whatever the pass
    concluded (issue #201), so a clause-scope flip on one is NOT observable from here. Those
    cells are tagged `withheld` rather than printed as None, which would read as "unverified"
    and let a caller's gate be quoted over citations it is structurally blind to. The tallies in
    `run` are unaffected because `withheld` is a STRING: the True/False classes cannot match it,
    and the #302 null-side classes test `is None`. Do not change the tag to None — the null->False
    class would then start counting withheld reference citations as #302 regressions. ("every class
    needs a True on one side" was the old reason and it is no longer true: null->False needs none.)

    `attached` is a third tag for the same reason: since issue #305 a chart-group citation the
    MODULE attached carries grounded=null because there is no claim of the model's to check, and
    printing that as None reads as "unverified" — the distinction that whole issue turns on. A
    STRING again, so the True/False classes cannot match it and the #302 null-side classes, which
    test `is None`, cannot either; every counted class tests `is True` or `is False` on at least one
    side, so no tally moves. Do not tag it None.

    It deliberately does NOT share drift-metric's `model_cited` predicate, which is the one home
    of the rule for the scorers that EXCLUDE such a citation. This directory tags rather than
    excludes — the cell still has to appear in the per-cell table a human reads — so a shared
    exclusion would obscure exactly what this tag is for. Different directory, no import path.
    """
    if reference.get("attachedByTheModule"):
        return "attached"
    if reference.get("group") == "reference":
        return "withheld"
    return reference.get("grounded")


def search(patient, question):
    d = req("/chartsearchai/search", {"patient": patient, "question": question}, "POST")
    verdicts = {}
    for r in (d.get("references") or []):
        # `verdict_tag`'s docstring carries why neither of its two tags may be None.
        verdicts[r.get("index")] = verdict_tag(r)
    return (d.get("answer", "") or "").strip(), verdicts


def run():
    # FIRST, before any GP is read or written (#240). The arms are switched by writing a GP
    # before the first search, and the restore in the finally below is best-effort. So a cohort
    # that does not resolve here would change the instance's configuration and measure nothing.
    if COHORT_PROBLEMS:
        raise SystemExit("ERROR: %s.\nRefusing to run: no request was sent. Unset the variable to"
                         " use its default." % "; ".join(COHORT_PROBLEMS))
    unresolved = unresolved_patients()
    if unresolved:
        raise SystemExit(
            "ERROR: %d cohort patient(s) are not found (HTTP 404) on %s:\n  %s\n"
            "Refusing to run: no GP was read or written. Pass patients that exist on this instance\n"
            "as CONDITION_PATIENT / MEDICATION_PATIENTS; the CASES comment says what they must carry."
            % (len(unresolved), BASE, "\n  ".join(unresolved)))
    orig_uuid, orig = get_gp(GP)
    grounding = (get_gp(GROUNDING_GP)[1] or "").strip().lower()
    entailment = (get_gp(ENTAILMENT_GP)[1] or "").strip().lower()
    print("harness BASE=%s  %s baseline value=%r" % (BASE, GP, orig))
    print("regime: %s=%s  %s=%s" % (GROUNDING_GP, grounding or "unset",
                                    ENTAILMENT_GP, entailment or "unset"))
    if grounding != "true":
        print("!! grounding is OFF — every verdict below is null and no class here can fire.")
    elif entailment != "true":
        print("!! entailment is OFF — the #302 withholding is gated on it, so the withheld tallies")
        print("   below are NOT measuring it. Turn it on to exercise these classes.")
    print("")
    regressions, wins = 0, 0
    demoted_wins, demoted_regressions = 0, 0
    try:
        for patient, q in CASES:
            set_gp(GP, "false")
            s_ans, s = search(patient, q)
            set_gp(GP, "true")
            c_ans, c = search(patient, q)
            print("Q: %s" % q)
            if s_ans != c_ans:
                print("  !! answer differs between modes (LLM nondeterminism) — verdict A/B is confounded")
            idxs = sorted(set(s) | set(c))
            cells = []
            for i in idxs:
                tag = ""
                if s.get(i) is True and c.get(i) is False:
                    tag = "<REGRESSION"
                    regressions += 1
                elif s.get(i) is False and c.get(i) is True:
                    tag = "<win"
                    wins += 1
                elif s.get(i) is None and c.get(i) is True and i in s:
                    tag = "<win(demoted)"
                    demoted_wins += 1
                elif s.get(i) is True and c.get(i) is None and i in c:
                    tag = "<REGRESSION(demoted)"
                    demoted_regressions += 1
                elif s.get(i) is None and c.get(i) is False and i in s:
                    tag = "<REGRESSION(publishes unsupported)"
                    demoted_regressions += 1
                cells.append("[%s] sent=%s clause=%s %s" % (i, s.get(i), c.get(i), tag))
            print("  " + ("\n  ".join(cells) if cells else "(no citations)"))
            print("  answer: %s\n" % (c_ans[:160] + ("…" if len(c_ans) > 160 else "")))
    finally:
        if orig_uuid:
            set_gp(GP, orig)
            print("restored %s -> %r" % (GP, get_gp(GP)[1]))
    print("\nTALLY across %d queries: clause-scope WINS=%d  REGRESSIONS=%d (vs sentence-scope safe baseline)"
          % (len(CASES), wins, regressions))
    print("  of the demoted kind (sentence-scope null, see #302): WINS=%d  REGRESSIONS=%d"
          % (demoted_wins, demoted_regressions))
    print("GATE for a candidate scoping: must ground [89] on 'any ear problems?' — as a win of EITHER")
    print("kind, since #302 makes that compound sentence's sentence-scope cell null rather than false —")
    print("AND produce ZERO regressions of either kind across all cases.")
    if CONDITION_PATIENT != DEFAULT_CONDITION_PATIENT:
        print("!! CONDITION_PATIENT is overridden: [89] is a citation index on %s's chart, so the"
              % DEFAULT_CONDITION_PATIENT[:8])
        print("   first GATE criterion does not apply to %s. Read its cells instead." % CONDITION_PATIENT)


def selftest():
    """Offline guard for the ORDER `run` does things in (#240). No server, no model.

    It drives the real `run` over the real CASES, with `req` swapped for an in-memory stub that
    records every request. Its runs:

      * every one of the cohort's patients 404s. `run` must REFUSE, name each of them, and have
        sent NO `/systemsetting` request of any kind — not a write, and not the read either,
        because the refusal has to come before the harness touches the GPs at all;
      * only CONDITION_PATIENT 404s and the medication patients resolve, which is the dev
        standalone's own shape (#240). `run` must refuse the same way, naming that patient and no
        other. Without this run, a `run` that refused only a WHOLLY absent cohort, or dropped the
        missing patient and ran the rest, would pass the one above;
      * one patient answers 401. `run` must raise that error rather than call the patient
        missing, again before any `/systemsetting` request;
      * every patient resolves. `run` must complete, write the clause-scope GP both ways, and
        restore it. This is the control: without it, a `run` that always refused would pass the
        runs above;
      * `parse_cohort` over overrides that are set but name no patient must report a problem, and
        `run` given one must refuse before sending any request at all. The unset and the padded
        cases are its controls.

    `req` and COHORT_PROBLEMS are restored in a finally, because `codes_only_order_grounding.py` imports this module
    and reuses its `req`.
    """
    import contextlib
    import io

    patients = sorted({patient for patient, _ in CASES})

    def stub_server(missing, status=404):
        calls = []
        gps = {GP: ["gp-clause", "false"], GROUNDING_GP: ["gp-grounding", "true"],
               ENTAILMENT_GP: ["gp-entailment", "true"]}

        def fake_req(path, data=None, method="GET"):
            calls.append((method, path, data))
            if path.startswith("/patient/"):
                if path[len("/patient/"):] in missing:
                    raise urllib.error.HTTPError(BASE + path, status, "stub refusal", None, None)
                return {"uuid": path[len("/patient/"):]}
            if path.startswith("/systemsetting?q="):
                row = gps.get(path[len("/systemsetting?q="):].split("&")[0])
                return {"results": [{"uuid": row[0], "value": row[1]}] if row else []}
            if path.startswith("/systemsetting/"):
                for row in gps.values():
                    if row[0] == path[len("/systemsetting/"):]:
                        row[1] = data["value"]
                return {}
            if path == "/chartsearchai/search":
                return {"answer": "stub answer", "references": [{"index": 1, "grounded": True}]}
            raise AssertionError("the stub does not serve %s %s" % (method, path))
        return fake_req, calls, gps

    real_req = globals()["req"]
    real_problems = globals()["COHORT_PROBLEMS"]
    try:
        globals()["req"], calls, _ = stub_server(set(patients))
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                run()
        except SystemExit as e:
            for uuid in patients:
                assert uuid in str(e), "the refusal must name every missing patient: %s" % e
        else:
            raise AssertionError("an unresolved cohort must refuse, not run")
        touched = [c for c in calls if c[1].startswith("/systemsetting")]
        assert not touched, "the refusal came after a GP request: %s" % touched

        resolving = [p for p in patients if p != CONDITION_PATIENT]
        assert resolving, "the partial run needs a cohort patient that resolves: %s" % patients
        globals()["req"], calls, _ = stub_server({CONDITION_PATIENT})
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                run()
        except SystemExit as e:
            assert CONDITION_PATIENT in str(e), "the refusal must name the missing patient: %s" % e
            for uuid in resolving:
                assert uuid not in str(e), "the refusal named a patient that resolves: %s" % e
        else:
            raise AssertionError("a cohort with one patient missing must refuse, not run the rest")
        touched = [c for c in calls if c[1].startswith("/systemsetting")]
        assert not touched, "the partial refusal came after a GP request: %s" % touched

        globals()["req"], calls, _ = stub_server({patients[-1]}, status=401)
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                run()
        except urllib.error.HTTPError as e:
            assert e.code == 401, "the credential error must surface as itself: %s" % e
        except SystemExit as e:
            raise AssertionError("a 401 was reported as a missing patient: %s" % e)
        else:
            raise AssertionError("a 401 must not be read as a cohort that resolves")
        touched = [c for c in calls if c[1].startswith("/systemsetting")]
        assert not touched, "the 401 surfaced after a GP request: %s" % touched

        globals()["req"], calls, gps = stub_server(set())
        with contextlib.redirect_stdout(io.StringIO()):
            run()
        writes = [c[2]["value"] for c in calls if c[1] == "/systemsetting/gp-clause"]
        assert {"false", "true"} <= set(writes), "both arms must be written: %s" % writes
        assert writes[-1] == "false" and gps[GP][1] == "false", \
            "the baseline must be restored last: %s" % writes
        searches = [c for c in calls if c[1] == "/chartsearchai/search"]
        assert len(searches) == 2 * len(CASES), "one search per case per arm: %d" % len(searches)

        for environ in ({"CONDITION_PATIENT": ""}, {"CONDITION_PATIENT": "  "},
                        {"MEDICATION_PATIENTS": ""}, {"MEDICATION_PATIENTS": " , "}):
            assert parse_cohort(environ)[2], "an override naming no patient must be refused: %r" % environ
        assert parse_cohort({}) == (DEFAULT_CONDITION_PATIENT, DEFAULT_MEDICATION_PATIENTS, []), \
            "an unset override must take its default: %r" % (parse_cohort({}),)
        padded = parse_cohort({"CONDITION_PATIENT": " c ", "MEDICATION_PATIENTS": " a , ,b "})
        assert padded == ("c", ["a", "b"], []), "an override is stripped: %r" % (padded,)
        globals()["req"], calls, _ = stub_server(set())
        globals()["COHORT_PROBLEMS"] = parse_cohort({"MEDICATION_PATIENTS": ","})[2]
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                run()
        except SystemExit as e:
            assert "MEDICATION_PATIENTS" in str(e), "the refusal must name the override: %s" % e
        else:
            raise AssertionError("an override naming no patient must refuse, not run")
        assert not calls, "the override refusal came after a request: %s" % calls
    finally:
        globals()["req"] = real_req
        globals()["COHORT_PROBLEMS"] = real_problems
    print("selftest OK")


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        selftest()
    elif sys.argv[1:]:
        # A mistyped `--selftest` must not fall through to the live run, which writes GPs.
        sys.exit("usage: %s [--selftest]" % sys.argv[0])
    else:
        run()
