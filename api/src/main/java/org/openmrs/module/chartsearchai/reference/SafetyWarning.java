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
	 *  warning no fold reconciled, which is every warning but a folded interaction chip's. */
	private final DrugReference.Interaction reconciledRule;

	private final String reconciledNoteName;

	/** A warning raised from something the reference data assigns no severity to — see
	 *  {@link #getSeverity()} for which joins those are. */
	public SafetyWarning(String type, String drug, String detail) {
		this(type, drug, detail, null);
	}

	public SafetyWarning(String type, String drug, String detail, String severity) {
		this(type, drug, detail, severity, false);
	}

	/**
	 * Package-private, matching {@link #carriesUnratedRelationship()}: a caller may set only what it
	 * may read back. The three- and four-argument forms above are public because the wire-facing
	 * shape is, and this flag is deliberately not part of it — public here would offer an outside
	 * caller a way to govern the injected record's strength with no way to observe the assertion from
	 * where it was made. The one caller is {@code DrugSafetyValidator.interactionWarning}.
	 *
	 * @param unratedRelationship whether this warning also asserts a relationship the source rates
	 *            nothing for — see {@link #carriesUnratedRelationship()}
	 */
	SafetyWarning(String type, String drug, String detail, String severity,
			boolean unratedRelationship) {
		this(type, drug, detail, severity, unratedRelationship, false, null, null);
	}

	/**
	 * A contraindication chip's warning, and the only shape that can carry
	 * {@link #restsOnAnUncorroboratedChartMatch()} (issue #308). A FACTORY rather than a sixth
	 * constructor argument, for the reason issue #298 states of a label and its source: the two flags
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
				uncorroboratedChartMatch, null, null);
	}

	private SafetyWarning(String type, String drug, String detail, String severity,
			boolean unratedRelationship, boolean uncorroboratedChartMatch,
			DrugReference.Interaction reconciledRule, String reconciledNoteName) {
		this.type = type;
		this.drug = drug;
		this.detail = detail;
		this.severity = severity;
		this.unratedRelationship = unratedRelationship;
		this.uncorroboratedChartMatch = uncorroboratedChartMatch;
		this.reconciledRule = reconciledRule;
		this.reconciledNoteName = reconciledNoteName;
	}

	/**
	 * An INTERACTION chip's warning, and the only shape that can carry
	 * {@link #reconciledPartnerNoteName} (issue #297). A FACTORY rather than a seventh constructor
	 * argument, for the reason {@link #contraindication} states of its own flag: only the drug-in-play
	 * arm folds, so only its chips have a reconciled name at all, and a constructor offering the pair
	 * beside {@code uncorroboratedChartMatch} would offer a caller a combination that has no meaning.
	 *
	 * <p>Package-private, matching the accessor: a caller may set only what it may read back. The one
	 * caller is {@code DrugSafetyValidator.interactionWarning}, which is itself the one place either
	 * interaction arm builds a chip.
	 *
	 * @param reconciledRule the rule this chip's detail was folded on, or null when nothing folded
	 * @param reconciledNoteName see {@link #reconciledPartnerNoteName} — null when the fold refused, so
	 *        that a refusal and an absent fold are one answer here, as they are for the chip
	 */
	static SafetyWarning interaction(String drug, String detail, String severity,
			boolean unratedRelationship, DrugReference.Interaction reconciledRule,
			String reconciledNoteName) {
		return new SafetyWarning(TYPE_INTERACTION, drug, detail, severity, unratedRelationship, false,
				reconciledRule, reconciledNoteName);
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
	 * <p>What distinguishes one warning from another is {@link #getDetail()}: of the three fields a
	 * client receives, it is the one that varies between warnings about a single substance, because it
	 * names the interacting order, the allergen or the ceiling that particular finding is about. Key
	 * per-finding identity on {@code detail}, or on the whole warning.
	 */
	public String getDrug() {
		return drug;
	}

	/** The warning as one complete, standalone sentence naming the drug — e.g. "The stated
	 *  Ibuprofen dose ~2400 mg/day exceeds the 1200 mg/day maximum for ages 2-11" or
	 *  "Warfarin interacts with active order aspirin — Major. …". <b>Renderers should display
	 *  this alone</b>; prefixing {@link #getDrug()} duplicates the subject, because every
	 *  detail already leads with it. It is also this field, not {@link #getDrug()}, that
	 *  tells one warning from another — see {@link #getDrug()} for why. */
	public String getDetail() {
		return detail;
	}

	/**
	 * The severity the reference data assigns the rule this warning was raised from — one of
	 * {@code Major}, {@code Moderate}, {@code Minor}, {@code Unknown} for a DDInter-rated rule, ranked
	 * by {@code DrugSafetyValidator.severityPriority}, which is also what
	 * {@code addQuestionPairInteractions} and the screening arm sort their chips on.
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
	 * all. Not serialized onto the REST response; the wire shape is unchanged.
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
	 * SCREEN (issue #113), which answers a question naming no drug, builds through the two-argument
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
	 * <p><b>It is the RECORD's vocabulary and never the chip's.</b> The chip's name can be
	 * {@link DrugReference#displayLabel()}, which may not enter this record's prose
	 * ({@code DrugSafetyChipLabelTest.displayLabelNeverLeaksIntoTheRenderedRecordText}), so the two
	 * surfaces name one SUBSTANCE in each one's own vocabulary rather than sharing one string —
	 * {@code Acetylsalicylic acid} in the note beside {@code Acetylsalicylic acid (aspirin)} in the
	 * chip. Which name that is, per fold outcome, is stated on
	 * {@code DrugSafetyValidator.foldedPartnerLabel}.
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
	 * <p><b>Nothing in the suite pins that condition, and this says so rather than letting it look
	 * defended.</b> Measured by mutation: dropping it — returning the name for any rule — leaves all
	 * 1514 api tests green, because no fixture puts a partner's winning rule on a SIBLING row of the
	 * substance while the rendered row carries one too. It is a fail-safe for a shape the bundled data
	 * does not reach ({@code ddinter} writes every rule's token and ATC from one partner row, so a
	 * label group's rows do not differ on either field — the same premise
	 * {@code DrugReferenceInjector.onePerPartner} records) and a hand-authored {@code json} dataset
	 * reaches immediately. Whoever fixtures that shape should assert this condition, not assume it.
	 *
	 * <p>Not serialized — the wire shape is the three keys
	 * {@code ChartSearchAiRestController.serializeSafetyWarnings} writes, and the chip's own detail is
	 * unchanged by this.
	 */
	String reconciledPartnerNoteName(DrugReference.Interaction rule) {
		return rule != null && rule == reconciledRule ? reconciledNoteName : null;
	}

	@Override
	public String toString() {
		return type + ":" + drug + ":" + detail;
	}
}
