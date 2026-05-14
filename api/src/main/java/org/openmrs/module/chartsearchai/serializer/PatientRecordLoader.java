/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.serializer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openmrs.Allergy;
import org.openmrs.Concept;
import org.openmrs.Condition;
import org.openmrs.Diagnosis;
import org.openmrs.MedicationDispense;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.api.context.Context;
import org.openmrs.parameter.MedicationDispenseCriteria;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Loads and serializes all clinical records for a patient. Provides a single point for
 * record iteration logic shared by {@code PatientChartSerializer} and
 * {@code EmbeddingIndexer}.
 *
 * <p>Each record's {@code date} field is the <b>record timestamp</b> — the
 * most clinically relevant date for sorting and citation purposes (e.g.
 * obs datetime for observations, onset date for conditions, activation date
 * for orders). This date is <em>not</em> included in the serialized text;
 * it is added later by {@link PatientChartSerializer} as a citation label.
 * See {@link ClinicalTextSerializer} for the full date handling policy.
 */
@Component
public class PatientRecordLoader {

	@Autowired
	private ObsTextSerializer obsSerializer;

	@Autowired
	private ConditionTextSerializer conditionSerializer;

	@Autowired
	private AllergyTextSerializer allergySerializer;

	@Autowired
	private OrderTextSerializer orderSerializer;

	@Autowired
	private DiagnosisTextSerializer diagnosisSerializer;

	@Autowired
	private PatientProgramTextSerializer programSerializer;

	@Autowired
	private MedicationDispenseTextSerializer medicationDispenseSerializer;

