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

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * WordPiece tokenizer compatible with BERT-based models (e.g. all-MiniLM-L6-v2).
 * Loads a vocab.txt file and tokenizes text using the WordPiece algorithm:
 * split into words, then greedily match the longest subword tokens from the vocabulary.
 */
public class WordPieceTokenizer {

	private static final int CLS_TOKEN_ID = 101;

	private static final int SEP_TOKEN_ID = 102;

	private static final int UNK_TOKEN_ID = 100;

	private static final String SUBWORD_PREFIX = "##";

	private static final int MAX_WORD_LENGTH = 200;

	private static final Pattern PUNCTUATION = Pattern.compile(
			"([\\p{Punct}\\u2000-\\u206F\\u2E00-\\u2E7F\\\\'\"`])");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private final Map<String, Integer> vocab;

	private final int maxSequenceLength;

	public WordPieceTokenizer(String vocabFilePath, int maxSequenceLength) throws IOException {
		this.maxSequenceLength = maxSequenceLength;
		this.vocab = loadVocab(vocabFilePath);
	}

	/**
	 * Tokenizes text into input IDs, attention mask, and token type IDs
	 * suitable for BERT model input.
	 *
	 * @return a {@link TokenizedInput} containing the three arrays
	 */
	public TokenizedInput tokenize(String text) {
		List<Integer> tokenIds = new ArrayList<Integer>();
		tokenIds.add(CLS_TOKEN_ID);

		String normalized = text.toLowerCase().trim();
		// Insert spaces around punctuation so it becomes a separate token
		normalized = PUNCTUATION.matcher(normalized).replaceAll(" $1 ");
		String[] words = WHITESPACE.split(normalized);

		for (String word : words) {
			if (word.isEmpty()) {
				continue;
			}
			tokenizeWord(word, tokenIds);
			// Reserve space for [SEP] token
			if (tokenIds.size() >= maxSequenceLength - 1) {
				break;
			}
		}

		// Truncate to leave room for [SEP]
		if (tokenIds.size() > maxSequenceLength - 1) {
			tokenIds = new ArrayList<Integer>(tokenIds.subList(0, maxSequenceLength - 1));
		}
		tokenIds.add(SEP_TOKEN_ID);

		int seqLen = tokenIds.size();
		long[] inputIds = new long[seqLen];
		long[] attentionMask = new long[seqLen];
		long[] tokenTypeIds = new long[seqLen];

		for (int i = 0; i < seqLen; i++) {
			inputIds[i] = tokenIds.get(i);
			attentionMask[i] = 1;
			tokenTypeIds[i] = 0;
		}

		return new TokenizedInput(inputIds, attentionMask, tokenTypeIds);
	}

	private void tokenizeWord(String word, List<Integer> tokenIds) {
		if (word.length() > MAX_WORD_LENGTH) {
			tokenIds.add(UNK_TOKEN_ID);
			return;
		}

		int start = 0;
		boolean isBad = false;
		List<Integer> subTokens = new ArrayList<Integer>();

		while (start < word.length()) {
			int end = word.length();
			Integer matchedId = null;

			while (start < end) {
				String substr = word.substring(start, end);
				if (start > 0) {
					substr = SUBWORD_PREFIX + substr;
				}
				Integer id = vocab.get(substr);
				if (id != null) {
					matchedId = id;
					break;
				}
				end--;
			}

			if (matchedId == null) {
				isBad = true;
				break;
			}

			subTokens.add(matchedId);
			start = end;
		}

		if (isBad) {
			tokenIds.add(UNK_TOKEN_ID);
		} else {
			tokenIds.addAll(subTokens);
		}
	}

	private Map<String, Integer> loadVocab(String filePath) throws IOException {
		Map<String, Integer> vocabMap = new HashMap<String, Integer>();
		BufferedReader reader = new BufferedReader(new FileReader(filePath));
		try {
			String line;
			int index = 0;
			while ((line = reader.readLine()) != null) {
				vocabMap.put(line.trim(), index);
				index++;
			}
		}
		finally {
			try {
				reader.close();
			}
			catch (IOException e) {
				// suppress close exception to avoid masking the original
			}
		}
		return vocabMap;
	}

	public static class TokenizedInput {

		private final long[] inputIds;

		private final long[] attentionMask;

		private final long[] tokenTypeIds;

		public TokenizedInput(long[] inputIds, long[] attentionMask, long[] tokenTypeIds) {
			this.inputIds = inputIds;
			this.attentionMask = attentionMask;
			this.tokenTypeIds = tokenTypeIds;
		}

		public long[] getInputIds() {
			return inputIds;
		}

		public long[] getAttentionMask() {
			return attentionMask;
		}

		public long[] getTokenTypeIds() {
			return tokenTypeIds;
		}

		public int getLength() {
			return inputIds.length;
		}
	}
}
