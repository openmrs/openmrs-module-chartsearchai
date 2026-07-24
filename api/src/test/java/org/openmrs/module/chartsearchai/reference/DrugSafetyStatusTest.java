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
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Hub-side adapter for the shared drug_safety_status conformance fixture (see
 * med-agent-hub's mirror-image test_drug_safety_status.py). Drives
 * {@link DrugSafetyValidator#validateWithStatus(String, String, PatientClinicalContext)} against
 * the fixture's 3 scenarios (per the conformance contract's Temporal, Citation, and Safety
 * Contract: checked/limited/unavailable are honest states — neither an empty warning list nor a
 * missing source package implies checked). The fixture's mapping_complete/exposure_complete/
 * execution_complete flags are conceptual; each case is translated to the closest concrete
 * real-code scenario, exactly mirroring the Python adapter's translation:
 *
 * <ul>
 *   <li>complete-check-is-checked: a resolved patient context, all three check categories enabled.</li>
 *   <li>partial-check-is-limited: a resolved context, but the caller only asked for a subset of
 *       the checks (contraindications disabled via the GP toggle) — a deliberately partial,
 *       specifically described check.</li>
 *   <li>missing-package-is-unavailable: no patient context at all, mirroring
 *       {@code DrugSafetyValidator#validateWithStatus(String, String, Patient)}'s real
 *       "no patient" path.</li>
 * </ul>
 */
public class DrugSafetyStatusTest {

	private static JsonNode fixture() throws IOException {
		try (InputStream is = DrugSafetyStatusTest.class
				.getResourceAsStream("/conformance/dual-provider-conformance.v1.json")) {
			return new ObjectMapper().readTree(is);
		}
	}

	private static JsonNode fixtureCase(String id) throws IOException {
		for (JsonNode candidate : fixture().path("drug_safety_status")) {
			if (id.equals(candidate.path("id").asText())) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("No drug_safety_status fixture case '" + id + "'");
	}

	private DrugSafetyValidator validator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.bundledService());
	}

	@Test
	public void completeCheckIsChecked() throws IOException {
		JsonNode fixtureCase = fixtureCase("drug-safety.complete-check-is-checked");
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(30, null, null, null, null, null);

		DrugSafetyValidator.SafetyCheckResult result =
				validator().validateWithStatus("Ibuprofen 200 mg as needed.", null, context);

		assertEquals(fixtureCase.path("expected_status").asText(), result.getStatus());
	}

	@Test
	public void partialCheckIsLimited() throws IOException {
		JsonNode fixtureCase = fixtureCase("drug-safety.partial-check-is-limited");
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(30, null, null, null, null, null);

		DrugSafetyValidator.SafetyCheckResult result = validator().validateWithStatus(
				"Ibuprofen 200 mg as needed.", null, context, false, true, true);

		assertEquals(fixtureCase.path("expected_status").asText(), result.getStatus());
	}

	@Test
	public void missingContextIsUnavailable() throws IOException {
		JsonNode fixtureCase = fixtureCase("drug_safety.missing-package-is-unavailable");

		DrugSafetyValidator.SafetyCheckResult result =
				validator().validateWithStatus("Ibuprofen 200 mg as needed.", null, (PatientClinicalContext) null);

		assertEquals(fixtureCase.path("expected_status").asText(), result.getStatus());
	}

	@Test
	public void checkedStatusStillSurfacesRealWarnings() {
		// The status is orthogonal to warning content — a checked result can still flag something.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(5, null, null, null, null, null);

		DrugSafetyValidator.SafetyCheckResult result = validator().validateWithStatus(
				"Ibuprofen 600 mg every 6 hours can be given for pain.", null, context);

		assertEquals(DrugSafetyValidator.STATUS_CHECKED, result.getStatus());
		assertTrue(DrugReferenceTestSupport.has(result.getWarnings(), SafetyWarning.TYPE_OVERDOSE, "ibuprofen"));
	}
}
