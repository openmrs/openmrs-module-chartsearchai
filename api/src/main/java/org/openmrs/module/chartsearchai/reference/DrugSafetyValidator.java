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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 *       {@link CrossReactivityGroup} (cross-branch family overlap). One warning per (SUBSTANCE, active
 *       order), whichever of those reasons applies and however many apply at once: several rules can
 *       name one partner — DDInter's route variants of a drug all publish the same match token — and
 *       they collapse to the most severe row ({@link #bestRulePerPartner}); the several rows one
 *       substance is FILED as collapse the same way on the subject side, so a question naming a
 *       substance no longer chips once per route ({@link #addInteractionWarnings}, issue #162); and a
 *       partner that is
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
 *       (cross-branch cross-reactivity). One warning per (SUBSTANCE, recorded finding), not per
 *       reference row: a dataset that files one substance as several route or formulation rows put
 *       every one of them in play from one clinician-facing word, and each raised its own chip — the
 *       siblings of the row the allergy resolved to reporting the substance as cross-reactive with
 *       itself ({@link ContraindicationChips}, issue #145). Per recorded FINDING and not per arm
 *       either: a hand-authored allergy rule naming the very drug it is filed against reports the
 *       identity check's fact, so the two fold into one chip keeping whichever wording carries the
 *       deployment's own note (issue #146 — 3 of the 4 entries in the shipped curated file). These
 *       same two checks additionally run
 *       over the patient's OWN ACTIVE ORDERS, whatever the question and the answer name — "is the
 *       patient allergic to something they are taking?" is a fact about their chart, and the
 *       drug-in-play framing above could not ask it (see
 *       {@link #addActiveOrderContraindications}, issue #143).</li>
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
 * reasoning stays data-driven end to end. A shared subgroup is necessary but not sufficient since
 * issue #167: one that classifies neither the substances nor a therapy is skipped, and the pair falls
 * through to the curated groups as though nothing were shared — see
 * {@link DrugReference#isUnclassifyingAtcCode}. See ADR Decision 24.
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
	 * {@link DrugReferenceService#findImpliedByQuery} the injector uses, so the two never drift) and those
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
	List<SafetyWarning> validate(String answer, String question, PatientClinicalContext rawContext,
			List<RecordMapping> mappings) {
		List<SafetyWarning> warnings = new ArrayList<SafetyWarning>();
		// The patient's active orders resolved to their reference entries, ONE dataset sweep per
		// validate, feeding both things this pass needs from that resolution (issue #136):
		//
		//   - the entries themselves, for the three arms that screen or name them — the chip grouping's
		//     partner identity below, the active-order contraindication subjects (#143) and the
		//     screening subjects (#113), which were each resolving them for themselves;
		//   - and those entries' own names on the context, so hasActiveDrug can match a rule token
		//     against them. Attached here rather than threaded through the arms below: that keeps
		//     hasActiveDrug the single join, with the same signature for every caller, so no arm can
		//     accidentally ask the narrower question.
		//
		// One resolution for both, so the names the context carries and the subjects the arms read can
		// never describe different sets of orders. Per validate, not per question: the pre-answer
		// findings pass reaches this method through DrugReferenceInjector.injectRecords, which resolves
		// the same orders for its own promotion predicate before calling in. That repeat is idempotent
		// today — neither leg of the resolution reads the names it attaches — so it is cost, and a trap
		// for the first widening that makes it read them. Reported, not fixed here.
		List<DrugReference> orderEntries = drugReferenceService.findForActiveOrders(rawContext);
		PatientClinicalContext context = drugReferenceService.withReferenceNames(rawContext, orderEntries);

		boolean warnDose = toggle(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_DOSE_EXCESS,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WARN_ON_DOSE_EXCESS);
		boolean warnInteractions = toggle(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_INTERACTIONS,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WARN_ON_INTERACTIONS);
		boolean warnContra = toggle(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS);

		String lower = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
		List<DrugReference> all = drugReferenceService.getAll();

		// Drugs in play = those the QUESTION resolves to UNION those the ANSWER names — both via the same
		// DrugReferenceService.findImpliedByQuery the injector uses, so question/answer/injector matching
		// can never drift, and identity-dedup holds (it resolves against the shared getAll() cache).
		// Answer-side drugs are echo-scoped (issue #105): a drug the answer names while a record the
		// answer cites already names it in its own text is an echo of that record (a recited reference
		// partner, an allergy reported off the chart), not a proposal — validating it produced chips
		// about drugs nobody suggested giving. Question-named drugs are always validated.
		//
		// findImpliedByQuery, not the bare findByQuery, since issue #209: what this set is is the drugs
		// in PLAY, and prose carrying one alias of two substances put both in play — so a question about
		// hydrocortisone chipped about `Hydrocortisone butyrate`, an ester nobody named. The prose matcher
		// itself is unchanged (it answers "is this entry mentioned", correctly); the ranking is applied
		// where the answer has to be a substance.
		Set<DrugReference> questionDrugs = new LinkedHashSet<DrugReference>(
				drugReferenceService.findImpliedByQuery(question));
		Set<DrugReference> inPlay = new LinkedHashSet<DrugReference>(questionDrugs);
		// The echo corpus is built lazily so the common case — the answer names no drug beyond
		// the question's — does no citation parsing and no mapping sweep at all.
		List<String> citedTextsLower = null;
		for (DrugReference ref : drugReferenceService.findImpliedByQuery(answer)) {
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

		// One ledger for every contraindication chip this pass raises, across BOTH arms and both of
		// their call sites (the drug-in-play loop below and the order-driven arm after it) — see
		// ContraindicationChips. It has to span them: one substance's route variants can arrive as
		// several drugs in play, as several entries of one active order, or as some of each, and a
		// collapse living inside one arm would still let the other emit the siblings.
		ContraindicationChips contraindications = new ContraindicationChips(warnings);

		// The interaction arms' subject is a SUBSTANCE, not a reference row (issue #162): one clinician
		// word resolves every route/formulation row of a substance, and the arm ran once per row, so one
		// pair became one chip per row. Grouped here rather than inside the arm because the arm is what
		// has to see the whole group at once — its survivor rule compares rows against each other.
		//
		// A per-validate local, never a field: a memoised DrugReference outliving a getAll() hot-reload
		// fails the reference comparisons the contraindication arms make against the same objects
		// (issue #172), which would silently re-open #145 with no test failing.
		//
		// Consumed inside the row loop below, keyed by the group's first row, so the substance's chips
		// land where its first row's chips have always landed and no client sees the chip sequence
		// reshuffle — the same positional promise ContraindicationChips makes.
		//
		// The group is the rows of that substance this pass resolved from EITHER side — the question and
		// answer text, and the patient's own orders (issue #175); see resolvedSubstanceRows.
		Map<Object, List<DrugReference>> interactionSubjects = warnInteractions
				? resolvedSubstanceRows(inPlay, orderEntries)
				: Collections.<Object, List<DrugReference>> emptyMap();

		// The same grouping for the DOSE arm (issue #174 site 4), which ran once per row and so
		// produced one dose warning per row for one stated dose — see addOverdose. Its own map rather
		// than the one above, because both are consumed by remove() as the loop reaches each group's
		// first row and one map cannot be drained twice; and gated on its own toggle, so switching
		// interactions off does not silently switch the dose grouping off with it.
		//
		// The same GROUP as well, not merely the same grouping method: both arms name their subject
		// through interactionSubject, so a narrower row set here would let one response call one
		// substance two things — the divergence this PR exists to remove, re-created by fixing only the
		// interaction arms. It also gives this arm what its own javadoc asks for, every row of the
		// substance the request resolved being tried for a published band.
		Map<Object, List<DrugReference>> doseSubjects = warnDose
				? resolvedSubstanceRows(inPlay, orderEntries)
				: Collections.<Object, List<DrugReference>> emptyMap();

		// One ledger of the (substance, partner) pairs an interaction chip has been raised for, spanning
		// the drug-in-play arm below and the screening arm at the end — see InteractionPairs. Like the
		// contraindication ledger above it is a per-validate local, and for the same reason: it holds
		// DrugReference-derived keys, which must not outlive a getAll() hot-reload.
		InteractionPairs interactionPairs = new InteractionPairs();

		// The patient's recorded allergies resolved to the SUBSTANCES they name, ONE resolution per pass
		// (issues #193/#195). Resolved here rather than inside addAllergyContraindications, which is where
		// it used to happen: that arm runs once per subject, and the answer does not depend on the
		// subject, so a patient with several subjects resolved the same allergy list several times over —
		// and since a recorded name now also resolves each of its constituents and its parent moiety, the
		// repeat is several dataset sweeps rather than one. Same shape as orderEntries above (issue #136),
		// and a per-validate local for the same reason as the two ledgers: it holds DrugReference objects,
		// which must not outlive a getAll() hot-reload (issue #172).
		List<List<DrugReference>> recordedAllergens = warnContra
				? recordedAllergens(context) : Collections.<List<DrugReference>> emptyList();

		for (DrugReference ref : inPlay) {
			if (warnContra) {
				addContraindications(contraindications, ref, context);
				addAllergyContraindications(contraindications, ref, recordedAllergens);
			}
			if (warnInteractions) {
				// remove(), so a substance's rows are handed to the arm ONCE — at the first of them — and
				// the map itself is the already-done ledger.
				List<DrugReference> substance = interactionSubjects.remove(ref.substanceGroupKey());
				if (substance != null) {
					// One call, not one per arm: the rule arm and the class arm can both raise a chip about
					// the same active order, so the decision of how many chips that pair gets belongs to a
					// method that sees both (issue #88).
					addInteractionWarnings(warnings, substance, context, severityFloor, orderEntries,
							interactionPairs);
				}
			}
			if (warnDose) {
				// remove(), for the same reason as the interaction arm above: a substance's rows are
				// handed to the arm ONCE, at the first of them, so its warning keeps the position that
				// row's warning has always had and the map itself is the already-done ledger.
				List<DrugReference> substance = doseSubjects.remove(ref.substanceGroupKey());
				if (substance != null) {
					addOverdose(warnings, substance, context, lower, all);
				}
			}
		}
		// The patient's own prescriptions against their own allergy and condition records — the one
		// contraindication question no drug-in-play arm can ask (issue #143). After the loop above so a
		// drug in play keeps the chip position it has always had, and before the PAIRWISE arms below so
		// that a check against her own allergy and condition records is read before any pair the
		// reference data merely relates. Not because those arms are less about her — the #113 screen's
		// pairs are her own orders on both sides — but because they are a lookup OVER her medication
		// list rather than a finding AGAINST her records, they are the two that grow quadratically, and
		// they are the two a cap can truncate (maxPairChips, #131).
		if (warnContra) {
			addActiveOrderContraindications(contraindications, inPlay, context, orderEntries,
					recordedAllergens);
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
			addActiveOrderPairInteractions(warnings, context, severityFloor, orderEntries,
					interactionPairs);
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

	/**
	 * Every contraindication chip one {@code validate} pass raises: <b>at most one per (substance,
	 * recorded finding)</b>, whatever arm reaches it and however many reference rows the loaded
	 * dataset files that substance as (issue #145).
	 *
	 * <p><b>The defect.</b> Both contraindication arms are keyed on a subject ENTRY, and DDInter files
	 * one substance as several route/formulation rows. One clinician-facing string resolves all of them
	 * — a candidate set is every ROW of each substance the string names, and every row of a substance
	 * publishes the same aliases — so one clinical fact became one chip per row. Measured live on
	 * the 3.7.1 standalone: a recorded dexamethasone allergy asked about dexamethasone gave FOUR chips,
	 * and only the row {@link DrugReferenceService#lookupByToken} resolved the allergy to matched by
	 * identity, so the other three fell through to the class comparison and reported the substance as
	 * cross-reactive with the patient's allergy to <em>itself</em>. Since issue #110 every chip
	 * is also injected as a citable pre-answer record, so each duplicate reached the prompt as well.
	 *
	 * <p><b>Why a ledger rather than a filter over the finished chip list.</b> Three reasons, and the
	 * first is the decisive one. (1) The sibling's chip is not merely a duplicate, it is WRONG — the
	 * true statement about that substance is the identity one — so the collapse has to choose which
	 * relationship survives, and by the time only rendered text is left the reasons are gone. (2) Those
	 * chips differ in text (each names its own route), so an exact-repeat key over the rendered chip
	 * cannot see them; recognising "differs only in a route qualifier" from the strings alone means
	 * pattern-matching a display label, which is the mistake issue #148 had to undo. (3) The two arms
	 * run at two call sites — the drug-in-play loop and {@link #addActiveOrderContraindications} — and
	 * a collapse inside either one leaves the other emitting the siblings, which is Sarah Taylor's live
	 * shape (one hydrocortisone order, four rows, four chips, question naming no drug).
	 *
	 * <p><b>The key.</b> {@code (subject substance, recorded finding)}. The subject side is
	 * {@link DrugReference#substanceKey()}, which is where the measurement behind it lives and why it
	 * is not simply the dataset's substance name; an entry from a source publishing none keys on its
	 * own identity, so nothing collapses for the {@code atc} adapter or the shipped curated
	 * {@code json} dataset. The finding side is what the arm actually compared — the resolved allergen's
	 * SUBSTANCE for the allergy arm, by the same {@link DrugReference#substanceGroupKey()} as the
	 * subject side, and the curated rule's own {@code (type, token)} for the rule arm.
	 * Those stay two key spaces, and the reason is unchanged: a rule's token is free text that may name a
	 * CLASS ({@code nsaid}, {@code aminoglycoside}) rather than a drug, so resolving tokens to entries
	 * wholesale in order to make the two arms comparable would collapse a class-level rule into an
	 * identity chip — a different and worse defect. What keeps the two spaces from colliding is no longer
	 * their TYPE — the allergy finding used to be a {@link DrugReference}, which defines no
	 * {@code equals}, and is now usually a {@link List} like the rule's — but their LENGTH, two against
	 * three, plus the rule's leading {@code "rule"} tag. Both are needed: a substance key that grew a
	 * third component would collide on length alone.
	 *
	 * <p><b>The one rule that crosses (issue #146).</b> An allergy rule whose token is one of the SUBJECT
	 * ENTRY'S OWN names reports the allergy arm's fact — the patient is allergic to this very drug — so
	 * it is filed in the allergy arm's key space instead of the rule one, and the two collapse. On the
	 * shipped default {@code sourceFormat=json} that shape is 3 of the file's 4 entries (Gentamicin's
	 * allergy rule names a class, which is why it was the control), and each of the three double-reported
	 * one allergy: {@code Ibuprofen is contraindicated by an active allergy: documented ibuprofen
	 * allergy} beside {@code The patient has a recorded allergy to Ibuprofen.} — the first defect in this
	 * area that needed no non-default configuration to see. What is keyed is the FACT and never "both
	 * arms fired", which is what leaves a class-level rule its own chip beside the folded one for a
	 * patient recorded as allergic to both the drug and its class.
	 *
	 * <p>The test is name IDENTITY between two REFERENCE strings — a curated token against the entry's
	 * own alias list — so it is {@link DrugReference#isNamed}, the same predicate {@link #namesEntry}
	 * asks of an interaction rule's token. Deliberately NOT
	 * {@link DrugReferenceService#findImpliedSubstances}: that answers which substances a RECORDED name
	 * denotes and widens deliberately (issues #193/#195/#209), and applying it to a curated token is
	 * exactly the wholesale resolution the paragraph above rules out. The narrower predicate also bounds
	 * the fold to the one shape it is about — {@code isNamed} can only be true of the entry the rule is
	 * filed on, so no rule can fold onto a substance other than its own subject.
	 *
	 * <p><b>Which chip survives.</b> The most specific relationship, since that is this arm's analogue
	 * of "the highest severity wins" — a contraindication chip carries no severity, and what it can
	 * under-report is the STRENGTH of the claim: identity ("the patient has a recorded allergy to X")
	 * over a shared ATC class over a shared curated group. Where a self-named curated rule joins that
	 * space it is ranked by what it ADDS, not by which arm produced it — issue #88's finding that "arm X
	 * yields to arm Y" is the wrong dedup whenever the yielding arm can be the one carrying the content.
	 * A rule with a note of its own says the identity fact in the deployment's own words and outranks it;
	 * a rule with none renders its own token back
	 * ({@link ChartSearchAiUtils#firstNonBlank}, "…: ibuprofen") and is outranked by every other
	 * relationship, so it survives only where nothing else reports the substance at all. Ties keep the
	 * incumbent, so a group of
	 * equally-related rows is reported as the dataset's first row. NOT the same rule
	 * {@link #bestRulePerPartner} applies since issue #162: that one prefers the row naming no route
	 * before falling back to the incumbent, and this one does not — so a substance whose unqualified row
	 * is not the dataset's first (7 of the shipped KB's multi-row families) can have an interaction chip
	 * naming it and a contraindication chip naming one of its routes in the same response. That is the
	 * route-qualifier residue this javadoc's last paragraph already accepts, now visible against a
	 * canonicalized sibling arm rather than against another per-row one. It no longer reaches the
	 * IDENTITY chip, which since issue #164 is named after the patient's own recorded allergen rather
	 * than after whichever row of the substance raised it. The surviving chip is written back into the
	 * position the group's first candidate took, so no client sees the chip sequence reshuffle when a
	 * later, stronger row replaces an earlier one.
	 *
	 * <p>Resolving a tie by position is lossless rather than merely tidier, and that rests on the rows
	 * of one substance publishing the SAME ATC codes — which the shipped KB does for every group this
	 * key merges, and which is the thing to re-measure before widening the key, since the class chip
	 * names a code and a divergent row's code would be dropped unheard. Curated-group membership needs
	 * no separate check: {@link CrossReactivityGroup#groupsOf} is a pure function of those same codes,
	 * so equal codes are equal membership. What is left for a tie to choose between is then the route
	 * qualifier in the subject's own label — and, since issue #146 put self-named rules in this space,
	 * one such rule's NOTE against another's, where an entry authors the same rule twice
	 * ({@code ContraindicationRouteVariantTest.oneCuratedRuleAuthoredTwiceRaisesOneChip}, whose comment
	 * records why the incumbent is the right survivor for a re-spelling and lossy for two genuinely
	 * different notes). Issue #176 widened the key's FINDING side onto the same
	 * substance, so that instruction was carried out: re-measured 2026-08-08 through
	 * {@link DrugReference#atcSubgroups()}, 0 of the 129 multi-row families publish differing level-4
	 * subgroups, and 0 differ in curated-group membership.
	 *
	 * <p><b>Replacing an incumbent, and what still reaches it.</b> This ledger used to be reachably
	 * order-dependent: an allergy resolves to ONE row of its substance and not necessarily the group's
	 * first — where no alias of a row is the bare substance name the allergy skips that row and resolves
	 * a LATER member of its own group — and the earlier members, matching by class rather than by
	 * identity, raised the weaker chip first ({@code Tozinameran} behind the {@code Pfizer-BioNTech}
	 * presentations, {@code Insulin aspart (aspart)}, {@code Iobenguane (I-131)}). Issue #164 removed
	 * that route: identity is now decided by SUBSTANCE, so every row of one group answers the identity
	 * question the same way and an identity chip can no longer arrive after a class one for the same
	 * key. What is left to replace is a class chip by a more specific class chip — a group whose rows
	 * publish DIFFERENT ATC codes, so that one shares only a curated group with the allergen while
	 * another shares a level-4 subgroup. The shipped KB has no such group (0 of the 129 it files as
	 * more than one row; measured 2026-08-07 and the same 0 before this key widened), so the branch is
	 * currently unexercised rather than wrong, and it is kept because "the most specific relationship
	 * survives" is this arm's contract and first-wins is not: a refresh that diverges one row's codes
	 * would otherwise silently report the weaker relationship. Re-measure that 0 rather than trusting
	 * it — it is a property of the dataset, not of this code.
	 */
	private static final class ContraindicationChips {

		/** A curated allergy rule NAMING THE SUBSTANCE it is filed against and carrying a note of its
		 *  own (issue #146): the identity relationship below, stated in the deployment's own clinical
		 *  wording. Above {@link #IDENTITY} because it says everything that sentence says and the note
		 *  besides, which nothing else in this ledger can reproduce — a deployment authoring
		 *  {@code drug-reference.json} is recording exactly that wording, and a fold that kept the
		 *  module's stock sentence would silently discard it. */
		static final int NAMED_RULE = 4;

		/** A recorded allergy to this very substance — needs no ATC code and outranks both class
		 *  comparisons (the precedence {@link #addAllergyContraindications} already applies per
		 *  allergen, extended across the rows of one substance). Since issue #164 the comparison behind
		 *  it is the substance rather than the reference row, so it is uniform across the rows this
		 *  ledger groups: every row of one substance answers it alike. */
		static final int IDENTITY = 3;

		/** A shared ATC level-4 chemical subgroup with the allergen. */
		static final int SAME_CLASS = 2;

		/** A shared curated {@link CrossReactivityGroup} with the allergen — the fallback the class
		 *  comparison takes when no subgroup is shared, and so the least specific of the three. */
		static final int SAME_GROUP = 1;

		/** A curated contraindication rule. Its own key space, so this rank never competes with the
		 *  three above; it exists so every call reads alike. */
		static final int CURATED_RULE = 1;

		/** {@link #NAMED_RULE} with no note of its own — the same claim, rendered as the rule's own
		 *  token ("Ibuprofen is contraindicated by an active allergy: ibuprofen"), which says strictly
		 *  less than {@link #IDENTITY}. Below every other relationship rather than merely below that
		 *  one, so it can never displace a chip and is displaced by any: it is still RAISED, because the
		 *  arms are independent and this rule fires on evidence the allergen arm need not reproduce
		 *  ({@link PatientClinicalContext#hasAllergyToken} is bare containment where
		 *  {@link DrugReferenceService#findImpliedSubstances} is boundary-aware), and dropping it would
		 *  have made a curated rule conditional on an arm that never gated it. */
		static final int NAMED_RULE_WITHOUT_A_NOTE = 0;

		/** A chip already raised for one key: where it sits in {@code warnings}, and how specific the
		 *  relationship behind it is. ONE entry rather than two maps keyed alike, so the position and the
		 *  rank cannot desync — a position with no rank beside it would throw inside a {@code validate}
		 *  whose callers catch {@link RuntimeException} and return nothing, i.e. it would silently drop
		 *  every chip on the request rather than the one it mishandled. */
		private static final class RaisedChip {

			private final int position;

			private int relationship;

			RaisedChip(int position, int relationship) {
				this.position = position;
				this.relationship = relationship;
			}
		}

		private final List<SafetyWarning> warnings;

		private final Map<List<Object>, RaisedChip> raised = new LinkedHashMap<List<Object>, RaisedChip>();

		ContraindicationChips(List<SafetyWarning> warnings) {
			this.warnings = warnings;
		}

		/**
		 * Raise {@code chip} for {@code subject} about {@code finding}, unless a chip for that pair is
		 * already raised — in which case the more specific {@code relationship} wins, in place.
		 */
		void add(DrugReference subject, Object finding, int relationship, SafetyWarning chip) {
			// substanceGroupKey: the substance this row stands for, else the row itself — the same key the
			// interaction arms' subject side groups on (issue #162), shared so the two arms cannot come to
			// merge different sets of rows. Its javadoc is where the two key spaces are justified.
			List<Object> key = Arrays.asList(subject.substanceGroupKey(), finding);
			RaisedChip already = raised.get(key);
			if (already == null) {
				raised.put(key, new RaisedChip(warnings.size(), relationship));
				warnings.add(chip);
				return;
			}
			if (relationship > already.relationship) {
				warnings.set(already.position, chip);
				already.relationship = relationship;
			}
		}
	}

	private void addContraindications(ContraindicationChips chips, DrugReference ref,
			PatientClinicalContext context) {
		if (context == null) {
			return;
		}
		for (DrugReference.Contraindication c : ref.getContraindications()) {
			boolean allergy = "allergy".equalsIgnoreCase(c.getType())
					&& context.hasAllergyToken(c.getToken());
			boolean condition = !allergy && "condition".equalsIgnoreCase(c.getType())
					&& context.hasConditionToken(c.getToken());
			if (!allergy && !condition) {
				continue;
			}
			// The rule as the two tests above compared it — type case-insensitively, token through
			// hasAllergyToken/hasConditionToken, which lower-case — so the key says what the match
			// said, and two rows differing only in case cannot chip twice.
			Object finding = Arrays.asList("rule", DrugReference.normalizeName(c.getType()),
					DrugReference.normalizeName(c.getToken()));
			int relationship = ContraindicationChips.CURATED_RULE;
			if (allergy && ref.isNamed(c.getToken())) {
				// The rule names the drug it is filed against, so it reports what
				// addAllergyContraindications reports: one allergy, one chip (issue #146). Keyed the way
				// that arm keys it — on the SUBSTANCE, so this also collapses across the rows one
				// substance is filed as, exactly as issue #145's dedup does on the subject side. Only an
				// ALLERGY rule can join it; a condition rule token that happened to name the drug would
				// be a fact about a condition record, which no chip in this key space is about.
				finding = ref.substanceGroupKey();
				relationship = ChartSearchAiUtils.isBlank(c.getNote())
						? ContraindicationChips.NAMED_RULE_WITHOUT_A_NOTE
						: ContraindicationChips.NAMED_RULE;
			}
			chips.add(ref, finding, relationship,
					new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, ref.displayLabel(),
							ref.displayLabel() + " is contraindicated by an "
									+ (allergy ? "active allergy" : "active condition") + ": "
									+ ChartSearchAiUtils.firstNonBlank(c.getNote(), c.getToken())));
		}
	}

	/**
	 * Every interaction chip one SUBSTANCE raises about the patient's own medications: <b>one chip per
	 * (substance, active order) pair</b>, with the two arms that can each raise one — the rule arm
	 * ({@link #bestRulePerPartner}) and the class arm ({@link #classRelationships}) — coordinated
	 * instead of run independently (issue #88).
	 *
	 * <p><b>The subject is a substance, not a reference row (issue #162).</b> This ran once per entry in
	 * the drugs-in-play set, and {@link #bestRulePerPartner} groups per PARTNER within one subject, so the
	 * subject side was never grouped at all: one clinician word resolves every route/formulation row a KB
	 * files a substance as (a candidate set is every ROW of each substance the word names, and the rows of
	 * one substance publish the same aliases), and each row that carried a rule about the same order
	 * raised its own chip. Measured live on the 3.7.1 standalone — Sarah Taylor, one diclofenac order,
	 * "Is it safe to give hydrocortisone?" — as
	 * {@code Hydrocortisone interacts with active order diclofenac} AND
	 * {@code Hydrocortisone (ophthalmic) interacts with active order diclofenac}, each carrying its own
	 * row's mechanism prose. So it was not a duplicate to drop but a choice to make, and both halves of
	 * the choice are decided elsewhere and deliberately: which rule row survives by
	 * {@link #outranks}, and what the chip calls the subject by {@link #interactionSubject}. The caller
	 * groups the rows ({@code substanceRows}) and hands them here at the group's first row, so a
	 * substance's chips keep the position they have always had.
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
	 * {@link #bestRulePerPartner} groups on — where the class arm names it by whatever
	 * {@link #orderPartners} resolves the order's codes to, which since issue #155 is the dataset's
	 * name for the substance, else the order's own display name, and only then the bare code. The two
	 * sentences of a folded chip can therefore still call one order two things (the rule's match token
	 * is a dataset alias, lower-cased by the {@code ddinter} parser). The class relationship follows as
	 * its own sentence, worded exactly as its standalone chip words it — see
	 * {@link #classRelationships}, where the two shortenings that seem obvious are recorded along with
	 * the issue #108 assertions each of them broke.
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
	 * dataset carries no entry for the active order's ATC code, the rule's own code is the only evidence
	 * left that the two arms are discussing one drug — and a substance is filed under several codes, so
	 * a rule carrying one of them was compared against a class hit found under another and the pair
	 * stayed uncorrelated. That is the bundled curated seed's ibuprofen-versus-aspirin shape: the seed
	 * carries no aspirin entry, its rule cites {@code B01AC06} and an aspirin order's class hit is under
	 * {@code N02BA01}, so both chips were emitted for one pair. Since issue #155 the codes of one ORDER
	 * are one partner ({@link #orderPartners}) and {@link #ruleAbout} is asked about all of them at
	 * once, which correlates that shape and folds it — the narrowing the per-order codes of issue #132
	 * made available, taken. What is left is a context carrying only the flattened union (issue #118's
	 * fallback), where nothing says which order contributed which code: there each code is its own
	 * partner again and the two chips stand. {@code ClassChipPartnerLabelTest} pins both halves.
	 */
	private void addInteractionWarnings(List<SafetyWarning> warnings, List<DrugReference> subjects,
			PatientClinicalContext context, int severityFloor, List<DrugReference> orderEntries,
			InteractionPairs pairs) {
		if (context == null) {
			return;
		}
		DrugReference ref = interactionSubject(subjects, recordedDrugNames(context));
		List<SubjectRule> rules = new ArrayList<SubjectRule>(
				bestRulePerPartner(subjects, context, severityFloor, orderEntries));
		// Which rule row carries which class sentence, decided before anything is emitted: the class
		// arm is walked per active-order CO-MEDICATION (issue #171 — it used to walk per CODE, so a
		// substance filed under several codes reached this loop once per code) while the chips are one
		// per rule ROW, and the two groupings are not the same partition.
		//
		// The class arm reads the CANONICAL row alone, not the whole group, and that is lossless only
		// while every row of a substance publishes the same ATC list — which the shipped KB does, across
		// all 129 of its multi-row families, and which is the same premise ContraindicationChips'
		// positional tie-break rests on. It is a DATA invariant, not a code-gated one: a KB refresh that
		// gave one route variant a code its siblings lack would silently drop a duplicate-therapy chip
		// this arm used to raise, so re-measure it on a refresh as well as before widening substanceKey.
		// (Re-measured for issue #164's widening: still 0 divergent, at 129 families rather than 121.)
		// Reading the group instead would produce one sentence per row, each naming its own label, which
		// is the duplication being removed.
		Map<SubjectRule, String> folded = new LinkedHashMap<SubjectRule, String>();
		List<String> classOnly = new ArrayList<String>();
		for (Map.Entry<OrderPartner, String> hit : classRelationships(ref, context).entrySet()) {
			SubjectRule rule = ruleAbout(hit.getKey().codes, rules);
			if (rule == null) {
				classOnly.add(hit.getValue());
			} else if (!folded.containsKey(rule)) {
				folded.put(rule, hit.getValue());
			}
			// else: a SECOND co-medication that the same rule is about. The relationship is already
			// stated on that chip; emitting it again, standalone or appended, would put one pair's
			// duplicate-therapy reasoning in front of a clinician twice.
			//
			// This is a narrower branch than it looks, and narrower than it was: keyed by ATC CODE it
			// fired for every extra code of one order, which is the duplication issue #171 removed by
			// keying on the co-medication instead. What is left needs two DISTINCT partners that
			// ruleAbout answers with one and the same rule. No fixture here reaches it — verified by
			// making the branch throw and running the suite — so it is a guard, and this comment
			// deliberately names no worked example rather than name one that turns out not to reach it.
		}
		// Rule chips first, then the class-only chips, which is the order the two arms produced them in
		// before they were coordinated — a folded chip therefore keeps the rule chip's position and no
		// client sees the chip sequence reshuffle.
		for (SubjectRule rule : rules) {
			warnings.add(interactionWarning(ref, rule.rule, folded.get(rule)));
			// Recorded as the pair it is, not as the string it renders, so the screening arm can recognise
			// it whatever either arm calls the substance — see InteractionPairs.
			pairs.add(ref, rule.partnerKey());
		}
		for (String detail : classOnly) {
			// No rating, and not an omission: a shared-ATC-subgroup or cross-reactivity join is a
			// relationship the reference data states without severity, which is why these chips are never
			// floor-filtered either. See SafetyWarning.getSeverity on why null is the correct value.
			warnings.add(new SafetyWarning(SafetyWarning.TYPE_INTERACTION, ref.displayLabel(), detail));
		}
	}

	/**
	 * The reference rows of {@code entries}, grouped by the substance each stands for
	 * ({@link DrugReference#substanceGroupKey()}), each group in first-appearance order.
	 *
	 * <p>The order WITHIN a group is the load-bearing one, because {@link #bestRulePerPartner}'s survivor
	 * rule falls back to "keep the incumbent", which is only "the dataset's first such row" while the
	 * group is in dataset order.
	 *
	 * <p>The order BETWEEN groups is not, and it is worth saying so rather than leaving a
	 * {@link LinkedHashMap} looking like a guarantee: nothing iterates this map. The caller walks
	 * {@code entries} itself and removes each group at its first row, so what keeps a substance's chips
	 * in the position that row's chips had is the CALLER's iteration — replacing this with a
	 * {@code HashMap} would change no output (measured: the whole api suite passes with one). Keyed
	 * insertion order is kept only so a debug dump of this map reads in dataset order. Move the emit
	 * site into an iteration of this map and that positional promise moves with it.
	 */
	/**
	 * The subject groups the drug-in-play and dose arms work over: {@link #substanceRows} over
	 * {@code inPlay}, each group additionally carrying the rows of that same substance the patient's own
	 * ACTIVE ORDERS resolved — i.e. every row of an in-play substance that THIS REQUEST resolved, from
	 * either side.
	 *
	 * <p><b>Issue #175.</b> The subject of an interaction chip is a SUBSTANCE (issue #162), but the rows
	 * this arm saw were only the ones the question and answer TEXT resolved — an accident of which alias
	 * the clinician or the model happened to use. Where the patient's own order name resolves MORE rows
	 * of that substance, the arm chose its rule from a strictly smaller candidate set than the screening
	 * arm ({@link #addActiveOrderPairInteractions}) would have, and since issue #173 the screen stands
	 * down from any pair this arm reported — so the milder rule survived and the more severe one was
	 * never reported. Over-report to under-report is the one direction this cluster's other fixes did not
	 * take, and it is the failure mode the whole feature exists to prevent (issue #86).
	 *
	 * <p>Reachable on shipped data, not only in principle. Measured 2026-08-09 over the standalone's
	 * 19 MB KB by calling {@link DrugReference#matchesText} and {@link DrugReference#matchesDrugName}
	 * through the built jar: of its 129 multi-row substances, 16 carry an alias that resolves a strict
	 * SUBSET of the family which an order name then widens, and in one of them the subset's best rule is
	 * strictly less severe — {@code Insulin human} against {@code fluticasone} and against
	 * {@code mometasone}, Minor deferred over Moderate. {@code OneSubstanceOneRuleTest} pins that very
	 * slice.
	 *
	 * <p>Only substances already in play gain rows: this map is keyed on the in-play groups and the
	 * caller drains it while walking {@code inPlay}, so an order the question says nothing about still
	 * raises nothing here (it is the screening arm's business). What the widening does change is that a
	 * partner whose rule sits only on an order-resolved row now reaches a chip from this arm too —
	 * the same chip the screen raises for the same pair, which is the point: the two arms must not
	 * choose from different row sets.
	 *
	 * <p>In-play rows come FIRST and order rows after, so a full tie on {@link #outranks} keeps a row
	 * the text actually named. It is still a per-{@code validate} local for issue #172's reason, and the
	 * lists are the ones {@link #substanceRows} just built, so appending to them mutates nothing shared.
	 */
	private static Map<Object, List<DrugReference>> resolvedSubstanceRows(
			Collection<DrugReference> inPlay, List<DrugReference> orderEntries) {
		Map<Object, List<DrugReference>> groups = substanceRows(inPlay);
		for (DrugReference ordered : orderEntries) {
			List<DrugReference> rows = groups.get(ordered.substanceGroupKey());
			// contains() is identity here — DrugReference defines no equals, and both sets are resolved
			// against DrugReferenceService's shared getAll() cache, so one row is one object.
			if (rows != null && !rows.contains(ordered)) {
				rows.add(ordered);
			}
		}
		return groups;
	}

	private static Map<Object, List<DrugReference>> substanceRows(Collection<DrugReference> entries) {
		Map<Object, List<DrugReference>> out = new LinkedHashMap<Object, List<DrugReference>>();
		for (DrugReference entry : entries) {
			Object key = entry.substanceGroupKey();
			List<DrugReference> rows = out.get(key);
			if (rows == null) {
				rows = new ArrayList<DrugReference>();
				out.put(key, rows);
			}
			rows.add(entry);
		}
		return out;
	}

	/**
	 * @return the row of one substance that its chips name: the row the patient's own record claims most
	 *         strongly ({@link DrugReference#nameMatchStrength}), and among rows tied on that —
	 *         including the common case where no recorded name matches any of them at all —
	 *         {@link DrugReference#canonicalRow}'s choice, i.e. the row carrying no route qualifier
	 *         wherever the loaded data has one.
	 *
	 *         <p><b>Issue #162, the second half</b>, and the half that is a correctness fix rather than a
	 *         de-duplication. The chips named the subject by whichever ROW produced them, so a question
	 *         about "hydrocortisone" reported {@code Hydrocortisone (ophthalmic) interacts with active
	 *         order diclofenac} — asserting an ophthalmic preparation the clinician never named and the
	 *         chart does not record.
	 *
	 *         <p><b>Issue #194.</b> {@link DrugReference#canonicalRow} answers "which row names this
	 *         substance", and where NO row of a family names a route it can only keep the earliest — so
	 *         a patient ordered one presentation was told about another. Live-measured on the 3.7.1
	 *         standalone: a {@code Botulinum toxin type A} order (the demo dictionary's concept 4259,
	 *         whose name the order carries verbatim) was subjected on
	 *         {@code Daxibotulinumtoxina (botulinum toxin type a)}, because both rows of that substance
	 *         name no route and the {@code Daxibotulinumtoxina} row is the dataset's first.
	 *
	 *         <p>So the chart decides first, and only then the fold. That order is the constraint issue
	 *         #187 settled and #192 re-measured — naming the row the CHART records is what makes a
	 *         finding truthful, and applying {@code canonicalRow} to a recorded name instead renames a
	 *         charted {@code Ketorolac (ophthalmic)} allergy. The ranking is
	 *         {@link DrugReference#nameMatchStrength}, the one #192 introduced for the allergen side,
	 *         rather than a second definition of "how strongly does this row claim that name".
	 *
	 *         <p>What it does NOT do is manufacture a preference where the record supports none. An
	 *         order contributes two names ({@link PatientClinicalContextBuilder}): its drug row's name,
	 *         which commonly carries a strength ({@code Aspirin 81mg}), and its CONCEPT's own name, which
	 *         commonly does not ({@code Botulinum toxin type A}) — and an order with no drug row
	 *         contributes only the second. So a row whose display name IS that concept name reaches
	 *         {@link DrugReference#NAME_IS_THE_DISPLAY_NAME} and wins, which is what moves the botulinum
	 *         case; where no recorded name is any row's name or alias every row scores
	 *         {@link DrugReference#NAME_TOKEN_INSIDE_A_NAME} and the fold decides exactly as before. That
	 *         second shape is what the route-variant and no-unqualified-row cases in
	 *         {@code InteractionRouteVariantTest} and {@code ScreeningSubjectLabelTest} supply — their
	 *         contexts carry the dosed form alone — which is why they are unchanged, and it is also the
	 *         residue: a deployment whose order names all carry strengths gains nothing here.
	 *
	 *         <p>Kept as a named method over the shared fold rather than inlined at its call sites,
	 *         because "what a chip calls its subject" is the decision issue #162 made, #174 site 3
	 *         extended to the screening arm and #194 anchored on the chart — the name is where that
	 *         decision is looked up.
	 *
	 * @param recordedNames the names the patient's own active orders carry
	 *        ({@link PatientClinicalContext#getActiveDrugNames()}); empty is normal and means the fold
	 *        alone decides
	 */
	private static DrugReference interactionSubject(List<DrugReference> subjects,
			Collection<String> recordedNames) {
		return DrugReference.canonicalRow(strongestClaimants(subjects, recordedNames));
	}

	/**
	 * @return the rows of {@code subjects} tied at the strongest claim any of {@code recordedNames}
	 *         gives them, in their original order — every row when none of them is named at all, since
	 *         {@link DrugReference#NAME_NO_MATCH} is then the shared maximum. Never empty for a
	 *         non-empty {@code subjects}, so {@link DrugReference#canonicalRow} keeps its
	 *         "null only for an empty group" contract.
	 */
	private static List<DrugReference> strongestClaimants(List<DrugReference> subjects,
			Collection<String> recordedNames) {
		List<DrugReference> strongest = new ArrayList<DrugReference>();
		int best = DrugReference.NAME_NO_MATCH;
		for (DrugReference row : subjects) {
			int claim = DrugReference.NAME_NO_MATCH;
			for (String recorded : recordedNames) {
				claim = Math.max(claim, row.nameMatchStrength(recorded));
			}
			if (claim > best) {
				best = claim;
				strongest.clear();
			}
			if (claim == best) {
				strongest.add(row);
			}
		}
		return strongest;
	}

	/** @return the names the patient's active orders carry, or an empty set with no context — the
	 *          evidence {@link #interactionSubject} anchors a substance's representative row on. */
	private static Collection<String> recordedDrugNames(PatientClinicalContext context) {
		return context == null ? Collections.<String> emptySet() : context.getActiveDrugNames();
	}

	/**
	 * One matched interaction rule together with the reference row that carries it and the partner it
	 * points at — the unit {@link #bestRulePerPartner} chooses between now that its candidates come from
	 * several rows of one substance rather than from one entry. The row is needed for the choice itself (a
	 * route-unspecified row's mechanism prose is the one that fits a subject named by the substance) and
	 * nowhere else: the chip is rendered from the group's canonical row, so which row supplied the winning
	 * RULE never changes what the chip calls the drug.
	 *
	 * <p>The partner is carried rather than re-resolved by each consumer, because
	 * {@link #activeOrderEntryFor} is a scan whose answer two arms and one ledger all have to agree on —
	 * {@link #bestRulePerPartner} groups on it, {@link #addActiveOrderPairInteractions} names and logs a
	 * pair by it, and {@link InteractionPairs} keys the cross-arm suppression on it. Three copies of the
	 * same scan is three chances to answer that question differently, which is the shape of every
	 * duplicate chip this class has had to fix.
	 */
	private static final class SubjectRule {

		private final DrugReference subject;

		private final DrugReference.Interaction rule;

		/** The active-order reference entry this rule names, or null when the dataset carries none for
		 *  that order — see {@link #activeOrderEntryFor}. */
		private final DrugReference partner;

		SubjectRule(DrugReference subject, DrugReference.Interaction rule, DrugReference partner) {
			this.subject = subject;
			this.rule = rule;
			this.partner = partner;
		}

		/** @return what identifies this rule's partner for grouping: the partner ENTRY where the dataset
		 *          resolves one, else the label the chip renders ({@link #partnerLabel}, case-folded).
		 *          The two key spaces cannot collide — a {@link DrugReference} defines no
		 *          {@code equals} and can never equal a {@link String}. */
		Object partnerKey() {
			return partner != null ? partner : partnerLabel(rule).toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * The (substance, partner) pairs an interaction chip has already been raised for in this pass, so the
	 * screening arm ({@link #addActiveOrderPairInteractions}) can stand down from a pair the drug-in-play
	 * arm ({@link #addInteractionWarnings}) already reported. The interaction counterpart of
	 * {@link ContraindicationChips}, and keyed the same way: on IDENTITY, never on rendered text.
	 *
	 * <p><b>Why not the chip's text.</b> It was, until this ledger: the screen seeded a set with every
	 * already-raised chip's {@code (type, drug, detail)} triple and dropped any candidate that repeated
	 * one. That recognises a repeat only while the two arms word one finding identically, and they do not:
	 * <ul>
	 *   <li>Since issue #162 the drug-in-play arm names its chip after the substance's CANONICAL row
	 *       while this arm names its own after whichever row {@link DrugReferenceService#findForActiveOrders}
	 *       returned first. For a family whose route-unspecified row is not its first row the two
	 *       therefore disagree, and one pair was chipped twice — {@code Chloroprocaine interacts with
	 *       active order lidocaine} beside {@code Chloroprocaine (ophthalmic) interacts with active order
	 *       lidocaine}, identical mechanism prose under two subject labels. Reproduced through the real
	 *       parser and the real {@code validate} by
	 *       {@code DrugSafetyInteractionScreeningTest.theScreenStandsDownFromAPairTheSubstanceArmReportedUnderItsCanonicalName};
	 *       the surviving per-row subject LABEL is issue #174's site 3, which this ledger does not fix and
	 *       no longer depends on.</li>
	 *   <li>A pair stated in the OTHER DIRECTION was never recognised at all — "B interacts with active
	 *       order A" is not the string "A interacts with active order B" — so whether the repeat was
	 *       suppressed came down to which side this arm reached the pair from, which is the order
	 *       {@link DrugReferenceService#findForActiveOrders} walks the chart's own order names in. This
	 *       ledger is unordered, so it does not. Pinned by that test's sibling,
	 *       {@code ...ReportedInTheOtherDirection}, over the same fixture with the two order names
	 *       transposed.</li>
	 *   <li>Issue #88's fold appends a class sentence to the very chip this arm would raise, so equality
	 *       alone let the pair back in under two wordings, one folded and one not. That needed a
	 *       second, prefix-matching test on top of the equality one — anchored on the folded sentence's
	 *       opening because {@code iron} and {@code iron dextran} are both real KB partner labels, so a
	 *       plain {@code startsWith} would suppress a chip about a different drug. Pair identity needs
	 *       neither test: the fold decorates a chip this same pair was recorded for.</li>
	 * </ul>
	 *
	 * <p><b>The key.</b> {@code {subject substance, partner}}, unordered. The subject side is
	 * {@link DrugReference#substanceGroupKey()} — the substance a row stands for, else the row itself —
	 * so the arms agree about a pair however many rows either of them saw it through, and an entry from a
	 * source publishing no substance name still keys on its own identity. The partner side is
	 * {@link SubjectRule#partnerKey()}, i.e. the very key {@link #bestRulePerPartner} groups on, mapped
	 * through the same {@code substanceGroupKey} where it is an entry; so "which partner is this rule
	 * about" is answered once for the grouping and the suppression alike.
	 *
	 * <p>Only the RULE chips are recorded. A class-only chip ({@link #classRelationships}) states a
	 * different fact in different words, and the screening arm raises no such chip, so there is nothing
	 * for it to repeat. (There used to be a second reason — the class arm could not key this ledger,
	 * having only an ATC code where this wants an entry. Issue #171 gave it
	 * {@link DrugReference#substanceGroupKey()}, so that reason is gone and only the first is
	 * load-bearing.) The arm's own
	 * doubling — one pair reached from each of its two orders — is a different question with its own key,
	 * inside the arm.
	 */
	private static final class InteractionPairs {

		/** Insertion-ordered only so a debug dump reads in the order the chips were raised. */
		private final Set<List<Object>> reported = new LinkedHashSet<List<Object>>();

		/** Record that a chip has been raised for {@code subject} against {@code partner}. */
		void add(DrugReference subject, Object partner) {
			reported.add(Arrays.asList(substance(subject), substance(partner)));
		}

		/** @return true when a chip has already been raised for this pair, in EITHER direction. */
		boolean alreadyReported(DrugReference subject, Object partner) {
			Object one = substance(subject);
			Object other = substance(partner);
			// Both orderings rather than a canonical one: the two sides are heterogeneous — a substance
			// key, a DrugReference or a partner label — so there is no ordering to canonicalize them by.
			return reported.contains(Arrays.asList(one, other))
					|| reported.contains(Arrays.asList(other, one));
		}

		/** @return the substance a reference row stands for; anything else (a partner label) unchanged. */
		private static Object substance(Object side) {
			return side instanceof DrugReference ? ((DrugReference) side).substanceGroupKey() : side;
		}
	}

	/**
	 * @return the rule among {@code rules} that is about the very co-medication the active-order ATC
	 *         codes {@code orderCodes} identify — so the class arm's finding about that order folds into
	 *         its chip (issue #88) — or null when no rule is. A code SET rather than one code because
	 *         the class arm now decides per co-medication (issue #171), and a substance filed under
	 *         five codes carries a rule under only one of them; sorted, so which code answers first is
	 *         not a dataset's iteration order.
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
	 *         <p>{@code rules} holds one row per partner — per ENTRY where the dataset identifies one
	 *         and per label where it cannot (see {@link #bestRulePerPartner}) — so two rows can still
	 *         both name one order: across the full KB exactly one such pair exists, {@code enalapril}
	 *         and {@code enalaprilat}, two genuinely different entries which that grouping deliberately
	 *         keeps as two chips. The first in dataset order takes the fold; the other keeps its rule chip
	 *         unfolded, which is the conservative direction, since the alternative is stating one
	 *         duplicate-therapy relationship twice.
	 */
	private SubjectRule ruleAbout(Set<String> orderCodes, List<SubjectRule> rules) {
		for (String orderCode : new TreeSet<String>(orderCodes)) {
			DrugReference orderEntry = entryForAtcCode(orderCode);
			for (SubjectRule rule : rules) {
				if (orderCode.equals(DrugReference.normalizeAtcToken(rule.rule.getAtc()))) {
					return rule;
				}
				if (orderEntry != null && identifies(rule.rule, orderEntry)) {
					return rule;
				}
			}
		}
		return null;
	}

	/**
	 * The partner label a chip names for {@code interaction} — its match token, else its ATC code.
	 *
	 * <p>One definition, because the chip detail renders it and {@link #bestRulePerPartner} groups on it
	 * wherever the dataset identifies no partner entry: on that branch the grouping is only correct
	 * while the key IS the label the chip says, and two copies of the same coalesce could drift into
	 * grouping rules by something a clinician never sees.
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
	 * <p>Shared with {@link DrugReferenceInjector#orderedInteractionNotes} since issue #174, which
	 * coalesced its own copy untrimmed (it trimmed the assembled {@code label (note)} piece, not the
	 * label), so a padded curated token reached the citable record as {@code "warfarin   (Major. …)"}
	 * beside a chip reading {@code "active order warfarin"}. That method now groups its notes by
	 * partner exactly as {@link #bestRulePerPartner} groups its chips, so the two must agree on what
	 * the partner is CALLED as well as on which of them is which — one method rather than two
	 * coalesces, for the same reason the grouping key and the rendered label have to be one string.
	 *
	 * @return the label, or null when the rule carries neither — which a rule that matched an active
	 *         order cannot ({@code hasActiveDrug} needs a non-blank token or a non-blank ATC), so
	 *         callers inside the matched loop never see it
	 */
	static String partnerLabel(DrugReference.Interaction interaction) {
		String label = ChartSearchAiUtils.firstNonBlank(interaction.getToken(), interaction.getAtc());
		return label == null ? null : label.trim();
	}

	/**
	 * The matched interaction rules of {@code subjects} — all the reference rows of ONE substance
	 * (issue #162), or a single row for the screening arm's overload — worth chipping: at most one per
	 * partner, in dataset order of first appearance, each paired with the row that carries it.
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
	 * <p><b>What is grouped.</b> The partner DRUG when the loaded dataset identifies one — the
	 * active-order entry the rule {@link #identifies} — else the label the chip renders
	 * ({@link #partnerLabel}, case-folded). Either way rules that would produce the same
	 * "interacts with active order X" subject collapse while rules naming different partners each
	 * keep their chip — even when their notes are identical strings. Grouping is per SUBSTANCE
	 * and per arm: two different substances in play still chip separately about the same order, and this
	 * decides only WHICH RULE ROW survives for a partner, never how many arms describe that partner.
	 * The rule-plus-class double chip of issue #88 is the separate, cross-arm question, answered
	 * downstream of this method by {@link #addInteractionWarnings} — which folds the class arm's
	 * finding into the row chosen here, so the two collapses compose in one direction instead of
	 * competing.
	 *
	 * <p>Since issue #162 the candidates for one partner come from several rows, not one — the subject's
	 * whole substance — and the two rows of a substance resolve the SAME partner key: they carry the same
	 * match token (their shared {@code rxnorm_name}), and {@link #activeOrderEntryFor} returns the first
	 * order entry that token names. So no extra key work is needed on the subject side; what needed
	 * deciding is which row wins, below.
	 *
	 * <p>The label alone was the key until issue #136, and it was a sound proxy for "one partner" only
	 * while a rule could reach an order by ONE spelling. The reference-name arm of
	 * {@link PatientClinicalContext#hasActiveDrug} matches every name the dataset gives a drug the
	 * patient is on, so two rules whose tokens are two names of the SAME partner both match and the
	 * label key kept them apart: reproduced through the real validator, a patient on
	 * {@code Warfarin 5mg} against an entry carrying both a {@code coumadin} row and a {@code warfarin}
	 * row got two Major chips for one pair. Two DISTINCT entries still get two chips even when one order
	 * name resolves both, which is what leaves the {@code enalapril}/{@code enalaprilat} decision below
	 * intact — the key is the entry {@link #identifies} names, so it collapses two rules only when the
	 * dataset itself says they name one drug, with the same alias-collision limit {@link #pairKeyNames}
	 * records for the question side (an entry whose alias list claims another drug's name). Where the
	 * dataset carries no entry for the order's substance the label is still the key, and there #121's
	 * invariant holds unchanged: the key IS what the chip says.
	 *
	 * <p><b>Which row wins.</b> The most severe rating, then — since issue #162 — the row naming no
	 * route, then the longer note; longer in prose, not in whitespace, see {@link #noteLength}. The full
	 * ordering and the measurement behind the middle step live on {@link #outranks}. Route variants
	 * genuinely differ — topical dexamethasone does
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
	 * — so a tie on severity keeps the fuller note. Equal on all THREE keys — severity, then the route
	 * step issue #162 inserted between them, then the note — keeps the incumbent, so a group's chip is
	 * the dataset's first row among those the earlier keys could not separate.
	 *
	 * <p><b>Two corners this rule accepts.</b> A row with no note at all still wins its group on
	 * severity alone, so an operator's token-only unrated rule beats a rated row carrying a mechanism
	 * paragraph and the chip then gives no reason — reachable only in hand-authored data, since every
	 * DDInter row has a note, and left as it is because the alternative (a note outranking a rating)
	 * would drop the operator's own rule, which is the thing {@link #severityPriority} exists to
	 * protect. And two tokens naming two DIFFERENT entries stay two chips even when one order name
	 * matches both: across the full KB exactly one such pair exists — {@code enalapril} and
	 * {@code enalaprilat}, which 376 entries carry as separate partners, and which a single order
	 * named "Enalaprilat 1.25 mg" matches through the order-name matcher's inflection tolerance.
	 * Prodrug and active metabolite are genuinely different DDInter entries, so that pair is reported
	 * rather than merged.
	 *
	 * @param orderEntries the reference entries the patient's active orders resolve to
	 *        ({@link DrugReferenceService#findForActiveOrders}), from which
	 *        {@link #activeOrderEntryFor} identifies the partner drug a rule points at; an empty list
	 *        falls the grouping back to the label alone
	 */
	private static Collection<SubjectRule> bestRulePerPartner(List<DrugReference> subjects,
			PatientClinicalContext context, int severityFloor, List<DrugReference> orderEntries) {
		// Keys are either the partner DrugReference (object identity — the class defines no equals, and
		// a DrugReference can never equal a String) or the lowercased partner label, so the two key
		// spaces cannot collide.
		Map<Object, SubjectRule> best = new LinkedHashMap<Object, SubjectRule>();
		for (DrugReference ref : subjects) {
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
				// The partner DRUG when the dataset identifies one, else the label — resolved ONCE, onto
				// the candidate, so the screening arm and the cross-arm ledger read the same answer rather
				// than each scanning for it (see SubjectRule). Two rows of ONE subject substance resolve the
				// same partner entry here: they carry the same match token (their shared rxnorm_name), and
				// activeOrderEntryFor returns the first order entry that token names, so the group is one
				// key rather than one per row.
				SubjectRule candidate = new SubjectRule(ref, i,
						activeOrderEntryFor(orderEntries, ref, i));
				Object key = candidate.partnerKey();
				SubjectRule incumbent = best.get(key);
				if (incumbent == null || outranks(candidate, incumbent)) {
					// LinkedHashMap keeps a re-put key in its original position, so replacing a group's
					// winner does not reorder the chips.
					best.put(key, candidate);
				}
			}
		}
		return best.values();
	}

	/**
	 * @return true when {@code candidate} is the row worth chipping for a partner {@code incumbent}
	 *         already covers — a more severe rating; failing that, the row that names no route; failing
	 *         that, a longer note. See {@link #bestRulePerPartner} for the first and third, which predate
	 *         issue #162 and are unchanged.
	 *
	 *         <p><b>The route step, and why it sits BETWEEN the other two (issue #162).</b> Once the
	 *         candidates come from several rows of one substance, the winning row decides which MECHANISM
	 *         PROSE a clinician reads under a chip that names the substance — and a route-qualified row's
	 *         prose describes a presentation nobody named ("Concomitant use of ophthalmic nonsteroidal
	 *         anti-inflammatory drugs and ophthalmic steroids …" for a systemic order). The note length
	 *         cannot be what decides that, and the shipped KB carries hundreds of (substance, partner)
	 *         pairs where it would decide it wrongly: {@code Ketorolac (ophthalmic)} against lepirudin
	 *         carries a 495-character note against plain {@code Ketorolac}'s 265, so
	 *         severity-then-longest-note alone hands that chip the eye-drop prose. Pinned by
	 *         {@code InteractionRouteVariantTest.theSurvivingChipIsNotDecidedByWhichNoteIsLonger} over
	 *         those very rows, rather than by an exact count of the pairs sharing the shape — two
	 *         independent measurements over the KB disagreed about that count while agreeing about these
	 *         rows and about the order of magnitude, so the rows are what this records.
	 *
	 *         <p>It sits below severity, not above it, and that is the deliberate residue: some
	 *         route-qualified rows are STRICTLY more severe than their substance's unqualified row
	 *         ({@code Sirolimus (protein-bound)} Major against plain {@code Sirolimus} Moderate, against
	 *         lapatinib), and preferring the route there would report the milder rating for a pair the
	 *         source rates worse. Under-warning is the one direction a non-blocking advisory the
	 *         clinician adjudicates must not take — the same call {@link #bestRulePerPartner} already
	 *         records for the partner side — so severity leads, and those pairs keep a chip whose prose
	 *         describes the qualified presentation. Pinned by
	 *         {@code InteractionRouteVariantTest.severityStillOutranksTheRoutePreference}.
	 */
	private static boolean outranks(SubjectRule candidate, SubjectRule incumbent) {
		return outranks(candidate.subject, candidate.rule, incumbent.subject, incumbent.rule);
	}

	/**
	 * {@link #outranks(SubjectRule, SubjectRule)} over a bare (row, rule) pair, for the arm whose
	 * candidates are not {@link SubjectRule}s: the question-PAIR arm
	 * ({@link #collectQuestionPairInteraction}), which resolves no active-order partner and so builds
	 * none. Shared rather than re-derived there, because issue #189 is precisely that the three arms
	 * ranked one substance's rows by three different rules — this arm by "whichever entry pair the walk
	 * reached first". The full ordering and its measurements live on
	 * {@link #outranks(SubjectRule, SubjectRule)} and {@link #bestRulePerPartner}; nothing about them is
	 * restated here.
	 */
	private static boolean outranks(DrugReference candidateRow, DrugReference.Interaction candidate,
			DrugReference incumbentRow, DrugReference.Interaction incumbent) {
		int candidateSeverity = severityPriority(candidate.getSeverity());
		int incumbentSeverity = severityPriority(incumbent.getSeverity());
		if (candidateSeverity != incumbentSeverity) {
			return candidateSeverity > incumbentSeverity;
		}
		if (candidateRow.namesNoRoute() != incumbentRow.namesNoRoute()) {
			return candidateRow.namesNoRoute();
		}
		// Severity is equal by here, so this falls through to the note-length step.
		return outranksOnRule(candidate, incumbent);
	}

	/**
	 * The rule-side half of {@link #outranks} — most severe, then the longer note — for a collapse
	 * whose candidates all come from ONE subject row and so cannot differ on the route step between
	 * them. That is {@link DrugReferenceInjector#orderedInteractionNotes} (issue #174 site 2), which
	 * collapses one entry's several rows about a partner into the note it renders.
	 *
	 * <p>Shared rather than re-derived there, because the record and the chip describe the same pair
	 * to the same clinician: with two definitions, a record could name the Moderate mechanism beside
	 * a chip reporting Major — a severity the deterministic layer deliberately discarded, arriving in
	 * the prompt through the more quotable half. Both rationales for the two steps live on
	 * {@link #bestRulePerPartner} and {@link #outranks}; nothing about them is restated here.
	 */
	static boolean outranksOnRule(DrugReference.Interaction candidate,
			DrugReference.Interaction incumbent) {
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
	 * <p>{@link DrugReferenceInjector#orderedInteractionNotes} is deliberately NOT extended to match.
	 * Its "a partner that raises a chip is exactly a partner promoted here" does not hold across the
	 * whole chip set, since a pair chip's partner is promoted nowhere; that sentence now says so
	 * itself, scoped to the drug-in-play arm, which is the rewording this paragraph used to defer to
	 * whichever PR owned that method next (issue #174 site 2 was it). What is NOT affected is the
	 * invariant the sentence exists to protect, that a chip and the prose cannot describe the same
	 * finding differently: since issue #110 the deterministic finding is itself injected as a
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
		// And what the sentence CALLS each side: the substance's representative row, never the row this
		// walk reached (issue #174, the fifth site of the same shape — see canonicalSubjects). Anchored
		// on the patient's own order names like the other two arms (issue #194), which for this arm
		// usually resolves nothing — a question-named pair need not be on the chart at all — and then
		// falls back to canonicalRow exactly as before.
		Map<DrugReference, DrugReference> subjects = canonicalSubjects(drugs,
				recordedDrugNames(context));
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
						subjects, context, severityFloor);
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

	/**
	 * One candidate pair chip, with the source-assigned rating that orders it and the (row, rule) it was
	 * built from — which a LATER entry pair of the same clinical pair is compared against, so that the
	 * survivor is the best rule rather than the first one reached (issue #189).
	 */
	private static final class PairFinding {

		final SafetyWarning warning;

		final String severity;

		/** The reference row that carried {@link #rule} — the {@link #outranks} route step reads it. */
		final DrugReference row;

		final DrugReference.Interaction rule;

		PairFinding(SafetyWarning warning, String severity, DrugReference row,
				DrugReference.Interaction rule) {
			this.warning = warning;
			this.severity = severity;
			this.row = row;
			this.rule = rule;
		}
	}

	/** One question-named pair: at most one candidate chip, from whichever side carries the rule. */
	private void collectQuestionPairInteraction(Map<List<String>, PairFinding> candidates,
			Set<List<String>> chartOwned, DrugReference first, DrugReference second,
			Map<DrugReference, String> names, Map<DrugReference, DrugReference> subjects,
			PatientClinicalContext context, int severityFloor) {
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
		// Whichever side carries the rule owns the sentence; with symmetric data both do, and the tie
		// goes to whichever entry the DATASET lists first — not whichever the question names first,
		// because questionDrugs comes from findImpliedByQuery, which filters a walk of getAll() in place
		// and so returns dataset order. Measured on the 3.7.1 standalone: "does voxelotor interact with
		// dexamethasone?" and "can dexamethasone be given with voxelotor?" both chip with Voxelotor as
		// the subject, its entry sitting at index 1055 against dexamethasone's 1744. Stable either way,
		// which is what the chip needs; a rule that followed the question's word order would need the
		// drug's offset in the question, which neither the prose scan nor the ranking above it reports.
		boolean fromFirst = !forward.isEmpty();
		// The ROW decides which side owns the sentence and which rule it carries; the SUBSTANCE
		// decides what the sentence calls the two drugs (issue #174). Naming the row asserted a
		// preparation the question never mentioned — "Lidocaine interacts with Chloroprocaine
		// (ophthalmic), also named in the question" for a question that said "chloroprocaine" — the
		// same defect issue #162 fixed on the drug-in-play arm, reached here through the dataset
		// order the tie-break above deliberately settles on.
		DrugReference subject = subjects.get(fromFirst ? first : second);
		DrugReference partner = subjects.get(fromFirst ? second : first);
		DrugReference row = fromFirst ? first : second;
		// Issue #189, within this entry pair: several rules can join one pair of entries — a brand-name
		// row and an INN row — and the first of them was quoted. Ranked by the shared rule ordering, so
		// this arm cannot quote a milder rule than the pair's own data carries.
		DrugReference.Interaction rule = bestRule(fromFirst ? forward : reverse);
		// And issue #189 ACROSS the entry pairs of one clinical pair: route variants make one clinical
		// pair arrive as several entry pairs carrying different rules (Lapatinib rates Sirolimus Moderate
		// and Sirolimus (protein-bound) Major), and the first pair to arrive kept the sentence — so this
		// arm quoted the milder of two ratings its own KB publishes for the pair the question asked about.
		// Compared through the SAME ordering the chip arms use, so no arm ranks one pair's rows
		// differently from another; a tie keeps the incumbent, which leaves the dataset-order tie-break
		// above as exactly that, a tie-break. LinkedHashMap keeps a re-put key in its original position,
		// so replacing a candidate does not reorder the chips.
		PairFinding incumbent = candidates.get(pairKey);
		if (incumbent != null && !outranks(row, rule, incumbent.row, incumbent.rule)) {
			return;
		}
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
		// The chip carries the rating it is ORDERED on (issue #207), so the ordering is observable
		// without reading it back out of the prose above.
		candidates.put(pairKey, new PairFinding(new SafetyWarning(SafetyWarning.TYPE_INTERACTION,
				subject.displayLabel(), detail, rule.getSeverity()), rule.getSeverity(), row, rule));
	}

	/**
	 * @return the rule worth quoting among the several that can join ONE pair of reference entries —
	 *         {@link #outranksOnRule}'s choice, i.e. the most severe and then the fuller note. The route
	 *         step of {@link #outranks} cannot separate these: they all sit on the same row.
	 *         {@code rules} is never empty at the one call site, which has already returned when both
	 *         directions are.
	 */
	private static DrugReference.Interaction bestRule(List<DrugReference.Interaction> rules) {
		DrugReference.Interaction best = rules.get(0);
		for (DrugReference.Interaction rule : rules) {
			if (outranksOnRule(rule, best)) {
				best = rule;
			}
		}
		return best;
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

	/** @return true when {@code token} is, case-folded, one of {@code other}'s own aliases. Through
	 *          {@link DrugReference#isNamed}, which is where that rule now lives so that the
	 *          reference-name arm of {@link PatientClinicalContext#hasActiveDrug} (issue #136) asks it
	 *          identically — the two decide the same question from opposite sides, and a second copy
	 *          could drift into answering it differently. */
	private static boolean namesEntry(String token, DrugReference other) {
		return other.isNamed(token);
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
	 * match on — so ONE word in the question resolves several entries (a candidate set is every ROW of
	 * each substance the word names) that all pair off the same rule: four dexamethasone entries
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
	 * first is kept — no longer the same rule as the chart arm's, which since issue #162 prefers the row
	 * naming no route before falling back to its first.
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
		// partnerLabel, not a second coalesce: it is the label bestRulePerPartner GROUPS on where the
		// dataset identifies no partner entry, and there #121's grouping is only correct while the key
		// IS the label the chip says.
		String detail = ref.displayLabel() + " interacts with active order " + partnerLabel(i);
		if (i.getNote() != null && !i.getNote().isEmpty()) {
			detail += " — " + i.getNote();
		}
		if (alsoSameClass != null) {
			detail = endSentence(detail) + " " + alsoSameClass;
		}
		// The rule's own rating travels with the chip (issue #207). Null for a curated hand-authored
		// rule, which is unrated by design — see SafetyWarning.getSeverity, and note that a FOLDED chip
		// still reports the RULE's rating: the class sentence appended to it carries none, so folding
		// cannot lower or raise what the pair is rated.
		return new SafetyWarning(SafetyWarning.TYPE_INTERACTION, ref.displayLabel(), detail,
				i.getSeverity());
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
	 *       #86 settles on. The guard has to cover every leg of that join, not the order-name one alone
	 *       — the ATC leg needs the co-formulation case as well, where ONE order resolves to several
	 *       entries and the order's own code would otherwise witness a pair between them, and since
	 *       issue #136 the reference-NAME leg needs the same reduction, which is the purest form of the
	 *       self-witness: the subject's own entry naming itself.</li>
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
	 *       keys, and one pair reached from both sides is one {@code ref} each time. Separately from this
	 *       arm's own key, nothing is reported for a pair a drug-in-play chip already covers, whichever
	 *       row either arm named the substance after — see {@link InteractionPairs}.</li>
	 *   <li><b>Blast radius.</b> Candidates grow quadratically with the medication list, so they are
	 *       ordered most-severe-first and cut at {@link #maxPairChips()}, with every withheld pair
	 *       logged.</li>
	 * </ul>
	 */
	private void addActiveOrderPairInteractions(List<SafetyWarning> warnings,
			PatientClinicalContext context, int severityFloor, List<DrugReference> orderDrugs,
			InteractionPairs reportedPairs) {
		if (context == null) {
			return;
		}
		List<ScreenedPair> pairs = new ArrayList<ScreenedPair>();
		Set<List<String>> seenPairs = new LinkedHashSet<List<String>>();
		// Keyed by the reference data's own name for each drug, not by entry, and resolved once per
		// drug — issue #115's shape reaches the subjects here exactly as it reaches the question drugs
		// in the pair arm, because one order name resolves every route variant sharing an
		// {@code rxnorm_name} and each variant would otherwise be its own subject keying its own pair.
		Map<DrugReference, String> keyNames = pairKeyNames(orderDrugs, severityFloor);
		// What each row's chip CALLS its subject: the substance's representative row, never the row the
		// loop happens to be on (issue #174 site 3), anchored on the patient's own order names (#194).
		// See canonicalSubjects.
		Map<DrugReference, DrugReference> subjects = canonicalSubjects(orderDrugs,
				recordedDrugNames(context));
		// The subject is a SUBSTANCE here too (issue #189). This arm asked bestRulePerPartner about ONE
		// row at a time and let its own pair key drop the siblings, so the rule a pair's chip quoted was
		// whichever row findForActiveOrders returned first — while the drug-in-play arm, which has seen
		// the whole group since issue #162, chose the most severe. One patient's two questions therefore
		// quoted two different mechanism paragraphs for one pair (measured live on Salicylic acid), and
		// on a family whose first row is the milder one this arm reported the milder RATING: measured on
		// the shipped KB, Lapatinib rates Sirolimus Moderate and Sirolimus (protein-bound) Major, and
		// Sirolimus is the dataset's first row.
		//
		// remove(), so a substance's rows are handed to the arm ONCE — at the first of them, keeping the
		// position that row's chips have always had — and the map itself is the already-done ledger. The
		// same idiom validate() uses for the drug-in-play and dose arms.
		Map<Object, List<DrugReference>> substances = substanceRows(orderDrugs);
		for (DrugReference ref : orderDrugs) {
			List<DrugReference> substance = substances.remove(ref.substanceGroupKey());
			if (substance == null) {
				continue;
			}
			// Resolved once per subject SUBSTANCE, not per rule, and reduced by the whole group: with the
			// group as subject, an order any of its rows resolves from is the subject's own order, so a
			// sibling row may not witness the subject's pair either (see activeOrdersOtherThan).
			PatientClinicalContext others = activeOrdersOtherThan(substance, orderDrugs, context);
			// bestRulePerPartner applies the severity floor and the hasActiveDrug join and returns at
			// most ONE rule per partner label, most severe first (#121) — the same grouping, the same
			// predicate and now the same subject unit the drug-in-play arm gets, asked of the OTHER
			// orders instead of all of them. Reusing it is what stops this arm keeping a route variant's
			// Moderate row for a pair whose Major row the other arm reports, and what keeps the cap
			// below sorting on the severity a clinician would actually be shown.
			for (SubjectRule matched : bestRulePerPartner(substance, others, severityFloor, orderDrugs)) {
				DrugReference.Interaction i = matched.rule;
				// The partner is an active order too, so it was looked up among the order entries rather
				// than across the whole dataset, and it is carried on the matched rule rather than
				// re-resolved here (see SubjectRule). Null when that order carries no reference entry of
				// its own (a substance the dataset does not cover, matched by name): the finding still
				// stands — the join above is what decides that — and the pair simply keys on the rule's
				// own label, which no reverse direction can produce.
				DrugReference partner = matched.partner;
				String partnerName = partner != null ? keyNames.get(partner)
						: partnerLabel(i).toLowerCase(Locale.ROOT);
				// keyNames.get(ref) is the GROUP's key name, ref being the row the group was handed at.
				// One name per substance rather than one per row, which is what pairKeyNames' token key
				// already gives wherever another drug's rule names the substance at all — and where none
				// does, its displayLabel fallback differs per ROW, so before the grouping above two rows
				// of one substance could key two pairs and chip twice. Now one substance keys once.
				if (!seenPairs.add(unorderedPairKey(keyNames.get(ref), partnerName))) {
					continue;
				}
				// The screen's gate reads the QUESTION alone (see the call site — the pre-answer findings
				// pass and the post-answer chips pass must gate identically), so a drug the ANSWER named
				// can be in play beside it, and then addInteractionWarnings has already run this very rule
				// over these very orders: measured, an answer naming a subject the screen also reaches put
				// the identical "X interacts with active order Y — Major" line in safetyWarnings TWICE.
				// Asked of the pair rather than of the rendered chip, so the two arms cannot disagree by
				// wording — see InteractionPairs. After the pair key above, so the pair is marked seen
				// either way and the reverse direction cannot re-report it.
				if (reportedPairs.alreadyReported(ref, matched.partnerKey())) {
					continue;
				}
				// The SUBSTANCE, not the row this iteration is on — and the same substance the log
				// label below names, so a withheld pair is recoverable under the name the chip would
				// have carried.
				DrugReference subject = subjects.get(ref);
				DrugReference partnerSubject = partner != null ? subjects.get(partner) : null;
				pairs.add(new ScreenedPair(interactionWarning(subject, i),
						severityPriority(i.getSeverity()),
						subject.displayLabel() + " x "
								+ (partnerSubject != null ? partnerSubject.displayLabel()
										: partnerLabel(i))
								+ " (" + ChartSearchAiUtils.firstNonBlank(i.getSeverity(), "unrated")
								+ ")"));
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
	 * @return for each of {@code rows}, the row that names the SUBSTANCE it is a row of —
	 *         {@link #interactionSubject}'s choice over the whole group, so every arm calls a
	 *         substance what the drug-in-play arm calls it.
	 *
	 *         <p><b>Issue #174.</b> Two arms still named a drug by whichever ROW they reached, while
	 *         the drug-in-play arm has named the canonical row since issue #162:
	 *         <ul>
	 *           <li><b>site 3</b>, the screening arm ({@link #addActiveOrderPairInteractions}), whose
	 *               subject was whichever row {@link DrugReferenceService#findForActiveOrders}
	 *               returned first. Confirmed live: one patient's chip read {@code Salicylic acid} for
	 *               a question naming the drug and {@code Salicylic acid (sodium)} for a screening
	 *               question — two names for one substance in one build;</li>
	 *           <li>and the question-PAIR arm ({@link #addQuestionPairInteractions}), a FIFTH site the
	 *               issue does not enumerate, whose two sides were both named by the row the walk
	 *               reached — settled, as its own javadoc records, by "whichever entry the DATASET
	 *               lists first".</li>
	 *         </ul>
	 *         Both are observable only for the 7 shipped families whose route-unspecified row is not
	 *         their first, which is why they survived several passes.
	 *
	 *         <p>Label-only, and deliberately so. Both arms already collapse the rows into one chip on
	 *         their own unordered pair keys, and issue #173's {@link InteractionPairs} already
	 *         suppresses a pair another arm reported — that ledger is keyed on identity precisely so
	 *         it did NOT depend on these labels being fixed. Which ROW's rule survives is the separate
	 *         axis that issue #189 closed: both arms took the first row to reach the pair rather than
	 *         the most severe rule, and both now hand the whole row group to
	 *         {@link #bestRulePerPartner} / {@link #outranks} instead.
	 *
	 *         <p>A per-{@code validate} local map, never a field, for the reason issue #172 records:
	 *         a memoised {@link DrugReference} outliving a {@code getAll()} hot-reload fails the
	 *         reference comparisons the contraindication arms make against the same objects.
	 *
	 * @param recordedNames passed through to {@link #interactionSubject}, so the screening arm anchors a
	 *        substance's representative row on the patient's own order names exactly as the drug-in-play
	 *        arm does (issue #194) — the two must not name one substance two ways in one build
	 */
	private static Map<DrugReference, DrugReference> canonicalSubjects(List<DrugReference> rows,
			Collection<String> recordedNames) {
		Map<DrugReference, DrugReference> out = new LinkedHashMap<DrugReference, DrugReference>();
		for (Map.Entry<Object, List<DrugReference>> group : substanceRows(rows).entrySet()) {
			DrugReference canonical = interactionSubject(group.getValue(), recordedNames);
			for (DrugReference row : group.getValue()) {
				out.put(row, canonical);
			}
		}
		return out;
	}

	/**
	 * @return the patient's active-order state with everything {@code subjectRows} themselves answer for
	 *         removed — the ORDERS any of those rows resolve from, whole (their names AND their ATC
	 *         codes), plus the rows' own codes — so {@link PatientClinicalContext#hasActiveDrug} against
	 *         it can only be satisfied by a DIFFERENT order. A derived context rather than a second
	 *         matching rule: the predicate stays the one the question-driven arm uses.
	 *
	 *         <p><b>The whole substance, not one row (issue #189).</b> Since the screening arm's subject
	 *         is a substance rather than a row, the reduction has to be too: reduced against one row
	 *         only, a SIBLING row's order would remain in this context and could witness the subject's
	 *         own pair — the self-witness this method exists to prevent, arriving through the route
	 *         variant instead of through the order name. Every "{@code ref}" test below is therefore
	 *         asked of the group: an order is the subject's own when ANY of its rows resolves from it,
	 *         and the codes removed are the union of the group's.
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
	 *
	 *         <p>The reference-NAME leg of the join (issue #136) is reduced the same way and from the
	 *         same evidence: an entry's names are carried over only when that entry resolves from an
	 *         order this reduction kept, so the arm can reach a partner by its reference name exactly
	 *         when a DIFFERENT order supplies it. Left un-reduced it would be the self-witness this
	 *         method exists to prevent, in its purest form — the subject's own entry naming itself. The
	 *         flattened fallback contributes none, for the same reason it has to clear the names: with
	 *         no order identity there is nothing to attribute an entry to.
	 */
	private static PatientClinicalContext activeOrdersOtherThan(List<DrugReference> subjectRows,
			List<DrugReference> orderDrugs, PatientClinicalContext context) {
		Set<String> names = new LinkedHashSet<String>();
		Set<String> referenceNames = new LinkedHashSet<String>();
		Set<String> codes = new LinkedHashSet<String>(context.getActiveDrugAtcCodes());
		// Read on both branches, so it is resolved once here rather than at each use (it allocates).
		Set<String> refCodes = new LinkedHashSet<String>();
		for (DrugReference row : subjectRows) {
			refCodes.addAll(row.normalizedAtcCodes());
		}
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
				if (!resolvesFromAny(subjectRows, order)) {
					names.addAll(order.getNames());
					otherCodes.addAll(order.getAtcCodes());
					// And the reference names of the entries THIS order resolves to, so the #136 leg of
					// the join is witnessed by this order too — never by the subject's own rows, which is
					// why it is collected here rather than taken whole off the context.
					for (DrugReference coResolved : orderDrugs) {
						if (!subjectRows.contains(coResolved) && resolvesFrom(coResolved, order)) {
							referenceNames.addAll(coResolved.getAliases());
						}
					}
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
					if (!subjectRows.contains(coResolved) && resolvesFrom(coResolved, order)) {
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
			return new PatientClinicalContext(null, null, names, codes, null, null)
					.withActiveDrugReferenceNames(referenceNames);
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
			// Through the drug-NAME matcher, the same one findForActiveOrders used to choose the
			// subjects (issue #147): asking a narrower question here than the one that made the subject
			// a subject would leave its own order unattributed and let it witness its own pair.
			if (!matchesDrugNameAny(subjectRows, name)) {
				names.add(name);
				continue;
			}
			ownOrderNamed = true;
			for (DrugReference coResolved : orderDrugs) {
				if (!subjectRows.contains(coResolved) && coResolved.matchesDrugName(name)) {
					codes.removeAll(coResolved.normalizedAtcCodes());
				}
			}
		}
		if (!ownOrderNamed) {
			// No name resolved to any of the subject's rows, so the subject reached the subject set
			// through its ATC code alone — and its own order name is therefore still in this set,
			// indistinguishable from everyone else's. Since hasActiveDrug matches a partner token as a
			// SUBSTRING of an order name (#86), leaving them in lets the subject's own order witness the
			// pair: the token `iron` inside `spironolactona`, an INN spelling the dataset's aliases do
			// not cover. Without order identity no name can be trusted for such a subject, so it may
			// only pair by ATC.
			names.clear();
		}
		return new PatientClinicalContext(null, null, names, codes, null, null);
	}

	/**
	 * @return true when {@code ref} resolves from {@code order} — through either of the two matchers
	 *         that could have made {@code ref} a subject in
	 *         {@link DrugReferenceService#findForActiveOrders}, asked of this
	 *         one order: a drug-name alias match on one of its names
	 *         ({@link DrugReference#matchesDrugName}, the primitive under
	 *         {@link DrugReferenceService#findImpliedByDrugName}) or one of its ATC codes
	 *         ({@link DrugReferenceService#findByActiveOrders}). So "which order is this subject's own"
	 *         is answered by the matchers that chose it, and every arm of
	 *         {@link PatientClinicalContext#hasActiveDrug} is thereby attributed — the reference-name
	 *         arm too, since #136's names are collected per order through this same predicate. The
	 *         name-only form left an ATC-resolved subject unattributable, which is issue #132: a concept
	 *         mapped to the codes of two interacting entries witnessed the pair between them from one
	 *         order.
	 *
	 *         <p>Sharing an exact ATC code means being the same substance (level 5 is per-substance;
	 *         class relatedness is {@link DrugReference#atcSubgroups()}'s business, not this one's), so
	 *         the code leg cannot mistake a different drug's order for the subject's own.
	 *
	 *         <p>Since issue #209 the name leg is a strict SUPERSET of what
	 *         {@link DrugReferenceService#findImpliedByDrugName} would admit from the same order, because
	 *         it asks the unranked primitive: an order named {@code Hydrocortisone Injection vial 100mg}
	 *         still reads as {@code Hydrocortisone butyrate}'s own order, though that order no longer puts
	 *         the ester in play. Deliberately not narrowed here, and the residue is in the safe direction:
	 *         the only effect of counting an order as the subject's own is to withhold it as that
	 *         subject's interaction PARTNER, so an over-wide answer misses a pair and can never invent
	 *         one. Reaching such a pair needs the subject in play by another route (an answer naming the
	 *         ester outright) AND a rule between the two — unmeasured on the shipped KB, so narrowing it
	 *         would be a change without a case.
	 */
	/** @return true when ANY row of the subject's substance {@link #resolvesFrom} {@code order} — the
	 *          group form of that test, since the screening arm's subject is a substance (issue #189). */
	private static boolean resolvesFromAny(List<DrugReference> subjectRows,
			PatientClinicalContext.ActiveDrugOrder order) {
		for (DrugReference row : subjectRows) {
			if (resolvesFrom(row, order)) {
				return true;
			}
		}
		return false;
	}

	/** @return true when ANY row of the subject's substance matches the recorded order name — the group
	 *          form of {@link DrugReference#matchesDrugName}, for the flattened fallback below. */
	private static boolean matchesDrugNameAny(List<DrugReference> subjectRows, String name) {
		for (DrugReference row : subjectRows) {
			if (row.matchesDrugName(name)) {
				return true;
			}
		}
		return false;
	}

	private static boolean resolvesFrom(DrugReference ref, PatientClinicalContext.ActiveDrugOrder order) {
		for (String name : order.getNames()) {
			if (ref.matchesDrugName(name)) {
				return true;
			}
		}
		// Emptiness first: most orders carry no ATC map at all, and this way their subject check costs no
		// allocation (normalizedAtcCodes builds a set per call).
		return !order.getAtcCodes().isEmpty()
				&& !Collections.disjoint(order.getAtcCodes(), ref.normalizedAtcCodes());
	}

	/**
	 * @return the active-order reference entry {@code i} NAMES — through {@link #identifies}, the same
	 *         name-identity test the question-pair arm uses, so both arms agree about which entry a
	 *         rule points at — or null when that order carries no entry in the loaded dataset.
	 *         {@code subject} is never returned: the partner is the OTHER side of the pair, and a rule
	 *         of {@code subject} that {@link #identifies} {@code subject} itself is a self-pair.
	 *
	 *         <p>ONE call site inside this class, {@link #bestRulePerPartner}, which stores the answer on
	 *         the {@link SubjectRule} it builds. Three things need it and must not disagree — that
	 *         grouping, the name and log label {@link #addActiveOrderPairInteractions} gives a screened
	 *         pair, and the cross-arm key in {@link InteractionPairs} — so it is resolved once and carried
	 *         rather than asked again by each of them. Issue #136 made it two consumers; keeping them in
	 *         step by re-running the same scan was the arrangement, and carrying the result removes the
	 *         chance of a pair being named after one entry and grouped under another.
	 *
	 *         <p>Shared with {@link DrugReferenceInjector#orderedInteractionNotes} since issue #190 item
	 *         2, whose partner grouping is the RECORD's counterpart of {@link #bestRulePerPartner}'s:
	 *         two names of one active order (issue #136's {@code warfarin}/{@code coumadin}) were two
	 *         notes beside one chip because the record keyed on the label alone. It keys on this answer
	 *         first now, for the same reason the two must agree on which rows are one partner — and it is
	 *         this method rather than a second resolution, because a second one is how the two come to
	 *         name a partner twice again.
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
	static DrugReference activeOrderEntryFor(List<DrugReference> orderDrugs,
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
	 * allergy is taken as the SUBSTANCES its name denotes ({@link #recordedAllergens}) and a warning
	 * fires when one of them is the same SUBSTANCE as {@code ref} (a recorded allergy to the very drug
	 * being checked), shares {@code ref}'s ATC level-4 subgroup (cross-reactivity), or — failing both —
	 * shares a curated {@link CrossReactivityGroup}
	 * (cross-<em>branch</em> cross-reactivity, e.g. aspirin vs an ibuprofen allergy, which ATC's tree
	 * cannot express). At most one warning per (SUBSTANCE, ALLERGEN'S SUBSTANCE): the most specific match
	 * wins, several aliases of one allergy warn once ({@link #recordedAllergens} de-duplicates them), a
	 * recorded name denoting several substances warns once (the loop below stops at its first match), the
	 * several reference rows one substance is filed as warn once between them, and so do two allergy
	 * RECORDS for two presentations of one substance
	 * ({@link ContraindicationChips}, issue #145 — the ledger this arm adds to rather than appending to
	 * the chip list, and the reason it takes one). That ledger is shared with the curated arm, whose
	 * allergy rules NAMING their own entry land on this arm's key since issue #146 and report the same
	 * fact; so "at most one" spans the two arms and not only this one's own loop.
	 * The two class comparisons need only ATC codes, which
	 * is how an authoritative classification source carrying no rules ({@link AtcDrugReferenceSource})
	 * still produces allergy reasoning.
	 *
	 * <p><b>Identity is not classification (issue #135).</b> The three comparisons were all gated on
	 * one early return taken when {@code ref} had neither an ATC subgroup nor a curated group. That
	 * guard is right for the two class comparisons — without a subgroup or a group there is nothing to
	 * compare against — but the identity comparison is between the two drugs themselves: it needs
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
	 * stack on them — and since issues #193/#195 that precedence spans the whole set of substances one
	 * recorded name denotes, so it cannot be decided a member at a time either) and <em>one warning per
	 * recorded allergy</em> — the wider collapse, across two allergy RECORDS that name one substance, is
	 * the ledger's key and not this loop's. Split across two methods, each would need its own copy
	 * of the other's state — which is precisely the two-arms-cannot-see-each-other shape that produced
	 * issue #88's duplicate interaction chip. {@link #addContraindications} also walks a different
	 * collection ({@code ref.getContraindications()}, matched by token against allergy AND condition
	 * text), so hosting the allergen walk there would put two unrelated loops in one method and still
	 * leave the precedence decision spanning both. What was wrong was the guard's placement, not the
	 * home; the method name said "class" because two of its three comparisons are class-based.
	 *
	 * <p><b>Coverage bound, measured.</b> Identity is only as sound as the resolution behind it, and
	 * {@link DrugReferenceService#findImpliedSubstances} resolves the allergy token to every SUBSTANCE
	 * that name denotes — by the drug-NAME rule since issue #147, not the whole-word one, ranked rather
	 * than first-past-the-post since issue #176, and set-valued rather than one entry since issues
	 * #193/#195. Measured over the full KB, asking about each of the 444 ATC-less entries with an allergy
	 * recorded under that entry's own name: every one raises a contraindication, and every one now names
	 * ITSELF. Before #176, <b>53 of them named a DIFFERENT entry</b> — 43 where every alias that matched
	 * was a strict FRAGMENT of the queried name ({@code Loteprednol etabonate} resolving to
	 * {@code Loteprednol (ophthalmic)}), 10 where the earlier entry carried the queried name in FULL
	 * among its own aliases ({@code Moderna covid-19 vaccine} resolving to
	 * {@code Pfizer-BioNTech Covid-19 Vaccine}, whose CIEL list contains it verbatim). Both shapes are
	 * separated by the claim ranking, which is why it is a rank and not the longest-alias preference this
	 * javadoc used to record as resolving only the first 43.
	 *
	 * <p>An allergy recorded as free text rather than as a drug name still resolves by the containment
	 * rule or not at all. Separately, an ANSWER-named drug can still be echo-scoped out of play before
	 * this arm sees it (issue #105, {@link #isEchoOfCitedRecord}) — the 444 measurement is of the
	 * question-driven path, which is never echo-scoped.
	 *
	 * <p><b>One recorded name, several substances, still one chip.</b> The precedence below is decided
	 * ACROSS the implied set and not per member, which is what keeps the strongest true statement:
	 * a patient allergic to {@code abacavir / lamivudine} asked about lamivudine is told they are
	 * allergic to it, rather than that it cross-reacts with the abacavir in the same tablet. And the
	 * loop raises at most ONE chip per recorded allergy per subject, so a subject related to two of the
	 * implied substances — zidovudine shares {@code J05AF} with both of those constituents — reports the
	 * one clinical fact once. The ledger behind it collapses the wider case, two allergy RECORDS naming
	 * one substance: for EVERY one of the 129 substances the shipped KB files as more than one row,
	 * measured 2026-08-08 by recording two of a family's rows as two allergies and running validate over
	 * each family in turn — 2 identity chips keyed on the resolved row, 1 keyed on the substance, 129 of
	 * 129 either way.
	 *
	 * <p><b>What a correct resolution still costs.</b> Driven through this method by {@code validate}
	 * for each distinct published name whose resolution the ranking moves — ONE recorded allergy per
	 * probe, asking about the row the pre-#192 rule landed on (2026-08-09, over the 5169 distinct names
	 * the shipped KB publishes; re-measure before relying on the figures): resolution moves for 265 of
	 * them and 87 raised no contraindication at all. Reading the recorded name as the substances it
	 * denotes brings 23 of those 87 back and silences none. Most of the remaining 64 are #192's own
	 * correct withdrawals, where the row the old rule landed on was never the recorded drug — the
	 * {@code …lactate} and {@code …salicylate} salts that used to resolve to {@code Lactic acid} and
	 * {@code Salicylic acid}, {@code Moderna covid-19 vaccine} against the Pfizer row,
	 * {@code digoxin antibodies fab fragments} against {@code Digoxin}. What is genuinely left is the
	 * shape no rule over NAMES can reach: a moiety the KB names by a bare WORD rather than by a
	 * trailing qualifier ({@code Peanut oil} against {@code Peanut}, {@code Dextran 40},
	 * {@code penicillin g, procaine}), which is spelled exactly like
	 * {@code Digoxin Immune Fab (Ovine)} against {@code Digoxin}, the patient allergic to digoxin's
	 * ANTIDOTE that issue #192 measured and separated — plus a constituent the KB publishes no name for
	 * at all ({@code apple pectin}, {@code dextran 70}). See
	 * {@link DrugReferenceService#findImpliedSubstances} for the gates and
	 * {@code PresentationMoietyAllergenTest} for the bound pinned as a test.
	 */
	private void addAllergyContraindications(ContraindicationChips chips, DrugReference ref,
			List<List<DrugReference>> recordedAllergens) {
		if (recordedAllergens.isEmpty()) {
			return;
		}
		Set<String> refClasses = ref.atcSubgroups();
		List<CrossReactivityGroup> refGroups = CrossReactivityGroup.groupsOf(ref,
				drugReferenceService.getCrossReactivityGroups());
		// substanceGroupKey: the substance this row stands for where the data publishes one and the row
		// itself where it does not, so the identity comparison below subsumes the entry comparison it
		// replaced (issue #164) rather than sitting beside it. It is also the key the ledger groups on, so
		// a substance whose rows arrive from several call sites cannot raise one chip twice.
		Object refSubstance = ref.substanceGroupKey();
		for (List<DrugReference> allergen : recordedAllergens) {
			// Identity FIRST, over every substance the recorded name implies, and only then the class
			// comparisons over the same set: precedence belongs to the recorded allergy as a whole, so a
			// weaker relationship with one implied substance must not pre-empt a stronger one with
			// another. Each arm stops at its first match, which is what makes one recorded allergy one
			// chip however many of the implied substances the subject is related to.
			DrugReference sameSubstance = firstOfSameSubstance(allergen, refSubstance);
			if (sameSubstance != null) {
				chips.add(ref, sameSubstance.substanceGroupKey(), ContraindicationChips.IDENTITY,
						new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION,
								sameSubstance.displayLabel(), "The patient has a recorded allergy to "
										+ sameSubstance.displayLabel() + "."));
				continue;
			}
			if (refClasses.isEmpty() && refGroups.isEmpty()) {
				// The class comparisons' own precondition, kept where it belongs — after the identity
				// check, which needs none of it. Both comparisons below are provably no-ops on empty
				// sets, so this states the requirement in code rather than leaving it to be re-derived:
				// "same class as" and "same group as" are questions only a classified drug can be asked.
				continue;
			}
			boolean chipped = false;
			for (DrugReference implied : allergen) {
				String shared = sharedClass(refClasses, implied);
				if (shared != null) {
					chips.add(ref, implied.substanceGroupKey(), ContraindicationChips.SAME_CLASS,
							new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, ref.displayLabel(),
									ref.displayLabel() + " is in the same ATC class (" + shared
											+ ") as the patient's allergy to " + implied.displayLabel()
											+ " — possible cross-reactivity"));
					chipped = true;
					break;
				}
			}
			if (chipped) {
				continue;
			}
			for (DrugReference implied : allergen) {
				CrossReactivityGroup group = CrossReactivityGroup.sharedGroup(refGroups, implied);
				if (group != null) {
					chips.add(ref, implied.substanceGroupKey(), ContraindicationChips.SAME_GROUP,
							new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, ref.displayLabel(),
									ref.displayLabel() + " is in the same cross-reactivity group ("
											+ group.getName() + ") as the patient's allergy to "
											+ implied.displayLabel() + " — possible cross-reactivity"));
					break;
				}
			}
		}
	}

	/** @return the first of {@code allergen}'s implied substances that IS {@code refSubstance}, else
	 *          null — the identity comparison, hoisted so the precedence read above is one line. */
	private static DrugReference firstOfSameSubstance(List<DrugReference> allergen,
			Object refSubstance) {
		for (DrugReference implied : allergen) {
			if (refSubstance.equals(implied.substanceGroupKey())) {
				return implied;
			}
		}
		return null;
	}

	/**
	 * @return the substances each of the patient's recorded allergies names, one entry per distinct
	 *         resolution and in the order the context lists the tokens — the input to
	 *         {@link #addAllergyContraindications}, resolved once per {@code validate} because it does
	 *         not depend on the subject being checked.
	 *
	 *         <p>De-duplicated on the whole resolved LIST rather than on one row of it — this is the
	 *         {@code seenAllergens} guard that used to live inside the arm, widened because one row is
	 *         no longer the answer. Two tokens whose lists merely OVERLAP are two records and are left
	 *         to the ledger, which collapses them exactly when they reach the same substance by the
	 *         same relationship.
	 *
	 *         <p>The list, not the set: {@link List#equals} is ordered, so two tokens naming the same
	 *         substances in a different ORDER survive as two records where the old row-keyed guard
	 *         collapsed them. That is a gap rather than a decision — see the PR discussion — and it is
	 *         not reachable from the reference data: no two published names resolving to one row
	 *         produce the same substances in a different order (measured through
	 *         {@link DrugReferenceService#findImpliedSubstances}; re-derive rather than trusting it).
	 *         A free-text allergen can, and the ledger then collapses the identity chip but not
	 *         necessarily the class one.
	 */
	private List<List<DrugReference>> recordedAllergens(PatientClinicalContext context) {
		if (context == null) {
			return Collections.emptyList();
		}
		List<List<DrugReference>> out = new ArrayList<List<DrugReference>>();
		for (String allergyToken : context.getAllergyTokens()) {
			List<DrugReference> implied = drugReferenceService.findImpliedSubstances(allergyToken);
			if (!implied.isEmpty() && !out.contains(implied)) {
				out.add(implied);
			}
		}
		return out;
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
	 * would witness a pair with itself, as one {@code Aspirin and omeprazole} order did when it reported
	 * "Acetylsalicylic acid (aspirin) interacts with active order esomeprazole", the two halves of one
	 * tablet as an interacting pair (measured; see {@link #activeOrdersOtherThan}). And not
	 * overdose: {@link #addOverdose} reads a dose out of the ANSWER, so an order the answer never
	 * mentions has no dose to check, and reinstating an echoed drug's dose check is precisely what #105
	 * measured and fixed.
	 *
	 * <p><b>Not gated on the question, deliberately.</b> "Is the patient allergic to something they are
	 * taking?" is a fact about their chart, not about the wording of a query, and gating a
	 * contraindication on the question's wording is what produced this defect. What bounds the arm
	 * instead is the chart: it can only fire where an allergy or condition record and an active order
	 * point at the same drug, and the two arms it delegates to bound it further — one chip per
	 * (substance, allergen's substance) and one per (substance, matching curated rule), those two being
	 * ONE bound wherever the rule names the substance itself (issue #146), through the same
	 * {@link ContraindicationChips} ledger the drug-in-play call site uses, which is what stops one
		 * order that resolves several reference rows raising a chip per row (issue #145). That is a bound
	 * in the patient's own records, the same kind every other contraindication chip has and the reason
	 * the pairwise arms need {@link #maxPairChips()} while this one does not: nothing here is quadratic
	 * in a list the module does not choose.
	 *
	 * <p><b>The precondition.</b> With neither allergy nor condition tokens recorded both arms are
	 * provably no-ops — every branch of {@link #addContraindications} requires
	 * {@link PatientClinicalContext#hasAllergyToken} or
	 * {@link PatientClinicalContext#hasConditionToken}, and
	 * {@link #addAllergyContraindications}'s whole body is a loop over the allergens
	 * {@link #recordedAllergens} resolved from {@link PatientClinicalContext#getAllergyTokens()} — so
	 * the check is skipped rather than run to find nothing. What it saves is the two arms' own work over
	 * every order subject, not the resolution of those subjects: since issue #136 {@code validate}
	 * resolves them once per pass whatever the question, because
	 * {@link PatientClinicalContext#hasActiveDrug} needs their names. (Before that, this precondition
	 * also spared the resolution, and its javadoc said so.) Read BOTH token sets, not just allergies:
	 * the curated arm's condition leg is half of what the scoping was suppressing.
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
	 * limit {@link DrugReferenceService#findForActiveOrders} documents for the screen. And the arms it
	 * delegates to decide the rest: an allergy token {@link DrugReferenceService#findImpliedSubstances}
	 * cannot resolve to the drug the chart means — free text naming no entry — is labelled here exactly
	 * as it is on the question path (see {@link #addAllergyContraindications}'s measured coverage
	 * bound). A combination product naming several used to be on that list and is not since issues
	 * #193/#195.
	 */
	private void addActiveOrderContraindications(ContraindicationChips chips, Set<DrugReference> inPlay,
			PatientClinicalContext context, List<DrugReference> orderEntries,
			List<List<DrugReference>> recordedAllergens) {
		if (context == null
				|| (context.getAllergyTokens().isEmpty() && context.getConditionTokens().isEmpty())) {
			return;
		}
		for (DrugReference ref : orderEntries) {
			if (inPlay.contains(ref)) {
				continue;
			}
			addContraindications(chips, ref, context);
			addAllergyContraindications(chips, ref, recordedAllergens);
		}
	}

	/**
	 * Class-based interaction reasoning: warns when the drug {@code ref} being checked shares
	 * an ATC level-4 subgroup with one of the patient's active orders (additive effects / duplicate
	 * therapy) or — failing that — a curated {@link CrossReactivityGroup} (a cross-branch family
	 * overlap, e.g. ibuprofen recommended over an active aspirin order). An order that is the
	 * <em>same</em> drug as {@code ref} (a shared exact ATC code) is skipped — restating existing
	 * therapy is not a duplicate. Active orders carry ATC codes (the builder maps them), so this
	 * matches on codes directly and names the order by the ladder {@link #orderPartners} documents —
	 * the dataset's name for the substance, else the order's own display name, else the code (issue
	 * #155). The most specific match wins, so a subgroup + group double-match warns once — except
	 * where the shared subgroup is one {@link DrugReference#isUnclassifyingAtcCode} vetoes, which is
	 * not a match at all and lets the group answer (issue #167).
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
	 * <p><b>One sentence per co-medication, not per shared code (issue #171).</b> This walked
	 * {@link PatientClinicalContext#getActiveDrugAtcCodes()} and emitted a sentence per CODE, so a
	 * partner filed under several codes produced a sentence per shared subgroup — identical but for the
	 * class named. Measured inputs: the 3.7.1 demo dictionary's {@code Metronidazole} concept carries
	 * five {@code WHOATC} maps and shares three level-4 subgroups with tinidazole, with no rated KB row
	 * to fold them into, so one clinical fact reached the clinician three times. The codes are
	 * therefore grouped by the co-medication they identify ({@link #orderPartners}) before anything is
	 * worded, and WHICH class the one sentence names is {@link #sharedClass}'s decision over the whole
	 * shared set — the same decision the allergy arm makes, so the two arms can no longer describe one
	 * pair through different subgroups, and neither depends on the order a dictionary published its
	 * mappings in.
	 *
	 * @return the class relationship sentence for each active-order partner that has one, keyed by that
	 *         partner, in partner FIRST-APPEARANCE order over the context's codes; empty when the drug
	 *         is in no class or group at all.
	 *         <p>Not the same as the per-code order this returned before issue #171, and the difference
	 *         is observable: a partner whose first code shares nothing now sorts by that first code
	 *         rather than by the code that produced its sentence, so it can precede a partner that the
	 *         per-code walk put ahead of it. Chip CONTENT is unchanged; two class-only chips can swap
	 *         places. Stated rather than smoothed over, because "chip order is unchanged" was the
	 *         previous promise here and is no longer one that can be kept.
	 */
	private Map<OrderPartner, String> classRelationships(DrugReference ref, PatientClinicalContext context) {
		Map<OrderPartner, String> out = new LinkedHashMap<OrderPartner, String>();
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
		for (OrderPartner partner : orderPartners(context)) {
			if (!Collections.disjoint(partner.codes, refCodes)) {
				// Restating existing therapy is not a duplicate — per PARTNER, because a substance is
				// the same substance under every code it is filed under, so sharing ONE exact code
				// already says the order and the drug asked about are one drug.
				continue;
			}
			String shared = sharedClass(refClasses, DrugReference.atcSubgroups(partner.codes));
			if (shared != null) {
				out.put(partner, ref.displayLabel() + " is in the same ATC class (" + shared
						+ ") as active order " + partner.label + " — possible duplicate therapy");
				continue;
			}
			CrossReactivityGroup group = CrossReactivityGroup.sharedGroupForCodes(refGroups, partner.codes);
			if (group != null) {
				out.put(partner, ref.displayLabel() + " is in the same cross-reactivity group ("
						+ group.getName() + ") as active order " + partner.label
						+ " — possible additive or duplicate-class therapy");
			}
		}
		return out;
	}

	/**
	 * One co-medication the class arm reasons about: the ATC codes of the patient's active orders that
	 * identify it, and the name a chip calls it by.
	 *
	 * <p>Identity, not text: two rows of one substance, or one order's several codes, are one partner
	 * however the chip words them — the rule both the contraindication ledger and
	 * {@link InteractionPairs} already follow.
	 */
	private static final class OrderPartner {

		private String label;

		/** Whether {@link #label} came from an ORDER rather than from the dataset — see
		 *  {@link #nameByOrder}. */
		private boolean namedByOrder;

		private final Set<String> codes = new LinkedHashSet<String>();

		private OrderPartner(String label, boolean namedByOrder) {
			this.label = label;
			this.namedByOrder = namedByOrder;
		}

		/**
		 * Re-name this partner after the ORDER, because one of the codes it holds is a code the
		 * dataset cannot name (issue #186). Monotone — an entry name yields to an order name, never
		 * the reverse, so the answer does not depend on which of the order's codes the context listed
		 * first.
		 *
		 * <p>Why the order wins there. The dataset names the substance the COVERED codes identify;
		 * it says nothing about the uncovered one, and the two need not be the same substance. The
		 * 3.7.1 demo dictionary's {@code Isoniazid / Rifapentine} concept is exactly that: the loaded
		 * KB covers {@code J04AB05} (rifapentine) and not {@code J04AC51}, and naming the merged
		 * partner "Rifapentine" produces "…is in the same ATC class (J04AC) as active order
		 * Rifapentine", a chip whose stated class does not classify the drug it names — the
		 * right-finding-wrong-reason failure issue #161 fixed on the allergy arm. The order's own
		 * display name is true of everything the order contains, which is what a partner holding an
		 * unnameable code needs.
		 */
		private void nameByOrder(String orderLabel) {
			if (!namedByOrder && !ChartSearchAiUtils.isBlank(orderLabel)) {
				label = orderLabel;
				namedByOrder = true;
			}
		}
	}

	/**
	 * The patient's co-medications as the class arm sees them: every active-order ATC code, grouped by
	 * the co-medication it identifies, in first-appearance order.
	 *
	 * <p><b>The identity ladder</b>, best evidence first, applied PER CODE. The dataset's own entry for
	 * the code where it has one, keyed by {@link DrugReference#substanceGroupKey()} so a substance filed
	 * as several rows is one partner — and so two orders of one substance are one partner too; else the
	 * ACTIVE ORDER that contributed the code, so an order the dataset does not cover at all is one
	 * partner rather than one per code; else the code itself, which is all a context carrying only the
	 * flattened set (issue #118's fallback) offers.
	 *
	 * <p><b>The rung issue #186 added, and why it is not simply "group by order".</b> The ladder used
	 * to be applied per code with nothing consulting the ORDER until the entry rung had already
	 * failed, so an order the dataset covers only PARTLY climbed two different rungs — the covered
	 * codes onto the entry, the uncovered ones onto the order — and became two partners under two
	 * different labels ("… as active order Metronidazole" beside "… as active order Metronidazole
	 * 500mg"). An uncovered code now first asks whether the order carrying it resolves, as a whole,
	 * to exactly ONE substance, and joins that substance's partner when it does
	 * ({@link #soleSubstanceOf}) — carrying the ORDER's name onto that partner as it goes, because
	 * the dataset's name speaks only for the codes it covers (see {@link OrderPartner#nameByOrder}).
	 *
	 * <p>Grouping by the order OUTRIGHT would have been wrong in both directions, which is why the
	 * order is consulted only for the codes the dataset cannot speak for. It would split a substance
	 * the patient holds TWO orders of into two partners, which this ladder deliberately merges; and
	 * it would merge a fixed-dose COMBINATION — one order whose concept maps to the codes of two
	 * different substances — into one, dropping a real duplicate-therapy chip. A combination is
	 * exactly the case {@link #soleSubstanceOf} answers null for, so its uncovered codes stay on the
	 * order rung: with two substances in one tablet there is no evidence which of them an uncovered
	 * code belongs to, and the order is the honest answer.
	 *
	 * <p><b>Where the ladder still does not hold its promise.</b> The builder's KNOWN GAP: a nameless
	 * order reaches {@link PatientClinicalContext#getActiveDrugAtcCodes()} without reaching
	 * {@code getActiveDrugOrders()}, so {@link #orderCarrying} finds nothing, the new rung cannot
	 * fire either, and each of its uncovered codes is its own partner. Nothing here can close that —
	 * with no order identity there is nothing to group BY, and grouping every unclaimed code together
	 * would merge two genuinely different orders (and, on the flattened fallback of issue #118, merge
	 * the whole medication list into one partner). Closing it means giving such an order a fallback
	 * display in {@link PatientClinicalContextBuilder} so it reaches the list at all, which is that
	 * gap's own fix and has its own consequences for the injected record.
	 *
	 * <p><b>The label follows the same ladder</b> (issue #155). It used to be the entry's label or,
	 * failing that, the bare CODE — so on the default {@code sourceFormat=json}, whose four-entry
	 * curated seed carries no aspirin, Agnes Adams' chip read "… as active order N02BA01". A clinician
	 * has no reason to recognise an ATC code, and the order it stands for carries a display name that
	 * needs no reference dataset at all. The code survives only as the last resort, where nothing in
	 * the context names the order either.
	 *
	 * <p>Grouped once per SUBJECT and carried through that subject's sentence and its fold, so the
	 * partner a chip names and the partner {@link #addInteractionWarnings} decides about cannot be
	 * different ones. Not once per {@code validate}: {@link #classRelationships} runs per in-play
	 * substance and calls this each time, and {@link #ruleAbout} re-runs {@link #entryForAtcCode}
	 * itself rather than reading the resolution carried here. They agree because that scan is a
	 * function of {@code getAll()} alone, which is loaded once — a property of the service, not
	 * something this method enforces. The memo below is per CALL and does not change that; widening it
	 * to the whole {@code validate} pass would, and would cut the repeated full scans, but it must
	 * then be a local threaded through the pass and NEVER a field: a memoised {@link DrugReference}
	 * outliving a {@code getAll()} reload fails the reference comparisons the contraindication arms
	 * make (issue #172), which silently re-opens issue #145.
	 */
	private List<OrderPartner> orderPartners(PatientClinicalContext context) {
		Map<Object, OrderPartner> byIdentity = new LinkedHashMap<Object, OrderPartner>();
		// Per-CALL memos, never fields: entryForAtcCode is a full scan of getAll() and the rung added
		// by issue #186 asks it once per code of an order as well as once per code of the context, so
		// without them a partly-covered order rescans the dataset for every code it carries. A field
		// would be issue #172's trap — a memoised DrugReference outliving a getAll() hot-reload fails
		// the reference comparisons the contraindication arms make, silently re-opening issue #145.
		Map<String, DrugReference> entryByCode = new LinkedHashMap<String, DrugReference>();
		Map<PatientClinicalContext.ActiveDrugOrder, DrugReference> substanceByOrder =
				new LinkedHashMap<PatientClinicalContext.ActiveDrugOrder, DrugReference>();
		for (String orderCode : context.getActiveDrugAtcCodes()) {
			DrugReference entry = entryForAtcCode(orderCode, entryByCode);
			PatientClinicalContext.ActiveDrugOrder order = null;
			boolean unnameableCode = entry == null;
			if (unnameableCode) {
				// The dataset cannot name this code. Before falling to the order itself, ask whether
				// the ORDER carrying it names one substance between all its codes — issue #186.
				order = orderCarrying(orderCode, context);
				if (order != null) {
					entry = soleSubstanceOf(order, entryByCode, substanceByOrder);
				}
			}
			Object identity;
			String label;
			boolean namedByOrder;
			if (entry != null) {
				identity = entry.substanceGroupKey();
				label = entry.displayLabel();
				namedByOrder = false;
			} else {
				identity = order != null ? order : (Object) orderCode;
				label = order != null
						? ChartSearchAiUtils.firstNonBlank(order.getDisplay(), orderCode) : orderCode;
				namedByOrder = order != null;
			}
			OrderPartner partner = byIdentity.get(identity);
			if (partner == null) {
				partner = new OrderPartner(label, namedByOrder);
				byIdentity.put(identity, partner);
			}
			if (unnameableCode && order != null) {
				// This partner holds a code the dataset cannot name, so the dataset's name for its
				// other codes does not speak for the whole of it — see OrderPartner.nameByOrder.
				partner.nameByOrder(ChartSearchAiUtils.firstNonBlank(order.getDisplay(), orderCode));
			}
			partner.codes.add(orderCode);
		}
		return new ArrayList<OrderPartner>(byIdentity.values());
	}

	/**
	 * @return the one substance {@code order}'s ATC codes resolve to between them, as the row that
	 *         names it ({@link DrugReference#canonicalRow}), or null when they resolve to none or to
	 *         MORE than one.
	 *
	 *         <p>Null for a combination is the load-bearing half (issue #186): one order mapped to
	 *         two substances' codes is two co-medications, and answering with either of them would
	 *         attach an uncovered code to a substance nothing says it belongs to — and, worse, could
	 *         merge the two into one partner and drop a duplicate-therapy chip. Null for "resolves to
	 *         none" leaves the ladder exactly where it was: the order itself is the identity.
	 *
	 *         <p>Memoised through {@code cache} for the duration of one {@link #orderPartners} call,
	 *         because this walks every code of an order and every code of that order asks it — see
	 *         there for why the memo may not be a field.
	 */
	private DrugReference soleSubstanceOf(PatientClinicalContext.ActiveDrugOrder order,
			Map<String, DrugReference> entryByCode,
			Map<PatientClinicalContext.ActiveDrugOrder, DrugReference> cache) {
		if (cache.containsKey(order)) {
			return cache.get(order);
		}
		Object substance = null;
		DrugReference canonical = null;
		for (String code : order.getAtcCodes()) {
			DrugReference entry = entryForAtcCode(code, entryByCode);
			if (entry == null) {
				continue;
			}
			Object key = entry.substanceGroupKey();
			if (substance != null && !substance.equals(key)) {
				// A second substance: this order is a combination, and no uncovered code of it can be
				// attributed to either half.
				canonical = null;
				break;
			}
			substance = key;
			canonical = DrugReference.canonicalRow(canonical, entry);
		}
		cache.put(order, canonical);
		return canonical;
	}

	/** {@link #entryForAtcCode} memoised for one {@code orderPartners} call. {@code null} is a real
	 *  answer ("the dataset does not cover this code") and is cached as one, so an uncovered code
	 *  does not rescan the dataset on every visit — hence {@code containsKey} rather than a null
	 *  check. */
	private DrugReference entryForAtcCode(String upperCode, Map<String, DrugReference> cache) {
		if (cache.containsKey(upperCode)) {
			return cache.get(upperCode);
		}
		DrugReference entry = entryForAtcCode(upperCode);
		cache.put(upperCode, entry);
		return entry;
	}

	/**
	 * @return the patient's active order whose own concept maps to {@code upperCode} (issue #132's
	 *         per-order codes), or null — which is the normal answer for a context built from the
	 *         flattened sets alone, and also for the builder's KNOWN GAP, an order with no readable
	 *         name whose codes reach the union without the order reaching the list.
	 */
	private static PatientClinicalContext.ActiveDrugOrder orderCarrying(String upperCode,
			PatientClinicalContext context) {
		for (PatientClinicalContext.ActiveDrugOrder order : context.getActiveDrugOrders()) {
			if (order.getAtcCodes().contains(upperCode)) {
				return order;
			}
		}
		return null;
	}

	/**
	 * @return the ATC level-4 subgroup {@code other} shares with {@code refClasses} that best explains
	 *         a cross-reactivity concern between them — the one classifying the SUBSTANCE where they
	 *         share one, else the locally-applied one they do share — or null when they share none.
	 *
	 * <p><b>The defect this exists to fix (issue #161).</b> This returned the first shared subgroup in
	 * the allergen's own ATC array. Every array in the shipped 19 MB KB is in ascending code order
	 * (measured 2026-08-06: 1839 of the 1839 entries carrying codes), so "first" meant "alphabetically
	 * smallest" — and ATC's alphabet front-loads the locally-applied groups: A01 stomatological, C05A
	 * topical, D dermatological all sort ahead of H, J, L, M and N. A substance marketed by several
	 * routes carries a code for each, so the chip systematically justified a systemic concern with a
	 * topical class. Reported live against a dexamethasone allergy (issue #161): methylprednisolone as
	 * {@code D10AA} (anti-acne preparations), an injected hydrocortisone as {@code A01AC}
	 * (corticosteroids for local ORAL treatment) — both reproduced in-process from the same KB rows by
	 * {@code CrossReactivityClassChoiceTest}, while prednisone, whose only shared subgroup IS
	 * {@code H02AB}, was right in the same answer. The finding was right and its stated reason was
	 * not, which is the failure a clinician checks and then stops trusting.
	 *
	 * <p><b>Why prefer the systemic class rather than match the order's route.</b> The route is not
	 * available here, and could only ever be available on one of the two call sites. This arm runs
	 * both for a drug the QUESTION names, which has no route at all, and for one the patient is on
	 * ({@link #addActiveOrderContraindications}) — and even there it receives a resolved
	 * {@link DrugReference}, not the order. Nothing carries the route that far:
	 * {@link PatientClinicalContextBuilder} reads a {@code DrugOrder}'s name concepts and its ATC
	 * mappings, never {@code getRoute()}, and {@link PatientClinicalContext.ActiveDrugOrder}'s display
	 * is the order's first NAME rather than a dosing line. Route-matching is therefore not a smaller
	 * change than this one but a larger one — a new field on the context, a route-concept-to-ATC
	 * mapping the module does not have — and it would still leave the question-driven half of this
	 * arm choosing by some other rule.
	 *
	 * <p><b>And why not report the shared level-3 group instead</b>, which the issue offers as the
	 * answer that is coarser but never false. Measured over the shipped KB (2026-08-06; re-measure
	 * before relying on any figure here): of the 1090 drug pairs that share more than one level-4
	 * subgroup, <b>1041 still share more than one level-3 group</b>.
	 * Dexamethasone and hydrocortisone share six subgroups spanning six different level-3 groups, so
	 * the collapse removes the chemical subgroup — the part that carries the cross-reactivity claim —
	 * without removing the choice it was supposed to settle.
	 *
	 * <p><b>A preference, never a filter.</b> The 1090 pairs partition into 263 whose class this
	 * changes, 587 that share no systemic subgroup at all — two topical azoles, two ophthalmic
	 * preparations, two local anaesthetic formulations, for which the locally-applied class IS the
	 * honest answer and is kept — and 240 that were already naming a systemic one. A filter rather than
	 * a preference would have to drop or fabricate a class for the 587, the largest of the three
	 * groups. In 70 pairs the systemic tier itself holds more than one candidate and the tie-break
	 * between them is still alphabetical (issue #168, filed against the pre-correction count of 87);
	 * both are true statements about the substance, so that is a choice between honest answers rather
	 * than the defect above.
	 *
	 * <p>Sorted rather than in the allergen's array order so the result is a function of the two code
	 * SETS and not of the position a dataset happened to write a code in — what keeps a KB refresh that
	 * reorders an array from silently rewording a chip. A no-op on the shipped KB, whose arrays are all
	 * ascending, so the case that pins it
	 * ({@code CrossReactivityClassChoiceTest.theAnswerDoesNotDependOnTheAllergenArraysCodeOrder}) is
	 * the one fixture here that deviates from verbatim, by writing one allergen's array descending.
	 *
	 * <p><b>And the subgroups no tier may return</b> (issue #167): a shared subgroup that classifies
	 * neither the substance nor a therapy is skipped outright rather than demoted, in both tiers, so
	 * the method can answer "they share nothing that explains anything" — see
	 * {@link DrugReference#isUnclassifyingAtcCode}. A demotion would not do: potassium iodide and
	 * acetylcysteine share {@code S01XA} "Other ophthalmologicals" AND {@code V03AB} "Antidotes", one
	 * locally applied and one not, so every tier a demotion could fall through to is occupied by
	 * another bucket that means nothing either. The caller then decides what "no shared class" implies:
	 * both arms fall through to the curated cross-reactivity groups, which is the one class statement
	 * this module makes from data a clinician curated rather than from a code.
	 */
	private static String sharedClass(Set<String> refClasses, DrugReference other) {
		return sharedClass(refClasses, other.atcSubgroups());
	}

	/**
	 * The same choice over a bare code set, for the arm whose "other" is an ACTIVE ORDER rather than a
	 * resolved entry — the interaction arm reads the order's own ATC mappings, and the dataset need not
	 * carry the substance they identify at all ({@link #classRelationships}).
	 *
	 * <p>One decision shared by the two arms rather than a scan in each (issue #171): the allergy arm
	 * got the preference in issue #161/#166 and the interaction arm kept naming whichever code it
	 * reached first, so one build could report a pair's topical subgroup as duplicate therapy and its
	 * systemic one as cross-reactivity.
	 *
	 * <p>{@code otherSubgroups} must be level-4 SUBGROUPS, not full codes — everything here is compared
	 * against {@code refClasses}, which holds subgroups, so a full code would silently match nothing
	 * and the arm would report no relationship rather than fail. Callers reduce first, both through
	 * {@link DrugReference#atcSubgroups(Set)}.
	 */
	private static String sharedClass(Set<String> refClasses, Set<String> otherSubgroups) {
		String locallyApplied = null;
		for (String subgroup : new TreeSet<String>(otherSubgroups)) {
			if (!refClasses.contains(subgroup) || DrugReference.isUnclassifyingAtcCode(subgroup)) {
				continue;
			}
			if (!DrugReference.isLocallyAppliedAtcCode(subgroup)) {
				return subgroup;
			}
			if (locallyApplied == null) {
				locallyApplied = subgroup;
			}
		}
		return locallyApplied;
	}

	/**
	 * @return the loaded reference entry carrying {@code upperCode} — the dataset's own record for the
	 *         substance an active order's ATC code identifies — or null when the dataset does not cover
	 *         it. One definition, because the class chip NAMES this entry while the cross-arm
	 *         correlation asks WHICH RULE POINTS AT IT ({@link #ruleAbout}): resolving the code two
	 *         ways would let a chip name one substance while the fold decided about another.
	 *
	 *         <p><b>Which row, when the substance is filed as several (issue #174, site 1).</b> Every
	 *         row of a substance publishes the SAME ATC list, so "the entry carrying this code" is
	 *         ambiguous by construction and this returned whichever row the dataset listed first. Four
	 *         shipped substances list a route-qualified row first and carry ATC codes —
	 *         {@code Salicylic acid (sodium)}, {@code Chloroprocaine (ophthalmic)},
	 *         {@code Tozinameran (12y+)} and {@code Cyclosporine (ophthalmic)} — so a systemic
	 *         cyclosporine order mapped to {@code L04AD01} was named "Cyclosporine (ophthalmic)" in a
	 *         chip about tacrolimus. {@link DrugReference#canonicalRow} decides it instead, the same
	 *         choice the chip's SUBJECT side (issue #162) and the injected record (issue #163) already
	 *         make, so one substance is one name wherever it appears. A full scan rather than a
	 *         first-match return is what that costs.
	 */
	private DrugReference entryForAtcCode(String upperCode) {
		DrugReference canonical = null;
		for (DrugReference ref : drugReferenceService.getAll()) {
			if (ref.normalizedAtcCodes().contains(upperCode)) {
				canonical = DrugReference.canonicalRow(canonical, ref);
			}
		}
		return canonical;
	}

	/**
	 * At most ONE dose warning for the substance {@code subjects} are the reference rows of, named
	 * after the row that names the substance.
	 *
	 * <p><b>Issue #174 site 4 — the fourth per-row site, and the one that was latent.</b> This ran
	 * once per row of {@code inPlay}, so a substance filed as several rows produced one dose warning
	 * per row for a single stated dose, each named after its own row — and, since issue #110, one
	 * near-identical citable safety-finding record per warning in the prompt as well. No bundled
	 * dataset can reach it: a warning needs {@code ageBands}, which only the curated {@code json}
	 * schema carries, and the grouping needs a {@code substanceName}, which the shipped curated seed
	 * does not set. An operator authoring a file with both — which
	 * {@link DrugReference#getSubstanceName()} explicitly permits — reaches it immediately, so it is
	 * guarded while the pattern is being swept rather than waited for.
	 *
	 * <p><b>Every row is still tried, and that is the point of the shape.</b> A collapse that
	 * simply read the canonical row would DROP a warning whenever the band sits on a sibling — the
	 * one direction a non-blocking advisory must never take. So the rows are tried in
	 * canonical-first order and the first warning found is the one raised, which also keeps the
	 * quoted band the substance's own wherever it publishes one. What the collapse gives up is the
	 * ability to report two different published ceilings for one substance, which is not a thing a
	 * clinician can act on: nothing here knows which formulation is in play (see
	 * {@link DrugReference#namesNoRoute()}), so a second ceiling is a second guess, not a second
	 * fact.
	 */
	private void addOverdose(List<SafetyWarning> warnings, List<DrugReference> subjects,
			PatientClinicalContext context, String lowerAnswer, List<DrugReference> allEntries) {
		// The same choice of representative row the interaction arms make, recorded names and all
		// (issue #194): a dose warning and an interaction chip about ONE substance in ONE response must
		// not call it two things, which is exactly the divergence anchoring only the interaction arms
		// would have created.
		DrugReference subject = interactionSubject(subjects, recordedDrugNames(context));
		if (addOverdose(warnings, subject, subject, context, lowerAnswer, allEntries)) {
			return;
		}
		for (DrugReference row : subjects) {
			if (row != subject && addOverdose(warnings, subject, row, context, lowerAnswer, allEntries)) {
				return;
			}
		}
	}

	/**
	 * The dose check for ONE reference row, reported under {@code subject}'s name.
	 *
	 * @param subject the row the warning NAMES — the substance's canonical row, so a chip never
	 *        asserts a formulation the chart does not record (the same judgement
	 *        {@link #interactionSubject} makes for the interaction chips)
	 * @param ref the row whose published {@code ageBands} and aliases the check READS
	 * @return whether a warning was raised, so the caller can stop at the first row that trips
	 */
	private boolean addOverdose(List<SafetyWarning> warnings, DrugReference subject, DrugReference ref,
			PatientClinicalContext context, String lowerAnswer, List<DrugReference> allEntries) {
		Integer age = context != null ? context.getAgeYears() : null;
		DrugReference.AgeBand band = ref.bandForAge(age);
		if (band == null) {
			return false;
		}
		Double weightKg = context != null ? context.getWeightKg() : null;
		boolean dailyArm = band.getMaxDailyDoseMg() > 0;
		boolean weightArm = weightKg != null && weightKg > 0 && band.getMgPerKgMax() > 0;
		if (!dailyArm && !weightArm) {
			return false;
		}
		// One attribution walk feeds whichever arms apply.
		List<AttributedDose> doses = attributedDoses(lowerAnswer, ref, allEntries);
		String label = subject.displayLabel();
		if (dailyArm) {
			Double dailyMg = parseDailyDoseMg(doses);
			if (dailyMg != null && dailyMg > band.getMaxDailyDoseMg()) {
				warnings.add(new SafetyWarning(SafetyWarning.TYPE_OVERDOSE, label,
						"The stated " + label + " dose ~" + DrugReference.formatNumber(dailyMg)
								+ " mg/day exceeds the "
								+ DrugReference.formatNumber(band.getMaxDailyDoseMg()) + " mg/day maximum for ages "
								+ band.getMinYears() + "-" + band.getMaxYears()));
				// One warning per drug: the published daily ceiling is the stronger statement,
				// so the per-dose arm below is not stacked on top of it.
				return true;
			}
		}
		if (!weightArm) {
			return false;
		}
		Double perDoseMg = parseMaxPerDoseMg(doses);
		double perDoseLimitMg = band.getMgPerKgMax() * weightKg;
		if (perDoseMg != null && perDoseMg > perDoseLimitMg) {
			warnings.add(new SafetyWarning(SafetyWarning.TYPE_OVERDOSE, label,
					"The stated " + label + " dose ~" + DrugReference.formatNumber(perDoseMg)
							+ " mg exceeds the "
							+ DrugReference.formatNumber(band.getMgPerKgMax()) + " mg/kg per-dose maximum (~"
							+ DrugReference.formatNumber(perDoseLimitMg) + " mg) for the patient's weight "
							+ DrugReference.formatNumber(weightKg) + " kg (ages " + band.getMinYears() + "-"
							+ band.getMaxYears() + ")"));
			return true;
		}
		return false;
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
