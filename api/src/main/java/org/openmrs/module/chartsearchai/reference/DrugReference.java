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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *   <li>{@link #getAliases()} — lowercase free-text names, for question-driven matching and (through
 *       {@link #matchesDrugName}) for resolving an active order's own display name.</li>
 *   <li>{@link #getAtcCodes()} — ATC codes for order-driven matching against an active order's concept
 *       mappings. One of those two keys since issue #151, not the only one — see
 *       {@link DrugReferenceService#findForActiveOrders}.</li>
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
	 * ddinter parser, because a curated json file can bind {@code genericName} directly.
	 *
	 * <p><b>It DOES reach prompt text, and this javadoc said otherwise until August 2026.</b> The
	 * claim was "never used in prompt text — record rendering keeps {@code getName()}", and what is
	 * true is the narrower half of it: the {@code drug_reference} record's own text keeps
	 * {@link #getName()}, which {@code DrugSafetyChipLabelTest.displayLabelNeverLeaksIntoTheRenderedRecordText}
	 * pins. The {@code safety_finding} record does not: {@code DrugSafetyValidator.interactionWarning}
	 * builds both a chip's drug and its detail from this label, and
	 * {@code DrugReferenceInjector.renderFinding} copies both verbatim into prompt text — measured
	 * through the real {@code validate} over the bundled DDInter sample,
	 * {@code Safety finding — Acetylsalicylic acid (aspirin): Acetylsalicylic acid (aspirin) interacts
	 * with active order warfarin — Major. …}. So do not cite the old sentence as a reason two surfaces
	 * cannot share a string; say which RECORD is meant.
	 */
	public String displayLabel() {
		return appendsGenericName() ? name + " (" + genericName + ")" : name;
	}

	/**
	 * @return whether {@link #displayLabel()} appends {@link #getGenericName()} as a synonym — the
	 *         divergence test itself, extracted so that {@link #labelNameOccursIn} can ask which names
	 *         the printed label is actually BUILT from without restating the rule. Two spellings of it
	 *         would let a caller admit a generic the label never prints, which is the whole point of
	 *         asking.
	 */
	private boolean appendsGenericName() {
		if (genericName == null || genericName.isEmpty() || name == null) {
			return false;
		}
		String n = name.toLowerCase(Locale.ROOT);
		String g = genericName.toLowerCase(Locale.ROOT);
		return !n.contains(g) && !g.contains(n);
	}

	/**
	 * Whether a clinician-entered drug NAME carries one of the names {@link #displayLabel()} is built
	 * from — the display name, and the generic only where the label actually appends it.
	 *
	 * <p>The question a caller about to PRINT that label asks of the chart's own string (issue #268):
	 * "The patient has a recorded allergy to X." reports a record, so X may be this entry's label only
	 * where the record says it. It reads two names where {@link #matchesDrugName} scans every alias,
	 * and an alias is precisely how a row comes to be reached by a name belonging to a different
	 * substance — the shape this is asked to separate. Narrower than that scan for any entry whose
	 * aliases carry its own name and generic, which the {@code ddinter} and {@code atc} parsers
	 * guarantee and a hand-authored {@code json} entry need not: there this can answer true where
	 * {@code matchesDrugName} answers false. Not reachable through the caller — {@code
	 * findNamedSubstances} only ever sees rows {@code findImpliedSubstances} already gated on that
	 * scan — so it is a bound on the accessor rather than on the arm.
	 *
	 * <p>Gated on what the label PRINTS rather than on {@link #getName()} alone because the two must
	 * be one string. A row whose generic diverges renders as {@code Acetylsalicylic acid (aspirin)},
	 * and an allergy recorded as {@code aspirin} does name that label even though it does not carry
	 * {@code Acetylsalicylic acid}. Where the label does NOT append the generic the generic is not part
	 * of what is printed, so it cannot license printing it: a chart recording
	 * {@code dextroamphetamine sulfate} carries {@code Amphetamine}'s {@code rxnorm_name} and not its
	 * name, and {@code displayLabel()} prints no synonym there because the name is a substring of the
	 * generic — so the recorded string does not name that row, which is a different substance.
	 *
	 * <p>Measured 2026-08-24 over the shipped KB, the two halves of this accessor pull in opposite
	 * directions and both earn their place: the appended generic ADMITS 512 (name, row) pairs the
	 * display name alone would refuse, and the {@code appendsGenericName()} guard REFUSES 7 that
	 * nothing else names — {@code esomeprazole magnesium} and three combination kits reaching
	 * {@code Omeprazole} (issue #185's shape), {@code dexfenfluramine hydrochloride} reaching
	 * {@code Fenfluramine}, and the two {@code dextroamphetamine} salts reaching {@code Amphetamine}.
	 *
	 * <p>An earlier version of this paragraph offered the three shipped {@code gallium} rows as the
	 * example, and they do not exercise this half at all: {@code DdiDrugReferenceSource} sets
	 * {@code genericName} only where the display name does NOT contain the {@code rxnorm_name}, and
	 * every gallium display name contains it, so those rows carry no generic for the gate to test.
	 * They are refused by the display-name half.
	 *
	 * <p>By {@link #matchesOrderName}'s rule, since both operands are that shape: a clinician-entered
	 * name on one side and a reference name on the other. So {@code trastuzumab} names
	 * {@code ado-trastuzumab emtansine} — the hyphen is a boundary — and {@code ketoconazole} does not
	 * name {@code Levoketoconazole}, where the token sits inside a longer word (issue #86).
	 */
	boolean labelNameOccursIn(String recordedName) {
		if (matchesOrderName(recordedName, name)) {
			return true;
		}
		return appendsGenericName() && matchesOrderName(recordedName, genericName);
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
	 *         substance is about: nothing in a QUESTION tells this module which route is in play, and
	 *         the data cannot help either, since every variant publishes the same aliases and the same
	 *         ATC list — the data-side gap issue #115 records — so the only route this predicate can
	 *         honestly assert is none.
	 *
	 *         <p><b>An ORDER can now say, and it makes no difference here</b> (issue #234). The
	 *         builder reads a {@code DrugOrder}'s route and dose form, so the sentence above no longer
	 *         holds of an order; what still holds is why that cannot move THIS choice. Every row of a
	 *         substance publishes the same ATC list — measured over the shipped KB, all 129 of its
	 *         multi-row substances and none differing — so preferring the row whose qualifier matches a
	 *         recorded route would change which name a chip prints and nothing about what it claims.
	 *         That is why {@link #codesForRecordedAdministration} narrows the CODES instead, and why
	 *         issue #234 left this predicate alone.
	 *
	 *         <p>Consumed by {@link #canonicalRow}, which is where the collapses that need it agree
	 *         on one answer. Measured over the shipped 19 MB KB (2026-08-07; re-measured 2026-08-13 for
	 *         issue #206 by driving {@link #substanceGroupKey()}, this predicate and
	 *         {@link #canonicalRow} over the shipped file, unchanged at 129/119/7; re-measure before relying
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
	 * @return whether this entry's display name IS the name the data files it under — {@code Estradiol}
	 *         against a {@code substanceName} of {@code estradiol}, compared through
	 *         {@link #normalizeName} like every other identity between two reference strings. The row a
	 *         family is NAMED after, as distinct from the row that names no route
	 *         ({@link #namesNoRoute()}): a family can hold several of the second and this asks which one
	 *         the data itself calls the substance.
	 *
	 *         <p>The FULL display name and not {@link #displayStem}, which is the difference that makes
	 *         it answer for a substance whose own name carries a parenthetical: the shipped
	 *         {@code Tick-borne encephalitis vaccine (whole virus, inactivated)} row is filed under that
	 *         very string, and its paediatric sibling under the same one, so both stems are
	 *         {@code tick-borne encephalitis vaccine} and a stem comparison separates neither.
	 *
	 *         <p><b>The closest neighbour is {@link #nameMatchStrength}'s top rank, and the two are
	 *         measured identical on the shipped data.</b> The expression here is the one
	 *         {@code nameMatchStrength} uses for {@link #NAME_IS_THE_DISPLAY_NAME}, with this row's own
	 *         {@code substanceName} as the operand, and over the shipped KB
	 *         {@code nameMatchStrength(getSubstanceName()) == NAME_IS_THE_DISPLAY_NAME} agrees with this
	 *         predicate on all 2283 rows and elects the same row for all 129 multi-row families. It is
	 *         still not reused, and the second reason is the one that would bite: that method is for a
	 *         CLINICIAN-ENTERED name, and CLAUDE.md keeps identity, ranking and widening apart; and it is
	 *         gated on {@link #matchesDrugName}, which its own javadoc says excludes "a hand-authored
	 *         {@code json} entry whose {@code aliases} omit its own {@code name}" — so on such a dataset
	 *         it answers false where this answers true, i.e. fails CLOSED. Recorded because the
	 *         measurement is what makes the two look interchangeable to whoever reads them next.
	 *
	 *         <p>Read by {@link #canonicalRow} alone in production, and by the cases that state its
	 *         premises. It is not a claim that the row is
	 *         the substance — {@code DrugReferenceValidity.derivesFromItsOwnSubstance} asks a different
	 *         and narrower question about the same two fields (does this row's stem carry the substance's
	 *         name without naming it as a word, i.e. is it a DERIVATIVE) and reusing that here fixes 1 of
	 *         the 3 families this rung is for, measured through the shipped KB. Null-safe on both sides:
	 *         a source publishing no {@code substanceName} — the {@code atc} adapter, a curated file that
	 *         sets none — answers false for every row, so the fold behaves exactly as it did before this
	 *         predicate existed.
	 */
	boolean namesItsSubstance() {
		String substance = normalizeName(substanceName);
		return substance != null && substance.equals(normalizeName(name));
	}

	/**
	 * Which of two rows of ONE substance should represent it — the row a collapsed chip is named after
	 * ({@code DrugSafetyValidator.interactionSubject}, issue #162, and since issue #206 every chip arm
	 * through {@code DrugSafetyValidator.SubstanceSubjects}), the row a collapsed reference
	 * record is rendered from ({@link DrugReferenceInjector#matchingEntries}, issue #163), and the row a
	 * class chip names its PARTNER by ({@code DrugSafetyValidator.entryForAtcCode}, issue #174 site 1 —
	 * where the ambiguity is not two rows a question resolved but the several rows that all publish the
	 * one ATC code being looked up). Shared rather than decided three times, because those surfaces
	 * describe the same substance to the same clinician and to the same model: a chip naming the
	 * substance beside a record naming one of its routes is the chip-versus-prose divergence this module
	 * keeps having to remove.
	 *
	 * <p>At the CHIP-SUBJECT site this is the second step rather than the whole answer since issue #194:
	 * {@code DrugSafetyValidator.interactionSubject} asks {@link #nameMatchStrength} first — the row the
	 * patient's own record names is the truthful subject (#187) — and folds only the rows tied on that.
	 * This fold is unchanged and still decides every case where the record names none of them, which is
	 * most of them.
	 *
	 * <p><b>Do not add the recorded-name step here</b> — but not for the reason this used to give. It
	 * said {@code DrugReferenceInjector.matchingEntries} and the class-partner site "have no recorded
	 * name to anchor on", and since issues #237/#259 that is false of the first: {@code matchingEntries}
	 * takes the patient's context and asks {@code interactionSubject} which row this response names each
	 * substance by. The reasons it must still not move are two, and both are stronger than the one they
	 * replace. First, this fold is the tie-break INSIDE {@code interactionSubject}, so a recorded-name
	 * step here would be applied twice at every chip-subject site. Second, {@code matchingEntries} calls
	 * this to choose the row a reference record is RENDERED from, and moving that to the charted row was
	 * measured and declined — the route-unspecified row carries the breadth, and rendering the charted
	 * one loses the patient's own interaction partner in 74 of the shipped KB's 129 multi-row families
	 * against 0 the other way. The injector says which row it rendered instead
	 * ({@code DrugReferenceInjector.rowAttribution}). The class-partner site genuinely still has no
	 * recorded name.
	 *
	 * <p><b>Two rungs since issue #250, in this order.</b> First {@link #namesNoRoute()}: the
	 * route-unspecified row wins wherever the family has one. Then, among rows agreeing on THAT and
	 * belonging to one substance, {@link #namesItsSubstance()}: the row the data files the family under
	 * wins. Otherwise the first row seen keeps the role. The second rung exists because the first one
	 * ties on any family holding two rows that both name no route, and dataset order then answered — so
	 * the shipped KB elected {@code Fluoroestradiol f-18}, a diagnostic PET tracer at index 1282, to
	 * speak for the estradiol substance against {@code Estradiol} at 1927, and every rated partner of
	 * that substance took the tracer as its chip subject. It did not stay in the chip either: live on a
	 * 3.7.1 standalone, a question naming estradiol was answered <em>"No — Fluoroestradiol f-18 should
	 * not be given"</em>, the substance the clinician asked about named nowhere in the response.
	 *
	 * <p><b>Why the second rung is BELOW the first and not above it.</b> Above it, the rung renames a
	 * fourth shipped family — the influenza A/Vietnam antigen, whose elected row carries a display name
	 * with a dropped leading "I" — and issue #250 lists that as one of its four. It is also the ONLY
	 * shipped family for which that placement elects a route-qualified row while the family HAS an
	 * unqualified one, which falsifies this method's own first rung and, with it,
	 * {@link DrugReferenceInjector#matchingEntries}' stated reason for widening its candidate set
	 * ("{@code canonicalRow} never moves AWAY from {@code namesNoRoute()}"). And the row a record
	 * renders is where its rules come from ({@code DrugReferenceInjector.onePerPartner} walks the
	 * elected row's own {@code getInteractions()}), so that placement costs that family 12 rendered
	 * interaction partners against 1 gained — traded for a typo the issue itself files as a ninth
	 * instance of issue #196's upstream data problem, which is the handoff the estradiol MERGE already
	 * takes. That refusal is about the rung ORDER and is NOT an argument that this is the only route to
	 * that family: the parenthetical {@code namesNoRoute()} reads as a qualifier there is part of the row's
	 * own {@code substanceName}, so correcting THIS rung's reading of it delivers that family and, measured
	 * over the shipped KB, no other family's election. ADR Decision 43's rejected-alternative section
	 * carries that measurement and why it is out of scope here — a second consumer of this predicate,
	 * {@code DrugSafetyValidator.outranks}, decides whose mechanism prose a chip renders and was not
	 * measured for it.
	 *
	 * <p>Below the first rung the fold never moves AWAY from {@code namesNoRoute()}, and that is
	 * structural rather than a property of the shipped data: the first rung RETURNS whenever the two rows
	 * disagree on it, so the second is only ever reached between rows that agree, and it therefore cannot
	 * replace an unqualified row with a qualified one. It can still move LATERALLY between two rows that
	 * agree — which is what all three of its shipped moves are — so "monotone" here means the weaker
	 * thing; {@link DrugReferenceInjector#matchingEntries}' bullet says so at its own site.
	 * {@code SubstanceNameRowTest.routeQualificationStillOutranksNamingTheSubstance} is what fails if the
	 * rungs are ever reordered. Reorder them and read the failures rather than trusting a list here; when
	 * that case was written it was the only one that reddened, which is why it exists.
	 *
	 * <p><b>What it moves, measured 2026-08-24 through the real parse and this method on BOTH sides</b> —
	 * the baseline is this same method as it stands before the rung, driven from a second worktree, rather
	 * than a re-expression of it; re-measure before relying on the figures. Of the 129 multi-row families,
	 * <b>3 change subject</b> and 126 do not: {@code Fluoroestradiol f-18}/{@code Estradiol},
	 * {@code Daxibotulinumtoxina}/{@code Botulinum toxin type A}, and the paediatric tick-borne
	 * encephalitis vaccine/the row the data files that family under. <b>All three moves are LATERAL in
	 * route terms</b> — the first two elect a row that names no route where the incumbent did too, and the
	 * third elects one that does not where the incumbent did not either, since neither tick-borne row is
	 * unqualified. The count of families electing a route-qualified row is 10 before and 10 after. At the one
	 * {@code canonicalRow} site whose row set is NOT one substance
	 * ({@code DrugSafetyValidator.entryForAtcCode}, over every row publishing one ATC code, 30 of the
	 * KB's 2148 codes spanning more than one substance) exactly 1 fold moves, and within one substance.
	 * The rules the RENDERED record then carries move with the row: the vaccine family gains 19 partners
	 * and loses none, botulinum is identical at 110, and estradiol goes from the tracer's 4 partners to
	 * 578. Those are counts of the elected ROW's rated partners in the dataset and NOT of anything a
	 * clinician sees — the chip arm is scoped to the patient's own active orders, so the response for the
	 * ticket's own patient carries five interaction chips before and after, live-verified. Read 578 as
	 * breadth available to a record, never as chips gained. The estradiol row loses
	 * {@code bazedoxifene} and {@code toremifene}, which only the tracer row rates, and
	 * rendering {@code ospemifene} and {@code tamoxifen} at the substance row's own rating rather than
	 * the tracer's. The CHIP arm is unaffected there, because it pools every row of the substance
	 * ({@code DrugSafetyValidator.bestRulePerPartner}): that pool is 580 partners before and after.
	 *
	 * <p><b>The residue of the same-substance gate, stated rather than left to be found.</b> Where the
	 * row set spans substances the second rung is skipped per PAIR, so the answer can depend on the order
	 * the rows arrive in — a row of one substance interposed between two rows of another is compared
	 * against neither of them on that rung. This fold was already order-sensitive there, and the gate
	 * does not make it more so: re-folding each of the 2148 ATC codes' rows reversed, and under 40 random
	 * permutations each, changes the elected row for <b>49</b> codes with this rung and <b>50</b> without
	 * it — the one it removes being {@code G03CA03}, which the rung now decides outright. Nothing here is
	 * a claim that the ordering is sound; what would make it sound is grouping by substance before
	 * folding, and that is a change to {@code DrugSafetyValidator.entryForAtcCode} rather than to this
	 * method.
	 *
	 * @return {@code candidate} where it outranks {@code incumbent} on {@link #namesNoRoute()}, or — for
	 *         two rows of one substance agreeing on that — on {@link #namesItsSubstance()}; else
	 *         {@code incumbent}. For the 10 shipped families that name no unqualified row the survivor
	 *         still carries a qualifier: the KB publishes no unqualified name for those substances, and
	 *         manufacturing one by stripping a display name is the pattern-match-a-label mistake issue
	 *         #148 had to undo.
	 */
	static DrugReference canonicalRow(DrugReference incumbent, DrugReference candidate) {
		if (incumbent == null) {
			return candidate;
		}
		if (candidate.namesNoRoute() != incumbent.namesNoRoute()) {
			return candidate.namesNoRoute() ? candidate : incumbent;
		}
		// Rung two, and only where the two rows are one substance: "is this row the one the family is
		// named after" is a question about a family, and this method is also folded over row sets that
		// are NOT one — DrugSafetyValidator.entryForAtcCode folds every row publishing one ATC code, and
		// 30 of the shipped KB's 2148 codes span more than one substance. Ungated the rung renames three
		// of those across substances, including A02BC05 Omeprazole -> Esomeprazole: the shipped
		// Omeprazole row carries rxnorm_name esomeprazole, so it does not name its own substance while
		// Esomeprazole does, and the pair is the one DrugReference.substanceKey exists to keep apart.
		if (incumbent.substanceGroupKey().equals(candidate.substanceGroupKey())
		        && candidate.namesItsSubstance() && !incumbent.namesItsSubstance()) {
			return candidate;
		}
		return incumbent;
	}

	/**
	 * @return {@link #canonicalRow(DrugReference, DrugReference)} folded over {@code rows} in
	 *         iteration order — the row that represents the substance for a caller that already holds
	 *         the whole group rather than accumulating it as it scans. {@code null} for an empty
	 *         {@code rows}, which is the only way this can answer nothing.
	 *
	 *         <p>Shared rather than written out at each site for the reason the pairwise form exists at
	 *         all: every surface that must describe one substance to one clinician chooses its
	 *         representative row here, and a fold written once per surface is one chance per surface for
	 *         them to iterate in an order the others do not. Grep the callers rather than trusting a count
	 *         here — every issue that finds another surface adds one. Since #206 the CHIP subjects share
	 *         one per-{@code validate} lookup ({@code DrugSafetyValidator.SubstanceSubjects}) that ranks the
	 *         patient's recorded names before folding; a caller with no recorded name to rank by asks this
	 *         directly.
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
	static String displayStem(String name) {
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
	 *         record is noise, not what to assert to a clinician. Since issues #183/#184 that discard
	 *         is no longer uniform across the safety checks either: the CROSS-REACTIVITY arm discards
	 *         strictly more, everything {@link #isPurposeOnlyAtcCode} recognises, because a shared
	 *         purpose is enough to call two drugs duplicate therapy and not enough to call them
	 *         chemically related. So there are three widths of the same intersection here, and which
	 *         one a caller wants is decided by what it is about to assert.
	 *
	 *         <p>Issue #151 widened the injector's candidate set from ATC-mapped orders to every order
	 *         the reference data resolves, so that divergence is reached far more often and its cost was
	 *         measured rather than left as a judgement. Applying the #167 veto to the injector's gate as
	 *         well would remove 6 of the 491 records injected across a 24-patient x 21-question sweep of
	 *         the 3.7.1 demo instance's real active orders against the 19 MB knowledge base (measured
	 *         2026-08-13 by running the real {@code DrugReferenceInjector.injectRecords} with and without
	 *         the veto), and 0 of the records that were injected before #151. That figure was taken while
	 *         {@link #isUnclassifyingAtcCode} held 30 groups; issues #183/#184 took it to 36 and added a
	 *         second, wider predicate, so re-measure it before quoting it for either. Two of the six are the
	 *         noise the veto is for (neomycin beside a ciprofloxacin question, related only through
	 *         "both are also sold as ear drops"); four are pairs a clinician wants — diclofenac beside an
	 *         ibuprofen question, budesonide beside a prednisolone one — that the veto would drop because
	 *         every subgroup they share is one it vetoes, the class relation they really have being one
	 *         this knowledge base does not otherwise express. That trade is a
	 *         question about the relevance rule and belongs to its own issue, not to #151.
	 *
	 *         <p>A third CONSUMER since issue #285 — consumer, not caller; by call site this method has
	 *         six. {@code DrugReferenceLoad}'s per-arm capability report counts the entries whose codes
	 *         survive this reduction, because that is what the class comparison consumes. So this
	 *         method's output is now an operator-facing wire value too.
	 */
	public Set<String> atcSubgroups() {
		return atcSubgroups(normalizedAtcCodes());
	}

	/**
	 * @return the level-4 subgroups of already-normalized {@code codes} — the same reduction
	 *         {@link #atcSubgroups()} applies to an entry's own codes, for a caller holding a bare code
	 *         SET. That caller is {@code DrugSafetyValidator.classRelationships}, whose co-medications
	 *         carry the codes an ACTIVE ORDER's concept maps to, which may belong to no entry (the
	 *         loaded dataset need not carry the substance they identify) — and, since issue #228, the
	 *         codes of the reference row an unmapped order's NAME resolves, which belong to exactly one.
	 *         One definition either way, so a co-medication and an entry cannot come to be in "the same
	 *         ATC class" by two different reductions.
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
	 * Without them, 46 of the shipped KB's 1090 multi-subgroup ROW pairs named one of the four — among
	 * them ibuprofen/naproxen reading {@code G02CC} instead of {@code M01AE} — and 21 of the 46 had been
	 * moved onto one by this rule itself rather than merely left there (measured 2026-08-06). ROW
	 * pairs: that base and the substance-pair one, and the conversion between them, are defined at
	 * {@code DrugSafetyValidator.sharedClass}, which also carries this 1090's substance-pair
	 * counterpart (issue #243). {@code CrossReactivityClassChoiceTest} pins one case per group, save
	 * {@code B02BC}: its only shipped-KB pairs are epinephrine route variants, which issue #160
	 * collapses to an identity chip before this arm can name a class at all.
	 */
	private static final List<String> LOCALLY_APPLIED_ATC_GROUPS = Collections
			.unmodifiableList(Arrays.asList("A01", "A07A", "A07E", "B02BC", "B05C", "C05A", "C05B",
					"D", "G01", "G02CC", "M02", "P03A", "R01", "R02", "R03A", "R03B", "S"));

	/** The administration sites {@link #ATC_GROUPS_BY_SITE} and {@link #SITE_TERMS} are both keyed on —
	 *  named constants so the two halves cannot drift apart on a typo. */
	static final String SITE_SKIN = "skin";

	static final String SITE_EYE = "eye";

	static final String SITE_EAR = "ear";

	static final String SITE_NOSE = "nose";

	static final String SITE_THROAT = "throat";

	static final String SITE_AIRWAY = "airway";

	static final String SITE_VAGINA = "vagina";

	static final String SITE_MOUTH = "mouth";

	static final String SITE_GUT = "gut";

	static final String SITE_ANORECTAL = "anorectal";

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
	 * three ROW pairs, all psoralens, since methoxsalen and trioxsalen share {@code D05AD} (topical)
	 * and {@code D05BA} (systemic) and would be reported as sharing the topical one. Two of the three
	 * are across those substances and collapse to the ONE substance pair; the third is methoxsalen
	 * against its own second row, which issue #160 collapses to an identity chip before this arm can
	 * name a class (re-measured 2026-08-14 for issue #243; the two bases are defined at
	 * {@code DrugSafetyValidator.sharedClass}). The other four change none and are here on the
	 * criterion rather than on measured impact; removing them breaks no test, which is exactly why the
	 * criterion and not the test suite has to decide membership.
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
	 *         <p>Package-private on purpose: it is a rule about ATC's own group names, not a fact about
	 *         a substance, so nothing outside this package should be asking it. TWO callers since issue
	 *         #234, and they ask it for different claims — which is the thing to keep straight before
	 *         adding a third. {@code DrugSafetyValidator.sharedClass} PREFERS among candidate subgroups
	 *         and can still return a locally applied one when that is all a pair shares;
	 *         {@link #codesAtSites} can DROP a code outright, so that a {@code D01B} systemic
	 *         antifungal is not read as a skin presentation however plainly it sits under {@code D}.
	 *         The difference is not a change of mind about the predicate: the second caller holds
	 *         independent evidence about the presentation the patient was actually given, which the
	 *         first has none of, and the dropping is decided by {@link #ATC_GROUPS_BY_SITE} against
	 *         that evidence with this predicate only refusing the {@link #SYSTEMIC_USE_EXCEPTIONS}
	 *         nested inside a matched site. A caller with no such evidence gets the preference and
	 *         never the veto.
	 */
	static boolean isLocallyAppliedAtcCode(String code) {
		String normalized = normalizeAtcToken(code);
		return normalized != null && !fallsUnderAnyGroup(normalized, SYSTEMIC_USE_EXCEPTIONS)
				&& fallsUnderAnyGroup(normalized, LOCALLY_APPLIED_ATC_GROUPS);
	}

	/**
	 * The application SITE each {@link #LOCALLY_APPLIED_ATC_GROUPS} member is named for, and the whole
	 * of that list — this map together with {@link #LOCALLY_APPLIED_GROUPS_NAMING_NO_SITE} is a
	 * PARTITION of it, deliberately, so that it cannot go stale relative to the list it partitions
	 * (issue #234). Written as a partition rather than freehand because the first draft of it was
	 * freehand and dropped {@code S03}: the shipped KB files neomycin's ear codes as
	 * {@code {S02AA07, S03AA01}}, so an order recorded at the ear would have had one of its own site's
	 * codes discarded by a rule whose whole purpose is to keep them. That is the failure mode
	 * {@link #LOCALLY_APPLIED_ATC_GROUPS}'s own javadoc records for issue #161's hand-picked list.
	 *
	 * <p><b>The criterion is the same one that list uses, read the other way round</b>: a group is
	 * locally applied when its own published name says WHERE it is applied, so this map records WHAT
	 * that name says. {@code A01} "Stomatological preparations" -&gt; mouth; {@code A07A} "Intestinal
	 * antiinfectives" and {@code A07E} "Intestinal antiinflammatory agents" -&gt; gut; {@code C05A}
	 * "Antihemorrhoidals for topical use" -&gt; anorectal — spelled as
	 * {@link #LOCALLY_APPLIED_ATC_GROUPS}'s javadoc spells it, because three other texts cite that one
	 * as this module's record of ATC's published name and two records of one name is one too many;
	 * {@code D} "Dermatologicals", {@code M02} "Topical products for joint and muscular pain" and
	 * {@code P03A} "Ectoparasiticides, incl. scabicides" -&gt; skin; {@code G01} "Gynecological
	 * antiinfectives and antiseptics" and {@code G02CC} "Antiinflammatory products for vaginal
	 * administration" -&gt; vagina; {@code R01} "Nasal preparations" -&gt; nose; {@code R02} "Throat
	 * preparations" -&gt; throat; {@code R03A} / {@code R03B}, both "inhalants" -&gt; airway.
	 *
	 * <p><b>{@code S} is expanded here and nowhere else.</b> That list writes it at main-group
	 * granularity ("Sensory organs"), which names two sites at once, so it is taken apart into ATC's
	 * own three level-2 children: {@code S01} "Ophthalmologicals" -&gt; eye, {@code S02}
	 * "Otologicals" -&gt; ear, and {@code S03} "Ophthalmological and otological preparations" -&gt;
	 * BOTH. Those three are the whole of main group S in the WHO index, and measured over the shipped
	 * 19 MB KB every {@code S} code it publishes falls under one of them ({@code S01A}-{@code S01X},
	 * {@code S02A}/{@code S02B}/{@code S02D}, {@code S03A}/{@code S03B}).
	 *
	 * <p>Do not add a member without re-deriving it from the group's published name and recording the
	 * measured KB impact, which is the rule {@code CLAUDE.md} states for
	 * {@link #LOCALLY_APPLIED_ATC_GROUPS} and binds this map for the same reason. Measured 2026-08-28
	 * through {@link DdiDrugReferenceSource#parse}, {@link #substanceGroupKey()},
	 * {@link #normalizedAtcCodes()} and {@link #isLocallyAppliedAtcCode} over the shipped KB — per
	 * site, the substances filed there / of those, the ones this map narrows / the ones already filed
	 * only there: skin 139/106/33, eye 134/101/33, ear 20/20/0, nose 24/24/0, airway 24/19/5, vagina
	 * 22/20/2, throat 12/12/0.
	 */
	private static final Map<String, List<String>> ATC_GROUPS_BY_SITE;

	/**
	 * The three {@link #LOCALLY_APPLIED_ATC_GROUPS} members whose published name states a PROPERTY
	 * rather than a place, so no recorded route or dose form can select them: {@code B02BC} "Local
	 * hemostatics" (the name says "local", never where), {@code B05C} "Irrigating solutions", and
	 * {@code C05B} "Antivaricose therapy", whose name is the condition — the same {@code C05B} that
	 * {@link #LOCALLY_APPLIED_ATC_GROUPS}'s javadoc already carries as its one named exception to
	 * "identified by the route or site of application in the group's own published name".
	 *
	 * <p>The other half of the partition. A code under one of these is never kept by a site and never
	 * counts as unaccounted for — {@link #namesNoAdministrationSite} is how a caller tells those two
	 * apart, and {@code UnmappedOrderAdministrationSiteTest} is what fails if a fourth group appears
	 * in neither half.
	 */
	private static final List<String> LOCALLY_APPLIED_GROUPS_NAMING_NO_SITE = unmodifiable("B02BC",
			"B05C", "C05B");

	/**
	 * Recorded words that name a route of ENTRY rather than a site of action, and so refuse the site
	 * walk outright — whatever site word the same recorded string may also contain.
	 *
	 * <p>The criterion is {@link #SITE_TERMS}' own, applied to the other half of the vocabulary: a term
	 * names a site when it says where the drug ACTS, and it names a route of entry when it says how the
	 * drug gets in. {@code transdermal}, {@code sublingual} and {@code buccal} name a surface and
	 * deliver through it; {@code subcutaneous}, {@code intradermal}, {@code intravenous},
	 * {@code intramuscular}, {@code intraosseous}, {@code intrathecal}, {@code epidural} and
	 * {@code parenteral} name a compartment; {@code oral}, {@code rectal} and {@code nasogastric} name
	 * a way in that serves locally-acting and systemic preparations alike, which is the same reason
	 * mouth, gut and anorectal have no term in {@link #SITE_TERMS}.
	 *
	 * <p><b>Why a refusal and not merely an absence.</b> Absence is not enough where a systemic route's
	 * recorded name CONTAINS a site word: measured, {@code "transdermal skin patch"} matched
	 * {@code skin} and narrowed a systemic presentation to the skin, silencing a real chip. Refusing
	 * first makes the site walk unreachable for such a string. Refusing narrows nothing, which is the
	 * fail-safe direction, so a term wrongly listed here costs a correction and never a false silence.
	 *
	 * <p><b>The refusal is of the whole recorded SET, not of the string it fired on.</b> An order
	 * records a route and a dose form, and this refuses on either — so {@code "Oral administration"}
	 * beside {@code "Cutaneous cream"} narrows nothing, though the second names the skin. That is
	 * deliberate rather than incidental: a record naming a route of entry AND a site of action
	 * contradicts itself, and narrowing on the half one prefers would silence a chip on the strength
	 * of a record the module cannot reconcile. Declining keeps the answer the arm already had. It does
	 * cost the two-source design its one contradictory case — the dose form is otherwise the only leg
	 * that reaches the skin, see {@code PatientClinicalContextBuilder.addAdministration} — and
	 * {@code UnmappedOrderAdministrationSiteTest.aRouteOfEntryRefusesTheSiteADoseFormNames} is what
	 * pins it, so a reader who thinks the other reading is right has a case to move rather than a
	 * silence to interpret.
	 *
	 * <p><b>Two residues, named rather than claimed away.</b> This is word matching after the hyphen
	 * strip, so it reaches {@code sub-cutaneous} but not {@code sub cutaneous} spaced — that spelling
	 * still matches {@code cutaneous} and narrows to the skin. And {@code "Oral inhalation solution"}
	 * is a real dose form that names the airway in the same breath as a route of entry, so it declines
	 * by the paragraph above. Both cost a narrowing rather than causing a false one.
	 */
	private static final List<String> ROUTES_OF_ENTRY = unmodifiable("transdermal", "sublingual",
			"buccal", "subcutaneous", "intradermal", "intravenous", "intramuscular", "intraosseous",
			"intrathecal", "epidural", "parenteral", "oral", "rectal", "nasogastric");

	/**
	 * The recorded route and dose-form words that NAME each site — matched against what the chart
	 * records for an order ({@code PatientClinicalContext.ActiveDrugOrder.getAdministrationTerms()}),
	 * never against the drug's name.
	 *
	 * <p><b>A term must name the SITE, not the form.</b> {@code cream}, {@code ointment} and
	 * {@code lotion} were in the first draft and are deliberately absent: a cream is made for the
	 * skin, the vagina or the anorectum alike, so reading one as "skin" asserts something the record
	 * did not say — and reading it as all three is worse rather than more cautious, because
	 * hydrocortisone's {@code C05AA01} then survives and a chip that said {@code H02AB} says
	 * {@code C05AA} instead, which is a haemorrhoid preparation and no more true. A form word is
	 * admitted only where the form serves ONE site and no other, which is why {@code inhaler} is here
	 * and those three are not.
	 *
	 * <p><b>Mouth, gut and anorectal have no term, deliberately.</b> {@code Oral administration} and
	 * {@code Rectal administration} are the systemic routes in the reference dictionary AND the way an
	 * {@code A01}/{@code A07}/{@code C05A} presentation is given, so the recorded term cannot separate
	 * the two readings and the honest answer is to narrow nothing. {@code transdermal},
	 * {@code sublingual} and {@code buccal} name a surface but deliver systemically, so they appear in
	 * no list at all.
	 *
	 * <p><b>A prefixed or suffixed spelling needs its own entry.</b> Matching is
	 * {@link #containsWord}, whose prose boundary is symmetric, so {@code nasal} does not reach
	 * {@code intranasal} and {@code inhaled} does not reach {@code inhaler}; each such spelling is
	 * listed in its own right rather than the boundary being loosened, because loosening it is what
	 * would let {@code cutaneous} reach {@code subcutaneous}. Everything this list fails to recognise
	 * narrows nothing, which is the fail-safe direction.
	 */
	private static final Map<String, List<String>> SITE_TERMS;

	static {
		Map<String, List<String>> groups = new LinkedHashMap<String, List<String>>();
		groups.put(SITE_SKIN, unmodifiable("D", "M02", "P03A"));
		groups.put(SITE_EYE, unmodifiable("S01", "S03"));
		groups.put(SITE_EAR, unmodifiable("S02", "S03"));
		groups.put(SITE_NOSE, unmodifiable("R01"));
		groups.put(SITE_THROAT, unmodifiable("R02"));
		groups.put(SITE_AIRWAY, unmodifiable("R03A", "R03B"));
		groups.put(SITE_VAGINA, unmodifiable("G01", "G02CC"));
		groups.put(SITE_MOUTH, unmodifiable("A01"));
		groups.put(SITE_GUT, unmodifiable("A07A", "A07E"));
		groups.put(SITE_ANORECTAL, unmodifiable("C05A"));
		ATC_GROUPS_BY_SITE = Collections.unmodifiableMap(groups);

		Map<String, List<String>> terms = new LinkedHashMap<String, List<String>>();
		terms.put(SITE_SKIN, unmodifiable("cutaneous", "skin"));
		terms.put(SITE_EYE, unmodifiable("eye", "ophthalmic", "ocular", "intraocular", "conjunctival",
				"subconjunctival"));
		terms.put(SITE_EAR, unmodifiable("ear", "otic", "aural", "auricular"));
		terms.put(SITE_NOSE, unmodifiable("nasal", "intranasal", "nose"));
		terms.put(SITE_THROAT, unmodifiable("throat", "oropharyngeal"));
		terms.put(SITE_AIRWAY, unmodifiable("inhaled", "inhalation", "inhaler", "nebulised",
				"nebulized", "nebuliser", "nebulizer"));
		terms.put(SITE_VAGINA, unmodifiable("vaginal", "intravaginal"));
		terms.put(SITE_MOUTH, Collections.<String> emptyList());
		terms.put(SITE_GUT, Collections.<String> emptyList());
		terms.put(SITE_ANORECTAL, Collections.<String> emptyList());
		SITE_TERMS = Collections.unmodifiableMap(terms);
	}

	/**
	 * @return {@code codes} narrowed to the ones that classify a presentation given at the site
	 *         {@code recordedTerms} names — the entry point for reading a chart-recorded route or dose
	 *         form against an ATC code list (issue #234). {@link #narrowsAnyCode} is the only other
	 *         thing that reads them, answering whether narrowing is possible at all, and the two are
	 *         built from the same pair of primitives so they cannot disagree about what a record can
	 *         express. {@code DrugSafetyValidator.codesForThisSubstancesPresentations} is the single
	 *         production caller of both.
	 *
	 *         <p><b>What it is for.</b> An active order the concept dictionary did not classify is
	 *         compared on every code the reference dataset files its SUBSTANCE under, which covers
	 *         every presentation that substance is marketed as; a dictionary-MAPPED order is compared
	 *         on the one code the dictionary published. So an unmapped {@code Hydrocortisone cream 1%}
	 *         was named in a systemic {@code H02AB} duplicate-therapy chip that the same order, mapped
	 *         to {@code D07AA02}, correctly does not raise — mapping an order more correctly made the
	 *         module quieter. This narrows the first to the second wherever the chart says where the
	 *         drug is applied.
	 *
	 *         <p><b>Two ways it declines, and both return {@code codes} untouched.</b> No recorded
	 *         term names a site this module can attribute — which is every order built before issue
	 *         #234 and, measured on the 3.7.1 demo, all 46 of its active drug orders. Or the substance
	 *         publishes no code at that site at all, in which case its classification is the only one
	 *         there is and narrowing would silence the arm rather than correct it (an inhaled
	 *         aminoglycoside filed only under {@code J01GB} is the shape). Declining is not the same
	 *         as suppressing the co-medication: this method can only ever remove CODES, never the
	 *         partner, so the arm keeps whatever answer it has today.
	 *
	 *         <p><b>{@link #isLocallyAppliedAtcCode} is asked here as well as the site groups</b>, so
	 *         {@link #SYSTEMIC_USE_EXCEPTIONS} are honoured without a second copy of them: a
	 *         {@code D01B} systemic antifungal is not a skin presentation however plainly it sits
	 *         under {@code D}. That is a stricter use than {@code DrugSafetyValidator.sharedClass}
	 *         makes of the same predicate — there it prefers among candidates, here it can drop one —
	 *         and the difference is that this method holds independent evidence about the presentation
	 *         the patient was actually given, which that arm has none of.
	 */
	static Set<String> codesForRecordedAdministration(Set<String> codes, Set<String> recordedTerms) {
		if (codes == null || codes.isEmpty() || recordedTerms == null || recordedTerms.isEmpty()) {
			return codes;
		}
		// No emptiness guard on the SITES: codesAtSites keeps nothing for an empty site set, and the
		// tail below already returns codes for that. The two emptiness halves above are cost
		// short-circuits and not a second decline rule — deleting them changes no answer, which is
		// said here because a reader applying "one decline path" to them would be right to.
		//
		// The tail is UNREACHABLE from the one production caller today, and stays because it is this
		// method's contract rather than that caller's convenience: since the caller admits an order
		// only where narrowsAnyCode is true of it, the union it passes keeps at least that order's
		// codes. Nothing discriminates it, which is said here rather than left for a mutation to
		// discover — the method must never hand back an empty classification, and a second caller
		// would need that whether or not it asked narrowsAnyCode first.
		Set<String> kept = codesAtSites(codes, recordedSites(recordedTerms));
		return kept.isEmpty() ? codes : kept;
	}

	/**
	 * @return the sites {@code recordedTerms} names, in {@link #ATC_GROUPS_BY_SITE} order; empty when
	 *         they name none.
	 *
	 *         <p>Hyphens are removed from the recorded term before matching, which is what stops
	 *         {@code sub-cutaneous} being read as the skin: {@link #containsWord}'s left boundary
	 *         accepts any non-alphanumeric, so a hyphen would open one where the unhyphenated
	 *         {@code subcutaneous} correctly has none. The same removal costs {@code eye-drops} its
	 *         match, which is the fail-safe direction and the reason it is done here rather than by
	 *         widening the boundary rule several arms share.
	 */
	private static Set<String> recordedSites(Set<String> recordedTerms) {
		// De-hyphenated once, before the site walk: the transformation depends only on the recorded
		// term, and inside the two loops it was recomputed once per (site term x recorded term) pair.
		// The null filter comes with it, so the hot test does not repeat that either.
		List<String> matchable = new ArrayList<String>(recordedTerms.size());
		for (String recorded : recordedTerms) {
			if (recorded != null) {
				matchable.add(recorded.replace("-", ""));
			}
		}
		for (String recorded : matchable) {
			for (String entry : ROUTES_OF_ENTRY) {
				if (containsWord(recorded, entry)) {
					return Collections.emptySet();
				}
			}
		}
		Set<String> sites = new LinkedHashSet<String>();
		for (Map.Entry<String, List<String>> site : SITE_TERMS.entrySet()) {
			for (String term : site.getValue()) {
				for (String recorded : matchable) {
					if (containsWord(recorded, term)) {
						sites.add(site.getKey());
					}
				}
			}
		}
		return sites;
	}

	/**
	 * @return whether {@code recordedTerms} narrows {@code codes} at all — the question a caller asks
	 *         when it must decide about SEVERAL orders of one substance together
	 *         ({@code DrugSafetyValidator.codesForThisSubstancesPresentations}).
	 *
	 *         <p><b>Narrows, not "names a site", and the difference is a defect that shipped between
	 *         two passes of this change.</b> The two answers come apart for an order this module CAN
	 *         place at a site the substance is filed under no code for — a nasal hydrocortisone, say,
	 *         where the KB publishes no {@code R01}. Asked the weaker question that order passes, and
	 *         its own decline then depends on {@link #codesForRecordedAdministration}'s empty-set
	 *         fallback, which is evaluated once over the UNION of every order's terms — so a sibling
	 *         order that DOES narrow rescues the union from emptiness and the nasal order's codes are
	 *         dropped with it. Measured through the real {@code validate}: that patient's systemic chip
	 *         stood for the nasal order alone and vanished when a cutaneous cream was prescribed
	 *         beside it, which is the direction the whole-substance decline exists to prevent.
	 *
	 *         <p>Built from the same two primitives the narrowing itself uses, so the decline and the
	 *         narrowing cannot come to disagree about what this record can express.
	 */
	static boolean narrowsAnyCode(Set<String> codes, Set<String> recordedTerms) {
		return recordedTerms != null && !codesAtSites(codes, recordedSites(recordedTerms)).isEmpty();
	}

	/**
	 * @return the members of {@code codes} that classify a presentation given at one of {@code sites}.
	 *         UNGUARDED — it does not fall back to {@code codes} when nothing survives, which is what
	 *         makes it the right thing for the partition guard to drive and the wrong thing for the
	 *         class arm to call. {@link #codesForRecordedAdministration} is the guarded entry point.
	 */
	static Set<String> codesAtSites(Set<String> codes, Set<String> sites) {
		Set<String> kept = new LinkedHashSet<String>();
		for (String code : codes) {
			String normalized = normalizeAtcToken(code);
			if (normalized == null || !isLocallyAppliedAtcCode(normalized)) {
				continue;
			}
			for (String site : sites) {
				List<String> groups = ATC_GROUPS_BY_SITE.get(site);
				if (groups != null && fallsUnderAnyGroup(normalized, groups)) {
					kept.add(code);
					break;
				}
			}
		}
		return kept;
	}

	/**
	 * @return whether {@code code}'s locally-applied group is one whose published name states a
	 *         property rather than a place, so that no site can claim it — see
	 *         {@link #LOCALLY_APPLIED_GROUPS_NAMING_NO_SITE}. The other half of the partition, and the
	 *         thing that tells "this group deliberately has no site" from "this group was left out".
	 */
	static boolean namesNoAdministrationSite(String code) {
		String normalized = normalizeAtcToken(code);
		return normalized != null && fallsUnderAnyGroup(normalized, LOCALLY_APPLIED_GROUPS_NAMING_NO_SITE);
	}

	/** @return the locally-applied ATC groups {@link #ATC_GROUPS_BY_SITE} and
	 *          {@link #LOCALLY_APPLIED_GROUPS_NAMING_NO_SITE} partition — for the guard that checks
	 *          they still do, which is the only thing that can see a member left out of BOTH halves.
	 *          The data guard beside it cannot: it walks the shipped KB's codes, so a group that KB
	 *          publishes nothing under escapes it, which a mutation adding {@code V07AY} to this list
	 *          demonstrated on a green build. */
	static List<String> locallyAppliedGroups() {
		return LOCALLY_APPLIED_ATC_GROUPS;
	}

	/** @return the group prefixes {@code site} claims — for that same guard, which has to relate the
	 *          two halves prefix-wise in EITHER direction: {@code S} is covered by {@code S01} /
	 *          {@code S02} / {@code S03}, which are longer than it, while a longer member would be
	 *          covered by a shorter site prefix the other way round. */
	static List<String> groupsForSite(String site) {
		List<String> groups = ATC_GROUPS_BY_SITE.get(site);
		return groups != null ? groups : Collections.<String> emptyList();
	}

	/** @return the administration sites this module can attribute a code to, in a stable order —
	 *          for the partition guard, which has to drive every one of them. */
	static Set<String> administrationSites() {
		return ATC_GROUPS_BY_SITE.keySet();
	}

	/** @return the recorded route and dose-form words that name {@code site}; empty for the three
	 *          sites no recorded term can select — see {@link #SITE_TERMS}. */
	static List<String> termsForSite(String site) {
		List<String> terms = SITE_TERMS.get(site);
		return terms != null ? terms : Collections.<String> emptyList();
	}

	/** {@code Arrays.asList} is fixed-size but its elements are still settable, and both maps above are
	 *  handed out — {@link #termsForSite} returns one of these lists directly. Wrapped for the same
	 *  reason {@link #LOCALLY_APPLIED_ATC_GROUPS} is, so the two constants cannot be immutable in
	 *  different degrees. */
	private static List<String> unmodifiable(String... values) {
		return Collections.unmodifiableList(Arrays.asList(values));
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
	 * from 1974 of the KB's 7783 ROW pairs that share a subgroup; 1488 of those keep it here. ROW
	 * pairs, not the SUBSTANCE pairs the 5550 below counts: the two bases, and the conversion between
	 * them, are defined at {@code DrugSafetyValidator.sharedClass} (issue #243).
	 *
	 * <p><b>The families, and the reading of ATC's words that puts each here:</b>
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
	 * <p><b>The fourth family, added for issue #184: a residue whose ancestry asserts nothing at any
	 * level.</b> The reading above is that a residue inherits its parent's assertion; apply it
	 * recursively and the parent may be a residue too, in which case the walk continues upward and can
	 * reach the top without ever meeting a name that states a property. {@code A16AX} "Various
	 * alimentary tract and metabolism products" sits under {@code A16A} "OTHER ALIMENTARY TRACT AND
	 * METABOLISM PRODUCTS" under {@code A16} (the same words again) under {@code A} "ALIMENTARY TRACT
	 * AND METABOLISM" — an anatomical main group, which by ATC's own level definitions states where the
	 * drug acts and not what it is. Two drugs sharing it are related by nothing. {@code D11AX} is the
	 * tell that this family was already half-caught: it is exactly this shape and was vetoed only
	 * incidentally, because dermatologicals happen to be locally applied.
	 *
	 * <p>Enumerated the same way as the first family — every level-4 subgroup in the WHO ATC index
	 * whose own name begins "Other"/"Various", which contributes no term its ancestors' names do not
	 * already carry, and whose assertion, followed upward through further residues, resolves to nothing
	 * at all or to a bare LEVEL-1 name: ATC's anatomical main group, a body system, which asserts no
	 * shared purpose either. Six are new here — {@code A16AX}, {@code B06AX}, {@code G02CX},
	 * {@code M09AX}, {@code N07XX}, {@code R07AX} — and {@code D11AX}, {@code R03BX}, {@code S01XA} and
	 * {@code V03AX}/{@code V07AY} were already vetoed by the families above.
	 *
	 * <p><b>Level 1 and not level 2, which is where this rule was first drawn and was wrong.</b> ATC's
	 * level 2 is its THERAPEUTIC tier, so a residue inheriting one does assert something — "ANTIBACTERIALS
	 * FOR SYSTEMIC USE" for {@code J01XX}, "ANTINEOPLASTIC AGENTS" for {@code L01XX}, "DIAGNOSTIC AGENTS"
	 * for {@code V04CX}. Two drugs sharing it are not chemically related and ARE duplicate therapy, so
	 * they belong in {@link #PURPOSE_ONLY_ATC_GROUPS} below, not here. Stopping at level 1 instead cost
	 * this list 17 of the 23 members it briefly had, and with them 381 of the 538 duplicate-therapy
	 * claims it would otherwise have withdrawn — among them two erythropoiesis-stimulating agents
	 * ({@code B03XA}) and every pair of systemic antibacterials ATC files as "other" ({@code J01XX}).
	 * Issue #184 reports {@code V04CX} as one of its six; the criterion puts it in the other list, and
	 * the criterion decides.
	 *
	 * <p>The "contributes no term of its own" clause is what stops the walk eating a residue that does
	 * classify: {@code J01DI} "Other cephalosporins and penems" sits under "OTHER BETA-LACTAM
	 * ANTIBACTERIALS" under "ANTIBACTERIALS FOR SYSTEMIC USE" and would otherwise inherit a therapeutic
	 * tier, when its own name names the chemical family that is the whole reason a cephalosporin
	 * allergy says anything about another cephalosporin. Same shape: {@code M03AC} "Other quaternary
	 * ammonium compounds" under "MUSCLE RELAXANTS".
	 *
	 * <p>The family costs real relationships too, and the cost is taken deliberately: {@code G02CX}
	 * bremelanotide × flibanserin, {@code M09AX} onasemnogene × risdiplam and {@code A16AX} miglustat ×
	 * eliglustat are genuine pairs that lose their claim, because no rule over ATC's words can tell
	 * them from eliglustat × givosiran, which is not one. Measured over the shipped KB by driving
	 * {@link DrugSafetyValidator#validate} over each of the 5550 substance pairs the KB relates by a
	 * level-4 subgroup (2026-08-13; re-measure before relying on a figure): these six remove 157 of the
	 * 5271 duplicate-therapy claims, 14 of them for a pair DDInter also rates. Unlike issue #183's
	 * family below, the duplicate-therapy claim goes too — that is the whole difference between
	 * "asserts nothing" and "asserts a purpose".
	 *
	 * <p>Measured over the shipped KB for the 30 groups this list held at issue #182 (2026-08-06,
	 * re-measured independently 2026-08-07; re-measure before relying on a figure): of the 7783 ROW
	 * pairs sharing at least one level-4 subgroup, 486 lose their class claim entirely, 54 keep one and
	 * name a subgroup that does classify the substances instead, and 7243 are untouched. The largest
	 * contributors are {@code V03AB} (135 pairs), {@code D11AX} "Other dermatologicals" (130),
	 * {@code S01XA} "Other ophthalmologicals" (99) and {@code D06AX} "Other antibiotics for topical
	 * use" (68).
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
					"S01JX", "S01KX", "S01XA", "S02DC", "V03A", "V07A",
					// issue #184: a residue that adds no term of its own and whose assertion, followed up
					// through further residues, resolves to nothing at all or to a bare LEVEL-1 anatomical
					// main group -- a body system, which asserts no shared purpose either
					"A16AX", "B06AX", "G02CX", "M09AX", "N07XX", "R07AX"));

	/**
	 * @return whether {@code code} — a full ATC code or any prefix of one, normalized as
	 *         {@link #isLocallyAppliedAtcCode} normalizes its argument — sits in one of the
	 *         {@link #UNCLASSIFYING_ATC_GROUPS}, i.e. whether it is a residual bucket that tells a
	 *         reader nothing about the substances in it. Null and blank are not: nothing is known about
	 *         them at all, which is a different answer from "known to mean nothing".
	 *         <p>Package-private for the same reason as its siblings: it is a rule about ATC's own
	 *         group names, not a fact about a substance. Two callers since issue #183 —
	 *         {@code DrugSafetyValidator.justifiesClaim} for the duplicate-therapy arm, and
	 *         {@link #isPurposeOnlyAtcCode}, which subsumes it for the cross-reactivity one.
	 */
	static boolean isUnclassifyingAtcCode(String code) {
		String normalized = normalizeAtcToken(code);
		return normalized != null && fallsUnderAnyGroup(normalized, UNCLASSIFYING_ATC_GROUPS);
	}

	/**
	 * The ATC groups whose published name says only what their members are FOR — an indication, an
	 * organism acted against, a therapeutic area, a diagnostic use — and nothing about what they ARE
	 * (issue #183). Sharing one is a statement about PURPOSE, so it justifies a duplicate-therapy
	 * claim and not a cross-reactivity one: two ophthalmic antibiotics really are duplicate therapy
	 * for one another, and really do not thereby cross-react. That is the whole of the difference from
	 * {@link #UNCLASSIFYING_ATC_GROUPS}, which asserts nothing to either arm.
	 *
	 * <p><b>The reading, and where it draws its one hard line.</b> A name states a CLASS when it names
	 * a structural family ({@code J01CA} "Penicillins with extended spectrum", {@code M01AE}
	 * "Propionic acid derivatives", {@code N05BA} "Benzodiazepine derivatives"), a derivative class, or
	 * a molecular TARGET ({@code C10AA} "HMG CoA reductase inhibitors", {@code A02BC} "Proton pump
	 * inhibitors", {@code C09AA} "ACE inhibitors"). It states a PURPOSE when it names the condition or
	 * the organism instead. The line runs through ATC's {@code anti-} names and not around them:
	 * {@code S01AA} "Antibiotics" and {@code J04AB} "Antibiotics" name an organism to kill, while
	 * {@code R06AX} "Other antihistamines" names a receptor, {@code N06DA} "Anticholinesterases" an
	 * enzyme and {@code C01BD} "Antiarrhythmics, class III" a channel — those three are classes and
	 * stay out. {@code N01B} "Anesthetics, local" and its {@code S01HA}/{@code C05AD}/{@code R02AD}
	 * counterparts stay out for the same reason, which is the reading
	 * {@link #LOCALLY_APPLIED_ATC_GROUPS} already records for {@code N01B}.
	 *
	 * <p><b>Enumerated from the index, not from the reported cases.</b> Every level-4 subgroup in the
	 * WHO ATC index was read: the subgroup's own name where it names anything, and — for a residue that
	 * adds no term of its own, following issue #182's rule — the name of the nearest ancestor that
	 * does. 117 subgroups state a purpose and no chemistry without already being vetoed outright by
	 * {@link #UNCLASSIFYING_ATC_GROUPS}, and they are the list below. Deriving it from the index rather
	 * than from the three subgroups issue #183 names is the point: issue #161's list was hand-picked
	 * and its hardening found it incomplete in a way that reproduced the defect it was fixing.
	 * {@code S01AA}, {@code A07AA} and {@code S02AA} are here because the criterion reaches them, not
	 * because they were reported.
	 *
	 * <p><b>Why this is a per-ARM rule and not another veto.</b> The alternative put to this work was
	 * that ATC classifies purpose and route rather than chemistry, so it should license duplicate
	 * therapy only and cross-reactivity should come from the curated groups alone. Measured over the
	 * shipped 19 MB KB by driving {@link DrugSafetyValidator#validate} over each of the 5550 substance
	 * pairs the KB relates by a level-4 subgroup (2026-08-13; re-measure before relying on a figure):
	 * the blanket rule removes all 5266 cross-reactivity claims, of which 3701 rest on a subgroup that
	 * does name chemistry or a molecular target — the penicillins, the cephalosporins, the
	 * aminoglycosides, the benzodiazepines, the statins — and 1565 on purpose or on nothing, while the
	 * one cross-reactivity group the module ships replaces 24 of the 5266. It loses real signal at 2.4
	 * times the rate it removes false claims, so it is not what shipped. This list and the one above
	 * remove the 1565 between them, keep the 3701, and rename 4; 586 of the 1565 are for a pair
	 * DDInter also rates, so for those the interaction chip survives and only the class claim goes.
	 * The largest contributors are {@code L01XX} "Other antineoplastic agents" (276 claims),
	 * {@code N06AX} "Other antidepressants" (132), {@code S01AA} "Antibiotics" (118), {@code N03AX}
	 * "Other antiepileptics" (91) and {@code B05XA} "Electrolyte solutions" (73).
	 */
	private static final List<String> PURPOSE_ONLY_ATC_GROUPS = Collections
			.unmodifiableList(Arrays.asList(
					"A01AB", "A02BX", "A03AX", "A03CC", "A03DC", "A03EA", "A03ED", "A04AD", "A05AB",
					"A05AX", "A06AB", "A06AG", "A06AX", "A07AA", "A07DA", "A07EB", "A07XA", "A10XX",
					"A11HA", "B01AX", "B02BX", "B03XA", "B05BA", "B05BB", "B05BC", "B05CA", "B05CB",
					"B05XA", "B06AC", "C01EB", "C02KN", "C02KX", "C02LX", "C05AB", "C05AE", "C05XX",
					"C09XX", "D01AA", "D01BA", "D05BX", "D06BB", "D09AA", "D10AB", "D10AF", "D11AA",
					"D11AH", "G01AA", "G01BA", "G01BD", "G03AD", "G03XX", "G04BD", "G04BE", "G04CX",
					"J01XX", "J02AA", "J02AX", "J04AB", "J04AK", "J04BA", "J05AP", "J05AR", "L01XU",
					"L01XX", "M01AX", "M02AA", "M02AC", "M03AX", "M03BX", "M04AA", "M04AB", "M04AC",
					"M05BX", "N02BG", "N03AX", "N04CX", "N05AX", "N05CM", "N05CX", "N06AX", "N06CA",
					"N07BA", "N07BB", "N07BC", "N07CA", "P01AX", "P01CX", "R01AC", "R02AA", "R02AB",
					"R03BC", "R03DX", "R05CA", "R05DB", "R05FB", "S01AA", "S01AD", "S01BC", "S01CC",
					"S01LA", "S02AA", "S03AA", "V04CA", "V04CB", "V04CC", "V04CD", "V04CE", "V04CG",
					"V04CH", "V04CJ", "V04CK", "V04CL", "V04CM", "V04CX", "V09XX", "V10AX", "V10XX"));

	/**
	 * @return whether {@code code} — a full ATC code or any prefix of one, normalized as
	 *         {@link #isUnclassifyingAtcCode} normalizes its argument — asserts no chemistry: either it
	 *         asserts nothing at all, or it sits in one of the {@link #PURPOSE_ONLY_ATC_GROUPS} and so
	 *         asserts only a purpose. This is the question the CROSS-REACTIVITY arm asks; the
	 *         duplicate-therapy arm asks {@link #isUnclassifyingAtcCode}, which is strictly weaker.
	 *         <p>Subsuming its sibling is a contract and not a convenience: a group that asserts
	 *         nothing cannot assert a purpose either, so refusing it for duplicate therapy while
	 *         admitting it for cross-reactivity would have the two arms disagree about which claim is
	 *         the stronger one. {@code AtcCrossReactivityLicensingTest} pins that ordering.
	 *         <p>Package-private with one caller ({@code DrugSafetyValidator.justifiesClaim}) for the
	 *         same reason as its siblings: it is a rule about ATC's own group names, not a fact about a
	 *         substance.
	 */
	static boolean isPurposeOnlyAtcCode(String code) {
		String normalized = normalizeAtcToken(code);
		return normalized != null && (isUnclassifyingAtcCode(normalized)
				|| fallsUnderAnyGroup(normalized, PURPOSE_ONLY_ATC_GROUPS));
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
	 * @return WHICH of this entry's names {@code lowerText} carries — {@link #matchesText}'s witnesses,
	 *         in alias order, empty exactly when that returns false. Same rule, same primitive
	 *         ({@link #containsWord}), so the two cannot come to disagree about what prose carries; the
	 *         boolean stays separate because it is the hot path and must not allocate.
	 *
	 *         <p><b>Why a caller needs the witness and not only the answer (issue #209).</b> A boolean
	 *         says an entry is mentioned; it does not say by WHICH name, and that is what decides whether
	 *         the mention is about this entry's substance. One alias is routinely shared by two
	 *         substances — {@code hydrocortisone} is the display name of {@code Hydrocortisone} and an
	 *         alias of {@code Hydrocortisone butyrate}, {@code esomeprazole} likewise for the two PPI rows
	 *         the KB files under one {@code rxnorm_name} — so a set built by iterating the boolean admits
	 *         both, and only the name actually carried can be ranked. See
	 *         {@link DrugReferenceService#findImpliedByQuery}.
	 */
	List<String> aliasesIn(String lowerText) {
		if (lowerText == null) {
			return Collections.emptyList();
		}
		List<String> carried = new ArrayList<String>();
		for (String alias : aliases) {
			if (containsWord(lowerText, alias)) {
				carried.add(alias);
			}
		}
		return carried;
	}

	/**
	 * @return true when this entry names the drug in {@code drugName} — a single clinician-entered
	 *         drug NAME: an active order's display name, an allergen as recorded.
	 *         Case- and diacritic-insensitive; a null name never matches. Not restricted to
	 *         lowercased input, unlike {@link #matchesText}.
	 *
	 *         <p>This used to read "a drug NAME rather than prose", and issue #293 retired that half:
	 *         an order's names now include the free text a clinician typed, and an allergen "as
	 *         recorded" was always free text. The matcher choice is unchanged — see the tail-allowance
	 *         argument on {@link #matchesOrderName} — but what it is applied to is no longer all one
	 *         shape, and the cost of that is recorded on
	 *         {@code PatientClinicalContextBuilder.addDrugName}.
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

	/**
	 * @return WHICH of this entry's names {@code drugName} carries — {@link #matchesDrugName}'s witnesses,
	 *         in alias order, empty exactly when that returns false. The recorded-name counterpart of
	 *         {@link #aliasesIn}, and separate from it for the reason {@link #matchesDrugName} is separate
	 *         from {@link #matchesText}: the two boundary rules differ, so the set of names a recorded
	 *         display name carries is not the set its prose reading would carry ({@code Aspirine Co 81mg}
	 *         carries {@code aspirin} under this rule and nothing under the prose one, issue #147). Each
	 *         witness accessor calls the same primitive as its own boolean
	 *         ({@link #matchesOrderName} here, {@link #containsWord} there), so neither pair can drift.
	 */
	List<String> aliasesNaming(String drugName) {
		List<String> carried = new ArrayList<String>();
		for (String alias : aliases) {
			if (matchesOrderName(drugName, alias)) {
				carried.add(alias);
			}
		}
		return carried;
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
	 * <p>The two do COMPOSE at one site, in that order and only there: since issue #194
	 * {@code DrugSafetyValidator.interactionSubject} asks this first and folds only the rows that tie,
	 * because a chip about the patient's own order should name the row their chart records. Composing
	 * them the other way round is the #187 regression above.
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
		return containsBoundedToken(text, word, PROSE_TRAILING_LETTERS);
	}

	/**
	 * How many trailing letters PROSE may carry past a matched drug token before the token stops naming
	 * that drug. None: prose is words, so the boundary is symmetric — the "symmetric boundary" row of
	 * {@link #matchesOrderName}'s measured table, which is the rule a question, an answer or a rendered
	 * record gets.
	 *
	 * <p>A constant rather than a literal for the same reason {@link #MAX_ORDER_NAME_INFLECTION_LETTERS}
	 * is one, and since issue #260 for a sharper one: prose is now asked as a boolean
	 * ({@link #containsWord}) and as a position ({@link #wordIndex}), and two literals would be two
	 * decisions about which of the two boundary rules prose gets — the drift #260 was, one level up.
	 * {@link #namedOccurrences} also depends on it arithmetically: its {@code end} is
	 * {@code idx + w.length()}, which is the whole match only while this is zero.
	 */
	private static final int PROSE_TRAILING_LETTERS = 0;

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
	 * the two kinds of string differ: prose is words, an order name is typically one localized,
	 * inflected display name with a dose appended. Typically, not always — since issue #293 an order's
	 * names include the free text a clinician typed, which can be a sentence; the allowance is still
	 * right for the display-name shape it was measured on, and what it is applied to is now wider. Measured over the 3.7.1 demo dictionary (2531 drug and
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
	 * Whether {@code text} carries {@code token} under the boundary rule: the boolean view of
	 * {@link #boundedTokenIndex}, which is the one scan and has three sharers — prose matching
	 * ({@link #containsWord}) and order-name matching ({@link #matchesOrderName}) through here, and
	 * {@link #namedOccurrences} through {@link #wordIndex}, because since issue #260 the dose arm
	 * needs the POSITION rather than the answer. So the boundary rule cannot drift between them.
	 * A match needs {@code token} to start at a word boundary in {@code text} and to end at
	 * one, give or take up to {@code maxTrailingLetters} letters. Letters only: a digit is never an
	 * inflection, so a digit sitting against the token is neither stepped over nor treated as the
	 * end of the name, and a display name that glues its strength straight onto the drug name
	 * ({@code Aspirin81mg}) therefore does not match. That shape does not occur in the measured
	 * dictionary — of the 67 matches this rule drops relative to bare containment, 61 are a token
	 * inside a longer word and 6 are tails longer than two letters, none is a glued digit — and
	 * treating a digit as the end of the name instead scores identically over that corpus (829
	 * either way), so the two are indistinguishable on real data and this is the conservative one.
	 * Case-insensitive; a null or empty token never matches. Whitespace-only is the caller's
	 * business, deliberately not this method's: {@link PatientClinicalContext#hasActiveDrug} trims its
	 * token, and since issue #150 EVERY format's load drops an alias that names nothing —
	 * {@link DrugReferenceValidity#checkEntries} runs {@code sanitizeAliases} on the parsed entries
	 * whatever parsed them, so such a token no longer reaches this scan from a loaded dataset. (This
	 * paragraph used to say a hand-authored {@code json} KB was unsanitized and that the guard belonged
	 * in that parser. Both were true before #150 and neither is now: the guard is one shared load-time
	 * rule, which is the arrangement CLAUDE.md's validity bullet requires.) It still matters that the
	 * scan itself would match one, because the {@code setEntries} seam bypasses the load — and it is
	 * wider under the tail allowance than under the symmetric rule, so #147 giving allergens the tail
	 * allowance widened it.
	 *
	 * <p>Diacritic-insensitive on BOTH sides (issue #129), which is why the fold lives here rather
	 * than in either named matcher: the same accented order name reaches both of them — as the
	 * haystack when a rule token is matched against it ({@link #matchesOrderName}) and as the
	 * haystack again when the order-driven arms resolve that order's own reference entry
	 * ({@link DrugReferenceService#findForActiveOrders} → {@code findImpliedByDrugName} →
	 * {@code findByDrugName} → {@link #matchesDrugName}, which is this rule again since issue #147),
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
	 *
	 * <p>Since issue #260 one caller does its own folding — {@link #namedOccurrences} folds its needle
	 * and takes its haystack already folded, because it returns a POSITION and the fold is not
	 * length-preserving. That is a deliberate exception to "the fold lives here" and not a second copy of
	 * the rule: both go through {@link #foldedLower}, so there is still exactly one expression of what
	 * this scan's operands must be.
	 */
	private static boolean containsBoundedToken(String text, String token, int maxTrailingLetters) {
		if (text == null || token == null) {
			return false;
		}
		return boundedTokenIndex(foldedLower(text), foldedLower(token), maxTrailingLetters, 0) >= 0;
	}

	/**
	 * @return {@code value} lowercased ({@link Locale#ROOT}) and then {@link #foldDiacritics}-folded —
	 *         the form both operands of {@link #boundedTokenIndex} must be in, named once so that a
	 *         caller preparing them itself cannot apply half of it or apply the two in the other order.
	 *
	 *         <p><b>{@code toLowerCase} is idempotent, and that alone is what lets a caller holding an
	 *         already-lowercased string pass it straight in</b> ({@code DrugSafetyValidator.attributedDoses}
	 *         does). <b>The composition is NOT.</b> Measured 2026-08-14 through this method: the sequence
	 *         {@code a}, U+1D16D, U+0E31, U+1D165 folds to {@code a}, U+1D16D, U+1D165 and folding THAT
	 *         gives {@code a}, U+1D165, U+1D16D — stripping a combining mark of canonical class 0 from
	 *         between two of higher class merges two canonical-ordering runs, and the next NFD reorders
	 *         them. No reference or chart string reaches that shape, but do not build an argument on
	 *         re-folding being free; {@code DrugSafetyValidator.substanceOwnsDose} records what it still
	 *         costs at the one place a string is folded twice.
	 */
	static String foldedLower(String value) {
		return foldDiacritics(value.toLowerCase(Locale.ROOT));
	}

	/** Any run of whitespace, for {@link #collapseWhitespace}. */
	private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

	/**
	 * @return {@code value} with every run of whitespace collapsed to one space, or {@code null} for a
	 *         null input. NOT trimmed — a caller wanting that does it itself, because the two sides of
	 *         the comparison this exists for want different things: a recorded token is trimmed on the
	 *         way in, and a haystack of chart prose must not be.
	 *
	 *         <p>Here, and named once, for the reason {@link #foldedLower} is: it is the form BOTH
	 *         operands of one comparison must be in, and two copies of it is how the two sides come
	 *         apart. Since issue #293 {@code PatientClinicalContextBuilder.addRaw} collapses every
	 *         recorded string it collects and {@code PatientClinicalContext.ActiveDrugOrder.namedIn}
	 *         collapses the chart prose it searches them in; those were two independent
	 *         {@code Pattern.compile("\\s+")} constants, and review measured that widening one and not
	 *         the other — to fold {@code U+00A0}, say, for a name pasted out of a word processor —
	 *         reddened NOTHING in the whole suite while silently making a name unfindable in the record
	 *         that renders it. One definition makes that state unconstructible rather than untested.
	 */
	static String collapseWhitespace(String value) {
		return value == null ? null : WHITESPACE_RUN.matcher(value).replaceAll(" ");
	}

	/**
	 * The boundary rule above, as a POSITION rather than a boolean.
	 *
	 * <p>Split out so that a caller needing to know WHERE one of these names sits shares the rule with
	 * every caller needing only whether it occurs, instead of re-expressing the boundary conditions
	 * beside it. That was issue #260: {@code DrugSafetyValidator}'s dose arm gated a clause on
	 * {@link #matchesText} and then located the name in that same clause with {@link String#indexOf}, so
	 * the two disagreed in both directions — a name the prose rule does not find ({@code penicillin}
	 * inside {@code penicillins}) was located anyway and vetoed a real dose, and a name it does find
	 * ({@code paracetamol} written {@code paracétamol}) was not located and the subject could not claim
	 * its own dose. Both silently, which is the direction the dose arm exists to prevent.
	 *
	 * <p><b>Operands pre-folded and pre-lowercased, and that is a contract rather than an economy.</b>
	 * Neither transform preserves length — the fold decomposes and drops combining marks, and
	 * {@code toLowerCase} turns the single character {@code İ} into two — so an index into the
	 * transformed string is not an index into the string it came from, and doing either transform HERE
	 * would silently shift every index this returns. A caller comparing this index against any other
	 * position must have produced that position in the SAME transformed text — which is why
	 * {@link DrugSafetyValidator} folds a clause once and reads every position out of that one string.
	 *
	 * @param t the haystack, already lowercased and {@link #foldDiacritics}-folded
	 * @param w the needle, likewise
	 * @return the index of the first occurrence of {@code w} in {@code t} at or after {@code from} that
	 *         satisfies the rule, or -1 when there is none
	 */
	private static int boundedTokenIndex(String t, String w, int maxTrailingLetters, int from) {
		if (w.isEmpty()) {
			// The FOLDED form, which is what the contract above delivers and why the check reads well
			// here even though nothing here folds: a token of nothing but combining marks folds to
			// empty, and the empty token matches almost anything below.
			return -1;
		}
		int idx = t.indexOf(w, from);
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
						return idx;
					}
				}
			}
			idx = t.indexOf(w, idx + 1);
		}
		return -1;
	}

	/**
	 * @return EVERY occurrence of one of this entry's names in {@code foldedLowerText}, each carrying its
	 *         distance from {@code pos} and the span it covers; empty — and then immutable, so a caller
	 *         pools the answer rather than appending to it — when none of them occurs. Where
	 *         this entry is NAMED, answered by the same rule {@link #matchesText} answers whether it is
	 *         named at all. A distance is zero when {@code pos} falls inside that occurrence.
	 *
	 *         <p><b>All of them, not the nearest, and that is the whole of issue #270's second half.</b>
	 *         The dose arm has to ask whether an occurrence is a sub-span of some rival's — a name
	 *         present only as part of a longer name the same clause carries is not independently named
	 *         there — and that is a question about EACH occurrence. Answer it against one reported
	 *         occurrence and a substance the clause names twice, once nested and once on its own, is
	 *         judged on whichever the scan happened to keep: it loses a dose it plainly owns, and which
	 *         way it goes depends on alias order. Measured on {@code amoxicillin and clavulanate was at
	 *         2.5 mg, clavulanate was stopped} — both of Clavulanate's occurrences sit at the same
	 *         distance, so the substance IS independently named at the minimum, and reporting the nested
	 *         one silenced it.
	 *
	 *         <p><b>The span, and not merely the matched length</b> (issue #270). A caller comparing two
	 *         substances at the same distance needs to know whether one occurrence CONTAINS the other,
	 *         and length alone cannot answer that: the metric is {@code idx - pos} on one side and
	 *         {@code pos - end} on the other, so with a dose sitting BETWEEN two names an equal-distance
	 *         rival on the far side that is merely longer looks like a container and is not one.
	 *
	 *         <p><b>The PROSE rule</b>, through {@link #wordIndex}, which is the same binding of
	 *         {@link #boundedTokenIndex} that {@link #containsWord} is — so this shares the rule by
	 *         construction rather than by both spelling the same allowance. Prose gets symmetric word
	 *         boundaries and a clinician-entered drug NAME gets {@link #matchesOrderName}'s left boundary
	 *         plus a short tail, and widening one to serve the other was issues #86, #128, #147 and #209.
	 *         Its caller has already gated the clause on {@link #matchesText}, so answering the WHERE by a
	 *         different rule than the WHETHER is the same mistake one level down — which is what issue
	 *         #260 was.
	 *
	 *         <p>{@code foldedLowerText} must be in {@link #foldedLower} form and {@code pos} an index
	 *         into THAT string; see {@link #boundedTokenIndex} for why positions from the two forms may
	 *         not be mixed. The names read are this entry's {@code aliases} — the same list
	 *         {@link #matchesText} reads.
	 *
	 *         <p>The metric is asymmetric by one, and always was: {@code end} is exclusive and the test
	 *         is {@code pos > end}, so a name ending immediately before {@code pos} scores 0 while one
	 *         starting immediately after it scores 1, and a tie therefore goes to the earlier name. Not
	 *         reachable from the dose arm — {@code DOSE_MG} starts on a digit, and a digit at {@code end}
	 *         fails the right-boundary test, so no accepted occurrence ends exactly at the dose.
	 *
	 *         <p>Here rather than in the caller because this is a question about the entry's own names,
	 *         and because keeping it beside them is what lets it share the boundary rule instead of
	 *         restating it — the whole of the defect it closes.
	 */
	List<NamedOccurrence> namedOccurrences(String foldedLowerText, int pos) {
		if (foldedLowerText == null) {
			return Collections.emptyList();
		}
		// Allocated on the first hit and not before: the dose arm asks this of EVERY entry in the
		// knowledge base per stated dose, and most entries are named nowhere in the clause, so most calls
		// allocate nothing. Thrift, not a fix for a cost anyone measured — and the empty answer is shared
		// with the null-text path above so both return one shape.
		List<NamedOccurrence> found = null;
		for (String alias : aliases) {
			if (alias == null) {
				continue;
			}
			String w = foldedLower(alias);
			int idx = wordIndex(foldedLowerText, w, 0);
			while (idx >= 0) {
				// w.length() is the whole match only because the prose rule allows no trailing letters;
				// under matchesOrderName's allowance the match can run past it and every distance on the
				// right-hand side would be overstated. wordIndex is what keeps that true.
				int end = idx + w.length();
				int distance = pos < idx ? idx - pos : (pos > end ? pos - end : 0);
				if (found == null) {
					found = new ArrayList<NamedOccurrence>();
				}
				found.add(new NamedOccurrence(distance, idx, end));
				idx = wordIndex(foldedLowerText, w, idx + 1);
			}
		}
		return found != null ? found : Collections.<NamedOccurrence> emptyList();
	}

	/**
	 * One occurrence of one of an entry's own names in a clause: how far it sits from a position, and the
	 * span it covers there. Immutable, and package-private because the only question it answers is the
	 * dose arm's (issue #270).
	 */
	static final class NamedOccurrence {

		private final int distance;

		private final int start;

		private final int end;

		NamedOccurrence(int distance, int start, int end) {
			this.distance = distance;
			this.start = start;
			this.end = end;
		}

		int getDistance() {
			return distance;
		}

		/**
		 * @return whether this occurrence strictly CONTAINS {@code other} — covers all of it and more. The
		 *         fact the dose arm settles a claim by: a name present only as part of a longer name the
		 *         same clause carries is not independently named there, whichever side of it the dose
		 *         falls on. Strictly, so two occurrences of the same span never each contain the other.
		 *
		 *         <p>It answers only that. Whether the container is near the dose BY the name it contains
		 *         is a second question — a longer name can reach the dose with a word of its own — and the
		 *         dose arm asks it by pairing this with the two distances; see
		 *         {@code DrugSafetyValidator.nearnessInheritedFrom}, which is where that pairing lives so
		 *         no caller has to remember it.
		 */
		boolean strictlyContains(NamedOccurrence other) {
			return start <= other.start && end >= other.end
					&& (end - start) > (other.end - other.start);
		}
	}

	/** @return {@link #containsWord}'s rule as a POSITION — {@link #boundedTokenIndex} with the prose
	 *          allowance bound once, so the boolean and the index cannot come to disagree about which of
	 *          the two boundary rules prose gets. Operands in {@link #foldedLower} form. */
	private static int wordIndex(String foldedLowerText, String foldedLowerWord, int from) {
		return boundedTokenIndex(foldedLowerText, foldedLowerWord, PROSE_TRAILING_LETTERS, from);
	}

	/** Unicode non-spacing marks — the combining accents an NFD decomposition separates out. */
	private static final Pattern NON_SPACING_MARKS = Pattern.compile("\\p{Mn}+");

	/**
	 * @return {@code value} with its diacritics folded away — canonically decomposed (NFD) and
	 *         stripped of combining non-spacing marks, so {@code budésonide} compares as
	 *         {@code budesonide}. The one definition; never call {@link Normalizer} for this elsewhere.
	 *
	 *         <p><b>Not length-preserving</b>, so an index into the folded string is not an index into
	 *         the string it was folded from. Anything reading POSITIONS out of folded text has to fold
	 *         once and take every position from that one form — see {@link #boundedTokenIndex}.
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
	 *         is most order-name text, and this runs on hot paths — once per (rule token, order name)
	 *         pair, up to a few hundred rules per question — so the common path must not allocate.
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
