/*
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * What the shipped knowledge base's alias vocabulary is SHAPED like, measured through the real parser
 * and the real accessor — the figure ADR Decision 68 quotes when it declines to harvest a dictionary's
 * every synonym into that vocabulary (issue #353).
 *
 * <p><b>Why a test and not a script.</b> {@code CLAUDE.md} forbids re-expressing a production predicate
 * in a script that measures the knowledge base for a figure that will be quoted: this one would have to
 * reimplement {@code DdiDrugReferenceSource.DrugRow.addAlias}'s trim, lower-case and de-duplication,
 * plus {@code DrugReference.setAliases}' own trim and {@code DrugReferenceValidity}'s blank-alias drop
 * and its repair of an entry no alias names. A first pass of this measurement was written as a script
 * and disagreed with an independent read in exactly the tail being investigated, which is the failure
 * that rule records. Every figure asserted below is produced by {@link DrugReference#getAliases()} over
 * {@code DrugReferenceTestSupport.shippedEntries()}, which is the real
 * {@code DdiDrugReferenceSource#load()}.
 *
 * <p><b>What the figures are OF, and what makes them safe to pin.</b> They are properties of the
 * dataset this module SHIPS, so they move when that dataset is refreshed and are meant to — a refresh
 * that changes them is a change to the argument Decision 68 rests on and should be read, not silently
 * absorbed. That is why the short vocabulary is asserted as the LIST rather than as a count: a count
 * going from 7 to 8 says nothing, while a new member says which word joined and whether it is still a
 * substance name.
 */
public class ShippedAliasVocabularyTest {

	/** Five characters, because that is the band the dictionary synonyms Decision 68 declines
	 *  concentrate in ({@code alert}, {@code dos}, {@code pas}, {@code dt}, {@code ascot}). Nothing in
	 *  production reads it — {@code DrugReference.matchesText} has no length floor, which is the whole
	 *  reason those synonyms are a hazard — so it is a measurement boundary and not a rule. */
	private static final int SHORT = 5;

	private static Set<String> distinctAliases() {
		Set<String> distinct = new LinkedHashSet<String>();
		for (DrugReference entry : DrugReferenceTestSupport.shippedEntries()) {
			for (String alias : entry.getAliases()) {
				if (alias != null) {
					distinct.add(alias);
				}
			}
		}
		return distinct;
	}

	/**
	 * The vocabulary's SHORT tail, which is what the argument turns on: every one of these is a
	 * substance in its own right, so nothing in the shipped alias set is a word that ordinary clinical
	 * prose carries in another sense. That is the property harvesting a dictionary's every synonym
	 * would end — measured on the 3.7.1 reference-application demo dictionary (2026-09-02, a raw
	 * {@code SELECT} over {@code concept_name}), the 171 bridged CIEL concepts it carries publish 1519
	 * distinct non-voided names, 103 of them five characters or shorter, among them {@code alert},
	 * {@code dos}, {@code pas}, {@code dt}, {@code abc}, {@code arret}, {@code ascot}, {@code mabel},
	 * {@code linde} and the French fully specified name {@code 73702}.
	 *
	 * <p>Asserted as the list, in sorted order, so a knowledge-base refresh reports WHICH word joined
	 * rather than that a number moved.
	 */
	@Test
	public void everyShortAliasTheShippedVocabularyCarriesIsItselfASubstance() {
		List<String> shortAliases = new ArrayList<String>();
		for (String alias : distinctAliases()) {
			if (alias.length() <= SHORT) {
				shortAliases.add(alias);
			}
		}
		Collections.sort(shortAliases);

		assertEquals(java.util.Arrays.asList("clove", "hemin", "iron", "kava", "opium", "urea", "yeast"),
			shortAliases,
			"every alias of " + SHORT + " characters or fewer must be a substance name — the property"
					+ " ADR Decision 68 declines to give up, and a new member here is a change to that"
					+ " argument rather than a number to update");
	}
}
