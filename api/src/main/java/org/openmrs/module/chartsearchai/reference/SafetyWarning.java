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
import java.util.List;

/**
 * A non-blocking advisory raised by {@link DrugSafetyValidator} after the LLM
 * answers. A warning <em>annotates</em> the answer — it never rewrites or
 * suppresses it. The clinician decides. Carried on
 * {@code ChartSearchService.ChartAnswer} and rendered as a chip below the
 * answer in the frontend.
 */
public class SafetyWarning {

	/** Overdose: a daily dose parsed from the answer exceeds the reference maximum for the patient's
	 *  age band — or, when a fresh weight is on record, a per-administration dose exceeds the band's
	 *  {@code mgPerKgMax} × weight. One warning per drug; the daily ceiling wins when both trip. */
	public static final String TYPE_OVERDOSE = "overdose";

	/**
	 * Interaction: two drugs the reference data relates — by a dataset rule (one the source RATED has
	 * to clear {@code chartsearchai.drugSafety.minInteractionSeverity}; an unrated one is exempt, so
	 * every hand-authored rule shows), or by a shared ATC level-4 subgroup / curated cross-reactivity
	 * group, which carry no severity and are never floor-filtered.
	 *
	 * <p>Either side may come from the question, from the answer's own proposal, or from the patient's
	 * chart, so there are three joins — a drug in play against an active order (the patient-specific
	 * one); several drugs the QUESTION names against each other (a reference lookup that may involve
	 * no drug the patient takes, so it is the one join whose detail does NOT claim an active order —
	 * issue #114); and, for a question that asks to be screened but names no drug, the patient's own
	 * active orders against each other (a chart drug on BOTH sides, so its detail names an active
	 * order exactly as the first join's does — issue #113). See {@code DrugSafetyValidator}.
	 */
	public static final String TYPE_INTERACTION = "interaction";

	/**
	 * Contraindication: a drug is contraindicated by an active allergy or condition. Two joins — a drug
	 * IN PLAY (asked about in the question, or named by the answer on its own authority), and the
	 * patient's OWN ACTIVE ORDERS, scoped to what the response is about — either the drug or the
	 * recorded finding must be named by the question, the answer or a cited record. (Enumerated for the same
	 * reason {@link #TYPE_INTERACTION} enumerates its three: which joins a chip type can come from is
	 * what a renderer needs to know about it.)
	 *
	 * <p>The first is keyed off the question, not ONLY the answer: the headline case is a recorded
	 * allergy to the very drug the clinician asked about, where the answer may never write the drug's
	 * name at all (issue #135). The second exists because the in-play framing could not ask "is the
	 * patient allergic to something they are TAKING?" — a prescribing error the chart already contains,
	 * which the ANSWER's wording alone must not be able to hide (issue #143): a prescribed drug appears
	 * in a cited {@code drug_order} record, so echo scoping read the answer's mention of it as a
	 * recitation. What that join is bounded by instead is the whole response — question, answer and
	 * cited records, either side of the chip counting — because this module annotates answers and an
	 * annotation owing nothing to what was asked is an alert it has no machinery to carry. So a
	 * contraindication chip does NOT imply that anything proposed the drug; it may be reporting a
	 * medication the patient is already on. See {@code DrugSafetyValidator}.
	 */
	public static final String TYPE_CONTRAINDICATION = "contraindication";

	private final String type;

	private final String drug;

	private final String detail;

	private final String severity;

	private final boolean unratedRelationship;

	private final boolean uncorroboratedChartMatch;

	/** The rule {@link #reconciledPartnerNoteName} was decided on — see that accessor. Null for every
	 *  warning that carries no reconciled name, which since issue #339 is every warning but an
	 *  INTERACTION chip whose partner was reconciled — folded or not, and in either active-order arm.
	 *  A chip whose reconciliation refused, or whose partner the ladder never reached, answers null
	 *  here too, deliberately, so that a refusal and an absent answer are one answer to the record. */
	private final DrugReference.Interaction reconciledRule;

	private final String reconciledNoteName;

	/** @see #chartOrderBridges() */
	private final List<ChartOrderBridge> chartOrderBridges;

	/** A warning raised from something the reference data assigns no severity to — see
	 *  {@link #getSeverity()} for which joins those are. */
	public SafetyWarning(String type, String drug, String detail) {
		this(type, drug, detail, null);
	}

	public SafetyWarning(String type, String drug, String detail, String severity) {
		this(type, drug, detail, severity, false, false, null, null,
				Collections.<ChartOrderBridge> emptyList());
	}

	/**
	 * As the constructor above, for a chip that also states which of the patient's own orders its
	 * substances were resolved from — see {@link #chartOrderBridges()}, which is published as this
	 * chip's {@code chartOrderBridges} wire key (issue #347).
	 *
	 * <p>Public for the reason the shorter constructors above are: the wire-facing shape is public,
	 * and since issue #347 the bridges are part of it. (Neither of those carries that reason in its
	 * own javadoc, so it is given here rather than cross-referenced.) (What made it NECESSARY is
	 * narrower — the test that pins their serialization lives in {@code web.rest} and cannot reach
	 * {@code interaction(..)} below, which already answers "build a chip carrying bridges" but is
	 * package-private here. Stated second because the policy sentence is the stronger reason and the
	 * neighbouring factory's javadoc gives it for the others.) It is not the production path: {@code DrugSafetyValidator.interactionWarning}
	 * builds a chip that also carries a reconciled partner name and a folded relationship, so it takes
	 * the private constructor below. Nothing here is an impossible pair — the reason issue #298 gave a
	 * FACTORY to the contraindication shape rather than widening a constructor does not reach this
	 * one, since a chip of any type may in principle have been resolved from an order.
	 */
	public SafetyWarning(String type, String drug, String detail, String severity,
			List<ChartOrderBridge> chartOrderBridges) {
		this(type, drug, detail, severity, false, false, null, null, chartOrderBridges);
	}

