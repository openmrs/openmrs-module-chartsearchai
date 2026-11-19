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
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A {@code Nexium 40mg} order written against the plain Esomeprazole concept (CIEL 75875) is esomeprazole, and the
 * module must never tell a clinician it is omeprazole. Measured on the 3.7.1 reference application demo data on
 * 2026-11-18, patient Patricia Garcia ({@code Nexium 40mg} and {@code Clopidogrel 75mg}): a screen of her
 * medications answered "1 Major interaction — Omeprazole with Clopidogrel ... Omeprazole from Nexium 40mg.", naming
 * no esomeprazole at all, and "Is her Nexium safe with clopidogrel?" answered with an Omeprazole chip AND an
 * Esomeprazole chip for her one prescription.
 *
 * <p>The knowledge base bundled until ADR Decision 662 filed the brand {@code Nexium}, the code {@code A02BC05} and both
 * esomeprazole CIEL concepts on its Omeprazole row as well as its Esomeprazole row, because that row had been matched to
 * esomeprazole's RxCUI, so every leg resolving that order reached both rows. Decision 662 maps the row to omeprazole's own
 * RxCUI upstream and bundles the result. The cases drive the real service, context builder, validator and injector over
 * the shipped knowledge base, from the dataset the dictionary on the demo records; both fail on the earlier one.
 */
public class NexiumOnEsomeprazoleConceptContextTest extends BaseModuleContextSensitiveTest {

	private static final String ORDERS = "NexiumOnEsomeprazoleConceptOrdersTestData.xml";

	/** The questions the demo was asked: two screens, and one naming the pair. */
	private static final String[] QUESTIONS = { "Do any of her current medications interact with each other?",
			"Any drug interactions I should know about?", "Is her Nexium safe with clopidogrel?" };

	/** Omeprazole as a word: {@code Esomeprazole} carries the letters but not the word. */
	private static final Pattern OMEPRAZOLE = Pattern.compile("\\bomeprazole\\b", Pattern.CASE_INSENSITIVE);

	private Patient patient;

	@BeforeEach
	public void setUp() throws Exception {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS,
			"true");
		executeDataSet(ORDERS);
		patient = Context.getPatientService().getPatient(7);
	}

	@Test
	public void aNexiumOrderOnTheEsomeprazoleConceptIsNeverCalledOmeprazole() {
		for (String question : QUESTIONS) {
			ChartAnswer answer = serviceWith(DrugReferenceTestSupport.shippedServiceWithGroups()).search(patient,
				question);

			assertFalse(OMEPRAZOLE.matcher(answer.getAnswer()).find(),
				question + " — the answer names a drug her chart does not record: " + answer.getAnswer());
			for (SafetyWarning chip : answer.getSafetyWarnings()) {
				assertFalse(OMEPRAZOLE.matcher(chip.getDrug() + " " + chip.getDetail() + " " + chip.namedPartners() + " "
						+ chip.chartOrderBridges()).find(),
					question + " — a chip names a drug her chart does not record: " + chip.getDetail());
			}
		}
	}

	@Test
	public void herEsomeprazoleIsStillReportedWithHerClopidogrelOnceAsMajor() {
		for (String question : QUESTIONS) {
			ChartAnswer answer = serviceWith(DrugReferenceTestSupport.shippedServiceWithGroups()).search(patient,
				question);

			List<SafetyWarning> pair = new ArrayList<SafetyWarning>();
			for (SafetyWarning chip : answer.getSafetyWarnings()) {
				String text = chip.getDrug() + " " + chip.namedPartners();
				if (text.contains("Esomeprazole") && text.contains("Clopidogrel")
						|| OMEPRAZOLE.matcher(text).find() && text.contains("Clopidogrel")) {
					pair.add(chip);
				}
			}
			assertEquals(1, pair.size(),
				question + " — her one esomeprazole prescription and her clopidogrel are one pair, so one chip, was: "
						+ details(answer.getSafetyWarnings()));
			assertEquals("Major", pair.get(0).getSeverity(), question + " — was: " + pair.get(0).getDetail());
			assertTrue(answer.getAnswer().contains("Esomeprazole"),
				question + " — the answer states the finding about her esomeprazole: " + answer.getAnswer());
		}
	}

	private static List<String> details(List<SafetyWarning> chips) {
		List<String> details = new ArrayList<String>();
		for (SafetyWarning chip : chips) {
			details.add(chip.getDetail());
		}
		return details;
	}

	private static TestableService serviceWith(DrugReferenceService reference) {
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(new NoModel());
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(reference));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(reference));
		return service;
	}

	/** The module answers these questions itself, as it did on the demo; the model's answer names neither drug. */
	private static final class NoModel extends LlmProvider {

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question, String cacheScope,
				String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return new LlmResponse("The model's answer [1].", Collections.singletonList(Integer.valueOf(1)));
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer, String cacheScope,
				String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return search(numberedRecords, focusIndices, question, cacheScope, cacheSeedRecords, enumerateFindings,
				referenceRecords, drugsAlreadyOrdered);
		}

		@Override
		LlmEngine getActiveEngine() {
			return null;
		}
	}

	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
			return false;
		}

		@Override
		protected boolean resolveProgressiveReasoningEnabled() {
			return false;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return DrugReferenceTestSupport.chartOf(DrugReferenceTestSupport.obsRecord(1, "BP 120/80"));
		}

		@Override
		PatientChart buildFocusedChart(Patient patient, String question) {
			return buildChart(patient, question);
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}
}
