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
 * Exercises {@link PatientClinicalContext#hasActiveDrug} through the real
 * {@link DrugSafetyValidator} over a fixture whose drug names are substring-nested
 * ("chlorothiazide" ⊂ "hydrochlorothiazide"). An interaction token that is merely a sub-token of a
 * longer active-order name must not raise a warning naming a drug the patient is not on.
 *
 * <p>The discriminating rule is the LEFT word boundary, which is all these three cases need — the
 * class name records the whole-word fix this file was written for (issue #86), not the rule that
 * shipped. {@link DrugReference#matchesOrderName} additionally tolerates a bounded inflectional
 * tail, because requiring a boundary on the RIGHT too stops a patient on {@code Aspirine Co 81mg}
 * being checked for aspirin interactions at all. That half of the contract — and the bound on it —
 * lives in {@link DrugSafetyOrderNameMatchingTest}; do not "restore" whole-word symmetry here
 * without reading it first.
 */
public class HasActiveDrugWholeWordTest {

	private static final String FIXTURE = "chartsearchai-test/drug-reference-substring-nested.json";

	private DrugSafetyValidator validatorOverFixture() throws Exception {
		return DrugReferenceTestSupport.validator(
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(FIXTURE)));
	}

	private long interactionCount(List<SafetyWarning> warnings, String drug) {
		return warnings.stream()
				.filter(w -> SafetyWarning.TYPE_INTERACTION.equals(w.getType())
						&& drug.equalsIgnoreCase(w.getDrug()))
				.count();
	}

	@Test
	public void subtokenOfLongerOrderNameDoesNotFire() throws Exception {
		// Patient is on hydrochlorothiazide; ibuprofen interacts with BOTH hydrochlorothiazide and
		// chlorothiazide. Only the hydrochlorothiazide rule should fire — the chlorothiazide rule
		// must not, even though "chlorothiazide" is a substring of "hydrochlorothiazide".
		List<SafetyWarning> warnings = validatorOverFixture().validate(
				"Ibuprofen is a reasonable analgesic choice.",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("hydrochlorothiazide"),
						null, null, null));

		assertEquals(1, interactionCount(warnings, "Ibuprofen"),
				"exactly one interaction should fire (hydrochlorothiazide), not the nested chlorothiazide");
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Ibuprofen", "hydrochlorothiazide"),
				"the fired interaction should name the drug actually on the chart");
	}

	@Test
	public void wholeWordInAMultiWordOrderNameStillFires() throws Exception {
		// Real order display names carry dose/form suffixes; a whole-word token must still match.
		List<SafetyWarning> warnings = validatorOverFixture().validate(
				"Ibuprofen is a reasonable analgesic choice.",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("hydrochlorothiazide 25 mg tablet"), null, null, null));

		assertEquals(1, interactionCount(warnings, "Ibuprofen"),
				"the whole-word token should match within a dose/form-qualified order name");
	}

	@Test
	public void theNestedShorterDrugStillFiresOnItsOwnOrder() throws Exception {
		// The other side of the same nested pair, so the fix is de-duplication and not suppression of
		// the shorter name: a chlorothiazide order must still raise chlorothiazide's own rule. Without
		// this, a matcher that simply never matched the shorter token would pass the two tests above.
		List<SafetyWarning> warnings = validatorOverFixture().validate(
				"Ibuprofen is a reasonable analgesic choice.",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("chlorothiazide 500 mg"),
						null, null, null));

		assertEquals(1, interactionCount(warnings, "Ibuprofen"),
				"exactly one interaction should fire (chlorothiazide), and the longer hydrochlorothiazide "
						+ "token must not match a chlorothiazide order either");
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Ibuprofen", "chlorothiazide"),
				"the fired interaction should name the drug actually on the chart");
	}
}
