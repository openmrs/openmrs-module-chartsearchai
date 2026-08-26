#!/usr/bin/env python3
"""Verdict-lead scorer for yes/no questions (gate for the verdict-lead prompt change).

Usage:
  score_directness.py [--cohort NAME[,NAME...]] <capture_dir> [metric_gold.json] [probe_gold_yesno.json]
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
that file, whose optional "cohort" field (default "rc2") names the demo database the cell
exists in — no host holds both, so the completeness count is scoped to one cohort or the
count is always short. Say WHICH with --cohort; without it the scope is INFERRED from the
cells the capture contains, which cannot tell a cohort that is not on this host from one
whose every cell failed, so a run without it prints the basis it used. Reports
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
import shutil
import subprocess
import tempfile

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
    cohort_scope_selftest()
    print("selftest OK")


def _run(args):
    """This scorer in a subprocess, so a selftest case reads exactly what an operator reads."""
    p = subprocess.Popen([sys.executable, os.path.abspath(__file__)] + args,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = p.communicate()[0].decode("utf-8", "replace")
    return out, p.returncode


def _capture_dir(cells, probes):
    """A capture directory holding exactly `cells` (keys of probe_gold_yesno.json), each answered
    with its own expected lead. Synthesized rather than a committed fixture because what is being
    checked is the completeness ARITHMETIC over cell KEYS — no answer text is involved, and a frozen
    capture would pin the gold file's cell list as well, which moves whenever a probe is added."""
    d = tempfile.mkdtemp(prefix="score-directness-cohort-")
    for cell in cells:
        uuid, topic = cell.split("|", 1)
        lead = "Yes, " if probes[cell]["expected"] == "YES" else "No, "
        with open(os.path.join(d, "%s__%s.json" % (uuid, topic)), "w") as fh:
            json.dump({"answer": lead + "selftest cell"}, fh)
    return d


def _tier_a_only_capture_dir(probes):
    """A capture directory holding ONE Tier-A gold yes/no cell and no Tier-B probe cell — the shape a
    regression arm has, and the shape the documented standalone invocation of capture_probe_yesno.sh
    produces (`CAPTURE_PATIENTS=<uuids>` with CAPTURE_TIER_B unset suppresses Tier B). Read out of
    the default gold file rather than hardcoded, so it cannot name a cell that file stopped holding."""
    gold = json.load(open(os.path.join(HERE, "metric_gold.rc2.json")))
    cells = [c for c in gold if not c.startswith("_")
             and c.split("|", 1)[1] in YESNO_TOPICS and c not in probes]
    assert cells, "metric_gold.rc2.json holds no yes/no gold cell outside probe_gold_yesno.json"
    d = tempfile.mkdtemp(prefix="score-directness-tier-a-")
    uuid, topic = cells[0].split("|", 1)
    with open(os.path.join(d, "%s__%s.json" % (uuid, topic)), "w") as fh:
        json.dump({"answer": "No, selftest cell"}, fh)
    return d


