/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The injected record's patient-specific reading of a contraindication list (issue #208 item 2) is the
 * RECORD's half of a contraindication chip, so it stands down with the chips.
 *
 * <p>Neither toggle reaches {@code DrugReferenceInjector.injectRecords}, which gates itself on
 * {@code chartsearchai.drugReference.enabled} alone: the chips are gated further down, in
 * {@code preAnswerFindings} ({@code drugSafety.validateAnswers}) and in both contraindication arms
 * ({@code drugSafety.warnOnContraindications}). So an operator who switched contraindication reporting
 * off would have got a citable record asserting "Recorded for this patient: documented ibuprofen
 * allergy" into the prompt with no chip and no {@code safety_finding} record beside it — prose without a
 * chip, which is the divergence {@code preAnswerFindings} gates the first of those toggles to prevent,
 * and which {@code README} promises the {@code drugSafety.*} switches govern.
 *
 * <p>Context-sensitive because the point IS the global property, exactly as
 * {@link ContraindicationToggleContextTest} is for the arm it covers: the case writes a real GP through
 * the admin service and reads it back through the real {@code injectRecords}, so it cannot pass on the
 * hardcoded default. Every contextless case in {@code InjectedContraindicationPatientReadingTest} runs
 * with both GPs absent, which fails safe to {@code true}, so none of them can tell a rendering that
 * honours the operator's switch from one that ignores it.
 *
 * <p>Both directions are asserted for each switch: a "no reading" assertion under a disabled flag proves
 * nothing unless the same arrangement provably reads when it is enabled.
 */
public class InjectedContraindicationReadingToggleContextTest extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "Is ibuprofen safe for her?";

	private void configure(String property, String value) {
		Context.getAdministrationService().setGlobalProperty(property, value);
	}

	/** The shipped curated seed, a recorded ibuprofen allergy, and a question naming ibuprofen — the
	 *  arrangement that renders a reading naming one clause and withholding three. */
	private String ibuprofenRecord() {
		return DrugReferenceTestSupport.referenceTextNaming(
				DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.curatedService())
						.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
								DrugReferenceTestSupport.ctx(60, null, null, null,
										DrugReferenceTestSupport.set("Ibuprofen"), null),
								QUESTION),
				"Ibuprofen");
	}

	private void assertReadingRendered(boolean expected) {
		String record = ibuprofenRecord();
		assertNotNull(record, "the reference record itself must be injected either way, was: " + record);
		assertTrue(record.contains(" Contraindicated with: "),
				"and must carry the drug's own list either way — the toggles govern what is claimed "
						+ "about the PATIENT, not what the reference material is, was: " + record);
		if (expected) {
			assertTrue(record.contains(" Recorded for this patient: documented ibuprofen allergy."),
					"with the switches on, the reading must name the matched rule, was: " + record);
			assertTrue(record.contains(" Not recorded for this patient: "),
					"and the unrecorded half beside it, was: " + record);
		} else {
			// BOTH leads, and not just the positive one: " Not recorded for this patient: " does not
			// contain "Recorded for this patient" (the capital R), so a regression that gated only the
			// positive half would leave a negative claim about the patient in a citable record with the
			// chips switched off — the very thing this file exists to forbid — and pass.
			assertFalse(record.contains("Recorded for this patient"),
					"with the switch off, the record may claim nothing about this patient, was: " + record);
			assertFalse(record.contains("Not recorded for this patient"),
					"on either side of the split, was: " + record);
		}
	}

	@Test
	public void theReadingStandsDownWhenAnswerValidationIsOff() {
		configure(ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS, "false");

		assertReadingRendered(false);
	}

	@Test
	public void theReadingStandsDownWhenContraindicationWarningsAreOff() {
		configure(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "false");

		assertReadingRendered(false);
	}

	@Test
	public void theSameArrangementReadsWhenBothSwitchesAreOn() {
		// The discriminator for both cases above, written explicitly rather than left to the defaults so
		// the three cases isolate the flags themselves.
		configure(ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS, "true");
		configure(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "true");

		assertReadingRendered(true);
	}
}
