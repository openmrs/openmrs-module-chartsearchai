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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A question that lists the patient's medications before the drug it asks about is answered as that drug asked alone is
 * (ADR Decision 556). An external evaluation sheet asked the public demo's three ARV patients <em>"The patient is
 * currently on …, is it safe to give X?"</em>, and on {@code main} @ {@code 6655f965} the module, beside the bare <em>"Is it
 * safe to give X?"</em>, printed two identical lines about one combination order, and handed a drug she already takes to
 * the model, which answered rifampicin "No" over a nevirapine her chart does not hold and, for another chart, "can be
 * given" beside a Major. And a line naming her combination order by one of its displays cited the prescriptions of a
 * differently named order carrying the same substance without naming it. The charts are the demo's, each order with the
 * ATC code of its CIEL concept; the shipped knowledge base.
 */
public class ListedRegimenAnswerTest extends BaseModuleContextSensitiveTest {

	/** dd556a73: two differently named rifampicin combinations, and her ARVs and cotrimoxazole. */
	private static final List<PatientClinicalContext.ActiveDrugOrder> TWO_TB_COMBINATIONS = orders(
		"Lamivudine / zidovudine", "J05AR01", 6, "Efavirenz", "J05AG03", 7, "Isoniazid / pyrazinamide / rifampin", "J04AM05", 2,
		"Trimethoprim and sulfamethoxazole", "J01EE01", 6, "Rifampicin isoniazid pyrazinamide and ethambutol", "J04AM06", 2,
		"Lamivudine", "J05AF05", 1, "Stavudine", "J05AF04", 1);

	/** dd5558ed: one rifampicin combination, and no nevirapine or stavudine at all. */
	private static final List<PatientClinicalContext.ActiveDrugOrder> ONE_TB_COMBINATION = orders(
		"Lamivudine / zidovudine", "J05AR01", 5, "Efavirenz", "J05AG03", 5, "Trimethoprim and sulfamethoxazole", "J01EE01", 5,
		"Rifampicin isoniazid pyrazinamide and ethambutol", "J04AM06", 3);

	/** dd79194e: lopinavir/ritonavir beside nevirapine. */
	private static final List<PatientClinicalContext.ActiveDrugOrder> LOPINAVIR_RITONAVIR = orders(
		"Lamivudine / zidovudine", "J05AR01", 5, "Nevirapine", "J05AG01", 6, "Trimethoprim and sulfamethoxazole", "J01EE01", 7,
		"Lamivudine", "J05AF05", 1, "Stavudine", "J05AF04", 1, "Lopinavir / ritonavir", "J05AR10", 2, "Abacavir", "J05AF06", 2,
		"Didanosine", "J05AF02", 2);

	private static final String LISTING_HER_REGIMEN = "The patient is currently on Lamivudine / zidovudine, Efavirenz, "
			+ "Trimethoprim and sulfamethoxazole  is it safe to give ";

	private static final String LISTING_DRUGS_SHE_IS_NOT_ON = "The patient is currently on Lamivudine, Nevirapine, Stavudine, "
			+ "is it safe to give ";

	private static final String LISTING_HER_PROTEASE_INHIBITOR = "The patient is currently on Abacavir, Lopinavir / ritonavir, "
			+ "Didanosine and Trimethoprim and sulfamethoxazole is it safe to give ";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void aListedRegimenStatesEachFindingAboutOneCombinationOrderOnALineOfItsOwn() {
		String answer = ask(TWO_TB_COMBINATIONS, LISTING_HER_REGIMEN + "Metformin?");
		assertNoTwoLinesAlike(answer);
		assertTrue(answer.contains(" The interacting substance is Rifampicin.")
				&& answer.contains(" The interacting substance is Isoniazid."),
			"each line says which substance of the combination it is about: " + answer);
	}

