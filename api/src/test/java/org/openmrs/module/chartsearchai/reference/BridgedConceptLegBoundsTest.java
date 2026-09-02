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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The two bounds on issue #353's bridged-concept leg, each shown NARROWING a non-empty answer —
 * `DrugReferenceService.findByBridgedConcept` is the entries the dataset's bridge files under a
 * concept, INTERSECTED with the ones `findImpliedByDrugName` answers for the name the bridge records
 * for it. ADR Decision 68 rests on both, and each rules out a different wrong leg.
 *
 * <p><b>Over the SHIPPED knowledge base and deliberately not over a slice of it.</b> Both bounds turn
 * on {@code nameMatchStrength}'s ranking, which is a property of the WHOLE dataset — the strongest
 * claimant of a name is decided against every entry there is. Measured while writing this file: an
 * eight-row verbatim slice carrying exactly the rows named below answers differently for two of the
 * three cases, because rows the slice omits are what claim those names most strongly. A slice is the
 * right instrument for chip TEXT and the wrong one here, so a knowledge-base refresh that moves any of
 * these is a change to Decision 68's argument and is meant to be read rather than absorbed.
 *
 * <p>These cases assert WHICH entries resolve and never any rendered text, so the refresh sensitivity
 * they carry is the one that is worth carrying.
 */
public class BridgedConceptLegBoundsTest {

	/** CIEL 85300, which the bridge records as {@code Trastuzumab}. */
	private static final String TRASTUZUMAB_CONCEPT = "85300AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	/** CIEL 78482, which the bridge records as {@code Ketorolac tromethamine}. */
	private static final String KETOROLAC_CONCEPT = "78482AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	/** CIEL 166154, which the bridge records as {@code Moderna COVID-19 vaccine}. */
	private static final String MODERNA_CONCEPT = "166154AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	/** Parsed once: this is the real 19 MB knowledge base through the real
	 *  {@code DdiDrugReferenceSource#load()}, and every case here asks the same dataset. */
	private static DrugReferenceService service;

	@BeforeAll
	public static void loadTheShippedKnowledgeBase() {
		service = DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.shippedEntries());
	}

	private static List<String> resolved(String conceptUuid) {
		List<String> names = new ArrayList<String>();
		for (DrugReference entry : service.findByBridgedConcept(conceptUuid,
			new HashMap<Object, Set<Object>>())) {
			names.add(entry.getName());
		}
		return names;
	}

	private static List<String> bridgedUnder(String conceptUuid) {
		List<String> names = new ArrayList<String>();
		for (DrugReference entry : service.getAll()) {
			for (DrugReference.BridgedConcept bridged : entry.getBridgedConcepts()) {
				if (conceptUuid.equals(bridged.getConceptUuid())) {
					names.add(entry.getName());
					break;
				}
			}
		}
		return names;
	}

	/**
	 * <b>The RANKING bound.</b> One bridged concept can be filed on several substances, and only one of
	 * them is what the concept's own recorded name names. An unranked identity leg would make an order
	 * for {@code Trastuzumab} the patient's own order for {@code Trastuzumab deruxtecan} and
	 * {@code Trastuzumab emtansine} as well — three drugbank ids sharing no ATC code — and two of this
	 * leg's consumers are SUPPRESSIONS, where a superset removes a warning with no chip and no log line
	 * to notice it by.
	 *
	 * <p>The bridged set is asserted first, or the case would pass for a leg that resolved nothing and
	 * for a knowledge base that had stopped filing the other two there.
	 */
	@Test
	public void aBridgedConceptFiledOnSeveralSubstancesResolvesOnlyTheOneItsNameNames() {
		assertEquals(
			Arrays.asList("Trastuzumab", "Trastuzumab deruxtecan", "Trastuzumab emtansine"),
			bridgedUnder(TRASTUZUMAB_CONCEPT),
			"the premise: the bridge files three substances under this concept");

		assertEquals(Arrays.asList("Trastuzumab"), resolved(TRASTUZUMAB_CONCEPT),
			"and its recorded name claims exactly one of them");
	}

