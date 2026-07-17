#!/usr/bin/env python3
"""A/B whether Tier-2 grounding (grounding.entailment.enabled) starves the single
llama-server decode slot and inflates the NEXT request's ttft.

For each setting (ON, OFF): warm patient C, then fire 3 CONCURRENT identical queries
(same prefix => zero prefill difference; pure slot-contention test). With --parallel 1
they serialize; later arrivals' ttft reveals queueing behind the in-flight request's
answer + (if ON) grounding calls. GP restored in finally. Interleaved order ON/OFF/ON/OFF
keeps external load comparable.
"""
import base64, json, threading, time, urllib.request

BASE = "https://chartsearchai.openmrs.org/openmrs/ws/rest/v1"
AUTH = base64.b64encode(b"admin:Admin123").decode()
GP = "chartsearchai.grounding.entailment.enabled"
P = "dda99123-1691-11df-97a5-7038c432aabf"
Q = "what medications is the patient taking?"


def _open(path, data=None, method="GET", timeout=600):
    r = urllib.request.Request(BASE + path,
        data=(json.dumps(data).encode() if data is not None else None), method=method)
    r.add_header("Authorization", "Basic " + AUTH)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    return urllib.request.urlopen(r, timeout=timeout)


def get_gp():
    rows = json.load(_open("/systemsetting?q=%s&v=full" % GP)).get("results", [])
    return (rows[0]["uuid"], rows[0].get("value")) if rows else (None, None)


def set_gp(uuid, value):
    _open("/systemsetting/" + uuid, {"value": str(value)}, "POST").read()


def stream(out, idx):
    t0 = time.monotonic(); ev = None; dl = []; ttft = astart = total = None
    try:
        r = urllib.request.Request(BASE + "/chartsearchai/search/stream",
            data=json.dumps({"patient": P, "question": Q}).encode(), method="POST")
        r.add_header("Authorization", "Basic " + AUTH)
        r.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(r, timeout=600) as resp:
            for raw in resp:
                ln = raw.decode("utf-8", "replace").rstrip("\n")
                if ln.startswith("event:"):
                    ev = ln[6:].strip(); dl = []; now = (time.monotonic() - t0) * 1000
                    if ev in ("thinking", "token") and ttft is None: ttft = now
                    if ev == "token" and astart is None: astart = now
                    if ev == "done": total = now
                elif ln.startswith("data:"): dl.append(ln[5:].lstrip())
                elif ln == "" and ev: ev, dl = None, []
    except Exception as e:
        out[idx] = ("ERR", str(e)); return
    out[idx] = (ttft, astart, total)


def burst(n=3):
    out = [None] * n
    threads = [threading.Thread(target=stream, args=(out, i)) for i in range(n)]
    for t in threads: t.start()
    for t in threads: t.join()
    return out


def fmt(v):
    return "  n/a" if v is None else ("%6.0f" % v if isinstance(v, (int, float)) else str(v))


def main():
    uuid, orig = get_gp()
    print("GP %s uuid=%s original=%r" % (GP, uuid, orig))
    if not uuid:
        print("GP not found — abort"); return
    try:
        for setting in ("true", "false", "true", "false"):
            set_gp(uuid, setting); time.sleep(2)
            _open("/chartsearchai/warmup", {"patient": P}, "POST").read()
            time.sleep(12)  # let warmup prime + settle
            res = burst(3)
            ttfts = sorted([r[0] for r in res if isinstance(r, tuple) and isinstance(r[0], (int, float))])
            print("entailment=%-5s  3 concurrent -> ttft(sorted)=%s | totals=%s"
                  % (setting, [int(x) for x in ttfts],
                     [int(r[2]) for r in res if isinstance(r, tuple) and isinstance(r[2], (int, float))]))
            time.sleep(8)
    finally:
        set_gp(uuid, orig if orig is not None else "true")
        print("\nRESTORED %s -> %r" % (GP, get_gp()[1]))


if __name__ == "__main__":
    main()
