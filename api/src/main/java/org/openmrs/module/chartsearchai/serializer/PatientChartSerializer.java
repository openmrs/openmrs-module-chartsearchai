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
import java.util.HashSet;
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
 * <p>It also states, on a drug-order record whose order the chart builder could resolve, whether
 * that order is in force ({@link #ACTIVE_ORDER_LABEL} / {@link #INACTIVE_ORDER_LABEL}, issue #317).
 * querystore's rendered text cannot say: it renders no marker at all for an order that lapsed by its
 * {@code auto_expire_date}, so such a prescription would otherwise reach the model byte-shaped
 * exactly like one still being taken.
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
	 * What a drug-order record's line says when the module has read the patient's orders and this
	 * one is among the active ones, and when it is not (issue #317).
	 *
	 * <p><strong>The wording is a measured decision, not a preference, and it is not settled.</strong>
	 * ADR Decision 45 records this prompt being phrasing-sensitive at one word, so a change to either
	 * string is a change to what every chart says to the model and needs its own interleaved A/B on
	 * the arrangements that entry lists. {@code DrugOrderCurrencyMarkTest} pins the literals so that
	 * such a change cannot be made by accident.
	 *
	 * <p>Three things it says on purpose. It reports {@code Order.isActive()} and nothing more — the
	 * module's own authoritative predicate, the one the drug-safety layer screens on — so the chart
	 * and the chips cannot disagree about which prescriptions are in force. It says "active order"
	 * rather than "ended" or "stopped", because absence from the active set is not a claim about a
	 * stop date and is not a claim about whether the patient is taking anything; it borrows the
	 * vocabulary {@code DrugReferenceInjector.renderActiveOrder} already uses ("Active drug order:")
	 * so one axis is not described two ways in one prompt. And it is a plain field in querystore's own
	 * {@code ". Label: value"} idiom rather than a sentence, because issue #110 measured that prose
	 * inside a record gets recited into the answer as though it were clinical content.
	 *
	 * <p>Being recited is the POINT here, which is what separates this from issue #117 and the rule
	 * {@code README} draws from it — that a field belongs beside the citation rather than inside the
	 * record, because everything in a record's text is quotable. What #117 forbids in the text is the
	 * module's own BOOKKEEPING (a truncation counter, a dataset attribution), which a clinician-facing
	 * answer should never carry. Whether a prescription is in force is a fact about the patient's
	 * record, and an answer that repeats it is doing the right thing. The same answer also rides
	 * structurally on {@link RecordMapping#getOrderActive()}, for the consumer that needs to branch on
	 * it rather than read it; that field is deliberately not published on the wire, where a client has
	 * the record text and needs no second copy.
	 */
	public static final String ACTIVE_ORDER_LABEL = ". Active order: yes";

	/** The negative half of {@link #ACTIVE_ORDER_LABEL}; see there for the wording's reasons. */
	public static final String INACTIVE_ORDER_LABEL = ". Active order: no";

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
	 * across queries for the same patient (the property llama-server's KV-cache reuse needs) —
	 * stable across QUESTIONS, that is; since issue #317 a drug-order record's line also states
	 * whether that order is in force, so the bytes move when an order's status does.
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
			// The order-currency mark, for a drug-order record whose order the module could resolve.
			// Part of the BODY rather than a separate label so it reaches the chart line and the
			// grounding mapping by construction: they must not be able to disagree about whether the
			// model was told this prescription is in force.
			body.append(orderCurrencyLabel(record));
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
					record.getDate(), renderedText, null, 0, record.getOrderActive()));

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
	/**
	 * The order-currency label for a record ({@link #ACTIVE_ORDER_LABEL} /
	 * {@link #INACTIVE_ORDER_LABEL}), or {@code ""} when the module cannot say.
	 *
	 * <p>Silence is the whole guard, and it is why this reads a three-valued answer rather than a
	 * boolean: a record that is not a drug order, an order read that failed, and an order that could
	 * not be attributed to this patient all arrive here as {@code null}, and rendering any of them as
	 * "no" would tell a clinician a prescription had ended on the strength of the module not knowing.
	 * That is the fail-closed hazard issue #317 names, and
	 * {@code PatientClinicalContext.contraindicationRecordsRead()} is the same distinction one layer
	 * along: a chart the module could not read is not a chart that records nothing.
	 */
	private static String orderCurrencyLabel(SerializedRecord record) {
		if (record == null || record.getOrderActive() == null) {
			return "";
		}
		return record.getOrderActive().booleanValue() ? ACTIVE_ORDER_LABEL : INACTIVE_ORDER_LABEL;
	}

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
	 * The {@link #getText()} bytes do not vary with the question — the focus indices are
	 * the per-query payload that rides alongside and is rendered at the end of the LLM prompt
	 * by {@code LlmProvider.buildUserMessage}. Question-independent is not time-independent: the
	 * bytes are a function of the patient and of their order status as read when the chart was
	 * assembled (issue #317), as they already were of the patient's current age.
	 */
	public static class PatientChart {

		private final String text;

		private final List<RecordMapping> mappings;

		private final List<Integer> focusIndices;

		// THE STAMPS START HERE — queryScoped, preFiltered, completeResourceTypes. Each records what
		// the BUILDER decided, so a later consumer reads the chart that was actually assembled
		// instead of re-deriving it from a global property that may since have changed.
		//
		// Adding a fourth? It must also be carried across DrugReferenceInjector.injectRecords, which
		// rebuilds this object from scratch to append its records — a fresh PatientChart defaults
		// every stamp to "not set", so a stamp that is not copied there is silently lost on any
		// question that matches the drug reference, and lost in the fail-OPEN direction. That has
		// been the failure twice: once for queryScoped (a slice persisted under a patient's KV
		// scope) and once for preFiltered (a focus-hinted prompt filed in the audit log as a plain
		// full chart, issue #178). Each stamp has a regression test in DrugReferenceInjectorTest;
		// a fourth needs one too.

		/** Whether this chart is a question-dependent query-scoped slice (set by the scoped
		 *  builder via {@link #markQueryScoped}) rather than the stable full chart. Carried ON
		 *  the chart so downstream KV decisions are made against the chart that was actually
		 *  built: re-reading the chartMode GP later can disagree with the read that built this
		 *  chart (a transient GP-read failure, or an operator flip mid-request), and persisting
		 *  a slice prompt under a patient's KV scope would purge their real full-chart entry. */
		private boolean queryScoped;

		/** Whether this chart carries the similarity focus hint the {@code embedding.preFilter}
		 *  global property turns on — the second of the two full-chart shapes, and only ever set on
		 *  a chart that is not {@link #queryScoped}. Carried ON the chart for the same reason that
		 *  flag is: the audit row naming which mode assembled a prompt has to follow the chart that
		 *  was built, and a later re-read of the GP can disagree with the read that built it. */
		private boolean preFiltered;

		/** The resource types this chart carries COMPLETELY — every record querystore holds of
		 *  that type for this patient. Only a query-scoped slice needs to state this: the full
		 *  chart carries everything by construction, so {@link #isCompleteFor} answers from
		 *  {@link #queryScoped} unless a slice has declared its scope. Stamped by the scoped
		 *  builder, for the same reason the queryScoped flag is: a consumer deciding whether a
		 *  record's ABSENCE is meaningful must read the chart that was built, not re-derive the
		 *  routing from the question. */
		private Set<String> completeResourceTypes = Collections.emptySet();

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

		/** Marks this chart as a query-scoped slice. Called by the scoped chart builder, and again by
		 *  {@code DrugReferenceInjector} when it rebuilds the chart to append injected records —
		 *  a rebuild that dropped the stamp would silently turn a slice into something downstream
		 *  reads as a full chart. */
		public void markQueryScoped() {
			this.queryScoped = true;
		}

		/** True when this chart is a question-dependent query-scoped slice — the authoritative,
		 *  race-free signal for KV decisions (see the field note). */
		public boolean isQueryScoped() {
			return queryScoped;
		}

		/** Marks this chart as carrying the preFilter focus hint. Called by the full-chart builder
		 *  from the same {@code usePreFilter} it dispatched on, and again by
		 *  {@code DrugReferenceInjector} on its rebuilt chart — a rebuild that dropped the stamp
		 *  would file a focus-hinted prompt in the audit log as a plain full chart. */
		public void markPreFiltered() {
			this.preFiltered = true;
		}

		/** True when this chart carries the preFilter focus hint — the race-free signal for which of
		 *  the two full-chart shapes assembled it, and (with {@link #isQueryScoped()}) what
		 *  {@code ChartBuildingStrategy.searchModeLabel} names in the audit row. */
		public boolean isPreFiltered() {
			return preFiltered;
		}

		/** Declares the resource types this chart carries completely. Called by the scoped chart
		 *  builder with the typed scope it filtered on, and again by {@code DrugReferenceInjector}
		 *  on its rebuilt chart (via {@link #getCompleteResourceTypes}) for the same reason
		 *  {@link #markQueryScoped} is. A null/empty set declares nothing. */
		public void markCompleteFor(Set<String> resourceTypes) {
			this.completeResourceTypes = resourceTypes == null || resourceTypes.isEmpty()
					? Collections.<String>emptySet()
					: Collections.unmodifiableSet(new HashSet<String>(resourceTypes));
		}

		/**
		 * True when this chart carries every record of {@code resourceType} that was RETRIEVED for
		 * this patient — so a record's ABSENCE from it is informative (nothing here dropped it on
		 * purpose) rather than merely out of scope.
		 *
		 * <p>A statement about this chart, deliberately, not about the index. A scoped slice built at
		 * querystore's ES chart cap declares completeness even though the fetch itself dropped the
		 * older tail, so absence can mean "the retrieved chart lacks it" as well as "the index lacks
		 * it". That is the contract the consumers this exists for need — they repair the chart the
		 * ANSWER is grounded in, and at the cap it genuinely lacks the record — but it means a caller
		 * must not report absence as an indexing defect without hedging. See
		 * {@code QueryStoreChartBuilder.buildScoped}, which explains why suppressing the stamp there
		 * would be the wrong fix.
		 *
		 * <p>A full chart is complete for every type by construction, which is why only the scoped
		 * builder stamps anything: a mode that fetches the whole chart cannot forget to. A
		 * query-scoped slice is complete only for the types it declared via
		 * {@link #markCompleteFor} — a slice omits everything outside its typed scope by design, so
		 * absence there carries no information, and a consumer reading it as drift would fire on
		 * almost every query.
		 *
		 * <p>Ask this only of a chart from the chart-assembly entry point
		 * ({@code ChartBuildingStrategy.buildChart}). The progressive-reasoning preview's focused
		 * top-K chart is neither of those shapes and declares nothing, so it would answer as a full
		 * chart; nothing consults it, and nothing should.
		 */
		public boolean isCompleteFor(String resourceType) {
			return !queryScoped || completeResourceTypes.contains(resourceType);
		}

		/** The types declared via {@link #markCompleteFor}, so a caller rebuilding this chart can
		 *  carry the declaration across; empty on a full chart, which needs none. */
		public Set<String> getCompleteResourceTypes() {
			return completeResourceTypes;
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
		 * Whether the {@code Order} this record was serialized from is in force right now, or
		 * {@code null} when the module cannot say — the structural half of the label the chart line
		 * carries, and the form a consumer reads rather than re-deriving from prose (issue #317).
		 * See {@code SerializedRecord.getOrderActive()} for why the {@code null} cases are one answer.
		 */
		private final Boolean orderActive;

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
			this(index, resourceType, resourceUuid, date, text, source, withheldInteractions, null);
		}

		/**
		 * Full constructor, including the order-currency answer. Every shorter constructor defaults it
		 * to {@code null} — "the module cannot say" — which is right for an injected record (no
		 * {@code Order} behind it) and for every caller that has not read the patient's orders.
		 */
		public RecordMapping(int index, String resourceType, String resourceUuid, Date date, String text,
				String source, int withheldInteractions, Boolean orderActive) {
			this.index = index;
			this.resourceType = resourceType;
			this.resourceUuid = resourceUuid;
			this.date = date;
			this.text = text;
			this.source = source;
			this.withheldInteractions = withheldInteractions;
			this.orderActive = orderActive;
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
		 * How many of this record's interaction partners it does not show, so a client can be honest
		 * that the citation shows a subset. 0 when it shows them all, and for every record that has
		 * no interactions to withhold.
		 *
		 * <p>Two rules withhold, and outside a broad dataset the second dominates: the per-record
		 * render budget, and — once a partner the patient is actually on is shown — the remaining
		 * dataset being represented by one partner rather than rendered in full. A large count
		 * therefore usually means "not relevant to this patient" rather than "did not fit", so it
		 * must not be presented to a clinician as an omission for length.
		 *
		 * <p>Structural for the same reason as {@link #getSource()}: as a text tail ("and 824 more
		 * interactions on file") the model recited it as though it were clinical content. The
		 * deterministic {@code DrugSafetyValidator} reads every interaction off the entry either
		 * way, so a withheld partner is withheld from the prompt only, never from safety checking.
		 */
		public int getWithheldInteractions() {
			return withheldInteractions;
		}

		/**
		 * @return {@code TRUE} when this record's order is in the patient's active-order set,
		 *         {@code FALSE} when it was read and this order was not in it, {@code null} when the
		 *         module cannot say.
		 *
		 *         <p>Structural rather than re-read from {@link #getText()} for the reason the
		 *         active-order reconciliation records: keying a decision on another module's display
		 *         prose cannot see an end the prose does not carry, which is exactly the auto-expiry
		 *         gap issue #317 exists to close.
		 */
		public Boolean getOrderActive() {
			return orderActive;
		}
	}
}
