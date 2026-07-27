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

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Build-time guard that fails if someone reintroduces pipeline logic
 * duplication. Scans Java source files for known anti-patterns and
 * reports violations as test failures.
 *
 * <p>This test exists because the same category of bug — tests or
 * production code reimplementing logic that belongs in a shared method —
 * has caused multiple production incidents. Visibility restrictions
 * (private methods) catch some cases, but hardcoded strings, formula
 * reimplementations, and helper duplication can only be caught by
 * scanning the source.
 */
public class ArchitectureGuardTest {

	private static final Path SRC_ROOT = findSourceRoot();

	// --- Rules ---

	/**
	 * No file outside ChartSearchAiConstants should call getEmbeddingPrefix().
	 * It is private, so the compiler enforces this for production code, but
	 * this test catches reflection hacks or accidental visibility changes.
	 */
	@Test
	public void noDirectGetEmbeddingPrefixCalls() throws IOException {
		List<String> violations = scanForPattern(
				SRC_ROOT,
				Pattern.compile("getEmbeddingPrefix\\s*\\("),
				"ChartSearchAiConstants.java|ChartSearchAiUtils.java|ArchitectureGuardTest.java",
				"Should use buildPrefixedText() instead of getEmbeddingPrefix()");
		assertNoViolations(violations);
	}

	/**
	 * No file should hardcode the embedding prefix strings that
	 * getEmbeddingPrefix() returns. If someone writes
	 * {@code "Clinical observation: " + text} they are bypassing
	 * buildPrefixedText().
	 */
	@Test
	public void noHardcodedEmbeddingPrefixes() throws IOException {
		// Match quoted prefix strings followed by concatenation or variable use.
		// Exclude ChartSearchAiConstants (where prefixes are defined),
		// TestDatasetHelper (where dataset strings naturally contain them),
		// and this test file itself.
		Pattern pattern = Pattern.compile(
				"\"(Clinical observation: |Medical condition: "
				+ "|Patient allergy: |Clinical diagnosis: "
				+ "|Medication prescription: |Lab or diagnostic test: "
				+ "|Clinical referral: |Clinical order: "
				+ "|Program enrollment: |Medication dispensed: )\"");
		List<String> violations = scanForPattern(
				SRC_ROOT, pattern,
				"ChartSearchAiConstants.java|ChartSearchAiUtils.java|TestDatasetHelper.java|ArchitectureGuardTest.java",
				"Should use buildPrefixedText() instead of hardcoded prefix strings");
		assertNoViolations(violations);
	}

	/**
	 * No file should reimplement cosine similarity. The canonical
	 * implementation is in ChartSearchAiUtils.cosineSimilarity().
	 * Reimplementations typically contain {@code dot +=} and
	 * {@code na +=} or {@code normA +=} in the same method.
	 */
	@Test
	public void noReimplementedCosineSimilarity() throws IOException {
		// Detect the common reimplementation pattern: a loop body that
		// computes dot product and norms.
		Pattern pattern = Pattern.compile(
				"dot\\s*\\+=\\s*[ab]\\[");
		List<String> violations = scanForPattern(
				SRC_ROOT, pattern,
				"ChartSearchAiConstants.java|ChartSearchAiUtils.java",
				"Should use ChartSearchAiUtils.cosineSimilarity() "
				+ "instead of reimplementing the formula");
		assertNoViolations(violations);
	}

	/**
	 * The dataset helpers (inferResourceType, stripDatasetPrefixAndDate,
	 * DATASET_PREFIXES) should only exist in TestDatasetHelper. Any other
	 * test file defining these is duplicating shared logic.
	 */
	@Test
	public void noDuplicatedDatasetHelpers() throws IOException {
		Pattern pattern = Pattern.compile(
				"(private|static).*(inferResourceType|stripDatasetPrefixAndDate"
				+ "|DATASET_PREFIXES|DATE_PREFIX_PATTERN)");
		List<String> violations = scanForPattern(
				SRC_ROOT, pattern,
				"TestDatasetHelper.java|ArchitectureGuardTest.java",
				"Should use TestDatasetHelper instead of duplicating dataset helpers");
		// Allow the thin delegates in LlmInferenceServiceTest
		List<String> filtered = new ArrayList<>();
		for (String v : violations) {
			// Thin delegate pattern: "return TestDatasetHelper.xxx"
			if (v.contains("return TestDatasetHelper.")) {
				continue;
			}
			filtered.add(v);
		}
		assertNoViolations(filtered);
	}

