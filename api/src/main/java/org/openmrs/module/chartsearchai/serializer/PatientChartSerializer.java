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
import java.util.regex.Pattern;

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
 * the date is <strong>run-length compressed</strong> by default: it is rendered on the first
 * record of each consecutive same-date run and dropped on the rest (and re-shown
 * after any undated record, which resets the run). Callers serializing small
 * query-scoped slices switch this off via the {@code compressDateRuns} overload —
 * see {@link #serialize(Patient, List, Set, boolean, boolean)}. The chart stays a flat numbered
 * list — a same-date follow-on line looks exactly like a legacy undated line — so
 * no information is lost and the model's per-record view is unchanged in shape. The
 * {@link RecordMapping} text, by contrast, always retains the inline date so the
 * grounding verifier can still resolve a cited date.
 *
 * <p>It also appends an obs-group label (e.g. {@code "(part of: Basic metabolic panel)"})
 * after the body of any record that carries obs-group metadata, so the LLM can cluster
 * the atomic members of a lab panel / vital-signs set — see {@link #groupMembershipLabel}.
 *
 * <p>Finally, the trailing {@code ".0"} OpenMRS adds to whole-number obs values is trimmed
 * (e.g. {@code "988.0"} → {@code "988"}) to save further prompt tokens — value-lossless and
 * scoped so a {@code ".0"} inside a code or version (e.g. ICD-10 {@code "E11.0"},
 * {@code "1.0.0"}) is preserved. See {@link #trimTrailingZeroDecimals}.
 */
@Component
public class PatientChartSerializer {

	/** querystore's resource type for the patient demographics document (see its PatientRecordSerializer). */
	private static final String PATIENT_RESOURCE_TYPE = "patient";

	/**
	 * Matches a standalone whole-number value rendered with a trailing {@code ".0"} (OpenMRS formats
	 * whole-number obs values that way, e.g. {@code "988.0"}, {@code "18.0"}) so it can be dropped to save
	 * prompt tokens — the {@code ".0"} is formatting noise, not precision, so removing it is value-lossless.
	 * The {@code (?<![\w.])} / {@code (?![\w.])} guards keep it standalone: a {@code ".0"} embedded in a code
	 * or version is preserved (e.g. ICD-10 {@code "E11.0"}, where {@code "E11"} is a DIFFERENT diagnosis, and
	 * {@code "1.0.0"}), so the trim can never silently change clinical meaning.
	 */
	private static final Pattern TRAILING_ZERO_DECIMAL = Pattern.compile("(?<![\\w.])(\\d+)\\.0(?![\\w.])");

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
		return serialize(patient, records, focusUuids, false);
	}

	/**
	 * As {@link #serialize(Patient, List, Set)} but, when {@code dedupGroupLabels} is true, applies
	 * run-length de-dup to the obs-group membership label exactly as the date prefix is run-length
	 * de-duped: a group member renders {@code " (part of: <group>)"} only when its group differs from the
	 * immediately-preceding record's group, so the label is dropped on consecutive same-group members (a
	 * non-member, or a different group, resets the run). The grounding {@link RecordMapping} text always
	 * carries the full label, so citation verification is unchanged. Default (false) keeps the legacy
	 * every-member labelling that the small-model clustering signal relies on. Gated in production by
	 * {@code chartsearchai.serializer.dedupGroupLabels}.
	 *
	 * @param dedupGroupLabels whether to run-length de-dup the obs-group label on consecutive same-group members
	 */
	public PatientChart serialize(Patient patient, List<SerializedRecord> records, Set<String> focusUuids,
			boolean dedupGroupLabels) {
		return serialize(patient, records, focusUuids, dedupGroupLabels, true);
	}

	/**
	 * As {@link #serialize(Patient, List, Set, boolean)} but with the date-run compression
	 * switchable. Compression exists to save prompt tokens on whole-chart serializations
	 * (hundreds of records clustering many per date); the query-scoped slice chart is a few dozen
	 * records, where the saving is negligible and the cost is real — a temporal question ("most
	 * recent weight?") needs the date visible on the record itself, not inferred from a run
	 * header several lines up (measured: a small model quoted an older, explicitly-dated reading
	 * over the newest, run-compressed one). {@code compressDateRuns=false} renders every dated
	 * record's {@code "(date)"} label. The grounding {@link RecordMapping} text is identical
	 * either way (it always carries the date).
	 */
	public PatientChart serialize(Patient patient, List<SerializedRecord> records, Set<String> focusUuids,
			boolean dedupGroupLabels, boolean compressDateRuns) {
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
		String previousGroupUuid = null;
		for (int i = 0; i < records.size(); i++) {
			SerializedRecord record = records.get(i);
			int index = i + 1;
			String dateLabel = record.getDate() != null ? DateFormatUtil.formatDate(record.getDate()) : null;

			// Body = synonym-stripped text + live age. The obs-group (panel) label is computed SEPARATELY
			// below (not appended here) so the chart line can drop a repeated label while the grounding
			// mapping keeps it — everything after "[N] " EXCEPT the leading date and the trailing group label.
			StringBuilder body = new StringBuilder();
			body.append(trimTrailingZeroDecimals(ConceptNameUtil.stripSynonyms(record.getText())));
			// Age is the one demographic that must be computed live: baking it into querystore's
			// indexed patient record would go stale as the patient ages (the index carries only
			// birthdate). Merge the current age into that same citable line so "how old is the
			// patient?" answers directly instead of echoing a birthdate. No-op for non-patient records,
			// which never co-occur with a group label (a group member is never the patient record).
			appendLiveAge(body, record, patient);
			String bodyBase = body.toString();
			// Obs-group (e.g. lab-panel / vital-signs-set) membership label, " (part of: <panel>)" or "",
			// surfaced inline so the LLM can cluster atomic members of the same group. querystore carries
			// the group identity only in metadata, never in the doc text (ADR Decision 6).
			String groupLabel = groupMembershipLabel(record);

			// The RecordMapping the grounding verifier compares cited records against ALWAYS carries the
			// inline date AND the group label, even when the chart line below drops either as a run repeat:
			// the model can cite a date/panel it read from an earlier record in the run, so the verifier's
			// per-record view must still contain it. Grounding behaviour is therefore unchanged.
			String renderedText = dateLabelPrefix(dateLabel) + bodyBase + groupLabel;
			mappings.add(new RecordMapping(index, record.getResourceType(), record.getResourceUuid(),
					record.getDate(), renderedText));

			// Chart line: show the date only on the first record of a same-date run (an undated record
			// resets the run, so the next dated record shows its date again); otherwise drop it. With
			// dedupGroupLabels, run-length de-dup the group label the same way: render it only when this
			// record's group differs from the previous line's group (a non-member or a different group
			// resets the run), so every member's panel stays visible on its own line or the line directly
			// above. Measured saving is only ~2% of prompt tokens, and safe ONLY on E4B+ (the small E2B
			// model fails to cluster the thinned-label members — see GP_SERIALIZER_DEDUP_GROUP_LABELS).
			String currentGroupUuid = record.getObsGroupUuid();
			boolean dropGroupLabel = dedupGroupLabels && !groupLabel.isEmpty()
					&& currentGroupUuid != null && currentGroupUuid.equals(previousGroupUuid);
			sb.append("[").append(index).append("] ");
			if (dateLabel != null && (!compressDateRuns || !dateLabel.equals(previousDateLabel))) {
				sb.append(dateLabelPrefix(dateLabel));
			}
			sb.append(bodyBase);
			if (!dropGroupLabel) {
				sb.append(groupLabel);
			}
			sb.append("\n");
			previousDateLabel = dateLabel;
			previousGroupUuid = currentGroupUuid;

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
	 * Drops the value-lossless trailing {@code ".0"} OpenMRS adds to whole-number obs values, to save
	 * prompt tokens. Scoped by {@link #TRAILING_ZERO_DECIMAL} so only standalone numeric values are
	 * trimmed ({@code "988.0 cells" -> "988 cells"}); a {@code ".0"} inside a code or version is never
	 * touched, so the trim cannot change clinical meaning.
	 */
	private static String trimTrailingZeroDecimals(String text) {
		return TRAILING_ZERO_DECIMAL.matcher(text).replaceAll("$1");
	}

	/**
	 * Returns the obs-group label (e.g. {@code " (part of: Basic metabolic panel)"}) so co-grouped
	 * atomic records (a lab panel, a vital-signs set, an exam) are clusterable by the LLM, or {@code ""}
	 * when the record is not a group member or the group concept has no preferred name (nothing
	 * LLM-meaningful to show). The group's concept name carries the clinical term verbatim — we
	 * deliberately do not inject a fixed word like "panel", since OpenMRS models these uniformly as obs
	 * groups and the grouping is not always a lab panel. {@link SerializedRecord#getObsGroupUuid()} is
	 * the authoritative membership flag; the concept name is the label. Returned (not appended) so the
	 * caller can place it in the grounding mapping unconditionally while dropping it from the chart line
	 * on consecutive same-group members (the {@code dedupGroupLabels} path in
	 * {@link #serialize(Patient, List, Set, boolean)}).
	 */
	private static String groupMembershipLabel(SerializedRecord record) {
		if (record == null || record.getObsGroupUuid() == null) {
			return "";
		}
		String groupName = record.getObsGroupConceptName() == null
				? "" : record.getObsGroupConceptName().trim();
		return groupName.isEmpty() ? "" : " (part of: " + groupName + ")";
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

		/** Whether this chart is a question-dependent query-scoped slice (set by the scoped
		 *  builder via {@link #markQueryScoped}) rather than the stable full chart. Carried ON
		 *  the chart so downstream KV decisions are made against the chart that was actually
		 *  built: re-reading the chartMode GP later can disagree with the read that built this
		 *  chart (a transient GP-read failure, or an operator flip mid-request), and persisting
		 *  a slice prompt under a patient's KV scope would purge their real full-chart entry. */
		private boolean queryScoped;

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

		/** Marks this chart as a query-scoped slice; called only by the scoped chart builder. */
		public void markQueryScoped() {
			this.queryScoped = true;
		}

		/** True when this chart is a question-dependent query-scoped slice — the authoritative,
		 *  race-free signal for KV decisions (see the field note). */
		public boolean isQueryScoped() {
			return queryScoped;
		}
	}

	/**
	 * Maps a sequential index used in the LLM prompt back to the OpenMRS resource.
	 *
	 * <p>{@link #getText()} is the record's content — the part the LLM reads and may quote.
	 * {@link #getSource()} and {@link #getWithheldInteractions()} are <em>about</em> the record
	 * rather than part of it, and are deliberately kept off the text: anything inside it is
	 * quotable, and a model told to cite records recited the module's own truncation counter and
	 * dataset attribution into a clinician-facing answer (issue #117). Metadata a client should
	 * render beside a citation therefore travels as its own field, never as prose.
	 */
	public static class RecordMapping {

		private final int index;

		private final String resourceType;

		private final String resourceUuid;

		private final Date date;

		private final String text;

		private final String source;

		private final int withheldInteractions;

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
			this(index, resourceType, resourceUuid, date, text, null, 0);
		}

		/**
		 * Full constructor, including the citation metadata that must not live in {@code text}
		 * (see the class doc). A chart record has neither, so the shorter constructors default
		 * them to "no attribution, nothing withheld".
		 */
		public RecordMapping(int index, String resourceType, String resourceUuid, Date date, String text,
				String source, int withheldInteractions) {
			this.index = index;
			this.resourceType = resourceType;
			this.resourceUuid = resourceUuid;
			this.date = date;
			this.text = text;
			this.source = source;
			this.withheldInteractions = withheldInteractions;
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

		/**
		 * Where this record's content came from, for a client to render as provenance beside the
		 * citation — the dataset attribution of an injected drug-reference record (e.g.
		 * {@code "DDInter 2.0 (via openmrs-ddi-knowledge-base)"}). {@code null} for a chart
		 * record, whose provenance is the patient's own record.
		 *
		 * <p>Structural rather than appended to {@link #getText()} on purpose: it used to be
		 * rendered into the citable text, and the model quoted it into the answer (issue #117).
		 */
		public String getSource() {
			return source;
		}

		/**
		 * How many of this record's interaction partners the render budget left out, so a client
		 * can be honest that the citation shows a subset. 0 when nothing was withheld, and for
		 * every record that has no interactions to withhold.
		 *
		 * <p>Structural for the same reason as {@link #getSource()}: as a text tail ("and 824 more
		 * interactions on file") the model recited it as though it were clinical content. The
		 * deterministic {@code DrugSafetyValidator} reads every interaction off the entry either
		 * way, so a withheld partner is withheld from the prompt only, never from safety checking.
		 */
		public int getWithheldInteractions() {
			return withheldInteractions;
		}
	}
}
