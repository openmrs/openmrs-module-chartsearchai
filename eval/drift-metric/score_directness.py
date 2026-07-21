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
that file. Reports directness, expected-lead match, and SAFETY violations: a YES lead
on a cell marked safety=true (no explicit diagnosis record exists -> a bare "Yes" is
an unsafe inference upgrade). The 'medications' topic (wh-question) is never scored.

Amendment (2026-07-21, user-approved): verdict_gold_yesno.json overrides the
present->YES expectation per cell. Present cells whose entire on-topic universe is
obs/lab records (no Condition:/Diagnosis:/Allergy/Program record) expect a
record-grounded NO-family lead instead — topic-presence is not verdict truth there.

The regexes are the gate's metric definition — versioned here on purpose. Changing them
invalidates any thresholds locked against them; re-quote baselines after any edit.
"""
import json, sys, glob, os, re

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
    cap = sys.argv[1]
    gold_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "metric_gold.rc2.json")
    probe_path = sys.argv[3] if len(sys.argv) > 3 else os.path.join(HERE, "probe_gold_yesno.json")
    gold = json.load(open(gold_path))
    probes = {k: v for k, v in json.load(open(probe_path)).items() if not k.startswith("_")} \
        if os.path.exists(probe_path) else {}
    override_path = os.path.join(HERE, "verdict_gold_yesno.json")
    verdict_override = {k: v for k, v in json.load(open(override_path)).items() if not k.startswith("_")} \
        if os.path.exists(override_path) else {}

    a_n = a_direct = a_correct = 0
    b_n = b_direct = b_match = 0
    a_fail, b_fail, safety_viol = [], [], []
    for f in sorted(glob.glob(cap + "/*.json")):
        base = os.path.basename(f)[:-5]
        if "__" not in base:
            continue
        uuid, topic = base.split("__", 1)
        cell = uuid + "|" + topic
        try:
            d = json.load(open(f))
        except (ValueError, OSError) as e:
            print("WARN: unreadable capture %s (%s) — skipped" % (base, e))
            continue
        if "references" not in d and "answer" not in d:
            print("WARN: capture %s has neither references nor answer (error body?) — skipped" % base)
            continue
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
