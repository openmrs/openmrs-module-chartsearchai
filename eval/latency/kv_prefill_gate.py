#!/usr/bin/env python3
"""KV-prefill measurement gate: how much first-query latency would a KV prewarm remove?

For each patient, on a fresh llama-server process (empty RAM prompt-cache pool):
  1. reindex (warm the querystore index WITHOUT touching the LLM, so the cold reading
     isolates LLM prefill, not the ~42s lazy index)
  2. delete the patient's on-disk KV .bin -> the next query is a GENUINE cold full prefill
  3. cold  /search/stream  -> record the `prefill` phase (t0 -> first output)
  4. warm  /search/stream  (different question, same patient: KV prefix now RAM-resident)
                           -> record the `prefill` phase again
  delta = prefill_cold - prefill_warm  == exactly what ANY prewarm (reactive or bootstrap)
  could remove from a first query. This is the number the prewarm-bootstrap decision hinges on.

Run against the live standalone on :8081. Prints a per-patient table + median delta.
"""
import base64
import glob
import json
import os
import statistics
import time
import urllib.request

BASE = "http://localhost:8081/openmrs/ws/rest/v1"
AUTH = base64.b64encode(b"admin:Admin123").decode()
KVDIR = ("/Users/danielkayiwa/Projects/openmrs/test/"
         "referenceapplication-standalone-3.7.0-SNAPSHOT/appdata/chartsearchai/kvcache")

PATIENTS = {
    # Large-chart patients (KV .bin 78-142MB) -- the case a prewarm is meant to help.
    "sarah-taylor":   "165497e8-13e0-4fa4-8190-8e6fa067c4b7",
    "richard-jones":  "2c384236-7c4f-4971-9c57-05d0069d3bbd",
    "karen-sanchez":  "60369905-12b3-4c57-99c4-817aa7d3ae4c",
    "kevin-brown":    "636ac9f1-bcc7-452a-b51e-32a2c0399939",
    "steven-white":   "f9e185d5-ef1e-43de-87d0-aba47aae4957",
    "susan-young":    "0178f06f-c6e6-4fe0-b1ae-1c2d8490ed5f",
}
# Two DISTINCT questions so the warm query is a real second query (same question would also
# hit the answer cache). The records prefix is question-independent, so KV reuse holds.
Q_COLD = "What medications is the patient taking?"
Q_WARM = "Does the patient have any allergies?"


def req(path, data=None, method="GET", timeout=180):
    r = urllib.request.Request(BASE + path,
                               data=(json.dumps(data).encode() if data is not None else None),
                               method=method)
    r.add_header("Authorization", "Basic " + AUTH)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        b = resp.read()
        return json.loads(b) if b else {}


def set_gp(name, value):
    rows = req("/systemsetting?q=%s&v=full" % name).get("results", [])
    if rows:
        req("/systemsetting/" + rows[0]["uuid"], {"value": str(value)}, "POST")


def delete_kv(uuid):
    n = 0
    for f in glob.glob(os.path.join(KVDIR, uuid + "-*.bin")):
        os.remove(f)
        n += 1
    return n


def stream_prefill(uuid, q):
    """Return (total, prefill, reasoning, answer, cites). prefill = t0 -> first SSE output."""
    r = urllib.request.Request(BASE + "/chartsearchai/search/stream",
                               data=json.dumps({"patient": uuid, "question": q}).encode(),
                               method="POST")
    r.add_header("Authorization", "Basic " + AUTH)
    r.add_header("Content-Type", "application/json")
    r.add_header("Accept", "text/event-stream")
    t0 = time.time()
    mark, cur, cites = {}, None, 0
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
                        cites = len(json.loads(line[5:].strip()).get("references") or [])
                    except Exception:
                        pass
    total = time.time() - t0
    # Full-chart prefill boundary = t0 -> first 'thinking' (full-chart reasoning start).
    # With progressiveReasoning OFF this is the first event, so it's pure prompt prefill +
    # TTFT, exactly what a KV prewarm removes. Fall back to first 'token' if no thinking.
    if "thinking" in mark:
        prefill = mark["thinking"][0]
    elif "token" in mark:
        prefill = mark["token"][0]
    else:
        prefill = min((v[0] for v in mark.values()), default=0.0)
    tk0, tk1 = mark.get("token", (0, 0))
    return total, prefill, 0.0, tk1 - tk0, cites


# --- config: disable rate limit so the run isn't throttled; record + restore -------------
rl = req("/systemsetting?q=chartsearchai.rateLimitPerMinute&v=full").get("results", [])
orig_rl = rl[0].get("value") if rl else None
set_gp("chartsearchai.rateLimitPerMinute", "0")
# Turn progressiveReasoning OFF so the first 'thinking' event is the full-chart prefill
# boundary with no preview pass in front of it. Restored in finally.
pr = req("/systemsetting?q=chartsearchai.progressiveReasoning.enabled&v=full").get("results", [])
orig_pr = pr[0].get("value") if pr else None
set_gp("chartsearchai.progressiveReasoning.enabled", "false")
print("KV-prefill gate | model=E2B | box=M1 Max (GPU/Metal) | progressiveReasoning OFF for clean prefill\n")
print("%-15s %6s | %8s %8s | %7s | %6s" %
      ("patient", "cites", "cold_pf", "warm_pf", "DELTA", "cold_tot"))

deltas = []
try:
    for name, uuid in PATIENTS.items():
        req("/querystore/reindex", {"patient": uuid}, "POST")     # warm index, no LLM
        delete_kv(uuid)                                            # force genuine cold prefill
        time.sleep(1)
        c_tot, c_pf, _, _, c_ct = stream_prefill(uuid, Q_COLD)     # COLD: full prefill
        time.sleep(2)
        _, w_pf, _, _, _ = stream_prefill(uuid, Q_WARM)            # WARM: KV reused
        d = c_pf - w_pf
        deltas.append(d)
        print("%-15s %6d | %7.2fs %7.2fs | %6.2fs | %6.1fs" %
              (name, c_ct, c_pf, w_pf, d, c_tot))
        time.sleep(2)
finally:
    set_gp("chartsearchai.rateLimitPerMinute", orig_rl if orig_rl else "10")
    set_gp("chartsearchai.progressiveReasoning.enabled", orig_pr if orig_pr else "true")

print("\n----- KV-prefill delta (cold - warm), n=%d -----" % len(deltas))
print("  median=%.2fs  min=%.2fs  max=%.2fs  mean=%.2fs" %
      (statistics.median(deltas), min(deltas), max(deltas), statistics.mean(deltas)))
print("  >>> a prewarm removes ~%.1fs (median) from a first query on THIS box <<<"
      % statistics.median(deltas))
