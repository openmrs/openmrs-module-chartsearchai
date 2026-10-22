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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.module.chartsearchai.api.impl.QueryScopeRouter;

/**
 * A word standing where a question names the drug it asks about, which the interaction data does not recognise — and
 * the names it does carry that the word is closest to (ADR Decision 183).
 *
 * <p><b>The defect.</b> <em>"Can I give her rifampcin?"</em> resolved no drug, so no check ran; the model answered
 * <em>"The records do not address Rifampicin."</em>, and the post-answer pass, reading the model's corrected spelling,
 * raised a Major Rifampicin chip beside an answer saying the records did not address it. <em>"Can I give her
 * tenofovir?"</em> resolved nothing either — the data carries <em>Tenofovir disoproxil</em> and <em>Tenofovir
 * alafenamide</em>, two substances — and was answered <em>"The records do not address tenofovir."</em>, which reads as
 * nothing to worry about.
 *
 * <p><b>Never a guess.</b> Nothing here puts a drug in play: a wrong guess would screen the wrong drug, silently. The
 * statement says no check ran and names what the data carries, and the clinician asks again by that name.
 *
 * <p><b>Which word.</b> The question must fit one of the closed grammars that name a drug's place —
 * {@code QueryScopeRouter.asksWhetherToGiveADrug} or {@code asksWhetherADrugInteractsWithHerMedications} — with the word
 * (one or two of them) in that place, so a question naming no drug is never said to name an unrecognised one. Words that
 * are not names ("this drug", "it") are refused.
 *
 * <p><b>Which suggestions.</b> Of the names the data carries — every entry's name and aliases, folded — those the word
 * is the first word of (<em>insulin</em> → its forms; <em>tenofovir</em> is a question's name for its two since ADR
 * Decision 284, and never reaches here), else single-word names within one edit of it (two
 * for a word of eight letters or more; <em>rifampcin</em> → Rifampicin), one per substance, by the row that names it.
 */
final class UnrecognisedDrugName {

	/** Words that stand in a drug's place without naming one. */
	private static final Set<String> NOT_A_NAME = new HashSet<String>(Arrays.asList("this", "that", "these", "those",
		"the", "a", "an", "it", "them", "drug", "drugs", "medication", "medications", "medicine", "medicines", "something",
		"anything", "another", "other", "same", "new", "one", "some", "any", "more", "her", "his", "their", "him", "you",
		"she", "all", "too", "now", "two", "pain", "help", "food", "water", "fluids", "oxygen", "blood"));

	private static final int MAX_SUGGESTIONS = 4;

	/** The words naming what follows as the patient's own: "his ARVs", "their tablets" (ADR Decision 250). "Her" is one
	 *  too, except as the patient a verb of {@link #GIVING} gives to — "give her penicillin". */
	private static final Set<String> POSSESSIVES = new HashSet<String>(Arrays.asList("his", "their", "patient's"));

	private static final Set<String> GIVING = new HashSet<String>(Arrays.asList("give", "start", "prescribe", "administer",
		"add", "offer", "put", "get"));

	private final String word;

	private final List<String> suggestions;

	/** Whether the word stands in a switch's place (ADR Decision 261): no pair was the question, so no pair is named. */
	private final boolean inASwitch;

	/** Whether the question called the word hers while none of her orders carries it (ADR Decision 308). */
	private final boolean namedAsHersButNoOrder;

	private UnrecognisedDrugName(String word, List<String> suggestions) {
		this(word, suggestions, false);
	}

	private UnrecognisedDrugName(String word, List<String> suggestions, boolean inASwitch) {
		this(word, suggestions, inASwitch, false);
	}

	private UnrecognisedDrugName(String word, List<String> suggestions, boolean inASwitch, boolean namedAsHersButNoOrder) {
		this.word = word;
		this.suggestions = suggestions;
		this.inASwitch = inASwitch;
		this.namedAsHersButNoOrder = namedAsHersButNoOrder;
	}

