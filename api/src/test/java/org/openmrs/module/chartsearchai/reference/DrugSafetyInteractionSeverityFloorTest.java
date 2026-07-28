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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Severity floor for rule-based interaction chips (the #84 severity follow-up): DDInter's
 * per-row severity is now a structured field on {@link DrugReference.Interaction}, and rule
 * chips below {@code chartsearchai.drugSafety.minInteractionSeverity} (default {@code minor})
 * are not raised. Measured motivation (2026-07-29, "Is it safe to give her aspirin?" on a
 * simvastatin patient): the severe-allergy contraindication chip shared equal billing with an
 * aspirin x simvastatin row of Unknown severity and no mechanism text — 42,415 of the full
 * KB's 295,184 rows (14%) are that shape, all information-free. The floor filters exactly
 * them by default while Major/Moderate/Minor rules, class-based chips, contraindications, and
 * curated rules without a severity (all deliberate, hand-authored) are untouched.
 *
 * <p>All scenarios run the real pipeline: real bundled DDInter sample (or curated seed) parsed
 * by the real sources, real validate overloads, GP reads on their no-context defaults.
 */
public class DrugSafetyInteractionSeverityFloorTest {

	private static DrugSafetyValidator ddinterValidator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService());
	}

	@Test
	public void ddinterInteractionsCarryStructuredSeverity() {
		DrugReference warfarin = new DdiDrugReferenceSource().load().stream()
				.filter(r -> "Warfarin".equalsIgnoreCase(r.getName())).findFirst().orElseThrow();
		DrugReference.Interaction ibuprofen = warfarin.getInteractions().stream()
				.filter(i -> "ibuprofen".equals(i.getToken())).findFirst().orElseThrow();
		assertEquals("Major", ibuprofen.getSeverity(),
				"the DDInter row severity must be a structured field, not only note prose");
	}

	@Test
	public void unknownSeverityRuleChipIsFilteredByDefault() {
		// The aspirin x simvastatin shape: a real row whose severity is Unknown with no
		// mechanism text. Under the default floor (minor) it must not chip — it carries no
		// actionable content and dilutes the chips that do.
		List<SafetyWarning> warnings = ddinterValidator().validate(
				"Aspirin could be considered for cardioprotection.", "Can she take aspirin?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Simvastatin"),
						DrugReferenceTestSupport.set("C10AA01"), null, null));

		assertFalse(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "acetylsalicylic"),
				"an Unknown-severity, no-mechanism rule must not chip under the default floor, was: "
						+ warnings);
	}

	@Test
	public void moderateSeverityRuleChipStillFires() {
		// Boundary pin one step above the default floor: aspirin x lisinopril is a Moderate
		// row in the bundled sample and must keep chipping.
		List<SafetyWarning> warnings = ddinterValidator().validate(
				"Aspirin could be considered for cardioprotection.", "Can she take aspirin?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Lisinopril"),
						DrugReferenceTestSupport.set("C09AA03"), null, null));

		assertTrue(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "acetylsalicylic"),
				"a Moderate rule sits above the default floor and must still chip");
	}

	@Test
	public void curatedRuleWithoutSeverityIsNeverFiltered() {
		// The curated seed's hand-authored rules carry no severity field; absent severity is
		// exempt from the floor — every curated rule is deliberate.
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.bundledService());
		List<SafetyWarning> warnings = validator.validate(
				"Ibuprofen would be a reasonable choice.", "What can we give for pain?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Warfarin"),
						DrugReferenceTestSupport.set("B01AA03"), null, null));

		assertTrue(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "ibuprofen"),
				"a curated rule without a severity must never be floor-filtered");
	}

	@Test
	public void classBasedChipsAreUnaffectedByTheFloor() {
		// The floor governs rule-based chips only: the class arm (duplicate therapy) carries no
		// severity and keeps firing — with the Unknown-severity ramipril x lisinopril rule chip
		// filtered, the duplicate-therapy chip is what remains of that pair (also trims #88's
		// double-chip for Unknown-severity same-class pairs down to one).
		List<SafetyWarning> warnings = ddinterValidator().validate(
				"Lisinopril could be added.", "Can we add lisinopril?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Enalapril"),
						DrugReferenceTestSupport.set("C09AA02"), null, null));

		assertNotNull(warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Lisinopril", "same ATC class"),
				"the class-based duplicate-therapy chip must be unaffected by the severity floor, was: "
						+ warnings);
	}
}
