#!/usr/bin/env python3
"""Cold-patient latency decomposition: what does the FIRST query on a never-warmed patient cost?

For each scenario, streams POST /chartsearchai/search/stream and timestamps every SSE event,
then scrapes the standalone log for the matching [timing] lines, decomposing the first-query
latency of a patient with no RAM-resident KV and no on-disk .bin into:

  chartBuildMs   (querystore getPatientChart + serialize; includes lazy index if any)
  previewMs      (progressive-reasoning preview pass; 0 when the GP is off)
  prefill        (llmStart -> first `thinking` byte: dominated by full-chart prompt prefill)
  reasoning      (first `thinking` -> first `token`: reasoning-field decode)
  answer         (first `token` -> `done`: answer+citations decode)

Scenarios per patient: cold first query, then a warm second query (different question) as the
in-process contrast. A `--warmup-race` patient gets POST /warmup immediately followed by the
query, reproducing what a clinician who types fast sees on chart open.

Usage:
  python3 eval/latency/cold_patient_gate.py baseline   # preview off (as configured)
  python3 eval/latency/cold_patient_gate.py preview    # flip progressiveReasoning on for 2 fresh
                                                       # cold patients, restore GP afterwards
Results land in eval/latency/results/cold-gate-<label>-<ts>.json
"""
import base64
import json
import os
import re
import sys
import time
import urllib.request

BASE = "http://localhost:8081/openmrs/ws/rest/v1"
AUTH = base64.b64encode(b"admin:Admin123").decode()
STAND = "/Users/danielkayiwa/Projects/openmrs/test/referenceapplication-standalone-3.7.0-rc.2"
LOG_GLOB = STAND + "/tomcat/logs"

# Cold candidates (no kvcache .bin as of 2026-07-16), obs counts from the DB.
COLD = {
    "donald-thompson-225": "59a5f0bb-b863-4213-9177-b883fe9f5f79",
    "donald-harris-216": "489db738-ad5f-4335-a9f1-270ec0c76ea2",
    "daniel-scott-199": "5b24c81f-2b66-41ed-8f2d-158433d531cc",
}
WARMUP_RACE = {"george-phillips-192": "b360ca49-d432-404f-9b6c-aa6a66125693"}
PREVIEW_COLD = {
    "james-thompson-181": "bd3927f4-8c75-470b-bdbb-6c92857b2205",
    "michael-turner-171": "c34d0124-76c9-4197-9f84-35e44e1317a8",
}
RACE_PREVIEW = {"barbara-miller-166": "16ca09dd-a8d4-405a-bda6-76d18ed65b25"}
# Disk-cached patient (bin from 2026-07-02) used as the model-load throwaway + restore probe.
CACHED = {"mark-williams-160": "089f766b-943c-427b-a039-672f90b0a49e"}

Q_COLD = "What medications is the patient taking?"
Q_WARM = "Does the patient have any allergies?"


def req(path, data=None, method="GET", timeout=600):
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
    for row in rows:
        if row["property"] == name:
            req("/systemsetting/" + row["uuid"], {"value": str(value)}, "POST")
            return True
    return False


def newest_log():
    files = [os.path.join(LOG_GLOB, f) for f in os.listdir(LOG_GLOB) if f.endswith(".log")]
    return max(files, key=os.path.getmtime)


def stream(uuid, question):
    """POST /search/stream, return millisecond offsets of each SSE milestone."""
    r = urllib.request.Request(BASE + "/chartsearchai/search/stream",
                               data=json.dumps({"patient": uuid, "question": question}).encode(),
                               method="POST")
    r.add_header("Authorization", "Basic " + AUTH)
    r.add_header("Content-Type", "application/json")
    r.add_header("Accept", "text/event-stream")
    t0 = time.time()
    first = {}
    last = {}
    counts = {}
    cur = None
    with urllib.request.urlopen(r, timeout=900) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").rstrip("\n")
            now = time.time() - t0
            if line.startswith("event:"):
                cur = line.split(":", 1)[1].strip()
            elif line.startswith("data:") and cur:
                first.setdefault(cur, now)
                last[cur] = now
                counts[cur] = counts.get(cur, 0) + 1
    return {"t0": t0, "first": first, "last": last, "counts": counts,
            "total_s": round(max(last.values()) if last else 0, 3)}