def cohort_scope_selftest():
    """The Tier-B completeness denominator, which is the one piece of integrity logic in this file
    that is neither a regex nor a printed number: a cohort's cells only exist on that cohort's host,
    so the count is scoped — and a scope INFERRED from the capture cannot see a cohort that captured
    nothing. Both halves are checked, plus the input that closes the inference's hole."""
    probe_path = os.path.join(HERE, "probe_gold_yesno.json")
    probes = {k: v for k, v in json.load(open(probe_path)).items() if not k.startswith("_")}
    by_cohort = {}
    for cell, v in probes.items():
        by_cohort.setdefault(v.get("cohort", "rc2"), []).append(cell)
    assert len(by_cohort) > 1, ("probe_gold_yesno.json now holds one cohort only — this case can no "
                               "longer observe the scoping it exists for; give it a second cohort "
                               "or delete it deliberately")
    main_cohort = max(by_cohort, key=lambda c: len(by_cohort[c]))
    other = min(by_cohort, key=lambda c: len(by_cohort[c]))
    total, n_main, n_other = len(probes), len(by_cohort[main_cohort]), len(by_cohort[other])
    dirs = []

    def check(name, args, want_exit, want, unwanted=()):
        out, rc = _run(args)
        assert rc == want_exit, "%s: exit %d, want %d\n%s" % (name, rc, want_exit, out)
        for w in want:
            assert w in out, "%s: missing %r in\n%s" % (name, w, out)
        for u in unwanted:
            assert u not in out, "%s: unexpected %r in\n%s" % (name, u, out)
        print("  ok  %-42s exit=%d" % (name, want_exit))

    try:
        whole = _capture_dir(by_cohort[main_cohort], probes)
        short = _capture_dir(by_cohort[main_cohort][:-1], probes)
        lone = _capture_dir(by_cohort[other], probes)
        mixed = _capture_dir(by_cohort[main_cohort][:-1] + by_cohort[other], probes)
        dirs = [whole, short, lone, mixed]
        incomplete = "Tier-B probes — capture incomplete"

        # A complete capture of ONE cohort is complete, even though the gold file holds more cells.
        check("one-cohort-capture-is-complete", [whole], 0,
              ["denominator %d of %d gold probe cells" % (n_main, total),
               "INFERRED from the capture"], [incomplete])
        # A truncated capture of that same cohort still warns — the scoping must not swallow this.
        check("truncated-cohort-capture-warns", [short], 0,
              ["scored %d/%d %s" % (n_main - 1, n_main, incomplete)])
        # The small cohort alone: its own denominator, not the file's.
        check("other-cohort-scopes-to-itself", [lone], 0,
              ["denominator %d of %d gold probe cells" % (n_other, total)], [incomplete])
        # STATED scope closes the inference's hole: the absent cohort stays in the denominator, so
        # "its cells all failed" no longer reads as "it is not on this host".
        check("stated-scope-keeps-absent-cohort", ["--cohort", ",".join(sorted(by_cohort)), lone], 0,
              ["denominator %d of %d gold probe cells" % (total, total),
               "scored %d/%d %s" % (n_other, total, incomplete),
               "stated but scored no cell at all"])
        # A cell OUTSIDE the stated scope must not pad the completeness numerator. Same shortfall as
        # `truncated-cohort-capture-warns` — one in-scope cell missing — with the other cohort's cells
        # also in the directory, which is the state a host that somehow holds both would produce and
        # the state a mis-stated --cohort produces on any host. Before the numerator was scoped, those
        # extra cells were counted into it and the WARN did not fire.
        check("out-of-scope-cell-cannot-pad-the-ratio",
              ["--cohort", main_cohort, mixed], 0,
              ["scored %d/%d %s" % (n_main - 1, n_main, incomplete),
               "which the stated scope excludes"])
        # A typo must not quietly move the denominator.
        check("unknown-cohort-is-an-error", ["--cohort", "no-such-cohort", lone], 1,
              ["which no cell in probe_gold_yesno.json carries"])
        # ZERO Tier-B cells with a scope STATED — the one shape --cohort exists for, and the one the
        # statement was inert on: the missing-cohort WARN lived inside `if b_n:`, so a Tier-A-only
        # capture scored with --cohort printed output byte-identical to no --cohort at all and said
        # the absence was "expected for regression-arm dirs". That is the state the documented
        # standalone invocation of capture_probe_yesno.sh leaves (Tier B suppressed by `auto`), so
        # the medications gate cell's absence read as a clean pass.
        tier_a = _tier_a_only_capture_dir(probes)
        dirs.append(tier_a)
        check("stated-scope-with-no-tier-b-cell-still-warns", ["--cohort", other, tier_a], 0,
              ["TIER-B cohort scope: %s (stated)" % other,
               "denominator %d of %d gold probe cells" % (n_other, total),
               "stated but scored no cell at all"],
              ["expected for regression-arm dirs"])
        # Unstated, the same directory keeps the pre-existing note and says nothing about scope:
        # an inferred scope over no Tier-B cell has nothing to state.
        check("unstated-scope-with-no-tier-b-cell-keeps-the-note", [tier_a], 0,
              ["expected for regression-arm dirs"],
              ["TIER-B cohort scope:", "stated but scored no cell at all"])
    finally:
        for d in dirs:
            shutil.rmtree(d, ignore_errors=True)


def parse_cohorts(argv):
    """Pulls `--cohort a,b` / `--cohort=a,b` out of argv, returning (remaining_argv, names_or_None).
    None means "not stated", which is the INFERRED scope — a different thing from an empty set."""
    rest, names = [], None
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--cohort":
            if i + 1 >= len(argv):
                sys.exit("--cohort needs a value: --cohort <name>[,<name>...]")
            names = (names or []) + argv[i + 1].split(",")
            i += 2
            continue
        if a.startswith("--cohort="):
            names = (names or []) + a.split("=", 1)[1].split(",")
            i += 1
            continue
        rest.append(a)
        i += 1
    if names is not None:
        names = [n.strip() for n in names if n.strip()]
        if not names:
            sys.exit("--cohort was given but names no cohort")
    return rest, (set(names) if names is not None else None)


