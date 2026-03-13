# Chart Search AI Module

An OpenMRS module that lets clinicians ask natural language questions about a patient's chart and get answers with source citations.

## Requirements

- OpenMRS Platform 2.6.9+
- Webservices REST module 2.44.0+
- 8GB+ RAM (for LLM inference)

## Setup

### 1. Build

```
mvn package
```

The `.omod` file is in `omod/target/`.

### 2. Download the LLM model

Download Llama 3.2 3B (Q4_K_M quantization) in GGUF format (~2GB) from [Hugging Face](https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF).

Place the `.gguf` file inside the OpenMRS application data directory (e.g., `<openmrs-application-data-directory>/chartsearchai/`). Model paths are resolved relative to this directory for security.

### 3. Download the embedding model (optional)

If you plan to use the embedding search mode with ONNX semantic vectors, download the all-MiniLM-L6-v2 ONNX model (~90MB) from [Hugging Face](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2). You need both `model.onnx` and `vocab.txt` from the repository.

Place them alongside the LLM model (e.g., `<openmrs-application-data-directory>/chartsearchai/`).

This is not required if you use the default `llm` search mode or the `term-frequency` embedding provider.

### 4. Install

Upload the `.omod` file via **Admin > Manage Modules** in OpenMRS.

### 5. Configure

Set these global properties in **Admin > Settings**:

#### Required

| Property | Description |
|----------|-------------|
| `chartsearchai.llm.modelFilePath` | Relative path (within the OpenMRS application data directory) to the `.gguf` model file, e.g. `chartsearchai/Llama-3.2-3B-Instruct-Q4_K_M.gguf` |

#### Search mode

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.searchMode` | `llm` | `llm` (sends full chart to LLM) or `embedding` (RAG: retrieves relevant records first, then sends to LLM) |
| `chartsearchai.embedding.provider` | `term-frequency` | `term-frequency` (keyword-based) or `onnx` (semantic with all-MiniLM-L6-v2) |
| `chartsearchai.embedding.modelFilePath` | — | Required if using `onnx` provider. Relative path to the ONNX model file |
| `chartsearchai.embedding.vocabFilePath` | — | Required if using `onnx` provider. Relative path to the WordPiece `vocab.txt` file |

#### LLM tuning

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.llm.systemPrompt` | *(built-in clinical prompt)* | System prompt sent to the LLM. Instructs it to cite records by number |
| `chartsearchai.llm.timeoutSeconds` | `120` | Maximum seconds to wait for LLM inference before timing out |

#### Rate limiting and caching

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.rateLimitPerMinute` | `10` | Maximum queries per user per minute. Set to `0` to disable |
| `chartsearchai.cacheTtlMinutes` | `5` | How long to cache identical (patient, question) answers. Set to `0` to disable |

#### Audit

| Property | Default | Description |
|----------|---------|-------------|
| `chartsearchai.auditLogRetentionDays` | `90` | Audit log entries older than this are purged daily. Set to `0` to retain all |

### 6. Grant privileges

| Privilege | Purpose |
|-----------|---------|
| **AI Query Patient Data** | Execute chart search queries |
| **View AI Audit Logs** | Access the audit log endpoint |

### 7. Backfill embeddings (embedding mode only)

If you set `chartsearchai.searchMode` to `embedding` on a system with existing patient data, you need to build the initial embedding index. Go to **Admin > Scheduler > Manage Scheduler** and run the **"Chart Search AI - Embedding Backfill"** task. It indexes only patients that don't have embeddings yet, so it's safe to re-run. After the initial backfill, new data is indexed automatically via AOP hooks on encounter, obs, condition, allergy, and order saves.

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
  "references": [
    { "index": 1, "resourceType": "order", "resourceId": 456 },
    { "index": 3, "resourceType": "order", "resourceId": 789 }
  ]
}
```

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
| `done` | Final JSON with the complete answer, references, and disclaimer |
| `error` | Error message if something goes wrong |

### Audit log

Requires the **"View AI Audit Logs"** privilege.

```
GET /ws/rest/v1/chartsearchai/auditlog?patient=...&user=...&fromDate=...&toDate=...&startIndex=0&limit=50
```

All query parameters are optional. `fromDate` and `toDate` are epoch milliseconds. Returns paginated results ordered by most recent first, with a `totalCount` for pagination.

## Patient access control

By default, any user with the **"AI Query Patient Data"** privilege can query any patient. To add patient-level restrictions (e.g., location-based or care-team-based), provide a custom Spring bean that implements the `PatientAccessCheck` interface:

```xml
<bean id="chartSearchAi.patientAccessCheck"
      class="com.example.LocationBasedPatientAccessCheck"/>
```

This overrides the default permissive implementation.

## Architecture

See [docs/adr.md](docs/adr.md) for architectural decisions and design rationale.

## License

This project is licensed under the [MPL 2.0](http://openmrs.org/license/).

Llama 3.2 is licensed under the [Llama 3.2 Community License](https://www.llama.com/llama3_2/license/), Copyright (C) Meta Platforms, Inc. All Rights Reserved.
