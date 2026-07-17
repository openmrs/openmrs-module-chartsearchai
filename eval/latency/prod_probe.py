#!/usr/bin/env python3
"""Read-only production TTFT probe for chartsearchai.openmrs.org.

Measures client-side SSE milestones (ttft / answer_start / refs / total) for a
few (patient, query) cases against the LIVE demo. Does NOT mutate any global
property (so it leaves prod config untouched) and paces requests to respect the
rateLimitPerMinute=10 GP. answer_start_ms == "first answer text" == the metric
the operator cares about.

Per patient: a /warmup (chart-open flow), then the SAME query twice — round 1
(cold KV) and round 2 (warm KV reuse) — to expose prefill/KV effects.
"""
import base64, json, os, time, urllib.request

BASE = "https://chartsearchai.openmrs.org/openmrs/ws/rest/v1"
AUTH = base64.b64encode(b"admin:Admin123").decode()
PACE_S = 7.0  # ~8/min, under the 10/min limit

CASES = [
    ("dd92543f-1691-11df-97a5-7038c432aabf", "what are the patient's diagnoses?"),
    ("dd9836d7-1691-11df-97a5-7038c432aabf", "what active conditions does the patient have?"),
    ("dda99123-1691-11df-97a5-7038c432aabf", "what medications is the patient taking?"),
]


def _req(path, data=None, method="GET", timeout=600):
    r = urllib.request.Request(
        BASE + path,
        data=(json.dumps(data).encode() if data is not None else None),
        method=method)
    r.add_header("Authorization", "Basic " + AUTH)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    return urllib.request.urlopen(r, timeout=timeout)


def stream_search(patient, question):
    r = urllib.request.Request(
        BASE + "/chartsearchai/search/stream",
        data=json.dumps({"patient": patient, "question": question}).encode(),
        method="POST")
    r.add_header("Authorization", "Basic " + AUTH)
    r.add_header("Content-Type", "application/json")
    t0 = time.monotonic()
    m = {"ttft_ms": None, "answer_start_ms": None, "refs_ms": None, "total_ms": None}
    event, data_lines, done = None, [], None
    with urllib.request.urlopen(r, timeout=600) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").rstrip("\n")
            if line.startswith("event:"):
                event = line[6:].strip()
                data_lines = []
                now = (time.monotonic() - t0) * 1000.0
                if event in ("thinking", "token") and m["ttft_ms"] is None:
                    m["ttft_ms"] = now
                if event == "token" and m["answer_start_ms"] is None:
                    m["answer_start_ms"] = now
                if event == "references" and m["refs_ms"] is None:
                    m["refs_ms"] = now
                if event == "done":
                    m["total_ms"] = now
            elif line.startswith("data:"):
                data_lines.append(line[5:].lstrip())
            elif line == "" and event is not None:
                if event == "done":
                    done = json.loads("\n".join(data_lines))
                elif event == "error":
                    raise RuntimeError("SSE error: %s" % "\n".join(data_lines))
                event, data_lines = None, []
    answer = (done or {}).get("answer", "") or ""
    return m, answer.strip()


def fmt(v):
    return "   n/a" if v is None else "%6.0f" % v


def run():
    print("PROD probe BASE=%s (no GP mutation; pace=%.0fs)\n" % (BASE, PACE_S))
    results = []
    for patient, q in CASES:
        t0 = time.monotonic()
        try:
            _req("/chartsearchai/warmup", {"patient": patient}, "POST").read()
        except Exception as e:
            print("warmup %s failed: %s" % (patient[:8], e))
        print("warmup patient=%s %.0fms" % (patient[:8], (time.monotonic() - t0) * 1000))
        time.sleep(PACE_S)
        for rnd in (1, 2):
            try:
                m, answer = stream_search(patient, q)
            except Exception as e:
                print("  r%d ERROR %s: %s" % (rnd, q, e))
                time.sleep(PACE_S)
                continue
            results.append({"round": rnd, "patient": patient, "question": q,
                            "client": m, "answer": answer})
            print("  r%d ttft=%s answerStart=%s refs=%s total=%s  %s"
                  % (rnd, fmt(m["ttft_ms"]), fmt(m["answer_start_ms"]),
                     fmt(m["refs_ms"]), fmt(m["total_ms"]), q))
            time.sleep(PACE_S)
        # show one answer per patient so we can eyeball reasoning length effects
        if results:
            print("    answer: %s\n" % (results[-1]["answer"][:160].replace("\n", " ")))

    print("=== per-round medians (ms) ===")
    for rnd in (1, 2):
        rows = [r for r in results if r["round"] == rnd]
        if not rows:
            continue
        for k in ("ttft_ms", "answer_start_ms", "refs_ms", "total_ms"):
            vals = sorted(r["client"][k] for r in rows if r["client"][k] is not None)
            med = vals[len(vals) // 2] if vals else None
            print("r%d %-16s median=%s  (n=%d)" % (rnd, k, fmt(med), len(vals)))
        print()

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "results", "prod-ttft-probe.json")
    with open(out, "w") as f:
        json.dump({"base": BASE, "results": results}, f, indent=2)
    print("written %s" % out)


if __name__ == "__main__":
    run()
