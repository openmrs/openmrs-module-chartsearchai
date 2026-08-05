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

/**
 * The slice of a patient's clinical state the drug-reference feature needs:
 * age (for dose-band selection and age-gated injection), weight in kg (for the
 * weight-aware per-dose overdose check; {@code null} = unknown, check skipped),
 * the names/ATC codes of active drug orders (for interaction checks and
 * order-driven injection), the active drug orders themselves — names and codes attributed to the
 * order they came from, both for reconciling the safety layer's read against the serialized chart
 * (see {@link DrugReferenceInjector#unrepresentedActiveOrders}) and so the interaction screen can
 * exclude a subject's own order from witnessing it — and lowercased text tokens
 * from active allergies and conditions (for contraindication checks).
 *
 * <p>This is a pure value object so the injector and validator can be unit-tested
 * with hand-built contexts, while production builds one from a {@code Patient} via
 * {@link PatientClinicalContextBuilder}. Keeping the OpenMRS-{@code Context} reads in
 * a separate builder is what lets the matching/validation logic run without a
 * live OpenMRS context.
 */
public class PatientClinicalContext {

	private final Integer ageYears;

	private final Double weightKg;

	private final Set<String> activeDrugNames;

	private final Set<String> activeDrugAtcCodes;

	private final Set<String> allergyTokens;

	private final Set<String> conditionTokens;

