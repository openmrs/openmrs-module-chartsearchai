#!/usr/bin/env python3
"""Score the Tier-2 grounding judge over corpus.json against a real llama-server (issue #612).

The judge's system prompts are read from CAPTURED requests (capture_server.py), never copied
here, so the score is always of the prompt the module actually sends. --clause inserts text
before ANCHOR in both prompts and scores that arm beside the base.

  score_judge.py --rig <standalone>/appdata/chartsearchai --batch-capture req-001.json \
      [--list-capture req-NNN.json] [--clause "..."] [--port 18998]
"""
import argparse, copy, json, os, subprocess, sys, time, urllib.request

ANCHOR = "Do not use any outside knowledge."
HERE = os.path.dirname(os.path.abspath(__file__))


def start_server(rig, gguf, port, log_path):
    # The rig's own binary and model with the module's flags (ps on its child), smaller context.
    return subprocess.Popen(
        [rig + "/bin/llama-server", "-m", rig + "/" + gguf, "--port", str(port), "--host", "127.0.0.1",
         "--no-webui", "-ngl", "99", "-fa", "on", "-c", "8192", "--parallel", "1", "-b", "4096", "-ub", "1024",
         "--cache-reuse", "0", "--reasoning-budget", "0"],
        env=dict(os.environ, DYLD_LIBRARY_PATH=rig + "/bin", LD_LIBRARY_PATH=rig + "/bin"),
        stdout=subprocess.DEVNULL, stderr=open(log_path, "w"))


def wait_healthy(url, server, seconds=400):
    for _ in range(seconds):
        if server.poll() is not None:
            sys.exit("llama-server exited with %s; see its log" % server.returncode)
        try:
            if urllib.request.urlopen(url + "/health", timeout=2).status == 200:
                return
        except Exception:
            time.sleep(1)
    sys.exit("llama-server never became healthy")


def system_prompt(capture_path):
    req = json.load(open(capture_path))
    system = [m["content"] for m in req["messages"] if m["role"] == "system"]
    if len(system) != 1 or ANCHOR not in system[0]:
        sys.exit("%s: expected one system prompt containing %r" % (capture_path, ANCHOR))
    return req, system[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rig", required=True, help="<standalone>/appdata/chartsearchai")
    ap.add_argument("--gguf", default="gemma-4-E4B-it-Q4_K_M.gguf")
    ap.add_argument("--batch-capture", required=True, help="a captured entailsBatch request")
    ap.add_argument("--list-capture", help="a captured entailsListItem request; list cases are skipped without it")
    ap.add_argument("--clause", help="text inserted before %r in each prompt, scored as a second arm" % ANCHOR)
    ap.add_argument("--port", type=int, default=18998)
    ap.add_argument("--server-log", default="llama-judge.log")
    args = ap.parse_args()

    corpus = json.load(open(os.path.join(HERE, "corpus.json")))
    source = lambda c: c["source"] if "source" in c else corpus["orderTemplate"] % tuple(c["order"])
    batch_req, batch_prompt = system_prompt(args.batch_capture)
    list_prompt = system_prompt(args.list_capture)[1] if args.list_capture else None
    arms = {"base": lambda p: p}
    if args.clause:
        arms["clause"] = lambda p: p.replace(ANCHOR, args.clause.strip() + " " + ANCHOR, 1)

    # The local engine's body for a judge call (LocalLlmEngine.buildRequestBody, no reference records).
    body = {k: v for k, v in batch_req.items() if k not in ("model", "messages")}
    body.update(temperature=0.0, samplers=["dry", "temperature"], dry_multiplier=0.8, dry_base=1.75,
                dry_allowed_length=8, dry_penalty_last_n=-1)

    url = "http://127.0.0.1:%d" % args.port
    server = start_server(args.rig, args.gguf, args.port, args.server_log)
    try:
        wait_healthy(url, server)

        def judge(system, user):
            b = copy.deepcopy(body)
            b["messages"] = [{"role": "system", "content": system}, {"role": "user", "content": user}]
            b["response_format"]["json_schema"]["schema"]["properties"]["verdicts"].update(minItems=1, maxItems=1)
            req = urllib.request.Request(url + "/v1/chat/completions", data=json.dumps(b).encode(),
                                         headers={"Content-Type": "application/json"})
            content = json.load(urllib.request.urlopen(req, timeout=600))["choices"][0]["message"]["content"]
            return json.loads(content)["verdicts"][0] == "YES"

        rows = [(c["label"], c["expect"], batch_prompt,
                 "PAIR 1:\n SOURCE: %s\n STATEMENT: %s\n" % (source(c), c["statement"])) for c in corpus["pairs"]]
        if list_prompt:
            rows += [(c["label"], c["expect"], list_prompt,
                      "SOURCE: %s\nLIST HEADER: %s\nITEM: %s\n" % (source(c), c["header"], c["item"]))
                     for c in corpus["listItems"]]
        correct = dict.fromkeys(arms, 0)
        print("%-34s exp  %s" % ("case", "  ".join("%-6s" % a for a in arms)))
        for label, expect, prompt, user in rows:
            cells = []
            for name, edit in arms.items():
                verdict = judge(edit(prompt), user)
                correct[name] += verdict == expect
                cells.append("%-6s" % (("Y" if verdict else "N") + ("" if verdict == expect else "!")))
            print("%-34s %s    %s" % (label, "Y" if expect else "N", "  ".join(cells)))
        print("correct: " + ", ".join("%s %d/%d" % (a, correct[a], len(rows)) for a in arms))
    finally:
        server.terminate()
        server.wait(30)


if __name__ == "__main__":
    main()
