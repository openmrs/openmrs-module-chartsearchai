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
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.FindingCitationExtent;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures how many injected safety findings the prompt carried against how many the answer cited —
 * issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/395">#395</a>. A
 * deterministic count: no model call, no embedding, no cosine floor, no scan of the answer's prose
 * at all beyond deciding whether there is any.
 *
 * <p><b>The failure.</b> Measured live on a RefApp 3.7.1 standalone against the bundled knowledge
 * base, with the drug-reference layer enabled and {@code chartMode=fullChart}, two runs
 * byte-identical. A <em>"should i give Amlodipine?"</em> answer opened <em>"No — Amlodipine should
 * not be given"</em> and enumerated six active orders, each carrying its own rating and its own
 * citation. The screen had raised SEVEN interaction findings and the injector had written all seven
 * into the prompt, each at its own citation index; the seventh — Amlodipine against her active
 * {@code Advil 400mg}, Moderate — reached the prose nowhere. A clinician reading the answer got six
 * reasons to withhold where the module had screened seven, with nothing saying a seventh existed.
 *
 * <p><b>Why nothing else can see it.</b> Its four neighbours all judge a finding the answer DID
 * cite. {@link SafetyFindingSeverityFidelityCheck} asks whether a cited finding's rating reached the
 * prose, and on that answer it correctly read {@code []} — it asks of the WHOLE answer and
 * <em>Moderate</em> appeared six times, so a seventh Moderate finding dropped entirely is invisible
 * to it by construction. {@link ReferenceProseFidelityCheck} reports a SUBSTITUTION inside a
 * reproduction and that answer reproduced nothing. {@link ActiveOrderCitationFidelityCheck} judges
 * the citations a claim offered, and a finding that made no claim offered none.
 * {@link ClassCodeFidelityCheck} compares one ATC token shape. The residue was already named, in
 * {@code SafetyFindingSeverityFidelityCheck}'s own javadoc quoting the prose check's <em>"a hazard
 * dropped by stopping early"</em>; this is the half of it that citation makes deterministic.
 *
 * <p><b>What it compares.</b> Two populations it derives nothing itself:
 * <ul>
 *   <li>CARRIED — the {@code safety_finding} records in the chart the prompt was built from, which
 *       {@code DrugReferenceInjector}'s findings loop writes one-per-finding and filters not at all.
 *       Never the {@code safetyWarnings} chips, which are a different and usually larger population
 *       (seventeen against seven on the measured run) and which CLAUDE.md forbids as the source of
 *       an extent;</li>
 *   <li>CITED — the subset of those the answer's own resolution admitted, taken from
 *       {@link LlmInferenceService#extractCitedReferences} rather than re-derived from the markers,
 *       which is what keeps "which records did this answer cite" to one answer. A citation the
 *       MODULE attached (issue #305) is not one the answer made and is not counted; that filter is
 *       belt and braces on today's path, where only the two contraindication factories set the flag
 *       and they set it on chart records rather than findings, and it is here because the rule that
 *       a scorer counts the model's own citations is stated of this module generally.</li>
 * </ul>
 *
 * <p><b>A COUNT and deliberately not an accusation</b>, which is the one design decision in this
 * class. Over the unit of one finding the residues run in BOTH directions — an answer that states a
 * finding in prose and omits its marker would be falsely accused, and one that cites a marker while
 * saying nothing about it would be missed — and that is exactly the condition ADR Decision 81 gives
 * for publishing the base rather than a per-item accusation. Naming the uncited indexes on the WIRE
 * would make each of those residues a claim about a specific finding; naming them in the log makes
 * them a lead for a maintainer, which is what they are.
 *
 * <p><b>Conservative by construction</b>, for the reasons its siblings are:
 * <ul>
 *   <li>it reports nothing where the prompt carried no finding. On the shipped default
 *       {@code chartsearchai.drugReference.enabled} is false, so the injector never runs and this
 *       returns a zeroed statement before touching the citations;</li>
 *   <li>a BLANK or absent answer is silent — the WARN only. The extent is still STATED for it, and
 *       that difference from its siblings is deliberate: they judge prose and a degenerate output
 *       has none to judge, while this one counts citations and
 *       {@code extractCitedReferences} resolves the structured array for a blank answer on purpose.
 *       Counting what really did resolve is a fact; reporting it as a dropped hazard would not be;</li>
 *   <li>it never rewrites the answer, and it names no word of the answer or of any record — both
 *       carry patient data, the discipline {@link ClassCodeFidelityCheck} states. A citation index
 *       is the module's own bookkeeping.</li>
 * </ul>
 *
 * <p><b>What it cannot see</b>, stated rather than left to be found:
 * <ul>
 *   <li>whether a cited finding was stated CORRECTLY, or stated at all. That is the four
 *       neighbours' question, and {@code cited == carried} is therefore not a certificate;</li>
 *   <li>a finding the answer states in prose without citing it, which it counts as uncited, and a
 *       finding cited but never discussed, which it counts as cited. Both are why this publishes a
 *       base and not an accusation;</li>
 *   <li>whether an uncited finding MATTERED. The injector renders findings the screen raised, and
 *       not every one of them bears on the question the way the reported seventh did.</li>
 * </ul>
 *
 * <p><b>Where it runs.</b> Of {@link #measureFindingCitations}, which is this class's published
 * measurement: both answer paths, {@link LlmInferenceService#search} and {@code searchStreaming}, so
 * the endpoint users hit is covered. Not the progressive-reasoning preview, which discards its
 * answer and resolves no citations, and not a cached answer, which was measured when it was produced
 * — the same scoping its siblings state. {@link #carriedFindingIndexes} is this class's other
 * published projection of that population and has no production caller of its own; its javadoc is
 * canonical for what it is for, and for what {@code measureFindingCitations} shares with it rather
 * than calling it.
 * &rarr; ADR Decision 83.
 */
final class SafetyFindingCitationExtentCheck {

	private static final Logger log = LoggerFactory.getLogger(SafetyFindingCitationExtentCheck.class);

	private SafetyFindingCitationExtentCheck() {
	}

	/**
	 * The injected {@code safety_finding} records {@code mappings} carries, by citation index — the
	 * CARRIED population {@link #measureFindingCitations} counts. Issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a>.
	 *
	 * <p><b>The population is SELECTED by {@code ChartSearchAiUtils.safetyFindingMappings} and never
	 * by a walk spelled here</b> — that method is canonical for its null tolerance, its
	 * injection-order contract and why there is one selection at all; {@code findingSubjects} is the
	 * other projection off it.
	 *
	 * <p><b>Nothing in PRODUCTION calls this method, and that is stated rather than left to be
	 * found.</b> {@link LlmInferenceService#severalFindingsAboutOneDrug} counts the shared walk's own
	 * records, which is what its conjunct means; {@link #measureFindingCitations} shares
	 * {@link #indexesOf} instead, needing the walk the projection was taken from as well and not
	 * walking twice to get both. What this is, is the COMPOSED projection — the entry point the
	 * projection's own contract can be put to without a caller chaining the two production steps
	 * itself, and its reader is
	 * {@code FindingEnumerationClauseContextTest.theSharedWalkHandsTheFindingsBackInTheOrderTheInjectorWroteThem}'s
	 * content leg. Keep it composed if a production caller returns.
	 *
	 * <p>Keyed on the INDEX, the injector's own sequential numbering — one increment per finding
	 * record, in the sole producer of these mappings — so it is unique across a chart by
	 * construction and this set counts records rather than folding any. That is what the leg above
	 * observes, one entry per finding against this set.
	 *
	 * <p><b>Nothing reads this set's ITERATION ORDER either</b>, so the {@code LinkedHashSet} is a
	 * convenience and not a contract: a {@code HashSet} or a {@code TreeSet} here is INERT, measured,
	 * and a caller that comes to depend on the prompt's order brings its own pin. The order contract
	 * is the shared WALK's, pinned directly List against List by the case above, whose javadoc
	 * carries what that pin catches and what a collection substituted here cannot reach.
	 */
	static Set<Integer> carriedFindingIndexes(List<RecordMapping> mappings) {
		return indexesOf(ChartSearchAiUtils.safetyFindingMappings(mappings));
	}

	/** The citation indexes of {@code findings}, in the order given — the projection itself, shared
	 *  by the published accessor above and by {@link #measureFindingCitations}, which needs the walk
	 *  it was taken from as well and must not walk twice to get both. */
	private static Set<Integer> indexesOf(List<RecordMapping> findings) {
		Set<Integer> carried = new LinkedHashSet<Integer>();
		for (RecordMapping mapping : findings) {
			carried.add(Integer.valueOf(mapping.getIndex()));
		}
		return carried;
	}

	/**
	 * The carried findings the answer's own citation resolution did not admit, in the order the
	 * injector wrote them — issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/398">#398</a>.
	 *
	 * <p><b>It is the walk {@link #measureFindingCitations}'s WARN already did, named so a second
	 * caller cannot spell it differently.</b> That line and {@code LlmInferenceService}'s repair pass
	 * must be about the same findings or the log describes one population and the second prompt asks
	 * about another — the two-resolutions-that-agree shape
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/151">#151</a> forbids.
	 * The projection is off the shared WALK and never off the carried SET, so the order is the
	 * injector's pinned one rather than whatever iteration order a set happens to give; that order is
	 * what the repair's own question is composed in, and a maintainer reading the WARN beside it sees
	 * one list.
	 *
	 * <p><b>It answers about CITATION and not about prose.</b> A finding whose substance the answer
	 * names in words while citing no marker for it is uncited here, because {@code cited} is
	 * {@code LlmInferenceService.extractCitedReferences}' own output and that is the only reading of
	 * "the answer cited it" this module has. So a repair driven by this can ask again for a finding
	 * the answer did mention — fail-open toward asking, which costs a call and cannot lose content.
	 *
	 * @param cited the references the answer cites, as resolved by
	 *            {@code LlmInferenceService.extractCitedReferences}
	 * @param mappings the chart's records, the carrier of the carried population
	 * @return the uncited carried finding indexes, empty where the answer cited every one of them
	 *         and where the chart carried none
	 */
	static List<Integer> uncitedFindingIndexes(List<RecordReference> cited,
			List<RecordMapping> mappings) {
		List<RecordMapping> findings = ChartSearchAiUtils.safetyFindingMappings(mappings);
		return uncitedOf(findings, citedFindingIndexes(cited, indexesOf(findings)));
	}

	/** The projection both readers share: the walk's own findings, less the ones cited. The
	 *  {@code contains} guard keeps the list in step with the count, which is a set — defensive only,
	 *  the injector's numbering being unique per chart. */
	private static List<Integer> uncitedOf(List<RecordMapping> findings, Set<Integer> citedFindings) {
		List<Integer> uncited = new ArrayList<Integer>();
		for (RecordMapping finding : findings) {
			Integer index = Integer.valueOf(finding.getIndex());
			if (!citedFindings.contains(index) && !uncited.contains(index)) {
				uncited.add(index);
			}
		}
		return uncited;
	}

	/** Which of {@code carried} the answer's own resolution admitted. A citation the module attached
	 *  is not one the answer made (issue #305); the set de-duplicates, so one finding cited in two
	 *  sentences is one cited finding. Shared by the extent and by {@link #uncitedFindingIndexes} so
	 *  the count and the complement cannot disagree about what "cited" means. */
	private static Set<Integer> citedFindingIndexes(List<RecordReference> cited, Set<Integer> carried) {
		Set<Integer> citedFindings = new LinkedHashSet<Integer>();
		if (cited != null) {
			for (RecordReference citation : cited) {
				Integer index = Integer.valueOf(citation.getIndex());
				if (!citation.isAttachedByTheModule() && carried.contains(index)) {
					citedFindings.add(index);
				}
			}
		}
		return citedFindings;
	}

	/**
	 * Counts the injected safety findings the prompt carried and the ones {@code answer} cited,
	 * reporting at WARN when it cited fewer.
	 *
	 * @param patient whose answer it is — logged so a line is attributable under concurrent requests
	 * @param answer the answer prose, unchanged by this method and read only to tell a degenerate
	 *            output from a real one, which gates the WARN and never the count
	 * @param cited the references the answer cites, as resolved by
	 *            {@link LlmInferenceService#extractCitedReferences}
	 * @param mappings the chart's records, cited or not — the carrier of the CARRIED population
	 * @return the extent, or null only when the check itself failed. Never null for a chart carrying
	 *         no finding: that is a zeroed statement, and {@code FindingCitationExtent} is canonical
	 *         for the difference
	 */
	static FindingCitationExtent measureFindingCitations(Patient patient, String answer,
			List<RecordReference> cited, List<RecordMapping> mappings) {
		Integer patientId = null;
		try {
			// Inside the guard, not above it: reading a detached patient proxy is the one line here
			// that could throw, and the promise this catch makes is structural or it is nothing.
			patientId = patient == null ? null : patient.getPatientId();
			List<RecordMapping> findings = ChartSearchAiUtils.safetyFindingMappings(mappings);
			Set<Integer> carried = indexesOf(findings);
			if (carried.isEmpty()) {
				// The cheapest gate first, as every sibling resolves its own: on the shipped default
				// the injector never runs, so this is the ordinary path and it must not walk the
				// citations to learn it had nothing to count.
				return new FindingCitationExtent(0, 0);
			}
			// Shared with uncitedFindingIndexes, so the count and its complement cannot come to
			// disagree about what "cited" means (issue #398).
			Set<Integer> citedFindings = citedFindingIndexes(cited, carried);
			if (citedFindings.size() < carried.size() && !ChartSearchAiUtils.isBlank(answer)) {
				List<Integer> uncited = uncitedOf(findings, citedFindings);
				// Neither the answer nor any record text is logged — they carry patient data, and the
				// citation with the patient identifies the claim. The indexes are this module's own
				// numbering of its own injected records and say nothing about the patient.
				log.warn("Answer for patient={} cites {} of {} injected safety finding(s); "
						+ "not cited: {}. The answer prose is left unchanged (issue #395).",
						patientId, Integer.valueOf(citedFindings.size()),
						Integer.valueOf(carried.size()), uncited);
			}
			return new FindingCitationExtent(carried.size(), citedFindings.size());
		}
		catch (RuntimeException e) {
			// A diagnostic must never break a clinical answer — the same promise its siblings make,
			// made structurally rather than by inspection, and loudly, so the failure of the check is
			// not itself silent. Null rather than a zeroed statement: the caller publishes this, and
			// "no measurement" is not "carried none".
			//
			// Deliberately UNPINNED, and said so rather than left to look defended: every accessor
			// this check reads is read by an earlier step on the production path —
			// extractCitedReferences indexes the mappings by getIndex() and ChartSearchAiUtils
			// .referenceSlice walks getResourceType() over all of them before any answer exists — so
			// no chart-shaped arrangement reaches this catch, and a test that forced one would be
			// pinning the order of the checks rather than this promise.
			log.warn("Finding-citation extent failed for patient={}; the answer is unaffected: {}",
					patientId, e.toString());
			return null;
		}
	}
}
