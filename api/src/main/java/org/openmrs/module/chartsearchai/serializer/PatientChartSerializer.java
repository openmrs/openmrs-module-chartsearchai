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
import java.util.List;

import org.openmrs.Allergy;
import org.openmrs.Condition;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Serializes an entire patient chart into numbered records for direct LLM inference.
 * Each record is prefixed with a sequential number that the LLM can use for citations.
 * The {@link RecordReference} list maps each number back to a resource type and ID so
 * the UI can link citations to the source record in OpenMRS.
 */
@Component
public class PatientChartSerializer {

	@Autowired
	private ObsTextSerializer obsSerializer;

	@Autowired
	private ConditionTextSerializer conditionSerializer;

	@Autowired
	private AllergyTextSerializer allergySerializer;

	@Autowired
	private OrderTextSerializer orderSerializer;

	/**
	 * Serialize all clinical records for a patient into numbered text lines.
	 *
	 * @param patient the patient whose chart to serialize
	 * @return the serialized chart with numbered records and their source references
	 */
	public PatientChart serialize(Patient patient) {
		List<RecordReference> references = new ArrayList<RecordReference>();
		StringBuilder sb = new StringBuilder();
		int index = 1;

		// Observations (top-level only — group members are inlined by serializer)
		List<Obs> observations = Context.getObsService().getObservationsByPerson(patient);
		for (Obs obs : observations) {
			if (obs.getObsGroup() != null) {
				continue;
			}
			String text = obsSerializer.toText(obs);
			if (!text.trim().isEmpty()) {
				sb.append("[").append(index).append("] ").append(text).append("\n");
				references.add(new RecordReference(index, "obs", obs.getObsId()));
				index++;
			}
		}

		// Conditions
		List<Condition> conditions = Context.getConditionService().getActiveConditions(patient);
		for (Condition condition : conditions) {
			String text = conditionSerializer.toText(condition);
			if (!text.trim().isEmpty()) {
				sb.append("[").append(index).append("] ").append(text).append("\n");
				references.add(new RecordReference(index, "condition", condition.getConditionId()));
				index++;
			}
		}

		// Allergies
		List<Allergy> allergies = Context.getPatientService().getAllergies(patient);
		for (Allergy allergy : allergies) {
			String text = allergySerializer.toText(allergy);
			if (!text.trim().isEmpty()) {
				sb.append("[").append(index).append("] ").append(text).append("\n");
				references.add(new RecordReference(index, "allergy", allergy.getAllergyId()));
				index++;
			}
		}

		// Orders
		List<Order> orders = Context.getOrderService().getAllOrdersByPatient(patient);
		for (Order order : orders) {
			String text = orderSerializer.toText(order);
			if (!text.trim().isEmpty()) {
				sb.append("[").append(index).append("] ").append(text).append("\n");
				references.add(new RecordReference(index, "order", order.getOrderId()));
				index++;
			}
		}

		return new PatientChart(sb.toString(), references);
	}

	/**
	 * The serialized patient chart with numbered records and their source references.
	 */
	public static class PatientChart {

		private final String text;

		private final List<RecordReference> references;

		public PatientChart(String text, List<RecordReference> references) {
			this.text = text;
			this.references = references;
		}

		public String getText() {
			return text;
		}

		public List<RecordReference> getReferences() {
			return references;
		}
	}
}
