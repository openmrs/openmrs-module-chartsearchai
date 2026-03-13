/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure unit tests for {@link ChartSearchServiceRouter} — no OpenMRS Context required.
 */
public class ChartSearchServiceRouterTest {

	private Patient patient;

	@BeforeEach
	public void setUp() {
		patient = new Patient();
		patient.setUuid("test-patient-uuid");
	}

	@Test
	public void search_shouldDelegateToLlmByDefault() {
		StubService llmStub = new StubService("llm answer");
		StubService embeddingStub = new StubService("embedding answer");
		ChartSearchServiceRouter router = createRouter(llmStub, embeddingStub, "llm", 0);

		ChartAnswer answer = router.search(patient, "What medications?");
		assertEquals("llm answer", answer.getAnswer());
		assertEquals(1, llmStub.callCount);
		assertEquals(0, embeddingStub.callCount);
	}

	@Test
	public void search_shouldDelegateToEmbeddingWhenConfigured() {
		StubService llmStub = new StubService("llm answer");
		StubService embeddingStub = new StubService("embedding answer");
		ChartSearchServiceRouter router = createRouter(llmStub, embeddingStub, "embedding", 0);

		ChartAnswer answer = router.search(patient, "What medications?");
		assertEquals("embedding answer", answer.getAnswer());
		assertEquals(1, embeddingStub.callCount);
	}

	@Test
	public void search_shouldCacheAnswers() {
		StubService llmStub = new StubService("llm answer");
		ChartSearchServiceRouter router = createRouter(llmStub, new StubService(""), "llm", 5);

		router.search(patient, "What medications?");
		router.search(patient, "What medications?");

		assertEquals(1, llmStub.callCount);
	}

	@Test
	public void search_shouldNotCacheWhenTtlIsZero() {
		StubService llmStub = new StubService("llm answer");
		ChartSearchServiceRouter router = createRouter(llmStub, new StubService(""), "llm", 0);

		router.search(patient, "What medications?");
		router.search(patient, "What medications?");

		assertEquals(2, llmStub.callCount);
	}

	@Test
	public void search_shouldBeCaseInsensitiveForCacheKey() {
		StubService llmStub = new StubService("llm answer");
		ChartSearchServiceRouter router = createRouter(llmStub, new StubService(""), "llm", 5);

		router.search(patient, "What Medications?");
		router.search(patient, "what medications?");

		assertEquals(1, llmStub.callCount);
	}

	@Test
	public void searchStreaming_shouldDelegateAndPassTokens() {
		final StringBuilder received = new StringBuilder();
		StubService llmStub = new StubService("streamed answer");
		ChartSearchServiceRouter router = createRouter(llmStub, new StubService(""), "llm", 0);

		ChartAnswer answer = router.searchStreaming(patient, "What medications?",
				new java.util.function.Consumer<String>() {
					@Override
					public void accept(String token) {
						received.append(token);
					}
				});

		assertEquals("streamed answer", answer.getAnswer());
		assertEquals("streamed answer", received.toString());
		assertEquals(1, llmStub.callCount);
	}

	@Test
	public void searchStreaming_shouldCacheAnswers() {
		StubService llmStub = new StubService("streamed");
		ChartSearchServiceRouter router = createRouter(llmStub, new StubService(""), "llm", 5);

		router.searchStreaming(patient, "What medications?",
				new java.util.function.Consumer<String>() {
					@Override
					public void accept(String token) {}
				});
		final StringBuilder secondCall = new StringBuilder();
		router.searchStreaming(patient, "What medications?",
				new java.util.function.Consumer<String>() {
					@Override
					public void accept(String token) {
						secondCall.append(token);
					}
				});

		assertEquals(1, llmStub.callCount);
		assertEquals("streamed", secondCall.toString());
	}

	@Test
	public void search_shouldUseSeparateCacheKeysForDifferentQuestions() {
		StubService llmStub = new StubService("answer");
		ChartSearchServiceRouter router = createRouter(llmStub, new StubService(""), "llm", 5);

		router.search(patient, "What medications?");
		router.search(patient, "What allergies?");

		assertEquals(2, llmStub.callCount);
	}

	private ChartSearchServiceRouter createRouter(ChartSearchService llm, ChartSearchService embedding,
			final String mode, final int cacheTtl) {
		ChartSearchServiceRouter router = new ChartSearchServiceRouter() {

			@Override
			protected ChartSearchService getDelegate() {
				if ("embedding".equalsIgnoreCase(mode)) {
					return embedding;
				}
				return llm;
			}

			@Override
			protected int getCacheTtlMinutes() {
				return cacheTtl;
			}
		};
		ReflectionTestUtils.setField(router, "llmService", llm);
		ReflectionTestUtils.setField(router, "embeddingService", embedding);
		return router;
	}

	private static class StubService implements ChartSearchService {

		final String responseText;

		int callCount = 0;

		StubService(String responseText) {
			this.responseText = responseText;
		}

		@Override
		public ChartAnswer search(Patient patient, String question) {
			callCount++;
			return new ChartAnswer(responseText,
					Collections.<RecordReference>emptyList());
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			callCount++;
			tokenConsumer.accept(responseText);
			return new ChartAnswer(responseText,
					Collections.<RecordReference>emptyList());
		}
	}
}
