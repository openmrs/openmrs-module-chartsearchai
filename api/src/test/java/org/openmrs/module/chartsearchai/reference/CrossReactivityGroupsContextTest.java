/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsUtil;

/**
 * Context-sensitive coverage for the operator-configured branches of
 * {@link CrossReactivityGroupsLoader}: the GP-pointed file in the application data
 * directory is preferred, and a missing/unreadable one falls back to the bundled
 * dataset — exercised through the production {@link DrugReferenceService#getCrossReactivityGroups()}
 * path (the classpath-fallback branch is covered contextlessly in {@link CrossReactivityGroupsTest}).
 */
public class CrossReactivityGroupsContextTest extends BaseModuleContextSensitiveTest {

	private boolean hasGroup(List<CrossReactivityGroup> groups, String name) {
		for (CrossReactivityGroup group : groups) {
			if (name.equalsIgnoreCase(group.getName())) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void missingConfiguredFileFallsBackToTheBundledGroups() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH,
				"chartsearchai/nonexistent-groups.json");
		List<CrossReactivityGroup> groups = new DrugReferenceService().getCrossReactivityGroups();
		assertTrue(hasGroup(groups, "NSAID"),
				"a configured-but-absent groups file must fall back to the bundled dataset");
	}

	@Test
	public void configuredGroupsFileInTheDataDirectoryIsLoaded() throws IOException {
		File dir = new File(OpenmrsUtil.getApplicationDataDirectory(), "chartsearchai");
		dir.mkdirs();
		File file = new File(dir, "test-cross-reactivity-groups.json");
		try {
			Files.write(file.toPath(),
					("{\"groups\":[{\"name\":\"TESTFAM\",\"note\":\"test family\","
							+ "\"atcPrefixes\":[\"J01CA\",\"J01GB\"]}]}").getBytes(StandardCharsets.UTF_8));
			Context.getAdministrationService().setGlobalProperty(
					ChartSearchAiConstants.GP_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH,
					"chartsearchai/test-cross-reactivity-groups.json");

			List<CrossReactivityGroup> groups = new DrugReferenceService().getCrossReactivityGroups();
			assertTrue(hasGroup(groups, "TESTFAM"),
					"the operator-configured groups file must be loaded from the data directory");
			assertFalse(hasGroup(groups, "NSAID"),
					"an operator file REPLACES the bundled groups (same contract as dataFilePath), not merges");
		}
		finally {
			file.delete();
		}
	}
}
