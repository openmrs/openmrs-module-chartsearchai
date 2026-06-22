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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.util.OpenmrsUtil;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Local LLM engine that manages an embedded llama-server subprocess for GGUF
 * model inference. The server is started lazily on first use and automatically
 * stopped after an idle period to free memory. It exposes an
 * OpenAI-compatible chat completions API internally.
 *
 * <p>Public methods ({@code infer}, {@code inferStreaming}, {@code warmup},
 * {@code close}) are {@code synchronized} so that only one call is in flight
 * at a time. Three reasons:
 * <ul>
 *   <li>The subprocess is launched with {@code --parallel 1} (see
 *       {@link #buildServerCommand}), so concurrent callers would queue inside
 *       llama-server with no parallelism gain.</li>
 *   <li>The prefix-cache-reuse strategy ({@code --cache-reuse} +
 *       {@code cache_prompt=true}, primed by {@link #warmup}) assumes serial
 *       access on the same chart prefix; interleaved calls with different
 *       prefixes would thrash the KV cache and erase the warmup gain.</li>
 *   <li>Subprocess and HTTP-client lifecycle state ({@code serverProcess},
 *       {@code loadedModelPath}, {@code loadedContextSize}, {@code httpClient},
 *       {@code idleUnloadFuture}) is shared mutable state that
 *       {@link #ensureServerRunning} and the idle timer mutate; without the
 *       monitor, callers could race on start/stop or tear down the
 *       {@code HttpClient} mid-request.</li>
 * </ul>
 * {@link RemoteLlmEngine} does not synchronize {@code infer} for this reason —
 * the remote endpoint handles concurrency itself and there is no subprocess to
 * manage.
 *
 * <p>See {@code docs/adr.md} &mdash; Decision 12: Concurrency model &mdash;
 * for the fuller rationale (alternatives considered, impact on concurrent
 * users, future options).
 */
@Component("chartSearchAi.localLlmEngine")
public class LocalLlmEngine implements LlmEngine {

	private static final Logger log = LoggerFactory.getLogger(LocalLlmEngine.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final int SERVER_STARTUP_TIMEOUT_SECONDS = 120;

	private static final int HEALTH_POLL_INTERVAL_MS = 500;

	private Process serverProcess;

	private String loadedModelPath;

	private int loadedContextSize = -1;

	/** The resolved KV-cache directory the running server was configured for (the
	 *  {@link #resolveKvCacheDir()} value at launch; null when disabled). Used ONLY for the restart
	 *  decision — it must reflect intent, not whether the directory could be created, otherwise a
	 *  one-time {@code mkdirs} failure (effective path nulled below) would never equal the intended
	 *  path and the server would restart on every call. */
	private String loadedKvCacheDir;

	/** The {@code --slot-save-path} the running server was actually launched with, or null when KV
	 *  persistence is off OR the directory could not be created. Save/restore is gated on this, so a
	 *  server running without the flag never attempts (and logs) doomed slot calls. */
	private String loadedSlotSavePath;

	private int serverPort;

	/** The KV-cache keys whose chart prefix has been loaded into THIS server process's RAM
	 *  prompt-cache pool (by a warmup or query) since it last started. llama-server's
	 *  {@code cache_prompt} pool retains many prefixes at once, so a key present here will be
	 *  reused from RAM without a disk restore; a key absent here (a fresh process after a restart /
	 *  idle-unload, or one the pool evicted) is the only case where a disk restore avoids a full
	 *  re-prefill. Tracking residency this way keeps the warm and alternating-patient paths free of
	 *  any extra disk I/O. Mutated only under the instance monitor (all callers are synchronized);
	 *  cleared on {@link #stopServer()} because the RAM pool dies with the process. */
	private final Set<String> ramResidentKeys = new HashSet<>();

	private HttpClient httpClient;

	private final ScheduledExecutorService idleTimer = Executors.newSingleThreadScheduledExecutor(
			r -> {
				Thread t = new Thread(r, "chartsearchai-llm-idle-timer");
				t.setDaemon(true);
				return t;
			});

	private ScheduledFuture<?> idleUnloadFuture;

	@Override
	public synchronized InferenceResult infer(String systemPrompt, String userMessage,
			int timeoutSeconds) {
		ensureServerRunning();
		return postForResult(buildRequestBody(systemPrompt, userMessage, false), timeoutSeconds);
	}

	@Override
	public synchronized InferenceResult infer(String systemPrompt, String userMessage,
			int timeoutSeconds, ObjectNode responseFormat) {
		ensureServerRunning();
		String requestBody = responseFormat == null
				? buildRequestBody(systemPrompt, userMessage, false)
				: buildRequestBody(systemPrompt, userMessage, false,
						ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS, responseFormat);
		return postForResult(requestBody, timeoutSeconds);
	}

	/**
	 * Sends a pre-built request body to the local llama-server and parses the (non-streaming)
	 * result. Shared by the default-schema and custom-{@code response_format} {@link #infer}
	 * overloads. Called only from {@code synchronized} methods, so it runs under the engine lock.
	 */
	private InferenceResult postForResult(String requestBody, int timeoutSeconds) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(getCompletionsUrl()))
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
				.build();

		try {
			HttpResponse<String> response = getHttpClient().send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				log.error("Local llama-server returned HTTP {}: {}", response.statusCode(),
						truncate(response.body()));
				if (response.statusCode() == 400 && LlmResponseParser.isContextOverflowError(response.body())) {
					throw new ChartTooLargeException(
							"Patient chart exceeds the LLM context window of "
									+ getContextSize() + " tokens. Increase "
									+ ChartSearchAiConstants.GP_LLM_CONTEXT_SIZE
									+ " or enable embedding pre-filter.");
				}
				throw new APIException("Local llama-server returned HTTP " + response.statusCode());
			}

			resetIdleTimer();
			return LlmResponseParser.parseResponse(response.body(), log);
		}
		catch (IOException e) {
			throw new APIException("Failed to call local llama-server: " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new APIException("Local llama-server call was interrupted", e);
		}
	}

	@Override
	public synchronized InferenceResult inferStreaming(String systemPrompt, String userMessage,
			int timeoutSeconds, Consumer<String> tokenConsumer) {
		return inferStreaming(systemPrompt, userMessage, timeoutSeconds, tokenConsumer, null, null);
	}

	@Override
	public synchronized InferenceResult inferStreaming(String systemPrompt, String userMessage,
			int timeoutSeconds, Consumer<String> tokenConsumer, String cacheScope, String cacheSeed) {
		ensureServerRunning();

		// Disk-persisted KV cache on the QUERY path (mirrors what warmup already does, see
		// #warmup). The seed is the question-INDEPENDENT prefix, so warmup-saved and query-saved
		// entries share one filename per patient+chart and the restored KV is byte-for-byte what a
		// fresh prefill would produce — answer quality is unchanged; cache_prompt then re-prefills
		// only the cheap focus-hint + question tail. The decision is gated so the warm and
		// alternating-patient paths (the chart already resident in this process's RAM prompt-cache
		// pool) do NO extra disk I/O — only a genuinely cold chart with a disk hit restores.
		String cacheDir = loadedSlotSavePath;
		String cacheKey = (cacheDir != null && cacheSeed != null)
				? kvCacheKey(cacheScope, systemPrompt, cacheSeed,
						modelDiscriminator(loadedModelPath, loadedContextSize))
				: null;
		boolean ramResident = cacheKey != null && ramResidentKeys.contains(cacheKey);
		boolean fileExists = cacheKey != null && !ramResident && new File(cacheDir, cacheKey).isFile();
		KvQueryAction action = kvQueryAction(cacheKey != null, ramResident, fileExists);
		boolean restored = false;
		if (action == KvQueryAction.RESTORE && restoreSlot(cacheKey, timeoutSeconds)) {
			restored = true;
			ramResidentKeys.add(cacheKey);
			log.warn("Query restored KV cache from disk: {}", cacheKey);
		}

		String requestBody = buildRequestBody(systemPrompt, userMessage, true);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(getCompletionsUrl()))
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
				.build();

		try {
			HttpResponse<InputStream> response = getHttpClient().send(request,
					HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
				log.error("Local llama-server returned HTTP {}: {}", response.statusCode(),
						truncate(body));
				if (response.statusCode() == 400 && LlmResponseParser.isContextOverflowError(body)) {
					throw new ChartTooLargeException(
							"Patient chart exceeds the LLM context window of "
									+ getContextSize() + " tokens. Increase "
									+ ChartSearchAiConstants.GP_LLM_CONTEXT_SIZE
									+ " or enable embedding pre-filter.");
				}
				throw new APIException("Local llama-server returned HTTP " + response.statusCode());
			}

			InferenceResult result = LlmResponseParser.parseStreamingResponse(
					response.body(), tokenConsumer, log);

			// The chart prefix is now resident in the RAM pool. If this was a genuinely cold prefill
			// (not resident, not restored), persist it so the next visit — even after a restart —
			// restores from disk instead of re-paying the prefill; replace the patient's prior entry
			// and apply the global cap, exactly as warmup does.
			if (cacheKey != null) {
				ramResidentKeys.add(cacheKey);
				if (!ramResident && !restored) {
					persistKvEntry(cacheKey, cacheScope, cacheDir, timeoutSeconds);
				}
			}
			resetIdleTimer();
			return result;
		}
		catch (IOException e) {
			throw new APIException("Failed to call local llama-server: " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new APIException("Local llama-server call was interrupted", e);
		}
	}

	/**
	 * The disk-KV action a query should take, given whether KV persistence is active for this call
	 * ({@code kvEnabled}: a slot-save-path is configured and a cache seed was supplied), whether the
	 * chart's prefix is already resident in this process's RAM prompt-cache pool ({@code ramResident}),
	 * and whether a persisted entry exists on disk ({@code fileExists}). Pure so the policy is unit
	 * tested without a live server.
	 *
	 * <ul>
	 *   <li>{@code NONE} — KV off, OR the chart is RAM-resident: {@code cache_prompt} reuses it with
	 *       zero disk I/O. Returning RESTORE here would regress the warm / alternating-patient paths.</li>
	 *   <li>{@code RESTORE} — RAM-cold but a disk entry exists: load it (tens of ms) to skip a full
	 *       re-prefill (tens of seconds on a GPU-less host). This is the gap the feature closes.</li>
	 *   <li>{@code PREFILL_AND_SAVE} — cold everywhere: prefill as before, then persist so the next
	 *       visit (even after a restart) is fast.</li>
	 * </ul>
	 */
	enum KvQueryAction {
		NONE, RESTORE, PREFILL_AND_SAVE
	}

	static KvQueryAction kvQueryAction(boolean kvEnabled, boolean ramResident, boolean fileExists) {
		if (!kvEnabled || ramResident) {
			return KvQueryAction.NONE;
		}
		return fileExists ? KvQueryAction.RESTORE : KvQueryAction.PREFILL_AND_SAVE;
	}

	@Override
	public synchronized void warmup(String systemPrompt, String userMessage, int timeoutSeconds) {
		warmup(systemPrompt, userMessage, timeoutSeconds, null);
	}

	@Override
	public synchronized void warmup(String systemPrompt, String userMessage, int timeoutSeconds,
			String cacheScope) {
		warmup(systemPrompt, userMessage, timeoutSeconds, cacheScope, false);
	}

	@Override
	public synchronized void warmup(String systemPrompt, String userMessage, int timeoutSeconds,
			String cacheScope, boolean pin) {
		ensureServerRunning();

		// Disk-persisted KV cache (on by default). When enabled, a patient's prefilled chart KV is
		// restored from disk (I/O-bound, tens of ms) instead of recomputed (CPU-bound, tens of
		// seconds to minutes on a GPU-less host). The key is the exact prompt prefix scoped by the
		// patient UUID, so a restore only ever reuses the right patient's state; the restored KV is
		// byte-for-byte what a fresh prefill would produce, so answer quality is unchanged. On a miss
		// we prefill as before and then save, so the next visit (or the next process lifetime) is
		// fast — and the save replaces this patient's previous entry so a changed chart leaves no
		// orphan.
		String cacheDir = loadedSlotSavePath;
		// Bind the key to the model + context the server is actually running (set by
		// ensureServerRunning above), so a KV saved under one model is never restored under
		// another — including a model file replaced in place at the same path (a redeploy that
		// ships a newer gguf into the preserved volume), which the path alone would not catch.
		String discriminator = modelDiscriminator(loadedModelPath, loadedContextSize);
		String cacheKey = cacheDir != null
				? kvCacheKey(cacheScope, systemPrompt, userMessage, discriminator) : null;
		if (cacheKey != null && new File(cacheDir, cacheKey).isFile()
				&& restoreSlot(cacheKey, timeoutSeconds)) {
			log.warn("Warmup restored KV cache from disk: {}", cacheKey);
			// A prewarm pass over a patient already prefilled by an earlier query finds the .bin on
			// disk and only needs to pin it (no re-prefill) so it joins the durable corpus.
			if (pin) {
				writePinMarker(new File(cacheDir), cacheKey);
			}
			// Record residency so a query right after this warmup reuses the RAM pool rather than
			// redundantly restoring from disk again.
			ramResidentKeys.add(cacheKey);
			resetIdleTimer();
			return;
		}

		// max_tokens=1 forces llama-server to do the prompt prefill (loading the system+user
		// message into the KV cache) without spending time on real generation. The prefix
		// cached this way is what a real query reuses via cache_prompt=true + --cache-reuse.
		String requestBody = buildRequestBody(systemPrompt, userMessage, false, 1);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(getCompletionsUrl()))
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
				.build();

		try {
			HttpResponse<String> response = getHttpClient().send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				log.warn("Warmup returned HTTP {}: {}", response.statusCode(),
						truncate(response.body()));
				return;
			}

			InferenceResult result = LlmResponseParser.parseResponse(response.body(), log);
			// WARN — visible under OpenMRS's default log4j2 (org.openmrs.* pinned to WARN);
			// the cached-token count is the only direct evidence the warmup primed the KV.
			log.warn("Warmup primed KV cache: {} input ({} cached)",
					result.getInputTokens(), result.getCachedTokens());
			// The chart prefix is now resident in this process's RAM prompt-cache pool (so a query
			// right after this warmup reuses it directly, no disk restore), and is persisted to disk
			// so the next visit is fast even after a server restart or an eviction by another
			// patient's query.
			if (cacheKey != null) {
				ramResidentKeys.add(cacheKey);
				persistKvEntry(cacheKey, cacheScope, cacheDir, timeoutSeconds, pin);
			}
			resetIdleTimer();
		}
		catch (IOException e) {
			log.warn("Warmup failed: {}", e.getMessage());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.debug("Warmup interrupted");
		}
	}

	/**
	 * The model-identity discriminator folded into the KV-cache key: the model path, context size,
	 * and the model file's length + last-modified time. Path alone is not enough — a redeploy can
	 * ship a newer gguf at the SAME path into the preserved volume, and restoring KV produced by the
	 * old weights into the new model would be silent corruption. Length+mtime change when the file
	 * is replaced, so the key changes and the stale entry simply misses (then re-prefills). Tolerates
	 * a null/missing model file (length and mtime read as 0) so it never throws on the warmup path.
	 */
	static String modelDiscriminator(String modelPath, int contextSize) {
		long length = 0L;
		long lastModified = 0L;
		if (modelPath != null) {
			File modelFile = new File(modelPath);
			length = modelFile.length();
			lastModified = modelFile.lastModified();
		}
		return modelPath + " " + contextSize + " " + length + " " + lastModified;
	}

	/**
	 * The KV-cache filename for a prompt prefix: an optional {@code <scope>-} prefix followed by a
	 * hex SHA-256 of {@code systemPrompt}, {@code userMessage}, and {@code discriminator}
	 * (NUL-separated so the boundaries are unambiguous) plus {@code .bin}. The digest changes
	 * whenever the system prompt or chart text changes, so a stale chart never restores the wrong
	 * KV — a miss simply falls back to a fresh prefill. The {@code discriminator} carries the
	 * model + context identity, so a KV produced under one model is never restored under another
	 * (the operator-driven model A/B swap on {@code chartsearchai.llm.modelFilePath} would otherwise
	 * match by prompt hash and risk loading mismatched KV). The {@code scope} (e.g. the patient
	 * UUID, sanitized) groups a subject's entries so {@link #purgeKvScopeEntries} can drop the
	 * previous one when the chart changes. The whole name is path-traversal safe for llama-server's
	 * {@code --slot-save-path}.
	 */
	static String kvCacheKey(String scope, String systemPrompt, String userMessage,
			String discriminator) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update((systemPrompt == null ? "" : systemPrompt).getBytes(StandardCharsets.UTF_8));
			md.update((byte) 0);
			md.update((userMessage == null ? "" : userMessage).getBytes(StandardCharsets.UTF_8));
			md.update((byte) 0);
			md.update((discriminator == null ? "" : discriminator).getBytes(StandardCharsets.UTF_8));
			byte[] digest = md.digest();
			StringBuilder sb = new StringBuilder();
			String prefix = sanitizeScope(scope);
			if (!prefix.isEmpty()) {
				sb.append(prefix).append('-');
			}
			for (byte b : digest) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16));
				sb.append(Character.forDigit(b & 0xF, 16));
			}
			return sb.append(".bin").toString();
		}
		catch (NoSuchAlgorithmException e) {
			// SHA-256 is mandated by the JLS; unreachable on any conformant JVM.
			throw new APIException("SHA-256 unavailable for KV-cache key", e);
		}
	}

	/**
	 * Reduces a cache scope (e.g. a patient UUID) to a filename-safe token: characters outside
	 * {@code [A-Za-z0-9_.-]} become {@code _}, capped at 64 chars. Returns "" for null/blank, which
	 * yields a scope-less (hash-only) filename. UUIDs pass through unchanged.
	 */
	static String sanitizeScope(String scope) {
		if (scope == null) {
			return "";
		}
		String trimmed = scope.trim();
		if (trimmed.isEmpty()) {
			return "";
		}
		String cleaned = trimmed.replaceAll("[^A-Za-z0-9_.-]", "_");
		return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
	}

	/**
	 * Deletes every persisted KV file for {@code scope} except {@code keepFilename} — the subject's
	 * now-current entry. Called after a save so a changed chart (which hashes to a new filename)
	 * does not leave the previous version as an orphan. A no-op when {@code scope} is null/blank,
	 * since scope-less entries cannot be grouped.
	 */
	static void purgeKvScopeEntries(File dir, String scope, String keepFilename) {
		String prefix = sanitizeScope(scope);
		if (prefix.isEmpty()) {
			return;
		}
		String match = prefix + "-";
		File[] files = dir.listFiles((d, name) ->
				name.startsWith(match) && name.endsWith(".bin") && !name.equals(keepFilename));
		if (files == null) {
			return;
		}
		for (File f : files) {
			if (!f.delete()) {
				log.warn("Could not delete superseded KV-cache file {}", f);
			}
			// A superseded entry may carry a prewarm pin marker; delete it too, or it would pin a
			// .bin that no longer exists (and skew the pinned-corpus accounting).
			File pin = new File(dir, f.getName() + PIN_SUFFIX);
			if (pin.exists() && !pin.delete()) {
				log.warn("Could not delete orphaned KV pin marker {}", pin);
			}
		}
	}

	/** Suffix of the zero-byte sidecar that marks a {@code .bin} as pinned (exempt from the LRU cap).
	 *  Written by the prewarm bootstrap so a host with disk for every patient keeps a durable,
	 *  uncapped warm corpus, while ad-hoc warmup/query entries stay under {@code kvCacheMaxEntries}. */
	static final String PIN_SUFFIX = ".pin";

	/** Marks {@code cacheKey} as pinned by writing its zero-byte sidecar, so {@link #evictOldestKvEntries}
	 *  never reclaims it. Idempotent; a write failure is logged and left non-fatal (the entry simply
	 *  stays subject to the cap). */
	static void writePinMarker(File dir, String cacheKey) {
		File pin = new File(dir, cacheKey + PIN_SUFFIX);
		if (pin.exists()) {
			return;
		}
		try {
			java.nio.file.Files.write(pin.toPath(), new byte[0]);
		}
		catch (IOException e) {
			log.warn("Could not write KV pin marker {}: {}", pin, e.getMessage());
		}
	}

	/**
	 * Keeps at most {@code maxEntries} <em>unpinned</em> {@code .bin} files in {@code dir}, deleting
	 * the oldest (by last-modified time) first. Each persisted KV file is large (proportional to the
	 * chart's token count), so this bounds disk use for the ad-hoc warmup/query pool. Pinned entries
	 * (a {@code <name>.pin} sidecar exists) are the prewarm bootstrap's durable corpus — they are
	 * neither counted toward {@code maxEntries} nor evicted, so the cap never reclaims them.
	 * Non-{@code .bin} files (including the {@code .pin} sidecars themselves) are never touched.
	 */
	static void evictOldestKvEntries(File dir, int maxEntries) {
		// Pinned entries (a <name>.pin sidecar exists) are the prewarm bootstrap's durable corpus:
		// excluded from the candidate list so they are neither counted toward the cap nor deleted.
		// The cap therefore governs only the ad-hoc (chart-open warmup + query-save) pool.
		File[] files = dir.listFiles((d, name) ->
				name.endsWith(".bin") && !new File(d, name + PIN_SUFFIX).exists());
		if (files == null || files.length <= maxEntries) {
			return;
		}
		Arrays.sort(files, Comparator.comparingLong(File::lastModified));
		for (int i = 0; i < files.length - maxEntries; i++) {
			if (!files[i].delete()) {
				log.warn("Could not evict stale KV-cache file {}", files[i]);
			}
		}
	}

	/** POSTs a {@code /slots/0} save/restore action, returning true on a 2xx response. The slot id
	 *  is fixed at 0 because the server runs {@code --parallel 1}. Failures (missing file, disabled
	 *  endpoint, I/O) are logged and treated as a miss so warmup degrades to a plain prefill. */
	private boolean slotAction(String action, String filename, int timeoutSeconds) {
		String url = "http://127.0.0.1:" + serverPort + "/slots/0?action=" + action;
		ObjectNode body = MAPPER.createObjectNode();
		body.put("filename", filename);
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.timeout(Duration.ofSeconds(timeoutSeconds))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body),
							StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> response = getHttpClient().send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				return true;
			}
			log.warn("KV-cache slot {} returned HTTP {}: {}", action, response.statusCode(),
					truncate(response.body()));
			return false;
		}
		catch (IOException e) {
			log.warn("KV-cache slot {} failed: {}", action, e.getMessage());
			return false;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private boolean restoreSlot(String filename, int timeoutSeconds) {
		return slotAction("restore", filename, timeoutSeconds);
	}

	private boolean saveSlot(String filename, int timeoutSeconds) {
		return slotAction("save", filename, timeoutSeconds);
	}

	/**
	 * Persists the current slot's KV under {@code cacheKey}, then drops this scope's superseded
	 * entries (a changed chart hashes to a new filename, so its old file would otherwise orphan) and
	 * applies the global count cap. Shared by {@link #warmup} and the streaming query path so both
	 * persist identically. No-op when the save itself fails. Caller guarantees KV persistence is
	 * active ({@code cacheKey} and {@code cacheDir} non-null).
	 */
	private void persistKvEntry(String cacheKey, String cacheScope, String cacheDir, int timeoutSeconds) {
		persistKvEntry(cacheKey, cacheScope, cacheDir, timeoutSeconds, false);
	}

	/**
	 * As {@link #persistKvEntry(String, String, String, int)} but, when {@code pin} is true, marks the
	 * saved entry as pinned (prewarm-bootstrap corpus) BEFORE the cap is applied, so it is exempt from
	 * eviction. The pin write precedes {@link #evictOldestKvEntries} so the just-saved entry cannot be
	 * reclaimed by the same call.
	 */
	private void persistKvEntry(String cacheKey, String cacheScope, String cacheDir, int timeoutSeconds,
			boolean pin) {
		if (saveSlot(cacheKey, timeoutSeconds)) {
			purgeKvScopeEntries(new File(cacheDir), cacheScope, cacheKey);
			if (pin) {
				writePinMarker(new File(cacheDir), cacheKey);
			}
			evictOldestKvEntries(new File(cacheDir), getKvCacheMaxEntries());
		}
	}

	/** The effective KV-cache directory, or null when disk KV persistence is disabled. Read on the
	 *  request/daemon thread; {@link #loadedSlotSavePath} caches what the running server was actually
	 *  launched with. */
	String resolveKvCacheDir() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_KV_CACHE_DIR);
		// Only the default (empty) branch needs the application-data directory, and resolving it
		// touches the filesystem; skip that work for explicit-path and disable-token deployments,
		// since ensureServerRunning calls this on every request.
		boolean usesAppData = value == null || value.trim().isEmpty();
		return resolveKvCacheDir(value,
				usesAppData ? OpenmrsUtil.getApplicationDataDirectory() : null);
	}

	/**
	 * Pure resolution of the configured {@link ChartSearchAiConstants#GP_LLM_KV_CACHE_DIR} value
	 * against the application-data directory. Disk KV persistence is ON by default: an empty/unset
	 * value resolves to {@code <appdata>/chartsearchai/kvcache}. An explicit path is used verbatim.
	 * A disable token ({@code off}/{@code false}/{@code none}/{@code disabled}, case-insensitive)
	 * turns the feature off (returns null) — the escape hatch for hosts where the on-disk KV files
	 * (which contain the model's encoding of the chart) or their disk footprint are unwanted.
	 */
	static String resolveKvCacheDir(String gpValue, String appDataDir) {
		String value = gpValue == null ? "" : gpValue.trim();
		if (value.isEmpty()) {
			return new File(appDataDir, "chartsearchai/kvcache").getAbsolutePath();
		}
		if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false")
				|| value.equalsIgnoreCase("none") || value.equalsIgnoreCase("disabled")) {
			return null;
		}
		return value;
	}

	int getKvCacheMaxEntries() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_KV_CACHE_MAX_ENTRIES);
		if (value != null && !value.trim().isEmpty()) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed > 0) {
					return parsed;
				}
			}
			catch (NumberFormatException e) {
				log.warn("Invalid KV-cache max entries '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_LLM_KV_CACHE_MAX_ENTRIES;
	}

	@Override
	public synchronized void close() {
		if (idleUnloadFuture != null) {
			idleUnloadFuture.cancel(false);
			idleUnloadFuture = null;
		}
		stopServer();
	}

	@Override
	public void shutdown() {
		idleTimer.shutdownNow();
		close();
	}

	private void ensureServerRunning() {
		String modelPath = resolveModelPath();
		int currentContextSize = getContextSize();
		// The CONFIGURED (intended) directory, not the effective --slot-save-path. The restart
		// decision must compare intent so a one-time mkdirs failure (which nulls the effective path)
		// does not differ on every call and loop. Do not swap this for loadedSlotSavePath.
		String configuredKvCacheDir = resolveKvCacheDir();

		if (serverProcess != null && serverProcess.isAlive()
				&& !serverNeedsRestart(loadedModelPath, loadedContextSize, loadedKvCacheDir,
						modelPath, currentContextSize, configuredKvCacheDir)) {
			return;
		}

		if (serverProcess != null && serverProcess.isAlive()) {
			log.info("LLM config changed (model {}→{}, ctx {}→{}, kvCacheDir {}→{}), restarting server",
					loadedModelPath, modelPath, loadedContextSize, currentContextSize,
					loadedKvCacheDir, configuredKvCacheDir);
			stopServer();
		}

		startServer(modelPath);
	}

	/**
	 * Decides whether a running llama-server needs to be restarted because its
	 * model path or context size differs from the latest configured values.
	 * Returns false when nothing has been loaded yet — the caller handles the
	 * initial start in that case.
	 */
	static boolean shouldRestartServer(String loadedPath, int loadedCtx,
			String currentPath, int currentCtx) {
		if (loadedPath == null) {
			return false;
		}
		return !loadedPath.equals(currentPath) || loadedCtx != currentCtx;
	}

	/**
	 * Whether a running server must restart, factoring in the KV-cache directory alongside model
	 * path and context size. The {@code loadedKvCacheDir} argument is the CONFIGURED (intended)
	 * directory the server was started for, NOT the effective path that {@code mkdirs} may have
	 * nulled — comparing the effective path here would make a one-time directory-creation failure
	 * differ from the intended value on every subsequent call and restart the server in a loop.
	 * Returns false when nothing is loaded yet (the caller handles the initial start).
	 */
	static boolean serverNeedsRestart(String loadedModel, int loadedCtx, String loadedKvCacheDir,
			String currentModel, int currentCtx, String currentKvCacheDir) {
		if (loadedModel == null) {
			return false;
		}
		return shouldRestartServer(loadedModel, loadedCtx, currentModel, currentCtx)
				|| !Objects.equals(loadedKvCacheDir, currentKvCacheDir);
	}

	/**
	 * Builds the llama-server argument list. Tuned for chart-search workloads:
	 * long input prompts (whole patient charts), single-user latency-sensitive
	 * generation.
	 *
	 * <p>Flag rationale:
	 * <ul>
	 *   <li>{@code -ngl 99} — offload all layers to GPU when a GPU build is in use; no-op on CPU build.</li>
	 *   <li>{@code -fa on} — flash attention; cuts attention compute on long-context chart prompts.</li>
	 *   <li>{@code --parallel 1} — single decode slot. Chart-search is one request at a time per
	 *       process; the default 4 slots add LRU eviction noise that interferes with prefix-cache
	 *       reuse for no benefit.</li>
	 *   <li>{@code --mlock} — pin model weights in RAM so the OS cannot page them out under
	 *       memory pressure, avoiding multi-second stalls on a busy host.</li>
	 *   <li>{@code -b 4096 -ub 1024} — large batch sizes for prompt processing. Default 2048/512
	 *       leaves prompt-processing parallelism on the table for chart-length inputs.</li>
	 *   <li>{@code --cache-reuse 0} + {@code cache_prompt=true} (in request body) — reuse the
	 *       chart prefix's KV cache across successive queries on the same patient via the
	 *       request-body flag (exact-prefix match). The CLI flag is pinned to 0 (llama.cpp's
	 *       default) because the focus-hint prompt structure has byte-identical prefix bytes
	 *       across successive queries on the same patient — KV shifting (the {@code N>0}
	 *       behavior) is for fuzzy prefix matching when the prefix bytes drift slightly, and
	 *       that case doesn't arise on this prompt shape. Note that cache_prompt itself
	 *       introduces a low-level non-determinism on borderline argmax decisions because the
	 *       reused-vs-fresh KV path is numerically close but not bit-identical — observed as
	 *       "is she pregnant?" alternating between Gravida and Self-Induced Abortion on
	 *       successive identical requests for patient 4acc0b80. The trade-off (latency win
	 *       from cache_prompt vs. determinism on borderline questions) is fundamental to
	 *       llama-server's design; cache_prompt stays on because the latency win is the
	 *       whole point.</li>
	 *   <li>{@code --reasoning-budget 0} — disable reasoning channel; json_schema does not
	 *       constrain it and Gemma 4 burns thousands of tokens before the answer.</li>
	 * </ul>
	 *
	 * <p>Thread count and KV-cache dtype are left to llama-server's auto-detect. Explicit
	 * pinning (e.g. {@code -t/--tb}) and KV quantization (e.g. {@code --cache-type-k/v q4_0})
	 * regress prefill on hosts where logical-core count exceeds physical cores or where the
	 * backend's native KV path is faster than the dequantize-on-read kernel — both common.
	 */
	static List<String> buildServerCommand(String binaryPath, String modelPath, int port,
			int contextSize) {
		return buildServerCommand(binaryPath, modelPath, port, contextSize, null);
	}

	/**
	 * As {@link #buildServerCommand(String, String, int, int)} but, when {@code slotSavePath} is
	 * non-blank, adds {@code --slot-save-path} so llama-server can persist a slot's KV cache to disk
	 * and restore it later. Restoring a patient's prefilled chart KV from disk is I/O-bound (tens of
	 * ms) versus re-running the full prefill, which on a GPU-less host is tens of seconds to minutes;
	 * see {@link #warmup}.
	 */
	static List<String> buildServerCommand(String binaryPath, String modelPath, int port,
			int contextSize, String slotSavePath) {
		List<String> cmd = new ArrayList<>();
		cmd.add(binaryPath);
		cmd.add("-m");
		cmd.add(modelPath);
		cmd.add("--port");
		cmd.add(String.valueOf(port));
		cmd.add("-ngl");
		cmd.add("99");
		cmd.add("-fa");
		cmd.add("on");
		cmd.add("-c");
		cmd.add(String.valueOf(contextSize));
		cmd.add("--parallel");
		cmd.add("1");
		cmd.add("--mlock");
		cmd.add("-b");
		cmd.add("4096");
		cmd.add("-ub");
		cmd.add("1024");
		cmd.add("--cache-reuse");
		cmd.add("0");
		cmd.add("--reasoning-budget");
		cmd.add("0");
		if (slotSavePath != null && !slotSavePath.trim().isEmpty()) {
			cmd.add("--slot-save-path");
			cmd.add(slotSavePath.trim());
		}
		cmd.add("--log-disable");
		return cmd;
	}

	private void startServer(String modelPath) {
		// A freshly launched llama-server starts with an EMPTY RAM prompt-cache pool, so no chart is
		// resident. Clear here (not only in stopServer): when the server process is killed externally
		// or crashes, ensureServerRunning restarts it WITHOUT going through stopServer, and a stale
		// residency record would make a cold query wrongly skip the disk restore and re-prefill.
		ramResidentKeys.clear();

		String serverBinaryPath = LlamaServerBinary.resolve();
		serverPort = getServerPort();

		// The intended (configured) directory drives the restart decision; the effective path is
		// what we actually launch with — nulled if the directory can't be created, so save/restore
		// is skipped without forcing a per-call restart (the two must not be conflated).
		String configuredKvCacheDir = resolveKvCacheDir();
		String slotSavePath = configuredKvCacheDir;
		if (slotSavePath != null) {
			// llama-server's --slot-save-path requires the directory to exist; create it up front
			// so the first save does not fail on a fresh install.
			File dir = new File(slotSavePath);
			if (!dir.isDirectory() && !dir.mkdirs()) {
				log.warn("Could not create KV-cache directory {}; disabling disk KV persistence "
						+ "for this server start", slotSavePath);
				slotSavePath = null;
			}
		}

		List<String> command = buildServerCommand(serverBinaryPath, modelPath, serverPort,
				getContextSize(), slotSavePath);

		log.info("Starting llama-server on port {} with model {}", serverPort, modelPath);

		try {
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.redirectErrorStream(true);
			String binDir = new File(serverBinaryPath).getParent();
			if (binDir != null) {
				pb.environment().put("DYLD_LIBRARY_PATH", binDir);
				pb.environment().put("LD_LIBRARY_PATH", binDir);
			}
			serverProcess = pb.start();

			// Drain server output in a daemon thread to prevent buffer blocking
			Thread outputDrain = new Thread(() -> {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(serverProcess.getInputStream(),
								StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						log.debug("llama-server: {}", line);
					}
				}
				catch (IOException e) {
					log.debug("llama-server output stream closed");
				}
			}, "chartsearchai-llama-server-output");
			outputDrain.setDaemon(true);
			outputDrain.start();

			waitForServerReady();
			loadedModelPath = modelPath;
			loadedContextSize = getContextSize();
			loadedKvCacheDir = configuredKvCacheDir;
			loadedSlotSavePath = slotSavePath;
			log.info("llama-server started successfully on port {}", serverPort);
		}
		catch (IOException e) {
			throw new APIException(
					"Failed to start llama-server. Ensure the binary exists at "
							+ serverBinaryPath + ": " + e.getMessage(), e);
		}
	}

	private void waitForServerReady() {
		long deadline = System.currentTimeMillis()
				+ (SERVER_STARTUP_TIMEOUT_SECONDS * 1000L);
		String healthUrl = "http://127.0.0.1:" + serverPort + "/health";

		while (System.currentTimeMillis() < deadline) {
			if (!serverProcess.isAlive()) {
				throw new APIException(
						"llama-server process exited during startup with code "
								+ serverProcess.exitValue());
			}
			try {
				HttpResponse<String> response = getHttpClient().send(
						HttpRequest.newBuilder()
								.uri(URI.create(healthUrl))
								.timeout(Duration.ofSeconds(2))
								.GET()
								.build(),
						HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200) {
					JsonNode json = MAPPER.readTree(response.body());
					String status = json.has("status") ? json.get("status").asText() : "";
					if ("ok".equals(status)) {
						return;
					}
				}
			}
			catch (IOException | InterruptedException e) {
				// Server not ready yet
			}
			try {
				Thread.sleep(HEALTH_POLL_INTERVAL_MS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new APIException("Interrupted while waiting for llama-server to start");
			}
		}
		stopServer();
		throw new APIException("llama-server did not become healthy within "
				+ SERVER_STARTUP_TIMEOUT_SECONDS + " seconds");
	}

	private void stopServer() {
		if (serverProcess != null) {
			log.info("Stopping llama-server");
			serverProcess.destroy();
			try {
				if (!serverProcess.waitFor(10, TimeUnit.SECONDS)) {
					serverProcess.destroyForcibly();
				}
			}
			catch (InterruptedException e) {
				serverProcess.destroyForcibly();
				Thread.currentThread().interrupt();
			}
			serverProcess = null;
			loadedModelPath = null;
			loadedContextSize = -1;
			loadedKvCacheDir = null;
			loadedSlotSavePath = null;
			// The RAM prompt-cache pool dies with the process, so its residency record must too —
			// otherwise the next process would wrongly believe a chart is RAM-resident and skip the
			// disk restore that now actually avoids a re-prefill.
			ramResidentKeys.clear();
		}
		httpClient = null;
	}

	static boolean isContextOverflowError(String responseBody) {
		return LlmResponseParser.isContextOverflowError(responseBody);
	}

	String buildRequestBody(String systemPrompt, String userMessage, boolean stream) {
		return buildRequestBody(systemPrompt, userMessage, stream,
				ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS);
	}

	String buildRequestBody(String systemPrompt, String userMessage, boolean stream,
			int maxTokens) {
		return buildRequestBody(systemPrompt, userMessage, stream, maxTokens,
				ChartAnswerResponseFormat.build(MAPPER, resolveReasoningMaxChars()));
	}

	/** Test seam wrapping {@link ChartSearchAiUtils#getReasoningMaxChars()} (fail-safe 0). */
	int resolveReasoningMaxChars() {
		return ChartSearchAiUtils.getReasoningMaxChars();
	}

	/**
	 * As {@link #buildRequestBody(String, String, boolean, int)} but with a caller-supplied
	 * {@code response_format} (e.g. the verdict-only {@link EntailmentBatchResponseFormat}) in
	 * place of the default chart-answer schema. Every other field — temperature, the pinned
	 * sampler chain, DRY penalties, {@code cache_prompt} — is identical, so KV-cache reuse and
	 * decoding behaviour are unchanged.
	 */
	String buildRequestBody(String systemPrompt, String userMessage, boolean stream,
			int maxTokens, ObjectNode responseFormat) {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("temperature", 0.0);
		root.put("max_tokens", maxTokens);
		root.put("stream", stream);
		// At temperature=0 the decode is greedy (argmax). The default sampler chain
		// (penalties, dry, top_n_sigma, top_k, typ_p, top_p, min_p, xtc, temperature)
		// runs every one of those samplers per token. At greedy most are no-ops on
		// the OUTPUT but still consume CPU, so we pin the chain to the two samplers
		// that matter: DRY (penalizes multi-token sequence repeats, which is the
		// failure mode small models exhibit on chart search — verbatim repetition
		// of date+finding blocks — without penalizing single-token repetition,
		// which is required for legitimate extraction of dates and identifiers
		// from the chart) and temperature.
		ArrayNode samplers = MAPPER.createArrayNode();
		samplers.add("dry");
		samplers.add("temperature");
		root.set("samplers", samplers);
		// DRY parameters tuned for extractive QA over patient charts: penalize
		// n-gram repeats of length >= 8. Catastrophic loops observed in the
		// 14-model benchmark were all 10+ token sequences ("Ovarian cyst, Zika
		// virus disease, Haemoglobin: 15.8 g/dL (HIGH)," is ~18 tokens; "On
		// 2024-02-28, the patient had a high temperature" is 15+). At
		// allowed_length=4 the penalty fired on 4-7 token date n-grams in the
		// chart context (e.g. "on 2023-05-04 [") and forced the model to drift
		// to neighboring digits (2023-05-04 -> 2023-05-03, 2026-02-28 ->
		// 2026-02-18) or switch scripts to dodge ("Serum potassium" -> "Serum
		// पोटेशियम"). allowed_length=8 keeps the loop-catching margin while
		// letting date and identifier n-grams pass through unchanged.
		root.put("dry_multiplier", 0.8);
		root.put("dry_base", 1.75);
		root.put("dry_allowed_length", 8);
		root.put("dry_penalty_last_n", -1);
		// llama.cpp-specific extension. Without it, each request reprocesses the
		// whole prompt from scratch, so successive queries on the same patient
		// pay full prefill cost every time. With it set, llama-server reuses
		// the slot's KV cache for any matching prefix — typically the system
		// prompt + chart text are byte-identical between queries on one patient,
		// so only the new question's tokens need processing. Order-of-magnitude
		// latency win for repeat queries.
		root.put("cache_prompt", true);
		if (stream) {
			ObjectNode streamOptions = MAPPER.createObjectNode();
			streamOptions.put("include_usage", true);
			root.set("stream_options", streamOptions);
		}

		root.set("response_format", responseFormat);
		root.set("messages", ChatMessages.systemAndUser(MAPPER, systemPrompt, userMessage));

		try {
			return MAPPER.writeValueAsString(root);
		}
		catch (IOException e) {
			throw new APIException("Failed to build request body", e);
		}
	}

	private String getCompletionsUrl() {
		return "http://127.0.0.1:" + serverPort + "/v1/chat/completions";
	}

	private String resolveModelPath() {
		String configuredPath = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);
		if (configuredPath == null || configuredPath.trim().isEmpty()) {
			throw new IllegalStateException(
					"LLM model path not configured. Set the global property: "
							+ ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);
		}
		return ChartSearchAiUtils.resolveModelPath(
				configuredPath.trim(), ChartSearchAiConstants.GP_LLM_MODEL_FILE_PATH);
	}

	int getContextSize() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_CONTEXT_SIZE);
		if (value != null && !value.trim().isEmpty()) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed > 0) {
					return parsed;
				}
			}
			catch (NumberFormatException e) {
				log.warn("Invalid context size '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_LLM_CONTEXT_SIZE;
	}

	int getServerPort() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_SERVER_PORT);
		if (value != null && !value.trim().isEmpty()) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed > 0 && parsed <= 65535) {
					return parsed;
				}
			}
			catch (NumberFormatException e) {
				log.warn("Invalid server port '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_LLM_SERVER_PORT;
	}

	private int getIdleTimeoutMinutes() {
		String value = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_LLM_IDLE_TIMEOUT_MINUTES);
		if (value != null && !value.trim().isEmpty()) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed >= 0) {
					return parsed;
				}
			}
			catch (NumberFormatException e) {
				log.warn("Invalid idle timeout value '{}', using default", value);
			}
		}
		return ChartSearchAiConstants.DEFAULT_LLM_IDLE_TIMEOUT_MINUTES;
	}

	private synchronized void resetIdleTimer() {
		if (idleUnloadFuture != null) {
			idleUnloadFuture.cancel(false);
			idleUnloadFuture = null;
		}
		int idleMinutes = getIdleTimeoutMinutes();
		if (idleMinutes > 0) {
			try {
				idleUnloadFuture = idleTimer.schedule(() -> {
					log.info("LLM idle for {} minutes, stopping server to free memory",
							idleMinutes);
					close();
				}, idleMinutes, TimeUnit.MINUTES);
			}
			catch (java.util.concurrent.RejectedExecutionException e) {
				log.debug("Idle timer already shut down, skipping schedule");
			}
		}
	}

	private synchronized HttpClient getHttpClient() {
		if (httpClient == null) {
			httpClient = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(5))
					.build();
		}
		return httpClient;
	}

	private static String truncate(String text) {
		if (text == null) {
			return "";
		}
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}

}
