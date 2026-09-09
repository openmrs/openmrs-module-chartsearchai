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
 * — the same scoping its siblings state. {@link #carriedFindingIndexes} also runs on the
 * prompt-assembly path, in BOTH answer methods, and there it runs before any answer exists: issue
 * #397 extracted it out of {@code measureFindingCitations} so that path could ask this population
 * the question it needs. Its own javadoc is canonical for that.
 * &rarr; ADR Decision 83.
 */
final class SafetyFindingCitationExtentCheck {

	private static final Logger log = LoggerFactory.getLogger(SafetyFindingCitationExtentCheck.class);

	private SafetyFindingCitationExtentCheck() {
	}

	/**
	 * The injected {@code safety_finding} records {@code mappings} carries, by citation index — the
	 * CARRIED population this check counts, and the one thing about an assembled chart that says
	 * whether the prompt asked the model to enumerate anything.
	 *
	 * <p><b>ONE walk, and it is shared rather than spelled twice —
	 * {@code ChartSearchAiUtils.safetyFindingMappings}, which is where the population is SELECTED
	 * and where its null tolerance and injection-order contract live.</b> This method is one
	 * PROJECTION of that walk and {@code ChartSearchAiUtils.findingSubjects} is the other;
	 * {@link LlmInferenceService#severalFindingsAboutOneDrug} composes the two in the same request —
	 * the chart local is live at both points, which an earlier draft of that method's javadoc denied
	 * — needing only {@code size() > 1} of this one, while its second conjunct asks a different
	 * question of the same records and is not this one narrowed. Both projections once opened with
	 * their own copy of the type test, character for character, and were folded into the shared walk
	 * for what this sentence says: two spellings would let a filter added to one drift from the other
	 * silently, so that the prompt asks for an enumeration of a population this key then counts
	 * differently. Issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a>.
	 *
	 * <p>A LinkedHashSet over that order, so the uncited indexes the WARN lists read in the order
	 * the prompt carried them rather than in whatever order a hash gives — a maintainer comparing
	 * the line against the prompt is reading down one list. <b>The ORDER half of that is pinned;
	 * the COLLECTION TYPE's own contribution to it is not, and the two are different claims.</b>
	 * {@code FindingEnumerationClauseContextTest.theCarriedIndexesReadInTheOrderTheInjectorWroteTheFindings}
	 * reddens on a reversal of the shared walk, which is what used to be invisible. Substituting a
	 * {@code HashSet} here leaves it green — measured — because that chart's indexes are small
	 * enough that a hash set iterates ascending anyway. Keyed on the INDEX, which is the
	 * injector's own sequential numbering and unique across a chart by construction, so the set
	 * counts records and is not silently folding any — which is also why the shared walk hands back
	 * a List and leaves each projection its own collapse.
	 */
	static Set<Integer> carriedFindingIndexes(List<RecordMapping> mappings) {
		Set<Integer> carried = new LinkedHashSet<Integer>();
		for (RecordMapping mapping : ChartSearchAiUtils.safetyFindingMappings(mappings)) {
			carried.add(Integer.valueOf(mapping.getIndex()));
		}
		return carried;
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
			Set<Integer> carried = carriedFindingIndexes(mappings);
			if (carried.isEmpty()) {
				// The cheapest gate first, as every sibling resolves its own: on the shipped default
				// the injector never runs, so this is the ordinary path and it must not walk the
				// citations to learn it had nothing to count.
				return new FindingCitationExtent(0, 0);
			}
			Set<Integer> citedFindings = new LinkedHashSet<Integer>();
			if (cited != null) {
				for (RecordReference citation : cited) {
					// A citation the module attached is not one the answer made (issue #305). The set
					// de-duplicates, so one finding cited in two sentences is one cited finding —
					// belt and braces, since extractCitedReferences already emits one reference per
					// index, and said so the guard does not look better defended than it is.
					Integer index = Integer.valueOf(citation.getIndex());
					if (!citation.isAttachedByTheModule() && carried.contains(index)) {
						citedFindings.add(index);
					}
				}
			}
			if (citedFindings.size() < carried.size() && !ChartSearchAiUtils.isBlank(answer)) {
				List<Integer> uncited = new ArrayList<Integer>();
				for (Integer index : carried) {
					if (!citedFindings.contains(index)) {
						uncited.add(index);
					}
				}
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
