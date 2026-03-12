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
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.test.util.ReflectionTestUtils;

public class ChartSearchServiceRouterTest extends BaseModuleContextSensitiveTest {

	private static final String TEST_DATA = "ChartSearchAiTestData.xml";

	private ChartSearchServiceRouter router;

	private StubChartSearchService llmStub;

	private StubChartSearchService embeddingStub;

	private Patient patient;

	@BeforeEach
	public void setUp() throws Exception {
		executeDataSet(TEST_DATA);
		patient = Context.getPatientService().getPatient(2);

		router = new ChartSearchServiceRouter();

		llmStub = new StubChartSearchService("llm answer");
		embeddingStub = new StubChartSearchService("embedding answer");

		ReflectionTestUtils.setField(router, "llmService", llmStub);
		ReflectionTestUtils.setField(router, "embeddingService", embeddingStub);
	}

	@Test
	public void ask_shouldDelegateToLlmServiceByDefault() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_SEARCH_MODE, "llm");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_CACHE_TTL_MINUTES, "0");

		ChartAnswer answer = router.ask(patient, "What medications?");
		assertEquals("llm answer", answer.getAnswer());
		assertEquals(1, llmStub.callCount);
		assertEquals(0, embeddingStub.callCount);
	}

	@Test
	public void ask_shouldDelegateToEmbeddingServiceWhenConfigured() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_SEARCH_MODE, "embedding");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_CACHE_TTL_MINUTES, "0");

		ChartAnswer answer = router.ask(patient, "What medications?");
		assertEquals("embedding answer", answer.getAnswer());
		assertEquals(0, llmStub.callCount);
		assertEquals(1, embeddingStub.callCount);
	}

	@Test
	public void ask_shouldReturnCachedAnswerOnSecondCall() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_SEARCH_MODE, "llm");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_CACHE_TTL_MINUTES, "5");

		router.ask(patient, "What medications?");
		ChartAnswer second = router.ask(patient, "What medications?");

		assertEquals("llm answer", second.getAnswer());
		assertEquals(1, llmStub.callCount);
	}

	@Test
	public void ask_shouldNotCacheWhenTtlIsZero() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_SEARCH_MODE, "llm");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_CACHE_TTL_MINUTES, "0");

		router.ask(patient, "What medications?");
		router.ask(patient, "What medications?");

		assertEquals(2, llmStub.callCount);
	}

	@Test
	public void ask_shouldTreatDifferentQuestionsAsDifferentCacheKeys() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_SEARCH_MODE, "llm");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_CACHE_TTL_MINUTES, "5");

		router.ask(patient, "What medications?");
		router.ask(patient, "What allergies?");

		assertEquals(2, llmStub.callCount);
	}

	@Test
	public void ask_shouldBeCaseInsensitiveForCacheKey() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_SEARCH_MODE, "llm");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_CACHE_TTL_MINUTES, "5");

		router.ask(patient, "What Medications?");
		router.ask(patient, "what medications?");

		assertEquals(1, llmStub.callCount);
	}

	private static class StubChartSearchService implements ChartSearchService {

		private final String responseText;

		int callCount = 0;

		StubChartSearchService(String responseText) {
			this.responseText = responseText;
		}

		@Override
		public ChartAnswer ask(Patient patient, String question) {
			callCount++;
			return new ChartAnswer(responseText,
					Collections.<RecordReference>emptyList());
		}

		@Override
		public ChartAnswer askStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			callCount++;
			tokenConsumer.accept(responseText);
			return new ChartAnswer(responseText,
					Collections.<RecordReference>emptyList());
		}
	}
}
