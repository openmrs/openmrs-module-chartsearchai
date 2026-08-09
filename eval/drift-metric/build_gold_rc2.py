#!/usr/bin/env python3
"""Builds a drift-metric gold for the rc.2 standalone (2026-07), whose demo content does not
reproduce the original betty/richard/karen/mark personas (remap_gold_standalone.py matched only
1 of 4 — see remap_audit). Instead of porting record-by-record, this inherits the ORIGINAL
human gold's per-topic category boundaries (the ontopic texts in metric_gold.json define what
the human counted for each topic, e.g. trauma broadly under "fractures", renal labs + test
orders under "kidney", plain hypertension NOT under "heart") and applies them to rc.2 records
by concept identity. rc.2-specific concepts with no original-gold precedent are surfaced as
CANDIDATES in the audit and resolved by the explicit ADJUDICATIONS table below — every
inclusion/exclusion is reviewable in the emitted audit file.

DB access from env (as remap_gold_standalone.py): MARIADB_BIN, MYSQL_PWD, MARIADB_PORT.

Outputs (rc.2-suffixed; the committed .standalone.json gold of the previous install is untouched):
  metric_gold.rc2.json   offtopic_adj.rc2.json   gold_audit.rc2.md
"""
import json
import os
import re
import subprocess
from collections import defaultdict

# DB config is read lazily inside sql() (not at import), so the module can be imported
# without a live DB — e.g. for the --selftest that exercises classify() offline.
PORT = os.environ.get("MARIADB_PORT", "3316")
HERE = os.path.dirname(os.path.abspath(__file__))

# The four rc.2 eval patients (chosen by profile_patients(): topic spread — several present
# topics with >=2 on-topic records AND several genuinely-absent topics — plus chart size).
# Filled in after the profiling pass; see main().
EVAL_PATIENTS = {}  # name -> (uuid, person_id), set by profile or overridden via GOLD_PATIENTS env

TOPICS = ["programs", "allergies", "medications", "eye", "heart", "fractures", "kidney", "mental",
          "drug-allergies"]

