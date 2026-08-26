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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * hand-typed {@code ". Stopped: "} would be a second definition of the marker. Be precise about what
 * that buys: a hand-typed copy compiles to a byte-identical prompt and passes every case here, so
 * the sharing does not catch it today. What it catches is the NEXT querystore rename — the constant
 * moves, a hardcoded copy does not, and {@code EndedOrderMarkerContractTest} reddens.
 *
 * <p><b>ADR Decision 45 is canonical for what was measured and for the drafts it refuted</b>, and it
 * is not restated here — three copies of one measurement narrative is how this repo's notes have come
 * to contradict themselves before. Each case below carries only what justifies its own assertion.
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
	public void thePromptNamesBothRecordPrefixesItClassifiesBy() {
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;

		// The clause identifies the record CLASS by these two cues before it looks for either end
		// marker, so a reworded cue silently stops the whole rule firing. Two independent Phase 2
		// reviewers found this unpinned and one confirmed it by mutation: rewriting the prompt's
		// "Drug order:" to "Drug prescription:" left the entire suite green.
		//
		// The querystore side is pinned by EndedOrderMarkerContractTest against the real serializer;
		// the module's own side by ActiveOrderReconciliationTest, which asserts the rendered
		// "Active drug order: ..." text. These two assertions close the loop from the prompt end.
		//
		// And the VERB the class cue is introduced by, which is load-bearing at one word and was
		// pinned nowhere: mutating "its text begins" to the literally more accurate "carries"
		// leaves the whole suite green while putting #315's original defect back. Measured, one
		// word changed and everything else byte-identical: "is he currently taking any
		// medications?" on the ticket's own arrangement went from "No, the patient is not
		// currently taking any medications. The order for Nevirapine was stopped on 2026-08-24"
		// to "Yes - the patient is currently taking Nevirapine", n=3 byte-identical on both
		// sides, and reverting the word restored it, also n=3. The doubled "carries … that also
		// carries" is the likely mechanism and the evidence does not establish it; what it does
		// establish is that this word is not free to be corrected.
		assertTrue(prompt.contains("its text begins"),
				"the class cue must stay 'its text BEGINS'. Correcting it to 'carries' — which is "
						+ "literally the more accurate word, since records reach the model as "
						+ "'[7] Drug order: …' — reinstates #315's defect: n=3 byte-identical, the "
						+ "ticket's stopped-Nevirapine chart answered 'Yes - the patient is "
						+ "currently taking Nevirapine' again, and reverting the word restored the "
						+ "correct answer, also n=3");
		assertTrue(prompt.contains(DrugReferenceInjector.QUERYSTORE_DRUG_ORDER_PREFIX),
				"the prompt must name querystore's drug-order prefix, or no chart record is "
						+ "recognised as belonging to the class this rule is about");
		assertTrue(prompt.contains(DrugReferenceInjector.ACTIVE_ORDER_PREFIX),
				"and the module's own active-order prefix, or the record #118 injects to stop a "
						+ "chip and the prose contradicting each other has no standing in the rule");
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
		assertTrue(prompt.contains("that settles that the patient is not on it"),
				"the record must be stated to SETTLE the status, not merely to carry a date");
		assertTrue(prompt.contains("never treat its current status as unrecorded"),
				"the second half of #315: the answer denied the status its own cited record states");

		// Shape-INDEPENDENT, and measured rather than preferred. The first draft of this clause
		// scoped the prohibition to a yes/no framing ("asked whether they are currently taking it,
		// answer No"), and on the standalone that left the wh-question shape uncovered and made it
		// WORSE than the baseline: "what medications is he taking?" went from "The patient was
		// taking Nevirapine [1]" (past tense) to "He is currently taking Nevirapine [1]" — a flat
		// falsehood about a drug stopped the day before — n=3 byte-identical on both sides. A
		// question's grammatical shape must not decide whether a stopped drug reads as current.
		assertTrue(prompt.contains("never present it as one they are taking now"),
				"the prohibition must not be scoped to a yes/no framing: a wh-question ('what "
						+ "medications is he taking?') names the drug without ever asking whether, "
						+ "and that is the shape a yes/no-scoped clause measurably made worse");

		// And it must out-rank the prompt's own current-value rule, which points AT this record.
		// Measured: with the shape-independent prohibition but WITHOUT this phrase, "what
		// medications is he taking?" still answered "He is taking Nevirapine [1]" — present tense,
		// no end — n=3 byte-identical, because "the relevant record is the FIRST matching one in
		// the list; report that value" is satisfied by the stopped order when it is the only one.
		assertTrue(prompt.contains("even where its record is the most recent or the only drug order"),
				"the rule must name the case the current-value rule above resolves the other way, "
						+ "or a chart whose ONLY drug order is a stopped one reports it as current");
		// The wh branch needs the positive counterpart for the same reason the yes/no branch does,
		// and it was measured missing: with the prohibition alone, "what medications is he taking?"
		// answered "No medications are currently recorded for the patient." — dropping the stopped
		// order and citing nothing, n=3 byte-identical. A prohibition that names no replacement
		// reads as silence, which is #214's lesson arriving one branch along.
		assertTrue(prompt.contains("do not list it among the drugs they are taking"),
				"the wh-question shape needs its own prohibition, since it never asks 'whether'");
		assertTrue(prompt.contains("so the record is reported rather than dropped"),
				"and that prohibition needs its positive counterpart, or the answer omits the very "
						+ "record the clause exists to surface");

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
		// The counterpart says what the record RECORDS, and says nothing about how to answer.
		// Its predecessor did both — "A drug-order record carrying neither marker is CURRENT, and
		// a question about what the patient is taking now is answered from those" — and each half
		// was measured harmful, n=3 byte-identical per arm against the same prompt with the whole
		// clause excised. Asserting CURRENCY: on a chart whose Simvastatin order had lapsed by its
		// auto_expire_date (which querystore renders no marker for) beside two live orders, "is he
		// currently taking any medications?" answered "Yes — the patient is currently taking
		// Simvastatin Co 20mg [8]" — the lapsed drug asserted current and both live ones dropped,
		// against a baseline that named the two live ones and called the third an older order.
		// The ANSWERING half: on 8 active orders and no ended ones the same question lost its
		// verdict lead altogether, which is the #107 property the yes/no paragraph below states.
		assertTrue(prompt.contains("carrying neither marker records no end"),
				"the clause must say what an order with NO end marker means, or the scoping has "
						+ "no counterpart and the ended branch reads as the only rule; and it must "
						+ "say it WITHOUT asserting the drug is current — the module cannot see an "
						+ "auto-expiry, so 'is CURRENT' states a falsehood about a lapsed order — "
						+ "and without saying which records a medications question is answered "
						+ "from, which out-ranked the verdict-lead rule below it");

		// The SAME-drug case, which the "any OTHER drug" scoping above does not reach and which the
		// first version of this clause left to inference. A dose change in OpenMRS is a REVISE: a new
		// order is created and the previous order's dateStopped is set, so a chart routinely carries
		// an ended record and a live record for ONE drug. Measured on that arrangement (a stopped
		// Nevirapine 200mg beside a live REVISE 400mg, n=2 byte-identical) the model answered "Yes"
		// on all three cells WITHOUT this sentence — so what it fixes is not an observed wrong answer
		// but a clause that did not say what it meant, on the commonest chart shape there is.
		assertTrue(prompt.contains("the CURRENT record governs"),
				"where one drug has both an ended and a live order the live one must be stated to "
						+ "win, or the clause's categorical 'never present that drug as one the "
						+ "patient is taking now' is left to be resolved by inference");
		assertTrue(prompt.contains("Where every record naming a drug has ended"),
				"and the ended branch must be conditioned on ALL of the drug's records having "
						+ "ended, or it still reads as categorical about the drug");
	}

	@Test
	public void theWholePromptIsStillONE_compileTimeConstant() throws Exception {
		// A near-miss from this change's own review, kept as a guard because it is silent in every
		// channel that normally catches things. The clause concatenates constants from
		// DrugReferenceInjector; writing one of them as ACTIVE_ORDER_PREFIX.trim() made the
		// initializer a non-constant expression, so javac stopped folding DEFAULT_SYSTEM_PROMPT into
		// a single literal and computed it in <clinit> instead. It COMPILED, the whole suite stayed
		// green, and the prompt was correct at runtime — but it no longer existed in the class
		// file's constant pool, which is where the eval harness's pure-prompt A/B reads it from to
		// build its arms. The A/B silently produced a truncated prompt.
		//
		// So this asserts the property that broke: the prompt is present, verbatim and whole, in
		// LlmProvider's own constant pool. Any future operand that is not a constant expression
		// (a .trim(), a String.format, a method call) reddens this and names why.
		byte[] classFile;
		try (java.io.InputStream in = LlmProvider.class.getResourceAsStream("LlmProvider.class")) {
			assertNotNull(in, "LlmProvider.class must be readable from the test classpath");
			java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			for (int n; (n = in.read(chunk)) > 0; ) {
				buf.write(chunk, 0, n);
			}
			classFile = buf.toByteArray();
		}

		// The pool stores modified UTF-8; for this prompt (no NUL, no supplementary characters)
		// that coincides with standard UTF-8, so a straight byte search is exact.
		byte[] needle = LlmProvider.DEFAULT_SYSTEM_PROMPT.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(indexOf(classFile, needle) >= 0,
				"DEFAULT_SYSTEM_PROMPT must remain a COMPILE-TIME constant, folded whole into "
						+ "LlmProvider's constant pool. If this fails, an operand of the "
						+ "concatenation stopped being a constant expression — javac now builds the "
						+ "prompt in <clinit>, which is correct at runtime and invisible to every "
						+ "test, but breaks anything reading the prompt out of the class file, "
						+ "including eval/drift-metric's pure-prompt A/B.");
	}

	/** First index of {@code needle} in {@code haystack}, or -1. */
	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
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
		assertFalse(safetyRule.toLowerCase(java.util.Locale.ROOT).contains("otherwise"),
				"and adding it must not have introduced an otherwise-branch there: " + safetyRule);
	}
}
