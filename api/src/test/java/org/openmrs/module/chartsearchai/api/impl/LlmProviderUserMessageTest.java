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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/**
 * TWO INDEPENDENT PROPERTIES of the user message, and the nested instruction file points here for
 * the second one.
 *
 * <p>The warmup contract: the user-message prefix sent during {@code warmup}
 * MUST be a byte-prefix of the user-message sent during a real query on the same
 * chart, otherwise llama-server's KV-cache prefix match breaks and the warmup
 * is wasted work.
 *
 * <p>And the #397 one-line-per-finding clause — its exact bytes, the position after the question
 * and the SPACE that separates it from one, each measured and none of them derivable from the
 * other property. {@code api/src/main/java/org/openmrs/module/chartsearchai/reference/CLAUDE.md}'s
 * arrow lands here for <em>"its words are pinned as a literal"</em>, so this class is where that
 * pin has to be findable.
 *
 * <p>Both production paths (search, searchStreaming, warmup) call
 * {@link LlmProvider#buildUserMessage} — testing through that helper exercises
 * the same code production uses, per the project's "no reimplementation in tests"
 * rule.
 */
public class LlmProviderUserMessageTest {

	private static final String CHART = "[1] (2025-01-15) Clinical observation: BP 120/80\n"
			+ "[2] (2025-01-10) Diagnosis: Hypertension";

	@Test
	public void warmupUserMessageShouldBePrefixOfRealQuery() {
		String warmup = LlmProvider.buildUserMessage(CHART, "");
		String realQuery = LlmProvider.buildUserMessage(CHART, "What is the patient's BP?");

		assertTrue(realQuery.startsWith(warmup),
				"warmup must produce a byte-prefix of the real query so llama-server's "
				+ "exact-prefix cache match (via cache_prompt=true; --cache-reuse is now "
				+ "pinned to 0 to avoid the KV-shifting argmax flip) can reuse the cached "
				+ "prefix tokens. If this ever fails, the warmup is silently wasted: the "
				+ "prefix diverges before the question and llama-server reprocesses the "
				+ "whole chart from scratch.\n"
				+ "  warmup:    " + warmup + "\n"
				+ "  realQuery: " + realQuery);
	}

	@Test
	public void buildUserMessageShouldVaryByQuestion() {
		String a = LlmProvider.buildUserMessage(CHART, "What is the BP?");
		String b = LlmProvider.buildUserMessage(CHART, "Any allergies?");
		assertNotEquals(a, b,
				"sanity check: changing the question must change the user message, "
				+ "otherwise the prefix-match assertion above would pass trivially");
	}

	@Test
	public void warmupUserMessageShouldEndWithEmptyQueryMarker() {
		String warmup = LlmProvider.buildUserMessage(CHART, "");
		// The trailing "Clinician's query: " is what llama-server caches up to;
		// when the real query arrives, only the question text after it is new.
		assertTrue(warmup.endsWith("Clinician's query: "),
				"warmup must end with the 'Clinician's query: ' marker so the cache "
				+ "reuses everything up to that point and only the question text is new "
				+ "tokens to process. Got: '" + warmup + "'");
	}

	@Test
	public void buildUserMessageShouldIncludeRecordsHeader() {
		String msg = LlmProvider.buildUserMessage(CHART, "Q?");
		assertTrue(msg.startsWith("Patient records (most recent first):\n"),
				"the records-header is part of the cached prefix; if it changes, every "
				+ "previously-cached patient's KV cache is silently invalidated");
	}

	@Test
	public void buildUserMessageShouldHandleEmptyChart() {
		// When the embedding pre-filter narrows to no records, normalizeRecords
		// substitutes a placeholder. Both warmup and search must agree on it,
		// otherwise switching pre-filter on/off mid-session would break caching.
		String warmupEmpty = LlmProvider.buildUserMessage("", "");
		String realEmpty = LlmProvider.buildUserMessage("", "Q?");
		assertTrue(realEmpty.startsWith(warmupEmpty),
				"empty-chart prefix must still match between warmup and real query");
	}

	// ---------- the #397 finding-enumeration clause ----------

