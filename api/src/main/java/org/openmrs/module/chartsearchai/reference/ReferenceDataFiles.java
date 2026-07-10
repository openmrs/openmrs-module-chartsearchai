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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one operator-path → classpath-fallback resolution used by the drug-reference data
 * files ({@link JsonDrugReferenceSource} and {@link CrossReactivityGroupsLoader}): prefer
 * the file the GP points at (relative to the OpenMRS application data directory), fall
 * back to the dataset bundled on the module classpath, and degrade every failure to an
 * empty list — the drug-reference feature is an additive net that must never break the
 * answer path. Shared so the fallback/logging/exception contract cannot drift between
 * the two datasets. ({@link AtcDrugReferenceSource} deliberately stays out: it has no
 * bundled fallback — the operator must point at an obtained ATC export.)
 */
final class ReferenceDataFiles {

	private static final Logger log = LoggerFactory.getLogger(ReferenceDataFiles.class);

	/** Parses one dataset stream into its items. */
	@FunctionalInterface
	interface DatasetParser<T> {

		List<T> parse(InputStream in) throws IOException;
	}

	private ReferenceDataFiles() {
	}

	/**
	 * @param pathGlobalProperty GP holding the operator path (relative to the app data directory)
	 * @param classpathDefault absolute classpath resource of the bundled dataset
	 * @param datasetLabel human label used in log lines (e.g. "drug-reference entries")
	 * @param parser the dataset's parser
	 * @return the parsed items from the operator file, else from the bundled dataset, else empty —
	 *         never null, never an exception
	 */
	static <T> List<T> loadWithClasspathFallback(String pathGlobalProperty, String classpathDefault,
			String datasetLabel, DatasetParser<T> parser) {
		// Fail-safe read returns "" when unset/blank or no context is available -> classpath default.
		String configuredPath = ChartSearchAiUtils.getStringGlobalProperty(pathGlobalProperty, "");

		if (!configuredPath.isEmpty()) {
			try {
				String resolved = ChartSearchAiUtils.resolveModelPath(configuredPath, pathGlobalProperty);
				try (InputStream in = new FileInputStream(new File(resolved))) {
					List<T> loaded = parser.parse(in);
					log.info("Loaded {} {} from {}", loaded.size(), datasetLabel, resolved);
					return loaded;
				}
			}
			catch (IllegalStateException e) {
				// File not configured/found/path-invalid -> fall back to the bundled default.
				log.info("{} file '{}' not available ({}); using bundled default",
						datasetLabel, configuredPath, e.getMessage());
			}
			catch (IOException e) {
				log.warn("Failed to read {} file '{}'; using bundled default", datasetLabel, configuredPath, e);
			}
		}

		try (InputStream in = ReferenceDataFiles.class.getResourceAsStream(classpathDefault)) {
			if (in == null) {
				log.warn("Bundled {} dataset {} not found on classpath; running empty",
						datasetLabel, classpathDefault);
				return Collections.emptyList();
			}
			List<T> loaded = parser.parse(in);
			log.info("Loaded {} {} from bundled default {}", loaded.size(), datasetLabel, classpathDefault);
			return loaded;
		}
		catch (IOException e) {
			log.error("Failed to parse bundled {} dataset; running empty", datasetLabel, e);
			return Collections.emptyList();
		}
	}
}