# Category boundaries inherited from the ORIGINAL human gold (metric_gold.json ontopic texts).
# "names": exact concept names the human counted (normalized). "stems": regex fragments that
# surface rc.2 CANDIDATES with no original precedent; a candidate is on-topic only if accepted
# in ADJUDICATIONS (or auto-accepted when the stem is unambiguous, marked auto=True).
BOUNDARIES = {
    "eye": {
        # \bptosis\b (not bare "ptosis"): Proctoptosis is rectal prolapse — substring match
        # wrongly made it an eye record on the first build (caught in audit review).
        "stems": [r"\beye\b", r"retinopathy", r"\bptosis\b", r"cataract", r"glaucoma", r"conjunctiv",
                  r"\bvision\b", r"blind", r"macular", r"ophthalm", r"keratitis", r"uveitis",
                  r"strabismus", r"nystagmus"],
        "auto": True,
    },
    "heart": {
        # Original counted structural/functional cardiac disease + Troponin; NOT plain hypertension.
        "stems": [r"\bheart\b", r"cardiac", r"cardiovascular", r"myocardi", r"atrial", r"ventric",
                  r"mitral", r"aortic valve", r"tricuspid", r"palpitation", r"rheumatic fever",
                  r"troponin", r"angina", r"arrhythm", r"tachycard", r"bradycard", r"pericard",
                  r"endocard", r"cardiomyopathy", r"coronary",
                  # Adjudicated ON from capture review, by the original's generic "Cardiovascular
                  # disease" precedent: cerebrovascular history, arterial thromboembolism, and the
                  # congenital circulatory disorder. Venous-only DVT stays OFF (not a heart problem).
                  r"\bstroke\b", r"embolism and thrombosis", r"persistent fetal circulation"],
        "exclude": [r"hypertensive retinopathy"],
        "auto": True,
    },
    "fractures": {
        # The human counted trauma broadly for "fractures or broken bones": fractures,
        # dislocations, injuries, wounds, burns, bites, assault, bone destruction, post-trauma.
        # But NOT surgical/iatrogenic injuries: the original gold put "Intra-operative injury to
        # ureter" under kidney only, never fractures — exclude that family here.
        "stems": [r"fractur", r"broken", r"dislocation", r"\binjur", r"\bwound\b", r"\bburn\b",
                  r"bite by", r"assault", r"destruction of bone", r"post-?traumatic", r"nonunion",
                  r"crushing", r"sprain", r"contusion", r"laceration", r"trauma"],
        "exclude": [r"intra-?operative"],
        "auto": True,
    },
    "kidney": {
        # Original counted renal/urinary conditions + renal labs (creatinine, BUN, GFR, urea,
        # uric acid, urinalysis, urine glucose, urinary albumin, bacteriuria) + their test orders.
        # "Oliguria" adjudicated ON from capture review (urinary symptom; precedent:
        # "Painful Urging to Urinate", "Urinary incontinence"). Dehydration/hepatitis stay OFF.
        "stems": [r"kidney", r"renal", r"nephr", r"creatinine", r"blood urea", r"\burea\b", r"oliguria",
                  r"uric acid", r"glomerular", r"urinalysis", r"urinary", r"urine", r"bladder",
                  r"ureter", r"urethr", r"urinate", r"incontinence", r"dialysis", r"pyelonephritis",
                  r"bacteriuria"],
        # "renal" is kept broad (so prerenal/perirenal/intrarenal — genuine renal terms — count)
        # but the adrenal/suprarenal glands are ENDOCRINE, not renal: exclude them so "Malignant
        # tumor of adrenal gland" is not scored kidney. (An earlier \brenal\b fix excluded adrenal
        # but silently dropped prerenal/perirenal too; this states the intent directly. Gold-neutral
        # on the current universes — none contain prerenal/perirenal/intrarenal/suprarenal.)
        "exclude": [r"adrenal", r"suprarenal"],
        "auto": True,
    },
    "mental": {
        # Adjudicated additions from capture review (2026-07-16), by original-gold precedent:
        # "Fearful mood" (precedent: "Fear of medical care"), "Rumination disorder" and
        # "Undifferentiated somatoform disorder" (DSM conditions; precedent: "Eating disorder"),
        # "Enuresis" (DSM elimination disorder; precedent: childhood-psych records). Neurological
        # records (Babinski sign, seizures, "Disorder of nervous system") stay OFF — the question
        # asks for psychiatric conditions.
        "stems": [r"schizo", r"psychos", r"psychiatric", r"\bmental\b", r"depress", r"anxiety",
                  r"fearful mood", r"rumination disorder", r"somatoform", r"enuresis",
                  r"anxio", r"bipolar", r"eating disorder", r"anorexia", r"bulimia",
                  # bare "substance" also matched "Stool test for reducing substance" (a GI lab) —
                  # require a substance-use qualifier; "psychoactive substance" is covered below.
                  r"substance (abuse|use|addiction|dependence|misuse)",
                  r"cocaine", r"stimulant use", r"sedative", r"hypnotic", r"alcohol use|alcoholism",
                  r"adjustment reaction", r"aggressive behavior", r"hoarding", r"obsessive",
                  r"compulsive", r"explosive disorder", r"conversion disorder", r"self-accusation",
                  r"hyperkinetic", r"reading disorder", r"post-?partum depression", r"addiction",
                  r"behavio[u]?ral disorder", r"psychoactive", r"suicid", r"ptsd|post-?traumatic stress",
                  r"panic", r"phobia", r"dementia", r"delirium", r"fear of"],
        "auto": True,
    },
    # Typed topics: DB truth is the anchor. Allergy rows / program rows / drug orders are
    # on-topic by construction; condition/diagnosis/obs records join via stems (the original
    # gold counted allergy-named conditions and a med-mention encounter note).
    # urticaria/angioedema adjudicated ON from capture review (hypersensitivity conditions;
    # precedent: "Itching", "Seasonal allergic rhinitis").
    "allergies": {"stems": [r"allerg", r"\bitching\b", r"anaphyla", r"urticaria", r"angio-?oedema|angio-?edema"], "auto": True},
    # drug-allergies (added with the router union fix, fdf1c9c): the DRUG-allergen subset of the
    # allergies universe. allergy rows are on-topic iff allergen_type=DRUG (DB truth, no naming
    # heuristics); condition/diagnosis rows only via stems or explicit ADJUDICATIONS — the rc.2
    # install records e.g. "Allergy to imipenem" as condition+diagnosis rows, and imipenem is not
    # in this install's drug table, so drug-table membership cannot be the rule here.
    "drug-allergies": {"stems": [r"drug allerg", r"drug hypersensitivity", r"adverse drug reaction"], "auto": True},
    "medications": {"stems": [], "auto": True},  # drug orders + dispenses; obs med-mentions via drug-name scan
    "programs": {"stems": [], "auto": True},
}

