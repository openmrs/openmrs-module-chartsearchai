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
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.InsufficientContextException;
import org.openmrs.module.chartsearchai.api.provider.CancellationSignal;
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
	@Autowired @Qualifier("chartSearchAi.queryStoreChartBuilder") private QueryStoreChartBuilder chartBuilder;
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

	// A llama-server build without the count route answers every count with an error. That is no count, as an
	// unavailable counter is, so the answer is produced unbudgeted rather than failed (TokenCounter.countPrompt).
	@Test
	public void blockingSearchAnswersUnbudgetedWhereTheCountFails() {
		assertAnsweredDespiteAFailedCount(false);
	}

	@Test
	public void streamingSearchAnswersUnbudgetedWhereTheCountFails() {
		assertAnsweredDespiteAFailedCount(true);
	}

	private void assertAnsweredDespiteAFailedCount(boolean streaming) {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "false");
		List<String> prompts = new ArrayList<>();
		ask(new TokenCounter() {
			@Override public boolean isAvailable() { return true; }
			@Override public int countPrompt(String records, List<Integer> focus, String question,
					boolean enumerateFindings, List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
				throw new org.openmrs.api.APIException("Local chat input token count returned HTTP 404");
			}
			@Override public int inputBudget() { return 100; }
		}, prompts, "Can I give her clarithromycin?", streaming);
		assertEquals(1, prompts.size(), "the model is asked once, with the chart as assembled");
		assertTrue(prompts.get(0).contains("Warfarin"), prompts.get(0));
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

	/** Production hands the provider the router, not the inference service: its overload must forward the signal. */
	@Test
	public void cancellingAStalledModelThroughTheRouterReleasesTheRequest() throws Exception {
		assertModelCancellation(true, true, "0");
	}

	/** With caching on, a fresh router misses its cache and reaches the model on the caching path. */
	@Test
	public void cancellingAStalledModelThroughTheCachingRouterReleasesTheRequest() throws Exception {
		assertModelCancellation(true, true, "5");
	}

	/**
	 * Grounding's entailment check calls the model after the answer, so a cancel while that request stalls must
	 * release the request as the answer call's cancel does, not wait out the peer.
	 */
	@Test
	public void cancellingAStalledEntailmentCheckReleasesTheRequest() throws Exception {
		TurnCancellation cancellation = new TurnCancellation();
		AtomicBoolean reachedEntailment = new AtomicBoolean();
		CountDownLatch release = new CountDownLatch(1);
		HttpServer server = HttpServer.create(new InetSocketAddress(LlamaServerEndpoint.LOOPBACK_HOST, 0), 0);
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.createContext("/v1/chat/completions", exchange -> {
			try {
				String request = new String(exchange.getRequestBody().readAllBytes(),
						java.nio.charset.StandardCharsets.UTF_8);
				byte[] body;
				if (request.contains("fact-checker")) {
					reachedEntailment.set(true);
					cancellation.cancel();
					release.await(10, TimeUnit.SECONDS);
					return;
				}
				if (request.contains("\"stream\":true")) {
					exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
					body = ("data: {\"choices\":[{\"delta\":{\"content\":"
							+ "\"{\\\"answer\\\":\\\"Her warfarin order is recorded [1].\\\",\\\"citations\\\":[1]}\"}}]}\n\n"
							+ "data: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
				}
				else {
					exchange.getResponseHeaders().add("Content-Type", "application/json");
					body = "{\"choices\":[{\"message\":{\"content\":\"OTHER\"}}]}"
							.getBytes(java.nio.charset.StandardCharsets.UTF_8);
				}
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		server.start();
		try {
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "false");
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_GROUNDING_ENABLED, "true");
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_GROUNDING_ENTAILMENT_ENABLED, "true");
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_ENGINE, "remote");
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_MODEL_NAME, "test-model");
			Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_LLM_REMOTE_ENDPOINT_URL,
					"http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/v1/chat/completions");
			LlmInferenceService service = service(null);
			LlmProvider llm = new LlmProvider();
			ReflectionTestUtils.setField(llm, "remoteEngine", new RemoteLlmEngine());
			service.setLlmProvider(llm);
			CitationGroundingVerifier verifier = new CitationGroundingVerifier();
			verifier.setLlmProvider(llm);
			service.setCitationGroundingVerifier(verifier);
			long start = System.nanoTime();
			TurnResult result = new BundledClinicalAnswerProvider(service).execute(
					new TurnRequest(Context.getPatientService().getPatient(7), "What anticoagulant is she on?",
							"conversation", "request", null), event -> { }, cancellation).toCompletableFuture().join();
			assertTrue(reachedEntailment.get(), "exercise the real entailment request after the answer");
			assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 6000,
					"cancellation must return before the stalled peer is released");
			// Cancelled after its answer was shown, the turn keeps that answer as its outcome.
			assertEquals(org.openmrs.module.chartsearchai.api.provider.TurnEventType.TURN_DONE, result.getTerminalState());
			assertEquals("Her warfarin order is recorded [1].", result.getAnswer().getText());
			assertFalse(Thread.currentThread().isInterrupted(), "do not leak cancellation into request cleanup");
		} finally {
			release.countDown();
			server.stop(0);
		}
	}

	/**
	 * With the drug reference on, a question the router reads no drug in is read by the model before the chart
	 * is answered (ADR Decision 489), so a cancel while that call stalls must release the request too, not wait
	 * out its timeout.
	 */
	@Test
	public void cancellingAStalledSafetyReadingReleasesTheRequest() throws Exception {
		TurnCancellation cancellation = new TurnCancellation();
		AtomicBoolean reachedReading = new AtomicBoolean();
		CountDownLatch release = new CountDownLatch(1);
		HttpServer server = HttpServer.create(new InetSocketAddress(LlamaServerEndpoint.LOOPBACK_HOST, 0), 0);
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.createContext("/v1/chat/completions", exchange -> {
			try {
				String request = new String(exchange.getRequestBody().readAllBytes(),
						java.nio.charset.StandardCharsets.UTF_8);
				if (request.contains("You classify one question a clinician typed")) {
					reachedReading.set(true);
					cancellation.cancel();
					release.await(30, TimeUnit.SECONDS);
					return;
				}
				exchange.sendResponseHeaders(500, -1);
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
					new TurnRequest(Context.getPatientService().getPatient(7), "What is her latest weight?",
							"conversation", "request", null), event -> { }, cancellation).toCompletableFuture().join();
			assertTrue(reachedReading.get(), "exercise the real safety-reading request before the answer");
			assertEquals("cancelled", result.getProblemCode());
			assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 6000,
					"cancellation must return before the stalled peer is released");
			assertFalse(Thread.currentThread().isInterrupted(), "do not leak cancellation into request cleanup");
		} finally {
			release.countDown();
			server.stop(0);
		}
	}

	private void assertModelCancellation(boolean sendHeaders) throws Exception {
		assertModelCancellation(sendHeaders, false, null);
	}

	private void assertModelCancellation(boolean sendHeaders, boolean throughTheRouter, String cacheTtlMinutes)
			throws Exception {
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
			ChartSearchService answering = service;
			if (throughTheRouter) {
				Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_CACHE_TTL_MINUTES,
						cacheTtlMinutes);
				ChartSearchServiceRouter router = new ChartSearchServiceRouter();
				router.setLlmService(service);
				answering = router;
			}
			long start = System.nanoTime();
			TurnResult result = new BundledClinicalAnswerProvider(answering).execute(
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
	public void blockingScopedSliceGivesUpOptionalRecordsSoTheInjectedReferenceFits() {
		assertOptionalRecordsGiveWayToInjectedReferences(false);
	}

	@Test
	public void streamingScopedSliceGivesUpOptionalRecordsSoTheInjectedReferenceFits() {
		assertOptionalRecordsGiveWayToInjectedReferences(true);
	}

	/**
	 * The scoped builder budgets the slice before the injector appends this module's reference
	 * records, which ADR Decision 57 leaves uncapped. Here the slice alone fits and the composed
	 * prompt is one token over: the answer must still go to the model, with optional slice records
	 * given up and the injected drug reference kept, rather than being refused.
	 */
	private void assertOptionalRecordsGiveWayToInjectedReferences(boolean streaming) {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "false");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_CHART_MODE,
				ChartSearchAiConstants.CHART_MODE_QUERY_SCOPED);
		// "now" is a temporal cue, so QueryStore adds her newest records as the optional recency anchor.
		String question = "Can I give her clarithromycin now?";

		List<Integer> unbounded = new ArrayList<>();
		List<String> unboundedPrompts = new ArrayList<>();
		ask(lengthCounted(Integer.MAX_VALUE, unbounded), unboundedPrompts, question, streaming);
		assertEquals(2, unbounded.size(), "precondition: the slice is counted once, then the composed prompt");
		int sliceAlone = unbounded.get(0);
		int composed = unbounded.get(1);
		assertTrue(unboundedPrompts.get(0).contains("Drug reference — Clarithromycin (ATC J01FA09)"),
				"precondition: the injector appended the drug reference\n" + unboundedPrompts.get(0));
		assertTrue(sliceAlone < composed - 1, "precondition: the slice alone fits a budget the composed prompt exceeds");

		int budget = composed - 1;
		List<Integer> bounded = new ArrayList<>();
		List<String> prompts = new ArrayList<>();
		ask(lengthCounted(budget, bounded), prompts, question, streaming);

		assertEquals(1, prompts.size(), "the answer goes to the model instead of being refused");
		assertTrue(bounded.get(bounded.size() - 1) <= budget, "the prompt the model reads fits: " + bounded);
		assertTrue(prompts.get(0).contains("Drug reference — Clarithromycin (ATC J01FA09)"),
				"the injected reference is kept\n" + prompts.get(0));
		assertTrue(prompts.get(0).length() < unboundedPrompts.get(0).length(),
				"optional slice records gave way to the reference");
	}

	/**
	 * Without a temporal cue this slice carries only protected records, so nothing can give way:
	 * references that do not fit beside them are the explicit context limit, and no injected record is
	 * dropped to make room.
	 */
	@Test
	public void aScopedSliceWithNothingOptionalRefusesInjectedReferencesThatDoNotFit() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "false");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_CHART_MODE,
				ChartSearchAiConstants.CHART_MODE_QUERY_SCOPED);
		String question = "Can I give her clarithromycin?";
		List<Integer> unbounded = new ArrayList<>();
		ask(lengthCounted(Integer.MAX_VALUE, unbounded), new ArrayList<>(), question, false);
		int composed = unbounded.get(unbounded.size() - 1);

		List<String> prompts = new ArrayList<>();
		assertThrows(InsufficientContextException.class,
				() -> ask(lengthCounted(composed - 1, new ArrayList<>()), prompts, question, false));
		assertTrue(prompts.isEmpty(), "no prompt reaches the model");
	}

	/** Counts a prompt as its characters, plus fixed costs for the two clauses production adds, and
	 *  records every count in call order. */
	private static TokenCounter lengthCounted(int budget, List<Integer> counts) {
		return new TokenCounter() {
			@Override public boolean isAvailable() { return true; }
			@Override public int countPrompt(String records, List<Integer> focus, String question,
					boolean enumerateFindings, List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
				int count = records.length() + question.length() + (enumerateFindings ? 50 : 0)
						+ 20 * drugsAlreadyOrdered.size();
				counts.add(count);
				return count;
			}
			@Override public int inputBudget() { return budget; }
		};
	}

	private void ask(TokenCounter counter, List<String> prompts, String question, boolean streaming) {
		// The builder and the service share one counter in production; the builder is a singleton,
		// so the one it held is put back.
		TokenCounter previous = (TokenCounter) ReflectionTestUtils.getField(chartBuilder, "tokenCounter");
		chartBuilder.setTokenCounter(counter);
		try {
			askWith(counter, prompts, question, streaming);
		} finally {
			chartBuilder.setTokenCounter(previous);
		}
	}

	private void askWith(TokenCounter counter, List<String> prompts, String question, boolean streaming) {
		LlmInferenceService service = service(counter);
		service.setLlmProvider(new LlmProvider() {
			@Override
			public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String asked,
					String cacheScope, String cacheSeedRecords, boolean enumerateFindings,
					LlmEngine.ReferenceRecords referenceRecords, List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
				prompts.add(numberedRecords);
				return new LlmResponse("Clarithromycin interacts with her warfarin.", Collections.<Integer>emptyList());
			}

			@Override
			public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices, String asked,
					Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer, String cacheScope,
					String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
					List<AlreadyOrderedDrug> drugsAlreadyOrdered, CancellationSignal cancellation) {
				prompts.add(numberedRecords);
				return new LlmResponse("Clarithromycin interacts with her warfarin.", Collections.<Integer>emptyList());
			}
		});
		if (streaming) {
			service.searchStreaming(Context.getPatientService().getPatient(7), question, token -> { });
		} else {
			service.search(Context.getPatientService().getPatient(7), question);
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
