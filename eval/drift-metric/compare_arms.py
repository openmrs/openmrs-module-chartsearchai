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

DEFAULT_GOLD = "metric_gold.local.json"

DEFAULT_ADJ = "offtopic_adj.local.json"


def _kind(doc):
    """'adj', 'gold', or None when the document cannot be either."""
    if not isinstance(doc, dict) or not doc:
        return None
    if "_ontopic" in doc or all(isinstance(v, list) for v in doc.values()):
        return "adj"
    if all(isinstance(v, dict) and "present" in v for v in doc.values()):
        return "gold"
    return None


def _load(path, expected):
    """Load `path` and require it to BE `expected`. Never guesses — a mismatch exits."""
    try:
        doc = json.load(open(path))
    except ValueError as exc:
        sys.exit("compare_arms: %s is not valid JSON (%s)" % (path, exc))
    except (IsADirectoryError, OSError) as exc:
        sys.exit("compare_arms: cannot read %s (%s)" % (path, exc))
    kind = _kind(doc)
    if kind is None:
        sys.exit("compare_arms: %s is neither a gold nor an adjudication (empty, or the wrong "
                 "shape). A gold maps \"uuid|topic\" -> {present, ontopic, focus_uuids}; an "
                 "adjudication maps \"uuid|topic\" -> [uuid, ...] and/or carries \"_ontopic\"."
                 % path)
    if kind != expected:
        sys.exit("compare_arms: %s looks like the %s, but it was given in the %s position.\n"
                 "Usage: compare_arms.py <baselineDir> <armDir> [goldFile] [adjFile]  (note "
                 "metric_score.py takes these two in the OPPOSITE order)" % (path, kind, expected))
    return doc


