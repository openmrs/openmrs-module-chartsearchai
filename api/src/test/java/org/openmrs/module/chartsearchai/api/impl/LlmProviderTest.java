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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;

/**
 * Pure unit tests for {@link LlmProvider} response parsing and configuration logic.
 */
public class LlmProviderTest {

	@Test
	public void defaultSystemPrompt_shouldMentionClinicalAssistant() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("clinical assistant"));
	}

	@Test
	public void defaultSystemPrompt_shouldRequireCitations() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("[1]") || LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("[2]"));
	}

	@Test
	public void defaultSystemPrompt_shouldConstrainToQuestionAsked() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("ONLY the specific query"));
	}

	@Test
	public void defaultSystemPrompt_shouldRequireAllRelevantRecords() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("ALL relevant records"),
				"System prompt must instruct LLM to include ALL relevant records");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("never omit"),
				"System prompt must explicitly tell LLM not to omit records for brevity");
	}

	@Test
	public void defaultSystemPrompt_shouldDescribeRecordOrderingAccurately() {
		// The default prompt tells the LLM records appear most-recent-first; this test pins
		// that wording so the LLM's mental model matches the chart's ordering (records reach
		// the LLM in querystore's chart order — date descending).
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("most recent first"),
				"System prompt must describe records as sorted most recent first");
		assertFalse(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("grouped by type"),
				"System prompt must not claim records are grouped by type — they are not");
	}

	@Test
	public void defaultSystemPrompt_shouldRequireVerdictLeadOnYesNoQuestions() {
		// Measured on the rc.2 standalone (2026-07-21, probe-yesno-baseline-20260721): on
		// short-register yes/no questions ("any heart problems", "is she hypertensive") only
		// 47% of answers opened with a verdict — the rest enumerated findings without ever
		// answering the question. The prompt must demand a record-grounded verdict lead.
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("yes/no question"),
				"System prompt must address yes/no questions explicitly");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("begin the answer with an explicit verdict"),
				"System prompt must require answers to yes/no questions to open with a verdict");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("is recorded"),
				"System prompt must teach the record-grounded \"No <condition> is recorded\" verdict form");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("Never infer"),
				"The verdict instruction must not displace the no-inference rule");
	}

	@Test
	public void defaultSystemPrompt_shouldForbidYesNoVerdictsOnUnaddressedSafetyQuestions() {
		// Measured on the 3.7.1 standalone (2026-07-28, issue #107): "Is it safe to give her
		// panadol?" where no record addresses panadol produced a SAMPLED verdict across five
		// generations (Yes/Yes/Yes/No/abstain), each justified by the patient's unrelated
		// aspirin allergy — a groundless safety verdict in front of a legitimately grounded
		// citation. The presence-shaped verdict rules ("is the patient hypertensive") did not
		// transfer to the safety/suitability shape, so the prompt must name it explicitly and
		// demonstrate it in the few-shot.
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("safety and suitability questions"),
				"System prompt must address safety/suitability yes/no questions explicitly");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("the records do not address"),
				"System prompt must teach the records-do-not-address verdict for unaddressed subjects");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("never evidence for or against"),
				"System prompt must forbid justifying a safety verdict with a record about something else");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("Is it safe to deliver mangoes?"),
				"The few-shot must demonstrate the unanswerable safety question");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("The records do not address mango deliveries"),
				"The few-shot's safety demonstration must lead with the abstention verdict, not Yes/No");
		// Review follow-up on the first guard shape (issue #107): appending the unrelated record
		// as "context" after the abstention verdict implies a relevance the system cannot
		// establish — its own deterministic layers assert none — and invites exactly the false
		// inference the model drew pre-guard ("No — the patient has an aspirin allergy"). The
		// verdict stands alone, mirroring the cite-nothing rule for category questions.
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("cite nothing with it"),
				"System prompt must forbid attaching records to an unaddressed-safety verdict");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains(
				"\"answer\": \"The records do not address mango deliveries.\", \"citations\": []"),
				"The few-shot's safety abstention must stand alone with no cited context");
		assertFalse(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("For context, 8 oranges"),
				"The few-shot must not teach attaching unrelated records as context");
	}

	@Test
	public void defaultSystemPrompt_shouldTellTheModelWhatASafetyFindingRecordIs() {
		// Measured live (2026-07-31): with the deterministic finding injected, "Is naproxen safe for
		// this patient?" answered 3/3 "The records do not address the safety of Naproxen for this
		// patient, BUT they do indicate a possible cross-reactivity finding: ... [232]" — an abstention
		// clause sitting in front of evidence that contradicts it. The same build answered the
		// ibuprofen equivalent cleanly, so the substance was right and only the framing failed.
		//
		// Cause: the abstention rule's precondition is "no record addresses the drug asked about", and
		// a safety-finding record IS such a record — but the prompt describes what a "Drug reference"
		// record is and says nothing about "Safety finding", a type this change introduced. With no
		// rule for it the model reads the chart as silent on the drug, emits the abstention, then
		// appends the finding it cannot ignore.
		//
		// So this is a record-TYPE description, deliberately not another verdict or wording rule: four
		// such rules were measured and reverted (a quoted prohibition primed the phrase it banned; a
		// verdict-lead deferral inherited a YES criterion that inverts on safety questions; an
		// "otherwise state what the record shows" fallback let reference material break abstention on
		// unconnected cells). Being gated on a finding record existing bounds this one structurally:
		// no finding, no effect, so the ABSTAIN cells issue #107 guards cannot be reached.
		//
		// AMENDED by #112 (2026-08-04): the sentence this test pins is no longer purely a record-TYPE
		// description. Its lead clause now reads "answer the question from that finding and cite it",
		// re-pointed from "open with what the finding says" — the recitation lead that clause asked
		// for is what six of six safety probes complied with, leaving the clinical call only on the
		// chip (see defaultSystemPrompt_shouldTeachTheVerdictLeadWhenASafetyFindingAddressesTheQuestion).
		// Every clause this test asserts is untouched, and the structural bound above still holds for
		// both rules: each is gated on a finding record existing. Read the two together — the safety
		// guidance for this feature lives in TWO paragraphs written by two changes, and reading one
		// without the other is exactly how #112 misdiagnosed its own root cause.
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("Safety finding"),
				"System prompt must describe the safety-finding record type it is given");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("records DO address that drug"),
				"It must say a finding satisfies the addressed branch, or the abstention rule misfires");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("contradicts it in the same breath"),
				"It must forbid the measured abstention-then-finding contradiction");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("the records do not address"),
				"and must not disturb the #107 abstention rule it is scoped around");
	}

	@Test
	public void defaultSystemPrompt_shouldTeachTheVerdictLeadWhenASafetyFindingAddressesTheQuestion() {
		// Measured on the 3.7.1 standalone (2026-08-04, issue #112): six of six safety probes whose
		// finding, chip and citation were all correct still answered the wrong question. "Is it safe
		// to give her clarithromycin?" (mary, active simvastatin) -> "Clarithromycin interacts with
		// active order simvastatin — Major. Coadministration with potent inhibitors of CYP450 3A4
		// may significantly increase the plasma concentrations of simvastatin..." The words "not
		// safe", "avoid" and "No" never appeared: the clinical call existed only on the chip.
		//
		// Cause: that is literal compliance with the record-type sentence #110 added, which told the
		// model to "open with what the finding says". The mechanism was therefore occupying the
		// verdict slot. The safety/suitability rule below it covers only the UNADDRESSED branch
		// ("when no record addresses the drug ... never Yes or No") and the only safety few-shot is
		// the mango abstention, so nothing described or demonstrated the addressed branch.
		//
		// So the finding's lead is re-pointed at the verdict and the addressed branch is written and
		// demonstrated. THREE constraints come from the arms measured and reverted on 2026-07-30
		// (eval/drift-metric/README.md, "the two-hop join is impossible"), and each one below carries
		// its own assertion because a mutation probe showed none of them was pinned. Seven shapes this
		// change must resist all left `mvn test` green: deleting any of its five load-bearing clauses
		// from DEFAULT_SYSTEM_PROMPT — including the never-"Yes" token — plus appending a fallback in
		// wording other than arm D's, plus splitting the addressed branch into a paragraph of its own.
		// Two more are why the few-shot's record prefix is now one shared constant rather than a copy:
		// rewording DrugReferenceInjector.renderFinding's prefix out from under the demonstration, and
		// re-inlining a literal into the prompt and then rewording that prefix. All nine were run
		// against these assertions and every one fails.
		//   * The verdict's DIRECTION must come from the finding, never by deferring to the general
		//     yes/no rule: that rule's "start with Yes ONLY when a record explicitly names what is
		//     asked" is a PRESENCE criterion, and on a safety question a record naming the drug is
		//     evidence AGAINST it. Arm C of the A/B/C/D deferred and produced an inverted
		//     "Yes ... ivosidenib (Major...)" 5/6 for a patient on simvastatin.
		//   * Hence the never-"Yes" token, and it must stay a TOKEN rather than a quoted sentence
		//     template: arm B forbade a meta-lead by quoting it and the model then emitted that exact
		//     string 6/6 on a cell that never had it. The README draws that distinction explicitly.
		//   * The rule's precondition must stay "a safety finding names the drug asked about" with no
		//     otherwise-branch. Arm D's "otherwise state what the record shows" made drug-reference
		//     material fair game and broke the #107 abstention on 3 unconnected cells. Gated on a
		//     finding record existing, this rule is unreachable on those cells by construction.
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;
		assertTrue(prompt.contains("evidence against giving it"),
				"System prompt must state that a safety finding naming the asked-about drug is "
				+ "evidence AGAINST it, so the verdict's direction comes from the finding rather "
				+ "than from the presence-shaped yes/no rule");
		assertTrue(prompt.contains("belongs after the call, not in place of it"),
				"System prompt must say the finding's mechanism follows the verdict rather than "
				+ "replacing it — reciting the mechanism in the verdict slot IS the defect");
		assertTrue(prompt.contains("Never open such an answer with \"Yes\""),
				"The never-\"Yes\" token is the arm-C guard: on a safety question a record naming "
				+ "the drug is evidence AGAINST giving it, so a presence-style \"Yes\" inverts the "
				+ "clinical meaning. Without this token the addressed branch inherits the general "
				+ "rule's presence criterion, which is what produced arm C's inverted verdict 5/6");
		assertTrue(prompt.contains("When a safety finding DOES name the drug or intervention asked about"),
				"The addressed branch must stay gated on a SAFETY FINDING naming the asked-about "
				+ "drug. A finding record exists only when DrugSafetyValidator found something, so "
				+ "that antecedent is objectively false on an unconnected cell and the #107 "
				+ "abstention stays reachable by construction rather than by wording");
		assertFalse(prompt.contains("otherwise state what the record shows"),
				"Arm D's otherwise-branch made drug-reference material fair game on cells nothing "
				+ "bears on and broke the #107 abstention 3x; the rule keeps a single precondition");
		// Pinning arm D's exact wording is not enough — an otherwise-branch phrased any other way
		// evades it while reinstating the measured failure, and the eval gate provably cannot see the
		// fabricated verdict that produces (#126). So assert the property structurally, over the whole
		// safety/suitability paragraph: no fallback clause anywhere in it. Extracting the paragraph
		// also pins that the addressed branch stays INSIDE it — split into a paragraph of its own it
		// becomes a second, competing lead instruction, the shape that regressed before.
		int safetyRuleAt = prompt.indexOf("The same rules apply to safety and suitability questions");
		assertTrue(safetyRuleAt > 0, "the safety/suitability paragraph must still be present");
		// To the next newline, which is where the paragraph ends — so moving the addressed branch out
		// of it fails the next assertion rather than slipping through a wider window.
		int safetyRuleEnd = prompt.indexOf('\n', safetyRuleAt);
		assertTrue(safetyRuleEnd > safetyRuleAt,
				"the safety/suitability paragraph must still be newline-terminated, or the extraction "
				+ "below silently widens to the rest of the prompt");
		String safetyRule = prompt.substring(safetyRuleAt, safetyRuleEnd);
		assertTrue(safetyRule.contains("When a safety finding DOES name the drug"),
				"the addressed branch must live in the same paragraph as the unaddressed one it is "
				+ "the converse of, not in a paragraph of its own: " + safetyRule);
		assertFalse(safetyRule.toLowerCase().contains("otherwise"),
				"the safety/suitability paragraph must carry NO otherwise-branch in any wording. Arm "
				+ "D's fallback cost the most ABSTAIN of the 2026-07-30 A/B/C/D — 7/10 against a "
				+ "10/10 baseline, where arm B's quoted template cost 1 and arm C none — because a "
				+ "fallback makes every other record fair game on a cell nothing bears on. Both "
				+ "branches here are gated on a positive antecedent instead: " + safetyRule);
		assertTrue(prompt.contains("every record it rests on, cited"),
				"Leading with the verdict must not cost the complete-enumeration property: the "
				+ "records the finding rests on still have to reach the answer, cited");
		assertFalse(prompt.contains("open with what the finding says"),
				"The record-type sentence must no longer point the lead at the finding's own words: "
				+ "that instruction and a verdict-lead rule contradict each other, and it is the one "
				+ "the model obeyed 6/6");
		assertTrue(prompt.contains("answer the question from that finding and cite it"),
				"and it must be RE-POINTED, not merely deleted — #110's sentence is the only place "
				+ "that tells the model what to do with a safety finding, so dropping its lead "
				+ "clause would leave the addressed branch undemonstrated in the record-type "
				+ "paragraph while the verdict rule below it stands alone");
		assertTrue(prompt.contains("Is it safe to deliver durian?"),
				"The few-shot must DEMONSTRATE the addressed safety branch, not only describe it — "
				+ "in the same fake-fruit vocabulary as its neighbours");
		assertTrue(prompt.contains("[4] Safety finding — Durian:"),
				"The demonstration needs a record in the real, undated "
				+ "'Safety finding — <drug>: <detail>' shape DrugReferenceInjector.renderFinding "
				+ "appends, or it teaches a shape the model never sees");
		assertTrue(prompt.contains("[4] " + DrugReferenceInjector.FINDING_PREFIX + "Durian:"),
				"and that shape must be the PRODUCTION prefix rather than a copy of it. The two "
				+ "assertions cover opposite drift directions and BOTH pass only while the prompt "
				+ "and renderFinding agree: reword the constant and the literal one above fails; "
				+ "re-inline a literal into the prompt and then reword the constant, and this one "
				+ "fails. Two independent literals would let renderFinding be reworded while the "
				+ "few-shot kept demonstrating a shape the model never sees, with every test green");
		assertTrue(prompt.contains("\"answer\": \"No — durian should not be delivered:"),
				"The demonstrated answer must LEAD with the verdict; a lead that opens on the "
				+ "mechanism is the behaviour being fixed");
		assertTrue(prompt.contains("a Major problem [4].\", \"citations\": [2, 4]}"),
				"and it must carry the finding's own severity and cite BOTH the finding and the "
				+ "record it rests on. A demonstrated verdict with no citation behind it is exactly "
				+ "the fabricated-verdict shape the eval gate cannot see (#126), taught by example");
		// Both branches must stay reachable. #107's abstention is the direction that trades a
		// missing verdict for a fabricated one, which is the worse defect, so the addressed-case
		// demonstration must sit AFTER the mango abstention and BEFORE the focus-hint banana
		// abstention — it must not displace either.
		int mango = prompt.indexOf("Is it safe to deliver mangoes?");
		int durian = prompt.indexOf("Is it safe to deliver durian?");
		int focusHint = prompt.indexOf(LlmProvider.FOCUS_HINT_LABEL);
		assertTrue(mango > 0 && durian > mango && focusHint > durian,
				"Few-shot order must be mango abstention -> durian verdict -> focus-hint banana "
				+ "abstention, so neither abstention demonstration is displaced by the new one. "
				+ "mango=" + mango + " durian=" + durian + " focusHint=" + focusHint);
		assertTrue(prompt.contains("never \"Yes\" or \"No\""),
				"The unaddressed branch's #107 guard must survive verbatim");
	}

	@Test
	public void defaultSystemPrompt_shouldInstructAbstentionWhenNoRecordsRelevant() {
		// The few-shot demonstrates abstention in FOCUS mode (a "Records ranked by
		// similarity..." line followed by an empty-citations answer). On the non-focus path
		// (full-chart mode / usePreFilter=false, or any query whose focus list is empty) the
		// user message carries NO ranked line, so this explicit instruction is the sole
		// abstention guidance the model gets. A future prompt edit that drops it would
		// silently strip all "none found" behaviour from the non-focus path with no demo to
		// fall back on — pin it.
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("If no records are relevant"),
				"System prompt must keep the explicit abstention instruction for the non-focus "
				+ "path — the focus-mode few-shot does not cover it");
	}

	@Test
	public void defaultSystemPrompt_shouldDemonstrateAbstentionDespiteFocusHint() {
		// In querystore focus-hint mode, retrieval (searchByPatient + topK) has no
		// relevance gate — it always returns the K nearest records, so the user message
		// always carries a non-empty "Records ranked by similarity to the query: ..." line,
		// even when NOTHING in the chart is actually about the query. The few-shot must
		// demonstrate abstaining in exactly that situation, otherwise the model treats the
		// ranked list as proof of relevance and answers about off-topic records (the
		// "Is she in any programs?" -> lists conditions failure on the demo).
		String prompt = LlmProvider.DEFAULT_SYSTEM_PROMPT;
		int rankedAt = prompt.indexOf("Records ranked by similarity to the query:");
		assertTrue(rankedAt > 0,
				"System prompt few-shot must include a focus-hint line in the same "
				+ "'Records ranked by similarity to the query: ...' format the user message uses, "
				+ "so the demonstration matches the real prompt shape. Prompt:\n" + prompt);
		String afterRanked = prompt.substring(rankedAt);
		// The demo object leads with a "reasoning" field (chain-of-thought), so match the
		// abstaining answer + empty citations + closing brace rather than a literal "{...}" — the
		// intent is unchanged: the focus-hint line is immediately followed by an abstaining answer.
		assertTrue(afterRanked.contains(
				"\"answer\": \"There are no records of banana deliveries.\", \"citations\": []}"),
				"The focus-hint line must be immediately followed by the abstaining banana answer "
				+ "(empty citations) — not just any later empty-citations answer. This pins the "
				+ "demonstration order: a non-empty ranked list does NOT guarantee the listed "
				+ "records are about the query, so the correct response can be to cite nothing. A "
				+ "loose check would pass even if the abstention few-shot were reordered before the "
				+ "ranked line or its answer swapped. Prompt:\n" + prompt);
	}

	@Test
	public void extractResponse_shouldIgnoreLeadingReasoningField() {
		// The output schema puts a "reasoning" field first (chain-of-thought). The extractor must
		// read answer/citations and ignore reasoning — it is the model's scratchpad, never shown.
		String response = "{\"reasoning\": \"Hearing Loss is an ear-related condition, so [89] is "
				+ "relevant.\", \"answer\": \"Hearing Loss [89].\", \"citations\": [89]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Hearing Loss [89].", result.getAnswer());
		assertEquals(Arrays.asList(89), result.getCitations());
	}

	@Test
	public void defaultSystemPrompt_fewShotShouldDemonstrateReasoningField() {
		// The few-shot demo answers must match the schema shape (reasoning first), or the
		// demonstrated format contradicts the grammar the model is constrained to.
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("\"reasoning\":"),
				"Few-shot demo answers must include a \"reasoning\" field so the demonstrated "
				+ "format matches the response schema");
	}

	/** Feeds {@code json} to a fresh AnswerExtractingConsumer in the given chunk sizes and
	 *  returns what reached the client. chunkSize 0 means feed the whole string at once. */
	private static String streamThrough(String json, int chunkSize) {
		StringBuilder out = new StringBuilder();
		Consumer<String> delegate = out::append;
		LlmProvider.AnswerExtractingConsumer consumer = new LlmProvider.AnswerExtractingConsumer(delegate);
		if (chunkSize <= 0) {
			consumer.accept(json);
		} else {
			for (int i = 0; i < json.length(); i += chunkSize) {
				consumer.accept(json.substring(i, Math.min(i + chunkSize, json.length())));
			}
		}
		return out.toString();
	}

	/** Splits {@code json} into the (reasoning, answer) channels the streaming path uses: two
	 *  field-scanning consumers fed the same stream, exactly as LlmProvider.searchStreaming tees. */
	private static String[] streamSplit(String json, int chunkSize) {
		StringBuilder reasoning = new StringBuilder();
		StringBuilder answer = new StringBuilder();
		LlmProvider.AnswerExtractingConsumer r = new LlmProvider.AnswerExtractingConsumer("reasoning", reasoning::append);
		LlmProvider.AnswerExtractingConsumer a = new LlmProvider.AnswerExtractingConsumer("answer", answer::append);
		Consumer<String> tee = chunk -> { r.accept(chunk); a.accept(chunk); };
		if (chunkSize <= 0) {
			tee.accept(json);
		} else {
			for (int i = 0; i < json.length(); i += chunkSize) {
				tee.accept(json.substring(i, Math.min(i + chunkSize, json.length())));
			}
		}
		return new String[] { reasoning.toString(), answer.toString() };
	}

	@Test
	public void streamingConsumer_shouldSplitReasoningAndAnswerOntoSeparateChannels() {
		// The "thinking" feature: the reasoning channel must receive ONLY the reasoning value and
		// the answer channel ONLY the answer value — no cross-leak — even when the stream arrives
		// char-by-char. Reasoning contains a quoted span and [n] markers to stress the scanner.
		String json = "{\"reasoning\": \"The query is about ears; record [89] is 'Hearing Loss', an "
				+ "ear problem.\", \"answer\": \"Hearing Loss [89].\", \"citations\": [89]}";
		String expReasoning = "The query is about ears; record [89] is 'Hearing Loss', an ear problem.";
		for (int chunk : new int[] { 0, 1, 5 }) {
			String[] ra = streamSplit(json, chunk);
			assertEquals(expReasoning, ra[0], "reasoning channel must get the full reasoning value (chunk=" + chunk + ")");
			assertEquals("Hearing Loss [89].", ra[1], "answer channel must get only the answer (chunk=" + chunk + ")");
		}
	}

	@Test
	public void streamingConsumer_answerWordInReasoningMustNotLeakToAnswerChannel() {
		// Adversarial: the reasoning value itself contains the word "answer". The answer channel
		// must extract ONLY the real "answer" field — it must not latch onto the word inside the
		// reasoning value (it can't: once in the reasoning value the scanner forwards content
		// without re-matching keys, and only the quoted "answer": key triggers the answer channel).
		String json = "{\"reasoning\": \"To answer this I checked record [89] for ear conditions.\", "
				+ "\"answer\": \"Hearing Loss [89].\", \"citations\": [89]}";
		String[] ra = streamSplit(json, 1);
		assertEquals("To answer this I checked record [89] for ear conditions.", ra[0]);
		assertEquals("Hearing Loss [89].", ra[1],
				"the word 'answer' inside reasoning must not leak into or truncate the answer channel");
	}

	@Test
	public void streamingConsumer_shouldNotLeakLeadingReasoningField() {
		// The schema emits "reasoning" FIRST, before "answer". The streaming path (/search/stream)
		// must forward only the answer value to the clinician — never the model's reasoning
		// scratchpad. Exercised both as one chunk and char-by-char (real token streams arrive in
		// arbitrary fragments, so the state machine must skip reasoning across chunk boundaries).
		String json = "{\"reasoning\": \"Hearing Loss is an ear-related condition, so record [89] "
				+ "is relevant to the query about ears.\", \"answer\": \"Hearing Loss [89].\", "
				+ "\"citations\": [89]}";
		assertEquals("Hearing Loss [89].", streamThrough(json, 0),
				"whole-string: only the answer value must reach the client, not reasoning");
		assertEquals("Hearing Loss [89].", streamThrough(json, 1),
				"char-by-char: reasoning must be skipped across token boundaries too");
		assertEquals("Hearing Loss [89].", streamThrough(json, 7),
				"7-char chunks: reasoning must be skipped regardless of fragment alignment");
	}

	@Test
	public void streamingConsumer_shouldDecodeUnicodeEscapes() {
		// The non-streaming path decodes \\uXXXX via Jackson (see
		// extractResponse_shouldDecodeUnicodeEscapes); the streaming consumer must match, or a
		// streamed clinical value like "38.9°C" reaches the clinician as literal "38.9\\u00b0C".
		// The 4 hex digits must also decode when they straddle streaming-chunk boundaries.
		String json = "{\"reasoning\": \"unit conversion\", \"answer\": \"Temperature is "
				+ "38.9\\u00b0C\", \"citations\": [1]}";
		assertEquals("Temperature is 38.9°C", streamThrough(json, 0),
				"whole-string: streamed answer must decode \\uXXXX like the non-streaming path");
		assertEquals("Temperature is 38.9°C", streamThrough(json, 1),
				"char-by-char: \\uXXXX must decode even when its hex digits span chunk boundaries");
	}

	@Test
	public void streamingConsumer_shouldDecodeControlCharEscapes() {
		// JSON control-char escapes (\r, \b, \f) must decode like Jackson on the non-streaming
		// path — otherwise a streamed answer with one shows the literal backslash sequence.
		String json = "{\"reasoning\": \"x\", \"answer\": \"line1\\r\\nline2\\tend\", \"citations\": []}";
		assertEquals("line1\r\nline2\tend", streamThrough(json, 0));
		assertEquals("line1\r\nline2\tend", streamThrough(json, 1));
	}

	@Test
	public void streamingConsumer_reasoningMentioningAnswerWordShouldNotFalseTrigger() {
		// Adversarial: the reasoning text itself contains the escaped word \"answer\". An escaped
		// quote inside the reasoning value must NOT be mistaken for the real "answer" key, or the
		// client would see reasoning text. Only the genuine answer value may be forwarded.
		String json = "{\"reasoning\": \"The query literally says \\\"answer\\\" but I judge by "
				+ "meaning.\", \"answer\": \"No relevant records.\", \"citations\": []}";
		assertEquals("No relevant records.", streamThrough(json, 0));
		assertEquals("No relevant records.", streamThrough(json, 1));
	}

	@Test
	public void extractResponse_shouldParseJsonWithCitations() {
		String response = "{\"answer\": \"The patient has Hypertension [48] and Diabetes [49].\", \"citations\": [48, 49]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("The patient has Hypertension [48] and Diabetes [49].", result.getAnswer());
		assertEquals(Arrays.asList(48, 49), result.getCitations());
	}

	@Test
	public void extractResponse_shouldHandleEmptyCitations() {
		String response = "{\"answer\": \"There are no records about diabetes in this patient's chart.\", \"citations\": []}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("There are no records about diabetes in this patient's chart.", result.getAnswer());
		assertTrue(result.getCitations().isEmpty());
	}

	@Test
	public void extractResponse_shouldHandleEscapedQuotes() {
		String response = "{\"answer\": \"The patient said \\\"I feel fine\\\" during the visit.\", \"citations\": []}";
		assertEquals("The patient said \"I feel fine\" during the visit.",
				LlmProvider.extractResponse(response).getAnswer());
	}

	@Test
	public void extractResponse_shouldHandleEscapedBackslashes() {
		String response = "{\"answer\": \"Path: C:\\\\Users\\\\data\", \"citations\": []}";
		assertEquals("Path: C:\\Users\\data",
				LlmProvider.extractResponse(response).getAnswer());
	}

	@Test
	public void extractResponse_shouldHandleEscapedNewlines() {
		String response = "{\"answer\": \"Line 1\\nLine 2\", \"citations\": []}";
		assertEquals("Line 1\nLine 2",
				LlmProvider.extractResponse(response).getAnswer());
	}

	@Test
	public void extractResponse_shouldHandleEmptyString() {
		LlmProvider.LlmResponse result = LlmProvider.extractResponse("");
		assertEquals("", result.getAnswer());
		assertTrue(result.getCitations().isEmpty());
	}

	@Test
	public void extractResponse_shouldFallBackToRawResponseWhenNotJson() {
		String response = "The patient has Diabetes [1].";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals(response, result.getAnswer());
		assertTrue(result.getCitations().isEmpty());
	}

	@Test
	public void extractResponse_shouldDecodeUnicodeEscapes() {
		String response = "{\"answer\": \"Temperature is 38.9\\u00b0C\", \"citations\": [1]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Temperature is 38.9\u00b0C", result.getAnswer());
		assertEquals(Arrays.asList(1), result.getCitations());
	}

	@Test
	public void extractResponse_shouldHandleWhitespaceInJson() {
		String response = "{ \"answer\" : \"There are no records about diabetes in this patient's chart.\" , \"citations\" : [] }";
		assertEquals("There are no records about diabetes in this patient's chart.",
				LlmProvider.extractResponse(response).getAnswer());
	}

	@Test
	public void extractResponse_shouldHandleMissingCitationsField() {
		String response = "{\"answer\": \"Some answer.\"}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Some answer.", result.getAnswer());
		assertTrue(result.getCitations().isEmpty());
	}

	@Test
	public void normalizeSlashCitations_shouldConvertSlashesToSeparateBrackets() {
		assertEquals("Tuberculosis [1], [2] and Malaria [3], [4]",
				LlmProvider.normalizeSlashCitations("Tuberculosis [1/2] and Malaria [3/4]"));
	}

	@Test
	public void normalizeSlashCitations_shouldHandleTripleSlash() {
		assertEquals("Infections [5], [12], [15]",
				LlmProvider.normalizeSlashCitations("Infections [5/12/15]"));
	}

	@Test
	public void normalizeSlashCitations_shouldLeaveNormalCitationsUnchanged() {
		assertEquals("Has condition [1], [2].",
				LlmProvider.normalizeSlashCitations("Has condition [1], [2]."));
	}

	@Test
	public void normalizeSlashCitations_shouldLeaveTextWithNoCitationsUnchanged() {
		assertEquals("No citations here.",
				LlmProvider.normalizeSlashCitations("No citations here."));
	}

	@Test
	public void extractResponse_shouldNormalizeSlashCitationsInAnswer() {
		String response = "{\"answer\": \"Infections [7/13] and HIV [4/11].\", \"citations\": [7, 13, 4, 11]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Infections [7], [13] and HIV [4], [11].", result.getAnswer());
		assertEquals(Arrays.asList(7, 13, 4, 11), result.getCitations());
	}

	@Test
	public void extractResponse_shouldNormalizeCorroboratedCommaCitationsInAnswer() {
		// The compact comma form is the measured failure shape (rc.2 standalone, 2026-07-21,
		// bc4ba445|heart): "[6, 7]" was unrecognized inline, so the #76 guard dropped every
		// reference from a fully-cited answer. Groups corroborated by the structured citations
		// array are rewritten exactly like slash shorthand; the array stays the authority.
		String response = "{\"answer\": \"Circulation [6, 7] and cardiomyopathy [11,12].\", "
				+ "\"citations\": [6, 7, 11, 12]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Circulation [6], [7] and cardiomyopathy [11], [12].", result.getAnswer());
		assertEquals(Arrays.asList(6, 7, 11, 12), result.getCitations());
	}

	@Test
	public void extractResponse_shouldSurviveOverlongDigitRunsInTruncatedCitationsArray() {
		// The truncation fallback exists to salvage answers from responses cut off by the
		// output-token cap — including degenerate digit runs the citations schema's
		// "type":"integer" does not bound. An unguarded Integer.parseInt turned exactly the
		// responses the fallback exists for into HTTP 500s.
		String truncated = "{\"reasoning\":\"r\",\"answer\":\"Condition [1].\", \"citations\": [1, 22222222222";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(truncated);
		assertEquals("Condition [1].", result.getAnswer());
		assertTrue(result.getCitations().contains(1),
				"salvageable in-range citation must survive: " + result.getCitations());
	}

	@Test
	public void extractResponse_shouldLeaveUncorroboratedCommaBracketsIntact() {
		// A numeric comma bracket the model did NOT list in its citations array is a clinical
		// value, not citation shorthand — splitting it would fabricate references (a
		// "[120, 80]" reading resolving to records 120 and 80 on a large chart). Mirrors the
		// slash rule for "[120/80]".
		String response = "{\"answer\": \"Readings were [120, 80] throughout.\", \"citations\": [3]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Readings were [120, 80] throughout.", result.getAnswer());
		assertEquals(Arrays.asList(3), result.getCitations());
	}

	@Test
	public void extractResponse_shouldNormalizeMixedSlashAndCommaShorthand() {
		String response = "{\"answer\": \"TB [1/2] and anemia [5, 6].\", \"citations\": [1, 2, 5, 6]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("TB [1], [2] and anemia [5], [6].", result.getAnswer());
	}

	@Test
	public void extractResponse_shouldPreserveClinicalSlashTerms() {
		String response = "{\"answer\": \"The patient has HIV/AIDS [1] and nausea/vomiting [2].\", \"citations\": [1, 2]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("The patient has HIV/AIDS [1] and nausea/vomiting [2].", result.getAnswer());
	}

	@Test
	public void extractResponse_shouldNotSplitBracketedValueAbsentFromCitations() {
		// A blood-pressure value the model bracketed as [120/80] must NOT be rewritten into
		// citation markers [120], [80] — 120 and 80 are not in the citations array, so they
		// are a slash-separated clinical value, not citation shorthand.
		String response = "{\"answer\": \"Blood pressure was [120/80] mmHg [1].\", \"citations\": [1]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Blood pressure was [120/80] mmHg [1].", result.getAnswer());
		assertEquals(Arrays.asList(1), result.getCitations());
	}

	@Test
	public void extractResponse_shouldNotSplitSlashGroupWhenAnyPartAbsentFromCitations() {
		// [5/120]: 5 is a cited record but 120 is not. Splitting would emit a half-correct
		// [5], [120]; the whole group must be left untouched unless EVERY part is cited.
		String response = "{\"answer\": \"Reading [5/120] noted [5].\", \"citations\": [5]}";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(response);
		assertEquals("Reading [5/120] noted [5].", result.getAnswer());
	}

	@Test
	public void normalizeSlashCitations_withCitations_shouldSplitOnlyWhollyCitedGroups() {
		// [1/2] -> both cited -> split; [120/80] -> neither cited -> preserved as a value.
		assertEquals("BP [120/80] and conditions [1], [2]",
				LlmProvider.normalizeSlashCitations("BP [120/80] and conditions [1/2]", Arrays.asList(1, 2)));
	}

	@Test
	public void extractResponse_fallbackPath_shouldValidateSlashGroupsAgainstCitations() {
		// Missing closing brace forces the regex-fallback path (Jackson rejects it). Even there,
		// a slash value [120/80] must be preserved while a genuinely cited group [1/2] still splits.
		String malformed = "{\"answer\": \"BP [120/80] and conditions [1/2]\", \"citations\": [1, 2]";
		LlmProvider.LlmResponse result = LlmProvider.extractResponse(malformed);
		assertEquals("BP [120/80] and conditions [1], [2]", result.getAnswer());
		assertEquals(Arrays.asList(1, 2), result.getCitations());
	}

	@Test
	public void extractResponse_shouldNotStackOverflowOnLongTruncatedJson() {
		// Reproduces the production failure: an LLM answer that hits max_tokens mid-string
		// produces a long JSON missing its closing quote and brace, sending Jackson into the
		// regex fallback path. The recursive (?:|)* alternation in ANSWER_VALUE used to blow
		// the JVM stack on inputs above ~30KB, returning HTTP 500.
		StringBuilder sb = new StringBuilder("{\"answer\": \"");
		for (int i = 0; i < 4000; i++) {
			sb.append("On 2026-01-").append(i % 28 + 1)
					.append(" the patient had encounter [").append(i).append("]. ");
		}
		// no closing quote, no closing brace — this is what we get when the model is cut off
		String truncated = sb.toString();
		assertTrue(truncated.length() > 30000,
				"need a long input to trigger the original stack overflow");

		LlmProvider.LlmResponse result = LlmProvider.extractResponse(truncated);
		assertTrue(result.getAnswer().startsWith("On 2026-01-1 the patient had encounter [0]"),
				"should recover the answer prefix from a truncated JSON, got: "
						+ result.getAnswer().substring(0, Math.min(80, result.getAnswer().length())));
	}

	// ---- batch entailment verdict parsing (Tier-2 grounding, one call for many citations) ----

	@Test
	public void parseBatchVerdicts_readsYesNoArrayInOrder() {
		assertEquals(Arrays.asList(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE),
				LlmProvider.parseBatchVerdicts("{\"verdicts\": [\"YES\", \"NO\", \"YES\"]}", 3));
	}

	@Test
	public void parseBatchVerdicts_nullOrBlankYieldsEmptyList() {
		assertTrue(LlmProvider.parseBatchVerdicts(null, 3).isEmpty());
		assertTrue(LlmProvider.parseBatchVerdicts("   ", 3).isEmpty());
	}

	@Test
	public void parseBatchVerdicts_envelopeFreeReplyDegradesToEmpty() {
		// A reply that is not the {"verdicts":[...]} envelope (e.g. an engine that ignored the
		// custom schema and used chart_answer) must yield an empty list so the caller falls back to
		// the Tier-1 verdict instead of misreading a verdict from the wrong field.
		assertTrue(LlmProvider.parseBatchVerdicts(
				"{\"reasoning\": \"x\", \"answer\": \"YES\", \"citations\": []}", 1).isEmpty());
		assertTrue(LlmProvider.parseBatchVerdicts("not json at all", 1).isEmpty());
	}

	@Test
	public void parseBatchVerdicts_unrecognisedElementBecomesNull() {
		// Defensive: an element that is neither YES nor NO is undecidable (null), not a guess —
		// grounding then keeps the Tier-1 verdict for that citation.
		java.util.List<Boolean> v = LlmProvider.parseBatchVerdicts("{\"verdicts\": [\"YES\", \"MAYBE\"]}", 2);
		assertEquals(2, v.size());
		assertEquals(Boolean.TRUE, v.get(0));
		assertNull(v.get(1));
	}

	/** Minimal {@link LlmEngine} that echoes a canned response body, so {@code entailsBatch}'s
	 *  numbering / blank-skip / position-mapping / parse wiring can be exercised without a real
	 *  model or an OpenMRS context. */
	private static class StubEngine implements LlmEngine {

		private final String content;

		StubEngine(String content) {
			this.content = content;
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds) {
			return new InferenceResult(content, 0, 0);
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds,
				ObjectNode responseFormat) {
			return new InferenceResult(content, 0, 0);
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage, int timeoutSeconds,
				Consumer<String> tokenConsumer) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void warmup(String systemPrompt, String userMessage, int timeoutSeconds) {
		}

		@Override
		public void close() {
		}

		@Override
		public void shutdown() {
		}
	}

	@Test
	public void entailsBatch_mapsVerdictsToInputPositionsAndSkipsBlankPairs() {
		// entailsBatch numbers ONLY the checkable pairs (1..k) in the prompt, then maps the model's
		// k verdicts back to the ORIGINAL input positions. A blank pair stays null and must not
		// shift the others — an off-by-one here would assign one citation's verdict to another (a
		// silent grounding error). Stub the engine to echo a fixed verdicts array.
		LlmProvider provider = new LlmProvider() {

			@Override
			LlmEngine getActiveEngine() {
				return new StubEngine("{\"verdicts\": [\"NO\", \"YES\"]}");
			}

			@Override
			protected int getTimeoutSeconds() {
				return 30;
			}
		};
		// Middle pair has a blank source -> only positions 0 and 2 are checked -> verdicts [NO, YES].
		List<Boolean> out = provider.entailsBatch(
				Arrays.asList("record A", "   ", "record C"),
				Arrays.asList("claim A", "claim B", "claim C"));
		assertEquals(3, out.size());
		assertEquals(Boolean.FALSE, out.get(0), "first checkable pair -> NO");
		assertNull(out.get(1), "blank pair -> not checked -> null verdict");
		assertEquals(Boolean.TRUE, out.get(2), "second checkable pair -> YES (not shifted by the blank)");
	}

	@Test
	public void entailsBatch_rejectsMismatchedInputLengths() {
		assertThrows(IllegalArgumentException.class,
				() -> new LlmProvider().entailsBatch(Arrays.asList("a"), Arrays.asList("x", "y")));
	}

	@Test
	public void entailsBatch_makesNoEngineCallWhenNoPairIsCheckable() {
		// Every pair blank -> returns all-null WITHOUT touching the engine (getActiveEngine throws
		// if called), so a degenerate answer never issues a wasted LLM round-trip.
		LlmProvider provider = new LlmProvider() {

			@Override
			LlmEngine getActiveEngine() {
				throw new AssertionError("entailsBatch must not call the engine when nothing is checkable");
			}
		};
		List<Boolean> out = provider.entailsBatch(Arrays.asList("", "  "), Arrays.asList("", "x"));
		assertEquals(2, out.size());
		assertNull(out.get(0));
		assertNull(out.get(1));
	}

	// ---- query-path KV-cache scoping (TTFT: restore a patient's prefilled chart from disk) ----

	/**
	 * Captures the engine arguments of one {@code inferStreaming} call so the provider's
	 * KV-scope plumbing can be asserted without a live llama-server.
	 */
	private static final class CapturingEngine implements LlmEngine {

		String capturedSystem;

		String capturedUserMessage;

		String capturedScope;

		String capturedSeed;

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage,
				int timeoutSeconds, Consumer<String> tokenConsumer, String cacheScope, String cacheSeed) {
			this.capturedSystem = systemPrompt;
			this.capturedUserMessage = userMessage;
			this.capturedScope = cacheScope;
			this.capturedSeed = cacheSeed;
			return new InferenceResult("{\"reasoning\": \"r\", \"answer\": \"a\", \"citations\": []}", 1, 1, 0);
		}

		@Override
		public InferenceResult infer(String s, String u, int t) {
			throw new AssertionError("streaming test must not call infer");
		}

		@Override
		public InferenceResult inferStreaming(String s, String u, int t, Consumer<String> c) {
			throw new AssertionError("the scope-aware 6-arg overload must be used so KV scoping reaches the engine");
		}

		@Override
		public void warmup(String s, String u, int t) {
		}

		@Override
		public void close() {
		}

		@Override
		public void shutdown() {
		}
	}

	private static LlmProvider providerWith(final CapturingEngine engine) {
		return new LlmProvider() {

			@Override
			LlmEngine getActiveEngine() {
				return engine;
			}

			@Override
			protected String getSystemPrompt() {
				return "SYS";
			}

			@Override
			protected int getTimeoutSeconds() {
				return 30;
			}
		};
	}

	@Test
	public void searchStreaming_scopeAware_forwardsScopeAndQuestionIndependentSeedToEngine() {
		CapturingEngine engine = new CapturingEngine();
		LlmProvider provider = providerWith(engine);
		String records = "1. [2024-01-01] BP 120/80\n2. [2024-02-02] HbA1c 7.1%";
		List<Integer> focus = Arrays.asList(1, 2);

		provider.searchStreaming(records, focus, "Is the patient diabetic?",
				tok -> { }, reason -> { }, "patient-uuid-42");

		assertEquals("patient-uuid-42", engine.capturedScope,
				"the patient UUID must reach the engine as the KV cache scope so the query path can "
				+ "restore/persist this patient's prefilled chart");
		assertEquals(LlmProvider.buildUserMessage(records, focus, "Is the patient diabetic?"),
				engine.capturedUserMessage,
				"the engine must still receive the full focus-hinted question prompt");
		// The KV filename seed MUST be the question-independent prefix — identical bytes to what
		// warmup() sends — or a warmup-saved file would hash to a different name and never be found
		// by the query. This is the core key-match invariant the whole feature relies on.
		assertEquals(LlmProvider.buildUserMessage(records, ""), engine.capturedSeed,
				"the query-path KV seed must equal the warmup user message (question-independent), "
				+ "so warmup-saved and query-saved entries share one filename per patient+chart");
	}

	@Test
	public void searchStreaming_scopeAware_nullScopeSendsNoSeed_soUnstablePipelinesNeverPersistKv() {
		// When the caller passes a null scope (the pipeline mode makes the chart prefix
		// question-dependent), the engine must receive a null seed and therefore do no disk KV ops.
		CapturingEngine engine = new CapturingEngine();
		LlmProvider provider = providerWith(engine);

		provider.searchStreaming("1. x", Arrays.<Integer>asList(), "q",
				tok -> { }, reason -> { }, null);

		assertNull(engine.capturedScope, "a null scope must pass through unchanged");
		assertNull(engine.capturedSeed,
				"with no scope there is no patient to key on, so the seed must be null and the engine "
				+ "must skip all disk KV restore/save");
	}

}
