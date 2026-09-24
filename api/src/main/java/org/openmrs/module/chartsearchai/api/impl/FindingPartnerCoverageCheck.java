/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.FindingPartnerCoverage;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the answer stated every active order the findings it CITED name.
 *
 * <p><b>The gap this closes.</b> ADR Decision 99 collapsed one mechanism onto one chip naming every
 * order it covers, and moved a shortfall with it: before, a finding the answer left out went uncited
 * and {@code findingCitations} counted it; after, the finding is cited and a NAME inside it can go
 * unstated, which that key cannot see because it counts findings. Measured live on the 3.7.1
 * standalone (patient Sarah Taylor, 2026-09-15, two consecutive runs): the chip named
 * Methylprednisolone, Prednisone, Budesonide, Dexamethasone and Hydrocortisone; the answer named four
 * of them, dropping Dexamethasone, while every published key read clean.
 *
 * <p><b>It reads the names structurally</b> — {@code RecordMapping.getFindingPartners()}, the injected
 * finding's own copy of {@link SafetyWarning#namedPartners()}, written where the record is — and never
 * by parsing the detail the module itself composed them into, which is the two-resolutions-that-agree
 * shape issue #151 forbids.
 *
 * <p><b>Its population is the findings the answer CITED</b> (issue #516), read through
 * {@code SafetyFindingCitationExtentCheck.citedFindingIndexes}, the only reading of which findings an
 * answer cited, and pinned to it by
 * {@code ArchitectureGuardTest.theFindingPartnerCompletionTakesItsCitedReadingFromTheExtentCheck}.
 * Before that issue it was every chip the response raised, which the appended sentence then credited
 * to "those findings" — ADR Decision 100's amendment records what that put in front of a clinician.
 * A finding the answer did not cite is {@code findingCitations}' to count, and none of its
 * orders is appended — the chips beside the answer carry the orders each names as
 * {@code namedPartners}; an answer citing no finding gets nothing appended and no measurement.
 *
 * <p><b>What it does NOT establish.</b> Containment over the answer's prose is the test, so a name the
 * model spelled differently reads as unstated; the residue therefore runs toward REPORTING a shortfall
 * rather than toward silence, which is the safe direction for a diagnostic and the opposite of
 * {@code findingCitations}'s. Since issue #516 both operands are compared in {@code comparable} form,
 * which folds case and takes out the whitespace around a slash — the difference that issue measured
 * putting a false "not named above" into an answer — and leaves every other spelling difference
 * unstated. Containment has a residue in the other direction too: an order whose name sits inside a
 * longer one the answer wrote ({@code Lamivudine} inside {@code Lamivudine / zidovudine}) reads as
 * stated. It says nothing about whether the answer's claim ABOUT a partner is right — that is {@code ReferenceProseFidelityCheck}'s question — only whether the
 * partner was named at all. An ordinary finding names one order and is measured like the rest, while
 * the merged finding and, since issue #477, the finding that a drug is already in several of her
 * orders and a screen's finding that several of her orders share a substance are where a list can be
 * under-stated in part.
 */
public final class FindingPartnerCoverageCheck {

	private static final Logger log = LoggerFactory.getLogger(FindingPartnerCoverageCheck.class);

	/** Whitespace on either side of a slash — the spacing an answer and an order display disagree on. */
	private static final Pattern SPACING_AROUND_A_SLASH = Pattern.compile("\\s*/\\s*");

	private FindingPartnerCoverageCheck() {
	}

	/**
	 * The active orders the findings {@code answer} cited name that it does not — in the order the
	 * injector wrote those findings and each names its orders, each once however many cited findings
	 * cover it; empty where the answer cited no finding.
	 *
	 * <p><b>It shares its population and its comparison with {@link #measure}</b> — {@code citedFindings}
	 * and {@code comparable} — so an order is stated to both or to neither; {@code measure} counts in a
	 * loop of its own and, since issue #439, states no list of names at all. The two do not agree in
	 * UNIT: {@code measure} counts a partner once per cited finding that names it while this dedups, so
	 * on two cited findings naming one order {@code named - stated} exceeds the size of the list
	 * appended to the answer. That divergence is pre-existing and this
	 * sentence used to claim it away.
	 */
	private static List<String> unstatedPartners(String answer, List<RecordReference> cited,
			List<RecordMapping> mappings) {
		List<String> unstated = new ArrayList<String>();
		if (ChartSearchAiUtils.isBlank(answer)) {
			return unstated;
		}
		String haystack = comparable(answer);
		Set<String> listed = new HashSet<String>();
		for (RecordMapping finding : citedFindings(answer, cited, mappings)) {
			for (String partner : finding.getFindingPartners()) {
				String key = comparable(partner);
				if (!haystack.contains(key) && listed.add(key)) {
					unstated.add(partner);
				}
			}
		}
		return unstated;
	}

	/**
	 * The answer with the orders it left unnamed appended, or the answer unchanged where it named them
	 * all.
	 *
	 * <p><b>Why this and not a second inference.</b> Issue #398's repair asks the model again, which
	 * costs an inference and is a change in the PROMPT position ADR Decision 84 measured added
	 * instruction to regress in. Nothing here touches the prompt: the module already knows the names
	 * deterministically — it composed the finding — so completing the sentence needs no model at all.
	 *
	 * <p><b>It APPENDS and never replaces</b>, the contract issue #398 set: the verdict lead is never
	 * re-decided, an answer that named every order is returned byte for byte, and the sentence states
	 * only what the cited findings already state. It carries no citation marker because it offers no NEW
	 * evidence — the finding that covers these orders is cited in the sentence the model wrote, and a
	 * marker here would claim a record this text did not read. That justification holds only of a
	 * finding the answer cites, which is why the orders are those of the cited findings and an answer
	 * citing none is returned untouched (issue #516).
	 *
	 * <p><b>The checks that judge the MODEL's prose run before this</b>, deliberately, so that
	 * appending cannot make an incomplete answer look complete to them — and {@link #measure} is one of
	 * them: {@code findingPartners} is measured BEFORE this, on the model's own prose (ADR Decision
	 * 100), so a response whose {@code stated} is short of {@code named} says the module named the rest.
	 *
	 * @param answer the answer — the model's, or the one the module composed — read for the markers it
	 *        anchors and for the orders it names
	 * @param cited the references the answer cites, as resolved by
	 *        {@code LlmInferenceService.extractCitedReferences}
	 * @param mappings the chart's records, the carrier of the findings and of the orders each names
	 */
	static String withUnstatedPartnersNamed(String answer, List<RecordReference> cited,
			List<RecordMapping> mappings) {
		return withNamed(answer, unstatedPartners(answer, cited, mappings));
	}

	private static String withNamed(String answer, List<String> unstated) {
		if (unstated == null || unstated.isEmpty()) {
			return answer;
		}
		// Through the shared rule and never a terminator of this method's own: endSentence is the one
		// entry point asking whether a string already ends a sentence.
		StringBuilder sb = new StringBuilder(DrugSafetyValidator.endSentence(answer.trim()));
		sb.append(" Also covered by those findings and not named above: ");
		for (int i = 0; i < unstated.size(); i++) {
			if (i > 0) {
				sb.append(i == unstated.size() - 1 ? " and " : ", ");
			}
			sb.append(DrugSafetyValidator.ACTIVE_ORDER_NOUN).append(" ").append(unstated.get(i));
		}
		sb.append(".");
		return sb.toString();
	}

	/**
	 * @param patient whose identifier the WARN names — never the answer, the record text, or the
	 *        name of an order the findings cover (issue #439)
	 * @param answer the model's answer; blank states no measurement, since an answer that does not
	 *        exist has not omitted anything
	 * @param cited the references the answer cites, as resolved by
	 *        {@code LlmInferenceService.extractCitedReferences}
	 * @param mappings the chart's records, the carrier of the findings and of the orders each names
	 * @return how many partners the findings the answer cited NAME and how many of those the answer
	 *         STATED; {@code null} where the answer cited no finding or no cited finding names an
	 *         order — absence of the population, not a measurement of none — and where the check
	 *         itself failed, since a broken diagnostic must not read as one that found nothing
	 */
	static FindingPartnerCoverage measure(Patient patient, String answer, List<RecordReference> cited,
			List<RecordMapping> mappings) {
		Integer patientId = null;
		try {
			patientId = patient == null ? null : patient.getPatientId();
			if (ChartSearchAiUtils.isBlank(answer)) {
				return null;
			}
			List<RecordMapping> findings = citedFindings(answer, cited, mappings);
			if (findings.isEmpty()) {
				return null;
			}
			String haystack = comparable(answer);
			int named = 0;
			int stated = 0;
			for (RecordMapping finding : findings) {
				for (String partner : finding.getFindingPartners()) {
					named++;
					if (haystack.contains(comparable(partner))) {
						stated++;
					}
				}
			}
			if (named == 0) {
				// No finding the answer cited names an order, so there is no list anything could be short of.
				// Absence of the population and not a measurement of none — see FindingPartnerCoverage.
				return null;
			}
			if (stated < named) {
				// The two COUNTS and the patient, never a name — the rule its siblings state at their
				// own WARNs, and this line broke it (issue #439). A partner is named here only because
				// this patient is prescribed it, so a list of them is that patient's medication list on
				// a line carrying their id, and core ships org.openmrs at WARN. The names reach the
				// clinician instead: ADR Decision 100 appends them to the ANSWER. → ADR Decision 102.
				log.warn("Answer for patient={} stated {} of {} active order(s) its safety finding(s) "
							+ "name; the module named the rest itself (ADR Decision 100).",
						patientId, Integer.valueOf(stated), Integer.valueOf(named));
			}
			return new FindingPartnerCoverage(named, stated);
		}
		catch (RuntimeException e) {
			// A diagnostic must never break a clinical answer — the promise its siblings make, made
			// structurally rather than by inspection, and loudly, so the check's own failure is not
			// itself silent.
			log.warn("Finding-partner coverage failed for patient={}; the answer is unaffected: {}",
					patientId, e.toString());
			return null;
		}
	}

	/**
	 * {@code text} in the one form both operands of the containment test are compared in — the answer
	 * and every order name alike (issue #516): case-folded, and with the whitespace around a {@code /}
	 * taken out, so an answer writing {@code Isoniazid / pyrazinamide/rifampin} states the order its
	 * finding names {@code Isoniazid / pyrazinamide / rifampin}. One helper for both methods and for
	 * {@link #unstatedPartners}' dedup, so a name cannot be stated to one and unstated to the other.
	 * Only that whitespace: {@code DrugReference.collapseWhitespace} is not widened for it.
	 */
	private static String comparable(String text) {
		return SPACING_AROUND_A_SLASH.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("/");
	}

	/** The injected findings {@code answer} cited, in the order the injector wrote them —
	 *  {@code SafetyFindingCitationExtentCheck.citedFindingIndexes}, the only reading of which findings
	 *  an answer cited, projected back onto the records so each one's orders can be read. */
	private static List<RecordMapping> citedFindings(String answer, List<RecordReference> cited,
			List<RecordMapping> mappings) {
		List<RecordMapping> findings = new ArrayList<RecordMapping>();
		Set<Integer> citedIndexes = SafetyFindingCitationExtentCheck.citedFindingIndexes(answer, cited,
				mappings);
		if (citedIndexes.isEmpty()) {
			return findings;
		}
		for (RecordMapping mapping : mappings) {
			if (citedIndexes.contains(Integer.valueOf(mapping.getIndex()))) {
				findings.add(mapping);
			}
		}
		return findings;
	}
}