def scrape_timing(log_path, patient_dbid_hint, since_pos):
    """Read [timing] lines appended after since_pos; return (lines, new_pos)."""
    with open(log_path, "r", errors="replace") as f:
        f.seek(since_pos)
        chunk = f.read()
        pos = f.tell()
    lines = [l for l in chunk.splitlines() if "[timing]" in l]
    # Timing lines are duplicated by two appenders; dedupe consecutive identical payloads.
    dedup = []
    for l in lines:
        payload = l.split("[timing]", 1)[1]
        if not dedup or dedup[-1].split("[timing]", 1)[1] != payload:
            dedup.append(l)
    return dedup, pos


def parse_fields(timing_line):
    return dict(re.findall(r"(\w+)=([\w.-]+)", timing_line.split("[timing]", 1)[1]))


def run_cell(label, uuid, question, log_path, pos):
    t = stream(uuid, question)
    time.sleep(1.0)
    lines, pos = scrape_timing(log_path, None, pos)
    cell = {"label": label, "uuid": uuid, "question": question, "sse": t, "timing": lines}
    f = t["first"]
    print("%-38s prelim=%s thinking=%s token=%s done=%s total=%.1fs" % (
        label,
        ("%.1f" % f["preliminary"]) if "preliminary" in f else "-",
        ("%.1f" % f["thinking"]) if "thinking" in f else "-",
        ("%.1f" % f["token"]) if "token" in f else "-",
        ("%.1f" % f["done"]) if "done" in f else "-",
        t["total_s"]))
    for l in lines:
        print("    " + l.split("|")[-1].strip()[:200])
    return cell, pos


def main():
    label = sys.argv[1] if len(sys.argv) > 1 else "baseline"
    log_path = newest_log()
    with open(log_path) as fh:
        fh.seek(0, 2)
        pos = fh.tell()
    out = {"label": label, "started": time.strftime("%Y-%m-%d %H:%M:%S"), "cells": []}

    if label == "baseline":
        # Throwaway/model-load + disk-restore probe on the cached patient.
        for name, uuid in CACHED.items():
            cell, pos = run_cell("throwaway+restore " + name, uuid, Q_COLD, log_path, pos)
            out["cells"].append(cell)
            cell, pos = run_cell("ram-warm " + name, uuid, Q_WARM, log_path, pos)
            out["cells"].append(cell)
        # Cold-then-warm per fresh patient.
        for name, uuid in COLD.items():
            cell, pos = run_cell("COLD " + name, uuid, Q_COLD, log_path, pos)
            out["cells"].append(cell)
            cell, pos = run_cell("warm " + name, uuid, Q_WARM, log_path, pos)
            out["cells"].append(cell)
        # Warmup race: fire /warmup then query immediately (chart-open UX).
        for name, uuid in WARMUP_RACE.items():
            req("/chartsearchai/warmup", {"patient": uuid}, "POST", timeout=30)
            time.sleep(2.0)
            cell, pos = run_cell("WARMUP-RACE " + name, uuid, Q_COLD, log_path, pos)
            out["cells"].append(cell)
    elif label == "preview":
        assert set_gp("chartsearchai.progressiveReasoning.enabled", "true")
        try:
            for name, uuid in PREVIEW_COLD.items():
                cell, pos = run_cell("COLD+preview " + name, uuid, Q_COLD, log_path, pos)
                out["cells"].append(cell)
                cell, pos = run_cell("warm+preview " + name, uuid, Q_WARM, log_path, pos)
                out["cells"].append(cell)
        finally:
            set_gp("chartsearchai.progressiveReasoning.enabled", "false")
            print("progressiveReasoning.enabled restored to false")
    elif label == "race-preview":
        # Chart-open UX with preview on: warmup in flight when the query (and its preview)
        # arrives. Today the preview queues behind the warmup's whole monolithic prefill —
        # this cell is the Gate-C baseline that slot-yielding warmup must beat.
        assert set_gp("chartsearchai.progressiveReasoning.enabled", "true")
        try:
            for name, uuid in RACE_PREVIEW.items():
                req("/chartsearchai/warmup", {"patient": uuid}, "POST", timeout=30)
                time.sleep(2.0)
                cell, pos = run_cell("RACE+preview " + name, uuid, Q_COLD, log_path, pos)
                out["cells"].append(cell)
        finally:
            set_gp("chartsearchai.progressiveReasoning.enabled", "false")
            print("progressiveReasoning.enabled restored to false")
    else:
        print("unknown label", label)
        return

    dest = os.path.join(os.path.dirname(__file__), "results",
                        "cold-gate-%s-%s.json" % (label, time.strftime("%Y%m%d-%H%M%S")))
    with open(dest, "w") as fh:
        json.dump(out, fh, indent=1)
    print("saved", dest)


if __name__ == "__main__":
    main()
