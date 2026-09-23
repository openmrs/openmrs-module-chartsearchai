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

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

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
 */
public class ConditionMediatedFindingTest {

	private static final String TYPE = "condition-mediated";

	private static final String DISCLAIMER = "not a DDInter pairwise rating";

	private static DrugReferenceService shipped;

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
}
