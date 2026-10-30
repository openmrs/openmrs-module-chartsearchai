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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.impl.QueryScopeRouter;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A screen's answer states its reasons to change a medication whole, her orders sharing a substance on one line, its
 * cautions as one count, and what it did not check on one line (ADR Decision 493). On the public demo's patient
 * dd8f98cf, "What shouldn't I prescribe for this patient?" was answered in 16 lines and 305 words, its one Major the
 * fourth line, beside 14 chips stating every finding in full.
 */
public class BriefScreenAnswerTest extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "Does he have any drug contraindications?";

	private static final Pattern MARKER = Pattern.compile("\\[(\\d+)\\]");

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void theDemoPatientsScreenOpensWithItsMajorAndSaysWhereTheRestAre() {
		PatientChart chart = demoPatientsScreen();
		String answer = chart.getModuleAnswer();

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(5, lines.length, "was: " + answer);
		assertEquals(BOTTOM_LINE, lines[0], "was: " + answer);
		assertEquals(BOTTOM_LINE, chart.getModuleAnswerSummary(), "the line a client shows, the rest collapsed");
		assertTrue(lines[1].startsWith("Nelfinavir interacts with active order Rifampin — Major. ["), "was: " + answer);
		assertTrue(lines[2].startsWith(SHARED_SUBSTANCES), "was: " + answer);
		assertTrue(lines[3].startsWith("Also in the safety warnings beside this answer: 12 interactions, each a caution (8 "
				+ "Moderate, 4 Minor) — Efavirenz with Lamivudine, Stavudine, Sulfamethoxazole, Rifampicin and isoniazid, "
				+ "Rifampin, Nelfinavir, Trimethoprim; Stavudine with Rifampicin and isoniazid; Sulfamethoxazole with Rifampin; "
				+ "Trimethoprim with Rifampin; Lamivudine with Trimethoprim; Rifampicin (rifampin) with Isoniazid. ["), "was: " + answer);
		assertEquals(NOT_CHECKED, lines[4]);
	}

	/** Asked which drugs to avoid, her allergies and that the check lists no other drug are one line before the screen (ADR
	 *  Decisions 496, 509). */
	@Test
	public void theDemoPatientsAvoidAnswerStatesItsLeadAndHerAllergiesOnOneLine() {
		// The question the model's reading runs "What shouldn't I prescribe for this patient?" as (ADR Decision 491).
		PatientChart chart = demoPatientsScreen(QueryScopeRouter.DRUGS_TO_AVOID_QUESTION);
		String answer = chart.getModuleAnswer();

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals(6, lines.length, "was: " + answer);
		// One sentence a client shows: her allergies, the bottom line, and the lead's limit; the answer's words the
		// composer's, the lead above the bottom line (ADR Decisions 517, 519).
		assertEquals("No drug allergies are recorded for this patient; 1 Major interaction — Nelfinavir with Rifampin — "
				+ "plus 4 possible duplicate therapies and 12 cautions (this check does not list other drugs to avoid).",
			chart.getModuleAnswerSummary());
		assertEquals("No drug allergies are recorded for this patient. This check does not list other drugs to avoid.",
			lines[0]);
		assertEquals(BOTTOM_LINE, lines[1], "was: " + answer);
		assertTrue(lines[2].startsWith("Nelfinavir interacts with active order Rifampin — Major. ["), "was: " + answer);
		assertEquals(NOT_CHECKED, lines[5]);
	}

	/** The bottom line a screen's answer opens with (ADR Decision 512): what is rated a reason to withhold, named, and the
	 *  rest counted. */
	private static final String BOTTOM_LINE = "1 Major interaction — Nelfinavir with Rifampin — plus 4 possible duplicate "
			+ "therapies and 12 cautions.";

	/** Her orders sharing a substance, a reason to change one of them, named on one line (ADR Decisions 493, 506). */
	private static final String SHARED_SUBSTANCES = "Possible duplicate therapy — each in more than one of this patient's "
			+ "active orders: Lamivudine; Stavudine; Sulfamethoxazole and Trimethoprim; Isoniazid and Rifampicin (rifampin). [";

	/** What a screen of the demo patient did not check, on one line, the orders the data does not identify last, each name
	 *  once (ADR Decisions 496, 506, 509). */
	private static final String NOT_CHECKED = "Not checked: unrated interactions, anything beyond drug interactions and "
			+ "contraindications, this patient's conditions (no drug-condition rules are loaded), and 10 active orders the "
			+ "drug data does not identify: Measles vaccination; Diphtheria tetanus and pertussis vaccination; Hepatitis B "
			+ "vaccination; Polio vaccination, oral; Hemophilus influenza B vaccine.";

	/** Every finding the screen raised is cited by some line, so the chips pass publishes every one of them. */
	@Test
	public void everyFindingTheScreenRaisedIsStillCited() {
		PatientChart chart = demoPatientsScreen();
		String answer = chart.getModuleAnswer();
		Set<Integer> cited = new LinkedHashSet<Integer>();
		Matcher marker = MARKER.matcher(answer);
		while (marker.find()) {
			cited.add(Integer.valueOf(marker.group(1)));
		}
		Matcher finding = Pattern.compile("\\[(\\d+)\\] " + Pattern.quote(DrugReferenceInjector.FINDING_PREFIX))
				.matcher(chart.getText());
		int findings = 0;
		while (finding.find()) {
			findings++;
			assertTrue(cited.contains(Integer.valueOf(finding.group(1))), "finding record [" + finding.group(1)
					+ "] is cited by no line of: " + answer);
		}
		assertEquals(14, findings, "precondition: the screen's 14 findings were injected");
	}

	private static PatientChart demoPatientsScreen() {
		return demoPatientsScreen(QUESTION);
	}

	private static PatientChart demoPatientsScreen(String question) {
		List<PatientClinicalContext.ActiveDrugOrder> orders = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		add(orders, "Efavirenz", 1, null, "Efavirenz", "EFV", "Stocrin", "Sustiva");
		add(orders, "Nelfinavir", 1, null, "Nelfinavir", "NFV", "Viracept");
		add(orders, "Lamivudine", 2, null, "Lamivudine", "3TC", "Epivir");
		add(orders, "Stavudine", 2, null, "Stavudine", "d4T", "Zerit");
		add(orders, "Trimethoprim and sulfamethoxazole", 2, null, "Trimethoprim and sulfamethoxazole", "Cotrimoxazole",
			"Septrin");
		add(orders, "Rifampicin and isoniazid", 2, "J04AM02", "Rifampicin and isoniazid", "Rifinah");
		add(orders, "Measles vaccination", 2, null, "Measles vaccination", "Measles immunization");
		add(orders, "Diphtheria tetanus and pertussis vaccination", 2, null, "Diphtheria tetanus and pertussis vaccination");
		add(orders, "Hepatitis B vaccination", 2, "J07BC01", "Hepatitis B vaccination");
		add(orders, "Polio vaccination, oral", 2, "J07BF", "Polio vaccination, oral");
		add(orders, "Hemophilus influenza B vaccine", 2, "J07AG01", "Hemophilus influenza B vaccine");
		Set<String> names = new LinkedHashSet<String>();
		Set<String> codes = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
			codes.addAll(order.getAtcCodes());
		}
		return DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null, names, codes, null, null, orders),
			question);
	}

	private static void add(List<PatientClinicalContext.ActiveDrugOrder> orders, String display, int count, String atc,
			String... names) {
		for (int n = 1; n <= count; n++) {
			orders.add(DrugReferenceTestSupport.activeOrder("order-" + display + "-" + n, display,
				new LinkedHashSet<String>(Arrays.asList(names)),
				atc == null ? new LinkedHashSet<String>() : new LinkedHashSet<String>(Arrays.asList(atc))));
		}
	}
}
