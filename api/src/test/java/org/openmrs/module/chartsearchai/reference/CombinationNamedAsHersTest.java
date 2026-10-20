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
 * Her combination order named whole, by its product's name or its substances', is answered as her own medication (ADR
 * Decision 372): on the 3.7.1 standalone "Is her sulfamethoxazole / trimethoprim safe?" went to the model for the four
 * patients on cotrimoxazole, which told Rhoda "Sulfamethoxazole / Trimethoprim can be given, with several cautions" — a
 * drug she takes, cleared as though proposed. Over the shipped knowledge base.
 */
public class CombinationNamedAsHersTest extends BaseModuleContextSensitiveTest {

	private static final List<PatientClinicalContext.ActiveDrugOrder> ORDERS = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-ctx", "Cotrimoxazole 960mg", "Sulfamethoxazole", "Trimethoprim"),
		DrugReferenceTestSupport.activeOrder("order-lpv", "Lopinavir / ritonavir 200mg/50mg", "Lopinavir", "Ritonavir"));

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void herCombinationNamedWholeIsAnsweredAsHerMedication() {
		for (String named : Arrays.asList("sulfamethoxazole / trimethoprim", "cotrimoxazole")) {
			String safe = ask("Is her " + named + " safe?");
			assertNotNull(safe, named + " — the module answers");
			String[] lines = safe.split("\n");
			assertTrue(lines[0].contains(" is one of this patient's active medications (Cotrimoxazole 960mg [n])"),
				named + " — was: " + safe);
			assertTrue(safe.contains("\nSulfamethoxazole interacts with active order")
					&& safe.contains("\nTrimethoprim interacts with active order"), named + " — both its substances: " + safe);
			assertTrue(!safe.contains("can be given") && !safe.contains("No —"), named + " — no verdict: " + safe);
			assertEquals(safe, ask("What interacts with her " + named + "?"), named);
			assertEquals(DrugReferenceInjector.WEIGHS_NO_STOPPING_LEAD + "\n" + safe, ask("Can I stop her " + named + "?"),
				named);
		}
	}

	/** Her combination her only order: said, as one substance would be, that there is no other medication to check it
	 *  against (ADR Decisions 316, 372) — Amina Wekesa, on cotrimoxazole alone, was told by the model "The records do not
	 *  address the safety of sulfamethoxazole / trimethoprim". */
	@Test
	public void herCombinationHerOnlyOrderHasNothingToBeCheckedAgainst() {
		List<PatientClinicalContext.ActiveDrugOrder> alone = Arrays.asList(ORDERS.get(0));
		LinkedHashSet<String> names = new LinkedHashSet<String>(alone.get(0).getNames());
		for (String question : Arrays.asList("Is her sulfamethoxazole / trimethoprim safe?",
				"What interacts with her sulfamethoxazole / trimethoprim?")) {
			String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
					.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(45, null, names, null, null, null, alone), question).getModuleAnswer();
			assertNotNull(answer, question + " — the module answers");
			assertTrue(answer.replaceAll("\\[\\d+\\]", "[n]").endsWith(" is one of this patient's active medications "
					+ "(Cotrimoxazole 960mg [n]), and this patient has no other active medication to check it against."),
				question + " — was: " + answer);
		}
	}

	/** Her isoniazid / rifapentine, her only order (ADR Decision 372): on the 3.7.1 standalone Michelle Lewis and Steven
	 *  White were told by the model "The records do not address the safety of Isoniazid / Rifapentine". */
	@Test
	public void herIsoniazidRifapentineHerOnlyOrderHasNothingToBeCheckedAgainst() {
		List<PatientClinicalContext.ActiveDrugOrder> alone = Arrays.asList(DrugReferenceTestSupport.activeOrder("order-3hp",
			"Isoniazid / Rifapentine", "Isoniazid", "Rifapentine"));
		LinkedHashSet<String> names = new LinkedHashSet<String>(alone.get(0).getNames());
		for (String question : Arrays.asList("Is her isoniazid / rifapentine safe?",
				"What interacts with her isoniazid / rifapentine?")) {
			String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
					.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(45, null, names, null, null, null, alone), question).getModuleAnswer();
			assertNotNull(answer, question + " — the module answers");
			assertTrue(answer.replaceAll("\\[\\d+\\]", "[n]").endsWith(" is one of this patient's active medications "
					+ "(Isoniazid / Rifapentine [n]), and this patient has no other active medication to check it against."),
				question + " — was: " + answer);
		}
	}

	private static String ask(String question) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : ORDERS) {
			names.addAll(order.getNames());
		}
		String answer = DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(45, null, names, null, null, null, ORDERS), question).getModuleAnswer();
		return answer == null ? null : answer.replaceAll("\\[\\d+\\]", "[n]");
	}
}
