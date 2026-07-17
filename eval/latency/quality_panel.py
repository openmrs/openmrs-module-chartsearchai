#!/usr/bin/env python3
"""Pre/post quality panel for answer-changing latency work (measure-first gate).

When a change is EXPECTED to alter answer text (e.g. shortening the model's
reasoning scratchpad), byte-parity cannot be the correctness guard. This panel
captures, per (patient, clinical-topic) cell on the RUNNING standalone:
  * abstention behavior (did the answer cite any records at all),
  * the cited record-index set (the measurable clinical claim),
  * grounding verdicts per citation.
`capture` saves one JSON per cell; `compare` evaluates a post-change capture
against a pre-change one:
  * abstention flips (answered <-> abstained)   — gate: zero
  * citation-set Jaccard per cell               — gate: mean >= 0.80, min >= 0.50
  * overall grounded-rate delta                 — gate: drop <= 2 percentage points

Usage:  quality_panel.py capture <outdir>
        quality_panel.py compare <pre_dir> <post_dir>
Env:    BASE, OMRS_USER, OMRS_PASS as in latency_bench.py.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import latency_bench as lb

PANEL_PATIENTS = [
    ("f25ba560-187e-4fe0-80ce-28bbaa5eed1d", "mark-smith"),
    ("636ac9f1-bcc7-452a-b51e-32a2c0399939", "kevin-brown"),
    ("22494d3a-4d2a-45d2-bd88-61d787c47e79", "carol-brown"),
    ("6dfce5b2-c4ca-4400-aabb-d0fec3104270", "james-martinez"),
]

# Same 8 clinical topics as eval/drift-metric/capture_eval.sh, so panel coverage
# mirrors the adjudicated gold harness even though its gold patients don't exist
# on the standalone.
TOPIC_QUERIES = [
    ("programs", "Is the patient enrolled in any programs?"),
    ("allergies", "Does the patient have any allergies?"),
    ("medications", "What medications is the patient taking?"),
    ("eye", "Does the patient have any eye problems?"),
    ("heart", "Does the patient have any heart or cardiac problems?"),
    ("fractures", "Has the patient had any fractures or broken bones?"),
    ("kidney", "Does the patient have any kidney problems?"),
    ("mental", "Does the patient have any mental health or psychiatric conditions?"),
]


def capture(outdir):
    os.makedirs(outdir, exist_ok=True)
    rate_uuid, rate_orig = lb.get_gp(lb.GP_RATE)
    try:
        lb.set_gp(lb.GP_RATE, "1000")
        for uuid, name in PANEL_PATIENTS:
            for topic, q in TOPIC_QUERIES:
                d = lb.req("/chartsearchai/search",
                           {"patient": uuid, "question": q}, "POST")
                cell = {"patient": uuid, "topic": topic, "question": q,
                        "answer": (d.get("answer") or "").strip(),
                        "citations": sorted(r.get("index") for r in (d.get("references") or [])),
                        "verdicts": {str(r.get("index")): r.get("grounded")
                                     for r in (d.get("references") or [])}}
                path = os.path.join(outdir, "%s__%s.json" % (name, topic))
                with open(path, "w") as f:
                    json.dump(cell, f, indent=1)
                print("captured %s/%s: %d citations" % (name, topic, len(cell["citations"])))
    finally:
        if rate_uuid:
            lb.set_gp(lb.GP_RATE, rate_orig)


def load_cells(d):
    cells = {}
    for fn in sorted(os.listdir(d)):
        if fn.endswith(".json"):
            with open(os.path.join(d, fn)) as f:
                cells[fn[:-5]] = json.load(f)
    return cells


def compare(pre_dir, post_dir):
    pre, post = load_cells(pre_dir), load_cells(post_dir)
    keys = sorted(set(pre) & set(post))
    if set(pre) != set(post):
        print("WARN: cell sets differ; comparing %d common cells" % len(keys))
    flips, jaccards = 0, []
    g_pre = g_post = n_pre = n_post = 0
    for k in keys:
        a, b = pre[k], post[k]
        sa, sb = set(a["citations"]), set(b["citations"])
        if bool(sa) != bool(sb):
            flips += 1
            print("ABSTENTION FLIP %s: pre=%d cites post=%d cites" % (k, len(sa), len(sb)))
        if sa or sb:
            j = len(sa & sb) / float(len(sa | sb))
            jaccards.append((j, k))
            if j < 1.0:
                print("  jaccard %.2f %s: pre-only=%s post-only=%s"
                      % (j, k, sorted(sa - sb), sorted(sb - sa)))
        n_pre += len(a["verdicts"]); g_pre += sum(1 for v in a["verdicts"].values() if v is True)
        n_post += len(b["verdicts"]); g_post += sum(1 for v in b["verdicts"].values() if v is True)
    mean_j = sum(j for j, _ in jaccards) / len(jaccards) if jaccards else 1.0
    min_j, min_k = min(jaccards) if jaccards else (1.0, "-")
    rate_pre = g_pre / float(n_pre) if n_pre else 0.0
    rate_post = g_post / float(n_post) if n_post else 0.0
    print("\ncells=%d  abstention_flips=%d  citation_jaccard mean=%.3f min=%.3f (%s)"
          % (len(keys), flips, mean_j, min_j, min_k))
    print("grounded-rate pre=%.3f (%d/%d)  post=%.3f (%d/%d)  delta=%+.1fpp"
          % (rate_pre, g_pre, n_pre, rate_post, g_post, n_post,
             (rate_post - rate_pre) * 100))
    ok = flips == 0 and mean_j >= 0.80 and min_j >= 0.50 and (rate_post - rate_pre) >= -0.02
    print("GATE(quality): %s  [flips==0, meanJ>=0.80, minJ>=0.50, groundedRate drop<=2pp]"
          % ("PASS" if ok else "FAIL"))
    return ok


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "capture":
        capture(sys.argv[2])
    elif len(sys.argv) >= 4 and sys.argv[1] == "compare":
        sys.exit(0 if compare(sys.argv[2], sys.argv[3]) else 1)
    else:
        print(__doc__)
        sys.exit(2)
