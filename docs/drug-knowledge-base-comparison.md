# Drug knowledge base: the ChartSearchAI stack vs. openmrs_chatbot

**Purpose.** The drug-reference feature adapted its knowledge-base *data* from the
[anichiti/openmrs_chatbot](https://github.com/anichiti/openmrs_chatbot) project. This note records
what was actually shared and — more importantly — how each project *uses* the knowledge base, so
the lineage and the divergence are on the record rather than living only in chat history or memory.

**Where the feature lives now.** The drug-reference feature was originally built as a Java package
inside this module (`org.openmrs.module.chartsearchai.reference`). Once ChartSearchAI became a thin
relay to **med-agent-hub** (the hub owns retrieval, synthesis, grounding, and now drug safety), that
package was deleted and the feature was **ported to the hub** — a single deterministic module,
`med-agent-hub/server/drug_safety.py`. This document describes the feature as it exists in the hub;
file references are to that module unless noted. (The Java originals are recoverable from this
repo's git history at commit `65e3c08^` if the port's contract ever needs re-checking.)

**Provenance.** Compared against `openmrs_chatbot` at commit `2e723f8` (2026-05-12); the
ChartSearchAI-stack feature as ported to the hub (2026-07). File/line references are to those revisions.

---

## TL;DR

Same KB **data schema** — we adapted their `drug_knowledge_base.json`. Everything about how the KB is
*used* differs:

- **openmrs_chatbot** is a standalone Python **multi-agent chatbot**. It classifies each query by
  intent and routes it to *one* knowledge source: a deterministic drug-JSON lookup, a **PDF RAG**
  store (Chroma), live **RxNorm/FDA** APIs, or the OpenMRS DB — and it gates the LLM *off* for most
  intents ("pull from DB only").
- **The ChartSearchAI stack** (OpenMRS SPA → ChartSearchAI relay → med-agent-hub) runs a profile-
  composed path. The hub builds an evidence ledger from configured context sources (Querystore is
  optional), deterministically injects matching drug-reference records, fits whole records to the
  model window when necessary, generates one LLM answer, then runs deterministic **post-answer
  safety validation**.

Two consequences worth highlighting up front:
- Their RAG **does not index the drug KB** — it indexes clinical PDFs. The drug JSON is read
  deterministically. (We don't RAG the drug KB either.)
- Their **ATC codes are stored but never used in code**; ours drive class-based safety reasoning
  (cross-reactivity, duplicate therapy) and are consumable directly from a **WHO-ATC classification
  export** as a pluggable source.

---

## 1. Shared: the KB data schema (the part that was copied)

Their `data/drug_knowledge_base.json` entry shape is the ancestor of our curated dataset,
`med-agent-hub/server/drug_data/drug-reference.json`:

| Field (openmrs_chatbot) | Field (ours) | Notes |
|---|---|---|
| `aliases` | `aliases` | free-text names for matching |
| `atc_code` | `atcCodes` | ATC code(s) |
| `category` | `drugClass` | drug class label |
| `contraindications` | `contraindications` | we use a typed `{type, token, note}` rule shape |
| `dosing.{child,adolescent}.mg_per_kg_range`, `max_daily_dose_mg` | `ageBands[].{mgPerKgMin,mgPerKgMax,maxDailyDoseMg}` | age-banded pediatric dosing |
| `indications`, `major_warnings`, `precautions` | — | not carried over |
| — | `interactions[].{token, atc, note}` | our structured interaction rules |

Deterministic alias resolution is also shared in spirit: their `KnowledgeLoader.find_drug()` (exact
match on generic/name/alias) ≈ our `DrugReferenceDataset.find_by_query` / `lookup_by_token`.

---

## 2. Two pluggable sources (curated JSON + WHO-ATC)

The hub's drug-reference layer is source-agnostic (the Python port of the Java `DrugReferenceSource`
adapter, ADR Decision 24). One source is active at a time, selected deployment-wide:

| Source | Selector | Path | What it provides |
|---|---|---|---|
| **Curated JSON** (default) | `DRUG_SAFETY_SOURCE_FORMAT=json` (or unset) | bundled `server/drug_data/drug-reference.json` | full rules: aliases, ATC codes, age-banded dosing, interactions, contraindications |
| **WHO-ATC export** | `DRUG_SAFETY_SOURCE_FORMAT=atc` | operator-supplied via `DRUG_SAFETY_DATASET_PATH` | classification only: one entry per level-5 substance, `drugClass` from the nearest parent group; **no** per-entry rules |

The ATC parser (`_load_atc_entries`) reads a `<atcCode><whitespace><name>` all-levels export, emits
one entry per level-5 substance (a 7-char valid ATC code), and derives each substance's class from
the nearest parent group **present in the same dataset** (level 4 → 3 → 2). It is fail-safe: a
missing or unreadable dataset degrades to an empty list, so the feature stays an additive net that
never breaks the answer path. There is no bundled ATC file — the operator points at the WHO ATC/DDD
index (or an RxNorm/ATC crosswalk) they obtained.

---

## 3. How the drug KB is used

Both projects read the structured drug KB **deterministically** (not via RAG). What they *do* with
it diverges:

**openmrs_chatbot** — on the `MEDICATION_QUERY` route only:
- `agents/medication_controller.py` combines `KnowledgeLoader.find_drug()` +
  `utils/dose_calculator.py` `DoseCalculator.calculate_dose(weight, age, drug)` to compute a
  **recommended** pediatric dose.
- `agents/mcp_agent.py` wraps that with **RxNorm** (brand→generic normalization), **openFDA** (label
  data), and falls back to local JSON when the APIs are down.
- `utils/warning_engine.py` is a **template message formatter** (doctor vs patient wording) — it does
  *not* compute the alert; detection happens in the agents.

**The hub** (`server/drug_safety.py`) — on every product-profile query; all current product profiles
enable drug safety:
- `inject_drug_references(...)` injects matching reference entries into the retrieved chart as
  **numbered, citable records** the LLM grounds on (question-driven by alias + order-driven by ATC,
  relevance-scoped — see ADR Decision 24). Dosing is age-gated.
- `validate_answer(...)` runs **after** the answer and *computes* the checks deterministically: it
  parses the dose the answer states and flags **overdose** vs the age-band maximum, flags
  **interactions** with active orders, and flags **contraindications** against allergies/conditions —
  both hand-authored rules (from the curated JSON) and **ATC class** reasoning (`atc_subgroups()`,
  the level-4 prefix). Warnings ride the chat surfaces as non-blocking chips (`{type, drug, detail}`).

Net: they **calculate** a recommended dose from the KB; we **validate** the dose the LLM stated. They
**format** pre-detected warnings; we **detect** them.

---

## 4. RAG indexes PDFs — not the drug JSON

A point that's easy to get wrong: openmrs_chatbot's vector store (`vectorstore/chroma.py`, two Chroma
collections, HNSW cosine) indexes **PDF documents** — e.g. `WHO-MHP-HPS-EML-2023.03-eng.pdf` (WHO
Essential Medicines List) and the CDC milestone checklist — chunked with
`RecursiveCharacterTextSplitter` (1000/100), embedded via `nomic-embed-text`. The structured drug
JSON is **never embedded**; it is reached only by deterministic `find_drug` + `DoseCalculator`.

Our stack doesn't RAG the drug KB either — drug matching is deterministic (alias + ATC). Our only
semantic retrieval is over the **patient chart**, and it lives in **querystore** (the hub retrieves
the chart from querystore per turn); we do not index reference PDFs at all.

A nice parallel: their RAG ingests the **WHO Essential Medicines List as an unstructured PDF**; we
consume a WHO artifact too — the **ATC classification** — but as **structured data** for
deterministic class matching, either curated into our JSON or loaded directly from a WHO-ATC export
(§2). Same provenance instinct, opposite mechanism.

---

## 5. ATC codes: dead data there, load-bearing here

In openmrs_chatbot, `atc_code` is present in the JSON but **no `.py` file uses it** in logic (drug
interactions come from the RxNorm API; "duplicate therapy" appears only as a printed checklist line,
not computed).

In our stack the ATC codes are central: `DrugReferenceEntry.atc_subgroups()` (ATC level-4, 5-char
prefix) is the shared definition behind both the validator's **cross-reactivity** + **duplicate-
therapy** checks and the injector's relevance scoping, and the WHO-ATC source (§2) consumes a WHO ATC
export as a pluggable classification source. Documented boundary: ATC's tree does not capture
cross-*branch* cross-reactivity (aspirin `N02BA01` vs ibuprofen `M01AE01`), which needs curated data —
so an ATC-only deployment gets class-level warnings within a subgroup but not across branches.

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

The drug KB and the PDF RAG are reached by **mutually exclusive intents**.

### The ChartSearchAI stack — single path (SPA → relay → hub)

```
query
 ├─ hub builds a complete evidence ledger from the configured context source(s)
 ├─ small chart: retain all records; oversized chart: deterministic whole-record selection
 ├─ inject_drug_references: inject matching reference records (relevance-scoped)
 ├─ LLM generates ONE answer, citing chart + reference records
 ├─ grounding/entailment pass over the citations
 └─ validate_answer: deterministic overdose / interaction / contraindication
    (rule + ATC class) → non-blocking safety chips
```

No intent router, no LLM gate, no external APIs: the LLM answers over the selected evidence view,
while temporal and drug-safety layers use the complete ledger. Drug safety is required by current
product profiles; low-level experimental legs may configure it explicitly.

---

## 7. Summary

| Dimension | openmrs_chatbot | ChartSearchAI stack (hub) |
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
| Pluggable source | multiple JSON files + APIs | curated JSON **or** WHO-ATC export, `DRUG_SAFETY_SOURCE_FORMAT`-selected |

## 8. What we kept vs. changed

- **Kept:** the KB data/schema and deterministic alias resolution.
- **Changed / added (ours):** inject-as-citable-records integrated with the chart citation + grounding
  pipeline; a deterministic post-answer validator (dose parsing, interaction, contraindication);
  **ATC level-4 class reasoning** (cross-reactivity + duplicate therapy); a **pluggable WHO-ATC
  source**; relevance-scoped order injection. We use **no external drug APIs** and do **not** RAG the
  drug KB.
- **Moved:** the whole feature now lives in **med-agent-hub** (`server/drug_safety.py`), ported from
  the deleted chartsearchai Java `reference` package when ChartSearchAI became a thin hub relay.

See ADR [Decision 23](adr.md#decision-23-drug-reference-injection--post-answer-drug-safety-validation)
(the feature) and [Decision 24](adr.md#decision-24-drug-reference-as-a-pluggable-consumer-of-authoritative-datasets)
(pluggable consumer of authoritative datasets / the ATC class layer). These ADR entries record the
original Java design; the hub port preserves their contract (verified by the hub's drug-safety parity
tests, `med-agent-hub/tests/test_drug_safety*.py`).
