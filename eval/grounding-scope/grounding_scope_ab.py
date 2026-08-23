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

Issue #284 adds one more cause of a sentence-side null, on the same entailment
gate: a chart citation whose claim also rests on a module-supplied safety
finding has the judge's NEGATIVE withheld. So a null-side count is an upper
bound over that too, and this harness cannot attribute a cell between the two.
Its six cases are condition-shaped rather than drug-safety questions, so a
finding is unlikely to be injected at all -- but that has not been re-measured,
and "unlikely" is not "cannot". Do not quote a tally here over a change to the
#284 rule.

Only CHART-group citations are measurable here: a reference-group citation
publishes no verdict at all (issue #201), so its cells read `withheld` and a
scoping flip on one cannot be seen from the wire. The gate below is therefore a
statement about chart citations.

The GP is saved before and restored after. Answers are grounding-independent,
so a differing answer between modes signals LLM nondeterminism (reported).

Usage:  python3 eval/grounding-scope/grounding_scope_ab.py
Env:    BASE (default http://localhost:8081/openmrs/ws/rest/v1), OMRS_USER, OMRS_PASS.
"""
import base64
import json
import os
import urllib.request

BASE = os.environ.get("BASE", "http://localhost:8081/openmrs/ws/rest/v1")
AUTH = base64.b64encode(
    ("%s:%s" % (os.environ.get("OMRS_USER", "admin"),
               os.environ.get("OMRS_PASS", "Admin123"))).encode()).decode()
GP = "chartsearchai.grounding.clauseScoped"
GROUNDING_GP = "chartsearchai.grounding.enabled"
ENTAILMENT_GP = "chartsearchai.grounding.entailment.enabled"

# (patient, question). 165497e8 = Sarah Taylor: malnutrition recorded as BOTH an
# active condition AND a provisional primary diagnosis (the compound-sentence
# shape clause-scoping targets), microstomia, etc.
CASES = [
    ("165497e8-13e0-4fa4-8190-8e6fa067c4b7", "any ear problems?"),
    ("165497e8-13e0-4fa4-8190-8e6fa067c4b7", "any feeding problems?"),
    ("165497e8-13e0-4fa4-8190-8e6fa067c4b7", "any nutritional problems?"),
    ("165497e8-13e0-4fa4-8190-8e6fa067c4b7", "what are the patient's diagnoses?"),
    ("165497e8-13e0-4fa4-8190-8e6fa067c4b7", "any mouth or swallowing problems?"),
    ("165497e8-13e0-4fa4-8190-8e6fa067c4b7", "what active conditions does the patient have?"),
    # Issue #302's own population and question: two patients whose medication answer is a
    # colon-less list of two active orders, which is a compound claim unit in sentence scope.
    ("83f95445-d471-4e9c-b10e-a89b6632dbe8", "What medications is this patient currently taking?"),
    ("e30bc8f0-08bb-406c-986a-2b153a495603", "What medications is this patient currently taking?"),
]


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


def get_gp(name):
    rows = req("/systemsetting?q=%s&v=full" % name).get("results", [])
    return (rows[0]["uuid"], rows[0].get("value")) if rows else (None, None)


def set_gp(name, value):
    uuid, _ = get_gp(name)
    if uuid:
        req("/systemsetting/" + uuid, {"value": str(value)}, "POST")


def search(patient, question):
    # A reference-group citation's `grounded` is always null on the wire, whatever the pass
    # concluded (issue #201), so a clause-scope flip on one is NOT observable from here. Those
    # cells are tagged `withheld` rather than printed as None, which would read as "unverified"
    # and let this harness's gate be quoted over citations it is structurally blind to.
    # The tallies below are unaffected because `withheld` is a STRING: the True/False classes cannot
    # match it, and the #302 null-side classes test `is None`. Do not change the tag to None — the
    # null->False class would then start counting withheld reference citations as #302 regressions.
    # ("every class needs a True on one side" was the old reason and it is no longer true: null->False
    # needs none.)
    d = req("/chartsearchai/search", {"patient": patient, "question": question}, "POST")
    verdicts = {}
    for r in (d.get("references") or []):
        withheld = r.get("group") == "reference"
        verdicts[r.get("index")] = "withheld" if withheld else r.get("grounded")
    return (d.get("answer", "") or "").strip(), verdicts


def run():
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


if __name__ == "__main__":
    run()
