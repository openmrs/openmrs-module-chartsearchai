/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** Build-time checks for the thin med-agent-hub relay boundary. */
public class ArchitectureGuardTest {

	private static final String[] FORBIDDEN_JAVA_TYPES = {
			"LocalLlmEngine", "LlmInferenceService", "CitationGroundingVerifier",
			"ModelSwitchService", "QueryStoreChartBuilder", "PatientChartSerializer"
	};

	@Test
	public void mainJavaContainsNoLegacyInferenceOrContextPipeline() throws IOException {
		Path root = projectRoot();
		StringBuilder source = new StringBuilder();
		try (Stream<Path> files = Files.walk(root)) {
			files.filter(path -> path.toString().endsWith(".java"))
					.filter(path -> path.toString().contains("/src/main/"))
					.forEach(path -> append(source, path));
		}
		for (String forbidden : FORBIDDEN_JAVA_TYPES) {
			assertFalse(source.toString().contains(forbidden),
					"legacy runtime type returned: " + forbidden);
		}
	}

	@Test
	public void moduleDoesNotRequireQuerystore() throws IOException {
		Path root = projectRoot();
		String apiPom = Files.readString(root.resolve("api/pom.xml"));
		String config = Files.readString(root.resolve("omod/src/main/resources/config.xml"));

		assertFalse(apiPom.contains("querystore-api"));
		assertFalse(config.contains(">org.openmrs.module.querystore</require_module>"));
	}

	@Test
	public void moduleDeclaresOnlyTheHubProductConnection() throws IOException {
		Path root = projectRoot();
		String config = Files.readString(root.resolve("omod/src/main/resources/config.xml"));

		assertTrue(config.contains("chartsearchai.hub.endpointUrl"));
		assertTrue(config.contains("chartsearchai.hub.profileId"));
		assertFalse(config.contains("chartsearchai.llm."));
	}

	private static void append(StringBuilder target, Path path) {
		try {
			target.append(Files.readString(path));
		}
		catch (IOException e) {
			throw new IllegalStateException("Could not inspect " + path, e);
		}
	}

	private static Path projectRoot() {
		Path current = Paths.get("").toAbsolutePath();
		while (current != null) {
			if (Files.exists(current.resolve("api/pom.xml"))
					&& Files.exists(current.resolve("omod/pom.xml"))) {
				return current;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Could not locate ChartSearchAI project root");
	}
}
