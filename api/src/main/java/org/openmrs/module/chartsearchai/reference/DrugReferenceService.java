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
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
 * {@link ChartSearchAiConstants#GP_DRUG_REFERENCE_DATA_FILE_PATH} through
 * {@link ReferenceDataFiles} — the {@code json} and {@code ddinter} formats with a bundled classpath
 * default behind it, {@code atc} with none, so that format runs on the operator's own export or runs
 * empty. This lets the
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
 *
 * <p><b>Memoising anything derived from {@link #getAll()}: in a per-call LOCAL, never in a field.</b>
 * Issue #172's rule, and it binds every site that caches a resolution — here, in
 * {@code DrugSafetyValidator} and in {@code DrugReferenceInjector}. Nine comments across those three
 * classes used to justify it by a {@code getAll()} hot-reload, and <b>issue #172's own text still
 * does</b> — which is why the reason is stated HERE, and why a {@code #172} pointer elsewhere in this
 * package that carries no reason of its own means this paragraph and not the issue. Two sites take the
 * same RULE for a reason of their own rather than for these — {@link DrugReferenceValidity} and the
 * local that builds one in {@code ensureLoaded} are per-LOAD collectors, not {@code getAll()} memos,
 * and each says so where it stands.
 *
 * <p>There is no such reload. Measured 2026-08-14, two ways: statically, {@code dataset} is written
 * only by {@code ensureLoaded()} when it is null and by the package-private {@code setEntries} test
 * seam, which has no production caller — the module registers no {@code GlobalPropertyListener} and
 * exposes no reload endpoint. That half is pinned in-suite, and has been since issue #154, by
 * {@code DrugReferenceLoadContextTest.loadStatusDoesNotDriftFromTheCachedEntriesWhenTheGlobalPropertiesChange}
 * — read it rather than re-measuring by hand, and rather than trusting a paraphrase of it here. Live,
 * flipping
 * {@code chartsearchai.drugReference.sourceFormat} from {@code ddinter} to {@code atc} on a running
 * server left {@code GET /chartsearchai/drugreferencestatus} reporting the DDInter entries it already
 * had and a {@code /search} still raising its chip, where a reload would have re-parsed the DDInter
 * file with the ATC parser, loaded nothing and dropped it. The "held for the life of the bean"
 * paragraph above is the accurate statement and always was: it has been there since {@code d15719cf},
 * the commit that added this class, and so predates every one of those nine comments — the earliest of
 * which arrived with issue #173.
 *
 * <p><b>The group LIST is the same shape; one group's PREFIXES are not, and the two must not be read
 * as one fact.</b> The field behind {@link #getCrossReactivityGroups()} is written once by
 * {@code ensureGroupsLoaded()} when it is null and otherwise only by test seams, so it does not reload
 * either — and since issue #266 that one write also publishes the load's own outcome
 * ({@link CrossReactivityGroupsLoad}), which is why they are ONE holder rather than two fields. But
 * {@link CrossReactivityGroup#setAtcPrefixes} is public API Jackson writes through, and it has to stay
 * authoritative after a membership question has been asked — so
 * {@link CrossReactivityGroup#containsAnyCode} keeps the reason stated in its own javadoc, not this
 * one. Same rule, different mechanism. (Issue #248's own statement of that reason opens with the same
 * reload error this paragraph retires; only its write-path half survives.)
 *
 * <p>Two reasons hold today, and a third is why the discipline is worth keeping rather than merely
 * defensible. These are Spring singletons, so a memo held in a field is one unsynchronized map shared
 * by every concurrent request — stale reads and lost updates possible, and a torn internal structure
 * possible. And some of these memos are keyed on something UNBOUNDED, which is what makes a field one
 * that grows for the life of the JVM: two of {@code DrugSafetyValidator.orderPartners}' four are keyed
 * on a per-request {@code PatientClinicalContext.ActiveDrugOrder} object, and {@code validate}'s
 * recorded-allergen list has no key at all, so a field version of it would have to key on the allergy
 * tokens — patient free text, and it would answer for whoever asked first. The other two are bounded
 * and only the singleton reason binds them: one by the dataset's own aliases (see
 * {@link #findImpliedByDrugName(String, Map)}) and one by the ATC code space.
 *
 * <p>And a module whose memos are all per call is one a reload path can be ADDED to later without
 * re-auditing every one of them — the honest version of the reload reason, and the one that cannot
 * rot.
 *
 * <p>{@code RecordedAllergenMemoScopeTest} pins ONE shape of this — a memo outliving the entries it was
 * resolved from — and before it nothing pinned even that. It does not pin the first reason above: a
 * single-threaded case cannot observe a structure being shared, and a field REASSIGNED once per pass
 * stays green on it. See that test's javadoc, which says so rather than leaving the rule looking better
 * defended than it is.
 */
@Service("chartSearchAi.drugReferenceService")
public class DrugReferenceService {

	private static final Logger log = LoggerFactory.getLogger(DrugReferenceService.class);

	/**
	 * A completed load, published as ONE reference (issue #158).
	 *
	 * <p>The entries and the {@link DrugReferenceLoad} describing them used to be two volatile fields
	 * written in a required order — the status first, so a reader taking the lock-free fast path on the
	 * entries could not see them without the outcome. That is correct by the Java memory model, and it is
	 * an invariant nothing enforces: reversing the two writes survives the entire test suite, because the
	 * window is a single volatile write wide and cannot be hit reliably from a test. The invariant is
	 * load-bearing for the whole purpose of that status — it exists so that "what is actually loaded"
	 * cannot be read stale, and a reader seeing populated entries beside a status saying nothing was
	 * loaded defeats it in exactly the case it was built for.
	 *
	 * <p>So the ordering is removed rather than restated in a comment: one final field written once, so
	 * there is no order to get wrong and no reordering a refactor can introduce. What is left to assert is
	 * the pairing this carries, which
	 * {@code DrugReferenceLoadConcurrencyTest.aCompletedLoadIsNeverPublishedWithAStatusThatSaysNothingWasLoaded}
	 * does.
	 */
	private static final class LoadedDataset {

		private final List<DrugReference> entries;

		private final DrugReferenceLoad load;

		LoadedDataset(List<DrugReference> entries, DrugReferenceLoad load) {
			this.entries = Collections.unmodifiableList(entries);
			this.load = load;
		}
	}

	private volatile LoadedDataset dataset;

	/**
	 * A completed groups load, published as ONE reference — issue #158's rule, applied to the second
	 * dataset when it gained a status of its own (issue #266). Two volatile fields written in a required
	 * order would reintroduce exactly the invariant that issue removed: correct by the Java memory model,
	 * enforced by nothing, and load-bearing for the whole purpose of a status that exists so "what is
	 * actually loaded" cannot be read stale.
	 */
	private static final class LoadedGroups {

		private final List<CrossReactivityGroup> groups;

		private final CrossReactivityGroupsLoad load;

		LoadedGroups(List<CrossReactivityGroup> groups, CrossReactivityGroupsLoad load) {
			this.groups = Collections.unmodifiableList(groups);
			this.load = load;
		}
	}

	private volatile LoadedGroups loadedGroups;

	private DrugReferenceSource source;

	/**
	 * @return all loaded reference entries (never null; empty when nothing could be loaded).
	 *
	 *         <p><b>Memoise anything derived from this in a per-call LOCAL, never in a field</b> — issue
	 *         #172's rule, stated with its reasons in this class's javadoc, under "Two reasons hold
	 *         today". Restated here because this is where a reader arrives, while that comment opens on
	 *         something else entirely.
	 */
	public List<DrugReference> getAll() {
		return ensureLoaded().entries;
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
		LoadedDataset current = dataset;
		if (current != null) {
			return current.load;
		}
		if (!ChartSearchAiUtils.isDrugReferenceEnabled()) {
			return DrugReferenceLoad.notLoaded();
		}
		return ensureLoaded().load;
	}

	/**
	 * Question-driven matching: entries whose aliases hit the user's query text.
	 * Cheap and deterministic — no embedding required.
	 *
	 * <p>The PRIMITIVE and not the answer, since issue #209: it reports which entries prose MENTIONS,
	 * which is a strict superset of which SUBSTANCES prose names. Its one caller is
	 * {@link #findImpliedByQuery}, which applies the ranking on top; nothing else may build a candidate
	 * set from it — that admission was the defect.
	 *
	 * @param question the clinician's query
	 * @return matching entries, in dataset order, deduplicated
	 */
	List<DrugReference> findByQuery(String question) {
		if (question == null || question.trim().isEmpty()) {
			return Collections.emptyList();
		}
		List<DrugReference> out = new ArrayList<DrugReference>();
		// Folded once for the whole sweep, not once per entry (issue #330) — the prose counterpart of
		// findByDrugName's hoist below. foldedLower lower-cases for itself, so the separate
		// toLowerCase this used to keep would only have been a second one. What still folds per call is
		// findImpliedByQuery's aliasesIn, over the entries this returned rather than over the dataset.
		String foldedLower = DrugReference.foldedLower(question);
		for (DrugReference ref : getAll()) {
			if (ref.matchesFoldedText(foldedLower)) {
				out.add(ref);
			}
		}
		return out;
	}

	/**
	 * The entries a PROSE text puts in play — {@link #findByQuery}, restricted to the entries whose
	 * SUBSTANCE that text is read to name. The answer every chip arm and the injector need, and the
	 * ranked counterpart of the boolean scan above.
	 *
	 * <p><b>Why the boolean scan is not that answer (issue #209).</b> {@link #findByQuery} asks whether
	 * an entry is MENTIONED, which is a question about prose and is answered correctly. What its callers
	 * ask is which SUBSTANCES the text puts in play, and one alias is routinely shared by two of them:
	 * measured through the production predicates over the shipped 19 MB KB (2026-08-09; re-derive rather
	 * than trusting the figures), {@code matchesText("hydrocortisone")} is true for all four
	 * hydrocortisone rows while {@link DrugReference#nameMatchStrength} scores {@code Hydrocortisone} 2
	 * and {@code Hydrocortisone butyrate} 1 — a different substance, an ester the patient is not on. Live,
	 * that was a chip reading "Hydrocortisone butyrate is in the same ATC class (H02AB) as the patient's
	 * allergy to Dexamethasone" for a patient whose only order in the family is
	 * {@code Hydrocortisone Injection vial 100mg}.
	 *
	 * <p><b>How much it narrows</b>, measured by driving each of the 2283 published display names through
	 * this method and through {@link #findByQuery} (2026-08-09; re-derive rather than trusting the
	 * figures): 33 names resolve fewer rows, 71 rows in total, and the two invariants below hold for every
	 * one of the 2283 — nothing was emptied and no substance lost a row. The order-name leg
	 * ({@link #findImpliedByDrugName}) narrows by the same counts over the same corpus. Read the row total
	 * with its cause attached: 7 of those names and 42 of those rows are the single upstream data defect
	 * on issue #196, where {@code Pfizer-BioNTech Covid-19 Vaccine} publishes
	 * {@code moderna covid-19 vaccine} among its aliases and all five {@code Tozinameran} rows inherit it.
	 * Excluding it the ranking moves 26 names and 29 rows, so most of the row total is one bad alias's
	 * consequences rather than a reshaping of clinical data.
	 *
	 * <p><b>Why that is fewer names than issue #209 counted, and deliberately.</b> The issue measured a
	 * different question — how many display names the unranked scan admits a substance for that
	 * {@link #findImpliedSubstances} does not report for the WHOLE STRING — and got a larger figure. Both
	 * are correct; the gap is exactly the names where the whole-string rule would narrow and the
	 * name-CARRIED rule refuses to, which is the paragraph below and not a shortfall. Re-derive both
	 * before quoting either: they differ by roughly a factor of two on the shipped KB, so a reader
	 * comparing the issue's figure with this one will otherwise read a fix as incomplete.
	 *
	 * <p><b>The rule: the name CARRIED, not the whole text.</b> An entry stays when one of its own names
	 * that the text carries ({@link DrugReference#aliasesIn}) denotes its substance under
	 * {@link #findImpliedSubstances}. The witness is what makes this rankable at all — the text is prose
	 * and prose has no claim strength, while the alias by which an entry matched is exactly the kind of
	 * string {@code findImpliedSubstances} takes. It also keeps the narrowing about the CLAIM rather than
	 * about spans: a question naming {@code hydrocortisone butyrate} keeps the ester (which is named it
	 * outright) AND the parent substance (whose own name the string also carries), because neither name's
	 * resolution is affected by the other appearing beside it.
	 *
	 * <p><b>What it cannot do.</b> It cannot empty a non-empty set. On the DDInter and ATC datasets that
	 * follows from the rule: an entry's carried alias resolves to some strongest claimant, that claimant
	 * carries the same alias and so is in the matched set, and {@code findImpliedSubstances} always
	 * answers with its substance first — so whatever the text most strongly names always survives. The
	 * middle step is a property of those PARSERS rather than of this filter, and a hand-authored
	 * {@code json} dataset can break it, so the invariant is ENFORCED in {@link #rowsOf} instead of being
	 * left to follow — see there for the shape and for why emptying is the one answer this must never
	 * give. And it drops whole SUBSTANCES only, never a row of one that
	 * survives: the verdict is taken per substance and then applied to every matched row of it. That
	 * second half is not decoration. A qualified row need not carry the alias its own family's bare row
	 * carries — {@code Estrone sulfate (topical)} publishes {@code estrone} as its {@code rxnorm_name},
	 * the OTHER substance's name, and nothing spelled {@code estrone sulfate} — so a per-row verdict
	 * dropped a presentation of a substance that was in play, and with it any rule sitting only on that
	 * presentation (measured over the shipped KB: one published name, before this was made per-substance).
	 *
	 * <p>Route/formulation variants are therefore kept, deliberately: they ARE the substance, and the arms
	 * downstream need the whole family — the dose arm tries each row for a published band, and
	 * {@code DrugSafetyValidator.resolvedSubstanceRows} chooses one rule across all of them.
	 *
	 * @return the matching entries, in dataset order, deduplicated — a subset of {@link #findByQuery}
	 */
	public List<DrugReference> findImpliedByQuery(String question) {
		List<DrugReference> matched = findByQuery(question);
		if (matched.size() < 2) {
			// A no-op on one match, and provably so given rowsOf's empty-set fallback: either the single
			// row's own alias denotes its substance and it is kept, or nothing is in play and rowsOf returns
			// `matched` anyway. Purely a cost guard, so this is the common case not paying for the
			// resolution; removing it cannot change an answer.
			return matched;
		}
		String lower = question.toLowerCase(Locale.ROOT);
		Map<Object, Set<Object>> impliedByName = new HashMap<Object, Set<Object>>();
		Set<Object> inPlay = new HashSet<Object>();
		for (DrugReference ref : matched) {
			if (namesSubstanceOf(ref, ref.aliasesIn(lower), impliedByName)) {
				inPlay.add(ref.substanceGroupKey());
			}
		}
		return rowsOf(matched, inPlay);
	}

	/**
	 * The entries a clinician-entered drug NAME puts in play — {@link #findByDrugName}, restricted to the
	 * entries whose SUBSTANCE that name is read to name. The recorded-name counterpart of
	 * {@link #findImpliedByQuery}, and the multi-row counterpart of {@link #findImpliedSubstances}: that
	 * one answers with one representative row per substance, which is what a LABEL needs, while the arms
	 * screening a patient's orders need every row of each substance the name denotes.
	 *
	 * <p>Same rule and same reason as {@link #findImpliedByQuery} — see there — with the witness taken
	 * under the recorded-name boundary rule ({@link DrugReference#aliasesNaming}) rather than the prose
	 * one, so a localized display name still resolves: {@code Aspirine Co 81mg} carries {@code aspirin}
	 * here and nothing under the prose rule, which is issue #147.
	 *
	 * <p>The witness matters most sharply on this leg. A recorded order name is usually nobody's name —
	 * a display name with a strength appended — so the strongest claim on the WHOLE string is only
	 * {@link DrugReference#NAME_TOKEN_INSIDE_A_NAME}, at which rank {@code findImpliedSubstances}
	 * deliberately refuses to widen (see there) and answers with the earliest matching row alone.
	 * Filtering on that would discard genuinely-named substances — a combination order name would keep one
	 * ingredient and drop the rest, the direction issues #193/#195 exist to prevent. Resolving the name
	 * each row actually matched BY has no such edge: {@code Hydrocortisone Injection vial 100mg} carries
	 * {@code hydrocortisone}, which denotes one substance, while {@code Abacavir / lamivudine} carries
	 * {@code abacavir} for one row and {@code lamivudine} for the other and both stand.
	 *
	 * <p><b>The constraint on what this answer may be used FOR (issue #226).</b> This result may seed a
	 * CANDIDATE SET, where a superset costs a spurious candidate that a later rank filters out. It must
	 * not seed a SUPPRESSION unless that consumer accepts a superset, because there over-reporting
	 * silences a warning — with no chip, no log line and nothing for a clinician to notice. One accessor,
	 * two consumers whose safe direction of error is opposite; {@code DrugSafetyValidator}'s
	 * restating-existing-therapy skip has been the second kind since issue #185.
	 *
	 * <p>The over-report is not a missing rank, and that matters because it rules out the obvious fix.
	 * It is {@link #rowsOf}'s FAIL-OPEN, which is deliberate and pinned by
	 * {@code SubstanceCandidateSetTest.narrowingNeverEmptiesACandidateSetEvenWhenNoMatchedRowIsTheStrongestClaimant}:
	 * emptying a non-empty set means a question naming a drug gets no contraindication, no interaction and
	 * no overdose check at all, which is the silent-and-closed failure this whole layer exists to prevent.
	 * The {@code matched.size() < 2} early return above is that same fail-open's DEGENERATE case rather
	 * than a second shape — with one candidate there is nothing to rank, so ranking harder cannot narrow
	 * it. Giving a suppression a narrower answer therefore means a THIRD accessor, one allowed to return
	 * empty, plus a decision about what a suppression should do when it does.
	 *
	 * <p><b>What makes it safe today is the DATA, not this code.</b> The fallback's precondition is an
	 * entry whose {@code aliases} omit its own {@code name}; measured 2026-08-14 through
	 * {@link DrugReference#isNamed}, that holds for <b>0 of the 2283 entries</b> of the shipped 19 MB KB
	 * and 0 of each smaller bundled dataset, which makes the fallback unreachable for ANY input string on them
	 * rather than merely for a sampled population. It is a property of the PARSERS
	 * ({@link DdiDrugReferenceSource} makes the display name {@code alias[0]},
	 * {@link AtcDrugReferenceSource} makes it the only alias) and, since issue #150, of
	 * {@link DrugReferenceValidity}'s {@code sanitizeAliases}, which appends an entry's own name when it
	 * is missing — except, since issue #296, where that name is itself a string that names nothing, which
	 * it REPORTS instead of repairing, for the reason recorded there. So an entry left without a
	 * self-name IS constructible again, in a hand-authored file whose display name folds to nothing;
	 * what would make this reachable is a future DATASET, not a future caller — re-derive that count
	 * before relying on it. The narrowing is meanwhile doing real work and is not inert:
	 * measured the same way through this method, it removes a substance the string does not name on
	 * <b>276 of 29 808</b> order-name-shaped strings, {@code Hydrocortisone Injection vial 100mg} reaching
	 * {@code Hydrocortisone butyrate} (issue #209) among them.
	 *
	 * @return the matching entries, in dataset order — a subset of {@link #findByDrugName}
	 */
	public List<DrugReference> findImpliedByDrugName(String drugName) {
		return findImpliedByDrugName(drugName, new HashMap<Object, Set<Object>>());
	}

	/**
	 * As {@link #findImpliedByDrugName(String)}, sharing one resolution cache with the other names of the
	 * same call — which is {@link #findForActiveOrders}, where a patient's orders contribute several
	 * names each (the coded drug's, the free text a clinician typed for a non-coded one, and the
	 * concept's) and several orders of one family carry the same aliases, and
	 * since issue #228 {@code DrugSafetyValidator.substanceRowsNamedBy}, which asks the same question of
	 * the same names once per {@code validate} pass — once per IN-PLAY SUBSTANCE until issue #256
	 * hoisted the co-medication resolution that reaches it to the pass. What the cache saves is not the match scan but the
	 * WITNESS resolution behind it: each alias a matched row carries costs a {@link #findImpliedSubstances},
	 * and those repeat across the names of one order and across orders of one family.
	 *
	 * <p>The cache is a per-call LOCAL of the outermost caller, never a field — issue #172's rule, for
	 * the reasons this class's javadoc gives — NOT the {@link #getAll()} hot-reload this comment used to
	 * cite, which does not exist. The reason that applies here is the first one there —
	 * this is a singleton bean, so a field memo would be one unsynchronized map shared by every
	 * concurrent request. Not the second: these keys are normalized aliases of the LOADED entries, so
	 * the map is bounded by the dataset rather than by patient free text. Package-private for that second
	 * caller and no wider: the cache is the whole of what it adds, and a caller that cannot hold one has
	 * {@link #findImpliedByDrugName(String)}.
	 */
	List<DrugReference> findImpliedByDrugName(String drugName,
			Map<Object, Set<Object>> impliedByName) {
		List<DrugReference> matched = findByDrugName(drugName);
		if (matched.size() < 2) {
			return matched;
		}
		Set<Object> inPlay = new HashSet<Object>();
		DrugReference.FoldedName folded = DrugReference.fold(drugName);
		for (DrugReference ref : matched) {
			if (namesSubstanceOf(ref, ref.aliasesNaming(folded), impliedByName)) {
				inPlay.add(ref.substanceGroupKey());
			}
		}
		return rowsOf(matched, inPlay);
	}

	/**
	 * @return every row of {@code matched} whose substance is in {@code inPlay}, in the order given — the
	 *         second half of both legs above, which is what makes their verdict per SUBSTANCE rather than
	 *         per row. See {@link #findImpliedByQuery} for the presentation this exists to keep.
	 *
	 *         <p><b>And {@code matched} unchanged when nothing is in play</b>, which is where the
	 *         "cannot empty a non-empty set" invariant is ENFORCED rather than assumed. The argument for
	 *         it (see {@link #findImpliedByQuery}) needs the strongest claimant on a carried alias to
	 *         carry that alias itself, and so to be in {@code matched} too. That is a property of the
	 *         PARSERS, not of this filter: {@link DdiDrugReferenceSource} makes an entry's display name
	 *         its first alias and {@link AtcDrugReferenceSource} makes it the only one, so on both of
	 *         those every entry names itself. A hand-authored {@code json} dataset need not — the shape
	 *         {@link DrugReference#nameMatchStrength}'s javadoc already records its gate as excluding —
	 *         and a {@code json} dataset is what {@code sourceFormat=json} loads — the default until ADR
	 *         Decision 36, and still what a deployment needing dosing selects. There the rank-2 claimant can be an
	 *         entry the prose matcher never reached, and then no matched row's alias denotes its own
	 *         substance and every one is dropped.
	 *
	 *         <p>Emptying has to be ruled out because this list is what every arm iterates: an emptied
	 *         set means a question naming a drug gets no contraindication, no interaction and no overdose
	 *         check, with nothing in the log to say so — the silent-and-closed failure this feature exists
	 *         to prevent. Falling back to {@code matched} is the pre-#209 answer for that one shape, and it
	 *         over-reports, which for a non-blocking advisory is the safe direction. Measured over every
	 *         shipped dataset the fallback never fires — 0 firings on each, over the 7452 names and
	 *         aliases of the full 19 MB KB and over the smaller bundled datasets — so it costs the shipped
	 *         configuration nothing.
	 *         {@code narrowingNeverEmptiesACandidateSetEvenWhenNoMatchedRowIsTheStrongestClaimant} pins it.
	 *
	 *         <p>It bounds emptying and nothing more. A dataset carrying that same shape can still lose
	 *         every rule-bearing row while keeping a rule-less one, because then {@code inPlay} is
	 *         non-empty and this never fires — measured on the fixture above, an order for
	 *         {@code Ibuprofen tablets 400mg} keeps only the bare {@code Ibuprofen} row and the reference
	 *         data's findings go with the dropped rows. Not addressed here: it needs the same precondition
	 *         as the emptying shape — an entry whose aliases omit its own name — and no shipped dataset has
	 *         one (measured: 0 such entries in the full 19 MB KB and in each smaller bundled dataset).
	 *         Closing it would mean deciding what a source publishing no substance name should mean by
	 *         "one substance", which this issue does not settle.
	 */
	private static List<DrugReference> rowsOf(List<DrugReference> matched, Set<Object> inPlay) {
		if (inPlay.isEmpty()) {
			return matched;
		}
		List<DrugReference> out = new ArrayList<DrugReference>();
		for (DrugReference ref : matched) {
			if (inPlay.contains(ref.substanceGroupKey())) {
				out.add(ref);
			}
		}
		return out;
	}

	/**
	 * @return whether one of {@code carried} — the entry's own names the recorded string carries — denotes
	 *         {@code ref}'s substance. Through {@link #findImpliedSubstances}, so the two legs above and
	 *         the allergy arm read one recorded name the same way and this cannot become a fourth
	 *         resolution rule; keyed on {@link DrugReference#normalizeName} so two entries spelling one
	 *         shared alias differently share the cache entry.
	 */
	private boolean namesSubstanceOf(DrugReference ref, List<String> carried,
			Map<Object, Set<Object>> impliedByName) {
		Object substance = ref.substanceGroupKey();
		for (String name : carried) {
			Object key = DrugReference.normalizeName(name);
			if (key == null) {
				continue;
			}
			Set<Object> implied = impliedByName.get(key);
			if (implied == null) {
				implied = new HashSet<Object>();
				for (DrugReference row : findImpliedSubstances(name)) {
					implied.add(row.substanceGroupKey());
				}
				impliedByName.put(key, implied);
			}
			if (implied.contains(substance)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Patient-driven matching: entries whose ATC codes match an active drug order
	 * on the patient's chart, regardless of whether the question mentions the drug.
	 *
	 * <p>The PRIMITIVE and not the answer, as {@link #findByQuery} and {@link #findByDrugName} are —
	 * though it errs the other way. Those two answer a WIDER question than their callers ask; this one
	 * answers a narrower: it reports only the entries reached from the orders a DICTIONARY happened to
	 * map to ATC, which is a subset of what {@link #findForActiveOrders} answers and empty on a
	 * dictionary that maps none. That method is the answer — "which reference entries are this
	 * patient's active orders" — and nothing else may build a candidate set from this one.
	 * That admission was issue #151: {@code DrugReferenceInjector.matchingEntries}
	 * resolved its order-driven leg here while {@code DrugSafetyValidator} screened the union, so the
	 * two layers disagreed about which orders the patient had, and reference material about a drug she
	 * was on stayed out of the prompt behind the chip that named it.
	 *
	 * @param context the patient's clinical context (active-order ATC codes)
	 * @return matching entries, in dataset order, deduplicated
	 */
	List<DrugReference> findByActiveOrders(PatientClinicalContext context) {
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
	 * The entries the reference dataset's own dictionary bridge files under {@code conceptUuid} — the
	 * THIRD key of the order-to-entry join, beside {@link #findByActiveOrders}' ATC codes and
	 * {@link #findImpliedByDrugName}'s recorded names (issue #353).
	 *
	 * <p><b>Why a third key.</b> The other two are both defeated by one shape, and the shape is
	 * ordinary: a dictionary concept the bridge records under one name while the deployment's locale
	 * elects another. CIEL 105281 is {@code Sulfamethoxazole / trimethoprim} in the bridge and in an
	 * {@code en} session, and {@code Cotrimoxazole} in an {@code fr} one; the knowledge base carries no
	 * spelling of the second, and the concept's ATC code {@code J01EE01} is on no entry at all. So the
	 * whole interaction screen for the commonest co-prescription in HIV care disappeared in one locale
	 * and not the other, with no exception and no log line. The concept the order was written against
	 * is the one key that does not depend on which of its names a session elects.
	 *
	 * <p><b>RANKED, and that is not decoration.</b> The answer is the entries the bridge files under
	 * the concept INTERSECTED with the ones {@link #findImpliedByDrugName} answers for the name the
	 * bridge records for it. Both bounds are load-bearing and they fail in opposite directions. Without
	 * the ranking the leg is WIDER than the name leg it stands in for — a bridged uuid can reach
	 * several entries that are not one substance ({@code Trastuzumab}, {@code Trastuzumab deruxtecan}
	 * and {@code Trastuzumab emtansine} share one bridged concept and share no ATC code), which is
	 * issue #209's widening arriving by a new route; and two consumers of this leg are SUPPRESSIONS —
	 * {@code DrugSafetyValidator.activeOrdersOtherThan} withholds an order from witnessing an
	 * interaction, {@code OrderPartner.substances} silences a duplicate-therapy chip — where a superset
	 * removes a warning with no chip and no log line to notice it by (see
	 * {@link #findImpliedByDrugName(String, Map)}'s own constraint). Without the intersection the leg
	 * could reach an entry the bridge does not file under this concept at all, on the strength of a
	 * name it merely shares.
	 *
	 * <p>What the two bounds buy together: the answer is a SUBSET of what a session electing the
	 * bridge's own spelling already gets today, so this leg states nothing the reference data does not
	 * already state about that concept — it removes the dependence on WHICH spelling the session
	 * elects. What it inherits with that is the bridge's own defects: ADR Decision 33 records ~10 stray
	 * cross-walk rows in the shipped knowledge base, and this leg makes those locale-independent too.
	 * That is the trade and it cannot be had one way round — the correct rows and the stray ones are
	 * the same field.
	 *
	 * <p>Package-private, like {@link #findByActiveOrders} and for the same reason: it is a LEG and not
	 * an answer. "Which reference entries are this patient's active orders" is
	 * {@link #findForActiveOrders}, and nothing else may build a candidate set from this one.
	 *
	 * @param conceptUuid the uuid of the concept an active order was written against, or null
	 * @param impliedByName the caller's per-call resolution cache — a LOCAL, never a field (issue #172)
	 * @return the bridged entries in dataset order, deduplicated, unmodifiable; empty for a null or
	 *         unbridged concept, and empty on every source but {@code ddinter}, which is the only one
	 *         whose format carries a dictionary bridge
	 */
	List<DrugReference> findByBridgedConcept(String conceptUuid,
			Map<Object, Set<Object>> impliedByName) {
		if (conceptUuid == null || conceptUuid.trim().isEmpty()) {
			return Collections.emptyList();
		}
		String uuid = conceptUuid.trim();
		// One pass for both halves — which entries the bridge files here, and the name it files them
		// under — because asking twice would walk the whole dataset twice for one order.
		List<DrugReference> bridged = new ArrayList<DrugReference>();
		Set<String> bridgeNames = new LinkedHashSet<String>();
		for (DrugReference ref : getAll()) {
			String bridgeName = ref.bridgedConceptName(uuid);
			if (bridgeName != null) {
				bridged.add(ref);
				bridgeNames.add(bridgeName);
			}
		}
		if (bridged.isEmpty()) {
			return Collections.emptyList();
		}
		// Identity, as findForActiveOrders' own dedup is and for the same reason: every row here came
		// out of this bean's shared getAll() cache.
		Set<DrugReference> ranked = Collections.newSetFromMap(
				new IdentityHashMap<DrugReference, Boolean>());
		for (String bridgeName : bridgeNames) {
			ranked.addAll(findImpliedByDrugName(bridgeName, impliedByName));
		}
		List<DrugReference> out = new ArrayList<DrugReference>();
		for (DrugReference ref : bridged) {
			if (ranked.contains(ref)) {
				out.add(ref);
			}
		}
		return Collections.unmodifiableList(out);
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
		DrugReference.FoldedName folded = DrugReference.fold(drugToken);
		for (DrugReference ref : getAll()) {
			int strength = ref.nameMatchStrength(folded);
			// Strictly greater, so the earliest entry keeps the role on a tie — including the tie with
			// NAME_NO_MATCH, which is how a non-matching entry is skipped.
			if (strength > strongest) {
				best = ref;
				strongest = strength;
				if (strongest == DrugReference.NAME_IS_THE_DISPLAY_NAME) {
					// Nothing outranks it and a later equal claim would lose the tie anyway, so the scan is
					// over. It is an exit, not a cost guarantee: a name reaching only the alias or the
					// containment rank has no such stopping point and scans every entry where the
					// first-match rule could stop at the first one. That is the shape to time if this ever
					// looks expensive, and this call is no longer the unit to count it in: since issues
					// #193/#195 findImpliedSubstances makes several of these per recorded name — one for
					// the name, one per constituent, one for the parent moiety, and its own full pass for
					// the equal claimants — while DrugSafetyValidator resolves the allergy list once per
					// validate instead of once per drug in play.
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
	 *       merely occur inside the recorded one. The equally-claimed names that are NOT {@code /}-joined
	 *       are what makes this leg irreplaceable rather than a second route to the one below: the KB
	 *       also spells combinations with a word or a hyphen — {@code amoxicillin and clavulanic acid},
	 *       {@code rifampicin isoniazid pyrazinamide and ethambutol},
	 *       {@code potassium chloride-potassium gluconate}, {@code sultamicillin tosylate} — and no
	 *       separator rule reaches those. {@code CombinationAllergenResolutionTest} pins one;</li>
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
	 * <p>The other bound is that the legs read the RECORDED STRING and never each other's output, so a
	 * qualifier on a combination name switches the wider legs off instead of composing with them:
	 * {@code abacavir / lamivudine (oral)} is no entry's name, so the claim on it drops to the
	 * containment rank and the equal-claimant leg is skipped, and the constituent it splits into is
	 * {@code lamivudine (oral)}, which is no entry's name either — so that name implies one substance
	 * where the unqualified spelling implies two. Never fewer than the arm had before all of this, so it
	 * is a shape not reached rather than one made worse. No published name in the shipped KB loses a
	 * substance this way (measured through this method; re-derive rather than trusting it), so it
	 * reaches production through a free-text allergen and not through the reference data.
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
		DrugReference.FoldedName folded = DrugReference.fold(drugName);
		int claim = strongest.nameMatchStrength(folded);
		if (claim >= DrugReference.NAME_IS_ANOTHER_NAME) {
			for (DrugReference ref : getAll()) {
				// A full scan, unlike lookupByToken's, which stops at the first display-name claim: the
				// equal claimants are exactly what it stops looking for.
				if (ref != strongest && ref.nameMatchStrength(folded) == claim) {
					addSubstance(bySubstance, ref);
				}
			}
		}
		for (DrugReference derived : derivedRows(drugName)) {
			addSubstance(bySubstance, derived);
		}
		return new ArrayList<DrugReference>(bySubstance.values());
	}

	/**
	 * Of the substances {@link #findImpliedSubstances} reads out of a recorded drug NAME, the ones the
	 * name itself NAMES — the question a caller about to QUOTE the chart asks, and deliberately a
	 * narrower one than which substances the name implies (issue #268).
	 *
	 * <p><b>Why the two differ, and must.</b> That resolution is additive on purpose (issues
	 * #193/#195): a recorded name reaches every substance it could denote, so the class and
	 * cross-reactivity comparisons see all of them. Narrowing it would trade a false positive for a
	 * false NEGATIVE in a safety net. But its equal-claimant leg admits a row on a rank TIE, and a tie
	 * is satisfied by two quite different things — a combination the KB spells without a separator
	 * ({@code amoxicillin and clavulanic acid}, that leg's own reason for existing) and two substances
	 * sharing one name that is neither's display name. The second is a row the recorded name does not
	 * name, and a chip saying "The patient has a recorded allergy to X." about it states something the
	 * chart does not.
	 *
	 * <p><b>Three ways a name names a row</b>, and no leg is exempt AS a leg:
	 * <ul>
	 *   <li>it is the <b>unique strongest NAME claimant</b> — the recorded name IS one of its names
	 *       ({@link DrugReference#NAME_IS_ANOTHER_NAME} or better) and every other implied substance
	 *       claims the whole recorded name strictly less strongly. That keeps an allergy recorded as
	 *       one of a row's own aliases naming its row ({@code papaveretum} → {@code Opium}).
	 *       <p>Deliberately NOT "the first element": {@link #lookupByToken} breaks a tie by earliest
	 *       dataset entry, which carries no clinical meaning, so on a tie the first row has no
	 *       privilege — three shipped rows publish {@code gallium} as their {@code rxnorm_name} and
	 *       exempting the earliest would announce a radiodiagnostic the chart never mentions while
	 *       correcting its two co-tied rivals in the same payload.
	 *       <p>And deliberately NOT "the strongest claim available, however weak", which is what this
	 *       said first and what a reachable input refutes. At the CONTAINMENT rank the equal-claimant
	 *       leg of {@link #findImpliedSubstances} does not run, so rival rows never enter the implied
	 *       set at all and the survivor is uncontested for a reason that is an artefact of the
	 *       resolution rather than evidence about the record: an allergy charted as
	 *       {@code gallium — hives} resolves that way, and naming its row prints the same
	 *       radiodiagnostic off a chart that says {@code Gallium}. Requiring a NAME claim costs nothing
	 *       on a name the reference data publishes — measured 2026-08-24, 145 (name, row) pairs over
	 *       the shipped KB are claimed only at containment, 143 of them named by their own label
	 *       anyway (a name that matched by containment usually CONTAINS the row's name) and the other
	 *       two by the derivation clause below ({@code menthol / selenium disulfide},
	 *       {@code yohimbe preparation / zinc sulfate}). None is lost. Free text is where a containment
	 *       match does not carry the row's name;</li>
	 *   <li>a name its printed label is built from OCCURS in the recorded string
	 *       ({@link DrugReference#labelNameOccursIn}) — what a separator-less combination asserts
	 *       ({@code Amoxicillin} in {@code amoxicillin and clavulanic acid}, {@code Trastuzumab
	 *       emtansine} in {@code ado-trastuzumab emtansine}) and what a shared alias does not;</li>
	 *   <li>a combination CONSTITUENT or the parent MOIETY of the recorded name names its SUBSTANCE, at
	 *       those two legs' OWN ranks and through the same {@link #resolvedAtLeast} they resolve with —
	 *       so every substance {@link #findImpliedSubstances} admits through them is named by
	 *       construction, whichever row ends up representing it. The moiety rank stays
	 *       {@link DrugReference#NAME_IS_THE_DISPLAY_NAME}: that leg's javadoc records why it is
	 *       stricter than the constituent gate, and relaxing it here would license printing the name of
	 *       exactly the rows it refuses.</li>
	 * </ul>
	 *
	 * <p><b>What it gives up, measured like everything else here.</b> Over the shipped KB,
	 * <b>169 of 6888</b> (recorded name, row) pairs take the relationship sentence rather than the
	 * identity one. Most are corrections — a shared alias, or a row the recorded string reaches
	 * through one ({@code acetic acid / hydrocortisone} → {@code Hydrocortisone butyrate},
	 * {@code amoxicillin / clarithromycin / esomeprazole combination kit} → {@code Omeprazole}). The
	 * cost is the rest: a recorded name spelling out several ingredients whose ingredient this KB files
	 * under a SYNONYM is named by none of the three clauses — {@code atovaquone / chloroguanide} does
	 * not carry {@code Proguanil} and no constituent of it resolves there, and
	 * {@code ascorbic acid / folate} does not carry {@code Folic acid}. Both sentences are true; the
	 * more specific one is lost, which is the safe direction for something reporting a record, and the
	 * defect it replaces is a FALSE sentence. The two populations are not separated by a predicate —
	 * telling them apart is a judgement about substances, which is why the sentence changes rather than
	 * the resolution.
	 *
	 * <p><b>What it does not reach at all</b>, so that a reader does not take the paragraph above for
	 * the whole surface: this decides WHICH SUBSTANCE a sentence may name, never which ROW represents
	 * that substance. {@link #addSubstance} keeps the first row seen, so a recorded
	 * {@code estradiol / levonorgestrel} still names the estradiol substance by its
	 * {@code Fluoroestradiol f-18} row — a PET tracer the KB files under
	 * {@code [estradiol, db00783]}, the same substance key. That is issue #187/#206's question, it
	 * behaves exactly as it did before issue #268, and nothing here improves or worsens it.
	 *
	 * <p>One more consequence, stated because CLAUDE.md's own rule is that one substance must not be
	 * named two ways in one response: a single recorded allergy CAN now produce two chips whose
	 * sentences name their subject differently, one identifying it and one stating the relationship.
	 * Each names its own drug, so no reader is misled about which drug a chip is about; what is given
	 * up is the uniformity.
	 *
	 * <p><b>Every clause decides rows no other one does.</b> Measured 2026-08-24 through the real
	 * {@link DdiDrugReferenceSource#parse} of the shipped 19 MB KB, this method and
	 * {@link DrugReference#matchesOrderName}, over <b>all 5169 published names</b> as the recorded
	 * string — rows named by that clause and by no other: appended generic 40 (the penicillin G family,
	 * {@code atropine sulfate} → {@code Hyoscyamine (atropine)}), display name 55, unique claim 322,
	 * derived substance 153. State the base: three of those four are the same over any base, and the
	 * unique column is not — restricted to names implying MORE than one substance it reads 1, because
	 * its other 321 are ordinary single-substance names ({@code thyroxine} → {@code Levothyroxine}).
	 * A reader taking the smaller figure for the whole would conclude the clause is all but redundant
	 * and delete it. Re-derive rather than trusting the figures — they are a property of the dataset,
	 * not of this code — but do not drop a clause on the assumption that another covers it.
	 *
	 * <p>Package-private, like {@link #findByActiveOrders} and for the same reason: this answers a
	 * NARROWER question than {@link #findImpliedSubstances} and must never be mistaken for a resolution.
	 * A caller building a candidate set from it would silently drop the substances a recorded name
	 * implies without naming — which is every comparison this module makes about a shared class.
	 *
	 * @param drugName the recorded name, as the chart holds it
	 * @param implied  that name's substances, as {@link #findImpliedSubstances} resolved them — passed
	 *                 in rather than re-resolved, so this cannot become a second resolution rule
	 * @return the sublist of {@code implied} the name names, in the same order; the rows are the very
	 *         objects handed in, so a caller may test membership by identity
	 */
	List<DrugReference> findNamedSubstances(String drugName, List<DrugReference> implied) {
		Set<Object> derived = null;
		List<DrugReference> named = new ArrayList<DrugReference>(implied.size());
		for (DrugReference row : implied) {
			if (row.labelNameOccursIn(drugName) || uniqueStrongestClaimant(drugName, row, implied)) {
				named.add(row);
				continue;
			}
			// Resolved on demand, and never for a row the two cheap clauses already settled: a
			// constituent that resolves to NOTHING has no early exit in lookupByToken and costs a full
			// sweep of the dataset, which is exactly what a combination allergen string is full of.
			// Most rows never get here, so most recorded names never pay for it at all. No tally: the
			// numbers move whenever the dataset or the clauses do, and the mechanism is the reason.
			// A per-call local, never a field (issue #172).
			if (derived == null) {
				derived = derivedSubstanceKeys(drugName);
			}
			if (derived.contains(row.substanceGroupKey())) {
				named.add(row);
			}
		}
		return named;
	}

	/** @return whether {@code row} claims the whole of {@code drugName} as a NAME
	 *          ({@link DrugReference#NAME_IS_ANOTHER_NAME} or better) AND strictly more strongly than
	 *          every other candidate in {@code candidates} — {@link #findNamedSubstances}'s first clause,
	 *          both halves. The rank floor is not a detail of the implementation: without it an
	 *          uncontested CONTAINMENT match passes, which is the shape that reads
	 *          {@code gallium — hives} and names a radiodiagnostic.
	 *
	 *          <p><b>Package-visible since issue #296, for a second caller asking the same question of a
	 *          different string.</b> {@code DrugSafetyValidator.unambiguouslyNames} asks it of a rule's
	 *          match TOKEN rather than of a name the chart recorded, to decide whether a chip may
	 *          call an active order by the class arm's label instead of by that token (since issue #339
	 *          any rule chip, not only a folded one). One definition
	 *          rather than two: both callers are deciding whether a name may be PRINTED as this
	 *          substance's, and a second spelling of "strictly outranks every rival" could drift from
	 *          this one in the direction that matters — admitting where this refuses puts one
	 *          substance's rated mechanism under another's name.
	 *
	 *          <p><b>The candidate set is the caller's</b>, and the two differ on purpose. This one is
	 *          {@link #findImpliedSubstances}'s output: one representative row per substance, over a
	 *          {@link DrugReference#matchesDrugName} superset. The validator's is every row the token
	 *          {@link DrugReference#isNamed}s, minus the rows of {@code row}'s own substance — so a
	 *          sibling presentation of the substance being named cannot contest its own family's claim,
	 *          and a rival substance is measured by its STRONGEST row rather than by a representative.
	 *          What is shared is the comparison, which is the part that must not be written twice.
	 *
	 *          <p>Strictly, so a TIE refuses: two substances that claim a name equally cannot say which
	 *          of them it denotes, and both callers need that answer to be "neither". */
	static boolean uniqueStrongestClaimant(String drugName, DrugReference row,
			List<DrugReference> candidates) {
		DrugReference.FoldedName folded = DrugReference.fold(drugName);
		int claim = row.nameMatchStrength(folded);
		if (claim < DrugReference.NAME_IS_ANOTHER_NAME) {
			return false;
		}
		for (DrugReference other : candidates) {
			if (other != row && other.nameMatchStrength(folded) >= claim) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The loaded entries indexed by the names they answer {@link DrugReference#isNamed} to — one entry
	 * of the map per {@link DrugReference#normalizeName}d alias, each holding every row carrying it, in
	 * dataset order.
	 *
	 * <p><b>One walk, so that "which entries does this token name" stops being one.</b>
	 * {@code DrugSafetyValidator.unambiguouslyNames} collected its rival claimants by walking
	 * {@link #getAll()}, which was affordable while issue #292 asked it once per FOLDED chip — "the
	 * rare outcome of the class arm rather than the ordinary one", as that method said. Issue #339 asks
	 * it once per rule CHIP, and the number of distinct (token, entry) pairs a pass reconciles grows
	 * with the drugs in play, so a walk per ask breaks the invariant
	 * {@code CoMedicationResolutionPerPassTest.theCoMedicationResolutionDoesNotGrowWithTheDrugsInPlay}
	 * states — that the dataset sweeps a chart costs are the SAME at every drug count. A memo over the
	 * asks cannot restore it (the asks themselves grow); inverting the dataset once can, because one
	 * walk is one walk however many chips read it.
	 *
	 * <p>Built by the caller and HELD BY THE CALLER for its pass — a per-call local threaded through,
	 * never a field on this bean (CLAUDE.md, issue #172): this map is keyed on the loaded aliases, so
	 * it is bounded, but the bean is a Spring singleton and an unsynchronised map shared by concurrent
	 * requests is the first of the two reasons that rule gives. {@code DrugSafetyValidator.CoMedications}
	 * is the only holder.
	 *
	 * @return a fresh index; the caller owns it. Read it back through
	 *         {@link #entriesNamedBy(String, Map)} rather than by {@code get}, so the token is
	 *         normalised the one way {@link DrugReference#isNamed} normalises it.
	 */
	Map<String, List<DrugReference>> nameIndex() {
		Map<String, List<DrugReference>> index = new LinkedHashMap<String, List<DrugReference>>();
		for (DrugReference entry : getAll()) {
			for (String key : entry.nameKeys()) {
				List<DrugReference> named = index.get(key);
				if (named == null) {
					named = new ArrayList<DrugReference>();
					index.put(key, named);
				}
				named.add(entry);
			}
		}
		return index;
	}

	/**
	 * @return every entry of the indexed dataset that {@link DrugReference#isNamed} {@code token} — the
	 *         same answer a walk asking that predicate of each row would give, in the same order, and
	 *         empty for a blank token, which names nothing.
	 *
	 *         <p>Static and taking the index rather than reading a field, for the reason
	 *         {@link #nameIndex()} gives about where the index lives. The equality with the walk is a
	 *         property of {@link DrugReference#nameKeys()} being derived from the same aliases through
	 *         the same normalisation as the predicate, and it is asked of whole loaded datasets by
	 *         {@code NameIndexAgreesWithIsNamedTest} rather than argued here.
	 */
	static List<DrugReference> entriesNamedBy(String token, Map<String, List<DrugReference>> index) {
		String key = DrugReference.normalizeName(token);
		List<DrugReference> named = key == null ? null : index.get(key);
		// Unmodifiable, and for the reason Decision 58 states of findForActiveOrders: this list is the
		// index's own, so a consumer that sorted or filtered it in place would change what every later
		// reader of the pass is told about a token's claimants — and that decides whether one
		// substance's rated mechanism may be printed under another's name. O(1) over a list the caller
		// only iterates.
		return named == null ? Collections.<DrugReference> emptyList()
				: Collections.unmodifiableList(named);
	}

	/**
	 * @return the SUBSTANCE of each row {@link #derivedRows} resolves — {@link #findNamedSubstances}'s
	 *         third clause, and a view over the very list {@link #findImpliedSubstances} folds into its
	 *         implied set, so the two cannot disagree about which legs exist or what rank each admits
	 *         at.
	 *
	 *         <p>Keyed on the SUBSTANCE and not on the row, which is what makes it a mirror rather
	 *         than an approximation: {@link #addSubstance} keeps the FIRST row seen for a substance, so
	 *         a substance a derivation leg reached can be represented in the implied list by a row some
	 *         earlier leg contributed — a row that need not claim the constituent itself. Asking the
	 *         row would then refuse to name a substance the recorded string demonstrably asserts.
	 *
	 *         <p><b>The moiety leg contributes nothing to THIS clause today.</b> Measured 2026-08-24:
	 *         removing it from the mirror changes no naming decision over the shipped KB and reddens no
	 *         test, because {@link DrugReference#parentMoietyName} returns a PREFIX of the recorded name
	 *         and that leg admits a row only where the prefix IS its display name — so
	 *         {@link DrugReference#labelNameOccursIn} has already said yes. That is a coincidence of
	 *         three rules in two classes, not a property of this one, and it stops holding the moment a
	 *         moiety is derived as anything but a prefix. It is not this method's to drop in any case:
	 *         {@link #derivedRows} owns the leg list and the resolution leg needs it.
	 */
	private Set<Object> derivedSubstanceKeys(String drugName) {
		Set<Object> keys = new HashSet<Object>();
		for (DrugReference row : derivedRows(drugName)) {
			keys.add(row.substanceGroupKey());
		}
		return keys;
	}

	/**
	 * @return the rows the two DERIVATION legs of {@link #findImpliedSubstances} resolve out of
	 *         {@code drugName} — each combination CONSTITUENT an entry is NAMED, then the parent MOIETY
	 *         an entry is CALLED — in that order, so {@link #addSubstance}'s first-row-wins rule sees
	 *         them exactly as it did when the legs were written inline.
	 *
	 *         <p>The single expression of WHICH legs there are and WHAT rank each admits at, because
	 *         {@link #findNamedSubstances} has to mirror both to decide whether a derived substance may
	 *         be printed. Sharing only the per-candidate gate was not enough: the leg list and its two
	 *         rank constants were written twice, so a third leg added here would not be mirrored, and a
	 *         rank tightened here but not there would let the mirror print a name the record does not
	 *         name — issue #268 re-entering through the clause written to prevent it.
	 */
	private List<DrugReference> derivedRows(String drugName) {
		List<DrugReference> rows = new ArrayList<DrugReference>();
		for (String constituent : DrugReference.combinationConstituents(drugName)) {
			addResolved(rows, constituent, DrugReference.NAME_IS_ANOTHER_NAME);
		}
		addResolved(rows, DrugReference.parentMoietyName(drugName),
				DrugReference.NAME_IS_THE_DISPLAY_NAME);
		return rows;
	}

	/** Appends what {@code candidate} resolves to at {@code minimumClaim} or better, if anything —
	 *  {@link #derivedRows}'s one-line accumulator, so its two legs read as the two questions they are. */
	private void addResolved(List<DrugReference> rows, String candidate, int minimumClaim) {
		DrugReference resolved = resolvedAtLeast(candidate, minimumClaim);
		if (resolved != null) {
			rows.add(resolved);
		}
	}

	/**
	 * @return the entry {@code candidate} resolves to when it claims it at {@code minimumClaim} or
	 *         better, else null — the derivation legs' gate itself, named once because
	 *         {@link #findNamedSubstances} has to ask the SAME question of the SAME string to decide
	 *         whether a derived substance may be printed. Two spellings of it would let the two answers
	 *         drift, and in the direction that matters: the leg admits a substance and the mirror then
	 *         refuses to name it, so a chip quotes the chart where it had a perfectly good name.
	 */
	private DrugReference resolvedAtLeast(String candidate, int minimumClaim) {
		if (candidate == null) {
			return null;
		}
		DrugReference resolved = lookupByToken(candidate);
		return resolved != null && resolved.nameMatchStrength(candidate) >= minimumClaim ? resolved : null;
	}

	/** Keyed by {@link DrugReference#substanceGroupKey()}, FIRST row seen kept — so a later leg can add
	 *  a substance but never rename one, which is what keeps every existing chip label unchanged for a
	 *  name implying one substance.
	 *
	 *  <p>First is not the same as strongest-claiming, and the difference is visible: where the whole
	 *  name reaches a substance only weakly and a constituent reaches the SAME substance by its own
	 *  display name, the weaker-claiming row stays the representative — {@code 4-aminobenzoic acid /
	 *  salicylic acid} keeps {@code Salicylic acid (sodium)} although its {@code salicylic acid}
	 *  constituent names {@code Salicylic acid} outright (29 published names, measured through this
	 *  method 2026-08-09; re-derive before relying on the figure). That is the answer
	 *  {@link #lookupByToken} already gave the whole name, so it is a bound carried rather than one
	 *  introduced, and preferring the stronger claimant would be a relabelling decision — which is
	 *  {@link DrugReference#canonicalRow}'s question, deliberately not asked here (see
	 *  {@link DrugReference#nameMatchStrength}: applying it would rename a charted
	 *  {@code Ketorolac (ophthalmic)} allergy to {@code Ketorolac}). */
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
	 * false claims on it ({@code Lactic acid} for {@code Ciprofloxacin lactate}). It is therefore the
	 * PRIMITIVE and not the answer: since issue #209 its one caller is
	 * {@code findImpliedByDrugName}, which applies the ranking on top, and nothing else may build a
	 * candidate set from it — that admission was the defect.
	 */
	List<DrugReference> findByDrugName(String drugName) {
		if (drugName == null || drugName.trim().isEmpty()) {
			return Collections.emptyList();
		}
		List<DrugReference> out = new ArrayList<DrugReference>();
		// Folded once for the whole sweep, not once per entry (issue #330): the name is this scan's
		// invariant operand and every entry's aliases arrive already folded, so nothing below folds.
		DrugReference.FoldedName folded = DrugReference.fold(drugName);
		for (DrugReference ref : getAll()) {
			if (ref.matchesDrugName(folded)) {
				out.add(ref);
			}
		}
		return out;
	}

	/**
	 * The reference entries the patient's active orders resolve to — the subjects
	 * {@code DrugSafetyValidator.addActiveOrderPairInteractions} screens against each other, the
	 * subjects {@code addActiveOrderContraindications} checks against the patient's own allergy and
	 * condition records, the candidate set {@code DrugReferenceInjector.matchingEntries} scopes
	 * order-driven injection over (issue #151), and the source of the names {@link #withReferenceNames}
	 * attaches. It unions the documented order-driven matcher ({@link #findByActiveOrders}, which
	 * keys on ATC codes) with a name resolution of every name the patient's active orders carry
	 * ({@link #findImpliedByDrugName} over {@link PatientClinicalContext#getActiveDrugNames()}, the
	 * flattened union of each order's {@code ActiveDrugOrder.getNames()} — since issue #293 a display
	 * name, the free text a clinician typed, and a concept name, not the display alone). One definition, so those consumers cannot come to disagree
	 * about which of the patient's prescriptions the reference data covers.
	 *
	 * <p>That last consumer joined late and at a cost, which is why this list is worth keeping literal:
	 * the injector resolved the ATC-only primitive for itself from the moment this union was
	 * introduced, so the chips and the prompt behind them were computed over different sets of orders.
	 * It now takes the list its caller already resolved rather than calling this a second time.
	 *
	 * <p>Both of those keys are needed because {@link PatientClinicalContext#hasActiveDrug} — the join
	 * that decides whether a rule concerns this patient — matches on name OR ATC, so a subject set
	 * resolved on only one of them cannot be the subject of every chip that join can raise. Neither key
	 * can be assumed present: measured on the 3.7.1 standalone's demo dictionary (2026-08-04), ATC coverage is
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
	 * <p>The name leg is the recorded-name matcher rather than {@link #findByQuery} since issue #147: an
	 * order's display name is a localized drug name, so resolving it with the prose rule left
	 * {@code Aspirine Co 81mg} and {@code Clarithromycine Co 500mg} matching no entry at all —
	 * measured, 117 (order name, entry) pairs gained and 0 lost over the 3.7.1 dictionary's 2533 names.
	 * That argument used to add "not prose", and issue #293 retired that half: this set now also holds
	 * the free text a clinician typed, which can be a whole sentence. The choice of matcher is
	 * unchanged — the recorded-name rule is still what a localized display name needs, and the prose
	 * rule would still lose the 117 — but what it is applied to is no longer all one shape, and the
	 * cost of that is recorded on {@code PatientClinicalContextBuilder.addDrugName}.
	 *
	 * <p>And it is {@link #findImpliedByDrugName} rather than the bare {@link #findByDrugName} since issue
	 * #209: a match is the join for the ATC leg above, where the code either belongs to the order or does
	 * not, but a NAME can be shared by two substances and this list is what three arms report ON. Sarah
	 * Taylor's one {@code Hydrocortisone Injection vial 100mg} order resolved 4 rows and 2 substances, the
	 * second of them {@code Hydrocortisone butyrate}, and the order-driven contraindication arm reported it
	 * as cross-reactive with her dexamethasone allergy — a chip about a drug she is not prescribed. Her
	 * whole order list resolved 18 entries and 9 substances from 8 orders; ranked, 17 and 8.
	 *
	 * <p><b>And a THIRD key since issue #353: the CONCEPT each order was written against</b>
	 * ({@link #findByBridgedConcept}). The two above are both defeated by one ordinary shape — a
	 * dictionary concept the reference data's bridge records under one name while the deployment's
	 * locale elects another — and for CIEL 105281 that took the whole cotrimoxazole interaction screen
	 * out in {@code fr} while leaving it standing in {@code en}, with no exception and no log line.
	 * That leg needs no analogue in {@link PatientClinicalContext#hasActiveDrug} and must not be given
	 * one: it reaches that join the way the ATC leg already does, through
	 * {@link #withReferenceNames}, which copies the resolved entries' own aliases into the context's
	 * {@code activeDrugReferenceNames}. A rule has no concept to be keyed on, so a leg there would be
	 * a second spelling of a fact this one already carries.
	 *
	 * <p>Identity de-duplication is sound because all three matchers resolve against this bean's shared
	 * {@link #getAll()} cache (the same reason the drugs-in-play set can dedup by identity).
	 *
	 * <p><b>The list is UNMODIFIABLE</b> — adding to it, removing from it or sorting it in place throws
	 * {@code UnsupportedOperationException}. Said here rather than only where it is returned, because
	 * this method is public and a caller reads the javadoc: since issue #255 the list one caller holds
	 * can be the same object another reasons over, and the two production paths that could reach a
	 * mutation both sit inside a {@code catch (RuntimeException)}, so an undocumented throw degrades to
	 * no injected records and no chips behind one WARN rather than to a stack trace.
	 */
	public List<DrugReference> findForActiveOrders(PatientClinicalContext context) {
		if (context == null) {
			return Collections.emptyList();
		}
		Set<DrugReference> entries = new LinkedHashSet<DrugReference>(findByActiveOrders(context));
		// One resolution cache for every name in this list — see findImpliedByDrugName(String, Map) for
		// why it is a local of THIS call and not a field.
		Map<Object, Set<Object>> impliedByName = new HashMap<Object, Set<Object>>();
		for (String name : context.getActiveDrugNames()) {
			entries.addAll(findImpliedByDrugName(name, impliedByName));
		}
		// The third key (issue #353): the CONCEPT each order was written against, through the reference
		// dataset's own dictionary bridge. Sharing the resolution cache above, because that leg resolves
		// the bridge's recorded name through the very same ranked accessor. Contributes nothing where the
		// context carries no per-order structure and nothing on a dataset whose format has no bridge.
		for (PatientClinicalContext.ActiveDrugOrder order : context.getActiveDrugOrders()) {
			entries.addAll(findByBridgedConcept(order.getConceptUuid(), impliedByName));
		}
		// Unmodifiable, and that is a contract rather than caution (issue #255). Since that change the
		// list a caller holds can be the SAME object another one reasons over — DrugReferenceInjector
		// hands its resolution to the validate it calls and then goes on rendering from it — so an arm
		// that sorted or filtered it in place would change what the injector puts in the chart, after
		// the fact and silently. The wrapper is O(1) over the copy already being made, it binds every
		// consumer from the one place the list is produced, and nothing in the module mutates it today.
		return Collections.unmodifiableList(new ArrayList<DrugReference>(entries));
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
		return ensureGroupsLoaded().groups;
	}

	/**
	 * Which drug CLASS this question names, when it names one — {@code "oral contraceptive"},
	 * {@code "NSAID"} — or {@code null} where it names none (issue #354). The vocabulary and the two
	 * sources behind it are {@link DrugClassTerms}; what belongs HERE is what the answer may and may
	 * not be used for.
	 *
	 * <p><b>It asks nothing of the dataset's ENTRIES, so it is not self-gating.</b> A question naming
	 * both a class and a drug this dataset resolves answers with the class, and the caller is what
	 * decides whether that matters: {@code DrugReferenceInjector.injectRecords} raises its note only
	 * where {@code findImpliedByQuery} came back empty. A second caller written as though this method
	 * had asked that question would report a class over a drug the module did resolve —
	 * {@code DrugClassQuestionNoteTest.aQuestionNamingAClassAndAResolvableDrugRaisesNoNote} is that
	 * case, and it drives the injector rather than this method for exactly that reason.
	 *
	 * <p><b>And a CONSUMER of the response never asks it.</b> What the {@code unresolvedDrugClass}
	 * response key states is read off the injected chart by
	 * {@link org.openmrs.module.chartsearchai.ChartSearchAiUtils#unresolvedDrugClass}, precisely
	 * because this method is not self-gating and knows nothing of {@code injectFromQuery}: asking it
	 * again at the wire would publish a class for a question the module did resolve a substance for,
	 * and would be a second resolution of one question that can disagree with the prompt (issue
	 * #151's shape). The sole production caller stays {@code DrugReferenceInjector.injectRecords}.
	 *
	 * <p><b>It answers with a class NAME and never a member set, deliberately.</b> A caller may report
	 * that the question named a class; it may NOT read the answer as putting substances in play. The
	 * candidate-set accessors are {@link #findImpliedByQuery} and {@link #findImpliedByDrugName} and
	 * this is not a third one — it resolves nothing, so a chip arm handed its answer has no subject,
	 * no severity and no partner.
	 *
	 * <p><b>Why the class is not resolved to its members, which is the whole of issue #354's design
	 * decision.</b> The ATC hierarchy the issue proposes does not express these classes — measured in
	 * both directions on the shipped knowledge base, and recorded ONCE, with its figures and its date,
	 * in ADR Decision 67 rather than restated here. A class resolved that way would put a substance's
	 * own label into a {@code safety_finding} that {@code DrugReferenceInjector.renderFinding} copies
	 * verbatim into citable evidence carrying {@code STRENGTH_WITHHOLD}, asserting a class membership
	 * false of the drug named — the shape CLAUDE.md records as reverted in issue #339's rounds 5-6.
	 * The sound alternative, a hand-curated clinical MEMBERSHIP list, is a knowledge-base deliverable
	 * rather than a module one, and CLAUDE.md's ATC-subgroup bullet records what hand-picking one cost
	 * last time.
	 *
	 * <p><b>Why it lives here and not in {@code QueryScopeRouter}.</b> That class owns enumeration
	 * INTENT and forbids a second drug vocabulary for it. This is not intent: what a question's drug
	 * words denote is already resolved here, by {@link #findImpliedByQuery}, and this is that same
	 * question asked one rung wider — the rung at which the answer is a class rather than a substance.
	 *
	 * @param question the clinician's query; {@code null} or blank names no class
	 */
	public String namedDrugClass(String question) {
		return DrugClassTerms.namedIn(question, getCrossReactivityGroups());
	}

	/**
	 * @return the outcome of the cross-reactivity groups load that is IN FORCE — see
	 *         {@link CrossReactivityGroupsLoad}. Triggers the (lazy) load when the feature is enabled and
	 *         nothing has loaded yet; reports {@link CrossReactivityGroupsLoad#notLoaded()} without
	 *         loading anything when the feature is switched off, so polling the status cannot manufacture
	 *         a parse on an install that does not use the feature. A load that HAS happened is reported
	 *         whatever the switch says now — the groups in memory are the ones the safety layer would
	 *         use. Every clause of that is {@link #getLoadStatus()}'s contract for the entry dataset,
	 *         deliberately: two datasets, one question, and one answer to how it is asked.
	 *
	 *         <p>Issue #266. Until then this load's findings reached only the log, which cannot answer
	 *         after a lazy load (issue #154) — so {@code configured-data-file-not-read} for the groups
	 *         file was invisible to the operator it names.
	 */
	public CrossReactivityGroupsLoad getCrossReactivityLoadStatus() {
		LoadedGroups current = loadedGroups;
		if (current != null) {
			return current.load;
		}
		if (!ChartSearchAiUtils.isDrugReferenceEnabled()) {
			return CrossReactivityGroupsLoad.notLoaded();
		}
		return ensureGroupsLoaded().load;
	}

	/**
	 * @return the completed groups load, performing it once however many callers race here. One write, so
	 *         the groups and the outcome describing them are published together or not at all (see
	 *         {@link LoadedGroups}).
	 */
	private LoadedGroups ensureGroupsLoaded() {
		LoadedGroups current = loadedGroups;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (loadedGroups != null) {
				return loadedGroups;
			}
			// One instance, so the origin, the configured path and the findings read below all belong to
			// the load performed here — the same contract, and the same reason, as ensureLoaded's read of
			// the entry source. The path comes from the loader rather than from a second read of the
			// global property, so the pair this status reports (configuredFilePath against origin) is
			// provably ONE read of it: those two are exactly what config.xml tells an operator to
			// compare, and reporting them from two reads is how they could disagree about which file the
			// finding beside them is about.
			CrossReactivityGroupsLoader loader = new CrossReactivityGroupsLoader();
			List<CrossReactivityGroup> loaded = loader.load();
			LoadedGroups completed = new LoadedGroups(loaded,
					new CrossReactivityGroupsLoad(loader.lastConfiguredPath(), loader.lastLoadOrigin(),
							loaded.size(), loader.lastLoadFindings()));
			loadedGroups = completed;
			return completed;
		}
	}

	/**
	 * @return the completed load, performing it once however many callers race here — the lock-free fast
	 *         path is the whole reason the entries and the outcome are published as one reference (see
	 *         {@link LoadedDataset}).
	 */
	private LoadedDataset ensureLoaded() {
		LoadedDataset current = dataset;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (dataset != null) {
				return dataset;
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
			// The load-time validity check (issues #150, #156, #196, #211): the configuration rules the
			// resolution ran, plus the content rules, which are applied HERE — once, for every format —
			// so an operator's file gets the same checks whichever parser read it. A per-load local, never
			// a field — issue #172's rule, taken here for DrugReferenceValidity's own reason (a collector
			// describes the ONE load that built it) rather than for the getAll()-memo reasons in this
			// class's javadoc: this local is built before `dataset` is assigned below.
			DrugReferenceValidity validity = new DrugReferenceValidity();
			validity.addAll(active.lastLoadFindings());
			validity.configuredSourceFormatNotUsed(configuredFormat, effectiveFormat);
			validity.checkEntries(loaded);
			// With the origin, so a data finding is reported at the level of whoever can act on it: the
			// operator for their own file, the module's maintainers for the knowledge base we ship
			// (ADR Decision 36). A configuration finding is loud either way — see logTo.
			validity.logTo(log, active.lastLoadOrigin());
			DrugReferenceLoad outcome = new DrugReferenceLoad(effectiveFormat, configuredFormat,
					configuredPath, active.lastLoadOrigin(), loaded, validity.getFindings());
			// What this install is actually checking, said once at the moment of the load — stamped with
			// the dataset it belongs to, which is what a lazily-loaded cache makes a later reader guess
			// at. INFO because an arm with nothing behind it is a capability the dataset does not have
			// rather than a defect in it — no validity finding, so ADR Decision 36's loudness scoping is
			// untouched. One rendering, shared with the wire, so the log and the endpoint cannot name a
			// verdict two ways.
			//
			// This is NOT the channel that removes the need to poll, and must not be justified as one:
			// core's shipped log4j2.xml puts org.openmrs at WARN, so an unmodified install prints
			// neither this line nor the Parsed/Loaded ones beside it, and the answer to issue #285 is
			// the status endpoint's own arms field. Raising it to WARN is not the fix either — under the
			// shipped default two arms are absent on EVERY install with nothing an operator can do
			// about it, which is precisely the register ADR Decision 36 refused for that dataset's own
			// findings.
			log.info("Drug-reference safety arms over the {} entr(ies) read from {}: {}",
					outcome.getEntryCount(), outcome.getOrigin(), outcome.armSummary());
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
			// One write, so the entries and the outcome describing them are published together or not at
			// all — there is no ordering left to get wrong (issue #158; see LoadedDataset).
			LoadedDataset completed = new LoadedDataset(loaded, outcome);
			dataset = completed;
			return completed;
		}
	}

	/**
	 * @return the source format that will actually be used for {@code configuredFormat}: {@code atc} and
	 *         {@code ddinter} select their own adapters, and any other value — a typo — falls through to
	 *         the curated {@code json} parser. Reported in {@link DrugReferenceLoad#getSourceFormat()} so
	 *         that fallback is visible rather than silent — a mistyped format is one of the ways a
	 *         deployment ends up parsing a dataset with the wrong parser and loading nothing.
	 *
	 *         <p><b>The unset case no longer arrives here as a typo.</b> The caller reads the global
	 *         property with {@link ChartSearchAiConstants#DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT} as its
	 *         default, and since ADR Decision 36 that is {@code ddinter} — so an install that configured
	 *         nothing, and a unit test with no context, both reach this method with {@code ddinter} and
	 *         match above. What still lands on the fall-through is a value that matches no adapter, and
	 *         that is now a DIFFERENT answer from the default rather than the same one: mistyping
	 *         {@code ddinter} hands whatever {@code dataFilePath} names to the curated parser. Loud, via
	 *         {@link DrugReferenceValidity#configuredSourceFormatNotUsed}.
	 *
	 *         <p>The fall-through returns the curated format's NAME rather than "whatever the default
	 *         is" — they were equal until Decision 36 and were never the same fact. It is paired with
	 *         {@link #sourceFor(String)}'s own fall-through, which is unconditionally
	 *         {@link JsonDrugReferenceSource}, so the name has to be the one that parser answers to
	 *         however the default moves.
	 */
	private static String effectiveFormat(String configuredFormat) {
		if (ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_ATC.equalsIgnoreCase(configuredFormat)) {
			return ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_ATC;
		}
		if (ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER.equalsIgnoreCase(configuredFormat)) {
			return ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER;
		}
		return ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON;
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
		this.dataset = new LoadedDataset(entries == null ? Collections.<DrugReference> emptyList()
				: new ArrayList<DrugReference>(entries), DrugReferenceLoad.notLoaded());
		this.loadedGroups = new LoadedGroups(Collections.<CrossReactivityGroup> emptyList(),
				CrossReactivityGroupsLoad.notLoaded());
	}

	/**
	 * Test seam: inject known cross-reactivity groups, bypassing the groups-file load. Paired with
	 * {@link CrossReactivityGroupsLoad#notLoaded()} for the reason {@link #setEntries} pairs its entries
	 * with {@link DrugReferenceLoad#notLoaded()}: the retained outcome describes a LOAD, and there was
	 * none. It is the one path on which the status and the groups in use are not two views of one event;
	 * production never seeds groups.
	 */
	void setCrossReactivityGroups(List<CrossReactivityGroup> groups) {
		this.loadedGroups = new LoadedGroups(groups == null
				? Collections.<CrossReactivityGroup> emptyList()
				: new ArrayList<CrossReactivityGroup>(groups), CrossReactivityGroupsLoad.notLoaded());
	}
}
