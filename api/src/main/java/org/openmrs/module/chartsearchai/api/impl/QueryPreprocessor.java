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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Normalizes a raw user question into the inputs the rest of the module derives from it.
 *
 * <p>{@link #forRetrieval} is the entry point chart assembly should use: it composes abbreviation
 * expansion — lab panels and condition initialisms alike — with stopword stripping, in that order,
 * and ALL THREE build paths call it (the fullChart focus-hint pass, the query-scoped slice pass,
 * and the progressive-reasoning preview). The individual steps
 * ({@link #stripQueryStopwords}, {@link #expandLabPanelAbbreviations},
 * {@link #expandClinicalAbbreviations}) remain visible for their own tests, but a call site that
 * chains them by hand has historically drifted — one path expanded and the others did not — which
 * is why {@code ArchitectureGuardTest.noHandChainedRetrievalPreprocessing} forbids it.
 *
 * <p>Also derives {@link #contentWords} (the stopword vocabulary without stripping's
 * fall-back-to-the-whole-sentence behaviour, used by {@code QueryScopeRouter} to tell a
 * problem-list question from a domain-qualified one), content terms for keyword matching, and a
 * recency cap.
 */
final class QueryPreprocessor {

	private QueryPreprocessor() {
	}

	private static final Logger log = LoggerFactory.getLogger(QueryPreprocessor.class);

	private static final String NUMBER_GROUP =
			"(\\d+|one|two|three|four|five|six|seven|eight|nine|ten)";

	private static final String KEYWORD_GROUP =
			"(?:last|latest|past|previous|recent|most recent)";

	private static final Pattern RECENCY_PATTERN = Pattern.compile(
			KEYWORD_GROUP + "\\s+" + NUMBER_GROUP
			+ "|" + NUMBER_GROUP + "\\s+" + KEYWORD_GROUP,
			Pattern.CASE_INSENSITIVE);

	/** Matches a definite-article recency phrase without a number, e.g.
	 *  "the latest weight" or "the most recent BP". The definite article
	 *  signals that the user expects a single (the most recent) result,
	 *  unlike bare "latest vital signs" which is a synonym for "recent".
	 *  Implies a cap of 1. */
	private static final Pattern BARE_RECENCY_PATTERN = Pattern.compile(
			"\\bthe\\s+(?:latest|most recent)\\b", Pattern.CASE_INSENSITIVE);

	private static final Map<String, Integer> WORD_NUMBERS;

	static {
		Map<String, Integer> m = new HashMap<String, Integer>();
		m.put("one", 1);
		m.put("two", 2);
		m.put("three", 3);
		m.put("four", 4);
		m.put("five", 5);
		m.put("six", 6);
		m.put("seven", 7);
		m.put("eight", 8);
		m.put("nine", 9);
		m.put("ten", 10);
		WORD_NUMBERS = Collections.unmodifiableMap(m);
	}

	private static final Set<String> QUERY_STOPWORDS = loadStopwords("query-stopwords.txt");

	private static Set<String> loadStopwords(String fileName) {
		// Try the OpenMRS application data directory first so admins can customize
		// without recompiling. Fall back to the bundled resource.
		InputStream is = null;
		boolean fromFile = false;
		try {
			File appDataFile = new File(
					org.openmrs.util.OpenmrsUtil.getApplicationDataDirectory(),
					"chartsearchai" + File.separator + fileName);
			if (appDataFile.exists()) {
				is = new FileInputStream(appDataFile);
				fromFile = true;
				// WARN, not INFO: this file has TWO consumers now. Besides shaping the retrieval
				// query it drives QueryScopeRouter's domain-qualification check, so an override
				// that omits ordinary function words ("patient", "does", "have") silently costs
				// every problem-list question its completeness guarantee. The module package is not
				// covered by the stock log.level=info, so at INFO the override is invisible.
				log.warn("Loading stopwords from {} instead of the bundled list. This file also "
						+ "drives query-scope routing: an override that drops function words will "
						+ "stop problem-list questions from being enumerated completely.",
						appDataFile.getAbsolutePath());
			}
		}
		catch (Exception e) {
			log.debug("Could not load stopwords from application data directory: {}", e.getMessage());
		}

		if (is == null) {
			is = QueryPreprocessor.class.getClassLoader().getResourceAsStream(fileName);
			if (is == null) {
				log.warn("Stopwords resource not found: {}, query normalization will be disabled", fileName);
				return Collections.emptySet();
			}
		}

		Set<String> words = new HashSet<String>();
		try (InputStream stream = is) {
			BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					words.add(trimmed.toLowerCase());
				}
			}
		}
		catch (IOException e) {
			log.warn("Failed to load stopwords from {}: {}", fileName, e.getMessage());
		}

		if (fromFile) {
			// INFO, not WARN: the override itself is already warned about above, and two WARNs for
			// one event is how a log trains its reader to skip them.
			log.info("Loaded {} stopwords from the application data directory", words.size());
		}
		return Collections.unmodifiableSet(words);
	}

	/**
	 * Extracts a numeric recency constraint from the question, e.g. "last 7
	 * visits" or "latest two weights" returns the number. Supports both
	 * digits and word numbers (one through ten). A bare recency keyword
	 * without a number (e.g. "latest weight", "most recent BP") implies 1.
	 * Returns 0 if no constraint is found.
	 *
	 * @param question the raw user question
	 * @return the recency cap, or 0 if none detected
	 */
	static int extractRecencyCap(String question) {
		Matcher m = RECENCY_PATTERN.matcher(question);
		if (m.find()) {
			// Group 1 = keyword-first ("last 7"), group 2 = number-first ("7 most recent")
			String value = (m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase();
			Integer wordNum = WORD_NUMBERS.get(value);
			if (wordNum != null) {
				return wordNum;
			}
			try {
				int n = Integer.parseInt(value);
				return n > 0 ? n : 0;
			}
			catch (NumberFormatException e) {
				return 0;
			}
		}
		// Bare recency keyword without a number implies cap of 1.
		if (BARE_RECENCY_PATTERN.matcher(question).find()) {
			return 1;
		}
		return 0;
	}

	/**
	 * Common lab-panel abbreviations mapped to the full concept names querystore indexes.
	 * Clinicians ask by abbreviation ("the last BMP") but the indexed records carry the full
	 * panel name ("Basic metabolic panel"), so the abbreviation alone embeds far from the
	 * records and the similarity slice misses them (measured on the rc.2 standalone: "results
	 * of the last BMP" answered "no records" while the panel existed). Word-boundary,
	 * case-insensitive; deliberately curated — only unambiguous panel abbreviations belong here.
	 */
	private static final Map<Pattern, String> LAB_PANEL_ABBREVIATIONS;
	static {
		Map<Pattern, String> m = new LinkedHashMap<Pattern, String>();
		m.put(cue("BMP"), "basic metabolic panel");
		m.put(cue("CMP"), "comprehensive metabolic panel");
		m.put(cue("CBC"), "complete blood count");
		m.put(cue("LFTs?"), "liver function tests");
		m.put(cue("RFTs?"), "renal function tests");
		m.put(cue("ABG"), "arterial blood gas");
		m.put(cue("ESR"), "erythrocyte sedimentation rate");
		m.put(cue("CRP"), "C-reactive protein");
		LAB_PANEL_ABBREVIATIONS = Collections.unmodifiableMap(m);
	}

	/**
	 * Appends the full panel name after each recognized lab abbreviation ("last BMP" →
	 * "last BMP basic metabolic panel"), keeping the original token so both surface forms reach
	 * the retrieval embedding. Pass-through for null/blank/cue-free questions. Callers should
	 * prefer {@link #expandClinicalAbbreviations}, which also covers the condition vocabulary.
	 */
	static String expandLabPanelAbbreviations(String question) {
		return expand(question, LAB_PANEL_ABBREVIATIONS);
	}

	/**
	 * Condition/finding initialisms mapped to the full clinical terms querystore indexes.
	 * Same failure mode as {@link #LAB_PANEL_ABBREVIATIONS}, one level up from the lab bench:
	 * a clinician asks "any HTN?" or "does she have CKD" while the record reads "Hypertension" /
	 * "Chronic kidney disease, stage IIIA (moderate)", and the bare initialism embeds far from
	 * the record — so the similarity slice can miss a condition the patient demonstrably has.
	 *
	 * <p>Curated and deliberately conservative: only unambiguous, in-common-use clinical
	 * initialisms. Expansion is <em>additive</em> — the clinician's own token is always kept —
	 * so the worst case for a wrong entry is a couple of extra words in the retrieval text,
	 * never a lost query term.
	 *
	 * <p>Case sensitivity is declared PER ENTRY, not by a length rule: an initialism whose
	 * lowercase spelling is also an ordinary English word or unit — {@code mi} (mile),
	 * {@code hr} (hour), {@code sob}, {@code tb} (terabyte), {@code cad}, {@code tia} (a name) —
	 * is matched only in capitals, where it is unambiguously the diagnosis. The rest are matched
	 * case-insensitively because clinicians type them either way ("any htn?").
	 */
	private static final Map<Pattern, String> CLINICAL_ABBREVIATIONS;
	static {
		Map<Pattern, String> m = new LinkedHashMap<Pattern, String>();
		m.put(cue("HTN"), "hypertension");
		m.put(cue("T2DM"), "type 2 diabetes mellitus");
		m.put(cue("T1DM"), "type 1 diabetes mellitus");
		m.put(cue("CKD"), "chronic kidney disease");
		m.put(cue("ESRD"), "end stage renal disease");
		m.put(cue("AKIs?"), "acute kidney injury");
		m.put(cue("UTIs?"), "urinary tract infection");
		m.put(cue("COPD"), "chronic obstructive pulmonary disease");
		m.put(cue("CHF"), "congestive heart failure");
		m.put(cue("CVAs?"), "cerebrovascular accident stroke");
		m.put(cue("DVTs?"), "deep vein thrombosis");
		m.put(cue("GERD"), "gastroesophageal reflux disease");
		m.put(cue("BMI"), "body mass index");
		m.put(cue("URTIs?"), "upper respiratory tract infection");
		m.put(cue("STIs?"), "sexually transmitted infection");
		m.put(cue("STDs?"), "sexually transmitted disease");
		m.put(cue("ARV"), "antiretroviral");
		m.put(cue("BP"), "blood pressure");
		// Capitals only — the lowercase spelling is an ordinary word or unit.
		m.put(capitalsOnlyCue("DM"), "diabetes mellitus");
		m.put(capitalsOnlyCue("CADs?"), "coronary artery disease");
		m.put(capitalsOnlyCue("TIAs?"), "transient ischemic attack");
		m.put(capitalsOnlyCue("MIs?"), "myocardial infarction");
		m.put(capitalsOnlyCue("PEs?"), "pulmonary embolism");
		m.put(capitalsOnlyCue("TB"), "tuberculosis");
		m.put(capitalsOnlyCue("AF"), "atrial fibrillation");
		m.put(capitalsOnlyCue("SOB"), "shortness of breath");
		m.put(capitalsOnlyCue("PIDs?"), "pelvic inflammatory disease");
		m.put(capitalsOnlyCue("HR"), "heart rate");
		m.put(capitalsOnlyCue("RR"), "respiratory rate");
		CLINICAL_ABBREVIATIONS = Collections.unmodifiableMap(m);
	}

	/** Word-boundary, case-insensitive pattern for an initialism that cannot be confused with an
	 *  ordinary word. */
	private static Pattern cue(String abbreviation) {
		return Pattern.compile("\\b" + abbreviation + "\\b", Pattern.CASE_INSENSITIVE);
	}

	/** Word-boundary pattern that matches only the capitalised spelling — for initialisms whose
	 *  lowercase form is an ordinary word or unit (see {@link #CLINICAL_ABBREVIATIONS}). */
	private static Pattern capitalsOnlyCue(String abbreviation) {
		return Pattern.compile("\\b" + abbreviation + "\\b");
	}

	/**
	 * The retrieval-text normalizer both chart-build paths call: appends the full clinical term
	 * after every recognized abbreviation — lab panels ({@link #LAB_PANEL_ABBREVIATIONS}) and
	 * condition initialisms ({@link #CLINICAL_ABBREVIATIONS}) alike — keeping the clinician's own
	 * wording so both surface forms reach the embedding. Pass-through for null/blank/cue-free
	 * questions.
	 *
	 * <p>One composed entry point on purpose: a caller that remembered only the lab expansion
	 * would silently get half the vocabulary, which is exactly how the fullChart focus-hint path
	 * came to expand nothing while the scoped path expanded panels.
	 */
	static String expandClinicalAbbreviations(String question) {
		return expand(expandLabPanelAbbreviations(question), CLINICAL_ABBREVIATIONS);
	}

	/**
	 * The composed retrieval-text pipeline: abbreviation expansion followed by stopword
	 * stripping, in that order (expansion must see the clinician's raw casing and punctuation,
	 * which stripping removes). This is the ONLY entry point chart assembly should use to turn a
	 * question into querystore search text — the three build paths previously chained the steps
	 * themselves and had already drifted apart (the fullChart focus-hint path expanded nothing
	 * while the scoped path expanded lab panels).
	 *
	 * <p>Null/blank-safe (returned unchanged), unlike the raw {@link #stripQueryStopwords} step it
	 * wraps: every call site guards for a blank question today, but a composed entry point that
	 * NPEs on one is a trap for the next caller.
	 */
	static String forRetrieval(String question) {
		if (question == null || question.trim().isEmpty()) {
			return question;
		}
		return stripQueryStopwords(expandClinicalAbbreviations(question));
	}

	/** Appends each map value after every word-boundary match of its key, leaving the match in
	 *  place. Shared by the two vocabularies so their expansion semantics cannot drift. */
	private static String expand(String question, Map<Pattern, String> vocabulary) {
		if (question == null || question.trim().isEmpty()) {
			return question;
		}
		String expanded = question;
		for (Map.Entry<Pattern, String> entry : vocabulary.entrySet()) {
			// "$0" keeps the matched abbreviation; the expansion is quoted so a value ever
			// containing '$' or '\' is inserted literally instead of being parsed as a group
			// reference / escape (which would throw or mangle the retrieval text).
			expanded = entry.getKey().matcher(expanded)
					.replaceAll("$0 " + Matcher.quoteReplacement(entry.getValue()));
		}
		return expanded;
	}

	/**
	 * Removes common stopwords before embedding so that queries like
	 * "any medications?" and "does the patient have any medications?"
	 * produce the same embedding vector and thus the same retrieval results.
	 *
	 * @param question the raw user question
	 */
	static String stripQueryStopwords(String question) {
		String[] words = cleanTokens(question);
		List<String> contentWords = new ArrayList<String>();
		List<String> allClean = new ArrayList<String>();
		for (String word : words) {
			if (!word.isEmpty()) {
				allClean.add(word);
				if (!QUERY_STOPWORDS.contains(word)) {
					contentWords.add(word);
				}
			}
		}
		if (contentWords.size() >= 2) {
			StringBuilder sb = new StringBuilder();
			for (String w : contentWords) {
				if (sb.length() > 0) {
					sb.append(" ");
				}
				sb.append(w);
			}
			return sb.toString();
		}
		// Too few content words — preserve all cleaned words so the
		// embedding model gets enough context. The full sentence
		// "does the patient have cancer" produces a more specific
		// embedding than the single word "cancer", helping the model
		// differentiate cancer-related records from unrelated ones.
		if (!allClean.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (String w : allClean) {
				if (sb.length() > 0) {
					sb.append(" ");
				}
				sb.append(w);
			}
			return sb.toString();
		}
		return question.toLowerCase().trim();
	}

	/**
	 * The question's content words — lowercased, punctuation-stripped, stopwords removed — with
	 * NO fallback. {@link #stripQueryStopwords} deliberately returns the whole cleaned sentence
	 * when fewer than two content words survive (a one-word embedding is too vague to retrieve
	 * on); a caller reasoning about which words the clinician actually supplied must not see
	 * those function words reappear. Used by {@code QueryScopeRouter} to tell a problem-list
	 * question ("what conditions does the patient have?") from a domain-qualified one ("any
	 * psychiatric conditions?"), so routing and retrieval share one stopword vocabulary instead
	 * of growing a second.
	 *
	 * @return the content words in order; empty for a null/blank question or one that is all
	 *         stopwords
	 */
	static List<String> contentWords(String question) {
		List<String> words = new ArrayList<String>();
		if (question == null || question.trim().isEmpty()) {
			return words;
		}
		for (String word : cleanTokens(question)) {
			// Trim the punctuation the retrieval tokenizer deliberately leaves in place. Retrieval
			// hands its text to an embedder that is indifferent to a stray bracket, so widening the
			// shared tokenizer would change retrieval bytes — and with them the measured
			// answer-quality baseline — for no retrieval benefit. A CALLER reasoning about which
			// words the clinician supplied cannot be indifferent: "(active)" read as a distinct word
			// is read as a clinical domain, which silently costs a problem-list question its
			// completeness guarantee. Verified before this trim: "conditions (active)?" and
			// "Any conditions -- active?" both routed away from the problem list.
			//
			// Only EDGE runs are trimmed. A token with interior punctuation ("2024-01",
			// "conditions/diagnoses") still reaches the caller intact, which is why
			// QueryScopeRouter compares against a letters-and-digits-only form of the token as
			// well as the token itself.
			String trimmed = ROUTING_PUNCTUATION.matcher(word).replaceAll("");
			if (!trimmed.isEmpty() && !QUERY_STOPWORDS.contains(trimmed)) {
				words.add(trimmed);
			}
		}
		return words;
	}

	/** Leading/trailing punctuation the shared tokenizer keeps but a word-level consumer must not
	 *  see — brackets, quotes and dashes. Stripped only in {@link #contentWords}; see the note
	 *  there for why the retrieval tokenizer is left alone.
	 *
	 *  <p>{@code \p{Punct}} is ASCII-only, so the Unicode punctuation category is unioned with it:
	 *  macOS, iOS and Word substitute em dashes and curly quotes for the ASCII spellings by
	 *  default, which made the typed-by-hand forms the only ones this trimmed. */
	private static final Pattern ROUTING_PUNCTUATION = Pattern.compile(
			"^[\\p{Punct}\\p{IsPunctuation}]+|[\\p{Punct}\\p{IsPunctuation}]+$");

	/** Lowercases, drops possessives and sentence punctuation, and splits on whitespace — the
	 *  tokenizer {@link #stripQueryStopwords} and {@link #contentWords} share, so the two cannot
	 *  disagree about word BOUNDARIES or casing. They deliberately differ afterwards: stripping
	 *  keeps the token as the embedder will see it, while {@link #contentWords} additionally trims
	 *  edge punctuation and drops empties, because a word-level consumer must not mistake
	 *  "(active)" for a clinical domain. */
	private static String[] cleanTokens(String question) {
		return question.toLowerCase().replaceAll("'s\\b", "")
				.replaceAll("[?!.,;:']", "").trim().split("\\s+");
	}

	/**
	 * Extracts content terms from the normalized query for keyword matching.
	 * Returns lowercased terms with length >= 2 (single-letter terms are too
	 * ambiguous to be useful for keyword overlap scoring).
	 */
	static String[] extractQueryTerms(String normalizedQuery) {
		String[] allTerms = normalizedQuery.toLowerCase().split("\\s+");
		List<String> terms = new ArrayList<String>();
		for (String term : allTerms) {
			if (term.length() >= 2 && !QUERY_STOPWORDS.contains(term)) {
				terms.add(term);
			}
		}
		return terms.toArray(new String[0]);
	}

}
