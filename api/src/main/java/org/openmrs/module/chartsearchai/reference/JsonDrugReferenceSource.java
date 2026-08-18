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
 * <p>This is the curated/hand-authored source, selected by
 * {@code chartsearchai.drugReference.sourceFormat=json}. It was the DEFAULT until ADR Decision 36 moved
 * that to {@link DdiDrugReferenceSource}, and what it is still the only source of is DOSING: its four
 * seeded entries carry the age bands the dose-excess check needs, which neither DDInter nor a WHO ATC
 * export publishes. It also remains the parser a mistyped {@code sourceFormat} falls through to, so a
 * document of another format reaching it is a live case rather than a hypothetical — see
 * {@code DrugReferenceService.effectiveFormat}. For authoritative datasets see
 * {@link AtcDrugReferenceSource} and {@link DdiDrugReferenceSource}.
 */
public class JsonDrugReferenceSource implements DrugReferenceSource {

	private static final Logger log = LoggerFactory.getLogger(JsonDrugReferenceSource.class);

	static final String CLASSPATH_DEFAULT = "/chartsearchai/drug-reference.json";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private volatile String lastLoadOrigin;

	private volatile List<DrugReferenceValidity.Finding> lastLoadFindings = Collections.emptyList();

	@Override
	public List<DrugReference> load() {
		ReferenceDataFiles.Loaded<DrugReference> loaded = ReferenceDataFiles.loadWithClasspathFallback(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH,
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_DATA_FILE_PATH, CLASSPATH_DEFAULT,
				"drug-reference entries", JsonDrugReferenceSource::parse);
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
	 * The form for a caller that wants only the entries — package-private and static so tests can
	 * exercise the real parser against the real dataset. Delegates; see {@link #parse(InputStream,
	 * DrugReferenceValidity)} for what parsing this dataset means, and
	 * {@link DdiDrugReferenceSource#parse(InputStream)} for why what the parser found wrong with the
	 * DOCUMENT still reaches the log from here.
	 */
	static List<DrugReference> parse(InputStream in) throws IOException {
		DrugReferenceValidity validity = new DrugReferenceValidity();
		List<DrugReference> parsed = parse(in, validity);
		validity.logTo(log);
		return parsed;
	}

	/**
	 * Parse a dataset stream into reference entries, reporting what only this parser can see about the
	 * document to {@code validity} — the {@link ReferenceDataFiles.DatasetParser} form, and the one the
	 * load takes. Entries with a blank {@code id} or {@code name} are dropped (with a warning): a
	 * name-less entry would render {@code "Drug reference — null"} into the citable record and a
	 * {@code null} drug into the safety warnings, and an id-less one has no stable citation
	 * {@code resourceUuid}.
	 *
	 * <p>The curated schema is the DEFAULT format, so the document this parser is likeliest to be handed
	 * by mistake is one of another format — a DDInter export named by {@code dataFilePath} while
	 * {@code sourceFormat} was left alone. That declares no {@code entries}, and used to load as zero in
	 * the same silence issue #242 records on the DDInter side. Nothing is counted as discarded: a
	 * document with no {@code entries} carries nothing this parser can read, which is what tells an
	 * operator it is a file of another format rather than a mis-shaped one of this.
	 */
	static List<DrugReference> parse(InputStream in, DrugReferenceValidity validity) throws IOException {
		Dataset dataset = MAPPER.readValue(in, Dataset.class);
		if (dataset == null || dataset.entries == null) {
			// The format's NAME, not "whatever the default is" — those are equal today and mean
			// different things, and only one of them stays right if the default moves.
			validity.datasetMissingARequiredTable(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON,
					Collections.singletonList("entries"), 0);
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
