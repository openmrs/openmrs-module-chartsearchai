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
- [Decision 23: Drug-reference injection + post-answer drug-safety validation](#decision-23-drug-reference-injection--post-answer-drug-safety-validation)
- [Decision 24: Drug-reference as a pluggable consumer of authoritative datasets](#decision-24-drug-reference-as-a-pluggable-consumer-of-authoritative-datasets)
- [Decision 25: Citation grounding (Tier-1 cosine + Tier-2 entailment)](#decision-25-citation-grounding-tier-1-cosine--tier-2-entailment)
- [Decision 26: Chart-write detection via core service events](#decision-26-chart-write-detection-via-core-service-events)
- [Decision 27: Drug-safety parity follow-through — weight-aware dosing, curated cross-reactivity groups, prose warnings](#decision-27-drug-safety-parity-follow-through--weight-aware-dosing-curated-cross-reactivity-groups-prose-warnings)
- [Decision 28: Query-scoped slice charts (chartMode=queryScoped)](#decision-28-query-scoped-slice-charts-chartmodequeryscoped)
- [Decision 29: Module-extensible query-scope routing (QueryScopeContributor SPI)](#decision-29-module-extensible-query-scope-routing-queryscopecontributor-spi)
- [Decision 30: One chip per substance — the contraindication ledger and its collapse key](#decision-30-one-chip-per-substance--the-contraindication-ledger-and-its-collapse-key)
- [Decision 31: Name the class that explains the relationship, not the first one shared](#decision-31-name-the-class-that-explains-the-relationship-not-the-first-one-shared)
- [Decision 32: Observable drug-reference load status](#decision-32-observable-drug-reference-load-status)
- [Decision 33: A residual ATC subgroup is not a relationship](#decision-33-a-residual-atc-subgroup-is-not-a-relationship)
- [Decision 34: An ATC subgroup licenses only the claim its own name asserts](#decision-34-an-atc-subgroup-licenses-only-the-claim-its-own-name-asserts)
- [Decision 35: A class code in the answer must come from a record the answer cites](#decision-35-a-class-code-in-the-answer-must-come-from-a-record-the-answer-cites)
- [Decision 36: The shipped default is the whole DDInter knowledge base](#decision-36-the-shipped-default-is-the-whole-ddinter-knowledge-base)
- [Decision 37: A safety answer's call is as strong as the finding's rating](#decision-37-a-safety-answers-call-is-as-strong-as-the-findings-rating)
- [Decision 38: An active order the module cannot name is still one co-medication](#decision-38-an-active-order-the-module-cannot-name-is-still-one-co-medication)
- [Decision 39: A folded chip names one active order once](#decision-39-a-folded-chip-names-one-active-order-once)
- [Decision 40: A partner's name source has one write path](#decision-40-a-partners-name-source-has-one-write-path)
- [Decision 41: A composite claim's negative says nothing about the citation](#decision-41-a-composite-claims-negative-says-nothing-about-the-citation)
- [Decision 42: A recorded clause needs corroboration, not just a match](#decision-42-a-recorded-clause-needs-corroboration-not-just-a-match)
- [Decision 43: A substance is named by the row the data files it under](#decision-43-a-substance-is-named-by-the-row-the-data-files-it-under)
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

Solves 70% of the problem with 20% of the complexity. No hallucination risk from retrieval errors, no extra model files required, works offline. Weakness: no semantic understanding — relies on lexical matching with stemming. Was implemented in-process as a selectable alternative pipeline ([Decision 13](#decision-13-lucene-bm25-as-an-alternative-retrieval-pipeline)) and later removed with the rest of that stack in #51; BM25 now lives in querystore.

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

The initial plan was full RAG with a deterministic retrieval layer feeding a small subset of records to the LLM. Decision 10 refined it into a **single LLM approach**, and [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped) settled where it landed: querystore assembles a query-scoped slice, and one LLM call reasons over it. The two-step structure remains — retrieval then synthesis — but the LLM handles all queries, not just "hard" ones.

## Decision 3: Embedding approach — semantic search index

**Status: Superseded** — the in-process retrieval stack this decision describes (embedding index, vector store, Lucene/Elasticsearch pipelines, the `chartsearchai.retrieval.pipeline` and `chartsearchai.embedding.*` global properties) was **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)). Retrieval now belongs entirely to [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) — see [Decision 22](#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) and [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped) for what runs today. Kept as the record of *why* the approach was taken; **read the body as history, not as current behaviour.**

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

**Status: Partly superseded** — the principle (flat, concise clinical text rather than FHIR JSON) still holds and is what querystore emits. The `ClinicalTextSerializer` implementations named below, and the OpenMRS service reads that fed them, were **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)); querystore produces record text now, and this module's `PatientChartSerializer` only numbers and date-labels it.

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

**Status: Superseded** — the in-process retrieval stack this decision describes (embedding index, vector store, Lucene/Elasticsearch pipelines, the `chartsearchai.retrieval.pipeline` and `chartsearchai.embedding.*` global properties) was **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)). Retrieval now belongs entirely to [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) — see [Decision 22](#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) and [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped) for what runs today. Kept as the record of *why* the approach was taken; **read the body as history, not as current behaviour.**

### Options

| Granularity | Pros | Cons |
|---|---|---|
| Individual record | Precise retrieval, fine-grained citations | Many embeddings per patient, records in isolation lose context |
| Per encounter | Groups related data naturally, fewer embeddings | Large encounters produce long text, less precise |
| Per clinical category | Matches how clinicians think | Arbitrary groupings, large text chunks |

### Decision

Embed at the **individual record level**. Each record is serialized to concise clinical text (e.g., `"Systolic Blood Pressure: 120 mmHg (ABNORMAL)"`). This keeps embeddings small and precise while giving the similarity search enough context to work with.

## Decision 6: Embedding model

**Status: Superseded** — the in-process retrieval stack this decision describes (embedding index, vector store, Lucene/Elasticsearch pipelines, the `chartsearchai.retrieval.pipeline` and `chartsearchai.embedding.*` global properties) was **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)). Retrieval now belongs entirely to [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) — see [Decision 22](#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) and [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped) for what runs today. Kept as the record of *why* the approach was taken; **read the body as history, not as current behaviour.**

### Decision

Semantic vectors via **all-MiniLM-L6-v2** running in-process through ONNX Runtime. ~90MB model file, runs on CPU, no GPU needed. Produces 384-dimensional vectors. Requires two files configured via `chartsearchai.embedding.modelFilePath` and `chartsearchai.embedding.vocabFilePath`. Captures semantic meaning — effective for clinical queries where synonyms and related concepts matter (e.g., "hypertension" and "high blood pressure" are recognized as related). Embedding dimensions are auto-detected from the model output on first use, so models with different dimensions (e.g., 768-dim pubmedbert-base-embeddings) work without code changes.

A term-frequency hashing approach was considered as a simpler alternative (no model file needed, keyword-overlap retrieval). It was rejected because it cannot capture semantic similarity — for a clinical question like "any infections?", it would find records containing the word "infection" but miss "tuberculosis", "malaria", or "UTI". This defeats the purpose of pre-filtering, since the LLM with the full chart would catch all of these.

## Decision 7: Vector storage — MySQL, not a vector database

**Status: Superseded** — the in-process retrieval stack this decision describes (embedding index, vector store, Lucene/Elasticsearch pipelines, the `chartsearchai.retrieval.pipeline` and `chartsearchai.embedding.*` global properties) was **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)). Retrieval now belongs entirely to [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) — see [Decision 22](#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) and [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped) for what runs today. Kept as the record of *why* the approach was taken; **read the body as history, not as current behaviour.**

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

**Status: Superseded** — the in-process retrieval stack this decision describes (embedding index, vector store, Lucene/Elasticsearch pipelines, the `chartsearchai.retrieval.pipeline` and `chartsearchai.embedding.*` global properties) was **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)). Retrieval now belongs entirely to [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) — see [Decision 22](#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) and [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped) for what runs today. Kept as the record of *why* the approach was taken; **read the body as history, not as current behaviour.**

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

**Status: Superseded** — every `*TextSerializer` class named here, and `PatientRecordLoader`, were **deleted** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)). Record text is now produced by querystore's own serializers; this module's `PatientChartSerializer` numbers records, attaches dates and obs-group labels, and strips synonyms. The per-record-type field lists below therefore describe **another module's** code and are not verifiable from this repo. Two further corrections for anyone reading the body: obs-group members are indexed **atomically**, not flattened into one record; and patient demographics are a numbered, citable record, the un-numbered header line being only a fallback. Kept as the record of *why*; **read as history.**

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
- The **record date** (when it was observed/created) is not produced by the serializer itself. Instead, `PatientChartSerializer` prepends it as a citation label when constructing the LLM prompt (e.g., `[1] (2025-10-30) Systolic Blood Pressure: 120 mmHg`). To save prompt tokens the date is **run-length compressed** — rendered on the first record of each consecutive same-date run and omitted on the rest — so the **LLM still sees every record's date** (each record either carries its own date or inherits the one shown just above it in the same run); it is just added at the prompt assembly level rather than the serializer level. The `RecordMapping` used for citation grounding retains each record's date regardless of this display compression. The date is also included in the API response's `references` array for the UI to display. Records are sorted most-recent-first, giving the LLM a positional recency signal in addition to the explicit date.

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

**Status: Partly superseded** — the single-LLM architecture stands and is what runs. Everything below about an embedding pre-filter narrowing the chart, and the whole "Embedding model selection" / "Similarity threshold algorithm" / "Retrieval precision improvements" / "Chunking strategy" tail, describes machinery **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)). Two claims to correct explicitly, because they are load-bearing and still repeated: the full chart is **not** what the LLM sees by default (`chartsearchai.chartMode` defaults to `queryScoped`, [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped)), and `chartsearchai.embedding.preFilter` **narrows nothing** — it adds a trailing focus hint to a full chart, in `fullChart` mode only.

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

The system prompt instructs the LLM to cite record numbers in brackets and respond with a JSON object. A strict JSON-schema constraint (`response_format: json_schema`, built by `ChartAnswerResponseFormat`) asks for the exact shape `{"reasoning": "...", "answer": "...", "citations": [1, 2]}`. That constraint is enforced by the **server**, not by this module — llama-server compiles it into a GBNF grammar; an OpenAI-compatible remote may approximate it or ignore its sub-fields — so `LlmAnswerExtractor` does not assume it held. It reads a truncated response through a regex salvage path, and reads citation indices that arrive as strings, reporting the latter at WARN ([#219](https://github.com/openmrs/openmrs-module-chartsearchai/issues/219)). This paragraph used to claim the constraint made malformed citations "structurally impossible"; the salvage path already contradicted that when it was written. The leading `reasoning` field is a deliberate chain-of-thought slot (the grammar emits properties in order, so the model thinks before it answers — without it the small local model abstains on queries whose wording differs from the record, e.g. "ear problems" vs a "Hearing Loss" record); it is the model's scratchpad and is ignored by the parser. The `answer` and `citations` are parsed directly as structured data — no regex parsing of free text is needed.

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

The module exposes eight endpoints under `/ws/rest/v1/chartsearchai`, all on one controller (`ChartSearchAiRestController`), registered under the OpenMRS `webservices.rest` module namespace. Every one gates on a privilege as the first statement of its handler, so an unprivileged caller gets 401/403 before any argument is validated.

| Method | Path | Privilege |
|---|---|---|
| POST | `/search` | `AI Query Patient Data` |
| POST | `/search/stream` | `AI Query Patient Data` |
| POST | `/warmup` | `AI Query Patient Data` |
| POST | `/feedback` | `AI Query Patient Data` |
| GET | `/auditlog` | `View AI Audit Logs` |
| POST | `/prewarm` | `Manage AI Prewarm` |
| GET | `/prewarmstatus` | `Manage AI Prewarm` |
| GET | `/drugreferencestatus` | core `Get Global Properties` |

`Manage AI Prewarm` is deliberately not the clinician privilege: a sweep prefills every patient's chart and monopolises the single inference slot, so it is a system operation. `/drugreferencestatus` gates on core's `Get Global Properties` because it discloses configuration, not patient data — see [Decision 32](#decision-32-observable-drug-reference-load-status).

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
    { "index": 3, "resourceType": "order", "resourceUuid": "a8f5f167-4ee2-4d2a-94f9-3f3f86d2e9b6", "date": "2025-03-15", "grounded": null, "group": "chart", "source": null, "withheldInteractions": 0 },
    { "index": 1, "resourceType": "obs", "resourceUuid": "5946f880-b197-400b-9caa-a3c661d71165", "date": "2025-01-10", "grounded": null, "group": "chart", "source": null, "withheldInteractions": 0 }
  ]
}
```

`grounded` is the citation-grounding verdict. On a `chart`-group citation it is `true`/`false` once verified and `null` when grounding is disabled (the default), did not check that citation, or checked it and could not certify it (a compound claim unit under entailment, [#302](https://github.com/openmrs/openmrs-module-chartsearchai/issues/302); or the judge's negative on a composite claim, [#284](https://github.com/openmrs/openmrs-module-chartsearchai/issues/284)), and a client must render that `null` as unverified, never as verified. On a `reference`-group citation it is always `null` whatever the pass concluded, which means "grounding does not apply" rather than "unverified" ([#201](https://github.com/openmrs/openmrs-module-chartsearchai/issues/201) — see [Decision 25](#decision-25-citation-grounding-tier-1-cosine--tier-2-entailment)). `group` is derived from `resourceType` and separates `chart` (a record retrieved from this patient's chart) from `reference` (module-supplied drug knowledge-base prose, not a record about this patient); the array is ordered `chart` group first, preserving the upstream order (most recent first, undated last) within each group. `source` and `withheldInteractions` are the citation's metadata — the dataset an injected drug-reference entry came from, and how many of its interaction partners the record left unrendered. Both keys are always present (`null` / `0` for a chart record, and for a module-derived safety finding, which is computed rather than quoted). They are fields rather than sentences inside the record because everything in a record's text is quotable and the model recited both into answers (issue #117); render them beside the citation, never as part of the answer.

#### Streaming endpoint (SSE)

```
POST /ws/rest/v1/chartsearchai/search/stream
{
  "patient": "patient-uuid-here",
  "question": "What medications is this patient on?"
}
```

Returns a `text/event-stream`. The event names are literals at the `writeSseEvent` call sites in `ChartSearchAiRestController`, and README's [Streaming search (SSE)](../README.md#streaming-search-sse) table documents each one's payload:
- `thinking`, `preliminary` — reasoning chunks, rendered distinctly from the answer, never as it
- `token` — a chunk of the answer text, streamed as generated
- `references` — the citations, as soon as the answer is complete and before any grounding verdict exists
- `done` — final JSON: answer, references, `safetyWarnings`, `questionId`, disclaimer
- `grounded` — a *trailing* event after `done`, only under async grounding, carrying the verdicts
- `error` — an error message if something goes wrong

A client must therefore keep reading past `done`, and must not assume `done` is terminal. It must also skip any line beginning with `:` rather than read it as a frame: between events the stream carries SSE *comments* — one before generation begins and one every 15 s until the answer is finished — so a reverse proxy never sees a read-idle connection through the silent pre-answer wait, which on a CPU-only server is most of the request. They are not events and carry no data, and skipping them falls to whatever parser the client uses: `EventSource` would do it, but it issues a GET and sends no body, so it cannot reach this POST endpoint. README's [Streaming search (SSE)](../README.md#streaming-search-sse) section carries the proxy timeout numbers and the demo measurements behind them.

Both search endpoints return a `questionId` (the audit log row ID as a string) that the frontend uses to submit user feedback.

#### Warmup endpoint

```
POST /ws/rest/v1/chartsearchai/warmup
{
  "patient": "patient-uuid-here"
}
```

Pre-warms the LLM prompt cache for a patient's chart so the first AI query on that patient skips full prefill cost. The frontend hits this when a patient chart is opened. Returns `202 Accepted` immediately; the warmup runs on a background daemon thread. Requires the same `AI Query Patient Data` privilege as the search endpoints. No-op when the engine is remote, and whenever `chartsearchai.chartMode` is `queryScoped` — the default — because a per-question slice has no reusable chart prefix to prime; `LlmInferenceService.shouldRunWarmup` is the gate. Disabled globally by setting `chartsearchai.warmupEnabled=false`.

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
- **Answer caching**: An in-memory LRU cache (`ChartSearchServiceRouter`) stores recent answers keyed by every setting that changes what the LLM sees or what verdict a citation carries, so that changing one invalidates rather than serves a stale answer. `ChartSearchServiceRouter.buildCacheKey` is the definition; today that is the patient, `preFilter`, `chartMode`, `querystore.topK`, the three grounding GPs, and the question. `chartMode` is in the key because it swaps the entire context — the largest possible change to what the LLM sees. The legacy embedding/Lucene/Elasticsearch tuning GPs left the key when that pipeline was removed (#51). Configurable TTL via `chartsearchai.cacheTtlMinutes` (default 0 = disabled). When enabled, identical queries with the same parameters within the TTL window return the cached answer without invoking the LLM. The cache uses an access-ordered `LinkedHashMap` with a fixed maximum size, automatically evicting the least-recently-used entry when the size limit is exceeded. Expired entries are cleaned up periodically (every 10 cache puts) rather than on every access, to avoid scanning the entire cache on each insertion. **Chart-write invalidation**: a chart write to a patient (obs, encounter, order, condition, diagnosis, allergy, program, medication dispense, patient demographics, merge) evicts that patient's cached answers via `ChartSearchServiceRouter.invalidatePatient`. Chart writes are detected through core #6084 service events (`ChartSearchEventListener` → `IndexingHelper.onChartWrite`), which replaced the former per-service AOP advice — see [Decision 26](#decision-26-chart-write-detection-via-core-service-events). This eviction is independent of the preFilter/querystore gating that governs embedding indexing, so a repeated identical question after an edit recomputes against the new chart rather than serving a stale answer — this is what makes the cache safe to enable. It is a backstop, not a guarantee for every path: writes that bypass the OpenMRS service layer (direct DAO, SQL load) do not publish these events, so the TTL must stay finite as the catch-all for those.
- **Serialized chart sourcing**: querystore is the only source of chart records — `getPatientChart(patientUuid)` for the whole chart, a typed/similarity slice for the default `queryScoped` mode. There is no in-process fallback and no toggle: the legacy path, and the in-memory `ChartCache` that amortized it, were removed in the querystore migration (#51). The read store keeps per-type indices current off core's events, so the chart returns at index-read latency without round-tripping core. The LLM-side prompt-prefix cache (KV reuse on llama-server, provider-managed caching on remote) is unaffected.
- **Rate limiting**: Configurable per-user rate limit (`chartsearchai.rateLimitPerMinute`, default 10). Set to 0 to disable.
- **Database audit logging**: Every query is recorded in the `chartsearchai_audit_log` table with:
  - The authenticated user and patient
  - The question asked and the LLM's response
  - The number of source references returned
  - The search mode used: `queryScoped` (the [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped) default), or `full-chart`, or `pre-filter` for a full chart carrying a focus hint. Stated by the pipeline on the answer and written unchanged, rather than derived at the REST layer — [#178](https://github.com/openmrs/openmrs-module-chartsearchai/issues/178) was that derivation, which branched on `chartsearchai.embedding.preFilter` alone and so could never record `queryScoped` at all. A fourth value, `unknown`, is written when an answer states no mode — unreachable from the shipped pipeline, but a deployment that substitutes its own `chartSearchAi.chartSearchServiceRouter` bean can produce it, so a consumer mapping this column must handle it rather than assume three
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
3. `chartsearchai.llm.timeoutSeconds` (default 300s) does not bound that wait. It is a JDK `HttpRequest.timeout()` on the call to the inference server, so it does not start until a request already holds the lock, and it stops applying the moment that server's response headers arrive. On the streaming endpoint those arrive with the first token, so it bounds the prefill and leaves the queueing and the answer's own generation uncapped. Measured 2026-08-20 — README's [Streaming search (SSE)](../README.md#streaming-search-sse) keep-alive paragraph carries the figures.

With an 8B model on CPU, a single query typically takes 15–45 seconds, so roughly **2–3 concurrent users** can be served before the queue wait is longer than a clinician will sit through. What happens then differs by endpoint, and only since the SSE keep-alive: `/search/stream` starts writing comment frames before it calls the service, so a request still queueing on the engine lock is not read-idle and nothing cuts it off — it waits. A blocking `/search` queued that long writes nothing and is still cut by the proxy's read timeout. Smaller models (3B) are faster but produce lower quality responses; larger models (12B) have slower inference and reduce concurrency further.

Embedding computation is faster (~50–200ms per patient) so the embedding lock is rarely a bottleneck. The remote engine has no client-side serialisation — concurrency limits are whatever the remote server (vLLM, Ollama, OpenAI) imposes.

### Existing mitigations

- **Answer cache** (`chartsearchai.cacheTtlMinutes`): Identical (patient, question) pairs return cached results without acquiring the LLM lock.
- **Rate limiter** (`chartsearchai.rateLimitPerMinute`): Limits per-user query frequency, reducing queue depth.
- **Configurable timeout** (`chartsearchai.llm.timeoutSeconds`): Caps how long one request can wait for the inference server's first output while holding the lock, so a call that stalls before producing anything cannot block the queue indefinitely. It does not bound the queue wait itself, or the generation that follows that first output (see point 3 above).

### Future options (not yet implemented)

- **Multiple model instances**: Load the LLM into separate native contexts and round-robin across them. Trades RAM for throughput (each 8B instance adds ~6–8GB).
- **Request queuing with position feedback**: Return queue position to the client via SSE so the UI can show "you are #3 in queue" instead of hanging silently.
- **External inference server**: Offload to a dedicated inference server (e.g., llama.cpp server mode, vLLM, Ollama) that manages its own concurrency. This decouples the module from native memory constraints but adds an external dependency.

For the initial release targeting small clinics with low concurrent usage, the serialized approach is acceptable and avoids the complexity of managing multiple native contexts.

## Decision 13: Lucene BM25 as an alternative retrieval pipeline

**Status: Superseded** — the in-process retrieval stack this decision describes (embedding index, vector store, Lucene/Elasticsearch pipelines, the `chartsearchai.retrieval.pipeline` and `chartsearchai.embedding.*` global properties) was **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)). Retrieval now belongs entirely to [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) — see [Decision 22](#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) and [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped) for what runs today. Kept as the record of *why* the approach was taken; **read the body as history, not as current behaviour.**

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

**Status: Superseded** — the in-process retrieval stack this decision describes (embedding index, vector store, Lucene/Elasticsearch pipelines, the `chartsearchai.retrieval.pipeline` and `chartsearchai.embedding.*` global properties) was **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)). Retrieval now belongs entirely to [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) — see [Decision 22](#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) and [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped) for what runs today. Kept as the record of *why* the approach was taken; **read the body as history, not as current behaviour.**

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

**Status: Superseded** — the in-process retrieval stack this decision describes (embedding index, vector store, Lucene/Elasticsearch pipelines, the `chartsearchai.retrieval.pipeline` and `chartsearchai.embedding.*` global properties) was **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)). Retrieval now belongs entirely to [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) — see [Decision 22](#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) and [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped) for what runs today. Kept as the record of *why* the approach was taken; **read the body as history, not as current behaviour.**

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

**Status: Accepted; the comparison table is stale.** The decision stands. Several "existing implementation" entries below name code **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)) — `PatientRecordLoader`, the per-type text serializers, `OnnxEmbeddingProvider`, the `chartsearchai_embedding` table, and the three retrieval pipelines. Read the table as what the module had when the decision was taken.

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

**Status: Superseded** — the in-process retrieval stack this decision describes (embedding index, vector store, Lucene/Elasticsearch pipelines, the `chartsearchai.retrieval.pipeline` and `chartsearchai.embedding.*` global properties) was **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)). Retrieval now belongs entirely to [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) — see [Decision 22](#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) and [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped) for what runs today. Kept as the record of *why* the approach was taken; **read the body as history, not as current behaviour.**

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

**Status: Superseded** — the in-process retrieval stack this decision describes (embedding index, vector store, Lucene/Elasticsearch pipelines, the `chartsearchai.retrieval.pipeline` and `chartsearchai.embedding.*` global properties) was **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)). Retrieval now belongs entirely to [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) — see [Decision 22](#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) and [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped) for what runs today. Kept as the record of *why* the approach was taken; **read the body as history, not as current behaviour.**

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

> Both baseline files were **deleted** in #179. Their tests (`EnrichedRetrievalEvalTest`, `RetrievalQualityEvalTest`) went with the rest of the in-process stack in #51, leaving 185 KB of relevance judgments and pinned ranked lists that nothing loaded — and retrieval-quality evaluation belongs to querystore now, so they could not be revived here either. The vectors they were paired with (`api/src/test/resources/embedding-cache/`, 794 files / 6,555,996 B) were deleted in **#204** for the same reason: nothing read them, and cached embedding vectors in the test resources of a module that must not have an embedding pipeline are an invitation to rebuild one. No figure in this decision rests on them — they were a speed optimisation for a harness that no longer exists, and the numbers above came from that harness's runs.

### ONNX external data workaround

MedCPT's ONNX models split weights into `model.onnx` + `model.onnx.data`. The ONNX runtime 1.24.3 has a bug where it resolves the data file path as `<model_path>/model.onnx.data` instead of `<model_dir>/model.onnx.data`. The workaround is to merge weights back into a single file using `onnx.save()`. A `createSessionWithExternalData()` method attempts canonical path resolution first, falling back to byte-array loading.

## Decision 21: Concept-name re-ranking for subword-tokenized queries

**Status: Superseded** — the in-process retrieval stack this decision describes (embedding index, vector store, Lucene/Elasticsearch pipelines, the `chartsearchai.retrieval.pipeline` and `chartsearchai.embedding.*` global properties) was **removed** in the querystore migration ([#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51)). Retrieval now belongs entirely to [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) — see [Decision 22](#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) and [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped) for what runs today. Kept as the record of *why* the approach was taken; **read the body as history, not as current behaviour.**

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

Decisions 19–21 cover the chartsearchai-side pre-filter pipeline — `all-MiniLM-L6-v2` + adaptive filtering (similarity ratio, gap detection, z-score gates, concept-name re-ranking) co-evolved against the 485-case eval baseline. That pipeline no longer exists — it was removed in #51, and querystore is now the only retrieval path.

In the querystore-backed path:

- chartsearchai serializes patient records.
- [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) indexes them (Lucene BM25, plus optional ONNX kNN).
- Querystore returns the top-K candidates per question.
- The local LLM filters those candidates as part of the answer phase.

No L6-v2-tuned thresholds run at the querystore layer. The "filter" stage is the LLM itself reading the top-K and choosing what to cite.

### Problem

With the LLM doing the filtering, the embedder's job changes. It no longer needs to feed an adaptive filtering pipeline that depends on a specific score-distribution geometry; it just needs to put plausible candidates in the top-K so the LLM can reason over them. The dominant failure mode in this regime is **vocabulary gaps** — clinical records use formal terms ("Cerebrovascular Accident") while users type colloquial ones ("any heart issues?", "any cardiovascular issues?"). If the embedder doesn't cluster these, the top-K never contains the relevant record and the LLM can't recover.

### Evaluation

Three embedders were compared on a standalone with real OpenMRS demo data, over the querystore path, with Gemma 4 E4B as the filtering LLM:

| Embedder | Colloquial → clinical bridge | Source | Notes |
|---|---|---|---|
| `sentence-transformers/all-MiniLM-L6-v2` | Misses (CVA in chart, "any heart issues?" returns 0 cardiovascular records) | self-contained ONNX | Decision 19's winner for the pre-filter pipeline, but its tighter clusters fail on this question class without the adaptive filtering to compensate |
| `intfloat/e5-base-v2` (via `Xenova/e5-base-v2`) | **Bridges** ("heart issues" surfaces CVA records the LLM correctly cites) | self-contained ONNX | Empirical winner for the querystore + LLM-as-filter path |
| `ncbi/MedCPT-*` (dual-encoder) | Refuses to bridge — PubMed-trained clusters pedantically separate colloquial from clinical | dual ONNX | Optimized for PubMed corpus; doesn't generalize to the chart-search user's phrasing |

### Decision

Use `intfloat/e5-base-v2` as the querystore embedder. `backend-init.sh` provisions it automatically for Docker deployments; non-Docker installs should download it manually to `<openmrs-application-data-directory>/querystore/`.

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

## Decision 23: Drug-reference injection + post-answer drug-safety validation

**Status: Accepted** (June 2026)

### Context

Chart-search answers are grounded only in what the patient's chart contains. The chart says *what was prescribed*; it does not carry the reference facts a clinician weighs against it — published dosing maxima, drug–drug interactions, allergy/condition contraindications. Asked "is ibuprofen safe for this patient?" against a chart with an NSAID allergy, the LLM can reason it out only if the allergy record happens to surface *and* the model connects "NSAID" to "ibuprofen" — a colloquial-to-clinical bridge it makes unreliably, and exactly the gap the "Concept graph traversal" future-work item (see Planned future work) was meant to close through retrieval.

### Decision

Add an additive, opt-in (`chartsearchai.drugReference.enabled`, default `false`) drug-reference subsystem in two deterministic parts:

1. **Injection (`DrugReferenceInjector`, pre-answer)** — append reference entries matching the question (by alias) or the patient's active orders (by whatever resolves them — an ATC code or the order's display name; ATC-only until #151) to the serialized chart as numbered, citable records carrying the `drug_reference` resource type. Numeric dosing is age-gated (a pediatric maximum is never surfaced for an adult). The LLM cites them the same way it cites chart records; the system prompt notes that `drug_reference` records are reference data, not the patient's own.
2. **Validation (`DrugSafetyValidator`, post-answer)** — a deterministic check that annotates the answer with non-blocking `SafetyWarning`s (overdose / interaction / contraindication), cross-referencing the reference table against the patient's age, active orders, allergies, and conditions. It never rewrites or blocks the answer.

### Why deterministic + data-driven

The clinical knowledge lives in a configurable JSON dataset (`drug-reference.json`, operator-overridable via `chartsearchai.drugReference.dataFilePath`, with the bundled dataset as a classpath fallback), **not** in the algorithm — consistent with the project rule against encoding clinical domain knowledge into code. The matching (alias / ATC), age-banding, and safety checks are domain-agnostic mechanisms over that data. The validator runs deterministically (no second LLM call) so the safety net does not inherit the LLM's variability, and it is conservative by construction: a warning fires only when a dose can be computed and exceeds a published maximum, or a named drug matches an interaction/contraindication rule against real patient data — so a chart-sufficient answer naming no reference drug produces nothing.

### Dose parser: clause-scoped, alias-anchored

The overdose check parses `(drug, mg, frequency)` from the free-text answer. To avoid false alarms, dose attribution is **clause-scoped and anchored to the nearest drug name**: a dose stated for one drug in a neighbouring clause is not charged to another (`"ibuprofen or paracetamol 1000 mg"` does not flag ibuprofen), a number introduced by a limit cue (`"maximum 2400 mg/day"`) is treated as a ceiling rather than a prescribed dose, and frequency word-forms are word-boundary matched (`"bd"` does not match inside `"abdominal"`). Known v1 limitation: only the literal unit `mg` is parsed — doses in grams are not flagged (the conservative, miss-not-false-positive direction).

### Wire & frontend

`ChartAnswer` carries `safetyWarnings`; the REST controller emits a `safetyWarnings` array (`{ type, drug, detail }`) on both the blocking `/search` response and the streaming `done` event (always present, possibly empty). The [frontend ESM](https://github.com/openmrs/openmrs-esm-chartsearchai) renders them as colour-coded chips below the answer and renders `drug_reference` citations as non-navigating reference chips — it currently identifies those by testing `resourceType` itself, which the per-reference `group` discriminator now supersedes (see the `references` shape above); adopting `group` there is outstanding follow-up. With the feature off (the default), the wire carries an empty array and the frontend is a no-op.

### Trade-offs

- **+** Reference facts (dosing / interactions / contraindications) the chart alone can't provide, cited and grounded like any record, plus a deterministic safety net independent of LLM variability.
- **+** Knowledge is data, not code — operators extend `drug-reference.json` without a rebuild.
- **−** The seed dataset is small (ibuprofen, paracetamol, amoxicillin, gentamicin; WHO Model List of Essential Medicines for Children) — coverage expands per deployment.
- **−** The overdose arm depends on the LLM stating a parseable dose in the answer; with chart-grounded prompts the model often recites reference maxima rather than proposing doses, so in practice the interaction/contraindication arms (which only need the drug *named* plus matching patient data) fire more than the overdose arm.
- **−** Injected `drug_reference` records add a little answer/grounding latency when the feature is enabled and a reference is cited.

## Decision 24: Drug-reference as a pluggable consumer of authoritative datasets

**Status: Accepted** (June 2026) — implemented. Extends [Decision 23](#decision-23-drug-reference-injection--post-answer-drug-safety-validation).

### Context

Decision 23 shipped the drug-reference feature with a hand-curated, chartsearchai-specific `drug-reference.json` seeded from four drugs (from the WHO Model List of Essential Medicines for Children). That is fine as a proof-of-concept seed but is **not a maintainable knowledge base**: clinical drug knowledge spans thousands of agents, dosing varies by formulation/age/renal function, and interactions are combinatorial. Hand-curating an entry per drug does not scale and rots immediately.

The "is aspirin safe?" case made the deeper point concrete. The warning a clinician expects there is not an *aspirin* fact — it is a **class** fact: aspirin and ibuprofen are both NSAIDs, and the patient is allergic to the NSAID class. Encoding it as a per-drug aspirin entry "works," but then the same NSAID-allergy rule must be re-encoded on naproxen, diclofenac, ketorolac, … indefinitely.

### Constraints

The maintainer's requirements, which this decision is bound by:

1. **Do not extend or maintain the OpenMRS concept dictionary** for this — an explicit maintenance burden to avoid.
2. **Do not hand-maintain per-drug data** inside chartsearchai.
3. **Consume authoritative datasets** published by recognised global bodies (WHO and similar) that can be *downloaded and refreshed* rather than authored.
4. The system should be **ready to consume such a dataset by simply pointing at it** when one is available.

### Decision

Refactor the drug-reference *data layer* into a **pluggable source/adapter architecture**:

- A `DrugReferenceSource` interface over the existing internal model.
- **Format adapters** that map an external dataset's *native* format → that model, so a dataset is consumed as published rather than hand-transformed into chartsearchai's bespoke schema.
- A global property selecting the adapter/format, reusing the existing `chartsearchai.drugReference.dataFilePath` to locate the file.

Incorporating an authoritative dataset then becomes: **drop the file, point the GP at it, select the adapter** — no code, no curation, no dictionary changes. The knowledge (drug → class, dosing references) comes entirely from the consumed dataset, so there is **no dependency on the OpenMRS concept dictionary** (constraint 1). The current bespoke JSON is demoted to *one* adapter plus a small fallback example — not the artifact anyone maintains.

### Class-based reasoning is the scalable mechanism

The first and most important adapter consumes the **WHO ATC classification**. ATC gives the class hierarchy, so a drug named in the question/answer resolves to its class *from the dataset*, and the safety checks run at the **class** level — one rule per class (e.g. "an NSAID-class allergy contraindicates NSAID-class drugs") covers the whole family. This removes the per-drug treadmill and answers "do we do this for every drug?" directly: **no — classify, then reason by class, from a downloaded table.**

### Honest data-availability matrix

Not every safety dimension has a clean, free, downloadable authoritative dataset. Building with eyes open:

| Dimension | Authoritative downloadable source? | Notes |
|---|---|---|
| **Drug classification** | **Yes — WHO ATC** | Enables class-based allergy/interaction reasoning (the scalable win). WHOCC bulk ATC/DDD data carries use-terms; the cleanest machine-readable form is often an RxNorm↔ATC crosswalk. |
| **Essential-medicines list** | **Yes — WHO EML** | A curated formulary list. |
| **Max / safe daily dose** | **No (free)** | WHO **DDD is a drug-*utilization* statistic, not a clinical ceiling**, and is adult-only — it must **not** drive overdose checks. Dosing maxima live in the WHO Model Formulary as prose, not a dataset. |
| **Pairwise interactions / contraindication rules** | **No (free) when this was written; partly yes since [Decision 36](#decision-36-the-shipped-default-is-the-whole-ddinter-knowledge-base)** | Structured rules live in prose formularies or in **commercial** databases (First Databank, Lexicomp, Medi-Span, full DrugBank), and the adapter lets one be plugged in when licensed. What the `ddinter` adapter then found is a third position this row did not anticipate: DDInter 2.0 publishes ~295,000 severity-rated INTERACTION pairs free of charge, and the module now bundles them — but under CC BY-NC-SA terms and from an academic group rather than an agency, so it is free-and-usable rather than free-and-authoritative, which is why Decision 36 carries the governance caveat instead of deleting this row. CONTRAINDICATION rules are still unavailable: DDInter publishes none. |

So pointing at WHO ATC delivers **class-based contraindication/interaction reasoning today**; the **exact-dosing** dimension has no free authoritative dataset and remains either a small curated seed or a licensed source loaded through the same adapter. This is a documented bound, not a regression — it is the same or better than the Decision 23 seed. The **pairwise-rule** dimension was in that sentence too until [Decision 36](#decision-36-the-shipped-default-is-the-whole-ddinter-knowledge-base) shipped DDInter as the default; dosing is what is left of the bound.

### Relationship to other decisions

- **Extends Decision 23**: the bespoke JSON becomes one adapter + fallback; the feature's *behaviour* (injection + post-answer validation, opt-in / default-off, the clause-scoped dose parser) is unchanged.
- **Deliberately does not take the "concept graph traversal" route** listed in Planned future work for drug safety. That item framed class reasoning as an *OpenMRS-dictionary* traversal; per constraint 1 the class knowledge is instead carried in the **consumed external dataset**. The two remain complementary — concept-graph traversal is still relevant to *retrieval*.

### Trade-offs

- **+** No per-drug curation and no concept-dictionary maintenance; coverage is refreshed by downloading a newer dataset.
- **+** Class-based reasoning covers whole drug families from one table (solves aspirin/NSAID and its siblings at once).
- **+** Authoritative provenance (WHO et al.) instead of a hand-written file.
- **−** No free authoritative dataset exists for exact dosing maxima or pairwise interaction/contraindication rules — those stay curated-seed or licensed.
- **−** WHO ATC/DDD bulk data carries use-terms (not a no-strings-open CSV); an RxNorm↔ATC crosswalk may be the practical source.
- **−** Class-based allergy matching needs the recorded allergy to resolve to a class: free-text "NSAID" matches directly, but a coded allergen needs a class mapping that must come from the consumed dataset (not the OpenMRS dictionary, per constraint 1) — an edge for the ATC adapter to handle.

### Implementation

- `DrugReferenceSource` interface; `JsonDrugReferenceSource` (the bespoke schema, retained as one adapter + bundled fallback); `AtcDrugReferenceSource` (WHO ATC / RxNorm-ATC crosswalk → one classification entry per level-5 substance, `drugClass` derived from the nearest parent group present in the dataset).
- GP `chartsearchai.drugReference.sourceFormat` (`json` | `atc` | `ddinter`) alongside the existing `dataFilePath`.
- **Class-level contraindication / interaction matching in `DrugSafetyValidator`**, keyed on ATC class rather than per-drug rules, so a rule-less classification source still produces safety reasoning:
  - **Contraindication** — fires when a recorded allergy resolves (by name) to the drug in play (a direct allergy that the rule-less source would otherwise miss) **or** to a drug sharing its ATC class (cross-reactivity). What deduplicates what is decided in `DrugSafetyValidator` and stated there, at `recordedAllergens` and at `ContraindicationChips` (with the key itself in `contraindicationFinding`) — do not restate it here; it has moved three times ([#145](https://github.com/openmrs/openmrs-module-chartsearchai/issues/145), [#176](https://github.com/openmrs/openmrs-module-chartsearchai/issues/176), [#146](https://github.com/openmrs/openmrs-module-chartsearchai/issues/146)). The direct-allergy comparison is **not** class-level reasoning, even though it lives in the same method: it compares the two substances (a reference comparison until [#164](https://github.com/openmrs/openmrs-module-chartsearchai/issues/164)), so it needs no ATC code and must not be gated on one. Listing it under this bullet is what made a classification guard in front of the whole method look right, and that guard silently suppressed the direct-allergy chip for every entry carrying no ATC code — 444 of the full DDInter dataset's 2283 ([#135](https://github.com/openmrs/openmrs-module-chartsearchai/issues/135)); the guard now sits inside the per-allergen loop, after the identity comparison and before the class comparisons that follow it (the shared-subgroup one here, plus the curated cross-reactivity group added by Decision 27). **All three comparisons also run over the patient's own ACTIVE ORDERS**, not only over the drug in play: keyed on the drug in play alone, the check could not ask "is the patient allergic to something they are *taking*?", and echo scoping ([#105](https://github.com/openmrs/openmrs-module-chartsearchai/issues/105)) actively withheld it — a drug the patient is prescribed appears in a cited `drug_order` chart record, so the answer's mention of it counted as an echo rather than a proposal ([#143](https://github.com/openmrs/openmrs-module-chartsearchai/issues/143)). The order subjects come from the same `activeOrderEntries` definition the interaction screen uses, an entry already in play is skipped so nothing is checked twice, and the arm stands down when the patient has neither an allergy nor a condition record. That order-driven arm is **scoped to what the response is about** — either side of the chip, the drug or the recorded finding, must be named by the question, the answer or a record the answer cited, with a medication-, allergy- or condition-domain question keeping the corresponding list in scope wholesale. Unscoped it raised the identical chips on every response regardless of topic (measured on the 3.7.1 standalone: four different questions — two of them about drugs — returned the identical chips, down to one about a date of birth), which is an alert rather than an annotation, and this module has no subscription, acknowledgement or unprompted delivery path to carry one. The finding it stops announcing — a prescribed drug nobody asks a drug-shaped question about — needs a surface that has those ([#280](https://github.com/openmrs/openmrs-module-chartsearchai/issues/280)).
  - **Interaction** — fires when the answer's drug shares an ATC class with an active order (additive effects / duplicate therapy), skipping the co-medication that **is** that same drug — and, where the reference data cannot name one of an order's codes, whatever that order's own recorded name says it contains (restating existing therapy is not a duplicate). What counts as the same drug is decided in `DrugSafetyValidator.classRelationships` and stated there; do not restate it here. It was a shared exact ATC code ALONE until [#185](https://github.com/openmrs/openmrs-module-chartsearchai/issues/185), which is how a drug came to be reported as duplicating the patient's own order of it — a proxy standing in for the question it was proxying for.
  - Both are **additive** to the rule-based checks and reuse the existing `warnOnContraindications` / `warnOnInteractions` toggles.
- **"Same class" = ATC level-4 (chemical subgroup, the 5-character prefix `M01AE`), not level-3.** Level 4 groups structurally-related drugs (ibuprofen/naproxen) with the fewest false positives; level 3 (`M01A` = all NSAIDs) fans out far more broadly with no curated data to justify it. This is the project's standing anti-false-positive stance (the dose-parser hardening existed for the same reason) applied to class breadth.
- **Documented boundary (unchanged from the matrix above):** ATC's tree does not capture cross-*branch* cross-reactivity — aspirin `N02BA01` (salicylates) is a different ATC branch from ibuprofen `M01AE01` (propionic NSAIDs), so an ibuprofen allergy does **not** flag aspirin by class alone. That linkage needs a curated cross-reactivity dataset, not classification, and is asserted as a boundary test (`classContraindicationNotRaisedAcrossDifferentAtcBranch`).

## Decision 25: Citation grounding (Tier-1 cosine + Tier-2 entailment)

**Status: Accepted** (June 2026) — implemented, opt-in / default-off.

### Context

The LLM cites records by number (`[N]`), and the REST layer already validates that every `[N]` maps to a real record in the retrieved set. That index check is necessary but not sufficient: it confirms the citation *points at something real*, not that the pointed-at record *actually supports the claim*. The dangerous failure is a real, retrieved record cited for a claim it does not back — a blood-pressure record cited for a diabetes statement, or "the patient has X [5]" where record 5 says a *relative* had X (or negates X). A small local model produces these confidently, and a citation that survives index validation looks trustworthy in the UI.

### Decision

Add an opt-in post-answer grounding pass (`chartsearchai.grounding.enabled`, default `false`) that verifies each citation against the record it points to and annotates the reference with a `grounded` verdict (`true` / `false` / `null` when unchecked or, per the compound-claim and composite-claim rules below, uncertifiable). It is **advisory only** — it never rewrites, reorders, or blocks the answer, and never changes which records are cited; the per-citation `grounded` verdict lets clients surface unverified citations distinctly, and the clinician decides. Verification is two-tier, each tier independently toggleable:

1. **Tier-1 (cosine, `chartsearchai.grounding.minCosine`, default `0.40`)** — the cited record's text must be semantically close to the answer sentence that cites it. Cheap (an embedding cosine, no extra LLM call), and catches grossly off-topic citations. It cannot catch subtle subject/negation flips, where the record and sentence share most of their vocabulary.
2. **Tier-2 (entailment, `chartsearchai.grounding.entailment.enabled`, default `false`)** — a yes/no LLM judgement of whether the record entails the sentence. This separates high-overlap-but-false citations that cosine cannot. An answer's citations are verified in a **single batched LLM call** (capped per answer) so Tier-2 costs one extra round-trip, not one per citation.

### Grounding unit: the rendered chart line, clause-scoped

Two refinements make the verdict match what the citation actually claims:

- **Ground against the rendered chart line (date + body), not the bare record text** — the model sees dated chart lines, so the verifier compares against the same surface.
- **Clause-scoping (`chartsearchai.grounding.clauseScoped`, default `false`, sentence-scoped)** — in a sentence citing multiple records, each citation is checked against the cumulative answer prefix *up to and including its own `[N]` marker* rather than the whole compound sentence. This flags a citation that supports its own clause but not a *later* clause cited by a different record — e.g. "Hearing Loss was noted as a condition [89] and diagnosed as a provisional condition [91]", where [89] (an active condition) does not back the "provisional diagnosis" clause that [91] supports. The prefix keeps the sentence subject (which normally precedes the first marker), so it still flags family-history/negation flips in later clauses. It is left off by default because per-pair Tier-2 batching is not yet fully independent (shortening an earlier cite's statement can flip a later cite's verdict — tracked separately).

- **Enumerating sentences are split per item regardless of that flag** ([#278](https://github.com/openmrs/openmrs-module-chartsearchai/issues/278)) — where a sentence announces a list with a colon before its first marker, each citation is checked against the preamble plus its OWN item, not the whole sentence and not the cumulative prefix. Both of the scopings above ask the wrong-sized question of a list: the whole sentence makes each record answer for a conjunction naming the others, and the cumulative prefix still names items 1..*k*−1, so only the first citation is ever asked about its own claim. Measured live, a correct three-allergen answer had every chart citation published `grounded=false`, which a client renders as *Unsupported*. This is not gated on `clauseScoped` because the claim is **mis-identified** rather than wide-scoped, and the defect bites on the shipped default. It also does not inherit that flag's reason for being off: these fragments are Tier-2 verified one pair per call, so the batching-independence problem above does not arise — at a measured ~0.5s per additional citation, bounded by `GROUNDING_ENTAILMENT_MAX_CHECKS`. Keyed on the colon, and the colon alone is deliberately not enough — the items must be name-shaped too, since the split is sound only while the preamble holds the SUBJECT. An item carrying its own subject (a pronoun, or a finite verb of clinical assertion) or running past a length backstop keeps whole-sentence scoping; measured through the production splitter over TWO corpora — the 7452 names the shipped KB publishes and the 1194 distinct condition/diagnosis/allergen forms on the demo database — the grammar test refuses none of either, and the length backstop refuses 93 and 24 respectively. Sweep BOTH before changing the grammar set: the drug KB alone would have cleared "patient", which the clinical corpus showed refuses "Patient died" and "Smear positive, new tuberculosis patient", and family terms were measured and rejected against both (13 refusals, 6 on "child"). A comma-only enumeration has no recoverable preamble/first-item boundary (in "Has diabetes [1] and hypertension [2]" the preamble could be "Has" or "Has diabetes"), and guessing it short strips the subject the prefix rule exists to retain — so that case is left unsplit. What being unsplit costs it is bounded by the compound-claim rule below: it stays mis-scoped, but the module no longer publishes the conjunction's refusal as each citation's own verdict.

### Cost shaping

The grounding pass is pure overhead on the user's critical path, and on CPU-only servers the Tier-2 LLM call adds seconds *after* the answer is already readable. Two mechanisms keep it from regressing perceived latency:

- **Lazy Tier-1** — when Tier-2 entailment is on, the Tier-1 cosine is computed only for citations where the LLM check produced no verdict AND a Tier-1 verdict could still be published (since [#302](https://github.com/openmrs/openmrs-module-chartsearchai/issues/302), a compound claim unit publishes neither, so the judge is not asked and the lazy fallback does not run for it). Tier-2 is authoritative where it spoke, so the embedding work (and any embedding-model requirement) is skipped for those citations — grounding then works with no Tier-1 embedding model configured, for any citation the judge is asked about. Since #302 it is not asked about a compound claim unit, which on an embedder-less deployment is therefore left unverified by both tiers.
- **Async grounding (`chartsearchai.grounding.async`, default `false`, streaming only)** — the SSE `done` event is emitted as soon as the answer is complete (its references carrying no verdicts yet), and the verdicts arrive afterward in a trailing `grounded` event. The Tier-2 tail moves off the user's perceived completion time; clients keep consuming the stream after `done` and apply verdicts when they land. The blocking `/search` endpoint is unaffected and always returns final verdicts.

### Model-dependent cosine floor

`minCosine` is **not** a universal constant — it tracks the embedding model's score-spread geometry. `0.40` suits a wide-spread model like all-MiniLM-L6-v2 but is far too low for e5, whose grounded pairs sit much higher; on an e5/querystore deployment the floor must be ~`0.82`. The verifier **reuses querystore's own embedding provider** rather than loading a second model, so the floor must match whatever model querystore is configured with. This is the same per-model-tuning stance as the retrieval pipeline (see [Decision 19](#decision-19-retain-all-minilm-l6-v2-as-the-embedding-model) / [Decision 22](#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path)).

### Reference-group citations: demote-only, and their verdict unpublished (July 2026)

Injected `drug_reference` records (Decision 24) break both tiers' assumptions once a broad source like DDInter makes reference prose long and drug-dense ([#106](https://github.com/openmrs/openmrs-module-chartsearchai/issues/106)). An answer sentence citing one is a recitation of module-rendered prose: it embeds near-identically to its source even when it swaps subject roles ("erythromycin decreases X" where the record says "ivosidenib decreases X … including erythromycin"), and the same lexical containment defeats the Tier-2 judge — measured live, 4/4 role-swapped recitations were judged entailed while the one faithful recitation was judged not. So drug-reference citations never enter Tier-2 (and do not consume its per-answer cap), and Tier-1 may only **demote**: an off-topic citation still flags `false` (except where it also sits inside a compound claim unit, where the #302 rule below withholds even that), but a pass renders `null` (unverified), never `true`. The faithfulness check for reference content is the deterministic safety validator (Decision 24), not this pass.

The rule is keyed on the reference **group** rather than on the `drug_reference` type name, so `safety_finding` ([#110](https://github.com/openmrs/openmrs-module-chartsearchai/issues/110)) inherited it instead of being forgotten a second time ([#122](https://github.com/openmrs/openmrs-module-chartsearchai/issues/122)).

**The surviving `false` is not published (August 2026, [#201](https://github.com/openmrs/openmrs-module-chartsearchai/issues/201)).** A `reference`-group citation now serializes `grounded: null` at every emission site, whatever the pass concluded. The verdict is well-defined but only in terms of `group`: it means "this citation is not about that record", where a chart citation's `false` means "this claim may not be supported". A client that classifies citations by `resourceType` instead has no way to tell those apart — the reference frontend does exactly that, and rendered *"Unsupported — the cited record may not support this statement"*, in red, on the module's own deterministic Major-interaction finding. The two settlements were "the client reads `group`" and "the wire stops offering a value that must not be read"; the second was taken, because it holds for every client rather than for the one that is patched, and because a field whose correct interpretation requires a second field is a trap. `null` rather than an omitted key: `null` already means "not verified" here, clients are already told never to render it as verified, and the key's unconditional presence is relied on. The grounding pass itself is unchanged: it still computes the verdict and `RecordReference.getGrounded()` still carries it inside the module. What is given up is the off-topic signal *on that channel*, and no client is known to have read it correctly.

### A compound claim unit cannot be verified per citation, so it publishes no verdict (August 2026, [#302](https://github.com/openmrs/openmrs-module-chartsearchai/issues/302))

The enumeration split above is keyed on a list-introducing colon, and #279 recorded what that leaves behind: a comma-only list is not split. Measured live, the residual is not a tail case — on *"What medications is this patient currently taking?"*, over the 12 patients on a 3.7.1 standalone carrying an active drug order, **8 of the 30 chart citations published `grounded=false`, every one of them in the colon-less multi-citation shape** — and of those, the five the issue checked against the standalone's database name the right patient's right drug, active and unvoided. The discriminator was a single character: the same list with a colon graded `true`.

The colon is not the cause. What decides a citation's fate is whether its **claim unit** is that citation's own claim. A claim unit that attaches its citations to different pieces of its own text states a conjunction, and no one cited record answers for all of it — so a correct judge replies "no" to every one of them, and a "no" there is the expected reply whether the citation is right or wrong. #279 shrank the population of such claim units; it did not change what a verdict on one **means**. Publishing that verdict is asserting a negative the pass never established.

**The decision.** A compound claim unit — more than one attributed citation, with claim text standing between two of its `[N]` markers — publishes NO verdict for any of its citations: `null` in either direction. They never enter Tier-2, never consume its per-answer cap, and no Tier-1 cosine is computed for publication — claim selection still embeds where several sentences cite one record and the argmax must choose between them, and that verdict is then discarded. This is stricter than the reference-group rule, which is demote-only and keeps its cosine FAIL — except where the two overlap, and this one wins; they are otherwise independent, and this one turns on the shape of the CLAIM rather than the provenance of the RECORD.

**It applies under entailment only, and that asymmetry with the reference-group rule is deliberate.** With Tier-2 off, cosine is not standing in for a judge that could not be asked — it is the whole of what the mode promises, every verdict in it is that same comparison, and sentence scope has always compared against the whole compound sentence — `clauseScoped` is this module's remedy for that and is untouched here. Demoting in that mode would suppress the PASS of a comparison whose FAIL is still published, costing a correct citation its verdict for no defect removed. It is not a small population either: #302's sweep puts 10 of 30 chart citations in the colon-less multi-citation row, of which this rule reaches those whose markers are separated by claim text. The issue folds the co-cited `Salicylic acid [1], [2].` citation into **both** that 10 and the 8 published `false` ("its citation is included in the 10/8 above"), so the rule does not reach all 8 — that sub-shape stays graded, deliberately.

Three things this deliberately does **not** do:

- **It does not narrow the statement.** No preamble boundary is guessed without a colon, so the subject-stripping hazard #279 argues from is not reintroduced, and the enumeration split is byte-identical. This is also why the remedy suits the composite safety answer the module's own few-shot teaches (`"No — durian should not be delivered: it spoils the oranges already in store [2], a Major problem [4]."`, one claim unit citing both): #302's own words are that in a composite claim *"no narrowing of the statement can help, because the minimal statement carrying the citation still asserts the relationship"*. Its reference-group half already publishes nothing (#201), so only the chart half changes.
- **It silences the cosine FAIL as well, and that was the hard part.** The first draft kept it, on the argument that sentence scope has always flagged such a citation. Measured, that reintroduced the issue's own harm on one cell: with a record whose cosine is diluted below the floor and a lenient judge, main published `true` (the judge rescuing the score) and the draft published `false`. Cosine against the conjunction is the same wrong-sized question the judge was asked, so its refusal is not better evidence — a compound unit publishes nothing. Guarded by `compoundClaim_anEagerlyScoredCosineFailIsWithheldToo`, the only case that reaches the branch — the others take the deferred path, where no cosine is computed and the null comes from the lazy-Tier-1 skip instead. What FOUND the defect was a different case, `compoundClaim_publishesNothingWhicheverWayTheJudgeWouldHaveAnswered`, and its LENIENT judge stub: every other compound case drives `ConjunctionAwareJudge`, which always refuses a conjunction, so the judge-yes cell was assumed rather than tested for four review cycles.
- **It does not touch CO-CITATION.** Where nothing but a list separator stands between the markers (`Infections [5], [12], [15]`), every record is cited for the same statement, that statement IS each citation's own claim, and the judge's question is well-formed. The module manufactures this shape itself: `LlmAnswerExtractor.normalizeSlashCitations` rewrites a corroborated `[5/12/15]` group into exactly it. Keying the rule on the citation count alone would have silenced it, which is why the rule requires claim text between the markers. It also leaves #302's own closing sub-shape (`Salicylic acid [1], [2].`) graded as today, since it is textually the same thing.

**Costs, and what stays open.** The rule only removes work: nothing is published for these citations, so the judge is never asked and the lazy Tier-1 fallback never runs. Claim SELECTION still embeds where several sentences cite one record and the cosine argmax has to choose between them — that predates this rule and is unchanged by it, which is why the A/B below adds no passes rather than removing them all. A/B through the real `verify` over a 12-citation answer with entailment on — one compound line citing all 12 drops the batch entirely (1 `entailsBatch` call to 0, 12 pairs to 0) and adds no embedding passes; 8 sentences of which 4 are compound, covering 9 of the 12, keep the batch for the rest (1 call to 1, 12 pairs to 3) and likewise add none. Tier-1-only mode is unchanged and `clauseScoped` is untouched. What is given up is any verdict at all on a compound unit under entailment: a subject/polarity flip inside one is no longer flagged, and neither is a genuinely off-topic citation of one. Both were previously flagged only by a signal that flagged correct citations at the same rate — the judge refuses every conjunction, and cosine against a conjunction is diluted for the correct record too — which is the sweep's 8-of-8-on-correct-records finding. A clause-scoped fragment's cumulative prefix still names earlier items while being attributed to one citation, and that residual is not closed here. Nor is a second one the selection creates: where a record is cited by both a compound unit and a single-claim sentence, the cosine argmax decides which it is asked about, so the citation can be withheld although a claim unit that is its own claim was a candidate — preferring the non-compound candidate would break the documented parity between `selectClaim`'s choice and `verdictTier1`'s, which needs its own evidence. Nor is [#284](https://github.com/openmrs/openmrs-module-chartsearchai/issues/284), whose measured case is a one-marker answer plus a citation present only in the structured array — a claim unit citing one record, so untouched by this rule; it is closed separately by [Decision 41](#decision-41-a-composite-claims-negative-says-nothing-about-the-citation), and the two are disjoint because a compound unit never reaches the judge and so carries no negative for that rule to withhold.

**A chart citation's `false` is withheld too where its claim is COMPOSITE (August 2026, [#284](https://github.com/openmrs/openmrs-module-chartsearchai/issues/284))** — see [Decision 41](#decision-41-a-composite-claims-negative-says-nothing-about-the-citation), which qualifies "Tier-2 is authoritative" above for the one case where the judge's negative is guaranteed by the pairing rather than earned by the record.

### Trade-offs

- **+** Catches the dangerous "real record, wrong claim" citation that index validation structurally cannot — the cosine tier for free, the entailment tier for the subtle subject/negation flips that matter clinically.
- **+** Advisory and additive: default-off, never alters the answer, degrades to "unchecked" rather than failing.
- **−** Tier-2 adds one batched LLM round-trip; on CPU-only servers that is seconds of latency, mitigated but not eliminated by async + lazy Tier-1.
- **−** The cosine floor is a per-model tuning knob, not a constant — a model swap without re-tuning `minCosine` silently mis-grounds.
- **−** Clause-scoped grounding is correct in principle but coupled to per-pair Tier-2 independence, so it ships off by default behind a measure-first gate.
- **−** The first "+" above does not extend to a compound claim unit: neither tier is asked a question that is the citation's own, so nothing is published for it and a flip inside one goes uncaught. That is the #302 trade — an honest silence in place of a refusal published on every citation of the shape, right or wrong.

## Decision 26: Chart-write detection via core service events

**Status: Accepted** (June 2026) — implemented. Supersedes the per-service AOP-advice mechanism (the `*IndexingAdvice` classes) used for chart-write detection, including the "Chart-write invalidation" path in the "Answer caching" note above.

### Context

chartsearchai reacts to a chart write in two ways: it invalidates the patient's cached answers (so an edit never serves a stale cached answer — see "Answer caching"), and, when the prewarm corpus is enabled, it re-pins that patient's KV cache. Historically both rode three `AfterReturningAdvice` classes (`ObsIndexingAdvice`, `EncounterIndexingAdvice`, `PatientDataIndexingAdvice`) registered as AOP `<advice>` on eight core services. Querystore, meanwhile, already keeps its retrieval index fresh by subscribing to core's #6084 `*ServiceEvent`s — so the module ecosystem had two different write-detection mechanisms.

### Decision

Replace the three advice classes and their eight `<advice>` blocks with a single Spring `@EventListener` bean, `ChartSearchEventListener`, that consumes core's `Save/Void/Unvoid/PurgeServiceEvent`s — the same mechanism querystore uses. Each event carries the written entity; the listener resolves the affected patient(s) and dispatches to the unchanged `IndexingHelper.onChartWrite`, which fans out to answer-cache invalidation and (when enabled) the prewarm re-pin.

### Why move off AOP

- **No method-name matching.** The advice matched write methods by name (`saveObs`, `voidObs`, `discontinueOrder`, …) per service — a list that silently rots when core renames or adds a write method. The events are entity-typed, so a new write path is caught automatically.
- **Writes only.** The `afterReturning` advice fired on *every* method of the advised services (including hot getters) and filtered by name; the events fire only on actual save/void/unvoid/purge, so there is no read traffic to filter.
- **One mechanism.** Aligns chartsearchai with querystore instead of maintaining a parallel detection path.
- **Broader coverage.** Allergy writes go through `AllergyService`, which the old advice (scoped to `PatientService` et al.) only caught via `PatientService`'s delegating methods; the global #6084 advice intercepts them directly.

No platform-floor change: core 2.9 + querystore are already required, so the event classes are already on the classpath.

### Behaviour parity

Clinical types (obs, encounter, condition, diagnosis, allergy, order, patient program, medication dispense) are handled on any operation. A bare `Patient` is handled on **save only** (demographics) — void/unvoid/purge of a patient stays out of scope, left to the cache TTL backstop, exactly as before. Patient merge emits no event, but `mergePatients` ends by saving a `PersonMergeLog`, which arrives as `SaveServiceEvent<PersonMergeLog>` and dispatches both winner and loser.

### Transaction semantics

The handlers run **synchronously inside the originating transaction** (session open, so `getPatient()` navigations resolve). Unlike querystore — which must defer its heavy embed+index after commit to avoid indexing rolled-back data — chartsearchai's work here is cheap and idempotent: an in-memory answer-cache eviction, and *scheduling* a debounced re-pin whose actual prefill already runs later on a daemon thread. A rolled-back write therefore costs at most a harmless cache miss or one redundant, debounced re-pin, so no after-commit dispatch is needed.

### What this does and does not fix

This buys decoupling, robustness against method-name drift, and consistency with querystore. It does **not** widen coverage to non-service writes: core's #6084 advice is itself service-layer AOP, so direct DAO writes and SQL-dump loads still publish no event — the same gap the advice had. The finite cache TTL and the manual prewarm re-sweep / querystore reindex remain the backstops for those paths.

### Trade-offs

- **+** Entity-typed detection that cannot drift with core method renames; fires only on writes; one mechanism shared with querystore; catches direct `AllergyService` writes the old advice missed.
- **+** Net code reduction — three advice classes + 34 tests + eight `<advice>` blocks replaced by one bean + seven parity tests.
- **−** Synchronous in-transaction handlers run on the clinical thread; kept safe by the cheap-gate-first / idempotent / best-effort-swallow design, but a future heavier reaction would need after-commit deferral like querystore's.
- **−** No coverage of non-service writes (unchanged from AOP); the TTL must stay finite.
- **−** Verified by parity unit tests + live end-to-end checks; no in-memory CI test asserts the core events fire (querystore proves that generic wiring on the same core).

## Decision 27: Drug-safety parity follow-through — weight-aware dosing, curated cross-reactivity groups, prose warnings

**Status: Accepted** (July 2026) — implemented. Extends [Decision 23](#decision-23-drug-reference-injection--post-answer-drug-safety-validation) and [Decision 24](#decision-24-drug-reference-as-a-pluggable-consumer-of-authoritative-datasets).

### Context

The drug-reference feature adapted its knowledge-base data from the
[anichiti/openmrs_chatbot](https://github.com/anichiti/openmrs_chatbot) project (see
[drug-knowledge-base-comparison.md](drug-knowledge-base-comparison.md)). A functional
gap analysis of that origin identified exactly three capabilities that both survive
chartsearchai's constraints (local-only, knowledge-as-data-not-code, warnings-never-blocks,
single pipeline) and add real safety value — everything else there was either already
ported in stronger form, display-only/broken at the source, or constraint-violating
(dose *recommendation*, live RxNorm/openFDA/RxClass APIs, excipient matching).

1. **Weight-aware per-dose overdose validation.** The validator compared the answer's
   daily total against the absolute `maxDailyDoseMg` only; the dataset's `mgPerKgMin/Max`
   values were rendered for the LLM but never enforced. For a small patient, a
   per-administration dose can be far above the per-kg ceiling while the daily total stays
   under the absolute one — and bands that publish mg/kg dosing with *no* daily maximum
   (ibuprofen 0–1y) previously supported no overdose check at all.
2. **Cross-branch cross-reactivity.** Decision 24 documented the boundary honestly: ATC's
   tree cannot link aspirin (`N02BA01`, salicylates) to an ibuprofen (`M01AE01`) allergy —
   "that linkage needs curated data, not classification."
3. **Prose warnings.** The origin's `major_warnings` (Reye-syndrome-type cautions) were
   deliberately dropped in Decision 23 because they carry no matchable token; that also
   meant the LLM had no citable source for them.

### Decision

Three additive, data-driven extensions:

1. **Weight-aware per-dose check** (`DrugSafetyValidator`). `PatientClinicalContext` gains
   the patient's most recent weight (kg), read by the builder from the concept configured in
   `chartsearchai.drugSafety.weightConceptUuid` (default: the reference CIEL "Weight (kg)"
   concept 5089; the `none` sentinel turns the arm off — a *blanked* GP reads back as null,
   indistinguishable from absent via the privilege-free reader, so blank falls back to the
   default like every other GP) and only when newer than
   `chartsearchai.drugSafety.weightMaxAgeDays` (default 90 — a stale, typically lower,
   pediatric weight would over-report mg/kg, the false-positive direction this feature never
   takes). When a weight is known, a per-administration dose above the age band's
   `mgPerKgMax` × weight is flagged. Both overdose arms consume the **same** clause-scoped,
   alias-anchored, limit-cue-guarded attribution walk (refactored into one shared
   `attributedDoses` pass), so a dose counts for either arm under identical conditions.
   **One warning per drug: the published daily ceiling wins when both arms trip.**
2. **Curated cross-reactivity groups** (`cross-reactivity-groups.json`, GP
   `chartsearchai.drugReference.crossReactivityGroupsFilePath`, bundled fallback). A group
   is a named drug family expressed as ATC code *prefixes* (any level, so data chooses the
   breadth); membership = any of a drug's ATC codes starts with any prefix. Loaded
   **independently of the entry source** — deliberately not a `DrugReferenceSource` — so the
   rule-less `atc` format gains cross-branch family reasoning from the same file. The
   validator's class-based contraindication and interaction checks fall back to a shared
   group when no ATC subgroup **that classifies the substances** is shared
   (most-specific-match-wins; a subgroup+group double-match warns once). Since
   [Decision 33](#decision-33-a-residual-atc-subgroup-is-not-a-relationship) a shared
   subgroup can be discarded, so a pair that shares one may still be reported through the
   group. The injector's order-relevance scoping
   accepts group-related orders. The bundled seed is minimal — one NSAID group spanning
   `M01AE` + `N02BA`, exactly the branches Decision 24 named — expand per deployment.
   The Decision 24 boundary tests remain true as written: they assert ATC **alone** does
   not cross branches, and the test seam pins a groups-free dataset; the new tests assert
   both sides (without the data: unlinked; with it: linked).
3. **Prose `warnings` on entries.** An optional free-text list rendered verbatim into the
   injected, citable reference record (between dosing and contraindications). Display-only
   by design: no matchable token, so the deterministic validator never fires on it —
   enforceable facts stay in the structured rule fields. This restores the origin's
   Reye-syndrome-type content as something the LLM can ground and cite, without weakening
   the validator's no-false-positive stance.

### What remains deliberately unported

- **Dose recommendation** (calculate a dose from weight+age): turns a validator into
  prescribing decision support — a different liability class and the opposite of the
  warnings-never-blocks posture. We validate the dose the answer states; we do not propose one.
- **Live RxNorm / openFDA / RxClass APIs**: fails the local-only constraint; the curated
  groups file is the offline, data-driven equivalent of the RxClass cross-reactivity lookup.
- **Excipient / food-allergen matching**: needs per-product inactive-ingredient data that
  neither OpenMRS nor any free local dataset carries, plus an allergen→excipient table that
  would put clinical knowledge in code.

### Trade-offs

- **+** The per-kg arm catches small-patient overdoses the absolute ceiling cannot, and gives
  bands without a published daily maximum their first overdose check; weight enters through
  the same guarded, best-effort builder as every other patient read (missing/stale/misconfigured
  weight degrades to the old behavior, never an error). The fetch-all-then-scan weight read is a
  measured decision: ~2 ms/query at 500 obs on a real MariaDB (threshold 50 ms), so the
  `mostRecentN=1` DB-side variant was rejected as unmeasurable win for real API risk.
- **+** Cross-branch cross-reactivity with zero per-drug curation — one group line covers a
  family, for both source formats, and the aspirin/ibuprofen case ships working out of the box.
- **+** All three are data: operators extend the JSON files, no rebuild.
- **−** Weight is assumed to be recorded in kilograms on the configured concept; a
  pounds-valued concept would need a kg concept (or the `none` sentinel in the GP to
  disable the arm — blanking it falls back to the default, like every GP).
- **−** The bundled NSAID group is a deliberate minimal seed (two branches); real deployments
  own the clinical breadth of their families, consistent with the no-medical-knowledge-in-code rule.
- **−** Prose warnings are LLM-visible but not validator-enforced; a deployment wanting
  enforcement must express the fact as a structured rule instead.

## Decision 28: Query-scoped slice charts (chartMode=queryScoped)

**Status: Accepted** (July 2026) — implemented behind `chartsearchai.chartMode`, which now defaults to `queryScoped` (it shipped defaulting to `fullChart`; see the update below). Complements — and in scoped mode disengages — the warmup/prewarm/KV-persistence machinery of Decisions 12 and 26.

**Update (2026-07, default flipped to `queryScoped`).** After validation, `queryScoped` became the default (`config.xml` defaultValue + the `CHART_MODE_DEFAULT` constant both readers use). Evidence: a 22-patient drift-metric A/B — scoped beat fullChart on meanF1 (0.748 vs 0.668), abstention (0.86 vs 0.74), and off-topic drift (181 vs 477: the focused slice keeps the small model from citing a whole chart's worth of noise) — plus a CPU latency check where scoped's cold first answer was ~3× faster (no full-chart prefill). Consequences: (1) the full-chart prefill machinery (warmup, prewarm bootstrap, per-patient KV persistence, progressive-reasoning preview) is now dormant by default — it re-engages only when an operator sets `chartMode=fullChart`; (2) the fail-safe direction reverses — an *absent or unreadable* `chartMode` GP now resolves to `queryScoped`, though a GP set to any non-`queryScoped` value (including a typo) still resolves to fullChart, so a mistyped value fails toward the whole chart. `fullChart` remains supported for many-questions-per-patient sessions where its warm-cache reuse and completeness-over-focus are preferred.

### Context

The fullChart architecture amortizes one large prefill (whole chart, ~13–15k tokens) across queries via llama-server's KV prefix cache, warmup-on-open, and the pinned prewarm corpus. Its weak spot is the *not-yet-warmed* patient: on a GPU-less host the first query pays the full prefill (~70s measured for ~150-record charts; minutes for large ones), and warmth requires per-patient state (~110–280 MB of persisted KV each) that cannot scale to every patient of a real facility — cold patients always exist. A directive constraint for this work: the answer path must not depend on prefill amortization at all.

### Decision

Add a second chart-assembly mode. `QueryScopeRouter` matches the question against conservative word-boundary cue sets — MEDICATIONS, ALLERGIES, PROGRAMS, CONDITIONS, VISITS, ORDERS get their record types included *complete* (an enumeration answer cannot omit what isn't retrieved); a question matching several cue sets ("any drug allergies?") carries the *union* of the matched intents' types, because first-match routing silently dropped the runner-up's completeness on exactly the type being enumerated; everything cue-free is TOPICAL. Every slice also carries the querystore similarity top-K (semantic catch-all; lab abbreviations expanded first, e.g. BMP → basic metabolic panel), the demographics record, obs-group *family completion* (a panel parent or member in the slice pulls the whole panel — member texts carry no panel name, so similarity alone misses the values), and — only for temporal questions ("most recent…", "lately…") — a recency anchor of the chart's newest records. Slices render in chart order (most recent first) with a date on every record: run-length date compression is a full-chart token optimization, and at slice scale it hid exactly the dates temporal questions need. Records whose querystore date is administrative render undated in both modes — `patient` and `allergy` (dateCreated), the two types measured answering "when was the last visit?" with record-keeping time. Known remainder: querystore also stamps dateCreated on `condition`/`diagnosis` (unconditionally) and on `program`/`medication_dispense` (when their clinical date is null); those still render it, so a "when was X diagnosed?" answer can quote record-keeping time. They stay dated for now because blanket-undating an unmeasured type can cost more than it fixes (an undated condition list loses chronology; condition's clinical onset_date sits in doc metadata, unrendered) — extending the set, or rendering onset instead, is follow-up work behind the two gates below. In scoped mode, warmup, the prewarm bootstrap sweep, per-patient KV persistence, and the progressive-reasoning preview all disengage (the same `shouldRunWarmup` decision point Decision 12's machinery already consulted).

### Evidence (rc.2 standalone, E4B, July 2026 gates)

- CPU cold first answer: fullChart 73–74s vs scoped 12–27s; first output ~68s vs 7–12s. Scoped cold ≡ scoped warm — no warmth states exist.
- 40-cell adjudicated quality (same build): meanF1 0.733 vs 0.800, abstention 0.89 vs 1.00, off-topic citations 51 vs 2; per-cell 9 better / 4 worse / 27 ties.
- Temporal DB-truth probe: scoped 15/15 stable; fullChart 14/15 (a stale-value failure that survives rebuilds).
- Two gates are mandatory for any slice-composition change — the 40-cell scope eval and the temporal probe pull in opposite directions (an unconditional recency anchor fixed temporal cells and simultaneously drove an absent-topic cell to 39 drift citations).
- Gate re-run after the router union fix (fdf1c9c, 2026-07-17, same rc.2 standalone/E4B): 40-cell aggregate unchanged — meanF1 0.800, abstention 1.00, off-topic 2 (none of the eight questions is multi-cue, so routing on them is identical; confirmed from the per-cell `intent=` labels). The temporal probe is now committed (`eval/drift-metric/temporal_probe_rc2.py`) and extended with previously-uncovered "when was the last visit?" cells: 14/15 — 10/10 value-recency plus 2 correct abstentions, the one miss being the type-conflation residual already listed under trade-offs (the newest encounter was in-slice and the model quoted an older one; "last visit" accepts the newest visit-table OR encounter date, since the VISITS scope deliberately carries both). New drug-allergies topic (the union's coverage cell, 45-cell suite): both present cells F1 1.00 — typed-complete imipenem condition/diagnosis rows and DRUG-typed allergy rows; one absent-cell drift where a latex-allergy condition was presented as a drug allergy (model-side negative-question failure; the records reach the slice via similarity identically pre/post union). 45-cell aggregate: meanF1 0.817, abstention 0.95, off-topic 4.

### Trade-offs

- **+** Cold latency collapses ~5–7× on CPU with zero pre-warming, no per-patient disk, no sweeps; quality gates met or improved on every axis.
- **+** Typed completeness fixes fullChart's omit-for-brevity failures on enumeration questions; the small prompt also decodes faster.
- **−** Repeat queries lose the ~2.5s ultra-warm full-chart path (each question pays its own small prefill; on CPU scoped is still faster than fullChart's warm decode).
- **−** Deep-history recall depends on the router + similarity + family completion; the residual known weakness is type-conflation on "last visit"-style questions (the model quotes the newest dated record — e.g. a billing event — instead of a visit record; billing dates are real event dates, so they stay rendered).
- **−** The intent router is an English keyword table; unmatched phrasings degrade to TOPICAL (similarity-only), never to a wrong typed scope, and multi-cue questions union every matched intent's types rather than betting on one.
- **−** Flipping modes leaves the previous mode's persisted KV `.bin` files on disk unused until manually cleaned. The pinned prewarm corpus does not survive a scoped interlude unattended (edits during the interlude are not re-pinned; the sweep and per-edit refresh refuse to run in scoped mode rather than fake success) — after flipping back, re-run the `/prewarm` sweep. The admin-date byte change likewise invalidates pre-existing KV entries once: each patient's first touch re-prefills and re-persists **unpinned** (the purge also removes the old entry's `.pin`), so fullChart deployments with a pinned corpus should re-run the sweep after upgrading across this change.
- **Correctness note (KV-stamp preservation).** The per-request KV decision reads the built chart's `PatientChart#isQueryScoped()` stamp, never a re-read of the `chartMode` GP, so a mode-flip or transient GP-read mid-request cannot mis-scope the persist. The one hazard was the drug-reference injector ([Decision 23](#decision-23-drug-reference-injection--post-answer-drug-safety-validation)): `injectRecords` rebuilds the `PatientChart` to append reference records, and the fresh instance defaulted the stamp to `false` — so on the medications path (the flagship scoped intent *and* the likeliest drug-ref match) an injected scoped slice could be persisted under the patient's KV scope and evict their pinned full-chart entry. `injectRecords` now carries the stamp across the reconstruction (regression-tested with the real injector). It is the only `PatientChart` reconstructor outside the serializer, whose output is always (re-)stamped by `buildScoped`.

### Follow-up: independent re-measurement and tuning levers ruled out (2026-07-17)

The gates were re-run on the current build; the fullChart baseline reproduced **exactly** (meanF1 0.733, abstention 0.89, off-topic 51), and scoped reproduced **exactly** (0.800 / 1.00 / 2), confirming the harness and the reported numbers. The four scoped cells that score below fullChart were then diagnosed from the actual cited records: three are **recall losses where scoped keeps perfect precision** (the missed records are in the slice but uncited — the model answers more conservatively, which is the same property that yields the abstention and off-topic-drift wins), and one is an over-citation of a borderline-relevant condition. They pull in **opposite directions**, so no single lever fixes them as a set. Three levers were measured and all net-regress:

- **`querystore.topK` (the one no-code dial).** Curve on the 40-cell gate: topK=20 → meanF1 0.865 / abstention 0.94 / off-topic **25**; topK=25 → 0.813 / 1.00 / 16; **topK=30 (shipped) → 0.800 / 1.00 / 2**; topK=50 → 0.771 / 1.00 / 7. Lowering topK raises present-cell F1 but **reintroduces off-topic drift** — at topK=20 an *absent* cardiac cell dumped 21 irrelevant citations instead of abstaining. **topK=30 uniquely minimized drift with perfect abstention** on that gate, so it shipped as a safety optimum rather than a max-F1 point. **Superseded:** a later, wider sweep (the 22-patient drift-metric gold plus 36 patients on demo data) lowered the default to **12**, which holds F1 at the plateau while *improving* abstention and roughly halving drift, and cuts CPU time-to-first-token. `ChartSearchAiConstants.DEFAULT_QUERYSTORE_TOP_K` is the current default and records that measurement; do not read 30 out of this section as the shipped value.
- **Subtype-aware routing.** Routing domain-qualified conditions questions ("mental health or psychiatric *conditions*?") to TOPICAL instead of the full CONDITIONS dump fixed the *under-cited* mental cell (0.67 → 0.92) but **broke the *over-cited* one** (0.67 → 0.50) and raised off-topic 2 → 9 — net worse; reverted. Both cells are the identical question; they differ only by patient chart shape, which a text router cannot see.
- **Deeper retrieval** (topK=50 above) adds noise without net benefit.

Conclusion: the four cell-level regressions are a genuine Pareto cost of the precision/abstention rebalance that drives the aggregate win; no change tested *at that time* improved the net. Shipped at `chartMode=queryScoped`. The `querystore.topK` half of that conclusion was later overturned — see the note above.

## Decision 29: Module-extensible query-scope routing (QueryScopeContributor SPI)

**Status: Accepted** (July 2026) — implemented. Extends [Decision 28](#decision-28-query-scoped-slice-charts-chartmodequeryscoped).

### Context

Decision 28's `QueryScopeRouter` maps a question to a *complete-by-construction* typed scope for six built-in domains (medications, allergies, programs, conditions, visits, orders). Any other domain — billing, appointments, and whatever a given deployment's modules add — falls through to TOPICAL (similarity-only), with no completeness guarantee, and the router's resource-type strings are baked into chartsearchai. That means a new domain cannot get first-class scoped routing without editing this module, and "appointments" is actively *misrouted* today (it is a VISITS cue mapping to `{visit, encounter}`, not to a distinct appointment resourceType).

### Decision

Add a Spring SPI, `QueryScopeContributor` (in `chartsearchai-api`): a module registers a bean that, for a question it recognizes, returns the querystore resourceTypes to include complete. `QueryStoreChartBuilder` resolves the registered contributor beans **live on each call** via `Context.getRegisteredComponents(QueryScopeContributor.class)` — the same lazy-resolution posture this class already uses for `QueryStoreService`, rather than a cached `@Autowired` snapshot that would silently miss a contributor module started after the builder singleton was wired — and, in `buildScoped`, **unions** each contributor's claim on top of the built-in typed scope. The union is deliberately *additive*: with zero contributors the slice is byte-identical to Decision 28's gated behaviour, and a contributor can only add its own domain's records — it can never perturb another domain's routing, so none of Decision 28's measured wins are put at risk. Each contributor call is wrapped in try/catch — a contributor that throws (or returns null) is skipped with a WARN and forfeits its claim, never breaking the answer path (the same fail-safe posture as querystore resolution and similarity).

### Trade-offs

- **+** New domains get complete-by-construction scoping without modifying chartsearchai; no hard dependency (contributors declare chartsearchai as `aware_of_module` and compile against `chartsearchai-api`); zero contributors = unchanged behaviour.
- **+** Fixes the appointment misroute in principle: an appointment module registers the real resourceType instead of the wrong `{visit, encounter}` mapping.
- **−** The hook is only half the contract. querystore owns retrieval/indexing (project rule), so a claimed resourceType contributes nothing unless querystore actually indexes that domain — first-class support for a new domain needs *both* a querystore indexing extension and a contributor here.
- **−** A contributor is a slice-composition change, which this project gates on BOTH the scope eval and the temporal probe (they pull in opposite directions — see Decision 28). The SPI cannot enforce that a contributor was gated; the interface javadoc states the expectation, and an unvalidated contributor can silently regress its own domain's answer quality. Union also means a careless, over-broad contributor enlarges the slice for its questions (the same over-cite risk Decision 28 measured) — hence the contract's "match conservatively; prefer to under-claim."
- **−** The answer cache key (Decision 28) folds in `chartMode` and the question but not the registered contributor set. Contributors are resolved live per query, so a *newly started* contributor module immediately affects fresh answers; but answers already cached for its domain's questions are served until the cache TTL expires. Adding or removing a contributor module at runtime should therefore be followed by a cache flush (or accepted as bounded by the TTL) — keying the cache on the contributor set would add a `getRegisteredComponents` call to every cache-key computation for a rare, lifecycle-only change.

## Decision 30: One chip per substance — the contraindication ledger and its collapse key

**Status: Accepted** (August 2026) — implemented. Extends [Decision 24](#decision-24-drug-reference-as-a-pluggable-consumer-of-authoritative-datasets) ([#145](https://github.com/openmrs/openmrs-module-chartsearchai/issues/145) / [#160](https://github.com/openmrs/openmrs-module-chartsearchai/pull/160)).

### Context

Both contraindication arms were keyed on a reference **entry**, and DDInter files one substance as several route/formulation rows. One clinician-facing name resolves all of them, so one clinical fact produced one chip per row. The duplication was the visible symptom; the real defect was that only the row the allergen resolved to by *identity* said the true thing. Its siblings fell through to the class comparison and reported the substance as cross-reactive with the patient's allergy **to itself**. And since [#110](https://github.com/openmrs/openmrs-module-chartsearchai/issues/110) every chip is also injected as a citable record, so each duplicate spent prompt budget too.

### Decision

`DrugSafetyValidator` keeps a validate-scoped ledger that both contraindication arms and both of their call sites feed. It keys on `(subject substance, recorded finding)`, keeps the most specific relationship, and writes the survivor back into the position the group's first candidate held, so no client sees the chip order reshuffle.

### Why a ledger and not a filter over the finished list

Decisively: the sibling's chip is **wrong**, not merely duplicated, so the collapse must *choose which relationship survives* — and by the time only rendered text is left, the reasons that justify that choice are gone. The chips also differ in text, each naming its own route, so no text-level dedup would have caught them.

### Why the collapse key is substance name **plus** display-name stem

`rxnorm_name` equality alone was rejected: it is not populated for every row, and where it is, route variants of one substance do not reliably share it — so the key would silently fail to collapse exactly the rows that motivated the work, while also risking collapsing two genuinely different substances that share an ingredient name. Requiring agreement on both the substance name and the display-name stem makes the key hold on the rows that exist rather than on the rows the schema promises.

### Refinement: which findings are one finding ([#146](https://github.com/openmrs/openmrs-module-chartsearchai/issues/146))

The *recorded finding* side of the key is whatever the arm actually compared — the allergen's substance for the allergy arm, the rule's `(type, token)` for the curated arm. Those stay two key spaces, because a curated token may name a **class** (`nsaid`, `aminoglycoside`) rather than a drug and resolving tokens to entries wholesale would collapse a class-level rule into an identity chip, a different and worse defect. One rule shape crosses: an **allergy** rule whose token is one of the subject entry's own names reports the allergy arm's fact, so it is keyed on the substance and the two chips become one. On the shipped default `sourceFormat=json` that shape is 3 of the file's 4 entries and every one of them double-reported a single allergy, with no non-default configuration needed to see it.

Which of the two wordings survives is decided by **content**, not by a fixed arm-yields-to-arm precedence: a curated rule carrying an operator-authored note states the identity fact in the deployment's own words and outranks it, while one with no note renders its own token back and is outranked by every relationship (it is still raised, because the two arms fire on different evidence and one can match where the other resolves nothing). That is the rule [#88](https://github.com/openmrs/openmrs-module-chartsearchai/issues/88) settled for the interaction arms, applied to the contraindication ones: "arm X yields to arm Y" is the wrong dedup whenever the yielding arm can be the one carrying the content.

A note is necessary and **not sufficient** ([#223](https://github.com/openmrs/openmrs-module-chartsearchai/issues/223)). The fold's premise is that such a rule reports the allergy arm's fact, and what files it there is `PatientClinicalContext.hasAllergyToken` — deliberately bare containment, because a curated token may be a class or a fragment of a clinician's free text. A self-named token is the one shape that is neither: it *is* one of the entry's own drug names, and drug names nest, so an allergen recorded as `Tiotropium` gave an `Opium` rule the identity chip's rank and replaced the sentence a separately recorded opium allergy had raised.

What containment does not say is **which** allergy record the token reached, so `contraindicationRank` asks for the witnesses (`PatientClinicalContext.allergensMatching`, sharing the boolean's own primitives so the two cannot drift) and puts each to the entry through `DrugReference.matchesDrugName`, the accessor for a clinician-entered drug name. Per witness **and** against the entry, both halves measured by mutation: asking it of the *token* demotes a rule it must not — an entry publishing `thyroxine` among its own names and ruling on that name, for a patient whose allergy is recorded as `Levothyroxine`, where the note is the only thing in the response saying what the reaction was — and asking it of the *whole allergy list* rather than of the matched record is #223 again with a longer proof. A rule no recorded allergy names still chips where nothing else reports the drug — this is a rank, not a gate, for the reason above — it simply cannot outrank a chip the allergen arm corroborated.

Tightening the **match** instead was measured and declined: over the shipped KB's 5169 published names as the allergen corpus, of the ten rules the bundled curated file publishes it loses 5 real allergen names, every one on the class token `penicillin` (`benzylpenicillin`, `phenoxymethylpenicillin` …) and none on a self-named one — trading a false positive for a false **negative** in a safety net, which is the wrong direction. That corpus is reference names, not the localized dictionary and not free text; it bounds the class-token loss, which is what the decision turned on. The match staying as it is left the injected `drug_reference` record's "Recorded for this patient:" half following bare containment, deliberately out of scope here and closed separately by [Decision 42](#decision-42-a-recorded-clause-needs-corroboration-not-just-a-match) ([#269](https://github.com/openmrs/openmrs-module-chartsearchai/issues/269)).

The test for "does this token name this entry" is `DrugReference.isNamed` — name identity between two **reference** strings — deliberately not `DrugReferenceService.findImpliedSubstances`, which reads a **recorded** name and widens on purpose. `DrugReferenceInjector`'s `Contraindicated with:` clause keys on the chip's collapse unit by contract ([#190](https://github.com/openmrs/openmrs-module-chartsearchai/issues/190) item 1), so it moved with it.

## Decision 31: Name the class that explains the relationship, not the first one shared

**Status: Accepted** (August 2026) — implemented. Refines [Decision 24](#decision-24-drug-reference-as-a-pluggable-consumer-of-authoritative-datasets)'s level-4 class matching ([#161](https://github.com/openmrs/openmrs-module-chartsearchai/issues/161) / [#166](https://github.com/openmrs/openmrs-module-chartsearchai/pull/166)).

### Context

`sharedClass` returned the first shared ATC level-4 subgroup in the allergen's own code array. A corticosteroid carries one code per route it is marketed in, so a systemic cross-reactivity concern was justified by a topical class — methylprednisolone against a dexamethasone allergy read as anti-acne preparations. The finding was right and the reason it gave was not, which is precisely what a clinician checks before deciding whether to trust the feature.

This was a systematic bias, not an arbitrary list position: every ATC array in the shipped KB is in ascending code order, and ATC's alphabet front-loads the locally applied groups, so "first" reliably meant "most topical".

### Decision

Prefer the shared subgroup that classifies the **substance** over one that classifies a **locally applied formulation**, falling back to the locally applied one when that is all the pair shares. Candidates are examined in code order rather than array order, so the answer is a function of the two code sets rather than of where a dataset happened to write a code.

The route/site knowledge is two prefix lists on `DrugReference`: the locally applied groups, each justified by the route or site in the ATC group's own published name, minus the groups nested inside those that ATC itself names *"for systemic use"*. That exception is not hypothetical — without it, a pair sharing both a topical and a systemic subgroup is reported under the topical one. ([Decision 33](#decision-33-a-residual-atc-subgroup-is-not-a-relationship) adds a third list, derived from the first.)

### Why not the two alternatives

- **Match the route of the active order.** The route is not available at this seam and could only ever be available at one of the two call sites: the arm also runs for a drug the *question* names, which has no route at all. Nothing carries the route that far.
- **Fall back to ATC level 3.** Widening the class breadth to make the label read better trades a wrong reason for a vaguer one and reopens the false-positive cost [Decision 24](#decision-24-drug-reference-as-a-pluggable-consumer-of-authoritative-datasets) settled. The grain stays level 4; only the *choice among* shared level-4 codes changed. ([Decision 33](#decision-33-a-residual-atc-subgroup-is-not-a-relationship) later added the possibility of choosing **none** of them; the matching grain is still level 4.)

## Decision 32: Observable drug-reference load status

**Status: Accepted** (August 2026) — implemented ([#149](https://github.com/openmrs/openmrs-module-chartsearchai/issues/149) / [#154](https://github.com/openmrs/openmrs-module-chartsearchai/pull/154)).

### Context

A `sourceFormat` / `dataFilePath` mismatch loads **zero** entries and was reported at INFO exactly like a successful load: a count of 0 printed as cheerfully as 2283. The module starts clean — no startup error, no WARN — and the whole drug-safety layer is inert, every safety question answering as though there were nothing to find. Neither existing loud path covers it: they fire when the bundled dataset is missing or unparseable, not when a file is present, readable and simply the wrong shape for the configured parser.

This is not a hypothetical operator error. It silently produced a wrong result inside this project: a verification pass flipped `sourceFormat` while leaving `dataFilePath` on the other dataset, saw every probe return zero chips, and could not distinguish that from "the fix does not work".

### Decision

Two parts, and the second is why the first can be trusted.

1. **Loud on empty.** The service warns when a load it just performed produced no entries, naming both global properties, the parser in use and the file actually read. The rule lives in the service rather than in a source adapter, so one rule covers all three formats and cannot drift per source.
2. **Observable after the load.** `DrugReferenceLoad` retains the outcome — effective format, configured format, configured path, the origin actually read, entry count, the inert verdict, the loader's own validity findings (added with the load-time check, ADR Decision 36), and since issue #285 a per-arm capability verdict saying which safety arms the loaded entries can actually serve — captured at the instant the load populates the cache, so it can never describe a different dataset than the one the safety layer is using. Exposed by `GET /chartsearchai/drugreferencestatus` under core's `Get Global Properties`; no new privilege.

### Why the status must be observable *after* a lazy load

The load is lazy and cached for the life of the module, so "which dataset is in force?" cannot be answered from the log at all: the most recent `Loaded N …` line may pre-date the global properties as they now read, or belong to a process a failed restart left running. That is exactly how the pass above was fooled. Reading the endpoint therefore **performs** the load if it has not happened yet, so the answer is current by construction rather than a historical line that may be stale — the property that makes any source-flip verification, including that PR's own, trustworthy.

`origin` is reported separately from `configuredDataFilePath` because a configured path that cannot be read falls back to the bundled dataset and yields a perfectly plausible non-zero count. In that state "the count is non-zero, so my file loaded" is false, and the count alone cannot distinguish it. One `isInert()` verdict drives both the WARN and the reported status, so the two cannot disagree.

Reading the status when the feature is disabled deliberately does **not** trigger a load: polling a status endpoint must not be what starts a large parse, or manufactures the warning, on an install that does not use the feature.

> **A fourth decision in this area is pending, not yet merged.** [#173](https://github.com/openmrs/openmrs-module-chartsearchai/pull/173) carries the "one substance, one row" follow-through — the interaction subject, the injected reference record, and the self-pair parse guard. It is deliberately not written up here while it is open; add it when it lands.

## Decision 33: A residual ATC subgroup is not a relationship

**Status: Accepted** (August 2026) — implemented ([#167](https://github.com/openmrs/openmrs-module-chartsearchai/issues/167), [#171](https://github.com/openmrs/openmrs-module-chartsearchai/issues/171), [#155](https://github.com/openmrs/openmrs-module-chartsearchai/issues/155), [#174](https://github.com/openmrs/openmrs-module-chartsearchai/issues/174) site 1 / [#182](https://github.com/openmrs/openmrs-module-chartsearchai/pull/182)).

### Context

[Decision 24](#decision-24-drug-reference-as-a-pluggable-consumer-of-authoritative-datasets) reads a shared ATC level-4 subgroup as evidence of a pharmacological relationship, and [Decision 31](#decision-31-name-the-class-that-explains-the-relationship-not-the-first-one-shared) chooses among several shared subgroups. Both assume every subgroup classifies its members. Some do not: ATC files a residue in most of its groups, and a residue inside a group ATC defines by *where the product is applied* says only that — "both are put in the mouth". Live on the 3.7.1 standalone that produced *"Epinephrine is in the same ATC class (A01AD) as active order Acetylsalicylic acid (aspirin) — possible duplicate therapy"*, and *"…same ATC class (V03AB)…"* between potassium iodide and an acetylcysteine allergy.

The same assumption reads a code as a reliable route back to a **name**, and as identifying **one** thing: an order's concept can map to five ATC codes, so one co-medication produced one chip per shared subgroup, and a code the loaded dataset does not cover was printed raw (`as active order N02BA01`).

### Decision

Four sites, two rules.

1. **`DrugReference.isUnclassifyingAtcCode`** — a shared subgroup that classifies neither the substances nor a therapy is *skipped* in both of `sharedClass`'s tiers, so the method can answer "they share nothing that explains anything" and both arms fall through to the curated cross-reactivity groups. A residue **inherits** whatever its containing group asserts, so a blanket residual veto is wrong: `R06AX` sits under "ANTIHISTAMINES FOR SYSTEMIC USE" and two drugs sharing it really are both antihistamines. The list is the residues inside the [Decision 31](#decision-31-name-the-class-that-explains-the-relationship-not-the-first-one-shared) locally applied groups, plus `V03A` and `V07A`, plus `S02DC` — and, since [Decision 34](#decision-34-an-atc-subgroup-licenses-only-the-claim-its-own-name-asserts), the residues whose ancestry asserts nothing at any level. Decision 34 also makes the skip **per-arm**: the sentence opening this bullet, about both arms and both tiers, describes the duplicate-therapy bar; the cross-reactivity arm now has a higher one. Its derivation, its deliberate over-reach and its measured cost are recorded on the constant.
2. **One claim per co-medication** — `classRelationships` groups the active-order codes by the co-medication they identify and words one sentence per partner, choosing the class with the same `sharedClass` the allergy arm uses (since [Decision 34](#decision-34-an-atc-subgroup-licenses-only-the-claim-its-own-name-asserts), the same method with the arm as a parameter — one preference, two candidate sets). The partner is named by a ladder: the dataset's entry for the code (through `DrugReference.canonicalRow`, so one substance is one name wherever it appears), else the ORDER's own display name, else the code.

### Why skip rather than demote

A demotion needs a tier left to fall to. Potassium iodide and acetylcysteine share `S01XA` *and* `V03AB` — one locally applied, one not — so every tier a demotion could reach is occupied by another bucket that means nothing either. Reverting the skip to a tier-1-only demotion fails both of `ResidualAtcClassClaimTest`'s arms, the second of them by reporting `S01XA` instead.

### What it costs, and why that is accepted

Of the 7783 shipped-KB **row** pairs that share a level-4 subgroup — the row and substance bases are defined at `DrugSafetyValidator.sharedClass`, and the 5550 below is the same relation counted on the other one ([#243](https://github.com/openmrs/openmrs-module-chartsearchai/issues/243)) — 486 lose the class claim and 54 keep one under a subgroup that does classify (measured for the 30 groups the list held at this decision; [Decision 34](#decision-34-an-atc-subgroup-licenses-only-the-claim-its-own-name-asserts) took it to 36 and added a second predicate, so re-measure before quoting these for today's code). 116 of the 486 name a subgroup whose own published name states a therapy or an indication — `D06AX` "Other antibiotics for topical use" is a residue *and* an assertion — so those claims were defensible. Separating the groups that mean something from the ones that do not is a per-group pharmacological judgement this module has no curated data to make, and per-child hand-tuning is exactly what left Decision 31's own list incomplete. The curated cross-reactivity groups remain the fall-through for every vetoed pair.

## Decision 34: An ATC subgroup licenses only the claim its own name asserts

**Status: Accepted** (August 2026) — implemented ([#183](https://github.com/openmrs/openmrs-module-chartsearchai/issues/183), [#184](https://github.com/openmrs/openmrs-module-chartsearchai/issues/184)).

### Context

[Decision 33](#decision-33-a-residual-atc-subgroup-is-not-a-relationship) skips a shared subgroup that classifies neither the substances nor a therapy, in **both** of `sharedClass`'s tiers and for both arms alike. It left two families standing and said so: broad therapeutic buckets that group heterogeneous chemistry by purpose or site (`S01AA` "Antibiotics", i.e. *ophthalmic* antibiotics), and a residue whose parent is itself ATC's residue (`A16AX` under "OTHER ALIMENTARY TRACT AND METABOLISM PRODUCTS"). Both were deferred because excluding them looked like a per-group pharmacological judgement rather than a reading of ATC's words.

The proposal put to this work was blunter: ATC classifies purpose and route rather than chemistry, so it should license **duplicate therapy only**, and cross-reactivity should come from the curated groups alone.

### The measurement, because the proposal was conditional on it

Driving the real `DrugSafetyValidator.validate` over the shipped 19 MB knowledge base, on each of the 5550 substance pairs it relates by a level-4 subgroup:

| | cross-reactivity claims | duplicate-therapy claims | curated-group claims |
|---|---|---|---|
| before | 5266 | 5271 | 0 |
| ATC licenses duplicate therapy only | 0 | 5271 | 24 |
| this decision | 3701 | 5114 | 0 |

Of the 5266 the blunt rule removes, **3701 rest on a subgroup that does name chemistry or a molecular target** — `J01CA` penicillins, `J01DD` cephalosporins, `J01GB` aminoglycosides, `N05BA` benzodiazepines, `C10AA` statins — and 1565 on purpose or on nothing. The single curated group the module ships replaces 24. It loses real signal at 2.4 times the rate it removes false claims, so it was rejected on its own stated test. The premise does not survive either: ATC level 4 is its *chemical* subgroup tier and mostly reads like one.

### Decision

A subgroup may justify a claim only as strong as what its published name asserts, and — extending Decision 33's rule from one level to every level — a residue asserts whatever the group containing it asserts.

1. **Names a chemical family, a derivative class, or a molecular target** — licenses both claim types. `J01CA` "Penicillins with extended spectrum", `R06AX` "Other antihistamines for systemic use" (a receptor), `C01BD` "Antiarrhythmics, class III" (a channel).
2. **Names only what its members are FOR** — an indication, an organism acted against, a therapeutic area, a diagnostic use — licenses **duplicate therapy and not cross-reactivity** (`DrugReference.isPurposeOnlyAtcCode`, 117 subgroups). Two ophthalmic antibiotics really are duplicate therapy for one another, and really do not thereby cross-react.
3. **Asserts nothing at any level** — a residue that contributes no term its ancestors' names lack, and whose ancestry is residue up to a bare **level-1 anatomical** main group — licenses **neither** (6 subgroups added to `isUnclassifyingAtcCode`). Level 1 and not level 2: ATC's level 2 is its *therapeutic* tier, so a residue inheriting "ANTINEOPLASTIC AGENTS" or "ANTIBACTERIALS FOR SYSTEMIC USE" does assert a purpose and belongs in rule 2. Drawing the line at level 2 first would have withdrawn 381 further duplicate-therapy claims that are genuine, including every pair of systemic antibacterials ATC files as "other".

`sharedClass` takes the arm as a parameter. The preference among surviving candidates — systemic over locally applied, sorted — stays in that one method, which is what #171 asked for. It does **not** follow that the arms always name the same class: their candidate *sets* now differ, so where a pair shares a purpose-named subgroup and a chemically named one, duplicate therapy is honestly about the first and cross-reactivity about the second. Measured at `sharedClass` over the 5550 pairs — a pair base, not the claim base the table above uses: 3693 answer on both arms, 4 differ (miconazole × clotrimazole names `A01AB` and `D01AC`). Naming them alike would make one of the two sentences false.

### Why per-arm rather than one wider veto

One list serving both arms cannot express this: extending `isUnclassifyingAtcCode` far enough to stop "both are ophthalmic antibiotics" being cross-reactivity also stops it being duplicate therapy, which it is. Measured, that variant removed 1228 duplicate-therapy claims against this decision's 157 — and the 157 are the family that asserts *nothing*, where withdrawing both claims is the correct answer.

### Why this is a reading and not the judgement Decision 33 declined

Decision 33 said separating meaningful groups from meaningless ones is "a per-group pharmacological judgement this module has no curated data to make". The data it lacked is ATC's own published group names, which the module does not carry and which were read for all 939 level-4 subgroups. The criterion is applied to the index, not to the reported cases, and it independently reproduces every decision Decision 33 states — `R06AX`, `J01GB` and `N02AX` keep; `A01AD`, `D11AX`, `S01XA` and `D06AX` are vetoed — differing on 19 of the 51 level-4 codes Decision 33's list covers, all of them that decision's own declared over-reach: `S02DC`, which it says a name test structurally cannot find; `V03A`/`V07A`'s 17 children, written at group level deliberately so a KB refresh cannot add one that escapes; and `C05BX` "Other sclerosing agents", whose own name does classify. Nothing moves — this change is additive, so all 19 stay vetoed by Decision 33's entries. Reproducing a previously settled list is the evidence that the criterion is a reading rather than a fit.

### What it costs, and why that is accepted

1565 cross-reactivity claims go, 586 of them for a pair DDInter also rates, so for those the interaction chip survives and only the class claim goes; 157 duplicate-therapy claims go with them, 14 rated. The residue family costs real relationships too — `G02CX` bremelanotide × flibanserin, `M09AX` onasemnogene × risdiplam, `A16AX` miglustat × eliglustat are genuine pairs — because no rule over ATC's words can tell them from eliglustat × givosiran, which is not one.

### Rejected

- **A blanket rule** (ATC → duplicate therapy only). Measured above; rejected 2.4 to 1.
- **A list of the subgroups the two issues reported.** `S01AA`, `A07AA`, `S02AA`, `A16AX`, `N07XX`, `V04CX`, `G02CX`, `R07AX`, `M09AX` — nine, against the criterion's 123, and the criterion also disagrees with #184 about where `V04CX` belongs. Hand-picking is what left Decision 31's own list incomplete in a way that reproduced the defect it was fixing.
- **Breaking the remaining alphabetical tie-break** inside the systemic tier ([#168](https://github.com/openmrs/openmrs-module-chartsearchai/issues/168)). This narrows it — 20 substance pairs leave more than one systemic candidate for the duplicate-therapy arm, 16 for the cross-reactivity arm — and cannot close it: `H02CA` "Anticorticosteroids" and `J02AB` "Imidazole derivatives" both name a class, so preferring either is the unmeasured preference Decision 31 refused to invent.

## Decision 35: A class code in the answer must come from a record the answer cites

**Status: Accepted** (August 2026) — implemented ([#142](https://github.com/openmrs/openmrs-module-chartsearchai/issues/142)).

### Context

[Decision 23](#decision-23-drug-reference-injection--post-answer-drug-safety-validation) injects the deterministic safety finding as a citable record precisely so the model reports a conclusion it will not re-derive, and [Decision 25](#decision-25-citation-grounding-tier-1-cosine--tier-2-entailment) checks whether a cited record supports the sentence citing it. Neither can see the model *edit* the fact it was handed. For a citation of an injected finding — the citation #142 was measured on — Tier-2 entailment does not run at all: reference-group citations are demote-only and skip it, so the only pass that sees them is Tier-1 cosine over the record's text, which a two-character change inside an alphanumeric token barely moves. Where Tier-2 does run, on a cited chart record, it is paraphrase-tolerant, which is exactly the wrong tolerance for a code substitution. Index validation passes either way, because the citation is real. Live, the chip said `J01MA` (fluoroquinolones) while the answer, citing that finding's record number, said `J01CA` (penicillins) — two drug families, one character apart, in a sentence a clinician reads as a classification claim. Six further captures on #142 show the same handling: a character mutated, a code invented beside the true one, granularity changed in either direction, a correct code duplicated.

### Decision

`ClassCodeFidelityCheck` runs after every answer on both the blocking and the streaming path (not on a cache hit — that answer was checked when it was produced) and reports at WARN every ATC-shaped token the answer states that appears in **no** record the answer cites, carrying the codes stated, the records cited, the codes those records state, and the patient. Seven things are deliberate.

1. **It says nothing unless a cited record states a class code.** With nothing to copy there is no copy to be unfaithful to, and this is what keeps ordinary prose out of the check: an answer about dosing frequencies cites drug-order records, which carry no codes. Without this gate the check reports on a *resemblance* — measured during review, the shape alone matches `Q12H`, `Q24H`, `Q48H`, `D50W`, `G12C`, `H63D`.
2. **The shape is constrained to ATC's fourteen anatomical main groups** (`A B C D G H J L M N P R S V`, derived from the index itself, where no code at any level starts with another letter). That is a closed set at the top of the classification; the level-2 groups beneath it are not, and a stale table of those would fail *silently*, by no longer detecting a real miscopy. `Q12H` and its siblings die here. `D50W`, `G12C` and `H63D` die on gate 1 only in an answer that states no real class code; in an answer that states one they are reported, and that is the residual false-alarm shape, left un-narrowed for the reason just given.
3. **A code the QUESTION states counts as support.** A clinician who types a code and gets it back has been echoed, not misled — the same reading this module already applies to a question-named drug.
4. **It reports; it never rewrites.** Deleting a token from a clinician-facing sentence is a larger decision than this check is licensed to make, and a silent edit is worse than a visible flag. Nothing about the verdict reaches the wire either: since [Decision 25](#decision-25-citation-grounding-tier-1-cosine--tier-2-entailment)/#201 a reference-group citation publishes no verdict at all, so a clinician-visible form is a wire and UI change and is deliberately left open on #142.
5. **Whole tokens, on both sides.** The answer's codes and the record's codes are read by one pattern and compared as tokens, so `A02B` is not "found in" `A02BC`. A `contains()` comparison would pass the truncation and the duplication captures alike; the granularity change is a different, broader claim, not a copy. **And no roll-up**, though the module has the reduction to justify one (`DrugReference.atcSubgroups`, what the chips use) and an answer naming the level-4 class of a cited level-5 code is usually right. Accepting it was written and then removed: support is pooled across the cited records, so the roll-up silences #142's own headline capture on any chart citing a reference record for a drug in the wrongly named class — records stating `J01MA` and `J01CA04`, and "same ATC class (J01CA)" becomes supported. Reporting a correct generalisation costs a log line a maintainer dismisses; failing to report the fabrication this check exists for costs the check its purpose.
6. **Every cited record, chart evidence and reference material alike.** A code can be read off a chart record — a note, an order's display name — not only off an injected finding, so restricting the comparison to `safety_finding` records would accuse a faithful answer.
7. **It abstains for the whole answer when any cited record carries no readable text** — the same "cannot verify" treatment the grounding verifier gives a null/blank record text. A record we could not read may be the one that states the code.

Its pattern is its own and must never be merged with `ChartSearchAiUtils.INLINE_CITATION`: that one parses citation *markers* and is deliberately single-index, because a bracketed group in prose can always be a clinical value (`[120, 80]`) and widening it fabricates references. This matches a code token wherever it appears. Same brackets, different question, no shared consumer.

### What it costs, and what it cannot see

One regex pass over the answer and over each cited record's text: no model call, no embedding, no I/O. It is inline before `search` returns and after the streaming path's user-visible handoff, in both cases microseconds.

Measured by driving the production predicate `ClassCodeFidelityCheck.classCodesIn` (not a re-expression of it) over the 340 live answers this project's probe sweeps captured, August 2026 — a corpus outside the repository, so the figures are recorded rather than re-derivable here: 33 answers state an ATC-shaped token, every one a real ATC code in a real classification sentence, and reading the same shape case-insensitively matched nothing further. That corpus contains no `Q12H`-shaped counter-example, which is why gates 1 and 2 come from an adversarial read of the shape rather than from it.

Four things stay invisible or wrong by construction. A duplicated correct code is textually supported by its record; so is any code the answer OMITS — this reads what the answer states, never what it fails to state. A code in the model's *reasoning*, which the streaming endpoint surfaces as its own event, is not read at all, because reasoning cites nothing and every code in it would be reported. And two things are reported that a reader may not expect: a correct generalisation of a cited substance code to its class, per decision 5; and a code the model read off a chart record it did not cite, which is this decision's title rather than an oversight — the system prompt requires citing every record an answer references.

### Rejected

- **Keeping the code out of the record the model recites** (#142's direction 1: render the class *name* instead of, or beside, the code, so a miscopy reads as visibly wrong). The names are the data [Decision 34](#decision-34-an-atc-subgroup-licenses-only-the-claim-its-own-name-asserts) records the module as not carrying: no shipped dataset has them (the `atc` source format reads them only from a file an operator supplies), and whether the WHO ATC/DDD index they were read from may be redistributed with this module has not been established. The chip and the finding record share one sentence, so it is also a change to what a clinician reads, and the sentence is written out in 70 lines across 17 test files, 66 of them inside a string literal (`grep -rn "same ATC class (" --include='*.java' api/src/test omod/src/test`, measured on this branch — a different grep counts differently, which is why it is named; and every merge moves it, so re-run rather than quote). Complementary rather than alternative: rendering a name makes a miscopy legible, it does not detect one — and a name can be miscopied too.
- **Documenting it and doing nothing** (#142's direction 3). Defensible only if the failure is rare, and its rate is unmeasurable here: answer prose is not reproducible on the reference box (`cache_prompt` KV reuse), so "rare" cannot be established. What *is* deterministic is the check.
- **Comparing the answer's codes against the CHIPS instead of the cited records.** The chips are what the answer was expected to report, not what it was licensed to state; a model that legitimately declines to repeat a chip would be reported, and a code read off a chart record would be too.

## Decision 36: The shipped default is the whole DDInter knowledge base

**Status: Accepted** (August 2026) — implemented. Extends [Decision 24](#decision-24-drug-reference-as-a-pluggable-consumer-of-authoritative-datasets).

### Context

Decision 24 made the drug-reference data layer pluggable and left the default where Decision 23 had put it: the hand-curated `drug-reference.json`, **four drugs**. The `ddinter` adapter shipped a 16-drug excerpt as its classpath fallback, with the real 18.9 MB `ddi_knowledge_base.json` left for the operator to download from the [openmrs-ddi-knowledge-base](https://github.com/pbiondich/openmrs-ddi-knowledge-base) project and point `dataFilePath` at.

That default under-delivers by so much that it misrepresents the feature. An implementer who switches `chartsearchai.drugReference.enabled` on gets interaction cover for ibuprofen, paracetamol, amoxicillin and gentamicin, and nothing else — while the module's own machinery (the severity floor, the pair cap, the substance-identity rules, the ATC class arms) was built and measured against the full knowledge base. Nothing in the product tells the operator that the safety net they just enabled is nearly empty, and "no interaction found" is indistinguishable from "not in the four drugs I know".

### Decision

**Bundle the whole knowledge base and select it by default.** Two global-property defaults move, and the classpath fallback becomes the real dataset:

| | before | after |
|---|---|---|
| `chartsearchai.drugReference.sourceFormat` | `json` | `ddinter` |
| `chartsearchai.drugReference.dataFilePath` | `chartsearchai/drug-reference.json` | `chartsearchai/ddi_knowledge_base.json` |
| bundled `/chartsearchai/ddi-knowledge-base.json` | 16-drug excerpt | the whole KB (2283 substances, 8234 mechanisms, 295,184 rows) |

The path default is the **upstream release's own filename**, so refreshing the knowledge base is a file copy into `<appdata>/chartsearchai/` with no property to edit — and therefore no way for the path and the format to end up disagreeing. The module never creates that file, so an untouched install falls back to the bundled dataset and stays silent about it, which is the rule `DrugReferenceValidity` already enforced for the path.

Measured through the production `DdiDrugReferenceSource.parse` over the shipped file: **0.6 s** to parse cold (~0.4 s warm), **~30 MB** retained for the module's lifetime, **2.1 MB** of packed jar. The parse is lazy and once per module lifetime, so the cost lands on the first drug question after a restart. The interning the parser already does for mechanism notes and severities is what keeps 590,312 partner links inside 30 MB.

The excerpt survives as a **test fixture** (`DrugReferenceTestSupport.DDI_EXCERPT`), because a case asserting "this record renders exactly these partners", "this entry has one partner" or "13 were withheld" needs a dataset whose partner lists it can state — lisinopril alone has 730 in the full KB, and pointing those cases at the shipped default would test the prompt budget's truncation instead of the behaviour each one is about.

### What the default gives up: dosing

DDInter publishes drug-drug interactions only. The shipped default therefore carries **no age band and no hand-authored allergy/condition rule**, so `chartsearchai.drugSafety.warnOnDoseExcess` defaults to `true` and has nothing it can ever fire on. That is the trade taken deliberately — 5 hand-authored interaction rules over 4 drugs, exchanged for ~295,000 severity-rated pairs over 2283 substances — and it is not a regression this decision hides:

- Decision 24's data-availability matrix already records that **no free authoritative dataset publishes dosing maxima** (WHO DDD is a drug-*utilization* statistic and adult-only; the Model Formulary carries maxima as prose). The curated seed was never a knowledge base, only a seed.
- Contraindications still reach the clinician on the three arms that need no hand-authored rule: a recorded allergy to the drug itself (which needs no ATC code either), a shared ATC level-4 subgroup, and the curated cross-reactivity groups. 1839 of the 2283 entries publish ATC codes.
- `sourceFormat=json` remains selectable for a deployment that wants the dose ceilings, and `dataFilePath` takes any dataset that publishes age bands.
- `ShippedDrugReferenceDefaultTest` **pins the bound** rather than leaving it to be discovered, because a safety arm that cannot fire looks exactly like one that found nothing.

### The consequence nobody expected: a shipped dataset that trips our own rules

The module's load-time validity check (Decision 32, issues #150/#156/#196/#211/#242) reports what is wrong with the dataset it just loaded. Run over the real knowledge base it fires on **19 of the 2283 rows**, plus 28 interaction rows the parser drops as self-paired:

| rule | count | shape |
|---|---|---|
| `alias-names-another-substance` | 18 | `Omeprazole` publishes `esomeprazole`; five `Tozinameran`/Pfizer rows publish `moderna covid-19 vaccine`; `Trastuzumab emtansine` publishes `trastuzumab deruxtecan`; … |
| `derivative-merged-with-its-parent-substance` | 1 | `Fluoroestradiol f-18` keyed as `estradiol` |
| `self-paired-interaction-rows` | 28 rows | both sides the same substance |

That collided with two standing rules: *a rule must stay silent on an untouched default*, and `everyShippedDatasetSatisfiesEveryRule`, whose premise was that every dataset the module ships satisfies every rule. Three resolutions were considered and two rejected on evidence.

**Correcting the data ourselves was rejected.** It sounds obvious — we are the ones shipping it — and it tops out at 10 of 19. Classifying each offender by the field that holds the offending value: **10 are stray `ciel[]` cross-walk links** (a rival product wrongly attached to a row), which delete cleanly and assert nothing new. The other **9 sit in the row's own `rxnorm_name`**, which `DdiDrugReferenceSource` feeds to `setSubstanceName` — the field `DrugReference.substanceKey()`/`substanceGroupKey()` are built from, and that issues #164, #185 and #187 rest on. Editing it re-partitions substances on our own authority. And they are not typos: they are RxNorm ingredient normalizations of enantiomers, prodrugs and metabolites (`Hyoscyamine`→`atropine` — hyoscyamine *is* l-atropine; `Fenofibric acid`→`fenofibrate`, its active metabolite; `Fosnetupitant`→`netupitant`, its prodrug), so "correcting" them means authoring a clinical normalization decision per substance. `Fluoroestradiol f-18` would need a DrugBank id the module does not have. So a data fork cannot reach silence, and would trade a checkable provenance for one.

**Keeping every finding at WARN was rejected too.** It leaves three WARN lines on every module start of every install, naming rows no operator can act on — the "noise every install learns to ignore" that `DrugReferenceValidity`'s own class javadoc is written against.

**What was taken: the log LEVEL is scoped to who can act; the status channel is not.** A finding about the DATA is WARN when the dataset came from the application data directory (the operator's file, which they can fix) and INFO when it came from the module's own classpath (the dataset we ship, whose remedy is the upstream handoff #196 already defines). A finding about the CONFIGURATION never scales — and keying the scoping on the rule rather than on the origin alone is precisely what keeps that true, because #156's finding fires *when the operator's file was not read and the bundled dataset was*, so an origin-only rule would have silenced the one case Decision 32 and issues #149/#154 exist for. `DrugReferenceLoad.getFindings()` and `GET /chartsearchai/drugreferencestatus` carry every finding identically either way: **the level says who can act, the status says what is true.** Anything not classified as a data rule stays loud, so the fail direction is loud.

This keeps the rule and replaces its reason, the same move Decision 32's own history took twice. It also promoted the parser's self-pair count from a bare `log.warn` to a real finding (`self-paired-interaction-rows`, remedy `dropped`) — it was the one data verdict in the loader that never reached the status endpoint, and the only one that would have stayed loud about a dataset the module ships.

`everyShippedDatasetSatisfiesEveryRule` split accordingly, into `everyDatasetTheModuleAuthorsSatisfiesEveryRule` and `theDatasetTheModuleRedistributesReportsOnlyFindingsItsOwnProvenanceExplains`: a dataset the module **authors** must still produce no finding at all, while the dataset it **redistributes** must produce only findings the scoping may soften — a configuration finding among them would mean the softening had swallowed something that names an operator's own choice. The counts are deliberately not pinned: that would break the build on any refresh, **including one that fixes these rows**.

### Provenance

Bundled byte-identical to the upstream release, so it can be verified rather than trusted:

- source: `ddi_knowledge_base.json`, openmrs-ddi-knowledge-base @ `main` (last upstream commit 2026-07-22, "Fix ATC to level-5 and enforce it in the build pipeline")
- `sha256` = `3eccdfec3838cdd9f5877f341b122fef65057b02091b5f3d875e56f8c2eb7a07`, 18,918,643 bytes
- underlying data: DDInter 2.0 (Xiong G, *et al.*, *Nucleic Acids Research*, 2025), CC BY-NC-SA 4.0; the upstream project's `ATTRIBUTION.md` records its terms review, and the README carries the attribution the module now owes as a redistributor.
- refreshing it: replace the file, run the suite (`DrugReferenceValidityContextTest` and `ShippedDrugReferenceDefaultTest` are the checks that speak), and re-record the hash here.

### Trade-offs

- **+** The feature is real out of the box: 2283 substances and ~295,000 rated pairs instead of 4 drugs, with no download and no configuration.
- **+** The module's own measured behaviour (severity floor, pair cap, substance identity, ATC class arms) was tuned against this dataset, so the default is now the configuration the code was designed for.
- **+** A knowledge-base refresh is a file copy, and provenance is checkable by hash.
- **−** The dose-excess arm is dormant by default; an install that needs dose ceilings must select `sourceFormat=json` or supply a dosing dataset.
- **−** The module becomes a redistributor of a third-party academic dataset, with the attribution, NC licence terms and governance caveat that carries.
- **−** 19 known data defects ship with it, reported but unfixed, pending an upstream handoff.
- **−** +2.1 MB of packed jar, ~30 MB of heap, and 0.6 s on the first drug question after a restart. (The omod grows twice that: its build unpacks the whole api jar into the omod root as well, an SDK-archetype step whose stated purpose is only `moduleApplicationContext.xml` and `messages`. Narrowing that would recover ~2.1 MB and is untouched here.)

## Known limitations

- **Counting questions**: LLMs are unreliable at precise counting tasks (e.g., "how many weight records in the last 10 years?"). The model may undercount or overcount even when all relevant records are provided. Larger, more capable models perform better at counting but are still not perfectly reliable. This is a fundamental limitation of LLM inference, not a retrieval issue. Questions that require exact counts are better suited to structured queries.

## Planned future work

- **Concept graph traversal**: Complement embedding search with OpenMRS concept relationship traversal to improve retrieval for queries involving related concepts (e.g., finding NSAID allergies when asking about ibuprofen).
- **Pre-computed summaries**: Cache LLM-generated summaries for common query patterns (e.g., "current medications", "active problems") to reduce inference latency for frequently asked questions.
- **Agent/tool-use pattern**: Enable multi-step reasoning where the LLM can request additional data or perform follow-up queries. Deferred until local models with reliable tool-use capabilities are available.
- **Multimodal medical image interpretation**: Extend the pipeline to pass complex observations (X-rays, dermatology photos, ultrasounds, pathology slides, scanned documents) alongside text to multimodal LLMs like MedGemma 1.5 4B. The main changes are: have querystore's obs serializer carry complex-value obs, add an optional image field to `SerializedRecord`, and update `LlmProvider`/`LlmEngine` implementations to construct multimodal content arrays (text + base64 image blocks) for the OpenAI-compatible `/chat/completions` API. Both engines already speak this protocol — the embedded llama-server supports multimodal via libmtmd, and remote backends (vLLM, OpenAI, Anthropic) accept the same content-array format. No new serializers are needed — complex obs are still observations.
- **Unstructured data / image OCR**: Extract text from photos of paper forms at write time so the content flows through the existing serializer and embedding pipeline.


## Decision 37: A safety answer's call is as strong as the finding's rating

**Status: Accepted** (August 2026) — implemented. Extends [Decision 23](#decision-23-drug-reference-injection--post-answer-drug-safety-validation).

### Context

The deterministic layer has rated every interaction it raises since issue #207 put the source's severity on the chip. The **answer's** opening call did not use it. The addressed-safety branch of the system prompt asserted, of any finding naming the drug asked about, that "that finding is evidence against giving it, so begin the answer with the call it supports — No", and the one demonstrated safety verdict in the few-shot was a **Major** finding refusing a delivery. Nothing said what a Minor rating should produce, so it produced the same refusal.

Measured on the local standalone, `main` @ `b0cfe545` (bundled DDInter KB, `gemma-4-E4B-it-Q4_K_M`), asking "Is gentamicin appropriate for this patient?" of a patient on lidocaine:

```
No — gentamicin should not be given: Gentamicin interacts with active order lidocaine, a Minor interaction [239].
```

The chip beside it carried the finding's own mechanism text, which ends *"Data are available for neomycin only. No special precautions are necessary."* The answer withheld a drug on evidence that says no precautions are needed.

It was also never the severity that decided the strength — the wording was. The same patient and question on this box produced *"which requires monitoring"* on two runs (2026-08-11 17:21, 17:46) and the flat refusal on the others, and answer prose here is not reproducible at all (`cache_prompt` KV reuse, `LocalLlmEngine`). A clinical call carried only by wording is a call nobody can pin.

### Decision

**The record states what the finding licenses, and the prompt follows what the record states.** Three pieces, no new data and no new global property:

| | |
|---|---|
| `DrugSafetyValidator.licensesWithholding(SafetyWarning)` (over the rating-only `ratingLicensesWithholding`) | the one definition of the split: `minor` and `unknown` are cautions, `moderate` and `major` withhold, and **unrated withholds** |
| `DrugReferenceInjector.renderFinding` | appends `STRENGTH_WITHHOLD` / `STRENGTH_CAUTION` to **every** finding it renders, breaking the sentence with the `endSentence` rule the chip detail already uses |
| `LlmProvider.DEFAULT_SYSTEM_PROMPT` | the evidence-against claim becomes conditional on what the finding says, and the caution class is **demonstrated** beside the existing Major refusal — both clauses taken from the production constants, for the reason `FINDING_PREFIX` is |

**Unrated withholds, and that is the half a "no rating means nothing serious" reading gets backwards.** A null severity is not a low one, and it covers two things that withhold for different reasons. A **curated** rule is unrated because an implementation authored it deliberately — `severityPriority` already sorts it *above* `major` for that reason — so reading it as a caution would silence the arm a deployment added on purpose. An **ATC-subgroup or cross-reactivity join** is unrated because the reference data relates the two drugs without rating the relationship, and nobody authored it at all: it withholds because that is the behaviour it already had, not because anything argues it should. That second case is the weaker claim and is deliberately left where it was; grading those joins is its own decision, on its own evidence.

**Every finding states one of the two clauses, and silence is not a third answer.** This was got wrong first, in the way that matters: the clause was scoped to interaction findings, on the reasoning that a contraindication licenses withholding without needing to say so. It does not, because the same change made the prompt's evidence-against claim *conditional* on the finding saying it, and a finding matching neither antecedent falls through to whichever branch the model reaches for. Measured on the standalone against `main` @ `b0cfe545` — Betty Williams (`a7090f70`), one **Severe** recorded Aspirin allergy, one NSAID cross-reactivity chip, no interaction finding:

| question | `main` | scoped to interactions | with the clause on contraindications |
|---|---|---|---|
| Can this patient take ibuprofen? | `No — ibuprofen should not be taken: …` | `Ibuprofen can be given, with one caution: …` (3/3) | `No — Ibuprofen should not be given: …` (3/3) |
| Is naproxen safe for this patient? | `No — Naproxen should not be given: …` | `Naproxen can be given, with one caution: …` (2/2) | `No — Naproxen should not be given: …` (2/2) |

The chip was identical in all three columns; only the answer's call moved, and it moved onto the caution demonstration's own wording. So a contraindication states `STRENGTH_WITHHOLD` — it is never a caution, and this is the strongest refusal the module makes, on the cross-reactivity case the README leads with.

An **overdose** finding is the one that genuinely wants neither clause, being a reason to change the **dose**, which withholding overstates and a caution understates. It cannot reach `renderFinding` today — `preAnswerFindings` validates with an empty answer and the dose arm parses a stated dose out of it — so it falls to the empty default, and that default is now a defect waiting on a caller rather than a safe fallback. Whoever makes the arm reachable gives it its own clause in the same change.

**What guards that is the premise, not the conclusion**, and this decision said otherwise until review read the case it named. `SafetyFindingSeverityStrengthTest.theTypeThatStatesNeitherClauseCannotReachTheRendererBeforeThereIsAnAnswer` drives an arrangement that *does* raise an overdose warning through the real `validate` given an answer, and asserts the pre-answer path raises none, so it reddens the moment the dose arm becomes reachable from the injector. `everyInjectedFindingStatesOneOfTheTwoStrengths` does not: it iterates the findings one fixed arrangement produced, and no arrangement of `injectRecords` produces an overdose finding, so it can never observe the type it was named as the guard for. A caller that renders findings after an answer exists is a new path neither case runs, and it writes its own clause with no test behind it. Recorded here rather than quietly corrected, because a rule defended by a guard that cannot fire is the failure mode CLAUDE.md's memo-scope bullet exists to name.

**A SET of findings states one lead, and the strongest governs it.** Two gated branches need a rule for the case both antecedents match, where the single unconditional claim they replaced had nothing to resolve, so the paragraph now says: *where more than one finding names the drug and they state different strengths, the strongest governs: a finding that is a reason to withhold it outranks one that is only a caution to note, so open with "No"*. It names the loser as well as the winner because the phrase the withholding clause names its class with, "a reason to withhold it", occurs inside the caution clause too, negated ("…is a caution to note, **not** a reason to withhold it"), so a rule whose antecedent were that bare phrase would be satisfied by a caution read shallowly, with only the "different strengths" half separating them. The clauses themselves are not substrings of one another; the shared phrase is. That case is common rather than a corner. Through the real `injectRecords` over the DDInter sample fixture, a patient on Warfarin and Aspirin asked *"Is it safe to give methotrexate?"* is handed the **Minor** warfarin caution first and the **Major** aspirin withholding second, and 10 of that fixture's 16 entries produce an interleaved mix when the patient is on the rest, caution before withhold in every one. The interleaving is structural: the drug-in-play arm emits one finding per partner in the entry's own rule order with no severity sort (`PAIR_SEVERITY_DESCENDING` orders the question-pair arm and `SCREENED_PAIR_SEVERITY_DESCENDING` the screen; `addInteractionWarnings` does not), so the lead cannot rest on the strongest happening to be read first. `warfarin × aspirin` with a Major rating is also the case issue #283 names as the one this arm exists for, so without the rule the change could weaken exactly the refusal it was asked not to. The rule is worded as a positive antecedent like the two branches above it, because `LlmProviderTest` fails this paragraph on the substring `otherwise` in any casing (#107 arm D), and it is **described rather than demonstrated**: a third safety demonstration would cost a sixth record plus a rewrite of the citation assertion that pins the durian demo, for a case that was measured resolving correctly with no rule at all (the mixed-set re-run recorded on this PR's review thread, not a fresh measurement). `SafetyVerdictSeverityGradationTest.aSetOfFindingsStatingDifferentStrengthsIsLedByTheStrongest` pins it; every other clause assertion in the suite is per finding. The same shared phrase reaches the two BRANCH antecedents above, which is where it was left standing: each names its own clause, and the withholding one is now asserted NOT to name the caution class, because the assertion that it names "a reason to withhold it" passes on either clause. Measured by mutation before that assertion existed: swapping the two antecedents, so the prompt says a caution is evidence against giving the drug and a withholding finding is not, left the api suite green.

**The clause states the finding's strength, never a prescribing action.** That is what keeps it true on the arms whose subject is a drug the patient is already **on** — the interaction screen (#113) and the allergy-versus-active-order join (#143) — where "withhold it" reads as a reason to stop rather than a reason not to start. Both are the finding's own claim; neither is this module telling a clinician what to do. Measured, the screening answer is unchanged by it: *"Yes, there are several drug interactions recorded: …"*, 3/3.

**The chip and the wire are unchanged.** The clause is prompt-facing evidence about how far a rating reaches, not a clinical instruction to put in front of a clinician, and `safetyWarnings` is a published shape.

**A folded finding takes the stronger claim.** Issue #171's fold puts the class arm's duplicate-therapy or cross-reactivity sentence onto a rated rule's chip when both arms are about one co-medication, so one finding asserts two things while `SafetyWarning.getSeverity()` keeps reporting the *rule's* rating — deliberately, because folding must not move what the pair is rated. Keying the clause on that rating alone made the fold lower a claim: a Minor rule folded with duplicate therapy read as "a caution", while the same relationship standing alone reads as "a reason to withhold" because it is unrated. That would also have been a behaviour change beyond this decision's scope, since before it every finding produced a refusal. So `licensesWithholding` is asked of the **finding** (`ratingLicensesWithholding` is the rating-only primitive underneath it), and `SafetyWarning.carriesUnratedRelationship()` carries the fold to it. Measured over the shipped knowledge base through the production predicates (the real `DdiDrugReferenceSource.parse`, `DrugReference.atcSubgroups()`, `DrugReferenceService.lookupByToken`): **108 of the 24,690** Minor-rated interaction ROWS the parsed model carries pair two drugs whose subgroups intersect, and the ROW is the unit that matters because a chip is raised per subject so either orientation can fold. So the shape is in the data rather than constructed for the test.

This paragraph added "54 unordered pairs, each held by both entries", attributed to a raw-JSON scan reading the same population on a different counting base, and review measured that wrong. Through the same three predicates the 108 rows are **56** unordered pairs of display names (44 by `DrugReference.substanceGroupKey()`, 60 keyed on the raw entry-name/token strings), and they are not two rows each: 32 pairs contribute 2 rows, 18 contribute 1, 2 contribute 3 and 4 contribute 5, so 18 of the 56 are held from one side only. The multiplicity is the multi-row families — `Amphotericin B` has three presentation rows beside the plain one, so `Amphotericin B | Clotrimazole` contributes five rows while `Amphotericin B (liposomal) | Clotrimazole` contributes one, because clotrimazole's own row names the token `amphotericin b` and `lookupByToken` answers with the plain row. 54 was 108/2 and not a second measurement, so there were never two counting bases to reconcile. Decision 33's rule stands; what it forbids is exactly this — a derived figure published beside a measured one with no base of its own.

**The fold flag is arm-scoped, so one pair can state two strengths.** `SafetyWarning.carriesUnratedRelationship()` is set only by `addInteractionWarnings`, because `classRelationships` runs per IN-PLAY substance and the interaction SCREEN (#113) answers a question naming no drug: the screen builds through the two-argument `interactionWarning` and never sets it. So the same Minor-rated pair, on the same two active orders, states *"a reason to withhold it"* from the drug-in-play arm and *"a caution to note"* from the screen — a property of which arm ran rather than of the pair. Measured through the real `injectRecords` over `chartsearchai-test/ddi-folded-minor-class-pair.json`, whose two drugs share `N06BA`. It is left there deliberately — giving the screen the class arm's sentence would change the DETAIL of a published `safetyWarnings` chip, which #113 and #171 would both have to re-measure, and it is outside what this decision set out to do. The chip is unchanged either way, and the graded branch is gated on a finding naming the drug asked about, which a screening question does not, so no verdict is decided by it today. What was missing is that nothing said so and nothing checked it: before this decision neither record stated a strength and the prompt refused on either, so the two arms differed in detail text alone and the divergence was not a difference in the CALL. `FoldedFindingStrengthTest.theScreeningArmStatesTheWeakerClaimForTheSamePairBecauseItRunsNoClassArm` pins it, non-vacuously by mutation — `carriesUnratedRelationship()` returning true unconditionally reddens that case and neither of the two beside it — so moving either arm is visible. The question-pair arm does not set it either, so its finding always states the strength its rating licenses; the fold happens only inside `addInteractionWarnings`, so a class relationship that does hold for one of those drugs is never folded into the pair finding and reaches the model as its own unrated warning instead. This paragraph first said the question-pair arm has "no co-medication for a class relationship to hold against" because its two drugs need not be on the chart, and that does not follow — the patient can be on one of them; the narrower statement is what the flag actually needs.

### Why the caution branch is not a "Yes"

Because a "Yes" here was measured wrong before. Issue #107's arm C let the addressed branch defer to the general yes/no rule, whose criterion is *presence* — and on a safety question a record naming the drug is evidence against it, so the model produced an inverted *"Yes … ivosidenib (Major …)"* on 5 of 6 runs. That is why the never-`"Yes"` token is pinned by `LlmProviderTest` and stays pinned here. The caution branch therefore leads with neither: it states that the drug **can be given** and names the caution in the same sentence. Gradation, not loosening — the two properties hold together, and `SafetyVerdictSeverityGradationTest` asserts the second one beside the first for that reason.

### Trade-offs

- **+** The strength of a clinical call now rests on the rating the deterministic layer assigned, and both halves of the mapping are pinned by tests over the real pipeline (`SafetyFindingSeverityStrengthTest` drives the real injector over real datasets, one case per rating INCLUDING the `moderate` boundary itself; the full api suite is green at 1301 tests).
- **+** Nothing changes for `major`, `moderate` or unrated findings, which is where the refusals that matter live.
- **+** Measured on the drift-metric safety probe (`capture_probe_safety.sh`, 20 cells over 4 patients, both arms captured on this standalone with everything but the module identical): **verdict-led 6/7 → 6/7, abstained-on-an-ANSWER-cell 1 → 0, abstention held 11/13 → 11/13, unlicensed verdicts 0 → 0**, on one class flip. That flip is `agnes__safety-aspirin`, her own active order and no chip, where the baseline abstains — the defect the probe's fourth blind spot is about — and this branch does not. Reading that gate needed a fix of its own: it counted only YES and NO as verdict-led, so the caution lead scored as the #107 hedge and the same A/B read **verdict-led 6 → 5**, a regression against a branch that had not regressed. `score_probe_safety.py` now carries the caution class, and `fixtures/probe-safety/caution-lead/` pins it from a live capture of this build. Counting the lead needs the licence check beside it (`unsupported_caution`) *and* the whole shape the prompt teaches, which took three rounds to state. Matching only "the drug can be given" credited four hedges that fit a 40-character window and that neither `classify` nor `ABSTAINED` catches ("I cannot determine whether warfarin can be given" among them), which is the #107 hedge scored as its own fix, so the lead also requires the caution to be named in the same sentence; a hedge can name one itself (*"It is unclear whether warfarin can be given, so caution is advised"*), so a subordinating marker in front of the verb phrase is refused as well. That marker list was still a blacklist of the shapes seen, and review measured **eleven** more registers it did not see, every one scoring verdict-led — *"It is possible that warfarin can be given, with caution"* and the same frame under may / uncertain / doubtful / questionable / could-be-argued / nothing-states / insufficient-data / unsure, plus *"It seems …"* and *"Presumably …"*, the last two subordinating nothing at all, so no list of any length reaches them. What closes it is the half of the prompt's own shape the regex was not using: every lead it teaches opens **on the drug**, and the scorer already resolves each cell's drug through `_aliases` to filter `chips`, `own_drug` and `findings`. Anchored there a hedge cannot get in front of the call at all, whatever register it is written in, and the marker list is **gone**, replaced by a second positive rule rather than by one: the one span the anchor leaves open, between the drug name and the modal, is now stated positively by what a real lead needs it for — only NAME MATERIAL may stand there, whitespace and hyphens or a few more words of the name followed by a bracketed synonym (`Acetylsalicylic acid (aspirin) can be given`). One rule in place of four, and it subsumes them: a subordinating clause, a comma-delimited aside, a pre-modal adverb and a dose apposition are all bare words, commas or digits, none of which is name material. Getting there took three falsified claims, each measured rather than argued — nine markers of which eight could not fire, then three complementizers that missed *"Warfarin, unable to say, can be given"*, then a comma ban that missed *"Warfarin possibly can be given"* — which is why the span is no longer defended by a list at all. What it does not close is stated with it: a bracketed *epistemic* aside (*"Warfarin (uncertain) can be given"*) is shaped exactly like a synonym and still counts, and closing that means enumerating bracket contents. Every part of the rule is pinned by mutation, and the mutations also settle which rule does what — which is not what this decision first claimed. Drop the anchor and **only positives redden**: the real leads stop counting, because the span will not absorb `Ibuprofen ` either. Loosen the span to a bare 30-character window and **only hedges redden**. So the two cover the hedges redundantly and each uniquely holds the other half — the anchor admits the drug name, the span refuses everything that is not name material — and the evidence does not single out either as "the" fix. Per-mutation counts are deliberately not published: three revisions of this bullet carried a tally and each went stale when the rule moved, which is Decision 33's own lesson one level down. `CAUTION_LEAD_CASES` holds at least one case per part (including the KB display name in `DRUG_ALIASES`, without which ` acid ` would stand in front of the modal and a real aspirin lead would stop counting), the selftest names the case that breaks, and CI runs it. Two terms in `caution_led` are redundant under the anchor and say so rather than passing for guards: dropping `classify(...) == "NONE"` or `not abstained(...)` reddens nothing, since either needs a lead that opens on the cell's drug *and* on "yes"/"no"/"cannot"/"the records". Counting the lead inside `verdict-led` also made that column a UNION, which cost the A/B a comparison rather than a count: two arms tie on it while one leads with a refusal and the other with a permission, so a Major refusal rewritten as *"Clarithromycin can be given, with one caution"* printed no flip line and A=B on every column, where the scorer before this change printed `A:NO -> B:NONE` and moved verdict-led 3 → 2. The flip condition now compares `caution_led` as well, the A/B prints `of which the lead is a caution, not a refusal` beside the column, and `fixtures/probe-safety/caution-over-major/` pins both. Naming the class needs no rating, which is the point: whether the rating licenses the caution is still not asked, because a second copy of `licensesWithholding` in Python is the drift `adverse_finding` refuses, and that boundary now has a fixture of its own beside `wrong-partner/` rather than only a sentence. None of the three anchor rules costs a fixture, every arm's printed numbers are byte-identical across the anchor change, and all three under-count rather than over-count — "Warfarin can be given, but monitor INR" stops counting, and so does a lead that does not open on the drug ("The patient can be given ibuprofen, with one caution"), which is the safe direction for a gate whose other failure is fail-open.
- **−** `moderate` still refuses. Whether it should qualify instead is a clinical judgement this decision deliberately does not take: the reported defect is Minor, and DDInter's own Moderate tier carries mechanisms that a refusal does not misrepresent.
- **−** The prompt grows. The few-shot gains one record and one demonstration, and every finding carries an extra sentence. Measured on the standalone against a 32768-token context, `main` @ `b0cfe545` to this branch: the gentamicin question **8009 → 8309** (audit rows 6460, 6499–6501) and the ibuprofen control **11905 → 12254** (rows 6449/6451, 6502). The fixed few-shot is most of both and is paid on every query, safety-related or not, so its size is stated as its own measurement rather than derived from those deltas: read off `LlmProvider.DEFAULT_SYSTEM_PROMPT.length()` (a throwaway case in the api module, three builds compiled in turn), the constant is **6256** characters on `main` @ `b0cfe545` and **7637** here, so it grows by **1381**. That corrects the **1065** this bullet published, which no version of the constant produces: the growth to the build the token deltas were taken on was already **1173** (7429 characters, the prompt unchanged between that commit and the one before this round), and the mixed-set rule adds the remaining 208. The per-finding cost is 10 tokens, measured directly rather than divided out — Betty's ibuprofen question, which injects exactly one more clause than before, went **8474 → 8484**. Neither figure moved when the clause was extended to contraindications, because both questions inject only interaction findings *before* the answer exists — the contraindication chips beside them are raised by the post-answer pass and are never rendered into the prompt. **The three token figures predate the mixed-set rule** and are 208 characters of fixed prompt light; re-measuring them needs the standalone, and it has not been re-run, so they are left attributed to the build that produced them rather than adjusted on paper.
- **−** The guarantee is that the **evidence states its own strength**, not that the answer obeys it. The model can still write a refusal over a caution clause; what changed is that doing so now contradicts a sentence in the record it cites, which the answer-quality gate and a reader can both see.

## Decision 38: An active order the module cannot name is still one co-medication

**Status: Accepted** (August 2026) — implemented, issue [#290](https://github.com/openmrs/openmrs-module-chartsearchai/issues/290).

### Context

`PatientClinicalContextBuilder` skipped an active drug order it could read no name for — it can be neither rendered as a record nor matched against chart text, and a nameless line in front of a clinician is worse than silence. But `addAtcCodes` runs above that skip and needs no name, so the order's ATC codes reached the flattened union with **no order behind them**, and `DrugSafetyValidator.orderPartners` keys such a code on the raw code string (`identity = order != null ? order : (Object) orderCode`).

One prescription therefore became one duplicate-therapy chip **per ATC code the loaded dataset cannot name**, each labelled by the bare code — those are the codes that fall through to that `identity` line. A code the dataset *can* name never reaches it: it takes the entry rung above, keyed on `substanceGroupKey()`. So an order all of whose codes are covered yields one partner per covered substance, and that is unchanged here and deliberate — `CLAUDE.md` states it under `OrderPartner.substances`: an order mapped to two covered codes must stay two partners, or a question about the first constituent loses the second. **This decision therefore claims no shipped-knowledge-base symptom of one prescription presented as several different medications**, and an earlier revision of this paragraph did; it was measured false. Measured through the real `validate` with the curated seed, which carries neither code: a nameless order carrying `M01AE02` and `M01AE04`, asked about ibuprofen, raised **2 chips**; the same order with a display name raised **1**. Measured the same way on a FULLY covered order — `ddi-shared-class-choice`'s `H02AB02` and `H02AB04` mapped to one prescription, asked about hydrocortisone — **2 chips, naming Dexamethasone and Methylprednisolone, in all three states: nameless on this branch, nameless with `PatientClinicalContextBuilder` alone reverted to `main`, and left named**.

Issue #155's label ladder could not reach it — an order skipped by the builder never enters `getActiveDrugOrders()`, so there was no display name to fall back to. The builder's own comment had named this a known gap and prescribed the fix as "a fallback display rather than a skip".

**How often a real dictionary produces such an order is not measured**, and nothing here claims a frequency: the reachability argument is the builder's own — `addConceptName` swallows a `RuntimeException` from `concept.getName()` (a detached or lazy-init proxy) in its own try while `addAtcCodes` succeeds in a separate one, a dictionary's names can be voided, and a recorded name can be blank (`addRaw` drops it, so `getName()` need not be null at all). The order must also carry at least one ATC code to reach any of this, since the defect is one chip per code the dataset cannot name; on the 3.7.1 reference demo dictionary only 16 of 43 active drug orders carry a code at all (the complement of the measurement recorded for issue #228).

### Decision

Such an order reaches the per-order list through `PatientClinicalContext.ActiveDrugOrder.namedByCodesOnly`, carrying:

- a **display of its own ATC codes labelled as codes** — `[ATC C10AA01, J01FA09]` — built from the shared normalizer so the label cannot disagree with the codes it keys on;
- an **empty name set**, because that set is lowercased and matched against chart prose, so a code in it would match free text;
- the order's **real uuid**, so the injected record stays citable.

An order with no name *and* no ATC code is still skipped: nothing can name it and no chip can be raised for it.

Two guards keep a synthesized display from behaving like a name. It is withheld from `OrderPartner.nameByOrder`, so it can never displace a name the reference data supplied — asked of the order (`hasKnownName()`), never re-derived as "are its names empty", which is a proxy the public constructor lets a caller falsify. And `orderCarrying` prefers a carrier that can name itself, because it picks **one** carrier of a dataset-unnameable code and that pick decides the partner's label; taking the first would let a code list displace a real drug name on the strength of the sequence `OrderService` returned the prescriptions in.

### Trade-offs

- **+** A nameless order can no longer witness its **own** interaction. Issue #132's per-order exclusion needs the order to be *in* the per-order list; without it only the flattened fallback applied, which cannot tell one order carrying two codes from two orders carrying one. Reverting only the builder makes the pinned case report `Simvastatin interacts with active order clarithromycin — Major` off a single tablet. A false positive removed, not a wording change.
- **+** One chip instead of one per **dataset-unnameable** code — the reach the Context states, and not a change to how a covered code is keyed. Not strictly one per PRESCRIPTION: where two orders the module cannot name carry the same unnameable code, `orderCarrying` picks one of them and both collapse onto that partner, so the unit is really one per pick. Observed live on the 3.7.0-rc.2 standalone at `b0a24a96` — two nameless orders, three unnameable codes each, one `[ATC A01AD05, B01AC06, N02BA01]` clause — and it is the same list-order residue `orderCarrying`'s javadoc and `CLAUDE.md` record.
- **+** The order becomes a citable chart record instead of being invisible to the reconciliation that exists to substantiate the chip. Observed live at `b0a24a96`: an active order the querystore index had never seen was injected as `active_drug_order` carrying its real `Order` uuid and was cited by the model in its answer, and the reconciliation WARN counted it (`1 of 9`). Before this change such an order was skipped by the builder, so it could be neither counted, injected nor cited.
- **−** Where the shared class is matched through the **unnameable** code alone, the covered constituent's name stays on the chip, so its stated class need not classify the drug it names — issue #161's shape, now reachable for a partly-covered nameless order. Accepted because the alternative was measured to contradict itself: letting the code list win put `[ATC N02BA01, N02BA99]` beside the rule arm's `aspirin` inside **one** folded chip detail.
- **−** This order class is **uuid-only** for the #118 reconciliation. With no names, `namedIn` can never be true, so the name fallback that `DrugReferenceInjector` documents as insurance against uuid-contract drift is unavailable exactly here.
- **−** `RESOURCE_TYPE_ACTIVE_DRUG_ORDER` groups as `REFERENCE_GROUP_CHART`, so unlike `drug_reference` and `safety_finding` its grounding verdict **is** published on the wire, and a record naming no drug may not entail a medication claim. A false `Unsupported` can therefore reach a client. Accepted because the alternative is the order being invisible; carried forward as [#294](https://github.com/openmrs/openmrs-module-chartsearchai/issues/294), which asks for the measurement first — nobody has yet observed a published verdict for one of these records.
- **−** Not fixed: the rule arm names its partner from the **rule's** own token (`partnerLabel`), which nothing the builder supplies can reach, so the rule arm and the class arm can still name one order differently. That needs a change to the interaction arm and is [#292](https://github.com/openmrs/openmrs-module-chartsearchai/issues/292), where the folded chip detail that shows it is recorded verbatim from a live run. **Narrowed by Decision 39**, which reconciles the two arms wherever the partner's name came from the DATASET — including the folded detail quoted in #292 — and, where the name came from an ORDER, only where the rule's own token names that order, since otherwise a rule token and an order name need not denote the same drug. Not closed: that decision's own trade-offs list the folded shapes that still carry two names.

## Decision 39: A folded chip names one active order once

**Status: Accepted** (August 2026) — implemented, issue [#292](https://github.com/openmrs/openmrs-module-chartsearchai/issues/292). Narrows Decision 38's last **−** rather than closing it; the trade-offs below say which folded shapes still name one order twice.

### Context

Issue #88's fold puts both interaction arms' sentences into a single chip detail, and each arm named the partner from its own source. The class arm uses the ladder in `DrugSafetyValidator.orderPartners` (Decisions from #155/#186/#290: the dataset entry's display label, else the ORDER's own display where a code is dataset-unnameable, else the bare code or the `[ATC …]` stand-in). The rule arm uses `partnerLabel`, which is `firstNonBlank(interaction.getToken(), interaction.getAtc())` — the rule's own match token, read off the reference row, and reaching nothing the context carries. So one prescription appeared under two names in one sentence.

Observed live on the 3.7.0-rc.2 standalone at `b0a24a96`, curated seed, one active order the module could read no name for, asked *"Can she be given ibuprofen?"* — one chip, verbatim:

```
Ibuprofen interacts with active order aspirin — additive GI and bleeding risk.
Ibuprofen is in the same cross-reactivity group (NSAID) as active order
[ATC A01AD05, B01AC06, N02BA01] — possible additive or duplicate-class therapy
```

The code-only display only makes it obvious. The divergence is systematic for a formulation, because the `ddinter` parser lower-cases every token it writes from the partner row's `rxnorm_name` while the class arm prints that row's `displayLabel()` — `cyclosporine` beside `Cyclosporine`, `aspirin` beside `Acetylsalicylic acid (aspirin)`. Both names are true of the order, so no claim is false; it reads as two co-medications where there is one.

### Decision

`DrugSafetyValidator.foldedPartnerLabel` decides the one name both sentences of a folded chip take, and the fold is the only place that can: it is where both arms' answers exist. Reconciling two names asserts that they denote one drug, so it reconciles only where that is provable — **and where it is not, each sentence keeps the name its own arm resolved**. Not stated as a count of paths: two of the three conditions below reconcile or refuse on a condition of their own (the first on whether the rule carries a token at all, the second on whether the rule's token names the naming order). The list is in the order the method asks them in.

- **The ladder found no name** (`!OrderPartner.namesADrug`). A bare code and the `[ATC …]` stand-in are the absence of one, so the rule's own token is the only name either arm holds and both sentences take it. That is the live case above. Asked of the token rather than of `partnerLabel`, which falls back to the ATC code: with no token either, nothing here is a name and nothing yields.
- **The label came from an ORDER** (`OrderPartner.namingOrder`) — reconciled only where the **rule's own token names that very order** (`namesNamingOrder`), because an order is not a substance and the name it supplied may be a different drug's. The test is `DrugReference.matchesOrderName` over that order's own `getNames()`, which is exactly the predicate `PatientClinicalContext.hasActiveDrug` applied to admit the rule in the first place — so it asks no new question about the pair, it asks that same question of one prescription instead of the patient's flattened name list, and being a narrowing of a set the rule match already satisfied it cannot license anything new. Asked of the ORDER and deliberately not of `labelEntry`: `nameByOrder` does not update that field, so on a renamed partner it identifies one drug while the label names another, and `unambiguouslyNames` would prove a fact about the first and hand out the second. Asked of the ONE order `namingOrder` carries and not of every carrier of the code, because `nameByOrder` is monotone and that is the order the label came from; the whole carrier set would prove a fact about one prescription and print another's name. Both shapes measured through the real `validate` refuse on it, and refuse for that reason rather than by luck: a partner keyed on `Naproxen` but renamed after an `Esomeprazole` order carrying naproxen's code (token `naproxen`, naming order `Esomeprazole 20mg`) printed an NSAID duplicate-therapy finding under the PPI order's name with `naproxen` nowhere in the detail; and one order carrying codes of two substances lets `ruleAbout` pick a rule by whichever code sorts first, so a **warfarin** rule was printed under `Aspirin 81mg` (token `warfarin`, naming order names `{aspirin}`) — reaching the prompt through `renderFinding` with `STRENGTH_WITHHOLD`. Both are the #161/#187/#194 failure, strictly worse than the legibility cost this decision removes. Mutating `namesNamingOrder` to always permit reddens both.
  - **This started as a blanket refusal and was narrowed in review round 2 of the implementing PR**, which is worth recording because the blanket form was described as not removable. Both measurements above are cases where the order-supplied name does not name the rule's drug, so the refusal was broader than its own evidence — and what it also refused was the ticket's *second named shape*, the ordinary formulation whose token and order name differ — the ticket writes it `warfarin` / `Warfarin Sodium 5mg`, and the pinned arrangement is `naproxen` beside a `Naproxen 500mg` order — leaving #292's own mechanism live on the rung its second example illustrates while the PR closed the issue. Under the gate that shape reconciles (`FoldedChipOnePartnerNameTest.anOrderSuppliedNameTheRulesTokenNamesIsHandedToBothSentences`), the live case in `ClassChipPartnerLabelTest.anOrderTheDatasetDoesNotCoverIsNamedByItsOwnDisplayName` reconciles too, and exactly one test changed expectation — that one, in the direction the ticket asks.
- **The rule's token does not name the ladder's entry unambiguously** (`unambiguouslyNames`) — refused, the two arms may be about different co-medications.
- Otherwise the ladder's label goes to both sentences.

What this reaches is therefore narrower than "every folded chip": the live case above, every partner the DATASET named — the `cyclosporine`/`Cyclosporine` and `aspirin`/`Acetylsalicylic acid (aspirin)` shape the ticket calls the ordinary case — and a partner named after an ORDER **where the rule's own token names that order**, which is the ticket's other ordinary case. A partner named after an order the rule is not demonstrably about keeps two names, deliberately.

Outside a folded chip nothing here applies: an unfolded rule chip and both grouping keys (`SubjectRule.partnerKey`, `DrugReferenceInjector.onePerPartner`) keep `partnerLabel`, the injected `drug_reference` note list keeps it too, and a class-only chip keeps the ladder's own label — which is what it always used, never `partnerLabel`. One unfolded chip DOES change: moving the guard into `OrderPartner.nameByOrder` means a partly-covered order whose display is blank no longer renames its partner after a bare ATC code, so a class-only chip for it now reads the dataset's name. **That is an improvement only where the shared class was matched through a code the dataset COVERS** — then the code was opaque and the name is right, which is issue #155's defect being removed. Where the class was matched through the UNCOVERED code alone, the covered constituent's name does not classify the drug the sentence names, so the chip trades an opaque-but-true code for a false claim: measured, `as active order M01AE03` becomes `as active order Omeprazole` under an M01AE class, and Omeprazole is `A02BC`. That is exactly issue #161's shape, which Decision 38 already accepted for a NAMELESS order and which this extends to a blank-display one — builder-unreachable (`PatientClinicalContextBuilder` takes the display from a non-blank name and routes the nameless case through `namedByCodesOnly`), so it needs the public constructor's latitude, the same latitude `aBlankDisplayNeverDisplacesTheDatasetName` relies on.

**A smaller variant was proposed in review round 2 and declined**: keep main's `firstNonBlank(display, orderCode)` label and `hasKnownName()` gate, and gate only the new `namesADrug` flag on `displayNamesADrug` — every unfolded chip then byte-identical to main, and a folded chip still safe because a code-valued label answers `namesADrug` false and routes to the token path. It buys back the opaque-but-true `as active order M01AE03` in place of the false `as active order Omeprazole`, which is a real point in its favour on this arrangement. It is declined because it keeps a standing rule broken to do it: `CLAUDE.md`'s "Whether an order's DISPLAY is a name at all" requires `nameByOrder` to ask before a display DISPLACES a name another source supplied, "a code list is the absence of a name", and a blank display resolving to a bare ATC code is that same absence displacing the dataset's own name — issue #155's defect, at the one write site the rule names. Two shapes of "no name" would then get two answers: the synthesized code-only display withheld (Decision 38) and the blank one admitted. What the trade costs is stated above and pinned by `aBlankDisplayNeverDisplacesTheDatasetName`, on an arrangement no builder produces.

Issue #121's invariant — on the branch where the dataset identifies no partner entry, the key IS the label the chip says — is therefore **scoped by this decision rather than preserved by it**. It holds for every unfolded chip; a folded chip on that branch can render the ladder's name while the grouping key stays the token, which is `ClassChipPartnerLabelTest.anOrderTheDatasetDoesNotCoverIsNamedByItsOwnDisplayName` (key `aspirin`, chip `Aspirin 81mg`). A cost really incurred rather than recorded defensively, and it is the ORDER path that incurs it: on the entry paths the rule names an entry of the patient's own orders, which `activeOrderEntryFor` then resolves, so `SubjectRule.partnerKey` is that entry and this branch is not in play. Measured by printing the key beside the rendered name for every folded chip `ClassChipPartnerLabelTest` and `FoldedChipOnePartnerNameTest` build: that one is the only chip whose rendered name differs from its key by more than the key's own case-folding. The absolute counts this sentence used to give (18 folded chips, 6 on this branch, with the other five named) are deleted rather than re-measured — issue #298 added a folded arrangement to the second of those classes and so moved them, which is the second time a tally here went stale; re-derive by printing the pairs, and note that the surviving claim is the one that mattered. The grouping itself is unaffected, running before the fold and on that same key. What it costs is that two rules about one order carrying different tokens still produce two chips, and one may now name the order by the ladder while the other names it by its token. **That cost is not confined to this branch**, and an earlier draft of this line said it was: on the ORDER rung a reconciled chip renders the naming order's display while a second rule under a different token keys on the entry `activeOrderEntryFor` resolves, so #121's no-entry branch is not in play at all and the response still carries one prescription under two names across two details. Observed live: one payload holding `active order Isoniazid / Rifapentine` (folded) beside `active order isoniazid` (unfolded). Two names across two chips is issue #136's pre-existing shape; the asymmetry is new. The branch qualifier above applies only to the key-versus-name divergence. Left standing because the alternative refuses to reconcile the very shape the live case above is drawn from.

The second condition is the one that needed the machinery. `ruleAbout` correlates the two arms through `entryForAtcCode`, which answers with the canonical row publishing a code, and a level-5 code can be published by two substances in this knowledge base (`Omeprazole` and `Esomeprazole` share `A02BC05`). `addPartnersForUnmappedOrders` already recorded that bound and said both sentences stay true, "what is lost is which co-medication the second sentence is about" — which holds only while the two NAMES differ, because those two names are the only evidence a reader has that two partners are in play. That javadoc also named this locus for the fix: "correlating on the partner's SUBSTANCE instead is a change to issue #88's fold rather than to this leg."

It is a name test and deliberately **not** a comparison of the two arms' resolved substances: `identifies` accepts a bare shared ATC code as well as a token, so on that very shape `activeOrderEntryFor` and `entryForAtcCode` both answer `Omeprazole` and a substance comparison would agree spuriously — licensing exactly the displacement it was added to refuse. Measured by mutating the gate to always displace: the chip reads `Pantoprazole interacts with active order Omeprazole — Major. Esomeprazole competes for CYP2C19 …`, one substance's rated mechanism under another's name. `FoldedChipOnePartnerNameTest.aRuleAboutAnotherSubstanceSharingTheCodeKeepsItsOwnToken` reddens on it, along with the two cases beside it — three in all.

**Why identity alone is not enough, which the first version of this decision got wrong.** `DrugReference.isNamed` asks whether the token is one of the entry's *aliases*, and the `ddinter` parser builds those from the entry's name AND its `rxnorm_name`. The shipped knowledge base's row named `Omeprazole` carries `rxnorm_name: esomeprazole` — the same row this decision already cites for publishing only esomeprazole's `A02BC05` — so `isNamed("esomeprazole")` is **true** of it and an identity-only gate *permits* the displacement on precisely the pair named above. Measured through the real `DdiDrugReferenceSource.parse` over the pinned excerpt: that row is `name=Omeprazole, rxnorm_name=esomeprazole`. Measured over the shipped KB through the same parser plus `DrugReference.isNamed` and `substanceGroupKey`: **25 of its 2093 distinct rule tokens are named by more than one substance** (`esomeprazole`, `hydrocortisone`, `trastuzumab`, `gabapentin`, `ketoconazole` …). A hand-written JSON fixture gives each row one self-name and so refuses for a reason the default dataset does not share — which is why the guard requires the token to name *one* substance and why `aRuleWhoseTokenNamesTwoSubstancesKeepsItsOwnToken` pins it in DDInter shape, through the real parser. No live shipped-KB instance has been demonstrated, and the reason is the severity floor rather than the absence of such a rule — an earlier draft of this decision said no `A02BC`-classed entry carries an esomeprazole rule, which is false. Measured through the real parser and `DrugReference.atcSubgroups()`: **three do** — Lansoprazole, Pantoprazole and Rabeprazole, each `token=esomeprazole, atc=A02BC05` — and all three are rated `Unknown`, which `clearsSeverityFloor` drops against the default `minor` floor. Driving the real `validate` over the shipped KB (patient on omeprazole/`A02BC05`, "Is pantoprazole safe here?") raises the class chip and no rule chip, so nothing folds. **A KB refresh that rates any of those three `Minor` or above makes this guard live-reachable**, which is the signal the earlier wording gave no way to see. What was corrected here is therefore the premise, the guard and the fixture rather than an observed chip.

### Trade-offs

- **+** One prescription is named once in the sentence a clinician reads, wherever the partner's name came from the dataset — the live case above and the ordinary formulation case — and, since the round-2 narrowing, wherever it came from an ORDER the rule's own token names, which is the ticket's other stated shape.
- **−** On that ORDER path the name both sentences take is a prescription DISPLAY, so the rule sentence can carry strength or formulation text its token never did: `interacts with active order Aspirin 81mg` where it used to read `interacts with active order aspirin`. No measurement is claimed for how often. It is bounded by the same union argument as the `safety_finding` bullet below — the class sentence of that very chip already carried the display, so the prompt's name union for the partner shrinks rather than grows — and it is the chart's own vocabulary, which is the direction issue #155 chose for the class sentence in the first place.
- **−** On the reconciling path the chip can lose a longer form of the partner's name that the rule sentence used to carry. Measured over the shipped KB by composing the gate's own predicates: of 522,024 rules carrying both a token and a code, 513,026 satisfy the gate, and on 2,406 of them the handed-out label does not contain the rule's token — **16 distinct pairs, every one of them the label being a shorter or parent form** (`st. john's wort extract` → `St. John's Wort`, `cholestyramine resin` → `Cholestyramine`, `insulin glulisine, human` → `Insulin glulisine`), none disjoint. Benign in direction, and the one pair that arguably names a different substance (`benzgalantamine` → `Galantamine`) is the `alias-names-another-substance` residue above rather than a second fault.
- **−** On the ENTRY path the reconciled detail may afterwards contain no string the CHART itself carries. Observed live: `Aspirin 81mg` named `Acetylsalicylic acid (aspirin)`, `Diclofenac Co 50mg` named `Diclofenac`, `Chloroquine Co 250mg` named `Chloroquine`. Before this change the rule sentence at least carried a token that is a case-folded substring of most order displays. The delta is small — the class sentence of that same chip already named the order by the dataset label, and #108/#155 chose that synonym-augmented label over the chart string deliberately and pinned it (`DrugSafetyChipLabelTest.classChipOrderNamesCarryBothVocabularies`) — so this is recorded as a cost of reconciling in that direction rather than a reason to reverse it.
- **+** The class sentence's wording is now one template rather than two (`ClassRelationship.sentence`), so the string a class-only chip and a folded chip's second sentence share cannot drift. The two shortenings issue #108 rejected are untouched.
- **−** The `drug_reference` record's interaction-note list still names the partner by `partnerLabel`, so where the fold takes the ladder's name the chip and that record name one partner two ways — the property `CLAUDE.md` states for `partnerLabel` and which #292 asked a fix to keep. Deliberately traded, and NOT because the plumbing is missing: `DrugReferenceInjector` holds the validator bean and the service, and `orderedInteractionNotes` already receives `orderEntries`. The reason is that the note is PROMPT text — keeping the property turns `aspirin (Major. …)` into `Acetylsalicylic acid (Major. …)` for every active-order partner across the whole knowledge base, and this repo has rejected four prompt changes on the eval gate for stronger motives than legibility. An earlier form of this bullet added that `displayLabel()` "forbids itself there anyway", quoting its own javadoc's "never used in prompt text" — **that was false and both are now corrected**: `interactionWarning` builds a chip's drug and detail from `displayLabel()` and `renderFinding` copies both verbatim, measured as `Safety finding — Acetylsalicylic acid (aspirin): Acetylsalicylic acid (aspirin) interacts with active order warfarin — Major. …`. What is pinned is the narrower property, that the `drug_reference` record's own text keeps `getName()` (`DrugSafetyChipLabelTest.displayLabelNeverLeaksIntoTheRenderedRecordText`). The eval-gate reason above stands on its own and never needed the other one. What bounds the trade: the prompt's NAME UNION for that partner never GROWS — the chip already carried both names and the record carried the rule's, so the chip goes from two names to one and the record is untouched. It can shrink, and the precondition an earlier draft left unstated is that the subject's `drug_reference` note list names that partner at all: where the subject is multi-row and the partner's rule sits only on a sibling row, `orderedInteractionNotes` never renders the token (`DrugReferenceInjector.collect`'s own measured residue — 80 of 121 multi-row substances, 2627 partners), so after this change the prompt names that partner by the ladder name alone. Benign in direction, the survivor being the dataset's or the chart's own name; `DrugReferenceInjector` already states the narrower true version, and only this summary overreached. Not carried forward as an issue yet; it needs the measurement first.
- **−** The `safety_finding` record's wording DOES move for folded chips, since `renderFinding` carries the chip detail verbatim and `preAnswerFindings` runs the drug-in-play arm pre-answer. Accepted on the same union argument: every name it can now carry is one the same prompt already contained.
- **−** The refusal is **over-cautious on a measurable slice of the shipped knowledge base, and the ticket's headline symptom survives there.** Measured through the real parser and the real `validate`: 72 above-floor rules carry an ambiguous token whose subject shares an ATC subgroup with an entry that token names. A patient on ketoconazole asked about osilodrostat still reads `Osilodrostat interacts with active order ketoconazole — Major. … Osilodrostat is in the same ATC class (H02CA) as active order Ketoconazole — possible duplicate therapy` — one prescription, two names, one detail, in #292's own `cyclosporine`/`Cyclosporine` shape — refused only because `ketoconazole` is also an alias of the separate `Levoketoconazole` row, which the patient is not on. No clean narrowing is available: `Levoketoconazole` publishes the same four codes, so a code-scoped test refuses too. Whether an ambiguity the patient's own orders resolve should still refuse is a decision on its own evidence and is not taken here; recorded so the next reader does not take the guard as closing what it does not.
- **−** A rule carrying **no match token at all**, only an ATC code, still names its partner by that code while the class sentence names it by a resolved entry — so the folded detail keeps two names, one of them raw: `Lisinopril interacts with active order C09AA05 — … as active order Ramipril — …`. `hasActiveDrug` joins such a rule on its code and `isNamed` answers false for a null token, so the gate refuses. Unchanged from before this decision rather than introduced by it, and reachable only on an operator-edited `sourceFormat=json` dataset (the `ddinter` parser always writes a token from the partner row's `rxnorm_name`). The refusal is not protecting against a mis-attribution here — `ruleAbout` correlated these arms through its exact-code leg, so they are demonstrably about one co-medication — but against asserting a substance a bare code does not license. Closing it means giving a token-less rule a name, which is a change to `partnerLabel` and therefore to the injected record too. Pinned by `aRuleThatCarriesOnlyAnAtcCodeKeepsBothNames`.
- **−** Outcome 1 can make the class sentence state something FALSE about a named substance, which is more than an earlier draft of this line claimed. Where the ladder has no name, the rule's token names the partner and the class sentence then asserts a class relationship about *whatever that token names* — which need not be the drug the matched code classifies. **ONE uncovered code suffices**; an earlier draft of this line said several, belonging to two substances, and that is too narrow. Measured two ways on `sourceFormat=json`: a nameless order carrying `B01AA03` and `N02BA01` yields "Ibuprofen is in the same cross-reactivity group (NSAID) as active order **warfarin**", and a single-code order on `M01AE03` against a rule whose token is `warfarin` yields "in the same ATC class (M01AE) as active order **warfarin**" — warfarin being `B01AA03` in both. Before, each read as the `[ATC …]` list: vague but true of the prescription. So the class sentence's SUBJECT moves from the prescription to whatever the token names, and "merely stops introducing a second name" understated it. `ddinter` cannot produce it, that parser taking a rule's token and its `atc` from one and the same partner row. Not closable here: bounding it to "the rule's own code accounts for the class match" refuses the live case above (the seed's rule cites `B01AC06` while the NSAID group matches `N02BA01`), and asking whether the codes are all one substance is undecidable on a branch entered only because none of them resolved an entry. Accepted on Decision 38's own reasoning pattern — a narrower fault, of unmeasured reachability, over a demonstrated one — and the observed live chip is the demonstrated one. Pinned AS WRONG by `aNamelessOrderCarryingTwoSubstancesCodesNamesTheClassSentenceAfterTheRulesDrug`, on the first of the two arrangements measured above, so that a change closing it reddens a test instead of leaving this bullet the only record.
- **−** `unambiguouslyNames` detects **ambiguity, not wrongness**. Where a KB row publishes another substance's own name — the defect the loader already reports as `alias-names-another-substance` (#211), which fires on the shipped KB — and the substance truly named has no row of its own, nothing is ambiguous and the displacement proceeds under the wrong name. Reproduced on a two-entry fixture: the chip named `Omeprazole` while the mechanism was about esomeprazole. The population the guard works on and the population it cannot see are disjoint, so closing this needs the KB rows fixed upstream, which is #196's handoff.
- **−** Chips of DIFFERENT subjects can still name one order differently — a class-only chip by the ladder, an unfolded rule chip by the token. Out of scope by the ticket's own framing, which is about one chip detail, and unchanged by this decision.

## Decision 40: A partner's name source has one write path

**Status: Accepted** (August 2026) — implemented, issue [#298](https://github.com/openmrs/openmrs-module-chartsearchai/issues/298). Behaviour-neutral: it makes a guarantee Decision 39 relies on a property of `OrderPartner`'s state instead of a property of one caller's discipline, **without** giving up the statement-order guard that already held it. Because it is behaviour-neutral it is pinned structurally rather than behaviourally.

### Context

`OrderPartner.namingOrder` carries the active order a partner's label was taken from, so that `foldedPartnerLabel` can ask a question OF that order (Decision 39's second condition, `namesNamingOrder`). Its companion `namesADrug` says whether the label is a drug name at all (Decision 39's first condition, and the `displayNamesADrug` guard Decision 38 moved into `nameByOrder`).

The two encode one fact and were written independently. `orderPartners`' order rung handed the constructor `order` unconditionally while passing `displayNamesADrug(order)` as the flag, so a `namedByCodesOnly` order — or one with a blank display — produced a **non-null `namingOrder` beside a label that is an `[ATC …]` stand-in or a bare ATC code**: a partner that looks order-named whose label is not a name. Reachable, and reached by `FoldedChipOnePartnerNameTest`'s own nameless-order arrangement.

Nothing was wrong with the output, because `foldedPartnerLabel` tests `!namesADrug` first and so never reaches the order branch for such a partner. The guarantee was real; it just lived in a caller's statement order rather than in the value, so a rung added later that read `namingOrder` first would hand a bare ATC code out as an order display — issue #155's defect, in **both** sentences of a folded chip, which is the failure the one-guard design in `nameByOrder` exists to prevent.

### Decision

**One write path for the pair, and no caller-supplied pair to disagree with it.** Two legs, and it takes both. `OrderPartner.recordNameSource(order, namesADrug)` is the only thing that assigns either field: it sets the flag and admits the order only where the flag is true. And no constructor takes the label and the flag as separate arguments — the four-argument constructor is gone, replaced by one per rung (`OrderPartner(DrugReference)` for the entry rung, `OrderPartner(ActiveDrugOrder, String)` for the order rung and issue #118's bare-code rung). Each derives the label, the flag and the name source itself — **not** all from one source, which an earlier draft of this bullet said and which is false of the order rung: it takes the order AND the code, and the label is the code whenever the display is blank. What is load-bearing is narrower and is the second leg: no caller supplies the label and the flag independently. With a caller-supplied pair a future caller could honour `namingOrder != null => namesADrug` and still pass a label it had not taken from that order, whereupon the order branch validates the RULE against that order and hands out the other label — the mis-attribution that branch exists to refuse. Reasoned, not measured: the constructor that would have allowed it no longer exists. The invariant *a non-null `namingOrder` means the label IS a name* therefore holds at every statement boundary.

Three things this deliberately is not.

- **Not a gate on the constructor's argument**, which is the shape the issue proposed. It would establish the implication in today's code — `nameByOrder`'s two writes are a consistent pair — but it would not ENFORCE it: `namingOrder` is not final and `nameByOrder` assigns it, so a constructor gate binds constructor callers and leaves the second write site unbound. "It does not hold" is what an earlier draft of this bullet said and is too strong.
- **Not `displayNamesADrug` re-asked inside `recordNameSource`.** At the ladder's own call site the predicate and the flag are equal, so that form compiles, reads more like CLAUDE.md's "ask it of yourself" rule, and leaves the suite green — verified. It is rejected because it would leave `namingOrder` decided by the ORDER while `namesADrug` is decided by whoever passed it: two sources for one fact, which is the defect this decision removes. An earlier draft justified it by a harm that cannot occur — "a caller passing `false` beside a real display would keep a non-null order, and the fold would hand that caller's label, which on this rung can be a bare ATC code, to both sentences". On the order rung those clauses are mutually exclusive: a real display makes the label that display, and the label is not a NAME precisely when `displayNamesADrug` is false — a bare code on the blank-display and #118 rungs, the `[ATC …]` stand-in for a `namedByCodesOnly` order, whose display is non-blank so "bare code" would be too narrow, in which case the re-asking form nulls the order exactly as the chosen form does. The reachable version of that harm belongs to a future rung that writes `label` itself, which is the residue recorded below. Note the object DOES ask the predicate of itself — in the order-rung constructor; what must not ask it is this method, which derives.
- **Not extended to `label`.** On the order rung a label that is a bare code is CORRECT precisely when `namesADrug` is false, so folding it into the pair would have to reject the state the ladder's last rung exists to express. What keeps the label and the flag in step instead is the second leg above — each constructor computes both itself, from its own arguments, so no caller supplies them independently.

**And `foldedPartnerLabel`'s branch order is deliberately unchanged: `!namesADrug` is still asked first.** So this decision ADDS a guard and retires none. The two are independent and reach the same conclusion from different premises — the statement order makes an inconsistent pair harmless whatever produced it, the write path makes such a pair unconstructible — and Decision 39's condition list and `foldedPartnerLabel`'s `<ol>` are both still in code order, so nothing here needs renumbering.

**The reversal was implemented and then reverted, which is worth recording because the argument for it looked sound.** The reasoning was that with the branches as they are the write path changes nothing observable through the public pipeline (measured: the whole api suite, 0 failures, no expectation moved), so reversing them was what would make the invariant load-bearing and therefore testable at all. Review refuted both halves. The cost: reversed, an inconsistent pair reaches `namesNamingOrder` and can hand a bare ATC code to BOTH sentences of a folded chip — through `DrugReferenceInjector.renderFinding` into the prompt as citable `safety_finding` text — so the reversal spends a working defence-in-depth guard to buy coverage. And the coverage argument is false, because a behaviour-neutral rule can be pinned STRUCTURALLY: this repo already does exactly that in `ChartSearchAiReferenceGroundingWithholdingTest`, which reads every class file the controller compiles to and fails the build on a hardcoded resource-type name, "precisely because no behavioural assertion can see the rule it guards". So the write path is pinned the same way — see below — and the branch order stays.

**How it is pinned.** `OrderPartnerNameSourceWritePathTest` (api, `…reference`) scans `DrugSafetyValidator.java`'s own source, with comments and string literals blanked out, and fails the build on two things: an assignment to `namingOrder` or `namesADrug` anywhere but inside `recordNameSource` (or a third assignment added beside the two expected ones, wherever it sits), and either of `recordNameSource`'s two statements assigning anything but the expression it must be — `namesADrug ? order : null` for the order, the parameter itself for the flag, compared with whitespace removed and nothing else normalized. The SOURCE and not the class files, unlike the precedent: that one's needle is a string constant, which javac inlines into the constant pool, whereas this needle is the LOCATION of an assignment — which method it sits in — and a class file answers that only through a bytecode parser this module does not have on its test classpath. `ArchitectureGuardTest` already establishes source scanning as the second structural mechanism here. Proved by mutation rather than by reasoning: adding a write in `nameByOrder` reddens the count assertion, which names the offending line; MOVING the `namingOrder` write out of `recordNameSource` while keeping the count at two reddens both the location assertion and the gate case's exactly-one precondition; and each of `this.namingOrder = order`, `order != null || namesADrug ? order : null`, `namesADrug || true ? order : null`, the inverted `namesADrug ? null : order` and a flag write that ignores its parameter (`this.namesADrug = true`) reddens a shape assertion. **A token check is not enough, and the first version of this guard used one.** It asserted only that the recorded order's expression NAMED `namesADrug`, which the second and third of those five satisfy while meaning `this.namingOrder = order` for every non-null order — the pre-#298 state, restored with a green build. Measured in round 2 of the PR's review, on `38b5b508`: api 1350 tests, 0 failures, for both. Which shape each channel catches is in the trade-offs below and in the guard's own javadoc; the short version is that the three shapes making the recorded order unconditional are seen by that class and by nothing else.

### Trade-offs

- **+** The value answers the question. A rung added later may read `namingOrder` without having to know which order `foldedPartnerLabel` asks its branches in, which is what the issue asked for.
- **+** Both fields are assigned in exactly one place, and that is now **checked by the build** rather than stated in a javadoc — which is the part the first implementation of this decision could not offer, and the reason it reached for the branch reversal instead.
- **+** The two guards are independent and additive. An inconsistent pair is unconstructible AND harmless if one were constructed. Neither was given up for the other.
- **−** The structural guard's own limits, stated so nobody has to rediscover them: it does not see assignment by reflection, nor a value smuggled in by a form its pattern does not describe; and it is a statement about the source as WRITTEN, so renaming either field — or `recordNameSource`'s `order` parameter, or re-expressing either statement in a form that means the same thing — means updating its needles. That failure it makes loud, since it asserts it located both declarations, the `OrderPartner` body and `recordNameSource`'s body before asserting anything about the assignments. Its scope is one file, which is the whole scope the compiler leaves open: both fields are private members of a private nested class.
- **−** **Which channel catches which shape, since an earlier form of this bullet credited one with the other's coverage.** It said the guard "says nothing about whether the gate is the right way ROUND" and that such a shape "is caught behaviourally instead" — true of the INVERTED gate and false of an ungated one, which is the half that mattered, because the ungated shapes are the reachable slip. Measured by mutation on `38b5b508` with the shape assertions in place, each shape written alone and the whole build run: `this.namingOrder = order`, `order != null || namesADrug ? order : null` and `namesADrug || true ? order : null` each produce exactly ONE failure, the guard's — nothing behavioural sees them. The token check the shape assertion replaced caught only the first of those three; the other two passed it, so two ways of writing the pre-#298 state were green until round 2. The inverted `namesADrug ? null : order` reddens the guard AND `ClassChipPartnerLabelTest.anOrderTheDatasetDoesNotCoverIsNamedByItsOwnDisplayName` and `FoldedChipOnePartnerNameTest.anOrderSuppliedNameTheRulesTokenDoesNotNameIsNeverHandedToTheRuleSentence`, because an order-rung partner then carries no naming order and the fold refuses where it should reconcile. `this.namesADrug = true` reddens the guard and four `FoldedChipOnePartnerNameTest` cases. So the guard is the only channel for the three unconditional-order shapes, and merely the first and most legible for the other two.
- **−** **The residue is semantic, and it is a CALL rather than an assignment — this is the "reachable version of that harm" the second *not* bullet above defers to.** A rung added later that writes `label` itself and then calls `recordNameSource(order, true)` for an order that label was NOT taken from writes a pair that is internally consistent and false: `foldedPartnerLabel`'s order branch then validates the RULE against that order and hands out the other label, the mis-attribution that branch exists to refuse. The guard inspects assignments, not calls, so it cannot see it; the second leg of the decision above bounds it rather than closing it — no constructor takes the label and the flag independently, and the three existing call sites each derive the label from their own arguments. `nameByOrder` is the working template for such a rung (it sets `label` from an order and then records that same order), which is what makes the shape easy to copy correctly and easy to copy wrongly. Nothing in this repo pins it.
- **−** Nothing BEHAVIOURAL pins the write path, and that is not a gap this decision can close — the change is behaviour-neutral by construction, which is why the issue predicted that no expectation would move, and none did. `FoldedChipOnePartnerNameTest.aBlankDisplayWithNamesNeverHandsItsCodeToBothSentences` was added believing it pinned the gate, and it does not: under the pre-#298 mutation — `recordNameSource` admitting the order unconditionally — it PASSES, because `!namesADrug` is reached first. Measured by applying that mutation: `FoldedChipOnePartnerNameTest`, `ClassChipPartnerLabelTest` and `NamelessActiveOrderPartnerTest` are all green under it. It is kept because what it *does* pin was pinned by nothing before — on the order rung a blank display makes the label a bare code, and that code must reach NEITHER sentence — and because the sweep's accompanying `ATC_CODE_SHAPED` assertion is a real strengthening: measured by making the `!namesADrug` branch hand out `OrderPartner.label` wherever that label is not an `[ATC …]` list, a code-for-name substitution on that arrangement alone, the shape assertion and the byte-exact case both redden while the sweep's one-name count stays silent.
- **−** **This change removes the only behavioural coverage of the FIRST guard, and nothing replaces it.** Reversing `foldedPartnerLabel`'s first two branches reddens three named cases on `main` — the three the ticket itself cites — and reddens NOTHING here, because the state that reversal endangers (`namesADrug` false beside a non-null `namingOrder`) is exactly what `recordNameSource` now makes unconstructible. So the branch order survives on javadoc and on this decision, not on a test. Measured both ways with the same textual swap: `main` 1347 tests / 3 failures, this head 1350 / 0. **The same measurement cuts the other way and is why the reversal was reverted rather than merely disliked**: on this head reversing those branches buys ZERO coverage, so nothing was traded away by keeping them.
- **Considered and declined: an immutable `OrderPartner`.** Make `label`, `namingOrder` and `namesADrug` final, have `nameByOrder` return a NEW partner through a copy constructor sharing the `codes` and `substances` sets, and have its one call site re-`put` the result — `identity` is in scope there, and the only escape of the reference is the terminal `new ArrayList<>(byIdentity.values())`. javac would then enforce the single write path and the source scan would be unnecessary. Declined as wider than issue #298: it changes `OrderPartner` from an accumulating object to an immutable one, which is its own review surface, and the accumulation across loop iterations is what `codes`/`substances` depend on. What declining costs is stated rather than waved away — every future rename of those two fields or of `recordNameSource`'s `order` parameter, and any rewording of either statement, fails the build until the scan's needles are edited by hand. The scan's assertion message says so at the point of failure. Worth revisiting if that cost is ever paid twice.
- **Considered and declined: deriving `namesADrug`.** Post-change the flag equals `namingOrder != null || labelEntry != null` at every write site, and review measured the derivation green over the whole suite — so it would remove a field and a parameter. Declined on two grounds. It changes what the flag MEANS, from a property of the LABEL (which is what its own javadoc has said since #292) to a function of the label's SOURCE. And its stated benefit is false: with one field there is no pair to disagree, but the property that matters — when the fold hands out `label`, `label` is a name — still rests on only ever storing a naming order, so a rung writing `namingOrder` directly would yield a derived `true`. That residue is now what the structural guard covers; the derivation would buy a field and remove the thing the guard watches.

## Decision 41: A composite claim's negative says nothing about the citation

**Status: Accepted** (August 2026) — implemented, issue [#284](https://github.com/openmrs/openmrs-module-chartsearchai/issues/284). Amends [Decision 25](#decision-25-citation-grounding-tier-1-cosine--tier-2-entailment), whose "Tier-2 is authoritative" rule this qualifies. Changes one published verdict class: a chart citation whose graded statement also rests on module-supplied reference material renders `null` where it rendered `false`.

### Context

`CitationGroundingVerifier` grades one citation by asking whether THAT record entails the statement it is attached to. The question presupposes that one record wholly supports the statement.

A drug-safety answer breaks the presupposition by design. Its claim rests on two records — the chart record for the co-medication or the allergen, and the module's own `safety_finding` for the RELATIONSHIP between them — and the module asks for exactly that shape: `LlmProvider`'s safety few-shot demonstrates a composite claim citing both. So no single record entails the statement, a correct judge answers "no" for the chart half **by construction**, and Tier-2 being authoritative published `grounded=false` on a `group=chart` citation, which a client renders as *Unsupported*, in red, on the correct record.

Measured live on the 3.7.1 standalone in two shapes, both reported on the issue with provenance: an enumerating answer whose chart citation is inline (`Gentamicin interacts with active order lidocaine [4], a Minor problem [239]` — `[4]` is Betty Williams' Lidocaine order, the right record to cite), and a one-sentence answer whose chart citation appears only in the structured `citations` array (`[11]`, the Aspirin allergy, beside a cross-reactivity finding). The control on the same box is the load-bearing half: asked "what allergies does she have?", that same allergy record grounds `true`. The record is not unverifiable; the PAIRING is.

Two directions were closed before this one was chosen, both on the issue and both by measurement rather than by argument. **Narrowing the statement does not help**: `splitEnumeration` already claims that sentence per item in either mode, and the minimal item still asserts the interaction, because the chart record supplies the co-medication and the finding supplies the relation. The composition is semantic, not syntactic, so this is not another instance of Decision 25's wrong-sized-claim rule ([#278](https://github.com/openmrs/openmrs-module-chartsearchai/issues/278)) to be fixed by cutting further — and clause scoping is a structural no-op here, since `splitIntoClauseScopedSentences` passes single-citation units straight through. **Withholding an uncited citation's verdict** would have fixed the second shape and moved the first, whose marker is present.

### Decision

**Where a chart citation's claim statement rests on module-supplied reference material, a Tier-2 NEGATIVE is withheld: the citation renders `null` ("could not verify"), the distinction [#201](https://github.com/openmrs/openmrs-module-chartsearchai/issues/201) was filed over.** What the rule does and does not reach:

- **Only the negative.** The composition guarantees the "no". It does not guarantee the "yes", so a positive verdict still verifies the citation — the check the demote-only carve-out is deliberately not extended to for `active_drug_order` (the record #118 injected for reconciliation, kept gradable by the carve-out comment in `verify`), and the reason a composite citation is not simply made demote-only. This is the mirror of the demote-only carve-out rather than a second instance of it: **in each case the verdict the pairing cannot make informative is the one withheld.** For a recitation of reference prose, lexical containment makes the "yes" uninformative — Decision 25 measured 4 role-swapped recitations judged entailed **and the one faithful recitation judged not** ([#106](https://github.com/openmrs/openmrs-module-chartsearchai/issues/106)), which is why "uninformative" is the right word there and "guaranteed" is not — so the yes is withheld and the no kept. Here it is the missing half that makes the "no" uninformative, and it is stronger: the record cannot carry the relationship at all, whatever it says. And the reason for keeping the "yes" is NOT that it is informative on a genuinely composite statement — by the premise above it cannot be, since a correct judge answers "no" there by construction, so a "yes" is a judge error and publishing it is the false-assurance direction #106 guards. It is kept because the TRIGGER is a proxy that over-fires: the co-citation test cannot tell a genuinely composite statement from a sibling enumeration item, or from a chart citation in an answer that merely carries an unanchored finding, and on those a "yes" is a real verification that withholding would throw away. The withheld negative is therefore paid for by the shapes the proxy is right about, and the kept positive by the shapes it is wrong about.
- **Only where a judge spoke.** With no Tier-2 verdict — entailment disabled, cap overflow, engine failure — there is nothing guaranteed to withhold, so Tier-1-only mode is unchanged, off-topic `false` included. Every measurement on #284 was taken with `entailment.enabled=true`, so no claim is made here about what a cosine floor does to a composite statement.
- **Asked of the whole answer where the pairing was GUESSED**, for the reason set out below.
- **Membership is the existing classification.** The set is the `demoteOnlyIndexes` `verify` already derives through `ChartSearchAiUtils.isGroundingDemoteOnly`; no resource type is compared, which is the mistake [#122](https://github.com/openmrs/openmrs-module-chartsearchai/issues/122) exists to stop being made a second time. A reference type added later inherits the rule.

**What the statement rests on is carried, not re-parsed.** `Sentence` gains `sourceCitedIndexes` — every citation of the sentence a unit was split from — because a fragment cannot answer the question about itself: an enumeration item's own set is a singleton by construction, so the co-citation that makes its claim composite is invisible from the item alone. Both splitters pass the parent's set down. `Tier1Result.claimRestsOn` then records what the SELECTED statement rests on, so Pass 2 reads a decision the selection already made.

**For a GUESSED pairing the question is asked of the whole answer.** Where no sentence cited the record inline, the statement is chosen by cosine argmax over every sentence, so reading only the picked sentence's citations would make the rule turn on which sentence the argmax landed on — the same answer written as two sentences instead of one would grade differently. The issue's own comment names that fragility class ("a wording detail, deciding which of two grounding paths runs"). So `claimRestsOn` is every citation in the answer on that path.

### Trade-offs

- **+** Both live shapes are closed by one rule, and the model's wording does not decide it: either side of the pairing may be marked up inline or left to the structured array, and the sentence may be split per enumeration item, left whole, or cut into clause-scoped prefixes. `CitationGroundingVerifierTest`'s `compositeClaim_*` cases cover those splitter outcomes across both pairings; delete the Pass-2 block and read which of them redden, rather than trusting a count here.
- **+** No wire change and no new classification. `groundedForWire` publishes a chart verdict as-is, so `null` renders as unverified, which clients are already instructed to treat as "not verified" and never as verified.
- **−** **The mis-attribution flag has no counterpart here.** A chart citation the model attached to the WRONG record inside a safety sentence now renders unverified instead of unsupported. #122 kept exactly that signal for reference citations ("a FALSE verdict here … is a statement about the CITATION"), and this decision does not keep it for chart citations in composite sentences. Two things bound the loss rather than excuse it: the signal #122 kept is module-internal, since #201 withholds every reference-group verdict from the wire, so keeping ours is not the same trade the analogy suggests; and faithfulness of this answer class is checked deterministically by the `DrugSafetyValidator` chips and `ClassCodeFidelityCheck`, which is #106's own remedy for material a semantic check cannot grade.
- **−** **The alternative that keeps it was rejected on a citation, not on taste.** Falling back to Tier-1 after withholding — cosine pass renders `null`, cosine fail still flags — keeps the off-topic net and was the shape this change had at plan stage. It makes the fix a function of `chartsearchai.grounding.minCosine`, whose own global-property text says the shipped `0.40` "is far too low for e5 — set it to ~0.82 on an e5 querystore deployment", and the verifier embeds with querystore's model. At the advised floor a composite statement — which carries the other record's words as well as its own — is predicted to fall below it and re-publish `false`, returning #284 for any operator who followed the advice. Predicted, not measured: under entailment a single-candidate claim defers Tier-1 entirely, so no cosine was ever computed for the ticket's cells at any floor. A fix whose efficacy is a function of a GP the module tells you to raise is not a fix.
- **−** **The residual is the same GP dependency, on the path where no judge answered.** A composite claim whose Tier-2 check produced no verdict — cap overflow, engine failure, an unparseable reply — falls to Tier-1 as it always did and can publish `false`, more often at the advised floor. That follows from "only where a judge spoke" and is the price of leaving Tier-1-only deployments untouched; it is narrower than the rejected alternative, which would have put every withheld negative on that path rather than only the ones the judge never reached.
- **−** A composite chart citation still spends a Tier-2 cap slot and a round-trip for a verdict usable in one direction. Decision 25 skips the pair entirely for reference prose partly to protect that cap; here skipping would also lose the "yes", which the first property above exists to keep. Bounded by the existing per-answer cap.
- **−** **The narrower option was to admit an unanchored citation only where the CHART side is the unanchored one**, and it was declined rather than overlooked. It closes both of #284's measured shapes exactly as this one does, because in the second shape the chart citation IS the guessed side; what it gives up is the mirror shape, an inline chart citation beside a finding the model left to the structured array. Round 1's reviewer measured what the wider rule costs for that: an answer of two independent sentences about two drugs, with one unanchored finding, publishes `null` for BOTH chart citations though neither sentence names the finding. So an unmeasured shape is bought at a measured cost, and that is the honest statement of the trade. Taken anyway, because the alternative is to treat the two sides of one pairing differently on no principle: an unanchored citation is one the model declined to attach to any statement, the module is in the same epistemic position whichever side it sits on, and it has already resolved that position in the withholding direction for the chart side. Asymmetry there would need its own evidence, and there is none either way.
- **−** **Reading an unanchored citation as evidence for the whole answer accepts a false-negative class**, stated because the mechanical necessity is not the whole argument. It cuts both ways. A citations-array-only CHART citation has its Tier-2 negative discarded whenever the answer cites reference material anywhere, even where the guessed statement has nothing to do with the finding — close to the issue's own first fix direction, restricted to answers containing reference material. And an unanchored REFERENCE citation makes every claim in that answer composite, including chart claims that plainly rest on their own record. Both follow from the same fact: an unanchored citation says nothing about where it belongs, so the only honest reading is "somewhere in this answer". Where the model does mark a citation up, the rule stays narrow — a chart citation whose own GRADED STATEMENT anchors no reference material keeps its denial, which `compositeClaim_aChartCitationWhoseOwnClaimRestsOnNoFindingIsStillFlagged` pins because without it the whole mechanism can be replaced by "does this answer cite reference material anywhere" with the suite green. Read "graded statement" and not "sentence": an enumeration ITEM is graded on its own text but rests on its parent sentence's citations, so one item citing a finding withholds every sibling item's negative. That is the same proxy limitation one level down, and it is not separable — the item shape that must be withheld (the gentamicin claim, whose relationship comes from the sibling item) and the one that need not be (a recorded-allergy list beside a finding) are syntactically identical.
- **−** **The co-citation is a proxy, and a sharper test exists in the module.** Whether the finding actually names THIS chart record is decidable for an INJECTED record — `DrugSafetyValidator.activeOrderEntryFor` and `DrugReferenceInjector.onePerPartner` key on the resolved active-order entry. The proxy is preferred because the verifier sees only `RecordMapping` (index, type, text) with no link back to the validator, and because the ticket's own `[4]` is a RETRIEVED `drug_order` that no injector resolved, which a provenance test would miss. Cheaper, and wider in the one direction that matters — not "undecidable", which an earlier draft of this claimed.
- **−** **Issue #294 is narrowed, not closed.** A codes-only `active_drug_order` record cited in a plain medication sentence with no co-cited finding still publishes `false`; the same record cited in a safety answer now renders `null`.

## Decision 42: A recorded clause needs corroboration, not just a match

**Status: Accepted** (August 2026) — implemented, issue [#269](https://github.com/openmrs/openmrs-module-chartsearchai/issues/269). Extends [Decision 30](#decision-30-one-chip-per-substance--the-contraindication-ledger-and-its-collapse-key), whose #223 tail left this half open by name. Adds a third section to the injected `drug_reference` record's patient-specific reading; no chip, no wire field and no clause list changes.

### Context

Decision 30's #223 tail demoted the CHIP a bare-containment match raises: a curated allergy rule whose token is one of its own entry's drug names, matched only because drug names nest, can no longer displace the sentence the allergen arm raised. It says in its own last line that the injected record's `Recorded for this patient:` half was left following bare containment.

That half is the sharper one. A demoted chip is still shown but no longer displaces a true statement; a record clause has nothing displacing it, and since [#110](https://github.com/openmrs/openmrs-module-chartsearchai/issues/110) an injected record is citable evidence the answer is *invited* to assert. For a patient whose only recorded allergy is `Tiotropium`, the Opium record read `Recorded for this patient: documented opium allergy` — a statement about a chart that records a tiotropium allergy. Same asymmetry as [#237](https://github.com/openmrs/openmrs-module-chartsearchai/issues/237) and [#259](https://github.com/openmrs/openmrs-module-chartsearchai/issues/259), chip and record answering one question from different evidence, reaching the STRENGTH of a claim rather than which row it names.

### Decision

**A matched self-named allergy rule's clause is stated as the chart's own reading only where something CORROBORATES the match, and the gate is the UNION of two questions.** Either an allergy record the rule matched NAMES the entry (`DrugSafetyValidator.aMatchedRecordNamesTheEntry` — the chip rank's own predicate, extracted and named), or the allergen arm reads some recorded allergy as an allergy to the entry's substance (`DrugSafetyValidator.allergicSubstanceKeys` — that arm's own identity question, asked over the whole allergy list). Where neither holds the clause goes to a third named section, `Matched in this patient's chart but not corroborated as a record of this drug:`, which asserts no contraindication and denies none. It does speak ABOUT the chart, which is what puts it on the `drugSafety` switches' side of `render`'s divide along with the two sections beside it. **That lead states what the module established and deliberately not a categorical about the chart** — an earlier wording said "not by a recorded allergy to this drug", and a probe refuted it: an entry aliasing `ketoconazole` and ruling on another of its own names, beside an allergy recorded as `Ketoconazole` that `matchesDrugName` accepts, is a clause both legs miss (the first sees only this rule's witnesses, the second is narrowed away) and the record denied an allergy the chart holds.

**Neither half alone is right, and they fail in opposite directions.** This is the part that is not obvious and it is why the union rather than the simpler rule the ticket proposed:

* **The rank's predicate alone UNDERSTATES.** It is per WITNESS of the rule that fired, and `papaveretum` does not contain `opium` — so for a patient allergic to papaveretum and, separately, to tiotropium, `allergensMatching("opium")` is `[tiotropium]` while `findImpliedSubstances("Papaveretum")` is `[Opium]` and the allergen arm chips the identity sentence. Hedging there would understate a real allergy in citable evidence, which is the defect with its sign flipped. That is the ticket's own suggested fix ("the same predicate the chip now uses"), refuted at plan time by measurement.
* **The allergen arm's set alone OVERSTATES.** `findImpliedSubstances` admits equal claimants only at the strongest claimant's rank, so an allergy recorded as `Ketoconazole` reaches the entry CALLED that and not one merely aliasing it — and a self-named rule on the aliasing entry keeps the FULL `SELF_NAMED_RULE` chip rank while the record would hedge it. That is the cost `contraindicationRank`'s javadoc already records against swapping its own predicate for this one ("demotes a rule filed on an entry publishing a borrowed alias"), reached from the other side.

The union is **monotone**, which is the whole argument for it: it can hedge nothing either half admits, so it can neither understate a recorded allergy nor disagree with a chip standing at full rank. Asked in cost order, the context-only predicate first, so the dataset sweep happens only where it fails.

**Scoped to a self-named ALLERGY rule**, exactly as the chip's demotion is, and load-bearing rather than incidental. A rule whose token is not one of its entry's names is asking about a class or about a fragment of free text — which is what the bare match exists for — and neither corroborating question can speak to it. The shipped seed's `nsaid` rule is such a rule and the allergen arm resolves nothing at all from an allergy recorded as `NSAIDs`, so an unscoped reading hedges a correct clause; mutate the scope out and read the failures.

**Marked, not denied and not dropped.** A denial would be TRUE under this gate, so refusing it is a judgement: the same injection still carries the demoted chip as a `safety_finding` asserting the contraindication with `STRENGTH_WITHHOLD` — deliberately, since the arms fire on different evidence and this one was never gated on the other — so `Not recorded for this patient: documented opium allergy` beside it would be two citable records of one chart in flat contradiction, with #110's design meaning the model may cite either. Dropping it is refused by `DrugReferenceInjector.render`'s own two live-measured failures of naming some clauses and leaving the rest to inference. The ticket asked for "omitted or marked as uncorroborated"; a denial is neither of its options.

**Precedence on an identical clause string across keys**: recorded, then uncorroborated, then not-recorded. Extending the existing rule ("whichever section is true of the string keeps it, and the recorded one is the one that can be true") — of the two that remain only the DENIAL can be false, so it yields.

**Per collapsed key, resolved as a MAX.** `contraindicationFinding` keys a self-named allergy rule on the SUBSTANCE ([#146](https://github.com/openmrs/openmrs-module-chartsearchai/issues/146)), so two such rules of one entry under different tokens are ONE clause while the naming question reads each rule's own token — they can disagree, and one corroborated rule of the key carries the key. Not a formality: reversing the two branches reddens a case.

**One value, and it is handed nothing but the chart.** `render` takes a `ContraindicationReading` in place of its `boolean patientReading` — and no chart parameter and no age either, since a second source for either lets one record's dose bands and its patient reading describe different patients. The reading carries whether it may be stated, the chart it is about, and the allergic-substance key set; everything but the chart is DERIVED on the object rather than supplied. That is [#298](https://github.com/openmrs/openmrs-module-chartsearchai/issues/298)'s discipline, whose own words are that "no constructor takes the label and the flag as separate arguments", and deriving is what lets it hold without the structural guard #298 needed.

**Reaching that took three goes, and the one that mattered was found by a reviewer CONSTRUCTING the pair rather than reading for it — which is the transferable part.** Version one derived the set and took the SERVICE, a shortfall visible in the signature: those keys are `substanceGroupKey()` values, which for an entry publishing no substance name is the row itself, so a set resolved from a different `DrugReferenceService` than the rendered entries came from contains nothing the caller can find and every self-named allergy rule reads as uncorroborated. Version two made the class an INNER class, deriving the service from the injector's own field, and still took the FLAG — the worse half, and the one nothing but construction would have shown: `new ContraindicationReading(true, null)` renders *"Not recorded for this patient: documented opium allergy"*, a denial about a chart nobody read, i.e. [#208](https://github.com/openmrs/openmrs-module-chartsearchai/issues/208) item 2 with the sign flipped. Version three asks `statesTheChartsContraindicationReading` of itself. Between them the two versions carried the word "unconstructible" while the pair was constructible, which is the specific way a structural claim rots: it reads as a property of the design and is a property of the one call site. Resolved lazily and memoised on that object, a per-call object and never a field on the singleton bean ([#172](https://github.com/openmrs/openmrs-module-chartsearchai/issues/172)).

### Trade-offs

- **+** The record no longer states as this patient's chart's own reading a clause nothing in the chart supports, and it says so where a model reads it rather than by falling silent.
- **+** The chip's rank is byte-identical: its witness loop was extracted, not changed. The extraction also gives that question a name, which the rank alone could never expose — it folds the answer onto a value it shares with the blank-note disqualification, so nothing behavioural could tell the two apart. The record's cases now pin it.
- **+** Reachability is curated-source-only by construction: neither `DdiDrugReferenceSource` nor `AtcDrugReferenceSource` publishes a contraindication field at all. Measured through those parsers and `DrugSafetyValidator.selfNamedAllergyRule` rather than read off the schema, and over the dataset that actually ships rather than a fixture: **0 contraindication rules over the 2283 entries the real `DdiDrugReferenceSource` parses from the bundled 19 MB knowledge base**, 0 over the ATC sample's 6, against 10 rules on the curated seed's 4 entries, 3 of them self-named allergy rules (`ibuprofen`, `paracetamol`, `amoxicillin`). So no bundled `ddinter` or `atc` load renders a third section, and the lazy resolution means such a load never resolves the set either.
- **−** **The `safety_finding` channel still asserts the rule's own sentence, with `STRENGTH_WITHHOLD`.** Deliberate and out of this slice: the chip is raised because the arms fire on independent evidence, which Decision 30 settled and #269 does not reopen — its own residue paragraph treats a demoted note reaching NO channel as a loss. So the prompt carries a finding asserting the contraindication beside a record saying nothing corroborates it. That is a qualification rather than a contradiction, and it is the reason the third section is not a denial; whether the finding itself should state its provenance is a separate decision on separate evidence. **Taken, on evidence this decision did not have — [Decision 44](#decision-44-a-finding-says-how-its-rule-reached-the-chart), issue [#308](https://github.com/openmrs/openmrs-module-chartsearchai/issues/308).** Measured live afterwards, the model answers from the finding and never surfaces this section: a qualification reaching one of two citable records of one fact changes no answer. The finding now states the same provenance. Its CALL is unchanged, so everything above still holds.
- **−** **The section's exact WORDING is unmeasured.** `render` records two live-measured failures at this very site, so measurement is the precedent here and this lead does not have it. What is argued is narrower: both measured failures were "name some clauses and leave the rest to inference", and a three-way partition still names every clause on a side. The lead also claims no MECHANISM for the failure, because either corroborating question can fail for reasons that are not a mid-word accident.
- **−** **The residue #269 states remains open in the other direction.** Where a drug is named only by the ANSWER, `matchingEntries` injects no `drug_reference` record at all, so an uncorroborated note reaches the clinician through no channel — not the chip, not the record. Correct in itself, and it means the two surfaces still differ depending on how the drug entered the response.
- **−** **A `condition` rule matched by bare containment is untouched.** `selfNamedAllergyRule` is allergy-typed and so is the chip's demotion, and there is no allergen-arm analogue to corroborate a condition against. Widening it is a decision with no measurement behind it.


## Decision 43: A substance is named by the row the data files it under

**Status: Accepted** (August 2026) — implemented, issue [#250](https://github.com/openmrs/openmrs-module-chartsearchai/issues/250). Adds a second rung to `DrugReference.canonicalRow`, the fold [Decision 30](#decision-30-one-chip-per-substance--the-contraindication-ledger-and-its-collapse-key) and issues #162/#163/#174/#194/#206 all read. No new call site and no wire-format change; the chip arms are untouched, though the subject they name moves for three substances.

### Context

`canonicalRow` decides which row of a substance every surface names it by — the chip's subject, the injected record's title and the class arm's partner label. It had ONE rung — prefer the row that answers `namesNoRoute()` — and a fallback to the earliest row seen. (That counting matters, because the issue asks for "a third rung": the fallback is not one, so what this adds is the SECOND, which is also why it must not go above the first.)

`namesNoRoute()` is true of any display name carrying no trailing parenthesised qualifier, so it ties on any family holding two such rows, and dataset order then answered. On the shipped knowledge base the row that wins that race for the estradiol substance is `Fluoroestradiol f-18` — a diagnostic PET tracer, at index 1282 against `Estradiol` at 1927, merged into that family because DDInter gives it no `drugbank_id` (the merge itself is [#249](https://github.com/openmrs/openmrs-module-chartsearchai/pull/249)'s load-time finding and Decision 36's upstream handoff, deliberately unrepaired).

The consequence is not confined to the chip. `DrugReferenceInjector.renderFinding` carries a chip's detail into the prompt verbatim, so the tracer became the answer's subject. Live on a 3.7.1 standalone with the stock dataset, *"Is it safe to give her estradiol?"* was answered:

> No — **Fluoroestradiol f-18** should not be given: it interacts with active order methylprednisolone, a Moderate problem. …

over five interaction chips every one of which was subjected on the tracer, each carrying an oestrogen's mechanism prose, and with the word `estradiol` appearing nowhere in the response except inside `Fluoroestradiol`. The clinician cannot tell from the output that the module answered about a different drug, because the substitution happens before the answer is written.

### Decision

**Among rows of ONE substance that tie on `namesNoRoute()`, the row whose display name IS the name the data files the family under wins** — `DrugReference.namesItsSubstance()`, comparing the full display name to `getSubstanceName()` through `normalizeName`.

**Below the first rung, not above it — and this is the decision, not the predicate.** Above it, the rung also renames the influenza A/Vietnam antigen family, whose elected row carries a display name with a dropped leading "I", and #250 lists that as one of its four. It is refused because it is the only shipped family for which that placement elects a route-qualified row while the family HAS an unqualified one, which falsifies the first rung and, with it, `DrugReferenceInjector.matchingEntries`' reason for widening its candidate set — that the fold's direction is monotone. (That bullet used to put it as *"only ever moves toward `namesNoRoute()`"*; this change rewrote it, because rung two makes the fold move **laterally** between two rows agreeing on that predicate, which is what all three of its shipped moves are. It now reads *"never moves AWAY from `namesNoRoute()`"*, which the placement above rung one would break outright.) And the elected row's own rules are what a record renders (`onePerPartner` walks its `getInteractions()`), so that placement costs that family **12 rendered interaction partners against 1 gained**. The typo is a #196 upstream data defect, which is the handoff the estradiol merge itself already takes; repairing it by re-ranking rows would be this module correcting a display name on its own authority, and it is a different defect from the ones Decision 36 enumerates — those sit in `rxnorm_name`, this one in the row's own `name`, where no validity rule sees it.

Below the first rung the direction is preserved **structurally** rather than by measurement: the first rung returns whenever the two rows disagree on it, so the second is only ever reached between rows that agree, and it therefore cannot replace an unqualified row with a qualified one.

**Compared against the WHOLE display name, not its stem**, which is the third decision and the one that looked most like a free choice. `DrugReference.displayStem` strips trailing parentheticals, and a stem comparison is strictly more permissive, so it reads as the safer reading of "is this the row the family is named after". It is wrong twice over the shipped data, and neither cost was visible from the predicate's own side. The tick-borne encephalitis family is filed under a substance name that itself carries a parenthetical, so BOTH its rows reduce to `tick-borne encephalitis vaccine` and a stem comparison separates neither — the fold falls back to dataset order and re-elects the paediatric row, giving back one of the three renames above. And no row of the shipped silver-nitrate family names its substance by its whole name, so the fold decides nothing there today; compare stems and `Silver nitrate (ophthalmic)` self-names, taking both that family and `entryForAtcCode("D08AL01")` from `Silver (topical)` — an ophthalmic presentation elected to speak for a substance, which is the shape issue #174 site 1 removed when a systemic cyclosporine order was named `Cyclosporine (ophthalmic)` in a chip about tacrolimus. Nothing caught either: the mutation left the whole api suite green until `SubstanceNameRowTest.theSubstanceNameIsMatchedAgainstTheWholeDisplayNameAndNotItsStem` and `noRouteQualifiedPresentationIsElectedToSpeakForItsSubstance` were written for it — make the mutation and read the failures rather than trusting a count here, split into two cases because folded together the first fails and JUnit never reaches the second.

**And one consequence two surfaces away needed a second change, which is the part of this decision nobody predicted.** `DrugReferenceInjector.rowAttribution` prints *"Published by this dataset for X, not for Y — the row this patient's record names"* so a model can tell the row a `drug_reference` record renders from the row every chip beside it names — issue [#237](https://github.com/openmrs/openmrs-module-chartsearchai/issues/237)'s clause. Its gate asked whether the chart had chosen the subject by comparing `interactionSubject`'s row against `canonicalRow`'s: where they agreed, no recorded name had out-claimed any other row. That is a **proxy**, and it held only while the fold could not reach the row the chart names.

This rung makes the fold reach exactly that row, so the proxy read their agreement as "the chart chose nothing" and suppressed the clause on the arrangement that needs it most. Measured over the shipped KB through the real `injectRecords`, question *"Is it safe to give her daxibotulinumtoxina?"* for a patient ordered `Botulinum toxin type A` and `Kanamycin`: the record renders `Daxibotulinumtoxina` (the only row the question resolves) while the chip names `Botulinum toxin type A`, and the reconciling sentence — printed by that same arrangement before the rung — was gone. A strict regression, with the whole suite green on both sides.

So the question is now put to the chart itself, of the row the record RENDERS: `DrugSafetyValidator.recordNamesMoreStrongly(subject, rendered, context)` — does the patient's own record claim the subject more strongly than the row published here? That is precisely what the sentence asserts, so it replaces an inference with the predicate, and unlike the proxy it cannot drift as the fold's rungs change. It is a read of `nameMatchStrength` through the same per-row step `strongestClaimants` already took (extracted as `recordedClaim`, so the two cannot come to disagree about how strongly the chart claims a row), and not a second ranking: the composition that decides a SUBJECT still lives only in `interactionSubject`. The three cases in `ReferenceRecordRowAttributionTest` that pin the clause and its silence are unchanged and green, and `SubstanceNameRowTest.theRecordStillSaysWhichRowItIsWhereTheFoldNowAgreesWithTheChart` reddens on a revert to the proxy. Three parts of that predicate had to be pinned separately, none of them by the cases above and each found by mutation: its FLOOR (bare containment is not naming — `aRowTheChartMerelyCONTAINSIsNotARowTheChartNames`), its LEVEL (an alias IS a name; no family of the shipped KB witnesses it, because rows of one substance share their aliases — measured through the real load, `substanceGroupKey` and `nameMatchStrength`, 1021 alias candidates over its 129 multi-row families and none whose strongest claim is exactly that rank while a sibling claims lower — so it is pinned over curated data by `aRowTheChartNamesByAnAliasIsARowTheChartNames` and, on the printed record, `theRecordSaysWhichRowItIsWhereTheChartNamesThatRowByAnAlias`), and its STRICTNESS (`>` and not `>=` — `aRowTheChartClaimsNoMoreStronglyThanItsSiblingIsNotARowTheChartPreferred` with `aRecordAttributesItsRowToNobodyWhereTheChartClaimsBothRowsAlike`). Raise the floor, or relax the comparison, and read those failures rather than trusting this list. Its `row == than` fast path stays pinned by nothing, for the reason stated on it: the strict comparison behind it already answers false for a row against itself.

**Gated on the two rows being one substance**, because this fold is also applied to sets that are not one: `DrugSafetyValidator.entryForAtcCode` folds every loaded row publishing one ATC code, and 30 of the KB's 2148 codes span more than one substance. Ungated, the rung renames three of those **across** substances — `A02BC05 Omeprazole` → `Esomeprazole`, `N02BF01 Gabapentin enacarbil` → `Gabapentin`, `D08AG03 Iodide I-131` → `Iodine` — every pair one `DrugReference.substanceKey`'s own javadoc names as deliberately distinct, and the first the pair `substanceKey`'s `drugbank_id` component exists to keep apart. The shipped `Omeprazole` row carries `rxnorm_name: esomeprazole`, so it does not name its own substance while `Esomeprazole` does; unscoped, the rung would rename a class chip's partner to a substance the patient is not on.

### Rejected alternative for the fourth family: correcting rung ONE, instead of reordering the rungs

The refusal above is argued structurally — with rung two above rung one the influenza A/Vietnam family elects a row `namesNoRoute()` calls qualified while the family holds one it calls unqualified — and that much is true. What it never asks is **why `namesNoRoute()` says that**. The parenthetical in `Influenza A virus A/Vietnam/1194/2004 (H5N1) antigen (formaldehyde inactivated)` is not a route or a formulation qualifier at all: it is part of the row's own `substanceName`, character for character — the same phenomenon `DrugReference.namesItsSubstance()`'s javadoc already documents for the tick-borne family, read from the other side. So there is a second route to the ticket's fourth family that leaves the rung ORDER alone: teach rung one that a row whose display name IS its own substance name carries no qualifier.

**Measured 2026-08-24** through the real `DdiDrugReferenceSource().load()` of the shipped 19 MB KB and the real `namesNoRoute`/`namesItsSubstance`/`canonicalRow` on both sides — the alternative driven by mutating `namesNoRoute()` to `normalized.equals(displayStem(name)) || namesItsSubstance()` and diffing its elections against this branch's head, rather than re-expressing either:

| | |
|---|---|
| rows `namesNoRoute()` misreads this way (`namesItsSubstance() && !namesNoRoute()`) | **4 of 2283** — the A/Vietnam and A/California influenza antigens, the Yersinia pestis 195/P antigen, the tick-borne encephalitis row |
| the A/Vietnam family, before | typo row `Nfluenza a virus a/vietnam/1194/2004 (h5n1) antigen` `noRoute=true selfNames=false` 251 rules; correctly-spelled row `noRoute=false selfNames=true` 239 rules; **elected: the typo row** |
| multi-row families whose election moves | **exactly 1 of 129** — that family, to the correctly-spelled row |
| ATC folds that move (all 2148 codes, folded as `entryForAtcCode` folds them) | **0** |
| api suite under the alternative | exactly **1** failure — mutate `namesNoRoute()` as above and read it rather than trusting a total here |

So the alternative delivers the ticket's fourth family and nothing else on the shipped data. Its single test failure is not a behavioural assertion: it is this change's own vacuity **precondition** in `SubstanceNameRowTest.routeQualificationStillOutranksNamingTheSubstance` ("at least one such family's ONLY self-naming row must carry a qualifier"). Under the alternative no shipped family discriminates the two rung placements at all, so the reorder guard becomes unwitnessable rather than falsified — which is the honest form of "it also dissolves the rung-order constraint".

**Declined here on scope, not on doubt, and the cost is named rather than waved at.** `namesNoRoute()` has a second production consumer — `DrugSafetyValidator.outranks`, which decides whose MECHANISM PROSE a chip renders where two rows of one substance rate a pair equally — and the alternative changes that predicate's answer for 4 rows. That surface was not measured for it; the api suite being green is weaker evidence than a sweep over the KB's rated pairs, and it is exactly the kind of second consumer this decision's own gate paragraph exists because of. It also pays the same 12 rendered interaction partners the refusal above cites (251 → 239), since the elected row is where a record's rules come from. What this section records is that the rung ORDER is not the only route to that family, so a later reader does not conclude that it is: as the refusal stands alone, the argument for letting a typo row speak for its family rests on a `namesNoRoute()` reading that this alternative shows to be a misreading.

### Measured

Through the real `DdiDrugReferenceSource.parse` of the shipped 19 MB knowledge base and the real `canonicalRow` **on both sides** — the baseline is that same method as it stands on `main`, driven from a second worktree, rather than a re-expression of it, which is what CLAUDE.md's measurement rule requires:

| | |
|---|---|
| multi-row families | 129 of 2283 entries |
| families changing subject | **3** — `Fluoroestradiol f-18`→`Estradiol`, `Daxibotulinumtoxina`→`Botulinum toxin type A`, paediatric tick-borne encephalitis vaccine→the row the data files that family under |
| families unchanged | 126 |
| families electing a route-qualified row | 10, unchanged — the same ten that name no unqualified row at all |
| families electing a qualified row over an existing unqualified one | 0 |
| ATC-code folds moving | 1 of 2148 (`G03CA03`, within one substance) |

All three moves are **lateral** in route terms: the first two elect a row naming no route where the incumbent did too, and the third elects one that does not where the incumbent did not either — *neither* tick-borne row is unqualified, so that rename is not a move "toward the unqualified row" and rung two does not only ever hand off to a `namesNoRoute()` row.

Rendered-record partners move with the row — counts of the elected ROW's rated partners in the dataset, not of anything a clinician sees, since the chip arm is scoped to the patient's own active orders (live-verified: five interaction chips for the ticket's patient before and after). The vaccine family gains 19 and loses none, botulinum is identical at 110, and estradiol goes from the tracer's 4 to 578 — losing `bazedoxifene` and `toremifene`, which only the tracer row rates, and rendering `ospemifene` and `tamoxifen` at the substance row's own rating rather than the tracer's. The chip arm is unaffected there because it pools every row of the substance (`bestRulePerPartner`): that pool is 580 partners before and after.

### Trade-offs

- **+** A question naming a substance is answered about that substance. The estradiol case is the sharpest available: a real, clinically important oestrogen/anticoagulant interaction was attached to a PET tracer nobody is giving, so both failure directions were bad — act on a warning about a drug not in play, or dismiss it and miss the interaction.
- **+** One decision, one place. The chip subject, the injected record's title and the class arm's partner label all read this fold, so none of them can disagree about what a substance is called.
- **−** **On the ticket's own reproduction shape the chip's partner leaves the injected record entirely**, which is sharper than a severity difference and was measured rather than reasoned. Driving the real `injectRecords` and the real `validate` over the shipped KB for a patient on `Tamoxifen 20mg` asked *"Is it safe to give her estradiol?"*: the chip reads `Estradiol interacts with active order tamoxifen — Major`, while the record reads `Drug reference — Estradiol (ATC G03CA03). Interactions: ivosidenib …; kanamycin …; ketoconazole …` — three partners the patient is not on, tamoxifen absent. Before the change the record named tamoxifen at Major, because the tracer row rates it so. Two mechanisms compose: the `Estradiol` row rates tamoxifen `Unknown`, which is below the floor so `promotable()` does not promote it, and the 592-rule row is then truncated by `MAX_INTERACTION_RENDER_CHARS`. That is the shape CLAUDE.md records as #151's symptom — "a Major chip whose supporting reference record was simply not in the prompt" — and the loss scales with the elected row's rule count, so electing the big substance row is what evicts the patient's own co-medication from the note. **Not left uncorroborated**: the `safety_finding` record carries the whole claim verbatim with `STRENGTH_WITHHOLD`, and `main`'s behaviour here was the same shape with the wrong drug named. Closing it means rendering the substance's pooled rules rather than one row's, which is a change to `DrugReferenceInjector.onePerPartner` on its own evidence.
- **−** **A chip about `Estradiol` can carry the tracer's own mechanism prose.** Measured verbatim on that same run: `Estradiol interacts with active order tamoxifen — Major. Coadministration of the radioactive diagnostic agent fluoroestradiol F 18 with drugs that block the estrogen receptor (ER), such as tamoxifen and fulvestrant, may reduce the uptake of fluoroestradiol F 18 into ER-positive tumors.` — carried into the prompt as a `safety_finding` with `STRENGTH_WITHHOLD`. This is `bestRulePerPartner`'s pre-existing pooling over every row of a substance, which `main` has too (with the wrong label as well); what makes it clinically loud here is the unrepaired #196 merge, which files a diagnostic tracer and a therapeutic substance as one. **Pinned rather than only described**, and the first version of this bullet mis-attributed that: it credited the stem guard in `SubstanceNameRowTest.theChipNamesTheRowTheDataNamesTheSubstanceAfter`, whose slice rates no rule on the tracer row at all, so that assertion has no pooled tracer prose to see and cannot observe this. What observes it is `theChipNamesTheElectedRowWhereThePooledWinningRuleIsTheTracers`, over `ddi-derivative-merged-into-one-substance.json` — the one verbatim slice that files a rated rule on the tracer (Major against `ospemifene`, against the substance row's Moderate) — which asserts both halves: the chip names `Estradiol`, and the prose under that name is the tracer's. Matching the STEM case-insensitively is still what the assertion needs, since the KB spells the tracer three ways and `contains("Fluoroestradiol f-18")` does not see `fluoroestradiol F 18`.
- **−** **The influenza A/Vietnam antigen family keeps its typo name**, for the reasons above. #250 asked for four renames and this delivers three; the fourth is a data fix.
- **−** **The gate leaves the mixed-substance fold order-sensitive**, because the second rung is skipped per pair: a row of one substance interposed between two rows of another is compared against neither on that rung. It was already order-sensitive there and this does not make it more so — re-folding each of the 2148 codes' rows reversed, and under 40 random permutations each, changes the elected row for 49 codes with the rung and 50 without it, the one removed being `G03CA03`. What would make it sound is grouping by substance before folding, in `entryForAtcCode` rather than in the fold.
- **+** The `#237` attribution clause now rests on the chart rather than on an inference about the fold. That is strictly better independently of this rung, and it is the second time a proxy at this site has had to be replaced by the question it stood in for.
- **−** **The fix was found by construction, not by the suite.** Nothing reddened when the clause disappeared, because no case paired a question resolving only the non-elected row with a chart naming the elected one. That case now exists; the general shape — a record's row and a chip's row diverging with nothing saying so — remains the residue `chartAnchoredSubject`'s own javadoc records, and is narrower than before rather than closed.
- **−** **Two existing precondition assertions asserted the pre-change fold** over the botulinum family and are corrected. That family also stops supplying the trap two `OrderedSubjectRowTest` scenarios needed — after the fix the fold reaches the charted row unaided, so neither case can fail if `interactionSubject`'s chart-anchoring step is removed. The trap moved to the `Tozinameran`/`Pfizer-BioNTech Covid-19 Vaccine` pair already in the same fixture, whose rows tie on both rungs; verified by mutation rather than assumed. `AllergenExactNameResolutionTest` was checked the same way and keeps its teeth — it reddens on a `nameMatchStrength` mutation, which is the rank it was written to pin.


## Decision 44: A finding says how its rule reached the chart

**Status: Accepted** (August 2026) — implemented, issue [#308](https://github.com/openmrs/openmrs-module-chartsearchai/issues/308). Discharges the deferral [Decision 42](#decision-42-a-recorded-clause-needs-corroboration-not-just-a-match) recorded against itself. Adds one clause to the injected `safety_finding`; no chip, no wire field, no prompt change, and no clause of STRENGTH changes.

### Context

Decision 42 gave the injected `drug_reference` record a third section for a self-named allergy rule whose match against the chart nothing corroborates — `Matched in this patient's chart but not corroborated as a record of this drug:` — and deliberately left the `safety_finding` injected beside it in the same request alone, on the grounds that the chip is raised on independent evidence and that the two sit as a qualification rather than a contradiction. Its own trade-off list said so, and named the follow-up: *"whether the finding itself should state its provenance is a separate decision on separate evidence."*

That evidence arrived. Measured live on the 3.7.1 standalone with the real local model, `sourceFormat=json`, a patient whose only relevant record is a free-text allergen `Dexibuprofen` — a real drug in which `ibuprofen` sits mid-word — asked *"Can I give him ibuprofen?"*. Both records reached the prompt; the model answered from the finding and never surfaced the section:

> No — Ibuprofen should not be given: it is contraindicated by an active allergy: documented ibuprofen allergy, this finding is a reason to withhold it [25].

So the qualification was true, correctly placed, and **inert**. Since [#110](https://github.com/openmrs/openmrs-module-chartsearchai/issues/110) an injected record is citable evidence the answer is invited to assert, and where two citable records report one fact and only one of them is qualified, the model has no reason to prefer the qualified one.

### Decision

**The finding states how its rule reached the chart, and states no less strongly what it licenses.** `DrugReferenceInjector.renderFinding` appends `FINDING_UNCORROBORATED_MATCH` between the detail and the strength clause, off `SafetyWarning.restsOnAnUncorroboratedChartMatch()`, which `DrugSafetyValidator.addContraindications` sets from the corroboration union.

**Additive, and that is the decision rather than a detail.** The obvious fix — the one the ticket lists first — is a third strength class between `STRENGTH_WITHHOLD` and `STRENGTH_CAUTION`. It was refuted before any code was written, on two things already on the record:

* Decision 42 defers **provenance**, not strength, and it records this union being **wrong in the false-negative direction**: an entry aliasing `ketoconazole` and ruling on another of its own names, beside an allergy recorded as `Ketoconazole` that `matchesDrugName` accepts, is a clause *"both legs miss"* while the chart really holds the allergy.
* [Decision 37](#decision-37-a-safety-answers-call-is-as-strong-as-the-findings-rating) measured what a contraindication finding stating no withholding clause produces on this very drug and question shape: *"No — ibuprofen should not be taken"* became *"Ibuprofen can be given, with one caution"*, **3 of 3**, with the chip byte-identical.

Weakening a refusal on a gate that can be wrong that way is fail-open in a safety net, and the two failures are not symmetric: a qualified refusal costs a clinician a second look at the chart, an unqualified permission does not. So `licensesWithholding` answers exactly as it did, `getSeverity()` is still null, and CLAUDE.md's *"one definition"* rule is honoured rather than forked. `UncorroboratedFindingProvenanceTest.theFindingStillStatesTheStrongestCallItStatedBefore` is that invariant, stated on its own so a future change that wants the call to move has to redden it and answer both decisions above.

**One spelling of the union, and the move is the point.** `DrugReferenceInjector.corroborated`'s body is now `DrugSafetyValidator.corroboratedByTheChart`, which the injector delegates to. Two channels reporting one fact about one chart is what #308 measured the cost of; a second copy of the predicate is how they would come apart again, and it would do so **silently** — nothing errors when a hedge and an assertion sit side by side, which is exactly the shape #269 fixed on one side and this one finishes. The parameter is a `Supplier<Set<Object>>` rather than a set so the injector keeps the cost order it documents (leg 1 reads only the context and the entry; leg 2's dataset sweep happens only where leg 1 fails), while `validate` hands over a set derived from the `recordedAllergens` walk it already does once per pass — through `allergicSubstanceKeys`' own list overload, so the derivation is not written twice either.

**One rule is not one chip, and the fold belongs beside the record's, not in the chip ledger.** This is the part that was got wrong three times, and every one of them was found by a reviewer CONSTRUCTING the contradicting pair rather than reading for it — on a change whose entire subject is two channels agreeing. `contraindicationFinding` keys two self-named allergy rules of one entry on the SUBSTANCE ([#146](https://github.com/openmrs/openmrs-module-chartsearchai/issues/146)), so they are one chip and one rendered clause while each rule is put to the chart on its own token, and they can disagree. The record already resolved that as a MAX (*"one corroborated rule of the key is enough for the key"*).

The first cut let the answer ride on the rule that won the ledger by RANK. That fails on a tie the rank cannot see: `contraindicationRank` answers `SELF_NAMED_RULE_WITHOUT_A_NOTE` for a blank note **without asking corroboration at all**, both disqualifications share the value 0, and the incumbent-keeps tiebreak leaves the uncorroborated rule's sentence standing. One injection then carried

```
[drug_reference]   … Recorded for this patient: documented levo allergy — ketoconazole.
[safety_finding]   … documented levo allergy. This module matched that record in this patient's
                     chart by its wording alone and could not corroborate it as a record of this drug.
```

The second cut folded it inside `ContraindicationChips`, and that unit is wrong in two independent ways. The ledger's key is the SUBSTANCE, so it spans every ROW of it while `matchingEntries` injects ONE record for the substance and renders it for `canonicalRow`'s row, whose sections state that row's rules alone — on two rule-bearing rows of one substance (the shape `DrugReferenceValidity.RULES_WITHOUT_A_SUBSTANCE_IDENTITY` actively instructs operators to author, and which the `json` source reaches because Jackson binds `substanceName` straight onto `DrugReference`) a corroborated rule on one row cleared the flag while the other row's own record went on hedging its own clause. And the `SubjectMatter` gate ([#143](https://github.com/openmrs/openmrs-module-chartsearchai/issues/143)) `continue`s a rule the response is not about **before** it can reach the ledger at all, so a corroborated rule the question does not name never carried its key — printing the same contradiction through a third door.

**So the fold is resolved in `addContraindications`, over this ENTRY's matched rules, keyed by `contraindicationFinding`, and deliberately not scoped by the subject-matter gate** — the same unit and the same fold `DrugReferenceInjector.contraindicationSections` uses, so the two answers are one fold over one partition — in BOTH of that partition's stages, per the paragraph below — rather than two mechanisms happening to coincide.

**And the record's partition has TWO stages, so the finding's fold has two.** This is the fourth way the unit was got wrong, found the same way as the other three — by a reviewer constructing the contradicting pair. After keying by `contraindicationFinding`, `contraindicationSections` resolves its three sections over clause TEXT (`uncorroborated.removeAll(recorded)`), because two rules of DIFFERENT keys may render the same string — an allergy rule and a condition rule carrying one note, which that walk's own comment calls a natural way to author *"recorded either way"* — and a record cannot both state a string as this chart's reading and hedge it. A fold that stops at the key leaves the finding hedging the identical string the record beside it asserts, which is #308's own defect inside one injection, and it is reachable on a fixture already in the repo: over `drug-reference-borrowed-alias-corroboration.json`'s Codeine entry, one recorded allergy `Dihydrocodeine` and one recorded condition `Respiratory depression`, the record read `Recorded for this patient: opioid reaction` beside a finding hedging `opioid reaction`. So `addContraindications` asks the record's own cross-key question of the record's own strings — `DrugSafetyValidator.contraindicationClauses`, one definition called by both, for the reason `contraindicationFinding` is one — and `aClauseAnotherKeyOfThisEntryStatesAsRecordedIsNotHedged` pins it; replace the whole `uncorroborated` expression with the key fold alone (`!Boolean.TRUE.equals(corroboratedClauses.get(key))`) and read the failure.

**And that stage is asked of TWO strings, which is the fifth way the unit was got wrong** — found the same way as the other four, by a reviewer constructing the contradicting pair, and one this branch's own change CREATED rather than merely failed to close: on `main` `renderFinding` appends nothing, so the pair agreed. `contraindicationClauses` JOINS the distinct notes of the rules a key collapses (`A — B`), while the sentence the ledger builds prints the winning rule's own note alone (`ChartSearchAiUtils.firstNonBlank(c.getNote(), c.getToken())`). So as soon as a collapsed key carries a second rule saying something different, the two strings differ, a guard asked only of the key's clause cannot see that another key states the finding's own words as recorded, and the finding hedges words the record beside it asserts. Reproduced through the real `JsonDrugReferenceSource.parse` and `injectRecords` over `drug-reference-collapsed-key-joined-clause.json` — a `Levoketoconazole` entry whose `levo` and `ketoconazole` allergy rules collapse onto the substance key with the notes `opioid reaction` and `other reaction`, beside a condition rule on `respiratory depression` carrying `opioid reaction`, a recorded allergy `Levocetirizine` and a recorded condition `Respiratory depression` — and pinned by `theWordsTheFindingPrintsAreNotHedgedWhereAnotherKeyStatesThemAsRecorded`. `addContraindications` therefore asks the stage of both strings; mutate either conjunct away and read the failures, which are different sets. And it asks it in the record's own NORMALISATION: `contraindicationClause(c)` is the sentence's expression *trimmed*, which is the form `contraindicationClauses` renders, so a curated note carrying surrounding whitespace still compares like with like. That was asserted in prose and pinned by nothing until review round 5 — the renormalising mutation (`contraindicationClause(c)` -> `ChartSearchAiUtils.firstNonBlank(c.getNote(), c.getToken())`) left the whole build green. The fixture's `levo` note is therefore authored padded, and under that same mutation the one case reddens on the ticket's own contradiction inside one injection: the record reading `Recorded for this patient: opioid reaction` beside a finding hedging `  opioid reaction`.

**That buys agreement about one ROW and not about one substance**, which is the residue in the trade-offs below. That gate decides which CHIPS a response may raise; whether the chart corroborates a match is a fact about the chart. Every chip then arrives carrying its own entry's answer, the ledger keeps no corroboration state at all, and the sentence that survives the rank brings that answer with it. Three cases pin the three failures — `oneCorroboratedRuleOfACollapsedKeyClearsTheClauseForTheWholeKey`, `aCorroboratedRuleOnANEIGHBOURRowDoesNotClearTheClauseThatRowsOwnRecordStates`, `aRuleTheSubjectMatterGateSKIPSStillCarriesItsClausesCorroboration` — each over a fixture authored for it. Reading the per-rule primitive at the read site instead (replace the whole `uncorroborated` expression with `!corroboratedByTheChart(ref, c, context, allergicSubstances)`) reddens the first and the third of them, alongside `aClauseAnotherKeyOfThisEntryStatesAsRecordedIsNotHedged` and `theWordsTheFindingPrintsAreNotHedgedWhereAnotherKeyStatesThemAsRecorded`; the second is reached by the cross-row fold, whose own mutation is named in the trade-off below.

**There is no third conjunct reading the key fold at that site.** `statedAsRecorded` is built from `corroboratedClauses`, and a matched rule always carries a matchable — hence non-blank — token (`PatientClinicalContext.matchableToken`), so its key always has a rendered clause and a corroborated key always contributed that clause to the set: the key-clause conjunct already answers for it. That premise is a statement about the map holding MATCHED rules only — the pre-pass's own opening `continue`, which nothing pinned: delete it, leaving `Object key = contraindicationFinding(ref, c);` as the loop's first statement, and the whole build stayed green, while the arrangement below with its recorded CONDITION taken away had the record still hedging `opioid reaction`, and stating nothing as recorded, beside a bare finding. `aRuleTheChartDoesNotRecordCannotStateItsClauseAsRecorded` is that case. One was written there and was dead — measured on the revision that carried it, replacing it with `corroboratedByTheChart(ref, c, context, allergicSubstances)` moved no case's colour — while four texts, this decision among them, prescribed exactly that mutation as the fold's guard. What the MAP's OR semantics does still decide is which rule of a key carries it: break the fold to first-rule-wins (`!corroboratedClauses.containsKey(key)`) and `oneCorroboratedRuleOfACollapsedKeyClearsTheClauseForTheWholeKey` and `aRuleTheSubjectMatterGateSKIPSStillCarriesItsClausesCorroboration` redden.

**The whole allergy list, at all three call sites.** `addActiveOrderContraindications` holds a second, NARROWED list beside the one it passes on — `allergensAskedAbout`, the records the response is about — and its subject-matter-gated branch hands that to the allergen arm. Leg 2 must not take it: it is *"that arm's own identity question, asked over the WHOLE allergy list"*, and narrowing it would report a finding as uncorroborated on the strength of the question's wording, hedging a clause a recorded allergy really does support. The narrowing decides which records may SPEAK in this response; the union decides what the chart holds.

**The wording is a sentence, not the section lead with its colon filed off**, and three properties of it are load-bearing. It NAMES its subject, so it is not a dangling participle whose implied subject is the previous sentence's object — the shape most at risk from a model that has been measured paraphrasing injected text it should copy. It OPENS by asserting that a record WAS matched, which is the negation of the antecedent of the prompt's opposite branch (*"when no record addresses the drug or intervention asked about, the whole answer is one sentence stating that the records do not address it — never \"Yes\" or \"No\""*); a clause reading only *not a record of this drug*, inside a record type the same prompt says IS about this patient, sits close to that antecedent, and a flip to it would be fail-open. And it says what the MODULE established rather than a categorical about the chart — Decision 42's own measured constraint, and what keeps it true on the ketoconazole shape where the union is wrong.

**The two channels are deliberately not one string.** They report one fact and must keep saying the same thing, but `UNCORROBORATED_READING_LEAD` is a colon-terminated section head whose object is supplied by the clause after it, so a well-formed sentence cannot be a substring of it; and deriving one from the other would put the single case pinning that literal silently in charge of prompt text in a second channel it was never written for. Each is pinned in its own file, and the pairing is carried by cross-referencing javadocs.

**No prompt change.** The clause introduces no new call for `LlmProvider.DEFAULT_SYSTEM_PROMPT` to teach — it is evidence inside a finding the prompt already instructs the model to carry whole — so the graded-safety paragraph, its two format demonstrations and `LlmProviderTest`'s few-shot ordering chain are untouched. That also means no third verdict class, and therefore nothing owed to `eval/drift-metric/score_probe_safety.py`, whose `caution_led` column exists because a lead class the scorer did not know about was mis-scored.

### Measured live, before and after

A/B on the 3.7.1 standalone with the real local model, `sourceFormat=json` (the curated seed — the only population that can reach this clause), a patient whose ONLY record is a free-text drug allergen `Dexibuprofen`, asked *"Can I give him ibuprofen?"*. Before arm: `main` @ `8189cd76`. After arm: this branch. Three runs each; the deployed omod was hash-checked against the build and its compiled `DrugReferenceInjector` byte-grepped for the clause, so the arms are known to differ.

**The answer is byte-identical across all six runs**, and it is the ticket's own:

> No — Ibuprofen should not be given: it is contraindicated by an active allergy: documented ibuprofen allergy [4].

Two things follow, and the second is a residue rather than a win.

**The verdict does not move**, which is the property this decision was most at risk of losing and the reason the change is additive. A reviewer's objection at plan time was concrete: the clause reads *"could not corroborate it as a record of this drug"* inside a record type the prompt says IS about this patient, and the prompt's opposite branch fires *"when no record addresses the drug or intervention asked about … never \"Yes\" or \"No\""* — a flip to that abstention would be fail-open in a safety net, on a gate Decision 42 records as able to miss a real allergy. It did not happen, 3 of 3. That is what the wording's three properties were chosen for, and it is now measured rather than argued.

**The model does not surface the clause either.** On this arrangement it compresses the finding — it also drops `STRENGTH_WITHHOLD`, which the ticket's own earlier capture shows the model quoting — so the omission is evidence about how this model summarises a finding, not about this clause's wording. So what this change fixes is the module's own evidence: the prompt no longer carries a hedge and a bare assertion of one fact and let the model choose. It does not, on this arrangement, change what the clinician reads. **Anyone reading this as "then it did nothing" should read the collapsed-key paragraph above first**: without a clause on the finding, the contradiction that paragraph closes could not even be expressed, and it was reachable.

### Trade-offs

- **+** Both injected channels now report the corroboration question, so the prompt no longer carries a hedge and a bare assertion of one fact and lets the model pick.
- **+** Monotone: the change adds words and moves no call, no rank, no severity and no wire field. The chip's detail is the same string it was, so #146 and #223 — which twice refused to GATE this chip on corroboration — are not reopened.
- **+** The union is now one method with two callers instead of one method and a place where the same question was not asked at all.
- **−** **The clinician-visible answer is unchanged on the arrangement this was measured over** — 3 runs before, 3 after, byte-identical. See *Measured live* above for why that is a residue rather than a refutation, and for the one property the measurement was really for. What this change buys is that the module's own evidence stops contradicting itself; it does not make this model repeat the qualification.
- **−** **Curated sources only, and the figure is this change's own.** Re-measured for this decision rather than carried over, by walking `DrugReference.getContraindications()` and `DrugSafetyValidator.selfNamedAllergyRule` over what the real production loaders return — `DdiDrugReferenceSource().load()` of the bundled 19 MB knowledge base: **2283 entries, 0 entries publishing a rule, 0 rules**; the real `AtcDrugReferenceSource.parse` of the ATC sample: **6 entries, 0 rules**; the curated seed: **4 entries, 10 rules, 3 of them self-named allergy rules**. Only that last population can render this clause, and `ddinter` is the shipped default since Decision 36 — so no default install reaches it, and the live measurement above is necessarily a `sourceFormat=json` arrangement. Re-derive with the loaders rather than trusting the numbers; they are a fact about the shipped files and move when those do.
- **−** **The two channels still disagree where they are about DIFFERENT ROWS of one substance, and this decision does not close it.** One fold over one unit buys agreement about one ROW. `ContraindicationChips` keys on the SUBSTANCE and keeps the strictly stronger RANK across its rows, while `matchingEntries` injects ONE record for the substance and renders it for `canonicalRow`'s row, whose sections state that row's rules alone — so a sibling row's sentence can replace the rendered row's in the ledger and bring its own corroboration answer with it, while that record states its own rule's. Reproduced through the real `JsonDrugReferenceSource.parse`, `DrugReferenceInjector.injectRecords` and `DrugSafetyValidator`: two `Levoketoconazole` rows declaring one `substanceName`, the tablets rule on `levo` reached mid-word by a recorded `Levocetirizine` (rank 0, uncorroborated) and the gel rule on `ketoconazole` named outright by a recorded `Ketoconazole` (rank `SELF_NAMED_RULE`, corroborated); one record is injected, it hedges the tablets clause, and the finding beside it states the gel clause bare with `STRENGTH_WITHHOLD`. Authored with one note on both rules it is #308's own defect string for string. The shape is one `DrugReferenceValidity.RULES_WITHOUT_A_SUBSTANCE_IDENTITY` steers a curated-`json` operator toward.

  **It runs in BOTH directions, and only one of them was declared here.** The direction above — the sibling's CORROBORATED sentence outranking the rendered row's while that record hedges — prints the same pair on `main`, measured by emptying `FINDING_UNCORROBORATED_MATCH` so `renderFinding` appends nothing: record hedges, finding bare, either way. The reverse is not. Where the rendered (`canonicalRow`) row's rule is the corroborated one, its record states the clause as this chart's reading, while a sibling row's UNCORROBORATED sentence holding the ledger on a rank TIE now brings the hedge with it — record asserts, finding hedges, where the same measurement shows `main` printing agreement. **So the clause makes that arrangement worse, and "on `main` the finding carries no clause on any arrangement" is not the reason it is left open** — that reason is invalid in general, since adding a clause where the two channels agreed is exactly how a disagreement gets made, and an earlier revision of this bullet and of CLAUDE.md both stated it. Reproduced through the real `JsonDrugReferenceSource.parse` and `injectRecords` over `drug-reference-rule-rows-rendered-row-corroborated.json` — a bare `Levoketoconazole` row (which `namesNoRoute` elects) ruling on `ketoconazole` with a blank note and named outright by a recorded `Ketoconazole`, beside a `Levoketoconazole (tablets)` row ruling on `levo`, authored first so it is the ledger's incumbent, reached only mid-word by a recorded `Levocetirizine` — and pinned by `UncorroboratedFindingProvenanceTest.theRenderedRowsRecordAssertsWhileTheSurvivingSiblingSentenceHedges`. What IS true of both directions is the remedy: left open because closing it moves what a clinician-facing surface SAYS, which this decision is deliberately monotone about: either the record states the whole substance's rules (a different record, and every row's notes under one row's name), or the ledger's surviving sentence becomes the rendered row's (a different chip, decided by the rank rules #146 and #223 settled). The cross-row fold rejected as the second cut above does **not** close it — mutate the pre-walk to span the substance's rows and read the failures: `aCorroboratedRuleOnANEIGHBOURRowDoesNotClearTheClauseThatRowsOwnRecordStates` reddens, because that fold breaks its arrangement, while this one goes on printing exactly what it printed before. The interaction arm already records the same asymmetry against its own text — `onePerPartner`'s javadoc, issue [#163](https://github.com/openmrs/openmrs-module-chartsearchai/issues/163): the chip reads every row of a substance while the record sees only the canonical one — so this is that residue arriving in the contraindication arm rather than a new class of defect. One case per direction, each over a fixture authored for it: `aSiblingRowsSentenceOutranksTheRenderedRowsAndBringsItsOwnAnswer` and `theRenderedRowsRecordAssertsWhileTheSurvivingSiblingSentenceHedges`. Both turn on which row's sentence survives the ledger, which is what they observe rather than assume — but not on the same mutation, and an earlier revision of this bullet named one for both. Making `ContraindicationChips.add`'s tie-break replace an equal-ranked incumbent reddens `theRenderedRowsRecordAssertsWhileTheSurvivingSiblingSentenceHedges`, `aCorroboratedRuleOnANEIGHBOURRowDoesNotClearTheClauseThatRowsOwnRecordStates` and `oneCorroboratedRuleOfACollapsedKeyClearsTheClauseForTheWholeKey` alongside five pre-existing cases — and NOT `aSiblingRowsSentenceOutranksTheRenderedRowsAndBringsItsOwnAnswer`, whose two rules do not tie on rank (4 against 0), so no tie-break can move it. That case is observed by the other half of the same ledger rule: make `add` keep the incumbent whatever the rank and it reddens, because the sentence it asserts as its precondition is the one the gel row's rule won by outranking.
- **−** **A `condition` rule matched by bare containment is still untouched.** `selfNamedAllergyRule` is allergy-typed and so are the chip's demotion and Decision 42's section; there is no allergen-arm analogue to corroborate a condition against. Unchanged residue, not a new one.
- **−** **The residue Decision 42 states in the other direction remains open.** Where a drug is named only by the ANSWER, `matchingEntries` injects no `drug_reference` record at all — so that channel is silent while this one now speaks, which is a narrowing of the asymmetry rather than a closing of it.

## Decision 45: An ended drug order is a record class the prompt names

**Status: Accepted** (August 2026) — implemented, issue [#315](https://github.com/openmrs/openmrs-module-chartsearchai/issues/315). One clause in `LlmProvider.DEFAULT_SYSTEM_PROMPT`. No chip, no wire field, no deterministic arm, and no strength clause changes.

### Context

`DEFAULT_SYSTEM_PROMPT` classifies every other record class it puts in front of the model and says what it means — `"Records beginning with \"Drug reference\" are clinical reference data, not this patient's data"` ([#110](https://github.com/openmrs/openmrs-module-chartsearchai/issues/110)/[#112](https://github.com/openmrs/openmrs-module-chartsearchai/issues/112)), `"Records beginning with \"Safety finding\" ARE about this patient"` — and classified a drug order not at all. An ENDED prescription therefore reached the model as a flat field list in which the end date is one field among four: `Drug order: Nevirapine. Action: NEW. Urgency: ROUTINE. Stopped: 2026-08-24`.

Measured on the 3.7.1 standalone against `main` @ `3775c997`, one concept-only Nevirapine order stopped the day before and no active order, `chartMode=queryScoped`, n=3 byte-identical per shape:

| question | answer |
|---|---|
| `is he currently taking any medications?` | **"Yes — the patient was ordered Nevirapine on 2026-07-26 [1]."** |
| `what medications has he been prescribed?` | "Nevirapine was prescribed on 2026-07-26 [1]." |
| `what medications is he taking?` | "The patient was taking Nevirapine [1]." |
| `when was his nevirapine stopped?` | "Nevirapine was stopped on 2026-08-24 [1]." |

Three of four shapes named the drug and dropped its end; only the shape whose question supplied the word "stopped" carried the date. The ticket reported a fourth variant of the first row — a hedge that denied the status while citing the record that states it — so the failure is not one wording but the absence of a rule.

**Two EXISTING rules produce the "Yes" between them**, which is why this is a contradiction and not a mere omission. The *"When the query asks for the latest, current, or most recent value, the relevant record is the FIRST matching one in the list; report that value"* rule points AT the stopped order, because it is the first matching one. And the yes/no rule's YES criterion is a PRESENCE criterion — *"Start with \"Yes\" ONLY when a record explicitly names what is asked — a diagnosis, condition, allergy, or enrollment naming it"* — whose enumerated classes do not include a drug order at all, so the model is resolving a case neither rule covers.

### Decision

**The prompt names the record class and says what it settles.** One clause in the record-class paragraph, keyed on the two markers `DrugReferenceInjector.describesEndedOrder` already keys on for the [#118](https://github.com/openmrs/openmrs-module-chartsearchai/issues/118) reconciliation, concatenated from the display-cased constants `ORDER_STOPPED_MARKER` / `ORDER_DISCONTINUED_MARKER` rather than respelled — the `FINDING_PREFIX` idiom, for the reason that bullet gives: a prompt carrying its own copy of a cue goes on teaching a marker no chart record carries, and every test stays green. `EndedOrderMarkerContractTest` pins both constants against querystore's REAL `DrugOrderRecordSerializer` output, raw casing included.

**Three drafting constraints, each measured rather than preferred.**

* **The stop DATE is conditional.** querystore appends `". Stopped: "` only for a non-null `getDateStopped()` and `". Action: "` unconditionally, so a DISCONTINUE order reads as ended and carries no date. A rule demanding one would be unsatisfiable there and the model would have to invent it. Pinned by `aDiscontinuedOrderCarriesNoStopDate_soTheRuleMayNotDemandOne`.
* **Scoped to THAT drug, with the no-marker counterpart stated.** A stopped record settles nothing about whether the patient is on ANY medication, and under the shipped `queryScoped` mode the model reads a slice — unscoped, the clause trades an over-hedge for a fabricated categorical negative, which is the slice-for-the-patient confusion [#94](https://github.com/openmrs/openmrs-module-chartsearchai/issues/94) and #214 already name.
* **The ended branch is conditioned on EVERY record naming the drug having ended, and where both exist the live record governs.** A dose change in OpenMRS is a REVISE — a new order is created and the previous order's `date_stopped` is set — so one drug on two records, one ended and one live, is the commonest chart shape there is, and querystore indexes both. An ended branch stated categorically about the DRUG (which the first draft was, its only escape hatch naming *other* drugs) asserts a falsehood about a live prescription. **The symptom did not reproduce and the clause was fixed anyway**: measured on a stopped Nevirapine 200mg beside a live REVISE 400mg, n=2 byte-identical, all three "currently taking" cells answered *"Yes"* under the first draft — the model resolved the two records correctly without being told to. That is exactly the inference this issue exists to stop relying on, so the precedence is now stated. Raised by a Phase 2 integration reviewer that had never seen the change being written.
* **The clause is phrasing-sensitive at ONE WORD, and that is a fact about this rule rather than a caveat about prompts in general.** A Phase 2 reviewer correctly observed that records reach the model as `[7] Drug order: …`, so the antecedent's *"its text **begins**"* is literally loose and *"carries"* is the accurate word. Applying that correction — one word, everything else byte-identical — put the ticket's original defect back: *"is he currently taking any medications?"* returned to *"Yes — the patient is currently taking Nevirapine"* about a drug stopped two days earlier, n=3 byte-identical, and reverting the word restored the correct answer, also n=3. The doubled *"carries … that also carries"* is the likely mechanism and the evidence does not establish it; what the evidence does establish is the sensitivity. **The clause therefore keeps the less accurate word, deliberately**, and anything rewording it re-runs the three arrangements first.
* **The prefix cue is a GUARD, not labelling.** The clause identifies the record class by its text beginning `"Drug order:"` before looking for either marker, and querystore's `AbstractServiceOrderRecordSerializer` emits the SAME `". Stopped: "` and `". Action: "` behind `"Referral order:"` and `"Test order:"` — verified from the deployed jar's constant pool. Drop the prefix as redundant labelling and the model reports an ended lab test as an ended prescription. `theRecordPrefixTheClauseIdentifiesTheClassBySurvivesToo` pins it against the real serializer, because unlike the two markers that cue is a literal in the prompt.
* **The forbidden sentence is DESCRIBED, never quoted, and paired with the lead that replaces it.** Arm B of the 2026-07-30 A/B forbade a sentence by quoting it and the model then emitted that exact string 6/6 on a cell the baseline never did — *"Prohibition-by-quotation primes the phrasing."* And #214's hunk is the precedent for pairing a prohibition with a positive instruction. No otherwise-branch: that was arm D, the costliest of the four.

**Two drafts were refuted by measurement before this one, and both are pinned.** They are recorded because each looked right.

1. **Scoped to a yes/no framing** (*"asked whether they are currently taking it, answer No"*). Rows 1 and 2 turned; row 3 went from *"The patient **was** taking Nevirapine"* to **"He is currently taking Nevirapine [1]"** — *worse than baseline*, a flat falsehood about a drug stopped the day before, n=3 byte-identical. A wh-question names the drug without ever asking "whether". `thePromptRefusesAYesAndAnUnrecordedStatusOnTheStrengthOfAnEndedOrder` pins the shape-independent prohibition.
2. **Shape-independent, but not out-ranking the current-value rule.** Row 3 still read *"He is taking Nevirapine [1]"*, because *"report that value"* is satisfied by the stopped order when it is the only one. Only naming that case turned the cell. (That draft wrote it *"even where it is the most recent…"*; the shipped clause says *"even where **its record** is the most recent or the only drug order in the chart"*, the pronoun having moved when the ended branch was re-conditioned on the drug's records rather than on the drug.)

**Measured after, same arrangement, n=3 byte-identical:** all four shapes correct, the already-correct shape unchanged. On a second patient carrying a stopped order BESIDE an active one — the ticket's own named unmeasured gap — the two "currently taking" shapes stay correct (*"Yes — the patient is currently taking Acetaminophen"*) and the third labels both (*"1. Acetaminophen (Current) … 2. Nevirapine (Stopped on 2026-08-20)"*), which is the direct refutation of the over-fire risk. On a third patient carrying the RENEWAL shape — one drug on an ended order and a live REVISE — the answer keeps its "Yes", keeps the dose, and now names the prior therapy with its date, where the baseline said nothing about it.

**Gated as a pure-prompt A/B**, per `eval/drift-metric/README.md`: one binary, swapping `chartsearchai.llm.systemPrompt`, arms differing by exactly one `difflib` insertion opcode. The committed golds are unusable on this cohort — 0 of `metric_gold.standalone.json`'s 4 patients and 0 of `metric_gold.rc2.json`'s 22 exist in the 3.7.1 demo DB, and a full `capture_eval_standalone.sh` run returns 32/32 HTTP 404 — which is the situation the README's *"the 3.7.1 cohort: standalone gold is unremappable"* note records, and it names these two instruments instead:

| gate | result |
|---|---|
| `capture_probe_safety.sh` + `score_probe_safety.py`, 20 shared cells | verdict-led 6/6 both arms, unlicensed verdicts 0 both, **abstention held 11 → 13 of 13** |
| `capture_probe_yesno.sh` + `compare_arms.py`, 64 presence cells | verdict-led 64/64 both, directness 64/64 both, **`records do not address` bleed: 0** |

Beat-or-match holds on both. The safety probe's two flips are arm A's fabricated refusals becoming correct abstentions and are stable at **n=6 per arm** (the README's repeat protocol, since single-cell flips sit inside this host's decode noise). **No mechanism is claimed for that improvement and none is asserted** — it is recorded as a stable, measured, non-regressive side effect, not as a benefit of the clause.

### Trade-offs

- **+** The record class the module has always put in front of the model is now the one class it also explains, so whether an ended prescription reads as ended no longer depends on the question's phrasing.
- **+** One definition of "ended order". The answer rule and the #118 reconciliation key on the same two constants, so a querystore rewording moves both together and cannot leave the prompt teaching a cue the chart does not carry.
- **+** Deterministic layers untouched. No chip, no severity, no wire field, no strength clause; the ticket verified the chips were already correct, because a stopped order leaves `getActiveOrders`.
- **−** **A date the answer must now state is a date it can get wrong.** An earlier draft of this clause rendered one stop date as `2026-10-20` where the record said `2026-08-20` (n=3), on the stopped-beside-active patient; the shipped clause states it correctly on all three arrangements, so the residue is not carried as a live defect. It is recorded because the class of risk is real and created by this change: the baseline stated no date on those cells at all. The obvious mitigation is **refused on the record rather than untried** — `eval/drift-metric/README.md` measured *"Quote numeric values and dates EXACTLY … do not invent values"* (focus-hint v4) and rolled it back, because it *"made the LLM less willing to abstain"*, which would trade a date slip for loss on the very column the second gate above protects. Where the model does conflate the record's date prefix with its in-body stop date, Tier-2 grounding flags the citation unsupported, so it fails loudly rather than silently.
- **−** **Auto-expired orders are not reached, and this clause makes that residue SHARPER rather than merely leaving it uncovered.** querystore carries `auto_expire_date` in the document metadata but renders no marker for it into the record text — measured by RUNNING the serializer, in `QuerystoreOrderTextMarkerTest.anAutoExpireDateAloneIsNotVisibleInTheRenderedText`. So an order that lapsed by auto-expiry carries no end marker, and the clause's positive counterpart — *"A drug-order record carrying neither marker is CURRENT"* — therefore **asserts** that such an order is current, which is false. Before this change the prompt said nothing about the class and the model inferred; now the module states it. That is a real cost and it is stated plainly rather than filed as "not covered": the counterpart is load-bearing (measured — without it, *"what medications is he taking?"* answered *"No medications are currently recorded"* and dropped the stopped order entirely, n=3), so softening it to protect a case the module has no signal for would trade a measured behaviour for an unmeasured one. And the disagreement runs deeper than the prose: `PatientClinicalContextBuilder` reads `getActiveOrders`, and OpenMRS's `Order.isActive()` excludes an expired order — so such a drug gets no chip, no interaction or duplicate-therapy screening and no #118 injection, while the answer now states it is current. Chip and prose can therefore disagree on that drug in the worst direction, the answer asserting a medication the deterministic layer has silently not screened. It is the same limitation `describesEndedOrder` already records for the reconciliation, and it is closed by the same structural fix — threading the metadata rather than reading rendered prose — which is deliberately out of this issue's scope.
- **−** **The precedence sentence teaches a claim that rests on TWO chart citations, and the grounding verifier's withholding does not cover that shape.** *"the CURRENT record governs … the ended record is earlier therapy for it"* is one assertion supported jointly by two `drug_order` citations, neither of which entails it alone. `CitationGroundingVerifier.restsOnReferenceMaterial` withholds a Tier-2 negative only where a claim co-cites a demote-only reference-group index, which these are not. Read as a compound unit (several assertions) #302's rule rescues it; read as composite (one assertion, jointly supported) both citations can publish `grounded=false` and a correct answer renders *Unsupported*, in red — the #201/#284 shape one substitution along. Not widened here, because widening it would cost the mis-attribution signal #122 exists for on chart citations. Raised by a Phase 2 integration reviewer and recorded rather than fixed.
- **−** **Prompt-only, so a deployment overriding `chartsearchai.llm.systemPrompt` loses it**, exactly as it loses every other rule in the built-in prompt. Not new and not addressed.
- **−** **The efficacy evidence is two synthetic arrangements on one local model.** No committed gold cell contains a drug order at all, so neither regression gate can corroborate the fix — they bound collateral damage only. The ticket's own caveat stands: these are not per-shape rates over models or over phrasings generally.
- **−** **The prompt grew by one clause**, against a default `contextSize` of 32768. No character count is published here on purpose: three ways of measuring the compiled constant (constant-pool walk, reflection, `javap -constants`) gave three different numbers during review, the last inflated by `\uXXXX` escaping — so the figure is exactly the kind this repo has had to re-measure before. Reproduce it rather than trust a tally: dump `DEFAULT_SYSTEM_PROMPT` with and without the clause and diff. What IS reproducible and is the A/B's actual control is the **structural** claim: `difflib.SequenceMatcher` over the deployed jar's prompt and this branch's reports exactly **one** non-equal opcode, a single insertion.
  There is no local token-budget calculation to invalidate — `LocalLlmEngine` detects chart overflow reactively, from llama-server's own 400 — so nothing hardcoded changes meaning. `LocalLlmEngine.kvCacheKey` does hash the system prompt, so persisted disk KV entries keyed on the old prompt are invalidated once on deploy and re-warm; steady-state reuse is unaffected, since the new prompt is equally constant. Recorded because the prompt is the one artifact in this module where every addition has to justify itself against `eval/drift-metric`'s record of three reverted arms.
