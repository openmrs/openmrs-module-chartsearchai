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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsUtil;

public class ChartSearchAiUtilsTest extends BaseModuleContextSensitiveTest {

	private static final String GP_NAME = "chartsearchai.test.modelPath";

	@Test
	public void resolveModelPath_shouldRejectPathContainingDotDot() {
		assertThrows(IllegalStateException.class,
				() -> ChartSearchAiUtils.resolveModelPath("../etc/passwd", GP_NAME));
	}

	@Test
	public void resolveModelPath_shouldRejectAbsolutePath() {
		assertThrows(IllegalStateException.class,
				() -> ChartSearchAiUtils.resolveModelPath("/etc/passwd", GP_NAME));
	}

	@Test
	public void resolveModelPath_shouldRejectPathThatEscapesDataDirectory() {
		assertThrows(IllegalStateException.class,
				() -> ChartSearchAiUtils.resolveModelPath("subdir/../../outside", GP_NAME));
	}

	@Test
	public void resolveModelPath_shouldThrowWhenFileDoesNotExist() {
		assertThrows(IllegalStateException.class,
				() -> ChartSearchAiUtils.resolveModelPath("chartsearchai/nonexistent-model.gguf", GP_NAME));
	}

	@Test
	public void resolveModelPath_shouldResolveValidRelativePathWhenFileExists() throws IOException {
		String appDataDir = OpenmrsUtil.getApplicationDataDirectory();
		File subDir = new File(appDataDir, "chartsearchai");
		subDir.mkdirs();
		File tempFile = new File(subDir, "test-model.gguf");
		try {
			assertTrue(tempFile.createNewFile(), "Temp file should be created");

			String resolved = ChartSearchAiUtils.resolveModelPath("chartsearchai/test-model.gguf", GP_NAME);

			assertNotNull(resolved);
			assertTrue(resolved.endsWith("chartsearchai" + File.separator + "test-model.gguf"));
		}
		finally {
			tempFile.delete();
			subDir.delete();
		}
	}

	// --- Category-hint enrichment ---
	// These tests guard the metadata flow that makes category-name queries
	// (e.g. "vital signs" → Temperature/BP/Pulse) work when the OpenMRS
	// Concept dictionary classifies the source concept under a containing
	// concept-set. Refactors that drop hints from any link in the chain
	// would silently regress production retrieval; these tests catch that.

	@Test
	public void injectCategoryHints_withEmptyHints_shouldReturnBodyUnchanged() {
		String body = "Finding — Temperature: 36.7";
		assertEquals(body,
				ChartSearchAiUtils.injectCategoryHints(body, Collections.<String>emptyList()));
		assertEquals(body, ChartSearchAiUtils.injectCategoryHints(body, null));
	}

	@Test
	public void injectCategoryHints_withSingleHint_shouldPrependWithSlashSeparator() {
		assertEquals("Vital signs / Finding — Temperature: 36.7",
				ChartSearchAiUtils.injectCategoryHints(
						"Finding — Temperature: 36.7",
						Arrays.asList("Vital signs")));
	}

	@Test
	public void injectCategoryHints_withMultipleHints_shouldJoinWithSlashSeparator() {
		assertEquals("Vital signs / Anthropometric / Finding — Weight: 70",
				ChartSearchAiUtils.injectCategoryHints(
						"Finding — Weight: 70",
						Arrays.asList("Vital signs", "Anthropometric")));
	}

	@Test
	public void buildPrefixedText_threeArg_withEmptyHints_shouldEqualTwoArgVersion() {
		String text = "Finding — Temperature: 36.7";
		assertEquals(
				ChartSearchAiUtils.buildPrefixedText("obs", text),
				ChartSearchAiUtils.buildPrefixedText("obs", text,
						Collections.<String>emptyList()));
	}

	@Test
	public void buildPrefixedText_threeArg_withHints_shouldInjectBetweenPrefixAndBody() {
		// Required for the embedding pipeline to bridge category-name
		// queries: the literal category word must appear in the embedded
		// text. Format: "<structuralPrefix><hint1> / <hint2> / <body>".
		assertEquals(
				"Clinical observation: Vital signs / Finding — Temperature: 36.7",
				ChartSearchAiUtils.buildPrefixedText("obs",
						"Finding — Temperature: 36.7",
						Arrays.asList("Vital signs")));
	}

	@Test
	public void buildPrefixedText_threeArg_shouldMatch_twoArgOnHintInjectedBody() {
		// Invariant: the 3-arg buildPrefixedText (resourceType + body + hints)
		// produces the same string as the 2-arg form applied to the
		// hint-injected body, so any consumer that re-prefixes a stored
		// hint-injected body gets back the exact string originally built.
		String body = "Finding — Pulse: 72";
		java.util.List<String> hints = Arrays.asList("Vital signs");
		String hintInjected = ChartSearchAiUtils.injectCategoryHints(body, hints);
		assertEquals(
				ChartSearchAiUtils.buildPrefixedText("obs", body, hints),
				ChartSearchAiUtils.buildPrefixedText("obs", hintInjected));
	}

	@Test
	public void extractCategoryHints_nullConcept_shouldReturnEmptyList() {
		assertTrue(ChartSearchAiUtils.extractCategoryHints(null).isEmpty());
	}
}