	/**
	 * A contraindication chip's warning, and the only shape that can carry
	 * {@link #restsOnAnUncorroboratedChartMatch()} (issue #308). A FACTORY rather than a wider PUBLIC
	 * constructor, for the reason issue #298 states of a label and its source: the two flags
	 * describe relationships that cannot both hold — an interaction never matches a rule against the
	 * chart's allergy list, and a contraindication carries no rating for a fold to outrun — so a
	 * constructor taking both would offer a caller a pair that has no meaning. Naming the type here
	 * also keeps {@link #TYPE_CONTRAINDICATION} out of the call site, which is where a chip arm would
	 * otherwise repeat it.
	 *
	 * <p>Package-private, matching the accessor: a caller may set only what it may read back. The one
	 * caller is {@code DrugSafetyValidator.addContraindications} — the curated-rule arm, the only arm
	 * whose warning is derived from a rule matched against the chart at all. The allergen arm's own
	 * three sentences are built from a {@code RecordedAllergen} and keep the public three-argument
	 * form, so they answer false by construction rather than by remembering to.
	 *
	 * @param uncorroboratedChartMatch see {@link #restsOnAnUncorroboratedChartMatch()}
	 */
	static SafetyWarning contraindication(String drug, String detail,
			boolean uncorroboratedChartMatch) {
		return new SafetyWarning(TYPE_CONTRAINDICATION, drug, detail, null, false,
				uncorroboratedChartMatch, null, null, Collections.<ChartOrderBridge> emptyList());
	}

	private SafetyWarning(String type, String drug, String detail, String severity,
			boolean unratedRelationship, boolean uncorroboratedChartMatch,
			DrugReference.Interaction reconciledRule, String reconciledNoteName,
			List<ChartOrderBridge> chartOrderBridges) {
		this.type = type;
		this.drug = drug;
		this.detail = detail;
		this.severity = severity;
		this.unratedRelationship = unratedRelationship;
		this.uncorroboratedChartMatch = uncorroboratedChartMatch;
		this.reconciledRule = reconciledRule;
		this.reconciledNoteName = reconciledNoteName;
		// Copied and wrapped rather than stored as handed: this list travels to
		// DrugReferenceInjector.renderFinding, so a caller that went on filling its own builder would
		// change what a record already published. Never null, so no reader branches on absence — an
		// empty list is the honest answer wherever nothing needed reconciling, which is most chips.
		// chartOrderBridges()'s javadoc is the one place that says what empty covers.
		this.chartOrderBridges = chartOrderBridges == null || chartOrderBridges.isEmpty()
				? Collections.<ChartOrderBridge> emptyList()
				: Collections.unmodifiableList(new ArrayList<ChartOrderBridge>(chartOrderBridges));
	}

	/**
	 * An INTERACTION chip's warning, and the only shape that can carry
	 * {@link #reconciledPartnerNoteName} (issue #297). A FACTORY rather than a wider PUBLIC
	 * constructor, for the reason {@link #contraindication} states of its own flag: these facts travel
	 * together only for an interaction chip, and a constructor offering them beside
	 * {@code uncorroboratedChartMatch} would offer a caller a combination that has no meaning. No
	 * ordinal is given for the argument it replaces — the private constructor's width moves whenever a
	 * fact is added (it did at issue #349), and the point is which SHAPE may set what. They are
	 * not the same fact and do not arrive together: only the drug-in-play arm can FOLD, so only its
	 * chips carry {@code unratedRelationship}, while since issue #339 both active-order arms set a
	 * reconciled name. Only the drug-in-play arm's is READ today — {@code DrugReferenceInjector}
	 * reaches this accessor from the loop over the entries a question put in play, and a screening
	 * question puts none in play by its own gate — so the screening arm's is set for the shape rather
	 * than for a consumer, which is what stops a later reader having to remember to set it.
	 *
	 * <p>Package-private, matching the accessors: a caller may set only what it may read back. The
	 * public constructors above are public because the wire-facing shape is, and
	 * neither of these two facts is part of it — public here would offer an outside caller a way to
	 * govern the injected record's strength, and the name of a partner in it, with no way to observe
	 * either assertion from where it was made. The one caller is
	 * {@code DrugSafetyValidator.interactionWarning} — which is <b>not</b> the only place an interaction
	 * chip is built, and saying so would be false: the class-only chip inside
	 * {@code addInteractionWarnings} itself and {@code addQuestionPairInteractions} both build one from a
	 * public constructor. Neither can fold, so neither has either of these facts to carry, and both
	 * answer false/null by construction rather than by remembering to — the same argument
	 * {@link #contraindication} makes for the allergen arm's three sentences. This factory replaced a
	 * five-argument package-private CONSTRUCTOR that carried {@code unratedRelationship} alone; that
	 * constructor is gone rather than left unreachable beside this, because a second way in is a second
	 * way for the two facts to be set apart.
	 *
	 * @param unratedRelationship whether this warning also asserts a relationship the source rates
	 *        nothing for — see {@link #carriesUnratedRelationship()}
	 * @param reconciledRule the rule this chip's partner was reconciled on, or null when nothing
	 *        reconciled
	 * @param reconciledNoteName see {@link #reconciledPartnerNoteName} — null when the reconciliation
	 *        refused or reached no co-medication, so that a refusal and an absent answer are one answer
	 *        here, as they are for the chip
	 * @param chartOrderBridges see {@link #chartOrderBridges()}, which is canonical for what empty
	 *        covers — empty is most chips and is not a degraded state
	 */
	// Three facts travel here, not two: the paragraphs above are worded for the pair issue #297 added
	// and issue #349 put a third beside them. Read the @param list rather than any count in the prose.
	static SafetyWarning interaction(String drug, String detail, String severity,
			boolean unratedRelationship, DrugReference.Interaction reconciledRule,
			String reconciledNoteName, List<ChartOrderBridge> chartOrderBridges) {
		return new SafetyWarning(TYPE_INTERACTION, drug, detail, severity, unratedRelationship, false,
				reconciledRule, reconciledNoteName, chartOrderBridges);
	}

