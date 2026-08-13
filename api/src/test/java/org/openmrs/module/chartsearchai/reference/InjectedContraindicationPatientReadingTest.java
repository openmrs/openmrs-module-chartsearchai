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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Issue #208 item 2 — {@code DrugReferenceInjector.render} lists EVERY contraindication rule an entry
 * carries while the chips list only the subset the patient's own chart matches, and the record said
 * nothing about which was which. Measured live on the 3.7.1 standalone: a patient with no recorded
 * gastrointestinal bleeding got an injected record reading
 * {@code Contraindicated with: … active gastrointestinal bleeding …} with no chip beside it, and the
 * record is injected as CITABLE evidence, so a model reporting what it can see can report a
 * contraindication that does not apply to this patient.
 *
 * <p><b>Marked, not filtered.</b> A drug's contraindications are properties of the drug, and this record
 * is the only reference material the prompt carries about it — filtering to the matched subset would make
 * the record disagree with itself between the two {@code validate} passes of one request (the pre-answer
 * pass and the chips pass see different rows in play; see {@code DrugSafetyValidator.SubstanceSubjects}),
 * and would delete the operator-authored clinical prose that {@code InjectedContraindicationClauseTest}
 * exists to preserve. So the list is unchanged and the record states the patient-specific reading of it.
 *
 * <p><b>One predicate, two surfaces.</b> The reading is
 * {@link DrugSafetyValidator#recordedContraindicationKind}, the very method the chip arm decides a rule
 * has matched by — for the same reason {@code contraindicationFinding} is shared (issue #190 item 1): a
 * second copy is how a record and the chip beside it come to disagree, silently and in the direction of
 * asserting more than the chart supports.
 *
 * <p><b>Placed before the list it qualifies</b>, so a model reading forward has the qualifier before the
 * content — the same reason {@code orderedInteractionNotes} promotes the patient's own partners to the
 * front of the interactions section rather than appending them.
 *
 * <p>Every case runs the REAL production path: the SHIPPED curated seed and the real
 * {@link DdiDrugReferenceSource} sample through the real {@code injectRecords} and the real
 * {@code validate}, GP reads on their no-context defaults.
 */
public class InjectedContraindicationPatientReadingTest {

	private static final String QUESTION = "Is ibuprofen safe for her?";

	/** The four rules the shipped seed files on ibuprofen, in dataset order — two allergy, two
	 *  condition. Read as a list rather than one string so a case can say which it expects. */
	private static final List<String> SHIPPED_IBUPROFEN_RULES = Arrays.asList(
			"NSAID hypersensitivity", "documented ibuprofen allergy", "active gastrointestinal bleeding",
			"active peptic ulcer disease");

	private static String ibuprofenRecord(PatientClinicalContext context) {
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(
				DrugReferenceTestSupport.bundledService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, QUESTION);
		for (String text : DrugReferenceTestSupport.referenceTexts(chart)) {
			if (text.startsWith("Drug reference — Ibuprofen")) {
				return text;
			}
		}
		throw new IllegalStateException("no ibuprofen reference record was injected: "
				+ DrugReferenceTestSupport.referenceTexts(chart));
	}

	/** The clause the record states the patient's chart records, read off the rendered text rather than
	 *  recomputed — what a model reads is what the string says. */
	private static String recordedReading(String record) {
		String marker = " Of the contraindications below, this patient's chart records: ";
		int start = record.indexOf(marker);
		assertTrue(start >= 0, "the record must state the patient-specific reading, was: " + record);
		int end = record.indexOf(". Contraindicated with: ", start);
		assertTrue(end > start, "and state it immediately before the list it qualifies, was: " + record);
		return record.substring(start + marker.length(), end);
	}

	private static List<String> contraindicationChips(List<SafetyWarning> warnings) {
		List<String> out = new ArrayList<String>();
		for (SafetyWarning warning : warnings) {
			if (SafetyWarning.TYPE_CONTRAINDICATION.equals(warning.getType())) {
				out.add(warning.getDetail());
			}
		}
		return out;
	}

	@Test
	public void aChartRecordingNoneOfThemSaysNoneOfThem() throws Exception {
		// Issue #208's live case, re-derived here over the shipped seed: no allergy and no condition on
		// record, so none of ibuprofen's four rules can match, and before this fix the record listed all
		// four with nothing to distinguish them from the patient's own findings.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null, null, null, null);
		assertTrue(contraindicationChips(DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.bundledService()).validate("", QUESTION, context))
						.isEmpty(),
				"precondition: no chip may be raised, or the record is not the only place this is stated");

		String record = ibuprofenRecord(context);

		assertEquals("none", recordedReading(record),
				"the record must say the chart records none of them, was: " + record);
		for (String rule : SHIPPED_IBUPROFEN_RULES) {
			assertTrue(record.contains(rule),
					"and must still list " + rule + " — marked, not filtered, was: " + record);
		}
	}

	@Test
	public void theOneTheChartRecordsIsNamedAndTheRestAreStillListed() throws Exception {
		// The other half of "marked, not filtered": the matched rule is named, the unmatched three stay in
		// the list, and the reading names ONLY the matched one — a fix that marked the section rather than
		// the rules would say all four are recorded.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null, null,
				DrugReferenceTestSupport.set("Ibuprofen"), null);

		String record = ibuprofenRecord(context);

		assertEquals("documented ibuprofen allergy", recordedReading(record),
				"exactly the rule the chart matches, was: " + record);
		for (String rule : SHIPPED_IBUPROFEN_RULES) {
			assertTrue(record.contains(rule), "and all four still listed, was: " + record);
		}
	}

	@Test
	public void aConditionOnRecordReadsFromTheConditionListAndOnlyItsOwnRule() throws Exception {
		// The condition leg, and the exact inverse of the live finding: with a peptic ulcer history on
		// record the record names THAT rule and still does not claim the gastrointestinal bleeding one.
		// A predicate that read the two lists as one, or matched a condition rule against an allergy,
		// would name two here.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null, null, null,
				DrugReferenceTestSupport.set("History of peptic ulcer disease"));

		String record = ibuprofenRecord(context);

		assertEquals("active peptic ulcer disease", recordedReading(record),
				"the condition rule the chart matches, and only it, was: " + record);
	}

	@Test
	public void theReadingNamesExactlyWhatTheChipsBesideItAssert() throws Exception {
		// The drift guard, and the reason the predicate is shared rather than copied: whatever the record
		// says the chart records, a chip must be asserting. Both surfaces are driven here through their own
		// production entry points, so a second implementation of the match on either side reddens this.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null, null,
				DrugReferenceTestSupport.set("Ibuprofen"),
				DrugReferenceTestSupport.set("Active GI bleed"));

		List<String> chips = contraindicationChips(DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.bundledService()).validate("", QUESTION, context));
		String reading = recordedReading(ibuprofenRecord(context));

		assertEquals(2, chips.size(), "precondition: two rules match, so two chips, was: " + chips);
		assertEquals("documented ibuprofen allergy; active gastrointestinal bleeding", reading,
				"both, in dataset order, was: " + reading);
		for (String clause : reading.split("; ")) {
			boolean asserted = false;
			for (String chip : chips) {
				asserted = asserted || chip.endsWith(": " + clause);
			}
			assertTrue(asserted, "the record may name only what a chip asserts — " + clause
					+ " was not among " + chips);
		}
		assertEquals(chips.size(), reading.split("; ").length,
				"and must name every one of them, was: " + reading + " against " + chips);
	}

	@Test
	public void anEntryWithNoContraindicationRulesClaimsNothingEitherWay() throws Exception {
		// The bound on the prompt cost, and on what may be asserted: the ddinter and atc sources publish
		// no contraindications at all, so a record rendered from them must carry neither the list nor a
		// statement about it. An unconditional sentence would spend characters on every reference record
		// the shipped configuration injects — which is every one of them, since ddinter is the shipped
		// source format — to say nothing.
		String record = DrugReferenceTestSupport.injectedDdinterReferenceText("is warfarin safe to add?");

		assertNotNull(record, "precondition: a ddinter record must be injected");
		assertFalse(record.contains("Contraindicated with:"),
				"precondition: the ddinter source publishes no contraindications, was: " + record);
		assertFalse(record.contains("this patient's chart records"),
				"so there is nothing to state a patient-specific reading of, was: " + record);
	}
}
