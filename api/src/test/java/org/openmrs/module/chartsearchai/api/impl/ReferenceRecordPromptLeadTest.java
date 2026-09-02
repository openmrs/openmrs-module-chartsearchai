/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;

/**
 * The prompt's record-type rules and the leads the injector actually writes name the same tokens.
 *
 * <p>Each rule is keyed on the text a record BEGINS with, and each lead is written on the injector's
 * side, so the two are separate spellings of one token. javac inlines a constant, so no behavioural
 * assertion can tell a copy from a reference — and a drift is silent in the worst direction: a
 * reference record whose lead the rule no longer matches is read by the model under
 * {@code Use only the patient records below}, i.e. as this patient's own data.
 *
 * <p>Both tokens are spelled here as LITERALS on purpose, the way
 * {@code ReferenceProseFidelityTest} spells the shared terminator set out rather than iterating the
 * constant: comparing either side to itself would assert nothing at all.
 *
 * <p>The reference lead became a constant in issue #354, when a second record kind
 * ({@code drug_class_note}) started wearing it in order to read under the same rule.
 */
public class ReferenceRecordPromptLeadTest {

	@Test
	public void theReferenceLeadAndThePromptsReferenceRuleNameOneToken() {
		String token = "Drug reference";

		assertTrue(DrugReferenceInjector.REFERENCE_PREFIX.startsWith(token),
				"the injected lead must start with the token the prompt's rule names, was: "
						+ DrugReferenceInjector.REFERENCE_PREFIX);
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("\"" + token + "\""),
				"and the system prompt must still name that token in its record-type rule");
	}

	@Test
	public void theFindingLeadAndThePromptsFindingRuleNameOneToken() {
		String token = "Safety finding";

		assertTrue(DrugReferenceInjector.FINDING_PREFIX.startsWith(token),
				"the injected lead must start with the token the prompt's rule names, was: "
						+ DrugReferenceInjector.FINDING_PREFIX);
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("\"" + token + "\""),
				"and the system prompt must still name that token in its record-type rule");
	}
}
