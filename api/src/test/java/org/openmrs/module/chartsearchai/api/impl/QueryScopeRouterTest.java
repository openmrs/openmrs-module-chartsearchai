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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The module's clinical safety cues, independent of QueryStore's retrieval-scope policy. */
public class QueryScopeRouterTest {

	@Test
	public void asksAboutMedications_isTheWideningSignalAndNotADrugDetector() {
		// Read by the drug-safety layer to decide that a question about what the patient is TAKING puts
		// her whole active-order list in the response's subject matter.
		assertTrue(QueryScopeRouter.asksAboutMedications("What are her current medications?"));
		assertTrue(QueryScopeRouter.asksAboutMedications("is she on any drugs?"));
		assertTrue(QueryScopeRouter.asksAboutMedications("what has she been prescribed?"));
		assertFalse(QueryScopeRouter.asksAboutMedications("Does she have cancer?"));
		assertFalse(QueryScopeRouter.asksAboutMedications("What is her date of birth?"));
		assertFalse(QueryScopeRouter.asksAboutMedications(null));
		assertFalse(QueryScopeRouter.asksAboutMedications("   "));

		// LOAD-BEARING, and the reason the safety layer must not be gated on this predicate alone: a
		// question naming a drug outright carries none of the medication cue words. Such a question is
		// answered by the drug-in-play arm, which the subject-matter scoping never touches — but a
		// reader who mistook this for "is this a drug question" would gate that arm on it and silence it.
		assertFalse(QueryScopeRouter.asksAboutMedications("Can I give her bupivacaine?"));
	}

	@Test
	public void asksAboutAllergies_coversTheAllergyTablesOwnVocabulary() {
		assertTrue(QueryScopeRouter.asksAboutAllergies("any allergies?"));
		assertTrue(QueryScopeRouter.asksAboutAllergies("has she had any adverse reactions?"));
		assertTrue(QueryScopeRouter.asksAboutAllergies("any intolerance on record?"));
		assertFalse(QueryScopeRouter.asksAboutAllergies("Does she have cancer?"));
		assertFalse(QueryScopeRouter.asksAboutAllergies(null));
	}

	@Test
	public void asksAboutConditions_coversTheProblemListsOwnVocabulary() {
		assertTrue(QueryScopeRouter.asksAboutConditions("What conditions does she have?"));
		assertTrue(QueryScopeRouter.asksAboutConditions("what was she diagnosed with?"));
		assertTrue(QueryScopeRouter.asksAboutConditions("what is on her problem list?"));
		assertFalse(QueryScopeRouter.asksAboutConditions(null));

		// LOAD-BEARING, and the counterpart of the bupivacaine case above: the reported off-topic
		// question names a condition outright and carries none of the CONDITIONS cues, so this predicate
		// must not be mistaken for "is this question about a clinical finding". It is the widening
		// signal for the LIST, and a question about one named condition is answered by the token match.
		assertFalse(QueryScopeRouter.asksAboutConditions("Does she have cancer?"));
	}

	@Test
	public void retrievalOfDrugOrdersDoesNotByItselfRequestAMedicationSafetyScreen() {
		assertFalse(QueryScopeRouter.asksAboutMedications("What orders were placed?"));
		assertFalse(QueryScopeRouter.isInteractionScreening("Are those orders safe?"));
		assertTrue(QueryScopeRouter.asksAboutMedications("What medication orders were placed?"));
		assertTrue(QueryScopeRouter.isInteractionScreening("Are those medications safe?"));
	}
}
