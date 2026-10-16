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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A line naming her combination order says which of its substances it is about, in every answer listing a drug's findings
 * against her orders, and what the interaction with one of its drugs is takes the whole order (ADR Decision 288).
 * The knowledge base relates a drug to each substance of a combination in a row of its own, and a line names the order by
 * its display: on the 3.7.1 standalone Ruth, on lopinavir/ritonavir, read "Rifampicin (rifampin) interacts with active
 * order Lopinavir / ritonavir 200mg/50mg — Major." twice under "Does fluconazole interact with rifampicin?", and under
 * "Does her didanosine interact with her lopinavir?" one such line rated Moderate and one rated Minor. Over the shipped
 * knowledge base, her orders read as the context builder read the standalone's: each order's display and its concept's
 * names, the concept's ATC code and the concept itself.
 */
public class CombinationOrderLineContextTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> RUTH = Arrays.asList(
		order("order-abacavir", "Abacavir 300mg", "J05AF06", "70056AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "Abacavir"),
		order("order-lpvr", "Lopinavir / ritonavir 200mg/50mg", "J05AR10", "794AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
			"Lopinavir / ritonavir"),
		order("order-didanosine", "Didanosine 400mg", "J05AF02", "74807AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "Didanosine"),
		order("order-ctx", "Cotrimoxazole 960mg", "J01EE01", "105281AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
			"Sulfamethoxazole / trimethoprim", "Cotrimoxazole", "Co-trimoxazole", "Bactrim", "Septrin"));

	private static final String HER_LPVR = " interacts with active order Lopinavir / ritonavir 200mg/50mg — ";

	private static final String NAMED = DrugReferenceInjector.INTERACTING_SUBSTANCE_OPENING;

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void twoOfHerDrugsNameTheSubstanceOfHerCombinationOrderEachLineIsAbout() {
		String answer = ask("Does her didanosine interact with her lopinavir?");
		assertTrue(answer.contains("\nDidanosine" + HER_LPVR + "Moderate." + NAMED + "Lopinavir. ["), "was: " + answer);
		assertTrue(answer.contains("\nDidanosine" + HER_LPVR + "Minor." + NAMED + "Ritonavir. ["), "was: " + answer);
	}

	/**
	 * A line naming her combination order stands alone and still names its substance: "Any interaction between her
	 * lopinavir and cotrimoxazole?" stated the two Moderate findings about the ritonavir in that order under its display
	 * alone, below the lopinavir's own Minor finding, so they read as a second rating of the same pair.
	 */
	@Test
	public void aLineNamingHerCombinationOrderByItsDisplayNamesItsSubstanceStandingAlone() {
		String answer = ask("Any interaction between her lopinavir and cotrimoxazole?");
		for (String drug : new String[] { "Sulfamethoxazole", "Trimethoprim" }) {
			String line = lineStarting(answer, drug + HER_LPVR + "Moderate.");
			assertTrue(line != null && line.matches(".*" + java.util.regex.Pattern.quote(NAMED + "Ritonavir.") + "( \\[\\d+\\])+"),
				drug + ": " + answer);
		}
		assertTrue(lineStarting(answer, "Lopinavir interacts with active order Sulfamethoxazole — Minor.") != null,
			"the lopinavir's own line is as it was: " + answer);
	}

	/** The line of {@code answer} starting {@code start}, or {@code null}. */
	private static String lineStarting(String answer, String start) {
		for (String line : answer.split("\n")) {
			if (line.startsWith(start)) {
				return line;
			}
		}
		return null;
	}

	@Test
	public void twoDrugsNeitherOfThemHersNameTheSubstanceOfHerCombinationOrderEachLineIsAbout() {
		String answer = ask("Does fluconazole interact with rifampicin?");
		assertTrue(answer.contains("\nRifampicin (rifampin)" + HER_LPVR + "Major." + NAMED + "Lopinavir. ["), "was: " + answer);
		assertTrue(answer.contains("\nRifampicin (rifampin)" + HER_LPVR + "Major." + NAMED + "Ritonavir. ["), "was: " + answer);
		assertTrue(answer.contains("\nFluconazole" + HER_LPVR + "Moderate." + NAMED + "Lopinavir. ["), "was: " + answer);
		assertTrue(answer.contains("\nFluconazole" + HER_LPVR + "Minor." + NAMED + "Ritonavir. ["), "was: " + answer);
	}

	/**
	 * A switch away from the lopinavir of her lopinavir/ritonavir replaces the lopinavir, and what relates the new drug to
	 * the ritonavir stays among its cautions — a ritonavir may stay, boosting the drug switched to — each line naming the
	 * substance it is about: the answer printed one line twice, once among atazanavir's cautions and once under the order
	 * replaced.
	 */
	@Test
	public void aSwitchAwayFromADrugSheTakesInACombinationNamesTheSubstanceOfEachLine() {
		String answer = ask("Can I switch her lopinavir to atazanavir?");
		assertTrue(answer.startsWith("Lopinavir is one of this patient's active medications, and this switch replaces it.\n"
				+ DrugReferenceInjector.cautionLead(2, "Atazanavir") + "\n"), "was: " + answer);
		int replaced = answer.indexOf("\nAbout the order the switch replaces:\n");
		assertTrue(replaced > 0, "was: " + answer);
		int ritonavir = answer.indexOf("\nAtazanavir" + HER_LPVR + "Moderate." + NAMED + "Ritonavir. [");
		assertTrue(ritonavir > 0 && ritonavir < replaced, "a caution for the new drug: " + answer);
		assertTrue(answer.indexOf("\nAtazanavir" + HER_LPVR + "Moderate." + NAMED + "Lopinavir. [") > replaced, "was: " + answer);
	}

	/** And what the interaction between a drug and "her lopinavir" is, is what relates it to that order, each substance of
	 *  it stated whole (ADR Decisions 288, 289). */
	@Test
	public void whatTheInteractionWithADrugSheTakesInACombinationIsStatesEachSubstanceOfThatOrder() {
		String answer = ask("What is the interaction between rifampicin and her lopinavir?");
		assertTrue(answer.startsWith("Lopinavir is one of this patient's active medications.\nWhat the interaction check relates "
				+ "between Rifampicin (rifampin) and Lopinavir:\n"), "was: " + answer);
		assertTrue(answer.contains("\nRifampicin (rifampin)" + HER_LPVR + "Major. Coadministration with rifampin may "
				+ "significantly decrease the plasma concentrations of lopinavir"), "was: " + answer);
		assertTrue(answer.contains("\nRifampicin (rifampin)" + HER_LPVR + "Major. Coadministration with rifampin may decrease "
				+ "the plasma concentrations of ritonavir"), "was: " + answer);
		assertTrue(answer.contains(NAMED + "Lopinavir. [") && answer.contains(NAMED + "Ritonavir. ["), "was: " + answer);
	}

	/**
	 * A line naming the order by one of the substance's own names says nothing more: on the 3.7.1 standalone a screen of
	 * a patient on two TB combinations named one of them by DDInter's "Rifampin", and the first form of this decision then
	 * read "Efavirenz interacts with active order Rifampin — Moderate. The interacting substance is Rifampicin."
	 */
	@Test
	public void aLineNamingTheOrderByOneOfTheSubstancesOwnNamesSaysNothingMore() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			order("order-3tc-zdv", "Lamivudine / zidovudine 150mg/300mg", "J05AR01", "630AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
				"Lamivudine / zidovudine", "Combivir", "Zidovudine and lamivudine"),
			order("order-efv", "Efavirenz 600mg", "J05AG03", "75523AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "Efavirenz", "Stocrin"),
			order("order-ctx", "Cotrimoxazole 960mg", "J01EE01", "105281AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
				"Sulfamethoxazole / trimethoprim", "Cotrimoxazole", "Co-trimoxazole", "Bactrim", "Septrin"),
			order("order-rhz", "Isoniazid / pyrazinamide / rifampin 75/400/150mg", null, "768AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
				"Isoniazid / pyrazinamide / rifampin", "Rifampicin isoniazid and pyrazinamide", "Rifater"),
			order("order-rhze", "Rifampicin isoniazid pyrazinamide and ethambutol 150/75/400/275mg", "J04AM06",
				"1131AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "Rifampicin isoniazid pyrazinamide and ethambutol", "RHEZ", "Rifafour"),
			order("order-d4t", "Stavudine 30mg", "J05AF04", "1ae08332-8f70-4345-a793-3b6c9b0ff6bd", "Stavudine", "d4T", "Zerit"));
		String answer = askOver("Do her TB drugs interact with her ARVs?", orders);
		assertTrue(answer.contains(" interacts with active order Rifampin — "), "precondition: was: " + answer);
		for (String line : answer.split("\n")) {
			assertTrue(!(line.contains("active order Rifampin — ") && line.contains(NAMED + "Rifampicin.")), "was: " + answer);
		}
	}

	/**
	 * Nor does a line naming a one-substance order by its display, its salt and its strength: on the 3.7.1 standalone
	 * Brian Lopez's "Will omeprazole lower his levothyroxine absorption?" read "Omeprazole interacts with active order
	 * Levothyroxine sodium 75 mcg — Moderate. The interacting substance is Levothyroxine." The order's display names no
	 * other substance the module resolved it to.
	 */
	@Test
	public void aLineNamingAOneSubstanceOrderByItsDisplaySaysNothingMore() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			order("order-omeprazole", "Omeprazole Co 20mg", null, "df3f7c1f-dac8-429b-ba38-90d770c1253c", "Omeprazole"),
			order("order-levothyroxine", "Levothyroxine sodium 75 mcg", "H03AA01", "78809AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
				"Levothyroxine sodium", "Eltroxin", "Euthyroid"));
		String answer = askOver("Will omeprazole lower his levothyroxine absorption?", orders);
		String line = lineStarting(answer, "Omeprazole interacts with active order Levothyroxine sodium 75 mcg — Moderate.");
		assertTrue(line != null, "precondition: was: " + answer);
		assertTrue(!line.contains(NAMED), "was: " + answer);
	}

	/**
	 * A question about her lopinavir, which she takes as lopinavir/ritonavir, is answered for that order: its ritonavir's
	 * findings too, the lead saying so (ADR Decision 292). On the 3.7.1 standalone "Is she on lopinavir?" of Ruth listed
	 * lopinavir's two findings and none of the ritonavir's, which carries most of what that order interacts with.
	 */
	@Test
	public void whetherSheIsOnADrugSheTakesInACombinationIsAnsweredForTheWholeOrder() {
		for (String question : new String[] { "Is she on lopinavir?", "Does her lopinavir interact with her other medications?" }) {
			String answer = ask(question);
			assertTrue(answer.startsWith("Lopinavir is one of this patient's active medications (Lopinavir / ritonavir 200mg/50mg ["),
				question + " was: " + answer);
			assertTrue(answer.split("\n")[0].contains(DrugReferenceInjector.TAKEN_WITH_OPENING + "Ritonavir in the same order."),
				question + " was: " + answer);
			assertTrue(answer.contains("\nLopinavir interacts with active order Didanosine — Moderate."), question + " was: " + answer);
			assertTrue(answer.contains("\nRitonavir interacts with active order Didanosine — Minor."), question + " was: " + answer);
		}
	}

	/** The module's answer to {@code question} over {@link #RUTH}, every line of it printed once. */
	private static String ask(String question) {
		return askOver(question, RUTH);
	}

	/** The module's answer to {@code question} over {@code orders}, every line of it printed once. */
	private static String askOver(String question, List<PatientClinicalContext.ActiveDrugOrder> orders) {
		Set<String> names = new LinkedHashSet<String>();
		Set<String> codes = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
			codes.addAll(order.getAtcCodes());
		}
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(40, null, names, codes, null, null, orders), question)
				.getModuleAnswer();
		assertNotNull(answer, question + ": the module answers");
		List<String> lines = new ArrayList<String>();
		for (String line : answer.split("\n")) {
			String withoutMarkers = line.replaceAll("( \\[\\d+\\])+$", "");
			assertTrue(!lines.contains(withoutMarkers), question + ": a line printed twice: " + answer);
			lines.add(withoutMarkers);
		}
		return answer;
	}

	/** An order as {@code PatientClinicalContextBuilder} reads one: its display, then its concept's names, and its
	 *  concept's ATC code where it maps one. */
	private static PatientClinicalContext.ActiveDrugOrder order(String uuid, String display, String atc, String conceptUuid,
			String... conceptNames) {
		Set<String> names = new LinkedHashSet<String>();
		names.add(display);
		names.addAll(Arrays.asList(conceptNames));
		return PatientClinicalContext.ActiveDrugOrder.named(uuid, display, names,
			atc == null ? Collections.<String> emptySet() : DrugReferenceTestSupport.set(atc), Collections.<String> emptySet(),
			conceptUuid);
	}
}
