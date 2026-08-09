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
import org.slf4j.Logger;

/**
 * The drug-reference loader's self-audit: what was wrong with the dataset and the configuration it just
 * loaded, and what it did about each thing. One check with one answer to "what does this loader consider
 * valid", rather than a guard per defect — five separate guards would come to five different answers,
 * and every rule here was filed as its own issue precisely because the loader was silent about one more
 * way its input can violate an assumption its own code makes.
 *
 * <p><b>Why this exists at all.</b> Issues #149 and #154 established that a load which is WRONG must be
 * loud, and that what was loaded must be observable AFTER the lazy load rather than inferred from a log
 * line that may belong to a previous load. Those covered the COUNT. Every rule below is that same
 * principle applied one layer deeper, to the CONTENT: a dataset can load a plausible number of entries
 * and still violate an assumption that silently changes every safety decision taken from it (#150), drop
 * every rule-bearing row of a substance (#211), be a different dataset than the one configured (#156), or
 * publish one substance's name on another (#196).
 *
 * <p><b>Why the remedies differ per rule, deliberately.</b> Making them uniform would be a decision taken
 * by default. Each rule below records which of the three it takes and why:
 * <ul>
 *   <li>{@link Remedy#DROPPED} where the offending VALUE is the whole defect and the rest of the entry
 *       is usable. Dropping more than that would trade a fail-open for a silent fail-closed, and this
 *       module treats withholding a finding as the worse direction.</li>
 *   <li>{@link Remedy#REPAIRED} where the loader can bring the data to the shape its own resolution
 *       assumes without asserting anything the data does not already say — which, in the one case it
 *       applies to, is what the other two parsers do as they build their alias lists.</li>
 *   <li>{@link Remedy#REPORTED} where fixing it would mean inventing a fact: which rows are one
 *       substance, or which of two colliding names is right. The data is left exactly as loaded and the
 *       operator (or the upstream project) is told what to fix.</li>
 * </ul>
 * No rule REFUSES a file. The drug-reference feature is an additive net that must never break the answer
 * path ({@link ReferenceDataFiles}), and refusing a dataset over a content defect would turn a
 * misconfiguration into an inert safety layer — the exact state issue #149's WARN exists to flag.
 *
 * <p><b>Loudness is per rule too</b> ({@link Finding#isLoud()}), for one reason and one exception. The
 * reason: an untouched default must stay silent. {@code dataFilePath} and
 * {@code crossReactivityGroupsFilePath} both default to paths inside the application data directory that
 * the module never creates, so every install that has configured nothing falls back — a rule that warned
 * on every fallback would fire on every install and be filtered within a week, which is worse than
 * silence because it trains people to ignore the channel. The exception is recorded on
 * {@link #CONFIGURED_SOURCE_FORMAT_NOT_USED}.
 *
 * <p>An instance is a per-load collector, created where the load happens and discarded with it — never a
 * field, and never shared between loads (issue #172's rule for anything cached around this data). Not
 * thread-safe, and does not need to be: it is built inside {@code DrugReferenceService.ensureLoaded}'s
 * monitor and is immutable in practice by the time anything else can see it.
 */
public final class DrugReferenceValidity {

	/** An alias that names nothing, so it can only match by accident — issue #150. */
	public static final String BLANK_ALIAS = "blank-alias";

	/** An entry that none of its own aliases names, so the ranked resolution can reach a claimant that
	 *  is not in the candidate set — issues #210, #211. */
	public static final String ENTRY_NOT_NAMED_BY_ITS_OWN_ALIASES = "entry-not-named-by-its-own-aliases";

	/** A rule-bearing entry sharing a published name with another entry, neither declaring which
	 *  substance it is a row of — issue #211. */
	public static final String RULES_WITHOUT_A_SUBSTANCE_IDENTITY = "rules-without-a-substance-identity";

	/** An entry publishing, among its own names, a name a different substance is called — issue #196. */
	public static final String ALIAS_NAMES_ANOTHER_SUBSTANCE = "alias-names-another-substance";

	/** An explicitly configured dataset file that could not be read, so a different dataset is in
	 *  force — issue #156, case 1. */
	public static final String CONFIGURED_DATA_FILE_NOT_READ = "configured-data-file-not-read";