	/** One of {@link #TYPE_OVERDOSE}, {@link #TYPE_INTERACTION}, {@link #TYPE_CONTRAINDICATION}. */
	public String getType() {
		return type;
	}

	/**
	 * The reference drug the warning is about — its display label, which may carry a parenthesized
	 * generic synonym when the dataset's display name diverges from it, e.g.
	 * {@code "Acetylsalicylic acid (aspirin)"} (see {@link DrugReference#displayLabel()}).
	 *
	 * <p>Since issue #206 this names a SUBSTANCE, not a finding, and not the dataset row an arm
	 * happened to match. Several warnings about one substance therefore carry the same string by
	 * construction: issue #238 records a live patient with seven hydrocortisone chips that all do.
	 *
	 * <p><b>So it is not a deduplication key.</b> A client collapsing on {@code (type, drug)} would
	 * discard six of those seven distinct findings — #238's second item exists because this class's
	 * javadoc invited exactly that, saying the field was for "grouping/sorting/deduping" (it said so
	 * on {@link #getDetail()}, where a reader is least likely to look). Nor is it a stable substance
	 * name to group on: which arms share one subject and which resolve their own is
	 * {@code DrugSafetyValidator}'s to state, and {@code SubstanceSubjects}' javadoc states it,
	 * exemptions and residues included. Do not re-derive that list here — it has moved.
	 *
	 * <p>What distinguishes one warning from another is {@link #getDetail()}: of the fields a
	 * client receives, it is the one that tells warnings about a single substance apart, because it
	 * names the interacting order, the allergen or the ceiling that particular finding is about. Since
	 * issue #340 {@link #getSeverity()} travels beside it on the wire and may differ too —
	 * that issue's own capture has a Major and a Minor chip of one drug — but it is no more a key than
	 * this field is: two findings about one substance commonly share a rating, and every unrated one
	 * shares null. Key per-finding identity on {@code detail}, or on the whole warning.
	 */
	public String getDrug() {
		return drug;
	}

	/**
	 * The warning as complete, standalone prose naming its own drug — e.g. "The stated
	 * Ibuprofen dose ~2400 mg/day exceeds the 1200 mg/day maximum for ages 2-11", or
	 * "Warfarin interacts with active order aspirin — Major. …".
	 *
	 * <p><b>How many SENTENCES it runs to is not part of the contract, and no rule about that
	 * belongs here.</b> This javadoc read "one complete, standalone sentence" while illustrating it
	 * with the two-sentence second example above, and a first attempt to correct that put a rule in
	 * its place ("one for a contraindication, two for a rated interaction, three when folded") which
	 * measurement refuted as well. What the composition depends on is authored TEXT — a dataset's
	 * mechanism description is whatever its author wrote — so measure it over the dataset you ship if
	 * you need a number. {@link #getSeverity()} is canonical for how the dataset builds a rated rule's
	 * NOTE, which is the part of a detail this field's own contract turns on, and it is not restated
	 * here. A renderer that splits or truncates at a
	 * sentence boundary drops the mechanism text, which is the chip's clinical content — and on a
	 * folded chip the class sentence after it. Render the whole string.
	 *
	 * <p><b>Renderers should display this alone</b>; prefixing {@link #getDrug()} duplicates the
	 * subject, because every detail already names it. It is also this field, not
	 * {@link #getDrug()}, that tells one warning from another — see {@link #getDrug()} for why.
	 */
	public String getDetail() {
		return detail;
	}

