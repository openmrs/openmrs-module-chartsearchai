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

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link LlmInferenceService#shouldRunWarmup}, the pure-logic
 * decision for whether the current retrieval mode produces a chart prefix that
 * warmup can usefully prime.
 *
 * <p>The helper only decides chart-byte-stability semantics. The operational
 * kill switches (`chartsearchai.warmupEnabled` and `LlmProvider.supportsWarmup`)
 * are checked at the {@code warmup()} call site, not here. The call-site wiring
 * is locked by {@link LlmInferenceServiceWarmupIntegrationTest}.
 *
 * <p>Post-#51, querystore is the only retrieval path, so every mode produces a
 * question-independent chart prefix and warmup is always viable — regardless of
 * the {@code preFilter} dimension (its focus hint is a small trailing payload
 * that doesn't break the chart-prefix cache match). The {@code preFilter}
 * parameter is retained as the contract for this single decision point in case a
 * future mode reintroduces a per-query prefix.
 */
public class LlmInferenceServiceWarmupTest {

	@ParameterizedTest(name = "preFilter={0} -> warmup viable")
	@ValueSource(booleans = { false, true })
	public void shouldRunWarmup_isAlwaysViable_postQuerystoreMigration(boolean preFilterEnabled) {
		assertTrue(LlmInferenceService.shouldRunWarmup(preFilterEnabled),
				"every querystore retrieval mode yields a question-independent chart prefix, "
				+ "so warmup is viable regardless of preFilter");
	}
}
