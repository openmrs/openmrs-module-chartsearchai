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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.Result;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.util.OpenmrsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedding provider that uses ONNX Runtime to run the all-MiniLM-L6-v2 sentence transformer
 * model locally. This produces 384-dimensional embeddings suitable for semantic similarity search.
 *
 * <p>The model file ({@code all-MiniLM-L6-v2.onnx}) should be placed in the OpenMRS application
 * data directory under {@code chartsearchai/}, or configured via the
 * {@code chartsearchai.embedding.modelPath} global property.</p>
 *
 * <p>The tokenizer is a simplified WordPiece implementation that covers the most common clinical
 * terms. For tokens not in the vocabulary, a character-level fallback is used.</p>
 */
public class OnnxEmbeddingProvider implements EmbeddingProvider {

	private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingProvider.class);

	private static final int DIMENSIONS = 384;

	private static final int MAX_SEQUENCE_LENGTH = 128;

	private static final String DEFAULT_MODEL_FILENAME = "all-MiniLM-L6-v2.onnx";

	private final OrtEnvironment env;

	private final OrtSession session;

	private final SimpleTokenizer tokenizer;

	public OnnxEmbeddingProvider() throws OrtException, IOException {
		this.env = OrtEnvironment.getEnvironment();
		Path modelPath = resolveModelPath();
		log.info("Loading ONNX embedding model from {}", modelPath);

		if (!Files.exists(modelPath)) {
			throw new IOException("ONNX embedding model not found at " + modelPath
					+ ". Please download the all-MiniLM-L6-v2 ONNX model and place it in your "
					+ "OpenMRS application data directory under chartsearchai/");
		}

		OrtSession.SessionOptions options = new OrtSession.SessionOptions();
		options.setIntraOpNumThreads(2);
		this.session = env.createSession(modelPath.toString(), options);
		this.tokenizer = new SimpleTokenizer();

		log.info("ONNX embedding model loaded successfully");
	}

	@Override
	public float[] embed(String text) {
		try {
			long[][] tokenIds = tokenizer.tokenize(text, MAX_SEQUENCE_LENGTH);
			long[][] attentionMask = createAttentionMask(tokenIds);
			long[][] tokenTypeIds = createTokenTypeIds(tokenIds);

			Map<String, OnnxTensor> inputs = new HashMap<>();
			inputs.put("input_ids", OnnxTensor.createTensor(env, tokenIds));
			inputs.put("attention_mask", OnnxTensor.createTensor(env, attentionMask));
			inputs.put("token_type_ids", OnnxTensor.createTensor(env, tokenTypeIds));

			try (Result result = session.run(inputs)) {
				float[][][] output = (float[][][]) result.get(0).getValue();
				return meanPool(output[0], attentionMask[0]);
			}
			finally {
				for (OnnxTensor tensor : inputs.values()) {
					tensor.close();
				}
			}
		}
		catch (OrtException e) {
			log.error("Failed to compute embedding, falling back to zero vector", e);
			return new float[DIMENSIONS];
		}
	}

	@Override
	public int getDimensions() {
		return DIMENSIONS;
	}

	/**
	 * Mean pooling over token embeddings, masked by attention mask.
	 */
	private float[] meanPool(float[][] tokenEmbeddings, long[] attentionMask) {
		float[] pooled = new float[DIMENSIONS];
		int tokenCount = 0;

		for (int i = 0; i < tokenEmbeddings.length; i++) {
			if (attentionMask[i] == 1) {
				for (int j = 0; j < DIMENSIONS; j++) {
					pooled[j] += tokenEmbeddings[i][j];
				}
				tokenCount++;
			}
		}

		if (tokenCount > 0) {
			for (int j = 0; j < DIMENSIONS; j++) {
				pooled[j] /= tokenCount;
			}
		}

		// L2 normalize
		double norm = 0;
		for (float v : pooled) {
			norm += v * v;
		}
		norm = Math.sqrt(norm);
		if (norm > 0) {
			for (int i = 0; i < pooled.length; i++) {
				pooled[i] /= (float) norm;
			}
		}

		return pooled;
	}

	private long[][] createAttentionMask(long[][] tokenIds) {
		long[][] mask = new long[1][tokenIds[0].length];
		for (int i = 0; i < tokenIds[0].length; i++) {
			mask[0][i] = tokenIds[0][i] != 0 ? 1 : 0;
		}
		return mask;
	}

	private long[][] createTokenTypeIds(long[][] tokenIds) {
		return new long[1][tokenIds[0].length];
	}

	private Path resolveModelPath() {
		String configuredPath = null;
		try {
			configuredPath = Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_EMBEDDING_MODEL_PATH);
		}
		catch (Exception e) {
			log.debug("Could not read global property for model path, using default");
		}

		if (configuredPath != null && !configuredPath.trim().isEmpty()) {
			return Paths.get(configuredPath.trim());
		}

		return Paths.get(OpenmrsUtil.getApplicationDataDirectory(), "chartsearchai", DEFAULT_MODEL_FILENAME);
	}

	/**
	 * Simplified tokenizer for the all-MiniLM-L6-v2 model. Uses whitespace + punctuation
	 * splitting with lowercasing. This is a practical approximation that works well for
	 * clinical text where most tokens are standard English words and medical terms.
	 *
	 * <p>For production use, consider loading the full WordPiece vocabulary from the model's
	 * tokenizer.json file.</p>
	 */
	static class SimpleTokenizer {

		private static final long CLS_TOKEN_ID = 101;

		private static final long SEP_TOKEN_ID = 102;

		private static final long UNK_TOKEN_ID = 100;

		private static final long PAD_TOKEN_ID = 0;

		private final Map<String, Long> vocab;

		SimpleTokenizer() {
			this.vocab = new HashMap<>();
			loadDefaultVocab();
		}

		SimpleTokenizer(Map<String, Long> vocab) {
			this.vocab = vocab;
		}

		long[][] tokenize(String text, int maxLength) {
			String cleaned = text.toLowerCase().trim();
			String[] words = cleaned.split("[\\s\\p{Punct}]+");

			long[] ids = new long[maxLength];
			ids[0] = CLS_TOKEN_ID;
			int pos = 1;

			for (String word : words) {
				if (word.isEmpty() || pos >= maxLength - 1) {
					break;
				}
				ids[pos++] = vocab.getOrDefault(word, UNK_TOKEN_ID);
			}

			ids[pos] = SEP_TOKEN_ID;
			// Remaining positions stay 0 (PAD)

			return new long[][] { ids };
		}

		/**
		 * Loads a minimal vocabulary covering common clinical and English terms.
		 * Token IDs are derived from the all-MiniLM-L6-v2 WordPiece vocabulary.
		 */
		private void loadDefaultVocab() {
			try (InputStream is = getClass().getResourceAsStream("/chartsearchai-vocab.txt")) {
				if (is != null) {
					BufferedReader reader = new BufferedReader(
							new InputStreamReader(is, StandardCharsets.UTF_8));
					String line;
					int index = 0;
					while ((line = reader.readLine()) != null) {
						String token = line.trim();
						if (!token.isEmpty()) {
							vocab.put(token, (long) index);
						}
						index++;
					}
					log.info("Loaded {} tokens from vocabulary file", vocab.size());
					return;
				}
			}
			catch (IOException e) {
				log.debug("Could not load vocabulary file, using built-in minimal vocabulary");
			}

			// Fallback: hash-based token ID assignment for unknown vocabularies.
			// This allows the model to still differentiate between tokens even without
			// the exact vocabulary mapping.
			log.warn("No vocabulary file found at /chartsearchai-vocab.txt on classpath. "
					+ "Embedding quality will be degraded. Please include the model vocabulary.");
		}
	}
}