# Explicit calls for candidates the stems surface but the original gold has no precedent for.
# name (normalized) -> topic -> True (on-topic) / False (off). Reviewed entries only.
ADJUDICATIONS = {
    # "fear of medical care" was counted under mental by the original human gold; the stem
    # r"fear of" auto-covers it. Add overrides here as capture-time unknowns surface.
    # drug-allergies: the rc.2 install's five "allerg"-named condition/diagnosis concepts,
    # each called explicitly (imipenem is a carbapenem antibiotic -> drug allergy; latex,
    # spiders, peanuts ("eanuts" is the install's typo) and environmental are not drugs).
    "allergy to imipenem": {"drug-allergies": True},
    "allergy to latex": {"drug-allergies": False},
    "allergy to spiders": {"drug-allergies": False},
    "allergy to eanuts": {"drug-allergies": False},
    "environmental allergies": {"drug-allergies": False},
    # Widened-gold patients (2026-07-19): anaesthetic/antibiotic drug allergens recorded as
    # condition/diagnosis rows — drugs, so ON for drug-allergies (imipenem precedent).
    "allergy to sevoflurane": {"drug-allergies": True},
    "allergy to sufentanil": {"drug-allergies": True},
    "4-quinolones allergy": {"drug-allergies": True},
    # non-drug allergens surfaced by the new patients — explicitly OFF for drug-allergies.
    "allergy to insect bites": {"drug-allergies": False},
    "dander (animal) allergy": {"drug-allergies": False},
    "food allergy": {"drug-allergies": False},
}


def sql(q):
    M = os.environ["MARIADB_BIN"]
    out = subprocess.run([M, "--skip-ssl", "-h127.0.0.1", "-P" + PORT, "-uopenmrs", "openmrs",
                          "-N", "--batch", "-e", q],
                         capture_output=True, text=True,
                         env={**os.environ, "PATH": "/usr/bin:/bin"})
    if out.returncode != 0:
        raise RuntimeError(out.stderr[:300])
    return [line.split("\t") for line in out.stdout.splitlines() if line]


def norm(s):
    return re.sub(r"\s+", " ", (s or "").strip().lower())


