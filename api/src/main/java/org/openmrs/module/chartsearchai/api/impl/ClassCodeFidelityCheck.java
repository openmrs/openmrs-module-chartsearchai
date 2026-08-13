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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports an ATC classification code the answer states that no record the answer CITES contains
 * (issue #142) — a deterministic, exact check for the one failure the semantic grounding pass is
 * structurally unable to see.
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
 * <p><b>Two of those modes this check cannot see, by construction.</b> A duplicated correct code is
 * textually supported by its record, and so is any code the answer OMITS — this reads what the
 * answer states, never what it fails to state. Detecting either needs a comparison against the
 * chips the answer was expected to report, which is a different question with a different failure
 * mode (a chip the model deliberately did not repeat is not an error).
 *
 * <p><b>Why the existing passes cannot catch it.</b> {@link CitationGroundingVerifier}'s Tier-1 is
 * cosine similarity over the cited record's text, and a two-character edit inside an alphanumeric
 * token moves an embedding almost not at all; its Tier-2 entailment is paraphrase-tolerant, which
 * is exactly the wrong tolerance for a code substitution. Index validation passes too, because the
 * citation really does point at the record. So this check is not more grounding: it is an exact
 * token comparison, which is the only thing that separates {@code J01CA} from {@code J01MA}.
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
 * re-checked either — it was checked when it was produced.
 *
 * <p><b>Conservative by construction</b>, because a check that cries wolf is worse than no check:
 * <ul>
 *   <li>it compares WHOLE tokens, in both directions — a code the record does not state as a token
 *       is unsupported even when it is a prefix of one that it does ({@code A02B} against
 *       {@code A02BC}), and a code stated beside the true one is still unsupported;</li>
 *   <li>it abstains for the whole answer when any cited record carries no readable text, since a
 *       record we could not read may be the one that states the code — the same "cannot verify"
 *       treatment {@link CitationGroundingVerifier} gives a null/blank record text;</li>
 *   <li>it matches only upper-case tokens, the form the WHO ATC index publishes and the form
 *       {@code DrugReference.normalizeAtcToken} renders, so no lower-case word can be mistaken for
 *       a code. A lower-cased code in prose is therefore a silent pass, deliberately — and one that
 *       costs nothing measured: over the 340 answers captured from this pipeline's live probe
 *       sweeps (2026-08-14), reading the same shape case-insensitively matched not one further
 *       token.</li>
 * </ul>
 */
final class ClassCodeFidelityCheck {

	private static final Logger log = LoggerFactory.getLogger(ClassCodeFidelityCheck.class);

	/**
	 * An ATC classification code as it occurs in prose: a level-3 group ({@code J01M}), a level-4
	 * subgroup ({@code J01MA}) or a level-5 substance code ({@code J01MA02}). Level 1 (a single
	 * letter) and level 2 ({@code J01}) are deliberately out: they are too short to tell from
	 * ordinary text, and no chip or record states them.
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
	static final Pattern ATC_CLASS_CODE = Pattern.compile(
			"(?<![A-Za-z0-9])[A-Z]\\d{2}[A-Z](?:[A-Z](?:\\d{2})?)?(?![A-Za-z0-9])");

	private ClassCodeFidelityCheck() {
	}

	/**
	 * @return every ATC-shaped code token in {@code text}, in first-appearance order; empty for
	 *         null text. Used for both sides of the comparison — what the answer states and what a
	 *         record states — so the two can never be read by different rules.
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
	 * cites contains.
	 *
	 * @param answer the answer prose, unchanged by this method
	 * @param cited the references the answer cites, as resolved by
	 *            {@link LlmInferenceService#extractCitedReferences} — the union of the inline
	 *            {@code [N]} markers and the structured citations array, index-validated. Taking
	 *            the accessor's own output rather than re-deriving it from the prose is what keeps
	 *            "which records were cited" a single answer: it is also what the clinician can
	 *            click, so a code traceable to none of them is traceable to nothing the reader can
	 *            check. An answer that cites nothing therefore supports no code.
	 * @param mappings the chart's records, cited or not — the carrier of the cited records' text
	 */
	static void reportUnsupportedClassCodes(String answer, List<RecordReference> cited,
			List<RecordMapping> mappings) {
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
		Set<String> supported = new LinkedHashSet<String>();
		if (cited != null) {
			for (RecordReference reference : cited) {
				Integer index = Integer.valueOf(reference.getIndex());
				String text = textByIndex.get(index);
				if (ChartSearchAiUtils.isBlank(text)) {
					// Abstain for the whole answer: an unreadable cited record may be the one that
					// states the code, and accusing the prose of a code we could not look for is
					// exactly the false alarm this check must not raise.
					log.debug("Class-code check skipped: cited record [{}] carries no text", index);
					return;
				}
				citedIndexes.add(index);
				supported.addAll(classCodesIn(text));
			}
		}
		List<String> unsupported = new ArrayList<String>();
		for (String code : stated) {
			if (!supported.contains(code)) {
				unsupported.add(code);
			}
		}
		if (!unsupported.isEmpty()) {
			// Both halves are needed to reconstruct it from logs: the code the model wrote, and the
			// codes it could have copied. Neither the answer nor the record text is logged — they
			// carry patient data, and the codes alone identify the claim.
			log.warn("Answer states ATC class code(s) {} that no cited record contains; cited "
					+ "record(s) {} state {}. The answer prose is left unchanged (issue #142).",
					unsupported, citedIndexes, supported);
		}
	}
}
