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
import org.openmrs.Person;
import org.openmrs.api.context.Context;
import org.springframework.aop.AfterReturningAdvice;

/**
 * AOP advice that invalidates a patient's cached answers when observations are saved, voided,
 * unvoided, or purged directly (outside of an encounter save).
 *
 * <p>Backend-independent: it runs whenever the answer cache is on
 * ({@code chartsearchai.cacheTtlMinutes > 0}) so an edit never leaves a stale cached answer.
 * Retrieval-index freshness is owned by querystore (via core's #6084 service events), not by
 * chartsearchai — this advice no longer triggers any embedding/Lucene/Elasticsearch re-index.
 *
 * <p>Registered in config.xml as advice on {@code org.openmrs.api.ObsService}.</p>
 */
public class ObsIndexingAdvice implements AfterReturningAdvice {

	private static final Set<String> WRITE_METHODS = new HashSet<String>(
			Arrays.asList("saveObs", "voidObs", "unvoidObs", "purgeObs"));

	@Override
	public void afterReturning(Object returnValue, Method method, Object[] args, Object target) {
		if (!WRITE_METHODS.contains(method.getName())) {
			return;
		}
		// Answer cache off → nothing to do. Checked before resolving the affected patient so the
		// default cache-off hot path skips Obs.getPerson()'s proxy resolution and the patient-by-id
		// lookup getPatientFromArgs may otherwise perform.
		if (!IndexingHelper.isAnswerCacheEnabled()) {
			return;
		}

		Patient patient = getPatientFromArgs(returnValue, args);
		if (patient != null) {
			IndexingHelper.invalidateAnswerCache(patient);
		}
	}

	Patient getPatientFromArgs(Object returnValue, Object[] args) {
		Person person = personFromArgs(returnValue, args);
		if (person == null) {
			return null;
		}
		// A real obs loaded by the service/REST layer carries a plain Person (Hibernate maps
		// obs.person to Person), never a Patient instance, so an instanceof check alone would drop
		// every obs write and leave the answer cache stale after an obs edit. Resolve the Patient by
		// id when the person is not already one; a non-patient or unsaved Person resolves to null and
		// is correctly skipped.
		if (person instanceof Patient) {
			return (Patient) person;
		}
		return resolvePatient(person);
	}

	private static Person personFromArgs(Object returnValue, Object[] args) {
		if (returnValue instanceof Obs) {
			return ((Obs) returnValue).getPerson();
		}
		if (args != null && args.length > 0 && args[0] instanceof Obs) {
			return ((Obs) args[0]).getPerson();
		}
		return null;
	}

	/**
	 * Resolves the {@link Patient} for a person carrying a patient id, or null when the person is
	 * unsaved or not a patient. Package-private seam so unit tests can exercise the loaded-obs path
	 * (where {@code obs.getPerson()} is a {@link Person}, not a {@link Patient}) without an OpenMRS
	 * context.
	 */
	Patient resolvePatient(Person person) {
		if (person == null || person.getPersonId() == null) {
			return null;
		}
		return Context.getPatientService().getPatient(person.getPersonId());
	}
}
