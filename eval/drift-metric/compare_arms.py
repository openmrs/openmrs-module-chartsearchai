#!/usr/bin/env python3
"""Side-by-side per-topic comparison of two capture dirs against the local gold.

Usage: compare_arms.py <baselineDir> <armDir> [goldFile] [adjFile]
"""
import collections
import json
import sys

import os  # noqa: E402
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metric_score as M  # noqa: E402

def _load_by_shape(paths):
    """-> (gold, adj), either possibly None. A gold maps "uuid|topic" -> {present, ontopic,
    focus_uuids}; an adjudication maps "uuid|topic" -> [uuid, ...] and/or carries "_ontopic"."""
    gold = adj = None
    for path in paths:
        doc = json.load(open(path))
        # An empty document has no shape: all() over no values is vacuously True, so {} would read
        # as an adjudication and the real gold would fall back to the committed one, reporting the
        # committed gold's numbers with no warning. build_gold_local.py writes {} when its patient
        # selection returns nothing, so this is reachable, not theoretical.
        if not doc:
            sys.exit("compare_arms: %s is empty — no cells to score" % path)
        looks_adj = "_ontopic" in doc or all(isinstance(v, list) for v in doc.values())
        if looks_adj and adj is None:
            adj = (path, doc)
        elif not looks_adj and gold is None:
            gold = (path, doc)
        else:
            sys.exit("compare_arms: two files of the same kind passed: %s" % ", ".join(paths))
    return gold, adj


def resolve_inputs(argv, here=None):
    """-> (gold, adj) for `compare_arms.py <baselineDir> <armDir> [goldFile] [adjFile]`.

    The two file arguments are identified by SHAPE, not by position, because metric_score.py takes
    them in the opposite order (`<capture> [adj] [gold]`) and passing this script the order the
    README teaches for that one silently scored 0 cells and reported "no change" with exit 0.

    Three rules, each of which was a defect first — this function exists so `--selftest` can pin
    them instead of a reviewer re-deriving them:

    1. A path the operator TYPED must exist. Existence-filtering the given arguments turned a typo
       into a silent fall back to the committed gold and a complete-looking table.
    2. Each kind falls back to its own default INDEPENDENTLY. Resolving them together meant that
       supplying only a gold — the form the README teaches — left the adjudication empty, so this
       table and metric_score.py disagreed on the same capture (measured: meanF1 1.000/drift 0 vs
       0.667/drift 1, no warning, exit 0).
    3. The adjudication is honoured at all, because metric_score.py honours it and actively asks
       the operator to extend it.
    """
    here = here or M.HERE
    given = argv[3:5]
    for path in given:
        if not os.path.exists(path):
            sys.exit("compare_arms: no such file: %s" % path)
    gold, adj = _load_by_shape(given)
    if gold is None:
        default_gold = os.path.join(here, "metric_gold.local.json")
        if not os.path.exists(default_gold):
            sys.exit("compare_arms: no gold file found (pass one, or put metric_gold.local.json "
                     "beside this script)")
        gold = (default_gold, json.load(open(default_gold)))
    if adj is None:
        adj_doc = {}
        # Pair the adjudication with the gold's OWN family. A hardcoded ".local" default silently
        # scored metric_gold.json against the empty offtopic_adj.local.json instead of
        # offtopic_adj.json, discarding its 13 adjudicated cells.
        base = os.path.basename(gold[0])
        candidates = []
        if "metric_gold" in base:
            # Only a recognisable gold name maps to a family. Blind substitution on an arbitrary
            # name ("other_gold.json") leaves it unchanged, so the gold would be loaded a second
            # time AS the adjudication — caught by selftest case 2 when this was first written.
            candidates.append(base.replace("metric_gold", "offtopic_adj"))
        candidates.append("offtopic_adj.local.json")
        adj_path = None
        for candidate in candidates:
            path = os.path.join(os.path.dirname(gold[0]) or here, candidate)
            if os.path.exists(path):
                adj_path, adj_doc = path, json.load(open(path))
                break
        adj = (adj_path, adj_doc)
    return gold[1], adj[1]


