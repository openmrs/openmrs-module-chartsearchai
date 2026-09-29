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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.ChartSearchService.UnfoundedFindingSeverity;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports a rating the answer attaches to a cited safety finding that carries NONE — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/560">#560</a>, ADR
 * Decision 126. {@link SafetyFindingSeverityFidelityCheck}'s question asked in the opposite direction:
 * that one asks whether a finding's rating survives into the answer, and by construction says nothing
 * about a record with no rating. A deterministic comparison, no model call.
 *
 * <p><b>The failure.</b> Sarah Taylor's chart carries {@code Prednisone Co 5mg} and recorded allergies
 * to dexamethasone and hydrocortisone. Her dexamethasone cross-reactivity finding has no rating, and its
 * record says <em>"No severity is rated for this finding."</em> ({@code DrugReferenceInjector}'s
 * {@code FINDING_NO_SEVERITY}, ADR Decision 123). Answers still called it <em>"a Major finding"</em>, on
 * {@code main} and on PR #554, and the issue records that every wording lever tried on this family
 * failed or moved the fault.
 *
 * <p><b>Which findings carry no rating is not this class's decision.</b> It reads
 * {@link RecordMapping#getFindingUnrated()}, written once by the injector off the same predicate that
 * appends that sentence. {@link RecordMapping#getFindingSeverity()} being {@code null} could not serve:
 * it also answers for a rating {@code DrugSafetyValidator.statableRating} declines and for a rating the
 * record does not state, and an answer stating either has attached nothing the finding lacks.
 *
 * <p><b>The unit is the SENTENCE citing the unrated finding</b>, split by
 * {@link ChartSearchAiUtils#SENTENCE_BOUNDARY}, as the issue's owner decided. The sibling's whole-answer
 * unit cannot work here, because "Major" elsewhere in the answer is exactly the false report. A rating
 * word in that sentence is reported only where no OTHER finding the sentence cites carries that rating
 * ({@link RecordMapping#getFindingSeverity()}), which keeps silent a rating quoted from another finding's
 * detail beside it — PR #554's fourth run.
 *
 * <p><b>Which findings a sentence cites is {@link SafetyFindingCitationExtentCheck#citedFindingIndexes}</b>,
 * asked of the sentence: the ONE reading of that question (issue #409), with the #305 filter and the
 * resolution's admission inside it, so a bracketed clinical value or a citation the module attached is
 * never one here.
 *
 * <p><b>The vocabulary is {@link DrugSafetyValidator#statableRatings()}</b>, the ratings
 * {@code statableRating} states; this class spells no severity literal. The scan is
 * {@link ChartSearchAiUtils#statesWord}, the word-boundary, case-insensitive scan the sibling and the
 * injector share, so "majority" is not "Major" and "**major**" is.
 *
 * <p><b>What it cannot see, or reports wrongly</b>, stated rather than left to be found:
 * <ul>
 *   <li>"Unknown severity" attached to an unrated finding — ADR Decision 123 measured that shape too.
 *       {@code statableRating} declines {@code unknown}, and reading it would report correct prose such
 *       as "its severity is unknown";</li>
 *   <li>a rating attached to the unrated finding in a sentence that also cites a finding carrying that
 *       rating — the enumeration sentence ADR Decision 76 refuted sentence scoping with. The exemption
 *       is what buys the #554 fourth run's silence, and this is its cost;</li>
 *   <li>a marker placed after its sentence's terminator ("…a Major finding. [354]"), which the
 *       splitter puts in the next sentence: the finding's own sentence is then silent, and the next
 *       one's rating, if any, is attached to it;</li>
 *   <li>a rating the sentence owes to a co-cited record that is not a finding — a {@code drug_reference}
 *       record lists its interactions with their ratings — IS reported. The owner's decision exempts a
 *       rating another FINDING carries;</li>
 *   <li>a rating word used otherwise — negated ("not Major"), or in ordinary English ("a minor
 *       rash") — is reported, and so is one an operator dataset's note put inside the unrated record's
 *       own text.</li>
 * </ul>
 *
 * <p>It reports the CITATION and the rating word, never a word of the answer or of a record — both
 * carry patient data; the rating is the module's closed vocabulary. It never rewrites the answer: a
 * rewrite would be the module editing what the model said.
 *
 * <p><b>Where it runs.</b> Both answer paths, {@link LlmInferenceService#search} and
 * {@code searchStreaming} — on the latter after the ungrounded handoff, so the early {@code done} states
 * {@code null}, as the sibling's does. Not a module-composed answer, which no model wrote.
 */
final class UnfoundedFindingSeverityCheck {

	private static final Logger log = LoggerFactory.getLogger(UnfoundedFindingSeverityCheck.class);

	private UnfoundedFindingSeverityCheck() {
	}

	/**
	 * Reports, at WARN, every rating {@code answer} attaches to a cited finding that carries none, and
	 * returns them for publication.
	 *
	 * @param patient whose answer it is — logged so a line is attributable under concurrent requests
	 * @param answer the answer prose, unchanged by this method
	 * @param cited the references the answer cites, as {@link LlmInferenceService#extractCitedReferences}
	 *            resolved them — narrowed, per sentence, by
	 *            {@link SafetyFindingCitationExtentCheck#citedFindingIndexes}
	 * @param mappings the chart's records — the carrier of each finding's stamp and rating
	 * @return one entry per distinct (citation, rating) pair, in sentence order and, within one
	 *         sentence, in citation then vocabulary order; empty where the check ran and found none, and
	 *         null only where the check itself failed
	 */
	static List<UnfoundedFindingSeverity> reportUnfoundedFindingSeverities(Patient patient, String answer,
			List<RecordReference> cited, List<RecordMapping> mappings) {
		Integer patientId = null;
		try {
			patientId = patient == null ? null : patient.getPatientId();
			List<UnfoundedFindingSeverity> offending = new ArrayList<UnfoundedFindingSeverity>();
			if (cited == null || cited.isEmpty() || mappings == null || ChartSearchAiUtils.isBlank(answer)) {
				return offending;
			}
			// The GATE as well as the lookup: on the shipped default no finding is injected, and a chart
			// carrying none that is unrated returns here before the answer is read.
			Set<Integer> unrated = new HashSet<Integer>();
			Map<Integer, String> ratings = new HashMap<Integer, String>();
			for (RecordMapping mapping : mappings) {
				if (Boolean.TRUE.equals(mapping.getFindingUnrated())) {
					unrated.add(Integer.valueOf(mapping.getIndex()));
				}
				else if (mapping.getFindingSeverity() != null) {
					ratings.put(Integer.valueOf(mapping.getIndex()),
							mapping.getFindingSeverity().toLowerCase(Locale.ROOT));
				}
			}
			if (unrated.isEmpty()) {
				return offending;
			}
			List<String> vocabulary = DrugSafetyValidator.statableRatings();
			Set<UnfoundedFindingSeverity> seen = new LinkedHashSet<UnfoundedFindingSeverity>();
			for (String sentence : ChartSearchAiUtils.SENTENCE_BOUNDARY.split(answer)) {
				if (ChartSearchAiUtils.isBlank(sentence)) {
					// Reachable — a line holding only spaces between two newlines splits to one — and the
					// shared reading answers a blank text with the whole resolution. BEHAVIOURALLY NEUTRAL,
					// measured: a blank piece states no rating word, so removing this skip reports nothing
					// more and the suite stays green. It keeps that reading from being asked a question
					// whose answer is "every finding" for a piece that cites none.
					continue;
				}
				Set<Integer> citedHere =
						SafetyFindingCitationExtentCheck.citedFindingIndexes(sentence, cited, mappings);
				for (Integer citation : citedHere) {
					if (!unrated.contains(citation)) {
						continue;
					}
					// An unrated finding is never in `ratings`, so this is the OTHER cited findings' ratings.
					Set<String> carriedByAnother = new HashSet<String>();
					for (Integer other : citedHere) {
						String rating = ratings.get(other);
						if (rating != null) {
							carriedByAnother.add(rating);
						}
					}
					for (String rating : vocabulary) {
						if (!carriedByAnother.contains(rating.toLowerCase(Locale.ROOT))
								&& ChartSearchAiUtils.statesWord(sentence, rating)) {
							seen.add(new UnfoundedFindingSeverity(citation.intValue(), rating));
						}
					}
				}
			}
			offending.addAll(seen);
			if (!offending.isEmpty()) {
				// The citation and the closed-vocabulary rating, never a word of the answer or a record.
				log.warn("Answer for patient={} attaches a rating to cited finding(s) that carry none: {}. "
						+ "The answer prose is left unchanged (issue #560).", patientId, offending);
			}
			return offending;
		}
		catch (RuntimeException e) {
			// A diagnostic must never break a clinical answer, and its own failure must not be silent.
			// Null rather than an empty list: "no measurement" is not "none".
			log.warn("Unfounded-rating check failed for patient={}; the answer is unaffected: {}", patientId,
					e.toString());
			return null;
		}
	}
}