def resolve_inputs(argv, here=None):
    """-> (gold, adj, provenance) for `compare_arms.py <baselineDir> <armDir> [goldFile] [adjFile]`.

    This resolution produced NINE silent failures before it was rewritten — every one of them a
    wrong-but-plausible number with no warning and exit 0 — because it tried to infer what the
    operator meant from four independent signals: argument position, document shape, filename
    family, and which files happened to sit in a directory. Each inference was a door. The fix is
    not another special case; it is to stop inferring:

    - **Positional, in the documented order.** Each supplied file must BE what its position says.
      A swapped pair (metric_score.py's order) is now a loud error naming both kinds, where shape
      sniffing silently accepted it and then, in other combinations, silently mispaired it.
    - **One validation path.** Given files, defaults and family siblings are all loaded by
      {@code _load}, so "the default was empty" cannot behave differently from "the argument was
      empty" — it used to: rc=0 with a misattributed warning versus rc=1 with the right message.
    - **No cross-family fallback.** An adjudication is only ever the gold's own sibling, resolved
      beside the gold's absolute path. The hardcoded ".local" second chance silently paired a fresh
      gold with a foreign adjudication, and `dirname` of a bare filename is "", which made the same
      file resolve differently depending on whether it was typed as `g.json`, `./g.json` or an
      absolute path.
    - **Nothing is silent.** The returned provenance is printed on every run, so a resolution this
      function did not anticipate is visible in the output instead of being a plausible number.
    """
    here = here or M.HERE
    if len(argv) > 5:
        sys.exit("compare_arms: too many arguments (%d). Usage: compare_arms.py <baselineDir> "
                 "<armDir> [goldFile] [adjFile]" % (len(argv) - 1))
    given_gold = argv[3] if len(argv) > 3 else None
    given_adj = argv[4] if len(argv) > 4 else None
    for path in (given_gold, given_adj):
        if path is not None and not os.path.exists(path):
            sys.exit("compare_arms: no such file: %s" % path)

    provenance = []
    if given_gold:
        gold_path = os.path.abspath(given_gold)
    else:
        gold_path = os.path.join(here, DEFAULT_GOLD)
        if not os.path.exists(gold_path):
            sys.exit("compare_arms: no gold given and no %s beside this script" % DEFAULT_GOLD)
    gold = _load(gold_path, "gold")
    provenance.append("gold: %s (%d cells)%s"
                      % (gold_path, len(gold), "" if given_gold else " [default]"))

    if given_adj:
        adj_path = os.path.abspath(given_adj)
        adj = _load(adj_path, "adj")
        provenance.append("adj:  %s" % adj_path)
    else:
        # The gold's OWN sibling, beside the gold itself — never a different family, never relative
        # to this script. build_gold_local.py writes the pair into one directory, so this is the
        # pairing that is always correct when it exists.
        base = os.path.basename(gold_path)
        # replace(..., 1) so "metric_gold_metric_gold.json" looks for
        # "offtopic_adj_metric_gold.json" rather than rewriting both halves.
        sibling_name = base.replace("metric_gold", "offtopic_adj", 1) if "metric_gold" in base else None
        sibling = os.path.join(os.path.dirname(gold_path), sibling_name) if sibling_name else None
        if sibling and os.path.exists(sibling):
            adj = _load(sibling, "adj")
            provenance.append("adj:  %s [sibling of the gold]" % sibling)
        else:
            adj = {}
            # Name the candidate actually looked for. Reporting DEFAULT_ADJ when a sibling name WAS
            # derivable said "no offtopic_adj.local.json beside the gold" while
            # offtopic_adj.rc2.json sat right there.
            provenance.append("adj:  NONE — %s. Unknown citations stay unknown; metric_score.py "
                              "may report different numbers if you pass it one."
                              % ("no %s beside the gold" % sibling_name if sibling_name
                                 else "the gold's name has no \"metric_gold\" in it, so no "
                                      "sibling adjudication name can be derived; pass one "
                                      "explicitly as the 4th argument"))
    return gold, adj, provenance


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
    """Pin every historical defect. Nine silent failures landed in this resolution; each case below
    names the one it stops, and each was verified to FAIL against the code that had that defect."""
    import shutil
    import tempfile
    home = tempfile.mkdtemp()          # stands in for the script's own directory
    other = tempfile.mkdtemp()         # a separate directory, so cross-directory pairing is testable
    bare = tempfile.mkdtemp()          # no defaults at all
    try:
        GOLD = {"p|eye": {"present": True, "ontopic": {"u1": "x"}, "focus_uuids": ["u1"]}}
        HOME_ADJ = {"_ontopic": {"p|eye": ["HOME"]}}
        FAM_ADJ = {"_ontopic": {"p|eye": ["FAMILY"]}}

        def write(d, name, doc):
            p = os.path.join(d, name)
            json.dump(doc, open(p, "w"))
            return p

        home_gold = write(home, DEFAULT_GOLD, GOLD)
        home_adj = write(home, DEFAULT_ADJ, HOME_ADJ)

        def resolved(argv, here=home):
            g, a, prov = resolve_inputs(argv, here)
            return g, a, "\n".join(prov)

        def must_exit(argv, why, here=home):
            try:
                resolve_inputs(argv, here)
            except SystemExit:
                return
            raise AssertionError(why)

        # 1. no arguments -> the committed pair, both flagged in the provenance
        g, a, prov = resolved(["x", "b", "arm"])
        assert g == GOLD and a == HOME_ADJ, ("defaults", a)
        assert "[default]" in prov and DEFAULT_ADJ in prov, prov

        # 2. gold only, sibling PRESENT -> the sibling, not the script's default
        #    (stops: a hardcoded .local fallback pairing a fresh gold with a foreign adjudication)
        fam_gold = write(other, "metric_gold.pr93.json", GOLD)
        write(other, "offtopic_adj.pr93.json", FAM_ADJ)
        g, a, prov = resolved(["x", "b", "arm", fam_gold])
        assert a == FAM_ADJ, ("must use the gold's own family", a, prov)

        # 3. gold only, sibling ABSENT -> NO adjudication, and the output says so
        #    (stops: silently substituting the .local adjudication of another family)
        #
        # The FOREIGN adjudication below is what makes this case able to fail. Without it the
        # fixture directory holds nothing for a cross-family fallback to find, so "sibling absent"
        # was only ever tested where the bug could not fire — and a mutation reintroducing the
        # fallback kept this green while silently scoring 0.250 instead of 0.333.
        write(other, DEFAULT_ADJ, {"_ontopic": {"p|eye": ["FOREIGN"]}})
        lone = write(other, "metric_gold.lonely.json", GOLD)
        g, a, prov = resolved(["x", "b", "arm", lone])
        assert a == {}, ("a missing sibling must not fall back to another family", a)
        assert "NONE" in prov, prov
        assert "metric_gold.lonely" not in prov.split("adj:")[1], \
            ("the NONE line must name the sibling it looked for, not the gold", prov)
        # …and the derived candidate must be named, not a hardcoded default
        assert "offtopic_adj.lonely.json" in prov, prov

        # 4. a bare filename must resolve exactly like ./x and like an absolute path
        #    (stops: dirname("") sending the family lookup to the script's directory)
        cwd = os.getcwd()
        try:
            os.chdir(other)
            spellings = [resolved(["x", "b", "arm", s])[1]
                         for s in ("metric_gold.pr93.json", "./metric_gold.pr93.json", fam_gold)]
        finally:
            os.chdir(cwd)
        assert spellings[0] == spellings[1] == spellings[2] == FAM_ADJ, spellings

        # 5. an adjudication in the GOLD slot must exit loudly, not be silently swapped
        #    (stops: shape sniffing accepting metric_score.py's order and mispairing the other file)
        must_exit(["x", "b", "arm", home_adj], "an adj in the gold slot must exit")
        must_exit(["x", "b", "arm", home_gold, home_gold], "a gold in the adj slot must exit")

        # 6. a typo'd path must exit in EITHER slot (stops: silent substitution of the default)
        must_exit(["x", "b", "arm", os.path.join(home, "nope.json")], "typo in slot 3")
        must_exit(["x", "b", "arm", home_gold, os.path.join(home, "nope.json")], "typo in slot 4")

        # 7. an empty or wrong-shaped document must exit — as an ARGUMENT and as a DEFAULT
        #    (stops: {} classifying as an adjudication; and the default escaping validation)
        must_exit(["x", "b", "arm", write(other, "metric_gold.empty.json", {})], "empty gold arg")
        for junk in ({}, [], "s", 0, None, {"p|eye": 3}):
            junk_home = tempfile.mkdtemp()
            write(junk_home, DEFAULT_GOLD, junk)
            must_exit(["x", "b", "arm"], "a junk DEFAULT gold must exit: %r" % (junk,), junk_home)
            shutil.rmtree(junk_home, ignore_errors=True)

        # 8. a junk family sibling must exit rather than be silently discarded
        sib_home = tempfile.mkdtemp()
        write(sib_home, "metric_gold.x.json", GOLD)
        write(sib_home, "offtopic_adj.x.json", {"p|eye": {"present": True}})
        must_exit(["x", "b", "arm", os.path.join(sib_home, "metric_gold.x.json")],
                  "a junk sibling must exit")
        shutil.rmtree(sib_home, ignore_errors=True)

        # 9. arguments beyond slot 4 must be rejected, not ignored
        must_exit(["x", "b", "arm", home_gold, home_adj, "extra.json"], "arg 5 must be rejected")

        # 10. a gold carrying an "_ontopic" cell key must not flip roles
        odd = dict(GOLD)
        odd["_ontopic"] = {"p|eye": ["X"]}
        must_exit(["x", "b", "arm", write(other, "metric_gold.odd.json", odd)],
                  "a gold with an _ontopic key must exit, not be read as the adjudication")

        # 11. no default gold at all -> exit, not an empty comparison
        must_exit(["x", "b", "arm"], "a missing default gold must exit", bare)

        # 12. an EMPTY adjudication is not a shape either — in the slot AND as a sibling
        must_exit(["x", "b", "arm", home_gold, write(other, "offtopic_adj.empty.json", {})],
                  "an empty adjudication argument must exit")
        empty_sib = tempfile.mkdtemp()
        write(empty_sib, "metric_gold.es.json", GOLD)
        write(empty_sib, "offtopic_adj.es.json", {})
        must_exit(["x", "b", "arm", os.path.join(empty_sib, "metric_gold.es.json")],
                  "an empty sibling adjudication must exit")
        shutil.rmtree(empty_sib, ignore_errors=True)

        # 13. a gold whose cells lack "present" is not a gold (it used to load and then die
        #     mid-scoring with a KeyError)
        must_exit(["x", "b", "arm", write(other, "metric_gold.nopresent.json",
                                          {"p|eye": {"ontopic": {}, "focus_uuids": []}})],
                  "a cell without \"present\" must be rejected at load time")

        # 14. the resolved provenance must always name both roles — it is the redesign's only
        #     guarantee that an unanticipated resolution is visible
        for argv in (["x", "b", "arm"], ["x", "b", "arm", fam_gold],
                     ["x", "b", "arm", home_gold, home_adj]):
            _, _, prov_lines = resolve_inputs(argv, home)
            joined = "\n".join(prov_lines)
            assert joined.startswith("gold: ") and "adj:" in joined, (argv, prov_lines)
            assert os.path.isabs(prov_lines[0].split("gold: ")[1].split(" (")[0]), prov_lines

        # 15. and the CLI must actually PRINT it — deleting the print loop left every case above
        #     green, because they call resolve_inputs directly and never exercise __main__
        cap = os.path.join(other, "cap")
        os.makedirs(cap, exist_ok=True)
        out = __import__("subprocess").run(
            [__import__("sys").executable, os.path.abspath(__file__), cap, cap, home_gold],
            capture_output=True, text=True).stdout
        assert "gold: " in out and "adj:" in out, ("the CLI must print the provenance", out[:400])

        # 16. both capture arguments must be directories — forgetting the arm dir printed a full
        #     table comparing the real baseline against an empty arm, exit 0
        for bad in ([__import__("sys").executable, os.path.abspath(__file__), cap, home_gold],
                    [__import__("sys").executable, os.path.abspath(__file__), cap]):
            rc = __import__("subprocess").run(bad, capture_output=True, text=True).returncode
            assert rc != 0, ("a non-directory capture argument must exit: %s" % bad[2:])
    finally:
        for d in (home, other, bare):
            shutil.rmtree(d, ignore_errors=True)
    print("compare_arms selftest OK (16 cases)")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    else:
        # Both capture arguments must be directories. Forgetting the arm dir
        # ("compare_arms.py <cap> <goldfile>") otherwise printed a full table comparing the real
        # baseline against an empty arm, with one warning and exit 0.
        for _i in (1, 2):
            if len(sys.argv) <= _i or not os.path.isdir(sys.argv[_i]):
                sys.exit("compare_arms: argument %d must be a capture DIRECTORY, got %r\n"
                         "Usage: compare_arms.py <baselineDir> <armDir> [goldFile] [adjFile]"
                         % (_i, sys.argv[_i] if len(sys.argv) > _i else None))
        gold, ADJ, PROVENANCE = resolve_inputs(sys.argv)
        ADJ_ON = ADJ.get("_ontopic", {})
        # Printed on EVERY run. Nine silent failures here shared one signature — a plausible number
        # with no indication of which files produced it — so the resolution is now always visible.
        for line in PROVENANCE:
            print("  " + line)
        main()
