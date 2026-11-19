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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * A brand the knowledge base files under two rows this module counts as two substances makes ONE
 * prescription resolve twice, and the restating-existing-therapy skip could see only one of the two
 * — so a patient on a single order was told the drug she was asking about duplicates a second drug
 * she is not on.
 *
 * <p><b>The defect</b>, reported in review of the {@code brand_names} read (pull request #392). The
 * brand is an ALIAS of both rows, so both claim the order's name at exactly the same rank and
 * {@link DrugReference#nameMatchStrength}'s display-name rung — which is what used to hide DDInter's
 * shared-RxCUI rows behind the generic spelling — has nothing to break the tie with. Both substances
 * therefore reach {@code DrugSafetyValidator.addPartnersForUnmappedOrders}, each becoming a
 * co-medication holding its own substance and no other, and a question about either one is then
 * related to the other:
 *
 * <pre>Esomeprazole is in the same ATC class (A02BC) as active order Omeprazole — possible duplicate
 * therapy</pre>
 *
 * <p>for a chart whose only prescription is {@code Nexium 40mg}. Through the real injector that
 * sentence reaches the prompt as a citable {@code safety_finding} carrying the withholding clause,
 * which is why it is not merely a redundant chip.
 *
 * <p><b>What separates it from a real combination</b>, and the whole of the fix: an order name that
 * SPELLS its substances — {@code Metronidazole and secnidazole} — names each of them, so the two are
 * constituents of one tablet and a question about one really is duplicated by the other. A brand
 * names neither, so the module knows the prescription is one of them and cannot say which. That is
 * the distinction {@code DrugReferenceService.substancesNamedByBridge} already draws for the
 * dictionary bridge, on this very pair of substances (issue #353), and this applies it to the leg
 * that reads an order's own recorded names.
 *
 * <p>Every case drives the real {@code DrugSafetyValidator.validate} and the real
 * {@code DrugReferenceInjector.injectRecords} over the dataset the module SHIPS, because the
 * population is the shipped knowledge base's own brand table. Nothing driven over the shipped file
 * asserts rendered chip or record TEXT for that reason ({@code DrugReferenceTestSupport.shippedEntries()}'
 * own rule): those assertions are emptiness, non-emptiness, and what the production accessors resolve a
 * name to. The one case that reads a rendered sentence is the combination control, and it runs over a
 * curated fixture.
 *
 * <p><b>The Nexium pair the review reported is no longer one of them.</b> It was the knowledge base filing its Omeprazole
 * row under esomeprazole's RxCUI, which ADR Decision 662 corrected upstream: Nexium is now esomeprazole's brand alone, so a
 * Nexium prescription is that one substance and a question proposing esomeprazole is told she already takes it. That is
 * pinned below beside the three families that still share a brand.
 */
public class AmbiguousBrandNamedOrderTest {

	/**
	 * Three families the shipped knowledge base files a brand under two substances of — three of the
	 * four the review drove, each raising a duplicate-therapy chip against its own prescription and none
	 * on {@code main}; the fourth, Nexium, is one substance's since ADR Decision 662. Row 0 is the
	 * order's display; row 1 the question; row 2 the substance the question asks about; row 3 the sibling
	 * substance the chip named as a second active order.
	 */
	private static final String[][] AMBIGUOUS_BRANDS = {
			{ "Neurontin 300mg", "Can I give her gabapentin?", "Gabapentin", "Gabapentin enacarbil" },
			{ "Tricor 145mg", "Can I give her fenofibrate?", "Fenofibrate", "Fenofibric acid" },
			{ "Nasonex", "Can I give her mometasone?", "Mometasone", "Mometasone furoate" } };

	/** A third gabapentinoid, in the same level-4 subgroup (N02BF) as both rows {@code Neurontin} resolves
	 *  and neither of them — what the arm is asked about to show it is live on the very chart the cases
	 *  below find silent. */
	private static final String THIRD_DRUG_QUESTION = "Can I give her pregabalin?";

	private static DrugSafetyValidator shippedValidator() {
		return DrugReferenceTestSupport.validator(shippedService());
	}

	private static DrugReferenceService shippedService() {
		return DrugReferenceTestSupport.serviceWithGroups(DrugReferenceTestSupport.shippedEntries());
	}

	/** One active drug order whose concept a dictionary mapped to nothing — the shape
	 *  {@code addPartnersForUnmappedOrders} exists for, and the one a brand-named prescription has
	 *  wherever the dictionary does not carry the brand. */
	private static PatientClinicalContext unmappedOrder(String display) {
		Set<String> names = DrugReferenceTestSupport.set(display);
		return DrugReferenceTestSupport.ctx(60, null, names, null, null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-uuid-392", display, names,
						Collections.<String> emptySet())));
	}

	@Test
	public void eachOfThoseBrandsResolvesTwoSubstancesInOneClassAndSpellsNeitherOfThem() {
		// The premise every case below rests on, stated through the production resolution rather than
		// read off the knowledge-base file. Three parts, and the emptiness asserted further down means
		// nothing without all three: the order's own recorded name implies exactly two substances; they
		// are the two the review reported, so the case has not drifted onto some other pair; they share
		// a level-4 subgroup, so the class arm has a relationship to find and is not silent for want of
		// one; and the display spells neither name, which is why nothing can tell the module which of
		// the two the prescription is. Repair those rows upstream and the cases below go green whichever
		// skip is in force — which is what ADR Decision 662 did to the Nexium row this list used to open with.
		DrugReferenceService service = shippedService();
		for (String[] row : AMBIGUOUS_BRANDS) {
			List<DrugReference> resolved = service.findImpliedByDrugName(row[0]);
			Set<Object> substances = new HashSet<Object>();
			Set<String> subgroupsOfSubject = null;
			Set<String> subgroupsOfSibling = null;
			for (DrugReference entry : resolved) {
				substances.add(entry.substanceGroupKey());
				if (row[2].equals(entry.getName())) {
					subgroupsOfSubject = entry.atcSubgroups();
				}
				if (row[3].equals(entry.getName())) {
					subgroupsOfSibling = entry.atcSubgroups();
				}
			}
			assertEquals(2, substances.size(), row[0] + " must resolve exactly two substances, was: "
					+ DrugReferenceTestSupport.names(resolved));
			assertNotNull(subgroupsOfSubject, row[0] + " must resolve the " + row[2] + " row, was: "
					+ DrugReferenceTestSupport.names(resolved));
			assertNotNull(subgroupsOfSibling, row[0] + " must resolve the " + row[3] + " row, was: "
					+ DrugReferenceTestSupport.names(resolved));
			Set<String> shared = new LinkedHashSet<String>(subgroupsOfSubject);
			shared.retainAll(subgroupsOfSibling);
			assertFalse(shared.isEmpty(), row[2] + " and " + row[3] + " must share a level-4 subgroup, or"
					+ " the class arm has nothing to relate and the silence below says nothing");
			assertFalse(row[0].toLowerCase().contains(row[2].toLowerCase()),
					row[0] + " must not spell " + row[2]);
			assertFalse(row[0].toLowerCase().contains(row[3].toLowerCase()),
					row[0] + " must not spell " + row[3]);
		}
	}

	@Test
	public void aBrandTwoSubstancesShareRaisesNothingAgainstThePrescriptionItIs() {
		DrugSafetyValidator validator = shippedValidator();
		for (String[] row : AMBIGUOUS_BRANDS) {
			assertEquals(Collections.<String> emptyList(),
					DrugReferenceTestSupport.details(
							validator.validate("", row[1], unmappedOrder(row[0]))),
					"a patient on " + row[0] + " alone has ONE prescription, so relating " + row[2]
							+ " to " + row[3] + " as a second active order states a prescription her chart"
							+ " does not record");
		}
	}

	@Test
	public void aBrandTheDataFilesUnderOneSubstanceIsThatSubstancesPrescription() {
		// Nexium, since ADR Decision 662: the brand resolves esomeprazole alone, so proposing esomeprazole to a patient
		// on it states that she already takes it, and relates no second substance as another active order of hers.
		DrugReferenceService service = shippedService();
		List<DrugReference> resolved = service.findImpliedByDrugName("Nexium 40mg");
		Set<Object> substances = new HashSet<Object>();
		for (DrugReference entry : resolved) {
			substances.add(entry.substanceGroupKey());
			assertEquals("Esomeprazole", entry.getName(), "Nexium 40mg resolved " + DrugReferenceTestSupport.names(resolved));
		}
		assertEquals(1, substances.size(), "Nexium 40mg resolved " + DrugReferenceTestSupport.names(resolved));

		List<SafetyWarning> chips = shippedValidator().validate("", "Can I give her esomeprazole?",
				unmappedOrder("Nexium 40mg"));

		assertFalse(chips.isEmpty(), "proposing the drug her one prescription is must say she already takes it");
		for (SafetyWarning chip : chips) {
			assertTrue(chip.statesAProposedDrugIsAlreadyOrdered(),
					"every finding is the one that she already takes it, was: " + DrugReferenceTestSupport.details(chips));
		}
		assertEquals(Collections.<String> emptyList(), DrugReferenceTestSupport.classChipDetails(chips),
				"no second substance is related to her one prescription");
	}

	@Test
	public void andTheArmIsLiveOnThatSameChart() {
		// What makes the emptiness above mean something: the same unmapped brand-named order, asked
		// about a drug that IS a different substance in the same subgroup, still relates them.
		List<SafetyWarning> chips = shippedValidator()
				.validate("", THIRD_DRUG_QUESTION, unmappedOrder(AMBIGUOUS_BRANDS[0][0]));

		assertFalse(chips.isEmpty(),
				"the class arm must still reach a third substance of that subgroup");
	}

	@Test
	public void noSuchFindingReachesThePromptAsCitableEvidence() {
		// The cost of the defect, and why a redundant chip is not all it was: renderFinding copies the
		// chip's sentence into a safety_finding record carrying the withholding clause, so the model
		// reads a prescription the chart does not record as this module's own established fact.
		DrugReferenceService service = shippedService();
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(), unmappedOrder(AMBIGUOUS_BRANDS[0][0]),
				AMBIGUOUS_BRANDS[0][1]);

		assertEquals(Collections.<String> emptyList(), DrugReferenceTestSupport.findingTexts(chart),
				"no finding may be injected for a drug the patient's one prescription may itself be");
		assertFalse(DrugReferenceTestSupport.injectedReferences(chart).isEmpty(),
				"while the asked-about drug's own reference record is still injected");
	}

	@Test
	public void anUnmappedCombinationSpellingBothItsSubstancesStillReportsTheOther() throws Exception {
		// The other side of the rule, and the case that keeps the fix from being a blanket "one order
		// cannot duplicate itself" suppression. This order's name NAMES both nitroimidazoles it
		// contains, so they are two constituents of one tablet rather than two readings of one drug,
		// and a question about either really is duplicated by the other. Unmapped, so the partners are
		// built by the same name leg the cases above go through — the code walk never runs.
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport
						.fixtureEntries(DrugReferenceTestSupport.PARTIAL_ORDER_COVERAGE)));
		// The premise, and the one that separates this case from the four above: this display spells
		// both substance names, where a brand spells neither.
		String display = "Metronidazole and secnidazole";
		Set<Object> substances = new HashSet<Object>();
		for (DrugReference entry : DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(
						DrugReferenceTestSupport.PARTIAL_ORDER_COVERAGE))
				.findImpliedByDrugName(display)) {
			substances.add(entry.substanceGroupKey());
		}
		assertEquals(2, substances.size(), "this display must resolve two substances, as a brand does");

		List<String> aboutMetronidazole = DrugReferenceTestSupport.classChipDetails(
				validator.validate("", "Is it safe to give metronidazole?", unmappedOrder(display)));
		List<String> aboutSecnidazole = DrugReferenceTestSupport.classChipDetails(
				validator.validate("", "Is it safe to give secnidazole?", unmappedOrder(display)));

		assertEquals(1, aboutMetronidazole.size(),
				"the secnidazole half is a different substance in the same subgroup, was: "
						+ aboutMetronidazole);
		assertTrue(aboutMetronidazole.get(0).contains("as active order Secnidazole"),
				"was: " + aboutMetronidazole);
		assertEquals(1, aboutSecnidazole.size(),
				"and the rule is symmetric, was: " + aboutSecnidazole);
		assertTrue(aboutSecnidazole.get(0).contains("as active order Metronidazole"),
				"was: " + aboutSecnidazole);
	}

	@Test
	public void aBrandNamedOrderTheDictionaryDIDMapIsGovernedByItsCodeAndNotByThisRule() {
		// The bound, stated rather than left to be rediscovered. This rule reads an order's NAME, so it
		// reaches only the orders no dictionary classified. The same prescription mapped to a code the
		// knowledge base carries is resolved by the code walk, which never asks what the name implies —
		// the control that puts the defect on the name leg rather than on the class arm. Nexium mapped to
		// esomeprazole's own code: since ADR Decision 662 its name is that one substance's too, so the one
		// finding is that she already takes the drug proposed, and no class arm relates a second substance.
		Set<String> names = DrugReferenceTestSupport.set("Nexium 40mg");
		Set<String> codes = new LinkedHashSet<String>(Collections.singleton("A02BC05"));
		PatientClinicalContext mapped = DrugReferenceTestSupport.ctx(60, null, names, codes, null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-uuid-392b", "Nexium 40mg",
						names, codes)));

		List<SafetyWarning> chips = shippedValidator().validate("", "Can I give her esomeprazole?", mapped);
		assertEquals(Collections.<String> emptyList(), DrugReferenceTestSupport.classChipDetails(chips),
				"a mapped order is classified by its code, and that code is the one substance's");
		assertFalse(chips.isEmpty(), "her one prescription is the drug proposed, so that is said");
		for (SafetyWarning chip : chips) {
			assertTrue(chip.statesAProposedDrugIsAlreadyOrdered(),
					"no finding but that she already takes it, was: " + DrugReferenceTestSupport.details(chips));
		}
	}
}
