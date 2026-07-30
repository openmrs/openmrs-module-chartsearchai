#!/usr/bin/env python3
"""Scores a safety/suitability probe capture (capture_probe_safety.sh).

Each cell is self-labelling: DrugSafetyValidator is deterministic and reads the patient's
own active orders, allergies and the drug-reference KB, so a `safetyWarnings` chip is the
ground truth for "the asked-about drug connects to this patient". That gives two cell
classes with opposite expectations:

  ANSWER   the drug connects to this patient — a chip fired, OR the drug matches one of
           their active orders / recorded allergies. The answer must lead with a verdict.
           An abstention is the defect this probe exists to measure.
  ABSTAIN  neither holds. The answer must abstain and must not produce a bare Yes/No —
           #107 was added precisely to stop that, so this is the regression direction.

A chip alone is NOT a sufficient label, and treating it as one inverted a column of the
first result: a patient ALREADY TAKING the asked-about drug produces no chip (no interaction,
no allergy — they are simply on it) while their chart addresses the question directly through
the active order. capture_probe_safety.sh therefore writes each patient's own drugs and
allergens to _context.json, and the label is the union.

Reported per arm: the two rates, plus every cell so flips can be read individually. Verdict
classification reuses score_directness.classify — the versioned metric definition — so this
probe and the presence-topic comparator agree on what "verdict-led" means.

Usage: score_probe_safety.py <capture_dir> [<other_capture_dir>]
       With two directories, prints a per-cell A/B diff.
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from score_directness import classify

ABSTAINED = re.compile(r"do(es)? not address|no records? (that )?address", re.I)


def load(directory):
    """Cells keyed slug__safety-drug, each carrying the patient context needed to label it."""
    context = {}
    for f in sorted(os.listdir(directory)):
        if f.endswith("___context.json"):
            try:
                context[f.split("___")[0]] = json.load(open(os.path.join(directory, f)))
            except Exception:
                pass

    cells = {}
    for f in sorted(os.listdir(directory)):
        if not f.endswith(".json") or f.endswith("___context.json"):
            continue
        try:
            d = json.load(open(os.path.join(directory, f)))
        except Exception as e:  # surface, never crash the report
            cells[f[:-5]] = {"answer": "<<UNREADABLE: %s>>" % e, "chips": [], "refs": []}
            continue
        key = f[:-5]
        slug, _, drug = key.partition("__safety-")
        ctx = context.get(slug, {})
        # Substring match on the display strings: an order reads "Aspirin 81mg: ..." and an
        # allergen "Aspirin", so the probe's lowercase drug name is contained in whichever
        # names it. Deliberately not a token match — "aspirin" must hit "Acetylsalicylic acid
        # (aspirin)" too.
        own = " ".join(ctx.get("drugs", []) + ctx.get("allergens", [])).lower()
        cells[key] = {
            "answer": d.get("answer") or "",
            "chips": [w.get("type") for w in (d.get("safetyWarnings") or [])],
            "refs": [r.get("group") for r in (d.get("references") or [])],
            "own_drug": bool(drug) and drug.lower() in own,
            "no_context": not ctx,
        }
    return cells


def summarise(name, cells):
    missing_ctx = sorted(k for k, c in cells.items() if c["no_context"])
    if missing_ctx:
        print("WARN: no _context.json for %d cell(s) — labelled on chips alone, which "
              "mislabels an already-prescribed drug: %s" % (len(missing_ctx), missing_ctx[:4]))

    connected, unrelated = [], []
    for k in sorted(cells):
        c = cells[k]
        # The union: a chip, or the drug is one of theirs. Either way the chart addresses it.
        (connected if (c["chips"] or c["own_drug"]) else unrelated).append((k, c))

    # CONNECTED splits three ways, and the distinction matters: an abstention is the guard
    # over-firing, whereas stating the fact without leading on a verdict is a weaker miss.
    # classify() cannot separate them on its own — it scores "the records do not address..."
    # as NO, because the abstention template is literally a negative lead.
    conn_abstained = [k for k, c in connected if ABSTAINED.search(c["answer"])]
    conn_ok = [k for k, c in connected
               if not ABSTAINED.search(c["answer"]) and classify(c["answer"]) != "NONE"]
    conn_hedged = [k for k, c in connected
                   if not ABSTAINED.search(c["answer"]) and classify(c["answer"]) == "NONE"]
    # UNRELATED: an abstention is correct, a bare verdict is the regression.
    unrel_ok = [k for k, c in unrelated if ABSTAINED.search(c["answer"])]

    print("\n=== %s ===" % name)
    print("ANSWER cells (chip or their own drug — a verdict is expected): %d" % len(connected))
    print("  verdict-led:                   %d/%d" % (len(conn_ok), len(connected)))
    print("  stated, no verdict lead:       %d/%d" % (len(conn_hedged), len(connected)))
    print("  abstained (guard over-firing): %d/%d" % (len(conn_abstained), len(connected)))
    print("ABSTAIN cells (unconnected — abstention is expected): %d" % len(unrelated))
    print("  abstained:              %d/%d" % (len(unrel_ok), len(unrelated)))
    print("  bare verdict (REGRESSION): %d/%d" % (len(unrelated) - len(unrel_ok), len(unrelated)))

    print("\n  per cell:")
    for k, c in connected + unrelated:
        kind = "ANSWER" if (c["chips"] or c["own_drug"]) else "ABSTAIN"
        why = ("chip" if c["chips"] else "") + ("+own" if c["own_drug"] else "")
        cited_ref = "ref" if "reference" in c["refs"] else "-"
        print("    %-28s %-7s %-9s cites=%-4s %-7s %s"
              % (k, kind, why or "-", cited_ref, classify(c["answer"]), c["answer"][:66]))
    return {"connected": connected, "unrelated": unrelated,
            "conn_ok": set(conn_ok), "conn_abstained": set(conn_abstained),
            "unrel_ok": set(unrel_ok)}


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__.strip().split("\n")[0] + "\n\nusage: score_probe_safety.py <dir> [<dir>]")

    a = load(sys.argv[1])
    sa = summarise("ARM A: %s" % sys.argv[1], a)
    if len(sys.argv) == 2:
        return

    b = load(sys.argv[2])
    sb = summarise("ARM B: %s" % sys.argv[2], b)

    print("\n=== A/B, cells in both arms ===")
    both = sorted(set(a) & set(b))
    only = sorted(set(a) ^ set(b))
    if only:
        print("WARN: %d cell(s) in only one arm (excluded): %s" % (len(only), only[:6]))
    for k in both:
        ca, cb = classify(a[k]["answer"]), classify(b[k]["answer"])
        aa, ab = bool(ABSTAINED.search(a[k]["answer"])), bool(ABSTAINED.search(b[k]["answer"]))
        if ca != cb or aa != ab:
            kind = "ANSWER" if (a[k]["chips"] or a[k]["own_drug"]) else "ABSTAIN"
            print("  FLIP %-28s (%s)  A:%-6s%s -> B:%-6s%s"
                  % (k, kind, ca, " abstain" if aa else "", cb, " abstain" if ab else ""))
            print("       A: %s" % a[k]["answer"][:96])
            print("       B: %s" % b[k]["answer"][:96])

    print("\nnet on ANSWER cells (verdict-led is the goal):   A=%d/%d  B=%d/%d"
          % (len(sa["conn_ok"]), len(sa["connected"]), len(sb["conn_ok"]), len(sb["connected"])))
    print("  of which abstained (the defect):                 A=%d      B=%d"
          % (len(sa["conn_abstained"]), len(sb["conn_abstained"])))
    print("net on ABSTAIN cells (abstention must hold):     A=%d/%d  B=%d/%d"
          % (len(sa["unrel_ok"]), len(sa["unrelated"]), len(sb["unrel_ok"]), len(sb["unrelated"])))


if __name__ == "__main__":
    main()
