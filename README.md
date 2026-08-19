# Chart Search AI Module

[![Download Standalone](https://img.shields.io/badge/Download-O3_Standalone_with_Chart_Search_AI-blue?style=for-the-badge)](https://nightly.link/openmrs/openmrs-module-chartsearchai/workflows/build-standalone/main/openmrs-standalone-chartsearchai.zip)

An OpenMRS module that lets clinicians ask natural language questions about a patient's chart and get answers with source citations.

For project background, community discussion, and roadmap, see the [wiki project page](https://openmrs.atlassian.net/wiki/spaces/projects/pages/373325839/Chart+Search+aka+ChartSearchAI).

The standalone download above includes the backend module, frontend ESM, and the following AI models — ready to run:

- **LLM**: [Gemma 4 E4B Instruct (Q4_K_M)](https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF) — ~5 GB, the module's default model, for answering clinical questions. (A larger Gemma 4 26B MoE bundle can be built via the workflow's `gguf_model_url` input.)
- **Retrieval + embedding**: the [querystore module](https://github.com/openmrs/openmrs-module-querystore) with [e5-base-v2](https://huggingface.co/intfloat/e5-base-v2) (~440 MB ONNX) — querystore is a required module and owns the retrieval path. chartsearchai no longer ships its own embedder; retrieval and citation grounding both use querystore's model.

> **Before running the download, see [Standalone platform notes](#standalone-platform-notes)** — in particular the **Windows JDK requirement** (the local embedder won't load on an old or Oracle JDK).

## Table of Contents

- [Try it on the demo server](#try-it-on-the-demo-server)
- [Standalone platform notes](#standalone-platform-notes)
- [Requirements](#requirements)
- [Docker](#docker)
- [Setup](#setup)
  - [1. Build](#1-build)
  - [2. Download the LLM model](#2-download-the-llm-model-local-mode-only)
  - [3. Download the embedding model](#3-download-the-embedding-model-optional)
  - [4. Install](#4-install)
  - [5. Configure](#5-configure)
  - [6. Grant privileges](#6-grant-privileges)
  - [7. Indexing](#7-indexing)
- [Query behavior](#query-behavior)
- [API](#api)
  - [Search](#search)
  - [Streaming search (SSE)](#streaming-search-sse)
  - [Warmup](#warmup)
  - [Feedback](#feedback)
  - [Audit log](#audit-log)
  - [Drug-reference status](#drug-reference-status)
- [Patient access control](#patient-access-control)
- [Evals](#evals)
- [Evaluated models](#evaluated-models)
- [Architecture](#architecture)
- [License](#license)

## Try it on the demo server

A live demo runs at **https://chartsearchai.openmrs.org**, so you can try Chart Search AI without installing anything.

1. Open https://chartsearchai.openmrs.org and log in (default credentials: `admin` / `Admin123`).
2. Click the magnifying-glass icon in the top header and search for a patient by name, then open a chart from the result list.

   The demo carries whatever demo data the server was last seeded with, and that population changes — so this walkthrough deliberately names no patient. Pick one whose chart actually has records (Medications, Vitals, Conditions), since the AI answers only from what the chart contains; on a chart with nothing in it the correct answer is that there are no records, which is not much of a demo. Common demo-data surnames (`Williams`, `Smith`) are a reasonable place to start.

   ![Patient search overlay with a name typed and a matching patient in the result list](docs/images/ai-chart-search-patient-search.png)

3. Click the floating blue AI sparkle icon in the bottom-right corner of the chart (tooltip: *Ask AI about this patient*). A chat panel slides in.
4. Type a clinical question — e.g. *What medications is this patient on?*, *Any allergies?*, *Last 3 blood pressure readings* — and press **Send**, or click the microphone for voice input.
5. The answer streams in token-by-token. The records the answer is grounded in appear under **References**, numbered to match the inline citations (`[1]`, `[2]`, …). Both the inline citations and the chips under **References** are clickable — they navigate to the relevant chart tab (Orders, Results, Allergies, Conditions, Programs, etc.) and highlight the source record. Every response carries the AI-generated disclaimer.

   ![AI Chart Search panel showing an answer with numbered citations on a patient's chart](docs/images/ai-chart-search-demo.png)

6. Optionally rate the answer under **Was this helpful?** with **Helpful** / **Not helpful** and an optional comment. Feedback is recorded in the audit log alongside the question.

Notes:

- The AI button is only rendered for users with the **AI Query Patient Data** privilege.
- The launch surface is configurable via the frontend `chatLaunchMode` setting: `floating` (the bottom-right circular button used above), `workspace` (an icon in the top-right workspace strip that opens the chat as a docked workspace), or `both` (default).
- Answers take seconds to minutes. The demo's engine and model are whatever its operators have configured (`chartsearchai.llm.engine`), so treat its latency as indicative of that deployment, not of the module.

## Standalone platform notes

Per-platform setup for the [downloaded standalone](#chart-search-ai-module) (Java 21+ required; see the Windows note for *which* JDK):

- **Windows:** run it with a JDK whose bundled MSVC runtime is **≥ 14.40** (Visual Studio 2022 17.10+) — [**Microsoft Build of OpenJDK 21.0.8+**](https://learn.microsoft.com/en-us/java/openjdk/download) (recommended), a current [**Eclipse Temurin 21**](https://adoptium.net/temurin/releases/?version=21), or **Azul Zulu**. The local ONNX embedder (querystore retrieval) is compiled against that runtime; an older JDK — **Oracle JDK in particular ships an outdated `msvcp140.dll` and fails even at 21/24** — makes ONNX fail to initialize (`onnxruntime.dll: A dynamic link library (DLL) initialization routine failed`) and chart queries error out. Check yours with `java -version` (vendor matters more than the number). See [onnxruntime#24287](https://github.com/microsoft/onnxruntime/issues/24287).
- **macOS — requires macOS 14 (Sonoma) or newer.** The bundled native binaries (llama-server, MariaDB, the embedder dylibs) are built for the macOS 14 SDK, so older releases — e.g. High Sierra 10.13 — can't launch them and the standalone exits at startup. On an unsupported macOS, use [Docker](#docker) or the [demo server](#try-it-on-the-demo-server) instead. If a (supported) build fails to start with a `libpcre2` dyld error, run `xattr -dr com.apple.quarantine <extracted-directory>` once and retry — current builds self-heal this at launcher startup, rebuild the patient search index on first run, and land on the login page.
- **Apple Silicon vs Intel Mac:** Apple Silicon is the supported Mac target and runs the bundled database out of the box. Intel (x86_64) Macs ship without a bundled MariaDB — there's no prebuilt x86_64 macOS binary to bundle (Homebrew publishes no x86_64 bottle, MariaDB has no macOS `.pkg`, and the bundleable `mariaDB4j-db-mac64` stops at 10.2.11) — so install one with `brew install mariadb` (on Intel, Homebrew builds a current MariaDB from source) and the standalone uses it automatically. This still requires macOS 14+; otherwise prefer Apple Silicon, Windows, or Docker.

## Requirements

- Java 11+
- OpenMRS Platform 2.8.0+
- Webservices REST module 2.44.0+
- RAM for local LLM inference (not required when using a remote LLM):
  - **~6–8GB RAM** for the module's default model — Gemma 4 E4B (~5GB GGUF), as bundled with the standalone download. Suitable for most deployments adding the module to an existing OpenMRS site.
  - **~24GB+ RAM** for the production-grade Gemma 4 26B MoE (optional; build the standalone bundle with the workflow's `gguf_model_url` input and point `chartsearchai.llm.modelFilePath` at the downloaded filename).
- The [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) module — required; it owns all retrieval, indexing, and embedding.

## Docker

```bash
git clone https://github.com/openmrs/openmrs-module-chartsearchai.git
cd openmrs-module-chartsearchai
docker compose up --build
```

No JDK or model downloads needed — the Docker build handles everything. On first start, the e5-base-v2 sentence embedder (~440MB), the default LLM (Gemma 4 E4B, ~5GB), and a standby Gemma 4 E2B (~3GB, for operator-driven A/B latency testing via `chartsearchai.llm.modelFilePath`) are downloaded automatically from HuggingFace and persisted in a Docker volume (~8GB total LLM footprint). The embedder is provisioned for the [querystore deployment](#querystore-deployment) — set the matching querystore GPs after first start (see that section for the exact wiring).

First startup takes 5–15 minutes (model downloads + database initialization). Once the logs show that OpenMRS has started, open http://localhost/openmrs/spa (default credentials: `admin` / `Admin123`). Subsequent starts are fast since the data volume persists.

Alternatively, download the [O3 Standalone with Chart Search AI](https://nightly.link/openmrs/openmrs-module-chartsearchai/workflows/build-standalone/main/openmrs-standalone-chartsearchai.zip) — a single zip with everything included, no Docker required (Java 21+ needed). See the [OpenMRS Standalone guide](https://openmrs.atlassian.net/wiki/spaces/docs/pages/25472583/OpenMRS+Standalone) for instructions.

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

**Measured E4B vs E2B latency (CPU-only inference on the `chartsearchai.openmrs.org` demo, single patient, single question, ~1855-token serialized chart):**

| Model | Cold query (model loaded, fresh prompt) | Warm query (identical prompt re-asked, llama.cpp KV-cache reuse) |
|-------|------------------------------------------|------------------------------------------------------------------|
| Gemma 4 E4B | ~194 s | not measured (KV-cache reuse would help here too, just less in relative terms) |
| Gemma 4 E2B | ~63 s | ~8.5 s |

Swapping the served model from E4B to E2B cut cold-query latency by ~3× on this CPU-only deployment. The warm number reflects llama.cpp reusing the prompt's KV cache when an identical question is re-issued; diverse production traffic only partially benefits (the chart prefix reuses, the per-question suffix re-prefills). The same KV-cache mechanism also accelerates *different* follow-up questions on the same patient when the chart prefix is stable across calls — see the [Prompt-stability caveat](#querystore-deployment) under Querystore deployment for the measured ~4–7 s follow-up numbers. Quality also diverges on the same prompt: E4B cited 2 `condition` resources, E2B cited 3 `diagnosis` resources with additional metadata in the answer text. A single observation isn't a quality verdict — run the [Evals](#evals) suite before promoting E2B as the served default.

Gemma 4 26B MoE is recommended for production deployments because it follows the system prompt rules (never infer, cite every record, complete enumeration on list queries) reliably without needing reasoning as a safety scaffold. Smaller models work but trade off either safety or list completeness depending on the query. The MoE architecture activates only ~3.8B parameters per token, so per-token speed is comparable to a 4B dense model despite the 26B total size.

### 3. Download the embedding model *(optional)*

The embedding model belongs to querystore — chartsearchai no longer ships its own. It is used both for querystore's retrieval index and for chartsearchai's citation grounding (the verifier embeds with the same model that built the index).

**Querystore-backed retrieval** — the querystore module handles retrieval; the LLM filters the top-K it returns. See [Querystore deployment](#querystore-deployment) below for the global properties this path expects and [ADR Decision 22](docs/adr.md#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) for the model rationale. The LLM is still required (see [step 2](#2-download-the-llm-model-local-mode-only) or use a remote engine). Download `intfloat/e5-base-v2` (~440MB):

- ONNX model: https://huggingface.co/Xenova/e5-base-v2/resolve/main/onnx/model.onnx *(self-contained — see [ADR Decision 22](docs/adr.md#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) for why this source over the canonical `intfloat/e5-base-v2`)*
- Vocab: https://huggingface.co/Xenova/e5-base-v2/resolve/main/vocab.txt

Place both at `<openmrs-application-data-directory>/querystore/` and wire the global properties documented in [Querystore deployment](#querystore-deployment) below.

> chartsearchai's own embedding/Lucene/Elasticsearch pre-filter pipelines were removed in the querystore migration (#51); querystore is now the only retrieval and grounding embedder.

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

**GPU acceleration.** This applies to *any* local-engine deployment — standalone, the module installed into an existing OpenMRS site, or Docker — since they all run the same bundled `llama-server`:

- **Apple Silicon Macs use the GPU automatically.** The bundled macOS build has llama.cpp's Metal backend compiled in, so the whole model offloads to the GPU with no configuration (`-ngl` is already set); unified memory means there's no separate VRAM limit to manage.
- **Linux and Windows bundled binaries are CPU-only by design.** They are single self-contained builds with no GPU backend compiled in, so they run on the CPU even on a host with an NVIDIA card, and `-ngl` is a no-op. This is deliberate: a GPU-linked binary can't double as the one universal build (it won't start without a matching GPU driver, and discrete cards differ in VRAM and CUDA version).
- **Docker is always CPU-only**, even on an Apple Silicon host — the image is a Linux container, so it uses the Linux (CPU-only) binary and cannot reach the host's Metal GPU.
- **To use an NVIDIA (or other) GPU on Linux/Windows/Docker**, either: (a) run the model on a GPU inference server (vLLM, Ollama, text-generation-inference) and point chartsearchai at it with the **Remote engine** below (`chartsearchai.llm.engine=remote`); or (b) supply your own GPU-built `llama-server`. The local engine uses any executable `llama-server` (`llama-server.exe` on Windows) already present at `<appdata>/chartsearchai/bin/` instead of extracting the bundled CPU build, and adds that directory to the library path — so place a GPU-enabled binary there alongside its backend libraries (e.g. `libggml-cuda.so`) and the launcher's hardcoded `-ngl 99` offloads to the GPU (recent llama.cpp builds auto-fit the layer count to the card's VRAM, so a card smaller than the model still gets a partial offload).

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

#### Querystore deployment

chartsearchai delegates all retrieval to the [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) module (a required dependency) — querystore handles indexing and top-K retrieval, and the local LLM reasons over the result set. chartsearchai's own embedding/Lucene/Elasticsearch pipelines were removed in the querystore migration (#51), so this is the only retrieval path. It is what the Docker image (`Dockerfile.backend` + `backend-init.sh`) provisions by default. See [ADR Decision 22](docs/adr.md#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) for the full architectural narration.

**Deployment checklist:**

1. LLM available — local GGUF ([step 2](#2-download-the-llm-model-local-mode-only)) or remote engine.
2. e5-base-v2 ONNX + vocab placed at `<openmrs-application-data-directory>/querystore/` ([step 3](#3-download-the-embedding-model-optional)).
3. Global properties set per the table below — done for you on the Docker path (see *Who sets these* below the table).
4. Indexing is lazy on first chart access — no backfill task needed.

| Property | Value | Description |
|----------|-------|-------------|
| `chartsearchai.querystore.topK` | `12` | Number of similarity records requested from querystore. In `queryScoped` mode (the default `chartsearchai.chartMode`) this sizes the query-scoped slice the LLM actually sees, alongside the question's complete typed scope; in `fullChart` mode it only sizes the optional focus hint, and is unused when `chartsearchai.embedding.preFilter` is `false`. querystore is a required module and is always the retrieval path — there is no toggle to disable it. `ChartSearchAiConstants.DEFAULT_QUERYSTORE_TOP_K` carries the default and the measurements behind it |
| `querystore.embedding.modelFilePath` | `querystore/model.onnx` | Path to the ONNX embedder, relative to `<openmrs-application-data-directory>`. Querystore ships this with an empty default (the module is model-agnostic), so it has to be set somewhere — on the Docker path `backend-init.sh` does it, otherwise you do (see *Who sets these* below) |
| `querystore.embedding.vocabFilePath` | `querystore/vocab.txt` | Path to the WordPiece vocab, same convention |
| `querystore.embedding.queryModelFilePath` | *(empty)* | Leave empty for `e5-base-v2`; set only for dual-encoder models like MedCPT |

**Index freshness.** querystore owns retrieval-index freshness. chartsearchai reacts to a chart write only by invalidating the answer cache (and, when `chartsearchai.prewarm.refreshOnEdit` is on, re-pinning that patient's prewarm KV), detected via core #6084 service events — see [ADR Decision 26](docs/adr.md#decision-26-chart-write-detection-via-core-service-events).

**Prompt-stability caveat — only relevant in `fullChart` mode.** In `fullChart` mode the chart bytes are a function of the patient alone, so the `<system> + <chart>` prompt prefix is stable across consecutive queries and llama-server's KV cache reuses it; that is what the [Warmup](#warmup) endpoint and the disk-persisted KV cache exist to exploit. In the default `queryScoped` mode each question carries its own small slice, so there is no shared prefix to amortize — and none is needed, because a slice prefills in a fraction of the time. Setting `chartsearchai.embedding.preFilter=true` in `fullChart` mode leaves the chart prefix intact (the focus hint is a small trailing payload), so it does not break the reuse.

**Who sets these.** On the Docker path, `backend-init.sh` writes them itself on every start — `chartsearchai.querystore.enabled`, `querystore.embedding.modelFilePath` and `querystore.embedding.vocabFilePath`, pointed at the files it has just provisioned — and only where the property is blank, so a value you set deliberately survives. One exception: when the demo dataset is (re)seeded, `chartsearchai.querystore.enabled` and `querystore.bootstrap.autostart` are asserted outright, because a freshly imported dump brings its own values for both and they describe the dump rather than your server. They used to be a manual step, which meant they existed nowhere but the running instance's database: a `--destroy-volumes` deploy deleted the wiring along with it, and because the demo seed also enables `querystore.bootstrap.autostart`, the next start swept every record of every seeded patient against an unconfigured embedder and logged a stack trace for each one. On any other install you still set them yourself after first start. A follow-up in the querystore module's `config.xml` would make them defaults everywhere; the GPs are already declared there with empty values, which is why they appear in **Admin > Settings** today. See [ADR Decision 22](docs/adr.md#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) for why this path uses `e5-base-v2`.

#### Retrieval

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.chartMode` | `queryScoped` | How the prompt's chart context is assembled. `queryScoped` (default) sends only a slice: every record of the question's typed scope (complete by construction — an enumeration answer cannot omit what was never retrieved), plus the `chartsearchai.querystore.topK` similarity records, plus demographics. `fullChart` serializes the whole chart into every prompt. **The full-chart prefill machinery — warmup, the prewarm bootstrap, per-patient KV persistence, the progressive-reasoning preview — is dormant in `queryScoped` mode and re-engages only under `fullChart`.** A value that is not an exact (case-insensitive) `queryScoped` behaves as `fullChart`, so a typo fails toward the whole chart; an absent or unreadable GP takes the default. See [ADR Decision 28](docs/adr.md#decision-28-query-scoped-slice-charts-chartmodequeryscoped) for the A/B behind the default |
| `chartsearchai.embedding.preFilter` | `false` | *(`fullChart` mode only)* When `true`, querystore additionally ranks the patient's records by similarity to the question and passes a short **focus hint** — the top `chartsearchai.querystore.topK` record indices — to the LLM. **The full chart is still sent either way**, so the hint biases attention without removing records the LLM needs for negative reasoning (correctly answering "any allergies?" requires having seen the empty allergy section, not just an absence of matches). Has no effect in the default `queryScoped` mode |

#### LLM tuning

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.llm.systemPrompt` | *(built-in clinical prompt)* | System prompt that guides how the LLM responds — e.g. answering only the question asked, using only the provided patient records, citing records by number, naming what is missing when records lack relevant information (e.g. "There are no records about diabetes in this patient's chart"), keeping answers concise, and returning structured JSON |
| `chartsearchai.llm.timeoutSeconds` | `300` | Maximum seconds to wait for LLM inference before timing out |
| `chartsearchai.llm.idleTimeoutMinutes` | `30` | *(Local engine only)* Minutes of inactivity after which the embedded llama-server is stopped to free RAM. It is automatically restarted on the next query. Set to `0` to keep it running indefinitely |
| `chartsearchai.llm.serverPort` | `18085` | *(Local engine only)* Port for the embedded llama-server. Change if the default conflicts with another service |
| `chartsearchai.llm.contextSize` | `32768` | *(Local engine only)* Context window size in tokens for the embedded llama-server. The system prompt + serialized chart + question must fit within this. Larger values let bigger charts pass through full-chart mode but increase the KV cache memory footprint roughly linearly. Increase if you see "Patient chart exceeds the LLM context window" (HTTP 413) and have headroom for a larger KV cache; reduce on memory-constrained hardware |
| `chartsearchai.llm.reasoningMaxChars` | `0` | Caps the model's reasoning scratchpad at this many characters (via a grammar-enforced `maxLength` in the chart-answer schema) when greater than `0`; the answer itself is never capped. The reasoning phase is the dominant decode cost on CPU-only servers (a measured 3–27 seconds of "thinking" before any answer text), so bounding it bounds that cost. `0` (default) leaves the schema unchanged. **Caution:** truncating the chain of thought can change answers — only enable a value that has cleared the answer-quality gold standard (`eval/drift-metric/`) with no regression in mean F1, abstention accuracy, or off-topic citations versus the uncapped baseline. Gemma 4 E2B at `400` failed that gate on all three axes (measured 2026-06-12); no certified value exists, so leave at `0` unless a fresh gate run for your model and value passes |
| `chartsearchai.warmupEnabled` | `true` | When `true`, opening a patient chart triggers a background warmup that primes the LLM prompt cache (system prompt + serialized chart) so the first AI query on that patient skips the full prefill cost. No-op when `chartsearchai.llm.engine` is `remote` (remote providers manage their own caching), and — because there is no question-independent chart prefix to prime — whenever `chartsearchai.chartMode` is `queryScoped`, **which is the default**. The gate is `LlmInferenceService.shouldRunWarmup` |
| `chartsearchai.llm.kvCacheDir` | *(empty → `<appdata>/chartsearchai/kvcache`)* | *(Local engine only)* Directory where each patient's prefilled chart KV cache is persisted to disk (via llama-server `--slot-save-path`). **Enabled by default** — empty resolves to `<appdata>/chartsearchai/kvcache`; set an explicit path to relocate it, or `off` (or `false`/`none`/`disabled`) to turn it off. Both the chart-open warmup and the streaming query path **restore** a patient's KV from disk (I/O-bound, ~tens of ms) instead of recomputing the full chart prefill (CPU-bound, tens of seconds to minutes on a GPU-less host) whenever the RAM prompt cache is cold for it, and **save** a fresh cold prefill so the next visit is fast even without a warmup — and, unlike the in-RAM prompt cache, this survives llama-server restarts and single-slot evictions. The restored KV is byte-for-byte what a fresh prefill produces, so answers are unchanged. The first-ever visit to a patient still pays one prefill (to create the file). Files are large (tens to a few hundred MB each) and contain the model's encoding of the chart (PHI) — prefer fast local storage with appropriate permissions; disable on hosts where that on-disk footprint is unwanted. The biggest first-query latency win for CPU-only deployments — see [Warmup](#warmup) |
| `chartsearchai.llm.kvCacheMaxEntries` | `16` | *(Local engine only)* Maximum persisted KV-cache files to retain in `chartsearchai.llm.kvCacheDir`; the oldest (by mtime) are evicted beyond this, bounding disk use. **Pinned** entries created by the prewarm bootstrap (below) are exempt from this cap — they are neither counted nor evicted |
| `chartsearchai.llm.kvCache.maxPinnedEntries` | `0` | *(Local engine only)* Upper bound on the number of **pinned** KV entries the prewarm bootstrap may create. `0` (default) means unbounded — pin the whole patient population. Set a positive value to bound the on-disk pinned footprint on hosts that want a partial prewarm corpus: once reached, the sweep stops pinning (it does not evict already-pinned entries). Only consulted by the prewarm sweep |
| `chartsearchai.prewarm.enabled` | `false` | *(Local engine only)* Master switch for the bulk KV-prewarm bootstrap — the `POST /chartsearchai/prewarm` + `GET /chartsearchai/prewarmstatus` endpoints and the background sweep that pre-fills and **pins** every patient's chart KV so a first query on a never-opened patient is also warm. Opt-in: a full-database sweep prefills every patient (tens of seconds each) on the single inference slot and grows the on-disk pinned corpus (bound it with `chartsearchai.llm.kvCache.maxPinnedEntries`). Independent of the per-chart-open warmup (`chartsearchai.warmupEnabled`) |
| `chartsearchai.prewarm.autostart` | `false` | *(Local engine only)* When `true` (and `chartsearchai.prewarm.enabled` is on), the prewarm sweep resumes/starts automatically on module startup from the persisted cursor, without a manual `POST /prewarm` |
| `chartsearchai.prewarm.throttleMs` | `500` | *(Local engine only)* Milliseconds the bulk prewarm sweep pauses between patients so the single inference slot is not monopolised by the backfill while live queries are served |
| `chartsearchai.prewarm.refreshOnEdit` | `false` | *(Local engine only)* When `true` (and a pinned corpus exists), a chart edit to a patient who is **already pinned** schedules a debounced, single-patient re-pin so the pin tracks the new chart instead of going stale (a query for an edited-but-not-refreshed patient otherwise re-prefills as an ordinary, unpinned, LRU-capped entry, eroding the pinned corpus). Independent of `chartsearchai.prewarm.enabled` — it only refreshes existing pins and never grows the corpus. **Trade-off:** each re-pin is a full chart prefill (tens of seconds, the same cost as the sweep) that serializes on the single inference slot shared with live queries, and — unlike the bulk sweep — there is **no inter-patient throttle**, so several pinned patients edited near-simultaneously queue their prefills back-to-back. Low impact on single-user / GPU hosts; on busy multi-user CPU hosts with frequent edits to pinned patients, prefer a periodic manual re-sweep instead |
| `chartsearchai.prewarm.refreshDebounceMs` | `5000` | *(Local engine only)* Quiet-period in milliseconds before a `refreshOnEdit` re-pin fires, so a burst of writes to one patient (e.g. an encounter save writing many obs) collapses to a single re-pin instead of one prefill per write |

#### Citation grounding *(optional, off by default)*

After the LLM answers, each citation can be verified against the record it points to — catching the dangerous case of a real record cited for a claim it does not actually support (index validation alone only confirms `[N]` maps to a retrieved record, not that the record supports the claim). Two tiers, both opt-in and non-blocking: grounding only annotates which citations could be confirmed, never rewrites or blocks the answer. See [Citation grounding](#citation-grounding) under Query behavior for what the verdicts mean and [ADR Decision 25](docs/adr.md#decision-25-citation-grounding-tier-1-cosine--tier-2-entailment) for the design.

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.grounding.enabled` | `false` | Master switch. When `true`, every cited record is checked for grounding after the answer is produced; `chart`-group citations that fail are flagged as unverified. The answer is never blocked or rewritten. A `reference`-group citation's verdict is never published whatever this is set to (see [Citation grounding](#citation-grounding)) |
| `chartsearchai.grounding.minCosine` | `0.40` | Tier-1 floor: minimum cosine similarity between a cited record's text and the answer sentence that cites it. Catches grossly off-topic citations, not subtle subject/negation flips. Model-dependent — the verifier embeds with querystore's model; the default `0.40` is far too low for `e5-base-v2`, so set ~`0.82` on an e5 deployment. Must be between 0 and 1 |
| `chartsearchai.grounding.entailment.enabled` | `false` | Tier-2: confirm each citation with a yes/no LLM entailment judgement of whether the record actually supports the sentence citing it. Catches high-overlap-but-false citations (the record says a *relative* had X, or negates X) that cosine cannot separate. Verified in a batched LLM call, except that the citations of one sentence whose claim statements overlap get a call each (a clause-scoped compound, or an enumerating sentence in either mode — [#278](https://github.com/openmrs/openmrs-module-chartsearchai/issues/278)), since batched entailment is not per-pair independent. Tier-1 cosine is computed lazily in this mode, so Tier-2 works even when no embedding model is configured. Requires `chartsearchai.grounding.enabled` |
| `chartsearchai.grounding.async` | `false` | *(Streaming only)* Emit the `done` event as soon as the answer is complete (references unverified) and deliver verdicts afterward in a trailing `grounded` event — moving the Tier-2 tail off the user's perceived completion time. Clients must keep consuming the SSE stream after `done`. The blocking `/search` endpoint is unaffected and always returns final verdicts. Requires `chartsearchai.grounding.enabled`. See [Streaming search (SSE)](#streaming-search-sse) |
| `chartsearchai.grounding.clauseScoped` | `false` | When `true`, a citation in a sentence that cites multiple records is checked against the answer text up to and including its own `[N]` marker, rather than the whole compound sentence — flagging a citation that supports its own clause but not a later clause cited by a different record. Independently of this setting, a sentence that enumerates its citations after a list-introducing colon (`recorded allergies: X [1], Y [2], and Z [3]`) is always verified per item, since asking one record to entail the whole list marks every citation of a correct list unsupported (#278); a comma-only enumeration with no colon is not split. Only affects which text a citation is verified against; never changes the answer or which records are cited |

The Tier-2 (`entailment`) and `async` checks require `chartsearchai.grounding.enabled`.

#### Drug reference & safety *(optional, off by default)*

An additive, opt-in feature that (1) injects matching clinical drug-reference records into the chart so the LLM can cite reference dosing / interaction / contraindication facts, and (2) runs a deterministic post-answer check that annotates the answer with non-blocking safety warnings. The clinical knowledge lives in a data file, not in code — and since [ADR Decision 36](docs/adr.md#decision-36-the-shipped-default-is-the-whole-ddinter-knowledge-base) the module **bundles the whole DDInter 2.0 knowledge base** (2283 substances, ~295,000 severity-rated interaction pairs) and selects it by default, so switching `enabled` on needs no download and no further configuration. What that dataset does not publish is dosing or hand-authored allergy/condition rules; `sourceFormat=json` selects the four-drug curated seed that does. See [Drug-reference injection & safety validation](#drug-reference-injection--safety-validation) and [ADR Decision 23](docs/adr.md#decision-23-drug-reference-injection--post-answer-drug-safety-validation).

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.drugReference.enabled` | `false` | Master switch for both parts. When `false` (default), behaviour is unchanged — no records injected, no warnings produced |
| `chartsearchai.drugReference.dataFilePath` | `chartsearchai/ddi_knowledge_base.json` | Relative path (within the OpenMRS application data directory) to the drug-reference dataset, interpreted per `sourceFormat`. For `json` and `ddinter`: when absent/unreadable, the dataset bundled with the module is used. The default names the upstream release's own filename, so refreshing the knowledge base is a file copy — drop a newer `ddi_knowledge_base.json` into `<appdata>/chartsearchai/` and it is read in place of the bundled one, with no property to edit. The module never creates that file, so an untouched install reads the bundled dataset and is silent about it. For `atc`: point it at the WHO ATC export you obtained (no bundled fallback). Path traversal (`..`) is rejected. Editing takes effect on the next module restart |
| `chartsearchai.drugReference.sourceFormat` | `ddinter` | Data adapter ([ADR Decisions 24](docs/adr.md#decision-24-drug-reference-as-a-pluggable-consumer-of-authoritative-datasets) and [36](docs/adr.md#decision-36-the-shipped-default-is-the-whole-ddinter-knowledge-base)). `json` reads the curated dataset — the only format carrying *dosing* rules or hand-authored allergy/condition rules, and so the one to select if you need the dose-excess check, at the cost of covering only its four seeded drugs. `atc` consumes a WHO ATC classification export (`<atcCode> <name>` per line, all levels) into one classification entry per level-5 substance, deriving each drug's class from its parent group; **classification only** — no per-entry dosing/interaction/contraindication rules, but the post-answer validator still derives class-based contraindication/interaction warnings from ATC codes (see below). `ddinter` (default) reads the DDInter 2.0 drug-drug interaction knowledge base (structured DDIs with severity + mechanism, normalized to RxNorm and cross-walked to CIEL; from the [openmrs-ddi-knowledge-base](https://github.com/pbiondich/openmrs-ddi-knowledge-base) data project) — it carries interaction rules but not dosing/contraindications (V1 DDI-only scope). The module bundles the **whole** knowledge base, so full coverage needs no download and `dataFilePath` only has to be set to run a refreshed or locally edited copy. Any unrecognized value is treated as `json`, which since the default moved is **not** the same as leaving this unset: mistyping `ddinter` applies the curated parser to whatever `dataFilePath` names, and is reported at WARN and as a `configured-source-format-not-used` finding on [`/drugreferencestatus`](#drug-reference-status) |
| `chartsearchai.drugReference.crossReactivityGroupsFilePath` | `chartsearchai/cross-reactivity-groups.json` | Relative path (within the OpenMRS application data directory) to the curated cross-reactivity groups: named drug families expressed as ATC code prefixes, carrying the cross-*branch* cross-reactivity knowledge ATC's tree cannot express (aspirin `N02BA01` vs ibuprofen `M01AE01` — the [ADR Decision 24](docs/adr.md#decision-24-drug-reference-as-a-pluggable-consumer-of-authoritative-datasets) boundary, closed by this data). Loaded alongside **either** source format; when absent, the bundled minimal NSAID seed is used. Editing takes effect on the next module restart |
| `chartsearchai.drugReference.injectFromOrders` | `true` | Inject an active drug order's reference only when the question is about a specific drug clinically related to it (sharing an ATC subgroup or a curated cross-reactivity group) — an unrelated current medication, or a question that names no drug, is not injected (the model still sees the order records, and the safety validator reads active orders directly) |
| `chartsearchai.drugReference.injectFromQuery` | `true` | Inject a reference entry whose alias appears in the question. Numeric dosing is rendered only when the patient's age falls in a published band, so a pediatric maximum is never surfaced for an adult |
| `chartsearchai.drugSafety.validateAnswers` | `true` | Enable the post-answer validator. Annotates the answer with non-blocking warnings; it never rewrites or blocks the answer |
| `chartsearchai.drugSafety.warnOnDoseExcess` | `true` | Flag a daily dose parsed from the answer that exceeds the reference maximum for the patient's age band — and, when a fresh weight is on record, a per-administration dose above the band's `mgPerKgMax` × weight (the only possible check for bands publishing mg/kg dosing with no daily maximum). One warning per drug; the daily ceiling wins when both trip **Dormant under the shipped default**: dosing maxima have no free authoritative source, and neither the bundled DDInter knowledge base nor a WHO ATC export publishes any — select `sourceFormat=json`, or point `dataFilePath` at a dataset carrying age-banded maxima, for this arm to have anything to fire on. |
| `chartsearchai.drugSafety.warnOnInteractions` | `true` | Flag a drug in play — asked about in the question, or named by the answer on its own authority — that interacts with one of the patient's active orders, by dataset rule, shared ATC subgroup (duplicate therapy), or shared curated cross-reactivity group (additive / duplicate-class therapy across branches); most specific match wins. An active order that is the drug in play raises nothing — restating existing therapy is not a duplicate — and so does an order whose own recorded name names the drug in play, where the reference data cannot name one of that order's ATC codes — a fixed-dose combination mapped to a combination code the dataset does not carry. Decided per co-medication by substance identity **as well as** by a shared ATC code, so a *second* order in the same class still reports ([#185](https://github.com/openmrs/openmrs-module-chartsearchai/issues/185)). Two further joins are not that drug-in-play-against-an-active-order shape: several drugs the QUESTION names are checked against each other (a reference lookup, so neither side need be a drug the patient takes — [#114](https://github.com/openmrs/openmrs-module-chartsearchai/issues/114)), and a question that asks to be screened for interactions but names no drug has the patient's own active orders checked against each other (a chart drug on *both* sides, [#113](https://github.com/openmrs/openmrs-module-chartsearchai/issues/113)). Both of those are pairwise, so both report most severe first, capped by `chartsearchai.drugSafety.maxPairChips`, with the withheld pairs logged |
| `chartsearchai.drugSafety.warnOnContraindications` | `true` | Flag a drug that is contraindicated by an active allergy or condition: by dataset rule, by a recorded allergy to **that same drug**, by an allergy sharing its ATC subgroup, or by an allergy in the same curated cross-reactivity group (cross-branch). The shared-subgroup route asks more of the subgroup than the interaction arm's does, because a cross-reactivity claim is a claim about chemistry. A subgroup whose published name states only what its members are *for* — `S01AA` "Antibiotics" — does not license one, **though it still licenses duplicate therapy**: two ophthalmic antibiotics do duplicate one another ([#183](https://github.com/openmrs/openmrs-module-chartsearchai/issues/183)). A subgroup that asserts nothing at any level — `A16AX` "Various alimentary tract and metabolism products", a residue whose ancestry is residue up to a bare anatomical main group — licenses **neither** ([#184](https://github.com/openmrs/openmrs-module-chartsearchai/issues/184)). Most specific match wins, and the same-drug routes are one chip however many of them reach it — a dataset rule naming the very drug it is filed against reports the same-drug case, so the two fold into one chip keeping the rule's own note where it has one ([#146](https://github.com/openmrs/openmrs-module-chartsearchai/issues/146)); a rule naming a *class* still reports separately. The note **wins over** the same-drug wording only where the allergy record the rule matched **names** that drug — a token can reach a record by sitting inside a longer word (`opium` inside an allergen recorded as `Tiotropium`), and such a match still chips, in its own words where nothing else reports the drug, but no longer speaks in the same-drug chip's place ([#223](https://github.com/openmrs/openmrs-module-chartsearchai/issues/223)). The same-drug case is identity, not classification, so it needs no ATC code and fires for a drug the dataset cannot classify at all ([#135](https://github.com/openmrs/openmrs-module-chartsearchai/issues/135)). Two joins: a drug in play — asked about in the question, or named by the answer on its own authority — and the patient's **own active orders**. The second answers "is the patient allergic to something they are *taking*?", which the in-play framing could not ask and echo scoping actively withheld ([#143](https://github.com/openmrs/openmrs-module-chartsearchai/issues/143)), and it is **scoped to what the response is about**: a chip is raised where either side of it — the drug, or the recorded finding — is named by the question, by the answer, or by a record the answer cited, and a question in the medication, allergy or condition domain keeps that whole list in scope even where the prose writes no individual name. Unscoped it raised the identical chips on every answer regardless of topic, down to a question about a date of birth; this module answers questions and has no subscription, acknowledgement or unprompted delivery path, so a finding with no claim on the response is not one it can honestly carry. The deliberate cost: a prescribing error nobody asks a drug-shaped question about is not announced here, and belongs on a surface that has those things (order entry, a chart banner, CDS hooks). An order already in play is checked once, and the order join stands down entirely for a patient with neither an allergy nor a condition record |
| `chartsearchai.drugSafety.minInteractionSeverity` | `minor` | Minimum source-assigned severity (`unknown` < `minor` < `moderate` < `major`) a rule-based interaction must carry to chip. Only rated rules are filtered (the `ddinter` source's per-row DDInter severity); unrated curated rules, class-based warnings, and contraindications always show. The default filters exactly DDInter's Unknown-severity rows, which carry no mechanism text (14% of the full KB) and would otherwise share equal billing with critical warnings ([#84](https://github.com/openmrs/openmrs-module-chartsearchai/issues/84)) |
| `chartsearchai.drugSafety.maxPairChips` | `10` | Most chips one question may raise from a *pairwise* check (the question's own drugs against each other, or — for a screening question naming no drug — the patient's active orders against each other). Both grow as N²/2, and every chip is also injected as a citable finding, so a cap is required: unbounded, one 16-drug question produced 72 chips and 42,708 characters of finding text. But the number is a clinical judgement, so it is yours: a polypharmacy review clinic may want 30, a triage screen 5. The cost of a low cap is real — measured on the full DDInter data, a 16-drug question shows 10 of 72 pairs and withholds `[Major ×13, Moderate ×40, Minor ×9]`, i.e. thirteen withheld Majors. What is dropped is always the least severe, and every withheld pair is named in a WARN (today the only place the withheld count appears). Unparseable or non-positive values fall back to the default ([#131](https://github.com/openmrs/openmrs-module-chartsearchai/issues/131)) |
| `chartsearchai.drugSafety.weightConceptUuid` | `5089AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` (CIEL Weight (kg)) | Kg-valued numeric concept whose most recent observation supplies the patient's weight for the per-dose check. Set to `none` to disable the weight-aware arm (blank falls back to the default, like every GP). Values must be in kilograms |
| `chartsearchai.drugSafety.weightMaxAgeDays` | `90` | Freshness window for that weight observation — pediatric weight changes fast, and a stale (lower) weight would over-report mg/kg (a false positive, the direction the validator never takes). An older weight just disables the weight-aware arm |

The `drugSafety.*` checks require both `chartsearchai.drugReference.enabled` and `chartsearchai.drugSafety.validateAnswers`.

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
| **AI Query Patient Data** | Execute chart search queries (`/search`, `/search/stream`, `/warmup`, `/feedback`) |
| **View AI Audit Logs** | Access the audit log endpoint |
| **Manage AI Prewarm** | Trigger and monitor the bulk KV-prewarm bootstrap (`/prewarm`, `/prewarmstatus`) |

`/drugreferencestatus` gates on core's **Get Global Properties** instead, which the `Authenticated` role already holds on a default install.

### 7. Indexing

Retrieval indexing is owned entirely by the [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) module — it performs its own lazy per-patient projection on first chart access and keeps the retrieval index current via core events. There is no chartsearchai-side index to build or maintain; see the querystore repo for indexing details.

## Query behavior

### Absent-data detection

chartsearchai does not run its own relevance gate. The LLM is given the patient's chart — a query-scoped slice by default, or the whole chart under `chartsearchai.chartMode=fullChart` — and reasons over what is present and what is absent — when nothing in the chart addresses the question (e.g., asking "any cancer?" for a patient with no cancer-related records), the system prompt instructs it to answer that there are no records about the topic rather than inferring one. querystore-backed retrieval narrows what reaches the LLM, and the optional [citation grounding](#citation-grounding) pass verifies that each cited record actually supports the claim, catching off-topic or unsupported citations after the answer is produced.

### Recency cap

Questions with numeric recency constraints are automatically detected and honored. For example, "last 3 blood pressure readings" or "most recent 5 lab results" will cap the results per concept group to the specified number, keeping only the most recent measurements.

### Input validation

Questions are checked against common prompt injection patterns (e.g., "ignore previous instructions", "you are now", "system prompt:") and rejected with HTTP 400 if matched. This is a defense-in-depth measure — the primary protection is the structured-output constraint (`response_format: json_schema`, sent by both engines and shared via `ChartAnswerResponseFormat`; the local llama-server enforces it via a derived GBNF grammar internally, and remote OpenAI-compat providers enforce it server-side) that forces LLM output into a fixed `{answer, citations}` shape regardless of prompt content. Normal clinical questions containing words like "ignore" or "instructions" in non-adversarial contexts (e.g., "What instructions were given at discharge?") are not affected.

### Citation grounding

When `chartsearchai.grounding.enabled` is `true` (off by default), every citation the LLM emits is verified against the record it points to *after* the answer is produced. Index validation already confirms each `[N]` maps to a real retrieved record; grounding adds the harder check — does that record actually support the claim it is cited for? Verification is two-tier:

- **Tier-1 (cosine)** — the cited record's text must be semantically close (cosine ≥ `chartsearchai.grounding.minCosine`) to the answer sentence that cites it. This catches grossly off-topic citations (a blood-pressure record cited for a diabetes claim) cheaply, with no extra LLM call.
- **Tier-2 (entailment)** — with `chartsearchai.grounding.entailment.enabled=true`, a yes/no LLM judgement confirms the record actually entails the sentence. This catches high-overlap-but-false citations that cosine cannot separate — e.g. "the patient has X [5]" where record 5 says a *relative* had X, or negates X. Citations are verified in a batched LLM call — one round-trip for citations whose claim statements do not overlap, and one call each for the fragments of a single sentence that do, because batched entailment is not per-pair independent. Tier-1 cosine is computed lazily in this mode (only where the LLM produced no verdict), so Tier-2 works even when no embedding model is configured.

**What text a citation is checked against.** By default the whole sentence citing it — with one exception that does not depend on a setting: a sentence that *enumerates* its citations after a list-introducing colon (`recorded allergies: X [1], Y [2], and Z [3]`) is verified per item, the preamble plus that citation's own item. Otherwise each record would be asked to entail a conjunction naming the *other* records too, and a correct judge answers no, marking every citation of a correct list unsupported ([#278](https://github.com/openmrs/openmrs-module-chartsearchai/issues/278)). The colon is necessary but not sufficient: the items must also be name-shaped. An item carrying its own subject (a pronoun such as `he` or `their`, or a finite verb such as `has` or `reports`) or running past a length backstop keeps whole-sentence scoping, because the split is only sound while the shared preamble holds the subject — otherwise a later item's claim loses it and a citation can be published as supported when it is not. A comma-only enumeration with no colon is likewise not split: the preamble/first-item boundary is not recoverable there, and guessing it strips the subject. `chartsearchai.grounding.clauseScoped` independently widens this to non-enumerating compound sentences.

Each **chart**-group reference in the response carries a `grounded` verdict (`true` / `false` / `null` when not checked), which clients should surface by rendering any citation whose verdict is `false` or `null` as unverified. A `reference`-group citation carries no verdict at all — its `grounded` is always `null`; see below. Grounding never rewrites or blocks the answer — it only annotates which citations could be confirmed.

**Reference-group citations are demote-only, and their verdict is not published.** A citation of module-supplied reference prose (`drug_reference`, `safety_finding`) cannot be marked `true`: an answer that recites reference prose overlaps its source near-identically even when it swaps subject roles, so neither cosine nor the entailment judge can vouch for faithfulness there ([#106](https://github.com/openmrs/openmrs-module-chartsearchai/issues/106)). These citations skip Tier-2 entirely (and don't consume its per-answer cap). Tier-1 can still find one off-topic, and the module still records that internally, but **it does not reach the wire**: since [#201](https://github.com/openmrs/openmrs-module-chartsearchai/issues/201) a `reference`-group citation always serializes `grounded: null`. The verdict's meaning there is "this citation is not about that record", not "this claim is unsupported", and no client had a correct reading of it — so rather than ask every client to key one field on another, the field stops being offered. Faithfulness of reference content is checked deterministically by the [safety validator](#drug-reference-injection--safety-validation)'s warnings instead.

The verifier embeds with querystore's model, so the cosine floor is model-dependent (≈`0.82` for `e5-base-v2`; see `chartsearchai.grounding.minCosine`). On CPU-only servers the Tier-2 pass adds seconds after the answer is already readable, so `chartsearchai.grounding.async=true` moves it into a trailing `grounded` SSE event (see [Streaming search (SSE)](#streaming-search-sse)). See [ADR Decision 25](docs/adr.md#decision-25-citation-grounding-tier-1-cosine--tier-2-entailment) for the design rationale.

**A class code the answer states must come from a record it cites.** Neither tier can see the model *edit* a code it was handed: a citation of an injected finding never reaches Tier-2 at all (it is demote-only, above), and Tier-1 cosine barely moves when two characters change inside an alphanumeric token. Live, the chip said `J01MA` (fluoroquinolones) while the answer, citing that finding's record number, said `J01CA` (penicillins) ([#142](https://github.com/openmrs/openmrs-module-chartsearchai/issues/142)). So an exact token comparison runs after every answer, independently of `chartsearchai.grounding.enabled`: **when the records an answer cites state class codes**, any ATC-shaped code the answer states that is not one of them — nor one the question itself states — is logged at `WARN` with the codes those records do state. When they state none there was nothing to copy and it says nothing, which is what keeps `Q12H`-shaped prose out of it. It never rewrites the answer and nothing about it reaches the wire — it is a maintainer's signal, not a clinician's, so it has no global property; silence it by muting the `…api.impl.ClassCodeFidelityCheck` logger. See [ADR Decision 35](docs/adr.md#decision-35-a-class-code-in-the-answer-must-come-from-a-record-the-answer-cites).

### Drug-reference injection & safety validation

When `chartsearchai.drugReference.enabled` is `true` (off by default), three additive stages run around the answer — two pre-answer, one post:

- **Injection (pre-answer)** — clinical drug-reference entries matching the question (by alias) or the patient's active orders (by whatever the reference data resolves an order by — its concept's ATC code or its own display name, [#151](https://github.com/openmrs/openmrs-module-chartsearchai/issues/151) — scoped to orders clinically related to the question's drug: same ATC subgroup or same curated cross-reactivity group) are appended to the chart as numbered, citable records carrying the `drug_reference` resource type. Numeric dosing is age-gated; an entry's free-text `warnings` (e.g. a Reye-syndrome caution) render into the record so the LLM can cite them. This lets the LLM ground reference facts (dosing, warnings, interactions, contraindications) the same way it grounds chart records. Where a dataset files one substance as several rows (route or formulation variants), a record is rendered from one of them. Where this response names that substance by a *different* row — because the patient's own record names it — the record says so, naming the row it describes against that one ([#237](https://github.com/openmrs/openmrs-module-chartsearchai/issues/237)); and it states the dosing the substance's other resolved rows publish wherever that differs from its own ([#259](https://github.com/openmrs/openmrs-module-chartsearchai/issues/259)). So a citable ceiling cannot read as the whole substance's, and a ceiling a warning quotes from another row of the same substance is in the record beside it — for the rows this request resolved, which is every row the pre-answer pass can see. Both are absent — byte-for-byte unchanged output — for a substance the dataset files as one row, which is every entry of the bundled curated seed. Their reachability then differs: the naming half needs only a multi-row substance, so a `ddinter` deployment reaches it (the shipped 19 MB knowledge base files 129 of its 2086 substances as more than one row, across 2283 entries), while the dosing half additionally needs published age bands, which no `ddinter` file carries — so it is reachable only for a deployment that authors per-presentation dosing in a curated file. A record lists **every** contraindication the entry publishes, because those are properties of the drug rather than of this patient — and, immediately before that list, names which of them this patient's chart records and which it does not, so a model reading citable evidence is not left to infer either ([#208](https://github.com/openmrs/openmrs-module-chartsearchai/issues/208)). Both halves are named because naming only the recorded ones was measured making a local model's answer *worse*: it read the whole list under the patient framing. The list itself is never filtered — it is the only reference material the prompt carries about the drug. Three things are never claimed: a rule this module cannot put to the chart at all (a `type` that is neither `allergy` nor `condition`, or a rule with no token) is listed and claimed **neither** way rather than reported absent; the reading stands down entirely when `drugSafety.validateAnswers` or `drugSafety.warnOnContraindications` is off, because it is the record's half of a chip and must not outlive one; and it reads the same allergy and condition lists the chips do, so where those cannot be read the record and the chips fall silent together. Neither the `ddinter` nor the `atc` source publishes contraindications, so this costs those deployments nothing.
- **Active-order reconciliation (pre-answer)** — the safety stages read the patient's active drug orders straight from `OrderService`, while the answer is grounded only in the retrieved chart. When those two disagree — an active order the chart carries no `drug_order` record for — the module used to publish both sides of the disagreement: a chip naming "active order simvastatin" beside an answer stating "No active medications are recorded." ([#118](https://github.com/openmrs/openmrs-module-chartsearchai/issues/118)). The unsubstantiated orders are now appended as citable records carrying the `active_drug_order` resource type, and the discrepancy is logged at `WARN` with counts. The chip is never suppressed: it comes from the authoritative read. Only the orders the chart is actually missing are injected, so an agreeing chart is left byte-identical; and only when the chart is one that should carry every drug-order record (the full chart, or a query-scoped slice whose typed scope includes drug orders — a slice legitimately omits them for an unrelated question, where absence says nothing). The usual cause of a disagreement is the querystore index being behind the database, which is querystore's to fix — this module only stops it degrading into a self-contradiction.
- **Safety validation (post-answer)** — a deterministic check annotates the answer with non-blocking `safetyWarnings` (overdose / interaction / contraindication), computed from the reference table and the patient's age, weight, active orders, allergies, and conditions. The overdose check compares the answer's daily total against the age band's `maxDailyDoseMg` and — when a fresh weight is on record — the per-administration dose against `mgPerKgMax` × weight. Where a dataset files one substance as several rows, the stated dose is read once for the **substance** rather than once per row — reading it per row meant only the row whose alias the answer's wording happened to use ever had a dose to compare, so a sibling publishing a stricter ceiling was never reached and a real overdose could raise nothing at all ([#245](https://github.com/openmrs/openmrs-module-chartsearchai/issues/245)) — and every row is still tried, so a ceiling published only by a sibling row still warns; the warning is named after the row the patient's chart records and says so when the ceiling it quotes came from a different row ([#208](https://github.com/openmrs/openmrs-module-chartsearchai/issues/208)). Contraindication and interaction checks fire on the dataset's hand-authored rules and — using only ATC codes — on **class membership**: the same ATC level-4 chemical subgroup — one whose own published name names chemistry or a molecular target, since a shared *purpose* justifies a duplicate-therapy claim but not a cross-reactivity one ([#183](https://github.com/openmrs/openmrs-module-chartsearchai/issues/183)) — or, across ATC branches, the same curated **cross-reactivity group** (`cross-reactivity-groups.json` — how an aspirin recommendation warns on an ibuprofen allergy, the linkage classification alone cannot make). This is how the rule-less `atc` source still produces warnings. One contraindication needs neither a rule nor a code: a recorded allergy to the **very drug in play** is identity, so it fires even for a drug the loaded dataset cannot classify at all ([#135](https://github.com/openmrs/openmrs-module-chartsearchai/issues/135)). One warning per clinical fact — the most specific match wins. It never rewrites or blocks the answer; the clinician decides. It is conservative: a warning fires only when a value can actually be computed or matched. The drugs checked are those the question names plus those the answer names on its own authority, so a chart-sufficient answer naming no reference drug produces nothing — with two deliberate exceptions, both of which take their subjects from the patient's own active orders rather than from any wording. A question that asks to be *screened* for interactions and names no drug the loaded dataset carries has those orders checked against each other ([#113](https://github.com/openmrs/openmrs-module-chartsearchai/issues/113)); that screen is still no-false-positive, reporting only pairs the reference data actually relates (unrated rules included — they are exempt from the severity floor, not filtered by it). And those orders are checked against the patient's own allergy and condition records — "is the patient allergic to something they are taking?" is a fact about the chart, and gating it on the answer's wording alone is what hid it: a prescribed drug appears in a cited `drug_order` record, so the echo scoping below treated the answer's mention of it as a recitation ([#143](https://github.com/openmrs/openmrs-module-chartsearchai/issues/143)). That join is scoped to the response's own subject matter — either side of the chip, the drug or the recorded finding, must be named by the question, the answer or a cited record, with a medication-, allergy- or condition-domain question keeping the corresponding list in scope wholesale — because an annotation that ignores what was asked is an alert, and this module has none of an alerting system's machinery to carry one responsibly. It reports only a drug the patient is on whose own records contraindicate it, and stands down entirely when neither an allergy nor a condition is recorded. A drug the answer mentions only by echoing a record it cites (a partner recited out of an injected reference record, an allergy reported off the chart) is a mention, not a proposal, and is not validated ([#105](https://github.com/openmrs/openmrs-module-chartsearchai/issues/105)). Rule-based interaction chips additionally respect a severity floor when their dataset rates rows (`chartsearchai.drugSafety.minInteractionSeverity`, default `minor` — filters DDInter's no-mechanism Unknown tier); unrated curated rules are always shown. Chip labels append the everyday generic as a synonym when the dataset's display name diverges from it (`Acetylsalicylic acid (aspirin)`), so a warning is recognizable against both the question's vocabulary and the chart's; entry names themselves are never rewritten (most divergences are INN-vs-USAN pairs a rename would mistranslate).

The clinical knowledge lives in configurable data files (`ddi-knowledge-base.json`, `drug-reference.json`, `cross-reactivity-groups.json`), not in code. The overdose dose-parser recognises the literal unit `mg` only — doses written in grams are not flagged (the conservative, no-false-positive direction). See [ADR Decision 23](docs/adr.md#decision-23-drug-reference-injection--post-answer-drug-safety-validation) and [ADR Decision 27](docs/adr.md#decision-27-drug-safety-parity-follow-through--weight-aware-dosing-curated-cross-reactivity-groups-prose-warnings) for the full design, and [Drug knowledge base — demo setup](docs/drug-kb-demo.md) for a reproducible way to exercise the Decision-23 paths on a standalone (seed scripts + query cheat-sheet; the Decision-27 additions — weight-aware per-dose and cross-branch groups — are pinned by evals and context tests, and the demo doc notes what to seed to see them live).

**Bundled knowledge-base attribution.** The dataset the module ships and selects by default is a reformatting of **DDInter 2.0** (Xiong G, *et al.*, *Nucleic Acids Research*, 2025 — <https://ddinter2.scbdd.com/>), normalized to RxNorm and cross-walked to CIEL by the [openmrs-ddi-knowledge-base](https://github.com/pbiondich/openmrs-ddi-knowledge-base) data project, and bundled byte-identical to that project's release. It adds no interaction claim of its own. DDInter's own data terms are CC BY-NC-SA 4.0; consult them before a use the upstream project's terms review does not cover. It is an academic database rather than a government-agency product, which is a governance consideration for clinical deployment — stated here rather than left to be discovered, and the reason the whole feature is opt-in. Nineteen of its 2283 rows publish a name that denotes a different substance, or key a derivative with its parent; the module detects those at load, reports them on [`/drugreferencestatus`](#drug-reference-status), and the remedy is an upstream data fix ([#196](https://github.com/openmrs/openmrs-module-chartsearchai/issues/196)) rather than a change here — see [ADR Decision 36](docs/adr.md#decision-36-the-shipped-default-is-the-whole-ddinter-knowledge-base).

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
    { "index": 3, "resourceType": "order", "resourceUuid": "a8f5f167-4ee2-4d2a-94f9-3f3f86d2e9b6", "date": "2025-03-15", "grounded": null, "group": "chart", "source": null, "withheldInteractions": 0 },
    { "index": 1, "resourceType": "order", "resourceUuid": "5946f880-b197-400b-9caa-a3c661d71165", "date": "2025-01-10", "grounded": null, "group": "chart", "source": null, "withheldInteractions": 0 }
  ],
  "safetyWarnings": []
}
```

`questionId` is a string identifier for this query, used to submit feedback (see below). It is omitted if audit logging fails.

Each reference carries a `grounded` field — `true` / `false` once [citation grounding](#citation-grounding) has verified it, or `null` when grounding is disabled (the default, shown above), did not check that citation, or the citation is `reference`-group (always, see below). The key is always present.

Each reference also carries a `group`, derived from its `resourceType`, telling a client what kind of source it is:

| `group` | Meaning |
| --- | --- |
| `chart` | A record retrieved from **this patient's chart** — evidence about the patient. |
| `reference` | **Module-supplied reference prose** injected by [drug-reference injection](#drug-reference-injection--safety-validation), not a record about this patient — a drug knowledge-base entry (`drug_reference`), or one of the module's own deterministic safety findings (`safety_finding`, the record form of a `safetyWarnings` chip). |

Injected does not imply `reference`: an `active_drug_order` record is injected by the module but is the patient's own active order read from `OrderService` (see [active-order reconciliation](#drug-reference-injection--safety-validation)), so it groups as `chart` and carries the real `Order` uuid — meaning a client *can* navigate to it like any other chart citation. Note that `group` alone is not enough to make that happen: a client that also keys a chart-tab route or a display label off `resourceType` needs a row for `active_drug_order`, or the citation lands on its default tab under a raw `active_drug_order` label. The reference frontend needs that row added — it maps `drug_order` but not `active_drug_order` — so treat the navigability above as the wire contract's guarantee, not as already realised end to end.

Render the two groups distinctly: a `reference` entry is a pointer into a drug knowledge base, so presenting it alongside chart records without distinction lets module-supplied text read as chart evidence. Prefer `group` over testing `resourceType` against `drug_reference` client-side — that is what `group` is for, and it keeps the classification in one place when another kind of injected record is added. Do not use `group` to hide references — a `reference` entry is the disclosure of where a drug-interaction statement came from, and the answer prose still cites it by number.

**A `reference`-group citation never carries a grounding verdict.** Its `grounded` key is always present and always `null` — never `true`, never `false` — on the `/search` response and on all three SSE events that carry references. This holds however the client is written, because the withholding is server-side and is keyed on **`group`**: every `reference`-group type is affected, which today means `drug_reference` and `safety_finding` (the module's own deterministic safety findings, injected as citable records since [#110](https://github.com/openmrs/openmrs-module-chartsearchai/issues/110)), and a `reference` type added later is covered without a client changing. Those citations are [demote-only](#citation-grounding), so there was never a `true` to publish; a Tier-1 off-topic `false` was published until [#201](https://github.com/openmrs/openmrs-module-chartsearchai/issues/201), and it is now withheld too. It meant "this citation is not about that record", which is not what a grounding badge says, and a client keying its badge on `resourceType` rather than on `group` rendered it as *"Unsupported — the cited record may not support this statement"* on a deterministic Major-interaction finding. Rather than require every client to read one field to interpret another, the field is no longer offered: render a `reference` entry from its `group`, and treat `null` here as "grounding does not apply", not as "unverified evidence". Faithfulness of reference content is checked by the `safetyWarnings` chips instead. None of this applies to `active_drug_order`: that record asserts one drug of this patient, with no subject roles to swap, so a passing verdict is real assurance — it groups as `chart` and is verified and published normally.

A client must still not treat the `reference`-group types differently from each other. The grounding field can no longer be got wrong, but the badge, the label and the navigation target can be: keying any of them on `resourceType` gives `safety_finding` whatever the default branch happens to be, which is how this was found.

Each reference also carries `source` and `withheldInteractions`, the citation's metadata:

| field | Meaning |
| --- | --- |
| `source` | Where the cited record's content came from — the dataset attribution of an injected **drug-reference** entry (e.g. `"DDInter 2.0 (via openmrs-ddi-knowledge-base)"`). `null` for a `chart` entry, whose provenance is the patient's own record, and `null` for a module-derived finding, which is computed rather than quoted from a dataset. So do not key rendering on `group`: a `reference`-group entry may legitimately carry no attribution — branch on the value, not the group. |
| `withheldInteractions` | How many of the cited record's interaction partners the record does not show, so a client can say the citation shows a subset. `0` when it shows them all, and for every record with no interactions. Two rules withhold, and the second is usually the bigger one: the per-record render budget, and — once a partner the patient is actually on is shown — the rest of the dataset being represented by a single partner instead of in full. So a large count normally means "not relevant to this patient", not "too long to fit": say the citation shows a subset, **not** that partners were omitted for length. Nothing is withheld from safety checking — the `safetyWarnings` validator reads every interaction regardless. |

Both keys are always present. They are fields rather than sentences inside the record because everything in a record's text is quotable, and the model quoted both into clinician-facing answers — a one-drug safety question came back with the module's own truncation counter and dataset attribution appended to the prose (issue #117). Render them beside the citation, not as part of the answer.

The array is ordered so the groups are contiguous, `chart` first, preserving the upstream order (most recent first, undated last) within each group — so a client that just renders the array in order gets the grouping without doing any work. `index` is unaffected by that ordering; it remains each record's citation number, matching the inline `[N]` markers in the answer.

`safetyWarnings` is an array of non-blocking drug-safety advisories (each `{ type, drug, detail }`, where `type` is `overdose` / `interaction` / `contraindication`). `detail` is one complete standalone sentence that already leads with the drug — **clients should render `detail` alone**; prefixing `drug` duplicates the subject ("Aspirin: Aspirin interacts with…"). The key is always present and empty unless the optional drug-reference feature is enabled and something was flagged (see [Drug-reference injection & safety validation](#drug-reference-injection--safety-validation)).

**`drug` identifies a substance, not a finding, so it is not a deduplication key.** It carries the drug's display label (possibly with a parenthesized generic synonym) — since [#206](https://github.com/openmrs/openmrs-module-chartsearchai/issues/206) the substance's name rather than whichever dataset row a check matched. Several warnings about one substance therefore carry the same string: [#238](https://github.com/openmrs/openmrs-module-chartsearchai/issues/238) records a patient carrying seven hydrocortisone chips that a client collapsing on `(type, drug)` would reduce to one, discarding six distinct findings. Until #238 this section said the opposite — that `drug` was there "for grouping, sorting, and deduping". Nor is it a stable label to group on: some checks are deliberately exempt and name the row the chart itself records, so one substance can appear under two labels in one response. Key per-finding identity on `detail` — of the three fields, the one that varies between warnings about a single substance, since it names the interacting order, the allergen or the ceiling — or on the whole warning.

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
| `thinking` | A chunk of the model's reasoning, emitted before the answer; render distinctly (e.g. a collapsible panel), never as the answer |
| `preliminary` | A chunk of the fast preview reasoning pass, ahead of the committed answer; render like `thinking`, and expect the committed reasoning to replace it. Requires **both** `chartsearchai.progressiveReasoning.enabled=true` and `chartsearchai.chartMode=fullChart`, so it never fires on a default install |
| `token` | A chunk of the answer text as it is generated |
| `references` | The answer's citations the moment the answer is complete — before grounding verdicts exist; render as unverified until verdicts arrive |
| `done` | Final JSON with the complete answer, references (`chart` group first, upstream order — most recent first, undated last — within each group, with `index`, `resourceType`, `resourceUuid`, `date`, `grounded`, `group`, `source`, `withheldInteractions`), `safetyWarnings`, `questionId`, and disclaimer. With `chartsearchai.grounding.async=true`, `done` is emitted as soon as the answer is complete — its references carry no verdicts yet and `safetyWarnings` is empty (validation runs with grounding) |
| `grounded` | Only with `chartsearchai.grounding.async=true`: the references re-sent with their grounding verdicts (`grounded` true/false/null — always `null` for a `reference`-group citation, as everywhere else) once Tier-2 verification completes, plus the final `safetyWarnings`, with the same `questionId`. Keep consuming the stream after `done` to receive it |
| `error` | Error message if something goes wrong |

**Keep-alive comments.** Between events the stream also carries SSE *comments* — lines opening with `:` — one before generation begins and one every 15 seconds until the answer is finished. They are not events and carry no data; their only job is to stop a reverse proxy closing a connection it has read nothing on. A client must skip any line beginning with `:` (as the SSE spec requires) rather than treat it as a frame, with whatever parser it uses: `EventSource` would do that for you, but it issues a GET and sends no body, so it cannot reach this endpoint at all; the module's own frontend reads the stream with a `fetch` + `getReader()` parser. This matters because on a CPU-only server the entire pre-answer wait is silent, and a proxy cannot tell that silence from a hung origin: measured on this project's own demo 2026-08-19, Gemma 4 E4B queries were closed at ~125 s having delivered **zero** bytes and no `error` event, while the same question served by E2B (first `thinking` at 27–38 s) completed at 149–154 s. Cloudflare closes a silent origin connection at ~120 s and nginx's `proxy_read_timeout` defaults to 60 s — both shorter than `chartsearchai.llm.timeoutSeconds` (300), which until this keep-alive made the network the effective ceiling. It no longer is: the stream is never read-idle, so a slow query now runs to `timeoutSeconds`, holding its request thread and llama-server's single slot (`--parallel 1`) for that long. Longer than before, but the old ceiling was not the ~120 s cut — nothing was written before the model's first output, so a cut connection went unnoticed until the request's first write attempt, which on the E4B runs above landed *after* the cut and is where the request unwound. Set `timeoutSeconds` on that basis rather than on the proxy's window. The blocking [`/search`](#search) endpoint has no equivalent protection, because it writes nothing until the answer is complete: on hardware where one answer outlasts the proxy's timeout, that endpoint fails where this one now succeeds.

### Warmup

Pre-warms the LLM prompt cache for a patient's chart so the first AI query skips the full prefill cost. The frontend should call this when a patient chart is opened. Returns `202 Accepted` immediately; the warmup runs on a background daemon thread. Requires the **"AI Query Patient Data"** privilege.

```
POST /ws/rest/v1/chartsearchai/warmup
Content-Type: application/json

{
  "patient": "patient-uuid-here"
}
```

No-op when `chartsearchai.llm.engine` is `remote`, and whenever `chartsearchai.chartMode` is `queryScoped` (the default) — a per-question slice has no reusable chart prefix. Disable entirely with `chartsearchai.warmupEnabled=false`. Concurrent warmups for different patients are coalesced — only the most recently submitted patient runs, since llama-server processes one request at a time.

**Disk-persisted KV cache (the biggest CPU-only first-query win).** The plain warmup above primes the prompt cache *in RAM* — it helps only until the model is evicted (another patient's query takes the single slot) or the llama-server process restarts, after which the next visit pays the full chart prefill again (tens of seconds to minutes on a GPU-less host). The disk-persisted KV cache fixes that and is **on by default** (`chartsearchai.llm.kvCacheDir` empty → `<appdata>/chartsearchai/kvcache`; set a path to relocate it, or `off` to disable): llama-server is launched with `--slot-save-path`, so both the warmup **and the streaming query path save and restore** each patient's prefilled chart KV (~tens of ms of disk I/O) instead of recomputing. Because the prefill is the entire pre-answer wait on a CPU-only server, this turns a slow first query into a fast one (measured on the standalone in CPU-only mode: ~19–60 s to first token → ~0.9 s after a disk restore), and it survives restarts and evictions that the RAM cache does not. The restored KV is byte-for-byte identical to a fresh prefill, so answers and citations are unchanged (verified: identical answer text and grounding verdicts for the same question on the restore vs. prefill paths). Only the first-ever visit to a patient pays a prefill (to create the file); subsequent visits restore. See `chartsearchai.llm.kvCacheDir` / `chartsearchai.llm.kvCacheMaxEntries` in the [config table](#5-configure).

Restore is **not** confined to the chart-open warmup — the streaming query path restores too. When a query arrives and the patient's chart prefix is not resident in this llama-server process's RAM prompt-cache pool (after a process restart / idle-unload, a prompt-cache overflow, or simply because no warmup fired or finished before the question), the query restores the KV from disk (~tens of ms) on the request thread instead of re-prefilling the whole chart. A genuinely cold query (no disk entry yet) prefills as before and then **saves** its KV, so the next visit is fast even if `/warmup` is never called; a warm or alternating-patient query (chart already in the RAM pool) does no extra disk I/O. Measured on the standalone in CPU-only mode: a cold-RAM query that previously re-prefilled (~20 s small chart, ~100 s large chart to first token) now restores in ~tens of ms (≈1 s to first token plus any one-time llama-server process startup). The restore is gated by the same chart-byte-stability condition as the warmup, which holds in every querystore retrieval mode (with `preFilter=true` the focus hint is a small trailing payload that doesn't break the chart-prefix match), so a per-patient KV entry is keyed for any patient. One limitation remains on busy hosts: **`kvCacheMaxEntries` bounds the file *count*, not total bytes** — since each file scales with chart length, worst-case disk use is roughly `kvCacheMaxEntries × your largest chart`, and under heavy multi-patient churn the count-cap eviction (oldest by mtime) can evict another patient's still-current entry (harmless — they re-prefill on next visit).

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

### Drug-reference status

Which drug-reference dataset the module is **actually** using. Requires the core **"Get Global Properties"** privilege — which the `Authenticated` role holds on a default install, so treat this as readable by any logged-in user. It carries configuration metadata only: no patient data, and no absolute server paths.

```
GET /ws/rest/v1/chartsearchai/drugreferencestatus
```

```json
{"enabled": true, "loaded": true, "inert": false, "entryCount": 2283,
 "sourceFormat": "ddinter", "configuredSourceFormat": "ddinter",
 "configuredDataFilePath": "chartsearchai/ddi_knowledge_base.json",
 "origin": "appdata:chartsearchai/ddi_knowledge_base.json",
 "findings": []}
```

Ask this — not the log — after editing `sourceFormat` or `dataFilePath`. The dataset load is lazy and cached for the life of the module, so the most recent `Loaded N …` line may belong to a load performed before those properties were last edited, or to a process a failed restart left running. Reading this endpoint reports the load that filled the cache, performing it if it has not happened yet.

- **`inert: true`** means a source *was* selected and produced **zero** entries: no interaction, allergy or contraindication warning can be raised, and every safety question answers as though there were nothing to find, while the module looks healthy. Usually a `sourceFormat`/`dataFilePath` mismatch — each format parses only its own shape and returns nothing, without failing, for another's. Also logged at WARN when it happens. **Read `findings` before guessing which mismatch it is**: since [#242](https://github.com/openmrs/openmrs-module-chartsearchai/issues/242) the parser says so itself, and a `dataset-missing-a-required-table` finding names the table your file omits, and — where that parser could count what it was discarding — how many rows went with it. The exception is `sourceFormat=atc`, which resolves its own file and reports no findings at all: its dataset is line-based, so there is no table to name, and an inert `atc` load is diagnosed only by this bullet.
- **`findings`** is the loader's self-audit of the dataset it just read: what violated an assumption the loader's own code makes, and what it did about each — one object per rule that fired, carrying `rule`, `remedy` (`reported` / `repaired` / `dropped`), `occurrences` and a `detail` naming the rows or files to look at. Empty on a healthy load, and empty for every dataset the module *authors* — but **not** for the DDInter knowledge base it redistributes, which reports 19 known rows and 28 dropped self-paired ones whose remedy is an upstream data fix ([ADR Decision 36](docs/adr.md#decision-36-the-shipped-default-is-the-whole-ddinter-knowledge-base)). Ask this rather than the log for the same reason as the rest of the endpoint: the load is lazy, so the most recent log line may describe a previous one. Everything here is *also* logged at the moment it happens — **at WARN, except a data finding about the dataset the module ships, which is INFO** because no operator can act on it. The two channels carry the same findings and neither is a summary of the other; only the level differs, so read `findings` rather than inferring anything from the level.
- **`enabled: false`** with `loaded: false` is the default, legitimate state: the feature is off, so nothing is loaded. Reading the status does not trigger a load in that case.
- **`origin`** is what was *read*, marked with the space it came from — `appdata:<path>` for an operator file, `classpath:/chartsearchai/…` for the bundled dataset; `configuredDataFilePath` is what was *asked for*. **Your file loaded exactly when `origin` is `appdata:` + that path.** They differ when the configured file could not be read and the bundled dataset was used — which yields a plausible non-zero `entryCount`, so the count alone does not tell you your file loaded. `origin` is relative rather than absolute because `Get Global Properties` is held by the `Authenticated` role on a default install, so any logged-in user can read this endpoint, while core keeps the absolute application-data path behind `View Administration Functions`.

## Patient access control

By default, any user with the **"AI Query Patient Data"** privilege can query any patient. To add patient-level restrictions (e.g., location-based or care-team-based), provide a custom Spring bean that implements the `PatientAccessCheck` interface:

```xml
<bean id="chartSearchAi.patientAccessCheck"
      class="com.example.LocationBasedPatientAccessCheck"/>
```

This overrides the default permissive implementation.

## Evals

The project includes an eval framework covering citation accuracy, absent-data answering, drug-safety warnings, and prompt-injection resistance. All of it runs offline **except** the prompt-injection suite, which drives a real LLM.

### Running evals

```
mvn test -pl api -Dtest="*EvalTest"
```

Or run a specific suite:

```
mvn test -pl api -Dtest="CitationEvalTest"
mvn test -pl api -Dtest="AbsentDataEvalTest"
mvn test -pl api -Dtest="DrugSafetyEvalTest"
mvn test -pl api -Dtest="PromptInjectionEvalTest" -Dchartsearchai.prompt.injection.test=true
```

The prompt-injection suite needs **both** that system property **and** a reachable llama-server (it probes `chartsearchai.llm.serverPort`, overridable with `-Dchartsearchai.prompt.injection.endpoint`). Without one, every case is skipped by a JUnit assumption rather than failing — check the surefire report for skips before reading a green run as a pass.

### Adding cases

Each suite is driven by a JSON dataset. To add a case, append an entry to the relevant file:

| File | What it tests |
|------|---------------|
| `api/src/test/resources/eval/citation-eval-dataset.json` | Simulated LLM JSON → expected citation indices (F1) |
| `api/src/test/resources/eval/absent-data-eval-dataset.json` | Query → expected keywords in "no records" answer |
| `api/src/test/resources/eval/prompt-injection-eval-dataset.json` | Adversarial payload → LLM produces safe JSON, no system prompt leakage |
| `api/src/test/resources/evals/drug-reference/drug-safety-eval.json` | Patient + question → expected drug-safety warnings |

### Metrics report

The citation and prompt-injection suites append per-case rows to `api/target/eval-results.csv` (via `EvalReporter`) for tracking regressions over time; the citation suite also appends a summary row. The absent-data and drug-safety suites do not report to the CSV, so their results are only in the surefire output.

**The CSV gates nothing, and reaching it is not what makes a suite trustworthy.** No workflow uploads or reads it, `EvalReporter` swallows its own write failures rather than failing a test, and every one of the four suites is gated by its JUnit assertions either way — `CitationEvalTest`'s `avgF1 >= 0.8`, `DrugSafetyEvalTest`'s expected warning types, `AbsentDataEvalTest`'s stopword check. So the two suites that skip the CSV are no less gated than the two that write to it, and wiring them in would add rows to a file with no consumer. Read the surefire result, not the CSV. (#179 originally recorded the reverse; the CSV's missing consumer is why.)

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
