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

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code model-manifest.tsv} for the two suites that check it — {@link
 * ModelDownloadIntegrityTest}, which reconciles each row against what the shell library answers,
 * and {@link ModelDownloadPinningGuardTest}, which checks the rows themselves.
 *
 * <p><b>One reader, so the format has two parsers rather than three.</b> {@code _mm_field} in
 * {@code scripts/model-manifest.sh} is the production one; a copy in each suite made three, and a
 * change to the format — a fifth column, a comment convention — could land in two of them and leave
 * the third quietly reading something else. Of the two that remain, this one is reconciled against
 * the shell by {@code ModelDownloadIntegrityTest.everyLookupReturnsTheFieldOnThatArtifactsOwnRow},
 * which compares every lookup the library answers against the row read here.
 */
public final class ModelManifest {

	private ModelManifest() {
	}

	public static Path path() {
		return ModuleSourceRoot.repoRoot().resolve("model-manifest.tsv");
	}

	/**
	 * The artifact rows, each split into id, sha256, bytes and url. Blank lines and whole-line
	 * comments are skipped, which is the same rule {@code _mm_field} applies.
	 *
	 * <p>An empty manifest throws rather than returning nothing: every caller loops over these rows,
	 * so an empty list would make each of their checks vacuously true.
	 */
	public static List<String[]> rows() throws IOException {
		List<String[]> rows = new ArrayList<String[]>();
		for (String line : Files.readAllLines(path(), StandardCharsets.UTF_8)) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
				rows.add(trimmed.split("\\s+"));
			}
		}
		assertFalse(rows.isEmpty(), "the manifest at " + path() + " carries no artifact rows");
		return rows;
	}
}