	/**
	 * @return the unrecognised word standing in {@code question}'s drug place, with its suggestions, or {@code null}
	 *         where the question names no drug's place or names it with something that is not a name. The caller asks
	 *         this only where the question resolved no drug.
	 */
	static UnrecognisedDrugName in(String question, List<DrugReference> entries) {
		String word = wordInADrugsPlace(question);
		if (word == null) {
			// Or two words in a two-drug question's drug places, neither recognised (ADR Decision 382).
			return twoIn(question, entries);
		}
		// Something she would eat or drink is offered no drug's name: "Can she drink milk?" is no misspelled milk thistle
		// (ADR Decision 390).
		UnrecognisedDrugName name = new UnrecognisedDrugName(asWritten(word, question), toEatOrDrink(question, word)
				? new ArrayList<String>() : suggestionsFor(word, entries));
		name.inAStop = QueryScopeRouter.asksWhetherToStopHerDrug(QueryScopeRouter.words(
				(" " + question.toLowerCase(java.util.Locale.ROOT) + " ").replaceFirst("(?<=[^\\p{L}])" + java.util.regex.Pattern
						.quote(word) + "(?=[^\\p{L}])", " " + QueryScopeRouter.DRUG_NAME + " ")));
		// Named as hers — "his klarizom" — the question says she takes it, and none of her orders is named so (ADR Decision
		// 374), as Decision 308 says it beside a drug.
		name.namedAsHersAlone = QueryScopeRouter.asksAboutHerNamedDrug(QueryScopeRouter.words(
				(" " + question.toLowerCase(java.util.Locale.ROOT) + " ").replaceFirst("(?<=[^\\p{L}])" + java.util.regex.Pattern
						.quote(word) + "(?=[^\\p{L}])", " " + QueryScopeRouter.DRUG_NAME + " ")));
		return name;
	}

	/** The second word, where two stand in a two-drug question's drug places and the data recognises neither (ADR Decision
	 *  382); {@code null} for one. */
	private String second;

	private List<String> secondSuggestions = new ArrayList<String>();

	/** Whether either of two such words is named as hers — "his klarizom" (ADR Decision 382). */
	private boolean eitherNamedAsHers;

	/** The two words standing in a two-drug question's drug places, or {@code null} (ADR Decision 382). */
	private static UnrecognisedDrugName twoIn(String question, List<DrugReference> entries) {
		int[] at = twoWordsInDrugsPlaces(QueryScopeRouter.words(question));
		if (at == null) {
			return null;
		}
		List<String> words = QueryScopeRouter.words(question);
		String first = String.join(" ", words.subList(at[0], at[0] + at[1]));
		String other = String.join(" ", words.subList(at[2], at[2] + at[3]));
		UnrecognisedDrugName name = new UnrecognisedDrugName(asWritten(first, question), suggestionsFor(first, entries));
		name.second = asWritten(other, question);
		name.secondSuggestions = suggestionsFor(other, entries);
		name.eitherNamedAsHers = namedAsHers(words, at[0]) || namedAsHers(words, at[2]);
		return name;
	}

	/**
	 * Where two spans of one or two words stand in the drug places of a two-drug question — whether the two interact, are
	 * safe together, or one changes the other — as {start, length, start, length}, or {@code null}: each a name, neither a
	 * group of her medications nor her allergies (ADR Decision 382). The question resolved no drug, so neither is one the
	 * data recognises.
	 */
	private static int[] twoWordsInDrugsPlaces(List<String> words) {
		// One-word names before two-word ones, as wordInADrugsPlace reads one, so "his klarizom" is never a name.
		for (int la = 1; la <= 2; la++) {
			for (int lb = 1; lb <= 2; lb++) {
				for (int a = 0; a + la <= words.size(); a++) {
					for (int b = a + la + 1; b + lb <= words.size(); b++) {
						List<String> one = words.subList(a, a + la);
						List<String> two = words.subList(b, b + lb);
						if (!isADrugsName(one) || !isADrugsName(two)) {
							continue;
						}
						List<String> marked = new ArrayList<String>(words.subList(0, a));
						marked.add(QueryScopeRouter.DRUG_NAME);
						marked.addAll(words.subList(a + la, b));
						marked.add(QueryScopeRouter.DRUG_NAME);
						marked.addAll(words.subList(b + lb, words.size()));
						if (QueryScopeRouter.asksAboutTwoDrugsTogether(marked) || QueryScopeRouter.asksWhetherTwoDrugsInteract(marked)
								|| QueryScopeRouter.asksWhetherOneDrugChangesTheOther(marked)) {
							return new int[] { a, la, b, lb };
						}
					}
				}
			}
		}
		return null;
	}

