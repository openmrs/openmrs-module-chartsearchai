/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai;

public class ChartSearchAiConstants {

	public static final String PRIV_QUERY_PATIENT_DATA = "AI Query Patient Data";

	public static final String PRIV_VIEW_AUDIT_LOGS = "View AI Audit Logs";

	public static final String GP_LLM_MODEL_FILE_PATH = "chartsearchai.llm.modelFilePath";

	public static final String GP_EMBEDDING_PRE_FILTER = "chartsearchai.embedding.preFilter";

	/**
	 * When {@code true}, the chart serializer run-length de-dups the obs-group membership label: a member
	 * renders {@code " (part of: <group>)"} only when its group differs from the immediately-preceding
	 * record's group (mirrors the date-run compression). Applies to ALL obs groups (lab panels,
	 * vital-signs sets, exam findings, ...), not only lab panels. VERIFIED 2026-06-18: real saving is only
	 * ~2% of prompt tokens (a chars/4 estimate had overstated it ~3x), and it is SAFE ONLY ON E4B+ — on
	 * the small E2B model it causes a clustering failure (a false "no results" for a thinned-label group).
	 * So only enable on E4B-or-larger deployments. Default {@code false} (legacy every-member labelling).
	 */
	public static final String GP_SERIALIZER_DEDUP_GROUP_LABELS = "chartsearchai.serializer.dedupGroupLabels";

	/** Number of top results the querystore retrieval path requests for the
	 *  focus-hint pass; tunes that path independently of any default. */
	public static final String GP_QUERYSTORE_TOP_K = "chartsearchai.querystore.topK";

	public static final int DEFAULT_QUERYSTORE_TOP_K = 30;

	public static final String GP_AUDIT_LOG_RETENTION_DAYS = "chartsearchai.auditLogRetentionDays";

	public static final int DEFAULT_AUDIT_LOG_RETENTION_DAYS = 90;

	public static final String GP_LLM_ENGINE = "chartsearchai.llm.engine";

	public static final String LLM_ENGINE_LOCAL = "local";

	public static final String LLM_ENGINE_REMOTE = "remote";

	public static final String GP_LLM_REMOTE_ENDPOINT_URL = "chartsearchai.llm.remote.endpointUrl";

	public static final String RP_LLM_REMOTE_API_KEY = "chartsearchai.llm.remote.apikey";

	public static final String GP_LLM_REMOTE_MODEL_NAME = "chartsearchai.llm.remote.modelName";

	public static final String GP_SYSTEM_PROMPT = "chartsearchai.llm.systemPrompt";

	public static final String GP_LLM_TIMEOUT_SECONDS = "chartsearchai.llm.timeoutSeconds";

	public static final int DEFAULT_LLM_TIMEOUT_SECONDS = 300;

	public static final String GP_LLM_IDLE_TIMEOUT_MINUTES = "chartsearchai.llm.idleTimeoutMinutes";

	public static final int DEFAULT_LLM_IDLE_TIMEOUT_MINUTES = 30;

	public static final String GP_LLM_SERVER_PORT = "chartsearchai.llm.serverPort";

	public static final int DEFAULT_LLM_SERVER_PORT = 18085;

	public static final String GP_LLM_CONTEXT_SIZE = "chartsearchai.llm.contextSize";

	public static final int DEFAULT_LLM_CONTEXT_SIZE = 32768;

	public static final int DEFAULT_LLM_MAX_OUTPUT_TOKENS = 4096;

	/**
	 * Directory where the local engine persists each patient's prefilled KV cache (one file per
	 * distinct chart prefix). When set, llama-server is launched with {@code --slot-save-path} and
	 * both the chart-open warmup and the streaming query path restore a patient's KV from disk
	 * (I/O-bound, ~tens of ms) instead of re-running the full chart prefill (CPU-bound, tens of
	 * seconds to minutes on a GPU-less host) whenever the in-process RAM prompt cache is cold for it;
	 * a cold query also saves its fresh prefill so the next visit is fast even without a warmup. The restored state is byte-for-byte what a
	 * fresh prefill would have produced, so answer quality is unchanged. Enabled by default: an
	 * empty/unset value resolves to {@code <appdata>/chartsearchai/kvcache}. Set an explicit path to
	 * relocate it (e.g. to faster or larger storage), or a disable token
	 * ({@code off}/{@code false}/{@code none}/{@code disabled}) to turn it off — the escape hatch for
	 * hosts that do not want the on-disk KV files (which contain the model's encoding of the chart)
	 * or their disk footprint. See {@link org.openmrs.module.chartsearchai.api.impl.LocalLlmEngine#resolveKvCacheDir(String, String)}.
	 */
	public static final String GP_LLM_KV_CACHE_DIR = "chartsearchai.llm.kvCacheDir";