def fetch_universe(pid):
    """(uuid, kind, name, display) for every unvoided citable record of the patient."""
    recs = []
    for u, name, status in sql(f"""
        SELECT c.uuid, COALESCE(cn.name, c.condition_non_coded), c.clinical_status
        FROM conditions c LEFT JOIN concept_name cn ON cn.concept_id=c.condition_coded
          AND cn.locale='en' AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        WHERE c.patient_id={pid} AND c.voided=0"""):
        recs.append((u, "condition", norm(name), f"Condition: {name}. Status: {status}", ""))
    for u, name, cert, rank in sql(f"""
        SELECT d.uuid, cn.name, d.certainty, d.dx_rank
        FROM encounter_diagnosis d JOIN concept_name cn ON cn.concept_id=d.diagnosis_coded
          AND cn.locale='en' AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        WHERE d.patient_id={pid} AND d.voided=0"""):
        recs.append((u, "diagnosis", norm(name), f"Diagnosis: {name}. Certainty: {cert}", ""))
    for u, name, otype in sql(f"""
        SELECT o.uuid, COALESCE(dn.name, cn.name), ot.name
        FROM orders o JOIN order_type ot ON ot.order_type_id=o.order_type_id
        LEFT JOIN drug_order do2 ON do2.order_id=o.order_id
        LEFT JOIN drug d ON d.drug_id=do2.drug_inventory_id
        LEFT JOIN concept_name dn ON dn.concept_id=d.concept_id AND dn.locale='en'
          AND dn.concept_name_type='FULLY_SPECIFIED' AND dn.voided=0
        LEFT JOIN concept_name cn ON cn.concept_id=o.concept_id AND cn.locale='en'
          AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        WHERE o.patient_id={pid} AND o.voided=0"""):
        kind = "drug_order" if "drug" in norm(otype) else "order"
        recs.append((u, kind, norm(name), f"{otype}: {name}", ""))
    for u, name in sql(f"""
        SELECT pp.uuid, cn.name FROM patient_program pp
        JOIN program pr ON pr.program_id=pp.program_id
        JOIN concept_name cn ON cn.concept_id=pr.concept_id AND cn.locale='en'
          AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        WHERE pp.patient_id={pid} AND pp.voided=0"""):
        recs.append((u, "program", norm(name), f"Program: {name}", ""))
    for u, name, atype in sql(f"""
        SELECT a.uuid, COALESCE(cn.name, a.coded_allergen), a.allergen_type
        FROM allergy a LEFT JOIN concept_name cn ON cn.concept_id=a.coded_allergen
          AND cn.locale='en' AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        WHERE a.patient_id={pid} AND a.voided=0"""):
        # allergen_type rides in the textval slot (unused for allergy rows): DB truth for the
        # drug-allergies topic without a second query or naming heuristics.
        recs.append((u, "allergy", norm(name), f"Allergy: {name}", norm(atype)))
    for u, name, vn, vt, vc in sql(f"""
        SELECT o.uuid, cn.name, o.value_numeric, o.value_text, vcn.name
        FROM obs o JOIN concept_name cn ON cn.concept_id=o.concept_id AND cn.locale='en'
          AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0
        LEFT JOIN concept_name vcn ON vcn.concept_id=o.value_coded AND vcn.locale='en'
          AND vcn.concept_name_type='FULLY_SPECIFIED' AND vcn.voided=0
        WHERE o.person_id={pid} AND o.voided=0"""):
        val = vn if vn not in ("NULL", "") else (vt if vt not in ("NULL", "") else (vc if vc not in ("NULL", "") else ""))
        # For topic matching include the coded VALUE name too (e.g. "Visit Diagnoses: Fracture").
        # The raw TEXT value rides along separately: med-mention detection matches drug names in
        # free-text notes only (the original gold's lone medications record was an encounter note),
        # never in concept names — "Serum magnesium measurement" is a lab, not a medication, even
        # though "magnesium" is in the drug table.
        recs.append((u, "obs", norm(name + " " + (vc if vc not in ("NULL",) else "")),
                     f"{name}: {val}", norm(vt if vt not in ("NULL",) else "")))
    for u, vtype in sql(f"""
        SELECT v.uuid, vt.name FROM visit v JOIN visit_type vt ON vt.visit_type_id=v.visit_type_id
        WHERE v.patient_id={pid} AND v.voided=0"""):
        recs.append((u, "visit", norm(vtype), f"Visit: {vtype}", ""))
    for u, etype in sql(f"""
        SELECT e.uuid, et.name FROM encounter e JOIN encounter_type et ON et.encounter_type_id=e.encounter_type
        WHERE e.patient_id={pid} AND e.voided=0"""):
        recs.append((u, "encounter", norm(etype), f"Encounter: {etype}", ""))
    for u, in sql(f"SELECT uuid FROM person WHERE person_id={pid}"):
        recs.append((u, "patient", "patient demographics", "Patient demographics record", ""))
    return recs


