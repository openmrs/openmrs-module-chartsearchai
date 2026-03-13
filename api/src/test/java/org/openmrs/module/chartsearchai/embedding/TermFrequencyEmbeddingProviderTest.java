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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

public class TermFrequencyEmbeddingProviderTest extends BaseModuleContextSensitiveTest {

	private TermFrequencyEmbeddingProvider provider;

	@BeforeEach
	public void setUp() {
		provider = new TermFrequencyEmbeddingProvider();
	}

	@Test
	public void embed_shouldReturnCorrectDimensions() {
		float[] embedding = provider.embed("test text");
		assertEquals(ChartSearchAiConstants.EMBEDDING_DIMENSIONS, embedding.length);
	}

	@Test
	public void embed_shouldReturnNonNullEmbedding() {
		float[] embedding = provider.embed("Hypertension diagnosis confirmed");
		assertNotNull(embedding);
	}

	@Test
	public void embed_shouldBeL2Normalized() {
		float[] embedding = provider.embed("Systolic Blood Pressure 120 mmHg");
		double norm = 0;
		for (float v : embedding) {
			norm += v * v;
		}
		norm = Math.sqrt(norm);
		assertEquals(1.0, norm, 0.001, "Embedding should be L2 normalized");
	}

	@Test
	public void embed_shouldProduceDeterministicResults() {
		float[] first = provider.embed("Malaria diagnosis");
		float[] second = provider.embed("Malaria diagnosis");
		for (int i = 0; i < first.length; i++) {
			assertEquals(first[i], second[i], "Embedding should be deterministic");
		}
	}

	@Test
	public void embed_shouldProduceSimilarEmbeddingsForSimilarText() {
		float[] a = provider.embed("blood pressure high");
		float[] b = provider.embed("blood pressure elevated");
		// They share "blood" and "pressure", so cosine similarity should be > 0
		double similarity = cosineSimilarity(a, b);
		assertTrue(similarity > 0, "Similar texts should have positive similarity");
	}

	@Test
	public void embed_shouldHandleEmptyString() {
		float[] embedding = provider.embed("");
		assertNotNull(embedding);
		assertEquals(ChartSearchAiConstants.EMBEDDING_DIMENSIONS, embedding.length);
	}

	@Test
	public void getDimensions_shouldReturnCorrectValue() {
		assertEquals(ChartSearchAiConstants.EMBEDDING_DIMENSIONS, provider.getDimensions());
	}

	private double cosineSimilarity(float[] a, float[] b) {
		double dot = 0;
		double normA = 0;
		double normB = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			normA += a[i] * a[i];
			normB += b[i] * b[i];
		}
		double denominator = Math.sqrt(normA) * Math.sqrt(normB);
		if (denominator == 0) {
			return 0;
		}
		return dot / denominator;
	}
}
