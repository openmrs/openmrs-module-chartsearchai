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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Exercises the real {@link DdiDrugReferenceSource#load()} path. With no OpenMRS
 * context available it falls back to the bundled {@code /chartsearchai/ddi-knowledge-base.json}
 * sample — the production default — so this runs the real parse against a real dataset.
 */
public class DdiDrugReferenceSourceTest {

	private static final String SEVERITY = "Major Moderate Minor Unknown";

	private DrugReference entry(String name) {
		return new DdiDrugReferenceSource().load().stream()
				.filter(r -> name.equalsIgnoreCase(r.getName())).findFirst().orElse(null);
	}

	@Test
	public void loadsBundledDatasetViaClasspathFallback() {
		List<DrugReference> all = new DdiDrugReferenceSource().load();
		assertFalse(all.isEmpty(), "bundled DDI dataset should load via the classpath fallback");
		assertNotNull(entry("Warfarin"), "dataset should contain the Warfarin entry");
	}

	@Test
	public void entriesCarryInteractionsWithSeverityNotes() {
		DrugReference warfarin = entry("Warfarin");
		assertNotNull(warfarin);
		assertFalse(warfarin.getInteractions().isEmpty(),
				"the DDInter source should carry drug-drug interaction rules");
		DrugReference.Interaction nsaid = warfarin.getInteractions().stream()
				.filter(i -> "ibuprofen".equals(i.getToken())).findFirst().orElse(null);
		assertNotNull(nsaid, "Warfarin should list an interaction with ibuprofen");
		assertNotNull(nsaid.getNote(), "the interaction should carry a note");
		String severityWord = nsaid.getNote().split("[ .]", 2)[0];
		assertTrue(SEVERITY.contains(severityWord),
				"the note should begin with the DDInter severity, was: " + nsaid.getNote());
	}

	@Test
	public void v1ScopeIsInteractionsOnly() {
		// DDI-only in V1: no dosing bands, no drug-allergy/condition contraindications.
		DrugReference warfarin = entry("Warfarin");
		assertNotNull(warfarin);
		assertTrue(warfarin.getAgeBands().isEmpty(), "V1 carries no dosing bands");
		assertTrue(warfarin.getContraindications().isEmpty(), "V1 carries no contraindications");
	}

	@Test
	public void aliasesIncludeCielConceptNames() {
		// The CIEL bridge contributes brand/combination names as aliases — a real win
		// for question-driven matching against the dictionary the chart uses.
		boolean anyCombinationAlias = new DdiDrugReferenceSource().load().stream()
				.flatMap(r -> r.getAliases().stream())
				.anyMatch(a -> a.contains("/"));
		assertTrue(anyCombinationAlias,
				"aliases should include CIEL concept names (e.g. combination products)");
	}
}
