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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Realizes the draft PR's drug-safety eval cases as real-pipeline tests: each runs the production
 * {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)} (or the answer-only
 * {@link DrugSafetyValidator#validate(String, PatientClinicalContext)} overload, which delegates with
 * a null question) over the real bundled dataset with a hand-built clinical context (the value-object
 * input shape the production builder produces). Per-check toggles fall back to their {@code true}
 * defaults with no OpenMRS context, matching production defaults.
 */
public class DrugSafetyValidatorTest {

	private DrugSafetyValidator validator() {
		DrugSafetyValidator validator = new DrugSafetyValidator();
		validator.setDrugReferenceService(new DrugReferenceService());
		return validator;
	}

	/**
	 * A validator backed by the real WHO ATC sample (parsed by the real
	 * {@link AtcDrugReferenceSource#parse}), so the class-based reasoning is exercised over
	 * authoritative classification entries that carry NO hand-authored rules — the case the
	 * class layer exists for.
	 */
	private DrugSafetyValidator atcValidator() throws IOException {
		DrugReferenceService svc = new DrugReferenceService();
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("atc/atc-sample.tsv")) {
			assertNotNull(in, "ATC sample resource should be on the test classpath");
			svc.setEntries(AtcDrugReferenceSource.parse(in));
		}
		DrugSafetyValidator validator = new DrugSafetyValidator();
		validator.setDrugReferenceService(svc);
		return validator;
	}

	private PatientClinicalContext withActiveAtc(Integer age, Set<String> activeAtcCodes) {
		return new PatientClinicalContext(age, Collections.<String> emptySet(), activeAtcCodes,
				Collections.<String> emptySet(), Collections.<String> emptySet());
	}

	private boolean detailContains(List<SafetyWarning> warnings, String type, String drug, String... needles) {
		for (SafetyWarning w : warnings) {
			if (!w.getType().equals(type) || !w.getDrug().equalsIgnoreCase(drug)) {
				continue;
			}
			boolean all = true;
			for (String needle : needles) {
				if (!w.getDetail().toLowerCase().contains(needle.toLowerCase())) {
					all = false;
					break;
				}
			}
			if (all) {
				return true;
			}
		}
		return false;
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

	// --- Class-based (ATC) safety reasoning: turns authoritative classification into warnings ---

	@Test
	public void classContraindicationAcrossSameAtcSubgroup() throws IOException {
		// Ibuprofen allergy + a naproxen recommendation: both are ATC M01AE (propionic-acid NSAIDs),
		// so class reasoning raises a cross-reactivity contraindication even though the ATC source
		// carries no hand-authored contraindication rules.
		List<SafetyWarning> warnings = atcValidator().validate(
				"Naproxen could be considered for this patient.",
				ctx(40, null, set("ibuprofen"), null));
		assertTrue(has(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "naproxen"),
				"naproxen (M01AE02) shares ATC subgroup M01AE with an ibuprofen (M01AE01) allergy");
		assertTrue(detailContains(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "Naproxen", "ibuprofen", "M01AE"),
				"the contraindication should name the cross-reacting allergen and the shared ATC subgroup");
	}

	@Test
	public void classContraindicationForRecordedAllergyToTheNamedDrug() throws IOException {
		// The single most important case: the answer names a drug the patient is allergic to. ATC
		// entries carry no rules, so only the class layer catches it (the allergy resolves to the
		// same reference drug the answer recommends).
		List<SafetyWarning> warnings = atcValidator().validate(
				"Ibuprofen 200 mg as needed.",
				ctx(40, null, set("ibuprofen"), null));
		assertTrue(has(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "ibuprofen"),
				"an ibuprofen allergy must flag an ibuprofen recommendation");
	}

	@Test
	public void contraindicationFiresWhenQuestionNamesDrugButAnswerDoesNot() throws IOException {
		// Reliability fix: the clinician asks about ibuprofen and the patient has a recorded ibuprofen
		// allergy, but the LLM's answer phrases it by class ("an NSAID allergy") and NEVER writes
		// "ibuprofen". The safety net must still fire — it keys off the QUESTION (findByQuery), not only
		// the answer's word choice. Pre-fix, the answer named no drug, so nothing was checked.
		List<SafetyWarning> warnings = atcValidator().validate(
				"The patient has an allergy to NSAID (drug allergen).",
				"Is ibuprofen contraindicated for her?",
				ctx(40, null, set("ibuprofen"), null));
		assertTrue(has(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "ibuprofen"),
				"a contraindication for the asked-about drug must fire even when the answer never names it");
	}

	@Test
	public void contraindicationFiresFromQuestionEvenWhenAnswerIsEmpty() throws IOException {
		// Extreme of the decoupling: the LLM produced no usable answer text, but the clinician asked
		// about a drug the patient is allergic to. The question-driven check must still fire (the old
		// answer-only guard that returned early on an empty answer is deliberately gone).
		List<SafetyWarning> warnings = atcValidator().validate(
				"",
				"Is ibuprofen safe for her?",
				ctx(40, null, set("ibuprofen"), null));
		assertTrue(has(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "ibuprofen"),
				"a contraindication for the asked-about drug must fire even when the answer is empty");
	}

	@Test
	public void questionDrivenCheckRespectsAtcBranchBoundaryNoFalsePositive() throws IOException {
		// The question-driven path must be as class-correct as the answer-driven one — it must not
		// warn for ANY drug merely named in the question. Aspirin (N02BA01, salicylates) is a DIFFERENT
		// ATC branch from ibuprofen (M01AE01), so asking about it with an ibuprofen allergy must NOT warn.
		// Aspirin enters only via the question here (the answer names no resolvable drug).
		List<SafetyWarning> warnings = atcValidator().validate(
				"The patient has an allergy to NSAID (drug allergen).",
				"Is acetylsalicylic acid a good option for her?",
				ctx(40, null, set("ibuprofen"), null));
		assertFalse(has(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "acetylsalicylic"),
				"the question-driven path must not link aspirin to an ibuprofen allergy across ATC branches");
	}

	@Test
	public void drugInBothQuestionAndAnswerWarnsOnlyOnce() throws IOException {
		// A drug named in BOTH the question and the answer must be checked once, not twice. The
		// question∪answer union dedups by identity, which holds only because findByQuery resolves
		// against the shared getAll() cache; this pins that contract so a future findByQuery that
		// returned copies (breaking dedup) would fail here rather than silently double-warn.
		List<SafetyWarning> warnings = atcValidator().validate(
				"Ibuprofen 200 mg as needed.",
				"Is ibuprofen safe for her?",
				ctx(40, null, set("ibuprofen"), null));
		long contra = warnings.stream()
				.filter(w -> w.getType().equals(SafetyWarning.TYPE_CONTRAINDICATION)
						&& w.getDrug().equalsIgnoreCase("ibuprofen"))
				.count();
		assertEquals(1, contra,
				"a drug named in both question and answer must produce one contraindication, not two");
	}

	@Test
	public void classContraindicationNotRaisedAcrossDifferentAtcBranch() throws IOException {
		// Honest boundary (ADR Decision 24): aspirin (N02BA01, salicylates) is a DIFFERENT ATC
		// branch from ibuprofen (M01AE01, propionic NSAIDs), so class membership alone does NOT
		// link them — NSAID cross-reactivity spans branches and needs curated data, not ATC.
		List<SafetyWarning> warnings = atcValidator().validate(
				"Acetylsalicylic acid is a reasonable option here.",
				ctx(40, null, set("ibuprofen"), null));
		assertFalse(has(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "acetylsalicylic"),
				"ATC class matching must not link aspirin to an ibuprofen allergy across ATC branches");
	}

	@Test
	public void classInteractionFlaggedForSameClassActiveOrder() throws IOException {
		// Answer recommends ibuprofen while the patient already has an active naproxen order
		// (M01AE02). Same ATC subgroup M01AE -> a duplicate-therapy interaction, no rule needed.
		List<SafetyWarning> warnings = atcValidator().validate(
				"Ibuprofen could help with the pain.", withActiveAtc(40, set("M01AE02")));
		assertTrue(has(warnings, SafetyWarning.TYPE_INTERACTION, "ibuprofen"),
				"ibuprofen + an active same-class (M01AE) naproxen order should flag duplicate therapy");
		assertTrue(detailContains(warnings, SafetyWarning.TYPE_INTERACTION, "Ibuprofen", "naproxen", "M01AE"),
				"the interaction should name the active order it duplicates and the shared ATC subgroup");
	}

	@Test
	public void classInteractionNotRaisedWhenTheActiveOrderIsTheSameDrug() throws IOException {
		// The answer describes ibuprofen and ibuprofen (M01AE01) is itself the active order: this is
		// restating existing therapy, not a duplicate -> no interaction (the false positive we avoid).
		List<SafetyWarning> warnings = atcValidator().validate(
				"Ibuprofen 200 mg is already charted.", withActiveAtc(40, set("M01AE01")));
		assertFalse(has(warnings, SafetyWarning.TYPE_INTERACTION, "ibuprofen"),
				"the same drug already on order must not be flagged as a duplicate-therapy interaction");
	}

	@Test
	public void classInteractionForSameClassOrderNotInTheDatasetNamesTheBareCode() throws IOException {
		// An active order whose ATC code shares ibuprofen's M01AE subgroup but is NOT one of the
		// dataset's substances (e.g. a partial dataset, or an order mapped to a code the dataset
		// omits): the duplicate-therapy interaction still fires, naming the bare ATC code rather
		// than silently dropping the warning.
		List<SafetyWarning> warnings = atcValidator().validate(
				"Ibuprofen could help with the pain.", withActiveAtc(40, set("M01AE99")));
		assertTrue(detailContains(warnings, SafetyWarning.TYPE_INTERACTION, "Ibuprofen", "M01AE99"),
				"an in-class order absent from the dataset should still warn, named by its ATC code");
	}

	@Test
	public void classInteractionNotRaisedForDifferentClassActiveOrder() throws IOException {
		// Amoxicillin (J01CA04) is a different ATC class than ibuprofen (M01AE01) -> no duplicate therapy.
		List<SafetyWarning> warnings = atcValidator().validate(
				"Ibuprofen could help with the pain.", withActiveAtc(40, set("J01CA04")));
		assertFalse(has(warnings, SafetyWarning.TYPE_INTERACTION, "ibuprofen"),
				"a different-class active order must not flag a duplicate-therapy interaction");
	}

	@Test
	public void duplicateAllergyAliasesProduceASingleContraindication() {
		// advil and brufen are both ibuprofen aliases; two allergy records that resolve to the same
		// reference drug must produce ONE class contraindication, not one per alias. (Real bundled
		// JSON dataset, whose curated rules do NOT key on these brand aliases — so the class layer is
		// the only thing that fires, and it must dedupe by resolved allergen.)
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen 200 mg as needed.",
				ctx(40, null, set("advil", "brufen"), null));
		long ibuprofenContraindications = warnings.stream()
				.filter(w -> w.getType().equals(SafetyWarning.TYPE_CONTRAINDICATION)
						&& w.getDrug().equalsIgnoreCase("Ibuprofen"))
				.count();
		assertEquals(1, ibuprofenContraindications,
				"two aliases of the same allergen must not double-warn");
	}
}