	/**
	 * The FULL_PATIENT_DATASET and SECOND_PATIENT_DATASET arrays should
	 * only be defined in TestDatasetHelper. Other test files should
	 * reference TestDatasetHelper's copy, not define their own.
	 */
	@Test
	public void noDuplicatedDatasetArrays() throws IOException {
		// Match array declarations containing dataset record literals
		// (lines starting with /* [ and containing "Clinical observation:"
		// or similar). A file defining 10+ such lines is duplicating the
		// dataset.
		Path testDir = SRC_ROOT.resolve(
				"src/test/java/org/openmrs/module/chartsearchai");
		if (!Files.exists(testDir)) {
			return;
		}
		List<String> violations = new ArrayList<>();
		Files.walkFileTree(testDir, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
					throws IOException {
				if (!file.toString().endsWith(".java")) {
					return FileVisitResult.CONTINUE;
				}
				String name = file.getFileName().toString();
				if ("TestDatasetHelper.java".equals(name)
						|| "ArchitectureGuardTest.java".equals(name)) {
					return FileVisitResult.CONTINUE;
				}
				List<String> lines = Files.readAllLines(file);
				int datasetLines = 0;
				for (String line : lines) {
					if (line.contains("/* [") && (
							line.contains("Clinical observation:")
							|| line.contains("Medication prescription:")
							|| line.contains("Medical condition:")
							|| line.contains("Patient allergy:")
							|| line.contains("Program enrollment:"))) {
						datasetLines++;
					}
				}
				if (datasetLines > 5) {
					violations.add(file.getFileName() + ": contains "
							+ datasetLines + " inline dataset records. "
							+ "Use TestDatasetHelper.FULL_PATIENT_DATASET instead.");
				}
				return FileVisitResult.CONTINUE;
			}
		});
		assertNoViolations(violations);
	}

	/**
	 * Chart assembly must turn a question into querystore search text through the ONE composed
	 * entry point {@code QueryPreprocessor.forRetrieval}, never by chaining the steps at the call
	 * site. The three build paths did chain them, and had already drifted: the scoped path
	 * expanded lab abbreviations while the fullChart focus-hint and progressive-reasoning paths
	 * expanded nothing, so the same question retrieved different records depending on the mode.
	 */
	@Test
	public void noHandChainedRetrievalPreprocessing() throws IOException {
		List<String> violations = scanForPattern(
				SRC_ROOT,
				Pattern.compile(
						"(stripQueryStopwords|expandLabPanelAbbreviations|expandClinicalAbbreviations)\\s*\\("),
				"QueryPreprocessor.java|QueryPreprocessorLabExpansionTest.java"
						+ "|QueryPreprocessorClinicalAbbreviationTest.java|QueryPreprocessorRetrievalTextTest.java"
						+ "|LlmInferenceService.java|LlmInferenceServiceTest.java|ArchitectureGuardTest.java",
				"Should call QueryPreprocessor.forRetrieval() — the composed expand-then-strip "
						+ "pipeline — instead of chaining its steps");
		assertNoViolations(violations);
	}

	// --- Infrastructure ---

	/** Cache of file name → lines, populated once by {@link #loadAllSources}. */
	private static java.util.Map<String, List<String>> sourceCache;

	private static java.util.Map<String, List<String>> getSourceCache()
			throws IOException {
		if (sourceCache == null) {
			sourceCache = new java.util.LinkedHashMap<>();
			Files.walkFileTree(SRC_ROOT, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file,
						BasicFileAttributes attrs) throws IOException {
					if (file.toString().endsWith(".java")) {
						sourceCache.put(file.getFileName().toString(),
								Files.readAllLines(file));
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
		return sourceCache;
	}

	private static List<String> scanForPattern(Path root, Pattern pattern,
			String excludeFiles, String message) throws IOException {
		List<String> violations = new ArrayList<>();
		Pattern excludePattern = Pattern.compile(excludeFiles);

		for (java.util.Map.Entry<String, List<String>> entry
				: getSourceCache().entrySet()) {
			String fileName = entry.getKey();
			if (excludePattern.matcher(fileName).find()) {
				continue;
			}
			List<String> lines = entry.getValue();
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				// Skip comments and Javadoc
				String trimmed = line.trim();
				if (trimmed.startsWith("//") || trimmed.startsWith("*")
						|| trimmed.startsWith("/*")) {
					continue;
				}
				if (pattern.matcher(line).find()) {
					violations.add(fileName + ":" + (i + 1)
							+ " — " + message + "\n    " + trimmed);
				}
			}
		}
		return violations;
	}

	private static void assertNoViolations(List<String> violations) {
		if (!violations.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			sb.append(violations.size())
					.append(" architecture violation(s) found:\n\n");
			for (String v : violations) {
				sb.append("  - ").append(v).append("\n");
			}
			sb.append("\nSee CLAUDE.md 'API surface rules' for the correct methods to use.");
			fail(sb.toString());
		}
	}

	private static Path findSourceRoot() {
		// Walk up from the compiled test class to find the api/ module root.
		// Maven sets CWD to the module directory, so check CWD first.
		Path current = Paths.get("").toAbsolutePath();
		while (current != null) {
			if (Files.exists(current.resolve("src/main/java"))
					&& Files.exists(current.resolve("src/test/java"))) {
				return current;
			}
			// Try api/ subdirectory (for when CWD is the project root)
			Path api = current.resolve("api");
			if (Files.exists(api.resolve("src/main/java"))) {
				return api;
			}
			current = current.getParent();
		}
		// Last resort: assume CWD is the module directory
		return Paths.get("").toAbsolutePath();
	}
}
