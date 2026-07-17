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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link QueryPreprocessor#expandLabPanelAbbreviations}: clinicians ask for
 * labs by abbreviation ("the last BMP") but querystore indexes the panel's <em>full concept
 * name</em> ("Basic metabolic panel"), so the abbreviation embeds far from the records and the
 * similarity slice misses them (measured: "results of the last BMP" answered "no records" while
 * the panel existed). Expansion appends the full name next to the abbreviation so the retrieval
 * text carries both surface forms.
 */
public class QueryPreprocessorLabExpansionTest {

	@Test
	public void expand_shouldAppendFullPanelNameAfterAbbreviation() {
		String expanded = QueryPreprocessor.expandLabPanelAbbreviations("Give me the results of the last BMP.");
		assertTrue(expanded.toLowerCase().contains("basic metabolic panel"),
				"BMP must expand to its full panel name: " + expanded);
		assertTrue(expanded.contains("BMP"), "the original abbreviation must be preserved: " + expanded);
	}

	@Test
	public void expand_shouldBeCaseInsensitiveAndCoverCommonPanels() {
		assertTrue(QueryPreprocessor.expandLabPanelAbbreviations("last cbc?").toLowerCase()
				.contains("complete blood count"));
		assertTrue(QueryPreprocessor.expandLabPanelAbbreviations("recent CMP results").toLowerCase()
				.contains("comprehensive metabolic panel"));
		assertTrue(QueryPreprocessor.expandLabPanelAbbreviations("any LFTs on file?").toLowerCase()
				.contains("liver function"));
	}

	@Test
	public void expand_shouldNotTouchAbbreviationsInsideWords() {
		assertEquals("the subcmp module", QueryPreprocessor.expandLabPanelAbbreviations("the subcmp module"),
				"word-boundary only — substrings inside other words must not expand");
	}

	@Test
	public void expand_shouldPassThroughNullBlankAndCueFreeQuestions() {
		assertEquals(null, QueryPreprocessor.expandLabPanelAbbreviations(null));
		assertEquals("   ", QueryPreprocessor.expandLabPanelAbbreviations("   "));
		assertEquals("Does she have any allergies?",
				QueryPreprocessor.expandLabPanelAbbreviations("Does she have any allergies?"));
	}
}
