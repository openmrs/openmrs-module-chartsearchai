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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
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

	private static final Pattern ATC_GROUP_PREFIX =
			Pattern.compile("[A-Z]\\d{2}(?:[A-Z](?:[A-Z](?:\\d{2})?)?)?");

	private volatile ParsedDataset parsedDuringLoad;

	private volatile DrugReferencePackage lastLoadPackage;

	public List<CrossReactivityGroup> load() {
		parsedDuringLoad = null;
		ReferenceDataFiles.Loaded<CrossReactivityGroup> loaded = ReferenceDataFiles.loadWithClasspathFallback(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH, CLASSPATH_DEFAULT,
				"cross-reactivity groups", this::parseAndCapture);
		lastLoadPackage = parsedDuringLoad == null
				? unavailablePackage(loaded.getOrigin())
				: parsedDuringLoad.toPackage(loaded.getOrigin());
		return loaded.getItems();
	}

	public DrugReferencePackage lastLoadPackage() {
		return lastLoadPackage;
	}

	private static DrugReferencePackage unavailablePackage(String origin) {
		return new DrugReferencePackage("unavailable-cross-reactivity-package", "json", null,
				Collections.<String, Object> singletonMap("origin", origin),
				DrugReferencePackage.REVIEW_PROPOSED,
				Collections.singletonList("cross_reactivity_source_unavailable"));
	}

	/**
	 * Parse a groups stream. Groups with a blank {@code name} or no usable {@code atcPrefixes}
	 * are dropped (with a warning): a name-less group would render
	 * {@code "… is in the same cross-reactivity group (null) …"} into a safety warning, and a
	 * prefix-less one can never match. Package-private and static so tests can exercise the real parser against
	 * the real dataset.
	 */
	static List<CrossReactivityGroup> parse(InputStream in) throws IOException {
		return parseDataset(in).groups;
	}

	static DrugReferencePackage parsePackage(InputStream in, String origin) throws IOException {
		return parseDataset(in).toPackage(origin);
	}

	private List<CrossReactivityGroup> parseAndCapture(InputStream in) throws IOException {
		ParsedDataset parsed = parseDataset(in);
		parsedDuringLoad = parsed;
		return parsed.groups;
	}

	private static ParsedDataset parseDataset(InputStream in) throws IOException {
		JsonNode root = MAPPER.readTree(in);
		if (root == null || !root.isObject()) {
			return ParsedDataset.invalid();
		}
		List<String> issues = new ArrayList<String>();
		String packageId = text(root, "packageId");
		String version = text(root, "version");
		String source = text(root, "source");
		if (packageId == null || version == null || source == null) {
			issues.add("cross_reactivity_package_identity_incomplete");
		}
		JsonNode rawGroups = root.get("groups");
		if (rawGroups == null || !rawGroups.isArray()) {
			issues.add("cross_reactivity_data_invalid");
			return new ParsedDataset(Collections.<CrossReactivityGroup> emptyList(),
					packageId, version, source,
					text(root, "reviewState"), issues);
		}
		List<CrossReactivityGroup> usable = new ArrayList<CrossReactivityGroup>();
		int dropped = 0;
		boolean partial = false;
		for (JsonNode rawGroup : rawGroups) {
			CrossReactivityGroup group;
			try {
				group = rawGroup != null && rawGroup.isObject()
						? MAPPER.treeToValue(rawGroup, CrossReactivityGroup.class) : null;
			}
			catch (IOException | RuntimeException e) {
				group = null;
			}
			if (group == null || ChartSearchAiUtils.isBlank(group.getName())
					|| group.normalizedAtcPrefixes().isEmpty()) {
				dropped++;
				continue;
			}
			if (invalidPrefixes(rawGroup)) {
				dropped++;
				partial = true;
				continue;
			}
			usable.add(group);
		}
		if (dropped > 0) {
			log.warn("Dropped {} unusable cross-reactivity groups (invalid name or atcPrefixes)", dropped);
			partial = true;
		}
		String reviewState = text(root, "reviewState");
		if (!validReviewState(reviewState)) {
			partial = true;
		}
		if (partial) {
			issues.add("cross_reactivity_data_partially_invalid");
		}
		return new ParsedDataset(usable, packageId, version, source, reviewState, issues);
	}

	private static boolean invalidPrefixes(JsonNode group) {
		JsonNode prefixes = group == null ? null : group.get("atcPrefixes");
		if (prefixes == null || !prefixes.isArray()) {
			return true;
		}
		for (JsonNode prefix : prefixes) {
			if (prefix == null || !prefix.isTextual() || ChartSearchAiUtils.isBlank(prefix.asText())
					|| !ATC_GROUP_PREFIX.matcher(prefix.asText().trim()
							.toUpperCase(java.util.Locale.ROOT)).matches()) {
				return true;
			}
		}
		return false;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return value != null && value.isTextual() && !ChartSearchAiUtils.isBlank(value.asText())
				? value.asText().trim() : null;
	}

	private static boolean validReviewState(String value) {
		return DrugReferencePackage.REVIEW_PROPOSED.equals(value)
				|| DrugReferencePackage.REVIEW_EVIDENCE_CURATED.equals(value)
				|| DrugReferencePackage.REVIEW_CLINICALLY_APPROVED.equals(value)
				|| DrugReferencePackage.REVIEW_RETIRED.equals(value);
	}

	private static final class ParsedDataset {

		private final List<CrossReactivityGroup> groups;
		private final String packageId;
		private final String version;
		private final String source;
		private final String reviewState;
		private final List<String> issues;

		private ParsedDataset(List<CrossReactivityGroup> groups, String packageId,
				String version, String source, String reviewState, List<String> issues) {
			this.groups = groups;
			this.packageId = packageId;
			this.version = version;
			this.source = source;
			this.reviewState = reviewState;
			this.issues = issues;
		}

		private static ParsedDataset invalid() {
			return new ParsedDataset(Collections.<CrossReactivityGroup> emptyList(), null, null,
					null, DrugReferencePackage.REVIEW_PROPOSED,
					Collections.singletonList("cross_reactivity_data_invalid"));
		}

		private DrugReferencePackage toPackage(String origin) {
			Map<String, Object> provenance = new LinkedHashMap<String, Object>();
			provenance.put("origin", origin);
			if (source != null) {
				provenance.put("source", source);
			}
			return new DrugReferencePackage(
					packageId == null ? "unidentified-cross-reactivity-package" : packageId,
					"json", version, provenance, reviewState, issues);
		}
	}
}
