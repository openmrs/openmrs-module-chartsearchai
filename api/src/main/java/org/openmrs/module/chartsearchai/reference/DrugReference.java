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
	 *         never matches. Backs {@link #matchesText} (alias-in-prose); the active-order
	 *         counterpart is {@link #matchesOrderName}, which shares this rule's left boundary but
	 *         not its right one — see there for why one matcher cannot serve both.
	 */
	static boolean containsWord(String text, String word) {
		return containsBoundedToken(text, word, 0);
	}

	/**
	 * How many trailing letters an active-order display name may carry past a matched drug token
	 * before the token stops naming that drug. Two: a localized drug name suffixes the INN stem with
	 * one inflectional ending — Romance singulars ({@code Aspirine}, {@code Aspirina},
	 * {@code Ondansetrona}) and their plurals ({@code Multivitamines}) — while a longer tail is a
	 * different substance ({@code Heparinoids}, {@code Multi-Vitamin Adult}). See
	 * {@link #matchesOrderName} for the measurement behind the number.
	 */
	private static final int MAX_ORDER_NAME_INFLECTION_LETTERS = 2;

	/**
	 * Order-name matching: whether {@code token} — an interaction rule's drug token — names the drug
	 * in a patient's active drug ORDER, whose {@code orderName} is one display name rather than
	 * prose.
	 *
	 * <p>A bare containment test here reports drugs the patient has never taken, because drug names
	 * nest: {@code "tiotropium".contains("opium")} and {@code "spironolactone".contains("iron")} are
	 * both true, and both were raised as Major interaction chips on the 3.7.1 standalone (issue #86).
	 * The discriminating half of the fix is the LEFT boundary shared with {@link #containsWord}: an
	 * alphanumeric character immediately before the token means the token sits inside a longer word,
	 * i.e. a different molecule — {@code tiotr|opium}, {@code sp|iron|olactone},
	 * {@code nitro|glycerin}, {@code bud|esonide}, {@code hydro|chlorothiazide},
	 * {@code cipr|ofloxacin}.
	 *
	 * <p>The right-hand side is where this deliberately differs from {@link #containsWord}, because
	 * the two kinds of string differ: prose is words, an order name is one localized, inflected
	 * display name with a dose appended. Measured over the 3.7.1 demo dictionary (2531 drug and
	 * drug-concept names x the full KB's 2093 rule tokens), by tolerated trailing letters:
	 *
	 * <pre>
	 *   rule                    matches   nested-name collisions leaking   what enters at this step
	 *   contains (the defect)     896     9 of 9                           —
	 *   symmetric boundary        761     0 of 9                           —
	 *   left + &lt;=1 letter         828     0 of 9   67 localized spellings (Aspirine Co 81mg, Aspirina,
	 *                                              Amoxicilline, Clarithromycine Co 500mg, Ondansetrona)
	 *   left + &lt;=2 letters        829     0 of 9   1 localized plural (Multivitamines et fer)
	 *   left + &lt;=3 letters        829     0 of 9   nothing
	 *   left + &lt;=4 letters        834     0 of 9   5 FALSE positives (Heparinoids ~ heparin,
	 *                                              Multi-Vitamin Adult ~ vitamin a)
	 * </pre>
	 *
	 * A symmetric boundary would therefore stop checking a patient on {@code Aspirine Co 81mg} for
	 * aspirin interactions at all — trading a false positive for a false NEGATIVE, the wrong
	 * direction for a safety net, and one that looks exactly like the noise being removed. Two is the
	 * far edge of the plateau where every legitimate name is matched and no false positive has yet
	 * appeared; the first ones appear at four. Stopping at one (this issue's originally measured
	 * recommendation) leaves exactly one legitimate name unmatched, {@code Multivitamines et fer},
	 * whose reference entry carries 2 Major and 8 Moderate rules that would silently stop being
	 * checked.
	 *
	 * <p>A bound on the tail, rather than a list of known inflections: stripping
	 * {@code -e}/{@code -a}/{@code -o} from both sides was measured on the same corpus at 826
	 * matches, a strict subset of this rule, and trades one bound for a per-language whitelist that a
	 * differently-localized deployment falls off silently. Residual imprecision this rule keeps, for
	 * the record: 2 of the 829 are a nitroglycerin order matching the token {@code glycerin} through
	 * its own parenthetical synonym ("glycerine trinitrate") — a mislabel, not a fabricated drug, and
	 * one every rule that tolerates an inflectional tail shares.
	 */
	static boolean matchesOrderName(String orderName, String token) {
		return containsBoundedToken(orderName, token, MAX_ORDER_NAME_INFLECTION_LETTERS);
	}

	/**
	 * The one boundary-aware containment scan, shared by prose matching ({@link #containsWord}) and
	 * order-name matching ({@link #matchesOrderName}) so the boundary rule cannot drift between
	 * them. A match needs {@code token} to start at a word boundary in {@code text} and to end at
	 * one, give or take up to {@code maxTrailingLetters} letters — never digits, which in a drug
	 * name are a strength rather than part of the name. Case-insensitive; a null/blank token never
	 * matches.
	 */
	private static boolean containsBoundedToken(String text, String token, int maxTrailingLetters) {
		if (text == null || token == null || token.isEmpty()) {
			return false;
		}
		String t = text.toLowerCase(Locale.ROOT);
		String w = token.toLowerCase(Locale.ROOT);
		int idx = t.indexOf(w);
		while (idx >= 0) {
			if (idx == 0 || !Character.isLetterOrDigit(t.charAt(idx - 1))) {
				int end = idx + w.length();
				for (int tail = 0; tail <= maxTrailingLetters; tail++) {
					int at = end + tail;
					// A tail character must itself be a letter to be stepped over.
					if (tail > 0 && !Character.isLetter(t.charAt(at - 1))) {
						break;
					}
					if (at >= t.length() || !Character.isLetterOrDigit(t.charAt(at))) {
						return true;
					}
				}
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
