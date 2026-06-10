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

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link DrugReferenceSource} for the chartsearchai-native JSON format
 * ({@code drug-reference.json}). Resolves the dataset from the path in
 * {@link ChartSearchAiConstants#GP_DRUG_REFERENCE_DATA_FILE_PATH} (relative to the
 * OpenMRS application data directory); when that file is absent or unreadable —
 * including when no OpenMRS context is available — it falls back to the dataset
 * bundled on the module classpath at {@code /chartsearchai/drug-reference.json},
 * so the module ships with working defaults.
 *
 * <p>This is the curated/hand-authored source. For authoritative datasets
 * (e.g. WHO ATC) see {@link AtcDrugReferenceSource}; the source is chosen by
 * {@code chartsearchai.drugReference.sourceFormat}.
 */
public class JsonDrugReferenceSource implements DrugReferenceSource {

	private static final Logger log = LoggerFactory.getLogger(JsonDrugReferenceSource.class);

	static final String CLASSPATH_DEFAULT = "/chartsearchai/drug-reference.json";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Override
	public List<DrugReference> load() {
		// Prefer the operator-configured file in the application data directory. The fail-safe
		// read returns "" when unset/blank or no context is available -> classpath default below.
		String configuredPath = ChartSearchAiUtils.getStringGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, "");

		if (!configuredPath.isEmpty()) {
			try {
				String resolved = ChartSearchAiUtils.resolveModelPath(configuredPath,
						ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH);
				try (InputStream in = new FileInputStream(new File(resolved))) {
					List<DrugReference> loaded = parse(in);
					log.info("Loaded {} drug-reference entries from {}", loaded.size(), resolved);
					return loaded;
				}
			}
			catch (IllegalStateException e) {
				// File not configured/found/path-invalid -> fall back to the bundled default.
				log.info("Drug-reference file '{}' not available ({}); using bundled default",
						configuredPath, e.getMessage());
			}
			catch (IOException e) {
				log.warn("Failed to read drug-reference file '{}'; using bundled default", configuredPath, e);
			}
		}

		try (InputStream in = JsonDrugReferenceSource.class.getResourceAsStream(CLASSPATH_DEFAULT)) {
			if (in == null) {
				log.warn("Bundled drug-reference dataset {} not found on classpath; running empty",
						CLASSPATH_DEFAULT);
				return Collections.emptyList();
			}
			List<DrugReference> loaded = parse(in);
			log.info("Loaded {} drug-reference entries from bundled default {}",
					loaded.size(), CLASSPATH_DEFAULT);
			return loaded;
		}
		catch (IOException e) {
			log.error("Failed to parse bundled drug-reference dataset; running empty", e);
			return Collections.emptyList();
		}
	}

	/**
	 * Parse a dataset stream into reference entries. Package-private and static so
	 * tests can exercise the real parser against the real dataset.
	 */
	static List<DrugReference> parse(InputStream in) throws IOException {
		Dataset dataset = MAPPER.readValue(in, Dataset.class);
		if (dataset == null || dataset.entries == null) {
			return Collections.emptyList();
		}
		return dataset.entries;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class Dataset {

		public List<DrugReference> entries;
	}
}
