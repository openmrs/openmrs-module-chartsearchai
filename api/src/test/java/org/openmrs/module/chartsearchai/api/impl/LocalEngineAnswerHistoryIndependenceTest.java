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
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsUtil;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * An answer must not depend on what the local engine did before it was asked — a chart-open warmup,
 * an earlier question, the same question a moment ago, an evicted entry, a server restart. The live
 * demo answered <em>"Is warfarin safe for her?"</em> one way when the chart had been opened in the UI
 * (whose {@code /warmup} restored the patient's saved KV from disk) and the other way when it had
 * not, every time, with the request bytes identical. ADR Decision 157 records the measurement and the
 * cause: llama-server's prefix reuse is deterministic per path but not equal ACROSS paths, so
 * whatever state the slot happened to be in moved a borderline answer.
 *
 * <p>Drives the composed production path — {@link LlmInferenceService#warmup} and
 * {@link LlmInferenceService#searchStreaming}, with the real {@link ChartBuildingStrategy} over the
 * real {@link QueryStoreChartBuilder}, the real drug-reference injector over the DDInter excerpt,
 * and the Spring-wired {@link LlmProvider} and {@link LocalLlmEngine}, which starts the bundled
 * llama-server itself. Stood in for: querystore (serving {@link TestDatasetHelper#FULL_PATIENT_DATASET},
 * as in {@code EndedOrderAnswerRuleTest}), the drug-safety validator (its chips are not what is
 * compared), and grounding and the progressive preview, switched off.
 *
 * <p><b>Skipped unless opted into</b>, because it needs a model file:
 * {@code -Dchartsearchai.test.localEngine=true -Dchartsearchai.test.localEngine.model=<gguf>}, and
 * optionally {@code -Dchartsearchai.test.localEngine.binary=<llama-server>} to run a given build in
 * place of the bundled one. The assertion is on the whole streamed output, reasoning included — the
 * strictest observable the engine exposes, and the one that reddened before the fix.
 */
public class LocalEngineAnswerHistoryIndependenceTest extends BaseModuleContextSensitiveTest {

	private static final String ENABLE_PROPERTY = "chartsearchai.test.localEngine";

	private static final String MODEL_PROPERTY = "chartsearchai.test.localEngine.model";

	private static final String BINARY_PROPERTY = "chartsearchai.test.localEngine.binary";

	/** A drug question: the module appends reference records to the chart before the model sees it. */
	private static final String DRUG_QUESTION = "Is warfarin safe for her?";

	/** A question the module appends nothing to. */
	private static final String CHART_QUESTION = "what is the patient's most recent weight?";

	@Autowired
	private LlmProvider llmProvider;

	@Autowired
	private LocalLlmEngine localEngine;

	private Path kvCacheDir;

	private Path modelLink;

	private Path serverWrapper;

	private Path serverCopy;

	private HistoryService service;

	private Patient patient;

	@BeforeEach
	public void setUp() throws IOException {
		LlmEndpointTestSupport.assumeOptedIn(ENABLE_PROPERTY);
		String model = System.getProperty(MODEL_PROPERTY);
		Assumptions.assumeTrue(model != null && new File(model).isFile(),
				"Skipping: set -D" + MODEL_PROPERTY + " to a gguf file");

		kvCacheDir = Files.createTempDirectory("chartsearchai-kv-history");
		setGlobalProperty(ChartSearchAiConstants.GP_LLM_ENGINE, "local");
		// The GP is resolved under the application data directory, so the model is linked in there —
		// HARD-linked, since the module's containment check follows a symbolic link back out.
		Path modelDir = new File(OpenmrsUtil.getApplicationDataDirectory(), "chartsearchai").toPath();
		Files.createDirectories(modelDir);
		modelLink = modelDir.resolve(new File(model).getName());
		Files.deleteIfExists(modelLink);
		Files.createLink(modelLink, new File(model).getAbsoluteFile().toPath());
		setGlobalProperty(ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH, "chartsearchai/" + modelLink.getFileName());
		setGlobalProperty(ChartSearchAiConstants.GP_LLM_SERVER_PORT, String.valueOf(freePort()));
		setGlobalProperty(ChartSearchAiConstants.GP_LLM_KV_CACHE_DIR, kvCacheDir.toString());
		setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		runOnTheCpuAlone();

		CountingQueryStoreStub queryStore = new CountingQueryStoreStub();
		queryStore.stubChart = TestDatasetHelper.toQueryDocuments(TestDatasetHelper.FULL_PATIENT_DATASET);
		TestableBuilder builder = new TestableBuilder(queryStore.asService());
		builder.setChartSerializer(new PatientChartSerializer());
		ChartBuildingStrategy strategy = new ChartBuildingStrategy();
		strategy.setQueryStoreChartBuilder(builder);

		service = new HistoryService();
		service.setChartBuildingStrategy(strategy);
		service.setLlmProvider(llmProvider);
		service.setDrugReferenceInjector(DrugReferenceTestSupport.ddinterInjector());
		service.setDrugSafetyValidator(new DrugSafetyValidator() {

			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
				return Collections.emptyList();
			}
		});
		patient = Context.getPatientService().getPatient(2);
	}

	@AfterEach
	public void tearDown() throws IOException {
		if (localEngine != null) {
			localEngine.close();
		}
		for (Path created : new Path[] { modelLink, serverWrapper, serverCopy }) {
			if (created != null) {
				Files.deleteIfExists(created);
			}
		}
		if (kvCacheDir != null) {
			emptyKvCacheDir();
			Files.deleteIfExists(kvCacheDir);
		}
	}

	@Test
	public void aDrugQuestionIsAnsweredTheSameWhateverTheEngineDidBeforeIt() throws IOException {
		assertAnsweredTheSameWhateverCameBefore(DRUG_QUESTION, true, CHART_QUESTION);
	}

	@Test
	public void aQuestionTheModuleAddsNothingToIsAnsweredTheSameWhateverTheEngineDidBeforeIt() throws IOException {
		assertAnsweredTheSameWhateverCameBefore(CHART_QUESTION, false, DRUG_QUESTION);
	}

	/**
	 * {@code question}, asked after five histories, against the same question on a cold engine with
	 * nothing saved: a chart-open warmup, {@code otherQuestion}, the question itself, an evicted entry
	 * remade with another answer in the slot, and a server restart with the entry on disk.
	 */
	private void assertAnsweredTheSameWhateverCameBefore(String question, boolean carriesReferenceRecords,
			String otherQuestion) throws IOException {
		Asked cold = ask(question);
		// The premises: the model wrote this answer, and the drug case is the one whose prompt carries
		// records the injector appended — without them it is the chart case again and proves nothing.
		assertFalse(cold.answer.isAnsweredByTheModule(), "the premise: the model must answer \"" + question + "\"");
		assertEquals(carriesReferenceRecords, cold.answer.getReferenceSlice().getRecords() > 0,
				"the premise: \"" + question + "\" must " + (carriesReferenceRecords ? "" : "not ")
						+ "carry injected reference records");

		Map<String, String> histories = new LinkedHashMap<String, String>();

		coldStart();
		service.warmup(patient);
		histories.put("after the chart-open warmup the UI fires", ask(question).output);

		ask(otherQuestion);
		histories.put("after a different question on the same chart", ask(question).output);

		histories.put("asked again straight after itself", ask(question).output);

		// The entry evicted (kvCacheMaxEntries) while the server runs on: the query must make it again
		// with the slot holding the last answer, and what it makes may not depend on that.
		ask(otherQuestion);
		emptyKvCacheDir();
		histories.put("after its saved entry was evicted, with another question in the slot", ask(question).output);

		localEngine.close();
		histories.put("after a server restart, with the saved entry on disk", ask(question).output);

		for (Map.Entry<String, String> history : histories.entrySet()) {
			assertEquals(cold.output, history.getValue(),
					"\"" + question + "\" must be answered identically " + history.getKey()
							+ " as on a cold engine with nothing saved — the model output may not depend on "
							+ "the engine's history (ADR Decision 157)");
		}
	}

	/** One answer and the whole streamed output: the reasoning channel and the answer channel. */
	private static final class Asked {

		private final ChartAnswer answer;

		private final String output;

		private Asked(ChartAnswer answer, String output) {
			this.answer = answer;
			this.output = output;
		}
	}

	private Asked ask(String question) {
		StringBuilder answer = new StringBuilder();
		StringBuilder reasoning = new StringBuilder();
		ChartAnswer result = service.searchStreaming(patient, question, answer::append, reasoning::append);
		return new Asked(result, "reasoning: " + reasoning + "\nanswer: " + answer + "\nfinal: " + result.getAnswer());
	}

	/**
	 * Puts a wrapper where {@link LlamaServerBinary#resolve()} looks first, so the engine launches the
	 * bundled server (or {@code -Dchartsearchai.test.localEngine.binary}) with {@code --device none}
	 * appended. The demo host has no GPU, and the divergence was measured on the CPU: on this
	 * machine's Metal backend the same histories happened to agree, so a GPU run would pass with or
	 * without the fix and prove nothing.
	 */
	private void runOnTheCpuAlone() throws IOException {
		Path binDir = new File(OpenmrsUtil.getApplicationDataDirectory(), "chartsearchai/bin").toPath();
		Files.createDirectories(binDir);
		Path wrapper = binDir.resolve("llama-server");
		Files.deleteIfExists(wrapper);
		String server = System.getProperty(BINARY_PROPERTY);
		if (server == null || server.trim().isEmpty()) {
			Path bundled = Paths.get(LlamaServerBinary.resolve());
			serverCopy = binDir.resolve("llama-server.bundled");
			Files.move(bundled, serverCopy, StandardCopyOption.REPLACE_EXISTING);
			server = serverCopy.toString();
		}
		Files.write(wrapper, ("#!/bin/sh\nexec '" + server + "' \"$@\" --device none\n")
				.getBytes(StandardCharsets.UTF_8));
		Assumptions.assumeTrue(wrapper.toFile().setExecutable(true), "Skipping: cannot mark the wrapper executable");
		serverWrapper = wrapper;
	}

	/** A stopped server and no saved entry: what a never-opened patient meets on a fresh process. */
	private void coldStart() throws IOException {
		localEngine.close();
		emptyKvCacheDir();
	}

	private void emptyKvCacheDir() throws IOException {
		File[] files = kvCacheDir.toFile().listFiles();
		if (files != null) {
			for (File f : files) {
				Files.deleteIfExists(f.toPath());
			}
		}
	}

	private static void setGlobalProperty(String property, String value) {
		GlobalProperty gp = Context.getAdministrationService().getGlobalPropertyObject(property);
		if (gp == null) {
			gp = new GlobalProperty(property, value);
		}
		else {
			gp.setPropertyValue(value);
		}
		Context.getAdministrationService().saveGlobalProperty(gp);
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	/** The production service with warmup on, and grounding and the progressive preview off. */
	private static final class HistoryService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return true;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
			return false;
		}

		@Override
		protected boolean resolveProgressiveReasoningEnabled() {
			return false;
		}

		@Override
		protected boolean resolveQueryScopedMode() {
			return false;
		}
	}

	private static final class TestableBuilder extends QueryStoreChartBuilder {

		private final QueryStoreService stub;

		TestableBuilder(QueryStoreService stub) {
			this.stub = stub;
		}

		@Override
		protected QueryStoreService resolveQueryStoreService() {
			return stub;
		}

		@Override
		protected int resolveQueryStoreTopK() {
			return 100;
		}

		@Override
		protected boolean resolveUsePreFilter() {
			return false;
		}

		@Override
		protected boolean resolveDedupGroupLabels() {
			return false;
		}

		@Override
		protected int resolveProgressiveReasoningTopK() {
			return 10;
		}
	}
}
