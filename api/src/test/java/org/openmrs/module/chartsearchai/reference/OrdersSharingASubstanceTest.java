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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Whether two of the patient's OWN active orders carrying one substance are said to on a screen of
 * her medications — issue #477's remaining shape after #483, which states it only for a drug the
 * question puts in play.
 *
 * <p><b>The defect.</b> A screening question puts no drug in play, so neither the class arm nor
 * {@code alreadyInSeveralOrders} runs, and the screening arm relates constituents pairwise and has no
 * identity leg: a patient on two tuberculosis combinations that both contain rifampicin, isoniazid and
 * pyrazinamide heard those combinations' constituents interact with each other and nothing saying the
 * two orders duplicate each other.
 *
 * <p><b>What decides that an order carries a substance</b> is #483's predicate, the order's DISPLAY
 * (issue #293), and the finding is ONE per set of orders, naming every substance that set shares
 * (ADR Decision 99's one-statement rule). It is raised on a screening question and nowhere else: the
 * rifampicin question's finding list is pinned by {@code SubstanceInSeveralActiveOrdersTest}, and
 * ADR Decision 114 carries why.
 */
public class OrdersSharingASubstanceTest {

	/** Verbatim shipped-KB rows for the four first-line TB substances — see the fixture's note. */
	private static final String FIXTURE = "chartsearchai-test/ddi-substance-in-several-orders.json";

	private static final String RHZ = "Isoniazid / pyrazinamide / rifampin";

	private static final String RHZE = "Rifampicin isoniazid pyrazinamide and ethambutol 150/75/400/275mg";

	/** The two TB orders' finding, in full, so a reword is a decision. */
	private static final String SHARED_BY_BOTH = "Isoniazid, Pyrazinamide and Rifampicin (rifampin) are in"
			+ " active orders " + RHZ + " and " + RHZE + " — possible duplicate therapy";

	/** Pinned as literals and not read off the injector's constants — see
	 *  {@code SubstanceInSeveralActiveOrdersTest.CHANGE_CURRENT}. */
	private static final String CHANGE_CURRENT =
			"This finding is a reason to change a medication this patient is already taking.";

	@Test
	public void aScreenNamesEverySubstanceTwoOfHerOrdersBothCarryOnce() throws IOException {
		List<SafetyWarning> found = shared(screen(FIXTURE, twoTuberculosisCombinations()));

		assertEquals(1, found.size(), "one finding for one set of orders: " + found);
		SafetyWarning finding = found.get(0);
		assertEquals(SHARED_BY_BOTH, finding.getDetail());
		assertEquals("Isoniazid, Pyrazinamide and Rifampicin (rifampin)", finding.getDrug(),
				"every substance it names, as the detail lists them — README's drug row");
		assertEquals(Arrays.asList(RHZ, RHZE), finding.namedPartners());
		assertEquals(SafetyWarning.TYPE_INTERACTION, finding.getType());
		assertNull(finding.getSeverity(), "nothing rates this relationship");
		assertTrue(finding.isAboutACurrentMedication(),
				"both orders are her own prescriptions and nothing is proposed");
	}

	@Test
	public void theReproductionOverTheShippedKnowledgeBaseStatesTheTwoCombinationsAndNothingElse() {
		// The issue's six orders over the whole knowledge base, so every competing claimant the display
		// predicate ranks against is present, and an HIV combination beside a single HIV drug is too.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
				DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 40, 60.0,
				"Lamivudine / zidovudine", "Efavirenz", "Cotrimoxazole 960mg", RHZ, RHZE, "Stavudine");

		List<SafetyWarning> found = shared(DrugReferenceTestSupport.validator(service).validate("",
				DrugReferenceTestSupport.SCREENING_QUESTION, context));

		assertEquals(Arrays.asList(SHARED_BY_BOTH), DrugReferenceTestSupport.details(found));
	}

	@Test
	public void aSubstanceOnlyOneOrderCarriesIsNotNamed() throws IOException {
		// Ethambutol is in the four-drug combination alone; the three it shares with the other order are
		// the finding, and a single order of ethambutol beside it shares only that one.
		List<SafetyWarning> found = shared(screen(FIXTURE, contextOf(
				DrugReferenceTestSupport.activeOrder("order-rhze", RHZE),
				DrugReferenceTestSupport.activeOrder("order-emb", "Ethambutol 400mg"))));

		assertEquals(Arrays.asList("Ethambutol is in active orders " + RHZE + " and Ethambutol 400mg"
				+ " — possible duplicate therapy"), DrugReferenceTestSupport.details(found));
	}

	@Test
	public void twoSetsOfOrdersAreTwoFindings() throws IOException {
		// One per set, in either chart arrangement. The order between the two is the resolution's.
		for (boolean ethambutolFirst : new boolean[] { true, false }) {
			PatientClinicalContext.ActiveDrugOrder emb = DrugReferenceTestSupport.activeOrder("order-emb",
				"Ethambutol 400mg");
			PatientClinicalContext.ActiveDrugOrder rhz = DrugReferenceTestSupport.activeOrder("order-rhz", RHZ);
			PatientClinicalContext.ActiveDrugOrder rhze = DrugReferenceTestSupport.activeOrder("order-rhze", RHZE);
			String ethambutol = "Ethambutol is in active orders " + (ethambutolFirst ? "Ethambutol 400mg and " + RHZE
					: RHZE + " and Ethambutol 400mg") + " — possible duplicate therapy";
			List<String> found = DrugReferenceTestSupport.details(shared(screen(FIXTURE, ethambutolFirst
					? contextOf(emb, rhz, rhze) : contextOf(rhz, rhze, emb))));

			assertEquals(ethambutolFirst ? Arrays.asList(ethambutol, SHARED_BY_BOTH)
					: Arrays.asList(SHARED_BY_BOTH, ethambutol), found);
		}
	}

	@Test
	public void oneOrderNamesNothing() throws IOException {
		assertEquals(0, shared(screen(FIXTURE, contextOf(
				DrugReferenceTestSupport.activeOrder("order-rhz", RHZ)))).size());
	}

	@Test
	public void anOrderWhoseOtherRecordedNameNamesASubstanceIsJudgedOnItsDisplay() throws IOException {
		// Issue #293's shape: the free text names rifampicin, the display isoniazid. Only isoniazid is
		// in both, by the names the finding prints.
		List<SafetyWarning> found = shared(screen(FIXTURE, contextOf(
				DrugReferenceTestSupport.activeOrder("order-rhz", RHZ),
				DrugReferenceTestSupport.activeOrder("order-inh", "Isoniazid 300mg", "Rifampicin 150mg"))));

		assertEquals(Arrays.asList("Isoniazid is in active orders " + RHZ + " and Isoniazid 300mg"
				+ " — possible duplicate therapy"), DrugReferenceTestSupport.details(found));
	}

	@Test
	public void twoOrdersRecordedUnderOneNameAreNamedOnceWithTheirCount() throws IOException {
		List<SafetyWarning> found = shared(screen(FIXTURE, contextOf(
				DrugReferenceTestSupport.activeOrder("order-rif-1", "Rifampicin 150mg"),
				DrugReferenceTestSupport.activeOrder("order-rif-2", "Rifampicin 150mg"))));

		assertEquals(Arrays.asList("Rifampicin (rifampin) is in active orders Rifampicin 150mg (2 orders)"
				+ " — possible duplicate therapy"), DrugReferenceTestSupport.details(found));
		assertEquals(Arrays.asList("Rifampicin 150mg"), found.get(0).namedPartners());
	}

	@Test
	public void aQuestionPuttingADrugInPlayStatesNoCurrentMedicationFindingBesideItsProposalFindings() {
		// The ticket's two questions over its six orders and the shipped knowledge base, both putting
		// drugs in play. What they state about her orders sharing a substance is still open on issue
		// #477, so this pins not that silence but the constraint any statement there must keep: this
		// finding's current-medication referent never sits beside the proposal findings of a drug in
		// play, the mixed-referent response ADR Decision 112 recorded on a model (Decision 114).
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
				DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 40, 60.0,
				"Lamivudine / zidovudine", "Efavirenz", "Cotrimoxazole 960mg", RHZ, RHZE, "Stavudine");
		int proposals = 0;
		for (String drug : Arrays.asList("Rifampicin", "Metformin")) {
			String question = "The patient is currently on Lamivudine / zidovudine, Efavirenz, Trimethoprim and"
					+ " sulfamethoxazole is it safe to give " + drug + "?";
			assertTrue(service.findImpliedByQuery(question).size() > 0, "precondition: drugs in play: " + question);
			List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("", question, context);
			boolean proposal = false;
			for (SafetyWarning warning : warnings) {
				proposal |= !warning.isAboutACurrentMedication();
			}
			if (!proposal) {
				continue;
			}
			proposals++;
			for (SafetyWarning finding : shared(warnings)) {
				assertTrue(!finding.isAboutACurrentMedication(), "a current-medication finding beside proposal"
						+ " findings on " + question + ": " + DrugReferenceTestSupport.details(warnings));
			}
		}
		assertTrue(proposals > 0, "precondition: a question raised proposal findings");
	}

	@Test
	public void theChipListRanksTheFindingByStrengthAsTheModuleAnswerDoes() throws IOException {
		// A reason to change her therapy, so beside a Major and ahead of a caution, on the chips and the
		// prompt's record order as in the module's answer (OrdersSharingASubstanceModuleAnswerContextTest):
		// one response must not order one set of findings two ways, and a truncated answer keeps what
		// the arm appended first (issue #346).
		List<String> major = DrugReferenceTestSupport.details(screen(FIXTURE, contextOf(
			DrugReferenceTestSupport.activeOrder("order-rhz", RHZ),
			DrugReferenceTestSupport.activeOrder("order-rif", "Rifampicin 150mg"))));

		assertEquals(3, major.size(), "was: " + major);
		assertTrue(major.get(0).startsWith("Pyrazinamide interacts with active order Rifampicin (rifampin) — Major."),
			"the Major leads: " + major);
		assertEquals("Rifampicin (rifampin) is in active orders " + RHZ + " and Rifampicin 150mg"
				+ " — possible duplicate therapy", major.get(1), "then this finding: " + major);
		assertTrue(major.get(2).startsWith("Isoniazid interacts with active order Rifampicin (rifampin) — Minor."),
			"then the caution: " + major);

		List<String> cautions = DrugReferenceTestSupport.details(screen(FIXTURE, contextOf(
			DrugReferenceTestSupport.activeOrder("order-rhze", RHZE),
			DrugReferenceTestSupport.activeOrder("order-inh", "Isoniazid 300mg"),
			DrugReferenceTestSupport.activeOrder("order-emb", "Ethambutol 400mg"))));
		int lastShared = -1;
		int firstPair = -1;
		for (int i = 0; i < cautions.size(); i++) {
			if (cautions.get(i).endsWith(" — possible duplicate therapy")) {
				lastShared = i;
			}
			if (firstPair < 0 && cautions.get(i).contains(" interacts with active order ")) {
				firstPair = i;
			}
		}
		assertTrue(firstPair >= 0, "precondition: the screen related pairs: " + cautions);
		assertTrue(lastShared >= 0 && lastShared < firstPair, "the reasons to change lead the cautions: " + cautions);
	}

	@Test
	public void theFindingIsNotCountedAsAnInteractionPairFound() throws IOException {
		PairChipExtent.Sink sink = new PairChipExtent.Sink();
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(
				DrugReferenceTestSupport.ddiFixtureService(FIXTURE)).validate("",
					DrugReferenceTestSupport.SCREENING_QUESTION, twoTuberculosisCombinations(), null, null, sink);

		assertEquals(1, shared(warnings).size(), "precondition: raised in this pass: " + warnings);
		int rated = 0;
		for (SafetyWarning warning : warnings) {
			if (warning.getSeverity() != null) {
				rated++;
			}
		}
		// Rated chips stand for pairs here because this fixture's screen collapses no mechanism.
		assertTrue(rated > 0, "precondition: the screen related pairs: " + warnings);
		assertEquals(rated, sink.stated().getFound(), "the rated pairs and nothing else");
	}

	@Test
	public void aScreenThatRelatedNoPairStillStatesThatItRanBesideThisFinding() {
		// Issue #401's note says the screen ran and related nothing. This finding relates no pair either,
		// so it must not stand in for a screen result and take the note's place: over the shipped
		// knowledge base the two orders' substances, amlodipine and valsartan, relate nothing.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
				DrugReferenceTestSupport.shippedEntries());
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(),
			DrugReferenceTestSupport.contextNaming(service, 60, null, "Amlodipine", "Amlodipine / valsartan"),
			DrugReferenceTestSupport.SCREENING_QUESTION);

		List<String> findings = DrugReferenceTestSupport.findingTexts(chart);
		assertEquals(1, findings.size(), "precondition: this finding and no pair: " + findings);
		assertTrue(findings.get(0).contains("Amlodipine is in active orders Amlodipine and Amlodipine / valsartan"),
				"was: " + findings.get(0));
		int notes = 0;
		for (RecordMapping mapping : chart.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_INTERACTION_SCREEN_NOTE.equals(mapping.getResourceType())) {
				notes++;
			}
		}
		assertEquals(1, notes, "the screen's own statement that it ran and related nothing: " + chart.getText());
	}

	@Test
	public void theModelReadsItAsAReasonToChangeHerCurrentTherapy() throws IOException {
		// Through the real injector: a duplicate of her own therapy is a reason to change it, which is
		// the current-medication counterpart of the unrated default, and never the caution's "not a
		// reason to change it".
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(
				DrugReferenceTestSupport.ddiFixtureService(FIXTURE)).injectRecords(
					DrugReferenceTestSupport.oneRecordChart(), twoTuberculosisCombinations(),
					DrugReferenceTestSupport.SCREENING_QUESTION);

		List<String> texts = new ArrayList<String>();
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			if (finding.getText().contains(SHARED_BY_BOTH)) {
				texts.add(finding.getText().trim());
			}
		}
		assertEquals(1, texts.size(), "precondition: the finding reached the prompt: " + chart.getText());
		assertTrue(texts.get(0).endsWith(CHANGE_CURRENT), "was: " + texts.get(0));
	}

	private static List<SafetyWarning> screen(String fixture, PatientClinicalContext context) throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(fixture))
				.validate("", DrugReferenceTestSupport.SCREENING_QUESTION, context);
	}

	/** This finding among {@code warnings}, recognised by the flag its factory sets, so a reword of
	 *  the sentence cannot leave a negative case asserting the absence of a string nobody emits. */
	private static List<SafetyWarning> shared(List<SafetyWarning> warnings) {
		List<SafetyWarning> found = new ArrayList<SafetyWarning>();
		for (SafetyWarning warning : warnings) {
			if (warning.statesOrdersSharingASubstance()) {
				found.add(warning);
			}
		}
		return found;
	}

	private static PatientClinicalContext twoTuberculosisCombinations() {
		return contextOf(DrugReferenceTestSupport.activeOrder("order-rhz", RHZ),
				DrugReferenceTestSupport.activeOrder("order-rhze", RHZE));
	}

	private static PatientClinicalContext contextOf(PatientClinicalContext.ActiveDrugOrder... orders) {
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.ctx(40, null,
				new LinkedHashSet<String>(names), null, null, null, Arrays.asList(orders));
	}
}
