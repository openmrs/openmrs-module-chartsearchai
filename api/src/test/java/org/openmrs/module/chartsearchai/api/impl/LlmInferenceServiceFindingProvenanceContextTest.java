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
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
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
	 * The abstention-dump carve-out is upstream of this and stays that way: an answer that is real
	 * prose and anchors NO citation inline surfaces nothing, so it cannot acquire a chart record
	 * either. Adding the provenance BEFORE that carve-out would attach the patient's allergy to a
	 * "nothing found" answer.
	 */
	@Test
	public void anAnswerThatAnchorsNoCitationInlineAcquiresNothing() {
		ChartAnswer answer = serviceUnderTest(new AbstainsWhileDumpingTheArray()).search(patient,
				QUESTION);

		assertTrue(answer.getReferences().isEmpty(),
				"an answer anchoring nothing inline surfaces no references at all, so there is no "
						+ "cited finding to bring a record with it. Was: " + indexes(answer));
	}

	/** Exposes the seams, and keeps warmup out of a test about a reference list. */
	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}
	}

	/** A two-record chart: an obs, and the allergy record the finding will name. */
	private static final class StubStrategy extends ChartBuildingStrategy {

		private final String allergyUuid;

		private StubStrategy(String allergyUuid) {
			this.allergyUuid = allergyUuid;
		}

		@Override
		PatientChart buildChart(Patient patient, String question) {
			List<RecordMapping> mappings = Arrays.asList(
					new RecordMapping(1, ChartSearchAiConstants.RESOURCE_TYPE_OBS, "obs-uuid-1", null,
							"BP 120/80"),
					new RecordMapping(ALLERGY_RECORD, ChartSearchAiConstants.RESOURCE_TYPE_ALLERGY,
							allergyUuid, null, "Allergy: Ibuprofen (drug)"));
			return new PatientChart("[1] BP 120/80\n[" + ALLERGY_RECORD + "] Allergy: Ibuprofen (drug)\n",
					mappings, Collections.<Integer> emptyList());
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
