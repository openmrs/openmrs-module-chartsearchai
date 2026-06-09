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

	@Test
	public void overdoseNotAttributedToADrugNamedInANeighbouringClause() {
		// A paracetamol dose in the next clause must not be charged to ibuprofen, which
		// has no dose of its own here. The dose must attribute to the nearest drug.
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen may help with pain; paracetamol 1000 mg every 6 hours is an alternative.",
				ctx(5, null, null, null));
		assertFalse(has(warnings, SafetyWarning.TYPE_OVERDOSE, "ibuprofen"),
				"a paracetamol dose in a neighbouring clause must not flag ibuprofen");
	}

	@Test
	public void overdoseFrequencyDoesNotBleedAcrossSentences() {
		// Ibuprofen 600 mg with no frequency in its own sentence = 600 mg/day, under the
		// 1200 max. The "every 6 hours" belongs to the next sentence and must not apply.
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen 600 mg was administered. Paracetamol every 6 hours was also charted.",
				ctx(5, null, null, null));
		assertFalse(has(warnings, SafetyWarning.TYPE_OVERDOSE, "ibuprofen"),
				"a frequency in a different sentence must not inflate the ibuprofen daily total");
	}

	@Test
	public void statedReferenceCeilingIsNotReadAsAPrescribedDose() {
		// Reciting the reference maximum (which the injector feeds the LLM) must not itself
		// trip an overdose: a number introduced by a limit cue is a ceiling, not a dose.
		List<SafetyWarning> warnings = validator().validate(
				"For ibuprofen, the maximum 2400 mg per day should not be exceeded.",
				ctx(5, null, null, null));
		assertFalse(has(warnings, SafetyWarning.TYPE_OVERDOSE, "ibuprofen"),
				"a dose introduced by 'maximum' is a ceiling, not a prescribed dose");
	}

	@Test
	public void frequencyWordFormsRequireWordBoundaries() {
		// "bd" inside "abdominal" must not be read as twice-daily; a real "bd" still parses.
		assertEquals(0, DrugSafetyValidator.frequencyPerDay("for abdominal discomfort"));
		assertEquals(2, DrugSafetyValidator.frequencyPerDay("ibuprofen 200 mg bd"));
	}

	@Test
	public void decimalDoseIsNotSplitByTheClauseDelimiter() {
		// The clause splitter must not break "333.5" on its decimal point: 333.5 mg x4 = 1334 mg/day.
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen 333.5 mg every 6 hours.", ctx(5, null, null, null));
		assertTrue(has(warnings, SafetyWarning.TYPE_OVERDOSE, "ibuprofen"),
				"a decimal mg dose must be parsed, not split on its decimal point");
	}

	@Test
	public void realSingleDrugOverdoseStillFlaggedAfterAnchoring() {
		// Guard against over-correction: a genuine single-drug overdose must still fire.
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen 800 mg every 6 hours.", ctx(5, null, null, null));
		assertTrue(has(warnings, SafetyWarning.TYPE_OVERDOSE, "ibuprofen"),
				"800 mg x4 = 3200 mg/day must still exceed the 1200 mg/day maximum");
	}
}
