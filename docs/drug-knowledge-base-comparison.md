# Drug knowledge base: chartsearchai vs. openmrs_chatbot

**Purpose.** chartsearchai's drug-reference feature adapted its knowledge-base
*data* from the [anichiti/openmrs_chatbot](https://github.com/anichiti/openmrs_chatbot)
project. This note records what was actually shared and — more importantly — how
each project *uses* the knowledge base, so the lineage and the divergence are on
the record rather than living only in chat history or memory.

**Provenance.** Compared against `openmrs_chatbot` at commit `2e723f8`
(2026-05-12, "Update README with final revisions"); chartsearchai as of 2026-06-10
(PR #41 backend class-based safety + relevance-scoped injection; PR #17 frontend).
File/line references below are to those revisions.

---

## TL;DR

Same KB **data schema** — we adapted their `drug_knowledge_base.json`. Everything
about how the KB is *used* differs:

- **openmrs_chatbot** is a standalone Python **multi-agent chatbot**. It classifies
  each query by intent and routes it to *one* knowledge source: a deterministic
  drug-JSON lookup, a **PDF RAG** store (Chroma), live **RxNorm/FDA** APIs, or the
  OpenMRS DB — and it gates the LLM *off* for most intents ("pull from DB only").
- **chartsearchai** is a Java **OpenMRS module** embedded in the chart-search
  answer pipeline. *Every* query retrieves the patient chart (embeddings),
  deterministically injects matching drug-reference records, generates one LLM
  answer, then runs a deterministic **post-answer safety validator**.

Two consequences worth highlighting up front:
- Their RAG **does not index the drug KB** — it indexes clinical PDFs. The drug
  JSON is read deterministically. (We don't RAG the drug KB either.)
- Their **ATC codes are stored but never used in code**; ours drive class-based
  safety reasoning (cross-reactivity, duplicate therapy) and a WHO-ATC source.

---

## 1. Shared: the KB data schema (the part that was copied)

Their `data/drug_knowledge_base.json` entry shape is the ancestor of our
`api/src/main/resources/chartsearchai/drug-reference.json`:

| Field (openmrs_chatbot) | Field (chartsearchai) | Notes |
|---|---|---|
| `aliases` | `aliases` | free-text names for matching |
| `atc_code` | `atcCodes` | ATC code(s) |
| `category` | `drugClass` | drug class label |
| `contraindications` | `contraindications` | we use a typed `{type, token, note}` rule shape |
| `dosing.{child,adolescent}.mg_per_kg_range`, `max_daily_dose_mg` | `ageBands[].{mgPerKgMin,mgPerKgMax,maxDailyDoseMg}` | age-banded pediatric dosing |
| `indications`, `major_warnings`, `precautions` | — | not carried over |
| — | `interactions[].{token, atc, note}` | our structured interaction rules |

Deterministic alias resolution is also shared in spirit: their
`KnowledgeLoader.find_drug()` (exact match on generic/name/alias) ≈ our
`DrugReferenceService.findByQuery` / `lookupByToken`.

---

## 2. Architecture & stack

| | openmrs_chatbot | chartsearchai |
|---|---|---|
| Language / form | Python standalone chatbot | Java OpenMRS module |
| Integration | Separate service, talks to OpenMRS + APIs | Embedded in the chart-search answer pipeline |
| LLM | Ollama (local), **gated off** for most intents | Local LLM generates every answer; deterministic validation around it |
| Embeddings | `nomic-embed-text` (Ollama) over **clinical PDFs** | sentence/biomedical model over the **patient chart** (retrieval index) |
| Drug-info sources | local JSON **+ live RxNorm + openFDA** | local **pluggable dataset only** (curated JSON or WHO ATC export) |
| Control flow | intent classifier → branch to one source | single path: retrieve → inject → answer → validate |

---

## 3. How the drug KB is used

Both projects read the structured drug KB **deterministically** (not via RAG).
What they *do* with it diverges:

**openmrs_chatbot** — on the `MEDICATION_QUERY` route only:
- `agents/medication_controller.py` combines `KnowledgeLoader.find_drug()` +
  `utils/dose_calculator.py` `DoseCalculator.calculate_dose(weight, age, drug)` to
  compute a **recommended** pediatric dose.
- `agents/mcp_agent.py` wraps that with **RxNorm** (brand→generic normalization),
  **openFDA** (label data), and falls back to local JSON when the APIs are down.
- `utils/warning_engine.py` is a **template message formatter** (doctor vs patient
  wording) for allergy/vital/lab/milestone/vaccine alerts — it does *not* compute
  the alert; detection happens in the agents.

**chartsearchai** — on every query:
- `DrugReferenceInjector` injects matching reference entries into the serialized
  chart as **numbered, citable records** the LLM grounds on (question-driven by
  alias + order-driven by ATC, relevance-scoped — see ADR Decision 24). Dosing is
  age-gated.
- `DrugSafetyValidator` runs **after** the answer and *computes* the checks
  deterministically: it parses the dose the answer states and flags **overdose**
  vs the age-band maximum, flags **interactions** with active orders, and flags
  **contraindications** against allergies/conditions — both hand-authored rules and
  **ATC class** reasoning. Warnings render as non-blocking chips.

Net: they **calculate** a recommended dose from the KB; we **validate** the dose the
LLM stated. They **format** pre-detected warnings; we **detect** them.

---

## 4. RAG indexes PDFs — not the drug JSON

A point that's easy to get wrong: openmrs_chatbot's vector store
(`vectorstore/chroma.py`, two Chroma collections `doctor_knowledge_base` /
`patient_knowledge_base`, HNSW cosine) indexes **PDF documents** from
`knowledge_base/doctor/` and `knowledge_base/patient/` — e.g.
`WHO-MHP-HPS-EML-2023.03-eng.pdf` (WHO Essential Medicines List) and the CDC
milestone checklist — chunked with `RecursiveCharacterTextSplitter`
(1000/100), embedded via `nomic-embed-text`. `technical/init_kb.py` says so
outright: *"index PDF documents… after adding PDF files to knowledge_base/."*

The structured drug JSON is **never embedded** (a `grep` for the JSON files across
`vectorstore/` + `technical/` returns nothing). It is reached only by deterministic
`find_drug` + `DoseCalculator`.

chartsearchai does not RAG the drug KB either — drug matching is deterministic
(alias + ATC). Our only embeddings are over the **patient chart** for retrieval; we
do not index reference PDFs at all.

A nice parallel: their RAG ingests the **WHO Essential Medicines List as an
unstructured PDF**; we consume a WHO artifact too — the **ATC classification** — but
as **structured data** for deterministic class matching. Same provenance instinct,
opposite mechanism.

---

## 5. ATC codes: dead data there, load-bearing here

In openmrs_chatbot, `atc_code` is present in the JSON but **no `.py` file uses it**
in logic (drug interactions come from the RxNorm API; "duplicate therapy" appears
only as a printed checklist line in `agents/medication_response.py`, not computed).

In chartsearchai the ATC codes are central: `DrugReference.atcSubgroups()` (ATC
level-4 prefix) is the shared definition behind both the validator's **cross-
reactivity** + **duplicate-therapy** checks and the injector's relevance scoping,
and the `AtcDrugReferenceSource` consumes a WHO ATC export as a pluggable
classification source (ADR Decision 24). Documented boundary: ATC's tree does not
capture cross-*branch* cross-reactivity (aspirin `N02BA01` vs ibuprofen `M01AE01`),
which needs curated data.

---

## 6. Query flow

### openmrs_chatbot — intent-routed (`main.py` `process_query`)

```
query
 ├─ TwoLayerIntentClassifier: keywords → embeddings → LLM fallback (<0.75)
 ├─ fetch patient data from OpenMRS DB
 ├─ LLM gate (is_llm_allowed): blocked for data intents → DB-only
 ├─ direct-data fast path (vitals/labs straight from DB)
 └─ role pipeline, dispatched by intent:
      • MEDICATION_QUERY  → RxNorm normalize → find_drug (drug JSON) →
                            DoseCalculator → openFDA  [no RAG]
      • MILESTONE / PATIENT_RECORD / GENERAL → Chroma PDF RAG → LLM answer
      • VITALS / ALLERGY  → OpenMRS DB directly (LLM gated off)
 └─ allergy safety net: scan query+response → warning_engine template alert
```

The drug KB and the PDF RAG are reached by **mutually exclusive intents**: a
"what dose / is X safe?" question never touches the vector store, and a general
question never touches the drug JSON.

### chartsearchai — single-path (`LlmInferenceService`)

```
query
 ├─ retrieve patient-chart records (embeddings + hybrid scoring)
 ├─ DrugReferenceInjector: inject matching reference records (relevance-scoped)
 ├─ LLM generates ONE answer, citing chart + reference records
 ├─ grounding/verification pass over the citations
 └─ DrugSafetyValidator: deterministic overdose / interaction / contraindication
    (rule + ATC class) → non-blocking safety chips
```

No intent router, no LLM gate, no external APIs: the LLM always answers over the
chart, and the deterministic layers enrich (pre) and validate (post).

---

## 7. Summary

| Dimension | openmrs_chatbot | chartsearchai |
|---|---|---|
| Drug KB schema | origin | adapted from it |
| Drug KB access | deterministic `find_drug` + `DoseCalculator` | deterministic alias/ATC match |
| KB → LLM | RAG of **PDFs** (not the drug JSON) | inject drug records as **citable** chart lines |
| Dose handling | **calculate** recommended dose | **validate** the answer's stated dose (overdose) |
| Interactions | external **RxNorm API** | KB rules + **ATC duplicate-therapy** (computed) |
| ATC codes | stored, **unused in code** | **drive** cross-reactivity / duplicate therapy |
| Warnings | template formatter (detection elsewhere) | deterministic detector → chips |
| External APIs | RxNorm + openFDA | none (local pluggable dataset) |
| Control flow | intent classifier → branch; LLM gated | single retrieve→inject→answer→validate |
| Pluggable source | multiple JSON files + APIs | `DrugReferenceSource` adapter (JSON \| ATC), GP-selected |

## 8. What we kept vs. changed

- **Kept:** the KB data/schema and deterministic alias resolution.
- **Changed / added (ours):** inject-as-citable-records integrated with the chart
  citation + grounding pipeline; a deterministic post-answer validator (dose
  parsing, interaction, contraindication); **ATC level-4 class reasoning**
  (cross-reactivity + duplicate therapy); a **pluggable WHO-ATC source**;
  relevance-scoped order injection. We use **no external drug APIs** and do **not**
  RAG the drug KB.

See ADR [Decision 23](adr.md#decision-23-drug-reference-injection--post-answer-drug-safety-validation)
(the feature) and [Decision 24](adr.md#decision-24-drug-reference-as-a-pluggable-consumer-of-authoritative-datasets)
(pluggable consumer of authoritative datasets / the ATC class layer).
