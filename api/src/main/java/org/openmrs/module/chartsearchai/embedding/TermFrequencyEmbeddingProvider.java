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

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.springframework.stereotype.Component;

/**
 * Embedding provider using term-frequency hashing. Produces a deterministic vector that enables
 * basic keyword-overlap retrieval. This is a placeholder; replace with ONNX Runtime +
 * all-MiniLM-L6-v2 for semantic similarity.
 */
@Component("chartSearchAi.termFrequencyEmbeddingProvider")
public class TermFrequencyEmbeddingProvider implements EmbeddingProvider {

	@Override
	public float[] embed(String text) {
		int dimensions = ChartSearchAiConstants.EMBEDDING_DIMENSIONS;
		float[] embedding = new float[dimensions];

		String[] tokens = text.toLowerCase().split("\\W+");
		for (String token : tokens) {
			if (token.isEmpty()) {
				continue;
			}
			int index = Math.abs(token.hashCode() % dimensions);
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
		return ChartSearchAiConstants.EMBEDDING_DIMENSIONS;
	}
}