	@Test
	public void aListedRegimenStatesTheFindingLinesTheDrugAskedAloneStates() {
		assertFindingLinesAsAskedAlone(TWO_TB_COMBINATIONS, LISTING_HER_REGIMEN, "Metformin");
		assertFindingLinesAsAskedAlone(LOPINAVIR_RITONAVIR, LISTING_HER_PROTEASE_INHIBITOR, "Amlodipine");
		assertFindingLinesAsAskedAlone(LOPINAVIR_RITONAVIR, LISTING_HER_PROTEASE_INHIBITOR, "Fluconazole");
	}

	@Test
	public void aWithholdingListedAnswerStatesEachSubstanceOfHerCombinationOnALineOfItsOwn() {
		String answer = ask(LOPINAVIR_RITONAVIR, LISTING_HER_PROTEASE_INHIBITOR + "Rifampicin?");
		assertTrue(answer.startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING), "her own Major: " + answer);
		assertNoTwoLinesAlike(answer);
		assertTrue(answer.contains(" The interacting substance is Lopinavir.")
				&& answer.contains(" The interacting substance is Ritonavir."), answer);
	}

	@Test
	public void aDrugSheTakesAskedAfterAListIsAnsweredAsHerOwnMedication() {
		String answer = ask(TWO_TB_COMBINATIONS, LISTING_HER_REGIMEN + "Rifampicin?");
		assertNotNull(answer, "the module answers, as it does the drug asked alone");
		// The list puts her listed drugs in play too (issue #513), so the check also finds that her two combinations both
		// carry rifampicin, and the lead names them with their prescriptions.
		assertTrue(answer.startsWith("Rifampicin (rifampin) is one of this patient's active medications ("
				+ "Isoniazid / pyrazinamide / rifampin, 2 orders and Rifampicin isoniazid pyrazinamide and ethambutol, 2 orders). "
				+ "5 interactions with this patient's other active medications, and its substance in more than one of this "
				+ "patient's orders:"), answer);
		assertTrue(answer.contains("\nRifampicin (rifampin) is already in active orders Isoniazid / pyrazinamide / rifampin "
				+ "(2 orders) and Rifampicin isoniazid pyrazinamide and ethambutol (2 orders) — possible duplicate therapy."),
			"the two combinations carrying it, said once: " + answer);
		assertNoTwoLinesAlike(answer);
		assertTrue(answer.contains("\nIsoniazid, Pyrazinamide and Rifampicin (rifampin) are in active orders Isoniazid / "
				+ "pyrazinamide / rifampin (2 orders) and Rifampicin isoniazid pyrazinamide and ethambutol (2 orders) — possible "
				+ "duplicate therapy."), "and which of their substances the two combinations both carry: " + answer);
		assertFalse(answer.contains("can be given") || answer.contains("No —"), "no verdict on her own drug: " + answer);
		assertEquals(findingLines(ask(TWO_TB_COMBINATIONS, "Is it safe to give Rifampicin?")), findingLines(answer),
			"the findings the drug asked alone states, and no others: " + answer);
	}

	@Test
	public void aDrugSheTakesAskedAfterAListOfDrugsSheIsNotOnStatesWhatTheListAdds() {
		String answer = ask(ONE_TB_COMBINATION, LISTING_DRUGS_SHE_IS_NOT_ON + "Rifampicin?");
		assertNotNull(answer, "the module answers");
		assertTrue(answer.startsWith("Rifampicin (rifampin) is one of this patient's active medications ("
				+ "Rifampicin isoniazid pyrazinamide and ethambutol, 3 orders). "), answer);
		assertNoTwoLinesAlike(answer);
		assertFalse(answer.contains("can be given") || answer.contains("No —"), "no verdict on her own drug: " + answer);
		String[] lines = answer.split("\n");
		// The pair is raised from whichever side the data rates it: the question's own pair, or the listed drug against her
		// order of rifampicin.
		assertTrue(Arrays.asList(lines).stream().anyMatch(line -> line.matches("(Rifampicin \\(rifampin\\) interacts with "
				+ "Nevirapine, also named in the question|Nevirapine interacts with active order Rifampin \\(3 orders\\)) — Major\\.( \\[\\d+\\])+")),
			"the finding between the listed drug she is not on and rifampicin, stated: " + answer);
		assertFalse(answer.contains("against Nevirapine"), "nevirapine is never said to have raised nothing: " + answer);
		assertTrue(answer.contains("\nThe check of Rifampicin (rifampin) against Stavudine, also named in the question, raised "
				+ "no finding."), "the listed drug the check raised nothing against: " + answer);
		for (String line : findingLines(ask(ONE_TB_COMBINATION, "Is it safe to give Rifampicin?"))) {
			assertTrue(findingLines(answer).contains(line), "every finding the drug asked alone states — " + line + ": "
					+ answer);
		}
		String lead = lines[0];
		assertTrue(lead.contains(" interactions with this patient's other active medications"), lead);
		assertEquals(Integer.parseInt(lead.replaceAll("^.*\\. (\\d+) interactions with .*$", "$1")),
			findingLines(ask(ONE_TB_COMBINATION, "Is it safe to give Rifampicin?")).size(),
			"the count is of her orders' findings, never the listed drug's: " + answer);
	}

	@Test
	public void theListsOwnFindingsAreNotCountedUnderTheLeadsCountOfHerMedications() {
		String answer = ask(ONE_TB_COMBINATION, LISTING_DRUGS_SHE_IS_NOT_ON + "Rifampicin?");
		List<String> lines = Arrays.asList(answer.split("\n"));
		int heading = lines.indexOf(DrugReferenceInjector.LISTED_NOT_HERS_HEADING);
		assertTrue(heading > 0, "the list's findings under a heading of their own: " + answer);
		int nevirapine = -1;
		for (int n = 0; n < lines.size(); n++) {
			if (lines.get(n).startsWith("Nevirapine interacts with")
					|| lines.get(n).startsWith("Rifampicin (rifampin) interacts with Nevirapine")) {
				nevirapine = n;
			}
		}
		assertTrue(nevirapine > heading, "the nevirapine finding after that heading: " + answer);
		for (int n = 1; n < heading; n++) {
			assertFalse(lines.get(n).contains("Nevirapine"), "none of the list's findings among hers: " + answer);
		}
	}

	@Test
	public void aListWhoseQuestionPairsTheCapCutIsNeverSaidToHaveRaisedNothing() {
		// Drugs she is not on whose pairs among themselves fill the question-pair arm's cap, beside stavudine, which nothing
		// the data rates relates to rifampicin: a withheld pair may be stavudine's, so "raised no finding" would be a guess.
		String answer = ask(ONE_TB_COMBINATION, "The patient is currently on Ketoconazole, Itraconazole, Clarithromycin, "
				+ "Simvastatin, Amiodarone, Erythromycin, Lovastatin and Stavudine, is it safe to give Rifampicin?");
		assertNull(answer, "nothing is said to be unrelated where the screen of the question's pairs was cut, so the model "
				+ "answers: " + answer);
	}

	@Test
	public void aListedDrugWhoseFindingNamesAPartnerTheModuleCannotResolveIsNeverSaidToHaveRaisedNothing() {
		// Two NRTIs: the class arm's finding about the listed lamivudine names her zidovudine order with no partner rows.
		// Her cotrimoxazole is what zidovudine interacts with, so the module answers about her own zidovudine.
		List<PatientClinicalContext.ActiveDrugOrder> zidovudine = orders("Zidovudine", "J05AF01", 1,
			"Trimethoprim and sulfamethoxazole", "J01EE01", 1);
		String answer = ask(zidovudine, "The patient is currently on Lamivudine, is it safe to give Zidovudine?");
		assertNull(answer, "a relationship the module could not resolve is never stated as none, so the model answers: "
				+ answer);
	}

	@Test
	public void aLineNamingHerCombinationByOneDisplayNamesTheOtherOrderCarryingItsSubstance() {
		String line = lineAbout(ask(TWO_TB_COMBINATIONS, "Is it safe to give Metformin?"), "The interacting substance is Rifampicin.");
		assertTrue(line.matches("Metformin interacts with active order Isoniazid / pyrazinamide / rifampin \\(2 orders\\) — "
				+ "Moderate\\. The interacting substance is Rifampicin\\. The same interaction holds of active order "
				+ "Rifampicin isoniazid pyrazinamide and ethambutol \\(2 orders\\)\\.( \\[\\d+\\]){5}"),
			"the other order is named, counted, and the four prescriptions cited: " + line);
	}

	@Test
	public void aLineNamingThePartnerBySubstanceNamesNoOtherOrder() {
		String answer = ask(ONE_TB_COMBINATION, "Is it safe to give Metformin?");
		assertTrue(answer.contains("\nMetformin interacts with active order Rifampin (3 orders) — Moderate."), answer);
		assertFalse(answer.contains(DrugReferenceInjector.SAME_INTERACTION_OPENING),
			"every order behind the substance's own name is counted under it already: " + answer);
	}

	private static void assertFindingLinesAsAskedAlone(List<PatientClinicalContext.ActiveDrugOrder> orders, String listing,
			String drug) {
		List<String> alone = findingLines(ask(orders, "Is it safe to give " + drug + "?"));
		List<String> listed = findingLines(ask(orders, listing + drug + "?"));
		assertFalse(alone.isEmpty(), "precondition: the drug asked alone states findings");
		assertEquals(new HashSet<String>(alone), new HashSet<String>(listed),
			drug + " — the same findings, word for word: alone " + alone + " listed " + listed);
	}

	private static void assertNoTwoLinesAlike(String answer) {
		assertNotNull(answer, "the module answers");
		Set<String> seen = new HashSet<String>();
		for (String line : answer.split("\n")) {
			assertTrue(seen.add(withoutMarkers(line)), "a line stated twice — " + line + ": " + answer);
		}
	}

	/** The answer's lines stating an interaction of the drug asked about with her orders, markers dropped: the finding
	 *  record numbers differ between two questions, and the words are what a clinician compares. */
	private static List<String> findingLines(String answer) {
		assertNotNull(answer, "the module answers");
		List<String> lines = new ArrayList<String>();
		for (String line : answer.split("\n")) {
			if (line.contains(DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE)) {
				lines.add(withoutMarkers(line));
			}
		}
		return lines;
	}

	private static String lineAbout(String answer, String words) {
		assertNotNull(answer, "the module answers");
		for (String line : answer.split("\n")) {
			if (line.contains(words)) {
				return line;
			}
		}
		throw new AssertionError("precondition: a line says " + words + ": " + answer);
	}

	private static String withoutMarkers(String line) {
		return line.replaceAll("( \\[\\d+\\])+$", "");
	}

	private static String ask(List<PatientClinicalContext.ActiveDrugOrder> orders, String question) {
		Set<String> names = new LinkedHashSet<String>();
		Set<String> codes = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
			codes.addAll(order.getAtcCodes());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(40, null, names, codes, null, null, orders), question)
				.getModuleAnswer();
	}

	/** Display, the ATC code of its concept, and how many prescriptions of it her chart holds, never stopped. */
	private static List<PatientClinicalContext.ActiveDrugOrder> orders(Object... displayCodeAndCount) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		for (int i = 0; i < displayCodeAndCount.length; i += 3) {
			String display = (String) displayCodeAndCount[i];
			for (int n = 1; n <= (Integer) displayCodeAndCount[i + 2]; n++) {
				orders.add(DrugReferenceTestSupport.activeOrder("order-" + display.replaceAll("\\W", "") + "-" + n, display,
					DrugReferenceTestSupport.set(display), DrugReferenceTestSupport.set((String) displayCodeAndCount[i + 1])));
			}
		}
		return orders;
	}
}
