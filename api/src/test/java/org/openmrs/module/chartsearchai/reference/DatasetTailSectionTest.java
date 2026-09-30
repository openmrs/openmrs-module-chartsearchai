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

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Issue #564 — where the injected record shows anything patient-specific, its one dataset-tail
 * representative is a drug the chart says nothing about, and it used to be rendered as the last item
 * of the SAME {@code Interactions:} list as her own partners. Captured live on the 3.7.1 standalone
 * (2026-09-30, {@code main} @ {@code e9189c77}): a patient whose active orders include lidocaine,
 * metoclopramide, neomycin and tiotropium, asked whether to start clarithromycin, got
 * {@code Interactions: lidocaine (Unknown …); metoclopramide (Unknown); neomycin (Unknown);
 * tiotropium (Unknown); ivosidenib (Major).}, and the model answered with all five as "the following
 * interactions" — so the answer's only rated interaction was a Major with a drug she does not take.
 *
 * <p>The tail is kept (ADR Decision 66's breadth), under its own lead, so the partition
 * {@code renderTier} already computes is visible to the model. ADR Decision 132.
 *
 * <p>Every case runs the real {@code injectRecords} over the real {@link DdiDrugReferenceSource}
 * parse — the pinned excerpt, and for the ticket's own regimen the shipped knowledge base — on the
 * no-context GP defaults (severity floor {@code minor}).
 */
public class DatasetTailSectionTest {

	private static final String TAIL_LEAD = DrugReferenceTestSupport.DATASET_TAIL_LEAD_IN_SECTION;

	/** The lowercased {@code Interactions:} section onward of the record rendered for {@code drug},
	 *  for a patient on {@code drugs}. */
	private static String interactionsFor(List<DrugReference> entries, String question, String drug,
			String... drugs) {
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(12, null,
				DrugReferenceTestSupport.set(drugs), null, null, null);
		PatientChart chart = DrugReferenceTestSupport.injector(DrugReferenceTestSupport.serviceWith(entries))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
		return DrugReferenceTestSupport.interactionsSectionOf(
				DrugReferenceTestSupport.referenceMappingNaming(chart, drug));
	}

	@Test
	public void theTicketsOwnRegimenListsOnlyHerPartnersAndPutsTheStrangerUnderItsOwnLead() {
		// The ticket's arrangement on the data the module ships. Stated as a property rather than as
		// record text, so a knowledge-base refresh that re-rates one of her pairs or reorders the
		// dataset cannot make it assert something else: every note of the Interactions list is headed
		// by one of her own drugs, and the one representative of everything else sits after the lead.
		List<String> hers = Arrays.asList("lidocaine", "metoclopramide", "neomycin", "tiotropium");
		String section = interactionsFor(DrugReferenceTestSupport.shippedEntries(),
				"Is it safe to start her on clarithromycin?", "Clarithromycin",
				"Lidocaine", "Metoclopramide", "Neomycin", "Tiotropium");

		int lead = section.indexOf(TAIL_LEAD);
		assertTrue(lead > 0, "the dataset tail must render under its own lead: " + section);
		assertEquals(lead, section.lastIndexOf(TAIL_LEAD), "and the lead is stated once: " + section);

		String list = section.substring("interactions: ".length(), lead);
		assertTrue(list.endsWith("."), "her list closes before the lead opens: " + section);
		assertEquals(hers, DrugReferenceTestSupport.noteHeads(list),
				"the list holds her partners, in order, and nothing else: " + section);

		String tail = section.substring(lead + TAIL_LEAD.length());
		List<String> tailHeads = DrugReferenceTestSupport.noteHeads(tail);
		assertEquals(1, tailHeads.size(), "one representative of everything else: " + section);
		assertFalse(hers.contains(tailHeads.get(0)), "and it is a drug she is not on: " + section);
		assertTrue(tail.endsWith(")."), "and it is the record's last note: " + section);
	}

	@Test
	public void theLeadIsTheWordsAModelReads() {
		// The literal, in the case the model reads it. Every other case here and in the classes whose
		// pins #564 moved reads the constant or a lowercased copy, so a reword would compare the
		// constant to itself; this is the one place a reword is visible.
		assertEquals(" Other interactions, not matched to this patient's active medications: ",
				DrugReferenceInjector.DATASET_TAIL_LEAD);
	}

	@Test
	public void aPromotedPartnerIsKeptApartFromTheTailToo() {
		// The same mixing with a rated partner of hers: the excerpt's Metformin x Warfarin Moderate is
		// promoted, and the representative behind it is lisinopril, which she is not on.
		String section = interactionsFor(DrugReferenceTestSupport.ddinterEntries(),
				"is it safe to give metformin?", "Metformin", "Warfarin");

		assertTrue(section.startsWith("interactions: warfarin (moderate. "),
				"her promoted partner leads the list: " + section);
		assertTrue(section.endsWith(")." + TAIL_LEAD + "lisinopril (moderate)."),
				"her list closes, and the stranger follows under its own lead: " + section);
	}

	@Test
	public void aRecordWithNothingPatientSpecificHasNoLead() {
		// The control: with no partner the chart names, the tail IS the record (issue #355's branch)
		// and there is nothing of hers to keep it apart from, so the record is what it was.
		String section = interactionsFor(DrugReferenceTestSupport.ddinterEntries(),
				"is it safe to give metformin?", "Metformin");

		assertTrue(section.startsWith("interactions: "), "the section still opens as before: " + section);
		assertEquals(-1, section.indexOf(TAIL_LEAD.trim()), "no lead where nothing is hers: " + section);
	}
}
