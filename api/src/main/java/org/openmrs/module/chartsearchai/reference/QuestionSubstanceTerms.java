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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Words a clinician's QUESTION names a substance by that the knowledge base cannot carry as one of its names (ADR Decision
 * 268): <em>alcohol</em>, for the row the knowledge base calls {@code Ethanol}. <em>"Can she drink alcohol while on
 * metronidazole?"</em> resolved no drug, though the knowledge base rates ethanol with metronidazole Major.
 *
 * <p><b>Why not a name in the knowledge base.</b> A name the knowledge base carries is matched against every recorded drug
 * name and every prose this module reads — an order, an allergy, an answer — and "alcohol" is a word of other substances'
 * names: an order of polyvinyl alcohol eye drops would have been read as ethanol, and raised ethanol's interactions about a
 * drug she takes. The curated international names leave out ordinary words for the same reason. So a term here is read in
 * the question alone, through {@code DrugReferenceService.findImpliedByQuestion} and the question's own marking
 * ({@code DrugReferenceInjector.occurrencesInTheQuestion}), and never as one of the row's names.
 *
 * <p><b>Where it names the substance.</b> Not where the word before it makes the phrase another substance's name
 * (<em>benzyl alcohol</em>, <em>isopropyl alcohol</em>), nor where the word after it makes it a condition or a product
 * (<em>alcohol withdrawal</em>, <em>alcohol use disorder</em>, <em>alcohol swabs</em>): <em>"Can I give her diazepam for
 * alcohol withdrawal?"</em> asks about diazepam. <b>The residue</b>: such a phrase the lists below do not carry is read as
 * ethanol, and a drink named any other way (<em>beer</em>, <em>wine</em>) is not.
 *
 * <p><b>And a drug named by its moiety</b> (ADR Decision 284): <em>lithium</em>, which the knowledge base files only as
 * Lithium carbonate and keeps out of that row's names because it is also a laboratory test's name, and <em>tenofovir</em>,
 * whose two forms it carries apart. A term naming several rows names every one of them, and where her orders hold some of
 * them, those alone ({@code DrugReferenceService.findImpliedByQuestion}).
 */
final class QuestionSubstanceTerms {

	/**
	 * What a term names: the display names of the rows — one, or the forms the knowledge base carries apart, every one of
	 * which the term names — and the words before or after it that make the phrase another name.
	 */
	private static final class Term {

		final List<String> rowNames;

		final Set<String> namingAnotherBefore;

		final Set<String> namingSomethingElseAfter;

		/** Whether "&lt;term&gt; use disorder" is a condition, where "&lt;term&gt; use" alone is the substance. */
		final boolean useDisorderIsACondition;

		Term(List<String> rowNames, Set<String> namingAnotherBefore, Set<String> namingSomethingElseAfter,
				boolean useDisorderIsACondition) {
			this.rowNames = Collections.unmodifiableList(rowNames);
			this.namingAnotherBefore = namingAnotherBefore;
			this.namingSomethingElseAfter = namingSomethingElseAfter;
			this.useDisorderIsACondition = useDisorderIsACondition;
		}
	}

	/** Words before "alcohol" that make the phrase another substance's name. */
	private static final Set<String> NAMING_ANOTHER_SUBSTANCE_BEFORE = new HashSet<String>(Arrays.asList("polyvinyl",
		"benzyl", "isopropyl", "cetyl", "stearyl", "cetostearyl", "cetearyl", "lanolin", "wool", "rubbing", "denatured",
		"methyl", "propyl", "butyl", "phenethyl", "phenylethyl", "dichlorobenzyl", "sugar", "fatty"));

	/** Words after "alcohol" that make the phrase a condition or a product rather than the drink. */
	private static final Set<String> NAMING_SOMETHING_ELSE_AFTER = new HashSet<String>(Arrays.asList("withdrawal",
		"dependence", "dependency", "abuse", "misuse", "intoxication", "poisoning", "swab", "swabs", "wipe", "wipes", "rub",
		"rubs", "gel", "gels", "hand", "based", "septal", "induced", "related"));

	/** Each term, folded lower, and what it names. */
	private static final Map<String, Term> TERMS;

