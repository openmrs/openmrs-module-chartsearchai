/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports what an answer does to a class code it was handed — a deterministic, exact check for the
 * failures the semantic grounding pass is structurally unable to see. Three of them: a code no
 * record the answer CITES contains (issue #142), and, since issue #338, a code stated more than
 * once inside one parenthetical and a citation marker placed inside one.
 *
 * <p><b>The failure.</b> Measured live: the deterministic chip said class {@code J01MA}
 * (fluoroquinolones) while the answer, citing that finding's record number, said {@code J01CA}
 * (penicillins). The chip is right, the record is right, the citation is right, and the sentence a
 * clinician reads is wrong. Six further captures on #142 show the model treating a code as editable
 * text rather than as an identifier: a character mutated ({@code V03AB}→{@code V03AV},
 * {@code S01BC}→{@code S01SC}), a code invented beside the true one
 * ({@code (H02AB)}→{@code (H02AB, S01BA02)}), granularity changed in both directions
 * ({@code (A02BC)}→{@code (A02B)}, {@code (D01AC)}→{@code (D01AC07, G01AF08)}), and a correct code
 * duplicated ({@code (A01AD) (A01AD)}).
 *
 * <p><b>What the MEMBERSHIP comparison cannot see, and what issue #338 added beside it.</b> A
 * duplicated correct code is textually supported by its record, and so is any code the answer
 * OMITS — that comparison reads what the answer states, never what it fails to state. The reason
 * recorded here used to be that detecting either needs a comparison against the chips the answer
 * was expected to report; that is still true of an OMISSION (and is why one is not attempted — a
 * chip the model deliberately did not repeat is not an error), and it was measured wrong for one
 * shape of the duplication. {@link #reportMalformedParentheticals} detects a code repeated inside
 * ONE parenthetical answer-locally, reading no chip and no record: nothing this module renders
 * states one code twice inside one parenthetical, so the SHAPE is wrong on its own terms whatever
 * the records state. What stays undetected is the cross-parenthetical duplication of #142's own capture
 * ({@code (A01AD) (A01AD)}), which two clauses about two partners legitimately produce.
 *
 * <p><b>Why the existing passes cannot catch it.</b> For the citation #142 was measured on — an
 * injected {@code safety_finding} — {@link CitationGroundingVerifier}'s Tier-2 entailment never runs
 * at all: reference-group citations are demote-only and skip it (issue #106/#122), so the only pass
 * that sees them is Tier-1 cosine over the record's text, which a two-character edit inside an
 * alphanumeric token moves almost not at all. Where Tier-2 does run — a cited CHART record — it is
 * paraphrase-tolerant, which is exactly the wrong tolerance for a code substitution. Index
 * validation passes either way, because the citation really does point at the record. So this check
 * is not more grounding: it is an exact token comparison, which is the only thing that separates
 * {@code J01CA} from {@code J01MA}.
 *
 * <p><b>What it does and does not do.</b> It reports; it never rewrites. Editing a
 * clinician-facing sentence to remove a token is a larger decision than this check is licensed to
 * make, and a silent edit would be worse than a visible flag. The verdict reaches maintainers as a
 * WARN carrying the code the answer states, the records it cites and the codes those records
 * state, so the miscopy can be reconstructed from logs alone. Whether it should also reach the
 * clinician — a chip, or a demoted citation — is deliberately left open on #142: every surfacing
 * option is a wire or UI change, and since issue #201 a reference-group citation publishes no
 * verdict at all.
 *
 * <p><b>Where it runs.</b> Both answer paths — {@link LlmInferenceService#search} and
 * {@code searchStreaming} — so the endpoint users actually hit is covered. Deliberately NOT the
 * progressive-reasoning preview: that pass discards its answer, streams only reasoning from a
 * focused top-K chart, and resolves no citations at all, so every code in it would be reported and
 * the signal would be noise within a day. A cached answer ({@code ChartSearchServiceRouter}) is not
 * re-checked either — it was checked when it was produced. Nor is the model's REASONING, which the
 * streaming endpoint surfaces as its own {@code thinking} event: reasoning cites nothing, so every
 * code in it would be reported. That is a real gap in what a clinician can read, and a narrower
 * rule than this one would be needed to close it.
 *
 * <p><b>Conservative by construction</b>, because a check that cries wolf is worse than no check:
 * <ul>
 *   <li>it says nothing at all unless a record the answer CITES states a class code. With nothing
 *       to copy there is no copy to be unfaithful to, and this is what keeps ordinary prose out of
 *       the check: an answer about dosing frequencies cites drug-order records, which carry no
 *       codes;</li>
 *   <li>it compares WHOLE tokens, in both directions — a code the record does not state as a token
 *       is unsupported even when it is a prefix of one that it does ({@code A02B} against
 *       {@code A02BC}), and a code stated beside the true one is still unsupported;</li>
 *   <li>and it does NOT accept a level-4 class rolled up from a cited level-5 code, though that
 *       roll-up is the module's own ({@code DrugReference.atcSubgroups}) and an answer making it is
 *       usually right. It was written, then removed: pooling support across cited records, the
 *       roll-up silences #142's own headline capture whenever the chart cites a reference record
 *       for a drug in the WRONGLY named class — a patient on ciprofloxacin and amoxicillin, records
 *       stating {@code J01MA} and {@code J01CA04}, and "same ATC class (J01CA)" becomes supported.
 *       Reporting an answer that generalises correctly costs a log line a maintainer dismisses;
 *       failing to report the fabrication this check exists for costs the check its purpose;</li>
 *   <li>it abstains for the whole answer — not per citation, as the grounding verifier does — when
 *       any cited record carries no readable text, since a record we could not read may be the one
 *       that states the code. That is the same "cannot verify" treatment
 *       {@link CitationGroundingVerifier} gives a null/blank record text, applied at a coarser
 *       grain because one unread record is enough to make the whole comparison unsound;</li>
 *   <li>it matches only upper-case tokens whose first letter is one of ATC's fourteen main groups,
 *       so no lower-case word and no {@code Q12H}-shaped frequency can be mistaken for a code. One
 *       pattern reads both sides, so that cuts both ways: a lower-cased code in an answer is a
 *       silent pass, and a lower-cased code in a chart NOTE supports nothing. Both are accepted —
 *       every code this module renders is upper-cased ({@code DrugReference.normalizeAtcToken}),
 *       and case-folding one side would make the two sides disagree about what a code is.</li>
 * </ul>
 */
final class ClassCodeFidelityCheck {

	private static final Logger log = LoggerFactory.getLogger(ClassCodeFidelityCheck.class);

	/**
	 * ATC's fourteen anatomical/pharmacological main groups — the letter every ATC code, at every
	 * level, begins with. Derived rather than recalled: taken from the WHO ATC index itself, where
	 * the level-1 entries are exactly these and no code at any level starts with another letter.
	 * A closed set at the top of the classification, unlike the level-2 groups beneath it, which the
	 * index does add to — so this constrains the shape without a table that can go stale into
	 * silence.
	 *
	 * <p>It is what keeps the commonest clinical shapes out of the check: {@code Q12H},
	 * {@code Q24H} and {@code Q48H} are dosing frequencies of exactly the ATC level-4 shape, and
	 * {@code Q} is not an ATC main group. What it does not exclude is a token under a real
	 * main-group letter whose level 2 does not exist — {@code D50W} for 50% dextrose, {@code G12C}
	 * and {@code H63D} for variant nomenclature. In an answer that states no real class code the
	 * "nothing to copy" gate holds those out too; in an answer that states one, they are reported,
	 * and that is this check's residual false-alarm shape. It is left un-narrowed on purpose: the
	 * table that would exclude them is ATC's level-2 groups, which the index does add to, and a
	 * stale copy of it would stop detecting real miscopies in silence.
	 */
	private static final String MAIN_GROUPS = "ABCDGHJLMNPRSV";

	/**
	 * An ATC classification code as it occurs in prose: a level-3 group ({@code J01M}), a level-4
	 * subgroup ({@code J01MA}) or a level-5 substance code ({@code J01MA02}). Level 1 (a single
	 * letter) and level 2 ({@code J01}) are deliberately out: they are too short to tell from
	 * ordinary text, and nothing the shipped datasets render states them — a chip names a level-4
	 * class and a reference record renders the entry's own codes.
	 *
	 * <p><b>Not {@link ChartSearchAiUtils#INLINE_CITATION}, and never to be merged with it.</b>
	 * That pattern parses citation MARKERS and is deliberately single-index because a bracketed
	 * group in answer prose can always be a clinical value ({@code [120, 80]}); widening it
	 * fabricates references. This one matches a CODE TOKEN wherever it appears — inside brackets,
	 * inside parentheses, bare — and answers a different question about a different string. They
	 * share no input and no consumer; one pattern doing both jobs could only be the union of two
	 * unrelated dialects.
	 *
	 * <p>The boundaries are character-class lookarounds rather than {@code \b}: {@code \b} would
	 * accept a code welded to a neighbouring alphanumeric run (the tail of {@code 0DTJ4ZZ}), which
	 * is not a code the model copied but a fragment of something else.
	 */
	private static final Pattern ATC_CLASS_CODE = Pattern.compile(
			"(?<![A-Za-z0-9])[" + MAIN_GROUPS + "]\\d{2}[A-Z](?:[A-Z](?:\\d{2})?)?(?![A-Za-z0-9])");

	private ClassCodeFidelityCheck() {
	}

	/**
	 * @return every ATC-shaped code token in {@code text}, in first-appearance order; empty for
	 *         null text. Used for both sides of the comparison — what the answer states and what a
	 *         record states — so the two can never be read by different rules, and, since issue #338,
	 *         for a third reading: what one PARENTHETICAL of the answer states
	 *         ({@link #reportMalformedParentheticals}). A filter added here reaches all three.
	 *
	 *         <p>Package-private rather than private on purpose: this is the predicate a measurement
	 *         over captured answers has to CALL rather than re-express (CLAUDE.md's rule for any
	 *         figure that ends up in javadoc, a PR body or an issue), and a same-package throwaway
	 *         case cannot call a private one.
	 */
	static Set<String> classCodesIn(String text) {
		Set<String> codes = new LinkedHashSet<String>();
		if (text == null) {
			return codes;
		}
		Matcher matcher = ATC_CLASS_CODE.matcher(text);
		while (matcher.find()) {
			codes.add(matcher.group());
		}
		return codes;
	}

	/**
	 * Reports, at WARN, every ATC class code {@code answer} states that none of the records it
	 * cites contains — and then, behind the same gates, the two malformations of a class-code
	 * parenthetical that comparison cannot see ({@link #reportMalformedParentheticals}, issue #338).
	 *
	 * @param patient whose answer it is — logged so a line is attributable under concurrent requests
	 * @param question the clinician's own question — its codes count as support, see the body
	 * @param answer the answer prose, unchanged by this method
	 * @param cited the references the answer cites, as resolved by
	 *            {@link LlmInferenceService#extractCitedReferences} — the union of the inline
	 *            {@code [N]} markers and the structured citations array, index-validated. Taking
	 *            the accessor's own output rather than re-deriving it from the prose is what keeps
	 *            "which records were cited" a single answer, and it is also what the clinician can
	 *            click. An answer that cites nothing cites no code-bearing record either, so it
	 *            takes the "nothing to copy" exit above like any other.
	 * @param mappings the chart's records, cited or not — the carrier of the cited records' text.
	 *            Support is pooled across the cited records rather than matched per citation: an
	 *            answer citing [3] and [7] may state any code either of them carries, because the
	 *            question here is where a code came from, not which sentence carries which marker
	 *            (that is grounding's question, and {@code grounding.clauseScoped} is where it is
	 *            answered).
	 */
	static void reportClassCodeDefects(Patient patient, String question, String answer,
			List<RecordReference> cited, List<RecordMapping> mappings) {
		Integer patientId = null;
		try {
			// Inside the guard, not above it: reading a detached patient proxy is the one line here
			// that could throw, and the promise this catch makes is structural or it is nothing.
			patientId = patient == null ? null : patient.getPatientId();
			Set<String> stated = classCodesIn(answer);
			if (stated.isEmpty()) {
				return;
			}
			Map<Integer, String> textByIndex = new HashMap<Integer, String>();
			if (mappings != null) {
				for (RecordMapping mapping : mappings) {
					textByIndex.put(Integer.valueOf(mapping.getIndex()), mapping.getText());
				}
			}
			List<Integer> citedIndexes = new ArrayList<Integer>();
			Set<String> recordCodes = new LinkedHashSet<String>();
			if (cited != null) {
				for (RecordReference reference : cited) {
					Integer index = Integer.valueOf(reference.getIndex());
					// A cited index always has a mapping — the reference list is built from the
					// mappings — so a null here is only ever a mapping carrying no text.
					String text = textByIndex.get(index);
					if (ChartSearchAiUtils.isBlank(text)) {
						// Abstain for the whole answer: an unreadable cited record may be the one
						// that states the code, and accusing the prose of a code we could not look
						// for is exactly the false alarm this check must not raise.
						log.debug("Class-code check skipped for patient={}: cited record [{}] "
								+ "carries no text", patientId, index);
						return;
					}
					citedIndexes.add(index);
					recordCodes.addAll(classCodesIn(text));
				}
			}
			if (recordCodes.isEmpty()) {
				// The first gate: nothing the answer cites states a class code, so the answer copied
				// none and there is no copy to be unfaithful to. Everything code-SHAPED in an answer
				// like that — a dosing frequency, a dextrose strength — would be reported on a
				// resemblance, which is the failure this check exists to avoid.
				log.debug("Class-code check skipped for patient={}: no cited record states a class "
						+ "code", patientId);
				return;
			}
			List<String> unsupported = new ArrayList<String>();
			// The question's own codes count as support: a code the reader typed and the answer
			// echoed is not a code the model invented, and this module already treats a
			// question-named drug as in play wherever it decides what an answer may say.
			Set<String> supported = new LinkedHashSet<String>(recordCodes);
			supported.addAll(classCodesIn(question));
			for (String code : stated) {
				if (!supported.contains(code)) {
					unsupported.add(code);
				}
			}
			if (!unsupported.isEmpty()) {
				// Both halves are needed to reconstruct it from logs: the code the model wrote, and
				// the codes it could have copied — the ones the records literally state, not the
				// derived set, so the line says what a maintainer would read in the record. Neither
				// the answer nor the record text is logged: they carry patient data, and the codes
				// with the patient identify the claim.
				log.warn("Answer for patient={} states ATC class code(s) {} that no cited record "
						+ "contains; cited record(s) {} state {}. The answer prose is left unchanged "
						+ "(issue #142).", patientId, unsupported, citedIndexes, recordCodes);
			}
			reportMalformedParentheticals(patientId, answer, citedIndexes);
		}
		catch (RuntimeException e) {
			// A diagnostic must never break a clinical answer. Nothing here does I/O and every line
			// is traceably total, so this is the same promise CitationGroundingVerifier makes in its
			// javadoc, made structurally rather than by inspection — and loudly, so the failure of
			// the check is not itself silent.
			log.warn("Class-code check failed for patient={}; the answer is unaffected: {}",
					patientId, e.toString());
		}
	}

	/**
	 * Reports, at WARN, the two malformations of a class-code parenthetical that the membership
	 * comparison above is structurally unable to see (issue #338): the same code stated more than
	 * once inside one parenthetical, and a citation marker placed inside one.
	 *
	 * <p><b>Both are answer-LOCAL in the sense that matters here.</b> Neither asks what the records
	 * STATE — they ask what SHAPE the prose has, and the shape is wrong on its own terms. (The marker
	 * rule does ask the records one thing: whether an index resolves to one, which is what keeps a
	 * number the chart has no record for out of it.) That is what keeps them clear of the
	 * comparison ADR Decision 35 rejects (the answer's codes against the CHIPS, i.e. against what the
	 * answer was expected to report rather than what it was licensed to state): nothing here reads a
	 * chip, and nothing here reads what the answer failed to say.
	 *
	 * <p><b>The repetition rule is a shape claim and not a licensing one</b>, and saying so is the
	 * point. On #338's capture four cited findings each state {@code H02AB}, so the four occurrences
	 * of it in {@code (H02AB, H02AB, H02AB, H02AB)} are supported both as a set and as a multiset;
	 * a membership test of any strength calls that answer faithful. What no record licenses is the
	 * FORM. It is a LIST that has a source and a REPETITION that has none: the injected
	 * drug-reference record renders a substance's own codes as a round-parenthesised list, so two
	 * DIFFERENT codes in one parenthetical are deliberately left alone — but it builds that list from
	 * {@code DrugReference.normalizedAtcCodes()}, which returns a {@code Set}, and the chip's class
	 * sentence renders one subgroup. Several renderers parenthesise dataset- or operator-authored
	 * FREE TEXT beside a code — a drug class, an interaction note, a severity, a cross-reactivity
	 * group name, a generic name — so the guarantee is about the data rather than about the code, and
	 * it is stated as a measurement rather than as a list of renderers that would go stale.
	 *
	 * <p>Measured over all three shipped reference files, by driving the real parsers and this
	 * class's own {@link #classCodesIn}: over the knowledge base's 2283 entries and their 590,312
	 * interactions, counting one field at a time — drug class, generic name and display label per
	 * entry, note and severity per interaction, note and token per contraindication, 1,187,473
	 * fields in all — ZERO state an ATC-shaped token. State the base as well as the count: the
	 * contraindication clause contributes none of those fields, the shipped KB parsing to zero
	 * contraindications. The curated file's 4 rows carry a drug class stating none, and the one
	 * shipped cross-reactivity group is named {@code NSAID}. So no shipped data can put a code
	 * inside a parenthetical twice. An operator file that states one in any of those fields can,
	 * and an answer quoting that record faithfully is then reported.
	 *
	 * <p><b>Every parenthetical is read AT ITS OWN LEVEL</b> ({@link #parentheticalsAtTheirOwnLevel}),
	 * over a bracket walk rather than a regex: a nested group's codes and markers are that group's,
	 * not its parent's. Both rules are then about one pair of brackets, which is what they say. An
	 * unmatched {@code (} opens nothing — it neither pools a paragraph into one "parenthetical" nor
	 * silences the groups after it — and an unmatched {@code )} closes nothing. That walk's javadoc
	 * carries what the two rejected readings cost, both of which shipped in review.
	 *
	 * <p><b>It reports; it never rewrites</b>, for the reason the membership report gives and not for
	 * a new one: editing a clinician-facing sentence is a larger decision than this check is licensed
	 * to make, and a silent edit is worse than a visible flag (ADR Decision 35, point 4). It is NOT
	 * because the streaming path has already handed the answer out — {@code normalizeSlashCitations}
	 * already ships a repair that applies to the final answer only, with the client contract that
	 * makes it safe, so streaming forces nothing here.
	 *
	 * <p><b>What the false-alarm rate is, is unmeasured.</b> ADR Decision 35's own gates were
	 * justified by driving {@link #classCodesIn} over 340 live captured answers; that corpus is
	 * outside this repository and the fixture tree inside it is structurally blind to these rules —
	 * measured here by driving {@link #classCodesIn} itself over that tree (72 json files, 42 captured
	 * answers, ZERO stating an ATC-shaped token; 0 repetitions and 0 enclosed markers, both
	 * vacuously), so "no hits in the fixtures" is vacuous rather than evidence.
	 */
	private static void reportMalformedParentheticals(Integer patientId, String answer,
			List<Integer> cited) {
		// cited: the validated indexes for THIS answer, as resolved by reportClassCodeDefects, whose
		// @param cited carries the contract. One call site, after both abstain gates.
		Set<String> repeated = new LinkedHashSet<String>();
		Set<String> enclosed = new LinkedHashSet<String>();
		for (String group : parentheticalsAtTheirOwnLevel(answer)) {
			// Through classCodesIn, not a second reading of the pattern: that accessor is what this
			// class means by "the codes a text states", and a filter added to it later (dropping a
			// level too coarse to be a claim, say) has to reach these two rules as well as the
			// membership one.
			Set<String> stated = classCodesIn(group);
			if (stated.isEmpty()) {
				// A parenthetical stating no class code is out of both rules' subject matter: an
				// aside, a gloss, a blood pressure. Reporting a marker inside one would make this a
				// rule about brackets rather than about codes.
				continue;
			}
			// Multiplicity is the one thing classCodesIn's Set discards, so it is counted here — but
			// only for codes that reading admits, so a filter added there governs this rule too.
			// Nothing in the suite can discriminate that conjunct while classCodesIn filters nothing:
			// it is written for the change that gives it something to filter, not for today.
			Set<String> once = new LinkedHashSet<String>();
			Matcher codes = ATC_CLASS_CODE.matcher(group);
			while (codes.find()) {
				String code = codes.group();
				if (stated.contains(code) && !once.add(code)) {
					repeated.add(code);
				}
			}
			// The shared decode step over INLINE_CITATION, never a second matcher: this class must
			// not carry a bracket-marker dialect of its own, and it needs no text offset — the group
			// it is asking about IS the substring (CLAUDE.md's inline-citation rule). Single-index
			// by that rule's own design, so a compact group the structured citations array does not
			// corroborate — {@code (H02AB [12, 13])} — is not read as markers here and is missed.
			Set<Integer> inGroup = ChartSearchAiUtils.citedIndexes(group);
			// Only markers the answer's own resolution admitted: this method is handed the same
			// validated list the membership report cites, rather than re-deriving citedness from the
			// prose, which reportClassCodeDefects' @param cited forbids one arity up. State the bound
			// exactly, because a weaker one was written here first and is what a reader will assume.
			// extractCitedReferences promotes every IN-RANGE bracketed integer in the answer to a
			// citation, so what this excludes is a bracketed number the chart has NO record for
			// ("(J01MA, dose [97] mg)" on a 40-record chart) — not bracketed clinical values as such.
			// An eGFR of 45 beside a 45-record chart is still reported, and that residue is real.
			inGroup.retainAll(cited);
			if (!inGroup.isEmpty()) {
				// One entry per PAIRING, not two pooled lists: with two offending groups in one
				// answer, pooled lists say which codes and which markers occurred and no longer say
				// which sat with which, and reconstructing the placement from the line alone is what
				// this check's logging is for. A Set, so two groups stating the same codes with the
				// same markers are one entry — the line reports distinct placements, not a count of
				// offending brackets, and it is worded that way.
				enclosed.add(stated + " with " + inGroup);
			}
		}
		// Neither line logs the answer or the record text — they carry patient data, and the codes
		// with the patient identify the claim. The same rule the membership report follows.
		if (!repeated.isEmpty()) {
			log.warn("Answer for patient={} states ATC class code(s) {} more than once inside one "
					+ "parenthetical; nothing this module renders states one code twice inside one. "
					+ "The answer prose is left unchanged (issue #338).", patientId, repeated);
		}
		if (!enclosed.isEmpty()) {
			log.warn("Answer for patient={} places citation marker(s) inside a parenthetical stating "
					+ "ATC class code(s), one entry per distinct code-and-marker pairing: {}; a marker "
					+ "attributes the clause and belongs after it. The answer prose is left unchanged "
					+ "(issue #338).", patientId, enclosed);
		}
	}

	/**
	 * @return the text of every {@code (...)} group in {@code text} AT ITS OWN LEVEL — the content
	 *         between its brackets with each nested group's own span REPLACED BY A SPACE, since that
	 *         span is its own entry — in opening order, one entry per matched {@code (}. An unmatched
	 *         {@code (} opens nothing and an unmatched {@code )} closes nothing. Empty for null text.
	 *         A space and not nothing: the replacement is a separator, or the text either side of a
	 *         nested span is welded into one run and both rules read TOKENS off the result.
	 *
	 *         <p><b>Own level, and both halves of that were wrong once.</b> Reading only the
	 *         OUTERMOST group emitted nothing at all while an unclosed {@code (} was open, so one
	 *         stray bracket made both rules blind to the whole remainder of an answer — silently,
	 *         fail-open, on exactly the malformed prose they are about. Reading every group's WHOLE
	 *         text instead put a child's codes and markers into its parent as well, so two clauses
	 *         wrapped in one aside ({@code (levofloxacin (J01MA) and moxifloxacin (J01MA))}) reported
	 *         a repetition, and a marker correctly placed after an inner clause
	 *         ({@code (ATC class (J01MA) [3])}) reported a misplacement — both correct prose, and the
	 *         cries-wolf direction this check must not fail in. Pairing the brackets first and then
	 *         reading each group at its own level is what answers all three, and it is linear: every
	 *         character belongs to exactly one level, and an ancestor steps over a nested span in one
	 *         jump. The whole-text form was measured quadratic — seconds of blocking-path CPU and a
	 *         multi-megabyte WARN line on a deeply bracketed answer.
	 */
	private static List<String> parentheticalsAtTheirOwnLevel(String text) {
		List<String> groups = new ArrayList<String>();
		if (text == null) {
			return groups;
		}
		int length = text.length();
		int[] closedAt = new int[length];
		Arrays.fill(closedAt, -1);
		Deque<Integer> open = new ArrayDeque<Integer>();
		for (int i = 0; i < length; i++) {
			char c = text.charAt(i);
			if (c == '(') {
				open.push(Integer.valueOf(i));
			}
			else if (c == ')' && !open.isEmpty()) {
				closedAt[open.pop().intValue()] = i;
			}
		}
		for (int i = 0; i < length; i++) {
			if (closedAt[i] < 0) {
				continue;
			}
			StringBuilder own = new StringBuilder();
			int j = i + 1;
			while (j < closedAt[i]) {
				if (closedAt[j] >= 0) {
					// A SPACE where the nested span was, never nothing: deleting it would weld the
					// text either side into one run and both rules read tokens. Measured — "(J01M(sic)A,
					// J01MA)" reported a repetition the prose does not have, "(J01MA [0(sic)3])" a marker
					// it does not carry, and "(J01MA()J01MA)" went silent on one it does.
					own.append(' ');
					j = closedAt[j] + 1;
				}
				else {
					own.append(text.charAt(j));
					j++;
				}
			}
			groups.add(own.toString());
		}
		return groups;
	}

}
