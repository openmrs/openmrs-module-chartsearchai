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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Verifies that {@link LlmInferenceService#warmup(Patient)} correctly delegates
 * to {@link LlmInferenceService#shouldRunWarmup} and respects its decision at
 * the call site.
 *
 * <p>The companion {@code LlmInferenceServiceWarmupTest} covers the pure-logic
 * helper in isolation. This test locks the WIRING: that {@code warmup()} actually
 * uses the helper to gate the {@code chartBuildingStrategy.buildChart} +
 * {@code llmProvider.warmup} calls, and that the operational kill switches
 * short-circuit before the gate. Without this, a future refactor could bypass the
 * helper or evaluate the kill switches in the wrong order and the helper-only test
 * would still pass while the call-site behavior silently regressed.
 */
public class LlmInferenceServiceWarmupIntegrationTest {

	private static Patient patient(int id) {
		Patient p = new Patient();
		p.setPatientId(id);
		p.setUuid("uuid-" + id);
		return p;
	}

	private CountingChartBuildingStrategy strategy;
	private CountingLlmProvider provider;
	private TestableLlmInferenceService service;

	@BeforeEach
	public void setUp() {
		strategy = new CountingChartBuildingStrategy();
		provider = new CountingLlmProvider();
		service = new TestableLlmInferenceService();
		service.setChartBuildingStrategy(strategy);
		service.setLlmProvider(provider);
		// Default-favorable so individual tests just flip the gate they want to exercise.
		service.warmupEnabledStub = true;
		provider.supportsWarmupStub = true;
		strategy.usePreFilterStub = false;
	}

	@Test
	public void warmup_shouldInvokeProviderWarmup_whenAllGatesAreFavorable() {
		// Happy path: warmup IS expected to fire. Without this positive control, a refactor
		// that always returns early from warmup() would still pass every "should-not-fire"
		// test below.
		service.warmup(patient(1));

		assertTrue(strategy.buildChartCalled,
				"warmup must build the chart so its bytes can prime the KV cache — the whole "
				+ "point of the call");
		assertTrue(provider.warmupCalled,
				"warmup must invoke llmProvider.warmup with the chart bytes; missing this call "
				+ "would silently leave the cache cold for the next real query");
	}

	@Test
	public void warmup_shouldSkipAllSideEffects_whenWarmupDisabled() {
		service.warmupEnabledStub = false;

		service.warmup(patient(1));

		assertFalse(strategy.buildChartCalled);
		assertFalse(provider.warmupCalled,
				"warmup disabled at the top level must short-circuit ALL side effects, not "
				+ "just provider.warmup — the chart build is itself expensive and must skip");
		// Short-circuit semantic: when warmup is the top-level kill switch, no downstream
		// gate should be evaluated. Pre-slice this was implicit in the if-chain; the
		// extracted helper made it eager, which was a subtle regression. Lock the restored
		// short-circuit so a future re-eager refactor would fail this assertion.
		assertEquals(0, provider.supportsWarmupCalls,
				"warmupEnabled=false must skip the supportsWarmup() call — eager evaluation "
				+ "would needlessly hit the LLM provider's engine state on every chart open");
		assertEquals(0, strategy.usePreFilterCalls,
				"warmupEnabled=false must skip the usePreFilter() call — eager evaluation "
				+ "would needlessly read a GP on every chart open when warmup is globally off");
	}

	@Test
	public void warmup_shouldSkipAllSideEffects_whenProviderDoesNotSupportWarmup() {
		provider.supportsWarmupStub = false;

		service.warmup(patient(1));

		assertFalse(strategy.buildChartCalled);
		assertFalse(provider.warmupCalled);
		// Short-circuit semantic, parallel to the warmupEnabled=false case: a remote-engine
		// deployment must not waste the usePreFilter GP read on every chart open just to
		// discover the engine doesn't support warmup. Without this assertion, a future refactor
		// that pulled supportsWarmup into shouldRunWarmup as an eager arg would silently regress
		// the short-circuit.
		assertEquals(0, strategy.usePreFilterCalls,
				"supportsWarmup=false must short-circuit before the usePreFilter GP read — "
				+ "remote-engine deployments shouldn't pay this cost on every chart open");
	}

	@Test
	public void warmup_shouldFire_whenPreFilterEnabled() {
		// preFilter is the only remaining retrieval dimension post-#51. Its focus hint is a small
		// trailing payload; the chart prefix stays question-independent, so warmup must still fire.
		strategy.usePreFilterStub = true;

		service.warmup(patient(1));

		assertTrue(strategy.buildChartCalled,
				"preFilter on still yields a stable chart prefix; warmup must build it");
		assertTrue(provider.warmupCalled,
				"preFilter on still yields a stable chart prefix; provider.warmup must fire");
	}

	// ---- query-path KV cache scope (same chart-stability gate as warmup) ----

	@Test
	public void kvCacheScopeFor_returnsPatientUuid_whenPreFilterOff() {
		// preFilter off => full chart, question-independent => a per-patient KV entry matches the
		// next query, so the streaming query path must scope its restore/save to this patient's UUID.
		strategy.usePreFilterStub = false;

		assertEquals("uuid-1", service.kvCacheScopeFor(patient(1)),
				"a stable chart prefix must scope query-path KV to the patient UUID so the engine can "
				+ "restore/persist this patient's prefilled chart");
	}

	@Test
	public void kvCacheScopeFor_returnsPatientUuid_whenPreFilterOn() {
		// preFilter on keeps the records section byte-identical across queries (the focus hint is a
		// small trailing payload), so the scope is still viable.
		strategy.usePreFilterStub = true;

		assertEquals("uuid-1", service.kvCacheScopeFor(patient(1)));
	}

	@Test
	public void kvCacheScopeFor_returnsNull_forNullPatientOrUuid() {
		strategy.usePreFilterStub = false; // chart would otherwise be stable; isolate the null guards
		assertNull(service.kvCacheScopeFor(null), "no patient => no scope");
		Patient noUuid = new Patient();
		noUuid.setPatientId(99);
		noUuid.setUuid(null); // BaseOpenmrsObject auto-assigns a UUID, so null it explicitly
		assertNull(service.kvCacheScopeFor(noUuid),
				"a patient without a UUID cannot key a KV entry, so the scope must be null");
	}

	/**
	 * Subclass exposing the static-call seams as instance state for tests.
	 * Production resolves these via OpenMRS Context; this stub avoids that.
	 */
	private static final class TestableLlmInferenceService extends LlmInferenceService {

		boolean warmupEnabledStub;

		@Override
		protected boolean resolveWarmupEnabled() {
			return warmupEnabledStub;
		}
	}

	/**
	 * Stub that records whether {@code buildChart} was called and exposes a knob
	 * for the {@code usePreFilter} gate. All autowired fields in the superclass
	 * stay null — tests never exercise paths that touch them.
	 */
	private static final class CountingChartBuildingStrategy extends ChartBuildingStrategy {

		boolean buildChartCalled = false;

		boolean usePreFilterStub = false;

		int usePreFilterCalls = 0;

		@Override
		PatientChart buildChart(Patient patient, String question) {
			buildChartCalled = true;
			return new PatientChart("", Collections.emptyList());
		}

		@Override
		boolean usePreFilter() {
			usePreFilterCalls++;
			return usePreFilterStub;
		}
	}

	/**
	 * Stub that records whether {@code warmup} was called and exposes a knob for the
	 * {@code supportsWarmup} gate. Inherits an unconfigured autowired-fields state from
	 * the superclass — the test never calls a path (search, searchStreaming) that needs
	 * the underlying engines.
	 */
	private static final class CountingLlmProvider extends LlmProvider {

		boolean warmupCalled = false;

		boolean supportsWarmupStub = true;

		int supportsWarmupCalls = 0;

		@Override
		public void warmup(String numberedRecords) {
			warmupCalled = true;
		}

		// Production warms via the scope-aware overload (it passes the patient UUID so the local
		// engine can group a patient's on-disk KV entries). Record that call too, so this wiring
		// lock keeps asserting the same thing — warmup fires when the gates are favorable — rather
		// than falling through to the real LlmProvider (which would read GPs off a missing context).
		@Override
		public void warmup(String numberedRecords, String cacheScope) {
			warmupCalled = true;
		}

		@Override
		public boolean supportsWarmup() {
			supportsWarmupCalls++;
			return supportsWarmupStub;
		}

		// LlmProvider's super has @Autowired engine fields that will be null in tests.
		// Override the methods the test code might trigger indirectly to be safe.
		@Override
		public LlmResponse search(String numberedRecords, String question) {
			throw new UnsupportedOperationException("warmup tests should never reach search");
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, String question,
				Consumer<String> tokenConsumer) {
			throw new UnsupportedOperationException("warmup tests should never reach searchStreaming");
		}
	}
}
