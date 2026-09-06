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
 * three. A deterministic, exact comparison, like its three siblings and for the same reason: no
 * model call, no embedding, no cosine floor, no reproduction threshold.
 *
 * <p><b>The failure.</b> Measured live on a RefApp 3.7.1 standalone against the bundled knowledge
 * base, stock global properties, three runs byte-identical. A <em>"Is it safe to start her on
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
 * one ATC token shape. {@link ActiveOrderCitationFidelityCheck} asks whether a chart citation can be
 * the order its sentence names, which on this answer it could. And a reference-group citation skips
 * Tier-2 entailment entirely (demote-only, #106/#122), so nothing graded these sentences either.
 *
 * <p><b>What it compares.</b> Nothing it derives from prose. The rating travels structurally beside
 * the record it belongs to — {@link RecordMapping#getFindingSeverity()}, written once by
 * {@code DrugReferenceInjector} off {@code SafetyWarning.getSeverity()} — and this check asks
 * whether that word appears in the answer at all. Reading the rating back out of the record's own
 * rendered text was refused rather than merely not chosen: a knowledge-base mechanism can contain
 * its own rating word — thousands of the shipped knowledge base's rows do, and ADR Decision 77
 * carries the measurement, its date and what it is a count OF — so a parse would attribute a rating
 * this module never assigned.
 *
 * <p><b>Which ratings it asks about is not this class's decision.</b>
 * {@code DrugSafetyValidator.statableRating} makes it, at the write site, and is canonical for the
 * two that answer null — an UNRATED finding, which has no word at all, and {@code unknown}, which
 * has a word that says nothing. This class never sees them and states no vocabulary of its own; it
 * has no severity literal in it, which is what keeps that decision in one place.
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
 *   <li>it says nothing about a record carrying no rating worth requiring, which is every record
 *       that is not an injected safety finding and every finding {@code statableRating} declines;</li>
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
 * <p><b>Why the bounded scan is written here rather than borrowed.</b> {@code DrugReference}'s
 * bounded-token family is the drug-NAME matcher, and CLAUDE.md's rule for it is that its three
 * shapes are not interchangeable and that a caller must never choose an allowance of its own
 * (#260). Its allowances exist for inflected order names and for prose naming a substance; a rating
 * is a closed vocabulary of English words with no aliases, no diacritics and no inflection to
 * allow. Borrowing that family would be widening one of its shapes to serve a question it was not
 * written for, so the rule this check applies is stated as its own.
 *
 * <p><b>What it cannot see</b>, stated rather than left to be found:
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
 * for publishing the first of these answers — on the reported response every observable field read
 * exactly as a clean answer's would.
 *
 * <p><b>Where it runs.</b> Both answer paths, {@link LlmInferenceService#search} and
 * {@code searchStreaming}, so the endpoint users hit is covered. Not the progressive-reasoning
 * preview, which discards its answer and resolves no citations, and not a cached answer, which was
 * checked when it was produced — the same scoping {@link ClassCodeFidelityCheck} states.
 * &rarr; ADR Decision 77.
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
	 * @return the distinct offending citation indexes in CITATION order — {@code cited}'s own order,
	 *         taken rather than re-derived so that "which records were cited, and in what order" has
	 *         one answer. Empty when the check ran and found none, and null only when the check
	 *         itself failed
	 */
	static List<Integer> reportUnstatedFindingSeverities(Patient patient, String answer,
			List<RecordReference> cited, List<RecordMapping> mappings) {
		Integer patientId = null;
		try {
			// Inside the guard, not above it: reading a detached patient proxy is the one line here
			// that could throw, and the promise this catch makes is structural or it is nothing.
			patientId = patient == null ? null : patient.getPatientId();
			List<Integer> offending = new ArrayList<Integer>();
			if (cited == null || cited.isEmpty() || mappings == null) {
				return offending;
			}
			Map<Integer, RecordMapping> byIndex = new HashMap<Integer, RecordMapping>();
			for (RecordMapping mapping : mappings) {
				byIndex.put(Integer.valueOf(mapping.getIndex()), mapping);
			}
			// Lower-cased ONCE for the whole answer rather than per citation: the ratings are a
			// closed vocabulary, so one fold of the answer serves every comparison below.
			String folded = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
			List<String> reasons = new ArrayList<String>();
			Set<Integer> seen = new LinkedHashSet<Integer>();
			for (RecordReference citation : cited) {
				RecordMapping mapping = byIndex.get(Integer.valueOf(citation.getIndex()));
				if (mapping == null) {
					// Unreachable rather than lenient: the reference list is built FROM the mappings,
					// so a cited index always has one. Said so the guard does not look better
					// defended than it is.
					continue;
				}
				String rating = mapping.getFindingSeverity();
				if (rating == null || statesRating(folded, rating)) {
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

	/**
	 * @return whether {@code foldedAnswer} — the answer, lower-cased — states {@code rating} as a
	 *         word rather than merely containing its letters.
	 *
	 *         <p>The boundary is "not a letter and not a digit on either side", which admits every
	 *         way an answer has been observed to write a rating (a colon after it, parentheses or
	 *         markdown emphasis around it, a hyphen before {@code -rated}) and refuses only a longer
	 *         word it sits inside — {@code majority} being the one that matters, since it is ordinary
	 *         in clinical prose and a substring test would let it silence every Major finding in the
	 *         answer.
	 *
	 *         <p>Deliberately not {@code DrugReference}'s bounded-token family: those are the
	 *         drug-NAME shapes, whose allowances exist for inflected order names and for prose naming
	 *         a substance, and CLAUDE.md's rule for them is that a caller must never choose an
	 *         allowance of its own (#260). A rating has no aliases, no diacritics and no inflection
	 *         to allow, so this rule is stated here as its own rather than by widening one of theirs.
	 */
	private static boolean statesRating(String foldedAnswer, String rating) {
		String needle = rating.toLowerCase(Locale.ROOT);
		if (ChartSearchAiUtils.isBlank(needle)) {
			// Unreachable through the production write site, which never carries a blank rating; the
			// guard is here because an empty needle would otherwise match everything, and a check
			// silenced by a blank is a check that fails open.
			return false;
		}
		for (int at = foldedAnswer.indexOf(needle); at >= 0;
				at = foldedAnswer.indexOf(needle, at + 1)) {
			boolean boundedLeft = at == 0 || !Character.isLetterOrDigit(foldedAnswer.charAt(at - 1));
			int after = at + needle.length();
			boolean boundedRight = after >= foldedAnswer.length()
					|| !Character.isLetterOrDigit(foldedAnswer.charAt(after));
			if (boundedLeft && boundedRight) {
				return true;
			}
		}
		return false;
	}
}
