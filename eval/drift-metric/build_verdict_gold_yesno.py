#!/usr/bin/env python3
"""Regenerates verdict_gold_yesno.json from metric_gold + offtopic_adj (rule-based, no
per-failure edits): a present yes/no cell whose ENTIRE on-topic universe is obs/lab records
(no Condition:/Diagnosis:/Allergy/Program/Enrolled record anywhere in it) expects a record-grounded
NO-family verdict lead, not YES — topic-presence is not verdict truth on those cells
(user-approved amendment, 2026-07-21).

Usage: build_verdict_gold_yesno.py [metric_gold.rc2.json] [offtopic_adj.rc2.json]
Writes verdict_gold_yesno.json alongside this script. Preserves nothing: output is fully
derived, so re-running after a gold/adjudication change is always safe.
"""
import json, os, re, sys

from score_directness import YESNO_TOPICS

HERE = os.path.dirname(os.path.abspath(__file__))
NAMED_RECORD = re.compile(r"^(Condition:|Diagnosis:|Allergy|Program|Enrolled)", re.I)


def build(gold_path, adj_path):
    gold = json.load(open(gold_path))
    adj = json.load(open(adj_path)) if os.path.exists(adj_path) else {}
    adj_on = adj.get("_ontopic", {})
    override = {}
    for cell, g in sorted(gold.items()):
        topic = cell.split("|")[1]
        if topic not in YESNO_TOPICS or not g["present"]:
            continue
        texts = list(g["ontopic"].values())
        for uuid in adj_on.get(cell, []):
            text = g.get("focus_uuids", {}).get(uuid)
            if text:
                texts.append(text)
        if not any(NAMED_RECORD.match(t.strip()) for t in texts):
            override[cell] = "NO"
    return override


def main():
    gold_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "metric_gold.rc2.json")
    adj_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "offtopic_adj.rc2.json")
    if not os.path.exists(gold_path):
        sys.exit("gold file not found: %s" % gold_path)
    if not os.path.exists(adj_path):
        # This script OVERWRITES the checked-in metric definition — never proceed on an
        # explicitly-requested adjudication file that doesn't exist.
        if len(sys.argv) > 2:
            sys.exit("adjudication file not found: %s" % adj_path)
        print("WARN: %s not found — building without out-of-focus adjudications" % os.path.basename(adj_path))
    override = build(gold_path, adj_path)
    out = {"_comment": "Expected-verdict overrides for yes/no scoring (user-approved amendment, "
           "2026-07-21): present cells whose ENTIRE on-topic universe is obs/lab records (no "
           "Condition:/Diagnosis:/Allergy/Program/Enrolled record) expect a record-grounded NO-family "
           "verdict lead (e.g. 'No kidney problem diagnosis is recorded.') with the labs still "
           "cited — matching the approved verdict rule instead of raw topic-presence. Generated "
           "rule-based over ALL present cells by build_verdict_gold_yesno.py; do not hand-edit."}
    for cell in sorted(override):
        out[cell] = override[cell]
    path = os.path.join(HERE, "verdict_gold_yesno.json")
    with open(path, "w") as f:
        json.dump(out, f, indent=1)
        f.write("\n")
    print("wrote %s (%d overrides)" % (path, len(override)))


if __name__ == "__main__":
    main()
