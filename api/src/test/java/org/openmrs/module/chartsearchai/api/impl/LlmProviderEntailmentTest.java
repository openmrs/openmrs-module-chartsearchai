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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Tier-2 grounding entailment helpers on {@link LlmProvider}
 * ({@link LlmProvider#parseYesNo} and the input guards of
 * {@link LlmProvider#entails}). Kept in a dedicated class so this slice's
 * coverage is independent of the main {@code LlmProviderTest}. The engine-backed
 * path of {@code entails} is exercised via the stub provider in
 * {@code CitationGroundingVerifierTest}; here we cover the no-engine guards and
 * the response parser, which need no OpenMRS context.
 */
public class LlmProviderEntailmentTest {

	@Test
	public void parseYesNo_shouldParsePlainYesAndNo() {
		assertEquals(Boolean.TRUE, LlmProvider.parseYesNo("YES"));
		assertEquals(Boolean.FALSE, LlmProvider.parseYesNo("NO"));
	}

	@Test
	public void parseYesNo_shouldBeCaseAndWhitespaceAndPunctuationTolerant() {
		assertEquals(Boolean.TRUE, LlmProvider.parseYesNo("  yes.\n"));
		assertEquals(Boolean.FALSE, LlmProvider.parseYesNo("No, the source does not support it."));
	}

	@Test
	public void parseYesNo_shouldReturnNullWhenNeitherTokenPresent() {
		assertNull(LlmProvider.parseYesNo("I am not sure"));
		assertNull(LlmProvider.parseYesNo(""));
		assertNull(LlmProvider.parseYesNo(null));
	}

	@Test
	public void parseYesNo_shouldNotMatchTokenInsideALongerWord() {
		// "NOTHING"/"YESTERDAY" begin with the tokens but are not standalone words;
		// the word-boundary anchor must reject them.
		assertNull(LlmProvider.parseYesNo("NOTHING is stated"));
		assertNull(LlmProvider.parseYesNo("YESTERDAY's note"));
	}

	@Test
	public void entailmentSystemPrompt_shouldDirectVerdictToTheAnswerField() {
		// The shared response schema forces {reasoning, answer, citations}. The entailment prompt
		// must put the YES/NO verdict in the "answer" field (where parseEntailmentVerdict reads it)
		// and must NOT demand a bare one-word reply — that would contradict the enforced schema.
		String p = LlmProvider.ENTAILMENT_SYSTEM_PROMPT;
		assertTrue(p.contains("\"answer\""),
				"entailment prompt must direct the verdict into the answer field, matching the schema");
		assertTrue(p.contains("YES") && p.contains("NO"), "must still ask for a YES/NO verdict");
		assertFalse(p.contains("exactly one word"),
				"must not demand a bare one-word reply — the schema forces reasoning-first JSON");
	}

	@Test
	public void parseEntailmentVerdict_shouldReadVerdictFromAnswerNotReasoning() {
		// Regression for the reasoning-first response schema: "reasoning" is emitted before
		// "answer" and a fact-checker's reasoning routinely contains "no" ("no explicit
		// wording…"). Scanning the raw reply (what the old entails did via parseYesNo) grabs that
		// leading "no" and flips the verdict. parseEntailmentVerdict must read the answer field.
		String yesDespiteNoInReasoning = "{\"reasoning\": \"There is no explicit wording, but the "
				+ "source does state the finding, so it supports the statement.\", "
				+ "\"answer\": \"YES\", \"citations\": []}";
		// Pre-fix behaviour (parseYesNo on the raw reply) would have returned FALSE here:
		assertEquals(Boolean.FALSE, LlmProvider.parseYesNo(yesDespiteNoInReasoning),
				"guards the regression: raw-text parsing reads the verdict out of the reasoning");
		// The fix reads the structured answer field, recovering the true verdict:
		assertEquals(Boolean.TRUE, LlmProvider.parseEntailmentVerdict(yesDespiteNoInReasoning));

		String noDespiteYesInReasoning = "{\"reasoning\": \"At first glance one might say yes, but "
				+ "the source is about a different body part.\", \"answer\": \"NO\", \"citations\": []}";
		assertEquals(Boolean.FALSE, LlmProvider.parseEntailmentVerdict(noDespiteYesInReasoning));
	}

	@Test
	public void parseEntailmentVerdict_shouldDegradeSafely() {
		// A bare, envelope-free reply still parses (stubs / non-JSON paths), and null stays null.
		assertEquals(Boolean.TRUE, LlmProvider.parseEntailmentVerdict("YES"));
		assertEquals(Boolean.FALSE, LlmProvider.parseEntailmentVerdict("NO"));
		assertNull(LlmProvider.parseEntailmentVerdict(null));
	}

	@Test
	public void parseEntailmentVerdict_shouldReturnNullWhenAnswerHasBothVerdictWords() {
		// A verbose, non-compliant answer field containing BOTH verdict words must
		// not be parsed positionally (which would silently flip the verdict) — it is
		// undecidable, so grounding falls back to its Tier-1 verdict.
		String bothWords = "{\"reasoning\": \"weighing it up\", "
				+ "\"answer\": \"Yes, but there is no explicit confirmation\", \"citations\": []}";
		assertNull(LlmProvider.parseEntailmentVerdict(bothWords),
				"both YES and NO present in answer -> undecidable -> null");

		// A single verdict word with surrounding prose is still unambiguous.
		String onlyYes = "{\"reasoning\": \"r\", \"answer\": \"Yes, the source supports it\", "
				+ "\"citations\": []}";
		assertEquals(Boolean.TRUE, LlmProvider.parseEntailmentVerdict(onlyYes));
	}

	@Test
	public void entails_shouldReturnNullForBlankInputsWithoutCallingEngine() {
		// These guards short-circuit before any engine call, so they are safe to
		// exercise without an OpenMRS context. If they did not short-circuit, the
		// missing engine/context would throw instead of returning null.
		LlmProvider provider = new LlmProvider();
		assertNull(provider.entails(null, "claim"));
		assertNull(provider.entails("source", null));
		assertNull(provider.entails("   ", "claim"));
		assertNull(provider.entails("source", "   "));
	}
}
