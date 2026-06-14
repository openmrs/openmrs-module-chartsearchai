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
import org.openmrs.Diagnosis;
import org.openmrs.MedicationDispense;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;

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
	public void extractPatient_shouldExtractPatientArgOnSavePatient() {
		// Demographics (age from birthdate, and sex) are part of the serialized chart the LLM cites, so
		// a savePatient must invalidate cached answers — otherwise "how old is this patient?" goes stale
		// after a birthdate correction. PatientService is already advised; this is the extraction case.
		Patient patient = new Patient(7);

		Patient result = advice.extractPatient("savePatient", new Object[] { patient });
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
	public void extractPatient_shouldExtractFromDiagnosisOnSave() {
		Patient patient = new Patient(5);
		Diagnosis diagnosis = new Diagnosis();
		diagnosis.setPatient(patient);

		Patient result = advice.extractPatient("save", new Object[] { diagnosis });
		assertEquals(patient, result);
	}

	@Test
	public void extractPatient_shouldExtractFromDiagnosisOnVoidDiagnosis() {
		Patient patient = new Patient(5);
		Diagnosis diagnosis = new Diagnosis();
		diagnosis.setPatient(patient);

		Patient result = advice.extractPatient("voidDiagnosis", new Object[] { diagnosis });
		assertEquals(patient, result);
	}

	@Test
	public void extractPatient_shouldExtractFromConditionOnPurgeCondition() {
		Patient patient = new Patient(1);
		Condition condition = new Condition();
		condition.setPatient(patient);

		Patient result = advice.extractPatient("purgeCondition", new Object[] { condition });
		assertEquals(patient, result);
	}

	@Test
	public void extractPatient_shouldExtractFromOrderOnDiscontinueOrder() {
		Patient patient = new Patient(4);
		Order order = new Order();
		order.setPatient(patient);

		Patient result = advice.extractPatient("discontinueOrder", new Object[] { order });
		assertEquals(patient, result);
	}

	@Test
	public void extractPatient_shouldExtractFromOrderOnPurgeOrder() {
		Patient patient = new Patient(4);
		Order order = new Order();
		order.setPatient(patient);

		Patient result = advice.extractPatient("purgeOrder", new Object[] { order });
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

	@Test
	public void extractPatient_shouldExtractFromPatientProgramOnSave() {
		Patient patient = new Patient(6);
		PatientProgram pp = new PatientProgram();
		pp.setPatient(patient);

		Patient result = advice.extractPatient("savePatientProgram", new Object[] { pp });
		assertEquals(patient, result);
	}

	@Test
	public void extractPatient_shouldExtractFromPatientProgramOnVoid() {
		Patient patient = new Patient(6);
		PatientProgram pp = new PatientProgram();
		pp.setPatient(patient);

		Patient result = advice.extractPatient("voidPatientProgram", new Object[] { pp });
		assertEquals(patient, result);
	}

	@Test
	public void extractPatient_shouldExtractFromMedicationDispenseOnSave() {
		Patient patient = new Patient(7);
		MedicationDispense dispense = new MedicationDispense();
		dispense.setPatient(patient);

		Patient result = advice.extractPatient("saveMedicationDispense", new Object[] { dispense });
		assertEquals(patient, result);
	}

	@Test
	public void extractPatient_shouldExtractFromMedicationDispenseOnVoid() {
		Patient patient = new Patient(7);
		MedicationDispense dispense = new MedicationDispense();
		dispense.setPatient(patient);

		Patient result = advice.extractPatient("voidMedicationDispense", new Object[] { dispense });
		assertEquals(patient, result);
	}
}
