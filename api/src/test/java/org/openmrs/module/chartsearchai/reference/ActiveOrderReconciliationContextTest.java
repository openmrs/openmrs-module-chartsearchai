/*
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * End-to-end, context-sensitive coverage of the active-order reconciliation (issue #118): runs the
 * PUBLIC production entry {@link DrugReferenceInjector#inject(PatientChart, Patient, String)}
 * against a real OpenMRS context, so the whole chain executes — the feature gate,
 * {@link PatientClinicalContextBuilder}'s real {@code OrderService} read, the reconciliation against
 * the serialized chart, and the record injection. This is the half of the chain
 * {@code ActiveOrderReconciliationTest} cannot reach: that one supplies the clinical context
 * directly, so it never proves that the orders the safety layer actually reads arrive in a shape the
 * reconciliation can use.
 *
 * <p>Patient 2 of the standard test dataset has four active drug orders across two care settings —
 * Triomune-30 (orders 3 and 222), ASPIRIN (444) and NYQUIL (5) — and no stopped/expired order among
 * them, so {@code getActiveOrders} returns all four.
 *
 * <p>What still needs a standalone: module AOP is not active in
 * {@code BaseModuleContextSensitiveTest} and querystore does not index here, so the chart is
 * supplied rather than retrieved. The DRIFT behaviour end to end — a real querystore index missing a
 * document a real answer is then grounded without — is verified on the 3.7.1 standalone, as the
 * cache-eviction work in #53 was.
 */
public class ActiveOrderReconciliationContextTest extends BaseModuleContextSensitiveTest {

	/** Order 3, Triomune-30 (standard test dataset): activated 2008-02-08, never stopped. */
	private static final String TRIOMUNE_ORDER_UUID = "e3d621f0-a4d5-47d1-a4e1-5ace3f66d43a";

	/** Order 222, the second Triomune-30 order, in the other care setting. */
	private static final String TRIOMUNE_OTHER_SETTING_ORDER_UUID = "2662e6c2-697b-11e3-bd76-0800271c1b75";

	/** Order 444, ASPIRIN. */
	private static final String ASPIRIN_ORDER_UUID = "9c21e407-697b-11e3-bd76-0800271c1b75";

	/** Order 5, NYQUIL. */
	private static final String NYQUIL_ORDER_UUID = "0c96f25c-4949-4f72-9931-d808fbc226db";

	private static final String MEDICATION_QUESTION = "what medications is the patient taking?";

	private DrugReferenceInjector injector;

	private Patient patient;

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		// Wired as production wires it, so a finding record would surface here too — a medication-list
		// question names no drug, so the only records injected must be the active orders.
		injector = DrugReferenceTestSupport
				.injectorWithSafety(DrugReferenceTestSupport.ddinterServiceWithGroups());
		patient = Context.getPatientService().getPatient(2);
	}

	/** The injected records' resource uuids, by resource type. */
	private Set<String> injectedUuids(PatientChart chart, String resourceType) {
		Set<String> uuids = new LinkedHashSet<String>();
		for (RecordMapping mapping : chart.getMappings()) {
			if (resourceType.equals(mapping.getResourceType())) {
				uuids.add(mapping.getResourceUuid());
			}
		}
		return uuids;
	}

	private String injectedText(PatientChart chart) {
		StringBuilder text = new StringBuilder();
		for (RecordMapping mapping : chart.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER.equals(mapping.getResourceType())) {
				text.append(mapping.getText()).append('\n');
			}
		}
		return text.toString();
	}

	@Test
	public void everyActiveOrderTheChartCannotSubstantiateReachesTheRecordList() {
		// The observed failure, through the production entry point: the chart carries no drug-order
		// record (on the standalone because the querystore index was behind; here because the chart
		// supplied has none), the safety layer reads the orders anyway, and the answer was left able
		// to state "No active medications are recorded." while a chip named one of them.
		PatientChart result = injector.inject(DrugReferenceTestSupport.oneRecordChart(), patient,
				MEDICATION_QUESTION);

		Set<String> injected = injectedUuids(result, ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER);
		assertTrue(injected.contains(TRIOMUNE_ORDER_UUID) && injected.contains(ASPIRIN_ORDER_UUID)
				&& injected.contains(NYQUIL_ORDER_UUID) && injected.contains(TRIOMUNE_OTHER_SETTING_ORDER_UUID),
				"every active drug order OrderService returns must be injected, carrying its own Order "
						+ "uuid so the citation resolves to the order: " + injected);
		String text = injectedText(result).toLowerCase();
		assertTrue(text.contains("triomune-30") && text.contains("aspirin") && text.contains("nyquil"),
				"each record must name its drug, which is what stops the answer denying the medication: "
						+ text);
		assertEquals(0, injectedUuids(result, ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE).size(),
				"a medication-list question names no drug, so no drug-reference record may be injected");
		assertEquals(0, injectedUuids(result, ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING).size(),
				"and nothing bears on it, so no safety finding may be injected either");
	}

	@Test
	public void aChartCarryingTheDrugOrderRecordsIsLeftUntouched() {
		// The negative, through the same production entry: when the chart and the service agree there
		// is nothing to reconcile, so the chart must come back byte-identical — no WARN, no extra
		// record, no extra citation for a prescription the chart already carries. A reconciliation
		// that fired here would fire on essentially every query for every medicated patient.
		List<RecordMapping> records = new ArrayList<RecordMapping>();
		records.add(DrugReferenceTestSupport.obsRecord(1, "BP 120/80"));
		records.add(DrugReferenceTestSupport.drugOrderRecord(2, TRIOMUNE_ORDER_UUID, "Triomune-30"));
		records.add(DrugReferenceTestSupport.drugOrderRecord(3, TRIOMUNE_OTHER_SETTING_ORDER_UUID,
				"Triomune-30"));
		records.add(DrugReferenceTestSupport.drugOrderRecord(4, ASPIRIN_ORDER_UUID, "ASPIRIN 325mg"));
		records.add(DrugReferenceTestSupport.drugOrderRecord(5, NYQUIL_ORDER_UUID, "NYQUIL"));
		PatientChart chart = DrugReferenceTestSupport.chartOf(
				records.toArray(new RecordMapping[records.size()]));

		assertSame(chart, injector.inject(chart, patient, MEDICATION_QUESTION),
				"the chart substantiates every active order, so it must be returned untouched");
	}
}
