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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The slice of a patient's clinical state the drug-reference feature needs:
 * age (for dose-band selection and age-gated injection), the names/ATC codes of
 * active drug orders (for interaction checks and order-driven injection), and
 * lowercased text tokens from active allergies and conditions (for
 * contraindication checks).
 *
 * <p>This is a pure value object so the injector and validator can be unit-tested
 * with hand-built contexts, while production builds one from a {@code Patient} via
 * {@link PatientClinicalContextBuilder}. Keeping the OpenMRS-{@code Context} reads in
 * a separate builder is what lets the matching/validation logic run without a
 * live OpenMRS context.
 */
public class PatientClinicalContext {

	private final Integer ageYears;

	private final Set<String> activeDrugNames;

	private final Set<String> activeDrugAtcCodes;

	private final Set<String> allergyTokens;

	private final Set<String> conditionTokens;

	public PatientClinicalContext(Integer ageYears, Set<String> activeDrugNames,
			Set<String> activeDrugAtcCodes, Set<String> allergyTokens, Set<String> conditionTokens) {
		this.ageYears = ageYears;
		this.activeDrugNames = lower(activeDrugNames);
		this.activeDrugAtcCodes = upper(activeDrugAtcCodes);
		this.allergyTokens = lower(allergyTokens);
		this.conditionTokens = lower(conditionTokens);
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
		if (in == null) {
			return Collections.emptySet();
		}
		Set<String> out = new LinkedHashSet<String>();
		for (String s : in) {
			if (s != null && !s.trim().isEmpty()) {
				out.add(s.trim().toUpperCase(Locale.ROOT));
			}
		}
		return Collections.unmodifiableSet(out);
	}

	/** @return the patient's age in years, or {@code null} when unknown. */
	public Integer getAgeYears() {
		return ageYears;
	}

	/** @return lowercased display names of the patient's active drug orders. */
	public Set<String> getActiveDrugNames() {
		return activeDrugNames;
	}

	/** @return uppercased ATC codes mapped from the patient's active drug orders. */
	public Set<String> getActiveDrugAtcCodes() {
		return activeDrugAtcCodes;
	}

	/** @return lowercased text of the patient's active allergies (allergen names/comments). */
	public Set<String> getAllergyTokens() {
		return allergyTokens;
	}

	/** @return lowercased text of the patient's active conditions. */
	public Set<String> getConditionTokens() {
		return conditionTokens;
	}

	/** @return true when any active-order name or ATC code matches the given interaction rule. */
	boolean hasActiveDrug(String nameToken, String atcCode) {
		if (nameToken != null && !nameToken.trim().isEmpty()) {
			String n = nameToken.trim().toLowerCase(Locale.ROOT);
			for (String drug : activeDrugNames) {
				if (drug.contains(n)) {
					return true;
				}
			}
		}
		if (atcCode != null && !atcCode.trim().isEmpty()
				&& activeDrugAtcCodes.contains(atcCode.trim().toUpperCase(Locale.ROOT))) {
			return true;
		}
		return false;
	}

	/** @return true when any allergy token contains the given (lowercased) contraindication token. */
	boolean hasAllergyToken(String token) {
		return containsToken(allergyTokens, token);
	}

	/** @return true when any condition token contains the given (lowercased) contraindication token. */
	boolean hasConditionToken(String token) {
		return containsToken(conditionTokens, token);
	}

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
}
