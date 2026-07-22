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
	 * every interaction off the entry, so nothing is lost from safety checking. At least one
	 * interaction is always shown; the rest are summarised as "and N more interactions on file".
	 */
	static final int MAX_INTERACTION_RENDER_CHARS = 1500;

	@Autowired
	private DrugReferenceService drugReferenceService;

	/** Test seam: production wires {@link DrugReferenceService} via {@link Autowired}. */
	void setDrugReferenceService(DrugReferenceService drugReferenceService) {
		this.drugReferenceService = drugReferenceService;
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
		if (matched.isEmpty()) {
			return chart;
		}

		Integer age = context != null ? context.getAgeYears() : null;
		StringBuilder text = new StringBuilder(chart.getText());
		List<RecordMapping> mappings = new ArrayList<RecordMapping>(chart.getMappings());
		int index = mappings.size() + 1;

		for (DrugReference ref : matched) {
			String rendered = render(ref, age);
			mappings.add(new RecordMapping(index, ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE,
					ref.getId(), null, rendered));
			text.append("[").append(index).append("] ").append(rendered).append("\n");
			index++;
		}

		log.debug("Injected {} drug-reference record(s) into chart for question '{}'",
				matched.size(), question);
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
	 * Renders one reference entry into the citable line the LLM sees. Numeric dosing
	 * is included only when an age band matches {@code age}; prose warnings,
	 * contraindications and interactions are always rendered.
	 */
	static String render(DrugReference ref, Integer age) {
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

		List<String> interactionNotes = new ArrayList<String>();
		for (DrugReference.Interaction i : ref.getInteractions()) {
			String label = ChartSearchAiUtils.firstNonBlank(i.getToken(), i.getAtc());
			String note = ChartSearchAiUtils.firstNonBlank(i.getNote());
			if (label != null) {
				addIfPresent(interactionNotes, note != null ? label + " (" + note + ")" : label);
			} else {
				addIfPresent(interactionNotes, note);
			}
		}
		if (!interactionNotes.isEmpty()) {
			// Cap what is *rendered* into the prompt, not what is parsed: a broad interaction
			// dataset (e.g. the ddinter source's Warfarin, ~934 partners) would otherwise write
			// tens of thousands of tokens into a single citable line and blow the LLM context
			// window. The safety validator still reads every interaction off the entry, so this
			// only bounds the grounding text. A blank tail records how many were withheld.
			List<String> shown = new ArrayList<String>();
			int used = 0;
			for (String n : interactionNotes) {
				if (!shown.isEmpty() && used + n.length() > MAX_INTERACTION_RENDER_CHARS) {
					break;
				}
				shown.add(n);
				used += n.length() + 2;
			}
			sb.append(" Interactions: ").append(String.join("; ", shown));
			int withheld = interactionNotes.size() - shown.size();
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