	/** Whether {@code span} may be a drug's name standing in a two-drug question: a name, no possessive, and neither a group
	 *  of her medications nor her allergies (ADR Decision 382). */
	private static boolean isADrugsName(List<String> span) {
		String joined = String.join(" ", span);
		for (String w : span) {
			if (POSSESSIVES.contains(w) || "her".equals(w)) {
				return false;
			}
		}
		return isAName(span) && !joined.contains("allerg") && !QueryScopeRouter.namesAMedicationGroup(joined);
	}

	/** The verbs that make the word in a drug's place something she would eat or drink (ADR Decision 390). */
	private static final Set<String> EATING_OR_DRINKING = new HashSet<String>(Arrays.asList("eat", "eats", "eating", "drink",
		"drinks", "drinking"));

	/** Whether {@code question} has her eat or drink {@code word} — the word follows one of {@link #EATING_OR_DRINKING},
	 *  an article or "some" between them at most (ADR Decision 390). */
	private static boolean toEatOrDrink(String question, String word) {
		List<String> words = QueryScopeRouter.words(question);
		List<String> named = QueryScopeRouter.words(word);
		for (int i = 0; i + named.size() <= words.size(); i++) {
			if (!words.subList(i, i + named.size()).equals(named)) {
				continue;
			}
			int before = i - 1;
			while (before >= 0 && Arrays.asList("a", "an", "some", "any").contains(words.get(before))) {
				before--;
			}
			if (before >= 0 && EATING_OR_DRINKING.contains(words.get(before))) {
				return true;
			}
		}
		return false;
	}

	/** Whether the word stands in the place of a drug the question asks whether to stop (ADR Decision 335): what was not
	 *  done is a stop of an order none of hers is named for, not an interaction check. */
	private boolean inAStop;

	/** Whether the question names the word as hers, where it stands alone in a drug's place (ADR Decision 374). */
	private boolean namedAsHersAlone;

