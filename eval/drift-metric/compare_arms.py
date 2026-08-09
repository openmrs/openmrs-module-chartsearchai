#!/usr/bin/env python3
"""Same-environment A/B over two capture directories: per-cell verdict-lead
classification (score_directness.classify — the versioned metric definition)
diffed between a baseline arm and a candidate arm.

THIS IS A REPORT, NOT A GATE. It has no pass/fail threshold and never had one, and
its exit 0 means "the two captures were readable", not "the candidate is good". The
docstring below has always said interpretation is manual; #179 recorded that the
distinction was being lost anyway, because a script that prints a score and exits 0
reads like a gate to anything automated. So the exit code now carries integrity only
(see below) and the final line says which it is. Do not cite this script's numbers as
a verdict; cite the flips you read.

Built for the #107 verdict-guard gate (2026-07-29), where the human F1/drift
gold proved unremappable (the 3.7.1 standalone's demo DB is a different
synthetic cohort — see the README's "3.7.1 cohort" note) and the strongest
available instrument was a pure-prompt A/B: two builds differing ONLY in the
change under test, identical patients/queries/GPs, captured with
capture_probe_yesno.sh (CAPTURE_PATIENTS=... CAPTURE_TIER_B=0).

Usage: compare_arms.py <baseline_capture_dir> <candidate_capture_dir>
       compare_arms.py --selftest

Reports, over the cells present in BOTH arms (medications cells excluded —
wh-questions are never verdict-scored): the verdict-class distribution per
arm, TWO lead-rate lines (see below), every class flip with answer
snippets for manual reading, and any "records do not address" leads in the
candidate arm — the #107 guard's template, whose appearance on presence
topics would mean the guard bled beyond its safety/suitability scope.

TWO rates, because this file used to print one under both names. The single line it
printed was labelled "directness (verdict-led / scored)" and counted YES+NO+CANNOT,
which is `score_directness`'s DIRECTNESS but NOT `score_probe_safety`'s VERDICT-LED —
that scorer excludes CANNOT on purpose ("a hedge, not a verdict, and must not score as
the goal state"). Two live definitions sharing one label is how #107's quoted
"62/64 vs 63/64" became unattributable: the figure is reproducible under the directness
definition and unknown under the other, and those captures are gone. Both are printed
now, separately named, so neither definition can hide inside the other and an old
number stays comparable to the line it actually came from.

Exit codes, mirroring score_probe_safety's convention (0 clean / 3 integrity):
  0  both arms read cleanly — the flips below are worth reading
  3  integrity problems, printed above the numbers: a missing CAPTURE_DONE (a killed
     run still yields a full-looking table over a biased prefix), or a cell that would
     not parse. The numbers are STILL printed for a human, but an unreadable cell
     classifies as NONE and stays in the denominator, so a partial capture reads as a
     directness drop that no prompt change caused.

Interpretation is manual by design: single-sample captures carry decode
nondeterminism, so read each flip (opposite-direction flips on borderline
topics are the noise floor; same-direction, same-form clusters are signal).
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from score_directness import classify

HERE = os.path.dirname(os.path.abspath(__file__))
GUARD_TEMPLATE = re.compile(r"records do not address", re.I)

UNREADABLE = "<<UNREADABLE"


def load(directory):
    """Returns (cells, done). `done` is the CAPTURE_DONE marker, which this script used to
    ignore entirely — a run killed part-way then produced a table over whichever cells the
    pinned order happened to reach first."""
    cells = {}
    for f in sorted(os.listdir(directory)):
        if not f.endswith(".json"):
            continue
        try:
            cells[f[:-5]] = json.load(open(os.path.join(directory, f))).get("answer", "") or ""
        except Exception as e:  # unreadable capture: surface, never crash the report
            cells[f[:-5]] = "%s: %s>>" % (UNREADABLE, e)
    return cells, os.path.isfile(os.path.join(directory, "CAPTURE_DONE"))


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__.strip().split("\n")[0] + "\n\nusage: compare_arms.py <baseline_dir> <candidate_dir>")
    (base, base_done), (cand, cand_done) = load(sys.argv[1]), load(sys.argv[2])
    keys = sorted(set(base) & set(cand))
    only_one = sorted(set(base) ^ set(cand))
    if only_one:
        print("WARN: %d cells present in only one arm (excluded): %s" % (len(only_one), only_one[:6]))

    problems = []
    for label, done, d in (("baseline", base_done, sys.argv[1]), ("candidate", cand_done, sys.argv[2])):
        if not done:
            problems.append("no CAPTURE_DONE in the %s arm (%s) — the run may have been killed "
                            "mid-way, and a partial arm still produces a full-looking table over "
                            "a biased prefix" % (label, d))
    bad = sorted(k for k in keys if base[k].startswith(UNREADABLE) or cand[k].startswith(UNREADABLE))
    if bad:
        problems.append("%d unreadable cell(s), which classify as NONE and stay in the "
                        "denominator — that reads as a directness drop no prompt change "
                        "caused: %s" % (len(bad), bad[:4]))
    for p in problems:
        print("  !! %s" % p)

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

    # Two definitions, two lines, each named for the scorer that owns it. Neither is a
    # substitute for the other and the gap between them IS the CANNOT count.
    directness = lambda c: c.get("YES", 0) + c.get("NO", 0) + c.get("CANNOT", 0)
    verdict_led = lambda c: c.get("YES", 0) + c.get("NO", 0)
    print("\ndirectness  (lead != NONE, score_directness's definition): B=%d/%d  A=%d/%d"
          % (directness(counts["B"]), scored, directness(counts["A"]), scored))
    print("verdict-led (YES/NO only, score_probe_safety's definition): B=%d/%d  A=%d/%d"
          % (verdict_led(counts["B"]), scored, verdict_led(counts["A"]), scored))
    print("  the difference between the two lines is the CANNOT hedge: B=%d A=%d"
          % (counts["B"].get("CANNOT", 0), counts["A"].get("CANNOT", 0)))

    print("\nclass flips: %d" % len(flips))
    for k, cb, ca, tb, ta in flips:
        print("  %-50s B:%-6s -> A:%-6s" % (k, cb, ca))
        print("      B: %s" % tb)
        print("      A: %s" % ta)

    print("\n'records do not address' leads in the candidate arm: %d" % len(guard_leads))
    for k, t in guard_leads:
        print("  %-50s %s" % (k, t))

    if problems:
        print("\n!! integrity problems above — exiting 3 so automation cannot read this as a "
              "clean comparison.")
        sys.exit(3)
    print("\nREPORT (not a gate): exit 0 means both arms read cleanly, NOT that the candidate "
          "passed. Read the flips.")


# Real captures, not synthesized ones: these are the committed probe-safety fixtures, whose
# provenance is recorded in fixtures/probe-safety/PROVENANCE.md. Only the CAPTURE_DONE marker is
# manipulated (removed, in a copy) — that absence is exactly the condition being pinned.
FIXTURES = os.path.join(HERE, "fixtures", "probe-safety")


def _run(args):
    p = subprocess.Popen([sys.executable, os.path.abspath(__file__)] + args,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return p.communicate()[0].decode("utf-8", "replace"), p.returncode


def selftest():
    if not os.path.isdir(FIXTURES):
        sys.exit("ERROR: no fixtures at %s — nothing to check" % FIXTURES)
    clean, no_marker = os.path.join(FIXTURES, "shipped-clean"), None
    cand = os.path.join(FIXTURES, "unsupported-no")
    failures = []

    def check(name, args, want_exit, wanted, unwanted=()):
        out, rc = _run(args)
        if rc != want_exit:
            failures.append("%s: exit %d, want %d\n%s" % (name, rc, want_exit, out))
            return
        for w in wanted:
            if w not in out:
                failures.append("%s: missing %r\n%s" % (name, w, out))
        for n in unwanted:
            if n in out:
                failures.append("%s: unexpected %r\n%s" % (name, n, out))
        if not any(f.startswith(name + ":") for f in failures):
            print("  ok  %-34s exit=%d" % (name, want_exit))

    # Both definitions are printed and named, and the CANNOT gap is stated. This is the item
    # #179 records: one line labelled "directness (verdict-led / scored)" served both.
    check("two-named-rates", [clean, cand], 0,
          ["directness  (lead != NONE, score_directness's definition):",
           "verdict-led (YES/NO only, score_probe_safety's definition):",
           "the difference between the two lines is the CANNOT hedge:",
           "REPORT (not a gate)"])

    # A missing CAPTURE_DONE must exit 3, not 0. The numbers still print.
    try:
        no_marker = tempfile.mkdtemp(prefix="compare-arms-nodone-")
        for f in os.listdir(cand):
            if f != "CAPTURE_DONE":
                shutil.copy(os.path.join(cand, f), no_marker)
        check("missing-CAPTURE_DONE", [clean, no_marker], 3,
              ["no CAPTURE_DONE in the candidate arm", "cells compared:"],
              ["REPORT (not a gate)"])
    finally:
        if no_marker:
            shutil.rmtree(no_marker, ignore_errors=True)

    # An unreadable cell must be called out, not silently classified NONE in the denominator.
    bad = None
    try:
        bad = tempfile.mkdtemp(prefix="compare-arms-bad-")
        for f in os.listdir(cand):
            shutil.copy(os.path.join(cand, f), bad)
        truncated = sorted(f for f in os.listdir(bad) if f.endswith(".json"))[0]
        with open(os.path.join(bad, truncated), "w") as fh:
            fh.write("{ not json")
        check("unreadable-cell", [clean, bad], 3,
              ["unreadable cell(s), which classify as NONE and stay in the denominator"])
    finally:
        if bad:
            shutil.rmtree(bad, ignore_errors=True)

    if failures:
        for f in failures:
            print("\nFAIL %s" % f)
        sys.exit("selftest FAILED (%d)" % len(failures))
    print("selftest OK (3 cases)")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        selftest()
    else:
        main()
