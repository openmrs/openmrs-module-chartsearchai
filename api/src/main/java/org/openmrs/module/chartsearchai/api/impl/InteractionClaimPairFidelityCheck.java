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
import java.util.Arrays;
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
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
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
 * {@code DrugSafetyValidator.ACTIVE_ORDER_NOUN}, the words before it in its clause as the SUBJECT — from
 * past a lead's colon or spaced dash ({@link #afterItsLead}), and no further back than the end of the
 * previous claim's partner span or run —
 * the words after it up to its marker run (or clause bound) as the PARTNER, and that run as what it
 * cites. One walk, so this check and that one cannot disagree about which claims the answer made or
 * which markers each offered. A marker past the claim's comma is not in its run — the ticket's cases
 * 2 and 4 put it there — and is never taken for the claim, which is judged as citing nothing. Taken on
 * gates reading the words before it, it accused a later clause's own correct citation, the report ADR
 * Decision 76 calls crying wolf, where that clause named its drug by a word no finding prints in a verb
 * other than the phrase's (<em>"…, and the other statin does too [6]"</em>, round 2 of #514's third
 * review). A finding it would have taken that relates the pair is in the population an uncited claim is
 * judged against, so such a claim is reported only where no finding relates its pair — as unfounded.
 *
 * <p><b>What a finding relates is read structurally, never from its text.</b> Every name a finding
 * goes by: its subject ({@link ChartSearchAiUtils#findingSubject} on a record,
 * {@link SafetyWarning#getDrug()} on a chip), the orders it names
 * ({@link RecordMapping#getFindingPartners()}, {@link SafetyWarning#namedPartners()}), and the names
 * its drugs go by through her orders ({@link RecordMapping#getFindingBridgeNames()},
 * {@link SafetyWarning#orderNamesOf}) — the prescriptions and substances its chart-order clause
 * resolved them from, because a brand-named order's finding gives the drug a second name the model may
 * use (#349), and the display of every order its arm matched a drug against, because a finding names
 * its partner by the knowledge base's label (<em>Rifampicin (rifampin)</em>) while the chart's record of
 * the order prints its display (<em>Rifampicin 300mg capsule</em>), and the finding states no
 * chart-order clause where that display names the substance (round 4 of #514's review) — and beside
 * each display the label of every substance that order resolves, for the mirror: a finding naming its
 * partner by the order's display (<em>Isoniazid / pyrazinamide / rifampin</em>) while the prompt's
 * findings about rifampicin print <em>Rifampicin (rifampin)</em> (round 4 of the fourth review).
 * A finding relates a subject to a partner when each names one of those names, whichever way round: an
 * interaction relates two drugs whichever the sentence leads with, the screening arm's two drugs are
 * both her orders, and issue #477's findings relate two of her orders to each other. So two orders one
 * finding names read as related, and a claim pairing two orders of a merged finding is not reported —
 * {@link #anyRelates} says why that is the direction chosen.
 *
 * <p><b>A partner is judged only where its span STARTS with a name the findings carry</b> — the owner's
 * decision on #514 after round 6 of its review, where <em>"… active order Rifampicin — Moderate
 * [4]"</em>, compared whole, accused its own finding. A known name is one of the findings' and chips'
 * names above; the span must begin, on a word's boundary, with the start of one or of one of its parts
 * ({@link #beginsAPartOf}), and whatever follows is ignored — a strength, a form, a severity, a full
 * stop ({@link #nameAt}). A span starting otherwise — a brand or paraphrase no finding prints, a word
 * before the name, a drug no finding or chip names — is left UNJUDGED, never compared whole. Only a
 * false report blocks this check; a missed one is a residue of ADR Decision 120.
 *
 * <p><b>A claim can state several pairs</b> (round 2 of #514's review). Every further drug the partner
 * span names that a finding or chip names is a PARTNER of it — <em>"active order Amiodarone and
 * Digoxin"</em> states two ({@link #partnersNamed}). Every such drug the SUBJECT span
 * names is a READING of its subject, and the claim is judged only where every reading reaches one
 * verdict: which drug of <em>"X can be given alongside Y but X interacts with…"</em> is the subject is
 * the one nearest the noun, and of <em>"…alongside Y but it interacts with…"</em> is not, the same
 * readings in the same positions — so taking any reading that relates passes a swap, and taking the
 * nearest accuses a correct citation.
 *
 * <p><b>Two answers.</b>
 * <ul>
 *   <li>A claim whose run cites findings, none of which relates its
 *       subject to any of its partners: every one of them is MISATTRIBUTED. A run citing one that
 *       relates and one that does not is silent.</li>
 *   <li>A claim citing no finding, or citing one that relates another of its partners, where a partner
 *       is related to its subject by no finding in the prompt and no chip beside the answer: UNFOUNDED —
 *       the invented partner, alone or beside a real one. The chips count because a drug only the answer
 *       names is put in play after the answer, and a pair the module did raise is not one "no finding
 *       raised".</li>
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
 *   <li>the partner span is blank, or does not start with a known name;</li>
 *   <li>the subject clause carries a word standing for a drug without naming it —
 *       {@link #SUBJECT_STAND_INS}, <em>it</em>, <em>this</em>, <em>which</em>, the <em>the</em> of
 *       <em>"the drug"</em> — since its subject may then be a drug named before its comma or in the
 *       sentence before, and the drug the clause does name is the wrong reading: <em>"Clarithromycin can
 *       be given, but together with Simvastatin it interacts with active order Amiodarone"</em> (round 3
 *       of #514's review);</li>
 *   <li>the subject clause carries a word denying what it states — {@link #NEGATORS}: <em>"Simvastatin
 *       does not interact with active order Digoxin"</em> names a pair it denies (round 1 of #514's
 *       second review);</li>
 *   <li>the subject span does not END in a drug it names followed straight by the phrase's own verb
 *       ({@link #statesTheVerbOfItsDrug}) — <em>"is unlikely to interact with"</em> or <em>"rarely
 *       interacts with"</em> may not assert the pair it names (round 2 of #514's third review);</li>
 *   <li>that drug does not OPEN its clause ({@link #opensItsClause}) — a word stands straight before
 *       it other than a lone <em>and</em> or a <em>but</em> after no denial, so its subject may be a
 *       drug before a comma the clause never closed or one named by a word no finding prints:
 *       <em>"Clarithromycin, like Simvastatin interacts…"</em>, <em>"Biaxin with Simvastatin
 *       interacts…"</em> (round 3 of #514's third review) — or, citing a finding, a word stands before
 *       the comma its clause begins past and the drug has nothing but a lone <em>and</em> before it
 *       ({@link #mayShareItsSubject}), since a drug there may be a subject beside it
 *       (<em>"Clarithromycin, Simvastatin interacts…"</em>);</li>
 *   <li>the partner span names several drugs joined by words other than a list's
 *       ({@link #PARTNER_LIST_WORDS}) — <em>"active order Amiodarone but not with Digoxin"</em> ran on
 *       into a clause of its own, which may deny the second pair (round 3) — or followed by a word
 *       before any punctuation, the last of them perhaps opening a clause of its own (<em>"active order
 *       Amiodarone and Digoxin is unaffected"</em>, round 1 of the second review), where the claim read
 *       with that drug as a partner and without it reaches two verdicts ({@link #partnerReadings});</li>
 *   <li>the drugs the subject span names reach different verdicts — the claim's clause names another
 *       drug before the noun with no comma or semicolon between, as a lead clause joined by
 *       <em>but</em> or a parenthesis does;</li>
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
 * <em>Lamivudine</em> inside <em>Lamivudine / zidovudine</em> — so a swap between those two passes,
 * and so does a claim naming a combination order by the label of a substance in it other than the one
 * the finding's rule is about, as it does by that substance's word in the display.
 * An invented pair whose subject or partner is a drug no finding or chip names is unjudged, and so is a
 * partner named by a brand, a paraphrase or a word inside a part of a name (<em>isoniazid</em> of a
 * combination's display) rather than the start of one. The first words of a name two drugs share read
 * as either, toward silence. A claim pairing two orders one finding names reads as
 * related, so an order put in for a merged finding's subject passes — {@link #anyRelates} says why.
 * A claim whose only marker sits past its clause names no citation, so a swap the marker carries is
 * reported only where no finding relates the claim's pair. A claim whose verb is not the phrase's own
 * straight after its drug — a hedge (<em>"may interact with"</em>), <em>"also interacts with"</em>, a
 * verbatim copy of another finding's wording — is left unjudged, as is one naming its drug by a brand
 * no finding prints. So is a claim whose drug has a word before it in its clause other than a lone
 * <em>and</em> or a <em>but</em> (<em>"Also X interacts…"</em>, an item number <em>"1)"</em>), and one
 * citing a finding after a comma a word stands before (<em>"However, X interacts… [n]"</em>, <em>"No,
 * X interacts… [n]"</em>) — a swap there is a missed report; the same claim citing nothing is judged.
 * The stand-in words and the list words are closed sets used only to refuse: a
 * clause carrying one for another reason (<em>"note that X interacts…"</em>) and a list joined by
 * other words (<em>"as well as"</em>) are left unjudged. The negators are a closed set too, and a
 * negator in an earlier clause the subject span reaches — no comma, semicolon, colon or spaced em or
 * en dash between, as in <em>"X should not be given because X interacts…"</em> or a lead ending in a
 * spaced hyphen — leaves an asserting claim unjudged. A list's last partner followed by
 * punctuation and then a clause of its own is still read as a partner.
 * A second partner no finding or chip names at all — <em>Heparin</em> in <em>"active order Amiodarone
 * and Heparin"</em> — is no name to this check, so it reads as words after the related partner and
 * passes; a partner list continued past a comma is cut at it, so <em>"active order Amiodarone, Heparin
 * and Digoxin"</em> is judged on Amiodarone alone. A swapped subject in a clause naming several drugs is
 * left unjudged, not reported — a pronoun or a parenthesis included — and so is one after a claim with no
 * marker and no comma, whose partner span runs to its noun and leaves its subject span empty. Starting that
 * span where the previous partner BEGAN reported the swap and read <em>"X interacts with active order A and
 * active order B [b]"</em> as A's claim, accusing X's own B finding (round 3 of #514's second review).
 * And a claim not written in the
 * active-order form — the ticket's first case, <em>"a caution to note regarding interactions with
 * Lopinavir / ritonavir, Didanosine, and Nevirapine [288], [290]"</em> — is not a claim to this check
 * at all. ADR Decision 120 records them.
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

	/**
	 * Words that stand for a drug without naming it — a pronoun, a demonstrative, a relative, the
	 * definite article of <em>"the drug"</em>. One in a claim's SUBJECT clause says its subject may be a
	 * drug the clause does not name, named before its comma or in the sentence before (round 3 of #514's
	 * review), so the claim is left unjudged. A closed set used only to REFUSE: it never decides what a
	 * claim offered, which {@code ActiveOrderCitationFidelityCheck.clauseBound} declines a vocabulary
	 * for, so a word missing from it leaves a claim judged as before and a word added can only silence.
	 */
	private static final Set<String> SUBJECT_STAND_INS = Collections.unmodifiableSet(new HashSet<String>(
			Arrays.asList("it", "its", "they", "their", "this", "that", "these", "those", "which", "who",
					"the")));

	/**
	 * Words denying what their clause states — <em>not</em>, <em>never</em>, <em>without</em>,
	 * <em>cannot</em>, <em>neither</em>, <em>nor</em>, <em>none</em> — read by {@link #deniesItsClause}
	 * with a contracted <em>n't</em>, and <em>no</em> where a word follows it (<em>"no interaction"</em>, <em>"no finding"</em>). One in a claim's SUBJECT
	 * clause says the clause may deny the pair it names — <em>"Simvastatin does not interact with active
	 * order Digoxin"</em> was published unfounded (round 1 of #514's second review) — so the claim is left
	 * unjudged. The verdict lead's <em>"No —"</em> is followed by a dash, not a word, so it is not one. A
	 * closed set used only to REFUSE, as {@link #SUBJECT_STAND_INS} is: a denial worded outside it
	 * (<em>"is unlikely to interact"</em>) is left to {@link #statesTheVerbOfItsDrug}, and a negator in
	 * an earlier clause the subject span reaches with no comma, semicolon or {@link #afterItsLead}
	 * separator between
	 * (<em>"X should not be given because X interacts…"</em>) silences a claim that asserts.
	 */
	private static final Set<String> NEGATORS = Collections.unmodifiableSet(new HashSet<String>(
			Arrays.asList("not", "never", "without", "cannot", "neither", "nor", "none")));

	/**
	 * The words that may join one partner to the next — a list. Anything else between two drugs of the
	 * partner span is another clause the span ran on into, so the claim is left unjudged (round 3 of
	 * #514's review). Used only to refuse, as {@link #SUBJECT_STAND_INS} is: outside it, silence.
	 */
	private static final Set<String> PARTNER_LIST_WORDS = Collections.unmodifiableSet(new HashSet<String>(
			Arrays.asList("and", "or")));

	/**
	 * The verb of {@link DrugSafetyValidator#ACTIVE_ORDER_INTERACTION_PHRASE} — the phrase less its
	 * {@link DrugSafetyValidator#ACTIVE_ORDER_NOUN}, derived and never spelled again. A claim is judged
	 * only where its subject span ends in a drug and then this ({@link #statesTheVerbOfItsDrug}).
	 */
	private static final String RELATIONSHIP_VERB = relationshipVerb();

	/**
	 * The one word that may stand straight before a claim's drug with other words before it, since it
	 * CONTRASTS the drug with what precedes and so leaves it the subject alone — <em>"Simvastatin can be
	 * given, but Clarithromycin interacts…"</em>. Read by {@link #opensItsClause}, used only to refuse.
	 */
	private static final String CONTRASTING_WORD = "but";

	/**
	 * The one word that may stand before a claim's drug as the only word of its span — <em>"…, and
	 * Simvastatin interacts…"</em>, <em>"X interacts with active order A [a] and Y interacts…"</em>. It
	 * adds the drug as a subject, alone or beside one before it, so the drug is asserted either way, and
	 * {@link #mayShareItsSubject} says when the citation may be the other subject's. Read by
	 * {@link #opensItsClause}, used only to refuse.
	 */
	private static final String JOINING_WORD = "and";

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
				String subject = FindingPartnerCoverageCheck.comparable(afterItsLead(claim.subject()));
				Set<String> subjectNames = namedIn(subject, vocabulary);
				if (subjectNames.isEmpty() || containsAWordOf(subject, SUBJECT_STAND_INS)
						|| deniesItsClause(subject) || !statesTheVerbOfItsDrug(subject, subjectNames)
						|| !opensItsClause(subject, subjectNames, claim.beforeSubject())) {
					continue;
				}
				// A partner is judged only where the span STARTS with a name the findings carry, and on that
				// name alone — never the words after it, never the span compared whole (the owner's decision
				// on #514, after round 6 of its review). Every further drug the span names is a partner too:
				// "active order Amiodarone and Digoxin" states two pairs (round 2).
				String partner = normalized(withoutItsNounsPlural(claim.partner()));
				List<Partner> partners = partnersNamed(partner, vocabulary);
				if (partners.isEmpty()) {
					continue;
				}
				List<List<Partner>> partnerReadings = partnerReadings(partner, partners);
				if (partnerReadings.isEmpty()) {
					// "…active order Amiodarone but not with Digoxin" — the span ran on into a clause of its
					// own, and which of its drugs the claim offered cannot be read (round 3 of #514's review).
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
				// A marker past the claim's clause is never the claim's, and a claim with no run of its own is
				// judged as citing nothing. Taking the first run past its clause — gated on the words before it
				// naming no drug a finding names, not stating the phrase's verb, and the finding naming the
				// partner — accused a later clause's own correct citation where that clause names its drug by a
				// word no finding prints, in a verb other than the phrase's ("…, and the other statin does too
				// [6]"; round 2 of #514's third review): a false report, which the owner's decision on #514 lets
				// block. A taken finding relating the pair is in the population an uncited claim is judged
				// against already.
				List<Finding> candidates = runFindings.isEmpty() ? population : runFindings;
				if (unreadable || anyUndecidable(candidates, subjectNames, partners)
						|| !runFindings.isEmpty()
								&& mayShareItsSubject(subject, subjectNames, claim.beforeSubject())) {
					continue;
				}
				// Each drug the subject span names is a READING of the claim's subject, and the claim is
				// judged only where every reading reaches one verdict. Round 2 of #514's review: taking any
				// reading that relates passed a swap behind a lead clause with no comma ("X can be given
				// alongside Y but X interacts with…"); taking the one nearest the noun accuses a correct
				// citation behind a pronoun ("…alongside Y but it interacts with…"). Both shapes put the
				// same readings in the same positions, so what cannot be read is left unjudged. The partner
				// side has readings too, where a list runs on (partnerReadings), under the same rule.
				Verdict verdict = null;
				boolean readingsDisagree = false;
				for (String reading : subjectNames) {
					for (List<Partner> partnersRead : partnerReadings) {
						Verdict read = verdict(reading, partnersRead, runFindings, population);
						if (verdict == null) {
							verdict = read;
						}
						else if (verdict != read) {
							readingsDisagree = true;
						}
					}
				}
				if (readingsDisagree) {
					continue;
				}
				judged++;
				if (verdict == Verdict.UNFOUNDED) {
					unfounded++;
				}
				else if (verdict == Verdict.MISATTRIBUTED) {
					misattributedClaims++;
					misattributed.addAll(runIndexes);
				}
			}
			if (!misattributed.isEmpty() || unfounded > 0) {
				// The citations and the counts, never a name: a claim's names are this patient's
				// medications, and core ships org.openmrs at WARN (ADR Decision 102, issue #439).
				log.warn("Answer for patient={} states {} active-order claim(s) no cited finding relates "
						+ "the pair of — cited {} — and {} naming a pair no finding relates at all. The "
						+ "answer prose is left unchanged (issue #514).", patientId,
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

	/** @return whether {@code text} has, as a whole word, one of {@code words} */
	private static boolean containsAWordOf(String text, Set<String> words) {
		for (String word : text.split("[^\\p{L}\\p{N}]+")) {
			if (words.contains(word)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return whether {@code text} carries a word of {@link #NEGATORS}, a contracted <em>n't</em>, or a
	 *         <em>no</em> a word follows — the determiner of <em>"no interaction"</em>, never the verdict
	 *         lead's <em>"No —"</em>. A character scan and not a pattern: this class compiles none, so it
	 *         cannot grow a citation-marker dialect ({@code ArchitectureGuardTest}).
	 */
	private static boolean deniesItsClause(String text) {
		int at = 0;
		while (at < text.length()) {
			if (!Character.isLetterOrDigit(text.charAt(at))) {
				at++;
				continue;
			}
			int end = at;
			while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) {
				end++;
			}
			String word = text.substring(at, end);
			if (NEGATORS.contains(word)) {
				return true;
			}
			if ("t".equals(word) && at >= 2 && "'\u2019".indexOf(text.charAt(at - 1)) >= 0
					&& text.charAt(at - 2) == 'n') {
				return true;
			}
			if ("no".equals(word)) {
				int next = end;
				while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
					next++;
				}
				if (next > end && next < text.length() && Character.isLetter(text.charAt(next))) {
					return true;
				}
			}
			at = end;
		}
		return false;
	}

	/**
	 * @return whether {@code subject} ENDS in one of {@code subjectNames}, on a word's boundary, and then
	 *         {@link #RELATIONSHIP_VERB} with nothing but spacing between — the phrase's own verb stated of
	 *         a drug the span names. Anything else between the drug and the noun says the clause may not
	 *         assert the pair: <em>"is unlikely to interact with"</em>, <em>"rarely interacts with"</em>,
	 *         a hedge, a verbatim copy of another finding's wording (<em>"is already in"</em>). Round 2
	 *         of #514's third review found a denial outside {@link #NEGATORS} published unfounded; a
	 *         structural rule and not a longer word list, and used only to refuse. False where the verb
	 *         cannot be derived, so a reworded phrase silences the check rather than widening it. A
	 *         character comparison, as {@link #deniesItsClause} is, for its reason. No case pins the space
	 *         before the verb or the word boundary before the name.
	 */
	private static boolean statesTheVerbOfItsDrug(String subject, Set<String> subjectNames) {
		String span = subject.trim();
		if (RELATIONSHIP_VERB.isEmpty() || !span.endsWith(RELATIONSHIP_VERB)) {
			return false;
		}
		int verbAt = span.length() - RELATIONSHIP_VERB.length();
		if (verbAt == 0 || !Character.isWhitespace(span.charAt(verbAt - 1))) {
			return false;
		}
		String before = span.substring(0, verbAt).trim();
		for (String name : subjectNames) {
			int at = before.length() - name.length();
			if (before.endsWith(name) && (at == 0 || !Character.isLetterOrDigit(before.charAt(at - 1)))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return whether the drug the verb follows ({@link #statesTheVerbOfItsDrug}) OPENS its clause, so
	 *         no other subject can stand inside the span unread: nothing but punctuation before it there, a
	 *         {@link #JOINING_WORD} as the span's only word, or a {@link #CONTRASTING_WORD} straight
	 *         before it with no denial in the words before the span's separator
	 *         ({@code beforeSubject} — <em>"Not only X, but Y interacts…"</em> asserts both). Round 3 of
	 *         #514's third review (r3-1): the real subject sat before a comma the clause never closed
	 *         (<em>"Clarithromycin, like Simvastatin interacts…"</em>) or was named by a word no finding
	 *         prints (<em>"Biaxin with Simvastatin interacts…"</em>), the span's lone reading was the other
	 *         drug, and the claim's own correct citation was published misattributed. Every word but the two
	 *         may make another drug the subject or a co-subject; those two are the only ones whose relation to
	 *         what precedes them this check can read — a contrast leaves the drug after it the subject alone,
	 *         a join asserts it — so a word list is unavoidable, and it is used only to refuse: a word
	 *         missing from it leaves a claim unjudged. The <em>and</em> must be the span's only word, since
	 *         <em>"Biaxin and Simvastatin"</em> is a subject of two drugs whose first no finding prints.
	 */
	private static boolean opensItsClause(String subject, Set<String> subjectNames, String beforeSubject) {
		List<String> words = wordsBeforeItsDrug(subject, subjectNames);
		if (words.isEmpty()) {
			return true;
		}
		String last = words.get(words.size() - 1);
		if (CONTRASTING_WORD.equals(last)) {
			return !deniesItsClause(FindingPartnerCoverageCheck.comparable(beforeSubject));
		}
		return JOINING_WORD.equals(last) && words.size() == 1;
	}

	/**
	 * @return whether a drug before the span's comma or semicolon may be the claim's subject BESIDE the one
	 *         it reads, so a finding cited for the claim may be that drug's: where nothing, or only a
	 *         {@link #JOINING_WORD}, stands before the drug in the span, and a word stands before the
	 *         separator since the previous claim — <em>"Clarithromycin, and Simvastatin interacts…"</em>,
	 *         <em>"Clarithromycin, Simvastatin interacts…"</em> may list two subjects, and that first one
	 *         may be named by a word no finding prints. Then a claim citing a finding is left unjudged,
	 *         never accused; one citing none is still judged, since the drug it reads is asserted to
	 *         interact with its partner under either reading. Where only the previous claim stands before
	 *         the span — <em>"… [a] and Y interacts…"</em> — no other subject can.
	 */
	private static boolean mayShareItsSubject(String subject, Set<String> subjectNames, String beforeSubject) {
		List<String> words = wordsBeforeItsDrug(subject, subjectNames);
		return (words.isEmpty() || words.size() == 1 && JOINING_WORD.equals(words.get(0)))
				&& containsAWord(beforeSubject);
	}

	/**
	 * @return the words of {@code subject} before the drug the verb follows — the longest of
	 *         {@code subjectNames} ending the span less its verb on a word's boundary, so a combination's name
	 *         is not read as a word before its last part. Asked only of a span
	 *         {@link #statesTheVerbOfItsDrug} admitted.
	 */
	private static List<String> wordsBeforeItsDrug(String subject, Set<String> subjectNames) {
		String span = subject.trim();
		String before = span.substring(0, span.length() - RELATIONSHIP_VERB.length()).trim();
		String drug = "";
		for (String name : subjectNames) {
			int at = before.length() - name.length();
			if (before.endsWith(name) && name.length() > drug.length()
					&& (at == 0 || !Character.isLetterOrDigit(before.charAt(at - 1)))) {
				drug = name;
			}
		}
		List<String> words = new ArrayList<String>();
		for (String word : before.substring(0, before.length() - drug.length()).split("[^\\p{L}\\p{N}]+")) {
			if (!word.isEmpty()) {
				words.add(word);
			}
		}
		return words;
	}

	/** @return whether {@code text} has a letter or a digit — a word, of any kind */
	private static boolean containsAWord(String text) {
		for (int at = 0; at < text.length(); at++) {
			if (Character.isLetterOrDigit(text.charAt(at))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return the drugs {@code span} names as the claim's partners, in span order — EMPTY, so the claim
	 *         is left unjudged, where the span does not start with a name the findings carry. The first
	 *         partner is the one it starts with ({@link #nameAt}); every name of the vocabulary the span
	 *         contains, and every name starting just after a word of {@link #PARTNER_LIST_WORDS}, is one
	 *         too, read by {@link #partnerReadings}'s refusals. A name inside another's extent
	 *         ({@code lamivudine} of {@code lamivudine/zidovudine}) is that one, and its spelling one more
	 *         form of it, so it is never a partner of its own.
	 */
	private static List<Partner> partnersNamed(String span, Set<String> vocabulary) {
		Partner first = nameAt(span, 0, vocabulary);
		if (first == null) {
			return Collections.emptyList();
		}
		List<Partner> found = new ArrayList<Partner>();
		found.add(first);
		for (String name : vocabulary) {
			for (int at = span.indexOf(name); at >= 0; at = span.indexOf(name, at + 1)) {
				found.add(new Partner(at, at + name.length(), Collections.singleton(name)));
			}
		}
		int at = 0;
		while (at < span.length()) {
			int end = wordEnd(span, at);
			if (end > at && PARTNER_LIST_WORDS.contains(span.substring(at, end))) {
				int next = end;
				while (next < span.length() && Character.isWhitespace(span.charAt(next))) {
					next++;
				}
				Partner listed = next > end ? nameAt(span, next, vocabulary) : null;
				if (listed != null) {
					found.add(listed);
				}
			}
			at = end > at ? end : at + 1;
		}
		Collections.sort(found, (one, other) -> one.start != other.start ? Integer.compare(one.start, other.start)
				: Integer.compare(other.end, one.end));
		List<Partner> partners = new ArrayList<Partner>();
		for (Partner partner : found) {
			Partner last = partners.isEmpty() ? null : partners.get(partners.size() - 1);
			if (last != null && partner.start < last.end) {
				last.forms.addAll(partner.forms);
				last.end = Math.max(last.end, partner.end);
			}
			else {
				partners.add(partner);
			}
		}
		return partners;
	}

	/**
	 * @return the name the span STARTS with at {@code from}, as every run of its words from there, ending
	 *         on a word's end, that begins a known name or a part of one ({@link #beginsAPartOf}) — or null
	 *         where the first word begins none, or {@code from} is not a word's start. Whatever follows the
	 *         longest such run — a strength, a form, <em>"— Moderate"</em>, a full stop — is no part of
	 *         the name (the owner's decision on #514: r6-1 was <em>"Rifampicin — Moderate [4]"</em>
	 *         compared whole and its own finding accused). Every run is a FORM of the partner and a finding
	 *         relates it where one form is read as one of its names, so a name the answer shortens to its
	 *         first words (<em>Rifampicin</em> of <em>Rifampicin 300mg capsule</em>) and one it gives in
	 *         full both read as the finding's, whichever of the two the finding carries. Chosen toward
	 *         silence; no case pins every run over the longest or the shortest alone.
	 */
	private static Partner nameAt(String span, int from, Set<String> vocabulary) {
		if (from >= span.length() || !Character.isLetterOrDigit(span.charAt(from))) {
			return null;
		}
		Set<String> forms = new LinkedHashSet<String>();
		int end = -1;
		int at = wordEnd(span, from);
		while (true) {
			String words = span.substring(from, at);
			boolean known = false;
			for (String name : vocabulary) {
				known |= beginsAPartOf(name, words);
			}
			if (!known) {
				break;
			}
			forms.add(words);
			end = at;
			int next = nextWord(span, at);
			if (next >= span.length()) {
				break;
			}
			at = wordEnd(span, next);
		}
		return forms.isEmpty() ? null : new Partner(from, end, forms);
	}

	/**
	 * @return whether {@code words} occur in {@code name} ending on a word's end and beginning at its
	 *         start or at the start of one of its PARTS — past a character that is not a letter, digit or
	 *         space, as a label's parenthesis (<em>Rifampicin (rifampin)</em>) or a combination's slash
	 *         begins one. Never a word inside a part: <em>capsule</em> of <em>Rifampicin 300mg
	 *         capsule</em> and the <em>and</em> of a combination's display begin no name. No case pins
	 *         that refusal.
	 */
	private static boolean beginsAPartOf(String name, String words) {
		for (int at = name.indexOf(words); at >= 0; at = name.indexOf(words, at + 1)) {
			int end = at + words.length();
			if (end < name.length() && Character.isLetterOrDigit(name.charAt(end))) {
				continue;
			}
			int before = at - 1;
			while (before >= 0 && Character.isWhitespace(name.charAt(before))) {
				before--;
			}
			if (before < 0 || !Character.isLetterOrDigit(name.charAt(before))) {
				return true;
			}
		}
		return false;
	}

	/** @return where the word starting at {@code at} ends — {@code at} itself where none starts there */
	private static int wordEnd(String text, int at) {
		int end = at;
		while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) {
			end++;
		}
		return end;
	}

	/** @return where the next word after {@code at} starts — the text's length where none does */
	private static int nextWord(String text, int at) {
		int next = at;
		while (next < text.length() && !Character.isLetterOrDigit(text.charAt(next))) {
			next++;
		}
		return next;
	}

	/**
	 * @return the READINGS of which of {@code partners} the claim names — empty, so it is left unjudged,
	 *         where some stretch between two of them is more than {@link #PARTNER_LIST_WORDS} and
	 *         punctuation; otherwise all of them where the list ENDS at its last — the span stops there,
	 *         or punctuation follows it before any word does — and, where a word follows the last of
	 *         several, all of them and all but that last. A word straight after the last name says it may
	 *         have opened a clause of its own — <em>"Amiodarone and Digoxin is unaffected"</em>, or the next
	 *         claim's subject where the span runs up to it (round 1 of #514's second review) — or may not
	 *         (<em>"Amiodarone and Digoxin tablets"</em>), so the claim is judged only where both readings
	 *         reach one verdict: a citation relating the subject to none of the drugs named is
	 *         misattributed under either (round 2 of that review), and one relating only that last drug
	 *         is not accused. #477's finding, copied verbatim, closes its list with a dash (<em>"… A and B
	 *         — possible duplicate therapy"</em>). Punctuation and not a vocabulary, {@code clauseBound}'s
	 *         reason; what that gives up is a last name followed by punctuation and then a clause of its
	 *         own, still read as a partner. Where the span names one drug, the words after it are not
	 *         asked.
	 */
	private static List<List<Partner>> partnerReadings(String span, List<Partner> partners) {
		for (int at = 1; at < partners.size(); at++) {
			for (String word : span.substring(partners.get(at - 1).end, partners.get(at).start)
					.split("[^\\p{L}\\p{N}]+")) {
				if (!word.isEmpty() && !PARTNER_LIST_WORDS.contains(word)) {
					return Collections.emptyList();
				}
			}
		}
		if (partners.size() > 1) {
			String after = span.substring(partners.get(partners.size() - 1).end).trim();
			if (!after.isEmpty() && Character.isLetterOrDigit(after.codePointAt(0))) {
				return Arrays.asList(partners, partners.subList(0, partners.size() - 1));
			}
		}
		return Collections.singletonList(partners);
	}

	/**
	 * @return {@code partner} less the {@code s} of the noun's plural — <em>"active orders A and B"</em>,
	 *         #477's finding copied verbatim — which the claim walk leaves at the span's start, since it
	 *         finds the noun in its singular. A span starting with a word is otherwise read as it is.
	 */
	private static String withoutItsNounsPlural(String partner) {
		return partner.length() > 1 && partner.charAt(0) == 's' && !Character.isLetterOrDigit(partner.charAt(1))
				? partner.substring(1)
				: partner;
	}

	/**
	 * @return {@code subject} from just past its last lead separator — a colon followed by a space, or an
	 *         em or en dash with a space on each side — or whole where it carries none. The prompt asks a
	 *         withhold finding to open with <em>"No"</em> and what to avoid, so a lead before the claim is
	 *         the form it invites, and a negator, a stand-in or another drug in it (<em>"Not recommended —
	 *         X interacts…"</em>, <em>"Do not give X: X interacts…"</em>) was read as the claim's own, leaving
	 *         a swap behind it unjudged (round 2 of #514's second review). Local to the subject span, so
	 *         {@code ActiveOrderCitationFidelityCheck}'s clause bounds are unchanged. A colon without a
	 *         space is not one — two knowledge-base names carry one (<em>"(2:1)"</em>) — nor is a spaced
	 *         hyphen, which can join a combination's names; a lead ending in either is still read as the
	 *         claim's. The spaces around a dash keep a combination joined by one (<em>"Simvastatin–X"</em>)
	 *         a subject of two readings; no case pins the space after a colon or the one before a dash. A
	 *         character scan, as {@link #deniesItsClause} is, for its reason.
	 */
	private static String afterItsLead(String subject) {
		for (int at = subject.length() - 2; at >= 0; at--) {
			char c = subject.charAt(at);
			boolean spaceAfter = Character.isWhitespace(subject.charAt(at + 1));
			if (c == ':' && spaceAfter) {
				return subject.substring(at + 1);
			}
			if ((c == '\u2014' || c == '\u2013') && spaceAfter && at > 0
					&& Character.isWhitespace(subject.charAt(at - 1))) {
				return subject.substring(at + 1);
			}
		}
		return subject;
	}

	/** @return every word of the phrase but the two its noun is ({@code lastTwoWordsOf}'s complement),
	 *          in comparable form — never throwing, since a class that cannot initialise would break the
	 *          answer this check promises never to */
	private static String relationshipVerb() {
		String[] words = DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE.trim().split("\\s+");
		StringBuilder verb = new StringBuilder();
		for (int at = 0; at < words.length - 2; at++) {
			verb.append(at == 0 ? "" : " ").append(words[at]);
		}
		return FindingPartnerCoverageCheck.comparable(verb.toString());
	}

	/** What a judged claim is, under one reading of its subject. */
	private enum Verdict {
		RELATED, MISATTRIBUTED, UNFOUNDED
	}

	/**
	 * @return the claim's verdict with {@code reading} as its subject: MISATTRIBUTED where it cites
	 *         findings none of which relates the reading to any of its partners; otherwise UNFOUNDED where
	 *         a partner is related by no finding in the prompt and no chip, cited or not — the invented
	 *         partner beside a cited one that relates; otherwise RELATED. A partner the run leaves
	 *         unrelated that an uncited finding relates is not a pair "no finding raised".
	 */
	private static Verdict verdict(String reading, List<Partner> partners, List<Finding> runFindings,
			List<Finding> population) {
		boolean citationRelates = false;
		boolean everyPartnerFounded = true;
		for (Partner partner : partners) {
			if (!runFindings.isEmpty() && anyRelates(runFindings, reading, partner)) {
				citationRelates = true;
			}
			else if (!anyRelates(population, reading, partner)) {
				everyPartnerFounded = false;
			}
		}
		if (!runFindings.isEmpty() && !citationRelates) {
			return Verdict.MISATTRIBUTED;
		}
		return everyPartnerFounded ? Verdict.RELATED : Verdict.UNFOUNDED;
	}

	/** @return {@code text} in the one form every name and span here is compared in —
	 *          {@link FindingPartnerCoverageCheck#comparable}, trimmed — and empty for null. The SUBJECT
	 *          span is compared untrimmed, only ever searched by containment. */
	private static String normalized(String text) {
		return text == null ? "" : FindingPartnerCoverageCheck.comparable(text).trim();
	}

	/** @return whether two names, in comparable form, are read as naming one drug: either contains the
	 *          other — so a partner named by the front of a longer name the finding carries
	 *          ({@code coumadin} of {@code coumadin 5mg}) matches it. That direction is also what reads
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
	 * @return whether one of {@code findings} relates one reading of the claim's subject to one of its
	 *         partners: each names one of the finding's names — its subject, an order it names, or a
	 *         name through her orders — whichever way round.
	 *         Two of its ORDERS count as a pair it relates, deliberately: issue #477's findings (a drug
	 *         already in several of her orders, orders sharing a substance) state exactly that relation,
	 *         and nothing on the record tells them from a merged finding stating each order against its
	 *         subject, so refusing the pair would call a verbatim copy of them misattributed. The cost is
	 *         the other direction: a claim pairing two orders of a merged finding is not reported.
	 */
	private static boolean anyRelates(List<Finding> findings, String subject, Partner partner) {
		for (Finding finding : findings) {
			if (finding.namesAnOrder && namesAny(partner.forms, finding.names)
					&& matchesAny(subject, finding.names)) {
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

	/** @return whether a form of any of {@code partners} is read as one of {@code names} */
	private static boolean namesAny(List<Partner> partners, Set<String> names) {
		for (Partner partner : partners) {
			if (namesAny(partner.forms, names)) {
				return true;
			}
		}
		return false;
	}

	/** @return whether a finding naming no order is about a drug either side of the claim names — the
	 *          class-only relationship, whose partner is a class this check cannot compare */
	private static boolean anyUndecidable(List<Finding> findings, Set<String> subjectNames,
			List<Partner> partners) {
		for (Finding finding : findings) {
			if (finding.namesAnOrder) {
				continue;
			}
			if (namesAny(partners, Collections.singleton(finding.subject))) {
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

	/** One drug a claim's partner span names: where it sits in the span, and every form of its name. */
	private static final class Partner {

		private final int start;

		private int end;

		private final Set<String> forms;

		private Partner(int start, int end, Set<String> forms) {
			this.start = start;
			this.end = end;
			this.forms = new LinkedHashSet<String>(forms);
		}
	}

	/** One finding, record or chip, as the names it goes by in comparable form. */
	private static final class Finding {

		/** Whether the finding's type relates a drug to an order at all. */
		private final boolean relatesDrugs;

		/** Whether it names an order structurally — false for the class-only relationship. */
		private final boolean namesAnOrder;

		private final String subject;

		/** Every name it goes by — its subject, the orders it names, its names through her orders. */
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
					SafetyWarning.orderNamesOf(chip));
		}
	}
}
