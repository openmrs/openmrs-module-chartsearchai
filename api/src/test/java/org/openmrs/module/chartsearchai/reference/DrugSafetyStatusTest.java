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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

	@Test
	public void proposedPackageIsLimitedAndCannotSurfaceWarnings() {
		DrugSafetyValidator validator = validator();
		validator.setReviewStateForTest(DrugReferencePackage.REVIEW_PROPOSED);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(5, null, null, null, null, null);

		DrugSafetyValidator.SafetyCheckResult result = validator.validateWithStatus(
				"Ibuprofen 600 mg every 6 hours can be given for pain.", null, context);

		assertEquals(DrugSafetyValidator.STATUS_LIMITED, result.getStatus());
		assertTrue(result.getWarnings().isEmpty());
		assertEquals(DrugReferencePackage.REVIEW_PROPOSED,
				((java.util.Map<?, ?>) result.toMap().get("package")).get("review_state"));
		assertTrue(((java.util.List<?>) result.toMap().get("issues"))
				.contains("source_not_clinically_approved"));
	}

	@Test
	public void mappingAndExposureFailuresAreReportedSeparately() {
		PatientClinicalContext incomplete = new PatientClinicalContext(30, null,
				java.util.Collections.singleton("unknown drug"),
				java.util.Collections.<String> emptySet(),
				java.util.Collections.<String> emptySet(),
				java.util.Collections.<String> emptySet(),
				java.util.Collections.<PatientClinicalContext.ActiveDrugOrder> emptyList(),
				false, 1);

		DrugSafetyValidator.SafetyCheckResult result = validator().validateWithStatus(
				"No medication recommendation.", null, incomplete);
		java.util.Map<?, ?> wire = result.toMap();
		java.util.List<?> issues = (java.util.List<?>) wire.get("issues");
		java.util.Map<?, ?> coverage = (java.util.Map<?, ?>) wire.get("coverage");

		assertEquals(DrugSafetyValidator.STATUS_LIMITED, result.getStatus());
		assertTrue(issues.contains("mapping_incomplete"));
		assertTrue(issues.contains("exposure_incomplete"));
		assertFalse((Boolean) coverage.get("mapping_complete"));
		assertFalse((Boolean) coverage.get("exposure_complete"));
		assertEquals("limited", wire.get("identity_confidence"));
	}

	@Test
	public void proposedRelationshipPackageCannotActivateSameAtcClassWarnings() {
		DrugReference ibuprofen = new DrugReference();
		ibuprofen.setId("ibuprofen");
		ibuprofen.setName("Ibuprofen");
		ibuprofen.setAliases(Collections.singletonList("ibuprofen"));
		ibuprofen.setAtcCodes(Collections.singletonList("M01AE01"));
		DrugReference naproxen = new DrugReference();
		naproxen.setId("naproxen");
		naproxen.setName("Naproxen");
		naproxen.setAliases(Collections.singletonList("naproxen"));
		naproxen.setAtcCodes(Collections.singletonList("M01AE02"));
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
				Arrays.asList(ibuprofen, naproxen));
		DrugSafetyValidator validator = new DrugSafetyValidator();
		validator.setDrugReferenceService(service);
		validator.setReviewStateForTest(DrugReferencePackage.REVIEW_CLINICALLY_APPROVED);
		validator.setCrossReactivityReviewStateForTest(DrugReferencePackage.REVIEW_PROPOSED);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null,
				null, null, Collections.singleton("ibuprofen"), null);

		DrugSafetyValidator.SafetyCheckResult result = validator.validateWithStatus(
				"Naproxen may be used.", "Can this patient use naproxen?", context);

		assertEquals(DrugSafetyValidator.STATUS_LIMITED, result.getStatus());
		assertTrue(result.getWarnings().isEmpty());
		assertTrue(((java.util.List<?>) result.toMap().get("issues"))
				.contains("cross_reactivity_not_clinically_approved"));
		java.util.Map<?, ?> primary = (java.util.Map<?, ?>) result.toMap().get("package");
		java.util.Map<?, ?> relationship = (java.util.Map<?, ?>) primary.get("cross_reactivity");
		assertEquals(DrugReferencePackage.REVIEW_PROPOSED, relationship.get("review_state"));
	}

	@Test
	public void partiallyInvalidApprovedPackageCannotReportChecked() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
				DrugReferenceTestSupport.bundledService().getAll());
		DrugSafetyValidator validator = new DrugSafetyValidator();
		validator.setDrugReferenceService(service);
		validator.setSourcePackageForTest(new DrugReferencePackage(
				"partly-invalid", "json", "1", Collections.<String, Object> emptyMap(),
				DrugReferencePackage.REVIEW_CLINICALLY_APPROVED,
				Collections.singletonList("source_data_partially_invalid")));
		validator.setCrossReactivityReviewStateForTest(
				DrugReferencePackage.REVIEW_CLINICALLY_APPROVED);

		DrugSafetyValidator.SafetyCheckResult result = validator.validateWithStatus(
				"No medication recommendation.", null,
				DrugReferenceTestSupport.ctx(30, null, null, null, null, null));

		assertEquals(DrugSafetyValidator.STATUS_LIMITED, result.getStatus());
		assertTrue(((java.util.List<?>) result.toMap().get("issues"))
				.contains("source_data_partially_invalid"));
	}

	@Test
	public void approvedLoadedPackageActivatesWithoutATestReviewOverride() {
		DrugReference ibuprofen = DrugReferenceTestSupport.bundledService()
				.findByQuery("ibuprofen").get(0);
		DrugReferencePackage reviewed = new DrugReferencePackage(
				"operator-reviewed-v3", "json", "3.0",
				Collections.<String, Object> singletonMap("source", "local formulary"),
				DrugReferencePackage.REVIEW_CLINICALLY_APPROVED);
		DrugReferenceService service = new DrugReferenceService();
		service.setSource(new DrugReferenceSource() {
			@Override
			public List<DrugReference> load() {
				return Collections.singletonList(ibuprofen);
			}

			@Override
			public DrugReferencePackage lastLoadPackage() {
				return reviewed;
			}
		});
		service.getAll();
		service.setCrossReactivityGroups(Collections.<CrossReactivityGroup> emptyList());
		service.setCrossReactivityPackage(new DrugReferencePackage(
				"operator-reviewed-relationships", "json", "1",
				Collections.<String, Object> emptyMap(),
				DrugReferencePackage.REVIEW_CLINICALLY_APPROVED));
		DrugSafetyValidator validator = new DrugSafetyValidator();
		validator.setDrugReferenceService(service);

		DrugSafetyValidator.SafetyCheckResult result = validator.validateWithStatus(
				"Ibuprofen 600 mg every 6 hours can be given for pain.", null,
				DrugReferenceTestSupport.ctx(5, null, null, null, null, null));

		assertEquals(DrugSafetyValidator.STATUS_CHECKED, result.getStatus());
		assertTrue(DrugReferenceTestSupport.has(result.getWarnings(),
				SafetyWarning.TYPE_OVERDOSE, "ibuprofen"));
	}

	@Test
	public void partiallyInvalidRelationshipPackageCannotReportChecked() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
				DrugReferenceTestSupport.bundledService().getAll());
		service.setCrossReactivityGroups(Collections.<CrossReactivityGroup> emptyList());
		service.setCrossReactivityPackage(new DrugReferencePackage(
				"partly-invalid-relationships", "json", "1",
				Collections.<String, Object> emptyMap(),
				DrugReferencePackage.REVIEW_CLINICALLY_APPROVED,
				Collections.singletonList("cross_reactivity_data_partially_invalid")));
		DrugSafetyValidator validator = new DrugSafetyValidator();
		validator.setDrugReferenceService(service);
		validator.setReviewStateForTest(DrugReferencePackage.REVIEW_CLINICALLY_APPROVED);

		DrugSafetyValidator.SafetyCheckResult result = validator.validateWithStatus(
				"Ibuprofen 600 mg every 6 hours can be given for pain.", null,
				DrugReferenceTestSupport.ctx(5, null, null, null, null, null));

		assertEquals(DrugSafetyValidator.STATUS_LIMITED, result.getStatus());
		assertTrue(DrugReferenceTestSupport.has(result.getWarnings(),
				SafetyWarning.TYPE_OVERDOSE, "ibuprofen"));
		assertTrue(((java.util.List<?>) result.toMap().get("issues"))
				.contains("cross_reactivity_data_partially_invalid"));
	}

	@Test
	public void malformedPrimaryPackageDiagnosticSurvivesTheUnavailableResult() {
		DrugReferencePackage malformed = new DrugReferencePackage(
				"invalid-primary", "json", "1",
				Collections.<String, Object> singletonMap("source", "test formulary"),
				DrugReferencePackage.REVIEW_CLINICALLY_APPROVED,
				Collections.singletonList("source_data_invalid"));
		DrugReferenceService service = new DrugReferenceService();
		service.setSource(new DrugReferenceSource() {
			@Override
			public List<DrugReference> load() {
				return Collections.emptyList();
			}

			@Override
			public DrugReferencePackage lastLoadPackage() {
				return malformed;
			}
		});
		service.getAll();
		DrugSafetyValidator validator = new DrugSafetyValidator();
		validator.setDrugReferenceService(service);

		DrugSafetyValidator.SafetyCheckResult result = validator.validateWithStatus(
				"No medication recommendation.", null,
				DrugReferenceTestSupport.ctx(30, null, null, null, null, null));

		assertEquals(DrugSafetyValidator.STATUS_UNAVAILABLE, result.getStatus());
		java.util.List<?> issues = (java.util.List<?>) result.toMap().get("issues");
		assertTrue(issues.contains("source_data_invalid"));
		assertTrue(issues.contains("source_unavailable"));
	}
}
