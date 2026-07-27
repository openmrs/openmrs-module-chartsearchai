#!/usr/bin/env python3
"""Repeatability probe: fire the SAME cell N times and count how often the reference set moves.

Every number this harness reports is a comparison of cited UUID sets, so this measures the noise
floor beneath all of them. Run it before trusting a small delta: a same-arm 270-cell repeat moved
drift by +26 and meanF1 by +0.011 (see README, "Run-to-run noise"), and this probe sizes that in
about ten minutes instead of two hours.

The cause is decode nondeterminism across restarts, not the pipeline — verified against
chartsearchai_audit_log, where a cell recorded identical input_tokens on nine runs while its
reference_count alternated between 9 and 0. Greedy decode removes sampling noise, not this.

Usage: repeat_probe.py <goldFile> [cells] [repeats]     (cells=0 means every cell in the gold)
Env:   OPENMRS_REST (default http://localhost:8081/openmrs/ws/rest/v1), OPENMRS_AUTH (admin:Admin123)
"""
import collections
import json
import os
import subprocess
import sys

GOLD = sys.argv[1]
NCELLS = int(sys.argv[2]) if len(sys.argv) > 2 else 12
REPEATS = int(sys.argv[3]) if len(sys.argv) > 3 else 3
BASE = os.environ.get("OPENMRS_REST", "http://localhost:8081/openmrs/ws/rest/v1") \
    + "/chartsearchai/search"
AUTH = os.environ.get("OPENMRS_AUTH", "admin:Admin123")
# The nine gold topics, matching capture_eval_local.sh.
QUESTION = {"programs": "Is the patient enrolled in any programs?",
            "allergies": "Does the patient have any allergies?",
            "medications": "What medications is the patient taking?",
            "eye": "Does the patient have any eye problems?",
            "heart": "Does the patient have any heart or cardiac problems?",
            "fractures": "Has the patient had any fractures or broken bones?",
            "kidney": "Does the patient have any kidney problems?",
            "mental": "Does the patient have any mental health or psychiatric conditions?",
            "drug-allergies": "Does the patient have any drug allergies?"}

gold = json.load(open(GOLD))
cells = sorted(gold)[:NCELLS] if NCELLS else sorted(gold)


def ask(uuid, question):
    out = subprocess.run(["curl", "-s", "-u", AUTH, "--max-time", "600",
                          "-H", "Content-Type: application/json", "-X", "POST", BASE,
                          "-d", json.dumps({"patient": uuid, "question": question})],
                         capture_output=True, text=True, stdin=subprocess.DEVNULL).stdout
    d = json.loads(out)
    return (frozenset(r.get("resourceUuid") for r in d.get("references", [])),
            (d.get("answer") or "").strip())


unstable_refs = unstable_text = 0
detail = []
for cell in cells:
    uuid, topic = cell.split("|")
    seen_refs, seen_text = collections.Counter(), collections.Counter()
    for _ in range(REPEATS):
        refs, text = ask(uuid, QUESTION[topic])
        seen_refs[refs] += 1
        seen_text[text] += 1
    if len(seen_refs) > 1:
        unstable_refs += 1
        detail.append((cell, "REFS", [len(r) for r in seen_refs]))
    elif len(seen_text) > 1:
        unstable_text += 1
        detail.append((cell, "text-only", len(seen_text)))
    print("  %-52s refs_variants=%d text_variants=%d" % (cell[:52], len(seen_refs), len(seen_text)),
          flush=True)

print("\nREPEATABILITY: %d cells x %d runs | reference-set unstable: %d (%.0f%%) | "
      "wording-only unstable: %d (%.0f%%)"
      % (len(cells), REPEATS, unstable_refs, 100.0 * unstable_refs / len(cells),
         unstable_text, 100.0 * unstable_text / len(cells)))
for d in detail:
    print("  ", d)
