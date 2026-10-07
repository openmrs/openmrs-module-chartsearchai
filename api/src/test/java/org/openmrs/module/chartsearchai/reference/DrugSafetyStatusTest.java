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
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.io.InputStream;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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

	private final List<File> created = new ArrayList<>();

	@AfterEach
	public void removeTestDatasets() throws IOException {
		for (File file : created) {
			Files.deleteIfExists(file.toPath());
		}
	}

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
		prepareCompletePatient();
		DrugSafetyValidator.SafetyCheckResult result = validate(patient);
		assertEquals(fixtureCase("drug-safety.complete-check-is-checked")
				.path("expected_status").asText(), result.getStatus(), result.getIssues().toString());
		assertTrue(result.getIssues().isEmpty());
	}

	private void prepareCompletePatient() {
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
	}

	@Test
	public void partialCheckIsLimited() throws IOException {
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "false");
		DrugSafetyValidator.SafetyCheckResult result = validate(patient);
		assertEquals(fixtureCase("drug-safety.partial-check-is-limited")
				.path("expected_status").asText(), result.getStatus());
		assertTrue(result.getIssues().contains("checks_partially_disabled"));
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
	public void answerWithoutAnAssessableDoseDoesNotClaimTheChartIsIncomplete() {
		prepareCompletePatient();
		DrugSafetyValidator.SafetyCheckResult result = validator.validateWithStatus(
				"Ibuprofen and paracetamol were discussed.", "Do ibuprofen and paracetamol interact?",
				patient, Collections.emptyList(), new PairChipExtent.Sink());
		assertEquals("limited", result.getStatus());
		assertEquals(Collections.singletonList("dose_not_assessable"), result.getIssues());
	}

	@Test
	public void damagedGroupsLimitTheInteractionCheckEvenWithoutAllergies() throws IOException {
		prepareCompletePatient();
		String entries = "{\"entries\":["
				+ "{\"id\":\"group-probe\",\"name\":\"GroupProbe\",\"atcCodes\":[\"M01AE01\"],"
				+ "\"ageBands\":[{\"minYears\":0,\"maxYears\":120,\"maxDailyDoseMg\":1000}],"
				+ "\"contraindications\":[{\"type\":\"condition\",\"token\":\"probe condition\"}],\"interactions\":[]},"
				+ "{\"id\":\"order-probe\",\"name\":\"OrderProbe\",\"atcCodes\":[\"N02BA01\"],"
				+ "\"ageBands\":[{\"minYears\":0,\"maxYears\":120,\"maxDailyDoseMg\":1000}],"
				+ "\"contraindications\":[{\"type\":\"condition\",\"token\":\"probe condition\"}],\"interactions\":[]}]}";
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH,
				DrugReferenceTestSupport.writeDatasetToAppData("status-group-probes.json", entries, created));
		Context.getAdministrationService().executeSQL("update orders set voided = 0 where order_id = 111", false);
		Context.getAdministrationService().executeSQL("update drug_order set drug_inventory_id = null,"
				+ " drug_non_coded = 'OrderProbe' where order_id = 111", false);
		Context.flushSession();
		Context.clearSession();
		patient = Context.getPatientService().getPatient(7);
		assertTrue(PatientClinicalContextBuilder.build(patient).getAllergyTokens().isEmpty());
		referenceService = new DrugReferenceService();
		validator = DrugReferenceTestSupport.validator(referenceService);
		DrugSafetyValidator.SafetyCheckResult healthy = validator.validateWithStatus("GroupProbe 100 mg twice daily.",
				"Can I take GroupProbe?", patient, Collections.emptyList(), new PairChipExtent.Sink());
		assertEquals("checked", healthy.getStatus(), healthy.getIssues().toString());
		assertTrue(healthy.getWarnings().stream().anyMatch(w -> w.getDetail().contains("cross-reactivity group")));
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH,
				DrugReferenceTestSupport.writeDatasetToAppData("status-missing-groups.json", "{}", created));
		referenceService = new DrugReferenceService();
		validator = DrugReferenceTestSupport.validator(referenceService);
		DrugSafetyValidator.SafetyCheckResult degraded = validator.validateWithStatus("GroupProbe 100 mg twice daily.",
				"Can I take GroupProbe?", patient, Collections.emptyList(), new PairChipExtent.Sink());
		assertEquals("limited", degraded.getStatus());
		assertEquals(Collections.singletonList("cross_reactivity_data_partially_invalid"), degraded.getIssues());
		assertFalse(degraded.getWarnings().stream().anyMatch(w -> w.getDetail().contains("cross-reactivity group")));
	}

	@Test
	public void sourceLoadFailureHasItsOwnLimitationEvenWhenFallbackRulesWork() throws IOException {
		prepareCompletePatient();
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH,
				DrugReferenceTestSupport.writeDatasetToAppData("status-unreadable-source.json", "{", created));
		referenceService = new DrugReferenceService();
		validator = DrugReferenceTestSupport.validator(referenceService);
		DrugSafetyValidator.SafetyCheckResult result = validate(patient);
		assertFalse(referenceService.getLoadStatus().isInert());
		assertEquals("limited", result.getStatus());
		assertEquals(Collections.singletonList("source_data_partially_invalid"), result.getIssues());
	}

	@Test
	public void aRecognizedDrugWithoutInteractionDataHasItsOwnLimitation() throws IOException {
		prepareCompletePatient();
		ObjectMapper mapper = new ObjectMapper();
		JsonNode dataset;
		try (InputStream input = getClass().getResourceAsStream("/chartsearchai/drug-reference.json")) {
			dataset = mapper.readTree(input);
		}
		for (JsonNode entry : dataset.path("entries")) {
			if ("ibuprofen".equals(entry.path("id").asText())) {
				((com.fasterxml.jackson.databind.node.ObjectNode) entry).putArray("interactions");
				((com.fasterxml.jackson.databind.node.ObjectNode) entry).putArray("atcCodes");
			}
		}
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH,
				DrugReferenceTestSupport.writeDatasetToAppData("status-no-interactions.json",
						mapper.writeValueAsString(dataset), created));
		referenceService = new DrugReferenceService();
		validator = DrugReferenceTestSupport.validator(referenceService);
		DrugSafetyValidator.SafetyCheckResult result = validate(patient);
		assertEquals("limited", result.getStatus());
		assertEquals(Collections.singletonList("interaction_reference_unavailable"), result.getIssues());
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
		assertTrue(result.getIssues().contains("condition_rules_unavailable"));
		assertTrue(result.getIssues().contains("no_actionable_dose_reference"));
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
		assertTrue(result.getIssues().contains("no_applicable_check"));
	}

	@Test
	public void unknownWeightCannotCompleteAPublishedWeightBasedDoseCheck() {
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WEIGHT_CONCEPT_UUID, ChartSearchAiConstants.DRUG_SAFETY_WEIGHT_CONCEPT_DISABLED);
		assertEquals(null, PatientClinicalContextBuilder.build(patient).getWeightKg());
		DrugSafetyValidator.SafetyCheckResult result = validate(patient);
		assertEquals("limited", result.getStatus());
		assertTrue(result.getIssues().contains("weight_unavailable"));
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
