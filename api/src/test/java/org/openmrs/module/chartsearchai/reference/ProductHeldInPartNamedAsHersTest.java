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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A combination named as hers that she takes only some of the substances of is said not to be hers, with what of it she
 * takes and in which orders, and given no verdict (ADR Decision 377). On the 3.7.1 standalone "Is his isoniazid /
 * rifapentine safe?" went to the model for Rose Rifadupla, on isoniazid in two TB regimens and not on rifapentine — "The
 * records do not address the safety of isoniazid / rifapentine" — and "Is her eucalyptus &amp; diphenhydramine &amp;
 * phenylephrine safe?" for Mark Smith, on Benadryl. Their orders as the standalone holds them; the shipped knowledge base.
 */
public class ProductHeldInPartNamedAsHersTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> ROSE = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-3tc", "Lamivudine / zidovudine 150mg/300mg", "Lamivudine", "Zidovudine"),
		DrugReferenceTestSupport.activeOrder("order-efv", "Efavirenz 600mg", "Efavirenz"),
		DrugReferenceTestSupport.activeOrder("order-ctx", "Cotrimoxazole 960mg", "Sulfamethoxazole", "Trimethoprim"),
		DrugReferenceTestSupport.activeOrder("order-rhz", "Isoniazid / pyrazinamide / rifampin 75/400/150mg", "Isoniazid",
			"Pyrazinamide", "Rifampicin"),
		DrugReferenceTestSupport.activeOrder("order-rhze", "Rifampicin isoniazid pyrazinamide and ethambutol 150/75/400/275mg",
			"Rifampicin", "Isoniazid", "Pyrazinamide", "Ethambutol"),
		DrugReferenceTestSupport.activeOrder("order-d4t", "Stavudine 30mg", "Stavudine"));

	private static final List<PatientClinicalContext.ActiveDrugOrder> MARK = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-cq", "Chloroquine Co 250mg", "Chloroquine"),
		DrugReferenceTestSupport.activeOrder("order-dph", "Benadryl 25mg", "Diphenhydramine"));

	private static final String ROSE_CARRIES = "Isoniazid / rifapentine carries Isoniazid, which this patient takes in "
			+ "active orders Isoniazid / pyrazinamide / rifampin 75/400/150mg [n] and Rifampicin isoniazid pyrazinamide and "
			+ "ethambutol 150/75/400/275mg [n].";

	private static final String MARK_PRODUCT = "Eucalyptus & diphenhydramine & phenylephrine";

	private static final String MARK_CARRIES = MARK_PRODUCT + " carries Diphenhydramine, which this patient takes in active "
			+ "order Benadryl 25mg [n].";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void herIsoniazidRifapentineIsNotHersAndWhatTheCheckFoundIsListed() {
		String safe = ask(ROSE, "Is his isoniazid / rifapentine safe?");
		assertNotNull(safe, "the module answers");
		String[] lines = safe.split("\n");
		assertEquals("Isoniazid / rifapentine" + DrugReferenceInjector.NOT_HER_MEDICATION, lines[0], safe);
		assertEquals(ROSE_CARRIES, lines[1], safe);
		assertEquals(DrugReferenceInjector.FOUND_LEAD_OPENING + "Isoniazid / rifapentine:", lines[2], safe);
		assertTrue(safe.contains("\nRifapentine interacts with active order"), "what relates rifapentine to her orders: " + safe);
		assertTrue(!safe.contains("can be given") && !safe.contains("should not be given") && !safe.contains("No —"),
			"no verdict: " + safe);
		assertEquals(safe, ask(ROSE, "What interacts with his isoniazid / rifapentine?"));
	}

	@Test
	public void stoppingHerIsoniazidRifapentineIsNoOrderOfItAndSaysWhatOfItSheTakes() {
		assertEquals("Isoniazid / rifapentine is not one of this patient's active medications, so there is no order of it "
				+ "to stop.\n" + ROSE_CARRIES, ask(ROSE, "Can I stop his isoniazid / rifapentine?"));
	}

	@Test
	public void herBenadrylProductIsNotHersAndTheCheckRaisedNothingAboutIt() {
		String safe = ask(MARK, "Is her eucalyptus & diphenhydramine & phenylephrine safe?");
		assertNotNull(safe, "the module answers");
		String[] lines = safe.split("\n");
		assertEquals(MARK_PRODUCT + DrugReferenceInjector.NOT_HER_MEDICATION, lines[0], safe);
		assertEquals(MARK_CARRIES, lines[1], safe);
		assertEquals("The interaction check raised no finding about " + MARK_PRODUCT
				+ " against this patient's active medications.", lines[2], safe);
		assertTrue(!safe.contains("can be given") && !safe.contains("should not be given"), "no verdict: " + safe);
		assertEquals(safe, ask(MARK, "What interacts with her eucalyptus & diphenhydramine & phenylephrine?"));
	}

	private static String ask(List<PatientClinicalContext.ActiveDrugOrder> orders, String question) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders), question).getModuleAnswer();
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}
}
