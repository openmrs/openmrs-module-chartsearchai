# Chart Search AI Module

[![Download Standalone](https://img.shields.io/badge/Download-O3_Standalone_with_Chart_Search_AI-blue?style=for-the-badge)](https://nightly.link/openmrs/openmrs-module-chartsearchai/workflows/build-standalone/main/openmrs-standalone-chartsearchai.zip)

An OpenMRS module that lets clinicians ask natural language questions about a patient's chart and get answers with source citations.

For project background, community discussion, and roadmap, see the [wiki project page](https://openmrs.atlassian.net/wiki/spaces/projects/pages/373325839/Chart+Search+aka+ChartSearchAI).

The standalone download above includes the backend module and frontend ESM. Chat uses med-agent-hub as its one inference and orchestration endpoint; the hub may use local or remote model-serving backends behind that boundary.

- **Clinical answer service**: med-agent-hub owns chart context, model/profile stages, deterministic checks, answer review, evidence grounding, and In-Depth generation.
- **Context sources**: configured behind med-agent-hub. Querystore is supported as one optional source, but ChartSearchAI does not import or require it.

## Table of Contents

- [Try it on the demo server](#try-it-on-the-demo-server)
- [Requirements](#requirements)
- [Docker](#docker)
- [Setup](#setup)
  - [1. Build](#1-build)
  - [2. Configure med-agent-hub](#2-configure-med-agent-hub)
  - [3. Optional context sources](#3-optional-context-sources)
  - [4. Install](#4-install)
  - [5. Configure](#5-configure)
  - [6. Grant privileges](#6-grant-privileges)
  - [7. Optional Querystore indexing](#7-optional-querystore-indexing)
- [Query behavior](#query-behavior)
- [API](#api)
  - [Chat](#chat)
  - [Streaming chat (SSE)](#streaming-chat-sse)
  - [Feedback](#feedback)
  - [Audit log](#audit-log)
- [Patient access control](#patient-access-control)
- [Validation and profiles](#validation-and-profiles)
- [Architecture](#architecture)
- [License](#license)

## Try it on the demo server

A live demo runs at **https://chartsearchai.openmrs.org** with the standard O3 reference patient set, so you can try Chart Search AI without installing anything.

1. Open https://chartsearchai.openmrs.org and log in (default credentials: `admin` / `Admin123`).
2. Click the magnifying-glass icon in the top header and search for **Betty Williams** — she is the reference patient with the most data on the demo (medications, vitals, conditions), so the AI has something to ground its answers in. Open her chart from the dropdown.

   ![Patient search overlay with "Betty" typed and Betty Williams in the result list](docs/images/ai-chart-search-patient-search.png)

3. Click the floating blue AI sparkle icon in the bottom-right corner of the chart (tooltip: *Ask AI about this patient*). A chat panel slides in.
4. Type a clinical question — e.g. *What medications is this patient on?*, *Any allergies?*, *Last 3 blood pressure readings* — and press **Send**, or click the microphone for voice input.
5. The answer appears when the backend emits the answer phase. Staged hub-backed models may then update the same message with answer-check status and a later in-depth section. The records the answer cites appear under **References**, numbered to match the inline citations (`[1]`, `[2]`, ...). Both the inline citations and the chips under **References** are clickable — they navigate to the relevant chart tab (Orders, Results, Allergies, Conditions, Programs, etc.) and highlight the source record. Every response carries the AI-generated disclaimer.

   ![AI Chart Search panel showing an answer with numbered citations on Betty Williams' chart](docs/images/ai-chart-search-demo.png)

6. Optionally rate the answer under **Was this helpful?** with **Helpful** / **Not helpful** and an optional comment. Feedback is recorded in the audit log alongside the question.

Notes:

- The AI button is only rendered for users with the **AI Query Patient Data** privilege.
- The launch surface is configurable via the frontend `chatLaunchMode` setting: `floating` (the bottom-right circular button used above), `workspace` (an icon in the top-right workspace strip that opens the chat as a docked workspace), or `both` (default).
- First-query latency on the demo reflects the configured LLM endpoint and any serving-side warmup. chartsearchai itself no longer owns model warmup or local model process lifecycle.
- The demo currently calls a remote/hub-backed LLM endpoint; latency reflects that endpoint, not an embedded OpenMRS JVM inference process.

## Requirements

- Java 11+
- OpenMRS Platform 2.8.0+
- Webservices REST module 2.44.0+
- Access to med-agent-hub, deployed locally as a sidecar or as a remote service.

## Docker

```bash
git clone https://github.com/openmrs/openmrs-module-chartsearchai.git
cd openmrs-module-chartsearchai
docker compose up --build
```

No model runtime is bundled into the OpenMRS module or its Docker image. The Docker deployment needs a configured med-agent-hub service. This demo distribution also includes Querystore as an optional context source; that does not create a ChartSearchAI module dependency.

First startup takes several minutes for database initialization and retrieval model provisioning. Once the logs show that OpenMRS has started, open http://localhost/openmrs/spa (default credentials: `admin` / `Admin123`). Subsequent starts are fast since the data volume persists.

Alternatively, download the [O3 Standalone with Chart Search AI](https://nightly.link/openmrs/openmrs-module-chartsearchai/workflows/build-standalone/main/openmrs-standalone-chartsearchai.zip) — a single zip with everything included, no Docker required (Java 21+ needed). See the [OpenMRS Standalone guide](https://openmrs.atlassian.net/wiki/spaces/docs/pages/25472583/OpenMRS+Standalone) for instructions.

## Setup

### 1. Build

```
mvn package
```

The `.omod` file is in `omod/target/`.

### 2. Configure med-agent-hub

chartsearchai does not bundle a model runtime or select model backends. It sends every chat turn to one med-agent-hub deployment. For local/offline use, run the hub as a local sidecar and let the hub manage its llama.cpp or other OpenAI-compatible serving backend.

Minimum required configuration:

| Property | Where | Description |
|----------|-------|-------------|
| `chartsearchai.hub.endpointUrl` | Global property | med-agent-hub `/v1/chat/completions` URL |
| `chartsearchai.hub.profileId` | Global property | Fallback product profile; defaults to `single-e4b-checked`. The ESM normally uses the hub-advertised default |
| `chartsearchai.hub.apikey` | `openmrs-runtime.properties` | Optional Bearer token for the hub |

The API key is read from `openmrs-runtime.properties` (not from the database) so it is never exposed in the Admin UI or database backups:

```
chartsearchai.hub.apikey=your-hub-api-key
```

The OpenMRS module does not manage model downloads, process lifecycle, KV caches, prompt-cache warmup, or stage composition. Those belong behind med-agent-hub.

### 3. Optional context sources

ChartSearchAI sends only patient identity, profile, question, and prior turns to med-agent-hub. Configure context sources in the hub. Inline chart data and other source adapters can run without Querystore.

To use Querystore as an optional hub source, deploy and configure it independently, then enable the corresponding med-agent-hub adapter. Querystore's e5-base-v2 deployment uses:

- ONNX model: https://huggingface.co/Xenova/e5-base-v2/resolve/main/onnx/model.onnx *(self-contained — see [ADR Decision 22](docs/adr.md#decision-22-e5-base-v2-for-the-querystore-backed-retrieval-path) for why this source over the canonical `intfloat/e5-base-v2`)*
- Vocab: https://huggingface.co/Xenova/e5-base-v2/resolve/main/vocab.txt

Place both at `<openmrs-application-data-directory>/querystore/` and wire the global properties documented in [Querystore deployment](#querystore-deployment) below.

> ChartSearchAI has no retrieval pipeline. Querystore owns its own index; med-agent-hub decides which configured context sources to use.

### 4. Install

Copy the `.omod` file into the `modules` folder of the OpenMRS application data directory (e.g., `<openmrs-application-data-directory>/modules/`). The module will be loaded on the next OpenMRS startup.

### 5. Configure

Set these global properties in **Admin > Settings**:

#### Hub connection

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.hub.endpointUrl` | *(empty)* | med-agent-hub chat-completions URL |
| `chartsearchai.hub.profileId` | `single-e4b-checked` | Fallback product profile when the request omits one |
| `chartsearchai.hub.apikey` | *(empty)* | Optional runtime-property Bearer token |

The API key is read from `openmrs-runtime.properties` (not from the database) so it is never exposed in the Admin UI or database backups. Add it to your runtime properties file:

```
chartsearchai.hub.apikey=your-hub-api-key
```

Configure model providers, credentials, and model-serving details in med-agent-hub rather than in OpenMRS.

#### Optional Querystore deployment

The [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) module is an optional, independently deployed context source. The demo Docker distribution includes it, but the ChartSearchAI OMOD does not require it. med-agent-hub's source configuration determines whether Querystore is queried. See Querystore's own documentation for supported deployment and indexing settings.

**Deployment checklist:**

1. med-agent-hub available and configured ([step 2](#2-configure-med-agent-hub)).
2. Querystore deployed and its model/index configured according to that module's documentation.
3. The Querystore source adapter enabled in med-agent-hub.

| Property | Value | Description |
|----------|-------|-------------|
| `querystore.embedding.modelFilePath` | `querystore/model.onnx` | Path to the ONNX embedder, relative to `<openmrs-application-data-directory>`. Querystore ships this with an empty default (the module is model-agnostic), so a fresh install must set it |
| `querystore.embedding.vocabFilePath` | `querystore/vocab.txt` | Path to the WordPiece vocab, same convention |
| `querystore.embedding.queryModelFilePath` | *(empty)* | Leave empty for `e5-base-v2`; set only for dual-encoder models like MedCPT |

These are Querystore settings, not ChartSearchAI settings. med-agent-hub must also have the source adapter's URL and credentials configured explicitly.

#### Hub-owned answer checks

Answer synthesis, temporal validation, citation grounding, drug-safety checks, and staged review are owned by med-agent-hub. chartsearchai relays the hub envelope, persists phased updates, and renders metadata such as `answerValidation`, `references[].groundingStatus`, `inDepth`, and `safetyWarnings` when present.

#### Rate limiting and caching

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.rateLimitPerMinute` | `10` | Maximum queries per user per minute. Set to `0` to disable |

#### Audit

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.auditLogRetentionDays` | `90` | Audit log entries older than this are purged daily. Set to `0` to retain all |

### 6. Grant privileges

| Privilege | Purpose |
|-----------|---------|
| **AI Query Patient Data** | Execute chart search queries |
| **View AI Audit Logs** | Access the audit log endpoint |

### 7. Optional Querystore indexing

When Querystore is deployed, it owns and maintains its index. ChartSearchAI has no index to build or maintain; see the Querystore repository for current indexing details.

## Query behavior

chartsearchai is an authorization, session, persistence, and streaming relay. It sends the patient uuid, selected hub profile, cleaned prior turns, and current question to med-agent-hub. The hub owns context supply, prompt assembly, temporal validation, citation grounding, answer review, In-Depth generation, and safety checks. chartsearchai preserves the returned lifecycle and evidence metadata.

This split keeps OpenMRS responsible for session state, authorization, audit, feedback, and source navigation, while the hub owns model orchestration and answer-quality gates.

## API

### Chat

Multi-turn: pass a prior response's `session` uuid (or the one returned by `GET /chat`) to continue that
conversation; omit it to use or open the caller's active session for the patient. Every chat turn relays
through the configured med-agent-hub endpoint — chartsearchai has no inference path of its own — see [Configure](#5-configure).

```
POST /ws/rest/v1/chartsearchai/chat
Content-Type: application/json

{
  "patient": "patient-uuid-here",
  "question": "What medications is this patient on?",
  "session": "existing-session-uuid (optional)",
  "profile": "single-e4b-checked"
}
```

Response:

```json
{
  "answer": "The patient is currently on Metformin [1] and Lisinopril [3]...",
  "disclaimer": "This response is AI-generated and may not be accurate...",
  "references": [
    { "index": 3, "resourceType": "order", "resourceUuid": "a8f5f167-4ee2-4d2a-94f9-3f3f86d2e9b6", "date": "2025-03-15" },
    { "index": 1, "resourceType": "order", "resourceUuid": "5946f880-b197-400b-9caa-a3c661d71165", "date": "2025-01-10" }
  ],
  "blocks": [],
  "confidence": { "answer": { "level": "green", "note": "" } },
  "answerValidation": { "status": "checked", "label": "Checked" },
  "safetyWarnings": [],
  "session": "session-uuid",
  "messageId": "assistant-message-uuid",
  "auditLogId": 42,
  "model": "single-e4b-checked"
}
```

`confidence`, `answerValidation`, and `safetyWarnings` are emitted by the selected hub profile.
`safetyWarnings` entries are non-blocking advisories.

### Streaming chat (SSE)

```
POST /ws/rest/v1/chartsearchai/chat/stream
Content-Type: application/json
Accept: text/event-stream

{
  "patient": "patient-uuid-here",
  "question": "What medications is this patient on?",
  "session": "existing-session-uuid (optional)",
  "profile": "single-e4b-checked"
}
```

The `X-ChartSearchAi-Session` response header carries the session uuid before the stream opens.

The product stream relays the hub profile's staged answer/validation/In-Depth sequence:

| Event | Description |
|-------|-------------|
| `answer_done` | The direct answer is complete; the envelope's `answerValidation.status` is `checking` and `inDepth.status` is `pending` |
| `answer_validation` | *(only when the level has a validator)* the same message updated after its self-check |
| `indepth_pending` | The in-depth analysis is about to start |
| `indepth_done` / `indepth_error` | The in-depth analysis completed or failed |
| `done` | Final envelope with the settled answer and the completed in-depth |
| `error` | Error message if something goes wrong |

Every persisted JSON event payload also carries `session`, `messageId`, `auditLogId`, `model`, and `disclaimer`.

### Serving-side warmup

Model readiness and prompt-cache preparation belong to med-agent-hub and its serving backend. For local demos, prepare the hub/router before recording latency-sensitive sessions.

### Feedback

Submit user feedback (thumbs up/down) for an AI response. Requires the **"AI Query Patient Data"** privilege.

```
POST /ws/rest/v1/chartsearchai/feedback
Content-Type: application/json

{
  "auditLogId": 42,
  "rating": "positive",
  "comment": "Accurate and helpful"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `auditLogId` | Yes | The numeric `auditLogId` from the chat response |
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

## Validation and profiles

Run `mvn test` for the module's authorization, persistence, relay, lifecycle, and API contracts. Clinical answer quality, temporal behavior, citations, context selection, and model comparisons are evaluated through the companion [clinical AI validation harness](https://github.com/pmanko/clinical-ai-validation-harness) against med-agent-hub's real profile path.

The UI obtains the current profile list, human labels, availability, and default directly from med-agent-hub through `GET /ws/rest/v1/chartsearchai/models`. ChartSearchAI does not maintain a model catalog.

### Historical model research

The table below is retained as historical background from earlier serving experiments. It is not the current profile catalog or a deployment recommendation; consult med-agent-hub metadata and published harness reports for current choices.

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
| **Gemma 4 E4B** | E4B (4.5B eff) | ~2.5GB | ~6–8GB | 128K tokens | ~10–20 tok/s | gemma |
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
| **Gemma 4 26B MoE** | 26B (3.8B active) | ~15GB | ~18–22GB | 256K tokens | ~3–6 tok/s | gemma |
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

A server running OpenMRS typically uses 1–2GB for the JVM heap. Size the LLM serving endpoint separately from the OpenMRS JVM; colocating both on one machine requires enough memory for both.

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
