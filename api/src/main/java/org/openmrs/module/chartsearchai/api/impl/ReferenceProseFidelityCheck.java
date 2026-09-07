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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * place the claim was written; ADR Decision 61 names them and says how they were found.
 *
 * <p><b>What it does and does not do.</b> It reports and it STATES; it never rewrites. Editing a
 * clinician-facing sentence is a larger decision than this check is licensed to make, and since #201
 * a reference-group citation publishes no verdict to carry one. It does not stop the paraphrase
 * either — that is the ticket's option 1, a prompt or assembly change, and this check is the
 * instrument that would measure whether such a change worked.
 *
 * <p><b>Since the second round of issue #337 it also reaches the wire</b>, as the citation indexes it
 * warned about: {@code ChartAnswer.getUnfaithfullyRenderedCitations()}, published as the
 * {@code unfaithfullyRenderedCitations} response key. What travels is the INDEX and never a word of
 * either text. That accessor is canonical for what is stated and ADR Decision 74 for why — including
 * why no record prose goes with it, and why the sibling class-code check publishes nothing.
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
 *       {@code DrugSafetyValidator.endSentence} whenever a clause follows it, so a strength clause
 *       — {@code " This finding is a reason to withhold it."} and, since issue #348, any of the
 *       three counterparts beside it — always opens a new record sentence.
 *       It covers the SEAM and not the clause's interior — a reproduction that runs from the
 *       detail into the clause and diverges inside it is reported, and so is one inside a clause
 *       long enough to clear {@link #MIN_REPRODUCED_WORDS} on its own, which every clause but
 *       {@code STRENGTH_WITHHOLD} is. Since issue #349 a further clause can follow
 *       the detail, the chart-order bridge, and it is why that clause's own lead ENDS a
 *       sentence rather than introducing its items with a colon: the lead alone clears
 *       {@link #MIN_REPRODUCED_WORDS}, so joined to the items it would put invariant
 *       boilerplate inside a sentence whose interior carries order displays. Its ITEMS are
 *       still interior, and that is inherent to carrying variable content. A citation marker
 *       issue #379 appends to an item is invisible here, by {@link #wordsWithoutMarkers} and
 *       deliberately; so are punctuation and case, which {@link Words#of} drops for every
 *       operand. That residue is
 *       accepted rather than excluded:
 *       excluding it means teaching this check where a finding's own prose ends, which is knowledge
 *       that belongs to {@code renderFinding} and would be a second copy of it here;</li>
 *   <li><b>support is POOLED across the cited records</b>, exactly as {@link ClassCodeFidelityCheck}
 *       pools it for a class code: a continuation ANY cited record explains — by ending, by opening
 *       a new sentence, or by simply continuing the same way — is not a divergence, whatever a
 *       second record's layout makes of it. One mechanism string really is rendered into TWO records
 *       that are cited together (the {@code safety_finding}'s detail and the {@code drug_reference}'s
 *       {@code "; "}-joined interaction item) wherever that partner is PROMOTED — since issue #355 a
 *       record with nothing patient-specific to show normally carries names and severities rather
 *       than mechanism prose, which leaves this check little to judge on the commonest record. Normally, not always:
 *       a rule carrying no token and no ATC has no name to shorten to, so an operator-authored
 *       dataset still renders paragraphs there ({@code drug-reference-unpromoted-tail-budget.json} is
 *       one). That is where this came from — but on that
 *       arrangement the weak gap question now lets the reference record explain its own continuation,
 *       so the pooling is not what keeps THAT answer quiet and an earlier draft of this bullet said
 *       it was. What it still decides is the case where a second cited record ends where the first
 *       diverges, and the case where one record's own reproduction carries on past the point another
 *       stops matching; both have cases of their own. Only the cited REFERENCE records
 *       are asked, and widening that to the chart records is refused rather than merely unnecessary:
 *       a chart record is the patient's own charted prose, which this module never asked the answer
 *       to reproduce, and the WARN carries the patient id
 *       ({@code aCitedChartRecordIsNeverComparedAgainstTheAnswer}). The third leg — "by simply
 *       continuing the same way" — is narrower than it reads: an explaining record's own overlap
 *       must itself reach {@link #MIN_REPRODUCED_WORDS}, so a record that carries the continuation
 *       but joins the answer eleven words later is never consulted;</li>
 *   <li>it compares WORDS — runs of letters and digits, lower-cased — so punctuation cannot make
 *       two identical words differ. The sentence-boundary bit each word carries is deliberately NOT
 *       part of that equality: were it, a record writing {@code "(e.g. chloroquine"} against an
 *       answer writing {@code "(e.g., chloroquine"} would break the reproduction and then report
 *       two IDENTICAL words as a substitution, turning a boundary misreading into a false
 *       accusation instead of silence.</li>
 * </ul>
 *
 * <p><b>Which way a boundary misreading fails, and why the gap question is the WEAK one.</b> Both
 * bit-driven conditions are SILENCING, so a gap read as a sentence end can only add silence: on the
 * record side it takes the "reproduced a sentence and moved on" exit, on the answer side the
 * "stopped copying" one. So a misread boundary costs a REPORT and never causes one — but only
 * because the bit is {@link ChartSearchAiUtils#mayEndASentence}, which says yes to a terminator
 * ANYWHERE in the gap. Asking {@link ChartSearchAiUtils#SENTENCE_BOUNDARY} instead makes that false
 * in the other direction, and it was measured rather than argued: it wants the terminator followed
 * IMMEDIATELY by whitespace, so an answer that quotes the record verbatim, closes the quotation
 * ({@code ."}) and starts its own next sentence has no answer-side boundary, falls through to the
 * report, and is accused of a substitution it did not make
 * ({@code aQuotationTheAnswerClosedBeforeItsOwnNextSentenceIsNotReported}, which reddens under that
 * predicate).
 *
 * <p><b>An ELISION is a report, and that is the intended reading rather than a false alarm.</b> An
 * answer that reproduces a record's opening, marks a cut with {@code …} or an em dash, and resumes
 * is reported — it states no word the record does not, and it has still dropped content out of a
 * sentence a clinician reads, which is issue #337's SECOND capture exactly (<em>"neuromuscular
 * blockers, aminoglycoside antibiotics,"</em> excised from a botulinum toxin warning whose partner
 * is an aminoglycoside). What that costs is a WARN whose wording — "states different words" — reads
 * oddly of a marked cut. What it MISSES is the same elision written with three ASCII dots, whose
 * first dot the weak gap question reads as a sentence end: {@code ...} is silent where {@code …} is
 * reported. Which elisions are seen therefore depends on the glyph the model chose, and no
 * arrangement of these two rules removes that — closing it means a gap question that is not
 * silencing, and then a closed quotation is a false report again.
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
 * <p><b>The two pooling legs are pinned on ASSEMBLED records, and that is deliberate.</b> Neither
 * {@code Reproductions.explained} (another cited record ended innocently here) nor
 * {@code carriedThrough} (some reproduction carries on past here) is reachable on the bundled
 * sixteen-entry excerpt: the two records the injector produces for one question carry the same
 * mechanism string, so they diverge together, and no record there states one passage twice. Both
 * shapes are ordinary in the data this runs on — a rendered reference record whose partner was
 * PROMOTED is a {@code "; "}-joined list of per-partner items and DDInter partners routinely share a
 * mechanism, and since issue #355 a record with nothing patient-specific to show normally carries
 * names and severities instead, per the bullet above — so their cases build the record rather than injecting
 * one. That is the right operand here, because this check is a pure function of an answer and a
 * record's TEXT and the cases beside them already pin that it runs over production-rendered records
 * on the real answer path. Both were unpinned when they were written,
 * and a review's mutation sweep is what said so.
 *
 * <p><b>One regression this file cannot see.</b> Its gate asks
 * {@link ChartSearchAiUtils#referenceGroup} rather than testing {@code resourceType} against a type
 * name, per CLAUDE.md's rule for a question that is not about grading — so a reference type added
 * later is covered without this class changing. Nothing guards that: hardcoding the pair here leaves
 * the whole suite green. The sentence this replaces said a THIRD reference-group type would close
 * it; issue #354 added one ({@code drug_class_note}) and re-measured — the hardcode still ships
 * green, because no case drives a record of that type through this check. What would close it is a
 * case that does, not another type. ADR Decision 61 carries the same residue.
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
	 * words is what the prompt asked for. What pins this number from below is the sibling check's own
	 * suite rather than a case here, which would only be a copy of that arrangement: {@code
	 * ClassCodeFidelityTest}'s silence assertions capture this whole PACKAGE, and its canned answers
	 * restate that generated headline, so lowering this floor to nine turns several of them red with
	 * a failure message about class codes. <b>Mutate this constant and read the failures; WHICH of
	 * them redden is not enumerated here.</b> It was, alongside the counts in {@code
	 * ClassCodeFidelityTest} and in ADR Decision 61, and one merge into that file falsified all three
	 * at once — a count replacing a stale count is stale again on the next merge.
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
	 * and then states different words inside the sentence it was reproducing — and returns the
	 * citations it reported, for the caller to carry onto the answer.
	 *
	 * @param patient whose answer it is — logged so a line is attributable under concurrent requests
	 * @param answer the answer prose, unchanged by this method
	 * @param cited the references the answer cites, as resolved by
	 *            {@link LlmInferenceService#extractCitedReferences} — taking that accessor's own
	 *            output rather than re-deriving "which records were cited" from the prose, for the
	 *            reason {@link ClassCodeFidelityCheck} takes it
	 * @param mappings the chart's records, the carrier of the cited records' type and text
	 * @return the DISTINCT citation indexes this call warned about, in the order they were reported;
	 *         an empty list wherever it ran and warned about none, and {@code null} — the absence of
	 *         a measurement rather than a measurement of none — only where the check itself failed.
	 *         One record is one entry however many times the answer diverged from it: what a consumer
	 *         can act on is "this citation's rendering is not the record's words", and the issue's
	 *         own comment records two WARNs on one record from a single answer. The list is
	 *         unmodifiable and carries no word of either text; {@code ChartAnswer
	 *         .getUnfaithfullyRenderedCitations()} carries why, and what an empty one does not say.
	 */
	static List<Integer> reportUnfaithfulReferenceProse(Patient patient, String answer,
			List<RecordReference> cited, List<RecordMapping> mappings) {
		Integer patientId = null;
		try {
			// Inside the guard, not above it: reading a detached patient proxy is the one line here
			// that could throw, and the promise this catch makes is structural or it is nothing.
			patientId = patient == null ? null : patient.getPatientId();
			List<RecordMapping> reference = citedReferenceProse(cited, mappings);
			if (reference.isEmpty()) {
				// The first gate, and the commonest by far: on a stock install
				// chartsearchai.drugReference.enabled is false, so no answer cites reference prose
				// and nothing here — including tokenising the answer — is worth doing.
				log.debug("Reference-prose check skipped for patient={}: the answer cites no readable "
						+ "reference record", patientId);
				return Collections.emptyList();
			}
			Words answerWords = wordsWithoutMarkers(answer);
			Reproductions found = new Reproductions();
			if (answerWords.size() >= MIN_REPRODUCED_WORDS) {
				for (RecordMapping mapping : reference) {
					examine(answerWords, wordsWithoutMarkers(mapping.getText()), mapping.getIndex(),
						found);
				}
			}
			if (!found.any()) {
				log.debug("Reference-prose check skipped for patient={}: the answer reproduces no "
						+ "cited reference record", patientId);
				return Collections.emptyList();
			}
			List<Divergence> unexplained = found.unexplained();
			if (unexplained.isEmpty()) {
				// Reproduced and faithful — the state the check exists to be able to tell from the
				// two above, and silent without a line of its own would be the third indistinguishable
				// silence ClassCodeFidelityTest's own gate assertions exist to prevent.
				log.debug("Reference-prose check found no divergence for patient={}: every "
						+ "reproduction of a cited reference record is faithful", patientId);
				return Collections.emptyList();
			}
			// Ordered and de-duplicated in one pass; the WARNs below stay per divergence, because a
			// maintainer needs both offsets. The @return tag carries why the statement collapses them.
			Set<Integer> stated = new LinkedHashSet<Integer>();
			for (Divergence divergence : unexplained) {
				stated.add(Integer.valueOf(divergence.recordIndex));
				// Both halves are needed to reconstruct it: which record's prose was degraded, and
				// where in it the answer stopped agreeing. Neither the answer nor the record text is
				// logged — see the class javadoc. The word position counts from one, because it is
				// the only handle a maintainer has for finding the divergence in a record this line
				// deliberately does not quote.
				log.warn("Answer for patient={} reproduces {} words of cited record [{}] and "
						+ "then states different words inside the sentence it was copying "
						+ "(the record's own text continues at its word {}, counting from one). The "
						+ "answer prose is left unchanged (issue #337).", patientId,
						Integer.valueOf(divergence.reproducedWords),
						Integer.valueOf(divergence.recordIndex),
						Integer.valueOf(divergence.recordWordOffset));
			}
			return Collections.unmodifiableList(new ArrayList<Integer>(stated));
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
			// Null and not an empty list, for the reason the return tag gives: this call made no
			// measurement. It is the one path where the log and the statement can disagree — a throw
			// after the loop has emitted some lines leaves those WARNs standing while the response
			// says nothing — and that asymmetry is deliberate: a partial list published as if it were
			// the whole one would tell a client the unlisted citations were checked.
			return null;
		}
	}

	/**
	 * @return the cited records that are this module's own reference prose and carry text to compare
	 *         against, in citation order. A record whose text could not be read is left out rather
	 *         than accused: one we could not read is one we cannot say the answer diverged from.
	 *
	 *         <p>Resolved BEFORE the answer is tokenised, so the commonest arrangement — the shipped
	 *         default, where {@code chartsearchai.drugReference.enabled} is false and no reference
	 *         record exists at all — costs a map build and nothing else.
	 */
	private static List<RecordMapping> citedReferenceProse(List<RecordReference> cited,
			List<RecordMapping> mappings) {
		List<RecordMapping> reference = new ArrayList<RecordMapping>();
		if (cited == null || cited.isEmpty() || mappings == null) {
			return reference;
		}
		Map<Integer, RecordMapping> byIndex = new HashMap<Integer, RecordMapping>();
		for (RecordMapping mapping : mappings) {
			byIndex.put(Integer.valueOf(mapping.getIndex()), mapping);
		}
		for (RecordReference citation : cited) {
			RecordMapping mapping = byIndex.get(Integer.valueOf(citation.getIndex()));
			if (mapping != null && isModuleSuppliedReferenceProse(mapping.getResourceType())
					&& !ChartSearchAiUtils.isBlank(mapping.getText())) {
				reference.add(mapping);
			}
		}
		return reference;
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
	 * The comparable words of one text: {@link ChartSearchAiUtils#INLINE_CITATION} markers removed,
	 * then tokenised.
	 *
	 * <p><b>ONE rule over BOTH operands</b>, which is what makes the comparison marker-blind rather
	 * than marker-blind on one side. The answer's markers were stripped from the first version of this
	 * check because the answer is where markers are emitted; the RECORD side was left alone because no
	 * record carried one. Issue #379 gave a {@code safety_finding}'s chart-order attribution the number
	 * of the record it names, and the asymmetry then had a failure of its own: {@link Words#of}
	 * tokenises runs of letters and digits, so a record's {@code "[14]"} becomes the record word
	 * {@code "14"} that the stripped answer can never reproduce — and an answer copying that
	 * attribution VERBATIM, which is exactly what the number is for, would break its reproduction there
	 * and be reported as having substituted words. A published report (see
	 * {@code ChartAnswer.getUnfaithfullyRenderedCitations}) manufactured by the faithful behaviour.
	 *
	 * <p>Replaced with a SPACE rather than removed, so a marker can never weld its neighbours into one
	 * token. Every marker this module has been observed to receive already has whitespace on both
	 * sides, so nothing in the suite tells the two apart; the space is the choice that cannot be wrong
	 * rather than the one a measurement demanded. <b>That is why this is not
	 * {@code CitationGroundingVerifier.stripCitationMarkers}</b>, which sits in this same package and
	 * replaces with the EMPTY string: that method hands a statement to a judge reading prose, where a
	 * welded token is a wording; here the output is TOKENISED and compared for equality, where a
	 * welded token is a false substitution report. Do not consolidate the two on the empty-string
	 * form.
	 */
	private static Words wordsWithoutMarkers(String text) {
		return Words.of(text == null ? "" : ChartSearchAiUtils.INLINE_CITATION.matcher(text)
				.replaceAll(" "));
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
					found.diverged(i, new Divergence(recordIndex, length, j + 2));
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

		/** Answer word positions some record's reproduction carried ON past — every position it
		 *  covers except its LAST, which is where that reproduction stopped agreeing and is the
		 *  position a divergence is keyed on. The first position IS included: a reproduction that
		 *  begins there still carried through it, and excluding it would un-pool a divergence another
		 *  record's run starts at. Nothing was substituted at any of them, however a second record's
		 *  layout reads. */
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
	 * <p>The bit is read by {@link ChartSearchAiUtils#mayEndASentence}, the deliberately WEAK question
	 * over the terminator set {@link ChartSearchAiUtils#SENTENCE_BOUNDARY} splits
	 * {@link CitationGroundingVerifier}'s units on: any terminator anywhere in the gap answers yes.
	 * Both of this check's uses of the bit are silencing, so the weaker reading can only suppress a
	 * report — and the stronger one was measured to cause a false one, on a quotation the model closed
	 * with {@code ."} before starting its own next sentence. It is deliberately not part of
	 * {@link #word} equality; the class javadoc says why.
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
						ChartSearchAiUtils.mayEndASentence(text.substring(gapFrom, start))));
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
