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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;

/**
 * The module's own statement that the chart records a drug only as an order no longer in force, for
 * an answer that did not say so — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/472">#472</a>, ADR Decision
 * 110.
 *
 * <p><b>The gap this closes.</b> Decision 110's A/B recorded the shipped prompt answering <em>"Her
 * current medications are lamivudine, nevirapine and rifampicin. Any interactions?"</em> with <em>"Yes,
 * there are interactions recorded for these medications."</em>: the refusal gone, the clinician's false
 * premise still confirmed, and the only trace of the referent a chip key no client renders yet. The
 * module holds that fact structurally — {@link SafetyWarning#isAboutAnEndedOrder()} and
 * {@link SafetyWarning#getEndedOrderStopDate()}, written where the validator decided them — so it
 * states it the way ADR Decision 100's {@code FindingPartnerCoverageCheck} states an order the prose
 * left unnamed: appended by the module, with no model asked and no prompt changed.
 *
 * <p><b>What "the answer said so" is</b>: some sentence of the answer
 * ({@code ChartSearchAiUtils.SENTENCE_BOUNDARY}) names the chip's drug and contains
 * {@link #NO_LONGER_IN_FORCE}, the words the prompt's ended-order branch tells the model to use. The
 * drug is asked by {@link DrugSafetyValidator#namesTheEndedOrderDrug} — the prose rule over every row of
 * its substance, so "rifampicin" or "rifampin" names a chip labelled {@code Rifampicin (rifampin)} — and
 * never as a substring of that label, which no answer writes (PR #478, review round 2). The phrase is
 * containment, so a paraphrase ("it was discontinued") reads as unstated and the sentence is appended
 * beside it — the residue runs toward saying it twice rather than toward silence, the direction
 * Decision 100 chose for the same reason. The drug predicate's own residue runs the other way, and that
 * method's javadoc states it.
 */
public final class EndedOrderStatement {

	/** The prompt's words for such an order, after {@code PatientChartSerializer.INACTIVE_ORDER_LABEL}. */
	static final String NO_LONGER_IN_FORCE = "no longer in force";

	private EndedOrderStatement() {
	}

	/**
	 * The chips about an ended order whose drug {@code answer} does not state as one — one per drug, in
	 * chip order, however many findings are about it.
	 */
	public static List<SafetyWarning> unstatedEndedOrders(String answer, List<SafetyWarning> warnings) {
		List<SafetyWarning> unstated = new ArrayList<SafetyWarning>();
		if (warnings == null || ChartSearchAiUtils.isBlank(answer)) {
			return unstated;
		}
		String[] sentences = ChartSearchAiUtils.SENTENCE_BOUNDARY.split(answer);
		Set<String> seen = new LinkedHashSet<String>();
		for (SafetyWarning warning : warnings) {
			if (!warning.isAboutAnEndedOrder() || ChartSearchAiUtils.isBlank(warning.getDrug())) {
				continue;
			}
			if (seen.add(warning.getDrug().toLowerCase(Locale.ROOT)) && !statesItEnded(sentences, warning)) {
				unstated.add(warning);
			}
		}
		return unstated;
	}

	private static boolean statesItEnded(String[] sentences, SafetyWarning warning) {
		for (String sentence : sentences) {
			if (sentence.toLowerCase(Locale.ROOT).contains(NO_LONGER_IN_FORCE)
					&& DrugSafetyValidator.namesTheEndedOrderDrug(sentence, warning)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The answer with one sentence per drug in {@code unstated} appended, or the answer unchanged where
	 * there is none.
	 *
	 * <p><b>It APPENDS and never replaces</b>, Decision 100's contract: the verdict lead is the model's,
	 * and an answer {@link #unstatedEndedOrders} reads as having said it of every drug is returned byte
	 * for byte — one naming the drug by a name no row of its substance carries, or saying it in other
	 * words, is not, and there the clinician reads it twice. The drug is printed as the chip's label, so for
	 * {@code Rifampicin (rifampin)} the sentence names both. It carries no citation marker — it
	 * offers no evidence the chips do not, and a marker would claim a record this text did not read —
	 * and it states the date only where the chip carries one. It states what the chart RECORDS, never
	 * that the drug may or may not be given: that is the finding's call, and the prompt's.
	 */
	public static String withEndedOrdersStated(String answer, List<SafetyWarning> unstated) {
		if (unstated == null || unstated.isEmpty()) {
			return answer;
		}
		// Through the shared rule and never a terminator of this method's own, as Decision 100's does.
		StringBuilder sb = new StringBuilder(DrugSafetyValidator.endSentence(answer.trim()));
		for (SafetyWarning warning : unstated) {
			sb.append(" The chart records ").append(warning.getDrug()).append(" only as an order ")
					.append(NO_LONGER_IN_FORCE);
			if (warning.getEndedOrderStopDate() != null) {
				sb.append(" (ended ").append(warning.getEndedOrderStopDate()).append(")");
			}
			sb.append(", not as a current medication.");
		}
		return sb.toString();
	}
}
