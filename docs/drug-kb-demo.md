# Drug knowledge base — demo setup

A reproducible setup for demonstrating **every** path of the drug-reference / drug-safety
feature on an OpenMRS standalone (or any RefApp demo database). It creates one dedicated
test patient with the allergies, conditions and active drug orders needed to trigger each
warning, adds the ATC concept mappings the class-based paths need, and (for the
cross-reactivity path) swaps in a small custom knowledge base.

For the feature itself — config reference, design, API/SSE shape — see the
[README "Drug-reference injection & safety validation"](../README.md#drug-reference-injection--safety-validation)
section and [ADR Decisions 23 & 24](adr.md). This document is only the **demo data**.

> All seeded rows use UUIDs prefixed `dkb` so the teardown can find them. The numeric
> `concept_id`s in `atc_drugkb.sql` (114/118/122/3923) are from the RefApp demo DB — see
> [Portability](#portability) if you run against a different database.

---

## What it lets you demonstrate

| # | Path | How it fires |
|---|------|--------------|
| 1 | Question-driven injection | a KB drug name/alias in the question → a `drug_reference` citation is injected |
| 2 | Contraindication — allergy | patient allergy token matches a KB contraindication (coded + non-coded) |
| 3 | Contraindication — condition | patient condition token matches a KB contraindication (coded + non-coded) |
| 4 | Contraindication — "recorded allergy to X" (ATC class) | allergy resolves to the very drug asked about |
| 5 | Interaction — active order | a patient's active order matches a KB interaction token |
| 6 | Interaction — duplicate therapy (ATC class) | an active order shares the asked drug's ATC level-4 subgroup |
| 7 | Order-driven injection | a reference is injected from an active order (needs `injectFromQuery=false` to see in isolation) |
| 8 | Cross-reactivity (ATC class) | an allergy to a *different* drug in the same ATC subgroup as the asked drug |
| 9 | Overdose | the answer states a dose above the KB maximum (LLM-output dependent — see [caveat](#overdose-caveat)) |

The bundled KB has four drugs: **ibuprofen** (`M01AE01`), **paracetamol** (`N02BE01`),
**amoxicillin** (`J01CA04`), **gentamicin** (`J01GB03`). Paths 1–7 work against the bundled
KB; path 8 needs the [custom KB](#step-4-cross-reactivity-custom-kb) (it adds Naproxen,
`M01AE02`, sharing ibuprofen's subgroup).

---

## Prerequisites

- A running standalone with the chartsearchai module (these examples assume
  `http://localhost:8081/openmrs` with the bundled MariaDB on port `3316`).
- The bundled MariaDB client, e.g. `./database/bin/mariadb -h127.0.0.1 -P3316 -uroot openmrs`.
- REST calls below use **cookie-jar auth** — basic-auth alone loses the session mid-sequence:

  ```bash
  J=cookies.txt; AUTH='admin:Admin123'; BASE=http://localhost:8081/openmrs/ws/rest/v1
  curl -s -u "$AUTH" -c $J "$BASE/session" -o /dev/null   # establish session
  gp(){ curl -s -u "$AUTH" -b $J -c $J -H 'Content-Type: application/json' \
        -X POST "$BASE/systemsetting/$1" -d "{\"value\":\"$2\"}" -w " $1=$2 [%{http_code}]\n" -o /dev/null; }
  ```

> The stock RefApp demo DB has **0 allergies and 0 drug orders**, so without this seed the
> patient-driven paths have nothing to act on even with the feature enabled.

---

## Step 1 — enable the feature

The master switch gates everything: `DrugSafetyValidator` and `DrugReferenceInjector` both
short-circuit to empty when it is `false`, regardless of `validateAnswers`.

```bash
gp chartsearchai.drugReference.enabled true
# these default to true already; shown for completeness
gp chartsearchai.drugSafety.validateAnswers true
gp chartsearchai.drugReference.injectFromQuery true
```

---

## Step 2 — seed the test patient (`seed_drugkb.sql`)

Creates **Margaret Holloway** (UUID `dkb00000-0000-0000-0000-000000000001`), an adult so the
adult dose bands apply. Idempotent — re-running wipes and recreates. Run with:

```bash
./database/bin/mariadb -h127.0.0.1 -P3316 -uroot openmrs < seed_drugkb.sql
```

```sql
-- ============================================================================
-- Drug Knowledge Base test patient seed (idempotent).
-- Creates one dedicated patient "Margaret Holloway" with the allergies,
-- conditions and active drug orders needed to fire every name/token-based
-- drug-KB path against the 4 KB drugs (ibuprofen, paracetamol, amoxicillin,
-- gentamicin). All rows carry uuids prefixed 'dkb' for easy teardown.
-- ============================================================================
SET @puuid := 'dkb00000-0000-0000-0000-000000000001';

-- ---- teardown any prior run (child -> parent) -----------------------------
SET @pid := (SELECT person_id FROM person WHERE uuid=@puuid);
DELETE FROM drug_order        WHERE order_id IN (SELECT order_id FROM orders WHERE patient_id=@pid);
DELETE FROM orders            WHERE patient_id=@pid;
DELETE FROM encounter         WHERE patient_id=@pid;
DELETE FROM conditions        WHERE patient_id=@pid;
DELETE FROM allergy           WHERE patient_id=@pid;
DELETE FROM patient_identifier WHERE patient_id=@pid;
DELETE FROM patient           WHERE patient_id=@pid;
DELETE FROM person_name       WHERE person_id=@pid;
DELETE FROM person_address    WHERE person_id=@pid;
DELETE FROM person            WHERE person_id=@pid;

-- ---- person + patient ------------------------------------------------------
INSERT INTO person (gender, birthdate, birthdate_estimated, dead, deathdate_estimated, creator, date_created, voided, uuid)
VALUES ('F','1985-01-01',0,0,0,1,NOW(),0,@puuid);
SET @pid := LAST_INSERT_ID();

INSERT INTO person_name (preferred, person_id, given_name, family_name, creator, date_created, voided, uuid)
VALUES (1,@pid,'Margaret','Holloway',1,NOW(),0,'dkb00000-0000-0000-0000-0000000000n1');

INSERT INTO patient (patient_id, creator, date_created, voided, allergy_status)
VALUES (@pid,1,NOW(),0,'Unknown');

-- OpenMRS ID (type 3) for a neutral, demo-friendly banner. '1007427' = base '100742'
-- plus the idgen Luhn-mod-30 check digit '7'; if you change it, recompute the check digit.
INSERT INTO patient_identifier (patient_id, identifier, identifier_type, preferred, location_id, creator, date_created, voided, uuid)
VALUES (@pid,'1007427',3,1,1,1,NOW(),0,'dkb00000-0000-0000-0000-0000000000i1');

-- ---- allergies (contraindication path) ------------------------------------
-- coded_allergen is NOT NULL; the non-coded aminoglycoside uses the "Other" concept (5622) + free text.
INSERT INTO allergy (patient_id, coded_allergen, non_coded_allergen, allergen_type, creator, date_created, voided, uuid) VALUES
 (@pid,(SELECT concept_id FROM concept WHERE uuid='77897AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'),NULL,'DRUG',1,NOW(),0,'dkb00000-0000-0000-0000-0000000000a1'),
 (@pid,(SELECT concept_id FROM concept WHERE uuid='162297AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'),NULL,'DRUG',1,NOW(),0,'dkb00000-0000-0000-0000-0000000000a2'),
 (@pid,(SELECT concept_id FROM concept WHERE uuid='70116AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'),NULL,'DRUG',1,NOW(),0,'dkb00000-0000-0000-0000-0000000000a3'),
 (@pid,(SELECT concept_id FROM concept WHERE uuid='5622AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'),'Aminoglycoside','DRUG',1,NOW(),0,'dkb00000-0000-0000-0000-0000000000a4');

-- ---- conditions (contraindication path) -----------------------------------
INSERT INTO conditions (patient_id, condition_coded, condition_non_coded, clinical_status, creator, date_created, voided, uuid) VALUES
 (@pid,(SELECT concept_id FROM concept WHERE uuid='114262AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'),NULL,'ACTIVE',1,NOW(),0,'dkb00000-0000-0000-0000-0000000000c1'),
 (@pid,NULL,'Active GI bleed','ACTIVE',1,NOW(),0,'dkb00000-0000-0000-0000-0000000000c2'),
 (@pid,NULL,'Severe hepatic impairment','ACTIVE',1,NOW(),0,'dkb00000-0000-0000-0000-0000000000c3'),
 (@pid,NULL,'Significant renal impairment','ACTIVE',1,NOW(),0,'dkb00000-0000-0000-0000-0000000000c4');

-- ---- one encounter to hang the drug orders on ------------------------------
INSERT INTO encounter (encounter_type, patient_id, encounter_datetime, location_id, creator, date_created, voided, uuid)
VALUES (3,@pid,NOW(),1,1,NOW(),0,'dkb00000-0000-0000-0000-0000000000e1');
SET @enc := LAST_INSERT_ID();

-- ---- active drug orders (interaction path; matched by concept name token) --
INSERT INTO orders (order_type_id, concept_id, orderer, encounter_id, creator, date_created, voided, patient_id, urgency, order_number, order_action, care_setting, date_activated, uuid) VALUES
 (2,(SELECT concept_id FROM concept WHERE uuid='5e97fe35-58df-4925-bba7-7c49d75268a1'),1,@enc,1,NOW(),0,@pid,'ROUTINE','DKB-ORD-1','NEW',1,NOW(),'dkb00000-0000-0000-0000-0000000000o1'),
 (2,(SELECT concept_id FROM concept WHERE uuid='71617AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'),1,@enc,1,NOW(),0,@pid,'ROUTINE','DKB-ORD-2','NEW',1,NOW(),'dkb00000-0000-0000-0000-0000000000o2'),
 (2,(SELECT concept_id FROM concept WHERE uuid='017dcf18-5ba3-4aac-b12a-27a5df1ffee5'),1,@enc,1,NOW(),0,@pid,'ROUTINE','DKB-ORD-3','NEW',1,NOW(),'dkb00000-0000-0000-0000-0000000000o3'),
 (2,(SELECT concept_id FROM concept WHERE uuid='9f6c3927-2a31-485c-8735-f10b9951aef9'),1,@enc,1,NOW(),0,@pid,'ROUTINE','DKB-ORD-4','NEW',1,NOW(),'dkb00000-0000-0000-0000-0000000000o4');

INSERT INTO drug_order (order_id, dispense_as_written)
SELECT order_id, 0 FROM orders WHERE patient_id=@pid;

-- ---- report ----------------------------------------------------------------
SELECT @puuid AS patient_uuid, @pid AS patient_id;
SELECT 'allergies' k, COUNT(*) n FROM allergy WHERE patient_id=@pid AND voided=0
UNION ALL SELECT 'conditions', COUNT(*) FROM conditions WHERE patient_id=@pid AND voided=0
UNION ALL SELECT 'active_drug_orders', COUNT(*) FROM orders WHERE patient_id=@pid AND voided=0;
```

**Seeded clinical data** (concept UUIDs are stable across RefApp demo DBs):

| Type | Items | Drives |
|------|-------|--------|
| Allergies | Ibuprofen `77897…`, Penicillins `162297…`, Paracetamol `70116…`, non-coded "Aminoglycoside" (on "Other" `5622…`) | paths 2, 4, 8 |
| Conditions | Peptic ulcer `114262…`, non-coded "Active GI bleed" / "Severe hepatic impairment" / "Significant renal impairment" | path 3 |
| Active orders | Warfarin `5e97fe35…`, Aspirin `71617…`, Methotrexate `017dcf18…`, Furosemide `9f6c3927…` | path 5 |

---

## Step 3 — ATC mappings for the class-based paths (`atc_drugkb.sql`)

Adds WHO-ATC mappings to real, correctly-classified concepts plus four more active orders, so
the **duplicate-therapy** (path 6) and **order-driven injection** (path 7) paths can fire.
Ampicillin (`J01CA01`) is a sibling of amoxicillin (`J01CA04`); Amikacin (`J01GB06`) a sibling
of gentamicin (`J01GB03`). Run **after** the seed:

```bash
./database/bin/mariadb -h127.0.0.1 -P3316 -uroot openmrs < atc_drugkb.sql
```

```sql
-- ============================================================================
-- ATC mappings + sibling/own-drug orders to make the ATC-CLASS paths testable:
--   * class interaction (duplicate therapy)  -- Ampicillin~Amoxicillin, Amikacin~Gentamicin
--   * order-driven injection                 -- Amoxicillin/Gentamicin own-code orders
-- Maps real, correctly-classified WHO-ATC codes onto real concepts. Idempotent.
-- Requires seed_drugkb.sql to have created the patient first.
-- ============================================================================
SET @src := (SELECT concept_source_id FROM concept_reference_source WHERE name='WHOATC');
SET @puuid := 'dkb00000-0000-0000-0000-000000000001';
SET @pid := (SELECT person_id FROM person WHERE uuid=@puuid);
SET @enc := (SELECT encounter_id FROM encounter WHERE uuid='dkb00000-0000-0000-0000-0000000000e1');

-- ---- WHOATC terms (create the two that don't already exist) -----------------
INSERT INTO concept_reference_term (concept_source_id, code, creator, date_created, retired, uuid)
SELECT @src,'J01CA01',1,NOW(),0,'dkb00000-0000-0000-0000-0000000000t1'
WHERE NOT EXISTS (SELECT 1 FROM concept_reference_term WHERE concept_source_id=@src AND code='J01CA01');
INSERT INTO concept_reference_term (concept_source_id, code, creator, date_created, retired, uuid)
SELECT @src,'J01GB03',1,NOW(),0,'dkb00000-0000-0000-0000-0000000000t2'
WHERE NOT EXISTS (SELECT 1 FROM concept_reference_term WHERE concept_source_id=@src AND code='J01GB03');

SET @t_amp  := (SELECT concept_reference_term_id FROM concept_reference_term WHERE concept_source_id=@src AND code='J01CA01');
SET @t_amox := (SELECT concept_reference_term_id FROM concept_reference_term WHERE concept_source_id=@src AND code='J01CA04');
SET @t_amik := (SELECT concept_reference_term_id FROM concept_reference_term WHERE concept_source_id=@src AND code='J01GB06');
SET @t_gent := (SELECT concept_reference_term_id FROM concept_reference_term WHERE concept_source_id=@src AND code='J01GB03');

-- ---- concept_reference_map (SAME-AS=1): concept -> ATC term ------------------
-- 114 Ampicillin -> J01CA01 ; 118 Amoxicillin -> J01CA04 ; 122 Amikacin -> J01GB06 ; 3923 Gentamicin -> J01GB03
DELETE FROM concept_reference_map WHERE uuid IN
 ('dkb00000-0000-0000-0000-0000000000m1','dkb00000-0000-0000-0000-0000000000m2',
  'dkb00000-0000-0000-0000-0000000000m3','dkb00000-0000-0000-0000-0000000000m4');
INSERT INTO concept_reference_map (concept_reference_term_id, concept_map_type_id, creator, date_created, concept_id, uuid) VALUES
 (@t_amp ,1,1,NOW(),114 ,'dkb00000-0000-0000-0000-0000000000m1'),
 (@t_amox,1,1,NOW(),118 ,'dkb00000-0000-0000-0000-0000000000m2'),
 (@t_amik,1,1,NOW(),122 ,'dkb00000-0000-0000-0000-0000000000m3'),
 (@t_gent,1,1,NOW(),3923,'dkb00000-0000-0000-0000-0000000000m4');

-- ---- active drug orders for the four ATC-mapped concepts --------------------
DELETE FROM drug_order WHERE order_id IN (SELECT order_id FROM orders WHERE patient_id=@pid AND order_number IN ('DKB-ORD-5','DKB-ORD-6','DKB-ORD-7','DKB-ORD-8'));
DELETE FROM orders WHERE patient_id=@pid AND order_number IN ('DKB-ORD-5','DKB-ORD-6','DKB-ORD-7','DKB-ORD-8');
INSERT INTO orders (order_type_id, concept_id, orderer, encounter_id, creator, date_created, voided, patient_id, urgency, order_number, order_action, care_setting, date_activated, uuid) VALUES
 (2,114 ,1,@enc,1,NOW(),0,@pid,'ROUTINE','DKB-ORD-5','NEW',1,NOW(),'dkb00000-0000-0000-0000-0000000000o5'),
 (2,118 ,1,@enc,1,NOW(),0,@pid,'ROUTINE','DKB-ORD-6','NEW',1,NOW(),'dkb00000-0000-0000-0000-0000000000o6'),
 (2,122 ,1,@enc,1,NOW(),0,@pid,'ROUTINE','DKB-ORD-7','NEW',1,NOW(),'dkb00000-0000-0000-0000-0000000000o7'),
 (2,3923,1,@enc,1,NOW(),0,@pid,'ROUTINE','DKB-ORD-8','NEW',1,NOW(),'dkb00000-0000-0000-0000-0000000000o8');
INSERT INTO drug_order (order_id, dispense_as_written)
SELECT order_id, 0 FROM orders WHERE patient_id=@pid AND order_number IN ('DKB-ORD-5','DKB-ORD-6','DKB-ORD-7','DKB-ORD-8');

-- ---- report -----------------------------------------------------------------
SELECT cn.name concept, t.code atc FROM concept_reference_map m
 JOIN concept_reference_term t ON t.concept_reference_term_id=m.concept_reference_term_id
 JOIN concept_name cn ON cn.concept_id=m.concept_id AND cn.locale='en' AND cn.concept_name_type='FULLY_SPECIFIED'
 WHERE m.uuid LIKE 'dkb%';
SELECT order_number, (SELECT cn.name FROM concept_name cn WHERE cn.concept_id=o.concept_id AND cn.locale='en' AND cn.concept_name_type='FULLY_SPECIFIED' LIMIT 1) drug
 FROM orders o WHERE patient_id=@pid AND order_number LIKE 'DKB-ORD-%' ORDER BY order_number;
```

> These concept mappings are **global** (they apply to those concepts for every patient), but
> the data is clinically correct (Amoxicillin *is* `J01CA04`, etc.), so it is harmless. The
> teardown removes them.

---

## Step 4 — cross-reactivity custom KB

> **What "the knowledge base" is.** The knowledge base is the **drug dataset** the feature
> reads — drug entries with aliases, ATC codes, age-banded doses, and interaction/contraindication
> rules. It is *data*, not logic: the code that injects citations, parses doses, and derives the
> ATC-class warnings lives in the module, not in the file. The active dataset comes from one of
> two sources:
> - **Bundled default** — `drug-reference.json` shipped inside the module (the four drugs above).
>   Used automatically when no override is configured.
> - **External override** — any file `chartsearchai.drugReference.dataFilePath` points at (within
>   the app-data directory). When set, it **replaces** the bundled dataset entirely — it does
>   **not** merge with it. So a custom file must contain *all* the drugs you want, not just the
>   additions.
>
> `drug-reference-custom.json` below is such an override (the bundled four **plus** Naproxen);
> while `dataFilePath` points at it, it *is* the live knowledge base and the bundled one is
> dormant. Clearing the GP (and restarting) reverts to the bundled dataset. *(A third option:
> `sourceFormat=atc` reads a WHO-ATC classification export instead of JSON — classification only,
> no hand-authored rules; the default `json` format is the curated, rule-bearing KB.)*

Cross-reactivity (path 8) needs two KB drugs sharing an ATC subgroup. The bundled four are all
in different subgroups, so this path is unreachable by patient data alone. Extend the KB via
the external-file mechanism (no rebuild):

1. Copy a KB file with the extra drug to `<application-data-directory>/drug-reference-custom.json`.
   A ready-made one is committed alongside this doc at [`docs/drug-reference-custom.json`](drug-reference-custom.json)
   (the bundled four **plus** Naproxen) — just copy it into the app-data dir. Equivalently, copy the
   bundled `api/src/main/resources/chartsearchai/drug-reference.json` and append this fifth entry
   (Naproxen shares ibuprofen's `M01AE` subgroup; it carries **no** contraindications/interactions
   of its own, so a naproxen query surfaces *only* the cross-reactivity warning):

   ```json
   {
     "id": "naproxen",
     "name": "Naproxen",
     "drugClass": "NSAID",
     "aliases": ["naproxen", "naprosyn", "aleve"],
     "atcCodes": ["M01AE02"],
     "ageBands": [
       { "minYears": 12, "maxYears": 120, "mgPerKgMin": 5, "mgPerKgMax": 10, "maxDailyDoseMg": 1000 }
     ],
     "interactions": [],
     "contraindications": [],
     "source": "WHO ATC classification (test entry: shares M01AE subgroup with ibuprofen)"
   }
   ```

2. Point the GP at it and **restart** (the KB is cached in-process; it only reloads on restart):

   ```bash
   gp chartsearchai.drugReference.dataFilePath drug-reference-custom.json
   # then restart the standalone / module
   ```

---

## Knowledge base entry schema

Reference for authoring a custom KB (the `json` source format). The top-level file is
`{ "version": ..., "source": ..., "description": ..., "entries": [ ... ] }` — `version` /
`source` / `description` are informational; `entries` is the array of drug objects below.

**Per-drug entry**

| Field | Type | Read by | Purpose / notes |
|-------|------|---------|-----------------|
| `id` | string | injection | **Required** — an entry with a blank `id` is dropped at load (it has no stable citation `resourceUuid`). Keep unique (e.g. `naproxen`). |
| `name` | string | injection, validator | **Required** — an entry with a blank `name` is dropped at load (it would render a null display name). Shown in the injected reference and in `safetyWarnings[].drug`. |
| `drugClass` | string | injection | Human-readable class label rendered in the reference text (e.g. "NSAID"). Informational only — class **logic** uses `atcCodes`, not this. |
| `aliases` | string[] | injection, validator | Lowercase names matched **whole-word, case-insensitive** against the question and the answer ("advil" matches "is advil safe?"; "amox" won't spuriously match). Drives question-driven injection and which drug a warning is attributed to. |
| `atcCodes` | string[] | injection, validator | WHO-ATC codes. Used two ways: **exact code** → order-driven injection / interaction match against an active order's ATC; **level-4 prefix** (`M01AE01` → `M01AE`) → the class-based cross-reactivity & duplicate-therapy checks. Two drugs are "same class" iff their level-4 subgroups intersect. |
| `ageBands` | object[] | injection, overdose | Age-banded dosing (below). The band whose range contains the patient's age is selected; **no matching band → no numeric dosing rendered and no overdose check** (this is the age-gating that stops a pediatric max being shown for an adult). |
| `warnings` | string[] | injection | Optional free-text prose warnings (e.g. a Reye-syndrome caution) rendered verbatim into the injected, citable record so the LLM can ground and cite them. **Display-only** — no matchable token, so the validator never fires on them; enforceable facts belong in the rule fields. |
| `interactions` | object[] | interaction warning | Drug–drug interaction rules (below). |
| `contraindications` | object[] | contraindication warning | Allergy/condition rules (below). |
| `source` | string | injection | Provenance string rendered in the reference text. |
| `genericName` | string, optional | Everyday generic synonym appended to safety-chip labels when it genuinely diverges from `name` (neither containing the other) — `"Acetylsalicylic acid (aspirin)"`. Display only: never rendered into the reference text the LLM sees, never used for matching. A redundant value (equal to, containing, or contained in `name`) is ignored. |

**`ageBands[]` object**

| Field | Type | Purpose / notes |
|-------|------|-----------------|
| `minYears`, `maxYears` | number | Inclusive age range (years) the band applies to. |
| `mgPerKgMin`, `mgPerKgMax` | number | Per-dose mg/kg range rendered in the reference text. `mgPerKgMax` also drives the **weight-aware per-dose overdose check**: with a fresh weight on record (see the `weightConceptUuid` / `weightMaxAgeDays` GPs), a per-administration dose above `mgPerKgMax` × weight is flagged. |
| `maxDailyDoseMg` | number | Daily maximum for the overdose check. **`0` is a sentinel meaning "no published maximum"** — dosing is still rendered and the daily arm stays silent for that band, though the weight-aware per-dose arm still runs when `mgPerKgMax` is set. The overdose parser reads the literal unit `mg` only (grams are not flagged). |

**`interactions[]` object**

| Field | Type | Purpose / notes |
|-------|------|-----------------|
| `token` | string | Substring matched (case-insensitive) against the patient's active-order drug names. |
| `atc` | string | ATC code matched against the active order's ATC mapping (an alternative to `token`). |
| `note` | string | Free text appended to the interaction warning (e.g. "increased risk of GI bleeding"). |
| `severity` | string, optional | Source-assigned rating (`Unknown`/`Minor`/`Moderate`/`Major`). **Rating a rule opts it into the severity floor** (`chartsearchai.drugSafety.minInteractionSeverity`, default `minor`): a rule rated below the floor raises no warning. Omit it (the usual case for hand-authored rules) and the rule always fires. |

**`contraindications[]` object**

| Field | Type | Purpose / notes |
|-------|------|-----------------|
| `type` | string | Must be exactly `allergy` or `condition` — selects whether `token` is matched against the patient's **allergies** or **active conditions**. |
| `token` | string | Substring matched (case-insensitive) against that patient data (e.g. `nsaid`, `peptic ulcer`). |
| `note` | string | Free text shown in the contraindication warning. |

> The hand-authored `interactions` / `contraindications` rules are **additive** to the ATC
> class-based checks: with only `atcCodes` (and no rules), the validator still derives
> cross-reactivity and duplicate-therapy warnings from ATC class membership — which is how the
> rule-less `atc` source format produces warnings at all.

## Cross-reactivity groups file schema

A second, independent data file (`chartsearchai.drugReference.crossReactivityGroupsFilePath`,
bundled fallback `cross-reactivity-groups.json`) loaded alongside **either** source format. It
carries the cross-*branch* family knowledge ATC's tree cannot express (ADR Decision 27). The
top-level file is `{ "version": ..., "description": ..., "groups": [ ... ] }`.

**`groups[]` object**

| Field | Type | Purpose / notes |
|-------|------|-----------------|
| `name` | string | Family name shown in the warning (e.g. `NSAID`). |
| `note` | string | Informational — documents why the group exists; not rendered in warnings. |
| `atcPrefixes` | string[] | ATC code prefixes, **any level** (`M01AE`, `N02BA`, or broader like `M01A`) — the data chooses each family's breadth. A drug is a member when any of its `atcCodes` starts with any prefix. |

Two drugs sharing a group — but **no** ATC level-4 subgroup (the more specific match always
wins, one warning per fact) — raise cross-reactivity contraindications against allergies and
additive / duplicate-class interaction warnings against active orders, and make an active order
relevant enough for its reference to be injected on a related question. The bundled seed is one
NSAID group spanning `M01AE` + `N02BA` (the aspirin↔ibuprofen case) — expand per deployment.
Editing the file takes effect on the next module restart (same lazy-cache contract as the
entry dataset).

> **Not exercised by this demo's seed (Decision 27 additions).** The cheat-sheet below predates
> the weight-aware per-dose check and the cross-branch group path, and the seed gives Margaret
> neither a weight obs nor an `N02BA`-mapped order — so neither new mechanism fires here (both
> are pinned by `evals/drug-reference/drug-safety-eval.json` and the context tests). To see them
> live: record a recent Weight (kg) obs for the patient and ask a per-dose question (e.g.
> *"Can she take ibuprofen 600 mg every 8 hours?"* — flags once the stated dose exceeds
> `mgPerKgMax` × weight; note the overdose arms read the dose from the **answer**, so use the
> "repeat back the proposed order" phrasing from the [overdose caveat](#overdose-caveat) on a
> clean patient), and map the Aspirin concept to ATC `N02BA01` for the cross-branch group chip.
> The committed `atc_drugkb.sql` maps only the J01CA/J01GB antibiotics, but the long-lived
> :8081 instance **already carries** an `N02BA01` mapping on Aspirin — live-verified 2026-07-10:
> the ibuprofen query there shows an extra *"Ibuprofen is in the same cross-reactivity group
> (NSAID) as active order N02BA01 — possible additive or duplicate-class therapy"* chip (the
> bare code appears because no KB entry carries `N02BA01`; wording as of the sentence-detail
> refactor — the 2026-07-10 capture predates the leading subject). Both Decision-27 paths were live-verified end-to-end that day
> (weight arm: `~1000 mg exceeds the 15 mg/kg per-dose maximum (~750 mg) … weight 50 kg`,
> driven by the bundled CIEL default with no GP row).

---

## Query cheat-sheet

Run on **Margaret Holloway** (`dkb00000-0000-0000-0000-000000000001`) unless noted. Each
query surfaces only the warnings for the drug named.

| Query | Expected `safetyWarnings` / injection |
|-------|----------------------------------------|
| *Can this patient take ibuprofen?* | injected `ibuprofen`; contraindication (ibuprofen allergy, GI bleed, peptic ulcer), "The patient has a recorded allergy to Ibuprofen.", interaction (warfarin, aspirin) — plus, where the Aspirin order is `N02BA`-mapped (the live :8081 instance is), an "Ibuprofen is in the same cross-reactivity group (NSAID)…" interaction |
| *Is amoxicillin safe for this patient?* | injected `amoxicillin`; contraindication (penicillin-class), interaction (methotrexate), **duplicate therapy J01CA** (Ampicillin) |
| *Can this patient take paracetamol?* | injected `paracetamol`; contraindication (severe hepatic), "The patient has a recorded allergy to Paracetamol.", interaction (warfarin) |
| *Is gentamicin appropriate for this patient?* | injected `gentamicin`; contraindication (aminoglycoside allergy, renal impairment), interaction (furosemide), **duplicate therapy J01GB** (Amikacin) |
| *Is naproxen safe for this patient?* | injected `naproxen`; **cross-reactivity** "Naproxen is in the same ATC class (M01AE) as the patient's allergy to Ibuprofen…" *(needs Step 4)* |
| any KB alias (brufen, advil, panadol, tylenol, amoxil…) on **any** patient | a `drug_reference` citation (question-driven injection needs no patient data) |

**Order-driven injection (path 7)** — set `injectFromQuery=false`, then ask
*"What is the dosing for amoxicillin?"*: the `amoxicillin` reference is still injected (from the
active order), while a drug with no order (e.g. ibuprofen) injects nothing. Restore
`injectFromQuery=true` afterward.

### Overdose caveat

Overdose (path 9) is the only **LLM-output-dependent** path: the validator parses a daily dose
from the *answer* and the parser is deliberately conservative — the dose must appear **in the
same clause as the drug name** and not be preceded by a limit word ("maximum", "up to"…). The
demo patient is *contraindicated* for paracetamol, so the model refuses without restating a
dose — demonstrate overdose on a **clean** patient (one with no paracetamol allergy/condition,
e.g. a stock demo patient) with a phrasing that keeps drug + dose together:

> *"Repeat back the proposed order and state if it is safe: paracetamol 6000 mg daily."*

→ answer "The proposed order is paracetamol 6000 mg daily…" → `overdose: The stated Paracetamol
dose ~6000 mg/day exceeds the 4000 mg/day maximum for ages 12-120`. (Phrasings where the model writes the drug and the dose in
*separate* sentences will not fire, even when the arithmetic is right.)

---

## Driving the UI (Playwright)

`ask_chart_ui.mjs` logs into the SPA, opens a patient chart, clicks **Ask AI**, submits a
question, waits for a match, and screenshots. Usage:

```bash
node ask_chart_ui.mjs "<question>" "<wait-regex>" "<screenshot-path>" "<patient-uuid>"
# e.g. cross-reactivity on the demo patient:
node ask_chart_ui.mjs "Is naproxen safe for this patient?" "cross-reactivity" out.png
# e.g. overdose on a clean patient:
node ask_chart_ui.mjs "Repeat back the proposed order and state if it is safe: paracetamol 6000 mg daily." \
  "overdose|exceeds" out.png <clean-patient-uuid>
```

```js
import pw from 'playwright';           // adjust to your Playwright install path
const { chromium } = pw;

const BASE = 'http://localhost:8081/openmrs/spa';
const PT = process.argv[5] || 'dkb00000-0000-0000-0000-000000000001';
const QUESTION = process.argv[2] || 'Is naproxen safe for this patient?';
const MATCH = new RegExp(process.argv[3] || 'cross-reactivity', 'i');
const SHOT = process.argv[4] || 'ask_chart.png';

const log = (...a) => console.log(...a);

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
const page = await ctx.newPage();
page.setDefaultTimeout(45000);

try {
  log('→ login');
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.locator('#username').fill('admin');
  await page.getByRole('button', { name: /continue/i }).click();
  await page.locator('#password').fill('Admin123');
  await page.getByRole('button', { name: /log in/i }).click();
  await page.getByText('Outpatient Clinic', { exact: false }).click();
  await page.getByRole('button', { name: /confirm/i }).click();
  await page.waitForURL(/\/home/, { timeout: 60000 });
  log('✓ logged in');

  log('→ open chart');
  await page.goto(`${BASE}/patient/${PT}/chart`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: /actions/i }).first().waitFor({ timeout: 60000 });
  log('✓ chart loaded');

  log('→ open AI panel');
  const launcher = page.getByRole('button', { name: /ask ai|ai search/i });
  await launcher.first().waitFor({ timeout: 45000 });
  await launcher.first().click();

  const input = page.getByPlaceholder(/ask ai about this patient/i);
  await input.waitFor({ timeout: 30000 });
  await input.fill(QUESTION);
  log(`→ ask: "${QUESTION}"`);
  const send = page.getByRole('button', { name: /^send$/i });
  if (await send.count()) await send.first().click();
  else await input.press('Enter');

  try {
    await page.getByText(MATCH).first().waitFor({ timeout: 90000 });
    log(`✓ matched "${MATCH.source}"`);
  } catch {
    log(`⚠ no match for "${MATCH.source}" within timeout; capturing anyway`);
  }
  await page.waitForTimeout(2500);

  const bodyText = await page.locator('body').innerText();
  const lines = bodyText.split('\n').map(s => s.trim()).filter(Boolean);
  const interesting = lines.filter(l => /paracetamol|naproxen|amoxicillin|overdose|exceeds|mg\/day|duplicate|therapy|interaction|contraindic|cross-reactivity|safety|reference|maximum/i.test(l));
  log('\n----- on-screen text (filtered) -----');
  interesting.forEach(l => log('  ', l));
  log('-------------------------------------');

  await page.screenshot({ path: SHOT, fullPage: false });
  log(`\n✓ screenshot: ${SHOT}`);
} catch (e) {
  log('ERROR:', e.message);
  await page.screenshot({ path: SHOT, fullPage: false }).catch(() => {});
} finally {
  await browser.close();
}
```

You can also drive everything over REST without a browser —
`POST /ws/rest/v1/chartsearchai/search` with `{"patient": "...", "question": "..."}` returns
`answer`, `references`, and `safetyWarnings`.

---

## Teardown (`teardown_drugkb.sql`)

Removes the patient, the ATC maps/terms this setup added (leaving pre-existing terms intact),
and clears the custom-KB GP. **Restart** afterward to drop the cached custom KB.

```bash
./database/bin/mariadb -h127.0.0.1 -P3316 -uroot openmrs < teardown_drugkb.sql
# then restart, and set chartsearchai.drugReference.enabled=false to fully restore defaults
```

```sql
-- Removes the Drug-KB test patient and all its seeded rows. Safe to run repeatedly.
SET @puuid := 'dkb00000-0000-0000-0000-000000000001';
SET @pid := (SELECT person_id FROM person WHERE uuid=@puuid);
DELETE FROM drug_order        WHERE order_id IN (SELECT order_id FROM orders WHERE patient_id=@pid);
DELETE FROM orders            WHERE patient_id=@pid;
DELETE FROM encounter         WHERE patient_id=@pid;
DELETE FROM conditions        WHERE patient_id=@pid;
DELETE FROM allergy           WHERE patient_id=@pid;
DELETE FROM patient_identifier WHERE patient_id=@pid;
DELETE FROM patient           WHERE patient_id=@pid;
DELETE FROM person_name       WHERE person_id=@pid;
DELETE FROM person_address    WHERE person_id=@pid;
DELETE FROM person            WHERE person_id=@pid;

-- ATC mappings added by atc_drugkb.sql (concept maps are global, not patient-scoped).
-- Remove the 4 maps I added, and only the 2 ATC terms I created (J01CA01/J01GB03);
-- leave pre-existing terms (J01CA04/J01GB06) intact.
DELETE FROM concept_reference_map WHERE uuid IN
 ('dkb00000-0000-0000-0000-0000000000m1','dkb00000-0000-0000-0000-0000000000m2',
  'dkb00000-0000-0000-0000-0000000000m3','dkb00000-0000-0000-0000-0000000000m4');
DELETE FROM concept_reference_term WHERE uuid IN
 ('dkb00000-0000-0000-0000-0000000000t1','dkb00000-0000-0000-0000-0000000000t2');

-- Revert the external KB back to the bundled 4-drug dataset (used for the
-- cross-reactivity test). NOTE: takes effect only after a module/standalone
-- restart, since the KB is cached in-process. The appdata/drug-reference-custom.json
-- file can be left in place or deleted manually.
UPDATE global_property SET property_value='' WHERE property='chartsearchai.drugReference.dataFilePath';

SELECT CONCAT('removed patient_id=', IFNULL(@pid,'(none)'), ' + ATC maps/terms + reset KB file GP') AS result;
```

---

## Portability

- **Concept UUIDs** (used by `seed_drugkb.sql`) are stable across RefApp demo databases.
- **Numeric `concept_id`s** in `atc_drugkb.sql` (114 Ampicillin, 118 Amoxicillin, 122 Amikacin,
  3923 Gentamicin) are specific to the loaded demo DB. On a different database, re-derive them:

  ```sql
  SELECT c.concept_id, cn.name FROM concept c
   JOIN concept_name cn ON cn.concept_id=c.concept_id
   WHERE cn.locale='en' AND cn.concept_name_type='FULLY_SPECIFIED'
     AND cn.name IN ('Ampicillin','Amoxicillin','Amikacin','Gentamicin');
  ```

- **`order_type_id=2`** (Drug Order), **`care_setting=1`** (Outpatient), **`location_id=1`**,
  **`encounter_type=3`** (Consultation), **`orderer=1`**, allergen/condition **concept UUIDs**,
  and **identifier_type=3** (OpenMRS ID) are all from the stock RefApp demo metadata — verify if
  your distro differs. The OpenMRS ID value needs a valid idgen Luhn-mod-30 check digit.
- **Gotcha:** the stock RefApp demo DB has 0 allergies and 0 drug orders out of the box; this
  seed is what makes the patient-driven paths demonstrable.
