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
 * cite, so it can ground reference facts (dosing, contraindications,
 * interactions) the same way it grounds chart records.
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
	 * chart unchanged when the feature is off or nothing matches.
	 */
	public PatientChart inject(PatientChart chart, Patient patient, String question) {
		if (chart == null || !ChartSearchAiUtils.isDrugReferenceEnabled()) {
			return chart;
		}
		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		return injectRecords(chart, context, question);
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
		return new PatientChart(text.toString(), java.util.Collections.unmodifiableList(mappings),
				chart.getFocusIndices());
	}

	/**
	 * Deduplicated union of question-driven and patient-driven matches, query matches first.
	 *
	 * <p>Order-driven injection is <em>relevance-scoped</em>: an active-order reference is injected only
	 * when the question is about a specific drug clinically related to that order (sharing an ATC
	 * chemical subgroup — a real duplicate-therapy / cross-reactivity concern). An active medication
	 * unrelated to the asked-about drug — or a question that names no drug at all — is not injected: it
	 * would be noise that helps the clinician in no way. The model still sees the active-order records
	 * in the chart, and the safety validator reads active orders directly, so neither the answer's
	 * medication awareness nor the safety chips depend on this injection.
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
			for (DrugReference ref : drugReferenceService.findByActiveOrders(context)) {
				// Only when the question names a drug this active order is clinically related to. A
				// question naming no drug has no relevance anchor, so nothing is injected here
				// (relatedToAny returns false for an empty questionDrugs).
				if (relatedToAny(ref, questionDrugs)) {
					byId.put(ref.getId(), ref);
				}
			}
		}

		return new ArrayList<DrugReference>(byId.values());
	}

	/** @return true when {@code order} shares an ATC level-4 subgroup with any of {@code questionDrugs}
	 *          — a genuine class relationship (duplicate therapy / cross-reactivity) that makes the
	 *          active-order reference relevant to the question. An order with no ATC codes is unrelated. */
	private static boolean relatedToAny(DrugReference order, List<DrugReference> questionDrugs) {
		Set<String> orderSubgroups = order.atcSubgroups();
		if (orderSubgroups.isEmpty()) {
			return false;
		}
		for (DrugReference q : questionDrugs) {
			if (!java.util.Collections.disjoint(orderSubgroups, q.atcSubgroups())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Renders one reference entry into the citable line the LLM sees. Numeric dosing
	 * is included only when an age band matches {@code age}; contraindications and
	 * interactions are always rendered.
	 */
	static String render(DrugReference ref, Integer age) {
		StringBuilder sb = new StringBuilder("Drug reference — ").append(ref.getName());
		StringBuilder paren = new StringBuilder();
		if (ref.getDrugClass() != null && !ref.getDrugClass().isEmpty()) {
			paren.append(ref.getDrugClass());
		}
		if (!ref.getAtcCodes().isEmpty()) {
			if (paren.length() > 0) {
				paren.append("; ");
			}
			paren.append("ATC ").append(String.join(", ", ref.getAtcCodes()));
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

		if (!ref.getContraindications().isEmpty()) {
			List<String> notes = new ArrayList<String>();
			for (DrugReference.Contraindication c : ref.getContraindications()) {
				notes.add(c.getNote() != null ? c.getNote() : c.getToken());
			}
			sb.append(" Contraindicated with: ").append(String.join("; ", notes)).append(".");
		}

		if (!ref.getInteractions().isEmpty()) {
			List<String> notes = new ArrayList<String>();
			for (DrugReference.Interaction i : ref.getInteractions()) {
				String label = i.getToken() != null ? i.getToken() : i.getAtc();
				notes.add(i.getNote() != null ? label + " (" + i.getNote() + ")" : label);
			}
			sb.append(" Interactions: ").append(String.join("; ", notes)).append(".");
		}

		if (ref.getSource() != null && !ref.getSource().isEmpty()) {
			sb.append(" Source: ").append(ref.getSource()).append(".");
		}
		return sb.toString();
	}
}
