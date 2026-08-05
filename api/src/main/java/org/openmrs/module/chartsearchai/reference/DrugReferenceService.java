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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Loads and indexes the drug-reference dataset. The data <em>layer</em> is
 * pluggable: the active {@link DrugReferenceSource} is selected by
 * {@link ChartSearchAiConstants#GP_DRUG_REFERENCE_SOURCE_FORMAT}
 * ({@code json} = the curated {@link JsonDrugReferenceSource}, {@code atc} = the
 * classification-only {@link AtcDrugReferenceSource}, {@code ddinter} = the DDInter-backed
 * {@link DdiDrugReferenceSource}); each resolves its file from
 * {@link ChartSearchAiConstants#GP_DRUG_REFERENCE_DATA_FILE_PATH}, with a bundled
 * classpath default. This lets the
 * feature consume different research formats without treating format selection as clinical
 * approval. See the safety-boundary correction on ADR Decision 24.
 *
 * <p>Loading is lazy and cached: the first lookup triggers a load, and the result
 * is held for the life of the bean. The same applies to the curated
 * {@link CrossReactivityGroup} dataset, which loads independently of the source
 * format. Editing either dataset — or switching the source format — therefore
 * requires a module restart.
 *
 * <p>Because it is lazy, "which dataset is in force?" cannot be answered from the log: the most
 * recent {@code "Loaded N …"} line may pre-date the global properties as they read now. Ask
 * {@link #getLoadStatus()} instead, which reports the load that populated the cache (performing it
 * if it has not happened yet) — see {@link DrugReferenceLoad} and the module's
 * {@code GET /chartsearchai/drugreferencestatus} endpoint.
 */
@Service("chartSearchAi.drugReferenceService")
public class DrugReferenceService {

	private static final Logger log = LoggerFactory.getLogger(DrugReferenceService.class);

	private volatile List<DrugReference> entries;

	private volatile DrugReferenceLoad load;

	private volatile List<CrossReactivityGroup> crossReactivityGroups;

	private DrugReferenceSource source;

	/**
	 * @return all loaded reference entries (never null; empty when nothing could be loaded).
	 */
	public List<DrugReference> getAll() {
		ensureLoaded();
		return entries;
	}

	/**
	 * @return the outcome of the dataset load that is IN FORCE — see {@link DrugReferenceLoad}.
	 *         Triggers the (lazy) load when the feature is enabled and nothing has loaded yet;
	 *         reports {@link DrugReferenceLoad#notLoaded()} without loading anything when the
	 *         feature is switched off, so polling the status cannot manufacture the inert warning on
	 *         an install that does not use the feature.
	 *
	 *         <p>This is the answer to "which drug-reference dataset is this module actually using?"
	 *         that a log line cannot give (issue #149). The load is lazy, so the most recent
	 *         {@code "Loaded N …"} line may pre-date the global properties as they read now; this
	 *         either reports the load that populated the cache, or performs it. A load that HAS
	 *         happened is reported whatever the enabled switch says now — the entries in memory are
	 *         the ones the safety layer would use, and the switch can be flipped after the fact.
	 */
	public DrugReferenceLoad getLoadStatus() {
		DrugReferenceLoad current = load;
		if (current != null) {
			return current;
		}
		if (!ChartSearchAiUtils.isDrugReferenceEnabled()) {
			return DrugReferenceLoad.notLoaded();
		}
		ensureLoaded();
		DrugReferenceLoad afterLoad = load;
		return afterLoad == null ? DrugReferenceLoad.notLoaded() : afterLoad;
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
	 * Resolve a clinician-entered drug NAME — an allergen as recorded on the chart — to a reference
	 * entry. Returns the first matching entry in dataset order, or null.
	 *
	 * <p>Through {@link DrugReference#matchesDrugName}, not {@link DrugReference#matchesText}: the
	 * input is one localized, inflected display name rather than prose, and resolving it with the
	 * prose rule is issue #147 — the same string resolved as an active order's name and not as an
	 * allergen, so a patient's own recorded allergy to a drug they were taking produced no
	 * contraindication while the interaction it caused was reported. The matcher is named at this call
	 * site deliberately: it was inherited by default before, which is how the two halves of one safety
	 * check came to have different tolerance.
	 *
	 * <p>Coverage bound, unchanged by that fix and measured over the full KB: this takes the EARLIEST
	 * matching entry, so a multi-drug name resolves to whichever constituent the dataset lists first,
	 * and an entry whose alias list claims another drug's name can capture it — see
	 * {@code DrugSafetyValidator.addAllergyContraindications}, which reports the measurement and is
	 * where the consequence for a chip's wording is recorded.
	 */
	public DrugReference lookupByToken(String drugToken) {
		if (drugToken == null || drugToken.trim().isEmpty()) {
			return null;
		}
		for (DrugReference ref : getAll()) {
			if (ref.matchesDrugName(drugToken)) {
				return ref;
			}
		}
		return null;
	}

	/**
	 * Name-driven matching for a clinician-entered drug NAME: EVERY entry that name resolves to, in
	 * dataset order. The multi-entry counterpart of {@link #lookupByToken} — a combination product's
	 * name resolves each of its constituents, and a drug the dataset files as several route variants
	 * resolves all of them — and the order-name counterpart of {@link #findByQuery}, which stays bound
	 * to the prose matcher because a question and an answer are prose.
	 */
	public List<DrugReference> findByDrugName(String drugName) {
		if (drugName == null || drugName.trim().isEmpty()) {
			return Collections.emptyList();
		}
		List<DrugReference> out = new ArrayList<DrugReference>();
		for (DrugReference ref : getAll()) {
			if (ref.matchesDrugName(drugName)) {
				out.add(ref);
			}
		}
		return out;
	}

	/**
	 * The reference entries the patient's active orders resolve to — the subjects
	 * {@code DrugSafetyValidator.addActiveOrderPairInteractions} screens against each other, the
	 * subjects {@code addActiveOrderContraindications} checks against the patient's own allergy and
	 * condition records, and the source of the names {@link #withReferenceNames} attaches. The union of
	 * the documented order-driven matcher ({@link #findByActiveOrders}, which keys on ATC codes) and a
	 * name resolution of each active order's own display name ({@link #findByDrugName}). One
	 * definition, so those consumers cannot come to disagree about which of the patient's
	 * prescriptions the reference data covers.
	 *
	 * <p>Both keys are needed because {@link PatientClinicalContext#hasActiveDrug} — the join that
	 * decides whether a rule concerns this patient — matches on name OR ATC, so a subject set resolved
	 * on only one of them cannot be the subject of every chip that join can raise. Neither key can be
	 * assumed present: measured on the 3.7.1 standalone's demo dictionary (2026-08-04), ATC coverage is
	 * sparse but real — 85 of 616 Drug-class concepts carry a map from an ATC-named source
	 * ({@code Torasemide} → {@code C03CA04}, {@code Heparin sodium} → {@code B01AB01}) and 158 carry a
	 * {@code concept_reference_map} of any kind, so {@link PatientClinicalContextBuilder} yields ATC
	 * codes for some orders and none for others. Every order on every probe patient there
	 * (Simvastatin, Spironolactone, Tiotropium, Nitroglycerin, Budesonide, Dexamethasone) fell in the
	 * unmapped majority, so an ATC-only subject set was empty for every case measured — which makes
	 * this union a robustness property rather than a workaround for one dictionary: on a
	 * fully-ATC-mapped dictionary the order-driven matcher carries the subject set, and where mapping
	 * is absent the name resolution does. The ATC path is dormant on that instance, not dead.
	 *
	 * <p>The name leg is {@link #findByDrugName} rather than {@link #findByQuery} since issue #147: an
	 * order's display name is a localized drug name, not prose, so resolving it with the prose rule
	 * left {@code Aspirine Co 81mg} and {@code Clarithromycine Co 500mg} matching no entry at all —
	 * measured, 117 (order name, entry) pairs gained and 0 lost over the 3.7.1 dictionary's 2533 names.
	 *
	 * <p>Identity de-duplication is sound because both matchers resolve against this bean's shared
	 * {@link #getAll()} cache (the same reason the drugs-in-play set can dedup by identity).
	 */
	public List<DrugReference> findForActiveOrders(PatientClinicalContext context) {
		if (context == null) {
			return Collections.emptyList();
		}
		Set<DrugReference> entries = new LinkedHashSet<DrugReference>(findByActiveOrders(context));
		for (String name : context.getActiveDrugNames()) {
			entries.addAll(findByDrugName(name));
		}
		return new ArrayList<DrugReference>(entries);
	}

	/**
	 * @return {@code context} carrying the reference data's own names for the drugs its active orders
	 *         name ({@link PatientClinicalContext#getActiveDrugReferenceNames()}), resolved through
	 *         {@link #findForActiveOrders}. The same context back when there is nothing to add.
	 *
	 *         <p>This is issue #136's fix, and it is applied ONCE per pass at each of the two pure
	 *         entry points that own a context ({@code DrugSafetyValidator.validate} and
	 *         {@code DrugReferenceInjector.injectRecords}) rather than being threaded through their
	 *         call trees, so that {@link PatientClinicalContext#hasActiveDrug} stays the single join
	 *         both of them reach with an unchanged signature — no call site can accidentally ask the
	 *         narrower question, which is what would let the chips and the promoted prompt text
	 *         disagree about which orders a rule matches.
	 *
	 *         <p>A drug ordered under a name the dataset carries as an alias rather than as the rule's
	 *         match token had no interaction coverage at all: every DDInter rule about aspirin carries
	 *         the token {@code aspirin} (the partner row's {@code rxnorm_name}) while that row's own
	 *         name is {@code Acetylsalicylic acid}, a real drug-concept name in the 3.7.1 demo
	 *         dictionary that does not contain the string {@code aspirin}. Resolving the ORDER to its
	 *         entry and carrying that entry's names is what closes it, and it costs one dataset sweep
	 *         per pass rather than one per rule.
	 */
	public PatientClinicalContext withReferenceNames(PatientClinicalContext context) {
		return context == null ? null : withReferenceNames(context, findForActiveOrders(context));
	}

	/**
	 * @return as {@link #withReferenceNames(PatientClinicalContext)}, for a caller that has already
	 *         resolved {@code orderEntries} and needs them itself — which is
	 *         {@code DrugSafetyValidator.validate}, whose chip grouping and two order-driven arms take
	 *         the same list. Passing it rather than resolving twice is not only the cheaper of the two:
	 *         it makes the names this attaches and the subjects those arms screen ONE resolution by
	 *         construction, so no later change to {@link #findForActiveOrders} can make the context
	 *         describe a different set of orders than the arms are reading.
	 */
	PatientClinicalContext withReferenceNames(PatientClinicalContext context,
			List<DrugReference> orderEntries) {
		if (context == null) {
			return null;
		}
		Set<String> names = new LinkedHashSet<String>();
		for (DrugReference ref : orderEntries) {
			names.addAll(ref.getAliases());
		}
		int mappedOrderCount = 0;
		for (PatientClinicalContext.ActiveDrugOrder order : context.getActiveDrugOrders()) {
			if (resolves(order, orderEntries)) {
				mappedOrderCount++;
			}
		}
		return context.withActiveDrugReferenceNames(names, mappedOrderCount);
	}

	private static boolean resolves(PatientClinicalContext.ActiveDrugOrder order,
			List<DrugReference> candidates) {
		for (DrugReference ref : candidates) {
			for (String code : ref.normalizedAtcCodes()) {
				if (order.getAtcCodes().contains(code)) {
					return true;
				}
			}
			for (String name : order.getNames()) {
				if (ref.matchesDrugName(name)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * @return the curated cross-reactivity groups, loaded lazily from
	 *         {@code cross-reactivity-groups.json} (operator path, else the bundled default) and
	 *         cached for the bean's lifetime. Independent of the entry source, so the rule-less
	 *         {@code atc} format gains cross-branch family reasoning from the same file. Never null.
	 */
	public List<CrossReactivityGroup> getCrossReactivityGroups() {
		if (crossReactivityGroups == null) {
			synchronized (this) {
				if (crossReactivityGroups == null) {
					crossReactivityGroups = Collections
							.unmodifiableList(new CrossReactivityGroupsLoader().load());
				}
			}
		}
		return crossReactivityGroups;
	}

	private void ensureLoaded() {
		if (entries != null) {
			return;
		}
		synchronized (this) {
			if (entries != null) {
				return;
			}
			String configuredFormat = ChartSearchAiUtils.getStringGlobalProperty(
					ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT,
					ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT);
			String effectiveFormat = effectiveFormat(configuredFormat);
			String configuredPath = ChartSearchAiUtils.getStringGlobalProperty(
					ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, "");
			// One instance, so the origin read below belongs to the load performed here.
			DrugReferenceSource active = source != null ? source : sourceFor(effectiveFormat);
			List<DrugReference> loaded = active.load();
			DrugReferenceLoad outcome = new DrugReferenceLoad(effectiveFormat, configuredFormat,
					configuredPath, active.lastLoadOrigin(), loaded.size());
			// A configured source that resolved to nothing is reported LOUDLY, naming both global
			// properties: this used to print at INFO exactly like a successful load, so the whole
			// drug-safety feature could be off with nothing at default log levels to say so
			// (issue #149). The state that stays silent is the feature being switched OFF, which
			// never reaches this method — not "no dataset path is set", which is one of the ways to
			// arrive here with nothing loaded (sourceFormat=atc has no bundled fallback) and is
			// loud. See DrugReferenceLoad.
			if (outcome.isInert()) {
				log.warn("Loaded 0 drug-reference entries — drug-safety checking is INERT: no "
						+ "interaction, allergy or contraindication warning can be raised, and every "
						+ "safety question will answer as though there were nothing to find. "
						+ "{}={} (parser in use: {}), {}={}, read from {}. The usual cause is a "
						+ "format/path mismatch: each source format parses only its own shape and "
						+ "returns nothing — without failing — for another's.",
						ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT, configuredFormat,
						effectiveFormat, ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH,
						outcome.getConfiguredDataFilePath(), outcome.getOrigin());
			}
			// `load` before `entries`: the double-checked fast path above keys on `entries`, so a
			// reader that sees it populated must already be able to see the outcome describing it.
			load = outcome;
			entries = Collections.unmodifiableList(loaded);
		}
	}

	/**
	 * @return the source format that will actually be used for {@code configuredFormat}: {@code atc}
	 *         and {@code ddinter} select their own adapters, and any other value (including the
	 *         unset/no-context case, and a typo) falls back to the curated {@code json} default.
	 *         Reported in {@link DrugReferenceLoad#getSourceFormat()} so that fallback is visible
	 *         rather than silent — a mistyped format is one of the ways a deployment ends up parsing
	 *         a dataset with the wrong parser and loading nothing.
	 */
	private static String effectiveFormat(String configuredFormat) {
		if (ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_ATC.equalsIgnoreCase(configuredFormat)) {
			return ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_ATC;
		}
		if (ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER.equalsIgnoreCase(configuredFormat)) {
			return ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER;
		}
		return ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT;
	}

	/**
	 * @return the adapter for an {@link #effectiveFormat(String)}: {@code atc} →
	 *         {@link AtcDrugReferenceSource}, {@code ddinter} → {@link DdiDrugReferenceSource}, else
	 *         the curated {@link JsonDrugReferenceSource}.
	 */
	private static DrugReferenceSource sourceFor(String effectiveFormat) {
		if (ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_ATC.equals(effectiveFormat)) {
			return new AtcDrugReferenceSource();
		}
		if (ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER.equals(effectiveFormat)) {
			return new DdiDrugReferenceSource();
		}
		return new JsonDrugReferenceSource();
	}

	/**
	 * Test seam: inject a known source, bypassing the format GP. {@link #getLoadStatus()} still
	 * reports the format the GP selects, which then describes the adapter that WOULD have been used
	 * rather than the injected one — production never injects a source. The origin reads {@code none}
	 * for the same reason: an injected source tracks none, so it is the one case where {@code none}
	 * accompanies a non-zero entry count instead of meaning nothing could be read.
	 */
	void setSource(DrugReferenceSource source) {
		this.source = source;
	}

	/**
	 * Test seam: inject a known entry set, bypassing ALL dataset loading — the curated
	 * cross-reactivity groups are pinned empty too, so the resulting service is fully hermetic
	 * (an ATC-only dataset really is classification-only, which is what the ADR Decision 24
	 * boundary tests assert). Tests that want groups set them via
	 * {@link #setCrossReactivityGroups} afterwards.
	 *
	 * <p>No load happens, so {@link #getLoadStatus()} keeps reporting
	 * {@link DrugReferenceLoad#notLoaded()} for a service seeded this way — the retained outcome
	 * describes a load, and there was none. It is the one path on which the status and the entries in
	 * use are not two views of the same event; production never seeds entries.
	 */
	void setEntries(List<DrugReference> entries) {
		this.entries = entries == null ? Collections.<DrugReference> emptyList()
				: Collections.unmodifiableList(new ArrayList<DrugReference>(entries));
		this.crossReactivityGroups = Collections.emptyList();
	}

	/** Test seam: inject known cross-reactivity groups, bypassing the groups-file load. */
	void setCrossReactivityGroups(List<CrossReactivityGroup> groups) {
		this.crossReactivityGroups = groups == null ? Collections.<CrossReactivityGroup> emptyList()
				: Collections.unmodifiableList(new ArrayList<CrossReactivityGroup>(groups));
	}
}
