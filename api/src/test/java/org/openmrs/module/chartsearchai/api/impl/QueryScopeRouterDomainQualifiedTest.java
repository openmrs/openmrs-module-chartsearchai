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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * "conditions" is the one enumeration cue that is also a generic noun clinicians attach to a
 * domain — "heart conditions", "skin conditions", "any mental health or psychiatric conditions".
 * "medications" and "allergies" name their own domain; "conditions" does not.
 *
 * <p>Routing a domain-qualified question to the CONDITIONS scope hands the small model the
 * patient's ENTIRE problem list and asks it to filter — and on a patient with a long list it
 * enumerates instead. Measured on the 3.7.1 demo set (30 patients × 9 topics): the mental-health
 * cell answered <em>"Yes, the patient has several mental health or psychiatric conditions
 * recorded: Lumbago with sciatica, Cardiogenic shock, Bacterial gastroenteritis, Pulmonary
 * atelectasis, Chronic gingivitis …"</em> — a clinically wrong answer, and 52 of the 75 off-topic
 * citations in the whole eval came from that single topic, while genuinely TOPICAL topics (eye,
 * fractures) drifted 1 citation each.
 *
 * <p>So a conditions cue only earns the complete problem list when nothing narrows it. Generic
 * qualifiers that do not name a clinical domain ("active", "chronic", "medical", "anything")
 * still enumerate the list — those questions ARE problem-list questions.
 */
public class QueryScopeRouterDomainQualifiedTest {

	private static Set<QueryScopeRouter.Intent> intents(String question) {
		return QueryScopeRouter.matchedIntents(question);
	}

	@Test
	public void domainQualifiedConditionsQuestionsFallThroughToTopical() {
		assertFalse(intents("Does the patient have any mental health or psychiatric conditions?")
				.contains(QueryScopeRouter.Intent.CONDITIONS),
				"a psychiatric-domain question must not pull the whole problem list");
		assertFalse(intents("Any heart conditions?").contains(QueryScopeRouter.Intent.CONDITIONS));
		assertFalse(intents("Was she diagnosed with depression?")
				.contains(QueryScopeRouter.Intent.CONDITIONS));
	}

	@Test
	public void unqualifiedProblemListQuestionsKeepTheCompleteScope() {
		// These ARE problem-list enumerations — the completeness guarantee is exactly what they need.
		assertTrue(intents("What conditions does the patient have?")
				.contains(QueryScopeRouter.Intent.CONDITIONS));
		assertTrue(intents("What is on her problem list?")
				.contains(QueryScopeRouter.Intent.CONDITIONS));
		assertTrue(intents("Any active conditions?").contains(QueryScopeRouter.Intent.CONDITIONS),
				"'active' narrows nothing clinically — still the whole problem list");
		assertTrue(intents("List all chronic medical conditions")
				.contains(QueryScopeRouter.Intent.CONDITIONS));
		assertTrue(intents("Has the patient been diagnosed with anything?")
				.contains(QueryScopeRouter.Intent.CONDITIONS));
		assertTrue(intents("What are the past diagnoses?")
				.contains(QueryScopeRouter.Intent.CONDITIONS));
	}

	@Test
	public void theQualifierRuleAppliesOnlyToTheConditionsCue() {
		// "medications" and "allergies" name their own domain, so a qualifier in front of them
		// ("drug allergies", "current medications") does not make the enumeration narrower —
		// the typed scope is still exactly the right answer set.
		assertTrue(intents("Does the patient have any drug allergies?")
				.contains(QueryScopeRouter.Intent.ALLERGIES),
				"drug allergies must keep the allergy table complete");
		assertTrue(intents("Does the patient have any drug allergies?")
				.contains(QueryScopeRouter.Intent.MEDICATIONS));
		assertTrue(intents("What medications is the patient taking?")
				.contains(QueryScopeRouter.Intent.MEDICATIONS));
		assertTrue(intents("Is the patient enrolled in any HIV programs?")
				.contains(QueryScopeRouter.Intent.PROGRAMS),
				"a qualified programs question still needs the complete program list");
	}

	@Test
	public void aDomainQualifiedConditionsQuestionKeepsItsOtherIntents() {
		// Suppressing CONDITIONS must not suppress a co-matched intent: "any drug allergies or
		// skin conditions?" still needs the allergy and medication tables complete.
		Set<QueryScopeRouter.Intent> matched = intents("Any drug allergies or skin conditions?");
		assertFalse(matched.contains(QueryScopeRouter.Intent.CONDITIONS));
		assertTrue(matched.contains(QueryScopeRouter.Intent.ALLERGIES));
		assertTrue(matched.contains(QueryScopeRouter.Intent.MEDICATIONS));
	}

	@Test
	public void nullAndBlankAreStillTopical() {
		assertEquals(0, intents(null).size());
		assertEquals(0, intents("   ").size());
	}
}
