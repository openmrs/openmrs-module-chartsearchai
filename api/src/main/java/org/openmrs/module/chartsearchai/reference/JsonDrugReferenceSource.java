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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

	private volatile DrugReferencePackage lastLoadPackage;

	private volatile ParsedDataset parsedDuringLoad;

	private static final Pattern ATC_LEVEL_5 = Pattern.compile("[A-Z]\\d{2}[A-Z]{2}\\d{2}");

	@Override
	public List<DrugReference> load() {
		parsedDuringLoad = null;
		ReferenceDataFiles.Loaded<DrugReference> loaded = ReferenceDataFiles.loadWithClasspathFallback(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, CLASSPATH_DEFAULT,
				"drug-reference entries", this::parseAndCapture);
		lastLoadOrigin = loaded.getOrigin();
		lastLoadPackage = parsedDuringLoad == null
				? unavailablePackage(lastLoadOrigin)
				: parsedDuringLoad.toPackage(lastLoadOrigin);
		return loaded.getItems();
	}

	@Override
	public String lastLoadOrigin() {
		return lastLoadOrigin;
	}

	@Override
	public DrugReferencePackage lastLoadPackage() {
		return lastLoadPackage;
	}

	private static DrugReferencePackage unavailablePackage(String origin) {
		return new DrugReferencePackage("unavailable-json-package", "json", null,
				Collections.<String, Object> singletonMap("origin", origin),
				DrugReferencePackage.REVIEW_PROPOSED,
				Collections.singletonList("source_unavailable"));
	}

	/**
	 * Parse a dataset stream into reference entries. Entries with a blank {@code id} or
	 * {@code name} are dropped (with a warning): a name-less entry would render
	 * {@code "Drug reference — null"} into the citable record and a {@code null} drug into the
	 * safety warnings, and an id-less one has no stable citation {@code resourceUuid}.
	 * Package-private and static so tests can exercise the real parser against the real dataset.
	 */
	static List<DrugReference> parse(InputStream in) throws IOException {
		return parseDataset(in).entries;
	}

	static DrugReferencePackage parsePackage(InputStream in, String origin) throws IOException {
		return parseDataset(in).toPackage(origin);
	}

	private List<DrugReference> parseAndCapture(InputStream in) throws IOException {
		ParsedDataset parsed = parseDataset(in);
		parsedDuringLoad = parsed;
		return parsed.entries;
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
			issues.add("source_package_identity_incomplete");
		}
		JsonNode rawEntries = root.get("entries");
		if (rawEntries == null || !rawEntries.isArray()) {
			issues.add("source_data_invalid");
			return new ParsedDataset(Collections.<DrugReference> emptyList(),
					packageId, version, source,
					text(root, "reviewState"), issues);
		}
		List<DrugReference> usable = new ArrayList<DrugReference>();
		int dropped = 0;
		boolean partial = false;
		for (JsonNode rawEntry : rawEntries) {
			DrugReference entry = parseEntry(rawEntry);
			if (entry == null || ChartSearchAiUtils.isBlank(entry.getId())
					|| ChartSearchAiUtils.isBlank(entry.getName())) {
				dropped++;
				continue;
			}
			if (hasRejectedContent(rawEntry)) {
				partial = true;
			}
			sanitizeEntry(entry);
			usable.add(entry);
		}
		if (dropped > 0) {
			log.warn("Dropped {} unusable drug-reference entries (blank id or name)", dropped);
			partial = true;
		}
		String reviewState = text(root, "reviewState");
		if (!validReviewState(reviewState)) {
			partial = true;
		}
		if (partial) {
			issues.add("source_data_partially_invalid");
		}
		return new ParsedDataset(usable, packageId, version, source, reviewState, issues);
	}

	private static DrugReference parseEntry(JsonNode raw) {
		if (raw == null || !raw.isObject() || text(raw, "id") == null || text(raw, "name") == null) {
			return null;
		}
		ObjectNode safe = ((ObjectNode) raw).deepCopy();
		for (String field : new String[] { "id", "name", "genericName", "drugClass", "source" }) {
			sanitizeTextField(safe, field);
		}
		sanitizeTextArray(safe, "aliases", false);
		sanitizeTextArray(safe, "atcCodes", true);
		sanitizeTextArray(safe, "warnings", false);
		sanitizeAgeBands(safe);
		sanitizeInteractions(safe);
		sanitizeContraindications(safe);
		try {
			return MAPPER.treeToValue(safe, DrugReference.class);
		}
		catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static void sanitizeTextField(ObjectNode object, String field) {
		JsonNode value = object.get(field);
		if (value != null && (!value.isTextual() || ChartSearchAiUtils.isBlank(value.asText()))) {
			object.remove(field);
		}
	}

	private static void sanitizeTextArray(ObjectNode object, String field, boolean atcCodes) {
		JsonNode values = object.get(field);
		if (values == null) {
			return;
		}
		ArrayNode safe = MAPPER.createArrayNode();
		if (values.isArray()) {
			for (JsonNode value : values) {
				if (value != null && value.isTextual() && !ChartSearchAiUtils.isBlank(value.asText())
						&& (!atcCodes || validAtcCode(value.asText()))) {
					safe.add(value.asText());
				}
			}
		}
		object.set(field, safe);
	}

	private static void sanitizeAgeBands(ObjectNode object) {
		JsonNode values = object.get("ageBands");
		if (values == null) {
			return;
		}
		ArrayNode safe = MAPPER.createArrayNode();
		if (values.isArray()) {
			for (JsonNode value : values) {
				if (validAgeBand(value)) {
					ObjectNode band = MAPPER.createObjectNode();
					band.put("minYears", value.get("minYears").asInt());
					band.put("maxYears", value.get("maxYears").asInt());
					copyNumber(value, band, "mgPerKgMin");
					copyNumber(value, band, "mgPerKgMax");
					copyNumber(value, band, "maxDailyDoseMg");
					safe.add(band);
				}
			}
		}
		object.set("ageBands", safe);
	}

	private static void copyNumber(JsonNode source, ObjectNode target, String field) {
		JsonNode value = source.get(field);
		if (value != null && !value.isNull()) {
			target.put(field, value.asDouble());
		}
	}

	private static void sanitizeInteractions(ObjectNode object) {
		JsonNode values = object.get("interactions");
		if (values == null) {
			return;
		}
		ArrayNode safe = MAPPER.createArrayNode();
		if (values.isArray()) {
			for (JsonNode value : values) {
				if (value != null && value.isObject() && !invalidInteractions(MAPPER.createArrayNode().add(value))) {
					ObjectNode interaction = MAPPER.createObjectNode();
					copyText(value, interaction, "token");
					copyText(value, interaction, "atc");
					copyText(value, interaction, "note");
					copyText(value, interaction, "severity");
					safe.add(interaction);
				}
			}
		}
		object.set("interactions", safe);
	}

	private static void sanitizeContraindications(ObjectNode object) {
		JsonNode values = object.get("contraindications");
		if (values == null) {
			return;
		}
		ArrayNode safe = MAPPER.createArrayNode();
		if (values.isArray()) {
			for (JsonNode value : values) {
				if (value != null && value.isObject()
						&& !invalidContraindications(MAPPER.createArrayNode().add(value))) {
					ObjectNode contraindication = MAPPER.createObjectNode();
					copyText(value, contraindication, "type");
					copyText(value, contraindication, "token");
					copyText(value, contraindication, "note");
					safe.add(contraindication);
				}
			}
		}
		object.set("contraindications", safe);
	}

	private static void copyText(JsonNode source, ObjectNode target, String field) {
		String value = text(source, field);
		if (value != null) {
			target.put(field, value);
		}
	}

	private static boolean hasRejectedContent(JsonNode raw) {
		if (invalidTextArray(raw, "aliases") || invalidTextArray(raw, "atcCodes")
				|| invalidTextArray(raw, "warnings")) {
			return true;
		}
		JsonNode atcCodes = raw.get("atcCodes");
		if (atcCodes != null && atcCodes.isArray()) {
			for (JsonNode code : atcCodes) {
				if (code == null || !code.isTextual() || !validAtcCode(code.asText())) {
					return true;
				}
			}
		}
		JsonNode ageBands = raw.get("ageBands");
		if (ageBands != null && (!ageBands.isArray() || invalidAgeBand(ageBands))) {
			return true;
		}
		JsonNode interactions = raw.get("interactions");
		if (interactions != null && (!interactions.isArray() || invalidInteractions(interactions))) {
			return true;
		}
		JsonNode contraindications = raw.get("contraindications");
		return contraindications != null
				&& (!contraindications.isArray() || invalidContraindications(contraindications));
	}

	private static void sanitizeEntry(DrugReference entry) {
		entry.setAliases(validText(entry.getAliases()));
		entry.setWarnings(validText(entry.getWarnings()));
		List<String> atcCodes = new ArrayList<String>();
		for (String code : validText(entry.getAtcCodes())) {
			if (ATC_LEVEL_5.matcher(code.trim().toUpperCase(java.util.Locale.ROOT)).matches()) {
				atcCodes.add(code);
			}
		}
		entry.setAtcCodes(atcCodes);

		List<DrugReference.AgeBand> ageBands = new ArrayList<DrugReference.AgeBand>();
		for (DrugReference.AgeBand band : entry.getAgeBands()) {
			if (band != null && band.getMinYears() >= 0 && band.getMaxYears() >= band.getMinYears()
					&& finiteNonNegative(band.getMgPerKgMin())
					&& finiteNonNegative(band.getMgPerKgMax())
					&& finiteNonNegative(band.getMaxDailyDoseMg())) {
				ageBands.add(band);
			}
		}
		entry.setAgeBands(ageBands);

		List<DrugReference.Interaction> interactions = new ArrayList<DrugReference.Interaction>();
		for (DrugReference.Interaction interaction : entry.getInteractions()) {
			String token = interaction == null ? null : interaction.getToken();
			String atc = interaction == null ? null : interaction.getAtc();
			if (interaction != null && (!ChartSearchAiUtils.isBlank(token)
					|| validAtcCode(atc))) {
				if (ChartSearchAiUtils.isBlank(atc) || validAtcCode(atc)) {
					interactions.add(interaction);
				}
			}
		}
		entry.setInteractions(interactions);

		List<DrugReference.Contraindication> contraindications =
				new ArrayList<DrugReference.Contraindication>();
		for (DrugReference.Contraindication contraindication : entry.getContraindications()) {
			String type = contraindication == null ? null : contraindication.getType();
			if (contraindication != null
					&& ("allergy".equalsIgnoreCase(type) || "condition".equalsIgnoreCase(type))
					&& !ChartSearchAiUtils.isBlank(contraindication.getToken())) {
				contraindications.add(contraindication);
			}
		}
		entry.setContraindications(contraindications);
	}

	private static List<String> validText(List<String> values) {
		List<String> out = new ArrayList<String>();
		for (String value : values) {
			if (!ChartSearchAiUtils.isBlank(value)) {
				out.add(value);
			}
		}
		return out;
	}

	private static boolean validAtcCode(String value) {
		return !ChartSearchAiUtils.isBlank(value)
				&& ATC_LEVEL_5.matcher(value.trim().toUpperCase(java.util.Locale.ROOT)).matches();
	}

	private static boolean finiteNonNegative(double value) {
		return !Double.isNaN(value) && !Double.isInfinite(value) && value >= 0;
	}

	private static boolean invalidTextArray(JsonNode object, String field) {
		JsonNode values = object.get(field);
		if (values == null) {
			return false;
		}
		if (!values.isArray()) {
			return true;
		}
		for (JsonNode value : values) {
			if (value == null || !value.isTextual() || ChartSearchAiUtils.isBlank(value.asText())) {
				return true;
			}
		}
		return false;
	}

	private static boolean invalidAgeBand(JsonNode values) {
		for (JsonNode value : values) {
			if (!validAgeBand(value)) {
				return true;
			}
		}
		return false;
	}

	private static boolean validAgeBand(JsonNode value) {
		return value != null && value.isObject() && value.path("minYears").isIntegralNumber()
				&& value.path("maxYears").isIntegralNumber()
				&& value.path("minYears").canConvertToInt()
				&& value.path("maxYears").canConvertToInt()
				&& value.path("minYears").asInt() >= 0
				&& value.path("maxYears").asInt() >= value.path("minYears").asInt()
				&& validOptionalDose(value, "mgPerKgMin")
				&& validOptionalDose(value, "mgPerKgMax")
				&& validOptionalDose(value, "maxDailyDoseMg");
	}

	private static boolean validOptionalDose(JsonNode object, String field) {
		JsonNode value = object.get(field);
		if (value == null || value.isNull()) {
			return true;
		}
		if (!value.isNumber()) {
			return false;
		}
		double number = value.asDouble();
		return finiteNonNegative(number);
	}

	private static boolean invalidInteractions(JsonNode values) {
		for (JsonNode value : values) {
			String atc = text(value, "atc");
			if (value == null || !value.isObject()
					|| (ChartSearchAiUtils.isBlank(text(value, "token"))
							&& ChartSearchAiUtils.isBlank(atc))
					|| (!ChartSearchAiUtils.isBlank(atc) && !validAtcCode(atc))
					|| !validInteractionSeverity(value)) {
				return true;
			}
		}
		return false;
	}

	private static boolean validInteractionSeverity(JsonNode interaction) {
		JsonNode severity = interaction == null ? null : interaction.get("severity");
		if (severity == null || severity.isNull()) {
			return true;
		}
		if (!severity.isTextual() || ChartSearchAiUtils.isBlank(severity.asText())) {
			return false;
		}
		String value = severity.asText().trim();
		return "unknown".equalsIgnoreCase(value) || "minor".equalsIgnoreCase(value)
				|| "moderate".equalsIgnoreCase(value) || "major".equalsIgnoreCase(value);
	}

	private static boolean invalidContraindications(JsonNode values) {
		for (JsonNode value : values) {
			String type = text(value, "type");
			if (value == null || !value.isObject()
					|| !("allergy".equalsIgnoreCase(type) || "condition".equalsIgnoreCase(type))
					|| ChartSearchAiUtils.isBlank(text(value, "token"))) {
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

		private final List<DrugReference> entries;
		private final String packageId;
		private final String version;
		private final String source;
		private final String reviewState;
		private final List<String> issues;

		private ParsedDataset(List<DrugReference> entries, String packageId, String version,
				String source, String reviewState, List<String> issues) {
			this.entries = entries;
			this.packageId = packageId;
			this.version = version;
			this.source = source;
			this.reviewState = reviewState;
			this.issues = issues;
		}

		private static ParsedDataset invalid() {
			return new ParsedDataset(Collections.<DrugReference> emptyList(), null, null, null,
					DrugReferencePackage.REVIEW_PROPOSED,
					Collections.singletonList("source_data_invalid"));
		}

		private DrugReferencePackage toPackage(String origin) {
			Map<String, Object> provenance = new LinkedHashMap<String, Object>();
			provenance.put("origin", origin);
			if (source != null) {
				provenance.put("source", source);
			}
			return new DrugReferencePackage(
					packageId == null ? "unidentified-json-package" : packageId,
					"json", version, provenance, reviewState, issues);
		}
	}
}
