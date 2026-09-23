/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;

/**
 * The module's own statement that the chart records a drug only as an order no longer in force, for
 * an answer that did not say so — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/472">#472</a>, ADR Decision
 * 110.
 *
 * <p><b>The gap this closes.</b> Decision 110's A/B recorded the shipped prompt answering <em>"Her
 * current medications are lamivudine, nevirapine and rifampicin. Any interactions?"</em> with <em>"Yes,
 * there are interactions recorded for these medications."</em>: the refusal gone, the clinician's false
 * premise still confirmed, and the only trace of the referent a chip key no client renders yet. The
 * module holds that fact structurally — {@link SafetyWarning#isAboutAnEndedOrder()} and
 * {@link SafetyWarning#getEndedOrderStopDate()}, written where the validator decided them — so it
 * states it the way ADR Decision 100's {@code FindingPartnerCoverageCheck} states an order the prose
 * left unnamed: appended by the module, with no model asked and no prompt changed.
 *
 * <p><b>What "the answer said so" is</b>: some sentence of the answer
 * ({@code ChartSearchAiUtils.SENTENCE_BOUNDARY}) contains {@link #NO_LONGER_IN_FORCE}, the words the
 * prompt's ended-order branch tells the model to use, ABOUT the chip's drug. An occurrence is about the
 * drug named NEAREST before it, asked clause by clause back from the phrase ({@link #clauseStart}): the
 * first clause that names any drug decides, so a clause naming none ({@code "Nevirapine was prescribed,
 * but its order is no longer in force"}, ADR Decision 47's recorded live wording) is read through, and so
 * is a fragment a comma or a dose range left inside another drug's clause. So <em>"Rifampicin interacts
 * with nevirapine; her isoniazid order is no longer in force."</em> does not state rifampicin's end (issue
 * #482): until then one sentence naming the drug anywhere and carrying the phrase anywhere was read as
 * saying it. Whether a clause names some OTHER drug is {@link DrugSafetyValidator#namesADrug}, over the
 * loaded dataset; where no clause before the phrase names any drug, the sentence rule stands. The
 * drug is asked by {@link DrugSafetyValidator#namesTheEndedOrderDrug} — the prose rule over every row of
 * its substance, so "rifampicin" or "rifampin" names a chip labelled {@code Rifampicin (rifampin)} — and
 * never as a substring of that label, which no answer writes (PR #478, review round 2). The phrase is
 * containment, so a paraphrase ("it was discontinued") reads as unstated and the sentence is appended
 * beside it — the residue runs toward saying it twice rather than toward silence, the direction
 * Decision 100 chose for the same reason. What this reading still gets wrong, in each direction, is ADR
 * Decision 110's first residue.
 */
public final class EndedOrderStatement {

	/** The prompt's words for such an order, after {@code PatientChartSerializer.INACTIVE_ORDER_LABEL}. */
	static final String NO_LONGER_IN_FORCE = "no longer in force";

	/**
	 * Where the clause an occurrence of {@link #NO_LONGER_IN_FORCE} sits in begins, within a sentence
	 * {@code SENTENCE_BOUNDARY} already cut: these characters, and a hyphen written as a dash
	 * ({@link #clauseStart}). It shares no character with {@code ChartSearchAiUtils.SENTENCE_TERMINATORS}
	 * and is not a claim unit — it splits nothing grounding or a fidelity check judges. It only decides
	 * which drug a phrase already inside one sentence is read as being ABOUT, and it can only take a
	 * "stated" reading away, never add one. Wider than {@code ActiveOrderCitationFidelityCheck.clauseBound}'s
	 * comma and semicolon on purpose: there a colon introduces the very marker run being attributed, and
	 * cutting it would lose the claim's citations; here no citation is attributed.
	 */
	static final String CLAUSE_BOUNDARIES = ",;:\u2013\u2014";

	private EndedOrderStatement() {
	}

	/**
	 * The chips about an ended order whose drug {@code answer} does not state as one — one per drug, in
	 * chip order, however many findings are about it.
	 */
	public static List<SafetyWarning> unstatedEndedOrders(String answer, List<SafetyWarning> warnings,
			DrugSafetyValidator validator) {
		List<SafetyWarning> unstated = new ArrayList<SafetyWarning>();
		if (warnings == null || ChartSearchAiUtils.isBlank(answer)) {
			return unstated;
		}
		String[] sentences = ChartSearchAiUtils.SENTENCE_BOUNDARY.split(answer);
		Set<String> seen = new LinkedHashSet<String>();
		for (SafetyWarning warning : warnings) {
			if (!warning.isAboutAnEndedOrder() || ChartSearchAiUtils.isBlank(warning.getDrug())) {
				continue;
			}
			if (seen.add(warning.getDrug().toLowerCase(Locale.ROOT))
					&& !statesItEnded(sentences, warning, validator)) {
				unstated.add(warning);
			}
		}
		return unstated;
	}

