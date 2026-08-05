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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
 * so the module ships with working defaults (the shared
 * {@link ReferenceDataFiles} resolution).
 *
 * <p>This is the curated/hand-authored source. For authoritative datasets
 * (e.g. WHO ATC) see {@link AtcDrugReferenceSource}; the source is chosen by
 * {@code chartsearchai.drugReference.sourceFormat}.
 */
public class JsonDrugReferenceSource implements DrugReferenceSource {

	private static final Logger log = LoggerFactory.getLogger(JsonDrugReferenceSource.class);

	static final String CLASSPATH_DEFAULT = "/chartsearchai/drug-reference.json";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private volatile String lastLoadOrigin;

	@Override
	public List<DrugReference> load() {
		ReferenceDataFiles.Loaded<DrugReference> loaded = ReferenceDataFiles.loadWithClasspathFallback(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, CLASSPATH_DEFAULT,
				"drug-reference entries", JsonDrugReferenceSource::parse);
		lastLoadOrigin = loaded.getOrigin();
		return loaded.getItems();
	}

	@Override
	public String lastLoadOrigin() {
		return lastLoadOrigin;
	}

	/**
	 * Parse a dataset stream into reference entries. Entries with a blank {@code id} or
	 * {@code name} are dropped (with a warning): a name-less entry would render
	 * {@code "Drug reference — null"} into the citable record and a {@code null} drug into the
	 * safety warnings, and an id-less one has no stable citation {@code resourceUuid}.
	 * Package-private and static so tests can exercise the real parser against the real dataset.
	 */
	static List<DrugReference> parse(InputStream in) throws IOException {
		Dataset dataset = MAPPER.readValue(in, Dataset.class);
		if (dataset == null || dataset.entries == null) {
			return Collections.emptyList();
		}
		List<DrugReference> usable = new ArrayList<DrugReference>();
		int dropped = 0;
		for (DrugReference entry : dataset.entries) {
			if (entry == null || ChartSearchAiUtils.isBlank(entry.getId())
					|| ChartSearchAiUtils.isBlank(entry.getName())) {
				dropped++;
				continue;
			}
			usable.add(entry);
		}
		if (dropped > 0) {
			log.warn("Dropped {} unusable drug-reference entries (blank id or name)", dropped);
		}
		return usable;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class Dataset {

		public List<DrugReference> entries;
	}
}
