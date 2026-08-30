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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports an answer that REPRODUCES a stretch of a cited reference record and then, inside the
 * sentence it was copying, states different words (issue #337) — the prose counterpart of
 * {@link ClassCodeFidelityCheck}, and the mechanism the README claimed already existed.
 *
 * <p><b>The failure.</b> Measured live on the 3.7.1 standalone. The chip, straight from the
 * knowledge base, said <em>"Coadministration of local anesthetics with other oxidizing agents that
 * can also <b>induce methemoglobinemia</b> such as antimalarials…"</em>; the answer, citing that
 * finding, said <em>"…oxidizing agents that can also <b>increase the risk</b>"</em>. The risk of
 * what is no longer in the sentence, and methaemoglobinaemia — the entire content of that Major
 * rating, and an oxygen-saturation problem to watch for — reaches the clinician nowhere. A second
 * capture deleted <em>"neuromuscular blockers, aminoglycoside antibiotics,"</em> from a botulinum
 * toxin warning whose partner, neomycin, IS an aminoglycoside: what survived was a generic
 * statement that no longer connects to the drug named in the same sentence.
 *
 * <p><b>Why nothing else can see it.</b> A reference-group citation skips Tier-2 entailment
 * entirely (demote-only, #106/#122) and Tier-1 cosine barely moves when one phrase inside a long
 * recitation changes. {@link ClassCodeFidelityCheck} compares one token shape and says nothing
 * about prose. The {@code safetyWarnings} chips carry the true text but are a parallel list that
 * nothing reconciles against the answer — which is what this repository asserted they did, in every
 * place the claim was written; ADR Decision 59 names them and says how they were found.
 *
 * <p><b>What it does and does not do.</b> It reports; it never rewrites, and nothing about it
 * reaches the wire. Editing a clinician-facing sentence is a larger decision than this check is
 * licensed to make, and since #201 a reference-group citation publishes no verdict to carry one.
 * It does not stop the paraphrase either — that is the ticket's option 1, a prompt or assembly
 * change, and this check is the instrument that would measure whether such a change worked.
 *
 * <p><b>Conservative by construction</b>, because a check that cries wolf is worse than no check:
 * <ul>
 *   <li>it says nothing unless the answer REPRODUCES at least {@link #MIN_REPRODUCED_WORDS}
 *       consecutive words of a record it cites. With nothing reproduced there is no reproduction to
 *       be unfaithful to, and this is what keeps ordinary prose out: an answer that summarises in
 *       its own words shares only short phrases with its sources;</li>
 *   <li>it reports a SUBSTITUTION and never a truncation. Where the answer stops reproducing and
 *       ends its sentence, it has stated nothing the record does not, and reporting it would fire
 *       on every answer that quotes one clause of a 150-word mechanism — which is most of them.
 *       That under-reports the ticket's weaker cousin, a hazard dropped by stopping early, and it
 *       is the safe direction for a check whose failure mode is being ignored;</li>
 *   <li>it treats a record SENTENCE reproduced whole as faithful however the answer goes on. That
 *       exit is also what keeps the clauses {@code DrugReferenceInjector.renderFinding} appends out
 *       of the comparison at the seam: the detail is passed through
 *       {@code DrugSafetyValidator.endSentence} whenever a clause follows it, so
 *       {@code " This finding is a reason to withhold it."} always opens a new record sentence.
 *       It covers the SEAM and not the clause's interior — a reproduction that runs from the
 *       detail into the clause and diverges inside it is reported, and so is one inside the
 *       thirteen words of {@code STRENGTH_CAUTION}. That residue is accepted rather than excluded:
 *       excluding it means teaching this check where a finding's own prose ends, which is knowledge
 *       that belongs to {@code renderFinding} and would be a second copy of it here;</li>
 *   <li><b>support is POOLED across the cited records</b>, exactly as {@link ClassCodeFidelityCheck}
 *       pools it for a class code, and here that is what keeps a faithful answer quiet rather than
 *       merely what makes the check generous. One mechanism string is rendered into TWO records
 *       that are routinely cited together — the {@code safety_finding}'s detail and the
 *       {@code drug_reference}'s interaction item — and they lay it out differently: the finding
 *       ends the mechanism's sentence, while in the reference record it is one {@code "; "}-joined
 *       item of a list, so the next partner's name follows with no sentence boundary before it.
 *       Read against that record alone, an answer that reproduced the whole mechanism and welded a
 *       clause on looks exactly like a substitution. So a continuation ANY cited record explains —
 *       by ending, by opening a new sentence, or by simply continuing the same way — is not a
 *       divergence, whatever a second record's layout makes of it. Only the cited REFERENCE records
 *       are asked, and widening that to the chart records buys nothing: an explainer has to carry
 *       the reproduced run itself, and a chart record does not carry twelve consecutive words of a
 *       knowledge-base mechanism;</li>
 *   <li>it compares WORDS — runs of letters and digits, lower-cased — so punctuation cannot make
 *       two identical words differ. The sentence-boundary bit each word carries is deliberately NOT
 *       part of that equality: were it, a record writing {@code "(e.g. chloroquine"} against an
 *       answer writing {@code "(e.g., chloroquine"} would break the reproduction and then report
 *       two IDENTICAL words as a substitution, turning a boundary misreading into a false
 *       accusation instead of silence.</li>
 * </ul>
 *
 * <p><b>Which way a boundary misreading fails.</b> Both bit-driven conditions are SILENCING, so a
 * sentence boundary read where none was meant — an abbreviation dot before a space — can only add
 * silence: on the record side it takes the "reproduced a sentence and moved on" exit, on the answer
 * side the "stopped copying" one. The check loses recall, never precision.
 *
 * <p><b>What the WARN carries, and what it deliberately does not.</b> The patient, the cited
 * record's index, how many words were reproduced, and the word offset in the record at which the
 * reproduction stopped agreeing. <b>No prose from either side.</b> A first draft logged a window of
 * the record's own continuation on the reasoning that a reference record carries no patient data;
 * that reasoning is false. A {@code safety_finding}'s detail embeds this patient's own prescription
 * string ({@code "… interacts with active order Warfarin 5mg"}) and a {@code drug_reference} record
 * carries {@code DrugReferenceInjector}'s three reading sections, whose clauses name this patient's
 * recorded allergens and conditions — so a window could print a named allergy beside the patient
 * id. Offsets identify the divergence without quoting it, which is the discipline
 * {@link ClassCodeFidelityCheck} already states ("Neither the answer nor the record text is logged:
 * they carry patient data").
 *
 * <p><b>Where it runs.</b> Both answer paths — {@link LlmInferenceService#search} and
 * {@code searchStreaming} — beside the class-code check and for the same reasons it states there:
 * not the progressive-reasoning preview, which resolves no citations, not a cached answer, which
 * was checked when it was produced, and not the model's reasoning, which cites nothing.
 *
 * <p><b>One regression this file cannot see.</b> Its gate asks
 * {@link ChartSearchAiUtils#referenceGroup} rather than testing {@code resourceType} against a type
 * name, per CLAUDE.md's rule for a question that is not about grading — so a reference type added
 * later is covered without this class changing. Hardcoding the pair here would leave the whole
 * suite green until a THIRD reference-group type existed; that is measured elsewhere in this
 * module and it holds of this new site too. Nothing here guards it, and ADR Decision 59 says so
 * rather than leaving the next reader to infer coverage.
 */
final class ReferenceProseFidelityCheck {

	private static final Logger log = LoggerFactory.getLogger(ReferenceProseFidelityCheck.class);

	/**
	 * How many consecutive words an answer must share with a cited record before this check will
	 * call it a reproduction of that record. The two live captures behind issue #337 reproduce runs
	 * of eighteen and nineteen words before diverging.
	 *
	 * <p><b>Twelve rather than eight, and the suite is what decided it.</b> At eight, this check
	 * reports the module's OWN generated headline being restated: the answer <em>"Ciprofloxacin is in
	 * the same ATC class (J01MA) as the patient's active order"</em> against a record saying
	 * <em>"…as active order levofloxacin…"</em> reproduces nine words and then substitutes, which is
	 * structurally the same shape as issue #337's second capture. But the sentence it diverges from
	 * is one this module composed about this patient and the prompt asks the model to carry, not
	 * knowledge-base prose whose wording is the evidence — and the model restating it in its own
	 * words is what the prompt asked for. Two cases in {@code ClassCodeFidelityTest} fail on a floor
	 * of nine, and they are what pins this number; a case here would only be a third copy of that
	 * arrangement.
	 *
	 * <p>It is a floor on EVIDENCE OF COPYING and not a defence against the false alarm that
	 * matters. That one is legitimate partial quotation — textually identical to the second capture,
	 * which IS an elision — and no threshold separates them; what separates them are the truncation
	 * and record-sentence exits above and the pooling beside them. What the floor gives up is a
	 * substitution inside a reproduction shorter than twelve words, wherever it sits.
	 */
	private static final int MIN_REPRODUCED_WORDS = 12;

	private ReferenceProseFidelityCheck() {
	}

	/**
	 * Reports, at WARN, every place where {@code answer} reproduces a cited reference-group record
	 * and then states different words inside the sentence it was reproducing.
	 *
	 * @param patient whose answer it is — logged so a line is attributable under concurrent requests
	 * @param answer the answer prose, unchanged by this method
	 * @param cited the references the answer cites, as resolved by
	 *            {@link LlmInferenceService#extractCitedReferences} — taking that accessor's own
	 *            output rather than re-deriving "which records were cited" from the prose, for the
	 *            reason {@link ClassCodeFidelityCheck} takes it
	 * @param mappings the chart's records, the carrier of the cited records' type and text
	 */
	static void reportUnfaithfulReferenceProse(Patient patient, String answer,
			List<RecordReference> cited, List<RecordMapping> mappings) {
		Integer patientId = null;
		try {
			// Inside the guard, not above it: reading a detached patient proxy is the one line here
			// that could throw, and the promise this catch makes is structural or it is nothing.
			patientId = patient == null ? null : patient.getPatientId();
			Words answerWords = Words.of(answer == null ? ""
					: ChartSearchAiUtils.INLINE_CITATION.matcher(answer).replaceAll(" "));
			// Replaced with a space rather than removed: a marker sits between words, and deleting
			// it would weld its neighbours into one token that matches nothing.
			if (answerWords.size() >= MIN_REPRODUCED_WORDS && cited != null) {
				Map<Integer, RecordMapping> byIndex = new HashMap<Integer, RecordMapping>();
				if (mappings != null) {
					for (RecordMapping mapping : mappings) {
						byIndex.put(Integer.valueOf(mapping.getIndex()), mapping);
					}
				}
				Reproductions found = new Reproductions();
				for (RecordReference reference : cited) {
					RecordMapping mapping = byIndex.get(Integer.valueOf(reference.getIndex()));
					if (mapping == null || !isModuleSuppliedReferenceProse(mapping.getResourceType())) {
						continue;
					}
					String text = mapping.getText();
					if (ChartSearchAiUtils.isBlank(text)) {
						// A record we could not read is one we cannot say the answer diverged from.
						continue;
					}
					examine(answerWords, Words.of(text), reference.getIndex(), found);
				}
				if (found.any()) {
					for (Divergence divergence : found.unexplained()) {
						// Both halves are needed to reconstruct it: which record's prose was
						// degraded, and where in it the answer stopped agreeing. Neither the answer
						// nor the record text is logged — see the class javadoc.
						log.warn("Answer for patient={} reproduces {} words of cited record [{}] and "
								+ "then states different words inside the sentence it was copying "
								+ "(the record's own text continues at its word {}). The answer prose "
								+ "is left unchanged (issue #337).", patientId,
								Integer.valueOf(divergence.reproducedWords),
								Integer.valueOf(divergence.recordIndex),
								Integer.valueOf(divergence.recordWordOffset));
					}
					return;
				}
			}
			log.debug("Reference-prose check skipped for patient={}: the answer reproduces no cited "
					+ "reference record", patientId);
		}
		catch (RuntimeException e) {
			// A diagnostic must never break a clinical answer. Nothing here does I/O and every line
			// is traceably total, so this is the same promise CitationGroundingVerifier makes in its
			// javadoc, made structurally rather than by inspection — and loudly, so the failure of
			// the check is not itself silent. No test reaches it: every reference-group mapping's
			// text is read earlier in the same method by ChartSearchAiUtils.referenceSlice, which
			// has no guard of its own, so a record that throws on read never gets this far.
			log.warn("Reference-prose check failed for patient={}; the answer is unaffected: {}",
					patientId, e.toString());
		}
	}

	/**
	 * @return whether a record of this type is this module's own reference material — the classifier
	 *         and never a type name, so a reference type added later is covered without this class
	 *         changing (CLAUDE.md: for a question that is not about grading, ask
	 *         {@code referenceGroup} directly rather than borrowing the grounding view of it).
	 *
	 *         <p>Named for what it asks rather than after {@code ChartSearchAiUtils}' own private
	 *         {@code isReferenceMaterial}, which it deliberately does NOT reach: that one is the
	 *         shared body under {@code isGroundingDemoteOnly} and {@code referenceSlice}, and
	 *         borrowing a named view of the classification is what couples a caller to what that view
	 *         is for.
	 */
	private static boolean isModuleSuppliedReferenceProse(String resourceType) {
		return ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE.equals(
				ChartSearchAiUtils.referenceGroup(resourceType));
	}

	/**
	 * Walks one record against the answer and records, per answer position, what each maximal
	 * reproduction ending there turned out to be.
	 *
	 * <p>Every maximal run is its own candidate, not just the longest one in the record: an answer
	 * that substitutes inside a short run and then reproduces a long passage faithfully is exactly
	 * the shape of the ticket's second capture, and a longest-run reading would look at the faithful
	 * passage, find it innocent, and report nothing.
	 */
	private static void examine(Words answer, Words record, int recordIndex, Reproductions found) {
		int n = answer.size();
		int m = record.size();
		int[] previous = new int[m + 1];
		int[] current = new int[m + 1];
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < m; j++) {
				current[j + 1] = answer.word(i).equals(record.word(j)) ? previous[j] + 1 : 0;
				int length = current[j + 1];
				if (length < MIN_REPRODUCED_WORDS) {
					continue;
				}
				boolean extendsRight = i + 1 < n && j + 1 < m
						&& answer.word(i + 1).equals(record.word(j + 1));
				if (extendsRight) {
					continue;
				}
				// A maximal reproduction: it ends at answer word i and record word j. Every answer
				// word it covers but its last is one the copy carried through, so a divergence
				// another record reports there is explained by this one continuing past it.
				found.reproduced(i - length + 1, i);
				if (j + 1 >= m || record.startsSentence(j + 1) || i + 1 >= n
						|| answer.startsSentence(i + 1)) {
					// The record ran out, or reached the end of one of its own sentences; or the
					// answer ran out, or ended its own sentence. Nothing was substituted.
					found.explained(i);
				}
				else {
					found.diverged(i, new Divergence(recordIndex, length, j + 1));
				}
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
	}

	/** What the reproductions of one answer, across every cited reference record, came to. */
	private static final class Reproductions {

		/** Answer word positions at which some record's reproduction ended innocently. */
		private final Set<Integer> explained = new HashSet<Integer>();

		/** Answer word positions strictly inside some record's reproduction — the copy carried on
		 *  there, so nothing was substituted at them however another record's layout reads. */
		private final Set<Integer> carriedThrough = new HashSet<Integer>();

		private final Map<Integer, Divergence> diverged = new LinkedHashMap<Integer, Divergence>();

		private boolean anyReproduction;

		private void reproduced(int from, int lastInclusive) {
			anyReproduction = true;
			for (int at = from; at < lastInclusive; at++) {
				carriedThrough.add(Integer.valueOf(at));
			}
		}

		private void explained(int at) {
			explained.add(Integer.valueOf(at));
		}

		private void diverged(int at, Divergence divergence) {
			Integer key = Integer.valueOf(at);
			if (!diverged.containsKey(key)) {
				diverged.put(key, divergence);
			}
		}

		private boolean any() {
			return anyReproduction;
		}

		private List<Divergence> unexplained() {
			List<Divergence> reportable = new ArrayList<Divergence>();
			for (Map.Entry<Integer, Divergence> entry : diverged.entrySet()) {
				if (!explained.contains(entry.getKey()) && !carriedThrough.contains(entry.getKey())) {
					reportable.add(entry.getValue());
				}
			}
			return reportable;
		}
	}

	/** One reported divergence, as much of it as may be logged. */
	private static final class Divergence {

		private final int recordIndex;

		private final int reproducedWords;

		private final int recordWordOffset;

		private Divergence(int recordIndex, int reproducedWords, int recordWordOffset) {
			this.recordIndex = recordIndex;
			this.reproducedWords = reproducedWords;
			this.recordWordOffset = recordWordOffset;
		}
	}

	/**
	 * A text as the words this check compares — runs of letters and digits, lower-cased — each
	 * carrying whether a sentence boundary stands between it and the word before it.
	 *
	 * <p>The bit is read by {@link ChartSearchAiUtils#sentenceBoundaryBetween}, the one spelling of
	 * that rule in this module, which {@link CitationGroundingVerifier} splits its own units on. It
	 * is deliberately not part of {@link #word} equality; the class javadoc says why.
	 */
	private static final class Words {

		private final List<String> words;

		private final List<Boolean> startsSentence;

		private Words(List<String> words, List<Boolean> startsSentence) {
			this.words = words;
			this.startsSentence = startsSentence;
		}

		private static Words of(String text) {
			List<String> words = new ArrayList<String>();
			List<Boolean> boundaries = new ArrayList<Boolean>();
			int at = 0;
			int gapFrom = 0;
			int length = text.length();
			while (at < length) {
				if (!Character.isLetterOrDigit(text.charAt(at))) {
					at++;
					continue;
				}
				int start = at;
				while (at < length && Character.isLetterOrDigit(text.charAt(at))) {
					at++;
				}
				words.add(text.substring(start, at).toLowerCase());
				// The gap since the previous word — for the first word, the text before it, which no
				// caller reads: the two conditions above ask this of a word that follows one.
				boundaries.add(Boolean.valueOf(
						ChartSearchAiUtils.sentenceBoundaryBetween(text.substring(gapFrom, start))));
				gapFrom = at;
			}
			return new Words(words, boundaries);
		}

		private int size() {
			return words.size();
		}

		private String word(int at) {
			return words.get(at);
		}

		private boolean startsSentence(int at) {
			return startsSentence.get(at).booleanValue();
		}
	}

}
