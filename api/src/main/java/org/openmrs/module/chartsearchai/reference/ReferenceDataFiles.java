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
 * files ({@link JsonDrugReferenceSource}, {@link DdiDrugReferenceSource} and
 * {@link CrossReactivityGroupsLoader}): prefer the file the GP points at (relative to the
 * OpenMRS application data directory), fall back to the dataset bundled on the module
 * classpath, and degrade every failure to an empty list — the drug-reference feature is an
 * additive net that must never break the answer path. Shared so the fallback/logging/exception
 * contract cannot drift between these datasets. ({@link AtcDrugReferenceSource} deliberately
 * stays out: it has no bundled fallback — the operator must point at an obtained ATC export.)
 */
final class ReferenceDataFiles {

	private static final Logger log = LoggerFactory.getLogger(ReferenceDataFiles.class);

	/** Prefix marking a {@link Loaded#getOrigin()} that names a bundled classpath resource. */
	static final String CLASSPATH_ORIGIN_PREFIX = "classpath:";

	/**
	 * Prefix marking a {@link Loaded#getOrigin()} that names an operator file, relative to the
	 * OpenMRS application data directory — the same form the path global property holds.
	 *
	 * <p>Relative rather than absolute because the origin is reported over REST to any caller with
	 * the core {@code Get Global Properties} privilege, which the {@code Authenticated} role holds by
	 * default. Such a caller can already read the path global property itself, but not the server's
	 * absolute layout: core keeps its own disclosure of the application data directory behind
	 * {@code View Administration Functions}. Nothing is lost — {@code ChartSearchAiUtils.resolveModelPath}
	 * rejects {@code ..} and confirms the file resolves inside that directory, so this form names the
	 * file exactly, and the absolute path is still logged at INFO by
	 * {@link #loadWithClasspathFallback}. See {@code DrugReferenceLoad#getOrigin()}.
	 */
	static final String APPDATA_ORIGIN_PREFIX = "appdata:";

	/** {@link Loaded#getOrigin()} when nothing could be read at all. */
	static final String ORIGIN_NONE = "none";

	/** Parses one dataset stream into its items. */
	@FunctionalInterface
	interface DatasetParser<T> {

		List<T> parse(InputStream in) throws IOException;
	}

	/**
	 * One dataset load's outcome: the items, and <em>where they came from</em>.
	 *
	 * <p>The origin is returned rather than only logged because the log line is a historical
	 * record. The drug-reference load is lazy, so by the time anyone asks "which dataset is in
	 * force?" the most recent matching line may belong to a previous load or a previous process —
	 * which is exactly how a verification pass came to believe it had switched source when it had
	 * not (issue #149). Returning it lets {@link DrugReferenceService} retain the answer alongside
	 * the entries it describes.
	 */
	static final class Loaded<T> {

		private final List<T> items;

		private final String origin;

		private Loaded(List<T> items, String origin) {
			this.items = items;
			this.origin = origin;
		}

		static <T> Loaded<T> nothing() {
			return new Loaded<T>(Collections.<T> emptyList(), ORIGIN_NONE);
		}

		List<T> getItems() {
			return items;
		}

		/**
		 * @return {@link #APPDATA_ORIGIN_PREFIX} + the operator file's path within the application
		 *         data directory, or {@link #CLASSPATH_ORIGIN_PREFIX} + the bundled resource when the
		 *         fallback was used, or {@link #ORIGIN_NONE} when nothing could be read. Every form
		 *         names the space it came from, which is the distinction a reader needs.
		 */
		String getOrigin() {
			return origin;
		}
	}

	private ReferenceDataFiles() {
	}

	/**
	 * @param pathGlobalProperty GP holding the operator path (relative to the app data directory)
	 * @param classpathDefault absolute classpath resource of the bundled dataset
	 * @param datasetLabel human label used in log lines (e.g. "drug-reference entries")
	 * @param parser the dataset's parser
	 * @return the parsed items from the operator file, else from the bundled dataset, else empty,
	 *         with the origin that produced them — never null, never an exception
	 */
	static <T> Loaded<T> loadWithClasspathFallback(String pathGlobalProperty, String classpathDefault,
			String datasetLabel, DatasetParser<T> parser) {
		// Fail-safe read returns "" when unset/blank or no context is available -> classpath default.
		String configuredPath = ChartSearchAiUtils.getStringGlobalProperty(pathGlobalProperty, "");

		if (!configuredPath.isEmpty()) {
			try {
				String resolved = ChartSearchAiUtils.resolveModelPath(configuredPath, pathGlobalProperty);
				try (InputStream in = new FileInputStream(new File(resolved))) {
					List<T> loaded = parser.parse(in);
					log.info("Loaded {} {} from {}", loaded.size(), datasetLabel, resolved);
					return new Loaded<T>(loaded, APPDATA_ORIGIN_PREFIX + configuredPath);
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
				return Loaded.nothing();
			}
			List<T> loaded = parser.parse(in);
			log.info("Loaded {} {} from bundled default {}", loaded.size(), datasetLabel, classpathDefault);
			return new Loaded<T>(loaded, CLASSPATH_ORIGIN_PREFIX + classpathDefault);
		}
		catch (IOException e) {
			log.error("Failed to parse bundled {} dataset; running empty", datasetLabel, e);
			return Loaded.nothing();
		}
	}
}
