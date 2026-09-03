/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.LogCapture;

/**
 * Exercises the real {@link JsonDrugReferenceSource#load()} path. With no OpenMRS
 * context available it falls back to the bundled {@code /chartsearchai/drug-reference.json}
 * — the production default — so this runs the real load path against the real dataset.
 *
 * <p>One case does not, and says so in its own javadoc: the issue #242 case feeds the real
 * {@link JsonDrugReferenceSource#parse} a fixture of ANOTHER format, because what it is about is the
 * document this parser cannot read rather than the dataset it can.
 */
public class JsonDrugReferenceSourceTest {

	@Test
	public void loadsBundledDatasetViaClasspathFallback() {
		List<DrugReference> all = new JsonDrugReferenceSource().load();
		assertFalse(all.isEmpty(), "bundled dataset should load via the classpath fallback");
		assertTrue(all.stream().anyMatch(r -> "ibuprofen".equals(r.getId())),
				"dataset should contain the ibuprofen entry");
	}

	/**
	 * Issue #242 from the curated side, which is the likelier of the two directions: this is the DEFAULT
	 * format, so the document this parser is most often handed by mistake is one of another format. A
	 * DDInter export declares no {@code entries} and used to read as zero in the same silence.
	 *
	 * <p>Through {@link DrugReferenceTestSupport#fixtureEntries}, the helper every curated fixture test
	 * takes, so what is asserted is the path a test would actually travel — and it reaches the
	 * one-argument {@code parse} form, which has no load status to report a finding into and is therefore
	 * where a report could have been dropped for want of a channel.
	 */
	@Test
	public void aDocumentOfAnotherFormatIsLoudRatherThanReadingAsZeroQuietly() throws Exception {
		List<DrugReference> parsed;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			parsed = DrugReferenceTestSupport.fixtureEntries(
					DrugReferenceTestSupport.DDI_EMPTY_INTERACTIONS_TABLE);
			assertTrue(
					capture.messagesAt(Level.WARN).toString()
							.contains(DrugReferenceValidity.DATASET_MISSING_A_REQUIRED_TABLE),
					"the WARN must name the rule, and the table this parser needed. Captured: "
							+ capture.describeAll());
			// The BRACKETED list, not the bare word: "entries" also occurs in every finding's shared
			// boilerplate ("parsed to no entries at all"), so the bare form passes even when the parser
			// names the wrong table — which is the defect this line claims to exclude.
			assertTrue(capture.messagesAt(Level.WARN).toString().contains("[entries]"),
					"named for what THIS parser requires, not for the format it was handed. Captured: "
							+ capture.describeAll());
		}
		assertTrue(parsed.isEmpty(), "and it still reads nothing from a document of another format");
	}

	@Test
	public void curatedEntriesCarrySafetyRules() {
		// Unlike the ATC classification source, the curated JSON carries the actual
		// contraindication/dosing rules the validator fires on.
		DrugReference ibuprofen = new JsonDrugReferenceSource().load().stream()
				.filter(r -> "ibuprofen".equals(r.getId())).findFirst().orElse(null);
		assertTrue(ibuprofen != null && !ibuprofen.getContraindications().isEmpty(),
				"the curated ibuprofen entry should carry contraindication rules");
	}
}
