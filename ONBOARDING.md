# Chart Search AI — Onboarding

An OpenMRS module that lets clinicians ask natural-language questions about a patient's chart and get answers with source citations. A local (or remote) LLM answers over a chart assembled by the **querystore** module, which owns retrieval and embeddings.

## Get oriented fast

- **Read first:** `CLAUDE.md` (project rules — TDD, root-cause-over-patch, and the **API-surface rules** below), `README.md` (setup + platform notes), `docs/adr.md` (decision log).
- **Retrieval lives in querystore, not here.** chartsearchai has no in-process embedding/Lucene/scoring pipeline. Retrieval and citation grounding both use querystore's e5-base-v2 model. Retrieval changes and retrieval-quality eval belong in `openmrs-module-querystore`.
- **Two engines:** `chartsearchai.llm.engine` = `local` (bundled `llama-server` subprocess, default — data stays on the host) or `remote` (OpenAI-compatible API). A **local** LLM is a hard requirement for production.

## Build & test

```bash
mvn install                       # full build (api + omod), produces omod/target/chartsearchai-*.omod
mvn -pl api test                  # api unit tests
mvn test                          # api + omod
mvn -pl api test -Dtest=ClassName # one test class
```

Tests must call the **real production pipeline** with real datasets — no mocks/reimplementations of pipeline logic (see `CLAUDE.md`). Follow TDD: write the failing test first.

## Run it locally

A configured standalone lives at `test/referenceapplication-standalone-3.7.0-SNAPSHOT` (RefApp 3.7, port **8081**, login `admin` / `Admin123`):

```bash
cd test/referenceapplication-standalone-3.7.0-SNAPSHOT
nohup java -jar openmrs-standalone.jar &     # nohup so it survives the shell
# REST base once up: http://localhost:8081/openmrs/ws/rest/v1
```

To redeploy after a build: stop it (`pkill -9 -f openmrs-standalone.jar; pkill -9 -f mariadbd; pkill -9 -f llama-server` — the embedded MariaDB must be killed too or the next boot can't lock the DB), copy `omod/target/chartsearchai-*.omod` into `appdata/modules/`, restart. Local GGUF models live under `MODELS/`; the standalone bundles Gemma E2B/E4B.

## API-surface rules (do not bypass)

These are the only correct entry points — never reimplement their logic inline:

- **Prefixed text:** `ChartSearchAiUtils.buildPrefixedText(resourceType, text)`
- **Cosine similarity:** `ChartSearchAiUtils.cosineSimilarity()`
- **Chart assembly:** `ChartBuildingStrategy.buildChart()` → `QueryStoreChartBuilder.build()` (querystore owns retrieval)
- **Global-property reads:** `ChartSearchAiUtils.getBooleanGlobalProperty` / `getStringGlobalProperty` (fail-safe on a missing context)
- **Test datasets / category hints:** `TestDatasetHelper.*`

## REST endpoints

Base path: `/ws/rest/v1/chartsearchai`. Every endpoint gates on a privilege up front.

| Method | Path | Privilege | Purpose |
|---|---|---|---|
| POST | `/search` | AI Query Patient Data | Blocking answer `{patient, question}` → answer + citations |
| POST | `/search/stream` | AI Query Patient Data | Same, as Server-Sent Events. Event types: `preliminary`, `thinking`, `token`, `references`, `grounded`, `done`, `error` |
| POST | `/warmup` | AI Query Patient Data | Fire-and-forget per-patient KV prewarm on chart open (202) |
| **POST** | **`/prewarm`** | **Manage AI Prewarm** | **Bulk KV-prewarm bootstrap (202 + status)** |
| **GET** | **`/prewarmstatus`** | **Manage AI Prewarm** | **Bulk-prewarm progress/status** |
| GET | `/auditlog` | View AI Audit Logs | Query the AI audit log |
| POST | `/feedback` | AI Query Patient Data | Submit thumbs-up/down on an answer |

### KV warmup & the prewarm bootstrap

Cold full-chart prefill is the dominant first-query latency cost (~10–20s even on GPU). The local engine persists each patient's prefilled KV to disk (`<appdata>/chartsearchai/kvcache`, one `.bin` per chart hash) so subsequent queries restore it (~ms) instead of re-prefilling.

- **`/warmup`** (reactive) — the frontend fires this on chart open so the clinician's first query is warm. LRU-capped by `chartsearchai.llm.kvCacheMaxEntries` (default 16).
- **`/prewarm`** (bulk bootstrap, **opt-in, default off**) — a resumable background sweep that pre-fills and **pins** every patient's KV so a first query on a *never-opened* patient is also warm. Pinned entries (`<name>.bin.pin` sidecar) are **exempt from the LRU cap** — durable for hosts with disk for the whole population.

**`POST /prewarm`** body (all optional): `{"scope": "all", "action": "start" | "restart" | "stop"}` — returns **202** with the current status. `start` resumes from the persisted cursor; `restart` sweeps from the beginning; `stop` cancels. Only `scope: "all"` is implemented (others → 400). A resumable cursor is persisted to `<appdata>/chartsearchai/prewarm-progress.json`, so a crash/restart continues where it stopped.

**`GET /prewarmstatus`** → `{status, running, scope, total, done, failed, cursorPatientId, currentPatientId, pinnedOnDisk, startedAt, updatedAt}` where `status` ∈ `IDLE | RUNNING | COMPLETED | STOPPED`.

Example:
```bash
A=$(printf 'admin:Admin123' | base64); B=http://localhost:8081/openmrs/ws/rest/v1
curl -s -H "Authorization: Basic $A" -H 'Content-Type: application/json' \
     -X POST "$B/chartsearchai/prewarm" -d '{"action":"start"}'
curl -s -H "Authorization: Basic $A" "$B/chartsearchai/prewarmstatus"
```

**Relevant global properties** (all default off/unbounded):
- `chartsearchai.prewarm.enabled` — master switch for the endpoints + sweep.
- `chartsearchai.prewarm.autostart` — resume the sweep on module startup.
- `chartsearchai.prewarm.throttleMs` — pause between patients (default 500) so the single inference slot isn't monopolised.
- `chartsearchai.llm.kvCache.maxPinnedEntries` — cap the pinned corpus (`0` = unlimited).

> Note: a pinned entry becomes stale when that patient's chart changes; the next query re-prefills and re-saves it as an ordinary (unpinned) entry, so the pinned corpus erodes over time — re-run the sweep to refresh it. Only meaningful with `engine=local`.
