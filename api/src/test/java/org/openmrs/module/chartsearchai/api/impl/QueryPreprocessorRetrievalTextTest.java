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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link QueryPreprocessor#forRetrieval} — the composed
 * expand-then-strip pipeline every chart-build path uses to turn a clinician's question into
 * querystore search text.
 *
 * <p>Composition ORDER is the load-bearing part: expansion must run on the raw question, because
 * stripping lowercases the text and removes the punctuation the two-letter, case-sensitive
 * initialisms depend on. A caller that chained the steps the other way round would silently lose
 * the "MI"/"TB"/"DM" vocabulary — which is why the steps live behind one method and
 * {@code ArchitectureGuardTest.noHandChainedRetrievalPreprocessing} forbids chaining them at the
 * call site.
 */
public class QueryPreprocessorRetrievalTextTest {

	@Test
	public void forRetrieval_shouldExpandThenStrip() {
		String text = QueryPreprocessor.forRetrieval("Does the patient have any CKD?");
		assertTrue(text.contains("chronic kidney disease"),
				"the initialism must be expanded for the embedding: " + text);
		assertTrue(text.contains("ckd"),
				"the clinician's own token must survive stripping: " + text);
		assertEquals(text, text.toLowerCase(),
				"stripping must still run after expansion (it lowercases): " + text);
		assertTrue(!text.contains("?"), "stripping must still remove punctuation: " + text);
	}

	@Test
	public void forRetrieval_shouldPreserveCaseSensitiveInitialismsThatStrippingWouldDestroy() {
		// Proves the ORDER: expansion sees "MI" in capitals and fires. Had stripping run first,
		// the token would already be "mi" and the case-sensitive two-letter rule would skip it.
		String text = QueryPreprocessor.forRetrieval("Any previous MI?");
		assertTrue(text.contains("myocardial infarction"),
				"expansion must run BEFORE the lowercasing strip: " + text);
	}

	@Test
	public void contentWords_shouldNeverFallBackToTheWholeSentence() {
		// stripQueryStopwords deliberately returns the WHOLE cleaned sentence when fewer than two
		// content words survive — a one-word embedding is too vague to retrieve on. A caller
		// reasoning about which words the clinician actually supplied (the scope router's
		// domain-qualifier test) must not see those function words come back, or every
		// single-content-word question looks domain-qualified.
		assertEquals(java.util.Arrays.asList("conditions"),
				QueryPreprocessor.contentWords("What conditions does the patient have?"));
		assertTrue(QueryPreprocessor.stripQueryStopwords("What conditions does the patient have?")
				.contains("patient"),
				"guards the premise: the stripping step DOES fall back here");
	}

	@Test
	public void contentWords_shouldBeNullAndBlankSafe() {
		assertTrue(QueryPreprocessor.contentWords(null).isEmpty());
		assertTrue(QueryPreprocessor.contentWords("   ").isEmpty());
		assertTrue(QueryPreprocessor.contentWords("the and of").isEmpty(),
				"an all-stopword question has no content words");
	}

	@Test
	public void forRetrieval_shouldBeNullAndBlankSafe() {
		// The raw stripping step NPEs on null. Every call site guards today, but the composed
		// entry point must not be a trap for the next one.
		assertEquals(null, QueryPreprocessor.forRetrieval(null));
		assertEquals("  ", QueryPreprocessor.forRetrieval("  "));
	}

	@Test
	public void forRetrieval_shouldEqualPlainStrippingWhenNothingExpands() {
		// The neutrality guarantee: a question with no abbreviation must produce exactly the
		// retrieval text the pipeline produced before expansion was composed in.
		String question = "Does the patient have any kidney problems?";
		assertEquals(QueryPreprocessor.stripQueryStopwords(question),
				QueryPreprocessor.forRetrieval(question));
	}
}
