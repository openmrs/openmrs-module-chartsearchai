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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The outcome of the drug-reference dataset load that is <em>in force</em>: which source format was
 * selected, which file the entries were actually read from, and how many there are. Immutable, and
 * built at the moment the load populates {@link DrugReferenceService}'s cache, so it can never
 * describe a different dataset than the one the safety layer is using.
 *
 * <p>Why this is a retained value and not just a log line (issue #149): the load is lazy, so a
 * reader who greps the log for "which dataset is in force?" can be handed the line from a previous
 * load or a previous process. That is not hypothetical — a verification pass switched
 * {@code sourceFormat} to {@code json}, failed to restart the module, read a stale
 * {@code "Loaded 2283 …"} line and concluded the switch had taken effect. Exposed through
 * {@link DrugReferenceService#getLoadStatus()} and the module's
 * {@code GET /chartsearchai/drugreferencestatus} endpoint, both of which report this object rather
 * than re-deriving it from the global properties, so what it says is what is loaded. (The endpoint
 * adds one field of its own, {@code enabled}, which IS a live read — the master switch can be
 * flipped after a load, and then it is meant to disagree with {@link #isLoaded()}.)
 *
 * <p>{@link #isInert()} is the single verdict that distinguishes the two states an empty dataset can
 * be in, and it drives BOTH the WARN at load time and the reported status, so the two cannot drift:
 *
 * <ul>
 * <li><b>Not loaded</b> ({@link #notLoaded()}) — nothing has been loaded, because nothing asked:
 * {@code chartsearchai.drugReference.enabled} is off, so neither {@code DrugSafetyValidator} nor
 * {@code DrugReferenceInjector} reaches the service. A legitimate state (it is the default), so it
 * is silent — warning here would spam every install that does not use the feature.</li>
 * <li><b>Inert</b> — a source WAS selected and loading it produced zero entries. The drug-safety
 * feature is then off while looking healthy: no interaction, allergy or contraindication warning can
 * be raised, and every safety question answers as though there were nothing to find. That is the
 * defect, and it is loud.</li>
 * </ul>
 *
 * <p><b>{@link Arm} coverage is that same question at finer grain</b> (issue #285), and it is a
 * separate one because a dataset can be entirely healthy by {@link #isInert()} and still leave whole
 * safety arms with nothing to act on. The shipped default is exactly that: 2283 entries load, the
 * interaction arms work, and DDInter publishes no age band and no hand-authored allergy/condition
 * rule at all — so {@code chartsearchai.drugSafety.warnOnDoseExcess} reads {@code true} over a check
 * that can never fire.
 *
 * <p>Prose already said so in several places, and one of them IS readable at runtime — a module's
 * {@code config.xml} property descriptions are persisted to {@code global_property.description} and
 * served by core's {@code /systemsetting}, so "nobody could read it" would be false. What no prose can
 * say is the thing an operator actually needs: not what the shipped DEFAULT lacks, but what THIS
 * install's configured dataset publishes. Point {@code dataFilePath} at your own file and every one of
 * those statements is silent about it, while this report answers from the entries that were loaded.
 *
 * <p>Each arm reports a {@link Coverage} verdict and, beside it, the number of entries publishing what
 * that arm needs. The verdict is not derivable from the count, which is the whole reason it exists:
 * {@link #notLoaded()} zeroes every field, so {@code 0} is what both "nothing was read" and "a dataset
 * was read and publishes none" look like — the {@code count of 0 printed as cheerfully as 2283}
 * failure ADR Decision 32 was written against, one level down.
 *
 * <p>This is deliberately NOT a {@link DrugReferenceValidity} finding. A dataset that publishes no
 * dosing is VALID here, and the suite says so in three places: an operator's dosing-less file must
 * load without a WARN ({@code DrugReferenceLoadContextTest.healthyLoadIsNotReportedAtWarnOrError}),
 * and two fixtures with no age bands must report no finding at all
 * ({@code DrugReferenceValidityContextTest.declaringTheSubstanceKeepsEveryRuleBearingRowAndSilencesTheFinding},
 * {@code ...theSameDocumentDeclaringAnEmptyInteractionsTableLoadsItsDrugsAndSaysNothing}). Absent
 * dosing is inside the boundary by design — ADR Decision 24's matrix records that no free
 * authoritative dataset publishes dosing maxima — so this reports a CAPABILITY, not a defect.
 */
public final class DrugReferenceLoad {

	/**
	 * A safety arm whose availability depends on what the loaded dataset publishes, with the key it
	 * serializes under. The wire key lives on the constant so the endpoint's field names and this enum
	 * cannot drift apart.
	 *
	 * <p>Only arms a dataset can withhold are listed. A recorded allergy to the drug itself needs
	 * neither a class code nor a hand-authored rule, so no dataset can take it away and reporting it
	 * would say nothing.
	 */
	public enum Arm {

		/**
		 * A published dosing ceiling — the overdose chip, {@code SafetyWarning.TYPE_OVERDOSE}, gated by
		 * {@code chartsearchai.drugSafety.warnOnDoseExcess}. An age band with no ceiling does not count;
		 * see {@link DrugSafetyValidator#publishesACeiling}.
		 */
		DOSE_CEILINGS("doseCeilings"),

		/**
		 * Hand-authored allergy/condition rules the module can actually put to a chart — PART of
		 * {@code SafetyWarning.TYPE_CONTRAINDICATION}, gated by
		 * {@code chartsearchai.drugSafety.warnOnContraindications}. Part, not all: a recorded allergy to
		 * the drug itself needs no rule and no code, so {@code absent} here does NOT mean
		 * contraindication checking is dead.
		 */
		HAND_AUTHORED_RULES("handAuthoredRules"),

		/**
		 * Class codes reduced to the level-4 subgroup the class arms actually compare — the
		 * shared-subgroup contraindication arm and duplicate therapy. Counted through
		 * {@link DrugReference#atcSubgroups()} and not the raw codes, because that reduction is what the
		 * subgroup comparison in both arms consumes.
		 *
		 * <p>This counts what the dataset PUBLISHES for the comparison, which is not the same as
		 * whether a chip is reachable for a given entry, and it can disagree in both directions. Under,
		 * because those arms have a second leg that prefix-matches the RAW codes against the curated
		 * cross-reactivity groups: an entry whose codes are all too short to reduce counts 0 here while
		 * that leg can still match, given a deployment that added a correspondingly short prefix. Over,
		 * because both legs then discard a shared subgroup the relevance veto rejects, so an entry whose
		 * subgroups are all vetoed and which belongs to no curated group is counted here although
		 * neither leg can fire on it. Read it as "the dataset publishes codes the class comparison can
		 * use", never as "a class chip is reachable for this entry".
		 */
		ATC_CODES("atcCodes"),

		/**
		 * Pairwise interaction rules published by the dataset — PART of
		 * {@code SafetyWarning.TYPE_INTERACTION}, gated by
		 * {@code chartsearchai.drugSafety.warnOnInteractions}. Part, not all, the same caveat
		 * {@link #HAND_AUTHORED_RULES} carries: the class legs also emit that chip type from ATC
		 * subgroups and the curated cross-reactivity groups, so {@code absent} here does NOT mean no
		 * interaction warning can be raised. Listed because a dataset can withhold these outright —
		 * {@code sourceFormat=atc} sets only class codes.
		 */
		INTERACTIONS("interactions");

		private final String wireKey;

		Arm(String wireKey) {
			this.wireKey = wireKey;
		}

		/** @return the key this arm serializes under in {@link DrugReferenceLoad#toMap()}. */
		public String getWireKey() {
			return wireKey;
		}
	}

	/** What the loaded dataset can do for one {@link Arm}. Three states, not two — see the class javadoc. */
	public enum Coverage {

		/** Nothing was loaded, so nothing is known about this arm. Never confuse this with {@link #ABSENT}. */
		UNLOADED,

		/** A dataset was loaded and at least one entry publishes what this arm needs. */
		PUBLISHED,

		/** A dataset was loaded and NO entry publishes what this arm needs: the arm cannot fire. */
		ABSENT;
	}

	private final boolean loaded;

	private final String sourceFormat;

	private final String configuredSourceFormat;

	private final String configuredDataFilePath;

	private final String origin;

	private final int entryCount;

	private final List<DrugReferenceValidity.Finding> findings;

	private final Map<Arm, Integer> armCounts;

	/**
	 * Takes the loaded entries rather than their count, so the count and the per-arm verdicts are
	 * derived from the same list and cannot be handed in disagreeing with each other.
	 */
	DrugReferenceLoad(String sourceFormat, String configuredSourceFormat, String configuredDataFilePath,
			String origin, List<DrugReference> entries,
			List<DrugReferenceValidity.Finding> findings) {
		List<DrugReference> loadedEntries = entries == null
				? Collections.<DrugReference> emptyList() : entries;
		this.loaded = true;
		this.sourceFormat = sourceFormat;
		this.configuredSourceFormat = configuredSourceFormat;
		this.configuredDataFilePath = configuredDataFilePath;
		this.origin = origin == null ? ReferenceDataFiles.ORIGIN_NONE : origin;
		this.entryCount = loadedEntries.size();
		this.armCounts = countArms(loadedEntries);
		this.findings = findings == null ? Collections.<DrugReferenceValidity.Finding> emptyList()
				: Collections.unmodifiableList(
						new ArrayList<DrugReferenceValidity.Finding>(findings));
	}

	private DrugReferenceLoad() {
		this.loaded = false;
		this.sourceFormat = null;
		this.configuredSourceFormat = null;
		this.configuredDataFilePath = null;
		this.origin = null;
		this.entryCount = 0;
		this.findings = Collections.emptyList();
		this.armCounts = countArms(Collections.<DrugReference> emptyList());
	}

	/**
	 * Counts, per arm, the entries publishing what that arm needs — through the production predicate for
	 * each, never a local re-expression of it.
	 *
	 * <p>{@link DrugSafetyValidator#evaluatesAgainstTheChart} rather than a non-empty contraindication
	 * list, because that is the documented answer to "could the module even ask?": a rule typed neither
	 * {@code allergy} nor {@code condition}, or carrying no matchable token, is unaskable. Counting the
	 * list instead would publish capability the arm does not have, which is the "looks healthy, checks
	 * nothing" state this whole report exists to remove.
	 *
	 * <p>The dosing count asks {@link DrugSafetyValidator#publishesACeiling}, the patient-independent
	 * half of that arm's own gate, rather than whether the entry has age bands at all. Only the half is
	 * decidable here — {@code actionableBand} additionally selects the band by AGE, and its mg/kg leg
	 * needs a WEIGHT, neither of which exists at load time — but the half that is decidable is exactly
	 * the one that matters: a band publishing no ceiling can never fire for any patient, so counting
	 * bands by presence would report a dosing arm over a dataset that publishes no dosing.
	 */
	private static Map<Arm, Integer> countArms(List<DrugReference> entries) {
		Map<Arm, Integer> counts = new EnumMap<Arm, Integer>(Arm.class);
		for (Arm arm : Arm.values()) {
			counts.put(arm, Integer.valueOf(0));
		}
		for (DrugReference entry : entries) {
			if (entry == null) {
				continue;
			}
			if (publishesACeiling(entry)) {
				increment(counts, Arm.DOSE_CEILINGS);
			}
			if (publishesAnAskableRule(entry)) {
				increment(counts, Arm.HAND_AUTHORED_RULES);
			}
			if (!entry.atcSubgroups().isEmpty()) {
				increment(counts, Arm.ATC_CODES);
			}
			if (!entry.getInteractions().isEmpty()) {
				increment(counts, Arm.INTERACTIONS);
			}
		}
		return Collections.unmodifiableMap(counts);
	}

	private static void increment(Map<Arm, Integer> counts, Arm arm) {
		counts.put(arm, Integer.valueOf(counts.get(arm).intValue() + 1));
	}

	private static boolean publishesACeiling(DrugReference entry) {
		for (DrugReference.AgeBand band : entry.getAgeBands()) {
			if (band != null && DrugSafetyValidator.publishesACeiling(band)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The null-element skip is not defensive noise: this is the first thing to dereference a
	 * contraindication ELEMENT at load time, and the parsers drop only null ENTRIES, so an operator file
	 * containing {@code "contraindications": [null]} reaches here. Without the check it throws inside
	 * {@code ensureLoaded}, which has no catch — the cache stays null, every later call re-throws behind
	 * the answer path's own catches, and the status endpoint that exists to diagnose a bad load 500s
	 * instead. A null rule is also, plainly, not one the module can ask.
	 */
	private static boolean publishesAnAskableRule(DrugReference entry) {
		for (DrugReference.Contraindication rule : entry.getContraindications()) {
			if (rule != null && DrugSafetyValidator.evaluatesAgainstTheChart(rule)) {
				return true;
			}
		}
		return false;
	}

	/** @return the outcome for "no load has happened", which is not a failure — see the class javadoc. */
	static DrugReferenceLoad notLoaded() {
		return new DrugReferenceLoad();
	}

	/** @return whether the dataset has been loaded at all. */
	public boolean isLoaded() {
		return loaded;
	}

	/**
	 * @return true when a source was selected and loading it produced NO entries, so drug-safety
	 *         checking is inert. False both for a healthy load and for "not loaded at all".
	 */
	public boolean isInert() {
		return loaded && entryCount == 0;
	}

	/**
	 * @return the source format actually used ({@code json}, {@code atc} or {@code ddinter}); null
	 *         when not loaded. Differs from {@link #getConfiguredSourceFormat()} when the configured
	 *         value matches no adapter and the curated {@code json} parser was used instead — which
	 *         since ADR Decision 36 is not the default, so a typo now changes the dataset as well as
	 *         the parser, and is itself a way to end up inert.
	 */
	public String getSourceFormat() {
		return sourceFormat;
	}

	/**
	 * @return the configured {@code chartsearchai.drugReference.sourceFormat} value, which reads
	 *         {@code json} when the global property is unset or blank; null when not loaded.
	 */
	public String getConfiguredSourceFormat() {
		return configuredSourceFormat;
	}

	/**
	 * @return the raw {@code chartsearchai.drugReference.dataFilePath} value ({@code ""} when unset);
	 *         null when not loaded. What was ASKED for — compare with {@link #getOrigin()}, which is
	 *         what was read.
	 */
	public String getConfiguredDataFilePath() {
		return configuredDataFilePath;
	}

	/**
	 * @return where the entries were read from, each form naming the space it came from:
	 *         {@code appdata:<path within the application data directory>} for an operator file,
	 *         {@code classpath:<resource>} for the bundled dataset, or {@code none}. Null when not
	 *         loaded.
	 *
	 *         <p>Reported separately from {@link #getConfiguredDataFilePath()} because a configured
	 *         path that cannot be read falls back to the bundled dataset and yields a perfectly
	 *         plausible entry count — the state in which "the count is non-zero, so my file loaded"
	 *         is false. So a configured file loaded exactly when this reads {@code appdata:} + that
	 *         path.
	 *
	 *         <p>Deliberately not the absolute path: this is served to any caller holding the core
	 *         {@code Get Global Properties} privilege, which the {@code Authenticated} role holds by
	 *         default, and core keeps its own disclosure of the application data directory behind
	 *         {@code View Administration Functions}. The absolute path is still logged at INFO, where
	 *         the audience is already an administrator.
	 */
	public String getOrigin() {
		return origin;
	}

	/** @return the number of reference entries in force; 0 when not loaded. */
	public int getEntryCount() {
		return entryCount;
	}

	/**
	 * @return what the load-time validity check found wrong with this dataset and this configuration, and
	 *         what the loader did about each — see {@link DrugReferenceValidity}. Empty for a healthy
	 *         load and for "not loaded at all"; never null.
	 *
	 *         <p>Retained here rather than only logged for the reason the rest of this object is
	 *         (issue #149): the load is lazy, so the most recent WARN may belong to a previous load or a
	 *         previous process, and after a global-property flip that is exactly the line a verification
	 *         pass misreads. This describes the dataset the safety layer is using.
	 *
	 *         <p>On the wire too, through {@link #toMap()} — the endpoint's whole purpose is to answer
	 *         "what is actually loaded?" after a lazy load, and a load that dropped an alias, appended a
	 *         display name or fell back to the bundled file is exactly that question. Retaining these in
	 *         Java while withholding them from the only channel an operator can reach would make the check
	 *         visible to tests and invisible to the person it protects. Each finding serializes its
	 *         {@code rule}, {@code remedy} and {@code occurrences} rather than only a count, because a
	 *         bare count would recreate at this level the defect issue #149 fixed one level down, where a
	 *         load of 0 and a load of 2283 logged identically.
	 */
	public List<DrugReferenceValidity.Finding> getFindings() {
		return findings;
	}

	/**
	 * @return what the loaded dataset can do for {@code arm}. {@link Coverage#UNLOADED} whenever nothing
	 *         was loaded, so a caller can never read a zero count as "we looked and found none".
	 */
	public Coverage coverageOf(Arm arm) {
		if (!loaded) {
			return Coverage.UNLOADED;
		}
		return entriesPublishing(arm) > 0 ? Coverage.PUBLISHED : Coverage.ABSENT;
	}

	/**
	 * @return how many loaded entries publish what {@code arm} needs. Zero both when nothing was loaded
	 *         and when a dataset was loaded carrying none, which is why {@link #coverageOf(Arm)} exists
	 *         beside this rather than callers comparing this to zero.
	 */
	public int entriesPublishing(Arm arm) {
		Integer count = armCounts.get(arm);
		return count == null ? 0 : count.intValue();
	}

	/**
	 * @return this outcome as a JSON-serializable map, for the REST status endpoint. Insertion-ordered,
	 *         and a new key is always APPENDED — deliberately, so the existing keys keep the positions
	 *         the endpoint's frozen key list already pins and that list stays an ordered assertion
	 *         rather than becoming order-insensitive to accommodate a new field. {@code findings} joined
	 *         that way and {@code arms} after it; the rule is the append, not which key happens to be
	 *         last.
	 */
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<String, Object>();
		map.put("loaded", loaded);
		map.put("inert", isInert());
		map.put("entryCount", entryCount);
		map.put("sourceFormat", sourceFormat);
		map.put("configuredSourceFormat", configuredSourceFormat);
		map.put("configuredDataFilePath", configuredDataFilePath);
		map.put("origin", origin);
		List<Map<String, Object>> serialized =
				new ArrayList<Map<String, Object>>(findings.size());
		for (DrugReferenceValidity.Finding found : findings) {
			serialized.add(found.toMap());
		}
		map.put("findings", serialized);
		// Appended after findings, never inserted: the endpoint's field list is asserted as an ORDERED
		// list, and appending is what keeps that assertion order-sensitive instead of making it
		// order-insensitive the first time a key lands in the middle.
		Map<String, Object> arms = new LinkedHashMap<String, Object>();
		for (Arm arm : Arm.values()) {
			Map<String, Object> reported = new LinkedHashMap<String, Object>();
			reported.put("coverage", coverageOf(arm).name().toLowerCase(Locale.ROOT));
			reported.put("entriesPublishing", Integer.valueOf(entriesPublishing(arm)));
			arms.put(arm.getWireKey(), reported);
		}
		map.put("arms", arms);
		return map;
	}

	@Override
	public String toString() {
		return findings.isEmpty() ? toMap().toString() : toMap() + " findings=" + findings;
	}
}