def drug_names():
    return {norm(n) for (n,) in sql(
        "SELECT cn.name FROM drug d JOIN concept_name cn ON cn.concept_id=d.concept_id"
        " AND cn.locale='en' AND cn.concept_name_type='FULLY_SPECIFIED' AND cn.voided=0")} | {
        norm(n) for (n,) in sql("SELECT name FROM drug")}


DRUGS = None


def classify(topic, kind, name, display, textval=""):
    """True/False/None(candidate-not-covered). Inherits the original gold's boundaries."""
    global DRUGS
    b = BOUNDARIES[topic]
    if topic == "programs":
        return kind == "program"
    if topic == "drug-allergies":
        if kind == "allergy":
            return textval == "drug"
        if kind in ("condition", "diagnosis", "obs"):
            adj = ADJUDICATIONS.get(name, {}).get(topic)
            if adj is not None:
                return adj
            return bool(any(re.search(s, name) for s in b["stems"]))
        return False
    if topic == "allergies":
        if kind == "allergy":
            return True
        if kind in ("condition", "diagnosis", "obs"):
            return any(re.search(s, name) for s in b["stems"]) or None if any(
                re.search(s, name) for s in b["stems"]) else False
        return False
    if topic == "medications":
        if kind in ("drug_order",):
            return True
        if kind == "obs":
            if DRUGS is None:
                DRUGS = drug_names()
            # med-mention obs: a free-TEXT obs (note) naming a known drug. Word-boundary, 4-char
            # minimum, and TEXT values only — concept names like "Serum magnesium measurement"
            # are labs even when the substance is also in the drug table ("magnesium", "RH").
            return bool(textval) and any(
                d and len(d) >= 4 and re.search(r"\b" + re.escape(d) + r"\b", textval)
                for d in DRUGS)
        return False
    # topical categories: conditions/diagnoses/obs/test-orders by stem
    if kind in ("condition", "diagnosis", "obs", "order", "drug_order"):
        if "exclude" in b and any(re.search(s, name) for s in b["exclude"]):
            return False
        adj = ADJUDICATIONS.get(name, {}).get(topic)
        if adj is not None:
            return adj
        return bool(any(re.search(s, name) for s in b["stems"]))
    return False


def profile_patients():
    """Per-topic on-topic counts for every patient, to choose the eval four."""
    rows = sql("""SELECT p.person_id, p.uuid, CONCAT(pn.given_name,' ',pn.family_name)
        FROM patient pt JOIN person p ON pt.patient_id=p.person_id
        JOIN person_name pn ON pn.person_id=p.person_id AND pn.voided=0 WHERE pt.voided=0""")
    profiles = []
    for pid, uuid, name in rows:
        uni = fetch_universe(int(pid))
        counts = {t: sum(1 for (_, k, n, d, tv) in uni if classify(t, k, n, d, tv) is True) for t in TOPICS}
        profiles.append((name, uuid, int(pid), len(uni), counts))
    return profiles