	static {
		Map<String, Term> terms = new LinkedHashMap<String, Term>();
		Term ethanol = new Term(Arrays.asList("Ethanol"), NAMING_ANOTHER_SUBSTANCE_BEFORE, NAMING_SOMETHING_ELSE_AFTER, true);
		for (String term : Arrays.asList("alcohol", "alcoholic drink", "alcoholic drinks", "alcoholic beverage",
			"alcoholic beverages")) {
			terms.put(term, ethanol);
		}
		// The drug a clinician names by the moiety the knowledge base files only under its salt or its prodrugs (ADR
		// Decision 284): "lithium", whose one row is Lithium carbonate and which the knowledge base's own curation keeps
		// out of its names because it is also a laboratory test's; and "tenofovir", whose two forms it carries apart and
		// names in full — so "tenofovir disoproxil" is that form's own name and not this term.
		terms.put("lithium", new Term(Arrays.asList("Lithium carbonate"), Collections.<String> emptySet(),
			Collections.<String> emptySet(), false));
		terms.put("tenofovir", new Term(Arrays.asList("Tenofovir disoproxil", "Tenofovir alafenamide"),
			Collections.<String> emptySet(), new HashSet<String>(Arrays.asList("disoproxil", "alafenamide")), false));
		TERMS = Collections.unmodifiableMap(terms);
	}

	private QuestionSubstanceTerms() {
	}

	/** The display names of the rows {@code foldedQuestion} names by a term, where it names them: one group per term, the
	 *  rows that one term names. */
	static List<List<String>> rowNameGroupsIn(String foldedQuestion) {
		List<List<String>> groups = new ArrayList<List<String>>();
		for (Map.Entry<String, Term> term : TERMS.entrySet()) {
			if (!spansOf(foldedQuestion, term.getKey(), term.getValue()).isEmpty() && !groups.contains(term.getValue().rowNames)) {
				groups.add(term.getValue().rowNames);
			}
		}
		return groups;
	}

	/** Where {@code foldedQuestion} names the row displayed as {@code rowName} by a term, as {start, end} spans. */
	static List<int[]> spansNaming(String foldedQuestion, String rowName) {
		List<int[]> spans = new ArrayList<int[]>();
		for (Map.Entry<String, Term> term : TERMS.entrySet()) {
			if (term.getValue().rowNames.contains(rowName)) {
				spans.addAll(spansOf(foldedQuestion, term.getKey(), term.getValue()));
			}
		}
		return spans;
	}

	/** The terms each spelled exactly as here, and the rows each names — for a data guard over the shipped knowledge
	 *  base. */
	static Map<String, List<String>> terms() {
		Map<String, List<String>> terms = new LinkedHashMap<String, List<String>>();
		for (Map.Entry<String, Term> term : TERMS.entrySet()) {
			terms.put(term.getKey(), term.getValue().rowNames);
		}
		return terms;
	}

	private static List<int[]> spansOf(String folded, String term, Term naming) {
		if (folded == null) {
			return Collections.emptyList();
		}
		List<int[]> spans = new ArrayList<int[]>();
		for (int at = DrugReference.wordIndex(folded, term, 0); at >= 0; at = DrugReference.wordIndex(folded, term, at + 1)) {
			int end = at + term.length();
			String next = wordAfter(folded, end);
			// "alcohol use disorder" is a condition, where "alcohol use" alone is the drinking.
			boolean useDisorder = naming.useDisorderIsACondition && "use".equals(next)
					&& wordAfter(folded, folded.indexOf(next, end) + next.length()).startsWith("disorder");
			if (!naming.namingAnotherBefore.contains(wordBefore(folded, at))
					&& !naming.namingSomethingElseAfter.contains(next) && !useDisorder) {
				spans.add(new int[] { at, end });
			}
		}
		return spans;
	}

	/** The word ending just before {@code at}, past the separators between them — "" at the start. */
	private static String wordBefore(String text, int at) {
		int end = at;
		while (end > 0 && !Character.isLetterOrDigit(text.charAt(end - 1))) {
			end--;
		}
		int start = end;
		while (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) {
			start--;
		}
		return text.substring(start, end);
	}

	/** The word starting just after {@code end}, past the separators between them — "" at the end. */
	private static String wordAfter(String text, int end) {
		int start = Math.min(end, text.length());
		while (start < text.length() && !Character.isLetterOrDigit(text.charAt(start))) {
			start++;
		}
		int stop = start;
		while (stop < text.length() && Character.isLetterOrDigit(text.charAt(stop))) {
			stop++;
		}
		return text.substring(start, stop);
	}
}
