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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.InteractionClaimPairs;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports an <em>"X interacts with active order Y"</em> claim whose pair no finding of the module's
 * relates — issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/514">#514</a>.
 * A deterministic, exact comparison like its siblings: no model call, no embedding.
 *
 * <p><b>The failure.</b> On the external DDI evaluation's demo patients (chartsearchai {@code main}
 * @ {@code 627449a7}, Gemma 4 E2B, each byte-identical across two runs), a question listing her
 * medications before asking about a new drug put findings about several subjects in the prompt, and
 * the answer gave one drug's finding to another — <em>"Metformin interacts with active order
 * Lamivudine / zidovudine … [353]"</em>, [353] being Stavudine's finding — or stated a Metformin pair
 * no finding raised, citing nothing. {@code misattributedOrderCitations},
 * {@code unfaithfullyRenderedCitations} and {@code unstatedFindingSeverities} read {@code []} or
 * flagged something else.
 *
 * <p><b>The unit is {@link ActiveOrderCitationFidelityCheck#claims}'s</b>: an occurrence of
 * {@code DrugSafetyValidator.ACTIVE_ORDER_NOUN}, the words before it in its clause as the SUBJECT,
 * the words after it up to its marker run (or clause bound) as the PARTNER, and that run as what it
 * cites. One walk, so this check and that one cannot disagree about which claims the answer made or
 * which markers each offered. A marker past the claim's comma is not in its run — the ticket's cases
 * 2 and 4 put it there — and a claim with no run of its own takes the findings of the first run past
 * its clause on two gates only: the words between the comma and that run name no drug any finding
 * names, and the finding names the claim's PARTNER. Each gate is against the report ADR Decision 76
 * calls crying wolf, a later clause's own correct citation: the first where that clause names another
 * drug a finding carries, the second where it names one no finding carries. A claim neither gate lets
 * through is judged as citing nothing, and is still reported where no finding relates its pair.
 *
 * <p><b>What a finding relates is read structurally, never from its text.</b> Every name a finding
 * goes by: its subject ({@link ChartSearchAiUtils#findingSubject} on a record,
 * {@link SafetyWarning#getDrug()} on a chip), the orders it names
 * ({@link RecordMapping#getFindingPartners()}, {@link SafetyWarning#namedPartners()}), and the
 * prescriptions and substances its chart-order clause resolved them from
 * ({@link RecordMapping#getFindingBridgeNames()}, {@link SafetyWarning#chartOrderBridges()}) — the
 * last because a brand-named order's finding gives the drug a second name the model may use (#349).
 * A finding relates a claim when each side of it names one of those names, whichever way round: an
 * interaction relates two drugs whichever the sentence leads with, the screening arm's two drugs are
 * both her orders, and issue #477's findings relate two of her orders to each other. So two orders one
 * finding names read as related, and a claim pairing two orders of a merged finding is not reported —
 * {@link #anyRelates} says why that is the direction chosen.
 *
 * <p><b>Two answers.</b>
 * <ul>
 *   <li>A claim whose run — or the trailing run it takes — cites findings, none of which relates its
 *       pair: every one of them is MISATTRIBUTED. A run citing one that relates and one that does not
 *       is silent.</li>
 *   <li>A claim whose run cites no finding, and whose pair no finding in the prompt and no chip beside
 *       the answer relates: UNFOUNDED. The chips count because a drug only the answer names is put in
 *       play after the answer, and a pair the module did raise is not one "no finding raised".</li>
 * </ul>
 *
 * <p><b>Conservative by construction</b>, since a check that cries wolf is worse than none — each of
 * these makes a claim UNJUDGED, outside all three published numbers:
 * <ul>
 *   <li>the subject side names no drug any interaction or condition-mediated finding or chip names —
 *       a pronoun, a brand the findings do not carry, a misspelling. A side names a name by
 *       containment in
 *       {@link FindingPartnerCoverageCheck#comparable} form, the form that class asks "did the answer
 *       name this order" in, so one question has one comparison;</li>
 *   <li>the partner side is blank;</li>
 *   <li>the run cites a reference record that is not a relating finding — a {@code drug_reference}
 *       monograph states interactions no finding raises (#357 renders a sub-floor rule naming her
 *       order in its tail), and this check reads no reference text;</li>
 *   <li>a finding naming no order — a class-only relationship, whose partner is a class — is about a
 *       drug either side names, among the findings the claim is judged against: those it cites, or
 *       where it cites none, every finding and chip. Whether it meant the claim's partner cannot be
 *       read.</li>
 * </ul>
 * Contraindication and overdose findings relate no drug to an order, so they are not in the
 * population an uncited claim is judged against — and a run citing one is the reference-record case
 * above: a finding is reference material, and one that relates no drug to an order is not a relating
 * one.
 *
 * <p><b>What it cannot see.</b> Containment reads a short name inside a longer one as the same drug —
 * <em>Lamivudine</em> inside <em>Lamivudine / zidovudine</em> — so a swap between those two passes.
 * An invented pair whose subject is a drug no finding names is unjudged. The partner side runs the
 * other way, because it is ungated: a partner the answer names by a brand or a paraphrase no finding
 * prints reads as unrelated and can be REPORTED — the bridge names are what keep a brand-named
 * prescription's own display out of that case. A claim pairing two orders one finding names reads as
 * related, so an order put in for a merged finding's subject passes — {@link #anyRelates} says why.
 * The trailing-run gates read names the findings carry, so a later clause naming another drug only by
 * a name no finding prints, citing a finding that names the claim's partner, is taken for the claim.
 * And a claim not written in the
 * active-order form — the ticket's first case, <em>"a caution to note regarding interactions with
 * Lopinavir / ritonavir, Didanosine, and Nevirapine [288], [290]"</em> — is not a claim to this check
 * at all. ADR Decision 117 records them.
 *
 * <p><b>It reports and it publishes</b> — the WARN for a maintainer, carrying the citations and the
 * counts and never a drug name (the names are this patient's medications, and core ships
 * {@code org.openmrs} at WARN: ADR Decision 102), and {@code ChartAnswer.getInteractionClaimPairs()}
 * for a client. It never rewrites the answer.
 *
 * <p><b>Where it runs.</b> Both answer paths, over the MODEL's prose and after the post-answer chips
 * exist; never over the module-composed answer (#469), which no model wrote, nor on the
 * async-grounding early {@code done}, which is handed off before the chips exist.
 */
final class InteractionClaimPairFidelityCheck {

	private static final Logger log = LoggerFactory.getLogger(InteractionClaimPairFidelityCheck.class);

	private InteractionClaimPairFidelityCheck() {
	}

	/**
	 * Judges every active-order claim in {@code answer} against the findings that relate its pair,
	 * reports at WARN what it found, and returns it for publication.
	 *
	 * @param patient whose answer it is — its id is logged, never a name
	 * @param answer the MODEL's answer prose, unchanged by this method
	 * @param cited the references the answer cites, as resolved by
	 *            {@link LlmInferenceService#extractCitedReferences}
	 * @param mappings the chart's records — the carrier of the injected findings
	 * @param chips the post-answer safety warnings, raised over this answer; null reads as none
	 * @return the statement — {@code judged: 0} and nothing reported where the answer stated no claim —
	 *         or null only when the check itself failed
	 */
	static InteractionClaimPairs examine(Patient patient, String answer, List<RecordReference> cited,
			List<RecordMapping> mappings, List<SafetyWarning> chips) {
		Integer patientId = null;
		try {
			patientId = patient == null ? null : patient.getPatientId();
			List<ActiveOrderCitationFidelityCheck.Claim> claims = ActiveOrderCitationFidelityCheck.claims(answer);
			if (claims.isEmpty()) {
				return new InteractionClaimPairs(0, Collections.<Integer> emptyList(), 0);
			}
			Map<Integer, Finding> citableFindings = new HashMap<Integer, Finding>();
			List<Finding> population = new ArrayList<Finding>();
			for (RecordMapping record : ChartSearchAiUtils.safetyFindingMappings(mappings)) {
				Finding finding = Finding.of(record);
				citableFindings.put(Integer.valueOf(record.getIndex()), finding);
				if (finding.relatesDrugs) {
					population.add(finding);
				}
			}
			if (chips != null) {
				for (SafetyWarning chip : chips) {
					Finding finding = Finding.of(chip);
					if (finding.relatesDrugs) {
						population.add(finding);
					}
				}
			}
			Set<String> vocabulary = new HashSet<String>();
			for (Finding finding : population) {
				vocabulary.addAll(finding.names);
			}
			Map<Integer, RecordMapping> byIndex = new HashMap<Integer, RecordMapping>();
			if (mappings != null) {
				for (RecordMapping mapping : mappings) {
					byIndex.put(Integer.valueOf(mapping.getIndex()), mapping);
				}
			}
			Set<Integer> admitted = ActiveOrderCitationFidelityCheck.admittedIndexes(cited);
			// The ONE reading of which findings the answer cited (#409), shared with findingCitations —
			// the markers the prose anchors, intersected with the resolution and with the carried findings.
			Set<Integer> citedFindings = SafetyFindingCitationExtentCheck.citedFindingIndexes(answer, cited,
					mappings);

			int judged = 0;
			int unfounded = 0;
			int misattributedClaims = 0;
			Set<Integer> misattributed = new LinkedHashSet<Integer>();
			for (ActiveOrderCitationFidelityCheck.Claim claim : claims) {
				Set<String> subjectNames = namedIn(FindingPartnerCoverageCheck.comparable(claim.subject()),
						vocabulary);
				String partner = normalized(claim.partner());
				if (subjectNames.isEmpty() || partner.isEmpty()) {
					continue;
				}
				List<Finding> runFindings = new ArrayList<Finding>();
				List<Integer> runIndexes = new ArrayList<Integer>();
				boolean unreadable = false;
				for (Integer index : claim.admittedRunIndexes(admitted)) {
					Finding finding = citedFindings.contains(index) ? citableFindings.get(index) : null;
					if (finding != null && finding.relatesDrugs) {
						runFindings.add(finding);
						runIndexes.add(index);
					}
					else if (isReferenceMaterial(byIndex.get(index))) {
						// Reference material other than a relating finding — a finding of a type relating
						// no drug to an order is reference material too — so what the claim offered
						// cannot be judged from here.
						unreadable = true;
					}
				}
				// A claim with no run of its own takes a finding marker past its clause only on two gates,
				// each against a false report Decision 76 names: nothing between the clause break and the
				// marker names a drug any finding names — a later clause about another drug carries its own
				// citation — and the finding names the claim's PARTNER, the evidence the marker is about this
				// claim. Round 1 of #514's review: without it the ticket's own cases 2 and 4, whose markers
				// sit after a comma, named no citation.
				if (namedIn(FindingPartnerCoverageCheck.comparable(claim.trailingGap()), vocabulary).isEmpty()) {
					for (Integer index : claim.admittedTrailingRunIndexes(admitted)) {
						Finding finding = citedFindings.contains(index) ? citableFindings.get(index) : null;
						if (finding != null && finding.relatesDrugs && matchesAny(partner, finding.names)) {
							runFindings.add(finding);
							runIndexes.add(index);
						}
					}
				}
				List<Finding> candidates = runFindings.isEmpty() ? population : runFindings;
				if (unreadable || anyUndecidable(candidates, subjectNames, partner)) {
					continue;
				}
				judged++;
				if (anyRelates(candidates, subjectNames, partner)) {
					continue;
				}
				if (runFindings.isEmpty()) {
					unfounded++;
				}
				else {
					misattributedClaims++;
					misattributed.addAll(runIndexes);
				}
			}
			if (!misattributed.isEmpty() || unfounded > 0) {
				// The citations and the counts, never a name: a claim's names are this patient's
				// medications, and core ships org.openmrs at WARN (ADR Decision 102, issue #439).
				log.warn("Answer for patient={} states {} active-order claim(s) no cited finding relates "
						+ "the pair of — cited {} — and {} whose pair no finding relates at all. The answer "
						+ "prose is left unchanged (issue #514).", patientId,
						Integer.valueOf(misattributedClaims), misattributed,
						Integer.valueOf(unfounded));
			}
			return new InteractionClaimPairs(judged, new ArrayList<Integer>(misattributed), unfounded);
		}
		catch (RuntimeException e) {
			// A diagnostic must never break a clinical answer — the promise its siblings make, made
			// structurally, and loudly. Null: "no measurement" is not "none".
			log.warn("Interaction-claim pair check failed for patient={}; the answer is unaffected: {}",
					patientId, e.toString());
			return null;
		}
	}

	/**
	 * @return the vocabulary names {@code span} contains — one direction only, so a span names a drug
	 *         by stating it and never by sitting inside a longer name. No case constructs a subject
	 *         span that a name contains — a subject span carries the words before the noun, verb
	 *         included — so no case pins the direction. It decides an EMPTY span, which every name
	 *         contains.
	 */
	private static Set<String> namedIn(String span, Set<String> vocabulary) {
		Set<String> named = new HashSet<String>();
		for (String name : vocabulary) {
			if (span.contains(name)) {
				named.add(name);
			}
		}
		return named;
	}

	/** @return {@code text} in the one form every name and span here is compared in —
	 *          {@link FindingPartnerCoverageCheck#comparable}, trimmed — and empty for null. The SUBJECT
	 *          span is compared untrimmed, only ever searched by containment. */
	private static String normalized(String text) {
		return text == null ? "" : FindingPartnerCoverageCheck.comparable(text).trim();
	}

	/** @return whether two names, in comparable form, are read as naming one drug: either contains the
	 *          other — so a partner span running on past the name still matches it, and a partner named
	 *          by the front of a longer name the finding carries ({@code coumadin} of
	 *          {@code coumadin 5mg}) does too. That second direction is also what reads
	 *          {@code lamivudine} as {@code lamivudine/zidovudine}. */
	private static boolean sameDrug(String one, String other) {
		return one.contains(other) || other.contains(one);
	}

	private static boolean matchesAny(String name, Set<String> names) {
		for (String candidate : names) {
			if (sameDrug(name, candidate)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return whether one of {@code findings} relates the claim's two sides: each side names one of the
	 *         finding's names — its subject, an order it names, or a bridge name — whichever way round.
	 *         Two of its ORDERS count as a pair it relates, deliberately: issue #477's findings (a drug
	 *         already in several of her orders, orders sharing a substance) state exactly that relation,
	 *         and nothing on the record tells them from a merged finding stating each order against its
	 *         subject, so refusing the pair would call a verbatim copy of them misattributed. The cost is
	 *         the other direction: a claim pairing two orders of a merged finding is not reported.
	 */
	private static boolean anyRelates(List<Finding> findings, Set<String> subjectNames, String partner) {
		for (Finding finding : findings) {
			if (finding.namesAnOrder && matchesAny(partner, finding.names)
					&& namesAny(subjectNames, finding.names)) {
				return true;
			}
		}
		return false;
	}

	/** @return whether any of {@code named} is read as one of {@code names} */
	private static boolean namesAny(Set<String> named, Set<String> names) {
		for (String name : named) {
			if (matchesAny(name, names)) {
				return true;
			}
		}
		return false;
	}

	/** @return whether a finding naming no order is about a drug either side of the claim names — the
	 *          class-only relationship, whose partner is a class this check cannot compare */
	private static boolean anyUndecidable(List<Finding> findings, Set<String> subjectNames, String partner) {
		for (Finding finding : findings) {
			if (finding.namesAnOrder) {
				continue;
			}
			if (sameDrug(partner, finding.subject)) {
				return true;
			}
			if (namesAny(subjectNames, Collections.singleton(finding.subject))) {
				return true;
			}
		}
		return false;
	}

	/** @return whether {@code mapping} is this module's own reference material — asked of
	 *          {@code referenceGroup} and never of a type name (#122) */
	private static boolean isReferenceMaterial(RecordMapping mapping) {
		return mapping != null && ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE
				.equals(ChartSearchAiUtils.referenceGroup(mapping.getResourceType()));
	}

	/** One finding, record or chip, as the names it goes by in comparable form. */
	private static final class Finding {

		/** Whether the finding's type relates a drug to an order at all. */
		private final boolean relatesDrugs;

		/** Whether it names an order structurally — false for the class-only relationship. */
		private final boolean namesAnOrder;

		private final String subject;

		/** Every name it goes by — its subject, the orders it names, its bridge names. */
		private final Set<String> names;

		private Finding(String type, String subject, List<String> partners, List<String> bridgeNames) {
			this.relatesDrugs = SafetyWarning.TYPE_INTERACTION.equals(type)
					|| SafetyWarning.TYPE_CONDITION_MEDIATED.equals(type);
			this.namesAnOrder = !partners.isEmpty();
			this.subject = normalized(subject);
			Set<String> all = new LinkedHashSet<String>();
			add(all, subject);
			for (String partner : partners) {
				add(all, partner);
			}
			for (String name : bridgeNames) {
				add(all, name);
			}
			this.names = all;
		}

		private static void add(Set<String> names, String name) {
			String comparable = normalized(name);
			if (!comparable.isEmpty()) {
				names.add(comparable);
			}
		}

		static Finding of(RecordMapping record) {
			return new Finding(ChartSearchAiUtils.findingType(record), ChartSearchAiUtils.findingSubject(record),
					record.getFindingPartners(), record.getFindingBridgeNames());
		}

		static Finding of(SafetyWarning chip) {
			return new Finding(chip.getType(), chip.getDrug(), chip.namedPartners(),
					SafetyWarning.ChartOrderBridge.namesOf(chip.chartOrderBridges()));
		}
	}
}
