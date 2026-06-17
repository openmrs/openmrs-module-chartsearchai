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
 * (e.g. {@code "(2024-01-15)"}) to the record text supplied by the caller
 * (querystore's serialized documents) — metadata for the LLM to reason about
 * chronology. The timestamp is not repeated on every line; see the compression below.
 * To save prompt tokens on charts that cluster many records per encounter date,
 * the date is <strong>run-length compressed</strong>: it is rendered on the first
 * record of each consecutive same-date run and dropped on the rest (and re-shown
 * after any undated record, which resets the run). The chart stays a flat numbered
 * list — a same-date follow-on line looks exactly like a legacy undated line — so
 * no information is lost and the model's per-record view is unchanged in shape. The
 * {@link RecordMapping} text, by contrast, always retains the inline date so the
 * grounding verifier can still resolve a cited date.
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

		// Date-run compression: render a record's "(date)" only when it differs from the immediately
		// preceding record's date, dropping the repeat on consecutive same-date records. Clinical charts
		// cluster many records per encounter date and the date is ~7 tokens, so this is the dominant
		// cold-prefill token saving (~30% fewer prompt tokens) with no information loss — the date still
		// appears on the first record of each run, and the chart stays a FLAT numbered list (no section
		// structure, which nudges small models toward over-enumeration). Every line is byte-shaped like a
		// legacy line: a dated record looks exactly as before; a same-date follow-on looks exactly like a
		// legacy undated record. So the format demonstration in DEFAULT_SYSTEM_PROMPT still mirrors it and
		// needs no change.
		String previousDateLabel = null;
		for (int i = 0; i < records.size(); i++) {
			SerializedRecord record = records.get(i);
			int index = i + 1;
			String dateLabel = record.getDate() != null ? DateFormatUtil.formatDate(record.getDate()) : null;

			// Body = synonym-stripped text + any obs-group (panel) label + live age — everything after
			// the "[N] " index EXCEPT the leading date parenthetical.
			StringBuilder body = new StringBuilder();
			body.append(ConceptNameUtil.stripSynonyms(record.getText()));
			// Surface obs-group (e.g. lab-panel / vital-signs-set) membership inline so the LLM can
			// cluster atomic members of the same group. querystore carries the group identity only in
			// metadata, never in the doc text (ADR Decision 6), and leaves clustering to the consumer.
			appendGroupMembership(body, record);
			// Age is the one demographic that must be computed live: baking it into querystore's
			// indexed patient record would go stale as the patient ages (the index carries only
			// birthdate). Merge the current age into that same citable line so "how old is the
			// patient?" answers directly instead of echoing a birthdate.
			appendLiveAge(body, record, patient);
			String bodyText = body.toString();

			// The RecordMapping text the grounding verifier compares cited records against ALWAYS carries
			// the inline date (when the record has one), even when the chart line below drops it as a
			// same-date repeat: the model can cite a date it read from an earlier record in the run, so
			// the verifier's per-record view must still contain it. This is unchanged from the legacy
			// format, so grounding behaviour is identical.
			String renderedText = dateLabelPrefix(dateLabel) + bodyText;
			mappings.add(new RecordMapping(index, record.getResourceType(), record.getResourceUuid(),
					record.getDate(), renderedText));

			// Chart line: show the date only on the first record of a same-date run (an undated record
			// resets the run, so the next dated record shows its date again); otherwise drop it.
			sb.append("[").append(index).append("] ");
			if (dateLabel != null && !dateLabel.equals(previousDateLabel)) {
				sb.append(dateLabelPrefix(dateLabel));
			}
			sb.append(bodyText).append("\n");
			previousDateLabel = dateLabel;

			if (focusUuids != null && focusUuids.contains(record.getResourceUuid())) {
				focusIndices.add(index);
			}
		}

		return new PatientChart(sb.toString(), Collections.unmodifiableList(mappings),
				Collections.unmodifiableList(focusIndices));
	}

	/**
	 * The {@code "(date) "} citation-label prefix for a record (or {@code ""} when undated). Single-sourced
	 * so the chart line and the grounding verifier's {@link RecordMapping} text can never diverge on date
	 * format: the chart line uses it only on the first record of a same-date run (see serialize), while the
	 * mapping text uses it on every dated record — but both render the date the same way.
	 */
	private static String dateLabelPrefix(String dateLabel) {
		return dateLabel == null ? "" : "(" + dateLabel + ") ";
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
		 * The full per-record content for this index that the citation grounding
		 * verifier compares cited records against — the date parenthetical (if any),
		 * the synonym-stripped body, and (for an obs-group member) the trailing
		 * {@code "(part of: <group>)"} label. The date is ALWAYS included when the
		 * record has one, even when the chart line itself dropped it as a same-date
		 * run repeat (see the class doc's run-length compression): the model may cite
		 * a date it read from the run's first line, so the verifier's view must retain
		 * it. For the first record of a run (or an undated record) this equals the
		 * chart line content after {@code "[N] "}; for a compressed follow-on it is a
		 * superset (the chart line omits the date this still carries). May be
		 * {@code null} when the mapping was built without text.
		 */
		public String getText() {
			return text;
		}
	}
}