	/**
	 * Load all clinical records for a patient and serialize each to text.
	 *
	 * @param patient the patient whose records to load
	 * @return serialized records with resource type and UUID for each
	 */
	public List<SerializedRecord> loadAll(Patient patient) {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		// Deduplicate by resource key (type:uuid) to avoid re-processing the same record,
		// but allow distinct records with identical text (e.g. two separate BP readings
		// that happen to have the same value).
		Set<String> seenKeys = new HashSet<String>();

		// Observations (top-level only — group members are inlined by serializer)
		for (Obs obs : Context.getObsService().getObservationsByPerson(patient)) {
			if (obs.getObsGroup() != null) {
				continue;
			}
			String text = obsSerializer.toText(obs);
			if (addIfValid(text, ChartSearchAiConstants.RESOURCE_TYPE_OBS, obs.getUuid(), seenKeys)) {
				// Record timestamp: when the observation was recorded
				Date date = obs.getObsDatetime() != null ? obs.getObsDatetime() : obs.getDateCreated();
				// Category hints from the Obs concept (e.g. Temperature
				// → "Vital signs" set). If the Obs concept has no set
				// membership, also check the value concept — assessment
				// and diagnosis Obs reference a diagnosis concept via
				// valueCoded (e.g. "Primary Diagnosis" obs with
				// valueCoded = HIV Disease → "Sexually transmitted
				// disease" set).
				List<String> hints = ChartSearchAiUtils.extractCategoryHints(obs.getConcept());
				if (hints.isEmpty() && obs.getValueCoded() != null) {
					hints = ChartSearchAiUtils.extractCategoryHints(obs.getValueCoded());
				}
				records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_OBS,
						obs.getUuid(), text, date, hints));
			}
		}

		// Conditions
		for (Condition condition : Context.getConditionService().getAllConditions(patient)) {
			String text = conditionSerializer.toText(condition);
			if (addIfValid(text, ChartSearchAiConstants.RESOURCE_TYPE_CONDITION, condition.getUuid(), seenKeys)) {
				// Record timestamp: onset date (not in serialized text — see
				// ConditionTextSerializer). End date is clinically significant
				// and IS included in the text as "Resolved: <date>".
				Date date = condition.getOnsetDate() != null ? condition.getOnsetDate() : condition.getDateCreated();
				List<String> hints = ChartSearchAiUtils.extractCategoryHints(
						condition.getCondition() != null ? condition.getCondition().getCoded() : null);
				records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_CONDITION,
						condition.getUuid(), text, date, hints));
			}
		}

		// Allergies (no clinically significant dates — only record timestamp)
		for (Allergy allergy : Context.getPatientService().getAllergies(patient)) {
			String text = allergySerializer.toText(allergy);
			if (addIfValid(text, ChartSearchAiConstants.RESOURCE_TYPE_ALLERGY, allergy.getUuid(), seenKeys)) {
				List<String> hints = allergy.getAllergen() != null
						? ChartSearchAiUtils.extractCategoryHints(allergy.getAllergen().getCodedAllergen())
						: Collections.<String>emptyList();
				records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_ALLERGY,
						allergy.getUuid(), text, allergy.getDateCreated(), hints));
			}
		}

		// Diagnoses
		for (Diagnosis diagnosis : Context.getDiagnosisService().getDiagnoses(patient, null)) {
			String text = diagnosisSerializer.toText(diagnosis);
			if (addIfValid(text, ChartSearchAiConstants.RESOURCE_TYPE_DIAGNOSIS, diagnosis.getUuid(), seenKeys)) {
				Date date = diagnosis.getEncounter() != null
						? diagnosis.getEncounter().getEncounterDatetime() : diagnosis.getDateCreated();
				List<String> hints = ChartSearchAiUtils.extractCategoryHints(
						diagnosis.getDiagnosis() != null ? diagnosis.getDiagnosis().getCoded() : null);
				records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DIAGNOSIS,
						diagnosis.getUuid(), text, date, hints));
			}
		}

		// Orders (skip voided — getAllOrdersByPatient includes voided orders).
		// Record timestamp: activation date. Stop date is clinically
		// significant and IS included in the text as "Stopped: <date>".
		for (Order order : Context.getOrderService().getAllOrdersByPatient(patient)) {
			if (Boolean.TRUE.equals(order.getVoided())) {
				continue;
			}
			String text = orderSerializer.toText(order);
			if (addIfValid(text, ChartSearchAiConstants.RESOURCE_TYPE_ORDER, order.getUuid(), seenKeys)) {
				List<String> hints = ChartSearchAiUtils.extractCategoryHints(order.getConcept());
				records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_ORDER,
						order.getUuid(), text, order.getDateActivated(), hints));
			}
		}

		// Program enrollments. Record timestamp: enrollment date.
		// Both enrollment and completion dates are clinically significant
		// and ARE included in the text.
		for (PatientProgram pp : Context.getProgramWorkflowService()
				.getPatientPrograms(patient, null, null, null, null, null, false)) {
			String text = programSerializer.toText(pp);
			if (addIfValid(text, ChartSearchAiConstants.RESOURCE_TYPE_PROGRAM, pp.getUuid(), seenKeys)) {
				List<String> hints = pp.getProgram() != null
						? ChartSearchAiUtils.extractCategoryHints(pp.getProgram().getConcept())
						: Collections.<String>emptyList();
				records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_PROGRAM,
						pp.getUuid(), text, pp.getDateEnrolled(), hints));
			}
		}

		// Medication dispenses. Record timestamp: date handed over.
		// The handed-over date is also clinically significant and IS
		// included in the text.
		MedicationDispenseCriteria dispenseCriteria = new MedicationDispenseCriteria();
		dispenseCriteria.setPatient(patient);
		for (MedicationDispense dispense : Context.getMedicationDispenseService()
				.getMedicationDispenseByCriteria(dispenseCriteria)) {
			String text = medicationDispenseSerializer.toText(dispense);
			if (addIfValid(text, ChartSearchAiConstants.RESOURCE_TYPE_MEDICATION_DISPENSE,
					dispense.getUuid(), seenKeys)) {
				Date date = dispense.getDateHandedOver() != null
						? dispense.getDateHandedOver() : dispense.getDateCreated();
				Concept dispenseConcept = dispense.getDrug() != null
						? dispense.getDrug().getConcept() : dispense.getConcept();
				List<String> hints = ChartSearchAiUtils.extractCategoryHints(dispenseConcept);
				records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_MEDICATION_DISPENSE,
						dispense.getUuid(), text, date, hints));
			}
		}

		Collections.sort(records, Comparator.comparing(SerializedRecord::getDate,
				Comparator.nullsLast(Comparator.reverseOrder())));

		return records;
	}

	private static boolean addIfValid(String text, String resourceType, String resourceUuid,
			Set<String> seenKeys) {
		if (text == null || text.trim().isEmpty()) {
			return false;
		}
		return seenKeys.add(resourceType + ":" + resourceUuid);
	}

	/**
	 * A serialized clinical record with its resource type and UUID.
	 */
	public static class SerializedRecord {

		private final String resourceType;

		private final String resourceUuid;

		private final String text;

		private final Date date;

		private final List<String> categoryHints;

		public SerializedRecord(String resourceType, String resourceUuid, String text, Date date) {
			this(resourceType, resourceUuid, text, date, Collections.<String>emptyList());
		}

		public SerializedRecord(String resourceType, String resourceUuid, String text, Date date,
				List<String> categoryHints) {
			this.resourceType = resourceType;
			this.resourceUuid = resourceUuid;
			this.text = text;
			this.date = date;
			this.categoryHints = categoryHints != null
					? categoryHints : Collections.<String>emptyList();
		}

		public String getResourceType() {
			return resourceType;
		}

		public String getResourceUuid() {
			return resourceUuid;
		}

		public String getText() {
			return text;
		}

		public Date getDate() {
			return date;
		}

		/**
		 * @return concept-set names (or other category metadata) to inject into
		 *         the embedding text. Empty when the source concept has no
		 *         containing sets, or the record type does not support hints.
		 */
		public List<String> getCategoryHints() {
			return categoryHints;
		}
	}
}
