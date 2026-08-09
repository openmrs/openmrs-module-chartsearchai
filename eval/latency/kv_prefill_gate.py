#!/usr/bin/env python3
"""KV-prefill MEASUREMENT: how much first-query latency would a KV prewarm remove?

This is a measurement with a hard precondition, not a pass/fail gate — it reports a delta
and has no ship bar to compare it against, despite the `_gate` in its filename. What it
DOES now enforce, and exits non-zero on, is its own precondition: that the "cold" arm is
actually cold. Read the delta; do not read exit 0 as a verdict.

For each patient, on a fresh llama-server process (empty RAM prompt-cache pool):
  1. reindex (warm the querystore index WITHOUT touching the LLM, so the cold reading
     isolates LLM prefill, not the ~42s lazy index)
  2. delete the patient's on-disk KV .bin -> the next query is a GENUINE cold full prefill
  3. cold  /search/stream  -> record the `prefill` phase (t0 -> first output)
  4. warm  /search/stream  (different question, same patient: KV prefix now RAM-resident)
                           -> record the `prefill` phase again
  delta = prefill_cold - prefill_warm  == exactly what ANY prewarm (reactive or bootstrap)
  could remove from a first query. This is the number the prewarm-bootstrap decision hinges on.

WHY THE PRECONDITION IS THE WHOLE POINT (#179 item 7). Step 2 is the only thing making the
cold arm cold, and its result was thrown away: `delete_kv(uuid)` returned a count that no
caller read, and `KVDIR` was a hardcoded absolute path into a `3.7.0-SNAPSHOT` install that
no longer exists on this box. A wrong directory therefore deleted nothing, every "cold" arm
ran against a warm on-disk cache, and the script printed a small, believable, entirely
fabricated DELTA — the failure mode where the instrument reports success because it never
did anything. Measured 2026-08-09: the old constant resolved to a directory that is absent,
while the real cache held 8 `.bin` files, 4 of them for corpus patients. So it would have
deleted nothing for every patient, silently.

Now: KVDIR is resolved from CHARTSEARCHAI_KVDIR (falling back to the 3.7.1 standalone), the
directory must exist and hold at least one `.bin` before ANY request is made, and each
patient's deleted count is printed. A patient whose cold arm deleted nothing has an
unattributable delta and the run exits 3 rather than folding it into the median.

Run against the live standalone on :8081. Prints a per-patient table + median delta.

Usage: kv_prefill_gate.py
       kv_prefill_gate.py --selftest   (precondition checks only; needs no server and no LLM)
"""
import base64
import glob
import json
import os
import statistics
import subprocess
import sys
import time
import urllib.request

BASE = "http://localhost:8081/openmrs/ws/rest/v1"
AUTH = base64.b64encode(b"admin:Admin123").decode()
# Overridable because the previous hardcoded constant rotted into a dead path and nothing
# noticed. The default is the standalone this harness is run against; an explicit env value
# wins. Either way `require_cold_cache_dir()` below refuses to proceed on a directory that
# does not look like a KV cache.
KVDIR = os.environ.get("CHARTSEARCHAI_KVDIR") or os.path.expanduser(
    "~/Downloads/referenceapplication-standalone-3.7.1/appdata/chartsearchai/kvcache")

GP_RATE = "chartsearchai.rateLimitPerMinute"
GP_PROGRESSIVE = "chartsearchai.progressiveReasoning.enabled"

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


def _exact_gp(name):
    """The row whose `property` IS `name`. `?q=` is a SUBSTRING search, so it can return a sibling
    (or nothing), and both callers below used to take rows[0] blind: the write then silently
    no-opped, leaving both arms on the same config while the run printed a plausible baseline, and
    the read captured a SIBLING's value which the restore later wrote over the real property.
    Raises rather than returning None, because no caller here has a correct behaviour without it."""
    rows = req("/systemsetting?q=%s&v=full" % name).get("results", [])
    for r in rows:
        if r.get("property") == name:
            return r
    raise SystemExit("ERROR: global property %r not found (search returned %d row(s): %s) — "
                     "refusing to continue, since an unset GP leaves both arms identical"
                     % (name, len(rows), [r.get("property") for r in rows][:4]))


def get_gp(name):
    return _exact_gp(name).get("value")


def set_gp(name, value):
    req("/systemsetting/" + _exact_gp(name)["uuid"], {"value": str(value)}, "POST")


def kv_files(uuid):
    return glob.glob(os.path.join(KVDIR, uuid + "-*.bin"))


def delete_kv(uuid):
    n = 0
    for f in kv_files(uuid):
        os.remove(f)
        n += 1
    return n


def require_cold_cache_dir():
    """Refuses to measure anything until the KV cache directory is real and populated.

    This runs BEFORE any network call so the failure is cheap and unambiguous. Two distinct
    conditions, because they need different responses from an operator: a directory that is
    absent or not a directory is a stale/wrong KVDIR (the #179 defect); a directory that exists
    but holds no `.bin` at all means llama-server was never launched with --slot-save-path, so
    there is no on-disk KV to delete and `cold` and `warm` would differ only by RAM cache."""
    if not os.path.isdir(KVDIR):
        raise SystemExit(
            "ERROR: KVDIR is not a directory: %s\n"
            "  Nothing would be deleted, so every 'cold' arm would run against a warm on-disk\n"
            "  cache and the DELTA would be fabricated. Set CHARTSEARCHAI_KVDIR to the running\n"
            "  standalone's <appdata>/chartsearchai/kvcache." % KVDIR)
    bins = glob.glob(os.path.join(KVDIR, "*.bin"))
    if not bins:
        raise SystemExit(
            "ERROR: no *.bin in KVDIR: %s\n"
            "  The disk KV cache is empty, so there is no cold arm to create. Check that\n"
            "  chartsearchai.llm.kvCache.path is set and the server was launched with\n"
            "  --slot-save-path, then run one query per patient to populate it." % KVDIR)
    print("KVDIR ok: %s (%d .bin present)" % (KVDIR, len(bins)))


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


