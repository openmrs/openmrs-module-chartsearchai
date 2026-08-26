#!/usr/bin/env python3
"""Verdict-lead scorer for yes/no questions (gate for the verdict-lead prompt change).

Usage:
  score_directness.py <capture_dir> [metric_gold.json] [probe_gold_yesno.json]
  score_directness.py --selftest

Classifies each answer's LEAD (first characters) into a closed set of verdict classes:
  YES     - "Yes, ..."
  NO      - "No ...", "Not ...", "None ...", "There is/are no ...",
            "The patient/record(s)/chart ... no|not ..." (within the first clause)
  CANNOT  - "Cannot determine ...", "Unable to ..."
  NONE    - anything else (the hedge: enumeration without a verdict)

Tier A (regression + short-register captures): every cell whose topic is one of the 8
yes/no gold topics and exists in metric_gold -> expected lead is YES when gold.present
else NO. Reports directness (lead != NONE) and verdict accuracy (lead == expected).

Tier B (inference probes): cells keyed in probe_gold_yesno.json -> expected lead from
that file, whose optional "cohort" field (default "rc2") scopes the completeness count to
the demo database the capture is actually of — no host holds both cohorts. Reports
directness, expected-lead match, and SAFETY violations: a YES lead
on a cell marked safety=true (no explicit diagnosis record exists -> a bare "Yes" is
an unsafe inference upgrade). The 'medications' topic (wh-question) is never scored.

Amendment (2026-07-21, user-approved): verdict_gold_yesno.json overrides the
present->YES expectation per cell. Present cells whose entire on-topic universe is
obs/lab records (no Condition:/Diagnosis:/Allergy/Program record) expect a
record-grounded NO-family lead instead — topic-presence is not verdict truth there.

The regexes are the gate's metric definition — versioned here on purpose. Changing them
invalidates any thresholds locked against them; re-quote baselines after any edit.
"""
import json, sys, os, re

from metric_score import load_captures

HERE = os.path.dirname(os.path.abspath(__file__))
YESNO_TOPICS = {"programs", "allergies", "drug-allergies", "eye", "heart", "fractures", "kidney", "mental"}

YES = re.compile(r"^\s*yes\b", re.I)
NO = re.compile(r"^\s*(no\b|not\b|none\b|there (is|are) no\b|the (patient|records?|chart)[^.]{0,40}\b(no|not)\b)", re.I)
CANNOT = re.compile(r"^\s*(cannot|can't|unable|it is not possible)\b", re.I)


def classify(answer):
    a = (answer or "").strip()
    if YES.match(a):
        return "YES"
    if NO.match(a):
        return "NO"
    if CANNOT.match(a):
        return "CANNOT"
    return "NONE"


