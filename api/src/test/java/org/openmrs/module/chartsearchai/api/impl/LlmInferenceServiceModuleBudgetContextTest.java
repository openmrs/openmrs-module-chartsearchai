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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartTooLargeException;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.querystore.QueryStoreConstants;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.api.impl.QueryStoreServiceImpl;
import org.openmrs.module.querystore.backend.BackendStore;
import org.openmrs.module.querystore.backend.BackendStoreSelector;
import org.openmrs.module.querystore.backend.JdbcSupport;
import org.openmrs.module.querystore.backend.lucene.LuceneBackendStore;
import org.openmrs.module.querystore.bootstrap.BootstrapServiceImpl;
import org.openmrs.module.querystore.bootstrap.BootstrapStatus;
import org.openmrs.module.querystore.embedding.EmbeddingProvider;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.util.ReflectionTestUtils;

/** Real QueryStore backfill/retrieval, chart assembly and safety composition; only the model
 * token counter is a boundary recorder. A deterministic answer must never ask it for a prompt. */
public class LlmInferenceServiceModuleBudgetContextTest extends BaseModuleContextSensitiveTest {

	@Autowired private ChartBuildingStrategy strategy;
	@Autowired @Qualifier("queryStoreService") private QueryStoreServiceImpl queryStore;
	@Autowired @Qualifier("bootstrapService") private BootstrapServiceImpl bootstrap;
	@Autowired private DbSessionFactory dbSessionFactory;
	@TempDir Path indexRoot;

	private LuceneBackendStore backend;
	private BackendStore previousBackend;
	private EmbeddingProvider previousEmbedding;
	private EmbeddingProvider previousBootstrapEmbedding;
	private BackendStoreSelector previousSelector;
	private String databaseMode;

