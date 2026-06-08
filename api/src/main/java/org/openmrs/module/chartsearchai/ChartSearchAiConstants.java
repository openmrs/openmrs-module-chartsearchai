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

	public static final String GP_EMBEDDING_MODEL_FILE_PATH = "chartsearchai.embedding.modelFilePath";

	/**
	 * Optional: path to a separate query-encoder model for dual-encoder
	 * architectures (e.g. MedCPT). When set, queries are embedded with
	 * this model while records use the standard model. When not set,
	 * the standard model is used for both (single-encoder mode).
	 */
	public static final String GP_EMBEDDING_QUERY_MODEL_FILE_PATH = "chartsearchai.embedding.queryModelFilePath";

	public static final String GP_LLM_MODEL_FILE_PATH = "chartsearchai.llm.modelFilePath";

	public static final String GP_EMBEDDING_PRE_FILTER = "chartsearchai.embedding.preFilter";

	public static final String GP_EMBEDDING_VOCAB_FILE_PATH = "chartsearchai.embedding.vocabFilePath";

	public static final String GP_EMBEDDING_TOP_K = "chartsearchai.embedding.topK";

	public static final int DEFAULT_RETRIEVAL_TOP_K = 10;

	public static final String GP_EMBEDDING_SIMILARITY_RATIO = "chartsearchai.embedding.similarityRatio";

	public static final double DEFAULT_SIMILARITY_RATIO = 0.80;

	public static final String GP_EMBEDDING_SCORE_GAP_MULTIPLIER = "chartsearchai.embedding.scoreGapMultiplier";

	public static final double DEFAULT_SCORE_GAP_MULTIPLIER = 2.5;

	public static final String GP_EMBEDDING_KEYWORD_WEIGHT = "chartsearchai.embedding.keywordWeight";

	public static final double DEFAULT_KEYWORD_WEIGHT = 0.3;

	public static final String GP_EMBEDDING_TYPE_BOOST_FACTOR = "chartsearchai.embedding.typeBoostFactor";

	public static final double DEFAULT_TYPE_BOOST_FACTOR = 1.0;

	public static final String GP_EMBEDDING_QUERY_PREFIX = "chartsearchai.embedding.queryPrefix";

	public static final String DEFAULT_QUERY_EMBEDDING_PREFIX = "";

	public static final String GP_EMBEDDING_MAX_SEQUENCE_LENGTH = "chartsearchai.embedding.maxSequenceLength";

	public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;

	public static final int ADAPTIVE_MIN_RECORDS = 2;

	public static final double ABSOLUTE_SIMILARITY_FLOOR = 0.25;

	/** Minimum z-score for the tight-cluster bypass of the zero-keyword
	 * z-score gate. When the ratio floor produces a small cluster AND
	 * the initial z-score exceeds this threshold AND the max semantic
	 * score is comfortably above the absolute floor, the cluster is
	 * considered validated and the z-score gate is bypassed. */
	public static final double FLOOR_RESCUE_MIN_Z_SCORE = 2.0;

	/** Minimum number of records required for the z-score gate to
	 * activate. Below this threshold, the score distribution has too
	 * few data points for the z-score to be statistically meaningful. */
	public static final int MIN_RECORDS_FOR_Z_SCORE = 30;

	public static final String GP_EMBEDDING_MIN_SCORE_GAP = "chartsearchai.embedding.minScoreGap";

	/**
	 * Minimum absolute gap between consecutive scores required for the adaptive
	 * cutoff detector to trigger. Prevents premature cutting when a relatively
	 * large gap (compared to a tight cluster's running average) is still small
	 * in absolute terms — e.g. a 0.07 gap inside a cluster that spans from
	 * 0.60 down to 0.30 should not be treated as a relevance boundary.
	 */
	public static final double DEFAULT_MIN_SCORE_GAP = 0.10;

	/** Absolute coherence floor below which a candidate is a true outlier.
	 * If a candidate flagged for removal by the coherence gap detector has
	 * average pairwise coherence at or above this value, the cut is
	 * suppressed — the candidate genuinely belongs to the same topic.
	 * Prevents duplicate/near-duplicate embeddings (identical text → cosine
	 * 1.0 between them) from inflating the coherence range and making a
	 * same-topic record look like an outlier. Empirically: same-topic
	 * candidates removed incorrectly have coherence ~0.91+, while true
	 * cross-topic outliers have coherence ~0.49-. Value of 0.70 sits
	 * well between these ranges. */
	public static final double COHERENCE_SAME_TOPIC_FLOOR = 0.70;

	/** Minimum cosine similarity between a keyword-matched record and
	 * the semantic core (non-keyword records found by gap detection)
	 * for the keyword match to be considered topically relevant.
	 * Keyword reps below this threshold are incidental matches —
	 * e.g. SpO2 matching "blood" in "blood oxygen saturation" when
	 * the query is about blood problems. Value of 0.55 sits between
	 * same-topic cross-type pairs (~0.65-0.80) and cross-topic pairs
	 * (~0.40-0.50). */
	public static final double SEMANTIC_CORE_MIN_COSINE = 0.55;

	/** Fraction of the semantic core's minimum score used as the
	 * relevance floor for expansion candidates. Semantic score
	 * (query cosine) is the only signal where genuinely relevant
	 * records consistently outscore template-similar noise — all
	 * inter-record cosine metrics (max, avg, min to core) fail
	 * because the embedding model conflates record-type similarity
	 * with content similarity. Value of 0.80 means expansion
	 * candidates must be within 20% of the core's relevance level. */
	public static final double SEMANTIC_CORE_SCORE_RATIO = 0.80;


	/** Fraction of max coherence used as the adaptive minimum gap for
	 * coherence outlier detection. */
	public static final double COHERENCE_ADAPTIVE_GAP_RATIO = 0.20;

	/** Corpus size above which the selective-keyword rescue extends from
	 * the absolute floor of {@link #ADAPTIVE_MIN_RECORDS} to a fractional
	 * threshold. Smaller charts (typical synthetic eval datasets ~150
	 * records) keep the absolute rule because their partial-keyword
	 * fractions overlap with the eval-empty cases that the rescue must
	 * not block. Larger charts (live patients with many concept entries)
	 * have more headroom: a "selective" keyword set at 6/462 is 1.3 %,
	 * which is meaningfully smaller than what eval cases produce, so a
	 * larger absolute count still represents a rare keyword anchor. */
	public static final int LARGE_CORPUS_SELECTIVE_RESCUE_MIN = 200;

	/** Fraction-of-corpus threshold for the selective-keyword rescue on
	 * large charts (corpus &gt; {@link #LARGE_CORPUS_SELECTIVE_RESCUE_MIN}).
	 * Records matching a keyword that appears in &le; this fraction of
	 * the corpus are considered selective enough to rescue from the
	 * partial-match semantic floor. Calibrated to catch Richard-class
	 * broad queries (musculoskeletal injuries: 6/462 = 1.3 %; tests
	 * ordered: 24/462 = 5.2 %) without affecting smaller eval datasets
	 * (which the size gate excludes). */
	public static final double LARGE_CORPUS_SELECTIVE_KW_FRACTION = 0.06;

	/** Maximum number of keyword terms a query can have for the concept-
	 * similarity expansion to consider it. Longer queries are typically
	 * multi-concept and unsafe to collapse onto a single chart concept's
	 * tokens. */
	public static final int CONCEPT_EXPANSION_MAX_KW_TERMS = 4;

	/** Minimum length of a kwTerm for it to count as a vocabulary-overlap
	 * anchor in the moderate-similarity guard. Two-letter tokens (e.g.
	 * "is", "of") are too generic to discriminate. */
	public static final int CONCEPT_EXPANSION_MIN_OVERLAP_LENGTH = 3;

	/** Fraction of the corpus above which a concept token is considered
	 * too common to anchor a keyword search after expansion. A token
	 * appearing in &gt;20 % of records is generic (e.g. "method" on a
	 * chart with many frequency obs). Threshold has a floor of 1 so
	 * very small corpora do not lose every token. */
	public static final double CONCEPT_EXPANSION_IDF_FRACTION = 0.20;

	/** Reference candidate count for coherence gap ratio calibration.
	 * {@link #COHERENCE_ADAPTIVE_GAP_RATIO} was calibrated for candidate
	 * sets of this size. For smaller sets, the gap ratio is scaled up by
	 * √((REFERENCE−1) / (n−1)) to account for the higher variance in
	 * coherence estimates (fewer pairwise comparisons per candidate).
	 * Value of 5 means: for n=4 the scale is modest (√(4/3)≈1.15);
	 * for n≥5 the scale is 1.0 (no inflation). The coherence filter
	 * is not called for n&lt;4 (too few pairwise comparisons). */
	public static final int COHERENCE_REFERENCE_N = 5;


	public static final String GP_EMBEDDING_GAP_VALIDATION_COSINE_THRESHOLD =
			"chartsearchai.embedding.gapValidationCosineThreshold";

	/** Cosine similarity threshold for validating whether a gap in the
	 * score distribution is intra-topic (records on both sides belong to
	 * the same broad topic, e.g. different vital sign types) or
	 * inter-topic (a real relevance boundary). When the average cosine
	 * between the records just above and below the gap meets or exceeds
	 * this threshold, the gap is considered intra-topic and the second-pass
	 * cut is skipped. Value of 0.47 was determined empirically: medical
	 * records within the same broad category (all vitals) typically have
	 * inter-record cosine >= 0.47, while cross-category pairs (vital vs
	 * encounter note or condition) are typically below. */
	public static final double DEFAULT_GAP_VALIDATION_COSINE_THRESHOLD = 0.47;

	public static final String GP_RETRIEVAL_PIPELINE = "chartsearchai.retrieval.pipeline";

	public static final String PIPELINE_EMBEDDING = "embedding";

	public static final String PIPELINE_LUCENE = "lucene";

	public static final String PIPELINE_ELASTICSEARCH = "elasticsearch";

	public static final String PIPELINE_HYBRID = "hybrid";

	public static final String GP_QUERYSTORE_ENABLED = "chartsearchai.querystore.enabled";

	/** Separate from {@link #GP_EMBEDDING_TOP_K} so the querystore path
	 *  can be tuned independently of the legacy pipelines. */
	public static final String GP_QUERYSTORE_TOP_K = "chartsearchai.querystore.topK";

	public static final int DEFAULT_QUERYSTORE_TOP_K = 30;

	/** Fetch multiplier for the Elasticsearch pipeline. The ES pipeline
	 * requests {@code topK * ES_FETCH_MULTIPLIER} results from the search
	 * engine, then passes all of them through the filter pipeline. This
	 * gives keyword rescue enough candidates to find all matching records
	 * — without it, RRF's fixed window would limit keyword rescue to
	 * only the top-K results, missing records that the embedding pipeline
	 * (which scores ALL records) would find. */
	public static final int ES_FETCH_MULTIPLIER = 10;

	/** RRF window size — number of top results from each retriever
	 * considered during rank fusion. Matches the Elasticsearch pipeline's
	 * setting so both pipelines produce comparable results. */
	public static final int RRF_RANK_WINDOW_SIZE = 100;

	/** RRF rank constant (k) — controls how quickly lower-ranked results
	 * lose influence: score = 1 / (k + rank). Higher k produces more
	 * uniform weighting across ranks. */
	public static final int RRF_RANK_CONSTANT = 60;

	/** Minimum cosine similarity for kNN results to be considered relevant.
	 * Uses the same value as {@link #ABSOLUTE_SIMILARITY_FLOOR}
	 * so both pipelines use the same relevance bar. */
	public static final double KNN_MIN_SIMILARITY = ABSOLUTE_SIMILARITY_FLOOR;

	/** Z-score threshold for the kNN fallback gate. When BM25 returns no
	 * keyword matches, kNN results must score this many standard deviations
	 * above the mean of all patient scores to be considered relevant. */
	public static final double KNN_FALLBACK_Z_SCORE = 2.5;

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

	public static final String GP_RATE_LIMIT_PER_MINUTE = "chartsearchai.rateLimitPerMinute";

	public static final int DEFAULT_RATE_LIMIT_PER_MINUTE = 10;

	public static final String GP_CACHE_TTL_MINUTES = "chartsearchai.cacheTtlMinutes";

	public static final int DEFAULT_CACHE_TTL_MINUTES = 0;

	public static final int DEFAULT_CACHE_MAX_SIZE = 100;

	public static final String GP_WARMUP_ENABLED = "chartsearchai.warmupEnabled";

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
	 * grounding verifier reuses when {@code chartsearchai.querystore.enabled=true}.
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
	 * Tier-1 cosine verdict is confirmed by a Tier-2 entailment check: a short
	 * yes/no LLM call asking whether the cited record actually supports the
	 * answer sentence. This is what catches high-overlap-but-false citations
	 * ("patient has X [5]" where record 5 says a relative had X, or the record
	 * negates X) that cosine similarity cannot separate. Costs one extra LLM
	 * call per cited reference, so it is a separate opt-in from the cheap
	 * Tier-1 pass. Default {@code false}. See {@code CitationGroundingVerifier}.
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

	// Resource type identifiers used in embeddings and citations
	public static final String RESOURCE_TYPE_OBS = "obs";

	public static final String RESOURCE_TYPE_CONDITION = "condition";

	public static final String RESOURCE_TYPE_ALLERGY = "allergy";

	public static final String RESOURCE_TYPE_DIAGNOSIS = "diagnosis";

	public static final String RESOURCE_TYPE_ORDER = "order";

	public static final String RESOURCE_TYPE_PROGRAM = "program";

	public static final String RESOURCE_TYPE_MEDICATION_DISPENSE = "medication_dispense";

	private ChartSearchAiConstants() {
	}
}
