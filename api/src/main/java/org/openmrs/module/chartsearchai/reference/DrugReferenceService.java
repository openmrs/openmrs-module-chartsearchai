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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads and indexes the drug-reference dataset. The dataset is resolved from the
 * path in {@link ChartSearchAiConstants#GP_DRUG_REFERENCE_DATA_FILE_PATH} (relative
 * to the OpenMRS application data directory); if that file is absent or
 * unreadable — including when no OpenMRS context is available — it falls back to
 * the dataset bundled on the classpath at {@code /chartsearchai/drug-reference.json}
 * so the module ships with working defaults.
 *
 * <p>Loading is lazy and cached: the first lookup triggers a parse, and the result
 * is held for the life of the bean. Editing the on-disk JSON therefore requires a
 * module restart (documented as a follow-up; a reindex task can come later).
 */
@Service("chartSearchAi.drugReferenceService")
public class DrugReferenceService {

	private static final Logger log = LoggerFactory.getLogger(DrugReferenceService.class);

	static final String CLASSPATH_DEFAULT = "/chartsearchai/drug-reference.json";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private volatile List<DrugReference> entries;

	/**
	 * @return all loaded reference entries (never null; empty when nothing could be loaded).
	 */
	public List<DrugReference> getAll() {
		ensureLoaded();
		return entries;
	}

	/**
	 * Question-driven matching: entries whose aliases hit the user's query text.
	 * Cheap and deterministic — no embedding required.
	 *
	 * @param question the clinician's query
	 * @return matching entries, in dataset order, deduplicated
	 */
	public List<DrugReference> findByQuery(String question) {
		if (question == null || question.trim().isEmpty()) {
			return Collections.emptyList();
		}
		String lower = question.toLowerCase(Locale.ROOT);
		List<DrugReference> out = new ArrayList<DrugReference>();
		for (DrugReference ref : getAll()) {
			if (ref.matchesText(lower)) {
				out.add(ref);
			}
		}
		return out;
	}

	/**
	 * Patient-driven matching: entries whose ATC codes match an active drug order
	 * on the patient's chart, regardless of whether the question mentions the drug.
	 *
	 * @param context the patient's clinical context (active-order ATC codes)
	 * @return matching entries, in dataset order, deduplicated
	 */
	public List<DrugReference> findByActiveOrders(PatientClinicalContext context) {
		if (context == null || context.getActiveDrugAtcCodes().isEmpty()) {
			return Collections.emptyList();
		}
		Set<String> atc = context.getActiveDrugAtcCodes();
		List<DrugReference> out = new ArrayList<DrugReference>();
		for (DrugReference ref : getAll()) {
			for (String code : ref.getAtcCodes()) {
				if (code != null && atc.contains(code.trim().toUpperCase(Locale.ROOT))) {
					out.add(ref);
					break;
				}
			}
		}
		return out;
	}

	/**
	 * Resolve a free-text drug token (e.g. a name parsed out of the LLM answer) to
	 * a reference entry via alias match. Returns the first matching entry, or null.
	 */
	public DrugReference lookupByToken(String drugToken) {
		if (drugToken == null || drugToken.trim().isEmpty()) {
			return null;
		}
		String lower = drugToken.toLowerCase(Locale.ROOT);
		for (DrugReference ref : getAll()) {
			if (ref.matchesText(lower)) {
				return ref;
			}
		}
		return null;
	}

	/** @return the union of every alias across all entries, lowercased (used by the answer parser). */
	public Set<String> allAliases() {
		Set<String> out = new LinkedHashSet<String>();
		for (DrugReference ref : getAll()) {
			for (String alias : ref.getAliases()) {
				if (alias != null && !alias.trim().isEmpty()) {
					out.add(alias.trim().toLowerCase(Locale.ROOT));
				}
			}
		}
		return out;
	}

	private void ensureLoaded() {
		if (entries != null) {
			return;
		}
		synchronized (this) {
			if (entries != null) {
				return;
			}
			entries = Collections.unmodifiableList(load());
		}
	}

	private List<DrugReference> load() {
		// Prefer the operator-configured file in the application data directory.
		String configuredPath = null;
		try {
			configuredPath = org.openmrs.api.context.Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH,
							ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_DATA_FILE_PATH);
		}
		catch (RuntimeException e) {
			// No admin service (e.g. context not started, or a unit test) -> classpath default below.
			log.debug("No OpenMRS context for drug-reference path; using bundled default", e);
		}

		if (configuredPath != null && !configuredPath.trim().isEmpty()) {
			try {
				String resolved = ChartSearchAiUtils.resolveModelPath(configuredPath.trim(),
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

		try (InputStream in = DrugReferenceService.class.getResourceAsStream(CLASSPATH_DEFAULT)) {
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

	/** Test seam: inject a known entry set, bypassing file/classpath loading. */
	void setEntries(List<DrugReference> entries) {
		this.entries = entries == null ? Collections.<DrugReference> emptyList()
				: Collections.unmodifiableList(new ArrayList<DrugReference>(entries));
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class Dataset {

		public List<DrugReference> entries;
	}
}
