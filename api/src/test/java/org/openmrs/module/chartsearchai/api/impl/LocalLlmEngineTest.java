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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LocalLlmEngine} request building.
 */
public class LocalLlmEngineTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final LocalLlmEngine engine = new LocalLlmEngine();

	@Test
	public void buildRequestBody_appliesReasoningCapFromConfiguration() throws IOException {
		LocalLlmEngine capped = new LocalLlmEngine() {

			@Override
			int resolveReasoningMaxChars() {
				return 400;
			}
		};
		String body = capped.buildRequestBody("sys", "usr", false);
		JsonNode props = MAPPER.readTree(body).get("response_format").get("json_schema")
				.get("schema").get("properties");
		assertEquals(400, props.get("reasoning").get("maxLength").asInt(),
				"the configured reasoning cap must reach the request schema");
		assertTrue(props.get("answer").get("maxLength") == null, "answer is never capped");
	}

	@Test
	public void buildRequestBody_omitsReasoningCapByDefault() throws IOException {
		// No OpenMRS context in this test -> the GP resolver fails safe to 0 -> no maxLength.
		String body = engine.buildRequestBody("sys", "usr", false);
		JsonNode reasoning = MAPPER.readTree(body).get("response_format").get("json_schema")
				.get("schema").get("properties").get("reasoning");
		assertTrue(reasoning.get("maxLength") == null,
				"default (cap=0 / no context) must leave the schema uncapped");
	}

	@Test
	public void buildRequestBody_shouldUseJsonSchemaStrictMode() throws IOException {
		String body = engine.buildRequestBody("system prompt", "user message", false);
		JsonNode root = MAPPER.readTree(body);

		JsonNode responseFormat = root.get("response_format");
		assertEquals("json_schema", responseFormat.get("type").asText(),
				"Local engine must use json_schema mode so the model stops at the closing brace "
				+ "instead of looping past a complete answer until max_tokens is hit.");

		JsonNode jsonSchema = responseFormat.get("json_schema");
		assertTrue(jsonSchema.get("strict").asBoolean(),
				"Schema must be strict so the model cannot generate content after the closing brace");

		JsonNode schema = jsonSchema.get("schema");
		assertEquals("object", schema.get("type").asText());
		assertFalse(schema.get("additionalProperties").asBoolean(),
				"additionalProperties must be false to prevent post-answer rambling");

		JsonNode properties = schema.get("properties");
		assertEquals("string", properties.get("reasoning").get("type").asText());
		assertEquals("string", properties.get("answer").get("type").asText());
		assertEquals("array", properties.get("citations").get("type").asText());
		assertEquals("integer", properties.get("citations").get("items").get("type").asText());

		JsonNode required = schema.get("required");
		assertEquals(3, required.size(), "reasoning, answer and citations are all required");
	}

	@Test
	public void buildRequestBody_shouldSetTemperatureZeroAndMaxTokens() throws IOException {
		String body = engine.buildRequestBody("sys", "usr", false);
		JsonNode root = MAPPER.readTree(body);

		assertEquals(0.0, root.get("temperature").asDouble());
		assertTrue(root.get("max_tokens").asInt() > 0);
	}

	@Test
	public void buildRequestBody_shouldIncludeSystemAndUserMessages() throws IOException {
		String body = engine.buildRequestBody("system prompt", "user message", false);
		JsonNode root = MAPPER.readTree(body);

		JsonNode messages = root.get("messages");
		assertEquals(2, messages.size());
		assertEquals("system", messages.get(0).get("role").asText());
		assertEquals("system prompt", messages.get(0).get("content").asText());
		assertEquals("user", messages.get(1).get("role").asText());
		assertEquals("user message", messages.get(1).get("content").asText());
	}

	@Test
	public void buildRequestBody_shouldEnablePromptCaching() throws IOException {
		String body = engine.buildRequestBody("sys", "usr", false);
		JsonNode root = MAPPER.readTree(body);

		assertTrue(root.get("cache_prompt").asBoolean(),
				"cache_prompt must be true so llama-server reuses the chart prefix's KV across "
				+ "successive queries on the same patient — without it, every query reprocesses "
				+ "the whole chart from scratch");
	}

	@Test
	public void buildRequestBody_shouldPinSamplerChainToDryAndTemperature() throws IOException {
		String body = engine.buildRequestBody("sys", "usr", false);
		JsonNode root = MAPPER.readTree(body);

		JsonNode samplers = root.get("samplers");
		assertEquals(2, samplers.size(),
				"at temperature=0 the decode is greedy; the default 9-sampler chain is wasted "
				+ "work per token. Pin samplers to [\"dry\", \"temperature\"] so the only active "
				+ "samplers are DRY (breaks degenerate multi-token loops that small models emit "
				+ "on chart search) and temperature");
		assertEquals("dry", samplers.get(0).asText());
		assertEquals("temperature", samplers.get(1).asText());

		assertEquals(8, root.get("dry_allowed_length").asInt(),
				"dry_allowed_length=8 catches the 10+ token catastrophic loops the small "
				+ "models exhibit on chart search while leaving 4-7 token date n-grams "
				+ "like \"on 2023-05-04 [\" alone. Lower values (e.g. 4) penalize the "
				+ "model for emitting dates that appear repeatedly in chart context, "
				+ "causing digit drift (2023-05-04 -> 2023-05-03) and script-switching");
		assertEquals(-1, root.get("dry_penalty_last_n").asInt(),
				"dry_penalty_last_n=-1 looks at the full sequence for repeats, so a loop "
				+ "started near the prompt end is still caught after generation grows past "
				+ "the default window");
	}

	@Test
	public void buildServerCommand_shouldPinCacheReuseToZero() {
		// --cache-reuse pinned to 0 (llama.cpp's default). The previous value of 256 enabled
		// KV shifting (re-applying RoPE to cached K blocks for fuzzy prefix matching when the
		// prefix bytes drift between requests). With the focus-hint prompt structure the
		// chart prefix is byte-identical across successive queries on the same patient, so
		// fuzzy matching adds nothing — we only pay the per-token RoPE re-application cost.
		// Note: this flag is NOT the determinism lever — cache_prompt in the request body
		// is the real cause of the borderline-argmax flip ("is she pregnant?" alternating
		// between Gravida and Self-Induced Abortion). The flip is fundamental to llama-server's
		// cache_prompt design (reused-vs-fresh KV is numerically close but not bit-identical)
		// and cache_prompt stays on because the latency win is the whole point.
		List<String> cmd = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 9999, 32768);

		int idx = cmd.indexOf("--cache-reuse");
		assertTrue(idx >= 0, "--cache-reuse flag must be present (explicit even at the "
				+ "default) so a future llama.cpp version that changes the default does not "
				+ "silently re-enable KV shifting that this prompt shape doesn't benefit from");
		assertEquals("0", cmd.get(idx + 1),
				"--cache-reuse must be 0 (llama.cpp default); any positive value enables KV "
				+ "shifting which the focus-hint prompt shape does not need");
	}

	@Test
	public void buildRequestBody_shouldEnableUsageWhenStreaming() throws IOException {
		String body = engine.buildRequestBody("sys", "usr", true);
		JsonNode root = MAPPER.readTree(body);

		assertTrue(root.get("stream").asBoolean());
		assertTrue(root.get("stream_options").get("include_usage").asBoolean());
	}

	@Test
	public void buildRequestBody_shouldOmitStreamOptionsWhenNotStreaming() throws IOException {
		String body = engine.buildRequestBody("sys", "usr", false);
		JsonNode root = MAPPER.readTree(body);

		assertFalse(root.get("stream").asBoolean());
		assertTrue(root.path("stream_options").isMissingNode());
	}

	@Test
	public void buildRequestBody_warmupShouldRequestSingleToken() throws IOException {
		String body = engine.buildRequestBody("sys", "usr", false, 1);
		JsonNode root = MAPPER.readTree(body);

		assertEquals(1, root.get("max_tokens").asInt(),
				"warmup must set max_tokens=1 — llama-server still does the prompt prefill "
				+ "(seeding the KV cache) but skips real generation, which is the wasted work");
		assertTrue(root.get("cache_prompt").asBoolean(),
				"warmup must keep cache_prompt=true so the prefilled tokens stay in the slot's "
				+ "KV cache for the next real query to reuse via --cache-reuse");
	}

	@Test
	public void buildRequestBody_warmupBodyMustMatchInferExceptMaxTokens() throws IOException {
		// The whole point of warmup is that the system+user prefix llama-server sees during
		// warmup is byte-identical to what it sees on the next real query — that's how
		// --cache-reuse 256 reuses the KV. If any field other than max_tokens drifts between
		// the two paths, the prefix tokens diverge and reuse fails silently. Deep-equal the
		// full request body minus max_tokens so any new field added to one path but not the
		// other is caught immediately.
		ObjectNode inferRoot = (ObjectNode) MAPPER.readTree(
				engine.buildRequestBody("sys prompt", "user msg", false));
		ObjectNode warmupRoot = (ObjectNode) MAPPER.readTree(
				engine.buildRequestBody("sys prompt", "user msg", false, 1));

		inferRoot.remove("max_tokens");
		warmupRoot.remove("max_tokens");

		assertEquals(inferRoot, warmupRoot,
				"warmup and infer request bodies must be identical except for max_tokens — "
				+ "any field that drifts will silently break llama-server's KV cache reuse");
	}

	@Test
	public void isContextOverflowError_shouldDetectLlamaServerOverflow() {
		// Actual response body llama-server returns when the prompt exceeds -c
		String body = "{\"error\":{\"code\":400,\"message\":\"request (21953 tokens) "
				+ "exceeds the available context size (16384 tokens), try increasing it\","
				+ "\"type\":\"exceed_context_size_error\","
				+ "\"n_prompt_tokens\":21953,\"n_ctx\":16384}}";
		assertTrue(LocalLlmEngine.isContextOverflowError(body),
				"should detect llama-server's exceed_context_size_error");
	}

	@Test
	public void isContextOverflowError_shouldReturnFalseForOtherErrors() {
		assertFalse(LocalLlmEngine.isContextOverflowError(
				"{\"error\":{\"code\":400,\"message\":\"some other 400\"}}"));
		assertFalse(LocalLlmEngine.isContextOverflowError("not json at all"));
		assertFalse(LocalLlmEngine.isContextOverflowError(""));
		assertFalse(LocalLlmEngine.isContextOverflowError(null));
	}

	@Test
	public void shouldRestartServer_modelPathChanged() {
		assertTrue(LocalLlmEngine.shouldRestartServer(
				"old/model.gguf", 16384, "new/model.gguf", 16384),
				"should restart when model path changes");
	}

	@Test
	public void shouldRestartServer_contextSizeChanged() {
		assertTrue(LocalLlmEngine.shouldRestartServer(
				"same/model.gguf", 16384, "same/model.gguf", 32768),
				"should restart when context size changes — admins editing the GP "
				+ "expect the new value to take effect on the next query");
	}

	@Test
	public void shouldRestartServer_nothingChanged() {
		assertFalse(LocalLlmEngine.shouldRestartServer(
				"same/model.gguf", 16384, "same/model.gguf", 16384),
				"no restart needed when both model and context size are unchanged");
	}

	@Test
	public void shouldRestartServer_neverLoaded() {
		assertFalse(LocalLlmEngine.shouldRestartServer(
				null, -1, "any/model.gguf", 16384),
				"if no server has loaded yet, the caller handles the initial start; "
				+ "this helper only decides whether to restart an already-running one");
	}

	@Test
	public void buildServerCommand_shouldIncludeModelAndPort() {
		List<String> cmd = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 9999, 32768);

		assertEquals("/bin/llama-server", cmd.get(0));
		assertTrue(cmd.contains("-m"));
		assertEquals("/data/model.gguf", cmd.get(cmd.indexOf("-m") + 1));
		assertTrue(cmd.contains("--port"));
		assertEquals("9999", cmd.get(cmd.indexOf("--port") + 1));
		assertTrue(cmd.contains("-c"));
		assertEquals("32768", cmd.get(cmd.indexOf("-c") + 1));
	}

	@Test
	public void buildServerCommand_shouldEnableFlashAttentionAndDisableReasoning() {
		List<String> cmd = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 9999, 32768);

		assertEquals("on", cmd.get(cmd.indexOf("-fa") + 1),
				"flash attention cuts attention compute on long-context chart prompts");
		assertEquals("0", cmd.get(cmd.indexOf("--reasoning-budget") + 1),
				"reasoning channel must stay disabled — burns output tokens before the JSON answer");
	}

	@Test
	public void buildServerCommand_shouldUseLargeBatchForChartPrompts() {
		List<String> cmd = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 9999, 32768);

		int b = Integer.parseInt(cmd.get(cmd.indexOf("-b") + 1));
		int ub = Integer.parseInt(cmd.get(cmd.indexOf("-ub") + 1));
		assertTrue(b >= 4096,
				"chart-search prompts are long (whole patient chart). Default 2048 leaves "
				+ "prompt-processing parallelism on the table");
		assertTrue(ub >= 1024 && ub <= b);
	}

	@Test
	public void buildServerCommand_shouldNotPinThreadCount() {
		List<String> cmd = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 9999, 32768);

		assertFalse(cmd.contains("-t"),
				"thread count must be left to llama-server's auto-detect — pinning to "
				+ "availableProcessors() pulls SMT siblings or efficiency cores into the work and "
				+ "regresses prefill on hosts where logical-core count exceeds physical cores");
		assertFalse(cmd.contains("-tb"));
	}

	@Test
	public void buildServerCommand_shouldNotQuantizeKvCache() {
		List<String> cmd = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 9999, 32768);

		assertFalse(cmd.contains("--cache-type-k"),
				"KV cache dtype must be left to llama-server's default — q4_0 with flash attention "
				+ "adds a dequantize-on-read tax in the attention kernel that costs more than the "
				+ "memory it saves on backends where KV fits comfortably (Metal, CUDA, sufficient-RAM CPU)");
		assertFalse(cmd.contains("--cache-type-v"));
	}

	@Test
	public void buildServerCommand_shouldUseSingleParallelSlot() {
		List<String> cmd = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 9999, 32768);

		assertEquals("1", cmd.get(cmd.indexOf("--parallel") + 1),
				"chart-search runs one request per process at a time — extra slots only add "
				+ "LRU-eviction noise that interferes with prefix-cache reuse");
	}

	@Test
	public void buildServerCommand_shouldMlockModelWeights() {
		List<String> cmd = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 9999, 32768);

		assertTrue(cmd.contains("--mlock"),
				"--mlock pins model weights in RAM so the OS cannot page them out under memory "
				+ "pressure, avoiding multi-second stalls when other processes touch RAM");
	}
}
