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
import org.openmrs.module.chartsearchai.util.ConceptNameUtil;
import org.openmrs.module.chartsearchai.util.DateFormatUtil;
import org.springframework.stereotype.Component;

/**
 * Serializes an entire patient chart into numbered records for direct LLM inference.
 * Each record is prefixed with a sequential number (e.g. [1]) to minimize
 * token usage. The mapping from number back to resource type and ID is returned
 * alongside the text.
 *
 * <p>This class adds record timestamps as parenthetical citation labels
 * (e.g. {@code "(2024-01-15)"}) before each record's text. These timestamps
 * are metadata for the LLM to reason about chronology, prepended to the
 * record text supplied by the caller (querystore's serialized documents).
 *
 * <p>It also appends an obs-group label (e.g. {@code "(part of: Basic metabolic panel)"})
 * after the body of any record that carries obs-group metadata, so the LLM can cluster
 * the atomic members of a lab panel / vital-signs set — see {@link #appendGroupMembership}.
 */
@Component
public class PatientChartSerializer {

	/** querystore's resource type for the patient demographics document (see its PatientRecordSerializer). */
	private static final String PATIENT_RESOURCE_TYPE = "patient";

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

		// querystore indexes the patient itself as a citable "patient" record (name, sex, birthdate,
		// identifiers — see querystore's PatientRecordSerializer), so when one is present the demographics
		// already live in a numbered, citable record. Prepending the computed header too would duplicate
		// them and, worse, place an un-numbered "Patient: ..." line directly above [1], where small models
		// misattribute it to record [1] (e.g. citing an allergy for the patient's sex). Fall back to the
		// computed header only when no patient record is present — e.g. a nameless patient, which
		// PatientRecordSerializer skips, yielding no querystore document.
		if (!hasPatientRecord(records)) {
			appendDemographics(sb, patient);
		}

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
			// Surface obs-group (e.g. lab-panel / vital-signs-set) membership inline so the LLM can
			// cluster atomic members of the same group. querystore carries the group identity only in
			// metadata, never in the doc text (ADR Decision 6), and leaves clustering to the consumer.
			appendGroupMembership(rendered, record);
			// Age is the one demographic that must be computed live: baking it into querystore's
			// indexed patient record would go stale as the patient ages (the index carries only
			// birthdate). Merge the current age into that same citable line so "how old is the
			// patient?" answers directly instead of echoing a birthdate — without resurrecting the
			// misattribution-prone demographics header. Included in renderedText so the grounding
			// verifier's view still matches what the model saw.
			appendLiveAge(rendered, record, patient);
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

	/**
	 * Appends the obs-group label so co-grouped atomic records (a lab panel, a vital-signs set, an
	 * exam) are clusterable by the LLM. The group's concept name carries the clinical term verbatim
	 * (e.g. {@code "Basic metabolic panel"}, {@code "Vital signs"}) — we deliberately do not inject a
	 * fixed word like "panel", since OpenMRS models these uniformly as obs groups and the grouping is
	 * not always a lab panel. {@link SerializedRecord#getObsGroupUuid()} is the authoritative
	 * membership flag; the concept name is the label. No-op when the record is not a group member or
	 * the group concept has no preferred name (nothing LLM-meaningful to show).
	 */
	private static void appendGroupMembership(StringBuilder rendered, SerializedRecord record) {
		if (record == null || record.getObsGroupUuid() == null) {
			return;
		}
		String groupName = record.getObsGroupConceptName() == null
				? "" : record.getObsGroupConceptName().trim();
		if (!groupName.isEmpty()) {
			rendered.append(" (part of: ").append(groupName).append(")");
		}
	}

	/**
	 * Appends the patient's <em>current</em> age to querystore's {@code patient} demographics record line.
	 * Computed live from the {@link Patient} rather than read from the indexed text, because age changes
	 * over time while the index stores only birthdate. No-op for non-patient records or when age is unknown.
	 */
	private static void appendLiveAge(StringBuilder rendered, SerializedRecord record, Patient patient) {
		if (patient == null || record == null || !PATIENT_RESOURCE_TYPE.equals(record.getResourceType())) {
			return;
		}
		Integer age = patient.getAge();
		if (age != null) {
			rendered.append(" (").append(age).append(age == 1 ? " year old)" : " years old)");
		}
	}

	/**
	 * True if the chart already carries querystore's citable {@code patient} demographics record. When
	 * it does, the separately-computed demographics header would be a redundant — and
	 * misattribution-prone — duplicate, so {@link #serialize} omits it.
	 */
	private static boolean hasPatientRecord(List<SerializedRecord> records) {
		if (records == null) {
			return false;
		}
		for (SerializedRecord record : records) {
			if (record != null && PATIENT_RESOURCE_TYPE.equals(record.getResourceType())) {
				return true;
			}
		}
		return false;
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
		 * index — the date parenthetical (if any), the synonym-stripped body, and
		 * (for an obs-group member) the trailing {@code "(part of: <group>)"} label,
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
