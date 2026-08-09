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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * authoritative {@link AtcDrugReferenceSource}, {@code ddinter} = the DDInter-backed
 * {@link DdiDrugReferenceSource}); each resolves its file from
 * {@link ChartSearchAiConstants#GP_DRUG_REFERENCE_DATA_FILE_PATH}, with a bundled
 * classpath default. This lets the
 * feature consume authoritative datasets by pointing at them, rather than
 * hand-maintaining a chartsearchai-specific file. See ADR Decision 24.
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
	 * entry, or null when no entry MATCHES it. Matching is the gate and naming is only the ranking
	 * below, so a name no entry is named still resolves — by containment, as it always did.
	 *
	 * <p>Through {@link DrugReference#matchesDrugName}, not {@link DrugReference#matchesText}: the
	 * input is one localized, inflected display name rather than prose, and resolving it with the
	 * prose rule is issue #147 — the same string resolved as an active order's name and not as an
	 * allergen, so a patient's own recorded allergy to a drug they were taking produced no
	 * contraindication while the interaction it caused was reported. The matcher is named at this call
	 * site deliberately: it was inherited by default before, which is how the two halves of one safety
	 * check came to have different tolerance.
	 *
	 * <p><b>Which of several matching entries (issue #176).</b> The one with the strongest claim on the
	 * name — {@link DrugReference#nameMatchStrength}, which is where the ordering of the three kinds of
	 * claim is defined and measured — and the earliest of those on a tie. This used to take the earliest
	 * MATCH outright, and reference names nest, so the row a chart's own string resolved to could be a
	 * different presentation of the substance or a different substance altogether; since issue #187 that
	 * row is what the contraindication chips name, so it was printed. Measured over the shipped 19 MB KB
	 * through this method (2026-08-08; re-measure before relying on the figures): asking for each of the
	 * 2283 entries by its own display name, earliest-match answered with a DIFFERENT entry 206 times, 54
	 * of them a different substance; ranking the claim answers with the entry itself every time.
	 *
	 * <p>The rank is a refinement, not a filter: {@link DrugReference#nameMatchStrength} is gated on the
	 * same {@link DrugReference#matchesDrugName} this used to scan, so the set of entries a name can
	 * resolve to is unchanged and only the choice within it moves. A recorded name that no entry is
	 * NAMED — a localized display name with a strength appended, which is the ordinary shape — still
	 * resolves to the earliest matching entry exactly as before.
	 *
	 * <p>Residual bound, measured the same way: where two entries make the SAME strongest claim the
	 * earliest still wins, so a dataset that files one display name twice (issue #164's shape) resolves
	 * to whichever row it lists first, and a multi-drug name whose constituents it names separately
	 * resolves to the first constituent that claims it.
	 *
	 * <p>That last bound is why this is now the PRIMITIVE rather than the answer: since issues
	 * #193/#195 the allergy arm asks {@link #findImpliedSubstances}, which is built on this and adds the
	 * substances the recorded string denotes BESIDES the one row it resolves to. Kept as its own method,
	 * and unchanged, because "which single row is this string about" is the question every label still
	 * needs answering — including the label on each chip the wider set raises.
	 */
	public DrugReference lookupByToken(String drugToken) {
		if (drugToken == null || drugToken.trim().isEmpty()) {
			return null;
		}
		DrugReference best = null;
		int strongest = DrugReference.NAME_NO_MATCH;
		for (DrugReference ref : getAll()) {
			int strength = ref.nameMatchStrength(drugToken);
			// Strictly greater, so the earliest entry keeps the role on a tie — including the tie with
			// NAME_NO_MATCH, which is how a non-matching entry is skipped.
			if (strength > strongest) {
				best = ref;
				strongest = strength;
				if (strongest == DrugReference.NAME_IS_THE_DISPLAY_NAME) {
					// Nothing outranks it and a later equal claim would lose the tie anyway, so the scan is
					// over. It is an exit, not a cost guarantee: a name reaching only the alias or the
					// containment rank has no such stopping point and now scans every entry where the
					// first-match rule could stop at the first one. That is the shape to time if this ever
					// looks expensive — one full scan per (drug in play, allergy token) pair.
					return best;
				}
			}
		}
		return best;
	}

	/**
	 * Every SUBSTANCE a clinician-entered drug NAME implies, one representative row each, the row
	 * {@link #lookupByToken} resolves the whole name to first (issues #193 and #195).
	 *
	 * <p><b>Why one entry was not enough.</b> Issues #176/#192 fixed <em>which</em> entry a recorded
	 * name resolves to; it still resolved to exactly one, and two shapes of recorded name denote more
	 * than one substance. A COMBINATION name denotes each of its ingredients, so one of them was
	 * compared and the rest were never checked — measured through {@link #lookupByToken} over the
	 * shipped 19 MB KB (2026-08-09; re-measure before relying on the figures),
	 * {@code sulfamethoxazole / trimethoprim} answers {@code Trimethoprim} and nothing is compared
	 * against the sulfa moiety that drives that allergy, and {@code omeprazole / sodium bicarbonate}
	 * answers {@code Sodium bicarbonate}, losing the PPI class entirely. A PRESENTATION name denotes
	 * the moiety it is a presentation of, so where the KB files the presentation as its own substance
	 * AND gives it no ATC code the class comparisons have nothing to compare and the finding goes
	 * silent ({@code Insulin lispro (protamine)}, {@code Insulin human (isophane)},
	 * {@code Iron (polysaccharide)}).
	 *
	 * <p><b>What is added, and the gate on each.</b> Nothing here changes
	 * {@link DrugReference#nameMatchStrength}'s ranking or {@link DrugReference#substanceKey()}: the
	 * widening is in what the recorded STRING is read to name, not in what the reference data calls one
	 * substance. In order:
	 * <ul>
	 *   <li>the substance of {@link #lookupByToken}'s answer — always first, so every caller's existing
	 *       label is unchanged for a name implying one substance;</li>
	 *   <li>the substance of every OTHER entry making the same strongest claim, when that claim is a
	 *       NAME claim ({@link DrugReference#NAME_IS_ANOTHER_NAME} or stronger). 1367 of the shipped
	 *       KB's 5169 distinct published names are claimed equally by two or more entries, 1125 of them
	 *       {@code /}-joined, and 1110 of the 1367 by two or more SUBSTANCES (measured 2026-08-09
	 *       through {@link DrugReference#nameMatchStrength} and
	 *       {@link DrugReference#substanceGroupKey()}). Deliberately not extended to the containment
	 *       rank, which is where issue #192's hazard lives: there a tie is two entries whose names
	 *       merely occur inside the recorded one;</li>
	 *   <li>the substance each {@link DrugReference#combinationConstituents constituent} of a
	 *       combination name resolves to, when the KB is NAMED that constituent. Any rank of NAME claim
	 *       is enough because the recorded string ASSERTS the constituent is an ingredient, so an entry
	 *       named it is that ingredient — including one whose display name diverges from it
	 *       ({@code aspirin} → {@code Acetylsalicylic acid}, whose
	 *       {@link DrugReference#displayLabel()} carries both);</li>
	 *   <li>the substance the {@link DrugReference#parentMoietyName parent moiety} of a presentation
	 *       name resolves to, when an entry is CALLED it
	 *       ({@link DrugReference#NAME_IS_THE_DISPLAY_NAME}). Stricter than the constituent gate, and
	 *       that is the point: a moiety is a derivation rather than a claim, so an entry that merely
	 *       lists the stem among its aliases is a different presentation, and naming it in a chip would
	 *       report an allergy to a drug the chart does not record — issue #176's defect from the other
	 *       side. It is also what leaves apart the sibling pairs that share a stem and no bare row:
	 *       {@code Varicella Zoster Vaccine (Recombinant)} against {@code (live/attenuated)},
	 *       {@code Manganese (chloride)} against {@code (sulfate)}, {@code Typhoid vaccine (live)}
	 *       against {@code (inactivated)}. Audited over the whole shipped KB rather than argued
	 *       (2026-08-09, through this method): 313 published names carry a trailing qualifier and
	 *       exactly 10 gain a substance from this leg — six Moderna COVID-19 presentations reaching
	 *       {@code Moderna covid-19 vaccine}, plus {@code Iron (polysaccharide)} → {@code Iron},
	 *       {@code Multivitamin (prenatal)} → {@code Multivitamin},
	 *       {@code Insulin human (isophane)} → {@code Insulin human} and
	 *       {@code Insulin lispro (protamine)} → {@code Insulin lispro}. Re-run that audit rather than
	 *       trusting the list: it is a property of the dataset, not of this code.</li>
	 * </ul>
	 *
	 * <p><b>The bound it carries.</b> A moiety the KB names by a bare WORD rather than by a qualifier is
	 * not reached — {@code Peanut oil} against {@code Peanut}, {@code Dextran 40}, {@code penicillin g,
	 * procaine} — because that shape is indistinguishable by spelling from
	 * {@code Digoxin Immune Fab (Ovine)} against {@code Digoxin}, a patient allergic to digoxin's
	 * ANTIDOTE, which issue #192 measured and separated. Reaching it needs a judgement about substances
	 * rather than about names, which this module does not make; the curated cross-reactivity groups are
	 * where a deployment can state one.
	 *
	 * @return one row per implied substance, first-appearance order, {@link #lookupByToken}'s answer
	 *         first; empty exactly when that answer is null
	 */
	public List<DrugReference> findImpliedSubstances(String drugName) {
		DrugReference strongest = lookupByToken(drugName);
		if (strongest == null) {
			return Collections.emptyList();
		}
		Map<Object, DrugReference> bySubstance = new LinkedHashMap<Object, DrugReference>();
		bySubstance.put(strongest.substanceGroupKey(), strongest);
		int claim = strongest.nameMatchStrength(drugName);
		if (claim >= DrugReference.NAME_IS_ANOTHER_NAME) {
			for (DrugReference ref : getAll()) {
				// A full scan, unlike lookupByToken's, which stops at the first display-name claim: the
				// equal claimants are exactly what it stops looking for.
				if (ref != strongest && ref.nameMatchStrength(drugName) == claim) {
					addSubstance(bySubstance, ref);
				}
			}
		}
		for (String constituent : DrugReference.combinationConstituents(drugName)) {
			addResolvedSubstance(bySubstance, constituent, DrugReference.NAME_IS_ANOTHER_NAME);
		}
		addResolvedSubstance(bySubstance, DrugReference.parentMoietyName(drugName),
				DrugReference.NAME_IS_THE_DISPLAY_NAME);
		return new ArrayList<DrugReference>(bySubstance.values());
	}

	/** Adds the substance {@code candidate} resolves to, when an entry claims it at {@code minimumClaim}
	 *  or better — through {@link #lookupByToken}, so a candidate string is resolved by the SAME ranking
	 *  as the recorded name it was derived from and this cannot become a second resolution rule. */
	private void addResolvedSubstance(Map<Object, DrugReference> bySubstance, String candidate,
			int minimumClaim) {
		if (candidate == null) {
			return;
		}
		DrugReference resolved = lookupByToken(candidate);
		if (resolved != null && resolved.nameMatchStrength(candidate) >= minimumClaim) {
			addSubstance(bySubstance, resolved);
		}
	}

	/** Keyed by {@link DrugReference#substanceGroupKey()}, first row seen kept: the representative of a
	 *  substance is the row with the strongest claim on the string that brought it in, and the legs run
	 *  strongest-claim-first, so a later leg can add a substance but never rename one. */
	private static void addSubstance(Map<Object, DrugReference> bySubstance, DrugReference ref) {
		Object key = ref.substanceGroupKey();
		if (!bySubstance.containsKey(key)) {
			bySubstance.put(key, ref);
		}
	}

	/**
	 * Name-driven matching for a clinician-entered drug NAME: EVERY entry that name resolves to, in
	 * dataset order. The multi-entry counterpart of {@link #lookupByToken} — a combination product's
	 * name resolves each of its constituents, and a drug the dataset files as several route variants
	 * resolves all of them — and the order-name counterpart of {@link #findByQuery}, which stays bound
	 * to the prose matcher because a question and an answer are prose.
	 *
	 * <p>Not the same question as {@link #findImpliedSubstances}, which is what a recorded name is read
	 * to NAME: this returns every entry the name MATCHES, including the ones issue #192 established are
	 * false claims on it ({@code Lactic acid} for {@code Ciprofloxacin lactate}). Its callers are the
	 * order-driven ones, where a match is the join and the ranking never applied.
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
		return names.isEmpty() ? context : context.withActiveDrugReferenceNames(names);
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
