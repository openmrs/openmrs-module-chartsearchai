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
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

public class ConceptNameUtilTest extends BaseModuleContextSensitiveTest {

	@Test
	public void getName_shouldReturnEmptyStringForNullConcept() {
		assertEquals("", ConceptNameUtil.getName(null));
	}

	@Test
	public void getName_shouldReturnPreferredName() {
		Concept concept = new Concept();
		concept.addName(conceptName("Hypertension"));

		assertEquals("Hypertension", ConceptNameUtil.getName(concept));
	}

	@Test
	public void getName_shouldIncludeSynonyms() {
		Concept concept = new Concept();
		concept.addName(conceptName("Hypertension"));
		concept.addName(conceptName("HTN"));
		concept.addName(conceptName("High Blood Pressure"));

		String result = ConceptNameUtil.getName(concept);
		assertTrue(result.startsWith("Hypertension"));
		assertTrue(result.contains("HTN"));
		assertTrue(result.contains("High Blood Pressure"));
	}

	@Test
	public void getName_shouldLimitSynonymsToThree() {
		Concept concept = new Concept();
		concept.addName(conceptName("Preferred"));
		concept.addName(conceptName("Syn1"));
		concept.addName(conceptName("Syn2"));
		concept.addName(conceptName("Syn3"));
		concept.addName(conceptName("Syn4"));

		String result = ConceptNameUtil.getName(concept);
		// Should have at most 3 synonyms in parentheses
		int commaCount = result.length() - result.replace(",", "").length();
		assertTrue(commaCount <= 2, "Should have at most 3 synonyms (2 commas)");
	}

	@Test
	public void getName_shouldExcludeDifferentLocaleSynonyms() {
		Concept concept = new Concept();
		concept.addName(conceptName("Hypertension"));
		ConceptName spanishName = new ConceptName();
		spanishName.setName("Hipertensión");
		spanishName.setLocale(new Locale("es"));
		concept.addName(spanishName);

		String result = ConceptNameUtil.getName(concept);
		assertEquals("Hypertension", result);
	}

	private ConceptName conceptName(String name) {
		ConceptName cn = new ConceptName();
		cn.setName(name);
		cn.setLocale(Locale.ENGLISH);
		return cn;
	}
}
