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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports a safety finding whose RATING the answer states nowhere — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/337">#337</a>, round
 * three. A deterministic, exact comparison: no model call, no embedding, no cosine floor and no
 * reproduction threshold, the last of those being what lets it see the answers Decision 61's check
 * by construction cannot. Stated of THIS check and not as a likeness to its siblings — two
 * successive attempts to word that comparison were each false of one of them.
 *
 * <p><b>The failure.</b> Measured live on a RefApp 3.7.1 standalone against the bundled knowledge
 * base, with the drug-reference layer enabled and {@code chartMode=fullChart}, three runs
 * byte-identical. A <em>"Is it safe to start her on
 * clarithromycin?"</em> answer enumerated five interaction findings in one clause — <em>"…
 * Clarithromycin interacts with active order Methylprednisolone [177] [350], Clarithromycin
 * interacts with active order Budesonide [166] [351], …"</em> — and stated no rating for any of
 * them. Two of the five were Major. The chips beside the answer carried every rating correctly.
 * A clinician reading the prose had no way to rank the five, and <em>"interacts with"</em> was doing
 * the work that <em>"Major — adrenal suppression"</em> was supposed to do.
 *
 * <p><b>Why nothing else can see it.</b> {@link ReferenceProseFidelityCheck} reports a SUBSTITUTION
 * inside a reproduction of at least its own word floor, and this answer reproduces nothing — it
 * enumerates, dropping the mechanism and the rating together. That carve-out is the right call for
 * that check, and its javadoc already names the residue ("a hazard dropped by stopping early"); this
 * is the half of that residue a rating makes deterministic. {@link ClassCodeFidelityCheck} compares
 * one ATC token shape. {@link ActiveOrderCitationFidelityCheck} asks a different question again —
 * whether a chart citation can be the order its sentence names. And a reference-group citation skips
 * Tier-2 entailment entirely (demote-only, #106/#122), so nothing graded these sentences either.
 *
 * <p><b>What it compares.</b> Nothing it derives from prose. The rating travels structurally beside
 * the record it belongs to — {@link RecordMapping#getFindingSeverity()}, written once by
 * {@code DrugReferenceInjector} off {@code SafetyWarning.getSeverity()} — and this check asks
 * whether that word appears in the answer at all. Reading the rating back out of the record's own
 * rendered text was refused rather than merely not chosen: a knowledge-base mechanism can contain
 * its own rating word, so a parse would attribute a rating this module never assigned. ADR
 * Decision 78 carries the measurement of how often, its date and what it is a count OF.
 *
 * <p><b>Which ratings it asks about is not this class's decision.</b>
 * {@code DrugReferenceInjector.ratingThisRecordStates} makes it, at the write site, and is canonical
 * for every case that answers null — the ratings {@code DrugSafetyValidator.statableRating} declines,
 * and a record that does not state its own rating. This class sees none of them: what reaches it is
 * one nullable field. It states no vocabulary of its own and has no severity literal in it, which is
 * what keeps that decision in one place.
 *
 * <p><b>It asks of the WHOLE answer, and that is the conservative choice rather than the thorough
 * one.</b> The unit could have been the sentence citing the finding, or the citation run its sibling
 * uses. Both would report an answer that states the rating somewhere else — <em>"There are two Major
 * interactions. Clarithromycin interacts with active order Methylprednisolone [350] …"</em> — which
 * is correct prose, and a check that cries wolf is worse than no check. The whole answer is also the
 * unit the issue's own reproduction was measured in.
 *
 * <p><b>Conservative by construction</b>, for the reason its siblings are:
 * <ul>
 *   <li>it says nothing about a record carrying no rating for it to ask after — every record that
 *       is not an injected safety finding, every finding {@code statableRating} declines, and every
 *       finding whose own record does not state its rating. It cannot tell those apart, by
 *       construction: what reaches it is one nullable field;</li>
 *   <li>a BLANK or absent answer is silent. That arm is reachable rather than defensive —
 *       {@code LlmInferenceService.extractCitedReferences} resolves the structured citations array
 *       for a blank answer deliberately — and a degenerate output is not a fidelity defect. Every
 *       sibling is silent there too, by an empty class-code set, a word floor or a phrase gate;</li>
 *   <li>it considers only the citations the answer's own resolution admitted
 *       ({@code LlmInferenceService.extractCitedReferences}), so a bracketed clinical value the
 *       chart has no record for is not a citation here either — CLAUDE.md's inline-citation rule
 *       states it, and taking that accessor's output rather than re-deriving "which records were
 *       cited" is what keeps that one answer;</li>
 *   <li>the rating is matched on a WORD boundary and case-insensitively, so <em>"Major"</em>,
 *       <em>"major"</em>, <em>"**Major**"</em>, <em>"(Major)"</em> and <em>"Major-rated"</em> all
 *       satisfy it while <em>"majority"</em> does not. The boundary fails toward silence in the one
 *       direction that matters: any occurrence at all, for any reason, silences the report;</li>
 *   <li>it reports the CITATION and the rating word, never a word of the answer or of the record —
 *       both carry patient data, the same discipline {@link ClassCodeFidelityCheck} states. The
 *       rating is safe to log because it is the module's own closed vocabulary and identifies which
 *       finding was degraded without quoting anything about the patient;</li>
 *   <li>it never rewrites the answer. Editing a clinician-facing sentence is a larger decision than
 *       this check is licensed to make, and since #201 a reference-group citation publishes no
 *       verdict to carry one.</li>
 * </ul>
 *
 * <p><b>The scan is {@link ChartSearchAiUtils#statesWord}, and it is shared for a reason.</b> The
 * other caller is {@code DrugReferenceInjector}, asking whether the RECORD states the rating before
 * it carries one at all. Its callers must share one rule: a rating the record states one way and the answer
 * states the other would otherwise be reported as dropped. That method's javadoc carries the
 * boundary and why it is not a member of {@code DrugReference}'s drug-name family.
 *
 * <p><b>What it cannot see</b>, stated rather than left to be found — and the write site's own
 * javadoc names one more, a record whose mechanism happens to contain the rating word:
 * <ul>
 *   <li>an answer that states one Major finding's rating and drops a second Major finding's. The
 *       whole-answer unit is what buys the silence above and this is what it costs;</li>
 *   <li>a rating word that reaches the answer for the wrong reason — inside a mechanism the answer
 *       reproduced ("major bleeding"), or stated for a different finding. Fail-toward-silence, and
 *       the direction this check must fail in;</li>
 *   <li>a rating rendered by synonym: an answer calling a Major interaction "serious" IS reported,
 *       because the module's own rating word is what the prompt asked the answer to carry. That is
 *       a decision rather than an oversight — the DDInter scale is what the chips are ordered by,
 *       and a synonym is the model's judgement substituted for the source's rating;</li>
 *   <li>whether the rating is attached to the RIGHT finding. It asks whether the word is in the
 *       answer, never where.</li>
 * </ul>
 *
 * <p><b>It reports and it publishes.</b> The WARN is the maintainer's channel;
 * {@code ChartAnswer.getUnstatedFindingSeverities()} is the clinician's, through the
 * {@code unstatedFindingSeverities} response key, and it exists for the reason ADR Decision 74 gave
 * for publishing the first of these answers: when the response was measured, nothing observable on
 * it distinguished a degraded rendering from a faithful one. Stated that way rather than as "every
 * observable field read as a clean answer's would", which is no longer true of that response — the
 * issue's own comment notes it also carries issue #377, whose key flags three of its chart
 * citations. Nothing flagged, or flags, the dropped rating.
 *
 * <p><b>Where it runs.</b> Both answer paths, {@link LlmInferenceService#search} and
 * {@code searchStreaming}, so the endpoint users hit is covered. Not the progressive-reasoning
 * preview, which discards its answer and resolves no citations, and not a cached answer, which was
 * checked when it was produced — the same scoping {@link ClassCodeFidelityCheck} states.
 * &rarr; ADR Decision 78.
 */
final class SafetyFindingSeverityFidelityCheck {

	private static final Logger log = LoggerFactory.getLogger(SafetyFindingSeverityFidelityCheck.class);

	private SafetyFindingSeverityFidelityCheck() {
	}

	/**
	 * Reports, at WARN, every cited safety finding whose rating {@code answer} states nowhere, and
	 * returns them for publication.
	 *
	 * @param patient whose answer it is — logged so a line is attributable under concurrent requests
	 * @param answer the answer prose, unchanged by this method
	 * @param cited the references the answer cites, as resolved by
	 *            {@link LlmInferenceService#extractCitedReferences}
	 * @param mappings the chart's records, cited or not — the carrier of each cited record's rating
	 * @return the offending citation indexes in CITATION order — {@code cited}'s own order, taken
	 *         rather than re-derived so that "which records were cited, and in what order" has one
	 *         answer, and pinned by
	 *         {@code SafetyFindingSeverityFidelityTest.theStatementIsInTHEANSWERSCitationOrderAndNotSortedByIndex}
	 *         rather than left indistinguishable from ascending index order. Empty when the check
	 *         ran and found none, and null only when the check itself failed.
	 *
	 *         <p>The set the walk accumulates into de-duplicates, and that is belt and braces rather
	 *         than load-bearing: {@code LlmInferenceService.extractCitedReferences} already collects
	 *         indexes into a {@code LinkedHashSet} and emits one reference per index, so
	 *         {@code cited} cannot carry a repeat today. Said so the guard does not look better
	 *         defended than it is — a review pass swapped the set for a list that always adds and the
	 *         whole build stayed green. What the set IS load-bearing for is the order above.
	 */
	static List<Integer> reportUnstatedFindingSeverities(Patient patient, String answer,
			List<RecordReference> cited, List<RecordMapping> mappings) {
		Integer patientId = null;
		try {
			// Inside the guard, not above it: reading a detached patient proxy is the one line here
			// that could throw, and the promise this catch makes is structural or it is nothing.
			patientId = patient == null ? null : patient.getPatientId();
			List<Integer> offending = new ArrayList<Integer>();
			if (cited == null || cited.isEmpty() || mappings == null
					|| ChartSearchAiUtils.isBlank(answer)) {
				// The blank arm is REACHABLE rather than defensive; the class javadoc's conservatism
				// list says why, and points at extractCitedReferences' own javadoc for the shape.
				return offending;
			}
			// The map holds only the records that carry a rating, and it is the GATE as well as the
			// lookup — on the shipped default `chartsearchai.drugReference.enabled` is false, so the
			// injector never runs, no record carries a rating, and this returns before touching the
			// answer at all. Every sibling check resolves its own cheapest gate first for the same
			// reason; this one used to build a full index over every chart record and fold the whole
			// answer before it could learn it had nothing to do.
			Map<Integer, String> ratings = new HashMap<Integer, String>();
			for (RecordMapping mapping : mappings) {
				if (mapping.getFindingSeverity() != null) {
					ratings.put(Integer.valueOf(mapping.getIndex()), mapping.getFindingSeverity());
				}
			}
			if (ratings.isEmpty()) {
				return offending;
			}
			// Memoised per distinct rating, in a per-call local and never a field (#172 binds this
			// module's memos, and a static utility on a Spring-managed path is no exception). The
			// walks are bounded by the SIZE OF THE VOCABULARY `statableRating` admits rather than by
			// the citation count, so an answer citing two hundred findings asks this a handful of
			// times rather than two hundred — the unbounded repeat of one identical needle that the
			// same shape forced ActiveOrderCitationFidelityCheck to bound with a Matcher region.
			//
			// The key is LOWER-CASED, and that is what makes the bound the vocabulary's size rather
			// than a hope.
			// `statableRating` hands on the dataset's own spelling trimmed, not canonicalised —
			// `severityRank` lower-cases to RECOGNISE a rating and nothing lower-cases what is
			// returned — so before this key was folded, an operator file writing `Major` and `major`
			// gave two keys for one rating and paid two walks of the answer for the same question.
			// `statesWord` is case-insensitive, so that was never a wrong answer, only a wrong bound.
			Map<String, Boolean> stated = new HashMap<String, Boolean>();
			List<String> reasons = new ArrayList<String>();
			Set<Integer> seen = new LinkedHashSet<Integer>();
			for (RecordReference citation : cited) {
				String rating = ratings.get(Integer.valueOf(citation.getIndex()));
				if (rating == null) {
					// Either the cited record is not a finding, or it is one carrying no rating worth
					// requiring — `ratingThisRecordStates` is canonical for which those are, and this
					// check deliberately cannot tell those cases apart.
					continue;
				}
				String key = rating.toLowerCase(Locale.ROOT);
				Boolean answerStatesIt = stated.get(key);
				if (answerStatesIt == null) {
					answerStatesIt = Boolean.valueOf(ChartSearchAiUtils.statesWord(answer, rating));
					stated.put(key, answerStatesIt);
				}
				if (answerStatesIt.booleanValue()) {
					continue;
				}
				if (seen.add(Integer.valueOf(citation.getIndex()))) {
					reasons.add("[" + citation.getIndex() + "] " + rating);
				}
			}
			offending.addAll(seen);
			if (!offending.isEmpty()) {
				// The rating travels inside the reason strings rather than as a bare index list, so
				// each citation reads beside the word that went missing: a maintainer triaging this
				// needs to know whether a Major rating was dropped or a Minor one. Neither the answer
				// nor any record text is logged — they carry patient data, and the citation with the
				// patient identifies the claim. The rating is the module's own closed vocabulary and
				// says nothing about this patient.
				log.warn("Answer for patient={} states no rating for cited finding(s) {}. The answer "
						+ "prose is left unchanged (issue #337).", patientId, reasons);
			}
			return offending;
		}
		catch (RuntimeException e) {
			// A diagnostic must never break a clinical answer — the same promise its siblings make,
			// made structurally rather than by inspection, and loudly, so the failure of the check is
			// not itself silent. Null rather than an empty list: the caller publishes this, and "no
			// measurement" is not "none".
			log.warn("Finding-severity check failed for patient={}; the answer is unaffected: {}",
					patientId, e.toString());
			return null;
		}
	}
}
