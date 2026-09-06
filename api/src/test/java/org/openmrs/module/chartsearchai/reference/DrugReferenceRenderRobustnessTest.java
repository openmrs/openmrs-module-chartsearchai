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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * The injector renders operator-authored data straight into the citable chart line, and
 * {@code drug-reference.json} is an operator-editable file — so a null/blank warning, a rule
 * stub with neither note nor token, or an interaction with neither token nor ATC must degrade
 * to "skip that element", never to an exception (which would fail the whole query) and never
 * to a literal {@code "null"} in the record the LLM cites. Runs the real parser + the real
 * injector over a deliberately mangled fixture.
 */
public class DrugReferenceRenderRobustnessTest {

	private static final String FIXTURE = "chartsearchai-test/drug-reference-malformed.json";

	/** Separate fixture: a valid entry cannot be added to {@link #FIXTURE} without changing the
	 *  entry count {@link #unusableEntriesAreDroppedAtParse} pins. */
	private static final String BLANK_SOURCE_FIXTURE = "chartsearchai-test/drug-reference-blank-source.json";

	private List<DrugReference> fixtureEntries() throws IOException {
		return DrugReferenceTestSupport.fixtureEntries(FIXTURE);
	}

	private PatientChart oneRecordChart() {
		return DrugReferenceTestSupport.oneRecordChart();
	}

	/** The injected drug-reference mapping for {@code question}, via the real injector. */
	private RecordMapping injectedReference(DrugReferenceInjector injector, String question) {
		return DrugReferenceTestSupport.injectedReference(injector.injectRecords(oneRecordChart(),
				DrugReferenceTestSupport.ctx(40, null, null, null, null, null), question));
	}

	@Test
	public void nullAndBlankRuleElementsRenderBestEffortWithoutThrowing() throws IOException {
		DrugReferenceInjector injector = DrugReferenceTestSupport
				.injector(DrugReferenceTestSupport.serviceWith(fixtureEntries()));

		PatientChart result = injector.injectRecords(oneRecordChart(),
				DrugReferenceTestSupport.ctx(40, null, null, null, null, null),
				"is mangled safe here?");

		String injected = result.getMappings().get(1).getText();
		assertTrue(injected.contains("Real warning survives"),
				"the real warning must survive its null/blank siblings: " + injected);
		assertTrue(injected.contains("test condition"),
				"the usable contraindication rule must still render: " + injected);
		assertTrue(injected.contains("note-only interaction"),
				"an interaction with only a note must render the note: " + injected);
		assertFalse(injected.contains("null"),
				"no null element may leak the literal 'null' into the citable record: " + injected);
	}

	@Test
	public void unusableEntriesAreDroppedAtParse() throws IOException {
		// An entry with no name would render "Drug reference — null" into the citable record (it can
		// still be injected order-driven via its ATC codes, alias-less or not), and one with no id has
		// no stable citation resourceUuid. Both are unusable — the parse boundary must drop them.
		List<DrugReference> entries = fixtureEntries();
		assertEquals(1, entries.size(),
				"entries with a blank id or name must be dropped at parse: " + entries.size());
		assertEquals("mangled", entries.get(0).getId());
	}

	@Test
	public void validatorIsUnfazedByNullRuleElements() throws IOException {
		// The validator's matching guards must also treat the null-element stubs as non-matching
		// rather than throwing (they carry nothing to match).
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(fixtureEntries()));

		List<SafetyWarning> warnings = validator.validate("Mangled 100 mg may be considered.",
				DrugReferenceTestSupport.ctx(40, null, null, null, null, null));
		assertNotNull(warnings);
	}

	@Test
	public void whitespaceOnlySourceIsPublishedAsNoAttributionAndAPaddedOneArrivesTrimmed()
			throws IOException {
		// `source` used to be appended to the citable record only when non-empty, so a whitespace-only
		// operator value rendered the literal " Source:    ." into the text. Issue #117 moved it onto
		// the mapping, which changes where a bad value lands: it is now published unconditionally as a
		// citation field, so blank must coalesce to "no attribution" rather than travel to a client as
		// whitespace it would render as a visible-but-empty provenance line. Same operator-editable
		// boundary the rest of this class covers, on the field that boundary now reaches.
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(BLANK_SOURCE_FIXTURE)));

		RecordMapping blank = injectedReference(injector, "is blanksource safe here?");
		assertNull(blank.getSource(),
				"a whitespace-only source must publish as no attribution, not as blank text: ["
						+ blank.getSource() + "]");
		assertFalse(blank.getText().contains("Source:"),
				"and nothing may be rendered into the citable record either: " + blank.getText());

		RecordMapping padded = injectedReference(injector, "is paddedsource safe here?");
		assertEquals("WHO Padded Attribution", padded.getSource(),
				"a padded source must arrive trimmed — a client renders this verbatim beside the "
						+ "citation: [" + padded.getSource() + "]");
	}

	@Test
	public void whitespaceOnlyRuleNoteFallsBackToTheTokenInTheWarning() throws IOException {
		// The fixture's testallergen rule carries a whitespace-only note: the warning detail must
		// coalesce past it to the token — never print blank/whitespace as the clinical reason.
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(fixtureEntries()));

		List<SafetyWarning> warnings = validator.validate("Mangled 100 mg may be considered.",
				DrugReferenceTestSupport.ctx(40, null, null, null,
						DrugReferenceTestSupport.set("testallergen"), null));
		String detail = warnings.stream()
				.filter(w -> w.getType().equals(SafetyWarning.TYPE_CONTRAINDICATION))
				.map(SafetyWarning::getDetail).findFirst().orElse("");
		assertTrue(detail.contains("testallergen"),
				"a whitespace-only note must fall back to the rule token: [" + detail + "]");
	}
}
