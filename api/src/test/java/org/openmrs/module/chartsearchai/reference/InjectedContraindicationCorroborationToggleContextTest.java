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

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The third section of the contraindication reading (issue #269) stands down with the
 * {@code drugSafety} switches, like the two halves beside it.
 *
 * <p>{@code InjectedContraindicationReadingToggleContextTest} exists because a citable record must not
 * claim anything about the patient with the chips switched off, and it asserts both existing leads by
 * substring. The lead this file is about shares no substring with either, so that file cannot see it,
 * and its own arrangement is a CORROBORATED allergy which never renders this section at all — there is
 * no case there to extend.
 *
 * <p>The section does say something about the patient's chart ("Matched in this patient's chart …"),
 * which is what puts it on the switches' side of {@code render}'s divide rather than beside the
 * clause list or {@code rowAttribution}. Both directions are asserted, because a "no section" assertion
 * under a disabled flag proves nothing unless the same arrangement provably renders it when enabled.
 */
public class InjectedContraindicationCorroborationToggleContextTest
		extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "Is it safe to give her opium?";

	private void configure(String property, String value) {
		Context.getAdministrationService().setGlobalProperty(property, value);
	}

	/** Issue #223's fixture and its one recorded allergy `Tiotropium` — the arrangement whose only
	 *  evidence for the `opium` rule is bare containment, so the reading renders this section and
	 *  neither of the other two. */
	private String opiumRecord() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport
				.fixtureEntries("chartsearchai-test/drug-reference-mid-word-allergy-token.json"));
		return DrugReferenceTestSupport.referenceTextNaming(
				DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
						DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("Tiotropium"), null),
						QUESTION),
				"Opium");
	}

	private void assertSectionRendered(boolean expected) throws IOException {
		String record = opiumRecord();
		assertNotNull(record, "the reference record itself must be injected either way");
		assertTrue(record.contains(" Contraindicated with: documented opium allergy"),
				"and must carry the drug's own list either way — the toggles govern what is claimed "
						+ "about the PATIENT, not what the reference material is, was: " + record);
		if (expected) {
			assertTrue(record.contains(DrugReferenceInjector.UNCORROBORATED_READING_LEAD
					+ "documented opium allergy."),
					"with the switches on, the uncorroborated section must name the clause, was: "
							+ record);
		} else {
			assertFalse(record.contains(DrugReferenceInjector.UNCORROBORATED_READING_LEAD),
					"with the switch off, the record may say nothing about this patient's chart, was: "
							+ record);
		}
	}

	@Test
	public void theUncorroboratedSectionStandsDownWhenAnswerValidationIsOff() throws IOException {
		configure(ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS, "false");

		assertSectionRendered(false);
	}

	@Test
	public void theUncorroboratedSectionStandsDownWhenContraindicationWarningsAreOff()
			throws IOException {
		configure(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "false");

		assertSectionRendered(false);
	}

	@Test
	public void theSameArrangementRendersItWhenBothSwitchesAreOn() throws IOException {
		// The discriminator for both cases above, written explicitly rather than left to the defaults so
		// the three cases isolate the flags themselves.
		configure(ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS, "true");
		configure(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "true");

		assertSectionRendered(true);
	}
}