	/**
	 * The severity the reference data assigns the rule this warning was raised from — {@code Major},
	 * {@code Moderate}, {@code Minor} or {@code Unknown} for a rule the shipped DDInter dataset rates,
	 * though that is what one dataset publishes and not a closed set (see the wire paragraph below) —
	 * ranked by {@code DrugSafetyValidator.severityPriority}, which is also what all three interaction
	 * arms order their chips on — the two pairwise arms directly, and the drug-in-play arm within each
	 * side of the withholds/cautions split it orders on first (issue #346).
	 *
	 * <p><b>Null means the source rates nothing here</b>, which is a real distinction rather than a
	 * missing value: a hand-authored curated rule is deliberately UNRATED (and outranks {@code Major}
	 * in that same ordering — unrated is not low-rated), an ATC-subgroup or cross-reactivity join
	 * carries no rating at all, and neither a contraindication nor an overdose has one. Callers must
	 * not read null as "no severity was determined".
	 *
	 * <p>Exposed for issue #207. The chip arms order themselves by this value and then discarded it,
	 * so the ONLY remaining trace of the key they sorted on was a word inside the rendered
	 * {@link #getDetail()} prose — which meant the ordering could only be asserted by parsing
	 * clinician-facing text, anchored on a clause the module rewords freely. Measured: rewording that
	 * clause left {@code thePairChipsAreOrderedBySeverityAndBounded} green while it asserted nothing at
	 * all.
	 *
	 * <p><b>Published on the wire since issue #340</b>, as the {@code severity} key of every
	 * {@code safetyWarnings} chip — on the blocking {@code /search} response and on both SSE events
	 * that carry chips, since all three reach
	 * {@code ChartSearchAiRestController.serializeSafetyWarnings} through the one
	 * {@code putSafetyChips} payload writer. Verbatim and UNNORMALIZED, which is
	 * deliberate rather than lazy: the field is the dataset's rating, and coercing it would put the
	 * wire at odds with the very prose a client is being told to stop parsing. What it publishes is the
	 * SOURCE's rating, not this module's judgment about what may be done — which is the separate thing
	 * issue #283 keeps off the wire ({@code DrugSafetyValidator.licensesWithholding} and the
	 * {@code DrugReferenceInjector.STRENGTH_*} clauses are prompt-facing only).
	 *
	 * <p><b>A non-null value is NOT a guarantee the module recognises it, so it does not mean "the
	 * source rated this".</b> {@code DrugSafetyValidator.severityRank} trims and lower-cases, and maps
	 * every value it does not recognise — including null — to the same answer, UNRATED: exempt from
	 * {@code clearsSeverityFloor}, sorted above {@code Major} by {@code severityPriority}, and
	 * licensing withholding in {@code ratingLicensesWithholding}. So there are three classes on the
	 * wire and not two, and the third is reachable: {@code DrugReference.Interaction}'s severity is a
	 * plain Jackson-bound string with no vocabulary check and no {@code DrugReferenceValidity} rule
	 * over it, so an operator's {@code json} dataset supplies its own words. A reader comparing this
	 * value should trim and case-fold as {@code severityRank} does, and treat anything it does not
	 * recognise as unrated rather than as a floor. The shipped DDInter dataset publishes exactly
	 * {@code Major}, {@code Moderate}, {@code Minor} and {@code Unknown}, none null and none blank,
	 * and the bundled curated seed rates none of its rules; ADR Decision 62 carries that census and
	 * the production method that produced it, and is not restated here.
	 *
	 * <p><b>Read this field; do not fall back to parsing {@link #getDetail()}.</b> On the bundled
	 * DDInter dataset the rating is somewhere in that prose: {@code DdiDrugReferenceSource.noteFor}
	 * builds every note as {@code severity + ". " + mechanism}, or as
	 * {@code severity + " severity interaction (… no mechanism description on file)."} where the row
	 * has none, and {@code DrugSafetyValidator.interactionWarning} appends that note. Measured
	 * 2026-08-30 over the shipped KB's 590,312 links: no note is null or blank, no rated rule carries
	 * none, and none fails to start with its own severity word.
	 *
	 * <p><b>That measurement is about the NOTE, and no claim about where the rating sits in the
	 * rendered detail follows from it.</b> Three attempts to state one have been refuted, which is why
	 * none is made here. The detail is assembled from chart text as well as dataset text, and the
	 * chart's half goes in unquoted — a free-text prescription display can carry the very delimiter
	 * the chip appends its note after, which
	 * {@code NonCodedDrugOrderNameTest.aFreeTextDisplayIsPrintedIntoTheChipUnquoted} pins as current
	 * behaviour. What is left, and is enough, is that {@code detail} is prose this module rewords
	 * freely and holds out as no contract. And on an operator {@code sourceFormat=json} dataset the
	 * rating need not be in the detail at all: note and severity are independently authored fields
	 * there, so a rule may carry no note, in which case {@code interactionWarning} appends none, or
	 * carry one whose leading word is a different rating.
	 * {@code ChartSearchAiSafetyWarningSeverityWireTest.theRatingIsPublishedEvenWhereTheProseNamesItNowhere}
	 * drives both of those.
	 *
	 * <p>Since issue #283 this value has a second reader, and it decides more than an order:
	 * {@code DrugSafetyValidator.ratingLicensesWithholding} splits it into "a reason to withhold" and
	 * "a caution to note", {@code licensesWithholding(SafetyWarning)} composes that with
	 * {@link #carriesUnratedRelationship()} for the whole finding, and
	 * {@code DrugReferenceInjector.renderFinding} states the answer in the record the model reads — so
	 * how strongly a safety answer opens now rests on this field, for an INTERACTION finding — and on
	 * it for EVERY such finding the answer addresses rather than for one: where several name the drug
	 * and their clauses disagree, the prompt ranks withholding above a caution, so one Major among
	 * Minors still decides the lead. Only for one TYPE, though: a CONTRAINDICATION states withholding
	 * unconditionally and never consults this value (it carries none — the arms that raise one use the
	 * three-argument constructor), because a recorded allergy is not a caution at any rating. The null
	 * rule above is what carries the most weight where the value IS read: unrated is not low-rated,
	 * and reading it as a caution would soften a curated rule an implementation authored deliberately.
	 */
	public String getSeverity() {
		return severity;
	}

