/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsUtil;

public class ChartSearchAiConstantsTest extends BaseModuleContextSensitiveTest {

	private static final String GP_NAME = "chartsearchai.test.modelPath";

	@Test
	public void resolveModelPath_shouldRejectPathContainingDotDot() {
		assertThrows(IllegalStateException.class,
				() -> ChartSearchAiConstants.resolveModelPath("../etc/passwd", GP_NAME));
	}

	@Test
	public void resolveModelPath_shouldRejectAbsolutePath() {
		assertThrows(IllegalStateException.class,
				() -> ChartSearchAiConstants.resolveModelPath("/etc/passwd", GP_NAME));
	}

	@Test
	public void resolveModelPath_shouldRejectPathThatEscapesDataDirectory() {
		assertThrows(IllegalStateException.class,
				() -> ChartSearchAiConstants.resolveModelPath("subdir/../../outside", GP_NAME));
	}

	@Test
	public void resolveModelPath_shouldThrowWhenFileDoesNotExist() {
		assertThrows(IllegalStateException.class,
				() -> ChartSearchAiConstants.resolveModelPath("chartsearchai/nonexistent-model.gguf", GP_NAME));
	}

	@Test
	public void resolveModelPath_shouldResolveValidRelativePathWhenFileExists() throws IOException {
		String appDataDir = OpenmrsUtil.getApplicationDataDirectory();
		File subDir = new File(appDataDir, "chartsearchai");
		subDir.mkdirs();
		File tempFile = new File(subDir, "test-model.gguf");
		try {
			assertTrue(tempFile.createNewFile(), "Temp file should be created");

			String resolved = ChartSearchAiConstants.resolveModelPath("chartsearchai/test-model.gguf", GP_NAME);

			assertNotNull(resolved);
			assertTrue(resolved.endsWith("chartsearchai" + File.separator + "test-model.gguf"));
		}
		finally {
			tempFile.delete();
			subDir.delete();
		}
	}
}
