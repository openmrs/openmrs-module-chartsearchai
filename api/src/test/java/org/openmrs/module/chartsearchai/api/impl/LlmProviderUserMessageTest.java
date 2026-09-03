/*
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
 * Verifies the warmup contract: the user-message prefix sent during {@code warmup}
 * MUST be a byte-prefix of the user-message sent during a real query on the same
 * chart, otherwise llama-server's KV-cache prefix match breaks and the warmup
 * is wasted work.
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
