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
 * A name the question calls hers, which the drug data does not recognise and none of her orders carries, beside a drug
 * it does recognise, is said to name none of her orders and to have gone unchecked (ADR Decision 308). On the 3.7.1
 * standalone "Can I give him fluconazole with his klarizom?" and "… with his clarithromycine?" — Michael Turner's brand,
 * and Kevin Brown's order's spelling — went to the model for every other patient: "The records do not address the
 * interaction between Fluconazole and Clarithromycin." Decision 250 stood down for a name called hers, which is right
 * of "her ARVs" and wrong of a name none of her orders carries. Over the shipped knowledge base.
 */
public class UnrecognisedNameNamedAsHersTest extends BaseModuleContextSensitiveTest {

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aNameCalledHersThatNamesNoneOfHerOrdersIsSaidToBeSoAndUnchecked() {
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-asa", "Acetylsalicylic acid", "Acetylsalicylic acid"));
		for (String word : Arrays.asList("klarizom", "clarithromycine")) {
			String question = "Can I give him fluconazole with his " + word + "?";
			String answer = ask(question, orders);
			assertNotNull(answer, question + " — the module answers");
			String[] lines = answer.split("\n");
			assertEquals("None of this patient's active orders is named \"" + word + "\".", lines[0],
				question + " — was: " + answer);
			assertTrue(lines[1].startsWith("No interaction check was run for \"" + word + "\" and Fluconazole: \"" + word
					+ "\" is not a drug name the interaction data recognises."), question + " — was: " + answer);
		}
	}

	private static String ask(String question, List<PatientClinicalContext.ActiveDrugOrder> orders) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(45, null, names, null, null, null, orders),
			question).getModuleAnswer();
	}
}
