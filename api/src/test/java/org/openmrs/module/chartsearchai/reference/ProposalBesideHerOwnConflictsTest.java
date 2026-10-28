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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A proposal whose question puts her medications in scope — "Can I add omeprazole to her medications?" — is answered as
 * the proposal, and her own allergy conflicts, which the validator drew apart as about her other medications, are stated
 * after it (ADR Decision 296). On the 3.7.1 standalone they kept the model call: Agnes Adams's answer was the model's,
 * and Sarah Taylor, whose omeprazole the check relates to none of her orders, was told it interacts with "Diclofenac
 * [349], Dexamethasone [349], Hydrocortisone [349], and Prednisone [349]" — one allergy finding cited for four drugs.
 */
public class ProposalBesideHerOwnConflictsTest extends BaseModuleContextSensitiveTest {

	private static final String APART = "Found as well, about this patient's other medications:";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aProposalCautionIsAnsweredAndHerOwnConflictFollowsIt() {
		String answer = ask("Can I add omeprazole to her medications?", DrugReferenceTestSupport.set("NSAIDs"),
			DrugReferenceTestSupport.activeOrder("order-asa", "Aspirin 81mg", "Aspirin"));

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("1 interaction caution for Omeprazole — Minor, with Acetylsalicylic acid (aspirin).", lines[0], "was: " + answer);
		assertEquals(true, lines[1].startsWith("Omeprazole interacts with active order "), "was: " + answer);
		// Her aspirin's conflict with her NSAID allergy is about aspirin, which the question does not name (ADR Decision 387).
		assertEquals(-1, Arrays.asList(lines).indexOf(APART), "nothing about her own conflict, was: " + answer);
		assertEquals(false, answer.contains("recorded allergy"), "was: " + answer);
	}

	/** The shipped data lists omeprazole against dexamethasone with no rating, so the proposal's answer is that sentence
	 *  (ADR Decision 204's), and the allergy to the dexamethasone he takes follows it. */
	@Test
	public void aProposalTheCheckRatesNothingAgainstIsSaidSoAndHerOwnConflictFollows() {
		String answer = ask("Can I add omeprazole to his medications?", DrugReferenceTestSupport.set("dexamethasone"),
			DrugReferenceTestSupport.activeOrder("order-dex", "Dexamethasone", "Dexamethasone"));

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("The interaction data gives no rated reason to withhold Omeprazole: it lists Omeprazole against one of"
				+ " this patient's orders, with no severity or mechanism on file. [3]", lines[0], "was: " + answer);
		// Her dexamethasone allergy is about dexamethasone, which the question does not name (ADR Decision 387).
		assertEquals(-1, Arrays.asList(lines).indexOf(APART), "nothing about her own conflict, was: " + answer);
		assertEquals(false, answer.contains("recorded allergy"), "was: " + answer);
	}

	private static String ask(String question, Set<String> allergens, PatientClinicalContext.ActiveDrugOrder... orders) {
		Set<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		List<PatientClinicalContext.ActiveDrugOrder> list = new ArrayList<PatientClinicalContext.ActiveDrugOrder>(
				Arrays.asList(orders));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(70, null, names, null, allergens, null,
				list), question).getModuleAnswer();
	}
}
