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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue #553 through the whole answer: the words the MODULE itself writes about an order scheduled to
 * start in the future — the stand-in record for an order the chart carries no record of, the sentence
 * naming a cited finding's partner the answer left unnamed, the allergy answer's statement of her
 * conflicting orders, and the line a module-composed answer states a contraindication about her order
 * in. The patient's orders are read from the database ({@code ScheduledDrugOrderTestData.xml}:
 * a started Nevirapine and a scheduled Rifampicin), through the real injector and validator over the
 * verbatim DDInter slice that relates them; only the model and the chart retrieval are stood in for.
 */
public class LlmInferenceServiceScheduledOrderContextTest extends BaseModuleContextSensitiveTest {

	private static final String SLICE = "chartsearchai-test/ddi-listed-medications-proposal.json";

	private static final String AMLODIPINE_QUESTION = "Can I give her amlodipine?";

	private static final String STARTS = "scheduled to start 2099-01-01";

	private Patient patient;

	@BeforeEach
	public void setUp() {
		executeDataSet("ScheduledDrugOrderTestData.xml");
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		patient = Context.getPatientService().getPatient(7);
	}

	private static Recorder serviceAnswering(String modelAnswer) throws IOException {
		DrugReferenceService references = DrugReferenceTestSupport.ddiFixtureService(SLICE);
		Recorder recorder = new Recorder(modelAnswer);
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy(DrugReferenceTestSupport.obsRecord(1, "BP 120/80")));
		service.setLlmProvider(recorder);
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(references));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(references));
		recorder.service = service;
		return recorder;
	}

	/** The number of the one finding about {@code drug} naming Rifampicin in the prompt the model read. */
	private static int rifampicinFindingAbout(String prompt, String drug) {
		Matcher line = Pattern.compile("(?m)^\\[(\\d+)\\] " + Pattern.quote(DrugReferenceInjector.FINDING_PREFIX
				+ drug + ": ") + "(.*)$").matcher(prompt);
		List<Integer> numbers = new ArrayList<Integer>();
		while (line.find()) {
			if (line.group(2).toLowerCase(Locale.ROOT).contains("rifamp")) {
				numbers.add(Integer.valueOf(line.group(1)));
			}
		}
		assertEquals(1, numbers.size(), "precondition: one finding about " + drug + " naming Rifampicin, prompt was:\n"
				+ prompt);
		return numbers.get(0).intValue();
	}

	@Test
	public void theStandInRecordForHerScheduledOrderSaysItIsScheduledAndWhen() throws IOException {
		// The chart stood in for carries no record of either order, so the module appends one per order
		// (issue #118's reconciliation) — and it called the scheduled one an "Active drug order".
		Recorder recorder = serviceAnswering("Amlodipine can be given.");
		recorder.service.search(patient, AMLODIPINE_QUESTION);

		assertTrue(recorder.prompt.contains("] Scheduled drug order: Rifampicin. Order status: " + STARTS + ".\n"),
				"the stand-in says what the order is and when it starts, prompt was:\n" + recorder.prompt);
		assertFalse(recorder.prompt.contains("Active drug order: Rifampicin"),
				"and never that it is an active drug order, prompt was:\n" + recorder.prompt);
		assertTrue(recorder.prompt.contains("] Active drug order: Nevirapine.\n"),
				"the started order beside it is unchanged, prompt was:\n" + recorder.prompt);
	}

	@Test
	public void aScheduledPartnerTheAnswerLeftUnnamedIsNamedByTheModuleAsScheduled() throws IOException {
		// ADR Decision 100's completion names the orders of a cited finding the prose left out, in the
		// module's own words — which said "active order Rifampicin" of a drug she has not started.
		int finding = rifampicinFindingAbout(prompt(), "Amlodipine");
		String modelAnswer = "No — Amlodipine should not be given: it has a Major interaction [" + finding + "].";

		ChartAnswer answer = serviceAnswering(modelAnswer).service.search(patient, AMLODIPINE_QUESTION);

		assertEquals(modelAnswer + " Also covered by those findings and not named above: scheduled order "
				+ "Rifampicin (rifampin) (" + STARTS + ").", answer.getAnswer(),
				"the module names the partner as a scheduled order, with its date");
	}

	@Test
	public void anAllergyQuestionStatesHerScheduledOrderWithTheDateItStarts() throws IOException {
		// ADR Decision 124 states, in an allergy answer, the orders of hers a recorded allergy conflicts
		// with, as "Currently prescribed: <display>". Her Rifampicin is prescribed and has not started, so
		// the statement says when it starts rather than reading as a drug she is taking.
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Rifampicin");
		String modelAnswer = "Yes — the patient has a recorded allergy to Rifampicin [1].";

		ChartAnswer answer = serviceAnswering(modelAnswer).service.search(patient, "any allergies?");

		// The placeholder concept the free-text allergen needs (88) is her aspirin's, so the answer states her
		// aspirin order as well; this case is about the Rifampicin statement.
		assertTrue(answer.getAnswer().startsWith(modelAnswer + " "), answer.getAnswer());
		assertTrue(answer.getAnswer().contains(" Currently prescribed: Rifampicin (" + STARTS + "). The patient has a "
				+ "recorded allergy to Rifampicin (rifampin)."), "the order is stated with its start date: " + answer.getAnswer());
	}

	@Test
	public void theModulesOwnAnswerNeverSaysSheIsAlreadyTakingHerScheduledOrder() throws IOException {
		// Review round 2 of PR #559: a module-composed answer (ADR Decision 113) follows a contraindication
		// about her own medication with the referent "This finding is about a medication this patient is
		// already taking." — said of the Rifampicin she has not started, one sentence after its chip says
		// so. The started order's line beside it is the other value, and keeps the referent.
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Rifampicin");
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");

		ChartAnswer answer = serviceAnswering("The model was called.").service.search(patient,
				"Are there any drug interactions among her current medications?");

		List<String> lines = Arrays.asList(answer.getAnswer().split("\n"));
		assertTrue(lines.stream().anyMatch(l -> l.matches(Pattern.quote("The patient has a recorded allergy to "
				+ "Rifampicin (rifampin). Her order for Rifampicin (rifampin) has not started: it is " + STARTS
				+ ". No severity is rated for this finding.") + " \\[\\d+\\]")),
				"the scheduled order's line says it has not started and no more: " + answer.getAnswer());
		assertTrue(lines.stream().anyMatch(l -> l.matches(Pattern.quote("The patient has a recorded allergy to "
				+ "Acetylsalicylic acid (aspirin). No severity is rated for this finding. This finding is about a "
				+ "medication this patient is already taking.") + " \\[\\d+\\]")),
				"the started order's line keeps its referent: " + answer.getAnswer());
	}

	@Test
	public void aDuplicateTherapyScreenNamesEachUnnamedOrderAsItStands() throws IOException {
		// Review round 4 of PR #559: issue #477's screening finding names her started Rifampicin 300mg and
		// her scheduled Rifampicin, and the sentence the module appends for an answer naming neither called
		// both "active order" — its wording read one date per finding, which only the interaction arm wrote.
		// The started order is the other value, and keeps "active order".
		assertDuplicateTherapyPartnersNamedAsTheyStand("Are there any drug interactions among her current medications?");
	}

	@Test
	public void aDuplicateTherapyFindingAboutTheDrugInPlayNamesEachUnnamedOrderAsItStands() throws IOException {
		// The same, for #477's finding about the drug the question puts in play.
		assertDuplicateTherapyPartnersNamedAsTheyStand("Can I give her rifampicin?");
	}

	private void assertDuplicateTherapyPartnersNamedAsTheyStand(String question) throws IOException {
		executeDataSet("StartedRifampicinOrderTestData.xml");
		Recorder reader = serviceAnswering("The model was called.");
		reader.service.search(patient, question);
		Matcher line = Pattern.compile("(?m)^\\[(\\d+)\\] " + Pattern.quote(DrugReferenceInjector.FINDING_PREFIX)
				+ ".*possible duplicate therapy.*$").matcher(reader.prompt);
		assertTrue(line.find(), "precondition: a duplicate-therapy finding, prompt was:\n" + reader.prompt);
		String modelAnswer = "Yes — there is a possible duplicate therapy [" + line.group(1) + "].";

		ChartAnswer answer = serviceAnswering(modelAnswer).service.search(patient, question);

		assertEquals(modelAnswer + " Also covered by those findings and not named above: scheduled order Rifampicin ("
				+ STARTS + ") and active order Rifampicin 300mg.", answer.getAnswer(),
				"each order the answer left unnamed is named as it stands, prompt was:\n" + reader.prompt);
	}

	/** The prompt the module builds for the amlodipine question, read once through the real pipeline. */
	private String prompt() throws IOException {
		Recorder recorder = serviceAnswering("Amlodipine can be given.");
		recorder.service.search(patient, AMLODIPINE_QUESTION);
		return recorder.prompt;
	}

	/** A recorder standing in for the model: answers {@code answer} and keeps the prompt's records. */
	private static final class Recorder extends LlmProvider {

		private final String answer;

		private String prompt;

		private TestableService service;

		private Recorder(String answer) {
			this.answer = answer;
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question,
				boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords) {
			prompt = numberedRecords;
			return new LlmResponse(answer, Collections.singletonList(Integer.valueOf(1)));
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords) {
			tokenConsumer.accept(answer);
			return search(numberedRecords, focusIndices, question, enumerateFindings, referenceRecords);
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

		private final RecordMapping[] records;

		private StubStrategy(RecordMapping... records) {
			this.records = records;
		}

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return DrugReferenceTestSupport.chartOf(records);
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
