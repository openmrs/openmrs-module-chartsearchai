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
import org.openmrs.Allergy;
import org.openmrs.Condition;
import org.openmrs.Order;
import org.openmrs.Patient;

/**
 * Pure unit tests for {@link PatientDataIndexingAdvice} patient extraction logic.
 */
public class PatientDataIndexingAdviceTest {

	private final PatientDataIndexingAdvice advice = new PatientDataIndexingAdvice();

	@Test
	public void extractPatient_shouldExtractFromConditionOnSaveCondition() {
		Patient patient = new Patient(1);
		Condition condition = new Condition();
		condition.setPatient(patient);

		Patient result = advice.extractPatient("saveCondition", new Object[] { condition });
		assertEquals(patient, result);
	}

	@Test
	public void extractPatient_shouldExtractFromConditionOnVoidCondition() {
		Patient patient = new Patient(1);
		Condition condition = new Condition();
		condition.setPatient(patient);

		Patient result = advice.extractPatient("voidCondition", new Object[] { condition });
		assertEquals(patient, result);
	}

	@Test
	public void extractPatient_shouldExtractPatientArgOnSetAllergies() {
		Patient patient = new Patient(2);

		Patient result = advice.extractPatient("setAllergies", new Object[] { patient });
		assertEquals(patient, result);
	}

	@Test
	public void extractPatient_shouldExtractFromAllergyOnSaveAllergy() {
		Patient patient = new Patient(3);
		Allergy allergy = new Allergy(patient, null, null, null, null);

		Patient result = advice.extractPatient("saveAllergy", new Object[] { allergy });
		assertEquals(patient, result);
	}

	@Test
	public void extractPatient_shouldExtractFromOrderOnSaveOrder() {
		Patient patient = new Patient(4);
		Order order = new Order();
		order.setPatient(patient);

		Patient result = advice.extractPatient("saveOrder", new Object[] { order });
		assertEquals(patient, result);
	}

	@Test
	public void extractPatient_shouldExtractFromOrderOnVoidOrder() {
		Patient patient = new Patient(4);
		Order order = new Order();
		order.setPatient(patient);

		Patient result = advice.extractPatient("voidOrder", new Object[] { order });
		assertEquals(patient, result);
	}

	@Test
	public void extractPatient_shouldReturnNullForUnrecognizedMethod() {
		Patient result = advice.extractPatient("getCondition", new Object[] { new Condition() });
		assertNull(result);
	}

	@Test
	public void extractPatient_shouldReturnNullWhenArgsAreEmpty() {
		Patient result = advice.extractPatient("saveCondition", new Object[] {});
		assertNull(result);
	}

	@Test
	public void extractPatient_shouldReturnNullWhenArgTypeDoesNotMatch() {
		Patient result = advice.extractPatient("saveCondition", new Object[] { "not a condition" });
		assertNull(result);
	}

	@Test
	public void extractPatient_shouldReturnNullForAllergyMethodWithWrongArgType() {
		Patient result = advice.extractPatient("saveAllergy", new Object[] { "not an allergy" });
		assertNull(result);
	}
}
