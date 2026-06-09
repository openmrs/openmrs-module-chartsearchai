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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Realizes the draft PR's drug-safety eval cases as real-pipeline tests: each runs
 * the production {@link DrugSafetyValidator#validate(String, PatientClinicalContext)}
 * over the real bundled dataset with a hand-built clinical context (the value-object
 * input shape the production builder produces). Per-check toggles fall back to their
 * {@code true} defaults with no OpenMRS context, matching production defaults.
 */
public class DrugSafetyValidatorTest {

	private DrugSafetyValidator validator() {
		DrugSafetyValidator validator = new DrugSafetyValidator();
		validator.setDrugReferenceService(new DrugReferenceService());
		return validator;
	}

	private PatientClinicalContext ctx(Integer age, Set<String> drugs, Set<String> allergies,
			Set<String> conditions) {
		return new PatientClinicalContext(age,
				drugs == null ? Collections.<String> emptySet() : drugs,
				Collections.<String> emptySet(),
				allergies == null ? Collections.<String> emptySet() : allergies,
				conditions == null ? Collections.<String> emptySet() : conditions);
	}

	private boolean has(List<SafetyWarning> warnings, String type, String drugContains) {
		for (SafetyWarning w : warnings) {
			if (w.getType().equals(type) && w.getDrug().toLowerCase().contains(drugContains.toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	private Set<String> set(String... values) {
		return new HashSet<String>(Arrays.asList(values));
	}

	@Test
	public void overdoseIsFlaggedWhenDailyTotalExceedsMax() {
		// 600 mg x4/day = 2400 mg/day, over the 1200 mg/day pediatric (2-11) maximum.
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen 600 mg every 6 hours can be given for pain.",
				ctx(5, null, null, null));
		assertTrue(has(warnings, SafetyWarning.TYPE_OVERDOSE, "ibuprofen"),
				"a daily total over the age-band maximum should be flagged");
	}

	@Test
	public void overdoseUsesEveryNHoursFrequency() {
		// 500 mg every 8 hours = 1500 mg/day > 1200 max.
		List<SafetyWarning> warnings = validator().validate(
				"Give ibuprofen 500 mg every 8 hours.", ctx(5, null, null, null));
		assertTrue(has(warnings, SafetyWarning.TYPE_OVERDOSE, "ibuprofen"));
	}

	@Test
	public void doseUnderMaxIsNotFlagged() {
		// 200 mg x3/day = 600 mg/day, under the 1200 max -> no overdose, nothing else in context.
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen 200 mg three times a day is appropriate.",
				ctx(5, null, null, null));
		assertFalse(has(warnings, SafetyWarning.TYPE_OVERDOSE, "ibuprofen"));
	}

	@Test
	public void interactionIsFlaggedAgainstActiveOrder() {
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen could help with the pain.",
				ctx(40, set("warfarin"), null, null));
		assertTrue(has(warnings, SafetyWarning.TYPE_INTERACTION, "ibuprofen"),
				"ibuprofen + active warfarin order should flag an interaction");
	}

	@Test
	public void contraindicationIsFlaggedAgainstAllergy() {
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen 200 mg as needed.",
				ctx(40, null, set("nsaid"), null));
		assertTrue(has(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "ibuprofen"),
				"ibuprofen with an NSAID allergy should flag a contraindication");
	}

	@Test
	public void noFalsePositiveWhenAnswerNeedsNoReference() {
		// Chart-sufficient answer naming no reference drug -> no warnings.
		List<SafetyWarning> warnings = validator().validate(
				"The patient's most recent blood pressure is 120/80 mmHg [1].",
				ctx(40, set("warfarin"), set("nsaid"), null));
		assertTrue(warnings.isEmpty(), "an answer naming no reference drug must produce no warnings");
	}

	@Test
	public void frequencyParsingMapsEveryNHoursToDosesPerDay() {
		assertEquals(4, DrugSafetyValidator.frequencyPerDay("one tablet every 6 hours"));
		assertEquals(3, DrugSafetyValidator.frequencyPerDay("every 8 hours"));
		assertEquals(2, DrugSafetyValidator.frequencyPerDay("twice daily"));
		assertEquals(3, DrugSafetyValidator.frequencyPerDay("three times a day"));
		assertEquals(0, DrugSafetyValidator.frequencyPerDay("as needed for pain"));
	}
}