	/**
	 * Issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a>: a
	 * real query carries the one-line-per-finding clause; a warmup does not.
	 *
	 * <p><b>Why it is here and not in {@code DEFAULT_SYSTEM_PROMPT}, which is where it was tried
	 * first.</b> POSITION is the variable, measured on one build with both arms served through
	 * {@code chartsearchai.llm.systemPrompt} so they differed in exactly this sentence, over 14
	 * safety cells on one patient with eight active orders: ahead of the records the same sentence
	 * made completeness WORSE and cost more output, and here, after the question, it improved
	 * completeness and made answers shorter. The five-arm ledger is
	 * {@code eval/drift-metric/README.md}'s and its figures are deliberately not restated here —
	 * they were, and a third copy of a measured table is the copy nobody re-measures. ADR
	 * Decision 84 carries the decision; {@code eval/drift-metric/score_probe_safety.py}'s
	 * completeness cell is what reads it.
	 *
	 * <p><b>What the blank-question guard is for, corrected.</b> An earlier version of this javadoc
	 * called it the KV-cache prefix contract and named the two warmup cases at the top of this class
	 * as reddening without it. Both claims are false and the mutation is what showed it: replacing
	 * the whole condition with {@code if (enumerateFindings)} reddens
	 * {@code warmupShouldNotCarryTheFindingEnumerationClause} and
	 * {@code theClauseMustNotBreakTheWarmupPrefixForAQuestionOfAnyLength}, and leaves those two
	 * green — they compare two clause-free messages through the 3-arg arity, which hardcodes the
	 * flag false, so no change to this condition can move them. What protects the prefix is the
	 * APPEND POSITION; the guard prevents nothing production can reach, {@code warmup} and
	 * {@code cacheSeed} both building through the arity that hardcodes the flag false, and what it
	 * buys is that a future widening of the seed path cannot carry the clause without reddening the
	 * two cases the mutation reddened — both of which call the 4-arg arity directly, which no
	 * production seed path does. {@code LlmProvider.buildUserMessage}'s own comment and ADR
	 * Decision 84 carry that correction too.
	 */
	@Test
	public void realQueryShouldCarryTheFindingEnumerationClause() {
		String msg = LlmProvider.buildUserMessage(CHART, Collections.<Integer>emptyList(),
				"should i give Amlodipine?", true);
		assertTrue(msg.contains("put every one of them on a line of its own"),
				"a real query must carry the one-line-per-finding clause: a running paragraph is what "
				+ "the model writes without it, and 8 of 12 measured cells then stated fewer findings "
				+ "than the prompt carried. Got: " + msg);
		assertTrue(msg.contains("each with the severity that finding states"),
				"and the severity half, without which the measured arms stated every finding and "
				+ "dropped every rating — issue #337's property traded for this one");
		assertTrue(msg.indexOf("Clinician's query: ") < msg.indexOf("put every one of them"),
				"and it must come AFTER the question, which is the position that was measured: ahead "
				+ "of the records, in the system prompt, the same sentence made completeness worse");
		// THE SEPARATOR, pinned because it is what the first shipped build got wrong. With a newline
		// the clause is a standalone imperative line, reads as the dominant instruction and cost a
		// verdict lead on the measured corpus; run on from the question it did not. The rows are ADR
		// Decision 84's and eval/drift-metric/README.md's, not restated here. Change the space to a
		// newline and this line reddens.
		assertTrue(msg.contains("? Where more than one finding names it"),
				"the clause must run on from the question with a SPACE, not start a line of its own: "
				+ "a line of its own cost a verdict lead on the measured corpus. Got: " + msg);
		// THE PROHIBITION, pinned because `reference/CLAUDE.md` says "completeness is never bought by
		// rewording it" and ADR Decision 84 claimed this class was where `nothing else` failed — it
		// was not: appending ", and nothing else" left the whole build green. What that wording was
		// measured to cost is the next member's javadoc; same shape as LlmProviderTest's `otherwise`
		// assertion over the safety paragraph, and for the same reason.
		assertFalse(msg.toLowerCase().contains("nothing else"),
				"the clause must not tell the answer to carry NOTHING ELSE on those lines: measured, "
				+ "that wording paid for completeness and ratings with the verdict lead, which is the "
				+ "trade issue #397 forbids. Got: " + msg);
	}

