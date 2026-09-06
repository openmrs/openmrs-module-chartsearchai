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
import java.io.InputStream;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Verifies the shared checked, limited, and unavailable safety-status contract
 * through the public patient entry point and real OpenMRS configuration. */
public class DrugSafetyStatusTest extends BaseModuleContextSensitiveTest {

	private DrugSafetyValidator validator;

	private Patient patient;

	@BeforeEach
	public void setUp() {
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS, "true");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_DOSE_EXCESS, "true");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_INTERACTIONS, "true");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "true");
		validator = DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService());
		patient = Context.getPatientService().getPatient(7);
	}

	private static void set(String property, String value) {
		Context.getAdministrationService().setGlobalProperty(property, value);
	}

	private static JsonNode fixtureCase(String id) throws IOException {
		try (InputStream input = DrugSafetyStatusTest.class
				.getResourceAsStream("/conformance/dual-provider-conformance.v1.json")) {
			for (JsonNode candidate : new ObjectMapper().readTree(input).path("drug_safety_status")) {
				if (id.equals(candidate.path("id").asText())) {
					return candidate;
				}
			}
		}
		throw new IllegalArgumentException("No drug-safety fixture case " + id);
	}

	private DrugSafetyValidator.SafetyCheckResult validate(Patient selectedPatient) {
		return validator.validateWithStatus("Ibuprofen 200 mg as needed.", null,
				selectedPatient, Collections.emptyList(), new PairChipExtent.Sink());
	}

	@Test
	public void completeCheckIsChecked() throws IOException {
		assertEquals(fixtureCase("drug-safety.complete-check-is-checked")
				.path("expected_status").asText(), validate(patient).getStatus());
	}

	@Test
	public void partialCheckIsLimited() throws IOException {
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "false");
		assertEquals(fixtureCase("drug-safety.partial-check-is-limited")
				.path("expected_status").asText(), validate(patient).getStatus());
	}

	@Test
	public void missingPatientIsUnavailable() throws IOException {
		assertEquals(fixtureCase("drug_safety.missing-package-is-unavailable")
				.path("expected_status").asText(), validate(null).getStatus());
	}
}