	/**
	 * Whether this warning asserts, beside whatever {@link #getSeverity()} rates, a relationship the
	 * source rates nothing for — today exactly the folded chip of issue #171: a co-medication that is
	 * both a rated interaction partner and class-related yields ONE warning carrying the rule's note
	 * and the class arm's duplicate-therapy or cross-reactivity sentence together.
	 *
	 * <p>It exists because {@link #getSeverity()} deliberately keeps reporting the RULE's rating on a
	 * folded warning — folding must not raise or lower what the pair is rated, which is what the chip
	 * ordering depends on — so the rating alone cannot say how strong the whole finding is. Reading it
	 * as the rating did made the fold LOWER a claim: a Minor rule folded with duplicate therapy read as
	 * a caution, while that same relationship on its own licenses withholding (issue #283).
	 *
	 * <p><b>It is scoped to the arm that can fold, and only one of the three can.</b>
	 * {@code DrugSafetyValidator.classRelationships} runs per IN-PLAY substance, so the interaction
	 * SCREEN (issue #113), which answers a question naming no drug, builds through the narrow
	 * {@code interactionWarning} and never sets this flag. One Minor-rated pair therefore states
	 * withholding from the drug-in-play arm and a caution from the screen, on the same two active
	 * orders: measured through the real {@code injectRecords} over
	 * {@code chartsearchai-test/ddi-folded-minor-class-pair.json}, whose two drugs share {@code N06BA}.
	 * That is a property of which arm ran rather than of the pair. It is left there deliberately —
	 * giving the screen the class arm's sentence would change the DETAIL of a published
	 * {@code safetyWarnings} chip, which issues #113 and #171 would both have to re-measure — and
	 * pinned by {@code FoldedFindingStrengthTest
	 * .theScreeningArmStatesTheWeakerClaimForTheSamePairBecauseItRunsNoClassArm} so that moving either
	 * arm is visible. The question-pair arm does not set it either — its warning is built at its own
	 * call site — so a question-pair finding always states the strength its RATING licenses. This
	 * javadoc read "there it is no asymmetry: that arm's two drugs need not be on the chart at all,
	 * so there is no co-medication for a class relationship to hold against", and the second half
	 * does not follow from the first: the patient CAN be on one of a question-named pair. What holds
	 * without it is narrower and is all this flag needs — the fold happens only inside
	 * {@code addInteractionWarnings}, so a class relationship that does hold for one of those drugs
	 * is never folded into the pair finding; it reaches the model as its own unrated warning, which
	 * licenses withholding on the rating leg. Whether the two arms can report one pair at once was
	 * not established here — {@code coveredByActiveOrderArm} asks {@code hasActiveDrug} where the
	 * pair walk asks {@code identifies}, and the two are different questions.
	 *
	 * <p>Not serialized; the wire shape is unchanged.
	 */
	boolean carriesUnratedRelationship() {
		return unratedRelationship;
	}

	/**
	 * Whether nothing corroborates, as a record of this drug, the chart match behind the CLAUSE this
	 * warning's sentence belongs to — the fourth question of CLAUDE.md's injected-record rule, asked
	 * once so the two injected channels cannot answer it differently (issue #308).
	 *
	 * <p><b>Of the collapsed CLAUSE, not of the one rule this sentence came from</b>, and the
	 * difference is reachable rather than pedantic. {@code DrugSafetyValidator.contraindicationFinding}
	 * keys two self-named allergy rules of one entry alike (issue #146), so they are one chip and one
	 * rendered clause while each is put to the chart on its own token — and one corroborated rule
	 * carries the key, which is the fold {@code DrugSafetyValidator.addContraindications} resolves and
	 * the same fold the injected {@code drug_reference} record makes. So this can answer false of a
	 * sentence whose OWN rule nothing corroborates, because a sibling rule of its clause is
	 * corroborated; {@code corroboratedByTheChart} is the per-rule primitive underneath that fold and
	 * is not this. Reading this as the negation of that primitive is the first cut ADR Decision 44
	 * refutes, and it reddens
	 * {@code UncorroboratedFindingProvenanceTest.oneCorroboratedRuleOfACollapsedKeyClearsTheClauseForTheWholeKey}.
	 *
	 * <p>It can also answer false because ANOTHER key of this entry states the identical clause TEXT as
	 * recorded, which is the record's own second stage ({@code uncorroborated.removeAll(recorded)}) and
	 * not a second rule about this key: an allergy rule and a condition rule may carry one note, and a
	 * record cannot both state a string as this chart's reading and hedge it — nor may a finding beside
	 * it. Pinned by
	 * {@code UncorroboratedFindingProvenanceTest.aClauseAnotherKeyOfThisEntryStatesAsRecordedIsNotHedged}.
	 *
	 * <p><b>And that stage is asked of TWO strings</b>, because the clause a key renders and the
	 * sentence this warning carries are not always one string: {@code contraindicationClauses} JOINS the
	 * distinct notes of the rules a key collapses, while the sentence prints the winning rule's own note
	 * alone. Asked only of the joined clause, the guard cannot see that another key states this
	 * sentence's own words as recorded, and the finding hedges words the record beside it asserts.
	 * Pinned by
	 * {@code UncorroboratedFindingProvenanceTest.theWordsTheFindingPrintsAreNotHedgedWhereAnotherKeyStatesThemAsRecorded}.
	 *
	 * <p>It changes what the injected {@code safety_finding} SAYS and never how strongly it speaks.
	 * {@code DrugReferenceInjector.renderFinding} appends
	 * {@code DrugReferenceInjector.FINDING_UNCORROBORATED_MATCH} for it; the strength clause is still
	 * {@code STRENGTH_WITHHOLD}, {@code getSeverity()} is still null and
	 * {@code DrugSafetyValidator.licensesWithholding} still answers true — one definition of how
	 * strongly a finding licenses a clinical call, and this is not a second one. Do not key a strength
	 * on this flag; <b>ADR Decision 44 is canonical for what that costs</b> and the measurements are
	 * not restated here, because three copies of a rejected-alternative argument is how this repo has
	 * come to contradict itself before.
	 *
	 * <p>Scoped exactly as the chip's own demotion is — a SELF-NAMED allergy rule — so a class-token
	 * rule, a condition rule and every allergen-arm sentence answer false. Not serialized; the wire
	 * shape is unchanged, and the chip's detail is the same string it was.
	 */
	boolean restsOnAnUncorroboratedChartMatch() {
		return uncorroboratedChartMatch;
	}

