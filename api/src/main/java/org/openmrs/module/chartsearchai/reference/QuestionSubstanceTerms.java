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
import java.util.LinkedHashSet;
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
 */
final class QuestionSubstanceTerms {

	/** Each term, folded lower, and the display name of the row it names. */
	private static final Map<String, String> TERMS;

	static {
		Map<String, String> terms = new LinkedHashMap<String, String>();
		for (String term : Arrays.asList("alcohol", "alcoholic drink", "alcoholic drinks", "alcoholic beverage",
			"alcoholic beverages")) {
			terms.put(term, "Ethanol");
		}
		TERMS = Collections.unmodifiableMap(terms);
	}

	/** Words before "alcohol" that make the phrase another substance's name. */
	private static final Set<String> NAMING_ANOTHER_SUBSTANCE_BEFORE = new HashSet<String>(Arrays.asList("polyvinyl",
		"benzyl", "isopropyl", "cetyl", "stearyl", "cetostearyl", "cetearyl", "lanolin", "wool", "rubbing", "denatured",
		"methyl", "propyl", "butyl", "phenethyl", "phenylethyl", "dichlorobenzyl", "sugar", "fatty"));

	/** Words after "alcohol" that make the phrase a condition or a product rather than the drink. */
	private static final Set<String> NAMING_SOMETHING_ELSE_AFTER = new HashSet<String>(Arrays.asList("withdrawal",
		"dependence", "dependency", "abuse", "misuse", "intoxication", "poisoning", "swab", "swabs", "wipe", "wipes", "rub",
		"rubs", "gel", "gels", "hand", "based", "septal", "induced", "related"));

	private QuestionSubstanceTerms() {
	}

	/** The display names of the rows {@code foldedQuestion} names by a term, where it names the substance. */
	static Set<String> rowNamesIn(String foldedQuestion) {
		Set<String> names = new LinkedHashSet<String>();
		for (Map.Entry<String, String> term : TERMS.entrySet()) {
			if (!spansOf(foldedQuestion, term.getKey()).isEmpty()) {
				names.add(term.getValue());
			}
		}
		return names;
	}

	/** Where {@code foldedQuestion} names the row displayed as {@code rowName} by a term, as {start, end} spans. */
	static List<int[]> spansNaming(String foldedQuestion, String rowName) {
		List<int[]> spans = new ArrayList<int[]>();
		for (Map.Entry<String, String> term : TERMS.entrySet()) {
			if (term.getValue().equals(rowName)) {
				spans.addAll(spansOf(foldedQuestion, term.getKey()));
			}
		}
		return spans;
	}

	/** The terms each spelled exactly as here — for a data guard over the shipped knowledge base. */
	static Map<String, String> terms() {
		return TERMS;
	}

	private static List<int[]> spansOf(String folded, String term) {
		if (folded == null) {
			return Collections.emptyList();
		}
		List<int[]> spans = new ArrayList<int[]>();
		for (int at = DrugReference.wordIndex(folded, term, 0); at >= 0; at = DrugReference.wordIndex(folded, term, at + 1)) {
			int end = at + term.length();
			String next = wordAfter(folded, end);
			// "alcohol use disorder" is a condition, where "alcohol use" alone is the drinking.
			boolean useDisorder = "use".equals(next)
					&& wordAfter(folded, folded.indexOf(next, end) + next.length()).startsWith("disorder");
			if (!NAMING_ANOTHER_SUBSTANCE_BEFORE.contains(wordBefore(folded, at))
					&& !NAMING_SOMETHING_ELSE_AFTER.contains(next) && !useDisorder) {
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
