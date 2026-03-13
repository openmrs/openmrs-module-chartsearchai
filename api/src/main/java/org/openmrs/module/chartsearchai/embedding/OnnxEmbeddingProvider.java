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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.embedding.WordPieceTokenizer.TokenizedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Embedding provider using ONNX Runtime with the all-MiniLM-L6-v2 model. Produces
 * 384-dimensional vectors for semantic similarity search. The ONNX model file path
 * is configured via the {@code chartsearchai.embedding.modelFilePath} global property.
 */
@Component("chartSearchAi.onnxEmbeddingProvider")
public class OnnxEmbeddingProvider implements EmbeddingProvider {

	private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingProvider.class);

	private static final int MAX_SEQUENCE_LENGTH = 256;

	private OrtEnvironment env;

	private OrtSession session;

	private WordPieceTokenizer tokenizer;

	@Override
	public synchronized float[] embed(String text) {
		Map<String, OnnxTensor> inputs = new HashMap<String, OnnxTensor>();
		OrtSession.Result result = null;
		try {
			OrtSession ortSession = getSession();
			WordPieceTokenizer wpTokenizer = getTokenizer();

			TokenizedInput tokenized = wpTokenizer.tokenize(text);
			int seqLen = tokenized.getLength();

			long[][] inputIdsArr = { tokenized.getInputIds() };
			long[][] attentionMaskArr = { tokenized.getAttentionMask() };
			long[][] tokenTypeIdsArr = { tokenized.getTokenTypeIds() };

			inputs.put("input_ids", OnnxTensor.createTensor(env, inputIdsArr));
			inputs.put("attention_mask", OnnxTensor.createTensor(env, attentionMaskArr));
			inputs.put("token_type_ids", OnnxTensor.createTensor(env, tokenTypeIdsArr));

			result = ortSession.run(inputs);

			// Mean pooling over token embeddings (masked by attention)
			float[][][] output = (float[][][]) result.get(0).getValue();
			float[] embedding = new float[ChartSearchAiConstants.EMBEDDING_DIMENSIONS];
			long[] attentionMask = tokenized.getAttentionMask();
			int tokenCount = 0;
			for (int i = 0; i < seqLen; i++) {
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

			return embedding;
		}
		catch (OrtException e) {
			throw new RuntimeException("Failed to compute embedding", e);
		}
		finally {
			if (result != null) {
				try {
					result.close();
				}
				catch (Exception e) {
					log.warn("Error closing ONNX result", e);
				}
			}
			for (OnnxTensor tensor : inputs.values()) {
				try {
					tensor.close();
				}
				catch (Exception e) {
					log.warn("Error closing ONNX tensor", e);
				}
			}
		}
	}

	@Override
	public int getDimensions() {
		return ChartSearchAiConstants.EMBEDDING_DIMENSIONS;
	}

	public synchronized void close() {
		if (session != null) {
			log.info("Closing ONNX embedding session");
			try {
				session.close();
			}
			catch (OrtException e) {
				log.warn("Error closing ONNX session", e);
			}
			session = null;
			tokenizer = null;
			env = null;
		}
	}

	private synchronized OrtSession getSession() throws OrtException {
		if (session == null) {
			String configuredPath = Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_EMBEDDING_MODEL_FILE_PATH);
			if (configuredPath == null || configuredPath.trim().isEmpty()) {
				throw new IllegalStateException(
						"Embedding model path not configured. Set the global property: "
								+ ChartSearchAiConstants.GP_EMBEDDING_MODEL_FILE_PATH);
			}
			String modelPath = ChartSearchAiConstants.resolveModelPath(
					configuredPath.trim(), ChartSearchAiConstants.GP_EMBEDDING_MODEL_FILE_PATH);
			log.info("Loading ONNX embedding model from {}", modelPath);
			env = OrtEnvironment.getEnvironment();
			session = env.createSession(modelPath);
			log.info("ONNX embedding model loaded successfully");
		}
		return session;
	}

	private synchronized WordPieceTokenizer getTokenizer() {
		if (tokenizer == null) {
			String configuredPath = Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_EMBEDDING_VOCAB_FILE_PATH);
			if (configuredPath == null || configuredPath.trim().isEmpty()) {
				throw new IllegalStateException(
						"Embedding vocabulary path not configured. Set the global property: "
								+ ChartSearchAiConstants.GP_EMBEDDING_VOCAB_FILE_PATH);
			}
			String vocabPath = ChartSearchAiConstants.resolveModelPath(
					configuredPath.trim(), ChartSearchAiConstants.GP_EMBEDDING_VOCAB_FILE_PATH);
			log.info("Loading WordPiece vocabulary from {}", vocabPath);
			try {
				tokenizer = new WordPieceTokenizer(vocabPath, MAX_SEQUENCE_LENGTH);
			}
			catch (IOException e) {
				throw new IllegalStateException("Failed to load WordPiece vocabulary from "
						+ vocabPath, e);
			}
			log.info("WordPiece vocabulary loaded successfully");
		}
		return tokenizer;
	}
}