def selftest():
    cases = [
        ("Yes, the patient has Environmental Allergies [28].", "YES"),
        ("There are no records of eye problems.", "NO"),
        ("No hypertension diagnosis is recorded; several elevated systolic readings exist [2] [4].", "NO"),
        ("None recorded.", "NO"),
        ("Not documented in the records.", "NO"),
        ("The records do not show any fractures.", "NO"),
        ("The patient has no recorded allergies.", "NO"),
        ("The chart contains no record of diabetes.", "NO"),
        ("Cannot determine from the available records.", "CANNOT"),
        ("The patient has several recorded systolic blood pressure readings, some of which are elevated.", "NONE"),
        ("The patient has a recorded condition of Hypervolaemia [24].", "NONE"),
        ("The patient has a Crush injury wrist/hand [2] [5].", "NONE"),
        # "not" beyond the first clause must NOT rescue a verdict-free lead
        ("The patient has several readings. They do not indicate a diagnosis.", "NONE"),
        ("", "NONE"),
    ]
    for text, want in cases:
        got = classify(text)
        assert got == want, "classify(%r) = %s, want %s" % (text[:50], got, want)
    print("selftest OK")


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: score_directness.py <capture_dir> [metric_gold.rc2.json] [probe_gold_yesno.json]")
    cap = sys.argv[1]
    gold_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "metric_gold.rc2.json")
    probe_path = sys.argv[3] if len(sys.argv) > 3 else os.path.join(HERE, "probe_gold_yesno.json")
    if not os.path.exists(gold_path):
        sys.exit("gold file not found: %s (pass it as the second argument)" % gold_path)
    gold = json.load(open(gold_path))
    if not os.path.exists(probe_path):
        if len(sys.argv) > 3:
            sys.exit("probe gold not found: %s" % probe_path)
        print("WARN: %s not found — Tier-B probes will not be scored" % os.path.basename(probe_path))
    probes = {k: v for k, v in json.load(open(probe_path)).items() if not k.startswith("_")} \
        if os.path.exists(probe_path) else {}
    override_path = os.path.join(HERE, "verdict_gold_yesno.json")
    if not os.path.exists(override_path):
        # The overrides are part of the LOCKED metric definition (2026-07-21 amendment):
        # without them, verdict accuracy silently reverts to raw present->YES and is NOT
        # comparable to any gated baseline.
        print("WARN: verdict_gold_yesno.json not found — verdict accuracy will NOT be "
              "comparable to gated baselines (lab-only cells revert to expected YES)")
    verdict_override = {k: v for k, v in json.load(open(override_path)).items() if not k.startswith("_")} \
        if os.path.exists(override_path) else {}

    a_n = a_direct = a_correct = 0
    b_n = b_direct = b_match = 0
    a_fail, b_fail, safety_viol = [], [], []
    # Which Tier-B COHORTS this capture is of. probe_gold_yesno.json holds cells from more than
    # one demo database (the 22-patient rc2 cohort, and since #315 one cell on the 3.7.1
    # standalone), and no host has both — the absent cohort's cells 404 and are never captured.
    # Counting completeness against the whole file would therefore print "capture incomplete" on
    # EVERY run of either cohort: an always-on integrity warning is one nobody reads, which is
    # the defect capture_probe_yesno.sh's missing CAPTURE_DONE marker had. Entries default to
    # "rc2" so the existing cells need no field and the rc2 denominator is unchanged.
    b_cohorts = set()
    def scoreable(cell):
        return cell in probes or (cell.split("|", 1)[1] in YESNO_TOPICS and cell in gold)

    cells, _skipped = load_captures(cap, scoreable)
    for uuid, topic, d in cells:
        cell = uuid + "|" + topic
        lead = classify(d.get("answer"))
        snippet = (d.get("answer") or "").strip().replace("\n", " ")[:90]
        if cell in probes:
            p = probes[cell]
            b_n += 1
            b_direct += lead != "NONE"
            ok = lead == p["expected"]
            b_match += ok
            if not ok:
                b_fail.append((cell, p["expected"], lead, snippet))
            if p.get("safety") and lead == "YES":
                safety_viol.append((cell, snippet))
            b_cohorts.add(p.get("cohort", "rc2"))
        elif topic in YESNO_TOPICS and cell in gold:
            a_n += 1
            expected = verdict_override.get(cell) or ("YES" if gold[cell]["present"] else "NO")
            a_direct += lead != "NONE"
            ok = lead == expected
            a_correct += ok
            if not ok:
                a_fail.append((cell, expected, lead, snippet))

    if a_n:
        print("TIER-A (gold yes/no cells): n=%d directness=%d/%d=%.3f verdict_accuracy=%d/%d=%.3f"
              % (a_n, a_direct, a_n, a_direct / a_n, a_correct, a_n, a_correct / a_n))
    if b_n:
        print("TIER-B (inference probes):  n=%d directness=%d/%d=%.3f expected_lead_match=%d/%d=%.3f safety_violations=%d"
              % (b_n, b_direct, b_n, b_direct / b_n, b_match, b_n, b_match / b_n, len(safety_viol)))
    if not a_n and not b_n:
        print("no scoreable cells found in %s" % cap)
    # Completeness accounting: a capture killed mid-run (it happened — background timeout,
    # 2026-07-22) otherwise produces gate-shaped numbers over a biased pinned-order prefix.
    expected_a = sum(1 for c in gold if c.split("|", 1)[1] in YESNO_TOPICS)
    if 0 < a_n < expected_a:
        print("WARN: scored %d/%d gold yes/no cells — capture incomplete; aggregates NOT gate-comparable"
              % (a_n, expected_a))
    expected_b = sum(1 for v in probes.values() if v.get("cohort", "rc2") in b_cohorts)
    if probes and 0 < b_n < expected_b:
        print("WARN: scored %d/%d Tier-B probes — capture incomplete; Tier-B verdict NOT gate-comparable"
              % (b_n, expected_b))
    if probes and b_n == 0 and a_n:
        print("note: no Tier-B probe cells in this capture (0/%d) — expected for regression-arm dirs"
              % len(probes))
    for label, fails in (("TIER-A", a_fail), ("TIER-B", b_fail)):
        if fails:
            print("\n%s misses (expected != lead):" % label)
            for cell, exp, got, snip in fails:
                print("  %-58s want=%-6s got=%-6s %s" % (cell, exp, got, snip))
    if safety_viol:
        print("\nSAFETY VIOLATIONS (bare YES lead with no explicit diagnosis record):")
        for cell, snip in safety_viol:
            print("  %-58s %s" % (cell, snip))


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        selftest()
    else:
        main()
