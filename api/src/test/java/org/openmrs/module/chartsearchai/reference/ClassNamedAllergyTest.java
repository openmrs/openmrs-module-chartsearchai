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

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * An allergy recorded by the NAME of a curated cross-reactivity group — CIEL's drug-class concept "Penicillins", a
 * clinician's free-text "NSAIDs" — is an allergy to that group's members (ADR Decision 231). Such a name resolves to no
 * substance, so on the public demo two patients recorded allergic to "Penicillins" were answered about amoxicillin with
 * no allergy finding, only that the allergy had not been checked (Decision 223's line); and on the 3.7.1 standalone
 * George Anderson's "NSAID" allergy was reported unchecked beside every drug. Over the bundled groups file and verbatim
 * slices of the shipped knowledge base.
 */
public class ClassNamedAllergyTest extends BaseModuleContextSensitiveTest {

	private static final String PENICILLINS = "chartsearchai-test/ddi-penicillin-family.json";

	private static final String NSAIDS = "chartsearchai-test/ddi-nsaid-branches.json";

	@Test
	public void anAllergyRecordedAsPenicillinsIsAnAllergyToAmoxicillin() throws IOException {
		assertEquals(Collections.singletonList("Amoxicillin is in the drug group (Penicillins) the patient has a recorded "
				+ "allergy to."), contraindicationsOf(PENICILLINS, "Can I give her amoxicillin?", "Penicillins"));
	}

	@Test
	public void anAllergyRecordedAsPenicillinsSaysNothingOfACephalosporin() throws IOException {
		assertEquals(Collections.<String> emptyList(),
			contraindicationsOf(PENICILLINS, "Can I give her ceftriaxone?", "Penicillins"));
	}

	@Test
	public void anAllergyRecordedAsNsaidsIsAnAllergyToDiclofenacAndNotToCelecoxib() throws IOException {
		assertEquals(Collections.singletonList("Diclofenac is in the drug group (NSAID) the patient has a recorded allergy "
				+ "to."), contraindicationsOf(NSAIDS, "Can I give her diclofenac?", "NSAIDs"));
		assertEquals(Collections.<String> emptyList(), contraindicationsOf(NSAIDS, "Can I give her celecoxib?", "NSAIDs"));
	}

	@Test
	public void anAllergyNamingAGroupIsNotReportedUnchecked() throws IOException {
		PatientClinicalContext context = context("Penicillins", "Moonflower extract");
		assertEquals(Collections.singletonList("moonflower extract"), DrugSafetyValidator.unresolvedDrugAllergens(
			DrugReferenceTestSupport.ddiFixtureService(PENICILLINS), context));
	}

	private static List<String> contraindicationsOf(String fixture, String question, String allergen) throws IOException {
		return DrugReferenceTestSupport.details(DrugReferenceTestSupport.contraindications(
			DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(fixture))
					.validate("", question, context(allergen))));
	}

	private static PatientClinicalContext context(String... allergens) {
		return DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set(), null,
			DrugReferenceTestSupport.set(allergens), null, Collections.<PatientClinicalContext.ActiveDrugOrder> emptyList());
	}
}
