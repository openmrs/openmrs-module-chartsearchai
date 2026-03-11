# Chart Search AI - Architectural Decisions

This document captures the architectural decisions made for the Chart Search AI module, including alternatives evaluated and the reasoning behind the chosen approaches.

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

Use an LLM only as a fallback for hard cases. The primary search path should be deterministic and fast. The LLM earns its compute cost only when simpler approaches cannot solve the problem.

## Decision 2: Overall architecture — RAG vs. alternatives

### Options evaluated

#### Option A: Full FHIR bundle to LLM
Send a complete FHIR bundle with all patient resources to an LLM for processing.

**Rejected because:**
- A patient with 5 years of visits could have thousands of resources, producing 500K-2M tokens — far exceeding any local model's context window (4-8K typical).
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

**Kept as fallback layer.** Solves 70% of the problem with 20% of the complexity. No hallucination risk, no compute requirements, works offline. Weakness: no natural language understanding or synthesis.

#### Option D: Agent/tool-use pattern
Give the LLM access to OpenMRS APIs as tools and let it autonomously decide what to call.

**Deferred to v2+.** Architecturally elegant but demands more capable models than the deployment environment can support. Small local models (2-8B) are weak at tool use and multi-step reasoning. Latency from multiple sequential LLM → API → LLM loops is problematic in a 90-second encounter.

#### Option E: Pre-computed summaries (batch processing)
Generate patient summaries offline ahead of time. Search summaries at query time.

**Kept as complement.** Good for common queries (active meds, allergies, problem list). Weakness: stale data, doesn't handle unexpected/novel questions. Best combined with real-time retrieval.

#### Option F: RAG (Retrieval Augmented Generation) — CHOSEN
Retrieve relevant records first using deterministic search, then use the LLM only for query understanding and response formatting.

**Chosen because:**
- Retrieval is deterministic and auditable — every piece of data has a traceable source.
- LLM never invents facts; it only works with data explicitly provided.
- Minimal hallucination surface area.
- Works with small local models since query parsing and response synthesis are short-context tasks.

### Decision

Use RAG with a layered approach:
1. **Query understanding**: Small local LLM translates natural language to search parameters.
2. **Deterministic retrieval**: Java code queries OpenMRS via existing services + embedding similarity search.
3. **Response synthesis**: LLM formats retrieved records into a coherent answer with citations.
4. **Validation**: Every claim is verified against the retrieved data before returning to the user.

## Decision 3: Embedding approach — semantic search index

### Options evaluated for retrieval

#### Option A: Targeted FHIR queries with manual concept mapping
Map each query type to specific FHIR resource types and SNOMED codes.

**Weakness:** Requires manually mapping every possible query pattern to the right resources. Misses things you wouldn't think to query — e.g., a free-text visit note mentioning "mother had breast cancer."

#### Option B: Concept graph traversal
Use the OpenMRS concept dictionary as a knowledge graph. Map query terms to SNOMED concepts, traverse the hierarchy, query matching records.

**Kept as complement.** Fast (milliseconds), deterministic, leverages existing concept dictionary. Weakness: only works for structured/coded data, misses free-text entirely.

#### Option C: Semantic search index with embeddings — CHOSEN
Pre-index all patient data with vector embeddings. At query time, find relevant records by embedding similarity.

**Chosen because:**
- No manual mapping needed — similarity search catches things you wouldn't think to query.
- Works with both structured and unstructured data.
- The embedding model is tiny (~80MB, runs on CPU in milliseconds).
- Query-time cost is just a vector similarity search — very fast.
- Per-patient search space is small enough (typically <2000 records) for brute-force in-memory cosine similarity.

#### Option D: Clinical concept extraction pipeline (NLP at write time)
Use rule-based NLP (cTAKES, MedSpaCy) to extract structured facts from all data at write time.

**Deferred.** Zero query-time AI cost, works on unstructured text. Weakness: extraction pipeline needs tuning per site, adds processing to the write path.

#### Option E: Map-reduce over chart segments
Split patient chart into time-based segments, classify each for relevance, only send relevant segments to LLM.

