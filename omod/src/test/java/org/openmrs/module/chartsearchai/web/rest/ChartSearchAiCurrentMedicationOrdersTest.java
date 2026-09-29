/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.web.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.reference.PatientClinicalContext;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.reference.SafetyWarningFixtures;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A contraindication chip about one of the patient's own orders names those orders ON THE WIRE, by display
 * and uuid (issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/552">#552</a>).
 *
 * <p>The ticket's shape: an order for <em>Advil 400mg</em> produces a chip whose {@code drug} is
 * {@code Ibuprofen}, and before {@code currentMedicationOrders} nothing on the chip said it came from the
 * Advil order, so README told a client to resolve {@code drug} against her orders itself. That the real
 * validator stamps every order the chip covers is {@code CurrentMedicationOrdersTest} and
 * {@code AllergyQuestionConflictingOrderContextTest} in the api module; this class is the half they cannot
 * see — that the controller publishes it on the blocking {@code /search} response and the SSE {@code done}
 * event, as two fields in JSON, and in the XML shape README states; {@code /chartalerts} is
 * {@code ChartSearchAiChartAlertsTest.aStandingAlertNamesTheOrderItIsAboutByDisplayAndUuid}.
 */
public class ChartSearchAiCurrentMedicationOrdersTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String ANSWER = "The patient has a recorded allergy to Ibuprofen [1].";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** The orders stamped on the chip; replaced by the cases that need another arrangement of them. */
	private PatientClinicalContext.ActiveDrugOrder[] orders;

	@BeforeEach
	public void setUp() {
		orders = new PatientClinicalContext.ActiveDrugOrder[] {
				SafetyWarningFixtures.activeOrder("uuid-advil", "Advil 400mg"),
				SafetyWarningFixtures.activeOrder("uuid-advil-second", "Advil 400mg") };
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new CurrentOrderChipStubService());
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();
		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
	}

	/** The allergen arm's current-medication chip, stamped as {@code DrugSafetyValidator} stamps it. */
	private ChartSearchService.ChartAnswer answer() {
		return new ChartSearchService.ChartAnswer(ANSWER,
				Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
				Arrays.asList(SafetyWarningFixtures.aboutCurrentOrders(
						SafetyWarningFixtures.recordedAllergenContraindication("Ibuprofen",
								"The patient has a recorded allergy to Ibuprofen.", true),
						orders)),
				null, null, null);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> searchPayload() {
		ResponseEntity<Object> response = controller.search(RestControllerContext.searchBody("any allergies?"));
		assertEquals(HttpStatus.OK, response.getStatusCode(), "the handler must have reached serialization");
		Map<String, Object> payload = (Map<String, Object>) response.getBody();
		assertNotNull(payload, "no response body");
		return payload;
	}

	/** The one chip's orders AS JSON — the payload map carries the module's own objects, so the field names a
	 *  client reads come from the mapper's view of that class. */
	private static JsonNode ordersOfOnlyChip(JsonNode chips) {
		assertNotNull(chips, "no safetyWarnings key");
		assertEquals(1, chips.size(), "one chip is what this arrangement raises, was: " + chips);
		JsonNode named = chips.get(0).get("currentMedicationOrders");
		assertNotNull(named, "every chip states which of her orders it is about: " + chips);
		return named;
	}

	@Test
	public void theChipNamesEveryOrderItIsAboutByDisplayAndUuid() {
		JsonNode named = ordersOfOnlyChip(MAPPER.valueToTree(searchPayload()).get("safetyWarnings"));

		assertEquals(2, named.size(), "both prescriptions, though they share a display, was: " + named);
		assertEquals("Advil 400mg", named.get(0).get("orderDisplay").asText());
		assertEquals("uuid-advil", named.get(0).get("orderUuid").asText());
		assertEquals("Advil 400mg", named.get(1).get("orderDisplay").asText());
		assertEquals("uuid-advil-second", named.get(1).get("orderUuid").asText());
	}

	@Test
	public void theDoneEventCarriesEachOrderAsExactlyTwoFields() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), "any allergies?", new User(3), false);

		JsonNode named = ordersOfOnlyChip(SseEvents.dataOfType(out, "done", MAPPER).get("safetyWarnings"));
		assertEquals(2, named.size(), "was: " + named);
		JsonNode first = named.get(0);
		assertEquals(2, first.size(), "exactly the two fields, so a public getter added to the class does not "
				+ "silently become a third: " + first);
		assertTrue(first.has("orderDisplay") && first.has("orderUuid"), "was: " + first);
	}

	@Test
	public void aChipWithNoStampedOrderStatesAnEmptyListRatherThanOmittingTheKey() {
		orders = new PatientClinicalContext.ActiveDrugOrder[0];

		JsonNode named = ordersOfOnlyChip(MAPPER.valueToTree(searchPayload()).get("safetyWarnings"));
		assertTrue(named.isArray() && named.size() == 0, "was: " + named);
	}

	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		// The accessor hands out an unmodifiable list, which XStream refuses (see serializeSafetyWarnings);
		// publish it as handed and every chip-carrying XML response is a 500, the empty case included.
		XmlPayloads.assertMarshals(searchPayload(), "a chip naming its orders");

		orders = new PatientClinicalContext.ActiveDrugOrder[0];
		XmlPayloads.assertMarshals(searchPayload(), "a chip with no stamped order");
	}

	@Test
	public void anXmlClientReceivesEachOrderUnderTheClassNamedElementAndNoUuidElementWhereItIsUnknown()
			throws Exception {
		// README's XML contract for the key: an element named after the module's class, as for
		// chartOrderBridges, and — where the order's uuid is unknown — no orderUuid element at all.
		orders = new PatientClinicalContext.ActiveDrugOrder[] {
				SafetyWarningFixtures.activeOrder(null, "Nurofen 200mg") };

		String xml = XmlPayloads.marshal(searchPayload(), "a chip naming an order with no uuid");

		assertTrue(xml.contains("<org.openmrs.module.chartsearchai.reference.SafetyWarning_-CurrentMedicationOrder>"),
				"was: " + xml);
		assertTrue(xml.contains("<orderDisplay>Nurofen 200mg</orderDisplay>"), "was: " + xml);
		assertFalse(xml.contains("orderUuid"), "was: " + xml);
	}

	/** Jackson reads the entry class's GETTERS and XStream its FIELDS, so every field needs a public getter of
	 *  its own name — {@code ChartSearchAiChartOrderBridgeTest}'s guard, for this class. */
	@Test
	public void everyFieldAnXmlClientReceivesIsAFieldAJsonClientReceives() {
		XmlPayloads.assertEveryFieldHasAPublicGetterOfItsName(SafetyWarning.CurrentMedicationOrder.class);
	}

	@Test
	public void theKeyIsWrittenInOnePlaceStraightOffTheChip() throws Exception {
		// Counts literals in the controller's own source, so a second writer in a sibling class is outside
		// it — the scope ChartSearchAiChartOrderBridgeTest's twin of this case states.
		String source = ChartSearchAiStreamingTest.controllerSource();

		assertEquals(1, ChartSearchAiStreamingTest.occurrences(source, "\"currentMedicationOrders\""),
				"the key is written inside the one method that builds a chip's wire map");
		assertEquals(1, ChartSearchAiStreamingTest.occurrences(source, "warning.currentMedicationOrders()"),
				"and read straight off the chip once, never re-resolved from her orders (#151)");
	}

	private class CurrentOrderChipStubService implements ChartSearchService {

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question, Consumer<String> tokenConsumer) {
			return searchStreaming(patient, question, tokenConsumer, r -> { }, c -> { }, a -> { });
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question, Consumer<String> tokenConsumer,
				Consumer<String> reasoningConsumer, Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			tokenConsumer.accept(ANSWER);
			citationsConsumer.accept(answer().getReferences());
			// Production's own early-done shape: built before validation runs, so it carries no chips.
			ungroundedAnswerConsumer.accept(new ChartSearchService.ChartAnswer(ANSWER,
					Collections.<ChartSearchService.RecordReference> emptyList()));
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
