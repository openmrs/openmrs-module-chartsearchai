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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openmrs.DrugOrder;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A finding about a drug this patient's chart records only as an order no longer in force says so,
 * rather than reading as a proposal or as a current co-medication (issue #472).
 *
 * <p><b>The defect.</b> On a RefApp 3.7.1 standalone, a patient on Lamivudine and Nevirapine whose
 * Rifampicin order had been discontinued was asked about with <em>"Her current medications are
 * lamivudine, nevirapine and rifampicin. Any interactions?"</em> and <em>"Why was her rifampicin
 * stopped, and does it matter for her current medications?"</em>. Both raised a Major chip
 * "Rifampicin interacts with active order Nevirapine", and the answers read <em>"a reason to withhold
 * it"</em> and <em>"No — Rifampicin should not be given"</em>: the drug-in-play arm raises every
 * finding about a question-named drug as a PROPOSAL, and nothing in it read the chart's own in-force
 * stamp, which said the Rifampicin order was not in force.
 *
 * <p><b>What these cases drive.</b> The real {@code injectRecords} and the real {@code validate}, over
 * reference datasets the module loads (DDInter excerpts, and the curated seed for the allergy case), with the ended order's chart record carrying querystore's REAL rendered text
 * ({@link DrugReferenceTestSupport#querystoreRenderedText}) — so the premise that a stopped record's
 * text names its drug to {@code DrugReference.matchesText} is exercised against what querystore
 * writes, not against a literal typed here. Context-sensitive for that reason alone.
 *
 * <p>The clauses are LITERALS, as {@code CurrentMedicationFindingStrengthTest} keeps its own: the
 * prompt keys on these sentences, so a case comparing a constant to itself would stay green through a
 * reword that changed the call the model reads.
 */
public class EndedOrderFindingReferentTest extends BaseModuleContextSensitiveTest {

	/** Verbatim DDInter excerpt: Simvastatin × Clarithromycin is Major, Spironolactone × Salicylic
	 *  acid is Minor, so one fixture carries both strength classes. */
	private static final String FIXTURE = "chartsearchai-test/ddi-alias-drug-names.json";

	private static final String WITHHOLD_ENDED = "This finding is a reason against giving it should it "
			+ "be proposed again; this patient's chart records its order as no longer in force, not as "
			+ "a current medication.";

	private static final String CAUTION_ENDED = "This finding is a caution to weigh should it be "
			+ "proposed again; this patient's chart records its order as no longer in force, not as a "
			+ "current medication.";

	private static final String WITHHOLD = "This finding is a reason to withhold it.";

	private static final String CAUTION = "This finding is a caution to note, not a reason to withhold it.";

	private static final Date STOPPED = new Date(1767225600000L);

	/**
	 * A chart record for an order of {@code drugName}, carrying the text querystore's real serializer
	 * renders for such an order and the in-force stamp {@code orderActive} — {@code FALSE} for an order
	 * no longer in force, {@code TRUE} for one in force, {@code null} where the module could not say.
	 * The standard dataset's drug order, renamed in memory and never saved.
	 */
	private RecordMapping orderRecord(int index, String drugName, Boolean orderActive) {
		DrugOrder order = DrugReferenceTestSupport.standardDatasetDrugOrder();
		order.getDrug().setName(drugName);
		String text = DrugReferenceTestSupport.querystoreRenderedText(order);
		assertTrue(text.toLowerCase(Locale.ROOT).contains(drugName.toLowerCase(Locale.ROOT)),
				"precondition: querystore's rendered text for the order names its drug, or no case "
						+ "here is about a record that names it: " + text);
		return new RecordMapping(index, "drug_order", order.getUuid() + "-" + index, null, text, null, 0,
				orderActive, Boolean.FALSE.equals(orderActive) ? STOPPED : null, null, null, null, null);
	}

	private static PatientClinicalContext onlyOn(String activeDrug, String... allergies) {
		return DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set(activeDrug), null,
				allergies.length == 0 ? null : DrugReferenceTestSupport.set(allergies), null);
	}

	private static List<String> findings(DrugReferenceService service, PatientChart chart,
			PatientClinicalContext context, String question) {
		return DrugReferenceTestSupport.findingTexts(
				DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(chart, context, question));
	}

	private static String onlyFinding(DrugReferenceService service, PatientChart chart,
			PatientClinicalContext context, String question) {
		List<String> findings = findings(service, chart, context, question);
		assertEquals(1, findings.size(), "one pair is one citable record here: " + findings);
		return findings.get(0);
	}

	private static DrugReferenceService ddi() throws IOException {
		return DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
	}

	@Test
	public void aWithholdingFindingAboutADrugTheChartHoldsOnlyAsAnEndedOrderStatesTheEndedOrderCall()
			throws IOException {
		String finding = onlyFinding(ddi(),
			DrugReferenceTestSupport.chartOf(orderRecord(1, "Clarithromycin 500mg", Boolean.FALSE)),
			onlyOn("Simvastatin"), "Her current medications are simvastatin and clarithromycin. Any interactions?");

		assertTrue(finding.toLowerCase(Locale.ROOT).contains("major"),
				"precondition: this is the withholding-class pair: " + finding);
		assertTrue(finding.endsWith(WITHHOLD_ENDED),
				"the chart records clarithromycin only as an order no longer in force, so the finding "
						+ "states that referent and does not read as a proposal: " + finding);
		assertFalse(finding.contains(WITHHOLD),
				"and it must not also state the proposal call the answer turned into a refusal: " + finding);
	}

	@Test
	public void aCautionFindingAboutADrugTheChartHoldsOnlyAsAnEndedOrderStatesTheEndedOrderCaution()
			throws IOException {
		String finding = onlyFinding(ddi(),
			DrugReferenceTestSupport.chartOf(orderRecord(1, "Salicylic acid 300mg", Boolean.FALSE)),
			onlyOn("Spironolactone"), "Does salicylic acid interact with her medications?");

		assertTrue(finding.toLowerCase(Locale.ROOT).contains("minor"),
				"precondition: this is the caution-class pair: " + finding);
		assertTrue(finding.endsWith(CAUTION_ENDED), "the caution class takes the ended-order caution: " + finding);
		assertFalse(finding.contains(CAUTION), "and not the proposal caution beside it: " + finding);
	}

	@Test
	public void aRecordTheModuleCannotSayIsInForceOrNotLeavesTheProposalCall() throws IOException {
		String finding = onlyFinding(ddi(),
			DrugReferenceTestSupport.chartOf(orderRecord(1, "Clarithromycin 500mg", null)),
			onlyOn("Simvastatin"), "Her current medications are simvastatin and clarithromycin. Any interactions?");

		assertTrue(finding.endsWith(WITHHOLD),
				"a null stamp is 'the module cannot say', never 'not in force': " + finding);
	}

	@Test
	public void aChartWithNoRecordOfTheDrugLeavesTheProposalCall() throws IOException {
		String finding = onlyFinding(ddi(), DrugReferenceTestSupport.oneRecordChart(), onlyOn("Simvastatin"),
			"Her current medications are simvastatin and clarithromycin. Any interactions?");

		assertTrue(finding.endsWith(WITHHOLD), "nothing records the drug, so nothing changes: " + finding);
	}

	@Test
	public void aDrugTheChartAlsoRecordsAsAnOrderInForceIsNotStatedAsEnded() throws IOException {
		// The second guard: an order in force the reference data did not resolve still makes the drug
		// a current one, and the chart's own TRUE stamp is what says so.
		String finding = onlyFinding(ddi(),
			DrugReferenceTestSupport.chartOf(orderRecord(1, "Clarithromycin 500mg", Boolean.FALSE),
				orderRecord(2, "Clarithromycin 250mg", Boolean.TRUE)),
			onlyOn("Simvastatin"), "Her current medications are simvastatin and clarithromycin. Any interactions?");

		assertFalse(finding.contains(WITHHOLD_ENDED),
				"a chart record stamped in force names the drug, so the chart does not hold it only as "
						+ "an ended order: " + finding);
	}

	@Test
	public void aDrugAnActiveOrderResolvesToIsNotStatedAsEnded() throws IOException {
		List<String> findings = findings(ddi(),
			DrugReferenceTestSupport.chartOf(orderRecord(1, "Clarithromycin 500mg", Boolean.FALSE)),
			DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set("Simvastatin", "Clarithromycin"),
				null, null, null),
			"Her current medications are simvastatin and clarithromycin. Any interactions?");

		assertFalse(findings.isEmpty(), "precondition: the pair still raises a finding");
		for (String finding : findings) {
			assertFalse(finding.contains(WITHHOLD_ENDED) || finding.contains(CAUTION_ENDED),
					"she has an active clarithromycin order, so an older ended one does not make it an "
							+ "ended medication: " + finding);
		}
	}

	@Test
	public void anAllergyContraindicationAboutADrugTheChartHoldsOnlyAsAnEndedOrderStatesTheEndedOrderCall() {
		String finding = DrugReferenceTestSupport.safetyFindingIn(
			DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.curatedService()).injectRecords(
				DrugReferenceTestSupport.chartOf(orderRecord(1, "Ibuprofen 400mg", Boolean.FALSE)),
				DrugReferenceTestSupport.ctx(60, null, null, null, DrugReferenceTestSupport.set("ibuprofen"), null),
				"Why was her ibuprofen stopped?")).getText();

		assertTrue(finding.toLowerCase(Locale.ROOT).contains("allerg"),
				"precondition: this is the recorded-allergy contraindication finding: " + finding);
		assertTrue(finding.endsWith(WITHHOLD_ENDED),
				"a contraindication about an ended order states the ended-order call too — both classes "
						+ "or neither: " + finding);
	}

	@Test
	public void aCrossReactivityContraindicationAboutADrugTheChartHoldsOnlyAsAnEndedOrderStatesTheEndedOrderCall() {
		String finding = DrugReferenceTestSupport.safetyFindingIn(
			DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.ddinterServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.chartOf(orderRecord(1, "Ibuprofen 400mg", Boolean.FALSE)),
					DrugReferenceTestSupport.ctx(60, null, null, null, DrugReferenceTestSupport.set("Aspirin"), null),
					"Why was her ibuprofen stopped?")).getText();

		assertTrue(finding.contains("cross-reactivity"),
				"precondition: this is the cross-reactivity rung of the allergen arm: " + finding);
		assertTrue(finding.endsWith(WITHHOLD_ENDED), "the class rung states the ended-order call too: " + finding);
	}

	@Test
	public void theChipCarriesTheReferent() throws IOException {
		DrugReferenceService service = ddi();
		PatientChart chart = DrugReferenceTestSupport.chartOf(orderRecord(1, "Clarithromycin 500mg", Boolean.FALSE));
		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(service).validate(
			"Clarithromycin interacts with simvastatin.",
			"Her current medications are simvastatin and clarithromycin. Any interactions?",
			onlyOn("Simvastatin"), chart.getMappings(), null, null);

		List<SafetyWarning> ended = new ArrayList<SafetyWarning>();
		for (SafetyWarning chip : chips) {
			if (chip.isAboutAnEndedOrder()) {
				ended.add(chip);
			}
		}
		assertEquals(1, ended.size(), "the clarithromycin chip is about an ended order: " + chips);
	}

	@Test
	public void aChipAboutADrugTheChartDoesNotHoldAsEndedCarriesNeither() throws IOException {
		DrugReferenceService service = ddi();
		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(service).validate(
			"Clarithromycin interacts with simvastatin.",
			"Her current medications are simvastatin and clarithromycin. Any interactions?",
			onlyOn("Simvastatin"), DrugReferenceTestSupport.oneRecordChart().getMappings(), null, null);

		assertFalse(chips.isEmpty(), "precondition: the pair raises a chip");
		for (SafetyWarning chip : chips) {
			assertFalse(chip.isAboutAnEndedOrder(), "no record holds the drug as ended: " + chip.getDetail());
		}
	}

	/**
	 * A question PROPOSING a drug her chart holds only as an ended order keeps the proposal call: the
	 * question supplies the proposal "withhold it" needs, so the finding is not re-referred, and where
	 * issue #469's answer is switched on the module still answers it with its withholding lead — the
	 * model path and the module path open alike.
	 */
	@Test
	public void aQuestionProposingADrugTheChartHoldsOnlyAsAnEndedOrderKeepsTheProposalCall() throws IOException {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(ddi()).injectRecords(
			DrugReferenceTestSupport.chartOf(orderRecord(1, "Clarithromycin 500mg", Boolean.FALSE)),
			onlyOn("Simvastatin"), "Can I give her clarithromycin?");

		assertTrue(DrugReferenceTestSupport.findingTexts(chart).get(0).endsWith(WITHHOLD),
				"the question proposes giving it, so the finding states the proposal call: " + chart.getText());
		String answer = chart.getModuleAnswer();
		assertTrue(answer != null && answer.startsWith(DrugReferenceInjector.WITHHOLD_LEAD_OPENING),
				"the question proposes the drug, so the module answers it and leads with the withholding "
						+ "call: " + answer);
	}

	/**
	 * A CLASS-only finding — a shared ATC subgroup and no rule — about a drug the chart holds only as
	 * an ended order states the ended-order caution: the class arm's chip is built apart from the rule
	 * chips, so it is its own site. Over the shipped-KB slice {@code ClassOnlyFindingStrengthTest}
	 * uses, where prednisolone shares {@code H02AB} with her Methylprednisolone and no rule relates them.
	 */
	@Test
	public void aClassOnlyFindingAboutADrugTheChartHoldsOnlyAsAnEndedOrderStatesTheEndedOrderCaution()
			throws IOException {
		String finding = onlyFinding(
			DrugReferenceTestSupport.ddiFixtureService("chartsearchai-test/ddi-class-only-and-rule-one-partner.json"),
			DrugReferenceTestSupport.chartOf(orderRecord(1, "Prednisolone 5mg", Boolean.FALSE)),
			DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Methylprednisolone"),
				DrugReferenceTestSupport.set("H02AB04"), null, null),
			"Why was her prednisolone stopped?");

		assertTrue(finding.contains("same ATC class (H02AB)"),
				"precondition: this is the class arm's finding: " + finding);
		assertTrue(finding.endsWith(CAUTION_ENDED),
				"the class-only chip states the ended-order referent too: " + finding);
	}

	/**
	 * A record the module could not say is in force or not — the stamp's {@code null} — beside an
	 * ended one of the same drug leaves the proposal call: the unstamped order may be one she is on,
	 * and "nothing is known" is not evidence it ended.
	 */
	@Test
	public void anUnstampedOrderRecordBesideAnEndedOneOfTheSameDrugLeavesTheProposalCall() throws IOException {
		String finding = onlyFinding(ddi(),
			DrugReferenceTestSupport.chartOf(orderRecord(1, "Clarithromycin 500mg", Boolean.FALSE),
				orderRecord(2, "Clarithromycin 250mg", null)),
			onlyOn("Simvastatin"), "Her current medications are simvastatin and clarithromycin. Any interactions?");

		assertTrue(finding.endsWith(WITHHOLD),
				"an order record the module cannot place may be in force, so the drug is not stated as "
						+ "ended: " + finding);
	}

	/**
	 * An active order the reference data cannot resolve leaves the proposal call: it may be the very
	 * drug under a name the data lacks, so "no active order of it" cannot be answered — the gate issue
	 * #469 put on "not already taking", {@code everyActiveOrderResolves}.
	 */
	@Test
	public void anActiveOrderTheDataCannotResolveLeavesTheProposalCall() throws IOException {
		String finding = onlyFinding(ddi(),
			DrugReferenceTestSupport.chartOf(orderRecord(1, "Clarithromycin 500mg", Boolean.FALSE)),
			DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set("Simvastatin", "Zyxobrand 250mg"),
				null, null, null, java.util.Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-simva", "Simvastatin"),
					DrugReferenceTestSupport.activeOrder("order-brand", "Zyxobrand 250mg"))),
			"Her current medications are simvastatin and clarithromycin. Any interactions?");

		assertTrue(finding.endsWith(WITHHOLD),
				"an order the data cannot name may be clarithromycin itself, so the drug is not stated as "
						+ "ended: " + finding);
	}

	/** An active-order read the module could not complete leaves the proposal call, for the same reason. */
	@Test
	public void anIncompleteActiveOrderReadLeavesTheProposalCall() throws IOException {
		String finding = onlyFinding(ddi(),
			DrugReferenceTestSupport.chartOf(orderRecord(1, "Clarithromycin 500mg", Boolean.FALSE)),
			DrugReferenceTestSupport.partiallyReadOrdersCtx(DrugReferenceTestSupport.set("Simvastatin")),
			"Her current medications are simvastatin and clarithromycin. Any interactions?");

		assertTrue(finding.endsWith(WITHHOLD),
				"an order list the module could not read in full cannot say she is not on it: " + finding);
	}
}
