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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Allergen;
import org.openmrs.AllergenType;
import org.openmrs.Allergy;
import org.openmrs.Concept;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.apache.logging.log4j.Level;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/305">#305</a> on the
 * surface it is about: the chart record a cited finding fired on reaches the answer's REFERENCE list,
 * so the clinician's click-through no longer depends on the model having cited it.
 *
 * <p><b>The measured defect.</b> {@code Can I give ibuprofen?} returned references {@code [11]} (the
 * allergy) and {@code [239]} (the finding) on 13 identical runs; {@code Can i give ibuprofen?} — one
 * character apart — returned {@code [239]} alone on 14. Everything upstream of the model was
 * byte-identical, so the divergence was entirely in whether the model put the record in its
 * structured {@code citations} array.
 *
 * <p><b>The stub provider is the second form.</b> It answers in the issue's own words, asserts the
 * allergy, and cites the finding ALONE — reading the finding's number out of the numbered records it
 * is handed rather than hardcoding an index, so a change to how many records the injector appends
 * cannot quietly turn the arrangement into one that cites nothing.
 *
 * <p><b>Context-sensitive with the REAL injector and validator</b>, unlike its sibling statement
 * tests, and that is not a shortcut: the provenance is collected by
 * {@code PatientClinicalContextBuilder} from a real {@code Allergy} read through
 * {@code PatientService}, so a pass-through injector or a stubbed validator would leave the whole
 * seam inert. The patient is patient 7 — persisted, because a detached {@code new Patient()} cannot
 * carry a saved allergy for the builder to read.
 */
public class LlmInferenceServiceFindingProvenanceContextTest extends BaseModuleContextSensitiveTest {

	/** Concept 88 (ASPIRIN), nominated as the {@code allergy.concept.otherNonCoded} placeholder that
	 *  {@code AllergyValidator} requires behind a free-text allergen. */
	private static final int OTHER_NON_CODED_CONCEPT = 88;

	private static final String QUESTION = "Can I give ibuprofen?";

	/** The chart record the allergy IS — the one the second form asserted and cited nothing for. */
	private static final int ALLERGY_RECORD = 2;

	/** The obs record, which the class-code cases below have the model cite so that the check has a
	 *  support pool at all — its first gate exits when no cited record states a class code. */
	private static final int OBS_RECORD = 1;

	/** Reads the finding's own number out of the numbered chart the provider is handed. */
	private static final Pattern FINDING_LINE = Pattern.compile("\\[(\\d+)\\] Safety finding");

	private Patient patient;

