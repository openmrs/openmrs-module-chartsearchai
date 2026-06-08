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

		@Override
		protected String gp(String property, String defaultValue) {
			return gps.containsKey(property) ? gps.get(property) : defaultValue;
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
}
