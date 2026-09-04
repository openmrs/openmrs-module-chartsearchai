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

import java.io.BufferedReader;
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
 * {@code M01AE01}); that linkage is curated data — the {@link CrossReactivityGroup}s
 * loaded alongside this source (ADR Decision 27), not ATC itself. See ADR Decision 24.
 *
 * <p>Unlike {@link JsonDrugReferenceSource} there is no bundled classpath fallback:
 * the operator points {@link ChartSearchAiConstants#GP_DRUG_REFERENCE_DATA_FILE_PATH}
 * at the ATC dataset they obtained. When it is absent or unreadable the source loads
 * empty (fail-safe), so it never breaks the answer path.
 *
 * <p>The resolution is still the shared one — {@link ReferenceDataFiles#loadOperatorFile}, the
 * no-fallback half of that contract — and this source reports through
 * {@link #lastLoadFindings()} like the other two. Both were missing until issue #266, and together they
 * meant the format had no validity channel at all: no collector for a rule to be raised into, and no
 * accessor for one to reach {@link DrugReferenceService#getLoadStatus()} through. So
 * {@code findings} on {@code GET /chartsearchai/drugreferencestatus} was empty for this format whatever
 * its file did — on the ONE format whose dataset can only ever be the operator's own.
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

	private volatile String lastLoadOrigin;

	private volatile List<DrugReferenceValidity.Finding> lastLoadFindings = Collections.emptyList();

	@Override
	public List<DrugReference> load() {
		ReferenceDataFiles.Loaded<DrugReference> loaded = ReferenceDataFiles.loadOperatorFile(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH,
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_DATA_FILE_PATH,
				"ATC drug-reference entries", AtcDrugReferenceSource::parse);
		lastLoadOrigin = loaded.getOrigin();
		lastLoadFindings = loaded.getValidity().getFindings();
		return loaded.getItems();
	}

	@Override
	public String lastLoadOrigin() {
		return lastLoadOrigin;
	}

	@Override
	public List<DrugReferenceValidity.Finding> lastLoadFindings() {
		return lastLoadFindings;
	}

	/**
	 * The form for a caller that wants only the entries — package-private and static so tests exercise
	 * the real parser against a real ATC sample. Delegates; see {@link #parse(InputStream,
	 * DrugReferenceValidity)} for what parsing this dataset means.
	 *
	 * <p>What the parser found wrong with the DOCUMENT still reaches the log, so a mis-shaped fixture is
	 * loud wherever it is read from. It cannot reach {@link DrugReferenceService#getLoadStatus()}, which
	 * describes a LOAD and not a parse; {@link #load()} takes the two-argument form for that — the same
	 * split, and for the same reason, as the other two sources.
	 */
	static List<DrugReference> parse(InputStream in) throws IOException {
		DrugReferenceValidity validity = new DrugReferenceValidity();
		List<DrugReference> parsed = parse(in, validity);
		validity.logTo(log);
		return parsed;
	}

	/**
	 * Parse an ATC dataset stream into classification entries, reporting what only this parser can see
	 * about the document to {@code validity} — the {@link ReferenceDataFiles.DatasetParser} form, and the
	 * one the load takes, which is how a finding reaches both the log and
	 * {@link DrugReferenceLoad#getFindings()}. Each non-blank, non-{@code #}-comment line is
	 * {@code <atcCode><whitespace><name>}; all levels are read so a substance's class can be resolved
	 * from its parent-group names.
	 *
	 * <p>The one document rule a line-based dataset can raise is
	 * {@link DrugReferenceValidity#NO_LINE_YIELDED_AN_ENTRY} (issue #266): this parser skips a line it
	 * cannot read rather than refusing it, so a document of another format is read to the end and emits
	 * nothing. Counted over CONTENT lines — blank lines and comments excluded — because a document made
	 * only of those carried nothing to discard, and an empty document is a different state from a
	 * discarded one. There is no table rule here: an ATC document declares no tables, which is why issue
	 * #242's rule could not be reused and #264 named this as a residual.
	 */
	static List<DrugReference> parse(InputStream in, DrugReferenceValidity validity) throws IOException {
		// code -> name, all levels, preserving file order so substances emit in dataset order.
		Map<String, String> names = new LinkedHashMap<String, String>();
		int contentLines = 0;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}
				contentLines++;
				String[] parts = trimmed.split("\\s+", 2);
				if (parts.length < 2) {
					continue;
				}
				// Normalise the code by the one shared ATC rule: export formats (e.g. RxNorm/ATC
				// crosswalks) are not all upper case, and the rest of the pipeline compares
				// ATC codes in normalized form.
				String code = DrugReference.normalizeAtcToken(parts[0]);
				String name = parts[1].trim();
				if (code != null && !name.isEmpty()) {
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
		if (out.isEmpty()) {
			validity.noLineYieldedAnEntry(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_ATC, contentLines);
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
