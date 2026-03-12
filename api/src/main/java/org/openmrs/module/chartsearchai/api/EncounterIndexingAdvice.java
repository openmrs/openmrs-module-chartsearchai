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

import org.openmrs.Encounter;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.AfterReturningAdvice;

/**
 * AOP advice that triggers incremental embedding indexing after an encounter is saved.
 * Only active when the search mode is set to {@code embedding}.
 *
 * <p>Registered in config.xml as advice on {@code org.openmrs.api.EncounterService}.</p>
 */
public class EncounterIndexingAdvice implements AfterReturningAdvice {

	private static final Logger log = LoggerFactory.getLogger(EncounterIndexingAdvice.class);

	@Override
	public void afterReturning(Object returnValue, Method method, Object[] args, Object target) {
		if (!"saveEncounter".equals(method.getName())) {
			return;
		}

		String mode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SEARCH_MODE);
		if (!ChartSearchAiConstants.SEARCH_MODE_EMBEDDING.equalsIgnoreCase(mode)) {
			return;
		}

		if (returnValue instanceof Encounter) {
			Encounter encounter = (Encounter) returnValue;
			try {
				EmbeddingIndexer indexer = Context.getRegisteredComponent(
						"embeddingIndexer", EmbeddingIndexer.class);
				indexer.indexEncounter(encounter);
			}
			catch (Exception e) {
				log.error("Failed to index encounter {}", encounter.getUuid(), e);
			}
		}
	}
}
