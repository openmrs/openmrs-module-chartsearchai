#!/usr/bin/env python3
"""Remaps the human-adjudicated drift-metric gold (betty/richard/karen/mark, original
install uuids) onto the current standalone DB, matching records by clinical identity:

  Condition  -> (concept name, status)        Diagnosis -> (concept name, certainty, rank)
  Test order -> concept name                  Program   -> program name
  obs labs   -> concept name (ALL instances — the gold already includes every instance
                of an on-topic lab concept, so concept-level inclusion preserves intent)

Outputs (alongside the original gold):
  metric_gold.standalone.json     remapped gold, cells keyed by NEW patient uuids;
                                  focus_uuids = the patient's FULL record universe, so every
                                  cited record is adjudicated (no unknown bucket)
  offtopic_adj.standalone.json    remapped adjudications
  remap_audit.standalone.md       per-cell old->new counts + anything unmatched, for review
"""
import json
import re
import subprocess
import sys
from collections import defaultdict

import os

# DB access comes from the environment — never hardcode credentials:
#   MARIADB_BIN  path to the mariadb client (e.g. <standalone>/database/bin/mariadb)
#   MYSQL_PWD    the connection.password from the standalone's openmrs-runtime.properties
#   MARIADB_PORT default 3316 (the standalone's embedded MariaDB)
M = os.environ["MARIADB_BIN"]
PORT = os.environ.get("MARIADB_PORT", "3316")
HERE = os.path.dirname(os.path.abspath(__file__))
GOLD = os.path.join(HERE, "metric_gold.json")
ADJ = os.path.join(HERE, "offtopic_adj.json")

OLD2NEW_PATIENT = {
    "4acc0b80-83c4-40f7-86fd-0e11a68dd405": ("betty", "c75bce49-60de-493f-a8a9-458674e7adf3", 7),
    "07e26b8e-00a9-4b31-b805-3560ad4e9e2e": ("richard", "2c384236-7c4f-4971-9c57-05d0069d3bbd", 22),
    "be83f269-66bd-4ba1-80ec-cc62d0d0c84e": ("karen", "60369905-12b3-4c57-99c4-817aa7d3ae4c", 25),
    "61d0a9db-d35f-40c9-aeae-ccd264470de5": ("mark", "f25ba560-187e-4fe0-80ce-28bbaa5eed1d", 11),
}


def sql(q):
    out = subprocess.run([M, "--skip-ssl", "-h127.0.0.1", "-P" + PORT, "-uopenmrs", "openmrs",
                          "-N", "--batch", "-e", q],
                         capture_output=True, text=True,
                         env={**os.environ, "PATH": "/usr/bin:/bin"})
    if out.returncode != 0:
        raise RuntimeError(out.stderr[:300])
    return [line.split("\t") for line in out.stdout.splitlines() if line]


def fetch_universe(pid):
    """Returns list of (uuid, kind, key-tuple, display) for every unvoided record of the patient."""
    recs = []
    for u, name, status in sql(f"""
        SELECT c.uuid, COALESCE(cn.name, c.condition_non_coded), c.clinical_status
        FROM conditions c LEFT JOIN concept_name cn ON cn.concept_id=c.condition_coded
          AND cn.locale='en' AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        WHERE c.patient_id={pid} AND c.voided=0"""):
        recs.append((u, "condition", ("condition", norm(name), norm(status)),
                     f"Condition: {name}. Status: {status}"))
    for u, name, cert, rank in sql(f"""
        SELECT d.uuid, cn.name, d.certainty, d.dx_rank
        FROM encounter_diagnosis d JOIN concept_name cn ON cn.concept_id=d.diagnosis_coded
          AND cn.locale='en' AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        WHERE d.patient_id={pid} AND d.voided=0"""):
        rankname = {"1": "Primary", "2": "Secondary"}.get(rank, rank)
        recs.append((u, "diagnosis", ("diagnosis", norm(name), norm(cert), norm(rankname)),
                     f"Diagnosis: {name}. Certainty: {cert}. Rank: {rankname}"))
    for u, name, otype in sql(f"""
        SELECT o.uuid, COALESCE(dn.name, cn.name), ot.name
        FROM orders o JOIN order_type ot ON ot.order_type_id=o.order_type_id
        LEFT JOIN drug d ON d.drug_id=(SELECT do2.drug_inventory_id FROM drug_order do2 WHERE do2.order_id=o.order_id)
        LEFT JOIN concept_name dn ON dn.concept_id=d.concept_id AND dn.locale='en'
          AND dn.concept_name_type='FULLY_SPECIFIED' AND dn.voided=0
        LEFT JOIN concept_name cn ON cn.concept_id=o.concept_id AND cn.locale='en'
          AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        WHERE o.patient_id={pid} AND o.voided=0"""):
        recs.append((u, "order", ("order", norm(name)), f"{otype}: {name}"))
    for u, name in sql(f"""
        SELECT pp.uuid, cn.name FROM patient_program pp
        JOIN program pr ON pr.program_id=pp.program_id
        JOIN concept_name cn ON cn.concept_id=pr.concept_id AND cn.locale='en'
          AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        WHERE pp.patient_id={pid} AND pp.voided=0"""):
        recs.append((u, "program", ("program", norm(name)), f"Program: {name}"))
    for u, name in sql(f"""
        SELECT a.uuid, COALESCE(cn.name, a.coded_allergen)
        FROM allergy a LEFT JOIN concept_name cn ON cn.concept_id=a.coded_allergen
          AND cn.locale='en' AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        WHERE a.patient_id={pid} AND a.voided=0"""):
        recs.append((u, "allergy", ("allergy", norm(name)), f"Allergy: {name}"))
    for u, name, vn, vt in sql(f"""
        SELECT o.uuid, cn.name, o.value_numeric, o.value_text
        FROM obs o JOIN concept_name cn ON cn.concept_id=o.concept_id AND cn.locale='en'
          AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        WHERE o.person_id={pid} AND o.voided=0"""):
        val = vn if vn not in ("NULL", "") else (vt if vt not in ("NULL", "") else "")
        recs.append((u, "obs", ("obs", norm(name)), f"{name}: {val}"))
    return recs


