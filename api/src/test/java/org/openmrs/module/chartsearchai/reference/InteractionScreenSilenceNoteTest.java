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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * A screening question the interaction screen related nothing on carries a record saying so, instead
 * of reaching the model with no reference material at all — issue #401.
 *
 * <p><b>What was measured.</b> On the 3.7.1 standalone at {@code :8081}, {@code sourceFormat=ddinter},
 * a patient on three antiretrovirals — Lamivudine 150mg, Nevirapine 200mg, Stavudine 30mg — asked
 * <i>"Are any of this patient's current medications interacting with each other?"</i> answered:
 *
 * <blockquote>The records do not address drug interactions.</blockquote>
 *
 * Its audit row recorded {@code reference_slice_chars = 0}: the screen related no pair above the
 * severity floor, the screening arm has no class leg, so the injector wrote nothing and the model was
 * left with an empty reference slice to describe. It described it as the RECORDS not addressing
 * interactions, which is a claim about the chart — and on that same patient a question naming two of
 * those drugs does relate them (<i>"same ATC class (J05AF) — possible duplicate therapy"</i>), so the
 * chart plainly does address them.
 *
 * <p><b>Never render silence as denial</b> is the standing rule of this subsystem, and this is that
 * rule at the level of an ARM rather than of a record: the injector's own negative claims are already
 * gated on whether the chart was read, but a screen that ran and related nothing was leaving no trace
 * in the prompt whatsoever. The remedy is the shape #354 established for a question naming an
 * unresolvable drug class — a citable record stating what the module established — and not a class leg
 * on the screening arm, which would move what {@code PairChipExtent} counts.
 *
 * <p><b>What the note may say is bounded the way {@code renderDrugClassNote}'s is.</b> Every clause is
 * a claim about what the SCREEN did, never about what the patient has: how many active medications
 * were checked against each other, that the reference data relates none of them above the configured
 * floor, and that relationships resting only on shared classification are not part of this check. The
 * last clause is load-bearing rather than a hedge — it is exactly what the standalone's own pair
 * question went on to find — and the note names no drug, because it is citable evidence and there is
 * nothing here to navigate to.
 *
 * <p>The fixture is {@code OneOrderNameAcrossOneResponseTest}'s, whose note already establishes the
 * arrangement: Prednisolone and Methylprednisolone are related by one rule rated {@code Unknown},
 * which the shipped {@code chartsearchai.drugSafety.minInteractionSeverity} default filters out, so a
 * screen over the two of them relates nothing. {@link #aScreenThatRelatedAPairStatesNoNote} uses
 * {@code FoldedFindingStrengthTest}'s pair instead, which is rated Minor and clears that floor.
 */
public class InteractionScreenSilenceNoteTest {

	/** Two active orders the shipped floor leaves unrelated — see the fixture's own note. */
	private static final String UNRELATED_PAIR_FIXTURE =
			"chartsearchai-test/ddi-class-only-and-rule-one-partner.json";

	/** Two active orders related by a Minor rule, which clears the shipped floor. */
	private static final String RELATED_PAIR_FIXTURE =
			"chartsearchai-test/ddi-folded-minor-class-pair.json";

	/** The screening shape, and the same wording {@code FoldedFindingStrengthTest} drives that arm
	 *  with: it names no drug the reference data carries, which is what stands the drug-in-play arm
	 *  down and lets the screen run. */
	private static final String SCREENING_QUESTION =
			"are there any drug interactions with her current medications?";

	private static PatientChart injected(String fixture, String... activeOrders) throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(fixture);
		List<PatientClinicalContext.ActiveDrugOrder> orders =
				new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		java.util.Set<String> names = new java.util.LinkedHashSet<String>();
		java.util.Set<String> codes = new java.util.LinkedHashSet<String>();
		for (String order : activeOrders) {
			PatientClinicalContext.ActiveDrugOrder one =
					DrugReferenceTestSupport.activeOrderFor(service, order);
			orders.add(one);
			names.add(order);
			codes.addAll(one.getAtcCodes());
		}
		return DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, names, codes, null, null, orders),
				SCREENING_QUESTION);
	}

	/** @return the injected screen notes, by resource type rather than by wording. */
	private static List<RecordMapping> screenNotes(PatientChart chart) {
		List<RecordMapping> notes = new ArrayList<RecordMapping>();
		for (RecordMapping mapping : chart.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_INTERACTION_SCREEN_NOTE
					.equals(mapping.getResourceType())) {
				notes.add(mapping);
			}
		}
		return notes;
	}

	/**
	 * The defect: a screen that ran over two active orders and related neither of them must leave the
	 * model something to state.
	 */
	@Test
	public void aScreenThatRelatedNothingStatesThatItRanAndRelatedNothing() throws IOException {
		PatientChart chart = injected(UNRELATED_PAIR_FIXTURE, "Prednisolone", "Methylprednisolone");

		assertTrue(DrugReferenceTestSupport.injectedFindings(chart).isEmpty(),
				"precondition: the screen must relate nothing above the floor, or this arrangement is "
						+ "not the silent one: " + chart.getText());

		List<RecordMapping> notes = screenNotes(chart);
		assertEquals(1, notes.size(),
				"a screen that ran and related nothing states exactly one note — with none, the model "
						+ "reaches an empty reference slice and describes it as the RECORDS not "
						+ "addressing interactions: " + chart.getText());
		String note = notes.get(0).getText();
		assertTrue(note.contains("2"),
				"it says how many active medications were checked against each other: " + note);
		assertFalse(note.toLowerCase().contains("prednisolone"),
				"and it names no drug — it is citable evidence with nothing to navigate to: " + note);
	}

	/**
	 * The note's FIRST proposition is the finding, not the count, and the count comes after it.
	 *
	 * <p>Measured rather than reasoned about. The first build put the count first, and on the
	 * standalone the model cited the note and opened <b>"Yes —"</b> on <i>"Are any of this patient's
	 * current medications interacting with each other?"</i> — a verdict lead inverted against the
	 * clause it introduced, which on that question is the dangerous direction and worse than the
	 * empty-slice denial the note replaced. The prompt asks for a verdict lead on a yes/no question,
	 * so the first thing the record offers is the polarity the answer copies.
	 *
	 * <p>The needle is a literal here rather than {@code DrugReferenceInjector}'s constant, for the
	 * reason {@link FoldedFindingStrengthTest} states of its strength clauses: a test comparing that
	 * constant to itself would stay green through the very reword this case exists to stop.
	 */
	@Test
	public void theNoteStatesTheFindingBeforeTheCount() throws IOException {
		PatientChart chart = injected(UNRELATED_PAIR_FIXTURE, "Prednisolone", "Methylprednisolone");
		String note = screenNotes(chart).get(0).getText();

		int finding = note.indexOf("No interactions were found among this patient's active medications.");
		int count = note.indexOf("2");
		assertTrue(finding >= 0,
				"the note must state its finding as a complete sentence in these words — the polarity, and the phrase, the answer copies: "
						+ note);
		assertTrue(finding < count,
				"and state it BEFORE the count, or a yes/no screening question gets a verdict lead "
						+ "chosen off a number: " + note);
	}

	/**
	 * The note is in the prompt, which is the whole point of it: a record the model never reads
	 * cannot stop it describing an empty slice.
	 */
	@Test
	public void theNoteIsInTheTextTheModelReads() throws IOException {
		PatientChart chart = injected(UNRELATED_PAIR_FIXTURE, "Prednisolone", "Methylprednisolone");

		RecordMapping note = screenNotes(chart).get(0);
		assertTrue(chart.getText().contains(note.getText()),
				"the note must be rendered into the chart text, numbered like every other record: "
						+ chart.getText());
		assertTrue(chart.getText().contains("[" + note.getIndex() + "] "),
				"and carry its own citation number: " + chart.getText());
	}

	/**
	 * The note wears the SAFETY-FINDING lead, not the drug-reference one, and that is a measured
	 * choice rather than a cosmetic one.
	 *
	 * <p>The prompt's record-type rules say a record beginning {@code "Drug reference"} is reference
	 * data and NOT this patient's, while one beginning {@code "Safety finding"} IS about this patient.
	 * This note's subject is her own medications. Measured on the standalone over the same question,
	 * three runs each: under the reference lead every run answered <b>"Yes — no interactions were found
	 * among this patient's active medications [7]"</b>, and under the finding lead every run answered
	 * <b>"No — ..."</b>. Same note, same question, same build; only the lead differed, and it decided
	 * whether the verdict contradicted the clause behind it.
	 *
	 * <p>The needle is a literal for the reason the others here are. What this case does NOT assert is
	 * that the note joins any finding population — it must not, and it does not: every consumer keys on
	 * the resource TYPE, which {@link #aScreenThatRelatedNothingStatesThatItRanAndRelatedNothing}
	 * selects on.
	 */
	@Test
	public void theNoteWearsTheFindingLeadBecauseItsSubjectIsThisPatient() throws IOException {
		PatientChart chart = injected(UNRELATED_PAIR_FIXTURE, "Prednisolone", "Methylprednisolone");
		String note = screenNotes(chart).get(0).getText();

		assertTrue(note.startsWith("Safety finding"),
				"the note speaks about this patient's own medications, so it must read under the rule "
						+ "for records that ARE about her — under the reference lead the same note took "
						+ "an inverted verdict on the standalone: " + note);
		assertFalse(note.startsWith("Drug reference"),
				"and never under the rule that tells the model this is not her data: " + note);
	}

	/**
	 * A screen that DID relate a pair states no note. The finding is what the model reads there, and a
	 * note beside it would say the screen related nothing while a finding beside it says otherwise.
	 */
	@Test
	public void aScreenThatRelatedAPairStatesNoNote() throws IOException {
		PatientChart chart = injected(RELATED_PAIR_FIXTURE, "Methylphenidate", "Modafinil");

		assertEquals(1, DrugReferenceTestSupport.injectedFindings(chart).size(),
				"precondition: this fixture's pair clears the floor, so the screen relates it: "
						+ chart.getText());
		assertTrue(screenNotes(chart).isEmpty(),
				"a screen that related a pair must not also say it related nothing: " + chart.getText());
	}

	/**
	 * One active order states no note: there is no pair to screen, so an arm that reports nothing
	 * there is reporting the chart rather than its own silence — the distinction
	 * {@code PairChipExtent}'s javadoc draws between a completed negative screen and a question
	 * nobody screened.
	 */
	@Test
	public void aChartWithNothingToScreenStatesNoNote() throws IOException {
		PatientChart chart = injected(UNRELATED_PAIR_FIXTURE, "Prednisolone");

		assertTrue(screenNotes(chart).isEmpty(),
				"one medication is not a pair, and a note claiming a screen ran over it would be "
						+ "stating something the module did not do: " + chart.getText());
	}

	/**
	 * ONE prescription of a substance the knowledge base files as several presentation ROWS is still
	 * one medication. Counted as rows it is a pair with nothing to compare, and the note fires and
	 * says two medications were checked.
	 *
	 * <p>Found by this file's first run rather than reasoned about: the fixture's Methylprednisolone is
	 * two rows under one {@code drugbank_id}, so the two-order case above reported <b>3</b> medications
	 * checked until the count went through {@link DrugReference#substanceGroupKey()}. That is the
	 * "N of something" defect this class's identity rules exist for, reached here through a COUNT that
	 * a clinician reads in a citable record.
	 */
	@Test
	public void onePrescriptionOfAMultiRowSubstanceIsNotAPair() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(UNRELATED_PAIR_FIXTURE);
		assertTrue(service.findByDrugName("Methylprednisolone").size() > 1,
				"precondition: this fixture must file the substance as more than one row, or the case "
						+ "cannot reach the row-versus-substance difference it is about");

		PatientChart chart = injected(UNRELATED_PAIR_FIXTURE, "Methylprednisolone");

		assertTrue(screenNotes(chart).isEmpty(),
				"one prescription is one medication however many rows the data files it as: "
						+ chart.getText());
	}
}
