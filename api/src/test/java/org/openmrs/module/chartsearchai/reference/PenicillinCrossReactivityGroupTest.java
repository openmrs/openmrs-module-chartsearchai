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
 * The bundled Penicillins cross-reactivity group (ADR Decision 229): an allergy recorded to one penicillin is
 * cross-reactive with every penicillin, and with no cephalosporin. ATC files the penicillins in four level-4 subgroups —
 * extended-spectrum (J01CA), beta-lactamase-sensitive (J01CE), beta-lactamase-resistant (J01CF) and combinations with a
 * beta-lactamase inhibitor (J01CR) — so on the 3.7.1 standalone a recorded "Penicillin G" allergy raised nothing against
 * amoxicillin, and an amoxicillin-clavulanate allergy nothing against penicillin V. Over the bundled groups file and a
 * verbatim slice of the shipped knowledge base.
 */
public class PenicillinCrossReactivityGroupTest extends BaseModuleContextSensitiveTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-penicillin-family.json";

	@Test
	public void aPenicillinGAllergyIsCrossReactiveWithAmoxicillin() throws IOException {
		assertEquals(Collections.singletonList("Amoxicillin is in the same cross-reactivity group (Penicillins) as the "
				+ "patient's allergy to Benzylpenicillin (penicillin g) — possible cross-reactivity"),
			contraindicationsOf("Can I give him amoxicillin?", "penicillin g"));
	}

	@Test
	public void aPenicillinGAllergyIsCrossReactiveWithCloxacillin() throws IOException {
		assertEquals(Collections.singletonList("Cloxacillin is in the same cross-reactivity group (Penicillins) as the "
				+ "patient's allergy to Benzylpenicillin (penicillin g) — possible cross-reactivity"),
			contraindicationsOf("Can I give him cloxacillin?", "penicillin g"));
	}

	@Test
	public void anAmoxicillinAllergyIsCrossReactiveWithPenicillinV() throws IOException {
		assertEquals(Collections.singletonList("Phenoxymethylpenicillin (penicillin v) is in the same cross-reactivity group "
				+ "(Penicillins) as the patient's allergy to Amoxicillin — possible cross-reactivity"),
			contraindicationsOf("Is penicillin V safe for her?", "amoxicillin"));
	}

	@Test
	public void aPenicillinAllergyIsNotCrossReactiveWithACephalosporin() throws IOException {
		assertEquals(Collections.<String> emptyList(), contraindicationsOf("Can I give him ceftriaxone?", "penicillin g"));
	}

	private static List<String> contraindicationsOf(String question, String allergen) throws IOException {
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set(), null,
			DrugReferenceTestSupport.set(allergen), null, Collections.<PatientClinicalContext.ActiveDrugOrder> emptyList());
		return DrugReferenceTestSupport.details(DrugReferenceTestSupport.contraindications(
			DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
					.validate("", question, context)));
	}
}
