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
 * {@code DrugSafetyValidator.ACTIVE_ORDER_NOUN}, the words before it in its clause as the SUBJECT,
 * the words after it up to its marker run (or clause bound) as the PARTNER, and that run as what it
 * cites. One walk, so this check and that one cannot disagree about which claims the answer made or
 * which markers each offered. A marker past the claim's comma is not in its run — the ticket's cases
 * 2 and 4 put it there — and a claim with no run of its own takes the findings of the first run past
 * its clause on three gates only: the words between the comma and that run name no drug any finding
 * names and do not state the relationship again in the phrase's own verb ({@link #RELATIONSHIP_VERB}),
 * and the finding names the claim's PARTNER. Each gate is against the report ADR Decision 76 calls
 * crying wolf, a later clause's own correct citation: the first where that clause names another drug a
 * finding carries, the second where it states another interaction naming its drug by a class or a word
 * no finding prints (round 3 of #514's review), the third where it names one no finding carries. A
 * claim the gates do not let through is judged as citing nothing, and is still reported where no
 * finding relates its pair.
 *
 * <p><b>What a finding relates is read structurally, never from its text.</b> Every name a finding
 * goes by: its subject ({@link ChartSearchAiUtils#findingSubject} on a record,
 * {@link SafetyWarning#getDrug()} on a chip), the orders it names
 * ({@link RecordMapping#getFindingPartners()}, {@link SafetyWarning#namedPartners()}), and the
 * prescriptions and substances its chart-order clause resolved them from
 * ({@link RecordMapping#getFindingBridgeNames()}, {@link SafetyWarning#chartOrderBridges()}) — the
 * last because a brand-named order's finding gives the drug a second name the model may use (#349).
 * A finding relates a subject to a partner when each names one of those names, whichever way round: an
 * interaction relates two drugs whichever the sentence leads with, the screening arm's two drugs are
 * both her orders, and issue #477's findings relate two of her orders to each other. So two orders one
 * finding names read as related, and a claim pairing two orders of a merged finding is not reported —
 * {@link #anyRelates} says why that is the direction chosen.
 *
 * <p><b>A claim can state several pairs</b> (round 2 of #514's review). Every drug the partner span
 * names that a finding or chip names is a PARTNER of it — <em>"active order Amiodarone and
 * Digoxin"</em> states two — and a span naming none is compared whole. Every such drug the SUBJECT span
 * names is a READING of its subject, and the claim is judged only where every reading reaches one
 * verdict: which drug of <em>"X can be given alongside Y but X interacts with…"</em> is the subject is
 * the one nearest the noun, and of <em>"…alongside Y but it interacts with…"</em> is not, the same
 * readings in the same positions — so taking any reading that relates passes a swap, and taking the
 * nearest accuses a correct citation.
 *
 * <p><b>Two answers.</b>
 * <ul>
 *   <li>A claim whose run — or the trailing run it takes — cites findings, none of which relates its
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
 *   <li>the partner side is blank;</li>
 *   <li>the subject clause carries a word standing for a drug without naming it —
 *       {@link #SUBJECT_STAND_INS}, <em>it</em>, <em>this</em>, <em>which</em>, the <em>the</em> of
 *       <em>"the drug"</em> — since its subject may then be a drug named before its comma or in the
 *       sentence before, and the drug the clause does name is the wrong reading: <em>"Clarithromycin can
 *       be given, but together with Simvastatin it interacts with active order Amiodarone"</em> (round 3
 *       of #514's review);</li>
 *   <li>the subject clause carries a word denying what it states — {@link #NEGATORS}: <em>"Simvastatin
 *       does not interact with active order Digoxin"</em> names a pair it denies (round 1 of #514's
 *       second review);</li>
 *   <li>the partner span names several drugs joined by words other than a list's
 *       ({@link #PARTNER_LIST_WORDS}) — <em>"active order Amiodarone but not with Digoxin"</em> ran on
 *       into a clause of its own, which may deny the second pair (round 3) — or followed by a word
 *       before any punctuation, the last of them opening a clause of its own: <em>"active order
 *       Amiodarone and Digoxin is unaffected"</em> (round 1 of the second review);</li>
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
 * <em>Lamivudine</em> inside <em>Lamivudine / zidovudine</em> — so a swap between those two passes.
 * An invented pair whose subject is a drug no finding names is unjudged. The partner side runs the
 * other way, because it is ungated: a partner the answer names by a brand or a paraphrase no finding
 * prints reads as unrelated and can be REPORTED — the bridge names are what keep a brand-named
 * prescription's own display out of that case. A claim pairing two orders one finding names reads as
 * related, so an order put in for a merged finding's subject passes — {@link #anyRelates} says why.
 * The trailing-run gates read names the findings carry and the phrase's own verb, so a later clause
 * naming another drug only by a name no finding prints, citing a finding that names the claim's
 * partner, is taken for the claim unless it says <em>interacts with</em> — one restating the
 * relationship in other words is still taken. The stand-in words and the list words are closed sets
 * used only to refuse: a subject clause naming another drug and then its own by a word the stand-ins
 * lack — a brand no finding prints, a bare class noun — is still read as that other drug and can be
 * reported, while a clause carrying one for another reason (<em>"note that X interacts…"</em>) and a
 * list joined by other words (<em>"as well as"</em>) are left unjudged. The negators are a closed set
 * too: a denial worded outside it (<em>"is unlikely to interact"</em>) is judged as the pair it names,
 * and a negator in an earlier clause the subject span reaches leaves an asserting claim unjudged. A
 * list's last partner followed by punctuation and then a clause of its own is still read as a partner.
 * A second partner no finding or chip names at all — <em>Heparin</em> in <em>"active order Amiodarone
 * and Heparin"</em> — is no name to this check, so it reads as more words of the related partner and
 * passes; a partner list continued past a comma is cut at it, so <em>"active order Amiodarone, Heparin
 * and Digoxin"</em> is judged on Amiodarone alone. A swapped subject in a clause naming several drugs is
 * left unjudged, not reported — a pronoun or a parenthesis included.
 * And a claim not written in the
 * active-order form — the ticket's first case, <em>"a caution to note regarding interactions with
 * Lopinavir / ritonavir, Didanosine, and Nevirapine [288], [290]"</em> — is not a claim to this check
 * at all. ADR Decision 119 records them.
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
	 * (<em>"is unlikely to interact"</em>) is judged as an assertion, and a negator in an earlier clause the
	 * subject span reaches with no comma between (<em>"X should not be given because X interacts…"</em>)
	 * silences a claim that asserts.
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
	 * {@link DrugSafetyValidator#ACTIVE_ORDER_NOUN}, derived and never spelled again. A trailing gap
	 * stating it states another relationship, whose marker is that clause's own.
	 */
	private static final String RELATIONSHIP_VERB = relationshipVerb();

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
				String subject = FindingPartnerCoverageCheck.comparable(claim.subject());
				Set<String> subjectNames = namedIn(subject, vocabulary);
				String partner = normalized(claim.partner());
				if (subjectNames.isEmpty() || partner.isEmpty() || containsAWordOf(subject, SUBJECT_STAND_INS)
						|| deniesItsClause(subject)) {
					continue;
				}
				// Every drug the partner span names that a finding or chip names is a partner of the claim —
				// "active order Amiodarone and Digoxin" states two pairs, and containment of the one related
				// name read both as related (round 2 of #514's review). A span naming none is compared whole.
				Set<String> partnerNames = namedIn(partner, vocabulary);
				if (partnerNames.isEmpty()) {
					partnerNames = Collections.singleton(partner);
				}
				else if (!joinedAsAList(partner, partnerNames)) {
					// "…active order Amiodarone but not with Digoxin" — the span ran on into a clause of its
					// own, and which of its drugs the claim offered cannot be read (round 3 of #514's review).
					// So did "…active order Amiodarone and Digoxin is unaffected", whose Digoxin opens that
					// clause (round 1 of #514's second review).
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
				// A claim with no run of its own takes a finding marker past its clause only on three gates,
				// each against a false report Decision 76 names: nothing between the clause break and the
				// marker names a drug any finding names — a later clause about another drug carries its own
				// citation — and the finding names the claim's PARTNER, the evidence the marker is about this
				// claim. Round 1 of #514's review: without it the ticket's own cases 2 and 4, whose markers
				// sit after a comma, named no citation. Round 3: a gap stating the relationship again
				// ("…, which also interacts with a statin [6]") is another claim naming its drug by a word no
				// finding prints, so its marker is not taken either.
				String trailingGap = FindingPartnerCoverageCheck.comparable(claim.trailingGap());
				if (namedIn(trailingGap, vocabulary).isEmpty() && (RELATIONSHIP_VERB.isEmpty()
						|| !trailingGap.contains(RELATIONSHIP_VERB))) {
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
				// Each drug the subject span names is a READING of the claim's subject, and the claim is
				// judged only where every reading reaches one verdict. Round 2 of #514's review: taking any
				// reading that relates passed a swap behind a lead clause with no comma ("X can be given
				// alongside Y but X interacts with…"); taking the one nearest the noun accuses a correct
				// citation behind a pronoun ("…alongside Y but it interacts with…"). Both shapes put the
				// same readings in the same positions, so what cannot be read is left unjudged.
				Verdict verdict = null;
				boolean readingsDisagree = false;
				for (String reading : subjectNames) {
					Verdict read = verdict(reading, partnerNames, runFindings, population);
					if (verdict == null) {
						verdict = read;
					}
					else if (verdict != read) {
						readingsDisagree = true;
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
	 * @return whether every stretch of {@code partner} between two of the {@code names} it contains is
	 *         {@link #PARTNER_LIST_WORDS} and punctuation alone, and — where it names more than one —
	 *         whether the list ENDS at the last of them: the span stops there, or punctuation follows it
	 *         before any word does. A word straight after the last name of several says that name opened a
	 *         clause of its own — <em>"Amiodarone and Digoxin is unaffected"</em>, or the next claim's
	 *         subject where the span runs up to it (round 1 of #514's second review) — while #477's
	 *         finding, copied verbatim, closes its list with a dash (<em>"… A and B — possible duplicate
	 *         therapy"</em>). Punctuation and not a vocabulary, {@code clauseBound}'s reason; what that gives
	 *         up is a last name followed by punctuation and then a clause of its own, still read as a
	 *         partner. A name inside another occurrence ({@code lamivudine} of
	 *         {@code lamivudine/zidovudine}) is that occurrence, so it opens no stretch; the words before
	 *         the first name are not asked, nor, where it names one drug, the words after it.
	 */
	private static boolean joinedAsAList(String partner, Set<String> names) {
		List<int[]> occurrences = new ArrayList<int[]>();
		for (String name : names) {
			for (int at = partner.indexOf(name); at >= 0; at = partner.indexOf(name, at + 1)) {
				occurrences.add(new int[] { at, at + name.length() });
			}
		}
		Collections.sort(occurrences, (one, other) -> one[0] != other[0] ? Integer.compare(one[0], other[0])
				: Integer.compare(other[1], one[1]));
		int coveredTo = -1;
		boolean several = false;
		for (int[] occurrence : occurrences) {
			if (coveredTo >= 0 && occurrence[0] >= coveredTo) {
				several = true;
				for (String word : partner.substring(coveredTo, occurrence[0]).split("[^\\p{L}\\p{N}]+")) {
					if (!word.isEmpty() && !PARTNER_LIST_WORDS.contains(word)) {
						return false;
					}
				}
			}
			coveredTo = Math.max(coveredTo, occurrence[1]);
		}
		if (several) {
			String after = partner.substring(coveredTo).trim();
			return after.isEmpty() || !Character.isLetterOrDigit(after.codePointAt(0));
		}
		return true;
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
	private static Verdict verdict(String reading, Set<String> partners, List<Finding> runFindings,
			List<Finding> population) {
		boolean citationRelates = false;
		boolean everyPartnerFounded = true;
		for (String partner : partners) {
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
	 * @return whether one of {@code findings} relates one reading of the claim's subject to one of its
	 *         partners: each names one of the finding's names — its subject, an order it names, or a
	 *         bridge name — whichever way round.
	 *         Two of its ORDERS count as a pair it relates, deliberately: issue #477's findings (a drug
	 *         already in several of her orders, orders sharing a substance) state exactly that relation,
	 *         and nothing on the record tells them from a merged finding stating each order against its
	 *         subject, so refusing the pair would call a verbatim copy of them misattributed. The cost is
	 *         the other direction: a claim pairing two orders of a merged finding is not reported.
	 */
	private static boolean anyRelates(List<Finding> findings, String subject, String partner) {
		for (Finding finding : findings) {
			if (finding.namesAnOrder && matchesAny(partner, finding.names)
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