	/**
	 * @return the unrecognised word standing in the OTHER drug's place of a two-drug question whose one drug the data does
	 *         recognise — {@code questionDrugs}, one substance — with its suggestions, or {@code null} where no two-drug shape
	 *         puts a word there, or the word is no name (ADR Decision 250). <em>"Is penicillin safe with her
	 *         methotrexate?"</em>: the data names no drug "penicillin", so the pair was never checked, and the model answered
	 *         "The records do not address …" beside a Major rating for every penicillin it carries.
	 */
	static UnrecognisedDrugName besideANamedDrug(String question, List<DrugReference> questionDrugs,
			List<DrugReference> entries, PatientClinicalContext context) {
		List<String> words = DrugReferenceInjector.wordsBesideItsNames(question, questionDrugs);
		for (int length = 1; length <= 2; length++) {
			for (int i = 0; i + length <= words.size(); i++) {
				List<String> span = words.subList(i, i + length);
				if (span.contains(QueryScopeRouter.DRUG_NAME)) {
					continue;
				}
				List<String> marked = new ArrayList<String>(words.subList(0, i));
				marked.add(QueryScopeRouter.DRUG_NAME);
				marked.addAll(words.subList(i + length, words.size()));
				// A switch to or from it too (ADR Decision 261): "Can I replace her stavudine with tenofovir?".
				boolean inASwitch = QueryScopeRouter.asksWhetherToSwitchADrug(marked)
						|| QueryScopeRouter.asksWhetherToGiveADrugInsteadOfAnother(marked);
				if (!inASwitch && !QueryScopeRouter.asksAboutTwoDrugsTogether(marked)
						&& !QueryScopeRouter.asksWhetherTwoDrugsInteract(marked)
						&& !QueryScopeRouter.asksWhetherOneDrugChangesTheOther(marked)) {
					continue;
				}
				String word = String.join(" ", span);
				// A word one of the named drug's own names carries — a combination name — is no drug apart from it: the
				// question may be naming that combination, which the module cannot tell, so the model answers. A word one of
				// her active orders' names carries is her drug, checked through the order: "Is warfarin safe with her
				// cotrimoxazole?" of a patient on "Cotrimoxazole 960mg" was checked, whatever the data calls it.
				// And a word the question names as hers — "her ARVs" — is something she takes, all of which was checked.
				// A group named as hers — "her ARVs" — was checked as her orders; a NAME called hers that none of her read orders
				// carries — "his klarizom" of a patient on no Klarizom — was checked as nothing, and is said so (ADR Decision 308).
				boolean hers = namedAsHers(words, i);
				// "Her allergies" is her allergy list, never a drug's name (ADR Decision 327).
				if (word.contains("allerg")) {
					return null;
				}
				if (!isAName(span) || namedByOneOf(questionDrugs, word) || namesOneOfHerOrders(context, word)
						|| hers && (QueryScopeRouter.namesAMedicationGroup(word) || context == null
								|| !context.activeDrugOrdersRead())) {
					return null;
				}
				return new UnrecognisedDrugName(asWritten(word, question), suggestionsFor(word, entries), inASwitch, hers);
			}
		}
		return null;
	}

	/** Whether the data finds a near name for the word — or, of two, for either (ADR Decision 384): a word it finds none for
	 *  may be no drug at all ("oranges"), and the question takes the chart answer, this statement after it. */
	boolean hasSuggestions() {
		return !suggestions.isEmpty() || !secondSuggestions.isEmpty();
	}

	/** Whether the unrecognised word names one of her active orders — her drug, checked through the order whatever the data
	 *  calls it, so never said to be unrecognised (ADR Decisions 250, 265). */
	boolean namesOneOfHerOrders(PatientClinicalContext context) {
		return namesOneOfHerOrders(context, word) || second != null && namesOneOfHerOrders(context, second);
	}

	/** {@link #statementBeside} — or, for a switch, {@link #statement}: the new drug is checked against her other
	 *  medications, not against the one it replaces, so no pair is what went unchecked (ADR Decision 261). */
	String statementFor(String namedDrug) {
		return inASwitch ? statement() : statementBeside(namedDrug);
	}

	/** Whether the word at {@code i} of {@code words} is named as the patient's own — "her ARVs", never "give her X". */
	private static boolean namedAsHers(List<String> words, int i) {
		if (i == 0) {
			return false;
		}
		String before = words.get(i - 1);
		return POSSESSIVES.contains(before) || "her".equals(before) && !(i > 1 && GIVING.contains(words.get(i - 2)));
	}

