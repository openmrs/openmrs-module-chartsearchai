#!/usr/bin/env python3
"""Loopback LLM engine that records every request the module sends (issue #612).

  capture_server.py <out-dir> <canned-answer.json> <port>

Point chartsearchai.llm.remote.endpointUrl at http://127.0.0.1:<port>/v1/chat/completions, set
chartsearchai.llm.remote.modelName to anything and chartsearchai.llm.engine to remote, ask one
question, then RESTORE all three GPs. Each POST body is written to <out-dir>/req-NNN.json. A judge
request (its response_format names "verdicts") is answered YES x minItems; anything else gets the
canned chart_answer JSON ({"reasoning", "answer", "citations"} - all three keys are required).
"""
import itertools, json, os, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

OUT, ANSWER, PORT = sys.argv[1], json.load(open(sys.argv[2])), int(sys.argv[3])
counter = itertools.count()


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        raw = self.rfile.read(int(self.headers["Content-Length"]))
        with open(os.path.join(OUT, "req-%03d.json" % next(counter)), "wb") as f:
            f.write(raw)
        req = json.loads(raw)
        fmt = req.get("response_format") or {}
        if '"verdicts"' in json.dumps(fmt):
            n = fmt["json_schema"]["schema"]["properties"]["verdicts"].get("minItems", 1)
            content = json.dumps({"verdicts": ["YES"] * n})
        else:
            content = json.dumps(ANSWER)
        out = json.dumps({"choices": [{"message": {"role": "assistant", "content": content}, "finish_reason": "stop"}],
                          "usage": {"prompt_tokens": 1, "completion_tokens": 1}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *args):
        pass


HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
