# Semantic retrieval — where it lives now (retrieval moved to querystore)

> **Status: superseded.** This document once held ChartSearchAI's in-process semantic-retrieval
> improvement plan (an embedding pre-filter, `LlmInferenceService.findSimilar`, that decided which
> chart records to feed the LLM). That subsystem **no longer exists in this module**. It has been
> rewritten to record where retrieval lives now and what carried forward, so nobody re-opens a plan
> against deleted code. The original analysis is recoverable from git history if needed.

## What changed

Two architectural moves retired the old pre-filter:

1. **Retrieval is owned by [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore)
   (issue #51).** ChartSearchAI no longer has an in-process embedding / Lucene / Elasticsearch
   pipeline, scoring/ranking code, an embedding store, or a retrieval eval harness (see this module's
   `CLAUDE.md`). Retrieval *and retrieval-quality evaluation* belong to querystore — it owns the index
   and the embedder.
2. **The chat path builds a complete evidence ledger, then applies an exact context budget.**
   med-agent-hub can obtain patient records from Querystore, inline chart input, or another source
   adapter. Small charts remain byte-identical and complete. Oversized charts are reduced by the
   hub's deterministic selector, while temporal and safety checks continue to use the complete
   ledger. There is no learned semantic top-K selector in the current answer path.

Net: the old embedding threshold was removed from the chat path. Querystore owns its search/index
capabilities; med-agent-hub owns answer-time context supply and deterministic selection. A future
learned reranker may operate behind the hub's selector contract, but it is not part of this iteration.

## Where retrieval quality lives now

For Querystore's retrieval backends, its ADR (`targets/querystore/docs/adr.md`) is the source of truth:

- **Hybrid retrieval** — BM25 + kNN with rank-based **RRF fusion** (`BackendStore.hybrid`), across
  pluggable **MySQL / Lucene / Elasticsearch** backends (Decision 3).
- **BM25 companion channels** — synonyms / description / mapping_names as separately-boosted text
  fields (carried forward from this module's earlier ADR Decision 18 experiments).
- **Embedder choice + evaluation** — querystore owns the embedder (`EmbeddingProvider`, single-encoder
  models such as multilingual-e5 or all-MiniLM-L6-v2) and the multi-patient eval that ratifies changes.

Answer-time context selection belongs in med-agent-hub. Indexing, embedding, and search-backend work
belongs in Querystore. ChartSearchAI should not regain either responsibility.

## The one durable lesson worth keeping

From the April 2026 embedding evaluation that informed this migration — still true, and it should
gate any future embedder change in querystore:

> **General retrieval benchmarks (MTEB) do not predict performance on clinical queries.** Models that
> ranked higher on MTEB performed *worse* on this pipeline's medical-terminology associations (e.g.
> "STD" → HIV/Zika, "vital signs" → Temperature). A clinically-named "clinical BERT" is not
> automatically a good retriever either: base MLMs (BiomedBERT, Bio_ClinicalBERT) are not trained for
> sentence similarity, and a medical retrieval model (MedCPT) was rejected for compressed score
> distributions that defeated threshold-based filtering. **Always benchmark candidate embedders on
> your own clinical data, with expected results, before switching** — score *separation* between
> relevant and irrelevant records matters as much as any leaderboard rank.

## Related

- Drug-reference / drug-safety: see [drug-knowledge-base-comparison.md](drug-knowledge-base-comparison.md)
  (also ported out of this module — to med-agent-hub).
- The historical embedding decisions (all-MiniLM retention, cross-encoder reranking removal) live in
  [adr.md](adr.md) Decisions 18/19 and remain valid as a record of what was tried.