	/**
	 * Maximum number of persisted KV-cache files to retain in {@link #GP_LLM_KV_CACHE_DIR}. Each
	 * file is large (tens to a few hundred MB, proportional to the chart's token count), so the
	 * oldest entries are evicted (by last-modified time) once this many exist. Only consulted when
	 * the cache directory is configured.
	 */
	public static final String GP_LLM_KV_CACHE_MAX_ENTRIES = "chartsearchai.llm.kvCacheMaxEntries";

	public static final int DEFAULT_LLM_KV_CACHE_MAX_ENTRIES = 16;

	public static final String GP_RATE_LIMIT_PER_MINUTE = "chartsearchai.rateLimitPerMinute";

	public static final int DEFAULT_RATE_LIMIT_PER_MINUTE = 10;

	public static final String GP_CACHE_TTL_MINUTES = "chartsearchai.cacheTtlMinutes";

	public static final int DEFAULT_CACHE_TTL_MINUTES = 0;

	public static final int DEFAULT_CACHE_MAX_SIZE = 100;

	public static final String GP_WARMUP_ENABLED = "chartsearchai.warmupEnabled";

	/**
	 * When {@code true}, a streaming query first runs a fast "preview" reasoning pass over only the
	 * querystore top-K query-relevant records and streams that reasoning to the thinking channel,
	 * ahead of the unchanged full-chart answer. The focused chart is a few hundred tokens versus the
	 * full chart's several thousand, so on a GPU-less host its prefill — and thus
	 * time-to-first-reasoning — is far smaller. The committed answer is still the full-chart call
	 * (the preview answer is discarded), so response quality is unchanged. Default {@code false}
	 * (opt-in): the two passes serialize on llama-server's single slot, so it trades a marginally
	 * longer time-to-final-answer for a much shorter time-to-first-reasoning. See
	 * {@code LlmInferenceService.maybeEmitPreliminaryReasoning}.
	 */
	public static final String GP_PROGRESSIVE_REASONING_ENABLED = "chartsearchai.progressiveReasoning.enabled";

	public static final boolean DEFAULT_PROGRESSIVE_REASONING_ENABLED = false;

	/**
	 * Number of top-ranked querystore records the progressive-reasoning preview pass reasons over.
	 * Smaller = faster preview prefill but less context for the preliminary reasoning; the committed
	 * full-chart answer is unaffected either way. Kept distinct from {@link #GP_QUERYSTORE_TOP_K}
	 * (the focus-hint size) so the two can be tuned independently.
	 */
	public static final String GP_PROGRESSIVE_REASONING_TOP_K = "chartsearchai.progressiveReasoning.topK";

	public static final int DEFAULT_PROGRESSIVE_REASONING_TOP_K = 15;

	/**
	 * When {@code true}, every cited record is checked for grounding after the
	 * LLM answers: the record's text must be semantically close enough to the
	 * answer sentence(s) that cite it, otherwise the citation is flagged as
	 * unverified. Index validation alone (does {@code [N]} map to a real
	 * retrieved record?) cannot catch the dangerous case of a real record cited
	 * for a claim it does not actually support. Default {@code false} so the
	 * feature is opt-in. See {@code CitationGroundingVerifier}.
	 */
	public static final String GP_GROUNDING_ENABLED = "chartsearchai.grounding.enabled";

	public static final boolean DEFAULT_GROUNDING_ENABLED = false;

	/**
	 * Minimum cosine similarity between a cited record's text and the answer
	 * sentence that cites it for the citation to count as grounded. This Tier-1
	 * check catches grossly off-topic citations (a blood-pressure record cited
	 * for a diabetes claim), not subtle subject/negation flips ("patient has X"
	 * vs "mother had X") — those need the Tier-2 entailment pass.
	 *
	 * <p><strong>The right floor depends on the embedding model — tune it.</strong>
	 * The {@link #DEFAULT_GROUNDING_MIN_COSINE} of {@value #DEFAULT_GROUNDING_MIN_COSINE}
	 * suits a wide-spread model like all-MiniLM-L6-v2 (chartsearchai's own default).
	 * It is far too low for <em>e5</em> — the model querystore uses, which the
	 * grounding verifier reuses (querystore is the only retrieval/embedding backend).
	 * Measured e5 cosines (mean-pooled, no prefix): supported pairs ~0.83–0.96,
	 * unrelated pairs ~0.75–0.80. So on an e5/querystore deployment set this to
	 * <strong>~0.82</strong>; at {@value #DEFAULT_GROUNDING_MIN_COSINE} e5 marks
	 * essentially everything grounded (no discrimination).
	 *
	 * <p>Note the e5 supported-vs-unrelated gap is narrow (~0.03), so Tier-1 alone
	 * is a weak discriminator there — enable {@link #GP_GROUNDING_ENTAILMENT_ENABLED}
	 * for reliable grounding on e5. Erring high is the safer direction: an
	 * over-flagged citation ("unsupported") prompts a clinician to verify, whereas
	 * an under-flagged one ("verified") gives false assurance.
	 */
	public static final String GP_GROUNDING_MIN_COSINE = "chartsearchai.grounding.minCosine";

