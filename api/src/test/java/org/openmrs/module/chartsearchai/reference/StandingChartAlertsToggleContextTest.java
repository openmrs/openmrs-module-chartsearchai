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
 * {@code chartsearchai.drugSafety.warnOnContraindications} silences the standing surface (issue #280)
 * exactly as it silences the answer one — the flag is read in the one place both passes go through.
 *
 * <p>Context-sensitive because the point IS the global property: each case writes a real GP through
 * the admin service and reads it back through the real {@code standingChartAlerts} path, so neither
 * can pass on the hardcoded default. Every case in {@link StandingChartAlertsTest} runs with the GP
 * absent, which fails safe to {@code true}, so none of them can tell a surface that honours the
 * operator's switch from one that ignores it.
 *
 * <p>Both directions, because a 0-alert assertion under a disabled flag proves nothing unless the same
 * arrangement provably alerts when it is enabled — the reason
 * {@code ContraindicationToggleContextTest}, whose shape this follows, states its own pair.
 *
 * <p>The two GPs ABOVE this seam ({@code chartsearchai.drugReference.enabled} and
 * {@code chartsearchai.drugSafety.validateAnswers}) are not reachable from here: they gate the
 * {@code Patient}-taking entry, whose chart read needs a patient with a real allergy and a real active
 * order. {@code StandingChartAlertsTest.theGateOnTheStandingSurfaceIsTheOneTheAnswerSurfaceReads} pins
 * those structurally instead, and says what that does and does not reach.
 */
public class StandingChartAlertsToggleContextTest extends BaseModuleContextSensitiveTest {

	/** Writes the GP the way an implementation would. */
	private void configureContraindicationWarnings(String value) {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, value);
	}

	/**
	 * The standing surface's own shape, on the bundled curated dataset: a patient prescribed ibuprofen
	 * and allergic to it, with no response at all — so nothing but the active-order contraindication
	 * arm can raise anything.
	 */
	private List<SafetyWarning> alertsForAPrescribedAllergy() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService())
				.standingChartAlerts(DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Ibuprofen 400mg"), null,
						DrugReferenceTestSupport.set("ibuprofen"), null));
	}

	@Test
	public void theStandingSurfaceStandsDownWhenContraindicationWarningsAreOff() {
		configureContraindicationWarnings("false");

		assertTrue(alertsForAPrescribedAllergy().isEmpty(),
				"an operator who switched contraindication warnings off must get none from the "
						+ "standing surface either");
	}

	@Test
	public void theSameChartAlertsWhenContraindicationWarningsAreOn() {
		// The discriminator for the case above: written explicitly rather than left to the default, so
		// this reads the same GP through the same path and the pair of cases isolates the flag itself.
		configureContraindicationWarnings("true");

		assertEquals(1, alertsForAPrescribedAllergy().size(),
				"with the switch on, the prescribed allergy must still be a standing alert");
	}
}
