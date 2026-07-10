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
 * Loads the curated {@link CrossReactivityGroup} dataset
 * ({@code cross-reactivity-groups.json}). Deliberately NOT a
 * {@link DrugReferenceSource}: groups complement the entry source rather than
 * replace it, and they load independently of the selected {@code sourceFormat} so
 * the rule-less ATC source gains cross-branch family reasoning from the same file.
 *
 * <p>Resolution is the shared {@link ReferenceDataFiles} contract: the
 * operator-configured path in
 * {@link ChartSearchAiConstants#GP_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH}
 * (relative to the OpenMRS application data directory) is preferred; when absent or
 * unreadable — including when no OpenMRS context is available — the dataset bundled
 * on the module classpath at {@code /chartsearchai/cross-reactivity-groups.json} is
 * used, and any failure degrades to an empty list, never an exception.
 */
public class CrossReactivityGroupsLoader {

	private static final Logger log = LoggerFactory.getLogger(CrossReactivityGroupsLoader.class);

	static final String CLASSPATH_DEFAULT = "/chartsearchai/cross-reactivity-groups.json";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public List<CrossReactivityGroup> load() {
		return ReferenceDataFiles.loadWithClasspathFallback(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH, CLASSPATH_DEFAULT,
				"cross-reactivity groups", CrossReactivityGroupsLoader::parse);
	}

	/**
	 * Parse a groups stream. Groups with a blank {@code name} or no usable {@code atcPrefixes}
	 * are dropped (with a warning): a name-less group would render
	 * {@code "same cross-reactivity group (null)"} into a safety warning, and a prefix-less one
	 * can never match. Package-private and static so tests can exercise the real parser against
	 * the real dataset.
	 */
	static List<CrossReactivityGroup> parse(InputStream in) throws IOException {
		Dataset dataset = MAPPER.readValue(in, Dataset.class);
		if (dataset == null || dataset.groups == null) {
			return Collections.emptyList();
		}
		List<CrossReactivityGroup> usable = new ArrayList<CrossReactivityGroup>();
		int dropped = 0;
		for (CrossReactivityGroup group : dataset.groups) {
			if (group == null || ChartSearchAiUtils.isBlank(group.getName())
					|| group.normalizedAtcPrefixes().isEmpty()) {
				dropped++;
				continue;
			}
			usable.add(group);
		}
		if (dropped > 0) {
			log.warn("Dropped {} unusable cross-reactivity groups (blank name or no usable atcPrefixes)", dropped);
		}
		return usable;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class Dataset {

		public List<CrossReactivityGroup> groups;
	}
}