	/**
	 * THE EXACT BYTES, which is the only assertion in this class that an ADDED imperative cannot
	 * pass. The substring cases above hold what the clause must SAY and one thing it must not; they
	 * are all satisfiable by a longer clause, and a longer clause in this exact position is the
	 * measured hazard rather than a hypothetical one — ADR Decision 84 records that of seven probed
	 * wordings the one appending {@code ", and nothing else"} took completeness and the ratings and
	 * lost the verdict lead, opening {@code 1. Solu-Medrol 125mg/5ml — Moderate [349]} with no call
	 * in front of it. Measured here too: appending {@code " State your verdict first."} to the
	 * production literal left every other case in this class green.
	 *
	 * <p>Asserted as the DIFFERENCE between the two flag values rather than over the whole message,
	 * so the case says what it is about and does not have to restate the records header, the focus
	 * block or the query marker. The flag-false message is a prefix of the flag-true one by
	 * construction — the clause is the last thing appended — and that is asserted first, because a
	 * clause moved ahead of the query marker would otherwise reach {@code substring} rather than an
	 * assertion.
	 *
	 * <p>The project's idiom for prompt-facing text whose wording was measured, and this clause is
	 * now held to it as its three neighbours are:
	 * {@code DrugClassQuestionNoteTest.theRenderedNoteIsExactlyTheseWords},
	 * {@code SafetyVerdictSeverityGradationTest.theTwoCurrentMedicationBranchesAreExactlyTheseWords}
	 * and {@code AbsentDataEvalTest.theEmptyChartPromptAsksTheModelToNameWhatIsMissing}.
	 */
	@Test
	public void theAppendedClauseIsExactlyTheseBytes() {
		String withClause = LlmProvider.buildUserMessage(CHART, Collections.<Integer>emptyList(),
				"should i give Amlodipine?", true);
		String withoutClause = LlmProvider.buildUserMessage(CHART, Collections.<Integer>emptyList(),
				"should i give Amlodipine?", false);
		assertTrue(withClause.startsWith(withoutClause),
				"the clause is APPENDED, so the flag-false message must be a byte-prefix of the "
				+ "flag-true one. If this fails the clause has moved out of the tail and the "
				+ "warmup-prefix contract is what to look at next.\n  without: " + withoutClause
				+ "\n  with:    " + withClause);
		assertEquals(" Where more than one finding names it, put every one of them on a line of its "
				+ "own, each with the severity that finding states.",
				withClause.substring(withoutClause.length()),
				"these are the measured bytes of the #397 clause, down to the SPACE in front of "
				+ "them. Every substring case in this class is satisfied by a longer clause, and an "
				+ "added imperative here is measured to cost the verdict lead on a safety cell — the "
				+ "trade issue #397 forbids. If you are changing the wording deliberately, the "
				+ "measured ledger in ADR Decision 84 stops describing the shipped bytes, so measure "
				+ "the new one and update it.");
	}

	@Test
	public void theSummariseClauseIsExactlyTheseBytes() {
		// ISSUE #403. The counterpart of theAppendedClauseIsExactlyTheseBytes, and held as bytes for
		// the same reason: this clause sits in the position ADR Decision 84 measured instruction to
		// regress in, so a rewording is a new arm and not a tidy-up. Reached through the resolver so
		// the enum value production selects is the one measured, rather than one a test names.
		LlmProvider summarising = new LlmProvider() {
			@Override
			protected FindingProse findingProse(boolean enumerateFindings) {
				return enumerateFindings ? FindingProse.SUMMARISED : FindingProse.UNPROMPTED;
			}
		};
		String withClause = LlmProvider.buildUserMessage(CHART, Collections.<Integer>emptyList(),
				"should i give Amlodipine?", summarising.findingProse(true));
		String withoutClause = LlmProvider.buildUserMessage(CHART, Collections.<Integer>emptyList(),
				"should i give Amlodipine?", summarising.findingProse(false));

		assertTrue(withClause.startsWith(withoutClause),
				"this clause is APPENDED too, so the no-clause message must be a byte-prefix of it.\n"
				+ "  without: " + withoutClause + "\n  with:    " + withClause);
		assertEquals(" The clinician is shown every finding in full beside your answer, so summarise "
				+ "rather than list them, citing each finding you rely on and stating its severity.",
				withClause.substring(withoutClause.length()),
				"these are the measured bytes of the #403 clause, down to the SPACE in front of "
				+ "them. If you are changing the wording, the arm measured on the rig stops "
				+ "describing the shipped bytes — measure the new one.");
	}

	@Test
	public void theSummariseClauseNeverAsksForTheEnumerationItReplaces() {
		// The two asks are opposites, so a message carrying both would be the contradiction
		// FindingProse exists to make unrepresentable. Asserted over the SHIPPED substring of the
		// #397 clause rather than over the enum, so a future third state that reintroduces the
		// enumeration ask alongside this one is caught here and not only by a type.
		String summarised = LlmProvider.buildUserMessage(CHART, Collections.<Integer>emptyList(),
				"should i give Amlodipine?", LlmProvider.FindingProse.SUMMARISED);

		assertFalse(summarised.contains("put every one of them on a line of its own"),
				"the summarise mode must not also ask for one line per finding, was: " + summarised);
		assertTrue(summarised.contains("summarise rather than list them"),
				"and it must carry its own ask, was: " + summarised);
	}

