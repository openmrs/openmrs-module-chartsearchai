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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.module.chartsearchai.api.impl.QueryScopeRouter;

/**
 * The module's own statement, in the answer, of which of the patient's active orders directly conflict with her
 * records — on a question that asks only for her ALLERGIES. ADR Decisions 124, 188.
 *
 * <p><b>The gap this closes.</b> <em>"any allergies?"</em>, asked of a patient prescribed two drugs she is
 * recorded as allergic to, came back with a complete allergy list and two red contraindication chips
 * beside it, each repeating an allergy the list had just named and tagged "About a current medication".
 * The allergy question widens the active-order contraindication arm to her allergy records on purpose
 * ({@code DrugSafetyValidator.SubjectMatter}), and what that arm found — that two of the allergens are
 * drugs she is prescribed — is the one fact about her allergies the list does not carry. It reached the
 * clinician only as a drug-safety alert beside an answer to a question that asked for none.
 *
 * <p><b>What it does.</b> Each of her orders a contraindication chip is about is stated after the answer, set apart
 * from it by a blank line under {@link #HEADING}, one item per set of her orders as her chart spells them (ADR
 * Decision 173) — except for a chip that is a cross-reactivity finding ({@link SafetyWarning#isACrossReactivityFinding()}):
 * an allergy to ANOTHER drug of the class is not an answer to a question asking for her allergies, so it stays a chip
 * (ADR Decision 188, which narrowed Decision 124's statement to this), and an order only such chips are about is not
 * named. Where every finding stated about an order is an allergy recorded to its very drug
 * ({@link SafetyWarning#isARecordedAllergyToItsOwnDrug()}) the order is followed by {@link #OWN_ALLERGY} alone,
 * <em>"- Lidocaine [4]: recorded allergy to this drug."</em> (ADR Decision 167); otherwise by each finding's own
 * sentence verbatim, since a rule finding or an allergy recorded under another name claims only what its own words
 * do — which is also why the heading says "records" and not "allergies". It says "directly", so a list leaving the
 * cross-reactivity chips out does not read as every order her records touch, and each such chip is
 * {@link SafetyWarning#asAboutAnotherOfHerMedications() drawn apart} from the answer, whether or not anything is
 * stated: a finding about her medications beside a list of her allergies. Only the chips it states are
 * {@link SafetyWarning#asStatedInTheAnswer() marked}, and every chip is still published: the wire keeps every
 * finding, and a client reads {@code statedInTheAnswer} to know the clinician has read it.
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
 * would read as covering all of them. (Within a qualifying response the statement leaves the cross-reactivity
 * findings to their chips, and says "directly" in its heading for that reason — ADR Decision 188.)
 */
public final class ConflictingOrderStatement {

	/** The line that opens the statement, after a blank line, above one item per set of her orders. */
	static final String HEADING = "Current orders that directly conflict with the patient's records:";

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
		// A cross-reactivity finding is an allergy to ANOTHER drug of the class, and stays a chip (ADR Decision 188);
		// every other finding is stated. Short-line items first, each one order, then the quoted ones, each in chip
		// order, as Decision 167's line led the statement.
		Map<Integer, List<Integer>> cited = new LinkedHashMap<Integer, List<Integer>>();
		Set<String> ownAllergy = new LinkedHashSet<String>();
		StringBuilder quoted = new StringBuilder();
		Set<SafetyWarning> statedChips = new HashSet<SafetyWarning>();
		for (Map.Entry<List<String>, List<SafetyWarning>> group : chipsByOrders.entrySet()) {
			List<SafetyWarning> own = notCrossReactive(group.getValue());
			if (own.isEmpty()) {
				continue;
			}
			statedChips.addAll(own);
			List<String> orders = new ArrayList<String>(group.getKey().size());
			for (String printed : group.getKey()) {
				orders.add(cite(printed, own, orderRecordNumbers, cited));
			}
			if (everyOneNamesItsDrug(own)) {
				for (String order : orders) {
					ownAllergy.add(ITEM + order + OWN_ALLERGY);
				}
				continue;
			}
			// Semicolons between orders, since a display can carry a comma.
			quoted.append(ITEM).append(String.join("; ", orders)).append(':');
			for (SafetyWarning chip : own) {
				quoted.append(' ').append(DrugSafetyValidator.endSentence(chip.getDetail().trim()));
			}
		}
		// A cross-reactivity chip is about her medications and not about what was asked, so it is drawn apart from the
		// answer (ADR Decision 188) — whether or not anything else is stated.
		List<SafetyWarning> published = new ArrayList<SafetyWarning>(warnings.size());
		for (SafetyWarning chip : warnings) {
			published.add(statedChips.contains(chip) ? chip.asStatedInTheAnswer()
					: chip.isACrossReactivityFinding() ? chip.asAboutAnotherOfHerMedications() : chip);
		}
		if (statedChips.isEmpty()) {
			return new Stated(answer, Collections.unmodifiableList(published), Collections.<Integer, List<Integer>> emptyMap());
		}
		StringBuilder sb = new StringBuilder(DrugSafetyValidator.endSentence(answer.trim())).append("\n\n").append(HEADING);
		for (String item : ownAllergy) {
			sb.append(item);
		}
		sb.append(quoted);
		// Trimmed so a blank answer takes the statement without a leading space.
		return new Stated(sb.toString().trim(), Collections.unmodifiableList(published), Collections.unmodifiableMap(cited));
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

	/** The ones of {@code chips}, all about one set of her orders, that are not a cross-reactivity finding. */
	private static List<SafetyWarning> notCrossReactive(List<SafetyWarning> chips) {
		List<SafetyWarning> direct = new ArrayList<SafetyWarning>();
		for (SafetyWarning chip : chips) {
			if (!chip.isACrossReactivityFinding()) {
				direct.add(chip);
			}
		}
		return direct;
	}

	/** Whether every one of {@code chips} says the recorded allergy NAMES its drug, so the short line is true. */
	private static boolean everyOneNamesItsDrug(List<SafetyWarning> chips) {
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