	/**
	 * An explicitly configured {@code sourceFormat} matching no adapter, so a different parser is in
	 * force — issue #156, case 2.
	 *
	 * <p><b>The one rule that is deliberately not loud, and not because it does not deserve to be.</b>
	 * {@code DrugReferenceLoadContextTest.loadStatusReportsAMistypedSourceFormatSeparatelyFromTheOneInForce}
	 * asserts that this case must NOT be reported at WARN, on the ground that the two format fields
	 * differing is signal enough. Issue #156 overturns that ground — it asks for "the operator named
	 * something specific and we did not use it" to be a first-class warning in BOTH its cases — so the
	 * two disagree, and which of them holds is a maintainer's decision rather than this check's. The
	 * assertion is therefore left exactly as it stands and this rule stays quiet in the log; recording it
	 * as a finding is what makes the case first-class in the meantime. Making it loud is a one-line
	 * change here.
	 */
	public static final String CONFIGURED_SOURCE_FORMAT_NOT_USED = "configured-source-format-not-used";

	/** What the loader did about a finding — see the class javadoc for why they differ per rule. */
	public enum Remedy {

		/** The data was left exactly as loaded; only the operator can fix it. */
		REPORTED,

		/** The loader brought the data to the shape its own resolution assumes. */
		REPAIRED,

		/** The offending value was removed from the loaded data. */
		DROPPED
	}

	/**
	 * One rule's verdict on one load: which rule, what the loader did, how many times, and enough detail
	 * to find the rows. Immutable.
	 *
	 * <p>The occurrence count and a bounded sample rather than one finding per row: the shipped knowledge
	 * base has thousands of rows and the count is how a maintainer sees whether a refresh introduced more
	 * of them, which is the same shape as {@code DdiDrugReferenceSource}'s self-pair line.
	 */
	public static final class Finding {

		private final String rule;

		private final Remedy remedy;

		private final boolean loud;

		private final int occurrences;

		private final String detail;

		private Finding(String rule, Remedy remedy, boolean loud, int occurrences, String detail) {
			this.rule = rule;
			this.remedy = remedy;
			this.loud = loud;
			this.occurrences = occurrences;
			this.detail = detail;
		}

		/** @return the rule that fired — one of this class's constants. */
		public String getRule() {
			return rule;
		}

		/** @return what the loader did about it. */
		public Remedy getRemedy() {
			return remedy;
		}

		/** @return whether the loader reports this at WARN. See {@link #CONFIGURED_SOURCE_FORMAT_NOT_USED}
		 *          for the only rule that is false. */
		public boolean isLoud() {
			return loud;
		}

		/** @return how many times the rule fired in this load. */
		public int getOccurrences() {
			return occurrences;
		}

		/** @return what is wrong and where, naming the rows or files an operator has to look at. */
		public String getDetail() {
			return detail;
		}

		/** @return this finding as a JSON-serializable map, for a caller that reports the load. */
		public Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<String, Object>();
			map.put("rule", rule);
			map.put("remedy", remedy.name().toLowerCase(Locale.ROOT));
			map.put("occurrences", occurrences);
			map.put("detail", detail);
			return map;
		}

