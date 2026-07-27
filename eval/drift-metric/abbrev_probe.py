#!/usr/bin/env python3
"""Abbreviation probe: does asking by initialism find the condition the patient actually has?

Clinicians write "any CKD?" while the chart reads "Chronic kidney disease, stage IIIA (moderate)".
The bare initialism embeds far from that record, so the similarity slice can miss a condition the
patient demonstrably has. Truth is DB-derived: patients whose condition/diagnosis rows match the
FULL term. The question uses only the initialism, and a cell is correct when the answer both
leads with Yes and cites at least one of those rows.

Usage: abbrev_probe.py <outdir> [max_patients_per_abbrev]
Env:   MARIADB_BIN, MYSQL_PWD, MARIADB_PORT, OPENMRS_AUTH, OPENMRS_REST
"""
import base64
import json
import os
import re
import subprocess
import sys
import urllib.request

PORT = os.environ.get("MARIADB_PORT", "3316")
AUTH = os.environ.get("OPENMRS_AUTH", "admin:Admin123")
BASE = os.environ.get("OPENMRS_REST", "http://localhost:8081/openmrs/ws/rest/v1")

# (initialism asked, SQL LIKE the chart record must match to count as the same condition)
#
# Truth must be what a clinician asking "does the patient have X?" would call a Yes:
#  - "Family history of hypertension" is NOT the patient's hypertension — excluded below, because
#    counting it made three correct baseline answers ("No hypertension diagnosis is recorded")
#    score as misses.
#  - "Personal history of X" IS the patient's X and stays in.
#  - DVT is deep VEIN thrombosis; "Embolism and thrombosis of artery" is a different disease, so
#    the LIKE is the full term rather than a bare "thrombosis".
PROBES = [
    ("CKD", "%chronic kidney disease%"),
    ("COPD", "%chronic obstructive pulmonary%"),
    ("MI", "%myocardial infarction%"),
    ("TB", "%tuberculosis%"),
    ("CVA", "%cerebrovascular accident%"),
    ("UTI", "%urinary tract infection%"),
    ("DM", "%diabetes mellitus%"),
    ("HTN", "%hypertension%"),
    ("PID", "%pelvic inflammatory%"),
    ("DVT", "%deep vein thrombosis%"),
]

EXCLUDE_LIKE = "family history%"


def sql(q):
    out = subprocess.run([os.environ["MARIADB_BIN"], "--skip-ssl", "-h127.0.0.1", "-P" + PORT,
                          "-uopenmrs", "openmrs", "-N", "--batch", "-e", q],
                         capture_output=True, text=True, env={**os.environ, "PATH": "/usr/bin:/bin"})
    if out.returncode != 0:
        raise RuntimeError(out.stderr[:400])
    return [line.split("\t") for line in out.stdout.splitlines() if line]


def carriers(like):
    """(patientUuid, [recordUuid…]) for patients with a condition/diagnosis matching `like`."""
    rows = sql("""
        SELECT p.uuid, c.uuid FROM conditions c
        JOIN concept_name cn ON cn.concept_id = c.condition_coded AND cn.locale='en'
          AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        JOIN person p ON p.person_id = c.patient_id
        WHERE c.voided=0 AND LOWER(cn.name) LIKE '%s' AND LOWER(cn.name) NOT LIKE '%s'
        UNION ALL
        SELECT p.uuid, d.uuid FROM encounter_diagnosis d
        JOIN concept_name cn ON cn.concept_id = d.diagnosis_coded AND cn.locale='en'
          AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        JOIN person p ON p.person_id = d.patient_id
        WHERE d.voided=0 AND LOWER(cn.name) LIKE '%s' AND LOWER(cn.name) NOT LIKE '%s'"""
        % (like, EXCLUDE_LIKE, like, EXCLUDE_LIKE))
    by = {}
    for patient, record in rows:
        by.setdefault(patient, []).append(record)
    return by


def ask(uuid, question):
    body = json.dumps({"patient": uuid, "question": question}).encode()
    req = urllib.request.Request(BASE + "/chartsearchai/search", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Basic " + base64.b64encode(AUTH.encode()).decode())
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read().decode())


LEADS_YES = re.compile(r"^\s*yes\b|^\s*yes[,—-]", re.I)


def main():
    outdir = sys.argv[1]
    cap = int(sys.argv[2]) if len(sys.argv) > 2 else 3
    os.makedirs(outdir, exist_ok=True)

    found = cited_ok = yes_ok = total = 0
    detail = []
    for abbrev, like in PROBES:
        by = carriers(like)
        for patient in sorted(by)[:cap]:
            expected = set(by[patient])
            question = "Does the patient have %s?" % abbrev
            try:
                resp = ask(patient, question)
            except Exception as e:                                # noqa: BLE001
                print("WARN %s/%s: %s" % (abbrev, patient[:8], e))
                continue
            answer = resp.get("answer", "") or ""
            refs = {r.get("resourceUuid") for r in resp.get("references", [])}
            hit = bool(expected & refs)
            yes = bool(LEADS_YES.match(answer))
            total += 1
            cited_ok += 1 if hit else 0
            yes_ok += 1 if yes else 0
            found += 1 if (hit and yes) else 0
            detail.append({"abbrev": abbrev, "patient": patient, "expected": sorted(expected),
                           "citedExpected": hit, "leadsYes": yes, "answer": answer})
            print("%-5s %-10s cited=%-5s yes=%-5s | %s"
                  % (abbrev, patient[:8], hit, yes, answer.replace("\n", " ")[:95]))

    print("\nABBREV PROBE n=%d  cites-the-condition=%d (%.2f)  leads-Yes=%d (%.2f)  both=%d (%.2f)"
          % (total, cited_ok, cited_ok / total if total else 0,
             yes_ok, yes_ok / total if total else 0, found, found / total if total else 0))
    with open(os.path.join(outdir, "abbrev_probe.json"), "w") as f:
        json.dump({"n": total, "cited": cited_ok, "yes": yes_ok, "both": found,
                   "detail": detail}, f, indent=1)


if __name__ == "__main__":
    main()
