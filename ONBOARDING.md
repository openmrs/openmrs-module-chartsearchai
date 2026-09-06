# Chart Search AI — Onboarding

An OpenMRS module that lets clinicians ask natural-language questions about a patient's chart and get answers with source citations. A local (or remote) LLM answers over a chart assembled by the **querystore** module, which owns retrieval and embeddings.

## Get oriented fast

- **Read first:** `CLAUDE.md` (project rules — TDD, root-cause-over-patch, and the cross-cutting API-surface rules), `api/src/main/java/org/openmrs/module/chartsearchai/reference/CLAUDE.md` (the drug-reference and drug-safety API-surface rules, which are most of them), `README.md` (setup + platform notes), `docs/adr.md` (decision log).
- **Retrieval lives in querystore, not here.** chartsearchai has no in-process embedding/Lucene/scoring pipeline. Retrieval and citation grounding both use querystore's e5-base-v2 model. Retrieval changes and retrieval-quality eval belong in `openmrs-module-querystore`.
- **Two engines:** `chartsearchai.llm.engine` = `local` (bundled `llama-server` subprocess, default — data stays on the host) or `remote` (OpenAI-compatible API; see [ADR Decision 17](docs/adr.md#decision-17-remote-llm-backend-support)).
- **The prompt carries a slice, not the whole chart.** `chartsearchai.chartMode` defaults to `queryScoped`: the question's typed scope complete, plus the querystore similarity top-K, plus demographics. `fullChart` is the other mode and it changes which machinery is live — see [ADR Decision 28](docs/adr.md#decision-28-query-scoped-slice-charts-chartmodequeryscoped).

## Build & test

```bash
mvn install                       # full build (api + omod), produces omod/target/chartsearchai-*.omod
mvn -pl api test                  # api unit tests
mvn -pl api test -Dtest=ClassName # one test class
```

Use `mvn install` when you need the omod tests too. A root `mvn test` **cannot** run them: omod's `unpack-dependencies` execution binds to `generate-resources` and unpacks the api *jar*, which a `test`-phase reactor never produces, so the build fails there with "Artifact has not been packaged yet" (MDEP-98) after the api tests pass. Always build from the repo root with a full reactor — never `mvn -pl omod`, which resolves the api artifact from `~/.m2` and can silently test a stale one.

Tests must call the **real production pipeline** with real datasets — no mocks/reimplementations of pipeline logic (see `CLAUDE.md`). Follow TDD: write the failing test first.

## Run it locally

The repo ships no standalone — download the [O3 Standalone with Chart Search AI](https://nightly.link/openmrs/openmrs-module-chartsearchai/workflows/build-standalone/main/openmrs-standalone-chartsearchai.zip) built by `build-standalone.yml`, or use `docker compose up --build`. See README's [Standalone platform notes](README.md#standalone-platform-notes) for the per-platform requirements (Java 21+, and *which* JDK on Windows).

Extract it, then `java -jar openmrs-standalone.jar` (login `admin` / `Admin123`; the port is in the launcher's output). To redeploy your own build, drop `omod/target/chartsearchai-*.omod` into the standalone's `appdata/modules/` and restart. Stopping it needs all three processes — `pkill -9 -f openmrs-standalone.jar; pkill -9 -f mariadbd; pkill -9 -f llama-server` — because the embedded MariaDB otherwise keeps the DB locked against the next boot.

## API-surface rules (do not bypass)

Two files hold the authoritative list of methods that are the only correct entry point for their operation. `CLAUDE.md` carries the cross-cutting ones — prefixed text, cosine similarity, citations and grounding, chart assembly, the test datasets and category hints. `api/src/main/java/org/openmrs/module/chartsearchai/reference/CLAUDE.md` carries the drug-reference and drug-safety ones, which are the bulk of them: diacritic folding, the three drug-name matching shapes, substance identity, and how a partner is named. Read them there rather than from a copy here; a second list is a second thing to fall out of date.

## REST endpoints

Base path: `/ws/rest/v1/chartsearchai`. Every endpoint gates on a privilege up front.

| Method | Path | Privilege | Purpose |
|---|---|---|---|
| POST | `/search` | AI Query Patient Data | Blocking answer `{patient, question}` → answer + citations |
| POST | `/search/stream` | AI Query Patient Data | Same, as Server-Sent Events — see the framing rules below |
| POST | `/warmup` | AI Query Patient Data | Fire-and-forget per-patient KV prewarm on chart open (202) |
| **POST** | **`/prewarm`** | **Manage AI Prewarm** | **Bulk KV-prewarm bootstrap (202 + status)** |
| **GET** | **`/prewarmstatus`** | **Manage AI Prewarm** | **Bulk-prewarm progress/status** |
| GET | `/auditlog` | View AI Audit Logs | Query the AI audit log |
| POST | `/feedback` | AI Query Patient Data | Submit thumbs-up/down on an answer |
| GET | `/drugreferencestatus` | Get Global Properties | Which drug-reference dataset is actually loaded |

**Writing a `/search/stream` client.** Events arrive in this order: `preliminary`, `thinking`, `token`, `references`, `done`, `grounded`, `error`. Two framing rules, and a client that breaks either does so in a way the server cannot detect. `grounded` is a *trailing* event after `done` (async grounding only), so keep consuming the stream past `done` rather than treating it as terminal. And between events the stream carries SSE *comments* — lines opening with `:`, written so a reverse proxy never sees a read-idle connection — which must be skipped rather than read as a frame, as the SSE spec requires, by whatever parser you use: `EventSource` would do it for you but cannot be used here, since it issues a GET and sends no body and so cannot reach this POST endpoint. README's [Streaming search (SSE)](README.md#streaming-search-sse) section has each event's payload and the proxy read timeouts behind the comments.

### KV warmup & the prewarm bootstrap

> **All of this is dormant on a default install.** Warmup, the prewarm sweep, per-patient KV persistence and the progressive-reasoning preview all disengage unless `chartsearchai.chartMode` is set to `fullChart`; the default, `queryScoped`, builds a per-question slice with no reusable chart prefix to prime. The gate is `LlmInferenceService.shouldRunWarmup`. Read this section as the `fullChart` contract.

In `fullChart` mode the cold whole-chart prefill dominates first-query latency, badly so on a GPU-less host. The local engine persists each patient's prefilled KV to disk (`<appdata>/chartsearchai/kvcache`, one `.bin` per chart hash) so subsequent queries restore it instead of re-prefilling.

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
- `chartsearchai.prewarm.refreshOnEdit` — when on, a chart edit to an *already-pinned* patient schedules a debounced single-patient re-pin so the pin tracks the new chart instead of eroding. Independent of `prewarm.enabled`; only refreshes existing pins, never grows the corpus. **Trade-off:** each re-pin is a full prefill on the single inference slot and there is **no inter-patient throttle** (unlike the bulk sweep), so on busy multi-user CPU hosts prefer a periodic manual re-sweep instead.
- `chartsearchai.prewarm.refreshDebounceMs` — quiet-period (default 5000) before a `refreshOnEdit` re-pin fires, collapsing a burst of writes to one patient into a single re-pin.

> Note: a pinned entry becomes stale when that patient's chart changes; the next query re-prefills and re-saves it as an ordinary (unpinned) entry, so the pinned corpus erodes over time — re-run the sweep (or enable `prewarm.refreshOnEdit`) to refresh it. Only meaningful with `engine=local`.

### Which drug-reference dataset is loaded

**`GET /drugreferencestatus`** → `{enabled, loaded, inert, entryCount, sourceFormat, configuredSourceFormat, configuredDataFilePath, origin, findings, arms, crossReactivity}`.

The drug-reference load is **lazy and cached for the life of the module**, so the log cannot answer "which dataset is in force?" — the most recent `Loaded N …` line may belong to a load performed before the global properties were last edited, or to a process a failed restart left running. This endpoint reports the load that populated the cache, performing it if it has not happened yet, so its answer is current by construction. Use it — not a log grep — after changing `sourceFormat` or `dataFilePath`.

- `arms` says which safety arms the loaded ENTRIES dataset can actually serve — `doseCeilings`, `handAuthoredRules`, `atcCodes`, `interactions`, `conditionRules`, each with a `coverage` of `published` / `absent` / `unloaded` and a count beside it. `conditionRules` is `handAuthoredRules`' `condition` leg alone, reported in its own right because the union says nothing about conditions on an allergy-only dataset; it is also the one arm published on the `/search` response, as `conditionRuleCoverage` ([#378](https://github.com/openmrs/openmrs-module-chartsearchai/issues/378)). `inert` below is the whole-dataset verdict; this is the same question per arm, and the two are independent: the shipped DDInter default is not inert and still reports `doseCeilings: absent`, because it publishes interaction rules and class codes but no dosing. `absent` is about what that dataset publishes — the class legs can still raise chips from ATC subgroups (reported here as `atcCodes`) and from the curated cross-reactivity groups, which are a second file with a load and a global property of their own, described in the `crossReactivity` section below rather than in this map. Read `coverage`, not the count — the count is `0` for both `absent` and `unloaded`, and only `coverage` separates "we looked and there is none" from "nobody looked". `interactions` counts the rows the dataset publishes rather than the ones the `minInteractionSeverity` floor admits; see the README for why that one arm is counted that way. The same verdicts also go to the log when the load happens (`Drug-reference safety arms over the N entr(ies) read from …`), from the same rendering — but at INFO, and core's shipped `log4j2.xml` puts `org.openmrs` at `WARN`, so an unmodified install prints nothing: raise `org.openmrs` (or `org.openmrs.module.chartsearchai`) to INFO to see it, as with the `Loaded N …` line beside it. Until then this endpoint is the only channel that answers.
- `inert: true` means a source **was** selected and produced **zero** entries: drug-safety checking is off while the module looks healthy. Usually a `sourceFormat`/`dataFilePath` mismatch — each format parses only its own shape and returns nothing, without failing, for another's. Also logged at WARN when it happens.
- `enabled: false` + `loaded: false` is the default, legitimate, silent state: the feature is off, so nothing is loaded and nothing is warned about. Reading the status does not trigger a load in that case.
- `crossReactivity` is the SECOND dataset — the curated cross-reactivity groups, loaded from `crossReactivityGroupsFilePath` alongside whatever `sourceFormat` is in force, so nothing above describes them ([#266](https://github.com/openmrs/openmrs-module-chartsearchai/issues/266)). It asks the same questions in its own keys: `loaded` (also `false` when the feature is off, and reading this does not trigger the parse then), `groupCount`, `configuredFilePath`, `origin` and `findings`. Until #266 those findings reached only the log, so a groups file you named and the module could not read was invisible here. There is no `inert` for it: carrying no groups is a legitimate configuration, since the class legs still reason from ATC subgroups.
- `findings` reports what the loader found wrong with the dataset it read — one list per dataset, in that dataset's own section. **Log level tells you whose problem it is** ([ADR Decision 36](docs/adr.md#decision-36-the-shipped-default-is-the-whole-ddinter-knowledge-base)): a data finding about your own file (`origin: appdata:`) is WARN, the same finding about the dataset the module ships (`origin: classpath:`) is INFO, and a finding naming your *configuration* is WARN either way. The status carries all of them identically, so read it rather than inferring from the level — the bundled DDInter knowledge base reports 19 known rows whose fix is upstream, and that is expected rather than a problem with your install.
- `origin` is what was **read**, marked with the space it came from — `appdata:<path>` for an operator file, `classpath:/chartsearchai/…` for the bundled dataset; `configuredDataFilePath` is what was **asked for**. Your file loaded exactly when `origin` is `appdata:` + that path. They differ when a configured file could not be read: for `json` and `ddinter` the bundled dataset is used, which yields a plausible non-zero count, so the count alone cannot tell you your file loaded; for `atc`, which bundles none, `origin` is `none` and nothing is in force. Either way a `configured-data-file-not-read` finding says so, on `atc` too since [#266](https://github.com/openmrs/openmrs-module-chartsearchai/issues/266). It is relative on purpose: any authenticated user can read this (the `Authenticated` role holds `Get Global Properties`), while core keeps the absolute application-data path behind `View Administration Functions`.

```bash
A=$(printf 'admin:Admin123' | base64); B=http://localhost:8081/openmrs/ws/rest/v1
curl -s -H "Authorization: Basic $A" "$B/chartsearchai/drugreferencestatus"
```