def main():
    # The precondition first, before any request: a wrong KVDIR must cost nothing to discover,
    # and must not be discoverable only by reading a fabricated DELTA afterwards.
    require_cold_cache_dir()

    # --- config: disable rate limit so the run isn't throttled; record + restore -------------
    orig_rl = get_gp(GP_RATE)
    set_gp(GP_RATE, "0")
    # Turn progressiveReasoning OFF so the first 'thinking' event is the full-chart prefill
    # boundary with no preview pass in front of it. Restored in finally.
    orig_pr = get_gp(GP_PROGRESSIVE)
    set_gp(GP_PROGRESSIVE, "false")
    print("KV-prefill measurement | model=E2B | progressiveReasoning OFF for clean prefill\n")
    print("%-15s %6s %5s | %8s %8s | %7s | %6s" %
          ("patient", "cites", "kvdel", "cold_pf", "warm_pf", "DELTA", "cold_tot"))

    deltas, not_cold = [], []
    try:
        for name, uuid in PATIENTS.items():
            req("/querystore/reindex", {"patient": uuid}, "POST")   # warm index, no LLM
            # The count is READ now. 0 means this patient had no on-disk KV to remove, so the
            # "cold" arm is only as cold as the RAM pool happens to be — not a cold full
            # prefill, and its delta is not the number this script claims to report.
            deleted = delete_kv(uuid)
            if deleted == 0:
                not_cold.append(name)
            time.sleep(1)
            c_tot, c_pf, _, _, c_ct = stream_prefill(uuid, Q_COLD)  # COLD: full prefill
            time.sleep(2)
            _, w_pf, _, _, _ = stream_prefill(uuid, Q_WARM)         # WARM: KV reused
            d = c_pf - w_pf
            deltas.append(d)
            print("%-15s %6d %5s | %7.2fs %7.2fs | %6.2fs | %6.1fs" %
                  (name, c_ct, deleted if deleted else "NONE", c_pf, w_pf, d, c_tot))
            time.sleep(2)
    finally:
        set_gp(GP_RATE, orig_rl if orig_rl else "10")
        set_gp(GP_PROGRESSIVE, orig_pr if orig_pr else "true")

    if not deltas:
        raise SystemExit("ERROR: no cells completed — nothing to report")
    print("\n----- KV-prefill delta (cold - warm), n=%d -----" % len(deltas))
    print("  median=%.2fs  min=%.2fs  max=%.2fs  mean=%.2fs" %
          (statistics.median(deltas), min(deltas), max(deltas), statistics.mean(deltas)))
    print("  >>> a prewarm removes ~%.1fs (median) from a first query on THIS box <<<"
          % statistics.median(deltas))
    if not_cold:
        print("\n  !! %d patient(s) had NO .bin to delete, so their 'cold' arm was not a cold "
              "full prefill and the median above is not attributable: %s"
              % (len(not_cold), not_cold))
        print("  Exiting 3. This is the #179 failure mode — re-run after populating the cache "
              "for these patients, or drop them from PATIENTS.")
        sys.exit(3)
    print("\n  MEASUREMENT (not a gate): there is no ship bar here. Exit 0 means the cold arms "
          "were genuinely cold, not that the delta is acceptable.")


def selftest():
    """Precondition checks only — no server, no LLM, no model. Runs in CI.

    Pins the two conditions whose absence was the defect: an absent KVDIR and an empty one must
    both refuse to measure. Each is asserted by running this script as a subprocess with KVDIR
    overridden, which is also the only way to prove the check fires BEFORE the first request
    (with no server running, reaching the network at all shows up as a different error)."""
    import shutil
    import tempfile
    failures = []

    def check(name, kvdir, want_fragment):
        env = dict(os.environ, CHARTSEARCHAI_KVDIR=kvdir)
        p = subprocess.Popen([sys.executable, os.path.abspath(__file__)],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
        out = p.communicate()[0].decode("utf-8", "replace")
        if p.returncode == 0:
            failures.append("%s: exited 0 — the precondition did not fire\n%s" % (name, out))
        elif want_fragment not in out:
            failures.append("%s: missing %r\n%s" % (name, want_fragment, out))
        elif "urlopen" in out or "Connection refused" in out:
            failures.append("%s: reached the network before checking KVDIR\n%s" % (name, out))
        else:
            print("  ok  %-22s exit=%d" % (name, p.returncode))

    check("absent-kvdir", os.path.join(tempfile.gettempdir(), "kv-does-not-exist-179"),
          "KVDIR is not a directory")
    empty = tempfile.mkdtemp(prefix="kv-empty-")
    try:
        check("empty-kvdir", empty, "no *.bin in KVDIR")
    finally:
        shutil.rmtree(empty, ignore_errors=True)

    if failures:
        for f in failures:
            print("\nFAIL %s" % f)
        sys.exit("selftest FAILED (%d)" % len(failures))
    print("selftest OK (2 precondition cases)")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        selftest()
    else:
        main()