gold = {}
ADJ = {}
ADJ_ON = {}


def score(cap):
    per = collections.defaultdict(lambda: {"f1": [], "prec": [], "rec": [], "abs_n": 0,
                                           "abs_ok": 0, "drift": 0})
    cells = {}
    rows, _ = M.load_captures(cap, lambda c: c in gold)
    if not rows:
        print("WARN: %s matched 0 of %d gold cells — wrong capture dir, or the gold and the "
              "capture are from different installs. The comparison below is empty, not equal."
              % (cap, len(gold)))
    for uuid, topic, d in rows:
        cell = uuid + "|" + topic
        g = gold[cell]
        cited = list(dict.fromkeys(r.get("resourceUuid") for r in d.get("references", [])
                                   if r.get("resourceUuid")))
        s = M.score_cell(cited, g["present"], set(g["ontopic"]), set(g["focus_uuids"]),
                         set(ADJ.get(cell, [])), set(ADJ_ON.get(cell, [])))
        p = per[topic]
        if s["present"] and s.get("scoreable"):
            p["f1"].append(s["f1"]); p["prec"].append(s["prec"]); p["rec"].append(s["rec"])
            p["drift"] += len(s["off"]) + len(s["unk"])
            cells[cell] = s["f1"]
        elif not s["present"]:
            p["abs_n"] += 1; p["abs_ok"] += 1 if s["abstain_ok"] else 0
            p["drift"] += len(s["on"]) + len(s["off"]) + len(s["unk"])
    return per, cells


def agg(per):
    f1 = [x for p in per.values() for x in p["f1"]]
    an = sum(p["abs_n"] for p in per.values()); ao = sum(p["abs_ok"] for p in per.values())
    return (sum(f1) / len(f1) if f1 else 0, len(f1), ao, an,
            sum(p["drift"] for p in per.values()))


def mean(xs):
    return sum(xs) / len(xs) if xs else 0.0


def main():
    base, arm = sys.argv[1], sys.argv[2]
    pb, cb = score(base)
    pa, ca = score(arm)
    print("%-15s | %-24s | %-24s" % ("topic", "BASE  f1/prec/rec  abst dr", "ARM   f1/prec/rec  abst dr"))
    print("-" * 76)
    for t in sorted(set(pb) | set(pa)):
        b, a = pb[t], pa[t]
        print("%-15s | %.3f %.3f %.3f %5s %3d | %.3f %.3f %.3f %5s %3d" % (
            t, mean(b["f1"]), mean(b["prec"]), mean(b["rec"]),
            "%d/%d" % (b["abs_ok"], b["abs_n"]), b["drift"],
            mean(a["f1"]), mean(a["prec"]), mean(a["rec"]),
            "%d/%d" % (a["abs_ok"], a["abs_n"]), a["drift"]))
    bf, bn, bao, ban, bd = agg(pb)
    af, an_, aao, aan, ad = agg(pa)
    print("\nAGGREGATE")
    print("  meanF1      %.3f (n=%d)  ->  %.3f (n=%d)   delta %+.3f" % (bf, bn, af, an_, af - bf))
    print("  abstention  %.3f (%d/%d)  ->  %.3f (%d/%d)   delta %+.3f" % (
        bao / ban if ban else 0, bao, ban, aao / aan if aan else 0, aao, aan,
        (aao / aan if aan else 0) - (bao / ban if ban else 0)))
    print("  drift       %d  ->  %d   delta %+d" % (bd, ad, ad - bd))

    both = set(cb) & set(ca)
    better = sorted(((ca[c] - cb[c], c) for c in both if ca[c] > cb[c] + 1e-9), reverse=True)
    worse = sorted((ca[c] - cb[c], c) for c in both if ca[c] < cb[c] - 1e-9)
    print("\n  per-cell F1: %d better / %d worse / %d tied"
          % (len(better), len(worse), len(both) - len(better) - len(worse)))
    for d, c in better[:8]:
        print("    +%.2f  %s" % (d, c))
    for d, c in worse[:8]:
        print("    %.2f  %s" % (d, c))


