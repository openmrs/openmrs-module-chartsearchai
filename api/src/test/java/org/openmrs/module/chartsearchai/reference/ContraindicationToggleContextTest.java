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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * {@code chartsearchai.drugSafety.warnOnContraindications} silences the active-order contraindication
 * arm (issue #143) exactly as it silences the drug-in-play ones — they share the one flag
 * {@code validate} resolves per call.
 *
 * <p>Context-sensitive because the point IS the global property: the case writes a real GP through the
 * admin service and reads it back through the real {@code validate} path, so the assertion cannot pass
 * on the hardcoded default. Every contextless case in {@link ActiveOrderContraindicationTest} runs with
 * the GP absent, which fails safe to {@code true} — so none of them can distinguish an arm that honours
 * the operator's switch from one that ignores it (mutation-verified: calling the arm unconditionally
 * left all 889 tests green before this case existed).
 *
 * <p>What the switch means matters here more than for the arms it already gated. Those fire only for a
 * drug the question or the answer names, so an operator who turns them off is declining an answer-scoped
 * annotation. This arm fires on EVERY question about an allergy-bearing patient on a related drug, and
 * every chip it raises is also injected into the prompt as a citable finding (issue #110) — so an arm
 * that ignored the flag would keep writing contraindications into the context window of a deployment
 * that had switched contraindication reporting off, and the answer could assert one with no chip beside
 * it. Both directions are asserted, because a 0-chip assertion under a disabled flag proves nothing
 * unless the same arrangement provably chips when it is enabled.
 */
public class ContraindicationToggleContextTest extends BaseModuleContextSensitiveTest {

	/** Writes the GP the way an implementation would. */
	private void configureContraindicationWarnings(String value) {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, value);
	}

	/**
	 * The arm's own shape, on the bundled curated dataset: a patient prescribed ibuprofen and allergic
	 * to it, asked a question that names no drug — so nothing but this arm can raise a contraindication.
	 */
	private List<SafetyWarning> chipsForAPrescribedAllergy() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.bundledService()).validate("",
				"What are her current medications?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Ibuprofen 400mg"),
						null, DrugReferenceTestSupport.set("ibuprofen"), null));
	}

	@Test
	public void theActiveOrderArmStandsDownWhenContraindicationWarningsAreOff() {
		configureContraindicationWarnings("false");

		assertTrue(chipsForAPrescribedAllergy().isEmpty(),
				"an operator who switched contraindication warnings off must get none from the "
						+ "active-order arm either");
	}

	@Test
	public void theSameArrangementChipsWhenContraindicationWarningsAreOn() {
		// The discriminator for the case above: written explicitly rather than left to the default, so
		// this reads the same GP through the same path and the pair of cases isolates the flag itself.
		configureContraindicationWarnings("true");

		assertEquals(2, chipsForAPrescribedAllergy().size(),
				"with the switch on, the prescribed allergy must still raise its two chips");
	}
}
