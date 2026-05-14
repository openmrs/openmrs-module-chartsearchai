# Chart Search AI Module

[![Download Standalone](https://img.shields.io/badge/Download-O3_Standalone_with_Chart_Search_AI-blue?style=for-the-badge)](https://nightly.link/openmrs/openmrs-module-chartsearchai/workflows/build-standalone.yml/main/openmrs-standalone-chartsearchai.zip)

An OpenMRS module that lets clinicians ask natural language questions about a patient's chart and get answers with source citations.

For project background, community discussion, and roadmap, see the [wiki project page](https://openmrs.atlassian.net/wiki/spaces/projects/pages/373325839/Chart+Search+aka+ChartSearchAI).

The standalone download above includes the backend module, frontend ESM, and the following AI models — ready to run:

- **LLM**: [Gemma 4 26B MoE Instruct (UD-Q4_K_M)](https://huggingface.co/unsloth/gemma-4-26B-A4B-it-GGUF) — 17 GB, for answering clinical questions
- **Embedding**: [all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) — ONNX model + vocab, for semantic search over patient records

## Table of Contents

- [Try it on the demo server](#try-it-on-the-demo-server)
- [Requirements](#requirements)
- [Docker](#docker)
- [Setup](#setup)
  - [1. Build](#1-build)
  - [2. Download the LLM model](#2-download-the-llm-model-local-mode-only)
  - [3. Download the embedding model](#3-download-the-embedding-model)
  - [4. Install](#4-install)
  - [5. Configure](#5-configure)
  - [6. Grant privileges](#6-grant-privileges)
  - [7. Indexing](#7-indexing)
  - [Testing the Elasticsearch pipeline locally](#testing-the-elasticsearch-pipeline-locally)
- [Query behavior](#query-behavior)
- [API](#api)
  - [Search](#search)
  - [Streaming search (SSE)](#streaming-search-sse)
  - [Feedback](#feedback)
  - [Audit log](#audit-log)
- [Patient access control](#patient-access-control)
- [Evals](#evals)
- [Evaluated models](#evaluated-models)
- [Architecture](#architecture)
- [License](#license)

## Try it on the demo server

A live demo runs at **https://chartsearchai.openmrs.org** with the standard O3 reference patient set, so you can try Chart Search AI without installing anything.

1. Open https://chartsearchai.openmrs.org and log in (default credentials: `admin` / `Admin123`).
2. Click the magnifying-glass icon in the top header and search for **Betty Williams** — she is the reference patient with the most data on the demo (medications, vitals, conditions), so the AI has something to ground its answers in. Open her chart from the dropdown.

   ![Patient search overlay with "Betty" typed and Betty Williams in the result list](docs/images/ai-chart-search-patient-search.png)

3. Click the floating blue AI sparkle icon in the bottom-right corner of the chart (tooltip: *Ask AI about this patient*). A chat panel slides in.
4. Type a clinical question — e.g. *What medications is this patient on?*, *Any allergies?*, *Last 3 blood pressure readings* — and press **Send**, or click the microphone for voice input.
5. The answer streams in token-by-token. The records the answer is grounded in appear under **References**, numbered to match the inline citations (`[1]`, `[2]`, …). Both the inline citations and the chips under **References** are clickable — they navigate to the relevant chart tab (Orders, Results, Allergies, Conditions, Programs, etc.) and highlight the source record. Every response carries the AI-generated disclaimer.

   ![AI Chart Search panel showing an answer with numbered citations on Betty Williams' chart](docs/images/ai-chart-search-demo.png)

6. Optionally rate the answer under **Was this helpful?** with **Helpful** / **Not helpful** and an optional comment. Feedback is recorded in the audit log alongside the question.

Notes:

- The AI button is only rendered for users with the **AI Query Patient Data** privilege.
- The launch surface is configurable via the frontend `chatLaunchMode` setting: `floating` (the bottom-right circular button used above), `workspace` (an icon in the top-right workspace strip that opens the chat as a docked workspace), or `both` (default).
- First-query latency on the demo reflects the remote provider's cold-start. The chart-open prompt-cache warmup (`chartsearchai.warmupEnabled`) is a no-op for remote engines — it only helps local llama-server deployments.
- The demo currently calls a remote LLM, since the server doesn't yet have the RAM and CPU headroom to comfortably run a local model like Gemma 4 E4B; latency on the demo therefore reflects the remote provider, not local CPU inference.

## Requirements

- Java 11+
- OpenMRS Platform 2.8.0+
- Webservices REST module 2.44.0+
- RAM for local LLM inference (not required when using a remote LLM):
  - **~6–8GB RAM** for the module's default model — Gemma 4 E4B (~5GB GGUF). Suitable for most deployments adding the module to an existing OpenMRS site.
  - **~24GB+ RAM recommended** for the production-grade Gemma 4 26B MoE bundled with the standalone download.
- Elasticsearch 8.14+ *(optional, for the hybrid retrieval pipeline; the default embedding and Lucene pipelines require no external services)*

## Docker

```bash
git clone https://github.com/openmrs/openmrs-module-chartsearchai.git
cd openmrs-module-chartsearchai
docker compose up --build
```

No JDK or model downloads needed — the Docker build handles everything. On first start, the embedding model (~86MB) and the default LLM (Gemma 4 E4B, ~5GB) are downloaded automatically from HuggingFace and persisted in a Docker volume.

First startup takes 5–15 minutes (model downloads + database initialization). Once the logs show that OpenMRS has started, open http://localhost/openmrs/spa (default credentials: `admin` / `Admin123`). Subsequent starts are fast since the data volume persists.

Alternatively, download the [O3 Standalone with Chart Search AI](https://nightly.link/openmrs/openmrs-module-chartsearchai/workflows/build-standalone.yml/main/openmrs-standalone-chartsearchai.zip) — a single zip with everything included, no Docker required (Java 21+ needed). See the [OpenMRS Standalone guide](https://openmrs.atlassian.net/wiki/spaces/docs/pages/25472583/OpenMRS+Standalone) for instructions.

## Setup

### 1. Build

```
mvn package
```

The `.omod` file is in `omod/target/`.

### 2. Download the LLM model *(local mode only)*

> **Skip this step** if you plan to use a remote LLM (see [LLM engine](#llm-engine) below).

The module's default `chartsearchai.llm.modelFilePath` points to **Gemma 4 E4B Instruct (Q4_K_M, ~5GB)** — `chartsearchai/gemma-4-E4B-it-Q4_K_M.gguf`. Download it from [unsloth/gemma-4-E4B-it-GGUF](https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF) if you intend to keep the default.

For production hardware (~24GB+ RAM), upgrade to **Gemma 4 26B MoE Instruct (UD-Q4_K_M, ~17GB)** — the model the standalone download bundles. Available from [unsloth/gemma-4-26B-A4B-it-GGUF](https://huggingface.co/unsloth/gemma-4-26B-A4B-it-GGUF). After downloading, update `chartsearchai.llm.modelFilePath` to point to the new filename.

Place whichever `.gguf` you choose inside the OpenMRS application data directory (e.g., `<openmrs-application-data-directory>/chartsearchai/`). Model paths are resolved relative to this directory for security.

**Recommended models for local inference:**

| Model | RAM Needed | Chat Template | Download |
|-------|-----------|---------------|----------|
| Llama 3.2 3B | ~6GB total | `llama3` | [GGUF](https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF) |
| MedGemma 1.5 4B | ~6–8GB total | `gemma` | [GGUF](https://huggingface.co/unsloth/medgemma-1.5-4b-it-GGUF) |
| **Gemma 4 E4B** *(module install default)* | ~6–8GB total | `gemma` | [GGUF](https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF) |
| Llama 3.3 8B | ~10GB total | `llama3` | [GGUF](https://huggingface.co/bartowski/Llama-3.3-8B-Instruct-GGUF) |
| Gemma 3 12B | ~12GB total | `gemma` | [GGUF](https://huggingface.co/bartowski/google_gemma-3-12b-it-GGUF) |
| Mistral Nemo 12B | ~12GB total | `mistral` | [GGUF](https://huggingface.co/bartowski/Mistral-Nemo-Instruct-2407-GGUF) |
| **Gemma 4 26B MoE** *(standalone bundle, recommended for production)* | ~18–22GB total | `gemma` | [GGUF](https://huggingface.co/unsloth/gemma-4-26B-A4B-it-GGUF) |
| Gemma 4 31B | ~20–24GB total | `gemma` | [GGUF](https://huggingface.co/bartowski/google_gemma-4-31B-it-GGUF) |

To switch models, update `chartsearchai.llm.modelFilePath` — no rebuild needed. The embedded llama-server detects the model's chat template automatically. See [Evaluated models](#evaluated-models) for a full comparison of all models tested, including size trade-offs and licensing.

Gemma 4 26B MoE is recommended for production deployments because it follows the system prompt rules (never infer, cite every record, complete enumeration on list queries) reliably without needing reasoning as a safety scaffold. Smaller models work but trade off either safety or list completeness depending on the query. The MoE architecture activates only ~3.8B parameters per token, so per-token speed is comparable to a 4B dense model despite the 26B total size.

### 3. Download the embedding model

If embedding pre-filtering is enabled (`chartsearchai.embedding.preFilter=true`), download the all-MiniLM-L6-v2 ONNX model (~90MB) from [Hugging Face](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2). You need both `model.onnx` and `vocab.txt` from the repository. The default is full-chart mode, which does not require these files.

Place them alongside the LLM model (e.g., `<openmrs-application-data-directory>/chartsearchai/`).

### 4. Install

Copy the `.omod` file into the `modules` folder of the OpenMRS application data directory (e.g., `<openmrs-application-data-directory>/modules/`). The module will be loaded on the next OpenMRS startup.

### 5. Configure

Set these global properties in **Admin > Settings**:

#### LLM engine

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.llm.engine` | `local` | LLM inference engine: `local` manages an embedded llama-server subprocess for GGUF model inference; `remote` calls an external OpenAI-compatible API |

**Local engine** (default) — requires a downloaded GGUF model file (see step 2):

| Property | Description |
|----------|-------------|
| `chartsearchai.llm.modelFilePath` | Relative path (within the OpenMRS application data directory) to the `.gguf` model file. Default: `chartsearchai/gemma-4-E4B-it-Q4_K_M.gguf`. Set to your downloaded model's filename — e.g. `chartsearchai/gemma-4-26B-A4B-it-UD-Q4_K_M.gguf` if you upgraded to 26B MoE. |

**Remote engine** — set `chartsearchai.llm.engine` to `remote` and configure:

| Property | Where | Description |
|----------|-------|-------------|
| `chartsearchai.llm.remote.endpointUrl` | Global property | Chat completions endpoint URL (e.g. `http://localhost:11434/v1/chat/completions` for Ollama, `http://gpu-server:8000/v1/chat/completions` for vLLM, `https://api.openai.com/v1/chat/completions` for OpenAI, `https://api.anthropic.com/v1/chat/completions` for Anthropic) |
| `chartsearchai.llm.remote.apikey` | `openmrs-runtime.properties` | API key for authentication (sent as `Bearer` token). Stored in runtime properties instead of the database for security. Optional — omit for self-hosted servers that don't require auth |
| `chartsearchai.llm.remote.modelName` | Global property | Model identifier (e.g. `llama3.3` for Ollama, `meta-llama/Llama-3.3-8B-Instruct` for vLLM, `gpt-4o` for OpenAI, `claude-opus-4-7` for Anthropic) |

The API key is read from `openmrs-runtime.properties` (not from the database) so it is never exposed in the Admin UI or database backups. Add it to your runtime properties file:

```
chartsearchai.llm.remote.apikey=sk-your-api-key-here
```

The remote engine works with any server that implements the OpenAI chat completions API format, including self-hosted inference servers (vLLM, Ollama, text-generation-inference) and cloud providers (OpenAI, Azure OpenAI, Google AI, Anthropic). Self-hosted servers keep patient data on-premise while still benefiting from GPU-accelerated inference. No GGUF model download is needed when using the remote engine.

For Anthropic's OpenAI-compat endpoint, point `chartsearchai.llm.remote.endpointUrl` at it and set `chartsearchai.llm.remote.modelName` to a Claude model identifier (e.g. `claude-opus-4-7`). The module emits Anthropic-compatible request bodies automatically: `response_format: json_schema` (Anthropic's compat endpoint rejects `json_object`) and, on Claude Opus 4.7, `top_k: 1` instead of `temperature` (Anthropic deprecated `temperature`/`top_p` on that model). Other Claude models (Opus 4.5/4.6, Haiku 4.5) keep using `temperature: 0`.

#### Retrieval pipeline

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.embedding.preFilter` | `false` | When `true`, uses the selected retrieval pipeline to narrow patient records to the most relevant ones before sending to the LLM. The default is `false` (full chart) — pre-filtering is faster on huge charts but can omit records the LLM needs for negative reasoning (e.g. correctly answering "any allergies?" requires having seen the empty allergy section, not just an absence of matches in the filtered set). Enable only when context-window size is the binding constraint |
| `chartsearchai.retrieval.pipeline` | `embedding` | Selects the retrieval pipeline: `embedding` (default) uses vector similarity via an ONNX model with custom scoring; `lucene` uses Apache Lucene BM25 text search; `hybrid` combines Lucene BM25 and embedding kNN search using Reciprocal Rank Fusion (RRF) — same quality as the Elasticsearch pipeline but with no external services required; `elasticsearch` uses Elasticsearch hybrid search combining BM25 text and kNN vector search via RRF (requires Elasticsearch 8.14+ configured in OpenMRS). All require `preFilter` to be `true`. Records are indexed automatically on first access. Changing this setting takes effect on the next query |

#### Embedding pipeline tuning

These settings apply when `chartsearchai.retrieval.pipeline` is `embedding` (the default). The Elasticsearch pipeline also uses `scoreGapMultiplier`, `minScoreGap`, `gapValidationCosineThreshold`, `keywordWeight`, and `similarityRatio` in its post-retrieval filter pipeline. They have no effect on the Lucene or hybrid pipelines.

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.embedding.topK` | `10` | Maximum number of records sent to the LLM per query. When the query mentions a specific clinical type (e.g., "medications", "allergies", "lab results"), all records of that type are included regardless of topK, and remaining slots are filled with contextual records from other types. For other queries, topK is applied only when some candidates lack keyword matches; when every candidate has a keyword match, topK is bypassed because gap detection and ratio filtering already identified the relevant cluster. Type detection uses keyword matching — for example, "medications" and "drugs" both match drug orders, while "blood pressure" and "bp" both match observations |
| `chartsearchai.embedding.similarityRatio` | `0.80` | Minimum similarity score as a fraction of the top result's score. Records scoring below this ratio are excluded even if within the topK limit. Must be between 0 and 1 |
| `chartsearchai.embedding.scoreGapMultiplier` | `2.5` | Controls adaptive topK by detecting natural cluster boundaries in similarity scores. Higher values include more records; lower values cut more aggressively. Set to a very large value (e.g. 999) to disable gap detection |
| `chartsearchai.embedding.minScoreGap` | `0.10` | Minimum absolute gap between consecutive similarity scores required for the adaptive cutoff detector to trigger. Prevents premature cutting when a relatively large gap (compared to a tight cluster's running average) is still small in absolute terms. Only applies when gap detection is active |
| `chartsearchai.embedding.gapValidationCosineThreshold` | `0.47` | Cosine similarity threshold for validating whether a detected gap is intra-topic or inter-topic. When the average cosine between records above and below the gap meets or exceeds this value, the gap is considered intra-topic and the cut is skipped. Must be between 0 and 1 |
| `chartsearchai.embedding.keywordWeight` | `0.3` | Additive keyword bonus weight in the hybrid retrieval formula: `finalScore = semanticScore + weight × keywordScore`. Keyword overlap can only increase the score, never decrease it. Set to `0` to disable keyword matching |
| `chartsearchai.embedding.typeBoostFactor` | `1.0` | Score multiplier applied to records whose resource type matches the query intent (e.g., drug orders when the query is about medications). Set to `1.0` to disable type boosting (default). Values like `1.2`–`1.5` provide moderate boosting. Must be between 1.0 and 3.0 |
| `chartsearchai.embedding.queryPrefix` | *(empty)* | Prefix prepended to the user query before embedding. Leave empty for models like all-MiniLM-L6-v2 that were not trained with instruction prefixes. Set to `search_query: ` or `Represent this sentence for searching relevant passages: ` for models that support instruction-aware queries (e.g., BGE) |
| `chartsearchai.embedding.maxSequenceLength` | `256` | Maximum WordPiece token sequence length for embedding input. Increase when using models that support longer contexts (e.g., 512 for BGE models). Must be between 32 and 8192 |
| `chartsearchai.embedding.modelFilePath` | — | Required when using the embedding, hybrid, or elasticsearch pipeline. Relative path to the ONNX model file (all-MiniLM-L6-v2), e.g. `chartsearchai/all-MiniLM-L6-v2.onnx`. Not needed for the Lucene pipeline |
| `chartsearchai.embedding.queryModelFilePath` | *(empty)* | Optional separate query-encoder ONNX model for dual-encoder architectures (e.g. MedCPT). When set, queries are embedded with this model while records use `chartsearchai.embedding.modelFilePath`. Leave empty to use a single encoder for both. Example for MedCPT: `chartsearchai/MedCPT/Query-Encoder/model.onnx` |
| `chartsearchai.embedding.vocabFilePath` | — | Required when using the embedding, hybrid, or elasticsearch pipeline. Relative path to the WordPiece `vocab.txt` file, e.g. `chartsearchai/vocab.txt`. Not needed for the Lucene pipeline |

#### LLM tuning

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.llm.systemPrompt` | *(built-in clinical prompt)* | System prompt that guides how the LLM responds — e.g. answering only the question asked, using only the provided patient records, citing records by number, naming what is missing when records lack relevant information (e.g. "There are no records about diabetes in this patient's chart"), keeping answers concise, and returning structured JSON |
| `chartsearchai.llm.timeoutSeconds` | `300` | Maximum seconds to wait for LLM inference before timing out |
| `chartsearchai.llm.idleTimeoutMinutes` | `30` | *(Local engine only)* Minutes of inactivity after which the embedded llama-server is stopped to free RAM. It is automatically restarted on the next query. Set to `0` to keep it running indefinitely |
| `chartsearchai.llm.serverPort` | `18085` | *(Local engine only)* Port for the embedded llama-server. Change if the default conflicts with another service |
| `chartsearchai.llm.contextSize` | `32768` | *(Local engine only)* Context window size in tokens for the embedded llama-server. The system prompt + serialized chart + question must fit within this. Larger values let bigger charts pass through full-chart mode but increase the KV cache memory footprint roughly linearly. Increase if you see "Patient chart exceeds the LLM context window" (HTTP 413) and have headroom for a larger KV cache; reduce on memory-constrained hardware |
| `chartsearchai.warmupEnabled` | `true` | When `true`, opening a patient chart triggers a background warmup that primes the LLM prompt cache (system prompt + serialized chart) so the first AI query on that patient skips the full prefill cost. No-op when `chartsearchai.llm.engine` is `remote` (remote providers manage their own caching) and when `chartsearchai.embedding.preFilter` is `true` (the prompt prefix varies per query, so a chart-only warmup cannot be reused) |

#### Rate limiting and caching

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.rateLimitPerMinute` | `10` | Maximum queries per user per minute. Set to `0` to disable |
| `chartsearchai.cacheTtlMinutes` | `0` | Minutes to cache identical (patient, question) answers. Set to `0` to disable (default) |

#### Audit

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.auditLogRetentionDays` | `90` | Audit log entries older than this are purged daily. Set to `0` to retain all |

### 6. Grant privileges

| Privilege | Purpose |
|-----------|---------|
| **AI Query Patient Data** | Execute chart search queries |
| **View AI Audit Logs** | Access the audit log endpoint |

### 7. Indexing

When `chartsearchai.embedding.preFilter` is `true`, patient records are automatically indexed on first chart access for whichever retrieval pipeline is active. Subsequent data changes trigger automatic re-indexing via AOP hooks on encounter, obs, condition, diagnosis, allergy, order, program enrollment, medication dispense, and patient merge operations. The default (`false`) skips indexing — full-chart mode does not need an embedding index.

**Embedding pipeline** (default): Uses an ONNX embedding model for vector similarity search. A bulk backfill task (**"Chart Search AI - Embedding Backfill"**) is available in **Admin > Scheduler > Manage Scheduler** to pre-index all patients. The default model is all-MiniLM-L6-v2 (general-purpose, 384 dimensions). Any BERT-based ONNX embedding model can be used as a drop-in replacement by updating `chartsearchai.embedding.modelFilePath` and `chartsearchai.embedding.vocabFilePath`. Embedding dimensions are auto-detected from the model output, so models with any dimension size work without code changes. After switching models, existing embeddings are incompatible — run the backfill task to re-index all patients with the new model.

**Lucene pipeline** (`chartsearchai.retrieval.pipeline=lucene`): Uses Apache Lucene BM25 text search with English stemming. No ONNX model files are required. The Lucene index is stored at `<openmrs-application-data-directory>/chartsearchai/lucene-index/` and is built automatically on first patient access. This pipeline is simpler to set up (no model download needed) and may be preferred for environments where the ONNX model is unavailable.

**Hybrid pipeline** (`chartsearchai.retrieval.pipeline=hybrid`): Combines Lucene BM25 text search with embedding kNN semantic search using Reciprocal Rank Fusion (RRF), the same algorithm used by the Elasticsearch pipeline. Provides Elasticsearch-quality hybrid retrieval without requiring any external services — everything runs in-process. Requires the ONNX embedding model (same as the embedding pipeline) for the kNN side. Both the Lucene index and embedding vectors are built automatically on first patient access. This is the best option when you want hybrid BM25+semantic search quality but don't have an Elasticsearch cluster.

**Elasticsearch pipeline** (`chartsearchai.retrieval.pipeline=elasticsearch`): Uses Elasticsearch hybrid search combining BM25 text search with kNN dense vector search via Reciprocal Rank Fusion (RRF). Requires Elasticsearch 8.14+ configured in OpenMRS (set `hibernate.search.backend.uris` in runtime properties). Also requires the ONNX embedding model (same as the embedding pipeline) to compute vectors for the kNN side of the hybrid search. Patient records are indexed into a shared `chartsearchai-patient-records` Elasticsearch index with both text and embedding vector fields. The RRF algorithm fuses rankings from both signals — this means queries like "any cancer?" can find semantic matches (e.g. Kaposi sarcoma) via kNN even when the literal term is absent from the records, while also benefiting from BM25's lexical matching. If Elasticsearch is not available at query time, the pipeline automatically falls back to the embedding pipeline. After switching embedding models, delete the `chartsearchai-patient-records` index from Elasticsearch — it will be recreated with the new model's dimensions on the next patient access.

**Choosing a pipeline:**

| Consideration | Embedding *(default)* | Lucene | Hybrid | Elasticsearch |
|--------------|----------------------|--------|--------|---------------|
| External dependencies | ONNX model files only | None | ONNX model files only | Elasticsearch 8.14+ cluster + ONNX model files |
| Semantic matching (e.g., "cancer" finds "Kaposi sarcoma") | Yes | No | Yes (via kNN) | Yes (via kNN) |
| Absent-data detection (returns "no records about X" instead of false positives) | Yes (z-score gate) | No | No | Yes (via post-filter pipeline) |
| Type-aware auto-expand (e.g., "any conditions?" returns all conditions) | Yes | No | No | No |
| Adaptive result filtering (gap detection, similarity ratio) | Yes | No | No | Yes (post-retrieval filter pipeline) |
| Keyword matching | Yes (hybrid scoring) | Yes (BM25 with stemming) | Yes (BM25 + kNN via RRF) | Yes (BM25 + kNN via RRF) |
| Tunable parameters | Many (topK, similarityRatio, scoreGapMultiplier, keywordWeight, etc.) | Few (topK only) | Few (topK only) | Few (topK only; scoring delegated to Elasticsearch) |
| Compute location | In-process (JVM) | In-process (JVM) | In-process (JVM) | Elasticsearch cluster |
| Graceful fallback | N/A (default) | Falls back to full chart on error | Falls back to full chart on error | Falls back to embedding pipeline |

The **embedding pipeline** is recommended for most deployments — it runs entirely in-process, has the most sophisticated filtering (z-score gate for absent-data detection, gap detection for adaptive result cutoff, type-aware expansion), and requires no external services. The **Lucene pipeline** is the simplest option when the ONNX model is unavailable, but lacks semantic understanding. The **hybrid pipeline** combines Lucene BM25 with embedding kNN via RRF, but it underperforms the embedding pipeline on the eval dataset because its fixed-size `topK` output cannot adapt: it always returns exactly `topK` records, failing on adversarial queries (can't return empty) and broad queries like blood pressure where more than `topK` records are relevant. The embedding pipeline's adaptive filtering (gap detection, floor gates, type-aware expansion) handles these cases. The **Elasticsearch pipeline** is best when you already have an ES cluster in your infrastructure and want to offload retrieval compute. ES results are post-filtered through the same scoring and gap detection pipeline as the embedding pipeline, so queries like "any cancer?" return only genuinely relevant records (e.g. Kaposi sarcoma) rather than the full RRF result set.

### Testing the Elasticsearch pipeline locally

The module auto-detects whether the backend is Elasticsearch or OpenSearch and adapts its queries accordingly. **OpenSearch is recommended** because RRF is free; Elasticsearch requires a paid Platinum or Enterprise subscription for RRF.

To test the Elasticsearch pipeline with the OpenMRS SDK:

**1. Start OpenSearch 2.19+ (recommended) or Elasticsearch 8.14+ with Docker:**

OpenSearch (RRF is free):

```
docker run -d --name opensearch \
  -p 9200:9200 \
  -e "discovery.type=single-node" \
  -e "DISABLE_SECURITY_PLUGIN=true" \
  opensearchproject/opensearch:2.19.0
```

Install the **analysis-phonetic** plugin (required by the OpenMRS platform for Soundex-based person name search):

```
docker exec opensearch bin/opensearch-plugin install analysis-phonetic
docker restart opensearch
```

<details>
<summary>Alternatively, use Elasticsearch (requires paid license for RRF)</summary>

```
docker run -d --name elasticsearch \
  -p 9200:9200 \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  elasticsearch:8.17.2
```

```
docker exec elasticsearch bin/elasticsearch-plugin install analysis-phonetic
docker restart elasticsearch
```

Start a 30-day trial to enable RRF:

```
curl -X POST 'http://localhost:9200/_license/start_trial?acknowledge=true'
```

</details>

Verify it's running: `curl http://localhost:9200/_cluster/health`

**2. Configure OpenMRS to use Elasticsearch:**

Add to your OpenMRS runtime properties file (e.g., `~/openmrs/openmrs-runtime.properties`):

```
hibernate.search.backend.type=elasticsearch
hibernate.search.backend.analysis.configurer=elasticsearchConfig
hibernate.search.backend.uris=http://localhost:9200
hibernate.search.backend.discovery.enabled=false
```

> **Notes:**
> - The `analysis.configurer` must match the backend type — use `elasticsearchConfig` for Elasticsearch and `luceneConfig` for Lucene (the default). If you see `Unknown filter type [phonetic]` errors, the `analysis-phonetic` plugin is missing from your Elasticsearch instance.
> - Set `discovery.enabled=false` when running a single local node. When enabled, Hibernate Search may discover and connect to internal Docker network IPs (e.g., `172.17.x.x`) that are unreachable from the host, causing `Timeout connecting` errors.

Or if using the SDK with Docker, pass the environment variable when running the server:

```
OMRS_SEARCH=elasticsearch mvn openmrs-sdk:run
```

**3. Set the retrieval pipeline:**

In **Admin > Settings**, set:

| Property | Value |
|----------|-------|
| `chartsearchai.retrieval.pipeline` | `elasticsearch` |

Also ensure the ONNX embedding model and vocab files are configured (same as the default embedding pipeline).

**4. Query a patient** — records are indexed automatically on first access. To verify indexing, check the ES index:

```
curl http://localhost:9200/chartsearchai-patient-records/_count
```

### Elasticsearch unavailability

If Elasticsearch is unreachable (not running, network issue, misconfigured URI), the module continues to work normally:

- **Startup:** The module starts successfully without checking Elasticsearch connectivity. The client is created lazily on first use.
- **Queries:** Each query calls `GET /_cluster/health` to check availability. If the check fails, the query automatically falls back to the embedding pipeline. No error is returned to the caller — users still get search results.
- **Indexing:** When patient data changes (new obs, conditions, orders, etc.), the module attempts to re-index in Elasticsearch. If the connection fails, the error is logged and swallowed — the data change proceeds normally.
- **Recovery:** There is no retry or circuit-breaker logic. Each request independently checks availability, so if Elasticsearch comes back online, the next query automatically uses it.

In short, the Elasticsearch pipeline is a best-effort enhancement. The module never fails because of Elasticsearch — it silently degrades to the embedding pipeline and silently recovers when Elasticsearch becomes available again.

**5. To reset and re-index**, delete the ES index:

```
curl -X DELETE http://localhost:9200/chartsearchai-patient-records
```

Records will be re-indexed on the next patient access.

## Query behavior

### Absent-data detection

When the embedding pipeline is active and a query has no keyword matches in the patient's records (e.g., asking "any cancer?" for a patient with no cancer-related records), the system uses a z-score gate to detect whether the top semantic match is a genuine result or just noise. If the patient has 30+ records and the best semantic score is not a statistical outlier (z-score < 1.5), the query returns "There are no records about [topic] in this patient's chart" instead of false positives. This prevents the system from returning unrelated records that happen to have slightly elevated similarity scores.

### Recency cap

Questions with numeric recency constraints are automatically detected and honored. For example, "last 3 blood pressure readings" or "most recent 5 lab results" will cap the results per concept group to the specified number, keeping only the most recent measurements. This applies across all retrieval pipelines.

### Input validation

Questions are checked against common prompt injection patterns (e.g., "ignore previous instructions", "you are now", "system prompt:") and rejected with HTTP 400 if matched. This is a defense-in-depth measure — the primary protection is the structured-output constraint (`response_format: json_schema`, sent by both engines and shared via `ChartAnswerResponseFormat`; the local llama-server enforces it via a derived GBNF grammar internally, and remote OpenAI-compat providers enforce it server-side) that forces LLM output into a fixed `{answer, citations}` shape regardless of prompt content. Normal clinical questions containing words like "ignore" or "instructions" in non-adversarial contexts (e.g., "What instructions were given at discharge?") are not affected.

## API

### Search

```
POST /ws/rest/v1/chartsearchai/search
Content-Type: application/json

{
  "patient": "patient-uuid-here",
  "question": "What medications is this patient on?"
}
```

Response:

```json
{
  "answer": "The patient is currently on Metformin [1] and Lisinopril [3]...",
  "disclaimer": "This response is AI-generated and may not be accurate...",
  "questionId": "42",
  "references": [
    { "index": 3, "resourceType": "order", "resourceUuid": "a8f5f167-4ee2-4d2a-94f9-3f3f86d2e9b6", "date": "2025-03-15" },
    { "index": 1, "resourceType": "order", "resourceUuid": "5946f880-b197-400b-9caa-a3c661d71165", "date": "2025-01-10" }
  ]
}
```

`questionId` is a string identifier for this query, used to submit feedback (see below). It is omitted if audit logging fails.

### Streaming search (SSE)

For real-time token-by-token streaming:

```
POST /ws/rest/v1/chartsearchai/search/stream
Content-Type: application/json
Accept: text/event-stream

{
  "patient": "patient-uuid-here",
  "question": "What medications is this patient on?"
}
```

SSE events:

| Event | Description |
|-------|-------------|
| `token` | A chunk of the answer text as it is generated |
| `done` | Final JSON with the complete answer, references (sorted most recent first, with `index`, `resourceType`, `resourceUuid`, `date`), `questionId`, and disclaimer |
| `error` | Error message if something goes wrong |

### Warmup

Pre-warms the LLM prompt cache for a patient's chart so the first AI query skips the full prefill cost. The frontend should call this when a patient chart is opened. Returns `202 Accepted` immediately; the warmup runs on a background daemon thread. Requires the **"AI Query Patient Data"** privilege.

```
POST /ws/rest/v1/chartsearchai/warmup
Content-Type: application/json

{
  "patient": "patient-uuid-here"
}
```

No-op when `chartsearchai.llm.engine` is `remote` and when `chartsearchai.embedding.preFilter` is `true`. Disable entirely with `chartsearchai.warmupEnabled=false`. Concurrent warmups for different patients are coalesced — only the most recently submitted patient runs, since llama-server processes one request at a time.

### Feedback

Submit user feedback (thumbs up/down) for an AI response. Requires the **"AI Query Patient Data"** privilege.

```
POST /ws/rest/v1/chartsearchai/feedback
Content-Type: application/json

{
  "questionId": "42",
  "rating": "positive",
  "comment": "Accurate and helpful"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `questionId` | Yes | The `questionId` from the search response |
| `rating` | Yes | `"positive"` or `"negative"` |
| `comment` | No | Optional text (max 500 characters, truncated if longer) |

Users can only submit feedback on their own queries. Submitting again overwrites the previous feedback.

### Audit log

Requires the **"View AI Audit Logs"** privilege.

```
GET /ws/rest/v1/chartsearchai/auditlog?patient=...&user=...&fromDate=...&toDate=...&startIndex=0&limit=50
```

All query parameters are optional. `fromDate` and `toDate` are epoch milliseconds. Returns paginated results ordered by most recent first, with a `totalCount` for pagination. Each entry includes `rating` and `feedbackComment` fields (null if no feedback was submitted).

## Patient access control

By default, any user with the **"AI Query Patient Data"** privilege can query any patient. To add patient-level restrictions (e.g., location-based or care-team-based), provide a custom Spring bean that implements the `PatientAccessCheck` interface:

```xml
<bean id="chartSearchAi.patientAccessCheck"
      class="com.example.LocationBasedPatientAccessCheck"/>
```

This overrides the default permissive implementation.

## Evals

The project includes an eval framework that tests retrieval quality, citation accuracy, absent-data detection, and prompt injection resistance without requiring a running LLM or external services.

### Running evals

```
mvn test -pl api -Dtest="*EvalTest"
```

Or run a specific suite:

```
mvn test -pl api -Dtest="RetrievalQualityEvalTest"
mvn test -pl api -Dtest="CitationEvalTest"
mvn test -pl api -Dtest="AbsentDataEvalTest"
mvn test -pl api -Dtest="PromptInjectionEvalTest" -Dchartsearchai.prompt.injection.test=true
```

### Adding cases

Each suite is driven by a JSON dataset in `api/src/test/resources/eval/`. To add a case, append an entry to the relevant file:

| File | What it tests |
|------|---------------|
| `retrieval-eval-dataset.json` | Query → expected record indices (recall\@30) |
| `citation-eval-dataset.json` | Simulated LLM JSON → expected citation indices (F1) |
| `absent-data-eval-dataset.json` | Query → expected keywords in "no records" answer |
| `prompt-injection-eval-dataset.json` | Adversarial payload → LLM produces safe JSON, no system prompt leakage |

### Metrics report

Each run appends per-case and summary metrics to `api/target/eval-results.csv` for tracking regressions over time.

## Evaluated models

The following models were evaluated for local inference via the embedded llama-server (Q4_K_M quantization, GGUF format). All figures are approximate and depend on hardware.

| Model | Params | File Size | Total RAM | Context Window | CPU Speed | Chat Template |
|-------|--------|-----------|-----------|----------------|-----------|---------------|
| Qwen 2.5 1.5B | 1.5B | ~1GB | ~2GB | 32K tokens | ~40–50 tok/s | chatml |
| Gemma 3 1B | 1B | ~0.7GB | ~2GB | 32K tokens | ~40–50 tok/s | gemma |
| Gemma 3n E2B | E2B (5B total) | ~1.5GB | ~3GB | 32K tokens | ~25–35 tok/s | gemma |
| Gemma 4 E2B | E2B (2.3B eff) | ~1.5GB | ~3–5GB | 128K tokens | ~25–35 tok/s | gemma |
| Llama 3.2 3B | 3B | ~2GB | ~6GB | 128K tokens | ~20–30 tok/s | llama3 |
| Phi-3 Mini 3.8B | 3.8B | ~2GB | ~4GB | 4K tokens | ~15–25 tok/s | phi3 |
| Gemma 3 4B | 4B | ~2.5GB | ~6–8GB | 128K tokens | ~10–20 tok/s | gemma |
| Gemma 3n E4B | E4B (8B total) | ~2.5GB | ~3–5GB | 32K tokens | ~15–25 tok/s | gemma |
| **Gemma 4 E4B** *(module default)* | E4B (4.5B eff) | ~2.5GB | ~6–8GB | 128K tokens | ~10–20 tok/s | gemma |
| MedGemma 1.5 4B | 4B | ~2.5GB | ~6–8GB | 128K tokens | ~10–20 tok/s | gemma |
| MedGemma 4B | 4B | ~2.5GB | ~6–8GB | 128K tokens | ~10–20 tok/s | gemma |
| Mistral 7B | 7B | ~4GB | ~8GB | 32K tokens | ~10–15 tok/s | mistral |
| Qwen 2.5 7B | 7B | ~4GB | ~8GB | 128K tokens | ~8–12 tok/s | chatml |
| Llama 3.3 8B | 8B | ~4.5GB | ~10GB | 128K tokens | ~8–12 tok/s | llama3 |
| Gemma 2 9B Instruct | 9B | ~5GB | ~10GB | 8K tokens | ~5–10 tok/s | gemma |
| Gemma 3 12B | 12B | ~7GB | ~12GB | 128K tokens | ~4–8 tok/s | gemma |
| Mistral Nemo 12B | 12B | ~7GB | ~12GB | 128K tokens | ~4–8 tok/s | mistral |
| Phi-3-Medium 14B | 14B | ~8GB | ~14GB | 4K tokens | ~3–6 tok/s | phi3 |
| Qwen 2.5 14B | 14B | ~8GB | ~14GB | 128K tokens | ~3–6 tok/s | chatml |
| **Gemma 4 26B MoE** *(standalone default)* | 26B (3.8B active) | ~15GB | ~18–22GB | 256K tokens | ~3–6 tok/s | gemma |
| Gemma 3 27B | 27B | ~16.5GB | ~20–24GB | 128K tokens | ~1–2 tok/s | gemma |
| MedGemma 27B Text | 27B | ~16.5GB | ~20–24GB | 128K tokens | ~1–2 tok/s | gemma |
| Gemma 4 31B | 31B | ~18GB | ~22–26GB | 256K tokens | ~1–2 tok/s | gemma |

### Model size guidance

- **1–2B models** (Gemma 3 1B, Gemma 3n E2B, Gemma 4 E2B): Ultra-low-resource or on-device deployments. Gemma 3n and Gemma 4 "E" models use Per-Layer Embeddings (PLE) for memory efficiency — E2B runs in as little as ~3GB RAM. Weaker reasoning but fast inference. Gemma 4 E2B offers 128K context; Gemma 3 1B and 3n E2B are limited to 32K.
- **3B models** (Llama 3.2 3B): Most deployable in low-resource settings but weaker instruction following — may produce verbose or hedging responses.
- **4B models** (MedGemma 1.5 4B, Gemma 4 E4B): Recommended default tier. MedGemma 1.5 4B provides medical-domain fine-tuning with improved medical imaging support. Gemma 4 E4B is a strong general-purpose alternative under the permissive Apache 2.0 license. Both offer 128K context and ~10–20 tok/s CPU inference at ~6–8GB total RAM.
- **8B models** (Llama 3.3 8B): Significantly better general reasoning and instruction following than 4B, feasible on 10GB RAM.
- **12B models** (Gemma 3 12B, Mistral Nemo 12B): Best sub-15B options for clinical Q&A. Gemma 3 12B offers 128K context with strong reasoning. Mistral Nemo 12B has strong medical text comprehension.
- **14B models** (Qwen 2.5 14B, Phi-3-Medium 14B): Best CPU-viable response quality, but slower (~2–4 tok/s) and need 14–16GB RAM.
- **26–31B models** (Gemma 4 26B MoE, Gemma 4 31B, MedGemma 27B Text): Highest quality tier. Gemma 4 26B MoE activates only 3.8B parameters per token, offering faster inference than dense models at this size. Gemma 4 31B Dense offers the best general reasoning under Apache 2.0. MedGemma 27B Text is the medical-domain specialist. All require ~20GB+ RAM and are practical mainly with GPU acceleration.

A server running OpenMRS typically uses 1–2GB for the JVM heap. A 4GB machine is insufficient — the smallest viable model requires at least 3–4GB on its own.

### Licensing notes

- **Gemma 4** (Google): Apache 2.0 license — fully permissive, no usage restrictions. The first Gemma family release under a standard open-source license.
- **Gemma 3, Gemma 3n** (Google): [Gemma Terms of Use](https://ai.google.dev/gemma/terms) — custom license that permits commercial use but reserves Google's right to terminate access for policy violations. More restrictive than Apache 2.0.
- **Gemma 2** (Google): [Gemma Terms of Use](https://ai.google.dev/gemma/terms).
- **MedGemma** (Google): [Health AI Developer Foundations Terms](https://developers.google.com/health-ai-developer-foundations/terms) — more restrictive than Gemma. Requires validation before clinical deployment. Applies to both MedGemma 1.5 4B and MedGemma 27B Text.
- **Llama 3.x** (Meta): Free for research and commercial use under the [Llama 3.2 Community License](https://www.llama.com/llama3_2/license/). Not technically "open source" by OSI definition — the only meaningful restriction is that products with over 700M monthly active users require a separate license.
- **Mistral** (Mistral AI): Apache 2.0 license.
- **Phi-3** (Microsoft): MIT license — fully permissive with no usage restrictions.
- **Qwen 2.5** (Alibaba): Apache 2.0 license. Developed by a Chinese company subject to China's data laws — while GGUF models run locally with no data leaving the machine, some organizations may have compliance concerns.

See [docs/adr.md](docs/adr.md) (Decision 10) for detailed per-model analysis, trade-off discussion, and architectural rationale.

## Architecture

See [docs/adr.md](docs/adr.md) for architectural decisions and design rationale.

## License

This project is licensed under the [MPL 2.0](http://openmrs.org/license/).

MedGemma is licensed under the [Health AI Developer Foundations License](https://developers.google.com/health-ai-developer-foundations/terms), Copyright (C) Google LLC. All Rights Reserved.

Gemma 4 is licensed under the [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0).

Gemma 3 and Gemma 3n are licensed under the [Gemma Terms of Use](https://ai.google.dev/gemma/terms), Copyright (C) Google LLC. All Rights Reserved.

Llama 3.3 is licensed under the [Llama 3.2 Community License](https://www.llama.com/llama3_2/license/), Copyright (C) Meta Platforms, Inc. All Rights Reserved.
