/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared helper used by the AOP advice classes after patient data changes. Two independent
 * concerns: (1) invalidate the patient's cached answers ({@link #invalidateAnswerCache}), which
 * runs whenever the answer cache is on regardless of retrieval backend; and (2) re-index or delete
 * Lucene and Elasticsearch pipeline data ({@link #reindexOtherPipelines} /
 * {@link #deleteOtherPipelineIndexes}), gated by preFilter/querystore — each indexer's
 * {@code reindexIfActive}/{@code deleteIfActive} method checks whether its pipeline is currently
 * selected before doing any work.
 */
final class IndexingHelper {

	private static final Logger log = LoggerFactory.getLogger(IndexingHelper.class);

	/** When the querystore migration flag is on, querystore owns indexing
	 *  and chartsearchai's AOP + backfill become no-ops to avoid double
	 *  indexing. */
	static boolean isDisabledByQueryStore() {
		return ChartSearchAiUtils.isQueryStoreEnabled();
	}

	/** Whether {@code chartsearchai.embedding.preFilter} is on. The AOP advice classes gate their
	 *  <em>embedding-indexing</em> work behind this (their answer-cache invalidation is NOT gated —
	 *  see {@link #isAnswerCacheEnabled()}), as does {@code EmbeddingIndexTask}; centralising the
	 *  check avoids the duplication that would silently diverge if the GP semantics ever change
	 *  (e.g. accepting {@code "0"} / {@code "disabled"}, or adding a system-property override).
	 *  Mirrors the {@link #isDisabledByQueryStore()} sibling. */
	static boolean isPreFilterEnabled() {
		String mode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_EMBEDDING_PRE_FILTER, "false");
		return !"false".equalsIgnoreCase(mode.trim());
	}

	/** The router bean that owns the answer cache, resolved through the {@link ChartSearchService}
	 *  interface (the same way {@code WarmupExecutor} and the REST controller reference it). Looked
	 *  up lazily, not injected, because the AOP advice classes are plain POJOs instantiated by
	 *  OpenMRS, not Spring-managed beans. */
	private static ChartSearchService answerCache() {
		return Context.getRegisteredComponent(
				"chartSearchAi.chartSearchServiceRouter", ChartSearchService.class);
	}

	/**
	 * Whether the answer cache is on (TTL &gt; 0). Checked by the AOP write-advice <em>before</em>
	 * resolving the affected patient, so the default cache-off hot path skips patient extraction.
	 * Independent of {@link #isDisabledByQueryStore()} / {@link #isPreFilterEnabled()}: the answer
	 * cache must be invalidated on every chart write regardless of which retrieval backend is active.
	 */
	static boolean isAnswerCacheEnabled() {
		try {
			ChartSearchService cache = answerCache();
			return cache != null && cache.isCacheEnabled();
		}
		catch (Exception e) {
			log.error("Failed to read answer-cache state; assuming off", e);
			return false;
		}
	}

	/**
	 * Evicts a patient's cached answers after a write to their chart. Unlike
	 * {@link #reindexOtherPipelines(Patient)} this is <em>not</em> gated by the querystore or
	 * preFilter flags — the answer cache sits in front of every retrieval backend, so a stale entry
	 * would survive an edit no matter which backend produced it.
	 */
	static void invalidateAnswerCache(Patient patient) {
		if (patient == null) {
			return;
		}
		try {
			ChartSearchService cache = answerCache();
			if (cache != null) {
				cache.invalidatePatient(patient.getUuid());
			}
		}
		catch (Exception e) {
			log.error("Failed to invalidate answer cache for patient [id={}]",
					patient.getPatientId(), e);
		}
	}

	static void reindexOtherPipelines(Patient patient) {
		if (patient == null) {
			return;
		}
		try {
			LuceneIndexer luceneIndexer = Context.getRegisteredComponent(
					"luceneIndexer", LuceneIndexer.class);
			if (luceneIndexer != null) {
				luceneIndexer.reindexIfActive(patient);
			}
		}
		catch (Exception e) {
			log.error("Failed to re-index Lucene for patient [id={}]",
					patient.getPatientId(), e);
		}
		try {
			ElasticsearchIndexer esIndexer = Context.getRegisteredComponent(
					"elasticsearchIndexer", ElasticsearchIndexer.class);
			if (esIndexer != null) {
				esIndexer.reindexIfActive(patient);
			}
		}
		catch (Exception e) {
			log.error("Failed to re-index Elasticsearch for patient [id={}]",
					patient.getPatientId(), e);
		}
	}

	static void deleteOtherPipelineIndexes(Patient patient) {
		if (patient == null) {
			return;
		}
		try {
			LuceneIndexer luceneIndexer = Context.getRegisteredComponent(
					"luceneIndexer", LuceneIndexer.class);
			if (luceneIndexer != null) {
				luceneIndexer.deleteIfActive(patient);
			}
		}
		catch (Exception e) {
			log.error("Failed to delete Lucene index for patient [id={}]",
					patient.getPatientId(), e);
		}
		try {
			ElasticsearchIndexer esIndexer = Context.getRegisteredComponent(
					"elasticsearchIndexer", ElasticsearchIndexer.class);
			if (esIndexer != null) {
				esIndexer.deleteIfActive(patient);
			}
		}
		catch (Exception e) {
			log.error("Failed to delete Elasticsearch index for patient [id={}]",
					patient.getPatientId(), e);
		}
	}

	private IndexingHelper() {
	}
}
