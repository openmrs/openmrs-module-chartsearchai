/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.openmrs.Encounter;
import org.openmrs.Patient;

/**
 * Pure unit tests for {@link EncounterIndexingAdvice} patient extraction logic.
 */
public class EncounterIndexingAdviceTest {

	private final EncounterIndexingAdvice advice = new EncounterIndexingAdvice();

	@Test
	public void getPatientFromArgs_shouldExtractPatientFromReturnValue() {
		Patient patient = new Patient(1);
		Encounter encounter = new Encounter();
		encounter.setPatient(patient);

		Patient result = advice.getPatientFromArgs(encounter, new Object[] {});
		assertEquals(patient, result);
	}

	@Test
	public void getPatientFromArgs_shouldExtractPatientFromFirstArg() {
		Patient patient = new Patient(2);
		Encounter encounter = new Encounter();
		encounter.setPatient(patient);

		Patient result = advice.getPatientFromArgs(null, new Object[] { encounter });
		assertEquals(patient, result);
	}

	@Test
	public void getPatientFromArgs_shouldReturnNullWhenReturnValueIsNotEncounter() {
		Patient result = advice.getPatientFromArgs("not an encounter", new Object[] {});
		assertNull(result);
	}

	@Test
	public void getPatientFromArgs_shouldReturnNullWhenArgsAreEmpty() {
		Patient result = advice.getPatientFromArgs(null, new Object[] {});
		assertNull(result);
	}

	@Test
	public void getPatientFromArgs_shouldReturnNullWhenArgsAreNull() {
		Patient result = advice.getPatientFromArgs(null, null);
		assertNull(result);
	}

	@Test
	public void getPatientFromArgs_shouldPreferReturnValueOverArgs() {
		Patient patient1 = new Patient(1);
		Patient patient2 = new Patient(2);
		Encounter enc1 = new Encounter();
		enc1.setPatient(patient1);
		Encounter enc2 = new Encounter();
		enc2.setPatient(patient2);

		Patient result = advice.getPatientFromArgs(enc1, new Object[] { enc2 });
		assertEquals(patient1, result);
	}
}
