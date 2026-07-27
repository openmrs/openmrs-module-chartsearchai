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

# compare_arms.py <baselineDir> <armDir> [goldFile] [adjFile] — the two file arguments are
# identified by SHAPE, not by position, because metric_score.py takes them in the opposite order
# (<capture> [adj] [gold]) and passing this script the order the README teaches for that one
# silently scored 0 cells and reported "no change" with exit 0.


def _load_by_shape(paths):
    """-> (gold, adj). A gold maps "uuid|topic" -> {present, ontopic, focus_uuids}; an adjudication
    maps "uuid|topic" -> [uuid, ...] and/or carries "_ontopic"."""
    gold_doc = adj_doc = None
    for path in paths:
        doc = json.load(open(path))
        looks_adj = "_ontopic" in doc or all(isinstance(v, list) for v in doc.values())
        if looks_adj and adj_doc is None:
            adj_doc = doc
        elif not looks_adj and gold_doc is None:
            gold_doc = doc
        else:
            sys.exit("compare_arms: two files of the same kind passed: %s" % ", ".join(paths))
    return gold_doc, adj_doc


_defaults = [os.path.join(M.HERE, "metric_gold.local.json"),
             os.path.join(M.HERE, "offtopic_adj.local.json")]
_given = [p for p in sys.argv[3:5] if os.path.exists(p)]
gold, _adj = _load_by_shape(_given if _given else [p for p in _defaults if os.path.exists(p)])
if gold is None:
    sys.exit("compare_arms: no gold file found (pass one, or put metric_gold.local.json beside "
             "this script)")
# Honour the same adjudication metric_score.py resolves, or the A/B table and the scorer report
# different meanF1/drift for the SAME capture the moment anyone adjudicates an unknown citation —
# which metric_score.py actively asks the operator to do.
ADJ = _adj or {}
ADJ_ON = ADJ.get("_ontopic", {})


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


if __name__ == "__main__":
    main()
