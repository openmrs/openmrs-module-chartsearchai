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
 *       {@link CrossReactivityGroup} (cross-branch family overlap). The rule arm raises one
 *       warning per (drug, matched partner): several rules can name one partner — DDInter's
 *       route variants of a drug all publish the same match token — and they collapse to the
 *       most severe row, see {@link #bestRulePerPartner}. It also interacts with
 *       ANOTHER DRUG THE QUESTION NAMED — see {@link #addQuestionPairInteractions}, the arm that
 *       answers "does A interact with B?" for a patient on neither.</li>
 *   <li><b>Contraindications</b> — the drug is contraindicated by an active allergy or
 *       condition: by a hand-authored rule, by being the same drug as — or sharing an ATC
 *       chemical subgroup with — a recorded allergy (cross-reactivity reasoning), or —
 *       failing both — by sharing a curated {@link CrossReactivityGroup} with the allergy
 *       (cross-branch cross-reactivity).</li>
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
 * <p>Conservative by design: overdose is flagged only when a value can be computed AND it
 * exceeds a published maximum — a daily total over {@code maxDailyDoseMg}, or (only with a
 * fresh recorded weight) a per-administration dose over {@code mgPerKgMax} × weight; class-based
 * interactions skip an active order that is the <em>same</em> drug (restating existing therapy
 * is not a duplicate). A question or answer that names no reference drug produces no warnings
 * (the no-false-positive case).
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
				addClassContraindications(warnings, ref, context);
			}
			if (warnInteractions) {
				addInteractions(warnings, ref, context, severityFloor);
				addClassInteractions(warnings, ref, context);
			}
			if (warnDose) {
				addOverdose(warnings, ref, context, lower, all);
			}
		}
		// LAST, so the patient's own findings lead: a chip about their allergy or their active order
		// is a fact about them, and outranks a reference lookup about a pair they may not be on.
		if (warnInteractions) {
			addQuestionPairInteractions(warnings, questionDrugs, context, severityFloor);
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
	 *         BOTH cites a record naming drug X AND independently proposes X is exempted — but a
	 *         proposal-worthy X is usually question-named (always validated) or actively ordered
	 *         (checked directly by the order-driven arms), so the residual shape is rare and the
	 *         measured alternative was worse (7 of 8 chips about unproposed drugs on one
	 *         enumeration answer).
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
	 * Rule-based interaction chips for {@code ref}: <b>one chip per (this drug, matched partner)</b>
	 * — see {@link #bestRulePerPartner} for why several rules can match one order.
	 */
	private void addInteractions(List<SafetyWarning> warnings, DrugReference ref,
			PatientClinicalContext context, int severityFloor) {
		if (context == null) {
			return;
		}
		for (DrugReference.Interaction i : bestRulePerPartner(ref, context, severityFloor)) {
			String detail = ref.displayLabel() + " interacts with active order " + partnerLabel(i);
			if (i.getNote() != null && !i.getNote().isEmpty()) {
				detail += " — " + i.getNote();
			}
			warnings.add(new SafetyWarning(SafetyWarning.TYPE_INTERACTION, ref.displayLabel(), detail));
		}
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
	 * and per arm: two different drugs in play still chip separately about the same order, and the
	 * class arm ({@link #addClassInteractions}) is untouched, so the rule-plus-class double chip
	 * of issue #88 is a different defect and stays open.
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
	 * <p>The floor is the shared {@code severityFloor} the chart arm is given, so this cannot become
	 * a route around a decision the chip path enforces; and a pair either of whose drugs is an
	 * active order is left to {@link #addInteractions}, whose chip states a fact about THIS patient
	 * — the stronger statement, and reporting both would say one finding in two voices.
	 *
	 * <p>{@link DrugReferenceInjector#orderedInteractionNotes} is deliberately NOT extended to match:
	 * its documented "a partner that raises a chip is exactly a partner promoted here" is scoped to
	 * the active-order arm, and stays exact, because a pair chip's partner is by construction not an
	 * active order. The pair finding's prose grounding comes from elsewhere — since issue #110 the
	 * deterministic finding is itself injected as a numbered, citable record by
	 * {@code preAnswerFindings}, carrying this chip's string verbatim — so the promoted-note budget
	 * and the chip/prose invariant both stay untouched.
	 */
	private void addQuestionPairInteractions(List<SafetyWarning> warnings, Set<DrugReference> questionDrugs,
			PatientClinicalContext context, int severityFloor) {
		if (questionDrugs.size() < 2) {
			return;
		}
		// Indexed so each UNORDERED pair of entries is visited once: DDInter rows are symmetric and
		// the parser writes every pair into both drugs' entries, so walking ordered pairs would report
		// each interaction twice, once from each side.
		List<DrugReference> drugs = new ArrayList<DrugReference>(questionDrugs);
		Set<List<String>> reportedPairs = new LinkedHashSet<List<String>>();
		for (int i = 0; i < drugs.size() - 1; i++) {
			for (int j = i + 1; j < drugs.size(); j++) {
				addQuestionPairInteraction(warnings, drugs.get(i), drugs.get(j), context, severityFloor,
						reportedPairs);
			}
		}
	}

	/** One question-named pair: at most one chip, from whichever side carries the rule. */
	private void addQuestionPairInteraction(List<SafetyWarning> warnings, DrugReference first,
			DrugReference second, PatientClinicalContext context, int severityFloor,
			Set<List<String>> reportedPairs) {
		List<DrugReference.Interaction> forward = aboveFloorRulesAgainst(first, second, severityFloor);
		List<DrugReference.Interaction> reverse = aboveFloorRulesAgainst(second, first, severityFloor);
		if (forward.isEmpty() && reverse.isEmpty()) {
			return;
		}
		if (coveredByActiveOrderArm(forward, context) || coveredByActiveOrderArm(reverse, context)) {
			return;
		}
		// One chip per clinical PAIR, not per pair of ENTRIES — see pairKeyFor.
		if (!reportedPairs.add(pairKeyFor(first, forward, second, reverse))) {
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
		warnings.add(new SafetyWarning(SafetyWarning.TYPE_INTERACTION, subject.displayLabel(), detail));
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
	 * normalized codes instead of against the chart's active orders. Alias matching goes through the
	 * shared {@link DrugReference#matchesText} — the same matcher {@code findByQuery}/
	 * {@code lookupByToken} use to bind free text to an entry — so "this rule points at that drug" is
	 * decided one way throughout.
	 *
	 * <p>Deliberately that matcher and not the order-name one {@code hasActiveDrug} reaches for (issue
	 * #128 splits the two): both operands here are canonical reference strings — a rule's match token
	 * against an entry's aliases — with no localized, inflected display name to tolerate a trailing
	 * letter for, and the orientation is inverted, the rule's TOKEN being the haystack and the entry's
	 * aliases the needles. The nesting hazard therefore runs the other way here, and it is the
	 * symmetric word boundary that stops an alias nested in a longer token from matching
	 * ({@code chlorothiazide} against a {@code hydrochlorothiazide} rule).
	 *
	 * @return true when {@code rule} names reference entry {@code other}
	 */
	private static boolean identifies(DrugReference.Interaction rule, DrugReference other) {
		String token = rule.getToken();
		if (!ChartSearchAiUtils.isBlank(token)
				&& other.matchesText(token.trim().toLowerCase(Locale.ROOT))) {
			return true;
		}
		String atc = DrugReference.normalizeAtcToken(rule.getAtc());
		return atc != null && other.normalizedAtcCodes().contains(atc);
	}

	/**
	 * @return true when any of {@code rules} points at one of the patient's active orders, i.e.
	 *         {@link #addInteractions} already raises this pair as a fact about the patient. ANY,
	 *         over every rule joining the pair rather than only the one this arm would chip, because
	 *         {@code addInteractions} walks them all: two rules can join one pair under different
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
	 * so the pair is keyed by the two drugs' MATCH TOKENS rather than by their entries — the token
	 * being the reference data's own canonical name for a drug, and precisely what the variants share.
	 * Which variant's row then supplies the severity is #115's open half; the dataset's first is kept,
	 * as the chart arm keeps its first.
	 *
	 * <p>De-duplicating inside a safety net can only be done where nothing actionable is lost, and two
	 * entries collapse here only when the reference data gives them the SAME name. In the shape this
	 * exists for, one rule then identifies both, so the surviving chip carries that very rule's
	 * severity and mechanism and all that is dropped is a second spelling of the partner. Entries the
	 * data can tell apart are named by different tokens and keep their own chips — asserted over three
	 * drugs named in one question. The residual is a KB that gives two genuinely different drugs one
	 * token and separates them only by ATC code: the first is reported. Such a token is ambiguous in
	 * the reference data itself: the chart arm reads that same token to decide which of the patient's
	 * orders a rule names, so it cannot resolve the ambiguity either.
	 *
	 * @return the key for this pair. Each drug is named by the rules on the OTHER side of it, so
	 *         {@code first} is named by {@code reverse} and {@code second} by {@code forward} — the
	 *         crossing is deliberate, not a transposition.
	 */
	private static List<String> pairKeyFor(DrugReference first, List<DrugReference.Interaction> forward,
			DrugReference second, List<DrugReference.Interaction> reverse) {
		return unorderedPairKey(pairKeyNameFor(first, reverse), pairKeyNameFor(second, forward));
	}

	/**
	 * @return the reference data's own name for {@code drug}, lower-cased: the match token (or, for a
	 *         rule carrying only a code, the ATC code) of {@code namingRules} — the rules on the other
	 *         side of the pair, which are the ones that name this drug. Falls back to the entry's own
	 *         label when no rule names it at all, so a pair with data on one side only still keys on
	 *         something specific to that drug rather than on a shared blank. That fallback is where
	 *         variants of one drug can key differently (each on its own label), and the direction is
	 *         the safe one for a net — an extra chip, never a dropped one. Not a matcher: this name is
	 *         only ever a key, which is why it may be a label.
	 */
	private static String pairKeyNameFor(DrugReference drug, List<DrugReference.Interaction> namingRules) {
		String name = namingRules.isEmpty() ? drug.displayLabel()
				: ChartSearchAiUtils.firstNonBlank(namingRules.get(0).getToken(), namingRules.get(0).getAtc());
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
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
	 * Class-based contraindication reasoning (needs only ATC codes, so it works for an
	 * authoritative classification source that carries no rules). For the drug {@code ref}
	 * being checked, each recorded allergy token is resolved to a reference drug; a
	 * warning fires when that allergen <em>is</em> {@code ref} (a recorded allergy to the very
	 * drug being checked), shares {@code ref}'s ATC level-4 subgroup (cross-reactivity), or —
	 * failing both — shares a curated {@link CrossReactivityGroup} (cross-<em>branch</em>
	 * cross-reactivity, e.g. aspirin vs an ibuprofen allergy, which ATC's tree cannot express).
	 * One warning per resolved allergen: the most specific match wins, and several aliases of
	 * one allergy warn once.
	 */
	private void addClassContraindications(List<SafetyWarning> warnings, DrugReference ref,
			PatientClinicalContext context) {
		if (context == null) {
			return;
		}
		Set<String> refClasses = ref.atcSubgroups();
		List<CrossReactivityGroup> refGroups = CrossReactivityGroup.groupsOf(ref,
				drugReferenceService.getCrossReactivityGroups());
		if (refClasses.isEmpty() && refGroups.isEmpty()) {
			return;
		}
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
	 * Class-based interaction reasoning: warns when the drug {@code ref} being checked shares
	 * an ATC level-4 subgroup with one of the patient's active orders (additive effects / duplicate
	 * therapy) or — failing that — a curated {@link CrossReactivityGroup} (a cross-branch family
	 * overlap, e.g. ibuprofen recommended over an active aspirin order). An order that is the
	 * <em>same</em> drug as {@code ref} (a shared exact ATC code) is skipped — restating existing
	 * therapy is not a duplicate. Active orders carry ATC codes (the builder maps them), so this
	 * matches on codes directly and names the order from the dataset; the most specific match wins,
	 * so a subgroup + group double-match warns once.
	 */
	private void addClassInteractions(List<SafetyWarning> warnings, DrugReference ref,
			PatientClinicalContext context) {
		if (context == null) {
			return;
		}
		Set<String> refClasses = ref.atcSubgroups();
		List<CrossReactivityGroup> refGroups = CrossReactivityGroup.groupsOf(ref,
				drugReferenceService.getCrossReactivityGroups());
		if (refClasses.isEmpty() && refGroups.isEmpty()) {
			return;
		}
		Set<String> refCodes = ref.normalizedAtcCodes();
		for (String orderCode : context.getActiveDrugAtcCodes()) {
			if (refCodes.contains(orderCode)) {
				// Restating existing therapy is not a duplicate.
				continue;
			}
			if (orderCode.length() >= DrugReference.ATC_SUBGROUP_PREFIX_LENGTH && refClasses
					.contains(orderCode.substring(0, DrugReference.ATC_SUBGROUP_PREFIX_LENGTH))) {
				warnings.add(new SafetyWarning(SafetyWarning.TYPE_INTERACTION, ref.displayLabel(),
						ref.displayLabel() + " is in the same ATC class ("
								+ orderCode.substring(0, DrugReference.ATC_SUBGROUP_PREFIX_LENGTH)
								+ ") as active order " + displayLabelForAtcCode(orderCode)
								+ " — possible duplicate therapy"));
				continue;
			}
			CrossReactivityGroup group = CrossReactivityGroup.sharedGroupForCode(refGroups, orderCode);
			if (group != null) {
				warnings.add(new SafetyWarning(SafetyWarning.TYPE_INTERACTION, ref.displayLabel(),
						ref.displayLabel() + " is in the same cross-reactivity group (" + group.getName()
								+ ") as active order " + displayLabelForAtcCode(orderCode)
								+ " — possible additive or duplicate-class therapy"));
			}
		}
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

	/** @return the synonym-augmented display label ({@link DrugReference#displayLabel()}) of the
	 *          reference drug carrying {@code upperCode}, or the bare code when the active
	 *          order's substance is not present in the loaded dataset. */
	private String displayLabelForAtcCode(String upperCode) {
		for (DrugReference ref : drugReferenceService.getAll()) {
			if (ref.normalizedAtcCodes().contains(upperCode)) {
				return ref.displayLabel();
			}
		}
		return upperCode;
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
