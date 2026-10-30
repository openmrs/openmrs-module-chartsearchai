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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A screen of her own orders states one mechanism once per drug, naming every order it relates that drug to by it, as
 * a proposal's chips do (ADR Decisions 99, 190). Measured on the 3.7.1 standalone: Sarah Taylor's "does she have any
 * drug interactions?" related 21 pairs, 15 of them her three NSAIDs against her five corticosteroids by one Moderate
 * mechanism, and listed 10 lines with "11 more are not shown". The fixture is a verbatim slice of the shipped knowledge
 * base: ibuprofen and celecoxib each relate to prednisone and dexamethasone by the corticosteroid-and-NSAID row, and to
 * each other by a second.
 */
public class ScreenSharedMechanismContextTest extends BaseModuleContextSensitiveTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-screen-shared-mechanism.json";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aDrugRelatedToSeveralOfHerOrdersByOneMechanismIsOneLineNamingThemAll() throws IOException {
		String answer = screen().getModuleAnswer();

		// Five pairs, every one a Moderate caution: since ADR Decision 506 a screen says on one line, citing every
		// finding, that its cautions are in the safety warnings, the grid (ADR Decision 191) still counting both NSAIDs
		// against both corticosteroids as four pairs and citing both its findings. The question asks only about
		// interactions, so the scope says the answer covers those (ADR Decision 190). Her two NSAIDs are said once as one
		// drug group (ADR Decision 197).
		assertEquals("No Major interactions; 1 possible duplicate therapy and 5 cautions.\n"
				+ "Ibuprofen and Celecoxib are in one drug group (NSAID) — possible duplicate therapy. [2] [3]\n"
				+ "Also in the safety warnings beside this answer: 5 interactions, each a caution (5 Moderate)"
				+ " — Ibuprofen with Prednisone, Dexamethasone, Celecoxib; Celecoxib with Prednisone, Dexamethasone. [6] [8] [7]\n"
				+ DrugReferenceInjector.BELOW_FLOOR_SCOPE, answer);
	}

	@Test
	public void pairsOfOneMechanismThatAreNotAGridKeepALinePerDrug() throws IOException {
		// Ibuprofen against both corticosteroids and celecoxib against prednisone alone: no two drugs each against
		// the same orders, so a line naming them together would claim a pair the data does not relate (ADR Decision 191).
		// Every one a Moderate caution, counted on one line since ADR Decision 493: four pairs, each finding cited (ADR
		// Decision 506).
		String answer = screen("chartsearchai-test/ddi-screen-shared-mechanism-not-a-grid.json").getModuleAnswer();

		assertEquals("No Major interactions; 1 possible duplicate therapy and 4 cautions.\n"
				+ "Ibuprofen and Celecoxib are in one drug group (NSAID) — possible duplicate therapy. [2] [3]\n"
				+ "Also in the safety warnings beside this answer: 4 interactions, each a caution (4 Moderate)"
				+ " — Ibuprofen with Prednisone, Dexamethasone, Celecoxib; Celecoxib with Prednisone. [6] [7] [8]\n"
				+ DrugReferenceInjector.BELOW_FLOOR_SCOPE, answer);
	}

	@Test
	public void theCapCountsLinesSoAScreenThatFitsWithholdsNothing() throws IOException {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_MAX_PAIR_CHIPS, "3");

		String answer = screen().getModuleAnswer();

		assertNotNull(answer, "the module answers the screen");
		assertEquals("Also in the safety warnings beside this answer: 5 interactions, each a caution (5 Moderate)"
				+ " — Ibuprofen with Prednisone, Dexamethasone, Celecoxib; Celecoxib with Prednisone, Dexamethasone. [6] [8] [7]", answer.split("\n")[2], "five pairs on three lines fit a cap of three: " + answer);
		assertTrue(!answer.contains(" not shown."), "so nothing is withheld: " + answer);
	}

	private static PatientChart screen() throws IOException {
		return screen(FIXTURE);
	}

	private static PatientChart screen(String fixture) throws IOException {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"),
			DrugReferenceTestSupport.activeOrder("order-celecoxib", "Celecoxib 200mg"),
			DrugReferenceTestSupport.activeOrder("order-prednisone", "Prednisone 5mg"),
			DrugReferenceTestSupport.activeOrder("order-dexamethasone", "Dexamethasone 8mg"));
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(names), null,
			null, null, orders);
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.ddiFixtureService(fixture))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, DrugReferenceTestSupport.SCREENING_QUESTION);
	}
}
