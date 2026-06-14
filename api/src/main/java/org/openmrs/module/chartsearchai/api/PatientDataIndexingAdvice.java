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
import org.openmrs.api.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.AfterReturningAdvice;

/**
 * AOP advice that, when conditions, diagnoses, allergies, orders, programs, medication dispenses,
 * or patient demographics ({@code savePatient}) are modified outside of encounter saves,
 * invalidates the patient's cached answers and (when embedding indexing is active) triggers a full
 * patient re-index. Also handles patient merges by invalidating both patients' cached answers and,
 * when indexing is active, re-indexing the preferred patient and deleting the non-preferred
 * patient's embeddings. Complements the {@link EncounterIndexingAdvice} which handles the more
 * common encounter path.
 *
 * <p>Embedding indexing runs only when {@code chartsearchai.embedding.preFilter} is {@code true}
 * and querystore is not the active backend. Answer-cache invalidation is independent of both — it
 * runs whenever the cache is on ({@code chartsearchai.cacheTtlMinutes > 0}), regardless of
 * retrieval backend, so an edit never leaves a stale cached answer. See {@link IndexingHelper}.</p>
 *
 * <p>Registered in config.xml as advice on {@code ConditionService}, {@code DiagnosisService},
 * {@code PatientService}, {@code OrderService}, {@code ProgramWorkflowService},
 * and {@code MedicationDispenseService}.</p>
 */
public class PatientDataIndexingAdvice implements AfterReturningAdvice {

	private static final Logger log = LoggerFactory.getLogger(PatientDataIndexingAdvice.class);

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
	// mergePatients is handled separately above, and void/unvoid/purgePatient are rare admin ops on
	// patients that are not normally queried (and would also mis-trigger a reindex of a gone patient
	// on the preFilter path) — left to the TTL backstop rather than expanding the shared indexing trigger.
	private static final Set<String> PATIENT_METHODS = new HashSet<String>(
			Arrays.asList("savePatient"));

	@Override
	public void afterReturning(Object returnValue, Method method, Object[] args, Object target) {
		String methodName = method.getName();

		// Indexing is gated by querystore/preFilter; the answer cache must be invalidated on every
		// write regardless of backend (see IndexingHelper.invalidateAnswerCache). Patient extraction
		// stays below both checks so the all-off hot path skips the Hibernate proxy resolution
		// extractPatient can trigger (Condition.getPatient(), Order.getPatient(), etc.).
		boolean indexingActive = !IndexingHelper.isDisabledByQueryStore()
				&& IndexingHelper.isPreFilterEnabled();
		boolean cacheActive = IndexingHelper.isAnswerCacheEnabled();
		if (!indexingActive && !cacheActive) {
			return;
		}

		boolean isMerge = "mergePatients".equals(methodName);
		if (isMerge) {
			handleMergePatients(args, indexingActive, cacheActive);
			return;
		}

		Patient patient = extractPatient(methodName, args);
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
				log.error("Failed to re-index patient [id={}] after {} call",
						patient.getPatientId(), methodName, e);
			}

			IndexingHelper.reindexOtherPipelines(patient);
		}
	}

	private void handleMergePatients(Object[] args, boolean indexingActive, boolean cacheActive) {
		if (args.length < 2 || !(args[0] instanceof Patient) || !(args[1] instanceof Patient)) {
			return;
		}
		Patient preferred = (Patient) args[0];
		Patient notPreferred = (Patient) args[1];

		// A merge changes the preferred patient's chart and retires the non-preferred one, so any
		// cached answers for either are now stale.
		if (cacheActive) {
			IndexingHelper.invalidateAnswerCache(preferred);
			IndexingHelper.invalidateAnswerCache(notPreferred);
		}

		if (indexingActive) {
			try {
				EmbeddingIndexer indexer = Context.getRegisteredComponent(
						"embeddingIndexer", EmbeddingIndexer.class);
				indexer.indexPatient(preferred);
				indexer.deletePatientEmbeddings(notPreferred);
			}
			catch (Exception e) {
				log.error("Failed to re-index after mergePatients [preferred={}, notPreferred={}]",
						preferred.getPatientId(), notPreferred.getPatientId(), e);
			}

			IndexingHelper.reindexOtherPipelines(preferred);
			IndexingHelper.deleteOtherPipelineIndexes(notPreferred);
		}
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
