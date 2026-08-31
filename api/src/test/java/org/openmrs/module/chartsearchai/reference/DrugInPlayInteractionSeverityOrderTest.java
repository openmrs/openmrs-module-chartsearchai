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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Issue #346 — the drug-in-play interaction arm ({@link DrugSafetyValidator#addInteractionWarnings})
 * appended one chip per partner in the knowledge base's own row order, with no severity sort, unlike
 * the other two interaction arms ({@code addQuestionPairInteractions}, {@code
 * addActiveOrderPairInteractions}), which both sort most-severe-first before emitting
 * ({@code PAIR_SEVERITY_DESCENDING}, {@code SCREENED_PAIR_SEVERITY_DESCENDING}). An LLM answer that
 * enumerates the chips it is given and stops partway through then drops whichever finding happens to
 * sit last in the KB — which can be the most severe one, exactly as it was live on issue #346's
 * warfarin/ibuprofen reproduction.
 *
 * <p>Runs the real production path: a verbatim-shaped DDInter fixture parsed by the real
 * {@link DdiDrugReferenceSource}, and the real {@link DrugSafetyValidator#validate} entry point.
 */
public class DrugInPlayInteractionSeverityOrderTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-drug-in-play-severity-order.json";

	private static List<SafetyWarning> interactionChips(List<SafetyWarning> warnings) {
		List<SafetyWarning> out = new ArrayList<SafetyWarning>();
		for (SafetyWarning warning : warnings) {
			if (SafetyWarning.TYPE_INTERACTION.equals(warning.getType())) {
				out.add(warning);
			}
		}
		return out;
	}

	private static List<String> severities(List<SafetyWarning> warnings) {
		List<String> out = new ArrayList<String>();
		for (SafetyWarning warning : warnings) {
			out.add(warning.getSeverity());
		}
		return out;
	}

	@Test
	public void theDrugInPlayArmOrdersItsChipsMostSevereFirst() throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		// The fixture's own KB row order is Moderate, Minor, Major — the shape issue #346 reproduced
		// live, where the Major finding sits last and is exactly the one an answer that stops partway
		// through an enumeration drops.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Alphadrug", "Betadrug", "Gammadrug"), null, null, null);

		List<SafetyWarning> chips = interactionChips(DrugReferenceTestSupport.validator(service)
				.validate("", "Is it safe to give her Coagulon?", context));

		assertEquals(3, chips.size(), "one chip per active-order partner, was: " + chips);
		assertEquals(Arrays.asList("Major", "Moderate", "Minor"), severities(chips),
				"the drug-in-play arm must order its chips most-severe-first, exactly as the other two "
						+ "interaction arms already do, not in the knowledge base's own row order, was: "
						+ chips);
	}
}
