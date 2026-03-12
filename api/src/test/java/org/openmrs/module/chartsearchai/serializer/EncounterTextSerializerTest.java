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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Location;
import org.springframework.test.util.ReflectionTestUtils;

public class EncounterTextSerializerTest {

	private EncounterTextSerializer serializer;

	@BeforeEach
	public void setUp() {
		serializer = new EncounterTextSerializer();
		ReflectionTestUtils.setField(serializer, "obsSerializer", new ObsTextSerializer());
		ReflectionTestUtils.setField(serializer, "diagnosisSerializer", new DiagnosisTextSerializer());
	}

	@Test
	public void toText_shouldIncludeEncounterType() {
		Encounter encounter = new Encounter();
		EncounterType type = new EncounterType();
		type.setName("Outpatient Visit");
		encounter.setEncounterType(type);
		encounter.setEncounterDatetime(new Date());

		String text = serializer.toText(encounter);
		assertTrue(text.startsWith("Outpatient Visit ("));
	}

	@Test
	public void toText_shouldUseDefaultLabelWhenNoEncounterType() {
		Encounter encounter = new Encounter();
		encounter.setEncounterDatetime(new Date());

		String text = serializer.toText(encounter);
		assertTrue(text.startsWith("Encounter ("));
	}

	@Test
	public void toText_shouldIncludeLocation() {
		Encounter encounter = new Encounter();
		encounter.setEncounterDatetime(new Date());
		Location loc = new Location();
		loc.setName("Main Clinic");
		encounter.setLocation(loc);

		String text = serializer.toText(encounter);
		assertTrue(text.contains("Location: Main Clinic"));
	}

	@Test
	public void toText_shouldHandleNullEncounterDatetime() {
		Encounter encounter = new Encounter();

		String text = serializer.toText(encounter);
		assertTrue(text.contains("unknown"));
	}

	@Test
	public void toText_shouldIncludeDateInExpectedFormat() {
		Encounter encounter = new Encounter();
		java.util.Calendar cal = java.util.Calendar.getInstance();
		cal.set(2024, java.util.Calendar.MARCH, 15);
		encounter.setEncounterDatetime(cal.getTime());

		String text = serializer.toText(encounter);
		assertTrue(text.contains("2024-03-15"));
	}

	@Test
	public void toText_shouldHandleEmptyEncounter() {
		Encounter encounter = new Encounter();
		encounter.setEncounterDatetime(new Date());

		// Should not throw, even with no obs or diagnoses
		String text = serializer.toText(encounter);
		assertTrue(text.length() > 0);
	}
}
