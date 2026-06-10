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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DrugReferenceSource} that consumes a WHO ATC classification export (the
 * WHOCC ATC/DDD index, or an RxNorm/ATC crosswalk) in a simple
 * {@code <atcCode><whitespace><name>} per-line format covering all levels.
 *
 * <p>It produces one classification entry per level-5 substance (a 7-character ATC
 * code), carrying its name, the code, and a {@code drugClass} derived from the
 * nearest parent group <em>present in the same dataset</em> (level 4, else 3, else 2).
 *
 * <p><b>Classification only.</b> ATC publishes a hierarchy, not clinical rules, so
 * these entries carry NO dosing, interaction, or contraindication data. An ATC-only
 * dataset therefore feeds the {@link DrugSafetyValidator} no per-entry rules to fire on;
 * the validator instead reasons at the <em>class</em> level (a recorded allergy or active
 * order that shares a drug's ATC level-4 subgroup), which is what turns this rule-less
 * classification into safety warnings. ATC's tree does not capture cross-<em>branch</em>
 * pharmacological cross-reactivity (e.g. aspirin {@code N02BA01} vs ibuprofen
 * {@code M01AE01}), so that linkage needs curated data, not ATC. See ADR Decision 24.
 *
 * <p>Unlike {@link JsonDrugReferenceSource} there is no bundled classpath fallback:
 * the operator points {@link ChartSearchAiConstants#GP_DRUG_REFERENCE_DATA_FILE_PATH}
 * at the ATC dataset they obtained. When it is absent or unreadable the source loads
 * empty (fail-safe), so it never breaks the answer path.
 */
public class AtcDrugReferenceSource implements DrugReferenceSource {

	private static final Logger log = LoggerFactory.getLogger(AtcDrugReferenceSource.class);

	/** A level-5 ATC substance code is 7 characters (e.g. {@code M01AE01}). */
	static final int SUBSTANCE_CODE_LENGTH = 7;

	/** Parent-group code lengths to try, longest first: level 4 (5), level 3 (4), level 2 (3). */
	private static final int[] PARENT_LENGTHS = { 5, 4, 3 };

	/** A level-5 ATC code: one letter, two digits, two letters, two digits (e.g. {@code M01AE01}).
	 *  Guards against a non-ATC or malformed file turning any 7-character first token into a drug. */
	private static final java.util.regex.Pattern ATC_LEVEL5 = java.util.regex.Pattern.compile("[A-Z]\\d{2}[A-Z]{2}\\d{2}");

	@Override
	public List<DrugReference> load() {
		// Fail-safe read returns "" when unset/blank or no context is available -> run empty.
		String configuredPath = ChartSearchAiUtils.getStringGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, "");
		if (configuredPath.isEmpty()) {
			log.info("ATC drug-reference source selected but no dataset path is configured; running empty");
			return Collections.emptyList();
		}
		try {
			String resolved = ChartSearchAiUtils.resolveModelPath(configuredPath,
					ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH);
			try (InputStream in = new FileInputStream(new File(resolved))) {
				List<DrugReference> loaded = parse(in);
				log.info("Loaded {} ATC drug-reference entries from {}", loaded.size(), resolved);
				return loaded;
			}
		}
		catch (IllegalStateException e) {
			log.info("ATC dataset '{}' not available ({}); running empty", configuredPath, e.getMessage());
			return Collections.emptyList();
		}
		catch (IOException e) {
			log.warn("Failed to read ATC dataset '{}'; running empty", configuredPath, e);
			return Collections.emptyList();
		}
	}

	/**
	 * Parse an ATC dataset stream into classification entries. Each non-blank,
	 * non-{@code #}-comment line is {@code <atcCode><whitespace><name>}; all levels are
	 * read so a substance's class can be resolved from its parent-group names.
	 * Package-private and static so tests exercise the real parser against a real ATC sample.
	 */
	static List<DrugReference> parse(InputStream in) throws IOException {
		// code -> name, all levels, preserving file order so substances emit in dataset order.
		Map<String, String> names = new LinkedHashMap<String, String>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}
				String[] parts = trimmed.split("\\s+", 2);
				if (parts.length < 2) {
					continue;
				}
				// Normalise the code to upper case: ATC export formats (e.g. RxNorm/ATC crosswalks)
				// are not all upper case, and the rest of the pipeline compares ATC codes upper-cased.
				String code = parts[0].trim().toUpperCase(Locale.ROOT);
				String name = parts[1].trim();
				if (!code.isEmpty() && !name.isEmpty()) {
					names.put(code, name);
				}
			}
		}
		List<DrugReference> out = new ArrayList<DrugReference>();
		for (Map.Entry<String, String> entry : names.entrySet()) {
			if (isLevel5Substance(entry.getKey())) {
				out.add(toEntry(entry.getKey(), entry.getValue(), names));
			}
		}
		return out;
	}

	/** @return true when {@code code} is a valid level-5 ATC substance code. A non-ATC or malformed
	 *          file's 7-character first tokens are rejected here rather than emitted as bogus drugs. */
	private static boolean isLevel5Substance(String code) {
		return code.length() == SUBSTANCE_CODE_LENGTH && ATC_LEVEL5.matcher(code).matches();
	}

	private static DrugReference toEntry(String code, String name, Map<String, String> names) {
		DrugReference ref = new DrugReference();
		ref.setId(code);
		ref.setName(name);
		ref.setAliases(Collections.singletonList(name.toLowerCase(Locale.ROOT)));
		ref.setAtcCodes(Collections.singletonList(code));
		ref.setDrugClass(nearestGroupName(code, names));
		return ref;
	}

	/** @return the name of the nearest parent group present in the dataset (level 4, else 3, else 2), or null. */
	private static String nearestGroupName(String code, Map<String, String> names) {
		for (int len : PARENT_LENGTHS) {
			if (code.length() > len) {
				String parent = names.get(code.substring(0, len));
				if (parent != null) {
					return parent;
				}
			}
		}
		return null;
	}
}
