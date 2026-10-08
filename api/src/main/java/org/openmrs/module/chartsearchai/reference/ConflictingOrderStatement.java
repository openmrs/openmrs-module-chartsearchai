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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * the arm could name ({@link SafetyWarning#currentOrderDisplays()}) is stated after the answer, set apart
 * from it by a blank line under {@link #HEADING}, one item per set of her orders as her chart spells them
 * (ADR Decision 173), and one per order where the item says only {@link #OWN_ALLERGY}. An order whose every finding is an allergy recorded to its very drug
 * ({@link SafetyWarning#isARecordedAllergyToItsOwnDrug()}) is followed by {@link #OWN_ALLERGY} alone,
 * <em>"- Lidocaine [4]: recorded allergy to this drug."</em> — the chips' own sentences repeat the allergy
 * list the answer just gave, and the conflict is the one fact the list lacks (ADR Decision 167). Any other
 * order is followed by each chip's own {@link SafetyWarning#getDetail()} verbatim, as Decision 124 states
 * it: a cross-reactivity or rule finding is an allergy to ANOTHER drug or no allergy at all, so only its
 * own words claim exactly what it does — which is also why the heading says "records" and not
 * "allergies", and "current" and not "active", since an order that has not started is named with its
 * start date. Every such chip is then
 * {@link SafetyWarning#asStatedInTheAnswer() marked}, and still published: the wire keeps every finding,
 * and a client reads {@code statedInTheAnswer} to know the clinician has read it.
 * APPENDS, as {@code EndedOrderStatement} does: no prompt change, and never a word about whether any drug may
 * be given. Each order it names cites the record that order IS, where one record unambiguously is
 * ({@link org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart#getOrderRecordNumbers()},
 * the injector's rule), and the records it cites are handed back ({@link Stated#getCitedOrderRecords()}) for
 * the references to carry as the module's — ADR Decision 168.
 *
 * <p><b>When.</b> Only where the question asks about her allergies and neither about her medications nor
 * for a drug-safety reading ({@link #asksOnlyForAllergies}), and only where EVERY chip on the answer is
 * such a finding. A chip of any other kind — a drug the question proposes, an interaction, a finding the
 * arm could name no order for — means the response is carrying a drug-safety reading, and then every
 * chip stays a chip and the answer is untouched: a statement covering some of a response's findings
 * would read as covering all of them.
 */
public final class ConflictingOrderStatement {

	/** The line that opens the statement, after a blank line, above one item per set of her orders. */
	static final String HEADING = "Current orders that conflict with the patient's records:";

	/** What opens each item, on its own line. */
	static final String ITEM = "\n- ";

	/** What follows an order whose every finding is an allergy recorded to its own drug, in place of them. */
	static final String OWN_ALLERGY = ": recorded allergy to this drug.";

	private ConflictingOrderStatement() {
	}

	/** The answer and chips after {@link #state}: either both as handed, or both completed. */
	public static final class Stated {

		private final String answer;

		private final List<SafetyWarning> warnings;

		private final Map<Integer, List<Integer>> citedOrderRecords;

		private Stated(String answer, List<SafetyWarning> warnings, Map<Integer, List<Integer>> citedOrderRecords) {
			this.answer = answer;
			this.warnings = warnings;
			this.citedOrderRecords = citedOrderRecords;
		}

		/**
		 * Each order record the statement cites, with the record numbers of the findings it states about that
		 * order, in the order cited — the input {@code LlmInferenceService.extractCitedReferences} attaches as the
		 * module's. Empty, never null, where nothing was stated or no order named had a record to cite.
		 */
		public Map<Integer, List<Integer>> getCitedOrderRecords() {
			return citedOrderRecords;
		}

		public String getAnswer() {
			return answer;
		}

		public List<SafetyWarning> getWarnings() {
			return warnings;
		}
	}

	/**
	 * @param orderRecordNumbers the record each of her orders' displays is, from the chart the injector built
	 * @return {@code answer} with her conflicting orders stated and every chip it states marked, or both
	 *         unchanged where the question or the chips do not qualify — see this class's javadoc.
	 */
	public static Stated state(String question, String answer, List<SafetyWarning> warnings,
			Map<String, Integer> orderRecordNumbers) {
		Stated unchanged = new Stated(answer, warnings, Collections.<Integer, List<Integer>> emptyMap());
		if (answer == null || warnings == null || warnings.isEmpty() || !asksOnlyForAllergies(question)) {
			return unchanged;
		}
		// One group per set of orders, in chip order, so two findings about one order name it once.
		Map<List<String>, List<SafetyWarning>> chipsByOrders = new LinkedHashMap<List<String>, List<SafetyWarning>>();
		for (SafetyWarning chip : warnings) {
			if (!SafetyWarning.TYPE_CONTRAINDICATION.equals(chip.getType()) || !chip.isAboutACurrentMedication()
					|| chip.currentOrderDisplays().isEmpty()) {
				return unchanged;
			}
			List<SafetyWarning> chips = chipsByOrders.get(chip.currentOrderDisplays());
			if (chips == null) {
				chips = new ArrayList<SafetyWarning>();
				chipsByOrders.put(chip.currentOrderDisplays(), chips);
			}
			chips.add(chip);
		}
		Map<Integer, List<Integer>> cited = new LinkedHashMap<Integer, List<Integer>>();
		// Own-allergy items first, then the rest, each in chip order: Decision 167's line led the statement. An
		// own-allergy item is one order, so an order two such findings name is named once.
		Set<String> ownAllergy = new LinkedHashSet<String>();
		StringBuilder quoted = new StringBuilder();
		for (Map.Entry<List<String>, List<SafetyWarning>> group : chipsByOrders.entrySet()) {
			List<String> orders = new ArrayList<String>(group.getKey().size());
			for (String printed : group.getKey()) {
				orders.add(cite(printed, group.getValue(), orderRecordNumbers, cited));
			}
			if (everyOneIsAnAllergyToItsOwnDrug(group.getValue())) {
				for (String order : orders) {
					ownAllergy.add(ITEM + order + OWN_ALLERGY);
				}
				continue;
			}
			// Semicolons between orders, since a display can carry a comma.
			quoted.append(ITEM).append(String.join("; ", orders)).append(':');
			for (SafetyWarning chip : group.getValue()) {
				quoted.append(' ').append(DrugSafetyValidator.endSentence(chip.getDetail().trim()));
			}
		}
		StringBuilder sb = new StringBuilder(DrugSafetyValidator.endSentence(answer.trim())).append("\n\n").append(HEADING);
		for (String item : ownAllergy) {
			sb.append(item);
		}
		sb.append(quoted);
		List<SafetyWarning> stated = new ArrayList<SafetyWarning>(warnings.size());
		for (SafetyWarning chip : warnings) {
			stated.add(chip.asStatedInTheAnswer());
		}
		// Trimmed so a blank answer takes the statement without a leading space.
		return new Stated(sb.toString().trim(), Collections.unmodifiableList(stated), Collections.unmodifiableMap(cited));
	}

	/**
	 * @return {@code printed} followed by the marker of the record its order is, where the injector numbered
	 *         one, recording that record in {@code cited} with the findings {@code chips} state about it; else
	 *         {@code printed} alone, which is what an order two prescriptions share a display with, or one no
	 *         record unambiguously is, reads.
	 */
	private static String cite(String printed, List<SafetyWarning> chips, Map<String, Integer> orderRecordNumbers,
			Map<Integer, List<Integer>> cited) {
		Integer number = orderRecordNumbers == null ? null
				: orderRecordNumbers.get(chips.get(0).orderDisplayPrintedAs(printed));
		if (number == null) {
			return printed;
		}
		List<Integer> findings = cited.get(number);
		if (findings == null) {
			findings = new ArrayList<Integer>();
			cited.put(number, findings);
		}
		for (SafetyWarning chip : chips) {
			if (chip.getFindingCitation() != null && !findings.contains(chip.getFindingCitation())) {
				findings.add(chip.getFindingCitation());
			}
		}
		return printed + " [" + number + "]";
	}

	/** Whether every one of {@code chips}, all about one set of her orders, is an allergy to its own drug. */
	private static boolean everyOneIsAnAllergyToItsOwnDrug(List<SafetyWarning> chips) {
		for (SafetyWarning chip : chips) {
			if (!chip.isARecordedAllergyToItsOwnDrug()) {
				return false;
			}
		}
		return true;
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
