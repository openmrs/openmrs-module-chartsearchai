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
import java.util.LinkedHashSet;
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
 * <p><b>Why a further spelling has to live here rather than in that file.</b> The boundary rule is
 * {@link DrugReference#containsWord}, whose prose allowance is zero trailing letters, so
 * {@code NSAIDs} is not the term {@code NSAID}; and a group publishes one {@code name}. Carrying the
 * plural here adds a SPELLING of a class that file still names, not a second registry of classes —
 * and "still names" is enforced rather than intended: a spelling listed in
 * {@link #SPELLINGS_OF_A_CURATED_CLASS} answers only while some loaded group publishes exactly the
 * class name it reports, so removing or renaming that group loses the class outright instead of
 * leaving the code table answering for data the deployment no longer has.
 *
 * <p><b>What may be admitted, stated so it can be re-applied.</b> A term is admitted only when
 * <ol>
 * <li>it designates a drug CLASS — a pharmacological, chemical or therapeutic grouping — rather than
 * the name of any single substance;</li>
 * <li>it resolves to no entry of the shipped knowledge base, which is a DATA GUARD
 * ({@code DrugClassQuestionNoteTest.everyCuratedClassTermResolvesToNoSubstanceInTheShippedKnowledgeBase},
 * through {@link DrugReferenceService#findImpliedByQuery}) and not a claim made here; and</li>
 * <li>it is not a spelling a curated cross-reactivity group already publishes as its {@code name}.</li>
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
	 * <p>Two classes, and both are the issue's. The contraceptive terms are here because no ATC
	 * subtree expresses that class (above). The NSAID terms are spellings only — the class itself is
	 * named by the shipped curated group, so {@code nsaid} is deliberately absent from this table and
	 * a deployment whose groups file drops that group loses the class rather than keeping a copy of
	 * it here.
	 *
	 * <p>Lower-cased at the source rather than at the scan: {@link DrugReference#containsWord} folds
	 * and lower-cases both operands for itself, so these are written in the form a reader compares
	 * against the question, not in a form this class has to normalise.
	 */
	private static final Map<String, String> TERMS;

	/**
	 * The subset of {@link #TERMS} whose class the CURATED GROUPS name, keyed the same way. A term in
	 * here is a further spelling of a class that file owns, so it answers only while that file still
	 * names the class — otherwise the code table would keep a copy of a class name the deployment's
	 * data no longer publishes, which is the thing having two sources must not do. Removing the
	 * shipped {@code NSAID} group therefore loses the class outright rather than leaving these three
	 * spellings answering for it.
	 */
	private static final Set<String> SPELLINGS_OF_A_CURATED_CLASS;

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
		terms.put("nsaids", "NSAID");
		terms.put("non-steroidal anti-inflammatory", "NSAID");
		terms.put("nonsteroidal anti-inflammatory", "NSAID");
		TERMS = Collections.unmodifiableMap(terms);

		Set<String> spellings = new LinkedHashSet<String>();
		spellings.add("nsaids");
		spellings.add("non-steroidal anti-inflammatory");
		spellings.add("nonsteroidal anti-inflammatory");
		SPELLINGS_OF_A_CURATED_CLASS = Collections.unmodifiableSet(spellings);
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
			if (!longerThan(term.getKey(), bestTerm) || !DrugReference.containsWord(prose, term.getKey())) {
				continue;
			}
			if (SPELLINGS_OF_A_CURATED_CLASS.contains(term.getKey())
					&& !namesALoadedGroup(term.getValue(), groups)) {
				// A spelling of a class the groups file owns, on a deployment whose file no longer
				// names it. Declining is what keeps that file the sole registry of the CLASS: answering
				// here would report a class name this deployment's data does not publish, and would
				// give one question two answers where an operator had renamed the group.
				continue;
			}
			bestTerm = term.getKey();
			bestClass = term.getValue();
		}
		return bestClass;
	}

	/** @return whether some loaded group publishes exactly {@code className} as its name — the check
	 *          that keeps a {@link #SPELLINGS_OF_A_CURATED_CLASS} entry from outliving the data that
	 *          owns its class. Exact equality on the stored name, which
	 *          {@link CrossReactivityGroup#setName} has already trimmed. */
	private static boolean namesALoadedGroup(String className, List<CrossReactivityGroup> groups) {
		if (groups == null) {
			return false;
		}
		for (CrossReactivityGroup group : groups) {
			if (group != null && className.equals(group.getName())) {
				return true;
			}
		}
		return false;
	}

	/** Whether {@code candidate} is longer than the term already matched, {@code null} counting as no
	 *  match at all — the comparison the longest-match rule is written in terms of, named so the two
	 *  loops above cannot express it differently. */
	private static boolean longerThan(String candidate, String matched) {
		return matched == null || candidate.length() > matched.length();
	}

}
