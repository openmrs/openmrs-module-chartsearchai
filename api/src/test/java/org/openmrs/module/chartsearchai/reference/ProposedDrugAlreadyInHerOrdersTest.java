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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.AlreadyOrderedDrug;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Whether a question PROPOSING a drug one of the patient's own active orders already carries is told so —
 * issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/548">#548</a>.
 *
 * <p><b>The defect.</b> <em>"Is it safe to add prednisone for her?"</em> over a chart holding
 * {@code Prednisone Co 5mg} opened <em>"No — Prednisone should not be added"</em>, although every finding
 * about prednisone stated the current-medication call (ADR Decision 123). The owner measured the trigger
 * as the question's verb and not the findings, so the module now states the fact the verb needs: the
 * drug is already in her orders, as a finding and as a clause after the question.
 *
 * <p><b>Three gates, each with a case that only it refuses</b>: the drug-in-play arm's referent
 * ({@code herOrder}, {@code DrugSafetyValidator.currentMedicationsInPlay}) —
 * {@link #aPresentationTheQuestionMayBeProposingAndSheDoesNotTakeGetsNeither}; the question proposing the
 * drug ({@code DrugReferenceInjector.questionProposes}) — {@link #aQuestionThatDoesNotProposeHerDrugGetsNeither};
 * and the drug being one the QUESTION put in play — {@link #aDrugOfHersOnlyTheAnswerNamesGetsNeither}.
 * Over the knowledge base the module ships, through the real {@code validate} and {@code injectRecords}.
 *
 * <p><b>Which orders it names, and how strongly</b> (review round 1 of PR #554): the referent's own
 * witnesses whose display establishes the drug, so a brand-named order is told too —
 * {@link #anOrderWhoseDisplayIsABrandIsToldTooSoTheFindingAndTheFlagAgree}; the display conjuncts'
 * refusals are {@code SubstanceInSeveralActiveOrdersTest}'s cases. One order is a caution in the
 * current-medication column — {@link #theOneOrderFindingIsACautionAboutHerMedicationAndNotAReasonToChangeIt}
 * — and two keep the call to change her medication —
 * {@link #twoOfHerOrdersCarryingTheProposedDrugStillStateTheCallToChangeHerMedication}.
 *
 * <p><b>What adding it would duplicate</b> (review round 3 of PR #554): the order, or where an order may
 * carry another substance, the drug that order carries —
 * {@link #aCombinationOrderIsSaidToCarryTheProposedDrugAndNotToBeDuplicatedByIt} and
 * {@link #severalOrdersOneOfThemACombinationAreSaidToCarryTheProposedDrug}.
 */
public class ProposedDrugAlreadyInHerOrdersTest {

	private static final DrugReferenceService SHIPPED = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());

	/** The issue's order, verbatim. */
	private static final String PREDNISONE_ORDER = "Prednisone Co 5mg";

	/** A second order of hers the question does not name, so the chart is not the drug alone. */
	private static final String WARFARIN_ORDER = "Warfarin 5mg";

	/** The finding this issue adds for the issue's chart — the full detail, so a reword is a decision. It
	 *  states the proposal's consequence in the words the clause after the question uses (review round 2 of
	 *  PR #554): the record's caution clause is what the model restated, so the record has to carry it. */
	private static final String PREDNISONE_ALREADY_IN = "Prednisone is already in active order "
			+ PREDNISONE_ORDER + " — adding it would duplicate that order";

	/** The phrase the finding is recognised by where a case asserts its ABSENCE, cut from the sentence
	 *  above so a reword of the one cannot leave the other asserting a string production never emits. */
	private static final String ALREADY_IN = PREDNISONE_ALREADY_IN.substring("Prednisone ".length(),
			"Prednisone is already in active order".length());

	/** What the stamp says adding a single-substance drug would do, for one order and for several. */
	private static final String THAT_ORDER = "adding it would duplicate that order";

	private static final String THOSE_ORDERS = "adding it would duplicate those orders";

	@Test
	public void eachOfTheIssuesPhrasingsIsToldTheDrugIsAlreadyInHerOrder() {
		// The owner's three measured phrasings: "add" refused, "safe" and "give" did not, and all three
		// must now be told she already takes it (the issue's live gate, rows 1 and 2).
		for (String question : Arrays.asList("Is it safe to add prednisone for her?",
				"Is prednisone safe for her?", "Can I give her prednisone?")) {
			PatientClinicalContext context = prednisoneChart();
			List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(SHIPPED).validate("", question,
				context);

			List<SafetyWarning> found = alreadyIn(warnings);
			assertEquals(1, found.size(), question + " — was: " + DrugReferenceTestSupport.details(warnings));
			SafetyWarning finding = found.get(0);
			assertEquals(PREDNISONE_ALREADY_IN, finding.getDetail(), question);
			assertEquals(Arrays.asList(PREDNISONE_ORDER), finding.namedPartners(),
				"the order the finding names, structurally: " + question);
			assertEquals(SafetyWarning.TYPE_INTERACTION, finding.getType());
			assertEquals(null, finding.getSeverity(), "nothing rates this relationship");
			assertTrue(finding.isAboutACurrentMedication(),
				"one referent per drug in play, and this drug is hers (ADR Decision 123): " + question);

			PatientChart chart = inject(context, question);
			assertTrue(DrugReferenceTestSupport.findingTexts(chart).stream().anyMatch(t -> t.contains(PREDNISONE_ALREADY_IN)),
				"the finding reaches the prompt: " + chart.getText());
			assertEquals(Collections.singletonList(new AlreadyOrderedDrug("Prednisone",
					Arrays.asList(PREDNISONE_ORDER), 1, THAT_ORDER)), chart.getDrugsAlreadyOrdered(),
				"the chart states the fact the user-message clause is written from: " + question);
		}
	}

	@Test
	public void anOrderWhoseDisplayIsABrandIsToldTooSoTheFindingAndTheFlagAgree() {
		// The owner's direction, section 2: the finding is gated on the predicate that sets
		// aboutACurrentMedication, "so the chip and the flag cannot disagree". Each order here establishes the
		// drug without its display NAMING it the way issue #477's display rule reads one, and Barbara Miller's
		// Advil cell is the issue's own. Each cell: {question, her order, the finding's subject label}.
		String[][] cells = {
			{ "Is ibuprofen safe for her?", "Advil 400mg", "Ibuprofen" },
			{ "Can I give her warfarin?", "Coumadin 5mg", "Warfarin" },
			{ "Can I give her acetaminophen?", "Tylenol 500mg", "Acetaminophen" },
			{ "Is it safe to add Prednisone for her?", "Deltasone 5mg", "Prednisone" } };
		for (String[] cell : cells) {
			String question = cell[0];
			String order = cell[1];
			String drug = cell[2];
			String other = "Warfarin".equals(drug) ? "Aspirin 81mg" : WARFARIN_ORDER;
			PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(SHIPPED, 60, null, order, other);
			List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(SHIPPED).validate("", question, context);

			List<SafetyWarning> found = alreadyIn(warnings);
			assertEquals(1, found.size(), question + " — was: " + DrugReferenceTestSupport.details(warnings));
			assertEquals(drug + " is already in active order " + order + " — adding it would duplicate that order",
				found.get(0).getDetail(), question);
			assertEquals(Arrays.asList(order), found.get(0).namedPartners(), question);
			for (SafetyWarning warning : warnings) {
				if (warning.getDrug().startsWith(drug)) {
					assertTrue(warning.isAboutACurrentMedication(),
						"every chip about the proposed drug states her medication, the flag the finding is gated on: "
								+ question + " — " + warning.getDetail());
				}
			}
			assertEquals(Collections.singletonList(new AlreadyOrderedDrug(drug, Arrays.asList(order), 1, THAT_ORDER)),
				inject(context, question).getDrugsAlreadyOrdered(), question);
		}
	}

	@Test
	public void theOneOrderFindingIsACautionAboutHerMedicationAndNotAReasonToChangeIt() {
		// One order cannot duplicate itself: only the proposal would. So the finding states the caution clause
		// in the current-medication column, never the call to change her prednisone (review round 1 of PR
		// #554) — and the prompt's ranking sentence cannot hand it the lead over a caution.
		String question = "Is it safe to add prednisone for her?";
		List<SafetyWarning> found = alreadyIn(DrugReferenceTestSupport.validator(SHIPPED).validate("", question,
			prednisoneChart()));
		assertEquals(1, found.size());
		assertFalse(DrugSafetyValidator.licensesWithholding(found.get(0)),
			"the one strength definition, which the chip ordering reads too");

		List<String> records = new ArrayList<String>();
		for (String text : DrugReferenceTestSupport.findingTexts(inject(prednisoneChart(), question))) {
			if (text.contains(PREDNISONE_ALREADY_IN)) {
				records.add(text);
			}
		}
		assertEquals(1, records.size(), "the finding reaches the prompt once");
		assertTrue(records.get(0).endsWith(DrugReferenceInjector.STRENGTH_CAUTION_CURRENT_MEDICATION),
			"a caution about her medication, not a reason to change it: " + records.get(0));
	}

	@Test
	public void twoOfHerOrdersCarryingTheProposedDrugStillStateTheCallToChangeHerMedication() {
		// Issue #477's finding: two of her orders DO duplicate each other, whatever the question proposes, so
		// its strength stays Decision 112's.
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(SHIPPED, 60, null,
			PREDNISONE_ORDER, "Prednisone 20mg", WARFARIN_ORDER);
		String question = "Is it safe to add prednisone for her?";
		List<SafetyWarning> found = alreadyIn(DrugReferenceTestSupport.validator(SHIPPED).validate("", question,
			context));
		assertEquals(1, found.size());
		assertTrue(DrugSafetyValidator.licensesWithholding(found.get(0)));
		List<String> records = new ArrayList<String>();
		for (String text : DrugReferenceTestSupport.findingTexts(inject(context, question))) {
			if (text.contains("is already in active orders " + PREDNISONE_ORDER)) {
				records.add(text);
			}
		}
		assertEquals(1, records.size(), "the finding reaches the prompt once");
		assertTrue(records.get(0).endsWith(DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION), records.get(0));
	}

	@Test
	public void twoOfHerOrdersCarryingTheProposedDrugAreBothStatedToTheClause() {
		// Issue #477's own finding, on a proposal: the chart stamp carries it too, naming both orders.
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(SHIPPED, 60, null,
			PREDNISONE_ORDER, "Prednisone 20mg", WARFARIN_ORDER);
		String question = "Is it safe to add prednisone for her?";

		List<SafetyWarning> found = alreadyIn(DrugReferenceTestSupport.validator(SHIPPED).validate("", question,
			context));
		assertEquals(1, found.size());
		assertEquals("Prednisone is already in active orders " + PREDNISONE_ORDER
				+ " and Prednisone 20mg — possible duplicate therapy", found.get(0).getDetail());
		assertEquals(Collections.singletonList(new AlreadyOrderedDrug("Prednisone",
				Arrays.asList(PREDNISONE_ORDER, "Prednisone 20mg"), 2, THOSE_ORDERS)), inject(context, question).getDrugsAlreadyOrdered());
	}

	@Test
	public void twoOrdersUnderOneDisplayAreStatedToTheClauseAsTheFindingCountsThem() {
		// One display, two prescriptions: the finding prints the display once with its count, and the
		// stamp the clause is written from must count the orders the same way rather than read one name
		// as one order.
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(SHIPPED, 60, null,
			PREDNISONE_ORDER, PREDNISONE_ORDER, WARFARIN_ORDER);
		String question = "Is it safe to add prednisone for her?";

		List<SafetyWarning> found = alreadyIn(DrugReferenceTestSupport.validator(SHIPPED).validate("", question,
			context));
		assertEquals(1, found.size());
		assertEquals("Prednisone is already in active orders " + PREDNISONE_ORDER
				+ " (2 orders) — possible duplicate therapy", found.get(0).getDetail());
		assertTrue(DrugSafetyValidator.licensesWithholding(found.get(0)),
			"two prescriptions duplicate each other, so the strength counts the orders as the stamp does, and "
					+ "not the one label they share");
		assertEquals(Collections.singletonList(new AlreadyOrderedDrug("Prednisone",
				Arrays.asList(PREDNISONE_ORDER + " (2 orders)"), 2, THOSE_ORDERS)), inject(context, question).getDrugsAlreadyOrdered());
	}

	@Test
	public void aDrugSheDoesNotTakeGetsNeither() {
		// The issue's control cell: clarithromycin is in none of her orders.
		String question = "Is it safe to start her on clarithromycin?";
		PatientClinicalContext context = prednisoneChart();

		assertNoneAlreadyIn(DrugReferenceTestSupport.validator(SHIPPED).validate("", question, context));
		assertEquals(Collections.emptyList(), inject(context, question).getDrugsAlreadyOrdered());
	}

	@Test
	public void anOrderThatResolvesTheDrugWithoutEstablishingItGetsNeither() {
		// Nexium is an alias of both the Omeprazole and the Esomeprazole rows: her orders RESOLVE to
		// omeprazole without establishing she takes it (ADR Decision 123, review round 3).
		String question = "Is it safe to give omeprazole?";
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(SHIPPED, 60, null, "Nexium 40mg",
			WARFARIN_ORDER);

		assertNoneAlreadyIn(DrugReferenceTestSupport.validator(SHIPPED).validate("", question, context));
		assertEquals(Collections.emptyList(), inject(context, question).getDrugsAlreadyOrdered());
	}

	@Test
	public void aPresentationTheQuestionMayBeProposingAndSheDoesNotTakeGetsNeither() {
		// Her one diclofenac order is a gel, coded to the locally applied M02AA15, and the data files
		// diclofenac under a systemic code too, so the question may be proposing an oral course she does
		// not take: the referent keeps the proposal (ADR Decision 123). The display NAMES diclofenac, so
		// this is the case only the referent gate refuses.
		String question = "Can I start her on diclofenac?";
		PatientClinicalContext context = SHIPPED.withReferenceNames(DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set("Diclofenac gel 1%", WARFARIN_ORDER), null, null, null,
			Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-gel", "Diclofenac gel 1%",
					DrugReferenceTestSupport.set("Diclofenac gel 1%"), DrugReferenceTestSupport.set("M02AA15")),
				DrugReferenceTestSupport.activeOrder("order-warfarin", WARFARIN_ORDER))));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(SHIPPED).validate("", question, context);
		assertFalse(warnings.isEmpty(), "precondition: the diclofenac proposal raises findings");
		for (SafetyWarning warning : warnings) {
			assertFalse(warning.isAboutACurrentMedication(),
				"precondition: the referent keeps the proposal for every finding: " + warning.getDetail());
		}
		assertNoneAlreadyIn(warnings);
		assertEquals(Collections.emptyList(), inject(context, question).getDrugsAlreadyOrdered());
	}

	@Test
	public void aQuestionThatDoesNotProposeHerDrugGetsNeither() {
		// A question about her prednisone that proposes nothing, and one LISTING it as current while
		// proposing another drug, keep today's behaviour (the issue's direction, section 2).
		PatientClinicalContext context = prednisoneChart();
		for (String question : Arrays.asList("What is her prednisone dose?",
				"The patient is currently on prednisone, is it safe to give clarithromycin?")) {
			List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(SHIPPED).validate("", question,
				context);
			assertTrue(warnings.stream().anyMatch(w -> w.getDrug().startsWith("Prednisone")),
				"precondition: prednisone is in play and raises findings: " + question + " — "
						+ DrugReferenceTestSupport.details(warnings));
			assertNoneAlreadyIn(warnings);
			assertEquals(Collections.emptyList(), inject(context, question).getDrugsAlreadyOrdered(), question);
		}
	}

	@Test
	public void aDrugOfHersOnlyTheAnswerNamesGetsNeither() {
		// The post-answer pass puts a drug the ANSWER names in play too. The question proposed
		// clarithromycin, not her prednisone, so nothing may say prednisone was proposed.
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(SHIPPED).validate(
			"Clarithromycin can be given; she also takes prednisone.", "Is it safe to start her on clarithromycin?",
			prednisoneChart());

		assertTrue(warnings.stream().anyMatch(w -> w.getDrug().startsWith("Prednisone")),
			"precondition: the answer put prednisone in play: " + DrugReferenceTestSupport.details(warnings));
		assertNoneAlreadyIn(warnings);
	}

	@Test
	public void aCombinationOrderIsSaidToCarryTheProposedDrugAndNotToBeDuplicatedByIt() {
		// Review round 3 of PR #554: a combination's display establishes the proposed constituent, so the
		// finding is raised, but adding one constituent doubles that constituent and is not a second prescription of
		// the combination. So the sentence, and the stamp the clause after the question is written from, say
		// which: the drug that order carries. Each cell: {question, her order's display, another name the chart
		// records for it or null, the finding's subject label}. The shipped knowledge base resolves a Bactrim DS
		// display to the trimethoprim row alone, so there it is the concept's recorded name that shows the
		// combination.
		String[][] cells = {
			{ "Can I give her hydrochlorothiazide?", "Lisinopril/hydrochlorothiazide 20/12.5", null,
				"Hydrochlorothiazide" },
			{ "Can I give her acetaminophen?", "Acetaminophen and codeine 300/30", null, "Acetaminophen" },
			{ "Can I give her codeine?", "Tylenol with Codeine #3", null, "Codeine" },
			{ "Can I give her trimethoprim?", "Bactrim DS", "Sulfamethoxazole / trimethoprim", "Trimethoprim" } };
		for (String[] cell : cells) {
			String question = cell[0];
			String order = cell[1];
			String drug = cell[3];
			List<String> names = cell[2] == null ? Arrays.asList(order) : Arrays.asList(order, cell[2]);
			PatientClinicalContext context = SHIPPED.withReferenceNames(DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set(order, WARFARIN_ORDER), null, null, null,
				Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-combination", order,
						new LinkedHashSet<String>(names), Collections.<String> emptySet()),
					DrugReferenceTestSupport.activeOrder("order-warfarin", WARFARIN_ORDER))));
			List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(SHIPPED).validate("", question, context);

			List<SafetyWarning> found = alreadyIn(warnings);
			assertEquals(1, found.size(), question + " — was: " + DrugReferenceTestSupport.details(warnings));
			String consequence = "adding it would duplicate the " + drug + " that order carries";
			assertEquals(drug + " is already in active order " + order + " — " + consequence, found.get(0).getDetail(),
				question);
			assertFalse(DrugSafetyValidator.licensesWithholding(found.get(0)),
				"still one order, so still a caution about her medication: " + question);
			assertEquals(Collections.singletonList(new AlreadyOrderedDrug(drug, Arrays.asList(order), 1, consequence)),
				inject(context, question).getDrugsAlreadyOrdered(), question);
		}
	}

	@Test
	public void severalOrdersOneOfThemACombinationAreSaidToCarryTheProposedDrug() {
		// The plural form of the same claim: "adding it would duplicate those orders" is as false of a
		// combination among them as the singular is of one.
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(SHIPPED, 60, null,
			"Lisinopril/hydrochlorothiazide 20/12.5", "Hydrochlorothiazide 25mg", WARFARIN_ORDER);
		String question = "Can I give her hydrochlorothiazide?";
		List<SafetyWarning> found = alreadyIn(DrugReferenceTestSupport.validator(SHIPPED).validate("", question,
			context));
		assertEquals(1, found.size());
		assertTrue(found.get(0).getDetail().endsWith(" — possible duplicate therapy"),
			"two orders duplicate each other, so the finding keeps #477's sentence: " + found.get(0).getDetail());
		assertEquals(Collections.singletonList(new AlreadyOrderedDrug("Hydrochlorothiazide",
				Arrays.asList("Lisinopril/hydrochlorothiazide 20/12.5", "Hydrochlorothiazide 25mg"), 2,
				"adding it would duplicate the Hydrochlorothiazide those orders carry")),
			inject(context, question).getDrugsAlreadyOrdered());
	}

	private static PatientClinicalContext prednisoneChart() {
		return DrugReferenceTestSupport.contextNaming(SHIPPED, 60, null, PREDNISONE_ORDER, WARFARIN_ORDER);
	}

	private static PatientChart inject(PatientClinicalContext context, String question) {
		return DrugReferenceTestSupport.injectorWithSafety(SHIPPED).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), context, question);
	}

	private static void assertNoneAlreadyIn(List<SafetyWarning> warnings) {
		assertEquals(0, alreadyIn(warnings).size(), "was: " + DrugReferenceTestSupport.details(warnings));
	}

	private static List<SafetyWarning> alreadyIn(List<SafetyWarning> warnings) {
		List<SafetyWarning> found = new ArrayList<SafetyWarning>();
		for (SafetyWarning warning : warnings) {
			if (warning.getDetail().contains(ALREADY_IN)) {
				found.add(warning);
			}
		}
		return found;
	}
}
