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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A citation marker the model put on one item of its answer while the record it cites is what ANOTHER item names,
 * under that item's own marker too, is removed from the item it does not belong to — ADR Decision 186, the one place
 * this module rewrites the model's prose other than {@code LlmAnswerExtractor.normalizeSlashCitations}.
 *
 * <p>Measured on the 3.7.1 standalone: <em>"... Personal history of allergy to shellfish [229] [232], and Allergy to
 * sufentanil [231] [232]."</em>, where [232] is the shellfish diagnosis.
 *
 * <p><b>The unit is a marker RUN</b> — markers separated by whitespace alone — and the words before it back to the
 * previous run or the start of its sentence ({@link ChartSearchAiUtils#SENTENCE_BOUNDARY}). A marker [N] is removed
 * from run X only where all of these hold, and the conjunction is what keeps it narrow:
 * <ul>
 * <li>[N] is also in another run Y;</li>
 * <li>X carries another marker, whose record X's words name by a word Y's do not — the item is about that record;</li>
 * <li>no word X shares with record N's text is outside Y's words, and some word Y shares with it is outside X's.</li>
 * </ul>
 * Words are the module's query terms ({@link QueryPreprocessor#extractQueryTerms}), so no medical vocabulary is
 * involved. A marker is never removed from every run it is in, by construction and not by a guard: the third condition
 * makes the words Y shares with the record a strict superset of the words X shares with it, so the run sharing the
 * most is never removed. Nor is a run ever left without a marker: where each of its markers is named better by a
 * different other run, it keeps them all.
 */
final class MisplacedCitationMarkers {

	private static final Logger log = LoggerFactory.getLogger(MisplacedCitationMarkers.class);

	private MisplacedCitationMarkers() {
	}

	/** One run of markers, the text it follows, and which of its markers stay. */
	private static final class Run {

		final int start;

		final int end;

		final String separator;

		final List<Integer> indexes = new ArrayList<Integer>();

		final Set<String> words;

		final Set<Integer> removed = new HashSet<Integer>();

		Run(int start, int end, String separator, Set<String> words) {
			this.start = start;
			this.end = end;
			this.separator = separator;
			this.words = words;
		}
	}

	/**
	 * @return {@code answer} with each marker this class's rule finds misplaced removed; {@code answer} itself where none
	 *         is, and where the rule fails — it must never cost the clinician the answer
	 */
	static String removed(String answer, List<RecordMapping> mappings) {
		if (answer == null || mappings == null || mappings.isEmpty()) {
			return answer;
		}
		try {
			return rewrittenWhereMisplaced(answer, mappings);
		}
		catch (RuntimeException e) {
			log.warn("Misplaced-citation check failed; the answer is published as the model wrote it: {}",
				e.getClass().getSimpleName());
			return answer;
		}
	}

	private static String rewrittenWhereMisplaced(String answer, List<RecordMapping> mappings) {
		Map<Integer, RecordMapping> byIndex = new HashMap<Integer, RecordMapping>();
		for (RecordMapping mapping : mappings) {
			byIndex.put(mapping.getIndex(), mapping);
		}
		// Read lazily: only a marker in two runs, and the other markers of its runs, need a record's text.
		RecordWords recordWords = new RecordWords(byIndex);
		List<Run> runs = runs(answer);
		Map<Integer, List<Run>> runsOf = new LinkedHashMap<Integer, List<Run>>();
		for (Run run : runs) {
			for (Integer index : new LinkedHashSet<Integer>(run.indexes)) {
				List<Run> of = runsOf.get(index);
				if (of == null) {
					of = new ArrayList<Run>();
					runsOf.put(index, of);
				}
				of.add(run);
			}
		}
		boolean any = false;
		for (Map.Entry<Integer, List<Run>> marker : runsOf.entrySet()) {
			if (marker.getValue().size() < 2) {
				continue;
			}
			Set<String> record = recordWords.get(marker.getKey());
			if (record == null) {
				continue;
			}
			for (Run x : marker.getValue()) {
				for (Run y : marker.getValue()) {
					if (x != y && belongsTo(y, x, record) && isAboutAnotherRecordItCites(x, y, marker.getKey(), recordWords)) {
						x.removed.add(marker.getKey());
						any = true;
						break;
					}
				}
			}
		}
		// Each marker of one run can be named better by a different other run; the item keeps them all rather than none.
		for (Run run : runs) {
			if (run.removed.containsAll(run.indexes)) {
				run.removed.clear();
			}
		}
		return any ? rewritten(answer, runs) : answer;
	}

	/** Y's words name the record by a word X's do not, and X's name it by none Y's do not. */
	private static boolean belongsTo(Run y, Run x, Set<String> record) {
		return sharedOutside(x.words, record, y.words).isEmpty() && !sharedOutside(y.words, record, x.words).isEmpty();
	}

	/** X names, by a word Y's do not, the record of another marker of its own run. */
	private static boolean isAboutAnotherRecordItCites(Run x, Run y, Integer misplaced, RecordWords recordWords) {
		for (Integer other : x.indexes) {
			Set<String> record = recordWords.get(other);
			if (!other.equals(misplaced) && record != null && !sharedOutside(x.words, record, y.words).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** Each cited record's words, read the first time they are asked for. */
	private static final class RecordWords {

		private final Map<Integer, RecordMapping> byIndex;

		private final Map<Integer, Set<String>> read = new HashMap<Integer, Set<String>>();

		RecordWords(Map<Integer, RecordMapping> byIndex) {
			this.byIndex = byIndex;
		}

		/** @return the record's words, or {@code null} where no record has that index */
		Set<String> get(Integer index) {
			RecordMapping mapping = byIndex.get(index);
			if (mapping == null) {
				return null;
			}
			Set<String> words = read.get(index);
			if (words == null) {
				words = words(mapping.getText());
				read.put(index, words);
			}
			return words;
		}
	}

	private static Set<String> sharedOutside(Set<String> words, Set<String> record, Set<String> outside) {
		Set<String> shared = new HashSet<String>(words);
		shared.retainAll(record);
		shared.removeAll(outside);
		return shared;
	}

	private static List<Run> runs(String answer) {
		List<Run> runs = new ArrayList<Run>();
		Matcher marker = ChartSearchAiUtils.INLINE_CITATION.matcher(answer);
		int previousEnd = 0;
		List<Integer> openIndexes = new ArrayList<Integer>();
		int openStart = -1;
		int openEnd = -1;
		String openSeparator = " ";
		while (marker.find()) {
			if (openStart >= 0 && answer.substring(openEnd, marker.start()).trim().isEmpty()) {
				if (openIndexes.size() == 1) {
					openSeparator = answer.substring(openEnd, marker.start());
				}
				openIndexes.add(Integer.valueOf(marker.group(1)));
				openEnd = marker.end();
				continue;
			}
			if (openStart >= 0) {
				runs.add(run(answer, previousEnd, openStart, openEnd, openSeparator, openIndexes));
				previousEnd = openEnd;
			}
			openStart = marker.start();
			openEnd = marker.end();
			openSeparator = " ";
			openIndexes = new ArrayList<Integer>();
			openIndexes.add(Integer.valueOf(marker.group(1)));
		}
		if (openStart >= 0) {
			runs.add(run(answer, previousEnd, openStart, openEnd, openSeparator, openIndexes));
		}
		return runs;
	}

	private static Run run(String answer, int previousEnd, int start, int end, String separator, List<Integer> indexes) {
		String before = answer.substring(previousEnd, start);
		int itemStart = 0;
		Matcher boundary = ChartSearchAiUtils.SENTENCE_BOUNDARY.matcher(before);
		while (boundary.find()) {
			itemStart = boundary.end();
		}
		Run run = new Run(start, end, separator, words(before.substring(itemStart)));
		run.indexes.addAll(indexes);
		return run;
	}

	private static String rewritten(String answer, List<Run> runs) {
		StringBuilder out = new StringBuilder();
		int copied = 0;
		for (Run run : runs) {
			if (run.removed.isEmpty()) {
				continue;
			}
			out.append(answer, copied, run.start);
			String separator = "";
			for (Integer index : run.indexes) {
				if (!run.removed.contains(index)) {
					out.append(separator).append('[').append(index).append(']');
					separator = run.separator;
				}
			}
			copied = run.end;
		}
		return out.append(answer.substring(copied)).toString();
	}

	private static Set<String> words(String text) {
		Set<String> words = new HashSet<String>();
		if (text == null) {
			return words;
		}
		for (String term : QueryPreprocessor.extractQueryTerms(text.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").trim())) {
			if (!term.isEmpty()) {
				words.add(term);
			}
		}
		return words;
	}
}