	public static final double DEFAULT_GROUNDING_MIN_COSINE = 0.40;

	/**
	 * When {@code true} (and {@link #GP_GROUNDING_ENABLED} is also on), the
	 * cited references are confirmed by a Tier-2 entailment check: a yes/no LLM
	 * judgement of whether each cited record actually supports the answer
	 * sentence citing it. This is what catches high-overlap-but-false citations
	 * ("patient has X [5]" where record 5 says a relative had X, or the record
	 * negates X) that cosine similarity cannot separate. An answer's citations
	 * are verified in one batched LLM call (capped per answer; clause-scoped
	 * compound-sentence citations get single-pair calls), and the Tier-1 cosine
	 * verdict is computed lazily only where Tier-2 yields none, so the marginal
	 * cost is one LLM round-trip per answer. Still a separate opt-in from the
	 * cheap Tier-1 pass. Default {@code false}. See {@code CitationGroundingVerifier}.
	 */
	public static final String GP_GROUNDING_ENTAILMENT_ENABLED = "chartsearchai.grounding.entailment.enabled";

	public static final boolean DEFAULT_GROUNDING_ENTAILMENT_ENABLED = false;

	/**
	 * When set, citation grounding is clause-scoped: a sentence citing multiple records checks each
	 * citation against the answer text up to and including its own {@code [N]} marker, not the whole
	 * compound sentence. This grounds a citation that supports its own clause but not a later clause
	 * cited by a different record (e.g. an active condition cited alongside a provisional diagnosis in
	 * one sentence). Default {@code false} (sentence-scoped, the original behaviour).
	 */
	public static final String GP_GROUNDING_CLAUSE_SCOPED = "chartsearchai.grounding.clauseScoped";

	public static final boolean DEFAULT_GROUNDING_CLAUSE_SCOPED = false;

	/**
	 * Upper bound on the number of citations Tier-2 entailment verifies per answer (i.e. the batch
	 * size), so a heavily-cited answer cannot make the single batched entailment prompt grow without
	 * bound. References beyond this many keep their Tier-1 verdict; the verifier logs once when the
	 * cap is hit (no silent truncation). Tier-2 issues one batched LLM call per answer regardless of
	 * how many citations it carries.
	 */
	public static final int GROUNDING_ENTAILMENT_MAX_CHECKS = 16;

	/**
	 * When {@code true} (and {@link #GP_GROUNDING_ENABLED} is also on), the streaming endpoint
	 * emits its terminal {@code done} event as soon as the answer is complete — references
	 * without verdicts — and delivers the grounding verdicts afterwards in a trailing
	 * {@code grounded} SSE event. On CPU-only deployments the grounding pass adds seconds of
	 * Tier-2 LLM work after the answer is already readable; this moves that tail off the user's
	 * perceived completion. Clients must keep consuming the stream after {@code done} and apply
	 * the {@code grounded} event's verdicts when it arrives (citations render as unverified until
	 * then). The blocking {@code /search} endpoint is unaffected — its single response always
	 * carries final verdicts. Default {@code false} (classic single grounded {@code done}).
	 */
	public static final String GP_GROUNDING_ASYNC = "chartsearchai.grounding.async";

	public static final boolean DEFAULT_GROUNDING_ASYNC = false;

	// ---------------------------------------------------------------------
	// Drug reference + post-answer drug-safety validation (additive, opt-in).
	// See DrugReferenceService, DrugReferenceInjector, DrugSafetyValidator.
	// ---------------------------------------------------------------------

	/** Master switch for both the DrugReference resource type and the DrugSafetyValidator. Default off. */
	public static final String GP_DRUG_REFERENCE_ENABLED = "chartsearchai.drugReference.enabled";

	public static final boolean DEFAULT_DRUG_REFERENCE_ENABLED = false;

	/** Path (relative to the OpenMRS application data directory) to the drug-reference dataset.
	 *  When absent, the dataset bundled on the module classpath is used. */
	public static final String GP_DRUG_REFERENCE_DATA_FILE_PATH = "chartsearchai.drugReference.dataFilePath";