	/**
	 * The one name the injected {@code drug_reference} record's interaction-note list must call this
	 * chip's partner by, when the note in hand is about {@code rule} — else null, meaning "keep
	 * {@link DrugSafetyValidator#partnerLabel}", which is what that list has always printed.
	 *
	 * <p><b>Issue #297.</b> Issue #292's fold reconciles a folded chip's two sentences, and the chip
	 * reaches the prompt verbatim as a citable {@code safety_finding}
	 * ({@code DrugReferenceInjector.renderFinding}) — while the {@code drug_reference} note kept
	 * {@code partnerLabel}. So the prompt carried one prescription under two names, which is the
	 * property {@code CLAUDE.md} states {@code partnerLabel} exists to hold. The name travels HERE, on
	 * the chip that decided it, rather than being re-derived by the injector, because
	 * {@code DrugReferenceInjector.injectRecords} already runs the whole fold once through
	 * {@code preAnswerFindings}: a second walk is the two-resolutions-that-agree shape issue #151
	 * forbids, and its failure mode is silent and one-directional.
	 *
	 * <p><b>It is the RECORD's own vocabulary</b> — the dataset's {@code getName()} where the dataset has
	 * a name for the partner it has PROVED is the rule's, the rule's own token everywhere else — and never
	 * {@link DrugReference#displayLabel()}. On the rung where the ladder found no name the two surfaces
	 * do end up on one string, which is not a counterexample: they agree there because both take the
	 * rule's token, not because this one took the chip's. The chip's name can be
	 * {@code displayLabel()}, which may not enter this record's prose
	 * ({@code DrugSafetyChipLabelTest.displayLabelNeverLeaksIntoTheRenderedRecordText}), so the two
	 * surfaces name one SUBSTANCE in each one's own vocabulary rather than sharing one string —
	 * {@code Acetylsalicylic acid} in the note beside {@code Acetylsalicylic acid (aspirin)} in the
	 * chip. Which name that is, per reconciliation outcome, is stated on
	 * {@code DrugSafetyValidator.reconciledPartnerName} — which since issue #339 answers for every rule
	 * chip and not only for a folded one, so this field is non-null on chips that carry no class
	 * sentence at all.
	 *
	 * <p><b>The rule is an argument and not a convenience.</b> A note may take this name only where it
	 * is about the very rule the fold was decided on: the record collapses its notes to one per partner
	 * over ONE row ({@code DrugReferenceInjector.onePerPartner}) while the chip chose its rule across
	 * every row of the substance ({@code DrugSafetyValidator.bestRulePerPartner}), so the two can elect
	 * different rules for one partner. Answering null there leaves the note exactly where it was, so
	 * this can only REMOVE a divergence and never create one. Asked here rather than left to the
	 * caller for the reason issue #298 gives of {@code OrderPartner.recordNameSource}: a reader that
	 * had to remember the check is a reader that can forget it.
	 *
	 * <p><b>It does TWO jobs and only the second is a fail-safe.</b> Its first is to scope the name to
	 * the note it was decided for at all: {@code DrugReferenceInjector.reconciledPartnerNoteName} is a
	 * linear scan that asks every finding of this response, so weakened to {@code rule != null} the
	 * FIRST reconciled name in that list answers for every note in the record. The ticket's own
	 * reproducer then renders lisinopril's Moderate interaction as
	 * {@code Acetylsalicylic acid (Moderate)} — one partner's interaction under another's name, in text
	 * the prompt carries as a citable record, with nothing thrown and no count changed.
	 * {@code OneNameAcrossChipAndInjectedRecordTest.aReconciledNameReachesOnlyTheNoteItWasDecidedFor}
	 * pins it, and was the one api case to redden on that weakening when it was made — mutate the
	 * conjunct and read the failures rather than trusting this sentence.
	 *
	 * <p>Its second job is the fail-safe, and THAT is what nothing pins: the record collapses over ONE
	 * row while the chip chose across every row of the substance, so where the two elect different
	 * rules for one partner this answers null and the note stays where it was. No fixture reaches that
	 * shape ({@code ddinter} writes every rule's token and ATC from one partner row, so a label group's
	 * rows do not differ on either field — the same premise
	 * {@code DrugReferenceInjector.onePerPartner} records) and a hand-authored {@code json} dataset
	 * reaches it immediately. Whoever fixtures it should assert this condition, not assume it.
	 *
	 * <p>Not serialized — the note name is the injected record's, and no key
	 * {@code ChartSearchAiRestController.serializeSafetyWarnings} writes carries it; the chip's own
	 * detail is unchanged by this. Unlike {@link #chartOrderBridges()}, which since issue #347 IS
	 * published, for the reason that accessor gives.
	 */
	String reconciledPartnerNoteName(DrugReference.Interaction rule) {
		return rule != null && rule == reconciledRule ? reconciledNoteName : null;
	}

