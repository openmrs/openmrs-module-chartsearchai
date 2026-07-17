#!/usr/bin/env python3
"""Measured A/B: brief vs verbose LLM reasoning on the chartsearchai.openmrs.org demo.

Mechanism: flip the `chartsearchai.llm.systemPrompt` GP (LlmProvider.getSystemPrompt reads
it live; blank => compiled DEFAULT_SYSTEM_PROMPT). The ONLY contrast between arms is the
directive "Keep the reasoning to one brief sentence." appended to the reasoning instruction
(exactly what reverted commit #29 did). GP restored to its original value in finally.

Per (patient, query) cell, from ONE streaming call, we capture BOTH:
  * LATENCY: ttft_ms, answer_start_ms, reasoning_ms (=answer_start-ttft, the decode the
    directive targets), total_ms.
  * QUALITY: answer text, cited record-index set, grounded verdict counts.

Quality gate mirrors eval/latency/quality_panel.py (purpose-built for reasoning-scratchpad
changes): abstention flips must be 0; citation-set Jaccard mean >= 0.80 / min >= 0.50;
grounded-rate drop <= 2pp. Latency gate (the benefit): answer_start median drop >= 20%.

Usage:  prod_reasoning_ab.py baseline      # control arm only (GP left blank; no mutation)
        prod_reasoning_ab.py ab            # full A/B (flips GP, restores in finally)
"""
import base64, json, os, sys, time, urllib.request

BASE = "https://chartsearchai.openmrs.org/openmrs/ws/rest/v1"
AUTH = base64.b64encode(b"admin:Admin123").decode()
GP = "chartsearchai.llm.systemPrompt"
PACE_S = 7.0

CASES = [
    ("dd92543f-1691-11df-97a5-7038c432aabf", "what active conditions does the patient have?"),
    ("dd92543f-1691-11df-97a5-7038c432aabf", "what medications is the patient taking?"),
    ("dd92543f-1691-11df-97a5-7038c432aabf", "what are the patient's diagnoses?"),
    ("dd9836d7-1691-11df-97a5-7038c432aabf", "what active conditions does the patient have?"),
    ("dd9836d7-1691-11df-97a5-7038c432aabf", "what medications is the patient taking?"),
    ("dda99123-1691-11df-97a5-7038c432aabf", "what active conditions does the patient have?"),
    ("dda99123-1691-11df-97a5-7038c432aabf", "what medications is the patient taking?"),
]

# EXACT replica of LlmProvider.DEFAULT_SYSTEM_PROMPT (api/.../impl/LlmProvider.java).
# The treatment appends the brevity directive after "...before you write the answer."
FOCUS_HINT_LABEL = "Records ranked by similarity to the query: "
DEFAULT_SYSTEM_PROMPT = (
    "You are a clinical assistant helping a clinician "
    "review a patient's chart. Answer ONLY the specific query. "
    "Use only the patient records below (sorted most recent first). "
    "When the query asks for the latest, current, or most recent value, the relevant "
    "record is the FIRST matching one in the list; report that value and do not present "
    "an older reading as the current one. "
    "Never infer, assume, or add information not explicitly stated in the records. "
    "Include ALL relevant records in your answer — never omit any for brevity. "
    "Cite EVERY record you reference by its number in brackets (e.g. [1], [3]). "
    "Respond with ONLY a JSON object with a \"reasoning\" string, then an \"answer\" string "
    "and a \"citations\" array listing every record number you cited. In \"reasoning\", first "
    "work out what the query refers to and which records match it by clinical meaning — not "
    "just shared words — before you write the answer.\n"
    "Use plain text only in the answer — no markdown, no bullet markers like * or -, "
    "no headers. Use numbered lines or simple newlines to structure lists.\n\n"
    "If no records are relevant, name what is missing.\n"
    "Your answer must not vary based on the punctuation or phrasing of the query "
    "— focus only on its semantic meaning.\n\n"
    "The following is a FORMAT DEMONSTRATION ONLY using fake non-medical data. "
    "Do NOT use any of this data in your answer.\n\n"
    "Records:\n"
    "[1] (2024-03-10) Fruit delivery: 12 apples\n"
    "[2] (2024-02-15) Fruit delivery: 8 oranges\n"
    "[3] (2024-01-20) Fruit delivery: 5 apples\n\n"
    "Clinician's query: How many apples were delivered?\n"
    "{\"reasoning\": \"The query is about apples. Records [1] and [3] are apple deliveries; "
    "[2] is oranges, a different fruit.\", "
    "\"answer\": \"12 apples on 2024-03-10 [1] and 5 apples on 2024-01-20 [3].\","
    " \"citations\": [1, 3]}\n\n"
    + FOCUS_HINT_LABEL + "2.\n"
    "Clinician's query: Were any bananas delivered?\n"
    "{\"reasoning\": \"The query is about bananas. The ranked record [2] is oranges and no "
    "other record mentions bananas, so nothing matches the query.\", "
    "\"answer\": \"There are no records of banana deliveries.\", \"citations\": []}\n\n"
    "END OF FORMAT DEMONSTRATION. Now answer using ONLY the actual patient records below."
)
_anchor = "before you write the answer.\n"
assert _anchor in DEFAULT_SYSTEM_PROMPT
BRIEF_PROMPT = DEFAULT_SYSTEM_PROMPT.replace(
    _anchor, "before you write the answer. Keep the reasoning to one brief sentence.\n", 1)


