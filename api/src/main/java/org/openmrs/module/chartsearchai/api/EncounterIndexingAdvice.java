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
import org.springframework.aop.AfterReturningAdvice;

/**
 * AOP advice that invalidates a patient's cached answers after their encounters are saved, voided,
 * unvoided, or purged.
 *
 * <p>Backend-independent: it runs whenever the answer cache is on
 * ({@code chartsearchai.cacheTtlMinutes > 0}) so an edit never leaves a stale cached answer.
 * Retrieval-index freshness is owned by querystore (via core's #6084 service events), not by
 * chartsearchai — this advice no longer triggers any embedding/Lucene/Elasticsearch re-index.
 *
 * <p>Registered in config.xml as advice on {@code org.openmrs.api.EncounterService}.</p>
 */
public class EncounterIndexingAdvice implements AfterReturningAdvice {

	private static final Set<String> WRITE_METHODS = new HashSet<String>(
			Arrays.asList("saveEncounter", "voidEncounter", "unvoidEncounter", "purgeEncounter"));

	@Override
	public void afterReturning(Object returnValue, Method method, Object[] args, Object target) {
		if (!WRITE_METHODS.contains(method.getName())) {
			return;
		}
		if (!IndexingHelper.isAnswerCacheEnabled()) {
			return;
		}
		// saveEncounter returns the Encounter; void/unvoid/purge carry it in args[0].
		Patient patient = getPatientFromArgs(returnValue, args);
		if (patient != null) {
			IndexingHelper.invalidateAnswerCache(patient);
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
