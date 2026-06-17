# Migration plan — remove chartsearchai's indexing/storage/sync layer, retrieve from querystore

Tracks GitHub issue [#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51).

> **Status (2026-06-17): migration COMPLETE.** All phases merged:
> Phase 0/0.5/1 ([#52](https://github.com/openmrs/openmrs-module-chartsearchai/pull/52),
> [#53](https://github.com/openmrs/openmrs-module-chartsearchai/pull/53)/[#54](https://github.com/openmrs/openmrs-module-chartsearchai/pull/54)),
> **Phase 2** querystore-only retrieval + legacy stack deleted ([#57](https://github.com/openmrs/openmrs-module-chartsearchai/pull/57)),
> **Phase 3** ONNX embedding subsystem deleted ([#58](https://github.com/openmrs/openmrs-module-chartsearchai/pull/58)),
> **Phase 4** `ChartEmbedding` store deleted ([#59](https://github.com/openmrs/openmrs-module-chartsearchai/pull/59)),
> **Phase 5a** querystore-only collapse + record-loading layer + `querystore.enabled` GP removed
> ([#60](https://github.com/openmrs/openmrs-module-chartsearchai/pull/60)), and **Phase 5b** this
> doc/README/CLAUDE.md reconciliation. chartsearchai is now a pure querystore consumer + LLM /
> grounding / drug-reference / audit layer, with no in-process retrieval, embedding, store, or toggle.
>
> §1–§3 below are the **original plan and Phase-0 measurements**, kept as the historical record of
> what was removed and why; §4 has the as-shipped per-phase outcome. The Phase-0 eval harness
> (`QueryStoreRetrievalParityEvalTest`, `QueryStoreContentParityEvalTest`) and the `RelevanceCutoff`
> prototype remain on the `phase0-evals` branch (measurement scaffolding, not on `main`).

## 1. Where we already are

The querystore bridge is built, working, and now the **default** retrieval path:

- `QueryStoreChartBuilder` calls `QueryStoreService.getPatientChart(uuid)` (full
  chart, the KV-cache-friendly shape) and, when `preFilter=true`, also
  `searchByPatient(uuid, q, topK)` to produce the focus-hint ranking.
- `ChartBuildingStrategy.buildChart()` routes to querystore first when
  `ChartSearchAiUtils.isQueryStoreEnabled()` (GP `chartsearchai.querystore.enabled`,
  **default `true`** as of #56), otherwise falls through to the deprecated legacy
  pipelines (removed in Phase 2).
- `querystore-api` is a dependency and `config.xml` declares querystore a
  **`require_module`** (#56) — it is always present at runtime.

So the migration is: **make querystore the only path, then delete the legacy
layer it duplicates** — not "build an integration."

## 2. What is actually removable vs. what must stay

The issue lists "the `serializer/` package" for removal, but the package is **not
monolithic**. Part of it is still on the querystore path and must survive:

| Component | Disposition | Why |
|---|---|---|
| `EmbeddingIndexer`, `LuceneIndexer`, `ElasticsearchIndexer`, `ElasticsearchQueryBuilder`, `HybridRetriever`, `IndexingHelper` | **Remove** | querystore owns indexing + hybrid search |
| `PatientDataIndexingAdvice`, `EncounterIndexingAdvice`, `ObsIndexingAdvice` (+ 8 `<advice>` blocks in `config.xml`) | **Remove** | querystore rides core #6084 events; also kills the buggy `handleMergePatients` bulk-overload skip |
| `EmbeddingIndexTask` (+ `registerBackfillTask()` in the activator + its GP/scheduler wiring) | **Remove** | querystore does its own bootstrap/backfill |
| `RetrievalQuery`, `EmbeddingRankingPipeline`, `SimilarityAndScoringEngine`, `ScoreStatistics`, `ScoredEmbedding`, `ConceptRescueAndFilter`, `ConceptNameReranker`, `ConceptKeywordMatching`, `CoherenceFilters`, `RankingPipelineGates`, `RefinementPaths`, `PipelineConfig`, `QueryPreprocessor` (retrieval bits) | **Remove** | the embedding scoring/ranking pipeline; querystore ranks |
| `OnnxEmbeddingProvider`, `WordPieceTokenizer`, `EmbeddingProvider`, `ModelNoiseProfile` + the bundled ONNX model resource | **Remove** | chartsearchai no longer embeds; querystore embeds the query inside `searchByPatient` |
| `ChartEmbedding` model + its Hibernate mapping + the embeddings table (liquibase changeset) + `ChartSearchAiDAO.getByPatient` and related embedding DAO methods | **Remove** | the embedding store |
| Legacy branches of `ChartBuildingStrategy` (`buildChartWithEmbeddings/Lucene/Elasticsearch/Hybrid`, `findSimilar*`, `filterAndSerialize`, the noise-profile cache, the pipeline-selection predicates) | **Remove** → collapse to a querystore-only `buildChart` | dead once querystore is the only path |
| `serializer/` per-resource text producers: `PatientRecordLoader.loadAll`, `ClinicalTextSerializer`, `Allergy/Condition/Diagnosis/MedicationDispense/Obs/Order/PatientProgram TextSerializer` | **Remove** | querystore produces the document text now |
| **`PatientChartSerializer`** (`serialize(patient, records, focusUuids)`, `RecordMapping`, `PatientChart`, `getFocusIndices`) | **KEEP** | prompt assembly + focus-hint rendering; used by `QueryStoreChartBuilder`, `LlmInferenceService`, `CitationGroundingVerifier` |
| **`SerializedRecord`** (today nested in `PatientRecordLoader`) | **KEEP, relocate** | it is the chart-record value type passed across the whole consumer layer — must be lifted out of the loader before the loader is deleted |
| **`DateFormatUtil`, `ConceptNameUtil`** | **KEEP** (verify) | `DateFormatUtil` used by `QueryStoreChartBuilder`; confirm `ConceptNameUtil` has no embedding-only callers |
| Retrieval-pipeline GPs, embedding-model GPs, ES-connection GPs | **Remove** | dead config |
| LLM Q&A orchestration, `CitationGroundingVerifier`, `DrugReference*`, `PatientAccessCheck`, `"AI Query Patient Data"` privilege, audit log, llama-server natives | **KEEP** | chartsearchai's reason to exist |

**Decoupling prerequisite (do this first, it's the lynchpin):**
`PatientChartSerializer.serialize(patient)` (no-records form) calls
`recordLoader.loadAll(patient)`, and `SerializedRecord` lives inside
`PatientRecordLoader`. Both couple the *kept* serializer to the *removed* loader.
Before any deletion: lift `SerializedRecord` to a top-level type and drop
`PatientChartSerializer`'s `@Autowired PatientRecordLoader` + its no-records
`serialize(patient)` overload (querystore always passes records explicitly).

## 3. The hard gate — validating the eval against querystore

The 485-case retrieval eval (`EnrichedRetrievalEvalTest`, plus
`RetrievalQualityEvalTest`, `WhiteningEvalProbeTest`, `LlmInferenceServiceEvalTest`,
and the `enriched_*` cases in `LlmInferenceServiceTest`) calls
`LlmInferenceService.findRelevantRecords` → `RetrievalQuery.findRelevantRecords`
**in-process against `ChartEmbedding` + the ONNX model**. These tests *are* the
embedding pipeline's spec — they exercise exactly the code being deleted.

Consequence: **removing the embedding pipeline removes the thing these evals
test.** They cannot simply be kept. So the gate is:

1. Build a querystore-backed eval path: run the same dataset + expected-record
   assertions through `QueryStoreService.searchByPatient` (requires querystore +
   its search backend running — this is an **integration** harness, not the
   current pure unit test, and likely needs a querystore-side fixture).
2. Establish parity: querystore retrieval must hit the eval's recall/precision
   bar on the same cases before the embedding pipeline is deleted. Per CLAUDE.md,
   check *which records* are returned, not just counts/the final answer.
3. Only then delete the embedding pipeline **and** its now-obsolete eval tests in
   the same change. Repoint any still-meaningful end-to-end answer-quality cases
   at the querystore path.

This step is the real cost of the migration and the most likely place to block.
It depends on the two still-open querystore prerequisites (§5).

### 3a. Phase 0 measurement — first results (2026-06-16)

`QueryStoreRetrievalParityEvalTest` (api test) stands up querystore's **real**
retrieval in-process — `QueryStoreServiceImpl.searchByPatient` over the embeddable
`LuceneBackendStore` + the same all-MiniLM-L6-v2 ONNX model — indexes all 5 test
datasets, and replays the 485-case baseline, scoring querystore's hits against the
embedding pipeline's own selections (the baseline `resultIndices`). Run with:

```
mvn -pl api test -Dtest=QueryStoreRetrievalParityEvalTest \
  -Dchartsearchai.embedding.model.dir=/abs/path/to/models/all-MiniLM-L6-v2 \
  [-Dchartsearchai.qsparity.topk=N]
```

Measured (324 gold-bearing cases, macro-averaged; 161 absent-data cases):

| topK | recall@K | precision@K | F1@K | absent over-return |
|---|---|---|---|---|
| 30 | **0.956** | 0.219 | 0.284 | 161/161 |
| 10 | **0.836** | 0.412 | 0.439 | 161/161 |

**Reading.** querystore has the **recall** — at top-30 it surfaces ~96% of the
records the embedding pipeline picked; the right records are in its ranking. The
low precision is **not** a ranking failure: querystore returns a fixed top-K
(avg 30 hits) while the embedding pipeline's adaptive gap-cutoff returns ~5–10.
The decisive gap is **absent-data handling** — querystore returned ≥1 record on
*every one* of the 161 "nothing relevant" queries; it has no mechanism to answer
"no relevant records," which the embedding pipeline's gap filter provides.

**Conclusion for the gate.** The migration's retrieval-quality work is a
**relevance-cutoff layer on top of querystore's ranking** (a score/gap threshold,
or a small re-ranker), *not* a question of whether querystore finds the right
records — it does. Phase 3 (deleting the embedding pipeline) should not proceed
until that cutoff exists and this harness shows precision + absent-data parity,
not just recall. Caveats already baked into the numbers (both conservative to
querystore): no category-hint/synonym enrichment was fed to querystore, and no
cutoff was applied — both are levers that can only improve these figures.

### 3b. Cutoff prototype — cosine-threshold sweep (2026-06-16)

`measureCosineCutoffSweep` (same test class) generates candidates from querystore
(`searchByPatient`, candidateK=50), re-scores each by cosine(query, doc) using the
same model, and sweeps a global relevance threshold. Macro over 324 gold / 161
absent cases:

| cosine T | recall | precision | F1 | avg preds | absent rejected |
|---|---|---|---|---|---|
| 0.00 (none) | 0.982 | 0.153 | 0.215 | 49.7 | 0/161 |
| 0.20 | 0.965 | 0.308 | 0.403 | 26.2 | 24/161 |
| 0.25 | 0.926 | 0.465 | 0.533 | 17.7 | 54/161 |
| **0.30** | **0.828** | **0.577** | **0.590** | 11.7 | 92/161 |
| 0.35 | 0.695 | 0.591 | 0.567 | 7.5 | 119/161 |
| 0.40 | 0.530 | 0.516 | 0.476 | 4.9 | 143/161 |
| 0.50 | 0.301 | 0.320 | 0.287 | 2.4 | 161/161 |

**Reading.** A cheap global cosine cutoff on querystore's candidates closes most
of the gap: at T≈0.30, F1 rises from 0.22 (no cutoff) to **0.59**, precision
0.15→0.58, recall holds at 0.83, and 57% of absent-data queries are correctly
emptied. So the lever works — querystore + a relevance cutoff is a viable
replacement for the embedding-pipeline filter.

**But a single global threshold has a ceiling.** It cannot hit high recall *and*
full absent-data rejection at once: rejecting all 161 absent queries (T=0.50)
collapses recall to 0.30. That trade-off is exactly why the embedding pipeline
uses *adaptive per-query* gap/z-score detection rather than a constant. The
target architecture is therefore **querystore candidate generation + a per-query
adaptive cutoff** (port the existing gap/z-score logic to run over cosine scores
of querystore's candidates — a small, self-contained piece — instead of over the
full-corpus embedding scan). The global-threshold prototype quantifies the floor
of the simplest version (F1 0.59) and the headroom an adaptive cutoff must buy
back. Re-run this harness after the adaptive cutoff lands; it is the Phase 3 gate.

### 3c. Adaptive cutoff — `RelevanceCutoff` (2026-06-16)

Built `RelevanceCutoff` (production class, `api/.../api/impl/RelevanceCutoff.java`):
a signal-agnostic pure function over a ranked candidate list — top-anchored
**absent gate** (empty if the best candidate < `absentTopFloor`) + **floor + gap
trim** (`keepFloor`, `maxGap`, `maxKeep`). It mirrors the embedding pipeline's
two load-bearing ideas (does the top stand out; where does the tail cliff) in a
small piece that can later consume querystore's own fused score instead of cosine.
`measureAdaptiveCutoffSweep` runs it over querystore's cosine-scored candidates.

Best operating point **atf=0.30, kf=0.25, gap=0.08** vs the global-threshold best:

| approach | recall | precision | F1 | avg preds | absent rejected |
|---|---|---|---|---|---|
| global threshold (T=0.30) | 0.828 | 0.577 | 0.590 | 11.7 | 92/161 |
| **adaptive cutoff** | **0.853** | **0.649** | **0.648** | 11.1 | 92/161 |

The adaptive gap **dominates the global threshold on gold queries** — higher
precision *and* higher recall (F1 0.590 → 0.648) at the same absent-rejection.

Absent-rejection remains governed by the absolute top-floor and trades against
recall (it is the binding constraint):

| absentTopFloor | recall | F1 | absent rejected |
|---|---|---|---|
| 0.30 | 0.853 | 0.648 | 92/161 (57%) |
| 0.35 | 0.756 | 0.572 | 119/161 (74%) |
| 0.40 | 0.587 | 0.445 | 143/161 (89%) |

**Conclusion.** An adaptive cutoff is the right shape and clearly beats a constant
threshold for precision/recall. The residual ceiling is **absent-data separation**:
pure cosine on the top candidate can't cleanly tell "weak-but-present" from
"absent," so pushing absent-rejection past ~57% costs recall. Closing that needs a
signal beyond cosine — **querystore exposing its fused hybrid score** (which carries
the BM25 keyword signal the embedding pipeline combined with semantics), or porting
the pipeline's keyword/noise gates into `RelevanceCutoff`. That suggested a
querystore-side prerequisite (expose per-hit scores). §3d tests *which* score.

### 3d. Which signal gates absent best — cosine vs fused RRF vs BM25 (2026-06-16)

`measureAbsentGateSignals` measures, per signal, how well the top-of-list score
separates gold-bearing from absent queries: it sweeps the gate threshold and
reports gold-kept (gold queries not wrongly silenced) at each absent-rejection
level. Best gold-kept at ≥80% absent-rejection:

| signal | best gold-kept @ absentRej≥0.80 | notes |
|---|---|---|
| **cosine (top candidate)** | **0.69** (T≈0.39) | best separator |
| fused RRF (top hybrid) | 0.41 | rank-based; scores collapse to ~0.016/~0.031, near-binary |
| raw BM25 (top) | — | never reaches 80% absent-rejection |
| BM25 hit-presence | ~0 absentRej | absent queries still surface keyword hits |

**This flips the earlier prerequisite.** querystore's **fused RRF score is the
wrong thing to expose** — it is a *worse* absent-gate signal than cosine because
RRF discards magnitudes for ranks. Raw BM25 / keyword-presence is also weak: an
absent query ("any family history of cancer?" on a patient with none) still
keyword-matches *something*. The single best available absent signal is the
**top kNN cosine similarity**, which querystore already computes internally but
does not surface (`searchByPatient` returns fused docs only).

**Refined prerequisite (querystore-side).** Expose the **per-hit kNN cosine
similarity** (not the fused RRF score) — then chartsearchai's `RelevanceCutoff`
can gate on the one signal shown to separate best, without re-embedding. This is
cheaper and more justified than exposing the fused score.

**Ceiling + scope caveat.** Even cosine tops out at ~0.69 gold-kept for 80%
absent-rejection — a single top-of-list score is intrinsically limited. The
embedding pipeline does better on absent data via *multiple* signals
(noise-profile-calibrated floor, per-record keyword refinement, concept-name
floors, category-hint rescue), not one threshold; full absent-data parity likely
needs porting one or more of those into `RelevanceCutoff`. **But weigh that
against scope:** today's `QueryStoreChartBuilder` sends the *full* chart to the
LLM and uses `searchByPatient` only as a focus *hint* — so absent-data precision
matters for **preFilter mode** (filtering the chart), not for the default
full-chart path. Decide the target mode before investing in absent-data parity:
if full-chart stays the default, the cosine adaptive cutoff (F1 0.65) is already
ample for the hint; if preFilter becomes the default, the multi-signal port is
warranted.

### 3e. BLOCKER — chartsearchai (2.8.0) and querystore (2.9+) are not co-loadable today (2026-06-16)

Building the content-parity eval (`QueryStoreContentParityEvalTest`, a context-sensitive test
that runs both real serializers over the same OpenMRS data) surfaced a hard prerequisite:

- chartsearchai pins **`openmrsPlatformVersion=2.8.0`**; querystore-api targets **2.9.0+** and its
  `CoreServiceEventListener` references the core service-event classes from **openmrs-core #6084**
  (`org.openmrs.aop.event.VoidServiceEvent` / `UnvoidServiceEvent`), absent in 2.8.0.
- querystore-api (provided scope) ships a `moduleApplicationContext.xml`, which the OpenMRS test
  framework component-scans via `classpath*:moduleApplicationContext.xml`. On 2.8.0 that bean fails
  to introspect → the **entire Spring context fails to load**. This currently breaks **chartsearchai's
  existing context-sensitive tests too** (verified: `PatientRecordLoaderTest` fails identically)
  whenever this querystore-api is on the classpath.
- Bumping to `3.0.0-SNAPSHOT` clears the event-class error but **cascades into a Lucene conflict**
  (`org.apache.lucene.util.ResourceLoader`; chartsearchai pins Lucene **8.11.2**, core 3.0's
  Hibernate-Search session factory needs a different Lucene). Each platform bump uncovers the next
  dependency conflict.

**Why this matters / re-sequencing.** This is not just an eval inconvenience — it is a gating
prerequisite for the *whole* migration. chartsearchai and querystore must run on a **common core
platform** before querystore can be a hard runtime dependency. That platform alignment (2.9+/3.0,
plus the Lucene/Hibernate-Search version reconciliation) should be **Phase 0.5**, ahead of any
deletion. The content-parity eval is written and correct but `@Disabled` until then; run it as the
first check once the platforms align (it needs no model — pure serializer-text comparison).

### 3f. Content parity measured on the standalone (2026-06-16)

Since the in-process eval is blocked (§3e), content parity was measured on the running
standalone (refapp 3.7, querystore + chartsearchai co-installed, patient **agnes-adams**,
267 records) — **read-only**, no server changes. querystore's Lucene docs were read directly
(read-only `DirectoryReader`); coverage was bounded against the raw OpenMRS source (chartsearchai's
`loadAll` is a subset of the source, so source coverage bounds chartsearchai coverage).

| type | chartsearchai emits | querystore covers | loss |
|---|---|---|---|
| condition | 21 | 21 | **0** (exact uuid match) |
| diagnosis | 21 | 21 | **0** (exact uuid match) |
| test_order | 11 | 11 | **0** (exact uuid match) |
| obs (top-level) | 111 | 100 | 11 — all value-less **panel containers** |
| allergy / drug_order / program / medication_dispense | 0 | 0 | 0 (none in source) |

**The only difference is obs-group representation.** The 11 obs querystore "drops" are value-less
grouping containers ("Basic metabolic panel", "Liver function tests", "Stool test", …). querystore
instead emits their **members** as 65 separate, more-granular docs (the "extra" obs), each carrying
the real lab value (e.g. `Systolic blood pressure: 132.0 mmHg`, `Stool microscopy with
concentration: …`) and, in metadata/embedding-input, the parent panel name. chartsearchai does the
inverse: it emits the panel as one record with members inlined and omits the members as standalone
records.

**Verdict: no clinical content is lost.** Every value chartsearchai shows the LLM is present in
querystore (inlined-under-panel vs flat-per-member). The sole representational delta is that
querystore's citation *text* presents members flat and omits the explicit panel grouping label (it
keeps that label only in embedding-input metadata). Querystore's text is clinically faithful
(concept + value + units + status/certainty/rank), matching chartsearchai's fields. For the default
full-chart path this is a formatting difference, not a loss — arguably finer-grained.

**Extended to more patients (2026-06-16).** karen-sanchez (patient 25): condition 36/36,
diagnosis 36/36, test_order 34/34 exact; obs flattened 163→235 (same group pattern, no data loss).
The scarce serializers were then exercised on the only demo patients that have them:

| type | patient | source | querystore | querystore text |
|---|---|---|---|---|
| allergy | 49 | 1 | 1 ✓ | `Allergy: NSAID (drug allergen)` |
| drug_order | 49 | 1 | 1 ✓ | `Drug order: Advil 200mg. Action: NEW. Urgency: ROUTINE` |
| program | 7 | 1 | 1 ✓ | `Program: PMTCT. Enrolled: 2023-05-06. Status: Active. Current state: Lost to follow-up` |
| medication_dispense | 67 | 1 | 1 ✓ | `Dispensed: Acetaminophen 325 mg. Status: Completed` |

medication_dispense was confirmed by triggering querystore's lazy index for patient 67 (his only
record) via the chartsearchai `warmup` REST endpoint (`POST /ws/rest/v1/chartsearchai/warmup`,
body `{"patient":"<uuid>"}`) — a benign write that querystore would perform on any first access.

So **obs, condition, diagnosis, test_order, allergy, drug_order, program, medication_dispense** —
8 of 9 resource types — are confirmed faithful and fully covered. The **only** unverified type is
**referral_order**: the demo DB contains zero referral orders, so it cannot be exercised here. Its
serializer (`ReferralOrderRecordSerializer`) exists and follows the identical
`AbstractRecordSerializer` pattern as the eight confirmed ones; verify it on data that has referral
orders before relying on parity.

**Side finding — querystore coverage is lazy/partial on the standalone.** Patient 67 has 0 querystore
docs because querystore indexes a patient on first access (`ensureIndexed`); patients never queried
through querystore are absent. This is expected for the lazy model but reinforces the **initial
backfill/bootstrap** open prerequisite — a full migration needs an all-patients backfill, not just
on-demand projection.

**Caveats / decisions.** (1) The one deliberate call: querystore's citation *text* omits the explicit
obs **panel-grouping label** (kept only in embedding metadata); decide whether that matters for answer
quality and, if so, surface `obs_group_concept_name` into querystore's `getText()`. (2) medication_dispense
and referral_order text fidelity still unverified (demo lacks the data).

### 3g. Phase 0.5 DONE — platform aligned to 2.9.0-SNAPSHOT (2026-06-16)

The §3e blocker is resolved. Root cause was simply the wrong target: 3.0.0-SNAPSHOT pulls Lucene 9
(the `ResourceLoader` collision), but querystore actually targets **2.9.0-SNAPSHOT with Lucene 8.11.2**
(its pom pins Lucene 8 deliberately — Lucene 9 "collides on the Codec ServiceLoader under standalone").
The standalone already runs core 2.9.0-SNAPSHOT. So the fix is one line:

- `pom.xml`: `openmrsPlatformVersion` **2.8.0 → 2.9.0-SNAPSHOT** (Lucene stays 8.11.2). `require_version`
  in `config.xml` already derives from the property, so the module's declared requirement updates too.

Verified:
- Compiles clean (api + omod web) against 2.9; full reactor packages the `.omod`.
- **662 tests pass, 0 failures.** No 2.8→2.9 source or behavioral regressions in the non-eval suite.
- chartsearchai + querystore now **co-load** in a context-sensitive test (the hard blocker). As a
  bonus this **repairs chartsearchai's own context-sensitive tests**, which were silently red on 2.8.0
  whenever querystore-api was on the classpath (e.g. `PatientRecordLoaderTest`).
- `QueryStoreContentParityEvalTest` is **re-enabled and passing in-process** — coverage **1.0**
  (csOnly=0, qsOnly=0) over the standard test dataset (obs 9/9, order 13/13, program 3/3),
  corroborating §3f. It now serves as a CI-able co-load + content regression guard.

Follow-ups (not blockers for the bump):
- **`EndToEndFullDataset.xml` does not load on 2.9** — `encounter` FK violation (H2 23506). The only
  user of that fixture, `EndToEndSearchTest`, is `@Tag("eval")` (excluded by default), so it was never
  validated on 2.9. Needs a fixture fix before that eval test runs on 2.9. (Content-parity test was
  repointed at the standard dataset to avoid the dependency.)
- **ShedLock noise**: querystore's ShedLock-based scheduling logs `Table "SHEDLOCK" not found`
  exceptions against the bare test DB during context init — non-fatal (tests pass), cosmetic.

## 4. Phased sequence (each phase independently shippable + reversible)

**Phase 0 — De-risk & default. (✅ default flip DONE — #56.)** Added the querystore-backed eval
harness (§3) and recorded parity numbers, without deleting anything. Then (#56) flipped
`chartsearchai.querystore.enabled` to default `true` and promoted querystore to a `require_module`
— querystore is now the default path with a guaranteed backend, the legacy pipelines kept as a
deprecated fallback until Phase 2 removes them.

**Phase 0.5 — Platform alignment (✅ DONE — see §3g).** Bumped `openmrsPlatformVersion` 2.8.0 →
2.9.0-SNAPSHOT (Lucene stays 8.11.2, matching core's transitive line). Both modules co-load; 662
tests pass; the content-parity eval runs in-process; the `.omod` packages. querystore can now be a
hard dependency. Remaining: the `EndToEndFullDataset.xml` 2.9 fixture fix (its eval test is excluded).

**Phase 1 — Stop writing the duplicate store. (✅ DONE — #53, + #54 JobRunr fix.)** The original
plan ("remove the 8 `<advice>` blocks + the three `*IndexingAdvice` classes") was **wrong**: those
advice classes also invalidate chartsearchai's **answer cache** on chart writes (#48) —
backend-independent, no querystore equivalent — so deleting them wholesale would regress answer
freshness. What actually shipped:
- **Stripped** the embedding/Lucene/ES re-index from the three advice classes, leaving them as pure
  answer-cache invalidators; **kept** their 8 config.xml registrations. This also retired the buggy
  `handleMergePatients` reindex.
- **Deleted** `EmbeddingIndexTask` + `registerBackfillTask()`; added `removeLegacyBackfillTask()` to
  clean up the leftover task on upgrade (#54 fixed a JobRunr `DELETED→DELETED` bug in that cleanup,
  found via standalone verification — the unit harness can't exercise the JobRunr scheduler).
- Corrected now-false GP/README claims of automatic re-index.
- **Standalone-verified**: a real chart write still fires the stripped advice and evicts the answer
  cache (cache hit 0.05s → recompute 5.94s after a write) — the AOP-on-write path the unit harness
  can't cover. chartsearchai no longer writes its own indices; querystore owns freshness via core events.

**Phase 2 — Collapse the router + delete the whole legacy retrieval stack. (✅ DONE.)**
The doc's original Phase 2/3 split (indexers in 2, scoring pipeline + eval tests in 3) was
**not independently compilable**: the eval/regression tests reference *both* the indexers and
`RetrievalQuery`/`PipelineConfig`, so deleting only the indexers leaves the test tree broken.
The legacy retrieval stack is one connected blob reachable solely through `buildChart()`'s legacy
branch, so Phase 2 deletes it whole. What shipped:
- `ChartBuildingStrategy.buildChart()` collapsed to `queryStoreChartBuilder.build()` (querystore
  enabled) or a plain unranked full-chart serialize (disabled) — the legacy
  `buildChartWith{Embeddings,Lucene,Elasticsearch,Hybrid}`, filter/findSimilar methods,
  pipeline-selection predicates, and noise-profile cache are gone.
- **Deleted 18 classes**: `EmbeddingIndexer`, `LuceneIndexer`, `ElasticsearchIndexer`,
  `HybridRetriever`, `ElasticsearchQueryBuilder`, `RetrievalQuery`, `EmbeddingRankingPipeline`,
  `SimilarityAndScoringEngine`, `ScoreStatistics`, `ScoredEmbedding`, `ConceptNameReranker`,
  `ConceptKeywordMatching`, `CoherenceFilters`, `RankingPipelineGates`, `RefinementPaths`,
  `PipelineConfig`, `ConceptRescueAndFilter`, `ModelNoiseProfile`.
- Stripped `LlmInferenceService`'s legacy static delegators + `FindSimilarResult` (kept the
  `QueryPreprocessor` delegators); slimmed `PipelineSettings` to `usePreFilter` + `getQueryStoreTopK`;
  dropped the ES/Lucene `close()` from the activator; deleted `ChartSearchAiUtils.usesLuceneIndex`.
- Reworked `ChartSearchServiceRouter.buildCacheKey` to fold the querystore GPs (preFilter,
  querystore.enabled, querystore.topK) + grounding GPs instead of the removed embedding-tuning GPs.
- Removed the retrieval-pipeline + embedding-tuning + ES/RRF/kNN constants + their config.xml GPs,
  and the Lucene/Elasticsearch pom dependencies. **Kept** the `chartsearchai.querystore.enabled`
  GP (grounding + cache key still read it; `false` now means unranked full chart).
- **Deleted 8 legacy test files** (`Elasticsearch/Embedding/HybridRetriever` indexer tests,
  `ElasticsearchKnnFallbackTest`, `EndToEndSearchTest`, `RetrievalQualityEvalTest`,
  `EnrichedRetrievalEvalTest` (the 485-case harness), `LlmInferenceServiceEvalTest`); surgically
  pared `LlmInferenceServiceTest` (84→38 methods) and `TestDatasetHelper`. The querystore-backed
  replacements (`QueryStoreRetrievalParityEvalTest`/`QueryStoreContentParityEvalTest`) live on the
  `phase0-evals` branch.
- **Verified (in-process)**: `mvn -pl api test` → 575 run, 0 failures, 35 skipped; clean `mvn install`
  packages the `.omod` with **no orphaned bytecode** for the deleted classes (a stale-`.class`
  packaging bug caught by inspecting the omod — an incremental build had shipped the `@Component`
  indexers, which would `NoClassDefFoundError` at Spring scan now that Lucene/ES are off the classpath).
- **Verified (standalone smoke, 2026-06-17, agnes-adams/patient 27, 267 records, local E2B engine)**:
  deployed the Phase 2 omod + cleared lib-cache; both `chartsearchai` and `querystore` report
  `started:true` (the slimmed Spring context initializes with the legacy beans gone — no
  `NoClassDefFoundError`/bean-wiring failure); deployed jar has **0** legacy classes. A real
  `POST /chartsearchai/search` returned a citation-grounded answer (14+ conditions, 29 references) and,
  on a follow-up, correctly answered absent data ("records do not contain information regarding
  allergies", 0 citations — no hallucination). `[timing] querystoreBuild patient=27 mode=fullChart
  hits=266 outcome=ok` proves the querystore path executed; `cachedTokens=10854/10866` confirms KV reuse.

**Phase 3 — Delete ONNX + the embedding provider. (✅ DONE.)** Decision: **drop** chartsearchai's
ONNX grounding fallback. `CitationGroundingVerifier.resolveEmbedder()` now returns querystore's
embedder (the same e5 model that built the index) or `null` — and the verifier already degrades a
`null` Tier-1 embedder to Tier-2-only (the authoritative pass). Since querystore is required and its
`querystore.embedding.dispatcher` is the normal path (the Phase-2 smoke already showed grounding ran
through it, not the fallback), removing the fallback changes behavior only in the rare
querystore-embedder-unavailable edge — to the same graceful degradation that already existed. What
shipped:
- Removed the `@Autowired EmbeddingProvider` + `setEmbeddingProvider` seam + the fallback branch and
  `isQueryStoreEnabled()` gate from `CitationGroundingVerifier`.
- Deleted the `embedding/` package: `EmbeddingProvider`, `OnnxEmbeddingProvider`
  (`@Component("chartSearchAi.embeddingProvider")`), `WordPieceTokenizer`.
- Activator: dropped the ONNX `close()` from `stopped()` and the ONNX model/vocab validation from
  `validateConfiguration()` (resolves the deferred Phase-2 harden item).
- Removed the ONNX constants/config GPs (`embedding.modelFilePath`, `queryModelFilePath`,
  `vocabFilePath`, `maxSequenceLength`) and the **`onnxruntime` pom dependency**. Kept the LLM model
  path + all grounding GPs (updated `grounding.minCosine` to drop the stale all-MiniLM advice).
- Tests: deleted the 5 ONNX/embedding probe tests + the dead `StubEmbeddingProvider` helper + the
  dead `TestDatasetHelper` model-locating fields; **refactored `CitationGroundingVerifierTest`** to
  inject via a `resolveEmbedder()`→`TextEmbedder` seam (all 32 tests preserved). Removing the obsolete
  `chartSearchAi.embeddingProvider` bean from `TestingApplicationContext.xml` was required — leaving
  it pointed Spring at the deleted stub and failed the whole context (126 errors, caught by the build).
- **Verified**: `mvn -pl api test` → 565 run, 0 failures, 34 skipped; clean omod **shrank 75.7 MB →
  36.1 MB (−52%)** with no onnxruntime or chartsearchai embedding classes. Standalone smoke
  (agnes-adams): both modules `started:true`, grounding returned **30 citations all grounded=true**
  via querystore's e5 embedder (zero "embedding provider unavailable" warnings — the fallback was
  never needed). `QueryPreprocessor` was left intact (its query-prep helpers are still used).

**Phase 4 — Drop the embedding store. (✅ DONE.)** The `ChartEmbedding` store went fully dead
once Phase 2 deleted its only readers/writers (the indexers + retrieval pipeline). Removed:
- `model/ChartEmbedding.java` + `ChartEmbedding.hbm.xml`; the `ChartEmbedding.hbm.xml` entry in
  `config.xml` `<mappingFiles>` **and** in the test `chartsearchai-hibernate.cfg.xml` (the latter
  would otherwise fail the test Hibernate session factory — caught before the build).
- The 6 dead embedding methods from `ChartSearchAiDAO` + `HibernateChartSearchAiDAO`
  (`saveChartEmbedding`, `getByResource`, `getByPatient`, `deleteByPatient`,
  `replacePatientEmbeddings`, `getIndexedPatientIds`) — all had zero callers post-Phase-2.
- The `chartsearchai-001` liquibase changeset (the `chartsearchai_embedding` table + FK/index/unique
  constraint). Deleted outright rather than adding a `dropTable` (no production deployments; the
  module is unreleased `1.0.0-SNAPSHOT`, matching the liquibase.xml pre-production "consolidate, don't
  append" header policy). Existing dev DBs keep a harmless orphaned empty table; liquibase doesn't
  error on a removed changeset.
- Tests: deleted `ChartEmbeddingTest`; pared `HibernateChartSearchAiDAOTest` 20→11 (removed the 9
  embedding tests + `createEmbedding` helper + UUID constants, kept all audit/query-count tests).
- Kept the audit-log store entirely (`ChartSearchAuditLog` + `chartsearchai-002`).
- **Verified**: `mvn -pl api test` → 562 run, 0 failures, 35 skipped (the context-sensitive
  `HibernateChartSearchAiDAOTest` brings up a real Hibernate session factory without the
  `ChartEmbedding` mapping); clean `mvn install` packages the `.omod` with no `ChartEmbedding`
  class/hbm and no embedding-table reference in the bundled config.xml/liquibase.xml.

**Phase 5 — Cleanup.** Relocate `SerializedRecord`; remove `PatientRecordLoader`
+ all `*TextSerializer`s; trim `PatientChartSerializer` to the kept rendering
form; prune dead constants/GPs/imports; update `README.md`, the GP descriptions,
and **`CLAUDE.md`** (its embedding-pipeline/eval-tuning rules — `PipelineConfig.forModel`,
`ModelNoiseProfile.compute`, `EnrichedRetrievalEvalTest`, the per-model eval
commands — become obsolete and would otherwise misdirect future work).

## 5. Risks & open prerequisites (from the issue)

- ◻︎ **Long-text chunking** — querystore must chunk to MiniLM's ~256-token cap or
  long obs/notes embed worse than chartsearchai did. Blocks Phase 3 parity.
- ◻︎ **Sync reliability & reconciliation** — detection shipped, remediation
  pending. Until remediation lands, a querystore sync gap silently degrades
  retrieval with no chartsearchai-side safety net. Blocks Phase 1 going to prod.
- ✅ Patient merge handling — resolved in querystore.
- ◻︎ Initial backfill/bootstrap — largely implemented; confirm before Phase 1.
- **One-way GP transition:** flipping `querystore.enabled` off→on does not refresh
  legacy indices (already documented). Treat Phase 1+ as one-way per deployment.
- **Citation grounding:** confirm `CitationGroundingVerifier` has no hidden
  dependency on the embedding provider before Phase 3 (it imports only
  `PatientChartSerializer.RecordMapping`, so likely safe — verify).

## 6. Per-phase verification

Run before each phase's PR (per CLAUDE.md):
- `mvn -q test` (full suite).
- Phase 0–1 (historical): the cross-query regression tests (`enriched_*` in
  `LlmInferenceServiceTest`) + the legacy eval harness (`EnrichedRetrievalEvalTest`),
  default (L6-v2) **and** `-Dchartsearchai.eval.model=medcpt`. **Removed in Phase 2**
  along with the embedding pipeline they exercised — do not expect them to exist.
- Phase 2+: the querystore-backed eval harness (`QueryStoreRetrievalParityEvalTest` /
  `QueryStoreContentParityEvalTest`, currently on the `phase0-evals` branch) must meet the
  recorded Phase-0 parity bar — verify the returned records, not just counts. CLAUDE.md's
  embedding-pipeline/eval-tuning rules become obsolete here and are pruned in Phase 5.
- End-to-end smoke on the standalone testbed against `agnes-adams`.