def _req(path, data=None, method="GET", timeout=600):
    r = urllib.request.Request(BASE + path,
                               data=(json.dumps(data).encode() if data is not None else None),
                               method=method)
    r.add_header("Authorization", "Basic " + AUTH)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    return urllib.request.urlopen(r, timeout=timeout)


def get_gp():
    rows = json.load(_req("/systemsetting?q=%s&v=full" % GP)).get("results", [])
    for x in rows:
        if x.get("property") == GP:
            return x.get("uuid"), (x.get("value") or "")
    return None, None


def set_gp(uuid, value):
    _req("/systemsetting/" + uuid, {"value": value}, "POST").read()


def stream(patient, question):
    r = urllib.request.Request(BASE + "/chartsearchai/search/stream",
                               data=json.dumps({"patient": patient, "question": question}).encode(),
                               method="POST")
    r.add_header("Authorization", "Basic " + AUTH)
    r.add_header("Content-Type", "application/json")
    t0 = time.monotonic()
    m = {"ttft_ms": None, "answer_start_ms": None, "total_ms": None}
    event, data_lines, done = None, [], None
    with urllib.request.urlopen(r, timeout=600) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").rstrip("\n")
            if line.startswith("event:"):
                event = line[6:].strip(); data_lines = []
                now = (time.monotonic() - t0) * 1000.0
                if event in ("thinking", "token") and m["ttft_ms"] is None:
                    m["ttft_ms"] = now
                if event == "token" and m["answer_start_ms"] is None:
                    m["answer_start_ms"] = now
                if event == "done":
                    m["total_ms"] = now
            elif line.startswith("data:"):
                data_lines.append(line[5:].lstrip())
            elif line == "" and event is not None:
                if event == "done":
                    done = json.loads("\n".join(data_lines))
                event, data_lines = None, []
    refs = (done or {}).get("references", []) or []
    cites = sorted({r.get("index") for r in refs if r.get("index") is not None})
    grounded = sum(1 for r in refs if r.get("grounded") is True)
    answer = ((done or {}).get("answer") or "").strip()
    if m["ttft_ms"] is not None and m["answer_start_ms"] is not None:
        m["reasoning_ms"] = m["answer_start_ms"] - m["ttft_ms"]
    else:
        m["reasoning_ms"] = None
    return {"m": m, "answer": answer, "cites": cites, "grounded": grounded, "nrefs": len(refs),
            "abstained": len(cites) == 0}


def capture_arm(label):
    print("\n### ARM %s ###" % label)
    out = []
    for patient, q in CASES:
        try:
            res = stream(patient, q)
        except Exception as e:
            print("  ERR %s | %s: %s" % (patient[:8], q[:30], e)); time.sleep(PACE_S); continue
        mm = res["m"]
        res.update({"patient": patient, "question": q})
        out.append(res)
        print("  %s | %-44s ttft=%5s answer=%6s reason=%6s total=%6s | cites=%s grounded=%d/%d %s"
              % (patient[:8], q[:44],
                 _f(mm["ttft_ms"]), _f(mm["answer_start_ms"]), _f(mm["reasoning_ms"]), _f(mm["total_ms"]),
                 res["cites"], res["grounded"], res["nrefs"], "ABSTAIN" if res["abstained"] else ""))
        time.sleep(PACE_S)
    return out


def _f(v):
    return "n/a" if v is None else "%.0f" % v


