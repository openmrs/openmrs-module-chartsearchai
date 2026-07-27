#!/usr/bin/env python3
"""Temporal probe: can the model answer a question whose truth depends on TODAY'S date?

Every chart record carries an absolute date, but until the prompt carries "Today's date", the
model has no anchor to compare them against — so "has the patient been seen in the last 30 days?"
can only be guessed. Truth comes from the DB (latest visit start / encounter datetime vs today),
and the question routes to the complete visit+encounter scope, so the model always HAS the dates
it needs; the only variable under test is whether it knows what day it is.

Usage: temporal_probe.py <outdir> [window_days]
Env:   MARIADB_BIN, MYSQL_PWD, MARIADB_PORT, OPENMRS_AUTH, OPENMRS_REST
"""
import json
import os
import re
import subprocess
import sys
import urllib.request
import base64

PORT = os.environ.get("MARIADB_PORT", "3316")
AUTH = os.environ.get("OPENMRS_AUTH", "admin:Admin123")
BASE = os.environ.get("OPENMRS_REST", "http://localhost:8081/openmrs/ws/rest/v1")


def sql(q):
    out = subprocess.run([os.environ["MARIADB_BIN"], "--skip-ssl", "-h127.0.0.1", "-P" + PORT,
                          "-uopenmrs", "openmrs", "-N", "--batch", "-e", q],
                         capture_output=True, text=True, env={**os.environ, "PATH": "/usr/bin:/bin"})
    if out.returncode != 0:
        raise RuntimeError(out.stderr[:400])
    return [line.split("\t") for line in out.stdout.splitlines() if line]


def ask(uuid, question):
    body = json.dumps({"patient": uuid, "question": question}).encode()
    req = urllib.request.Request(BASE + "/chartsearchai/search", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Basic " + base64.b64encode(AUTH.encode()).decode())
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read().decode())


YES = re.compile(r"^\s*(yes\b|yes[,—-])", re.I)
NO = re.compile(r"^\s*(no\b|no[,—-]|there (are|is) no|the patient has not|not recorded)", re.I)


def verdict(answer):
    a = (answer or "").strip()
    if YES.match(a):
        return True
    if NO.match(a):
        return False
    return None


FIRST_INT = re.compile(r"(\d{1,4})")


def days_answer(answer):
    """The first integer in the answer — the model's 'how many days ago' figure, or None."""
    m = FIRST_INT.search(answer or "")
    return int(m.group(1)) if m else None


def main():
    outdir = sys.argv[1]
    window = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    limit = int(sys.argv[3]) if len(sys.argv) > 3 else 26
    os.makedirs(outdir, exist_ok=True)
    question = ("Has the patient had any visit or encounter in the last %d days? "
                "Answer yes or no." % window)
    # Second, verdict-rule-free form: pure date arithmetic against "now". Without a date in the
    # prompt the model has nothing to subtract from, so this isolates the change under test.
    question2 = "How many days ago was the patient's most recent visit?"

    rows = sql("""
        SELECT p.uuid,
               DATEDIFF(CURDATE(), GREATEST(
                   COALESCE(MAX(v.date_started), '1900-01-01'),
                   COALESCE(MAX(e.encounter_datetime), '1900-01-01'))) AS days_ago
        FROM patient pt
        JOIN person p ON p.person_id = pt.patient_id
        LEFT JOIN visit v ON v.patient_id = pt.patient_id AND v.voided = 0
        LEFT JOIN encounter e ON e.patient_id = pt.patient_id AND e.voided = 0
        WHERE pt.voided = 0
        GROUP BY p.uuid HAVING days_ago < 100000 ORDER BY days_ago""")
    # Skip the ambiguity band around the boundary: a chart whose newest record is 28-33 days old
    # is a coin flip for anyone, model or human, and would measure nothing.
    cases = [(u, int(d), int(d) <= window) for (u, d) in rows
             if abs(int(d) - window) > 5]
    # Even spread across the recency range rather than the head of the list.
    if len(cases) > limit:
        step = len(cases) / float(limit)
        cases = [cases[int(i * step)] for i in range(limit)]

    right = wrong = unparsed = 0
    yes_bias = 0
    days_ok = days_said = 0
    detail = []
    for uuid, days, truth in cases:
        try:
            resp = ask(uuid, question)
            resp2 = ask(uuid, question2)
        except Exception as e:                                    # noqa: BLE001
            print("WARN %s: %s" % (uuid[:8], e))
            continue
        answer = resp.get("answer", "")
        answer2 = resp2.get("answer", "")
        v = verdict(answer)
        ok = (v is truth)
        right += 1 if ok else 0
        wrong += 1 if (v is not None and not ok) else 0
        unparsed += 1 if v is None else 0
        yes_bias += 1 if v is True else 0
        said = days_answer(answer2)
        # ±10 days: the chart's newest visit and newest encounter can differ by a few days, and
        # the model may round to whole weeks/months. Anything inside that band is right arithmetic.
        d_ok = said is not None and abs(said - days) <= 10
        days_said += 1 if said is not None else 0
        days_ok += 1 if d_ok else 0
        detail.append({"patient": uuid, "daysAgo": days, "truth": truth, "verdict": v, "ok": ok,
                       "answer": answer, "daysSaid": said, "daysOk": d_ok, "answerDays": answer2})
        print("%-10s daysAgo=%-4d truth=%-5s verdict=%-5s %s | saidDays=%-5s %s | %s"
              % (uuid[:8], days, truth, v, "OK " if ok else "BAD", said, "OK " if d_ok else "BAD",
                 answer.replace("\n", " ")[:70]))

    n = len(detail)
    print("\nTEMPORAL PROBE window=%dd  n=%d"
          "\n  A) in-last-%dd verdict : correct=%d (%.2f)  wrong=%d  unparsed=%d  said-yes=%d"
          "\n  B) how-many-days-ago   : correct=%d (%.2f)  gave-a-number=%d"
          % (window, n, window, right, right / n if n else 0, wrong, unparsed, yes_bias,
             days_ok, days_ok / n if n else 0, days_said))
    with open(os.path.join(outdir, "temporal_probe.json"), "w") as f:
        json.dump({"window": window, "n": n, "correct": right, "wrong": wrong,
                   "unparsed": unparsed, "daysCorrect": days_ok, "daysAnswered": days_said,
                   "detail": detail}, f, indent=1)


if __name__ == "__main__":
    main()
