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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Loads and serializes all clinical records for a patient. Provides a single point for
 * record iteration logic shared by {@code PatientChartSerializer} and
 * {@code EmbeddingIndexer}.
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

	/**
	 * Load all clinical records for a patient and serialize each to text.
	 *
	 * @param patient the patient whose records to load
	 * @return serialized records with resource type and ID for each
	 */
	public List<SerializedRecord> loadAll(Patient patient) {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();

		// Observations (top-level only — group members are inlined by serializer)
		for (Obs obs : Context.getObsService().getObservationsByPerson(patient)) {
			if (obs.getObsGroup() != null) {
				continue;
			}
			String text = obsSerializer.toText(obs);
			if (text != null && !text.trim().isEmpty()) {
				records.add(new SerializedRecord("obs", obs.getObsId(), text));
			}
		}

		// Conditions
		for (Condition condition : Context.getConditionService().getActiveConditions(patient)) {
			String text = conditionSerializer.toText(condition);
			if (text != null && !text.trim().isEmpty()) {
				records.add(new SerializedRecord("condition", condition.getConditionId(), text));
			}
		}

		// Allergies
		for (Allergy allergy : Context.getPatientService().getAllergies(patient)) {
			String text = allergySerializer.toText(allergy);
			if (text != null && !text.trim().isEmpty()) {
				records.add(new SerializedRecord("allergy", allergy.getAllergyId(), text));
			}
		}

		// Orders
		for (Order order : Context.getOrderService().getAllOrdersByPatient(patient)) {
			String text = orderSerializer.toText(order);
			if (text != null && !text.trim().isEmpty()) {
				records.add(new SerializedRecord("order", order.getOrderId(), text));
			}
		}

		return records;
	}

	/**
	 * A serialized clinical record with its resource type and ID.
	 */
	public static class SerializedRecord {

		private final String resourceType;

		private final Integer resourceId;

		private final String text;

		public SerializedRecord(String resourceType, Integer resourceId, String text) {
			this.resourceType = resourceType;
			this.resourceId = resourceId;
			this.text = text;
		}

		public String getResourceType() {
			return resourceType;
		}

		public Integer getResourceId() {
			return resourceId;
		}

		public String getText() {
			return text;
		}
	}
}