		@Override
		public String toString() {
			return rule + " (" + remedy.name().toLowerCase(Locale.ROOT) + ", " + occurrences + "): "
					+ detail;
		}
	}

	/**
	 * How many offending values one finding's detail names before it stops listing them. Enough to
	 * identify the shape and to fix a hand-authored file outright, bounded so a defect affecting
	 * thousands of rows does not put thousands of names in one log line.
	 */
	private static final int DETAIL_SAMPLE = 8;

	private final List<Finding> findings = new ArrayList<Finding>();

	private void report(String rule, Remedy remedy, boolean loud, int occurrences, String detail) {
		findings.add(new Finding(rule, remedy, loud, occurrences, detail));
	}

	/** @return every rule that fired in this load, in the order the loader applied them; never null. */
	public List<Finding> getFindings() {
		return Collections.unmodifiableList(findings);
	}

	/** Adds findings another stage of the same load produced — the parsers report through
	 *  {@link DrugReferenceSource#lastLoadFindings()}, which is the same channel
	 *  {@link DrugReferenceSource#lastLoadOrigin()} uses and for the same reason. */
	void addAll(List<Finding> earlier) {
		if (earlier != null) {
			findings.addAll(earlier);
		}
	}

	/**
	 * Reports every loud finding at WARN, one line each. Owned here so the two loads that run these rules
	 * — the entry dataset through {@link DrugReferenceService} and the cross-reactivity groups through
	 * {@link CrossReactivityGroupsLoader}, which has no status object to be read from — cannot come to
	 * report them differently.
	 */
	void logTo(Logger log) {
		for (Finding found : findings) {
			if (found.isLoud()) {
				log.warn("Drug-reference data validity — {}", found);
			}
		}
	}

	// ------------------------------------------------------------------
	// Configuration rules (issue #156)
	// ------------------------------------------------------------------

	/**
	 * Issue #156, case 1: the dataset the operator named was not the one read.
	 *
	 * <p>Loud only when the configured value is a value someone CHOSE — non-blank and not the global
	 * property's own declared default. Both spellings of "untouched" have to be excluded, because an
	 * installed module reads the declared default from its {@code config.xml} while a context without
	 * that row reads blank, and neither is a misconfiguration.
	 *
	 * @param declaredDefault the value the module's {@code config.xml} declares for this global property
	 * @param origin what was actually read ({@link ReferenceDataFiles.Loaded#getOrigin()})
	 */
	void configuredDataFileNotRead(String globalProperty, String configured, String declaredDefault,
			String origin) {
		if (configured == null || configured.trim().isEmpty() || configured.equals(declaredDefault)) {
			return;
		}
		report(CONFIGURED_DATA_FILE_NOT_READ, Remedy.REPORTED, true, 1,
				globalProperty + " names '" + configured + "', which could not be read, so '" + origin
						+ "' is in force instead. The entry count is therefore a count of a dataset "
						+ "nobody configured, and looks healthy.");
	}

	/**
	 * Issue #156, case 2: the parser the operator named was not the one used. See
	 * {@link #CONFIGURED_SOURCE_FORMAT_NOT_USED} for why this one is not loud.
	 */
	void configuredSourceFormatNotUsed(String configured, String effective) {
		if (configured == null || configured.trim().isEmpty()
				|| configured.equalsIgnoreCase(effective)) {
			return;
		}
		report(CONFIGURED_SOURCE_FORMAT_NOT_USED, Remedy.REPORTED, false, 1,
				ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT + " is '" + configured
						+ "', which matches no adapter, so the '" + effective
						+ "' parser is in force instead.");
	}

	// ------------------------------------------------------------------
	// Content rules, over the entries a load produced
	// ------------------------------------------------------------------

	/**
	 * Runs every content rule over the entries a load produced, repairing and dropping in place where the
	 * rule says so.
	 *
	 * <p>Here rather than inside each parser, for three reasons. An operator's file gets the same checks
	 * whichever parser reads it, so there is one answer to what is valid rather than one per format. The
	 * rules that ask whether two rows are one substance need {@link DrugReference#substanceGroupKey()},
	 * which is a property of the loaded model rather than of any one file's schema. And the load is the
	 * boundary the answer path is protected at: the {@code setEntries} test seam bypasses all dataset
	 * loading, so it bypasses this too, which is what it is documented to do.
	 *
	 * @param entries the loaded entries, mutated in place by the DROPPED and REPAIRED rules
	 */
	void checkEntries(List<DrugReference> entries) {
		if (entries == null || entries.isEmpty()) {
			return;
		}
		sanitizeAliases(entries);
		reportRulesWithoutASubstanceIdentity(entries);
		reportAliasesNamingAnotherSubstance(entries);
	}

	/**
	 * Issues #150 and #210/#211's precondition, in one pass because both are per-entry statements about
	 * one alias list and the second is only true of what the first leaves behind.
	 *
	 * <p><b>Blank aliases are DROPPED</b> (#150). An alias with no letter or digit in it names nothing, so
	 * it cannot identify a drug and can only match by accident — and it does:
	 * {@link DrugReference#containsBoundedToken} already refuses a token that is EMPTY after the
	 * diacritic fold, but a single space is not empty. Where a space sits after a non-alphanumeric
	 * character its left boundary holds, and the recorded-name rule's two-letter inflection allowance then
	 * carries the match over a short trailing word. So a blank alias makes
	 * {@link DrugReference#matchesDrugName} true for allergen text the entry has nothing to do with, and
	 * that entry's contraindications fire for a patient with an unrelated allergy — failing OPEN.
	 *
	 * <p>Narrower than "every allergen", and worth stating because the bound is what makes this latent
	 * rather than obvious. Measured through {@link DrugReference#matchesDrugName} on an entry carrying
	 * {@code [warfarin, " "]}: {@code Vitamin A, B} matches and {@code Aspirin},
	 * {@code Sodium chloride} and {@code Amphotericin B, liposomal} do not — a single-word allergen has no
	 * space at all, and a space preceded by a letter fails the left boundary. It takes a recorded name
	 * with a non-alphanumeric before a space and a short word after it. The prose matcher is false on the
	 * same string, which is the asymmetry issue #150 reports: #148 gave allergen resolution the
	 * recorded-name rule, and the tail allowance is what opened this.
	 *
	 * <p>The offending token and not the entry, deliberately. The entry's other aliases, its ATC codes and
	 * its rules are all valid and may be its only coverage — the ATC codes especially, since
	 * {@link DrugReferenceService#findByActiveOrders} matches on those and not on aliases at all — so
	 * refusing the entry would convert a fail-open into a silent fail-closed. Dropping the token removes
	 * exactly the thing that fails open and nothing else, which is why it is preferred over refusing the
	 * entry rather than merely gentler than it.
	 *
	 * <p><b>An entry no alias of its own names is REPAIRED</b> (#210, #211). The ranked resolution rests on
	 * a property of the PARSERS: {@link DdiDrugReferenceSource} makes an entry's display name its first
	 * alias and {@link AtcDrugReferenceSource} makes it the only one, so on both of those the strongest
	 * claimant on any alias an entry carries is itself in the matched set. A hand-authored {@code json}
	 * file need not do that, and {@code json} is the DEFAULT format — there the rank-2 claimant can be an
	 * entry the matcher never reached, and then no matched row's alias denotes its own substance and every
	 * one is dropped ({@code DrugReferenceService.rowsOf} bounds that to "never emptied", which is a floor
	 * rather than a fix). Giving the entry its own display name asserts nothing the file does not already
	 * say — it IS the entry's name — and it is exactly what the other two parsers do, so the invariant
	 * holds by construction on every format instead of resting on the shape of the file.
	 *
	 * <p>Lowercased as those parsers lowercase theirs, so an alias list stays one vocabulary; the authored
	 * aliases are kept and the name is appended, so nothing an author wrote is replaced.
	 */
	private void sanitizeAliases(List<DrugReference> entries) {
		int blanks = 0;
		int unnamed = 0;
		Set<String> blankIn = new LinkedHashSet<String>();
		Set<String> unnamedIn = new LinkedHashSet<String>();
		for (DrugReference entry : entries) {
			List<String> usable = new ArrayList<String>(entry.getAliases().size() + 1);
			for (String alias : entry.getAliases()) {
				if (namesAnything(alias)) {
					usable.add(alias);
				}
				else {
					blanks++;
					blankIn.add(String.valueOf(entry.getName()));
				}
			}
			if (usable.size() != entry.getAliases().size()) {
				entry.setAliases(usable);
			}
			// Asked of the entry AFTER the drop, and through its own predicate rather than a second
			// reading of the same list: a blank alias is not a name, so an entry whose only "name" was
			// blank is unnamed once it is gone. Gated on the display name being a name at all — the
			// curated parser already drops a blank-named entry, and repairing one by giving it a blank
			// alias would put back exactly what the rule above took out.
			String own = DrugReference.normalizeName(entry.getName());
			if (own != null && !entry.isNamed(own)) {
				unnamed++;
				unnamedIn.add(entry.getName());
				usable.add(own);
				entry.setAliases(usable);
			}
		}
		if (blanks > 0) {
			report(BLANK_ALIAS, Remedy.DROPPED, true, blanks,
					blanks + " alias(es) naming nothing (blank, or nothing but combining marks) were "
							+ "dropped: such a token matches at a word boundary in text it has nothing to "
							+ "do with, so the entry's rules would fire for a patient with an unrelated "
							+ "allergy. Entries: " + sample(blankIn));
		}
		if (unnamed > 0) {
			report(ENTRY_NOT_NAMED_BY_ITS_OWN_ALIASES, Remedy.REPAIRED, true, unnamed,
					unnamed + " entr(ies) whose aliases omitted their own name were given it, so the "
							+ "strongest claimant on a name is always among the entries that name "
							+ "matches. Entries: " + sample(unnamedIn));
		}
	}

	/**
	 * Issue #211, and the decision it asked for rather than a patch.
	 *
	 * <p><b>The defect.</b> Rows of one substance can disagree about which of them carries the interaction
	 * and contraindication rules. The ranked resolution decides which rows a name puts in play by NAME
	 * claim, which is silent about rules, and it takes that verdict per SUBSTANCE — so where the rows of
	 * what a clinician would call one drug key as DIFFERENT substances, only the strongest claimant on the
	 * shared name survives and every other row's rules go with it. Measured on a hand-authored fixture: an
	 * order for {@code Ibuprofen 400mg} keeps the bare {@code Ibuprofen} row, which carries no rules, and
	 * drops the two presentations that do. The candidate set is non-empty, so #210's emptying guard is
	 * satisfied and the findings are gone anyway.
	 *
	 * <p>An order naming a PRESENTATION ({@code Ibuprofen tablets 400mg}) keeps that presentation, because
	 * the repair above gives every row its own name and the string carries it. So the shape that still
	 * loses rules is the ordinary one — a name carrying only the substance's name — which is why the
	 * repair narrows this defect without closing it.
	 *
	 * <p><b>Why this is a load-time rule and not a resolution change.</b> The issue records three options.
	 * Preferring a rule-bearing row among equally-strong claimants re-introduces a rule-shaped tie-break
	 * into a name-shaped ranking, which is the confusion issue #209 was filed to remove, and it would
	 * change which row every chip names on data that is perfectly well-formed. Pooling a substance's rules
	 * changes what a chip's {@code ref} refers to, so a citable record would carry a rule that is not in
	 * the row it cites. The third — validate at load — is the only one that does not change a correct
	 * answer on correct data, and it is right for a further reason: the loader cannot tell a presentation
	 * of one substance from a second substance whose name merely nests inside the first, and inferring it
	 * from spelling is exactly the substance judgement issues #164 and #192 measured this module must not
	 * make. {@link DrugReference#substanceKey()} needs the DATA to say so.
	 *
	 * <p><b>So: REPORTED.</b> The rows stay as loaded and the operator is told which published name is
	 * ambiguous. The fix is in the file, and it is what the shipped datasets already do: declare
	 * {@code substanceName} on the rows that are one substance, and name a presentation with a TRAILING
	 * PARENTHESIZED qualifier. Both halves are needed, which is worth stating because the first alone
	 * looks sufficient and is not: a curated file publishes no substance registry, so
	 * {@link DrugReference#substanceKey()} has nothing to confirm the claim with and falls back to the
	 * display STEM — under which {@code Ibuprofen tablets} is still its own substance while
	 * {@code Ibuprofen (tablets)} is not. {@code DrugReferenceValidityContextTest} pins both files.
	 *
	 * <p><b>The residual, named rather than left to be rediscovered.</b> An author who declares
	 * {@code substanceName} and does NOT follow that naming convention gets silence here and still loses
	 * rules, because this rule is gated on declaring no substance name at all. Widening it to "the rows
	 * key differently although the data claimed one substance" is not available: on the shipped knowledge
	 * base that state is also how the DrugBank registry REFUSES a claim — the 19 substance-name families
	 * naming two or more DrugBank substances have their id withheld and fall back to the stem
	 * deliberately (issue #164), and {@link DrugReference#getSubstanceId()} is null in both cases, so the
	 * loaded model cannot tell "no registry" from "the registry says these differ". Flagging it would
	 * report {@code Omeprazole} against {@code Esomeprazole} — a separation this module is careful to
	 * keep — on the default configuration. Closing it needs the curated schema to be able to state a
	 * substance identity, which is a schema decision and not this check's.
	 *
	 * <p>The shipped datasets are quiet: every DDInter row publishes an {@code rxnorm_name}, so this
	 * cannot fire on the knowledge base, and an ATC entry carries no rules to lose.
	 *
	 * <p>Gated on the entry CARRYING rules, which is what makes the rule about a loss rather than about a
	 * name. A row with no interactions, contraindications or dose bands loses nothing by being dropped
	 * from a candidate set — it contributes no finding either way — and every {@code atc} entry is such a
	 * row, so the gate is also what keeps a classification-only dataset silent.
	 */
	private void reportRulesWithoutASubstanceIdentity(List<DrugReference> entries) {
		Map<String, Set<String>> sharedBy = new LinkedHashMap<String, Set<String>>();
		for (DrugReference entry : entries) {
			if (entry.substanceKey() != null) {
				// The data says which substance this row is of, so the verdict cannot split it off.
				continue;
			}
			for (String alias : entry.getAliases()) {
				String name = DrugReference.normalizeName(alias);
				if (name == null) {
					continue;
				}
				Set<String> claimants = sharedBy.get(name);
				if (claimants == null) {
					claimants = new LinkedHashSet<String>();
					sharedBy.put(name, claimants);
				}
				claimants.add(String.valueOf(entry.getName()));
			}
		}
		int atRisk = 0;
		Set<String> details = new LinkedHashSet<String>();
		for (DrugReference entry : entries) {
			if (entry.substanceKey() != null || !carriesRules(entry)) {
				continue;
			}
			for (String alias : entry.getAliases()) {
				String name = DrugReference.normalizeName(alias);
				Set<String> claimants = name == null ? null : sharedBy.get(name);
				if (claimants != null && claimants.size() > 1
						&& !name.equals(DrugReference.normalizeName(entry.getName()))) {
					atRisk++;
					details.add(entry.getName() + " shares '" + name + "' with " + claimants);
					break;
				}
			}
		}
		if (atRisk > 0) {
			report(RULES_WITHOUT_A_SUBSTANCE_IDENTITY, Remedy.REPORTED, true, atRisk,
					atRisk + " rule-bearing entr(ies) share a published name with another entry while "
							+ "declaring no substanceName, so each is its own substance and only the "
							+ "strongest claimant on that name is put in play — the others' rules are "
							+ "silently dropped. Fix: declare substanceName on the rows that are one "
							+ "substance AND name a presentation with a trailing parenthesized qualifier "
							+ "('Ibuprofen (tablets)', not 'Ibuprofen tablets'), which is what the shipped "
							+ "datasets do; the claim alone is vetoed by the display stem. "
							+ sample(details));
		}
	}

	/**
	 * The check decided on issue #196: an entry publishing, among its OWN names, a name that a different
	 * substance is CALLED.
	 *
	 * <p>Aliases are this module's resolution keys, so a name filed on the wrong entry is not a cosmetic
	 * data error. Two of the eight defects that issue records are this shape:
	 * {@code Pfizer-BioNTech Covid-19 Vaccine} carries {@code moderna covid-19 vaccine}, a rival
	 * manufacturer's product, and {@code Trastuzumab emtansine} carries {@code trastuzumab deruxtecan}
	 * because all three trastuzumab rows share one CIEL cross-walk list although they are three DrugBank
	 * substances with three ATC codes. The second produced a false clinical statement live — a Kadcyla
	 * allergy reported as "a recorded allergy to Trastuzumab deruxtecan". Issue #210 blocks the prose
	 * direction by ranking the claim, but {@link DrugReference#isNamed} — rule-token identity — does not
	 * rank, so the collision stays reachable however the prose legs are ordered.
	 *
	 * <p><b>Two conditions, each excluding a shape that is CORRECT</b>, which is what keeps this from
	 * being a channel operators filter:
	 * <ul>
	 *   <li>the two entries must be different substances to {@link DrugReference#substanceGroupKey()} —
	 *       otherwise one substance filed under two names is flagged, which issue #164 decided is one
	 *       substance ({@code Daxibotulinumtoxina} against {@code Botulinum toxin type A});</li>
	 *   <li>neither display stem may carry the other as a word — otherwise every ester, salt and
	 *       presentation whose own name extends the substance's is flagged, and those are legitimate:
	 *       {@code Hydrocortisone butyrate} publishing {@code hydrocortisone} is a true statement about a
	 *       true relationship, and narrowing it is what issues #198/#209 do at resolution time rather
	 *       than something a loader should refuse.</li>
	 * </ul>
	 * Through {@link DrugReference#containsWord} on the display stems rather than a fresh comparison, so
	 * "one name carries another" means here what it means to the matchers.
	 *
	 * <p><b>REPORTED.</b> The module has no better data to substitute: it can only choose WHICH of two
	 * drugs to name, and the data says they are interchangeable, so no choice it makes is right. Dropping
	 * the alias would lose the resolutions that name is the only path to, and dropping the row would lose
	 * its real interaction rules — both fail closed, silently, to fix a fail-loud. Issue #196 records the
	 * data fix as an upstream handoff, and this is the check that finds the rows to hand off.
	 */
	private void reportAliasesNamingAnotherSubstance(List<DrugReference> entries) {
		Map<String, List<DrugReference>> byDisplayName = new LinkedHashMap<String, List<DrugReference>>();
		for (DrugReference entry : entries) {
			String name = DrugReference.normalizeName(entry.getName());
			if (name == null) {
				continue;
			}
			List<DrugReference> named = byDisplayName.get(name);
			if (named == null) {
				named = new ArrayList<DrugReference>(1);
				byDisplayName.put(name, named);
			}
			named.add(entry);
		}
		int collisions = 0;
		Set<String> details = new LinkedHashSet<String>();
		for (DrugReference entry : entries) {
			String own = DrugReference.normalizeName(entry.getName());
			for (String alias : entry.getAliases()) {
				String name = DrugReference.normalizeName(alias);
				if (name == null || name.equals(own)) {
					continue;
				}
				for (DrugReference other : byDisplayName.getOrDefault(name,
						Collections.<DrugReference> emptyList())) {
					if (other.substanceGroupKey().equals(entry.substanceGroupKey())
							|| stemsCarryEachOther(entry, other)) {
						continue;
					}
					collisions++;
					details.add(entry.getName() + " publishes '" + name + "', which is "
							+ other.getName() + "'s own name");
					break;
				}
			}
		}
		if (collisions > 0) {
			report(ALIAS_NAMES_ANOTHER_SUBSTANCE, Remedy.REPORTED, true, collisions,
					collisions + " published name(s) denote a DIFFERENT substance in this dataset, so a "
							+ "question or a chart string carrying one resolves the wrong drug. The data "
							+ "is left as loaded — the fix is in the dataset. " + sample(details));
		}
	}

	// ------------------------------------------------------------------
	// Predicates
	// ------------------------------------------------------------------

	/**
	 * @return whether {@code alias} can identify a drug at all: it must carry a letter or a digit once
	 *         diacritics are folded away, which is the same fold
	 *         {@link DrugReference#containsBoundedToken} applies before scanning. Anything else — blank,
	 *         punctuation, nothing but combining marks — names nothing, and the scan's boundary rules
	 *         then make it match at word gaps rather than not at all.
	 */
	private static boolean namesAnything(String alias) {
		if (alias == null) {
			return false;
		}
		String folded = DrugReference.foldDiacritics(alias.toLowerCase(Locale.ROOT));
		for (int i = 0; i < folded.length(); i++) {
			if (Character.isLetterOrDigit(folded.charAt(i))) {
				return true;
			}
		}
		return false;
	}

	/** @return whether the entry carries anything a safety arm could report — which is what is lost when
	 *          a row is dropped from a candidate set. */
	private static boolean carriesRules(DrugReference entry) {
		return !entry.getInteractions().isEmpty() || !entry.getContraindications().isEmpty()
				|| !entry.getAgeBands().isEmpty();
	}

	/** @return whether either entry's display stem carries the other's as a word — the relationship a
	 *          presentation, salt or ester legitimately has to its substance. */
	private static boolean stemsCarryEachOther(DrugReference one, DrugReference other) {
		String a = DrugReference.displayStem(one.getName());
		String b = DrugReference.displayStem(other.getName());
		return a != null && b != null
				&& (DrugReference.containsWord(a, b) || DrugReference.containsWord(b, a));
	}

	/** @return up to {@link #DETAIL_SAMPLE} of the offending values, saying so when there are more. */
	private static String sample(Set<String> values) {
		List<String> shown = new ArrayList<String>(values);
		if (shown.size() <= DETAIL_SAMPLE) {
			return shown.toString();
		}
		String more = " and " + (shown.size() - DETAIL_SAMPLE) + " more";
		return shown.subList(0, DETAIL_SAMPLE).toString() + more;
	}
}
