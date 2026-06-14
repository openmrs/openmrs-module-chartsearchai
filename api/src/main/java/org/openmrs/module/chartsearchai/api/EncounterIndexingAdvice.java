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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.openmrs.Encounter;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.AfterReturningAdvice;

/**
 * AOP advice that, after encounters are saved, voided, or unvoided, invalidates the patient's
 * cached answers and (when embedding indexing is active) re-indexes their embeddings. On save,
 * does an incremental index of the encounter; on void/unvoid, a full patient re-index to remove
 * orphaned embeddings from voided encounters.
 *
 * <p>Embedding indexing runs only when {@code chartsearchai.embedding.preFilter} is {@code true}
 * and querystore is not the active backend. Answer-cache invalidation is independent of both — it
 * runs whenever the cache is on ({@code chartsearchai.cacheTtlMinutes > 0}), regardless of
 * retrieval backend, so an edit never leaves a stale cached answer. See {@link IndexingHelper}.</p>
 *
 * <p>Registered in config.xml as advice on {@code org.openmrs.api.EncounterService}.</p>
 */
public class EncounterIndexingAdvice implements AfterReturningAdvice {

	private static final Logger log = LoggerFactory.getLogger(EncounterIndexingAdvice.class);

	private static final Set<String> REINDEX_METHODS = new HashSet<String>(
			Arrays.asList("voidEncounter", "unvoidEncounter", "purgeEncounter"));

	@Override
	public void afterReturning(Object returnValue, Method method, Object[] args, Object target) {
		String methodName = method.getName();
		boolean isSave = "saveEncounter".equals(methodName);

		if (!isSave && !REINDEX_METHODS.contains(methodName)) {
			return;
		}

		// Indexing is gated by querystore/preFilter; the answer cache must be invalidated on every
		// write regardless of backend (see IndexingHelper.invalidateAnswerCache).
		boolean indexingActive = !IndexingHelper.isDisabledByQueryStore()
				&& IndexingHelper.isPreFilterEnabled();
		boolean cacheActive = IndexingHelper.isAnswerCacheEnabled();
		if (!indexingActive && !cacheActive) {
			return;
		}

		if (isSave) {
			if (!(returnValue instanceof Encounter)) {
				return;
			}
			Encounter encounter = (Encounter) returnValue;
			Patient patient = encounter.getPatient();
			if (cacheActive) {
				IndexingHelper.invalidateAnswerCache(patient);
			}
			if (indexingActive) {
				try {
					EmbeddingIndexer indexer = Context.getRegisteredComponent(
							"embeddingIndexer", EmbeddingIndexer.class);
					indexer.indexEncounter(encounter);
				}
				catch (Exception e) {
					log.error("Failed to index encounter [id={}]", encounter.getEncounterId(), e);
				}
				IndexingHelper.reindexOtherPipelines(patient);
			}
		} else {
			// void/unvoid/purge: the patient comes from the args, not a returned Encounter.
			Patient patient = getPatientFromArgs(returnValue, args);
			if (patient == null) {
				return;
			}
			if (cacheActive) {
				IndexingHelper.invalidateAnswerCache(patient);
			}
			if (indexingActive) {
				try {
					EmbeddingIndexer indexer = Context.getRegisteredComponent(
							"embeddingIndexer", EmbeddingIndexer.class);
					indexer.indexPatient(patient);
				}
				catch (Exception e) {
					log.error("Failed to re-index patient after {} call", methodName, e);
				}
				IndexingHelper.reindexOtherPipelines(patient);
			}
		}
	}

	Patient getPatientFromArgs(Object returnValue, Object[] args) {
		if (returnValue instanceof Encounter) {
			return ((Encounter) returnValue).getPatient();
		}
		if (args != null && args.length > 0 && args[0] instanceof Encounter) {
			return ((Encounter) args[0]).getPatient();
		}
		return null;
	}
}
