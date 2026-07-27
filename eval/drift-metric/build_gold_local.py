#!/usr/bin/env python3
"""Build a drift-metric gold for THIS standalone (RefApp 3.7.1 demo data, 50 patients).

Reuses the committed rc.2 gold builder's category boundaries and classify() verbatim — only
the patient selection and the output paths differ, so the on/off-topic standard is the same
human-adjudicated one the repo already ships. Writes into the scratchpad, never over the
committed rc.2 gold.

Env: MARIADB_BIN, MYSQL_PWD, MARIADB_PORT (as build_gold_rc2.py).
Usage: build_gold_local.py <outdir> [min_records]
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_gold_rc2 as G  # noqa: E402
import gold_overrides_local as gold_overrides  # noqa: E402

gold_overrides.apply()


def main():
    outdir = sys.argv[1]
    min_records = int(sys.argv[2]) if len(sys.argv) > 2 else 120
    os.makedirs(outdir, exist_ok=True)

    profiles = G.profile_patients()
    profiles.sort(key=lambda p: -p[3])
    chosen = [p for p in profiles if p[3] >= min_records]
    print("profiled %d patients; %d with >=%d records" % (len(profiles), len(chosen), min_records))

    gold = {}
    audit = ["# local-standalone gold (boundaries inherited from build_gold_rc2)", ""]
    for name, uuid, pid, unisize, counts in chosen:
        uni = G.fetch_universe(pid)
        audit.append("## %s (pid %s, %s) — %d records, %s" % (name, pid, uuid, unisize, counts))
        for t in G.TOPICS:
            ontopic = {u: d for (u, k, n, d, tv) in uni if G.classify(t, k, n, d, tv) is True}
            gold["%s|%s" % (uuid, t)] = {
                "present": len(ontopic) > 0,
                "ontopic": ontopic,
                "focus_uuids": {u: d for (u, k, n, d, tv) in uni},
            }
            audit.append("### %s|%s present=%s ontopic=%d" % (name, t, len(ontopic) > 0, len(ontopic)))
            for u, d in sorted(ontopic.items(), key=lambda kv: kv[1])[:80]:
                audit.append("  ON  %s" % d[:130])

    with open(os.path.join(outdir, "metric_gold.local.json"), "w") as f:
        json.dump(gold, f, indent=1)
    with open(os.path.join(outdir, "offtopic_adj.local.json"), "w") as f:
        json.dump({"_ontopic": {}}, f, indent=1)
    with open(os.path.join(outdir, "gold_audit.local.md"), "w") as f:
        f.write("\n".join(audit) + "\n")
    with open(os.path.join(outdir, "patients.txt"), "w") as f:
        f.write("\n".join(p[1] for p in chosen) + "\n")

    present = sum(1 for c in gold.values() if c["present"])
    print("patients=%d cells=%d present=%d absent=%d" % (
        len(chosen), len(gold), present, len(gold) - present))
    for name, uuid, pid, unisize, counts in chosen:
        print("  %-22s %s records=%d %s" % (name, uuid, unisize, counts))


if __name__ == "__main__":
    main()
