/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which drug CLASS a question names, when it names one — the vocabulary behind
 * {@link DrugReferenceService#namedDrugClass}, and nothing else (issue #354).
 *
 * <p>It classifies a STRING, not a substance: the answer is a class NAME and never a member set.
 * That boundary is the whole of the issue's design decision and is argued at
 * {@link DrugReferenceService#namedDrugClass}; read it there rather than here.
 *
 * <p><b>Two sources, and each is here because the other cannot serve its case.</b> A class the
 * curated cross-reactivity groups already name is read from THAT data — it is a curated,
 * operator-extensible class-name table with its own load-validity channel
 * ({@code CrossReactivityGroupsLoad.getFindings()}, issue #266), and the shipped seed's one group is
 * named {@code NSAID}, which is one of the two classes this issue reports. The table below carries
 * what that file cannot: a class it does not name at all, and a further SPELLING of one it does.
 *
 * <p><b>Why a class it does not name cannot simply be added to it.</b> A group is not a label. Its
 * {@code atcPrefixes} drive clinical claims — cross-reactivity contraindication chips and
 * duplicate-class-therapy chips, through {@link CrossReactivityGroup#groupsOf} and
 * {@link CrossReactivityGroup#sharedGroup} — so adding a group to make a word recognisable asserts
 * pharmacological cross-reactivity across those prefixes. And for the issue's headline class there
 * is no honest prefix set to add at all; the measurement that establishes it is at
 * {@link DrugReferenceService#namedDrugClass}, and is not repeated here.
 *
 * <p><b>The two sources are independent, and neither is subordinate to the other.</b> Each names the
 * classes it names; the answer is the union. In particular the table below names {@code NSAID}
 * OUTRIGHT rather than as a spelling of the shipped group, and that is a decision with a measured
 * reason: {@code chartsearchai.drugReference.crossReactivityGroupsFilePath} is a supported
 * configuration, so making the class conditional on some loaded group publishing that exact name
 * returned the issue's own {@code an NSAID} case to pre-#354 silence — with nothing logged — on any
 * deployment that curates its own groups file. The groups leg is there to honour a vocabulary an
 * operator has already curated, not to license this one.
 *
 * <p>What the boundary rule forces either way is that a class needs its SPELLINGS enumerated:
 * {@link DrugReference#containsWord}'s prose allowance is zero trailing letters, so {@code NSAIDs} is
 * not the term {@code NSAID}, and a group publishes one {@code name}. A class an operator's file
 * names is therefore recognised in that one spelling; one named here is recognised in as many as are
 * written out.
 *
 * <p><b>What may be admitted, stated so it can be re-applied.</b> A term is admitted only when
 * <ol>
 * <li>it designates a drug CLASS — a pharmacological, chemical or therapeutic grouping — rather than
 * the name of any single substance;</li>
 * <li>it resolves to no entry of the shipped knowledge base, which is a DATA GUARD
 * ({@code DrugClassQuestionNoteTest.everyCuratedClassTermResolvesToNoSubstanceInTheShippedKnowledgeBase},
 * through {@link DrugReferenceService#findImpliedByQuery}) and not a claim made here; and</li>
 * <li>where a curated cross-reactivity group already publishes the same class, the duplication is
 * DELIBERATE rather than forbidden — see the paragraph above on why {@code NSAID} is named here
 * outright.</li>
 * </ol>
 * Incompleteness is MONOTONE and that is what makes a partial vocabulary safe: an unrecognised class
 * term leaves the module exactly as silent as it is today, while a wrongly admitted one would have
 * the module call a drug a class — which is what (2) exists to prevent.
 *
 * <p><b>Residue, stated rather than claimed away.</b> {@link DrugReference#containsWord} does not
 * collapse whitespace ({@code boundedTokenIndex} is an {@code indexOf} over folded operands), so a
 * question spelling {@code oral  contraceptive} with two spaces, or breaking the phrase across a
 * line, carries no term. That is the same rule every drug alias is matched under, and giving this
 * one its own would be a caller choosing an allowance of its own.
 */
final class DrugClassTerms {

	/**
	 * Term → the class name a note reports it as, which is the class the QUESTION named and never a
	 * wider or narrower one: {@code hormonal contraceptive} is not a spelling of {@code oral
	 * contraceptive} but a different class, so it reports itself. The value column exists for the
	 * genuine spellings beside it. Insertion-ordered; where two terms of the same length are both
	 * carried the order does decide, which is the residue {@link #namedIn} states.
	 *
	 * <p>Three classes. Two are the issue's — {@code oral contraceptive}, which no ATC subtree
	 * expresses, and {@code NSAID}, which the shipped curated group also names, deliberately both for
	 * the reason the class javadoc gives. The third, {@code hormonal contraceptive}, is not the
	 * issue's: it is here because reporting a hormonal question as the narrower oral class would state
	 * something false about the question in a record the answer may cite.
	 *
	 * <p>Lower-cased at the source rather than at the scan: {@link DrugReference#containsWord} folds
	 * and lower-cases both operands for itself, so these are written in the form a reader compares
	 * against the question, not in a form this class has to normalise.
	 */
	private static final Map<String, String> TERMS;

	static {
		Map<String, String> terms = new LinkedHashMap<String, String>();
		terms.put("oral contraceptive", "oral contraceptive");
		terms.put("oral contraceptives", "oral contraceptive");
		terms.put("contraceptive pill", "oral contraceptive");
		terms.put("contraceptive pills", "oral contraceptive");
		terms.put("birth control pill", "oral contraceptive");
		terms.put("birth control pills", "oral contraceptive");
		terms.put("hormonal contraceptive", "hormonal contraceptive");
		terms.put("hormonal contraceptives", "hormonal contraceptive");
		terms.put("nsaid", "NSAID");
		terms.put("nsaids", "NSAID");
		terms.put("non-steroidal anti-inflammatory", "NSAID");
		terms.put("nonsteroidal anti-inflammatory", "NSAID");
		TERMS = Collections.unmodifiableMap(terms);
	}

	private DrugClassTerms() {
	}

	/** The code table's terms, for the data guard that enforces admission criterion (2). It is the
	 *  code table alone: a group's name comes from operator data, which no test in this module can
	 *  speak for. */
	static Set<String> terms() {
		return TERMS.keySet();
	}

	/**
	 * @return the class name of the LONGEST class term {@code prose} carries, or {@code null} where it
	 *         carries none. Longest so that a question spelling a class two ways at once — or a term
	 *         nested in a longer one — reports the more specific of them rather than whichever source
	 *         was consulted first, which would otherwise make the answer depend on the order the
	 *         operator's groups file happens to list its groups in.
	 *
	 *         <p>The residue, named rather than claimed away: the comparison is strict, so two terms
	 *         of EQUAL length that a question carries at once are still decided by order — the curated
	 *         groups ahead of the table below, and within the groups the order the operator's file
	 *         lists them in. Nothing pins that, because nothing distinguishes the two answers on any
	 *         ground this method has: both are class names the question really does carry.
	 *
	 * @param prose the question text; {@code null} carries no term
	 * @param groups the curated cross-reactivity groups, whose {@code name}s are the first source —
	 *        never null in production ({@code DrugReferenceService.getCrossReactivityGroups} answers a
	 *        list), and tolerated as null here so the rule can be read without one. Whitespace is not
	 *        stripped here: {@link CrossReactivityGroup#setName} trims at the writer, so a padded
	 *        operator-authored name — which the loader accepts, rejecting only a blank one — is
	 *        already trimmed by the time any reader, this one or the chip arms, sees it
	 */
	static String namedIn(String prose, List<CrossReactivityGroup> groups) {
		if (prose == null || prose.trim().isEmpty()) {
			return null;
		}
		String bestTerm = null;
		String bestClass = null;
		if (groups != null) {
			for (CrossReactivityGroup group : groups) {
				// Not trimmed here: CrossReactivityGroup.setName trims at the sole WRITER, so every
				// reader — this one and the chip arms that PRINT the name — sees one spelling. A trim
				// at this reader alone named one family two ways in one response.
				String name = group == null ? null : group.getName();
				if (name != null && longerThan(name, bestTerm) && DrugReference.containsWord(prose, name)) {
					bestTerm = name;
					bestClass = name;
				}
			}
		}
		for (Map.Entry<String, String> term : TERMS.entrySet()) {
			if (longerThan(term.getKey(), bestTerm) && DrugReference.containsWord(prose, term.getKey())) {
				bestTerm = term.getKey();
				bestClass = term.getValue();
			}
		}
		return bestClass;
	}

	/** Whether {@code candidate} is longer than the term already matched, {@code null} counting as no
	 *  match at all — the comparison the longest-match rule is written in terms of, named so the two
	 *  loops above cannot express it differently. */
	private static boolean longerThan(String candidate, String matched) {
		return matched == null || candidate.length() > matched.length();
	}

}
