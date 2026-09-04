/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;

/**
 * Locks {@link WarmupExecutor#submit}'s queryScoped short-circuit: in scoped mode every warmup
 * is a guaranteed downstream no-op, so submit must return before spawning a daemon task (and
 * before the {@code isWarmupEnabled()} GP read). The observable here is deliberate: this test
 * runs without an OpenMRS context, so reaching {@code isWarmupEnabled()} throws — the scoped
 * gate returning FIRST is exactly what makes the call complete cleanly.
 */
public class WarmupExecutorTest {

	private static final class TestableWarmupExecutor extends WarmupExecutor {

		boolean queryScopedMode = false;

		@Override
		protected boolean isQueryScopedMode() {
			return queryScopedMode;
		}
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	@Test
	public void submit_shouldShortCircuitBeforeAnyGpReadOrDaemonSpawn_inQueryScopedMode() {
		TestableWarmupExecutor executor = new TestableWarmupExecutor();
		executor.queryScopedMode = true;

		// Context-free: any code past the scoped gate (isWarmupEnabled's GP read, the daemon
		// spawn) would throw. Completing cleanly proves the short-circuit fires first.
		assertDoesNotThrow(() -> executor.submit(patient()),
				"queryScoped submit must return before the GP read / daemon spawn");
	}
}
