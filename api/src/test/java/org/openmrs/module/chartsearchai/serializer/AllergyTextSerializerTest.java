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
import java.util.Locale;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

public class AllergyTextSerializerTest extends BaseModuleContextSensitiveTest {

	private AllergyTextSerializer serializer;

	@BeforeEach
	public void setUp() {
		serializer = new AllergyTextSerializer();
	}

	@Test
	public void toText_shouldSerializeCodedAllergy() {
		Concept codedAllergen = new Concept();
		codedAllergen.addName(conceptName("Penicillin"));
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
		codedAllergen.addName(conceptName("Aspirin"));
		Allergen allergen = new Allergen(AllergenType.DRUG, codedAllergen, null);

		Concept severity = new Concept();
		severity.addName(conceptName("Severe"));

		Allergy allergy = new Allergy(new Patient(), allergen, severity, null, null);

		String result = serializer.toText(allergy);
		assertTrue(result.contains("Severity: Severe"));
	}

	@Test
	public void toText_shouldIncludeReactions() {
		Concept codedAllergen = new Concept();
		codedAllergen.addName(conceptName("Penicillin"));
		Allergen allergen = new Allergen(AllergenType.DRUG, codedAllergen, null);

		Allergy allergy = new Allergy(new Patient(), allergen, null, null, null);

		Concept reactionConcept = new Concept();
		reactionConcept.addName(conceptName("Rash"));
		allergy.addReaction(new AllergyReaction(allergy, reactionConcept, null));

		String result = serializer.toText(allergy);
		assertTrue(result.contains("Reactions: Rash"));
	}

	@Test
	public void toText_shouldIncludeComments() {
		Concept codedAllergen = new Concept();
		codedAllergen.addName(conceptName("Latex"));
		Allergen allergen = new Allergen(AllergenType.ENVIRONMENT, codedAllergen, null);

		Allergy allergy = new Allergy(new Patient(), allergen, null, "Discovered during surgery", null);

		String result = serializer.toText(allergy);
		assertTrue(result.contains("Comments: Discovered during surgery"));
	}

	@Test
	public void toText_shouldHandleNullSeverity() {
		Concept codedAllergen = new Concept();
		codedAllergen.addName(conceptName("Ibuprofen"));
		Allergen allergen = new Allergen(AllergenType.DRUG, codedAllergen, null);

		Allergy allergy = new Allergy(new Patient(), allergen, null, null, null);

		String result = serializer.toText(allergy);
		assertTrue(result.contains("Allergy: Ibuprofen"));
		assertTrue(!result.contains("Severity:"));
	}

	@Test
	public void toText_shouldIncludeNonCodedReaction() {
		Concept codedAllergen = new Concept();
		codedAllergen.addName(conceptName("Sulfa drugs"));
		Allergen allergen = new Allergen(AllergenType.DRUG, codedAllergen, null);

		Allergy allergy = new Allergy(new Patient(), allergen, null, null, null);
		allergy.addReaction(new AllergyReaction(allergy, null, "Skin peeling"));

		String result = serializer.toText(allergy);
		assertTrue(result.contains("Reactions: Skin peeling"));
	}

	private ConceptName conceptName(String name) {
		ConceptName cn = new ConceptName();
		cn.setName(name);
		cn.setLocale(Locale.ENGLISH);
		return cn;
	}
}