**Deferred to v2+.** Handles arbitrarily large charts but adds infrastructure complexity.

### Decision

Semantic search index as the primary retrieval mechanism, with concept graph traversal as a complement for structured data.

## Decision 4: FHIR as LLM input format

### Analysis

FHIR is a good retrieval API but a poor serialization format for LLM context windows:

- **Extremely verbose**: A single blood pressure observation is ~800 tokens in FHIR JSON vs. ~15 tokens in compressed form. On a small model with 4-8K context, this matters enormously.
- **Deeply nested**: `coding` inside `code` inside `component` inside `Observation`. Small LLMs are worse at extracting information from nested structures.
- **Redundant metadata**: System URIs, references, profiles waste context tokens.

### Decision

Use FHIR as the retrieval layer, not the LLM input format. A compression step (`ClinicalTextSerializer`) converts FHIR-style resources into flat, concise clinical text. This gives ~10x token efficiency while preserving clinical meaning.

Example:
```
FHIR JSON: ~800 tokens
Serialized: "Outpatient Visit (2024-01-15) - Systolic Blood Pressure: 120 mmHg (ABNORMAL)"  ~15 tokens
```

## Decision 5: Embedding granularity

### Options

| Granularity | Pros | Cons |
|---|---|---|
| Individual record | Precise retrieval, fine-grained citations | Many embeddings per patient, records in isolation lose context |
| Per encounter | Groups related data naturally, fewer embeddings | Large encounters produce long text, less precise |
| Per clinical category | Matches how clinicians think | Arbitrary groupings, large text chunks |

### Decision

Embed at the **individual record level**, but enrich each record with encounter context. Instead of just `"120 mmHg"`, the serialized text includes `"Outpatient Visit (2024-01-15) - Systolic Blood Pressure: 120 mmHg (ABNORMAL)"`. This keeps embeddings small and precise while giving the similarity search enough context to work with.

## Decision 6: Embedding model

### Decision

Use **all-MiniLM-L6-v2** via ONNX Runtime, running in-process in Java:
- ~80MB model, runs on CPU, no GPU needed
- Produces 384-dimensional vectors
- No external service dependency
- Falls back to term-frequency hashing when the ONNX model file is unavailable

## Decision 7: Vector storage — MySQL, not a vector database

### Analysis

MySQL does not natively support vector embeddings (native `VECTOR` type was added in MySQL 9.0+, but OpenMRS deployments typically run MySQL 5.7 or 8.x).

However, a vector database is unnecessary for this use case because:
- Search is **per-patient**, not across all patients
- A patient with 2000 records means 2000 vector comparisons — trivial in Java (microseconds)
- Embeddings are stored as BLOBs (~1.5KB per record for 384 dimensions)

### Decision

Store embeddings as `MEDIUMBLOB` in a regular MySQL table (`chartsearchai_embedding`), indexed by `patient_id`. Load a patient's embeddings into memory and compute cosine similarity in Java. Zero new infrastructure.

The `UNIQUE KEY (resource_type, resource_id)` constraint prevents duplicate embeddings and enables upsert on re-index.

## Decision 8: Index population strategy

### Decision

Two modes:
- **Batch**: Full patient re-index on first query or via nightly scheduled task. Deletes existing embeddings and re-creates from current chart state.
- **Incremental**: On encounter save (via OpenMRS event system), index only the new/updated encounter's records using upsert. Avoids re-indexing the entire patient for each data change.

## Decision 9: Text serialization — ClinicalTextSerializer pattern

### Decision

A generic `ClinicalTextSerializer<T>` interface with one implementation per OpenMRS resource type:

| Serializer | Output example |
|---|---|
| `ObsTextSerializer` | `"Outpatient Visit (2024-01-15) - Systolic Blood Pressure: 120 mmHg (ABNORMAL). Note: Taken after exercise"` |
| `ConditionTextSerializer` | `"Condition: Type 2 Diabetes Mellitus. Status: ACTIVE. Verification: CONFIRMED. Onset: 2019-03-10"` |
| `AllergyTextSerializer` | `"Allergy: Penicillin (DRUG). Severity: Severe. Reactions: Anaphylaxis, Rash"` |
| `DiagnosisTextSerializer` | `"Diagnosis: Malaria. Certainty: CONFIRMED. Rank: Primary. Date: 2024-01-15"` |
| `OrderTextSerializer` | `"Order: Complete Blood Count. Action: NEW. Urgency: STAT. Reason: Suspected anemia. Date: 2024-01-15"` |
| `EncounterTextSerializer` | Composes obs + diagnosis serializers into a full encounter summary |