	/** Whether one of her active orders' names carries {@code word} as a word of it. */
	private static boolean namesOneOfHerOrders(PatientClinicalContext context, String word) {
		if (context == null) {
			return false;
		}
		for (PatientClinicalContext.ActiveDrugOrder order : context.getActiveDrugOrders()) {
			for (String name : order.getNames()) {
				if (name != null && DrugReference.containsWord(name, word)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Whether one of {@code entries}' names carries {@code word} as a word of it. */
	private static boolean namedByOneOf(List<DrugReference> entries, String word) {
		for (DrugReference entry : entries) {
			for (String alias : entry.getAliases()) {
				if (DrugReference.containsWord(alias, word)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether {@code question} stands a word in a drug's place — see {@link #in} — without reading the data for
	 * suggestions. The screening arm asks it: such a question is about that drug and not a screen of her own orders, so
	 * no chip about two of her orders may stand beside "No interaction check was run" (ADR Decision 183).
	 */
	static boolean namesAWordInADrugsPlace(String question) {
		return question != null && (wordInADrugsPlace(question) != null
				|| twoWordsInDrugsPlaces(QueryScopeRouter.words(question)) != null);
	}

	private static String wordInADrugsPlace(String question) {
		List<String> words = QueryScopeRouter.words(question);
		for (int length = 1; length <= 2; length++) {
			for (int i = 0; i + length <= words.size(); i++) {
				List<String> span = words.subList(i, i + length);
				List<String> marked = new ArrayList<String>(words.subList(0, i));
				marked.add(QueryScopeRouter.DRUG_NAME);
				marked.addAll(words.subList(i + length, words.size()));
				// A question that is the span and nothing else names no drug's place: "Interaction check" fits the shape of a
				// drug's name asked alone, which is a shape only of a name the data recognises (ADR Decision 349).
				if (marked.size() == 1) {
					continue;
				}
				// Nor a word of medication — "her treatment", "her regimen" — which names her medications and no drug (ADR
				// Decision 416): read as a drug's name, "Are there any issues with her treatment?" stood the screen down.
				if (QueryScopeRouter.namesAMedicationGroup(String.join(" ", span))) {
					continue;
				}
				// Or whether to stop it, named as hers (ADR Decision 335) — or whether her drug is safe, or what interacts with
				// it (ADR Decision 374).
				// The shapes alone: the residual reading is of a name the data recognises (ADR Decision 396).
				if (!QueryScopeRouter.fitsAProposalShape(marked)
						&& !QueryScopeRouter.fitsAnInteractsShape(marked)
						&& !QueryScopeRouter.asksWhetherToStopHerDrug(marked)
						&& !(QueryScopeRouter.asksAboutHerNamedDrug(marked)
								&& !QueryScopeRouter.namesAMedicationGroup(String.join(" ", span)))) {
					continue;
				}
				return isAName(span) ? String.join(" ", span) : null;
			}
		}
		return null;
	}

	/** {@code word} as the question spells it — "TDF", not the tokenizer's "tdf"; "co-trimoxazole", not its "co trimoxazole"
	 *  (ADR Decision 245) — where it can be found there. */
	private static String asWritten(String word, String question) {
		Matcher m = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])"
				+ Pattern.quote(word).replace(" ", "\\E[\\s-]+\\Q") + "(?![\\p{L}\\p{N}])").matcher(question);
		return m.find() ? m.group() : word;
	}

	private static boolean isAName(List<String> span) {
		for (String w : span) {
			if (NOT_A_NAME.contains(w) || !w.matches("\\p{L}[\\p{L}\\-]*")) {
				return false;
			}
		}
		// Three letters, for the abbreviations clinicians write: "TDF", "ddI", "ORS".
		return String.join("", span).length() >= 3;
	}

	/** The names of {@code entries} the word begins, else those within an edit of it — one per substance. */
	private static List<String> suggestionsFor(String word, List<DrugReference> entries) {
		String w = DrugReference.foldedLower(word).trim();
		Map<Object, DrugReference> prefixed = new LinkedHashMap<Object, DrugReference>();
		Map<Object, DrugReference> near = new LinkedHashMap<Object, DrugReference>();
		int best = w.length() >= 8 ? 2 : w.length() >= 5 ? 1 : 0;
		for (DrugReference entry : entries) {
			List<String> names = new ArrayList<String>();
			if (entry.getName() != null) {
				names.add(entry.getName());
			}
			if (entry.getAliases() != null) {
				names.addAll(entry.getAliases());
			}
			for (String name : names) {
				String n = DrugReference.foldedLower(name).trim();
				// A name the word begins is suggested only where it names ONE substance: "Hyoscine butylbromide / Paracetamol"
				// is a name of acetaminophen's row, and suggesting Acetaminophen for "hyoscine butylbromide" names the
				// wrong drug.
				if (n.startsWith(w + " ") && n.indexOf('/') < 0 && !n.contains(" and ") && !n.contains(" with ")) {
					keepCanonical(prefixed, entry);
				} else if (best > 0 && n.indexOf(' ') < 0) {
					int distance = editDistance(w, n);
					if (distance > 0 && distance <= best) {
						if (distance < best) {
							best = distance;
							near.clear();
						}
						keepCanonical(near, entry);
					}
				}
			}
		}
		List<String> labels = new ArrayList<String>();
		for (DrugReference row : (prefixed.isEmpty() ? near : prefixed).values()) {
			if (labels.size() < MAX_SUGGESTIONS && !labels.contains(row.displayLabel())) {
				labels.add(row.displayLabel());
			}
		}
		return labels;
	}

	/** One row per substance: the one {@code DrugReference.canonicalRow} elects among those seen. */
	private static void keepCanonical(Map<Object, DrugReference> bySubstance, DrugReference entry) {
		Object key = entry.substanceGroupKey();
		DrugReference seen = bySubstance.get(key);
		bySubstance.put(key, seen == null ? entry : DrugReference.canonicalRow(seen, entry));
	}

	/** Optimal-string-alignment distance: insertions, deletions, substitutions and adjacent transpositions. */
	static int editDistance(String a, String b) {
		if (Math.abs(a.length() - b.length()) > 2) {
			return Integer.MAX_VALUE;
		}
		int[][] d = new int[a.length() + 1][b.length() + 1];
		for (int i = 0; i <= a.length(); i++) {
			d[i][0] = i;
		}
		for (int j = 0; j <= b.length(); j++) {
			d[0][j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
				if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
					d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
				}
			}
		}
		return d[a.length()][b.length()];
	}

	/**
	 * <em>"No interaction check was run: "rifampcin" is not a drug name the interaction data recognises. Did you mean
	 * Rifampicin (rifampin)? Ask again with the name it carries."</em> — or without the suggestion where there is none.
	 */
	String statement() {
		if (second != null) {
			// Two words, neither recognised: the pair was never checked (ADR Decision 382).
			List<String> both = new ArrayList<String>(suggestions);
			for (String suggestion : secondSuggestions) {
				if (!both.contains(suggestion)) {
					both.add(suggestion);
				}
			}
			return withSuggestions(new StringBuilder(eitherNamedAsHers
					? "None of this patient's active orders is named \"" + word + "\" or \"" + second + "\".\n" : "")
					.append("No interaction check was run for \"").append(word).append("\" and \"").append(second)
					.append("\": neither is a drug name the interaction data recognises."), both);
		}
		if (inAStop) {
			return "None of this patient's active orders is named \"" + word + "\", so there is no order of it to stop.";
		}
		return withSuggestions(new StringBuilder(namedAsHersAlone
				? "None of this patient's active orders is named \"" + word + "\".\n" : "")
				.append("No interaction check was run: \"").append(word)
				.append("\" is not a drug name the interaction data recognises."));
	}

	/**
	 * <em>"No interaction check was run for "penicillin" and Methotrexate: "penicillin" is not a drug name the interaction
	 * data recognises. Did you mean …?"</em> — {@link #statement} for a two-drug question whose other drug, {@code
	 * namedDrug}, the data does recognise, so that what was not checked is the pair (ADR Decision 250).
	 */
	String statementBeside(String namedDrug) {
		return withSuggestions(new StringBuilder(namedAsHersButNoOrder
				? "None of this patient's active orders is named \"" + word + "\".\n" : "")
				.append("No interaction check was run for \"").append(word).append("\" and ")
				.append(namedDrug).append(": \"").append(word).append("\" is not a drug name the interaction data recognises."));
	}

	private String withSuggestions(StringBuilder sb) {
		return withSuggestions(sb, suggestions);
	}

	private static String withSuggestions(StringBuilder sb, List<String> suggestions) {
		if (!suggestions.isEmpty()) {
			sb.append(" Did you mean ");
			for (int i = 0; i < suggestions.size(); i++) {
				if (i > 0) {
					sb.append(i == suggestions.size() - 1 ? " or " : ", ");
				}
				sb.append(suggestions.get(i));
			}
			sb.append("? Ask again with the name it carries.");
		}
		return sb.toString();
	}
}