def _median(xs):
    xs = sorted(v for v in xs if v is not None)
    return xs[len(xs) // 2] if xs else None


def summarize(arm):
    return {k: _median([c["m"][k] for c in arm]) for k in ("ttft_ms", "answer_start_ms", "reasoning_ms", "total_ms")}


def warm(value=None, uuid=None):
    if uuid is not None:
        set_gp(uuid, value if value is not None else "")
        time.sleep(2)
    for p in {c[0] for c in CASES}:
        try:
            _req("/chartsearchai/warmup", {"patient": p}, "POST").read()
        except Exception:
            pass
    time.sleep(PACE_S)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "baseline"
    uuid, orig = get_gp()
    print("GP %s uuid=%s original=%r" % (GP, uuid, (orig or "")[:40]))
    results = {}
    try:
        if mode == "baseline":
            warm()  # GP already blank = verbose default
            results["verbose"] = capture_arm("VERBOSE (control, GP blank = current prod)")
        elif mode == "ab":
            # interleaved blocks, repeated twice; warm the new prefix after each GP flip
            for rnd in (1, 2):
                print("\n===== ROUND %d =====" % rnd)
                warm(value="", uuid=uuid)
                results.setdefault("verbose", []).extend(capture_arm("VERBOSE r%d" % rnd))
                warm(value=BRIEF_PROMPT, uuid=uuid)
                results.setdefault("brief", []).extend(capture_arm("BRIEF r%d" % rnd))
        else:
            print("unknown mode"); return
    finally:
        if uuid is not None:
            set_gp(uuid, orig or "")
            _, now = get_gp()
            print("\nRESTORED %s -> %r" % (GP, (now or "")[:40]))

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results",
                       "prod-reasoning-ab-%s.json" % mode)
    with open(out, "w") as f:
        json.dump(results, f, indent=2)
    print("\nwritten %s" % out)

    print("\n=== LATENCY medians (ms) ===")
    for arm in ("verbose", "brief"):
        if results.get(arm):
            s = summarize(results[arm])
            print("  %-8s ttft=%5s answer_start=%6s reasoning=%6s total=%6s (n=%d)"
                  % (arm, _f(s["ttft_ms"]), _f(s["answer_start_ms"]), _f(s["reasoning_ms"]),
                     _f(s["total_ms"]), len(results[arm])))
    if results.get("verbose") and results.get("brief"):
        compare(results["verbose"], results["brief"])


def compare(verbose, brief):
    # pair cells by (patient, question), aggregate repeats by taking the first
    def index(arm):
        d = {}
        for c in arm:
            d.setdefault((c["patient"], c["question"]), c)
        return d
    V, B = index(verbose), index(brief)
    keys = [k for k in V if k in B]
    flips, jac, gdrop = 0, [], []
    print("\n=== QUALITY (per cell) ===")
    for k in keys:
        v, b = V[k], B[k]
        if v["abstained"] != b["abstained"]:
            flips += 1
        sv, sb = set(v["cites"]), set(b["cites"])
        j = 1.0 if not sv and not sb else len(sv & sb) / max(1, len(sv | sb))
        jac.append(j)
        gv = v["grounded"] / v["nrefs"] if v["nrefs"] else 1.0
        gb = b["grounded"] / b["nrefs"] if b["nrefs"] else 1.0
        gdrop.append(gv - gb)
        print("  %-8s | %-40s flip=%s jaccard=%.2f vCites=%s bCites=%s"
              % (k[0][:8], k[1][:40], v["abstained"] != b["abstained"], j, v["cites"], b["cites"]))
    sv = summarize(verbose); sb = summarize(brief)
    rv, rb = sv["answer_start_ms"], sb["answer_start_ms"]
    drop = None if not rv else (rv - rb) / rv * 100.0
    jmean = sum(jac) / len(jac) if jac else 1.0
    jmin = min(jac) if jac else 1.0
    gmax = max(gdrop) if gdrop else 0.0
    print("\n=== GATE ===")
    print("  LATENCY answer_start: verbose=%s brief=%s  drop=%s%%  (need >=20%%)"
          % (_f(rv), _f(rb), "n/a" if drop is None else "%.0f" % drop))
    print("  QUALITY abstention flips=%d (need 0) | jaccard mean=%.2f (>=0.80) min=%.2f (>=0.50) | grounded drop max=%.0fpp (<=2pp)"
          % (flips, jmean, jmin, gmax * 100))
    ok = (drop is not None and drop >= 20) and flips == 0 and jmean >= 0.80 and jmin >= 0.50 and gmax <= 0.02
    print("  VERDICT: %s" % ("SHIP" if ok else "DO NOT SHIP"))


if __name__ == "__main__":
    main()
