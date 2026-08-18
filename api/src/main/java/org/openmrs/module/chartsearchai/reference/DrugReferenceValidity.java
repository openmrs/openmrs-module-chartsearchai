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
import java.util.Arrays;
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
 * every rule-bearing row of a substance (#211), be a different dataset than the one configured (#156),
 * publish one substance's name on another, or key two substances as one (#196). One rule is a layer
 * ABOVE instead, over the document rather than the entries, and for the same reason from the other
 * direction: a file can carry content and load none of it, because it omits a table the parser reading
 * it requires (#242) — an empty PARSE of a non-empty file, where #149 made an empty LOAD loud.
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
 *       substance, which of two colliding names is right, or what a table the document never declared
 *       would have held. The data is left exactly as loaded and the operator (or the upstream project)
 *       is told what to fix.</li>
 * </ul>
 * No rule REFUSES a file. The drug-reference feature is an additive net that must never break the answer
 * path ({@link ReferenceDataFiles}), and refusing a dataset over a content defect would turn a
 * misconfiguration into an inert safety layer — the exact state issue #149's WARN exists to flag.
 *
 * <p><b>Every finding is reported, and silence is the absence of a finding rather than a muted one.</b>
 * That distinction is what keeps the channel usable. An untouched default must stay silent:
 * {@code dataFilePath} and {@code crossReactivityGroupsFilePath} both default to paths inside the
 * application data directory that the module never creates, so every install that has configured nothing
 * falls back, and a rule that warned on every fallback would fire on every install and be filtered within
 * a week — worse than silence, because it trains people to ignore the channel. So
 * {@link #configuredDataFileNotRead} returns without reporting anything there. What it does NOT do is
 * report a finding and then keep it out of a channel: a finding that exists is one somebody needs, and
 * "visible on the status but absent from the log" is the confusion issues #149 and #154 settled — see
 * {@link #CONFIGURED_SOURCE_FORMAT_NOT_USED}, which used to be the exception.
 *
 * <p>Both channels carry every finding, for the same reason: the log says it once, at the moment it
 * happened, and {@link DrugReferenceLoad#getFindings()} — including over
 * {@code GET /chartsearchai/drugreferencestatus} — answers it afterwards, which is the question a lazy
 * load makes a log line unable to answer.
 *
 * <p><b>What is scoped is the LEVEL, and only in the log.</b> "Every finding is loud" was true while every
 * dataset the module shipped was one it authored; since ADR Decision 36 the default is a third-party
 * knowledge base, and a data finding about THAT names something no operator can fix — 19 of its 2283 rows
 * trip two of the rules below, and reporting them at WARN on every install of every deployment is the
 * noise this class exists to avoid. So {@link #logTo(Logger, String)} reports a DATA finding about the
 * dataset the module ships at INFO and everything else at WARN, while the status channel stays identical
 * either way. The level says who can act; the status says what is true. A CONFIGURATION finding is never
 * scoped, because it names a choice the operator made — and because #156's own finding fires exactly when
 * the bundled dataset was the one read.
 *
 * <p>An instance is a per-load collector, created where the load happens and discarded with it — never a
 * field, and never shared between loads. That is issue #172's rule taken for a reason of this class's
 * own rather than for the ones {@link DrugReferenceService}'s class javadoc gives: those are about
 * memos derived from {@code getAll()} on a singleton, and this is a per-LOAD collector, whose findings
 * describe the one load that built it and would be reported against a different dataset if it outlived
 * that load. (Do not follow the issue for a reason either; its own statement of one is false — see that
 * class javadoc.) Not thread-safe, and does not need to be: it is built inside
 * {@code DrugReferenceService.ensureLoaded}'s monitor and is immutable in practice by the time anything
 * else can see it.
 *
 * <p>Two instances live for a PARSE rather than a load — the ones the parsers' one-argument
 * {@code parse} forms build for themselves. Same rule at a shorter scope, and safe for the same reason
 * twice over: each is a local of the call that made it, and each is handed no load to describe, so what
 * it collects reaches {@link #logTo} and stops there rather than reaching a status that would then be
 * describing a parse.
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

	/** A row the data merged into a substance its OWN name says it is only a derivative of — issue
	 *  #196 item 4. */
	public static final String DERIVATIVE_MERGED_WITH_ITS_PARENT_SUBSTANCE =
			"derivative-merged-with-its-parent-substance";

	/** A document that omits a table the parser reading it requires, so content it does carry is
	 *  discarded — issue #242. */
	public static final String DATASET_MISSING_A_REQUIRED_TABLE = "dataset-missing-a-required-table";

	/**
	 * Interaction rows pairing a drug with itself, or with another route/formulation row of the same
	 * substance — issues #152 and #164. A drug cannot interact with itself, so
	 * {@link DdiDrugReferenceSource} drops the rows; this is where the count is reported.
	 *
	 * <p>A finding rather than the bare WARN it used to be. The count was already the thing worth
	 * reporting — it is how a maintainer sees whether a refresh introduced more of them — but as a log
	 * line it reached only the channel that cannot answer after a lazy load (#154), and it was the one
	 * data verdict in this loader that never appeared on
	 * {@code GET /chartsearchai/drugreferencestatus}. Routing it here also means it is scoped by
	 * {@link #logTo(Logger, String)} like every other data rule, instead of being the one that stayed
	 * loud about a dataset the module ships.
	 */
	public static final String SELF_PAIRED_INTERACTION_ROWS = "self-paired-interaction-rows";

	/** An explicitly configured dataset file that could not be read, so a different dataset is in
	 *  force — issue #156, case 1. */
	public static final String CONFIGURED_DATA_FILE_NOT_READ = "configured-data-file-not-read";

	/**
	 * An explicitly configured {@code sourceFormat} matching no adapter, so a different parser is in
	 * force — issue #156, case 2.
	 *
	 * <p>Loud <b>wherever the entries came from</b>, which since ADR Decision 36 is no longer true of every
	 * rule here — the data rules soften for the dataset the module ships, and this one must not, because it
	 * fires precisely when the operator's own file was NOT read and that dataset WAS. It names a choice
	 * they made and can unmake. The reason it is worth stating is that it used not to be loud at all. An
	 * assertion in {@code DrugReferenceLoadContextTest} held that this case needed no WARN because the
	 * status reports the configured and effective formats separately, so the difference was already
	 * visible. That ground is the confusion issues #149 and #154 settled: <b>observable is not the same
	 * as loud.</b> #154 built the status endpoint precisely because an operator cannot be expected to
	 * poll it, and #149 exists because a wrong load logged at INFO is indistinguishable from a right
	 * one. A mistyped format hands a DDInter file to the curated parser and the operator is told
	 * nothing, which is the shape #156 was filed about.
	 */
	public static final String CONFIGURED_SOURCE_FORMAT_NOT_USED = "configured-source-format-not-used";

	/** What the loader did about a finding — see the class javadoc for why they differ per rule. */
	public enum Remedy {

		/**
		 * The data was left exactly as loaded; only whoever owns the file can fix it — the operator for
		 * their own dataset, and the upstream project for the one the module ships, which is the
		 * distinction {@link #logTo(Logger, String)} reports at the level of.
		 */
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

		private final int occurrences;

		private final String detail;

		private Finding(String rule, Remedy remedy, int occurrences, String detail) {
			this.rule = rule;
			this.remedy = remedy;
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

	private void report(String rule, Remedy remedy, int occurrences, String detail) {
		findings.add(new Finding(rule, remedy, occurrences, detail));
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
	 * Reports every finding at WARN, one line each — the form for a caller that does not know which
	 * dataset was read, which is every caller that has no load to report into: the two parsers'
	 * one-argument {@code parse} forms, and {@link CrossReactivityGroupsLoader}. Unknown provenance is
	 * reported loudly on purpose; {@link #logTo(Logger, String)} is what softens, and only on evidence.
	 *
	 * <p>Owned here so no caller can come to report findings differently.
	 */
	void logTo(Logger log) {
		logTo(log, null);
	}

	/**
	 * Reports every finding, at the level the party who can ACT on it can be expected to read.
	 *
	 * <p><b>A finding about the DATA scales with who owns the dataset</b> ({@link #DATA_RULES}). Read
	 * from the application data directory it describes the operator's own file, which they can fix, and
	 * it is WARN. Read from the module's own classpath it describes the knowledge base the module
	 * SHIPS — since ADR Decision 36 that is the default — and no operator can fix it: the remedy is the
	 * upstream handoff issue #196 records, so it is INFO. Reporting it at WARN on every install of every
	 * deployment is the "noise every install learns to ignore" this whole class is written to avoid, and
	 * the shipped knowledge base trips two of these rules on 19 of its 2283 rows.
	 *
	 * <p><b>A finding about the CONFIGURATION never scales.</b> It names a choice the operator made and
	 * can unmake, so it is WARN wherever the entries came from — and keying the softening on the rule
	 * rather than on the origin alone is exactly what keeps that true: issue #156's finding fires when
	 * the operator's file was NOT read and the bundled dataset WAS, so an origin-only rule would silence
	 * the one case issues #149 and #154 exist for. Anything not named in {@link #DATA_RULES} is loud,
	 * including a rule added later and not classified — silence about an operator's mistake is the worse
	 * failure of the two, so the default direction is loud.
	 *
	 * <p><b>The status channel is not scoped with the log.</b> {@link #getFindings()} and
	 * {@link Finding#toMap()} carry every finding identically either way, so
	 * {@code GET /chartsearchai/drugreferencestatus} answers the same question after the load whichever
	 * dataset was read. That is what makes this a statement about AUDIENCE rather than a muted finding:
	 * the level says who can act, the status says what is true. Do not "fix" an apparent inconsistency
	 * by scoping the status too.
	 *
	 * @param origin the load's {@link ReferenceDataFiles.Loaded#getOrigin()}, or null where the caller
	 *        has none — which is read as unknown provenance and therefore as loud
	 */
	void logTo(Logger log, String origin) {
		boolean weShipIt = ReferenceDataFiles.isBundledOrigin(origin);
		for (Finding found : findings) {
			if (weShipIt && scopedToWhoOwnsTheDataset(found.getRule())) {
				log.info("Drug-reference data validity — {} (in the dataset the module ships, so the "
						+ "remedy is a data fix upstream rather than a change to this deployment)", found);
			}
			else {
				log.warn("Drug-reference data validity — {}", found);
			}
		}
	}

	/**
	 * The rules whose subject is the DATA, and so the ones {@link #logTo(Logger, String)} may report
	 * without being loud when the dataset is the one the module ships. Everything else — the two
	 * {@code configured*} rules today — describes the operator's configuration and is always loud.
	 *
	 * <p>Named as a list of what may soften rather than of what may not, so that a rule added later and
	 * left unclassified stays loud. {@link #DATASET_MISSING_A_REQUIRED_TABLE} is in here even though on a
	 * bundled dataset it would mean a packaging defect rather than an operator's file: that case loads
	 * zero entries, and {@link DrugReferenceLoad#isInert()}'s own unconditional WARN in
	 * {@link DrugReferenceService} is what makes it loud, which is where the catastrophic case belongs.
	 */
	private static final Set<String> DATA_RULES = Collections.unmodifiableSet(
			new LinkedHashSet<String>(Arrays.asList(BLANK_ALIAS, ENTRY_NOT_NAMED_BY_ITS_OWN_ALIASES,
					RULES_WITHOUT_A_SUBSTANCE_IDENTITY, ALIAS_NAMES_ANOTHER_SUBSTANCE,
					DERIVATIVE_MERGED_WITH_ITS_PARENT_SUBSTANCE, DATASET_MISSING_A_REQUIRED_TABLE,
					SELF_PAIRED_INTERACTION_ROWS)));

	/**
	 * @return whether {@code rule}'s subject is the DATA, and so whether {@link #logTo(Logger, String)}
	 *         may report it without being loud when the dataset is the one the module ships. A rule this
	 *         answers {@code false} for is loud everywhere — see {@link #DATA_RULES} for why the default
	 *         direction is that way round.
	 *
	 *         <p>Exposed rather than inlined so the classification can be GUARDED: a rule added later and
	 *         left out of that list would be loud on the shipped dataset, which is the safe direction but
	 *         still the wrong register, and nothing about it would fail.
	 *         {@code DrugReferenceFindingLoudnessTest} is what makes an unclassified rule fail the build.
	 */
	static boolean scopedToWhoOwnsTheDataset(String rule) {
		return DATA_RULES.contains(rule);
	}

	// ------------------------------------------------------------------
	// Document rules, over the file a parser read (issue #242)
	// ------------------------------------------------------------------

	/**
	 * Issue #242: the document omits a table the parser reading it requires, so that parser produced no
	 * entries from a file that is not empty.
	 *
	 * <p><b>Why the existing loud thing does not cover it.</b> {@link DrugReferenceLoad#isInert()} sees
	 * the OUTCOME — a source was selected and produced zero entries — and issue #149's WARN fires on it.
	 * What neither can do is say why, because by then the document is gone: that WARN offers a
	 * format/path mismatch as the usual cause, which is a guess, and the findings channel an operator can
	 * poll after a lazy load (#154) said nothing at all. Only the parser knows the file declared drug
	 * rows and no interaction table, and that is the difference between "your file is empty" and "your
	 * file's content was discarded".
	 *
	 * <p><b>REPORTED, and not either of the other two remedies.</b> Repairing it means reading a table
	 * the document never declared as an empty one, which is the single thing the loader cannot know here:
	 * a catalogue that carries no interactions and an export truncated before it wrote them are the same
	 * document to this parser, and loading the second as the first would put a plausible entry count on a
	 * file missing most of itself — {@link #CONFIGURED_DATA_FILE_NOT_READ}'s shape, arrived at from the
	 * other side. So the count stays 0, which says plainly that nothing loaded, and the finding says what
	 * to add. Refusing the file is not available either, for the reason no rule here refuses one.
	 *
	 * <p>Stated over "a table THIS parser requires" rather than over one schema, because the same silence
	 * exists in both directions, and which one is likelier moved with the default: while it was {@code json},
	 * an untouched {@code sourceFormat} beside a {@code dataFilePath} naming a DDInter export handed a
	 * DDInter document to the curated parser, and that is the direction this rule was written for. Since
	 * ADR Decision 36 the untouched case is {@code ddinter}, so the likely mismatch is now the mirror of
	 * it — an operator's curated file read by the DDInter parser — which is why the rule is stated over
	 * "a table THIS parser requires" and covers both without naming either schema.
	 * Issue #156's rule is correctly silent there — {@code json} and {@code ddinter} both name real
	 * adapters, so nothing was overridden — and the mismatch is between the format and the FILE, which
	 * only the parser can observe.
	 *
	 * <p><b>The residual, named rather than left to be rediscovered.</b> The {@code atc} format is not
	 * covered and cannot be by this rule: its dataset is line-based, so there is no table to declare or
	 * omit, and a file of another format read by it yields nothing for a different reason — no line
	 * matched an ATC code. That is a rule of its own to state, and it would have to be reported through a
	 * channel {@link AtcDrugReferenceSource} does not have either: it resolves its file itself rather
	 * than through {@link ReferenceDataFiles}, so no collector reaches it. Such a load is still loud, via
	 * {@link DrugReferenceLoad#isInert()}; what it lacks is the diagnosis.
	 *
	 * @param format the source format whose parser read the document, in the vocabulary of
	 *        {@code chartsearchai.drugReference.sourceFormat}
	 * @param missing the required tables the document does not declare, named as that format names them;
	 *        nothing is reported when it is empty
	 * @param rowsCarried how many rows the document did carry and the parser therefore discarded, or 0
	 *        where it carried nothing this parser could count — which is itself the distinction between a
	 *        mis-shaped file of this format and a file of another one
	 */
	void datasetMissingARequiredTable(String format, List<String> missing, int rowsCarried) {
		if (missing == null || missing.isEmpty()) {
			return;
		}
		report(DATASET_MISSING_A_REQUIRED_TABLE, Remedy.REPORTED, missing.size(),
				"a '" + format + "' document must declare " + missing + "; this one does not, so it "
						+ "parsed to no entries at all"
						+ (rowsCarried > 0 ? ", discarding the " + rowsCarried + " row(s) it does carry."
								: ".")
						+ " The data is left as loaded — the fix is in the file: either it is not a "
						+ "document of the format in force, or, where a catalogue carrying no rules is "
						+ "intended, the missing table has to be declared empty. Reading it as empty here "
						+ "would load an export truncated before it wrote that table as a complete one.");
	}

	/**
	 * Issues #152 and #164: interaction rows the parser dropped because both sides are the same
	 * substance — see {@link #SELF_PAIRED_INTERACTION_ROWS}.
	 *
	 * <p><b>DROPPED, and the count is the whole report.</b> A row asserting that a drug interacts with
	 * itself carries no clinical claim to preserve, so the offending value is the row and the rest of the
	 * dataset is untouched — the same shape as {@link #BLANK_ALIAS}. Per row rather than per rule instance
	 * because the count is what a maintainer compares across refreshes; the shipped knowledge base has 28
	 * such rows among its 295,184.
	 *
	 * <p>Which rows those are is decided by {@code DdiDrugReferenceSource.isSelfPair} and not restated
	 * here: it needs the dataset's own substance registry, which only the parser holds.
	 *
	 * @param rows how many rows were dropped; nothing is reported when it is zero
	 */
	void rowsPairingASubstanceWithItself(int rows) {
		if (rows <= 0) {
			return;
		}
		report(SELF_PAIRED_INTERACTION_ROWS, Remedy.DROPPED, rows,
				rows + " interaction row(s) pair a drug with itself or with another route/formulation "
						+ "row of the same substance, and a drug cannot interact with itself, so they were "
						+ "dropped. The fix is in the dataset; the count is how a refresh introducing more "
						+ "of them becomes visible.");
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
		report(CONFIGURED_DATA_FILE_NOT_READ, Remedy.REPORTED, 1,
				globalProperty + " names '" + configured + "', which could not be read, so '" + origin
						+ "' is in force instead. The entry count is therefore a count of a dataset "
						+ "nobody configured, and looks healthy.");
	}

	/**
	 * Issue #156, case 2: the parser the operator named was not the one used — see
	 * {@link #CONFIGURED_SOURCE_FORMAT_NOT_USED}. Quiet on the same terms as the file rule: an unset or
	 * correctly-spelled format overrode nothing, and {@code atc}/{@code ddinter} are matched
	 * case-insensitively exactly as {@code DrugReferenceService.effectiveFormat} matches them.
	 */
	void configuredSourceFormatNotUsed(String configured, String effective) {
		if (configured == null || configured.trim().isEmpty()
				|| configured.equalsIgnoreCase(effective)) {
			return;
		}
		report(CONFIGURED_SOURCE_FORMAT_NOT_USED, Remedy.REPORTED, 1,
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
		reportDerivativesMergedWithTheirParent(entries);
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
			report(BLANK_ALIAS, Remedy.DROPPED, blanks,
					blanks + " alias(es) naming nothing (blank, or nothing but combining marks) were "
							+ "dropped: such a token matches at a word boundary in text it has nothing to "
							+ "do with, so the entry's rules would fire for a patient with an unrelated "
							+ "allergy. Entries: " + sample(blankIn));
		}
		if (unnamed > 0) {
			report(ENTRY_NOT_NAMED_BY_ITS_OWN_ALIASES, Remedy.REPAIRED, unnamed,
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
			report(RULES_WITHOUT_A_SUBSTANCE_IDENTITY, Remedy.REPORTED, atRisk,
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
			report(ALIAS_NAMES_ANOTHER_SUBSTANCE, Remedy.REPORTED, collisions,
					collisions + " published name(s) denote a DIFFERENT substance in this dataset, so a "
							+ "question or a chart string carrying one resolves the wrong drug. The data "
							+ "is left as loaded — the fix is in the dataset. " + sample(details));
		}
	}

	/**
	 * The check for issue #196 item 4: a row the dataset merged into a substance its OWN name says it
	 * is only a DERIVATIVE of.
	 *
	 * <p><b>Why the rule above cannot see this, structurally.</b>
	 * {@link #reportAliasesNamingAnotherSubstance} reports a published name denoting a DIFFERENT
	 * substance, and its first exclusion is that the two entries key alike — so where the merge itself
	 * is the defect there is no different substance left for it to find. The shipped case:
	 * {@code Fluoroestradiol f-18} publishes {@code rxnorm_name: estradiol} and carries no
	 * {@code drugbank_id}, so {@link DdiDrugReferenceSource}'s per-family resolution hands it
	 * {@code Estradiol}'s {@code DB00783} and a PET imaging tracer and a therapeutic oestrogen become one
	 * substance to {@link DrugReference#substanceKey()}. Measured over the shipped 19 MB KB 2026-08-13 by
	 * driving {@link DrugReferenceService#getLoadStatus()} over it: that rule fires 18 times and this row
	 * is in none of them.
	 *
	 * <p><b>The criterion, and why it is not "one substance, unlike names".</b> That broader predicate was
	 * measured first — {@link DrugReference#substanceGroupKey()} over the shipped KB, then
	 * {@link DrugReference#displayStem}/{@link DrugReference#containsWord} between each family's rows —
	 * and it fires on 15 rows across 8 families — a set that includes BOTH merges issue #164 measured as
	 * CORRECT, {@code Daxibotulinumtoxina} with {@code Botulinum toxin type A} and
	 * {@code Pfizer-BioNTech Covid-19 Vaccine} with {@code Tozinameran}. A rule reporting those would
	 * tell an operator that this module's own correct behaviour is a data defect. So the criterion is the relationship the row's own NAME states rather than the absence of
	 * one: a name that carries the substance's name WITHOUT a word boundary is a derivative — fluoro-,
	 * levo-, dex-, es-, nor- — and this module already reads it that way everywhere else.
	 * {@link DrugReference#containsWord} is exactly what separates {@code Levoketoconazole} from
	 * {@code Ketoconazole} and {@code Esomeprazole} from {@code Omeprazole} in every matcher here. So the
	 * dataset's registry is contradicting the module's own name rule, and it is the registry's silence
	 * that let it: those two pairs are kept apart only because each derivative carries its OWN DrugBank
	 * id, which withholds the family's id and drops the verdict to the display stem (issue #164). This
	 * rule is the residue of that mechanism — the merges that survived because the derivative row named
	 * no substance at all.
	 *
	 * <p><b>Both conditions exclude a shape that is CORRECT</b>, which is what keeps this off the list of
	 * channels operators filter:
	 * <ul>
	 *   <li>the family must hold a row the derivative could have been merged WITH — one that is not
	 *       itself a derivative of the same claim ({@link #holdsAParent}). That excludes both a lone row
	 *       claiming a substance name, which confuses nothing and whose wrong {@code rxnorm_name} is the
	 *       shape issue #196 records as undetectable from inside the file, and a derivative the module
	 *       has correctly kept APART from its parent that has route variants of its OWN;</li>
	 *   <li>the name must carry the substance's own name as a bounded WORD to be excluded, so every
	 *       presentation, salt and ester is read as what it is rather than as a derivative. That is not
	 *       a rare shape: 12 rows of the shipped KB carry their substance's name as a bounded word
	 *       ({@code Beclomethasone dipropionate} under {@code beclomethasone},
	 *       {@code Estrone sulfate} under {@code estrone}, the four
	 *       {@code Human immunoglobulin G} routes under {@code immunoglobulin g}), measured through
	 *       {@link DrugReference#displayStem}/{@link DrugReference#containsWord} over the file.</li>
	 * </ul>
	 * Folded through {@link DrugReference#foldDiacritics} before the substring half so that half and
	 * {@link DrugReference#containsWord} read the same alphabet; unfolded, a localized dataset would be
	 * quieter about a real merge than an ASCII one. No shipped row can witness that — measured
	 * 2026-08-13, none of the 2283 carries a non-ASCII character in {@code name} or {@code rxnorm_name}
	 * — so {@code ddi-derivative-rule-edges.json} is hand-authored and says so.
	 *
	 * <p><b>Which datasets this can fire on at all, and why it is one.</b> Reporting needs a family
	 * holding both a derivative row and a row that is not one, so it needs
	 * {@link DrugReference#substanceKey()} to be shared by rows whose display STEMS differ. Only a
	 * resolved substance registry id can do that: where the key falls back to the stem — the curated
	 * {@code json} schema, which publishes no registry — every row of a family has the same stem AND
	 * the same substance name, so {@link #derivesFromItsOwnSubstance} is constant across it and the
	 * family is either wholly derivative (no parent, skipped) or wholly not (nothing to report). The
	 * {@code atc} adapter publishes no substance name at all, so every row is its own family. Today
	 * that leaves the {@code ddinter} adapter, and this is a derivation from the two gates rather than
	 * a list of formats to maintain.
	 *
	 * <p><b>REPORTED.</b> Splitting the rows would be inventing the fact the data is missing — the
	 * loader cannot tell a derivative that is a different substance from one the registry would confirm
	 * is the same, which is the judgement issues #164 and #192 measured this module must not make — and
	 * dropping the row would lose real interaction rules (the tracer carries four of its own). Both fail
	 * closed, silently, to fix a fail-loud. The fix is a substance registry id on the derivative row, and
	 * this is the check that finds the rows to hand upstream.
	 *
	 * <p>Measured over the shipped 19 MB KB 2026-08-13, by this method through
	 * {@link DrugReferenceService#getLoadStatus()}: <b>one</b> row, {@code Fluoroestradiol f-18}.
	 * Re-measure before relying on the figure.
	 */
	private void reportDerivativesMergedWithTheirParent(List<DrugReference> entries) {
		Map<Object, List<DrugReference>> bySubstance = new LinkedHashMap<Object, List<DrugReference>>();
		for (DrugReference entry : entries) {
			Object key = entry.substanceGroupKey();
			List<DrugReference> rows = bySubstance.get(key);
			if (rows == null) {
				rows = new ArrayList<DrugReference>(2);
				bySubstance.put(key, rows);
			}
			rows.add(entry);
		}
		int merged = 0;
		Set<String> details = new LinkedHashSet<String>();
		for (List<DrugReference> rows : bySubstance.values()) {
			if (!holdsAParent(rows)) {
				continue;
			}
			for (DrugReference row : rows) {
				if (!derivesFromItsOwnSubstance(row)) {
					continue;
				}
				merged++;
				details.add(row.getName() + " is filed as '" + row.getSubstanceName() + "' beside "
						+ sample(namesOfOthers(rows, row)));
			}
		}
		if (merged > 0) {
			report(DERIVATIVE_MERGED_WITH_ITS_PARENT_SUBSTANCE, Remedy.REPORTED, merged,
					merged + " row(s) are keyed as ONE substance with rows their own name says they only "
							+ "DERIVE from, so a chip, a record or a citation about the substance can be "
							+ "named after the derivative and carry its rules. The data is left as loaded "
							+ "— the fix is in the dataset, which has to stop filing the derivative under "
							+ "the parent's substance: in a DDInter-shaped file by giving it its own "
							+ "drugbank_id, which is what keeps every derivative this module already "
							+ "separates apart, and in a curated file by giving it its own substanceName. "
							+ sample(details));
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

	/**
	 * @return whether {@code rows} holds a row the derivatives among them could have been merged WITH:
	 *         one that is not itself a derivative of the substance they all claim. This is the family
	 *         gate, and a size check is not it — a derivative the module has correctly kept APART from
	 *         its parent still forms a family with its OWN route variants, and reporting those states a
	 *         merge that never happened. {@code Levoketoconazole} carries its own DrugBank id against
	 *         {@code Ketoconazole}'s, so the family's id is withheld and the keys fall to the display
	 *         stem — which puts {@code Levoketoconazole} and a {@code (oral)} variant of it in one
	 *         family with no parent in it, and without this gate both are reported, each naming the
	 *         other. Measured through this rule on {@code ddi-derivative-rule-edges.json}: 3
	 *         occurrences without the gate, 1 with it.
	 *
	 *         <p>Strictly narrower than the size check it replaced, which is why it can only remove
	 *         findings: a reported row derives, this needs one that does not, and the two cannot be the
	 *         same row.
	 */
	private static boolean holdsAParent(List<DrugReference> rows) {
		for (DrugReference row : rows) {
			if (!derivesFromItsOwnSubstance(row)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return whether this row's display stem names a DERIVATIVE of the substance the data filed it
	 *         under: it carries that substance's name, and not as a bounded word. Two conditions
	 *         because there are three answers, and {@link DrugReference#containsWord} rules out two of
	 *         them at once — the stem that IS the substance name (a row of it, {@code Estradiol}) and
	 *         the stem that carries it as a WORD (a presentation, salt or ester of it,
	 *         {@code Beclomethasone dipropionate} under {@code beclomethasone}). What the substring test
	 *         then rules out is the third: a stem not carrying the name at all is a second NAME for the
	 *         substance, which issue #164 measured as one substance ({@code Daxibotulinumtoxina}). Only
	 *         what survives both is a derivative.
	 *
	 *         <p>Gated on the substance name being a name at all, through this class's own
	 *         {@link #namesAnything}, because that is where the two tests can disagree: a token with no
	 *         letter or digit is non-blank to {@link DrugReference#normalizeName}, and
	 *         {@code containsWord} refuses it while {@code String.contains} finds it in whichever stems
	 *         happen to carry the character. An {@code rxnorm_name} of {@code "-"} shared by
	 *         {@code Bupivacaine hcl-2} and {@code Marcaine} therefore reports the first as deriving
	 *         from {@code "-"} — it fails OPEN, which is the direction this class does not accept.
	 *         A token of nothing but combining marks is NOT that witness, though it looks like one: it
	 *         folds to empty, {@code contains("")} is true of every stem, so every row of the family
	 *         derives and {@link #holdsAParent} silences it before this gate is reached.
	 */
	private static boolean derivesFromItsOwnSubstance(DrugReference row) {
		String substance = DrugReference.normalizeName(row.getSubstanceName());
		if (substance == null || !namesAnything(substance)) {
			return false;
		}
		String stem = DrugReference.displayStem(row.getName());
		return !DrugReference.containsWord(stem, substance)
				&& DrugReference.foldDiacritics(stem).contains(DrugReference.foldDiacritics(substance));
	}

	/** @return the display names of the other rows of one substance, for a finding that has to say what
	 *          a row was merged WITH. Handed to {@link #sample} by the caller rather than joined here,
	 *          so one report's bound covers the occurrences AND the names inside each of them: a
	 *          mis-keyed family can be as large as the dataset, and an unbounded list nested inside a
	 *          bounded one is the same log line {@link #DETAIL_SAMPLE} exists to prevent. The whole set
	 *          is still built — the bound is on what is EMITTED, not on what is collected, because
	 *          {@link #sample}'s "and N more" has to count the ones it does not name. */
	private static Set<String> namesOfOthers(List<DrugReference> rows, DrugReference row) {
		Set<String> others = new LinkedHashSet<String>();
		for (DrugReference other : rows) {
			if (other != row) {
				others.add(other.getName());
			}
		}
		return others;
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
