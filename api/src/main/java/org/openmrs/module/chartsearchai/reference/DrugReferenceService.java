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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.springframework.stereotype.Service;

/**
 * Loads and indexes the drug-reference dataset. The data <em>layer</em> is
 * pluggable: the active {@link DrugReferenceSource} is selected by
 * {@link ChartSearchAiConstants#GP_DRUG_REFERENCE_SOURCE_FORMAT}
 * ({@code json} = the curated {@link JsonDrugReferenceSource}, {@code atc} = the
 * authoritative {@link AtcDrugReferenceSource}); both resolve their file from
 * {@link ChartSearchAiConstants#GP_DRUG_REFERENCE_DATA_FILE_PATH}. This lets the
 * feature consume authoritative datasets by pointing at them, rather than
 * hand-maintaining a chartsearchai-specific file. See ADR Decision 24.
 *
 * <p>Loading is lazy and cached: the first lookup triggers a load, and the result
 * is held for the life of the bean. Editing the dataset — or switching the source
 * format — therefore requires a module restart.
 */
@Service("chartSearchAi.drugReferenceService")
public class DrugReferenceService {

	private volatile List<DrugReference> entries;

	private DrugReferenceSource source;

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
			for (String code : ref.normalizedAtcCodes()) {
				if (atc.contains(code)) {
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
			entries = Collections.unmodifiableList(source().load());
		}
	}

	/**
	 * @return the active source. The {@code sourceFormat} GP selects the adapter;
	 *         any value other than {@code atc} (including the unset/no-context case)
	 *         defaults to the curated JSON source.
	 */
	private DrugReferenceSource source() {
		if (source != null) {
			return source;
		}
		String format = ChartSearchAiUtils.getStringGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT,
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT);
		if (ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_ATC.equalsIgnoreCase(format)) {
			return new AtcDrugReferenceSource();
		}
		return new JsonDrugReferenceSource();
	}

	/** Test seam: inject a known source, bypassing the format GP. */
	void setSource(DrugReferenceSource source) {
		this.source = source;
	}

	/** Test seam: inject a known entry set, bypassing source loading. */
	void setEntries(List<DrugReference> entries) {
		this.entries = entries == null ? Collections.<DrugReference> emptyList()
				: Collections.unmodifiableList(new ArrayList<DrugReference>(entries));
	}
}
