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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The slice of a patient's clinical state the drug-reference feature needs:
 * age (for dose-band selection and age-gated injection), weight in kg (for the
 * weight-aware per-dose overdose check; {@code null} = unknown, check skipped),
 * the names/ATC codes of active drug orders (for interaction checks and
 * order-driven injection), the active drug orders themselves — names and codes attributed to the
 * order they came from, both for reconciling the safety layer's read against the serialized chart
 * (see {@link DrugReferenceInjector#unrepresentedActiveOrders}) and so the interaction screen can
 * exclude a subject's own order from witnessing it — lowercased text tokens
 * from active allergies and conditions (for contraindication checks), and the names the loaded
 * reference data gives those same active drugs (issue #136 — see
 * {@link #getActiveDrugReferenceNames()}).
 *
 * <p>This is a pure value object so the injector and validator can be unit-tested
 * with hand-built contexts, while production builds one from a {@code Patient} via
 * {@link PatientClinicalContextBuilder}. Keeping the OpenMRS-{@code Context} reads in
 * a separate builder is what lets the matching/validation logic run without a
 * live OpenMRS context. The reference names are the one field the builder cannot fill, since they
 * come from the drug dataset rather than from the patient; they are attached afterwards by
 * {@link DrugReferenceService#withReferenceNames}, and a context without them behaves exactly as
 * every context did before #136.
 */
public class PatientClinicalContext {

	private final Integer ageYears;

	private final Double weightKg;

	private final Set<String> activeDrugNames;

	private final Set<String> activeDrugAtcCodes;

	private final Set<String> allergyTokens;

	private final Set<String> conditionTokens;

	private final List<ActiveDrugOrder> activeDrugOrders;

	private final Set<String> activeDrugReferenceNames;

	/** Whether the two chart lists a contraindication rule is put to — allergies and conditions — were
	 *  actually READ, as opposed to read and found empty. {@link PatientClinicalContextBuilder} swallows
	 *  a failure of either read and degrades that dimension to an empty set, which is right for a chip
	 *  (a finding it cannot substantiate is a finding it must not raise) and wrong for the injected
	 *  record's NEGATIVE half, which would otherwise tell the model this patient records none of a
	 *  drug's contraindications because the module could not look (issue #208 item 2). Every other
	 *  reader is unaffected and should stay that way: this says nothing about whether the lists are
	 *  empty, only about whether the emptiness means anything. */
	private final boolean contraindicationRecordsRead;

	public PatientClinicalContext(Integer ageYears, Double weightKg, Set<String> activeDrugNames,
			Set<String> activeDrugAtcCodes, Set<String> allergyTokens, Set<String> conditionTokens) {
		this(ageYears, weightKg, activeDrugNames, activeDrugAtcCodes, allergyTokens, conditionTokens, null);
	}

	/**
	 * Full form, carrying the active drug orders as individually-identified orders in addition to
	 * the flattened name/ATC sets the matching uses. The flattened sets stay independent of this
	 * list: they are what the {@link #hasActiveDrug} predicate and every class-based check read, so a
	 * caller supplying only them still gets every match those make. What such a caller does NOT get is
	 * attribution — which order a name or a code came from — so the interaction screen falls back to
	 * the weaker guard documented on {@code DrugSafetyValidator.activeOrdersOtherThan} (names since
	 * #118, ATC codes since #132), and nothing can be reconciled against the chart.
	 */
	public PatientClinicalContext(Integer ageYears, Double weightKg, Set<String> activeDrugNames,
			Set<String> activeDrugAtcCodes, Set<String> allergyTokens, Set<String> conditionTokens,
			List<ActiveDrugOrder> activeDrugOrders) {
		this(ageYears, weightKg, activeDrugNames, activeDrugAtcCodes, allergyTokens, conditionTokens,
				activeDrugOrders, null);
	}

	/**
	 * Widest form, additionally carrying the reference data's own names for the drugs the active
	 * orders name — see {@link #getActiveDrugReferenceNames()}. Not public: those names are resolved
	 * against the loaded dataset, which this value object deliberately knows nothing about, so they
	 * arrive through {@link DrugReferenceService#withReferenceNames} rather than being assembled by a
	 * caller.
	 */
	private PatientClinicalContext(Integer ageYears, Double weightKg, Set<String> activeDrugNames,
			Set<String> activeDrugAtcCodes, Set<String> allergyTokens, Set<String> conditionTokens,
			List<ActiveDrugOrder> activeDrugOrders, Set<String> activeDrugReferenceNames) {
		this(ageYears, weightKg, activeDrugNames, activeDrugAtcCodes, allergyTokens, conditionTokens,
				activeDrugOrders, activeDrugReferenceNames, true);
	}

	/**
	 * As above, additionally recording whether the allergy and condition reads SUCCEEDED — see
	 * {@link #contraindicationRecordsRead}. Package-private and defaulted to {@code true} everywhere
	 * else on purpose: only {@link PatientClinicalContextBuilder}, which performs those reads, is in a
	 * position to say otherwise, and a caller assembling a context by hand knows what it put in it.
	 */
	PatientClinicalContext(Integer ageYears, Double weightKg, Set<String> activeDrugNames,
			Set<String> activeDrugAtcCodes, Set<String> allergyTokens, Set<String> conditionTokens,
			List<ActiveDrugOrder> activeDrugOrders, Set<String> activeDrugReferenceNames,
			boolean contraindicationRecordsRead) {
		this.contraindicationRecordsRead = contraindicationRecordsRead;
		this.ageYears = ageYears;
		this.weightKg = weightKg;
		this.activeDrugNames = lower(activeDrugNames);
		this.activeDrugAtcCodes = upper(activeDrugAtcCodes);
		this.allergyTokens = lower(allergyTokens);
		this.conditionTokens = lower(conditionTokens);
		this.activeDrugOrders = activeDrugOrders == null ? Collections.<ActiveDrugOrder> emptyList()
				: Collections.unmodifiableList(new ArrayList<ActiveDrugOrder>(activeDrugOrders));
		this.activeDrugReferenceNames = lower(activeDrugReferenceNames);
	}

	/**
	 * @return a copy of this context carrying {@code referenceNames} as the reference data's own names
	 *         for the drugs its active orders name. Everything else is preserved.
	 */
	PatientClinicalContext withActiveDrugReferenceNames(Set<String> referenceNames) {
		return new PatientClinicalContext(ageYears, weightKg, activeDrugNames, activeDrugAtcCodes,
				allergyTokens, conditionTokens, activeDrugOrders, referenceNames,
				contraindicationRecordsRead);
	}

	/** @return whether the allergy and condition lists were read at all — see
	 *          {@link #contraindicationRecordsRead}. A reader that makes a NEGATIVE claim out of their
	 *          emptiness has to ask; a reader that only acts on what IS in them does not. */
	boolean contraindicationRecordsRead() {
		return contraindicationRecordsRead;
	}

	/** Pre-weight constructor, retained for test convenience (production uses the weight-carrying
	 *  form): equivalent to an unknown weight. */
	public PatientClinicalContext(Integer ageYears, Set<String> activeDrugNames,
			Set<String> activeDrugAtcCodes, Set<String> allergyTokens, Set<String> conditionTokens) {
		this(ageYears, null, activeDrugNames, activeDrugAtcCodes, allergyTokens, conditionTokens);
	}

	/** Through the shared {@link DrugReference#normalizeName} rule, not a second trim-and-lower-case:
	 *  {@link #hasActiveDrug} compares a rule token against {@link #activeDrugReferenceNames} by
	 *  IDENTITY, and {@code DrugSafetyValidator.namesEntry} asks that same question of an entry's own
	 *  aliases, so the two sides have to normalize alike or the reference-name arm silently stops
	 *  matching what that test says it matches. */
	private static Set<String> lower(Set<String> in) {
		if (in == null) {
			return Collections.emptySet();
		}
		Set<String> out = new LinkedHashSet<String>();
		for (String s : in) {
			String normalized = DrugReference.normalizeName(s);
			if (normalized != null) {
				out.add(normalized);
			}
		}
		return Collections.unmodifiableSet(out);
	}

	/** Any run of whitespace, matching the collapse {@code PatientClinicalContextBuilder.addRaw}
	 *  applies to every name this class holds. */
	private static final Pattern COLLAPSE_WHITESPACE = Pattern.compile("\\s+");

	private static Set<String> upper(Set<String> in) {
		// The active-order side of every ATC comparison must normalize by the same shared rule as
		// the reference side (entry codes / group prefixes), or matching silently drifts apart.
		if (in == null) {
			return Collections.emptySet();
		}
		return Collections.unmodifiableSet(DrugReference.normalizeAtcTokens(in));
	}

	/** @return the patient's age in years, or {@code null} when unknown. */
	public Integer getAgeYears() {
		return ageYears;
	}

	/** @return the patient's most recent weight in kg, or {@code null} when unknown/stale. */
	public Double getWeightKg() {
		return weightKg;
	}

	/** @return lowercased names the patient's active drug orders carry — the flattened union of each
	 *          order's {@link ActiveDrugOrder#getNames()}, not their displays alone: a coded drug's
	 *          name, the free text a clinician typed for a non-coded one (issue #293), and a concept's
	 *          name all reach it. */
	public Set<String> getActiveDrugNames() {
		return activeDrugNames;
	}

	/**
	 * @return lowercased names the loaded reference data gives the drugs {@link #getActiveDrugNames()}
	 *         and {@link #getActiveDrugAtcCodes()} resolve to — the aliases of those entries. Empty
	 *         unless the context came through {@link DrugReferenceService#withReferenceNames}, which
	 *         is what production's two entry points apply and what a hand-built test context does not
	 *         have; empty is equivalent to the pre-issue-#136 behaviour, never to "unknown".
	 *
	 *         <p>Held separately from {@link #getActiveDrugNames()} rather than folded into it because
	 *         the two are matched by DIFFERENT rules and must stay distinguishable: an order's display
	 *         name is a localized string scanned with {@link DrugReference#matchesOrderName}, while
	 *         these are canonical reference names compared by identity. Folding them together would
	 *         scan a rule token across a combination product's alias, which is exactly the wrong
	 *         answer — see {@link #hasActiveDrug}.
	 */
	public Set<String> getActiveDrugReferenceNames() {
		return activeDrugReferenceNames;
	}

	/**
	 * @return uppercased ATC codes mapped from the patient's active drug orders — the UNION over every
	 *         order, which is what the class-based arms and
	 *         {@link DrugReferenceService#findByActiveOrders} want (neither asks which order a code came
	 *         from). Held independently rather than derived from {@link #getActiveDrugOrders()}: a
	 *         caller may supply only the flattened sets (issue #118 deliberately kept that fallback), so
	 *         this set can hold codes no {@link ActiveDrugOrder} accounts for. In production it no
	 *         longer can: since issue #290 an order the module cannot name still reaches the per-order
	 *         list, and one with no codes contributes none here either, so the two agree. Deriving this
	 *         from the list would still drop the flattened caller's codes; one that needs to know
	 *         WHICH order a code belongs to reads {@link ActiveDrugOrder#getAtcCodes()} instead.
	 */
	public Set<String> getActiveDrugAtcCodes() {
		return activeDrugAtcCodes;
	}

	/** @return the patient's active drug orders as individually-identified orders, in the order
	 *          {@code OrderService} returned them; empty when none or when the caller supplied
	 *          only the flattened name/ATC sets. */
	public List<ActiveDrugOrder> getActiveDrugOrders() {
		return activeDrugOrders;
	}

	/** @return lowercased allergen text of the patient's active allergies — the coded allergen's name
	 *          in the current locale and the non-coded allergen, which is all
	 *          {@link PatientClinicalContextBuilder} reads (see {@link #containsToken} for why the
	 *          allergy's comment and reactions are deliberately not among them). */
	public Set<String> getAllergyTokens() {
		return allergyTokens;
	}

	/** @return lowercased text of the patient's active conditions. */
	public Set<String> getConditionTokens() {
		return conditionTokens;
	}

	/**
	 * @return true when any active-order name, any reference name of a drug those orders resolve to,
	 *         or any active-order ATC code matches the given interaction rule. Both callers — the chip
	 *         decision in {@link DrugSafetyValidator} and the prompt-promotion predicate in
	 *         {@link DrugReferenceInjector} — reach every one of those arms only through here, which is
	 *         what keeps the chips and the promoted prose agreeing about which orders a rule matches.
	 *
	 *         <p><b>The order-name arm</b> goes through {@link DrugReference#matchesOrderName} — not
	 *         bare containment, which reported drugs the patient had never taken because drug names
	 *         nest ("tiotropium" contains "opium"; issue #86), and not the prose rule either, because
	 *         an order's display name is localized and inflected rather than prose (see there).
	 *
	 *         <p><b>The reference-name arm</b> (issue #136) exists because a rule carries ONE token for
	 *         its partner while the reference data knows that drug by several names, and the chart may
	 *         use any of them: every DDInter rule about aspirin carries the token {@code aspirin}, its
	 *         entry's own name is {@code Acetylsalicylic acid}, and an order under the latter matched no
	 *         rule at all — a Major warfarin interaction silently absent. So the patient's orders are
	 *         resolved to their entries once per pass and those entries' names come in on
	 *         {@link #getActiveDrugReferenceNames()}.
	 *
	 *         <p>That arm is exact IDENTITY, deliberately not a second boundary scan, and the
	 *         difference is not cosmetic: an entry's alias list carries combination-product names, so
	 *         {@code salicylic acid / urea} is an alias of the Urea entry as well as of the Salicylic
	 *         acid one, and scanning a rule's token across those names would report a patient on urea
	 *         alone as being on salicylic acid — issue #86's fabricated drug arriving by a new route.
	 *         Identity asks the same question {@code DrugSafetyValidator.identifies} asks of the
	 *         reference side, so the two agree about when a rule names a given drug, and over the
	 *         order-NAME leg it makes this arm provably equal to resolving the rule's token to its
	 *         partner entries and matching THEIR names against the order name — the formulation issue
	 *         #136 proposed, at one dataset sweep per pass instead of one per rule. (Equal, not merely
	 *         equal in measurement: an entry supplies a name here exactly when the order name resolves
	 *         it, which is the same predicate that formulation applies to the partner. Where the two
	 *         differ is the ATC leg, which this one also reaches — an order mapped to an entry's exact
	 *         level-5 code is that substance, so the entry's names are the patient's names too.)
	 */
	boolean hasActiveDrug(String nameToken, String atcCode) {
		if (nameToken != null && !nameToken.trim().isEmpty()) {
			String n = nameToken.trim();
			for (String drug : activeDrugNames) {
				if (DrugReference.matchesOrderName(drug, n)) {
					return true;
				}
			}
			if (activeDrugReferenceNames.contains(DrugReference.normalizeName(n))) {
				return true;
			}
		}
		String normalizedAtc = DrugReference.normalizeAtcToken(atcCode);
		return normalizedAtc != null && activeDrugAtcCodes.contains(normalizedAtc);
	}

	/** @return true when any allergy token contains the given (lowercased) contraindication token — see
	 *          {@link #containsToken}, which is where the bare-containment rule and the measurement
	 *          behind it live, and {@link #allergensMatching} for WHICH tokens it matched. */
	boolean hasAllergyToken(String token) {
		return containsToken(allergyTokens, token);
	}

	/**
	 * @return WHICH recorded allergens {@link #hasAllergyToken} matched — its witnesses, in the order
	 *         the chart lists them, empty exactly when that returns false. The allergy list's
	 *         counterpart of {@link DrugReference#aliasesIn} / {@link DrugReference#aliasesNaming}, and
	 *         here for the same reason those are (issue #223): the boolean says a curated token reached
	 *         the allergy list, and it does not say by WHICH record — which is what decides whether the
	 *         rule reports the allergen arm's fact, because the token may have reached a record about a
	 *         different drug entirely ({@code opium} inside an allergen recorded as {@code Tiotropium}).
	 *         Only the record actually matched can be put to the drug.
	 *
	 *         <p>Shares {@link #containsToken}'s own primitives rather than re-expressing the scan, so
	 *         the witnesses and the boolean cannot drift — the rule CLAUDE.md states for
	 *         {@code matchesDrugName}/{@code aliasesNaming}. Read by
	 *         {@code DrugSafetyValidator.aMatchedRecordNamesTheEntry}, which pairs each witness with
	 *         {@link DrugReference#matchesDrugName}: the entry side of that question is about the
	 *         reference dataset, which this value object deliberately knows nothing about, so it is asked
	 *         there and not here. That method is read by {@code contraindicationRank} for the chip's rank
	 *         and by {@code DrugReferenceInjector.corroborated} for the injected record's reading (issue
	 *         #269), which is why it carries a name of its own.
	 */
	List<String> allergensMatching(String token) {
		if (!matchableToken(token)) {
			return Collections.emptyList();
		}
		String folded = foldedToken(token);
		List<String> out = new ArrayList<String>();
		for (String allergen : allergyTokens) {
			if (containsFolded(allergen, folded)) {
				out.add(allergen);
			}
		}
		return Collections.unmodifiableList(out);
	}

	/** @return true when any condition token contains the given (lowercased) contraindication token. */
	boolean hasConditionToken(String token) {
		return containsToken(conditionTokens, token);
	}

	/**
	 * @return whether {@code token} is something this class could match a record AGAINST at all — the
	 *         emptiness half of {@link #containsToken}, extracted so a second reader can ask it without
	 *         re-deriving it (issue #208 item 2: the injected record has to tell "the chart does not
	 *         record this" apart from "the module cannot evaluate this rule", and a blank token is the
	 *         second). Both emptiness checks live here, including the post-fold one {@link #containsToken} documents.
	 */
	static boolean matchableToken(String token) {
		return token != null && !token.trim().isEmpty() && !foldedToken(token).isEmpty();
	}

	/**
	 * Deliberately still bare containment, unlike the order-name arm above (issue #86): these
	 * haystacks are free text — an allergen name, a condition in the clinician's own wording — where a
	 * curated rule is meant to match a fragment ({@code nsaid} inside "NSAIDs", {@code peptic ulcer}
	 * inside "history of peptic ulcer disease"), so the word-start rule would silently stop matching
	 * the rules that exist. The nesting risk is the same in principle ({@code opium} against an
	 * allergen recorded as "Tiotropium") and this javadoc used to ask for its own measurement over
	 * allergy text rather than the order-name corpus that settled #86.
	 *
	 * <p><b>That measurement was made (issue #223)</b>, and it is why this stayed as it is. Over the
	 * shipped 19 MB KB's 5169 published names as the allergen corpus, of the ten rules the bundled
	 * curated file publishes, moving this match to the drug-NAME rule loses 5 real allergen names and
	 * every one is on the CLASS token {@code penicillin} ({@code benzylpenicillin},
	 * {@code phenoxymethylpenicillin}, {@code procaine benzylpenicillin} …); the three tokens that name
	 * their own entry lose nothing. So the token shapes really do want different rules, and what moved
	 * instead are the two decisions the nesting risk had made dangerous — which chip sentence a
	 * self-named rule may speak in ({@code DrugSafetyValidator.contraindicationRank}, issue #223) and
	 * whether an injected record may state its clause as the chart's own reading
	 * ({@code DrugReferenceInjector.corroborated}, issue #269). Both read
	 * {@link #allergensMatching} for the witnesses this method does not report.
	 *
	 * <p>What that corpus bounds, stated rather than implied: published reference NAMES, the shape a
	 * coded allergen carries. It is not the localized dictionary {@link PatientClinicalContextBuilder}
	 * actually reads a coded allergen's name out of (unreachable when this was measured), and not free
	 * text at all, which is what a {@code nonCodedAllergen} is. It bounds the CLASS-token loss, which is
	 * what the decision turned on; re-measure on the dictionary before reopening it.
	 *
	 * <p>Exposure today is confined to hand-authored
	 * contraindication rules: neither the {@code ddinter} nor the {@code atc} source emits any, and the
	 * allergy contraindication arm ({@code DrugSafetyValidator.addAllergyContraindications}) resolves
	 * allergens through {@link DrugReferenceService#findImpliedSubstances}, and so through
	 * {@link DrugReferenceService#lookupByToken} beneath it, which is boundary-aware.
	 * Boundary-aware is not the same as correct, though, and this is not a clean contrast: since issue
	 * #176 that resolver prefers an entry the allergen NAMES over one whose alias merely occurs inside the
	 * allergen, which settles the fragment case for every name the KB itself publishes, but an allergen
	 * recorded as free text that names no entry at all still resolves by containment or not at all.
	 * What it does rule out is this method's failure mode — a token matching mid-word.
	 *
	 * <p><b>Diacritics are folded on both sides (issue #141)</b>, through the one shared
	 * {@link DrugReference#foldDiacritics}. This was the matcher #129/#138 did not reach: that work
	 * folded {@link DrugReference#containsBoundedToken}, the order-name scan, and scoped itself there,
	 * leaving this one comparing raw code points. On the SHIPPED DEFAULT source format
	 * ({@code sourceFormat=json}) the curated Amoxicillin entry's {@code penicillin} allergy token
	 * therefore missed an allergen recorded as {@code Pénicilline G} — a real fr locale-preferred name
	 * in the 3.7.1 dictionary, and {@link PatientClinicalContextBuilder} reads the concept name in the
	 * CURRENT locale, so a francophone deployment reaches it by default. Measured 2026-08-05 over that
	 * dictionary's 1219 allergen-candidate names: 11 gained, 0 lost, all of them
	 * {@code penicillin}/{@code paracetamol} spellings. Folding is the WHOLE change — containment
	 * already tolerates the trailing {@code -e}/{@code -s} of {@code Pénicillines}, so no boundary or
	 * inflection rule comes with it, and the fragment matching above is untouched.
	 *
	 * <p><b>Not the allergen's comment or reactions.</b> This javadoc used to say the allergy haystack
	 * was "an allergen name plus its comments"; it never was —
	 * {@link PatientClinicalContextBuilder} reads {@code codedAllergen}'s name and
	 * {@code nonCodedAllergen} and never calls {@code Allergy.getComment()} or
	 * {@code getReactions()}. Reading the comment is not a free widening either, which is why the claim
	 * was corrected rather than made true: a comment can NEGATE ("tolerated penicillin, no reaction"),
	 * and the same strings feed {@code lookupByToken}, so a comment would be read as the allergen
	 * itself and fabricate an allergy. A reaction is a symptom, not a drug. The fragment rationale
	 * above survives on {@code nonCodedAllergen}, which is genuinely free text.
	 *
	 * <p><b>Package-private, and taking a {@link Collection} rather than a {@code Set}</b> since the
	 * subject-matter scoping of the active-order contraindication arm: that gate asks whether the
	 * finding a rule FIRED ON is part of what the response is about, and it must ask it with the same
	 * matcher the firing used, or "did not match" and "is not what was asked about" drift apart. One
	 * definition — never a second copy here. That caller's haystack is PROSE rather than recorded
	 * values, and lower-cased on the way in, because {@link #containsFolded} folds a value but does not
	 * case-fold it.
	 */
	static boolean containsToken(Collection<String> haystack, String token) {
		if (!matchableToken(token)) {
			// A token of nothing but combining marks folds to empty AFTER the fold, not before — and the
			// empty string is contained in everything, so both emptiness checks live in matchableToken.
			return false;
		}
		String folded = foldedToken(token);
		for (String value : haystack) {
			if (containsFolded(value, folded)) {
				return true;
			}
		}
		return false;
	}

	/** The needle {@link #containsToken} scans for, folded once — shared with
	 *  {@link #allergensMatching} so the boolean and its witnesses fold alike, and with
	 *  {@link #matchableToken}, whose whole subject is whether this expression comes out empty. */
	private static String foldedToken(String token) {
		return DrugReference.foldDiacritics(token.trim().toLowerCase(Locale.ROOT));
	}

	/** The one comparison behind both {@link #containsToken} and {@link #allergensMatching}: a recorded
	 *  value contains an already-folded token. Extracted rather than written twice, so the witnesses
	 *  cannot come to disagree with the boolean about what matched. */
	private static boolean containsFolded(String value, String foldedToken) {
		return DrugReference.foldDiacritics(value).contains(foldedToken);
	}

	/**
	 * One of the patient's active drug orders, identified well enough to be reconciled against the
	 * serialized chart and, when the chart cannot substantiate it, rendered as a citable record:
	 * the {@code Order} uuid (the identity querystore indexes its {@code drug_order} document
	 * under, so the two reads can be matched exactly), the display name, and every name that
	 * identifies the order in record text.
	 *
	 * <p>Also carries the ATC codes the order's own concept maps to, so a code can be attributed back
	 * to the order that contributed it (issue #132). Without that, {@link #getActiveDrugAtcCodes()} is
	 * the only place codes live and "one order carrying two codes" is indistinguishable from "two
	 * orders each carrying one" — which let a single order witness an interaction between the two
	 * reference entries its own codes resolve to (see
	 * {@code DrugSafetyValidator.activeOrdersOtherThan}). Kept as an association rather than gathered
	 * separately: {@link PatientClinicalContextBuilder} already reads these codes off the order's
	 * concept in the same single {@code getActiveOrders} pass that fills the names.
	 *
	 * <p>Deliberately carries no dosing. The whole point of the reconciliation is that this module
	 * must not hold one fact and present it two ways, and a second dose-rendering path beside
	 * querystore's is exactly that. The display name already carries the strength in real data
	 * ("Simvastatin Co 20mg"), and the citation resolves to the order itself for the rest.
	 *
	 * <p><b>One class of order is identified LESS well than the above, deliberately</b> (issue #290):
	 * {@link #namedByCodesOnly} stands in for an order no name could be read for, so its display is a
	 * list of its own ATC codes and it has no names at all. Of the identity described above it carries
	 * only the uuid: no name identifies it in record text, so the reconciliation can match it by uuid
	 * alone, and its display carries no strength because it is not a drug name. Ask
	 * {@link #hasKnownName()} before letting the display DISPLACE a name some other source supplied;
	 * labelling something that has no other name is what the display is for. It is still far
	 * better than the alternative it replaced: such an order used to be omitted entirely while its codes
	 * still reached {@link #getActiveDrugAtcCodes()}, which made one prescription look like one partner
	 * per code the loaded dataset could not name — a covered code is keyed on its substance either way,
	 * so that half is unchanged and deliberate.
	 */
	public static final class ActiveDrugOrder {

		private final String uuid;

		private final String display;

		private final Set<String> names;

		private final Set<String> atcCodes;

		/** Whether {@link #display} is a name for this order, as opposed to a stand-in the module
		 *  synthesized because it could not read one — see {@link #namedByCodesOnly}. Asked, rather
		 *  than inferred from {@link #names} being empty, because that is a PROXY: a caller may hand
		 *  this a real display with no match tokens, and the answer decides whether a real drug name
		 *  gets displaced on a chip (issue #290, {@code OrderPartner.nameByOrder}). */
		private final boolean nameKnown;

		/** An order whose concept carries no ATC map — the majority in practice: only 85 of the 616
		 *  Drug-class concepts in the 3.7.1 reference demo dictionary carry one (measured 2026-08-04,
		 *  the same count {@code DrugReferenceService.findForActiveOrders} cites) — so the code-carrying
		 *  form is not the only legitimate one. Equivalent to no codes, never to "unknown codes". */
		public ActiveDrugOrder(String uuid, String display, Set<String> names) {
			this(uuid, display, names, null);
		}

		public ActiveDrugOrder(String uuid, String display, Set<String> names, Set<String> atcCodes) {
			this(uuid, display, names, atcCodes, true);
		}

		/**
		 * An order the module could not read a name for, standing in with its ATC codes (issue #290).
		 *
		 * <p>Its {@link #getNames()} is empty on purpose — that set is matched against chart prose, and
		 * a code in it would match free text — so this order cannot be found by name anywhere, and the
		 * #118 reconciliation substantiates it by uuid alone. A separate factory rather than a fifth
		 * argument on the public constructor, so that no existing caller can produce this state by
		 * accident and the one place that does is named.
		 */
		static ActiveDrugOrder namedByCodesOnly(String uuid, String display, Set<String> atcCodes) {
			return new ActiveDrugOrder(uuid, display, null, atcCodes, false);
		}

		private ActiveDrugOrder(String uuid, String display, Set<String> names, Set<String> atcCodes,
				boolean nameKnown) {
			this.nameKnown = nameKnown;
			this.uuid = uuid;
			this.display = display;
			this.names = lower(names);
			// Through the outer class's own normalizer, so a per-order code and the same code in the
			// flattened set are the same string — otherwise attributing one to the other silently
			// stops matching.
			this.atcCodes = upper(atcCodes);
		}

		/** @return the {@code Order} uuid, or {@code null} when unknown. */
		public String getUuid() {
			return uuid;
		}

		/** @return the order's display name, as a record renders it — or, since issue #290, the code-only
		 *          stand-in {@link #namedByCodesOnly} builds for an order no name could be read for, which
		 *          is not a name at all. Ask {@link #hasKnownName()} before treating it as one. */
		public String getDisplay() {
			return display;
		}

		/** @return true when {@link #getDisplay()} is a name this order actually carries, false when it
		 *          is the code-only stand-in {@link #namedByCodesOnly} builds. Ask this before letting
		 *          the display DISPLACE a name another source supplied — that is the one decision it
		 *          exists for ({@code DrugSafetyValidator.OrderPartner.nameByOrder}). Using the display
		 *          to label something that has no other name does NOT need this test, and the chip's
		 *          own "as active order &lt;label&gt;" is that case. */
		public boolean hasKnownName() {
			return nameKnown;
		}

		/** @return lowercased names identifying this order — the coded {@code Drug}'s name, the free
		 *          text a clinician typed for a non-coded order ({@code drugNonCoded}, issue #293),
		 *          and the order concept's name, in that rank; {@link #getDisplay()} is the first of
		 *          them. Empty on the code-only rung {@link #namedByCodesOnly} builds, and possibly
		 *          empty on a caller-built order carrying a real display and no match tokens — ask
		 *          {@link #hasKnownName()} to tell the two apart, never {@code getNames().isEmpty()},
		 *          which is the proxy
		 *          {@code NamelessActiveOrderPartnerTest.aRealDisplayWithNoMatchTokensStillOutranksTheDatasetName}
		 *          exists to rule out. */
		public Set<String> getNames() {
			return names;
		}

		/** @return the uppercased ATC codes THIS order's concept maps to; empty when it maps to none.
		 *          The per-order half of {@link PatientClinicalContext#getActiveDrugAtcCodes()}, which
		 *          stays the union every class arm and {@link DrugReferenceService#findByActiveOrders}
		 *          legitimately wants. */
		public Set<String> getAtcCodes() {
			return atcCodes;
		}

		/**
		 * @return true when {@code lowercasedText} names this order — the fallback for matching an
		 *         order against chart record text when the uuids do not line up. Matched on word
		 *         boundaries, not by plain containment: a short order name ({@code ASA}) otherwise
		 *         reads as named by an unrelated word ({@code Nasal}), which silently suppresses both
		 *         the injection and the WARN and so hides the discrepancy instead of leaving it
		 *         unrepaired.
		 *
		 *         <p>Uses {@link DrugReference#containsWord} (symmetric boundaries) rather than its
		 *         sibling {@code matchesOrderName} (left boundary plus up to two trailing inflection
		 *         letters), because the roles here are the other way round from that one's: it asks
		 *         whether a rule token names the drug in a single order DISPLAY name, whereas this
		 *         asks whether an order name appears in querystore's rendered record PROSE — and
		 *         prose is what the symmetric rule is for. The tail tolerance also runs the wrong way
		 *         for this check: leniency here means deciding an order IS substantiated, which
		 *         suppresses the repair and the WARN together, so the stricter rule is the safe
		 *         direction. See {@code matchesOrderName}'s javadoc for why one matcher cannot serve
		 *         both.
		 *
		 *         <p><b>Both sides in ONE normal form (issue #293).</b> These names had their whitespace
		 *         runs collapsed on the way in ({@code PatientClinicalContextBuilder.addRaw}), and the
		 *         haystack is querystore's verbatim record prose, which renders a recorded value as it
		 *         was typed — so a name a clinician spaced irregularly would otherwise fail to be found
		 *         inside the record that renders that very value, and the order would be reported
		 *         unrepresented against a chart that plainly carries it. Measured: the name
		 *         {@code "warfarin  5mg"} collapses to {@code "warfarin 5mg"} and is not found in
		 *         {@code "drug order: warfarin  5mg, 1 tablet daily"} without this. Collapsed here rather
		 *         than inside {@link DrugReference#containsWord}, which several arms share and whose
		 *         haystacks are not all prose. One pass over the drug-order text per active order; the
		 *         operation is idempotent, so a caller that has already normalized loses nothing.
		 */
		boolean namedIn(String lowercasedText) {
			if (lowercasedText == null || lowercasedText.isEmpty()) {
				return false;
			}
			String haystack = COLLAPSE_WHITESPACE.matcher(lowercasedText).replaceAll(" ");
			for (String name : names) {
				if (DrugReference.containsWord(haystack, name)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public String toString() {
			return display + " [" + uuid + "]";
		}
	}
}
