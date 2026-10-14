/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.openmrs.Allergen;
import org.openmrs.AllergenType;
import org.openmrs.Allergy;
import org.openmrs.Concept;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Which recorded allergies the drug data does not identify are reported unchecked (ADR Decision 223): a drug allergy, and
 * never a food or environmental one, read off the patient's own allergy records by the real context builder.
 */
public class UncheckedDrugAllergyContextTest extends BaseModuleContextSensitiveTest {

	@Test
	public void onlyADrugAllergyTheDataDoesNotIdentifyIsReportedUnchecked() {
		Patient patient = Context.getPatientService().getPatient(7);
		// A coded food allergen (any concept: its name resolves to no drug), and a free-text drug allergen. One placeholder
		// concept cannot carry both, the platform reading two non-coded allergens on it as one.
		Context.getPatientService().saveAllergy(new Allergy(patient,
			new Allergen(AllergenType.FOOD, Context.getConceptService().getConcept(5089), null), null, null, null));
		record(patient, AllergenType.DRUG, "Penicillins");

		PatientClinicalContext context = PatientClinicalContextBuilder.build(Context.getPatientService().getPatient(7));

		assertEquals(Arrays.asList("penicillins"), DrugSafetyValidator.unresolvedDrugAllergens(
			DrugReferenceTestSupport.shippedServiceWithGroups(), context));
	}

	private static void record(Patient patient, AllergenType type, String allergen) {
		Concept otherNonCoded = Context.getConceptService().getConcept(88);
		Context.getAdministrationService().setGlobalProperty("allergy.concept.otherNonCoded", otherNonCoded.getUuid());
		Context.getPatientService().saveAllergy(new Allergy(patient, new Allergen(type, otherNonCoded, allergen), null,
			null, null));
		Context.flushSession();
		Context.clearSession();
	}
}
