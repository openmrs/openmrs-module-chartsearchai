#!/usr/bin/env python3
"""Local validation of the --parallel decode-slot fix.

Same standalone/omod/model; only chartsearchai.llm.parallelSlots changes (the server
restarts the llama-server on change). For each slot count, fire N concurrent identical
queries (same prefix => identical prefill) and record each one's ttft/answer_start. With
1 slot the requests serialize (later arrivals' ttft balloons); with >1 they run
concurrently. Rate limit raised for the run, restored after.
"""
import base64, json, threading, time, urllib.request

B = "http://localhost:8081/openmrs/ws/rest/v1"
AUTH = base64.b64encode(b"admin:Admin123").decode()
P = "165497e8-13e0-4fa4-8190-8e6fa067c4b7"  # Sarah Taylor (local dataset)
Q = "what active conditions does the patient have?"
SLOTS = "chartsearchai.llm.parallelSlots"
RATE = "chartsearchai.rateLimitPerMinute"
N = 3


def _open(path, data=None, method="GET", timeout=600):
    r = urllib.request.Request(B + path,
        data=(json.dumps(data).encode() if data is not None else None), method=method)
    r.add_header("Authorization", "Basic " + AUTH)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    return urllib.request.urlopen(r, timeout=timeout)


def get_gp(name):
    rows = json.load(_open("/systemsetting?q=%s&v=full" % name)).get("results", [])
    return (rows[0]["uuid"], rows[0].get("value")) if rows else (None, None)


def set_gp(uuid, value):
    _open("/systemsetting/" + uuid, {"value": str(value)}, "POST").read()


def stream(out=None, idx=None):
    t0 = time.monotonic(); ev = None; ttft = astart = total = None
    try:
        r = urllib.request.Request(B + "/chartsearchai/search/stream",
            data=json.dumps({"patient": P, "question": Q}).encode(), method="POST")
        r.add_header("Authorization", "Basic " + AUTH)
        r.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(r, timeout=600) as resp:
            for raw in resp:
                ln = raw.decode("utf-8", "replace").rstrip("\n")
                if ln.startswith("event:"):
                    ev = ln[6:].strip(); now = (time.monotonic() - t0) * 1000
                    if ev in ("thinking", "token") and ttft is None: ttft = now
                    if ev == "token" and astart is None: astart = now
                    if ev == "done": total = now
    except Exception as e:
        if out is not None: out[idx] = ("ERR", str(e)[:80])
        return ("ERR", str(e)[:80])
    if out is not None: out[idx] = (ttft, astart, total)
    return (ttft, astart, total)


def burst(n):
    out = [None] * n
    ts = [threading.Thread(target=stream, args=(out, i)) for i in range(n)]
    for t in ts: t.start()
    for t in ts: t.join()
    return out


def main():
    su, sv = get_gp(SLOTS)
    ru, rv = get_gp(RATE)
    print("parallelSlots original=%r ; rateLimit original=%r" % (sv, rv))
    try:
        if ru: set_gp(ru, 1000)
        for slots in (1, 4):
            set_gp(su, slots)
            print("\n--- parallelSlots=%d ---" % slots)
            # trigger server restart with new --parallel + absorb GGUF cold load + prime KV
            _open("/chartsearchai/warmup", {"patient": P}, "POST").read()
            time.sleep(3)
            warm = stream()  # throwaway: cold model load lands here
            print("  warmup query: ttft=%s answerStart=%s" % (warm[0], warm[1]))
            time.sleep(3)
            res = burst(N)
            ok = [r for r in res if isinstance(r[0], (int, float))]
            ttfts = sorted(int(r[0]) for r in ok)
            astarts = sorted(int(r[1]) for r in ok if r[1] is not None)
            print("  %d concurrent -> ttft(sorted)=%s" % (N, ttfts))
            print("                   answerStart(sorted)=%s" % astarts)
            errs = [r[1] for r in res if r[0] == "ERR"]
            if errs: print("  errors: %s" % errs)
            time.sleep(4)
    finally:
        if su: set_gp(su, sv if sv is not None else "4")
        if ru: set_gp(ru, rv if rv is not None else "10")
        print("\nrestored parallelSlots=%r rateLimit=%r" % (get_gp(SLOTS)[1], get_gp(RATE)[1]))


if __name__ == "__main__":
    main()