	/**
	 * <b>The INTERSECTION bound.</b> The ranked resolution of a bridge's own name can reach a substance
	 * the bridge files under no such concept: {@code Ketorolac tromethamine} names {@code Tromethamine}
	 * as well, which is a different drug. Ranking alone would admit it.
	 */
	@Test
	public void theLegAdmitsNoEntryTheBridgeDoesNotFileUnderThatConcept() {
		List<String> ranked = new ArrayList<String>();
		for (DrugReference entry : service.findImpliedByDrugName("Ketorolac tromethamine")) {
			ranked.add(entry.getName());
		}
		assertTrue(ranked.contains("Tromethamine"),
			"the premise: the ranked resolution of the bridge's own name reaches a substance the bridge"
					+ " files elsewhere, or this case passes for a leg with no intersection at all,"
					+ " was: " + ranked);

		assertEquals(Arrays.asList("Ketorolac", "Ketorolac (ophthalmic)"),
			resolved(KETOROLAC_CONCEPT), "only the entries the bridge files under this concept");
	}

	/**
	 * The two bounds COMPOSING, on one of the stray cross-walk links ADR Decision 36 records: CIEL
	 * 166154 is bridged as {@code Moderna COVID-19 vaccine} onto six Pfizer/Tozinameran rows and onto
	 * no Moderna row at all. The leg inherits the bridge's defects by design — the correct rows and the
	 * stray ones are the same field — so what this pins is that this one does not survive the bounds.
	 *
	 * <p>Nothing is claimed about the other stray links. Measured through this same accessor over all
	 * 4251 bridged concepts of the shipped knowledge base, exactly one resolves to nothing, and it is
	 * this one; the bridged set is asserted here so the zero is provably a narrowing rather than an
	 * absence.
	 */
	@Test
	public void aStrayLinkWhoseRecordedNameNamesNoneOfItsRowsResolvesNothing() {
		assertEquals(6, bridgedUnder(MODERNA_CONCEPT).size(),
			"the premise: the bridge files this concept on rows, was: " + bridgedUnder(MODERNA_CONCEPT));

		assertEquals(Collections.<String> emptyList(), resolved(MODERNA_CONCEPT),
			"and its recorded name names none of them");
	}

	/**
	 * How often each bound bites over the whole shipped dataset — the figure ADR Decision 68 quotes,
	 * produced HERE through the production accessors rather than by a script, because reproducing
	 * {@code findImpliedByDrugName}'s ranking outside them is exactly what {@code CLAUDE.md} forbids
	 * for a quoted figure.
	 *
	 * <p>Asserted rather than printed, so the decision's numbers cannot go stale unnoticed: a refresh
	 * that moves them reddens this and says which way.
	 */
	@Test
	public void bothBoundsNarrowRealConceptsOfTheShippedKnowledgeBase() {
		Map<String, String> nameByUuid = new java.util.LinkedHashMap<String, String>();
		Map<String, List<DrugReference>> bridged = new java.util.LinkedHashMap<String, List<DrugReference>>();
		for (DrugReference entry : service.getAll()) {
			for (DrugReference.BridgedConcept concept : entry.getBridgedConcepts()) {
				nameByUuid.put(concept.getConceptUuid(), concept.getConceptName());
				List<DrugReference> filed = bridged.get(concept.getConceptUuid());
				if (filed == null) {
					filed = new ArrayList<DrugReference>();
					bridged.put(concept.getConceptUuid(), filed);
				}
				filed.add(entry);
			}
		}
		Map<Object, Set<Object>> cache = new HashMap<Object, Set<Object>>();
		int rankingNarrows = 0;
		int intersectionNarrows = 0;
		int resolvesNothing = 0;
		for (Map.Entry<String, List<DrugReference>> concept : bridged.entrySet()) {
			List<DrugReference> answer = service.findByBridgedConcept(concept.getKey(), cache);
			if (answer.size() < concept.getValue().size()) {
				rankingNarrows++;
			}
			if (answer.isEmpty()) {
				resolvesNothing++;
			}
			Set<DrugReference> filed = Collections
					.newSetFromMap(new java.util.IdentityHashMap<DrugReference, Boolean>());
			filed.addAll(concept.getValue());
			for (DrugReference ranked : service
					.findImpliedByDrugName(nameByUuid.get(concept.getKey()), cache)) {
				if (!filed.contains(ranked)) {
					intersectionNarrows++;
					break;
				}
			}
		}

		assertEquals(4251, bridged.size(), "distinct bridged concepts in the shipped knowledge base");
		assertEquals(16, rankingNarrows,
			"concepts where the ranking drops an entry the bridge files there");
		assertEquals(243, intersectionNarrows,
			"concepts where the ranked resolution of the bridge's name reaches an entry it does not file"
					+ " there");
		assertEquals(1, resolvesNothing, "concepts the two bounds together leave resolving nothing");
	}
}
