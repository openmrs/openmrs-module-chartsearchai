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

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The llama-server this module launches keeps no copy of past prompts in RAM (ADR Decision 661).
 *
 * <p>llama-server's host-memory prompt cache ({@code --cache-ram}) is on unless the command turns it off, at
 * 8192 MiB, and it is filled every time the slot moves to an unrelated prompt — a chart prime after another
 * patient's answer, a question-reading call after a chart. Nothing this module sends is ever served from it:
 * every answer restores its chart prefix from the module's own disk entry first (ADR Decision 157), a prime
 * runs with {@code cache_prompt} off, and a reading call's state is never the one saved. So it held host
 * memory and returned none of it, growing with every patient asked about until the idle unload.
 *
 * <p>Read through {@code currentServerCommand}, the command {@code startServer} launches; that it launches
 * that command is {@code ArchitectureGuardTest.theLaunchPathLaunchesAndRecordsTheCurrentServerCommand}.
 */
public class LocalLlmPromptCacheContextTest extends BaseModuleContextSensitiveTest {

	@Test
	public void theServerIsLaunchedWithItsInRamPromptCacheOff() {
		LocalLlmEngine engine = new LocalLlmEngine();

		for (String slotSavePath : new String[] { null, "/var/kvcache" }) {
			List<String> command = engine.currentServerCommand("/bin/llama-server", "/data/model.gguf", 9999,
					slotSavePath);

			assertEquals(1, Collections.frequency(command, "--cache-ram"),
					"llama-server keeps up to 8192 MiB of past prompts in RAM unless told otherwise: " + command);
			assertEquals("0", command.get(command.indexOf("--cache-ram") + 1),
					"0 turns the cache off; any other value leaves RAM held for prompts this module never reuses");
		}
	}
}
