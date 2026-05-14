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
import java.util.Date;
import java.util.List;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.openmrs.module.chartsearchai.util.ConceptNameUtil;
import org.openmrs.module.chartsearchai.util.DateFormatUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Serializes an entire patient chart into numbered records for direct LLM inference.
 * Each record is prefixed with a sequential number (e.g. [1]) to minimize
 * token usage. The mapping from number back to resource type and ID is returned
 * alongside the text.
 *
 * <p>This class adds record timestamps as parenthetical citation labels
 * (e.g. {@code "(2024-01-15)"}) before each record's text. These timestamps
 * are <em>not</em> part of the embedding text produced by
 * {@link ClinicalTextSerializer} — they are metadata for the LLM to reason
 * about chronology. See {@link ClinicalTextSerializer} for the full date
 * handling policy.
 */
@Component
public class PatientChartSerializer {

	@Autowired
	private PatientRecordLoader recordLoader;

	/**
	 * Serialize all clinical records for a patient into numbered text lines.
	 *
	 * @param patient the patient whose chart to serialize
	 * @return the serialized chart with numbered records and index mapping
	 */
	public PatientChart serialize(Patient patient) {
		return serialize(patient, recordLoader.loadAll(patient));
	}

	/**
	 * Serialize a pre-filtered list of records into numbered text lines.
	 *
	 * @param patient the patient whose demographics to include
	 * @param records the records to serialize
	 * @return the serialized chart with numbered records and index mapping
	 */
	public PatientChart serialize(Patient patient, List<SerializedRecord> records) {
		StringBuilder sb = new StringBuilder();
		List<RecordMapping> mappings = new ArrayList<RecordMapping>();

		appendDemographics(sb, patient);

		for (int i = 0; i < records.size(); i++) {
			SerializedRecord record = records.get(i);
			int index = i + 1;
			mappings.add(new RecordMapping(index, record.getResourceType(), record.getResourceUuid(), record.getDate()));

			sb.append("[").append(index).append("] ");
			if (record.getDate() != null) {
				sb.append("(").append(DateFormatUtil.formatDate(record.getDate())).append(") ");
			}
			sb.append(ConceptNameUtil.stripSynonyms(record.getText())).append("\n");
		}

		return new PatientChart(sb.toString(), Collections.unmodifiableList(mappings));
	}

	private void appendDemographics(StringBuilder sb, Patient patient) {
		if (patient == null) {
			return;
		}
		Integer age = patient.getAge();
		String gender = patient.getGender();
		if (age == null && gender == null) {
			return;
		}
		sb.append("Patient: ");
		if (age != null) {
			sb.append(age).append("-year-old ");
		}
		if (gender != null) {
			sb.append("M".equalsIgnoreCase(gender) ? "Male" : "F".equalsIgnoreCase(gender) ? "Female" : gender);
		}
		sb.append("\n\n");
	}

	/**
	 * The serialized patient chart with numbered records and index mapping.
	 */
	public static class PatientChart {

		private final String text;

		private final List<RecordMapping> mappings;

		public PatientChart(String text, List<RecordMapping> mappings) {
			this.text = text;
			this.mappings = mappings;
		}

		public String getText() {
			return text;
		}

		public List<RecordMapping> getMappings() {
			return mappings;
		}
	}

	/**
	 * Maps a sequential index used in the LLM prompt back to the OpenMRS resource.
	 */
	public static class RecordMapping {

		private final int index;

		private final String resourceType;

		private final String resourceUuid;

		private final Date date;

		public RecordMapping(int index, String resourceType, String resourceUuid, Date date) {
			this.index = index;
			this.resourceType = resourceType;
			this.resourceUuid = resourceUuid;
			this.date = date;
		}

		public int getIndex() {
			return index;
		}

		public String getResourceType() {
			return resourceType;
		}

		public String getResourceUuid() {
			return resourceUuid;
		}

		public Date getDate() {
			return date;
		}
	}
}
