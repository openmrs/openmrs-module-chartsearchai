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
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;
import org.openmrs.module.chartsearchai.api.provider.BundledClinicalAnswerProvider;
import org.openmrs.module.chartsearchai.api.provider.TurnCancellation;
import org.openmrs.module.chartsearchai.api.provider.TurnRequest;
import org.openmrs.module.chartsearchai.api.provider.TurnResult;
import org.openmrs.module.querystore.backend.PatientChartRead;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
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

/** Real QueryStore backfill/retrieval, chart assembly and safety composition. Embeddings and
 * token counts are model-boundary doubles; stalled HTTP peers exercise the real LLM transport.
 * No query ranks embedding vectors here. Deterministic answers must never request a model budget. */
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
	private Runnable onChartRead = () -> { };
	private boolean chartReadInterrupted;

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
		backend = new LuceneBackendStore(indexRoot) {
			@Override public PatientChartRead findPatientChart(String patientUuid) {
				onChartRead.run();
				chartReadInterrupted = Thread.currentThread().isInterrupted();
				return super.findPatientChart(patientUuid);
			}
		};
		queryStore.setBackend(backend);
		queryStore.setEmbeddingProvider(null);
		// Full-chart reads do not rank vectors; replace only the embedding-model boundary.
		bootstrap.setEmbeddingProvider(new EmbeddingProvider() {
			@Override public int getDimensions() { return 8; }
			@Override public float[] embed(String text) { return new float[8]; }
		});
		bootstrap.setBackendSelector(new BackendStoreSelector(Collections.singletonMap("lucene", backend)));
		Context.getAdministrationService().setGlobalProperty(QueryStoreConstants.GP_BACKEND, "lucene");
		for (String type : bootstrap.getResourceTypeNames()) {
			bootstrap.resyncType(type);
		}
		for (String type : bootstrap.getResourceTypeNames()) {
			assertEquals(BootstrapStatus.COMPLETED, bootstrap.getStatus(type).getStatus(), type);
		}
		QueryStoreService actual = Context.getService(QueryStoreService.class);
		PatientChartRead indexed = actual.getPatientChartRead(Context.getPatientService().getPatient(7).getUuid());
		assertTrue(indexed.isProjectionComplete());
		assertFalse(indexed.isTruncated());
		assertTrue(indexed.getDocuments().stream().anyMatch(doc ->
				"9469dddd-0000-4000-8000-00000009469a".equals(doc.getResourceUuid())),
				"backfill must index this patient's actual warfarin order");
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
			@Override public int countPrompt(String records, List<Integer> focus, String question, boolean enumerateFindings, List<AlreadyOrderedDrug> drugsAlreadyOrdered) { throw new AssertionError("No model prompt is needed"); }
			@Override public int inputBudget() { return 1; }
		});
	}

	@Test
	public void blockingBudgetIncludesFindingAndAlreadyOrderedDrugClauses() {
		assertBudgetRejected(false, "Can I give her warfarin?", true);
	}

	@Test
	public void streamingBudgetIncludesFindingAndAlreadyOrderedDrugClauses() {
		assertBudgetRejected(true, "Can I give her warfarin?", true);
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
		assertBudgetRejected(streaming, "Can I give her clarithromycin?", false);
	}

	private void assertBudgetRejected(boolean streaming, String question, boolean extraClauses) {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "false");
		List<String> measured = new ArrayList<>();
		LlmInferenceService service = service(new TokenCounter() {
			@Override public boolean isAvailable() { return true; }
			@Override public int countPrompt(String records, List<Integer> focus, String question, boolean enumerateFindings, List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
				measured.add(records);
				measured.add(focus.toString());
				measured.add(question);
				if (extraClauses) {
					assertTrue(enumerateFindings, "count the finding-enumeration clause sent to the model");
					assertFalse(drugsAlreadyOrdered.isEmpty(), "count the clause naming her existing warfarin order");
				}
				return 101;
			}
			@Override public int inputBudget() { return 100; }
		});
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
		if (!extraClauses) {
			assertTrue(measured.get(0).contains("clarithromycin"), measured.get(0));
			assertTrue(measured.get(0).contains("Drug reference — Clarithromycin (ATC J01FA09)"), measured.get(0));
		}
		assertEquals("[]", measured.get(1), "full-chart mode carries no focus subset");
		assertEquals(question, measured.get(2));
	}

	@Test
	public void cancellingDuringChartRetrievalDoesNotInterruptTheSharedIndex() {
		TurnCancellation cancellation = new TurnCancellation();
		onChartRead = cancellation::cancel;
		TurnResult result = new BundledClinicalAnswerProvider(service()).execute(
				new TurnRequest(Context.getPatientService().getPatient(7), "Can I give her clarithromycin?",
						"conversation", "request", null), event -> { }, cancellation).toCompletableFuture().join();
		assertFalse(chartReadInterrupted, "cancellation must never interrupt QueryStore's index read");
		assertEquals("cancelled", result.getProblemCode());
		onChartRead = () -> { };
		PatientChartRead subsequent = backend.findPatientChart(Context.getPatientService().getPatient(7).getUuid());
		assertFalse(subsequent.isTruncated());
		assertFalse(subsequent.getDocuments().isEmpty());
		assertTrue(backend.upsert(subsequent.getDocuments().get(0)).isSucceeded(),
				"the shared writer must remain usable after cancellation");
	}

	@Test
	public void cancellingAStalledModelHeaderWaitReleasesTheRequest() throws Exception {
		assertModelCancellation(false);
	}

	@Test
	public void cancellingAStalledModelBodyReleasesTheRequest() throws Exception {
		assertModelCancellation(true);
	}

	private void assertModelCancellation(boolean sendHeaders) throws Exception {
		TurnCancellation cancellation = new TurnCancellation();
		AtomicBoolean reachedModel = new AtomicBoolean();
		CountDownLatch release = new CountDownLatch(1);
		HttpServer server = HttpServer.create(new InetSocketAddress(LlamaServerEndpoint.LOOPBACK_HOST, 0), 0);
		server.createContext("/v1/chat/completions", exchange -> {
			try {
				exchange.getRequestBody().readAllBytes();
				if (sendHeaders) {
					exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
					exchange.sendResponseHeaders(200, 0);
					exchange.getResponseBody().write(": waiting\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
					exchange.getResponseBody().flush();
				}
				reachedModel.set(true);
				cancellation.cancel();
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		server.start();
		try {
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "false");
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_ENGINE, "remote");
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_MODEL_NAME, "test-model");
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL,
					"http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/v1/chat/completions");
			LlmInferenceService service = service(null);
			LlmProvider llm = new LlmProvider();
			ReflectionTestUtils.setField(llm, "remoteEngine", new RemoteLlmEngine());
			service.setLlmProvider(llm);
			long start = System.nanoTime();
			TurnResult result = new BundledClinicalAnswerProvider(service).execute(
					new TurnRequest(Context.getPatientService().getPatient(7), "Can I give her clarithromycin?",
							"conversation", "request", null), event -> { }, cancellation).toCompletableFuture().join();
			assertTrue(reachedModel.get(), "exercise the real model transport after chart retrieval");
			assertEquals("cancelled", result.getProblemCode());
			assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 4000,
					"cancellation must return before the stalled peer is released");
			assertFalse(Thread.currentThread().isInterrupted(), "do not leak cancellation into request cleanup");
		} finally {
			release.countDown();
			server.stop(0);
		}
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
