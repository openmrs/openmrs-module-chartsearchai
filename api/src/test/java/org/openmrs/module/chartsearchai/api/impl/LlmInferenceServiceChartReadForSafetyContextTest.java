/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;

/**
 * A chart the module could not read says so ON THE ANSWER (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/247">#247</a> item 1).
 *
 * <p>The defect these cases pin is not that the safety layer fails — it is additive and failing
 * safe is correct — but that it fails INDISTINGUISHABLY. A failed allergy, condition or
 * active-order read degrades to an empty set, so the response carries no chips, no findings and,
 * before this key, nothing at all to separate that from a patient who is genuinely on nothing and
 * allergic to nothing. The issue's own measurement is the table in its first comment: read-failed
 * and healthy-chart were the same response.
 *
 * <p><b>The REAL injector and the REAL validator over one service</b>, for the reason
 * {@code LlmInferenceServiceFindingProvenanceContextTest} states: the verdict is collected by
 * {@code PatientClinicalContextBuilder} from real service reads, so a pass-through injector would
 * leave the whole seam inert. Patient 7 is persisted, which a detached {@code new Patient()} is
 * not.
 *
 * <p>The read is failed the way production fails it — a {@code UserContext} refusing exactly the
 * one {@code @Authorized} privilege core annotates the service call with, the technique
 * {@code StandingChartAlertsToggleContextTest} uses for the same three reads.
 */
public class LlmInferenceServiceChartReadForSafetyContextTest extends BaseModuleContextSensitiveTest {

	private static final String QUESTION = "Can I give him ibuprofen?";

	private Patient patient;

	@BeforeEach
	public void setUp() {
		// DEFAULT_DRUG_REFERENCE_ENABLED is false, so without this the pass returns before building a
		// context and every case below would be measuring the switch rather than the read.
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		patient = Context.getPatientService().getPatient(7);
		assertNotNull(patient, "precondition: the standard test patient must exist");
	}

	private TestableService serviceUnderTest() {
		DrugReferenceService reference = DrugReferenceTestSupport.curatedService();
		TestableService service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(new StubProvider());
		service.setDrugReferenceInjector(DrugReferenceTestSupport.injectorWithSafety(reference));
		service.setDrugSafetyValidator(DrugReferenceTestSupport.validator(reference));
		return service;
	}

	/** Runs {@code body} with {@code privilege} refused and every other one held. */
	private <T> T refusing(String privilege, java.util.function.Supplier<T> body) {
		UserContext prior = Context.getUserContext();
		Context.setUserContext(new UserContext(null) {

			@Override
			public boolean hasPrivilege(String held) {
				return !privilege.equals(held);
			}
		});
		try {
			return body.get();
		}
		finally {
			Context.setUserContext(prior);
		}
	}

	/**
	 * The issue's own shape: the allergy read fails, and the answer says the chart was not read
	 * rather than letting an empty chip list read as a clear chart.
	 */
	@Test
	public void search_saysSoWhenTheAllergyReadFailed() {
		Boolean stated = refusing(PrivilegeConstants.GET_ALLERGIES,
			() -> serviceUnderTest().search(patient, QUESTION).getChartReadForSafety());

		assertEquals(Boolean.FALSE, stated,
				"the contraindication screen evaluated against 'no allergies' because nobody could "
						+ "read them — and the response is otherwise identical to a healthy patient's, "
						+ "so the answer has to carry the difference (issue #247)");
	}

	/** The condition read, which is a second catch in a second try block. */
	@Test
	public void search_saysSoWhenTheConditionReadFailed() {
		Boolean stated = refusing(PrivilegeConstants.GET_CONDITIONS,
			() -> serviceUnderTest().search(patient, QUESTION).getChartReadForSafety());

		assertEquals(Boolean.FALSE, stated,
				"a failed condition read blinds the condition arm the same way the allergy read "
						+ "blinds its own");
	}

