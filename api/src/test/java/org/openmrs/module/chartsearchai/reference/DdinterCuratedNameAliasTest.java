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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The knowledge base's curated names beside its brand names (issue #591, ADR Decision 183): {@code international_names}
 * (schema 1.4, openmrs-ddi-knowledge-base issue #8 — Paracetamol under acetaminophen) and {@code abbreviations} (schema
 * 1.5 — TDF, 3TC, AZT). A clinician who asks "Can I give her TDF?" or "Is paracetamol safe for her?" names the drug as
 * the knowledge base now carries it, and the question must resolve it. The fixture is a verbatim slice of a schema 1.5
 * build.
 */
public class DdinterCuratedNameAliasTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-curated-name-aliases.json";

	private static List<String> namesResolvedBy(String question) throws IOException {
		List<String> names = new ArrayList<String>();
		for (DrugReference ref : DrugReferenceTestSupport.ddiFixtureService(FIXTURE).findImpliedByQuery(question)) {
			names.add(ref.getName());
		}
		Collections.sort(names);
		return names;
	}

	@Test
	public void anAbbreviationInTheQuestionResolvesItsIngredient() throws IOException {
		assertEquals(Collections.singletonList("Tenofovir disoproxil"), namesResolvedBy("Can I give her TDF?"));
		assertEquals(Collections.singletonList("Lamivudine"), namesResolvedBy("Can I give her 3TC?"));
		assertEquals(Collections.singletonList("Zidovudine"), namesResolvedBy("Is ZDV safe for her?"));
	}

	@Test
	public void anInternationalNameInTheQuestionResolvesItsIngredient() throws IOException {
		assertEquals(Collections.singletonList("Acetaminophen"), namesResolvedBy("Is paracetamol safe for her?"));
	}

	@Test
	public void aQuestionAboutDrugDrugInteractionsResolvesNoDrug() throws IOException {
		// "DDI" is not didanosine's alias (the knowledge base leaves "ddI" out on purpose); nothing here may resolve it.
		assertTrue(namesResolvedBy("Any DDIs?").isEmpty(), "was: " + namesResolvedBy("Any DDIs?"));
	}
}
