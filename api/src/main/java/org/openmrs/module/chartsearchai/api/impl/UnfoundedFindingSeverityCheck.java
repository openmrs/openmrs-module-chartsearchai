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
 * {@link RecordMapping#getFindingUnrated()}, written once by the injector: a finding with no rating of its
 * own, whose record says so — that sentence, or a condition-mediated finding's own "this finding has no
 * severity of its own". {@link RecordMapping#getFindingSeverity()} being {@code null} could not serve: it
 * also answers for a rating {@code DrugSafetyValidator.statableRating} declines and for a rating the
 * record does not state, and an answer stating either has attached nothing the finding lacks.
 *
 * <p><b>The unit is the SENTENCE citing the unrated finding</b>, split by
 * {@link ChartSearchAiUtils#SENTENCE_BOUNDARY}, as the issue's owner decided. The sibling's whole-answer
 * unit cannot work here, because "Major" elsewhere in the answer is exactly the false report. A rating
 * word in that sentence is reported only where no finding the sentence cites, the unrated one included,
 * carries that rating ({@code aCitedFindingCarries} says what carrying is for a finding carrying a rating
 * and for one carrying none), which keeps silent a rating quoted from another finding's detail beside it
 * — PR #554's fourth run.
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
 *       rating — the enumeration sentence ADR Decision 76 refuted sentence scoping with. The
 *       exemption is what buys the #554 fourth run's silence, and this is its cost;</li>
 *   <li>a marker placed after its sentence's terminator ("…a Major finding. [354]"), which the
 *       splitter puts in the next sentence: the finding's own sentence is then silent, and the next
 *       one's rating, if any, is attached to it;</li>
 *   <li>a rating the sentence owes to a co-cited finding that carries it but whose record does not state
 *       it — an operator dataset's note that omits the rating — IS reported, that finding's
 *       {@code getFindingSeverity()} being null;</li>
 *   <li>a rating the sentence owes to a co-cited record that is not a finding — a {@code drug_reference}
 *       record lists its interactions with their ratings — IS reported. The owner's decision exempts a
 *       rating another FINDING carries;</li>
 *   <li>a rating word used otherwise — negated ("not Major"), or in ordinary English ("a minor
 *       rash") — is reported.</li>
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
			// Every finding, for the exemption below: the stamp is non-null on a finding and on nothing else.
			Map<Integer, RecordMapping> findings = new HashMap<Integer, RecordMapping>();
			for (RecordMapping mapping : mappings) {
				Boolean findingUnrated = mapping.getFindingUnrated();
				if (findingUnrated != null) {
					findings.put(Integer.valueOf(mapping.getIndex()), mapping);
					if (findingUnrated.booleanValue()) {
						unrated.add(Integer.valueOf(mapping.getIndex()));
					}
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
					// more and no case reddens. It keeps that reading from being asked a question
					// whose answer is "every finding" for a piece that cites none.
					continue;
				}
				Set<Integer> citedHere =
						SafetyFindingCitationExtentCheck.citedFindingIndexes(sentence, cited, mappings);
				for (Integer citation : citedHere) {
					if (!unrated.contains(citation)) {
						continue;
					}
					for (String rating : vocabulary) {
						if (ChartSearchAiUtils.statesWord(sentence, rating)
								&& !aCitedFindingCarries(rating, citedHere, findings)) {
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

	/**
	 * Whether a finding cited in the sentence CARRIES {@code rating} — the exemption the issue's owner
	 * decided, "no other finding cited in the same sentence carries that rating". Asked by the stamp, and
	 * never by whether {@link RecordMapping#getFindingSeverity()} is null, which also answers for a rated
	 * finding.
	 * <ul>
	 *   <li>A finding carrying a rating ({@link RecordMapping#getFindingUnrated()} {@code FALSE}) carries
	 *       that rating and no other — its {@code getFindingSeverity()}, and never its prose, whose
	 *       mechanism can say "moderate inhibitors of CYP450 3A4" on a rule rated Major. Where that field is
	 *       null — a rating {@code statableRating} declines, or one an operator's record does not state — it
	 *       carries no word this check asks about.</li>
	 *   <li>A finding carrying none ({@code TRUE}) carries what its RECORD states: a condition-mediated
	 *       finding's detail states each drug-disease rating ("Metformin is rated Major in Acidosis,
	 *       Lactic"), and an unrated finding's record can carry a word an operator dataset's note put there.
	 *       The unrated finding being judged is asked too.</li>
	 * </ul>
	 * Reading a record can only EXEMPT, the direction this check must fail in. Both halves go through
	 * {@link ChartSearchAiUtils#statesWord}, the one scan, so a field an operator spells {@code major} is
	 * read as the rating {@code Major}.
	 */
	private static boolean aCitedFindingCarries(String rating, Set<Integer> citedHere,
			Map<Integer, RecordMapping> findings) {
		for (Integer cited : citedHere) {
			RecordMapping finding = findings.get(cited);
			if (finding == null) {
				continue;
			}
			String carried = finding.getFindingUnrated().booleanValue() ? finding.getText()
					: finding.getFindingSeverity();
			if (ChartSearchAiUtils.statesWord(carried, rating)) {
				return true;
			}
		}
		return false;
	}
}