def selftest():
    """Pin every invocation form. Three consecutive defects landed in resolve_inputs' logic while
    it was module-level code executed at import, i.e. unreachable from any test."""
    import shutil
    import tempfile
    tmp = tempfile.mkdtemp()
    try:
        g = {"p|eye": {"present": True, "ontopic": {"u1": "x"}, "focus_uuids": ["u1"]}}
        a = {"_ontopic": {"p|eye": ["u2"]}}
        gp, ap = os.path.join(tmp, "metric_gold.local.json"), os.path.join(tmp, "offtopic_adj.local.json")
        json.dump(g, open(gp, "w")); json.dump(a, open(ap, "w"))
        alt_g, alt_a = os.path.join(tmp, "other_gold.json"), os.path.join(tmp, "other_adj.json")
        shutil.copy(gp, alt_g); shutil.copy(ap, alt_a)

        # 1. both orders resolve identically, and neither kind is dropped
        for argv in (["x", "b", "a", alt_g, alt_a], ["x", "b", "a", alt_a, alt_g]):
            got_g, got_a = resolve_inputs(argv, tmp)
            assert got_g == g, argv
            assert got_a == a, ("adjudication dropped", argv)

        # 2. the README's form — gold only — must still pick up the default adjudication
        got_g, got_a = resolve_inputs(["x", "b", "a", alt_g], tmp)
        assert got_g == g and got_a == a, ("gold-only form dropped the adjudication", got_a)

        # 3. adjudication only — the gold must come from the default
        got_g, got_a = resolve_inputs(["x", "b", "a", alt_a], tmp)
        assert got_g == g and got_a == a, ("adj-only form", got_g, got_a)

        # 4. no file arguments — both defaults
        got_g, got_a = resolve_inputs(["x", "b", "a"], tmp)
        assert got_g == g and got_a == a

        # 5. a typo must exit, not silently substitute the default
        for bad in (["x", "b", "a", os.path.join(tmp, "nope.json")],
                    ["x", "b", "a", alt_g, os.path.join(tmp, "nope.json")]):
            try:
                resolve_inputs(bad, tmp)
                raise AssertionError("a missing path must exit: %s" % bad)
            except SystemExit:
                pass

        # 6. two files of the same kind must exit rather than silently keep one
        try:
            resolve_inputs(["x", "b", "a", alt_g, gp], tmp)
            raise AssertionError("two golds must exit")
        except SystemExit:
            pass

        # 7. an EMPTY document has no shape — it must exit, not read as an adjudication and let
        #    the real gold fall back to the committed one
        empty = os.path.join(tmp, "metric_gold.empty.json")
        json.dump({}, open(empty, "w"))
        try:
            resolve_inputs(["x", "b", "a", empty], tmp)
            raise AssertionError("an empty gold must exit, not silently substitute the default")
        except SystemExit:
            pass

        # 8. the adjudication defaults to the GOLD'S OWN family, not a hardcoded one
        fam_g = os.path.join(tmp, "metric_gold.fam.json")
        fam_a = os.path.join(tmp, "offtopic_adj.fam.json")
        json.dump(g, open(fam_g, "w"))
        json.dump({"_ontopic": {"p|eye": ["FAMILY"]}}, open(fam_a, "w"))
        got_g, got_a = resolve_inputs(["x", "b", "a", fam_g], tmp)
        assert got_a["_ontopic"]["p|eye"] == ["FAMILY"], ("wrong adjudication family", got_a)

        # 9. a missing default gold must exit rather than score nothing
        bare = tempfile.mkdtemp()
        try:
            resolve_inputs(["x", "b", "a"], bare)
            raise AssertionError("a missing default gold must exit")
        except SystemExit:
            pass
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("compare_arms selftest OK")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    else:
        gold, ADJ = resolve_inputs(sys.argv)
        ADJ_ON = ADJ.get("_ontopic", {})
        main()
