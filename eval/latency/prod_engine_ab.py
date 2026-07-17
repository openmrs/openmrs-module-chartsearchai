#!/usr/bin/env python3
"""A/B the prod LLM engine for TTFT: flip chartsearchai.llm.engine local->remote,
re-probe answer_start_ms, then ALWAYS restore the original value (try/finally).

Canary query first: if remote errors (e.g. missing API key runtime property),
abort and restore without running the full probe. Paced under rateLimitPerMinute.
"""
import base64, json, time, urllib.request

BASE = "https://chartsearchai.openmrs.org/openmrs/ws/rest/v1"
AUTH = base64.b64encode(b"admin:Admin123").decode()
GP_ENGINE = "chartsearchai.llm.engine"
PACE_S = 7.0

CASES = [
    ("dd92543f-1691-11df-97a5-7038c432aabf", "what are the patient's diagnoses?"),
    ("dd9836d7-1691-11df-97a5-7038c432aabf", "what active conditions does the patient have?"),
    ("dda99123-1691-11df-97a5-7038c432aabf", "what medications is the patient taking?"),
]


def _open(path, data=None, method="GET", timeout=600):
    r = urllib.request.Request(
        BASE + path,
        data=(json.dumps(data).encode() if data is not None else None),
        method=method)
    r.add_header("Authorization", "Basic " + AUTH)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    return urllib.request.urlopen(r, timeout=timeout)


def get_gp(name):
    rows = json.load(_open("/systemsetting?q=%s&v=full" % name)).get("results", [])
    return (rows[0]["uuid"], rows[0].get("value")) if rows else (None, None)


def set_gp(uuid, value):
    _open("/systemsetting/" + uuid, {"value": str(value)}, "POST").read()


def stream(patient, q):
    r = urllib.request.Request(
        BASE + "/chartsearchai/search/stream",
        data=json.dumps({"patient": patient, "question": q}).encode(), method="POST")
    r.add_header("Authorization", "Basic " + AUTH)
    r.add_header("Content-Type", "application/json")
    t0 = time.monotonic()
    ttft = astart = total = None
    ev, dl, done = None, [], None
    with urllib.request.urlopen(r, timeout=600) as resp:
        for raw in resp:
            ln = raw.decode("utf-8", "replace").rstrip("\n")
            if ln.startswith("event:"):
                ev = ln[6:].strip(); dl = []
                now = (time.monotonic() - t0) * 1000
                if ev in ("thinking", "token") and ttft is None: ttft = now
                if ev == "token" and astart is None: astart = now
                if ev == "done": total = now
            elif ln.startswith("data:"):
                dl.append(ln[5:].lstrip())
            elif ln == "" and ev:
                if ev == "done": done = json.loads("\n".join(dl))
                elif ev == "error": raise RuntimeError("SSE error: %s" % "\n".join(dl))
                ev, dl = None, []
    ans = (done or {}).get("answer", "") or ""
    return ttft, astart, total, ans.strip()


def fmt(v):
    return "   n/a" if v is None else "%6.0f" % v


def main():
    uuid, orig = get_gp(GP_ENGINE)
    print("engine GP uuid=%s original=%r" % (uuid, orig))
    if not uuid:
        print("cannot find engine GP — abort"); return
    results = []
    try:
        set_gp(uuid, "remote")
        print("set %s = remote\n" % GP_ENGINE)
        time.sleep(2)
        # canary
        p, q = CASES[0]
        try:
            ttft, astart, total, ans = stream(p, q)
            print("CANARY ok: ttft=%s answerStart=%s total=%s\n  ans: %s\n"
                  % (fmt(ttft), fmt(astart), fmt(total), ans[:140].replace("\n", " ")))
            results.append((1, p, q, ttft, astart, total, ans))
        except Exception as e:
            print("CANARY FAILED (remote likely misconfigured: %s) — aborting, will restore" % e)
            return
        time.sleep(PACE_S)
        # full probe: remaining cases r1, then all r2
        for p, q in CASES[1:]:
            try:
                ttft, astart, total, ans = stream(p, q)
                print("r1 ttft=%s answerStart=%s total=%s  %s" % (fmt(ttft), fmt(astart), fmt(total), q))
                results.append((1, p, q, ttft, astart, total, ans))
            except Exception as e:
                print("r1 ERROR %s: %s" % (q, e))
            time.sleep(PACE_S)
        for p, q in CASES:
            try:
                ttft, astart, total, ans = stream(p, q)
                print("r2 ttft=%s answerStart=%s total=%s  %s" % (fmt(ttft), fmt(astart), fmt(total), q))
                results.append((2, p, q, ttft, astart, total, ans))
            except Exception as e:
                print("r2 ERROR %s: %s" % (q, e))
            time.sleep(PACE_S)
    finally:
        set_gp(uuid, orig if orig is not None else "local")
        _, now = get_gp(GP_ENGINE)
        print("\nRESTORED %s -> %r" % (GP_ENGINE, now))

    if results:
        print("\n=== REMOTE engine medians (ms) ===")
        for rnd in (1, 2):
            rows = [r for r in results if r[0] == rnd]
            if not rows: continue
            for idx, name in ((3, "ttft"), (4, "answer_start"), (5, "total")):
                vals = sorted(r[idx] for r in rows if r[idx] is not None)
                med = vals[len(vals) // 2] if vals else None
                print("r%d %-13s median=%s  (n=%d)" % (rnd, name, fmt(med), len(vals)))
            print()
    with open("eval/latency/results/prod-engine-ab-remote.json", "w") as f:
        json.dump([{"round": r[0], "patient": r[1], "q": r[2], "ttft": r[3],
                    "answer_start": r[4], "total": r[5], "answer": r[6]} for r in results],
                  f, indent=2)


if __name__ == "__main__":
    main()
