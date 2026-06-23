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
	public void buildServerCommand_shouldOmitSlotSavePathWhenCacheDirNotConfigured() {
		List<String> cmd = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 9999, 32768);
		assertFalse(cmd.contains("--slot-save-path"),
				"--slot-save-path must be absent when no KV-cache directory is configured "
				+ "(the disk-persistence feature is opt-in)");
	}

	@Test
	public void buildServerCommand_shouldAddSlotSavePathWhenCacheDirConfigured() {
		List<String> cmd = LocalLlmEngine.buildServerCommand(
				"/bin/llama-server", "/data/model.gguf", 9999, 32768, "/var/kvcache");
		int idx = cmd.indexOf("--slot-save-path");
		assertTrue(idx >= 0, "--slot-save-path must be present so llama-server can persist and "
				+ "restore a patient's prefilled KV cache across queries and restarts");
		assertEquals("/var/kvcache", cmd.get(idx + 1));
	}

	private static final String DISC = "/models/e2b.gguf 32768";

	@Test
	public void kvCacheKey_shouldBeDeterministicAndContentSensitiveAndFilenameSafe() {
		String scope = "5a440752-e11d-4b2d-a9a5-c53ee0cab928";
		String a = LocalLlmEngine.kvCacheKey(scope, "system prompt", "patient chart A", DISC);
		String aAgain = LocalLlmEngine.kvCacheKey(scope, "system prompt", "patient chart A", DISC);
		String b = LocalLlmEngine.kvCacheKey(scope, "system prompt", "patient chart B", DISC);
		String c = LocalLlmEngine.kvCacheKey(scope, "different system", "patient chart A", DISC);

		assertEquals(a, aAgain, "same prompt prefix must map to the same KV-cache file");
		assertFalse(a.equals(b), "a different chart must map to a different KV-cache file so a "
				+ "stale chart never restores the wrong patient's KV");
		assertFalse(a.equals(c), "the system prompt is part of the key so a prompt change "
				+ "invalidates every cached KV");
		assertTrue(a.matches(scope + "-[0-9a-f]+\\.bin"), "the filename must be <scope>-<hex>.bin "
				+ "(path-traversal safe for slot-save-path), was: " + a);
	}

	@Test
	public void modelDiscriminator_shouldChangeWhenModelFileContextOrIdentityChanges() throws Exception {
		java.io.File dir = java.nio.file.Files.createTempDirectory("disc-test").toFile();
		try {
			java.io.File model = new java.io.File(dir, "m.gguf");
			java.nio.file.Files.write(model.toPath(), new byte[] { 1, 2, 3 });
			model.setLastModified(1_000_000L);
			String base = LocalLlmEngine.modelDiscriminator(model.getAbsolutePath(), 32768);
			assertEquals(base, LocalLlmEngine.modelDiscriminator(model.getAbsolutePath(), 32768),
					"same model file + context must yield a stable discriminator");
			assertFalse(base.equals(LocalLlmEngine.modelDiscriminator(model.getAbsolutePath(), 16384)),
					"a context-size change must change the discriminator");
			// Replace the file in place at the SAME path with different content (a redeploy shipping a
			// newer gguf): length and/or mtime change, so the discriminator must change.
			java.nio.file.Files.write(model.toPath(), new byte[] { 9, 9, 9, 9, 9 });
			model.setLastModified(2_000_000L);
			assertFalse(base.equals(LocalLlmEngine.modelDiscriminator(model.getAbsolutePath(), 32768)),
					"a model file replaced in place must change the discriminator so its old KV is not reused");
		}
		finally {
			for (java.io.File f : dir.listFiles()) {
				f.delete();
			}
			dir.delete();
		}
	}

	@Test
	public void modelDiscriminator_shouldTolerateNullOrMissingModelPath() {
		assertEquals("null 32768 0 0", LocalLlmEngine.modelDiscriminator(null, 32768),
				"a null model path must not throw on the warmup path");
		String missing = LocalLlmEngine.modelDiscriminator("/no/such/model.gguf", 32768);
		assertTrue(missing.endsWith(" 0 0"), "a missing file reads length+mtime as 0, was: " + missing);
	}

	@Test
	public void kvCacheKey_shouldDifferWhenModelOrContextDiscriminatorChanges() {
		String scope = "5a440752-e11d-4b2d-a9a5-c53ee0cab928";
		String e2b = LocalLlmEngine.kvCacheKey(scope, "sys", "chart", "/models/e2b.gguf 32768");
		String e4b = LocalLlmEngine.kvCacheKey(scope, "sys", "chart", "/models/e4b.gguf 32768");
		String ctx = LocalLlmEngine.kvCacheKey(scope, "sys", "chart", "/models/e2b.gguf 16384");
		assertFalse(e2b.equals(e4b), "a model swap must change the key so a KV saved under one model "
				+ "is never restored under another (silent KV corruption)");
		assertFalse(e2b.equals(ctx), "a context-size change must change the key");
	}

	@Test
	public void kvCacheKey_shouldOmitScopePrefixWhenScopeBlank() {
		String key = LocalLlmEngine.kvCacheKey(null, "system prompt", "patient chart A", DISC);
		assertTrue(key.matches("[0-9a-f]+\\.bin"),
				"a null scope must yield a hash-only filename, was: " + key);
	}

	@Test
	public void kvCacheKey_sameChartDifferentScope_shouldDifferByPrefixOnly() {
		String k1 = LocalLlmEngine.kvCacheKey("patient-1", "sys", "chart", DISC);
		String k2 = LocalLlmEngine.kvCacheKey("patient-2", "sys", "chart", DISC);
		assertFalse(k1.equals(k2), "different patients must map to different files");
		assertEquals(k1.replaceFirst("^patient-1-", ""), k2.replaceFirst("^patient-2-", ""),
				"identical chart text must share the same digest tail across scopes");
	}

	@Test
	public void kvQueryAction_disabledYieldsNone() {
		// KV persistence off (no slot-save-path or no seed) -> the query path must do nothing
		// special, regardless of RAM/disk state. This preserves the pre-feature behavior exactly.
		assertEquals(LocalLlmEngine.KvQueryAction.NONE,
				LocalLlmEngine.kvQueryAction(false, false, false));
		assertEquals(LocalLlmEngine.KvQueryAction.NONE,
				LocalLlmEngine.kvQueryAction(false, false, true));
		assertEquals(LocalLlmEngine.KvQueryAction.NONE,
				LocalLlmEngine.kvQueryAction(false, true, true));
	}

	@Test
	public void kvQueryAction_ramResidentYieldsNone_soWarmRepeatsAndAlternatingPatientsNeverReRestore() {
		// The chart's prefix was already loaded into this server's RAM prompt-cache pool earlier
		// this lifetime (a prior warmup/query). llama-server's cache_prompt will reuse it, so a
		// disk restore would be pure wasted I/O — and on an alternating-patient workload where both
		// charts fit the RAM pool, restoring on every switch would REGRESS the warm 0.6-0.8s path.
		// Must be NONE even when a disk file also exists.
		assertEquals(LocalLlmEngine.KvQueryAction.NONE,
				LocalLlmEngine.kvQueryAction(true, true, false));
		assertEquals(LocalLlmEngine.KvQueryAction.NONE,
				LocalLlmEngine.kvQueryAction(true, true, true));
	}

	@Test
	public void kvQueryAction_coldRamButDiskHitYieldsRestore() {
		// The exact gap this feature closes: the RAM pool is cold for this chart (e.g. after a
		// server restart / idle-unload, or warmup never fired) but its prefilled KV is on disk.
		// Restore (tens of ms) instead of a full chart re-prefill (tens of seconds on a GPU-less host).
		assertEquals(LocalLlmEngine.KvQueryAction.RESTORE,
				LocalLlmEngine.kvQueryAction(true, false, true));
	}

	@Test
	public void kvQueryAction_coldEverywhereYieldsPrefillAndSave() {
		// First-ever visit (or a chart that changed, hashing to a new file): nothing to restore, so
		// the query prefills as before — but its KV must then be PERSISTED so the next visit (even
		// after a restart) restores it instead of re-paying the prefill. Without the save, a cold
		// query without a preceding warmup would throw its expensive prefill away (the observed gap).
		assertEquals(LocalLlmEngine.KvQueryAction.PREFILL_AND_SAVE,
				LocalLlmEngine.kvQueryAction(true, false, false));
	}

	@Test
	public void purgeKvScopeEntries_shouldDeleteOnlyOtherFilesOfSameScope() throws Exception {
		java.io.File dir = java.nio.file.Files.createTempDirectory("kvscope-test").toFile();
		try {
			java.io.File keep = newFile(dir, "pA-newhash.bin", 3_000L);
			java.io.File stale = newFile(dir, "pA-oldhash.bin", 1_000L);
			java.io.File otherPatient = newFile(dir, "pB-hash.bin", 1_000L);
			java.io.File prefixLookalike = newFile(dir, "pAA-hash.bin", 1_000L);

			LocalLlmEngine.purgeKvScopeEntries(dir, "pA", "pA-newhash.bin");

			assertTrue(keep.exists(), "the patient's current entry must be kept");
			assertFalse(stale.exists(), "the patient's superseded entry must be deleted");
			assertTrue(otherPatient.exists(), "another patient's entry must never be touched");
			assertTrue(prefixLookalike.exists(),
					"a different scope that merely shares a leading substring must not be matched");
		}
		finally {
			for (java.io.File f : dir.listFiles()) {
				f.delete();
			}
			dir.delete();
		}
	}

	@Test
	public void purgeKvScopeEntries_shouldBeNoOpForBlankScope() throws Exception {
		java.io.File dir = java.nio.file.Files.createTempDirectory("kvscope-blank").toFile();
		try {
			java.io.File f = newFile(dir, "deadbeef.bin", 1_000L);
			LocalLlmEngine.purgeKvScopeEntries(dir, null, "other.bin");
			assertTrue(f.exists(), "with no scope there is nothing to group, so nothing is deleted");
		}
		finally {
			for (java.io.File g : dir.listFiles()) {
				g.delete();
			}
			dir.delete();
		}
	}

	@Test
	public void resolveKvCacheDir_shouldDefaultToAppdataSubdirWhenUnset() {
		String dir = LocalLlmEngine.resolveKvCacheDir("", "/var/openmrs");
		assertTrue(dir.replace('\\', '/').endsWith("/chartsearchai/kvcache"),
				"an empty value must enable the cache at <appdata>/chartsearchai/kvcache, was: " + dir);
		assertEquals(LocalLlmEngine.resolveKvCacheDir(null, "/var/openmrs"), dir,
				"null and empty must resolve the same");
	}

	@Test
	public void resolveKvCacheDir_shouldUseExplicitPathVerbatim() {
		assertEquals("/mnt/fast/kv",
				LocalLlmEngine.resolveKvCacheDir("  /mnt/fast/kv  ", "/var/openmrs"),
				"an explicitly configured path must win over the appdata default");
	}

	@Test
	public void resolveKvCacheDir_shouldTolerateNullAppDataForNonDefaultBranches() {
		// The instance method resolves the appdata dir lazily (only for the empty/default branch),
		// passing null otherwise. The explicit-path and disable-token branches must not touch it.
		assertEquals("/mnt/fast/kv", LocalLlmEngine.resolveKvCacheDir("/mnt/fast/kv", null));
		assertEquals(null, LocalLlmEngine.resolveKvCacheDir("off", null));
	}

	@Test
	public void resolveKvCacheDir_shouldDisableOnDisableToken() {
		for (String token : new String[] { "off", "OFF", "false", "none", "Disabled" }) {
			assertEquals(null, LocalLlmEngine.resolveKvCacheDir(token, "/var/openmrs"),
					"'" + token + "' must disable disk KV persistence (return null)");
		}
	}

	@Test
	public void evictOldestKvEntries_shouldDeleteOldestBeyondTheLimit() throws Exception {
		java.io.File dir = java.nio.file.Files.createTempDirectory("kvcache-test").toFile();
		try {
			// Three .bin files with strictly increasing last-modified times, plus an unrelated file
			// that must never be touched.
			java.io.File oldest = newFile(dir, "a.bin", 1_000L);
			java.io.File middle = newFile(dir, "b.bin", 2_000L);
			java.io.File newest = newFile(dir, "c.bin", 3_000L);
			java.io.File unrelated = newFile(dir, "notes.txt", 1L);

			LocalLlmEngine.evictOldestKvEntries(dir, 2);

			assertFalse(oldest.exists(), "the oldest .bin must be evicted when over the limit");
			assertTrue(middle.exists(), "entries within the limit must be kept");
			assertTrue(newest.exists(), "the newest entry must be kept");
			assertTrue(unrelated.exists(), "non-.bin files must never be evicted");
		}
		finally {
			for (java.io.File f : dir.listFiles()) {
				f.delete();
			}
			dir.delete();
		}
	}

	@Test
	public void evictOldestKvEntries_shouldNeverEvictPinnedEntries() throws Exception {
		// A pinned entry (a <name>.pin sidecar exists) is the prewarm bootstrap's durable corpus:
		// it must be exempt from the LRU cap entirely — neither counted toward the limit nor deleted —
		// so a deployment with disk for every patient keeps them all warm regardless of how small the
		// cap is. The cap continues to govern only the ad-hoc (chart-open warmup + query-save) pool.
		java.io.File dir = java.nio.file.Files.createTempDirectory("kvcache-pin").toFile();
		try {
			java.io.File pinned = newFile(dir, "pinA.bin", 1_000L);
			newFile(dir, "pinA.bin.pin", 1_000L);
			java.io.File pinnedOldest = newFile(dir, "pinB.bin", 500L);
			newFile(dir, "pinB.bin.pin", 500L);
			java.io.File adhocOld = newFile(dir, "x.bin", 2_000L);
			java.io.File adhocMid = newFile(dir, "y.bin", 3_000L);
			java.io.File adhocNew = newFile(dir, "z.bin", 4_000L);

			// Cap of 1 applied to the UNPINNED pool only: the two oldest unpinned go, the newest stays.
			LocalLlmEngine.evictOldestKvEntries(dir, 1);

			assertTrue(pinned.exists(), "a pinned entry must never be evicted");
			assertTrue(pinnedOldest.exists(),
					"a pinned entry must never be evicted, even when it is the oldest file overall");
			assertFalse(adhocOld.exists(), "the oldest unpinned entry over the cap must be evicted");
			assertFalse(adhocMid.exists(), "unpinned entries over the cap must be evicted");
			assertTrue(adhocNew.exists(), "the newest unpinned entry must be kept under the cap");
		}
		finally {
			for (java.io.File f : dir.listFiles()) {
				f.delete();
			}
			dir.delete();
		}
	}

	@Test
	public void purgeKvScopeEntries_shouldAlsoDeleteThePinSidecarOfSupersededEntries() throws Exception {
		// When a pinned patient's chart changes, the new prefill hashes to a new filename and the old
		// .bin is purged; its orphaned .pin sidecar must go with it, or it would pin a file that no
		// longer exists and skew the pinned-corpus accounting.
		java.io.File dir = java.nio.file.Files.createTempDirectory("kvscope-pin").toFile();
		try {
			java.io.File keep = newFile(dir, "pA-newhash.bin", 3_000L);
			java.io.File keepPin = newFile(dir, "pA-newhash.bin.pin", 3_000L);
			java.io.File stale = newFile(dir, "pA-oldhash.bin", 1_000L);
			java.io.File stalePin = newFile(dir, "pA-oldhash.bin.pin", 1_000L);

			LocalLlmEngine.purgeKvScopeEntries(dir, "pA", "pA-newhash.bin");

			assertTrue(keep.exists(), "the patient's current entry must be kept");
			assertTrue(keepPin.exists(), "the current entry's pin marker must be kept");
			assertFalse(stale.exists(), "the patient's superseded entry must be deleted");
			assertFalse(stalePin.exists(),
					"the superseded entry's orphaned pin marker must also be deleted");
		}
		finally {
			for (java.io.File f : dir.listFiles()) {
				f.delete();
			}
			dir.delete();
		}
	}

	@Test
	public void hasPinnedEntry_shouldDetectAScopesPinnedSidecar() throws Exception {
		// Membership check used by the per-edit auto-refresh: does this patient (scope) have any pinned
		// entry? True only when a <scope>-<hash>.bin.pin sidecar exists; a bare .bin (unpinned) is not a
		// corpus member, and a different scope sharing a leading substring must not match.
		java.io.File dir = java.nio.file.Files.createTempDirectory("kvpin-has").toFile();
		try {
			newFile(dir, "pA-hash.bin", 1_000L);
			newFile(dir, "pA-hash.bin.pin", 1_000L);
			newFile(dir, "pB-hash.bin", 1_000L); // unpinned: .bin only, no sidecar

			assertTrue(LocalLlmEngine.hasPinnedEntry(dir, "pA"), "pA has a pinned sidecar");
			assertFalse(LocalLlmEngine.hasPinnedEntry(dir, "pB"), "pB is unpinned (no sidecar)");
			assertFalse(LocalLlmEngine.hasPinnedEntry(dir, "pC"), "pC has no entry at all");
			assertFalse(LocalLlmEngine.hasPinnedEntry(dir, "p"),
					"a scope that is only a leading substring of pA/pB must not match");
		}
		finally {
			for (java.io.File f : dir.listFiles()) {
				f.delete();
			}
			dir.delete();
		}
	}

	@Test
	public void writePinMarker_shouldCreateTheSidecarAndBeIdempotent() throws Exception {
		java.io.File dir = java.nio.file.Files.createTempDirectory("kvpin-write").toFile();
		try {
			java.io.File bin = newFile(dir, "pX-hash.bin", 1_000L);
			java.io.File pin = new java.io.File(dir, "pX-hash.bin" + LocalLlmEngine.PIN_SUFFIX);
			assertFalse(pin.exists(), "precondition: no marker yet");

			LocalLlmEngine.writePinMarker(dir, "pX-hash.bin");
			assertTrue(pin.exists(), "the pin sidecar must be created for the given key");

			// Idempotent: a second call on an already-pinned entry is a no-op and must not throw.
			LocalLlmEngine.writePinMarker(dir, "pX-hash.bin");
			assertTrue(pin.exists() && bin.exists(), "re-pinning leaves both the marker and the .bin intact");
		}
		finally {
			for (java.io.File f : dir.listFiles()) {
				f.delete();
			}
			dir.delete();
		}
	}

	private static java.io.File newFile(java.io.File dir, String name, long lastModified) throws IOException {
		java.io.File f = new java.io.File(dir, name);
		java.nio.file.Files.write(f.toPath(), new byte[] { 1 });
		f.setLastModified(lastModified);
		return f;
	}

	@Test
	public void serverNeedsRestart_shouldNotRestartWhenNothingChanged() {
		assertFalse(LocalLlmEngine.serverNeedsRestart("/m.gguf", 32768, "/kv", "/m.gguf", 32768, "/kv"));
	}

	@Test
	public void serverNeedsRestart_shouldRestartWhenKvCacheDirChanges() {
		assertTrue(LocalLlmEngine.serverNeedsRestart("/m.gguf", 32768, "/kv", "/m.gguf", 32768, "/other"),
				"a KV-cache directory change must relaunch the server with the new --slot-save-path");
		assertTrue(LocalLlmEngine.serverNeedsRestart("/m.gguf", 32768, null, "/m.gguf", 32768, "/kv"),
				"enabling the cache at runtime must relaunch with the flag");
		assertTrue(LocalLlmEngine.serverNeedsRestart("/m.gguf", 32768, "/kv", "/m.gguf", 32768, null),
				"disabling the cache at runtime must relaunch without the flag");
	}

	@Test
	public void serverNeedsRestart_shouldNotLoopWhenDirCreationFailed() {
		// Regression guard: on a mkdirs failure the effective --slot-save-path is nulled, but
		// loadedKvCacheDir holds the INTENDED (configured) path. The restart decision compares that
		// intended value against the same resolved value, so it must report "no restart" — comparing
		// the nulled effective path instead would restart the server on every single call.
		String configured = "/var/openmrs/chartsearchai/kvcache";
		assertFalse(LocalLlmEngine.serverNeedsRestart("/m.gguf", 32768, configured,
				"/m.gguf", 32768, configured),
				"a one-time directory-creation failure must not cause a per-call restart loop");
	}

	@Test
	public void serverNeedsRestart_shouldNotRestartBeforeAnythingLoaded() {
		assertFalse(LocalLlmEngine.serverNeedsRestart(null, -1, null, "/m.gguf", 32768, "/kv"),
				"with nothing loaded yet the initial start handles it, not a restart");
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