	@Test
	public void aStockInstallSUMMARISESWhereTheGateFiresAndStaysSilentWhereItDoesNot() {
		// #403 ships ON since the fourteen-cell measurement, so a stock install must SUMMARISE where
		// the #397 gate fires — this is the assertion that fails if the default is flipped back
		// without the ledger in the README and ADR moving with it. A real LlmProvider is used
		// deliberately: getBooleanGlobalProperty fails safe to the constant with no OpenMRS context,
		// which is exactly a stock install's answer.
		LlmProvider stock = new LlmProvider();

		assertEquals(LlmProvider.FindingProse.SUMMARISED, stock.findingProse(true),
				"a stock install must SUMMARISE when the #397 gate fires");
		assertEquals(LlmProvider.FindingProse.UNPROMPTED, stock.findingProse(false),
				"and must still append nothing when the gate does not fire — the enumeration gate is "
				+ "untouched by #403, which decides only WHICH clause, never WHETHER one is sent");
	}

	@Test
	public void warmupShouldNotCarryTheFindingEnumerationClause() {
		String warmup = LlmProvider.buildUserMessage(CHART, Collections.<Integer>emptyList(), "", true);
		assertFalse(warmup.contains("put every one of them on a line of its own"),
				"a warmup must NOT carry the clause. It is sent with question=\"\" precisely to "
				+ "produce a byte-prefix of every real query, and a clause appended after an empty "
				+ "question sits where the question's own bytes go — so the seed stops being a prefix "
				+ "and every warmed patient reprocesses the whole chart. Got: " + warmup);
	}

	@Test
	public void theClauseMustNotBreakTheWarmupPrefixForAQuestionOfAnyLength() {
		// The two warmup cases above use one question. This asks the property of the SHORTEST
		// non-blank question there is, which is where an off-by-one in the guard would show: a
		// guard reading `question.isEmpty()` rather than trimming would let a whitespace-only
		// question take the clause, and `" "` is a real value — normalizeRecords already treats
		// blank and whitespace alike one field over.
		String warmup = LlmProvider.buildUserMessage(CHART, "");
		for (String question : new String[] { "?", "a", "  ", "\t" }) {
			String real = LlmProvider.buildUserMessage(CHART, Collections.<Integer>emptyList(),
					question, true);
			assertTrue(real.startsWith(warmup),
					"warmup must stay a byte-prefix for question " + Arrays.toString(question.toCharArray())
					+ "; got real=" + real);
		}
		assertFalse(LlmProvider.buildUserMessage(CHART, Collections.<Integer>emptyList(), "   ", true)
						.contains("put every one of them"),
				"a whitespace-only question is blank, so it takes no clause — otherwise the prefix "
				+ "property above holds only by accident of where the clause lands");
	}

	@Test
	public void aChartWithFewerThanTwoFindingsCarriesNoClause() {
		// The other half of the gate, and the half a caller can get wrong: `enumerateFindings` false
		// must leave the message byte-identical to what it was before #397, because that is what the
		// empty-chart and single-finding prompts still send.
		//
		// NOT compared against the 3-arg form. An earlier version did, with a comment claiming that
		// was the stronger check — it is the opposite: the 3-arg body IS
		// `buildUserMessage(records, focusIndices, question, false)`, so the comparison was
		// `f(x) == f(x)` and passed under the very mutation it was written for (ungating the clause
		// takes both sides together, measured). What is not vacuous is where the message ENDS: with
		// the flag false the question's own bytes are the last thing in it, which is the property
		// AbsentDataEvalTest pins to exact bytes one arity over.
		assertTrue(LlmProvider.buildUserMessage(CHART, Collections.<Integer>emptyList(), "Q?", false)
						.endsWith("Clinician's query: Q?"),
				"a chart the caller says carries fewer than two findings must send a message that "
				+ "ends at the question, with nothing appended after it");
		assertFalse(LlmProvider.buildUserMessage(CHART, Collections.<Integer>emptyList(), "Q?", false)
						.contains("put every one of them"),
				"and it must not carry the clause");
	}

	// ---------- focus-hint variant ----------

	@Test
	public void buildUserMessageWithEmptyFocusShouldEqualTwoArgForm() {
		// The 2-arg form is now a thin delegate over the 3-arg form with focusIndices=[].
		// A regression that diverged the two forms would silently break the warmup byte-
		// prefix contract: warmup uses the 2-arg form, real queries (when prefilter is off
		// or the focus list is empty) use the 3-arg form — they must produce identical bytes.
		String twoArg = LlmProvider.buildUserMessage(CHART, "Q?");
		String threeArgEmpty = LlmProvider.buildUserMessage(CHART,
				Collections.<Integer>emptyList(), "Q?");
		assertEquals(twoArg, threeArgEmpty,
				"empty focus must produce byte-identical output to the 2-arg form");
	}

