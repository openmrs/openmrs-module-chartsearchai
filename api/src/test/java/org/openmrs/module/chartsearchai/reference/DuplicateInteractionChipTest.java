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

/**
 * A co-medication that is BOTH an explicit interaction partner AND in the same ATC subgroup used to
 * raise two {@link SafetyWarning#TYPE_INTERACTION} chips (rule arm + class arm). The two arms are now
 * folded into one chip per pair. Driven through the real {@link DrugSafetyValidator} over a fixture
 * where lisinopril/enalapril each interact with ramipril and all three share subgroup C09AA.
 */
public class DuplicateInteractionChipTest {

	private static final String FIXTURE = "chartsearchai-test/drug-reference-sameclass.json";

	private DrugSafetyValidator validator() throws Exception {
		return DrugReferenceTestSupport.validator(
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(FIXTURE)));
	}

	/** Active order: ramipril (ATC C09AA05). */
	private PatientClinicalContext ramiprilActive() {
		return DrugReferenceTestSupport.ctx(60, null, null, DrugReferenceTestSupport.set("C09AA05"), null, null);
	}

	private long interactionCount(List<SafetyWarning> warnings, String drug) {
		return warnings.stream()
				.filter(w -> SafetyWarning.TYPE_INTERACTION.equals(w.getType())
						&& drug.equalsIgnoreCase(w.getDrug()))
				.count();
	}

	@Test
	public void sameClassPairWithMechanismFoldsToOneContentfulChip() throws Exception {
		// Lisinopril asked about, ramipril active: explicit interaction (with a mechanism) AND same
		// ATC subgroup C09AA. One chip, carrying both the duplicate-therapy relationship and the note.
		List<SafetyWarning> warnings = validator().validate(
				"Lisinopril is a reasonable choice.", ramiprilActive());

		assertEquals(1, interactionCount(warnings, "Lisinopril"),
				"the rule arm and the class arm must fold into a single interaction chip, not two");
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Lisinopril", "duplicate therapy", "additive hyperkalemia"),
				"the single chip should carry both the class/duplicate-therapy relationship and the mechanism");
	}

	@Test
	public void sameClassPairWithoutMechanismStillYieldsTheInformativeClassChip() throws Exception {
		// Enalapril's ramipril interaction carries no mechanism note. Naively preferring the rule arm
		// would drop to a contentless chip; folding keeps the informative class relationship.
		List<SafetyWarning> warnings = validator().validate(
				"Enalapril is a reasonable choice.", ramiprilActive());

		assertEquals(1, interactionCount(warnings, "Enalapril"),
				"still exactly one chip for the pair");
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Enalapril", "duplicate therapy", "C09AA"),
				"with no mechanism note, the surviving chip must still carry the class/duplicate-therapy info");
	}
}
