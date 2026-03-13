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
import org.openmrs.Condition;
import org.openmrs.ConditionClinicalStatus;
import org.openmrs.ConditionVerificationStatus;
import java.util.Locale;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

public class ConditionTextSerializerTest extends BaseModuleContextSensitiveTest {

	private ConditionTextSerializer serializer;

	@BeforeEach
	public void setUp() {
		serializer = new ConditionTextSerializer();
	}

	@Test
	public void toText_shouldSerializeCodedCondition() {
		Condition condition = new Condition();
		Concept concept = new Concept();
		concept.addName(conceptName("Type 2 Diabetes"));
		condition.setCondition(new CodedOrFreeText(concept, null, null));
		condition.setClinicalStatus(ConditionClinicalStatus.ACTIVE);

		String result = serializer.toText(condition);
		assertTrue(result.contains("Condition: Type 2 Diabetes"));
		assertTrue(result.contains("Status: ACTIVE"));
	}

	@Test
	public void toText_shouldSerializeNonCodedCondition() {
		Condition condition = new Condition();
		condition.setCondition(new CodedOrFreeText(null, null, "Chronic back pain"));
		condition.setClinicalStatus(ConditionClinicalStatus.ACTIVE);

		String result = serializer.toText(condition);
		assertTrue(result.contains("Condition: Chronic back pain"));
	}

	@Test
	public void toText_shouldIncludeVerificationStatus() {
		Condition condition = new Condition();
		Concept concept = new Concept();
		concept.addName(conceptName("Asthma"));
		condition.setCondition(new CodedOrFreeText(concept, null, null));
		condition.setClinicalStatus(ConditionClinicalStatus.ACTIVE);
		condition.setVerificationStatus(ConditionVerificationStatus.CONFIRMED);

		String result = serializer.toText(condition);
		assertTrue(result.contains("Verification: CONFIRMED"));
	}

	@Test
	public void toText_shouldIncludeOnsetDate() {
		Condition condition = new Condition();
		Concept concept = new Concept();
		concept.addName(conceptName("Hypertension"));
		condition.setCondition(new CodedOrFreeText(concept, null, null));
		condition.setClinicalStatus(ConditionClinicalStatus.ACTIVE);
		condition.setOnsetDate(new Date());

		String result = serializer.toText(condition);
		assertTrue(result.contains("Onset:"));
	}

	@Test
	public void toText_shouldIncludeEndDateAndReason() {
		Condition condition = new Condition();
		Concept concept = new Concept();
		concept.addName(conceptName("Malaria"));
		condition.setCondition(new CodedOrFreeText(concept, null, null));
		condition.setClinicalStatus(ConditionClinicalStatus.INACTIVE);
		condition.setEndDate(new Date());
		condition.setEndReason("Treatment completed");

		String result = serializer.toText(condition);
		assertTrue(result.contains("Resolved:"));
		assertTrue(result.contains("Treatment completed"));
	}

	@Test
	public void toText_shouldIncludeAdditionalDetail() {
		Condition condition = new Condition();
		Concept concept = new Concept();
		concept.addName(conceptName("Chronic Kidney Disease"));
		condition.setCondition(new CodedOrFreeText(concept, null, null));
		condition.setClinicalStatus(ConditionClinicalStatus.ACTIVE);
		condition.setAdditionalDetail("Stage 3, GFR 45 mL/min");

		String result = serializer.toText(condition);
		assertTrue(result.contains("Detail: Stage 3, GFR 45 mL/min"));
	}

	@Test
	public void toText_shouldHandleNullConditionObject() {
		Condition condition = new Condition();
		condition.setClinicalStatus(ConditionClinicalStatus.ACTIVE);

		String result = serializer.toText(condition);
		assertTrue(result.contains("Status: ACTIVE"));
	}

	private ConceptName conceptName(String name) {
		ConceptName cn = new ConceptName();
		cn.setName(name);
		cn.setLocale(Locale.ENGLISH);
		return cn;
	}
}
