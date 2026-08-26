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

	/** Admin/system privilege for the bulk KV-prewarm bootstrap endpoints (trigger + status). Distinct
	 *  from the per-patient {@link #PRIV_QUERY_PATIENT_DATA} clinician action because a sweep prefills
	 *  every patient's chart and monopolises the single inference slot — a system operation, not a
	 *  clinical one. */
	public static final String PRIV_MANAGE_PREWARM = "Manage AI Prewarm";

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

	/** Number of similarity records the querystore retrieval path requests. In queryScoped mode
	 *  (the default) this sizes the query slice the LLM actually sees; in fullChart mode it only
	 *  sizes the optional focus-hint (and is unused when {@code embedding.preFilter=false}). */
	public static final String GP_QUERYSTORE_TOP_K = "chartsearchai.querystore.topK";

	/**
	 * Default similarity budget for the queryScoped slice. Lowered 30 → 12 in 2026-07 after a topK
	 * sweep on the 22-patient drift-metric gold and a 36-patient sweep on the demo instance: 12
	 * holds recall/F1 at the plateau (meanF1 ≈ 0.746 vs 0.748 at 30) while IMPROVING abstention
	 * (0.93 vs 0.86) and roughly halving off-topic drift (≈100 vs 181) — the smaller, less noisy
	 * slice stops the small model over-citing vitals on absent-topic questions — and cutting CPU
	 * time-to-first-token ~2.4× (≈3.5s vs 8.3s). The knee is ~12–15 (below ~12 recall erodes;
	 * above ~15 abstention/drift degrade with no F1 gain). Only material in queryScoped; fullChart
	 * ignores it with preFilter off.
	 */
	public static final int DEFAULT_QUERYSTORE_TOP_K = 12;

	/**
	 * How the LLM prompt's chart context is assembled per query.
	 * <ul>
	 *   <li>{@link #CHART_MODE_QUERY_SCOPED} ({@link #CHART_MODE_DEFAULT default}) — the prompt carries
	 *       only a query-scoped slice: every record of the question's typed scope (e.g. all drug orders
	 *       for a medications question — complete by construction, see {@code QueryScopeRouter}) plus the
	 *       querystore similarity top-K plus the demographics record, in the chart's most-recent-first
	 *       order. Slices are a few hundred tokens, so a cold patient's first answer starts after a small
	 *       prefill with no pre-warming of any kind; the full-chart prefill machinery (warmup, prewarm
	 *       bootstrap, per-patient KV persistence, progressive-reasoning preview) disengages in this
	 *       mode. Made the default 2026-07 after a 22-patient drift-metric A/B: scoped beat fullChart on
	 *       meanF1 (0.748 vs 0.668), abstention (0.86 vs 0.74), and off-topic drift (181 vs 477) — the
	 *       focused slice keeps the small model from drowning in a whole chart's worth of noise.</li>
	 *   <li>{@link #CHART_MODE_FULL_CHART} — the patient's whole chart is serialized into every prompt.
	 *       The chart bytes do not vary with the question, so llama-server's KV prefix cache (plus
	 *       warmup/prewarm/disk persistence) amortizes the multi-thousand-token prefill across queries;
	 *       this makes repeat/varied questions on an already-warmed patient fast, at the cost of a heavy
	 *       first-ever query (tens of seconds to minutes on a GPU-less host). Prefer this only where a
	 *       single patient is queried many times per session and completeness-over-focus is wanted.</li>
	 * </ul>
	 */
	public static final String GP_CHART_MODE = "chartsearchai.chartMode";

	public static final String CHART_MODE_FULL_CHART = "fullChart";

	public static final String CHART_MODE_QUERY_SCOPED = "queryScoped";

	/**
	 * The chart mode used when {@link #GP_CHART_MODE} is unset (and the fail-safe when the GP layer
	 * cannot be read). Single source of truth for the default so the two independent readers
	 * ({@code PipelineSettings.queryScopedMode} and {@code ChartSearchServiceRouter}'s cache key)
	 * cannot drift. A GP explicitly set to a typo'd value still resolves to fullChart, because the
	 * scoped gate requires an exact (case-insensitive) {@code queryScoped} match — only a genuinely
	 * absent or unreadable GP takes this default.
	 */
	public static final String CHART_MODE_DEFAULT = CHART_MODE_QUERY_SCOPED;

	/**
	 * A full chart carrying the similarity focus hint {@code chartsearchai.embedding.preFilter}
	 * turns on — and the anchor for the whole {@code SEARCH_MODE_*} family, documented here because
	 * the family has no {@code GP_} declaration of its own to hang from the way {@code CHART_MODE_*}
	 * hangs from {@link #GP_CHART_MODE}.
	 *
	 * <p>The vocabulary of the audit log's {@code search_mode} column — how the prompt's chart context
	 * was assembled for the query that row records. Resolved once per request by
	 * {@code ChartBuildingStrategy.searchModeLabel} and carried on the answer; the REST layer writes
	 * it and derives nothing, because deriving it there is what issue #178 was: both audit-write
	 * sites branched on the preFilter GP alone, so {@link #CHART_MODE_QUERY_SCOPED} — the shipped
	 * {@link #CHART_MODE_DEFAULT default} — could not appear in the column at all, and every row on a
	 * default install claimed {@link #SEARCH_MODE_FULL_CHART} while the prompt carried a slice.
	 *
	 * <p>{@link #SEARCH_MODE_PRE_FILTER} and {@link #SEARCH_MODE_FULL_CHART} keep the exact spellings
	 * they have written since the column existed: these rows are read outside this module, so #178
	 * ADDS a third value rather than re-spelling two. {@link #SEARCH_MODE_QUERY_SCOPED} is defined AS
	 * the GP value, so the row names the mode with the same token an operator sets. Note the
	 * {@code [timing] querystoreBuild} log lines spell the same two dispatch shapes
	 * {@code preFilter}/{@code fullChart} — a separate ops contract, deliberately not unified here.
	 */
	public static final String SEARCH_MODE_PRE_FILTER = "pre-filter";

	/** A full chart with no focus hint. See {@link #SEARCH_MODE_PRE_FILTER} for the family. */
	public static final String SEARCH_MODE_FULL_CHART = "full-chart";

	/** A query-scoped slice — the same token {@link #GP_CHART_MODE} takes, by construction.
	 *  See {@link #SEARCH_MODE_PRE_FILTER} for the family. */
	public static final String SEARCH_MODE_QUERY_SCOPED = CHART_MODE_QUERY_SCOPED;

	/**
	 * Written when an answer states no mode. The column is {@code not-null}, so something must be
	 * written; it is deliberately none of the three real modes, because a row that silently claims a
	 * mode nobody resolved is the defect #178 fixed rather than a tidier version of it. Unreachable
	 * from the in-tree pipeline, which labels every answer it builds — it exists for an alternative
	 * {@code ChartSearchService}, and mirrors {@code QueryStoreChartBuilder.MODE_UNKNOWN}, which
	 * buckets the dispatch it cannot honestly label the same way.
	 */
	public static final String SEARCH_MODE_UNKNOWN = "unknown";

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
	 * the cache directory is configured. Entries pinned by the prewarm bootstrap
	 * ({@link #GP_PREWARM_ENABLED}) are EXEMPT from this cap — they are neither counted nor evicted;
	 * bound them separately with {@link #GP_LLM_KV_CACHE_MAX_PINNED_ENTRIES}.
	 */
	public static final String GP_LLM_KV_CACHE_MAX_ENTRIES = "chartsearchai.llm.kvCacheMaxEntries";

	public static final int DEFAULT_LLM_KV_CACHE_MAX_ENTRIES = 16;

	/**
	 * Upper bound on the number of <em>pinned</em> KV entries the prewarm bootstrap may create
	 * ({@code 0} = unlimited, the default). Pinned entries are exempt from {@link #GP_LLM_KV_CACHE_MAX_ENTRIES}
	 * (the LRU cap), so a deployment with disk for every patient keeps them all warm. This is the
	 * safety valve for hosts that want a prewarm corpus but still bound its disk footprint: when set
	 * {@code > 0} and reached, the sweep logs once and stops pinning further entries (it never
	 * silently evicts already-pinned ones). Only consulted by the prewarm sweep.
	 */
	public static final String GP_LLM_KV_CACHE_MAX_PINNED_ENTRIES = "chartsearchai.llm.kvCache.maxPinnedEntries";

	public static final int DEFAULT_LLM_KV_CACHE_MAX_PINNED_ENTRIES = 0;

	public static final String GP_RATE_LIMIT_PER_MINUTE = "chartsearchai.rateLimitPerMinute";

	public static final int DEFAULT_RATE_LIMIT_PER_MINUTE = 10;

	public static final String GP_CACHE_TTL_MINUTES = "chartsearchai.cacheTtlMinutes";

	public static final int DEFAULT_CACHE_TTL_MINUTES = 0;

	public static final int DEFAULT_CACHE_MAX_SIZE = 100;

	public static final String GP_WARMUP_ENABLED = "chartsearchai.warmupEnabled";

	/**
	 * Master switch for the bulk KV-prewarm bootstrap (the {@code /prewarm} + {@code /prewarmstatus}
	 * endpoints and the background sweep). Default {@code false}: the feature is opt-in because a
	 * full-database sweep prefills every patient (10–20s each) on the single inference slot and grows
	 * the on-disk pinned KV corpus without bound (see {@link #GP_LLM_KV_CACHE_MAX_PINNED_ENTRIES}).
	 * The per-patient chart-open warmup is unaffected by this flag.
	 */
	public static final String GP_PREWARM_ENABLED = "chartsearchai.prewarm.enabled";

	public static final boolean DEFAULT_PREWARM_ENABLED = false;

	/**
	 * When {@code true} (and {@link #GP_PREWARM_ENABLED} is on), the prewarm sweep resumes/starts on
	 * module startup from its persisted cursor — the analog of {@code querystore.bootstrap.autostart}.
	 * Default {@code false}.
	 */
	public static final String GP_PREWARM_AUTOSTART = "chartsearchai.prewarm.autostart";

	public static final boolean DEFAULT_PREWARM_AUTOSTART = false;

	/**
	 * Milliseconds the prewarm sweep pauses between patients, so the single inference slot is not
	 * monopolised and live clinician warmup/query traffic can interleave. Default {@code 500}.
	 */
	public static final String GP_PREWARM_THROTTLE_MS = "chartsearchai.prewarm.throttleMs";

	public static final long DEFAULT_PREWARM_THROTTLE_MS = 500L;

	/**
	 * When {@code true}, a chart edit to a patient who is <em>already in the pinned prewarm corpus</em>
	 * schedules a single-patient re-pin (a debounced background {@code warmup(patient, true)}), so the
	 * durable corpus stays fresh without a manual re-sweep. Patients not already pinned are untouched
	 * (the reactive chart-open warmup covers them). Default {@code false}; only meaningful with
	 * {@code engine=local} and a pinned corpus. Independent of {@link #GP_PREWARM_ENABLED}: the bulk
	 * sweep and the per-edit refresh can be run separately.
	 */
	public static final String GP_PREWARM_REFRESH_ON_EDIT = "chartsearchai.prewarm.refreshOnEdit";

	public static final boolean DEFAULT_PREWARM_REFRESH_ON_EDIT = false;

	/**
	 * Quiet window (milliseconds) a per-edit re-pin waits before firing, coalescing a burst of writes
	 * to one patient (e.g. an encounter save that writes many obs) into a single re-pin. Default
	 * {@code 5000}.
	 */
	public static final String GP_PREWARM_REFRESH_DEBOUNCE_MS = "chartsearchai.prewarm.refreshDebounceMs";

	public static final long DEFAULT_PREWARM_REFRESH_DEBOUNCE_MS = 5000L;

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
	 * (the queryScoped slice size / fullChart focus-hint size) so the two can be tuned independently.
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
	 * are verified in one batched LLM call (capped per answer; the citations of one
	 * sentence whose claim statements overlap get single-pair calls — a clause-scoped
	 * compound, or an enumerating sentence in either mode), and the Tier-1 cosine
	 * verdict is computed lazily only where Tier-2 yields none, so the marginal
	 * cost is one LLM round-trip per answer. Two kinds of citation are never put to
	 * the judge at all: module-supplied reference material (issue #106/#122) and a
	 * COMPOUND claim unit, a statement attaching its citations to different pieces of
	 * itself (issue #302). Still a separate opt-in from the
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
	 * Upper bound on the number of citations Tier-2 entailment verifies per answer, so a heavily-cited
	 * answer cannot make the entailment prompt grow without bound. References beyond this many keep
	 * their Tier-1 verdict; the verifier logs once when the cap is hit (no silent truncation).
	 *
	 * <p>It bounds PAIRS, not calls, and is deliberately no longer described as "the batch size".
	 * Citations whose claim statements overlap are verified one pair per call rather than co-batched,
	 * because batched entailment is not per-pair independent — the fragments of a clause-scoped
	 * compound sentence, and of an ENUMERATING sentence in either mode (#278). An answer can therefore
	 * cost up to this many LLM round-trips rather than one, which is why the number is also a latency
	 * ceiling and not only a prompt-size one; {@code CitationGroundingVerifier.splitEnumeration}
	 * records the measured per-call cost. The previous wording ("Tier-2 issues one batched LLM call per
	 * answer regardless of how many citations it carries") was already inaccurate for clause-scoped
	 * grounding before #278 made it inaccurate by default.
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

	/**
	 * The value {@code config.xml} declares as this global property's default — a path inside the
	 * application data directory that the module never creates, so an install that has configured
	 * nothing falls back to the bundled dataset. Held as a constant because that is the difference
	 * between an untouched default (fine, and silent) and an operator naming a file that was then not
	 * read (issue #156, which is loud): see {@code DrugReferenceValidity}. Pinned against
	 * {@code config.xml} by {@code GlobalPropertyDefaultsTest} — a drift here would silently make every
	 * install loud or every misconfiguration silent.
	 *
	 * <p>It is the upstream release's OWN filename, and that is what makes a knowledge-base refresh a
	 * file copy rather than a configuration change: dropping a newer {@code ddi_knowledge_base.json}
	 * from the openmrs-ddi-knowledge-base project into {@code <appdata>/chartsearchai/} is enough for it
	 * to be read in place of the bundled one, with no global property to edit and so no chance of the
	 * path and the format disagreeing. Do not rename it to match the bundled resource
	 * ({@code ddi-knowledge-base.json}): the two names are deliberately different, one naming what an
	 * operator downloads and the other what the module ships.
	 */
	public static final String DEFAULT_DRUG_REFERENCE_DATA_FILE_PATH = "chartsearchai/ddi_knowledge_base.json";

	/** Selects the drug-reference data adapter: {@code ddinter} (the default — the bundled DDInter
	 *  knowledge base), {@code json} (the curated seed) or {@code atc} (consume a WHO ATC
	 *  classification export by pointing dataFilePath at it). See ADR Decision 24. */
	public static final String GP_DRUG_REFERENCE_SOURCE_FORMAT = "chartsearchai.drugReference.sourceFormat";

	/**
	 * Value of {@link #GP_DRUG_REFERENCE_SOURCE_FORMAT} that selects the curated source — the NAME of a
	 * format, which is a different fact from {@link #DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT}'s "and it is
	 * the one in force when nobody chose". They were one constant, and the two uses only looked alike
	 * while the default happened to be {@code json}: anything naming the curated format through the
	 * default would start naming whatever the default became. <b>That has now happened</b> — the default
	 * is {@link #DRUG_REFERENCE_SOURCE_DDINTER} — so this is no longer a precaution: every remaining use
	 * of the default constant means "whatever is in force when nobody chose", and every site meaning the
	 * curated parser names it here. Its sibling formats each have their own name constant; this is the
	 * one that was missing.
	 */
	public static final String DRUG_REFERENCE_SOURCE_JSON = "json";

	/** Value of {@link #GP_DRUG_REFERENCE_SOURCE_FORMAT} that selects the ATC classification source. */
	public static final String DRUG_REFERENCE_SOURCE_ATC = "atc";

	/** Value of {@link #GP_DRUG_REFERENCE_SOURCE_FORMAT} that selects the DDInter-backed source
	 *  (structured drug-drug interactions with severity and mechanism, normalized to RxNorm and
	 *  cross-walked to CIEL). See ADR Decision 24 and the openmrs-ddi-knowledge-base data project. */
	public static final String DRUG_REFERENCE_SOURCE_DDINTER = "ddinter";

	/**
	 * The format in force when nobody chose, which since ADR Decision 36 is the DDInter knowledge base
	 * the module bundles: an install that switches {@link #GP_DRUG_REFERENCE_ENABLED} on and configures
	 * nothing else gets 2283 substances and ~295k severity-rated interaction pairs rather than the
	 * four-drug curated seed. What it does NOT get is dosing or hand-authored allergy/condition rules —
	 * DDInter publishes neither, so {@link #GP_DRUG_SAFETY_WARN_ON_DOSE_EXCESS} has nothing it can fire
	 * on under this default and an install needing dose ceilings selects
	 * {@link #DRUG_REFERENCE_SOURCE_JSON} (or points {@link #GP_DRUG_REFERENCE_DATA_FILE_PATH} at a
	 * dataset that carries them). Pinned, with the bound, by {@code ShippedDrugReferenceDefaultTest}.
	 *
	 * <p><b>An unrecognised value is not this.</b> A typo falls back to {@link #DRUG_REFERENCE_SOURCE_JSON}
	 * — see {@code DrugReferenceService.effectiveFormat}, whose fall-through has to name the parser
	 * {@code sourceFor} falls through to — so it is NOT the same thing as leaving the property unset, and
	 * an install that mistypes {@code ddinter} gets the curated parser applied to whatever
	 * {@code dataFilePath} names. That divergence is reported in both channels
	 * ({@code DrugReferenceValidity.configuredSourceFormatNotUsed}), which is the only reason it is safe
	 * to differ from the default at all.
	 */
	public static final String DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT = DRUG_REFERENCE_SOURCE_DDINTER;

	/** Path (relative to the OpenMRS application data directory) to the curated cross-reactivity
	 *  groups dataset, loaded alongside EITHER source format. When absent, the groups bundled on
	 *  the module classpath are used. Closes the ADR Decision 24 cross-branch boundary as data. */
	public static final String GP_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH =
			"chartsearchai.drugReference.crossReactivityGroupsFilePath";

	/** As {@link #DEFAULT_DRUG_REFERENCE_DATA_FILE_PATH}, for the groups dataset: the module never
	 *  creates this file either, so every untouched install serves the bundled groups. */
	public static final String DEFAULT_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH =
			"chartsearchai/cross-reactivity-groups.json";

	/** Patient-driven injection: inject the reference entries the patient's active orders resolve to —
	 *  an ATC-code hit OR the order's own display name, whichever the reference data answers
	 *  ({@code DrugReferenceService.findForActiveOrders}, issue #151) — scoped to the orders in a
	 *  family with the drug the question names. */
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

	/** Cross-check the drugs in play — those the question asks about and those the answer names on its own
	 *  authority — against the patient's allergies/conditions for contraindications, and the patient's own
	 *  active orders against those same records (issue #143), scoped to what the response is about:
	 *  either the drug or the recorded finding must be named by the question, the answer or a cited
	 *  record, with a medication-, allergy- or condition-domain question keeping the corresponding
	 *  list in scope. */
	public static final String GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS =
			"chartsearchai.drugSafety.warnOnContraindications";

	public static final boolean DEFAULT_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS = true;

	/** Minimum source-assigned severity ({@code unknown} &lt; {@code minor} &lt; {@code moderate} &lt;
	 *  {@code major}) a rule-based interaction must carry to raise a warning chip. Rules without a
	 *  severity (e.g. the curated seed's hand-authored rules) are always shown, as are class-based and
	 *  contraindication chips. {@code unknown} shows every rated rule; the default {@code minor}
	 *  filters exactly DDInter's Unknown-severity rows, which carry no mechanism text (14% of the
	 *  full knowledge base) and dilute the chips that matter. See issue #84. */
	public static final String GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY =
			"chartsearchai.drugSafety.minInteractionSeverity";

	public static final String DEFAULT_DRUG_SAFETY_MIN_INTERACTION_SEVERITY = "minor";

	/** Concept UUID (a kg-valued numeric concept) used to read the patient's most recent weight for
	 *  the weight-aware per-dose overdose check. The value {@link #DRUG_SAFETY_WEIGHT_CONCEPT_DISABLED}
	 *  disables the weight-aware arm; blank/absent falls back to the default like every other GP
	 *  (OpenMRS normalizes a blanked GP value to null, so blank cannot mean "off"). Default: the
	 *  reference (CIEL) "Weight (kg)" concept, 5089 — CIEL identifiers are the concept id padded
	 *  with 'A' to the standard 36-character uuid length (live-verified against a real CIEL
	 *  dictionary). */
	public static final String GP_DRUG_SAFETY_WEIGHT_CONCEPT_UUID = "chartsearchai.drugSafety.weightConceptUuid";

	public static final String DEFAULT_DRUG_SAFETY_WEIGHT_CONCEPT_UUID =
			"5089AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	/** Sentinel value of {@link #GP_DRUG_SAFETY_WEIGHT_CONCEPT_UUID} that disables the weight-aware
	 *  arm (case-insensitive). An explicit sentinel — not blank — because a blanked GP reads back as
	 *  null and is indistinguishable from an absent one without a privilege-gated object read. */
	public static final String DRUG_SAFETY_WEIGHT_CONCEPT_DISABLED = "none";

	/** Maximum age, in days, of a weight observation for it to drive the per-dose overdose check —
	 *  pediatric weight changes fast, and a stale (lower) weight would over-report mg/kg (a false
	 *  positive, the direction this feature never takes). */
	public static final String GP_DRUG_SAFETY_WEIGHT_MAX_AGE_DAYS = "chartsearchai.drugSafety.weightMaxAgeDays";

	public static final int DEFAULT_DRUG_SAFETY_WEIGHT_MAX_AGE_DAYS = 90;

	/** Most interaction chips one question may raise from a PAIRWISE arm — the question's own drugs
	 *  checked against each other, or the patient's active orders checked against each other. Both are
	 *  quadratic in a list this module does not control, and both feed the prompt as citable findings,
	 *  so the number is a clinical judgement a deployment makes (a polypharmacy review clinic may want
	 *  30, a triage screen 5) rather than one this module fixes at build time — issue #131. An
	 *  unparseable or non-positive value falls back to the default rather than disabling the cap; see
	 *  {@code DrugSafetyValidator.maxPairChips} for why a cap cannot simply be removed. */
	public static final String GP_DRUG_SAFETY_MAX_PAIR_CHIPS = "chartsearchai.drugSafety.maxPairChips";

	public static final int DEFAULT_DRUG_SAFETY_MAX_PAIR_CHIPS = 10;

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

	/**
	 * querystore's resource type for a prescription (its {@code DrugOrderRecordSerializer}
	 * contract). Chart evidence like any other querystore record — it is the patient's own order,
	 * not module-injected reference material — and distinct from
	 * {@link #RESOURCE_TYPE_ACTIVE_DRUG_ORDER}, which this module injects for an active order the
	 * retrieved chart carries no record of (issue #118).
	 *
	 * <p>Declared for issue #317, which added a fourth production reader — the chart builder, which
	 * scopes the order-currency mark to this type — to three that were already spelling the string as
	 * a literal: {@code QueryScopeRouter.typedSlice}'s MEDICATIONS and ORDERS slices, and
	 * {@code DrugReferenceInjector}, which filters the mappings it puts to the substantiation test.
	 * All four now read this constant, and the injector and the builder are the pair that MUST
	 * agree: the injector
	 * admits a record to that corpus only where the prose and the builder's own order read both leave
	 * it live, so a divergence would leave the #317 half of that AND looking at no records at all —
	 * silently, since a condition that never sees a drug-order mapping simply stops narrowing
	 * anything.
	 *
	 * <p>Being declared here puts it in
	 * {@code ChartSearchAiReferenceGroupTest}'s sweep, which forces a reference-group decision to be
	 * RECORDED for every declared type — that is a forcing function, not an obstacle, and the group
	 * it records ({@code chart}) is the one {@code referenceGroup} already returned for the bare
	 * string.
	 */
	public static final String RESOURCE_TYPE_DRUG_ORDER = "drug_order";

	/** Reference data, not patient data — injected by {@link org.openmrs.module.chartsearchai.reference.DrugReferenceInjector}. */
	public static final String RESOURCE_TYPE_DRUG_REFERENCE = "drug_reference";

	/**
	 * A finding the deterministic drug-safety layer derived from THIS patient's records plus the drug
	 * knowledge base, injected pre-answer so the answer can cite it instead of re-deriving it.
	 *
	 * <p>Distinct from {@link #RESOURCE_TYPE_DRUG_REFERENCE} because it is patient-specific: the
	 * system prompt tells the model that "Drug reference" records are NOT this patient's data, so
	 * folding a patient-specific conclusion into one would contradict the prompt. It is also not
	 * {@link #RESOURCE_TYPE_ALLERGY} or any other chart type — it is derived, with no chart row to
	 * navigate to, which is why it groups as reference material rather than chart evidence.
	 */
	public static final String RESOURCE_TYPE_SAFETY_FINDING = "safety_finding";

	/**
	 * One of the patient's ACTIVE drug orders, read from {@code OrderService}, that the serialized
	 * chart carries no drug-order record for — injected by
	 * {@link org.openmrs.module.chartsearchai.reference.DrugReferenceInjector} so the answer cannot
	 * deny a medication the drug-safety chips simultaneously name (issue #118).
	 *
	 * <p>Unlike {@link #RESOURCE_TYPE_DRUG_REFERENCE} and {@link #RESOURCE_TYPE_SAFETY_FINDING},
	 * which are module-supplied material, this is the patient's own record — the authoritative read
	 * of it — and it carries the real {@code Order} uuid, so it groups as
	 * {@link #REFERENCE_GROUP_CHART} evidence and stays navigable. Kept as its own type rather than
	 * borrowing querystore's {@code drug_order} so the reconciliation is visible on the wire: a
	 * reference of this type is the module reporting that the retrieved chart was incomplete.
	 */
	public static final String RESOURCE_TYPE_ACTIVE_DRUG_ORDER = "active_drug_order";

	/**
	 * Wire value of a serialized reference's {@code group}: a record retrieved from THIS
	 * patient's chart. Evidence about the patient, citable as such.
	 */
	public static final String REFERENCE_GROUP_CHART = "chart";

	/**
	 * Wire value of a serialized reference's {@code group}: module-supplied reference prose (a drug
	 * knowledge-base entry, or a finding derived from one), not a record about this patient. Kept
	 * visible precisely so a client can disclose that provenance rather than let it read as chart
	 * evidence. A citation in this group is additionally never grounding-verified as {@code true},
	 * being demote-only (see {@code CitationGroundingVerifier}) — a property of the GROUP since issue
	 * #122, which derived that gate from {@link ChartSearchAiUtils#referenceGroup} instead of from the
	 * {@code drug_reference} resource type. Keying it on the type is how
	 * {@link #RESOURCE_TYPE_SAFETY_FINDING} came to be reference-group yet graded as chart evidence.
	 *
	 * <p>{@link #RESOURCE_TYPE_ACTIVE_DRUG_ORDER} is injected but groups as
	 * {@link #REFERENCE_GROUP_CHART}, so it is graded normally (decided in #118: one drug asserted of
	 * this patient has no subject roles to swap, so a passing verdict is real assurance) — "the module
	 * injected it" is a different question from this group.
	 *
	 * <p>Since issue #201 the group decides the wire value outright: a citation in this group
	 * serializes {@code grounded: null} whatever the pass concluded. Demote-only had already ruled
	 * {@code true} out; what remained was a Tier-1 {@code false} meaning "this citation is not about
	 * that record", which is not what a chart citation's {@code false} means and which no client
	 * distinguished — so it is withheld too, in
	 * {@code ChartSearchAiRestController.groundedForWire}. The key stays present and null, which is
	 * this field's existing "unverified" value. Adding a type to this group therefore stops its
	 * citations being VERIFIED (they are still graded, demote-only) AND stops any verdict of theirs
	 * reaching a client. Since issue #284 it does one more thing, to OTHER citations: a chart
	 * citation whose claim rests on a record in this group has its own entailment negative withheld,
	 * so the blast radius of a type added here is not confined to that type's citations.
	 */
	public static final String REFERENCE_GROUP_REFERENCE = "reference";

	private ChartSearchAiConstants() {
	}
}
