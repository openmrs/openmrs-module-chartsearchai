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
 * order-driven injection), the active drug orders themselves (for reconciling the
 * safety layer's read against the serialized chart — see
 * {@link DrugReferenceInjector#unrepresentedActiveOrders}), and lowercased text tokens
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
	 * list: they are what every interaction and contraindication check reads, and a caller that
	 * supplies only them keeps exactly the pre-reconciliation behaviour (nothing to reconcile).
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

	/** @return uppercased ATC codes mapped from the patient's active drug orders. */
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
	 * {@code ddinter} nor the {@code atc} source emits any, and the class-based allergy arm resolves
	 * allergens through {@link DrugReferenceService#lookupByToken}, which is already boundary-aware.
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
	 * <p>Deliberately carries no dosing. The whole point of the reconciliation is that this module
	 * must not hold one fact and present it two ways, and a second dose-rendering path beside
	 * querystore's is exactly that. The display name already carries the strength in real data
	 * ("Simvastatin Co 20mg"), and the citation resolves to the order itself for the rest.
	 */
	public static final class ActiveDrugOrder {

		private final String uuid;

		private final String display;

		private final Set<String> names;

		public ActiveDrugOrder(String uuid, String display, Set<String> names) {
			this.uuid = uuid;
			this.display = display;
			this.names = lower(names);
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

		/** @return true when {@code lowercasedText} names this order — the fallback for matching an
		 *          order against chart record text when the uuids do not line up. */
		boolean namedIn(String lowercasedText) {
			if (lowercasedText == null || lowercasedText.isEmpty()) {
				return false;
			}
			for (String name : names) {
				if (lowercasedText.contains(name)) {
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
