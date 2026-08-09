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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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

	/** The reference data's own canonical name for the SUBSTANCE, or null — see
	 *  {@link #getSubstanceName()}. */
	private String substanceName;

	/** The reference data's own IDENTITY for that substance, or null — see {@link #getSubstanceId()}. */
	private String substanceId;

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

	/**
	 * @return the reference data's own canonical name for the SUBSTANCE this entry is a row of — the
	 *         DDInter {@code rxnorm_name} — or null for a source that publishes none: the {@code atc}
	 *         adapter, which has no such field, and the shipped curated {@code json} dataset, whose
	 *         entries carry none. Null there is the dataset's silence, not a schema ban — the curated
	 *         schema binds this class directly, so a hand-authored file that sets this field opts into
	 *         the grouping below. Unlike {@link #getGenericName()}, which
	 *         is a chip-label synonym and is deliberately null whenever the display name already
	 *         contains it, this is set whether or not the two agree: it is an identity, not a label,
	 *         and it is exactly the field that is EQUAL across a substance's route/formulation rows
	 *         ({@code Dexamethasone}, {@code Dexamethasone (nasal)}, … all publish
	 *         {@code dexamethasone}). Consumed by {@link #substanceKey()}.
	 */
	public String getSubstanceName() {
		return substanceName;
	}

	public void setSubstanceName(String substanceName) {
		this.substanceName = substanceName;
	}

	/**
	 * @return the identity the reference data gives the SUBSTANCE this row is a row of, at a
	 *         granularity {@link #getSubstanceName()} does not have — or {@code null} where the data
	 *         cannot supply one. Written by the loading source, not by the dataset: it is a
	 *         determination over all the rows sharing a substance name, so no row can carry it on its
	 *         own. {@link DdiDrugReferenceSource} resolves it from the DDInter {@code drugbank_id};
	 *         {@link AtcDrugReferenceSource} and the curated {@code json} dataset publish no substance
	 *         registry and leave it null, which is why the fallback below has to be the one that was
	 *         there before.
	 *
	 *         <p>Deliberately package-private, unlike {@link #getSubstanceName()}: that field is part
	 *         of the curated schema and a hand-authored file may set it, while this one is a
	 *         source-side derivation and binding it from a file would let a dataset assert a
	 *         resolution nothing had made.
	 */
	String getSubstanceId() {
		return substanceId;
	}

	void setSubstanceId(String substanceId) {
		this.substanceId = substanceId;
	}

	/** A trailing parenthesized qualifier on a display name — the route or formulation a DDInter row
	 *  is distinguished from its siblings by ({@code Dexamethasone (nasal)},
	 *  {@code Amphotericin B (lipid complex)}, {@code Tozinameran (5y-11y)}). Anchored at the END, so a
	 *  parenthetical in the middle of a name is left alone. {@link #displayStem} applies it repeatedly,
	 *  so a name carrying more than one trailing qualifier reduces fully rather than partly. */
	private static final Pattern TRAILING_QUALIFIER = Pattern.compile("\\s*\\([^()]*\\)\\s*$");

	/**
	 * The substance-level identity of this entry, or {@code null} when the loaded source publishes no
	 * substance name and this entry can therefore only stand for itself.
	 *
	 * <p><b>What it is for.</b> One substance is filed as several rows, so one clinician-facing string
	 * resolves several entries and a per-entry safety chip becomes several chips for one clinical fact
	 * (issue #145 on the contraindication arms; #115/#121 solved the partner side of the same problem
	 * for interactions). This is the key those chips group on. It is deliberately NOT
	 * {@link #displayLabel()}: grouping on a rendered label is the mistake issue #148 had to undo.
	 *
	 * <p><b>Two components, each load-bearing, both measured over the shipped 19 MB KB (2283 entries;
	 * re-measure before relying on the figures).</b>
	 * <ul>
	 *   <li>{@link #getSubstanceName()} — the data's own claim that two rows are one substance. 142
	 *       values are shared by more than one entry, across 332 entries, and the {@code rxcui}
	 *       partitions those entries identically (0 families disagreeing in either direction), so this
	 *       is the dataset's substance identity rather than a spelling coincidence.</li>
	 *   <li>{@link #getSubstanceId()} — the data's own IDENTITY for that substance, which either
	 *       CONFIRMS the claim or withdraws it, because the claim over-merges. Among those 142 families
	 *       sit pairs of genuinely different substances: {@code Omeprazole}/{@code Esomeprazole} (one
	 *       {@code rxnorm_name}, one {@code rxcui}, one ATC code),
	 *       {@code Amphetamine}/{@code Dextroamphetamine}, {@code Fenfluramine}/{@code Dexfenfluramine},
	 *       {@code Gabapentin}/{@code Gabapentin enacarbil}, {@code Netupitant}/{@code Fosnetupitant},
	 *       {@code Ketoconazole}/{@code Levoketoconazole}, {@code Fenofibrate}/{@code Fenofibric acid},
	 *       {@code Atropine}/{@code Hyoscyamine}, {@code Hydrocortisone}/{@code Hydrocortisone butyrate},
	 *       {@code Estrone}/{@code Estrone sulfate} — each of them the
	 *       {@code enalapril}/{@code enalaprilat} shape issue #121 decided must stay two chips. 19 of the
	 *       142 families name two or more DrugBank substances and are exactly these. There the id is
	 *       withheld — {@link DdiDrugReferenceSource} sets none, because it cannot say which of the two
	 *       a given row is — and the veto falls to the DISPLAY STEM, which separates every one of
	 *       them.</li>
	 * </ul>
	 *
	 * <p><b>Why the stem is the fallback and not the veto (issue #164).</b> It used to be the veto, and
	 * it cannot tell a second SUBSTANCE from a second NAME: it separates the two PPIs correctly and
	 * separates {@code Tozinameran} from {@code Pfizer-BioNTech Covid-19 Vaccine} — one {@code rxcui},
	 * one {@code rxnorm_name}, one DrugBank substance — incorrectly, and likewise
	 * {@code Botulinum toxin type A} from {@code Daxibotulinumtoxina}. Both were reported live as a
	 * substance cross-reactive, or interacting, with itself. A substance registry can tell them apart
	 * and a display name cannot, so where the reference data supplies one it decides, and the stem is
	 * left to the families it cannot speak for. Widening the substance NAME alone was never the
	 * alternative: it merges the two PPIs.
	 *
	 * <p>Neither half works alone. Where one stem covers two substances the stem alone merges them and
	 * the substance name is what refuses: {@code Varicella Zoster Vaccine (Recombinant)} against
	 * {@code (live/attenuated)} — a distinction that decides whether an immunocompromised patient may
	 * have it at all — and likewise {@code Manganese (chloride)}/{@code (sulfate)},
	 * {@code Dextran (-1)}/{@code (low molecular weight)}, {@code Insulin human}/{@code (isophane)},
	 * {@code Insulin lispro}/{@code (protamine)}, {@code Iron}/{@code (polysaccharide)}. A few more
	 * one-stem groups are kept apart because a row publishes NO substance name rather than a differing
	 * one ({@code Typhoid vaccine (live)}/{@code (inactivated)}) — that is the null-key fallback below,
	 * not this comparison. Together the three reduce those 332 entries to 163 substances (177 while the
	 * stem was the veto), and no resulting group holds two DrugBank substances.
	 *
	 * <p>Conservative where it cannot tell, and now in ONE direction rather than both. A name that
	 * extends the family's stem by a WORD rather than a qualifier keeps its own key, and so its own
	 * chip, only where the registry agrees it is another substance ({@code Hydrocortisone butyrate},
	 * {@code Estrone sulfate}, {@code Procaine benzylpenicillin}); where the KB is naming one substance
	 * two ways it no longer does ({@code Thallous Chloride}/{@code Thallous chloride tl-201},
	 * {@code Typhoid vaccine (live)}/{@code Typhoid vaccine live}). Over-reporting one chip is still the
	 * safe direction for a non-blocking advisory, and dropping a real one is not — but a chip reporting
	 * a substance against ITSELF is not a real one, which is what makes those merges a gain rather than
	 * a relaxation.
	 *
	 * @return an opaque key, equal exactly for two entries this module treats as one substance
	 */
	Object substanceKey() {
		return substanceKey(name, substanceName, substanceId);
	}

	/**
	 * {@link #substanceKey()} over the three fields it reads, for a caller holding those fields but no
	 * {@link DrugReference} yet: {@link DdiDrugReferenceSource}'s parse-time rows, which have to answer
	 * "are these two rows one substance?" before any entry exists (issue #152's self-pair guard). One
	 * definition, so a load-time guard and the chip grouping cannot come to disagree about what one
	 * substance is — the failure that would leave a self-pair loaded for exactly the rows the chips then
	 * merge, and the reason issue #164's interaction arm needed no change of its own.
	 *
	 * @return the key described at {@link #substanceKey()}, or null when {@code substanceName} is blank
	 */
	static Object substanceKey(String name, String substanceName, String substanceId) {
		String substance = normalizeName(substanceName);
		if (substance == null) {
			return null;
		}
		String identity = normalizeName(substanceId);
		return Arrays.asList(substance, identity != null ? identity : displayStem(name));
	}

	/**
	 * @return the substance this entry stands for ({@link #substanceKey()}), else the entry itself. The
	 *         two are different types — a {@link List} and a {@link DrugReference} — so the two key
	 *         spaces cannot collide, and an entry from a source publishing no substance name keys on
	 *         its own identity and therefore groups with nothing.
	 *
	 *         <p>The key for grouping the ROWS OF ONE SUBSTANCE inside one request, shared by the
	 *         contraindication chip ledger ({@code DrugSafetyValidator.ContraindicationChips}, issue
	 *         #145), the interaction arms' subject side (#162) and the class arm's co-medication
	 *         grouping ({@code DrugSafetyValidator.orderPartners}, issue #171), so no two of them can
	 *         merge different sets of rows. Identity is the right fallback for all of them because
	 *         every set they group is resolved against {@link DrugReferenceService}'s shared
	 *         {@code getAll()} cache, so one row is one object.
	 *         {@link DrugReferenceInjector#matchingEntries} deliberately falls back to
	 *         {@link #getId()} instead, not to this — see there.
	 */
	Object substanceGroupKey() {
		Object substance = substanceKey();
		return substance != null ? substance : this;
	}

	/**
	 * @return whether this entry's display name names the substance with NO trailing route/formulation
	 *         qualifier — {@code Dexamethasone} rather than {@code Dexamethasone (nasal)}. At most one
	 *         row of a substance normally answers true, and it is the row a question naming the bare
	 *         substance is about: nothing on a {@code DrugOrder} or in a question tells this module
	 *         which route is in play (every variant publishes the same aliases and the same ATC list —
	 *         the data-side gap issue #115 records), so the only route it can honestly assert is none.
	 *
	 *         <p>Consumed by {@link #canonicalRow}, which is where the two collapses that need it agree
	 *         on one answer. Measured over the shipped 19 MB KB (2026-08-07; re-measure before relying
	 *         on the figures): of the 129 substances filed as more than one row, 119 have such a row and
	 *         10 do not — {@code Oxymetazoline (nasal)}/{@code (ophthalmic)}/{@code (topical)},
	 *         {@code Iobenguane (I-123)}/{@code (I-131)} — and in 7 of the 119 it is NOT the family's
	 *         first row, which is why the choice cannot be left to dataset order.
	 */
	boolean namesNoRoute() {
		String normalized = normalizeName(name);
		return normalized != null && normalized.equals(displayStem(name));
	}

	/**
	 * Which of two rows of ONE substance should represent it — the row a collapsed chip is named after
	 * ({@code DrugSafetyValidator.addInteractionWarnings}, issue #162), the row a collapsed reference
	 * record is rendered from ({@link DrugReferenceInjector#matchingEntries}, issue #163), and the row a
	 * class chip names its PARTNER by ({@code DrugSafetyValidator.entryForAtcCode}, issue #174 site 1 —
	 * where the ambiguity is not two rows a question resolved but the several rows that all publish the
	 * one ATC code being looked up). Shared rather than decided three times, because those surfaces
	 * describe the same substance to the same clinician and to the same model: a chip naming the
	 * substance beside a record naming one of its routes is the chip-versus-prose divergence this module
	 * keeps having to remove.
	 *
	 * @return {@code candidate} when it {@link #namesNoRoute()} and {@code incumbent} does not, else
	 *         {@code incumbent} — so the route-unspecified row wins wherever the family has one, and
	 *         otherwise the first row seen keeps the role. For the 10 shipped families that name no
	 *         unqualified row the survivor therefore still carries a qualifier: the KB publishes no
	 *         unqualified name for those substances, and manufacturing one by stripping a display name
	 *         is the pattern-match-a-label mistake issue #148 had to undo.
	 */
	static DrugReference canonicalRow(DrugReference incumbent, DrugReference candidate) {
		if (incumbent == null) {
			return candidate;
		}
		return candidate.namesNoRoute() && !incumbent.namesNoRoute() ? candidate : incumbent;
	}

	/**
	 * @return {@link #canonicalRow(DrugReference, DrugReference)} folded over {@code rows} in
	 *         iteration order — the row that represents the substance for a caller that already holds
	 *         the whole group rather than accumulating it as it scans. {@code null} for an empty
	 *         {@code rows}, which is the only way this can answer nothing.
	 *
	 *         <p>Shared rather than written out at each site for the reason the pairwise form exists
	 *         at all: four surfaces now choose a substance's representative row — the interaction
	 *         chip's subject (issue #162), the injected record (#163), the class chip's partner
	 *         (#174 site 1) and the dose warning's subject (#174 site 4) — and a fold written four
	 *         times is four chances for one of them to iterate in an order the others do not.
	 */
	static DrugReference canonicalRow(Iterable<DrugReference> rows) {
		DrugReference canonical = null;
		for (DrugReference row : rows) {
			canonical = canonicalRow(canonical, row);
		}
		return canonical;
	}

	/**
	 * The separator this reference data joins a COMBINATION PRODUCT's ingredient names with —
	 * RxNorm's, and so the KB's: {@code sulfamethoxazole / trimethoprim},
	 * {@code abacavir / dolutegravir / lamivudine}. A structural marker in the string itself, which is
	 * why {@link #combinationConstituents} can read a multi-substance name off it rather than guessing
	 * from pharmacology: a name joined this way denotes every ingredient it lists.
	 *
	 * <p>Sufficient, not necessary, and the difference matters. Its absence says nothing — the KB also
	 * spells combinations with a word or a hyphen ({@code amoxicillin and clavulanic acid},
	 * {@code potassium chloride-potassium gluconate}), which is what
	 * {@link DrugReferenceService#findImpliedSubstances}'s equal-claimant leg is for. So this reads only
	 * one way: a name carrying the separator lists ingredients, while a name without it may be one drug
	 * however many words it has ({@code digoxin antibodies fab fragments},
	 * {@code ciprofloxacin lactate}) or a combination this rule cannot see.
	 */
	private static final char COMBINATION_SEPARATOR = '/';

	/** {@link #COMBINATION_SEPARATOR} as a split pattern, compiled once — {@code /} is not a regex
	 *  metacharacter, so the pattern is the character itself. */
	private static final Pattern COMBINATION_SPLIT = Pattern.compile(String.valueOf(COMBINATION_SEPARATOR));

	/**
	 * @return the ingredient names a recorded COMBINATION name lists, trimmed and in the order the name
	 *         lists them — empty for a name carrying no {@value #COMBINATION_SEPARATOR}, which is every
	 *         single-drug name. Each is a candidate, not a resolution: the caller decides what counts as
	 *         an entry claiming one, and {@link DrugReferenceService#findImpliedSubstances} requires the
	 *         KB to be NAMED it, which is what discards the fragments a separator inside a parenthesized
	 *         qualifier produces ({@code Varicella zoster vaccine (live/attenuated)} splits into
	 *         {@code varicella zoster vaccine (live} and {@code attenuated)}, and no entry is named
	 *         either).
	 *
	 *         <p>The whole name is deliberately NOT among them: it is the caller's starting point, not
	 *         a constituent, and returning it here would make the empty answer above indistinguishable
	 *         from "one constituent".
	 *
	 *         <p><b>And deliberately not gated on a SPACED separator</b>, which is the obvious way to
	 *         make the safety above structural rather than data-dependent — nearly every combination the
	 *         KB publishes spaces its separator, while the strain designations and the qualifiers that
	 *         contain one do not. It is refused because such a gate can only ever DROP an ingredient,
	 *         and the KB does publish combinations that join their ingredients bare
	 *         ({@code potassium citrate/potassium gluconate}). Dropping is the one direction this
	 *         widening exists to prevent; the gate above is what handles the other.
	 *
	 *         <p>Nothing pins that choice, and the honest reason is worth recording rather than
	 *         discovering twice: reverting this to {@code " / "} leaves the whole suite green (measured
	 *         by mutation, 2026-08-09), because the one bare-separator combination the shipped KB names
	 *         both ingredients of is ALSO claimed by both of them, so
	 *         {@link DrugReferenceService#findImpliedSubstances}'s equal-claimant leg reaches it without
	 *         this split. The dataset therefore offers no case that isolates the two, and a test
	 *         asserting one would be asserting the other. What IS pinned, by
	 *         {@code CombinationAllergenResolutionTest}, is the gate: a fragment the KB only CONTAINS
	 *         must not be reached.
	 */
	static List<String> combinationConstituents(String recordedName) {
		if (recordedName == null || recordedName.indexOf(COMBINATION_SEPARATOR) < 0) {
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<String>();
		for (String part : COMBINATION_SPLIT.split(recordedName)) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}
		return out;
	}

	/**
	 * @return the name of the PARENT MOIETY a recorded PRESENTATION name is a presentation of — the
	 *         recorded name with its trailing qualifier(s) removed ({@code Insulin lispro (protamine)}
	 *         → {@code insulin lispro}) — or {@code null} when the name carries no qualifier and so
	 *         names no moiety apart from itself.
	 *
	 *         <p>A derivation, not a claim: unlike a {@link #combinationConstituents constituent}, which
	 *         the recorded string asserts is an ingredient, this is only what is left after removing a
	 *         qualifier. That is why {@link DrugReferenceService#findImpliedSubstances} accepts it only
	 *         from an entry that is CALLED it ({@link #NAME_IS_THE_DISPLAY_NAME}) — see there.
	 */
	static String parentMoietyName(String recordedName) {
		String normalized = normalizeName(recordedName);
		if (normalized == null) {
			return null;
		}
		String stem = displayStem(recordedName);
		return stem.isEmpty() || stem.equals(normalized) ? null : stem;
	}

	/** @return {@code name} with any trailing parenthesized qualifier(s) removed, normalized by
	 *          {@link #normalizeName} — the empty string when the name is blank or is nothing but a
	 *          qualifier, which keeps the key total. */
	private static String displayStem(String name) {
		String stem = name == null ? "" : name;
		String previous;
		do {
			previous = stem;
			stem = TRAILING_QUALIFIER.matcher(stem).replaceFirst("");
		} while (!stem.equals(previous));
		String normalized = normalizeName(stem);
		return normalized == null ? "" : normalized;
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
	 *  subgroup are USUALLY structurally related (ibuprofen/naproxen, both {@code M01AE}) — but not
	 *  always: see {@link #isUnclassifyingAtcCode} for the subgroups where sharing means nothing. */
	public static final int ATC_SUBGROUP_PREFIX_LENGTH = 5;

	/**
	 * @return this entry's ATC level-4 chemical subgroups — the {@link #ATC_SUBGROUP_PREFIX_LENGTH}-char
	 *         prefixes of its {@link #normalizedAtcCodes()} (codes shorter than that contribute none).
	 *         This is the one shared REDUCTION, used by both the order-relevance scoping
	 *         ({@code DrugReferenceInjector}) and the class-based safety checks
	 *         ({@code DrugSafetyValidator}), so neither can reach a different set of subgroups from the
	 *         same codes.
	 *         <p>An intersection of two entries' subgroups is where "same ATC class" starts, not where
	 *         it ends: since issue #167 the safety checks additionally discard a shared subgroup that
	 *         {@link #isUnclassifyingAtcCode} recognises, and the injector's relevance scoping
	 *         deliberately does not — it is deciding what to put in front of the model, where an extra
	 *         record is noise, not what to assert to a clinician.
	 */
	public Set<String> atcSubgroups() {
		return atcSubgroups(normalizedAtcCodes());
	}

	/**
	 * @return the level-4 subgroups of already-normalized {@code codes} — the same reduction
	 *         {@link #atcSubgroups()} applies to an entry's own codes, for the codes an ACTIVE ORDER's
	 *         concept maps to, which belong to no entry ({@code DrugSafetyValidator.classRelationships}
	 *         compares those directly, because the loaded dataset need not carry the substance they
	 *         identify). One definition, so an order and an entry cannot come to be in "the same ATC
	 *         class" by two different reductions.
	 */
	static Set<String> atcSubgroups(Set<String> codes) {
		Set<String> out = new LinkedHashSet<String>();
		for (String code : codes) {
			if (code.length() >= ATC_SUBGROUP_PREFIX_LENGTH) {
				out.add(code.substring(0, ATC_SUBGROUP_PREFIX_LENGTH));
			}
		}
		return out;
	}

	/**
	 * ATC groups that classify a LOCALLY APPLIED formulation rather than the substance itself, each
	 * identified by the route or site of application in the group's own published name — bar the one
	 * exception noted at {@code C05B}: {@code D}
	 * "Dermatologicals" and {@code S} "Sensory organs" (whole anatomical main groups), {@code A01}
	 * "Stomatological preparations", {@code A07A} "Intestinal antiinfectives" and {@code A07E}
	 * "Intestinal antiinflammatory agents" (its {@code A07EA} is "Corticosteroids acting locally"),
	 * {@code B02BC} "Local hemostatics" (its {@code B02BX} sibling is "Other systemic hemostatics"),
	 * {@code B05C} "Irrigating solutions", {@code C05A} "Antihemorrhoidals for topical use",
	 * {@code C05B} "Antivaricose therapy" — the exception: that name is an indication, not a route, and
	 * this entry rests on its subgroups' names instead ({@code C05BA} "Heparins or heparinoids for
	 * topical use", {@code C05BB} "Sclerosing agents for local injection"); it changes no pair in the
	 * shipped KB, whose only {@code C05B} subgroup is {@code C05BA} —
	 * {@code G01} "Gynecological antiinfectives and antiseptics", {@code G02CC} "Antiinflammatory
	 * products for vaginal administration" (its {@code G02CB} sibling, prolactine inhibitors, is
	 * systemic), {@code M02} "Topical products for joint and muscular pain", {@code P03A}
	 * "Ectoparasiticides, incl. scabicides", {@code R01} "Nasal preparations", {@code R02} "Throat
	 * preparations", and {@code R03A} / {@code R03B}, the two <em>inhalant</em> subgroups of R03 —
	 * their {@code R03C} / {@code R03D} siblings are for systemic use and are deliberately absent,
	 * which is why this list cannot be written at main-group granularity throughout.
	 *
	 * <p>Deliberately NOT here: {@code N01B} "Anesthetics, local". Its name is the drug class, not the
	 * site of application — the codes classify the substance, and two local anaesthetics sharing
	 * {@code N01BB} is exactly the cross-reactivity statement a clinician wants.
	 *
	 * <p>Prefixes, and not an exhaustive partition of ATC: anything unlisted counts as classifying the
	 * substance. Neither direction of error is free, which is why the criterion is ATC's own wording
	 * about a GROUP rather than pharmacological judgement about a substance. A group wrongly listed
	 * here demotes a class that does explain a cross-reactivity concern. A group missing from it does
	 * NOT merely leave the pre-existing answer in place: it becomes the answer as soon as it sorts
	 * ahead of the systemic subgroup the pair also shares, since {@link DrugSafetyValidator}'s scan
	 * takes the first shared subgroup this method does not veto.
	 *
	 * <p>That is how {@code A07A}, {@code B02BC}, {@code B05C} and {@code G02CC} came to be here.
	 * Without them, 46 of the shipped KB's 1090 multi-subgroup pairs named one of the four — among them
	 * ibuprofen/naproxen reading {@code G02CC} instead of {@code M01AE} — and 21 of the 46 had been
	 * moved onto one by this rule itself rather than merely left there (measured 2026-08-06).
	 * {@code CrossReactivityClassChoiceTest} pins one case per group, save {@code B02BC}: its only
	 * shipped-KB pairs are epinephrine route variants, which issue #160 collapses to an identity chip
	 * before this arm can name a class at all.
	 */
	private static final List<String> LOCALLY_APPLIED_ATC_GROUPS = Collections
			.unmodifiableList(Arrays.asList("A01", "A07A", "A07E", "B02BC", "B05C", "C05A", "C05B",
					"D", "G01", "G02CC", "M02", "P03A", "R01", "R02", "R03A", "R03B", "S"));

	/**
	 * The groups nested INSIDE {@link #LOCALLY_APPLIED_ATC_GROUPS} that ATC itself names "for systemic
	 * use" — {@code D01B} antifungals, {@code D02BB} UV-radiation protectives, {@code D05B}
	 * antipsoriatics, {@code D10B} anti-acne preparations, {@code R01B} nasal decongestants. Same
	 * criterion as the list above, applied to the same words: a group is read as locally applied when
	 * its own name says where it is applied, and these five say the opposite. Without them a main-group
	 * prefix would be wrong in exactly the way this whole rule exists to fix.
	 *
	 * <p>Enumerated rather than asserted, which is what makes "these five" a claim and not a hope: the
	 * shipped KB uses 117 level-4 subgroups under one of the prefixes above, and exactly six of them are
	 * named for systemic use — {@code D01BA}, {@code D02BB}, {@code D05BA}, {@code D05BB},
	 * {@code D10BA}, {@code R01BA}, either in their own name or their level-3 parent's — all six covered
	 * by the five prefixes here (measured 2026-08-06). Only {@code D05B} changes any pair in that KB:
	 * three, all psoralens, since methoxsalen and trioxsalen share {@code D05AD} (topical) and
	 * {@code D05BA} (systemic) and would be reported as sharing the topical one. The other four change
	 * none and are here on the criterion rather than on measured impact; removing them breaks no test,
	 * which is exactly why the criterion and not the test suite has to decide membership.
	 *
	 * <p>An exception list here, while R03's systemic halves are handled by leaving {@code R03C} and
	 * {@code R03D} out of the list above, because the shapes differ: under D and R01 the locally
	 * applied part is nearly all of the group, so naming the exceptions is the shorter thing to write,
	 * while R03 splits evenly and its two inhalant halves are shorter to name than the group plus two
	 * exceptions.
	 */
	private static final List<String> SYSTEMIC_USE_EXCEPTIONS = Collections
			.unmodifiableList(Arrays.asList("D01B", "D02BB", "D05B", "D10B", "R01B"));

	/**
	 * @return whether {@code code} — a full ATC code or any prefix of one, normalized here the same
	 *         way {@link #normalizedAtcCodes()} normalizes an entry's — sits in one of the
	 *         {@link #LOCALLY_APPLIED_ATC_GROUPS} and not in one of the
	 *         {@link #SYSTEMIC_USE_EXCEPTIONS} nested inside them. A substance marketed by several
	 *         routes carries one code per route, so this is what separates the code that classifies
	 *         the SUBSTANCE from the codes that classify a locally applied presentation of it. Null and
	 *         blank are not locally applied, like every other ATC comparison here treats them: nothing
	 *         is known about them at all.
	 *         <p>Package-private with one caller ({@code DrugSafetyValidator.sharedClass}) on purpose:
	 *         it is a rule about ATC's own group names, not a fact about a substance, so nothing
	 *         outside this package should be asking it.
	 */
	static boolean isLocallyAppliedAtcCode(String code) {
		String normalized = normalizeAtcToken(code);
		return normalized != null && !fallsUnderAnyGroup(normalized, SYSTEMIC_USE_EXCEPTIONS)
				&& fallsUnderAnyGroup(normalized, LOCALLY_APPLIED_ATC_GROUPS);
	}

	/**
	 * The ATC groups that assert NOTHING about the substances filed under them, so that two drugs
	 * sharing one are not thereby related at all (issue #167). Prefixes at whatever level ATC states
	 * the property at — mostly level-4 subgroups, but {@code V03A} and {@code V07A} are level-3, which
	 * is why this is named for GROUPS the way {@link #LOCALLY_APPLIED_ATC_GROUPS} is and not for the
	 * {@value #ATC_SUBGROUP_PREFIX_LENGTH}-character subgroups the matching itself compares.
	 *
	 * <p><b>Why a residual bucket is not automatically one of these.</b> ATC files a residue in most of
	 * its groups — a level-4 subgroup whose published name begins "Other" or "Various", meaning
	 * "everything in the group above that is not classified so far". The shipped 19 MB KB uses 97 of
	 * them. But a residue INHERITS whatever the group containing it asserts: {@code R06AX} is "Other
	 * antihistamines for systemic use", and its parent {@code R06A} is "ANTIHISTAMINES FOR SYSTEMIC
	 * USE", so two drugs sharing {@code R06AX} really are both antihistamines and one really is
	 * duplicate therapy for the other. Same for {@code J01GB} "Other aminoglycosides" (already pinned
	 * by {@code CrossReactivityClassChoiceTest}), {@code N06AX} antidepressants, {@code N03AX}
	 * antiepileptics, {@code N02AX} opioids. Vetoing every residue would have dropped a class claim
	 * from 1974 of the KB's 7783 pairs that share a subgroup; 1488 of those keep it here.
	 *
	 * <p><b>The three families, and the reading of ATC's words that puts each here:</b>
	 * <ul>
	 *   <li>a residue inside a group ATC defines by SITE OF APPLICATION — the groups
	 *       {@link #isLocallyAppliedAtcCode} already recognises. {@code A01AD} "Other agents for local
	 *       oral treatment" under {@code A01} "Stomatological preparations" is the whole of what
	 *       acetylsalicylic acid ({@code A01AD05}, a mouth rinse) and epinephrine ({@code A01AD01}, a
	 *       dental haemostatic) have in common, and the chip built on it told a clinician that
	 *       adrenaline duplicates aspirin. Its siblings {@code A01AA}/{@code AB}/{@code AC} name what
	 *       their members ARE (caries prophylactics, antiinfectives, corticosteroids) and are
	 *       deliberately absent — being applied in one place is not a shared property, being a
	 *       corticosteroid is. <b>This family over-reaches, knowingly</b>: the level-3 groups nested
	 *       inside these do not all stop at a site — {@code D06A} is "Antibiotics for topical use",
	 *       {@code D01A} "Antifungals for topical use", {@code S01G} "Decongestants and antiallergics"
	 *       — so their residue does assert something about its members. Telling those groups from the ones that
	 *       assert nothing is a per-group pharmacological judgement, which is what this rule exists to
	 *       avoid making; the cost of not making it is counted below;</li>
	 *   <li>everything under {@code V03A} "ALL OTHER THERAPEUTIC PRODUCTS" and under {@code V07A} "ALL
	 *       OTHER NON-THERAPEUTIC PRODUCTS" — the only two groups in the index whose own published name
	 *       begins "ALL OTHER", and both are filed directly under {@code V} "VARIOUS", so nothing above
	 *       them names a body system or a property either. Their children partition a residue by the
	 *       accident each product is used for rather than by what it is: {@code V03AB} "Antidotes"
	 *       holds potassium iodide beside acetylcysteine, naloxone and dimercaprol, and reporting two
	 *       of them as cross-reacting states a chemical relationship that does not exist. Written at
	 *       the group rather than per child so a KB refresh cannot add a child that quietly escapes the
	 *       rule;</li>
	 *   <li>{@code S02DC} "Indifferent preparations", under {@code S02D} "OTHER OTOLOGICALS" — a bucket
	 *       ATC fills by exclusion inside a locally applied group, whose published name happens to say
	 *       nothing about its members WITHOUT beginning "Other" or "Various". The name test that
	 *       derives the first family structurally cannot find it, so it is named here instead. WHOCC's
	 *       own name search returns exactly this one row for "indifferent" and nothing at all for
	 *       "miscellaneous", which is the evidence that naming one such bucket is enough. Its sibling
	 *       {@code S02DA} "Analgesics and anesthetics" says what its members are and is deliberately
	 *       absent, the same distinction as {@code A01AD}'s. Not the same case as {@code D09AX} "Soft
	 *       paraffin dressings" or {@code D07XA}–{@code D07XD} "Corticosteroids, …, other combinations",
	 *       also inside {@code D} and also not named "Other …": those name what their members are, so
	 *       they classify and stay out.</li>
	 * </ul>
	 *
	 * <p><b>Enumerated, not sampled.</b> The first family is every level-4 subgroup in the WHO ATC index
	 * whose own published name begins "Other"/"Various" AND that falls under
	 * {@link #LOCALLY_APPLIED_ATC_GROUPS} — 27 subgroups, each name read off the WHOCC index itself.
	 * Derived from the index rather than from the defect, which is what makes the list complete rather
	 * than a patch of the two reported cases — the failure mode issue #161's own list hit, where four
	 * missing groups reproduced the defect it had just fixed. The shipped KB uses 20 of the 27, plus 8
	 * of {@code V03A}'s children; {@code S02DC} and {@code V07A} match no shipped-KB entry at all and
	 * are here on the criterion rather than on measured impact, exactly as four of the
	 * {@link #SYSTEMIC_USE_EXCEPTIONS} are — removing them breaks no test, which is why the criterion
	 * and not the test suite has to decide membership. The first family is complete only WITH RESPECT
	 * TO {@link #LOCALLY_APPLIED_ATC_GROUPS}: extend that list and it has to be re-derived against the
	 * same index.
	 *
	 * <p><b>Deliberately NOT here, though a reading of the same words reaches them:</b> {@code A16AX}
	 * "Various alimentary tract and metabolism products" and {@code N07XX} "Other nervous system drugs"
	 * are residues of a level-2 group that is itself ATC's residue for a whole main group — structurally
	 * what {@code D11AX} is, and {@code D11AX} is vetoed only because dermatologicals happen to be
	 * locally applied. They decide 91 and 55 shipped-KB pairs (eliglustat × givosiran, pitolisant ×
	 * inotersen, neither of which is a relationship). Left out and reported separately rather than
	 * folded in, because taking them moves every number below and the live evidence measured against it.
	 *
	 * <p>Measured over the shipped KB (2026-08-06, re-measured independently 2026-08-07; re-measure
	 * before relying on a figure): of the 7783 pairs sharing at least one level-4 subgroup, 486 lose
	 * their class claim entirely, 54 keep one and name a subgroup that does classify the substances
	 * instead, and 7243 are untouched. The largest contributors are {@code V03AB} (135 pairs),
	 * {@code D11AX} "Other dermatologicals" (130), {@code S01XA} "Other ophthalmologicals" (99) and
	 * {@code D06AX} "Other antibiotics for topical use" (68).
	 *
	 * <p><b>What that costs, counted rather than rounded down.</b> 116 of the 486 name a subgroup whose
	 * own published name states a therapy or an indication, so the claim they lose was defensible:
	 * {@code D06AX} 33, {@code D05AX} 26, {@code D01AE} 25, {@code S01GX} 12, {@code V03AE} 6,
	 * {@code D10AX} 5, {@code V03AC} 3, {@code G01AX} 3, {@code S01AX} 2, {@code M02AX} 1. Concretely:
	 * calcipotriol and calcitriol are both topical vitamin-D analogues and share only {@code D05AX};
	 * azelastine and cetirizine are both H1 antihistamines and share only {@code S01GX}; the iron
	 * chelators ({@code V03AC}) and the potassium/phosphate binders ({@code V03AE}) do share a mechanism
	 * and lose the claim with the rest of {@code V03A}. 451 of the 486 carry no DDInter rating either,
	 * so for those the class chip was the only chip. The price is paid deliberately: the alternative is
	 * the per-group or per-child judgement named above, and what this module does have instead is the
	 * curated cross-reactivity groups, which every vetoed pair still falls through to.
	 */
	private static final List<String> UNCLASSIFYING_ATC_GROUPS = Collections
			.unmodifiableList(Arrays.asList("A01AD", "A07AX", "B05CX", "C05AX", "C05BX", "D01AE",
					"D02AX", "D03AX", "D04AX", "D05AX", "D06AX", "D06BX", "D08AX", "D10AX", "D11AX",
					"G01AX", "M02AX", "P03AX", "R01AX", "R02AX", "R03BX", "S01AX", "S01EX", "S01GX",
					"S01JX", "S01KX", "S01XA", "S02DC", "V03A", "V07A"));

	/**
	 * @return whether {@code code} — a full ATC code or any prefix of one, normalized as
	 *         {@link #isLocallyAppliedAtcCode} normalizes its argument — sits in one of the
	 *         {@link #UNCLASSIFYING_ATC_GROUPS}, i.e. whether it is a residual bucket that tells a
	 *         reader nothing about the substances in it. Null and blank are not: nothing is known about
	 *         them at all, which is a different answer from "known to mean nothing".
	 *         <p>Package-private with one caller ({@code DrugSafetyValidator.sharedClass}) for the
	 *         same reason as its sibling: it is a rule about ATC's own group names, not a fact about a
	 *         substance.
	 */
	static boolean isUnclassifyingAtcCode(String code) {
		String normalized = normalizeAtcToken(code);
		return normalized != null && fallsUnderAnyGroup(normalized, UNCLASSIFYING_ATC_GROUPS);
	}

	/** @return whether the already-normalized {@code code} sits under any of {@code groups} — the one
	 *  definition of "an ATC code falls under a group prefix" on this side, matching what
	 *  {@link CrossReactivityGroup#containsCode} is for curated group prefixes, so that a future
	 *  refinement (level-boundary guarding, say) has one place to happen rather than two. */
	private static boolean fallsUnderAnyGroup(String code, List<String> groups) {
		for (String group : groups) {
			if (code.startsWith(group)) {
				return true;
			}
		}
		return false;
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
	 *
	 *         <p>For PROSE — a question, an answer, a rendered record. A clinician-entered drug NAME
	 *         (an order's display name, an allergen) is a different shape and has its own accessor,
	 *         {@link #matchesDrugName}; see there for why one matcher cannot serve both.
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
	 * @return true when this entry names the drug in {@code drugName} — a single clinician-entered
	 *         drug NAME rather than prose: an active order's display name, an allergen as recorded.
	 *         Case- and diacritic-insensitive; a null name never matches. Not restricted to
	 *         lowercased input, unlike {@link #matchesText}.
	 *
	 *         <p><b>Why this exists (issue #147).</b> Such a string reached {@link #matchesText}
	 *         by default, and the prose rule's symmetric boundary is wrong for it: a localized
	 *         dictionary suffixes the INN stem with one inflectional ending, so an allergy recorded as
	 *         {@code Clarithromycine} resolved to no entry at all while the SAME string as an order
	 *         name resolved fine — {@link PatientClinicalContext#hasActiveDrug} having been given
	 *         {@link #matchesOrderName} for exactly that reason (issue #86). A patient on
	 *         {@code Clarithromycine Co 500mg} was therefore told that simvastatin "interacts with
	 *         active order clarithromycin — Major" while their own recorded allergy to that drug
	 *         produced nothing: two matchers with different tolerance on the two halves of one safety
	 *         check.
	 *
	 *         <p>So this borrows {@code matchesOrderName}'s rule rather than introducing a third —
	 *         an allergen name and an order name are the same shape, one localized display name out of
	 *         the same dictionary, and the measurement behind that rule's tail allowance is recorded
	 *         there. What it must NOT be is a relaxation of {@code matchesText} itself: that serves
	 *         prose, where #86 measured the symmetric boundary as correct ("advil" must not match
	 *         inside a longer word), so the fix is to give this shape a matcher deliberately rather
	 *         than to make one rule serve three.
	 *
	 *         <p><b>Measured 2026-08-05</b> over the 3.7.1 demo dictionary's 1219 allergen-candidate
	 *         names against the full 19MB KB: the prose rule resolved 549 of them, this one resolves
	 *         624 — 75 gained, 0 lost — and the whole #86/#128/#129 kill set was re-scored in this
	 *         direction, 0 of 21 nesting pairs resolving to the nested drug. On the 2533 order names,
	 *         117 more (order name, entry) pairs resolve and none stops resolving.
	 */
	boolean matchesDrugName(String drugName) {
		for (String alias : aliases) {
			if (matchesOrderName(drugName, alias)) {
				return true;
			}
		}
		return false;
	}

	/** {@link #nameMatchStrength}: this entry does not name {@code drugName} at all. */
	static final int NAME_NO_MATCH = -1;

	/** {@link #nameMatchStrength}: one of this entry's names occurs INSIDE {@code drugName} — as a
	 *  bounded token, give or take an inflectional tail. That direction and not the reverse:
	 *  {@link #matchesDrugName} hands the recorded name to {@link #containsBoundedToken} as the text and
	 *  the entry's alias as the token, which is how an allergy recorded as {@code Ciprofloxacin lactate}
	 *  reaches a {@code Lactic acid} row whose CIEL name is {@code Lactate}. The weakest claim, and the
	 *  only one the resolution used to make. */
	static final int NAME_TOKEN_INSIDE_A_NAME = 0;

	/** {@link #nameMatchStrength}: {@code drugName} IS one of this entry's names, but not its display
	 *  name — its {@code rxnorm_name} or one of its CIEL names. */
	static final int NAME_IS_ANOTHER_NAME = 1;

	/** {@link #nameMatchStrength}: {@code drugName} IS this entry's own display name. The strongest
	 *  claim an entry can make on a name, and the one nothing can outrank. */
	static final int NAME_IS_THE_DISPLAY_NAME = 2;

	/**
	 * How strongly this entry claims a clinician-entered drug NAME — an allergen as the chart records
	 * it, an order's display name. {@link DrugReferenceService#lookupByToken} resolves such a name to
	 * the entry with the strongest claim on it, so that ONE definition orders the three kinds of claim
	 * rather than each caller re-deciding what "the same drug" means.
	 *
	 * <p><b>Why a rank and not first-past-the-post (issue #176).</b> Resolution took the earliest
	 * matching entry, and reference names nest: 206 of the shipped KB's 2283 entries did not resolve to
	 * themselves, 54 of them landing on a different SUBSTANCE (measured 2026-08-08 through
	 * {@link DrugReferenceService#lookupByToken} itself, before and after; re-measure before relying on
	 * the figures).
	 * Since issue #187 that row is what the contraindication chips NAME, so a chip reported an allergy
	 * to a drug the chart does not record — {@code Botulinum toxin type A} as
	 * {@code Daxibotulinumtoxina}, {@code Esomeprazole} as {@code Omeprazole}. It is not the
	 * unanchored-substring hazard of issues #86/#128: those names match as whole strings, so no
	 * boundary rule separates them, and it is not {@link #canonicalRow}'s question either — that fold
	 * picks a substance's representative row for DISPLAY, while this picks which row the chart's own
	 * string is about, and applying it here would rename a charted {@code Ketorolac (ophthalmic)}
	 * allergy to {@code Ketorolac}.
	 *
	 * <p><b>Gated on {@link #matchesDrugName} first</b>, so the entries a name can resolve to are
	 * exactly the ones it resolved to before and only the CHOICE among them changes: this can never
	 * resolve a name that resolved to nothing, and never fail to resolve one that resolved to
	 * something. The one shape that gate excludes is a hand-authored {@code json} entry whose
	 * {@code aliases} omit its own {@code name} — for it the display name is not a match at all, which
	 * is the pre-existing answer and not this method's to widen.
	 *
	 * @return one of {@link #NAME_NO_MATCH}, {@link #NAME_TOKEN_INSIDE_A_NAME},
	 *         {@link #NAME_IS_ANOTHER_NAME}, {@link #NAME_IS_THE_DISPLAY_NAME} — higher is a stronger
	 *         claim on {@code drugName}
	 */
	int nameMatchStrength(String drugName) {
		if (!matchesDrugName(drugName)) {
			return NAME_NO_MATCH;
		}
		String recorded = normalizeName(drugName);
		if (recorded != null && recorded.equals(normalizeName(name))) {
			return NAME_IS_THE_DISPLAY_NAME;
		}
		return isNamed(drugName) ? NAME_IS_ANOTHER_NAME : NAME_TOKEN_INSIDE_A_NAME;
	}

	/**
	 * @return true when {@code token} IS one of this entry's own names — exact identity after
	 *         {@link #normalizeName}, deliberately not a scan. Both operands are canonical reference
	 *         strings (a rule's match token against an entry's own alias list), so the question is name
	 *         identity; {@code DrugSafetyValidator.identifies} records what scanning them instead
	 *         produced (a multi-word token naming every drug called after one of its words).
	 *
	 *         <p>Also the middle rank of {@link #nameMatchStrength}, where the second operand is a
	 *         clinician-entered name rather than a reference one. It stays unfolded there for the reason
	 *         {@link #normalizeName} records: an accented chart string therefore reaches no exact rank
	 *         and falls through to the folded matcher, which is exactly what it did before — the fold's
	 *         own measurement says the reference side carries no combining mark, so the gap is on the
	 *         chart side and widening it needs its own measurement.
	 */
	boolean isNamed(String token) {
		String name = normalizeName(token);
		if (name == null) {
			return false;
		}
		for (String alias : aliases) {
			if (name.equals(normalizeName(alias))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The one normalisation for comparing a reference NAME with a token by identity: trimmed,
	 * lower-cased ({@link Locale#ROOT}), {@code null} when blank. Shared by {@link #isNamed}, by
	 * {@code DrugSafetyValidator.namesEntry} and by the name sets {@link PatientClinicalContext}
	 * holds, so "the same name" means one thing across the three of them.
	 *
	 * <p>Deliberately does NOT fold diacritics, unlike {@link #containsBoundedToken}: this decides
	 * IDENTITY between two reference strings, and folding it would widen
	 * {@code DrugSafetyValidator.identifies} — the test three arms share for "which entry does this
	 * rule point at" — by an amount nothing has measured. It would also be a no-op on every shipped
	 * dataset: 0 of the full KB's 2093 rule tokens and 0 of its 5169 aliases carry a combining mark.
	 */
	static String normalizeName(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
	}

	/**
	 * @return true when {@code word} occurs in {@code text} as a <em>whole word</em> — bounded on
	 *         each side by a non-alphanumeric character or the string edge. Whole-word, not
	 *         substring, so a drug name nested inside a longer one does not spuriously match
	 *         ("chlorothiazide" is not a whole word in "hydrochlorothiazide"), while a real token
	 *         still matches ("aspirin" in "Aspirin 81 mg"). Case-insensitive and
	 *         diacritic-insensitive (see {@link #containsBoundedToken}); a null or empty word
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
	 * <p>Since issue #147 this is the rule for a clinician-entered drug NAME generally, not only for
	 * an order's: {@link #matchesDrugName} borrows it to resolve an allergen, which is the same shape
	 * read out of the same dictionary. The measurement below is over order names because that is the
	 * corpus that settled the tail allowance; the allergen corpus was scored separately, see there.
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
	 *
	 * <p><b>Diacritics (issue #129).</b> The comparison folds them — see
	 * {@link #containsBoundedToken}, which is where the fold lives and why it lives there. DDInter's
	 * tokens are unaccented RxNorm generics while a localized dictionary spells the same drug with
	 * accents, so unfolded an order named {@code Budésonide} shared no substring with
	 * {@code budesonide} at the accented character and that patient was never checked against
	 * budesonide's interaction rules at all — the same silent absence as a false negative, arriving by
	 * a different route. Re-measured over the same corpus with the shipped matchers:
	 *
	 * <pre>
	 *   rule                            matches   nested-name collisions leaking   accented names matched
	 *   this matcher, unfolded (#128)     829     0 of 9   (0 of 12 accented)      2 of 10
	 *   this matcher, folded (#129)       907     0 of 9   (0 of 12 accented)     10 of 10
	 * </pre>
	 *
	 * 224 of the 2531 names carry a diacritic. Folding both operands makes the change a pure
	 * relaxation — 78 pairs added, <em>0 removed</em> — which is what let this widening be scored
	 * against #128's kill set instead of argued about, and the kill set includes the accented
	 * spellings, which are the ones folding could newly break: {@code nitroglycérine} folds to
	 * {@code nitroglycerine}, i.e. {@code glycerin} plus one inflectional letter, so it becomes a
	 * candidate for that token at the very moment {@code glycérine} legitimately does, and only the
	 * LEFT boundary separates them.
	 *
	 * <p>76 of the 78 are an accented spelling of the token's own drug ({@code héparine} ~ heparin,
	 * {@code lévofloxacine} ~ levofloxacin, {@code énoxaparine} ~ enoxaparin). The other two, for the
	 * record, are both the PHRASE nesting no boundary rule can see rather than a new class of error:
	 * {@code preparado de activador del plasminógeno tipo tisular} (tissue plasminogen ACTIVATOR)
	 * matches {@code plasminogen}, that token's only match in this dictionary, inheriting
	 * bleeding-risk rules that fit a fibrinolytic anyway; and
	 * {@code acétaminophene,pseudo-éphédrine,…} matches {@code ephedrine} across the hyphen, which its
	 * English twin row {@code Acetaminophen,Pseudo-ephedrine,…} already did before this change — so
	 * the fold makes one product's two spellings agree rather than introducing that imprecision.
	 * Both are a mislabel of a drug the patient IS on, like the {@code glycerin} case above.
	 *
	 * <p>Misspellings are deliberately NOT accommodated. {@code Lisoniazide} and
	 * {@code Sprironolactone} are typo'd rows in this same dictionary; folding leaves both unmatched
	 * (measured before and after), and it must stay that way — matching a typo means matching a name
	 * that differs from the token by an edit, which reopens the substring hazard from the other side.
	 * They are a data-quality problem, not a matcher problem.
	 */
	static boolean matchesOrderName(String orderName, String token) {
		return containsBoundedToken(orderName, token, MAX_ORDER_NAME_INFLECTION_LETTERS);
	}

	/**
	 * The one boundary-aware containment scan, shared by prose matching ({@link #containsWord}) and
	 * order-name matching ({@link #matchesOrderName}) so the boundary rule cannot drift between
	 * them. A match needs {@code token} to start at a word boundary in {@code text} and to end at
	 * one, give or take up to {@code maxTrailingLetters} letters. Letters only: a digit is never an
	 * inflection, so a digit sitting against the token is neither stepped over nor treated as the
	 * end of the name, and a display name that glues its strength straight onto the drug name
	 * ({@code Aspirin81mg}) therefore does not match. That shape does not occur in the measured
	 * dictionary — of the 67 matches this rule drops relative to bare containment, 61 are a token
	 * inside a longer word and 6 are tails longer than two letters, none is a glued digit — and
	 * treating a digit as the end of the name instead scores identically over that corpus (829
	 * either way), so the two are indistinguishable on real data and this is the conservative one.
	 * Case-insensitive; a null or empty token never matches. Whitespace-only is the caller's
	 * business, deliberately not this method's: {@link PatientClinicalContext#hasActiveDrug} trims
	 * its token, and the {@code ddinter} and {@code atc} sources drop blank aliases at parse. A
	 * hand-authored {@code json} KB is NOT sanitized, so a blank alias there is scanned like any other
	 * token and can match — measured, and wider under the tail allowance than under the symmetric rule,
	 * so #147 giving allergens the tail allowance widened it. Pre-existing, still not this method's to
	 * decide, and an authoring guard belongs in that parser; reported separately.
	 *
	 * <p>Diacritic-insensitive on BOTH sides (issue #129), which is why the fold lives here rather
	 * than in either named matcher: the same accented order name reaches both of them — as the
	 * haystack when a rule token is matched against it ({@link #matchesOrderName}) and as the
	 * haystack again when the order-driven arms resolve that order's own reference entry
	 * ({@link DrugReferenceService#findForActiveOrders} → {@code findByDrugName} →
	 * {@link #matchesDrugName}, which is this rule again since issue #147),
	 * and as the NEEDLE when an order name is looked for in a rendered record
	 * ({@code PatientClinicalContext.ActiveDrugOrder.namedIn}). Folding one matcher would leave the
	 * same patient half-checked; folding one SIDE would break that third case, whose needle is the
	 * patient's accented name. Folding both operands also makes the change a pure relaxation —
	 * everything that matched before still matches — which is what let the widening be measured
	 * against #128's kill set rather than argued about.
	 *
	 * <p>Folding the reference side is a no-op on every shipped dataset and is not there for gain:
	 * measured over the full DDInter KB, 0 of its 2093 rule tokens and 0 of its 5169 aliases carry a
	 * combining mark, and no two tokens fold together. It is there because a hand-authored
	 * {@code json} KB may carry an accented alias, and a one-sided fold would then silently stop
	 * matching the accented order name it was written for.
	 */
	private static boolean containsBoundedToken(String text, String token, int maxTrailingLetters) {
		if (text == null || token == null) {
			return false;
		}
		String t = foldDiacritics(text.toLowerCase(Locale.ROOT));
		String w = foldDiacritics(token.toLowerCase(Locale.ROOT));
		if (w.isEmpty()) {
			// After the fold, not before: a token of nothing but combining marks folds to empty, and
			// the empty token matches almost anything below.
			return false;
		}
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

	/** Unicode non-spacing marks — the combining accents an NFD decomposition separates out. */
	private static final Pattern NON_SPACING_MARKS = Pattern.compile("\\p{Mn}+");

	/**
	 * @return {@code value} with its diacritics folded away — canonically decomposed (NFD) and
	 *         stripped of combining non-spacing marks, so {@code budésonide} compares as
	 *         {@code budesonide}. The one definition, used on both operands of
	 *         {@link #containsBoundedToken}; never call {@link Normalizer} for this elsewhere.
	 *
	 *         <p>Decompose-and-strip rather than a hand-rolled character map: a map has to be
	 *         maintained per language and silently stops folding the first accent nobody listed
	 *         (this dictionary alone carries {@code é è ï ô û á ó}), whereas NFD is the Unicode
	 *         standard's own answer to which marks belong to which letter.
	 *
	 *         <p><b>Non-Latin scripts.</b> Stripping {@code Mn} is diacritic folding for Latin,
	 *         Greek, Cyrillic, Arabic harakat and Hebrew niqqud — all cases where the marks are
	 *         optional pointing over an unambiguous skeleton. It is NOT lossless everywhere: in Thai
	 *         and Lao the tone marks are {@code Mn}, and in Indic scripts the virama is, so two
	 *         distinct words in those scripts can fold together and match each other. That is the
	 *         same widening this fold is for, one script over, and it is bounded by the boundary rules
	 *         around it; spacing marks ({@code Mc} — Devanagari matras and the like) are deliberately
	 *         NOT stripped, because those carry the vowel rather than decorate it. Scripts with no
	 *         canonical decomposition at all (CJK ideographs) are untouched, and Hangul syllables
	 *         decompose to Jamo, which are letters and so survive the strip — both sides fold
	 *         identically, so matching within those scripts is unchanged.
	 *
	 *         <p>ASCII returns unchanged without normalizing: every reference token is ASCII and so
	 *         is most order-name text, and this runs once per (rule token, order name) pair — up to a
	 *         few hundred rules per question — so the common path must not allocate.
	 */
	static String foldDiacritics(String value) {
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) > 0x7F) {
				return NON_SPACING_MARKS
						.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("");
			}
		}
		return value;
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