# Pinned 2026-07-16: the five patients whose 40 cells were captured for the fullChart-vs-
# queryScoped A/B. Selection greed depends on boundary stems, so re-running after an
# adjudication round must NOT swap patients out from under already-taken captures.
# Widened 2026-07-19: all rc.2 patients with a substantial chart (>=200 unvoided citable
# records) added, so the fullChart-vs-queryScoped verdict rests on 22 patients not 5. The
# original five stay first (their captures are reused). The <200-record tail and the synthetic
# scan-test patients (Karen Sanchez, Susan Harris, the three *Scan* rows: 0 present topics) are
# excluded — they add no present cells and near-empty charts are not representative.
PINNED = ["bc4ba445-a35c-4996-b804-4d5b68387571", "1128c659-2d0a-4314-af23-91bac1b01109",
          "59a5f0bb-b863-4213-9177-b883fe9f5f79", "16ca09dd-a8d4-405a-bda6-76d18ed65b25",
          "dkb00000-0000-0000-0000-000000000001",
          "813b9f0d-3a8e-4f67-a0dd-d9b3eeef65c5", "489db738-ad5f-4335-a9f1-270ec0c76ea2",
          "5b24c81f-2b66-41ed-8f2d-158433d531cc", "b360ca49-d432-404f-9b6c-aa6a66125693",
          "bd3927f4-8c75-470b-bdbb-6c92857b2205", "c34d0124-76c9-4197-9f84-35e44e1317a8",
          "007e38b8-1344-4ea9-a790-a3f078471db3", "089f766b-943c-427b-a039-672f90b0a49e",
          "520016d7-67ae-40fa-aa8f-e8a5ec2b8fd6", "8d9fc13a-5d4c-4c5c-b265-23ac067835a4",
          "53927035-f177-4144-9d3f-b80ced7614bc", "072f69ce-f25a-4ca0-b2ff-4a7a4325ddd9",
          "0563178c-e107-43a0-be05-2a179ab02dbe", "3d6b5ada-c402-4f3f-9c70-6a17f4d2a339",
          "ec6af3d5-3082-45f4-8f14-cf42dad41ed2", "47028119-e1e0-467c-b807-a23d1a81fb2b",
          "e9712a18-c181-46c5-8a17-46b02e39b23b"]


RESELECT_ENV = "GOLD_ALLOW_RESELECT"


def resolve_cohort(profiles):
    """Returns the PINNED cohort, or refuses. Never silently re-picks.

    This used to fall straight through to the greedy selection below whenever a single pinned
    patient was absent from the DB, and then overwrite `metric_gold.rc2.json` keyed to FOUR
    different patients with no warning — contradicting the PINNED comment directly above, which
    exists to say that re-running "must NOT swap patients out from under already-taken captures"
    (#179 item 8). The condition is not hypothetical: the rc.2 install those 22 UUIDs describe has
    been replaced, so on today's DB every one of them is missing and the old code would have
    rewritten the 7 MB gold that `score_directness.py` loads by default, silently, keyed to a
    cohort no committed capture matches.

    Re-selection is still reachable, because bootstrapping a gold on a fresh install is what the
    greedy pass is for — but only as a deliberate act, via GOLD_ALLOW_RESELECT=1, and it says what
    it is overwriting. Returns None to mean "caller may re-select"."""
    present = {p[1] for p in profiles}
    missing = [u for u in PINNED if u not in present]
    if not missing:
        by_uuid = {p[1]: p for p in profiles}
        return [by_uuid[u] for u in PINNED]
    if os.environ.get(RESELECT_ENV) == "1":
        print("!! %s=1: re-selecting the cohort greedily and OVERWRITING metric_gold.rc2.json.\n"
              "   %d of %d pinned patients are absent from this DB, so the new gold will be keyed\n"
              "   to different patients and no existing capture will match it."
              % (RESELECT_ENV, len(missing), len(PINNED)))
        return None
    raise SystemExit(
        "ERROR: %d of %d pinned patients are absent from this DB, so the pinned gold cannot be\n"
        "  rebuilt. Refusing to write: the greedy fallback would overwrite metric_gold.rc2.json\n"
        "  keyed to DIFFERENT patients, and every committed capture would silently stop matching.\n"
        "  Missing: %s\n"
        "  If you really are bootstrapping a gold for a new install, re-run with %s=1."
        % (len(missing), len(PINNED), missing[:6], RESELECT_ENV))