	/**
	 * The ACTIVE-ORDER read, and this case is why the key is the whole pass rather than the records
	 * alone.
	 *
	 * <p>A records-only verdict stays green on the two cases above and states {@code TRUE} here,
	 * beside an empty chip list produced by interaction arms that read no orders at all. That is
	 * the defect ADR Decision 79 records one surface over, where a role without {@code Get Orders}
	 * was handed {@code screened: true} with an empty alert list.
	 */
	@Test
	public void search_saysSoWhenTheActiveOrderReadFailed() {
		Boolean stated = refusing(PrivilegeConstants.GET_ORDERS,
			() -> serviceUnderTest().search(patient, QUESTION).getChartReadForSafety());

		assertEquals(Boolean.FALSE, stated,
				"the interaction arms screened an empty order list because nobody could read the "
						+ "orders; a verdict made of the record stamps alone would state TRUE here");
	}

	/**
	 * The other direction, and the case that stops {@code FALSE} above being read as "what the key
	 * always says": a chart the module CAN read states {@code TRUE}, which is a measurement and not
	 * a certificate that anything was screened.
	 */
	@Test
	public void search_statesTheChartWasReadWhereEveryReadSucceeded() {
		assertEquals(Boolean.TRUE, serviceUnderTest().search(patient, QUESTION)
				.getChartReadForSafety(),
				"every read completed, so the emptiness of anything derived from this chart is a "
						+ "measurement rather than an unknown");
	}

	/**
	 * And the third value: with the feature off nothing reads the chart at all, so the answer states
	 * no measurement — never {@code TRUE}, which would claim reads that never happened, and never
	 * {@code FALSE}, which would claim reads that failed.
	 */
	@Test
	public void search_statesNoMeasurementWhereTheFeatureIsOff() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "false");

		assertNull(serviceUnderTest().search(patient, QUESTION).getChartReadForSafety(),
				"nothing looked, so nothing is known — and null must not collapse into either verdict");
	}

	/**
	 * Both answers the streaming method produces state it.
	 *
	 * <p>The {@code done} event a user sees under {@code chartsearchai.grounding.async=true} is
	 * emitted from the UNGROUNDED answer, and that consumer fires whether or not grounding is
	 * enabled. A statement set only on the returned answer is absent from the event that matters.
	 * This verdict is known before the model is called — the injector's chart read is the first
	 * thing the pass does — so there is no reason for the early event to carry less, and it is what
	 * makes the injector's pass rather than the validator's the right producer.
	 */
	@Test
	public void searchStreaming_statesItOnTheUngroundedAnswerTooAndNotOnlyOnTheFinalOne() {
		final List<Boolean> ungrounded = new ArrayList<Boolean>();

		ChartAnswer answer = refusing(PrivilegeConstants.GET_ALLERGIES,
			() -> serviceUnderTest().searchStreaming(patient, QUESTION,
				token -> { }, reasoning -> { }, citations -> { },
				early -> ungrounded.add(early.getChartReadForSafety())));

		assertEquals(1, ungrounded.size(), "the early-done consumer must have fired");
		assertEquals(Boolean.FALSE, ungrounded.get(0),
				"the early done event is emitted from this answer, so the verdict has to be on it — "
						+ "the validator's pass has not even run at that point, which is why the "
						+ "injector's read is what states this");
		assertEquals(Boolean.FALSE, answer.getChartReadForSafety(),
				"and on the answer the classic shape emits");
	}

	/** Exposes the seams, and keeps warmup out of a test about a statement. */
	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}
	}

	/** A one-record chart, built without touching querystore. */
	private static final class StubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			List<RecordMapping> mappings = Arrays.asList(
					new RecordMapping(1, "obs", "00000000-0000-0000-0000-000000000001", null));
			return new PatientChart("1. BP 120/80", mappings, Collections.<Integer> emptyList());
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	/** An answer with no citation markers, so nothing downstream depends on the model's wording. */
	private static final class StubProvider extends LlmProvider {

		private static LlmResponse canned() {
			return new LlmResponse("Ibuprofen can be given.", Collections.<Integer> emptyList());
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices,
				String question, boolean enumerateFindings) {
			return canned();
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings) {
			return canned();
		}
	}
}