Key design choices:
- Each serializer enriches its output with encounter context (type, date) for better embedding quality.
- `ObsTextSerializer` flattens group members into the parent obs text.
- Obs interpretation (`NORMAL`, `ABNORMAL`, `CRITICALLY_ABNORMAL`) and comments are included.
- Units are extracted from `ConceptNumeric`, not `Concept` (which has no `getUnits()` in OpenMRS 2.6.x).

## Decision 10: Direct LLM inference — simplified architecture without embeddings

### Context

The current architecture (Decisions 3–9) uses a two-model pipeline: an embedding model for semantic search retrieval, plus a generative LLM for query understanding and response synthesis. This requires vector storage, cosine similarity search, and an embedding indexing strategy.

However, if two conditions are met, this complexity can be eliminated entirely:

1. **The full patient chart fits within the LLM's context window.** A patient with 2000 records, each serialized to ~15 tokens by the `ClinicalTextSerializer`, produces ~30K tokens. Models like Mistral 7B (32K context) and Llama 3.2 3B (128K context) can accommodate this.
2. **A local LLM is available with acceptable latency.** Small quantized models (1.5B–3.8B parameters) can run on CPU via [java-llama.cpp](https://github.com/kherud/java-llama.cpp), which provides Java JNI bindings to llama.cpp and is available on Maven (`de.kherud:llama`). This keeps the module self-contained with no external service dependency.

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
- **No retrieval errors**: Embedding-based retrieval can miss relevant records if the query and record text are semantically distant. Direct LLM inference eliminates this failure mode.
- **No index staleness**: No need for batch or incremental indexing. Every query sees the current chart state.

### Candidate models

| Model | Quantized Size | RAM | Context Window |
|-------|---------------|-----|----------------|
| Qwen 2.5 1.5B | ~1GB | ~2GB | 32K tokens |
| Phi-3 Mini 3.8B | ~2GB | ~4GB | 4K tokens (128K variant available) |
| Llama 3.2 3B | ~2GB | ~4GB | 128K tokens |
| Mistral 7B | ~4GB | ~8GB | 32K tokens |

Qwen 2.5 1.5B (~1GB) is the minimum viable option for clinical reasoning. Phi-3 Mini 3.8B (~2GB) is the safer choice. Both run on CPU via java-llama.cpp with quantization (Q4_K_M format).

### When to use this approach

This approach is viable when:
- The deployment has sufficient RAM for the model (~2–4GB above baseline).
- Latency of a few seconds per query is acceptable (CPU inference on quantized models).
- The patient chart fits within the model's context window after serialization.

### When to fall back to embedding-based retrieval

The embedding-based architecture (Decisions 3–9) remains necessary when:
- Patient charts are too large for the LLM's context window (e.g., patients with decades of records at high-volume facilities).
- The deployment hardware cannot support even the smallest viable LLM.
- Sub-second response times are required (embedding similarity search completes in <10ms).

### Decision

Document this as an available architectural option. The embedding-based RAG approach (Decisions 3–9) remains the default for v1, as it has lower hardware requirements and provides sub-second retrieval. The direct LLM inference approach can be adopted when deployments meet the conditions above, with the `ClinicalTextSerializer` infrastructure serving both architectures.

## Planned future work

- Replace `TermFrequencyEmbeddingProvider` with ONNX Runtime + all-MiniLM-L6-v2
- Add concept graph traversal as a complement to embedding search
- Add pre-computed summaries for common queries
- Agent/tool-use pattern for complex multi-step questions (when better local models are available)
- Unstructured data / image OCR (photos of paper forms)
- LLM-based query understanding and response synthesis layers
- REST API endpoints for querying
- Guardrail validation and audit logging
