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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Weight-aware per-dose overdose validation: when the patient's weight is known, a
 * per-administration dose stated in the answer that exceeds the reference
 * {@code mgPerKgMax} × weight for the patient's age band is flagged — the check the
 * absolute {@code maxDailyDoseMg} ceiling cannot make for small patients, and the
 * only check available for bands that publish mg/kg dosing with no daily maximum.
 *
 * <p>All tests run the production {@link DrugSafetyValidator} over the real bundled
 * dataset (ibuprofen 2-11y: 5-10 mg/kg per dose, max 1200 mg/day; 0-1y: 5-10 mg/kg,
 * NO published daily maximum; paracetamol 0-11y: 10-15 mg/kg). The weight enters
 * through {@link PatientClinicalContext} exactly as the production builder supplies it.
 */
public class WeightAwareOverdoseTest {

	private DrugSafetyValidator validator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());
	}

	private PatientClinicalContext ctx(Integer age, Double weightKg) {
		return DrugReferenceTestSupport.ctx(age, weightKg, null, null, null, null);
	}

	private long overdoseCount(List<SafetyWarning> warnings, String drug) {
		return DrugReferenceTestSupport.overdoseCount(warnings, drug);
	}

	private String overdoseDetail(List<SafetyWarning> warnings, String drug) {
		return DrugReferenceTestSupport.overdoseDetail(warnings, drug);
	}

	@Test
	public void perDoseAboveWeightBasedMaxIsFlagged() {
		// 20 kg, ages 2-11: per-dose ceiling = 10 mg/kg x 20 kg = 200 mg. A 400 mg dose is double
		// that, yet the daily total (400 x3 = 1200) does NOT exceed the 1200 mg/day maximum — so
		// only the weight-aware check can catch it.
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen 400 mg every 8 hours can be given.", ctx(5, 20.0));
		assertEquals(1, overdoseCount(warnings, "ibuprofen"),
				"a per-dose excess for the patient's weight must be flagged exactly once");
		String detail = overdoseDetail(warnings, "ibuprofen");
		assertTrue(detail.contains("10 mg/kg"), "the warning should state the mg/kg per-dose maximum: " + detail);
		assertTrue(detail.contains("20 kg"), "the warning should state the patient's weight: " + detail);
		assertTrue(detail.contains("200 mg"), "the warning should state the computed ceiling: " + detail);
	}

	@Test
	public void perDoseWithinWeightBasedMaxIsNotFlagged() {
		// 150 mg at 20 kg is 7.5 mg/kg — inside the 5-10 mg/kg band, and 450 mg/day is under 1200.
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen 150 mg every 8 hours is appropriate.", ctx(5, 20.0));
		assertEquals(0, overdoseCount(warnings, "ibuprofen"),
				"a weight-appropriate dose must not be flagged");
	}

	@Test
	public void noWeightMeansNoPerKgCheck() {
		// Same answer as the flagged case but with no weight on record: the per-kg arm must stay
		// silent (conservative — never guess a weight), and the daily arm has nothing to flag
		// (1200 mg/day does not EXCEED the 1200 maximum). Pins pre-existing behavior exactly.
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen 400 mg every 8 hours can be given.", ctx(5, null));
		assertEquals(0, overdoseCount(warnings, "ibuprofen"),
				"without a known weight the per-kg check must not fire");
	}

	@Test
	public void weightCheckFiresEvenWhenNoDailyMaximumIsPublished() {
		// The 0-1y ibuprofen band publishes mg/kg dosing but NO daily maximum (maxDailyDoseMg 0).
		// Before the weight-aware check this band could flag nothing at all; now an 8 kg infant
		// stated to get 200 mg (ceiling 10 x 8 = 80 mg) is flagged.
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen 200 mg was suggested.", ctx(1, 8.0));
		assertEquals(1, overdoseCount(warnings, "ibuprofen"),
				"a band with mg/kg dosing but no daily maximum must still support the weight check");
		assertTrue(overdoseDetail(warnings, "ibuprofen").contains("mg/kg"));
	}

	@Test
	public void dailyExcessWinsOverPerDoseExcessWithOneWarning() {
		// 600 mg every 6 hours at 20 kg trips BOTH arms (2400 > 1200 mg/day; 600 > 200 mg per dose).
		// One clinical fact per drug: the published daily ceiling is the stronger statement and must
		// be the single warning emitted.
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen 600 mg every 6 hours.", ctx(5, 20.0));
		assertEquals(1, overdoseCount(warnings, "ibuprofen"),
				"daily + per-dose excess must produce one warning, not two");
		assertTrue(overdoseDetail(warnings, "ibuprofen").contains("mg/day"),
				"the daily-ceiling message should win when both arms trip");
	}

	@Test
	public void limitCueAppliesToThePerDoseCheck() {
		// A ceiling the answer merely recites ("maximum ... per dose") is not a prescribed dose —
		// the same limit-cue guard the daily arm uses must protect the per-dose arm.
		List<SafetyWarning> warnings = validator().validate(
				"For ibuprofen, the maximum 400 mg per dose should not be exceeded.", ctx(5, 20.0));
		assertEquals(0, overdoseCount(warnings, "ibuprofen"),
				"a recited ceiling must not be read as a prescribed per-dose amount");
	}

	@Test
	public void perDoseAttributionStaysWithTheNearestDrug() {
		// The 400 mg belongs to paracetamol (its per-dose ceiling at 20 kg is 15 x 20 = 300 mg ->
		// flagged); ibuprofen in the neighbouring clause has no dose of its own and must stay clean.
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen may help with pain; paracetamol 400 mg is an alternative.", ctx(5, 20.0));
		assertEquals(0, overdoseCount(warnings, "ibuprofen"),
				"a neighbouring drug's dose must not be charged to ibuprofen");
		assertEquals(1, overdoseCount(warnings, "paracetamol"),
				"the per-dose excess must attribute to the drug that owns the dose");
	}

	@Test
	public void fiveArgContextConstructorStillBuildsWithNoWeight() {
		// Backward compatibility: the pre-weight constructor must keep compiling and behave as
		// weight-unknown (the production builder now uses the 6-arg form).
		PatientClinicalContext ctx = new PatientClinicalContext(5, Collections.<String> emptySet(),
				Collections.<String> emptySet(), Collections.<String> emptySet(),
				Collections.<String> emptySet());
		assertFalse(ctx.getWeightKg() != null, "the 5-arg constructor must mean weight-unknown");
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen 400 mg every 8 hours can be given.", ctx);
		assertEquals(0, overdoseCount(warnings, "ibuprofen"));
	}
}
