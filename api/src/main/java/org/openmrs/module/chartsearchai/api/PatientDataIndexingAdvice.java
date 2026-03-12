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

import org.openmrs.Allergy;
import org.openmrs.Condition;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.AfterReturningAdvice;

/**
 * AOP advice that triggers a full patient re-index when conditions, allergies,
 * or orders are modified outside of encounter saves. Complements the
 * {@link EncounterIndexingAdvice} which handles the more common encounter path.
 *
 * <p>Only active when the search mode is set to {@code embedding}. Registered
 * in config.xml as advice on {@code ConditionService}, {@code PatientService},
 * and {@code OrderService}.</p>
 */
public class PatientDataIndexingAdvice implements AfterReturningAdvice {

	private static final Logger log = LoggerFactory.getLogger(PatientDataIndexingAdvice.class);

	private static final Set<String> CONDITION_METHODS = new HashSet<String>(
			Arrays.asList("saveCondition", "voidCondition", "unvoidCondition"));

	private static final Set<String> ALLERGY_METHODS = new HashSet<String>(
			Arrays.asList("saveAllergy", "setAllergies", "removeAllergy", "voidAllergy"));

	private static final Set<String> ORDER_METHODS = new HashSet<String>(
			Arrays.asList("saveOrder", "saveRetrospectiveOrder", "voidOrder", "unvoidOrder"));

	@Override
	public void afterReturning(Object returnValue, Method method, Object[] args, Object target) {
		String methodName = method.getName();

		Patient patient = null;

		if (CONDITION_METHODS.contains(methodName) && args.length > 0 && args[0] instanceof Condition) {
			patient = ((Condition) args[0]).getPatient();
		} else if (ALLERGY_METHODS.contains(methodName)) {
			if (args.length > 0 && args[0] instanceof Patient) {
				patient = (Patient) args[0];
			} else if (args.length > 0 && args[0] instanceof Allergy) {
				patient = ((Allergy) args[0]).getPatient();
			}
		} else if (ORDER_METHODS.contains(methodName) && args.length > 0 && args[0] instanceof Order) {
			patient = ((Order) args[0]).getPatient();
		}

		if (patient == null) {
			return;
		}

		String mode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SEARCH_MODE);
		if (!ChartSearchAiConstants.SEARCH_MODE_EMBEDDING.equalsIgnoreCase(mode)) {
			return;
		}

		try {
			EmbeddingIndexer indexer = Context.getRegisteredComponent(
					"embeddingIndexer", EmbeddingIndexer.class);
			indexer.indexPatient(patient);
		}
		catch (Exception e) {
			log.error("Failed to re-index patient [id={}] after {} call",
					patient.getPatientId(), methodName, e);
		}
	}
}
