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

import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.AfterReturningAdvice;

/**
 * AOP advice that triggers a full patient re-index when observations are voided or purged
 * directly (outside of an encounter save). This removes orphaned embeddings for voided obs.
 *
 * <p>Registered in config.xml as advice on {@code org.openmrs.api.ObsService}.</p>
 */
public class ObsIndexingAdvice implements AfterReturningAdvice {

	private static final Logger log = LoggerFactory.getLogger(ObsIndexingAdvice.class);

	private static final Set<String> REINDEX_METHODS = new HashSet<String>(
			Arrays.asList("voidObs", "unvoidObs", "purgeObs"));

	@Override
	public void afterReturning(Object returnValue, Method method, Object[] args, Object target) {
		if (!REINDEX_METHODS.contains(method.getName())) {
			return;
		}

		String mode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SEARCH_MODE);
		if (!ChartSearchAiConstants.SEARCH_MODE_EMBEDDING.equalsIgnoreCase(mode)) {
			return;
		}

		Patient patient = getPatientFromArgs(returnValue, args);
		if (patient == null) {
			return;
		}

		try {
			EmbeddingIndexer indexer = Context.getRegisteredComponent(
					"embeddingIndexer", EmbeddingIndexer.class);
			indexer.indexPatient(patient);
		}
		catch (Exception e) {
			log.error("Failed to re-index patient after {} call", method.getName(), e);
		}
	}

	Patient getPatientFromArgs(Object returnValue, Object[] args) {
		if (returnValue instanceof Obs) {
			return ((Obs) returnValue).getPerson() instanceof Patient
					? (Patient) ((Obs) returnValue).getPerson() : null;
		}
		if (args != null && args.length > 0 && args[0] instanceof Obs) {
			Obs obs = (Obs) args[0];
			return obs.getPerson() instanceof Patient ? (Patient) obs.getPerson() : null;
		}
		return null;
	}
}
