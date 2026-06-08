#!/usr/bin/env python3
"""Live grounding-scope A/B harness for chartsearchai (measure-first gate).

Measures how citation grounding verdicts change between scoping modes on the
RUNNING standalone, for a fixed (patient, query) set. This is the measurement
gate for any change to CitationGroundingVerifier's sentence/clause scoping —
it exercises the FULL production grounding path (real querystore retrieval, the
real LLM answer, and the real batched Tier-2 entailment), which is essential
because the clause-scoping regression is an emergent BATCH-COUPLING effect that
no stubbed unit test can reproduce.

For each (patient, query) case it runs POST /chartsearchai/search under each
value of the `chartsearchai.grounding.clauseScoped` GP and records, per cited
index, the grounded verdict. Sentence-scope (clauseScoped=false) is the SAFE
baseline. Versus it:
  * a True->False flip is a REGRESSION candidate (e.g. patient 165497e8
    "any feeding problems?" cite [5], a provisional-diagnosis false negative);
  * a False->True flip is a WIN candidate (e.g. "any ear problems?" cite [89]).

The GP is saved before and restored after. Answers are grounding-independent,
so a differing answer between modes signals LLM nondeterminism (reported).

Usage:  python3 eval/grounding-scope/grounding_scope_ab.py
Env:    BASE (default http://localhost:8081/openmrs/ws/rest/v1), OMRS_USER, OMRS_PASS.
"""
import base64
import json
import os
import urllib.request

BASE = os.environ.get("BASE", "http://localhost:8081/openmrs/ws/rest/v1")
AUTH = base64.b64encode(
    ("%s:%s" % (os.environ.get("OMRS_USER", "admin"),
               os.environ.get("OMRS_PASS", "Admin123"))).encode()).decode()
GP = "chartsearchai.grounding.clauseScoped"

# (patient, question). 165497e8 = Sarah Taylor: malnutrition recorded as BOTH an
# active condition AND a provisional primary diagnosis (the compound-sentence
# shape clause-scoping targets), microstomia, etc.
CASES = [
    ("165497e8-13e0-4fa4-8190-8e6fa067c4b7", "any ear problems?"),
    ("165497e8-13e0-4fa4-8190-8e6fa067c4b7", "any feeding problems?"),
    ("165497e8-13e0-4fa4-8190-8e6fa067c4b7", "any nutritional problems?"),
    ("165497e8-13e0-4fa4-8190-8e6fa067c4b7", "what are the patient's diagnoses?"),
    ("165497e8-13e0-4fa4-8190-8e6fa067c4b7", "any mouth or swallowing problems?"),
    ("165497e8-13e0-4fa4-8190-8e6fa067c4b7", "what active conditions does the patient have?"),
]


def req(path, data=None, method="GET"):
    r = urllib.request.Request(
        BASE + path,
        data=(json.dumps(data).encode() if data is not None else None),
        method=method)
    r.add_header("Authorization", "Basic " + AUTH)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(r, timeout=300) as resp:
        b = resp.read()
        return json.loads(b) if b else {}


def get_gp(name):
    rows = req("/systemsetting?q=%s&v=full" % name).get("results", [])
    return (rows[0]["uuid"], rows[0].get("value")) if rows else (None, None)


def set_gp(name, value):
    uuid, _ = get_gp(name)
    if uuid:
        req("/systemsetting/" + uuid, {"value": str(value)}, "POST")


def search(patient, question):
    d = req("/chartsearchai/search", {"patient": patient, "question": question}, "POST")
    verdicts = {r.get("index"): r.get("grounded") for r in (d.get("references") or [])}
    return (d.get("answer", "") or "").strip(), verdicts


def run():
    orig_uuid, orig = get_gp(GP)
    print("harness BASE=%s  %s baseline value=%r\n" % (BASE, GP, orig))
    regressions, wins = 0, 0
    try:
        for patient, q in CASES:
            set_gp(GP, "false")
            s_ans, s = search(patient, q)
            set_gp(GP, "true")
            c_ans, c = search(patient, q)
            print("Q: %s" % q)
            if s_ans != c_ans:
                print("  !! answer differs between modes (LLM nondeterminism) — verdict A/B is confounded")
            idxs = sorted(set(s) | set(c))
            cells = []
            for i in idxs:
                tag = ""
                if s.get(i) is True and c.get(i) is False:
                    tag = "<REGRESSION"
                    regressions += 1
                elif s.get(i) is False and c.get(i) is True:
                    tag = "<win"
                    wins += 1
                cells.append("[%s] sent=%s clause=%s %s" % (i, s.get(i), c.get(i), tag))
            print("  " + ("\n  ".join(cells) if cells else "(no citations)"))
            print("  answer: %s\n" % (c_ans[:160] + ("…" if len(c_ans) > 160 else "")))
    finally:
        if orig_uuid:
            set_gp(GP, orig)
            print("restored %s -> %r" % (GP, get_gp(GP)[1]))
    print("\nTALLY across %d queries: clause-scope WINS=%d  REGRESSIONS=%d (vs sentence-scope safe baseline)"
          % (len(CASES), wins, regressions))
    print("GATE for a candidate scoping: must ground [89] on 'any ear problems?' AND produce ZERO")
    print("True->False regressions across all cases (i.e. wins>=1 on the ear case, regressions==0).")


if __name__ == "__main__":
    run()
