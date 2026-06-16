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
import org.openmrs.Diagnosis;
import org.openmrs.MedicationDispense;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.springframework.aop.AfterReturningAdvice;

/**
 * AOP advice that invalidates a patient's cached answers when their conditions, diagnoses,
 * allergies, orders, programs, medication dispenses, or demographics ({@code savePatient}) change
 * outside of encounter saves, and on patient merges (both the preferred and non-preferred patient).
 *
 * <p>Backend-independent: it runs whenever the answer cache is on
 * ({@code chartsearchai.cacheTtlMinutes > 0}) so an edit never leaves a stale cached answer.
 * Retrieval-index freshness is owned by querystore (via core's #6084 service events), not by
 * chartsearchai — this advice no longer triggers any embedding/Lucene/Elasticsearch re-index.
 * Complements {@link EncounterIndexingAdvice} (the encounter path).
 *
 * <p>Registered in config.xml as advice on {@code ConditionService}, {@code DiagnosisService},
 * {@code PatientService}, {@code OrderService}, {@code ProgramWorkflowService}, and
 * {@code MedicationDispenseService}.</p>
 */
public class PatientDataIndexingAdvice implements AfterReturningAdvice {

	private static final Set<String> CONDITION_METHODS = new HashSet<String>(
			Arrays.asList("saveCondition", "voidCondition", "unvoidCondition", "purgeCondition"));

	private static final Set<String> DIAGNOSIS_METHODS = new HashSet<String>(
			Arrays.asList("save", "voidDiagnosis", "unvoidDiagnosis", "purgeDiagnosis"));

	private static final Set<String> ALLERGY_METHODS = new HashSet<String>(
			Arrays.asList("saveAllergy", "setAllergies", "removeAllergy", "voidAllergy"));

	private static final Set<String> ORDER_METHODS = new HashSet<String>(
			Arrays.asList("saveOrder", "saveRetrospectiveOrder", "voidOrder", "unvoidOrder",
					"purgeOrder", "discontinueOrder"));

	private static final Set<String> PROGRAM_METHODS = new HashSet<String>(
			Arrays.asList("savePatientProgram", "voidPatientProgram", "unvoidPatientProgram",
					"purgePatientProgram"));

	private static final Set<String> MEDICATION_DISPENSE_METHODS = new HashSet<String>(
			Arrays.asList("saveMedicationDispense", "voidMedicationDispense",
					"unvoidMedicationDispense", "deleteMedicationDispense"));

	// Patient demographics (age from birthdate, and sex — see PatientChartSerializer) are part of the
	// serialized chart the LLM cites, so a demographic edit must invalidate cached answers. A
	// name-only edit over-invalidates harmlessly (the name is not serialized). Scoped to savePatient:
	// mergePatients is handled separately, and void/unvoid/purgePatient are rare admin ops on patients
	// that are not normally queried — left to the TTL backstop.
	private static final Set<String> PATIENT_METHODS = new HashSet<String>(
			Arrays.asList("savePatient"));

	/** Union of every write method this advice acts on (plus mergePatients), used as the first,
	 *  cheapest gate. The advisor fires afterReturning on <em>every</em> method of the advised
	 *  services (ConditionService, OrderService, PatientService, …), including hot reads like
	 *  {@code getPatient}; this name check lets those skip the answer-cache lookup and patient
	 *  resolution entirely. Built from the per-type sets so it never diverges from them. */
	private static final Set<String> ALL_WRITE_METHODS = new HashSet<String>();

	static {
		ALL_WRITE_METHODS.addAll(CONDITION_METHODS);
		ALL_WRITE_METHODS.addAll(DIAGNOSIS_METHODS);
		ALL_WRITE_METHODS.addAll(ALLERGY_METHODS);
		ALL_WRITE_METHODS.addAll(ORDER_METHODS);
		ALL_WRITE_METHODS.addAll(PROGRAM_METHODS);
		ALL_WRITE_METHODS.addAll(MEDICATION_DISPENSE_METHODS);
		ALL_WRITE_METHODS.addAll(PATIENT_METHODS);
		ALL_WRITE_METHODS.add("mergePatients");
	}

	@Override
	public void afterReturning(Object returnValue, Method method, Object[] args, Object target) {
		String methodName = method.getName();
		// Cheapest gate first: skip reads (the advisor fires on every service method) before the
		// answer-cache lookup and before any Hibernate proxy resolution extractPatient can trigger
		// (Condition.getPatient(), Order.getPatient(), etc.).
		if (!ALL_WRITE_METHODS.contains(methodName)) {
			return;
		}
		if (!IndexingHelper.isAnswerCacheEnabled()) {
			return;
		}

		if ("mergePatients".equals(methodName)) {
			handleMergePatients(args);
			return;
		}

		Patient patient = extractPatient(methodName, args);
		if (patient != null) {
			IndexingHelper.invalidateAnswerCache(patient);
		}
	}

	private void handleMergePatients(Object[] args) {
		// A merge changes the preferred patient's chart and retires the non-preferred one, so any
		// cached answers for either are now stale.
		if (args.length < 2 || !(args[0] instanceof Patient) || !(args[1] instanceof Patient)) {
			return;
		}
		IndexingHelper.invalidateAnswerCache((Patient) args[0]);
		IndexingHelper.invalidateAnswerCache((Patient) args[1]);
	}

	Patient extractPatient(String methodName, Object[] args) {
		if (CONDITION_METHODS.contains(methodName) && args.length > 0 && args[0] instanceof Condition) {
			return ((Condition) args[0]).getPatient();
		} else if (DIAGNOSIS_METHODS.contains(methodName) && args.length > 0 && args[0] instanceof Diagnosis) {
			return ((Diagnosis) args[0]).getPatient();
		} else if (ALLERGY_METHODS.contains(methodName)) {
			if (args.length > 0 && args[0] instanceof Patient) {
				return (Patient) args[0];
			} else if (args.length > 0 && args[0] instanceof Allergy) {
				return ((Allergy) args[0]).getPatient();
			}
		} else if (ORDER_METHODS.contains(methodName) && args.length > 0 && args[0] instanceof Order) {
			return ((Order) args[0]).getPatient();
		} else if (PROGRAM_METHODS.contains(methodName) && args.length > 0 && args[0] instanceof PatientProgram) {
			return ((PatientProgram) args[0]).getPatient();
		} else if (MEDICATION_DISPENSE_METHODS.contains(methodName) && args.length > 0
				&& args[0] instanceof MedicationDispense) {
			return ((MedicationDispense) args[0]).getPatient();
		} else if (PATIENT_METHODS.contains(methodName) && args.length > 0 && args[0] instanceof Patient) {
			return (Patient) args[0];
		}
		return null;
	}
}
