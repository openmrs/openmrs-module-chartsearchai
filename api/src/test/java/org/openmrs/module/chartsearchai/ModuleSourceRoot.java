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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the {@code api} module root for the structural guards — the tests that read production
 * SOURCE rather than production behaviour, because what they pin cannot be observed at runtime.
 *
 * <p>Extracted when {@code EndedOrderAnswerRuleTest} became the THIRD class to need this walk, the
 * threshold {@link org.openmrs.module.chartsearchai.api.impl.LlmEndpointTestSupport}'s own javadoc
 * records for the same move ("Extracted when AbsentDataEvalTest became the third suite to need it";
 * the first two had a copy each). {@code DrugOrderCurrencyMarkTest} carried a byte-identical copy;
 * {@code ArchitectureGuardTest}'s was the same walk with its own comments.
 *
 * <p>It matters more than the line count suggests, for the reason that javadoc gives: these are
 * guards whose whole value is reading the right file, and three copies of the walk can disagree
 * about which module root that is without anything turning red — a structural guard that silently
 * reads nothing, or reads the wrong tree, passes.
 *
 * <p>{@code OrderPartnerNameSourceWritePathTest} deliberately does NOT use this: it resolves one
 * named file and throws when it cannot, rather than falling back to the working directory, because
 * its own javadoc says "Missing is a hard failure, never a skip". Two different contracts, kept
 * apart on purpose.
 */
public final class ModuleSourceRoot {

	private ModuleSourceRoot() {
	}

	/**
	 * The {@code api} module root: the working directory when surefire set it to the module (the
	 * normal case), else the nearest ancestor that looks like the module, else {@code api/} beneath
	 * one. Falls back to the working directory rather than returning null.
	 *
	 * <p><strong>That fallback is not a safety net, and the callers differ in whether they can
	 * tell.</strong> A caller that resolves a NAMED file under this root and asserts it exists
	 * fails loudly on a wrong answer. A caller that WALKS the root does not: it scans nothing, or
	 * the wrong tree, and reports no violations. Measured — forcing this method to an unrelated
	 * directory USED TO leave {@code ArchitectureGuardTest} entirely green while the two
	 * file-resolving callers went red. It no longer does: that class asserts its own scan is
	 * non-empty and found a file it expects, and the same mutation now reddens it. A new WALKING
	 * caller owes itself the same check, because nothing here can give it one.
	 */
	/**
	 * @return the repository root — the directory holding {@code CLAUDE.md}, the project
	 *         instructions, and {@code docs/}. Resolved by walking up from the working
	 *         directory, which surefire sets to the module directory, so the walk is one
	 *         or two steps.
	 *
	 *         <p>Throws rather than falling back to the working directory. A guard that
	 *         cannot find the file it guards must fail loudly: silently resolving to a
	 *         directory with no {@code CLAUDE.md} would make every check in
	 *         {@code ProjectInstructionsGuardTest} vacuously true, which is the
	 *         passes-for-the-wrong-reason failure those checks exist to prevent.
	 */
	public static Path repoRoot() {
		Path current = Paths.get("").toAbsolutePath();
		while (current != null) {
			if (Files.isRegularFile(current.resolve("CLAUDE.md"))
					&& Files.isDirectory(current.resolve("docs"))) {
				return current;
			}
			current = current.getParent();
		}
		throw new IllegalStateException(
				"Could not locate the repository root (a directory holding CLAUDE.md and docs/) "
						+ "walking up from " + Paths.get("").toAbsolutePath());
	}

	public static Path apiRoot() {
		Path current = Paths.get("").toAbsolutePath();
		while (current != null) {
			if (Files.exists(current.resolve("src/main/java"))
					&& Files.exists(current.resolve("src/test/java"))) {
				return current;
			}
			Path api = current.resolve("api");
			if (Files.exists(api.resolve("src/main/java"))) {
				return api;
			}
			current = current.getParent();
		}
		return Paths.get("").toAbsolutePath();
	}
}
