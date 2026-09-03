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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Concept;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * End-to-end, context-sensitive coverage for the weight-aware overdose arm: runs the
 * PUBLIC production entry {@link DrugSafetyValidator#validate(String, String, Patient)}
 * against a real OpenMRS context — real global properties, a standard-test-dataset
 * patient, and real weight observations — so the whole chain executes: the feature
 * gate, {@code PatientClinicalContextBuilder}'s weight read (concept resolution from
 * the GP, freshness window, newest-wins selection), and the validator's per-dose check
 * over the real bundled dataset.
 *
 * <p>Patient 7 (standard test dataset, born 1976-08-25) is an adult, so the ibuprofen
 * 12-120 band applies: 5-10 mg/kg per dose, 2400 mg/day maximum. Every case states
 * "600 mg every 8 hours" = 1800 mg/day — under the daily ceiling — so any overdose
 * warning can only come from the weight-aware per-dose arm (600 mg &gt; 10 mg/kg × 50 kg).
 */
public class DrugSafetyWeightContextTest extends BaseModuleContextSensitiveTest {

	/** WEIGHT (KG) in the standard test dataset (concept 5089). */
	private static final String WEIGHT_CONCEPT_UUID = "c607c80f-1ea9-4da3-bb88-6276ce8868dd";

	private static final String ANSWER = "Ibuprofen 600 mg every 8 hours can be given.";

	private static final String QUESTION = "What ibuprofen dose can she get?";

	private DrugSafetyValidator validator;

