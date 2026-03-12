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

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Embedding provider using ONNX Runtime with the all-MiniLM-L6-v2 model. Produces
 * 384-dimensional vectors for semantic similarity search. The ONNX model file path
 * is configured via the {@code chartsearchai.embedding.modelPath} global property.
 */
@Component("chartSearchAi.onnxEmbeddingProvider")
public class OnnxEmbeddingProvider implements EmbeddingProvider {

	private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingProvider.class);

	private OrtEnvironment env;

	private OrtSession session;

	@Override
	public float[] embed(String text) {
		try {
			OrtSession ortSession = getSession();

			// Simple whitespace tokenization with padding — a proper tokenizer
			// (WordPiece) should replace this for production use
			String[] words = text.toLowerCase().split("\\s+");
			int maxLen = Math.min(words.length, 256);

			long[] inputIds = new long[maxLen];
			long[] attentionMask = new long[maxLen];
			long[] tokenTypeIds = new long[maxLen];

			// CLS token
			inputIds[0] = 101;
			attentionMask[0] = 1;
			tokenTypeIds[0] = 0;

			for (int i = 1; i < maxLen - 1 && i < words.length; i++) {
				inputIds[i] = Math.abs(words[i].hashCode() % 30000) + 1000;
				attentionMask[i] = 1;
				tokenTypeIds[i] = 0;
			}

			// SEP token
			if (maxLen > 1) {
				inputIds[maxLen - 1] = 102;
				attentionMask[maxLen - 1] = 1;
				tokenTypeIds[maxLen - 1] = 0;
			}

			long[][] inputIdsArr = { inputIds };
			long[][] attentionMaskArr = { attentionMask };
			long[][] tokenTypeIdsArr = { tokenTypeIds };

			Map<String, OnnxTensor> inputs = new HashMap<String, OnnxTensor>();
			inputs.put("input_ids", OnnxTensor.createTensor(env, inputIdsArr));
			inputs.put("attention_mask", OnnxTensor.createTensor(env, attentionMaskArr));
			inputs.put("token_type_ids", OnnxTensor.createTensor(env, tokenTypeIdsArr));

			OrtSession.Result result = ortSession.run(inputs);

			// Mean pooling over token embeddings
			float[][][] output = (float[][][]) result.get(0).getValue();
			float[] embedding = new float[ChartSearchAiConstants.EMBEDDING_DIMENSIONS];
			int tokenCount = 0;
			for (int i = 0; i < maxLen; i++) {
				if (attentionMask[i] == 1) {
					for (int j = 0; j < embedding.length; j++) {
						embedding[j] += output[0][i][j];
					}
					tokenCount++;
				}
			}
			if (tokenCount > 0) {
				for (int j = 0; j < embedding.length; j++) {
					embedding[j] /= tokenCount;
				}
			}

			// L2 normalize
			double norm = 0;
			for (float v : embedding) {
				norm += v * v;
			}
			norm = Math.sqrt(norm);
			if (norm > 0) {
				for (int i = 0; i < embedding.length; i++) {
					embedding[i] /= (float) norm;
				}
			}

			// Clean up tensors
			for (OnnxTensor tensor : inputs.values()) {
				tensor.close();
			}
			result.close();

			return embedding;
		}
		catch (OrtException e) {
			throw new RuntimeException("Failed to compute embedding", e);
		}
	}

	@Override
	public int getDimensions() {
		return ChartSearchAiConstants.EMBEDDING_DIMENSIONS;
	}

	private synchronized OrtSession getSession() throws OrtException {
		if (session == null) {
			String configuredPath = Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_EMBEDDING_MODEL_PATH);
			if (configuredPath == null || configuredPath.trim().isEmpty()) {
				throw new IllegalStateException(
						"Embedding model path not configured. Set the global property: "
								+ ChartSearchAiConstants.GP_EMBEDDING_MODEL_PATH);
			}
			String modelPath = ChartSearchAiConstants.resolveModelPath(
					configuredPath.trim(), ChartSearchAiConstants.GP_EMBEDDING_MODEL_PATH);
			log.info("Loading ONNX embedding model from {}", modelPath);
			env = OrtEnvironment.getEnvironment();
			session = env.createSession(modelPath);
			log.info("ONNX embedding model loaded successfully");
		}
		return session;
	}
}
