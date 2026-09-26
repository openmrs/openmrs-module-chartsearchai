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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.impl.QueryScopeRouter;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Whether two of the patient's OWN active orders carrying one substance are said to, on a screen of
 * her medications and on a question that resolves a drug — issue #477's remaining shape after #483,
 * which states it only for the drug the question puts in play.
 *
 * <p><b>The defect.</b> A screening question puts no drug in play, so neither the class arm nor
 * {@code alreadyInSeveralOrders} runs, and the screening arm relates constituents pairwise and has no
 * identity leg: a patient on two tuberculosis combinations that both contain rifampicin, isoniazid and
 * pyrazinamide heard those combinations' constituents interact with each other and nothing saying the
 * two orders duplicate each other.
 *
 * <p><b>What decides that an order carries a substance</b> is #483's predicate, the order's DISPLAY
 * (issue #293), and the finding is ONE per set of orders, naming every substance that set shares
 * (ADR Decision 99's one-statement rule). It is raised on a screening question and on a question that
 * resolves a drug, and on no other question; ADR Decisions 114 and 116 carry why.
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
	public void bothOfTheTicketsDrugQuestionsStateTheTwoCombinationsOnceAfterEveryOtherFinding() {
		// The ticket's two questions over its six orders and the shipped knowledge base, each putting a
		// drug in play that is not the one the two combinations share: the same finding a screen states,
		// in the same words and referent, once for the one set of orders (the issue's decision comment).
		// Last, after every arm the question raised. The drug-in-play arm's own findings state the
		// current-medication referent for a drug in play her orders resolve to, and the proposal for one
		// they do not (issue #402, ADR Decision 121) — so the drugs the question LISTS as current that are
		// hers state it too (#513 item 1). Trimethoprim and sulfamethoxazole stay proposals: her
		// Cotrimoxazole 960mg order does not resolve to either, a residue the decision records.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
				DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 40, 60.0,
				"Lamivudine / zidovudine", "Efavirenz", "Cotrimoxazole 960mg", RHZ, RHZE, "Stavudine");
		for (String drug : Arrays.asList("Rifampicin", "Metformin")) {
			String question = "The patient is currently on Lamivudine / zidovudine, Efavirenz, Trimethoprim and"
					+ " sulfamethoxazole is it safe to give " + drug + "?";
			Set<Object> asked = new HashSet<Object>();
			for (DrugReference entry : service.findImpliedByQuery(question)) {
				asked.add(entry.substanceGroupKey());
			}
			List<DrugReference> named = service.findImpliedByQuery(drug);
			assertTrue(!named.isEmpty() && asked.contains(named.get(0).substanceGroupKey()),
					"precondition: the question puts " + drug + " itself in play, not only the drugs it lists: "
							+ named);
			List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("", question, context);

			List<SafetyWarning> found = shared(warnings);
			assertEquals(Arrays.asList(SHARED_BY_BOTH), DrugReferenceTestSupport.details(found), question);
			SafetyWarning finding = found.get(0);
			assertEquals(Arrays.asList(RHZ, RHZE), finding.namedPartners());
			assertEquals(SafetyWarning.TYPE_INTERACTION, finding.getType());
			assertNull(finding.getSeverity(), "nothing rates this relationship");
			assertTrue(finding.isAboutACurrentMedication(), "both orders are her own prescriptions");
			assertSame(finding, warnings.get(warnings.size() - 1),
					"after every other finding: " + DrugReferenceTestSupport.details(warnings));
			Set<String> current = new LinkedHashSet<String>(Arrays.asList("Lamivudine", "Zidovudine", "Efavirenz"));
			if ("Rifampicin".equals(drug)) {
				current.add("Rifampicin (rifampin)");
			}
			Set<String> proposed = new LinkedHashSet<String>(Arrays.asList("Sulfamethoxazole (sulfamethazine)",
					"Trimethoprim"));
			if ("Metformin".equals(drug)) {
				proposed.add("Metformin");
			}
			Set<String> seen = new LinkedHashSet<String>();
			for (SafetyWarning warning : warnings.subList(0, warnings.size() - 1)) {
				seen.add(warning.getDrug());
				assertTrue(current.contains(warning.getDrug()) || proposed.contains(warning.getDrug()),
						"a finding about a drug this case does not expect, on " + question + ": " + warning.getDetail());
				assertEquals(current.contains(warning.getDrug()), warning.isAboutACurrentMedication(),
						"the drug-in-play arm states the current-medication referent exactly for a drug her orders "
								+ "resolve to, on " + question + ": " + warning.getDetail());
			}
			Set<String> expected = new LinkedHashSet<String>(current);
			expected.addAll(proposed);
			assertEquals(expected, seen, "every drug the case expects raised a finding, on " + question);
		}
	}

	@Test
	public void theModelReadsItAsAReasonToChangeHerTherapyOnADrugQuestionToo() {
		// Through the real injector on the ticket's chart: the prompt carries the finding once, with the
		// current-medication clause, for both of its questions.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
				DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 40, 60.0,
				"Lamivudine / zidovudine", "Efavirenz", "Cotrimoxazole 960mg", RHZ, RHZE, "Stavudine");
		for (String drug : Arrays.asList("Rifampicin", "Metformin")) {
			String question = "The patient is currently on Lamivudine / zidovudine, Efavirenz, Trimethoprim and"
					+ " sulfamethoxazole is it safe to give " + drug + "?";
			PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
					DrugReferenceTestSupport.oneRecordChart(), context, question);

			List<String> texts = new ArrayList<String>();
			for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
				if (finding.getText().contains(SHARED_BY_BOTH)) {
					texts.add(finding.getText().trim());
				}
			}
			assertEquals(1, texts.size(), question + ": " + chart.getText());
			assertTrue(texts.get(0).endsWith(CHANGE_CURRENT), "was: " + texts.get(0));
		}
	}

	@Test
	public void onAProposalTheFindingFollowsTheProposedDrugsCautions() throws IOException {
		// Rifampicin proposed to a patient on two isoniazid orders and pyrazinamide: a Major against
		// pyrazinamide, a Minor against isoniazid, and her two isoniazid orders. The finding is about
		// her own orders and not about the drug asked about, so it trails that drug's findings, as the
		// module's composed answer puts it (OrdersSharingASubstanceModuleAnswerContextTest).
		List<String> details = DrugReferenceTestSupport.details(DrugReferenceTestSupport.validator(
				DrugReferenceTestSupport.ddiFixtureService(FIXTURE)).validate("", "Can I give her rifampicin?",
					contextOf(DrugReferenceTestSupport.activeOrder("order-inh-1", "Isoniazid 300mg"),
						DrugReferenceTestSupport.activeOrder("order-inh-2", "Isoniazid 100mg"),
						DrugReferenceTestSupport.activeOrder("order-pza", "Pyrazinamide 500mg"))));

		assertEquals(3, details.size(), "was: " + details);
		assertTrue(details.get(0).startsWith("Rifampicin (rifampin) interacts with active order Pyrazinamide — Major."),
			"the Major leads: " + details);
		assertTrue(details.get(1).startsWith("Rifampicin (rifampin) interacts with active order Isoniazid"),
			"then the caution: " + details);
		assertTrue(details.get(1).contains(" — Minor."), "was: " + details);
		assertEquals("Isoniazid is in active orders Isoniazid 300mg and Isoniazid 100mg — possible duplicate therapy",
			details.get(2), "then this finding: " + details);
	}

	@Test
	public void aSetOfOrdersSharingOnlyTheDrugAskedAboutIsLeftToTheFindingThatAlreadyStatesIt() throws IOException {
		// Rifampicin asked about, and two rifampicin orders: that they share it is the drug-in-play arm's
		// own finding (ADR Decision 112), which names the same orders. Stated again here it would be the
		// same fact twice, once in each referent. The issue's decision scopes this finding to a
		// substance that is not the drug in play.
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(
				DrugReferenceTestSupport.ddiFixtureService(FIXTURE)).validate("", "Is it safe to give rifampicin?",
					contextOf(DrugReferenceTestSupport.activeOrder("order-rif-1", "Rifampicin 150mg"),
						DrugReferenceTestSupport.activeOrder("order-rif-2", "Rifampicin 300mg")));

		assertEquals(Arrays.asList("Rifampicin (rifampin) is already in active orders Rifampicin 150mg and"
				+ " Rifampicin 300mg — possible duplicate therapy"), DrugReferenceTestSupport.details(warnings));
		assertEquals(0, shared(warnings).size());
	}

	@Test
	public void aQuestionNamingNoDrugThatIsNotAScreenStatesNothing() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
				DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 40, 60.0,
				"Lamivudine / zidovudine", "Efavirenz", "Cotrimoxazole 960mg", RHZ, RHZE, "Stavudine");
		String question = "What was her last blood pressure?";
		assertTrue(service.findImpliedByQuery(question).isEmpty(), "precondition: no drug in play");
		assertFalse(QueryScopeRouter.isInteractionScreening(question), "precondition: not a screen");

		assertEquals(0, shared(DrugReferenceTestSupport.validator(service).validate("", question, context)).size());
	}

	@Test
	public void aQuestionNamingNoDrugThatIsNotAScreenStatesNothingWhereTheAnswerNamesOne() {
		// The gate reads the QUESTION's drugs and never inPlay, which also holds the drugs the ANSWER
		// names: the pre-answer pass validates an empty answer, so a gate on inPlay would raise the
		// finding on the post-answer chips alone and the prose would never have been handed it.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
				DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 40, 60.0,
				"Lamivudine / zidovudine", "Efavirenz", "Cotrimoxazole 960mg", RHZ, RHZE, "Stavudine");
		String question = "What was her last blood pressure?";
		String answer = "She takes metformin.";
		assertTrue(service.findImpliedByQuery(question).isEmpty(), "precondition: the question names no drug");
		assertFalse(QueryScopeRouter.isInteractionScreening(question), "precondition: not a screen");
		assertFalse(service.findImpliedByQuery(answer).isEmpty(), "precondition: the answer puts a drug in play");

		assertEquals(0, shared(DrugReferenceTestSupport.validator(service).validate(answer, question, context))
				.size());
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

	private static List<SafetyWarning> shared(List<SafetyWarning> warnings) {
		return DrugReferenceTestSupport.ordersSharingASubstance(warnings);
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
