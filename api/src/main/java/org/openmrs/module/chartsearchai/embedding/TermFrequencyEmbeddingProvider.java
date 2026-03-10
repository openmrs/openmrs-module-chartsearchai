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

/**
 * Fallback embedding provider using simple term-frequency hashing. Produces a deterministic
 * vector that enables basic keyword-overlap retrieval when no ONNX model is available.
 *
 * <p>This is significantly less capable than a proper sentence transformer model — it has no
 * understanding of synonyms, context, or semantics. It is provided only as a fallback so the
 * module can function (with degraded search quality) without the ONNX model file.</p>
 */
public class TermFrequencyEmbeddingProvider implements EmbeddingProvider {

	private static final int DIMENSIONS = 384;

	@Override
	public float[] embed(String text) {
		float[] embedding = new float[DIMENSIONS];

		String[] tokens = text.toLowerCase().split("\\W+");
		for (String token : tokens) {
			if (token.isEmpty()) {
				continue;
			}
			int index = Math.abs(token.hashCode() % DIMENSIONS);
			embedding[index] += 1.0f;
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

	@Override
	public int getDimensions() {
		return DIMENSIONS;
	}
}
