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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;

/**
 * Unit tests for {@link ChartSearchServiceRouter#buildCacheKey}. The router caches the full
 * {@code ChartAnswer} — including each citation's {@code grounded} verdict — under this key, so
 * every global property that changes the answer (including the grounding GPs) MUST be folded into
 * the key. Otherwise toggling a setting while caching is on serves an answer whose verdicts no
 * longer match the live configuration. These tests pin that contract; the GP reads are supplied
 * through the {@code gp()} seam so the key can be exercised without an OpenMRS context.
 */
public class ChartSearchServiceRouterTest {

	/** Router whose global-property reads come from an in-memory map instead of {@code Context}. */
	private static class StubRouter extends ChartSearchServiceRouter {

		final Map<String, String> gps = new HashMap<String, String>();

		int cacheTtlMinutes;

		@Override
		protected String gp(String property, String defaultValue) {
			return gps.containsKey(property) ? gps.get(property) : defaultValue;
		}

		@Override
		protected int getCacheTtlMinutes() {
			return cacheTtlMinutes;
		}
	}

	private static Patient patient(String uuid) {
		Patient p = new Patient();
		p.setUuid(uuid);
		return p;
	}

	@Test
	public void buildCacheKey_isDeterministicForIdenticalConfig() {
		StubRouter router = new StubRouter();
		assertEquals(router.buildCacheKey(patient("p1"), "any kidney problems?"),
				router.buildCacheKey(patient("p1"), "any kidney problems?"),
				"same patient + question + config must produce the same cache key");
	}

	/**
	 * Sanity check that the harness actually detects key changes — guards against a regression
	 * where the key stops varying at all, which would make every grounding assertion below pass
	 * vacuously.
	 */
	@Test
	public void buildCacheKey_changesWhenANonGroundingGpChanges() {
		StubRouter router = new StubRouter();
		Patient p = patient("p1");
		String before = router.buildCacheKey(p, "q");
		router.gps.put(ChartSearchAiConstants.GP_EMBEDDING_TOP_K, "30");
		assertNotEquals(before, router.buildCacheKey(p, "q"));
	}

	@Test
	public void buildCacheKey_changesWhenGroundingEnabledToggles() {
		StubRouter router = new StubRouter();
		Patient p = patient("p1");
		String groundingOff = router.buildCacheKey(p, "q");
		router.gps.put(ChartSearchAiConstants.GP_GROUNDING_ENABLED, "true");
		assertNotEquals(groundingOff, router.buildCacheKey(p, "q"),
				"toggling grounding.enabled must change the key — else a cached answer's grounded "
				+ "verdicts go stale when the flag flips");
	}

	@Test
	public void buildCacheKey_changesWhenGroundingMinCosineChanges() {
		StubRouter router = new StubRouter();
		Patient p = patient("p1");
		router.gps.put(ChartSearchAiConstants.GP_GROUNDING_MIN_COSINE, "0.40");
		String at040 = router.buildCacheKey(p, "q");
		router.gps.put(ChartSearchAiConstants.GP_GROUNDING_MIN_COSINE, "0.55");
		assertNotEquals(at040, router.buildCacheKey(p, "q"),
				"changing the grounding cosine floor changes which citations are grounded, so it "
				+ "must change the key");
	}

	@Test
	public void buildCacheKey_changesWhenGroundingEntailmentToggles() {
		StubRouter router = new StubRouter();
		Patient p = patient("p1");
		String entailmentOff = router.buildCacheKey(p, "q");
		router.gps.put(ChartSearchAiConstants.GP_GROUNDING_ENTAILMENT_ENABLED, "true");
		assertNotEquals(entailmentOff, router.buildCacheKey(p, "q"),
				"toggling the Tier-2 entailment flag changes verdicts, so it must change the key");
	}

	// ---- async-grounding consumer routing (6-arg searchStreaming) ----

	/**
	 * Delegate that mimics {@code LlmInferenceService}'s live behavior for the async seam: it
	 * streams a token, surfaces citations, fires the ungrounded-answer consumer, then returns the
	 * grounded answer.
	 */
	private static class StubDelegate implements org.openmrs.module.chartsearchai.api.ChartSearchService {

		int streamingCalls;

		final ChartAnswer grounded = new ChartAnswer("Has TB [8].",
				java.util.Arrays.asList(new RecordReference(8, "condition", "u8", null, Boolean.TRUE)));

		private final ChartAnswer ungrounded = new ChartAnswer("Has TB [8].",
				java.util.Arrays.asList(new RecordReference(8, "condition", "u8", null)));

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return grounded;
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				java.util.function.Consumer<String> tokenConsumer) {
			return searchStreaming(patient, question, tokenConsumer, r -> { }, c -> { }, a -> { });
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				java.util.function.Consumer<String> tokenConsumer,
				java.util.function.Consumer<String> reasoningConsumer,
				java.util.function.Consumer<java.util.List<RecordReference>> citationsConsumer,
				java.util.function.Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			streamingCalls++;
			tokenConsumer.accept("Has TB [8].");
			citationsConsumer.accept(ungrounded.getReferences());
			ungroundedAnswerConsumer.accept(ungrounded);
			return grounded;
		}

		@Override
		public void warmup(Patient patient) {
		}
	}

	private static StubRouter routerWith(StubDelegate delegate, int ttlMinutes) {
		StubRouter router = new StubRouter();
		router.cacheTtlMinutes = ttlMinutes;
		router.setLlmService(delegate);
		return router;
	}

	@Test
	public void searchStreaming_passThroughForwardsUngroundedConsumer() {
		StubDelegate delegate = new StubDelegate();
		StubRouter router = routerWith(delegate, 0);
		java.util.List<ChartAnswer> seen = new java.util.ArrayList<ChartAnswer>();

		ChartAnswer answer = router.searchStreaming(patient("p1"), "tb?",
				t -> { }, r -> { }, c -> { }, seen::add);

		assertEquals(1, seen.size(), "uncached path must forward the ungrounded-answer consumer");
		assertEquals(Boolean.TRUE, answer.getReferences().get(0).getGrounded());
	}

	@Test
	public void searchStreaming_cacheHitDoesNotFireUngroundedConsumer() {
		StubDelegate delegate = new StubDelegate();
		StubRouter router = routerWith(delegate, 5);
		java.util.List<ChartAnswer> seen = new java.util.ArrayList<ChartAnswer>();

		router.searchStreaming(patient("p1"), "tb?", t -> { }, r -> { }, c -> { }, seen::add);
		assertEquals(1, seen.size(), "first call is a miss and must fire the consumer");

		java.util.List<String> replayedTokens = new java.util.ArrayList<String>();
		java.util.List<java.util.List<RecordReference>> replayedCitations =
				new java.util.ArrayList<java.util.List<RecordReference>>();
		ChartAnswer hit = router.searchStreaming(patient("p1"), "tb?",
				replayedTokens::add, r -> { }, replayedCitations::add, seen::add);

		assertEquals(1, delegate.streamingCalls, "second call must be served from the cache");
		assertEquals(1, seen.size(),
				"a cached answer is already grounded — there is no pending-grounding stage, so the "
						+ "ungrounded-answer consumer must NOT fire on a cache hit");
		assertEquals(1, replayedTokens.size(), "cache hit still replays the answer token");
		assertEquals(1, replayedCitations.size(), "cache hit still replays the citations");
		assertEquals(Boolean.TRUE, hit.getReferences().get(0).getGrounded(),
				"the cached answer keeps its verdicts");
	}
}
