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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one sentence a client shows of a list the model wrote, the whole answer collapsed under it — ADR Decision 528:
 * the list's own heading and how many items it lists, <em>"The patient is on the following medications — 9
 * listed."</em> Every word in view is the model's own heading or a count of what the answer lists, so nothing in view
 * can say what the answer does not. Asking the model for a one-sentence summary instead was measured and declined: over
 * 24 prompts captured on the demo replica and replayed on its model, at least 5 summaries contradicted their own answer
 * — a different blood pressure, a different visit date. An answer that is not such a list has none, and is shown whole.
 */
final class ModelAnswerList {

	/** A list's heading: the first line's words up to its first colon. */
	private static final Pattern HEADING = Pattern.compile("^([^:\\n]{3,200}):\\s*(.*)$");

	/** An item on a line of its own: numbered, bulleted, or a label and its value — "Pulse: 80 beats/min [137]" — but
	 *  never the module's own "Not checked:" line, which follows an answer rather than belonging to its list. */
	private static final Pattern ITEM_LINE = Pattern.compile(
		"^\\s*(?:(?:\\d{1,3}[.)]|[-*•])\\s+\\S.*|(?!Not checked:)[^:\\s][^:]{0,79}:\\s+\\S.*)");

	/** An item numbered on the heading's own line: "1. ", "2. ", … in order. */
	private static final Pattern INLINE_NUMBER = Pattern.compile("(?:^|\\s)(\\d{1,3})\\.\\s+\\S");

	private ModelAnswerList() {
	}

	/**
	 * @return the heading and the count, or {@code null} where {@code answer} is no list of two or more items under a
	 *         heading
	 */
	static String summaryOf(String answer) {
		if (answer == null) {
			return null;
		}
		String[] lines = answer.trim().split("\\n");
		Matcher heading = HEADING.matcher(lines[0].trim());
		if (!heading.matches()) {
			return null;
		}
		String rest = heading.group(2).trim();
		int items = 0;
		if (rest.isEmpty()) {
			for (int n = 1; n < lines.length && ITEM_LINE.matcher(lines[n]).matches(); n++) {
				items++;
			}
		} else {
			items = inlineNumberedItems(rest);
			if (items == 0 && !rest.matches("(?s).*[.!?]\\s+\\S.*")
					&& heading.group(1).trim().split("\\s+").length >= 4) {
				// One sentence listing its items, comma-separated, under a heading that is a sentence and not a label —
				// "Blood pressure: 120/80 mmHg, pulse 80, …" is a value — and three or more, so a clause is not a list.
				int commas = rest.split(",").length;
				items = commas >= 3 ? commas : 0;
			}
		}
		return items >= 2 ? heading.group(1).trim() + " — " + items + " listed." : null;
	}

	/** How many items are numbered 1, 2, 3, … in order on the heading's line, or 0 where they are not. */
	private static int inlineNumberedItems(String rest) {
		Matcher number = INLINE_NUMBER.matcher(rest);
		int expected = 1;
		while (number.find()) {
			if (Integer.parseInt(number.group(1)) != expected) {
				return expected > 2 ? expected - 1 : 0;
			}
			expected++;
		}
		return expected > 2 ? expected - 1 : 0;
	}
}
