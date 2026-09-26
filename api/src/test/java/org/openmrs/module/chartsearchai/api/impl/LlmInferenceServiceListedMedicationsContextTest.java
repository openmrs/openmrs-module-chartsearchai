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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
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
import org.openmrs.module.chartsearchai.api.ChartSearchService.CautionLedOverWithholding;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A question that lists the patient's medications is held to her chart, not to the list — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/515">#515</a>.
 *
 * <p><b>The defect.</b> <em>"The patient is currently on Lamivudine, Nevirapine, Stavudine, is it safe to
 * give Amlodipine?"</em>, asked of a patient whose chart holds none of the three and does hold a
 * rifampicin-containing order, came back from the default model as <em>"Amlodipine can be given, with one
 * caution: coadministration with nevirapine may decrease…"</em>. The chips beside it held Amlodipine ×
 * rifampin, a Major finding whose record the model had read ending <em>"This finding is a reason to
 * withhold it."</em>. Nothing in the response said the chart holds no order for the listed drugs, and no
 * key compared the lead with that finding.
 *
 * <p><b>Everything but the model is real</b>: patient 7 of the standard dataset with the issue's
 * rifampicin-containing order added ({@value #RIFAMPICIN_ORDER}) beside her aspirin order 111, read
 * through the real {@code OrderService}; the real injector and validator over a verbatim slice of the
 * shipped knowledge base ({@value #SLICE}); and the real {@link LlmInferenceService#search} and
 * {@code searchStreaming}. The model is a recorder answering the issue's recorded words, and it keeps
 * the prompt it was handed, which is where a case reads the number of the finding it expects reported.
 */
public class LlmInferenceServiceListedMedicationsContextTest extends BaseModuleContextSensitiveTest {

	private static final String RIFAMPICIN_ORDER = "ListedMedicationsRifampicinOrderTestData.xml";

	private static final String SLICE = "chartsearchai-test/ddi-listed-medications-proposal.json";

	/** The evaluation's own question for the issue's first row, verbatim. */
	private static final String AMLODIPINE_QUESTION = "The patient is currently on Lamivudine, Nevirapine, "
			+ "Stavudine, is it safe to give Amlodipine?";

	private static final String FLUCONAZOLE_QUESTION = "The patient is currently on Lamivudine, Nevirapine, "
			+ "Stavudine, is it safe to give Fluconazole?";

	/** The issue's recorded E4B lead for the first row. */
	private static final String AMLODIPINE_CAUTION_LEAD = "Amlodipine can be given, with one caution: "
			+ "coadministration with nevirapine may decrease the plasma concentrations of amlodipine.";

	private static final String NONE_OF_THE_LIST =
			" The chart holds no active order for Lamivudine, Nevirapine or Stavudine.";

	private Patient patient;

	@BeforeEach
	public void setUp() {
		executeDataSet(RIFAMPICIN_ORDER);
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		patient = Context.getPatientService().getPatient(7);
	}

	private static Recorder serviceAnswering(String modelAnswer, RecordMapping... chartRecords) throws IOException {
		return serviceAnswering(DrugReferenceTestSupport.ddiFixtureService(SLICE), modelAnswer, chartRecords);
	}

	private static Recorder serviceAnswering(DrugReferenceService references, String modelAnswer,
			RecordMapping... chartRecords) {
		Recorder recorder = new Recorder(modelAnswer);
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy(chartRecords));
		service.setLlmProvider(recorder);
		// One service behind both, as production autowires it.
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(references));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(references));
		recorder.service = service;
		return recorder;
	}

	private static RecordMapping obs() {
		return DrugReferenceTestSupport.obsRecord(1, "BP 120/80");
	}

	/**
	 * The number the prompt gave the one withholding finding about {@code drug} whose line names
	 * {@code partner}, read off the prompt the model was handed — the citation a client would follow.
	 */
	private static int findingNumber(String prompt, String drug, String partner) {
		return findingNumber(prompt, drug, partner, DrugReferenceInjector.STRENGTH_WITHHOLD);
	}

	/**
	 * The number the prompt gave the one finding about {@code drug} whose line names {@code partner} and
	 * ends in {@code clause} — one of the withholding-class clauses, which is what the case asserts the
	 * record states.
	 */
	private static int findingNumber(String prompt, String drug, String partner, String clause) {
		Matcher line = Pattern.compile("(?m)^\\[(\\d+)\\] " + Pattern.quote(DrugReferenceInjector.FINDING_PREFIX
				+ drug + ": ") + "(.*)$").matcher(prompt);
		List<Integer> numbers = new ArrayList<Integer>();
		while (line.find()) {
			String body = line.group(2);
			if (body.toLowerCase().contains(partner) && body.endsWith(clause)) {
				numbers.add(Integer.valueOf(line.group(1)));
			}
		}
		assertEquals(1, numbers.size(), "precondition: the prompt carries exactly one finding about " + drug
				+ " naming " + partner + " ending \"" + clause + "\", prompt was:\n" + prompt);
		return numbers.get(0).intValue();
	}

	private static void assertAChip(ChartAnswer answer, String drug, String partner, String severity) {
		for (SafetyWarning chip : answer.getSafetyWarnings()) {
			if (drug.equals(chip.getDrug()) && severity.equals(chip.getSeverity())
					&& chip.getDetail().toLowerCase().contains(partner)) {
				return;
			}
		}
		throw new AssertionError("precondition: a " + severity + " chip about " + drug + " naming " + partner
				+ " stands beside the answer, chips were: " + answer.getSafetyWarnings());
	}

	private static void assertReported(List<CautionLedOverWithholding> reported, int... citations) {
		assertNotNull(reported, "the check ran, so it states a measurement");
		List<String> got = new ArrayList<String>();
		for (CautionLedOverWithholding finding : reported) {
			got.add(finding.getCitation() + ":" + finding.getRating());
		}
		List<String> want = new ArrayList<String>();
		for (int citation : citations) {
			want.add(citation + ":Major");
		}
		assertEquals(want, got, "exactly the withholding findings about the drug the lead gives, each with its "
				+ "rating, and nothing else");
	}

	@Test
	public void search_aCautionLeadBesideAWithholdingMajorAboutItIsReportedAndTheListIsHeldToTheChart()
			throws IOException {
		Recorder recorder = serviceAnswering(AMLODIPINE_CAUTION_LEAD, obs());
		ChartAnswer answer = recorder.service.search(patient, AMLODIPINE_QUESTION);

		assertAChip(answer, "Amlodipine", "rifamp", "Major");
		assertReported(answer.getCautionLedOverWithholding(),
				findingNumber(recorder.prompt, "Amlodipine", "rifamp"));
		assertEquals(AMLODIPINE_CAUTION_LEAD + NONE_OF_THE_LIST, answer.getAnswer(),
				"the module states what the chart holds of the list, and the verdict the model wrote is untouched");
	}

	@Test
	public void searchStreaming_theEarlyDoneCarriesTheSameReportAndTheFinalAnswerIsCompleted() throws IOException {
		Recorder recorder = serviceAnswering(AMLODIPINE_CAUTION_LEAD, obs());
		final List<ChartAnswer> early = new ArrayList<ChartAnswer>();
		ChartAnswer answer = recorder.service.searchStreaming(patient, AMLODIPINE_QUESTION, token -> { },
				reasoning -> { }, citations -> { }, early::add);

		assertEquals(1, early.size(), "precondition: the early done fired");
		assertReported(answer.getCautionLedOverWithholding(),
				findingNumber(recorder.prompt, "Amlodipine", "rifamp"));
		assertEquals(answer.getCautionLedOverWithholding(), early.get(0).getCautionLedOverWithholding(),
				"resolved before the early done, so the early done carries the same report as the final answer");
		assertEquals(AMLODIPINE_CAUTION_LEAD + NONE_OF_THE_LIST, answer.getAnswer(),
				"the streaming path completes the final answer too");
		assertEquals(AMLODIPINE_CAUTION_LEAD + NONE_OF_THE_LIST, early.get(0).getAnswer(),
				"and the early done, which is the answer a streaming client is handed first: the chart stated the "
						+ "list's drugs before the model was asked");
	}

	/** The issue's second row: fluconazole's own Major against her rifampicin order is reported. */
	@Test
	public void theFluconazoleRowReportsItsMajorAgainstHerRifampicinOrder() throws IOException {
		String lead = "Fluconazole can be given, with two cautions: coadministration with nevirapine may increase "
				+ "nevirapine levels, and with amlodipine may increase amlodipine levels.";
		Recorder recorder = serviceAnswering(lead, obs());
		ChartAnswer answer = recorder.service.search(patient, FLUCONAZOLE_QUESTION);

		assertAChip(answer, "Fluconazole", "rifamp", "Major");
		assertReported(answer.getCautionLedOverWithholding(),
				findingNumber(recorder.prompt, "Fluconazole", "rifamp"));
		assertEquals(lead + NONE_OF_THE_LIST, answer.getAnswer());
	}

	/**
	 * The lead is matched to the findings about the drug IT gives, not the drug the question asked:
	 * a caution lead on nevirapine reports nevirapine's Major against her rifampicin order and none of
	 * amlodipine's.
	 */
	@Test
	public void theFindingsReportedAreThoseAboutTheDrugTheLeadGives() throws IOException {
		String lead = "Nevirapine can be given, with one caution: it may decrease amlodipine levels.";
		Recorder recorder = serviceAnswering(lead, obs());
		ChartAnswer answer = recorder.service.search(patient, AMLODIPINE_QUESTION);

		assertReported(answer.getCautionLedOverWithholding(),
				findingNumber(recorder.prompt, "Nevirapine", "rifamp"));
	}

	/**
	 * The lead is read over the whole answer, as {@code caution_led} reads it: a line break, or a period
	 * inside the bracket after the name, does not end the lead before its caution.
	 */
	@Test
	public void aLeadRunningPastALineBreakOrABracketedPeriodIsStillRead() throws IOException {
		for (String lead : new String[] { "Amlodipine can be given\nwith one caution: it may interact with nevirapine.",
				"Amlodipine (approx. 5 mg) can be given, with one caution: it may interact with nevirapine." }) {
			Recorder recorder = serviceAnswering(lead, obs());
			ChartAnswer answer = recorder.service.search(patient, AMLODIPINE_QUESTION);

			assertReported(answer.getCautionLedOverWithholding(),
					findingNumber(recorder.prompt, "Amlodipine", "rifamp"));
		}
	}

	@Test
	public void aRefusalLeadReportsNone() throws IOException {
		String refusal = "No — Amlodipine should not be given: it interacts with active order rifampin, a Major "
				+ "problem.";
		ChartAnswer answer = serviceAnswering(refusal, obs()).service.search(patient, AMLODIPINE_QUESTION);

		assertAChip(answer, "Amlodipine", "rifamp", "Major");
		assertReported(answer.getCautionLedOverWithholding());
	}

	/**
	 * The shape issue #513 item 2 recorded on E2B — a "No" before a permission — is not read as a caution
	 * lead: the anchor admits nothing but name material before the drug, so this check is silent on it
	 * even beside a withholding Major about that drug. Pinned so that silence is read as the residue it
	 * is (ADR Decision 119), never as a certificate.
	 */
	@Test
	public void aNoBeforeAPermissionIsNotReadAsACautionLead() throws IOException {
		String garbled = "No — Amlodipine can be given, but it is a caution to note regarding interactions with "
				+ "Nevirapine.";
		ChartAnswer answer = serviceAnswering(garbled, obs()).service.search(patient, AMLODIPINE_QUESTION);

		assertAChip(answer, "Amlodipine", "rifamp", "Major");
		assertReported(answer.getCautionLedOverWithholding());
	}

	/**
	 * The lead class is the caution lead, as {@code score_probe_safety.py}'s {@code caution_led} reads
	 * it: a bare permission naming no caution is not one, and this check is silent on it. Pinned for
	 * the reason the case above is.
	 */
	@Test
	public void aPermissionNamingNoCautionIsNotReadAsACautionLead() throws IOException {
		ChartAnswer answer = serviceAnswering("Amlodipine can be given.", obs()).service.search(patient,
				AMLODIPINE_QUESTION);

		assertAChip(answer, "Amlodipine", "rifamp", "Major");
		assertReported(answer.getCautionLedOverWithholding());
	}

	@Test
	public void aQuestionListingNothingIsNotCompleted() throws IOException {
		Recorder recorder = serviceAnswering(AMLODIPINE_CAUTION_LEAD, obs());
		ChartAnswer answer = recorder.service.search(patient, "Is it safe to give Amlodipine?");

		assertReported(answer.getCautionLedOverWithholding(),
				findingNumber(recorder.prompt, "Amlodipine", "rifamp"));
		assertEquals(AMLODIPINE_CAUTION_LEAD, answer.getAnswer(), "the question listed nothing, so nothing is stated");
	}

	/** A listed drug an active order of hers resolves to is on her chart, and is not named. */
	@Test
	public void aListedDrugSheHoldsAnActiveOrderForIsNotNamed() throws IOException {
		ChartAnswer answer = serviceAnswering(AMLODIPINE_CAUTION_LEAD, obs()).service.search(patient,
				"The patient is currently on Rifampicin, Nevirapine, Stavudine, is it safe to give Amlodipine?");

		assertEquals(AMLODIPINE_CAUTION_LEAD + " The chart holds no active order for Nevirapine or Stavudine.",
				answer.getAnswer());
	}

	/**
	 * A listed drug a drug-order record of the chart names — here one no longer in force, which is issue
	 * #472's referent — is not named as one the chart holds no order for.
	 */
	@Test
	public void aListedDrugADrugOrderRecordNamesIsNotNamed() throws IOException {
		ChartAnswer answer = serviceAnswering(AMLODIPINE_CAUTION_LEAD, obs(),
			DrugReferenceTestSupport.drugOrderRecord(2, "Stavudine 30mg", Boolean.FALSE, null)).service.search(
				patient, AMLODIPINE_QUESTION);

		assertEquals(AMLODIPINE_CAUTION_LEAD + " The chart holds no active order for Lamivudine or Nevirapine.",
				answer.getAnswer());
	}

	/**
	 * A list question whose drugs raise no finding and inject no record still gets the sentence: the
	 * injector rebuilds the chart to carry it rather than returning the chart it was handed. Patient 6
	 * holds no active order, and every pair among these three drugs is rated Unknown, below the floor.
	 */
	@Test
	public void aListQuestionThatResolvesNothingElseIsStillHeldToTheChart() throws IOException {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_INJECT_FROM_QUERY, "false");
		String answer = "Stavudine can be given.";
		Recorder recorder = serviceAnswering(answer, obs());
		ChartAnswer completed = recorder.service.search(Context.getPatientService().getPatient(6),
				"The patient is currently on Lamivudine, Nevirapine, is it safe to give Stavudine?");

		assertTrue(!recorder.prompt.contains(DrugReferenceInjector.FINDING_PREFIX),
				"precondition: the prompt carries no finding, prompt was:\n" + recorder.prompt);
		assertEquals(answer + " The chart holds no active order for Lamivudine or Nevirapine.",
				completed.getAnswer());
	}

	/**
	 * An active order the reference data cannot resolve may be a listed drug under a name the data lacks,
	 * so nothing is stated. The standard excerpt carries lisinopril, metformin and fluconazole and no
	 * rifampicin, so patient 7's rifampicin order resolves to nothing; patient 6, holding no order, is
	 * the control.
	 */
	@Test
	public void anActiveOrderTheDataCannotResolveStatesNothing() {
		String question = "The patient is currently on Lisinopril, Metformin, is it safe to give Fluconazole?";
		String answer = "Fluconazole can be given.";
		DrugReferenceService excerpt = DrugReferenceTestSupport.ddinterServiceWithGroups();

		assertEquals(answer + " The chart holds no active order for Lisinopril or Metformin.",
				serviceAnswering(excerpt, answer, obs()).service.search(Context.getPatientService().getPatient(6),
						question).getAnswer(), "control: with every order resolved the sentence is stated");
		assertEquals(answer, serviceAnswering(excerpt, answer, obs()).service.search(patient, question).getAnswer(),
				"her rifampicin order resolves to nothing, so the module cannot say she is on none of them");
	}

	/** Where the module wrote the answer there is no model lead to read, and the key states no measurement. */
	@Test
	public void anAnswerTheModuleWroteStatesNoMeasurement() throws IOException {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		ChartAnswer answer = serviceAnswering(AMLODIPINE_CAUTION_LEAD, obs()).service.search(patient,
				"Is it safe to give Amlodipine?");

		assertTrue(answer.isAnsweredByTheModule(), "precondition: the module answered from its findings");
		assertNull(answer.getCautionLedOverWithholding());
	}

	/**
	 * {@code want}, each {@code "citation:rating"}, is exactly what the key reports, in any order — for a case
	 * whose findings are not all rated Major, which {@link #assertReported} assumes.
	 */
	private static void assertReportedExactly(List<CautionLedOverWithholding> reported, String... want) {
		assertNotNull(reported, "the check ran, so it states a measurement");
		List<String> got = new ArrayList<String>();
		for (CautionLedOverWithholding finding : reported) {
			got.add(finding.getCitation() + ":" + finding.getRating());
		}
		List<String> expected = new ArrayList<String>();
		Collections.addAll(expected, want);
		Collections.sort(expected);
		Collections.sort(got);
		assertEquals(expected, got, "exactly these withholding findings about the drug the lead gives, each with "
				+ "the rating its record states, and nothing else");
	}

	/**
	 * A CONTRAINDICATION withholds without a rating — its clause is withholding-class and never asks
	 * {@code licensesWithholding} — and it is reported beside a caution lead on its drug as the interaction
	 * is. Her recorded allergy to amlodipine itself, recorded as free text; her rifampicin order's Major is
	 * the finding the other cases report. The key's population is the finding the record ENDS in, so a stamp
	 * re-derived from an interaction's rating would read this record as nothing.
	 */
	@Test
	public void aContraindicationBesideACautionLeadOnItsDrugIsReported() throws IOException {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Amlodipine");
		Recorder recorder = serviceAnswering(AMLODIPINE_CAUTION_LEAD, obs());
		ChartAnswer answer = recorder.service.search(patient, "Is it safe to give Amlodipine?");

		int allergy = findingNumber(recorder.prompt, "Amlodipine", "allergy");
		int rifampicin = findingNumber(recorder.prompt, "Amlodipine", "rifamp");
		assertReportedExactly(answer.getCautionLedOverWithholding(), allergy + ":null",
				rifampicin + ":Major");
	}

	/**
	 * A FOLDED finding withholds on a rating that says otherwise — methylphenidate's DDInter row against her
	 * modafinil order is rated Minor, and the drug-in-play arm folds the two drugs' shared {@code N06BA}
	 * class onto it ({@code SafetyWarning.carriesUnratedRelationship}) — so it is reported, with the Minor its
	 * record states. A stamp read off the rating would report nothing here.
	 */
	@Test
	public void aFoldedFindingWithholdingOnAMinorRatingIsReported() throws IOException {
		executeDataSet("AnswerFromFindingsModafinilOrderTestData.xml");
		String lead = "Methylphenidate can be given, with one caution: modafinil may interact with it.";
		Recorder recorder = serviceAnswering(DrugReferenceTestSupport.ddiFixtureService(
				"chartsearchai-test/ddi-folded-minor-class-pair-every-order-resolved.json"), lead, obs());
		ChartAnswer answer = recorder.service.search(patient, "Is it safe to give methylphenidate?");

		int folded = findingNumber(recorder.prompt, "Methylphenidate", "modafinil");
		assertTrue(recorder.prompt.contains("N06BA"), "precondition: the class sentence is folded onto the Minor "
				+ "row, prompt was:\n" + recorder.prompt);
		assertReportedExactly(answer.getCautionLedOverWithholding(), folded + ":Minor");
	}

	/**
	 * An unrated AUTHORED rule withholds (an unrated rule is not a low-rated one) and is reported with no
	 * rating: paracetamol's curated rule against her warfarin order carries no severity.
	 */
	@Test
	public void anUnratedAuthoredRuleIsReportedWithNoRating() throws IOException {
		executeDataSet("AnswerFromFindingsWarfarinOrderTestData.xml");
		String lead = "Paracetamol can be given, with one caution: it may potentiate warfarin.";
		Recorder recorder = serviceAnswering(DrugReferenceTestSupport.curatedFixtureService(
				"chartsearchai-test/drug-reference-answer-from-findings-unrated-rule.json"), lead, obs());
		ChartAnswer answer = recorder.service.search(patient, "Can I give her paracetamol?");

		assertReportedExactly(answer.getCautionLedOverWithholding(),
				findingNumber(recorder.prompt, "Paracetamol", "warfarin") + ":null");
	}

	/**
	 * A question-pair finding is about BOTH drugs of its pair, so it is reported whichever of the two the
	 * lead gives. The arm names one of them the finding's subject by the dataset's own order, not the
	 * question's — Rifampicin here, listed as current or proposed alike — so reading the subject alone made
	 * the report depend on which drug the question happened to list. Patient 6 holds no active order, so the
	 * Major pair is the question's own and no order-driven arm takes it.
	 */
	@Test
	public void aQuestionPairMajorIsReportedWhicheverOfItsTwoDrugsTheLeadGives() throws IOException {
		Patient noOrders = Context.getPatientService().getPatient(6);
		for (String[] cell : new String[][] {
				{ "The patient is currently on Rifampicin, is it safe to give Amlodipine?", "Amlodipine" },
				{ "The patient is currently on Amlodipine, is it safe to give Rifampicin?", "Rifampicin" } }) {
			String lead = cell[1] + " can be given, with one caution: it may interact with the other drug.";
			Recorder recorder = serviceAnswering(lead, obs());
			ChartAnswer answer = recorder.service.search(noOrders, cell[0]);

			assertReported(answer.getCautionLedOverWithholding(),
					findingNumber(recorder.prompt, "Rifampicin (rifampin)",
							"amlodipine, also named in the question"));
		}
	}

	/**
	 * A contraindication about a medication she ALREADY TAKES states the current-medication clause — a
	 * reason to change it rather than to withhold it — and is reported beside a caution lead on its drug as
	 * the proposal clause is: README and ADR Decision 119 promise the key covers it. Her recorded allergy to
	 * aspirin, recorded as free text, against her aspirin order 111; the question asks about her current
	 * medications, which puts the order-driven arm's chip in subject matter.
	 */
	@Test
	public void aContraindicationAboutAMedicationSheAlreadyTakesBesideACautionLeadOnItIsReported()
			throws IOException {
		DrugReferenceTestSupport.recordFreeTextAllergy(patient, 88, "Aspirin");
		String lead = "Aspirin can be given, with one caution: it may interact with her other medications.";
		Recorder recorder = serviceAnswering(lead, obs());
		ChartAnswer answer = recorder.service.search(patient,
				"Are there any drug interactions with her current medications?");

		assertReportedExactly(answer.getCautionLedOverWithholding(), findingNumber(recorder.prompt,
				"Acetylsalicylic acid (aspirin)", "allergy", DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION)
				+ ":null");
	}

	/**
	 * A finding about a drug the chart holds only as an ENDED order states the ended-order clause (issue
	 * #472) and is reported beside a caution lead on its drug as the proposal clause is. The question lists
	 * amlodipine as current and proposes nothing, and the chart carries an amlodipine order no longer in
	 * force, so amlodipine × her rifampicin order is about an ended order.
	 */
	@Test
	public void aWithholdingFindingAboutAnEndedOrderBesideACautionLeadOnItsDrugIsReported() throws IOException {
		String lead = "Amlodipine can be given, with one caution: it may interact with rifampicin.";
		Recorder recorder = serviceAnswering(lead, obs(),
			DrugReferenceTestSupport.drugOrderRecord(2, "Amlodipine 5mg", Boolean.FALSE, null));
		ChartAnswer answer = recorder.service.search(patient,
				"Her current medications are rifampicin and amlodipine. Any interactions?");

		assertReported(answer.getCautionLedOverWithholding(), findingNumber(recorder.prompt, "Amlodipine", "rifamp",
				DrugReferenceInjector.STRENGTH_WITHHOLD_ENDED_ORDER));
	}

	/**
	 * The drug-in-play arm's DUPLICATE-THERAPY finding (issue #477) is about the drug in play, states the
	 * withholding class, and is reported beside a caution lead on that drug as the arm's rule chips are. Two
	 * of her active orders carry rifampicin ({@code ListedMedicationsSecondRifampicinOrderTestData.xml}), so
	 * a question proposing rifampicin raises it — and, rifampicin being hers, in the current-medication
	 * column the arm states for every finding about it (issue #402, ADR Decision 121). It is unrated, so its
	 * record states no rating.
	 */
	@Test
	public void aDuplicateTherapyFindingAboutTheDrugInPlayBesideACautionLeadOnItIsReported() throws IOException {
		executeDataSet("ListedMedicationsSecondRifampicinOrderTestData.xml");
		String lead = "Rifampicin can be given, with one caution: monitor liver function.";
		Recorder recorder = serviceAnswering(lead, obs());
		ChartAnswer answer = recorder.service.search(patient, "Is it safe to give Rifampicin?");

		assertReportedExactly(answer.getCautionLedOverWithholding(),
				findingNumber(recorder.prompt, "Rifampicin (rifampin)", "possible duplicate therapy",
					DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION) + ":null");
	}

	/**
	 * The finding that two of her orders share substances (issue #477) is about EVERY substance it names,
	 * and states the current-medication clause, so it is reported beside a caution lead on any of them. Her
	 * two {@code Lamivudine / stavudine} orders ({@code ListedMedicationsLamivudineStavudineOrdersTestData.xml})
	 * share lamivudine and stavudine; the question names lamivudine alone, so the finding is stated, and the
	 * lead gives stavudine, which no other finding is about.
	 */
	@Test
	public void aFindingThatHerOrdersShareASubstanceIsReportedBesideACautionLeadOnAnyOfItsSubstances()
			throws IOException {
		executeDataSet("ListedMedicationsLamivudineStavudineOrdersTestData.xml");
		String lead = "Stavudine can be given, with one caution: monitor for peripheral neuropathy.";
		Recorder recorder = serviceAnswering(lead, obs());
		ChartAnswer answer = recorder.service.search(patient, "Is it safe to give Lamivudine?");

		assertReportedExactly(answer.getCautionLedOverWithholding(),
				findingNumber(recorder.prompt, "Lamivudine and Stavudine", "possible duplicate therapy",
					DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION) + ":null");
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
