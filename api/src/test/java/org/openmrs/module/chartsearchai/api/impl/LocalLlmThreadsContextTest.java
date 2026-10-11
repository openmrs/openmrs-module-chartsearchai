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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * How {@code chartsearchai.llm.threads} is read: the value {@link LocalLlmEngine#getThreads()} hands
 * to {@code buildServerCommand} on every server start. Anything that is not a positive integer reads
 * as auto-detect, so a typo launches what an install launched before the property existed rather
 * than a server pinned to a thread count nobody chose.
 */
public class LocalLlmThreadsContextTest extends BaseModuleContextSensitiveTest {

	@Test
	public void getThreads_shouldLeaveThreadCountToAutoDetectWhenUnset() {
		setThreads("");

		assertEquals(LocalLlmEngine.THREADS_AUTO, new LocalLlmEngine().getThreads());
	}

	@Test
	public void getThreads_shouldReturnTheConfiguredPositiveCount() {
		setThreads(" 3 ");

		assertEquals(3, new LocalLlmEngine().getThreads());
	}

	@Test
	public void getThreads_shouldFallBackToAutoDetectOnAnythingButAPositiveInteger() {
		for (String value : new String[] { "0", "-2", "three", "2.5" }) {
			setThreads(value);

			assertEquals(LocalLlmEngine.THREADS_AUTO, new LocalLlmEngine().getThreads(),
					"'" + value + "' must not pin llama-server to a thread count");
		}
	}

	/**
	 * The two places the setting takes effect, through the methods {@code startServer} and
	 * {@code ensureServerRunning} call: the command a start launches carries the configured {@code -t},
	 * and a later change to it is a configuration change that relaunches the server.
	 */
	@Test
	public void theConfiguredThreadCountReachesTheLaunchedCommandAndAChangeToItRelaunches() {
		setThreads("3");
		LocalLlmEngine engine = new LocalLlmEngine();

		List<String> command = engine.currentServerCommand("/bin/llama-server", "/data/model.gguf", 9999, null);

		int at = command.indexOf("-t");
		assertTrue(at >= 0, "chartsearchai.llm.threads must reach the command llama-server is launched with");
		assertEquals("3", command.get(at + 1));

		engine.recordLaunch("/data/model.gguf", engine.resolveKvCacheDir(), null, command);
		assertFalse(engine.configurationChangedSinceLaunch("/data/model.gguf"),
				"a server launched with the configured count must not restart on every query");

		setThreads("4");
		assertTrue(engine.configurationChangedSinceLaunch("/data/model.gguf"),
				"a changed count must relaunch the server, or an operator A/B-testing it measures the old one");

		setThreads("");
		assertTrue(engine.configurationChangedSinceLaunch("/data/model.gguf"),
				"clearing it must relaunch the server without -t");
	}

	private static void setThreads(String value) {
		GlobalProperty gp = Context.getAdministrationService()
				.getGlobalPropertyObject(ChartSearchAiConstants.GP_LLM_THREADS);
		if (gp == null) {
			gp = new GlobalProperty(ChartSearchAiConstants.GP_LLM_THREADS, value);
		}
		else {
			gp.setPropertyValue(value);
		}
		Context.getAdministrationService().saveGlobalProperty(gp);
	}
}
