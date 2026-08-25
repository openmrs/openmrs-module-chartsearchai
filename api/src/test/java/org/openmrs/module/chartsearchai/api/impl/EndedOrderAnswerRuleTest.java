/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;

/**
 * The PROMPT half of issue #315: an answer could name a drug from a <strong>stopped</strong>
 * drug-order record while dropping the stop date that same cited record carries, and on one
 * question shape deny the current-medication status the record settles.
 *
 * <p>The module already had one definition of "this record describes an ended order" —
 * {@code DrugReferenceInjector.describesEndedOrder}, which keeps such a record from substantiating a
 * live order in the #118 reconciliation — and nothing used it to shape what the ANSWER must say.
 *
 * <p>The DATA half is {@code EndedOrderMarkerContractTest}, which pins that the markers asserted
 * here are verbatim in what querystore really renders and are recognised by that matcher. The two
 * are joined by the PUBLIC constants rather than by a widened accessor: neither
 * {@code describesEndedOrder} nor {@link LlmProvider#DEFAULT_SYSTEM_PROMPT} is visible from the
 * other's package, and this is the coupling {@code LlmProviderTest} already uses for
 * {@code DrugReferenceInjector.FINDING_PREFIX}. An assertion here that the prompt contains a
 * hand-typed {@code ". Stopped: "} would be a second definition of the marker, which is the drift
 * the shared constant exists to prevent.
 *
 * <p><b>What was measured, and why the rule is shaped the way it is.</b> On the 3.7.1 standalone
 * against {@code main} @ {@code 3775c997}, one concept-only Nevirapine drug order stopped the day
 * before and no active order, {@code chartMode=queryScoped}, n=3 byte-identical per shape: three of
 * four question shapes named the drug and dropped its end, and {@code "is he currently taking any
 * medications?"} answered {@code "Yes — the patient was ordered Nevirapine on 2026-07-26 [1]."}
 * Only the shape whose question supplied the word "stopped" carried the date. On a second patient
 * carrying a stopped order BESIDE an active one, the two "currently taking" shapes were already
 * correct and must stay so — which is why the rule is scoped to the drug the record names.
 */
public class EndedOrderAnswerRuleTest {

	@Test
	public void thePromptNamesBothMarkersTheMatcherKeysOn() {
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;

		assertTrue(prompt.contains(DrugReferenceInjector.ORDER_STOPPED_MARKER),
				"the prompt must name the stop marker, or nothing tells the model what an ended "
						+ "drug order is and the stop date survives only when the question supplies "
						+ "the word (issue #315)");
		assertTrue(prompt.contains(DrugReferenceInjector.ORDER_DISCONTINUED_MARKER),
				"and the discontinue marker too: describesEndedOrder keys on EITHER marker, so a "
						+ "prompt naming only the stop date teaches a narrower record class than the "
						+ "module itself recognises");
	}

	@Test
	public void thePromptRequiresAnEndedOrdersEndToBeStatedWhereverTheDrugIsNamed() {
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;

		assertTrue(prompt.contains("has ENDED"),
				"the prompt must classify the record — naming a marker without saying what it means "
						+ "leaves the model to infer it from phrasing, which is the defect");
		assertTrue(prompt.contains("say in the same sentence that the order was stopped"),
				"naming the drug and stating the end must be ONE sentence: every measured failure "
						+ "named the drug in a sentence of its own and simply ended there");
		assertTrue(prompt.contains("when the record carries one"),
				"the stop DATE is conditional, because a DISCONTINUE order renders no date — pinned "
						+ "by EndedOrderMarkerContractTest"
						+ ".aDiscontinuedOrderCarriesNoStopDate_soTheRuleMayNotDemandOne");
	}

