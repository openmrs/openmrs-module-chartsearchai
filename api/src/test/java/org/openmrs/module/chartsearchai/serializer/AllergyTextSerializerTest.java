/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.serializer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Allergen;
import org.openmrs.AllergenType;
import org.openmrs.Allergy;
import org.openmrs.AllergyReaction;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseContextSensitiveTest;

public class AllergyTextSerializerTest extends BaseContextSensitiveTest {

	private AllergyTextSerializer serializer;

	@BeforeEach
	public void setUp() {
		serializer = new AllergyTextSerializer();
	}

	@Test
	public void toText_shouldSerializeCodedAllergy() {
		Concept codedAllergen = new Concept();
		codedAllergen.addName(new ConceptName("Penicillin", Context.getLocale()));
		Allergen allergen = new Allergen(AllergenType.DRUG, codedAllergen, null);

		Allergy allergy = new Allergy(new Patient(), allergen, null, null, null);

		String result = serializer.toText(allergy);
		assertTrue(result.contains("Allergy: Penicillin"));
		assertTrue(result.contains("(DRUG)"));
	}

	@Test
	public void toText_shouldSerializeNonCodedAllergy() {
		Allergen allergen = new Allergen(AllergenType.FOOD, null, "Shellfish");

		Allergy allergy = new Allergy(new Patient(), allergen, null, null, null);

		String result = serializer.toText(allergy);
		assertTrue(result.contains("Allergy: Shellfish"));
		assertTrue(result.contains("(FOOD)"));
	}

	@Test
	public void toText_shouldIncludeSeverity() {
		Concept codedAllergen = new Concept();
		codedAllergen.addName(new ConceptName("Aspirin", Context.getLocale()));
		Allergen allergen = new Allergen(AllergenType.DRUG, codedAllergen, null);

		Concept severity = new Concept();
		severity.addName(new ConceptName("Severe", Context.getLocale()));

		Allergy allergy = new Allergy(new Patient(), allergen, severity, null, null);

		String result = serializer.toText(allergy);
		assertTrue(result.contains("Severity: Severe"));
	}

	@Test
	public void toText_shouldIncludeReactions() {
		Concept codedAllergen = new Concept();
		codedAllergen.addName(new ConceptName("Penicillin", Context.getLocale()));
		Allergen allergen = new Allergen(AllergenType.DRUG, codedAllergen, null);

		Allergy allergy = new Allergy(new Patient(), allergen, null, null, null);

		Concept reactionConcept = new Concept();
		reactionConcept.addName(new ConceptName("Rash", Context.getLocale()));
		allergy.addReaction(new AllergyReaction(allergy, reactionConcept, null));

		String result = serializer.toText(allergy);
		assertTrue(result.contains("Reactions: Rash"));
	}

	@Test
	public void toText_shouldIncludeComments() {
		Concept codedAllergen = new Concept();
		codedAllergen.addName(new ConceptName("Latex", Context.getLocale()));
		Allergen allergen = new Allergen(AllergenType.ENVIRONMENT, codedAllergen, null);

		Allergy allergy = new Allergy(new Patient(), allergen, null, "Discovered during surgery", null);

		String result = serializer.toText(allergy);
		assertTrue(result.contains("Comments: Discovered during surgery"));
	}
}