	@BeforeEach
	public void loadRealChart() throws Exception {
		executeDataSet("AnswerFromFindingsWarfarinOrderTestData.xml");
		databaseMode = JdbcSupport.inTransaction(dbSessionFactory, conn -> {
			try (java.sql.Statement statement = conn.createStatement()) {
				String mode;
				try (java.sql.ResultSet result = statement.executeQuery(
						"SELECT VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE NAME='MODE'")) {
					result.next();
					mode = result.getString(1);
				}
				statement.execute("SET MODE MySQL");
				statement.execute("CREATE TABLE IF NOT EXISTS querystore_bootstrap_progress ("
						+ "resource_type VARCHAR(100) PRIMARY KEY, status VARCHAR(30), cursor_date_changed TIMESTAMP,"
						+ "cursor_uuid VARCHAR(100), documents_indexed BIGINT, started_at TIMESTAMP, completed_at TIMESTAMP,"
						+ "failure_message VARCHAR(4096), backend VARCHAR(30))");
				return mode;
			}
		});
		previousBackend = (BackendStore) ReflectionTestUtils.getField(queryStore, "backend");
		previousEmbedding = (EmbeddingProvider) ReflectionTestUtils.getField(queryStore, "embeddingProvider");
		previousBootstrapEmbedding = (EmbeddingProvider) ReflectionTestUtils.getField(bootstrap, "embeddingProvider");
		previousSelector = (BackendStoreSelector) ReflectionTestUtils.getField(bootstrap, "backendSelector");
		backend = new LuceneBackendStore(indexRoot);
		queryStore.setBackend(backend);
		queryStore.setEmbeddingProvider(null);
		bootstrap.setEmbeddingProvider(null);
		bootstrap.setBackendSelector(new BackendStoreSelector(Collections.singletonMap("lucene", backend)));
		Context.getAdministrationService().setGlobalProperty(QueryStoreConstants.GP_BACKEND, "lucene");
		for (String type : bootstrap.getResourceTypeNames()) {
			bootstrap.resyncType(type);
		}
		for (String type : bootstrap.getResourceTypeNames()) {
			assertEquals(BootstrapStatus.COMPLETED, bootstrap.getStatus(type).getStatus(), type);
		}
		QueryStoreService actual = Context.getService(QueryStoreService.class);
		assertTrue(actual.getPatientChartRead(Context.getPatientService().getPatient(7).getUuid()).isProjectionComplete());
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_CHART_MODE,
				ChartSearchAiConstants.CHART_MODE_FULL_CHART);
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_EMBEDDING_PRE_FILTER, "false");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@AfterEach
	public void restoreServices() throws Exception {
		if (backend != null) {
			queryStore.setBackend(previousBackend);
			queryStore.setEmbeddingProvider(previousEmbedding);
			bootstrap.setEmbeddingProvider(previousBootstrapEmbedding);
			bootstrap.setBackendSelector(previousSelector);
			backend.close();
		}
		if (databaseMode != null) {
			JdbcSupport.inTransaction(dbSessionFactory, conn -> {
				try (java.sql.Statement statement = conn.createStatement()) {
					statement.execute("SET MODE " + databaseMode);
				}
				return null;
			});
		}
	}

	private LlmInferenceService service(TokenCounter counter) {
		LlmInferenceService service = new LlmInferenceService();
		service.setChartBuildingStrategy(strategy);
		DrugReferenceService reference = DrugReferenceTestSupport.ddinterServiceWithGroups();
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(reference));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(reference));
		service.setTokenCounter(counter);
		return service;
	}

	private LlmInferenceService service() {
		return service(new TokenCounter() {
			@Override public boolean isAvailable() { return true; }
			@Override public int count(String text) { throw new AssertionError("No model tokenization is needed"); }
			@Override public int countPrompt(String records, String question) { throw new AssertionError("No model prompt is needed"); }
			@Override public int countPrompt(String records, List<Integer> focus, String question) { throw new AssertionError("No model prompt is needed"); }
			@Override public int inputBudget() { return 1; }
		});
	}

	@Test
	public void finalPromptBudgetIncludesInjectedReferenceMaterialAndQuestion() {
		assertBudgetRejected(false);
	}

	@Test
	public void blockingSearchRejectsAnInjectedPromptBeforeCallingTheLlm() {
		assertBudgetRejected(false);
	}

	@Test
	public void streamingSearchRejectsAnInjectedPromptBeforeCallingTheLlm() {
		assertBudgetRejected(true);
	}

	private void assertBudgetRejected(boolean streaming) {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "false");
		List<String> measured = new ArrayList<>();
		LlmInferenceService service = service(new TokenCounter() {
			@Override public boolean isAvailable() { return true; }
			@Override public int count(String text) { throw new AssertionError("Must count the composed prompt"); }
			@Override public int countPrompt(String records, String question) { throw new AssertionError("Must carry the focus indices"); }
			@Override public int countPrompt(String records, List<Integer> focus, String question) {
				measured.add(records);
				measured.add(focus.toString());
				measured.add(question);
				return 101;
			}
			@Override public int inputBudget() { return 100; }
		});
		String question = "Can I give her clarithromycin?";
		assertThrows(ChartTooLargeException.class, () -> {
			if (streaming) {
				service.searchStreaming(Context.getPatientService().getPatient(7), question,
						token -> { throw new AssertionError("No answer may stream after prompt-budget failure"); });
			} else {
				service.search(Context.getPatientService().getPatient(7), question);
			}
		});
		assertEquals(3, measured.size(), "one composed prompt is counted before any model call");
		assertTrue(measured.get(0).contains("Warfarin"), measured.get(0));
		assertTrue(measured.get(0).contains("clarithromycin"), measured.get(0));
		assertTrue(measured.get(0).contains("Drug reference — Clarithromycin (ATC J01FA09)"), measured.get(0));
		assertEquals("[]", measured.get(1), "full-chart mode carries no focus subset");
		assertEquals(question, measured.get(2));
	}

	@Test
	public void blockingModuleAnswerDoesNotRequireAModelPromptBudget() {
		ChartAnswer answer = service().search(Context.getPatientService().getPatient(7), "Can I give her clarithromycin?");
		assertTrue(answer.isAnsweredByTheModule());
		assertTrue(answer.getAnswer().startsWith("No"), answer.getAnswer());
		assertFalse(answer.getReferences().isEmpty());
	}

	@Test
	public void streamingModuleAnswerDoesNotRequireAModelPromptBudget() {
		StringBuilder shown = new StringBuilder();
		ChartAnswer answer = service().searchStreaming(Context.getPatientService().getPatient(7),
				"Can I give her clarithromycin?", shown::append, chunk -> { });
		assertTrue(answer.isAnsweredByTheModule());
		assertEquals(answer.getAnswer(), shown.toString());
		assertFalse(answer.getReferences().isEmpty());
	}
}
