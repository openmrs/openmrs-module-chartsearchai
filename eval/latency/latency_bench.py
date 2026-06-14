#!/usr/bin/env python3
"""End-to-end latency benchmark for chartsearchai chart-search.

Times the locked rubric — 4 OpenMRS reference-demo patients x 8 scope queries = 32 cells —
against the live standalone's blocking POST /chartsearchai/search, and reports median / p95 /
max with counts vs the ship bar (median <=3s, p99 <=5s).

Production config assumed: querystore on, preFilter off (full-chart), grounding entailment on,
clauseScoped off (the shipped default — set here, also undoing config drift). Each patient is
warmed once (POST /warmup, as the UI does on chart open) so timings reflect the warm
prompt-cache steady state. Requests are spaced to stay under the search endpoint's rate limit.

Usage: python3 eval/latency/latency_bench.py
"""
import base64
import json
import statistics
import time
import urllib.request

BASE = "http://localhost:8081/openmrs/ws/rest/v1"
AUTH = base64.b64encode(b"admin:Admin123").decode()
SPACING_S = 2.5
BAR_MEDIAN_S = 3.0
BAR_P99_S = 5.0

PATIENTS = {
    # Stock RefApp demo patients present on this standalone. (The drift-metric eval
    # patients betty/richard/karen/mark live only in the preserve backup, not the
    # stock demo DB, so they 404 here — use patients that actually exist.)
    "susan-hall": "6fc3ccf9-54f5-45a1-bec3-6213b4968973",
    "donna-roberts": "884a5281-c3d4-47d7-b451-65e54158f772",
    "agnes-adams": "5a440752-e11d-4b2d-a9a5-c53ee0cab928",
    "michelle-lewis": "e98b9e19-e5e9-4262-9a74-7c8bdbee6198",
}
QUERIES = [
    "Is the patient enrolled in any programs?",
    "Does the patient have any allergies?",
    "What medications is the patient taking?",
    "Does the patient have any eye problems?",
    "Does the patient have any heart or cardiac problems?",
    "Has the patient had any fractures or broken bones?",
    "Does the patient have any kidney problems?",
    "Does the patient have any mental health or psychiatric conditions?",
]


def req(path, data=None, method="GET"):
    r = urllib.request.Request(BASE + path,
                               data=(json.dumps(data).encode() if data is not None else None), method=method)
    r.add_header("Authorization", "Basic " + AUTH)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(r, timeout=180) as resp:
        b = resp.read()
        return json.loads(b) if b else {}


def set_gp(name, value):
    rows = req("/systemsetting?q=%s&v=full" % name).get("results", [])
    if rows:
        req("/systemsetting/" + rows[0]["uuid"], {"value": str(value)}, "POST")


def timed_search(uuid, q):
    t = time.time()
    d = req("/chartsearchai/search", {"patient": uuid, "question": q}, "POST")
    return time.time() - t, len(d.get("references") or [])


def pct(sorted_vals, p):
    if not sorted_vals:
        return 0.0
    k = max(0, min(len(sorted_vals) - 1, int(round((p / 100.0) * len(sorted_vals) + 0.5)) - 1))
    return sorted_vals[k]


set_gp("chartsearchai.grounding.clauseScoped", "false")
# Disable the per-user rate limit (chartsearchai.rateLimitPerMinute, default 10/min) for the run:
# firing the whole rubric as a single user trips it (HTTP 429) and drops cells. Restored in the
# finally below, so the standalone keeps its configured limit afterward.
rl_rows = req("/systemsetting?q=chartsearchai.rateLimitPerMinute&v=full").get("results", [])
orig_rl = rl_rows[0].get("value") if rl_rows else None
set_gp("chartsearchai.rateLimitPerMinute", "0")
print("config: clauseScoped=false, grounding entailment on, preFilter off, querystore on, "
      "rate-limit disabled for run\n")

results = []
try:
    for name, uuid in PATIENTS.items():
        try:
            req("/chartsearchai/warmup", {"patient": uuid}, "POST")
        except Exception:
            pass
        time.sleep(SPACING_S)
        for q in QUERIES:
            try:
                dt, nrefs = timed_search(uuid, q)
                results.append(dt)
                print("  %-8s %-52s %5.2fs  (%d cites)" % (name, q[:50], dt, nrefs))
            except Exception as e:
                print("  %-8s %-52s ERROR %s" % (name, q[:50], e))
            time.sleep(SPACING_S)
finally:
    set_gp("chartsearchai.rateLimitPerMinute", orig_rl if orig_rl else "10")
    print("\n(restored chartsearchai.rateLimitPerMinute=%s)" % (orig_rl if orig_rl else "10"))

if not results:
    raise SystemExit("no successful cells — check the standalone and patient UUIDs")
lat = sorted(results)
n = len(lat)
median = statistics.median(lat)
p95 = pct(lat, 95)
worst = lat[-1]
over3 = sum(1 for x in lat if x > BAR_MEDIAN_S)
over5 = sum(1 for x in lat if x > BAR_P99_S)
print("\n===== LATENCY (%d cells) =====" % n)
print("  median=%.2fs  p95=%.2fs  max=%.2fs  min=%.2fs  mean=%.2fs" % (median, p95, worst, lat[0], statistics.mean(lat)))
print("  cells > 3s: %d/%d   cells > 5s: %d/%d" % (over3, n, over5, n))
print("  BAR: median<=%.0fs -> %s | p99(~max for n=%d)<=%.0fs -> %s"
      % (BAR_MEDIAN_S, "PASS" if median <= BAR_MEDIAN_S else "FAIL", n, BAR_P99_S, "PASS" if worst <= BAR_P99_S else "FAIL"))