	/** Selects the drug-reference data adapter: {@code json} (the curated default) or {@code atc}
	 *  (consume a WHO ATC classification export by pointing dataFilePath at it). See ADR Decision 24. */
	public static final String GP_DRUG_REFERENCE_SOURCE_FORMAT = "chartsearchai.drugReference.sourceFormat";

	public static final String DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT = "json";

	/** Value of {@link #GP_DRUG_REFERENCE_SOURCE_FORMAT} that selects the ATC classification source. */
	public static final String DRUG_REFERENCE_SOURCE_ATC = "atc";

	/** Patient-driven injection: inject reference entries that match an active order's ATC code. */
	public static final String GP_DRUG_REFERENCE_INJECT_FROM_ORDERS = "chartsearchai.drugReference.injectFromOrders";

	public static final boolean DEFAULT_DRUG_REFERENCE_INJECT_FROM_ORDERS = true;

	/** Question-driven injection: inject reference entries whose aliases match the query text. */
	public static final String GP_DRUG_REFERENCE_INJECT_FROM_QUERY = "chartsearchai.drugReference.injectFromQuery";

	public static final boolean DEFAULT_DRUG_REFERENCE_INJECT_FROM_QUERY = true;

	/** Enables the post-LLM drug-safety validator (requires {@link #GP_DRUG_REFERENCE_ENABLED}). */
	public static final String GP_DRUG_SAFETY_VALIDATE_ANSWERS = "chartsearchai.drugSafety.validateAnswers";

	public static final boolean DEFAULT_DRUG_SAFETY_VALIDATE_ANSWERS = true;

	/** Flag answer doses above the reference {@code maxDailyDoseMg} for the patient's age band. */
	public static final String GP_DRUG_SAFETY_WARN_ON_DOSE_EXCESS = "chartsearchai.drugSafety.warnOnDoseExcess";

	public static final boolean DEFAULT_DRUG_SAFETY_WARN_ON_DOSE_EXCESS = true;

	/** Cross-check drugs named in the answer against the patient's active orders for interactions. */
	public static final String GP_DRUG_SAFETY_WARN_ON_INTERACTIONS = "chartsearchai.drugSafety.warnOnInteractions";

	public static final boolean DEFAULT_DRUG_SAFETY_WARN_ON_INTERACTIONS = true;

	/** Cross-check drugs named in the answer against the patient's allergies/conditions for contraindications. */
	public static final String GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS =
			"chartsearchai.drugSafety.warnOnContraindications";

	public static final boolean DEFAULT_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS = true;

	/**
	 * When {@code > 0}, the {@code reasoning} scratchpad in the chart-answer schema is capped at
	 * this many characters via a grammar-enforced {@code maxLength} — bounding the dominant
	 * decode cost on CPU-only servers (the model otherwise thinks for 3–27s before any answer
	 * token). The answer itself is never capped. {@code 0} (default) leaves the schema exactly
	 * as before. Because truncating the model's chain of thought can change its answers, any
	 * non-zero value must first clear the 32-cell answer-quality gold standard
	 * ({@code eval/drift-metric/metric_gold.standalone.json}): mean F1, abstention accuracy and
	 * off-topic-citation count must not regress versus the uncapped baseline.
	 *
	 * <p><strong>Measured negative result (2026-06-12), Gemma 4 E2B at 400 chars:</strong>
	 * meanF1 0.464&rarr;0.428, abstention 1.00&rarr;0.91 (a false citation on an absent-topic
	 * cell), off-topic citations 41&rarr;47 — the gate failed on all three axes, so NO certified
	 * value exists for E2B. The mechanism is structural: a binding cap cuts the chain of thought
	 * mid-derivation and the answer degrades; a non-binding cap saves nothing. Do not enable
	 * without a fresh gate run for the specific model and value.
	 */
	public static final String GP_LLM_REASONING_MAX_CHARS = "chartsearchai.llm.reasoningMaxChars";

	public static final int DEFAULT_LLM_REASONING_MAX_CHARS = 0;

	// Resource type identifiers used in embeddings and citations
	public static final String RESOURCE_TYPE_OBS = "obs";

	public static final String RESOURCE_TYPE_CONDITION = "condition";

	public static final String RESOURCE_TYPE_ALLERGY = "allergy";

	public static final String RESOURCE_TYPE_DIAGNOSIS = "diagnosis";

	public static final String RESOURCE_TYPE_ORDER = "order";

	public static final String RESOURCE_TYPE_PROGRAM = "program";

	public static final String RESOURCE_TYPE_MEDICATION_DISPENSE = "medication_dispense";

	/** Reference data, not patient data — injected by {@link org.openmrs.module.chartsearchai.reference.DrugReferenceInjector}. */
	public static final String RESOURCE_TYPE_DRUG_REFERENCE = "drug_reference";

	private ChartSearchAiConstants() {
	}
}
