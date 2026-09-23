/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The knowledge base's DERIVED tier reaches the clinician (issues #391 Part B and #473): a drug in play
 * that one of the patient's active orders is linked to through a drug-disease CONDITION — the cause
 * drug's DDInter drug-disease note names the condition in a sentence the knowledge base reads as
 * causal, and the rated drug is rated for that condition — raises a {@code condition-mediated}
 * finding, and the active orders linked through one condition in one direction are stated in ONE
 * finding rather than a finding apiece.
 *
 * <p>The arrangement is #359's row E, which the pairwise screen cannot reach: DDInter rates metformin
 * against stavudine and against lamivudine {@code Unknown}, with no mechanism text, below the default
 * severity floor. Every case runs the real validator over the shipped knowledge base.
 *
 * <p><b>Context-sensitive because the arm is gated, and OFF on a stock install</b> —
 * {@code chartsearchai.drugSafety.derivedFindings}, ADR Decision 110, whose precision section is the
 * reason. A contextless case runs with the property absent, which fails safe to the default, so it could
 * not tell an arm that honours the switch from one that ignores it. {@link #setUp} turns it on for every
 * case here; {@link #aStockInstallRaisesNoConditionMediatedFindingOnTheLinkTheReviewMeasuredFalse} and
 * {@link #aValueTheSwitchDoesNotOfferRaisesNothing} turn it back, and they are what pin the gate.
 */
public class ConditionMediatedFindingTest extends BaseModuleContextSensitiveTest {

	private static final String TYPE = "condition-mediated";

	private static final String DISCLAIMER = "not a DDInter pairwise rating";

	private static DrugReferenceService shipped;

	@BeforeEach
	public void setUp() {
		derivedFindings(ChartSearchAiConstants.DERIVED_FINDINGS_MAJOR);
	}

	private static void derivedFindings(String value) {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_DERIVED_FINDINGS,
			value);
	}

	private static synchronized DrugReferenceService shippedService() {
		if (shipped == null) {
			shipped = DrugReferenceTestSupport.serviceWithGroups(DrugReferenceTestSupport.shippedEntries());
		}
		return shipped;
	}

	private static PatientClinicalContext onOrders(String... displays) {
		return DrugReferenceTestSupport.rawContextNaming(60, null, displays);
	}

	private static List<SafetyWarning> conditionMediated(String question, PatientClinicalContext context) {
		return DrugReferenceTestSupport.ofType(
				DrugReferenceTestSupport.validator(shippedService()).validate("", question, context), TYPE);
	}

	@Test
	public void twoActiveOrdersLinkedToLacticAcidosisAreStatedInOneFindingAboutMetformin() {
		List<SafetyWarning> chips = conditionMediated("Can I give metformin?",
				onOrders("Stavudine", "Lamivudine"));

		assertEquals(1, chips.size(), "both orders are linked to metformin through one condition, so ONE finding"
				+ " states them both, was: " + DrugReferenceTestSupport.details(chips));
		SafetyWarning chip = chips.get(0);
		assertTrue(chip.getDrug().contains("Metformin"), chip.getDrug());
		String detail = chip.getDetail();
		assertTrue(detail.contains("Metformin is rated Major in Acidosis, Lactic"), detail);
		assertTrue(detail.contains(DrugSafetyValidator.ACTIVE_ORDER_NOUN + " Stavudine"), detail);
		assertTrue(detail.contains(DrugSafetyValidator.ACTIVE_ORDER_NOUN + " Lamivudine"), detail);
		assertTrue(detail.contains("(Liver Diseases, Major)"),
				"each partner states its own cause-side filing and rating: " + detail);
		assertTrue(detail.contains(DISCLAIMER), detail);
		assertTrue(detail.endsWith("this finding has no severity of its own."),
				"the only rating words in the detail are drug-disease ratings, so it says it has none: " + detail);
		assertNull(chip.getSeverity(), "the derived tier assigns the pair no rating of its own");
		assertEquals(new LinkedHashSet<String>(Arrays.asList("Stavudine", "Lamivudine")),
				new LinkedHashSet<String>(chip.namedPartners()));
		assertFalse(chip.isAboutACurrentMedication(), "the finding is about the drug the question proposes");
	}

	@Test
	public void theChainIsFoundFromTheCauseSideToo() {
		List<SafetyWarning> chips = conditionMediated("Can I give stavudine?", onOrders("Metformin"));

		assertEquals(1, chips.size(), DrugReferenceTestSupport.details(chips).toString());
		SafetyWarning chip = chips.get(0);
		assertTrue(chip.getDrug().contains("Stavudine"), chip.getDrug());
		assertTrue(chip.getDetail().contains(DrugSafetyValidator.ACTIVE_ORDER_NOUN
				+ " Metformin is rated Major in Acidosis, Lactic"), chip.getDetail());
		assertTrue(chip.getDetail().contains("Acidosis, Lactic"), chip.getDetail());
		assertTrue(chip.getDetail().contains(DISCLAIMER), chip.getDetail());
		assertEquals(Arrays.asList("Metformin"), chip.namedPartners());
	}

	@Test
	public void aChainWhoseRatedSideIsBelowMajorIsNotStated() {
		// The knowledge base also links metformin's lactic-acidosis note to didanosine's HEART FAILURE
		// rating, which is Moderate. Only the chain whose rated side is Major may speak.
		List<SafetyWarning> chips = conditionMediated("Can I give didanosine?", onOrders("Metformin"));

		assertEquals(1, chips.size(), DrugReferenceTestSupport.details(chips).toString());
		assertTrue(chips.get(0).getDetail().contains("Acidosis, Lactic"), chips.get(0).getDetail());
		assertFalse(chips.get(0).getDetail().contains("Heart Failure"), chips.get(0).getDetail());
	}

	@Test
	public void theInjectedFindingIsACautionAndNotAReasonToWithhold() {
		// ADR Decision 110, after Decision 86: a derived chain is not a rating of the pair — a text match
		// over two drug-disease rows found it — so it is a caution, the weakest claim this layer makes.
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(shippedService()).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(), onOrders("Stavudine", "Lamivudine"),
				"Can I give metformin?");

		List<String> derived = new ArrayList<String>();
		for (String text : DrugReferenceTestSupport.findingTexts(chart)) {
			if (text.contains(DISCLAIMER)) {
				derived.add(text);
			}
		}
		assertEquals(1, derived.size(), DrugReferenceTestSupport.findingTexts(chart).toString());
		assertTrue(derived.get(0).contains(DrugReferenceInjector.STRENGTH_CAUTION.trim()), derived.get(0));
		assertFalse(derived.get(0).contains(DrugReferenceInjector.STRENGTH_WITHHOLD.trim()), derived.get(0));
	}

	@Test
	public void aSubstanceTheKnowledgeBaseFilesTwiceLinksItsPartnerOnce() {
		// Lidocaine and Lidocaine (topical) are two rows of one substance, and BOTH are rated for seizures
		// in a chain acyclovir's note names (measured through the loader). One substance, one partner.
		List<SafetyWarning> seizures = new ArrayList<SafetyWarning>();
		for (SafetyWarning chip : conditionMediated("Can I give lidocaine?", onOrders("Acyclovir"))) {
			if (chip.getDetail().contains("Seizures")) {
				seizures.add(chip);
			}
		}

		assertEquals(1, seizures.size(), DrugReferenceTestSupport.details(seizures).toString());
		String detail = seizures.get(0).getDetail();
		String partner = DrugSafetyValidator.ACTIVE_ORDER_NOUN + " Acyclovir";
		assertEquals(detail.indexOf(partner), detail.lastIndexOf(partner), detail);
		assertEquals(Arrays.asList("Acyclovir"), seizures.get(0).namedPartners());
	}

	@Test
	public void aCombinationPrescriptionBothOfWhoseConstituentsLinkIsNamedOnceWithBothRatings() {
		// Lamivudine and Zidovudine are each linked to metformin through lactic acidosis, and a fixed-dose
		// Lamivudine / Zidovudine prescription is ONE active order: it is named once, by the name the
		// co-medication ladder gives it, carrying both constituents' ratings — never as two orders.
		String display = "Lamivudine / Zidovudine";
		java.util.Set<String> names = DrugReferenceTestSupport.set(display);
		java.util.Set<String> codes = DrugReferenceTestSupport.set("J05AR01");
		PatientClinicalContext chart = shippedService().withReferenceNames(DrugReferenceTestSupport.ctx(60, null,
			names, codes, null, null, Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-uuid-473-combination", display, names, codes))));

		List<SafetyWarning> chips = conditionMediated("Can I give metformin?", chart);

		assertEquals(1, chips.size(), DrugReferenceTestSupport.details(chips).toString());
		String detail = chips.get(0).getDetail();
		assertEquals(1, chips.get(0).namedPartners().size(), "one prescription, one partner: " + detail);
		String partner = DrugSafetyValidator.ACTIVE_ORDER_NOUN + " " + chips.get(0).namedPartners().get(0);
		assertEquals(detail.indexOf(partner), detail.lastIndexOf(partner), detail);
		assertTrue(detail.contains("(its Lamivudine: Liver Diseases, Major; its Zidovudine: Liver Diseases, Major)"),
				"both constituents' links are carried, each labelled, neither dropped: " + detail);
		assertTrue(detail.contains("drug-disease notes of"), "two constituents, two notes: " + detail);
	}

	@Test
	public void aDerivedChainIsNotCountedAsAPairTheScreenFound() {
		// PairChipExtent counts DDInter pairwise rule pairs, and a derived chain is not one: DDInter rates
		// both of these pairs Unknown, below the default floor, so the screen found none.
		PairChipExtent.Sink sink = new PairChipExtent.Sink();
		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(shippedService()).validate("",
				"Can I give metformin?", onOrders("Stavudine", "Lamivudine"),
				java.util.Collections.<org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping> emptyList(),
				null, sink);

		int derived = 0;
		for (SafetyWarning chip : chips) {
			if (TYPE.equals(chip.getType())) {
				derived++;
			}
		}
		assertEquals(1, derived, DrugReferenceTestSupport.details(chips).toString());
		assertEquals(0, sink.stated().getFound());
		assertEquals(0, sink.stated().getReported());
	}

	@Test
	public void theCauseSideStatesEveryRatedOrderInOneSentence() {
		// Acyclovir's note names seizures, and diazepam, lorazepam and midazolam are each rated Major for
		// them: one finding, three orders, one sentence.
		List<SafetyWarning> seizures = new ArrayList<SafetyWarning>();
		for (SafetyWarning chip : conditionMediated("Can I give acyclovir?",
				onOrders("Diazepam", "Lorazepam", "Midazolam"))) {
			if (chip.getDetail().contains("Seizures")) {
				seizures.add(chip);
			}
		}

		assertEquals(1, seizures.size(), DrugReferenceTestSupport.details(seizures).toString());
		assertTrue(seizures.get(0).getDetail().contains(DrugSafetyValidator.ACTIVE_ORDER_NOUN + " Diazepam, "
				+ DrugSafetyValidator.ACTIVE_ORDER_NOUN + " Lorazepam and " + DrugSafetyValidator.ACTIVE_ORDER_NOUN
				+ " Midazolam are each rated Major in Seizures"), seizures.get(0).getDetail());
	}

	@Test
	public void aPrescriptionWhoseDisplayNamesNoSubstanceIsAttributedToTheSubstanceItIs() {
		// A prescription the knowledge base knows only by its ATC code: its display names no substance, so
		// the finding says which of the patient's own orders metformin came from (#349) — through the one
		// shared chartOrderBridges call, as the interaction chips do.
		java.util.Set<String> names = DrugReferenceTestSupport.set("Metbrand");
		java.util.Set<String> codes = DrugReferenceTestSupport.set("A10BA02");
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null, names, codes, null, null,
			Arrays.asList(DrugReferenceTestSupport.activeOrder("order-metbrand", "Metbrand", names, codes)));

		List<SafetyWarning> chips = conditionMediated("Can I give stavudine?", chart);

		assertEquals(1, chips.size(), DrugReferenceTestSupport.details(chips).toString());
		boolean bridged = false;
		for (SafetyWarning.ChartOrderBridge bridge : chips.get(0).chartOrderBridges()) {
			bridged |= "Metbrand".equals(bridge.getOrderDisplay());
		}
		assertTrue(bridged, "the finding must say which prescription the substance came from, was: "
				+ chips.get(0).chartOrderBridges() + " on " + chips.get(0).getDetail());
	}

	@Test
	public void aConditionMediatedFindingDoesNotLicenseWithholding() {
		List<SafetyWarning> chips = conditionMediated("Can I give metformin?", onOrders("Stavudine"));

		assertEquals(1, chips.size(), DrugReferenceTestSupport.details(chips).toString());
		assertTrue(chips.get(0).getDetail().contains("the DDInter drug-disease note of "
				+ DrugSafetyValidator.ACTIVE_ORDER_NOUN + " Stavudine (Liver Diseases, Major) names Acidosis, Lactic"),
				"one partner, one note: " + chips.get(0).getDetail());
		assertFalse(DrugSafetyValidator.licensesWithholding(chips.get(0)));
	}

	@Test
	public void aStockInstallRaisesNoConditionMediatedFindingOnTheLinkTheReviewMeasuredFalse() {
		// Metformin's drug-disease note names congestive heart failure only as a CONTRAINDICATION, and the
		// knowledge base's matcher reads that sentence as causal, so the derived tier links metformin to
		// every drug rated Major in Heart Failure — lisinopril among them (ADR Decision 110). With the
		// precision of the kept chains unmeasured, a stock install states none of them, and the
		// interaction arm the same property does NOT gate still speaks.
		derivedFindings(ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_DERIVED_FINDINGS);

		List<SafetyWarning> all = DrugReferenceTestSupport.validator(shippedService()).validate("",
			"Can I give lisinopril?", onOrders("Metformin"));

		assertTrue(DrugReferenceTestSupport.ofType(all, TYPE).isEmpty(), DrugReferenceTestSupport.details(all).toString());
		assertFalse(DrugReferenceTestSupport.ofType(all, SafetyWarning.TYPE_INTERACTION).isEmpty(),
			"the switch is the derived tier's own, and warnOnInteractions' pairwise chip stands: "
					+ DrugReferenceTestSupport.details(all));
		assertTrue(conditionMediated("Can I give metformin?", onOrders("Stavudine", "Lamivudine")).isEmpty(),
			"and #473's own arrangement states nothing either");

		derivedFindings(ChartSearchAiConstants.DERIVED_FINDINGS_MAJOR);
		boolean heartFailure = false;
		for (SafetyWarning chip : conditionMediated("Can I give lisinopril?", onOrders("Metformin"))) {
			heartFailure |= chip.getDetail().contains("Heart Failure");
		}
		assertTrue(heartFailure, "precondition: switched on, the shipped data does raise the link the default"
				+ " withholds, or this case pins nothing");
	}

	@Test
	public void aValueTheSwitchDoesNotOfferRaisesNothing() {
		// #391 proposed `all`; the loader keeps no non-Major rated side, so it is not offered, and a value
		// the switch does not name reads as the default rather than as `major`.
		derivedFindings("all");

		assertTrue(conditionMediated("Can I give metformin?", onOrders("Stavudine", "Lamivudine")).isEmpty());
	}

	@Test
	public void aDrugInPlayThatCausesTheConditionNamesEveryOtherOrderThatCausesItToo() {
		// #473's N-drug finding is every order joined by derived edges on one condition. Asked about
		// didanosine on stavudine and metformin: didanosine's note and stavudine's both name lactic
		// acidosis, and metformin is rated Major for it — one finding naming both orders, whichever of the
		// three the question is about.
		List<SafetyWarning> lactic = new ArrayList<SafetyWarning>();
		for (SafetyWarning chip : conditionMediated("Can I give didanosine?", onOrders("Stavudine", "Metformin"))) {
			if (chip.getDetail().contains("Acidosis, Lactic")) {
				lactic.add(chip);
			}
		}

		assertEquals(1, lactic.size(), DrugReferenceTestSupport.details(lactic).toString());
		String detail = lactic.get(0).getDetail();
		assertTrue(detail.contains(", as does that of " + DrugSafetyValidator.ACTIVE_ORDER_NOUN
				+ " Stavudine (Liver Diseases, Major), and " + DrugSafetyValidator.ACTIVE_ORDER_NOUN
				+ " Metformin is rated Major in Acidosis, Lactic"), detail);
		assertEquals(new LinkedHashSet<String>(Arrays.asList("Metformin", "Stavudine")),
			new LinkedHashSet<String>(lactic.get(0).namedPartners()));
	}

	@Test
	public void aDrugInPlayRatedForTheConditionNamesEveryOtherOrderRatedForItToo() {
		// The same join from the rated side: lactic acid is rated Major in Acidosis, Lactic as metformin
		// is, and stavudine's note links to both. The order is named as the co-medication ladder names it.
		List<SafetyWarning> chips = conditionMediated("Can I give metformin?", onOrders("Stavudine", "Lactic acid"));
		List<SafetyWarning> lactic = new ArrayList<SafetyWarning>();
		for (SafetyWarning chip : chips) {
			if (chip.getDetail().startsWith("Metformin is rated Major in Acidosis, Lactic")) {
				lactic.add(chip);
			}
		}

		assertEquals(1, lactic.size(), DrugReferenceTestSupport.details(chips).toString());
		String detail = lactic.get(0).getDetail();
		assertTrue(detail.startsWith("Metformin is rated Major in Acidosis, Lactic (DDInter drug-disease), as is "
				+ DrugSafetyValidator.ACTIVE_ORDER_NOUN + " Lactic acid (lactate), and the DDInter drug-disease note of "
				+ DrugSafetyValidator.ACTIVE_ORDER_NOUN + " Stavudine"), detail);
		assertEquals(new LinkedHashSet<String>(Arrays.asList("Stavudine", "Lactic acid (lactate)")),
			new LinkedHashSet<String>(lactic.get(0).namedPartners()));
	}

	@Test
	public void anOrderOnBothSidesOfTheConditionIsNamedOnce() {
		// Lisinopril and benazepril are each rated Major in Heart Failure AND each note names it, so each is
		// a partner of a heart-failure chip about verapamil and, through the other, also a member of the
		// subject's side. One order, one mention, in either direction.
		for (String question : new String[] { "Can I give verapamil?", "Can I give metformin?" }) {
			for (SafetyWarning chip : conditionMediated(question, onOrders("Lisinopril", "Benazepril"))) {
				String detail = chip.getDetail();
				for (String order : Arrays.asList("Lisinopril", "Benazepril")) {
					String named = DrugSafetyValidator.ACTIVE_ORDER_NOUN + " " + order;
					assertEquals(detail.indexOf(named), detail.lastIndexOf(named), question + ": " + detail);
				}
				assertEquals(new LinkedHashSet<String>(chip.namedPartners()).size(), chip.namedPartners().size(),
					question + ": " + chip.namedPartners());
			}
		}
	}

	/**
	 * One prescription, one name, across chip TYPES (#339, ADR Decision 110): every active order a
	 * {@code condition-mediated} chip names is a name the response's interaction chips print, so a
	 * combination prescription is never named by its display on one chip and by a constituent on another.
	 * Held here, where the arm is switched on, over the two shipped-knowledge-base arrangements
	 * {@code OneOrderNameAcrossOneResponseTest} was written about; reverting the partner name to the entry
	 * rung reddens the combination case.
	 */
	private static void assertNamedAsTheInteractionChipsNameThem(List<SafetyWarning> all) {
		List<SafetyWarning> derived = DrugReferenceTestSupport.ofType(all, TYPE);
		assertFalse(derived.isEmpty(), "precondition: the arrangement must raise a derived chip, or this"
				+ " pins nothing: " + DrugReferenceTestSupport.details(all));
		// The names the interaction chips give an active order: a rule chip's namedPartners (a collapsed
		// chip lists several after one noun), and the name a class chip prints after the noun.
		List<SafetyWarning> interaction = DrugReferenceTestSupport.ofType(all, SafetyWarning.TYPE_INTERACTION);
		List<String> named = DrugReferenceTestSupport.namedPartners(interaction);
		StringBuilder printed = new StringBuilder();
		for (SafetyWarning chip : interaction) {
			printed.append(chip.getDetail()).append('\n');
		}
		for (SafetyWarning warning : derived) {
			for (String partner : warning.namedPartners()) {
				assertTrue(named.contains(partner)
						|| printed.indexOf(DrugSafetyValidator.ACTIVE_ORDER_NOUN + " " + partner + " ") >= 0
						|| printed.indexOf(DrugSafetyValidator.ACTIVE_ORDER_NOUN + " " + partner + "\n") >= 0,
					"a condition-mediated chip names " + partner + " where the interaction chips name " + named
							+ " and print " + printed + ": " + warning.getDetail());
			}
		}
	}

	@Test
	public void aCombinationPrescriptionIsNamedAsTheInteractionChipsBesideItNameIt() {
		String display = "Lisinopril / Hydrochlorothiazide";
		java.util.Set<String> codes = DrugReferenceTestSupport.set("C09BA03", "C03AA03");
		PatientClinicalContext chart = shippedService().withReferenceNames(DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set(display), codes, null, null, Arrays.asList(DrugReferenceTestSupport
					.activeOrder("order-combination", display, DrugReferenceTestSupport.set(display), codes))));

		assertNamedAsTheInteractionChipsNameThem(DrugReferenceTestSupport.validator(shippedService()).validate("",
			"Can I give her lisinopril and amiodarone?", chart));
	}

	@Test
	public void theNamingTicketsOwnArrangementNamesEachOrderAsTheInteractionChipsDo() {
		String[] orders = { "Celecoxib", "Diclofenac", "Ibuprofen", "Dexamethasone", "Prednisone", "Budesonide",
			"Methylprednisolone" };
		List<PatientClinicalContext.ActiveDrugOrder> active = new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		java.util.Set<String> codes = new LinkedHashSet<String>();
		for (String order : orders) {
			PatientClinicalContext.ActiveDrugOrder one = DrugReferenceTestSupport.activeOrderFor(shippedService(), order);
			active.add(one);
			codes.addAll(one.getAtcCodes());
		}
		PatientClinicalContext chart = shippedService().withReferenceNames(DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set(orders), codes, null, null, active));

		assertNamedAsTheInteractionChipsNameThem(DrugReferenceTestSupport.validator(shippedService()).validate("",
			"Can I give her hydrocortisone?", chart));
	}
}
