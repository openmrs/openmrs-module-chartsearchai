# Semantic Similarity Search — Improvement Plan

The embedding pre-filter in `LlmInferenceService.findSimilar()` returns inconsistent results: sometimes too many irrelevant records, sometimes missing relevant ones. This document analyzes the root causes and proposes improvements.

## Root Cause Analysis

After deep analysis of the full retrieval pipeline, 9 root causes were identified across three categories: what gets embedded, how queries are processed, and how results are selected.

---

## Category A: What Gets Embedded (Indexing-Side Issues)

### A1. First-Sentence Truncation Discards Semantically Rich Content

**File:** `EmbeddingIndexer.java` (`firstSentence()`)

The `firstSentence()` method truncates text at the first `. `, embedding only the opening fragment:

```
Full text:  "Drug order: Metformin 500mg. Dose: 1.0 Tablet(s) Oral twice daily. Duration: 30 Day(s)"
Embedded:   "Drug order: Metformin 500mg"
```

This means queries like *"oral medications"*, *"twice daily dosing"*, or *"30-day prescriptions"* cannot match because those tokens were never embedded. For obs with group members, the first sentence is just the group concept name (e.g., `"Blood Pressure"`), losing the actual values entirely.

**Impact:** Missing relevant records for queries about dosing, route, frequency, duration, severity, reactions, status — anything after the first sentence.

### A2. Semantic Prefix + First Sentence Creates a Narrow, Rigid Embedding

**File:** `EmbeddingIndexer.java`, `ChartSearchAiUtils.java` (`buildPrefixedText`, internal `getEmbeddingPrefix`)

The embedded text is: `prefix + firstSentence(text)`. For example:
```
"Medication prescription: Drug order: Metformin 500mg"
"Patient allergy: Allergy: Penicillin"
```

This creates redundancy — "Medication prescription: Drug order:" is doubly-typed — and the prefix dominates the embedding vector because the first sentence is already short. The embedding represents the *category* more than the *content*.

**Impact:** Records of the same type cluster together regardless of content, reducing intra-type discrimination. A query for "Penicillin" sees all allergies ranked similarly because the prefix dominates.

### A3. No Synonym Expansion in Indexed Text

Clinical records use specific concept names from the OpenMRS dictionary ("Systolic Blood Pressure", "Diastolic Blood Pressure"). The embedding model has some synonym awareness, but clinical abbreviations (BP, Hb, WBC, ARV) and lay terms ("sugar level" for glucose) are poorly represented in all-MiniLM-L6-v2's general-purpose vocabulary.

**Impact:** Queries using informal/abbreviated clinical language get low similarity scores to formally-named records.

---

## Category B: How Queries Are Processed (Query-Side Issues)

### B1. Query-Document Asymmetry

**File:** `LlmInferenceService.java` (`stripQueryStopwords`, `findSimilar`)

The query is: `lowercased, stopword-stripped, bare text`
The document is: `"Semantic Prefix: First sentence of record"`

This structural mismatch hurts cosine similarity. The query `"medications"` is compared against `"Medication prescription: Drug order: Metformin 500mg"` — the query has no prefix, no type label, no structure. The embedding model must bridge this asymmetry through learned semantic understanding alone, which is unreliable for a general-purpose model on domain-specific text.

### B2. No Query Classification or Intent Detection

All queries are processed identically. But clinically, queries fall into distinct retrieval patterns:

| Query Type | Example | Ideal Retrieval |
|---|---|---|
| Category lookup | "any medications?" | All records of type `order` (drug) + `medication_dispense` |
| Specific concept | "Metformin dose?" | Records mentioning Metformin |
| Temporal | "recent lab results?" | Records of type `obs` with test class, sorted by date |
| Cross-record synthesis | "is diabetes controlled?" | HbA1c obs + diabetes condition + medication orders |
| Negation check | "any allergies to penicillin?" | Allergy records + any penicillin-class drugs |

A category lookup like "any medications?" should retrieve ALL drug orders and dispenses, not just the top 10 by similarity. The current pipeline treats this the same as a specific-concept query.

### B3. Aggressive Stopword Removal Can Strip Meaning

**File:** `query-stopwords.txt`

The stopword list includes `"current"`, `"latest"`, and `"known"`. So:
- `"current medications"` → `"medications"` (loses temporal qualifier)
- `"latest lab results"` → `"lab results"` (loses recency intent)
- `"known allergies"` → `"allergies"` (loses established/confirmed intent)

While these are noted as "clinical query fillers," they carry semantic weight that affects what records are relevant.

---

## Category C: How Results Are Selected (Scoring & Filtering Issues)

### C1. Compressed Score Distribution Makes Thresholding Fragile

