/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.serializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseContextSensitiveTest;

public class ConceptNameUtilTest extends BaseContextSensitiveTest {

	@Test
	public void getName_shouldReturnEmptyStringForNullConcept() {
		assertEquals("", ConceptNameUtil.getName(null));
	}

	@Test
	public void getName_shouldReturnPreferredName() {
		Concept concept = new Concept();
		concept.addName(new ConceptName("Hypertension", Context.getLocale()));

		assertEquals("Hypertension", ConceptNameUtil.getName(concept));
	}

	@Test
	public void getName_shouldIncludeSynonyms() {
		Locale locale = Context.getLocale();
		Concept concept = new Concept();
		concept.addName(new ConceptName("Hypertension", locale));
		concept.addName(new ConceptName("HTN", locale));
		concept.addName(new ConceptName("High Blood Pressure", locale));

		String result = ConceptNameUtil.getName(concept);
		assertTrue(result.startsWith("Hypertension"));
		assertTrue(result.contains("HTN"));
		assertTrue(result.contains("High Blood Pressure"));
	}

	@Test
	public void getName_shouldLimitSynonymsToThree() {
		Locale locale = Context.getLocale();
		Concept concept = new Concept();
		concept.addName(new ConceptName("Preferred", locale));
		concept.addName(new ConceptName("Syn1", locale));
		concept.addName(new ConceptName("Syn2", locale));
		concept.addName(new ConceptName("Syn3", locale));
		concept.addName(new ConceptName("Syn4", locale));

		String result = ConceptNameUtil.getName(concept);
		// Should have at most 3 synonyms in parentheses
		int commaCount = result.length() - result.replace(",", "").length();
		assertTrue(commaCount <= 2, "Should have at most 3 synonyms (2 commas)");
	}

	@Test
	public void getName_shouldExcludeDifferentLocaleSynonyms() {
		Locale locale = Context.getLocale();
		Concept concept = new Concept();
		concept.addName(new ConceptName("Hypertension", locale));
		concept.addName(new ConceptName("Hipertensión", new Locale("es")));

		String result = ConceptNameUtil.getName(concept);
		assertEquals("Hypertension", result);
	}
}
