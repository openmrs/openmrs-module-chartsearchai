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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Part 2 of the drug-reference feature: a deterministic post-LLM check that runs
 * after the answer is generated and <em>annotates</em> it with {@link SafetyWarning}s.
 * It never rewrites or blocks the answer — the clinician decides.
 *
 * <p>For every reference drug named in the answer it checks three things against
 * the patient's clinical context and the reference table:
 * <ul>
 *   <li><b>Overdose</b> — a daily dose parsed from the answer exceeds the
 *       reference {@code maxDailyDoseMg} for the patient's age band.</li>
 *   <li><b>Interactions</b> — the drug interacts with one of the patient's active orders,
 *       either by a hand-authored rule OR by sharing an ATC chemical subgroup with an
 *       active order (duplicate-therapy reasoning).</li>
 *   <li><b>Contraindications</b> — the drug is contraindicated by an active allergy or
 *       condition, either by a hand-authored rule OR by being the same drug as — or sharing
 *       an ATC chemical subgroup with — a recorded allergy (cross-reactivity reasoning).</li>
 * </ul>
 *
 * <p>The rule-based checks fire on the entry's own curated {@code interactions}/
 * {@code contraindications}; the <em>class-based</em> checks need only ATC codes, so they
 * are the mechanism by which an authoritative classification source ({@link AtcDrugReferenceSource},
 * which carries no rules) still produces safety reasoning. "Same class" means a shared ATC
 * level-4 chemical subgroup ({@link #ATC_CLASS_PREFIX_LENGTH}), e.g. ibuprofen {@code M01AE01}
 * and naproxen {@code M01AE02} both {@code M01AE}. ATC's tree does not capture cross-branch
 * pharmacological cross-reactivity (aspirin {@code N02BA01} vs ibuprofen {@code M01AE01}); that
 * needs curated data, not classification. See ADR Decision 24.
 *
 * <p>Conservative by design: overdose is flagged only when a daily total can be
 * computed AND it exceeds a published maximum; class-based interactions skip an active order
 * that is the <em>same</em> drug (restating existing therapy is not a duplicate). A
 * sufficient-chart answer with no reference need produces no warnings (the no-false-positive case).
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

	/** "Same ATC class" for class-based reasoning means a shared level-4 (chemical subgroup) prefix —
	 *  the first 5 characters of a level-5 substance code (M01AE01 -> M01AE). Level 4 is the conservative
	 *  granularity: it groups structurally-related drugs (ibuprofen/naproxen, both M01AE) without fanning
	 *  out across an entire broad group (level 3 M01A = all NSAIDs), keeping false positives lowest. */
	static final int ATC_CLASS_PREFIX_LENGTH = 5;

	@Autowired
	private DrugReferenceService drugReferenceService;

	/** Test seam: production wires {@link DrugReferenceService} via {@link Autowired}. */
	void setDrugReferenceService(DrugReferenceService drugReferenceService) {
		this.drugReferenceService = drugReferenceService;
	}

	/**
	 * Production entry point: validates an answer for a patient when the feature
	 * and the validator are both enabled. Reads the patient's clinical context.
	 * Returns an empty list when disabled or nothing is flagged — never null.
	 */
	public List<SafetyWarning> validate(String answer, Patient patient) {
		if (answer == null || !ChartSearchAiUtils.isDrugReferenceEnabled()
				|| !ChartSearchAiUtils.getBooleanGlobalProperty(
						ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS,
						ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_VALIDATE_ANSWERS)) {
			return new ArrayList<SafetyWarning>();
		}
		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		return validate(answer, context);
	}

	/**
	 * Pure validation over an explicit clinical context — no OpenMRS context read —
	 * so the parsing/matching logic is unit-testable. Honours the per-check toggles.
	 */
	List<SafetyWarning> validate(String answer, PatientClinicalContext context) {
		List<SafetyWarning> warnings = new ArrayList<SafetyWarning>();
		if (answer == null || answer.trim().isEmpty()) {
			return warnings;
		}
		String lower = answer.toLowerCase(Locale.ROOT);

		boolean warnDose = toggle(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_DOSE_EXCESS,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WARN_ON_DOSE_EXCESS);
		boolean warnInteractions = toggle(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_INTERACTIONS,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WARN_ON_INTERACTIONS);
		boolean warnContra = toggle(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS);

		List<DrugReference> all = drugReferenceService.getAll();
		for (DrugReference ref : all) {
			if (!ref.matchesText(lower)) {
				continue;
			}
			if (warnContra) {
				addContraindications(warnings, ref, context);
				addClassContraindications(warnings, ref, context);
			}
			if (warnInteractions) {
				addInteractions(warnings, ref, context);
				addClassInteractions(warnings, ref, context);
			}
			if (warnDose) {
				addOverdose(warnings, ref, context, lower, all);
			}
		}
		if (!warnings.isEmpty()) {
			log.info("Drug-safety validator raised {} warning(s)", warnings.size());
		}
		return warnings;
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
				warnings.add(new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, ref.getName(),
						"contraindicated by " + against + ": " + noteOrToken(c.getNote(), c.getToken())));
			}
		}
	}

	private void addInteractions(List<SafetyWarning> warnings, DrugReference ref,
			PatientClinicalContext context) {
		if (context == null) {
			return;
		}
		for (DrugReference.Interaction i : ref.getInteractions()) {
			if (context.hasActiveDrug(i.getToken(), i.getAtc())) {
				String detail = "interacts with active order " + (i.getToken() != null ? i.getToken() : i.getAtc());
				if (i.getNote() != null && !i.getNote().isEmpty()) {
					detail += " — " + i.getNote();
				}
				warnings.add(new SafetyWarning(SafetyWarning.TYPE_INTERACTION, ref.getName(), detail));
			}
		}
	}

	/**
	 * Class-based contraindication reasoning (needs only ATC codes, so it works for an
	 * authoritative classification source that carries no rules). For the drug {@code ref}
	 * named in the answer, each recorded allergy token is resolved to a reference drug; a
	 * warning fires when that allergen <em>is</em> {@code ref} (a recorded allergy to the very
	 * drug recommended) or shares {@code ref}'s ATC level-4 subgroup (cross-reactivity).
	 * Deduplicated per resolved allergen so several aliases of one allergy warn once.
	 */
	private void addClassContraindications(List<SafetyWarning> warnings, DrugReference ref,
			PatientClinicalContext context) {
		if (context == null) {
			return;
		}
		Set<String> refClasses = atcClasses(ref);
		if (refClasses.isEmpty()) {
			return;
		}
		Set<DrugReference> seenAllergens = new LinkedHashSet<DrugReference>();
		for (String allergyToken : context.getAllergyTokens()) {
			DrugReference allergen = drugReferenceService.lookupByToken(allergyToken);
			if (allergen == null || !seenAllergens.add(allergen)) {
				continue;
			}
			if (allergen == ref) {
				warnings.add(new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, ref.getName(),
						"the patient has a recorded allergy to " + ref.getName()));
				continue;
			}
			String shared = sharedClass(refClasses, allergen);
			if (shared != null) {
				warnings.add(new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, ref.getName(),
						"same ATC class (" + shared + ") as the patient's allergy to " + allergen.getName()
								+ " — possible cross-reactivity"));
			}
		}
	}

	/**
	 * Class-based interaction reasoning: warns when the drug {@code ref} named in the answer shares
	 * an ATC level-4 subgroup with one of the patient's active orders (additive effects / duplicate
	 * therapy). An order that is the <em>same</em> drug as {@code ref} (a shared exact ATC code) is
	 * skipped — restating existing therapy is not a duplicate. Active orders carry ATC codes (the
	 * builder maps them), so this matches on codes directly and names the order from the dataset.
	 */
	private void addClassInteractions(List<SafetyWarning> warnings, DrugReference ref,
			PatientClinicalContext context) {
		if (context == null) {
			return;
		}
		Set<String> refClasses = atcClasses(ref);
		if (refClasses.isEmpty()) {
			return;
		}
		Set<String> refCodes = ref.normalizedAtcCodes();
		for (String orderCode : context.getActiveDrugAtcCodes()) {
			if (orderCode.length() < ATC_CLASS_PREFIX_LENGTH) {
				continue;
			}
			String orderClass = orderCode.substring(0, ATC_CLASS_PREFIX_LENGTH);
			if (!refClasses.contains(orderClass) || refCodes.contains(orderCode)) {
				continue;
			}
			warnings.add(new SafetyWarning(SafetyWarning.TYPE_INTERACTION, ref.getName(),
					"same ATC class (" + orderClass + ") as active order " + displayNameForAtcCode(orderCode)
							+ " — possible duplicate therapy"));
		}
	}

	/** @return the ATC level-4 subgroup prefixes of {@code ref}'s codes; codes shorter than the
	 *          prefix length contribute no class. */
	private static Set<String> atcClasses(DrugReference ref) {
		Set<String> out = new LinkedHashSet<String>();
		for (String code : ref.normalizedAtcCodes()) {
			if (code.length() >= ATC_CLASS_PREFIX_LENGTH) {
				out.add(code.substring(0, ATC_CLASS_PREFIX_LENGTH));
			}
		}
		return out;
	}

	/** @return the ATC level-4 subgroup {@code other} shares with {@code refClasses}, or null when none. */
	private static String sharedClass(Set<String> refClasses, DrugReference other) {
		for (String code : other.normalizedAtcCodes()) {
			if (code.length() >= ATC_CLASS_PREFIX_LENGTH) {
				String cls = code.substring(0, ATC_CLASS_PREFIX_LENGTH);
				if (refClasses.contains(cls)) {
					return cls;
				}
			}
		}
		return null;
	}

	/** @return the display name of the reference drug carrying {@code upperCode}, or the bare code
	 *          when the active order's substance is not present in the loaded dataset. */
	private String displayNameForAtcCode(String upperCode) {
		for (DrugReference ref : drugReferenceService.getAll()) {
			if (ref.normalizedAtcCodes().contains(upperCode)) {
				return ref.getName();
			}
		}
		return upperCode;
	}

	private void addOverdose(List<SafetyWarning> warnings, DrugReference ref,
			PatientClinicalContext context, String lowerAnswer, List<DrugReference> allEntries) {
		Integer age = context != null ? context.getAgeYears() : null;
		DrugReference.AgeBand band = ref.bandForAge(age);
		if (band == null || band.getMaxDailyDoseMg() <= 0) {
			return;
		}
		Double dailyMg = parseDailyDoseMg(lowerAnswer, ref, allEntries);
		if (dailyMg != null && dailyMg > band.getMaxDailyDoseMg()) {
			warnings.add(new SafetyWarning(SafetyWarning.TYPE_OVERDOSE, ref.getName(),
					"stated dose ~" + DrugReference.formatNumber(dailyMg) + " mg/day exceeds the "
							+ DrugReference.formatNumber(band.getMaxDailyDoseMg()) + " mg/day maximum for ages "
							+ band.getMinYears() + "-" + band.getMaxYears()));
		}
	}

	/**
	 * Parses the largest plausible daily dose (mg) the answer states <em>for {@code ref}</em>.
	 *
	 * <p>Attribution is clause-scoped and alias-anchored so one drug is never charged with another's
	 * dose: the answer is split into clauses, and within each clause that names {@code ref} a
	 * {@code N mg} value counts only when (a) it is not introduced by a limit cue — "maximum", "up
	 * to", … make it a ceiling, not a prescribed dose — and (b) {@code ref}'s alias is the nearest
	 * drug name to it (no other entry's alias sits strictly closer). The frequency is read from the
	 * same clause, so a frequency stated for a different drug in a neighbouring sentence is never
	 * applied. When a dose is found without a frequency, frequency 1 is assumed (conservative — it
	 * cannot over-report a daily total).
	 *
	 * <p>Known limitation (v1): only the literal unit {@code mg} is recognised; doses written in
	 * grams ("1 g"), "mgs", or "milligrams" are not parsed and will not be flagged. That is the
	 * conservative (miss, never false-positive) direction.
	 */
	static Double parseDailyDoseMg(String lowerAnswer, DrugReference ref, List<DrugReference> allEntries) {
		Double best = null;
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
				int freq = frequencyPerDay(clause);
				double daily = perDose * (freq > 0 ? freq : 1);
				if (best == null || daily > best) {
					best = daily;
				}
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

	private static String noteOrToken(String note, String token) {
		return note != null && !note.isEmpty() ? note : token;
	}
}