def main():
    argv, stated_cohorts = parse_cohorts(sys.argv[1:])
    if len(argv) < 1:
        sys.exit("usage: score_directness.py [--cohort NAME[,NAME...]] <capture_dir> "
                 "[metric_gold.rc2.json] [probe_gold_yesno.json]")
    cap = argv[0]
    gold_path = argv[1] if len(argv) > 1 else os.path.join(HERE, "metric_gold.rc2.json")
    probe_path = argv[2] if len(argv) > 2 else os.path.join(HERE, "probe_gold_yesno.json")
    if not os.path.exists(gold_path):
        sys.exit("gold file not found: %s (pass it as the second argument)" % gold_path)
    gold = json.load(open(gold_path))
    if not os.path.exists(probe_path):
        if len(argv) > 2:
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
    #
    # --cohort STATES the scope; without it it is INFERRED from the cells that scored, and the
    # inference has a hole the statement closes: a cohort whose every cell failed to capture is
    # indistinguishable from one that is not on this host, so it drops out of the denominator and
    # the run reads complete. Stated, that cohort stays in the denominator and the WARN fires. The
    # basis is printed either way, so no reader has to guess which denominator produced a figure.
    if stated_cohorts is not None and probes:
        known = set(v.get("cohort", "rc2") for v in probes.values())
        unknown = sorted(stated_cohorts - known)
        if unknown:
            sys.exit("--cohort names %s, which no cell in %s carries (known: %s). A typo would "
                     "silently move the completeness denominator, so this is an error."
                     % (", ".join(unknown), os.path.basename(probe_path), ", ".join(sorted(known))))
    seen_cohorts = set()
    # Scored Tier-B cells PER COHORT. The completeness ratio needs a numerator scoped the same way
    # its denominator is: `b_n` counts every probe cell that scored, so on a STATED scope narrower
    # than the directory's contents an out-of-scope cell padded the numerator and hid a real
    # shortfall — 12 of 13 rc2 cells plus the one standalone cell, `--cohort rc2`, printed n=13
    # against a denominator of 13 and no incomplete WARN. `missing` below reports a cohort STATED
    # and absent; nothing reported the reverse until this counter existed.
    b_by_cohort = {}
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
            cohort = p.get("cohort", "rc2")
            seen_cohorts.add(cohort)
            b_by_cohort[cohort] = b_by_cohort.get(cohort, 0) + 1
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
    scope = stated_cohorts if stated_cohorts is not None else seen_cohorts
    expected_b = sum(1 for v in probes.values() if v.get("cohort", "rc2") in scope)
    # The completeness numerator, scoped like the denominator. Equal to b_n whenever the scope was
    # INFERRED (it is then the set of cohorts that scored, so nothing can be outside it).
    b_in_scope = sum(n for c, n in b_by_cohort.items() if c in scope)
    # Reported whenever a scope was STATED, even with nothing scored — the one shape --cohort was
    # added for is a cohort that scored NOTHING, and inside `if b_n:` the statement was inert on
    # exactly it. capture_probe_yesno.sh's `auto` rule makes that the DOCUMENTED standalone
    # invocation: CAPTURE_PATIENTS=<uuids> with CAPTURE_TIER_B unset sets FIRE_TIER_B=0, so the
    # only medications gate cell in this directory never fires, and the run printed nothing but
    # the regression-arm note below and exited 0. An INFERRED scope with no Tier-B cell has
    # nothing to say (the inferred scope is then empty), so it stays silent.
    if b_n or stated_cohorts is not None:
        print("TIER-B cohort scope: %s (%s) — denominator %d of %d gold probe cells"
              % (", ".join(sorted(scope)) or "none", "stated" if stated_cohorts is not None
                 else "INFERRED from the capture; pass --cohort to state it", expected_b, len(probes)))
        missing = sorted(scope - seen_cohorts)
        if missing:
            print("WARN: cohort(s) %s were stated but scored no cell at all — either they are not "
                  "on this host, or every one of their cells failed to capture; this run cannot "
                  "tell those apart" % ", ".join(missing))
        outside = sorted(c for c in b_by_cohort if c not in scope)
        if outside:
            print("WARN: %d scored cell(s) belong to cohort(s) %s, which the stated scope excludes — "
                  "they are counted in the n above but NOT in the completeness denominator, so the "
                  "ratio is over the %d in-scope cell(s) only"
                  % (b_n - b_in_scope, ", ".join(outside), b_in_scope))
    if probes and 0 < b_in_scope < expected_b:
        print("WARN: scored %d/%d Tier-B probes — capture incomplete; Tier-B verdict NOT gate-comparable"
              % (b_in_scope, expected_b))
    if probes and b_n == 0 and a_n and stated_cohorts is None:
        # Only where the scope was NOT stated. A stated cohort is the operator asserting cells of it
        # should be here, so calling their absence "expected" is the opposite of what they asked;
        # the WARN above answers instead.
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
