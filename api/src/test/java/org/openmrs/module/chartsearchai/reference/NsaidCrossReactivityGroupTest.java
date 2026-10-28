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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The bundled NSAID cross-reactivity group spans the COX-1-inhibiting NSAID branches and leaves the selective COX-2
 * inhibitors out (ADR Decision 195). Cross-reactive NSAID hypersensitivity follows COX-1 inhibition across chemical
 * families — the group's own note says so — and its two prefixes, propionic acids and salicylates, left an ibuprofen
 * allergy silent beside a diclofenac order. Selective COX-2 inhibitors are the alternative such patients are usually
 * given, so an ibuprofen allergy raises no cross-reactivity against celecoxib. Over the bundled groups file and a
 * verbatim slice of the shipped knowledge base.
 */
public class NsaidCrossReactivityGroupTest extends BaseModuleContextSensitiveTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-nsaid-branches.json";

	@Test
	public void anIbuprofenAllergyIsCrossReactiveWithADiclofenacOrderAndNotWithCelecoxib() throws IOException {
		List<String> details = DrugReferenceTestSupport.details(DrugReferenceTestSupport.contraindications(
			DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
					.validate("", "Are her current medications safe to take together?", context())));

		assertTrue(details.contains("Diclofenac is in the same cross-reactivity group (NSAID) as the patient's allergy to "
				+ "Ibuprofen — possible cross-reactivity"), "the acetic-acid branch is an NSAID: " + details);
		for (String detail : details) {
			assertFalse(detail.startsWith("Celecoxib"), "a selective COX-2 inhibitor is left out: " + detail);
		}
	}

	@Test
	public void aScreenOfThreeNsaidsSaysSoOnce() throws IOException {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context(Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"),
					DrugReferenceTestSupport.activeOrder("order-diclofenac", "Diclofenac 50mg"),
					DrugReferenceTestSupport.activeOrder("order-celecoxib", "Celecoxib 200mg")), null),
					"Does she have any drug interactions?").getModuleAnswer();

		// Each order it names is cited, so a clinician can open it (ADR Decision 198).
		assertTrue(answer != null && answer.split("\n")[1].matches("Ibuprofen, Diclofenac and Celecoxib are in one drug "
				+ "group \\(NSAID\\) — possible duplicate therapy\\. \\[\\d+\\] \\[\\d+\\] \\[\\d+\\]"),
			"under the bottom line, once, citing her three orders: " + answer);
	}

	private static PatientClinicalContext context(List<PatientClinicalContext.ActiveDrugOrder> orders,
			java.util.Set<String> allergies) {
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(names), null, allergies, null, orders);
	}

	private static PatientClinicalContext context() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-diclofenac", "Diclofenac 50mg"),
			DrugReferenceTestSupport.activeOrder("order-celecoxib", "Celecoxib 200mg"));
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.ctx(40, null, new LinkedHashSet<String>(names), null,
			DrugReferenceTestSupport.set("ibuprofen"), null, orders);
	}
}
