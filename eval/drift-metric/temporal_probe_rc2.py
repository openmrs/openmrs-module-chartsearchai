#!/usr/bin/env python3
"""Temporal DB-truth probe (rc.2 standalone): does the answer to a "most recent X" /
"when was the last Y" question quote the NEWEST matching record in the database?

Reconstruction of the (previously uncommitted) probe behind ADR Decision 28's
"Temporal DB-truth probe: scoped 15/15 vs fullChart 14/15" — committed this time so the
mandatory gate is reproducible. 15 cells = 3 temporal questions x the 5 rc.2 eval patients
(same patients as capture_eval_rc2.sh / build_gold_rc2.py PINNED).

Per cell:
  truth   = newest unvoided value/date via SQL (weight obs, systolic obs, last clinical contact)
  answer  = POST /chartsearchai/search (whatever chartMode the server is in)
  PASS    = the truth value appears in the answer. The "last visit" cell accepts the newest
            visit-table date OR the newest encounter date: the VISITS typed scope deliberately
            carries both record types (ADR Decision 28), both are real clinical-contact dates,
            and this install has encounters outside any visit — so either is a faithful
            reading of "last visit". For a patient with NO matching records, PASS = the
            answer cites nothing and reads as an abstention (no fabricated number).
Cells the mechanical check cannot settle print as REVIEW with the full answer, rather than
being silently counted either way.

Env: MARIADB_BIN (bundled client), MYSQL_PWD, MARIADB_PORT (default 3316),
     OPENMRS_REST (default http://localhost:8081/openmrs/ws/rest/v1), OPENMRS_AUTH.
Usage: temporal_probe_rc2.py [outdir]   (outdir keeps per-cell JSON for the record)
"""
import base64
import json
import os
import re
import subprocess
import sys
import urllib.request

M = os.environ["MARIADB_BIN"]
PORT = os.environ.get("MARIADB_PORT", "3316")
BASE = os.environ.get("OPENMRS_REST", "http://localhost:8081/openmrs/ws/rest/v1")
AUTH = base64.b64encode(os.environ.get("OPENMRS_AUTH", "admin:Admin123").encode()).decode()

PATIENTS = ["bc4ba445-a35c-4996-b804-4d5b68387571", "1128c659-2d0a-4314-af23-91bac1b01109",
            "59a5f0bb-b863-4213-9177-b883fe9f5f79", "16ca09dd-a8d4-405a-bda6-76d18ed65b25",
            "dkb00000-0000-0000-0000-000000000001"]


def sql(q):
    out = subprocess.run([M, "--skip-ssl", "-h127.0.0.1", "-P" + PORT, "-uopenmrs", "openmrs",
                          "-N", "--batch", "-e", q],
                         capture_output=True, text=True, env={**os.environ, "PATH": "/usr/bin:/bin"})
    if out.returncode != 0:
        raise RuntimeError(out.stderr[:300])
    return [line.split("\t") for line in out.stdout.splitlines() if line]


def person_id(uuid):
    rows = sql(f"SELECT person_id FROM person WHERE uuid='{uuid}'")
    return int(rows[0][0]) if rows else None


def newest_obs(pid, concept_name):
    rows = sql(f"""
        SELECT o.value_numeric, DATE(o.obs_datetime) FROM obs o
        JOIN concept_name cn ON cn.concept_id=o.concept_id AND cn.locale='en'
          AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        WHERE o.person_id={pid} AND o.voided=0 AND cn.name='{concept_name}'
          AND o.value_numeric IS NOT NULL
        ORDER BY o.obs_datetime DESC LIMIT 1""")
    return (float(rows[0][0]), rows[0][1]) if rows else None


def newest_contact(pid):
    """(newest visit date, newest encounter date) — either satisfies the last-visit cell."""
    v = sql(f"SELECT DATE(MAX(date_started)) FROM visit WHERE patient_id={pid} AND voided=0")[0][0]
    e = sql(f"SELECT DATE(MAX(encounter_datetime)) FROM encounter WHERE patient_id={pid} AND voided=0")[0][0]
    dates = tuple(d for d in (v, e) if d not in ("NULL", ""))
    return dates or None


def ask(patient, question):
    req = urllib.request.Request(BASE + "/chartsearchai/search",
                                 data=json.dumps({"patient": patient, "question": question}).encode(),
                                 headers={"Content-Type": "application/json",
                                          "Authorization": "Basic " + AUTH})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read().decode())


def num_in(text, value):
    """True when `value` appears in `text` as a standalone number. Truth 70 matches "70" and
    "70.0" but not "70.5" or "170"; truth 70.5 matches only "70.5"."""
    v = "%g" % value
    pats = [re.escape(v)] + ([re.escape(v) + r"\.0+"] if "." not in v else [])
    return any(re.search(r"(?<![\d.])" + p + r"(?![\d.])", text) for p in pats)


ABSTAIN = re.compile(r"no (?:records?|information|data|documented|visits?)|not (?:documented|recorded"
                     r"|available)|there (?:are|is) no", re.I)


RESCORE = "--rescore" in sys.argv


def main():
    args = [a for a in sys.argv[1:] if a != "--rescore"]
    outdir = args[0] if args else None
    if RESCORE and not outdir:
        raise SystemExit("--rescore needs the capture dir")
    if outdir:
        os.makedirs(outdir, exist_ok=True)
    cells = []
    for uuid in PATIENTS:
        pid = person_id(uuid)
        cells.append((uuid, "weight", "What is the patient's most recent weight?",
                      newest_obs(pid, "Weight (kg)")))
        cells.append((uuid, "systolic", "What is the patient's most recent systolic blood pressure?",
                      newest_obs(pid, "Systolic blood pressure")))
        cells.append((uuid, "lastvisit", "When was the patient's last visit?",
                      newest_contact(pid)))
    npass = nfail = nreview = 0
    for uuid, kind, q, truth in cells:
        cellpath = outdir and os.path.join(outdir, f"{uuid}__temporal-{kind}.json")
        if RESCORE:
            with open(cellpath) as f:
                d = json.load(f)
        else:
            d = ask(uuid, q)
            if outdir:
                with open(cellpath, "w") as f:
                    json.dump(d, f, indent=1)
        ans = d.get("answer") or ""
        cited = len(d.get("references") or [])
        if truth is None:
            ok = cited == 0 and (ABSTAIN.search(ans) or not re.search(r"\d", ans))
            verdict = "PASS(abstain)" if ok else "REVIEW"
        elif kind == "lastvisit":
            ok = any(d in ans for d in truth)
            verdict = "PASS" if ok else "FAIL"
        else:
            value, vdate = truth
            ok = num_in(ans, value)
            verdict = "PASS" if ok else "FAIL"
        npass += verdict.startswith("PASS")
        nfail += verdict == "FAIL"
        nreview += verdict == "REVIEW"
        print(f"{verdict:14} {uuid[:8]} {kind:9} truth={truth} cited={cited}")
        if verdict != "PASS" and not verdict.startswith("PASS"):
            print(f"   answer: {ans[:300]}")
    print(f"\nTEMPORAL PROBE: {npass}/{len(cells)} pass, {nfail} fail, {nreview} review")


if __name__ == "__main__":
    main()
