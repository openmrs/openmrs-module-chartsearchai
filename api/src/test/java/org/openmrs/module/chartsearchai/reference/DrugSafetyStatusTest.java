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
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Verifies the shared checked, limited, and unavailable safety-status contract
 * through the public patient entry point and real OpenMRS configuration. */
public class DrugSafetyStatusTest extends BaseModuleContextSensitiveTest {

	private DrugSafetyValidator validator;

	private Patient patient;

	private DrugReferenceService referenceService;

	@BeforeEach
	public void setUp() {
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS, "true");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_DOSE_EXCESS, "true");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_INTERACTIONS, "true");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "true");
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT, ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON);
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, "");
		referenceService = new DrugReferenceService();
		validator = DrugReferenceTestSupport.validator(referenceService);
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
		return validator.validateWithStatus("Ibuprofen 200 mg twice daily.", "Can I take ibuprofen?",
				selectedPatient, Collections.emptyList(), new PairChipExtent.Sink());
	}

	@Test
	public void completeCheckIsChecked() throws IOException {
		Context.getAdministrationService().executeSQL("update orders set voided = 1 where patient_id = 7", false);
		Context.flushSession();
		Context.clearSession();
		patient = Context.getPatientService().getPatient(7);
		org.openmrs.Concept weightConcept = Context.getConceptService().getConcept(5089);
		org.openmrs.Obs weight = new org.openmrs.Obs(patient, weightConcept, new java.util.Date(),
				Context.getLocationService().getLocation(1));
		weight.setValueNumeric(70.0);
		Context.getObsService().saveObs(weight, null);
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WEIGHT_CONCEPT_UUID, weightConcept.getUuid());
		assertFalse(referenceService.getLoadStatus().isInert());
		assertTrue(patient.getAge() >= 18);
		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		assertTrue(context.activeDrugOrdersRead());
		assertTrue(context.contraindicationRecordsRead());
		assertEquals(70.0, context.getWeightKg());
		assertTrue(context.getActiveDrugOrders().isEmpty(), "positive case must not hide unmapped active orders");
		DrugSafetyValidator.SafetyCheckResult result = validate(patient);
		assertEquals(fixtureCase("drug-safety.complete-check-is-checked")
				.path("expected_status").asText(), result.getStatus(), result.getIssues().toString());
		assertTrue(result.getIssues().isEmpty());
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

	@Test
	public void enabledChecksWithARealPatientButNoRequiredPackageAreUnavailable() {
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT, ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_ATC);
		referenceService = new DrugReferenceService();
		validator = DrugReferenceTestSupport.validator(referenceService);
		assertTrue(referenceService.getLoadStatus().isLoaded());
		assertTrue(referenceService.getLoadStatus().isInert());
		DrugSafetyValidator.SafetyCheckResult result = validate(patient);
		assertEquals("unavailable", result.getStatus());
		assertTrue(result.getIssues().contains("source_unavailable"));
	}

	@Test
	public void unreadOrdersCannotBeReportedChecked() {
		assertUnreadContextUnavailable(PrivilegeConstants.GET_ORDERS);
	}

	@Test
	public void anOrderWithoutAnyReadableIdentityCannotDisappearIntoACompleteCheck() {
		Context.getAdministrationService().executeSQL("update drug_order set drug_inventory_id = null,"
				+ " drug_non_coded = null where order_id = 111", false);
		Context.getAdministrationService().executeSQL("update concept_name set voided = 1 where concept_id = 88", false);
		Context.flushSession();
		Context.clearSession();
		patient = Context.getPatientService().getPatient(7);
		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		assertTrue(context.activeDrugOrdersRead());
		assertTrue(context.getActiveDrugOrders().isEmpty());
		assertFalse(context.activeDrugIdentitiesComplete());
		DrugSafetyValidator.SafetyCheckResult result = validate(patient);
		assertEquals("limited", result.getStatus());
		assertTrue(result.getIssues().contains("mapping_incomplete"));
	}

	@Test
	public void anUnmappedActiveOrderLeavesSupportedInteractionWarningsVisible() {
		DrugReferenceTestSupport.nameTheConcept(88, "Other non-coded drug");
		Context.getAdministrationService().executeSQL("update drug_order set drug_inventory_id = null,"
				+ " drug_non_coded = 'Warfarin' where order_id = 111", false);
		Context.flushSession();
		Context.clearSession();
		patient = Context.getPatientService().getPatient(7);
		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		assertFalse(context.getActiveDrugOrders().isEmpty());
		assertTrue(referenceService.findForActiveOrders(context).isEmpty());
		DrugSafetyValidator.SafetyCheckResult result = validate(patient);
		assertEquals("limited", result.getStatus());
		assertTrue(result.getIssues().contains("mapping_incomplete"));
		assertTrue(result.getWarnings().stream().anyMatch(w -> SafetyWarning.TYPE_INTERACTION.equals(w.getType())));
	}

	@Test
	public void loadedInteractionDataWithoutDosingOrConditionRulesIsOnlyPartial() {
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT, ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER);
		referenceService = new DrugReferenceService();
		validator = DrugReferenceTestSupport.validator(referenceService);
		assertFalse(referenceService.getLoadStatus().isInert());
		assertEquals(DrugReferenceLoad.Coverage.ABSENT,
				referenceService.getLoadStatus().coverageOf(DrugReferenceLoad.Arm.DOSE_CEILINGS));
		DrugSafetyValidator.SafetyCheckResult result = validate(patient);
		assertEquals("limited", result.getStatus());
		assertTrue(result.getIssues().contains("check_scope_limited"));
	}

	@Test
	public void mappedOrdersAloneDoNotProveAnUnrequestedSafetyCheckRan() {
		DrugReferenceTestSupport.nameTheConcept(88, "Other non-coded drug");
		Context.getAdministrationService().executeSQL("update drug_order set drug_inventory_id = null,"
				+ " drug_non_coded = 'Ibuprofen' where order_id = 111", false);
		Context.flushSession();
		Context.clearSession();
		patient = Context.getPatientService().getPatient(7);
		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		assertFalse(referenceService.findForActiveOrders(context).isEmpty());
		assertTrue(context.getAllergyTokens().isEmpty());
		assertTrue(context.getConditionTokens().isEmpty());
		DrugSafetyValidator.SafetyCheckResult result = validator.validateWithStatus(
				"Latest blood test recorded.", "What is the latest blood test?", patient,
				Collections.emptyList(), new PairChipExtent.Sink());
		assertEquals("limited", result.getStatus());
		assertTrue(result.getIssues().contains("check_scope_limited"));
	}

	@Test
	public void unknownWeightCannotCompleteAPublishedWeightBasedDoseCheck() {
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WEIGHT_CONCEPT_UUID, ChartSearchAiConstants.DRUG_SAFETY_WEIGHT_CONCEPT_DISABLED);
		assertEquals(null, PatientClinicalContextBuilder.build(patient).getWeightKg());
		DrugSafetyValidator.SafetyCheckResult result = validate(patient);
		assertEquals("limited", result.getStatus());
		assertTrue(result.getIssues().contains("exposure_incomplete"));
	}

	@Test
	public void unreadAllergiesCannotBeReportedChecked() {
		assertUnreadContextUnavailable(PrivilegeConstants.GET_ALLERGIES);
	}

	@Test
	public void unreadConditionsCannotBeReportedChecked() {
		assertUnreadContextUnavailable(PrivilegeConstants.GET_CONDITIONS);
	}

	// Fail the real OpenMRS service authorization, not a mocked context-builder return value.
	private void assertUnreadContextUnavailable(String deniedPrivilege) {
		referenceService.getAll();
		UserContext previous = Context.getUserContext();
		Context.setUserContext(new UserContext(null) {
			@Override
			public boolean hasPrivilege(String privilege) {
				return !deniedPrivilege.equals(privilege);
			}
		});
		try {
			DrugSafetyValidator.SafetyCheckResult result = validate(patient);
			assertEquals("unavailable", result.getStatus());
			assertTrue(result.getIssues().contains("patient_context_unavailable"));
		} finally {
			Context.setUserContext(previous);
		}
	}
}
