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

import org.openmrs.module.chartsearchai.api.impl.QueryScopeRouter;

/**
 * The module's own statement, in the answer, of which of the patient's active orders conflict with her
 * records — on a question that asks only for her ALLERGIES. ADR Decision 121.
 *
 * <p><b>The gap this closes.</b> <em>"any allergies?"</em>, asked of a patient prescribed two drugs she is
 * recorded as allergic to, came back with a complete allergy list and two red contraindication chips
 * beside it, each repeating an allergy the list had just named and tagged "About a current medication".
 * The allergy question widens the active-order contraindication arm to her allergy records on purpose
 * ({@code DrugSafetyValidator.SubjectMatter}), and what that arm found — that two of the allergens are
 * drugs she is prescribed — is the one fact about her allergies the list does not carry. It reached the
 * clinician only as a drug-safety alert beside an answer to a question that asked for none.
 *
 * <p><b>What it does.</b> Each contraindication chip about a medication she already takes whose orders
 * the arm could name ({@link SafetyWarning#currentOrderDisplays()}) is stated after the answer: her
 * order as her chart spells it, then the chip's own {@link SafetyWarning#getDetail()} verbatim — never a
 * paraphrase, so the sentence claims exactly what the finding does and no strength of its own. Every
 * such chip is then {@link SafetyWarning#asStatedInTheAnswer() marked}, and still published: the wire
 * keeps every finding, and a client reads {@code statedInTheAnswer} to know the clinician has read it.
 * APPENDS, as {@code EndedOrderStatement} does: no marker, since it offers no record of its own; no
 * prompt change; and never a word about whether any drug may be given.
 *
 * <p><b>When.</b> Only where the question asks about her allergies and neither about her medications nor
 * for a drug-safety reading ({@link #asksOnlyForAllergies}), and only where EVERY chip on the answer is
 * such a finding. A chip of any other kind — a drug the question proposes, an interaction, a finding the
 * arm could name no order for — means the response is carrying a drug-safety reading, and then every
 * chip stays a chip and the answer is untouched: a statement covering some of a response's findings
 * would read as covering all of them.
 */
public final class ConflictingOrderStatement {

	/** The words that introduce her order, before its display. */
	static final String CURRENTLY_PRESCRIBED = "Currently prescribed: ";

	private ConflictingOrderStatement() {
	}

	/** The answer and chips after {@link #state}: either both as handed, or both completed. */
	public static final class Stated {

		private final String answer;

		private final List<SafetyWarning> warnings;

		private Stated(String answer, List<SafetyWarning> warnings) {
			this.answer = answer;
			this.warnings = warnings;
		}

		public String getAnswer() {
			return answer;
		}

		public List<SafetyWarning> getWarnings() {
			return warnings;
		}
	}

	/**
	 * @return {@code answer} with her conflicting orders stated and every chip it states marked, or both
	 *         unchanged where the question or the chips do not qualify — see this class's javadoc.
	 */
	public static Stated state(String question, String answer, List<SafetyWarning> warnings) {
		Stated unchanged = new Stated(answer, warnings);
		if (answer == null || warnings == null || warnings.isEmpty() || !asksOnlyForAllergies(question)) {
			return unchanged;
		}
		// One group per set of orders, in chip order, so two findings about one order name it once.
		Map<List<String>, List<String>> detailsByOrders = new LinkedHashMap<List<String>, List<String>>();
		for (SafetyWarning chip : warnings) {
			if (!SafetyWarning.TYPE_CONTRAINDICATION.equals(chip.getType()) || !chip.isAboutACurrentMedication()
					|| chip.currentOrderDisplays().isEmpty()) {
				return unchanged;
			}
			List<String> details = detailsByOrders.get(chip.currentOrderDisplays());
			if (details == null) {
				details = new ArrayList<String>();
				detailsByOrders.put(chip.currentOrderDisplays(), details);
			}
			details.add(DrugSafetyValidator.endSentence(chip.getDetail().trim()));
		}
		StringBuilder sb = new StringBuilder(DrugSafetyValidator.endSentence(answer.trim()));
		for (Map.Entry<List<String>, List<String>> group : detailsByOrders.entrySet()) {
			sb.append(' ').append(CURRENTLY_PRESCRIBED).append(String.join(", ", group.getKey())).append('.');
			for (String detail : group.getValue()) {
				sb.append(' ').append(detail);
			}
		}
		List<SafetyWarning> stated = new ArrayList<SafetyWarning>(warnings.size());
		for (SafetyWarning chip : warnings) {
			stated.add(chip.asStatedInTheAnswer());
		}
		// Trimmed so a blank answer takes the statement without a leading space.
		return new Stated(sb.toString().trim(), Collections.unmodifiableList(stated));
	}

	/**
	 * Whether {@code question} asks about the patient's allergies and neither about her medications
	 * ({@link QueryScopeRouter#asksAboutMedications}) nor for a drug-safety reading
	 * ({@link QueryScopeRouter#asksForADrugSafetyReading}) — the one question on which a finding about her
	 * own prescription is an answer to what was asked rather than an alert beside it.
	 */
	static boolean asksOnlyForAllergies(String question) {
		return question != null && QueryScopeRouter.asksAboutAllergies(question)
				&& !QueryScopeRouter.asksAboutMedications(question)
				&& !QueryScopeRouter.asksForADrugSafetyReading(question);
	}
}
