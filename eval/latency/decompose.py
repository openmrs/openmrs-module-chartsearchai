#!/usr/bin/env python3
"""Latency decomposition for chartsearchai chart-search, via SSE event timing.

Streams POST /chartsearchai/search/stream and timestamps each event to split total
latency into phases:
  - prefill   : t0 -> first output  (retrieval + chart serialize + prompt prefill + TTFT)
  - reasoning : first 'thinking' -> last 'thinking'   (chain-of-thought generation)
  - answer    : first 'token' -> last 'token'         (answer generation)
  - grounding : 'references' event -> 'done' event    (Tier-2 batched entailment)
Run across a citation-count range so grounding's scaling (vs the pre-#37 34s-at-16-cites
pathology) is visible. Patient 165497e8 (large chart) is the worst case.

Usage: python3 eval/latency/decompose.py
"""
import base64
import json
import time
import urllib.request

BASE = "http://localhost:8081/openmrs/ws/rest/v1"
AUTH = base64.b64encode(b"admin:Admin123").decode()
P = "165497e8-13e0-4fa4-8190-8e6fa067c4b7"
QUERIES = [
    "Is she pregnant?",
    "any feeding problems?",
    "what active conditions does the patient have?",
    "what are the patient's diagnoses?",
]


def warmup():
    try:
        r = urllib.request.Request(BASE + "/chartsearchai/warmup",
                                   data=json.dumps({"patient": P}).encode(), method="POST")
        r.add_header("Authorization", "Basic " + AUTH); r.add_header("Content-Type", "application/json")
        urllib.request.urlopen(r, timeout=180).read()
    except Exception:
        pass


def decompose(q):
    r = urllib.request.Request(BASE + "/chartsearchai/search/stream",
                               data=json.dumps({"patient": P, "question": q}).encode(), method="POST")
    r.add_header("Authorization", "Basic " + AUTH)
    r.add_header("Content-Type", "application/json")
    r.add_header("Accept", "text/event-stream")
    t0 = time.time()
    mark = {}  # event-type -> (first_t, last_t)
    cur = None
    refs = 0
    with urllib.request.urlopen(r, timeout=300) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").rstrip("\n")
            if line.startswith("event:"):
                cur = line[6:].strip()
            elif line.startswith("data:"):
                now = time.time() - t0
                f, _ = mark.get(cur, (now, now))
                mark[cur] = (f, now)
                if cur == "done":
                    try:
                        refs = len(json.loads(line[5:].strip()).get("references") or [])
                    except Exception:
                        pass
    return t0, mark, refs, time.time() - t0


def span(mark, ev):
    return mark.get(ev, (0, 0))


warmup()
time.sleep(2)
print("patient 165497e8 | clauseScoped=false, grounding entailment on, preFilter off\n")
print("%-46s %6s %8s %8s %8s %8s %6s" % ("query", "total", "prefill", "reason", "answer", "ground", "cites"))
for q in QUERIES:
    t0, mark, refs, total = decompose(q)
    first_out = min((v[0] for v in mark.values()), default=0.0)
    th0, th1 = span(mark, "thinking")
    tk0, tk1 = span(mark, "token")
    rf0, rf1 = span(mark, "references")
    dn0, dn1 = span(mark, "done")
    prefill = first_out
    reasoning = (th1 - th0) if "thinking" in mark else 0.0
    answer = (tk1 - tk0) if "token" in mark else 0.0
    grounding = (dn1 - rf0) if ("references" in mark and "done" in mark) else 0.0
    print("%-46s %5.1fs %7.1fs %7.1fs %7.1fs %7.1fs %6d"
          % (q[:46], total, prefill, reasoning, answer, grounding, refs))
    time.sleep(2.5)
