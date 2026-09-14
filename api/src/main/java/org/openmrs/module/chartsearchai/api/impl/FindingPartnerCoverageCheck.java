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
import java.util.List;
import java.util.Locale;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.FindingPartnerCoverage;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the answer stated every active order a MERGED finding names.
 *
 * <p><b>The gap this closes.</b> ADR Decision 99 collapsed one mechanism onto one chip naming every
 * order it covers, and moved a shortfall with it: before, a finding the answer left out went uncited
 * and {@code findingCitations} counted it; after, the finding is cited and a NAME inside it can go
 * unstated, which that key cannot see because it counts findings. Measured live on the 3.7.1
 * standalone (patient Sarah Taylor, 2026-09-15, two consecutive runs): the chip named
 * Methylprednisolone, Prednisone, Budesonide, Dexamethasone and Hydrocortisone; the answer named four
 * of them, dropping Dexamethasone, while every published key read clean.
 *
 * <p><b>It reads the names structurally</b> — {@link SafetyWarning#namedPartners()}, written where the
 * merge decided them — and never by parsing the detail the module itself composed them into, which is
 * the two-resolutions-that-agree shape issue #151 forbids.
 *
 * <p><b>What it does NOT establish.</b> Containment over the answer's prose is the test, so a name the
 * model spelled differently reads as unstated; the residue therefore runs toward REPORTING a shortfall
 * rather than toward silence, which is the safe direction for a diagnostic and the opposite of
 * {@code findingCitations}'s. It says nothing about whether the answer's claim ABOUT a partner is
 * right — that is {@code ReferenceProseFidelityCheck}'s question — only whether the partner was named
 * at all. And it is scoped to merged chips: an ordinary chip names one partner and has no list to
 * under-state, so it is outside the population rather than passing it.
 */
public final class FindingPartnerCoverageCheck {

	private static final Logger log = LoggerFactory.getLogger(FindingPartnerCoverageCheck.class);

	private FindingPartnerCoverageCheck() {
	}

	/**
	 * The active orders the response's findings name that {@code answer} does not — in the order the
	 * chips name them, each once however many findings cover it.
	 *
	 * <p>Shared with {@link #measure}, so the sentence the module appends and the count it publishes
	 * cannot come to disagree about which names were missing.
	 */
	public static List<String> unstatedPartners(String answer, List<SafetyWarning> warnings) {
		List<String> unstated = new ArrayList<String>();
		if (warnings == null || ChartSearchAiUtils.isBlank(answer)) {
			return unstated;
		}
		String haystack = answer.toLowerCase(Locale.ROOT);
		for (SafetyWarning warning : warnings) {
			for (String partner : warning.namedPartners()) {
				if (!haystack.contains(partner.toLowerCase(Locale.ROOT)) && !unstated.contains(partner)) {
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
	 * deterministically — it composed the chip — so completing the sentence needs no model at all.
	 *
	 * <p><b>It APPENDS and never replaces</b>, the contract issue #398 set: the verdict lead is never
	 * re-decided, an answer that named every order is returned byte for byte, and the sentence states
	 * only what the chips already state. It carries no citation marker because it offers no NEW
	 * evidence — the finding that covers these orders is cited in the sentence the model wrote, and a
	 * marker here would claim a record this text did not read.
	 *
	 * <p><b>The checks that judge the MODEL's prose run before this</b>, deliberately, so that
	 * appending cannot make an incomplete answer look complete to them; {@code findingPartners} is
	 * measured AFTER, because it describes the answer a client is handed.
	 */
	public static String withUnstatedPartnersNamed(String answer, List<String> unstated) {
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
	 * @param patient whose identifier the WARN names, never the answer or the record text
	 * @param answer the model's answer; blank states no measurement, since an answer that does not
	 *        exist has not omitted anything
	 * @param warnings the chips this pass raised
	 * @return how many partners the merged findings NAME and how many of those the answer STATED, or
	 *         {@code null} where the check itself failed — a broken diagnostic must not read as one
	 *         that found nothing
	 */
	static FindingPartnerCoverage measure(Patient patient, String answer, List<SafetyWarning> warnings) {
		Integer patientId = null;
		try {
			patientId = patient == null ? null : patient.getPatientId();
			if (warnings == null || warnings.isEmpty() || ChartSearchAiUtils.isBlank(answer)) {
				return null;
			}
			String haystack = answer.toLowerCase(Locale.ROOT);
			int named = 0;
			int stated = 0;
			List<String> unstated = new ArrayList<String>();
			for (SafetyWarning warning : warnings) {
				for (String partner : warning.namedPartners()) {
					named++;
					if (haystack.contains(partner.toLowerCase(Locale.ROOT))) {
						stated++;
					}
					else {
						unstated.add(partner);
					}
				}
			}
			if (named == 0) {
				// No merged finding in this response, so there is no list anything could be short of.
				// Absence of the population and not a measurement of none — see FindingPartnerCoverage.
				return null;
			}
			if (!unstated.isEmpty()) {
				// The partner NAMES are this module's own reference vocabulary, not the patient's data,
				// so they may be logged; the answer and the record text may not.
				log.warn("Answer for patient={} stated {} of {} active order(s) its safety finding(s) "
							+ "name; the module named the rest itself (ADR Decision 100): {}.",
						patientId, Integer.valueOf(stated), Integer.valueOf(named), unstated);
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
}
