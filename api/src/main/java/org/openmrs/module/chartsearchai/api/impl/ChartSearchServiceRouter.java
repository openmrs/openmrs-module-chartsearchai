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
 * Routes chart search queries to either the direct LLM inference service or the
 * embedding-based search service based on the {@code chartsearchai.searchMode} global
 * property. Defaults to {@code llm} if the property is not set.
 *
 * <p>Caches answers by (patient UUID, question) with a configurable TTL to avoid
 * recomputing identical queries. Cache TTL is controlled by the
 * {@code chartsearchai.cacheTtlMinutes} global property (default 5 minutes, 0 to disable).</p>
 */
@Service("chartSearchAi.chartSearchServiceRouter")
public class ChartSearchServiceRouter implements ChartSearchService {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchServiceRouter.class);

	@Autowired
	@Qualifier("chartSearchAi.llmInferenceService")
	private ChartSearchService llmService;

	@Autowired
	@Qualifier("chartSearchAi.embeddingSearchService")
	private ChartSearchService embeddingService;

	private final Map<String, CachedAnswer> cache = new LinkedHashMap<String, CachedAnswer>(
			ChartSearchAiConstants.DEFAULT_CACHE_MAX_SIZE + 1, 0.75f, true) {

		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, CachedAnswer> eldest) {
			return size() > ChartSearchAiConstants.DEFAULT_CACHE_MAX_SIZE;
		}
	};

	@Override
	public ChartAnswer ask(Patient patient, String question) {
		String cacheKey = buildCacheKey(patient, question);
		int ttlMinutes = getCacheTtlMinutes();

		if (ttlMinutes > 0) {
			ChartAnswer cached = getCachedAnswer(cacheKey, ttlMinutes);
			if (cached != null) {
				log.debug("Cache hit for patient {} question", patient.getUuid());
				return cached;
			}
		}

		ChartAnswer answer = getDelegate().ask(patient, question);

		if (ttlMinutes > 0) {
			putCache(cacheKey, answer);
		}

		return answer;
	}

	@Override
	public ChartAnswer askStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer) {
		String cacheKey = buildCacheKey(patient, question);
		int ttlMinutes = getCacheTtlMinutes();

		if (ttlMinutes > 0) {
			ChartAnswer cached = getCachedAnswer(cacheKey, ttlMinutes);
			if (cached != null) {
				log.debug("Cache hit for patient {} question (streaming)", patient.getUuid());
				tokenConsumer.accept(cached.getAnswer());
				return cached;
			}
		}

		ChartAnswer answer = getDelegate().askStreaming(patient, question, tokenConsumer);

		if (ttlMinutes > 0) {
			putCache(cacheKey, answer);
		}

		return answer;
	}

	private ChartSearchService getDelegate() {
		String mode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SEARCH_MODE);

		if (ChartSearchAiConstants.SEARCH_MODE_EMBEDDING.equalsIgnoreCase(mode)) {
			log.debug("Using embedding search mode");
			return embeddingService;
		}

		log.debug("Using LLM inference search mode");
		return llmService;
	}

	private String buildCacheKey(Patient patient, String question) {
		return patient.getUuid() + "::" + question.trim().toLowerCase();
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

	private void evictExpired() {
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

	private int getCacheTtlMinutes() {
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
