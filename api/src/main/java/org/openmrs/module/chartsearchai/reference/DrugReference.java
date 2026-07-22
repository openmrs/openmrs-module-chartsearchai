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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single clinical drug-reference entry loaded from {@code drug-reference.json}.
 * Reference data — <em>not</em> patient data: it describes what a chart record
 * <em>should</em> look like (dosing, interactions, contraindications) so the LLM
 * can cite reference facts the same way it cites chart records, and so the
 * post-answer {@link DrugSafetyValidator} has a deterministic table to check
 * against.
 *
 * <p>Matching keys:
 * <ul>
 *   <li>{@link #getAliases()} — lowercase free-text names for question-driven matching.</li>
 *   <li>{@link #getAtcCodes()} — ATC codes for order-driven matching against active orders.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DrugReference {

	private String id;

	private String name;

	/** Diverging everyday generic name, or null — see {@link #getGenericName()}. */
	private String genericName;

	private String drugClass;

	private List<String> aliases = Collections.emptyList();

	private List<String> atcCodes = Collections.emptyList();

	private List<AgeBand> ageBands = Collections.emptyList();

	private List<String> warnings = Collections.emptyList();

	private List<Interaction> interactions = Collections.emptyList();

	private List<Contraindication> contraindications = Collections.emptyList();

	private String source;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	/** The everyday generic name (e.g. RxNorm's {@code aspirin}) when it genuinely diverges
	 *  from {@link #getName()} (e.g. {@code Acetylsalicylic acid}), else {@code null}. Set by
	 *  sources whose display vocabulary can differ from the chart's; consumed by
	 *  {@link #displayLabel()}. */
	public String getGenericName() {
		return genericName;
	}

	public void setGenericName(String genericName) {
		this.genericName = genericName;
	}

	/**
	 * The clinician-facing label for safety chips: the display name, with the diverging generic
	 * appended as a synonym — {@code "Acetylsalicylic acid (aspirin)"} — so a warning is
	 * recognizable against both the dataset's vocabulary and the question/chart's. The synonym
	 * renders only when the two genuinely diverge (neither contains the other, case-insensitive):
	 * route variants like {@code Lidocaine (topical)} and redundancy like
	 * {@code Kava (kava preparation)} render unchanged — the check lives here, not only in the
	 * ddinter parser, because a curated json file can bind {@code genericName} directly. Never
	 * used in prompt text — record rendering keeps {@link #getName()} — so this is a
	 * chip-display concern only.
	 */
	public String displayLabel() {
		if (genericName == null || genericName.isEmpty() || name == null) {
			return name;
		}
		String n = name.toLowerCase(Locale.ROOT);
		String g = genericName.toLowerCase(Locale.ROOT);
		if (n.contains(g) || g.contains(n)) {
			return name;
		}
		return name + " (" + genericName + ")";
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDrugClass() {
		return drugClass;
	}

	public void setDrugClass(String drugClass) {
		this.drugClass = drugClass;
	}

	public List<String> getAliases() {
		return aliases;
	}

	public void setAliases(List<String> aliases) {
		this.aliases = aliases != null ? aliases : Collections.<String> emptyList();
	}

	public List<String> getAtcCodes() {
		return atcCodes;
	}

	public void setAtcCodes(List<String> atcCodes) {
		this.atcCodes = atcCodes != null ? atcCodes : Collections.<String> emptyList();
	}

	/**
	 * @return this entry's ATC codes trimmed, upper-cased ({@link Locale#ROOT}) and de-duplicated,
	 *         with blank/null entries dropped — the canonical normalisation for comparing ATC codes.
	 *         Shared by the order-driven matcher ({@link DrugReferenceService#findByActiveOrders}) and
	 *         the class-based safety checks ({@link DrugSafetyValidator}) so both decide "same ATC
	 *         code" identically; like {@link #formatNumber} this keeps one rule in one place.
	 */
	public Set<String> normalizedAtcCodes() {
		return normalizeAtcTokens(atcCodes);
	}

	/**
	 * The one normalisation for ATC tokens — entry codes here, group prefixes in
	 * {@link CrossReactivityGroup#normalizedAtcPrefixes()}, and the patient's active-order codes in
	 * {@link PatientClinicalContext}: trim, upper-case ({@link Locale#ROOT}), drop null/blank,
	 * de-duplicate. One shared definition so every ATC comparison compares like with like; if two
	 * sides normalized differently, class and cross-reactivity matching would silently stop matching.
	 */
	static Set<String> normalizeAtcTokens(Collection<String> tokens) {
		Set<String> out = new LinkedHashSet<String>();
		for (String token : tokens) {
			String normalized = normalizeAtcToken(token);
			if (normalized != null) {
				out.add(normalized);
			}
		}
		return out;
	}

	/** Single-token counterpart of {@link #normalizeAtcTokens}: the trimmed, upper-cased
	 *  ({@link Locale#ROOT}) form of {@code token}, or {@code null} when blank. */
	static String normalizeAtcToken(String token) {
		return token == null || token.trim().isEmpty() ? null : token.trim().toUpperCase(Locale.ROOT);
	}

	/** An ATC level-4 (chemical subgroup) code is the {@value #ATC_SUBGROUP_PREFIX_LENGTH}-character
	 *  prefix of a level-5 substance code ({@code M01AE01} -> {@code M01AE}). Two drugs sharing a
	 *  subgroup are structurally related (ibuprofen/naproxen, both {@code M01AE}). */
	public static final int ATC_SUBGROUP_PREFIX_LENGTH = 5;

	/**
	 * @return this entry's ATC level-4 chemical subgroups — the {@link #ATC_SUBGROUP_PREFIX_LENGTH}-char
	 *         prefixes of its {@link #normalizedAtcCodes()} (codes shorter than that contribute none).
	 *         Two entries are in the same ATC class iff their subgroup sets intersect. This is the one
	 *         shared definition used by both the order-relevance scoping ({@code DrugReferenceInjector})
	 *         and the class-based safety checks ({@code DrugSafetyValidator}).
	 */
	public Set<String> atcSubgroups() {
		Set<String> out = new LinkedHashSet<String>();
		for (String code : normalizedAtcCodes()) {
			if (code.length() >= ATC_SUBGROUP_PREFIX_LENGTH) {
				out.add(code.substring(0, ATC_SUBGROUP_PREFIX_LENGTH));
			}
		}
		return out;
	}

	public List<AgeBand> getAgeBands() {
		return ageBands;
	}

	public void setAgeBands(List<AgeBand> ageBands) {
		this.ageBands = ageBands != null ? ageBands : Collections.<AgeBand> emptyList();
	}

	/**
	 * @return free-text prose warnings (e.g. a Reye-syndrome caution) rendered verbatim into the
	 *         injected, citable reference record for the LLM to ground on. Display-only: they carry
	 *         no matchable token, so the deterministic validator never fires on them — enforceable
	 *         facts belong in {@link #getContraindications()}/{@link #getInteractions()}/age bands.
	 */
	public List<String> getWarnings() {
		return warnings;
	}

	public void setWarnings(List<String> warnings) {
		this.warnings = warnings != null ? warnings : Collections.<String> emptyList();
	}

	public List<Interaction> getInteractions() {
		return interactions;
	}

	public void setInteractions(List<Interaction> interactions) {
		this.interactions = interactions != null ? interactions : Collections.<Interaction> emptyList();
	}

	public List<Contraindication> getContraindications() {
		return contraindications;
	}

	public void setContraindications(List<Contraindication> contraindications) {
		this.contraindications = contraindications != null
				? contraindications : Collections.<Contraindication> emptyList();
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	/**
	 * @return the age band whose {@code [minYears, maxYears]} range contains
	 *         {@code ageYears}, or {@code null} when no band matches (e.g. age
	 *         unknown, or an adult age that this pediatric-focused dataset does
	 *         not cover). Age-gating is what stops a pediatric dose being
	 *         surfaced for an adult query.
	 */
	public AgeBand bandForAge(Integer ageYears) {
		if (ageYears == null) {
			return null;
		}
		for (AgeBand band : ageBands) {
			if (ageYears >= band.getMinYears() && ageYears <= band.getMaxYears()) {
				return band;
			}
		}
		return null;
	}

	/**
	 * @return true when any alias equals or is a whole-word token of the given
	 *         lowercased text. Whole-word so "advil" matches "is advil safe?"
	 *         but "amox" does not spuriously match unrelated prose.
	 */
	public boolean matchesText(String lowerText) {
		if (lowerText == null) {
			return false;
		}
		for (String alias : aliases) {
			if (containsWord(lowerText, alias)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return true when {@code word} occurs in {@code text} as a <em>whole word</em> — bounded on
	 *         each side by a non-alphanumeric character or the string edge. Whole-word, not
	 *         substring, so a drug name nested inside a longer one does not spuriously match
	 *         ("chlorothiazide" is not a whole word in "hydrochlorothiazide"), while a real token
	 *         still matches ("aspirin" in "Aspirin 81 mg"). Case-insensitive; a null/blank word
	 *         never matches. This is the one matcher shared by {@link #matchesText}
	 *         (alias-in-question) and {@link PatientClinicalContext#hasActiveDrug}
	 *         (token-in-order-name), so the two cannot drift.
	 */
	static boolean containsWord(String text, String word) {
		if (text == null || word == null || word.isEmpty()) {
			return false;
		}
		String t = text.toLowerCase(Locale.ROOT);
		String w = word.toLowerCase(Locale.ROOT);
		int idx = t.indexOf(w);
		while (idx >= 0) {
			boolean leftOk = idx == 0 || !Character.isLetterOrDigit(t.charAt(idx - 1));
			int end = idx + w.length();
			boolean rightOk = end >= t.length() || !Character.isLetterOrDigit(t.charAt(end));
			if (leftOk && rightOk) {
				return true;
			}
			idx = t.indexOf(w, idx + 1);
		}
		return false;
	}

	/**
	 * Formats a dose number for display, dropping a redundant trailing {@code .0} so an integral
	 * dose renders as "400" not "400.0". Shared by the reference renderer
	 * ({@link DrugReferenceInjector}) and the safety validator ({@link DrugSafetyValidator}) so both
	 * print doses identically.
	 */
	static String formatNumber(double value) {
		if (value == Math.floor(value) && !Double.isInfinite(value)) {
			return Long.toString((long) value);
		}
		return Double.toString(value);
	}

	/**
	 * An age-banded dosing rule. {@code maxDailyDoseMg} of 0 means "no published daily maximum
	 * for this band" — never "unlimited": the renderer omits the daily figure (and says so), the
	 * validator's daily arm stays silent for the band, and the weight-aware per-dose arm still
	 * runs when {@code mgPerKgMax} is set and a fresh weight is on record.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AgeBand {

		private int minYears;

		private int maxYears;

		private double mgPerKgMin;

		private double mgPerKgMax;

		private double maxDailyDoseMg;

		public int getMinYears() {
			return minYears;
		}

		public void setMinYears(int minYears) {
			this.minYears = minYears;
		}

		public int getMaxYears() {
			return maxYears;
		}

		public void setMaxYears(int maxYears) {
			this.maxYears = maxYears;
		}

		public double getMgPerKgMin() {
			return mgPerKgMin;
		}

		public void setMgPerKgMin(double mgPerKgMin) {
			this.mgPerKgMin = mgPerKgMin;
		}

		public double getMgPerKgMax() {
			return mgPerKgMax;
		}

		public void setMgPerKgMax(double mgPerKgMax) {
			this.mgPerKgMax = mgPerKgMax;
		}

		public double getMaxDailyDoseMg() {
			return maxDailyDoseMg;
		}

		public void setMaxDailyDoseMg(double maxDailyDoseMg) {
			this.maxDailyDoseMg = maxDailyDoseMg;
		}
	}

	/** A drug-drug interaction rule: this drug interacts with another identified by name token or ATC. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Interaction {

		private String token;

		private String atc;

		private String note;

		/** Source-assigned severity ({@code Major}/{@code Moderate}/{@code Minor}/{@code Unknown}
		 *  for DDInter rows), or {@code null} for sources that don't rate rules (the curated
		 *  seed) — a null severity is exempt from the validator's severity floor. */
		private String severity;

		public String getToken() {
			return token;
		}

		public void setToken(String token) {
			this.token = token;
		}

		public String getAtc() {
			return atc;
		}

		public void setAtc(String atc) {
			this.atc = atc;
		}

		public String getNote() {
			return note;
		}

		public void setNote(String note) {
			this.note = note;
		}

		public String getSeverity() {
			return severity;
		}

		public void setSeverity(String severity) {
			this.severity = severity;
		}
	}

	/** A contraindication rule keyed by patient allergy or condition text token. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Contraindication {

		/** "allergy" or "condition" — which patient data this rule cross-checks. */
		private String type;

		private String token;

		private String note;

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public String getToken() {
			return token;
		}

		public void setToken(String token) {
			this.token = token;
		}

		public String getNote() {
			return note;
		}

		public void setNote(String note) {
			this.note = note;
		}
	}
}