def norm(s):
    return re.sub(r"\s+", " ", (s or "").strip().lower())


def parse_gold_text(text):
    """Parses a gold ontopic text into the same key shapes as fetch_universe."""
    if text.startswith("Condition: "):
        m = re.match(r"Condition: (.*?)\. Status: (\w+)", text)
        if m:
            return ("condition", norm(m.group(1)), norm(m.group(2)))
    if text.startswith("Diagnosis: "):
        m = re.match(r"Diagnosis: (.*?)\. Certainty: (\w+)\. Rank: (\w+)", text)
        if m:
            return ("diagnosis", norm(m.group(1)), norm(m.group(2)), norm(m.group(3)))
    if text.startswith("Program: "):
        return ("program", norm(text[len("Program: "):].split(".")[0]))
    if text.startswith("Test order: "):
        # the order concept name may itself contain a unit suffix or parentheses — keep the
        # full name up to the ". Action" qualifier; matching falls back below if needed
        return ("order", norm(re.split(r"\. Action", text[len("Test order: "):])[0]))
    if text.startswith("Allergy: "):
        return ("allergy", norm(text[len("Allergy: "):].split(".")[0]))
    # obs: "<Concept name>: <value...>" — concept-level key
    concept = text.split(":")[0]
    concept = re.sub(r"\s*\([^)]*\)$", "", concept)  # strip unit suffix e.g. "(umol/L)"
    return ("obs", norm(concept))


def obs_unitless(key):
    return key[0] == "obs"


def main():
    gold = json.load(open(GOLD))
    adj = json.load(open(ADJ))
    audit = ["# Remap audit: original human gold -> standalone uuids", ""]
    new_gold = {}
    new_adj = {"_ontopic": {}}
    universes = {}
    for old_uuid, (name, new_uuid, pid) in OLD2NEW_PATIENT.items():
        recs = fetch_universe(pid)
        universes[old_uuid] = (name, new_uuid, recs)
        audit.append(f"## {name}: {len(recs)} records on standalone (patient {pid}, {new_uuid})")

    def match(old_uuid, text):
        """Returns list of new uuids matching this gold text's clinical identity."""
        name, new_uuid, recs = universes[old_uuid]
        key = parse_gold_text(text)
        hits = [r for r in recs if r[2] == key]
        if not hits and obs_unitless(key):
            # tolerate concept-name drift in parenthetical units on either side
            hits = [r for r in recs if r[1] == "obs" and r[2][1].split(" (")[0] == key[1].split(" (")[0]]
        if not hits and key[0] == "order":
            # unit-suffix drift between installs: compare unit-stripped names
            base = re.sub(r"\s*\([^)]*\)", "", key[1]).strip()
            hits = [r for r in recs if r[1] == "order"
                    and re.sub(r"\s*\([^)]*\)", "", r[2][1]).strip() == base]
        if not hits and key[0] in ("condition", "diagnosis"):
            # fall back to concept-only (status/certainty wording drift between installs)
            hits = [r for r in recs if r[1] == key[0] and r[2][1] == key[1]]
        return hits, key

    unmatched_total = 0
    for cell, g in sorted(gold.items()):
        old_uuid, topic = cell.split("|", 1)
        name, new_uuid, recs = universes[old_uuid]
        new_cell = f"{new_uuid}|{topic}"
        ontopic = {}
        lines = []
        for u, text in g["ontopic"].items():
            hits, key = match(old_uuid, text)
            if hits:
                for h in hits:
                    ontopic[h[0]] = h[3]
                lines.append(f"  OK   {text[:70]}  -> {len(hits)} match(es)")
            else:
                unmatched_total += 1
                lines.append(f"  MISS {text[:70]}  (key={key})")
        new_gold[new_cell] = {
            "present": g["present"],
            "ontopic": ontopic,
            "focus_uuids": {r[0]: r[3][:80] for r in recs},
        }
        audit.append(f"### {name}|{topic} present={g['present']} old_ontopic={len(g['ontopic'])} new_ontopic={len(ontopic)}")
        audit.extend(lines)

    for cell, uuids in adj.items():
        if cell == "_ontopic":
            for c2, texts_or_uuids in uuids.items():
                old_uuid, topic = c2.split("|", 1)
                # on_adj entries are uuid lists; their texts live nowhere -> cannot remap by uuid.
                audit.append(f"NOTE _ontopic[{c2}]: {len(texts_or_uuids)} uuid-only entries NOT remapped (no text key)")
            continue
        old_uuid, topic = cell.split("|", 1)
        audit.append(f"NOTE off-adj[{cell}]: {len(uuids)} uuid-only entries NOT remapped (no text key)")

    json.dump(new_gold, open(os.path.join(HERE, "metric_gold.standalone.json"), "w"), indent=1, sort_keys=True)
    json.dump(new_adj, open(os.path.join(HERE, "offtopic_adj.standalone.json"), "w"), indent=1, sort_keys=True)
    open(os.path.join(HERE, "remap_audit.standalone.md"), "w").write("\n".join(audit) + "\n")
    total_on = sum(len(c["ontopic"]) for c in new_gold.values())
    print(f"cells={len(new_gold)} remapped_ontopic={total_on} unmatched={unmatched_total}")
    print("audit -> eval/drift-metric/remap_audit.standalone.md")


if __name__ == "__main__":
    main()
