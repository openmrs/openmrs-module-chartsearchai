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
import java.util.regex.Matcher;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports the chart citations an answer offers as evidence of an ACTIVE DRUG ORDER that cannot be
 * one — issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/377">#377</a>.
 * A deterministic, exact comparison, like its two siblings and for the same reason: no model call,
 * no embedding, no cosine floor.
 *
 * <p><b>The failure.</b> Measured live on a real standalone. A "can I start her on clarithromycin?"
 * verdict named five of the patient's active orders and attached a chart citation to each. Three of
 * the five pointed at a record that is not a drug order at all: a clinician following the citation
 * behind <em>"Clarithromycin interacts with active order Methylprednisolone"</em> was shown
 * <em>Benign neoplasm of thyroid gland</em>; behind <em>Budesonide</em>, a home visit; behind
 * <em>Prednisone</em>, a consultation encounter. The findings themselves were correct and
 * deterministic — it is the chart evidence bolted onto them that was wrong, and the two remaining
 * sentences cited the right order, so the citations read as uniformly plausible. Three runs returned
 * byte-identical text.
 *
 * <p><b>Why nothing saw it.</b> Every citation in that response serialized {@code grounded: null}.
 * Grounding was on and working — a control question on the same patient in the same session returned
 * 341 chart citations, every one carrying a verdict — but each of these sentences also cites a
 * {@code safety_finding}, and a chart citation whose graded statement rests on reference material
 * has its entailment negative withheld (issue #284). {@link CitationGroundingVerifier}'s own javadoc
 * names that residue and accepts it; this check is what now covers the reported half of it.
 *
 * <p><b>What it compares.</b> Where the answer states this module's own rendered claim —
 * {@link DrugSafetyValidator#ACTIVE_ORDER_INTERACTION_PHRASE} — the citations it offers for that
 * claim must be able to be the order. Two rules, both exact:
 * <ul>
 *   <li>the cited record's resource type must be one
 *       {@link ChartSearchAiUtils#mayDescribeAMedicationOrder} admits. A condition, a visit and an
 *       encounter are not, and those are the three the ticket measured;</li>
 *   <li>and the chart must not already say that order is over —
 *       {@link RecordMapping#getOrderActive()} being {@code FALSE}, which is this patient's own
 *       order and not in force. That mark is three-valued and {@code null} stays silent here: see
 *       {@code SerializedRecord.orderActive}, which is canonical for what {@code null} covers, and
 *       note that the safety layer reads the orders through {@code OrderService} at a different
 *       instant from the chart builder — so an order lapsing between the two reads is a known and
 *       unpinned false-alarm shape for this rule alone.</li>
 * </ul>
 *
 * <p><b>The unit is the citation RUN, not the sentence.</b> The reported answer put all five
 * findings in ONE sentence, two of them citing correct drug orders, so a sentence-scoped comparison
 * passes it. What is offered for a claim is the run of markers immediately following it: markers
 * separated by nothing but spaces, tabs and commas, ending at the first other character. So
 * {@code "…active order Methylprednisolone [177] [350], Clarithromycin interacts with…"} offers
 * {@code [177] [350]} and nothing further, and
 * {@code "…active order Simvastatin [3] [61], and her thyroid neoplasm [9] is unrelated"} does not
 * attribute {@code [9]} to the order claim. Sentences still bound the scan
 * ({@link ChartSearchAiUtils#SENTENCE_BOUNDARY}, the SPLITTING question over the shared terminator
 * set) so a phrase occurrence with no run after it cannot reach into the next sentence for one. The
 * next phrase occurrence bounds the scan as well, though only the scan — {@link #examine} records
 * what removing that bound was measured to change, which is nothing.
 *
 * <p><b>Not a fourth claim-unit rule.</b> {@code CitationGroundingVerifier}'s {@code splitEnumeration}
 * and {@code splitIntoClauseScopedSentences} partition a sentence for GRADING, into cumulative
 * prefixes and colon-led items, and both hand a citation a STATEMENT to be entailed by its record.
 * This asks a different question of a different operand — which markers were offered for one claim
 * the module itself worded — and it needs no statement at all, so borrowing either would be reading
 * a grading partition as an attribution one. Marker DECODING is still the shared step
 * ({@link ChartSearchAiUtils#citedIndexes}) over the run's own substring; the pattern is matched
 * directly here only to find where that substring ends, which is the text OFFSET the shared step
 * cannot carry.
 *
 * <p><b>Conservative by construction</b>, because a check that cries wolf is worse than no check:
 * <ul>
 *   <li>it says nothing unless the answer states the module's phrase verbatim. That gate is a
 *       byte-exact containment: a plural, a capitalised first letter, a line break inside the phrase
 *       or markdown emphasis inside it all silence it. Its recall over live answers is UNMEASURED —
 *       the system prompt's safety few-shot teaches the composite SHAPE but not this wording, which
 *       the model copies from the finding record — and on the ticket's own reproduction it fires;</li>
 *   <li>it considers only indexes the answer's own resolution admitted, so a bracketed clinical
 *       value the chart has no record for is not a citation here either. That filter and
 *       {@link #refusal}'s null-mapping arm are ONE conservatism and not two — {@code
 *       extractCitedReferences} admits every in-range index, so removing either leaves the other
 *       answering, and removing this one was measured byte-identical. It is the one kept because
 *       CLAUDE.md's inline-citation rule states it: an index is not a citation until the answer's
 *       own resolution admits it;</li>
 *   <li>reference-group citations in the run are untouched. Every one of these runs carries the
 *       module's own {@code safety_finding} beside the chart citation — that is the shape the ticket
 *       measured — and it is cited legitimately;</li>
 *   <li>a record whose resource type the module could not read is not accused. The allow-list
 *       refuses a type it does not recognise, and a type nobody READ is not one of those —
 *       {@link ChartSearchAiUtils#referenceGroup}'s fail-safe would call it chart evidence and the
 *       report would read "null record";</li>
 *   <li>it reports the CITATION and never the record's text or the answer's, both of which carry
 *       patient data. The type travels instead, which is what makes an unexpected retrieval contract
 *       diagnosable from one line.</li>
 * </ul>
 *
 * <p><b>What the run rule attributes that a reader might not.</b> The run is the first one after the
 * phrase, and nothing bounds how far after — the partner's name sits in between and a name has no
 * fixed length, so no budget on that gap could be anything but arbitrary. Where the claim carries no
 * markers of its own, the next citation in the sentence is therefore read as offered for it:
 * {@code "…interacts with active order Warfarin and she also has hypertension [42]"} attributes
 * {@code [42]}. That is a decision rather than an oversight — a lone citation at the end of a
 * sentence is conventionally offered for the sentence, and this sentence asserts the order — and it
 * is pinned by
 * {@code ActiveOrderCitationFidelityTest.aLoneCitationInAClaimWithNoRunOfItsOwnIsAttributedToIt}, so
 * narrowing it later has to be argued rather than drifted into. It is bounded on the other side:
 * once a claim HAS a run, nothing after that run is attributed to it.
 *
 * <p><b>What it cannot see.</b> A citation that IS an in-force drug order but the WRONG one passes:
 * the check tests what a record can be, never which order the finding was computed from. Closing
 * that needs a per-finding order identity the module does not carry today, and ADR Decision 41
 * records why the obvious source for one would have missed this ticket's own case — the cited
 * records here are RETRIEVED {@code drug_order}s that no injector resolved. A claim whose markers do
 * not immediately follow it is not attributed either, and neither is a chart citation the answer
 * places before the phrase.
 *
 * <p><b>It reports and it publishes.</b> The WARN is the maintainer's channel;
 * {@code ChartAnswer.getMisattributedOrderCitations()} is the clinician's, through the
 * {@code misattributedOrderCitations} response key, and it exists for the reason ADR Decision 74
 * gave for publishing its sibling's answer — on the reported response every observable field read
 * exactly as a clean answer's would. It never rewrites the answer: editing a clinician-facing
 * sentence is a larger decision than this check is licensed to make.
 *
 * <p><b>Where it runs.</b> Both answer paths, {@link LlmInferenceService#search} and
 * {@code searchStreaming}, so the endpoint users hit is covered. &rarr; ADR Decision 76, canonical
 * for the reasoning, for the alternatives measured and rejected, and for the residues.
 *
 * <p>Not the progressive-reasoning
 * preview, which discards its answer and resolves no citations, and not a cached answer, which was
 * checked when it was produced — the same scoping {@link ClassCodeFidelityCheck} states.
 */
final class ActiveOrderCitationFidelityCheck {

	private static final Logger log = LoggerFactory.getLogger(ActiveOrderCitationFidelityCheck.class);

	/** The characters that may separate two markers of one run: a space, a tab, and a comma because
	 *  {@code LlmAnswerExtractor.normalizeSlashCitations} rewrites {@code [6, 7]} into two markers
	 *  and leaves the separator behind. Nothing else — a word between two markers means the second
	 *  one attributes a different clause.
	 *
	 *  <p>Spelled as the three characters rather than as a category, because a category is a claim
	 *  about members nobody enumerated: a non-breaking space is horizontal whitespace and is not in
	 *  this set, so {@code [1]\u00A0[2]} is one run of one marker and the second is not attributed.
	 *  That is the same direction as the line-break residue below — silence — and it is recorded
	 *  rather than closed.
	 *
	 *  <p><b>No carriage return or newline, and their absence is not an omission.</b> The caller has
	 *  already split on {@link ChartSearchAiUtils#SENTENCE_BOUNDARY}, whose line-break arm means no
	 *  string reaching here can carry one. What that costs is a real residue rather than nothing: a
	 *  model that hard-wraps BETWEEN two markers of one run puts them in two units, and only the
	 *  first is attributed. ADR Decision 76 records it.
	 *
	 *  <p>Deliberately narrower than {@code CitationGroundingVerifier}'s {@code LEADING_ITEM_SEPARATOR},
	 *  the nearest neighbouring alphabet, which also admits {@code ;} and a leading {@code and} or
	 *  {@code or} because it separates ITEMS of one enumeration. Widening this to match would make a
	 *  second claim's markers part of the first claim's run; the narrowing fails toward silence. */
	private static final String RUN_SEPARATORS = " \t,";

	private ActiveOrderCitationFidelityCheck() {
	}

	/**
	 * Reports, at WARN, every chart citation {@code answer} offers as evidence of an active drug
	 * order that cannot be one, and returns them for publication.
	 *
	 * @param patient whose answer it is — logged so a line is attributable under concurrent requests
	 * @param answer the answer prose, unchanged by this method
	 * @param cited the references the answer cites, as resolved by
	 *            {@link LlmInferenceService#extractCitedReferences} — taking the accessor's own
	 *            output rather than re-deriving it from the prose is what keeps "which records were
	 *            cited" a single answer, and it is also what the clinician can click
	 * @param mappings the chart's records, cited or not — the carrier of each cited record's
	 *            resource type and of its order-currency mark
	 * @return the distinct offending citation indexes in the order the answer states them, empty
	 *         when the check ran and found none, and null only when the check itself failed
	 */
	static List<Integer> reportMisattributedOrderCitations(Patient patient, String answer,
			List<RecordReference> cited, List<RecordMapping> mappings) {
		Integer patientId = null;
		try {
			// Inside the guard, not above it: reading a detached patient proxy is the one line here
			// that could throw, and the promise this catch makes is structural or it is nothing.
			patientId = patient == null ? null : patient.getPatientId();
			List<Integer> offending = new ArrayList<Integer>();
			if (answer == null
					|| !answer.contains(DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE)) {
				// A short-circuit and NOT the rule — deleting it was measured byte-identical over the
				// same 66,429 arrangements, because examine's own per-sentence indexOf is what
				// scopes the check to an active-order claim. What it buys is that the overwhelmingly
				// common answer, which states no such claim, costs one containment scan and neither
				// map below — ADR Decision 76 carries the figures, and carries them once.
				return offending;
			}
			Map<Integer, RecordMapping> byIndex = new HashMap<Integer, RecordMapping>();
			if (mappings != null) {
				for (RecordMapping mapping : mappings) {
					byIndex.put(Integer.valueOf(mapping.getIndex()), mapping);
				}
			}
			Set<Integer> citedIndexes = new HashSet<Integer>();
			if (cited != null) {
				for (RecordReference reference : cited) {
					citedIndexes.add(Integer.valueOf(reference.getIndex()));
				}
			}
			List<String> reasons = new ArrayList<String>();
			Set<Integer> seen = new LinkedHashSet<Integer>();
			for (String sentence : ChartSearchAiUtils.SENTENCE_BOUNDARY.split(answer)) {
				examine(sentence, byIndex, citedIndexes, seen, reasons);
			}
			offending.addAll(seen);
			if (!offending.isEmpty()) {
				// The indexes are carried inside the reason strings rather than as a bare list, so
				// each one reads beside the type that disqualified it: a maintainer triaging this
				// needs to know whether the model cited a condition or whether the deployment types
				// prescriptions in a way this module does not admit. Neither the answer nor any
				// record text is logged — they carry patient data, and the citation with the patient
				// identifies the claim.
				log.warn("Answer for patient={} cites {} as evidence of an active drug order it "
						+ "cannot be. The answer prose is left unchanged (issue #377).",
						patientId, reasons);
			}
			return offending;
		}
		catch (RuntimeException e) {
			// A diagnostic must never break a clinical answer — the same promise
			// CitationGroundingVerifier makes in its javadoc, made structurally rather than by
			// inspection, and loudly, so the failure of the check is not itself silent. Null rather
			// than an empty list: the caller publishes this, and "no measurement" is not "none".
			log.warn("Active-order citation check failed for patient={}; the answer is unaffected: {}",
					patientId, e.toString());
			return null;
		}
	}

	/**
	 * Examines one sentence, adding every offending citation to {@code seen} and its reason to
	 * {@code reasons}.
	 *
	 * <p>Split out so the run walk reads as the one thing it is. The two collections are carried in
	 * rather than returned because a citation offending in two runs is one entry — the answer states
	 * it once as far as a client is concerned — and de-duplicating afterwards would lose the order
	 * the answer states them in, which is the order the published list promises.
	 */
	private static void examine(String sentence, Map<Integer, RecordMapping> byIndex,
			Set<Integer> citedIndexes, Set<Integer> seen, List<String> reasons) {
		String phrase = DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE;
		int at = sentence.indexOf(phrase);
		while (at >= 0) {
			int next = sentence.indexOf(phrase, at + phrase.length());
			// Where the next claim begins. It bounds the SCAN and not the answer: removing it and
			// passing sentence.length() was measured byte-identical over 66,429 generated
			// arrangements of the phrase, markers, separators and a terminator — a run that would
			// reach past the next occurrence is already stopped by onlySeparators, the gap carrying
			// that occurrence's own letters, and a run beyond it is attributed to that occurrence
			// instead, in the same order. It stays because firstMarkerRun makes it the region bound
			// that keeps the scan linear.
			int limit = next < 0 ? sentence.length() : next;
			for (Integer index : ChartSearchAiUtils.citedIndexes(
					firstMarkerRun(sentence, at + phrase.length(), limit))) {
				if (!citedIndexes.contains(index)) {
					continue;
				}
				RecordMapping mapping = byIndex.get(index);
				String reason = refusal(mapping);
				if (reason != null && seen.add(index)) {
					reasons.add("[" + index + "] " + reason);
				}
			}
			at = next;
		}
	}

	/**
	 * @return the text of the first run of citation markers in {@code sentence} between
	 *         {@code from} and {@code limit} — markers separated by nothing but
	 *         {@link #RUN_SEPARATORS} — or an empty string when none begins there.
	 *
	 *         <p>Returns the SUBSTRING rather than the indexes it contains so that decoding stays
	 *         {@link ChartSearchAiUtils#citedIndexes}' job (CLAUDE.md's inline-citation rule). What
	 *         this method needs the shared pattern's matcher for is the one thing a set of indexes
	 *         cannot carry: where each marker sits, so the run can be told from the next claim's.
	 */
	private static String firstMarkerRun(String sentence, int from, int limit) {
		// Where the claim's own clause ends. The run may only BEGIN before it — a claim whose clause
		// carries no markers takes none, rather than annexing the next clause's. Round 1 of this PR's
		// review found the unbounded form crying wolf on
		// "…active order Prednisone, which she has been taking since 2024 for her benign thyroid
		// neoplasm [177]", where [177] is a correct citation for the clause it sits in and this key
		// is published to a client. It bounds the START only: a comma is a legitimate separator
		// BETWEEN two markers of one run ("[1], [2]"), which is why RUN_SEPARATORS carries one.
		int startBound = clauseBound(sentence, from, limit);
		// The region is what keeps this linear. Matcher.find scans to the end of the INPUT, not to a
		// bound the caller applies afterwards, so a phrase occurrence with no marker after it used to
		// pay for a scan of the whole remaining sentence — once per occurrence, which is quadratic in
		// the occurrences of one sentence. Measured by calling this class's own entry point from a
		// throwaway same-package case, best of five rounds of fifty after fifty warm-up iterations,
		// over an answer that is nothing but repeated claims with no markers: at 40 occurrences 207
		// us without the region and 103 us with it, at 320 occurrences 10.7 ms without and 574 us
		// with. The pattern has no anchor and no lookaround, so narrowing the region cannot change
		// what it matches; INLINE_CITATION's javadoc is where that property is stated.
		Matcher marker = ChartSearchAiUtils.INLINE_CITATION.matcher(sentence).region(from, limit);
		int start = -1;
		int end = -1;
		while (marker.find()) {
			if (start < 0) {
				if (marker.start() >= startBound) {
					break;
				}
				start = marker.start();
			}
			else if (!onlySeparators(sentence, end, marker.start())) {
				break;
			}
			end = marker.end();
		}
		return start < 0 ? "" : sentence.substring(start, end);
	}

	/**
	 * @return the offset of the first clause separator in {@code sentence} between {@code from} and
	 *         {@code limit}, or {@code limit} where there is none.
	 *
	 *         <p>Punctuation only, and deliberately not a vocabulary: a comma or a semicolon says a
	 *         new clause has begun whatever words follow, while a rule reading {@code and},
	 *         {@code which} or {@code but} would be a list of conjunctions nobody enumerated — the
	 *         claim shape this module's instructions forbid. What that gives up is a coordinating
	 *         conjunction with no comma before it, which still annexes the next clause's citation:
	 *         {@code ActiveOrderCitationFidelityTest.aLoneCitationInAClaimWithNoRunOfItsOwnIsAttributedToIt}
	 *         is that residue, pinned rather than left to be found.
	 *
	 *         <p>It fails toward SILENCE in both directions, which is this check's own direction. A
	 *         partner name carrying a comma truncates the search and the claim reports nothing; a
	 *         model that puts its markers after the clause break rather than before it is not
	 *         attributed. Neither can manufacture a report.
	 */
	private static int clauseBound(String sentence, int from, int limit) {
		for (int at = from; at < limit; at++) {
			char c = sentence.charAt(at);
			if (c == ',' || c == ';') {
				return at;
			}
		}
		return limit;
	}

	/** @return whether {@code text} between {@code from} and {@code to} is nothing but
	 *          {@link #RUN_SEPARATORS} — an empty gap included, since two markers can abut. */
	private static boolean onlySeparators(String text, int from, int to) {
		for (int at = from; at < to; at++) {
			if (RUN_SEPARATORS.indexOf(text.charAt(at)) < 0) {
				return false;
			}
		}
		return true;
	}

	/**
	 * @return why {@code mapping} cannot be the active order its claim names, or null when it can be
	 *         — which is also the answer for a reference-group citation, deliberately: the module's
	 *         own {@code safety_finding} is what these runs cite beside the chart record, and it is
	 *         not chart evidence about an order to begin with.
	 *
	 *         <p>A null mapping answers null too, and that arm is unreachable rather than lenient: a
	 *         cited index always has a mapping, the reference list being built from the mappings.
	 *         Said so the guard does not look better defended than it is. The blank-TYPE arm beside
	 *         it is a different matter and is reachable — {@code PatientChartSerializer} passes
	 *         through whatever querystore retrieved.
	 */
	private static String refusal(RecordMapping mapping) {
		if (mapping == null || ChartSearchAiUtils.isBlank(mapping.getResourceType())) {
			// A record whose type the module could not read is one it cannot say anything about, and
			// this is NOT covered by the allow-list refusal below: referenceGroup's fail-safe calls
			// an unknown type chart evidence, so a null would otherwise be reported as "null
			// record" — an accusation made about metadata nobody read.
			return null;
		}
		if (!ChartSearchAiConstants.REFERENCE_GROUP_CHART
				.equals(ChartSearchAiUtils.referenceGroup(mapping.getResourceType()))) {
			return null;
		}
		if (!ChartSearchAiUtils.mayDescribeAMedicationOrder(mapping.getResourceType())) {
			// No article: the types this reaches begin with vowels as often as not ("encounter",
			// "obs", "allergy"), and a maintainer-facing line reading "a encounter record" invites a
			// fix to the grammar rather than to the citation.
			return mapping.getResourceType() + " record";
		}
		if (Boolean.FALSE.equals(mapping.getOrderActive())) {
			return "an order the chart marks as no longer in force";
		}
		return null;
	}
}
