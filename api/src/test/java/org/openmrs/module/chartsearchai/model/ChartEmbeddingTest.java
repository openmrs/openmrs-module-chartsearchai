/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class ChartEmbeddingTest {

	@Test
	public void getEmbeddingVector_shouldRoundTripCorrectly() {
		ChartEmbedding ce = new ChartEmbedding();
		float[] original = { 0.1f, -0.5f, 3.14f, 0.0f };
		ce.setEmbeddingVector(original);

		float[] result = ce.getEmbeddingVector();
		assertEquals(original.length, result.length);
		for (int i = 0; i < original.length; i++) {
			assertEquals(original[i], result[i], 0.0001f);
		}
	}

	@Test
	public void getEmbeddingVector_shouldReturnEmptyArrayForNullEmbedding() {
		ChartEmbedding ce = new ChartEmbedding();
		assertArrayEquals(new float[0], ce.getEmbeddingVector());
	}

	@Test
	public void getEmbeddingVector_shouldReturnEmptyArrayForEmptyEmbedding() {
		ChartEmbedding ce = new ChartEmbedding();
		ce.setEmbedding(new byte[0]);
		assertArrayEquals(new float[0], ce.getEmbeddingVector());
	}

	@Test
	public void getEmbeddingVector_shouldThrowForCorruptedData() {
		ChartEmbedding ce = new ChartEmbedding();
		ce.setEmbedding(new byte[] { 0x01, 0x02, 0x03 }); // 3 bytes, not a multiple of 4

		assertThrows(IllegalStateException.class, () -> ce.getEmbeddingVector());
	}

	@Test
	public void setEmbeddingVector_shouldHandleNullVector() {
		ChartEmbedding ce = new ChartEmbedding();
		ce.setEmbeddingVector(new float[] { 1.0f });
		ce.setEmbeddingVector(null);

		assertNull(ce.getEmbedding());
		assertArrayEquals(new float[0], ce.getEmbeddingVector());
	}

	@Test
	public void setEmbeddingVector_shouldHandleEmptyVector() {
		ChartEmbedding ce = new ChartEmbedding();
		ce.setEmbeddingVector(new float[0]);

		assertEquals(0, ce.getEmbedding().length);
		assertArrayEquals(new float[0], ce.getEmbeddingVector());
	}
}
