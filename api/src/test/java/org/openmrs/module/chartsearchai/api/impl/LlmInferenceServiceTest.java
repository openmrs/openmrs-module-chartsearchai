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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.apache.logging.log4j.Level;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

public class LlmInferenceServiceTest {

	private static String uuid(int i) {
		return TestDatasetHelper.uuidForIndex(i);
	}

	@Test
	public void extractCitedReferences_shouldExtractReferencesFromCitations() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(456), null),
				new RecordMapping(2, "order", uuid(201), null));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Arrays.asList(1, 2), mappings);

		assertEquals(2, result.size());
		assertEquals("obs", result.get(0).getResourceType());
		assertEquals(uuid(456), result.get(0).getResourceUuid());
		assertEquals("order", result.get(1).getResourceType());
		assertEquals(uuid(201), result.get(1).getResourceUuid());
	}

	@Test
	public void extractCitedReferences_shouldReturnEmptyWhenNoCitations() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(456), null));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Collections.emptyList(), mappings);

		assertTrue(result.isEmpty());
	}

	@Test
	public void extractCitedReferences_shouldDeduplicateRepeatedCitations() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(456), null));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Arrays.asList(1, 1), mappings);

		assertEquals(1, result.size());
		assertEquals(uuid(456), result.get(0).getResourceUuid());
	}

	@Test
	public void extractCitedReferences_shouldHandleMultipleCitations() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(101), null),
				new RecordMapping(2, "obs", uuid(102), null),
				new RecordMapping(3, "obs", uuid(103), null));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Arrays.asList(1, 2, 3), mappings);

		assertEquals(3, result.size());
		assertEquals(uuid(101), result.get(0).getResourceUuid());
		assertEquals(uuid(102), result.get(1).getResourceUuid());
		assertEquals(uuid(103), result.get(2).getResourceUuid());
	}

	@Test
	public void extractCitedReferences_shouldIgnoreNumbersNotInMappings() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(10), null));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Arrays.asList(1, 99), mappings);

		assertEquals(1, result.size());
		assertEquals(uuid(10), result.get(0).getResourceUuid());
	}

	@Test
	public void extractCitedReferences_shouldSortByDateMostRecentFirst() {
		Date jan = makeDate(2025, 1, 10);
		Date mar = makeDate(2025, 3, 15);
		Date feb = makeDate(2025, 2, 20);

		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "condition", uuid(50), jan),
				new RecordMapping(2, "order", uuid(30), mar),
				new RecordMapping(3, "obs", uuid(999), feb));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Arrays.asList(1, 2, 3), mappings);

		assertEquals(3, result.size());
		assertEquals(uuid(30), result.get(0).getResourceUuid());
		assertEquals(mar, result.get(0).getDate());
		assertEquals(uuid(999), result.get(1).getResourceUuid());
		assertEquals(feb, result.get(1).getDate());
		assertEquals(uuid(50), result.get(2).getResourceUuid());
		assertEquals(jan, result.get(2).getDate());
	}

	@Test
	public void extractCitedReferences_shouldPutNullDatesLast() {
		Date recent = makeDate(2025, 3, 1);

		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(100), null),
				new RecordMapping(2, "obs", uuid(200), recent));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				Arrays.asList(1, 2), mappings);

		assertEquals(2, result.size());
		assertEquals(uuid(200), result.get(0).getResourceUuid());
		assertEquals(recent, result.get(0).getDate());
		assertEquals(uuid(100), result.get(1).getResourceUuid());
		assertNull(result.get(1).getDate());
	}

	@Test
	public void extractCitedReferences_shouldIncludeInlineMarkersMissingFromCitationsArray() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "program", uuid(1), null),
				new RecordMapping(8, "condition", uuid(8), null),
				new RecordMapping(9, "obs", uuid(9), null));

		// The LLM wrote [1] and [8] inline in the prose but listed only [9] in
		// its structured citations array. Every inline-cited record that exists
		// in the chart must still resolve to a clickable reference, otherwise the
		// answer text points at a citation the UI cannot render.
		String answer = "Diabetes program [1]. Active Tuberculosis [8]. CD4 988.0 [9].";

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				answer, Arrays.asList(9), mappings);

		List<Integer> indices = new ArrayList<Integer>();
		for (RecordReference ref : result) {
			indices.add(ref.getIndex());
		}
		Collections.sort(indices);
		assertEquals(Arrays.asList(1, 8, 9), indices);
	}

	@Test
	public void extractCitedReferences_shouldNotAddInlineMarkersWithNoMapping() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(9, "obs", uuid(9), null));

		// [8] is cited inline but there is no record 8 in the chart. A dangling
		// inline marker must not fabricate a reference.
		String answer = "Tuberculosis [8]. CD4 988.0 [9].";

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				answer, Arrays.asList(9), mappings);

		assertEquals(1, result.size());
		assertEquals(9, result.get(0).getIndex());
	}

	@Test
	public void extractCitedReferences_shouldKeepTheArrayWhenTheOnlyInlineMarkerIsUnmapped() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(8, "condition", uuid(8), null),
				new RecordMapping(9, "obs", uuid(9), null));

		// A positive answer whose ONLY inline marker is a typo ([99] does not exist)
		// while its structured array lists real records. The abstention-dump drop
		// must NOT fire: the model DID attempt an inline citation, so this is a
		// mistyped positive answer, not an abstention. Any inline marker — mapped or
		// not — bypasses the drop by design; a map-aware drop that tried to also
		// catch a stray-bracket abstention would lose these legitimate array
		// citations, and the two cases are otherwise indistinguishable without
		// semantic abstention detection. This locks that decision.
		String answer = "Yes, the patient has cancer [99].";

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				answer, Arrays.asList(8, 9), mappings);

		List<Integer> indices = new ArrayList<Integer>();
		for (RecordReference ref : result) {
			indices.add(ref.getIndex());
		}
		Collections.sort(indices);
		assertEquals(Arrays.asList(8, 9), indices);
	}

	/**
	 * A record the model cited INLINE ONLY stays the model's citation, even though the finding it is
	 * the provenance of was cited too (issue #305).
	 *
	 * <p>This is the case that pins WHERE the attach step sits, and the mutation it reddens on is
	 * precise: not moving the block, but having its {@code !seen.contains(derived)} check read the
	 * citations array alone — the state before {@code seen.addAll(inline)}. Record [1] is then not yet
	 * one of the model's citations, so it is admitted and published as
	 * {@code attachedByTheModule} — the module claiming a citation the model wrote, which a client
	 * would render without the {@code [N]} highlight the prose does carry. Iterating a pre-inline
	 * snapshot while leaving that check on {@code seen} moves nothing; measured both ways.
	 *
	 * <p>Deliberately NOT the abstention carve-out: a first draft of the attach site claimed the
	 * position mattered relative to that, and it does not — the carve-out returns an unconditional
	 * empty list, so moving the step across it leaves every case green.
	 */
	@Test
	public void extractCitedReferences_shouldNotClaimARecordTheModelCitedInlineOnly() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "allergy", uuid(456), null, "Allergy: Aspirin"),
				new RecordMapping(2, "safety_finding", "contraindication:Ibuprofen", null,
						"Safety finding", null, 0, null, null, Arrays.asList(Integer.valueOf(1))));

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				"Ibuprofen should not be given: she is allergic to aspirin [1].",
				Arrays.asList(Integer.valueOf(2)), mappings);

		assertEquals(2, result.size(), "both records resolve, was: " + result.size());
		for (RecordReference reference : result) {
			assertFalse(reference.isAttachedByTheModule(), "reference [" + reference.getIndex()
					+ "] was cited by the model — inline for [1], in the array for [2] — so neither is "
					+ "the module's citation");
		}
	}

	/**
	 * A derivation naming an index this mapping list has no record for adds nothing, and says nothing
	 * about it (issue #305).
	 *
	 * <p>Reachable through this entry point and no other: on the production path the derivation is
	 * resolved off the very list that arrives here, so every derived index maps. A caller that hands
	 * this method a different list is what the guard is for, and it fails closed — dropping the
	 * attachment rather than publishing a reference to nothing, and without the
	 * "cited record which does not exist" WARN, because an unmapped derivation is the module's own
	 * bookkeeping and not something the model claimed.
	 */
	@Test
	public void extractCitedReferences_shouldIgnoreADerivationWithNoMappingOfItsOwn() {
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(2, "safety_finding", "contraindication:Ibuprofen", null,
						"Safety finding", null, 0, null, null, Arrays.asList(Integer.valueOf(9))));

		// The PACKAGE and not this class's own logger, and the reason is a leak rather than a
		// preference: LogCapture restores the EFFECTIVE level it found, so capturing a class logger
		// leaves that logger with an explicit level of its own — which then overrides a later
		// PACKAGE-scoped capture and starves it of this pipeline's INFO lines. Measured: capturing
		// on LlmInferenceService here reddened 30 cases across the three fidelity test classes, all
		// on their "the capture must receive the pipeline's own INFO lines, or this passes
		// vacuously" control, and only in a full-suite run. The package is the scope those classes
		// already use.
		try (LogCapture capture = LogCapture.on("org.openmrs.module.chartsearchai.api.impl")) {
			List<RecordReference> result = LlmInferenceService.extractCitedReferences(
					Arrays.asList(Integer.valueOf(2)), mappings);

			assertEquals(1, result.size(), "only the cited finding resolves, was: " + result);
			assertEquals(2, result.get(0).getIndex());
			assertFalse(result.get(0).isAttachedByTheModule(),
					"the finding itself is the model's citation");
			// The discriminating assertion, and the reference COUNT is not it: without the guard,
			// index 9 joins `seen` and the reference walk then takes its unmapped branch, which
			// produces the same one-reference result and this WARN. Drop the conjunct and only this
			// line reddens.
			assertFalse(capture.hasMessageAt(Level.WARN, "[9]"),
					"an unmapped DERIVATION is the module's own bookkeeping, so it must not be "
							+ "reported as a record the LLM cited. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void extractCitedReferences_shouldCarryCitationMetadataFromTheMapping() {
		// Issue #117 moved a drug-reference record's dataset attribution and withheld-partner count
		// off the citable text — where the model recited them into answers — and onto the mapping,
		// so a client can render provenance on the citation chip instead. This is the hop that makes
		// that reachable: without it the two facts stop at the mapping and are effectively lost.
		//
		// Real mappings from the real injector over the real DDInter excerpt, so the values
		// asserted are the ones production computes, not hand-set stand-ins.
		List<RecordMapping> mappings = org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport
				.injectedDdinterMappings("is warfarin safe to add?");
		RecordMapping reference = null;
		RecordMapping chartRecord = null;
		for (RecordMapping mapping : mappings) {
			if ("drug_reference".equals(mapping.getResourceType())) {
				reference = mapping;
			} else {
				chartRecord = mapping;
			}
		}
		assertTrue(reference != null && chartRecord != null,
				"precondition: the fixture chart must hold one chart record and one injected reference");
		assertTrue(reference.getWithheldInteractions() > 0,
				"precondition: the Warfarin entry must be broad enough for the cap to withhold partners");

		List<RecordReference> result = LlmInferenceService.extractCitedReferences(
				"Warfarin interacts with several drugs [" + reference.getIndex() + "] per the chart ["
						+ chartRecord.getIndex() + "].",
				Arrays.asList(reference.getIndex(), chartRecord.getIndex()), mappings);

		// Both citations resolved, or the loop below leaves one side null and the assertions fail as a
		// NullPointerException instead of naming which citation went missing.
		assertEquals(2, result.size(), "both cited records must resolve to references: " + result);
		RecordReference cited = null;
		RecordReference citedChart = null;
		for (RecordReference ref : result) {
			if (ref.getIndex() == reference.getIndex()) {
				cited = ref;
			} else {
				citedChart = ref;
			}
		}
		assertEquals(reference.getSource(), cited.getSource(),
				"the cited reference must carry the record's dataset attribution");
		assertEquals(reference.getWithheldInteractions(), cited.getWithheldInteractions(),
				"and the count of partners the render budget withheld");
		assertNull(citedChart.getSource(),
				"a chart record has no dataset attribution — its provenance is the patient's record");
		assertEquals(0, citedChart.getWithheldInteractions(),
				"and nothing withheld");
	}

	@Test
	public void groundingVerdictMustNotDropCitationMetadata() {
		// withGrounded copies the reference to attach a verdict, so it is the one place the metadata
		// added in #117 can silently fall off — and it runs on every grounded answer, i.e. the
		// default path. A dropped source there would make provenance appear on ungrounded answers
		// only, which is exactly the kind of divergence nobody notices.
		RecordReference reference = new RecordReference(7, "drug_reference", "11289", null, null,
				"DDInter 2.0 (via openmrs-ddi-knowledge-base)", 12);

		RecordReference grounded = reference.withGrounded(Boolean.FALSE);

		assertEquals(Boolean.FALSE, grounded.getGrounded(), "the verdict must be attached");
		assertEquals("DDInter 2.0 (via openmrs-ddi-knowledge-base)", grounded.getSource(),
				"attaching a verdict must not drop the citation's provenance");
		assertEquals(12, grounded.getWithheldInteractions(),
				"nor the withheld-partner count");
	}

	@Test
	public void stripQueryStopwords_shouldNormalizeDifferentPhrasingsToSameResult() {
		// Both queries have only 1 content word ("medications"), so both
		// preserve the full sentence. The embedding model handles both
		// similarly because the key term is the same.
		String short1 = LlmInferenceService.stripQueryStopwords("any medications?");
		String long1 = LlmInferenceService.stripQueryStopwords("does the patient have any medications?");
		assertTrue(short1.contains("medications"), "Short form should contain 'medications'");
		assertTrue(long1.contains("medications"), "Long form should contain 'medications'");
	}

	@Test
	public void stripQueryStopwords_shouldPreserveContentWords() {
		assertEquals("medications hypertension",
				LlmInferenceService.stripQueryStopwords("any medications for hypertension?"));
	}

	@Test
	public void stripQueryStopwords_shouldReturnOriginalWhenAllStopwords() {
		String result = LlmInferenceService.stripQueryStopwords("does the patient have any?");
		assertTrue(!result.isEmpty());
	}

	@Test
	public void stripQueryStopwords_shouldHandleMixedCase() {
		// 1 content word "medications" → preserves full query for context.
		// Should be lowercased and contain the key term.
		String result = LlmInferenceService.stripQueryStopwords(
				"Does The Patient Have Any Medications?");
		assertTrue(result.contains("medications"),
				"Mixed case query should contain 'medications'");
		assertEquals(result, result.toLowerCase(),
				"Result should be lowercased");
	}

	@Test
	public void stripQueryStopwords_shouldPreserveContextForShortQueries() {
		// When stopword removal would leave < 2 words, the full question
		// should be preserved to give the embedding model enough context.
		// "does the patient have cancer?" has only 1 content word ("cancer").
		// The original code embedded the full question and returned 2 results;
		// stripping to just "cancer" loses context and returns 3.
		String result = LlmInferenceService.stripQueryStopwords(
				"does the patient have cancer?");
		String[] words = result.trim().split("\\s+");
		assertTrue(words.length >= 2,
				"Short query should preserve context words, got: '" + result + "'");
	}

	@Test
	public void stripQueryStopwords_shouldNormalizeCurrentAndLatestToSameResult() {
		assertEquals(
				LlmInferenceService.stripQueryStopwords("What is the current CD4 Count?"),
				LlmInferenceService.stripQueryStopwords("What is the latest CD4 Count?"));
	}

	@Test
	public void patientChart_demographicsOnlyHasNoRecords() {
		// A chart with only demographics (no records) should have empty
		// mappings even though getText() is non-empty. The search methods
		// must check mappings, not text, to detect "no records found".
		org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart chart =
				new org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart(
				"Patient: 4-year-old Male", Collections.<RecordMapping>emptyList());
		assertFalse(chart.getText().isEmpty(),
				"Demographics-only chart has non-empty text");
		assertTrue(chart.getMappings().isEmpty(),
				"Demographics-only chart should have no record mappings");
	}

	@Test
	public void searchStreaming_emitsCitationsOnTheCitationsChannelDuringTheCall() {
		// The async-grounding contract: the answer's citations are pushed to the citations channel
		// DURING the call (so the UI can render clickable citations immediately) — not only via the
		// returned answer — and they carry no grounding verdict yet (grounded == null). Exercises the
		// real production orchestration (buildChart -> generate -> extract citations -> ground) with
		// the chart/LLM collaborators stubbed; grounding is off (no context) so it is a clean no-op.
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", uuid(11), null),
				new RecordMapping(2, "order", uuid(22), null));
		PatientChart chart = new PatientChart("records", mappings);

		LlmInferenceService service = new LlmInferenceService();
		service.setDrugReferenceInjector(new org.openmrs.module.chartsearchai.reference.DrugReferenceInjector() {

			@Override
			public org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart inject(
					org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart chart,
					org.openmrs.Patient patient, String question) {
				return chart;
			}
		});
		service.setDrugSafetyValidator(new org.openmrs.module.chartsearchai.reference.DrugSafetyValidator() {

			// The overload production actually calls: mappings-carrying for echo scoping (issue #105)
			// and sink-carrying since issue #336. Stubbing the four-argument one instead leaves this
			// stub INERT — production would not reach it — which is why it names both parameters.
			@Override
			public java.util.List<org.openmrs.module.chartsearchai.reference.SafetyWarning> validate(
					String answer, String question, org.openmrs.Patient patient,
					java.util.List<RecordMapping> mappings,
					org.openmrs.module.chartsearchai.reference.PairChipExtent.Sink pairExtentSink) {
				return java.util.Collections.emptyList();
			}
		});
		service.setChartBuildingStrategy(new ChartBuildingStrategy() {

			@Override
			PatientChart buildChart(Patient patient, String question) {
				return chart;
			}
		});
		service.setLlmProvider(new LlmProvider() {

			// Production now calls the scope-aware 6-arg overload (it passes the patient UUID so the
			// local engine can restore/persist the patient's prefilled chart KV). Override that one;
			// the scope is irrelevant to this citations-channel assertion, so it is ignored.
			@Override
			public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
					String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
					String cacheScope) {
				tokenConsumer.accept("Finding A [1] and finding B [2].");
				return new LlmResponse("Finding A [1] and finding B [2].", Arrays.asList(1, 2));
			}
		});

		List<List<RecordReference>> captured = new ArrayList<List<RecordReference>>();
		ChartAnswer answer = service.searchStreaming(null, "any findings?",
				token -> { }, reasoning -> { }, captured::add);

		assertEquals(1, captured.size(), "citations channel must fire exactly once per answer");
		List<RecordReference> early = captured.get(0);
		assertEquals(2, early.size(), "both cited records must reach the citations channel");
		assertEquals(uuid(11), early.get(0).getResourceUuid());
		assertEquals(uuid(22), early.get(1).getResourceUuid());
		// Emitted before grounding -> no verdict yet; clients must render these as unverified.
		assertNull(early.get(0).getGrounded());
		assertNull(early.get(1).getGrounded());
		// The same citations come back on the returned answer.
		assertEquals(2, answer.getReferences().size());
	}

	@Test
	public void extractRecencyCap_shouldExtractNumberFromLastN() {
		assertEquals(7, LlmInferenceService.extractRecencyCap(
				"How have vitals trended across the last 7 visits?"));
	}

	@Test
	public void extractRecencyCap_shouldExtractFromPastN() {
		assertEquals(3, LlmInferenceService.extractRecencyCap(
				"Show past 3 lab results"));
	}

	@Test
	public void extractRecencyCap_shouldExtractFromPreviousN() {
		assertEquals(5, LlmInferenceService.extractRecencyCap(
				"What were the previous 5 blood pressure readings?"));
	}

	@Test
	public void extractRecencyCap_shouldExtractFromMostRecentN() {
		assertEquals(10, LlmInferenceService.extractRecencyCap(
				"List the most recent 10 observations"));
	}

	@Test
	public void extractRecencyCap_shouldBeCaseInsensitive() {
		assertEquals(7, LlmInferenceService.extractRecencyCap(
				"LAST 7 visits"));
	}

	@Test
	public void extractRecencyCap_shouldReturnZeroWhenNoPattern() {
		assertEquals(0, LlmInferenceService.extractRecencyCap(
				"What medications is the patient on?"));
	}

	@Test
	public void extractRecencyCap_shouldReturnZeroForZero() {
		assertEquals(0, LlmInferenceService.extractRecencyCap(
				"last 0 visits"));
	}

	@Test
	public void extractRecencyCap_shouldExtractFromLatestN() {
		assertEquals(2, LlmInferenceService.extractRecencyCap(
				"What are the latest 2 weights?"));
	}

	@Test
	public void extractRecencyCap_shouldExtractWordNumberTwo() {
		assertEquals(2, LlmInferenceService.extractRecencyCap(
				"What are the latest two weights?"));
	}

	@Test
	public void extractRecencyCap_shouldExtractWordNumberThree() {
		assertEquals(3, LlmInferenceService.extractRecencyCap(
				"Show the last three lab results"));
	}

	@Test
	public void extractRecencyCap_shouldExtractWordNumberFive() {
		assertEquals(5, LlmInferenceService.extractRecencyCap(
				"previous five blood pressure readings"));
	}

	@Test
	public void extractRecencyCap_shouldExtractWordNumberTen() {
		assertEquals(10, LlmInferenceService.extractRecencyCap(
				"most recent ten observations"));
	}

	@Test
	public void extractRecencyCap_shouldExtractNumberBeforeKeyword() {
		assertEquals(2, LlmInferenceService.extractRecencyCap(
				"What are the two latest weights?"));
	}

	@Test
	public void extractRecencyCap_shouldExtractDigitBeforeKeyword() {
		assertEquals(3, LlmInferenceService.extractRecencyCap(
				"Show the 3 most recent lab results"));
	}

	@Test
	public void extractQueryTerms_shouldFilterOutSingleCharacterTerms() {
		String[] terms = LlmInferenceService.extractQueryTerms("a medications b");
		assertEquals(1, terms.length);
		assertEquals("medications", terms[0]);
	}

	@Test
	public void extractQueryTerms_shouldLowercaseTerms() {
		String[] terms = LlmInferenceService.extractQueryTerms("Metformin Dose");
		assertEquals(2, terms.length);
		assertEquals("metformin", terms[0]);
		assertEquals("dose", terms[1]);
	}

	@Test
	public void extractQueryTerms_shouldHandleEmptyInput() {
		String[] terms = LlmInferenceService.extractQueryTerms("");
		assertEquals(0, terms.length);
	}

	@Test
	public void stripCategoryHints_shouldStripHintsFromTestOrderText() {
		// stripCategoryHints had "Lab order: " instead of "Test order: "
		// in its patterns — Test orders are serialized with a "Test order:"
		// prefix, so hint stripping must recognize that.
		String enriched = "Laboratory tests / Test order: CBC. Action: NEW. Urgency: STAT";
		String stripped = ChartSearchAiUtils.stripCategoryHints(enriched);
		assertEquals("Test order: CBC. Action: NEW. Urgency: STAT", stripped);
	}

	@Test
	public void stripCategoryHints_shouldStripHintsFromReferralOrderText() {
		String enriched = "Specialty referrals / Referral order: Cardiology. Action: NEW";
		String stripped = ChartSearchAiUtils.stripCategoryHints(enriched);
		assertEquals("Referral order: Cardiology. Action: NEW", stripped);
	}

	@Test
	public void stripCategoryHints_shouldStripHintsFromDispensedText() {
		String enriched = "Antiretrovirals / Dispensed: Efavirenz 600mg. Status: Completed";
		String stripped = ChartSearchAiUtils.stripCategoryHints(enriched);
		assertEquals("Dispensed: Efavirenz 600mg. Status: Completed", stripped);
	}

	@Test
	public void stripCategoryHints_shouldStripHintsFromRealDatasetTestOrder() {
		// Uses real text from THIRD dataset index 15 (lab test order)
		// to verify hint stripping works on production-shaped records.
		String rawText = TestDatasetHelper.stripDatasetPrefixAndDate(
				TestDatasetHelper.THIRD_PATIENT_DATASET[15]);
		String enriched = ChartSearchAiUtils.injectCategoryHints(
				rawText, Arrays.asList("Laboratory tests"));
		assertEquals(rawText,
				ChartSearchAiUtils.stripCategoryHints(enriched));
	}

	private static Date makeDate(int year, int month, int day) {
		Calendar cal = Calendar.getInstance();
		cal.set(year, month - 1, day, 0, 0, 0);
		cal.set(Calendar.MILLISECOND, 0);
		return cal.getTime();
	}
}
