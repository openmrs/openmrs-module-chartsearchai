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

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.CodedOrFreeText;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.ConditionVerificationStatus;
import org.openmrs.Diagnosis;
import org.openmrs.Encounter;
import java.util.Locale;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

public class DiagnosisTextSerializerTest extends BaseModuleContextSensitiveTest {

	private DiagnosisTextSerializer serializer;

	@BeforeEach
	public void setUp() {
		serializer = new DiagnosisTextSerializer();
	}

	@Test
	public void toText_shouldSerializeCodedDiagnosis() {
		Diagnosis diagnosis = new Diagnosis();
		Concept concept = new Concept();
		concept.addName(conceptName("Malaria"));
		diagnosis.setDiagnosis(new CodedOrFreeText(concept, null, null));
		diagnosis.setCertainty(ConditionVerificationStatus.CONFIRMED);
		diagnosis.setRank(1);

		String result = serializer.toText(diagnosis);
		assertTrue(result.contains("Diagnosis: Malaria"));
		assertTrue(result.contains("Certainty: CONFIRMED"));
		assertTrue(result.contains("Rank: Primary"));
	}

	@Test
	public void toText_shouldSerializeNonCodedDiagnosis() {
		Diagnosis diagnosis = new Diagnosis();
		diagnosis.setDiagnosis(new CodedOrFreeText(null, null, "Suspected TB"));
		diagnosis.setRank(2);

		String result = serializer.toText(diagnosis);
		assertTrue(result.contains("Diagnosis: Suspected TB"));
		assertTrue(result.contains("Rank: Secondary"));
	}

	@Test
	public void toText_shouldIncludeEncounterDate() {
		Diagnosis diagnosis = new Diagnosis();
		Concept concept = new Concept();
		concept.addName(conceptName("Pneumonia"));
		diagnosis.setDiagnosis(new CodedOrFreeText(concept, null, null));
		diagnosis.setRank(1);

		Encounter enc = new Encounter();
		enc.setEncounterDatetime(new Date());
		diagnosis.setEncounter(enc);

		String result = serializer.toText(diagnosis);
		assertTrue(result.contains("Date:"));
	}

	@Test
	public void toText_shouldHandleNullDiagnosisName() {
		Diagnosis diagnosis = new Diagnosis();
		diagnosis.setRank(1);

		String result = serializer.toText(diagnosis);
		assertTrue(result.contains("Rank: Primary"));
	}

	private ConceptName conceptName(String name) {
		ConceptName cn = new ConceptName();
		cn.setName(name);
		cn.setLocale(Locale.ENGLISH);
		return cn;
	}
}
