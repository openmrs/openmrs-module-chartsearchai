/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Delegates chart search queries to the LLM inference service and provides an
 * optional answer cache.
 *
 * <p>Caches answers by (patient UUID, question) with a configurable TTL to avoid
 * recomputing identical queries. Cache TTL is controlled by the
 * {@code chartsearchai.cacheTtlMinutes} global property (default 0 = disabled).</p>
 */
@Service("chartSearchAi.chartSearchServiceRouter")
public class ChartSearchServiceRouter implements ChartSearchService {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchServiceRouter.class);

	@Autowired
	@Qualifier("chartSearchAi.llmInferenceService")
	private ChartSearchService llmService;

	private final Map<String, CachedAnswer> cache = new LinkedHashMap<String, CachedAnswer>(
			ChartSearchAiConstants.DEFAULT_CACHE_MAX_SIZE + 1, 0.75f, true) {

		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, CachedAnswer> eldest) {
			return size() > ChartSearchAiConstants.DEFAULT_CACHE_MAX_SIZE;
		}
	};

	@Override
	public ChartAnswer search(Patient patient, String question) {
		int ttlMinutes = getCacheTtlMinutes();

		if (ttlMinutes > 0) {
			String cacheKey = buildCacheKey(patient, question);
			ChartAnswer cached = getCachedAnswer(cacheKey, ttlMinutes);
			if (cached != null) {
				log.debug("Cache hit for patient [id={}]", patient.getPatientId());
				return cached;
			}

			ChartAnswer answer = llmService.search(patient, question);
			putCache(cacheKey, answer);
			return answer;
		}

		return llmService.search(patient, question);
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer) {
		return searchStreaming(patient, question, tokenConsumer, chunk -> { });
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer) {
		return searchStreaming(patient, question, tokenConsumer, reasoningConsumer, refs -> { });
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
			Consumer<List<RecordReference>> citationsConsumer) {
		return searchStreaming(patient, question, tokenConsumer, reasoningConsumer,
				citationsConsumer, ungrounded -> { });
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
			Consumer<List<RecordReference>> citationsConsumer,
			Consumer<ChartAnswer> ungroundedAnswerConsumer) {
		int ttlMinutes = getCacheTtlMinutes();

		if (ttlMinutes > 0) {
			String cacheKey = buildCacheKey(patient, question);
			ChartAnswer cached = getCachedAnswer(cacheKey, ttlMinutes);
			if (cached != null) {
				log.debug("Cache hit for patient [id={}] (streaming)", patient.getPatientId());
				// Cache hit: the answer is returned instantly with no LLM call, so there is no
				// live reasoning to stream — only replay the cached answer. Its references are
				// already grounded, so also surface them on the citations channel for protocol
				// parity with the live path. The ungrounded-answer consumer is deliberately NOT
				// fired: its contract is "an answer exists whose grounding is still pending",
				// and a cached answer is already final — the caller treats the return value as
				// the single result (see the interface javadoc).
				tokenConsumer.accept(cached.getAnswer());
				citationsConsumer.accept(cached.getReferences());
				return cached;
			}

			ChartAnswer answer = llmService.searchStreaming(patient, question, tokenConsumer,
					reasoningConsumer, citationsConsumer, ungroundedAnswerConsumer);
			putCache(cacheKey, answer);
			return answer;
		}

		return llmService.searchStreaming(patient, question, tokenConsumer, reasoningConsumer,
				citationsConsumer, ungroundedAnswerConsumer);
	}

	/** Test seam: production wires the inference service via {@link Autowired}. */
	void setLlmService(ChartSearchService llmService) {
		this.llmService = llmService;
	}

	@Override
	public void warmup(Patient patient) {
		llmService.warmup(patient);
	}

	/**
	 * Test seam: resolves a global property. Overridable so {@link #buildCacheKey} can be unit-tested
	 * (which GPs are folded into the key) without standing up an OpenMRS context.
	 */
	protected String gp(String property, String defaultValue) {
		return Context.getAdministrationService().getGlobalProperty(property, defaultValue);
	}

	protected String buildCacheKey(Patient patient, String question) {
		String preFilter = gp(ChartSearchAiConstants.GP_EMBEDDING_PRE_FILTER, "false");
		String pipeline = gp(ChartSearchAiConstants.GP_RETRIEVAL_PIPELINE,
				ChartSearchAiConstants.PIPELINE_EMBEDDING);
		String topK = gp(ChartSearchAiConstants.GP_EMBEDDING_TOP_K, "");
		String similarityRatio = gp(ChartSearchAiConstants.GP_EMBEDDING_SIMILARITY_RATIO, "");
		String keywordWeight = gp(ChartSearchAiConstants.GP_EMBEDDING_KEYWORD_WEIGHT, "");
		String scoreGapMultiplier = gp(ChartSearchAiConstants.GP_EMBEDDING_SCORE_GAP_MULTIPLIER, "");
		String minScoreGap = gp(ChartSearchAiConstants.GP_EMBEDDING_MIN_SCORE_GAP, "");
		String gapValidationCosine = gp(
				ChartSearchAiConstants.GP_EMBEDDING_GAP_VALIDATION_COSINE_THRESHOLD, "");
		// Grounding GPs change the answer's per-citation `grounded` verdict, so
		// they must be part of the key — otherwise toggling grounding (or its
		// floor / entailment flag) while caching is on would serve answers whose
		// verdicts no longer match the current configuration.
		String grounding = gp(ChartSearchAiConstants.GP_GROUNDING_ENABLED, "");
		String groundingMinCosine = gp(ChartSearchAiConstants.GP_GROUNDING_MIN_COSINE, "");
		String groundingEntailment = gp(ChartSearchAiConstants.GP_GROUNDING_ENTAILMENT_ENABLED, "");
		return patient.getUuid() + "::" + preFilter.trim().toLowerCase()
				+ "::" + pipeline.trim().toLowerCase()
				+ "::" + topK.trim()
				+ "::" + similarityRatio.trim()
				+ "::" + keywordWeight.trim()
				+ "::" + scoreGapMultiplier.trim()
				+ "::" + minScoreGap.trim()
				+ "::" + gapValidationCosine.trim()
				+ "::" + grounding.trim().toLowerCase()
				+ "::" + groundingMinCosine.trim()
				+ "::" + groundingEntailment.trim().toLowerCase()
				+ "::" + question.trim().toLowerCase();
	}

	private synchronized ChartAnswer getCachedAnswer(String key, int ttlMinutes) {
		CachedAnswer entry = cache.get(key);
		if (entry == null) {
			return null;
		}
		long ageMs = System.currentTimeMillis() - entry.timestamp;
		if (ageMs > ttlMinutes * 60L * 1000L) {
			cache.remove(key);
			return null;
		}
		return entry.answer;
	}

	private synchronized void putCache(String key, ChartAnswer answer) {
		evictExpired();
		cache.put(key, new CachedAnswer(answer, System.currentTimeMillis()));
	}

	private int evictionCounter;

	private static final int EVICTION_INTERVAL = 10;

	/** Evicts expired entries periodically (every EVICTION_INTERVAL puts) to avoid scanning
	 *  the entire cache on every put. Must be called while holding the monitor. */
	private void evictExpired() {
		if (++evictionCounter < EVICTION_INTERVAL) {
			return;
		}
		evictionCounter = 0;
		int ttlMinutes = getCacheTtlMinutes();
		if (ttlMinutes <= 0) {
			return;
		}
		long now = System.currentTimeMillis();
		long ttlMs = ttlMinutes * 60L * 1000L;
		Iterator<Map.Entry<String, CachedAnswer>> it = cache.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, CachedAnswer> entry = it.next();
			if (now - entry.getValue().timestamp > ttlMs) {
				it.remove();
			}
		}
	}

	protected int getCacheTtlMinutes() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_CACHE_TTL_MINUTES);
		if (value != null && !value.trim().isEmpty()) {
			try {
				return Integer.parseInt(value.trim());
			}
			catch (NumberFormatException e) {
				log.warn("Invalid cache TTL value '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_CACHE_TTL_MINUTES;
	}

	private static class CachedAnswer {

		final ChartAnswer answer;

		final long timestamp;

		CachedAnswer(ChartAnswer answer, long timestamp) {
			this.answer = answer;
			this.timestamp = timestamp;
		}
	}
}
