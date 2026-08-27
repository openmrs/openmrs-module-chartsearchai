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
 * the first two had a copy each). {@code ArchitectureGuardTest} and {@code DrugOrderCurrencyMarkTest}
 * carried byte-identical copies before this.
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
	 * one. Falls back to the working directory, so a caller that resolves a file under it still gets
	 * a path that fails its own existence check rather than a null.
	 */
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
