/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.embedding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.embedding.WordPieceTokenizer.TokenizedInput;

public class WordPieceTokenizerTest {

	private static WordPieceTokenizer tokenizer;

	// Token IDs based on the test-vocab.txt line numbers (0-indexed)
	private static final int CLS = 101; // [CLS]
	private static final int SEP = 102; // [SEP]
	private static final int UNK = 100; // [UNK]

	// Token IDs match line numbers (0-indexed) in test-vocab.txt
	private static final int THE = 104;
	private static final int PATIENT = 105;
	private static final int HAS = 106;
	private static final int DIABETES = 107;
	private static final int HELLO = 129;
	private static final int WORLD = 130;
	private static final int SUBWORD_S = 131;
	private static final int DOT = 118;

	@BeforeAll
	public static void setUp() throws IOException {
		String vocabPath = WordPieceTokenizerTest.class.getClassLoader()
				.getResource("test-vocab.txt").getFile();
		tokenizer = new WordPieceTokenizer(vocabPath, 128);
	}

	@Test
	public void tokenize_shouldWrapWithClsAndSep() {
		TokenizedInput result = tokenizer.tokenize("hello");
		long[] ids = result.getInputIds();
		assertEquals(CLS, ids[0], "First token should be [CLS]");
		assertEquals(SEP, ids[ids.length - 1], "Last token should be [SEP]");
	}

	@Test
	public void tokenize_shouldTokenizeKnownWords() {
		TokenizedInput result = tokenizer.tokenize("the patient has diabetes");
		long[] ids = result.getInputIds();
		// [CLS] the patient has diabetes [SEP]
		assertEquals(6, ids.length);
		assertEquals(CLS, ids[0]);
		assertEquals(THE, ids[1]);
		assertEquals(PATIENT, ids[2]);
		assertEquals(HAS, ids[3]);
		assertEquals(DIABETES, ids[4]);
		assertEquals(SEP, ids[5]);
	}

	@Test
	public void tokenize_shouldUseUnkForUnknownWords() {
		TokenizedInput result = tokenizer.tokenize("xyzzy");
		long[] ids = result.getInputIds();
		// [CLS] [UNK] [SEP]
		assertEquals(3, ids.length);
		assertEquals(UNK, ids[1]);
	}

	@Test
	public void tokenize_shouldSetAttentionMaskToOne() {
		TokenizedInput result = tokenizer.tokenize("hello world");
		long[] mask = result.getAttentionMask();
		for (long v : mask) {
			assertEquals(1, v);
		}
	}

	@Test
	public void tokenize_shouldSetTokenTypeIdsToZero() {
		TokenizedInput result = tokenizer.tokenize("hello world");
		long[] typeIds = result.getTokenTypeIds();
		for (long v : typeIds) {
			assertEquals(0, v);
		}
	}

	@Test
	public void tokenize_shouldHandleEmptyText() {
		TokenizedInput result = tokenizer.tokenize("");
		long[] ids = result.getInputIds();
		// [CLS] [SEP]
		assertEquals(2, ids.length);
		assertEquals(CLS, ids[0]);
		assertEquals(SEP, ids[1]);
	}

	@Test
	public void tokenize_shouldBeCaseInsensitive() {
		TokenizedInput result = tokenizer.tokenize("THE PATIENT");
		long[] ids = result.getInputIds();
		// [CLS] the patient [SEP]
		assertEquals(4, ids.length);
		assertEquals(THE, ids[1]);
		assertEquals(PATIENT, ids[2]);
	}

	@Test
	public void tokenize_shouldSplitPunctuation() {
		TokenizedInput result = tokenizer.tokenize("the patient.");
		long[] ids = result.getInputIds();
		// [CLS] the patient . [SEP]
		assertEquals(5, ids.length);
		assertEquals(THE, ids[1]);
		assertEquals(PATIENT, ids[2]);
		assertEquals(DOT, ids[3]);
		assertEquals(SEP, ids[4]);
	}

	@Test
	public void tokenize_shouldTruncateToMaxLength() throws IOException {
		WordPieceTokenizer shortTokenizer = new WordPieceTokenizer(
				WordPieceTokenizerTest.class.getClassLoader()
						.getResource("test-vocab.txt").getFile(), 5);
		// "the patient has diabetes" has 4 word tokens + CLS + SEP = 6, but max is 5
		TokenizedInput result = shortTokenizer.tokenize("the patient has diabetes");
		assertTrue(result.getLength() <= 5, "Should not exceed max sequence length");
		assertEquals(SEP, result.getInputIds()[result.getLength() - 1],
				"Last token should still be [SEP]");
	}

	@Test
	public void tokenize_shouldUseSubwordTokens() {
		// "patients" should split into "patient" + "##s"
		TokenizedInput result = tokenizer.tokenize("patients");
		long[] ids = result.getInputIds();
		// [CLS] patient ##s [SEP]
		assertEquals(4, ids.length);
		assertEquals(PATIENT, ids[1]);
		assertEquals(SUBWORD_S, ids[2]);
	}

	@Test
	public void getLength_shouldMatchArrayLengths() {
		TokenizedInput result = tokenizer.tokenize("hello world");
		assertEquals(result.getLength(), result.getInputIds().length);
		assertEquals(result.getLength(), result.getAttentionMask().length);
		assertEquals(result.getLength(), result.getTokenTypeIds().length);
	}
}
