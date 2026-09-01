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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
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
 *       same two checks additionally run over the patient's OWN ACTIVE ORDERS — "is the patient
 *       allergic to something they are taking?" is a fact about their chart, and the drug-in-play
 *       framing above could not ask it (see {@link #addActiveOrderContraindications}, issue #143) —
 *       scoped to what the RESPONSE is about, either side of the chip counting, since that arm
 *       annotates an answer rather than raising an alert ({@link SubjectMatter}).</li>
 * </ul>
 *
 * <p>The rule-based checks fire on the entry's own curated {@code interactions}/
 * {@code contraindications}; the <em>class-based</em> checks need only ATC codes, so they
 * are the mechanism by which an authoritative classification source ({@link AtcDrugReferenceSource},
 * which carries no rules) still produces safety reasoning. "Same class" means a shared ATC
 * level-4 chemical subgroup ({@link DrugReference#ATC_SUBGROUP_PREFIX_LENGTH}), e.g. ibuprofen {@code M01AE01}
 * and naproxen {@code M01AE02} both {@code M01AE}. ATC's tree does not capture cross-branch
 * pharmacological cross-reactivity (aspirin {@code N02BA01} vs ibuprofen {@code M01AE01}); that
 * linkage is carried as curated data — {@link CrossReactivityGroup}s loaded alongside any
 * source — and both class checks fall back to it when no ATC subgroup is shared, so the family
 * reasoning stays data-driven end to end. A shared subgroup is necessary but not sufficient since
 * issue #167: one that classifies neither the substances nor a therapy is skipped, and the pair falls
 * through to the curated groups as though nothing were shared — see
 * {@link DrugReference#isUnclassifyingAtcCode}. Since issues #183/#184 how much a subgroup must
 * assert depends on WHICH claim is being made: naming a purpose ("Antibiotics") is enough to call two
 * drugs duplicate therapy and not enough to call them cross-reacting, so the contraindication arm
 * additionally skips everything {@link DrugReference#isPurposeOnlyAtcCode} recognises. The two arms
 * therefore no longer see the same "same class"; {@link #sharedClass} is where that single difference
 * lives. See ADR Decision 24.
 *
 * <p>One contraindication check is neither rule-based nor class-based: a recorded allergy to the very
 * drug in play is IDENTITY, and needs no rule, no ATC code and no curated group. It is therefore not
 * gated on classification data — issue #135, where it was, and 444 of the full DDInter dataset's 2283
 * entries (19.4%, none of them carrying any ATC code) consequently raised no chip for the most basic
 * check here. See {@link #addAllergyContraindications}.
 *
 * <p>Two checks do not need a drug in play at all. The first is the patient's own active orders
 * checked against their own allergy and condition records — see
 * {@link #addActiveOrderContraindications}, issue #143, which exists because the echo scoping below
 * withheld exactly that finding for a drug appearing in a cited {@code drug_order} record. It needs
 * no drug in play; it is not therefore unconditional, and {@link SubjectMatter} bounds it to the
 * responses it has something to say about.
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
 * interactions skip an active order that is the <em>same substance</em> (restating existing therapy
 * is not a duplicate). A question or answer that names no reference drug produces no warnings
 * (the no-false-positive case) unless the patient's own chart supplies the subject — the two
 * deliberate exceptions above, both still no-false-positive checks. The interaction screen reports
 * only pairs the reference data actually relates ("rates" would be too narrow — an unrated rule is
 * exempt from the severity floor, not filtered by it, so unrated pairs are screened too). The
 * active-order contraindication arm reports only a drug the patient is ON whose own allergy or
 * condition records contraindicate it, and stands down entirely when neither is recorded.
 *
 * <p><b>Every memo of anything derived from {@code DrugReferenceService.getAll()} is a per-call LOCAL
 * of the pass that made it, never a field of this bean</b> — issue #172's rule, stated with its reasons
 * in {@code DrugReferenceService}'s class javadoc. Said here because this class holds most of the sites
 * it binds, and a reader adding one is in this file rather than that one.
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
	 *  so a dose far from any drug name is ignored. Counted in the FOLDED clause
	 *  {@link #attributedDoses} establishes, since issue #260 — {@link DrugReference#foldDiacritics} is
	 *  not length-preserving, so this is a budget in that coordinate system and not in the answer's. */
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
	 * Public entry point with the chart's record mappings, which enable echo scoping: an
	 * answer-named drug that a record cited by the answer already names in its own text (a
	 * recited reference partner, an allergy reported off the chart) is a mention, not a
	 * proposal, and is not validated (issue #105). Passing {@code null}/empty mappings disables
	 * the scoping and keeps every answer-named drug in play (the conservative pre-scoping
	 * behavior).
	 *
	 * <p><b>Since issue #336, {@code LlmInferenceService} calls the five-argument overload below,
	 * not this one</b> — it also publishes how bounded the pairwise interaction list is, which this
	 * arity has nowhere to carry. A decorator or a test double that overrides THIS method alone is
	 * therefore inert on the production path, and inert SILENTLY — it returns, production simply
	 * never reaches it. Of the stubs in this repo that overrode it, exactly one asserted on the seam
	 * and went red; the rest passed while stubbing nothing, and two of them were still doing so after
	 * a review of the commit that added the overload — a sweep of the tree is what finds them, not a
	 * reading of the diff. Override the overload below, and where a test asserts that production
	 * reached the validator at all, assert WHICH arity it reached
	 * ({@code LlmInferenceServiceCitationWiringTest} does).
	 */
	public List<SafetyWarning> validate(String answer, String question, Patient patient,
			List<RecordMapping> mappings) {
		return validate(answer, question, patient, mappings, null);
	}

	/**
	 * The production entry point a caller uses when it intends to PUBLISH how bounded the answer's
	 * pairwise interaction list is (issue #336). Identical to
	 * {@link #validate(String, String, Patient, List)} in every other respect.
	 *
	 * @param pairExtentSink a caller-supplied one-slot accumulator the pairwise arms state their
	 *        candidate and reported counts into, or {@code null} from a caller that does not
	 *        publish it. It is the caller's per-call object and never a field: this bean is a
	 *        Spring singleton, so a field would be one slot shared by every concurrent request
	 *        (issue #172). The fail-safe below is what makes the sink's own null honest — a pass
	 *        that threw states nothing rather than stating a complete screen.
	 */
	public List<SafetyWarning> validate(String answer, String question, Patient patient,
			List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
		try {
			if (!ChartSearchAiUtils.isDrugReferenceEnabled()
					|| !ChartSearchAiUtils.getBooleanGlobalProperty(
							ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS,
							ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_VALIDATE_ANSWERS)) {
				return new ArrayList<SafetyWarning>();
			}
			PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
			return validate(answer, question, context, mappings, null, pairExtentSink);
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
	 * Mappings-aware overload, reached from the question- and answer-only seams above it and from
	 * tests. See {@link #validate(String, String, Patient, List)} for the echo-scoping contract.
	 *
	 * <p><b>It is not the seam production reaches</b>, and has not been since issue #336: the public
	 * {@code Patient} entry point delegates to the widest arity directly, because a sink has to travel
	 * with it. So overriding THIS overload does not intercept production either — the same trap the
	 * four-argument entry point above documents, one level down, and the reason that one documents it
	 * is that a sweep of the tree found two stubs already caught by it.
	 */
	List<SafetyWarning> validate(String answer, String question, PatientClinicalContext rawContext,
			List<RecordMapping> mappings) {
		return validate(answer, question, rawContext, mappings, null);
	}

	/**
	 * Five-argument seam for a caller that does not publish the pairwise extent — the internal
	 * mappings-aware overload above and {@code DrugReferenceInjector.preAnswerFindings}, whose
	 * findings go to the PROMPT rather than to a response. See the widest arity for both parameters.
	 */
	List<SafetyWarning> validate(String answer, String question, PatientClinicalContext rawContext,
			List<RecordMapping> mappings, List<DrugReference> resolvedOrderEntries) {
		return validate(answer, question, rawContext, mappings, resolvedOrderEntries, null);
	}

	/**
	 * The widest arity, and the one that builds the pass's shared state — every other delegates to it.
	 *
	 * <p><b>Two structural guards delimit this body by a literal needle, and each spells ALL THREE
	 * lines of this declaration.</b> No shorter prefix is spelled, and what each buys was measured rather than assumed. The first line alone matches THREE times — this declaration and the two arities above it that open identically — and a needle matching more than once is a hard failure in each guard's own unique-offset check ({@code SourceScan.uniqueOffset} for {@code CoMedicationResolutionPerPassTest}, and {@code ChipSubjectOneResolutionTest}'s own copy of it, which ADR Decision 54 records as deliberately not migrated). The two-line prefix is ALREADY unique, and by one character: the five-argument seam above wraps its parameters identically and ends that line with {@code )} where this one ends with a comma. So the third line is not what buys uniqueness — spelling it is what makes any re-wrap of this declaration re-target both needles loudly instead of leaving one silently landing on the seam. The tail alone names no METHOD, which is what the first line buys. Move this declaration and the needles
	 * move with it — {@code ChipSubjectOneResolutionTest} and {@code CoMedicationResolutionPerPassTest},
	 * which say so themselves.
	 *
	 * @param resolvedOrderEntries the patient's active orders ALREADY resolved to their reference
	 *        entries by a caller that needed them itself, or {@code null} from a caller that has not
	 *        resolved them — which is every caller but {@code DrugReferenceInjector.injectRecords}.
	 *        Issue #255: that method resolves the list for its own promotion predicate, for
	 *        {@code matchingEntries}' candidate set (issue #151) and for the reference names it
	 *        attaches, and then calls in here; resolving it again was a second derivation of a value
	 *        the pass already held. {@code CLAUDE.md}'s {@code findForActiveOrders} bullet prescribes
	 *        the shape — "wherever a caller already holds the resolved list, pass it down rather than
	 *        resolving again" — and it is a PARAMETER rather than a memo for issue #172's reason: this
	 *        bean is a Spring singleton, so a field would be one unsynchronized map shared by every
	 *        concurrent request.
	 *
	 *        <p>It is the transported input and not a transported CONTEXT: {@link
	 *        DrugReferenceService#withReferenceNames} is still applied below, because it costs no
	 *        dataset walk and keeping it here leaves this method the sole constructor of the context
	 *        it reasons over. A caller that handed a pre-enriched context instead would make that
	 *        construction depend on the caller having applied the right step, and a caller that got it
	 *        wrong would be silent.
	 *
	 *        <p>It is READ here and never written — every use below is an iteration or a pass-through,
	 *        and that is now load-bearing where it was not. The caller does not hand the list over: it
	 *        goes on rendering from the very same one after this returns ({@code render(ref,
	 *        orderEntries, …)}), so an arm added here that mutated it would change what the injector
	 *        puts in the chart, after the fact. The contract is therefore enforced at the producer
	 *        rather than described here —
	 *        {@link DrugReferenceService#findForActiveOrders} returns an unmodifiable list — and no
	 *        copy is taken here. Do not read that as a class-wide convention: the
	 *        {@code List<SafetyWarning> warnings} the arms below take is an ACCUMULATOR and is written
	 *        by design.
	 *
	 *        <p>Passing a list resolved from a DIFFERENT context than {@code rawContext} is the one
	 *        way to misuse this. The injector's own list is resolved from the raw context this then
	 *        receives enriched, and those answer alike — {@code withReferenceNames} writes only
	 *        {@code activeDrugReferenceNames}, which {@code findForActiveOrders} does not read.
	 *        {@code ActiveOrderResolutionPerPassTest} pins both halves: that the pass resolves once,
	 *        and that what it injects is what a self-resolving pass produces.
	 *
	 * @param pairExtentSink where the PAIRWISE arms state how many above-floor pairs they found and
	 *        how many of them {@link #maxPairChips()} let them report, or {@code null} down every
	 *        path but the one {@code LlmInferenceService} takes to publish it on the answer. It is
	 *        a caller-supplied per-call object rather than a field for issue #172's reason, the same
	 *        one {@code resolvedOrderEntries} above gives. Issue #336: without it a capped list was
	 *        indistinguishable from a complete one everywhere but the log. See
	 *        {@link PairChipExtent} for what an absent statement does and does not mean.
	 */
	List<SafetyWarning> validate(String answer, String question, PatientClinicalContext rawContext,
			List<RecordMapping> mappings, List<DrugReference> resolvedOrderEntries,
			PairChipExtent.Sink pairExtentSink) {
		List<SafetyWarning> warnings = new ArrayList<SafetyWarning>();
		// The patient's active orders resolved to their reference entries — at most ONE dataset sweep
		// per validate, and none at all where the caller has already made it (issue #255) — feeding
		// both things this pass needs from that resolution (issue #136):
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
		// never describe different sets of orders. Per validate, not per question — and since issue
		// #255 per PASS rather than per validate wherever the caller already holds the list: the
		// pre-answer findings pass reaches this method through DrugReferenceInjector.injectRecords,
		// which resolves the same orders for its own promotion predicate before calling in, and now
		// hands that resolution down instead of leaving this one to derive it again. Why the two
		// answers were the same, and why the INPUT travels and the enriched context does not, are on
		// resolvedOrderEntries above; ADR Decision 58 is canonical for both.
		List<DrugReference> orderEntries = resolvedOrderEntries != null ? resolvedOrderEntries
				: drugReferenceService.findForActiveOrders(rawContext);
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
		// Built lazily and at most once, for two consumers now: the echo test below, and the subject
		// matter the order-driven contraindication arm is scoped to. The laziness is therefore no
		// longer "the answer names no drug beyond the question's" alone — the arm needs the corpus
		// whenever it runs — but it still buys the whole of it where contraindications are off or the
		// chart records nothing an order could be contraindicated by (hasContraindicationRecords).
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

		// The rows of every substance this pass resolved, from EITHER side — the question and answer text,
		// and the patient's own orders (issue #175) — grouped by the substance each stands for; and the
		// one row each of those substances is NAMED by in this response (issue #206).
		//
		// The subject of a chip is a SUBSTANCE, not a reference row (issue #162): one clinician word
		// resolves every route/formulation row of a substance, and the arms ran once per row, so one fact
		// became one chip per row. Grouped here rather than inside each arm because an arm is what has to
		// see the whole group at once — the interaction arm's survivor rule compares rows against each
		// other, and choosing what to CALL the substance is a comparison over the group by definition.
		//
		// ONE resolution for every arm, not one per arm: the interaction chip, the dose warning and the
		// contraindication chip can all be about one substance in one response, and three arms answering
		// "which row is this?" separately is three chances to answer it differently — which is exactly
		// what issue #206 is, the contraindication arm having answered it positionally while the other two
		// went through interactionSubject.
		//
		// Per-validate locals, never fields — issue #172's rule, for the reasons DrugReferenceService's
		// class javadoc gives, NOT the getAll() hot-reload these comments used to cite, which does not
		// exist. This bean is a Spring singleton, so a field memo is one unsynchronized structure shared
		// by every concurrent request.
		Map<Object, List<DrugReference>> resolvedRows = resolvedSubstanceRows(inPlay, orderEntries);
		// The rows a substance is NAMED by, which is a strictly smaller question than the rows it is RULED
		// over, and since issue #238 a different set: the question's and the patient's own, never the
		// ANSWER's. Of every input the naming decision reads this is the one that varied between the two
		// validate passes of one request — the pre-answer findings pass calls in with an EMPTY answer
		// (DrugReferenceInjector.preAnswerFindings) and the chips pass with the real one, while the
		// question, orderEntries and the recorded names are read identically both times — so the citable
		// safety_finding record the model read could name a substance one way and the chip beside the
		// answer another. Naming off a set neither pass can disagree about closes that by construction,
		// with nothing carried from one pass to the other. See SubstanceSubjects.
		Map<Object, List<DrugReference>> namingRows = resolvedSubstanceRows(questionDrugs, orderEntries);
		SubstanceSubjects subjects = new SubstanceSubjects(namingRows, resolvedRows,
				recordedDrugNames(context));

		// One ledger for every contraindication chip this pass raises, across BOTH arms and both of
		// their call sites (the drug-in-play loop below and the order-driven arm after it) — see
		// ContraindicationChips. It has to span them: one substance's route variants can arrive as
		// several drugs in play, as several entries of one active order, or as some of each, and a
		// collapse living inside one arm would still let the other emit the siblings.
		ContraindicationChips contraindications = new ContraindicationChips(warnings, subjects);

		// Which substances may still owe the interaction arm and the dose arm their one call — "may",
		// because since the widening these also carry the substances only the ORDERS resolved, which owe
		// neither arm anything and are simply never looked up. Drained as the
		// loop below reaches each group's first row, so a substance's chips land where its first row's
		// chips have always landed and no client sees the chip sequence reshuffle — the same positional
		// promise ContraindicationChips makes. A key set apiece rather than a map apiece, because the ROWS
		// are now shared: two maps of the same groups was two chances for the two arms to be handed
		// different row sets, and a narrower set for either would let one response call one substance two
		// things. One set per arm and one toggle per set, which is the whole of that gate: an empty set
		// owes nobody a call. (Nothing pins the interactions-off branch — no test in the suite sets that
		// GP — so it is stated as what the code is, not as a property something checks.)
		//
		// A real empty HashSet rather than Collections.emptySet(): remove() on the latter happens to be a
		// no-op returning false, but that is AbstractCollection's behaviour and not a contract — Set.of()
		// throws UnsupportedOperationException for the same call — so a later "modernise the empty
		// collection" edit would turn a disabled arm into a thrown exception, which validate() catches
		// and answers with NO chips at all rather than with the ones the other arms raised.
		Set<Object> interactionsPending = warnInteractions
				? new HashSet<Object>(resolvedRows.keySet()) : new HashSet<Object>();
		Set<Object> dosePending = warnDose
				? new HashSet<Object>(resolvedRows.keySet()) : new HashSet<Object>();

		// One ledger of the (substance, partner) pairs an interaction chip has been raised for, spanning
		// the drug-in-play arm below and the screening arm at the end — see InteractionPairs. Like the
		// contraindication ledger above it is a per-validate local, and for a sharper version of the same
		// reason (issue #172, see above): a ledger records what THIS pass already raised, so a field
		// would go on suppressing every pair it had ever seen — first the second validate pass of the
		// same request, then any later patient carrying one of those pairs. Not a stale answer, a
		// missing one.
		InteractionPairs interactionPairs = new InteractionPairs();

		// One ledger of the interaction chips this pass has already STATED, in the words it stated them
		// in, spanning the same two arms — see StatedInteractionChips, which is also where the shape
		// that needs it lives. A per-pass local for InteractionPairs' own reason, one line above.
		StatedInteractionChips statedChips = new StatedInteractionChips();

		// The patient's recorded allergies resolved to the SUBSTANCES they name, ONE resolution per pass
		// (issues #193/#195). Resolved here rather than inside addAllergyContraindications, which is where
		// it used to happen: that arm runs once per subject, and the answer does not depend on the
		// subject, so a patient with several subjects resolved the same allergy list several times over —
		// and since a recorded name now also resolves each of its constituents and its parent moiety, the
		// repeat is several dataset sweeps rather than one. Same shape as orderEntries above (issue #136),
		// and a per-validate local for the same reason as the two ledgers (issue #172, see above) — plus
		// one of its own, since this memo has no key: it is a function of THIS patient's allergy tokens,
		// so a field would answer for whoever asked first. RecordedAllergenMemoScopeTest pins the shape
		// of that a functional case can see — a memo outliving the entries it was resolved from — and
		// before it nothing pinned even that. Reassigning a FIELD here once per pass stays green on it;
		// see that test's javadoc, which names what it does not cover.
		List<RecordedAllergen> recordedAllergens = warnContra
				? recordedAllergens(drugReferenceService, context)
				: Collections.<RecordedAllergen> emptyList();

		// Derived from the walk resolved just above rather than re-walked (allergicSubstanceKeys' own
		// list overload), and held for this pass exactly as that list is — a per-call local and never a
		// field, issue #172. It is leg 2 of the corroboration union both injected channels now ask
		// (issue #308); the supplier form is what lets the injector keep its lazy memo while this side,
		// which has already paid for the walk, hands over a set it has.
		final Set<Object> allergicSubstances = allergicSubstanceKeys(recordedAllergens);
		Supplier<Set<Object>> allergicSubstanceSupplier = () -> allergicSubstances;

		// The patient's co-medications, resolved at most ONCE for this pass and read by every arm that
		// needs them (issue #256). A per-pass local and never a field — see CoMedications, which also
		// says why it is lazy rather than resolved here and now.
		CoMedications coMedications = new CoMedications(context);

		for (DrugReference ref : inPlay) {
			if (warnContra) {
				// Ungated: a drug in play IS the subject matter — the question resolved it or the
				// answer proposed it — so a subject-matter gate has nothing left to decide here.
				addContraindications(contraindications, ref, context, null, allergicSubstanceSupplier);
				addAllergyContraindications(contraindications, ref, recordedAllergens);
			}
			// The rows this pass resolved for ref's substance, and the null/empty check the two-map form
			// used to get for free from remove(). It cannot fire: the map is seeded by substanceRows(inPlay)
			// and ref is FROM inPlay, so its key is present, and every group is non-empty by the time
			// resolvedSubstanceRows returns. It is kept because of what the alternative costs — validate()
			// answers a RuntimeException with an empty warning list, so one absent group would drop every
			// chip on the request rather than one arm's, which is the failure mode RaisedChip's own
			// javadoc says this class is shaped around.
			Object substance = ref.substanceGroupKey();
			List<DrugReference> rows = resolvedRows.get(substance);
			if (rows == null || rows.isEmpty()) {
				continue;
			}
			// remove(), so a substance's rows are handed to each arm ONCE — at the first of them — and the
			// pending key set is itself the already-done ledger.
			if (interactionsPending.remove(substance)) {
				// One call, not one per arm: the rule arm and the class arm can both raise a chip about
				// the same active order, so the decision of how many chips that pair gets belongs to a
				// method that sees both (issue #88).
				addInteractionWarnings(warnings, rows, subjects, context, severityFloor, orderEntries,
						interactionPairs, coMedications, statedChips);
			}
			if (dosePending.remove(substance)) {
				addOverdose(warnings, rows, subjects, context, lower, all);
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
		if (warnContra && hasContraindicationRecords(context)) {
			// The corpus is parsed HERE only where the arm can actually reach a chip. Asking the
			// precondition at the call site as well as inside the arm is one predicate and two callers,
			// never a second copy: a patient with no allergy and no condition record adds no parse of
			// its own. Not "parses nothing" — the echo loop above builds the same corpus when the answer
			// names a drug the question did not, and this only ever reuses it.
			if (citedTextsLower == null) {
				citedTextsLower = citedRecordTextsLower(answer, mappings);
			}
			addActiveOrderContraindications(contraindications, inPlay, context, orderEntries,
					recordedAllergens, new SubjectMatter(question, answer, citedTextsLower),
					allergicSubstanceSupplier);
		}
		// LAST, so the patient's own findings lead: a chip about their allergy or their active order
		// is a fact about them, and outranks a reference lookup about a pair they may not be on.
		// Held in a local and published to the caller's sink only on the normal return below, so a pass
		// that degrades cannot leave a statement about chips it did not produce: the public entry
		// answers a RuntimeException with an EMPTY warning list, and a sink written arm-by-arm would
		// then say "18 found, 10 reported" beside no chips at all. At most one of the two arms can
		// assign it — their gates are mutually exclusive, see the screening gate below.
		PairChipExtent pairExtent = null;
		if (warnInteractions) {
			pairExtent = addQuestionPairInteractions(warnings, questionDrugs, subjects, context,
					severityFloor);
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
			pairExtent = addActiveOrderPairInteractions(warnings, subjects, context, severityFloor,
					orderEntries, interactionPairs, coMedications, statedChips);
		}
		if (!warnings.isEmpty()) {
			log.info("Drug-safety validator raised {} warning(s)", warnings.size());
		}
		recordPairExtent(pairExtentSink, pairExtent);
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
	 * grouping here ({@link #bestRulePerPartner}), the two pairwise arms' chip orderings
	 * ({@link #PAIR_SEVERITY_DESCENDING} and {@link #SCREENED_PAIR_SEVERITY_DESCENDING}) and the
	 * promoted-note ordering in {@link DrugReferenceInjector.InteractionNote}; two copies could drift
	 * into ranking the same pair of rules oppositely, which is how the chip and the prompt text come
	 * to disagree.
	 *
	 * <p><b>{@link #FINDING_STRENGTH_DESCENDING} shares it as a TIEBREAK only</b>, and the difference
	 * is a real divergence rather than a wording nicety: that comparator asks
	 * {@link #licensesWithholding(SafetyWarning)} first, and the promoted-note ordering has no such
	 * key because it ranks RULES, which cannot fold. So one prompt can state a folded pair in one
	 * order among its {@code safety_finding} records and in the other inside a {@code drug_reference}
	 * record's note list — measured over {@code ddi-folded-caution-order.json}, where the chips lead
	 * with the folded Atorvastatin finding and the note list leads with Metformin. That is accepted
	 * rather than repaired: the two rank different things, and the note order decides only which rule
	 * keeps its mechanism prose under {@code MAX_INTERACTION_RENDER_CHARS}. Do not close it by giving
	 * the notes a fold key they have no way to observe.
	 *
	 * @return the rank, with null/unrecognized mapped to {@link Integer#MAX_VALUE}
	 */
	static int severityPriority(String severity) {
		int rank = severityRank(severity);
		return rank < 0 ? Integer.MAX_VALUE : rank;
	}

	/**
	 * Whether a finding of this severity is a reason to WITHHOLD the drug, or a caution to note
	 * beside giving it (issue #283). The one definition of that split, shared with
	 * {@link DrugReferenceInjector#renderFinding}, which states its answer in the record the model
	 * reads — so the strength of an answer's opening call cannot drift from the rating the chip
	 * carries. Before it, the severity reached the model only as a WORD inside the finding's prose
	 * and the prompt instructed a refusal for any finding at all: measured on the standalone,
	 * {@code main} @ b0cfe545, a Minor row produced "No — gentamicin should not be given" on a
	 * mechanism text ending "No special precautions are necessary".
	 *
	 * <p>The boundary is expressed against {@link #severityRank} rather than as a number, so it
	 * cannot fall out of step with that switch: {@code minor} and {@code unknown} are cautions — the
	 * ratings DDInter itself calls minimally significant, and {@code unknown} carries no mechanism
	 * text at all, which is why the default floor filters it out of the chips entirely.
	 *
	 * <p><b>Unrated withholds, and it is the case a "no rating means nothing serious" reading gets
	 * backwards.</b> Null is not a low rating — see {@link SafetyWarning#getSeverity()} — and it
	 * covers two different things, which withhold for two different reasons. A CURATED rule is
	 * unrated because an implementation authored it deliberately, and {@link #severityPriority}
	 * already sorts it ABOVE {@code major} for exactly that reason; softening it would silence the
	 * one arm a deployment added on purpose. An ATC-subgroup or cross-reactivity JOIN is unrated
	 * because the reference data states the relationship without rating it, and nobody authored it at
	 * all: it withholds here because that is the behaviour it already had, and softening a
	 * relationship no dataset rates would be a change nothing has measured. Neither is a caution, but
	 * do not carry the curated argument over to the join — the second is the weaker claim, and a
	 * later decision to grade those joins should be made on its own evidence.
	 *
	 * @param severity the source-assigned severity, or null where the source rates nothing
	 * @return true when the finding licenses withholding the drug
	 */
	static boolean ratingLicensesWithholding(String severity) {
		int rank = severityRank(severity);
		return rank < 0 || rank >= severityRank("moderate");
	}

	/**
	 * The same question asked of a whole FINDING rather than of a rating, and the form
	 * {@link DrugReferenceInjector#renderFinding} must use.
	 *
	 * <p>A finding can assert more than its rating covers. Issue #171's fold puts the class arm's
	 * duplicate-therapy or cross-reactivity sentence onto a rated rule's chip when both arms are about
	 * one co-medication, and {@link SafetyWarning#getSeverity()} keeps reporting the RULE's rating there
	 * on purpose — folding must not move what the pair is rated. So a Minor rule folded with a class
	 * relationship read as a caution while that relationship alone licenses withholding: the fold
	 * silently lowered a claim, and it also changed behaviour beyond what #283 set out to change, since
	 * every finding refused before it. Taking the stronger of the two leaves those pairs where they were.
	 *
	 * <p>Measured over the shipped knowledge base through the production predicates (the real
	 * {@link DdiDrugReferenceSource#parse}, {@link DrugReference#atcSubgroups()},
	 * {@link DrugReferenceService#lookupByToken}): <b>108 of the 24,690</b> Minor-rated interaction
	 * ROWS pair two drugs sharing a level-4 ATC subgroup. So this is a shape the data really carries
	 * rather than a constructed one. The count carries its base deliberately — quoting one without it
	 * is the defect #261 exists to stop — and the ROW is the unit the fold turns on, because a chip is
	 * raised per subject so either orientation can fold.
	 *
	 * <p><b>Do not restate that as a pair count by halving it.</b> This javadoc said "i.e. 54 unordered
	 * pairs held from both sides" and review measured that wrong: through the same three predicates the
	 * 108 rows are <b>56</b> unordered pairs of display names (44 by {@link DrugReference#substanceGroupKey()},
	 * 60 keyed on the raw entry-name/token strings), and they are not two rows each — 32 pairs
	 * contribute 2 rows, 18 contribute 1, 2 contribute 3 and 4 contribute 5, so 18 of the 56 are held
	 * from ONE side only. The multiplicity is the multi-row families this class's identity rules exist
	 * for: {@code Amphotericin B} has three presentation rows beside the plain one, so
	 * {@code Amphotericin B | Clotrimazole} contributes five rows while
	 * {@code Amphotericin B (liposomal) | Clotrimazole} contributes one, because clotrimazole's own row
	 * names the token {@code amphotericin b} and {@link DrugReferenceService#lookupByToken} answers with
	 * the plain row. 54 was 108/2 rather than a second measurement, which is why it reconciled with
	 * nothing.
	 */
	static boolean licensesWithholding(SafetyWarning finding) {
		return ratingLicensesWithholding(finding.getSeverity()) || finding.carriesUnratedRelationship();
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
	 * the context window. Measured on the 16-drug DDInter excerpt with the patient on nothing: one question
	 * naming all 16 raised 72 chips carrying 42,708 characters of finding text, against a path
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
	 * pair is NAMED in a WARN — a silent truncation would read to a clinician as "everything is
	 * covered". <b>The WARN is no longer the only place the cut surfaces</b> (issue #336): both arms
	 * now state how many pairs they found beside how many they reported, on the answer as
	 * {@code ChartAnswer.getPairChipExtent()} and on the wire as {@code interactionPairs}. This
	 * javadoc used to say a clinician-facing "10 of 72 shown" needed a per-question container the chip
	 * API does not have and was therefore a frontend change; the premise was half right and the
	 * conclusion wrong. The CHIP array has no such container — chips are per-drug findings — but the
	 * RESPONSE is itself the per-question container, and a key beside {@code safetyWarnings} is a
	 * module change. Rendering "10 of 18 shown" is still the frontend's, in
	 * {@code openmrs-esm-chartsearchai}; having something to render is not. What the WARN still holds
	 * alone is WHICH pairs went, and their ratings — the statement is a count, deliberately, because a
	 * list of withheld pairs on the wire is the uncapped prompt expansion this cap exists to prevent.
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
	 * <p><b>It bounds CHIPS and not WORK, and that is settled rather than pending</b> (issue #256).
	 * Every candidate pair is enumerated and evaluated before the cut, because the cut is defined as
	 * "the least severe go" and nothing knows a pair's rating until it has been evaluated — so an
	 * early stop would change WHICH pairs are dropped, which is the one property this cap exists to
	 * guarantee. Asking whether it could stop early was #256's own discriminator, and the answer is no.
	 * <b>It also does not matter</b>, which is the part worth recording here: measured through the real
	 * {@code validate} over the shipped knowledge base, the SAME question against a chart with no
	 * active orders at all costs 30 ms of a 490 ms ten-drug pass on a 43-order chart, roughly 6% — and
	 * that 30 ms is the drug-in-play arms and the pairwise ones together, so it is an upper bound on
	 * these rather than a measurement of them. What grew with the question was the co-medication
	 * resolution the arms above run per in-play substance — see {@link CoMedications}, which is where
	 * #256's fix went. Do not re-derive the misreading from the cap's name.
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
	 * States a pairwise arm's extent into the caller's sink, where there is one and the arm ran.
	 *
	 * <p>Called ONCE, on {@code validate}'s normal return, from a local both arms assign — never per
	 * arm. That is what makes the statement atomic with the chips: the public entry answers a
	 * RuntimeException with an empty warning list, so a sink written as each arm finished could
	 * describe a screen whose chips were then discarded (issue #336).
	 *
	 * <p>A {@code null} extent means no pairwise arm enumerated anything, which is not the same as an
	 * arm having enumerated nothing: an arm that ran and found no above-floor pair states
	 * {@code found == 0}, a complete screen. See {@link PairChipExtent}.
	 */
	private static void recordPairExtent(PairChipExtent.Sink sink, PairChipExtent extent) {
		if (sink != null && extent != null) {
			sink.record(extent.getFound(), extent.getReported());
		}
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
	 *         {@link #addActiveOrderContraindications}, so the exemption can no longer withhold a
	 *         contraindication for a drug the patient is on.
	 *
	 *         <p><b>Why that still holds now the arm is scoped.</b> It used to read "whatever the
	 *         answer's wording", which {@link SubjectMatter} falsified: that arm no longer runs on a
	 *         response it has nothing to say about. The bound survives, and by a tighter argument than
	 *         the wording one it replaces. The corpus this method asks about is {@code citedTextsLower},
	 *         and {@code validate} hands that same list to the {@code SubjectMatter} constructor, which
	 *         only ever ADDS the question and the answer on top of it. So this returning true implies
	 *         {@code SubjectMatter.names(ref)}: whenever the exemption withholds a drug, the very record
	 *         that triggered the exemption has already put that drug in subject matter, and the arm
	 *         checks it. The two are not merely usually in step, they are one containment — which is
	 *         also what a later change has to preserve, since narrowing what the arm reads to less than
	 *         the cited records would reopen issue #143 with nothing going red.
	 *
	 *         <p>The second half of the bound USED to be asserted here of "the order-driven arms", and
	 *         was false (issue #143). Counted over this class, those arms — {@link #addInteractionWarnings},
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
		return namesAnyOf(citedTextsLower, ref);
	}

	/**
	 * @return whether any of {@code texts} names {@code ref}, by the PROSE rule
	 *         ({@link DrugReference#matchesText}) because every one of them is prose — a question, an
	 *         answer, or a rendered chart record. Extracted so {@link #isEchoOfCitedRecord} and
	 *         {@link SubjectMatter} cannot answer "does this text name this drug" two ways; they
	 *         differ only in WHICH texts they ask about, which is the whole distinction between
	 *         issue #105's echo test and subject-matter scoping.
	 */
	private static boolean namesAnyOf(List<String> texts, DrugReference ref) {
		for (String text : texts) {
			if (ref.matchesText(text)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * What THIS response is about: the question, the answer, and the records the answer cited.
	 *
	 * <p><b>Why the active-order contraindication arm needs one.</b> chartsearchai answers questions;
	 * it is not an alerting system, and this class's own contract is a check that runs after the
	 * answer and ANNOTATES it. Issue #143 widened one arm past that: it walked every active order
	 * against every recorded allergy and condition <em>whatever the question and the answer named</em>.
	 * Measured live on the 3.7.1 standalone, four different questions — about allergies, interactions,
	 * cancer and a date of birth — returned the same two contraindication chips byte for byte. The
	 * finding is the INVARIANCE, not that all four were off-topic: two of them were squarely about
	 * drugs. The chips simply did not depend on what was asked, which is how a reader learns to skip
	 * the box. The finding it was carrying is real and belongs on a surface
	 * with subscription and acknowledgement (order entry, a chart banner, CDS hooks), not on every
	 * answer this module happens to produce.
	 *
	 * <p><b>Both sides count, and that is the point.</b> A contraindication relates a DRUG to a
	 * recorded FINDING, and either can be what was asked about. Scoped to the drug alone it would lose
	 * a chip whose allergy is the subject and whose drug is never written; scoped to the finding alone
	 * it would lose issue #143's own case, a prescribed drug named only by the cited {@code drug_order}
	 * record that issue #105's echo rule keeps out of the in-play set.
	 *
	 * <p><b>The three widenings are signals, never gates.</b> A medication-domain question makes the
	 * whole active-order list subject matter, and an allergy- or conditions-domain question makes the
	 * corresponding recorded findings subject matter, because there the LIST is the topic even where the
	 * prose writes no individual name. All three go through {@link QueryScopeRouter}'s own
	 * classification rather than a second vocabulary here. None can narrow anything: a question naming a
	 * drug outright carries no cue word at all ("Can I give her bupivacaine?") and is answered by the
	 * drug-in-play arm before this one runs.
	 *
	 * <p><b>Three and not two, and one per LIST.</b> A contraindication's recorded side is an allergy
	 * OR a condition, so the argument above reads identically for both and leaving conditions out made
	 * the gate asymmetric for a reason nobody could state: "does she have any allergies?" promoted a
	 * finding into the prompt and "what conditions does she have?" did not. Where that bit is the
	 * pre-answer pass, which has no answer text at all — with an answer, an enumeration usually spells
	 * the condition out and {@link PatientClinicalContext#containsToken} reaches it unaided. They stay
	 * three separate signals rather than one "asks about her chart", because a conditions question that
	 * also put her allergy records in scope would be the reported defect again one level up.
	 */
	private static final class SubjectMatter {

		/** Question, answer and cited-record texts, lowercased — every one of them prose. */
		private final List<String> texts;

		private final boolean coversActiveOrders;

		private final boolean coversRecordedAllergies;

		private final boolean coversRecordedConditions;

		private SubjectMatter(String question, String answer, List<String> citedTextsLower) {
			List<String> collected = new ArrayList<String>();
			if (question != null && !question.trim().isEmpty()) {
				collected.add(question.toLowerCase(Locale.ROOT));
			}
			if (answer != null && !answer.trim().isEmpty()) {
				collected.add(answer.toLowerCase(Locale.ROOT));
			}
			collected.addAll(citedTextsLower);
			this.texts = collected;
			this.coversActiveOrders = QueryScopeRouter.asksAboutMedications(question);
			this.coversRecordedAllergies = QueryScopeRouter.asksAboutAllergies(question);
			this.coversRecordedConditions = QueryScopeRouter.asksAboutConditions(question);
		}

		/** Whether an active order is what this response is about. */
		private boolean names(DrugReference ref) {
			return coversActiveOrders || namesAnyOf(texts, ref);
		}

		/**
		 * Whether the finding a MATCHED rule fired on is what this response is about — asked of the
		 * rule's own token and never of the patient's whole list, because a response citing one
		 * condition while a rule fires on another is the reported defect again at token granularity.
		 * Through {@link PatientClinicalContext#containsToken}, the matcher that decided the rule
		 * matched at all, so the two cannot drift.
		 *
		 * <p>The domain widening is asked of the rule's own TYPE for the same reason: a rule is put to
		 * one of the two chart lists ({@link #recordedContraindicationKind}) and only the question that
		 * covers THAT list has widened it. Both legs read the same two predicates the matcher does, so a
		 * vocabulary that grew a synonym cannot keep matching rules while the widening quietly stopped
		 * applying to them.
		 */
		private boolean names(DrugReference.Contraindication c) {
			if (coversRecordedAllergies && isAllergyRule(c)) {
				return true;
			}
			if (coversRecordedConditions && isConditionRule(c)) {
				return true;
			}
			return PatientClinicalContext.containsToken(texts, c.getToken());
		}

		/** Whether a recorded allergen — the entries one charted allergy resolved to — is subject matter. */
		private boolean namesRecordedAllergen(List<DrugReference> allergen) {
			if (coversRecordedAllergies) {
				return true;
			}
			for (DrugReference entry : allergen) {
				if (namesAnyOf(texts, entry)) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * What one RESPONSE calls each substance a {@code validate} pass resolved: {@link #interactionSubject}
	 * over the rows the question and the patient's own orders resolved for that substance, asked once and
	 * remembered — for a substance those rows reach at all. Not over the rows the ANSWER put in play for
	 * such a substance — those decide what the arms RULE on and not what they call it, which is issue
	 * #238 — see the paragraph beginning "And issue #238 makes the fold invariant". That is the ordinary
	 * case and not a universal one: a substance in play ONLY because the answer named it has no such rows
	 * to remember, and for exactly that substance {@link #groupOf} folds its answer-only rows instead — see that
	 * method's own javadoc, which states the narrower, correct claim.
	 *
	 * <p><b>Issue #206.</b> Five arms of this class name a subject. Three read this — the drug-in-play
	 * interaction chip (issue #162), the dose warning (#174 site 4) and the contraindication chip — and
	 * the third of them answered POSITIONALLY, keeping whichever row reached it first. On the shipped KB
	 * that disagreed with the other two for the families whose route-unspecified row is not the dataset's
	 * first; since issue #194 anchored the other two on the chart it disagreed wherever the CHART names
	 * some other row, which is a property of the patient's data rather than of the dataset. Either way
	 * one response called one substance two things.
	 *
	 * <p>A shared lookup rather than three calls to one method, because equal answers from three call
	 * sites are equal only while their INPUTS stay equal: the row group and the recorded names are both
	 * arguments, and it is a narrowed row set at one site that this class's own history says to expect
	 * (issues #162, #174, #175 are all "this arm was handed fewer rows than that one"). For every arm
	 * that reads this — three at #206, all five since #236 — the group is looked up rather than passed,
	 * so a caller cannot supply a different one.
	 *
	 * <p><b>All five arms read it, since issue #236.</b> The two pairwise arms
	 * ({@link #addQuestionPairInteractions}, {@link #addActiveOrderPairInteractions}) resolved their own
	 * subject over that arm's OWN rows — the question's drugs and the order-resolved entries respectively
	 * — and since issue #175 those are never WIDER than the group here, which also carries the rows the
	 * CHART resolved. So a substance the question names by a word only one of its rows publishes, and
	 * whose other rows the chart or the answer resolves, was named one thing by a pair chip and another
	 * by every other chip in the same response. Measured through the real {@code validate} over
	 * {@code drug-reference-charted-substance-row.json}: {@code Amoxicillin interacts with Warfarin, also
	 * named in the question} beside {@code The stated Amoxicillin (suspension) dose ~4000 mg/day …}, one
	 * substance, two names, one response ({@code OrderedSubjectRowTest
	 * .theQuestionPairChipNamesTheSubstanceTheOtherArmsName}).
	 *
	 * <p>What neither arm lost is how it chooses which RULE to quote, and that is a different mechanism in
	 * each: {@link #substanceRows} over its own order list for the screening arm, and — for the
	 * question-pair arm, which calls {@code substanceRows} nowhere — candidates keyed by
	 * {@link #pairKeyNames}/{@link #unorderedPairKey} and ranked by {@link #outranks} on the walked ROW.
	 * Both are #175's and #189's axis and both move chip COUNTS. Only the NAME moved.
	 *
	 * <p><b>The shared {@code namingGroups}/{@code allGroups} lookup is what makes that structural rather
	 * than coincidental</b>, and the memo is not: {@link #subjectOf} is {@link #interactionSubject} over
	 * {@link #groupOf}{@code (substance)} and {@code recordedNames}, both fixed for the pass and folded by
	 * a pure static method, so every arm gets the same answer whoever asks first. What the memo buys is
	 * that no arm REFOLDS a group another has
	 * already folded — including within one arm, which is why {@link #addContraindications} may ask once
	 * per MATCHED rule (see its own comment there), and within one iteration of {@code validate}'s
	 * drug-in-play loop, where the interaction and dose arms ask about the same substance in turn.
	 * Measured by deleting the {@code put}: the whole api suite stays green, so it is a cost property and
	 * nothing about the one-name-per-substance property rests on it.
	 *
	 * <p><b>And issue #238 makes the fold invariant to the ANSWER within each pass — never a shared answer
	 * across the whole REQUEST, which is why the rows this folds are not the rows the arms rule
	 * over.</b> {@code validate} runs twice for one {@code /search} — the pre-answer findings pass
	 * through {@code DrugReferenceInjector.injectRecords}, then the chips pass. Before this issue, the
	 * group differed between them precisely in the rows the ANSWER put in play (issue #175 admits them
	 * deliberately): where the answer resolved a row of an in-play substance that the question did not,
	 * the group grew for that pass alone, so the injected {@code safety_finding} the model READ could
	 * name a substance one way and the chip beside the answer another. {@code namingRows} closes that —
	 * it folds only {@code questionDrugs} and {@code orderEntries}, neither of which the answer-side
	 * widening touches, so the two passes now fold the identical rows for the identical substance. By
	 * design, not in total: {@code orderEntries} is read from the pass's own
	 * {@link PatientClinicalContext} exactly as the recorded names are, so an order that stops during
	 * the LLM call still moves that input between the two passes — see the paragraph beginning "And the
	 * question-pair arm since issue #236", which says the same thing of the recorded names. That is a
	 * chart-read residue #238 does not close, not the answer-driven one this paragraph is about.
	 *
	 * <p><b>And the question-pair arm since issue #236</b>, which is what that change COST until issue
	 * #238 closed it. Folding this arm's own rows made it invariant to the ANSWER —
	 * {@code questionDrugs} is a function of the question, which both passes share — and that is the
	 * only sense of "pass-stable" this paragraph and the one below claim, for either arm. Not
	 * byte-identical across the passes, which nothing here ever established: the two passes build two
	 * {@link PatientClinicalContext}s, so the recorded names this fold ranks by could already move
	 * between them for reasons that have nothing to do with #236. So the injected
	 * {@code safety_finding} the model reads and the chip beside the answer COULD name that substance
	 * differently — the residue the paragraph above already accepted for three arms, on a fourth. ADR
	 * Decision 49 is canonical for the measurement that showed it live. <b>That residue is closed as of
	 * issue #238 (ADR Decision 53), for this arm along with the other three</b>: every substance this
	 * arm asks {@link #subjectOf} about is one of {@code questionDrugs}, which {@code namingGroups}
	 * always covers, so its naming decision never falls through to {@code allGroups}'s answer-widened
	 * rows and the ANSWER cannot move it. What #238 did not touch is the caveat two sentences up — a
	 * chart read that genuinely differs between the two passes is a different residue from the one this
	 * paragraph is about.
	 *
	 * <p>The SCREENING arm keeps its pass-stability, and structurally rather than by luck: on a
	 * screening question {@code questionDrugs} is empty by the arm's own gate — see the paragraph
	 * beginning "That is a statement about the screening arm's OWN chips" — so its {@code namingRows}
	 * reduces to {@code resolvedSubstanceRows(<empty set>, orderEntries)}: byte-for-byte the same map
	 * the arm already builds for itself, {@code substanceRows(orderDrugs)}, same membership and the same
	 * order (see the comment at that arm's own read, near {@code substances = substanceRows(orderDrugs)}).
	 * {@link InteractionPairs} plays no part in this — it dedups PAIRS the drug-in-play arm already
	 * reported, never subject groups — so every pair this arm still reports has a subject whose group is
	 * the orders' alone.
	 *
	 * <p>That is a statement about the screening arm's OWN chips and not about a screening QUESTION,
	 * which the sentence above would otherwise be read as. This javadoc used to say the pair this arm
	 * stands down from is chipped by {@link #addInteractionWarnings} instead, "whose subject is folded
	 * over the answer-widened group", so the pre-answer finding could come from here and the post-answer
	 * chip from there, naming one substance two ways across the arm handoff. <b>That cannot happen, as of
	 * issue #238.</b> The substance a pair like this is about is an ORDERED one —
	 * {@code questionDrugs} is empty by this arm's own gate, so every substance the screening arm reaches
	 * is one {@code orderEntries} names — and {@code namingGroups}, built from
	 * {@code orderEntries} alone, always has that substance's group. The two arms
	 * therefore fold the identical group for that substance in both passes.
	 *
	 * <p>A PARTNER-label asymmetry used to survive at that handoff, and issue #339 closed it: an
	 * unfolded chip from {@link #addInteractionWarnings} named its partner by the rule's own match token
	 * while a folded one named it by {@link #reconciledPartnerName}, so one prescription answered to two
	 * names depending on whether a class sentence had folded onto the chip — measured across the two
	 * arms, the same pair read {@code active order Diclofenac} for a drug-in-play question and
	 * {@code active order diclofenac} for a screening one. Both arms now put that same question through
	 * {@link #reconciledPartnerFor}, so the asymmetry is not answer-driven and is no longer arm-driven
	 * either. What is left is where that method declines to answer — an order the loaded dataset covers
	 * no entry for — and it declines identically in both arms.
	 *
	 * <p>This javadoc used to reject closing that gap here at all, calling it "not designed around, and
	 * the alternative is worse" on two grounds: that naming from a pass-invariant set while RULING from
	 * the whole group is the two-row-sets shape this class exists to remove, one level up; and that
	 * closing it properly means deciding a substance's subject once per REQUEST rather than once per
	 * pass, which is the injector's and the inference service's business rather than this class's.
	 * <b>Both grounds are wrong, and each for its own reason (ADR Decision 53) — it is the REJECTION that
	 * is retracted below, not the gap, which #238 closes inside this class.</b>
	 *
	 * <p>The two-row-sets ground is wrong: the shape issues #162/#174/#175 removed is arms RULING from
	 * different row sets, whose harm is a milder rule surviving where a more severe one existed (issue
	 * #86's direction), and no arm's candidate set moves here. Naming from a set narrower than the one
	 * that decides content is moreover already this module's design, stated for the out-of-class caller
	 * in {@link #interactionSubject(List, PatientClinicalContext)}'s own javadoc — "deliberately NOT the
	 * narrower set it decides to INJECT … what a substance is CALLED may not depend on them" — and the set
	 * this folds IS the union that caller asks over.
	 *
	 * <p>The "injector's and the inference service's business" ground is wrong too: of every input the
	 * naming decision reads, exactly one varies between the two passes from INSIDE {@code validate} —
	 * {@code answer}. The question is the same string at both call sites; {@code orderEntries} is
	 * {@code findForActiveOrders(rawContext)}, reading the order's ATC codes and
	 * {@code getActiveDrugNames()}, neither of which the answer-side widening touches; and
	 * {@code recordedDrugNames(context)} is {@code getActiveDrugNames()} again. So the pass-varying input
	 * can be, and is, removed INSIDE {@code validate} — per pass, with nothing carried between the two
	 * passes and no new parameter on the public entry point — which is exactly what {@code namingRows}
	 * does. Closing this was never the injector's or the inference service's business to begin with; it
	 * needed no cross-pass carrier at all.
	 *
	 * <p><b>What it does cost, stated because it is a real trade.</b> {@link #addOverdose} tries the
	 * SUBJECT's own band first, so moving the subject moves which of a substance's published ceilings a
	 * dose chip quotes: over a fixture whose rows publish 3000 and 2000 mg/day, a stated 4000 mg/day is
	 * now reported against the named row's own 3000 rather than the answer-widened subject's 2000. That
	 * ceiling has followed the subject since #206 by that arm's own design, no warning is lost (the walk
	 * after the subject still reaches every row, and {@link #ceilingAttribution} says whose ceiling it
	 * quoted), and the alternative would have the dose arm name a substance differently from the other
	 * two, which is this class's whole reason to exist. Both directions are pinned by
	 * {@code PerRequestSubstanceSubjectTest}.
	 *
	 * <p>Still narrow to reach, and still reasoned rather than counted: it needs a family whose rows
	 * publish DIFFERENT alias sets, so that a question and an answer using different aliases resolve
	 * different rows. This used to name {@code Estrone sulfate (topical)} as such a family, on the
	 * strength of its publishing {@code estrone}; measured through the real
	 * {@code DdiDrugReferenceSource.parse} of the shipped knowledge base, that family does NOT pose it —
	 * its unqualified sibling publishes {@code estrone} too, because the {@code ddinter} parser builds
	 * both rows' aliases from one {@code rxnorm_name}. So the shape wants a curated
	 * {@code sourceFormat=json} dataset, which is what {@code PerRequestSubstanceSubjectTest}'s fixture
	 * is, or a {@code ddinter} family whose {@code rxnorm_name} is not word-bounded inside the display
	 * name (the {@code Omeprazole}/{@code esomeprazole} shape). No live instance was ever observed, and
	 * no count over the shipped KB is published here — the property is that the two passes stop disagreeing
	 * over {@code answer}, which does not depend on how often they would otherwise have differed.
	 *
	 * <p>Memoised for the pass and not beyond it, which #238 does not change: each pass resolves the rows
	 * and the recorded names for itself, so the memo may not outlive the pass that built it. The memo below IS a field, of an object
	 * {@code validate} constructs per pass — which is the shape issue #172's rule asks for, and the rule
	 * binds the step
	 * this must never take: hoisting it onto the VALIDATOR, where {@link DrugReferenceService}'s class
	 * javadoc gives the reasons. Not the {@code getAll()} hot-reload this used to cite, which does not
	 * exist.
	 *
	 * <p><b>One instance per pass, and that is now pinned rather than described.</b> Every property above
	 * rests on the arms sharing ONE of these, which nothing stated until
	 * {@code ChipSubjectOneResolutionTest} began asserting that {@code new SubstanceSubjects(} appears once
	 * in this file and inside {@code validate}. An arm re-constructing this over its own row group needs no
	 * call to {@link #interactionSubject}, so the caller scan beside it does not see that edit — and it is
	 * exactly issue #236's split, in the class written to close it.
	 */
	private static final class SubstanceSubjects {

		/** The rows the naming decision folds — the question's and the patient's own orders' (issue #238),
		 *  never the ANSWER's, which was the input that moved this answer between the two
		 *  {@code validate} passes. */
		private final Map<Object, List<DrugReference>> namingGroups;

		/** Every row this pass resolved, including the ones only the ANSWER put in play — the fallback
		 *  for a substance {@link #namingGroups} has no group for, i.e. one in play only because the
		 *  answer named it. */
		private final Map<Object, List<DrugReference>> allGroups;

		private final Collection<String> recordedNames;

		private final Map<Object, DrugReference> subjectBySubstance =
				new HashMap<Object, DrugReference>();

		SubstanceSubjects(Map<Object, List<DrugReference>> namingGroups,
				Map<Object, List<DrugReference>> allGroups, Collection<String> recordedNames) {
			this.namingGroups = namingGroups;
			this.allGroups = allGroups;
			this.recordedNames = recordedNames;
		}

		/**
		 * @return the row {@code row}'s substance is named by in this response — {@link #groupOf}'s rows
		 *         folded, or {@code row} itself when NEITHER map grouped any rows for it, which is the
		 *         answer every arm gave before there was a group to choose from and keeps a caller holding
		 *         an ungrouped row honest rather than null.
		 *
		 *         <p>That fallback is deliberately NOT memoised, and <b>the caller it was written against
		 *         arrived with issue #339</b>. It used to say the branch was unreachable, on the ground
		 *         that every arm asked about a row of a substance {@link #allGroups} groups — that map is
		 *         the one {@code validate} walks to reach the arms at all. The PARTNER slot is not that:
		 *         {@link DrugSafetyValidator#classPartnerName} and
		 *         {@link DrugSafetyValidator#reconciledPartnerName} ask about a co-medication's
		 *         {@code OrderPartner#labelEntry}, and {@code addPartnersForUnmappedOrders} can resolve
		 *         one from an ORDER's own names while {@code findForActiveOrders} — the only source
		 *         {@code allGroups} draws its order rows from — reads the context's FLATTENED name and
		 *         code sets and reaches nothing. Measured by making this branch throw and running the api
		 *         suite: 4 cases reach it, every one of them a hand-built context carrying an active order
		 *         and neither flattened set (production's builder assembles that union out of the
		 *         per-order names, so it is chiefly a test shape). The answer there is the row the caller
		 *         was given, which for those callers is the ladder's own label row — exactly the string
		 *         the chip printed before either of them existed, so the degradation is to the previous
		 *         behaviour rather than to a wrong one. Were it memoised, that POSITIONAL answer would be
		 *         cached under the substance's key and handed back to every arm that asks afterwards,
		 *         with the group lookup skipped — issue #206 re-created by the class written to prevent
		 *         it, silently and with no test reddening.
		 *         Note which map that is: a substance MISSING from {@link #namingGroups} is ordinary since
		 *         issue #238 (one the answer alone named), and {@link #groupOf} answers it from
		 *         {@code allGroups} rather than falling through to here.
		 *
		 *         <p>{@code null} is therefore the memo's MISS sentinel rather than a stored answer, which
		 *         is sound because the memoised branch cannot produce one: {@link #interactionSubject}
		 *         answers null only for an empty group ({@link #strongestClaimants}), and an empty group
		 *         takes the un-memoised fallback.
		 */
		DrugReference subjectOf(DrugReference row) {
			Object substance = row.substanceGroupKey();
			DrugReference subject = subjectBySubstance.get(substance);
			if (subject != null) {
				return subject;
			}
			List<DrugReference> group = groupOf(substance);
			if (group == null || group.isEmpty()) {
				return row;
			}
			subject = interactionSubject(group, recordedNames);
			subjectBySubstance.put(substance, subject);
			return subject;
		}

		/**
		 * @return the rows this response NAMES {@code substance} by: the naming group, else every row this
		 *         pass resolved for it, else {@code null} for a substance neither map groups.
		 *
		 *         <p>The fallback is reached by exactly one shape — a substance in play only because the
		 *         ANSWER named it, which the question did not resolve and the patient is not on — and it
		 *         cannot make the two passes disagree over the ANSWER, because the pre-answer pass
		 *         validates with an empty answer and so never reaches this branch at all. It folds that substance's
		 *         answer-only rows rather than falling through to {@code subjectOf}'s positional
		 *         {@code row}, because within ONE pass those rows still have to be named alike, which is
		 *         issue #206's own property.
		 *
		 *         <p>Emptiness is checked and not only nullness: {@link #resolvedSubstanceRows} never
		 *         stores an empty list, so today the two are the same test, and asking both is what keeps
		 *         a naming group that could be empty from silently answering for the whole group.
		 */
		private List<DrugReference> groupOf(Object substance) {
			List<DrugReference> naming = namingGroups.get(substance);
			if (naming != null && !naming.isEmpty()) {
				return naming;
			}
			return allGroups.get(substance);
		}
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
	 * own identity, so no two ROWS group for the {@code atc} adapter or the shipped curated
	 * {@code json} dataset — the FINDING side still folds there, which is what the paragraph after
	 * next is about. The finding side is what the arm actually compared — the resolved allergen's
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
	 * it is filed in the allergy arm's key space instead of the rule one, and the two collapse. On
	 * {@code sourceFormat=json} — the shipped default when this was measured, and since ADR Decision 36
	 * the format a deployment selects for dosing — that shape is 3 of the file's 4 entries (Gentamicin's
	 * allergy rule names a class, which is why it was the control), and each of the three double-reported
	 * one allergy: {@code Ibuprofen is contraindicated by an active allergy: documented ibuprofen
	 * allergy} beside {@code The patient has a recorded allergy to Ibuprofen.} — with no non-default
	 * configuration needed to see it. What is keyed is the FACT and never "both
	 * arms fired", which is what leaves a class-level rule its own chip beside the folded one for a
	 * patient recorded as allergic to both the drug and its class.
	 *
	 * <p>The test is name IDENTITY between two REFERENCE strings — a curated token against the entry's
	 * own alias list — so it is {@link DrugReference#isNamed}, the same predicate {@link #namesEntry}
	 * asks of an interaction rule's token. Deliberately NOT
	 * {@link DrugReferenceService#findImpliedSubstances}: that answers which substances a RECORDED name
	 * denotes and widens deliberately (issues #193/#195/#209), and applying it to a curated token is
	 * exactly the wholesale resolution the paragraph above rules out. The fold is bounded to its own
	 * subject by WHOM the question is asked OF, not by the predicate — {@code isNamed} is true of every
	 * entry aliasing the token — and it is asked of the entry the rule is filed on, keying on that
	 * entry's substance, so no rule can fold onto any other.
	 *
	 * <p><b>Which chip survives.</b> The most specific relationship, since that is this arm's analogue
	 * of "the highest severity wins" — a contraindication chip carries no severity, and what it can
	 * under-report is the STRENGTH of the claim: identity (the patient is allergic to this very drug)
	 * over a shared ATC class over a shared curated group. Identity is the RELATIONSHIP and not a fixed
	 * wording — since issue #268 that rank states it in one of two sentences, depending on whether the
	 * recorded name names the row. Where a self-named curated rule joins that
	 * space it is ranked by what it ADDS, not by which arm produced it — issue #88's finding that "arm X
	 * yields to arm Y" is the wrong dedup whenever the yielding arm can be the one carrying the content.
	 * A rule with a note of its own says the identity fact in the deployment's own words and outranks it;
	 * a rule with none renders its own token back ({@link ChartSearchAiUtils#firstNonBlank}, "…:
	 * ibuprofen") and is outranked, so it survives only where the allergen arm resolved nothing to this
	 * SAME substance — a class or group chip about a DIFFERENT allergen is a different key and stands
	 * beside it. Since issue #223 a note is necessary and not sufficient: the allergy RECORD the rule
	 * matched must also NAME that drug, because the fold's premise is that it reports the allergen arm's
	 * fact and the match that files it here is bare containment. See {@link #contraindicationRank}, which is where
	 * the whole choice lives. Ties keep the incumbent, so a group of equally-related rows is reported
	 * with the dataset's first such row's CONTENT. The surviving chip is written back into the position the
	 * group's first candidate took, so no client sees the chip sequence reshuffle when a later, stronger
	 * row replaces an earlier one.
	 *
	 * <p><b>What the chip CALLS that substance is a separate question, and not a positional one (issue
	 * #206).</b> It is {@link SubstanceSubjects} — {@link #interactionSubject} over the substance's rows,
	 * the same answer the interaction chip (issue #162) and the dose warning (#174 site 4) get, resolved
	 * once for the pass and — since issue #238, the naming rows being a function of the question and the
	 * orders alone — no longer moved between the pre-answer pass and the chips pass by the ANSWER. This arm
	 * used to name the chip after whichever row reached it first, which
	 * disagreed with those two for the shipped families whose route-unspecified row is not the dataset's
	 * first, and — once issue #194 anchored them on the chart — wherever the chart names some other row,
	 * i.e. on a property of the patient's data rather than of the dataset. Renaming, not re-counting:
	 * every row of one substance answers {@link DrugReference#substanceGroupKey()} alike, so moving the
	 * NAME to another row of the same group cannot move the key, which is why the subject is resolved
	 * through {@link ContraindicationChips#subjectOf} and handed to {@link ContraindicationChips#add} as
	 * the very row it keys on rather than resolved beside it.
	 *
	 * <p>The chip RANKED as identity is exempt, and stays named after the patient's own recorded
	 * allergen (issue #164): that sentence quotes an allergy RECORD, and naming the row the chart records
	 * is what makes a finding truthful — issue #187 settled it and #192 re-measured it at a charted
	 * {@code Ketorolac (ophthalmic)} allergy the fold renames wrongly. Both are chart-anchored, on
	 * different records; only the chips ASSERTING something about the drug being checked take the shared
	 * subject.
	 *
	 * <p>Resolving a tie by position is lossless rather than merely tidier, and that rests on the rows
	 * of one substance publishing the SAME ATC codes — which the shipped KB does for every group this
	 * key merges, and which is the thing to re-measure before widening the key, since the class chip
	 * names a code and a divergent row's code would be dropped unheard. Curated-group membership needs
	 * no separate check: {@link CrossReactivityGroup#groupsOf} is a pure function of those same codes,
	 * so equal codes are equal membership. What is left for a tie to choose between is then, since issue
	 * #206 took the label out of its hands and issue #146 put self-named rules in this space,
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
	 * key. Since issue #146 the replacement branch has a LIVE route again — a self-named rule outranks
	 * identity, and a dataset authoring that rule on a row that is not its family's first raises the
	 * identity chip before the rule reaches the same key, which
	 * {@code SelfNamedAllergyRuleFoldTest.aRuleOnALaterRowReplacesTheIdentityChipInPlace} exercises. So
	 * {@code warnings.set} is not dead code, whatever becomes of the class case below.
	 *
	 * <p>That class case is the OTHER thing left to replace: a class chip by a more specific class chip
	 * — a group whose rows publish DIFFERENT ATC codes, so that one shares only a curated group with
	 * the allergen while another shares a level-4 subgroup. The shipped KB has no such group (0 of the
	 * 129 it files as more than one row; measured 2026-08-07 and the same 0 before this key widened),
	 * so that branch alone is currently unexercised rather than wrong, and it is kept because "the
	 * most specific relationship survives" is this arm's contract and first-wins is not: a refresh that
	 * diverges one row's codes would otherwise silently report the weaker relationship. Re-measure that
	 * 0 rather than trusting it — it is a property of the dataset, not of this code.
	 */
	private static final class ContraindicationChips {

		/** A curated allergy rule NAMING THE SUBSTANCE it is filed against and carrying a note of its
		 *  own (issue #146), where the allergy RECORD it matched also names that drug (issue #223 — per
		 *  matched record, never over the allergy list as a whole; see {@link #contraindicationRank}): the identity
		 *  relationship below, stated in the deployment's own clinical wording. Above {@link #IDENTITY}
		 *  because it says the identity fact and the note besides, which nothing else in this ledger can
		 *  reproduce — a deployment authoring {@code drug-reference.json} is recording exactly that
		 *  wording, and a fold that kept the module's stock sentence would silently discard it.
		 *
		 *  <p>Its rank guard asks whether the matched record names the entry with
		 *  {@link DrugReference#matchesDrugName}, which scans every alias, while issue #268 asks the
		 *  narrower {@link DrugReference#labelNameOccursIn} of the sentence below. So a record matching
		 *  through a borrowed alias can hold this rank while {@link #IDENTITY} would have declined to
		 *  state identity. The two cannot contradict each other — only one rank ever renders, and this
		 *  one's sentence is itself relationship-shaped ("X is contraindicated by an active allergy:
		 *  …"), asserting the contraindication rather than an allergy to X. */
		static final int SELF_NAMED_RULE = 4;

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

		/** A curated contraindication rule that does NOT name its own entry. Its own key space, so this
		 *  rank never competes with any of the identity-key ranks — not the four declared above it nor
		 *  the one below — and it shares a VALUE with {@link #SAME_GROUP} for that reason rather than in
		 *  spite of it. It exists so every call reads alike. */
		static final int CURATED_RULE = 1;

		/** {@link #SELF_NAMED_RULE} with no note of its own — the same claim, rendered as the rule's own
		 *  token ("Ibuprofen is contraindicated by an active allergy: ibuprofen"), which says strictly
		 *  less than {@link #IDENTITY}. What it has to be is BELOW identity; 0 rather than 2.5 is a
		 *  reading convenience and nothing more, because {@link #SAME_CLASS} and {@link #SAME_GROUP} can
		 *  never share this key — {@link #addAllergyContraindications} reaches them only after
		 *  {@link #firstOfSameSubstance} returned null, so their finding is never the subject's own
		 *  substance. What IS load-bearing is that the chip is still RAISED: the arms are independent and
		 *  this rule fires on evidence the allergen arm need not reproduce
		 *  ({@link PatientClinicalContext#hasAllergyToken} is bare containment where
		 *  {@link DrugReferenceService#findImpliedSubstances} is boundary-aware), so dropping it would
		 *  have made a curated rule conditional on an arm that never gated it. */
		static final int SELF_NAMED_RULE_WITHOUT_A_NOTE = 0;

		/** A {@link #SELF_NAMED_RULE} that reached the allergy list only through
		 *  {@link PatientClinicalContext#hasAllergyToken}'s bare containment — no allergy record it
		 *  matched NAMES this entry ({@link DrugReference#matchesDrugName} over
		 *  {@link PatientClinicalContext#allergensMatching}), so the token sits inside a longer word or
		 *  past an implausible tail: {@code opium} in an allergen recorded as {@code Tiotropium}
		 *  (issue #223, and issue #86's own example). Such a rule may still be the whole finding, so it is
		 *  still RAISED — the arms fire on different evidence and this one was never gated on the other,
		 *  which is what {@link #SELF_NAMED_RULE_WITHOUT_A_NOTE} above says for its own reason. What it may
		 *  not do is SPEAK for a chip the allergen arm corroborated, because the fold's whole premise is
		 *  that a self-named rule reports that arm's fact, and a match no recorded name supports does not.
		 *
		 *  <p>Still raised, and since issue #308 the injected {@code safety_finding} such a rule produces
		 *  may state how it was matched ({@code DrugReferenceInjector.FINDING_UNCORROBORATED_MATCH}).
		 *  <b>May, not does</b>, and the difference is this rank's own condition: this rank asks
		 *  {@link #aMatchedRecordNamesTheEntry} alone, while the clause asks the UNION that predicate is
		 *  one leg of ({@link #corroboratedByTheChart}) — so a rule at this rank whose substance some
		 *  OTHER recorded allergy reaches carries no clause, and the record's third section agrees with
		 *  it. Do not re-derive one from the other: reading the clause off this rank is reading leg 1
		 *  alone, which is the false hedge leg 2 exists to prevent. Its CALL is
		 *  unchanged — the chip's rank, its detail and its severity are what they were, and the sentence
		 *  above about being raised on independent evidence is why. What #308 measured is that a
		 *  qualification reaching only one of two citable records changes no answer, because the model
		 *  answers from the unqualified one.
		 *
		 *  <p>0 rather than 3.5: what it has to be is BELOW {@link #IDENTITY}, and the two class ranks can
		 *  never share this key ({@link #addAllergyContraindications} reaches them only after
		 *  {@link #firstOfSameSubstance} returned null). It shares {@link #SELF_NAMED_RULE_WITHOUT_A_NOTE}'s
		 *  value — unlike {@link #CURATED_RULE} and {@link #SAME_GROUP}, which share one because they can
		 *  never meet, these two CAN meet, on an entry carrying one rule of each shape. They rank alike
		 *  deliberately: neither has a claim on {@link #IDENTITY}'s rank, so what is left between them is
		 *  the ledger's own tie rule, which keeps the incumbent and is what issue #146 already settled for
		 *  two rules on one key. Two names for it because the two disqualifications are different facts —
		 *  one about the rule's CONTENT, one about the RECORD it fired on — and a call site should say
		 *  which one it found. Nothing measured separates them. Ranks 1 and 2 are free on this key space
		 *  if a reason to order them ever appears, and the shape that would need one is authorable rather
		 *  than impossible — one entry carrying both, one token matching only mid-word, and no identity
		 *  chip on the key — but no shipped dataset carries it and this ranking has no measurement to rest
		 *  on, so it is left as the ledger's own tie rather than guessed. */
		static final int SELF_NAMED_RULE_MATCHED_BY_CONTAINMENT_ALONE = 0;

		/** A chip already raised for one key: where it sits in {@code warnings}, and how specific the
		 *  relationship behind it is. ONE entry rather than two maps keyed alike, so the position and the
		 *  rank cannot desync — a position with no rank beside it would throw inside a {@code validate}
		 *  whose callers catch {@link RuntimeException} and return nothing, i.e. it would silently drop
		 *  every chip on the request rather than the one it mishandled. */
		private static final class RaisedChip {

			private final int position;

			private int relationship;

			/** Whether the sentence currently occupying {@link #position} names the substance this
			 *  entry is keyed on — the equal-rank tiebreak (issue #268). Only the allergen arm's three
			 *  sentences can answer false. */
			private boolean namesTheFinding;

			RaisedChip(int position, int relationship, boolean namesTheFinding) {
				this.position = position;
				this.relationship = relationship;
				this.namesTheFinding = namesTheFinding;
			}
		}

		private final List<SafetyWarning> warnings;

		private final SubstanceSubjects subjects;

		private final Map<List<Object>, RaisedChip> raised = new LinkedHashMap<List<Object>, RaisedChip>();

		ContraindicationChips(List<SafetyWarning> warnings, SubstanceSubjects subjects) {
			this.warnings = warnings;
			this.subjects = subjects;
		}

		/**
		 * @return the row {@code row}'s substance is NAMED by in this response — {@link SubstanceSubjects},
		 *         the same answer the interaction and dose arms get (issue #206).
		 *
		 *         <p>Reached through this ledger rather than beside it so that the name and the KEY cannot
		 *         come apart: {@link #add} keys on the subject it is handed, and a caller resolving the
		 *         label from one row while keying on another is how a more precise resolver duplicates
		 *         chips instead of renaming them.
		 */
		DrugReference subjectOf(DrugReference row) {
			return subjects.subjectOf(row);
		}

		/**
		 * Raise {@code chip} for {@code subject} about {@code finding}, unless a chip for that pair is
		 * already raised — in which case the more specific {@code relationship} wins, in place.
		 *
		 * @param subject the row this chip NAMES — {@link #subjectOf}'s answer for a chip asserting
		 *        something about the drug being checked, and the recorded ALLERGEN row for the identity
		 *        chip, which names that instead (issue #164). Either way it is the row the chip's own
		 *        sentence uses, so what the chip says and what the ledger counts cannot come apart; and
		 *        either way it keys the same, since both are rows of the subject's substance.
		 */
		void add(DrugReference subject, Object finding, int relationship, SafetyWarning chip) {
			add(subject, finding, relationship, chip, false);
		}

		/**
		 * As above, declaring whether this chip's sentence NAMES the substance this entry is KEYED on —
		 * the drug for the allergen arm's identity chip, the allergen for its two class chips, which
		 * since issue #268 both have a wording that quotes the chart instead. Only that arm can answer
		 * anything but {@code false}: every other arm names both sides of its sentence unconditionally
		 * and so has no preference to express, and takes the four-argument form.
		 *
		 * <p>Keyed on the FINDING side deliberately, because that is what the ledger groups by, so the
		 * question "did this sentence name what these two chips have in common" is the same question in
		 * both branches.
		 */
		void add(DrugReference subject, Object finding, int relationship, SafetyWarning chip,
				boolean namesTheFinding) {
			// substanceGroupKey: the substance this row stands for, else the row itself — the same key the
			// interaction arms' subject side groups on (issue #162), shared so the two arms cannot come to
			// merge different sets of rows. Its javadoc is where the two key spaces are justified. It is
			// what keeps issue #206's resolved subject a RENAME: every row of one substance answers this
			// alike, so choosing a different row of the group to name the chip after cannot move its key.
			// Keying on the row's IDENTITY instead is NOT the same partition, and the difference is the
			// identity branch of addAllergyContraindications: it hands this the ALLERGEN row on purpose,
			// so two allergy RECORDS resolving two rows of one substance would key apart. Measured — that
			// mutation fails
			// AllergenExactNameResolutionTest.twoRecordedAllergiesToOneSubstanceStillRaiseOneChipPerSubject
			// — so this KEY FORM is a requirement and not a statement of intent.
			//
			// Which row a caller hands over is a separate matter and, with this key form, cannot change
			// the partition at all: every row of one substance answers substanceGroupKey alike. It is a
			// contract rather than a mechanism — pass the row the chip's own sentence names, so the two
			// cannot come apart if the key form is ever revisited. The two together are what a more
			// precise resolver needs: resolving the LABEL while keying per raising row gives 4 chips
			// where 2 are correct (measured — see
			// ContraindicationSubjectLabelTest.twoFindingsAboutOneSubjectStayTwoChips).
			List<Object> key = Arrays.asList(subject.substanceGroupKey(), finding);
			RaisedChip already = raised.get(key);
			if (already == null) {
				raised.put(key, new RaisedChip(warnings.size(), relationship, namesTheFinding));
				warnings.add(chip);
				return;
			}
			// Strictly stronger wins, and at EQUAL strength a chip that NAMES what the entry is keyed on
			// beats one that quotes the chart instead (issue #268). Without that second half the
			// surviving sentence depends on the order PatientService.getAllergies returned the records:
			// a chart recording `Gallium nitrate` verbatim was reported as merely contraindicated by
			// `gallium` because an unrelated free-text row sorted first, so the module held evidence that
			// the chart names the drug and printed a sentence saying it does not. The class chips have
			// the same shape on their allergen half and declare it the same way. It cannot demote a
			// naming chip, only promote one.
			if (relationship > already.relationship
					|| (relationship == already.relationship && namesTheFinding
							&& !already.namesTheFinding)) {
				warnings.set(already.position, chip);
				already.relationship = relationship;
				already.namesTheFinding = namesTheFinding;
			}
			// Nothing to reconcile about issue #308's corroboration flag here, and that is a property of
			// WHERE it is resolved rather than an omission: addContraindications folds it over the whole
			// ENTRY's rules for a clause before building any warning, so every chip arrives carrying its
			// own entry's answer and the sentence that survives brings that answer with it. Resolving it
			// in this ledger instead was tried and is wrong twice over — the key is the SUBSTANCE, so it
			// folds across rows only ONE of which has a record rendered for it, and the rules the
			// subject-matter gate skips never arrive to be folded at all.
			//
			// What this does NOT reconcile is the two channels being about DIFFERENT rows: the sentence
			// that survives here is the strongest RANK across the substance's rows, while the record is
			// rendered for canonicalRow's row and states that row's rules alone. ADR Decision 44 declares
			// that residue and
			// UncorroboratedFindingProvenanceTest.aSiblingRowsSentenceOutranksTheRenderedRowsAndBringsItsOwnAnswer
			// pins it.
		}
	}

	private void addContraindications(ContraindicationChips chips, DrugReference ref,
			PatientClinicalContext context, SubjectMatter askedAbout,
			Supplier<Set<Object>> allergicSubstances) {
		if (context == null) {
			return;
		}
		// The row this response names ref's substance by (issue #206), which is not necessarily the row the
		// RULE is authored on. Both readings are needed and they are different questions: the rule is a
		// fact about the row that publishes it — which is what selfNamedAllergyRule and
		// contraindicationFinding below keep asking ref, since a token naming a QUALIFIED row's own names
		// says nothing about a sibling's — while what the chip CALLS the drug is a fact about the
		// substance, and must be the one thing every arm calls it.
		//
		// So a rule authored on ONE row is now reported under the SUBSTANCE's name, which is a wider
		// claim than the row makes and is the same trade every other arm already takes: an interaction
		// rule sitting on one row has been chipped under the substance since issue #162, because the
		// subject of a chip is a substance and the row only supplies the content. Exempting a curated
		// rule instead — the obvious alternative, since it quotes an operator's own sentence — is ruled
		// out by SelfNamedAllergyRuleFoldTest.aClassLevelRuleKeepsItsOwnChipBesideTheFoldedOne: a rule
		// chip and a class chip about ONE substance stand side by side in one response, so an exempt rule
		// chip would name that substance one way and the class chip another, which is #206 re-created
		// inside this arm. What is left for a deployment authoring a genuinely route-specific rule is to
		// file that presentation as its own substance, which is what a row publishing no substanceName
		// already does. (That paragraph is about `subject`, which the walk below resolves; the pre-pass
		// between here and there is a separate concern and says so in its own comment.)

		// The corroboration answer for each collapsed CLAUSE of this entry, resolved before the walk
		// below and over the same unit the injected drug_reference record resolves it over: this
		// ENTRY's rules, folded by contraindicationFinding, one corroborated rule carrying the key,
		// and then — the record's own second stage — a clause TEXT another key of this entry states as
		// recorded, asked below both of the key's own rendered clause and of the sentence the finding
		// prints for the rule (issue #308, DrugReferenceInjector.contraindicationSections — a change to
		// either belongs in both). Two things about the scoping are load-bearing and each was measured
		// wrong first.
		//
		// It is per ENTRY and not per chip KEY. The ledger's key is the SUBSTANCE, so it spans every
		// ROW of it, while the injector injects ONE record for the substance and renders it for
		// canonicalRow's row — and on two rule-bearing rows of one substance a corroborated rule on the
		// sibling cleared the flag while the rendered row's own record went on hedging its own clause.
		// The residue that scoping leaves — the surviving SENTENCE can still be a row's the record does
		// not state — is declared in ADR Decision 44 and pinned by
		// UncorroboratedFindingProvenanceTest.aSiblingRowsSentenceOutranksTheRenderedRowsAndBringsItsOwnAnswer.
		//
		// And it ignores `askedAbout`. That gate decides which CHIPS this response may raise (issue
		// #143); whether the chart corroborates a match is a fact about the chart and not about what
		// was asked, and contraindicationSections asks it unscoped. Scoped, a corroborated rule the
		// question does not name is skipped before it can carry its key — the record then states the
		// clause while the finding beside it says nothing corroborates the match.
		//
		// The guard opening the loop — MATCHED rules only — is load-bearing too, and it is what
		// everything read off this map assumes. corroboratedByTheChart answers TRUE unconditionally for
		// a rule that is not a self-named allergy rule, and contraindicationClauses renders a clause for
		// every rule of the entry whether it matched or not, so an UNMATCHED rule reaching this fold
		// seeds its key TRUE, puts that key's clause into statedAsRecorded and clears a finding whose
		// record beside it goes on hedging the same string — issue #308's own contradiction, one rule
		// along, and nothing errors or changes a count when it happens. This loop and the walk below
		// open on the same recordedContraindicationKind call, so a later dedup pass that shares it is
		// the seam to watch. Delete the `continue` here, leaving `Object key = contraindicationFinding(
		// ref, c);` as the loop's first statement, and read the failure:
		// UncorroboratedFindingProvenanceTest.aRuleTheChartDoesNotRecordCannotStateItsClauseAsRecorded.
		Map<Object, String> clauses = contraindicationClauses(ref);
		Map<Object, Boolean> corroboratedClauses = new HashMap<Object, Boolean>();
		for (DrugReference.Contraindication c : ref.getContraindications()) {
			if (recordedContraindicationKind(c, context) == null) {
				continue;
			}
			Object key = contraindicationFinding(ref, c);
			if (!Boolean.TRUE.equals(corroboratedClauses.get(key))) {
				corroboratedClauses.put(key,
						corroboratedByTheChart(ref, c, context, allergicSubstances));
			}
		}
		// The record's SECOND stage, and the finding needs it for the reason the record does: the three
		// sections are resolved over clause TEXT and not over keys (`uncorroborated.removeAll(recorded)`),
		// because two rules of DIFFERENT keys may render the SAME string — an allergy rule and a
		// condition rule carrying one note, which contraindicationSections' own comment calls a natural
		// way to author "recorded either way". Stopping at the key left the record stating a string as
		// this chart's own reading while the finding beside it hedged the identical string: issue #308's
		// defect, in one injection, created by the change that fixes it everywhere else. Reproduced over
		// drug-reference-borrowed-alias-corroboration.json's Codeine entry and pinned by
		// UncorroboratedFindingProvenanceTest.aClauseAnotherKeyOfThisEntryStatesAsRecordedIsNotHedged;
		// replace both of the statedAsRecorded legs read below with the key fold alone
		// (`!Boolean.TRUE.equals(corroboratedClauses.get(key))`, the state this stage was added to) and
		// read the failures.
		//
		// The set holds the clause each corroborated KEY renders, off contraindicationClauses, so these
		// are the strings that walk prints. What is asked OF it is two strings rather than one, and the
		// read below says why: a key's rendered clause is a JOIN wherever the key collapses two rules
		// that say different things, while the sentence a finding prints is one rule's note alone.
		Set<String> statedAsRecorded = new HashSet<String>();
		for (Map.Entry<Object, Boolean> clause : corroboratedClauses.entrySet()) {
			if (Boolean.TRUE.equals(clause.getValue()) && clauses.get(clause.getKey()) != null) {
				statedAsRecorded.add(clauses.get(clause.getKey()));
			}
		}

		for (DrugReference.Contraindication c : ref.getContraindications()) {
			String recorded = recordedContraindicationKind(c, context);
			if (recorded == null) {
				continue;
			}
			// A null gate means the DRUG is already subject matter: every in-play caller, and the
			// order-driven caller whose order the response is about. Only the order-driven caller whose
			// drug is NOT subject matter passes one, and then the rule may speak for a finding that is.
			if (askedAbout != null && !askedAbout.names(c)) {
				continue;
			}
			// Resolved after the match rather than above the loop: the shipped ddinter source emits no
			// contraindications at all, so hoisting would fold a substance's rows for every drug in play of
			// every request, for a loop that then does nothing. SubstanceSubjects memoises per substance, so
			// asking it once per MATCHED rule costs no more than asking it once.
			DrugReference subject = chips.subjectOf(ref);
			// Whether the injected finding may state this rule's sentence bare — issue #308, and the SAME
			// question the injected drug_reference record's third section asks
			// (DrugReferenceInjector.corroborated, which delegates to the same method). It rides on the
			// WARNING because the renderer holds a SafetyWarning and not the rule it came from.
			//
			// The CLAUSE's answer, not this rule's: two self-named rules of one entry are one chip and
			// one rendered clause (issue #146 keys both on the substance), so they are folded above and
			// the fold is what is read here. Reading this rule's own answer instead was the first cut and
			// is measured wrong — contraindicationRank answers SELF_NAMED_RULE_WITHOUT_A_NOTE for a blank
			// note WITHOUT asking corroboration, so a corroborated blank-note rule ties with an
			// uncorroborated noted one and loses the incumbent-keeps tiebreak, and the prompt then carried
			// "Recorded for this patient" beside "could not corroborate it as a record of this drug".
			// Mutate the whole expression below to !corroboratedByTheChart(ref, c, context,
			// allergicSubstances) and read the failures.
			//
			// Asked of BOTH strings the two channels can print about this rule, because they are not
			// always one string. clauses.get(key) is what the RECORD renders for the collapsed key — a
			// JOIN of the distinct notes wherever the key collapses two rules that say different things
			// (contraindicationClauses) — while the sentence built below prints the winning rule's own
			// note alone. contraindicationClause(c) is that note, trimmed, which is the form the record
			// renders and so the form these strings have to be compared in. So once such a key carries
			// a second rule saying something different, a guard asked only of the joined
			// clause cannot see that another key of this entry states the finding's own words as
			// recorded, and the finding hedges words the record beside it asserts. That is issue #308's
			// defect one rule along, and one this walk CREATES rather than fails to close, since main
			// appends no clause to any finding. Mutate either conjunct away and read the failures: the
			// key-clause one reddens oneCorroboratedRuleOfACollapsedKeyClearsTheClauseForTheWholeKey,
			// aRuleTheSubjectMatterGateSKIPSStillCarriesItsClausesCorroboration and
			// theSentenceIsTheRankWinnersAndTheClauseIsTheKeysFold; the rule-clause one reddens
			// theWordsTheFindingPrintsAreNotHedgedWhereAnotherKeyStatesThemAsRecorded.
			//
			// RENORMALISING the second conjunct is a separate mutation from removing it, and it reddens
			// that same case: replace contraindicationClause(c) with the expression the sentence below is
			// built from — the untrimmed ChartSearchAiUtils.firstNonBlank(c.getNote(), c.getToken()) — and
			// a matched rule whose curated note carries surrounding whitespace hedges the very words the
			// record beside it states as this chart's reading. That case's fixture authors such a note for
			// exactly this; measured with the whitespace taken off, the replacement moved no case's colour.
			//
			// No conjunct reads corroboratedClauses directly at this site, and the absence is deliberate.
			// statedAsRecorded is built FROM that map, and a matched rule always carries a matchable —
			// hence non-blank — token (PatientClinicalContext.matchableToken), so its key always has a
			// rendered clause and a corroborated key always contributed that clause to the set: the
			// key-clause conjunct already answers for it. That premise is a statement about this map
			// holding MATCHED rules only, which is the guard above and is pinned by
			// aRuleTheChartDoesNotRecordCannotStateItsClauseAsRecorded. One was written here in round 1
			// of this branch's review and removed in round 3, having measured that replacing it with
			// corroboratedByTheChart(ref, c, context, allergicSubstances) — the mutation four texts then
			// prescribed as this fold's own guard — moved no case's colour.
			//
			// It changes what the record SAYS and never how strongly it speaks: the chip's detail, its
			// rank and its severity are untouched, so licensesWithholding still answers alike for it.
			Object key = contraindicationFinding(ref, c);
			boolean uncorroborated = !statedAsRecorded.contains(clauses.get(key))
					&& !statedAsRecorded.contains(contraindicationClause(c));
			chips.add(subject, key, contraindicationRank(ref, c, context),
					SafetyWarning.contraindication(subject.displayLabel(),
							subject.displayLabel() + " is contraindicated by an " + recorded + ": "
									+ ChartSearchAiUtils.firstNonBlank(c.getNote(), c.getToken()),
							uncorroborated));
		}
	}

	/**
	 * @return how specific a relationship a MATCHED contraindication rule states, in
	 *         {@link ContraindicationChips}' ordering — the whole of what decides whether a curated rule
	 *         may speak in the identity chip's place. Called only from the walk above, so {@code c} has
	 *         already matched; each constant's javadoc carries its own justification and none of it is
	 *         restated here.
	 *
	 *         <p>A rule that does not name its own entry keeps its own key space and never competes with
	 *         the identity ranks at all. One that does — issue #146's fold — is ranked by what it ADDS to
	 *         the identity sentence, and that is now two questions rather than one:
	 *
	 *         <ul>
	 *         <li><b>Does it have anything of its own to say?</b> A blank note renders the rule's own
	 *         token back and says strictly less than identity ({@link ChartSearchAiUtils#firstNonBlank}).
	 *         <li><b>Is the record it fired on one the chart reads as this drug?</b> (Issue #223.) The
	 *         fold's premise is that such a rule reports {@link #addAllergyContraindications}'s fact, and
	 *         the match that put it there is {@link PatientClinicalContext#hasAllergyToken}'s bare
	 *         containment — deliberately bare, because a curated token may name a CLASS or a fragment of
	 *         free text, and that is measured as the right rule for those (see
	 *         {@link PatientClinicalContext#hasAllergyToken}). What containment does not say is WHICH
	 *         allergy record the token reached, so the witnesses are asked for
	 *         ({@link PatientClinicalContext#allergensMatching}) and each is put to the entry by the
	 *         accessor for a clinician-entered drug NAME, {@link DrugReference#matchesDrugName}. Without
	 *         that an allergen recorded as {@code Tiotropium} let an {@code Opium} rule replace the
	 *         sentence a genuine, separately recorded opium allergy had raised. That walk is
	 *         {@link #aMatchedRecordNamesTheEntry}, which the injected record's reading reads too
	 *         (issue #269) — everything said about it in the paragraphs here is said of that method.
	 *         </ul>
	 *
	 *         <p><b>Per witness, and against the ENTRY rather than the token</b> — both halves measured,
	 *         because the obvious narrower rule ("does some allergen name what the TOKEN names") demotes
	 *         a rule it must not: an entry whose own names include {@code thyroxine}, ruling on that
	 *         name, for a patient whose recorded allergy is {@code Levothyroxine}. The token sits
	 *         mid-word there, so the token-scoped question answers no — while the entry itself publishes
	 *         the name the chart used, the allergen arm raises its identity chip for that very substance,
	 *         and the operator's note is the one thing in the response that says what the reaction was.
	 *         Asking it per witness is the other half: the entry-scoped question over the whole allergy
	 *         list would be satisfied by {@code Papaveretum} while the rule had actually fired on
	 *         {@code Tiotropium}, which is issue #223 again with a longer proof.
	 *
	 *         <p><b>The rejected alternative, measured.</b> The same question can be asked with the
	 *         allergen arm's OWN resolver — {@code findImpliedSubstances(witness)} reaching
	 *         {@code ref.substanceGroupKey()}, i.e. literally "would that arm raise its identity chip from
	 *         this record". Built and run: <b>outcome-identical over the whole suite</b> (1194 tests, 0
	 *         failures, 2026-08-14), so nothing constructible separates them. This one is kept because the
	 *         question here is the one CLAUDE.md's three-shapes rule settles — a recorded allergen against
	 *         an entry is a clinician-entered NAME, which is {@link DrugReference#matchesDrugName}'s shape
	 *         — and because it is an alias walk rather than a dataset sweep per witness. What the
	 *         alternative would buy, stated so the next reader has it: {@code matchesDrugName} is
	 *         ROW-scoped where this ledger's key is SUBSTANCE-scoped, so a row that omits its SUBSTANCE's
	 *         bare name from its aliases would be demoted for an allergy recorded under a sibling row's
	 *         name. Nothing reports that shape: {@link DrugReferenceValidity#ENTRY_NOT_NAMED_BY_ITS_OWN_ALIASES}
	 *         is keyed on an entry's OWN display name — which it repairs, or since issue #296 reports
	 *         where that name names nothing — so a row named by its own alias and not by its family's is
	 *         silent. Re-measure before swapping:
	 *         the swap also silently makes this rank depend on {@code findImpliedSubstances}' NARROWING,
	 *         which demotes a rule filed on an entry publishing a borrowed alias.
	 *
	 *         <p>Ranked and not gated, which is the same choice issue #146 made for the note: the arms
	 *         fire on different evidence and this one was never conditional on the other, so a rule no
	 *         recorded allergy names still chips where nothing else reports the drug — it simply cannot
	 *         outrank something that does. Tightening the MATCH instead was measured and declined; the
	 *         corpus and the 5 names it would cost are on {@link PatientClinicalContext#hasAllergyToken}.
	 */
	private static int contraindicationRank(DrugReference ref, DrugReference.Contraindication c,
			PatientClinicalContext context) {
		if (!selfNamedAllergyRule(ref, c)) {
			return ContraindicationChips.CURATED_RULE;
		}
		if (ChartSearchAiUtils.isBlank(c.getNote())) {
			return ContraindicationChips.SELF_NAMED_RULE_WITHOUT_A_NOTE;
		}
		return aMatchedRecordNamesTheEntry(ref, c, context) ? ContraindicationChips.SELF_NAMED_RULE
				: ContraindicationChips.SELF_NAMED_RULE_MATCHED_BY_CONTAINMENT_ALONE;
	}

	/**
	 * @return whether an allergy record that MATCHED {@code c} NAMES {@code ref} — the question the
	 *         chip rank above turns on, and one of the two the injected record's patient-specific
	 *         reading takes the union of (issue #269, {@code DrugReferenceInjector}). Asked of a rule
	 *         that has already matched; on any other the witness list is empty and the answer is a
	 *         vacuous false.
	 *
	 *         <p>Per WITNESS and against the ENTRY, both halves measured — see
	 *         {@link #contraindicationRank}, which states why and is not restated here. What the
	 *         extraction adds is a NAME for it: the rank folds this answer onto a value it shares with
	 *         the blank-note disqualification, so nothing behavioural could tell the two apart, while
	 *         the record asks this one alone (a blank note changes what a clause SAYS, not what its
	 *         match rests on).
	 *
	 *         <p>Reaching a non-empty witness list means the rule's ALLERGY leg matched:
	 *         {@link #recordedContraindicationKind}'s legs are exclusive by TYPE and
	 *         {@link #selfNamedAllergyRule} is true only of an allergy rule, so what is being asked is
	 *         which of those records the chart reads as this drug.
	 */
	static boolean aMatchedRecordNamesTheEntry(DrugReference ref, DrugReference.Contraindication c,
			PatientClinicalContext context) {
		// No caller reaches this today, and the reason is a property of the paths rather than a list of
		// them: every one of them is downstream of a context the caller has already established. The
		// rank and the flag are both asked from inside addContraindications, which returns on a null
		// context before either; the injected record's reading is gated on a reading that requires one.
		// Stated as the mechanism because the list has already grown once — issue #308 added the flag —
		// and an enumeration goes stale silently while a mechanism does not. Kept because
		// allergensMatching would throw instead, and because false is the only safe answer here: it
		// hedges or demotes, and can never make a record ASSERT something about a chart nobody read.
		if (context == null) {
			return false;
		}
		for (String allergen : context.allergensMatching(c.getToken())) {
			if (ref.matchesDrugName(allergen)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return what the patient's own chart records that makes {@code c} apply to THEM — the words the
	 *         chip sentence uses, {@code "active allergy"} or {@code "active condition"} — or null when
	 *         the chart records neither, which is every rule of every drug for most patients.
	 *
	 *         <p>ONE definition, called by the chip arm above and by
	 *         {@code DrugReferenceInjector.contraindicationSections}, which renders the injected
	 *         record's patient-specific reading of the contraindication list (issue #208 item 2). The
	 *         record lists every rule the entry publishes, because a drug's contraindications are the
	 *         drug's; what it must not do is leave a model unable to tell which of them this patient
	 *         has, since the record is injected as CITABLE evidence and a model reports what it can
	 *         see. Shared rather than restated for exactly the reason
	 *         {@link #contraindicationFinding} is (issue #190 item 1): a second copy of the match is how
	 *         a record and the chip beside it come to disagree, and here the disagreement would be the
	 *         record asserting more about the patient than the deterministic layer found.
	 *
	 *         <p>The two legs are exclusive by TYPE, not merely by evidence: an {@code allergy} rule is
	 *         answered from the allergy list alone and a {@code condition} rule from the condition list
	 *         alone, so a token naming a drug the patient is allergic to cannot satisfy a condition rule
	 *         written with the same word. Any other type is unrecorded, which is the conservative
	 *         direction — an unrecognised type states nothing about the patient rather than everything.
	 *
	 *         <p>A null {@code context} is "nothing known", not "nothing recorded": both consumers must
	 *         then assert nothing at all rather than report an absence they cannot see.
	 *
	 *         <p>Null does NOT distinguish "the chart says no" from "this rule cannot be evaluated" —
	 *         see {@link #evaluatesAgainstTheChart}, which a consumer making a NEGATIVE claim has to ask
	 *         as well.
	 */
	static String recordedContraindicationKind(DrugReference.Contraindication c,
			PatientClinicalContext context) {
		if (context == null) {
			return null;
		}
		if (isAllergyRule(c) && context.hasAllergyToken(c.getToken())) {
			return "active allergy";
		}
		if (isConditionRule(c) && context.hasConditionToken(c.getToken())) {
			return "active condition";
		}
		return null;
	}

	/**
	 * @return whether this configuration can raise a contraindication chip AT ALL — the two toggles the
	 *         chip arms are gated on, read together and in one place.
	 *
	 *         <p>The injected record's patient-specific reading of a contraindication list is gated on
	 *         this (issue #208 item 2), because that reading is the record's half of a chip: an operator
	 *         who switched the chips off would otherwise get a citable record asserting "Recorded for this
	 *         patient: documented ibuprofen allergy" into the prompt with no chip and no
	 *         {@code safety_finding} record beside it — prose without a chip, which is exactly the
	 *         divergence {@code DrugReferenceInjector.preAnswerFindings} gates the first of these toggles
	 *         to prevent, and which {@code README} promises the {@code drugSafety.*} toggles govern.
	 *
	 *         <p>Both, not either: {@code validateAnswers} gates the pass and
	 *         {@code warnOnContraindications} gates both contraindication arms within it, so a chip needs
	 *         the two. The injector reads {@code drugReference.enabled} for itself before any of this.
	 */
	static boolean reportsContraindications() {
		return toggle(ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_VALIDATE_ANSWERS)
				&& toggle(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS,
						ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS);
	}

	/**
	 * @return whether {@code c} is a rule this module can put to the patient's chart AT ALL — a
	 *         recognised {@code type} carrying a token there is something to look for. Distinguishing
	 *         that from "asked and the chart says no" is what a NEGATIVE claim needs and a chip does not:
	 *         {@link #recordedContraindicationKind} answers null for both, and the chip arm treats null
	 *         as "stay silent", which is right either way. The injected record's unrecorded half is the
	 *         second consumer, and there null would mean "print that this patient does not have it" —
	 *         which for a rule typed {@code diagnosis}, or one carrying no token, would be issue #208's
	 *         own failure with the sign flipped, asserted about a patient the module never checked.
	 *
	 *         <p>The token half is {@link PatientClinicalContext#matchableToken}, the very emptiness rule
	 *         the matcher applies, so "not matchable" and "did not match" cannot come to disagree about a
	 *         token of nothing but combining marks. The type half is the same pair of predicates
	 *         {@link #recordedContraindicationKind} puts a rule to its chart list by
	 *         ({@link #isAllergyRule}, {@link #isConditionRule}) and for the same reason: this is the
	 *         highest-stakes reader of that vocabulary, since a rule it wrongly calls evaluable is one
	 *         the record then reports the chart as NOT having. A dataset may carry any string here — the
	 *         curated parser validates neither field, dropping an entry only for a blank id or name — so
	 *         what the two chart lists are called has to have exactly one definition.
	 *
	 *         <p>A THIRD consumer since issue #285: {@code DrugReferenceLoad}'s per-arm capability
	 *         report asks it at LOAD time, to count the entries publishing a rule the module could
	 *         actually put to a chart. That answer reaches {@code GET /chartsearchai/drugreferencestatus},
	 *         so tightening this predicate moves an operator-facing wire value as well as the injected
	 *         record's unrecorded half below — which is the one other call site in main; the chip arm
	 *         consumes {@link #recordedContraindicationKind} rather than this predicate.
	 */
	static boolean evaluatesAgainstTheChart(DrugReference.Contraindication c) {
		return (isAllergyRule(c) || isConditionRule(c))
				&& PatientClinicalContext.matchableToken(c.getToken());
	}

	/**
	 * @return whether {@code c} is an ALLERGY rule whose token is one of {@code ref}'s own names — the
	 *         one rule shape that CAN report {@link #addAllergyContraindications}'s fact rather than its
	 *         own, and so the one that crosses into that arm's key space (issue #146). Can, not does:
	 *         since issue #223 whether a given match reports it is a further question, asked of the
	 *         patient's own records by {@link #contraindicationRank}. The KEY still crosses
	 *         unconditionally, which is what this predicate decides and why it stays as it is — A
	 *         property of the
	 *         rule and the entry alone, never of the patient: a record ABOUT the drug has to reach the
	 *         same answer as a chip about this patient, and only the collapse UNIT is shared between
	 *         them.
	 *
	 *         <p>{@link DrugReference#isNamed} — name IDENTITY between two REFERENCE strings, the same
	 *         predicate {@link #namesEntry} asks of an interaction rule's token. Deliberately NOT
	 *         {@link DrugReferenceService#findImpliedSubstances}, which reads a RECORDED name and widens
	 *         on purpose (issues #193/#195/#209): applying it to a curated token is the wholesale
	 *         resolution {@link ContraindicationChips}' javadoc rules out, because a token may name a
	 *         CLASS. What bounds the fold to its own subject is not that predicate — {@code isNamed} is
	 *         true of every entry aliasing the token — but that it is asked HERE of the entry the rule
	 *         is filed on, and keyed on that entry's substance.
	 *
	 *         <p>Only the ALLERGY leg: a condition rule whose token happened to name the drug would be a
	 *         fact about a CONDITION record, which no chip in that key space is about.
	 */
	static boolean selfNamedAllergyRule(DrugReference ref, DrugReference.Contraindication c) {
		return isAllergyRule(c) && ref.isNamed(c.getToken());
	}

	/**
	 * @return the {@code recorded finding} half of the ledger key for {@code c} — the substance for a
	 *         {@link #selfNamedAllergyRule}, else the rule's own {@code (type, token)} tagged
	 *         {@code "rule"}. Normalized the way {@link PatientClinicalContext#hasAllergyToken} and
	 *         {@link PatientClinicalContext#hasConditionToken} compare them (both lower-case), so the
	 *         key says what the match said and two rows differing only in case cannot chip twice; and
	 *         tagged, because that tag plus the key's LENGTH is what keeps the two spaces from colliding
	 *         — see {@link ContraindicationChips}, where both are justified.
	 *
	 *         <p>ONE definition, called by the chip ledger here and by
	 *         {@code DrugReferenceInjector.contraindicationSections}, which renders one clause per rule
	 *         and must count the same unit or the model is told the drug has two contraindications where
	 *         the deterministic layer found one (issue #190 item 1). A second copy is how those two came
	 *         apart when issue #146 moved this key: two allergy rules under two aliases of one drug were
	 *         one chip and two clauses, silently.
	 */
	static Object contraindicationFinding(DrugReference ref, DrugReference.Contraindication c) {
		return selfNamedAllergyRule(ref, c) ? ref.substanceGroupKey()
				: Arrays.<Object> asList("rule", DrugReference.normalizeName(c.getType()),
						DrugReference.normalizeName(c.getToken()));
	}

	/**
	 * @return the clause {@code c} contributes to a record about its entry — its note, else its own
	 *         token rendered back ({@link ChartSearchAiUtils#firstNonBlank}), trimmed — or {@code null}
	 *         where the rule states neither. A rule stating neither reaches no channel at all: it
	 *         cannot match ({@link PatientClinicalContext#matchableToken} refuses a blank token) and
	 *         {@link #contraindicationClauses} gives it no clause to be listed under.
	 *
	 *         <p>Since issue #308 {@link #addContraindications} asks this of a MATCHED rule as well, to
	 *         compare the sentence its finding prints against the strings the record states as this
	 *         chart's reading. That is the same expression the sentence is built from, trimmed — which
	 *         is the form the record renders — so the two channels compare like with like. The TRIM is
	 *         what that turns on wherever a curated note carries surrounding whitespace, and
	 *         {@code UncorroboratedFindingProvenanceTest.theWordsTheFindingPrintsAreNotHedgedWhereAnotherKeyStatesThemAsRecorded}
	 *         is what holds it: that case's fixture authors the matched rule's note padded, so replacing
	 *         this call at that site with the untrimmed
	 *         {@code ChartSearchAiUtils.firstNonBlank(c.getNote(), c.getToken())} reddens it. Measured
	 *         with the padding taken off, the same replacement moved no case's colour in the api suite —
	 *         so it is the fixture's whitespace and not the case's shape that pins the normalisation. It
	 *         is not
	 *         the same string as the clause the rule's collapsed KEY renders wherever that key folds two
	 *         rules saying different things; both are asked there, and the walk says why.
	 */
	static String contraindicationClause(DrugReference.Contraindication c) {
		String clause = ChartSearchAiUtils.firstNonBlank(c.getNote(), c.getToken());
		return ChartSearchAiUtils.isBlank(clause) ? null : clause.trim();
	}

	/**
	 * @return the clause each collapsed contraindication key of {@code ref} renders, in the order the
	 *         rules are authored — {@link #contraindicationFinding}'s partition carrying the TEXT the
	 *         injected {@code drug_reference} record prints for each key. Two rules of ONE key
	 *         contribute one clause, joined where they say different things and dropped where a row is
	 *         re-authored with the identical note ({@code contains}, so the drop is provably lossless —
	 *         the one issue #174 site 2 could make, made only where it is).
	 *
	 *         <p>ONE definition, called by {@code DrugReferenceInjector.contraindicationSections} to
	 *         build the clause list it renders and by {@link #addContraindications} to ask that walk's
	 *         own cross-key precedence question of the same strings — for the same reason
	 *         {@link #contraindicationFinding} is one definition. That walk resolves its three sections
	 *         over these strings and not over the keys ({@code uncorroborated.removeAll(recorded)}),
	 *         because two rules of DIFFERENT keys may render the SAME string — an allergy rule and a
	 *         condition rule may carry one note, which is a natural way to author "recorded either
	 *         way" — and a record cannot both state a string as this chart's reading and hedge it. A
	 *         finding beside it cannot either, which is why these have to be the same strings.
	 */
	static Map<Object, String> contraindicationClauses(DrugReference ref) {
		Map<Object, String> byKey = new LinkedHashMap<Object, String>();
		for (DrugReference.Contraindication c : ref.getContraindications()) {
			String note = contraindicationClause(c);
			if (note == null) {
				continue;
			}
			Object key = contraindicationFinding(ref, c);
			String clause = byKey.get(key);
			if (clause == null) {
				byKey.put(key, note);
			} else if (!clause.contains(note)) {
				byKey.put(key, clause + " — " + note);
			}
		}
		return byKey;
	}

	/**
	 * Every interaction chip one SUBSTANCE raises about the patient's own medications: <b>one chip per
	 * (substance, active order) pair</b>, with the two arms that can each raise one — the rule arm
	 * ({@link #bestRulePerPartner}) and the class arm ({@link #classRelationships}) — coordinated
	 * instead of run independently (issue #88).
	 *
	 * <p>Per PARTNER, to be exact, and one active order can be several: the rule arm keys on the partner
	 * ENTRY, so a fixed-dose combination prescription whose constituents both carry a rule is two chips
	 * about one order — two clinical facts, deliberately kept apart. What is collapsed is only a chip
	 * that repeats another word for word, which the issue #339 reconciliation made reachable by naming
	 * both after the prescription; see {@link StatedInteractionChips}.
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
	 * groups the rows ({@link #resolvedSubstanceRows}) and hands them here at the group's first row, so
	 * a substance's chips keep the position they have always had.
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
	 * a fold could act on it.
	 *
	 * <p>The shape that DOES survive the severity floor is the UNRATED rule:
	 * {@link #clearsSeverityFloor} deliberately exempts a rule with no severity rather than treating
	 * it as low, so every hand-authored curated rule reaches a chip whatever it carries, and one
	 * authored with no note produces a chip reading only "X interacts with active order Y". No bundled
	 * dataset holds such a rule (all five curated seed rules carry notes) but any deployment editing
	 * {@code drug-reference.json} can author one, and the fixture behind
	 * {@code DuplicateInteractionChipTest} pins exactly that row. Folding is correct for both
	 * populations without depending on which is the larger, which is why it is preferred over a
	 * measurement that #108 has already moved once.
	 *
	 * <p><b>Which rows the row counts above are over</b> (issue #263). INTERACTION ROWS: the records
	 * of the loaded KB's own {@code interactions} table, 295,184 of them on the shipped KB, each
	 * rating one unordered pair of entry ids. That is a population of its own, and neither of the two
	 * pair bases {@link #sharedClass} defines — 295,183 of these rows do pair two DIFFERENT entries,
	 * but one pairs an entry with ITSELF ({@code DDInter225}, the case
	 * {@code ddi-self-interaction.json} is built on), which no ROW PAIR can do. They do not convert to
	 * SUBSTANCE-pair figures by any arithmetic available here, because several rows can rate one
	 * substance pair; that conversion is a re-measurement, and issue #263 deliberately did not run one.
	 *
	 * <p>The fold leads with the RULE sentence: an explicit rule about this pair is the more specific
	 * finding and its mechanism note is the actionable half. It used to name the partner by the label
	 * {@link #bestRulePerPartner} groups on, and since issue #292 a chip names it by
	 * {@link #reconciledPartnerName} instead — where the class arm names it by whatever
	 * {@link #orderPartners} resolves the order's codes to, which since issue #155 is the dataset's
	 * name for the substance, else the order's own display name, and only then the bare code. Those two
	 * ladders used to disagree inside one detail, and since issue #292 they do not: the fold names the
	 * order ONCE, by {@link #reconciledPartnerName}, which is where the two cases that still keep both
	 * names are recorded. Since issue #339 an UNFOLDED chip asks that same method, so two chips of one
	 * response no longer disagree either. The class relationship follows as
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
	private void addInteractionWarnings(List<SafetyWarning> warnings, List<DrugReference> rows,
			SubstanceSubjects subjects, PatientClinicalContext context, int severityFloor,
			List<DrugReference> orderEntries, InteractionPairs pairs, CoMedications coMedications,
			StatedInteractionChips statedChips) {
		if (context == null) {
			return;
		}
		DrugReference ref = subjects.subjectOf(rows.get(0));
		List<SubjectRule> rules = new ArrayList<SubjectRule>(
				bestRulePerPartner(rows, context, severityFloor, orderEntries));
		// Which rule row carries which class sentence, decided before anything is emitted: the class
		// arm is walked per active-order CO-MEDICATION (issue #171 — it used to walk per CODE, so a
		// substance filed under several codes reached this loop once per code) while the chips are one
		// per rule ROW, and the two groupings are not the same partition.
		//
		// The class arm reads ONE row of a substance, not the whole group — the canonical row for a
		// partner reached by ATC code, and since issue #228 the row the ORDER records for a partner
		// reached by name — and that is lossless only
		// while every row of a substance publishes the same ATC list — which the shipped KB does, across
		// all 129 of its multi-row families, and which is the same premise ContraindicationChips' KEY
		// merge rests on. It is a DATA invariant, not a code-gated one: a KB refresh that
		// gave one route variant a code its siblings lack would silently drop a duplicate-therapy chip
		// this arm used to raise, so re-measure it on a refresh as well as before widening substanceKey.
		// (Re-measured for issue #164's widening: still 0 divergent, at 129 families rather than 121.)
		// Reading the group instead would produce one sentence per row, each naming its own label, which
		// is the duplication being removed.
		Map<SubjectRule, FoldedClassSentence> folded =
				new LinkedHashMap<SubjectRule, FoldedClassSentence>();
		List<String> classOnly = new ArrayList<String>();
		for (Map.Entry<OrderPartner, ClassRelationship> hit : classRelationships(ref, coMedications)
				.entrySet()) {
			SubjectRule rule = ruleAbout(hit.getKey().codes, rules, coMedications);
			if (rule == null) {
				// No rule to reconcile with, so the ladder's own name is the only one there is —
				// but WHICH ROW of the substance it is the display of is still this response's
				// question and not the ladder's, which is what classPartnerName re-decides.
				classOnly.add(hit.getValue().sentence(ref, classPartnerName(hit.getKey(), subjects)));
			} else if (!folded.containsKey(rule)) {
				// One name for the two sentences about to share a detail — see reconciledPartnerName.
				// Both are worded from it here rather than each arm wording its own, which is what let
				// them disagree (issue #292). The partner comes from the class hit itself here, not
				// from reconciledPartnerFor's lookup: this loop already holds the co-medication the
				// class sentence is ABOUT, and asking for it a second way is how two answers to one
				// question start to drift.
				ReconciledPartner reconciled = reconciledPartnerName(hit.getKey(), rule.rule, subjects,
					coMedications);
				folded.put(rule, new FoldedClassSentence(
						reconciled != null ? reconciled.chipName : partnerLabel(rule.rule),
						reconciled != null ? reconciled.noteName : null,
						hit.getValue().sentence(ref, reconciled != null ? reconciled.chipName
								: classPartnerName(hit.getKey(), subjects))));
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
		// client sees the class arm's sentences reshuffle into the rated ones.
		//
		// Collected here and appended below, because WHICH of two rule chips a truncated answer keeps is
		// decided by their order in this list and not by anything downstream (issue #346). The
		// collection stays in dataset order so the collapse and the pair ledger see exactly what they
		// saw before; only the appending is ordered.
		List<SafetyWarning> ruleChips = new ArrayList<SafetyWarning>();
		for (SubjectRule rule : rules) {
			FoldedClassSentence fold = folded.get(rule);
			// Through the two-arg overload where nothing reconciled, rather than passing partnerLabel and
			// null by hand: that keeps "a chip whose partner was not reconciled names it exactly as the
			// screening arm does" a single default in the callee instead of two call sites both
			// remembering to pass the same thing — and that label is also the grouping key, so a drift
			// there would silently unpick the part of #121's invariant this change leaves standing.
			SafetyWarning chip;
			if (fold == null) {
				// No class sentence to fold, and since issue #339 that no longer decides what the order
				// is CALLED: the same reconciliation runs here, so a partner the class arm had nothing
				// to say about is named the way a partner it did have something to say about is. Null
				// where the ladder reached no co-medication, and then this is the two-arg overload's
				// answer — partnerLabel, which is also the grouping key.
				ReconciledPartner reconciled = reconciledPartnerFor(rule, subjects, coMedications);
				chip = reconciled == null ? interactionWarning(ref, rule.rule)
						: interactionWarning(ref, rule.rule, reconciled.chipName, reconciled.noteName,
							null);
			} else {
				chip = interactionWarning(ref, rule.rule, fold.partnerName, fold.partnerNoteName,
					fold.sentence);
			}
			// Emitted only if it says something this pass has not already said. Two rules about ONE
			// prescription are two chips — bestRulePerPartner keys them on the partner ENTRY and keeps
			// them apart deliberately — but since issue #339 named both after that prescription, two
			// constituents the knowledge base rates alike and gives one class-level note render byte for
			// byte the same sentence. See StatedInteractionChips.
			if (statedChips.isFirstStatementOf(chip)) {
				ruleChips.add(chip);
			}
			// Recorded as the pair it is, not as the string it renders, so the screening arm can recognise
			// it whatever either arm calls the substance — see InteractionPairs. OUTSIDE the collapse
			// above, and that is load-bearing: a pair whose statement the surviving chip carries WAS
			// reported, so the screening arm must go on standing down from it.
			pairs.add(ref, rule.partnerKey());
		}
		// Strongest first — see FINDING_STRENGTH_DESCENDING for why the key is the FINDING and not its
		// rating, and Collections.sort is stable, so partners this ordering cannot separate keep the
		// dataset's own order exactly as they arrived.
		Collections.sort(ruleChips, FINDING_STRENGTH_DESCENDING);
		warnings.addAll(ruleChips);
		for (String detail : classOnly) {
			// No rating, and not an omission: a shared-ATC-subgroup or cross-reactivity join is a
			// relationship the reference data states without severity, which is why these chips are never
			// floor-filtered either. See SafetyWarning.getSeverity on why null is the correct value.
			warnings.add(new SafetyWarning(SafetyWarning.TYPE_INTERACTION, ref.displayLabel(), detail));
		}
	}

	/**
	 * Orders this arm's rule chips strongest first, on the FINDING and not on its rating.
	 *
	 * <p><b>Why the finding.</b> This is the only interaction arm that FOLDS, and a folded chip's
	 * rating deliberately understates it: {@link SafetyWarning#getSeverity()} keeps reporting the
	 * RULE's rating while {@link SafetyWarning#carriesUnratedRelationship()} carries the class arm's
	 * unrated relationship across, so a Minor rule folded with a class join answers
	 * {@link #licensesWithholding(SafetyWarning)} and states {@code STRENGTH_WITHHOLD} in the record
	 * the model reads. Ordered on {@link SafetyWarning#getSeverity()} alone that chip would sit below
	 * every caution it outranks, in the one list whose order decides what a truncated answer keeps —
	 * which is the ordering error {@code README.md} tells CLIENTS not to make with the published severity, and it
	 * would be worse made here, where the answer's own strength is what the order is protecting.
	 * {@link #licensesWithholding(SafetyWarning)} is the one definition of that split precisely so the
	 * answer's strength and the chip's ordering cannot disagree.
	 *
	 * <p><b>Then {@link #severityPriority} within each of the two, which is where unrated sits above
	 * Major</b> — the same ordering {@link #PAIR_SEVERITY_DESCENDING} and
	 * {@link #SCREENED_PAIR_SEVERITY_DESCENDING} give the two pairwise arms. That is the whole of what
	 * the three share: those two rank on it alone, because neither can fold, so this arm agrees with
	 * them on every chip whose strength its rating already states and departs from them on exactly the
	 * chips where it does not. A folded Minor therefore sorts below every rated
	 * withholding finding above it and still ahead of every caution — it withholds, so it may not fall
	 * below one. It is not necessarily the LAST withholding finding: a folded {@code Unknown} row
	 * withholds on the same OR and ranks below {@code minor}, which an operator reaches by lowering
	 * {@code minInteractionSeverity} to {@code unknown}.
	 *
	 * <p><b>What it deliberately does not order</b>: the unrated class-only chips appended after these.
	 * They state a relationship the reference data does not rate at all, so by this method's own key —
	 * where unrated leads — they would come to head the whole list; they stay where they have always
	 * been so that the sentences the class arm produces alone do not overtake the rated findings a
	 * clinician asked about. That is a stated limit of issue #346's fix rather than a property of it.
	 * <b>Not every unrated finding, which is the misreading to guard against</b>: a RULE the source
	 * rates nothing for — a hand-authored {@code json} or curated rule — is one of this arm's own rule
	 * chips, so it is ordered here, and by the paragraph above it leads the withholding side ahead of
	 * every {@code major}. Only the class-only chips are exempt.
	 */
	private static final Comparator<SafetyWarning> FINDING_STRENGTH_DESCENDING =
			new Comparator<SafetyWarning>() {

				@Override
				public int compare(SafetyWarning a, SafetyWarning b) {
					boolean aWithholds = licensesWithholding(a);
					if (aWithholds != licensesWithholding(b)) {
						return aWithholds ? -1 : 1;
					}
					return Integer.compare(severityPriority(b.getSeverity()),
						severityPriority(a.getSeverity()));
				}
			};

	/**
	 * The rows of each substance {@code candidates} names, grouped: {@link #substanceRows} over
	 * {@code candidates}, each group additionally carrying the rows of that same substance the patient's
	 * own ACTIVE ORDERS resolved.
	 *
	 * <p><b>Called TWICE per {@code validate}, with two candidate sets, and the difference is what issue
	 * #238 turns on.</b> Over the whole {@code inPlay} set it produces {@code resolvedRows} — every row of
	 * an in-play substance that this PASS resolved, from either side — which is what the drug-in-play and
	 * dose arms RULE over. Over {@code questionDrugs} alone it produces {@code namingRows}, which is what
	 * {@link SubstanceSubjects} folds to decide what those arms CALL the substance: the same groups minus
	 * the rows only the ANSWER put in play. Two maps of freshly built lists over the same shared
	 * {@link DrugReference} objects; no arm
	 * mutates a group after it is built, so the two cannot drift apart.
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
	 * <p>A substance ONLY the orders resolved gets a group of its own too, since issue #206 — the
	 * order-driven contraindication arm ({@link #addActiveOrderContraindications}) subjects chips on
	 * exactly those, and it has to call them what every other arm would. That does not put them in play:
	 * the interaction and dose arms are drained by the caller as it walks {@code inPlay}, so a key no
	 * in-play row reaches is never handed to them, and an order the question says nothing about still
	 * raises no interaction chip here (it is the screening arm's business). What the #175 widening does
	 * change is that a partner whose rule sits only on an order-resolved row now reaches a chip from
	 * this arm too — the same chip the screen raises for the same pair, which is the point: the two arms
	 * must not choose from different row sets.
	 *
	 * <p>Rows from the candidate set come FIRST and order rows after, so a full tie on
	 * {@link #outranks} keeps a row the text actually named. A row the ANSWER
	 * matched that {@code orderEntries} also carries sits at its in-play position in {@code resolvedRows}
	 * and at its order position in {@code namingRows}, and where a family ties on both of
	 * {@link DrugReference#canonicalRow}'s rungs that difference decides which row is elected. It is still
	 * a per-{@code validate} local for issue #172's reason — which
	 * lives in {@link DrugReferenceService}'s class javadoc and not on the issue, whose own statement of
	 * it is false — and the lists are the ones {@link #substanceRows} just built, so appending to them
	 * mutates nothing shared.
	 */
	private static Map<Object, List<DrugReference>> resolvedSubstanceRows(
			Collection<DrugReference> candidates, List<DrugReference> orderEntries) {
		Map<Object, List<DrugReference>> groups = substanceRows(candidates);
		for (DrugReference ordered : orderEntries) {
			List<DrugReference> rows = groupFor(groups, ordered);
			// contains() is identity here — DrugReference defines no equals, and both sets are resolved
			// against DrugReferenceService's shared getAll() cache, so one row is one object.
			if (!rows.contains(ordered)) {
				rows.add(ordered);
			}
		}
		return groups;
	}

	/**
	 * The reference rows of {@code entries}, grouped by the substance each stands for
	 * ({@link DrugReference#substanceGroupKey()}), each group in first-appearance order.
	 *
	 * <p>The order WITHIN a group is the load-bearing one, because {@link #bestRulePerPartner}'s survivor
	 * rule falls back to "keep the incumbent", which is only "the dataset's first such row" while the
	 * group is in dataset order.
	 *
	 * <p>The order BETWEEN groups is load-bearing for ONE of the three callers, and it is worth naming
	 * which rather than leaving a {@link LinkedHashMap} looking like a blanket guarantee.
	 * {@link #substanceRowsNamedBy} feeds {@link #addPartnersForUnmappedOrders}, which walks this map's
	 * {@code entrySet()} and appends a partner per group, and {@link #classRelationships} emits a class
	 * sentence per partner in that order — so for an order whose names imply two substances (issue #228's
	 * shape) this map's iteration IS the chip order.
	 *
	 * <p>The other two do not iterate it for emission: {@link #resolvedSubstanceRows}'s caller walks
	 * {@code inPlay} itself and drains a key set as it reaches each group's first row (the key set is
	 * seeded from {@code keySet()}, but into an unordered {@link java.util.HashSet}, so it carries no
	 * order anywhere), and {@link #addActiveOrderPairInteractions} drains by {@code remove()} while
	 * walking its own list. For those two what keeps a substance's chips in the position that row's chips
	 * had is the CALLER's iteration, and replacing this with a {@code HashMap} would change no output
	 * there (measured before {@link #substanceRowsNamedBy} existed: the whole api suite passed with one).
	 * Move an emit site into an iteration of this map and that positional promise moves with it — which
	 * is what {@link #substanceRowsNamedBy}'s caller did. A third such caller, {@code canonicalSubjects},
	 * iterated it only to build a subject lookup and is gone since issue #236; do not count it back.
	 */
	private static Map<Object, List<DrugReference>> substanceRows(Collection<DrugReference> entries) {
		Map<Object, List<DrugReference>> out = new LinkedHashMap<Object, List<DrugReference>>();
		for (DrugReference entry : entries) {
			groupFor(out, entry).add(entry);
		}
		return out;
	}

	/** @return {@code row}'s substance's group in {@code groups}, created empty if this is its first row —
	 *          the get-or-create both builders of this map need, written once so they cannot come to key
	 *          it differently. */
	private static List<DrugReference> groupFor(Map<Object, List<DrugReference>> groups, DrugReference row) {
		Object key = row.substanceGroupKey();
		List<DrugReference> rows = groups.get(key);
		if (rows == null) {
			rows = new ArrayList<DrugReference>();
			groups.put(key, rows);
		}
		return rows;
	}

	/**
	 * @return the row of one substance that its chips name: the row the patient's own record claims most
	 *         strongly ({@link DrugReference#nameMatchStrength}), and among rows tied on that —
	 *         including the common case where no recorded name matches any of them at all —
	 *         {@link DrugReference#canonicalRow}'s choice — the row carrying no route qualifier wherever
	 *         the loaded data has one, and among rows tied on THAT the row the data files the substance
	 *         under (issue #250).
	 *
	 *         <p><b>Issue #162, the second half</b>, and the half that is a correctness fix rather than a
	 *         de-duplication. The chips named the subject by whichever ROW produced them, so a question
	 *         about "hydrocortisone" reported {@code Hydrocortisone (ophthalmic) interacts with active
	 *         order diclofenac} — asserting an ophthalmic preparation the clinician never named and the
	 *         chart does not record.
	 *
	 *         <p><b>Issue #194.</b> {@link DrugReference#canonicalRow} answers "which row names this
	 *         substance", and where the rows of a family TIE on its rungs it can only keep the earliest —
	 *         so a patient ordered one presentation was told about another. Live-measured on the 3.7.1
	 *         standalone: a {@code Botulinum toxin type A} order (the demo dictionary's concept 4259,
	 *         whose name the order carries verbatim) was subjected on
	 *         {@code Daxibotulinumtoxina (botulinum toxin type a)}, because both rows of that substance
	 *         name no route and the {@code Daxibotulinumtoxina} row is the dataset's first.
	 *
	 *         <p>That example no longer ties: issue #250 gave the fold a second rung — the row the data
	 *         files the family under, which here is {@code Botulinum toxin type A} — so the fold now
	 *         reaches this case's answer unaided, and the family it was demonstrated on can no longer
	 *         demonstrate it. The chart-anchoring step below is unchanged and still decides every family
	 *         whose rows tie on BOTH rungs; where it is pinned moved with the
	 *         example, to the COVID pair in {@code OrderedSubjectRowTest
	 *         .theOrderNamedRowIsNamedWhereTheFoldCannotReachIt}, whose two rows tie on both.
	 *
	 *         <p>So the chart decides first, and only then the fold. That order is the constraint issue
	 *         #187 settled and #192 re-measured — naming the row the CHART records is what makes a
	 *         finding truthful, and applying {@code canonicalRow} to a recorded name instead renames a
	 *         charted {@code Ketorolac (ophthalmic)} allergy. The ranking is
	 *         {@link DrugReference#nameMatchStrength}, the one #192 introduced for the allergen side,
	 *         rather than a second definition of "how strongly does this row claim that name".
	 *
	 *         <p>What it does NOT do is manufacture a preference where the record supports none. An
	 *         order contributes up to three names ({@link PatientClinicalContextBuilder}): its drug
	 *         row's name, which commonly carries a strength ({@code Aspirin 81mg}), the free text a
	 *         clinician typed for a non-coded order (issue #293, which commonly carries a strength
	 *         too), and its CONCEPT's own name, which commonly does not
	 *         ({@code Botulinum toxin type A}). Which of the three an order contributes is not a
	 *         coded/non-coded dichotomy: {@code DrugOrder.isNonCodedDrug()} is only
	 *         {@code isNotBlank(drugNonCoded)}, so an order carrying a coded {@code Drug} AND free text
	 *         contributes all three, and that row is savable wherever {@code drugOrder.requireDrug} is
	 *         false, which is the default. So a row whose display name IS that concept name reaches
	 *         {@link DrugReference#NAME_IS_THE_DISPLAY_NAME} and wins, which is what moves the COVID pair
	 *         in {@code OrderedSubjectRowTest.theOrderNamedRowIsNamedWhereTheFoldCannotReachIt}. This
	 *         sentence used to name the botulinum case here, and that is the one family issue #250's
	 *         second rung leaves unable to demonstrate it — see the paragraph above. Where no recorded
	 *         name is any row's name or alias every row scores
	 *         {@link DrugReference#NAME_TOKEN_INSIDE_A_NAME} and the fold decides exactly as before. That
	 *         second shape is what the route-variant and no-unqualified-row cases in
	 *         {@code InteractionRouteVariantTest} and {@code ScreeningSubjectLabelTest} supply — their
	 *         contexts carry the dosed form alone — which is why they are unchanged, and it is also the
	 *         residue: a deployment whose order names all carry strengths gains nothing here.
	 *
	 *         <p>Kept as a named method over the shared fold rather than inlined at its call sites,
	 *         because "what a chip calls its subject" is the decision issue #162 made, #174 site 3
	 *         extended to the screening arm, #194 anchored on the chart, #206 gave one answer per
	 *         substance per validate pass and #238 made that answer invariant to the ANSWER within each
	 *         pass — never one shared answer across the request (the qualifier is load-bearing; see
	 *         {@link SubstanceSubjects}) — the name is where that decision
	 *         is DEFINED. Where a chip arm looks
	 *         it UP is {@link SubstanceSubjects}, and a new chip-subject site belongs there rather than
	 *         here: calling this directly is how an arm ends up folding a narrower row group than its
	 *         siblings, which is exactly what #206 was — and what #236 removed from the last two arms.
	 *         Since then {@code ChipSubjectOneResolutionTest} enforces it, by scanning this file's source
	 *         for a caller outside the three permitted bodies; before that the rule was javadoc only. That
	 *         scan is about CALLERS of this method, so it is paired there with a second needle for the
	 *         bypass that calls it not at all — re-constructing {@link SubstanceSubjects} over an arm's own
	 *         group, which is the deleted {@code canonicalSubjects} wearing this class's name.
	 *
	 *         <p><b>And since issue #228 the class arm's PARTNER too</b>, on the one rung where that
	 *         partner has a recorded name: {@link #addPartnersForUnmappedOrders} resolves a
	 *         dictionary-unmapped order by NAME, so the rows it must choose among are a group and the
	 *         patient's own order is the record. The question is the same one — which row of this
	 *         substance does the chart claim — so it is answered here rather than a second time. The
	 *         class arm's OTHER partner rung is unchanged and still {@link DrugReference#canonicalRow}
	 *         alone ({@link #entryForAtcCode}, issue #174 site 1), because a bare ATC code carries no
	 *         recorded name to rank by; the two rungs cannot disagree within one response, since a
	 *         substance is one partner and the first rung to reach it names it.
	 *
	 * @param recordedNames the names the patient's own active orders carry
	 *        ({@link PatientClinicalContext#getActiveDrugNames()}), or — at the partner site above —
	 *        the names of the ONE order that partner is; empty is normal and means the fold alone
	 *        decides
	 */
	static DrugReference interactionSubject(List<DrugReference> rows,
			Collection<String> recordedNames) {
		if (rows.size() == 1) {
			// The fold and the ranking both answer "that row" for a group of one, so this is provably the
			// same answer and not an approximation of it. It is here because issue #206 gave this method a
			// far larger population: the order-driven contraindication arm asks it about every substance the
			// patient's own orders resolve, and most substances the KB files as ONE row.
			return rows.get(0);
		}
		return DrugReference.canonicalRow(strongestClaimants(rows, recordedNames));
	}

	/**
	 * @return {@link #interactionSubject}'s answer for a caller holding a context rather than a name set
	 *         — the row {@code rows}' substance is NAMED by in this response. One method with two
	 *         arities, never a second definition: {@code recordedDrugNames} is the only thing added, and
	 *         it is the same null-safe read {@code validate} makes before building {@link
	 *         SubstanceSubjects}.
	 *
	 *         <p><b>Why there is a caller outside this class (issues #237, #259).</b>
	 *         {@code DrugReferenceInjector} renders one citable record per substance from
	 *         {@link DrugReference#canonicalRow}, which is a fold over the dataset and cannot see the
	 *         chart. Every chip names its subject through this method, which anchors on the chart first.
	 *         So wherever the patient's own record names a non-canonical row, the record and the chips
	 *         beside it called one substance two things — measured 2026-08-14 over the shipped 19 MB KB
	 *         through the real {@code injectRecords} and the real {@code validate}: 104 of the 129
	 *         multi-row substances, every one that could be posed with both surfaces.
	 *
	 *         <p>The injector reads this to decide whether it has something to SAY, not to choose the row
	 *         it renders — see {@code DrugReferenceInjector.rowAttribution} for why the row itself does
	 *         not move. That is what keeps this a widening of readers rather than a new chip-subject
	 *         site: the rule in {@link #interactionSubject}'s own javadoc — that a chip arm looks its
	 *         subject up through {@link SubstanceSubjects} rather than calling this directly — binds arms
	 *         that RAISE chips inside one {@code validate} pass, and the injector raises none.
	 *
	 *         <p>It asks over the union of {@code findImpliedByQuery}'s rows and every row the patient's
	 *         orders resolve — deliberately NOT the narrower set it decides to INJECT, which is that
	 *         union filtered by the {@code injectFromQuery}/{@code injectFromOrders} toggles and by the
	 *         relevance gate. Those decide what reaches the prompt; what a substance is CALLED may not
	 *         depend on them, and building the group from the injected rows was measured to lose the
	 *         charted row exactly when the clause is needed.
	 *
	 *         <p>One thing that union does not guarantee, stated rather than assumed. A question word
	 *         does not always resolve every row of the substance it names — the rows of one substance
	 *         USUALLY publish the same aliases, and {@code drug-reference-charted-substance-row.json} is
	 *         a fixture where they deliberately do not — so a substance reached by the question alone can
	 *         still be grouped short, the group is narrower, the fold decides, and the injector's caller
	 *         stays silent rather than guessing — see {@code DrugReferenceInjector.chartAnchoredSubject}.
	 *         (This used to name a second thing the union does not guarantee: that the injector cannot see
	 *         the rows the ANSWER puts in play. Since issue #238 the chip layer does not fold them either,
	 *         so that is no longer a difference between the two — the INPUTS now coincide. Their GROUPING
	 *         still does not, {@code DrugReferenceInjector.collect} keying on {@code substanceKey()}
	 *         falling back to the row's own id where this keys on {@link DrugReference#substanceGroupKey},
	 *         so do not read the coincidence as agreement in general.)
	 */
	static DrugReference interactionSubject(List<DrugReference> rows, PatientClinicalContext context) {
		return interactionSubject(rows, recordedDrugNames(context));
	}

	/**
	 * @return the rows of {@code rows} tied at the strongest claim any of {@code recordedNames}
	 *         gives them, in their original order — every row when none of them is named at all, since
	 *         {@link DrugReference#NAME_NO_MATCH} is then the shared maximum. Never empty for a
	 *         non-empty {@code rows}, so {@link DrugReference#canonicalRow} keeps its
	 *         "null only for an empty group" contract.
	 */
	private static List<DrugReference> strongestClaimants(List<DrugReference> rows,
			Collection<String> recordedNames) {
		List<DrugReference> strongest = new ArrayList<DrugReference>();
		int best = DrugReference.NAME_NO_MATCH;
		for (DrugReference row : rows) {
			int claim = recordedClaim(row, recordedNames);
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

	/** @return the strongest claim any of {@code recordedNames} makes on {@code row}
	 *          ({@link DrugReference#nameMatchStrength}), or {@link DrugReference#NAME_NO_MATCH} when none
	 *          of them names it — the per-row step {@link #strongestClaimants} takes a maximum over,
	 *          extracted so {@link #recordNamesMoreStrongly} asks it the same way rather than re-deriving
	 *          "how strongly does the chart claim this row" a second time. */
	private static int recordedClaim(DrugReference row, Collection<String> recordedNames) {
		int claim = DrugReference.NAME_NO_MATCH;
		for (String recorded : recordedNames) {
			claim = Math.max(claim, row.nameMatchStrength(recorded));
		}
		return claim;
	}

	/**
	 * @return whether the patient's own record claims {@code row} MORE STRONGLY than it claims
	 *         {@code than} — the question {@code DrugReferenceInjector.rowAttribution}'s sentence
	 *         asserts, since that sentence says the row it names is "the row this patient's record
	 *         names" and the row it contrasts is the one the record was published for.
	 *
	 *         <p><b>Why this and not "the fold and the chart disagree" (issue #250).</b>
	 *         {@code chartAnchoredSubject} used to answer by comparing
	 *         {@link #interactionSubject}'s row against {@link DrugReference#canonicalRow}'s — a proxy,
	 *         which held only while the fold could not reach the row the chart names. Issue #250's second
	 *         rung made it reach that row for three shipped substances, and the proxy then read their
	 *         agreement as "the chart chose nothing": measured over the shipped KB through the real
	 *         {@code injectRecords}, a question naming {@code daxibotulinumtoxina} for a patient ordered
	 *         {@code Botulinum toxin type A} rendered a record titled {@code Daxibotulinumtoxina} beside a
	 *         chip naming {@code Botulinum toxin type A}, and the clause reconciling them — which that
	 *         same arrangement printed before the rung — was suppressed. Asking the chart directly is what
	 *         the sentence needed all along, and it cannot drift as the fold changes.
	 *
	 *         <p>It is a READ of {@link DrugReference#nameMatchStrength} through
	 *         {@link #recordedClaim}, the same per-row step {@link #strongestClaimants} uses, and not a
	 *         second ranking: the composition that decides a SUBJECT still lives only in
	 *         {@link #interactionSubject}. What this decides is whether a record may say the chart
	 *         preferred one of its siblings.
	 */
	static boolean recordNamesMoreStrongly(DrugReference row, DrugReference than,
			PatientClinicalContext context) {
		// A fast path, NOT the guard against a sentence contrasting a row with itself: the comparison
		// below already answers false for row == than, since a claim cannot exceed itself. What actually
		// keeps "published for X, not for X" out of the prose is
		// DrugReferenceInjector.rowAttribution's worthNamingApart call, and that is where a reader
		// should look — this clause cannot be pinned by any test, because no mutation of it changes an
		// answer.
		if (row == null || than == null || row == than) {
			return false;
		}
		Collection<String> recorded = recordedDrugNames(context);
		int claim = recordedClaim(row, recorded);
		// A FLOOR, because the sentence says the record NAMES the row. NAME_TOKEN_INSIDE_A_NAME is bare
		// containment — one of the entry's names merely occurs inside the recorded string — and a record
		// asserting "the row this patient's record names" on that is the overclaim issue #269 removed from
		// the section beside this one, where `opium` matched an allergen recorded as `Tiotropium`. The
		// strictly-greater comparison alone admits it, at rank 0 against a rendered row at NAME_NO_MATCH:
		// measured over the shipped KB through this method, 6 such arrangements exist today — an order
		// recorded `Procaine benzylpenicillin` against a `Benzylpenicillin` subject, `Insulin human
		// (isophane)` against `Insulin human`. Nothing false is PRINTED for them, because the injector's
		// relevance gate is silent there too; but the sentence each would license IS false, and for a
		// reason worth stating exactly, since it is easy to misread. The pair being compared is one
		// substance — that is what makes a record of one row contrast with the other at all. What the
		// chart records is a THIRD: `Insulin human (isophane)` is filed under `insulin isophane`, while the
		// row whose name it merely contains is filed under `insulin, regular, human`. So the clause would
		// say the chart names a row of a substance the chart does not record. The floor is also what stops a
		// dataset refresh turning one of these into printed text, and the
		// hazard is exactly the one `nameMatchStrength`'s own javadoc records for that rank (`Lactate`
		// inside `Ciprofloxacin lactate`, two different substances). Those 6 are silent both before this
		// method existed and after the floor, so it costs nothing that was ever printed.
		//
		// NAME_IS_ANOTHER_NAME and not NAME_IS_THE_DISPLAY_NAME, because an alias IS a name: where the
		// chart records a row's rxnorm or CIEL name, that row is named and the sentence is true. Raising
		// the floor to the display name silences the #237 clause for exactly that chart, which is the
		// common shape rather than a corner — interactionSubject's own javadoc says why: an order
		// contributes its CONCEPT's name, and the rows of one substance share their rxnorm and CIEL
		// aliases.
		//
		// The SHIPPED data cannot observe that, and this used to say so and stop there. Measured through
		// the real DdiDrugReferenceSource().load(), substanceGroupKey and nameMatchStrength — taking every
		// alias any row of a family publishes as the candidate recorded order, 1021 candidates over the
		// 129 multi-row families, a wider population than the display names this comment first counted —
		// ZERO have a strongest claim of exactly rank 1 while another row of the family claims lower,
		// because a shared alias lands on every row at once and yields no strict inequality either way.
		// So the level needs a family whose rows publish DIFFERENT alias sets, which is curated data:
		// drug-reference-substance-dosing-ceilings.json publishes `clobex` on the route-qualified
		// Clobetasol row alone. Pinned there by
		// SubstanceNameRowTest.aRowTheChartNamesByAnAliasIsARowTheChartNames at this gate and by
		// theRecordSaysWhichRowItIsWhereTheChartNamesThatRowByAnAlias on the printed record — raise the
		// floor and read those failures rather than trusting this attribution. The fast path above is now
		// the only part of this predicate no case pins, for the reason it states itself.
		//
		// STRICTLY greater, which is the OTHER half of this return and was unpinned when the floor was
		// written: relaxed to `claim >= recordedClaim(than, recorded)` the predicate answers true in BOTH
		// directions for one pair, so it stops meaning "more strongly" and the clause asserts a preference
		// the chart never expressed. The arrangement is the paragraph above read from the other side —
		// because the rows of one substance share their rxnorm and CIEL aliases, a recorded order name that
		// is no row's own display name claims the whole family at rank 1, above the floor and tied. The
		// shipped botulinum family reaches it through the CIEL name `Botulinum type A
		// toxin-haemagglutinin complex`, which both its rows publish: relaxed, an injected record for a
		// question naming only `Daxibotulinumtoxina` reads "Published by this dataset for
		// Daxibotulinumtoxina, not for Botulinum toxin type A — the row this patient's record names" over a
		// chart that names neither in preference. Pinned by
		// SubstanceNameRowTest.aRowTheChartClaimsNoMoreStronglyThanItsSiblingIsNotARowTheChartPreferred at
		// this gate and by aRecordAttributesItsRowToNobodyWhereTheChartClaimsBothRowsAlike on the printed
		// record — two cases rather than one, because folded together the first fails and JUnit never
		// reaches the second. Mutate the comparison and read the failures rather than trusting that
		// attribution.
		return claim >= DrugReference.NAME_IS_ANOTHER_NAME
		        && claim > recordedClaim(than, recorded);
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
	 * <p><b>Why not the chip's text</b> — and this is about recognising a repeat of the same PAIR, not
	 * about {@link StatedInteractionChips}, which keys on text for the opposite question (two different
	 * pairs that render alike) and runs beside this rather than instead of it. It was the text, until
	 * this ledger: the screen seeded a set with every
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
	 * The interaction chips this pass has already STATED, in the words it stated them in — so one
	 * relationship worded one way is put in front of a clinician, and into the prompt, once.
	 *
	 * <p><b>Why this is not {@link InteractionPairs} and could not be</b> (issue #339, review round
	 * 12). That ledger answers "has a chip been raised for this PAIR", on identity, and its own javadoc
	 * says why it must not key on rendered text: two arms word one finding differently, so text
	 * equality UNDER-recognises a repeat. This one is the other direction and needs no identity at all
	 * — two chips whose every published field is equal are indistinguishable to every consumer there
	 * is, so a second copy states nothing a reader did not already have. Both ledgers run: this one
	 * cannot recognise a repeat the arms word differently, and that one cannot recognise two DIFFERENT
	 * pairs that render alike.
	 *
	 * <p><b>What makes two different pairs render alike is the reconciliation this issue widened.</b>
	 * A fixed-dose combination prescription resolves to one order-rung co-medication
	 * ({@link CoMedications#partnerNaming} via {@link OrderPartner#substances}) while
	 * {@link #bestRulePerPartner} keys a rule on the partner ENTRY, so a subject that rules on two
	 * constituents produces two chips — and since this issue both are named by the prescription's own
	 * display rather than by the two rule tokens. Where the knowledge base rates the two rules alike
	 * and files them under one class-level mechanism note, the two details are then equal byte for
	 * byte. Measured through the real {@link #validate} over the shipped knowledge base: one
	 * {@code Emtricitabine / Tenofovir disoproxil} order on {@code J05AR03} asked
	 * {@code Can I give her bedaquiline?} rendered
	 * {@code Bedaquiline interacts with active order Emtricitabine / Tenofovir disoproxil — Moderate.
	 * Coadministration of bedaquiline with other agents known to induce hepatotoxicity may potentiate
	 * the risk of liver injury.} TWICE, where the merge base rendered
	 * {@code active order tenofovir disoproxil} and {@code active order emtricitabine}.
	 *
	 * <p><b>The information the collapse gives up is WHICH constituent, and it is given up on the chip
	 * alone.</b> The injected {@code drug_reference} record still lists each partner under the rule's
	 * own token ({@code DrugReferenceInjector.orderedInteractionNotes}), which is the prompt-level
	 * residue issue #339 records, so both constituents are still named to the model. What no surface
	 * states any more is that TWO rules fired; naming the constituent on the chip instead would put new
	 * clause structure into a sentence {@code DrugSafetyChipLabelTest} and issue #108 constrain and
	 * that {@code DrugReferenceInjector.renderFinding} copies verbatim into a citable
	 * {@code safety_finding}, and it would do so conditionally on what OTHER chips the response holds —
	 * ADR Decision 63 carries that trade.
	 *
	 * <p><b>The key is every field a consumer can read</b>, not the detail alone: the wire publishes
	 * {@code type}, {@code drug}, {@code detail} and {@code severity}
	 * ({@code ChartSearchAiRestController.serializeSafetyWarnings}) and the prompt adds the two
	 * booleans {@link #licensesWithholding(SafetyWarning)} and
	 * {@code DrugReferenceInjector.renderFinding} read. So this can only drop a chip that is equal
	 * everywhere a chip is looked at.
	 *
	 * <p><b>It removes a chip and nothing else.</b> Measured over 610 arrangements built from the
	 * shipped knowledge base's own CIEL combination names, driven through the real
	 * {@code DrugReferenceInjector.injectRecords}: every rendered {@code drug_reference} text is
	 * byte-identical with this ledger and without it (636 records, 0 differing lines), so what a
	 * collapse costs is one {@code safety_finding}. That was measured rather than argued, because
	 * {@code DrugReferenceInjector.reconciledPartnerNoteName} finds a chip by RULE IDENTITY and a
	 * collapsed chip's rule therefore falls back to {@link #partnerLabel} — which on the ORDER rung, the
	 * rung a combination prescription reaches, is the value {@link #reconciledPartnerName} hands that
	 * note anyway. ADR Decision 63 carries what that population does and does not establish.
	 *
	 * <p><b>It does NOT record the pair</b> — {@link InteractionPairs#add} still runs for the collapsed
	 * rule, because the pair really was reported (under the surviving chip's words) and the screening
	 * arm must go on standing down from it. And it does not reach {@link SafetyWarning}'s own equality:
	 * that class deliberately declares no {@code equals}, so that nothing DOWNSTREAM can collapse two
	 * chips this module meant to keep apart ({@code InteractionRouteVariantTest}). The decision stays
	 * here, where the two arms that can create the duplicate are.
	 *
	 * <p>A per-pass local and never a field — issue #172's rule, and for {@link InteractionPairs}'
	 * sharper version of it: a ledger records what THIS pass stated, so a field would go on suppressing
	 * every chip it had ever seen. Shared by both active-order rule arms for
	 * {@link ContraindicationChips}' reason — a collapse living inside one arm cannot see the other's
	 * chips.
	 */
	private static final class StatedInteractionChips {

		/** Insertion-ordered only so a debug dump reads in the order the chips were stated. */
		private final Set<List<Object>> stated = new LinkedHashSet<List<Object>>();

		/**
		 * @return whether {@code chip} says something this pass has not already said — true the first
		 *         time each distinct chip is offered, false for a byte-identical restatement of one.
		 */
		boolean isFirstStatementOf(SafetyWarning chip) {
			return stated.add(Arrays.asList(chip.getType(), chip.getDrug(), chip.getDetail(),
				chip.getSeverity(), chip.carriesUnratedRelationship(),
				chip.restsOnAnUncorroboratedChartMatch()));
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
	 *         keeps as two chips — the population that figure counts, and its base, are named at
	 *         {@link #bestRulePerPartner} (issue #263). The first in dataset order takes the fold; the
	 *         other keeps its rule chip unfolded, which is the conservative direction, since the
	 *         alternative is stating one duplicate-therapy relationship twice.
	 */
	private SubjectRule ruleAbout(Set<String> orderCodes, List<SubjectRule> rules,
			CoMedications coMedications) {
		if (rules.isEmpty()) {
			// Both tests below are inside the rule loop, so with no rules the answer is null whatever
			// the codes are — and resolving them is a full dataset scan per code, though since issue
			// #256 one shared with orderPartners through CoMedications and so paid at most once per
			// pass per code. That is the ORDINARY outcome of this arm: a class-only chip is one this
			// method answered null for, and issue
			// #228 made both sides of the product larger (more partners, and a name-reached partner
			// carries the reference row's code list rather than one dictionary's — the WHOLE list only
			// where the chart records no presentation this module can place, since issue #234 narrows
			// it to the site an order's own route or dose form names).
			return null;
		}
		for (String orderCode : new TreeSet<String>(orderCodes)) {
			DrugReference orderEntry = coMedications.entryForCode(orderCode);
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
	 * <p>One definition, because {@link #bestRulePerPartner} groups on it wherever the dataset
	 * identifies no partner entry, and two copies of the same coalesce could drift into grouping rules
	 * by something a clinician never sees.
	 *
	 * <p><b>It is the GROUPING key, and since issue #339 it is no longer what most chips SAY.</b> Issue
	 * #292 scoped issue #121's second half — that the key is also the rendered label — to unfolded
	 * chips, and #339 narrows it again rather than removing it: the reconciliation the fold used to own
	 * is now asked at every rule chip. So this remains the value both surfaces group on, and it is what
	 * a chip renders only where {@link #reconciledPartnerName} refuses, reaches no co-medication at
	 * all, or reconciles onto this very value (its {@code !namesADrug} rung) — which on the no-entry
	 * branch #121 is about is every UNFOLDED chip, since
	 * {@link SubjectRule#partner} is null there and {@link #reconciledPartnerFor} declines on exactly
	 * that. A FOLDED chip on that branch does not go through {@link #reconciledPartnerFor} at all: the
	 * fold hands the class hit's own {@code OrderPartner} straight to {@link #reconciledPartnerName},
	 * so it can render the ladder's name there — which is what
	 * {@code ClassChipPartnerLabelTest.anOrderTheDatasetDoesNotCoverIsNamedByItsOwnDisplayName} shows,
	 * key {@code aspirin} and chip {@code Aspirin 81mg}.
	 * That method states what the departure costs; the grouping is unaffected either way, running
	 * before the reconciliation and on this value.
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
	 * <p><b>Issue #292 broke that agreement for a FOLDED chip and issue #297 restored it.</b> Where
	 * {@link #reconciledPartnerName} reconciles a chip's partner it may name it by the class arm's
	 * ladder, and for one release {@code orderedInteractionNotes} kept this coalesce, so chip and record
	 * called one partner two things. They no longer do: the reconciliation decides that name in BOTH
	 * vocabularies at once ({@code ReconciledPartner}) and the record's half travels to
	 * {@code DrugReferenceInjector} on the chip itself
	 * ({@link SafetyWarning#reconciledPartnerNoteName}). They still do not share one STRING — the chip's
	 * may be {@link DrugReference#displayLabel()}, which the record's prose may not carry — so what they
	 * share is the SUBSTANCE, each in its own vocabulary. Where the reconciliation refuses or reaches no
	 * co-medication, both surfaces are this label again, exactly as they always were.
	 *
	 * <p>Nothing about the GROUPING has ever changed: both surfaces still key on this label case-folded
	 * wherever the dataset identifies no partner entry. What issue #292 scoped and issue #339 finished
	 * scoping is the second half of issue #121's invariant — that the key is also what the surface SAYS
	 * — which now holds only where the reconciliation does not answer. See
	 * {@link #reconciledPartnerName} and ADR Decisions 39, 49 and 63.
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
	 * The one name a chip calls an active order by (issue #292), or null when the two names must not be
	 * reconciled at all.
	 *
	 * <p><b>Since issue #339 this is asked of every rule chip and not only of a FOLDED one.</b> It used
	 * to have one call site, inside the {@code classRelationships} loop, so which name an order got was
	 * decided by whether the class arm happened to have a sentence about it — one response therefore
	 * used two conventions and the same pair changed name between a drug-in-play question and a
	 * screening one. {@link #reconciledPartnerFor} is the entry point the two arms use; this one is
	 * called directly only by the fold, which already holds the co-medication its class sentence is
	 * about.
	 *
	 * <p><b>Which displacements are permitted DID move — once as this stands, and a second movement was
	 * tried across review rounds 3 to 6 and reverted in round 7 — and the first version of this change
	 * said it had not moved at all</b> — recorded here because a false "the gate is untouched" is
	 * exactly what the next change reads instead of re-measuring. The ENTRY rung asks the gate of the row this RESPONSE
	 * elects and falls back to the ladder's own, so a substance whose charted presentation the token
	 * does not claim can now reconcile on either row and no longer on neither (issue #339 review round
	 * 3, at the branch itself). {@code FoldedChipOnePartnerNameTest}'s
	 * {@code aRowTheResponseElectsButTheTokenDoesNotClaimFallsBackToTheLaddersOwn} is its case.
	 *
	 * <p><b>The ORDER rung carried a SECOND conjunct through review rounds 3 to 6 and does not any
	 * more</b> (review round 7). It refused where the prescription that supplied the label also named
	 * a substance this response raises chips about — a fixed-dose combination, where
	 * {@link #namesNamingOrder} alone is satisfied and the chip then reads as a drug interacting with
	 * itself ({@code Isoniazid interacts with active order Isoniazid / Rifapentine}) — and then had to
	 * choose a name to print instead. Every name available on this rung is a CONSTITUENT name: the
	 * rule's own token here, {@link #soleSubstanceOf}'s substance at {@link #classPartnerName}. That is
	 * the chip {@link OrderPartner#nameByOrder} exists to prevent, because
	 * {@link #classRelationships} cites a subgroup over ALL of the partner's codes, so where the shared
	 * subgroup came from the code the dataset could not name, no constituent it CAN name publishes it.
	 * Measured through the real {@link #validate} over the shipped knowledge base, one
	 * {@code Dorzolamide / Timolol} order (codes {@code S01ED51}, uncovered, and {@code S01EC03}) asked
	 * {@code "Can I give her timolol and levobunolol?"}: {@code Levobunolol is in the same ATC class
	 * (S01ED) as active order Dorzolamide (ophthalmic)}, and dorzolamide is a carbonic anhydrase
	 * inhibitor. It also made a prescription's name depend on the QUESTION, the very thing issue #339's
	 * second half forbids: the same chart asked {@code "Can I give her levobunolol?"} printed the
	 * display in both sentences. So the display stands here, refused or not, and the reading it leaves
	 * is the accepted residue —
	 * {@code OneOrderNameAcrossOneResponseTest.aPrescriptionNamingTheSubjectAndThePartnerIsStillNamedOnceByItsOwnDisplay}
	 * asserts it, with the measurement, and ADR Decision 63 carries the trade.
	 *
	 * <p><b>The defect.</b> Issue #88's fold puts both arms' sentences into one detail and each arm named
	 * the partner from its own source: the class arm from issue #155/#186/#290's ladder in
	 * {@link #orderPartners}, the rule arm from {@link #partnerLabel}, which is the rule's match token and
	 * reaches nothing the context carries. So one prescription appeared under two names in one sentence.
	 * Observed live on the 3.7.0-rc.2 standalone at {@code b0a24a96} over the curated seed, one active
	 * order the module could read no name for, asked "Can she be given ibuprofen?":
	 * {@code Ibuprofen interacts with active order aspirin — additive GI and bleeding risk. Ibuprofen is
	 * in the same cross-reactivity group (NSAID) as active order [ATC A01AD05, B01AC06, N02BA01] —
	 * possible additive or duplicate-class therapy}. The divergence is systematic for a formulation,
	 * because the {@code ddinter} parser lower-cases every token it writes from the partner row's RxNorm
	 * generic while the class arm prints that row's {@link DrugReference#displayLabel()}.
	 *
	 * <p><b>It reconciles where the two names are provably one drug's and refuses on the rest.</b>
	 * Reconciling two names means asserting they denote one drug, so it does so only where that is
	 * provable and otherwise leaves the chip saying what it has always said. Deliberately not stated as a
	 * count of paths: two of the three items below both reconcile and refuse, on a condition of their
	 * own.
	 * <ol>
	 *   <li><b>The ladder found no name</b> ({@code !namesADrug}) — a bare code or the {@code [ATC …]}
	 *       stand-in is the ABSENCE of a name (issue #290) and may not displace one, so both sentences
	 *       take the rule's own TOKEN. ADR Decision 38 measured the other direction: letting the code
	 *       list win "put {@code [ATC N02BA01, N02BA99]} beside the rule arm's {@code aspirin} inside ONE
	 *       folded chip detail". Asked of the token and not of {@link #partnerLabel}, which falls back to
	 *       the ATC code: with no token either, nothing here is a name and this yields null too.</li>
	 *   <li><b>The label came from an ORDER</b> ({@link OrderPartner#namingOrder}) — reconciled only where
	 *       the RULE's own token names that very order ({@link #namesNamingOrder}), because an order is
	 *       not a substance and the name it supplied may be a different drug's. Not validated against
	 *       {@link OrderPartner#labelEntry}: {@link OrderPartner#nameByOrder} deliberately does not update
	 *       that field, so on a renamed partner it identifies one drug while the label names another, and
	 *       {@link #unambiguouslyNames} would prove a fact about the first and hand out the second. The
	 *       ORDER is what the label came from, so the order is what it is put to — by the same predicate
	 *       {@link PatientClinicalContext#hasActiveDrug} used to admit the rule, narrowed to that one
	 *       prescription. Both shapes measured through the real {@code validate} refuse on it: a partner
	 *       keyed on {@code Naproxen} but renamed after an {@code Esomeprazole} order carrying naproxen's
	 *       code (token {@code naproxen}, naming order {@code Esomeprazole 20mg}) printed an NSAID
	 *       duplicate-therapy finding under the PPI order's name with {@code naproxen} nowhere in the
	 *       detail; and one order carrying codes of two substances lets {@link #ruleAbout} pick a rule by
	 *       whichever code sorts first, so a WARFARIN rule was printed under {@code Aspirin 81mg} (token
	 *       {@code warfarin}, naming order names {@code {aspirin}}). Both are the #161/#187/#194 failure,
	 *       and the second reaches the prompt through {@code DrugReferenceInjector.renderFinding} carrying
	 *       {@code STRENGTH_WITHHOLD}. What the gate ADMITS is the ticket's second named shape, where the
	 *       order the dataset cannot name is the very drug the rule is about: token {@code aspirin} beside
	 *       an {@code Aspirin 81mg} order, one name in both sentences.
	 *       <p><b>Nothing narrows this rung further, and issue #339's review rounds 3 to 6 tried</b>:
	 *       a display that also names another of this response's chip subjects is still handed to both
	 *       sentences, because the only names left to step back to are constituents of a prescription
	 *       the dataset cannot fully name and a constituent cannot carry the class sentence. See the
	 *       paragraph above and {@link #classPartnerName}.</li>
	 *   <li><b>The rule's token does not name the ladder's entry unambiguously</b> — see
	 *       {@link #unambiguouslyNames}, which carries the measurement. Refused for the same reason: the
	 *       two arms may be about different co-medications.</li>
	 *   <li>Otherwise the ladder's label is handed to both sentences.</li>
	 * </ol>
	 *
	 * <p>So {@code partnerLabel} becomes the ladder's last-but-one rung on the paths that reconcile,
	 * rather than a second independent ladder. Both GROUPING keys ({@link SubjectRule#partnerKey},
	 * {@code DrugReferenceInjector.onePerPartner}) keep {@link #partnerLabel} — they run before this and
	 * are unaffected by it — and a class-only chip keeps the ladder's own label, which is what it always
	 * used, {@link #classPartnerName} re-deciding only WHICH ROW of the substance that label displays
	 * (issue #339, review round 1).
	 * What is no longer true, and was until issue #339, is that "outside a folded chip nothing
	 * here applies": an unfolded rule chip asks this too, through {@link #reconciledPartnerFor}.
	 *
	 * <p><b>Since issue #297 this answers in TWO vocabularies</b> ({@code ReconciledPartner}), because the
	 * injected {@code drug_reference} note has to name that same partner and may not carry
	 * {@link DrugReference#displayLabel()}. Which name the RECORD takes is decided per outcome above and
	 * moves on ONE of them: outcomes 1 and 2 hand it the rule's own token, which is what it already
	 * printed, and outcome 3 hands it {@link OrderPartner#labelEntry}'s {@code getName()} coalesced with
	 * that same token, for the reason stated at the branch itself — the ENTRY rung being the one place
	 * the fold has PROVED the dataset's name is this rule's ({@link #unambiguouslyNames}). Not the one
	 * place a dataset name exists: outcome 2's {@code labelEntry} is a real entry with a real name too,
	 * and it is unvalidated, which is the whole of why the note does not take it. The record's own
	 * vocabulary, not the chip's; the two name one SUBSTANCE rather than sharing one string.
	 *
	 * <p><b>Issue #121's invariant is SCOPED by this method, not preserved by it.</b> On the branch
	 * where the dataset identifies no partner entry the grouping key is {@code partnerLabel}
	 * case-folded, and a chip on that branch can render the ladder's name instead. The grouping itself
	 * is unaffected, running before this and on that key. Issue #292 scoped that to folded chips; issue
	 * #339 asks the same question of every rule chip, so what is left of #121's second half is "the key
	 * is what the chip says wherever this method does not answer". The witness is
	 * {@code ClassChipPartnerLabelTest.anOrderTheDatasetDoesNotCoverIsNamedByItsOwnDisplayName}: the
	 * curated seed carries no aspirin entry at all, so the key is {@code aspirin} while the chip says
	 * {@code Aspirin 81mg}.
	 *
	 * <p>What issue #339 REMOVES is the asymmetry #292 left behind: two rules about one order carrying
	 * different tokens still produce two chips (issue #136's shape), but they no longer name that order
	 * two ways because one of them folded and the other did not. Both ask this.
	 *
	 * <p><b>What is NOT closed</b>, each recorded in ADR Decision 39's trade-offs with its measurement:
	 * outcome 1 can name a substance the prescription need not contain at all and then say something
	 * false ABOUT it, since the class sentence's subject moves from the prescription to whatever the
	 * token names — pinned AS WRONG by {@code FoldedChipOnePartnerNameTest}'s
	 * {@code aNamelessOrderCarryingTwoSubstancesCodesNamesTheClassSentenceAfterTheRulesDrug}, so a
	 * change that closes it fails there rather than passing in silence; outcome 3 still refuses on a TIE — a
	 * token no substance claims as its own display name ({@code penicillin g},
	 * {@code antithrombin iii}) — and wherever {@link DrugReference#canonicalRow} hands the ladder a row
	 * that is not the token's strongest claimant, which over the shipped KB is {@code gabapentin}
	 * ({@code entryForAtcCode} resolves {@code N02BF01} to {@code Gabapentin enacarbil}, an alias claim,
	 * while the {@code Gabapentin} row claims it outright) — the instance among the chips ADR Decision 52
	 * measured, and not the extent of the shape, which is not gabapentin's alone: {@code A02BC05}
	 * resolves to {@code Omeprazole} against the token {@code esomeprazole} the same way, and there
	 * refusing is the whole point of the guard. Issue #296 closed the rest of what ADR
	 * Decision 39 recorded here; a rule carrying only an ATC code keeps naming its partner by that code;
	 * and an order the loaded dataset covers NO entry for reaches no co-medication with a
	 * {@link OrderPartner#labelEntry} and no {@link SubjectRule#partner}, so an unfolded chip about it
	 * keeps the token beside a folded chip's order display — the residue issue #339 leaves, with
	 * {@code ClassChipPartnerLabelTest.anOrderTheDatasetDoesNotCoverIsNamedByItsOwnDisplayName} as its
	 * witness.
	 *
	 * @param partner the co-medication the class arm resolved, carrying the ladder's answer
	 *        ({@link OrderPartner#label}), whether that answer is a name at all
	 *        ({@link OrderPartner#namesADrug}), the ORDER that supplied the label where one did —
	 *        non-null only where that label is a name, which since issue #298 is a property of
	 *        {@link OrderPartner} as well as of this method's statement order — two independent guards,
	 *        so do not reverse the branches below on the strength of either (issue #293 changed what the
	 *        reversal would COST, not the answer; {@link OrderPartner#namingOrder} has the re-measurement)
	 *        ({@link OrderPartner#namingOrder}, {@link OrderPartner#recordNameSource}) — and the entry it
	 *        was resolved from ({@link OrderPartner#labelEntry})
	 * @param rule the matched rule the class sentence is folding onto. Its {@link SubjectRule} wrapper is
	 *        deliberately NOT taken: the entry {@link #activeOrderEntryFor} resolved for that rule is not
	 *        consulted, because {@link #identifies} accepts a bare shared ATC code, so comparing the two
	 *        arms' resolved substances agrees spuriously on exactly the shape this refuses (see
	 *        {@link #unambiguouslyNames}). What decides is the rule's own token — against the ladder's
	 *        entry on the entry rung, and against the naming ORDER where the label came from one
	 * @return the one name both sentences take, or <b>null</b> when they must not be reconciled — each
	 *         sentence then keeps the name its own arm resolved, which is what the chip has always said.
	 *         The caller reads null as "use each arm's own name", so it must not be confused with
	 *         {@link #partnerLabel}'s own nullability: that returns null only for a rule carrying neither
	 *         token nor code, which a rule inside the matched loop cannot be.
	 */
	private ReconciledPartner reconciledPartnerName(OrderPartner partner,
			DrugReference.Interaction rule, SubstanceSubjects subjects,
			CoMedications coMedications) {
		if (!partner.namesADrug) {
			// The ladder has no name to keep, so the rule's own token is the only one either arm holds —
			// unless the rule has no token either, when nothing here is a name and neither sentence
			// yields. Asked of the TOKEN and not of partnerLabel, which falls back to the ATC code: a
			// chip naming an active order N02BA01 is the very thing namesADrug refuses on the other side,
			// and returning it here would put a bare code where the class sentence had at least labelled
			// its codes AS codes.
			//
			// The RECORD's name here is that same token, which is what it already printed: the ladder
			// holds no name, so the dataset has none to offer this note (issue #297).
			if (ChartSearchAiUtils.isBlank(rule.getToken())) {
				return null;
			}
			String token = partnerLabel(rule);
			return new ReconciledPartner(token, token);
		}
		if (partner.namingOrder != null) {
			// The label names an ORDER, and an order is not a substance — so it goes to the rule sentence
			// only where the RULE's own token names that very order. What must not happen is a name some
			// order supplied being printed over a finding about a different drug: measured on a partner
			// keyed on Naproxen but renamed after an Esomeprazole order that carries naproxen's code,
			// where displacing printed an NSAID duplicate-therapy finding under the PPI order's name with
			// the word naproxen nowhere in the chip; and on one order carrying codes of two substances,
			// where ruleAbout picks a rule by whichever code sorts first, so a WARFARIN rule was printed
			// under Aspirin 81mg. Both still refuse, though since issue #293 for a narrower reason than
			// they used to: neither naming order's DISPLAY carries the rule's token. labelEntry is deliberately not the operand:
			// nameByOrder does not update it, so on a renamed partner it identifies a different drug from
			// the label being handed out, which is why this branch cannot use unambiguouslyNames.
			//
			// The RECORD keeps the rule's own TOKEN on this rung (issue #297), and NOT because the dataset
			// has no name here. It can have one: this rung is reached after soleSubstanceOf resolved an
			// entry for the order (issue #186) and nameByOrder then overwrote only the LABEL, so
			// labelEntry can be a real entry with a real getName() — printing it through
			// OneNameAcrossChipAndInjectedRecordTest.anOrderRungFoldStillLeavesTheNoteOnTheRulesOwnToken
			// reads "Naproxen". The reason is that this name is UNVALIDATED, exactly as the paragraph
			// above says for the chip's half: this branch deliberately does not ask unambiguouslyNames of
			// labelEntry, since on a renamed partner that field identifies one drug while the label names
			// another. Handing the note labelEntry.getName() would print a dataset name the fold has
			// proved nothing about — outcome 3's mis-attribution, one surface along. What the gate HAS
			// proved is that the rule's token names the very display the chip is about to print, so the
			// two surfaces still name one drug, the note's name being a word of the chip's rather than a
			// second name. Handing the note the prescription DISPLAY instead would put a strength and a
			// formulation the knowledge base knows nothing about into a list of that knowledge base's own
			// partners, and that list is quotable by construction
			// (DrugReferenceInjector.RenderedReference).
			if (!namesNamingOrder(rule, partner.namingOrder)) {
				return null;
			}
			return new ReconciledPartner(partner.label, partnerLabel(rule));
		}
		// null and NOT the rule's token: where the two arms may be about different co-medications, each
		// sentence keeps its own name. Making the class sentence adopt the rule's token here would move
		// the mis-attribution rather than refuse it — "Pantoprazole is in the same ATC class (A02BC) as
		// active order esomeprazole" states the class relationship the arm found for Omeprazole about a
		// drug it did not resolve, which is the same defect in the other direction.
		// The labelEntry null test is defensive and unreachable as written: namesADrug with no naming
		// order is the entry rung, whose constructor always supplies one. Kept because a future rung could
		// answer namesADrug true without an entry, and this way it refuses rather than dereferences.
		// The ENTRY rung is the one rung where the fold has PROVED the dataset's name for this partner is
		// this rule's — unambiguouslyNames, just below — so it is the one rung where the RECORD's name
		// moves (issue #297): getName(), the vocabulary that record already uses for its own subject,
		// against the chip's synonym-augmented displayLabel(). Not the one rung where a dataset name
		// EXISTS: the ORDER rung's labelEntry is a real entry with a real name too, and unvalidated,
		// which is what its own branch above says instead.
		//
		// Coalesced with the rule's own token, and that is not defensive. It guards a hazard THIS change
		// introduces and is not the module's position on a blank entry name — that belongs at the loader,
		// where DrugReferenceValidity already rules on the neighbouring shape (BLANK_ALIAS, issue #150),
		// and a rule there would reach the CHIP's label on such a row as well — displayLabel() is blank
		// too wherever the row publishes no diverging generic — which this line cannot. Until then it is
		// only this note: partnerLabel can never be blank — it trims a firstNonBlank of two fields, which
		// is why the note could not carry a blank before issue #297 — while getName() has no such guard
		// on the path this change opens: the ddinter parser refuses a row whose name isEmpty() but not
		// one that is whitespace, and setName does not trim. A blank here costs the note its partner's
		// name in one of two ways, and which one depends on whether the rule carries mechanism prose —
		// measured by mutation on both shapes. WITH a note, the assembled piece is still non-blank, so
		// DrugReferenceInjector.orderedInteractionNotes' isBlank(rendered) guard does not fire and the
		// record reads "Interactions: (Major. ...)", naming no partner at all. WITHOUT one, the piece IS
		// the blank label, that guard fires, and the partner leaves the record entirely — the worse of the
		// two, and what THIS coalesce exists to prevent. Not something orderedInteractionNotes guards
		// against: its isBlank drop is deliberate for a rule with nothing to say, and here that drop is
		// the mechanism by which the partner vanishes. No shipped parser produces both together today
		// (ddinter synthesises a note for every row, json refuses a blank name, atc emits no
		// interactions at all), which is why the fixture reaches only the first.
		if (partner.labelEntry == null) {
			return null;
		}
		// The row this RESPONSE names that substance by, and not the ladder's own label. The ladder
		// elects its row with canonicalRow alone (entryForAtcCode), while every other name slot in a
		// response is elected by interactionSubject — the row the patient's own record claims most
		// strongly, THEN canonicalRow among the rows tied on that (#194). Those disagree on 139 of the
		// shipped KB's rows whose display an order could carry, and the difference is #187's defect:
		// measured through the real validate, a charted `Pfizer-BioNTech Covid-19 Vaccine` order was
		// named `Tozinameran (…)` — a row of the same substance that the chart does not record — while
		// the screening arm names that same prescription by the charted row. So one substance was named
		// two ways in one response, which is what CLAUDE.md forbids at a chip-naming site. Asking
		// SubstanceSubjects here is that rule applied to the PARTNER slot; addPartnersForUnmappedOrders
		// already elects its own rung's row this way (issue #228).
		DrugReference named = subjects.subjectOf(partner.labelEntry);
		// Of the row about to be PRINTED, which is why the election above comes first: the gate proves
		// the rule's token names the row whose display the chip will carry, and asking it of a sibling
		// would license a displacement that row does not (see unambiguouslyNames).
		//
		// And the LADDER's own row is what a refused election falls back to, rather than the token —
		// issue #339's review round 3. The election moves WHICH ROW the gate is asked about, and the two
		// rows of one substance can answer differently: over the shipped knowledge base a charted
		// `Atropine (ophthalmic)` order elects that presentation, whose claim on the token `atropine`
		// merely ties Hyoscyamine's, while canonicalRow's `Atropine` claims it outright. Refusing there
		// left the rule sentence on partnerLabel while classPartnerName kept the elected row, so ONE
		// folded detail named one prescription `atropine` AND `Atropine (ophthalmic)` — worse than
		// before this issue, which printed `Atropine` in both. The gate is still asked of the row about
		// to be printed, which is the whole of CLAUDE.md's rule here and what
		// FoldedChipOnePartnerNameTest.aRuleTokenTheLaddersRowOnlyTiesKeepsItsOwnToken pins: nothing is
		// printed on a sibling's claim, the second ask is about the row the second branch prints. So the
		// order is a PREFERENCE over two rows the gate may each admit, never a widening of it — refusing
		// both still refuses, which is the unmapped-order rung's own arrangement, where the two rows
		// coincide and there is no second ask to make.
		if (!unambiguouslyNames(rule, named, coMedications.nameIndex())) {
			if (named == partner.labelEntry
					|| !unambiguouslyNames(rule, partner.labelEntry, coMedications.nameIndex())) {
				return null;
			}
			named = partner.labelEntry;
		}
		String datasetName = ChartSearchAiUtils.firstNonBlank(named.getName());
		return new ReconciledPartner(named.displayLabel(),
			datasetName != null ? datasetName.trim() : partnerLabel(rule));
	}

	/**
	 * The name the CLASS arm's own sentence calls an active-order partner by — the ladder's label
	 * ({@link OrderPartner#label}), with ONE thing re-decided: WHICH ROW of the substance that label is
	 * the display of.
	 *
	 * <p><b>Why this exists (issue #339, review round 1).</b> Moving a rule chip onto
	 * {@link #reconciledPartnerName} moved the ROW question with it — that method names the partner by
	 * {@code subjects.subjectOf(labelEntry)}, because the ladder elects its own row with
	 * {@link DrugReference#canonicalRow} alone ({@link #sweepForAtcCode}) while every other name slot
	 * in a response elects with {@link #interactionSubject}, and taking the ladder's answer at a chip
	 * site is issue #187. Left here, the class arm would go on electing the other way, and the
	 * two elections disagree on a multi-row substance whose CHARTED presentation is not the canonical
	 * row — so one response would name one prescription two ways again, in two visibly different
	 * strings rather than the case difference this issue started from. Measured through the real
	 * {@link #validate} over the shipped knowledge base, a chart carrying one
	 * {@code Methylprednisolone (topical)} order asked about prednisolone and warfarin:
	 * {@code Prednisolone is in the same ATC class (H02AB) as active order Methylprednisolone} beside
	 * {@code Warfarin interacts with active order Methylprednisolone (topical)}, one prescription.
	 * {@code OneOrderNameAcrossOneResponseTest.aClassOnlyChipNamesAPartnerByTheSameRowARuleChipDoes}
	 * is the pin; reading {@link OrderPartner#label} here reddens it (re-measured at issue #339's
	 * review round 11 head). <b>Nothing is claimed about what that mutation leaves green</b>, here or
	 * in ADR Decision 63: an exclusivity claim of exactly this shape stood on the neighbouring
	 * election for several rounds and was false by the time round 11 read it, because a case added
	 * mid-branch began reddening on it too. Mutate it and read the failures.
	 *
	 * <p><b>The election reaches the ENTRY rung and only it, and that is the whole of what this method
	 * does</b> — a label an ORDER supplied has no row to elect and a bare code or {@code [ATC …]} is the
	 * absence of a name (issues #155/#290, ADR Decisions 38 and 39), so both keep
	 * {@link OrderPartner#label} untouched. {@code OneOrderNameAcrossOneResponseTest}'s
	 * {@code anOrderTheDatasetCoversNoEntryForKeepsItsOwnDisplay} is the null-{@code labelEntry} case
	 * with a NAMED order behind it, and it is the only shape either conjunct can be read from
	 * separately — dropping {@code labelEntry != null} does NOT redden it, since a partner with a
	 * naming order still takes {@link OrderPartner#label} under the surviving conjunct. What that
	 * mutation reaches is the CODE-ONLY shape, where both are null: it dereferences null inside
	 * {@link SubstanceSubjects#subjectOf}, so what reddens is an NPE thrown from a chip-naming site
	 * rather than a set of cases about naming a partner — and this case is not among them (re-measured
	 * at issue #339's review round 10 head). <b>No count of those failures is published, here or in ADR
	 * Decision 63</b>: one was, and round 10 measured it wrong by 5&times;, most of the failures being
	 * in a class about answer-versus-class-code fidelity that has nothing to do with partner naming.
	 * Mutate the conjunct and read the failures; a count here would only invite the next reader to
	 * treat the surplus as damage they had just done. The election itself is a no-op wherever the response grouped no rows for that
	 * substance — {@link SubstanceSubjects#subjectOf} hands back the row it was given — so a
	 * single-row substance renders exactly the string it rendered before.
	 *
	 * <p><b>Where a folded chip's rule sentence could not take this row, this one still prints it.</b>
	 * {@link #reconciledPartnerName} answers null when the rule's token claims neither the elected row
	 * nor the ladder's, and the fold then words the class sentence from here while the rule sentence
	 * keeps {@link #partnerLabel} — the residue issue #292 records and issue #339 did not close, since
	 * a class-only chip has no rule token for any gate to read. What issue #339's review round 3 DID
	 * close is the case where the two rows disagree and the ladder's would have carried the
	 * displacement; see {@link #reconciledPartnerName}'s ENTRY rung.
	 *
	 * <p><b>The ENTRY rung's fallback opens a divergence of its own, which is left OPEN and is a
	 * regression against the merge base on the arrangement below</b> (issue #339, review round 4 —
	 * recorded here and in ADR Decision 63 rather than closed). Where the rule's token claims the
	 * ladder's row but not the row this response elects, the rule chip prints the LADDER's row while a
	 * class-ONLY chip about that same prescription — raised for a different subject, which is what puts
	 * the two in one response — goes on printing the elected one. Measured at this head through the real
	 * {@link #validate} over the shipped knowledge base, one {@code Atropine (ophthalmic)} order mapped
	 * to {@code S01FA01} asked {@code Can I give her tropicamide or scopolamine?}:
	 * {@code Scopolamine interacts with active order Atropine} beside {@code Tropicamide is in the same
	 * ATC class (S01FA) as active order Atropine (ophthalmic)}. Reading {@link OrderPartner#label} here
	 * — which is what the merge base did — makes both say {@code Atropine}, also measured, so the
	 * merge base named that prescription once and this head names it twice. Nothing in the suite pins
	 * it; the condition is per RULE — the rule's token claiming one row and not the other — so no
	 * class-only chip can read it, and closing it means demoting the WHOLE partner when any rule about
	 * it refuses the elected row, which no single chip site can decide. It needs the order's display to
	 * carry the route parenthetical the knowledge base's row uses; with an ordinary display
	 * ({@code Atropine 1% eye drops}) this head is consistent and better than the merge base.
	 *
	 * <p><b>What this method deliberately does NOT do is refuse an order-supplied display</b> (issue
	 * #339, review rounds 5 to 7). Rounds 5 and 6 had it step a display this response had refused for a
	 * co-medication ({@code CoMedications.displayNamesAnotherChipSubject}, since removed) back to the
	 * rung the ladder used before {@link OrderPartner#nameByOrder} — the dataset's name for the
	 * substance {@link #soleSubstanceOf} resolved. Round 7 measured what that costs and reverted it.
	 * An ORDER-rung partner is one holding a code the loaded dataset can name no entry for, and
	 * {@link #classRelationships} cites its subgroup over ALL of the partner's codes — so where the
	 * shared subgroup came from that uncovered code, {@link OrderPartner#labelEntry} does not publish
	 * it and naming it states a class membership false of the drug named. That is the chip
	 * {@link OrderPartner#nameByOrder}'s own javadoc exists to prevent, issue #161's
	 * right-finding-wrong-reason shape, and it reaches the prompt verbatim through
	 * {@code DrugReferenceInjector.renderFinding} as a citable {@code safety_finding}.
	 *
	 * <p>Measured through the real {@link #validate} over the shipped knowledge base. One
	 * {@code Dorzolamide / Timolol} order (codes {@code S01ED51}, uncovered, and {@code S01EC03}) asked
	 * {@code "Can I give her timolol and levobunolol?"} read {@code Levobunolol is in the same ATC
	 * class (S01ED) as active order Dorzolamide (ophthalmic)} beside a rule chip saying
	 * {@code active order timolol}: a false claim AND two disjoint names for one prescription, where
	 * both the merge base and this head say {@code Dorzolamide / Timolol} in both. One
	 * {@code Ibuprofen / Famotidine} order ({@code M01AE51}, uncovered, and {@code A02BA03}) named that
	 * one prescription FOUR ways in one response — {@code famotidine}, {@code ibuprofen},
	 * {@code Famotidine}, {@code famotidine} — two of them under an M01AE claim famotidine does not
	 * answer. Over a population of 396 synthetic arrangements, one per level-4 subgroup the shipped
	 * data populates with two substances, each a partly-covered combination order whose uncovered code
	 * is in that subgroup: 397 class sentences named a drug that does not publish the subgroup they
	 * cite under round 6's reading, and 0 do here.
	 *
	 * <p><b>That is pinned on the TRUTH now and was not before, which is how rounds 5 and 6 shipped
	 * green</b>: {@code PartialOrderCoveragePartnerTest}'s
	 * {@code everyClassSentenceAboutAPartlyCoveredOrderNamesADrugThatPublishesTheCitedSubgroup} asserts
	 * of this method's own sentence, and
	 * {@code .aFoldedClassSentenceAboutAPartlyCoveredOrderNamesADrugThatPublishesTheCitedSubgroup} of
	 * the fold's, that the drug named is filed under the subgroup cited — resolved through the loaded
	 * dataset rather than against a literal. Both were re-measured at issue #339's review round 11
	 * head, on the RUNGS themselves rather than on the gated refusals rounds 5 and 6 actually shipped,
	 * and the two mutations are told apart by the SECOND case rather than by counts: electing on
	 * the ORDER rung here reddens BOTH of them, while reconciling onto the rule's token at
	 * {@link #reconciledPartnerName}'s ORDER rung reddens the FOLDED one and leaves the class-ONLY one
	 * green — so the folded case is not a duplicate of its neighbour. Neither mutation is confined to
	 * that class and no count is published for either. {@code theMergedPartnerIsNotNamedAfterHalfOfACombination}
	 * beside them STATES this rule in a comment: it reddens on the first mutation and NOT on the
	 * second, because it asserts a label, and its arrangement is one where every candidate name is
	 * truthful — which is also why it survived rounds 5 and 6 themselves, whose refusals its
	 * arrangement never raises, and why round 7's own cases are stated over the class SENTENCE.
	 *
	 * <p>So the display stands. It is the one name true of every code the partner holds, which is what
	 * this sentence needs and what {@link OrderPartner#nameByOrder} chose it for; the reading it leaves
	 * — a lead naming a prescription that contains the chip's own subject as well as its partner — is
	 * the residue issue #339 accepts, asserted by
	 * {@code OneOrderNameAcrossOneResponseTest.aPrescriptionNamingTheSubjectAndThePartnerIsStillNamedOnceByItsOwnDisplay}.
	 * Rounds 5 and 6 also made a prescription's name depend on the QUESTION, since the refused set was
	 * built from the question's own drugs — the second half of what issue #339 forbids, and invisible to
	 * {@code theSamePairIsNamedTheSameWayWhicheverQuestionReachedIt}, whose arrangement raises no
	 * refusal.
	 *
	 * <p>It costs no dataset sweep: {@link SubstanceSubjects} memoises its answer per substance for the
	 * pass, so {@code CoMedicationResolutionPerPassTest}'s flat-sweep invariant is untouched.
	 */
	private static String classPartnerName(OrderPartner partner, SubstanceSubjects subjects) {
		return partner.labelEntry != null && partner.namingOrder == null
				? subjects.subjectOf(partner.labelEntry).displayLabel() : partner.label;
	}

	/**
	 * The one name a rule chip calls its active-order partner by, folded or not — {@code null} when the
	 * ladder reached no co-medication for that partner at all, which leaves the chip on
	 * {@link #partnerLabel} exactly as it has always been.
	 *
	 * <p><b>Issue #339.</b> {@link #reconciledPartnerName} used to be asked only where a class sentence
	 * folded onto the chip, so which name an order got was decided by whether the class arm happened to
	 * have something to say about it: a partner sharing a class was named by the ladder, every other
	 * partner by the knowledge base's own match token. One response therefore used two conventions, and
	 * the same pair changed name between a drug-in-play question (which can fold) and a screening
	 * question (which cannot). This is that question asked at every rule chip instead, in both
	 * active-order arms, through the SAME gate — one gate and not two, which is the point; what the
	 * widening cost that gate is recorded on {@link #reconciledPartnerName} itself and is not restated
	 * here. What it cost the CHIP LIST is a second thing and lives at {@link StatedInteractionChips}:
	 * two rules about one prescription, named alike by this method, can now render one sentence twice.
	 *
	 * <p>The partner is found from {@link SubjectRule#partner}, the entry {@link #activeOrderEntryFor}
	 * already resolved for this rule and the module's one answer to "which partner is this rule about",
	 * rather than by a second correlation of rules to co-medications.
	 *
	 * <p><b>That entry and the co-medication list are two different resolutions of one chart, and
	 * {@link CoMedications#partnerNaming} is where they are bridged</b> — read its rungs before
	 * changing what this declines on. {@link DrugReferenceService#findForActiveOrders} is additive (ATC
	 * ∪ NAME) while {@link #orderPartners} walks the chart's codes and resolves the rest by name, so one
	 * PRESCRIPTION can resolve to two reference substances with a partner keyed on only one of them; a
	 * lookup that answered null there left this chip on {@link #partnerLabel} beside another chip about
	 * the same prescription that reconciled, which is issue #339's own shape (review round 2).
	 */
	private ReconciledPartner reconciledPartnerFor(SubjectRule rule, SubstanceSubjects subjects,
			CoMedications coMedications) {
		OrderPartner partner = coMedications.partnerNaming(rule.partner);
		return partner == null ? null
				: reconciledPartnerName(partner, rule.rule, subjects, coMedications);
	}

	/**
	 * @return whether {@code rule}'s own match TOKEN names {@code order} — the test
	 *         {@link #reconciledPartnerName} needs before it lets a name an ORDER supplied stand in the rule
	 *         sentence.
	 *
	 *         <p>Through {@link DrugReference#matchesOrderName}, which is exactly the predicate
	 *         {@link PatientClinicalContext#hasActiveDrug} applied to admit this rule in the first place.
	 *         So this asks no new question about the pair — it asks that SAME question of one string
	 *         instead of the patient's flattened name list, and the narrowing is the whole of it: a rule
	 *         admitted because some OTHER prescription carries its token must not be printed under this
	 *         one's name, which is the {@code Naproxen}-renamed-after-{@code Esomeprazole} shape
	 *         {@link #reconciledPartnerName} records. Whether it can WIDEN anything is answered below, and
	 *         not by the superset argument that used to stand here: the flattened set contains every
	 *         name of every order, but the operand is now the order's DISPLAY, which a caller can supply
	 *         without putting it among that order's names at all.
	 *
	 *         <p><b>Asked of the NAME that is about to be printed</b> — {@code order.getDisplay()}, the
	 *         very string {@link OrderPartner#nameByOrder} handed to the label and the one this method's
	 *         answer licenses into the rule sentence. Two narrowings, and they are the same argument
	 *         applied twice. It is asked of the ORDER {@link OrderPartner#namingOrder} carries, not of
	 *         every order that merged into the partner: a partner can be reached by several orders
	 *         ({@link #ordersCarrying}) and {@link OrderPartner#nameByOrder} is monotone, so the first
	 *         carrier that can name itself is both the one the label came from and the one this
	 *         validates, and asking the whole carrier set would prove a fact about one prescription and
	 *         print another's name. And it is asked of that order's DISPLAY, not of every name that
	 *         order carries, because since issue #293 one order's names need no longer be one drug's:
	 *         {@code drugNonCoded} is a name source that can disagree with the coded drug's name or with
	 *         the concept's, and either arrangement is savable on a stock install
	 *         ({@code drugOrder.requireDrug} defaults to false). Scanning them all would prove a fact
	 *         about one NAME and print another — measured before this narrowing, a coded {@code ASPIRIN}
	 *         order carrying the free text {@code Warfarin 5mg} printed the seed's UNRATED warfarin
	 *         rule as {@code Ibuprofen interacts with active order ASPIRIN}, with warfarin
	 *         nowhere in the detail, and thence into the prompt as a citable {@code safety_finding}
	 *         carrying {@code STRENGTH_WITHHOLD}.
	 *
	 *         <p><b>It is not only a narrowing, and calling it one would be false.</b> A
	 *         builder NAMES an order by the first of the names it collected, so for such an order the
	 *         display is one of its names and the old reading is a superset of the new one there — the
	 *         move refuses more and permits nothing. (Its code-only stand-in has a display that is not
	 *         among its names, and cannot reach this method at all, for the two reasons below.) Where the display is NOT among
	 *         the names it can also PERMIT more — a caller-built order with a real display
	 *         and no match tokens, which the public constructor admits and
	 *         {@link PatientClinicalContext.ActiveDrugOrder#hasKnownName()} exists to tell apart from
	 *         the code-only rung — the old reading refused because the order offered no name to put the
	 *         token to, which is an artefact of the very names-empty PROXY that flag replaces, and the
	 *         chip then carried the rule's token beside the display. The new reading puts the token to
	 *         the display, which names it, and hands one name to both sentences.
	 *         {@code FoldedChipOnePartnerNameTest.anOrderWithNoMatchTokensIsStillJudgedOnTheNameItIsAboutToPrint}
	 *         pins that leg, because the whole api suite is green under either reading of it.
	 *
	 *         <p>What the narrowing gives up is a partner whose token
	 *         names one of the order's OTHER names but not its display — a brand display over a generic
	 *         concept name — which falls back to the rule's own token and so names one order two ways
	 *         across the folded chip's two sentences, issue #136's pre-existing shape. A confusing chip
	 *         is the better failure than a false one.
	 *
	 *         <p>ONE shape has nothing to compare, and it refuses rather than counting as agreement: a
	 *         rule with no token carries no name for its partner at all ({@link #partnerLabel} falls back
	 *         to the ATC code), so nothing about the order can license one — which is why a token-less
	 *         rule keeps naming its partner by that code. Until issue #293 there were TWO, the second
	 *         being an order with no names to put the token to; that shape is now judged on its display
	 *         like any other, which is the permitting leg above. The {@code namedByCodesOnly} stand-in of
	 *         issue #290 is excluded not by having no names but for TWO independent reasons:
	 *         {@link #displayNamesADrug} answers false for it, so {@link #reconciledPartnerName}'s
	 *         {@code !namesADrug} branch returns first, and since issue #298
	 *         {@link OrderPartner#recordNameSource} also leaves its {@link OrderPartner#namingOrder}
	 *         null, so the branch that calls this would not be entered either. The second was added
	 *         without retiring the first, deliberately — ADR Decision 40.
	 */
	private static boolean namesNamingOrder(DrugReference.Interaction rule,
			PatientClinicalContext.ActiveDrugOrder order) {
		if (ChartSearchAiUtils.isBlank(rule.getToken())) {
			return false;
		}
		String token = rule.getToken().trim();
		return DrugReference.matchesOrderName(order.getDisplay(), token);
	}

	/**
	 * @return true when {@code rule}'s own match TOKEN names {@code entry} and names no OTHER substance
	 *         in the loaded dataset as strongly — the test {@link #reconciledPartnerName} needs before it
	 *         lets the class arm's label displace that token.
	 *
	 *         <p><b>Two halves, and the second one is why this method exists at all.</b> The first is
	 *         {@link DrugReference#isNamed} through {@link #namesEntry}, name identity between two
	 *         reference strings, which is what CLAUDE.md names that accessor for. On its own it is
	 *         NOT ENOUGH, and the reason is a property of this knowledge base rather than a hypothetical:
	 *         the {@code ddinter} parser writes every entry's aliases from its name AND its
	 *         {@code rxnorm_name} ({@link DdiDrugReferenceSource}), and rows exist whose
	 *         {@code rxnorm_name} is ANOTHER substance's name. The shipped KB's row named
	 *         {@code Omeprazole} carries {@code rxnorm_name: esomeprazole} — the same row CLAUDE.md and
	 *         {@link #classRelationships} already cite for publishing only esomeprazole's
	 *         {@code A02BC05} — so {@code isNamed("esomeprazole")} is TRUE of it, and the identity test
	 *         alone would have licensed the displacement on exactly the pair this guard exists to refuse.
	 *         Measured through the real {@link DdiDrugReferenceSource#parse} over the pinned excerpt:
	 *         that row is {@code name=Omeprazole, rxnorm_name=esomeprazole}.
	 *
	 *         <p><b>The second half is a RANKING, not an existence test (issue #296).</b> It asks
	 *         {@link DrugReferenceService#uniqueStrongestClaimant}, the repo's one definition of "this
	 *         row claims the name and strictly outranks every rival", over every row of a DIFFERENT
	 *         substance the token names. Existence — "does the token name anything else?"
	 *         — cannot separate the two arrangements that matter, and they are opposites: the token is
	 *         the LADDER's substance's own display name and merely the rival's {@code rxnorm_name} alias,
	 *         where displacing is a spelling normalisation of one substance's name; or the token is the
	 *         RIVAL's display name and merely the ladder's alias, where displacing prints one substance's
	 *         rated mechanism under another's — the #161/#187/#194 failure, silently, in a
	 *         clinician-facing sentence and in the citable {@code safety_finding} record that carries it
	 *         verbatim. {@link DrugReference#nameMatchStrength} is what tells those apart, and the
	 *         comparison is not spelled out here for the reason its own javadoc gives.
	 *
	 *         <p>Issue #292 shipped the existence form and ADR Decision 39 recorded its cost. A tie still
	 *         refuses: two substances claiming the token equally cannot say which of them the rule is
	 *         about, which is the answer the existence form gave to every contested token.
	 *
	 *         <p><b>{@code entry}'s OWN claim, never its substance's strongest.</b> The label about to be
	 *         printed is this row's display, so a sibling presentation's stronger claim on the token says
	 *         nothing about this row. <b>Since issue #339 one rung can ask this TWICE about one
	 *         partner</b>, and that is not a relaxation of the sentence just made: the ENTRY rung offers
	 *         the row the response elects and then, if that row's own claim does not carry it, the
	 *         ladder's own row — two asks, each about the row the caller would then PRINT, and a refusal
	 *         of both is still a refusal. What is never done is carrying one row's answer to another
	 *         row's display, which is what the paragraph below rules out. The rungs also differ on which
	 *         row arrives here:
	 *         {@link #entryForAtcCode} hands over {@link DrugReference#canonicalRow}'s pick, while
	 *         {@link #addPartnersForUnmappedOrders} hands over the row the patient's own chart claims
	 *         most strongly, deliberately the route-qualified one where the chart named it that way. Over
	 *         the shipped KB, {@code Atropine} claims the token {@code atropine} outright while its
	 *         {@code Atropine (ophthalmic)} presentation and the separate {@code Hyoscyamine} substance
	 *         each claim it only as an alias — so reading the substance's strongest row would let
	 *         {@code Atropine}'s claim carry a displacement onto the label
	 *         {@code Atropine (ophthalmic)}, for a rule that may be {@code Hyoscyamine}'s. Rows of
	 *         {@code entry}'s own substance are excluded from the contest instead of contesting it, or an
	 *         uncontested token would start refusing wherever a sibling row claims it at least as
	 *         strongly — which on DDInter is every token that is not some row's own display name, the
	 *         rows of a substance sharing one alias list. A case apiece, because neither can see the
	 *         other's choice: {@code FoldedChipOnePartnerNameTest.aRuleTokenTheLaddersRowOnlyTiesKeepsItsOwnToken}
	 *         reddens if the row's claim gives way to its substance's, and
	 *         {@code aSiblingRowOfTheLaddersOwnSubstanceDoesNotContestTheToken} if the exclusion goes.
	 *
	 *         <p>Not exotic, and not a shape a curated fixture can stand in for: over the shipped KB,
	 *         25 of its 2093 distinct rule tokens are named by more than one substance
	 *         ({@code esomeprazole}, {@code hydrocortisone}, {@code trastuzumab}, {@code gabapentin},
	 *         {@code ketoconazole} …). A hand-written JSON fixture gives each row one self-name and so
	 *         refuses the displacement for a reason the default dataset does not share, which is why
	 *         {@code FoldedChipOnePartnerNameTest} pins both directions in DDINTER shape, through the
	 *         real parser.
	 *
	 *         <p><b>What "substance" means here depends on the source.</b> {@link DrugReference#substanceGroupKey()}
	 *         falls back to the ENTRY where a source publishes no substance name — the curated {@code json}
	 *         and {@code atc} adapters — so on those every other ROW is a rival, which is strictly
	 *         stricter and refuses more. Identity is the right comparison for that fallback
	 *         because every {@code labelEntry} comes from {@code getAll()}'s own cached objects and
	 *         {@link DrugReference} defines no {@code equals}.
	 *
	 *         <p><b>What the ranking does NOT decide.</b> That the token denotes the ladder's substance
	 *         is a fact about NAMES; the DDInter row a rule was authored on is a separate fact, and
	 *         {@code ddinter} derives a rule's token from the partner row's {@code rxnorm_name}, so a
	 *         rule authored on {@code Levoketoconazole} carries the token {@code ketoconazole} too. The
	 *         reconciled chip therefore prints that rule's prose under {@code Ketoconazole} — the same
	 *         substance the token already named, so nothing NEW is asserted, but the prose can name the
	 *         other row. Measured over the shipped KB: subjects carrying two above-floor rules under the
	 *         one token {@code ketoconazole} are common, and {@code Osilodrostat} is one of them. That is
	 *         {@link #partnerLabel}'s pre-existing property — before issue #339 the unfolded chip
	 *         printed that same token, and since #339 it prints this same reconciled name, so the two
	 *         chips agree either way and neither asserts more than the token did — and closing it means
	 *         choosing between two rules, which is {@link #bestRulePerPartner}'s question, not this
	 *         one.
	 *
	 *         <p><b>So a CONTESTED token can only be admitted at {@link DrugReference#NAME_IS_THE_DISPLAY_NAME},
	 *         and — measured, not derived — the reconciled label is then that token re-cased.</b>
	 *         Every rival reaching the comparison
	 *         passed {@link #namesEntry}, so the token IS one of that rival's stored aliases; those are
	 *         stored trimmed ({@link DrugReference#setAliases}) and the loader leaves none that names
	 *         nothing ({@code DrugReferenceValidity.sanitizeAliases}), so on a loaded dataset identity
	 *         implies containment and the rival ranks at least
	 *         {@link DrugReference#NAME_IS_ANOTHER_NAME}. Nothing strictly outranks
	 *         that but the top rank. It is the trim that makes this a derivation rather than a hope —
	 *         untrimmed, a padded alias answers {@link DrugReference#isNamed} true and
	 *         {@link DrugReference#NAME_NO_MATCH}, and such a rival drops out of the contest entirely.
	 *         The LABEL half is a separate fact and only measured: the top rank says the token is the
	 *         row's {@code name}, while the ladder prints {@link DrugReference#displayLabel()}, which can
	 *         append a diverging generic — the shipped {@code Hyoscyamine} row renders
	 *         {@code Hyoscyamine (atropine)}. Measured over the shipped KB: of the 18 contested pairs
	 *         this admits, 18 are at the top rank and none has a label differing from the token by
	 *         anything but case; if that goes stale the downstream bound goes with it. That is what
	 *         bounds the change downstream — the reconciled sentence names the same substance by the
	 *         same string, so the prompt's name union for that partner cannot move, and
	 *         {@link SubjectRule#partnerKey} case-folds to the label the chip now renders rather than
	 *         away from it. The UNCONTESTED path is unchanged and still admits at either rank, which is
	 *         where a label like {@code Acetylsalicylic acid (aspirin)} for the token {@code aspirin}
	 *         comes from.
	 *
	 *         <p><b>Since issue #297 that bound has a SECOND surface, and on it the bound is a
	 *         DERIVATION rather than the measurement above.</b> {@link #reconciledPartnerName}'s ENTRY rung
	 *         hands the injected {@code drug_reference} note {@code labelEntry.getName()} under this
	 *         very gate, so this widening moves prompt text there as well as on the chip's
	 *         {@link DrugReference#displayLabel()}. The top rank IS
	 *         {@code normalizeName(token).equals(normalizeName(name))} and both that name and
	 *         {@link #partnerLabel}'s token reach the note trimmed, so for a CONTESTED token the note's
	 *         name is that token re-cased whatever the chip's label appends. The measured half is the
	 *         chip's alone; it says nothing about this rung and does not need to.
	 *
	 *         <p><b>The name is kept although the criterion moved.</b> "Unambiguously" now means the token
	 *         has a unique strongest claimant and this is it, rather than that it names one substance —
	 *         a reading the paragraphs above state and ADR Decisions 39 and 52 both use. Renaming would
	 *         orphan every reference issue #292 left behind, in this file, in {@code CLAUDE.md} and in the
	 *         ADR, for a word that is still true under the definition given here.
	 *
	 *         <p><b>It rests on the alias list being stored TRIMMED, and that was not free.</b> The rank
	 *         floor inside {@link DrugReferenceService#uniqueStrongestClaimant} runs
	 *         {@link DrugReference#nameMatchStrength}, which gates on {@link DrugReference#matchesDrugName};
	 *         that predicate trims neither operand while {@link DrugReference#isNamed} trims both. So
	 *         before {@link DrugReference#setAliases} trimmed, a curated entry named only by a PADDED
	 *         alias passed this method's first gate and failed the floor — losing a reconciliation the
	 *         existence form had made — and the same padding on a RIVAL row dropped that row out of the
	 *         contest and licensed a displacement, which is the #161/#187/#194 failure the ranking exists
	 *         to prevent. Both measured through the real {@code JsonDrugReferenceSource} and the real
	 *         {@link #validate}, and both closed at the stored string rather than in either predicate,
	 *         for the reasons {@code setAliases} records.
	 *         {@code FoldedChipOnePartnerNameTest.aPaddedAliasNamesTheOneOrderOnce} reddens if that trim
	 *         is removed.
	 *
	 *         <p><b>No longer a sweep of {@code getAll()}, and issue #339 is why.</b> This used to walk
	 *         the dataset for the token's rival claimants on every call, kept uncached because it ran
	 *         once per FOLDED chip — the rare outcome of the class arm rather than the ordinary one.
	 *         Since #339 it runs once per rule CHIP, and the number of distinct (token, entry) pairs a
	 *         pass reconciles grows with the drugs in play, which
	 *         {@code CoMedicationResolutionPerPassTest.theCoMedicationResolutionDoesNotGrowWithTheDrugsInPlay}
	 *         forbids. <b>A memo over the asks cannot restore that</b> — the asks themselves grow, so
	 *         de-duplicating the repeats leaves the difference rising — which is why the remedy is to
	 *         invert the dataset ONCE per pass instead: {@link DrugReferenceService#nameIndex()}, read
	 *         back through {@link DrugReferenceService#entriesNamedBy}. One walk is one walk however
	 *         many chips read it. The index is a per-pass LOCAL held on {@code CoMedications} and never
	 *         a field on either bean — CLAUDE.md's issue #172 rule, whose reasons are on
	 *         {@link DrugReferenceService}.
	 */
	private boolean unambiguouslyNames(DrugReference.Interaction rule, DrugReference entry,
			Map<String, List<DrugReference>> nameIndex) {
		String token = rule.getToken();
		if (!namesEntry(token, entry)) {
			return false;
		}
		Object substance = entry.substanceGroupKey();
		List<DrugReference> rivals = new ArrayList<DrugReference>();
		// Every entry the token names, read off the pass's inverted index rather than found by walking
		// the dataset here. The membership test is not dropped, it has moved: the index is keyed on
		// DrugReference.nameKeys(), the inverse of the very predicate namesEntry asks, so everything
		// this loop is handed already satisfies it. Walking was affordable while this ran once per
		// FOLDED chip; issue #339 asks it once per rule chip, and the asks grow with the drugs in play
		// while one index build does not — see DrugReferenceService.nameIndex().
		for (DrugReference candidate : DrugReferenceService.entriesNamedBy(token, nameIndex)) {
			if (!substance.equals(candidate.substanceGroupKey())) {
				rivals.add(candidate);
			}
		}
		// entry's OWN claim against the rivals' — see this method's javadoc for both halves of that.
		return DrugReferenceService.uniqueStrongestClaimant(token, entry, rivals);
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
	 * invariant holds for every UNFOLDED chip: the key IS what the chip says. A folded chip can name that
	 * partner by the class arm's ladder instead since issue #292 — the grouping is unaffected, since it
	 * runs before the fold and on this same key, but the rendered name and the key can differ. See
	 * {@link #reconciledPartnerName}, which is where that departure and its cost are recorded.
	 *
	 * <p><b>Which row wins.</b> The most severe rating, then — since issue #162 — the row naming no
	 * route, then the longer note; longer in prose, not in whitespace, see {@link #noteLength}. The full
	 * ordering and the measurement behind the middle step live on {@link #outranks}. Route variants
	 * genuinely differ — topical dexamethasone does
	 * not have systemic dexamethasone's interaction profile, which is why DDInter rates voxelotor Major
	 * against systemic dexamethasone, Moderate against two others and carries no row at all against the
	 * topical variant — but this layer still cannot resolve which variant the order is, and since issue
	 * #234 that is a DATA-side limit rather than a context-side one. The context does now carry what the
	 * chart records about where each order is applied
	 * ({@link PatientClinicalContext.ActiveDrugOrder#getAdministrationTerms()}), and it is deliberately
	 * not read here: a rule ROW is what has to be selected, DDInter files each route variant as its own
	 * row, and all four of them publish an identical ATC list — so nothing relates a recorded route to a
	 * row. That is the data-side half of #115, and it is what issue #234 could not close;
	 * {@link DrugReference#codesForRecordedAdministration} narrows CODES for exactly that reason.
	 * Reporting the strongest rating over-warns rather than
	 * under-warns on a non-blocking advisory the clinician adjudicates, which is the fail-safe
	 * direction; the accepted cost is that a patient on a topical form may see the systemic
	 * severity. The note length is the only informativeness signal a
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
	 * protect. And two tokens naming two DIFFERENT entries stay two RULES here even when one order name
	 * matches both — two chips too, unless the two render the same sentence once their partner is
	 * named, which is {@link StatedInteractionChips}' question and not this grouping's: across the full KB exactly one such pair exists — {@code enalapril} and
	 * {@code enalaprilat}, which 376 entries carry as separate partners, and which a single order
	 * named "Enalaprilat 1.25 mg" matches through the order-name matcher's inflection tolerance.
	 * Prodrug and active metabolite are genuinely different DDInter entries, so that pair is reported
	 * rather than merged.
	 *
	 * <p><b>Which pairs that 1 counts</b> (issue #263). Pairs of dataset ENTRIES both matched by ONE
	 * order name: a population of its own, and neither of the subgroup-sharing populations
	 * {@link #sharedClass} counts, which relate two entries by ATC and not by a name. The two BASES
	 * {@link #sharedClass}'s javadoc defines do apply to it, and the answer is 1 on both. Measured
	 * 2026-08-29 over the shipped KB through {@code DdiDrugReferenceSource.load},
	 * {@link DrugReference#substanceGroupKey()} and {@link DrugReference#canonicalRow}:
	 * {@code Enalapril} and {@code Enalaprilat} are one row each and fall into two substance families,
	 * so the one ROW pair is also one SUBSTANCE pair. The 1 itself is the pre-existing figure and was
	 * not re-derived.
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
	 *         <p><b>The predicate it reads was corrected by issue #250, and this is the second site of
	 *         that defect rather than a bystander.</b> {@link DrugReference#namesNoRoute()} used to read
	 *         a trailing parenthetical as a qualifier even where it was the name the data files the row's
	 *         family under, so for 4 of the shipped KB's 2283 rows this step preferred the wrong side:
	 *         the tick-borne encephalitis chip was NAMED after the substance row while the prose under
	 *         that name was credited to the {@code (whole virus, inactivated, pediatric)} row — the very
	 *         failure the paragraph above describes, inverted. Measured 2026-08-30 through the real
	 *         {@link #bestRulePerPartner} over every multi-row family of the shipped KB, and through the
	 *         question-PAIR arm's own entry-pair walk: <b>222 of 40,619</b> drug-in-play groups and
	 *         <b>97 of 951</b> question-pair groups change which row wins, and exactly <b>1</b> changes
	 *         the rendered note text — the influenza A/Vietnam family against
	 *         {@code ozanimod}, where the fuller of the two notes now survives. <b>0 of the 319 change
	 *         severity</b>, and that figure is ENTAILED rather than independently observed: this method
	 *         compares {@link #severityPriority} first and returns wherever the two differ, so the route
	 *         step is reached only at equal severity and a group's winner is maximal in severity whatever
	 *         the steps below it decide. <b>4</b> of the 97 change
	 *         which SUBSTANCE owns the sentence, every one of them from a Moderna COVID-19 vaccine
	 *         PRESENTATION row ({@code (6m-5y)}, {@code (6m-5y bivalent booster)}, {@code (6y-11y)},
	 *         {@code (booster only)}) to the row the data names the tick-borne substance after, at
	 *         identical severity and byte-identical prose — the direction this step exists to move in,
	 *         with both drugs still named in the sentence because both were named in the question.
	 *         {@code SubstanceNameRowTest.aQuestionPairSentenceIsOwnedByTheSubstanceRowAndNotByAPresentationOfTheRival}
	 *         pins that arm over a verbatim slice, and it carries TWO Moderna rows so that the pair whose
	 *         rival names no route can be seen NOT moving beside the one that does.
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
	 * numbered, citable record by {@code preAnswerFindings}, carrying this chip's string verbatim and
	 * the strength clause after it ({@link DrugReferenceInjector#renderFinding}, issue #283 — a
	 * question-pair finding is an INTERACTION, so it states one like every other), so a pair
	 * finding's grounding comes from that record rather than from the promoted notes, and the
	 * promoted-note budget is untouched. <b>Verbatim across passes too, not only within one</b>:
	 * {@code renderFinding} copies the chip the PRE-ANSWER pass raised, and between issue #236 and issue
	 * #238 this arm's subject was folded over a group the answer widens, so the record the model reads
	 * and the chip the clinician sees could name one substance two ways — ADR Decision 49 is canonical
	 * for that measurement. Issue #238 (ADR Decision 53) closed it: this arm's subject now comes from
	 * {@code namingGroups}, which every question drug is already in, so the answer cannot move it — see
	 * {@link SubstanceSubjects}. What the two cannot do, before or after #238, is describe one finding
	 * differently within a pass. That half is worded to match
	 * {@link DrugReferenceInjector#orderedInteractionNotes}, which this paragraph is paired with —
	 * each cites the other — because the two came apart once already, when only one of them was
	 * reworded for the clause.
	 *
	 * @param subjects the one per-{@code validate} answer to "which row does this response call this
	 *        substance by", shared with every other arm since issue #236 — never a fold over this arm's
	 *        own rows, which is what let one response name one substance two ways. Every question drug is
	 *        in {@code inPlay}, so its substance is always in that lookup's group map and the
	 *        ungrouped-row fallback is unreachable from here.
	 */
	private PairChipExtent addQuestionPairInteractions(List<SafetyWarning> warnings,
			Set<DrugReference> questionDrugs, SubstanceSubjects subjects, PatientClinicalContext context,
			int severityFloor) {
		if (questionDrugs.size() < 2) {
			// The arm did not run: one drug is not a pair, so there is no candidate list to state the
			// extent of. Null, never a zero — see PairChipExtent for what the two say differently.
			return null;
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
			// the same shape the screening arm's own early return takes. The extent is still STATED, and
			// it is the whole reason zero is a measurement here: this pair list is complete, and a
			// caller that heard nothing could not tell that from an arm that never ran (issue #336).
			// Math.min(0, cap) is 0 whatever the cap, so stating it still reads no global property.
			return PairChipExtent.of(0, 0);
		}
		Collections.sort(found, PAIR_SEVERITY_DESCENDING);
		int cap = maxPairChips();
		int shown = Math.min(found.size(), cap);
		if (shown < found.size()) {
			// WARN, not INFO: which pairs went, and at what ratings, is an operator's diagnostic and it
			// lives only here — the response states the COUNTS (see the extent returned below) and
			// deliberately not the list, because putting the withheld pairs on the wire is the unbounded
			// expansion this cap exists to prevent. Silent truncation in a safety net reads as "nothing
			// else was found", which since issue #336 the response itself no longer says.
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
		return PairChipExtent.of(found.size(), shown);
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
			Map<DrugReference, String> names, SubstanceSubjects subjects,
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
		//
		// The shared lookup since issue #236, not a fold over this arm's own rows: that fold (the old
		// canonicalSubjects, deleted at #236) saw only the question's drugs. This comment used to say
		// every OTHER arm folds the rows the question, the ANSWER and the patient's ORDERS resolved, and
		// that a substance whose rows publish different alias sets could be named one thing here and
		// another by the chip beside it. That was true between issues #236 and #238 and is NOT true now:
		// issue #238 (ADR Decision 53) moved every arm's naming decision onto namingGroups, which folds
		// the question's drugs and the patient's ORDERS and never the ANSWER — see this method's own
		// javadoc above and SubstanceSubjects. Both slots still read the shared lookup, because both are
		// clinician-facing names for a substance in this response — that much has not changed.
		//
		// Nothing this arm collapses on reads the resolver — it keys its candidates on
		// pairKeyNames/unorderedPairKey and ranks them with outranks(row, rule, …) — so the chip COUNT
		// does not move with the name, which is the check issue #236 owes. That is the property
		// canonicalSubjects' javadoc stated as
		// "label-only, and deliberately so" before it was deleted, and the one the ticket warns about,
		// #205's hardening having turned 2 chips into 4 with a more precise resolver and an unmatched
		// ledger key. Read it off the code rather than off a probe count: put the resolver's answer into
		// the pair key and the count moves; nothing here does.
		//
		// So the name in either slot can still be a row the question's own word did not spell — through
		// the CHART, where the patient's own orders resolve a different row of the family than the
		// question's word did. NOT through the answer any more: both slots here are questionDrugs rows,
		// so groupOf always resolves them from namingGroups, which the answer cannot widen (issue #238) —
		// PerRequestSubstanceSubjectTest.theQuestionPairArmNamesTheSubstanceAlikeAcrossBothPasses pins
		// exactly that. The chart leg is the settled reading of a chip and not a widening of the claim: a
		// chip's subject is a SUBSTANCE (issue #162), the sentence's clause says the pair came from the
		// question rather than from the chart, and the module already reports what the TEXT stated under
		// a row the text did not spell — "The stated Amoxicillin (suspension) dose …" for an answer
		// saying "amoxicillin" (issue #245, pinned by
		// OrderedSubjectRowTest.everyArmOfOneResponseNamesOneSubstanceOneWay). The row named is the one
		// the patient's own record claims most strongly (#187/#192/#194), never the one this walk
		// reached, which is what the paragraph above refuses.
		DrugReference subject = subjects.subjectOf(fromFirst ? first : second);
		DrugReference partner = subjects.subjectOf(fromFirst ? second : first);
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
	 * list {@code matchesText} scans — since issue #330 through the entry's folded view of it, which is
	 * that same list element for element — so #128's change to how it is scanned cannot drift from it.
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
	 * screening arm below cannot word the same finding differently by accident.
	 *
	 * <p>They do differ deliberately, and since issue #339 it is the {@code alsoSameClass} ARGUMENT
	 * rather than the overload that says so — both arms call the overload now, to name their partner.
	 * Only the drug-in-play arm can fold, and since issue #283 the fold reaches the strength the
	 * injected record states rather than its wording alone — see
	 * {@link SafetyWarning#carriesUnratedRelationship()}.
	 */
	private static SafetyWarning interactionWarning(DrugReference ref, DrugReference.Interaction i) {
		return interactionWarning(ref, i, partnerLabel(i), null, null);
	}

	/**
	 * As {@link #interactionWarning(DrugReference, DrugReference.Interaction)}, additionally naming the
	 * partner by {@link #reconciledPartnerName}'s answer, and — where the class arm had a finding about
	 * that SAME active order — folding its sentence into this one chip (issue #88).
	 *
	 * <p>The two are independent since issue #339: a chip can be reconciled without folding, which is
	 * every rule chip whose partner the ladder resolved and whose displacement the gate permits. Only
	 * {@code alsoSameClass} decides whether this chip carries the class arm's claim.
	 *
	 * @param partnerName what this chip calls the active order: {@link #reconciledPartnerName}'s answer
	 *        where it reconciled, else {@link #partnerLabel}. Where it REFUSED on a folded chip the
	 *        class sentence keeps the ladder's label, so such a detail still names one order two ways,
	 *        deliberately (issue #292) — and only for #292's own refusal reasons: issue #339 added one
	 *        and review round 7 removed it again, so this list is #292's unchanged (see that method's
	 *        ORDER rung)
	 * @param partnerNoteName what the injected {@code drug_reference} note must call that same order —
	 *        {@link #reconciledPartnerName}'s answer in the RECORD's vocabulary, null where nothing was
	 *        reconciled, which leaves that note on {@link #partnerLabel} exactly as before (issue #297).
	 *        Carried on the warning rather than re-derived by the injector: see
	 *        {@link SafetyWarning#reconciledPartnerNoteName}
	 * @param alsoSameClass the class arm's own sentence about that order ({@link #classRelationships}),
	 *        or null when the class arm says nothing about this partner — in which case the detail is
	 *        byte-identical to what it has always been, so no single-arm chip changes
	 */
	private static SafetyWarning interactionWarning(DrugReference ref, DrugReference.Interaction i,
			String partnerName, String partnerNoteName, String alsoSameClass) {
		// partnerName is partnerLabel(i) only where reconciledPartnerName did not answer — it is the
		// label bestRulePerPartner GROUPS on where the dataset identifies no partner entry, and there
		// #121's grouping is only correct while the key IS the label the chip says. Every other chip
		// passes the reconciled name, so a folded chip's two sentences cannot name one order two ways
		// (issue #292) and two chips of one response cannot either (issue #339). That method is where
		// the conditions under which the ladder's name may displace this one are stated, along with
		// what remains of #121's invariant once it does.
		String detail = ref.displayLabel() + " interacts with active order " + partnerName;
		if (i.getNote() != null && !i.getNote().isEmpty()) {
			detail += " — " + i.getNote();
		}
		if (alsoSameClass != null) {
			detail = endSentence(detail) + " " + alsoSameClass;
		}
		// The rule's own rating travels with the chip (issue #207). Null for a curated hand-authored
		// rule, which is unrated by design — see SafetyWarning.getSeverity, and note that a FOLDED chip
		// still reports the RULE's rating: the class sentence appended to it carries none, so folding
		// cannot lower or raise what the pair is rated. What the fold DOES move is the strength the
		// injected record states — the class sentence is unrated, so a folded warning asserts more
		// than its rating does — which is why it travels beside the rating rather than inside it: see
		// SafetyWarning.carriesUnratedRelationship and licensesWithholding(SafetyWarning) (#283).
		// The RECORD's name for this partner travels on the chip that decided it (issue #297) — see
		// SafetyWarning.reconciledPartnerNoteName, which is also where the rule-identity condition the
		// note has to satisfy before it may take that name lives. Null wherever nothing reconciled,
		// which is what leaves the note on partnerLabel exactly as before — and since issue #339 that
		// is a chip whose partner the ladder did not reach or whose displacement the gate refused,
		// rather than a chip no class sentence folded onto.
		return SafetyWarning.interaction(ref.displayLabel(), detail, i.getSeverity(),
				alsoSameClass != null, partnerNoteName != null ? i : null, partnerNoteName);
	}

	/**
	 * @return {@code detail} closed off as a sentence, so a folded second sentence does not run into
	 *         it. Needed because what a rule chip ends on is authored data: every DDInter note ends in
	 *         a full stop, a curated note need not, and a rule carrying no note at all ends on the
	 *         partner label. Trailing whitespace goes with it — a note padded in the source file would
	 *         otherwise put the gap inside the sentence rather than between the two.
	 *         <p>Package-private because {@link DrugReferenceInjector#renderFinding} appends the
	 *         strength clause (#283) to this same detail and has to break the sentence the same way.
	 *         A second copy of the rule would leave the gap inside one renderer's sentence and
	 *         between the two in the other's, for one string the chip and the record share.
	 */
	static String endSentence(String detail) {
		String trimmed = detail.trim();
		if (trimmed.isEmpty()) {
			return trimmed;
		}
		// The shared terminator set, not a literal of this method's own: since issue #337
		// ReferenceProseFidelityCheck's record-sentence exit depends on the character appended here
		// being one that ChartSearchAiUtils.mayEndASentence recognises, and two spellings of one set
		// is what lets them come apart silently.
		char last = trimmed.charAt(trimmed.length() - 1);
		return ChartSearchAiUtils.SENTENCE_TERMINATORS.indexOf(last) >= 0 ? trimmed : trimmed + ".";
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
	 * <p><b>"The same join" is not "the same finding" since issue #283.</b>
	 * {@link #addInteractionWarnings} also folds the class arm's sentence in (issue #171) and this arm
	 * cannot, because {@link #classRelationships} runs per IN-PLAY substance and a screening question
	 * names none. Only a folded warning carries {@link SafetyWarning#carriesUnratedRelationship()},
	 * which since #283 decides the strength the injected record states, so one Minor-rated pair reads
	 * as a caution here and as a reason to withhold there. The measurement, and the reason it is left
	 * rather than closed, are on that method.
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
	 *
	 * @param subjects the one per-{@code validate} answer to "which row does this response call this
	 *        substance by", shared with every other arm since issue #236 — see the comment at the read
	 *        below for why no chip of this arm can move and what can. Every order entry is added to that
	 *        lookup's group map by {@link #resolvedSubstanceRows}, so the ungrouped-row fallback is
	 *        unreachable from here too.
	 */
	private PairChipExtent addActiveOrderPairInteractions(List<SafetyWarning> warnings,
			SubstanceSubjects subjects, PatientClinicalContext context, int severityFloor,
			List<DrugReference> orderDrugs, InteractionPairs reportedPairs,
			CoMedications coMedications, StatedInteractionChips statedChips) {
		if (context == null) {
			// The arm could not run at all, so it states nothing — not a complete screen of zero pairs.
			return null;
		}
		List<ScreenedPair> pairs = new ArrayList<ScreenedPair>();
		Set<List<String>> seenPairs = new LinkedHashSet<List<String>>();
		// Keyed by the reference data's own name for each drug, not by entry, and resolved once per
		// drug — issue #115's shape reaches the subjects here exactly as it reaches the question drugs
		// in the pair arm, because one order name resolves every route variant sharing an
		// {@code rxnorm_name} and each variant would otherwise be its own subject keying its own pair.
		Map<DrugReference, String> keyNames = pairKeyNames(orderDrugs, severityFloor);
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
		// same drain-once-per-substance idiom validate() uses for the drug-in-play and dose arms, which
		// since issue #206 drains a key set beside a shared row map rather than a map of its own.
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
				// have carried. Since issue #339 that holds for the PARTNER half too, by the label
				// reading the reconciliation's answer rather than re-deriving one.
				//
				// The naming group (SubstanceSubjects.groupOf's namingGroups map) IS this arm's own
				// fold, not merely close to it — since issue #238. This method's only call site
				// (validate(), gated on questionDrugs.isEmpty()) hands namingRows =
				// resolvedSubstanceRows(questionDrugs, orderEntries), which for THIS arm reduces to
				// resolvedSubstanceRows(<empty set>, orderEntries): substanceRows(<empty set>) is an
				// empty map, so the fold does nothing but walk orderEntries and groupFor/add each row
				// in list order — byte-for-byte the `substances = substanceRows(orderDrugs)` map this
				// method builds for itself a few lines above, same membership and the same order.
				// Both rows this loop ever hands subjectOf are drawn from that same orderDrugs list —
				// ref, always one of substance's own rows, and partner, always
				// activeOrderEntryFor(orderDrugs, ref, i)'s answer or null — so the lookup always hits
				// the naming group and never falls through to allGroups (the fuller resolvedRows,
				// built from inPlay, which can differ from questionDrugs wherever the ANSWER named a
				// substance the question and the orders did not). There is no re-ordering to have and
				// no fallback to reach: for this arm the two maps are not merely equal, they are the
				// same construction over the same list, so the WITHHELD-pair WARN label below can
				// never diverge from the chip's. That is a stronger guarantee than the question-pair
				// arm gets (its own comment above, near addQuestionPairInteractions' use of
				// interactionSubject): there, namingRows is substanceRows(questionDrugs) with THIS
				// arm's own order rows appended on top, by design (issue #175) — a substance the
				// question names can still pick up an order row in its naming group that the pair loop
				// itself never iterates, so the two are provably non-empty for every row that arm asks
				// about but not provably the identical construction the way they are here, where
				// orderEntries is the only source either map draws from.
				//
				// ddinter symmetry is what makes a labelling divergence hypothetically possible at
				// all, so it is still worth naming even though the reduction above closes it for this
				// arm: DdiDrugReferenceSource writes each interaction row onto BOTH drugs' entries (its
				// two partners.computeIfAbsent calls), so a ddinter pair's rule is symmetric or absent
				// and only a curated json file could carry different rules per direction.
				// Unified anyway, as a design choice and not only as this arm's happy accident: the
				// arms must not hold two answers to one question, which is what let them disagree
				// before issue #236.
				DrugReference subject = subjects.subjectOf(ref);
				DrugReference partnerSubject = partner != null ? subjects.subjectOf(partner) : null;
				// This arm cannot fold — classRelationships runs per in-play substance and a screening
				// question names none — but since issue #339 that no longer decides what the order is
				// called, or one prescription would answer to two names depending on which question
				// reached it. The same reconciliation, the same gate.
				ReconciledPartner reconciled = reconciledPartnerFor(matched, subjects, coMedications);
				// The label names the partner the way the CHIP does, which since issue #339 is the
				// reconciliation's answer wherever it gave one. It has to: this WARN is the only place
				// a withheld pair surfaces (see maxPairChips), and an operator grepping it for the
				// wording the clinician was shown must find it. Falling back to the subject row's
				// display where nothing reconciled is what it has always done.
				String loggedPartner = reconciled != null ? reconciled.chipName
						: partnerSubject != null ? partnerSubject.displayLabel() : partnerLabel(i);
				SafetyWarning chip = reconciled == null ? interactionWarning(subject, i)
						: interactionWarning(subject, i, reconciled.chipName, reconciled.noteName, null);
				// Before the candidate is collected rather than after the cap, so the extent this arm
				// states counts what a clinician can tell apart: a restatement is not a pair that was
				// found and withheld, it is a pair already shown. Same ledger as the drug-in-play arm —
				// see StatedInteractionChips, and note that a combination prescription reaches this arm
				// with both constituents as partners of one subject exactly as it reaches that one.
				if (!statedChips.isFirstStatementOf(chip)) {
					continue;
				}
				pairs.add(new ScreenedPair(chip,
						severityPriority(i.getSeverity()),
						subject.displayLabel() + " x " + loggedPartner
								+ " (" + ChartSearchAiUtils.firstNonBlank(i.getSeverity(), "unrated")
								+ ")"));
			}
		}
		if (pairs.isEmpty()) {
			// Stated, and stated as zero: this screen ran over the patient's orders and the reference
			// data related none of the pairs it enumerated. That is a COMPLETE screen, and it is the
			// half of issue #336 a truncation signal alone would leave unsaid — a caller hearing
			// nothing cannot tell it from a question that never asked to be screened.
			return PairChipExtent.of(0, 0);
		}
		Collections.sort(pairs, SCREENED_PAIR_SEVERITY_DESCENDING);
		// The same cap the question-pair arm applies, from the same GP — the two gates are mutually
		// exclusive, so one question can never meet both, and two limits for one concept would be
		// arbitrary (issue #131).
		int reported = Math.min(pairs.size(), maxPairChips());
		if (pairs.size() > reported) {
			// Named here, counted on the response. A clinician reading the reported chips could not tell
			// a capped screen from a complete one, which is issue #336 — and the count that closes it is
			// the extent this method returns, not this line. What the log still holds alone is WHICH
			// pairs went and at what ratings, an operator's diagnostic that must not go on the wire.
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
		return PairChipExtent.of(pairs.size(), reported);
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
	 *         code standing as if it were a second order. Measured on the 16-drug DDInter excerpt: one "Aspirin
	 *         and omeprazole" order reported "Acetylsalicylic acid (aspirin) interacts with active order
	 *         esomeprazole — Minor", i.e. the two halves of one tablet as an interacting pair, naming a
	 *         drug the patient is on in no form.
	 *
	 *         <p>A code the subject's own order carries is removed but then RE-ADDED when another order
	 *         carries it too, because removal from a flattened set cannot tell contributors apart: a
	 *         patient on both a combination and a separate order of one of its constituents keeps that
	 *         (genuine, duplicate-therapy) pair, which the pre-#132 reduction had to give up whenever
	 *         only the shared code witnessed it. Codes that no per-order set claims are left alone.
	 *         Since issue #290 the BUILDER produces no such code: an order it cannot name still reaches
	 *         the per-order list, and one with no codes contributes none to the union either, so in
	 *         production every code here has an order behind it. What remains is a HAND-BUILT context
	 *         that supplies an order list AND a wider flattened set — not the flattened-only shape of
	 *         issue #118, which never reaches this block at all (the guard below requires a non-empty
	 *         order list, and the flattened fallback further down is where that residual lives). For a
	 *         hand-built disagreement, leaving the code alone is a choice rather than a property:
	 *         nothing there establishes a second order, which is why the {@code otherCodes.retainAll}
	 *         below treats such a disagreement as untrusted.
	 *
	 *         <p>Restoration reaches every order EXCEPT one that is itself the subject's own, and that
	 *         bounds what this can find: when every order carrying one member of a pair also carries the
	 *         other's code, both members' orders are own-classified from both sides and the pair goes
	 *         unreported — two brand-named, ATC-mapped multi-substance orders covering the same two
	 *         substances (measured shape: two differently-named orders both mapped to
	 *         {@code C10AA01} + {@code J01FA09}). That is the safe direction — a pair missed, never one
	 *         invented — and it cannot be told from the one-order case without more order structure than
	 *         a code set carries, which is why the one-order suppression wins.
	 *
	 *         <p>A nameless order used to be a second such bound, its codes reaching the union while it
	 *         was itself absent from the list so nothing could restore them. Issue #290 removed it, and
	 *         the effect here runs in BOTH directions rather than only widening: such an order now
	 *         either classifies as the subject's own — its other codes joining {@code ownCodes} and
	 *         being removed, suppressing pairs that fired before — or as another order, restoring codes
	 *         that were stuck. It also gains the #136 reference-NAME leg below, which only the per-order
	 *         branch collects, so this is a new witness path and not merely a wider code set.
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
				// proxy removes is restored below whenever another order IN THE PER-ORDER LIST carries it.
				// Every order the builder reads that CONTRIBUTES a code is in that list since issue
				// #290, nameless ones included (one with no name and no code reaches neither), so a
				// code outside it means a hand-built context supplied one — this branch
				// only runs when the order list is non-empty, so #118's flattened-only shape is not it.
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
	 *         <p><b>Sharing an exact ATC code is not the same as being one substance.</b> Level 5 is
	 *         per-substance in the ATC standard, and this paragraph used to say so flatly and conclude
	 *         that the code leg cannot mistake another drug's order for the subject's own. That is
	 *         false of THIS knowledge base, which files two substances under one level-5 code — the
	 *         premise issue #185 turned on. The counterexample is pinned as a fixture premise rather
	 *         than restated as a number here: see
	 *         {@code DuplicateTherapySelfChipTest.theFixtureReallyFilesOmeprazoleUnderEsomeprazolesCode},
	 *         which asserts the two rows publish one code and are two substances. (Class relatedness
	 *         is still {@link DrugReference#atcSubgroups()}'s business and not this one's.)
	 *
	 *         <p>So the code leg CAN count a different substance's order as the subject's own, and is
	 *         kept anyway, because its residue runs the same safe direction as the name leg's below:
	 *         an over-wide answer withholds a partner, which misses a pair and can never invent one.
	 *         That reasoning is this predicate's alone and does not transfer.
	 *         {@link #classRelationships}'s restating-existing-therapy skip keeps its own exact-code
	 *         test beside a substance-identity one for the opposite reason — there the code is all a
	 *         context carrying no order has to go on, and dropping the leg raises a chip that arm has
	 *         never raised. That skip's leg is scoped to exactly that case since issue #228
	 *         ({@link OrderPartner#codesFromDataset}); this predicate's is not, and the two scopes are
	 *         not each other's business.
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
			List<RecordedAllergen> recordedAllergens) {
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
		// The row this response names that substance by (issue #206) — see addContraindications. The two
		// CLASS comparisons below assert something about the drug being checked and so must call it what
		// every other arm calls it; the identity chip does not and is exempt, for the reason recorded at
		// its own branch.
		//
		// So those two sentences now READ their evidence from ref (refClasses, refGroups above) and NAME
		// a different row of the same substance. "X is in the same ATC class (C) as …" is therefore true
		// of the row it names only while every row of a substance publishes the same ATC codes — the data
		// invariant ContraindicationChips' javadoc measures (0 of 129 multi-row families divergent,
		// 2026-08-08) for the ledger key and the class arm's one-row read. Since issue #206 that
		// invariant also underwrites the CHIP'S OWN CLAIM, which is a sharper consequence than a dropped
		// chip: a refresh giving one route variant a subgroup its siblings lack would make this sentence
		// name a drug that publishes no such code. Re-measure it on a refresh — the instruction lives with
		// the measurement, and this is one more thing that now depends on it.
		DrugReference subject = chips.subjectOf(ref);
		for (RecordedAllergen recorded : recordedAllergens) {
			List<DrugReference> allergen = recorded.substances();
			// Identity FIRST, over every substance the recorded name implies, and only then the class
			// comparisons over the same set: precedence belongs to the recorded allergy as a whole, so a
			// weaker relationship with one implied substance must not pre-empt a stronger one with
			// another. Each arm stops at its first match, which is what makes one recorded allergy one
			// chip however many of the implied substances the subject is related to.
			DrugReference sameSubstance = firstOfSameSubstance(allergen, refSubstance);
			if (sameSubstance != null) {
				// Named after the ALLERGEN ROW the chart resolved, not after the subject the other chips
				// name (issue #164, and exempt from issue #206 deliberately). This sentence reports the
				// patient's own allergy RECORD, and naming the row the chart records is what makes a
				// finding truthful — issue #187 settled that and #192 re-measured it, at a charted
				// `Ketorolac (ophthalmic)` allergy that the fold renames wrongly. So the two are anchored on
				// the same kind of evidence and simply on different records of it: the class chips below
				// assert something about the drug being CHECKED, this one quotes a record.
				//
				// The LEDGER is handed sameSubstance for the same reason, not the resolved subject. It is a
				// contract and NOT a mechanism, and nothing can pin it: the two key alike
				// (firstOfSameSubstance returns a row of ref's own substance, so their substanceGroupKey is
				// refSubstance either way), so no test can tell them apart. What it buys is that the exempt
				// chip does not read the resolver at all, so a later change to SubstanceSubjects cannot
				// reach a chip that must not move — which is #187, and #187 is not a thing to leave resting
				// on two expressions happening to be equal.
				//
				// WHICH NAME the sentence may use is a second question, and not the same one (issue
				// #268). The row above is a row of ref's substance, which is what makes the CHIP about
				// the right drug; it is not necessarily a row the recorded name NAMES, because
				// findImpliedSubstances reaches every substance a name could denote and admits some of
				// them on a rank TIE or through a derivation. So the sentence has two forms and
				// recordedAllergen decides between them — see DrugReferenceService.findNamedSubstances
				// for the three ways a name names a row and for what the second form gives up.
				chips.add(sameSubstance, sameSubstance.substanceGroupKey(), ContraindicationChips.IDENTITY,
						new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION,
								sameSubstance.displayLabel(), recorded.identitySentence(sameSubstance)),
						recorded.names(sameSubstance));
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
				String shared = sharedCrossReactivityClass(refClasses, implied);
				if (shared != null) {
					chips.add(subject, implied.substanceGroupKey(), ContraindicationChips.SAME_CLASS,
							new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, subject.displayLabel(),
									subject.displayLabel() + " is in the same ATC class (" + shared
											+ ") as the patient's allergy to " + recorded.allergenName(implied)
											+ " — possible cross-reactivity"),
							recorded.names(implied));
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
					chips.add(subject, implied.substanceGroupKey(), ContraindicationChips.SAME_GROUP,
							new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, subject.displayLabel(),
									subject.displayLabel() + " is in the same cross-reactivity group ("
											+ group.getName() + ") as the patient's allergy to "
											+ recorded.allergenName(implied) + " — possible cross-reactivity"),
							recorded.names(implied));
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
	 * @return the substances the patient's recorded allergies IMPLY, keyed as this arm keys them — NOT
	 *         the narrower set they NAME. Swapping in {@link DrugReferenceService#findNamedSubstances}
	 *         to "fix" the vocabulary (issue #268 makes implies-vs-names load-bearing) would narrow this
	 *         answer away from the very chip it exists to agree with, since that chip is raised off
	 *         {@link RecordedAllergen#substances()}. It is
	 *         {@link #addAllergyContraindications}'s own identity question, asked over the WHOLE allergy
	 *         list rather than of one record, in the form a caller holding a {@link DrugReference} can
	 *         test. Empty for a null context, which is
	 *         "nothing known" and not "nothing recorded"; a consumer making a negative claim has to ask
	 *         {@link PatientClinicalContext#contraindicationRecordsRead} as well.
	 *
	 *         <p>The keys are {@link DrugReference#substanceGroupKey()} — the very values
	 *         {@link #firstOfSameSubstance} compares, from the very walk
	 *         {@link #recordedAllergens} builds for that arm, so "would that arm raise its IDENTITY chip
	 *         for this substance from some record" cannot come to have two answers. A set rather than
	 *         that method because the caller has no row to scan and does not want one: it holds the
	 *         entry and asks about it.
	 *
	 *         <p>The WHOLE list is what separates this from {@link #aMatchedRecordNamesTheEntry}, and
	 *         the pair is not interchangeable in either direction (issue #269). This one is satisfied by
	 *         a record the RULE never fired on, which for a rank choosing between rival sentences is the
	 *         defect {@link #contraindicationRank} refuses and for a record stating what the chart holds
	 *         is the right answer. That one is satisfied by a name this resolution NARROWS away, since
	 *         {@link DrugReferenceService#findImpliedSubstances} admits equal claimants only at the
	 *         strongest claimant's rank — so an entry merely aliasing a recorded name is absent here
	 *         while the rank keeps its full {@code SELF_NAMED_RULE}. A consumer wanting "is this rule
	 *         corroborated at all" takes the UNION, which can hedge nothing either half admits.
	 *
	 *         <p><b>The caller's {@link DrugReference} must come from the same service.</b>
	 *         {@link DrugReference#substanceGroupKey()} is the substance name where the data publishes
	 *         one and the ROW ITSELF where it does not, and {@link DrugReference} declares no
	 *         {@code equals}, so for a curated entry publishing no substance name membership here is
	 *         object identity. That is the same condition {@link #firstOfSameSubstance} has always run
	 *         under — one {@code validate} pass, one service — stated here because this answer crosses
	 *         a bean boundary: {@code DrugReferenceInjector} passes its OWN
	 *         {@link DrugReferenceService}, which is also where the entries it renders come from, so the
	 *         two sides are the same objects. Two services would parse the dataset twice and this set
	 *         would silently contain nothing the caller can find.
	 *
	 *         <p>Static and service-taking rather than an instance method: its consumer is
	 *         {@code DrugReferenceInjector}, whose {@code drugSafetyValidator} may be absent while its
	 *         {@code drugReferenceService} is not, and an answer that went missing there would silently
	 *         report every self-named rule as uncorroborated. Resolved per call and held by the caller
	 *         for the life of one injection, never on this bean (issue #172).
	 *
	 *         <p>Since issue #308 this bean derives the same set for itself, from the
	 *         {@code recordedAllergens} walk {@code validate} already does once per pass — see the list
	 *         overload below, which is the one spelling of the derivation. That makes the same-service
	 *         condition above bind a SECOND pair: the flag is set here and the record's section is
	 *         decided in the injector, so an injector wired to a validator holding a different
	 *         {@code DrugReferenceService} would give the two channels different answers about one
	 *         chart — the divergence #308 exists to close, reappearing through the wiring. Both are
	 *         Spring singletons in production and {@code DrugReferenceTestSupport.injectorWithSafety}
	 *         wires one service into both, which is what makes the condition hold rather than anything
	 *         either class checks.
	 */
	static Set<Object> allergicSubstanceKeys(DrugReferenceService drugReferenceService,
			PatientClinicalContext context) {
		return allergicSubstanceKeys(recordedAllergens(drugReferenceService, context));
	}

	/**
	 * @return the same answer over a walk the caller has ALREADY resolved — {@code validate}'s own
	 *         per-pass {@code recordedAllergens} local (issue #308). One spelling of the derivation
	 *         rather than two, which is the whole reason this overload exists: the set and the arm
	 *         that raises the identity chip must not come to disagree about which substances this
	 *         patient is recorded allergic to, and a second loop is how they would.
	 *
	 *         <p>Not a memo and not a cache — it derives from the list it is handed and holds nothing,
	 *         so issue #172's rule is satisfied by the caller's own scoping rather than by anything
	 *         here.
	 */
	private static Set<Object> allergicSubstanceKeys(List<RecordedAllergen> recordedAllergens) {
		Set<Object> substances = new LinkedHashSet<Object>();
		for (RecordedAllergen recorded : recordedAllergens) {
			for (DrugReference implied : recorded.substances()) {
				substances.add(implied.substanceGroupKey());
			}
		}
		return substances;
	}

	/**
	 * @return whether anything CORROBORATES {@code c}'s match against this patient's chart, so that a
	 *         record derived from it may state the clause as the chart's own reading — CLAUDE.md's
	 *         fourth injected-record question, and since issue #308 the question BOTH injected
	 *         channels ask. Asked only of a rule that has already matched.
	 *
	 *         <p>The union of two questions, and neither half will do: everything about WHY is on
	 *         {@code DrugReferenceInjector.corroborated}, which is where the reasoning has lived since
	 *         issue #269 and which now delegates here. What moved is only the body, and it moved for
	 *         one reason — the injected {@code drug_reference} section and the injected
	 *         {@code safety_finding} beside it report ONE fact about ONE chart, and issue #308
	 *         measured what happens when only one of them is qualified: the model answers from the
	 *         bare one. Two copies of this predicate is how they would come apart again, silently,
	 *         since nothing errors when a hedge and an assertion sit side by side.
	 *
	 *         <p>{@code allergicSubstances} is a supplier and not a set deliberately: leg 2 is a
	 *         dataset sweep and leg 1 reads only the context and the entry, so the cost order the
	 *         injector documents is preserved here rather than at one caller. The injector hands its
	 *         own lazily memoised reading; {@code validate} hands a set it has already derived from
	 *         the walk it does once per pass.
	 *
	 *         <p><b>Both sides must hold the same {@link DrugReferenceService}</b>, and since this method
	 *         is the one both channels ask, the condition belongs here rather than only on
	 *         {@link #allergicSubstanceKeys}. Leg 2 compares {@link DrugReference#substanceGroupKey()},
	 *         which is the substance NAME where the data publishes one and the ROW ITSELF where it does
	 *         not. Where it is the ROW, membership is object identity, and a validator holding a second
	 *         service would answer differently from the injector rendering the record: the two channels
	 *         back to disagreeing about one chart, which is what this method exists to prevent. That is
	 *         the shape the BUNDLED curated seed has — it publishes no {@code substanceName} at all —
	 *         and it is the only bundled population that can reach this method, since neither the
	 *         {@code ddinter} nor the {@code atc} parser publishes a contraindication rule (ADR
	 *         Decision 44 re-measures all three). An operator's own {@code json} entry MAY set one,
	 *         which Jackson binds straight onto {@link DrugReference} and which
	 *         {@code DrugReferenceValidity.RULES_WITHOUT_A_SUBSTANCE_IDENTITY} steers a deployment
	 *         toward; the key is then a string and two services would agree, so what this condition is
	 *         really about is the identity case rather than every dataset. Not constructible today —
	 *         both beans are Spring singletons in one context and
	 *         {@code DrugReferenceTestSupport.injectorWithSafety} wires one service into both — and that
	 *         is what makes the condition hold, rather than anything either class checks.
	 *
	 *         <p><b>This answers about one RULE. A collapsed KEY is a fold over its rules</b>, and that
	 *         resolution belongs to {@link #addContraindications}, not to this method: two self-named
	 *         rules of one entry are ONE chip and one rendered clause (issue #146), so one corroborated
	 *         rule carries the key. Asking this per rule and reading the answer off whichever rule won
	 *         the ledger's RANK is not the same thing, and the difference is reachable — see issue
	 *         #308 and ADR Decision 44, which record three ways of getting the unit wrong.
	 *
	 *         <p>Scoped to a SELF-NAMED allergy rule, which is load-bearing rather than incidental —
	 *         a rule whose token is not one of its entry's names is asking about a class or about a
	 *         fragment of free text, which is what the bare match exists for, and neither corroborating
	 *         question can speak to it. Mutate the scope out and read the failures.
	 */
	static boolean corroboratedByTheChart(DrugReference ref, DrugReference.Contraindication c,
			PatientClinicalContext context, Supplier<Set<Object>> allergicSubstances) {
		if (!selfNamedAllergyRule(ref, c)) {
			return true;
		}
		return aMatchedRecordNamesTheEntry(ref, c, context)
				|| allergicSubstances.get().contains(ref.substanceGroupKey());
	}

	/**
	 * @return one {@link RecordedAllergen} per distinct resolution, in the order the context lists the
	 *         tokens — the input to {@link #addAllergyContraindications}, resolved once per
	 *         {@code validate} because it does not depend on the subject being checked, and once per
	 *         injection by {@link #allergicSubstanceKeys} for the injected record's own reading (issue
	 *         #269) — and, since issue #308, read a second time within the pass, to derive the key set
	 *         the injected finding's own clause turns on. Two invocations of ONE walk rather than two walks: the answer is a function of the
	 *         service and the context alone, so the two cannot disagree, and neither holds it past the
	 *         pass or the injection that asked for it. Each carries
	 *         its charted allergen token and the substances it implies, plus which of those it NAMES
	 *         (issue #268): the arm reasons over all of them, and only a named one may be reported as
	 *         the allergy itself.
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
	private static List<RecordedAllergen> recordedAllergens(DrugReferenceService drugReferenceService,
			PatientClinicalContext context) {
		if (context == null) {
			return Collections.emptyList();
		}
		List<RecordedAllergen> out = new ArrayList<RecordedAllergen>();
		for (String allergyToken : context.getAllergyTokens()) {
			List<DrugReference> implied = drugReferenceService.findImpliedSubstances(allergyToken);
			if (implied.isEmpty()) {
				continue;
			}
			List<DrugReference> named = drugReferenceService.findNamedSubstances(allergyToken, implied);
			RecordedAllergen seen = resolvedAlike(out, implied);
			if (seen == null) {
				out.add(new RecordedAllergen(allergyToken, implied, named));
			}
			else {
				seen.alsoNames(named);
			}
		}
		return out;
	}

	/** @return the entry of {@code out} that already resolved to exactly {@code implied}, else null —
	 *          the de-duplication above, unchanged in what it compares: the whole resolved LIST, never
	 *          the recorded token beside it, because two spellings of one allergy are one record and
	 *          the rule that says so is stated on {@link #recordedAllergens}. It returns the entry
	 *          rather than a boolean because the later spelling still carries evidence — see
	 *          {@link RecordedAllergen#alsoNames}. */
	private static RecordedAllergen resolvedAlike(List<RecordedAllergen> out,
			List<DrugReference> implied) {
		for (RecordedAllergen seen : out) {
			if (seen.substances().equals(implied)) {
				return seen;
			}
		}
		return null;
	}

	/**
	 * One of the patient's recorded allergies: its charted allergen token, the substances it implies, and
	 * the ones it NAMES (issue #268). Three facts about one record, held together because the identity
	 * chip needs all three at once — it reports THAT record, so it may print a row's label only where
	 * the record names it and must otherwise quote the chart.
	 *
	 * <p>Private to this class and built by whoever resolves the allergy list — once per
	 * {@code validate} pass, and once per injection for {@link #allergicSubstanceKeys} (issue #269) — so
	 * nothing here outlives the pass or the injection that built it (issue #172: this bean is a Spring
	 * singleton and this memo is keyed on nothing at all).
	 */
	private static final class RecordedAllergen {

		/** The allergen as {@link PatientClinicalContext} holds it — trimmed and lower-cased by
		 *  {@link DrugReference#normalizeName} at construction, so this is the chart's word rather than
		 *  the chart's STRING, and a sentence carrying it reads {@code … a recorded allergy to gallium.}
		 *  for a chart that wrote {@code Gallium}. No un-normalized carrier exists to use instead. */
		private final String token;

		private final List<DrugReference> substances;

		private final List<DrugReference> named;

		private RecordedAllergen(String token, List<DrugReference> substances,
				List<DrugReference> named) {
			this.token = token;
			this.substances = substances;
			this.named = new ArrayList<DrugReference>(named);
		}

		/**
		 * Folds in what ANOTHER spelling of this same allergy names. Two records resolving to the same
		 * substances are one clinical fact and are de-duplicated, but they are not equally good
		 * EVIDENCE: a chart carrying both {@code latanoprostene bunod} and
		 * {@code latanoprostene bunod 5mg} names the drug through the first and not the second, and
		 * which one {@code PatientService.getAllergies} returns first is not something a clinician
		 * should be able to see in the wording. So naming survives the merge, and the sentence no
		 * longer depends on that order.
		 *
		 * <p>Safe to union rather than choose, because a NAMED row's sentence quotes no token at all —
		 * it states the row's own label, which the other record supports just as well.
		 *
		 * <p>What it does NOT make order-independent: where NEITHER merged spelling names the row, the
		 * surviving sentence quotes the first spelling's token, because the merge keeps one token and
		 * nothing grounds a preference between two chart spellings. Both sentences are true and both
		 * quote the chart; which spelling is quoted still follows row order.
		 */
		private void alsoNames(List<DrugReference> alsoNamed) {
			for (DrugReference row : alsoNamed) {
				if (!names(row)) {
					named.add(row);
				}
			}
		}

		/** The substances this recorded name implies — what the class comparisons reason over, and the
		 *  de-duplication key. Unchanged by issue #268: no substance is withheld from any arm. */
		private List<DrugReference> substances() {
			return substances;
		}

		/**
		 * The identity chip's whole sentence, in one of two forms, because the two state different
		 * things and only one of them is available for a given row.
		 *
		 * <p>Where this recorded name NAMES the row, the chart records an allergy to that very drug and
		 * the sentence says so — unchanged since issue #164, and what issues #187/#192 settled must keep
		 * naming the row the chart records.
		 *
		 * <p>Where it does not, that claim would be false, so the sentence states the relationship the
		 * module actually established: there is a recorded allergy to THIS name, and it contraindicates
		 * THAT drug. It takes the curated rule arm's own shape ("X is contraindicated by an active
		 * allergy: …") deliberately, because the wire contract requires it — {@code README} and
		 * {@link SafetyWarning#getDetail()} say a detail is standalone prose naming its own drug,
		 * which clients render alone and key per-finding identity on. A sentence that named only the
		 * allergen would satisfy neither: the subject would appear nowhere on screen, and two chips
		 * about different drugs raised by one allergy record would carry byte-identical details (issue
		 * #238's collapse, from the other side).
		 *
		 * <p>Membership is tested by REFERENCE, which is what
		 * {@link DrugReferenceService#findNamedSubstances} returning a sublist of the very rows it was
		 * handed makes available: {@link DrugReference} defines no {@code equals}, so a containment test
		 * would mean the same thing today and something else the day one is added — and this decides
		 * whether a sentence about a patient is true.
		 * {@link DrugSafetyValidator#resolvedAlike} does lean on {@code List.equals}, unchanged from
		 * the {@code contains} it replaced; that one compares whole resolved lists for the
		 * de-duplication rule and is not deciding a claim.
		 */
		private String identitySentence(DrugReference row) {
			return names(row) ? "The patient has a recorded allergy to " + row.displayLabel() + "."
					: row.displayLabel() + " is contraindicated by a recorded allergy to " + quotedToken()
							+ ".";
		}

		/**
		 * @return what the two CLASS sentences may call {@code row}, the substance they report a
		 *         relationship WITH — its own label where this recorded name names it, and otherwise the
		 *         charted token. Those sentences say "as the patient's allergy to Y", which asserts the
		 *         allergy as flatly as the identity chip does, so the same rule binds them: measured over
		 *         the shipped KB, an allergen charted as {@code amoxicillin / esomeprazole / levofloxacin
		 *         combination kit} put "the patient's allergy to Omeprazole" beside the identity chip
		 *         that had just declined to say it, in one payload. Unlike the identity chip the sentence
		 *         needs no second form — it already names its own subject and states the relationship,
		 *         so only the allergen half moves.
		 */
		private String allergenName(DrugReference row) {
			return names(row) ? row.displayLabel() : quotedToken();
		}

		/**
		 * @return the charted token in quotation marks — because it is a quotation, and because a
		 *         non-coded allergen is clinician free text that can carry the module's own punctuation.
		 *         The class sentences end in {@code " — possible cross-reactivity"}, so an allergen
		 *         charted as {@code esomeprazole magnesium — hives} produced a sentence with two
		 *         em-dashed clauses and no way to tell which was the chart's; that text is injected as a
		 *         citable {@code safety_finding}, so a model reads it too.
		 */
		private String quotedToken() {
			return "\"" + token + "\"";
		}

		/** @return whether this recorded name NAMES {@code row} — by reference, for the reason
		 *          {@link #identitySentence} gives, and asked in one place because all three of the
		 *          arm's sentences turn on it. */
		private boolean names(DrugReference row) {
			for (DrugReference candidate : named) {
				if (candidate == row) {
					return true;
				}
			}
			return false;
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
	 * Measured on the bundled curated dataset ({@code sourceFormat=json}, the production default when this
	 * was measured): an
	 * active ibuprofen order plus an ibuprofen allergy, a question naming no drug and an answer citing
	 * the real order record raised <b>0 chips</b>, where the identical call with {@code mappings=null}
	 * raised <b>2</b> — which is <b>1</b> since issue #146, those two having been the curated rule and
	 * the identity check reporting one allergy twice rather than two findings. An allergy to a
	 * currently-prescribed drug is a prescribing error the chart
	 * already contains, and it reached the clinician as neither a chip nor anything in the prompt: the
	 * pre-answer findings issue #110 injects come from this same {@code validate}, so an arm that
	 * raises nothing puts nothing in front of the model either.
	 *
	 * <p>That implication is the one this paragraph needs, and it still holds. Its CONVERSE does not,
	 * since {@link SubjectMatter}: a chip this arm raises because the ANSWER or a cited record named
	 * the drug has no pre-answer record behind it, the prompt pass having run on the question alone.
	 * Other comments in this class still put the converse as a general property of issue #110 — they
	 * are about drug-in-play chips, where a drug only the ANSWER names has never had a pre-answer
	 * record either, so the looseness is older than this arm and correcting it is its own pass.
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
	 * <p><b>Bounded by the response's subject matter, not by the question's wording.</b> This paragraph
	 * used to read "not gated on the question, deliberately", and the reasoning it gave — that "is the
	 * patient allergic to something they are taking?" is a fact about the chart rather than about the
	 * wording of a query — is still true and is still not a licence to answer it unasked. Unbounded,
	 * the arm put the identical chips on every response: measured live on the 3.7.1 standalone, four
	 * questions about allergies, interactions, cancer and a date of birth returned the same two,
	 * byte for byte. This module annotates answers and has none of an alerting system's machinery, so
	 * the arm is bounded by {@link SubjectMatter} — a chip is raised where either SIDE of it, the drug
	 * or the recorded finding, is part of what the response is about. That is deliberately NOT a gate
	 * on the question's wording, which is the thing this defect taught: a question naming a drug
	 * carries no medication cue word at all, and the answer and the cited records count as much as the
	 * question. What bounds the arm besides is the chart: it can only fire where an allergy or
	 * condition record and an active order point at the same drug, and the two arms it delegates to
	 * bound it further — one chip per
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
			List<RecordedAllergen> recordedAllergens, SubjectMatter askedAbout,
			Supplier<Set<Object>> allergicSubstances) {
		if (!hasContraindicationRecords(context)) {
			return;
		}
		// The recorded allergies THIS response is about, resolved once per pass rather than once per
		// order: it is a function of the response and of the patient's allergy list, neither of which
		// varies inside the loop. A per-call local and never a field, for issue #172's reason — this
		// bean is a Spring singleton, so a field here is one unsynchronized structure shared by every
		// concurrent request, and this one is keyed on nothing at all.
		List<RecordedAllergen> allergensAskedAbout = null;
		for (DrugReference ref : orderEntries) {
			if (inPlay.contains(ref)) {
				continue;
			}
			// Either side of a contraindication can be what was asked about, so the drug side is tried
			// first and, where it holds, the whole of the patient's own record is fair game: a response
			// ABOUT one of her prescriptions may report anything her chart says contraindicates it.
			// Where it does not hold, only the findings the response is itself about may speak — which
			// is what stops a cancer question carrying chips about her local anaesthetics.
			if (askedAbout.names(ref)) {
				addContraindications(chips, ref, context, null, allergicSubstances);
				addAllergyContraindications(chips, ref, recordedAllergens);
				continue;
			}
			if (allergensAskedAbout == null) {
				allergensAskedAbout = recordedAllergensAskedAbout(recordedAllergens, askedAbout);
			}
			// The WHOLE-list set, never allergensAskedAbout beside it. Leg 2 of the union is the allergen
			// arm's own identity question "asked over the WHOLE allergy list" (ADR Decision 42), so
			// narrowing it to what the response is about would report a finding as uncorroborated on the
			// strength of the question's wording — hedging a clause a recorded allergy really does
			// support. The narrowing below is about which allergy records may SPEAK in this response;
			// this is about what the chart holds, and the two are different questions.
			addContraindications(chips, ref, context, askedAbout, allergicSubstances);
			addAllergyContraindications(chips, ref, allergensAskedAbout);
		}
	}

	/**
	 * @return whether {@code c} is the ALLERGY leg of the curated rule vocabulary. One spelling of that
	 *         test, because FOUR consumers now ask it for different reasons — {@link
	 *         #recordedContraindicationKind} to decide which chart list a rule is put to, {@link
	 *         SubjectMatter} to decide whether an allergy-domain question puts the rule in scope,
	 *         {@link #evaluatesAgainstTheChart} to decide whether the module could ask the chart at all,
	 *         and {@link #selfNamedAllergyRule} for issue #146's fold. Left as literals they would drift
	 *         silently and in the worse direction: a vocabulary that grew a synonym would keep matching
	 *         rules through one reader while another quietly stopped applying to them, and the worst of
	 *         those pairings is a rule {@code recordedContraindicationKind} can evaluate that
	 *         {@code evaluatesAgainstTheChart} cannot — the injected record then reports the chart as NOT
	 *         recording something nobody looked for, which is issue #208 item 2 with the sign flipped.
	 */
	private static boolean isAllergyRule(DrugReference.Contraindication c) {
		return "allergy".equalsIgnoreCase(c.getType());
	}

	/**
	 * @return whether {@code c} is the CONDITION leg of that same vocabulary. Extracted alongside
	 *         {@link #isAllergyRule} and for the reason listed there, which is not symmetry: the readers
	 *         of a rule's TYPE are enumerated once, on that method, rather than half here and half
	 *         there. Keeping one of the pair literal and the other named is how the pair comes apart.
	 */
	private static boolean isConditionRule(DrugReference.Contraindication c) {
		return "condition".equalsIgnoreCase(c.getType());
	}

	/**
	 * @return whether the chart records anything an active order could be contraindicated BY. Extracted
	 *         from {@link #addActiveOrderContraindications}'s own guard rather than copied to its call
	 *         site: the call site has to know the same answer to decide whether parsing the cited-record
	 *         corpus can pay for itself, and two spellings of "this patient records nothing to check
	 *         against" would drift into a corpus built for an arm that returns, or an arm that runs
	 *         without one. Both token sets, for the reason the guard's own javadoc gives — the curated
	 *         arm's condition leg is half of what the original scoping suppressed.
	 */
	private static boolean hasContraindicationRecords(PatientClinicalContext context) {
		return context != null
				&& !(context.getAllergyTokens().isEmpty() && context.getConditionTokens().isEmpty());
	}

	/**
	 * @return the recorded allergies {@code askedAbout} covers, in order, each still carrying every
	 *         entry it resolved to. Filtered per recorded ALLERGY and not per resolved entry: one
	 *         charted allergy is one finding however many rows or constituents it names (issues
	 *         #145/#193/#195), so admitting the allergy on any of its entries keeps the identity and
	 *         cross-reactivity arms reasoning over the same finding they always did.
	 */
	private static List<RecordedAllergen> recordedAllergensAskedAbout(
			List<RecordedAllergen> recordedAllergens, SubjectMatter askedAbout) {
		List<RecordedAllergen> out = new ArrayList<RecordedAllergen>();
		for (RecordedAllergen allergen : recordedAllergens) {
			if (askedAbout.namesRecordedAllergen(allergen.substances())) {
				out.add(allergen);
			}
		}
		return out;
	}

	/**
	 * Class-based interaction reasoning: warns when the drug {@code ref} being checked shares
	 * an ATC level-4 subgroup with one of the patient's active orders (additive effects / duplicate
	 * therapy) or — failing that — a curated {@link CrossReactivityGroup} (a cross-branch family
	 * overlap, e.g. ibuprofen recommended over an active aspirin order). A co-medication that IS
	 * {@code ref}'s own substance is skipped — restating existing therapy is not a duplicate — and
	 * only that co-medication: a class sibling the patient also happens to be on is exactly the
	 * finding this arm exists to make, so the question is asked per PARTNER. Where the dataset cannot
	 * name a co-medication and the ORDER names it, what the order names is what it IS, so a
	 * fixed-dose combination counts as each of its constituents. The comparison is on ATC codes
	 * throughout — an order's own where a dictionary published them and the dataset's for the substance
	 * an unmapped order NAMES (issue #228) — and the order is named by the ladder
	 * {@link #orderPartners} documents: the dataset's name for the substance, else the order's own
	 * display name, else the code (issue
	 * #155). The most specific match wins, so a subgroup + group double-match warns once — except
	 * where the shared subgroup is one {@link DrugReference#isUnclassifyingAtcCode} vetoes, which is
	 * not a match at all and lets the group answer (issue #167).
	 *
	 * <p><b>What "is the same drug" means, and why it takes two legs (issue #185).</b> Neither
	 * subsumes the other.
	 * <ul>
	 *   <li>{@link DrugReference#substanceGroupKey()} against the substances the partner's ORDER is
	 *       recorded as naming (see {@link OrderPartner#substances}). This is the question the skip is
	 *       actually asking, and it
	 *       is the one the code comparison cannot answer, because the code a co-medication carries and
	 *       the code the reference row publishes can be different codes of ONE substance. The shipped
	 *       KB's {@code Omeprazole} row publishes only {@code A02BC05} — esomeprazole's, alongside
	 *       {@code rxnorm_name} "esomeprazole" — while an omeprazole order on a dictionary that maps
	 *       the concept correctly carries {@code A02BC01}, which appears nowhere in that KB. Disjoint
	 *       code sets, one shared subgroup, and the chip read {@code Omeprazole is in the same ATC
	 *       class (A02BC) as active order Omeprazole 20mg}. Deciding identity by the substance key
	 *       rather than by something a row happens to publish is the correction issues #164/#187 made
	 *       for the two cross-reactivity routes to that same symptom; this is the third route.</li>
	 *   <li>A shared exact ATC code, which identity does not replace <b>where the partner's codes are
	 *       the CHART's</b> — see {@link OrderPartner#codesFromDataset} for the scoping issue #228 had
	 *       to add, and why the leg is wrong on a partner reached by name. Where the context carries only
	 *       the flattened code set (issue #118's fallback) no order names the partner, so the partner
	 *       is whichever row {@link #entryForAtcCode} picks for the code — and that row's substance
	 *       need not be the queried one. An order known only as {@code A02BC05} resolves to
	 *       {@code Omeprazole}, the earlier row, so dropping this leg would newly raise a chip about
	 *       {@code Esomeprazole} against it, which this arm has never raised.
	 *       <p>Not because a level-5 code cannot be shared by two substances — in THIS knowledge base
	 *       it can, and the pair just named is the counterexample: {@code Omeprazole} and
	 *       {@code Esomeprazole} publish one {@code A02BC05} and differ in {@code drugbank_id}. (The
	 *       unqualified "sharing an exact ATC code means being the same substance" on
	 *       {@link #resolvesFrom} is a claim about ATC the standard, and is false of this KB.) So the
	 *       leg CAN over-skip, and does exactly there — deliberately, because what it skips is a
	 *       partner the dataset can only name as {@code Omeprazole} anyway. Keeping it is a choice
	 *       between two imperfect answers, made the way it already was before this issue.</li>
	 * </ul>
	 *
	 * <p>Returns the relationships rather than adding the chips, because whether a relationship gets a chip
	 * of its own is no longer decidable from this arm alone: a pair the rule arm also raises folds into
	 * that rule's chip instead (issue #88, see {@link #addInteractionWarnings}). The reasoning itself —
	 * which codes are skipped, which family wins, how the sentence reads — is unchanged.
	 *
	 * <p>Its PARTS rather than a finished sentence, since issue #292 — but for the same reason the
	 * finished sentence was chosen: it must read identically on both surfaces, as the whole detail of a
	 * class-only chip and as the second sentence of a folded one, and {@link ClassRelationship#sentence}
	 * is now the one thing that words it (the template used to be written out twice here, once per
	 * family). What the parts buy is that the partner's NAME becomes an argument, which a folded chip
	 * needs because only the fold knows both arms' names for one order. Issue
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
	 * shared set — the same decision the allergy arm makes, so neither depends on the order a
	 * dictionary published its mappings in. That decision is one method and not two, which is what
	 * issue #171 asked for; since issues #183/#184 it also takes the ARM as a parameter, so the two
	 * arms share the preference and NOT the candidate set, and may name different subgroups for one
	 * pair where each claim is honestly about a different one. See {@link #sharedClass}, which
	 * measures how often.
	 *
	 * @return the class relationship for each active-order partner that has one — its parts, worded by
	 *         {@link ClassRelationship#sentence} once the partner's name is decided — keyed by that
	 *         partner, in partner FIRST-APPEARANCE order over the context's codes and then over the
	 *         orders none of those codes reached (issue #228); empty when the drug is in no class or
	 *         group at all.
	 *         <p>Not the same as the per-code order this returned before issue #171, and the difference
	 *         is observable: a partner whose first code shares nothing now sorts by that first code
	 *         rather than by the code that produced its sentence, so it can precede a partner that the
	 *         per-code walk put ahead of it. Chip CONTENT is unchanged; two class-only chips can swap
	 *         places. Stated rather than smoothed over, because "chip order is unchanged" was the
	 *         previous promise here and is no longer one that can be kept.
	 */
	private Map<OrderPartner, ClassRelationship> classRelationships(DrugReference ref,
			CoMedications coMedications) {
		Map<OrderPartner, ClassRelationship> out = new LinkedHashMap<OrderPartner, ClassRelationship>();
		Set<String> refClasses = ref.atcSubgroups();
		List<CrossReactivityGroup> refGroups = CrossReactivityGroup.groupsOf(ref,
				drugReferenceService.getCrossReactivityGroups());
		if (refClasses.isEmpty() && refGroups.isEmpty()) {
			return out;
		}
		Set<String> refCodes = ref.normalizedAtcCodes();
		Object refSubstance = ref.substanceGroupKey();
		for (OrderPartner partner : coMedications.resolved()) {
			if (partner.substances.contains(refSubstance)
					|| (!partner.codesFromDataset
							&& !Collections.disjoint(partner.codes, refCodes))) {
				// Restating existing therapy is not a duplicate. Identity first because it is the
				// question; the exact-code leg second because it answers where the dataset cannot name
				// the partner — and only where the partner's codes are the CHART's, which is what the
				// guard says. See this method's javadoc for why both are needed and why the code leg's
				// one over-skip is deliberate.
				continue;
			}
			String shared = sharedTherapyClass(refClasses, DrugReference.atcSubgroups(partner.codes));
			if (shared != null) {
				out.put(partner, new ClassRelationship("ATC class (" + shared + ")",
						"possible duplicate therapy"));
				continue;
			}
			CrossReactivityGroup group = CrossReactivityGroup.sharedGroupForCodes(refGroups, partner.codes);
			if (group != null) {
				out.put(partner, new ClassRelationship("cross-reactivity group (" + group.getName() + ")",
						"possible additive or duplicate-class therapy"));
			}
		}
		return out;
	}

	/**
	 * One class relationship the arm above found, as its two authored parts rather than as a finished
	 * sentence — what the drugs share, and what sharing it may mean.
	 *
	 * <p><b>Still one wording, which is what issue #108 requires of this sentence.</b> It reads
	 * identically whether it is the whole detail of a class-only chip or the second sentence of a folded
	 * one, because {@link #sentence} is the only thing that words it; before issue #292 that was
	 * achieved by returning the finished string, and the template was written out twice here, once per
	 * family. The two shortenings issue #108 rejected are untouched: nothing refers back to "that order"
	 * (which dropped the synonym-augmented order label) and nothing prefixes "also" (which broke the
	 * "X is in the same …" wording).
	 *
	 * <p>What the parts buy is that the partner's NAME becomes an argument. A folded chip has to name
	 * one order once (issue #292) and only the fold knows both arms' names for it, so the name cannot be
	 * baked in here — see {@link #reconciledPartnerName}.
	 */
	private static final class ClassRelationship {

		/** What the two drugs share, worded and parenthesised as the chip says it — {@code "ATC class
		 *  (A04AA)"}, {@code "cross-reactivity group (NSAID)"}. */
		private final String shared;

		/** What sharing it may mean — the clause after the dash. */
		private final String consequence;

		ClassRelationship(String shared, String consequence) {
			this.shared = shared;
			this.consequence = consequence;
		}

		/** @param partnerName what this chip calls the active order — {@link #classPartnerName} for a
		 *        class-only chip AND for a folded chip whose arms {@link #reconciledPartnerName}
		 *        refused to reconcile, else that method's answer. Both of those are the class arm's own
		 *        ladder ({@link OrderPartner#label}), with one thing {@link #classPartnerName} adds
		 *        (issue #339): on the ENTRY rung the ROW that label displays is the one this response
		 *        names the substance by, so the two arms cannot elect different rows of one
		 *        prescription. On the ORDER rung the prescription's own display stands, which is what
		 *        this sentence needs — it is the only name true of every code the partner holds. */
		String sentence(DrugReference subject, String partnerName) {
			return subject.displayLabel() + " is in the same " + shared + " as active order "
					+ partnerName + " — " + consequence;
		}
	}

	/**
	 * A class sentence that has been folded onto a rule chip, together with the ONE name both of that
	 * chip's sentences call the active order by ({@link #reconciledPartnerName}).
	 *
	 * <p>The name travels with the sentence rather than being recomputed for the rule half, because the
	 * two are one decision: recomputing is how the two sentences came to disagree in the first place
	 * (issue #292), and the chip is assembled in a different loop from the one that folds. Since issue
	 * #297 the RECORD's name for that same partner travels with it for the same reason, one step
	 * further: it is put on the {@link SafetyWarning} this chip becomes, and
	 * {@code DrugReferenceInjector} reads it off the findings list it already holds rather than walking
	 * the fold a second time.
	 */
	private static final class FoldedClassSentence {

		private final String partnerName;

		/** The RECORD's own name for that partner — {@link SafetyWarning#reconciledPartnerNoteName} —
		 *  or null where the fold refused, when the note keeps {@link #partnerLabel} as it always has. */
		private final String partnerNoteName;

		private final String sentence;

		FoldedClassSentence(String partnerName, String partnerNoteName, String sentence) {
			this.partnerName = partnerName;
			this.partnerNoteName = partnerNoteName;
			this.sentence = sentence;
		}
	}

	/**
	 * The ONE name a chip and its injected note call an active-order partner by, in each of the two
	 * vocabularies that name has to be spoken in (issue #297) — {@link #reconciledPartnerName}'s
	 * answer. For a FOLDED chip that is also the name its two sentences agree on, which is what this
	 * type was built for; since issue #339 it is returned for a chip with one sentence too.
	 *
	 * <p>Two strings and one decision, deliberately, because the surfaces cannot share a single string:
	 * the chip's name may be {@link DrugReference#displayLabel()} and that label may not enter the
	 * injected {@code drug_reference} record's prose
	 * ({@code DrugSafetyChipLabelTest.displayLabelNeverLeaksIntoTheRenderedRecordText}). So they name
	 * one SUBSTANCE in each one's own vocabulary. Rendering them from one gate rather than asking the
	 * gate twice is the whole point: a second copy of the conditions is how the chip's two sentences
	 * came apart in issue #292, one level down.
	 */
	private static final class ReconciledPartner {

		/** What the CHIP calls the order — both of its sentences, where it has two. */
		private final String chipName;

		/** What the injected {@code drug_reference} note calls it. */
		private final String noteName;

		ReconciledPartner(String chipName, String noteName) {
			this.chipName = chipName;
			this.noteName = noteName;
		}
	}

	/**
	 * One co-medication the class arm reasons about: the ATC codes that classify it, the substances it
	 * is known to contain, and the name a chip calls it by.
	 *
	 * <p>The codes are the patient's ACTIVE ORDERS' own wherever a dictionary published any, and the
	 * reference row's for an order it classified not at all (issue #228) — see
	 * {@link DrugSafetyValidator#addPartnersForUnmappedOrders}, which records why the two sources are
	 * not interchangeable and why the asymmetry between them is left standing.
	 *
	 * <p>Identity, not text: two rows of one substance, or one order's several codes, are one partner
	 * however the chip words them — the rule both the contraindication ledger and
	 * {@link InteractionPairs} already follow.
	 */
	private static final class OrderPartner {

		private String label;

		/**
		 * The ACTIVE ORDER this partner's {@link #label} was taken from — null wherever the label is not an
		 * order's name, which since issue #298 means the entry rung, issue #118's bare-code rung AND the
		 * order rung where the order's own display is not a name. "Where an order was consulted" is what
		 * this used to say and is not the rule: an order can be consulted and still supply no name. See
		 * {@link #recordNameSource}, which is what makes that true, and {@link #nameByOrder}.
		 *
		 * <p>The order itself and not a boolean saying that one supplied the name, because
		 * {@link DrugSafetyValidator#reconciledPartnerName} asks a question OF that order — whether the rule
		 * about to be folded onto this partner names it — so carrying the order makes "named by an order"
		 * and "which order" one fact, and the fold cannot be handed a name with nothing to validate it
		 * against.
		 *
		 * <p><b>A non-null value means {@link #label} is that order's name</b> (issue #298), and that is a
		 * property of this class rather than of any one caller. Two things make it so, and it takes both:
		 * {@link #recordNameSource} is the only thing that writes this field or {@link #namesADrug}, and it
		 * admits an order only where that flag is true; and no constructor takes the label and the flag as
		 * separate arguments any more, so neither can be supplied disagreeing with the other.
		 *
		 * <p>Until issue #298 that did not hold. The order rung set this whenever an order was consulted
		 * while {@link #namesADrug} came from {@link DrugSafetyValidator#displayNamesADrug}, so a
		 * {@code namedByCodesOnly} or blank-display order left a non-null value here beside an
		 * {@code [ATC …]} or bare-code label, and the only thing keeping the fold safe was the ORDER OF
		 * {@code reconciledPartnerName}'s BRANCHES — {@code !namesADrug} tested first.
		 *
		 * <p><b>That branch order is still what {@code reconciledPartnerName} uses, and is deliberately kept.</b>
		 * Reversing it — so that this field is read first — was implemented and reverted in review of issue
		 * #298, on the measurement that it would make the invariant above the ONLY thing between an
		 * inconsistent pair and a bare ATC code reaching BOTH sentences of a folded chip (and, through
		 * {@code DrugReferenceInjector.renderFinding}, the prompt as citable {@code safety_finding} text).
		 * <b>Issue #293 retired that measurement and the branch order stays anyway</b>, which is worth
		 * separating rather than quietly leaving the old sentence to rot: since
		 * {@link DrugSafetyValidator#namesNamingOrder} reads the naming order's DISPLAY, a bare code or an
		 * {@code [ATC …]} stand-in fails {@code matchesOrderName} and the fold refuses, so the reversed
		 * state now costs a code in the CLASS sentence alone — where the ladder's label already was —
		 * rather than in both. Re-measured under the reversal: the pre-#298 write path plus reversed
		 * branches prints the code once, and printing it twice needs the pre-#293 predicate as well. What
		 * survives is the ordinary defence-in-depth reason — the guards are independent, so a future rung
		 * that reaches this field cannot spend one of them by accident — and the residual cost above. So:
		 * do not read a non-null value here as licence to hand out {@link #label} without asking
		 * {@link #namesADrug}, and do not reverse the branches to make this field load-bearing. ADR Decision 40 records the reasoning; what pins the single write
		 * path is {@code OrderPartnerNameSourceWritePathTest}, structurally, because a behaviour-neutral
		 * rule has nothing a behavioural assertion can see.
		 *
		 * <p>What it still does NOT mean is that this order is the ONLY prescription behind the partner:
		 * several can reach one ({@link DrugSafetyValidator#ordersCarrying}), and this is the one whose
		 * display became the label. A rung wanting "which orders contributed this partner, named or not"
		 * wants a different field, not a widening of this one — {@link DrugSafetyValidator#namesNamingOrder}
		 * would then prove a fact about one prescription and print another's name.
		 */
		private PatientClinicalContext.ActiveDrugOrder namingOrder;

		private final Set<String> codes = new LinkedHashSet<String>();

		/**
		 * Whether {@link #codes} came from the loaded DATASET rather than from the patient's own
		 * orders — true only for a partner reached by an order's NAME (issue #228,
		 * {@link DrugSafetyValidator#addPartnersForUnmappedOrders}), where the order published no
		 * code and the reference row's are the classification available — narrowed since issue #234 to
		 * the presentation the order records, as the last paragraph here says.
		 *
		 * <p>Read by ONE thing: {@link DrugSafetyValidator#classRelationships}'s
		 * restating-existing-therapy skip, to scope its exact-code leg out. That leg is a PROXY for
		 * identity, kept for the case identity cannot reach — a context carrying only codes, where
		 * nothing names the co-medication (issue #185). Here identity is known exactly and the codes
		 * on both sides are reference rows', so the proxy would be asking whether two KB rows share a
		 * code — which this knowledge base says is not identity, and is the reason
		 * {@link DrugReference#substanceKey()} exists: {@code Omeprazole} and {@code Esomeprazole}
		 * publish one {@code A02BC05} between them and are two substances. Unscoped it silences
		 * exactly the chip issue #228 exists to add, measured on the shipped rows for that pair.
		 *
		 * <p>Not read by the CLASS comparison, which wants these codes whatever their provenance —
		 * they are what the reference data says the substance is, and without them the arm cannot
		 * reach an unmapped order at all.
		 *
		 * <p>Since issue #234 "the DATASET's" is still the right word for the provenance but no longer
		 * means the substance's whole list: {@link DrugSafetyValidator#addPartnersForUnmappedOrders}
		 * narrows them to the presentation the order's own recorded route or dose form names. That
		 * changes nothing here — the flag is about where the codes came FROM, and a narrowed reference
		 * row's codes are still reference rows' on both sides of the exact-code leg, which is the whole
		 * reason that leg is scoped out.
		 */
		private boolean codesFromDataset;

		/**
		 * The substances this co-medication is known to contain, as
		 * {@link DrugReference#substanceGroupKey()} values: what the ORDER's own recorded names imply
		 * ({@link DrugSafetyValidator#substancesNamedBy}). Read only by
		 * {@link DrugSafetyValidator#classRelationships}'s restating-existing-therapy skip; nothing
		 * here is rendered, so a partner may be named one thing and known to contain several.
		 *
		 * <p><b>Added on the same BRANCH as {@link #nameByOrder}</b> — but since issue #290 not under
		 * the same condition: the naming leg is withheld for a synthesized display and this one is not,
		 * so for an order the module could not name the substances leg runs where the naming does not.
		 * A partner the DATASET named stands for one substance; a partner named after an order stands
		 * for that order, so what the order names is what it holds. That rationale does not reach the
		 * one case #290 added — a partly-covered nameless order keeps the dataset's name and still
		 * collects substances — and it is safe there only because such an order has no names to
		 * resolve, so the leg contributes nothing. Attaching the order's whole
		 * set to every partner it produced instead silences a real finding: one order whose two codes
		 * both resolve is TWO partners, and a question about the first constituent would then skip the
		 * second — {@code Metronidazole and secnidazole} losing "Metronidazole is in the same ATC
		 * class (P01AB) as active order Secnidazole".
		 *
		 * <p><b>And its own key on the rung issue #228 added</b>, which is the same rule read the other
		 * way. A partner reached from an order's NAME rather than from a code is a partner the dataset
		 * named, so it stands for exactly one substance and holds exactly that one — never the rest of
		 * the tablet, or a question about one constituent of an unmapped combination would lose the chip
		 * naming the other, which is the loss the paragraph above describes.
		 *
		 * <p>On that rung it is the WHOLE of the skip, not a second opinion beside the exact-code leg:
		 * that leg is scoped out there ({@link #codesFromDataset}), because with the partner's codes
		 * taken from the dataset it would be asking whether two reference rows share a code, which this
		 * knowledge base says is not identity. So deleting this one line raises the self-chip issue #185
		 * exists to prevent — measured 2026-08-13, three cases redden.
		 *
		 * <p>Worth recording that it was not always so: while the code leg still applied here, deleting
		 * this line left every api test green, because the leg answered the same question wherever such
		 * a partner could raise a chip at all. That was a guard protected by nothing, and scoping the
		 * leg to the case it was kept for is what made it load-bearing rather than a second expression
		 * of the same rule.
		 *
		 * <p><b>The names, and not also the entry the ladder resolved.</b> That entry's substance is
		 * already what the skip's exact-code leg answers, and equivalently so: a row of it publishes
		 * the code that resolved it, and every row of one substance in the shipped KB publishes the
		 * same codes — the DATA invariant {@link DrugSafetyValidator#addInteractionWarnings} already
		 * rests on and already asks to be re-measured on a KB refresh. Adding it here would be a
		 * second, unreachable expression of one rule.
		 *
		 * <p><b>The names are needed because the codes cannot answer for a combination.</b> A
		 * fixed-dose combination resolves an entry for one constituent while its name names the
		 * other, and its combination code is typically absent from the KB. The 3.7.1 demo
		 * dictionary's {@code Isoniazid / Rifapentine} concept is exactly that — {@code J04AB05}
		 * resolves Rifapentine and {@code J04AC51} resolves nothing, which merges both codes onto ONE
		 * partner and renames it after the order — so a skip reading only what the codes resolve
		 * reports the isoniazid in the tablet as duplicating the tablet.
		 *
		 * <p>A SET rather than one key, because a combination names several and because a partner can be
		 * reached by more than one order. What it holds is what EVERY order carrying an unnameable code
		 * of this partner names — not what every order merged into the partner names: two orders of one
		 * substance that merged through their COVERED codes contribute nothing here, and need not,
		 * because the exact-code leg already answers for a code the dataset could name.
		 *
		 * <p><b>The bound this scoping leaves.</b> A combination order whose combination code the KB
		 * DOES carry never reaches this rung at all, so a question about one of its constituents still
		 * gets the self-chip. That is not closable from here — reading the order's names for a covered
		 * code is exactly the over-skip above — and it is unreachable on the shipped KB, which carries
		 * no row for any of the combination codes the 3.7.1 demo dictionary maps. Closing it needs the
		 * KB to say which substances a combination row contains, which it does not publish.
		 */
		private final Set<Object> substances = new LinkedHashSet<Object>();

		/**
		 * The reference entry this partner's label was RESOLVED from — the top rung of the ladder in
		 * {@link DrugSafetyValidator#orderPartners} — or null on the order and code rungs, where no
		 * entry named it.
		 *
		 * <p>Read by three things, and this field is never itself the string printed.
		 * {@link DrugSafetyValidator#reconciledPartnerName} asks whether the rule about to be printed
		 * beside this partner NAMES a row of this entry's substance before letting the class arm's name
		 * displace the rule's own token — the row this RESPONSE elects
		 * ({@link SubstanceSubjects#subjectOf}) first and this entry itself as the fallback, since
		 * issue #339's review round 3; each ask is about the row that would then be printed, and a
		 * refusal of both is a refusal. Only on the branch where {@link #label} is still this entry's
		 * own display label, because {@link #nameByOrder} does not update this field and a renamed
		 * partner's label can therefore name a different drug from the one validated here. So a renamed
		 * partner is validated against {@link #namingOrder} instead, which is the order the label
		 * actually came from; this field is not consulted there at all.
		 * {@link DrugSafetyValidator#classPartnerName} reads it for the same election on that same
		 * branch, so the two arms name one prescription by one row wherever the rule arm's gate admits
		 * the elected one. That read is the same election and NOT the gate above: the class arm has no
		 * rule token, so there is nothing there to validate this field against, which is why what it
		 * prints on that branch is the ladder's own answer for the code and not a displacement of
		 * anything. Never read on the ORDER branch — issue #339's review rounds 5 and 6 did read it
		 * there, to step a refused display back to this entry's own name, and round 7 measured that
		 * this entry is a CONSTITUENT of a partly-covered prescription and so cannot carry a class
		 * claim matched through the code the dataset could not name (see
		 * {@link DrugSafetyValidator#classPartnerName}). The third reader is
		 * {@code CoMedications.partnerNaming}, which reads only its {@code substanceGroupKey()} — to key
		 * this partner in the pass's substance index — and never its name, so nothing about the
		 * paragraph above is weakened by it. Not the same question as {@link #substances}, which is
		 * what an ORDER is known to contain and is populated on one rung only — conflating the two would
		 * widen a suppression that decides which chips are silenced (see that field).
		 */
		private final DrugReference labelEntry;

		/**
		 * Whether {@link #label} is a drug NAME rather than a code standing in for one.
		 *
		 * <p>False in three places, all of them a code standing in for a name: the bare {@code orderCode}
		 * of issue #118's flattened fallback, the {@code [ATC …]} stand-in
		 * {@link PatientClinicalContext.ActiveDrugOrder#namedByCodesOnly} builds for an order no name
		 * could be read for (issue #290), and an order that answers
		 * {@link PatientClinicalContext.ActiveDrugOrder#hasKnownName()} while carrying a BLANK display,
		 * whose label is then the bare code too — the case the last paragraph below is about.
		 *
		 * <p>Read by {@link DrugSafetyValidator#reconciledPartnerName}: a code may not displace the name
		 * the rule arm supplies, which is the direction ADR Decision 38 measured — letting the code list
		 * win "put {@code [ATC N02BA01, N02BA99]} beside the rule arm's {@code aspirin} inside ONE
		 * folded chip detail".
		 *
		 * <p>A property of the LABEL and not of the order, which is why it is set where the label is
		 * chosen rather than derived from {@link PatientClinicalContext.ActiveDrugOrder#hasKnownName()}
		 * alone: on the order rung the label is
		 * {@code firstNonBlank(order.getDisplay(), orderCode)}, so an order that answers
		 * {@code hasKnownName()} but carries a BLANK display is labelled by the bare code — and a chip
		 * naming an active order {@code N02BA01} is what issue #155 exists to remove. Both write sites
		 * therefore gate on {@link DrugSafetyValidator#displayNamesADrug}, once, rather than one of them
		 * inferring it from the label being non-blank.
		 *
		 * <p><b>Setting this also decides {@link #namingOrder}</b>, and the two are written by
		 * {@link #recordNameSource} alone (issue #298): a false answer here means no order is recorded as
		 * having supplied the name. So this is not a flag that can be adjusted on its own — moving it moves
		 * both what {@code reconciledPartnerName}'s first branch reads and whether its second branch is
		 * reachable at all.
		 */
		private boolean namesADrug;

		/** The entry rung, whose three facts travel together: the dataset's own name for the substance,
		 *  the entry that supplied it, and that it IS a name. Stated once so the two sites that take
		 *  this rung cannot come to disagree about what an entry-named partner is. */
		private OrderPartner(DrugReference entry) {
			this.label = entry.displayLabel();
			this.labelEntry = entry;
			recordNameSource(null, true);
		}

		/**
		 * The ladder's last two rungs, whose three facts travel together for the same reason the entry
		 * rung's do (issue #298): the label an ORDER supplies, the order that supplied it, and whether
		 * that label is a name at all. One constructor for both because the difference between them is
		 * only whether an order was reached — a context carrying nothing but the flattened code set
		 * (issue #118) has none, and then the code IS the label.
		 *
		 * <p><b>It takes the order, not the three facts.</b> A caller that passed them separately could
		 * pass a label it did not take from this order beside a flag saying it DID name a drug, and
		 * {@link DrugSafetyValidator#reconciledPartnerName}'s order branch would then validate the RULE
		 * against that order and hand out the other label — the mis-attribution that branch exists to
		 * refuse. Deriving all three here is what makes "a non-null {@link #namingOrder} means
		 * {@link #label} is that order's name" a property of the class rather than of one call site's
		 * discipline. That property is not what keeps the fold safe today — the {@code !namesADrug} branch
		 * asked first still does that — it is what stops a future rung from having to know the branch
		 * order.
		 */
		private OrderPartner(PatientClinicalContext.ActiveDrugOrder order, String orderCode) {
			this.label = order != null
					? ChartSearchAiUtils.firstNonBlank(order.getDisplay(), orderCode)
					: orderCode;
			this.labelEntry = null;
			recordNameSource(order, displayNamesADrug(order));
		}

		/**
		 * The ONE write path for {@link #namingOrder} and {@link #namesADrug} (issue #298), which are two
		 * fields carrying one fact — that {@link #label} is a drug NAME, and which order supplied it.
		 * All three sites take this — both constructors and {@link #nameByOrder}. Neither field can
		 * therefore be set without the other, so a reader of {@link #namingOrder} does not have to know
		 * which order {@code reconciledPartnerName} asks its branches in.
		 *
		 * <p>Shapes were rejected to get here — among them a gate on the constructor's ARGUMENT, and
		 * re-asking {@link DrugSafetyValidator#displayNamesADrug} here instead of taking the flag.
		 * <b>ADR Decision 40 is where that reasoning lives</b>, and it is deliberately not restated here:
		 * three copies of a rejected-alternative argument is how this repo has repeatedly come to
		 * contradict itself. What a future author needs from this method is the rule — write both fields
		 * through it, and derive one from the other rather than asking a second question.
		 *
		 * <p>The compiler cannot enforce this — {@link #namingOrder} cannot be final while
		 * {@link #nameByOrder} renames a partner — so the rule is pinned by a build-time source scan
		 * instead: {@code OrderPartnerNameSourceWritePathTest} fails if either field is assigned anywhere
		 * but here, and if either statement here stops being the expression it must be. It asks for the
		 * SHAPE and not merely that the flag is mentioned, because its first version asked the latter and
		 * a right-hand side naming the flag while storing the order unconditionally was green under it.
		 * A behavioural test cannot
		 * see either, the change being behaviour-neutral; the scan's own javadoc says what it does not
		 * cover.
		 *
		 * <p>{@link #label} is deliberately not a third parameter. On the order rung a label that is a bare
		 * code is CORRECT precisely when {@code namesADrug} is false, so folding it in would have to reject
		 * the very state the ladder's last rung exists to express; what stops the label and the flag
		 * disagreeing is that each constructor computes BOTH itself, from its own arguments, so no caller
		 * supplies them independently. (Not "from one source": the order rung takes the order AND the
		 * code, and labels from the code whenever the display is blank.)
		 */
		private void recordNameSource(PatientClinicalContext.ActiveDrugOrder order, boolean namesADrug) {
			this.namesADrug = namesADrug;
			this.namingOrder = namesADrug ? order : null;
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
		 *
		 * <p><b>That last premise is no longer unconditional, and issue #293 is what weakened it.</b>
		 * An order's display is now the free text a clinician typed wherever there is any, and the
		 * codes are still the concept's — so where those two disagree, this hands the class sentence a
		 * name the cited subgroup does not classify. Measured through the real builder and the real
		 * {@code validate} over the curated seed: an order whose concept is {@code Naproxen} mapped to
		 * {@code M01AE02} and whose free text reads {@code Warfarin 5mg} yields "Ibuprofen is in the
		 * same ATC class (M01AE) as active order Warfarin 5mg", and warfarin is {@code B01AA03}. That
		 * is issue #161's shape, which ADR Decision 38 already accepts for a partly-covered NAMELESS
		 * order and which now also reaches a NAMED one. Not closable here for the reason that decision
		 * gives: the branch is entered because no code resolved an entry, so asking whether the display
		 * and the codes name one substance is undecidable on it — and refusing the display would put
		 * back the bare code this ladder exists to replace. Pinned AS WRONG by
		 * {@code NonCodedDrugOrderNameTest.aClassChipCanNameAnOrderAfterTextTheCitedSubgroupDoesNotClassify},
		 * so a change that closes it reddens a test rather than leaving this paragraph the only record.
		 * The rule sentence of a FOLDED chip does not have this fault — {@link #namesNamingOrder}
		 * validates the display against the rule's own token before it is handed over — which is why
		 * the two sentences are guarded differently.
		 */
		private void nameByOrder(PatientClinicalContext.ActiveDrugOrder order) {
			// The guard is asked HERE rather than at the call site, so that "the display is a name" and
			// the flag a folded chip trusts are decided by one expression inside the object that carries
			// both. It used to be split — the caller asked hasKnownName() and this method only checked
			// the label for blankness — which left the flag written on the strength of a comment saying
			// the caller had checked. A second caller added later would then set namesADrug for a code
			// list and put [ATC …] in BOTH sentences of a folded chip, the failure ADR Decision 38
			// measured, made worse by the fold.
			//
			// The namingOrder clause changed MEANING at issue #298 and no longer says what it used to.
			// It used to mean "an order was already consulted"; since recordNameSource admits an order
			// only where the label is that order's name, it now means "the label already IS an order's
			// name". So monotonicity no longer follows from this clause alone: an ORDER-rung partner whose
			// display is not a name has a null namingOrder and is not stopped here. What stops a second,
			// differently-named order renaming it is REACHABILITY, and that fact is stated nowhere else —
			// orderPartners keys such a partner on the ActiveDrugOrder OBJECT (its identity expression
			// falls to `order` exactly when no entry was resolved), and the only call site passes that
			// same order, so this method can never be re-entered for it with a different one. A partner
			// several orders CAN reach is entry-keyed, which sets namesADrug true, so this clause does
			// still hold the monotonicity there.
			//
			// The clause is UNPINNED, and that is pre-existing rather than something #298 introduced:
			// deleting it leaves the whole api suite green (measured here; review of #298 measured the
			// same on origin/main). It stays because the reachability argument above is the only thing
			// behind it, and a rung that keys an order-rung partner on anything other than the order
			// object would need the clause back before it had anything to lean on.
			if (namingOrder != null || !displayNamesADrug(order)) {
				return;
			}
			// The display itself, and no longer firstNonBlank(display, orderCode): the guard above has
			// refused a blank display, and a label that is a code is what it refuses to let displace a
			// name (see namesADrug). Together those two DO move UNFOLDED class-only chip text, which is
			// more than the fold this ticket is about — a blank-display order used to rename its partner
			// after the bare ATC code, and Decision 39 measures the repair introducing a false claim in
			// one direction (`as active order M01AE03` becoming `as active order Omeprazole` under an
			// M01AE class). But the shape is PRODUCTION-UNREACHABLE, and that is said here rather than
			// only in the ADR: PatientClinicalContextBuilder takes an order's display from a recorded
			// name addRaw has already trimmed, whitespace-collapsed and dropped if blank, and routes
			// the nameless case through namedByCodesOnly, so no order it builds answers
			// hasKnownName() with a blank display. Only
			// the public constructor's latitude reaches the difference, which is the latitude
			// FoldedChipOnePartnerNameTest.aBlankDisplayNeverDisplacesTheDatasetName uses.
			label = order.getDisplay();
			// Through recordNameSource, so this method cannot record an order as the name source without
			// also saying the label is a name — the pair is written by one expression (issue #298).
			//
			// The flag is redundant on the only path that reaches here, which has already set it through
			// its entry: the guard above admits a partner whose namingOrder is null AND whose order names
			// a drug, and since #298 that is still the entry rung alone, because the ladder's own rung
			// records the order whenever the display names one. Passing true is deliberate all the same,
			// so that a future rung renaming a partner whose label was NOT entry-derived cannot leave the
			// flag stale. Mutate the argument to false and read the failures: several cases observe the
			// flag's true value here, and since #298 the mutation nulls the order beside it. No count and
			// no list is given on purpose — this comment carried an exhaustive pair twice and both times
			// it went stale in the very round that added another observer, and an enumeration that is
			// wrong invites the next reader to treat the extra red as a regression they caused.
			recordNameSource(order, true);
		}
	}

	/**
	 * The patient's co-medications, resolved ONCE for a {@code validate} pass and read by every arm of
	 * it that needs them (issue #256).
	 *
	 * <p>{@link DrugSafetyValidator#classRelationships} runs per in-play SUBSTANCE, and it used to call
	 * {@link DrugSafetyValidator#orderPartners} each time. That resolution is a function of the pass's
	 * {@code context} and the loaded dataset — both fixed for the pass — so every call after the first
	 * re-derived an answer the pass already had, and a request's cost grew as drugs-in-play TIMES
	 * active orders rather than as the PAIRS the issue blamed. Measured through the real
	 * {@code validate} over the shipped knowledge base with a 43-order chart, five interleaved runs:
	 * 95–130 ms at one drug in play and 482–488 ms at ten, against 96–134 ms and 130–173 ms with this
	 * class in place. A probe wrapping the resolution (one run, not interleaved) attributed 42% of the
	 * one-drug pass and 77% of the ten-drug pass to it. The same questions against a chart with NO
	 * active orders cost 1.2 ms and 30 ms, which bounds everything the chart does not drive — the
	 * pairwise arms the issue blamed included.
	 *
	 * <p><b>Lazy, and that is not an optimisation of an optimisation.</b> A question that puts no
	 * substance in play — the commonest one — resolves nothing at all today, because
	 * {@link DrugSafetyValidator#classRelationships} is never reached; hoisting the call into
	 * {@code validate} eagerly would make that question pay for a chart nothing asks about. So the pass
	 * carries the ABILITY to resolve and pays on first use.
	 *
	 * <p><b>Two memos, because there are two per-subject sweeps and the first does not reach the
	 * second.</b> {@link #resolved()} holds the partner list; {@link #entryForCode(String)} holds the
	 * code-to-entry resolution that {@link DrugSafetyValidator#orderPartners} needs while building that
	 * list and that {@link DrugSafetyValidator#ruleAbout} needs afterwards, per class hit, from the
	 * arm's own loop. Memoising the list alone would leave {@code ruleAbout} sweeping the dataset per
	 * (subject, partner, code) — measured SMALL, 0, 0, 3 and 5 sweeps at one, two, five and ten drugs
	 * in play against 93 to 433 dataset-walk CALLS in total, over a chart built so that every active order shares a
	 * subgroup with an in-play drug, because that method returns before resolving anything when the
	 * subject has no rule about an active order and that is the ordinary outcome — but real, and a
	 * second copy of the defect this class removes.
	 *
	 * <p><b>A per-call LOCAL and never a field</b> — issue #172's rule, for the reasons
	 * {@link DrugReferenceService}'s class javadoc gives — and which reason applies is asked per memo
	 * rather than reciting both, because they do not both bind. The SINGLETON reason binds each: a
	 * field on this bean would be one unsynchronized structure shared by every concurrent request. The
	 * second binds only {@link #resolved()}, which has no key at all — it is this patient's chart, so a
	 * field would answer for whoever asked first. {@link #entryForCode(String)}'s cache is bounded by
	 * the ATC code space and its values are a function of the loaded dataset rather than of any chart,
	 * so it takes the singleton reason alone.
	 * {@code CoMedicationResolutionPerPassTest} pins the sweep invariant and the chips it must
	 * not move behaviourally, and pins the single construction site structurally — the second because a
	 * field reassigned once per pass sweeps exactly as often as a local does, which is the limit
	 * CLAUDE.md records for the analogous {@code recordedAllergens} memo.
	 *
	 * <p>Sharing ONE partner list across the pass's subjects is sound because nothing downstream
	 * mutates or retains a partner: every write to an {@link OrderPartner} happens inside
	 * {@link DrugSafetyValidator#orderPartners} and
	 * {@link DrugSafetyValidator#addPartnersForUnmappedOrders} while the list is being built,
	 * {@code OrderPartner} declares no {@code equals}/{@code hashCode}, and the only structure keyed on
	 * one is the map {@link DrugSafetyValidator#classRelationships} builds fresh per call and consumes
	 * inside its caller's loop.
	 */
	private final class CoMedications {

		private final PatientClinicalContext context;

		/** {@link DrugSafetyValidator#entryForAtcCode(String, Map)}'s cache, held for the PASS rather
		 *  than for one {@code orderPartners} call — see this class's javadoc for the second reader that
		 *  makes the difference. Bounded by the ATC code space, so it takes only the singleton reason
		 *  above. */
		private final Map<String, DrugReference> entryByCode = new LinkedHashMap<String, DrugReference>();

		private List<OrderPartner> partners;

		CoMedications(PatientClinicalContext context) {
			this.context = context;
		}

		/**
		 * The pass's co-medications, resolved on first use — empty where the module could read no
		 * chart at all.
		 *
		 * <p>The null-context answer lives HERE rather than at the caller, and that is the point of it:
		 * before, {@link DrugSafetyValidator#classRelationships} took the context beside the memo and
		 * used it for nothing but that test, so two parameters carried one fact and a caller could
		 * supply them disagreeing — null-checking one chart while resolving another. Unreachable today
		 * ({@link DrugSafetyValidator#addInteractionWarnings} returns first), which is why this is a
		 * hazard removed rather than a defect fixed. The empty list is immutable and its one consumer
		 * only iterates.
		 */
		List<OrderPartner> resolved() {
			if (partners == null) {
				partners = context == null
						? Collections.<OrderPartner> emptyList()
						: orderPartners(context, entryByCode);
			}
			return partners;
		}

		/**
		 * The entry the dataset files {@code upperCode} under, resolved at most once per pass.
		 * {@code null} is a real answer and is cached as one — see the delegate.
		 *
		 * <p>Named apart from {@link DrugSafetyValidator#entryForAtcCode(String, Map)} deliberately, and
		 * for the reason that method's own javadoc gives one level down: the source guard in
		 * {@code CoMedicationResolutionPerPassTest} forbids a NAME, so each of the three — this
		 * accessor, the memoising overload, and the uncached
		 * {@link DrugSafetyValidator#sweepForAtcCode} — is separately nameable, and a mention of one is
		 * never a mention of another. Sharing a name here would not make two bodies indistinguishable —
		 * a needle that refuses a preceding dot separates them — but it would force the needle to refuse
		 * one, and it would then also miss a {@code this.}-qualified call from inside the outer class.
		 * Three names let the guard stay dot-BLIND, which is the property that costs it nothing.
		 */
		DrugReference entryForCode(String upperCode) {
			return entryForAtcCode(upperCode, entryByCode);
		}

		/** {@link DrugReferenceService#nameIndex()}'s answer, built at most once for the pass. Held
		 *  here rather than on the bean for the reason that method states and CLAUDE.md's issue #172
		 *  rule requires: this bean is a Spring singleton, and an unsynchronised map shared by
		 *  concurrent requests is a stale read away from deciding which substance may be printed under
		 *  another's name. */
		private Map<String, List<DrugReference>> nameIndex;

		/** {@link #partnerNaming}'s index, built at most once for the pass from {@link #resolved()}. */
		private Map<Object, OrderPartner> partnersBySubstance;

		/** {@link #partnerNaming}'s second index, built in the same pass over {@link #resolved()}: the
		 *  co-medication each CHART-recorded ATC code was attributed to. Keyed on the code and not on a
		 *  substance, because it answers for an entry no partner is keyed on at all — see
		 *  {@link #partnerNaming}. */
		private Map<String, OrderPartner> partnersByChartedCode;

		/** Taken by {@link DrugSafetyValidator#reconciledPartnerName} at the one branch that needs it,
		 *  rather than by its callers: the two rungs above that branch — a ladder with no name, and a
		 *  label an ORDER supplied — answer without asking the dataset anything, so a pass whose folds
		 *  all land there builds no index at all. */
		Map<String, List<DrugReference>> nameIndex() {
			if (nameIndex == null) {
				nameIndex = drugReferenceService.nameIndex();
			}
			return nameIndex;
		}

		/**
		 * The co-medication the patient's chart puts {@code entry} on, or null where the ladder reached
		 * none — which is every order the loaded dataset carries no entry for, and every context that
		 * carries no chart at all. Deliberately not "the co-medication resolved for {@code entry}'s
		 * SUBSTANCE", which is only the first of the three rungs below and was the whole of this method
		 * until issue #339's review round 2.
		 *
		 * <p>Two of those rungs are substance keys, and the second is what reaches the shape issue #339
		 * opens with. A
		 * partner's {@link OrderPartner#labelEntry} is the substance the ladder NAMED it after, and for
		 * a partly-covered combination order that is only one of the substances the prescription
		 * contains: the rifapentine half of an isoniazid/rifapentine order names the partner, while a
		 * rule about isoniazid resolves the isoniazid ENTRY, so a {@code labelEntry}-only index misses
		 * it and the two chips of that one payload go on naming the prescription two ways — the live
		 * payload ADR Decision 39 records, {@code active order Isoniazid / Rifapentine} beside
		 * {@code active order isoniazid}. {@link OrderPartner#substances} is what the order's own names
		 * imply, populated on exactly that rung, so it reaches the other half.
		 *
		 * <p>{@code labelEntry} keys are laid down FIRST and a {@code substances} key never displaces
		 * one: a partner that IS a substance speaks for it ahead of one that merely contains it, so a
		 * combination order cannot take a chip away from the single-substance order of the same drug.
		 *
		 * <p><b>And on a THIRD thing, which is not a substance at all</b> (issue #339, review round 2):
		 * the co-medication this pass attributed a CHART-recorded ATC code that {@code entry} also
		 * publishes to ({@link #partnerSharingAChartedCode}). The two sets this method bridges are not
		 * the same set and were never meant to be. {@link SubjectRule#partner} is
		 * {@link DrugSafetyValidator#activeOrderEntryFor}'s answer over
		 * {@link DrugReferenceService#findForActiveOrders} — ATC ∪ NAME, additive on purpose — while
		 * the partners here come from {@link DrugSafetyValidator#orderPartners}, which walks the chart's
		 * codes and then resolves the orders no code reached BY NAME. So ONE prescription can resolve to
		 * two reference substances, only one of which a partner is keyed on: the shipped knowledge base
		 * files {@code Ketoconazole} and {@code Levoketoconazole} as two substances (two
		 * {@code drugbank_id}s) publishing one {@code rxnorm_name} and one identical ATC list, and a
		 * chart carrying a single mapped {@code Ketoconazole} order resolves both. Without this rung the
		 * rule whose partner is the second entry keeps {@link DrugSafetyValidator#partnerLabel} beside a
		 * chip about that same prescription which reconciled — {@code Abacavir interacts with active
		 * order Ketoconazole} next to {@code Ketoconazole interacts with active order ketoconazole},
		 * which is exactly the shape issue #339 exists to remove and which the widening to every rule
		 * chip introduced. Measured through the real {@link DrugSafetyValidator#validate} over the
		 * shipped KB: of its 2114 substances, 84 put a SECOND substance into
		 * {@code findForActiveOrders} for a one-order chart, and sweeping every one of those against up
		 * to three counterpart drugs (216 responses) the rung removes 6 divergent responses and adds
		 * none, taking the responses in which two different {@code active order} labels differ only in
		 * CASE from 6 to 0 — 7 with the reconciliation disabled altogether, so it also closes one the
		 * pre-#339 code had.
		 *
		 * <p>Answering null is not a failure and is the ordinary outcome for an uncovered order — the
		 * caller then keeps {@link #partnerLabel}, which is what every rule chip printed before this.
		 *
		 * <p><b>It CAN hand back a partner the rule is not about, and the caller's gate is what refuses
		 * — do not read a bound into this method that it does not hold.</b> An earlier form of this
		 * javadoc claimed the opposite "by construction", on the premise that
		 * {@link OrderPartner#substances} is populated only on the branch that also calls
		 * {@link OrderPartner#nameByOrder}, so the order gate would apply. That premise is false and the
		 * comment at the write site says so in the opposite direction: since issues #290/#292 the two
		 * are on one branch but no longer under one condition — {@code nameByOrder} withholds the rename
		 * for a synthesized or blank display ({@link DrugSafetyValidator#displayNamesADrug}) while the
		 * {@code substances} leg runs for every carrier — so a partner can hold a null
		 * {@link OrderPartner#namingOrder}, a non-null {@link OrderPartner#labelEntry} and a
		 * {@code substances} set naming a different substance, and {@code reconciledPartnerName} then
		 * takes the ENTRY rung, where {@link DrugSafetyValidator#namesNamingOrder} never runs. The third
		 * rung above widens it much further still, deliberately: an ATC correlation is not identity, and
		 * {@link DrugReference#substanceKey()} exists because this knowledge base publishes one
		 * {@code A02BC05} for {@code Omeprazole} and {@code Esomeprazole} alike.
		 *
		 * <p>What actually refuses is {@link DrugSafetyValidator#unambiguouslyNames}, asked of the row
		 * about to be PRINTED: the rule's token must name that row and outrank every rival claimant, so
		 * the {@code Omeprazole}/{@code Esomeprazole} pair is refused on the ranking rather than on the
		 * lookup, and the caller keeps its own token. That is why widening the lookup cannot open a
		 * mis-attribution class — it can only turn a chip that printed the token into one the gate is
		 * asked about. Mutate that gate and read the failures rather than trusting this — and say WHICH
		 * mutation, because the gate has two halves, they answer differently, and a THIRD mutation a
		 * reader reaches for is neither of them. All three re-measured at issue #339's review round 10
		 * head. Short-circuit {@code unambiguouslyNames} itself so that it always permits, and
		 * {@code FoldedChipOnePartnerNameTest.aRuleAboutAnotherSubstanceSharingTheCodeKeepsItsOwnToken}
		 * — the one about a token whose two claimants share an ATC code, which is the correlation this
		 * rung makes — is among the failures. Replace only the RANKING half, the
		 * {@link DrugReferenceService#uniqueStrongestClaimant} CALL inside that method, and that case is
		 * NOT: its token does not name the ladder's entry at all, so it is refused by the identity half
		 * ({@link #namesEntry}) and a ranking mutation cannot reach it. That MEMBERSHIP is the whole of
		 * what separates the two halves, and it is what to read for. Short-circuiting
		 * {@code uniqueStrongestClaimant} ITSELF is the third mutation and not the second: that method
		 * has a second caller in {@link DrugReferenceService#findNamedSubstances} (the recorded-allergen
		 * path), so it also reddens {@code RecordedAllergenChipNameTest} and
		 * {@code InjectedContraindicationCorroborationTest}, which say nothing about this rung. <b>No
		 * case counts are published for any of the three</b> — the count for the first was wrong in this
		 * javadoc once already, and the count for the second was right for a mutation this sentence did
		 * not name, which is how review round 10 came to check a different one. Name the mutation and
		 * read the failures.
		 */
		OrderPartner partnerNaming(DrugReference entry) {
			if (entry == null) {
				return null;
			}
			if (partnersBySubstance == null) {
				partnersBySubstance = new LinkedHashMap<Object, OrderPartner>();
				partnersByChartedCode = new LinkedHashMap<String, OrderPartner>();
				Set<String> charted = context == null ? Collections.<String> emptySet()
						: context.getActiveDrugAtcCodes();
				for (OrderPartner partner : resolved()) {
					if (partner.labelEntry != null) {
						index(partner.labelEntry.substanceGroupKey(), partner);
					}
					for (String code : partner.codes) {
						if (charted.contains(code) && !partnersByChartedCode.containsKey(code)) {
							partnersByChartedCode.put(code, partner);
						}
					}
				}
				for (OrderPartner partner : resolved()) {
					for (Object substance : partner.substances) {
						index(substance, partner);
					}
				}
			}
			OrderPartner named = partnersBySubstance.get(entry.substanceGroupKey());
			return named != null ? named : partnerSharingAChartedCode(entry);
		}

		/**
		 * The co-medication this pass attributed a CHART-recorded ATC code to that {@code entry} also
		 * publishes, or null where the chart records none of {@code entry}'s codes.
		 *
		 * <p>Read {@link #partnerNaming}'s third paragraph for why this rung exists at all. What it is
		 * NOT is an assertion that the two are one substance — {@link DrugReference#substanceKey()}
		 * exists because this knowledge base publishes one level-5 code for {@code Omeprazole} and
		 * {@code Esomeprazole} alike. It asserts only that {@code entry} is on this chart BY THAT CODE,
		 * which is exactly the predicate {@link DrugReferenceService#findByActiveOrders} used to admit
		 * it, and it hands the answer to a gate that decides separately whether that co-medication's
		 * name may be printed for this rule.
		 *
		 * <p>Scoped to the codes the CHART records, and that conjunct is the whole of the scoping. A
		 * partner reached by NAME ({@link DrugSafetyValidator#addPartnersForUnmappedOrders}) carries the
		 * DATASET's codes for a whole substance rather than a prescription's, so without it an entry
		 * admitted by some OTHER order's code could be named after that unmapped order's prescription.
		 * Only codes the chart itself recorded can have admitted anything. <b>Nothing in the suite pins
		 * that conjunct</b> — measured, removing it leaves the whole api build green — and a reader must
		 * not delete it expecting a test to object. It is defence in depth: in every arrangement tried
		 * the caller's gate refuses the same correlation, because the rule's token names the entry and
		 * not the unmapped order's row, so the conjunct changes no output. It is kept because a
		 * correlation the CHART never made is not one this method may assert, and because the gate is a
		 * ranking that a later widening could soften.
		 *
		 * <p>Where an entry publishes charted codes attributed to two different prescriptions, the
		 * entry's own first such code decides — a presentation choice of the same kind
		 * {@link DrugSafetyValidator#orderCarrying} makes, and one the gate can still refuse.
		 */
		private OrderPartner partnerSharingAChartedCode(DrugReference entry) {
			if (partnersByChartedCode.isEmpty()) {
				return null;
			}
			for (String code : entry.normalizedAtcCodes()) {
				OrderPartner partner = partnersByChartedCode.get(code);
				if (partner != null) {
					return partner;
				}
			}
			return null;
		}

		/** First writer of a key wins — see {@link #partnerNaming} on why the two passes are ordered. */
		private void index(Object substance, OrderPartner partner) {
			if (!partnersBySubstance.containsKey(substance)) {
				partnersBySubstance.put(substance, partner);
			}
		}
	}

	/**
	 * The patient's co-medications as the class arm sees them: every active-order ATC code, grouped by
	 * the co-medication it identifies, followed by the orders no such code reached at all
	 * ({@link #addPartnersForUnmappedOrders}, issue #228) — each in first-appearance order.
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
	 * ({@link #soleSubstanceOf}) — carrying the ORDER's name onto that partner as it goes where the
	 * order HAS a name, because the dataset's name speaks only for the codes it covers (see
	 * {@link OrderPartner#nameByOrder}, and issue #290 for why a synthesized display is withheld).
	 *
	 * <p>Grouping by the order OUTRIGHT would have been wrong in both directions, which is why the
	 * order is consulted only for the codes the dataset cannot speak for. What the order NAMES is a
	 * different question (issue #185) but is read on that same rung and under that same condition —
	 * see {@link OrderPartner#substances}. It would split a substance
	 * the patient holds TWO orders of into two partners, which this ladder deliberately merges; and
	 * it would merge a fixed-dose COMBINATION — one order whose concept maps to the codes of two
	 * different substances — into one, dropping a real duplicate-therapy chip. A combination is
	 * exactly the case {@link #soleSubstanceOf} answers null for, so its uncovered codes stay on the
	 * order rung: with two substances in one tablet there is no evidence which of them an uncovered
	 * code belongs to, and the order is the honest answer.
	 *
	 * <p><b>The ladder is only reached by an order the dictionary CLASSIFIED</b>, which is the whole of
	 * what issue #228 added and is a leg rather than a rung: it is walked per CODE, so an order mapped to
	 * no code entered nowhere on it and the arm was blind to that prescription entirely. Such orders are
	 * resolved afterwards, by name, in {@link #addPartnersForUnmappedOrders} — which is where the
	 * scoping, the naming and the deliberate asymmetry with this loop are recorded. Nothing here changes:
	 * an order this loop walks is grouped, named and skipped exactly as before.
	 *
	 * <p><b>Where the ladder did not hold its promise, and why it now does.</b> A nameless order used
	 * to reach {@link PatientClinicalContext#getActiveDrugAtcCodes()} without reaching
	 * {@code getActiveDrugOrders()}, so {@link #orderCarrying} found nothing and each of its uncovered
	 * codes was its own partner — one prescription, one chip per code. Issue #290 closed it where this
	 * javadoc said it had to be closed: {@link PatientClinicalContextBuilder} gives such an order a
	 * code-only fallback display so it reaches the list, and the grouping here then keys on the ORDER.
	 * For a SINGLE order the grouping needed nothing on this side — the issue #186 rung already keys on
	 * the order. Admitting an order with no name added a second hazard that is not about naming at all:
	 * where two orders carry one dataset-unnameable code, {@link #orderCarrying}'s answer becomes this
	 * loop's {@code identity}, so preferring a carrier that can name itself decides which order the
	 * partner is KEYED on as well as what it is called — without it this change's own test observes TWO
	 * partners for one prescription. The naming needed one thing beside that: a display that is not a
	 * name is withheld from {@link OrderPartner#nameByOrder}.
	 *
	 * <p>What remains is a context built from the flattened sets alone (issue #118), where there is no
	 * order identity to group BY and grouping every unclaimed code together would merge the whole
	 * medication list into one partner. That residue is deliberate.
	 *
	 * <p>What the fallback does NOT do is rename anything the dataset could name: the code-only display
	 * labels a partner only where no entry resolved (the else branch below), and it is withheld from
	 * {@link OrderPartner#nameByOrder}, so it can never replace a dataset name on a partly-covered
	 * order. What that costs is stated on the guard itself — where the shared class was matched through
	 * the unnameable code alone, the covered constituent's name stays, which is issue #161's shape.
	 *
	 * <p><b>What each partner is known to CONTAIN is a separate answer</b> (issue #185), collected on
	 * the same rung the ladder takes the order's NAME from:
	 * {@link OrderPartner#substances} holds what the order's own recorded names imply
	 * ({@link #substancesNamedBy}), added on the same BRANCH as {@link OrderPartner#nameByOrder} — but
	 * since issue #290 no longer under the same condition: the substances leg runs for every carrier,
	 * while the naming leg is withheld when the order's display is synthesized OR blank — since issue #292 both, through {@link #displayNamesADrug}, which is why one unfolded class-only chip's text moves in that change.
	 * The two answers are deliberately not the same thing: the ladder picks ONE identity so the
	 * clinician sees one partner named one way, while the skip that reads this has to know about
	 * every substance in the tablet.
	 *
	 * <p><b>The label follows the same ladder</b> (issue #155). It used to be the entry's label or,
	 * failing that, the bare CODE — so on {@code sourceFormat=json}, then the default, whose four-entry
	 * curated seed carries no aspirin, Agnes Adams' chip read "… as active order N02BA01". A clinician
	 * has no reason to recognise an ATC code, and the order it stands for carries a display name that
	 * needs no reference dataset at all. The code survives only as the last resort, where nothing in
	 * the context names the order either.
	 *
	 * <p>Grouped once per SUBJECT and carried through that subject's sentence and its fold, so the
	 * partner a chip names and the partner {@link #addInteractionWarnings} decides about cannot be
	 * different ones. <b>And now once per {@code validate} as well</b> (issue #256): call this only
	 * through {@link CoMedications}, which is the pass's one resolution and the only caller —
	 * {@link #classRelationships} used to call it per in-play substance, so a request's cost grew as
	 * drugs-in-play TIMES active orders. The resolution this method returns, and the code cache it is
	 * handed, both live on that class as LOCALS threaded through the pass and never fields (issue
	 * #172's rule, for the reasons {@link DrugReferenceService}'s class javadoc gives; not the
	 * {@code getAll()} reload this used to cite, of which there is none). {@link #ruleAbout} reads the
	 * same code cache through that class rather than re-running the sweep for itself.
	 *
	 * <p>Issue #185 added a name resolution to this loop, bounded rather than paid for everywhere:
	 * {@link #substanceRowsNamedBy} runs only for a code the dataset cannot name, and is memoised per
	 * order. Timed interleaved against the pre-change code over the shipped 19 MB KB with
	 * a 30-order, 60-name context, the two were indistinguishable within this machine's run-to-run
	 * spread — which is the reason no figure is quoted here. Issue #256 is the larger order count that
	 * sentence asked to be measured at, and it built the per-pass memo: see {@link CoMedications}.
	 *
	 * <p>Issue #228 gave that resolution a second caller, and it is the one that raises the bound:
	 * {@link #addPartnersForUnmappedOrders} asks it once per DICTIONARY-UNMAPPED order rather than once
	 * per unnameable code, through the same memo, so the work is one dataset sweep per NAME of each
	 * such order — on the 3.7.1 standalone, 27 of 43 orders' worth of names. That is work the
	 * pre-change code did not do at all for an order carrying no codes. It is paid once per
	 * {@code validate} PASS since issue #256, and was once per in-play SUBSTANCE until then; the pass
	 * itself still runs twice per query (pre-answer through
	 * {@link DrugReferenceInjector#preAnswerFindings}, post-answer through {@code LlmInferenceService}),
	 * so a query pays it twice. What that per-order memo cannot save is a name REPEATED across orders,
	 * which is a separate cache and is threaded as one. And because this method now runs once per pass,
	 * "per call" and "per pass" are the same scope for the three memos below.
	 */
	private List<OrderPartner> orderPartners(PatientClinicalContext context,
			Map<String, DrugReference> entryByCode) {
		Map<Object, OrderPartner> byIdentity = new LinkedHashMap<Object, OrderPartner>();
		// Memos, never fields. Each covers a dataset sweep this loop would otherwise repeat:
		// entryForAtcCode is a full scan of getAll() and the rung added by issue #186 asks it once per
		// code of an order as well as once per code of the context, so without the first two a
		// partly-covered order rescans the dataset for every code it carries; substanceRowsNamedBy is a
		// resolution of every name of an order and issue #185 asks it once per UNNAMEABLE code while
		// issue #228's leg asks it once per unmapped order. A
		// field would be issue #172's trap, for the reasons DrugReferenceService's class javadoc gives,
		// NOT the getAll() hot-reload this used to cite, which does not exist. Both of those reasons
		// bite hardest here: this bean is a singleton, and TWO of the four memos are keyed on an
		// ActiveDrugOrder, which is a per-request object, so a field would grow for the life of the JVM.
		// The other two are bounded and take only the singleton reason — entryByCode by the ATC code
		// space, impliedByName by the dataset's own aliases (see findImpliedByDrugName(String, Map)).
		// Four between two owners since issue #256: entryByCode is the CALLER's, a parameter rather than
		// a local, because ruleAbout reads it after this method has returned (see CoMedications); the
		// three declared below are this method's own, and since that issue this method runs once per
		// pass, so per-call and per-pass have become the same scope for them.
		Map<PatientClinicalContext.ActiveDrugOrder, DrugReference> substanceByOrder =
				new LinkedHashMap<PatientClinicalContext.ActiveDrugOrder, DrugReference>();
		Map<PatientClinicalContext.ActiveDrugOrder, Map<Object, List<DrugReference>>> rowsByOrderName =
				new LinkedHashMap<PatientClinicalContext.ActiveDrugOrder, Map<Object, List<DrugReference>>>();
		// The fourth, and the one keyed on a NAME rather than on an order: what the third cannot save is
		// the same name asked twice from two different orders, which is the common shape (a family's
		// orders share aliases). Handed to the service's own cache-taking overload, which is where that
		// sharing is defined — see findImpliedByDrugName(String, Map). A local for the same reason as
		// the others.
		Map<Object, Set<Object>> impliedByName = new HashMap<Object, Set<Object>>();
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
			Object identity = entry != null ? entry.substanceGroupKey()
					: (order != null ? order : (Object) orderCode);
			OrderPartner partner = byIdentity.get(identity);
			if (partner == null) {
				// This site picks the RUNG and nothing else. Since issue #298 the label, whether it is a
				// name, and the order that supplied it are all derived by the constructor the rung
				// selects — see OrderPartner's two constructors and recordNameSource. This comment used
				// to explain that derivation from out here, which is the split #298 removed.
				partner = entry != null ? new OrderPartner(entry)
						: new OrderPartner(order, orderCode);
				byIdentity.put(identity, partner);
			}
			if (unnameableCode && order != null) {
				// This partner holds a code the dataset cannot name, so the dataset's name for its
				// other codes does not speak for the whole of it — see OrderPartner.nameByOrder.
				//
				// Except where the order has no name of its own either. Since issue #290 such an order
				// still reaches the list, labelled by its ATC codes, and that label is the ABSENCE of a
				// name rather than the order's own — so substituting it here discards a real drug name
				// for none. Measured through the real validate over the bundled DDInter sample plus the
				// curated cross-reactivity groups, on a partly-covered nameless order: the
				// issue #88 fold puts the rule arm's sentence and the class arm's in ONE chip detail,
				// and the rule arm names its partner from the RULE's token (partnerLabel), which the
				// builder cannot reach — so the chip read "interacts with active order aspirin …
				// is in the same cross-reactivity group (NSAID) as active order [ATC N02BA01, N02BA99]",
				// naming one order two ways in one sentence. Asked of the ORDER (hasKnownName) rather
				// than of its names being empty: that would be a proxy, since a caller may hand a real
				// display no match tokens, and this decides whether a real drug name is displaced.
				//
				// The residue this accepts, stated because it is the reason nameByOrder prefers the
				// order: where the shared class was matched through the UNNAMEABLE code alone, keeping
				// the dataset's name for the covered one gives a chip whose stated class need not
				// classify the drug it names — issue #161's shape. Reachable only for an order the
				// module could not name AND that is partly covered; the alternative was measured to
				// contradict itself on the same chip, so this trades a narrower fault for a
				// demonstrated one.
				// One guard, and it lives inside nameByOrder — which also means the label it sets is the
				// display itself rather than firstNonBlank(display, orderCode), since a blank display no
				// longer reaches it and a label that is a code is exactly what it refuses to let
				// displace a name (see OrderPartner.namesADrug).
				partner.nameByOrder(order);
				// And, for the same reason though no longer under the same condition (the naming above
				// is withheld for a synthesized display, this is not), what the orders carrying this
				// code NAME is what this partner is known to contain (issue #185). Inside this branch
				// and not beside it: the condition is exactly "the dataset could not name this code,
				// and an order carries it", so what is read here belongs to this code. Reading the
				// partner's naming order instead would let a LATER code of a DIFFERENT order pass
				// and attribute that order's constituents here. EVERY carrier and not just the one the
				// label came from, for the mirror reason — see ordersCarrying. Both are the same
				// hazard: a suppression that depends on the sequence OrderService returned the
				// prescriptions in.
				for (PatientClinicalContext.ActiveDrugOrder carrier : ordersCarrying(orderCode, context)) {
					partner.substances.addAll(substancesNamedBy(carrier, rowsByOrderName, impliedByName));
				}
			}
			partner.codes.add(orderCode);
		}
		addPartnersForUnmappedOrders(byIdentity, context, rowsByOrderName, impliedByName);
		return new ArrayList<OrderPartner>(byIdentity.values());
	}

	/**
	 * @return whether {@code order}'s display is a drug NAME — the one question both
	 *         {@link OrderPartner} write sites ask before letting a display stand as a partner's name,
	 *         written once so they cannot drift (issue #292).
	 *
	 *         <p>Two conditions, neither sufficient alone.
	 *         {@link PatientClinicalContext.ActiveDrugOrder#hasKnownName()} separates a display the order
	 *         really carries from the {@code [ATC …]} stand-in issue #290 synthesizes — asked of the
	 *         ORDER, never re-derived as "are its names empty", which is a proxy the public constructor
	 *         lets a caller falsify. And the display must be non-blank, because the label a blank one
	 *         produces is the bare ATC code
	 *         ({@code firstNonBlank(order.getDisplay(), orderCode)}), and a chip naming an active order
	 *         {@code N02BA01} is what issue #155 exists to remove. The builder produces no such order
	 *         ({@code PatientClinicalContextBuilder} takes the display from a non-blank name and routes
	 *         the nameless case through {@code namedByCodesOnly}) but the public constructor defaults
	 *         {@code nameKnown} to true, and hand-built contexts are how much of this behaviour is
	 *         pinned.
	 */
	private static boolean displayNamesADrug(PatientClinicalContext.ActiveDrugOrder order) {
		return order != null && order.hasKnownName() && !ChartSearchAiUtils.isBlank(order.getDisplay());
	}

	/**
	 * The co-medications the loop above could not see at all: those of the patient's active orders whose
	 * concept the dictionary mapped to no ATC code the context carries, resolved by the ORDER'S NAME
	 * instead and added to {@code byIdentity} as partners of their own (issue #228).
	 *
	 * <p><b>The defect this closes.</b> That loop walks {@link PatientClinicalContext#getActiveDrugAtcCodes()}
	 * and nothing else, so a patient's co-medications were exactly the subset of her prescriptions a
	 * concept DICTIONARY happened to classify. An order carrying no {@code WHOATC} map produced no
	 * partner and the whole duplicate-therapy arm was unreachable for it, however well the loaded
	 * reference data knows that drug — silently, with no chip and no log line to separate "no duplicate
	 * therapy" from "could not look". Measured on the 3.7.1 standalone (2026-08-13, by SQL applying the
	 * predicate {@link PatientClinicalContextBuilder}'s {@code addAtcCodes} applies — a
	 * concept-reference-source name containing {@code ATC}): <b>27 of its 43 active drug orders</b> carry
	 * no ATC code, while {@link DrugReference#normalizedAtcCodes()} over the real
	 * {@link DdiDrugReferenceSource} parse of the 19 MB KB publishes codes for 1839 of its 2283 entries.
	 * The sparsity is the dictionary's, never the reference data's — the same key mismatch issue #151
	 * fixed one layer over, where the injector decided an order's relevance from the ENTRY's codes and
	 * its membership from the ORDER's.
	 *
	 * <p><b>Which orders</b>: the complement of what the loop walked, computed from what it walked rather
	 * than from a proxy for it — an order none of whose codes is in the set above contributed no code to
	 * it and so produced no partner there. That is every order the dictionary mapped to nothing, and also
	 * the shape a context assembled from an order list and a narrower flattened set can hold. An order
	 * the loop DID walk is left alone completely, whatever it resolved to: how a partly-covered order is
	 * grouped and named is issue #186's answer, and this arm's own reachability is not a reason to
	 * re-open it.
	 *
	 * <p><b>Through {@link DrugReferenceService#findImpliedByDrugName}</b>, inside
	 * {@link #substanceRowsNamedBy}, which is the ranked accessor for a recorded drug NAME and the same
	 * resolution {@link DrugReferenceService#findForActiveOrders} — the list the chip layer already
	 * screens and the injector has taken since issue #151 — resolves its own name leg with. Per ORDER
	 * here rather than over the flattened name set that method walks, because a co-medication has to be
	 * attributable to the prescription it IS; in production the two are the same names, since
	 * {@link PatientClinicalContextBuilder} assembles that union out of these very per-order sets. So
	 * the substances this leg can reach are the name half of {@code findForActiveOrders}'s own answer,
	 * and the chip layer cannot come to disagree with the prompt about which prescriptions the reference
	 * data covers — which is the direction the disagreement ran before #151, the injector's answer
	 * always the smaller.
	 *
	 * <p>Never the unranked matcher underneath it: that admits a strict superset, and here the surplus
	 * becomes a co-medication of its own. Issue #209's case is a real unmapped order on this instance —
	 * Sarah Taylor's {@code Hydrocortisone Injection vial 100mg} — and reaches
	 * {@code Hydrocortisone butyrate} unranked, so one prescription would be reported as two, the second
	 * of them an ester she is not on. Both halves are asserted over the verbatim KB slice by
	 * {@code UnmappedOrderClassPartnerTest}.
	 *
	 * <p><b>Named and classified by the dataset, both.</b> The name is the entry's — issue #155's ladder
	 * taken at its first rung, since the dataset HAS a name for this substance — so the chip reads the
	 * same "as active order Dexamethasone" whether or not a dictionary classified the concept, which is
	 * the property this fix is for. The codes are that same row's, for the same reason and because there
	 * is no alternative: the order published none, so what the reference data files the substance under
	 * is the only classification there is — <b>except for the one thing the order CAN say about itself,
	 * which is where the drug is applied</b>. Since issue #234 those codes pass through
	 * {@link DrugReference#codesForRecordedAdministration} first, so a substance marketed by several
	 * routes is compared on the presentation the chart records rather than on all of them. Where the
	 * chart records nothing this module can attribute to a site, the sentence above stands unchanged.
	 *
	 * <p><b>WHICH row of the substance</b> is {@link #interactionSubject}, not the bare fold. A partner
	 * reached from an ATC CODE has no recorded name to prefer, which is why {@link #entryForAtcCode}
	 * answers with {@link DrugReference#canonicalRow} alone (issue #174 site 1); a partner reached from
	 * an order's NAME does, and issue #194 measured what the fold alone then costs — on this very corpus,
	 * a {@code Botulinum toxin type A} order named after {@code Daxibotulinumtoxina}, because both rows
	 * of that substance name no route and the other is the dataset's first. So the chart decides first
	 * and the fold decides the rows tied there, the order issue #187 settled. Re-measured 2026-08-13 by
	 * calling both through the built code over the 19 MB KB, on every (unmapped order, substance) pair
	 * the standalone's own order list resolves — 28 of them: they agree on 27 and differ on that one.
	 *
	 * <p>ONE row and not the group, which is the same choice {@link #entryForAtcCode} makes for this arm
	 * and is lossless on the same DATA invariant recorded at {@link #addInteractionWarnings}: every row
	 * of a substance in the shipped KB publishes the same ATC list. What does NOT rest on that invariant
	 * is the self-skip, which is held explicitly — see {@link OrderPartner#substances}.
	 *
	 * <p>That invariant is also what keeps this loop's answer independent of the sequence
	 * {@code OrderService} returned the prescriptions in. Two orders of one substance recorded under
	 * different row names rank different rows, and the FIRST such order names the partner — a
	 * presentation choice, the same one {@link #orderCarrying} makes on the code rung. It selects a row
	 * rather than a code set only while the rows agree about their codes; where they would not, which
	 * order came back first could decide whether a chip fires at all.
	 *
	 * <p>The codes being the DATASET's is an ASYMMETRY with the loop above, and it runs BOTH ways. A
	 * partner the dictionary mapped is compared on the codes the DICTIONARY published for it, which can
	 * be a proper subset of the substance's, while a partner reached here is compared on every code the
	 * dataset files it under.
	 * <ul>
	 *   <li>So a partly-mapped order can be blind to a subgroup an unmapped one would see. Widening the
	 *       first to match would change chips this issue is not about, and is left open deliberately.</li>
	 *   <li>And an unmapped order can be named in a chip stating a class its own PRESENTATION does not
	 *       belong to — an unmapped {@code Hydrocortisone cream 1%} is classified by every code the
	 *       hydrocortisone row carries, {@link #sharedClass} prefers the systemic one, and the chip says
	 *       {@code H02AB} where the same order mapped to {@code D07AA02} alone would say nothing. This
	 *       is the over-claiming direction, and it is the one a clinician sees.</li>
	 * </ul>
	 * <b>The second of those is issue #234 and is now closed; the first is not.</b> The route WAS
	 * unavailable to this arm — {@link PatientClinicalContextBuilder} read a {@code DrugOrder}'s names
	 * and its concept's ATC mappings and nothing else — and it is now carried, per order, as
	 * {@link PatientClinicalContext.ActiveDrugOrder#getAdministrationTerms()}: every name the order's
	 * route concept publishes and every name its drug's dosage-form concept publishes — not the one
	 * {@code Concept.getName()} elects, which is the locale-PREFERRED spelling and hides the formal one
	 * this module's vocabulary is written in for three of the reference dictionary's own routes. So the
	 * codes this leg loads into the partner go through {@link DrugReference#codesForRecordedAdministration} first, which keeps the
	 * ones classifying a presentation given where the chart says the drug is applied.
	 *
	 * <p>It closes the OVER-claiming direction only, and only where the chart says something this
	 * module can attribute: a term naming no site, and a substance the dataset files under no code at
	 * that site, both leave these codes exactly as they were. Measured on the 3.7.1 demo instance
	 * 2026-08-28, that is all 46 of its active drug orders — 32 recording "Oral administration" and 14
	 * recording nothing — so the narrowing is inert on every corpus this repo can drive and the first
	 * bullet's own case is untouched. The under-claiming direction above is still deliberately open:
	 * widening a partly-mapped order to the dataset's codes would change chips neither issue is about.
	 *
	 * <p><b>Appended after the code walk</b>, so every chip this arm already raised keeps its position and
	 * a newly-reachable co-medication sorts after the ones a dictionary classified. A substance the walk
	 * already reached is skipped outright: two orders of one substance are ONE co-medication (issue #186)
	 * whether they resolved by code or by name, and the partner that exists already carries the
	 * dictionary's own attribution. "Already reached" is {@link #alreadyACoMedication} and not a map
	 * lookup, because the walk keys some partners on the ORDER rather than on a substance.
	 *
	 * <p><b>The bound this leaves at the cross-arm fold.</b> {@link #ruleAbout} correlates a partner to a
	 * rule through {@link #entryForAtcCode}, which answers with the CANONICAL row publishing a code — and
	 * a level-5 code can be published by two substances in this KB ({@code Omeprazole} and
	 * {@code Esomeprazole} share {@code A02BC05}; see {@link #classRelationships}). A partner reached
	 * here carries the reference row's codes rather than an order's, so where the patient is ALSO on a
	 * dictionary-mapped order of the other substance, both partners hold that one code and the fold can
	 * attach this one's sentence to the other one's rule chip — or drop it, which is what
	 * {@link #addInteractionWarnings}'s second-partner branch already does with two partners of one
	 * rule. Both sentences are true and the chip's own subject is unaffected; what is lost is which
	 * co-medication the second sentence is about. Left standing rather than given machinery: it needs
	 * that shared-code pair AND both substances prescribed AND a rated rule, and correlating on the
	 * partner's SUBSTANCE instead is a change to issue #88's fold rather than to this leg.
	 *
	 * <p><b>Since issue #292 that fold depends on this paragraph</b>, and since issue #297 so does the
	 * injected {@code drug_reference} note, which takes the fold's answer in its own vocabulary — so
	 * closing the bound here is no longer a local change, and it now reaches PROMPT text:
	 * {@link #reconciledPartnerName}'s second refusal cites exactly this reasoning
	 * for why it will not let the class arm's label displace a rule's own token, and
	 * {@code FoldedChipOnePartnerNameTest.aRuleAboutAnotherSubstanceSharingTheCodeKeepsItsOwnToken}
	 * pins the behaviour that follows from it. Read that method before narrowing this.
	 */
	private void addPartnersForUnmappedOrders(Map<Object, OrderPartner> byIdentity,
			PatientClinicalContext context,
			Map<PatientClinicalContext.ActiveDrugOrder, Map<Object, List<DrugReference>>> cache,
			Map<Object, Set<Object>> impliedByName) {
		for (PatientClinicalContext.ActiveDrugOrder order : context.getActiveDrugOrders()) {
			if (!governedByTheNameLeg(order, context)) {
				continue;
			}
			for (Map.Entry<Object, List<DrugReference>> named
					: substanceRowsNamedBy(order, cache, impliedByName).entrySet()) {
				if (alreadyACoMedication(byIdentity, named.getKey())) {
					continue;
				}
				DrugReference row = interactionSubject(named.getValue(), order.getNames());
				OrderPartner partner = new OrderPartner(row);
				// The dataset's codes for the SUBSTANCE, narrowed to the presentations the chart
				// records (issue #234). Unnarrowed they cover every route the substance is marketed as,
				// and sharedClass prefers the systemic subgroup among them, so a topical order was named
				// in a systemic duplicate-therapy chip that the SAME order mapped to a topical code does
				// not raise. Asked of the SUBSTANCE and not of this order — see the helper, which is
				// where that distinction is load-bearing.
				partner.codes.addAll(codesForThisSubstancesPresentations(named.getKey(), context,
						row.normalizedAtcCodes(), cache, impliedByName));
				partner.codesFromDataset = true;
				// This partner IS this substance, so restating it is not duplicating it — and with the
				// exact-code leg scoped out above, this is the WHOLE of that skip here.
				partner.substances.add(named.getKey());
				byIdentity.put(named.getKey(), partner);
			}
		}
	}

	/**
	 * @return {@code codes} — the reference row's whole ATC list for {@code substance} — narrowed to the
	 *         presentations this patient's chart records for that substance, or unchanged where it
	 *         records one this module cannot attribute to a site.
	 *
	 *         <p><b>Asked of the SUBSTANCE, over every unmapped order that names it, and not of the one
	 *         order that happened to reach it first.</b> That is the whole reason this is a method. The
	 *         partner is keyed on {@link DrugReference#substanceGroupKey()}, so two orders of one
	 *         substance are ONE co-medication (issue #186) and {@link #alreadyACoMedication} skips the
	 *         second before its terms are ever read — which made the FIRST order in {@code OrderService}
	 *         list order decide the classification for both. Measured through the real {@code validate}
	 *         over {@code ddi-contra-route-variants.json}, a patient on an unmapped
	 *         {@code Hydrocortisone cream 1%} recorded {@code Topical} AND an unmapped
	 *         {@code Hydrocortisone 20mg tablet} recorded {@code Oral administration}, asked about
	 *         dexamethasone: cream first raised nothing, tablet first raised the {@code H02AB} chip. The
	 *         patient is on systemic hydrocortisone in both. A suppression that depends on the sequence
	 *         the prescriptions came back in is the hazard {@code CLAUDE.md} records for
	 *         {@link OrderPartner#substances} and {@link #ordersCarrying}, reached here by another road.
	 *
	 *         <p><b>One presentation this module cannot express declines for the whole substance</b>,
	 *         rather than the union of the sites the others name. The orders are presentations of ONE
	 *         drug the patient is on, so an order it cannot place is a presentation it cannot rule out —
	 *         and the substance's own classification is then the only honest answer, which is the same
	 *         reading {@link DrugReference#codesForRecordedAdministration} takes for a single order that
	 *         names no site. Failing the other way would let a topical cream silence the tablet beside
	 *         it.
	 *
	 *         <p><b>"Cannot express" and not "names no site"</b>, which is
	 *         {@link DrugReference#narrowsAnyCode}'s own distinction and a defect that stood between two
	 *         passes of this change: an order the module places at a site the dataset files no code for
	 *         passes the weaker test, and its decline then rests on a fallback evaluated over the UNION
	 *         of every order's terms — which a sibling that does narrow rescues, taking the first
	 *         order's codes with it.
	 *
	 *         <p>Scoped to the orders THIS leg governs — dictionary-unmapped, through the same
	 *         {@link #governedByTheNameLeg} the caller applies. <b>Nothing in the suite pins that
	 *         conjunct</b>: removing it left the whole api build green, so a reader must not delete it
	 *         expecting a test to object. It is kept because the cover it duplicates is not total. In
	 *         the ordinary case an order the code walk reached produced a partner of its own and
	 *         {@link #alreadyACoMedication} keeps this leg away from that substance entirely — through
	 *         its second half, {@link OrderPartner#substances}, wherever the dataset could name none of
	 *         that order's codes. Where the dataset CAN name them the partner is keyed on the substance
	 *         the dataset names the CODE, that field is not filled, and this leg asks a different
	 *         question: what the order's NAME implies. A substance the name implies and the code does
	 *         not name is therefore invisible to {@link #alreadyACoMedication} and can be given a
	 *         partner here through an unmapped sibling order, at which point this loop would reach the
	 *         mapped order too. Whether the shipped data holds such a pair is not measured; the
	 *         conjunct makes it moot, by keeping a dictionary-mapped prescription's recorded
	 *         administration out of a substance this leg owns.
	 *
	 *         <p><b>What the re-walk costs, measured rather than argued.</b> The WALK is quadratic in
	 *         the orders this leg governs — a {@link Collections#disjoint} and a map lookup per pair —
	 *         but the two expensive halves are not: the {@code containsKey} conjunct admits only the
	 *         orders naming THIS substance, so the site walk is linear, and no name is resolved twice.
	 *         {@code cache} is one memo for the whole {@link #orderPartners} call and the caller's own
	 *         loop shares it, so each governed order's names are resolved once across the whole of
	 *         {@link #addPartnersForUnmappedOrders}, by whichever of the two walks reaches it first.
	 *         Nothing is said here about WHICH walk that is: this one returns early on a decline, so it
	 *         may resolve none, and the property that matters does not depend on the answer. Counted through the real {@code validate} over the shipped KB: 27
	 *         orders give 486 inner iterations against 27 site walks, and 320 give 67550 against 315.
	 *         Timed the same way against a build with the narrowing removed altogether — 3 rounds of 20
	 *         reps at 10, 80 and 320 orders — the two are indistinguishable within this machine's
	 *         run-to-run spread, which is why no short-circuit is built here. Re-measure that way, and
	 *         note that no TIME figure is quoted for the same reason {@link #orderPartners} quotes none.
	 *
	 */
	private Set<String> codesForThisSubstancesPresentations(Object substance,
			PatientClinicalContext context, Set<String> codes,
			Map<PatientClinicalContext.ActiveDrugOrder, Map<Object, List<DrugReference>>> cache,
			Map<Object, Set<Object>> impliedByName) {
		Set<String> recorded = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : context.getActiveDrugOrders()) {
			if (!governedByTheNameLeg(order, context)
					|| !substanceRowsNamedBy(order, cache, impliedByName).containsKey(substance)) {
				continue;
			}
			if (!DrugReference.narrowsAnyCode(codes, order.getAdministrationTerms())) {
				return codes;
			}
			recorded.addAll(order.getAdministrationTerms());
		}
		return DrugReference.codesForRecordedAdministration(codes, recorded);
	}

	/**
	 * @return whether {@code order} is one the NAME leg governs — an order none of whose ATC codes
	 *         reached {@link #orderPartners}' code walk, so it produced no partner there.
	 *
	 *         <p>One expression rather than two, because two methods ask it and a drift between them is
	 *         silent and in the narrowing direction: the helper would then read the administration of an
	 *         order the loop never governed, letting a mapped prescription's route decide a substance
	 *         this leg owns. Extracted for the reason {@code CLAUDE.md} gives for
	 *         {@code PatientClinicalContext.matchableToken} — "not matchable" and "did not match" were
	 *         split out of one predicate precisely so they cannot answer differently.
	 *
	 *         <p>Computed from what the code walk ACTUALLY walked, not from a proxy for it, which is
	 *         {@link #addPartnersForUnmappedOrders}' own rule.
	 */
	private static boolean governedByTheNameLeg(PatientClinicalContext.ActiveDrugOrder order,
			PatientClinicalContext context) {
		return Collections.disjoint(order.getAtcCodes(), context.getActiveDrugAtcCodes());
	}

	/**
	 * @return the substances {@code order}'s own recorded names imply, as
	 *         {@link DrugReference#substanceGroupKey()} values — what a co-medication is known to
	 *         contain where its ATC codes do not say, or do not say all of it (issue #185). Empty when
	 *         the loaded dataset carries none of its names, which is the normal answer for an order
	 *         the reference data does not cover.
	 *
	 *         <p><b>Which accessor resolves the name, and the residual hazard it leaves</b>, are stated once at
	 *         {@link #substanceRowsNamedBy} — they bind this caller and issue #228's leg alike, and for
	 *         opposite reasons, which is why neither of them owns that paragraph. {@link #resolvesFrom} is
	 *         deliberately the unranked superset and answers a different question — which order is a
	 *         SUBJECT's own, where admitting too much costs a pair rather than a suppression.
	 *
	 *         <p>Asked of EVERY order carrying the unnameable code ({@link #ordersCarrying}), not of
	 *         the one {@link #orderCarrying} names the partner after. The label may take the first
	 *         carrier — that is a presentation choice — but a suppression may not, or two orders
	 *         sharing one unnameable code make the skip a function of the sequence
	 *         {@code OrderService} returned the prescriptions in.
	 *
	 *         <p>The keys of {@link #substanceRowsNamedBy}, which is where the resolution and its memo
	 *         live: a suppression needs only the identities, while issue #228's leg needs the rows too,
	 *         and resolving one order's names twice for the two of them is the split issue #151 exists
	 *         to warn about.
	 */
	private Set<Object> substancesNamedBy(PatientClinicalContext.ActiveDrugOrder order,
			Map<PatientClinicalContext.ActiveDrugOrder, Map<Object, List<DrugReference>>> cache,
			Map<Object, Set<Object>> impliedByName) {
		return substanceRowsNamedBy(order, cache, impliedByName).keySet();
	}

	/**
	 * @return whether {@code byIdentity} already holds a co-medication that IS the substance
	 *         {@code substance}, by its identity or by what it is known to contain.
	 *
	 *         <p>The second half is what makes this more than a map lookup, and issue #186's promise
	 *         depends on it: the code walk keys a partner on the ORDER, not on a substance, whenever
	 *         the dataset can name none of that order's codes — and on that same rung it records what
	 *         the order NAMES ({@link OrderPartner#substances}). So a lookup by substance key alone
	 *         cannot see such a partner, and a second, dictionary-unmapped order of the same drug
	 *         becomes a second co-medication: one clinical fact reported twice, once as
	 *         {@code active order Omeprazole 20mg} and once as {@code active order Omeprazole}. Two
	 *         orders of one substance are ONE co-medication however each of them resolved.
	 *
	 *         <p>What it cannot see is a partner keyed on a bare CODE — the context has no order
	 *         there, so nothing says which substance it is. That needs a code the flattened set holds
	 *         and no listed order carries, which since issue #290 {@link PatientClinicalContextBuilder}
	 *         never produces (a nameless order reaches the list too) and a hand-built or flattened-only
	 *         context can produce at will.
	 */
	private static boolean alreadyACoMedication(Map<Object, OrderPartner> byIdentity, Object substance) {
		if (byIdentity.containsKey(substance)) {
			return true;
		}
		for (OrderPartner partner : byIdentity.values()) {
			if (partner.substances.contains(substance)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return the substances {@code order}'s own recorded names imply, each mapped to ALL the rows of it
	 *         those names resolved, in first-appearance order and deduplicated — the resolution
	 *         {@link #substancesNamedBy} projects the keys of and issue #228's leg reads whole, because
	 *         a partner reached from a name needs a row as well as the identity: the dataset's label for
	 *         the chip and the dataset's codes to compare classes on. The rows and not one chosen row,
	 *         because choosing needs the recorded names too and that is {@link #interactionSubject}'s
	 *         composition, made at the call site rather than pre-empted here.
	 *
	 *         <p>ONE resolution behind both views rather than a second walk of the same names for the
	 *         second view — the rule issue #151 settled one layer over, where two derivations of "which
	 *         orders does this patient have" disagreed silently and in one direction.
	 *
	 *         <p><b>Through {@link DrugReferenceService#findImpliedByDrugName}</b>, the ranked accessor
	 *         for a recorded drug NAME, and never the unranked matcher underneath it. Both callers need
	 *         that and for OPPOSITE reasons, which is the reason to say it once here rather than at
	 *         either of them: for issue #185's skip an over-report SILENCES a chip, and for issue #228's
	 *         leg it FABRICATES a co-medication. Sarah Taylor's {@code Hydrocortisone Injection vial
	 *         100mg} is the measured case of both — unranked it reaches {@code Hydrocortisone butyrate}
	 *         (issue #209), a genuinely different substance, which as a suppression loses that ester's
	 *         own duplicate-therapy chip and as a partner reports her as being on a drug she is not.
	 *
	 *         <p>Two shapes reach this through the ranked accessor UNRANKED, and both are that
	 *         accessor's own documented behaviour rather than anything decided here: a name matching
	 *         exactly one row (nothing to rank), and the {@code rowsOf} fallback for a dataset whose
	 *         entries omit their own names — measured there as never firing on any shipped dataset.
	 *
	 *         <p><b>The residual hazard, and what bounds it.</b> An order name that merely CONTAINS
	 *         another substance's whole name resolves it — the phrase-nesting shape
	 *         {@link #identifies} records at its own site — and it costs the two callers different
	 *         things, in the directions above. Measured 2026-08-13 rather than argued: over every one of
	 *         the 3.7.1 demo dictionary's 116 {@code WHOATC}-mapped orderable names, asked about every
	 *         substance the shipped 19 MB KB files in a level-4 subgroup that order carries — 606
	 *         questions — the ranked accessor removes 5 chips and adds none, and all 5 are the self-chip
	 *         issue #185 is about. Re-measure that way rather than reasoning about it, and note the
	 *         population that matters differs per caller: mapped order names for the skip, and the
	 *         orders a dictionary mapped to NOTHING for issue #228's leg.
	 *
	 *         <p>Memoised through {@code cache} for the duration of one {@link #orderPartners} call:
	 *         this is a dataset sweep per name, and an order with several unnameable codes asks it
	 *         once per such code — see there for why the memo may not be a field. An empty answer is a
	 *         real one and is cached as such. The per-NAME resolution cache is a different one and is
	 *         threaded separately, because names repeat ACROSS orders where this memo cannot help.
	 */
	private Map<Object, List<DrugReference>> substanceRowsNamedBy(
			PatientClinicalContext.ActiveDrugOrder order,
			Map<PatientClinicalContext.ActiveDrugOrder, Map<Object, List<DrugReference>>> cache,
			Map<Object, Set<Object>> impliedByName) {
		Map<Object, List<DrugReference>> cached = cache.get(order);
		if (cached != null) {
			return cached;
		}
		// A LinkedHashSet, then the shared grouping — not a grouping written out here. The dedup is
		// identity (DrugReference defines no equals, and every row comes from the service's shared
		// getAll() cache), which is what makes the set the right collector: an order's several names
		// resolve overlapping row sets, and a row is one row.
		Set<DrugReference> matched = new LinkedHashSet<DrugReference>();
		for (String name : order.getNames()) {
			matched.addAll(drugReferenceService.findImpliedByDrugName(name, impliedByName));
		}
		Map<Object, List<DrugReference>> rows = substanceRows(matched);
		cache.put(order, rows);
		return rows;
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

	/** {@link #sweepForAtcCode} memoised for one {@code validate} pass — {@code cache} is
	 *  {@link CoMedications}' own, since issue #256, because {@link #ruleAbout} asks this after
	 *  {@link #orderPartners} has finished asking it. {@code null} is a real answer ("the dataset does
	 *  not cover this code") and is cached as one, so an uncovered code does not rescan the dataset on
	 *  every visit — hence {@code containsKey} rather than a null check.
	 *
	 *  <p>The uncached sweep it delegates to carries a NAME OF ITS OWN, {@link #sweepForAtcCode} — and
	 *  that separation is what a guard can read. A sweep is a full walk of {@code getAll()} per code,
	 *  and {@code ruleAbout} reaching it directly cost one walk per (subject, partner, code); while the
	 *  two shared this method's name, the shape that reinstates that — dropping the {@code cache}
	 *  argument at a call site — resolved one overload to the other rather than writing a new mention,
	 *  at the very sites a guard over that name had to permit. Under two names it does not compile at
	 *  all, and a call
	 *  written to the sweep's own name is a mention {@code CoMedicationResolutionPerPassTest} forbids
	 *  outside this body and the sweep's own. What that guard reads is source text, so it can still be
	 *  evaded; that test names the shapes it does not see rather than claiming there are none. */
	private DrugReference entryForAtcCode(String upperCode, Map<String, DrugReference> cache) {
		if (cache.containsKey(upperCode)) {
			return cache.get(upperCode);
		}
		DrugReference entry = sweepForAtcCode(upperCode);
		cache.put(upperCode, entry);
		return entry;
	}

	/**
	 * @return the patient's active order whose own concept maps to {@code upperCode} (issue #132's
	 *         per-order codes), or null — which is the normal answer for a context built from the
	 *         flattened sets alone (issue #118). It is no longer the answer for an order the module
	 *         could not name: since issue #290 such an order is in the list, labelled by its codes.
	 *
	 *         <p><b>A carrier that can NAME itself is preferred over one that cannot</b>, and that
	 *         preference arrived with issue #290. This answer decides a partner's label and its
	 *         identity, and {@link #ordersCarrying} records that naming a partner after the FIRST
	 *         carrier is merely a presentation choice — which held while every carrier had a name. A
	 *         code-only order breaks it: where one dataset-unnameable code is carried both by a
	 *         nameless order and by a named one, taking the first would let {@code [ATC N02BA99]}
	 *         displace {@code Aspirin 81mg} on a clinician-facing chip, decided by nothing but the
	 *         sequence {@code OrderService} returned the prescriptions in — the same sequence
	 *         dependence issue #185 removed from the skip. It selects on nothing but the NAME: whether
	 *         the carrier it prefers is also the one whose codes resolve to a substance depends on the
	 *         dataset's coverage, so invert the shape and {@link #soleSubstanceOf} still answers null.
	 *         With every carrier named this returns the first, exactly as before; with none named it
	 *         falls back to the first, because then there is nothing better to pick.
	 */
	private static PatientClinicalContext.ActiveDrugOrder orderCarrying(String upperCode,
			PatientClinicalContext context) {
		List<PatientClinicalContext.ActiveDrugOrder> carriers = ordersCarrying(upperCode, context);
		for (PatientClinicalContext.ActiveDrugOrder carrier : carriers) {
			if (carrier.hasKnownName()) {
				return carrier;
			}
		}
		return carriers.isEmpty() ? null : carriers.get(0);
	}

	/**
	 * @return EVERY active order whose own concept maps to {@code upperCode}, in the order the context
	 *         lists them; empty where {@link #orderCarrying} answers null.
	 *
	 *         <p>The set form, because a SKIP may not depend on which carrier came back first. The
	 *         identity/label ladder takes {@link #orderCarrying}'s single answer and always has —
	 *         a partner is named after one order. Naming it after the first WAS a presentation choice,
	 *         while every carrier had a name to offer; since issue #290 one may have none, so that
	 *         method prefers a carrier that can name itself and the choice is no longer between
	 *         equals. What the partner is known to CONTAIN was never a presentation
	 *         choice: with two orders carrying one code
	 *         the dataset cannot name, reading only the first one's names made
	 *         {@code classRelationships}'s restating-existing-therapy skip a function of the sequence
	 *         {@code OrderService} returned the prescriptions in — the same patient told two different
	 *         things (issue #185). Every carrier of the code that reached this partner contributes,
	 *         which is order-independent because the result is a union.
	 */
	private static List<PatientClinicalContext.ActiveDrugOrder> ordersCarrying(String upperCode,
			PatientClinicalContext context) {
		List<PatientClinicalContext.ActiveDrugOrder> out =
				new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		for (PatientClinicalContext.ActiveDrugOrder order : context.getActiveDrugOrders()) {
			if (order.getAtcCodes().contains(upperCode)) {
				out.add(order);
			}
		}
		return out;
	}

	/** @return the ATC level-4 subgroup {@code other} shares with {@code refClasses} that best
	 *          explains a CROSS-REACTIVITY concern between them, or null when they share none that
	 *          may justify one. The choice and its rationale live on {@link #sharedClass}; this
	 *          wrapper exists so the arm that holds a resolved ENTRY does not reduce it itself. */
	private static String sharedCrossReactivityClass(Set<String> refClasses, DrugReference other) {
		return sharedClass(refClasses, other.atcSubgroups(), true);
	}

	/**
	 * The same choice over a bare code set, for the arm whose "other" is a CO-MEDICATION rather than a
	 * resolved entry — the interaction arm compares an order's own ATC mappings, and the dataset need not
	 * carry the substance they identify at all ({@link #classRelationships}). Since issue #228 that set
	 * can instead be a reference row's own codes, for an order no dictionary classified; the choice made
	 * over it is the same one either way, which is the point of it being made here.
	 *
	 * <p>One decision shared by the two arms rather than a scan in each (issue #171): the allergy arm
	 * got the preference in issue #161/#166 and the interaction arm kept naming whichever code it
	 * reached first, so one build could report a pair's topical subgroup as duplicate therapy and its
	 * systemic one as cross-reactivity.
	 */
	private static String sharedTherapyClass(Set<String> refClasses, Set<String> otherSubgroups) {
		return sharedClass(refClasses, otherSubgroups, false);
	}

	/**
	 * @return the ATC level-4 subgroup the two code sets share that best explains the claim named by
	 *         {@code crossReactivity} — the one classifying the SUBSTANCE where they share one, else
	 *         the locally-applied one they do share — or null when they share none that may justify
	 *         that claim at all.
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
	 * available HERE, and the half of that which is about this seam is unchanged by issue #234: this
	 * arm runs both for a drug the QUESTION names, which has no route at all, and for one the patient
	 * is on ({@link #addActiveOrderContraindications}) — and even there it receives a resolved
	 * {@link DrugReference}, not the order. So a route-matching rule could only ever bind one of the
	 * two call sites, and the question-driven half would still be choosing by some other rule. That is
	 * why the preference stays exactly as it is.
	 *
	 * <p><b>What issue #234 changed, and what it did not.</b> The premise that "nothing carries the
	 * route that far" is no longer true of the context: {@link PatientClinicalContextBuilder} now reads
	 * a {@code DrugOrder}'s route concept and its drug's dosage-form concept, and
	 * {@link PatientClinicalContext.ActiveDrugOrder#getAdministrationTerms()} carries them. It is used
	 * at ONE site — {@link #addPartnersForUnmappedOrders}, where a partner reached by NAME holds the
	 * dataset's codes for a whole substance and the chart can say which presentation of it the patient
	 * has. It is deliberately NOT used here. This method compares two code sets and is handed them by
	 * four callers; the narrowing belongs where an order is turned into codes, not where codes are
	 * compared, or the same evidence would be applied twice and once out of reach of the order it came
	 * from.
	 *
	 * <p><b>WHICH PAIRS THE FIGURES BELOW COUNT (issue #243).</b> Two PAIR bases — the CLAIM base named
	 * in the paragraph below headed "But the two arms CAN now name different classes" is a third thing,
	 * counting what {@link #validate} emits rather than what the KB relates — and every figure here
	 * says which of the two it is over, because the same question over the same KB answers 1090 on one
	 * and 319 on the other. A count that does not say is ambiguous in exactly the dimension this code
	 * path keeps getting wrong (#145, #162, #163, #174, #185, #186). A ROW PAIR is an unordered pair of
	 * the entries {@link DdiDrugReferenceSource#parse} loads; a SUBSTANCE PAIR is an unordered pair of
	 * the {@link DrugReference#substanceGroupKey()} families those entries fall into, each represented
	 * by its {@link DrugReference#canonicalRow}. The row base counts a substance pair once per
	 * combination of their rows, and counts in addition the pairs that are two rows of ONE substance,
	 * which have no substance-pair counterpart at all — so over the shipped KB (2283 rows, 2114
	 * substances) 7783 row pairs share at least one level-4 subgroup against 5550 substance pairs, and
	 * 1090 against 319 share more than one, 128 of that 1090 being two rows of one substance. A chip
	 * names a substance, so the substance base is the one that counts what a clinician can see; the row
	 * figures are kept because they are what the decisions recorded here were taken on. What makes a
	 * substance's subgroups well-defined at all is the DATA invariant recorded at
	 * {@link #addInteractionWarnings} — every row of a substance in that KB publishes the same ATC
	 * list — which owns its own count and its own instruction to re-measure it on a KB refresh.
	 *
	 * <p>Every count in the paragraph above and in those that follow down to and including the issue
	 * #168 one, save the superseded 87 that issue #168 was itself filed against, was measured
	 * 2026-08-14 for issue #243 over the shipped KB by driving {@link DdiDrugReferenceSource#parse} for
	 * the load, {@link DrugReference#substanceGroupKey()} and {@link DrugReference#canonicalRow} for
	 * the substance base, {@link DrugReference#atcSubgroups()} for the intersections,
	 * {@link DrugReference#isLocallyAppliedAtcCode}, {@link DrugReference#isUnclassifyingAtcCode} and
	 * {@link DrugReference#isPurposeOnlyAtcCode} for the tiers, and — where the text says so —
	 * {@link #validate} itself. Re-measure before relying on one.
	 *
	 * <p><b>And why not report the shared level-3 group instead</b>, which the issue offers as the
	 * answer that is coarser but never false: of the 1090 ROW pairs that share more than one level-4
	 * subgroup, <b>1041 still share more than one level-3 group</b> — 309 of the 319 substance pairs.
	 * Dexamethasone and hydrocortisone share six subgroups spanning six different level-3 groups, so
	 * the collapse removes the chemical subgroup — the part that carries the cross-reactivity claim —
	 * without removing the choice it was supposed to settle.
	 *
	 * <p><b>A preference, never a filter.</b> Counting the locally-applied preference on its own, which
	 * is what it was introduced as — the claim filters described below sit in front of it and are
	 * measured on their own lists — the 1090 ROW pairs partition into 263 whose class this changes, 587
	 * that share no systemic subgroup at all — two topical azoles, two ophthalmic preparations, two
	 * local anaesthetic formulations, for which the locally-applied class IS the honest answer and is
	 * kept — and 240 that were already naming a systemic one; over substance pairs those three are 62,
	 * 165 and 92 in that order, so the 587 is 165. A filter rather than a preference would have to drop
	 * or fabricate a class for the 587, the largest of the three groups.
	 *
	 * <p><b>And why the tie inside the systemic tier is still broken alphabetically (issue #168).</b>
	 * On the same count 70 row pairs — 21 substance pairs — leave the systemic tier holding more than
	 * one candidate; once each arm has refused what its claim does not license that is 69 row / 20
	 * substance pairs for duplicate therapy and 64 / 16 for cross-reactivity, whose stronger
	 * requirement removes four more of the substance pairs and five more of the row ones (issue #168
	 * was filed against a pre-correction count of 87). The 16 are a subset of the 20, so those are 20
	 * distinct pairs; driving {@link #validate} over all 36 pair-and-arm combinations of them, every
	 * one names the alphabetically smallest surviving candidate and none fails to raise a class chip,
	 * so the tie-break really is what decides them.
	 * What ATC's own words support does not break those ties. Three of the 20 hold candidates whose
	 * published names are IDENTICAL — {@code G03AC}/{@code L02AB} "Progestogens",
	 * {@code N01AF}/{@code N05CA} "Barbiturates, plain", {@code L01EG}/{@code L04AH} "Mammalian target
	 * of rapamycin (mTOR) kinase inhibitors" — so for those there is no aptness to rank, only a code.
	 * For the rest, the two ranks this module already derives from those names move almost nothing:
	 * preferring the candidate that asserts more ({@link DrugReference#isPurposeOnlyAtcCode}) moves one
	 * of the 20, {@code Calcium chloride} against {@code Ammonium chloride}, from {@code B05XA}
	 * "Electrolyte solutions" to {@code G04BA} "Acidifiers", and none of the 16; preferring a subgroup
	 * whose own published name does not begin "Other"/"Various" — the name test issue #182's first
	 * family applies as one conjunct rather than as a rule of its own — moves none of either. Neither
	 * reaches issue #168's own example, because {@code H02CA} "Anticorticosteroids" and {@code J02AB}
	 * "Imidazole derivatives" sit in the same tier of both — the first names a target and the second a
	 * structural family, and issue #183 read every level-4 name in the WHO ATC index and put target and
	 * structure on the same side of its one hard line deliberately. A rule preferring {@code J02AB}
	 * would therefore be a new distinction drawn inside a tier and hand-picked from the reported case:
	 * the unmeasured preference issue #161 refused, and the shape whose hardening found issue #161's
	 * own list reproducing the defect it was fixing. Both candidates are true of the pair, so this is a
	 * choice between honest answers and not the defect above.
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
	 *
	 * <p><b>And how strong an assertion each ARM needs</b> (issues #183/#184). {@code crossReactivity}
	 * decides only that, and nothing else: the preference between the surviving candidates — systemic
	 * over locally applied, and sorted so the answer is a function of the two code SETS — is the same
	 * either way and stays here, in one method, which is what issue #171 asked for and is why the arm
	 * is a parameter rather than a second scan.
	 *
	 * <p><b>But the two arms CAN now name different classes for one pair, and that is not #171
	 * returning.</b> #171 was two independent scans that could disagree about which of the same
	 * candidates to prefer. Here the preference is identical and the CANDIDATE SETS differ, because
	 * the arms are making different claims: where a pair shares a purpose-named subgroup and a
	 * chemically named one, "duplicate therapy" is honestly about the first and "cross-reactivity"
	 * honestly about the second, so naming them alike would make one of the two sentences false.
	 * Measured over the shipped KB by calling THIS METHOD both ways on each of the 5550 substance
	 * pairs it relates — a pair base, not the claim base the two lists' own figures use, which counts
	 * what {@code validate} emits and so differs by the handful of claims a question's extra
	 * resolutions add (2026-08-13; re-measure before relying on a figure): 3693 pairs get an answer on
	 * both arms and 4 of them differ — {@code A01AB} against {@code D01AC}/{@code G01AF} for the
	 * imidazoles, {@code B05XA} against {@code G04BA}. Both chips are reachable in one response for a
	 * patient allergic to a drug they are also on; both take the drug being CHECKED as their subject,
	 * so this is the two in-play joins disagreeing, not the order-driven arm.
	 *
	 * <p>A subgroup may justify a claim only as strong as what its own published name asserts. Naming
	 * a purpose is enough to say two drugs duplicate one another and is not enough to say they
	 * cross-react, so the cross-reactivity arm requires a subgroup that
	 * {@link DrugReference#isPurposeOnlyAtcCode} does not recognise and the duplicate-therapy arm only
	 * the weaker {@link DrugReference#isUnclassifyingAtcCode}. Refusing a subgroup does not drop the
	 * claim: the scan continues, so a pair sharing both a purpose-headed and a chemically named
	 * subgroup keeps its cross-reactivity claim under the second — which is why this narrowing costs
	 * only the pairs that share nothing better. The impact of each list is measured on the list
	 * itself.
	 *
	 * <p>{@code otherSubgroups} must be level-4 SUBGROUPS, not full codes — everything here is compared
	 * against {@code refClasses}, which holds subgroups, so a full code would silently match nothing
	 * and the method would report no relationship rather than fail. Both entries satisfy that
	 * differently and neither may stop doing so: {@link #sharedTherapyClass}'s callers reduce first
	 * through {@link DrugReference#atcSubgroups(Set)}, while {@link #sharedCrossReactivityClass}
	 * reduces internally off the entry it is handed.
	 */
	private static String sharedClass(Set<String> refClasses, Set<String> otherSubgroups,
			boolean crossReactivity) {
		String locallyApplied = null;
		for (String subgroup : new TreeSet<String>(otherSubgroups)) {
			if (!refClasses.contains(subgroup) || !justifiesClaim(subgroup, crossReactivity)) {
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

	/** @return whether {@code subgroup} asserts enough to justify the claim being made — chemistry or
	 *          a molecular target for a cross-reactivity claim, any classifying property at all for a
	 *          duplicate-therapy one. The one place the two arms differ. */
	private static boolean justifiesClaim(String subgroup, boolean crossReactivity) {
		return crossReactivity ? !DrugReference.isPurposeOnlyAtcCode(subgroup)
				: !DrugReference.isUnclassifyingAtcCode(subgroup);
	}

	/**
	 * @return the loaded reference entry carrying {@code upperCode} — the dataset's own record for the
	 *         substance an active order's ATC code identifies — or null when the dataset does not cover
	 *         it. One definition, because the class chip NAMES this entry while the cross-arm
	 *         correlation asks WHICH RULE POINTS AT IT ({@link #ruleAbout}): resolving the code two
	 *         ways would let a chip name one substance while the fold decided about another.
	 *
	 *         <p><b>A full walk of {@code getAll()} per code, and named as one</b> so that a guard can
	 *         forbid it by name: {@link #entryForAtcCode(String, Map)} memoises it for the pass and is
	 *         what every arm asks. Callers reach it through that overload; a call written to THIS name
	 *         outside it is a mention {@code CoMedicationResolutionPerPassTest} fails on, whatever
	 *         syntax it uses. Sharing one name with the memo was the state issue #256 shipped, and a
	 *         dropped {@code cache} argument then reinstated the walk as an overload resolution nothing
	 *         could see.
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
	private DrugReference sweepForAtcCode(String upperCode) {
		DrugReference canonical = null;
		for (DrugReference ref : drugReferenceService.getAll()) {
			if (ref.normalizedAtcCodes().contains(upperCode)) {
				canonical = DrugReference.canonicalRow(canonical, ref);
			}
		}
		return canonical;
	}

	/**
	 * At most ONE dose warning for the substance {@code rows} are the reference rows of, named
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
	 * one direction a non-blocking advisory must never take. So the SUBJECT row is tried first and the
	 * rest after it, and the first warning found is the one raised — which keeps the quoted band the
	 * named row's own wherever that row's own ceiling is the one exceeded. (Subject-first, not
	 * canonical-first: this paragraph predates issue #206, which made every arm name the row the chart
	 * records, and the row tried first has followed that choice ever since — including through issue
	 * #238, which moved the subject to the row the QUESTION and the patient's own orders name and so
	 * moved the ceiling quoted with it. Over a fixture publishing 3000 mg/day for a route-qualified row
	 * and 2000 for its unqualified sibling, a stated 4000 mg/day is reported against the named row's own
	 * 3000 where it used to be reported against the answer-widened subject's 2000. That is this ordering
	 * working rather than a regression — the alternative leaves the dose arm naming a substance
	 * differently from the other two arms, which is what {@link SubstanceSubjects} exists to prevent —
	 * and it costs no warning, for the reason the next sentence gives. Both directions are pinned by
	 * {@code PerRequestSubstanceSubjectTest}.) What the collapse gives up
	 * is the ability to report two different published ceilings for one substance, which is not a thing
	 * a clinician can act on: nothing here knows which formulation is in play (see
	 * {@link DrugReference#namesNoRoute()}), so a second ceiling is a second guess, not a second
	 * fact.
	 *
	 * <p><b>Issue #208 item 1 — and the quoted ceiling therefore SAYS which row published it.</b> The
	 * two halves above are independent choices (the name is the subject's, the number is the tripping
	 * row's) and where they land on different rows the sentence read as though the number were the
	 * named row's: a patient on {@code Amoxicillin (suspension)}, whose own published ceiling is 2000
	 * mg/day, was told a stated 4000 mg/day exceeded "the 3000 mg/day maximum" — the unqualified
	 * sibling's number. Preferring the subject's own band instead would drop the warning wherever it
	 * publishes none, which is the direction the paragraph above rules out, so the number stays and
	 * {@link #ceilingAttribution} states its provenance. Note what that does NOT change: which row is
	 * chosen, and what the chip CALLS the substance ({@link SubstanceSubjects}, issue #206) — the
	 * attribution names a second row inside one sentence, and it is worded as a contrast precisely so
	 * that it cannot be read as a second claim about the patient.
	 *
	 * <p><b>Issue #245 — and the dose is read for the SUBSTANCE, which is what makes the stricter
	 * sibling ceiling reachable at all.</b> The walk above tries every row, but it used to re-READ the
	 * answer once per row, and the read was gated on that row's own aliases
	 * ({@link #attributedDoses}). So a row whose alias the answer's wording did not happen to use was
	 * handed no dose to compare, and its band — however strict — could not trip: over those same two
	 * rows, a stated 2500 mg/day was attributed to the unqualified row alone, cleared its 3000 ceiling,
	 * and raised NOTHING, though the presentation the patient is actually on publishes 2000. That is
	 * this layer's one forbidden direction (a missing warning) reached through the same row/subject
	 * split #208 reported as a mis-naming, and it leaves no trace at all — no chip, no log line,
	 * nothing separating "within the ceiling" from "compared against the wrong ceiling".
	 *
	 * <p>The read is therefore hoisted out of the loop and scoped to the substance: rows of one
	 * substance are not competitors for a dose, they are one drug, so "which row's alias sits nearest"
	 * was never the question. One answer, read once, compared against every row's band. Note what that
	 * does and does not settle about WHICH ceiling gets quoted — the order is unchanged and still
	 * first-to-trip, not strictest: the subject is tried first and now genuinely has a dose to compare,
	 * so its own ceiling is quoted wherever the stated dose EXCEEDS it, while a dose that clears the
	 * subject's band and exceeds a sibling's is reported against the sibling's, with
	 * {@link #ceilingAttribution} saying whose it is (#208). That fallback is the whole reason #208
	 * rejected "prefer the subject's band" outright, and it is what keeps a band published only by a
	 * sibling from becoming a lost warning. What this cannot do is warn where the data does not: the
	 * widening is over the rows of ONE substance, and {@link #substanceOwnsDose} still lets any OTHER
	 * substance's nearer alias take the dose away.
	 */
	private void addOverdose(List<SafetyWarning> warnings, List<DrugReference> rows,
			SubstanceSubjects subjects, PatientClinicalContext context, String lowerAnswer,
			List<DrugReference> allEntries) {
		// The same choice of representative row the other arms make, recorded names and all (issues #194,
		// #206): a dose warning, an interaction chip and a contraindication chip about ONE substance in
		// ONE response must not call it three things, which is exactly the divergence anchoring only some
		// of them would have created.
		DrugReference subject = subjects.subjectOf(rows.get(0));
		// Nothing to read a dose FOR unless some row of this substance publishes a band this patient's
		// age and weight make actionable — the same guard each row applies to itself, asked of the
		// family before the answer is scanned rather than after. That ordering is load-bearing rather
		// than tidy: the walk below is the only thing in this arm that costs anything, since
		// substanceOwnsDose compares against every entry in the knowledge base per stated dose, and
		// while it sat behind the per-row guard it never ran at all on a dataset publishing no bands.
		// That IS the shipped default since ADR Decision 36 — config.xml defaults sourceFormat to
		// ddinter, and neither that knowledge base nor an atc export carries a band at all — so this
		// ordering went from protecting the two opt-in deployments to protecting every install, over the
		// largest dataset the module ships. (It was written when the default was the curated seed, which
		// does publish bands, and the comment said so.) Hoisting the walk out of the loop below without
		// hoisting the guard with it would make those installs pay, on every request, for a check their
		// data can never answer.
		if (!anyActionableBand(rows, context)) {
			return;
		}
		// ONE reading of the answer for the whole substance (issue #245), before any row is consulted —
		// the dose a clinician stated is a fact about the drug, not about the row that happens to publish
		// the band it is about to be compared against.
		List<AttributedDose> doses = attributedDoses(lowerAnswer, rows, allEntries);
		if (addOverdose(warnings, subject, subject, doses, context)) {
			return;
		}
		for (DrugReference row : rows) {
			if (row != subject && addOverdose(warnings, subject, row, doses, context)) {
				return;
			}
		}
	}

	/** @return whether any row of one substance publishes a band this check could act on, i.e. whether
	 *          reading a dose out of the answer could lead anywhere. */
	private static boolean anyActionableBand(List<DrugReference> rows, PatientClinicalContext context) {
		for (DrugReference row : rows) {
			if (actionableBand(row, context) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return the band {@code ref} publishes for this patient's age that this check can actually use —
	 *         one carrying a daily ceiling, or a per-kg ceiling for a patient whose weight is known —
	 *         else null.
	 *
	 *         <p>One definition, asked twice: once of the family before the answer is read, and once of
	 *         each row as it is checked. Two copies of "is there anything to compare against" is how the
	 *         family-level skip and the per-row skip would come to disagree, and a disagreement in the
	 *         permissive direction costs a knowledge-base-wide scan per request while one in the
	 *         restrictive direction costs a warning.
	 *
	 *         <p>The patient-INDEPENDENT half of this question — does the band publish a ceiling at all? —
	 *         is named separately as {@link #publishesACeiling}, because a load-time reader can decide
	 *         that half and no more (see {@link DrugReferenceLoad.Arm#DOSE_CEILINGS}). It is not asked
	 *         here: {@code dailyArm || weightArm} below already implies it, so a guard would be a branch
	 *         no input can reach. The shared premise is that a band with neither ceiling is useless to
	 *         both readers, and it is stated rather than enforced twice.
	 */
	private static DrugReference.AgeBand actionableBand(DrugReference ref,
			PatientClinicalContext context) {
		DrugReference.AgeBand band = ref.bandForAge(context != null ? context.getAgeYears() : null);
		if (band == null) {
			return null;
		}
		Double weightKg = context != null ? context.getWeightKg() : null;
		boolean dailyArm = band.getMaxDailyDoseMg() > 0;
		boolean weightArm = weightKg != null && weightKg > 0 && band.getMgPerKgMax() > 0;
		return dailyArm || weightArm ? band : null;
	}

	/**
	 * @return whether {@code band} publishes any ceiling at all — the patient-INDEPENDENT half of
	 *         {@link #actionableBand}, and the only half a load-time reader can decide. A band carrying
	 *         only an age range parses perfectly well, because {@code AgeBand}'s ceiling fields are
	 *         primitives defaulting to 0, and it can then never fire for ANY patient: neither leg of the
	 *         arm above has a number to compare a dose against.
	 *
	 *         <p>Named rather than left inline because {@link DrugReferenceLoad} reports whether the
	 *         loaded dataset can serve this arm at all, and that question and this one must have a single
	 *         answer. Counting bands by PRESENCE instead reports a dosing arm over a dataset that
	 *         publishes no dosing — the "looks healthy, checks nothing" state that report exists to
	 *         remove, reintroduced by the report itself.
	 *
	 *         <p><b>{@link #actionableBand} does not call this</b>, and a reader looking for the arm's
	 *         own use of it will not find one: {@code publishesACeiling} is strictly weaker than that
	 *         method's {@code dailyArm || weightArm}, so a guard there could only return null on inputs
	 *         that already did. The shared premise is stated in that method's javadoc instead of being
	 *         enforced by a branch no input reaches. So this has exactly one production caller, the
	 *         load-time report, and it is that report's cases which pin it.
	 */
	static boolean publishesACeiling(DrugReference.AgeBand band) {
		return band.getMaxDailyDoseMg() > 0 || band.getMgPerKgMax() > 0;
	}

	/**
	 * The dose check for ONE reference row, reported under {@code subject}'s name.
	 *
	 * @param subject the row the warning NAMES — {@link SubstanceSubjects}' answer, i.e. the row the
	 *        chart records where it records one and the canonical row otherwise, so a chip never asserts
	 *        a formulation the chart does not record (the same answer every other arm gets, which since
	 *        issue #206 includes the contraindication chips)
	 * @param ref the row whose published {@code ageBands} the check READS — its ALIASES no longer come
	 *        into it, which is issue #245: the doses were read for the substance before this was called,
	 *        so a row is now consulted for the band it publishes and nothing else
	 * @param doses the substance's stated doses, read once by the caller and shared by every row and by
	 *        both arms below — so a dose counts for the daily and the per-dose check, and for the
	 *        subject row and its siblings, under exactly the same conditions
	 * @return whether a warning was raised, so the caller can stop at the first row that trips
	 */
	private boolean addOverdose(List<SafetyWarning> warnings, DrugReference subject, DrugReference ref,
			List<AttributedDose> doses, PatientClinicalContext context) {
		DrugReference.AgeBand band = actionableBand(ref, context);
		if (band == null) {
			return false;
		}
		Double weightKg = context != null ? context.getWeightKg() : null;
		boolean dailyArm = band.getMaxDailyDoseMg() > 0;
		boolean weightArm = weightKg != null && weightKg > 0 && band.getMgPerKgMax() > 0;
		String label = subject.displayLabel();
		// Whose ceiling this is, said once for both arms — a mismatch is a property of the ROW PAIR, not
		// of which arm noticed it, so wording it per arm is how one arm keeps the defect.
		String ceilingSource = ceilingAttribution(label, ref);
		if (dailyArm) {
			Double dailyMg = parseDailyDoseMg(doses);
			if (dailyMg != null && dailyMg > band.getMaxDailyDoseMg()) {
				warnings.add(new SafetyWarning(SafetyWarning.TYPE_OVERDOSE, label,
						"The stated " + label + " dose ~" + DrugReference.formatNumber(dailyMg)
								+ " mg/day exceeds the "
								+ DrugReference.formatNumber(band.getMaxDailyDoseMg()) + " mg/day maximum for ages "
								+ band.getMinYears() + "-" + band.getMaxYears() + ceilingSource));
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
							+ band.getMaxYears() + ")" + ceilingSource));
			return true;
		}
		return false;
	}

	/**
	 * @return the clause that says whose published ceiling a dose warning just quoted, given the label
	 *         the warning NAMES and the row the band was read off, or the empty string when it is the
	 *         named row's own — which is every warning any BUNDLED dataset can
	 *         raise, since none of them files a substance as more than one row carrying age bands.
	 *
	 *         <p>Worded as a CONTRAST rather than as a bare second name, and that is the whole care in
	 *         it. This sentence is the one place a chip names a row other than its subject, and a chip's
	 *         subject is the row the patient's own chart records (issues #187, #194, #206) — so a bare
	 *         "published for Amoxicillin" beside a warning about {@code Amoxicillin (suspension)} reads
	 *         as a second formulation in play. Naming both and saying which claim attaches to which
	 *         leaves the sentence a fact about the DATASET, which is what it is: the row is where the
	 *         number was filed, not something known about this patient.
	 *
	 *         <p>Silent unless it has something to say, and LABELS are what it compares rather than row
	 *         identity: the same row trivially publishes the same label, and two DIFFERENT rows may carry
	 *         one display name in an operator-editable dataset — for which "publishes for X, not for X" is
	 *         a contradiction rather than a provenance — compared by {@link DrugReference#normalizeName},
	 *         the module's own identity rule between two REFERENCE strings, because {@code Ranitidine} and
	 *         {@code ranitidine} are one name and {@code equals} would let that pair through. Silent for a
	 *         blank label too, which the same
	 *         boundary demands of every section this module renders rather than emitting a literal
	 *         {@code null}. Taking the caller's own label string, not the subject row, is what keeps the
	 *         clause naming what the SENTENCE names: {@code addOverdose} builds both from one value.
	 */
	private static String ceilingAttribution(String named, DrugReference ref) {
		String published = ref.displayLabel();
		if (!worthNamingApart(published, named)) {
			return "";
		}
		return " — a ceiling this dataset publishes for " + published + ", not for " + named;
	}

	/**
	 * @return whether a sentence contrasting the rows labelled {@code published} and {@code named} would
	 *         say anything — false when either label is blank, and false when the two are ONE name.
	 *
	 *         <p>The shared half of {@link #ceilingAttribution} and of
	 *         {@code DrugReferenceInjector.rowAttribution}, which are the same question asked of a chip
	 *         and of the injected record: "this claim is filed under a row other than the one this
	 *         response names — say which". Shared rather than restated because a second copy is how the
	 *         two surfaces come to disagree about when they may speak, and the guard is the load-bearing
	 *         half: an operator-editable dataset may file two rows under ONE display name (the parse
	 *         drops an entry only for a blank id or name), for which "for X, not for X" is a
	 *         contradiction shown to a clinician rather than a provenance.
	 *
	 *         <p>{@link DrugReference#normalizeName} and not {@code equals}, because that is this
	 *         module's identity rule between two REFERENCE strings (CLAUDE.md's three-shapes rule) and
	 *         {@code Ranitidine} and {@code ranitidine} are one name. The blank half is the same
	 *         degradation every rendered section takes: the dataset is operator-editable, so a missing
	 *         label skips its element rather than emitting a literal {@code null}.
	 */
	static boolean worthNamingApart(String published, String named) {
		return !ChartSearchAiUtils.isBlank(published) && !ChartSearchAiUtils.isBlank(named)
				&& !DrugReference.normalizeName(published).equals(DrugReference.normalizeName(named));
	}

	/**
	 * The one clause-scoped, alias-anchored attribution walk every row of a substance and both overdose
	 * arms consume, so a dose counts for the daily and the per-dose check, and against the subject row's
	 * band and its siblings', under exactly the same conditions. One drug is never charged with
	 * another's dose: the answer is split into clauses, and within each clause that names the substance
	 * {@code rows} are the rows of, a {@code N mg} value counts only when (a) it is not introduced by a
	 * limit cue — "maximum", "up to", … make it a ceiling, not a prescribed dose — and (b) one of that
	 * substance's aliases is the nearest drug name to it (no OTHER substance's alias sits strictly
	 * closer). The frequency is read from the same clause, so a frequency stated for a different drug in
	 * a neighbouring sentence is never applied.
	 *
	 * <p><b>Scoped to the substance, not to the row (issue #245).</b> This took a single {@code ref} and
	 * gated on {@code ref.matchesText}, so a substance filed as several rows was read once per row and
	 * each read saw only the wording that used THAT row's aliases. A stated dose therefore reached
	 * whichever row the answer's phrasing happened to name and no other, and a sibling publishing a
	 * stricter ceiling was never compared against — a missing warning, silently. Rows of one substance
	 * publish one substance's dosing; which of them a clinician's sentence happened to name is not
	 * information about the dose.
	 *
	 * <p><b>One rule for what a clause names, at both steps (issue #260).</b> The gate and the
	 * nearest-name comparison now ask {@link DrugReference} the same question — the PROSE rule, symmetric
	 * word boundaries over a diacritic fold — where the comparison used to search for a raw substring.
	 * The two disagreed in both directions and both were silent: {@code penicillin} inside the plural
	 * {@code penicillins} located a rival the gate says the clause does not name, which vetoed a real
	 * dose, and {@code paracetamol} written {@code paracétamol} located nothing at all, so a subject the
	 * gate had just accepted could not claim its own.
	 *
	 * <p><b>And it moves a warning the other way too</b>, which is worth stating because this layer's
	 * standing rule is that nothing firing today may stop. Applying the fold to the whole clause applies
	 * it to RIVALS as well: an answer writing another drug's name with diacritics now locates that rival,
	 * so it can take a dose the raw search left with the subject. That is the accented case above read
	 * from the other side, and it is what the arm's contract asks for — the number was stated nearer the
	 * other drug's name. The boundary half moves toward more warnings except in one case — where the
	 * occurrence NEAREST the dose was a substring one. Then the substance's nearest distance grows, and the
	 * dose can fall outside {@link #MAX_ALIAS_TO_DOSE_DISTANCE} or a rival can become strictly nearer. That case is the
	 * defect itself: a substring is not a naming, so that dose was never this substance's to claim.
	 *
	 * <p>Known limitation (v1): only the literal unit {@code mg} is recognised; doses written in
	 * grams ("1 g"), "mgs", or "milligrams" are not parsed and will not be flagged. That is the
	 * conservative (miss, never false-positive) direction.
	 *
	 * <p>Nesting no longer double-attributes (issue #270). Where two substances' published names nest,
	 * their occurrences can sit at the same distance from the dose — a prefix pair does when the dose
	 * PRECEDES them (shared start), a suffix pair when it FOLLOWS them (shared end), and neither ties in
	 * the other arrangement — so neither was strictly nearer and BOTH claimed the number. The
	 * boundary rule never touched it, because {@code estrone} is a whole word inside {@code estrone
	 * sulfate} and the prose rule finds it exactly where a substring search does; what closed it was
	 * giving {@link #substanceOwnsDose} the occurrence's SPAN, so a tie can be settled by containment.
	 * Two equally-near names neither of which contains the other remain ambiguous and both still claim.
	 */
	private static List<AttributedDose> attributedDoses(String lowerAnswer, List<DrugReference> rows,
			List<DrugReference> allEntries) {
		List<AttributedDose> out = new ArrayList<AttributedDose>();
		for (String rawClause : CLAUSE_DELIMITER.split(lowerAnswer)) {
			// ONE coordinate system per clause, established here (issue #260). Everything below is about
			// POSITIONS in this clause — where the dose sits, where a limit cue sits, how near each
			// substance is named — and the module's matching rule folds diacritics, so a clause left
			// unfolded would have the gate reading one string and the locator another. Converted once at
			// the top rather than inside the locator because neither half of foldedLower preserves
			// length: DOSE_MG's own index has to be an index into the same string the names are found in.
			// foldedLower and not foldDiacritics even though lowerAnswer is already lowercased, so that
			// the operand form is named rather than half-assumed from a parameter 4500 lines away.
			String clause = DrugReference.foldedLower(rawClause);
			if (!namesSubstance(clause, rows)) {
				continue;
			}
			Matcher m = DOSE_MG.matcher(clause);
			while (m.find()) {
				int dosePos = m.start();
				if (precededByLimitCue(clause, dosePos)
						|| !substanceOwnsDose(clause, dosePos, rows, allEntries)) {
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

	/** @return whether any row of the substance {@code rows} names the clause — the clause-level gate
	 *          {@link DrugReference#matchesText} used to answer for ONE row, widened to the substance
	 *          for issue #245's reason: a clause naming the drug names it whichever of its rows
	 *          publishes the alias the wording used.
	 *
	 *          <p>Through {@link DrugReference#matchesFoldedText} since issue #330, because
	 *          {@link #attributedDoses} folded this clause once already and {@code matchesText} would
	 *          fold it again — which is what let the gate and the locator read two different strings.
	 *          See {@link #substanceOwnsDose}, where that divergence was measured and, until this
	 *          accessor existed, could only be reported. */
	private static boolean namesSubstance(String foldedClause, List<DrugReference> rows) {
		for (DrugReference row : rows) {
			if (row.matchesFoldedText(foldedClause)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return true when one of the substance's own aliases is the nearest drug name to the dose at
	 *         {@code dosePos} within {@code clause} (and within {@link #MAX_ALIAS_TO_DOSE_DISTANCE}). A
	 *         DIFFERENT substance's alias sitting strictly closer means the dose belongs to that drug,
	 *         not this one — unless that rival is not independently named here either, which is issue
	 *         #270 read from both sides. One rule does all of it: a name present only as part of a longer
	 *         name the same clause carries is not independently named there. Applied to THIS substance's
	 *         occurrences it drops them; applied to a RIVAL's it stops them vetoing.
	 *
	 *         <p>Stated once. An occurrence counts unless one of the clause's RIVAL occurrences strictly
	 *         contains it — the subject's occurrences and a rival's alike, which is why
	 *         {@link #containedByAny} is asked twice against the one list — and the nearest counting
	 *         occurrence of the subject wins, within {@link #MAX_ALIAS_TO_DOSE_DISTANCE}. So the veto
	 *         carries TWO exceptions. A rival whose span CONTAINS one of the subject's occurrences is
	 *         exempt only where its nearness is that occurrence's ({@link #nearnessInheritedFrom}) — a
	 *         longer name reaching the dose with a word of its OWN vetoes like any rival. A rival nested
	 *         in ANOTHER rival's name is exempt outright, being no more independently named here than a
	 *         sub-span of {@code mine} is.
	 *
	 *         <p>Compared per SUBSTANCE rather than per row since issue #245: a sibling row is not a
	 *         rival claimant for its own substance's dose, so its occurrences count toward the near side
	 *         (they all join {@code mine}) and same-substance rows are excluded from the veto. Both edits
	 *         move in the same direction — a nearer surviving occurrence, a shorter veto list — so
	 *         AS AGAINST THE PER-ROW FORM this predicate could not cost a warning. Between substances
	 *         nothing changes at all.
	 *
	 *         <p><b>That sentence is about #245's two edits and no longer about the predicate as a
	 *         whole</b> (issue #270). The containment filter below deliberately DOES cost a warning the
	 *         per-row form would raise: a substance loses a dose it used to claim when the occurrence
	 *         NEAREST the number is a strict sub-span of a rival's name.
	 *
	 *         <p><b>Its plainest form is also the case it exists for, and it belongs here because the
	 *         veto scoping below refuses the very same silence.</b> Where the substance's ONLY mention is
	 *         nested it is not named in that clause at all, so it claims nothing — and where the
	 *         container publishes no band, which is the ordinary case for a combination product, the
	 *         number is then warned about by NOBODY ({@code Dexamethasone and framycetin 2.5 mg daily}
	 *         against framycetin's 1 mg/day ceiling). Accepted, and what separates it from the case below
	 *         is which question produced the silence. This filter answers whether the clause NAMES this
	 *         substance; where it does not there is nothing to hang the number on, exactly as if the
	 *         knowledge base carried no row for the combination. The veto answers which of two NAMED
	 *         claimants owns the number, and silence there means the arm found a claimant standing beside
	 *         the number and then discarded it. So "unwarned by anybody" is decisive in the second place
	 *         and not the first — read as an absolute it would forbid the filter too.
	 *
	 *         <p>Beyond that, the loss is not confined to a substance whose only mention was nested. One
	 *         named elsewhere too rests its claim on the surviving mentions and meets the ordinary rule
	 *         from there, so a survivor can fall outside {@link #MAX_ALIAS_TO_DOSE_DISTANCE}, or can lose
	 *         to an independent rival the nested mention would have out-ranked
	 *         ({@code Amoxicillin and clavulanate 2.5 mg, estrone too, clavulanate later} attributes to
	 *         the combination and no longer also to Clavulanate, because Estrone sits between the number
	 *         and Clavulanate's surviving mention). Measured through the real {@code validate} over
	 *         {@code drug-reference-nested-name-dose-tie.json}: this arm and the per-row form differ on
	 *         six of the fifteen clauses {@code NestedNameDoseTieTest} carries, all six removals, and
	 *         each of the three shapes is a case there rather than a sentence here. The scalar minimum
	 *         lives in the local {@code nearest}, computed AFTER that filter; {@code mine} is the list of
	 *         occurrences, and nothing here has a magnitude until the filter has run.
	 *
	 *         <p><b>Which is narrower than the filter alone would make it, and deliberately</b> (round-1
	 *         review of #270). Dropping the nested occurrence RAISES {@code nearest}, so on its own the
	 *         filter also handed the container an ordinary strictly-nearer veto over a mention that is
	 *         not nested at all: on {@code Amoxicillin and clavulanate ok, 2.5 mg clavulanate daily} the
	 *         combination's name ends 5 characters before the number while the surviving standalone
	 *         mention starts 7 after it, so the substance the clause doses BY NAME lost its warning — and
	 *         where the container publishes no band of its own, the number went unwarned by anybody.
	 *         That is the silence the paragraph above says this predicate may not produce — a claimant the
	 *         clause names beside the number, found and then discarded — so a rival whose nearness is
	 *         INHERITED from one of {@code mine} does not veto ({@link #nearnessInheritedFrom}). Scoped to
	 *         inherited nearness and not to containment, because a container can be nearer by a word of
	 *         its OWN — measured, and barring those too would add an attribution the per-row form never
	 *         made, which is the bound that method's javadoc states.
	 *
	 *         <p><b>And barring the container is not enough where THREE names nest</b> (rounds 2 and 3 of
	 *         #270). The seam was asymmetric: the filter judged {@code mine} for being mere sub-spans
	 *         while the veto walked every rival regardless. So a rival the clause names ONLY inside a
	 *         longer name still vetoed the substance the clause doses BY NAME — the standalone
	 *         {@code potassium} at the tail of {@code Amoxicillin and clavulanate potassium ok, 2.5 mg
	 *         clavulanate potassium daily}, and the leading constituent {@code dexamethasone} of
	 *         {@code Dexamethasone and framycetin ok, 2.5 mg was given and then framycetin later}, which
	 *         at distance 20 took the number from the surviving mention at 26. Measured through the real
	 *         {@code validate}: nothing else in either clause publishes a ceiling, so each raised no
	 *         warning of any kind, which the per-row form does not do. Closed by asking a rival the same
	 *         containment question the filter asks the subject, against the same UNFILTERED
	 *         {@code rivals}.
	 *
	 *         <p>One-directional by arithmetic, and this half needs stating because barring a
	 *         veto is the permissive direction. Containment bounds a container to no farther than what it
	 *         contains, so a barred rival {@code R} lies inside some rival {@code C} with
	 *         {@code distance(C) <= distance(R)}, and the chain of containers ends at a rival contained by
	 *         no rival — which this bar never touches, so it vetoes in {@code R}'s place. The only chain
	 *         that ends barred ends at a container barred as INHERITED, i.e. at
	 *         {@code distance(C) == distance(m)} for some {@code m} in {@code mine}; the per-row form's
	 *         minimum is then no greater than {@code distance(m)}, which is {@code <= distance(R)}, so
	 *         {@code R} was not strictly nearer THERE either and never vetoed in that form. Re-measured
	 *         over the enlarged fixture: still six of the fifteen clauses, still all six removals, and
	 *         the round-3 clause itself is not one of them — this arm and the per-row form agree on it.
	 *
	 *         <p><b>What that skip gives up, since it is not free either.</b> Where the container's
	 *         dose-facing edge IS this substance's name, the surviving mention faces no rival at all from
	 *         that quarter — so a mention on the far side of the number keeps a dose the container
	 *         states: on {@code Amoxicillin and clavulanate 2.5 mg daily was fine,
	 *         clavulanate is renally cleared} Clavulanate claims the combination's 2.5 mg from 23
	 *         characters away. Measured, that is what the per-row form does too — the choice neither
	 *         introduces that attribution nor removes it — so it is a case this issue leaves open rather
	 *         than one the fix creates. Closing it means a rule about the
	 *         number's IMMEDIATE neighbour ({@code N mg <name>} outranking a nearer name before it),
	 *         which is a different rule needing its own measurement. Pinned in both directions by
	 *         {@code NestedNameDoseTieTest} so neither can move in silence.
	 *
	 *         <p><b>The two edits do not cover the same case, and it is worth being exact about which
	 *         does the work.</b> The defect issue #245 was filed for — {@code Amoxicillin} sitting
	 *         closer to a dose than {@code Amoxicillin (suspension)} and taking it from a row of the
	 *         same drug — is repaired entirely by pooling the rows: the veto below walks
	 *         DIFFERENT-substance rivals only, so no row of this substance can take its own substance's
	 *         dose however near it lands. The exclusion below covers only a row of this substance that this pass did NOT
	 *         resolve (neither in play nor named by an active order) whose alias happens to land nearer
	 *         the dose. That is a narrower case and no bundled fixture poses it, so nothing here pins the
	 *         exclusion — it stays because it is the same defect one step out, not because a test reaches
	 *         it, and trimming it to what the tests happen to reach would reintroduce that defect.
	 *
	 *         <p>That exclusion is the veto's IDENTITY test and nothing more, because the two lists are
	 *         drawn from different universes: {@code mine} from the rows this pass resolved,
	 *         {@code rivals} from {@code allEntries}. An unresolved row of this substance is therefore
	 *         absent from BOTH, so its longer name neither disqualifies a nested occurrence of the
	 *         subject nor bars a rival nested inside it — pre-existing, and shared with the per-row
	 *         form.
	 *
	 *         <p>Identity is {@link DrugReference#substanceGroupKey()}, the module's one answer to "are
	 *         these rows one substance?", so this arm cannot merge a set of rows that the chip's subject
	 *         chooser and the contraindication ledger would split. For a dataset publishing no substance
	 *         name that key is the row itself, which reproduces the per-row behaviour exactly — the case
	 *         every bundled dataset is in.
	 *
	 *         <p><b>"Named" here means what it means everywhere else</b>
	 *         ({@link DrugReference#namedOccurrences}, the prose rule), which it did not until issue
	 *         #260: the walk below asked every entry in the knowledge base a RAW SUBSTRING question,
	 *         which the clause gate above would have answered no to. The walk itself is unchanged — what
	 *         changed is the question. An entry the clause does not name could take a dose away from one
	 *         it does, and, the same disagreement reversed, a subject the gate had just accepted could
	 *         fail to locate itself.
	 *
	 *         <p><b>That second one is now closed structurally, and how it used to fail is worth
	 *         keeping.</b> The gate re-folded the clause ({@link DrugReference#matchesText} folds its
	 *         own operands) while this reads the clause exactly as {@link #attributedDoses} established
	 *         it, and {@link DrugReference#foldedLower} is not idempotent — see there. Measured
	 *         2026-08-14 through both production methods: an entry whose alias is {@code a}, U+1D165,
	 *         U+1D16D was matched by the gate in a clause folded from {@code a}, U+1D16D, U+0E31,
	 *         U+1D165 and was NOT located here, so that substance had no occurrence at all for a clause
	 *         that passed. Unreachable from any dataset or chart string — it needs musical combining
	 *         marks in a drug name — and this paragraph used to end "closing it means changing which
	 *         accessor {@link #namesSubstance} calls, which CLAUDE.md governs. Reported rather than
	 *         taken." Issue #330 built that accessor ({@link DrugReference#matchesFoldedText}) and
	 *         {@link #namesSubstance} calls it, so the gate and the locator read one string. Pinned by a
	 *         source guard rather than by the compiler, and the difference matters: the prose operand is
	 *         a bare {@code String} by design, so handing this one to the unfolded arity COMPILES — a
	 *         review reverted the call with the whole api suite green before
	 *         {@code FoldedOperandTest.theDoseArmsClauseGateReadsTheClauseTheArmFolded} existed.
	 */
	private static boolean substanceOwnsDose(String clause, int dosePos, List<DrugReference> rows,
			List<DrugReference> allEntries) {
		List<DrugReference.NamedOccurrence> mine = namedOccurrences(clause, dosePos, rows);
		if (mine.isEmpty()) {
			return false;
		}
		Object substance = rows.get(0).substanceGroupKey();
		// Every occurrence of a DIFFERENT substance in this clause, collected once. The identity key is
		// still built conditionally — only for an entry whose name actually occurs here, which on a
		// knowledge base of thousands is a handful — but the condition has moved: it used to be "lands
		// nearer than mine", and it is now "occurs at all", because containment cannot be decided from a
		// distance. substanceGroupKey() rebuilds a key from normalized strings on every call, so the
		// difference is worth stating rather than leaving to be discovered.
		List<DrugReference.NamedOccurrence> rivals = new ArrayList<DrugReference.NamedOccurrence>();
		for (DrugReference other : allEntries) {
			List<DrugReference.NamedOccurrence> theirs = other.namedOccurrences(clause, dosePos);
			if (!theirs.isEmpty() && !substance.equals(other.substanceGroupKey())) {
				rivals.addAll(theirs);
			}
		}
		// A rival's longer name CONTAINING one of this substance's occurrences disqualifies that
		// occurrence and not the substance (issue #270): a name present only as part of a longer name the
		// same clause carries is not independently named there, but the same substance may also be named
		// on its own elsewhere in the clause, and then it is. Judging the substance on one occurrence is
		// how a dose the answer states outright gets silenced.
		int nearest = Integer.MAX_VALUE;
		for (DrugReference.NamedOccurrence occurrence : mine) {
			if (!containedByAny(occurrence, rivals) && occurrence.getDistance() < nearest) {
				nearest = occurrence.getDistance();
			}
		}
		if (nearest == Integer.MAX_VALUE || nearest > MAX_ALIAS_TO_DOSE_DISTANCE) {
			return false;
		}
		// And then the rule this arm always had: a different substance named strictly nearer owns the
		// number — asked of every rival that is INDEPENDENTLY NAMED here, which is the filter's own
		// question asked of the other operand, against the same list. A rival whose span CONTAINS one of
		// mine is measuring the name against itself, since the filter above already took that
		// occurrence's claim away — but only where its nearness is that occurrence's, because a longer
		// name can reach the number by a word of its own. And a rival nested in ANOTHER RIVAL's name is
		// a name the clause carries only inside a longer one, so it is not a claimant at all: the
		// standalone `potassium` at the tail of "Amoxicillin and clavulanate potassium", or the leading
		// `dexamethasone` of "Dexamethasone and framycetin". Both were lost warnings before they were
		// scoped, with nothing publishing a ceiling for either number. Neither bar can cost a warning
		// elsewhere; the javadoc above says why, for the second by an arithmetic that also makes it
		// one-directional. Unchanged in effect for every clause with no nesting in it.
		for (DrugReference.NamedOccurrence rival : rivals) {
			if (rival.getDistance() < nearest && !nearnessInheritedFrom(rival, mine)
					&& !containedByAny(rival, rivals)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * @return whether any of {@code others} strictly contains {@code occurrence} — see
	 *         {@link DrugReference.NamedOccurrence#strictlyContains}.
	 *
	 *         <p>One predicate for one question — "is this occurrence only a sub-span of a name the same
	 *         clause carries, and so not independently named here" — which {@link #substanceOwnsDose}
	 *         asks twice against the one list, {@code rivals}: of each of the subject's occurrences, to
	 *         decide which of them may claim the dose, and of each rival, to decide which of them may
	 *         veto it. The occurrence being judged comes from either side, which is why the parameters
	 *         are not named for either role.
	 *
	 *         <p>The rival-against-the-rivals call passes a list {@code occurrence} is ITSELF in, and
	 *         that needs no filtering: {@code strictlyContains} requires the containing span to be
	 *         strictly larger, so an occurrence never contains itself, and two rivals occupying the
	 *         IDENTICAL span (two substances publishing one alias — {@code Menotrophin}) never bar each
	 *         other. The same strictness the subject's own side depends on.
	 */
	private static boolean containedByAny(DrugReference.NamedOccurrence occurrence,
			List<DrugReference.NamedOccurrence> others) {
		for (DrugReference.NamedOccurrence other : others) {
			if (other.strictlyContains(occurrence)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return whether {@code rival}'s distance is INHERITED from one of {@code mine} — it strictly
	 *         contains that occurrence and sits at the SAME distance, which is to say the edge of its span
	 *         facing the dose is this substance's own name. (Containment already bounds a container to no
	 *         FARTHER than what it contains, so equality here is exactly "no nearer".) Such a rival cannot
	 *         be the reason the dose is not this substance's: its nearness IS this substance's nearness,
	 *         so vetoing with it compares the name against itself.
	 *
	 *         <p>{@link #containedByAny}'s relation with the operands the other way round, plus a
	 *         distance test. That predicate answers whether an occurrence is merely a sub-span of a name
	 *         the clause carries, and {@link #substanceOwnsDose} asks it of the subject's occurrences and
	 *         of the rivals alike; this asks a rival the converse — whether it is what one of the
	 *         subject's occurrences is nested IN — where the answer alone settles nothing, which is why
	 *         the distance test is here. Containment ALONE was measured and is wrong in the other
	 *         direction: a container can be strictly nearer than the occurrence it contains, because the
	 *         span reaching toward the dose is the rest of its name and not this substance's. Both shapes
	 *         are one nested pair with the dose after it, and only which EDGE faces the number differs:
	 *         {@code Dexamethasone and framycetin ok, 2.5 mg framycetin daily} (the constituent's name is
	 *         that edge — inherited) against {@code Estrone sulfate 2.5 mg daily, estrone was the
	 *         metabolite} ({@code sulfate} is — not inherited, 1 character out against the contained
	 *         occurrence's 9). Barring those too gives this substance a dose the container's own name sits beside,
	 *         which the per-row form never did, so the fix would stop being one-directional and would add
	 *         false attributions of exactly the kind issue #270 removes.
	 */
	private static boolean nearnessInheritedFrom(DrugReference.NamedOccurrence rival,
			List<DrugReference.NamedOccurrence> mine) {
		for (DrugReference.NamedOccurrence occurrence : mine) {
			if (rival.strictlyContains(occurrence) && rival.getDistance() == occurrence.getDistance()) {
				return true;
			}
		}
		return false;
	}

	/** @return every {@link DrugReference#namedOccurrences} over every row of one substance — where the
	 *          substance is named in {@code text}, by whichever of its rows publishes the name the text
	 *          used. Empty when no row names it. {@code text} is the folded clause
	 *          {@link #attributedDoses} established, and {@code pos} an index into it.
	 *
	 *          <p>Every occurrence and not the nearest, for the reason
	 *          {@link DrugReference#namedOccurrences} gives: whether a substance is INDEPENDENTLY named
	 *          near a dose is a question about each occurrence, and a substance named twice — once nested
	 *          inside a rival's longer name, once on its own — is judged wrongly by any single one of
	 *          them. Rows compound that: a presentation row publishing {@code estrone} beside one
	 *          publishing {@code estrone sulfate} matches different lengths at the same place. */
	private static List<DrugReference.NamedOccurrence> namedOccurrences(String text, int pos,
			List<DrugReference> rows) {
		List<DrugReference.NamedOccurrence> all = new ArrayList<DrugReference.NamedOccurrence>();
		for (DrugReference row : rows) {
			all.addAll(row.namedOccurrences(text, pos));
		}
		return all;
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
