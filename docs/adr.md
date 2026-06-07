# Chart Search AI - Architectural Decisions

This document captures the architectural decisions made for the Chart Search AI module, including alternatives evaluated and the reasoning behind the chosen approaches.

## Table of Contents

- [Problem Statement](#problem-statement)
- [Decision 1: What value does an LLM add?](#decision-1-what-value-does-an-llm-add)
- [Decision 2: Overall architecture — RAG vs. alternatives](#decision-2-overall-architecture--rag-vs-alternatives)
- [Decision 3: Embedding approach — semantic search index](#decision-3-embedding-approach--semantic-search-index)
- [Decision 4: Concise text as LLM input format](#decision-4-concise-text-as-llm-input-format)
- [Decision 5: Embedding granularity](#decision-5-embedding-granularity)
- [Decision 6: Embedding model](#decision-6-embedding-model)
- [Decision 7: Vector storage — MySQL, not a vector database](#decision-7-vector-storage--mysql-not-a-vector-database)
  - [CQRS separation](#cqrs-separation)
- [Decision 8: Index population strategy](#decision-8-index-population-strategy)
- [Decision 9: Text serialization — ClinicalTextSerializer pattern](#decision-9-text-serialization--clinicaltextserializer-pattern)
- [Decision 10: Single LLM architecture with optional embedding pre-filter](#decision-10-single-llm-architecture-with-optional-embedding-pre-filter)
- [Decision 11: REST API and guardrails](#decision-11-rest-api-and-guardrails)
- [Decision 12: Concurrency model](#decision-12-concurrency-model)
- [Decision 13: Lucene BM25 as an alternative retrieval pipeline](#decision-13-lucene-bm25-as-an-alternative-retrieval-pipeline)
- [Decision 14: Elasticsearch hybrid search pipeline with RRF](#decision-14-elasticsearch-hybrid-search-pipeline-with-rrf)
- [Decision 15: In-process hybrid pipeline (Lucene BM25 + embedding kNN with RRF)](#decision-15-in-process-hybrid-pipeline-lucene-bm25--embedding-knn-with-rrf)
- [Decision 16: LangChain / LangChain4j not adopted](#decision-16-langchain--langchain4j-not-adopted)
- [Decision 17: Remote LLM backend support](#decision-17-remote-llm-backend-support)
- [Decision 18: Cross-encoder reranking stage (superseded)](#decision-18-cross-encoder-reranking-stage-superseded)
- [Decision 19: Retain all-MiniLM-L6-v2 as the embedding model](#decision-19-retain-all-minilm-l6-v2-as-the-embedding-model)
- [Decision 20: MedCPT dual-encoder as an alternative embedding model](#decision-20-medcpt-dual-encoder-as-an-alternative-embedding-model)
- [Decision 21: Concept-name re-ranking for subword-tokenized queries](#decision-21-concept-name-re-ranking-for-subword-tokenized-queries)
- [Decision 22: e5-base-v2 for the querystore-backed retrieval path](#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path)
- [Known limitations](#known-limitations)
- [Planned future work](#planned-future-work)

## Problem Statement

Clinicians using OpenMRS often see hundreds of patients daily with limited time per encounter. Finding specific information in a patient's chart — especially across years of records, unstructured notes, and multiple widget pages — is slow and error-prone. A Chart Search feature should help clinicians quickly find what they need by asking natural language questions about a patient's chart.

## Decision 1: What value does an LLM add?

### Analysis

For most chart search queries (~80%), the question maps directly to structured data lookups (e.g., "What are her current medications?" is just a database query). An LLM adds genuine value only for:

- **Natural language query parsing** (~80% of value): Translating "Has she ever had a bad reaction to penicillin?" into a search for allergy records related to penicillin-class drugs.
- **Unstructured text search** (~15%): Finding information in free-text notes that requires language comprehension, not just keyword matching.
- **Synthesis across records** (~5%): Interpreting trends across multiple values over time (e.g., "Is her diabetes getting better or worse?").

An LLM adds no value for structured data lookup, concept synonym matching (OpenMRS `ConceptService` already handles this), filtering/sorting, or display formatting.

### Decision

This analysis initially suggested using the LLM only as a fallback, with a deterministic primary search path. In practice, the single-LLM approach (Decision 10) proved simpler and more effective — all queries go through the LLM, which handles both the "easy" structured lookups and the "hard" synthesis cases uniformly. The embedding pre-filter narrows the input, and the LLM does the rest. The value analysis above still holds: the LLM earns its cost primarily through natural language understanding and cross-record synthesis.

## Decision 2: Overall architecture — RAG vs. alternatives

### Options evaluated

#### Option A: Full FHIR bundle to LLM
Send a complete FHIR bundle with all patient resources to an LLM for processing.

**Rejected because:**
- A patient with 5 years of visits could have thousands of resources, producing 500K-2M tokens in FHIR JSON — far exceeding even 128K-token context windows, and massively wasteful due to FHIR's verbose structure (see Decision 4).
- LLMs lose information buried in the middle of long contexts ("lost in the middle" problem).
- Processing a massive bundle on a local model would take minutes, not seconds.
- Maximizes hallucination risk — the model sees lots of clinical terminology and confidently connects dots that don't exist.

#### Option B: Fine-tuned local model (no retrieval)
Fine-tune a small model to generate SQL or API calls from natural language.

**Rejected because:**
- Requires substantial labeled training data (question → correct API call pairs) that doesn't exist yet.
- SQL/API generation errors are silent and dangerous.
- Must re-fine-tune when schema or forms change.
- Training data problem alone makes this impractical for v1.

#### Option C: Traditional search (no LLM at all)
Full-text search index (Lucene/Solr/Elasticsearch) over patient data.

**Now implemented as an alternative retrieval pipeline.** Solves 70% of the problem with 20% of the complexity. No hallucination risk from retrieval errors, no extra model files required, works offline. Weakness: no semantic understanding — relies on lexical matching with stemming. Implemented using Apache Lucene 8.11.2 (already on the classpath via Hibernate Search) with `EnglishAnalyzer` for Porter stemming. Selectable via the `chartsearchai.retrieval.pipeline` global property. See Decision 13 for details. An Elasticsearch pipeline (Decision 14) addresses the semantic gap by combining BM25 text search with kNN vector search via Reciprocal Rank Fusion.

#### Option D: Agent/tool-use pattern
Give the LLM access to OpenMRS APIs as tools and let it autonomously decide what to call.

**Deferred to v2+.** Architecturally elegant but demands more capable models than the deployment environment can support. Small local models (2-8B) are weak at tool use and multi-step reasoning. Latency from multiple sequential LLM → API → LLM loops is problematic in a 90-second encounter.

#### Option E: Pre-computed summaries (batch processing)
Generate patient summaries offline ahead of time. Search summaries at query time.

**Deferred.** Good for common queries (active meds, allergies, problem list). Weakness: stale data, doesn't handle unexpected/novel questions. Best combined with real-time retrieval.

#### Option F: RAG (Retrieval Augmented Generation)
Retrieve relevant records first using deterministic search, then use the LLM only for query understanding and response formatting.

**Strengths:**
- Retrieval is deterministic and auditable — every piece of data has a traceable source.
- The LLM only sees data explicitly provided — it cannot hallucinate about records it was never given. It can still misinterpret or over-infer from the provided data (see Hallucination risk comparison in Decision 10), but the hallucination surface area is smaller than giving it the full chart.
- Works with small local models since query parsing and response synthesis are short-context tasks.

### Decision

The initial plan was full RAG with a deterministic retrieval layer feeding a small subset of records to the LLM. Decision 10 refined this into the current architecture: a **single LLM approach** where all patient records are sent to the LLM (or narrowed by an optional embedding pre-filter). The two-step structure remains — retrieval then synthesis — but the LLM handles all queries, not just "hard" ones. See Decision 10 for the full rationale.

## Decision 3: Embedding approach — semantic search index

### Options evaluated for retrieval

#### Option A: Targeted queries with manual concept mapping
Map each query type to specific OpenMRS resource types and concept codes.

**Weakness:** Requires manually mapping every possible query pattern to the right resources. Misses things you wouldn't think to query — e.g., a free-text visit note mentioning "mother had breast cancer."

#### Option B: Concept graph traversal
Use the OpenMRS concept dictionary as a knowledge graph. Map query terms to SNOMED concepts, traverse the hierarchy, query matching records.

**Deferred.** Fast (milliseconds), deterministic, leverages existing concept dictionary. Weakness: only works for structured/coded data, misses free-text entirely. Could complement embedding search in a future version.

#### Option C: Semantic search index with embeddings — CHOSEN
Pre-index all patient data with vector embeddings. At query time, find relevant records by embedding similarity.

**Chosen because:**
- No manual mapping needed — similarity search catches things you wouldn't think to query.
- Works with both structured and unstructured data.
- The embedding model is tiny (~90MB, runs on CPU in milliseconds).
- Query-time cost is just a vector similarity search — very fast.
- Per-patient search space is small enough (typically <2000 records) for brute-force in-memory cosine similarity.

#### Option D: Clinical concept extraction pipeline (NLP at write time)
Use rule-based NLP (cTAKES, MedSpaCy) to extract structured facts from all data at write time.

**Deferred.** Zero query-time AI cost, works on unstructured text. Weakness: extraction pipeline needs tuning per site, adds processing to the write path.

#### Option E: Map-reduce over chart segments
Split patient chart into time-based segments, classify each for relevance, only send relevant segments to LLM.

**Deferred to v2+.** Handles arbitrarily large charts but adds infrastructure complexity.

### Decision

Semantic search index as the primary retrieval mechanism. Concept graph traversal is deferred to future work as a potential complement for structured data lookups.

> **Note:** Later decisions combine this embedding approach with keyword search for better recall. Decision 14 adds Elasticsearch hybrid search (BM25 + kNN via RRF), and Decision 15 provides the same hybrid approach entirely in-process with no external dependencies.

## Decision 4: Concise text as LLM input format

### Analysis

Standard serialization formats (FHIR JSON, full OpenMRS domain objects) are poor formats for LLM context windows:

- **Extremely verbose**: A single blood pressure observation is ~800 tokens in FHIR JSON vs. ~15 tokens in compressed form. On a small model with 4-8K context, this matters enormously.
- **Deeply nested**: `coding` inside `code` inside `component` inside `Observation`. Small LLMs are worse at extracting information from nested structures.
- **Redundant metadata**: System URIs, references, profiles waste context tokens.

### Decision

Retrieve data via OpenMRS service APIs (ObsService, ConditionService, PatientService, OrderService, DiagnosisService, ProgramWorkflowService, MedicationDispenseService) and convert records into flat, concise clinical text using `ClinicalTextSerializer` implementations. This gives ~10x token efficiency while preserving clinical meaning.

Example:
```
FHIR JSON: ~800 tokens
Serialized: "Systolic Blood Pressure: 120 mmHg (ABNORMAL)"  ~10 tokens
```

## Decision 5: Embedding granularity

### Options

| Granularity | Pros | Cons |
|---|---|---|
| Individual record | Precise retrieval, fine-grained citations | Many embeddings per patient, records in isolation lose context |
| Per encounter | Groups related data naturally, fewer embeddings | Large encounters produce long text, less precise |
| Per clinical category | Matches how clinicians think | Arbitrary groupings, large text chunks |

### Decision

Embed at the **individual record level**. Each record is serialized to concise clinical text (e.g., `"Systolic Blood Pressure: 120 mmHg (ABNORMAL)"`). This keeps embeddings small and precise while giving the similarity search enough context to work with.

## Decision 6: Embedding model

### Decision

Semantic vectors via **all-MiniLM-L6-v2** running in-process through ONNX Runtime. ~90MB model file, runs on CPU, no GPU needed. Produces 384-dimensional vectors. Requires two files configured via `chartsearchai.embedding.modelFilePath` and `chartsearchai.embedding.vocabFilePath`. Captures semantic meaning — effective for clinical queries where synonyms and related concepts matter (e.g., "hypertension" and "high blood pressure" are recognized as related). Embedding dimensions are auto-detected from the model output on first use, so models with different dimensions (e.g., 768-dim pubmedbert-base-embeddings) work without code changes.

A term-frequency hashing approach was considered as a simpler alternative (no model file needed, keyword-overlap retrieval). It was rejected because it cannot capture semantic similarity — for a clinical question like "any infections?", it would find records containing the word "infection" but miss "tuberculosis", "malaria", or "UTI". This defeats the purpose of pre-filtering, since the LLM with the full chart would catch all of these.

## Decision 7: Vector storage — MySQL, not a vector database

### Analysis

MySQL does not natively support vector embeddings (native `VECTOR` type was added in MySQL 9.0+, but OpenMRS deployments typically run MySQL 5.7 or 8.x).

However, a vector database is unnecessary for this use case because:
- Search is **per-patient**, not across all patients
- A patient with 2000 records means 2000 vector comparisons — trivial in Java (microseconds)
- Embeddings are stored as BLOBs (~1.5KB per record for 384 dimensions, ~3KB for 768 dimensions)

### Why not a vector database?

Vector databases (pgvector, Milvus, Pinecone, etc.) use approximate nearest neighbor (ANN) algorithms to efficiently search across millions or billions of vectors. This module searches at most a few thousand vectors per patient — brute-force cosine similarity in Java completes in microseconds, so there is no scale problem for ANN to solve.

Adding a vector database would introduce extra infrastructure to install, configure, and maintain in low-resource settings that already struggle with MySQL + Tomcat — with no performance benefit. It would also return approximate results instead of exact ones, adding a source of retrieval error for no gain. The LLM inference step takes 15–45 seconds; the similarity search is never the bottleneck.

### Decision

Store embeddings as `MEDIUMBLOB` in a regular MySQL table (`chartsearchai_embedding`), indexed by `patient_id`. Load a patient's embeddings into memory and compute cosine similarity in Java. Zero new infrastructure.

The `UNIQUE KEY (resource_type, resource_uuid)` constraint prevents duplicate embeddings and enables upsert on re-index.

### CQRS separation

The module applies the CQRS (Command Query Responsibility Segregation) principle in relation to OpenMRS patient data. The transactional store (OpenMRS's normalized relational tables — `obs`, `orders`, `conditions`, etc.) serves clinical workflows and CRUD operations. The query stores are separate, denormalized projections optimized for search:

- **Embedding store** (`chartsearchai_embedding` table) — pre-serialized text and embedding vectors for cosine similarity search
- **Lucene store** (on-disk index) — BM25 full-text index with English stemming, using the same serialized text
- **Elasticsearch store** (external service) — combines BM25 text search with kNN dense vector search via Reciprocal Rank Fusion

Only one query store is active at a time, selected via the `chartsearchai.retrieval.pipeline` global property. AOP advice hooks (`PatientDataIndexingAdvice`, `ObsIndexingAdvice`, `EncounterIndexingAdvice`) on clinical services act as the event bridge, triggering projection rebuilds in the active query store to keep it eventually consistent with the transactional source. The module never writes to OpenMRS clinical tables.

## Decision 8: Index population strategy

### Decision

Three complementary strategies ensure embeddings stay current:

- **On-demand**: When a clinician queries a patient's chart for the first time and no embeddings exist, `LlmInferenceService` triggers `EmbeddingIndexer.indexPatient()` before running the similarity search. This means embeddings are created lazily — no setup required.
- **Incremental via AOP**: After-returning advice on eight OpenMRS services triggers re-indexing when clinical data changes. Most services use a **delete-and-recompute** strategy: when any record changes, all embeddings for that patient are deleted and recomputed from scratch. This is simpler than tracking which specific embedding corresponds to which record, and guarantees consistency — there is no risk of stale or orphaned embeddings. The cost is re-embedding unchanged records, but this is acceptable because: (a) embedding computation is fast (~50–200ms per patient), (b) most patients have hundreds, not thousands, of records, and (c) the AOP hooks fire on clinical data saves, which happen infrequently relative to reads. A future incremental approach (see Planned future work) would avoid this redundancy for patients with very large charts.

  The one exception is `EncounterService`, which uses an **incremental** strategy: it upserts only the encounter's obs and diagnoses rather than re-indexing the entire patient. This is a pragmatic optimization because encounters are the most frequent write path (every clinical visit creates one), and an encounter's obs/diagnoses are self-contained enough to update in isolation.

  The advised services are:
  - `EncounterService` — incremental encounter indexing (upserts only the encounter's obs and diagnoses)
  - `ObsService` — full patient re-index on save/void/unvoid/purge of standalone observations
  - `ConditionService` — full patient re-index on save/void/unvoid/purge
  - `DiagnosisService` — full patient re-index on save/void/unvoid/purge
  - `PatientService` — full patient re-index on allergy changes (`saveAllergy`, `setAllergies`, `removeAllergy`, `voidAllergy` — these methods live on `PatientService` in OpenMRS 2.8.x, not on a separate AllergyService); on `mergePatients`, re-indexes the preferred patient and deletes the non-preferred patient's embeddings
  - `OrderService` — full patient re-index on save/saveRetrospective/void/unvoid/purge/discontinue
  - `ProgramWorkflowService` — full patient re-index on save/void/unvoid/purge of program enrollments
  - `MedicationDispenseService` — full patient re-index on save/void/unvoid/delete of medication dispenses
  All AOP advice classes coordinate across pipelines via `IndexingHelper`, which triggers re-indexing in whichever secondary pipelines are active (Lucene, Elasticsearch) in addition to the embedding index. This ensures all active query stores stay consistent regardless of which pipeline is selected — a data change triggers updates to every store that needs it.

- **Backfill**: A one-time scheduled task (`EmbeddingIndexTask`) indexes all patients that don't yet have embeddings. Handles initial population when the module is installed on a system with existing data. Skips already-indexed patients, so it is safe to re-run and picks up where it left off if stopped. Admins trigger it from the scheduler UI; it does not run automatically.

## Decision 9: Text serialization — ClinicalTextSerializer pattern

### Decision

A generic `ClinicalTextSerializer<T>` interface with one implementation per OpenMRS resource type:

| Serializer | Output example |
|---|---|
| `ObsTextSerializer` | `"Systolic Blood Pressure: 120 mmHg (ABNORMAL). Note: Taken after exercise"` |
| `ConditionTextSerializer` | `"Condition: Type 2 Diabetes Mellitus. Status: ACTIVE. Verification: CONFIRMED"` |
| `AllergyTextSerializer` | `"Allergy: Penicillin (drug allergen). Severity: Severe. Reactions: Anaphylaxis, Rash"` |
| `DiagnosisTextSerializer` | `"Diagnosis: Malaria. Certainty: CONFIRMED. Rank: Primary"` |
| `OrderTextSerializer` | `"Drug order: Metformin 500mg. Dose: 1.0 Tablet(s) Oral twice daily. Duration: 30 Day(s). Action: NEW. Urgency: ROUTINE"` |
| `PatientProgramTextSerializer` | `"Program: HIV Treatment. Enrolled: 2024-01-15. Status: Active. Current state: On ART"` |
| `MedicationDispenseTextSerializer` | `"Dispensed: Metformin 500mg. Status: Completed. Quantity: 30 Tablet(s). Dose: 1 Tablet(s) Oral twice daily"` |

Key design choices:
- The **record date** (when it was observed/created) is not produced by the serializer itself. Instead, `PatientChartSerializer` prepends it as a citation label when constructing the LLM prompt (e.g., `[1] (2025-10-30) Systolic Blood Pressure: 120 mmHg`). This means the **LLM does see every record's date** — it is just added at the prompt assembly level rather than the serializer level. The date is also included in the API response's `references` array for the UI to display. Records are sorted most-recent-first, giving the LLM a positional recency signal in addition to the explicit date.

  The date is excluded from the serializer because **the same serialized text is used for both embedding and LLM input**. `EmbeddingIndexer` passes `record.getText()` directly to the embedding model. If the serializer included the date, then `"(2024-06-15) Systolic Blood Pressure: 120 mmHg"` and `"(2025-10-30) Systolic Blood Pressure: 120 mmHg"` would produce different embedding vectors despite being clinically identical observations. The date text would pollute the semantic similarity — a query like "blood pressure" would get slightly different similarity scores for the same reading depending on when it was recorded. By keeping dates out of the serialized text, embeddings reflect pure clinical content, and the date is added only at prompt assembly time when it is needed for the LLM.

  This distinction matters because the embedding model and the LLM use text differently. The embedding model computes a fixed vector where every token influences the math — date tokens shift the vector away from the pure clinical meaning. The LLM, by contrast, *reads and reasons* over the text — it can use the date to answer "when was the last blood pressure taken?" or simply ignore it when the date is irrelevant. Dates pollute embedding vectors but enrich LLM input.

  Note that small LLMs (1.5B–14B) are unreliable at date arithmetic — even 7B models struggle with "How many days between March 15 and June 2?" or "Was this before or after that?" This improves around 13B+ parameters but remains unreliable until much larger models. The dates are still included because they are clinically important (e.g., "when was the last blood pressure taken?"), but users should be aware that date-based reasoning may be inaccurate.

  In addition to the citation label date, serializers include **clinically significant dates within a record** when they represent facts distinct from the record date:
  - `ConditionTextSerializer`: end date (`Resolved: 2023-02-01`) — when a condition resolved is a clinical fact distinct from when it was recorded.
  - `OrderTextSerializer`: date stopped (`Stopped: 2024-06-20`) — when an order was discontinued.
  - `MedicationDispenseTextSerializer`: date handed over (`Handed over: 2025-01-10`) — when the patient actually received the medication.
  - `PatientProgramTextSerializer`: enrollment and completion dates (`Enrolled: 2024-01-15`, `Completed: 2023-01-15`) — both are intrinsic clinical facts about the enrollment.
- **Concept descriptions are intentionally omitted** from most serializers (`ConditionTextSerializer`, `DiagnosisTextSerializer`, `OrderTextSerializer`, `AllergyTextSerializer`, `ObsTextSerializer`, `MedicationDispenseTextSerializer`). OpenMRS concept descriptions can be very verbose (e.g., Malaria: "A protozoan disease caused by four species of the genus PLASMODIUM…" — 70+ words). Appending these to short clinical records like `"Condition: Malaria. Status: ACTIVE"` would make the description dominate the embedding vector, pulling it toward textbook biology rather than the clinical fact that the patient has malaria. Condition names, drug names, and allergen names are already clinically meaningful terms that embedding models handle well. The exception is `PatientProgramTextSerializer`, which does include `Program.getDescription()` because program names are often opaque acronyms (PMTCT, ART, TB-DOTS) that are meaningless to the embedding model without expansion.
- Voided and retired records are excluded by relying on OpenMRS service methods, not by checking voided flags in serializers. For example, `getObservationsByPerson()` returns only non-voided obs, and `getAllergies()` returns only non-voided allergies. This keeps the serializers focused on text formatting and avoids duplicating filtering logic that the platform already handles correctly.
- Both active and inactive conditions are loaded via `getAllConditions()`, not just active ones. This ensures resolved conditions (e.g., past malaria, resolved pneumonia) are visible to the LLM. Without inactive conditions, a clinician asking "Has this patient ever had malaria?" would get "No relevant information found" even if a resolved malaria condition exists — a wrong answer. The `ConditionTextSerializer` distinguishes between them via the `clinicalStatus` field (e.g., `Status: ACTIVE` vs `Status: INACTIVE`), so the LLM can differentiate when answering questions about current vs historical conditions. The token cost is modest — most patients have 5-20 conditions, and each serializes to ~10-15 tokens.
- `ObsTextSerializer` flattens obs groups into a single text record rather than serializing each group member as a separate record. This preserves clinical context — a blood pressure group with systolic and diastolic members stays together as one record (e.g., `"Blood Pressure: Systolic 120 mmHg; Diastolic 80 mmHg"`) rather than being split into two unrelated records that lose their association. Group members are delimited by semicolons. Nested groups (groups within groups) are flattened recursively.
- `ObsTextSerializer` includes concept name, value (coded/numeric/text/datetime/drug), value modifier (e.g., `>`, `<` for lab results like ">200 copies/mL"), units, interpretation, comments, and flattened group members. Omitted: reference ranges (`Obs.referenceRange` added in OpenMRS 2.7.0, and `ConceptNumeric` hi/low normal/critical/absolute thresholds) — including ranges adds ~8 tokens per numeric obs, and a patient with 50+ lab values would add 400+ tokens just for ranges, working against the goal of concise text for small LLMs with limited context windows; the LLM's role is to find and cite relevant records, not interpret lab abnormality, and clinicians can look up reference ranges outside the LLM. Also omitted: location (administrative), accession number (lab logistics), order linkage (structural reference, not clinical text), and status (PRELIMINARY/FINAL/AMENDED — adds tokens for a distinction small LLMs are unlikely to reason about meaningfully).
- Units are extracted from `ConceptNumeric`, not `Concept` (which has no `getUnits()` in OpenMRS 2.8.x).
- `ConditionTextSerializer` includes condition name, clinical status, verification status, additional detail, end date, and end reason. Omitted: onset date (handled by record sort position, same reasoning as other dates). Note: onset date is the most debatable omission across all serializers — unlike transactional obs dates, onset date is an intrinsic clinical fact ("When did diabetes start?"), similar to why PatientProgram enrollment dates ARE included. The difference is that conditions are sorted by onset date so position already conveys recency, and the actual date is available in the API response's references array. If future use cases require explicit onset dates (e.g., larger models that can reason about durations), this is the first field to reconsider.
- `AllergyTextSerializer` includes allergen name, allergen type, severity, reactions, and comments. No clinically meaningful fields are omitted — allergy serialization is comprehensive.
- `DiagnosisTextSerializer` includes diagnosis name, certainty, and rank. Omitted: linked condition (structural reference — the condition itself is serialized separately), and custom attributes (deployment-specific, may be empty).
- `OrderTextSerializer` handles the full Order hierarchy: base `Order`, `DrugOrder`, and `ServiceOrder` (which includes `TestOrder` and `ReferralOrder`). Base orders include concept name, action, urgency, instructions, reason, and date stopped. Drug orders additionally include drug name (coded or non-coded), dose/units, route, frequency, duration/units, quantity/units, as-needed flag with condition, and dosing instructions. Service/test/referral orders additionally include laterality (LEFT/RIGHT/BILATERAL — critical for imaging and procedures, e.g., "X-Ray Left Knee" vs "X-Ray Knee"), specimen source (e.g., "Venous blood"), and clinical history (free-text context for the order). Omitted across all order types: orderer (who placed the order — administrative), care setting (inpatient/outpatient — contextual metadata), fulfiller status/comments (pharmacy workflow, not prescription content), commentToFulfiller (could carry clinical context for the fulfiller, but overlaps with `instructions` which is already serialized and is primarily fulfillment-workflow-oriented), refills/brand name/dispense-as-written (pharmacy logistics). Omitted from ServiceOrder: frequency/numberOfRepeats (scheduling logistics), location (administrative).
- `PatientProgramTextSerializer` includes program name, enrollment/completion dates, active status, outcome, and current workflow state (via `getCurrentState(null)`). Only the current state is serialized; historical state transitions (e.g., "First Line → Second Line") are omitted. Including them would add ~15-20 tokens per transition (e.g., `States: First Line (2023-01-15 to 2024-06-01), Second Line (2024-06-01 to present)`), and most programs have 1-3 transitions. This is a potential future enhancement for questions about treatment changes (e.g., "Why was the patient's ARV regimen changed?"), but the token cost compounds across multiple program enrollments. Location is omitted — it is administrative metadata rarely part of a clinical question about a program enrollment.
- `MedicationDispenseTextSerializer` includes drug name, status (completed/declined/cancelled), quantity/units, dose/units/route/frequency, dosing instructions, status reason, substitution flag/type/reason, and date handed over. Status is clinically critical — a declined or cancelled dispense means the patient did NOT receive the medication, which changes the clinical picture entirely. Substitution details (type and reason) are included because a generic substitution for cost reasons is clinically different from a therapeutic substitution for a drug interaction. Omitted: date prepared (pharmacy workflow detail, not clinical content).

### Serialization format

Records are serialized as labeled plain text (e.g., `Condition: Diabetes. Status: ACTIVE`). This was chosen over structured formats for token efficiency:

| Format | Example | Tokens (approx.) |
|--------|---------|-------------------|
| **Plain text (chosen)** | `Condition: Diabetes. Status: ACTIVE` | ~8 |
| JSON | `{"type":"condition","name":"Diabetes","status":"ACTIVE"}` | ~18 |
| FHIR JSON | `{"resourceType":"Condition","code":{"text":"Diabetes"},"clinicalStatus":{"coding":[{"code":"active"}]}}` | ~30+ |
| XML | `<condition><name>Diabetes</name><status>ACTIVE</status></condition>` | ~16 |

With 10 records per query and potentially hundreds of patients per day, the token savings compound. Plain text also reads naturally, which helps smaller LLMs that perform better with human-readable input than structured formats. Field labels (e.g., "Status:", "Severity:") provide enough structure for the LLM to extract information without the overhead of delimiters, braces, or tags.

### Serialized fields per record type

Each record type is serialized into a concise text string. The fields below are chosen for clinical value while minimizing token count.

#### Obs (observations, vitals, lab results)

**Included fields:** concept class prefix (e.g., "Test — ", "Assessment — " when available), concept name, value (coded/numeric/text/datetime/drug), units, value modifier (e.g., "<", ">"), interpretation (NORMAL, ABNORMAL), comment, group members (flattened). The concept class "Question" is mapped to "Assessment" to avoid collision with the "Question:" separator in the LLM prompt.

**Excluded fields:** reference range (not exposed in OpenMRS 2.8.x API), linked order (serialized separately as its own record), obs datetime (already in the citation date label)

Examples:
```
[1] (2025-10-30) Systolic Blood Pressure: 120 mmHg (ABNORMAL). Note: Taken after exercise
[2] (2025-10-30) Blood Panel: Hemoglobin: 12.5 g/dL; White Blood Cells: 8000 cells/uL (NORMAL)
```

#### Condition

**Included fields:** condition name, clinical status (ACTIVE/INACTIVE), verification status (CONFIRMED, etc.), additional detail, end date, end reason

**Excluded fields:** onset date (already in the citation date label)

Examples:
```
[3] (2018-03-10) Condition: Type 2 Diabetes Mellitus. Status: ACTIVE. Verification: CONFIRMED.
    Detail: Stage 3, GFR 45 mL/min
[4] (2023-01-15) Condition: Malaria. Status: INACTIVE. Resolved: 2023-02-01 (Treatment completed)
```

#### Diagnosis

**Included fields:** diagnosis name, certainty (CONFIRMED/PROVISIONAL), rank (Primary/Secondary)

**Excluded fields:** linked condition (serialized separately)

Example:
```
[5] (2025-06-29) Diagnosis: Tuberculosis. Certainty: CONFIRMED. Rank: Secondary
```

#### Allergy

**Included fields:** allergen name, allergen type (drug allergen/food allergen/environmental allergen), severity, reactions, comments

Example:
```
[6] (2024-12-29) Allergy: Penicillin (drug allergen). Severity: Severe. Reactions: Anaphylaxis, Rash.
    Comments: Confirmed by allergist
```

#### Order (drug orders, test orders, referral orders)

**Included fields:** concept name, action (NEW/REVISE/DISCONTINUE/RENEW), urgency (ROUTINE/STAT), instructions, order reason, date stopped. Drug orders additionally: drug name, dose/units/route/frequency, duration/units, quantity/units, as-needed flag and condition, dosing instructions. Service/test/referral orders additionally: laterality, specimen source, clinical history.

**Excluded fields:** number of refills (pharmacy detail), brand name (drug name already captured), dispense-as-written flag (pharmacy detail), care setting — inpatient/outpatient (adds tokens to every order for marginal value), scheduled date (only relevant for rare ON_SCHEDULED_DATE urgency), number of repeats (rarely relevant)

Examples:
```
[7]  (2025-01-10) Drug order: Metformin 500mg. Dose: 1.0 Tablet(s) Oral twice daily.
     Duration: 30 Day(s). Quantity: 60.0 Tablet(s). Action: NEW. Urgency: ROUTINE
[8]  (2025-03-15) Drug order: Ibuprofen 400mg. Dose: 1.0 Tablet(s) Oral.
     As needed (for pain). Action: NEW. Urgency: ROUTINE
[9]  (2025-06-29) Test order: X-Ray Chest. Laterality: LEFT.
     Clinical history: Persistent cough for 3 weeks. Action: NEW. Urgency: STAT
[10] (2025-04-01) Drug order: Lisinopril 10mg. Action: DISCONTINUE. Urgency: ROUTINE.
     Reason: Persistent dry cough. Stopped: 2025-04-01
```

#### Medication dispense

**Included fields:** drug name, status (completed/declined/cancelled), quantity/units, dose/units/route/frequency, dosing instructions, status reason, substitution flag/type/reason, date handed over

**Excluded fields:** date prepared (pharmacy workflow detail)

Examples:
```
[11] (2025-01-10) Dispensed: Metformin 500mg. Status: Completed.
     Quantity: 60.0 Tablet(s). Dose: 1.0 Tablet(s) Oral twice daily.
     Handed over: 2025-01-10
[12] (2025-01-10) Dispensed: Metformin 500mg. Status: Completed.
     Status reason: Out of stock. Substituted: Generic equivalent.
     Substitution reason: Cost. Handed over: 2025-01-10
[13] (2025-02-15) Dispensed: Amoxicillin 250mg. Status: Declined.
     Status reason: Patient refused
```

#### Patient program

**Included fields:** program name, enrollment date, completion date, active status, outcome, current state

**Excluded fields:** location (where enrolled — marginal for clinical queries), program attributes (implementation-specific, unknown content)

Examples:
```
[14] (2024-01-15) Program: HIV Treatment. Enrolled: 2024-01-15. Status: Active.
     Current state: On ART
[15] (2022-06-01) Program: TB Treatment. Enrolled: 2022-06-01.
     Completed: 2023-01-15. Outcome: Treatment completed
```

### Medical imaging data (X-rays, scans, etc.)

The default Gemma 4 E4B model and the medical-fine-tuned alternative MedGemma 1.5 4B both support multimodal input (text + images), but the module currently uses them for text-only inference. Gemma 3n E4B also supports image and audio input. Larger multimodal variants (e.g. Llama 3.2 11B and 90B) are too large for CPU inference in low-resource settings.

#### Current approach: rely on text reports

For v1, the module relies on the text reports that accompany imaging studies. In OpenMRS, imaging results typically have an associated obs with the radiologist's interpretation (e.g., `"Obs: Chest X-ray findings: bilateral infiltrates consistent with pneumonia"`). This text is already captured by `ObsTextSerializer` and flows through the existing pipeline — the embedding pre-filter can match it by similarity, and the LLM can reason over it.

#### Future options for direct image interpretation

These require either hardware beyond current low-resource constraints or an external service:

- **Multimodal LLM (Llama 3.2 11B/90B)**: Can interpret images alongside text but requires GPU or significantly more RAM than available in target deployments.
- **Specialized medical imaging models**: Small models trained for specific tasks (e.g., CheXNet for chest X-ray classification). Each covers only one type of image, so multiple models would be needed, adding significant complexity and storage requirements.
- **Cloud API**: Offload image interpretation to a cloud-hosted multimodal model. Introduces external dependency, latency, cost, and data privacy concerns that conflict with the self-contained, offline-capable design goals.
- **OCR for paper forms**: Convert photos of handwritten or printed paper forms to text at write time. The extracted text then flows through the existing serializer pipeline. This is more feasible than general medical image interpretation and addresses a common need in low-resource settings where paper forms are digitized by photographing them.

Direct image interpretation is deferred to future work. The default Gemma 4 E4B model and MedGemma 1.5 4B both support multimodal input (medical images + text), and the embedded llama-server already exposes multimodal inference via the OpenAI-compatible chat completions API (using libmtmd under the hood). Both the local and remote engines speak the same chat-completions protocol, so the remaining work is constructing multimodal content arrays (text + base64 image blocks) for complex observations rather than any new transport. See [Planned future work](#planned-future-work) for the implementation outline.

### Resource type coverage analysis

The seven resource types above (Obs, Condition, Allergy, Diagnosis, Order, PatientProgram, MedicationDispense) were chosen after a systematic review of every patient-facing domain class in OpenMRS core 2.8.0. The following data types were considered and intentionally excluded:

| Data type | Reason for exclusion |
|-----------|---------------------|
| Visit | Pure metadata grouper for encounters. Clinical content lives in the encounters' obs and diagnoses, which are already embedded. |
| Encounter | Container for obs and diagnoses, which are already captured individually. Encounter type (e.g., "Admission") is implicit in the obs recorded during that encounter. |
| PersonAttribute | Deployment-specific custom fields (phone, occupation, next of kin). Too variable to serialize generically — may be empty or administrative. |
| Patient demographics | Not embedded as individual records, but age and gender are included as a header line in the LLM prompt (e.g., `Patient: 45-year-old Male`). This gives the LLM context for age- and sex-dependent clinical reasoning without inflating the record count or embedding index. For example: blood pressure 130/85 is concerning for a 25-year-old but unremarkable for a 75-year-old; cancer screening recommendations depend on sex and age (cervical vs prostate); and medication dosing may vary by age. |
| Relationship | Administrative (e.g., "Mother of Patient X", "Emergency contact"), not clinical. |
| OrderGroup | Groups related orders (e.g., chemotherapy regimen). The individual orders within the group are already serialized; the group itself is structural. |
| VisitAttribute | Deployment-specific custom fields on visits. Too variable to serialize generically. |
| PatientIdentifier | Administrative identifiers, not clinical content. |

Cross-checked against FHIR clinical resources to verify completeness:

| FHIR resource | OpenMRS mapping | Status |
|---------------|----------------|--------|
| Observation | Obs | Embedded |
| Condition | Condition | Embedded |
| AllergyIntolerance | Allergy | Embedded |
| MedicationRequest | DrugOrder | Embedded |
| MedicationDispense | MedicationDispense | Embedded |
| ServiceRequest | ServiceOrder / TestOrder / ReferralOrder | Embedded |
| DiagnosticReport | Obs (text reports from imaging/labs) | Embedded |
| EpisodeOfCare | PatientProgram | Embedded |
| Immunization | Not in OpenMRS core (separate module) | N/A |
| CarePlan | Not in OpenMRS core | N/A |
| Appointment | Not in OpenMRS core (separate module) | N/A |

### Resource types as string constants

Resource types (e.g., `"obs"`, `"condition"`, `"order"`) are defined as `public static final String` constants in `ChartSearchAiConstants`, not as a Java enum. This is because resource type values are stored as strings in the `chartsearchai_embedding` database table and returned as strings in the REST API's JSON response. Using an enum would require mapping between the enum and its string representation at every persistence and serialization boundary. String constants avoid this overhead while still providing compile-time references and a single source of truth for the values.

### Build-time architecture guards

`ArchitectureGuardTest` enforces API surface rules at build time by scanning all production source files for violations. If any code bypasses the required entry points — for example, calling `getEmbeddingPrefix()` directly instead of `buildPrefixedText()`, hardcoding prefix strings like `"Clinical observation: "`, reimplementing the cosine similarity formula, or duplicating test dataset helpers — the build fails. This prevents regression where a developer unfamiliar with the API contracts accidentally reimplements pipeline logic inline.

## Decision 10: Single LLM architecture with optional embedding pre-filter

### Context

The current architecture (Decisions 3–9) uses a two-model pipeline: an embedding model for semantic search retrieval, plus a generative LLM for query understanding and response synthesis. This requires vector storage, cosine similarity search, and an embedding indexing strategy.

However, if two conditions are met, this complexity can be eliminated entirely:

1. **The full patient chart fits within the LLM's context window.** A patient with 2000 records, each serialized to ~15 tokens by the `ClinicalTextSerializer`, produces ~30K tokens. Models like Mistral 7B (32K context) and Llama 3.2 3B (128K context) can accommodate this.
2. **A local LLM is available with acceptable latency.** Quantized models (3B–14B parameters) can run on CPU via the embedded [llama-server](https://github.com/ggml-org/llama.cpp/tree/master/tools/server), llama.cpp's built-in HTTP server. The module bundles pre-built llama-server binaries for each supported platform and starts a loopback subprocess on demand, so the operator just installs the `.omod` and the `.gguf` — no separate inference service to manage. The recommended 4B model requires ~6–8GB RAM and produces ~10–20 tokens/sec on CPU. This keeps the module self-contained with no external service dependency.

### Simplified architecture

```
Patient records → ClinicalTextSerializers → All clinical text → LLM → Answer
```

No embedding model, no vector storage, no cosine similarity search, no indexing strategy. The LLM receives all serialized patient records and answers the query directly.

### Advantages over the embedding-based approach

- **Simpler architecture**: One model, no vector storage or indexing infrastructure.
- **More accurate**: The LLM sees the full patient chart and can reason across all records. It understands clinical context, reasoning, and nuance far better than cosine similarity on vectors. For example:
  - *"Has the patient's blood pressure been improving?"* — The LLM can reason over trends across multiple observations over time, comparing values and dates. Vector search just returns individual records that mention blood pressure, with no understanding of whether the numbers are going up or down.
  - *"Any contraindications for prescribing ibuprofen?"* — The LLM can connect an NSAID allergy, a GI bleeding history, and a kidney condition to flag the risk. Vector search might miss records that don't lexically match "ibuprofen" — a GI bleeding episode recorded as `"Condition: Peptic Ulcer. Status: RESOLVED"` has low cosine similarity to the query but is clinically critical.
  - *"Is this patient a fall risk?"* — The LLM can synthesize age, medications with dizziness side effects, a prior hip fracture, and low blood pressure readings into a clinical assessment. Vector search would only find records that happen to contain words similar to "fall risk."
  - *"Should we be concerned about her liver?"* — The LLM can correlate elevated ALT/AST lab results, a hepatotoxic medication history, and alcohol use documented in a social history note. Vector search treats each record independently and cannot connect these dots.
  - *"Is this patient adherent to their TB treatment?"* — The LLM can compare the expected treatment timeline against actual dispensing dates, missed appointment records, and clinician notes about adherence counseling. Vector search would return records mentioning "TB" but cannot evaluate whether the treatment schedule was followed.
  - *"What might be causing her recurrent headaches?"* — The LLM can cross-reference the headache obs with a recent hypertension diagnosis, a new medication with headache as a known side effect, and elevated stress noted in a mental health screening. Vector search finds records containing "headache" but cannot reason about causality across unrelated record types.
  - *"Is it safe to give this child the measles vaccine today?"* — The LLM can check the immunization history for prior doses, look for active febrile illness in today's vitals, review allergy records for egg or neomycin sensitivity, and check for immunosuppressive conditions. Vector search would match on "measles" or "vaccine" but cannot perform the multi-factor safety assessment.
  - *"Summarize this patient's pregnancy history"* — The LLM can piece together gravidity/parity obs, antenatal visit encounters, delivery records, complication diagnoses, and neonatal outcomes across multiple pregnancies spanning years. Vector search returns individual records but cannot weave them into a coherent narrative.
  - *"Why was this patient's ARV regimen changed?"* — The LLM can correlate the regimen change order with a recent viral load result showing treatment failure, a drug resistance test, and a clinician's note about side effects. Vector search finds records mentioning "ARV" but cannot infer the clinical reasoning behind the switch.
  - *"Does this patient need a referral to a specialist?"* — The LLM can evaluate persistent abnormal lab trends despite treatment, a worsening condition status, and failed interventions to suggest that the current care level may be insufficient. Vector search has no concept of "enough has been tried" — it merely retrieves similar-sounding records.
  - *"Is this patient at risk for diabetes complications?"* — The LLM can connect an HbA1c trend showing poor glycemic control, a recent retinal screening referral, peripheral neuropathy symptoms in the review of systems, and a microalbuminuria lab result. Vector search for "diabetes complications" would miss the neuropathy symptoms recorded as `"Obs: Tingling in feet"` and the kidney marker recorded as `"Order: Urine Albumin-Creatinine Ratio"`.
  - *"What happened during the patient's last admission?"* — The LLM can reconstruct a timeline from the admission encounter, daily vitals, medication orders, procedure notes, consultant diagnoses, and discharge summary across dozens of records. Vector search returns fragments but cannot sequence them into a coherent clinical story.
- **No retrieval errors**: Embedding-based retrieval can miss relevant records if the query and record text are semantically distant. Direct LLM inference eliminates this failure mode.
- **No index staleness**: No need for batch or incremental indexing. Every query sees the current chart state.

### Comparison with a knowledge graph approach

A knowledge graph represents clinical data as entities and explicit relationships (e.g., `Patient → has_condition → Diabetes`, `Metformin → treats → Diabetes`, `Metformin → contraindicated_with → Renal Failure`). This is a fundamentally different approach from direct LLM inference, with distinct tradeoffs.

#### Where a knowledge graph is stronger

- **Deterministic and auditable**: Every answer traces to explicit relationships in the graph. There is no hallucination risk — if a contraindication edge exists, it is reported; if it does not, it is not invented.
- **Fast**: Graph traversal completes in milliseconds with no heavy compute requirements.
- **Structured reasoning**: Queries like "What drugs interact with this patient's current medications?" follow explicit edges rather than relying on a model's probabilistic understanding.

#### Where direct LLM inference is stronger

- **No schema to build or maintain**: A clinical knowledge graph requires someone to model every entity type, relationship, and rule upfront. Who defines the `contraindicated_with` relationships for every drug-condition pair? Who adds new relationships when clinical guidelines change? Who maintains the graph when the concept dictionary evolves? This is a massive ongoing investment.
- **Handles unstructured data**: A clinician's note saying "patient reports tingling in feet" is invisible to a knowledge graph unless someone runs NLP extraction to create structured entities first. The LLM reads and understands it directly.
- **Handles novel queries**: The graph can only answer questions about relationships someone thought to model. A query like "Is this patient isolated and at risk for depression?" drawing from social history notes, missed appointments, and living situation obs is impossible in a graph that does not have these relationship types defined.
- **Implicit reasoning**: "Why was this patient's ARV regimen changed?" requires inferring causality from the temporal proximity of a viral load result, a resistance test, and a regimen change order. A knowledge graph would need an explicit `caused_by` edge that no one created.
- **Built-in clinical knowledge**: The LLM brings clinical knowledge from training — it knows that ibuprofen is an NSAID, that NSAIDs are risky with peptic ulcers, and that metformin requires renal monitoring. A knowledge graph only knows what has been explicitly encoded into it.
- **Natural language interface**: The LLM natively understands "Is her sugar under control?" as a question about glycemic management. A knowledge graph requires the query to be translated into a structured graph traversal.

#### Practical considerations for OpenMRS deployments

OpenMRS already has a concept dictionary with some relationship structure (concept classes, concept mappings, drug-concept associations), but this is far from a full clinical knowledge graph with drug interactions, contraindications, risk factor models, and causal relationships. Building and maintaining such a graph requires dedicated clinical informatics expertise that low-resource settings typically lack.

The direct LLM inference approach is more practical for these settings: deploy a single model file and the module works out of the box with clinical reasoning capabilities, no graph construction or maintenance required. The tradeoff is accepting probabilistic answers (with hallucination risk) instead of deterministic graph traversal.

### Hallucination risk comparison

Both approaches carry hallucination risk, but the failure modes differ. In the current system, these correspond to the `chartsearchai.embedding.preFilter` toggle: `true` uses embedding-based pre-filtering, while `false` (default) sends the full chart to the LLM.

#### Embedding-based pre-filtering hallucinations (`preFilter=true`)

With pre-filtering enabled, the LLM only sees the top-K retrieved records (default 10, configurable via `chartsearchai.embedding.topK`). This limits how much it can hallucinate *about*, but it introduces a different risk: hallucinating from *missing context*. Examples:

- The retrieval step misses a relevant record (e.g., a resolved penicillin allergy) because the query and record text are semantically distant. The LLM confidently says "no known drug allergies" based on the records it received.
- The LLM sees a single elevated blood pressure reading without the surrounding context of the patient exercising beforehand (that context is in a different obs comment that was not retrieved). It may overstate the clinical significance.
- The LLM receives a medication order and a lab result but not the clinician's note explaining why the medication was started. It invents a plausible but incorrect reason.

#### Full-chart hallucinations (`preFilter=false`)

With pre-filtering disabled, the LLM sees the full patient chart. It will not miss relevant records, but more input means more opportunity to hallucinate from *over-interpreting context*. Examples:

- The LLM sees a headache obs and a new hypertension medication started the same week. It infers the medication caused the headache, when the timing was coincidental.
- The LLM notices elevated liver enzymes and a hepatitis B diagnosis. It concludes the hepatitis is active and causing the elevation, when the enzymes were actually elevated due to a statin started recently.
- The LLM sees multiple records mentioning fatigue across several visits and synthesizes a narrative about chronic fatigue syndrome, when each instance had a different, resolved cause.

#### Mitigation

The mitigation is the same for both modes: **never present LLM output as clinical fact**. The module should always show the source records alongside the LLM's answer so the clinician can verify. The full-chart mode actually makes this easier — since there is no retrieval step, every record the LLM saw is known and can be cited. With pre-filtering, the clinician must additionally trust that the retrieval step found the right records.

### Source citations

Source citations are straightforward because we control exactly what the LLM sees. Each serialized record is numbered sequentially before being included in the prompt (sorted most recent first):

```
Patient: 45-year-old Male

[1] (2025-10-30) Systolic Blood Pressure: 120 mmHg (ABNORMAL)
[2] (2018-03-10) Condition: Type 2 Diabetes Mellitus. Status: ACTIVE
[3] (2025-01-10) Order: Metformin. Action: NEW
[4] (2025-09-15) HbA1c: 8.2%
```

The system prompt instructs the LLM to cite record numbers in brackets and respond with a JSON object. A strict JSON-schema constraint (`response_format: json_schema`, built by `LocalLlmEngine.buildJsonSchemaResponseFormat()` and enforced by llama-server via a derived GBNF grammar) forces the LLM output to the exact shape `{"reasoning": "...", "answer": "...", "citations": [1, 2]}`, making it structurally impossible for the LLM to produce malformed citations. The leading `reasoning` field is a deliberate chain-of-thought slot (the grammar emits properties in order, so the model thinks before it answers — without it the small local model abstains on queries whose wording differs from the record, e.g. "ear problems" vs a "Hearing Loss" record); it is the model's scratchpad and is ignored by the parser. The `answer` and `citations` are parsed directly as structured data — no regex parsing of free text is needed.

Example LLM output:
```json
{"answer": "The patient's diabetes appears poorly controlled. Their most recent HbA1c was 8.2% [4], above the target of 7%, despite being on Metformin [3].", "citations": [4, 3]}
```

On the Java side, each citation number maps back to a `resource_type` + `resource_uuid` pair maintained in an ordered list (`RecordMapping`) during prompt construction. The UI can then link each citation directly to the source record in OpenMRS, allowing the clinician to verify every claim with one click.

As a safety net, slash-separated citation shorthand that small LLMs occasionally produce in the answer text (e.g., `[5/12]`) is normalized to `[5], [12]` before returning to the user. This split only fires when every number in the group appears in the model's structured `citations` array, so a slash-separated clinical *value* the model bracketed — e.g. a blood pressure `[120/80]` — is left intact rather than mangled into `[120], [80]`. Using the citations array as the authority (rather than valid record indices) keeps this correct regardless of chart size. This is cosmetic only — the authoritative citations come from the structured `citations` array.

### Candidate models

See the [Evaluated models](../README.md#evaluated-models) section in the README for the full comparison table of all models tested, including size, RAM, context window, CPU speed, and licensing. The discussion below covers the detailed per-model analysis and trade-offs behind the recommendation.

### Recommended models: Gemma 4 E4B (module default) and Gemma 4 26B MoE (production)

The module ships two recommended choices, sized for different deployment contexts.

**Gemma 4 E4B Instruct** is the default in `config.xml` (`chartsearchai/gemma-4-E4B-it-Q4_K_M.gguf`) for ordinary module installs. It is part of the Gemma 4 "E" line, which uses Per-Layer Embeddings (PLE) for memory efficiency: ~4.5B effective parameters at runtime, ~2.5GB on disk, ~6–8GB total RAM, ~10–20 tok/s on CPU. The 128K context window holds roughly 6,000 serialized patient records (~15 tokens each), enough for most patient charts. Apache 2.0 licensed. GGUF quantizations are available from [unsloth/gemma-4-E4B-it-GGUF](https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF). Picked because it is the smallest model in the Gemma 4 family that follows the system prompt rules (never infer, cite every record) acceptably without a reasoning channel as a safety scaffold, while staying small enough to download on a slow connection (~2.5GB) and run on a modest server.

**Gemma 4 26B MoE Instruct** is bundled with the standalone build and is the recommended upgrade for production hardware (~24GB+ RAM). It is a Mixture-of-Experts model with 26B total parameters but only ~3.8B activated per token, so per-token speed is comparable to a 4B dense model despite the 26B total size. The 256K context window comfortably holds even the largest patient charts. Apache 2.0 licensed. GGUF quantizations are available from [unsloth/gemma-4-26B-A4B-it-GGUF](https://huggingface.co/unsloth/gemma-4-26B-A4B-it-GGUF). Picked because among CPU-viable models it has the strongest instruction following on list-completeness and adversarial-question handling, without depending on reasoning tokens.

For deployments that prefer medical-domain fine-tuning, **MedGemma 1.5 4B** (released January 2026) is a strong alternative — built on the Gemma 3 architecture and fine-tuned on clinical text, biomedical literature, medical Q&A, and synthetic EHR data, with native support for medical imaging (CT, MRI, histopathology). At 4B parameters with Q4_K_M, it is ~2.5GB on disk and ~6–8GB total RAM. GGUF quantizations are available from [unsloth/medgemma-1.5-4b-it-GGUF](https://huggingface.co/unsloth/medgemma-1.5-4b-it-GGUF). Licensed under the [Health AI Developer Foundations Terms of Use](https://developers.google.com/health-ai-developer-foundations/terms) — requires validation before clinical deployment, more restrictive than the Apache 2.0 licensing of Gemma 4. The original MedGemma 4B remains available from [unsloth/medgemma-4b-it-GGUF](https://huggingface.co/unsloth/medgemma-4b-it-GGUF) and works identically with the module (same chat template and resource requirements).

### Alternative models

| Model | RAM Needed | Chat Template | Why |
|-------|-----------|---------------|-----|
| **Llama 3.2 3B** | ~6GB total | `llama3` | For low-resource deployments where medical-domain fine-tuning is not required. Faster inference but weaker instruction following. Requires changing model path. |
| **Llama 3.3 8B** | ~10GB total | `llama3` | Significantly better general reasoning and instruction following than 4B. Recommended when 10GB RAM is available. Requires changing model path. |
| **Mistral Nemo 12B** | ~12GB total | `mistral` | Best sub-15B option for clinical Q&A. Strong medical text comprehension and 128K context window. Requires changing model path. |

These models are from US/EU organizations (Meta and Mistral AI) and have strong performance on medical benchmarks. Switching requires only one global property change (`modelFilePath`) — llama-server reads the chat template from the GGUF metadata. No code changes or module rebuild.

### Other alternatives

- **Qwen 2.5 1.5B** is faster and smaller but its 32K context window limits it to ~2,000 records, and its reasoning capability is weaker at 1.5B parameters.
- **Phi-3 Mini 3.8B** (Microsoft) has slightly better reasoning per parameter than Llama 3.2 3B, but its default 4K context window is far too small for full patient charts. The 128K variant exists but is slower on CPU due to the longer context handling. Phi models are trained primarily on synthetic/textbook data and tend to be weaker on messy, real-world clinical text compared to Llama and Mistral models at similar sizes.
- **Phi-3-Medium 14B** (Microsoft) is the largest model in the Phi-3 family at 14 billion parameters. It uses the same Transformer decoder architecture as Phi-3 Mini but scaled up, trained on a mix of synthetic data generated by larger models and heavily filtered web data (Microsoft's "textbook quality" data curation pipeline). It scores competitively on reasoning benchmarks — outperforming Llama 3.1 8B and Mistral Nemo 12B on MMLU (~78%), GSM8K (~89%), and HumanEval (~62%), and approaching GPT-3.5-Turbo on several benchmarks. However, the same caveats that apply to Phi-3 Mini apply here: the training data skews toward clean, synthetic, and textbook-style text, which means it can underperform on messy, real-world clinical notes with abbreviations, typos, and inconsistent formatting compared to Llama and Mistral models trained on broader web corpora. The default context window is only 4K tokens, which limits it to ~250 serialized patient records without embedding pre-filtering — far too small for large patient charts. A 128K variant (`Phi-3-medium-128k-instruct`) exists but is significantly slower on CPU due to the RoPE scaling required for long contexts. At 14B parameters with Q4_K_M quantization, it is ~8GB on disk and requires ~14GB total RAM — the same resource footprint as Qwen 2.5 14B but with a much smaller default context window. It uses the `phi3` chat template already supported by the module. Licensed under MIT, which is a genuinely permissive open-source license with no usage restrictions — more permissive than Llama's community license. For deployments where licensing simplicity matters (e.g., government or NGO procurement), this is an advantage. However, given the 4K default context limitation and the weaker performance on unstructured clinical text, Qwen 2.5 14B or Mistral Nemo 12B are generally better choices at this parameter class for clinical Q&A.
- **Mistral 7B** has strong reasoning but at 7B parameters it is noticeably slower on CPU (~10–15 tok/s) and requires ~8GB RAM. Superseded by Llama 3.3 8B which offers better quality at a similar resource cost.
- **Qwen 2.5 7B/14B** (Alibaba) offers strong instruction following and large context windows. However, Qwen is developed by a Chinese company subject to China's data laws — while GGUF models run locally with no data leaving the machine, US healthcare organizations may face compliance or perception concerns. Consider Llama or Mistral alternatives first.
- **Gemma 3 4B** (Google, March 2025) is the base model for MedGemma. It offers the same 128K context window and resource footprint as MedGemma 4B but without medical-domain fine-tuning. Useful when a general-purpose model is preferred. Licensed under the [Gemma Terms of Use](https://ai.google.dev/gemma/terms). GGUF quantizations are available from [bartowski/google_gemma-3-4b-it-GGUF](https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF).
- **Gemma 3n E2B/E4B** (Google, June 2025) are on-device-optimized models using Per-Layer Embeddings (PLE) and the MatFormer architecture for extreme memory efficiency. E2B has 5B total parameters but runs with a memory footprint comparable to a 2B model (~2–3GB RAM); E4B has 8B total parameters but runs like a 4B model (~3–5GB RAM). Both have a 32K context window (not 128K), which limits them to ~2,000 records. Designed for edge deployment on mobile and low-power hardware. Licensed under the [Gemma Terms of Use](https://ai.google.dev/gemma/terms). GGUF quantizations are available from [bartowski/google_gemma-3n-E2B-it-GGUF](https://huggingface.co/bartowski/google_gemma-3n-E2B-it-GGUF) and [bartowski/google_gemma-3n-E4B-it-GGUF](https://huggingface.co/bartowski/google_gemma-3n-E4B-it-GGUF).
- **Gemma 3 12B** (Google, March 2025) offers strong reasoning at 12B parameters with a 128K context window — comparable to Mistral Nemo 12B in resource cost but with a larger context window. Licensed under the [Gemma Terms of Use](https://ai.google.dev/gemma/terms). GGUF quantizations are available from [bartowski/google_gemma-3-12b-it-GGUF](https://huggingface.co/bartowski/google_gemma-3-12b-it-GGUF).
- **Gemma 2 9B Instruct** (Google) has excellent reasoning and instruction following at 9B parameters, but its 8K context window limits it to ~500 records without embedding pre-filtering. Requires ~10GB RAM.
- **Gemma 4 E2B/E4B** (Google, April 2026) are the successors to Gemma 3n, using the same PLE architecture for memory efficiency but with improved quality. E4B offers 128K context (vs 32K in Gemma 3n) and is a strong general-purpose alternative to MedGemma 4B at similar resource cost (~6–8GB RAM). The major advantage is licensing: Gemma 4 uses the **Apache 2.0** license — fully permissive with no usage restrictions — the first Gemma family release under a standard open-source license. GGUF quantizations are available from [bartowski/google_gemma-4-E4B-it-GGUF](https://huggingface.co/bartowski/google_gemma-4-E4B-it-GGUF).
- **Gemma 4 26B MoE** (Google, April 2026) is a Mixture-of-Experts model with 26B total parameters but only 3.8B active parameters per token, providing faster inference than dense models of similar size. 256K context window. Apache 2.0 license. Requires ~18–22GB total RAM.
- **Gemma 4 31B Dense** (Google, April 2026) is the largest and most capable Gemma 4 model. At 31B dense parameters with a 256K context window, it offers the best general reasoning in the Gemma family. Apache 2.0 license. Requires ~22–26GB total RAM. CPU inference is very slow (~1–2 tok/s) — practical only with GPU acceleration. GGUF quantizations are available from [bartowski/google_gemma-4-31B-it-GGUF](https://huggingface.co/bartowski/google_gemma-4-31B-it-GGUF).
- **MedGemma 27B Text** (Google) is a medical-domain model built on the Gemma 3 architecture, fine-tuned on clinical and biomedical text. At 27B parameters it offers strong medical text comprehension and a 128K token context window. With Q4_K_M quantization it is ~16.5GB on disk and requires ~20–24GB total RAM. CPU inference is very slow (~1–2 tok/s), making it impractical for point-of-care use without a GPU (16–24GB VRAM recommended, where it can reach ~10–20+ tok/s). It uses the `gemma` chat template already supported by the module. Licensed under the [Health AI Developer Foundations Terms of Use](https://developers.google.com/health-ai-developer-foundations/terms), which is more restrictive than Llama's community license — review the terms before deploying. GGUF quantizations are available from [unsloth/medgemma-27b-text-it-GGUF](https://huggingface.co/unsloth/medgemma-27b-text-it-GGUF). Best suited for GPU-equipped deployments where medical-domain accuracy is the top priority.

All models run via the embedded llama-server with Q4_K_M quantization in GGUF format.

### Licensing

MedGemma (both 1.5 4B and 27B Text) is licensed under the [Health AI Developer Foundations Terms of Use](https://developers.google.com/health-ai-developer-foundations/terms). This is more restrictive than typical open-source licenses — it requires validation before clinical deployment and review of terms before distributing. The license requires the following attribution: *"MedGemma is licensed under the Health AI Developer Foundations License, Copyright (C) Google LLC. All Rights Reserved."*

**Gemma 4** (E2B, E4B, 26B MoE, 31B Dense) is licensed under the [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0) — fully permissive with no usage restrictions. This is a significant change from earlier Gemma releases and makes Gemma 4 the most permissively licensed model in the Gemma family. For deployments where licensing simplicity is a priority (e.g., government or NGO procurement), Gemma 4 models are the strongest option.

**Gemma 3 and Gemma 3n** are licensed under the [Gemma Terms of Use](https://ai.google.dev/gemma/terms). This is a custom license that permits commercial use but reserves Google's right to terminate access for policy violations and requires compliance with Google's acceptable use policy, including future amendments. More restrictive than Apache 2.0 but still allows commercial deployment.

The alternative Llama models (3.2 3B, 3.3 8B) are free for both research and commercial use under the [Llama 3.2 Community License](https://www.llama.com/llama3_2/license/). The only meaningful restriction is that products with over 700 million monthly active users require a separate license from Meta, which is not a concern for OpenMRS. The license requires the following attribution: *"Llama 3.3 is licensed under the Llama 3.2 Community License, Copyright (C) Meta Platforms, Inc. All Rights Reserved."*

### Deployment and memory requirements

The LLM runs as a `llama-server` subprocess that the OpenMRS JVM starts on the loopback interface and talks to over the OpenAI-compatible chat completions API. The subprocess is bundled with the module: a `llama-server-natives` Maven submodule packages pre-built binaries for each supported platform (mac/linux/windows × arm64/x86_64), and `LocalLlmEngine` extracts the right binary at runtime. From an operator's perspective the deployment is still two files:

1. The `.omod` module file (includes the bundled llama-server binaries)
2. The `.gguf` model file (placed in the OpenMRS application data directory)

On module startup, `ChartSearchAiModuleActivator` validates that all configured model files (LLM GGUF, ONNX embedding, WordPiece vocabulary) exist and are readable, logging warnings for any missing files. It also registers the embedding backfill and audit log purge scheduled tasks. On module shutdown, it shuts down the LLM provider (which terminates the llama-server subprocess and cancels the idle timer), closes the ONNX embedding provider, and disposes of the Elasticsearch and Lucene resources — preventing leftover processes and native memory leaks.

The model path is configured via the `chartsearchai.llm.modelFilePath` global property. The subprocess starts on first query (lazy loading) and is automatically stopped after a configurable idle period (`chartsearchai.llm.idleTimeoutMinutes`, default 30 minutes) to free RAM. The subprocess is transparently restarted on the next query. This debounced idle timer uses a single daemon thread with a `ScheduledExecutorService` — after each inference completes, any pending stop is cancelled and a new one is scheduled. Setting the idle timeout to 0 keeps the subprocess running indefinitely. If the model path global property is changed, `shouldRestartServer` detects the change and the subprocess is restarted with the new model on the next query — no OpenMRS restart required.

Inference uses temperature 0.0 with greedy decoding, which is deterministic at the sampler level for a fixed prompt. KV cache reuse is enabled (`cache_prompt: true` plus `--cache-reuse 256`) so repeat queries on the same patient pay only the new question's prefill cost — typically an order-of-magnitude latency win on follow-up questions, since the system prompt and chart text are byte-identical between turns.

The same KV cache also lets the *first* query on a patient be fast: when the chart is opened, the frontend calls `POST /ws/rest/v1/chartsearchai/warmup` and the module sends a fire-and-forget prefill request (system prompt + serialized chart, empty trailing question, `max_tokens=1`) to llama-server. By the time the clinician types their first real question, the chart is already in the KV cache. Gated by `chartsearchai.warmupEnabled` (default `true`). It is a no-op when the engine is remote (remote providers manage their own caching) and when `chartsearchai.embedding.preFilter` is `true` (the prompt prefix varies per query, so there is no stable prefix to prime). Stale-skip logic in `WarmupExecutor` drops queued warmups for patients the user has already navigated away from.

Model file paths are resolved relative to the OpenMRS application data directory. Path traversal (`..`) is rejected and the resolved path is verified to stay within the data directory, preventing an admin from accidentally (or maliciously) pointing the module at arbitrary files on the filesystem.

### Chat template handling

The embedded llama-server reads each model's chat template from the GGUF metadata (`tokenizer.chat_template`) at load time, so prompts are wrapped in the format the model was trained on without any per-model configuration. Switching to a different GGUF — Gemma, Llama, Mistral, Phi-3, Qwen, or any other modern instruct model — needs only a `chartsearchai.llm.modelFilePath` change; the right turn delimiters and special tokens come along automatically.

```
OpenMRS JVM
  └── chartsearchai module
        └── LlmInferenceService
              └── LlmProvider → LocalLlmEngine
                    └── llama-server subprocess (loopback HTTP)
                          └── llama.cpp (native C++) loading the GGUF
```

### Model size trade-offs

The module works with any GGUF-format model. Larger models produce better responses (more accurate, better instruction following, fewer hallucinations) but require more RAM and are slower on CPU. All figures below are for Q4_K_M quantization.

| Model | File Size | RAM (model + KV cache) | Total with OpenMRS JVM | CPU Inference Speed |
|-------|-----------|------------------------|------------------------|---------------------|
| **1–2B** (e.g. Gemma 3 1B, Gemma 3n E2B, Gemma 4 E2B) | ~0.7–1.5GB | ~1–3GB | ~2–5GB | ~25–50 tokens/sec |
| **3B** (e.g. Llama 3.2 3B) | ~2GB | ~3–4GB | ~5–6GB | ~5–15 tokens/sec |
| **4B** (e.g. MedGemma 1.5 4B, Gemma 4 E4B) | ~2.5GB | ~4–6GB | ~6–8GB | ~10–20 tokens/sec |
| **7B** (e.g. Qwen 2.5 7B, Mistral 7B) | ~4GB | ~6–8GB | ~8–10GB | ~3–8 tokens/sec |
| **9B** (e.g. Gemma 2 9B Instruct) | ~5GB | ~8–10GB | ~10–12GB | ~3–6 tokens/sec |
| **12B** (e.g. Gemma 3 12B, Mistral Nemo 12B) | ~7GB | ~10–12GB | ~12–14GB | ~4–8 tokens/sec |
| **14B** (e.g. Qwen 2.5 14B) | ~8GB | ~12–14GB | ~14–16GB | ~2–4 tokens/sec |
| **26–31B** (e.g. Gemma 4 26B MoE, Gemma 4 31B, MedGemma 27B Text) | ~15–18GB | ~18–24GB | ~20–26GB | ~1–2 tokens/sec |

**1–2B models** (e.g. Gemma 3 1B, Gemma 3n E2B, Gemma 4 E2B) are the smallest viable options. Gemma 3n and Gemma 4 "E" models use Per-Layer Embeddings (PLE) for memory efficiency — E2B runs in as little as ~2–3GB RAM. Fast inference (~25–50 tok/s) but weaker reasoning and instruction following. Gemma 4 E2B offers 128K context; Gemma 3 1B and Gemma 3n E2B are limited to 32K. Best suited for extremely resource-constrained or on-device deployments where response quality can be traded for speed and low memory.

**3B models** are the most deployable in low-resource settings but struggle with strict instruction following — they tend to produce verbose responses, add unsolicited commentary, and hedge when they should give a direct "not found" answer. Few-shot examples in the system prompt help but do not fully solve this.

**4B models** (e.g. MedGemma 1.5 4B, Gemma 4 E4B) occupy a sweet spot between 3B and 7B — similar resource cost to 3B models (~6–8GB total RAM). MedGemma 1.5 4B provides medical-domain fine-tuning with improved medical imaging support (CT, MRI, histopathology) over the original MedGemma 4B. Gemma 4 E4B is a strong general-purpose alternative with 128K context under the permissive Apache 2.0 license — for deployments where licensing simplicity matters more than medical fine-tuning. The trade-off versus general-purpose 8B models is that medical fine-tuning improves clinical text comprehension but may reduce general instruction-following ability compared to Llama 3.3 8B. Note that MedGemma's Health AI Developer Foundations license requires validation before clinical deployment.

**8B models** (e.g. Llama 3.3 8B) offer significantly better instruction following and clinical reasoning than 3B, while still feasible on a server with 10GB RAM. Recommended upgrade when hardware allows.

**9B models** (e.g. Gemma 2 9B Instruct) offer excellent reasoning and instruction following. Note that Gemma 2's 8K context window is smaller than Llama or Qwen models, so embedding pre-filtering is strongly recommended.

**12B models** (e.g. Gemma 3 12B, Mistral Nemo 12B) offer the best sub-15B quality for clinical Q&A. Gemma 3 12B provides 128K context with strong reasoning and instruction following under the Gemma Terms of Use. Mistral Nemo 12B has strong medical text comprehension under Apache 2.0. Both require ~12GB total RAM.

**14B models** (e.g. Qwen 2.5 14B, Phi-3-Medium 14B) provide the best response quality among CPU-viable options, with strong reasoning. They require 14–16GB total RAM and produce slower inference (~2–4 tok/s). Suitable for well-resourced deployments where response quality is prioritized over speed. Note that context window size varies significantly at this tier — Qwen 2.5 14B offers 128K tokens natively, while Phi-3-Medium defaults to 4K (128K variant available but slower on CPU).

**26–31B models** (e.g. Gemma 4 26B MoE, Gemma 4 31B Dense, MedGemma 27B Text) are the highest-quality tier. Gemma 4 26B MoE activates only 3.8B of its 26B parameters per token, offering faster inference than dense models at this size — a good trade-off for GPU-equipped deployments. Gemma 4 31B Dense offers the best general reasoning in the Gemma family under Apache 2.0 with a 256K context window. MedGemma 27B Text is the medical-domain specialist. All require ~20GB+ total RAM and are practical mainly with GPU acceleration (16–24GB VRAM), where they can achieve ~10–20+ tok/s. Consider this tier for GPU-equipped deployments where accuracy justifies the hardware investment.

A server running OpenMRS typically uses 1–2GB for the JVM heap. A 4GB machine is insufficient to run this module — the LLM alone requires at least 3–4GB for the smallest viable model.

### Hardware requirements

The module requires sufficient RAM for both the OpenMRS JVM and the LLM model:
- **Minimum**: ~3–5GB total (1–2GB JVM + ~2–3GB for a Gemma 4 E2B or Gemma 3n E2B model). Usable but with weaker instruction following and reasoning. For 3B models, ~6GB total.
- **Recommended**: ~6–8GB total for the default Gemma 4 E4B model (or MedGemma 1.5 4B). Upgrade to ~10GB for the 8B model, which provides significantly better general reasoning, or ~24GB+ for the production-grade Gemma 4 26B MoE bundled with the standalone build.
- The embedding pre-filter (opt-in via `chartsearchai.embedding.preFilter=true`) reduces the number of tokens sent to the LLM, which improves latency on huge patient charts at the cost of potentially omitting records the LLM needs for negative reasoning. The default is full-chart.

### Decision

A single architecture is used: all queries go through the LLM for reasoning and synthesis. By default the full patient chart is sent to the LLM. An optional embedding pre-filter (`chartsearchai.embedding.preFilter=true`) narrows the chart to the most relevant records (default top 10, configurable via `chartsearchai.embedding.topK`) before sending. Pre-filtering helps the "lost in the middle" problem on small LLMs with large charts, but can omit records the LLM needs for negative reasoning (e.g. "no allergies recorded" requires having seen the empty allergy section, not just an absence of matches). The default was originally `true` and was flipped to `false` after deployment experience showed silent record omission was the worse failure mode — full-chart mode now produces an actionable HTTP 413 response when the chart exceeds the LLM context window, prompting admins to increase `chartsearchai.llm.contextSize`.

Embeddings are indexed on first patient chart access and kept up to date automatically via AOP hooks on data changes. A bulk backfill task is also available for pre-indexing all patients.

Embeddings use all-MiniLM-L6-v2 via ONNX Runtime (~90MB model file, configured via `chartsearchai.embedding.modelFilePath` and `chartsearchai.embedding.vocabFilePath`).

### Embedding model selection

The default embedding model (all-MiniLM-L6-v2) is general-purpose. For clinical text, it produces narrow similarity score ranges (e.g., 0.20–0.31) with modest separation between relevant and irrelevant records. A clinical-domain model like [NeuML/pubmedbert-base-embeddings](https://huggingface.co/NeuML/pubmedbert-base-embeddings) (Apache 2.0, 768 dimensions, ~440MB), fine-tuned on PubMed text, was tested as an alternative. While pubmedbert produced higher absolute similarity scores (~0.57 vs ~0.31), it performed worse at retrieval ranking — for a query like "any past history of tumors?", pubmedbert returned pulse readings while all-MiniLM-L6-v2 correctly returned Kaposi sarcoma records. Higher absolute scores do not guarantee better retrieval; what matters is the relative ranking of relevant vs irrelevant records. all-MiniLM-L6-v2 remains the default.

Any BERT-based ONNX embedding model can be used as a drop-in replacement by updating `chartsearchai.embedding.modelFilePath` and `chartsearchai.embedding.vocabFilePath`. Embedding dimensions are auto-detected from the model output — both 384-dim and 768-dim models (and any other size) work without code changes. After switching models, all existing embeddings must be recomputed by running the backfill task, since embeddings from different models are not compatible.

A `chartsearchai.embedding.similarityRatio` setting (default 0.80) filters out low-relevance records by requiring each record to score at least 80% of the top result's similarity score. This works alongside `topK` as a quality floor — `topK` sets the maximum number of records, while `similarityRatio` drops noise within that cap.

### Similarity threshold algorithm

The similarity threshold uses a dual-floor approach that adapts to query strength:

- **Strong matches (top score > 0.50)**: The query matched a specific record strongly, so the ratio-based floor (`topScore * similarityRatio`) is reliable. Only this floor is used.
- **Weak matches (top score <= 0.50)**: Queries are fuzzier and out-of-vocabulary terms can depress relevant records. A range-based floor (`topScore - similarityRatio * scoreRange`) provides needed leniency. The minimum of the ratio floor and range floor is used.

An **absolute similarity floor** (0.25) filters out completely unrelated queries early — if the best match in the entire patient chart scores below 0.25, the query is unrelated to any record (e.g., "any teacher?" against clinical data) and an empty result is returned.

An **adaptive gap detection** algorithm (`chartsearchai.embedding.scoreGapMultiplier`, default 2.5) finds natural cluster boundaries in the sorted similarity scores. It walks the scores tracking the running average gap between consecutive entries. When a gap exceeds the multiplier times the average gap, the cluster boundary is found and lower-scoring records are excluded. This ensures at least 2 records are returned when available above the similarity floor.

### Retrieval precision improvements

The general-purpose embedding model (all-MiniLM-L6-v2) ranks records by lexical overlap rather than clinical semantics. For example, a query like "any medications?" would rank an allergy record containing "DRUG" higher than actual drug orders, because the word "DRUG" has higher surface-level similarity to "medications" than dosing details like "500mg Oral twice daily." Several techniques address this:

**Full-text embedding.** The complete serialized text of each record is embedded (prepended with its type-specific prefix). Earlier versions embedded only the first sentence (up to the first `. `), but this discarded semantically important content such as dosing details, severity, reactions, and values — making it impossible for queries about those details to match. The 256-token WordPiece tokenizer limit provides natural truncation for unusually long records. The `firstSentence()` utility method is retained for backward compatibility but is no longer used in the embedding pipeline.

**Semantic embedding prefixes.** Each record's text is additionally prepended with a type-specific prefix before computing embeddings (but not in the LLM prompt). For example, a drug order is embedded as `"Medication prescription: Drug order: Azithromycin..."` while an allergy is embedded as `"Patient allergy: Allergy: Penicillin..."`. This shifts the embedding vectors toward the right semantic space, so medication queries rank drug orders higher. Prefixes are further specialized by order sub-type: `"Medication prescription:"` for drug orders, `"Lab or diagnostic test:"` for test orders, and `"Clinical referral:"` for referral orders. In testing, this moved drug orders from 8th/9th place to 1st/3rd place for medication queries.

**Query-side prefixing.** To reduce the asymmetry between how queries and documents are embedded, the user's query can be prepended with a configurable prefix before being embedded (`chartsearchai.embedding.queryPrefix`, default empty). This is disabled by default because the current embedding model (all-MiniLM-L6-v2) was not trained with instruction prefixes, and adding one dilutes short queries with noise tokens that reduce cosine similarity. When switching to a model that supports instruction-aware queries (e.g., `BAAI/bge-base-en-v1.5` which expects `"Represent this sentence for searching relevant passages: "`), set the prefix via the global property to improve retrieval alignment.

**Hybrid keyword + semantic retrieval.** Pure semantic similarity can miss exact keyword matches (e.g., a query for "Metformin" should find all Metformin records regardless of embedding similarity). To address this, a keyword overlap score is computed alongside cosine similarity and combined as an additive bonus: `finalScore = semanticScore + α × keywordScore`, where α defaults to 0.3 (`chartsearchai.embedding.keywordWeight`). The keyword score is the fraction of query terms (after stopword removal) that appear as case-insensitive substrings in the record's stored `textContent`. The additive formulation ensures that keyword overlap can only increase a record's score, never decrease it — a zero keyword match leaves the semantic score unchanged. This prevents the retrieval pipeline from being blind to literal keyword overlap while preserving the semantic baseline that drives threshold computation.

**Query stopword normalization.** Common filler words ("does", "the", "patient", "have", "any") are stripped from the query before embedding, so that "any medications?" and "does the patient have any medications?" produce the same embedding vector and identical retrieval results. Without this, different phrasings of the same question return different filtered record sets, leading to inconsistent LLM answers. Stopwords are loaded from `<application-data-directory>/chartsearchai/query-stopwords.txt` if present, otherwise from a bundled default. Admins can customize the stopwords list by placing a modified file at that path without recompiling the module. Only true filler words are stripped — clinical qualifiers like "no", "not", "current", "recent", "last", and "active" are preserved because they change the query's meaning.

**Resource-type boosting.** A lightweight keyword-based query classifier (`QueryClassifier`) maps the user query to the clinical resource types most likely to contain the answer. For example, a query mentioning "medications" or "drugs" targets `order` and `medication_dispense` types; "allergies" targets `allergy`; "lab results" targets `obs`; "conditions" targets `condition`; "diagnoses" targets `diagnosis`. Each type is mapped independently so they don't compete for retrieval slots — asking "any conditions" returns only condition records, not a mix of conditions and diagnoses. When the classifier identifies target types, records of those types receive a configurable score boost (`chartsearchai.embedding.typeBoostFactor`, default 1.0, i.e., disabled) applied to their combined semantic + keyword score. The boost is disabled by default because it can create artificial score gaps between boosted and non-boosted records that trigger false gap-detection cutoffs. Values like 1.2–1.5 provide moderate boosting when enabled. Importantly, the classifier receives the **original raw query** (before stopword removal) because category indicator words like "any", "all", "what" overlap with stopwords and would be lost after stripping.

**Two-phase retrieval for category queries.** The query classifier also detects broad "category" queries — queries that combine a category indicator word ("any", "all", "list", "show", "what", "which", "every", "tell") with a resource type keyword. For example, "any medications?" or "list all conditions" are category queries. When detected, the retrieval pipeline uses a two-phase approach: **Phase 1** includes ALL records of the matched resource types regardless of score or topK (auto-expand), because the user explicitly asked for everything of that type and rare medical terms like "Granuloma annulare" can produce low cosine similarity against generic category words like "conditions" despite being a perfect type match. **Phase 2** fills remaining topK slots with the best non-type-matched records from the semantic adaptive cutoff (e.g., assessment notes that provide relevant context). For focused queries (no category indicator detected), topK is applied only when some surviving candidates lack keyword matches — those may be semantic false positives that need capping. When every candidate has a keyword match, topK is bypassed because the combination of gap detection and ratio floor already identified the relevant cluster; applying topK would arbitrarily truncate legitimate results (e.g., 15 vital-sign records for a multi-concept query about "BP, weight, and temperature trend"). This ensures that "any conditions?" returns every condition record, multi-concept queries return all matching vitals, while "does the patient have diabetes?" returns only the most semantically relevant records within topK.

**Absent-data detection.** When the embedding pre-filter returns zero matching records for a query, the system returns a clear answer naming what was asked about — e.g., "There are no records about diabetes in this patient's chart" — without invoking the LLM at all. This avoids a wasteful inference round-trip and gives the clinician an unambiguous signal that the data is absent, rather than a hallucinated answer. The query's stopwords are stripped first (`stripQueryStopwords()`) so the answer names the clinical terms, not filler words. If no content words remain after stripping, a generic "There are no records matching your question" fallback is used. Additionally, a **z-score gate** (`ZERO_KEYWORD_MIN_Z_SCORE = 1.5`, requiring at least `MIN_RECORDS_FOR_Z_SCORE = 30` records) rejects results when no query keyword appears in any candidate record and the top semantic score is not a statistical outlier. This prevents the embedding model's tendency to group similar record types together (e.g., all lab tests scoring ~0.27 for "HB results") from producing false positives — the top score must be in the top ~6.7% of the score distribution to be accepted without keyword corroboration. A **z-score floor rescue** (`FLOOR_RESCUE_MIN_Z_SCORE = 2.0`) handles vocabulary-mismatch queries where the top semantic score falls below the absolute similarity floor (0.25) despite correct ranking. Colloquial queries like "how hot is the patient?" produce low cosine similarity to clinical terms like "Temperature" because the embedding model has no direct lexical overlap, but the top score is still a statistical outlier relative to the rest of the patient's records. When the top score is below the floor but its z-score meets or exceeds 2.0 (and the query has content terms after stopword removal, and fewer than 2 candidates have keyword matches), the floor gate is bypassed. The threshold of 2.0 is stricter than the zero-keyword z-score gate (1.5) because overriding a hard floor requires stronger evidence, but less strict than the cluster z-score threshold (2.5) because below-floor scores are inherently compressed. This separates genuine vocabulary-mismatch queries (e.g., "hot" → Temperature, z≈2.25) from irrelevant queries on a dataset without matching records (e.g., "fracture" on a fracture-free dataset, z≈1.90).

**Recency cap extraction.** Queries like "last 7 weights" or "latest two blood pressure readings" contain natural-language recency constraints. The `extractRecencyCap()` method parses these using regex patterns that recognize both digit and word numbers (one through ten) combined with temporal keywords (last, latest, past, previous, recent, most recent). When a recency cap is detected, `capPerConcept()` limits the number of records per concept to the specified count. Since records are sorted most-recent-first, the first N per concept group are the most recent. Records without repeated measurements (conditions, allergies) are treated as unique groups and always kept — the recency cap only limits repeated measurements like vitals and lab results.

**Concept grouping.** After filtering and recency capping, retrieved records are reordered by `groupByConcept()` so that records of the same concept appear together. For example, interleaved records like [BP, Weight, BP, Temp, Weight] become [BP, BP, Weight, Weight, Temp]. This helps small LLMs process multi-concept queries by reducing the need to mentally sort interleaved records — the model can process all blood pressure readings together, then all weights, rather than jumping between concepts. Groups appear in the order their first record is encountered, preserving recency ordering at the group level.

**Concept synonym deduplication.** `PatientChartSerializer` uses `ConceptNameUtil.stripSynonyms()` to remove parenthesized synonym suffixes from concept names before constructing the LLM prompt. For example, `"WEIGHT (KG) — MEASURED"` and `"Weight (kg)"` are recognized as the same concept. This prevents the LLM from treating synonym variants as different clinical findings.

**Configurable embedding model parameters.** The embedding model's token sequence length (`chartsearchai.embedding.maxSequenceLength`, default 256) and query-side prefix (`chartsearchai.embedding.queryPrefix`, default empty) are configurable via global properties. This supports swapping to alternative embedding models without code changes — for example, `BAAI/bge-base-en-v1.5` uses a 512-token limit and the prefix `"Represent this sentence for searching relevant passages: "`, while `all-mpnet-base-v2` uses 384 tokens. After changing the model file, vocab file, and these parameters, run the embedding backfill task to recompute all vectors.

### Chunking strategy

No chunking is used. Each patient record (obs, condition, diagnosis, allergy, order, program enrollment, medication dispense) is serialized as a single text string and embedded as one unit. This is possible because individual clinical records are naturally short — typically a sentence or two — so they fit well within the embedding model's 256-token window without splitting. This avoids the complexity of chunk boundary management, overlap strategies, and reassembly that document-oriented RAG systems require.

## Decision 11: REST API and guardrails

### REST endpoints

The module exposes REST endpoints for chart search queries and user feedback. Query endpoints require the `AI Query Patient Data` privilege and are registered under the OpenMRS `webservices.rest` module namespace.

#### Synchronous endpoint

```
POST /ws/rest/v1/chartsearchai/search
{
  "patient": "patient-uuid-here",
  "question": "What medications is this patient on?"
}
```

Response:
```json
{
  "questionId": "42",
  "answer": "The patient is currently on...[1]...[3]",
  "disclaimer": "This response is AI-generated and may not be accurate...",
  "references": [
    { "index": 3, "resourceType": "order", "resourceUuid": "a8f5f167-4ee2-4d2a-94f9-3f3f86d2e9b6", "date": "2025-03-15" },
    { "index": 1, "resourceType": "obs", "resourceUuid": "5946f880-b197-400b-9caa-a3c661d71165", "date": "2025-01-10" }
  ]
}
```

#### Streaming endpoint (SSE)

```
POST /ws/rest/v1/chartsearchai/search/stream
{
  "patient": "patient-uuid-here",
  "question": "What medications is this patient on?"
}
```

Returns a `text/event-stream` with three event types:
- `token` — a chunk of the answer text, streamed as generated
- `done` — final JSON with the complete answer, references (with `index`, `resourceType`, `resourceUuid`, `date`), and disclaimer
- `error` — an error message if something goes wrong

Both search endpoints return a `questionId` (the audit log row ID as a string) that the frontend uses to submit user feedback.

#### Warmup endpoint

```
POST /ws/rest/v1/chartsearchai/warmup
{
  "patient": "patient-uuid-here"
}
```

Pre-warms the LLM prompt cache for a patient's chart so the first AI query on that patient skips full prefill cost. The frontend hits this when a patient chart is opened. Returns `202 Accepted` immediately; the warmup runs on a background daemon thread. Requires the same `AI Query Patient Data` privilege as the search endpoints. No-op when the engine is remote and when `chartsearchai.embedding.preFilter` is `true`. Disabled globally by setting `chartsearchai.warmupEnabled=false`.

#### Feedback endpoint

```
POST /ws/rest/v1/chartsearchai/feedback
{
  "questionId": "42",
  "rating": "positive",
  "comment": "Accurate and helpful"
}
```

Requires the `AI Query Patient Data` privilege. `rating` must be `positive` or `negative`. `comment` is optional (max 500 characters, control characters stripped). Users can only submit feedback on their own queries — requests for other users' queries return 404 to prevent information disclosure.

#### Audit log endpoint

```
GET /ws/rest/v1/chartsearchai/auditlog?patient=...&user=...&fromDate=...&toDate=...&startIndex=0&limit=50
```

Requires the `View AI Audit Logs` privilege. All query parameters are optional. `fromDate` and `toDate` are epoch milliseconds. Returns paginated results ordered by most recent first, with a `totalCount` for pagination.

### Guardrails

- **Input validation**: Patient UUID and question are required. Questions are limited to 1000 characters.
- **Prompt injection defense (two layers)**:
  1. **Structured-output constraint (primary defense)**: Both engines send `response_format: {"type": "json_schema", "strict": true, ...}` declaring the exact `{answer: string, citations: int[]}` shape — extracted into the shared `ChartAnswerResponseFormat` helper. The local llama-server enforces it by deriving a GBNF grammar from the schema internally. Remote OpenAI-compatible providers enforce it server-side; this is also what Anthropic's OpenAI-compat endpoint requires (it rejects the older `json_object` form with HTTP 400). Either way, the LLM cannot emit arbitrary text — even if a prompt injection manipulates the model's reasoning it cannot produce system information, execute instructions, or output anything outside the declared shape. This is the primary defense because it operates at the output level regardless of what the model "wants" to say.
  2. **Input regex filter**: Questions are checked against a regex pattern that rejects common prompt injection phrases (e.g., "ignore previous instructions", "you are now", "system prompt:"). Rejected questions return a 400 error without reaching the LLM.
- **AI disclaimer**: Every response includes a disclaimer stating the output is AI-generated and not a substitute for clinical judgment.
- **Answer caching**: An in-memory LRU cache (`ChartSearchServiceRouter`) stores recent answers keyed by `patientUuid::preFilter::pipeline::topK::similarityRatio::keywordWeight::scoreGapMultiplier::minScoreGap::gapValidationCosine::question`. The cache key includes all retrieval parameters so that changing any tuning setting correctly invalidates cached results. Configurable TTL via `chartsearchai.cacheTtlMinutes` (default 0 = disabled). When enabled, identical queries with the same parameters within the TTL window return the cached answer without invoking the LLM. The cache uses an access-ordered `LinkedHashMap` with a fixed maximum size, automatically evicting the least-recently-used entry when the size limit is exceeded. Expired entries are cleaned up periodically (every 10 cache puts) rather than on every access, to avoid scanning the entire cache on each insertion.
- **Serialized chart sourcing**: Querystore's `getPatientChart(patientUuid)` (querystore Decision 15) is the supported full-chart shape when `chartsearchai.querystore.enabled=true` (default). The read store keeps per-type indices current via the events-first sync pipeline, so the chart returns at index-read latency without round-tripping core or maintaining a parallel serialization cache. The legacy `chartsearchai.querystore.enabled=false` + `chartsearchai.embedding.preFilter=false` configuration falls back to `chartSerializer.serialize(patient)` per request — the 300–500 ms of OpenMRS DB roundtrips and Hibernate work are paid on every call, which is the migration's accepted operational cost on the legacy path. The pre-Decision-15 in-memory `ChartCache` that amortized this cost was removed once querystore became the supported full-chart shape; the AOP-driven invalidation overhead exceeded the savings on a per-call serialize. The LLM-side prompt-prefix cache (KV cache reuse on llama-server, provider-managed caching on remote) is unaffected and continues to shave prefill cost on follow-up queries against the same patient.
- **Rate limiting**: Configurable per-user rate limit (`chartsearchai.rateLimitPerMinute`, default 10). Set to 0 to disable.
- **Database audit logging**: Every query is recorded in the `chartsearchai_audit_log` table with:
  - The authenticated user and patient
  - The question asked and the LLM's response
  - The number of source references returned
  - The search mode used (`pre-filter` or `full-chart`)
  - Response time in milliseconds
  - Input and output token counts (for monitoring LLM usage and cost)
  - Timestamp
  - Optional user feedback: `rating` (`positive` or `negative`) and `feedback_comment` (free-text, max 500 characters)

  The audit log `id` is returned as `questionId` in search responses, allowing the frontend to link feedback to the original query via `POST /ws/rest/v1/chartsearchai/feedback` with `questionId`, `rating`, and optional `comment`. Feedback is stored on the same audit log row rather than in a separate table — this avoids schema bloat and keeps the query-feedback relationship as a simple column update. An ownership check ensures users can only submit feedback on their own queries.

  This audit trail supports compliance review (who queried which patient's data and what the AI responded), user feedback collection, and performance analysis. A scheduled task purges entries older than `chartsearchai.auditLogRetentionDays` (default 90 days, set to 0 to retain all).
- **Patient access control**: A `PatientAccessCheck` interface controls whether a user can query a specific patient's chart. The default implementation (`DefaultPatientAccessCheck`) permits all access — any user with the `AI Query Patient Data` privilege can query any patient. Deployments requiring patient-level restrictions (e.g., location-based or care-team-based) can override this by registering a custom Spring bean with id `chartSearchAi.patientAccessCheck`. This separates privilege-based access (handled by OpenMRS) from patient-level access (handled by the module).

## Decision 12: Concurrency model

### Constraint

Two pieces of native code constrain concurrent inference:

- **The embedded llama-server subprocess** runs with a single slot by default (via the `LocalLlmEngine` start arguments). Concurrent HTTP requests would serialise on that slot anyway, so we serialise client-side at the engine level for predictable queueing behaviour and to protect `LocalLlmEngine`'s own state (subprocess handle, idle timer, loaded model path).
- **The ONNX embedding model** runs in-JVM via ONNX Runtime, whose session object is not thread-safe — concurrent `embed()` calls can corrupt native memory.

To prevent corruption and keep behaviour predictable:

- `LocalLlmEngine.infer()` and `inferStreaming()` are `synchronized` — only one local LLM inference runs at a time. (The remote engine has no in-process state and lets the remote server handle concurrency, so its `infer` methods are not synchronised.)
- `OnnxEmbeddingProvider.embed()` and `embedQuery()` are `synchronized` — only one embedding computation runs at a time.

### Why `synchronized` instead of other concurrency primitives?

Java's `synchronized` keyword is the simplest correct choice here. A `ReentrantLock` with a bounded queue, a thread pool, or `CompletableFuture` would add complexity without benefit — for the local engine the fundamental constraint is that we run llama-server with one slot, so there is nothing to parallelise without rearchitecting both the subprocess startup and the KV-cache memory budget. A queue with position feedback (see Future options below) would improve the user experience for waiting requests, but the serialisation itself is intentional. For v1 targeting small clinics with 1–3 concurrent users, `synchronized` is sufficient and easy to reason about. Deployments needing higher concurrency on capable hardware can switch to the remote engine and put a multi-slot inference server (vLLM, llama-server with `--parallel`, Ollama) behind it.

### Impact on concurrent users

When multiple users submit queries simultaneously to the local engine, requests are serialised:

1. The first request acquires the engine lock and begins inference.
2. Subsequent requests queue on the `synchronized` block and wait.
3. Each request times out after `chartsearchai.llm.timeoutSeconds` (default 300s).

With an 8B model on CPU, a single query typically takes 15–45 seconds. This means roughly **2–3 concurrent users** can be served before requests start timing out. Smaller models (3B) are faster but produce lower quality responses; larger models (12B) have slower inference and reduce concurrency further.

Embedding computation is faster (~50–200ms per patient) so the embedding lock is rarely a bottleneck. The remote engine has no client-side serialisation — concurrency limits are whatever the remote server (vLLM, Ollama, OpenAI) imposes.

### Existing mitigations

- **Answer cache** (`chartsearchai.cacheTtlMinutes`): Identical (patient, question) pairs return cached results without acquiring the LLM lock.
- **Rate limiter** (`chartsearchai.rateLimitPerMinute`): Limits per-user query frequency, reducing queue depth.
- **Configurable timeout**: Prevents requests from waiting indefinitely.

### Future options (not yet implemented)

- **Multiple model instances**: Load the LLM into separate native contexts and round-robin across them. Trades RAM for throughput (each 8B instance adds ~6–8GB).
- **Request queuing with position feedback**: Return queue position to the client via SSE so the UI can show "you are #3 in queue" instead of hanging silently.
- **External inference server**: Offload to a dedicated inference server (e.g., llama.cpp server mode, vLLM, Ollama) that manages its own concurrency. This decouples the module from native memory constraints but adds an external dependency.

For the initial release targeting small clinics with low concurrent usage, the serialized approach is acceptable and avoids the complexity of managing multiple native contexts.

## Decision 13: Lucene BM25 as an alternative retrieval pipeline

### Context

The embedding pipeline (Decision 3) uses a custom scoring system: cosine similarity from an ONNX model combined with keyword matching, gap detection, and type boosting. This produces high-quality retrieval but requires downloading model files (~90MB ONNX model + vocabulary), and the custom scoring logic is complex with many tunable parameters.

Apache Lucene is already on the classpath — OpenMRS Platform bundles Lucene 8.11.2 via Hibernate Search 6.2.4. Lucene's BM25 scoring is a well-tested information retrieval algorithm that handles term frequency, document length normalization, and inverse document frequency automatically.

### Decision

Add Lucene BM25 as an alternative retrieval pipeline, selectable via the `chartsearchai.retrieval.pipeline` global property (`embedding` or `lucene`). Both pipelines coexist — no code is removed. The embedding pipeline remains the default.

**Lucene pipeline design:**
- **Shared index directory** at `<appDataDir>/chartsearchai/lucene-index/` with an `IntPoint` `patient_id` field for per-patient filtering.
- **`EnglishAnalyzer`** for both indexing and search, which includes Porter stemming — "conditions" matches "condition", "allergies" matches "allergy", etc.
- **Same prefixed text** as the embedding pipeline (e.g., `"Medical condition: Condition: Tuberculosis. Status: ACTIVE"`) so Lucene gets the same type signals.
- **Lazy indexing** — the Lucene index is built on first patient access, same as the embedding pipeline. AOP advice classes trigger incremental re-indexing for both pipelines when data changes.
- **No score cutoff** — all BM25 results up to `topK * 10` are returned. Lucene's BM25 naturally ranks relevant results higher, and the LLM handles moderate noise. This avoids reimplementing the embedding pipeline's gap detection logic.

**Why not replace the embedding pipeline?** The embedding pipeline captures semantic similarity that BM25 cannot. For example, the query "any cancer?" against a patient chart containing Kaposi sarcoma records returns zero results from Lucene — no record contains the literal word "cancer", so BM25 has nothing to match. The embedding pipeline finds the Kaposi sarcoma records because the embedding model understands the semantic relationship between "cancer" and "Kaposi sarcoma". Similarly, "any infections?" would find "tuberculosis" and "malaria" records via embeddings but miss them via Lucene. The Lucene pipeline excels at queries where the terms appear literally in the records (e.g., "any conditions?" matches all records prefixed with "Medical condition:"), but it fails on queries that require medical concept understanding. Both pipelines are kept so their retrieval quality can be compared on real patient data.

**Why Lucene 8.11.2 with `scope: provided`?** OpenMRS Platform bundles this version via Hibernate Search. Using the same version with `provided` scope avoids classpath conflicts and doesn't increase the module's `.omod` size.

## Decision 14: Elasticsearch hybrid search pipeline with RRF

### Context

The embedding pipeline (Decision 3) uses a custom scoring system with cosine similarity, keyword matching, gap detection, z-score gating, coherence filtering, and type boosting. While effective, this hand-rolled scoring logic is complex — many tunable parameters, subtle interactions between stages, and edge cases that require careful calibration. The Lucene pipeline (Decision 13) demonstrated that BM25 alone misses semantic matches: "any cancer?" returns nothing when the patient has Kaposi sarcoma records, because no record contains the literal word "cancer".

OpenMRS Platform 2.8+ supports Elasticsearch 8.17 via Hibernate Search, configured through the `OMRS_SEARCH=elasticsearch` environment variable which sets `hibernate.search.backend.type=elasticsearch` and `hibernate.search.backend.uris` in runtime properties. The low-level `elasticsearch-rest-client` is already on the classpath. A single-node instance is sufficient — multi-node clustering is not required.

Elasticsearch 8.14+ provides a native Reciprocal Rank Fusion (RRF) retriever that combines multiple ranking signals in a single query. RRF is an established algorithm: `score = Σ 1/(k + rank_i)` where `k` is a constant (typically 60) and `rank_i` is the document's position in each ranking. This fuses BM25 text search with kNN approximate nearest neighbor search without requiring custom scoring code.

**Important licensing constraint:** Elasticsearch's RRF retriever requires a paid Platinum or Enterprise subscription. **OpenSearch 2.19+ is the recommended alternative** because it provides RRF for free. The module auto-detects whether the backend is Elasticsearch or OpenSearch and adapts its queries accordingly. If neither a paid Elasticsearch subscription nor OpenSearch is available, the in-process hybrid pipeline (Decision 15) provides the same BM25 + kNN + RRF approach with no external dependencies.

### Decision

Add an Elasticsearch hybrid search pipeline as a third retrieval option (`chartsearchai.retrieval.pipeline=elasticsearch`). This pipeline:

1. **Indexes both text and vectors** — each patient record is stored as an Elasticsearch document with a `text` field (for BM25, using the `english` analyzer) and a `dense_vector` field (for kNN, using cosine similarity). The same prefixed text and embedding computation as the other pipelines is reused.

2. **Searches via RRF** — a single Elasticsearch query uses the retriever API with two sub-retrievers:
   - A `standard` retriever running BM25 on the `text` field (handles literal keyword matches)
   - A `knn` retriever running approximate nearest neighbor search on the `embedding` field (handles semantic matches)
   - RRF fuses the rankings: a document that appears in both rankings scores higher than one in only one

3. **Post-retrieval filter pipeline** (applied only to Elasticsearch RRF results, not to the base embedding pipeline) — Elasticsearch RRF handles scoring and fusion, but the kNN sub-retriever always returns its full `size` of results regardless of relevance. Unlike BM25 (which only returns documents containing query terms), kNN returns the *k nearest* vectors — and in a small patient chart, even the "nearest" vectors can be semantically unrelated to the query. RRF then ranks these low-quality kNN results alongside genuine BM25 matches, inflating the final result set with noise. Without post-retrieval filtering, a query like "latest blood pressure" could return 10 results where only 2 are actually about blood pressure, because 8 irrelevant records happened to be the nearest neighbors in embedding space. To address this, `LlmInferenceService.filterEsResults()` applies a post-retrieval filter pipeline to the RRF results: gap detection (large score drops between consecutive results), keyword scoring (exact query-term overlap), z-score gating (statistical outlier removal), and coherence filtering (topic-outlier removal via pairwise embedding similarity). This reuses the same filter logic as the embedding pipeline but applies it *after* Elasticsearch ranking rather than *instead of* it.

4. **Graceful fallback** — if Elasticsearch is not available (not configured or unreachable), the pipeline falls back to the embedding pipeline at query time. This makes it safe to set `pipeline=elasticsearch` even in environments where ES may be temporarily unavailable.

5. **Connection from runtime properties** — reads `hibernate.search.backend.uris` from `Context.getRuntimeProperties()` to find the ES instance. No additional configuration beyond what OpenMRS already provides for Hibernate Search.

**Why RRF over a weighted linear combination?** RRF is rank-based, not score-based. It doesn't require normalizing BM25 scores (which are unbounded) against cosine similarity scores (which range 0–1). This avoids the calibration problem that made the embedding pipeline's `keywordWeight` parameter sensitive to tune.

**Why not replace the embedding pipeline?** The embedding pipeline works without any external services — it runs entirely in-process with the ONNX model. The Elasticsearch pipeline requires a running Elasticsearch or OpenSearch instance, which not all OpenMRS deployments have. The embedding pipeline remains the default for self-contained deployments. For deployments that want hybrid search quality without running Elasticsearch or OpenSearch, see Decision 15 for an in-process alternative.

**Why `provided` scope for the ES REST client?** The `elasticsearch-rest-client` JAR is already on the classpath via `hibernate-search-backend-elasticsearch`. Using `provided` scope avoids bundling a duplicate in the `.omod` and prevents version conflicts.

## Decision 15: In-process hybrid pipeline (Lucene BM25 + embedding kNN with RRF)

### Context

Each existing retrieval pipeline has a blind spot. The Lucene pipeline (Decision 13) provides fast keyword search with no external dependencies, but misses semantic matches — "any cancer?" returns nothing when the patient has Kaposi sarcoma records because no record contains the literal word "cancer." The embedding pipeline (Decision 3) captures these semantic relationships, but misses exact keyword matches — a search for a specific drug name may rank semantically similar but wrong medications higher than an exact match. The Elasticsearch pipeline (Decision 14) solves both problems with hybrid RRF search, but requires a running Elasticsearch 8.14+ instance — a dependency that many OpenMRS deployments do not have, especially in low-resource settings where the platform runs with only MySQL.

This left deployments without Elasticsearch (or without a paid Elasticsearch subscription / OpenSearch instance — see Decision 14's licensing note) forced to choose between keyword-only or semantic-only retrieval, each with known failure modes that the other would catch.

### Decision

Add an in-process hybrid retrieval pipeline (`chartsearchai.retrieval.pipeline=hybrid`) implemented in `HybridRetriever`. This pipeline combines the existing Lucene BM25 index with the existing embedding kNN index using Reciprocal Rank Fusion, all running in-process with no external dependencies.

1. **Dual indexing** — `ensureIndexed()` ensures both the Lucene index (for BM25) and embedding index (for kNN) exist for the patient, creating either on demand if missing.

2. **RRF fusion** — both Lucene and embedding indexes are queried with a window size of 100 results each. The `fuseRRF()` method merges the two ranked lists using the same RRF formula as the Elasticsearch pipeline: `score = Σ 1/(k + rank_i)` with `k=60`. Documents appearing in both rankings score higher than those in only one.

3. **kNN fallback when BM25 returns nothing** — when the Lucene index has no keyword matches (e.g., a purely semantic query like "any cancer?"), the pipeline falls back to kNN-only results with additional quality gates:
   - **Z-score gating**: computes the similarity distribution across all patient embeddings and sets a dynamic floor at `mean + 2.5σ` (or the absolute minimum of 0.25, whichever is lower). This adapts to each patient's embedding distribution rather than using a fixed threshold.
   - **Adaptive fallback**: if z-score gating is too aggressive (fewer than `ADAPTIVE_MIN_RECORDS` survive), falls back to the absolute similarity floor.
   - **Coherence filtering**: removes topic outliers by computing pairwise embedding similarity among surviving results and dropping any result whose mean similarity to the others is below the group's threshold.

4. **Same retrieval interface** — the pipeline returns a `Set<String>` of resource keys (`type:id`), the same format as all other pipelines. The downstream LLM inference code does not need to know which pipeline produced the results.

**Why not just use the Elasticsearch pipeline?** The Elasticsearch pipeline requires a running Elasticsearch 8.14+ instance. Many OpenMRS deployments — especially in low-resource settings — run only the core platform with MySQL. The hybrid pipeline provides the same search quality (BM25 + kNN + RRF) using only in-process components (Lucene + ONNX embeddings + Java RRF implementation).

**Why RRF instead of a weighted linear combination?** Same reasoning as Decision 14: RRF is rank-based, not score-based, so it avoids the calibration problem of normalizing BM25 scores (unbounded) against cosine similarity scores (0–1).

**Benchmark comparison**: On a 153-record evaluation dataset, the embedding pipeline achieved 0.748 average recall while the hybrid pipeline achieved 0.659. The gap is due to the hybrid pipeline's fixed-size `topK` output — it always returns exactly `topK` records, which fails on adversarial queries (cannot return empty when no records match) and broad queries (e.g., blood pressure) where more than `topK` records are relevant. The embedding pipeline's adaptive filtering (gap detection, floor gates, type-aware expansion) handles these cases. The hybrid pipeline is still valuable for deployments that need both keyword and semantic matching without Elasticsearch, but the embedding pipeline is recommended as the default.

**Trade-off vs. Elasticsearch pipeline**: The in-process kNN search is exact (brute-force cosine similarity over all patient embeddings), not approximate. This is fine for typical patient chart sizes (hundreds to low thousands of records) but would not scale to corpus-wide search. The Elasticsearch pipeline uses approximate kNN via HNSW, which scales better for large indexes.

## Decision 16: LangChain / LangChain4j not adopted

### Context

LangChain (Python) and LangChain4j (Java) are popular frameworks that provide abstractions for RAG pipelines: document loaders, text splitters, embedding models, vector stores, retrievers, LLM clients, output parsers, and chain orchestration. Since this module implements a RAG pipeline, using one of these frameworks was evaluated.

### Decision

Do not adopt LangChain or LangChain4j. The module's purpose-built pipeline already covers every component these frameworks provide, with domain-specific optimizations that generic abstractions would make harder to maintain.

| LangChain concept | Existing implementation |
|---|---|
| Document loaders | `PatientRecordLoader` + per-type text serializers |
| Text splitting | Not needed — clinical records are discrete units |
| Embeddings | `OnnxEmbeddingProvider` (in-process ONNX Runtime) |
| Vector store | Hibernate-backed `chartsearchai_embedding` table |
| Retrievers | 3 pipelines with z-score gate, gap detection, type-aware expansion |
| LLM client | `LlmProvider` via the embedded llama-server |
| Output parsing | GBNF grammar constraint + JSON extraction |
| Prompt templates | System prompt with few-shot clinical examples |
| RAG chain | `LlmInferenceService.buildChart()` → retrieve → serialize → prompt → infer |

**Why not LangChain (Python)?** Would require a Python process or service alongside the JVM, complicating deployment beyond the "install the `.omod` plus the `.gguf` model" model. OpenMRS is a Java ecosystem — adding a Python dependency would significantly complicate deployment for the typical OpenMRS site.

**Why not LangChain4j?** Removes the language mismatch, but the module's custom retrieval logic (z-score gating for absent-data detection, adaptive gap detection, type-aware auto-expansion, GBNF constrained decoding) has no equivalent in LangChain4j's standard retrievers. Adopting LangChain4j would mean either losing these features or bypassing its retrieval abstractions entirely and using it only as a thin LLM client wrapper — not worth the dependency. The one feature LangChain4j would simplify — swapping cloud LLM providers via its `ChatLanguageModel` interface — can be achieved more simply by adding a provider interface to the existing `LlmProvider` if that need arises.

**When to revisit:** If the module needs agent/tool-use patterns for multi-step reasoning, a framework like LangChain4j may become worthwhile. Remote LLM backend support was added without LangChain4j (see [Decision 17](#decision-17-remote-llm-backend-support)). Until then, the purpose-built pipeline is simpler to deploy, easier to debug, has fewer dependencies, and gives full control over clinical-domain-specific scoring.

## Decision 17: Remote LLM backend support

### Context

The module was originally designed for local-only inference (GGUF models served by the embedded llama-server subprocess). This keeps patient data on the server and eliminates external dependencies. However, some hospitals have:

- **Insufficient hardware** for local inference (8B models need ~10 GB RAM, GPUs improve speed significantly)
- **Access to self-hosted GPU inference servers** (vLLM, Ollama, text-generation-inference) on a local network, or cloud APIs (OpenAI, Google AI, Anthropic) that provide faster, more capable models
- **Existing infrastructure** — a GPU server on the local network, or agreements with cloud providers that address data privacy and compliance requirements

### Decision

Add an `LlmEngine` interface with two implementations: `LocalLlmEngine` (drives the embedded llama-server subprocess) and `RemoteLlmEngine` (calls a remote OpenAI-compatible chat completions API). Selection is via the `chartsearchai.llm.engine` global property (`local` or `remote`). Local remains the default.

**Architecture:**

```
LlmProvider (orchestrator)
├── constructs system prompt + user message
├── delegates to active LlmEngine
└── parses JSON response (extractResponse)

LlmEngine (interface)
├── infer(systemPrompt, userMessage, timeout) → InferenceResult
└── inferStreaming(systemPrompt, userMessage, timeout, tokenConsumer) → InferenceResult

LocalLlmEngine (default)
├── Bundled llama-server subprocess (loopback HTTP), GGUF models
├── response_format: json_schema for structured output (GBNF under the hood)
├── Chat template read from the GGUF's tokenizer.chat_template metadata
└── Idle timer for subprocess lifecycle

RemoteLlmEngine
├── java.net.http.HttpClient (no new dependencies)
├── OpenAI-compatible /chat/completions endpoint
├── response_format: json_schema (shared with local engine)
└── SSE streaming support
```

**Why OpenAI-compatible API format?** It is the de facto standard. Self-hosted servers (vLLM, Ollama, text-generation-inference) and cloud providers (OpenAI, Google AI, Azure OpenAI) all support this format. A single implementation covers all of these.

**Why not add a dependency on an LLM client library?** Java's built-in `HttpClient` handles the OpenAI chat completions format in ~200 lines. Adding a library (LangChain4j, OpenAI Java SDK) would bring transitive dependencies into the OpenMRS module classloader for minimal benefit.

### Trade-offs

| Aspect | Local engine | Remote engine |
|---|---|---|
| Data privacy | Data stays on server | Self-hosted: data stays on local network. Cloud: data sent to provider |
| Latency | Higher (CPU inference) | Lower (GPU-accelerated inference) |
| Model capability | Limited by RAM (3B-27B) | Self-hosted: limited by GPU VRAM. Cloud: access to frontier models |
| Cost | Hardware only | Self-hosted: GPU hardware. Cloud: per-token API pricing |
| Availability | Always available | Self-hosted: always available on local network. Cloud: requires internet, subject to API outages |
| Setup | Download GGUF file | Configure endpoint URL, API key, model name |

### Configuration

| Property | Where | Description |
|---|---|---|
| `chartsearchai.llm.engine` | Global property | `local` (default) or `remote` |
| `chartsearchai.llm.remote.endpointUrl` | Global property | Chat completions URL (e.g. `http://localhost:11434/v1/chat/completions` for Ollama, `https://api.openai.com/v1/chat/completions` for OpenAI, `https://api.anthropic.com/v1/chat/completions` for Anthropic) |
| `chartsearchai.llm.remote.apikey` | Runtime property | Bearer token for authentication |
| `chartsearchai.llm.remote.modelName` | Global property | Model to request (e.g. `llama3.3` for Ollama, `gpt-4o` for OpenAI, `claude-opus-4-7` for Anthropic) |

**API key storage:** The API key is stored in `openmrs-runtime.properties` (a filesystem file), not in the database. This prevents exposure via the Admin UI, database backups, or SQL queries. This follows the same pattern OpenMRS uses for the database password. The endpoint URL and model name are stored as global properties since they are not secrets.

**Anthropic compatibility:** Anthropic's OpenAI-compat endpoint diverges from the de facto standard in two places that matter for this module: it rejects `response_format: json_object` (HTTP 400), so `RemoteLlmEngine` always emits the strict `json_schema` form (shared with the local engine via `ChartAnswerResponseFormat`); and on Claude Opus 4.7 it rejects `temperature`/`top_p` because Anthropic deprecated those samplers on that model. The engine handles this by sending `top_k: 1` instead — the only greedy-decoding lever Anthropic still accepts on the compat endpoint for Opus 4.7. Other Claude models (Opus 4.5/4.6, Haiku 4.5) and all non-Anthropic providers keep using `temperature: 0`. The branch is keyed on the model identifier, not on the endpoint URL, so users running Opus 4.7 through any OpenAI-compat shim get the same handling.

### Why only the LLM, not the embedding model?

The remote engine applies only to the generative LLM, not to the embedding model (all-MiniLM-L6-v2 ONNX). The embedding model is a fundamentally different situation:

- **Tiny footprint**: ~90MB on disk and minimal RAM, vs ~5GB+ for the LLM.
- **Fast on CPU**: Embedding computation takes milliseconds per record, vs seconds or minutes for LLM inference. No GPU needed.
- **High call volume**: Embeddings are computed for every patient record during indexing (potentially thousands per patient), not just once per query. Making these network calls would add significant latency to indexing and retrieval.
- **No hardware bottleneck**: The LLM justified a remote option because it requires large RAM (6–10GB+) and is painfully slow on CPU. The embedding model has none of these problems — it runs efficiently on any hardware that can run OpenMRS.

For deployments that cannot host even the 90MB ONNX file, the Lucene pipeline (`chartsearchai.retrieval.pipeline=lucene`) provides a zero-model-download alternative with BM25 text search.

## Decision 18: Cross-encoder reranking stage (superseded)

**Status: Superseded.** Implemented, benchmarked, and removed. See [Why it was removed](#why-it-was-removed) below.

### Problem (historical)

The bi-encoder retrieval pipeline (all-MiniLM-L6-v2) has a fundamental limitation: it encodes the query and each document independently, then compares their vectors via cosine similarity. This means the model cannot learn query-document relevance jointly — it can only measure how close a document's embedding is to the query's embedding in the shared vector space.

This causes two concrete problems:

**1. Template similarity inflates scores for unrelated records.** Medical records with the same template structure ("Condition: X. Clinical status: ACTIVE. Verification: CONFIRMED.") produce similar embeddings regardless of whether X is relevant to the query. For example, when a clinician asks "does she have any blood problems?", the embedding model scores Condition: Hypertension (maxCos to blood-related core = 0.66) higher than Obs: White Blood Cells (maxCos = 0.58) on inter-record cosine, even though WBC is blood-related and Hypertension is not. This happens because condition records share structural tokens ("Condition", "Clinical status", "ACTIVE") that dominate the embedding.

**2. Incidental keyword matches are semantically indistinguishable from genuine matches.** For the same "blood problems" query, "Arterial blood oxygen saturation (SpO2)" and "Systolic blood pressure" contain the keyword "blood" and score 0.35-0.36 semantically — nearly identical to genuinely relevant records like Haemoglobin (0.37). The bi-encoder cannot distinguish "blood" as a measurement medium (SpO2, BP) from "blood" as the clinical subject (anaemia, haemoglobin).

### Analysis: Why no bi-encoder metric can solve this

We exhaustively evaluated every available signal from the bi-encoder to find a data-derived threshold that separates relevant from irrelevant records. All failed:

| Metric | WBC (relevant) | Hypertension (irrelevant) | Can discriminate? |
|---|---|---|---|
| Max cosine to semantic core | 0.5782 | 0.6602 | No — HTN scores higher |
| Avg cosine to semantic core | 0.4294 | 0.4837 | No — HTN scores higher |
| Min cosine to semantic core | 0.3539 | 0.3586 | No — HTN scores higher |
| Semantic score (query cosine) | 0.4631 | 0.3543 | Yes — only discriminating signal |

Every inter-record cosine metric (max, avg, min to the semantic core) scores Hypertension *higher* than WBC because the embedding model conflates record-type similarity (condition-to-condition) with content similarity (blood-related-to-blood-related). The only signal that works is the semantic score (direct query-document cosine), where WBC (0.46) significantly outscores Hypertension (0.35).

This means the current pipeline must rely on a hand-tuned constant (`SEMANTIC_CORE_SCORE_RATIO = 0.80`) that defines a minimum semantic score as a fraction of the semantic core's lowest score. This constant has a tight valid range: for the test dataset where it was calibrated, values between 0.767 and 0.804 work — a margin of only 0.037. There is no way to derive this threshold from the data itself using bi-encoder embeddings alone, because the same fundamental limitation (independent encoding) prevents any adaptive approach from distinguishing template similarity from content similarity.

### Decision

Add a cross-encoder reranking stage between embedding retrieval and LLM inference.

A cross-encoder processes the query and document **jointly** through a single transformer pass, producing a direct relevance score. Unlike a bi-encoder, it sees both texts together and can learn that "blood" in "blood pressure" is a measurement context while "blood" in "blood problems" is a clinical subject. This is the industry-standard solution to bi-encoder limitations in RAG pipelines.

### How it works

```
Stage 1: Bi-encoder retrieval (existing)
  Query → embed → cosine similarity against all patient records
  → top-K candidates (e.g. 50-100 records)

Stage 2: Cross-encoder reranking (new)
  For each candidate: score = cross_encoder(query, candidate_text)
  → reorder by cross-encoder score
  → apply threshold or top-N cutoff
  → final candidate set (e.g. 5-15 records)

Stage 3: LLM generation (existing)
  System prompt + final candidates → LLM → answer with citations
```

The cross-encoder is computationally expensive (one forward pass per query-document pair), which is why it cannot replace the bi-encoder for initial retrieval over hundreds or thousands of records. But it is practical for reranking the top-K candidates (typically 10-100 documents), where it runs in milliseconds per pair on CPU.

### Why this solves both problems

1. **Template similarity**: The cross-encoder sees "does she have any blood problems?" alongside "Condition: Hypertension. Clinical status: ACTIVE." in a single pass. It learns that Hypertension is not a blood problem despite sharing the Condition template with Anaemia. A bi-encoder cannot do this because it never sees the query and document together.

2. **Incidental keyword matches**: The cross-encoder sees "blood" in context. "Arterial blood oxygen saturation" is about oxygen measurement; "Haemoglobin" is about blood composition. The joint encoding captures this distinction.

### What it replaces

The cross-encoder reranking stage can eventually replace the hand-tuned heuristics in the partial keyword match path:

- `SEMANTIC_CORE_SCORE_RATIO` (0.80) — the tight-margin constant that motivated this decision
- `SEMANTIC_CORE_MIN_COSINE` (0.55) — inter-record cosine threshold that fails for template-similar records
- Keyword rescue logic — the cross-encoder scores keyword-matched records directly
- Coherence gap detection — the cross-encoder provides a direct relevance signal, eliminating the need for indirect coherence-based filtering

These heuristics were necessary because the bi-encoder provides no direct relevance signal. The cross-encoder provides exactly that signal.

### Candidate models

| Model | Parameters | Size (ONNX) | Intended use |
|---|---|---|---|
| cross-encoder/ms-marco-MiniLM-L-6-v2 | 22M | ~85MB | General passage reranking, widely used baseline |
| BAAI/bge-reranker-v2-m3 | 568M | ~2.2GB | Multilingual, higher accuracy, larger footprint |
| cross-encoder/ms-marco-MiniLM-L-12-v2 | 33M | ~130MB | Slightly more accurate than L-6, still small |

The initial implementation should use **ms-marco-MiniLM-L-6-v2**: it is small enough to run on any hardware that already runs OpenMRS + the embedding model, ONNX-compatible (same runtime as the existing embedding model), and well-established in production RAG systems.

### Integration approach

The cross-encoder follows the same pattern as the existing embedding model:

- **ONNX Runtime** inference (already a dependency)
- **Global property** for model file path (`chartsearchai.reranker.modelFilePath`)
- **Optional stage** — if no reranker model is configured, the pipeline falls back to the existing heuristic filtering (no regression for deployments that don't download the reranker model)
- **Applies to all retrieval pipelines** (embedding, Lucene, Elasticsearch, hybrid) since it operates on the candidate set after retrieval

### Trade-offs

| Aspect | Without cross-encoder (current) | With cross-encoder |
|---|---|---|
| Model footprint | 90MB (embedding only) | 175MB (+85MB reranker) |
| Retrieval latency | ~50ms (cosine + heuristics) | ~150ms (+100ms for reranking 50 candidates) |
| Relevance accuracy | Good for exact matches, fragile for ambiguous queries | Robust for ambiguous keyword and template overlap |
| Maintenance burden | Hand-tuned constants with tight margins | Learned relevance signal, fewer magic numbers |
| Deployment complexity | One model file | Two model files |

The 85MB footprint increase is modest — comparable to the existing embedding model. The ~100ms latency increase for reranking is negligible compared to the 2-30 second LLM inference time that follows.

### Why it was removed

The cross-encoder was implemented (ms-marco-MiniLM-L-6-v2, 85MB ONNX) and benchmarked across 7 queries using a 160-record patient dataset. The benchmark compared four configurations: all-MiniLM bi-encoder alone, all-MiniLM + reranker, MedCPT bi-encoder alone, MedCPT + reranker.

**Finding: the reranker added no retrieval value.**

1. **No new relevant records surfaced.** The reranker can only reorder what the bi-encoder already retrieved — it never found records the bi-encoder missed.

2. **Reordering was inconsequential.** For most queries, the reranker shuffled order within an already-correct result set. The LLM sees all candidate records regardless of order, so reordering has no effect on answer quality.

3. **Active harm in one case.** For "blood pressure trend", the bi-encoder correctly retrieved 20 BP readings. The reranker's topN truncation cut this to 10, discarding half the clinically relevant data.

| Query | Bi-encoder results | + Reranker results | Reranker effect |
|---|---|---|---|
| "blood problems" | 7 relevant | Same 7, reordered | No change |
| "is the patient anemic?" | 3 relevant | Same 3, reordered | No change |
| "kidney function" | 2 relevant | Same 2, reordered | No change |
| "does she have diabetes?" | 3 relevant | Same 3, reordered | No change |
| "any infections?" | 4 relevant | Same 4, reordered | No change |
| "blood pressure trend" | 20 BP readings | 10 BP readings | **Worse** — truncated |
| "what medications is she on?" | 5 medications | Same 5, reordered | No change |

The cross-encoder was removed because it added 85MB of model footprint, ~100ms of per-query latency, and deployment complexity (a second model to download and configure) for zero retrieval improvement. The all-MiniLM-L6-v2 bi-encoder with adaptive filtering (IQR-based gap detection, semantic threshold filtering, z-score gating) handles the original motivating problems without a second model.

### MedCPT asymmetric bi-encoder (also evaluated and removed)

MedCPT (ncbi/MedCPT-Query-Encoder + ncbi/MedCPT-Article-Encoder) was also implemented as an alternative to all-MiniLM-L6-v2. MedCPT is an asymmetric bi-encoder trained on 18M PubMed query-article pairs, using separate encoders for queries and documents, CLS pooling, and 768-dimensional vectors.

**Finding: MedCPT produced dramatically worse results than all-MiniLM due to compressed score distributions that defeated adaptive filtering.**

MedCPT's scores cluster in a narrow range (IQR ~0.04) compared to all-MiniLM's wider spread (IQR ~0.10). This makes it impossible for the gap detector to distinguish relevant from irrelevant records — the gaps between them are smaller than the noise floor.

| Query | Dataset | all-MiniLM | MedCPT |
|---|---|---|---|
| "any blood problems?" | 1st (153 records) | 3 results, all Anemia diagnoses | 39 results, 34 noise (BP, SpO2) |
| "any blood problems?" | 4th (160 records) | 4 results (Haemoglobin, Haemorrhagic disease) | **160 results — entire dataset returned** |
| "Is she enrolled in any programs?" | 1st (153 records) | 1 result (PMTCT — correct) | 10 results, 9 noise |

MedCPT's theoretical advantage — medical synonym understanding (e.g. "blood problems" → Haemoglobin) — never materialized in practice because the compressed scores caused the pipeline to return everything, drowning relevant records in noise. all-MiniLM consistently returned small, precise result sets.

The asymmetric bi-encoder support (separate query/article encoders, CLS pooling, query encoder global properties) was removed to simplify the codebase. The module uses all-MiniLM-L6-v2 with mean pooling as its sole embedding model.

### Re-evaluation on the querystore path (May 2026)

After the querystore migration (Decision 22) routed retrieval through `openmrs-module-querystore` with `e5-base-v2` as the first-stage embedder, the cross-encoder rerank question was revisited. Three additional experiments ran against the locked widened 7-query rubric:

| Experiment | Setup | Mean P@5 | Δ vs no-rerank | Outcome |
|---|---|---|---|---|
| Exp 1' (2026-05-16) | MedCPT-Cross-Encoder over querystore | 0.743 | −0.171 | Reverted, not committed |
| Exp 2 (2026-05-17) | MedCPT bi-encoder as querystore embedder | 0.743 | −0.171 | Reverted, not committed |
| Exp 3 (2026-05-25) | BGE-reranker-v2-m3 INT8 over querystore + e5 | 0.571 | +0.057 | Reverted, not committed |

(Exp 3's no-rerank baseline measured 0.514 on the current smoke patient, vs the 0.914 in the original Decision 18 benchmark — same rubric, different patient data, since the standalone's demo patient has many "Enteroviral"/"Gonococcal"/"Zika virus" records that don't match the `\b...` word-boundary rubric.)

**Conclusion from Exp 3:** BGE-reranker-v2-m3 was the first cross-encoder to deliver a positive Δ on this corpus, but the gain (+0.057) is narrow, concentrated in a single query (infections rose 0/5 → 5/5 by surfacing `Temperature (c)` obs records from rank 31–60), and offset by regressions on cancer (−0.40) and kidney (−0.20) where the CE promoted surface-similar but category-wrong records (`Self-accusation`, `Melaena`, `Wasting syndrome` — the same surface-match failure mode that destroyed Exp 1 and Exp 1'). Doubling the candidate pool (`fetchMultiplier` 2 → 4, scoring 120 pairs instead of 60) gave **identical** results, confirming the limit is BGE's representation space, not first-stage recall: the kidney `Serum creatinine` record is already in the candidate pool, BGE just doesn't rank it as relevant to "kidney problems".

**Why generic-RAG rerank advice doesn't transfer to this corpus.** Standard RAG guidance ("rerank improves relevance, especially for huge corpora") assumes a setup we don't have: (1) huge corpus (10M+ docs vs our ~30–120 candidates per patient query), (2) weak first stage with high recall but noisy precision (vs our e5-base-v2 + BM25 hybrid that already concentrates the semantic signal), and (3) factoid queries with lexical overlap to relevant passages (vs our broad-category clinical queries like "any kidney problems?" that require domain knowledge like `creatinine = kidney function` which no generic CE carries). Generic CEs trained on web Q-passage pairs (MS-MARCO MiniLM, BGE) lack the clinical equivalences; clinical CEs trained on PubMed (MedCPT) carry article-co-occurrence priors that conflate categories ("Disorder of nervous system" promoted on psychiatric queries). Both failure modes show up in our data; both are structural, not tunable.

**Net status (May 2026): cross-encoder code is NOT in the tree.** The Exp 3 BGE prototype was implemented end-to-end (a `CrossEncoderReranker` Spring `@Component` wired into `QueryStoreChartBuilder`, four `chartsearchai.querystore.rerank.*` global properties, a `ai.djl.huggingface:tokenizers:0.30.0` dependency for XLM-R SentencePiece tokenization, an INT8 BGE-reranker-v2-m3 ONNX in the standalone's `appdata/chartsearchai/bge-reranker-v2-m3/`), benchmarked against the locked 7-query rubric, then reverted on the user's call because the +0.057 mean P@5 gain does not justify the 3–6 second per-query latency cost and the user-visible regressions on the cancer and kidney queries. No commits were merged. Future work that would unlock real rerank gains on this corpus lives at the indexing layer — injecting concept-set category hints into `QueryDocument.text` so the CE has explicit category signal for records that are otherwise semantically opaque (bare lab values, unmodified condition names) — not in swapping CE models. If a future contributor wonders why this module doesn't have rerank when the RAG literature recommends it: we tried four times, the gain ceiling is narrow for this clinical-retrieval setup over a strong hybrid first-stage, and the implementation-then-revert workflow is captured here so the next attempt can pick up from this baseline rather than rediscover it.

### Category-hint enrichment in indexed text (May 2026)

Picking up the "category hints in `QueryDocument.text`" thread the BGE diagnosis pointed at, a fifth experiment ran end-to-end against the same 7-query rubric:

**Experiment 4 — Category-hint enrichment at index time — MECHANISM VALIDATED, NOT SHIPPED (2026-05-25):**

- Implementation: new `ConceptCategoryHinter` utility in the querystore module (`org.openmrs.module.querystore.serialization`), with a hook in `AbstractRecordSerializer.appendCategoryHints` invoked by `Condition`/`Diagnosis`/`Obs` serializers. The hinter appended a bracketed suffix (e.g. `" [kidney, renal, nephrology]"`) to the indexed text when the concept's preferred name matched a hand-coded keyword pattern. Per-patient lazy re-projection (`QueryStoreService.bulkDeleteByPatient` + lazy `ensureIndexed` on next `searchByPatient`) refreshed the smoke patient's docs with hint-enriched text without a full bootstrap re-run. Chartsearchai's `QueryStoreChartBuilder` got a complementary `ChartSearchAiUtils.stripQuerystoreCategoryHints` pass to keep the internal taxonomy out of LLM-facing chart text.
- **Critical gotcha — user-facing taxonomy leak.** Without the stripping pass, the bracketed hint suffix flows through `QueryDocument.text` → `SerializedRecord` → `PatientChart` → LLM prompt → LLM answer, and the model echoes it back. Concretely, the kidney query's answer text contained `"…serum creatinine was 146.5 umol/L on 2023-05-04 (kidney, renal, nephrology)…"` — the LLM rephrased our internal `[kidney, renal, nephrology]` tag as a parenthetical clinical aside, exposing implementation detail to end users. The existing `ChartSearchAiUtils.stripCategoryHints` does NOT remove this suffix: it's designed for the legacy chartsearchai-embedding-pipeline format (`"hint1 / hint2 / body"` prepended) and matches on body-pattern anchors like `"Condition: "`, not on a trailing bracket. Any future revival of indexed-text category hints must ship its own strip method on the chartsearchai → LLM boundary OR pick a hint syntax that the existing strip already handles. A naive copy of the index-time hinter without a matching strip ships a UX regression that the smoke harness won't catch (the harness greps the hint-containing text, not the LLM output).
- Mean P@5 0.514 → **0.743** (Δ = **+0.229**) on the smoke patient. Concentrated wins: infections 0.00 → 1.00 (temperature obs surfaced under the infection hint), kidney 0.20 → 0.40 (BUN + creatinine surfaced under the renal hint), psychiatric 0.40 → 0.80 (substance-addiction records lifted under the mental-health hint). Cancer recovered from BGE's 0.40 regression back to 0.80. Zero regressions, zero per-query latency cost (hints are baked into the index, not computed at query time).
- **Why it was reverted anyway.** The +0.229 demonstrates that hint enrichment in indexed text is a real and effective mechanism, but the *source* of the hints was wrong: hand-coded keyword patterns in a Java class don't scale across deployments, locales, or evolving concept dictionaries. The architecturally correct source is OpenMRS concept-set membership (`Context.getConceptService().getSetsContainingConcept(concept)`), as already used by chartsearchai's legacy `ChartSearchAiUtils.extractCategoryHints` — but the demo standalone's data does not have its concepts categorized into clinically-useful sets (a Synthea-bootstrapped DB without CIEL set memberships imported). Shipping the hand-coded version as a stopgap was rejected because the maintenance burden (English-only patterns, per-deployment tuning, no admin UI) exceeds the experimental value.
- Code reverted on user authorization (2026-05-25). The `ConceptCategoryHinter` class, the `AbstractRecordSerializer.appendCategoryHints` hook, the three serializer call sites, the new `ChartSearchAiUtils.stripQuerystoreCategoryHints`, and the temporary `[QSEVAL]` retrieval-debug log are all out of the tree. The standalone's Lucene index was wiped to drop the hint-enriched text; the lazy `ensureIndexed` path rebuilds on next patient access.

**Diagnosis (post-4):** category-hint enrichment in indexed text is a viable and substantial retrieval improvement (+0.229 P@5 demonstrated), but production deployment requires CIEL-style concept-set data so the hint source can be authoritative metadata rather than hand-coded Java strings. The mechanism is **architecturally ready**; the **data prerequisite is not met** on the demo standalone and likely on many real-world OpenMRS deployments that haven't imported CIEL set memberships. The next attempt should either (a) treat the missing concept-set data as a data-administration task to fix at deployment time (then ship a concept-set-driven hinter that reuses `getSetsContainingConcept`), or (b) ship a per-deployment configurable hint map (loaded from a GP-referenced YAML/JSON) so operators can extend without code changes. Hand-coded keyword maps in Java are not the right shape.

**Experiment 5 — Concept-description indexing as a BM25-only retrieval signal — SHIPPED upstream in querystore (2026-05-25):**
- Approach: extended querystore's serializers to extract each concept's free-text description (locale-resolved via `ConceptNameUtil.getDescription`) into a new `description` metadata field. Lucene and Elasticsearch backends both index it as a top-level text companion of the existing `text` field with a sub-1.0 boost (`QueryStoreConstants.BM25_DESCRIPTION_BOOST = 0.5`). Deliberately NOT added to `QueryDocument.getEmbeddingInput()` — keeps the embedding clean and avoids the asymmetric-bias concern documented in `ChartSearchAiUtils.extractCategoryHints`. Chartsearchai is unchanged; the description stays in the metadata channel and never reaches the LLM, so the user-facing taxonomy-leak class that ended exp 4 is structurally impossible.
- Result: kidney query 0.20 → **0.40** on the smoke patient (`Serum creatinine` rank 3 → rank 1, `Blood urea nitrogen` rank 30+ → rank 2 — both because their CIEL descriptions explicitly mention "kidney function" / "kidney status" while their preferred names don't). Smoke-only mean P@5 0.514 → 0.543 (Δ +0.029 by the locked rubric). A subsequent 4-patient eval (28 queries) showed the smoke-only number was unrepresentative: aggregate P@5 actually went 0.657 → 0.636 (Δ -0.021). The drop is **rubric bias, not clinical degradation**: description indexing pulls related test orders (`Widal test`, `Urinalysis order`) into top-5 because their descriptions name the relevant condition — the regex word-boundary rubric marks them as misses, but the LLM correctly excludes them from its answer. The actual clinical win is qualitative and held across patients: Mark Smith's kidney answer went from wrong (citing "Body fluid pH: 2.3, Infection of intravenous catheter") to right (citing BUN + creatinine); Richard Jones's kidney answer added lab-value citations that the baseline didn't surface. LLM responses contain no internal-taxonomy text.
- Why this generalises where category-hints didn't: the vocabulary source is **data already in the OpenMRS database** that dictionary maintainers (CIEL, AMPATH, Bahmni) curate as part of their normal work. 42% of concepts in the demo standalone have descriptions; coverage scales with the deployment's dictionary quality, not with hand-coded Java strings. No new data to maintain. Multilingual by construction (descriptions are locale-tagged).
- **Code lives in the querystore module**, not chartsearchai. The authoritative documentation is `querystore/docs/adr.md` Decision 6's Synonyms-and-group-obs convention (description added as a peer of synonyms, with the same "BM25-indexed companion, excluded from embedding input" contract). Chartsearchai requires zero changes — the existing `QueryStoreChartBuilder` reads `QueryDocument.text` (unchanged) and the description rides through `QueryDocument.metadata` (unread by chartsearchai).

**Experiment 6 — Two additional small-LLM retrieval enrichments — SHIPPED then REVERTED (2026-05-25):**
- Approach (a): append abnormal-range flags (e.g., `"(HIGH; normal 60-115)"`) to numeric obs text whose `ConceptNumeric` reference ranges are populated and whose value falls outside them (querystore commit `53b1117`). The intent was to give small LLMs a structured cue for abnormality reasoning instead of expecting them to know reference ranges.
- Approach (b): at query time, prepend per-concept trend syntheses (`"<concept> trend: count N over date-range; range MIN-MAX; last LAST"`) for repeated numeric obs with ≥5 readings (querystore commit `8554b82`). The intent was to give small LLMs a single-line summary instead of expecting them to aggregate N individual readings.
- Multi-patient eval finding: across 4 patients × 7 queries × 5 top hits = 28 LLM answers inspected, **the LLM never cited a single abnormal-flag suffix and never cited a single trend-synthesis line**. Grep for `(HIGH`, `(LOW`, `(CRITICAL`, `; normal `, `trend:`, `; range `, `; last <num>` across all 28 answer bodies returned zero hits. Both features were occupying chart context (12-21 abnormal-flagged records per patient; 3 trend-synthesis prepends taking top-K slots per query) while contributing nothing to the LLM's answer.
- Aggregate P@5 with the features ON: 0.636. With them OFF (description indexing alone): 0.636. Same retrieval quality, 464 lines less code.
- Reverted at commits `89080d6` (trend syntheses) and `74d7308` (abnormal flags). Description indexing (`a8db30e`) stays.

**Diagnosis (post-6):** the right retrieval-improvement lever for this corpus is **vocabulary enrichment from existing concept-dictionary data on the BM25 channel** — not post-retrieval CE rerank, not hand-coded keyword maps, not embedding-input augmentation, not text-suffix enrichment the LLM ignores. The mechanism (additional BM25 text on a separate channel) is the same as category-hints; the source is what changed. Coverage is patchy (records whose concepts lack descriptions get nothing) but is monotonically improving as CIEL/OCL dictionaries are tightened — and the failure mode of a missing description is "no improvement vs baseline," not "regression." Exp 5 is the only experiment in this Decision-18 sequence to ship and stay.

**New evidentiary bar (post-6):** future small-LLM retrieval claims must show **LLM-citation evidence**, not just rubric P@5. A feature that doesn't appear in the LLM's actual cited records across a multi-patient eval is dead weight regardless of what regex P@5 says. The smoke-only P@5 win on the smoke patient (4acc0b80) is necessary but not sufficient — multi-patient generalisation and LLM-answer inspection are the bar.

**Experiment 7 — Concept reference-term mapping names indexed as a BM25-only retrieval signal — SHIPPED upstream in querystore (2026-05-25):**
- Approach: extended querystore's serializers to extract reference-term names from `Concept.getConceptMappings()` (LOINC's `Urea nitrogen [Moles/volume] in Serum or Plasma`, ICD-10's `Chronic kidney disease, unspecified`, PIH's `Chronic kidney disease`, WHO-ATC names, CIEL drug-class parents like `Heparins`) into a new `mapping_names` metadata list. Both Lucene and Elasticsearch index it as a top-level BM25 companion of `text` with a sub-1.0 boost (`BM25_MAPPING_NAMES_BOOST = 0.5`, shared via `QueryStoreConstants` so the two backends can't drift). Same architectural shape as exp 5: NOT added to `QueryDocument.getEmbeddingInput()`, NOT in the stored `text` chartsearchai reads, so the user-facing taxonomy-leak class that ended exp 4 is structurally impossible. Per-concept cap at `MAX_MAPPING_NAMES = 10`; retired terms and null/blank names dropped; alphabetically sorted for indexer determinism.
- Why it generalises further than exp 5 will: the vocabulary source is **external-authority dictionary maintainers** (SNOMED, LOINC, ICD-10, ICD-11, RxNorm, WHO-ATC, CIEL drug-class parents) curating names for the *target* concepts of mappings. Orders of magnitude more terminology labour than CIEL's own description authors. Coverage on a CIEL-imported deployment is ~95% of concepts having at least one mapping — much higher than description's ~42%. Multilingual is implicit (each `ConceptSource` is locale-tagged), so deployment locales other than English benefit without code changes.
- Data prerequisite & demo workaround: `concept_reference_term.name` must be populated. CIEL's official MRS-format dump (downloaded by the openmrs-module-openconceptlab subscription module) ships these names; Synthea-bootstrapped demo deployments don't (`name = NULL` across all 17,037 rows on the reference standalone). For this Decision's eval the demo was backfilled from the 25 OCL collection packages already on disk under `appdata/configuration/ocl/`: a one-time script joined `(to_source_name, to_concept_code) → to_concept_name_resolved` and `UPDATE`-d the demo's `concept_reference_term.name` for 6,574 rows across CIEL (3,688), ICD-10-WHO (1,800), PIH (618), LOINC (318), WHO-ATC (150). SNOMED's ~17.5% of mappings contribute no signal because OCL licensing prevents redistributing SNOMED's PT/FSN — that subset is silently no-op rather than harmful.
- Result on the same 4-patient rubric: aggregate P@5 0.636 → 0.621 (Δ -0.015 by the locked rubric). The drop is the same rubric-bias pattern exp 5 exhibited: mapping names bring clinically-related-but-rubric-invisible records into top-5 (e.g. "Painful Urging to Urinate" on a kidney query). LLM answers on rubric queries are unchanged in quality. The concrete win is on category-vocabulary queries the rubric doesn't measure: Richard Jones, query `"chronic kidney disease unspecified"` — a pure ICD-10 phrase whose tokens appear in zero concept's stored `text` — returns the `Chronic kidney insufficiency` Condition/Diagnosis pair at ranks 1–2 via the indexed `mapping_names` blob alone. LLM cites both records correctly. This kind of category-word query was structurally unreachable before the slice.
- **Code lives in the querystore module**, not chartsearchai. The authoritative documentation is `querystore/docs/adr.md` Decision 6's `Mapping names` paragraph + re-bootstrap advisory. Chartsearchai requires zero changes — `QueryStoreChartBuilder` reads `QueryDocument.text` (unchanged) and `mapping_names` rides through `QueryDocument.metadata` (unread by chartsearchai). LLM citations remain anchored on the preferred name and never carry SNOMED/LOINC/ICD verbiage. Two hardening cycles on the slice landed at commits `c5f520b` (initial slice) and `1ecff93` (contract tests, boundary tests, ADR doc rigor); 460 querystore tests pass.

**Diagnosis (post-7):** the BM25 vocabulary-enrichment lever has now been proven on two independent signals from the same architectural shape — `description` (exp 5) and `mapping_names` (exp 7). Both ship clean, both preserve LLM citation hygiene, both degrade gracefully to no-signal on concepts that lack the underlying data, both bring concrete query types into reach (category-word, external-vocabulary) that the preferred-name-only retrieval couldn't answer. The rubric's regex word-boundary patterns systematically underestimate gains from this class of enrichment because the value lives in answer *quality* (right records cited) not match *count* (top-5 regex hits). Future Decision-18 entries should default to this pattern — additive per-concept BM25-only enrichment from dictionary-resident data — and resist the gravity well of LLM-context enrichment (exp 6 trajectory) or hand-coded knowledge bases (exp 4 trajectory).

**Rubric extension (post-7, 2026-05-25):** the 7-query natural-language smoke set used through exp 5 and 7 systematically under-credits the BM25 companion channels because most preferred names already carry the query words ("kidney problems" → "Chronic kidney insufficiency" matches via the `text` channel alone). Three external-authority variants were appended to the rubric to exercise the new channels directly: `chronic kidney disease unspecified` (the ICD-10 name for the kidney concept), `renal failure` (semantic synonym requiring description/mapping_names to bridge), and `essential hypertension` (the ICD-10 name for hypertension). Same regex as the natural-language counterpart for each — a true positive is "any clinically relevant record, regardless of which channel surfaced it." 4-patient calibration at exp-7 ship state landed at mean P@5 of 0.90 (`essential hypertension`), 0.25 (`renal failure`), and 0.15 (`chronic kidney disease unspecified`). The wide variance is itself a property worth naming: `essential` is a rare token that only the ICD-10 mapping name carries, so the BM25 lift is unambiguous; `chronic` and `disease` are everywhere, so BM25 OR-matching crowds top-5 with `Chronic gingivitis`, `Cardiovascular disease`, `Chronic intractable pain` records that share tokens but not intent — the LLM correctly filters these but the regex rubric can't. The 10-query aggregate is 0.565, lower than the 7-query aggregate of 0.621 because the kidney variants are harder; that's the rubric working as designed, not a regression. Going forward `essential hypertension` is the clean win-detection signal for external-vocabulary retrieval gains; the two kidney variants serve as regression sentinels with a soft tolerance for BM25 token-frequency noise. Querystore tooling lives in `/tmp/qs_fire_queries.sh` (10-query firer) and `/tmp/qs_multi_patient_eval.py` (regex scorer).

**Small-LLM benefit, empirically verified (post-7, 2026-05-25):** the entire Decision-18 sequence is motivated by improving small-LLM retrieval quality (the demo's deployed model is Gemma 4 E4B per `chartsearchai.llm.modelFilePath`), so the question that closes the loop is whether the slices we kept (exp 5 description + exp 7 mapping_names) actually benefit small LLMs *more than* they benefit large LLMs — not just whether retrieval P@5 moved. Ran a 3-model × 2-state A/B on patient `61d0a9db` (Mark Smith), query "any kidney problems?" — the documented headline case where the relevant lab records (creatinine, BUN) sit at rank #28+ without enrichment and bubble up to top-5 with it. Models: Gemma 4 E2B (local), Gemma 4 E4B (local, the demo's default), `anthropic/claude-opus-4.7` via OpenRouter (with `chartsearchai.llm.engine` flipped to `remote` and an API key in `openmrs-runtime.properties` under `chartsearchai.llm.remote.apikey`). States: enrichment OFF (surgical revert of `putDescription` + `putMappingNames` calls in `AbstractRecordSerializer`/`AllergyRecordSerializer`/`PatientProgramRecordSerializer`, Lucene wipe, reindex) vs ON (current). Result: *both Gemma small models flip from clinically wrong to clinically correct between OFF and ON* — E2B drops Body fluid pH 2.3 and Uric acid from its cited list and replaces them with creatinine + BUN; E4B stops citing IV catheter infection as a kidney finding and lands on creatinine + BUN. *Claude Opus 4.7 is correct in both states* — even with creatinine buried at rank #28 it finds it, adds clinically appropriate context (bacteriuria, urinary incontinence, urine microscopy), and honestly notes "no records explicitly document a kidney disease." The differential is concentrated entirely on the small models: small models' lift from enrichment is wrong → right, large model's lift is right → still right. This is the small-LLM-specific benefit the architectural argument predicts — large models compensate for noisy retrieval by reading deeper into the top-30 chart and reasoning through it; small models can't. Caveat: N=1 query on N=1 patient; the pattern is consistent with the architectural reasoning but a single data point isn't a generalization. Worth memorializing because it directly answers the workstream's motivating question and predicts the same shape for any query whose relevant records would land at rank 20+ without enrichment.

**Model selection (post-7, 2026-05-26):** with retrieval-side improvements proven beneficial for small LLMs, the natural follow-on is whether the model itself could be swapped for a better small-LLM option without changing the substrate. MedGemma-4B (Google's clinically-tuned small Gemma variant, on disk at `appdata/chartsearchai/models/MedGemma-4B/medgemma-4b-it-Q4_K_M.gguf`) was the obvious candidate — same parameter count as the Gemma 4 E4B baseline, trained on medical-domain text. Head-to-head on 5 queries spanning chart-search shapes (kidney lookup, multi-cancer recall, infections, psychiatric focused query, ICD-10-phrased kidney query) shows MedGemma is *not* a drop-in upgrade — it's a *different-tradeoff* model. Better broad-recall: on richard_jones "any cancer or tumor?" MedGemma cited 7 distinct cancer records (astrocytoma, parotid, breast, prostate ×2, accessory sinus ×2) where E4B cited 2-3; the clinical-tuning surfaces more terminology associations. But the same confident clinical voice produces *more cross-domain false positives* — MedGemma listed "Disorder of thyroid" and "Graves' disease" as psychiatric findings on mark_smith (thyroid disorders cause psych symptoms but aren't themselves psych conditions), and added "Lipid proteinosis" (a rare skin/larynx disorder) to the answer for `chronic kidney disease unspecified` on richard_jones. The vanilla Gemma 4 E4B is more conservative — sticks to what's literally in the records, doesn't reach for adjacent-domain inferences. For chart-search-for-clinical-decision-support — the use case where the user may paste the answer into a chart note or act on it — conservative is safer; false-positive citations cost more than incomplete recall. **Demo default stays Gemma 4 E4B.** MedGemma's GGUF remains on disk for future use cases where broad recall outweighs precision (oncology-heavy specialty clinics, pipelines where the user post-filters). The same trade-off should be measured before adopting any future domain-tuned small model.

**Cross-family check, refining the small-LLM-benefit claim (post-7, 2026-05-26):** the small-LLM A/B above was validated on the Gemma family (E2B and E4B). The natural follow-on is whether the benefit transfers to other small-LLM families at the same size class. Same 4-query 2-state A/B against Llama-3.2-3B-Instruct (Meta, ~3B params, on disk at `appdata/chartsearchai/models/Llama-3-2-3B/Llama-3.2-3B-Instruct-Q4_K_M.gguf`) — same enrichment ON/OFF toggle via the surgical revert of `putDescription`/`putMappingNames` in the three serializers. Result: **the benefit does not transfer cleanly.** Llama 3.2 3B produces clinical errors in *both* states. On mark_smith kidney it correctly cites creatinine + BUN under enrichment ON but conflates BUN's `112.0 mmol/L` value as a second creatinine reading (unit-confusion error neither Gemma variant produced). On the broad-recall `any cancer or tumor?` query it cites `proctoptosis` (rectal prolapse) as a cancer in BOTH states, and adds `anal polyp` to the false-positive list under enrichment ON — a regression. On `chronic kidney disease unspecified` it lists `Disorder of nervous system` as kidney-related under OFF and adds `Lipid proteinosis` plus `Fall` under ON — also a regression. The pattern: Llama 3.2 3B's failure mode is over-association across whatever records are in the chart, **independent of which records those are.** Switching the records (which is what enrichment does) shifts which incorrectly-cited records appear but doesn't cure the hallucination. The refinement: the slices help small models **only when their bottleneck is the retrieval substrate** (Gemma family — limited by what records they see). When the bottleneck is the model's own reasoning quality (Llama 3.2 3B — over-associates terminology), enrichment can change the failure mode without reducing it, and on broad-recall queries actively regress. Model-swap decisions on this project must A/B against the enrichment substrate; the Gemma-validated benefit doesn't transfer by default to other small-LLM families.

**Tighter topK tested and rejected (post-7, 2026-05-26):** the hypothesis was that the demo's small model (Gemma 4 E4B) might focus better with a shorter chart — fewer records = less noise. `chartsearchai.querystore.topK` was lowered from 30 → 20 (GP-only change, no code, no reindex) and the locked 10-query × 4-patient rubric re-fired. P@5 essentially unchanged (0.565 → 0.570) because the rubric measures top-5 and is structurally blind to topK ≥ 5; the relevant differential is in the LLM-answer content since the LLM's chart context shrinks from 30 to 20 records. Side-by-side diff across 36 query-patient pairs: 9 identical, 27 changed but the vast majority of changes are cosmetic phrasing variability ("is" vs "was", sentence restructuring) indistinguishable from LLM-temperature noise. The substantive deltas split asymmetrically: 3 "lost a finding" regressions (smoke essential-hypertension stopped citing BP values, karen_sanchez cancer dropped Cestode Infection alongside Haemangioma, mark_smith renal stopped noting bacteriuria) versus 1 "cleaner opening" improvement (mark_smith infections led with "Fever lasting more than three weeks" instead of IV catheter infection). The Gemma 4 E4B with the enrichment substrate active was already focusing well on the top-of-chart records — it wasn't visibly distracted by records 21-30 in the first place, so tightening the budget mostly removed long-tail clinically-relevant context (the same asymmetric cost the adaptive-topK revert exhibited earlier in this session). **Restored to topK=30.** Note this is the *opposite* of what one might expect from the broader small-LLM context-window literature; the explanation appears to be that exp 5 + exp 7 have already done the work of ranking-clean retrieval in the top-of-chart slots, so the model isn't substrate-bound enough to gain from further pruning. Re-test if the embedding model or BM25 boosts change such that top-30 ranks degrade.

**System prompt experiment, tested and rejected (post-7, 2026-05-26):** the `chartsearchai.llm.systemPrompt` GP is `NULL` by default, falling through to `LlmProvider.DEFAULT_SYSTEM_PROMPT` which already instructs "Never infer, assume, or add information not explicitly stated in the records" + "Cite EVERY record you reference" + JSON-output formatting. The hypothesis was that a more aggressive small-model guardrail prompt — adding explicit don't-cite-keyword-overlapping-records language with concrete negative examples ("on a kidney query do not cite `Lipid proteinosis`, `Fall`, `Fear of medical care`") plus an "honest-absence" rule — would reduce the cross-domain false positives MedGemma and Llama exhibited. Tested at Gemma 4 E4B on 5 queries spanning E4B's behavior space. Result: citation hygiene marginally improved on 4 of 5 queries (proper `[N]` brackets vs occasional `(N)`, clinical-certainty surfacing on the psychiatric query, slightly more compact answers), but the tightened prompt *introduced* a false positive on richard_jones cancer — cited "Anal polyp" alongside the 7 real cancers, exactly the kind of recall-over-precision failure it was trying to prevent. The tension between rule 1 ("Cite EVERY record that directly answers") and rule 2 ("Direct-relevance only") resolved toward broader inclusion on broad-recall queries. Further iterations (more restrictive rule 1, few-shot examples, per-query-type prompts) would likely surface the same trade-off in different cells of the matrix. The default's "Never infer + Cite ALL relevant" is already a reasonable equilibrium for the Gemma family on this corpus. **Restored to NULL (default active).** Re-test if a new small-model family is adopted (e.g. a future Gemma 5 or a non-Gemma small model the project decides to swap to) — the prompt's interaction with model behavior is family-specific.

**Score-gap truncation tested and rejected, with a mechanism (post-7, 2026-05-26):** the earlier flat `topK=20` reduction was rejected because it dropped long-tail clinically relevant records uniformly. The hypothesis here was a *careful* adaptive version: truncate top-K only when there's a wide score gap between consecutive ranks (a "natural cliff") that separates strongly-relevant records from noise-floor records. Instrumented querystore's `runHybrid` to log per-hit hybrid-fusion scores; ran the 10-query × 4-patient rubric; analyzed score distributions. Three regimes emerged cleanly: (1) **strong early cliff** on most queries — top-1-or-2 records score ~0.032, then a gap of ~0.013-0.017 drops to noise floor ~0.015. Many queries have only 1-3 strongly relevant records. (2) **Sustained relevance** for blood-pressure queries — scores stay high through rank 20-26 because many BP records are genuinely all relevant. (3) **No signal** when the patient lacks records matching the query — top-1 is at noise floor with no cliff at all. Simulated a careful gap detector (absolute threshold 0.005 + relative threshold 15% of top-1, minimum floor of 3 records): would fire on 28 of 40 queries (70%), dropping average chart size from 30 to 13. A/B-tested 4 queries at the algorithm-picked K: 2 regressions, 2 equivalent. The headline regression is informative: **mark_smith kidney at K=3 — with `Serum creatinine 120.9 umol/L` literally at rank #2 — the LLM said "There are no records of kidney problems."** With topK=30 the same model correctly cites creatinine + BUN. At K=3 the chart contains `[1] Diabetic foot ulcer, [2] Serum creatinine, [3] Living in residential institution`; one kidney-related record sandwiched between two unrelated ones. The model defaulted to "no kidney records" because the chart *as a whole* looks mostly non-kidney. The mechanism: **Gemma 4 E4B is redundancy-bound, not noise-bound.** It needs ≥2-3 confirming records to commit to a topic — a lone signal between unrelated records gets discounted. The substrate work (exp 5 + exp 7) succeeds *precisely* because it brings lab + condition + diagnosis records together into the top-of-chart, building the redundancy small models need to recognize the topic. Truncating the chart removes that redundancy and breaks the substrate's mechanism of action. This is the *opposite* of the "tighter context = less noise = better focus" hypothesis broader small-LLM literature suggests; the explanation appears to be that small clinical LLMs on chart-search tasks rely heavily on cross-record confirmation rather than per-record reasoning. **Don't truncate.** The full topK=30 is keeping the redundancy that lets exp 5 + exp 7 work as designed. Verified the redundancy-bound finding generalises across the Gemma 4 small-model family: at K=3 both Gemma 4 E2B *and* E4B respond with the same flat "There are no records of kidney problems" denial — same chart, same failure. Claude Opus 4.7 at the same K=3 with the same 3-record chart correctly extracts the urinary-incontinence record at rank 2 and gives a nuanced answer ("no kidney-specific diagnosis noted") — large models can reason carefully from sparse context where small models discount lone signals. **The redundancy-bound mechanism is small-model-specific**, not a universal property of LLMs on this task. If a future small-LLM that uses single-record reasoning (e.g. a chain-of-thought-tuned model) replaces the Gemma 4 family, re-test — the finding is specific to the current small-model class.

The asymmetry is worth naming separately: **going lower than topK=30 catastrophically loses redundancy** for small models (the K=3 finding above); **going higher than topK=30 is asymmetrically safe**. Tested at topK=40 across the full 10-query × 4-patient rubric: aggregate P@5 unchanged (0.565 → 0.560, within stochastic LLM-temperature noise), and the LLM-answer diff shows ~7 real improvements (Ascaris infection cited on richard_jones; concrete GFR data point added on karen_sanchez chronic-kidney query; clinical-certainty/status detail surfaced on several condition queries) vs ~3 real regressions (one cross-domain false positive on karen_sanchez renal where K=40 cited "Right heart failure" as a kidney-relevant finding, plus two small completeness losses). Net mildly positive but not slam-dunk shippable. **topK=30 stays as the demo default** — the additional context at K=40 doesn't reliably outweigh the occasional cross-domain false positive it admits. The operational signal is that the floor matters (don't go below ~15-20) far more than the ceiling does; the substrate's top-of-chart redundancy is doing the work, and deeper records contribute marginally and asymmetrically.

**Known gap: chart-summary queries (global queries) are unsolved (post-7, 2026-05-26):** the entire Decision-18 workstream has been about *local* queries — focused asks like "any kidney problems?" or "any allergies?" that fit in top-K retrieval. The substrate work (exp 5 + exp 7) + the redundancy-bound finding apply to that query class. A separate query class is **global / summarization** — "summarize this patient", "give me an overview", "what are this patient's major medical issues" — where the LLM needs the corpus as a whole, not a top-K slice. The current system fails catastrophically on these: tested on Richard Jones (560+ records — 418 obs + 71 conditions + 71 diagnoses), "patient chart summary" returns a random list of pulse measurements; "summarize this patient" cites Anorexia nervosa + Disorder of nervous system + pulse readings while missing the actual major findings (Chronic kidney insufficiency, multiple cancers, schizoaffective disorder). Worse than refusing the query because the small LLM produces confident wrong summaries.

The canonical RAG-literature solution for this class is **Microsoft's GraphRAG** (Edge et al. 2024, *"From Local to Global: A Graph RAG Approach to Query-Focused Summarization"*, github.com/microsoft/graphrag). The approach: at index time, build an entity-relationship graph from the corpus, run community detection (Leiden clustering), have an LLM summarize each community at multiple resolution levels. At query time, route local queries through standard vector retrieval and global queries through map-reduce over community summaries. The mechanism that makes global queries work is that community summaries are *first-class retrievable entities* — the system isn't trying to summarize at query time, it's retrieving pre-computed summaries that cover the corpus structurally.

GraphRAG's typical-corpus cost (news articles, research papers) is dominated by LLM-based entity extraction — one LLM call per chunk to identify entities and relationships. **For clinical charts on OpenMRS, that step is already free.** The entity-relationship graph exists in the schema: `Patient → Encounters → Obs/Conditions/Diagnoses/Orders → Concepts → ConceptMappings/ConceptSets`. Foreign keys are the edges. The expensive part GraphRAG spends most of its budget on is something OpenMRS gives us at no cost. The remaining work is community detection on the per-patient graph + LLM summarization of each community. On Richard Jones this would likely produce ~5-10 clusters (kidney-related, oncology, psychiatric, cardiovascular, vital-signs trends), each with a ~200-word summary. At Gemma 4 E4B local-LLM speed: ~30-90 seconds per patient at first-time indexing + incremental on chart updates.

**Caveat compounding the redundancy-bound finding above**: community summaries would become the retrieval substrate for global queries. If community-summary quality is poor under a small LLM doing the summarization, the global retrieval is poor, and small models produce confidently-wrong summaries — the exp-6 pattern repeating one level up. The first principled experiment before sinking weeks of engineering would be: manually pre-compute community summaries for one rich-chart patient (Richard Jones), one-shot them with the small LLM, then test summary-query quality. If the small LLM can produce useful per-cluster summaries from a manually-clustered set of records, the architecture works. If not, it's doomed before we build the indexing pipeline.

Variant of GraphRAG worth knowing about for this specific case: **LazyGraphRAG** (Microsoft, late 2024) defers graph construction to query time, drastically reducing one-time index cost — a better fit for small per-patient corpora that change frequently than the canonical GraphRAG which builds the graph upfront. **DRIFT search** (added to GraphRAG mid-2024) hybridizes local + global for queries that need both breadth and specificity, which describes many real clinician asks. Anthropic's **contextual retrieval** (2024) is a cheaper non-graph alternative — prepends an LLM-generated context blurb to each chunk before embedding — that handles some of the same use cases without the graph machinery. None of these are implemented in chartsearchai today.

**Status**: not pursuing. Documented as an architectural option for when chart-summary becomes a real product requirement. The current workstream's focus on local-query quality is complete; global-query quality is a separate workstream with its own engineering depth (~2-3 weeks for a principled GraphRAG-on-OpenMRS implementation including the small-LLM-summary-quality validation).

**Query rewriting tested and rejected (post-7, 2026-05-26):** the hypothesis was that having a small LLM expand the user's natural-language query into BM25-friendly clinical-synonym form before retrieval (`"any kidney problems?"` → `"kidney renal nephropathy creatinine BUN glomerular dialysis hematuria proteinuria"`) would lift retrieval on queries where the user's wording doesn't match chart vocabulary. Tested as a manual cheap signal check first — I crafted careful rewrites by hand and sent them as the question field, which has the confound that the rewrite flows into both the retrieval query AND the LLM answer-generation prompt (chartsearchai's `LlmProvider.search(records, question)` doesn't separate them). The retrieval comparison is clean via QSEVAL; the answer comparison is confounded. Result on mark_smith kidney: retrieval top-5 went from 1-of-5 relevant (creatinine at #2 with Diabetic foot ulcer + Living in residential institution + Urinary incontinence as noise) to 5-of-5 relevant (creatinine, BUN, Bacteriuria, Serum albumin, Total protein) — and the LLM answer picked up two additional kidney findings the baseline missed (hematuria at chart rank 9, anemia at rank 10). **Clear retrieval win on this query.** Result on mark_smith psychiatric: retrieval top-5 went from 4-of-5 relevant (OCD + Fear of medical care diagnoses & conditions) to **2-of-5 relevant** — the rewrite's repeated `"disorder"` token (from `"anxiety disorder, substance use disorder"`) crowded "Disorder of thyroid" into top-5 and pushed Fear of medical care out. **Regression on this query.** Same trade-off shape as the other tested-and-rejected post-7 levers: works on under-specified queries where vocabulary expansion fills real gaps, hurts on already-specific queries where the expansion introduces common-token noise. Where it overlaps with what we shipped: exp 5 + exp 7 do the analogous expansion on the indexed side (mapping_names puts "kidney disease" into the BM25 index for CKI via the ICD-10 mapping, etc.) — query-side rewriting and indexed-side enrichment converge in effect, but rewriting carries per-query latency cost (an extra LLM call), implementation cost (non-trivial wiring in `LlmProvider` to separate retrieval-query from answer-question), and the conditional-benefit problem (needs query-type awareness or careful rewriter-prompt engineering to avoid the disorder-token failure mode). Not recommended for shipping based on this evidence. Re-test if a query class emerges where the user vocabulary is consistently far from the chart vocabulary (e.g. patient-language input rather than clinical-language input).

## Decision 19: Retain all-MiniLM-L6-v2 as the embedding model

**Status: Accepted** (April 2026)

### Problem

all-MiniLM-L6-v2 (384 dims, ~90MB) ranks lower than several newer models on general MTEB retrieval benchmarks. The question was whether upgrading to a larger 768-dim model would improve clinical retrieval quality — and whether provenance matters for US-funded deployments.

### Evaluation

Three 768-dim alternatives were exported to ONNX and benchmarked against the full test suite (782 tests, 259 of which exercise the real ONNX model on clinical queries across five patient datasets):

| Model | Dims | Size | Maintainer | License | Real-model failures (of 259) |
|---|---|---|---|---|---|
| **all-MiniLM-L6-v2** | 384 | 90MB | Microsoft/HF | Apache 2.0 | **0** |
| intfloat/e5-base-v2 | 768 | 436MB | Microsoft | MIT | **88 (34%)** |
| sentence-transformers/all-mpnet-base-v2 | 768 | 416MB | HF | Apache 2.0 | **6 of 10 hardest** |
| nomic-ai/nomic-embed-text-v1.5 | 768 | 548MB | Nomic AI | Apache 2.0 | **7 of 10 hardest** |

Key failure patterns for e5-base-v2 (the most thoroughly tested alternative):

- **"STD" → HIV/Zika missed entirely** — the model does not associate the abbreviation with sexually transmitted diseases
- **"vital signs" → Temperature missing** — the model does not rank Temperature records above the relevance threshold
- **"tests ordered" → false positives** — returns records for datasets that have no lab test orders
- **"fever" → Temperature missing** — fails to connect the symptom to the measurement

Adding e5-style `"query: "` / `"passage: "` prefixes was tested and did not improve results (still 10/10 hardest tests failing).

### Decision

Retain all-MiniLM-L6-v2. Do not upgrade to a larger model.

### Why the smaller model wins

Despite being smaller (384 vs 768 dims) and older, all-MiniLM-L6-v2 has a particularly good embedding space for medical terminology associations. It is not the highest-ranked model on general benchmarks, but for this specific task — matching clinical queries to patient records — it outperforms the larger alternatives. The reasons:

1. **Ranking, not thresholds.** The failures are not threshold problems — the larger models rank incorrect records above correct ones. No threshold tuning can fix records that are ranked below noise. For example, when e5-base-v2 returns empty results for "STD" (expecting HIV/Zika records), there is no cutoff point that includes the correct records without also including everything else.

2. **Score distribution geometry.** all-MiniLM-L6-v2 produces wider score distributions (IQR ~0.10) that give the adaptive filtering pipeline (ratio floor, z-score gates, keyword rescue, coherence filtering, gap validation) room to separate relevant from irrelevant records. The larger models produce tighter distributions that collapse this signal — the same problem that caused MedCPT to be rejected (see [Decision 18](#decision-18-cross-encoder-reranking-stage-superseded)).

3. **Co-evolution.** The pipeline's ~10 tuned constants were developed alongside this model's embedding space. The thresholds interact — adjusting one for a new model's geometry breaks others. With 88 failures spanning STDs, vital signs, medications, infections, cancer, mental health, and anemia, finding a single parameter set that satisfies all clinical associations simultaneously is not feasible.

### Provenance

all-MiniLM-L6-v2 is produced by Microsoft / Hugging Face (US/German), Apache 2.0 licensed — safe for US-funded (USAID, PEPFAR, NIH) deployments. BAAI/bge-base-en-v1.5 was the original top recommendation in the embedding improvement plan but was not benchmarked due to its provenance from a Chinese government-funded institution, which may conflict with compliance requirements for some funders.

### Compatibility fix

During this evaluation, `OnnxEmbeddingProvider` was updated to only send `token_type_ids` when the model expects it. all-MiniLM-L6-v2 requires all three inputs (input_ids, attention_mask, token_type_ids), but e5 and nomic models accept only two. The fix is backward-compatible — existing behavior is unchanged for all-MiniLM-L6-v2.

### Follow-up: same-family upgrade (L12-v2)

After the initial evaluation, `sentence-transformers/all-MiniLM-L12-v2` was benchmarked as the closest untested sibling to L6-v2 — same 384 dims, same WordPiece vocabulary, same maintainer and licence, just twice the transformer layers (12 vs 6). It is the most plausible drop-in upgrade because no pipeline thresholds or score-distribution assumptions need re-tuning for a different geometry family.

L6 was re-measured the same day on the same code revision so the comparison is apples-to-apples (the test suite has grown since the original Decision 19 evaluation, so the L6 baseline below differs from the "0" reported in the original table above):

| Model | Failures (of 305 tests in LlmInferenceServiceTest + ElasticsearchKnnFallbackTest + EndToEndSearchTest + RetrievalQualityEvalTest) |
|---|---|
| **all-MiniLM-L6-v2** | **20** |
| all-MiniLM-L12-v2 | **70** (3.5× more) |

L12-v2 fixed 2 tests (one "should return empty" assertion that L12 satisfied by returning empty too often, and one excluded EndToEndSearchTest), regressed 52 tests, and shared 18 failures with L6. Regressions spanned every clinical category — cancer/Kaposi sarcoma, mental health, anemia, cardiovascular, blood, STD, allergies, and vital signs — across all five patient datasets. Most strikingly, L12 broke the entire cancer suite (currently passing on L6) and the mental health suite, confirming that the deeper sibling does not preserve L6's clinical-vocabulary associations.

This reinforces the original finding: **the failure mode is not model capacity but the specific embedding geometry of L6-v2**. Even the closest sibling in the same family does not transfer. Future improvements to clinical retrieval should target the pipeline (or vocabulary expansion / synonym handling) rather than the embedding model.

**Update (April 2026):** Decision 19's conclusion was partially superseded by Decisions 20 and 21. MedCPT is now a supported alternative with 88% recall (vs L6-v2's 69%), enabled by model-specific pipeline tuning, dual-encoder support, and concept-name re-ranking.

## Decision 20: MedCPT dual-encoder as an alternative embedding model

**Status: Accepted** (April 2026)

### Problem

Decision 19 retained all-MiniLM-L6-v2 because larger models and MedCPT produced compressed score distributions that defeated the pipeline's adaptive filtering. However, L6-v2 has a critical limitation: its WordPiece tokenizer (30K vocab from general English text) splits medical abbreviations like BMI, STD, COPD, ECG, and ICU into meaningless subword fragments (e.g. "bmi" → ["b", "##mi"]). The model literally cannot understand these terms.

### Root cause analysis

The earlier MedCPT rejection (Decision 18) was due to untested pipeline parameters — the pipeline used L6-v2's tuned constants with MedCPT's different score distribution. Three specific issues were identified and fixed:

1. **No model-specific pipeline configuration.** MedCPT's compressed scores (noise mean ~0.67 vs L6-v2's ~0.26) require different `minScoreGap` (0.01 vs 0.10), `similarityRatio` (0.98 vs 0.80), and `floorRescueMinZScore` (1.0 vs 2.0). A `medcptDefaults()` config was added alongside L6-v2's `defaults()`.

2. **topK caps truncating valid results.** The pipeline had two places where `topK` truncated zero-keyword candidates. With compressed scores, many valid records pass the ratio floor, and the topK cap arbitrarily cut them — discarding Height records for BMI queries. The topK caps were removed entirely; the pipeline's gap detection, ratio floor, and z-score gates determine the result set organically.

3. **No dual-encoder support.** MedCPT uses separate query and article encoders trained in different embedding spaces. The pipeline used a single encoder for both, degrading MedCPT's query-article matching quality. `embedQuery()` was added to `EmbeddingProvider` with a separate ONNX session for the query encoder.

### Model fingerprinting

Models are identified automatically by embedding a sentinel string and matching the first 4 output values against known fingerprints. This detects the model regardless of file path, so `PipelineConfig.forModel()` selects the correct parameters without manual configuration.

### Evaluation

MedCPT (ncbi/MedCPT-Query-Encoder + ncbi/MedCPT-Article-Encoder, 768 dims, ~840MB total) was benchmarked against L6-v2 using the same 485-case eval harness across 5 patient datasets:

| Metric | L6-v2 | MedCPT |
|---|---|---|
| Queries with results | 337/485 (69%) | **425/485 (88%)** |
| Queries returning nothing | 148 | **60** |
| Total records returned | 2,979 | 6,987 |
| Cases where model returns more | 64 | **209** |

MedCPT's medical tokenizer correctly handles BMI, STD, COPD, ECG, ICU, TB, BP as single tokens. Its PubMedBERT-based vocabulary was trained on biomedical text where these abbreviations appear frequently.

### Decision

Support MedCPT as an alternative to L6-v2 via the `chartsearchai.embedding.queryModelFilePath` global property. L6-v2 remains the default for backward compatibility. MedCPT requires merged ONNX model files (the ONNX runtime has a bug with external data files).

Each model has its own eval baseline (`enriched-retrieval-eval.json` for L6-v2, `medcpt-retrieval-eval.json` for MedCPT) and pre-computed embedding caches committed to git for fast test runs.

### ONNX external data workaround

MedCPT's ONNX models split weights into `model.onnx` + `model.onnx.data`. The ONNX runtime 1.24.3 has a bug where it resolves the data file path as `<model_path>/model.onnx.data` instead of `<model_dir>/model.onnx.data`. The workaround is to merge weights back into a single file using `onnx.save()`. A `createSessionWithExternalData()` method attempts canonical path resolution first, falling back to byte-array loading.

## Decision 21: Concept-name re-ranking for subword-tokenized queries

**Status: Accepted** (April 2026)

### Problem

When a query term is split by the tokenizer into subword fragments (e.g. "bmi" → ["b","##mi"]), the model can't process it as a meaningful unit. The full-text embedding is then dominated by shared structural prefixes (e.g. "Vital signs / Finding —") rather than the actual concept content. This causes false positives — a BMI query returns Blood Pressure alongside the correct Height and Weight records because all vitals share the same prefix and score similarly.

### Solution

A concept-name re-ranking stage runs after the main pipeline, only when:
- The query has **zero keyword matches** (the pipeline relied entirely on semantic scores)
- At least one query term is **split by the tokenizer** (`EmbeddingProvider.isSubwordToken()` checks if the model's vocabulary contains the term as a whole token)

The re-ranking extracts each unique concept name from the result set (via the existing `ConceptNameUtil.extractConceptName()`), embeds it with the query encoder, and drops concepts that are **outliers below the cluster** (scoring below `mean - std` of the concept-name scores).

For "BMI" with 3 result concepts:
- Weight (kg): concept-name score 0.81
- Height (cm): 0.72
- Diastolic blood pressure: 0.63
- Mean = 0.72, std = 0.08, threshold = 0.64
- BP (0.63) is below → dropped. Weight and Height are above → kept.

### Why this works where cross-encoder reranking failed (Decision 18)

The cross-encoder (Decision 18) reranked individual records using the full text, which couldn't separate records that all contain the same "Vital signs" prefix. Concept-name re-ranking strips the prefix and scores just the concept identity ("Weight (kg)" vs "Diastolic blood pressure") against the query, where the difference is clear.

### Generic design

The approach encodes no domain knowledge:
- **Trigger:** determined by the model's own tokenizer vocabulary — not character length or language rules
- **Threshold:** standard statistical outlier detection (mean - std) on the concept-name scores
- **Self-correcting:** models that already understand the term as a single token (e.g. MedCPT knows "tb") don't trigger the re-ranking

### Evaluation impact

- **L6-v2:** 0 regressions (486/486 eval cases pass)
- **MedCPT:** 1 baseline update ("any STD?" on FULL dataset: 7 → 5 records, the 2 dropped records were marginally related)

### Production fix: concept-set membership enrichment at index time

Following the L12 result, the next obvious lever was enriching index text with metadata OpenMRS already has. CIEL classifies vital sign concepts (Temperature, BP, Pulse, RR, SpO2, Height, Weight) as members of the "Vital signs" concept set (1114). The previous serializer included `concept.getConceptClass()` (which yields "Test" or "Finding" — useless for category queries) but not `getSetsContainingConcept()`.

**Pre-deployment validation** measured cosine deltas for query "vital signs" against the L6 model:

| Record (representative form) | Unenriched cosine | Enriched cosine | Δ |
|---|---|---|---|
| `Clinical observation: Finding — Temperature: 36.7` | 0.20 | 0.53 | +0.33 |
| `... Systolic blood pressure: 145` | 0.19 | 0.45 | +0.26 |
| `... Pulse: 100` | 0.36 | 0.64 | +0.29 |
| `... Respiratory rate: 17` | 0.26 | 0.56 | +0.30 |
| `... Arterial blood oxygen saturation: 93` | 0.27 | 0.50 | +0.23 |
| `Clinical diagnosis: Diagnosis: Asthma` (control, not in set) | 0.20 | 0.20 | 0 |
| `Medical condition: Condition: Stroke` (control) | 0.18 | 0.18 | 0 |

Enriched form: `Clinical observation: Vital signs / Finding — Temperature: 36.7`. Vital sign records jump from below-noise (~0.20) to well-above-floor (~0.50). Non-set-members stay flat. Separation gap of ~0.30 is easily filterable.

**End-to-end production validation** against a real OpenMRS instance with patient `4acc0b80-83c4-40f7-86fd-0e11a68dd405` (chart contains the same kind of Temperature/BP/Pulse data the test fixtures simulate):

| Query | Before fix | After fix |
|---|---|---|
| `vital signs` | 0 refs, "No relevant records found" | **82 refs**, correct enumeration |
| `temperature` (control) | 9 refs | 9 refs (unchanged) |
| `blood pressure` (control) | 18 refs | 18 refs (unchanged) |
| `headache` (negative control) | 0 refs (correct) | 0 refs (correct) |

**Implementation:** at index time, `PatientRecordLoader` calls `Context.getConceptService().getSetsContainingConcept(obs.getConcept())` for each Obs and stores the set names as `categoryHints` on `SerializedRecord`. `EmbeddingIndexer` writes a hint-injected body into `ChartEmbedding.textContent` (e.g. `"Vital signs / Finding — Temperature: 36.7"`) and computes the embedding from `buildPrefixedText(resourceType, body)` — so both semantic cosine *and* keyword scoring see the enrichment downstream. `LlmInferenceService` keyword scoring trips into a stricter zero-keyword-match path otherwise, so injecting hints into `text_content` rather than only the embedding is essential.

**Why this respects the no-domain-knowledge rule:** the algorithm doesn't know what "vital signs" means. It just embeds whatever the OpenMRS Concept dictionary attaches to each concept's set membership. Medical knowledge stays in the dictionary, where it belongs.

**Scope limit:** CIEL diagnoses (Zika, Syphilis, Pneumonia, Asthma) are not members of any concept set in the standalone CIEL distribution tested. STD/infections category queries still fail there — and a follow-up investigation showed that the obvious alternative metadata sources are also blocked by missing data, not missing code:

| OpenMRS metadata source | Structure loaded? | Human-readable text loaded? |
|---|---|---|
| `concept_set` membership (Vital signs only) | ✓ | ✓ — **what the shipped fix uses** |
| Concept synonyms (already used by serializer) | ✓ | ✓ — but only spellings/abbreviations, no category words |
| `SAME-AS` reference mappings (14716 entries across CIEL/SNOMED/ICD-10/ICD-11/IMO) | ✓ | ✗ — **0 of 14716** with name or description |
| `BROADER-THAN` mappings (452) | ✓ | ✗ — 0 with text |
| `NARROWER-THAN` mappings (3263) | ✓ | ✗ — 0 with text |
| Multi-locale concept names | ✓ | ✓ — but pure translations of the specific concept, no category words |

The structural pointers for category enrichment exist (e.g., "Gonococcal arthritis NARROWER-THAN ICD-10 A54.4 'Gonococcal infection of musculoskeletal system'") but the term names and descriptions are uniformly NULL in this install — typical of the code-only CIEL distribution where SNOMED CT and ICD-10 descriptive text typically arrive via separate licensed content.

**The blocker for STD/infections/cancer queries is dictionary curation, not algorithm.** The same `extractCategoryHints` mechanism will automatically benefit any deployment whose dictionary adds either (a) explicit concept-set membership for diagnoses, or (b) descriptive text for the existing reference-term mappings. No further code changes needed in those deployments.

**Three deployment-time options for category-query coverage on diagnoses:**
1. **Enrich the dictionary** — add concept-set memberships (e.g., create "Sexually transmitted infections" set with HIV, Syphilis, Zika as members). One-time CIEL maintenance task.
2. **Load reference-term descriptive text** — typically requires the SNOMED CT or ICD-10-WHO content distribution with licensing implications.
3. **Accept the limitation** for category queries on diagnoses until either of the above happens.

When extending `extractCategoryHints` in the future, walk **NARROWER-THAN** mappings (the OpenMRS concept is narrower than the external broader category) — not BROADER-THAN. Both relationships exist; NARROWER-THAN is 7× more populated and points in the right direction for category queries.

**Fixture-driven test suite:** unchanged at 20 failures. String fixtures bypass `loadAll()` so they don't exercise the new metadata path. The 14 vital-signs/STD/infections failures in the fixture tests are now empirically classified as **fixture limitations**, not algorithm bugs — production behavior (with real Concept metadata) is correct for vital signs.

### Rejected alternative: runtime query expansion via dictionary lookup

After the vital-signs fix, we considered an alternative path for the unfixed diagnosis-category queries (STD, immunocompromised, etc.): rewriting the query at retrieval time by appending dictionary-derived synonyms for short uppercase tokens (e.g. `STD → "STD Sexually transmitted disease"`). Two trigger variants were prototyped and benchmarked against the running OpenMRS instance.

**Reactive trigger** (only expand when retrieval returns zero records): never fired for the failing queries. Production retrieval returns 2 off-topic records for `STD`/`immunocompromised`/`opportunistic infections in HIV` — not zero — so the trigger condition was structurally wrong for the actual failure mode.

**Eager trigger** (always expand when query has an all-uppercase 2–6-char alphabetic token): benchmarked across 91 of the 97 unique test queries (the 6 longest queries timed out equally in both modes). Pipeline-filtered record IDs compared between modes:

- 87 queries (95.6%): pipeline output **identical** — uppercase candidate filter correctly skipped lowercase queries
- 4 queries changed:
  - `any STD?`: gained 4 relevant records (HIV, Zika) and 4 noise records (Hookworm, Haemorrhagic disease of newborn)
  - `TB`: gained 2 noise records only (HIV via `getConceptsByName("TB")` matching `"TB/HIV clinic"`); patient has no actual TB
  - `TB treatment history`: same `TB → HIV` noise contamination
  - `HIV status and CD4 count`: gained PMTCT (relevant) but **lost HIV wasting syndrome** (relevant) — the expanded embedding shifted away from a previously-matched concept

The mechanism that fails: `ConceptService.getConceptsByName(token)` does substring matching across the entire dictionary, so any concept name containing the token (even tangentially, like `"TB/HIV clinic"`) is returned and its preferred name appended to the query. For an LLM with a finite context, noise dilutes signal — a clinician asking about TB should not be handed HIV records.

**Decision:** runtime query expansion via dictionary lookup is the wrong tool. The right path for STD/infections-style failures is **dictionary-side curation** — adding `concept_set` memberships that link HIV/Syphilis/Zika to a "Sexually transmitted disease" parent concept, the same mechanism the vital-signs fix uses. The `extractCategoryHints` code shipped earlier will then automatically benefit those queries with no further code changes.

All query-expansion code was reverted. The lesson is captured here so future maintainers don't re-litigate.

## Decision 22: e5-base-v2 for the querystore-backed retrieval path

**Status: Accepted** (May 2026)

### Context

Decisions 19–21 cover the chartsearchai-side pre-filter pipeline — `all-MiniLM-L6-v2` + adaptive filtering (similarity ratio, gap detection, z-score gates, concept-name re-ranking) co-evolved against the 485-case eval baseline. That pipeline is **not** what runs when `chartsearchai.querystore.enabled=true` (the recommended deployment).

In the querystore-backed path:

- chartsearchai serializes patient records.
- [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) indexes them (Lucene BM25, plus optional ONNX kNN).
- Querystore returns the top-K candidates per question.
- The local LLM filters those candidates as part of the answer phase.

No L6-v2-tuned thresholds run at the querystore layer. The "filter" stage is the LLM itself reading the top-K and choosing what to cite.

### Problem

With the LLM doing the filtering, the embedder's job changes. It no longer needs to feed an adaptive filtering pipeline that depends on a specific score-distribution geometry; it just needs to put plausible candidates in the top-K so the LLM can reason over them. The dominant failure mode in this regime is **vocabulary gaps** — clinical records use formal terms ("Cerebrovascular Accident") while users type colloquial ones ("any heart issues?", "any cardiovascular issues?"). If the embedder doesn't cluster these, the top-K never contains the relevant record and the LLM can't recover.

### Evaluation

Three embedders were compared on the demo standalone (Betty Williams and Richard Jones charts, real OpenMRS data) with `chartsearchai.querystore.enabled=true`, `chartsearchai.querystore.topK=30`, and Gemma 4 E4B as the filtering LLM:

| Embedder | Colloquial → clinical bridge | Source | Notes |
|---|---|---|---|
| `sentence-transformers/all-MiniLM-L6-v2` | Misses (CVA in chart, "any heart issues?" returns 0 cardiovascular records) | self-contained ONNX | Decision 19's winner for the pre-filter pipeline, but its tighter clusters fail on this question class without the adaptive filtering to compensate |
| `intfloat/e5-base-v2` (via `Xenova/e5-base-v2`) | **Bridges** ("heart issues" surfaces CVA records the LLM correctly cites) | self-contained ONNX | Empirical winner for the querystore + LLM-as-filter path |
| `ncbi/MedCPT-*` (dual-encoder) | Refuses to bridge — PubMed-trained clusters pedantically separate colloquial from clinical | dual ONNX | Optimized for PubMed corpus; doesn't generalize to the chart-search user's phrasing |

### Decision

For deployments running `chartsearchai.querystore.enabled=true`, use `intfloat/e5-base-v2` as the querystore embedder. `backend-init.sh` provisions it automatically for Docker deployments; non-Docker installs should download it manually to `<openmrs-application-data-directory>/querystore/`.

### Why this does not contradict Decision 19

Decision 19 evaluated e5-base-v2 on the **chartsearchai-side pre-filter pipeline** with L6-v2-tuned thresholds active (`similarityRatio=0.80`, `minScoreGap=0.10`, `scoreGapMultiplier=2.5`, etc.) and reported 88/259 failures. Those thresholds depend on L6-v2's wider score distribution (IQR ~0.10); e5-base-v2's tighter geometry collapses the signal those thresholds were designed to read, and the failure mode is "ranking, not thresholds" — no parameter retuning recovers a record the model ranks below noise.

In the querystore path none of those thresholds run. There is no `similarityRatio` floor, no gap detection, no z-score gate — the LLM evaluates the top-K directly. The win condition shifts from "is the relevant record ranked above all noise?" to "is the relevant record in the top-K at all?" — and on the colloquial-to-clinical bridge queries that chart-search users actually type, e5-base-v2 surfaces it where L6-v2 doesn't.

Both decisions are correct simultaneously:

- **Decision 19**: `all-MiniLM-L6-v2` wins the chartsearchai-side pre-filter pipeline. *(Still accurate; the legacy path is unchanged.)*
- **Decision 22**: `intfloat/e5-base-v2` wins the querystore + LLM-as-filter path.

### Source: Xenova mirror, not the canonical repo

Download via `Xenova/e5-base-v2`, which ships a self-contained ONNX export (~440MB). The canonical `intfloat/e5-base-v2/onnx/` directory uses external-data format (a graph file plus a separate `model.onnx_data` weights sidecar). Downloading only the graph produces a ~1MB "successful" file that the ONNX runtime opens but cannot execute, failing late at first inference with a misleading "Not a directory" error — the bug class that caused an earlier `all-MiniLM-L6-v2` provisioning path to silently break when its upstream export format changed. `backend-init.sh` carries a 200MB size guard as the second line of defense.

### Trade-offs

- **+** Colloquial-to-clinical vocabulary bridging that L6-v2 misses and MedCPT refuses.
- **+** Self-contained ONNX (no sidecar weights file to stage alongside).
- **+** Single encoder — no separate query-encoder model path needed (contrast with [Decision 20](#decision-20-medcpt-dual-encoder-as-an-alternative-embedding-model)).
- **−** ~440MB on disk vs L6-v2's ~90MB.
- **−** Slower per-record embedding than L6-v2 (~5× the parameters: 110M vs 22M). Bounded by querystore's per-patient projection (lazy indexing on chart open), not paid on every query.
- **−** Quality on this path is judged by end-to-end LLM-answer correctness rather than the recall@K eval baseline that drives Decisions 19–21. A separate eval harness for the LLM-as-filter path is future work.

## Known limitations

- **Counting questions**: LLMs are unreliable at precise counting tasks (e.g., "how many weight records in the last 10 years?"). The model may undercount or overcount even when all relevant records are provided. Larger, more capable models perform better at counting but are still not perfectly reliable. This is a fundamental limitation of LLM inference, not a retrieval issue. Questions that require exact counts are better suited to structured queries.

## Planned future work

- **Incremental embedding indexing**: The `EncounterService` AOP hook already uses an incremental strategy (indexes only new/changed encounters), but other data types (`ObsService`, `ConditionService`, etc.) still use `indexPatient()` which deletes all embeddings for a patient and recomputes from scratch. A fully incremental approach would track which record maps to which embedding row across all data types and only add, update, or delete the specific embeddings affected. This matters for patients with large charts where AOP hooks fire frequently.
- **Concept graph traversal**: Complement embedding search with OpenMRS concept relationship traversal to improve retrieval for queries involving related concepts (e.g., finding NSAID allergies when asking about ibuprofen).
- **Pre-computed summaries**: Cache LLM-generated summaries for common query patterns (e.g., "current medications", "active problems") to reduce inference latency for frequently asked questions.
- **Agent/tool-use pattern**: Enable multi-step reasoning where the LLM can request additional data or perform follow-up queries. Deferred until local models with reliable tool-use capabilities are available.
- **Multimodal medical image interpretation**: Extend the pipeline to pass complex observations (X-rays, dermatology photos, ultrasounds, pathology slides, scanned documents) alongside text to multimodal LLMs like MedGemma 1.5 4B. The main changes are: extend `ObsTextSerializer` to handle `ValueComplex` obs, add an optional image field to `SerializedRecord`, and update `LlmProvider`/`LlmEngine` implementations to construct multimodal content arrays (text + base64 image blocks) for the OpenAI-compatible `/chat/completions` API. Both engines already speak this protocol — the embedded llama-server supports multimodal via libmtmd, and remote backends (vLLM, OpenAI, Anthropic) accept the same content-array format. No new serializers are needed — complex obs are still observations.
- **Unstructured data / image OCR**: Extract text from photos of paper forms at write time so the content flows through the existing serializer and embedding pipeline.
