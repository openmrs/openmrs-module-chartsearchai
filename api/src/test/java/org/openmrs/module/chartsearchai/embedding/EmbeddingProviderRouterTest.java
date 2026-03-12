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

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure unit tests for {@link EmbeddingProviderRouter} — no OpenMRS Context required.
 */
public class EmbeddingProviderRouterTest {

	@Test
	public void embed_shouldDelegateToOnnxWhenConfigured() {
		float[] expected = { 0.1f, 0.2f };
		StubProvider onnx = new StubProvider(expected, 384);
		StubProvider tf = new StubProvider(new float[] { 0.9f }, 256);

		EmbeddingProviderRouter router = createRouter(onnx, tf, "onnx");

		assertArrayEquals(expected, router.embed("test"));
		assertEquals(1, onnx.callCount);
		assertEquals(0, tf.callCount);
	}

	@Test
	public void embed_shouldDelegateToTermFrequencyByDefault() {
		float[] expected = { 0.5f, 0.6f };
		StubProvider onnx = new StubProvider(new float[] { 0.1f }, 384);
		StubProvider tf = new StubProvider(expected, 256);

		EmbeddingProviderRouter router = createRouter(onnx, tf, "term-frequency");

		assertArrayEquals(expected, router.embed("test"));
		assertEquals(0, onnx.callCount);
		assertEquals(1, tf.callCount);
	}

	@Test
	public void embed_shouldDefaultToTermFrequencyForNullProvider() {
		StubProvider onnx = new StubProvider(new float[] { 0.1f }, 384);
		StubProvider tf = new StubProvider(new float[] { 0.5f }, 256);

		EmbeddingProviderRouter router = createRouter(onnx, tf, null);

		router.embed("test");
		assertEquals(0, onnx.callCount);
		assertEquals(1, tf.callCount);
	}

	@Test
	public void getDimensions_shouldReturnDimensionsFromActiveProvider() {
		StubProvider onnx = new StubProvider(new float[0], 384);
		StubProvider tf = new StubProvider(new float[0], 256);

		assertEquals(384, createRouter(onnx, tf, "onnx").getDimensions());
		assertEquals(256, createRouter(onnx, tf, "term-frequency").getDimensions());
	}

	private EmbeddingProviderRouter createRouter(EmbeddingProvider onnx, EmbeddingProvider tf,
			final String provider) {
		EmbeddingProviderRouter router = new EmbeddingProviderRouter() {
			@Override
			protected EmbeddingProvider getProvider() {
				if ("onnx".equalsIgnoreCase(provider)) {
					return onnx;
				}
				return tf;
			}
		};
		ReflectionTestUtils.setField(router, "onnxProvider", onnx);
		ReflectionTestUtils.setField(router, "termFrequencyProvider", tf);
		return router;
	}

	private static class StubProvider implements EmbeddingProvider {

		final float[] vector;
		final int dimensions;
		int callCount = 0;

		StubProvider(float[] vector, int dimensions) {
			this.vector = vector;
			this.dimensions = dimensions;
		}

		@Override
		public float[] embed(String text) {
			callCount++;
			return vector;
		}

		@Override
		public int getDimensions() {
			return dimensions;
		}
	}
}
