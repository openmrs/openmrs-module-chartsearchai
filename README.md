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

Place the `.gguf` file somewhere accessible on the server (e.g., `/opt/openmrs/models/`).

### 3. Install

Upload the `.omod` file via **Admin > Manage Modules** in OpenMRS.

### 4. Configure

Set these global properties in **Admin > Settings**:

| Property | Required | Description |
|----------|----------|-------------|
| `chartsearchai.llm.modelPath` | Yes | Path to the `.gguf` model file |
| `chartsearchai.searchMode` | No | `llm` (default) or `embedding` |
| `chartsearchai.embedding.provider` | No | `term-frequency` (default) or `onnx` |
| `chartsearchai.embedding.modelPath` | Only if using `onnx` | Path to the ONNX embedding model file |

### 5. Grant privilege

Assign the **"AI Query Patient Data"** privilege to users or roles that should have access.

## Usage

```
POST /ws/rest/v1/chartsearchai/search
Content-Type: application/json

{
  "patientUuid": "patient-uuid-here",
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

## Architecture

See [docs/adr.md](docs/adr.md) for architectural decisions and design rationale.

## License

This project is licensed under the [MPL 2.0](http://openmrs.org/license/).

Llama 3.2 is licensed under the [Llama 3.2 Community License](https://www.llama.com/llama3_2/license/), Copyright (C) Meta Platforms, Inc. All Rights Reserved.
