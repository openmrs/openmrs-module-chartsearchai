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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.ChartReadStatus;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * What a client shows under "Show details": the answer without the lines its summary already states (ADR Decision 560).
 * On the public demo's data the whole answer was collapsed under any summary the answer does not open with, so 3,735 of
 * 7,113 summarised answers said under the toggle what the sentence above it had just said. A line leaves the details only
 * where the summary states it whole — every word and every marker, on the words it cited — and nothing else does: no
 * heading's items, no line the sentence splitter cuts, no line whose marker cites one of several things it names.
 */
public class AnswerDetailsContextTest extends BaseModuleContextSensitiveTest {

	private static final String PROPOSAL = "Can I give her ibuprofen?";


	@BeforeEach
	public void setUp() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		answerFromFindings(true);
	}

	/**
	 * A drug the data relates to none of her orders, after a list naming one she does not hold, is two sentences the
	 * module composes (ADR Decision 557) and the line it appends. The summary states both sentences, the first's marker
	 * with it on the words it cited, and under the toggle is the appended line alone — not the sentences again.
	 */
	@Test
	public void aSentenceTheSummaryStatesWholeIsNotRepeatedUnderIt() {
		String question = "The patient is currently on Amoxicillin, is it safe to give ascorbic acid?";
		ChartAnswer answer = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(question, "Lamivudine"), "unused")
				.search(aPatient(), question);
		String[] lines = answer.getAnswer().split("\n");
		assertEquals(3, lines.length, "precondition: two sentences and the line appended — " + answer.getAnswer());
		Matcher sentence = Pattern.compile("(.*)\\. (\\[\\d+\\])").matcher(lines[0]);
		assertTrue(sentence.matches(), "precondition: one sentence, its marker after its full stop — " + lines[0]);
		assertTrue(lines[1].startsWith("The check of Ascorbic acid against Amoxicillin"), "precondition — " + lines[1]);

		assertEquals(sentence.group(1) + " " + sentence.group(2) + "; t" + lines[1].substring(1), answer.getAnswerSummary(),
			"the summary carries the sentence's marker on the sentence it cited");
		assertEquals(lines[2], answer.getAnswerDetails(), "the sentences are not said twice");
	}

	/**
	 * A one-line answer the module appends to — the dose said not checked (ADR Decision 322) — is more than one line, so it
	 * has one sentence a client shows (ADR Decision 519): its own line, the appended lines under it. "Can I give him
	 * ibuprofen?" for a patient on no medication is one sentence since the line about her conditions left the answer (ADR
	 * Decision 562); stating a dose, it was shown whole.
	 */
	/**
	 * A drug's name in brackets — "Acetylsalicylic acid (aspirin)" — is part of the line the summary states, so the line
	 * leaves the details (ADR Decision 575): on the demo replica "Can I give her fluconazole with her aspirin?" showed
	 * "Fluconazole and Acetylsalicylic acid, asked about together; …" and, first under "Show details", "Fluconazole and
	 * Acetylsalicylic acid (aspirin), asked about together; …" — the same line again.
	 */
	@Test
	public void aLineWhoseNameCarriesABracketedNameIsNotRepeatedUnderTheSummary() {
		String question = "Can I give her fluconazole and aspirin?";
		ChartAnswer answer = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(question, "Nevirapine"), "unused")
				.search(aPatient(), question);
		String first = "Fluconazole and Acetylsalicylic acid (aspirin), asked about together; neither is one of this patient's "
				+ "active medications.";
		assertTrue(answer.getAnswer().startsWith(first + "\n"), "precondition: the line — " + answer.getAnswer());

		assertTrue(answer.getAnswerSummary().startsWith(first.substring(0, first.length() - 1) + "; "),
			"the summary states the line, its bracketed name with it: " + answer.getAnswerSummary());
		assertFalse(answer.getAnswerDetails().contains(first), "not again under it: " + answer.getAnswerDetails());
		// Under it, what the data lists between the two (ADR Decision 576), then each drug.
		assertTrue(answer.getAnswerDetails().startsWith("Between them:\nThe interaction data lists Fluconazole against "
				+ "Acetylsalicylic acid (aspirin), "), answer.getAnswerDetails());
		assertTrue(answer.getAnswerDetails().contains("\nFor Fluconazole:\n"), answer.getAnswerDetails());
	}

	@Test
	public void aOneLineAnswerTheModuleAppendsToIsSummarisedByItsOwnLine() {
		ChartAnswer answer = realService(new DrugReferenceService()).search(patient(6), "Can I give her ibuprofen 400mg?");
		String[] lines = answer.getAnswer().split("\n");
		assertEquals(2, lines.length, "precondition: a sentence and the dose line — " + answer.getAnswer());
		assertEquals(DoseStatement.NOT_CHECKED, lines[1], "precondition");

		assertEquals(lines[0], answer.getAnswerSummary());
		assertEquals(lines[1], answer.getAnswerDetails());
		assertTrue(answer.answerDetailsStateLimits(), "the dose line says what was not checked");
	}

	/** And with nothing appended, one sentence is shown whole: no summary, nothing under one. */
	@Test
	public void aOneLineAnswerIsShownWhole() {
		ChartAnswer answer = realService(new DrugReferenceService()).search(patient(6), PROPOSAL);
		assertFalse(answer.getAnswer().contains("\n"), "precondition: one sentence — " + answer.getAnswer());

		assertNull(answer.getAnswerSummary());
		assertNull(answer.getAnswerDetails());
	}

	/**
	 * "St. John's Wort" is two sentences to the module's sentence splitter, and the summary joins what it cuts with a
	 * semicolon, so the line is not stated whole and stays under the toggle — the only copy of it a clinician can read
	 * right. A list of names to protect was measured incomplete: the question's own spelling, a drug joined to another
	 * with a slash, a name ending in a full stop.
	 */
	@Test
	public void aLineTheSentenceSplitterCutsStaysUnderTheSummary() {
		String question = "The patient is currently on Efavirenz, is it safe to give St. John's wort?";
		ChartAnswer answer = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(question, "Lamivudine"), "unused")
				.search(aPatient(), question);
		String[] lines = answer.getAnswer().split("\n");
		assertTrue(lines[0].contains("St. John's Wort"), "precondition: the line names it — " + answer.getAnswer());
		assertNotNull(answer.getAnswerSummary(), "precondition: the answer has a summary");

		assertEquals(answer.getAnswer(), answer.getAnswerDetails(), "nothing it states whole, so nothing leaves");
	}

	/**
	 * A marker inside a sentence cites one of the several things that sentence names. Carried to the summary's end, the
	 * replica's "Can I stop her isoniazid / rifapentine?" read "… and Ethambutol and isoniazid [107] [73]", both markers
	 * on the order neither cited. So the line stays under the toggle, its markers where they were.
	 */
	@Test
	public void aLineWhoseMarkerCitesOneOfSeveralThingsStaysUnderTheSummary() {
		String question = "Can I stop her isoniazid / rifapentine?";
		PatientChart chart = DrugReferenceTestSupport.injectedChartOverOrders(question,
			"Isoniazid / pyrazinamide / rifampin", "Rifampicin isoniazid pyrazinamide and ethambutol",
			"Ethambutol and isoniazid");
		ChartAnswer answer = serviceOver(chart, "unused").search(aPatient(), question);
		String[] lines = answer.getAnswer().split("\n");
		assertEquals(2, lines.length, "precondition — " + answer.getAnswer());
		assertTrue(lines[1].matches(".*\\[\\d+\\], .*"), "precondition: a marker mid-sentence — " + lines[1]);

		assertEquals(lines[1], answer.getAnswerDetails());
		assertFalse(answer.getAnswerSummary().contains("["), "no marker moved: " + answer.getAnswerSummary());
	}

	/**
	 * Two drugs asked about together: the summary states each heading's item, but a line under a heading stays with it, so
	 * no heading is shown with nothing under it. Only the line above the first heading leaves.
	 */
	@Test
	public void noHeadingLosesItsItems() {
		String question = "Can I give him ibuprofen and omeprazole?";
		ChartAnswer answer = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(question, "Lamivudine",
			"Nevirapine", "Stavudine"), "unused").search(aPatient(), question);
		String whole = answer.getAnswer();
		assertTrue(whole.contains("\nFor Ibuprofen:\n") && whole.contains("\nFor Omeprazole:\n"), "precondition: " + whole);

		assertEquals(whole.substring(whole.indexOf('\n') + 1), answer.getAnswerDetails());
	}

	/**
	 * A line the module writes after the summary was chosen — the dose said not to have been checked (ADR Decision 322) —
	 * is under the toggle, and the label says so: the flag is read off the details a client shows, not off the answer
	 * before that line was added.
	 */
	@Test
	public void theLimitsFlagReadsTheDetailsAClientShows() {
		ChartAnswer answer = realService(DrugReferenceTestSupport.shippedServiceWithGroups()).search(patient(7),
			"Can I give her ibuprofen 400mg?");
		String whole = answer.getAnswer();
		assertTrue(whole.endsWith("\n" + DoseStatement.NOT_CHECKED), "precondition: " + whole);
		assertFalse(whole.contains("Not checked:"), "precondition: the dose line is the only limit — " + whole);

		assertEquals(whole.substring(whole.indexOf('\n') + 1), answer.getAnswerDetails());
		assertTrue(answer.answerDetailsStateLimits(), "the dose line says what was not checked");
	}

	/**
	 * A lead the summary folds its limit into stays under it whole (ADR Decision 517), and the summary's own line leaves.
	 */
	@Test
	public void aFoldedLeadStaysAndTheSummarysOwnLineLeaves() {
		ChartAnswer answer = realService(new DrugReferenceService()).search(patient(7),
			"What should I monitor with her current medications?");
		String[] lines = answer.getAnswer().split("\n");
		assertEquals(2, lines.length, "precondition — " + answer.getAnswer());
		assertTrue(lines[0].startsWith("This check does not say what to monitor:"), "precondition — " + lines[0]);
		assertEquals(lines[1], answer.getAnswerSummary(), "precondition");

		assertEquals(lines[0], answer.getAnswerDetails());
	}

	/**
	 * A lead the summary folds in — its limit as a bracket at the summary's end — leaves the details too (ADR Decision 577):
	 * on the demo replica "Which of her medications are high risk?" showed "… (this check does not rate a medication's
	 * risk)." and then, first under "Show details", "This check does not rate a medication's risk: it states what it found
	 * among the medications this patient is already taking."
	 */
	@Test
	public void aLeadTheSummaryFoldsInIsNotRepeatedUnderIt() {
		String question = "Which of her medications are high risk?";
		ChartAnswer answer = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(question, "Warfarin", "Aspirin"),
			"unused").search(aPatient(), question);
		String[] lines = answer.getAnswer().split("\n");
		assertTrue(lines[0].startsWith("This check does not rate a medication's risk:"), "precondition — " + answer.getAnswer());
		assertTrue(answer.getAnswerSummary().endsWith("(this check does not rate a medication's risk)."),
			"precondition: the summary carries its limit — " + answer.getAnswerSummary());

		assertFalse(answer.getAnswerDetails().contains("This check does not rate a medication's risk"),
			"not again under it: " + answer.getAnswerDetails());
		assertEquals(answer.getAnswer().substring(lines[0].length() + lines[1].length() + 2), answer.getAnswerDetails(),
			"the details are the findings under the lead and the bottom line");
	}

	/**
	 * A pair the question names both drugs of is named whole in the summary (ADR Decision 578): on the 8089 rig "Can I give
	 * her warfarin and ibuprofen together?" read "3 Major interactions — Warfarin with Acetylsalicylic acid (aspirin);
	 * Ibuprofen with Acetylsalicylic acid (aspirin); Warfarin." — the pair asked about cut to its first drug, its finding
	 * naming no order of hers.
	 */
	@Test
	public void aPairTheQuestionNamesIsNamedWholeInTheSummary() {
		String question = "Can I give her warfarin and ibuprofen together?";
		ChartAnswer answer = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(question, "Aspirin"), "unused")
				.search(aPatient(), question);
		assertTrue(answer.getAnswer().contains("Warfarin interacts with Ibuprofen, also named in the question — Major."),
			"precondition: the pair's finding — " + answer.getAnswer());

		assertTrue(answer.getAnswerSummary().contains("Warfarin with Ibuprofen"), "was: " + answer.getAnswerSummary());
		assertFalse(answer.getAnswerSummary().matches(".*; Warfarin\\.$"), "was: " + answer.getAnswerSummary());
	}

	/** A model's list carries a summary too (ADR Decision 528), so it publishes its details: never null beside one. */
	@Test
	public void aModelsListPublishesItsDetails() {
		answerFromFindings(false);
		String question = "What medications is she on?";
		String list = "The patient is on the following medications:\n- Lisinopril 10mg [1]\n- Metformin 500mg [1]\n"
				+ "- Warfarin 5mg [1]";
		ChartAnswer answer = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(question, "Lisinopril 10mg"), list)
				.search(aPatient(), question);
		assertFalse(answer.isAnsweredByTheModule(), "precondition: the model answered");
		assertEquals("The patient is on the following medications — 3 listed.", answer.getAnswerSummary(), "precondition");

		assertEquals(list, answer.getAnswerDetails(), "the answer does not open with the summary, so all of it is under it");
	}

	/**
	 * What the module appends to a model's answer — the drug said to be the one the question named otherwise (ADR Decision
	 * 277) — is under the toggle too, on the answer a streaming client sees first as on the final one: on the replica "Can I
	 * give him fluconazole with his klarizom?" lost the module's two lines about "klarizom" from the details.
	 */
	@Test
	public void aModelsDetailsCarryTheLinesTheModuleAppends() {
		answerFromFindings(false);
		String question = "Is it safe to start glibenclamide?";
		String list = "Glyburide interacts with:\n- Lisinopril 10mg [1]\n- Metformin 500mg [1]";
		PatientChart chart = DrugReferenceTestSupport.injectedChartOverOrders(question, "Lisinopril 10mg", "Metformin 500mg");
		List<ChartAnswer> early = new ArrayList<ChartAnswer>();
		ChartAnswer answer = serviceOver(chart, list).searchStreaming(aPatient(), question, text -> { }, reasoning -> { },
			citations -> { }, early::add);
		assertNotNull(answer.getAnswerSummary(), "precondition: a list, so a summary — " + answer.getAnswer());
		assertTrue(answer.getAnswer().startsWith(list) && answer.getAnswer().length() > list.length(),
			"precondition: the module appended to the model's words — " + answer.getAnswer());

		assertEquals(answer.getAnswer(), answer.getAnswerDetails(), "all of a model's answer is under its summary");
		assertEquals(1, early.size(), "precondition: one early answer");
		assertEquals(early.get(0).getAnswer(), early.get(0).getAnswerDetails());
		ChartAnswer plain = serviceOver(chart, list).search(aPatient(), question);
		assertEquals(plain.getAnswer(), plain.getAnswerDetails());
	}

	/** An answer with no summary has nothing under one. */
	@Test
	public void anAnswerWithNoSummaryHasNoDetails() {
		String question = "Can I give him St. John's wort?";
		ChartAnswer answer = serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(question), "unused")
				.search(aPatient(), question);
		assertNull(answer.getAnswerSummary(), "precondition: one sentence — " + answer.getAnswer());

		assertNull(answer.getAnswerDetails());
	}

	/** The early answer a streaming client shows first carries the same details as the final one. */
	@Test
	public void theEarlyAnswerCarriesTheSameDetails() {
		List<ChartAnswer> early = new ArrayList<ChartAnswer>();
		ChartAnswer answer = realService(new DrugReferenceService()).searchStreaming(patient(6),
			"Can I give her ibuprofen 400mg?", text -> { }, reasoning -> { }, citations -> { }, early::add);

		assertEquals(DoseStatement.NOT_CHECKED, answer.getAnswerDetails());
		assertEquals(1, early.size(), "precondition: one early answer");
		assertEquals(answer.getAnswerDetails(), early.get(0).getAnswerDetails());
		assertEquals(answer.answerDetailsStateLimits(), early.get(0).answerDetailsStateLimits());
	}

	/**
	 * A proposal after a list of her own medications is summarised as the drug asked alone is (ADR Decision 561). On the
	 * replica, Kamwara's <em>"The patient is currently on Lamivudine, Nevirapine, Stavudine, is it safe to give
	 * Amlodipine?"</em> showed <em>"Amlodipine: no Major interactions; 1 caution."</em> and, under the toggle, <em>"1
	 * interaction caution for Amlodipine:"</em> — the count twice — where <em>"Is it safe to give Amlodipine?"</em> showed
	 * <em>"1 interaction caution for Amlodipine — Moderate, with Nevirapine."</em> and its finding under it.
	 */
	@Test
	public void aProposalAfterAListOfHerMedicationsIsSummarisedAsTheDrugAskedAlone() {
		ChartAnswer listed = overKamwarasOrders(
			"The patient is currently on Lamivudine, Nevirapine, Stavudine, is it safe to give Amlodipine?");
		ChartAnswer alone = overKamwarasOrders("Is it safe to give Amlodipine?");
		assertTrue(listed.isAnsweredByTheModule() && alone.isAnsweredByTheModule(),
			"precondition: the module answers both — " + listed.getAnswer() + " / " + alone.getAnswer());
		assertEquals("1 interaction caution for Amlodipine — Moderate, with Nevirapine.", alone.getAnswerSummary(),
			"precondition: " + alone.getAnswer());

		// Each question numbers the finding's own record after what it injected, so a marker is compared by where it sits.
		assertEquals(markersUnnumbered(alone.getAnswer()), markersUnnumbered(listed.getAnswer()),
			"the list adds nothing the drug asked alone does not say");
		assertEquals(alone.getAnswerSummary(), listed.getAnswerSummary());
		assertEquals(markersUnnumbered(alone.getAnswerDetails()), markersUnnumbered(listed.getAnswerDetails()),
			"nothing the summary states is under it again");
		assertEquals(alone.answerDetailsStateLimits(), listed.answerDetailsStateLimits());
	}

	private static String markersUnnumbered(String text) {
		return ChartSearchAiUtils.INLINE_CITATION.matcher(text).replaceAll("[#]");
	}

	/** {@code question} over the replica's Kamwara (dd74dc61): active orders for lamivudine, nevirapine and stavudine. */
	private static ChartAnswer overKamwarasOrders(String question) {
		return serviceOver(DrugReferenceTestSupport.injectedChartOverOrders(question, "Lamivudine", "Nevirapine", "Stavudine"),
			"unused").search(aPatient(), question);
	}

	private static void answerFromFindings(boolean on) {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS,
			String.valueOf(on));
	}

	private static Patient patient(int id) {
		Patient patient = Context.getPatientService().getPatient(id);
		assertNotNull(patient, "precondition: the standard dataset's patient " + id);
		return patient;
	}

	/** A patient for a service over an injected chart, which reads nothing of the patient's own. */
	private static Patient aPatient() {
		Patient patient = new Patient();
		patient.setPatientId(1);
		patient.setUuid("uuid-1");
		return patient;
	}

	/** The real injector and validator over {@code reference}, reading the patient's chart from the database. */
	private static TestableService realService(DrugReferenceService reference) {
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(new StubProvider("unused"));
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(reference));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(reference));
		return service;
	}

	/** A service whose injector seam hands back {@code injected}, the real injector's chart over named orders. */
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
			public DrugSafetyValidator.SafetyCheckResult validateWithStatus(String answer, String question, Patient patient,
					List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
				return new DrugSafetyValidator.SafetyCheckResult(DrugSafetyValidator.STATUS_CHECKED,
						Collections.<SafetyWarning> emptyList());
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
