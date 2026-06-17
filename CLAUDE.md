# Rules

- When a test fails, fix the pipeline/production code — never weaken assertions, revert test data, or change expected values to match wrong behavior.
- Prefer root-cause fixes over incremental symptom patches. Diagnose *why* something is broken before proposing a fix. Start with the best solution, not the quickest.
- Before claiming the filter is correct, verify what it actually returns — check the records, not just the count or the final answer. A filter returning 32 irrelevant records is not correct even if the LLM produces the right answer from them.
- Do not go in circles analyzing a problem without fixing it.
- Tests must call the actual production pipeline — no simulations, mocks, or reimplementations of pipeline logic in test code. This includes unit tests for internal methods: do not call internal methods directly with hand-crafted inputs. Every test must exercise the full production code path using real data (e.g. the patient test datasets and the real querystore-backed retrieval path).
- When a multi-step pipeline exists (e.g. normalize → transform → embed), tests must call the composed production method, not manually chain the individual steps. Manually chaining steps in tests can mask bugs where the production code assembles the pipeline differently. If no composed method exists, extract one and use it from both production and test code.
- Tests are the specification. Modifying tests to make them pass is changing the spec — only fix the production code to satisfy the existing tests.
- Before presenting changes, review your own diff multiple times. On each pass, check every item below. Keep reviewing until a pass finds nothing to fix.
  1. Does the change match the plan?
  2. Is the logic correct — edge cases, off-by-one errors, null/empty handling?
  3. Are there any regressions — does existing behavior stay intact?
  4. Is anything missing — incomplete implementation, forgotten call sites?
  5. Does it use the real production pipeline, not a simulation or mock?
  6. Were any tests modified? If so, that's a rule violation — revert and fix the production code instead.
  7. Were all rules in this file followed?
- Follow test-driven development: for every bug fix or new feature, first write a failing test that defines the expected behavior, then write production code to make it pass. Write the strictest assertion — if it doesn't fail, tighten it until it does.
- Always create a plan before writing code. Read the relevant code, outline the approach, then implement.
- Never commit code with known regressions. When a change fixes one case but breaks another, the root cause is usually in shared infrastructure, not the individual call site — diagnose the shared problem before patching stages.
- Retrieval is owned by openmrs-module-querystore (issue #51). chartsearchai no longer has an in-process embedding/Lucene/Elasticsearch pipeline, scoring/ranking code, an embedding store, or a retrieval eval harness — do not reintroduce them. Retrieval changes *and retrieval-quality evaluation* belong in the querystore module (it owns the index and the e5-base-v2 embedder), not here.

# API surface rules — do not bypass these methods

These methods are the ONLY correct entry points for their respective operations. Do not reimplement their logic inline, call their internal helpers directly, or hardcode their output values.

- **Prefixed text**: Use `ChartSearchAiUtils.buildPrefixedText(resourceType, text)`. Never call `getEmbeddingPrefix()` directly (it is private) or hardcode prefix strings like `"Clinical observation: "`.
- **Cosine similarity**: Use `ChartSearchAiUtils.cosineSimilarity()`. Never reimplement the formula.
- **Chart assembly**: Use `ChartBuildingStrategy.buildChart()` → `QueryStoreChartBuilder.build()`. Never fetch or serialize a patient chart by another path; querystore owns retrieval.
- **Test datasets**: Use `TestDatasetHelper.FULL_PATIENT_DATASET`, `TestDatasetHelper.SECOND_PATIENT_DATASET`, `TestDatasetHelper.toSerializedRecords()`, `TestDatasetHelper.inferResourceType()`, `TestDatasetHelper.stripDatasetPrefixAndDate()`. Never duplicate these helpers in individual test files.
- **Category hints in tests**: Use `TestDatasetHelper.FULL_DATASET_CATEGORY_HINTS` (and per-dataset variants). Never duplicate hints maps in individual test files.
- **Stripping category hints**: Use `ChartSearchAiUtils.stripCategoryHints()`. Never reimplement the hint-detection pattern.