	private final List<ActiveDrugOrder> activeDrugOrders;

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
		this.ageYears = ageYears;
		this.weightKg = weightKg;
		this.activeDrugNames = lower(activeDrugNames);
		this.activeDrugAtcCodes = upper(activeDrugAtcCodes);
		this.allergyTokens = lower(allergyTokens);
		this.conditionTokens = lower(conditionTokens);
		this.activeDrugOrders = activeDrugOrders == null ? Collections.<ActiveDrugOrder> emptyList()
				: Collections.unmodifiableList(new ArrayList<ActiveDrugOrder>(activeDrugOrders));
	}

	/** Pre-weight constructor, retained for test convenience (production uses the weight-carrying
	 *  form): equivalent to an unknown weight. */
	public PatientClinicalContext(Integer ageYears, Set<String> activeDrugNames,
			Set<String> activeDrugAtcCodes, Set<String> allergyTokens, Set<String> conditionTokens) {
		this(ageYears, null, activeDrugNames, activeDrugAtcCodes, allergyTokens, conditionTokens);
	}

	private static Set<String> lower(Set<String> in) {
		if (in == null) {
			return Collections.emptySet();
		}
		Set<String> out = new LinkedHashSet<String>();
		for (String s : in) {
			if (s != null && !s.trim().isEmpty()) {
				out.add(s.trim().toLowerCase(Locale.ROOT));
			}
		}
		return Collections.unmodifiableSet(out);
	}

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

	/** @return lowercased display names of the patient's active drug orders. */
	public Set<String> getActiveDrugNames() {
		return activeDrugNames;
	}

	/**
	 * @return uppercased ATC codes mapped from the patient's active drug orders — the UNION over every
	 *         order, which is what the class-based arms and
	 *         {@link DrugReferenceService#findByActiveOrders} want (neither asks which order a code came
	 *         from). Held independently rather than derived from {@link #getActiveDrugOrders()}: a
	 *         caller may supply only the flattened sets (issue #118 deliberately kept that fallback),
	 *         and even in production the per-order list can be narrower than this set — an order with no
	 *         readable name is skipped there while its codes still land here (the builder's KNOWN GAP).
	 *         Deriving this from the list would silently drop those codes; a caller that needs to know
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

	/** @return lowercased text of the patient's active allergies (allergen names/comments). */
	public Set<String> getAllergyTokens() {
		return allergyTokens;
	}

	/** @return lowercased text of the patient's active conditions. */
	public Set<String> getConditionTokens() {
		return conditionTokens;
	}

	/**
	 * @return true when any active-order name or ATC code matches the given interaction rule. The
	 *         name arm goes through {@link DrugReference#matchesOrderName} — not bare containment,
	 *         which reported drugs the patient had never taken because drug names nest ("tiotropium"
	 *         contains "opium"; issue #86), and not the prose rule either, because an order's display
	 *         name is localized and inflected rather than prose (see there). Both callers — the chip
	 *         decision in {@link DrugSafetyValidator} and the prompt-promotion predicate in
	 *         {@link DrugReferenceInjector} — reach that rule only through here, which is what keeps
	 *         the chips and the promoted prose agreeing about which orders a rule matches.
	 */
	boolean hasActiveDrug(String nameToken, String atcCode) {
		if (nameToken != null && !nameToken.trim().isEmpty()) {
			String n = nameToken.trim();
			for (String drug : activeDrugNames) {
				if (DrugReference.matchesOrderName(drug, n)) {
					return true;
				}
			}
		}
		String normalizedAtc = DrugReference.normalizeAtcToken(atcCode);
		return normalizedAtc != null && activeDrugAtcCodes.contains(normalizedAtc);
	}

	/** @return true when any allergy token contains the given (lowercased) contraindication token. */
	boolean hasAllergyToken(String token) {
		return containsToken(allergyTokens, token);
	}

	/** @return true when any condition token contains the given (lowercased) contraindication token. */
	boolean hasConditionToken(String token) {
		return containsToken(conditionTokens, token);
	}

	/**
	 * Deliberately still bare containment, unlike the order-name arm above (issue #86): these
	 * haystacks are free text — an allergen name plus its comments, a condition in the clinician's
	 * own wording — where a curated rule is meant to match a fragment ({@code nsaid} inside "NSAID
	 * class reaction", {@code peptic ulcer} inside "history of peptic ulcer disease"), so the
	 * word-start rule would silently stop matching the rules that exist. The nesting risk is the
	 * same in principle ({@code opium} against an allergen recorded as "Tiotropium") and wants its
	 * own measurement over real allergy and condition text, not the order-name corpus that settled
	 * #86. Exposure today is confined to hand-authored contraindication rules: neither the
	 * {@code ddinter} nor the {@code atc} source emits any, and the allergy contraindication arm
	 * ({@code DrugSafetyValidator.addAllergyContraindications}) resolves allergens through
	 * {@link DrugReferenceService#lookupByToken}, which is boundary-aware. Boundary-aware is not the
	 * same as correct, though, and this is not a clean contrast: that resolver takes the FIRST entry
	 * whose alias occurs as a whole word, so a multi-word allergen still mis-resolves to a shorter
	 * entry sharing one of its words (measured, and reported separately). What it does rule out is
	 * this method's failure mode — a token matching mid-word.
	 */
	private static boolean containsToken(Set<String> haystack, String token) {
		if (token == null || token.trim().isEmpty()) {
			return false;
		}
		String t = token.trim().toLowerCase(Locale.ROOT);
		for (String value : haystack) {
			if (value.contains(t)) {
				return true;
			}
		}
		return false;
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
	 */
	public static final class ActiveDrugOrder {

		private final String uuid;

		private final String display;

		private final Set<String> names;

		private final Set<String> atcCodes;

		/** An order whose concept carries no ATC map — the majority in practice: only 85 of the 616
		 *  Drug-class concepts in the 3.7.1 reference demo dictionary carry one (measured 2026-08-04,
		 *  the same count {@code DrugSafetyValidator.activeOrderEntries} cites) — so the code-carrying
		 *  form is not the only legitimate one. Equivalent to no codes, never to "unknown codes". */
		public ActiveDrugOrder(String uuid, String display, Set<String> names) {
			this(uuid, display, names, null);
		}

		public ActiveDrugOrder(String uuid, String display, Set<String> names, Set<String> atcCodes) {
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

		/** @return the order's display name, as a record renders it. */
		public String getDisplay() {
			return display;
		}

		/** @return lowercased names identifying this order (drug name and/or concept name). */
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
		 */
		boolean namedIn(String lowercasedText) {
			if (lowercasedText == null || lowercasedText.isEmpty()) {
				return false;
			}
			for (String name : names) {
				if (DrugReference.containsWord(lowercasedText, name)) {
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
