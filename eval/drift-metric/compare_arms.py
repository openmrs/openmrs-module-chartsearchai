#!/usr/bin/env python3
"""Same-environment A/B over two capture directories: per-cell verdict-lead
classification (score_directness.classify — the versioned metric definition)
diffed between a baseline arm and a candidate arm.

Built for the #107 verdict-guard gate (2026-07-29), where the human F1/drift
gold proved unremappable (the 3.7.1 standalone's demo DB is a different
synthetic cohort — see the README's "3.7.1 cohort" note) and the strongest
available instrument was a pure-prompt A/B: two builds differing ONLY in the
change under test, identical patients/queries/GPs, captured with
capture_probe_yesno.sh (CAPTURE_PATIENTS=... CAPTURE_TIER_B=0).

Usage: compare_arms.py <baseline_capture_dir> <candidate_capture_dir>

Reports, over the cells present in BOTH arms (medications cells excluded —
wh-questions are never verdict-scored): the verdict-class distribution per
arm, directness (verdict-led fraction), every class flip with answer
snippets for manual reading, and any "records do not address" leads in the
candidate arm — the #107 guard's template, whose appearance on presence
topics would mean the guard bled beyond its safety/suitability scope.

Interpretation is manual by design: single-sample captures carry decode
nondeterminism, so read each flip (opposite-direction flips on borderline
topics are the noise floor; same-direction, same-form clusters are signal).
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from score_directness import classify

GUARD_TEMPLATE = re.compile(r"records do not address", re.I)


def load(directory):
    cells = {}
    for f in sorted(os.listdir(directory)):
        if not f.endswith(".json"):
            continue
        try:
            cells[f[:-5]] = json.load(open(os.path.join(directory, f))).get("answer", "") or ""
        except Exception as e:  # unreadable capture: surface, never crash the report
            cells[f[:-5]] = "<<UNREADABLE: %s>>" % e
    return cells


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__.strip().split("\n")[0] + "\n\nusage: compare_arms.py <baseline_dir> <candidate_dir>")
    base, cand = load(sys.argv[1]), load(sys.argv[2])
    keys = sorted(set(base) & set(cand))
    only_one = sorted(set(base) ^ set(cand))
    if only_one:
        print("WARN: %d cells present in only one arm (excluded): %s" % (len(only_one), only_one[:6]))

    flips, guard_leads = [], []
    counts = {"B": {}, "A": {}}
    scored = 0
    for k in keys:
        if k.split("__")[1] == "medications":
            continue
        scored += 1
        cb, ca = classify(base[k]), classify(cand[k])
        counts["B"][cb] = counts["B"].get(cb, 0) + 1
        counts["A"][ca] = counts["A"].get(ca, 0) + 1
        if cb != ca:
            flips.append((k, cb, ca, base[k][:90], cand[k][:90]))
        if GUARD_TEMPLATE.search(cand[k]):
            guard_leads.append((k, cand[k][:110]))

    print("cells compared: %d  (+%d medications cells unscored)" % (scored, len(keys) - scored))
    print("\nverdict-class distribution  [B=baseline -> A=candidate]")
    for cls in ("YES", "NO", "CANNOT", "NONE"):
        print("  %-7s B=%2d  A=%2d" % (cls, counts["B"].get(cls, 0), counts["A"].get(cls, 0)))

    direct = lambda c: c.get("YES", 0) + c.get("NO", 0) + c.get("CANNOT", 0)
    print("\ndirectness (verdict-led / scored): B=%d/%d  A=%d/%d"
          % (direct(counts["B"]), scored, direct(counts["A"]), scored))

    print("\nclass flips: %d" % len(flips))
    for k, cb, ca, tb, ta in flips:
        print("  %-50s B:%-6s -> A:%-6s" % (k, cb, ca))
        print("      B: %s" % tb)
        print("      A: %s" % ta)

    print("\n'records do not address' leads in the candidate arm: %d" % len(guard_leads))
    for k, t in guard_leads:
        print("  %-50s %s" % (k, t))


if __name__ == "__main__":
    main()