**Acknowledged in ADR:** all-MiniLM-L6-v2 produces similarity scores in narrow ranges (e.g., 0.20–0.31) for clinical text. With only 0.11 separating the most relevant from the least relevant record, tiny floating-point differences determine inclusion/exclusion. The dual-floor threshold (ratio-based + range-based) and gap detection try to adapt, but they operate on a score distribution that has too little signal.

**Impact:** Small changes in query phrasing cause large changes in which records pass the threshold, explaining the "sometimes more, sometimes less" behavior.

### C2. No Resource-Type Boosting

When a clinician asks "any medications?", drug orders and medication dispenses should be strongly boosted. The current system relies entirely on cosine similarity, which treats all resource types equally. The semantic prefix helps somewhat, but:
- The query has no prefix (asymmetry from B1)
- The prefix effect is diluted by the first-sentence content

### C3. The Adaptive Gap Detection Has Edge Cases

**File:** `LlmInferenceService.java` (`findAdaptiveCutoff`)

The gap detection requires at least 2 prior gaps to compute a meaningful average. For patients with few records or when scores are tightly clustered, this can:
- Cut too aggressively (2 records returned when 5 are relevant)
- Not cut at all (all records above the floor returned when only 3 are relevant)

The `ADAPTIVE_MIN_RECORDS = 2` floor means gap detection never fires before position 2, so it can't cut a clearly irrelevant second result.

---

## Recommended Improvements (Ordered by Impact × Feasibility)

### 1. ★★★ Embed Full Serialized Text Instead of First Sentence Only — ✅ Implemented

**Change:** Remove the `firstSentence()` truncation. Embed the full `prefix + text` instead of `prefix + firstSentence(text)`.

**Why:** The 256-token limit on the WordPiece tokenizer already provides natural truncation for long records. Most serialized records are 1-3 sentences (20-60 tokens), well within the limit. The first-sentence heuristic discards semantically important content (dosing, severity, reactions) that distinguishes records within the same type.

**Risk:** Embedding vectors become more "noisy" for long records. Mitigated by the 256-token truncation and the fact that serializers already produce concise text.

**Files to change:** `EmbeddingIndexer.java` (remove `firstSentence()` calls)

### 2. ★★★ Add Query-Side Prefixing to Match Document Structure — ✅ Implemented

**Change:** The user query can be prepended with a configurable prefix before embedding (`chartsearchai.embedding.queryPrefix`, default empty). Disabled by default because all-MiniLM-L6-v2 was not trained with instruction prefixes — adding one dilutes short queries with noise tokens that reduce cosine similarity. Enable via global property when using instruction-aware models (e.g., BGE).

### 3. ★★★ Implement Hybrid Retrieval: Keyword + Semantic — ✅ Implemented

**Change:** An additive keyword bonus is computed alongside cosine similarity: `finalScore = semanticScore + α × keywordScore`, where α defaults to 0.3 (`chartsearchai.embedding.keywordWeight`). The additive formulation ensures keyword overlap can only increase a record's score, never decrease it. A weighted-average formulation (`(1-α) × semantic + α × keyword`) was tried first but rejected because it suppresses semantic scores by `1-α` when keywords don't match (keyword=0), pushing scores below the absolute similarity floor.

### 4. ★★☆ Add Resource-Type Boosting via Query Classification — ✅ Implemented

**Change:** A `QueryClassifier` maps query keywords to resource types. Each clinical type is mapped independently: "conditions" → `condition`, "diagnoses" → `diagnosis`, "medications" → `order` + `medication_dispense`, "allergies" → `allergy`, "labs" → `obs`, "vitals" → `obs`, "programs" → `program`. The classifier receives the raw query (before stopword removal) to preserve category indicators like "any" and "all". Type boost factor is configurable (`chartsearchai.embedding.typeBoostFactor`, default 1.0 — disabled) to avoid artificial score gaps.

### 5. ★★☆ Two-Phase Retrieval with Auto-Expand for Category Queries — ✅ Implemented

**Change:** Category queries (detected by a category indicator word + a type keyword) use a two-phase retrieval strategy: **Phase 1** includes ALL records of the matched type regardless of topK or score (auto-expand). **Phase 2** fills remaining topK slots with the best non-type-matched records from the semantic adaptive cutoff. Focused queries (no category indicator) use topK as a hard cap with semantic similarity controlling inclusion. No absolute similarity floor is applied to type-matched records in category queries because rare medical terms (e.g., "Granuloma annulare") can produce low cosine similarity against generic category words despite being a perfect type match.

### 6. ★★☆ Configurable Embedding Model Parameters — ✅ Implemented

**Change:** `MAX_SEQUENCE_LENGTH` (default 256) and query prefix (default empty) are configurable via global properties. This supports swapping to alternative embedding models without code changes.

