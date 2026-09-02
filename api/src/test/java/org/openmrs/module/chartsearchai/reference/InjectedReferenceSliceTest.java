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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #229 — how much of the prompt the module's own reference material occupies, measured off
 * the assembled chart by {@link ChartSearchAiUtils#referenceSlice}.
 *
 * <p>Before this, the number existed only inside {@code DrugReferenceInjector.injectRecords}, as a
 * private character sum consumed by one {@code log.debug} line — and on OpenMRS the {@code log.level}
 * global property is not applied at startup, so that line is not reachable by configuration alone.
 * Nothing carried the size out of the injector, so no durable channel could report it.
 *
 * <p><b>What these cases pin is that the slice is decided by the PROVENANCE CLASSIFICATION and not
 * by a list of type names</b>, which is the direction a plausible wrong implementation goes: "count
 * every record the injector added" satisfies a case that only checks a {@code drug_reference} and a
 * {@code safety_finding} are included. The injector writes several kinds and they do not all fall on
 * the same side — an {@code active_drug_order} is the patient's own prescription and groups as chart
 * evidence — so the arrangement here produces three of them and the exclusion is asserted beside the
 * inclusions. Mutate {@code referenceSlice}'s predicate in either direction and read the failures.
 * The fourth, the {@code drug_class_note} issue #354 added, cannot join this arrangement: it is
 * raised only where the question resolved no substance, which is exactly when no
 * {@code drug_reference} record is produced. It is NOT exclusive of the other reference-group kind —
 * a {@code safety_finding} can be raised beside it, the screening arm being gated on the same
 * emptiness, and {@code DrugClassQuestionNoteTest.theNoteDeniesNoScreenWhereTheScreeningArmRanBesideIt}
 * is that arrangement. {@code DrugClassQuestionNoteTest} covers the note's own slice contribution.
 */
public class InjectedReferenceSliceTest {

	/** The order uuid the arrangement's active order carries — unrepresented in the chart below. */
	private static final String SIMVASTATIN_ORDER_UUID = "11111111-2222-3333-4444-555555555555";

	/**
	 * One injection producing three of the injector's record kinds — every one that can co-occur —
	 * through the real
	 * load → parse → {@code injectRecords} → render chain: a question naming a drug that interacts
	 * with the patient's simvastatin (a {@code drug_reference} entry and the {@code safety_finding}
	 * derived from it), against a chart carrying no drug-order record for the simvastatin order
	 * (an {@code active_drug_order} record, issue #118).
	 */
	private PatientChart chartWithTheThreeCoOccurringInjectedKinds() {
		return DrugReferenceTestSupport
				.injectorWithSafety(DrugReferenceTestSupport.ddinterServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set("simvastatin"),
								DrugReferenceTestSupport.set("C10AA01"), null, null,
								Collections.singletonList(DrugReferenceTestSupport.activeOrder(
										SIMVASTATIN_ORDER_UUID, "Simvastatin Co 20mg", "simvastatin"))),
						"is it safe to give clarithromycin?");
	}

	@Test
	public void theSliceCountsEveryReferenceGroupRecordTheChartCarries() {
		PatientChart chart = chartWithTheThreeCoOccurringInjectedKinds();
		int references = DrugReferenceTestSupport.injectedReferences(chart).size();
		int findings = DrugReferenceTestSupport.injectedFindings(chart).size();
		assertTrue(references > 0 && findings > 0,
				"the arrangement must produce both reference kinds or it cannot discriminate: "
						+ chart.getText());

		ChartSearchAiUtils.ReferenceSlice slice =
				ChartSearchAiUtils.referenceSlice(chart.getMappings());

		assertEquals(references + findings, slice.getRecords(),
				"the slice is every reference-group record, so both kinds are counted");
	}

	@Test
	public void theSlicesCharacterTotalCountsTheFindingsAsWellAsTheEntries() {
		// The mutation-sensitive half. A slice keyed on `drug_reference` alone — which is what the
		// injector's own private character sum did before this — passes a record COUNT check that
		// only looks at the entries, and it silently under-reports the prompt budget by every
		// finding rendered beside them. Findings are uncapped at every layer (#229's own comment),
		// so that is the half most likely to grow.
		PatientChart chart = chartWithTheThreeCoOccurringInjectedKinds();
		int entryChars = 0;
		for (RecordMapping mapping : DrugReferenceTestSupport.injectedReferences(chart)) {
			entryChars += mapping.getText().length();
		}

		ChartSearchAiUtils.ReferenceSlice slice =
				ChartSearchAiUtils.referenceSlice(chart.getMappings());

		assertTrue(slice.getCharacters() > entryChars,
				"a slice that counted only the drug-reference entries would report " + entryChars
						+ "; the findings beside them are prompt budget too");
	}

	@Test
	public void thePatientsOwnActiveOrderRecordIsNotReferenceMaterial() {
		// The exclusion, and the reason this arrangement injects three kinds rather than two.
		// referenceGroup's own javadoc calls this the surprising direction: "Not everything injected
		// is reference material". An implementation reading "what did the injector add" rather than
		// "what does the classification say" is wrong here and passes every inclusion assertion.
		PatientChart chart = chartWithTheThreeCoOccurringInjectedKinds();
		List<RecordMapping> activeOrderRecords = DrugReferenceTestSupport.injectedActiveOrders(chart);
		int activeOrderChars = 0;
		for (RecordMapping mapping : activeOrderRecords) {
			activeOrderChars += mapping.getText().length();
		}
		assertEquals(1, activeOrderRecords.size(),
				"the arrangement must inject an active-order record or this case asserts nothing: "
						+ chart.getText());

		ChartSearchAiUtils.ReferenceSlice slice =
				ChartSearchAiUtils.referenceSlice(chart.getMappings());

		assertEquals(DrugReferenceTestSupport.injectedReferences(chart).size()
				+ DrugReferenceTestSupport.injectedFindings(chart).size(), slice.getRecords(),
				"the patient's own active order is chart evidence and must not be in the slice");
		int allInjectedChars = slice.getCharacters() + activeOrderChars;
		assertTrue(slice.getCharacters() < allInjectedChars,
				"counting every injected record would report " + allInjectedChars
						+ " rather than the reference slice's " + slice.getCharacters());
	}

	/**
	 * The DEBUG line reports the slice BESIDE the drug-reference-entry total it already reported, and
	 * the two are different numbers.
	 *
	 * <p>An earlier draft of this change replaced that total with the slice, on the reasoning that a
	 * log and an audit row must state the same number. The build refuted it:
	 * {@code ReferenceRecordSubstanceCollapseTest.theDebugLineReportsTheDrugReferenceEntryCharacterTotalAndOnlyThat}
	 * is the specification for that fragment and requires the entries' characters and explicitly not
	 * the findings', because that is issue #163's question. So the line answers both questions, and
	 * this case is the half of it #229 added — the other half stays pinned where it already was.
	 *
	 * <p>The strict inequality is what makes this more than "a number was printed": it holds because
	 * the arrangement injects a finding whose text the slice counts and the entry total does not, so
	 * a line printing one number twice fails it.
	 */
	@Test
	public void theDebugLineReportsTheSliceBesideTheEntryOnlyTotal() {
		List<String> logged;
		PatientChart chart;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER, Level.DEBUG)) {
			chart = chartWithTheThreeCoOccurringInjectedKinds();
			logged = capture.messagesAt(Level.DEBUG);
		}
		String line = String.join("\n", logged);
		ChartSearchAiUtils.ReferenceSlice slice =
				ChartSearchAiUtils.referenceSlice(chart.getMappings());

		Matcher reported =
				Pattern.compile("reference slice (\\d+) record\\(s\\), (\\d+) chars").matcher(line);
		assertTrue(reported.find(), "the DEBUG line must report the slice, was: " + logged);
		assertEquals(slice.getRecords(), Integer.parseInt(reported.group(1)));
		assertEquals(slice.getCharacters(), Integer.parseInt(reported.group(2)));

		Matcher entries = Pattern.compile("drug-reference \\((\\d+) chars\\)").matcher(line);
		assertTrue(entries.find(), "the entry-only total must still be on the line, was: " + logged);
		assertTrue(Integer.parseInt(entries.group(1)) < slice.getCharacters(),
				"the two totals answer different questions and this arrangement separates them; "
						+ "was " + entries.group(1) + " against " + slice.getCharacters());
	}

	@Test
	public void aChartCarryingNoReferenceMaterialReportsZeroRatherThanNothing() {
		// Zero is a real reading — the commonest one, a question that matches no entry — and it is
		// what the durable channel must be able to state. It is NOT the same as "nothing was
		// stated", which the answer expresses as a null slice.
		ChartSearchAiUtils.ReferenceSlice slice = ChartSearchAiUtils
				.referenceSlice(DrugReferenceTestSupport.oneRecordChart().getMappings());

		assertEquals(0, slice.getRecords());
		assertEquals(0, slice.getCharacters());
	}
}
