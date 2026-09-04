/*
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

	/**
	 * What this dataset is called, in log lines and in the {@code format} position of a
	 * {@link DrugReferenceValidity} finding. One constant because those two must not drift: a finding
	 * reading "a 'cross-reactivity groups' document must declare [groups]" is what an operator matches
	 * against the log line beside it. It is deliberately NOT a
	 * {@code chartsearchai.drugReference.sourceFormat} value — this file is loaded alongside every format
	 * and has a global property of its own.
	 */
	static final String DATASET_LABEL = "cross-reactivity groups";

	/** What a groups document would have produced, for the finding that says it produced none — see
	 *  {@link DrugReferenceValidity#datasetMissingARequiredTable}. Groups, not entries. */
	private static final String DATASET_ITEMS = "groups";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private volatile String lastLoadOrigin = ReferenceDataFiles.ORIGIN_NONE;

	private volatile String lastConfiguredPath = "";

	private volatile List<DrugReferenceValidity.Finding> lastLoadFindings = Collections.emptyList();

	/**
	 * @return the parsed groups, from the operator file if it could be read and from the bundled default
	 *         otherwise. What the load found wrong reaches the log here AND is retained for
	 *         {@link #lastLoadFindings()}, which {@link DrugReferenceService} reads on this same instance
	 *         immediately afterwards and publishes as {@link CrossReactivityGroupsLoad} — issue #266.
	 *         Until then it reached the log alone, which cannot answer after a lazy load (issue #154) and
	 *         is not a channel an operator can be expected to be watching when a module starts; issue
	 *         #156's second case, the default path naming a file the module never creates, was confirmed
	 *         live on exactly that gap.
	 */
	public List<CrossReactivityGroup> load() {
		ReferenceDataFiles.Loaded<CrossReactivityGroup> loaded = ReferenceDataFiles.loadWithClasspathFallback(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH,
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH, CLASSPATH_DEFAULT,
				DATASET_LABEL, CrossReactivityGroupsLoader::parse);
		lastLoadOrigin = loaded.getOrigin();
		lastConfiguredPath = loaded.getConfiguredPath();
		lastLoadFindings = loaded.getValidity().getFindings();
		loaded.getValidity().logTo(log);
		return loaded.getItems();
	}

	/**
	 * @return where the groups {@link #load()} last returned were read from, in
	 *         {@link ReferenceDataFiles}' origin vocabulary. Read on the same instance immediately after
	 *         {@code load()} and retained beside the groups, for the same reason
	 *         {@link DrugReferenceSource#lastLoadOrigin()} is: the load is lazy, so a log line cannot be
	 *         trusted to describe the load that is in force (issue #149).
	 */
	public String lastLoadOrigin() {
		return lastLoadOrigin;
	}

	/**
	 * @return the groups path global property's value as the resolution that produced
	 *         {@link #lastLoadOrigin()} read it; never null. Reported beside that origin because the two
	 *         are what an operator is told to compare, and taken from the resolution rather than re-read
	 *         so the reported pair provably comes from ONE read of the property.
	 */
	public String lastConfiguredPath() {
		return lastConfiguredPath;
	}

	/**
	 * @return what the validity check found while {@link #load()} resolved AND parsed the groups file:
	 *         the configuration rule only the resolution knows
	 *         ({@link DrugReferenceValidity#configuredDataFileNotRead}) and the document rule only the
	 *         parser knows ({@link DrugReferenceValidity#datasetMissingARequiredTable}). Never null; read
	 *         on the same instance immediately after {@code load()}, exactly as
	 *         {@link DrugReferenceSource#lastLoadFindings()} is.
	 */
	public List<DrugReferenceValidity.Finding> lastLoadFindings() {
		return lastLoadFindings;
	}

	/**
	 * The form for a caller that wants only the groups — package-private and static so tests can
	 * exercise the real parser against the real dataset. Delegates; see {@link #parse(InputStream,
	 * DrugReferenceValidity)} for what parsing this dataset means, and what the parser reports about the
	 * DOCUMENT still reaches the log from here.
	 */
	static List<CrossReactivityGroup> parse(InputStream in) throws IOException {
		DrugReferenceValidity validity = new DrugReferenceValidity();
		List<CrossReactivityGroup> parsed = parse(in, validity);
		validity.logTo(log);
		return parsed;
	}

	/**
	 * Parse a groups stream, reporting what only this parser can see about the document to
	 * {@code validity} — the {@link ReferenceDataFiles.DatasetParser} form, and the one the load takes.
	 * Groups with a blank {@code name} or no usable {@code atcPrefixes} are dropped (with a warning): a
	 * name-less group would render {@code "… is in the same cross-reactivity group (null) …"} into a
	 * safety warning, and a prefix-less one can never match.
	 *
	 * <p>A document declaring no {@code groups} table reports
	 * {@link DrugReferenceValidity#DATASET_MISSING_A_REQUIRED_TABLE} — issue #242's rule, on the third
	 * dataset that has its shape, and issue #266's second half. Until then this parse returned empty in
	 * silence, and unlike the entry datasets there is no {@link DrugReferenceLoad#isInert()} verdict to
	 * make even the emptiness loud, so a groups file of another shape produced no signal at all.
	 *
	 * <p>What is asked of the table is that it be DECLARED, not that it be usable, exactly as
	 * {@link DdiDrugReferenceSource#parse(InputStream, DrugReferenceValidity)} asks it: a document
	 * declaring {@code "groups": []} has said it carries no families, which is a legitimate deployment
	 * choice, and reporting it would make this rule fire on a decision rather than on a defect.
	 */
	static List<CrossReactivityGroup> parse(InputStream in, DrugReferenceValidity validity)
			throws IOException {
		Dataset dataset = MAPPER.readValue(in, Dataset.class);
		if (dataset == null || dataset.groups == null) {
			validity.datasetMissingARequiredTable(DATASET_LABEL,
					Collections.singletonList(DATASET_ITEMS), DATASET_ITEMS, 0);
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