### Future: Switch to a Clinical-Domain Embedding Model

Replace all-MiniLM-L6-v2 with a model that has wider score separation on clinical text.

**Why:** Wider score separation means the threshold logic works more reliably. The difference between relevant and irrelevant records should be 0.2-0.3, not 0.05-0.10.

**Risk:** Requires re-indexing all patients (backfill task already supports this). Larger model file (440MB vs 90MB).

#### Important: Base Language Models vs Sentence Embedding Models

Not all "clinical BERT" models are suitable for retrieval. There are two categories:

- **Base language models** (BiomedBERT, Bio_ClinicalBERT, ClinicalBERT) understand medical text but are NOT trained for sentence similarity or retrieval. Mean-pooled embeddings from these models produce poor cosine similarity distributions. They would require fine-tuning with a contrastive loss before use in this pipeline.
- **Sentence embedding models** (BGE, E5, GTE, MedCPT, Nomic Embed, Jina Embeddings) are specifically trained so that cosine similarity between embeddings indicates semantic similarity. These are drop-in candidates.

Only sentence embedding models should be considered without additional fine-tuning.

#### Candidate Models

**Evaluated and retained: `sentence-transformers/all-MiniLM-L6-v2`** (384 dims, ~90MB)

Three 768-dim alternatives (e5-base-v2, all-mpnet-base-v2, nomic-embed-text-v1.5) were benchmarked in April 2026 and all performed worse on clinical retrieval — see [ADR Decision 19](adr.md#decision-19-retain-all-minilm-l6-v2-as-the-embedding-model) for the full evaluation. The original recommendation for BGE was not tested due to provenance concerns for US-funded deployments.

| Model | Dims | Maintainer | License | Strengths | Compatibility Notes |
|---|---|---|---|---|---|
| **`BAAI/bge-base-en-v1.5`** | 768 | BAAI (org) | MIT | Top MTEB retrieval scores, instruction-aware queries | ✅ Drop-in, single encoder |
| `Alibaba-NLP/gte-base-en-v1.5` | 768 | Alibaba (org) | Apache 2.0 | Very competitive with BGE on benchmarks | ✅ Drop-in, single encoder |
| `intfloat/e5-base-v2` | 768 | Microsoft (org) | MIT | Query/passage prefix support (`"query: "` / `"passage: "`) | ✅ Drop-in, single encoder |
| ~~`ncbi/MedCPT-Query-Encoder`~~ | 768 | NCBI/NIH (gov org) | Apache 2.0 | Medical-specific retrieval model; trained on PubMed queries | ❌ **Evaluated and rejected** — compressed score distributions (IQR ~0.04 vs ~0.10 for all-MiniLM) defeated adaptive filtering, returning the entire dataset for queries like "blood problems". See [ADR Decision 18](adr.md#decision-18-cross-encoder-reranking-stage-superseded) |
| `nomic-ai/nomic-embed-text-v1.5` | 768 | Nomic AI (org) | Apache 2.0 | Matryoshka dimensions (variable size), long context | ✅ Drop-in, single encoder |
| `jinaai/jina-embeddings-v2-base-en` | 768 | Jina AI (org) | Apache 2.0 | 8K token context window | ✅ Drop-in, single encoder |
| `NeuML/pubmedbert-base-embeddings` | 768 | NeuML (individual) | Apache 2.0 | PubMed-trained sentence embeddings | ✅ Drop-in; previously tested (ADR) with first-sentence truncation and rejected — re-test with full text |
| `sentence-transformers/all-mpnet-base-v2` | 768 | Hugging Face (org) | Apache 2.0 | Better than MiniLM on retrieval while still general-purpose | ✅ Drop-in, single encoder |

**Not recommended without fine-tuning** (base language models, not sentence embedding models):

| Model | Maintainer | Why not |
|---|---|---|
| `microsoft/BiomedNLP-BiomedBERT-base` | Microsoft (org) | Base MLM, not trained for sentence similarity |
| `emilyalsentzer/Bio_ClinicalBERT` | Emily Alsentzer (individual) | Base MLM trained on MIMIC-III, no retrieval objective |
| `medicalai/ClinicalBERT` | medicalai (small group) | Base MLM, not trained for sentence similarity |
| `FremyCompany/BioLORD-2023` | FremyCompany (individual) | Biomedical ontology alignment, limited retrieval validation |

#### How to Evaluate

**Key lesson from the April 2026 evaluation:** General retrieval benchmarks (MTEB) do not predict performance on clinical queries. Models that rank higher on MTEB performed significantly worse on this pipeline's medical terminology associations (e.g. "STD" → HIV/Zika, "vital signs" → Temperature). Always benchmark on your own data — the test suite (`LlmInferenceServiceTest`, 259 real-model tests) is the definitive benchmark. Run it with `-Dchartsearchai.embedding.model.dir=/path/to/model` and count failures. See [ADR Decision 19](adr.md#decision-19-retain-all-minilm-l6-v2-as-the-embedding-model) for details.

Before committing to a model, benchmark 2-3 candidates on representative queries from your deployment:

1. **Prepare a test set:** 10-15 queries spanning category lookups ("any conditions?"), specific concepts ("does the patient have diabetes?"), and cross-type queries ("is diabetes controlled?"). Include expected results for each.
2. **Export an ONNX model** for each candidate using `optimum` or `sentence-transformers`:
   ```bash
   pip install optimum[exporters] sentence-transformers
   optimum-cli export onnx --model intfloat/e5-base-v2 e5-base-onnx/
   ```
3. **Swap the model** by replacing `model.onnx`, `tokenizer.json`, and `vocab.txt` in the module's resources.
4. **Re-index** by running the "Chart Search AI - Embedding Backfill" scheduled task.
5. **Run the test suite** with the new model and compare failures. A model that passes all 259 real-model tests is a genuine upgrade. Also compare score separation — a good model should show a clear gap between relevant and irrelevant records.

Set `chartsearchai.embedding.queryPrefix` appropriately for each model:
- BGE: `"Represent this sentence for searching relevant passages: "`
- E5: `"query: "`
- Others: leave empty

### ~~Future: Add a Cross-Encoder Re-Ranking Stage~~ (Superseded)

**Status:** Implemented, benchmarked, and removed.

The cross-encoder (ms-marco-MiniLM-L-6-v2, 85MB ONNX) was benchmarked across 7 queries on a 160-record patient chart. It added no retrieval value: it only reordered records within already-correct result sets (which has no effect since the LLM sees all candidates), and in one case ("blood pressure trend") actively hurt results by truncating 20 BP readings to 10 via its topN cutoff. The 85MB model footprint, ~100ms per-query latency, and deployment complexity were not justified. See [ADR Decision 18](adr.md#decision-18-cross-encoder-reranking-stage-superseded) for the full benchmark table.

### Future: Multi-Vector Embedding per Record

**Change:** Instead of one embedding per record, generate 2-3 embeddings capturing different aspects:
1. **Concept embedding**: Just the concept name/type (what it IS)
2. **Value embedding**: The values/details (what it SAYS)
3. **Full embedding**: The complete serialized text

Query similarity is the max across all vectors for a record.

**Why:** Separating "what" from "content" allows a query for "blood pressure" to match the concept vector perfectly even if the values (120/80) would dilute it.

**Risk:** 2-3x storage and indexing time. Significant code complexity increase.

### Future: LLM-Assisted Query Expansion

**Change:** Before embedding the query, use the local LLM to expand it:

```
Input:    "any medications?"
Expanded: "medications drugs prescriptions drug orders pills tablets dispensed"
```

**Why:** Bridges the vocabulary gap between how clinicians ask questions and how records are stored.

**Risk:** Adds LLM inference latency (~2-5 seconds) to the retrieval step. The LLM is already the bottleneck. Consider using a smaller/faster model or caching expansions.

---

## Implementation Status

Improvements **#1 through #6** have been implemented and are in production. Key design decisions discovered during implementation:

- **Additive keyword bonus, not weighted average.** A weighted-average formula `(1-α)×semantic + α×keyword` suppresses semantic scores when keywords don't match, pushing scores below the absolute floor. The additive formula `semantic + α×keyword` can only help, never hurt.
- **No query prefix for non-instruction models.** Adding `"search_query: "` to queries for all-MiniLM-L6-v2 (which was NOT trained with instruction prefixes) adds noise tokens that dilute short queries by ~50%. Default prefix is empty; opt-in via GP for instruction-aware models.
- **Type boost disabled by default.** A type boost factor > 1.0 inflates the reference score used for threshold computation, creating artificial score gaps. Default is 1.0 (disabled).
- **Classifier receives raw query.** Category indicator words ("any", "all", "what") overlap with stopwords. The classifier must see the original query before stopword removal.
- **Conditions and diagnoses decoupled.** Mapping both to the same classifier target caused them to compete for topK slots. Each is now an independent type.
- **Auto-expand for category queries.** Type-matched records in category queries are not subject to topK. The user explicitly asked for "any/all" of a type — capping results defeats the purpose.

The remaining future improvements (multi-vector embedding, LLM-assisted query expansion) are more invasive and should only be pursued if the current tier doesn't sufficiently improve quality. Note: the clinical-domain embedding model avenue was explored with MedCPT and rejected — see the candidate model table above for details.
