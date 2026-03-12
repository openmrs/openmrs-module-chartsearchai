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
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.AfterReturningAdvice;

/**
 * AOP advice that triggers embedding indexing after encounters are saved, voided, or unvoided.
 * On save, does an incremental index of the encounter. On void/unvoid, does a full patient
 * re-index to remove orphaned embeddings from voided encounters.
 * Only active when the search mode is set to {@code embedding}.
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

		String mode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SEARCH_MODE);
		if (!ChartSearchAiConstants.SEARCH_MODE_EMBEDDING.equalsIgnoreCase(mode)) {
			return;
		}

		if ("saveEncounter".equals(methodName) && returnValue instanceof Encounter) {
			Encounter encounter = (Encounter) returnValue;
			try {
				EmbeddingIndexer indexer = Context.getRegisteredComponent(
						"embeddingIndexer", EmbeddingIndexer.class);
				indexer.indexEncounter(encounter);
			}
			catch (Exception e) {
				log.error("Failed to index encounter [id={}]", encounter.getEncounterId(), e);
			}
		} else if (REINDEX_METHODS.contains(methodName)) {
			Patient patient = getPatientFromArgs(returnValue, args);
			if (patient != null) {
				try {
					EmbeddingIndexer indexer = Context.getRegisteredComponent(
							"embeddingIndexer", EmbeddingIndexer.class);
					indexer.indexPatient(patient);
				}
				catch (Exception e) {
					log.error("Failed to re-index patient after {} call", methodName, e);
				}
			}
		}
	}

	private Patient getPatientFromArgs(Object returnValue, Object[] args) {
		if (returnValue instanceof Encounter) {
			return ((Encounter) returnValue).getPatient();
		}
		if (args != null && args.length > 0 && args[0] instanceof Encounter) {
			return ((Encounter) args[0]).getPatient();
		}
		return null;
	}
}
