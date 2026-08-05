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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.impl.QueryScopeRouter;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Part 2 of the drug-reference feature: a deterministic post-LLM check that runs
 * after the answer is generated and <em>annotates</em> it with {@link SafetyWarning}s.
 * It never rewrites or blocks the answer — the clinician decides.
 *
 * <p>For every reference drug in play — those the question asks about, plus those the answer
 * names on its own authority (a drug the answer mentions only by echoing a cited record's own
 * text is a mention, not a proposal, and is excluded — see {@code isEchoOfCitedRecord}, issue
 * #105) — it checks three things against the patient's clinical context and the reference table.
 * One check departs from that framing deliberately: the question-named PAIR arm reads the reference
 * table alone, and covers the drugs the QUESTION resolved to rather than all of those in play, so it
 * answers "does A interact with B?" for a patient on neither — see {@link #addQuestionPairInteractions}.
 * <ul>
 *   <li><b>Overdose</b> — a daily dose parsed from the answer exceeds the
 *       reference {@code maxDailyDoseMg} for the patient's age band; or, when the patient's
 *       weight is known, a per-administration dose exceeds the band's {@code mgPerKgMax} ×
 *       weight (the only possible check for bands publishing mg/kg dosing with no daily
 *       maximum, and the tighter one for small patients). One warning per drug — the
 *       published daily ceiling wins when both arms trip.</li>
 *   <li><b>Interactions</b> — the drug interacts with one of the patient's active orders:
 *       by a hand-authored rule (a rule whose source rates it below
 *       {@code chartsearchai.drugSafety.minInteractionSeverity} is not raised — unrated rules
 *       are never floor-filtered), by sharing an ATC chemical subgroup with an active order
 *       (duplicate-therapy reasoning), or — failing that — by sharing a curated
 *       {@link CrossReactivityGroup} (cross-branch family overlap). One warning per (drug, active
 *       order), whichever of those reasons applies and however many apply at once: several rules can
 *       name one partner — DDInter's route variants of a drug all publish the same match token — and
 *       they collapse to the most severe row ({@link #bestRulePerPartner}), while a partner that is
 *       BOTH an explicit rule partner and class-related yields one chip carrying both relationships
 *       rather than one chip per arm ({@link #addInteractionWarnings}, issue #88). Separately, and
 *       needing no patient data at all, one question-named drug interacts with ANOTHER DRUG THE
 *       SAME QUESTION NAMED — see
 *       {@link #addQuestionPairInteractions}, the arm that answers "does A interact with B?" for a
 *       patient on neither.</li>
 *   <li><b>Contraindications</b> — the drug is contraindicated by an active allergy or
 *       condition: by a hand-authored rule, by being the same drug as — or sharing an ATC
 *       chemical subgroup with — a recorded allergy (cross-reactivity reasoning), or —
 *       failing both — by sharing a curated {@link CrossReactivityGroup} with the allergy
 *       (cross-branch cross-reactivity). These same two checks additionally run over the patient's
 *       OWN ACTIVE ORDERS, whatever the question and the answer name — "is the patient allergic to
 *       something they are taking?" is a fact about their chart, and the drug-in-play framing above
 *       could not ask it (see {@link #addActiveOrderContraindications}, issue #143).</li>
 * </ul>
 *
 * <p>The rule-based checks fire on the entry's own curated {@code interactions}/
 * {@code contraindications}; the <em>class-based</em> checks need only ATC codes, so they
 * are the mechanism by which an authoritative classification source ({@link AtcDrugReferenceSource},
 * which carries no rules) still produces safety reasoning. "Same class" means a shared ATC
 * level-4 chemical subgroup ({@link DrugReference#ATC_SUBGROUP_PREFIX_LENGTH}), e.g. ibuprofen {@code M01AE01}
 * and naproxen {@code M01AE02} both {@code M01AE}. ATC's tree does not capture cross-branch
 * pharmacological cross-reactivity (aspirin {@code N02BA01} vs ibuprofen {@code M01AE01}); that
 * linkage is carried as curated data — {@link CrossReactivityGroup}s loaded alongside either
 * source — and both class checks fall back to it when no ATC subgroup is shared, so the family
 * reasoning stays data-driven end to end. See ADR Decision 24.
 *
 * <p>One contraindication check is neither rule-based nor class-based: a recorded allergy to the very
 * drug in play is IDENTITY, and needs no rule, no ATC code and no curated group. It is therefore not
 * gated on classification data — issue #135, where it was, and 444 of the full DDInter dataset's 2283
 * entries (19.4%, none of them carrying any ATC code) consequently raised no chip for the most basic
 * check here. See {@link #addAllergyContraindications}.
 *
 * <p>Two checks do not need a drug in play at all. The first is the patient's own active orders
 * checked against their own allergy and condition records, on every question — see
 * {@link #addActiveOrderContraindications}, issue #143, which exists because the echo scoping below
 * withheld exactly that finding for a drug appearing in a cited {@code drug_order} record.
 *
 * <p>The second: a question that asks to be <em>screened</em> for
 * interactions ("are there any drug interactions with her current medications?") names no drug, so
 * the patient's active orders are screened against <em>each other</em> — see
 * {@link #addActiveOrderPairInteractions}, issue #113. It reuses the same rule join and the same
 * severity floor as the drug-in-play arm above; the trigger is
 * {@link QueryScopeRouter#isInteractionScreening}, and it stands down as soon as the question names
 * a drug the LOADED DATASET RECOGNISES, because then the arm above has its anchor. (Naming a drug the
 * dataset does not carry leaves the screen running: the gate is dataset recognition, not lexical, and
 * there is deliberately no second drug vocabulary here to tell the two apart — see decision 1 on
 * {@link QueryScopeRouter#isInteractionScreening}.)
 *
 * <p>Conservative by design: overdose is flagged only when a value can be computed AND it
 * exceeds a published maximum — a daily total over {@code maxDailyDoseMg}, or (only with a
 * fresh recorded weight) a per-administration dose over {@code mgPerKgMax} × weight; class-based
 * interactions skip an active order that is the <em>same</em> drug (restating existing therapy
 * is not a duplicate). A question or answer that names no reference drug produces no warnings
 * (the no-false-positive case) unless the patient's own chart supplies the subject — the two
 * deliberate exceptions above, both still no-false-positive checks. The interaction screen reports
 * only pairs the reference data actually relates ("rates" would be too narrow — an unrated rule is
 * exempt from the severity floor, not filtered by it, so unrated pairs are screened too). The
 * active-order contraindication arm reports only a drug the patient is ON whose own allergy or
 * condition records contraindicate it, and stands down entirely when neither is recorded.
 */
@Service("chartSearchAi.drugSafetyValidator")
public class DrugSafetyValidator {

	private static final Logger log = LoggerFactory.getLogger(DrugSafetyValidator.class);

	private static final Pattern DOSE_MG = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*mg\\b");

	private static final Pattern EVERY_N_HOURS = Pattern.compile("(?:every\\s+(\\d+)\\s*(?:hours|hrs|hr|h)\\b|q(\\d+)h\\b|(\\d+)\\s*hourly\\b)");

	// Frequency word-forms, word-boundary anchored so "bd"/"od" do not match inside larger words
	// such as "abdominal" or "blood".
	private static final Pattern FREQ_QID = Pattern.compile("\\b(?:four times|qid|qds)\\b");

	private static final Pattern FREQ_TID = Pattern.compile("\\b(?:three times|thrice|tid|tds)\\b");

	private static final Pattern FREQ_BID = Pattern.compile("\\b(?:twice|two times|bid|bd)\\b");

	private static final Pattern FREQ_OD = Pattern.compile("\\b(?:once daily|once a day|once|od|daily)\\b");

	/** A number immediately preceded (within {@link #LIMIT_CUE_LOOKBACK} chars) by one of these cues
	 *  is a CEILING, not a prescribed dose, so it must not be flagged as an overdose — e.g. the
	 *  reference "maximum 2400 mg/day" the injector feeds the LLM, recited back in the answer. */
	private static final Pattern LIMIT_CUE = Pattern.compile(
			"(?:maximum|max|up to|no more than|not exceed|do not exceed|exceeds?|ceiling|limit|less than|under)\\b\\W*$");

	private static final int LIMIT_CUE_LOOKBACK = 24;

	/** Splits an answer into clauses so dose attribution and frequency never cross a boundary into a
	 *  neighbouring drug's clause. A period is a boundary only when NOT between digits, so a decimal
	 *  dose ("1.5 mg") is never split on its decimal point. */
	private static final Pattern CLAUSE_DELIMITER = Pattern.compile("[;!?\\n]+|\\.(?!\\d)");

	/** How far before/after a dose a drug alias may sit and still own that dose; bounds attribution
	 *  so a dose far from any drug name is ignored. */
	private static final int MAX_ALIAS_TO_DOSE_DISTANCE = 120;

	@Autowired
	private DrugReferenceService drugReferenceService;

	/** Test seam: production wires {@link DrugReferenceService} via {@link Autowired}. */
	void setDrugReferenceService(DrugReferenceService drugReferenceService) {
		this.drugReferenceService = drugReferenceService;
	}

	/**
	 * Production entry point: validates an answer for a patient when the feature and the validator
	 * are both enabled. {@code question} is the clinician's query — the safety check covers the drug
	 * the question asks about even when the answer never names it (see the 3-arg seam). Reads the
	 * patient's clinical context. Returns an empty list when disabled or nothing is flagged — never
	 * null. Fails safe: the validator is an additive net that runs after the answer is produced, so
	 * any unexpected error degrades to "no warnings" rather than failing a query whose answer
	 * already exists.
	 */
	public List<SafetyWarning> validate(String answer, String question, Patient patient) {
		return validate(answer, question, patient, null);
	}

	/**
	 * Production entry point with the chart's record mappings, which enable echo scoping: an
	 * answer-named drug that a record cited by the answer already names in its own text (a
	 * recited reference partner, an allergy reported off the chart) is a mention, not a
	 * proposal, and is not validated (issue #105). Passing {@code null}/empty mappings disables
	 * the scoping and keeps every answer-named drug in play (the conservative pre-scoping
	 * behavior).
	 */
	public List<SafetyWarning> validate(String answer, String question, Patient patient,
			List<RecordMapping> mappings) {
		try {
			if (!ChartSearchAiUtils.isDrugReferenceEnabled()
					|| !ChartSearchAiUtils.getBooleanGlobalProperty(
							ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS,
							ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_VALIDATE_ANSWERS)) {
				return new ArrayList<SafetyWarning>();
			}
			PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
			return validate(answer, question, context, mappings);
		}
		catch (RuntimeException e) {
			log.warn("Drug-safety validation failed; returning no warnings — the answer path is never broken", e);
			return new ArrayList<SafetyWarning>();
		}
	}

	/**
	 * Answer-only overload retained for callers/tests with no question in hand; equivalent to
	 * passing a {@code null} question (no question-driven coverage).
	 */
	List<SafetyWarning> validate(String answer, PatientClinicalContext context) {
		return validate(answer, null, context);
	}

	/**
	 * Pure validation over an explicit clinical context — no OpenMRS context read — so the
	 * parsing/matching logic is unit-testable. Honours the per-check toggles.
	 *
	 * <p>The drugs checked are the union of those the QUESTION resolves to (via the same
	 * {@link DrugReferenceService#findByQuery} the injector uses, so the two never drift) and those
	 * NAMED IN THE ANSWER text. Keying off the question — not only the answer — decouples the safety
	 * net from the LLM's word choice: a contraindication for the asked-about drug fires even when the
	 * answer phrases it by class ("an NSAID allergy") and never writes the drug name. Overdose still
	 * reads the dose from the answer, so a question-only drug with no stated dose yields no overdose.
	 *
	 * <p>Two checks have no drug in play at all, so the union above is not the whole subject set: the
	 * patient's own active orders are checked against their own allergy and condition records on every
	 * question ({@link #addActiveOrderContraindications}, issue #143), and — when the question asks to be
	 * SCREENED for interactions and names no drug — screened against each other
	 * ({@link #addActiveOrderPairInteractions}, issue #113).
	 */
	List<SafetyWarning> validate(String answer, String question, PatientClinicalContext context) {
		return validate(answer, question, context, null);
	}

	/**
	 * Mappings-aware overload — the seam the public entry point delegates to. See
	 * {@link #validate(String, String, Patient, List)} for the echo-scoping contract.
	 */
	List<SafetyWarning> validate(String answer, String question, PatientClinicalContext context,
			List<RecordMapping> mappings) {
		List<SafetyWarning> warnings = new ArrayList<SafetyWarning>();

		boolean warnDose = toggle(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_DOSE_EXCESS,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WARN_ON_DOSE_EXCESS);
		boolean warnInteractions = toggle(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_INTERACTIONS,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WARN_ON_INTERACTIONS);
		boolean warnContra = toggle(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS);

		String lower = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
		List<DrugReference> all = drugReferenceService.getAll();

		// Drugs in play = those the QUESTION resolves to UNION those the ANSWER names — both via the same
		// DrugReferenceService.findByQuery the injector uses, so question/answer/injector matching can
		// never drift, and identity-dedup holds (findByQuery resolves against the shared getAll() cache).
		// Answer-side drugs are echo-scoped (issue #105): a drug the answer names while a record the
		// answer cites already names it in its own text is an echo of that record (a recited reference
		// partner, an allergy reported off the chart), not a proposal — validating it produced chips
		// about drugs nobody suggested giving. Question-named drugs are always validated.
		Set<DrugReference> questionDrugs = new LinkedHashSet<DrugReference>(
				drugReferenceService.findByQuery(question));
		Set<DrugReference> inPlay = new LinkedHashSet<DrugReference>(questionDrugs);
		// The echo corpus is built lazily so the common case — the answer names no drug beyond
		// the question's — does no citation parsing and no mapping sweep at all.
		List<String> citedTextsLower = null;
		for (DrugReference ref : drugReferenceService.findByQuery(answer)) {
			if (questionDrugs.contains(ref)) {
				continue; // already in play; question-named drugs are always validated
			}
			if (citedTextsLower == null) {
				citedTextsLower = citedRecordTextsLower(answer, mappings);
			}
			if (!isEchoOfCitedRecord(ref, citedTextsLower)) {
				inPlay.add(ref);
			}
		}

		// Resolved once per validate, fail-safe to the default with no OpenMRS context. Through the
		// shared accessor, not a second copy of the same expression: DrugReferenceInjector decides
		// which interactions to promote into the prompt off the same floor, and two identical inline
		// reads would let a future change to one of them (a different GP, an added fallback, a
		// per-patient override) silently diverge the chips from the prose — a chip with no matching
		// prose, or prose with no chip, which is the exact defect the shared floor exists to prevent.
		int severityFloor = configuredSeverityFloor();

		for (DrugReference ref : inPlay) {
			if (warnContra) {
				addContraindications(warnings, ref, context);
				addAllergyContraindications(warnings, ref, context);
			}
			if (warnInteractions) {
				// One call, not one per arm: the rule arm and the class arm can both raise a chip about
				// the same active order, so the decision of how many chips that pair gets belongs to a
				// method that sees both (issue #88).
				addInteractionWarnings(warnings, ref, context, severityFloor);
			}
			if (warnDose) {
				addOverdose(warnings, ref, context, lower, all);
			}
		}
		// The patient's own prescriptions against their own allergy and condition records — the one
		// contraindication question no drug-in-play arm can ask (issue #143). After the loop above so a
		// drug in play keeps the chip position it has always had, and before the pair arms below, which
		// are reference lookups rather than facts about this patient.
		if (warnContra) {
			addActiveOrderContraindications(warnings, inPlay, context);
		}
		// LAST, so the patient's own findings lead: a chip about their allergy or their active order
		// is a fact about them, and outranks a reference lookup about a pair they may not be on.
		if (warnInteractions) {
			addQuestionPairInteractions(warnings, questionDrugs, context, severityFloor);
		}
		// Interaction screening (issue #113). A question that asks to be SCREENED names no drug, so
		// neither question-driven arm above has an anchor and the whole feature stayed silent for the
		// one question a DDI knowledge base is chiefly wanted for. Gated on the QUESTION alone —
		// never on the answer — because the identical gate must hold for the pre-answer findings pass
		// (DrugReferenceInjector.preAnswerFindings, which calls this with an empty answer) and for
		// the post-answer chips pass: a gate that could differ between the two would produce prose
		// asserting an interaction with no chip beside it, or the reverse.
		//
		// Composition with the question-pair arm of issue #114, now that both arms are live, both group
		// chips and both cap: the two gates are mutually EXCLUSIVE, so on any one question at most one
		// of them runs at all. That arm needs questionDrugs.size() >= 2; this one needs it empty. No
		// pair can therefore be reported by one and suppressed by the other, the cap never applies to
		// overlapping sets (only one arm is ever reachable per question — which is also why the two
		// share ONE configured limit, #131), and no shared "who owns this pair" decision is needed
		// between them — which is why this arm needs no analogue of that arm's coveredByActiveOrderArm
		// precedence check against the chart. What this arm DOES share with it is the machinery: the
		// same bestRulePerPartner grouping, the same partnerLabel, the same pairKeyNames/unorderedPairKey
		// keys, the same severityPriority ordering and the same maxPairChips() bound, so the two cannot
		// drift apart on what a pair is, which of its rows is worth chipping, or how many are shown.
		if (warnInteractions && questionDrugs.isEmpty()
				&& QueryScopeRouter.isInteractionScreening(question)) {
			addActiveOrderPairInteractions(warnings, context, severityFloor);
		}
		if (!warnings.isEmpty()) {
			log.info("Drug-safety validator raised {} warning(s)", warnings.size());
		}
		return warnings;
	}

	/**
	 * @return the rank of a source-assigned interaction severity in the floor's ordering
	 *         ({@code unknown}=0 &lt; {@code minor}=1 &lt; {@code moderate}=2 &lt; {@code major}=3),
	 *         or {@code -1} for null/unrecognized — which the rule filter treats as exempt
	 *         (unrated is not low-rated).
	 */
	private static int severityRank(String severity) {
		if (severity == null) {
			return -1;
		}
		switch (severity.trim().toLowerCase(Locale.ROOT)) {
			case "unknown":
				return 0;
			case "minor":
				return 1;
			case "moderate":
				return 2;
			case "major":
				return 3;
			default:
				return -1;
		}
	}

	/**
	 * {@link #severityRank} raised so that an <em>unrated</em> rule sorts above {@code major}: every
	 * curated hand-authored rule is unrated and {@link #clearsSeverityFloor} already treats unrated
	 * as exempt rather than low — unrated is not low-rated, so wherever a most-severe-wins choice is
	 * made it must not lose to a rated row. The one definition of that ordering, shared by the chip
	 * grouping here ({@link #bestRulePerPartner}) and the promoted-note ordering in
	 * {@link DrugReferenceInjector.InteractionNote}; two copies could drift into ranking the same
	 * pair of rules oppositely, which is how the chip and the prompt text come to disagree.
	 *
	 * @return the rank, with null/unrecognized mapped to {@link Integer#MAX_VALUE}
	 */
	static int severityPriority(String severity) {
		int rank = severityRank(severity);
		return rank < 0 ? Integer.MAX_VALUE : rank;
	}

	/** @return the floor rank for the GP value, falling back to the default floor when the
	 *          value is unrecognized (a typo'd GP must not silently disable all rated rules). */
	private static int floorRank(String gpValue) {
		int rank = severityRank(gpValue);
		return rank >= 0 ? rank
				: severityRank(ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_MIN_INTERACTION_SEVERITY);
	}

	/**
	 * The configured interaction-severity floor. Extracted so
	 * {@link DrugReferenceInjector#orderedInteractionNotes} applies the same floor when it decides
	 * which interactions are worth promoting into the rendered record — one definition, so the
	 * prompt text and the chips cannot disagree about which rules count.
	 */
	static int configuredSeverityFloor() {
		return floorRank(ChartSearchAiUtils.getStringGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_MIN_INTERACTION_SEVERITY,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_MIN_INTERACTION_SEVERITY));
	}

	/**
	 * How many chips one question may raise from a PAIRWISE arm: the question's own drugs checked
	 * against each other ({@link #addQuestionPairInteractions}, issue #114) or the patient's active
	 * orders checked against each other ({@link #addActiveOrderPairInteractions}, issue #113).
	 *
	 * <p><b>Why a cap at all.</b> Every other safety check is bounded by the patient's own chart, which
	 * held findings to 0–3; these two are quadratic in a list this module does not choose — N²/2 in the
	 * drugs a question resolves, and 45 pairs for 10 active orders. And every chip is ALSO injected into
	 * the prompt as a citable pre-answer finding (see {@code DrugReferenceInjector.preAnswerFindings}),
	 * so an uncapped arm both buries the clinician under chips and writes the whole cross-product into
	 * the context window. Measured on the bundled sample with the patient on nothing: one question
	 * naming its 16 drugs raised 72 chips carrying 42,708 characters of finding text, against a path
	 * that caps a SINGLE reference record at {@link DrugReferenceInjector#MAX_INTERACTION_RENDER_CHARS}
	 * = 1500 for precisely this reason. On the screening side, DDInter's longest mechanism text (~1.2k
	 * chars) puts the default cap's contribution at ~12k characters, comparable to a handful of the
	 * reference records the prompt already carries. Removing the cap is not an option; this is a
	 * backstop, not a routine truncation — a realistic regimen produces a handful of above-floor pairs,
	 * not tens.
	 *
	 * <p><b>Why it is a global property (issue #131).</b> The default is the number both arms were
	 * hardcoded to, and ten covers every pair among five named drugs — well past the "does A interact
	 * with B?" question the pair arm exists for, and roughly 6k characters of finding text in that worst
	 * case. But which pairs a clinician should see is a clinical judgement, not a build-time one.
	 * Measured live on the 3.7.1 standalone against the FULL DDInter knowledge base (2026-08-05), a
	 * 16-drug polypharmacy question logged {@code 10 of 72 … (cap 10)} and withheld
	 * {@code [Major ×13, Moderate ×40, Minor ×9]}: a clinician reviewing sixteen medications is shown
	 * ten Majors while THIRTEEN MORE are withheld. (Issue #131 reports {@code 10 of 65} withholding
	 * {@code [Major ×10, Moderate ×37, Minor ×8]} for a differently-worded 16-drug question; that
	 * question is not in the tree and its profile was not reproduced here, so the figures above are the
	 * ones this cap was sized against.) A polypharmacy review clinic may want 30 where a triage screen
	 * wants 5, and that belongs in a deployment's hands.
	 *
	 * <p><b>One property for both arms</b>, deliberately: their gates are mutually exclusive — the pair
	 * arm needs the question to resolve two or more reference drugs, the screen needs it to resolve none
	 * — so at most one of them runs per question and no question can be subject to both. Two separately
	 * tunable limits for one concept would be arbitrary. Fail-safe like {@code weightMaxAgeDays} and
	 * {@code minInteractionSeverity}: an unparseable or non-positive value falls back to the default
	 * rather than disabling the cap, because a typo'd GP must not turn a bounded safety net into an
	 * unbounded question-controlled prompt expansion.
	 *
	 * <p><b>What the cap drops, and how it is visible.</b> A count rather than a character budget: a
	 * chip is a whole sentence a clinician reads, and half a chip is not a smaller chip. Candidates are
	 * ordered most-severe-first BEFORE the cut, so what goes is the least severe, and every withheld
	 * pair is named in a WARN — a silent truncation would read to a clinician as "everything is
	 * covered". That log line is currently the ONLY place the withheld count surfaces: a clinician-facing
	 * "10 of 72 shown" needs a per-question container the chip API does not have (chips are per-drug
	 * findings), so it is a frontend change rather than a module one. Recorded here as a decision rather
	 * than left as an oversight.
	 *
	 * <p><b>One honest limit on "most severe first":</b> {@link #severityPriority} sorts an UNRATED rule
	 * above Major, matching {@code DrugReferenceInjector.InteractionNote} and for the same reason —
	 * {@link #clearsSeverityFloor} treats unrated as exempt rather than low, so unrated is not rankable
	 * below a rated tier. Where that convention only decides who keeps mechanism prose it drops nothing;
	 * composed with a cap that DISCARDS, it means a dataset mixing unrated and rated rules can withhold
	 * a Major pair while reporting an unrated one. That needs an operator-authored dataset carrying both
	 * kinds (every DDInter row is rated; every curated rule is unrated, so neither bundled source mixes
	 * them) AND more above-floor pairs on one chart than the configured cap. The WARN line is what makes
	 * it recoverable, and picking the other order would instead discard an operator's own hand-authored
	 * rule in favour of a third-party rating — which is why the convention is shared with the injector
	 * rather than reversed here.
	 *
	 * @return the configured cap, or {@link ChartSearchAiConstants#DEFAULT_DRUG_SAFETY_MAX_PAIR_CHIPS}
	 *         when the GP is absent, unparseable or non-positive
	 */
	static int maxPairChips() {
		int configured = ChartSearchAiUtils.getIntGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_MAX_PAIR_CHIPS,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_MAX_PAIR_CHIPS);
		return configured > 0 ? configured : ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_MAX_PAIR_CHIPS;
	}

	/**
	 * Whether a rule's source-assigned severity clears {@code floor}. A rule with no severity at
	 * all is exempt — every curated hand-authored rule is unrated, and unrated is not low-rated.
	 */
	static boolean clearsSeverityFloor(DrugReference.Interaction interaction, int floor) {
		int rank = severityRank(interaction.getSeverity());
		return rank < 0 || rank >= floor;
	}

	/**
	 * The lowercased texts of the records the answer cites inline — the attribution corpus for
	 * echo scoping. Built once per validate() call (one citation decode, one mapping sweep, at
	 * most one lowercase copy per cited record) no matter how many answer-named drugs are
	 * checked. Null/empty mappings (the mappings-less overloads) and uncited answers yield an
	 * empty corpus, so no drug is ever exempted — the conservative direction.
	 */
	private static List<String> citedRecordTextsLower(String answer, List<RecordMapping> mappings) {
		List<String> texts = new ArrayList<String>();
		if (mappings == null || mappings.isEmpty()) {
			return texts;
		}
		Set<Integer> citedIndexes = ChartSearchAiUtils.citedIndexes(answer);
		if (citedIndexes.isEmpty()) {
			return texts;
		}
		for (RecordMapping mapping : mappings) {
			if (citedIndexes.contains(Integer.valueOf(mapping.getIndex())) && mapping.getText() != null) {
				texts.add(mapping.getText().toLowerCase(Locale.ROOT));
			}
		}
		return texts;
	}

	/**
	 * @return true when a record the answer cites inline names {@code ref} in its own text —
	 *         i.e. the answer's mention of the drug is attributable to cited record content (a
	 *         recited drug-reference partner, an allergy reported off the chart) rather than a
	 *         proposal on the answer's own authority (issue #105). Attribution is deliberately
	 *         answer-global, not sentence-scoped: recited record text carries its own sentence
	 *         punctuation ("… (Moderate. NSAIDs may …) [14]"), so sentence splitting routinely
	 *         separates a recited drug name from the {@code [N]} marker that vouches for it.
	 *         An empty corpus (no mappings, an uncited answer, or no cited record carrying text)
	 *         returns false, keeping the drug validated. The accepted trade-off: an answer that
	 *         BOTH cites a record naming drug X AND independently proposes X is exempted. The
	 *         measured alternative was worse (7 of 8 chips about unproposed drugs on one
	 *         enumeration answer), and what bounds the residue is that a proposal-worthy X is
	 *         either question-named (always validated) or actively ordered — and an actively-ordered
	 *         X is checked against the patient's allergy and condition records by
	 *         {@link #addActiveOrderContraindications} whatever the answer's wording, so the
	 *         exemption can no longer withhold a contraindication for a drug the patient is on.
	 *
	 *         <p>That second half USED to be asserted here of "the order-driven arms", and was false
	 *         (issue #143). Counted over this class, those arms — {@link #addInteractionWarnings},
	 *         {@link #addQuestionPairInteractions}, {@link #addActiveOrderPairInteractions} — read the
	 *         allergy list ZERO times, because what they check is INTERACTIONS; the contraindication
	 *         arms read allergies but only ever about the drug in play. Nothing joined the two, so a
	 *         prescribed drug the patient was allergic to lost its contraindication to this exemption:
	 *         measured on the bundled curated dataset, an active ibuprofen order plus an ibuprofen
	 *         allergy, a question naming no drug and an answer citing the {@code drug_order} record
	 *         gave 0 chips where the same call with null mappings gave 2. What this exemption still
	 *         withholds is an INTERACTION or OVERDOSE finding about an echoed drug, which is exactly
	 *         what #105 measured and fixed.
	 */
	private static boolean isEchoOfCitedRecord(DrugReference ref, List<String> citedTextsLower) {
		for (String text : citedTextsLower) {
			if (ref.matchesText(text)) {
				return true;
			}
		}
		return false;
	}

	private void addContraindications(List<SafetyWarning> warnings, DrugReference ref,
			PatientClinicalContext context) {
		if (context == null) {
			return;
		}
		for (DrugReference.Contraindication c : ref.getContraindications()) {
			boolean hit = false;
			String against = null;
			if ("allergy".equalsIgnoreCase(c.getType()) && context.hasAllergyToken(c.getToken())) {
				hit = true;
				against = "active allergy";
			} else if ("condition".equalsIgnoreCase(c.getType()) && context.hasConditionToken(c.getToken())) {
				hit = true;
				against = "active condition";
			}
			if (hit) {
				warnings.add(new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, ref.displayLabel(),
						ref.displayLabel() + " is contraindicated by an " + against + ": "
								+ ChartSearchAiUtils.firstNonBlank(c.getNote(), c.getToken())));
			}
		}
	}

	/**
	 * Every interaction chip {@code ref} raises about the patient's own medications: <b>one chip per
	 * (this drug, active order) pair</b>, with the two arms that can each raise one — the rule arm
	 * ({@link #bestRulePerPartner}) and the class arm ({@link #classRelationships}) — coordinated
	 * instead of run independently (issue #88).
	 *
	 * <p><b>The defect.</b> A co-medication that is BOTH an explicit interaction partner AND
	 * class-related raised TWO {@code TYPE_INTERACTION} chips for one clinical fact, because neither
	 * arm could see the other. Measured live on the 3.7.1 standalone against {@code main} at
	 * {@code 89d14ab}, twice: a patient on one Ondansetron order (the concept is one of the few in that
	 * dictionary carrying a {@code WHOATC} map, {@code A04AA01}) asked "Is it safe to give dolasetron?"
	 * ({@code A04AA04}) got both
	 * <pre>
	 * Dolasetron interacts with active order ondansetron — Major. Dolasetron can cause dose-related
	 *     prolongation of the QT interval via its pharmacologically active metabolite, hydrodolasetron. …
	 * Dolasetron is in the same ATC class (A04AA) as active order Ondansetron — possible duplicate therapy
	 * </pre>
	 * Since issue #110 every chip is also injected as a citable safety-finding record
	 * ({@code DrugReferenceInjector.preAnswerFindings}), so the duplicate reached the prompt as well.
	 *
	 * <p><b>Fold, rather than "the class arm yields to the rule arm".</b> Preferring the rule chip and
	 * dropping the class chip is the wrong dedup, because a rule can reach a chip carrying nothing
	 * while the class chip ("same ATC class … possible duplicate therapy") is the informative one.
	 * Issue #88 argued that from DDInter's 42,415 Unknown-severity rows, and re-measuring the loaded
	 * 19 MB KB (295,184 rows) shows that argument no longer describes any RATED row that can chip:
	 * all 42,415 rows whose mechanism carries no text are rated Unknown, which #108's severity floor
	 * filters out before this arm sees them (3 above-floor rows do carry an empty mechanism —
	 * vilanterol, mometasone and bitolterol against regular human insulin, all Moderate — and none of
	 * the three is a class-related pair). Put the other way round: of the 2,195 above-floor rows whose
	 * pair ALSO trips the class arm — 2,181 by a shared ATC-4 subgroup, 14 by the curated NSAID group —
	 * not one carries a contentless note, so on this dataset the case #88 argued from never arises where
	 * a fold could act on it. The shape that DOES survive is the UNRATED rule:
	 * {@link #clearsSeverityFloor} deliberately exempts a rule with no severity rather than treating
	 * it as low, so every hand-authored curated rule reaches a chip whatever it carries, and one
	 * authored with no note produces a chip reading only "X interacts with active order Y". No bundled
	 * dataset holds such a rule (all five curated seed rules carry notes) but any deployment editing
	 * {@code drug-reference.json} can author one, and the fixture behind
	 * {@code DuplicateInteractionChipTest} pins exactly that row. Folding is correct for both
	 * populations without depending on which is the larger, which is why it is preferred over a
	 * measurement that #108 has already moved once.
	 *
	 * <p>The fold leads with the RULE sentence: an explicit rule about this pair is the more specific
	 * finding, its mechanism note is the actionable half, and it names the partner by the label
	 * {@link #bestRulePerPartner} groups on — where the class arm can only name it by whatever
	 * {@link #entryForAtcCode} resolves the order's code to, which is the bare code when the dataset
	 * does not cover that substance. The class relationship follows as its own sentence, worded exactly
	 * as its standalone chip words it — see {@link #classRelationships}, where the two shortenings that
	 * seem obvious are recorded along with the issue #108 assertions each of them broke.
	 *
	 * <p><b>How the arms are correlated.</b> By SUBSTANCE identity, since that is what the two arms
	 * disagree about: the class arm's partner is an active-order ATC code, the rule arm's is a match
	 * token. Two exact tests, no heuristics — see {@link #ruleAbout}. Correlating on the order's ATC
	 * code alone (the key issue #88 proposed) is not enough, because a substance is filed under
	 * several ATC codes while a rule carries only ONE of them: {@code ddinter} writes the partner's
	 * FIRST code, so every rule naming aspirin in the loaded KB carries {@code A01AD05} while an
	 * aspirin order in this dictionary maps to {@code N02BA01}, and on that key the pair never
	 * correlates at all.
	 *
	 * <p><b>The residue this cannot correlate</b>, stated rather than papered over: when the loaded
	 * dataset carries no entry for the active order's ATC code, only the rule's own code is left to
	 * compare, so a rule that reached its chip by NAME under a different code of the same substance
	 * stays uncorrelated and both chips are still emitted. That is the bundled curated seed's
	 * ibuprofen-versus-aspirin shape — the seed carries no aspirin entry at all, which is equally why
	 * its class chip can only name the order "N02BA01". It cannot be narrowed from here AS WRITTEN,
	 * because this arm reads {@link PatientClinicalContext#getActiveDrugAtcCodes()} — the context-wide
	 * union, where "the order this rule matched by name" and "the order that contributed this code" are
	 * the same input. Since issue #132 the per-order codes DO exist
	 * ({@link PatientClinicalContext.ActiveDrugOrder#getAtcCodes()}, which is how
	 * {@link #activeOrdersOtherThan} now attributes them), so the narrowing available is to correlate
	 * against those rather than against the union — not a cleverer test over the union.
	 */
	private void addInteractionWarnings(List<SafetyWarning> warnings, DrugReference ref,
			PatientClinicalContext context, int severityFloor) {
		if (context == null) {
			return;
		}
		List<DrugReference.Interaction> rules = new ArrayList<DrugReference.Interaction>(
				bestRulePerPartner(ref, context, severityFloor));
		// Which rule row carries which class sentence, decided before anything is emitted: the class
		// arm is walked per active-order CODE while the chips are one per rule ROW, and a substance
		// filed under several codes reaches this loop once per code.
		Map<DrugReference.Interaction, String> folded = new LinkedHashMap<DrugReference.Interaction, String>();
		List<String> classOnly = new ArrayList<String>();
		for (Map.Entry<String, String> hit : classRelationships(ref, context).entrySet()) {
			DrugReference.Interaction rule = ruleAbout(hit.getKey(), rules);
			if (rule == null) {
				classOnly.add(hit.getValue());
			} else if (!folded.containsKey(rule)) {
				folded.put(rule, hit.getValue());
			}
			// else: a second ATC code of the substance that rule already covers. One partner, so the
			// relationship is already stated on that chip; emitting it again — standalone or appended —
			// would put one pair's duplicate-therapy reasoning in front of a clinician twice, which is
			// the defect being fixed rather than a second finding.
		}
		// Rule chips first, then the class-only chips, which is the order the two arms produced them in
		// before they were coordinated — a folded chip therefore keeps the rule chip's position and no
		// client sees the chip sequence reshuffle.
		for (DrugReference.Interaction rule : rules) {
			warnings.add(interactionWarning(ref, rule, folded.get(rule)));
		}
		for (String detail : classOnly) {
			warnings.add(new SafetyWarning(SafetyWarning.TYPE_INTERACTION, ref.displayLabel(), detail));
		}
	}

	/**
	 * @return the rule among {@code rules} that is about the very substance the active-order ATC code
	 *         {@code orderCode} identifies — so the class arm's finding about that order folds into
	 *         its chip (issue #88) — or null when no rule is.
	 *
	 *         <p>Two tests, both exact identity rather than proximity, because a false correlation
	 *         SUPPRESSES a chip: (1) the rule's own normalized ATC code IS the order's code; (2) the
	 *         dataset carries an entry for the order's code and the rule {@link #identifies} that
	 *         entry. (2) is the general one — it reaches a rule that matched by name, and a rule whose
	 *         code is a different code of the same substance — and it reuses the name-identity test
	 *         the question-pair and screening arms already ask, so all three arms agree about when a
	 *         rule names a given entry. (1) survives for the case (2) cannot resolve: an order whose
	 *         substance the loaded dataset does not carry, where the rule's code is the only evidence
	 *         left that the two arms are talking about one drug.
	 *
	 *         <p>{@code rules} holds one row per partner LABEL, not per substance, so two labels can
	 *         both name one order — across the full KB exactly one such pair exists, {@code enalapril}
	 *         and {@code enalaprilat}, which {@link #bestRulePerPartner} deliberately keeps as two
	 *         chips. The first in dataset order takes the fold; the other keeps its rule chip
	 *         unfolded, which is the conservative direction, since the alternative is stating one
	 *         duplicate-therapy relationship twice.
	 */
	private DrugReference.Interaction ruleAbout(String orderCode, List<DrugReference.Interaction> rules) {
		DrugReference orderEntry = entryForAtcCode(orderCode);
		for (DrugReference.Interaction rule : rules) {
			if (orderCode.equals(DrugReference.normalizeAtcToken(rule.getAtc()))) {
				return rule;
			}
			if (orderEntry != null && identifies(rule, orderEntry)) {
				return rule;
			}
		}
		return null;
	}

	/**
	 * The partner label a chip names for {@code interaction} — its match token, else its ATC code.
	 *
	 * <p>One definition, because the chip detail renders it and {@link #bestRulePerPartner} groups on
	 * it: that grouping is only correct while the key IS the label the chip says, and two copies of
	 * the same coalesce could drift into grouping rules by something a clinician never sees.
	 *
	 * <p>Trimmed to fold the way the MATCH folds. {@link PatientClinicalContext#hasActiveDrug} trims
	 * the rule's token and matches it case-insensitively against the order name, so two rows whose
	 * tokens differ only in case or in surrounding whitespace are one partner to the only predicate
	 * that decides an interaction concerns this patient — and must be one partner here too, or issue
	 * #115's duplicate chip returns for a hand-authored dataset, silently and with two labels a
	 * clinician cannot tell apart. That shape reaches this method: {@code ddinter} lower-cases every
	 * token it derives and takes it from the trimmed RxNorm generic whenever the partner row has one,
	 * and {@code atc} carries no rules at all, but the curated {@code json} source is plain Jackson
	 * over an operator-editable file and sanitizes neither case nor padding — nor does the ATC arm,
	 * which is why the fold is applied to the coalesced value rather than to the token alone.
	 *
	 * <p>Not yet the only label site: {@link DrugReferenceInjector#orderedInteractionNotes} still
	 * coalesces its own, untrimmed (it trims the assembled {@code label (note)} piece, not the label),
	 * so a padded curated token reaches the citable record as {@code "warfarin   (Major. …)"} beside a
	 * chip reading {@code "active order warfarin"}. Same partner, different spelling; giving the
	 * injector this method is the follow-up.
	 *
	 * @return the label, or null when the rule carries neither — which a rule that matched an active
	 *         order cannot ({@code hasActiveDrug} needs a non-blank token or a non-blank ATC), so
	 *         callers inside the matched loop never see it
	 */
	private static String partnerLabel(DrugReference.Interaction interaction) {
		String label = ChartSearchAiUtils.firstNonBlank(interaction.getToken(), interaction.getAtc());
		return label == null ? null : label.trim();
	}

	/**
	 * The matched interaction rules of {@code ref} worth chipping, at most one per partner, in
	 * dataset order of first appearance.
	 *
	 * <p><b>Why grouping is needed (issue #115).</b> DDInter carries one entry per <em>route
	 * variant</em> and every variant of a drug publishes the same {@code rxnorm_name}, so the
	 * variants' rows all reach this arm carrying the same match token: one active order matches
	 * every one of them, and ungrouped that is N chips for a single pair, at N different
	 * severities. Measured on the 3.7.1 standalone: a patient on one Dexamethasone 4mg order,
	 * asked about voxelotor, raised three chips — Major + Moderate + Moderate — of which the two
	 * Moderate details were byte-identical strings, because the nasal and ophthalmic variants
	 * share a mechanism group. Across the full KB 142 {@code rxnorm_name} values are shared by
	 * more than one entry (332 entries), and 1004 question-drugs would raise 3 or more chips for
	 * some single active drug. {@link SafetyWarning} carries no {@code equals}, so nothing
	 * downstream can collapse them — and because the chips are also injected as citable
	 * safety-finding records ({@code DrugReferenceInjector.preAnswerFindings}), each duplicate
	 * became a near-identical record in the prompt as well.
	 *
	 * <p><b>What is grouped.</b> The key is the label the chip actually renders
	 * ({@link #partnerLabel}, case-folded), so rules that would produce the same
	 * "interacts with active order X" subject collapse while rules naming different partners each
	 * keep their chip — even when their notes are identical strings. Grouping is per {@code ref}
	 * and per arm: two different drugs in play still chip separately about the same order, and this
	 * decides only WHICH RULE ROW survives for a partner, never how many arms describe that partner.
	 * The rule-plus-class double chip of issue #88 is the separate, cross-arm question, answered
	 * downstream of this method by {@link #addInteractionWarnings} — which folds the class arm's
	 * finding into the row chosen here, so the two collapses compose in one direction instead of
	 * competing.
	 *
	 * <p><b>Which row wins.</b> The most severe rating, then the longer note — longer in prose, not in
	 * whitespace, see {@link #noteLength}. Route variants genuinely differ — topical dexamethasone does
	 * not have systemic dexamethasone's interaction profile, which is why DDInter rates voxelotor Major
	 * against systemic dexamethasone, Moderate against two others and carries no row at all against the
	 * topical variant — but nothing on a {@code DrugOrder} tells this layer which variant the order is
	 * (the context carries names and ATC codes; all four variants publish an identical ATC list),
	 * so the variant cannot be resolved here. Reporting the strongest rating over-warns rather than
	 * under-warns on a non-blocking advisory the clinician adjudicates, which is the fail-safe
	 * direction; the accepted cost is that a patient on a topical form may see the systemic
	 * severity. Making the severity route-aware needs a dose-form/route vocabulary that does not
	 * exist yet — the data-side half of #115. The note length is the only informativeness signal a
	 * row carries: DDInter's "no mechanism description on file" fallback is shorter than any real
	 * mechanism paragraph, and where two equally-rated rows both carry prose the longer one has been
	 * a strict superset in the shapes measured — in the two dolutegravir x iron rows (171 and 236
	 * characters of note) the surplus is the sentence "The mechanism of interaction has not been
	 * established.", so the fuller row says everything the shorter one does and states its own limit
	 * — so a tie on severity keeps the fuller note. Equal on both keeps the incumbent, so a group's
	 * chip is the dataset's first such row.
	 *
	 * <p><b>Two corners this rule accepts.</b> A row with no note at all still wins its group on
	 * severity alone, so an operator's token-only unrated rule beats a rated row carrying a mechanism
	 * paragraph and the chip then gives no reason — reachable only in hand-authored data, since every
	 * DDInter row has a note, and left as it is because the alternative (a note outranking a rating)
	 * would drop the operator's own rule, which is the thing {@link #severityPriority} exists to
	 * protect. And grouping is by label, so two tokens that both match one order but are not the same
	 * string stay two chips: across the full KB exactly one such pair exists — {@code enalapril} and
	 * {@code enalaprilat}, which 376 entries carry as separate partners, and which a single order
	 * named "Enalaprilat 1.25 mg" matches through the order-name matcher's inflection tolerance.
	 * Prodrug and active metabolite are genuinely different DDInter entries, so that pair is reported
	 * rather than merged.
	 */
	private static Collection<DrugReference.Interaction> bestRulePerPartner(DrugReference ref,
			PatientClinicalContext context, int severityFloor) {
		Map<String, DrugReference.Interaction> best = new LinkedHashMap<String, DrugReference.Interaction>();
		for (DrugReference.Interaction i : ref.getInteractions()) {
			// The severity floor (issue #84): a rule the SOURCE rated below the floor is not
			// raised — DDInter's Unknown-severity rows carry no mechanism text and would bury
			// the chips that matter (measured: an uncharacterized aspirin x simvastatin row
			// sharing equal billing with a severe-allergy contraindication). A rule with no
			// severity (every curated hand-authored rule) is exempt: unrated is not low-rated.
			// Filtered BEFORE grouping, so a sub-floor row can never become a group's winner and
			// the floor keeps deciding exactly which rules exist for this arm.
			if (!clearsSeverityFloor(i, severityFloor)) {
				continue;
			}
			if (!context.hasActiveDrug(i.getToken(), i.getAtc())) {
				continue;
			}
			String key = partnerLabel(i).toLowerCase(Locale.ROOT);
			DrugReference.Interaction incumbent = best.get(key);
			if (incumbent == null || outranks(i, incumbent)) {
				// LinkedHashMap keeps a re-put key in its original position, so replacing a group's
				// winner does not reorder the chips.
				best.put(key, i);
			}
		}
		return best.values();
	}

	/**
	 * @return true when {@code candidate} is the row worth chipping for a partner {@code incumbent}
	 *         already covers — a more severe rating, or an equal rating with a longer note. See
	 *         {@link #bestRulePerPartner} for the rationale.
	 */
	private static boolean outranks(DrugReference.Interaction candidate, DrugReference.Interaction incumbent) {
		int candidateSeverity = severityPriority(candidate.getSeverity());
		int incumbentSeverity = severityPriority(incumbent.getSeverity());
		if (candidateSeverity != incumbentSeverity) {
			return candidateSeverity > incumbentSeverity;
		}
		return noteLength(candidate) > noteLength(incumbent);
	}

	/**
	 * @return how much mechanism prose {@code interaction}'s note carries — its <em>trimmed</em>
	 *         length. Whitespace is not the informativeness signal the tie-break is reading: measured
	 *         raw, a short referral note with a block of blank lines pasted onto it outranks a longer
	 *         real mechanism paragraph, and the surviving chip then says nothing about the mechanism
	 *         while the row explaining it is discarded. Trimmed for the comparison only — the note
	 *         still renders as authored, exactly as it does for a row with no competitor;
	 *         {@link DrugReferenceInjector#orderedInteractionNotes} makes the same "trim before you
	 *         compare lengths" call for the same reason.
	 */
	private static int noteLength(DrugReference.Interaction interaction) {
		return interaction.getNote() == null ? 0 : interaction.getNote().trim().length();
	}

	/**
	 * The question-named PAIR arm (issue #114): the drugs the question resolved to, checked against
	 * EACH OTHER rather than only against the chart.
	 *
	 * <p>Every other interaction arm joins one drug to the patient's own data, so a question naming
	 * two drugs was silently reduced to two independent one-drug questions: asked "does warfarin
	 * interact with aspirin?" about a patient on neither, the module reported no information about a
	 * pair its own KB rates Major (measured on the 3.7.1 standalone, 2026-08-04 — it also raised an
	 * unrequested Minor chip about the one drug that WAS on the chart, which reads as an answer to
	 * the question asked). The pair is a plain reference lookup: it needs no patient data at all, and
	 * withholding it because the patient happens not to be on either drug answers a question nobody
	 * asked.
	 *
	 * <p>Deliberately scoped to the QUESTION's drugs, not to all of {@code inPlay}. The
	 * answer-named additions are the LLM's word choice, and two drugs it names are as often
	 * alternatives ("ibuprofen, or paracetamol if …") as a proposed combination, so pairing them
	 * would assert a co-administration risk nobody proposed — the same over-reach echo scoping
	 * exists to prevent (issue #105). It also keeps the chips aligned with the injected findings:
	 * {@link DrugReferenceInjector#preAnswerFindings} runs this same validate with an EMPTY answer,
	 * so an answer-side pair could produce a chip with no record behind it.
	 *
	 * <p>The floor is the shared {@code severityFloor} the chart arm is given, so this cannot become a
	 * route around a decision the chip path enforces; and a pair the chart arm already reports is left
	 * to {@link #addInteractionWarnings}, whose chip states a fact about THIS patient — the stronger
	 * statement, and reporting both would say one finding in two voices. Stated precisely, because the
	 * looser version ("either drug is an active order") is not what the code can ask and not what it
	 * does: the test is whether any RULE joining the pair names an active order,
	 * {@link #coveredByActiveOrderArm} putting the same question to {@code hasActiveDrug} that
	 * {@code addInteractionWarnings} answers when it decides to chip. The two differ, and the difference
	 * is deliberate — a patient can be ON one of the pair while no rule joining it names their order
	 * (the one-directional case below), and that pair is this arm's to report, because the chart arm
	 * raises nothing for it.
	 *
	 * <p>{@link DrugReferenceInjector#orderedInteractionNotes} is deliberately NOT extended to match,
	 * and its "a partner that raises a chip is exactly a partner promoted here" should be read as scoped
	 * to the arm it describes: across the whole chip set it no longer holds, since a pair chip's partner
	 * is promoted nowhere. That sentence lives in a file this change does not touch — rewording it is
	 * left to whichever PR owns that method next, so two PRs do not collide on it. What is NOT affected
	 * is the invariant the sentence exists to protect, that a chip and the prose cannot describe the
	 * same finding differently: since issue #110 the deterministic finding is itself injected as a
	 * numbered, citable record by {@code preAnswerFindings}, carrying this chip's string verbatim, so a
	 * pair finding's grounding comes from that record rather than from the promoted notes, and the
	 * promoted-note budget is untouched.
	 */
	private void addQuestionPairInteractions(List<SafetyWarning> warnings, Set<DrugReference> questionDrugs,
			PatientClinicalContext context, int severityFloor) {
		if (questionDrugs.size() < 2) {
			return;
		}
		List<DrugReference> drugs = new ArrayList<DrugReference>(questionDrugs);
		Map<DrugReference, String> names = pairKeyNames(drugs, severityFloor);
		// Group first, decide second. Both the grouping and the chart-precedence verdict belong to the
		// CLINICAL pair, and route variants make one clinical pair arrive as several entry pairs
		// carrying different rule sets — the sub-floor sibling of an above-floor row loses that row, so
		// its entry pair sees different tokens and can reach a different verdict. Deciding per entry
		// pair, as an early return, therefore let a chart-owned pair leave no trace: its sibling
		// concluded the chart did not own it and chipped, so the patient's own medication became the
		// subject of a sentence reading as a reference lookup, carrying one variant's mechanism
		// attributed to another whose own row was sub-floor. Candidates are collected per pair key and
		// emitted only once every entry pair has been seen.
		Map<List<String>, PairFinding> candidates = new LinkedHashMap<List<String>, PairFinding>();
		Set<List<String>> chartOwned = new LinkedHashSet<List<String>>();
		// Indexed so each UNORDERED pair of entries is visited once: DDInter rows are symmetric and
		// the parser writes every pair into both drugs' entries, so walking ordered pairs would report
		// each interaction twice, once from each side.
		for (int i = 0; i < drugs.size() - 1; i++) {
			for (int j = i + 1; j < drugs.size(); j++) {
				collectQuestionPairInteraction(candidates, chartOwned, drugs.get(i), drugs.get(j), names,
						context, severityFloor);
			}
		}
		List<PairFinding> found = new ArrayList<PairFinding>();
		for (Map.Entry<List<String>, PairFinding> candidate : candidates.entrySet()) {
			if (!chartOwned.contains(candidate.getKey())) {
				found.add(candidate.getValue());
			}
		}
		// Most severe first, and bounded — see maxPairChips(). Collections.sort is stable, so
		// equally-rated pairs keep the dataset order the entry loop produced them in.
		if (found.isEmpty()) {
			// Nothing to order or bound, and no GP read for the common "these two do not interact" case —
			// the same shape the screening arm's own early return takes.
			return;
		}
		Collections.sort(found, PAIR_SEVERITY_DESCENDING);
		int cap = maxPairChips();
		int shown = Math.min(found.size(), cap);
		if (shown < found.size()) {
			// WARN, not INFO: a clinician reading the chips cannot tell a bounded list from a complete
			// one, so an operator has to be able to see that this question outran the bound and which
			// ratings went unshown. Silent truncation in a safety net reads as "nothing else was found".
			List<String> withheld = new ArrayList<String>();
			for (PairFinding finding : found.subList(shown, found.size())) {
				withheld.add(finding.severity);
			}
			// The CONFIGURED cap, not the default: an operator diagnosing a truncated list has to see
			// the number that actually did the truncating.
			log.warn("Question-pair safety: {} of {} question-named drug pairs shown (cap {}); the "
					+ "question resolved {} reference drugs, and pairs grow as N^2/2. Withheld, least "
					+ "severe last: {}", shown, found.size(), cap, drugs.size(), withheld);
		}
		for (PairFinding finding : found.subList(0, shown)) {
			warnings.add(finding.warning);
		}
	}

	/** Orders candidate pair chips most-severe first; see {@link #severityPriority}. */
	private static final Comparator<PairFinding> PAIR_SEVERITY_DESCENDING = new Comparator<PairFinding>() {

		@Override
		public int compare(PairFinding a, PairFinding b) {
			return Integer.compare(severityPriority(b.severity), severityPriority(a.severity));
		}
	};

	/** One candidate pair chip, with the source-assigned rating that orders it. */
	private static final class PairFinding {

		final SafetyWarning warning;

		final String severity;

		PairFinding(SafetyWarning warning, String severity) {
			this.warning = warning;
			this.severity = severity;
		}
	}

	/** One question-named pair: at most one candidate chip, from whichever side carries the rule. */
	private void collectQuestionPairInteraction(Map<List<String>, PairFinding> candidates,
			Set<List<String>> chartOwned, DrugReference first, DrugReference second,
			Map<DrugReference, String> names, PatientClinicalContext context, int severityFloor) {
		List<DrugReference.Interaction> forward = aboveFloorRulesAgainst(first, second, severityFloor);
		List<DrugReference.Interaction> reverse = aboveFloorRulesAgainst(second, first, severityFloor);
		if (forward.isEmpty() && reverse.isEmpty()) {
			return;
		}
		// One chip per clinical PAIR, not per pair of ENTRIES, and nothing at all when the two entries
		// are one drug — see pairKeyNames.
		String firstName = names.get(first);
		String secondName = names.get(second);
		if (firstName.equals(secondName)) {
			return;
		}
		List<String> pairKey = unorderedPairKey(firstName, secondName);
		if (coveredByActiveOrderArm(forward, context) || coveredByActiveOrderArm(reverse, context)) {
			// Recorded, not returned: the sibling entry pairs of this clinical pair must see it.
			chartOwned.add(pairKey);
			return;
		}
		if (candidates.containsKey(pairKey)) {
			return;
		}
		// Whichever side carries the rule owns the sentence; with symmetric data both do, and the tie
		// goes to whichever entry the DATASET lists first — not whichever the question names first,
		// because questionDrugs comes from findByQuery, which walks getAll() and so returns dataset
		// order. Measured on the 3.7.1 standalone: "does voxelotor interact with dexamethasone?" and
		// "can dexamethasone be given with voxelotor?" both chip with Voxelotor as the subject, its
		// entry sitting at index 1055 against dexamethasone's 1744. Stable either way, which is what
		// the chip needs; a rule that followed the question's word order would need the drug's offset
		// in the question, which findByQuery does not report.
		boolean fromFirst = !forward.isEmpty();
		DrugReference subject = fromFirst ? first : second;
		DrugReference partner = fromFirst ? second : first;
		DrugReference.Interaction rule = fromFirst ? forward.get(0) : reverse.get(0);
		// "named in the question" is what keeps this distinguishable from the active-order chip, and
		// it is a claim about the QUESTION, so it stays true whatever the chart holds. Wording it as
		// "neither drug is on the chart" instead would be false for the one-sided-data case that
		// reaches here — the patient IS on one of the pair, but only that drug's entry carries the
		// rule, so no active-order chip fires from either side — and stating the patient's
		// medications wrongly is the defect in #86.
		String detail = subject.displayLabel() + " interacts with "
				+ ChartSearchAiUtils.firstNonBlank(partner.displayLabel(), rule.getToken(), rule.getAtc())
				+ ", also named in the question";
		if (rule.getNote() != null && !rule.getNote().isEmpty()) {
			detail += " — " + rule.getNote();
		}
		candidates.put(pairKey, new PairFinding(new SafetyWarning(SafetyWarning.TYPE_INTERACTION,
				subject.displayLabel(), detail), rule.getSeverity()));
	}

	/**
	 * @return every interaction rule of {@code subject} that names {@code other} AND clears
	 *         {@code floor}, in dataset order — empty when it carries none. All of them, not just the
	 *         first, because the two questions asked of them differ: the CHIP carries one row (a pair
	 *         of reference entries is one clinical fact however many rows join them, so the chart
	 *         arm's one-chip-per-rule behaviour is deliberately not extended here), while the
	 *         chart-precedence check has to see them all — see {@link #coveredByActiveOrderArm}.
	 */
	private static List<DrugReference.Interaction> aboveFloorRulesAgainst(DrugReference subject,
			DrugReference other, int floor) {
		List<DrugReference.Interaction> out = new ArrayList<DrugReference.Interaction>();
		for (DrugReference.Interaction rule : subject.getInteractions()) {
			if (clearsSeverityFloor(rule, floor) && identifies(rule, other)) {
				out.add(rule);
			}
		}
		return out;
	}

	/**
	 * The reference-side counterpart of {@link PatientClinicalContext#hasActiveDrug}: the same two
	 * arms, a rule's name token and its ATC code, resolved against a loaded entry's aliases and
	 * normalized codes instead of against the chart's active orders.
	 *
	 * <p>Both operands here are canonical reference strings — a rule's match token against an entry's
	 * own alias list — so the question is NAME IDENTITY, and neither of the two matchers issue #128
	 * splits {@link DrugReference#matchesText} into answers it. Both scan for a name inside a longer
	 * string, which is right when the longer string is prose ({@code findByQuery}) or a localized order
	 * name ({@code matchesOrderName}) and wrong here, because a token is neither. Measured on the full
	 * KB, scanning made a multi-word token name every drug called after one of its words: 408 of 2093
	 * distinct tokens are multi-word and 53 of those carry a word that is some other entry's whole name
	 * — {@code ethinyl estradiol} (in 449 entries' rule lists) named the separate Estradiol entry,
	 * {@code magnesium salicylate} named Salicylic acid, {@code iron-dextran complex} named Iron. A
	 * word boundary does not help: it stops a name nested inside a WORD ({@code chlorothiazide} in
	 * {@code hydrochlorothiazide}) but not one nested inside a PHRASE. The chip then stated an
	 * interaction the KB does not carry, against a drug whose row it had read from a different drug,
	 * and {@link DrugReferenceInjector#preAnswerFindings} injected that same sentence as a citable
	 * record — a fabricated interaction handed to the model as evidence.
	 *
	 * <p>So this arm compares names rather than scanning: exactly one of {@code other}'s aliases,
	 * case-folded. No third matcher is introduced — this reads the same {@link DrugReference#getAliases}
	 * list {@code matchesText} reads, so #128's change to how that list is scanned cannot drift from it.
	 * Nothing genuine is lost: {@code ddinter} writes each rule's token from its partner row's
	 * {@code rxnorm_name} (falling back to its name), and the parser puts both in that partner's
	 * aliases, so every real rule still names its partner exactly — and an entry whose aliases omit its
	 * own name could never be resolved from a question in the first place, {@code findByQuery} reading
	 * the same list.
	 *
	 * @return true when {@code rule} names reference entry {@code other}
	 */
	private static boolean identifies(DrugReference.Interaction rule, DrugReference other) {
		if (namesEntry(rule.getToken(), other)) {
			return true;
		}
		String atc = DrugReference.normalizeAtcToken(rule.getAtc());
		return atc != null && other.normalizedAtcCodes().contains(atc);
	}

	/** @return true when {@code token} is, case-folded, one of {@code other}'s own aliases. */
	private static boolean namesEntry(String token, DrugReference other) {
		if (ChartSearchAiUtils.isBlank(token)) {
			return false;
		}
		String name = token.trim().toLowerCase(Locale.ROOT);
		for (String alias : other.getAliases()) {
			if (alias != null && name.equals(alias.trim().toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return true when any of {@code rules} points at one of the patient's active orders, i.e.
	 *         {@link #addInteractionWarnings} already raises this pair as a fact about the patient. ANY,
	 *         over every rule joining the pair rather than only the one this arm would chip, because
	 *         {@code addInteractionWarnings} walks them all: two rules can join one pair under different
	 *         tokens — a brand-name row and an INN row — and a pair is the chart arm's as soon as one
	 *         of them names an active order, so consulting the first alone would report that pair
	 *         twice, once as a fact about the patient and once as a reference lookup. Asked of both
	 *         directions by the caller, so it covers "the partner is on the chart" and "the subject
	 *         is on the chart" alike, and it asks through the very predicate that arm uses, so the two
	 *         cannot disagree about which pairs the chart already owns.
	 */
	private static boolean coveredByActiveOrderArm(List<DrugReference.Interaction> rules,
			PatientClinicalContext context) {
		if (context == null) {
			return false;
		}
		for (DrugReference.Interaction rule : rules) {
			if (context.hasActiveDrug(rule.getToken(), rule.getAtc())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * One chip per clinical PAIR, not per pair of ENTRIES. DDInter carries a separate entry per route
	 * variant and every variant publishes the same {@code rxnorm_name} — which is the token its rules
	 * match on — so ONE word in the question resolves several entries ({@code findByQuery} returns
	 * every entry whose aliases match) that all pair off the same rule: four dexamethasone entries
	 * against voxelotor is four near-identical chips, and four near-identical injected findings, for a
	 * single clinical fact. That is issue #115's shape arriving on the question side (142
	 * {@code rxnorm_name} values are shared by more than one entry across 332 entries of the full KB),
	 * so pairs are keyed by the two drugs' MATCH TOKENS rather than by their entries — the token being
	 * the reference data's own canonical name for a drug, and precisely what the variants share
	 * (measured: in all 142 of those families the token is an exact alias of every member).
	 *
	 * <p>The name is resolved PER DRUG, once, rather than per pair, because a drug that keys two ways
	 * gets two keys and its pair escapes the grouping. It did: the name came from the other side's rule
	 * token where there was one and from {@link DrugReference#displayLabel} where there was not — two
	 * vocabularies, so one entry keyed as {@code aspirin} in the pair whose partner row names it and as
	 * {@code Acetylsalicylic acid (aspirin)} in the pair whose partner row is sub-floor, leaving nothing
	 * to name it with. Resolving from ANY of the question's drugs' rules removes the second vocabulary
	 * from the common case entirely, and route variants agree by construction, being named by the one
	 * token that identifies them all.
	 *
	 * <p>De-duplicating inside a safety net can only be done where nothing actionable is lost, and two
	 * entries share a name only when the reference data itself gives them one. The surviving chip then
	 * carries that same rule's severity and mechanism, and all that is dropped is a second spelling of
	 * the partner; entries the data can tell apart keep their own chips, asserted over three drugs named
	 * in one question. Which variant's row supplies the severity is #115's open half; the dataset's
	 * first is kept, as the chart arm keeps its first.
	 *
	 * <p>Two entries that end up with the SAME name are one drug, not a pair, and raise nothing. That is
	 * reachable from a question naming a single drug: the two-drugs guard counts ENTRIES, and one word
	 * resolves several when the KB carries variants of it — 33 above-floor rows in the full KB join two
	 * entries sharing one token (29 drug words: minoxidil, timolol, lidocaine, atropine, neomycin,
	 * paclitaxel …). Reporting one would assert a combination the clinician never proposed (issue #105's
	 * over-reach), and where the variants are named after different substances it would name two drugs
	 * the question never mentioned, which is #86. The measured cost of that suppression is bounded: only
	 * 6 of the full KB's 2093 distinct tokens are an exact alias of entries with different
	 * {@code rxnorm_name}s, and all 6 are one substance family (the trastuzumab conjugates, isosorbide
	 * and its mononitrate, the COVID-19 vaccines), so a pair genuinely worth reporting is not among them.
	 *
	 * <p>The name comes from {@link #partnerLabel}, the same coalesce the chart arm's own grouping keys
	 * on, so the two arms that now group chips in this class agree by construction about what naming a
	 * partner means. They cannot fight over a pair: both consult {@code hasActiveDrug}, and they take
	 * opposite branches of it. Whenever any rule joining a pair names an active order, this arm records
	 * the pair as the chart's and raises nothing, while {@link #bestRulePerPartner} raises exactly one
	 * chip for that partner label — grouping only merges chips sharing a label, so a chip for the
	 * deferred pair always survives it, at that partner's most severe row. Whenever no joining rule
	 * names an active order, the chart arm raises nothing for the pair and this arm reports it. So one
	 * clinical pair yields one chip from one arm, never two chips and never none.
	 *
	 * @return each drug mapped to the reference data's own name for it: the match token (or, for a rule
	 *         carrying only a code, the ATC code) of the first above-floor rule any OTHER drug in
	 *         {@code drugs} uses to name it. Falls back to its generic name, else its display name, when
	 *         no rule names it at all — reachable only for the unnamed side of a one-directional pair,
	 *         since {@code ddinter} writes every row into both entries.
	 */
	private static Map<DrugReference, String> pairKeyNames(List<DrugReference> drugs, int floor) {
		Map<DrugReference, String> names = new LinkedHashMap<DrugReference, String>();
		for (DrugReference drug : drugs) {
			String name = null;
			for (DrugReference other : drugs) {
				if (other == drug) {
					continue;
				}
				List<DrugReference.Interaction> naming = aboveFloorRulesAgainst(other, drug, floor);
				if (!naming.isEmpty()) {
					name = partnerLabel(naming.get(0));
					break;
				}
			}
			if (name == null) {
				name = ChartSearchAiUtils.firstNonBlank(drug.getGenericName(), drug.displayLabel());
			}
			names.put(drug, name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
		}
		return names;
	}

	/**
	 * @return a key equal for {@code (a, b)} and {@code (b, a)}: the two names, sorted. A two-element
	 *         list rather than a joined string because every separator a name could be joined on is a
	 *         character some drug name already contains — "Multivitamines et fer" a space,
	 *         "Dexamethasone / lidocaine" a slash — and a separator that can appear inside a name
	 *         lets two different pairs collapse to one key, silently dropping the second pair's chip.
	 */
	private static List<String> unorderedPairKey(String one, String other) {
		boolean inOrder = one.compareTo(other) <= 0;
		List<String> key = new ArrayList<String>(2);
		key.add(inOrder ? one : other);
		key.add(inOrder ? other : one);
		return key;
	}

	/**
	 * The one chip an active-order interaction produces, so the question-driven arm and the
	 * screening arm below cannot word the same finding differently.
	 */
	private static SafetyWarning interactionWarning(DrugReference ref, DrugReference.Interaction i) {
		return interactionWarning(ref, i, null);
	}

	/**
	 * As {@link #interactionWarning(DrugReference, DrugReference.Interaction)}, additionally folding
	 * the class arm's finding about the SAME active order into this one chip (issue #88).
	 *
	 * @param alsoSameClass the class arm's own sentence about that order ({@link #classRelationships}),
	 *        or null when the class arm says nothing about this partner — in which case the detail is
	 *        byte-identical to what it has always been, so no single-arm chip changes
	 */
	private static SafetyWarning interactionWarning(DrugReference ref, DrugReference.Interaction i,
			String alsoSameClass) {
		// partnerLabel, not a second coalesce: it is the label bestRulePerPartner GROUPS on,
		// and #121's grouping is only correct while the key IS the label the chip says.
		String detail = ref.displayLabel() + " interacts with active order " + partnerLabel(i);
		if (i.getNote() != null && !i.getNote().isEmpty()) {
			detail += " — " + i.getNote();
		}
		if (alsoSameClass != null) {
			detail = endSentence(detail) + " " + alsoSameClass;
		}
		return new SafetyWarning(SafetyWarning.TYPE_INTERACTION, ref.displayLabel(), detail);
	}

	/**
	 * @return {@code detail} closed off as a sentence, so a folded second sentence does not run into
	 *         it. Needed because what a rule chip ends on is authored data: every DDInter note ends in
	 *         a full stop, a curated note need not, and a rule carrying no note at all ends on the
	 *         partner label. Trailing whitespace goes with it — a note padded in the source file would
	 *         otherwise put the gap inside the sentence rather than between the two.
	 */
	private static String endSentence(String detail) {
		String trimmed = detail.trim();
		if (trimmed.isEmpty()) {
			return trimmed;
		}
		char last = trimmed.charAt(trimmed.length() - 1);
		return last == '.' || last == '!' || last == '?' ? trimmed : trimmed + ".";
	}

	/**
	 * Interaction screening across the patient's OWN active orders — the answer to "are there any
	 * drug interactions with her current medications?" (issue #113).
	 *
	 * <p>Nothing here is a new safety rule. It is the same {@link PatientClinicalContext#hasActiveDrug}
	 * join {@link #addInteractionWarnings} performs and the same {@link #clearsSeverityFloor} filter, run
	 * over the reference entries for the patient's active orders instead of over the drugs the
	 * question named. Because the partner side of the join is by definition another active order, one
	 * pass over the order entries reaches every pair; no cross-product is enumerated.
	 *
	 * <p>Three things this arm must get right that the question-driven arm never faced:
	 * <ul>
	 *   <li><b>A pair needs TWO orders.</b> The join must be witnessed by an active order other than
	 *       the one {@code ref} itself answers for — see {@link #activeOrdersOtherThan}. Without that,
	 *       one order pairs with itself: {@code hasActiveDrug} matches a rule's partner token as a
	 *       SUBSTRING of an order name (issue #86, whose live example is the token {@code iron}
	 *       inside {@code spironolactone}), and a patient on one drug would be told it interacts with
	 *       a drug they are not on. For a question naming no drug there is no clinician-supplied
	 *       anchor to sanity-check such a chip against, so screening must establish the second order
	 *       itself. This is a property of pairing, not a copy of the matching rule: the predicate is
	 *       still {@code hasActiveDrug}, evaluated against the OTHER orders, so it follows whatever
	 *       #86 settles on. The join has two legs and the guard has to cover both — the ATC leg needs
	 *       the co-formulation case as well, where ONE order resolves to several entries and the
	 *       order's own code would otherwise witness a pair between them.</li>
	 *   <li><b>Each pair once.</b> Interaction rows are symmetric — {@link DdiDrugReferenceSource}
	 *       expands each row onto BOTH drugs' entries, which is what from-either-side matching
	 *       relies on — so pairwise the same pair is reached from each side and would chip twice,
	 *       in opposite directions, saying the same thing. Collapsed on an unordered pair key. This
	 *       doubling is created here, so it is fixed here; it is neither the cross-arm rule-vs-class
	 *       duplication of issue #88 (correlated per {@code ref} by
	 *       {@link #addInteractionWarnings}, so it cannot see two different {@code ref}s) nor the
	 *       route-variant duplication of #115 (collapsed per partner label by
	 *       {@link #bestRulePerPartner}). Both are fixed now, in their own arms, and neither fix
	 *       reaches this doubling — two entries sharing one {@code rxnorm_name} are two ids and so two
	 *       keys, and one pair reached from both sides is one {@code ref} each time. A second, weaker
	 *       key backs the pair key up: nothing is reported that words a chip already raised
	 *       identically, whichever arm raised it — see {@link #chipIdentity}.</li>
	 *   <li><b>Blast radius.</b> Candidates grow quadratically with the medication list, so they are
	 *       ordered most-severe-first and cut at {@link #maxPairChips()}, with every withheld pair
	 *       logged.</li>
	 * </ul>
	 */
	private void addActiveOrderPairInteractions(List<SafetyWarning> warnings,
			PatientClinicalContext context, int severityFloor) {
		if (context == null) {
			return;
		}
		List<DrugReference> orderDrugs = activeOrderEntries(context);
		List<ScreenedPair> pairs = new ArrayList<ScreenedPair>();
		Set<List<String>> seenPairs = new LinkedHashSet<List<String>>();
		// Seeded with every chip the arms above already raised, so the screen can add nothing that
		// merely repeats one of them. Every chip it could repeat comes from a drug-in-play interaction
		// arm: the contraindication arms — including the active-order one added by #143, which is why
		// this says "the arms above" rather than naming them — raise TYPE_CONTRAINDICATION, and
		// chipIdentity leads with the type, so one of those can never match a candidate here.
		// The screen's gate reads the QUESTION alone (see the call
		// site — the pre-answer findings pass and the post-answer chips pass must gate identically),
		// so a drug the ANSWER named can be in play beside it, and then addInteractionWarnings has already
		// run this very rule over these very orders: measured, an answer naming a subject the screen
		// also reaches put the identical "X interacts with active order Y — Major" line in
		// safetyWarnings TWICE. Suppression is on the chip's own identity, not on the gate, and the
		// pair is marked seen either way, so the reverse direction cannot re-report it. It also makes
		// two of this arm's own candidates that would word one statement identically collapse.
		// "Identically" has to allow for issue #88's fold, which appends a sentence to the very chip
		// this arm would raise for such a pair — see alreadySaid.
		Set<String> seenChips = new LinkedHashSet<String>();
		for (SafetyWarning existing : warnings) {
			seenChips.add(chipIdentity(existing));
		}
		// Keyed by the reference data's own name for each drug, not by entry, and resolved once per
		// drug — issue #115's shape reaches the subjects here exactly as it reaches the question drugs
		// in the pair arm, because one order name resolves every route variant sharing an
		// {@code rxnorm_name} and each variant would otherwise be its own subject keying its own pair.
		Map<DrugReference, String> keyNames = pairKeyNames(orderDrugs, severityFloor);
		for (DrugReference ref : orderDrugs) {
			// Resolved once per subject, not per rule: the reduction depends only on ref.
			PatientClinicalContext others = activeOrdersOtherThan(ref, orderDrugs, context);
			// bestRulePerPartner applies the severity floor and the hasActiveDrug join and returns at
			// most ONE rule per partner label, most severe first (#121) — the same grouping and the
			// same predicate the drug-in-play arm gets, asked of the OTHER orders instead of all of
			// them. Reusing it is what stops this arm keeping a route variant's Moderate row for a
			// pair whose Major row the other arm reports, and what keeps the cap below sorting on the
			// severity a clinician would actually be shown.
			for (DrugReference.Interaction i : bestRulePerPartner(ref, others, severityFloor)) {
				// The partner is an active order too, so it is looked up among the order entries
				// rather than across the whole dataset. Null when that order carries no reference
				// entry of its own (a substance the dataset does not cover, matched by name): the
				// finding still stands — the join above is what decides that — and the pair simply
				// keys on the rule's own label, which no reverse direction can produce.
				DrugReference partner = activeOrderEntryFor(orderDrugs, ref, i);
				String partnerName = partner != null ? keyNames.get(partner)
						: partnerLabel(i).toLowerCase(Locale.ROOT);
				if (!seenPairs.add(unorderedPairKey(keyNames.get(ref), partnerName))) {
					continue;
				}
				SafetyWarning chip = interactionWarning(ref, i);
				if (alreadySaid(chip, seenChips)) {
					continue;
				}
				seenChips.add(chipIdentity(chip));
				pairs.add(new ScreenedPair(chip, severityPriority(i.getSeverity()),
						ref.displayLabel() + " x "
								+ (partner != null ? partner.displayLabel() : partnerLabel(i)) + " ("
								+ ChartSearchAiUtils.firstNonBlank(i.getSeverity(), "unrated") + ")"));
			}
		}
		if (pairs.isEmpty()) {
			return;
		}
		Collections.sort(pairs, SCREENED_PAIR_SEVERITY_DESCENDING);
		// The same cap the question-pair arm applies, from the same GP — the two gates are mutually
		// exclusive, so one question can never meet both, and two limits for one concept would be
		// arbitrary (issue #131).
		int reported = Math.min(pairs.size(), maxPairChips());
		if (pairs.size() > reported) {
			// Named, not counted: a clinician reading the reported chips has no way to tell a capped
			// screen from a complete one, so the withheld pairs must at least be recoverable from the
			// log rather than vanishing.
			List<String> withheld = new ArrayList<String>();
			for (int n = reported; n < pairs.size(); n++) {
				withheld.add(pairs.get(n).label);
			}
			log.warn("Interaction screening across {} active-order reference entries found {} pair(s) "
					+ "above the severity floor; reporting the {} most severe and WITHHOLDING {}: {}",
					orderDrugs.size(), pairs.size(), reported, withheld.size(),
					String.join("; ", withheld));
		}
		for (int n = 0; n < reported; n++) {
			warnings.add(pairs.get(n).warning);
		}
	}

	/**
	 * The reference entries for the patient's active orders — the subjects
	 * {@link #addActiveOrderPairInteractions} screens against each other, and the subjects
	 * {@link #addActiveOrderContraindications} checks against the patient's own allergy and condition
	 * records. The union of the documented order-driven matcher
	 * ({@link DrugReferenceService#findByActiveOrders}, which keys on ATC codes) and an alias
	 * resolution of each active order's own name ({@link DrugReferenceService#findByQuery}, the same
	 * whole-word alias matcher the question path uses). One definition, so the two arms cannot come to
	 * disagree about which of the patient's prescriptions the reference data covers.
	 *
	 * <p>Both keys are needed because {@link PatientClinicalContext#hasActiveDrug} — the join that
	 * decides whether a rule concerns this patient — matches on name OR ATC, so a subject set
	 * resolved on only one of them cannot be the subject of every chip that join can raise. Neither
	 * key can be assumed present: measured on the 3.7.1 standalone's demo dictionary (2026-08-04),
	 * ATC coverage is sparse but real — 85 of 616 Drug-class concepts carry a map from an ATC-named
	 * source ({@code Torasemide} → {@code C03CA04}, {@code Heparin sodium} → {@code B01AB01}) and 158
	 * carry a {@code concept_reference_map} of any kind, so {@link PatientClinicalContextBuilder}
	 * yields ATC codes for some orders and none for others. Every order on every probe patient there
	 * (Simvastatin, Spironolactone, Tiotropium, Nitroglycerin, Budesonide, Dexamethasone) fell in the
	 * unmapped majority, so an ATC-only subject set was empty for every case measured — which makes
	 * this union a robustness property rather than a workaround for one dictionary: on a
	 * fully-ATC-mapped dictionary the order-driven matcher carries the subject set, and where mapping
	 * is absent the name resolution does. The ATC path is dormant on that instance, not dead.
	 *
	 * <p>Identity de-duplication is sound because both matchers resolve against the service's shared
	 * {@code getAll()} cache (the same reason the drugs-in-play set can dedup by identity).
	 */
	private List<DrugReference> activeOrderEntries(PatientClinicalContext context) {
		Set<DrugReference> entries = new LinkedHashSet<DrugReference>(
				drugReferenceService.findByActiveOrders(context));
		for (String name : context.getActiveDrugNames()) {
			entries.addAll(drugReferenceService.findByQuery(name));
		}
		return new ArrayList<DrugReference>(entries);
	}

	/**
	 * @return the patient's active-order state with everything {@code ref} itself answers for removed
	 *         — the ORDERS {@code ref} resolves from, whole (their names AND their ATC codes), plus
	 *         {@code ref}'s own codes — so {@link PatientClinicalContext#hasActiveDrug} against it can
	 *         only be satisfied by a DIFFERENT order. A derived context rather than a second matching
	 *         rule: the predicate stays the one the question-driven arm uses.
	 *
	 *         <p>Both legs of that join are attributable per order (issue #132): an order carries its
	 *         own ATC codes on {@link PatientClinicalContext.ActiveDrugOrder} exactly as it carries its
	 *         own names, so dropping the subject's order drops what it contributed to BOTH legs. Before
	 *         that, codes lived only in the context-wide set and a code could not be traced to an order:
	 *         a fixed-dose combination is one order that resolves to SEVERAL entries (the order name
	 *         whole-word-matches an alias of each constituent) while its single mapped code belongs to
	 *         just one of them, so dropping only {@code ref}'s codes left the co-formulated other half's
	 *         code standing as if it were a second order. Measured on the bundled sample: one "Aspirin
	 *         and omeprazole" order reported "Acetylsalicylic acid (aspirin) interacts with active order
	 *         esomeprazole — Minor", i.e. the two halves of one tablet as an interacting pair, naming a
	 *         drug the patient is on in no form.
	 *
	 *         <p>A code the subject's own order carries is removed but then RE-ADDED when another order
	 *         carries it too, because removal from a flattened set cannot tell contributors apart: a
	 *         patient on both a combination and a separate order of one of its constituents keeps that
	 *         (genuine, duplicate-therapy) pair, which the pre-#132 reduction had to give up whenever
	 *         only the shared code witnessed it. Codes that no per-order set claims are left alone —
	 *         they come from an order the per-order list omits (the builder's nameless-order KNOWN GAP),
	 *         which is a real second order and a legitimate witness.
	 *
	 *         <p>Restoration reaches every order EXCEPT one that is itself the subject's own, and that
	 *         bounds what this can find: when every order carrying one member of a pair also carries the
	 *         other's code, both members' orders are own-classified from both sides and the pair goes
	 *         unreported — two brand-named, ATC-mapped multi-substance orders covering the same two
	 *         substances (measured shape: two differently-named orders both mapped to
	 *         {@code C10AA01} + {@code J01FA09}). Same for a code shared with a list-omitted nameless
	 *         order, which claims nothing and so cannot restore it. Both are the safe direction — a pair
	 *         missed, never one invented — and neither can be told from the one-order case without more
	 *         order structure than a code set carries, which is why the one-order suppression wins.
	 *
	 *         <p>The flattened fallback below cannot do any of this, and that is where the residual now
	 *         lives: with orders reduced to one name set and one code set, "one order carrying two
	 *         codes" and "two orders each carrying one" are the same input, so no reduction strong
	 *         enough to catch the first spares the genuine two-order pair the ATC leg exists to find (a
	 *         mapped {@code Torasemida} against a mapped {@code Digoxina}, neither name in the dataset).
	 *         Production always builds the per-order form ({@link PatientClinicalContextBuilder}), so
	 *         that is the fallback's own limit rather than the arm's.
	 */
	private static PatientClinicalContext activeOrdersOtherThan(DrugReference ref,
			List<DrugReference> orderDrugs, PatientClinicalContext context) {
		Set<String> names = new LinkedHashSet<String>();
		Set<String> codes = new LinkedHashSet<String>(context.getActiveDrugAtcCodes());
		// Read on both branches, so it is resolved once here rather than at each use (it allocates).
		Set<String> refCodes = ref.normalizedAtcCodes();
		if (!context.getActiveDrugOrders().isEmpty()) {
			// Per ORDER, not per name — issue #118 / #124 put the orders themselves on the context, and
			// #132 put their ATC codes there too. An order is ref's own when ref resolves from it by
			// EITHER matcher that could have made ref a subject (a name alias, or a shared code), and
			// then everything that order contributes goes: its other names (a brand the aliases do not
			// cover is still that order) and its codes (a co-formulation's second constituent, or the
			// second of two codes on one concept). What remains is every OTHER order, whole — so a
			// subject can still pair by name even when nothing names the subject itself, which is what
			// the flattened fallback below has to give up.
			Set<String> ownCodes = new LinkedHashSet<String>();
			Set<String> otherCodes = new LinkedHashSet<String>();
			for (PatientClinicalContext.ActiveDrugOrder order : context.getActiveDrugOrders()) {
				if (!resolvesFrom(ref, order)) {
					names.addAll(order.getNames());
					otherCodes.addAll(order.getAtcCodes());
					continue;
				}
				ownCodes.addAll(order.getAtcCodes());
				// AND the pre-#132 proxy, not instead of it: the codes of every other order entry THIS
				// order resolves to (by the same widened predicate, so a co-resolved entry is found by
				// name or by code). Attribution is per ORDER, but an order's mapped codes need
				// not cover every substance in it — a fixed-dose combination whose concept maps to one
				// constituent's code is the measured #124 shape, and its other half's code is then in
				// the context-wide set with nothing attributing it. Reached in two ways: an order
				// carrying NO codes at all (its concept has no ATC map — 531 of 616 Drug-class concepts
				// in the demo dictionary — or a caller used the pre-#132 three-argument form), and an
				// order carrying SOME. Measured both: with only the exact removal, one "Aspirin and
				// omeprazole" order mapped to N02BA01 reported "interacts with active order
				// esomeprazole — Minor" off a single tablet, which the pre-#132 code suppressed. What the
				// proxy removes is restored below whenever another order IN THE PER-ORDER LIST carries it;
				// a code shared only with a list-omitted nameless order is not, which is the missed-pair
				// direction the method javadoc bounds.
				for (DrugReference coResolved : orderDrugs) {
					if (coResolved != ref && resolvesFrom(coResolved, order)) {
						ownCodes.addAll(coResolved.normalizedAtcCodes());
					}
				}
			}
			codes.removeAll(ownCodes);
			// Restores a code that ANOTHER order also carries: the two orders contribute one string to
			// the flattened set, so removing the subject's would silently take the other order's witness
			// with it. After the removal, so the same code being both own and other resolves as "another
			// order has it" — and intersected with the context's own union first, so this can only ever
			// put back a code the chart actually carries. The builder makes that intersection a no-op
			// (every order's codes are folded into the union in the same pass), but a caller assembling a
			// context by hand can pass an order carrying codes the union does not, and adding those would
			// let ONE order witness a pair through a code the patient's chart never held — this arm may
			// only ever narrow what hasActiveDrug can see.
			otherCodes.retainAll(context.getActiveDrugAtcCodes());
			codes.addAll(otherCodes);
			// ref's own codes, last of all: a rule pointing at ref's own code is answered by ref itself
			// however the code reached the chart, and restating existing therapy is not an interaction.
			codes.removeAll(refCodes);
			return new PatientClinicalContext(null, null, names, codes, null, null);
		}
		codes.removeAll(refCodes);
		// No per-order structure: a caller built this context from the flat sets (the null-patient
		// early return in the builder, and every context assembled without orders). Names then cannot
		// be attributed at all, so the guard falls back to matching them against ref itself and, when
		// NOTHING names ref, to trusting none of them — see below. Kept rather than removed because
		// the alternative is a silent one: with no orders on the context the loop above adds no names
		// and the arm would simply stop finding name-witnessed pairs, with no error anywhere.
		boolean ownOrderNamed = false;
		for (String name : context.getActiveDrugNames()) {
			// Already lowercased by PatientClinicalContext, which is what matchesText expects.
			if (!ref.matchesText(name)) {
				names.add(name);
				continue;
			}
			ownOrderNamed = true;
			for (DrugReference coResolved : orderDrugs) {
				if (coResolved != ref && coResolved.matchesText(name)) {
					codes.removeAll(coResolved.normalizedAtcCodes());
				}
			}
		}
		if (!ownOrderNamed) {
			// No name resolved to ref, so ref reached the subject set through its ATC code alone — and
			// ref's own order name is therefore still in this set, indistinguishable from everyone
			// else's. Since hasActiveDrug matches a partner token as a SUBSTRING of an order name
			// (#86), leaving them in lets the subject's own order witness the pair: the token `iron`
			// inside `spironolactona`, an INN spelling the dataset's aliases do not cover. Without
			// order identity no name can be trusted for such a subject, so it may only pair by ATC.
			names.clear();
		}
		return new PatientClinicalContext(null, null, names, codes, null, null);
	}

	/**
	 * @return true when {@code ref} resolves from {@code order} — through either of the two matchers
	 *         that could have made {@code ref} a subject in {@link #activeOrderEntries}, asked of this
	 *         one order: a whole-word alias match on one of its names
	 *         ({@link DrugReferenceService#findByQuery}) or one of its ATC codes
	 *         ({@link DrugReferenceService#findByActiveOrders}). So "which order is this subject's own"
	 *         is answered by the matchers that chose it, and both legs of
	 *         {@link PatientClinicalContext#hasActiveDrug} are covered — the name-only form left an
	 *         ATC-resolved subject unattributable, which is issue #132: a concept mapped to the codes of
	 *         two interacting entries witnessed the pair between them from one order.
	 *
	 *         <p>Sharing an exact ATC code means being the same substance (level 5 is per-substance;
	 *         class relatedness is {@link DrugReference#atcSubgroups()}'s business, not this one's), so
	 *         the code leg cannot mistake a different drug's order for the subject's own.
	 */
	private static boolean resolvesFrom(DrugReference ref, PatientClinicalContext.ActiveDrugOrder order) {
		for (String name : order.getNames()) {
			if (ref.matchesText(name.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		// Emptiness first: most orders carry no ATC map at all, and this way their subject check costs no
		// allocation (normalizedAtcCodes builds a set per call).
		return !order.getAtcCodes().isEmpty()
				&& !Collections.disjoint(order.getAtcCodes(), ref.normalizedAtcCodes());
	}

	/**
	 * @return a chip's full identity — every field a client renders — so a warning that states
	 *         exactly what an already-raised one states can be recognised as a repeat. Only an EXACT
	 *         repeat: two chips that state the same clinical fact in opposite directions ("A
	 *         interacts with active order B" / "B interacts with active order A") differ here, and
	 *         collapsing those needs the pair key above, not this one. The cross-arm duplication of
	 *         issue #88 is likewise not this key's to catch: {@link #addInteractionWarnings} correlates
	 *         a rule against the class arm's own finding about the same order, which is a comparison of
	 *         REASONS rather than of rendered text, and approximating it here on a text key would
	 *         collapse two chips whose wording happens to agree. NUL-separated because the fields are
	 *         clinical prose carrying spaces, colons and dashes of their own, so any printable
	 *         delimiter would let two distinct triples key alike.
	 */
	private static String chipIdentity(SafetyWarning warning) {
		return warning.getType() + '\u0000' + warning.getDrug() + '\u0000' + warning.getDetail();
	}

	/**
	 * @return true when one of the already-raised chips in {@code said} (as {@link #chipIdentity}
	 *         strings) states everything {@code candidate} states: its exact identity, or that identity
	 *         carrying issue #88's folded class sentence after it.
	 *
	 *         <p>The second case is why equality alone is no longer enough. The screen stands down from
	 *         a pair the drug-in-play arm already reported by recognising a chip worded identically —
	 *         and for a CLASS-RELATED pair that arm's chip is precisely this candidate's chip plus one
	 *         sentence, so on equality alone the screen re-reports the pair and #88's duplicate comes
	 *         back in two wordings, one folded and one not. Reproduced through the real validator: an
	 *         uncited answer naming one of the patient's own class-related orders, under a screening
	 *         question, put both wordings in {@code safetyWarnings}.
	 *
	 *         <p>Matched against how {@link #addInteractionWarnings} composes the fold rather than by
	 *         hunting for a sentence boundary — a mechanism note is prose full of full stops — and
	 *         anchored on the folded sentence's own opening ({@code "<drug> is in "}), which is what
	 *         keeps this from firing on a longer chip that merely BEGINS with the candidate's text:
	 *         {@code iron} and {@code iron dextran} are both real KB partner labels, and
	 *         "… active order iron" is a prefix of "… active order iron dextran".
	 */
	private static boolean alreadySaid(SafetyWarning candidate, Set<String> said) {
		String identity = chipIdentity(candidate);
		if (said.contains(identity)) {
			return true;
		}
		String foldedPrefix = endSentence(identity) + " " + candidate.getDrug() + " is in ";
		for (String existing : said) {
			if (existing.startsWith(foldedPrefix)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return the active-order reference entry {@code i} NAMES — through {@link #identifies}, the same
	 *         name-identity test the question-pair arm uses, so both arms agree about which entry a
	 *         rule points at — or null when that order carries no entry in the loaded dataset.
	 *         {@code subject} is never returned: the partner is the OTHER side of the pair.
	 *
	 *         <p>Name IDENTITY, deliberately not {@link DrugReference#matchesText}. A rule's token is
	 *         exactly its partner's own alias (the {@code ddinter} parser writes it from the partner's
	 *         RxNorm generic and puts that in the partner's aliases), whereas a whole-word scan also
	 *         matches any SHORTER entry whose alias sits inside a multi-word token — "insulin" inside
	 *         "insulin glargine". One order name resolves both entries, so a first-match scan handed
	 *         back the wrong one, the reverse direction of the symmetric row keyed on the right one,
	 *         and one interaction was chipped twice in opposite directions with the withheld-pair log
	 *         naming the wrong drug. Measured on a fixture through the real parser.
	 */
	private static DrugReference activeOrderEntryFor(List<DrugReference> orderDrugs,
			DrugReference subject, DrugReference.Interaction i) {
		for (DrugReference candidate : orderDrugs) {
			if (candidate != subject && identifies(i, candidate)) {
				return candidate;
			}
		}
		return null;
	}

	/** One screened active-order pair: its chip, its sort key, and a log label naming both sides. */
	private static final class ScreenedPair {

		final SafetyWarning warning;

		final int severityPriority;

		final String label;

		ScreenedPair(SafetyWarning warning, int severityPriority, String label) {
			this.warning = warning;
			this.severityPriority = severityPriority;
			this.label = label;
		}
	}

	/** Most severe first; stable, so equally severe pairs keep dataset order. */
	private static final Comparator<ScreenedPair> SCREENED_PAIR_SEVERITY_DESCENDING = new Comparator<ScreenedPair>() {

		@Override
		public int compare(ScreenedPair a, ScreenedPair b) {
			return Integer.compare(b.severityPriority, a.severityPriority);
		}
	};

	/**
	 * Allergy-driven contraindication reasoning: for the drug {@code ref} being checked, each recorded
	 * allergy token is resolved to a reference drug and a warning fires when that allergen <em>is</em>
	 * {@code ref} (a recorded allergy to the very drug being checked), shares {@code ref}'s ATC level-4
	 * subgroup (cross-reactivity), or — failing both — shares a curated {@link CrossReactivityGroup}
	 * (cross-<em>branch</em> cross-reactivity, e.g. aspirin vs an ibuprofen allergy, which ATC's tree
	 * cannot express). One warning per resolved allergen: the most specific match wins, and several
	 * aliases of one allergy warn once. The two class comparisons need only ATC codes, which is how an
	 * authoritative classification source carrying no rules ({@link AtcDrugReferenceSource}) still
	 * produces allergy reasoning.
	 *
	 * <p><b>Identity is not classification (issue #135).</b> The three comparisons were all gated on
	 * one early return taken when {@code ref} had neither an ATC subgroup nor a curated group. That
	 * guard is right for the two class comparisons — without a subgroup or a group there is nothing to
	 * compare against — but {@code allergen == ref} is a comparison of two object references: it needs
	 * no ATC code, no group, and no dataset support of any kind, and gating it on classification data
	 * silently skipped the most basic check the module makes. Measured over the full
	 * openmrs-ddi-knowledge-base DDInter 2.0 dataset (2283 drugs) on 2026-08-05: <b>444 entries
	 * (19.4%) carry no ATC codes at all</b>, and 0 carry ATC codes without a level-4 subgroup — so the
	 * guard's two halves fail together, on nearly a fifth of the dataset (Ledipasvir, Leucovorin,
	 * Levomefolic acid, Kava, Lactic acid, Anthrax vaccine …). Nothing else covered those drugs:
	 * {@link #addContraindications} reads only curated {@code contraindications}, which
	 * {@link DdiDrugReferenceSource} never emits, and a curated {@link CrossReactivityGroup} cannot
	 * rescue them <em>in principle</em> rather than merely in practice — membership is defined by ATC
	 * PREFIX ({@link CrossReactivityGroup#containsCode}), so an entry with no ATC code can belong to no
	 * group however much curated data a deployment authors. The warning had no path to the clinician at
	 * all: no chip, and since issue #110 turns every chip into a citable pre-answer record, nothing in
	 * the prompt either.
	 *
	 * <p><b>Why the identity check stays here</b> rather than moving to {@link #addContraindications},
	 * the curated/rule arm. Two invariants are decided per resolved allergen and need code that sees
	 * all three comparisons at once: <em>most-specific-match wins</em> (an allergen that IS this drug
	 * also shares every one of its subgroups, so identity must pre-empt the class arms rather than
	 * stack on them) and <em>one warning per resolved allergen</em> ({@code seenAllergens} below, so
	 * several aliases of one allergy warn once). Split across two methods, each would need its own copy
	 * of the other's state — which is precisely the two-arms-cannot-see-each-other shape that produced
	 * issue #88's duplicate interaction chip. {@link #addContraindications} also walks a different
	 * collection ({@code ref.getContraindications()}, matched by token against allergy AND condition
	 * text), so hosting the allergen walk there would put two unrelated loops in one method and still
	 * leave the precedence decision spanning both. What was wrong was the guard's placement, not the
	 * home; the method name said "class" because two of its three comparisons are class-based.
	 *
	 * <p><b>Coverage bound, measured.</b> Identity is only as sound as the resolution behind it, and
	 * {@link DrugReferenceService#lookupByToken} returns the EARLIEST entry any of whose aliases occurs
	 * as a whole word in the allergy token. Measured over the full KB on 2026-08-05, asking about each
	 * of the 444 ATC-less entries with an allergy recorded under that entry's own name: every one now
	 * raises a contraindication, but <b>53 of them name a DIFFERENT entry</b> — always one earlier in
	 * dataset order (0 of the 53 resolve later), though not a shorter-NAMED one: 17 of the 53 resolve
	 * to a name at least as long as the queried one. What splits the 53 is whether the entry they land
	 * on carries the queried name among its OWN aliases, because that is what decides whether any
	 * matcher could have told them apart:
	 * <ul>
	 *   <li><b>43</b> where it does not, so every alias that matched is a strict FRAGMENT of the
	 *       queried name — {@code Loteprednol etabonate} resolves to {@code Loteprednol (ophthalmic)}
	 *       on that entry's alias {@code loteprednol}, {@code Magnesium salicylate} to {@code Salicylic
	 *       acid (sodium)} on its CIEL alias {@code Salicylate}. This is the nesting hazard
	 *       {@link #activeOrderEntryFor} already documents and defeats on the rule side ("insulin"
	 *       inside "insulin glargine") — but not by the same means: a rule's token IS its partner's own
	 *       alias, so that arm can demand name identity, while an allergy token is a concept name or
	 *       free text. Preferring the LONGEST matching alias over the first resolves exactly these 43
	 *       to themselves, and none of the 10 below (measured).</li>
	 *   <li><b>10</b> where the resolved entry carries the queried drug's full name among its own
	 *       aliases — as a CIEL name for 7 of them, as the {@code rxnorm_name} for the other 3.
	 *       {@code Moderna covid-19 vaccine} resolves to {@code Pfizer-BioNTech Covid-19 Vaccine}
	 *       because that entry's CIEL list contains "Moderna COVID-19 vaccine" verbatim;
	 *       {@code Dotatate} resolves to {@code Lutetium Lu 177 dotatate}. No alias matcher can
	 *       separate these — two entries genuinely claim one name — so it is a defect in the dataset's
	 *       alias data, not in the lookup. (In 4 of the 10 a shorter alias matches as well, so these
	 *       two buckets are a partition on what the target entry CLAIMS, not on which alias happened
	 *       to win the scan.)</li>
	 * </ul>
	 * The refusal is still the right one — the same misresolution is what put that entry in play, so
	 * the question and the chip agree — but the substance named is not the charted allergen. Neither
	 * shape is a defect in this arm: both reach the class comparisons below identically, and 206 of all
	 * 2283 entries do not resolve to themselves. Reported separately; do not read the 444 above as 444
	 * correctly-labelled chips. Separately again, an ANSWER-named drug can still be echo-scoped out of
	 * play before this arm sees it (issue #105, {@link #isEchoOfCitedRecord}) — the 444 measurement is
	 * of the question-driven path, which is never echo-scoped.
	 */
	private void addAllergyContraindications(List<SafetyWarning> warnings, DrugReference ref,
			PatientClinicalContext context) {
		if (context == null) {
			return;
		}
		Set<String> refClasses = ref.atcSubgroups();
		List<CrossReactivityGroup> refGroups = CrossReactivityGroup.groupsOf(ref,
				drugReferenceService.getCrossReactivityGroups());
		Set<DrugReference> seenAllergens = new LinkedHashSet<DrugReference>();
		for (String allergyToken : context.getAllergyTokens()) {
			DrugReference allergen = drugReferenceService.lookupByToken(allergyToken);
			if (allergen == null || !seenAllergens.add(allergen)) {
				continue;
			}
			if (allergen == ref) {
				warnings.add(new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, ref.displayLabel(),
						"The patient has a recorded allergy to " + ref.displayLabel() + "."));
				continue;
			}
			if (refClasses.isEmpty() && refGroups.isEmpty()) {
				// The class comparisons' own precondition, kept where it belongs — after the identity
				// check, which needs none of it. Both comparisons below are provably no-ops on empty
				// sets, so this states the requirement in code rather than leaving it to be re-derived:
				// "same class as" and "same group as" are questions only a classified drug can be asked.
				continue;
			}
			String shared = sharedClass(refClasses, allergen);
			if (shared != null) {
				warnings.add(new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, ref.displayLabel(),
						ref.displayLabel() + " is in the same ATC class (" + shared
								+ ") as the patient's allergy to " + allergen.displayLabel()
								+ " — possible cross-reactivity"));
				continue;
			}
			CrossReactivityGroup group = CrossReactivityGroup.sharedGroup(refGroups, allergen);
			if (group != null) {
				warnings.add(new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, ref.displayLabel(),
						ref.displayLabel() + " is in the same cross-reactivity group (" + group.getName()
								+ ") as the patient's allergy to " + allergen.displayLabel()
								+ " — possible cross-reactivity"));
			}
		}
	}

	/**
	 * The contraindication question no other arm asks: <b>is the patient allergic to — or does an
	 * active condition of theirs contraindicate — something they are already TAKING?</b> (Issue #143.)
	 * The two contraindication arms above, run over the patient's own active orders instead of over the
	 * drugs the question and the answer name.
	 *
	 * <p><b>The defect.</b> Both contraindication arms were keyed on a drug IN PLAY, and echo scoping
	 * ({@link #isEchoOfCitedRecord}, issue #105) removes an answer-named drug from that set whenever a
	 * record the answer cites already names it. A drug the patient is PRESCRIBED appears in a
	 * {@code drug_order} chart record — which is exactly the record a good answer cites when asked
	 * about medications — so the scoping fired on the one shape where the finding matters most.
	 * Measured on the bundled curated dataset (the production default {@code sourceFormat=json}): an
	 * active ibuprofen order plus an ibuprofen allergy, a question naming no drug and an answer citing
	 * the real order record raised <b>0 chips</b>, where the identical call with {@code mappings=null}
	 * raised <b>2</b>. An allergy to a currently-prescribed drug is a prescribing error the chart
	 * already contains, and it reached the clinician neither as a chip nor — since issue #110 turns
	 * every chip into a citable pre-answer record — as anything in the prompt.
	 *
	 * <p><b>Why this arm rather than exempting contraindications from the scoping.</b> A carve-out
	 * would fix the measured case and nothing past it, because it still needs the ANSWER to name the
	 * drug: a prescribing error nobody happened to write down stays invisible, and that is not a corner
	 * — the pre-answer findings pass ({@code DrugReferenceInjector.preAnswerFindings}) calls
	 * {@code validate} with an EMPTY answer, so for a question naming no drug there is no answer text
	 * to name anything. A carve-out would also widen #105's own over-reach onto this surface: a drug the
	 * answer merely recites out of a cited allergy or reference record would become
	 * contraindication-checked, chipping about a drug nobody proposed giving. Keyed on the patient's
	 * active orders, neither happens — and the claim {@code isEchoOfCitedRecord} makes about
	 * actively-ordered drugs becomes true instead of being relaxed.
	 *
	 * <p><b>Contraindications only.</b> Not interactions: {@link #addInteractionWarnings} over an
	 * active-order entry against the patient's own orders IS {@link #addActiveOrderPairInteractions},
	 * which is deliberately gated on the question asking to be screened (issue #113) and would here run
	 * ungated, uncapped, and without that arm's {@link #activeOrdersOtherThan} reduction — so one order
	 * would witness a pair with itself (issue #86's {@code iron} inside {@code spironolactone}). And not
	 * overdose: {@link #addOverdose} reads a dose out of the ANSWER, so an order the answer never
	 * mentions has no dose to check, and reinstating an echoed drug's dose check is precisely what #105
	 * measured and fixed.
	 *
	 * <p><b>Not gated on the question, deliberately.</b> "Is the patient allergic to something they are
	 * taking?" is a fact about their chart, not about the wording of a query, and gating a
	 * contraindication on the question's wording is what produced this defect. What bounds the arm
	 * instead is the chart: it can only fire where an allergy or condition record and an active order
	 * point at the same drug, and the two arms it delegates to bound it further — one chip per resolved
	 * allergen ({@code seenAllergens}), one per matching curated rule. That is a bound in the patient's
	 * own records, the same kind every other contraindication chip has and the reason the pairwise arms
	 * need {@link #maxPairChips()} while this one does not: nothing here is quadratic in a list the
	 * module does not choose.
	 *
	 * <p><b>The precondition.</b> With neither allergy nor condition tokens recorded both arms are
	 * provably no-ops — every branch of {@link #addContraindications} requires
	 * {@link PatientClinicalContext#hasAllergyToken} or
	 * {@link PatientClinicalContext#hasConditionToken}, and
	 * {@link #addAllergyContraindications}'s whole body is a loop over
	 * {@link PatientClinicalContext#getAllergyTokens()} — so the check is skipped rather than run to
	 * find nothing. That matters here and not in the loop above: this arm resolves the order subjects
	 * itself ({@link #activeOrderEntries}, an alias sweep of the full dataset per order name), which
	 * every question about every patient would otherwise pay for, and most patients carry no allergy
	 * record at all. Read BOTH token sets, not just allergies: the curated arm's condition leg is half
	 * of what the scoping was suppressing.
	 *
	 * <p><b>Composition.</b> An entry already in {@code inPlay} is skipped, so a prescribed drug the
	 * question also names is checked once rather than twice — identity is the right test because both
	 * sets resolve against the service's shared {@code getAll()} cache (the same reason the drugs-in-play
	 * set can dedup by identity). Nothing downstream double-counts either: the screening arm seeds its
	 * suppression set from the chips raised so far, and these are {@code TYPE_CONTRAINDICATION} while
	 * every chip that arm can raise is {@code TYPE_INTERACTION}.
	 *
	 * <p><b>What it still cannot find</b>, stated rather than implied: an order whose substance the
	 * loaded dataset does not carry resolves to no entry at all, so it has nothing to compare — the same
	 * limit {@link #activeOrderEntries} documents for the screen. And the arms it delegates to decide
	 * the rest: an allergy token that {@link DrugReferenceService#lookupByToken} resolves to the wrong
	 * entry is mislabelled here exactly as it is on the question path (see
	 * {@link #addAllergyContraindications}'s measured coverage bound).
	 */
	private void addActiveOrderContraindications(List<SafetyWarning> warnings, Set<DrugReference> inPlay,
			PatientClinicalContext context) {
		if (context == null
				|| (context.getAllergyTokens().isEmpty() && context.getConditionTokens().isEmpty())) {
			return;
		}
		for (DrugReference ref : activeOrderEntries(context)) {
			if (inPlay.contains(ref)) {
				continue;
			}
			addContraindications(warnings, ref, context);
			addAllergyContraindications(warnings, ref, context);
		}
	}

	/**
	 * Class-based interaction reasoning: warns when the drug {@code ref} being checked shares
	 * an ATC level-4 subgroup with one of the patient's active orders (additive effects / duplicate
	 * therapy) or — failing that — a curated {@link CrossReactivityGroup} (a cross-branch family
	 * overlap, e.g. ibuprofen recommended over an active aspirin order). An order that is the
	 * <em>same</em> drug as {@code ref} (a shared exact ATC code) is skipped — restating existing
	 * therapy is not a duplicate. Active orders carry ATC codes (the builder maps them), so this
	 * matches on codes directly and names the order from the dataset; the most specific match wins,
	 * so a subgroup + group double-match warns once.
	 *
	 * <p>Returns the sentences rather than adding the chips, because whether a relationship gets a chip
	 * of its own is no longer decidable from this arm alone: a pair the rule arm also raises folds into
	 * that rule's chip instead (issue #88, see {@link #addInteractionWarnings}). The reasoning itself —
	 * which codes are skipped, which family wins, how the sentence reads — is unchanged.
	 *
	 * <p>A finished sentence rather than its parts, deliberately: it is the SAME string on both
	 * surfaces, the whole detail of a class-only chip and the second sentence of a folded one. Issue
	 * #108 made every chip detail a standalone sentence led by the subject's display label and naming
	 * the order by the dataset entry's, and
	 * {@code DrugSafetyChipLabelTest.classChipOrderNamesCarryBothVocabularies} pins that phrasing; two
	 * attempts to shorten it for the folded chip broke that pin in turn — referring back to "that
	 * order" dropped the synonym-augmented order label, and prefixing "also" (to acknowledge the
	 * sentence before it) broke the "X is in the same …" wording. Concatenating each arm's own sentence
	 * unchanged is therefore the fold: the chip gains a sentence and loses nothing, and there is no
	 * second wording of this relationship for a future change to let drift.
	 *
	 * @return the class relationship sentence for each active-order ATC code that has one, keyed by
	 *         that code, in the context's own code order (so chip order is unchanged); empty when the
	 *         drug is in no class or group at all
	 */
	private Map<String, String> classRelationships(DrugReference ref, PatientClinicalContext context) {
		Map<String, String> out = new LinkedHashMap<String, String>();
		if (context == null) {
			return out;
		}
		Set<String> refClasses = ref.atcSubgroups();
		List<CrossReactivityGroup> refGroups = CrossReactivityGroup.groupsOf(ref,
				drugReferenceService.getCrossReactivityGroups());
		if (refClasses.isEmpty() && refGroups.isEmpty()) {
			return out;
		}
		Set<String> refCodes = ref.normalizedAtcCodes();
		for (String orderCode : context.getActiveDrugAtcCodes()) {
			if (refCodes.contains(orderCode)) {
				// Restating existing therapy is not a duplicate.
				continue;
			}
			if (orderCode.length() >= DrugReference.ATC_SUBGROUP_PREFIX_LENGTH && refClasses
					.contains(orderCode.substring(0, DrugReference.ATC_SUBGROUP_PREFIX_LENGTH))) {
				out.put(orderCode, ref.displayLabel() + " is in the same ATC class ("
						+ orderCode.substring(0, DrugReference.ATC_SUBGROUP_PREFIX_LENGTH)
						+ ") as active order " + displayLabelForAtcCode(orderCode)
						+ " — possible duplicate therapy");
				continue;
			}
			CrossReactivityGroup group = CrossReactivityGroup.sharedGroupForCode(refGroups, orderCode);
			if (group != null) {
				out.put(orderCode, ref.displayLabel() + " is in the same cross-reactivity group ("
						+ group.getName() + ") as active order " + displayLabelForAtcCode(orderCode)
						+ " — possible additive or duplicate-class therapy");
			}
		}
		return out;
	}

	/** @return the ATC level-4 subgroup {@code other} shares with {@code refClasses}, or null when none. */
	private static String sharedClass(Set<String> refClasses, DrugReference other) {
		for (String cls : other.atcSubgroups()) {
			if (refClasses.contains(cls)) {
				return cls;
			}
		}
		return null;
	}

	/**
	 * @return the loaded reference entry carrying {@code upperCode} — the dataset's own record for the
	 *         substance an active order's ATC code identifies — or null when the dataset does not cover
	 *         it. One definition, because the class chip NAMES this entry while the cross-arm
	 *         correlation asks WHICH RULE POINTS AT IT ({@link #ruleAbout}): resolving the code two
	 *         ways would let a chip name one substance while the fold decided about another.
	 */
	private DrugReference entryForAtcCode(String upperCode) {
		for (DrugReference ref : drugReferenceService.getAll()) {
			if (ref.normalizedAtcCodes().contains(upperCode)) {
				return ref;
			}
		}
		return null;
	}

	/** @return the synonym-augmented display label ({@link DrugReference#displayLabel()}) of the
	 *          reference drug carrying {@code upperCode}, or the bare code when the active
	 *          order's substance is not present in the loaded dataset. */
	private String displayLabelForAtcCode(String upperCode) {
		DrugReference entry = entryForAtcCode(upperCode);
		return entry != null ? entry.displayLabel() : upperCode;
	}

	private void addOverdose(List<SafetyWarning> warnings, DrugReference ref,
			PatientClinicalContext context, String lowerAnswer, List<DrugReference> allEntries) {
		Integer age = context != null ? context.getAgeYears() : null;
		DrugReference.AgeBand band = ref.bandForAge(age);
		if (band == null) {
			return;
		}
		Double weightKg = context != null ? context.getWeightKg() : null;
		boolean dailyArm = band.getMaxDailyDoseMg() > 0;
		boolean weightArm = weightKg != null && weightKg > 0 && band.getMgPerKgMax() > 0;
		if (!dailyArm && !weightArm) {
			return;
		}
		// One attribution walk feeds whichever arms apply.
		List<AttributedDose> doses = attributedDoses(lowerAnswer, ref, allEntries);
		if (dailyArm) {
			Double dailyMg = parseDailyDoseMg(doses);
			if (dailyMg != null && dailyMg > band.getMaxDailyDoseMg()) {
				warnings.add(new SafetyWarning(SafetyWarning.TYPE_OVERDOSE, ref.displayLabel(),
						"The stated " + ref.displayLabel() + " dose ~" + DrugReference.formatNumber(dailyMg)
								+ " mg/day exceeds the "
								+ DrugReference.formatNumber(band.getMaxDailyDoseMg()) + " mg/day maximum for ages "
								+ band.getMinYears() + "-" + band.getMaxYears()));
				// One warning per drug: the published daily ceiling is the stronger statement,
				// so the per-dose arm below is not stacked on top of it.
				return;
			}
		}
		if (!weightArm) {
			return;
		}
		Double perDoseMg = parseMaxPerDoseMg(doses);
		double perDoseLimitMg = band.getMgPerKgMax() * weightKg;
		if (perDoseMg != null && perDoseMg > perDoseLimitMg) {
			warnings.add(new SafetyWarning(SafetyWarning.TYPE_OVERDOSE, ref.displayLabel(),
					"The stated " + ref.displayLabel() + " dose ~" + DrugReference.formatNumber(perDoseMg)
							+ " mg exceeds the "
							+ DrugReference.formatNumber(band.getMgPerKgMax()) + " mg/kg per-dose maximum (~"
							+ DrugReference.formatNumber(perDoseLimitMg) + " mg) for the patient's weight "
							+ DrugReference.formatNumber(weightKg) + " kg (ages " + band.getMinYears() + "-"
							+ band.getMaxYears() + ")"));
		}
	}

	/**
	 * The one clause-scoped, alias-anchored attribution walk both overdose arms consume, so a dose
	 * counts for the daily and the per-dose check under exactly the same conditions. One drug is
	 * never charged with another's dose: the answer is split into clauses, and within each clause
	 * that names {@code ref} a {@code N mg} value counts only when (a) it is not introduced by a
	 * limit cue — "maximum", "up to", … make it a ceiling, not a prescribed dose — and (b)
	 * {@code ref}'s alias is the nearest drug name to it (no other entry's alias sits strictly
	 * closer). The frequency is read from the same clause, so a frequency stated for a different
	 * drug in a neighbouring sentence is never applied.
	 *
	 * <p>Known limitation (v1): only the literal unit {@code mg} is recognised; doses written in
	 * grams ("1 g"), "mgs", or "milligrams" are not parsed and will not be flagged. That is the
	 * conservative (miss, never false-positive) direction.
	 */
	private static List<AttributedDose> attributedDoses(String lowerAnswer, DrugReference ref,
			List<DrugReference> allEntries) {
		List<AttributedDose> out = new ArrayList<AttributedDose>();
		for (String clause : CLAUSE_DELIMITER.split(lowerAnswer)) {
			if (!ref.matchesText(clause)) {
				continue;
			}
			Matcher m = DOSE_MG.matcher(clause);
			while (m.find()) {
				int dosePos = m.start();
				if (precededByLimitCue(clause, dosePos) || !aliasOwnsDose(clause, dosePos, ref, allEntries)) {
					continue;
				}
				double perDose;
				try {
					perDose = Double.parseDouble(m.group(1));
				}
				catch (NumberFormatException e) {
					continue;
				}
				out.add(new AttributedDose(perDose, frequencyPerDay(clause)));
			}
		}
		return out;
	}

	/** One dose statement attributed to a drug: per-administration mg + the clause's stated
	 *  doses-per-day ({@code 0} = no frequency stated). */
	private static final class AttributedDose {

		final double perDoseMg;

		final int frequencyPerDay;

		AttributedDose(double perDoseMg, int frequencyPerDay) {
			this.perDoseMg = perDoseMg;
			this.frequencyPerDay = frequencyPerDay;
		}
	}

	/**
	 * @return the largest plausible daily dose (mg) among the attributed doses. When a dose is
	 *         found without a frequency, frequency 1 is assumed (conservative — it cannot
	 *         over-report a daily total). Null when nothing was attributed.
	 */
	private static Double parseDailyDoseMg(List<AttributedDose> doses) {
		Double best = null;
		for (AttributedDose dose : doses) {
			double daily = dose.perDoseMg * (dose.frequencyPerDay > 0 ? dose.frequencyPerDay : 1);
			if (best == null || daily > best) {
				best = daily;
			}
		}
		return best;
	}

	/**
	 * @return the largest per-administration dose (mg) among the attributed doses (the limit-cue
	 *         and nearest-alias guards already applied by the walk). Null when nothing was attributed.
	 */
	private static Double parseMaxPerDoseMg(List<AttributedDose> doses) {
		Double best = null;
		for (AttributedDose dose : doses) {
			if (best == null || dose.perDoseMg > best) {
				best = dose.perDoseMg;
			}
		}
		return best;
	}

	/** @return true when a limit cue ("maximum", "up to", "do not exceed", …) sits immediately
	 *          before the dose at {@code dosePos}, marking it a ceiling rather than a stated dose. */
	private static boolean precededByLimitCue(String clause, int dosePos) {
		int from = Math.max(0, dosePos - LIMIT_CUE_LOOKBACK);
		return LIMIT_CUE.matcher(clause.substring(from, dosePos)).find();
	}

	/** @return true when {@code ref}'s alias is the nearest drug name to the dose at {@code dosePos}
	 *          within {@code clause} (and within {@link #MAX_ALIAS_TO_DOSE_DISTANCE}). A different
	 *          entry's alias sitting strictly closer means the dose belongs to that drug, not this. */
	private static boolean aliasOwnsDose(String clause, int dosePos, DrugReference ref,
			List<DrugReference> allEntries) {
		int mine = nearestAliasDistance(clause, dosePos, ref);
		if (mine == Integer.MAX_VALUE || mine > MAX_ALIAS_TO_DOSE_DISTANCE) {
			return false;
		}
		for (DrugReference other : allEntries) {
			if (other != ref && nearestAliasDistance(clause, dosePos, other) < mine) {
				return false;
			}
		}
		return true;
	}

	/** @return character distance from {@code pos} to the nearest occurrence of any of {@code ref}'s
	 *          aliases in {@code text}, or {@link Integer#MAX_VALUE} when none occur. */
	private static int nearestAliasDistance(String text, int pos, DrugReference ref) {
		int best = Integer.MAX_VALUE;
		for (String alias : ref.getAliases()) {
			if (alias == null || alias.isEmpty()) {
				continue;
			}
			String a = alias.toLowerCase(Locale.ROOT);
			int idx = text.indexOf(a);
			while (idx >= 0) {
				int end = idx + a.length();
				int dist = pos < idx ? idx - pos : (pos > end ? pos - end : 0);
				if (dist < best) {
					best = dist;
				}
				idx = text.indexOf(a, idx + 1);
			}
		}
		return best;
	}

	/** @return doses-per-day implied by a frequency phrase in {@code window}, or 0 when none found.
	 *          Word-forms are word-boundary anchored, so "bd"/"od" do not match inside larger words
	 *          such as "abdominal" or "blood". */
	static int frequencyPerDay(String window) {
		Matcher hours = EVERY_N_HOURS.matcher(window);
		if (hours.find()) {
			String n = hours.group(1) != null ? hours.group(1)
					: hours.group(2) != null ? hours.group(2) : hours.group(3);
			try {
				int h = Integer.parseInt(n);
				if (h > 0) {
					return (int) Math.round(24.0 / h);
				}
			}
			catch (NumberFormatException e) {
				// fall through to word forms
			}
		}
		if (FREQ_QID.matcher(window).find()) {
			return 4;
		}
		if (FREQ_TID.matcher(window).find()) {
			return 3;
		}
		if (FREQ_BID.matcher(window).find()) {
			return 2;
		}
		if (FREQ_OD.matcher(window).find()) {
			return 1;
		}
		return 0;
	}

	private static boolean toggle(String property, boolean defaultValue) {
		return ChartSearchAiUtils.getBooleanGlobalProperty(property, defaultValue);
	}
}
