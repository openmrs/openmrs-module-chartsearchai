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

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.ConceptNumeric;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Obs;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseContextSensitiveTest;

public class ObsTextSerializerTest extends BaseContextSensitiveTest {

	private ObsTextSerializer serializer;

	@BeforeEach
	public void setUp() {
		serializer = new ObsTextSerializer();
	}

	@Test
	public void toText_shouldIncludeEncounterContext() {
		Obs obs = new Obs();
		Encounter enc = new Encounter();
		EncounterType type = new EncounterType();
		type.setName("Outpatient Visit");
		enc.setEncounterType(type);
		enc.setEncounterDatetime(new Date());
		obs.setEncounter(enc);

		Concept concept = new Concept();
		concept.addName(new ConceptName("Weight", Context.getLocale()));
		obs.setConcept(concept);
		obs.setValueNumeric(70.0);

		String result = serializer.toText(obs);
		assertTrue(result.startsWith("Outpatient Visit ("));
		assertTrue(result.contains("Weight: 70.0"));
	}

	@Test
	public void toText_shouldIncludeNumericValueWithUnits() {
		Obs obs = new Obs();
		ConceptNumeric concept = new ConceptNumeric();
		concept.addName(new ConceptName("Temperature", Context.getLocale()));
		concept.setUnits("DEG C");
		obs.setConcept(concept);
		obs.setValueNumeric(37.5);

		String result = serializer.toText(obs);
		assertTrue(result.contains("37.5 DEG C"));
	}

	@Test
	public void toText_shouldIncludeCodedValue() {
		Obs obs = new Obs();
		Concept question = new Concept();
		question.addName(new ConceptName("Diagnosis", Context.getLocale()));
		obs.setConcept(question);

		Concept answer = new Concept();
		answer.addName(new ConceptName("Malaria", Context.getLocale()));
		obs.setValueCoded(answer);

		String result = serializer.toText(obs);
		assertTrue(result.contains("Diagnosis: Malaria"));
	}

	@Test
	public void toText_shouldIncludeInterpretation() {
		Obs obs = new Obs();
		Concept concept = new Concept();
		concept.addName(new ConceptName("Heart Rate", Context.getLocale()));
		obs.setConcept(concept);
		obs.setValueNumeric(120.0);
		obs.setInterpretation(Obs.Interpretation.HIGH);

		String result = serializer.toText(obs);
		assertTrue(result.contains("(HIGH)"));
	}

	@Test
	public void toText_shouldIncludeComment() {
		Obs obs = new Obs();
		Concept concept = new Concept();
		concept.addName(new ConceptName("BP", Context.getLocale()));
		obs.setConcept(concept);
		obs.setValueNumeric(140.0);
		obs.setComment("Taken after exercise");

		String result = serializer.toText(obs);
		assertTrue(result.contains("Note: Taken after exercise"));
	}

	@Test
	public void toText_shouldFlattenGroupMembers() {
		Obs parent = new Obs();
		Concept parentConcept = new Concept();
		parentConcept.addName(new ConceptName("Vitals", Context.getLocale()));
		parent.setConcept(parentConcept);
		parent.setValueText("group");

		Obs child = new Obs();
		Concept childConcept = new Concept();
		childConcept.addName(new ConceptName("Pulse", Context.getLocale()));
		child.setConcept(childConcept);
		child.setValueNumeric(80.0);
		parent.addGroupMember(child);

		String result = serializer.toText(parent);
		assertTrue(result.contains("Vitals:"));
		assertTrue(result.contains("; Pulse: 80.0"));
	}

	@Test
	public void toText_shouldHandleNullConcept() {
		Obs obs = new Obs();
		obs.setValueText("some text");

		String result = serializer.toText(obs);
		assertEquals("some text", result);
	}
}
