/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Part 1 of the drug-reference feature: injects matching {@link DrugReference}
 * entries into the serialized chart as additional numbered records the LLM can
 * cite, so it can ground reference facts (dosing, prose warnings,
 * contraindications, interactions) the same way it grounds chart records.
 *
 * <p>Injection is appended <em>after</em> the retrieved chart records, continuing
 * the citation numbering, and carries the {@link ChartSearchAiConstants#RESOURCE_TYPE_DRUG_REFERENCE}
 * resource type so the frontend can render its citation chip distinctly (a side
 * panel, not a chart-tab navigation).
 *
 * <p><strong>Adding a second kind of injected record?</strong> Its resource type must also be
 * classified by {@link org.openmrs.module.chartsearchai.ChartSearchAiUtils#referenceGroup}, which
 * decides whether a client presents a citation as evidence about the patient or as module-supplied
 * reference material. That method fails safe to <em>chart evidence</em> for types it does not
 * recognise — the wrong default for anything injected here — so an unclassified injected type is
 * published to clinicians as if it came from the patient's own record, with no error raised. The
 * reflective guard in {@code ChartSearchAiReferenceGroupTest} catches a new
 * {@code RESOURCE_TYPE_*} constant, but it cannot see a bare string literal written here.
 *
 * <p>Matching is deterministic and age-gated:
 * <ul>
 *   <li><b>Question-driven</b> — an alias hit against the query text.</li>
 *   <li><b>Patient-driven</b> — an ATC-code hit against an active drug order.</li>
 * </ul>
 * Numeric dosing is rendered only when an age band matches the patient's age, so
 * a pediatric maximum is never surfaced for an adult query; contraindication and
 * interaction facts (which are not age-specific) are still rendered.
 */
@Service("chartSearchAi.drugReferenceInjector")
public class DrugReferenceInjector {

	private static final Logger log = LoggerFactory.getLogger(DrugReferenceInjector.class);

	/**
	 * Per-entry character budget for the rendered {@code Interactions:} section. Bounds the
	 * grounding text a single reference line contributes to the prompt so a broad dataset cannot
	 * overflow the LLM context window; the deterministic {@link DrugSafetyValidator} still reads
	 * every interaction off the entry, so nothing is lost from safety checking. The rest are
	 * summarised as "and N more interactions on file".
	 *
	 * <p>Two guarantees override the budget, so the rendered length is the cap plus a bounded
	 * overshoot rather than a hard ceiling — see {@code render}: every partner the patient is
	 * actually on is represented (full note, or a compact {@code name (Severity)} form when the
	 * note will not fit), and at least one dataset-order partner renders alongside them.
	 */
	static final int MAX_INTERACTION_RENDER_CHARS = 1500;

	@Autowired
	private DrugReferenceService drugReferenceService;

	/** Test seam: production wires {@link DrugReferenceService} via {@link Autowired}. */
	void setDrugReferenceService(DrugReferenceService drugReferenceService) {
		this.drugReferenceService = drugReferenceService;
	}

	@Autowired
	private DrugSafetyValidator drugSafetyValidator;

	/** Test seam: production wires {@link DrugSafetyValidator} via {@link Autowired}. */
	void setDrugSafetyValidator(DrugSafetyValidator drugSafetyValidator) {
		this.drugSafetyValidator = drugSafetyValidator;
	}

	/**
	 * The deterministic safety findings for the drugs this question names, as citable records.
	 *
	 * <p>Rationale, measured. {@link DrugSafetyValidator} computes the safety join correctly every
	 * time, but it runs <em>after</em> the answer, so the model is asked to re-derive a conclusion the
	 * module already holds — and it does not. The eval README records 0 joins across 21 baseline
	 * cells; on 2026-07-30 two live cases abstained with the evidence rendered, cited and provably
	 * readable (a patient on simvastatin asked about clarithromycin, 0/6; a patient with a severe
	 * aspirin allergy asked about ibuprofen, 0/4, while quoting the NSAID family list back verbatim
	 * when asked). Supplying more evidence is measurably not the lever, and three prompt variants
	 * regressed. So the finding becomes a record: reporting a line already in front of it is something
	 * the model does reliably.
	 *
	 * <p>Computed by calling {@code validate} with an EMPTY answer — the production path, unmodified.
	 * That makes the drugs in play exactly the question-named ones (the answer contributes none), and
	 * runs the contraindication and interaction passes while contributing no dose-excess warning,
	 * which is correct: a dose warning is about a dose the answer proposes, and there is no answer yet.
	 * No second definition of any safety rule is introduced.
	 *
	 * <p>The list is empty whenever the deterministic layer finds nothing, so a question that nothing
	 * bears on gains no record and its abstention survives by construction rather than by prompt
	 * wording — the direction issue #107 guards.
	 */
	List<SafetyWarning> preAnswerFindings(PatientClinicalContext context, String question) {
		if (drugSafetyValidator == null) {
			return Collections.emptyList();
		}
		return drugSafetyValidator.validate("", question, context);
	}

	/**
	 * Production entry point: injects reference records into {@code chart} for the
	 * given patient and question when the feature is enabled. Reads the patient's
	 * clinical context (active orders) for patient-driven matching. Returns the
	 * chart unchanged when the feature is off or nothing matches. Fails safe: the
	 * injection is an additive enrichment, so any unexpected error degrades to the
	 * unmodified chart rather than failing the query.
	 */
	public PatientChart inject(PatientChart chart, Patient patient, String question) {
		try {
			if (chart == null || !ChartSearchAiUtils.isDrugReferenceEnabled()) {
				return chart;
			}
			PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
			return injectRecords(chart, context, question);
		}
		catch (RuntimeException e) {
			log.warn("Drug-reference injection failed; leaving the chart unmodified — the answer path is never broken",
					e);
			return chart;
		}
	}

	/**
	 * Pure injection over an explicit clinical context — no OpenMRS context read —
	 * so the matching/rendering logic is unit-testable. Honours the
	 * {@code injectFromQuery} / {@code injectFromOrders} toggles.
	 */
	PatientChart injectRecords(PatientChart chart, PatientClinicalContext context, String question) {
		List<DrugReference> matched = matchingEntries(context, question);
		List<SafetyWarning> findings = preAnswerFindings(context, question);
		if (matched.isEmpty() && findings.isEmpty()) {
			return chart;
		}

		Integer age = context != null ? context.getAgeYears() : null;
		StringBuilder text = new StringBuilder(chart.getText());
		List<RecordMapping> mappings = new ArrayList<RecordMapping>(chart.getMappings());
		int index = mappings.size() + 1;

		for (DrugReference ref : matched) {
			String rendered = render(ref, age, context);
			mappings.add(new RecordMapping(index, ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE,
					ref.getId(), null, rendered));
			text.append("[").append(index).append("] ").append(rendered).append("\n");
			index++;
		}

		// After the reference records, so a finding's citation number always follows the reference it
		// was derived from — the clinician reads cause then conclusion in chart order.
		for (SafetyWarning finding : findings) {
			String rendered = renderFinding(finding);
			mappings.add(new RecordMapping(index, ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING,
					ChartSearchAiUtils.resourceKey(finding.getType(), finding.getDrug()), null, rendered));
			text.append("[").append(index).append("] ").append(rendered).append("\n");
			index++;
		}

		log.debug("Injected {} drug-reference and {} safety-finding record(s) into chart for question '{}'",
				matched.size(), findings.size(), question);
		PatientChart injected = new PatientChart(text.toString(), Collections.unmodifiableList(mappings),
				chart.getFocusIndices());
		// Carry the query-scoped stamp across the reconstruction. LlmInferenceService.searchStreaming
		// derives its KV-cache decision from PatientChart.isQueryScoped() precisely so a mode-flip /
		// GP-read race cannot mis-scope the persist; a fresh PatientChart defaults the flag to false,
		// so dropping it here would silently re-arm exactly that hazard for question-dependent slices
		// (the medications path — the flagship scoped intent — is also the likeliest drug-ref match).
		if (chart.isQueryScoped()) {
			injected.markQueryScoped();
		}
		return injected;
	}

	/**
	 * Deduplicated union of question-driven and patient-driven matches, query matches first.
	 *
	 * <p>Order-driven injection is <em>relevance-scoped</em>: an active-order reference is injected only
	 * when the question is about a specific drug clinically related to that order (sharing an ATC
	 * chemical subgroup or a curated cross-reactivity group — a real duplicate-therapy /
	 * cross-reactivity concern). An active medication unrelated to the asked-about drug — or a
	 * question that names no drug at all — is not injected: it would be noise that helps the
	 * clinician in no way. The model still sees the active-order records in the chart, and the
	 * safety validator reads active orders directly, so neither the answer's medication awareness
	 * nor the safety chips depend on this injection.
	 */
	List<DrugReference> matchingEntries(PatientClinicalContext context, String question) {
		Map<String, DrugReference> byId = new LinkedHashMap<String, DrugReference>();

		// The reference drugs the question itself names — drives question-driven injection AND scopes
		// the order-driven injection below, so it is computed regardless of the injectFromQuery toggle.
		List<DrugReference> questionDrugs = drugReferenceService.findByQuery(question);

		boolean fromQuery = ChartSearchAiUtils.getBooleanGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_INJECT_FROM_QUERY,
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_INJECT_FROM_QUERY);
		if (fromQuery) {
			for (DrugReference ref : questionDrugs) {
				byId.put(ref.getId(), ref);
			}
		}

		boolean fromOrders = ChartSearchAiUtils.getBooleanGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_INJECT_FROM_ORDERS,
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_INJECT_FROM_ORDERS);
		if (fromOrders && context != null) {
			List<CrossReactivityGroup> groups = drugReferenceService.getCrossReactivityGroups();
			for (DrugReference ref : drugReferenceService.findByActiveOrders(context)) {
				// Only when the question names a drug this active order is clinically related to. A
				// question naming no drug has no relevance anchor, so nothing is injected here
				// (relatedToAny returns false for an empty questionDrugs).
				if (relatedToAny(ref, questionDrugs, groups)) {
					byId.put(ref.getId(), ref);
				}
			}
		}

		return new ArrayList<DrugReference>(byId.values());
	}

	/** @return true when {@code order} shares an ATC level-4 subgroup — or, failing that, a curated
	 *          cross-reactivity group — with any of {@code questionDrugs}: a genuine class/family
	 *          relationship (duplicate therapy / cross-reactivity) that makes the active-order
	 *          reference relevant to the question. An order with no ATC codes is unrelated. */
	private static boolean relatedToAny(DrugReference order, List<DrugReference> questionDrugs,
			List<CrossReactivityGroup> groups) {
		Set<String> orderSubgroups = order.atcSubgroups();
		List<CrossReactivityGroup> orderGroups = CrossReactivityGroup.groupsOf(order, groups);
		if (orderSubgroups.isEmpty() && orderGroups.isEmpty()) {
			return false;
		}
		for (DrugReference q : questionDrugs) {
			if (!Collections.disjoint(orderSubgroups, q.atcSubgroups())
					|| CrossReactivityGroup.sharedGroup(orderGroups, q) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * One deterministic finding as a chart line. The detail text is reused verbatim — it is the same
	 * string the clinician already sees on the chip, so the prose and the chip cannot describe the
	 * same finding differently.
	 */
	static String renderFinding(SafetyWarning finding) {
		return "Safety finding — " + finding.getDrug() + ": " + finding.getDetail();
	}

	/**
	 * The entry's interaction notes, ordered so the partners this patient is actually on come first —
	 * most severe of those first, see {@link #SEVERITY_DESCENDING} — then the rest in dataset order.
	 *
	 * <p>This ordering is what makes the {@link #MAX_INTERACTION_RENDER_CHARS} cut meaningful.
	 * Rendering in dataset order let the dataset's own sequence decide which partners a clinician's
	 * model could cite: in the full DDInter KB, Clarithromycin carries 898 partners with Simvastatin
	 * (Major) at index 324, so a patient on simvastatin asking about clarithromycin got a record
	 * naming ivosidenib, kanamycin and ketoprofen — none of which they take — and the one
	 * interaction that concerned them was truncated 300 entries earlier. The model then recited
	 * what it could see. {@link DrugSafetyValidator} was unaffected throughout, because it reads
	 * every interaction off the entry and never consults this text, so the chip named simvastatin
	 * while the prose named ivosidenib: the two disagreed by construction.
	 *
	 * <p>Relevance uses {@link PatientClinicalContext#hasActiveDrug} — deliberately the same
	 * predicate {@link DrugSafetyValidator} uses to decide an interaction concerns this patient, so
	 * a partner that raises a chip is exactly a partner promoted here, and the rendered text cannot
	 * drift from the chip.
	 *
	 * <p>Ordering alone is not sufficient, which is why {@code render} also overrides the budget for
	 * this segment: two above-floor partners can exceed {@link #MAX_INTERACTION_RENDER_CHARS}
	 * between them (measured on the bundled sample: methotrexate 783 + aspirin 809 against a 1500
	 * budget), and dropping the second reinstates exactly the chip-versus-prose split described
	 * above for the polypharmacy case. So the cap becomes a soft budget with a bounded overshoot
	 * rather than a hard ceiling — bounded by the patient's own active-drug count, not the dataset's
	 * breadth, and paid in the compact {@code name (Severity)} form rather than in full notes.
	 *
	 * @param context may be null (nothing to prioritise by) — the section then keeps dataset order
	 */
	static OrderedInteractions orderedInteractionNotes(DrugReference ref, PatientClinicalContext context) {
		List<InteractionNote> promoted = new ArrayList<InteractionNote>();
		List<InteractionNote> rest = new ArrayList<InteractionNote>();
		// Promotion honours the SAME severity floor the chips do (issue #84). Measured on the 3.7.1
		// standalone (2026-07-30): promoting on relevance alone surfaced DDInter's Unknown-severity
		// rows — which carry no mechanism text and which the floor deliberately suppresses from
		// chips — into the front of the prompt, and the model then answered from them. Two probe
		// cells that correctly abstained on the baseline started reporting "an Unknown severity
		// interaction between Erythromycin and Lisinopril", i.e. the render path was bypassing a
		// safety decision the chip path enforces. A sub-floor rule is not promoted; it keeps its
		// dataset position, exactly as before promotion existed.
		int floor = DrugSafetyValidator.configuredSeverityFloor();
		for (DrugReference.Interaction i : ref.getInteractions()) {
			String label = ChartSearchAiUtils.firstNonBlank(i.getToken(), i.getAtc());
			String note = ChartSearchAiUtils.firstNonBlank(i.getNote());
			// Kept identical to the previous rendering: a labelless rule still contributes its bare
			// note, and a null/blank pair contributes nothing (addIfPresent drops it) — the dataset
			// is operator-editable and must degrade, never throw or emit a literal "null".
			String rendered = label != null ? (note != null ? label + " (" + note + ")" : label) : note;
			if (ChartSearchAiUtils.isBlank(rendered)) {
				continue;
			}
			// Trim before the length comparison below, not after: comparing untrimmed and storing
			// trimmed lets a row whose note carries trailing whitespace still end up with a "compact"
			// form longer than the full one, which is the single thing that comparison exists to rule
			// out.
			rendered = rendered.trim();
			// The compact form exists so a relevant partner is never invisible when its full note
			// does not fit the budget (see render()). Severity is kept because it is the one thing a
			// clinician needs when the mechanism prose has to go; a labelless rule has nothing
			// shorter to fall back to, so it keeps its full text.
			String severity = ChartSearchAiUtils.firstNonBlank(i.getSeverity());
			String compact = (label == null ? rendered
					: (severity != null ? label + " (" + severity + ")" : label)).trim();
			// A row carrying a severity but no mechanism text renders full as just the label, which
			// the severity-bearing short form would then be LONGER than — a "compact" that costs
			// more than what it replaces. Fall back to the full text in that case so the name stays
			// true and the substitution can never grow the piece.
			if (compact.length() >= rendered.length()) {
				compact = rendered;
			}
			boolean promote = context != null && context.hasActiveDrug(i.getToken(), i.getAtc())
					&& DrugSafetyValidator.clearsSeverityFloor(i, floor);
			(promote ? promoted : rest).add(new InteractionNote(rendered, compact,
					DrugSafetyValidator.severityRank(i.getSeverity())));
		}
		// Within the promoted segment, severity — not dataset position — decides who keeps their
		// mechanism prose when the budget can only afford one full note (see render). Measured on the
		// bundled sample: a patient on lisinopril (Moderate x ibuprofen, 910 chars) and aspirin
		// (MAJOR, 809) exceeded the budget, and because lisinopril sits earlier in the dataset it
		// took the full note while the Major interaction was abbreviated to "aspirin (Major)". Both
		// severities stayed visible, so nothing was silently dropped — but the actionable half went
		// to the less dangerous interaction, decided by dataset accident. Stable, so equal severities
		// keep dataset order.
		Collections.sort(promoted, SEVERITY_DESCENDING);
		int promotedCount = promoted.size();
		// `promoted` becomes the whole ordered list from here — the count above is what keeps the two
		// segments distinguishable to render().
		promoted.addAll(rest);
		return new OrderedInteractions(promoted, promotedCount);
	}

	/**
	 * Orders promoted interactions most-severe first. An unrated rule sorts ahead of Major: every
	 * curated hand-authored rule is unrated, and {@link DrugSafetyValidator#clearsSeverityFloor}
	 * already treats unrated as exempt rather than low — unrated is not low-rated, so it must not be
	 * the one abbreviated.
	 *
	 * <p>Which source a rule came from decides whether that branch is reachable at all: DDInter rates
	 * every row (all 295,184 in the full KB are Major/Moderate/Minor/Unknown — none unrecognised), so
	 * unrated arises only from an operator's curated JSON. A mixed deployment is therefore the only
	 * configuration in which the unrated-versus-rated tie-break is observable, which is why no
	 * bundled dataset can cover it.
	 */
	private static final Comparator<InteractionNote> SEVERITY_DESCENDING = new Comparator<InteractionNote>() {

		@Override
		public int compare(InteractionNote a, InteractionNote b) {
			return Integer.compare(b.severityPriority, a.severityPriority);
		}
	};

	/** One interaction's rendered text, with a short form for when the budget cannot take the note. */
	static final class InteractionNote {

		final String full;

		final String compact;

		/** {@link DrugSafetyValidator#severityRank} with unrated raised above Major — see
		 *  {@link #SEVERITY_DESCENDING}. */
		final int severityPriority;

		InteractionNote(String full, String compact, int severityRank) {
			this.full = full;
			this.compact = compact;
			this.severityPriority = severityRank < 0 ? Integer.MAX_VALUE : severityRank;
		}
	}

	/** The ordered interaction notes plus the length of their patient-relevant prefix. */
	static final class OrderedInteractions {

		final List<InteractionNote> ordered;

		final int promotedCount;

		OrderedInteractions(List<InteractionNote> ordered, int promotedCount) {
			this.ordered = ordered;
			this.promotedCount = promotedCount;
		}
	}

	/**
	 * Renders one reference entry into the citable line the LLM sees. Numeric dosing
	 * is included only when an age band matches {@code age}; prose warnings,
	 * contraindications and interactions are always rendered.
	 *
	 * <p>{@code context} orders the capped {@code Interactions:} section — see
	 * {@link #orderedInteractionNotes}. It may be null (nothing to prioritise by), in which case
	 * the section keeps dataset order.
	 */
	static String render(DrugReference ref, Integer age, PatientClinicalContext context) {
		StringBuilder sb = new StringBuilder("Drug reference — ").append(ref.getName());
		StringBuilder paren = new StringBuilder();
		if (ref.getDrugClass() != null && !ref.getDrugClass().isEmpty()) {
			paren.append(ref.getDrugClass());
		}
		// Normalized (not raw) codes: null/blank elements in an operator-authored file must not
		// leak a literal "null" into the record the LLM cites.
		Set<String> atcCodes = ref.normalizedAtcCodes();
		if (!atcCodes.isEmpty()) {
			if (paren.length() > 0) {
				paren.append("; ");
			}
			paren.append("ATC ").append(String.join(", ", atcCodes));
		}
		if (paren.length() > 0) {
			sb.append(" (").append(paren).append(")");
		}
		sb.append(".");

		DrugReference.AgeBand band = ref.bandForAge(age);
		if (band != null) {
			sb.append(" Dosing for ages ").append(band.getMinYears()).append("-").append(band.getMaxYears())
					.append(": ").append(DrugReference.formatNumber(band.getMgPerKgMin())).append("-")
					.append(DrugReference.formatNumber(band.getMgPerKgMax())).append(" mg/kg per dose");
			if (band.getMaxDailyDoseMg() > 0) {
				sb.append(", maximum ").append(DrugReference.formatNumber(band.getMaxDailyDoseMg())).append(" mg/day");
			} else {
				sb.append(" (no pediatric daily maximum published for this age — consult a dosing reference)");
			}
			sb.append(".");
		}

		// The dataset is operator-editable: a null/blank element in any section must degrade to
		// "skip that element" — never a thrown exception (which would fail the whole query) and
		// never a literal "null" in the record the LLM cites.
		List<String> warningLines = new ArrayList<String>();
		for (String warning : ref.getWarnings()) {
			addIfPresent(warningLines, warning);
		}
		if (!warningLines.isEmpty()) {
			sb.append(" Warnings: ").append(String.join("; ", warningLines)).append(".");
		}

		List<String> contraindicationNotes = new ArrayList<String>();
		for (DrugReference.Contraindication c : ref.getContraindications()) {
			addIfPresent(contraindicationNotes, ChartSearchAiUtils.firstNonBlank(c.getNote(), c.getToken()));
		}
		if (!contraindicationNotes.isEmpty()) {
			sb.append(" Contraindicated with: ").append(String.join("; ", contraindicationNotes)).append(".");
		}

		OrderedInteractions interactions = orderedInteractionNotes(ref, context);
		List<InteractionNote> ordered = interactions.ordered;
		if (!ordered.isEmpty()) {
			// Cap what is *rendered* into the prompt, not what is parsed: a broad interaction
			// dataset (e.g. the ddinter source's Warfarin, ~934 partners) would otherwise write
			// tens of thousands of tokens into a single citable line and blow the LLM context
			// window. The safety validator still reads every interaction off the entry, so this
			// only bounds the grounding text. A blank tail records how many were withheld.
			List<String> shown = new ArrayList<String>();
			int used = 0;

			// Segment 1 — the partners this patient is actually on. Never invisible: the full note
			// while the budget allows, else the compact "name (Severity)" form. Dropping one of
			// these is how the chip and the prose come to disagree, which is the whole defect this
			// ordering exists to fix, so the budget yields to them rather than the reverse. Bounded
			// by the patient's own active-drug list, not by the dataset's breadth.
			for (int i = 0; i < interactions.promotedCount; i++) {
				InteractionNote n = ordered.get(i);
				String piece = shown.isEmpty() || used + n.full.length() <= MAX_INTERACTION_RENDER_CHARS
						? n.full : n.compact;
				shown.add(piece);
				used += piece.length() + 2;
			}

			// Segment 2 — the dataset tail, budget-limited, but never empty when partners remain:
			// this entry is also the only reference material the model has about the drug in
			// general, and a promoted note can be long enough to consume the budget alone (the
			// bundled aspirin x ibuprofen note is ~800 of the 1500 chars). That extends the
			// pre-existing "at least one interaction is always shown" guarantee to one per segment
			// rather than narrowing it to "only the patient's own".
			int restStart = interactions.promotedCount;
			int restShown = 0;
			for (int i = restStart; i < ordered.size(); i++) {
				String n = ordered.get(i).full;
				if (!shown.isEmpty() && used + n.length() > MAX_INTERACTION_RENDER_CHARS) {
					break;
				}
				shown.add(n);
				used += n.length() + 2;
				restShown++;
			}
			if (restShown == 0 && restStart < ordered.size()) {
				shown.add(ordered.get(restStart).full);
			}

			sb.append(" Interactions: ").append(String.join("; ", shown));
			int withheld = ordered.size() - shown.size();
			if (withheld > 0) {
				sb.append("; and ").append(withheld).append(" more interactions on file");
			}
			sb.append(".");
		}

		if (ref.getSource() != null && !ref.getSource().isEmpty()) {
			sb.append(" Source: ").append(ref.getSource()).append(".");
		}
		return sb.toString();
	}

	/** Adds {@code value} to {@code out} only when it is non-null and non-blank. */
	private static void addIfPresent(List<String> out, String value) {
		if (!ChartSearchAiUtils.isBlank(value)) {
			out.add(value.trim());
		}
	}
}
