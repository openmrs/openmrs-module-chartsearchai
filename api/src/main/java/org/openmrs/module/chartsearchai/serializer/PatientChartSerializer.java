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
import java.util.Set;

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
		return serialize(patient, records, Collections.<String>emptySet());
	}

	/**
	 * Serialize a list of records and compute focus indices for the records whose resource
	 * UUID appears in {@code focusUuids}. The focus-hint mode of prefilter retrieval (where
	 * the LLM sees the full chart but is told which records rank highest by similarity to the
	 * query) uses this to attach 1-based indices alongside the chart text — the LLM prompt then
	 * carries a short "Records ranked by similarity to the query: 3, 7, 12" hint after the chart so
	 * the variable-bytes portion of the prompt is tiny while the chart prefix stays stable
	 * across queries for the same patient (the property llama-server's KV-cache reuse needs).
	 *
	 * @param patient the patient whose demographics to include
	 * @param records the records to serialize
	 * @param focusUuids resource UUIDs (no resourceType prefix) the retrieval ranked highest by
	 *                   similarity to the question; empty means no hint will be rendered
	 * @return the serialized chart with numbered records, index mapping, and focus indices
	 */
	public PatientChart serialize(Patient patient, List<SerializedRecord> records, Set<String> focusUuids) {
		StringBuilder sb = new StringBuilder();
		List<RecordMapping> mappings = new ArrayList<RecordMapping>();
		List<Integer> focusIndices = new ArrayList<Integer>();

		appendDemographics(sb, patient);

		for (int i = 0; i < records.size(); i++) {
			SerializedRecord record = records.get(i);
			int index = i + 1;
			// The exact per-record content the LLM sees in the chart line (everything after
			// the "[N] " index): the date parenthetical plus the synonym-stripped body. The
			// grounding verifier compares cited records against this same string, so its view
			// matches the model's — otherwise a cited date (which the model reads from the
			// prefix) would look unsupported because the bare record body omits it.
			StringBuilder rendered = new StringBuilder();
			if (record.getDate() != null) {
				rendered.append("(").append(DateFormatUtil.formatDate(record.getDate())).append(") ");
			}
			rendered.append(ConceptNameUtil.stripSynonyms(record.getText()));
			String renderedText = rendered.toString();

			mappings.add(new RecordMapping(index, record.getResourceType(), record.getResourceUuid(),
					record.getDate(), renderedText));

			sb.append("[").append(index).append("] ").append(renderedText).append("\n");

			if (focusUuids != null && focusUuids.contains(record.getResourceUuid())) {
				focusIndices.add(index);
			}
		}

		return new PatientChart(sb.toString(), Collections.unmodifiableList(mappings),
				Collections.unmodifiableList(focusIndices));
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
	 * The serialized patient chart with numbered records, index mapping, and (in focus-hint
	 * prefilter mode) the 1-based indices of records the retrieval ranked highest by similarity.
	 * The {@link #getText()} bytes are a function of the patient only — the focus indices are
	 * the per-query payload that rides alongside and is rendered at the end of the LLM prompt
	 * by {@code LlmProvider.buildUserMessage}.
	 */
	public static class PatientChart {

		private final String text;

		private final List<RecordMapping> mappings;

		private final List<Integer> focusIndices;

		public PatientChart(String text, List<RecordMapping> mappings) {
			this(text, mappings, Collections.<Integer>emptyList());
		}

		public PatientChart(String text, List<RecordMapping> mappings, List<Integer> focusIndices) {
			this.text = text;
			this.mappings = mappings;
			this.focusIndices = focusIndices == null ? Collections.<Integer>emptyList() : focusIndices;
		}

		public String getText() {
			return text;
		}

		public List<RecordMapping> getMappings() {
			return mappings;
		}

		public List<Integer> getFocusIndices() {
			return focusIndices;
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

		private final String text;

		/**
		 * Backward-compatible constructor that carries no source text. Mappings
		 * built this way cannot be grounding-checked; the grounding verifier
		 * treats a null/blank text as "cannot verify" and leaves the citation
		 * unannotated.
		 */
		public RecordMapping(int index, String resourceType, String resourceUuid, Date date) {
			this(index, resourceType, resourceUuid, date, null);
		}

		public RecordMapping(int index, String resourceType, String resourceUuid, Date date, String text) {
			this.index = index;
			this.resourceType = resourceType;
			this.resourceUuid = resourceUuid;
			this.date = date;
			this.text = text;
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

		/**
		 * The exact per-record content the LLM saw in the chart line for this
		 * index — the date parenthetical (if any) plus the synonym-stripped body,
		 * i.e. everything after the {@code "[N] "} prefix. The citation grounding
		 * verifier compares cited records against this so its view matches the
		 * model's (including the date the model may cite). May be {@code null}
		 * when the mapping was built without text.
		 */
		public String getText() {
			return text;
		}
	}
}