	@Test
	public void thePromptRefusesAYesAndAnUnrecordedStatusOnTheStrengthOfAnEndedOrder() {
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;

		// The prompt's own "latest, current, or most recent value" rule points AT the stopped
		// record — it is the first matching one — so a clause that merely asks for the date to be
		// mentioned leaves that rule pointing at the same place. The rule therefore contradicts the
		// antecedent ("this record SETTLES the status") rather than competing with its conclusion.
		assertTrue(prompt.contains("SETTLES whether the patient is on that drug"),
				"the record must be stated to SETTLE the status, not merely to carry a date");
		assertTrue(prompt.contains("never treat that drug's current status as unrecorded"),
				"the second half of #315: the answer denied the status its own cited record states");

		// Shape-INDEPENDENT, and measured rather than preferred. The first draft of this clause
		// scoped the prohibition to a yes/no framing ("asked whether they are currently taking it,
		// answer No"), and on the standalone that left the wh-question shape uncovered and made it
		// WORSE than the baseline: "what medications is he taking?" went from "The patient was
		// taking Nevirapine [1]" (past tense) to "He is currently taking Nevirapine [1]" — a flat
		// falsehood about a drug stopped the day before — n=3 byte-identical on both sides. A
		// question's grammatical shape must not decide whether a stopped drug reads as current.
		assertTrue(prompt.contains("never present that drug as one the patient is taking now"),
				"the prohibition must not be scoped to a yes/no framing: a wh-question ('what "
						+ "medications is he taking?') names the drug without ever asking whether, "
						+ "and that is the shape a yes/no-scoped clause measurably made worse");

		// And it must out-rank the prompt's own current-value rule, which points AT this record.
		// Measured: with the shape-independent prohibition but WITHOUT this phrase, "what
		// medications is he taking?" still answered "He is taking Nevirapine [1]" — present tense,
		// no end — n=3 byte-identical, because "the relevant record is the FIRST matching one in
		// the list; report that value" is satisfied by the stopped order when it is the only one.
		assertTrue(prompt.contains("even where it is the most recent or the only drug order in the chart"),
				"the rule must name the case the current-value rule above resolves the other way, "
						+ "or a chart whose ONLY drug order is a stopped one reports it as current");
		assertTrue(prompt.contains("asked what they are taking, do not list that drug among them"),
				"the wh-question shape needs its own lead for the same reason the yes/no shape does");

		// Paired with a positive lead, per #214's precedent in this same prompt. A prohibition that
		// removes every plausible lead and supplies none leaves the model to invent one — and the
		// #107 measurements show an unled answer drifts to whichever branch it reaches for.
		assertTrue(prompt.contains("answer \"No\" and name the date it stopped"),
				"a prohibition must come with the lead that replaces it, or the model has nothing "
						+ "to say for the single-stopped-order case");
	}

	@Test
	public void theRuleIsScopedToTheDrugTheRecordNames() {
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;

		// The over-fire guard. A stopped-order record settles that THIS drug is not current; it
		// settles nothing about whether the patient is on any medication — and under the shipped
		// queryScoped mode the model is reading a slice, so an unscoped rule would turn an
		// over-hedge into a fabricated categorical negative, which is the slice-for-the-patient
		// confusion #94 and #214 already name. Measured on a patient carrying a stopped order
		// beside an active one, n=3: "is he currently taking any medications?" correctly answers
		// "Yes ... Acetaminophen", and must still.
		assertTrue(prompt.contains("settles nothing about any OTHER drug"),
				"the rule must be scoped to the drug the record names, or it licenses 'the patient "
						+ "is on no medication' from one stopped record");
		assertTrue(prompt.contains("carrying neither marker is current"),
				"and it must say what an order with NO end marker means, or the scoping has no "
						+ "positive counterpart to answer a medications question from");
	}

	@Test
	public void theEndedOrderRuleStaysOutOfTheSafetyParagraph() {
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;

		// #107/#126 assert structurally that the safety/suitability paragraph carries no
		// otherwise-branch in any wording, extracting it from its lead sentence to the next
		// newline. A record-class rule dropped inside that window would both widen what those
		// assertions read and put a second lead instruction into the paragraph that regressed
		// before. This pins that the #315 rule sits outside it.
		int safetyRuleAt = prompt.indexOf("The same rules apply to safety and suitability questions");
		assertTrue(safetyRuleAt > 0, "the safety/suitability paragraph must still be present");
		int safetyRuleEnd = prompt.indexOf('\n', safetyRuleAt);
		assertTrue(safetyRuleEnd > safetyRuleAt, "and must still be newline-terminated");
		String safetyRule = prompt.substring(safetyRuleAt, safetyRuleEnd);

		assertFalse(safetyRule.contains(DrugReferenceInjector.ORDER_STOPPED_MARKER),
				"the ended-order rule must not live in the safety/suitability paragraph: " + safetyRule);
		assertFalse(safetyRule.toLowerCase().contains("otherwise"),
				"and adding it must not have introduced an otherwise-branch there: " + safetyRule);
	}
}
