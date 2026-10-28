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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.ChartReadStatus;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A drug the question names by a name the answer's label for it is not built from is said to be that drug (ADR Decision
 * 277). On the 3.7.1 standalone "Is it safe to start glibenclamide?" read "3 interaction cautions for Glyburide:" — the
 * knowledge base's US name, which a clinician writing the international one need not know. Over the shipped knowledge base.
 */
public class LlmInferenceServiceNameAsAskedContextTest extends BaseModuleContextSensitiveTest {

	private static final String SAID = "Glibenclamide, as the question names it, is named Glyburide in this module's drug "
			+ "data.";

	@Test
	public void aDrugAskedAboutByAnotherNameIsSaidToBeTheDataNamedDrugTheAnswerNames() {
		answerFromFindings(true);
		String question = "Is it safe to start glibenclamide?";
		PatientChart chart = DrugReferenceTestSupport.injectedChartOverOrders(question, "Lisinopril 10mg", "Metformin 500mg",
			"Hydrochlorothiazide 25mg");
		assertNotNull(chart.getModuleAnswer(), "precondition: the module answers it");
		String composed = serviceOver(chart, "unused").search(patient(), question).getAnswer();
		assertTrue(composed.startsWith("3 interaction cautions for Glyburide — 3 Moderate.\n"), composed);
		assertTrue(composed.endsWith("\n" + SAID), composed);

		// The model's answer, and the module's own sentence after it, name it by the data's name alike.
		answerFromFindings(false);
		ChartAnswer model = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(question, "Lisinopril 10mg"),
			"Glyburide interacts with active order Lisinopril 10mg [1].").search(patient(), question);
		assertTrue(model.getAnswer().startsWith("Glyburide interacts with active order Lisinopril 10mg [1]. "),
			model.getAnswer());
		assertTrue(model.getAnswer().endsWith(". " + SAID), model.getAnswer());
	}

	/** Over an order the drug raises no finding against, so no sentence of the module's names it either. */
	@Test
	public void nothingIsSaidWhereTheAnswerNamesTheDrugAsAskedOrNotByTheDataName() {
		answerFromFindings(false);
		String question = "Is it safe to start glibenclamide?";
		ChartAnswer named = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(question, "Amlodipine 5mg"),
			"Glyburide can be given [1].").search(patient(), question);
		assertTrue(named.getAnswer().endsWith("[1]. " + SAID), "precondition: the data's name alone is said: "
				+ named.getAnswer());

		ChartAnswer asAsked = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(question, "Amlodipine 5mg"),
			"Glibenclamide (glyburide) can be given [1].").search(patient(), question);
		assertFalse(asAsked.getAnswer().contains("as the question names it"), asAsked.getAnswer());

		ChartAnswer unnamed = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(question, "Amlodipine 5mg"),
			"The records do not address it.").search(patient(), question);
		assertFalse(unnamed.getAnswer().contains("as the question names it"), unnamed.getAnswer());

		// The data's own name, punctuated otherwise, is no other name: on the 3.7.1 standalone "Can I give him St John's
		// wort?" was told "St John's wort, as the question names it, is named St. John's Wort".
		String punctuated = "Can I give him St John's wort?";
		ChartAnswer wort = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(punctuated, "Warfarin 5mg"),
			"St. John's Wort interacts with active order Warfarin 5mg [1].").search(patient(), punctuated);
		assertFalse(wort.getAnswer().contains("as the question names it"), wort.getAnswer());

		String byItsLabel = "Is it safe to start glyburide?";
		ChartAnswer label = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(byItsLabel, "Amlodipine 5mg"),
			"Glyburide can be given [1].").search(patient(), byItsLabel);
		assertFalse(label.getAnswer().contains("as the question names it"), label.getAnswer());
	}

	private static void answerFromFindings(boolean on) {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS,
			String.valueOf(on));
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	/** A service whose injector seam hands back {@code injected}, the real injector's chart, and whose model answers
	 *  {@code modelAnswer}. */
	private static TestableService serviceOver(final PatientChart injected, String modelAnswer) {
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(new StubProvider(modelAnswer));
		service.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question, ChartReadStatus readStatus) {
				return injected;
			}
		});
		service.setDrugSafetyValidator(new DrugSafetyValidator() {

			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient, List<RecordMapping> mappings,
					PairChipExtent.Sink pairExtentSink) {
				return Collections.emptyList();
			}
		});
		return service;
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

		@Override
		protected boolean resolveQueryScopedMode() {
			return false;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(1, "obs", "00000000-0000-0000-0000-000000000001", null, "BP 120/80"));
			return new PatientChart("[1] BP 120/80", mappings, Collections.<Integer> emptyList());
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	private static final class StubProvider extends LlmProvider {

		private final String answer;

		StubProvider(String answer) {
			this.answer = answer;
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question, String cacheScope,
				String cacheSeedRecords, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return new LlmResponse(answer, Collections.<Integer> emptyList());
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer, String cacheScope, String cacheSeedRecords,
				boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords,
				List<AlreadyOrderedDrug> drugsAlreadyOrdered) {
			return new LlmResponse(answer, Collections.<Integer> emptyList());
		}
	}
}