def main():
    profiles = profile_patients()
    pinned = resolve_cohort(profiles)
    if pinned is not None:
        write_outputs(pinned)
        return
    # Selection: rank by chart size; greedily add patients that keep aggregate present/absent
    # balance (aim: each topic present on >=1 patient AND absent on >=1 patient where possible).
    profiles.sort(key=lambda p: -p[3])
    chosen = []
    for p in profiles:
        if len(chosen) == 4:
            break
        chosen.append(p)
    # Prefer swapping in patients that add absent-cells for typed topics if the top-4 lack them.
    def absent_cells(sel):
        return sum(1 for t in TOPICS for p in sel if p[4][t] == 0)
    for cand in profiles[4:]:
        best = min(chosen, key=lambda p: p[3])
        trial = [c for c in chosen if c is not best] + [cand]
        if absent_cells(trial) > absent_cells(chosen) and cand[3] >= 150:
            chosen = trial
    # Force-include the drug-KB demo patient (the only patient on this install with real drug
    # orders) so the medications topic has a present cell that exercises typed completeness.
    for cand in profiles:
        if cand[4]["medications"] > 0 and all(c[4]["medications"] == 0 for c in chosen):
            chosen.append(cand)
            break
    write_outputs(chosen)


def write_outputs(chosen):
    audit = ["# rc.2 gold audit — boundaries inherited from the original human gold", ""]
    gold = {}
    for name, uuid, pid, unisize, counts in chosen:
        uni = fetch_universe(pid)
        audit.append(f"## {name} (pid {pid}, {uuid}) — {unisize} records, topic counts {counts}")
        for t in TOPICS:
            ontopic = {}
            for u, kind, n, display, tv in uni:
                if classify(t, kind, n, display, tv) is True:
                    ontopic[u] = display
            present = len(ontopic) > 0
            gold[f"{uuid}|{t}"] = {
                "present": present,
                "ontopic": ontopic,
                "focus_uuids": {u: d for (u, k, n, d, tv) in uni},
            }
            audit.append(f"### {name}|{t} present={present} ontopic={len(ontopic)}")
            for u, d in sorted(ontopic.items(), key=lambda kv: kv[1])[:60]:
                audit.append(f"  ON  {d[:120]}")
    with open(os.path.join(HERE, "metric_gold.rc2.json"), "w") as f:
        json.dump(gold, f, indent=1)
    with open(os.path.join(HERE, "offtopic_adj.rc2.json"), "w") as f:
        json.dump({"_ontopic": {}}, f, indent=1)
    with open(os.path.join(HERE, "gold_audit.rc2.md"), "w") as f:
        f.write("\n".join(audit) + "\n")
    print("chosen patients:")
    for name, uuid, pid, unisize, counts in chosen:
        print(f"  {name} pid={pid} uuid={uuid} records={unisize} {counts}")
    print(f"cells={len(gold)} -> metric_gold.rc2.json / offtopic_adj.rc2.json / gold_audit.rc2.md")