	private static boolean statesItEnded(String[] sentences, SafetyWarning warning,
			DrugSafetyValidator validator) {
		for (String sentence : sentences) {
			String lower = sentence.toLowerCase(Locale.ROOT);
			// A clause is a piece of its sentence, so a sentence not naming the drug has no clause that does.
			if (!lower.contains(NO_LONGER_IN_FORCE)
					|| !DrugSafetyValidator.namesTheEndedOrderDrug(lower, warning)) {
				continue;
			}
			for (int at = lower.indexOf(NO_LONGER_IN_FORCE); at >= 0;
					at = lower.indexOf(NO_LONGER_IN_FORCE, at + 1)) {
				if (aboutTheDrug(lower, at, warning, validator)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether the occurrence of {@link #NO_LONGER_IN_FORCE} at {@code at} of {@code sentence}, a sentence
	 * naming the chip's drug, is about that drug: walking back clause by clause, the first that names this
	 * drug says yes and the first that names any other says no; where none before the phrase names a drug,
	 * yes, the sentence rule.
	 */
	private static boolean aboutTheDrug(String sentence, int at, SafetyWarning warning,
			DrugSafetyValidator validator) {
		for (int end = at; end > 0;) {
			int start = clauseStart(sentence, end);
			String clause = sentence.substring(start, end);
			if (DrugSafetyValidator.namesTheEndedOrderDrug(clause, warning)) {
				return true;
			}
			if (validator != null && validator.namesADrug(clause)) {
				return false;
			}
			end = start - 1;
		}
		return true;
	}

	/**
	 * Where the clause holding position {@code at} of {@code sentence} begins — after the last of
	 * {@link #CLAUSE_BOUNDARIES} before it, or after a hyphen standing for a dash: one with whitespace on
	 * both sides ({@code " - "}) or doubled ({@code "--"}). A hyphen inside a word ({@code co-trimoxazole})
	 * is neither, and does not end a clause.
	 */
	private static int clauseStart(String sentence, int at) {
		for (int i = at - 1; i >= 0; i--) {
			char c = sentence.charAt(i);
			if (CLAUSE_BOUNDARIES.indexOf(c) >= 0 || c == '-' && isADash(sentence, i)) {
				return i + 1;
			}
		}
		return 0;
	}

	private static boolean isADash(String sentence, int i) {
		boolean spaced = i > 0 && i + 1 < sentence.length() && Character.isWhitespace(sentence.charAt(i - 1))
				&& Character.isWhitespace(sentence.charAt(i + 1));
		boolean doubled = i > 0 && sentence.charAt(i - 1) == '-' || i + 1 < sentence.length()
				&& sentence.charAt(i + 1) == '-';
		return spaced || doubled;
	}

	/**
	 * The answer with one sentence per drug in {@code unstated} appended, or the answer unchanged where
	 * there is none.
	 *
	 * <p><b>It APPENDS and never replaces</b>, Decision 100's contract: the verdict lead is the model's,
	 * and an answer {@link #unstatedEndedOrders} reads as having said it of every drug is returned byte
	 * for byte — one naming the drug by a name no row of its substance carries, or saying it in other
	 * words, is not, and there the clinician reads it twice. The drug is printed as the chip's label, so for
	 * {@code Rifampicin (rifampin)} the sentence names both. It carries no citation marker — it
	 * offers no evidence the chips do not, and a marker would claim a record this text did not read —
	 * and it states the date only where the chip carries one. It states what the chart RECORDS, never
	 * that the drug may or may not be given: that is the finding's call, and the prompt's.
	 */
	public static String withEndedOrdersStated(String answer, List<SafetyWarning> unstated) {
		if (unstated == null || unstated.isEmpty()) {
			return answer;
		}
		// Through the shared rule and never a terminator of this method's own, as Decision 100's does.
		StringBuilder sb = new StringBuilder(DrugSafetyValidator.endSentence(answer.trim()));
		for (SafetyWarning warning : unstated) {
			sb.append(" The chart records ").append(warning.getDrug()).append(" only as an order ")
					.append(NO_LONGER_IN_FORCE);
			if (warning.getEndedOrderStopDate() != null) {
				sb.append(" (ended ").append(warning.getEndedOrderStopDate()).append(")");
			}
			sb.append(", not as a current medication.");
		}
		return sb.toString();
	}
}