	private Patient patient;

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_WEIGHT_CONCEPT_UUID, WEIGHT_CONCEPT_UUID);
		validator = DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());
		patient = Context.getPatientService().getPatient(7);
	}

	private void saveWeightObs(double kg, Date when) {
		Concept weight = Context.getConceptService().getConceptByUuid(WEIGHT_CONCEPT_UUID);
		Obs obs = new Obs(patient, weight, when, null);
		obs.setValueNumeric(kg);
		Context.getObsService().saveObs(obs, null);
	}

	private Date daysAgo(int days) {
		return new Date(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000);
	}

	private long overdoseCount(List<SafetyWarning> warnings) {
		return DrugReferenceTestSupport.overdoseCount(warnings, "ibuprofen");
	}

	private String overdoseDetail(List<SafetyWarning> warnings) {
		return DrugReferenceTestSupport.overdoseDetail(warnings, "ibuprofen");
	}

	@Test
	public void weightAwarePerDoseOverdoseFiresEndToEnd() {
		saveWeightObs(50.0, new Date());
		List<SafetyWarning> warnings = validator.validate(ANSWER, QUESTION, patient);
		assertEquals(1, overdoseCount(warnings),
				"a fresh 50 kg weight must drive the per-dose check through the full production path");
		String detail = overdoseDetail(warnings);
		assertTrue(detail.contains("10 mg/kg"), "detail should state the per-dose maximum: " + detail);
		assertTrue(detail.contains("50 kg"), "detail should state the patient's weight: " + detail);
	}

	@Test
	public void staleWeightDoesNotDriveThePerDoseCheck() {
		// The only weight on record is older than the freshness window (default 90 days): the
		// weight-aware arm must stay silent rather than compute mg/kg from an outdated weight.
		saveWeightObs(50.0, daysAgo(200));
		assertEquals(0, overdoseCount(validator.validate(ANSWER, QUESTION, patient)),
				"a weight older than weightMaxAgeDays must not drive the per-dose check");
	}

	@Test
	public void newestFreshWeightWins() {
		// Two fresh weights: 100 kg (10 days ago) then 50 kg (now). With 50 kg the 600 mg dose
		// exceeds the 500 mg ceiling and must warn; had the older 100 kg been used the ceiling
		// would be 1000 mg and nothing would fire — so the warning proves newest-wins selection.
		saveWeightObs(100.0, daysAgo(10));
		saveWeightObs(50.0, new Date());
		assertEquals(1, overdoseCount(validator.validate(ANSWER, QUESTION, patient)),
				"the most recent weight observation must drive the per-dose ceiling");
	}

	/**
	 * Creates a numeric concept carrying the DEFAULT (CIEL 5089) weight UUID and puts a fresh
	 * 50 kg obs on it — so the default-UUID lookup actually resolves in the test dictionary.
	 * Without this, "blank GP" and "default UUID not in this dictionary" are indistinguishable
	 * (both yield no weight) and a blank-disables test would pass for the wrong reason.
	 */
	private void saveWeightObsOnDefaultCielConcept() {
		Concept std = Context.getConceptService().getConcept(5089);
		org.openmrs.ConceptNumeric ciel = new org.openmrs.ConceptNumeric();
		ciel.setUuid(ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WEIGHT_CONCEPT_UUID);
		ciel.addName(new org.openmrs.ConceptName("WEIGHT CIEL TEST", java.util.Locale.ENGLISH));
		ciel.setDatatype(std.getDatatype());
		ciel.setConceptClass(std.getConceptClass());
		Context.getConceptService().saveConcept(ciel);
		Obs obs = new Obs(patient, ciel, new Date(), null);
		obs.setValueNumeric(50.0);
		Context.getObsService().saveObs(obs, null);
		// The concept lookup below runs by-uuid SQL; flush so the just-created concept is visible.
		Context.flushSession();
	}

	@Test
	public void absentWeightConceptGpMeansTheBundledDefaultIsActive() {
		// The arm must run against the code-side default (the CIEL 5089 UUID) when no GP row
		// exists — absence means "default", not "disabled". setUp seeds the GP for the other
		// tests, so make it genuinely absent by purging the row.
		org.openmrs.GlobalProperty seeded = Context.getAdministrationService()
				.getGlobalPropertyObject(ChartSearchAiConstants.GP_DRUG_SAFETY_WEIGHT_CONCEPT_UUID);
		if (seeded != null) {
			Context.getAdministrationService().purgeGlobalProperty(seeded);
		}
		saveWeightObsOnDefaultCielConcept();
		List<SafetyWarning> warnings = validator.validate(ANSWER, QUESTION, patient);
		assertEquals(1, overdoseCount(warnings),
				"with no GP row the weight arm must use the bundled default concept UUID; got: " + warnings);
	}

	@Test
	public void noneSentinelDisablesTheWeightArm() {
		// The documented operator opt-out: setting the GP to "none" must disable the arm — even
		// though the DEFAULT UUID would resolve in this dictionary (proven by the absent-GP test
		// above, which shares the same arrangement and fires). An explicit sentinel, not blank:
		// OpenMRS normalizes a blanked GP value to null, which reads back like an absent GP and
		// therefore falls back to the default — pinned by the mustFallBackToTheDefault test below.
		saveWeightObsOnDefaultCielConcept();
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_WEIGHT_CONCEPT_UUID,
				ChartSearchAiConstants.DRUG_SAFETY_WEIGHT_CONCEPT_DISABLED);
		List<SafetyWarning> warnings = validator.validate(ANSWER, QUESTION, patient);
		assertEquals(0, overdoseCount(warnings),
				"the none sentinel is the operator opt-out for the weight-aware arm; got: " + warnings);
	}

	@Test
	public void noneSentinelIsCaseInsensitiveAndPaddingTolerant() {
		// The docs promise the sentinel is case-insensitive; an admin typing " NoNe " must still
		// disable the arm (pins equalsIgnoreCase + trim against a future equals() regression).
		saveWeightObsOnDefaultCielConcept();
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_WEIGHT_CONCEPT_UUID, " NoNe ");
		List<SafetyWarning> warnings = validator.validate(ANSWER, QUESTION, patient);
		assertEquals(0, overdoseCount(warnings),
				"the none sentinel must disable the arm regardless of case/padding; got: " + warnings);
	}

	@Test
	public void weightArmHonorsTheDoseExcessToggle() {
		// config.xml/README state the weight-aware arm requires warnOnDoseExcess — with the toggle
		// off, a per-dose excess (600 mg > 10 mg/kg x 50 kg) must raise nothing.
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_DOSE_EXCESS, "false");
		saveWeightObs(50.0, new Date());
		List<SafetyWarning> warnings = validator.validate(ANSWER, QUESTION, patient);
		assertEquals(0, overdoseCount(warnings),
				"warnOnDoseExcess=false must silence the weight-aware arm; got: " + warnings);
	}

	@Test
	public void blankedGpMustFallBackToTheDefaultNotDisable() {
		// OpenMRS stores a blanked GP value as null, so blank is INDISTINGUISHABLE from absent via
		// the privilege-free reader — it must therefore mean "default", exactly like every other
		// chartsearchai GP, and must NOT silently disable a safety arm.
		saveWeightObsOnDefaultCielConcept();
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_WEIGHT_CONCEPT_UUID, "");
		List<SafetyWarning> warnings = validator.validate(ANSWER, QUESTION, patient);
		assertEquals(1, overdoseCount(warnings),
				"a blanked GP reads back as null and must fall back to the default; got: " + warnings);
	}

	@Test
	public void validatorFailureNeverBreaksTheAnswerPath() {
		// The drug-reference feature is an additive safety net: even a broken dataset source
		// (here: one that throws) must degrade to "no warnings", never to an exception that
		// would fail a query whose answer is already produced.
		DrugReferenceService broken = new DrugReferenceService();
		broken.setSource(() -> {
			throw new RuntimeException("boom");
		});
		DrugSafetyValidator brokenValidator = new DrugSafetyValidator();
		brokenValidator.setDrugReferenceService(broken);
		saveWeightObs(50.0, new Date());
		assertTrue(brokenValidator.validate(ANSWER, QUESTION, patient).isEmpty(),
				"a throwing dataset source must degrade to no warnings, not break the query");
	}

	@Test
	public void injectorFailureNeverBreaksTheAnswerPath() {
		DrugReferenceService broken = new DrugReferenceService();
		broken.setSource(() -> {
			throw new RuntimeException("boom");
		});
		DrugReferenceInjector brokenInjector = DrugReferenceTestSupport.injector(broken);
		org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart chart =
				DrugReferenceTestSupport.oneRecordChart();
		assertTrue(brokenInjector.inject(chart, patient, "is ibuprofen safe?") == chart,
				"a throwing dataset source must return the chart unchanged, not break the query");
	}

	@Test
	public void unparseableFreshnessGpFallsBackToTheDefaultWindow() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_WEIGHT_MAX_AGE_DAYS, "not-a-number");
		saveWeightObs(50.0, daysAgo(10));
		assertEquals(1, overdoseCount(validator.validate(ANSWER, QUESTION, patient)),
				"an unparseable freshness GP must fall back to the 90-day default, not disable the arm");
	}

	@Test
	public void featureOffMasterGateProducesNoWarnings() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "false");
		saveWeightObs(50.0, new Date());
		assertTrue(validator.validate(ANSWER, QUESTION, patient).isEmpty(),
				"with the master switch off the production entry must return no warnings at all");
	}
}