def selftest():
    """Offline regression guard for the classify() boundaries (no DB). Locks the two
    substring bug-fixes (adrenal !-> kidney, reducing-substance !-> mental) and the
    drug-allergy adjudications, so re-broadening a stem fails here instead of silently
    miscounting the gold. Names are passed already-normalized, as fetch_universe() feeds
    them. condition/diagnosis/allergy/program/drug_order paths take no DB."""
    C = classify
    # kidney: "renal" is broad but the adrenal/suprarenal ENDOCRINE glands are excluded; genuine
    # renal-prefixed terms (prerenal/perirenal/intrarenal) still count.
    assert C("kidney", "condition", "malignant tumor of adrenal gland", "") is False, "adrenal is endocrine, not kidney"
    assert C("kidney", "condition", "malignant tumor of suprarenal gland", "") is False, "suprarenal is endocrine, not kidney"
    assert C("kidney", "condition", "prerenal azotemia", "") is True, "prerenal IS a renal term"
    assert C("kidney", "condition", "chronic kidney disease, stage v", "") is True
    assert C("kidney", "condition", "significant renal impairment", "") is True
    assert C("kidney", "diagnosis", "acute pyelonephritis", "") is True
    # mental: the "substance" stem must NOT catch "reducing substance" (a GI lab), but must catch use disorders.
    assert C("mental", "obs", "stool test for reducing substance", "") is False, "reducing substance is not mental"
    assert C("mental", "condition", "substance abuse", "") is True
    assert C("mental", "condition", "moderate alcohol use disorder", "") is True
    assert C("mental", "diagnosis", "mental or behavioral disorder due to psychoactive substance", "") is True
    # drug-allergies: allergy rows keyed on allergen_type=drug (the textval slot); condition/
    # diagnosis via adjudication. Signature is classify(topic, kind, name, display, textval="").
    assert C("drug-allergies", "allergy", "penicillin drug class", "", "drug") is True
    assert C("drug-allergies", "allergy", "bee venom", "", "food") is False
    assert C("drug-allergies", "condition", "allergy to sevoflurane", "") is True
    assert C("drug-allergies", "condition", "allergy to imipenem", "") is True
    assert C("drug-allergies", "diagnosis", "allergy to latex", "") is False
    assert C("drug-allergies", "condition", "food allergy", "") is False
    # allergies (superset): any allergy row + allergy-named conditions on-topic.
    assert C("allergies", "allergy", "penicillin drug class", "", "drug") is True
    assert C("allergies", "condition", "allergy to sevoflurane", "") is True
    # typed topics: DB-truth anchors, no stems.
    assert C("programs", "program", "pmtct", "") is True
    assert C("programs", "condition", "chronic kidney disease", "") is False
    assert C("medications", "drug_order", "warfarin", "") is True

    # The pinned-cohort guard (#179 item 8), which needs no DB: profile tuples are built from the
    # real PINNED constant, so this cannot pass by testing invented UUIDs. Tuple shape matches
    # profile_patients(): (name, uuid, person_id, chart_size, per-topic counts).
    def prof(uuids):
        return [("p%d" % i, u, i, 500, {t: 1 for t in TOPICS}) for i, u in enumerate(uuids)]

    full = resolve_cohort(prof(PINNED))
    assert [p[1] for p in full] == PINNED, "a complete cohort must come back in PINNED order"

    # One patient short must REFUSE, not fall through to greedy re-selection.
    saved = os.environ.pop(RESELECT_ENV, None)
    try:
        try:
            resolve_cohort(prof(PINNED[:-1]))
        except SystemExit as e:
            assert "Refusing to write" in str(e), "wrong refusal message: %s" % e
            assert PINNED[-1] in str(e), "the refusal must name the missing patient"
        else:
            raise AssertionError("an incomplete pinned cohort must refuse to write, not re-select")
        # An empty DB is the same refusal, not a fresh 4-patient gold.
        try:
            resolve_cohort([])
        except SystemExit as e:
            assert "Refusing to write" in str(e), "wrong refusal message: %s" % e
        else:
            raise AssertionError("an empty DB must refuse to write")
        # ...and re-selection stays reachable, but only deliberately.
        os.environ[RESELECT_ENV] = "1"
        assert resolve_cohort(prof(PINNED[:-1])) is None, \
            "%s=1 must permit re-selection" % RESELECT_ENV
    finally:
        os.environ.pop(RESELECT_ENV, None)
        if saved is not None:
            os.environ[RESELECT_ENV] = saved
    print("selftest OK")


if __name__ == "__main__":
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        selftest()
    else:
        main()