	private String allergyUuid;

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		patient = Context.getPatientService().getPatient(7);
		Concept otherNonCoded = Context.getConceptService().getConcept(OTHER_NON_CODED_CONCEPT);
		Context.getAdministrationService()
				.setGlobalProperty("allergy.concept.otherNonCoded", otherNonCoded.getUuid());
		Allergy allergy = new Allergy(patient,
				new Allergen(AllergenType.DRUG, otherNonCoded, "Ibuprofen"), null, null, null);
		Context.getPatientService().saveAllergy(allergy);
		Context.flushSession();
		Context.clearSession();
		allergyUuid = allergy.getUuid();
	}

	private TestableService serviceUnderTest(LlmProvider provider) {
		DrugReferenceService reference = DrugReferenceTestSupport.curatedService();
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy(allergyUuid));
		service.setLlmProvider(provider);
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(reference));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(reference));
		return service;
	}

	private static List<Integer> indexes(ChartAnswer answer) {
		List<Integer> out = new ArrayList<Integer>();
		for (RecordReference reference : answer.getReferences()) {
			out.add(Integer.valueOf(reference.getIndex()));
		}
		return out;
	}

	private static RecordReference referenceAt(ChartAnswer answer, int index) {
		for (RecordReference reference : answer.getReferences()) {
			if (reference.getIndex() == index) {
				return reference;
			}
		}
		return null;
	}

	/**
	 * THE case: the answer cites the finding and nothing else, and the allergy record reaches the
	 * reference list anyway.
	 */
	@Test
	public void aCitedFindingBringsTheChartRecordItFiredOnIntoTheReferences() {
		ChartAnswer answer = serviceUnderTest(new CitesTheFindingAlone()).search(patient, QUESTION);

		assertTrue(indexes(answer).contains(Integer.valueOf(ALLERGY_RECORD)),
				"the answer asserts the patient's recorded allergy and cites only the module's own "
						+ "finding, so the module must publish the record that allergy IS — otherwise "
						+ "the click-through is decided by the wording of the question (issue #305). "
						+ "References were: " + indexes(answer) + " for answer: " + answer.getAnswer());
		RecordReference attached = referenceAt(answer, ALLERGY_RECORD);
		assertEquals(ChartSearchAiConstants.RESOURCE_TYPE_ALLERGY, attached.getResourceType());
		assertEquals(allergyUuid, attached.getResourceUuid(),
				"and it carries the Allergy's own uuid, so a client can navigate to it");
	}

	/**
	 * The module says so rather than letting the citation pass as the model's own. A client renders a
	 * reference chip beside the answer; this one has no {@code [N]} marker in the prose to highlight,
	 * and no grounding verdict is being withheld from it.
	 */
	@Test
	public void anAttachedRecordSaysItWasAttachedByTheModuleAndTheModelsOwnCitationsDoNot() {
		ChartAnswer answer = serviceUnderTest(new CitesTheFindingAlone()).search(patient, QUESTION);

		assertTrue(referenceAt(answer, ALLERGY_RECORD).isAttachedByTheModule(),
				"the model did not cite this record; the module did, and says so");
		for (RecordReference reference : answer.getReferences()) {
			if (reference.getIndex() != ALLERGY_RECORD) {
				assertFalse(reference.isAttachedByTheModule(), reference.getResourceType()
						+ " [" + reference.getIndex() + "] was cited by the model, so it must not claim "
						+ "otherwise");
			}
		}
	}

	/**
	 * The first form of the issue's own measurement: the model cited the record itself. Nothing is
	 * added, nothing is duplicated, and the citation stays the model's own — so the fix cannot make
	 * the two forms disagree about what kind of citation this is.
	 */
	@Test
	public void aRecordTheModelCitedItselfIsUnchangedAndStaysTheModelsOwn() {
		ChartAnswer answer = serviceUnderTest(new CitesTheFindingAndTheRecord()).search(patient,
				QUESTION);

		List<Integer> indexes = indexes(answer);
		assertEquals(1, Collections.frequency(indexes, Integer.valueOf(ALLERGY_RECORD)),
				"the record must appear exactly once, was: " + indexes);
		assertFalse(referenceAt(answer, ALLERGY_RECORD).isAttachedByTheModule(),
				"the model cited it, so it is the model's citation and not the module's");
	}

	/**
	 * The STREAMING path, which is the one users hit. Its own {@code extractCitedReferences} call
	 * site, and its citations reach a client twice before the returned answer exists — on the early
	 * {@code references} event and on the early {@code done} — so a fix present only on the blocking
	 * path would be absent from every surface a user sees. {@code LlmInferenceServiceCitationWiringTest}
	 * exists for exactly this asymmetry and its class javadoc records why.
	 */
	@Test
	public void searchStreaming_bringsTheRecordOnTheEarlyCitationsAndTheEarlyDoneToo() {
		final List<List<Integer>> earlyCitations = new ArrayList<List<Integer>>();
		final List<List<Integer>> earlyDone = new ArrayList<List<Integer>>();

		ChartAnswer answer = serviceUnderTest(new CitesTheFindingAlone()).searchStreaming(patient,
			QUESTION, token -> { }, reasoning -> { },
			citations -> {
				List<Integer> seen = new ArrayList<Integer>();
				for (RecordReference reference : citations) {
					seen.add(Integer.valueOf(reference.getIndex()));
				}
				earlyCitations.add(seen);
			},
			early -> earlyDone.add(indexes(early)));

		assertEquals(1, earlyCitations.size(), "the citations consumer must have fired");
		assertTrue(earlyCitations.get(0).contains(Integer.valueOf(ALLERGY_RECORD)),
				"the early citations event is what a client renders while the grounding tail runs, so "
						+ "the attached record has to be on it. Was: " + earlyCitations.get(0));
		assertEquals(1, earlyDone.size(), "the early-done consumer must have fired");
		assertTrue(earlyDone.get(0).contains(Integer.valueOf(ALLERGY_RECORD)),
				"and on the early done, which under chartsearchai.grounding.async is the terminal "
						+ "event a user sees. Was: " + earlyDone.get(0));
		assertTrue(indexes(answer).contains(Integer.valueOf(ALLERGY_RECORD)),
				"and on the answer this method returns. Was: " + indexes(answer));
	}

	/**
	 * The abstention-dump carve-out is upstream of this and stays that way: an answer that is real
	 * prose and anchors NO citation inline surfaces nothing, so it cannot acquire a chart record
	 * either.
	 *
	 * <p><b>What this case cannot tell you</b> is that the attach step's POSITION relative to that
	 * carve-out matters. It does not: the carve-out returns an unconditional empty list, so nothing
	 * `seen` holds at that point can reach a client, and moving the attach step ahead of it leaves
	 * this case — and the whole suite — green. Measured, and said here because the comment at the
	 * attach site claimed otherwise. What the position DOES decide is
	 * {@link #attachesNothingToARecordTheModelAlreadyCitedInlineOnly}. This case's own claim is
	 * narrower and still worth pinning: the abstention-dump behaviour is unchanged by issue #305.
	 */
	@Test
	public void anAnswerThatAnchorsNoCitationInlineAcquiresNothing() {
		ChartAnswer answer = serviceUnderTest(new AbstainsWhileDumpingTheArray()).search(patient,
				QUESTION);

		assertTrue(answer.getReferences().isEmpty(),
				"an answer anchoring nothing inline surfaces no references at all, so there is no "
						+ "cited finding to bring a record with it. Was: " + indexes(answer));
	}

	/**
	 * The class-code check pools its support across the records the ANSWER reached for, and a record
	 * the MODULE attached is not one of them (issue #142's check, meeting issue #305).
	 *
	 * <p>The arrangement is the defect: the allergy record the module attaches carries the class code
	 * {@code M01AE01}, no record the model cited carries it, and the answer states it. Pooled, the
	 * attached record's code makes the answer's own code look copied — so a fabricated class code goes
	 * unreported for no reason but that the module happened to attach a record containing it. That is
	 * the check's whole subject reversed.
	 *
	 * <p>The obs record is cited so the check has a pool at all: its first gate exits when no cited
	 * record states a class code, which would make this case pass for the wrong reason.
	 */
	@Test
	public void aRecordTheModuleAttachedIsNotSupportForAClassCodeTheAnswerStates() {
		TestableService service = serviceUnderTest(new StatesAClassCode("M01AE01"));
		service.setChartBuildingStrategy(new StubStrategy(allergyUuid,
				"Serum creatinine 1.1 (C09AA01)", "Allergy: Ibuprofen (drug), class M01AE01"));

		try (LogCapture capture = LogCapture.on(ClassCodeFidelityCheck.class.getName())) {
			service.search(patient, QUESTION);

			assertTrue(capture.hasMessageAt(Level.WARN, "M01AE01"),
					"the answer states a class code only the ATTACHED record carries, so no record the "
							+ "answer cited supports it and the check must say so. Captured: "
							+ capture.describeAll());
		}
	}

	/**
	 * And a record the module attached cannot ABSTAIN the check either. The walk returns for the whole
	 * answer on the first cited record whose text is blank, which is right for a record the model
	 * cited — it may be the one that stated the code — and wrong for one the module supplied: an
	 * empty querystore document would then silence issue #142's check for every answer whose cited
	 * finding derives from it.
	 *
	 * <p>{@code QueryStoreChartBuilder} admits an empty text ({@code doc.getText() == null ? "" :
	 * doc.getText()}), so this is a reachable chart rather than a constructed one.
	 */
	@Test
	public void aBlankTextedAttachedRecordDoesNotAbstainTheClassCodeCheck() {
		TestableService service = serviceUnderTest(new StatesAClassCode("M01AE01"));
		service.setChartBuildingStrategy(new StubStrategy(allergyUuid,
				"Serum creatinine 1.1 (C09AA01)", ""));

		try (LogCapture capture = LogCapture.on(ClassCodeFidelityCheck.class.getName())) {
			service.search(patient, QUESTION);

			assertTrue(capture.hasMessageAt(Level.WARN, "M01AE01"),
					"a blank ATTACHED record must not abstain the check for the whole answer. "
							+ "Captured: " + capture.describeAll());
		}
	}

	/** Exposes the seams, and keeps warmup out of a test about a reference list. */
	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}
	}

	/** A two-record chart: an obs, and the allergy record the finding will name. Both texts are
	 *  parameters because the class-code cases below turn on what each of them states. */
	private static final class StubStrategy extends ChartBuildingStrategy {

		private final String allergyUuid;

		private final String obsText;

		private final String allergyText;

		private StubStrategy(String allergyUuid) {
			this(allergyUuid, "BP 120/80", "Allergy: Ibuprofen (drug)");
		}

		private StubStrategy(String allergyUuid, String obsText, String allergyText) {
			this.allergyUuid = allergyUuid;
			this.obsText = obsText;
			this.allergyText = allergyText;
		}

		@Override
		PatientChart buildChart(Patient patient, String question) {
			List<RecordMapping> mappings = Arrays.asList(
					new RecordMapping(OBS_RECORD, ChartSearchAiConstants.RESOURCE_TYPE_OBS, "obs-uuid-1",
							null, obsText),
					new RecordMapping(ALLERGY_RECORD, ChartSearchAiConstants.RESOURCE_TYPE_ALLERGY,
							allergyUuid, null, allergyText));
			return new PatientChart("[" + OBS_RECORD + "] " + obsText + "\n[" + ALLERGY_RECORD + "] "
					+ allergyText + "\n", mappings, Collections.<Integer> emptyList());
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	/** The number the injected finding was given, read out of the numbered chart. */
	private static int findingNumber(String numberedRecords) {
		Matcher matcher = FINDING_LINE.matcher(numberedRecords);
		if (!matcher.find()) {
			throw new IllegalStateException("the arrangement must inject a safety finding, chart was: "
					+ numberedRecords);
		}
		return Integer.parseInt(matcher.group(1));
	}

	/** Issue #305's SECOND form: the allergy asserted, the finding cited, the record not. */
	private static class CitesTheFindingAlone extends LlmProvider {

		LlmResponse answer(String numberedRecords) {
			int finding = findingNumber(numberedRecords);
			return new LlmResponse("No — Ibuprofen should not be given: the patient has a recorded "
					+ "allergy to Ibuprofen [" + finding + "].",
					Collections.singletonList(Integer.valueOf(finding)));
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question) {
			return answer(numberedRecords);
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope) {
			return answer(numberedRecords);
		}
	}

	/** Issue #305's FIRST form: both cited, which is what the module must not duplicate or relabel. */
	private static final class CitesTheFindingAndTheRecord extends CitesTheFindingAlone {

		@Override
		LlmResponse answer(String numberedRecords) {
			int finding = findingNumber(numberedRecords);
			return new LlmResponse("No — Ibuprofen should not be given: the patient has a recorded "
					+ "allergy to Ibuprofen [" + ALLERGY_RECORD + "][" + finding + "].",
					Arrays.asList(Integer.valueOf(ALLERGY_RECORD), Integer.valueOf(finding)));
		}
	}

	/**
	 * Cites the finding and the OBS record, and states {@code code} — the shape the class-code check
	 * needs: a support pool from a record the model really cited, and one asserted code.
	 */
	private static final class StatesAClassCode extends CitesTheFindingAlone {

		private final String code;

		private StatesAClassCode(String code) {
			this.code = code;
		}

		@Override
		LlmResponse answer(String numberedRecords) {
			int finding = findingNumber(numberedRecords);
			return new LlmResponse("No — Ibuprofen (" + code + ") should not be given: the patient has "
					+ "a recorded allergy to Ibuprofen [" + finding + "], and her renal function is "
					+ "stable [" + OBS_RECORD + "].",
					Arrays.asList(Integer.valueOf(finding), Integer.valueOf(OBS_RECORD)));
		}
	}

	/** The abstention-dump failure mode: real prose citing nothing inline, whole record set in the
	 *  structured array. */
	private static final class AbstainsWhileDumpingTheArray extends CitesTheFindingAlone {

		@Override
		LlmResponse answer(String numberedRecords) {
			int finding = findingNumber(numberedRecords);
			return new LlmResponse("The records do not address whether ibuprofen can be given.",
					Arrays.asList(Integer.valueOf(1), Integer.valueOf(ALLERGY_RECORD),
							Integer.valueOf(finding)));
		}
	}
}
