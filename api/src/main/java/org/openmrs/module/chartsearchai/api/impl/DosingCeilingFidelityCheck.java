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
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.ChartSearchService.UnstatedDosingCeiling;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports a cited reference record whose answer quoted one of its dosing ceilings and left a
 * STRICTER one from the same record unstated — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/276">#276</a>. A
 * deterministic, exact comparison: no model call, no embedding, no cosine floor and no
 * reproduction threshold.
 *
 * <p><b>The failure.</b> A substance filed as one row per presentation publishes a ceiling per
 * presentation. Measured live on a RefApp 3.7.1 standalone, deterministic across reruns, on a
 * curated three-entry dataset filing {@code Acetylsalicylic acid} at 4000 mg/day and
 * {@code Acetylsalicylic acid (81 mg dispersible)} at 100 mg/day as one substance: a patient
 * charted on the 81 mg presentation, asked <em>"What is the maximum daily dose of aspirin for this
 * patient?"</em>, was answered <em>"The maximum daily dose of Acetylsalicylic acid for ages 0-120
 * is 4000 mg/day [113]."</em> Record 113 stated both ceilings — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/274">#274</a> put the
 * second one there and its own measurement records that the answer did not change. The evidence was
 * complete, the attribution was correct, and the number the clinician was given was forty times the
 * ceiling published for the presentation she was on.
 *
 * <p><b>Why nothing else can see it.</b> {@link ReferenceProseFidelityCheck} reports a
 * SUBSTITUTION inside a reproduction of at least its own word floor; this answer substitutes
 * nothing — every word of it is true of the row it names. {@link SafetyFindingSeverityFidelityCheck}
 * asks after a rating and a {@code drug_reference} record carries none.
 * {@link ClassCodeFidelityCheck} compares one ATC token shape. {@link ActiveOrderCitationFidelityCheck}
 * asks which chart record a sentence was attached to, and this citation is of reference material.
 * The overdose chip cannot see it either, and deliberately: {@code DrugSafetyValidator.LIMIT_CUE}
 * exists so that a RECITED ceiling is not read as a prescribed dose. This asks the question that
 * carve-out leaves open — which ceiling the answer recited.
 *
 * <p><b>What it compares.</b> Nothing it derives from prose. The ceilings travel structurally
 * beside the record — {@link RecordMapping#getDosingCeilings()}, written once by
 * {@code DrugReferenceInjector} from the clauses that method actually appended — and this check
 * asks which of them appear in the answer. Reading them back out of the record's own rendered text
 * was refused rather than merely not chosen, and more firmly than its siblings refused it: that
 * text interleaves the module's own sentences with operator-authored free text which can pair any
 * number with any unit, so a parse would attribute ceilings the dataset never published. That is
 * {@link RecordMapping#getOrderActive()}'s rule (issue #317) and
 * {@link RecordMapping#getOrderDrugNamed()}'s (issue #294), one field along.
 *
 * <p><b>Selection is the FIELD and never a type name.</b> The walk gates on a record carrying
 * ceilings, exactly as its sibling gates on one carrying a rating — never
 * {@code resourceType.equals(drug_reference)}, which {@code CLAUDE.md} forbids outright (issue
 * #122), and never {@code referenceGroup}, which names a grounding rule. It is also the cheapest
 * gate: on the shipped default {@code chartsearchai.drugReference.enabled} is false, the injector
 * never runs, no record carries ceilings, and this returns before scanning the answer for anything.
 *
 * <p><b>The walk is strictest-first, which is what makes the report's own claim structural.</b>
 * {@code getDosingCeilings()} is ordered by the writer, where the numbers are still doubles. The
 * check reads position 0 as the strictest and never sorts: as strings these ceilings would order
 * {@code "300"} before {@code "50"}, and a reversed order would silence this check on exactly the
 * arrangement it was written for — the laxer ceiling would sit at position 0, the answer would be
 * found to state it, and the walk would return. So the first ceiling the answer states, scanning up
 * from the strictest, is reported against position 0, and everything below it in that scan is
 * unstated by construction rather than by assertion.
 *
 * <p><b>Conservative by construction</b>, for the reason its siblings are:
 * <ul>
 *   <li>it says nothing about a record carrying fewer than two ceilings. A record with one cannot
 *       have a stricter one gone unstated; a record with NONE is every record this module did not
 *       inject for a reference entry, and also every reference record whose own text states no
 *       ceiling — a row publishing no band for this patient's age, or none carrying a daily
 *       maximum. It cannot tell any of those apart, by construction: what reaches it is one
 *       nullable list;</li>
 *   <li>an answer stating NO ceiling of a record is silent. A question about that drug's allergies
 *       cites the same record and quotes no number, and a check that reported it would cry wolf on
 *       every answer that was never about dosing;</li>
 *   <li>an answer stating the strictest ceiling is silent however else it is wrong. Whether the
 *       laxer one is ALSO stated is not asked: an answer giving both numbers has put the stricter
 *       one in front of the clinician, which is all this key exists to secure;</li>
 *   <li>a BLANK or absent answer is silent. That arm is reachable rather than defensive —
 *       {@code LlmInferenceService.extractCitedReferences} resolves the structured citations array
 *       for a blank answer deliberately — and a degenerate output is not a fidelity defect;</li>
 *   <li>it considers only the citations the answer's own resolution admitted
 *       ({@code LlmInferenceService.extractCitedReferences}), so a bracketed clinical value the
 *       chart has no record for is not a citation here either — {@code CLAUDE.md}'s
 *       inline-citation rule states it, and taking that accessor's output rather than re-deriving
 *       "which records were cited" is what keeps that one answer;</li>
 *   <li>the ceiling is matched case-insensitively through
 *       {@link ChartSearchAiUtils#statesMeasurement}, which is {@link ChartSearchAiUtils#statesWord}'s
 *       boundary over the same scan plus the one rule a NUMERIC needle needs — that method is
 *       canonical for what the rule is and why it is a second entry point rather than a widening.
 *       No second dialect of "the answer states X" exists to drift. What it buys here:
 *       {@code "4000 mg/day"} inside {@code "14000 mg/day"} does not match, nor does
 *       {@code "5 mg/day"} inside {@code "2.5 mg/day"} — the latter would be a false REPORT, which
 *       is the one direction this check must never fail in. And any occurrence the boundary ADMITS,
 *       for any reason, silences the report;</li>
 *   <li>it reports the citation and the two ceilings and NO PROSE FROM EITHER SIDE. The ceilings
 *       are themselves bytes of the record — they are the whole point, the number this exists to
 *       put in front of a reader — and they are safe to log and to publish because they are the
 *       dataset's own reference material rather than any recorded value of this patient's. Not
 *       "nothing about this patient": the ceilings are the ones
 *       {@code DrugReference.bandForAge(age)} selected, so a reader holding the dataset can narrow
 *       her age band from them. The answer is never quoted and neither is any other part of the
 *       record;</li>
 *   <li>it never rewrites the answer. Editing a clinician-facing sentence is a larger decision than
 *       this check is licensed to make, and since issue #201 a reference-group citation publishes
 *       no verdict to carry one.</li>
 * </ul>
 *
 * <p><b>What it cannot see</b>, stated rather than left to be found:
 * <ul>
 *   <li><b>whether a number was quoted AS a ceiling.</b> This is deliberately cue-blind.
 *       {@code DrugSafetyValidator.LIMIT_CUE} is the module's discriminator between a recited
 *       ceiling and a stated dose; it is private and in another package, and a second copy of it is
 *       the drift this subsystem keeps having to un-say. Carrying the UNIT buys most of what the
 *       cue would — the needle is {@code "4000 mg/day"} and not {@code "4000"}, so
 *       <em>"give 4000 mg"</em> does not trip the report and <em>"Aspirin 300 mg tablets"</em> does
 *       not silence it. The residue is an answer quoting the laxer ceiling in the record's own
 *       spelling while stating the stricter one in ANOTHER: that is reported. The respellings worth
 *       naming are <em>"300 mg per day"</em>, <em>"300mg/day"</em>, and — the one this check's own
 *       needle rule makes ordinary — a decimal written without its leading zero, since
 *       the record always spells it {@code "0.5 mg/day"} ({@code DrugReferenceInjector.dailyCeiling} of
 *       {@code formatNumber}'s {@code "0.5"}) while a clinician
 *       writes <em>".5 mg/day"</em>. An answer stating the stricter ceiling ONLY in that form is
 *       silent (the laxer's needle is refused inside it); one stating it that way AND the laxer in
 *       full is reported. Measured. The record spells both of its ceilings the same way, so an
 *       answer reciting IT states both in the form the needle matches, and that is the common
 *       case;</li>
 *   <li>whether the ceiling the answer stated is the RIGHT one for this patient. It is not a dosing
 *       check, and on the arrangement it was measured on no row is identifiable as the subject at
 *       all — both rows claimed the recorded name equally, which is why
 *       {@code DrugReferenceInjector.rowAttribution} correctly said nothing;</li>
 *   <li>a ceiling recited out of a WARNING on the same record, which silences a report it should
 *       not. Fail-toward-silence, and the direction this check must fail in;</li>
 *   <li><b>a ceiling stated for a DIFFERENT drug, which can go the other way and produce a report.</b>
 *       The scan is the whole answer and is not scoped to the citing sentence, so where two cited
 *       substances publish a ceiling of the same spelling — and they are machine-generated round
 *       numbers, so that is ordinary — an answer quoting the other substance's is read as quoting
 *       this record's. Measured through the real answer path. It is the deliberate price of the
 *       whole-answer unit, which every sibling pays: scoping to the sentence is Decision 76's
 *       already-refuted alternative, and its refutation transfers;</li>
 *   <li>a ceiling the record never stated. The pass that writes the record runs BEFORE the answer,
 *       so a row only the answer's own wording names was never in the list — the residue
 *       {@code DrugReferenceInjector.otherRowDosing} already records of its own bound.</li>
 * </ul>
 *
 * <p><b>Where it runs.</b> Both answer paths, {@link LlmInferenceService#search} and
 * {@code searchStreaming}, so the endpoint users hit is covered. Not the progressive-reasoning
 * preview, which discards its answer and resolves no citations, and not a cached answer, which was
 * checked when it was produced — the same scoping every sibling states.
 * &rarr; ADR Decision 96.
 */
final class DosingCeilingFidelityCheck {

	private static final Logger log = LoggerFactory.getLogger(DosingCeilingFidelityCheck.class);

	private DosingCeilingFidelityCheck() {
	}

	/**
	 * Reports, at WARN, every cited record whose answer quoted one of its dosing ceilings while a
	 * stricter one from the same record went unstated, and returns them for publication.
	 *
	 * @param patient whose answer it is — logged so a line is attributable under concurrent requests
	 * @param answer the answer prose, unchanged by this method
	 * @param cited the references the answer cites, as resolved by
	 *            {@link LlmInferenceService#extractCitedReferences}. A citation the MODULE attached
	 *            (issue #305) needs no filter here, and the reason is stated rather than deferred to
	 *            a sibling — the one that used to carry it, {@code SafetyFindingSeverityFidelityCheck},
	 *            now delegates the question instead (issue #409 round two): the {@code ceilings} map
	 *            below holds only records carrying a {@code dosingCeilings} list, which on the
	 *            production path {@code DrugReferenceInjector}'s reference loop alone writes
	 * @param mappings the chart's records, cited or not — the carrier of each cited record's
	 *            ceilings
	 * @return one {@link UnstatedDosingCeiling} per offending citation, in CITATION order —
	 *         {@code cited}'s own order, taken rather than re-derived so that "which records were
	 *         cited, and in what order" has one answer. Empty when the check ran and found none, and
	 *         null only when the check itself failed.
	 *
	 *         <p>The set the walk de-duplicates on is belt and braces rather than load-bearing, the
	 *         same state {@code SafetyFindingSeverityFidelityCheck}'s own {@code @return} records of
	 *         its: {@code LlmInferenceService.extractCitedReferences} already collects indexes into a
	 *         {@code LinkedHashSet} and emits one reference per index, so {@code cited} cannot carry
	 *         a repeat today. Said so the guard does not look better defended than it is — swap the
	 *         set for a list that always adds and nothing reddens. What it still decides is that one
	 *         citation yields at most one entry, which is what lets a consumer treat
	 *         {@code citation} as a key.
	 */
	static List<UnstatedDosingCeiling> reportUnstatedDosingCeilings(Patient patient, String answer,
			List<RecordReference> cited, List<RecordMapping> mappings) {
		Integer patientId = null;
		try {
			// Inside the guard, not above it: reading a detached patient proxy is the one line here
			// that could throw, and the promise this catch makes is structural or it is nothing.
			patientId = patient == null ? null : patient.getPatientId();
			List<UnstatedDosingCeiling> offending = new ArrayList<UnstatedDosingCeiling>();
			if (cited == null || cited.isEmpty() || mappings == null
					|| ChartSearchAiUtils.isBlank(answer)) {
				// The blank arm is REACHABLE rather than defensive; the class javadoc's conservatism
				// list says why, and points at extractCitedReferences' own javadoc for the shape.
				return offending;
			}
			// The map is the GATE as well as the lookup, and it holds only records with something to
			// compare: a record stating fewer than two ceilings cannot have a stricter one gone
			// unstated, so it is dropped here rather than re-tested per citation. On the shipped
			// default `chartsearchai.drugReference.enabled` is false, the injector never runs, and
			// this map is empty before the answer is read at all.
			//
			// `> 1` and the `at = 1` below are FILTERS and not guards, said rather than left to look
			// tested: both were mutated (`> 0`, `at = 0`) and neither reddens anything, because
			// neither can change an answer. A one-ceiling record admitted here runs an inner loop
			// with no iterations, and index 0 is the ceiling the walk has just found unstated, so
			// starting there merely asks the memo for it twice. What they buy is work, not
			// correctness. The correctness is the ORDER — mutate it and read the failures.
			Map<Integer, List<String>> ceilings = new HashMap<Integer, List<String>>();
			for (RecordMapping mapping : mappings) {
				List<String> stated = mapping.getDosingCeilings();
				if (stated != null && stated.size() > 1) {
					ceilings.put(Integer.valueOf(mapping.getIndex()), stated);
				}
			}
			if (ceilings.isEmpty()) {
				return offending;
			}
			// Memoised per distinct ceiling PHRASE, in a per-call local and never a field (#172 binds
			// this module's memos, and a static utility on a Spring-managed path is no exception).
			// One substance's rows are read once however many citations name them, and two records
			// of one substance share the needle rather than each paying for it.
			Map<String, Boolean> stated = new HashMap<String, Boolean>();
			Set<Integer> seen = new LinkedHashSet<Integer>();
			for (RecordReference citation : cited) {
				List<String> published = ceilings.get(Integer.valueOf(citation.getIndex()));
				if (published == null) {
					// Either the cited record is not one this module injected for a reference entry,
					// or it states fewer than two ceilings. This check deliberately cannot tell those
					// cases apart: what reaches it is one nullable list.
					continue;
				}
				// Position 0 is the strictest, decided by the writer while the numbers were doubles.
				// Never sorted here — see the class javadoc for what a string sort would silence.
				String strictest = published.get(0);
				if (answerStates(answer, strictest, stated)) {
					continue;
				}
				// Up from the strictest, so the FIRST one found is the strictest the answer states,
				// and every ceiling between it and `strictest` has already been found unstated. That
				// is what makes UnstatedDosingCeiling's "strictly stricter" structural.
				for (int at = 1; at < published.size(); at++) {
					String quoted = published.get(at);
					if (!answerStates(answer, quoted, stated)) {
						continue;
					}
					if (seen.add(Integer.valueOf(citation.getIndex()))) {
						offending.add(new UnstatedDosingCeiling(citation.getIndex(), quoted, strictest));
					}
					break;
				}
			}
			if (!offending.isEmpty()) {
				// Each citation reads beside both numbers: a maintainer triaging this needs to know
				// how far apart they were. The WARN is the PUBLISHED list itself, so the log and the
				// wire cannot disagree about which triples they state — one list, not two collections
				// appended in step. Their FORMATS are separate and deliberately so: the log takes
				// each entry's `toString` while the wire's key names are literals in the controller,
				// which is where a documented key belongs.
				// Neither the answer nor any other part of the record is logged — they carry patient
				// data and operator free text, and the citation with the patient identifies the
				// claim. The ceilings are the dataset's own reference material and say nothing about
				// this patient.
				log.warn("Answer for patient={} quoted a dosing ceiling and left a stricter one from "
						+ "the same cited record unstated: {}. The answer prose is left unchanged "
						+ "(issue #276).", patientId, offending);
			}
			return offending;
		}
		catch (RuntimeException e) {
			// A diagnostic must never break a clinical answer — the same promise its siblings make,
			// made structurally rather than by inspection, and loudly, so the failure of the check is
			// not itself silent. Null rather than an empty list: the caller publishes this, and "no
			// measurement" is not "none".
			log.warn("Dosing-ceiling check failed for patient={}; the answer is unaffected: {}",
					patientId, e.toString());
			return null;
		}
	}

	/**
	 * @return whether {@code answer} states {@code ceiling}, through the scan this module shares for
	 *         that question, memoised in the caller's own per-call map. Extracted so the strictest
	 *         ceiling and the quoted one cannot come to be asked two different ways — two call sites
	 *         in one short loop, which is exactly how that drift starts.
	 */
	private static boolean answerStates(String answer, String ceiling, Map<String, Boolean> memo) {
		Boolean known = memo.get(ceiling);
		if (known == null) {
			known = Boolean.valueOf(ChartSearchAiUtils.statesMeasurement(answer, ceiling));
			memo.put(ceiling, known);
		}
		return known.booleanValue();
	}
}