	@Test
	public void buildUserMessageWithFocusShouldRenderHintBeforeQuestion() {
		String msg = LlmProvider.buildUserMessage(CHART, Arrays.asList(1, 2), "What is the BP?");
		assertTrue(msg.contains("Records ranked by similarity to the query: 1, 2"),
				"focus hint must render the focus label followed by 1-based indices comma-separated. "
				+ "Got: '" + msg + "'");
		assertTrue(msg.contains("Use these as the starting point"),
				"focus hint must include the positive-direction instruction telling the LLM "
				+ "the focus records are a starting point, not a hard cap — prevents the "
				+ "\"thin answer\" mode where the LLM treats the focus list as a hard limit. "
				+ "Got: '" + msg + "'");
		assertTrue(msg.contains("Do NOT cite records about unrelated clinical topics"),
				"focus hint must include the negative-direction instruction forbidding off-topic "
				+ "citations — addresses the \"drift\" mode where the LLM cites records that "
				+ "share keywords or chart position but aren't about the query topic. "
				+ "Got: '" + msg + "'");
		int hintPos = msg.indexOf("Records ranked by similarity");
		int queryPos = msg.indexOf("Clinician's query:");
		assertTrue(hintPos > 0 && hintPos < queryPos,
				"focus hint must sit between the records section and the question header — "
				+ "putting it after the question (or inside the records) would change the bytes "
				+ "the LLM sees BEFORE the question and break llama-server's prefix match");
	}

	@Test
	public void buildUserMessageWithFocusShouldNotPresupposeRelevanceAndShouldPermitAbstention() {
		String msg = LlmProvider.buildUserMessage(CHART, Arrays.asList(1, 2), "Any eye problems?");
		// The focus list is just the top-K nearest neighbours from querystore — there is no
		// relevance gate on that path, so it is non-empty even when nothing in the chart is
		// about the query. The label must NOT assert these records ARE relevant, otherwise it
		// overrides the system prompt's abstention guidance.
		assertFalse(msg.contains("most relevant"),
				"focus hint must not claim the ranked records are 'most relevant' — that "
				+ "presupposes relevance the similarity ranking does not establish, and steers "
				+ "the LLM to answer about off-topic nearest-neighbours instead of abstaining. "
				+ "Got: '" + msg + "'");
		assertTrue(msg.contains("Similarity does not guarantee relevance"),
				"focus hint must explicitly state that similarity does not guarantee relevance. "
				+ "Got: '" + msg + "'");
		assertTrue(msg.contains("no relevant records were found") && msg.contains("cite nothing"),
				"focus hint must give the LLM an explicit abstention escape: when none of the "
				+ "ranked records are actually about the query, say no relevant records were found "
				+ "and cite nothing. Got: '" + msg + "'");
	}

	@Test
	public void buildUserMessageWithFocusShouldShareChartPrefixWithEmptyFocus() {
		// The KV-cache contract: bytes up to the end of the chart records section must be
		// identical regardless of whether a focus hint follows. llama-server matches the
		// prefix up to the divergence point — if the chart bytes diverged when a focus
		// hint was added, we'd lose all the cache reuse the focus-hint mode was designed
		// to enable.
		String withFocus = LlmProvider.buildUserMessage(CHART, Arrays.asList(1, 2), "Q?");
		String emptyFocus = LlmProvider.buildUserMessage(CHART,
				Collections.<Integer>emptyList(), "Q?");
		// Common prefix = up to where the two strings diverge.
		int divergeAt = 0;
		int max = Math.min(withFocus.length(), emptyFocus.length());
		while (divergeAt < max && withFocus.charAt(divergeAt) == emptyFocus.charAt(divergeAt)) {
			divergeAt++;
		}
		String sharedPrefix = withFocus.substring(0, divergeAt);
		assertTrue(sharedPrefix.contains(CHART),
				"shared prefix must include the entire chart records section — focus-hint "
				+ "rendering must not perturb anything ABOVE the hint. Shared prefix ended at:\n"
				+ "  '" + sharedPrefix + "'");
	}

	@Test
	public void buildUserMessageShouldOmitHintLineWhenFocusIsNull() {
		// Defensive null-handling: callers (PatientChart.getFocusIndices() in particular)
		// can theoretically hand us null. Treat as empty rather than NPE.
		String msg = LlmProvider.buildUserMessage(CHART, null, "Q?");
		assertTrue(!msg.contains("Records most relevant"),
				"null focus list must not render a hint line. Got: '" + msg + "'");
	}
}
