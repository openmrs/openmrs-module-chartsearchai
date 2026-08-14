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

	/**
	 * @return the parsed groups, from the operator file if it could be read and from the bundled default
	 *         otherwise. Any validity finding the resolution produced is reported here rather than
	 *         returned, because this dataset has no retained status object to be read from: issue #154's
	 *         {@code getLoadStatus()} covers the ENTRY dataset only, so the log is the only channel these
	 *         groups have. That is a gap rather than a design — see issue #156's second case, where the
	 *         default path naming a file the module never creates was confirmed live.
	 */
	public List<CrossReactivityGroup> load() {
		ReferenceDataFiles.Loaded<CrossReactivityGroup> loaded = ReferenceDataFiles.loadWithClasspathFallback(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH,
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH, CLASSPATH_DEFAULT,
				// Nothing is reported to the collector from inside this parse, deliberately. Issue #242's
				// rule is worth having wherever a finding can reach BOTH channels, and here it can reach
				// only one: as the javadoc above says, these groups have no retained status object, so a
				// document declaring no `groups` would produce a WARN that nothing can be asked about
				// afterwards. That is the gap issue #156's second case records, and half-closing it one
				// rule at a time is what would leave the loader answering "what is valid?" one way for
				// the entry dataset and another for this one.
				"cross-reactivity groups", (in, unreported) -> parse(in));
		loaded.getValidity().logTo(log);
		return loaded.getItems();
	}

	/**
	 * Parse a groups stream. Groups with a blank {@code name} or no usable {@code atcPrefixes}
	 * are dropped (with a warning): a name-less group would render
	 * {@code "… is in the same cross-reactivity group (null) …"} into a safety warning, and a
	 * prefix-less one can never match. Package-private and static so tests can exercise the real parser against
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
