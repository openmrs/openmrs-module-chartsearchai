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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A free-text allergy is saved on core's "other non-coded" placeholder concept — the concept the
 * {@code allergy.concept.otherNonCoded} global property names — and that concept's name is no allergen (ADR Decision 227).
 *
 * <p><b>The case.</b> On the 3.7.1 standalone the placeholder is CIEL's "Other", and every module-written answer for a
 * patient with a free-text drug allergy closed <em>"Not checked: a recorded allergy the drug data does not identify —
 * other."</em> (Melissa Wright's "Vitamin A, B", George Anderson's "NSAID", 2026-10-15). Where the placeholder carries a
 * drug's name, the patient was read as allergic to that drug besides the one the clinician typed.
 *
 * <p>Everything is real: the patients of the standard dataset, the allergies saved through {@code PatientService}, the real
 * {@link PatientClinicalContextBuilder}, and the real injector over the shipped knowledge base.
 */
public class FreeTextAllergenPlaceholderContextTest extends BaseModuleContextSensitiveTest {

	/** WEIGHT (KG), a concept no drug resolves from, standing in for CIEL's "Other" placeholder. */
	private static final int PLACEHOLDER_NAMING_NO_DRUG = 5089;

	/** ASPIRIN, standing in for a placeholder concept a drug's name resolves from. */
	private static final int PLACEHOLDER_NAMING_A_DRUG = 88;

	/** Patient 6 of the standard dataset, who has no active drug order. */
	private static final int PATIENT_WITH_NO_ORDER = 6;

	/** Patient 7 of the standard dataset, on her aspirin order 111. */
	private static final int PATIENT_ON_ASPIRIN = 7;

	private static DrugReferenceService shipped;

	private static synchronized DrugReferenceService shipped() {
		if (shipped == null) {
			shipped = DrugReferenceTestSupport.shippedServiceWithGroups();
		}
		return shipped;
	}

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void thePlaceholderBehindAFreeTextAllergyIsNotReportedUnchecked() {
		Patient patient = Context.getPatientService().getPatient(PATIENT_ON_ASPIRIN);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, PLACEHOLDER_NAMING_NO_DRUG, "Penicillins");

		PatientClinicalContext context = PatientClinicalContextBuilder.build(
			Context.getPatientService().getPatient(PATIENT_ON_ASPIRIN));

		assertEquals(Arrays.asList("penicillins"), DrugSafetyValidator.unresolvedDrugAllergens(shipped(), context));
	}

	/**
	 * The answer names the allergy as the chart records it, never the placeholder: "NSAID", where the line printed "other;
	 * nsaid" on the standalone.
	 */
	@Test
	public void theUncheckedLineNamesTheFreeTextAllergenAsTheChartRecordsIt() {
		Patient patient = Context.getPatientService().getPatient(PATIENT_ON_ASPIRIN);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, PLACEHOLDER_NAMING_NO_DRUG, "NSAID");

		String answer = ask(PATIENT_ON_ASPIRIN, "Can I give her amoxicillin?").getModuleAnswer();

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("Not checked: a recorded allergy the drug data does not identify — NSAID. Whether it covers "
				+ "Amoxicillin is not established.", lines[lines.length - 1], "was: " + answer);
	}

	/** A placeholder carrying a drug's name records no allergy to that drug: the clinician typed "Penicillins". */
	@Test
	public void aPlaceholderNamedLikeADrugRecordsNoAllergyToThatDrug() {
		Patient patient = Context.getPatientService().getPatient(PATIENT_WITH_NO_ORDER);
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, PLACEHOLDER_NAMING_A_DRUG, "Penicillins");

		PatientChart chart = ask(PATIENT_WITH_NO_ORDER, "Can I give her aspirin?");

		assertEquals(Arrays.asList("penicillins"), Arrays.asList(PatientClinicalContextBuilder
				.build(Context.getPatientService().getPatient(PATIENT_WITH_NO_ORDER)).getAllergyTokens().toArray()));
		String answer = chart.getModuleAnswer();
		assertNotNull(answer, "the module answers");
		assertFalse(answer.contains("allergy to Acetylsalicylic acid"), "was: " + answer);
	}

	private PatientChart ask(int patientId, String question) {
		return DrugReferenceTestSupport.injectorWithSafety(shipped()).injectRecords(DrugReferenceTestSupport.oneRecordChart(),
			PatientClinicalContextBuilder.build(Context.getPatientService().getPatient(patientId)), question);
	}
}