	/**
	 * Which of this patient's own active orders each substance this chip NAMES was resolved from, where
	 * the name that order DISPLAYS does not name it — the bridge
	 * {@code DrugReferenceInjector.renderFinding} states in the injected {@code safety_finding} (issue
	 * #349; the silence test became the display at issue #347, and
	 * {@code DrugSafetyValidator.displaysANameOfAny} records why). Empty, never null, and <b>empty says
	 * "no attribution to show" rather than "the chart records these substances"</b>. <b>Read the
	 * MECHANISM off the code; no rule about which chips are empty is offered here, and that is
	 * deliberate</b> — four were written and each was measured false, the last two against the real
	 * pipeline. {@code DrugSafetyValidator.chartOrderBridges} walks the SUBJECT against every active
	 * order and the PARTNER against the orders its arm allowed to witness it, and each item
	 * additionally needs {@code resolvesFromAny} and a display that does not already name the
	 * substance. That clause is the whole of it: nothing is claimed here about what a chip's
	 * contribution depends on, and in particular not that it is arm-independent — the partner witness
	 * set is the CALLER's, and the two arms hand down different ones.
	 * {@code InteractionFindingChartOrderBridgeTest.theDrugInPlayArmsPartnerIsBridgedToo} is one
	 * arrangement of that — a partner side that bridges beside a subject side that does not — and is
	 * an arrangement rather than a rule. Empty is also the answer for: a chip whose substances their own orders already display; a chip that is not
	 * an interaction; an interaction chip built from a public constructor here rather than through
	 * {@code DrugSafetyValidator.interactionWarning} (the class-only and question-pair chips, whose
	 * residue ADR Decision 64 records); an order the module could read no name for; and a chart with
	 * no active medication. Not offered as exhaustive, and the client contract in README's
	 * {@code safetyWarnings} section says why an exhaustive reading of it is the costly mistake.
	 *
	 * <p><b>Why it travels here.</b> The finding's text is rendered from the chip, and the answer
	 * decides which of {@code DrugSafetyValidator}'s arms resolved each side; the injector holds
	 * neither. Deriving it there would mean a second walk over the same orders reaching the same
	 * answer, which is the two-resolutions-that-agree shape issue #151 forbids — and its failure mode
	 * is silent and one-directional. Resolved by ONE shared method
	 * ({@code DrugSafetyValidator.chartOrderBridges}) called at each arm's chip-wording site — not
	 * inside {@code interactionWarning}, which takes the list as a parameter, so nothing structural
	 * stops an ARM being wrong or empty. Each arm resolves once and needs its own cases:
	 * {@code InteractionFindingChartOrderBridgeTest.aFoldedChipsPartnerIsBridgedToo},
	 * {@code .theDrugInPlayArmsPartnerIsBridgedToo} and
	 * {@code .aCombinationOrderCarryingBOTHSubstancesBridgesBothSides} all redden TOGETHER on the
	 * drug-in-play arm, because its folded and unfolded branches share one resolution; the screening
	 * arm reddens several more. Neuter an arm and read the failures. No count of the sites is given —
	 * an earlier draft published one and a later fix in the same change falsified it by merging two.
	 *
	 * <p><b>It is a RESOLUTION and not an identity</b>, which is what keeps it clear of #339's reverted
	 * rounds 5-6: {@code DrugReferenceInjector.FINDING_CHART_ORDER_LEAD} carries that argument, and the
	 * scoping argument — attribution to the orders the PASS used and not to every carrier of the code —
	 * lives on {@code DrugSafetyValidator.chartOrderBridges}. Not restated here.
	 *
	 * <p><b>Serialized since issue #347, as each chip's own {@code chartOrderBridges} key</b> — named
	 * for this accessor because the wire guard requires it, see
	 * {@code ChartSearchAiRestController.serializeSafetyWarnings} — and that
	 * issue is why: a prompt record reaches a client only if the MODEL cites it, so the correspondence
	 * between the name a chip prints and the prescription it came from has to be stated
	 * deterministically as well — the settlement issue #354 reached for the class note, one step
	 * along. The chip's own {@code detail} is untouched, which is #283's and #339's scoping;
	 * {@code InteractionFindingChartOrderBridgeTest.theChipDetailIsTheWordsItAlwaysWas} pins it.
	 *
	 * <p><b>{@code DrugSafetyValidator.StatedInteractionChips} still does NOT key on it, and the reason
	 * is not that this is unpublished.</b> That key decides which chips are EMITTED and, through
	 * {@code ChartSearchAiUtils.resourceKey}, whether two injected findings share one resource uuid —
	 * so a bridge must not be able to change which chips exist, whether or not a client can read it.
	 * The consequence is that a COLLAPSED chip publishes the survivor's bridge, which is the same
	 * residue ADR Decision 63 already accepts for that collapse ("what it gives up is WHICH
	 * constituent"). See that class's javadoc and ADR Decisions 64 and 68.
	 */
	public List<ChartOrderBridge> chartOrderBridges() {
		return chartOrderBridges;
	}

