#!/usr/bin/env python3
"""Paired A/B on the date-explaining system-prompt sentence, INTERLEAVED per query.

Every arm measured so far was a block (all of A, then all of B), so session drift -- which this
install has in abundance -- is perfectly confounded with the arm. This alternates the two prompts
query by query on the same cell, so any drift hits both arms equally and the pairing cancels it.

A = the shipped prompt (contains the sentence explaining the date line)
B = the same prompt with that sentence removed

Metric: does the answer append a measurement list after a negative verdict? Both prompts already
forbid it ("cite nothing after a no-record verdict - do not list vital signs").
"""
import json, subprocess, sys, pathlib, collections

CELLS = [l.strip() for l in open(sys.argv[1]) if l.strip()]
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 4
BASE = "http://localhost:8081/openmrs/ws/rest/v1"
Q = "Does the patient have any heart or cardiac problems?"
A_PROMPT = ""                                              # empty GP -> shipped default
B_PROMPT = pathlib.Path("/tmp/prompt-nodate.txt").read_text()


def curl(args):
    return subprocess.run(["curl", "-s", "-u", "admin:Admin123"] + args,
                          capture_output=True, text=True, stdin=subprocess.DEVNULL).stdout


mode = json.loads(curl([BASE + "/systemsetting/chartsearchai.chartMode?v=custom:(value)"]))["value"]
if mode != "queryScoped":
    sys.exit("REFUSING: chartMode is %s" % mode)
print("chartMode=%s | %d cells x %d rounds, interleaved" % (mode, len(CELLS), ROUNDS))


def set_prompt(v):
    curl(["-H", "Content-Type: application/json", "-X", "POST",
          BASE + "/systemsetting/chartsearchai.llm.systemPrompt", "-d", json.dumps({"value": v})])


def ask(uuid):
    out = curl(["--max-time", "600", "-H", "Content-Type: application/json", "-X", "POST",
                BASE + "/chartsearchai/search",
                "-d", json.dumps({"patient": uuid, "question": Q})])
    d = json.loads(out)
    a = d.get("answer") or ""
    return any(t in a for t in ("mmHg", "Blood pressure", "beats/min", "Pulse"))


hits = collections.Counter(); runs = collections.Counter(); pairs = collections.Counter()
for r in range(ROUNDS):
    for uuid in CELLS:
        # alternate which arm goes first, so ordering within a pair cannot favour either
        order = [("A", A_PROMPT), ("B", B_PROMPT)] if (r + CELLS.index(uuid)) % 2 == 0 \
            else [("B", B_PROMPT), ("A", A_PROMPT)]
        res = {}
        for name, prompt in order:
            set_prompt(prompt)
            res[name] = ask(uuid)
            runs[name] += 1
            hits[name] += 1 if res[name] else 0
        pairs[(res["A"], res["B"])] += 1
        print("  r%d %s  A=%s B=%s" % (r + 1, uuid[:8], "HIT" if res["A"] else "ok ",
                                       "HIT" if res["B"] else "ok "), flush=True)

set_prompt("")
print("\nA (shipped, WITH the sentence): %d of %d" % (hits["A"], runs["A"]))
print("B (sentence removed)          : %d of %d" % (hits["B"], runs["B"]))
disc_a = pairs[(True, False)]; disc_b = pairs[(False, True)]
print("discordant pairs: A-only=%d  B-only=%d  (concordant: %d)"
      % (disc_a, disc_b, pairs[(True, True)] + pairs[(False, False)]))
