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
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * pass and the chips pass see different rows IN PLAY — the answer widens that set deliberately, issue
 * #175; see {@code DrugSafetyValidator.resolvedSubstanceRows}, and not
 * {@code DrugSafetyValidator.SubstanceSubjects}, whose own answer stopped being moved by the ANSWER at
 * issue #238),
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
				DrugReferenceTestSupport.curatedService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, QUESTION);
		// Through the shared terminator rule, not a bare startsWith: that also accepts a route-qualified
		// sibling, and every case here asserts the content of ONE named entry's record.
		String record = DrugReferenceTestSupport.referenceTextNaming(chart, "Ibuprofen");
		assertNotNull(record, "no ibuprofen reference record was injected: "
				+ DrugReferenceTestSupport.referenceTexts(chart));
		return record;
	}

	/** The rendering's own section lead for the drug's whole list, so a case asserting the list is
	 *  unchanged reads it where a model reads it. */
	private static final String RULE_LIST_MARKER = " Contraindicated with: ";

	private static final String RECORDED_MARKER = " Recorded for this patient: ";

	private static final String NOT_RECORDED_MARKER = " Not recorded for this patient: ";

	/** The text between {@code marker} and the sentence's own full stop, read off the rendered record
	 *  rather than recomputed — what a model reads is what the string says. */
	private static String sentenceAfter(String record, String marker) {
		// The positive lead is one capital letter away from being a substring of the negative one, so a
		// production reword to "Not Recorded" would silently make every recordedReading below read the
		// NEGATIVE half and still pass. Asserted rather than relied on.
		assertFalse(NOT_RECORDED_MARKER.contains(RECORDED_MARKER),
				"the two leads must stay distinguishable by indexOf");
		int start = record.indexOf(marker);
		if (start < 0) {
			return null;
		}
		int end = record.indexOf(".", start + marker.length());
		assertTrue(end > start, "an unterminated sentence, was: " + record);
		return record.substring(start + marker.length(), end);
	}

	/** The clauses the record states the patient's chart records. */
	private static String recordedReading(String record) {
		String reading = sentenceAfter(record, RECORDED_MARKER);
		assertNotNull(reading, "the record must state the patient-specific reading, was: " + record);
		assertTrue(record.indexOf(RECORDED_MARKER) < record.indexOf(RULE_LIST_MARKER),
				"and state it before the list it qualifies, was: " + record);
		return reading;
	}

	/** The clauses the record states the patient's chart does NOT record. */
	private static String notRecordedReading(String record) {
		String reading = sentenceAfter(record, NOT_RECORDED_MARKER);
		assertNotNull(reading, "the record must name the unrecorded half too, was: " + record);
		assertTrue(record.indexOf(NOT_RECORDED_MARKER) < record.indexOf(RULE_LIST_MARKER),
				"before the list as well, was: " + record);
		return reading;
	}

	@Test
	public void aChartRecordingNoneOfThemNamesEveryOneOfThemAsUnrecorded() throws Exception {
		// Issue #208's live case, re-derived here over the shipped seed: no allergy and no condition on
		// record, so none of ibuprofen's four rules can match, and before this fix the record listed all
		// four with nothing to distinguish them from the patient's own findings.
		//
		// Named one by one rather than summarised, because the summary was measured failing. Live on the
		// 3.7.1 standalone 2026-08-13, with the record saying "…this patient's chart records: none.", a
		// question about amoxicillin for a patient with no penicillin allergy was answered "the patient
		// has a documented amoxicillin allergy" — a clause of the list beside that very sentence.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null, null, null, null);
		assertTrue(DrugReferenceTestSupport.contraindicationDetails(DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.curatedService()).validate("", QUESTION, context))
						.isEmpty(),
				"precondition: no chip may be raised, or the record is not the only place this is stated");

		String record = ibuprofenRecord(context);

		assertEquals(String.join("; ", SHIPPED_IBUPROFEN_RULES), notRecordedReading(record),
				"every clause named on the side it is on, was: " + record);
		assertNull(sentenceAfter(record, RECORDED_MARKER),
				"and no empty positive half, which would state nothing and cost budget: " + record);
		assertTrue(record.contains(RULE_LIST_MARKER + String.join("; ", SHIPPED_IBUPROFEN_RULES) + "."),
				"and the drug's own list is unchanged — marked, not filtered, was: " + record);
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
		assertTrue(record.contains(RULE_LIST_MARKER + String.join("; ", SHIPPED_IBUPROFEN_RULES) + "."),
				"and all four still listed, in dataset order, was: " + record);
		// The negative half, named rather than left to be inferred. Measured live 2026-08-13: with the
		// positive half alone the model answered "the contraindications for this patient include:
		// documented ibuprofen allergy, active gastrointestinal bleeding, active peptic ulcer disease",
		// i.e. it read the list under the sentence's patient framing. The two halves must partition the
		// list exactly, which is what this asserts against SHIPPED_IBUPROFEN_RULES rather than against a
		// literal — a clause in neither half is a contraindication the record says nothing about.
		assertEquals("NSAID hypersensitivity; active gastrointestinal bleeding; active peptic ulcer disease",
				sentenceAfter(record, NOT_RECORDED_MARKER),
				"the other three named as NOT this patient's, was: " + record);
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
		assertEquals("NSAID hypersensitivity; documented ibuprofen allergy; active gastrointestinal bleeding",
				notRecordedReading(record),
				"and the allergy rules stay on the unrecorded side, was: " + record);
	}

	@Test
	public void theReadingNamesExactlyWhatTheChipsBesideItAssert() throws Exception {
		// The drift guard, and the reason the predicate is shared rather than copied: whatever the record
		// says the chart records, a chip must be asserting. Both surfaces are driven here through their own
		// production entry points, so a second implementation of the match on either side reddens this.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null, null,
				DrugReferenceTestSupport.set("Ibuprofen"),
				DrugReferenceTestSupport.set("Active GI bleed"));

		// The RULE chips about this entry, not every contraindication chip of the pass: the class, group
		// and identity arms raise chips with no clause to name (and the issue #143 arm raises them about
		// other drugs), so an equality against all of them would redden on inputs that are not defects —
		// the sibling InjectedContraindicationClauseTest filters the same way and says why.
		List<String> chips = new ArrayList<String>();
		for (String detail : DrugReferenceTestSupport.contraindicationDetails(DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.curatedService()).validate("", QUESTION, context))) {
			if (detail.startsWith("Ibuprofen is contraindicated by an ")) {
				chips.add(detail);
			}
		}
		String reading = recordedReading(ibuprofenRecord(context));

		assertEquals(2, chips.size(), "precondition: two rules match, so two chips, was: " + chips);
		assertEquals("documented ibuprofen allergy; active gastrointestinal bleeding", reading,
				"both, in dataset order, was: " + reading);
		assertEquals("NSAID hypersensitivity; active peptic ulcer disease",
				sentenceAfter(ibuprofenRecord(context), NOT_RECORDED_MARKER),
				"and the two halves partition the list, in clause order");
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

	/** Issue #208 item 2's own fixture for the rule shapes the negative half may not claim — see the
	 *  file's own description. */
	private static final String UNEVALUABLE_FIXTURE =
			"chartsearchai-test/drug-reference-unevaluable-contraindications.json";

	@Test
	public void aRuleTheModuleCannotEvaluateIsListedAndClaimedNeitherWay() throws Exception {
		// The sign-flipped form of this very issue, and the reason the shared predicate's null is not
		// enough on its own: "did not match" and "cannot be asked" are one answer to the chip arm, which
		// stays silent either way, and two answers to a record that prints a NEGATIVE claim. A rule typed
		// `diagnosis` and a rule carrying no token are both unaskable; a record saying this patient does
		// not have them asserts something nobody checked.
		//
		// The same case carries the cross-half duplicate: rules 1 and 2 render ONE note under two keys,
		// and only the allergy leg matches, so a partition by KEY would print "Recorded for this patient:
		// NSAID hypersensitivity. Not recorded for this patient: NSAID hypersensitivity."
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(UNEVALUABLE_FIXTURE));
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null, null,
				DrugReferenceTestSupport.set("NSAIDs"),
				DrugReferenceTestSupport.set("Chronic kidney disease stage 5",
						"Severe hepatic impairment"));

		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, QUESTION);
		String record = DrugReferenceTestSupport.referenceTextNaming(chart, "Ibuprofen");
		assertNotNull(record, "precondition: the ibuprofen record must be injected");

		assertEquals("NSAID hypersensitivity", recordedReading(record),
				"the rule the chart matches, named once, was: " + record);
		assertNull(sentenceAfter(record, NOT_RECORDED_MARKER),
				"and nothing on the negative side: the duplicate note is already claimed true, and the "
						+ "other two rules were never askable, was: " + record);
		assertTrue(record.contains("avoid in CKD stage 4 or worse")
				&& record.contains("avoid in severe hepatic impairment"),
				"while the drug's own list still carries them — marked, not filtered, was: " + record);
	}

	@Test
	public void aChartTheModuleCouldNotReadIsNotAChartThatRecordsNothing() throws Exception {
		// The other half of "cannot evaluate", one level up: the RULE is askable and the CHART is not.
		// PatientClinicalContextBuilder swallows a failed allergy or condition read and degrades that
		// dimension to an empty set — right for a chip, which must not raise what it cannot substantiate,
		// and wrong for a record that would report the emptiness as a fact about the patient. Without the
		// readability flag this reads "Not recorded for this patient: NSAID hypersensitivity; documented
		// ibuprofen allergy; …" for a patient who may well be allergic, as citable evidence, with no chip
		// beside it — this issue's own defect with the sign flipped.
		String record = ibuprofenRecord(DrugReferenceTestSupport.unreadableRecordsCtx(60, null));

		assertNull(sentenceAfter(record, RECORDED_MARKER),
				"a chart that could not be read supports no claim either way, was: " + record);
		assertNull(sentenceAfter(record, NOT_RECORDED_MARKER),
				"least of all a negative one, was: " + record);
		assertTrue(record.contains(RULE_LIST_MARKER + String.join("; ", SHIPPED_IBUPROFEN_RULES) + "."),
				"while the drug's own list is reference material and is rendered either way, was: "
						+ record);
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
		assertFalse(record.contains(RULE_LIST_MARKER.trim()),
				"precondition: the ddinter source publishes no contraindications, was: " + record);
		assertNull(sentenceAfter(record, RECORDED_MARKER),
				"so there is nothing to state a patient-specific reading of, was: " + record);
		assertNull(sentenceAfter(record, NOT_RECORDED_MARKER),
				"on either side, was: " + record);
	}
}