	/**
	 * One substance this chip names, and one active order of this patient's that the module resolved it
	 * from — the pair {@code DrugReferenceInjector.FINDING_CHART_ORDER_LEAD}'s items are rendered from.
	 *
	 * <p>A value class with {@link #equals} and {@link #hashCode}. <b>{@code equals} has TWO readers,
	 * both exercised.</b> The first is {@code DrugSafetyValidator.addChartOrderBridge}'s
	 * {@code out.contains(bridge)} — an {@code ArrayList}, so that resolves to {@code equals} and never
	 * to {@code hashCode}. Since issue #347 published this list there is a SECOND exercised reader,
	 * {@code ChartSearchAiSafetyWarningSeverityWireTest}'s accessor-versus-key comparison, whose
	 * {@code Objects.equals} falls through to {@code AbstractList.equals} and walks the elements of
	 * that fixture's bridged chip. It was not exercised when this list was first published — every
	 * chip in that fixture bridged nothing, so two empty lists compared equal without touching an
	 * element — and the chip that closed it was added for exactly that reason; ADR Decision 68 is the
	 * record. {@code hashCode} has NO reader (Jackson serializes
	 * through the getters below, not through either) and is here only to hold the contract with
	 * {@code equals}. The first of those two readers is the
	 * de-duplication that makes two orders of one display state their substance once, pinned by
	 * {@code InteractionFindingChartOrderBridgeTest.twoOrdersOfTheSameDisplayAreNamedOnce}. Said
	 * precisely because an earlier draft named the chip COLLAPSE as the reason and that is false (it
	 * does not key on this list): a maintainer checking that reason, finding it false and deleting these
	 * methods would leave the list identity-compared and print one substance twice inside a citable
	 * record. That is also NOT a licence to give {@link SafetyWarning} itself an {@code equals}: it has
	 * none so that nothing DOWNSTREAM can collapse chips this module meant to keep apart
	 * ({@code InteractionRouteVariantTest}).
	 *
	 * <p>Both fields are strings a record PRINTS. {@code substance} is the name the chip already
	 * says — never a second answer to which name to print — and {@code orderDisplay} is the order's own
	 * display, which is the string a chart record of that order carries wherever the order has a drug
	 * row WITH A NON-BLANK NAME — querystore falls back to the concept where it does not, and
	 * {@code DrugSafetyValidator.displaysANameOfAny} records the shapes that diverge there.
	 *
	 * <p><b>{@link #toString()} is the only reader that PRINTS them as one string</b>, and the
	 * PROMPT-side renderer must keep taking that spelling, so that the pair a debug dump prints and
	 * the pair a model reads cannot differ. The two getters beside it are the WIRE's (issue #347) and
	 * exist so that a client is handed two fields rather than a sentence to parse — the same reason
	 * issue #340 publishes {@code severity} instead of leaving a client to substring-match
	 * {@link SafetyWarning#getDetail()}. They are named {@code getSubstance} rather than
	 * {@code getSubstanceName} deliberately: the latter would shadow
	 * {@link DrugReference#getSubstanceName()}, which means the dataset's substance-name FIELD and not
	 * a printed label.
	 */
	public static final class ChartOrderBridge {

		private final String substance;

		private final String orderDisplay;

		/** Both arguments are required: {@link #equals} and {@link #hashCode} dereference them, and a
		 *  bridge with nothing to name is the absence of one. {@code DrugSafetyValidator} refuses that
		 *  case before reaching here rather than by a check in this constructor, so a caller building
		 *  one by hand owes the same.
		 *
		 *  <p>The two fields are NAMED for their getters rather than for what they hold
		 *  ({@code substance}, not {@code substanceName}) so that this class serializes to the same two
		 *  keys under a getter-based mapper and under a field-based one — those key names are issue
		 *  #347's contract, documented in README, and the {@code /search} payload carries this object
		 *  for a mapper the module does not configure. */
		public ChartOrderBridge(String substance, String orderDisplay) {
			this.substance = substance;
			this.orderDisplay = orderDisplay;
		}

		/** @return the substance name the chip prints — the {@code substance} half of the wire pair. */
		public String getSubstance() {
			return substance;
		}

		/** @return the patient's own order it was resolved from, as that order displays — the
		 *          {@code orderDisplay} half of the wire pair. */
		public String getOrderDisplay() {
			return orderDisplay;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof ChartOrderBridge)) {
				return false;
			}
			ChartOrderBridge that = (ChartOrderBridge) other;
			return substance.equals(that.substance) && orderDisplay.equals(that.orderDisplay);
		}

		@Override
		public int hashCode() {
			return 31 * substance.hashCode() + orderDisplay.hashCode();
		}

		@Override
		public String toString() {
			return substance + " from " + orderDisplay;
		}
	}

	@Override
	public String toString() {
		return type + ":" + drug + ":" + detail;
	}
}
