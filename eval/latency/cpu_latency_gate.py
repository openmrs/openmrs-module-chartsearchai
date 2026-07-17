#!/usr/bin/env python3
"""CPU cold-patient latency gate: fullChart vs queryScoped, end-to-end through the real
pipeline with llama-server forced to CPU (-ngl 0 via the bin/llama-server wrapper — install
it first; see install_cpu_wrapper in this directory's README or the session notes).

For each (mode, patient, question) cell: set chartsearchai.chartMode, fire /search/stream,
record SSE milestones + the server's [timing] lines. Patients must be GENUINELY cold: no
kvcache .bin (checked), fresh llama-server RAM pool (restart the server between modes by
killing the llama-server child — the engine relaunches it lazily).

Usage: python3 eval/latency/cpu_latency_gate.py <mode:fullChart|queryScoped> <patientUuid:label> ...
Results: eval/latency/results/cpu-gate-<mode>-<ts>.json
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cold_patient_gate as cg


def main():
    mode = sys.argv[1]
    cells = sys.argv[2:]
    assert mode in ("fullChart", "queryScoped"), mode
    assert cg.set_gp("chartsearchai.chartMode", mode), "chartMode GP not found"
    log_path = cg.newest_log()
    with open(log_path) as fh:
        fh.seek(0, 2)
        pos = fh.tell()
    out = {"label": "cpu-" + mode, "started": time.strftime("%Y-%m-%d %H:%M:%S"), "cells": []}
    try:
        for spec in cells:
            uuid, _, label = spec.partition(":")
            for q in (cg.Q_COLD, "Does the patient have any kidney problems?"):
                cell, pos = cg.run_cell("%s %s [%s]" % (mode, label or uuid[:8], q[:28]),
                                        uuid, q, log_path, pos)
                out["cells"].append(cell)
    finally:
        cg.set_gp("chartsearchai.chartMode", "fullChart")
        print("chartMode restored to fullChart")
    dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results",
                        "cpu-gate-%s-%s.json" % (mode, time.strftime("%Y%m%d-%H%M%S")))
    with open(dest, "w") as fh:
        json.dump(out, fh, indent=1)
    print("saved", dest)


if __name__ == "__main__":
    main()
