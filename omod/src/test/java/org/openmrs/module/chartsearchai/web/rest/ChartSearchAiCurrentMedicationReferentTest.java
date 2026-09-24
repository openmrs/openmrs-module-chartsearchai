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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.reference.SafetyWarningFixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A safety chip says whether the module raised it from one of the patient's own active orders (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/527">#527</a>).
 *
 * <p>Asked "any allergies?", the module checks her active orders against her recorded allergies, and a
 * chip raised that way is about a medication she already takes. Asked "can I give her ibuprofen?" of a
 * patient on no ibuprofen, the same allergen arm raises a chip about a drug being proposed. Both carry
 * one sentence, "The patient has a recorded allergy to Ibuprofen.", and on the issue's reproduction both
 * reached the client as one byte-identical object. The module held the difference on
 * {@code SafetyWarning.isAboutACurrentMedication()}; on the model's path it stated it in the injected
 * record, which reaches a client only if the model cites it — and there it did not.
 *
 * <p><b>Pairs, each one sentence carried by two chips that differ only in that answer.</b> So a value
 * computed from the other published fields cannot agree with both chips of a pair. The contraindication
 * pair is the issue's own. The rule-interaction pair is the screening arm's rated chip beside the
 * drug-in-play arm's, and is here so that a value narrowed by chip TYPE cannot agree with every chip; both
 * of its chips carry a non-empty {@code namedPartners}, as every chip
 * {@code DrugSafetyValidator.interactionWarning} builds does, so a value narrowed on it cannot either. The
 * shared-substance pair is issue #477's two findings, an UNRATED interaction naming SEVERAL orders, so a
 * value narrowed on {@code severity} or on how many orders a chip names cannot agree either. Each pair's
 * {@code true} chip is a population a narrowing to the others would drop unseen — ADR Decision 92's lesson
 * (issue #412) — and {@code ChartSearchAiSafetyWarningSeverityWireTest}'s reflective guard holds another,
 * a curated rule's.
 *
 * <p>What is not asserted here, because something else holds it. That the published value is the
 * accessor's own reading, over a fixture of its own:
 * {@code ChartSearchAiSafetyWarningSeverityWireTest.everyPublicZeroArgumentAccessorOfAWarningNamesAKeyOnTheWire}.
 * That the key reaches {@code GET /chartsearchai/chartalerts}:
 * {@code ChartSearchAiChartAlertsTest.everyFindingIsShapedExactlyAsASearchChipIs}, which reads the key set
 * off a {@code /search} chip. And that the real arms give the chips the wire publishes this answer on the
 * issue's own shape: {@code LlmInferenceServiceCurrentMedicationReferentContextTest}, in the api module,
 * which this module's tests cannot reach.
 */
public class ChartSearchAiCurrentMedicationReferentTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The key under test. */
	private static final String REFERENT = "aboutACurrentMedication";

	/** The allergen arm's identity sentence, as the issue's two patients both received it. */
	private static final String ALLERGY_DETAIL = "The patient has a recorded allergy to Ibuprofen.";

	/** A rule chip's sentence as either active-order arm words it for this pair. */
	private static final String INTERACTION_DETAIL = "Salicylic acid interacts with active order Methotrexate "
			+ "— Major. Salicylates may interfere with the renal elimination of methotrexate.";

	/** Two of her orders carrying the same substances, as issue #477's reproduction named them. */
	private static final List<String> TB_ORDERS = Arrays.asList("Isoniazid / pyrazinamide / rifampin",
		"Rifampicin isoniazid pyrazinamide and ethambutol 150/75/400/275mg");

	/** The substances those two orders share. */
	private static final String SHARED_SUBSTANCES = "Isoniazid, Pyrazinamide and Rifampicin (rifampin)";

	/** A shared-substance sentence, carried by both chips of the pair. */
	private static final String SHARED_DETAIL = SHARED_SUBSTANCES + " are in active orders " + TB_ORDERS.get(0)
			+ " and " + TB_ORDERS.get(1) + " — possible duplicate therapy";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new ReferentChipStubService());
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();
		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
	}

	/**
	 * Chips 0, 2 and 4 are raised from her active orders; chips 1, 3 and 5 carry their sentences verbatim
	 * and are about a drug put in play. Each chip is built by the factory the arm that raises it uses, with
	 * the answer that arm passes it — see {@link SafetyWarningFixtures}.
	 */
	private static List<SafetyWarning> chips() {
		return Arrays.asList(
			SafetyWarningFixtures.recordedAllergenContraindication("Ibuprofen", ALLERGY_DETAIL, true),
			SafetyWarningFixtures.recordedAllergenContraindication("Ibuprofen", ALLERGY_DETAIL, false),
			SafetyWarningFixtures.ruleInteraction("Salicylic acid", INTERACTION_DETAIL, "Major", "Methotrexate",
				true),
			SafetyWarningFixtures.ruleInteraction("Salicylic acid", INTERACTION_DETAIL, "Major", "Methotrexate",
				false),
			SafetyWarningFixtures.ordersSharingASubstance(SHARED_SUBSTANCES, SHARED_DETAIL, TB_ORDERS),
			SafetyWarningFixtures.substanceInSeveralActiveOrders(SHARED_SUBSTANCES, SHARED_DETAIL, TB_ORDERS));
	}

	/**
	 * The chips as the {@code done} event serialized them — the JSON a client receives, since the
	 * controller serializes the SSE payloads itself. What is asked of the bytes is that the answer
	 * arrives as a JSON boolean, rather than being dropped or stringified.
	 */
	private JsonNode streamedChips() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), "Any allergies?",
			RestControllerContext.user(), false);
		JsonNode chips = SseEvents.dataOfType(out, "done", MAPPER).get("safetyWarnings");
		assertNotNull(chips, "the done event carried no safetyWarnings key");
		assertEquals(chips().size(), chips.size(), "precondition: every fixture chip, was: " + chips);
		return chips;
	}

	/**
	 * The defect itself: two chips that say the same words, one about a medication she already takes and
	 * one about a drug proposed to her, must not reach a client as one object.
	 *
	 * <p>It does not assert that {@code detail} changed, because it did not — the chip states the referent
	 * as a field, as issue #472 did for a drug the chart holds only as an ended order.
	 */
	@Test
	public void aChipAboutAMedicationSheAlreadyTakesSaysSoWhereTheSameSentenceAboutAProposalDoesNot()
			throws Exception {
		JsonNode chips = streamedChips();

		assertReferentsDiffer(chips.get(0), chips.get(1), "the issue's contraindication pair");
		assertReferentsDiffer(chips.get(2), chips.get(3), "the rule-interaction pair");
		assertReferentsDiffer(chips.get(4), chips.get(5), "the shared-substance pair");
	}

	/**
	 * {@code current} was raised from one of her active orders and {@code proposed} about a drug put in
	 * play, and every other key the wire carries is equal between them — read off the chips themselves
	 * rather than listed, so a key added later is compared too.
	 */
	private static void assertReferentsDiffer(JsonNode current, JsonNode proposed, String pair) {
		assertEquals(fieldNames(current), fieldNames(proposed),
			"precondition: " + pair + " carries one key set: " + current + " / " + proposed);
		for (String key : fieldNames(current)) {
			if (!REFERENT.equals(key)) {
				assertEquals(current.get(key), proposed.get(key), "precondition: " + pair
						+ " differs in nothing else the wire carries, but '" + key + "' did: " + current + " / "
						+ proposed);
			}
		}

		JsonNode stated = current.get(REFERENT);
		assertNotNull(stated, "a chip the module raised from one of her active orders must say so — issue #527. "
				+ pair + ": " + current);
		assertTrue(stated.isBoolean(), "as a JSON boolean, not a string: " + current);
		assertTrue(stated.asBoolean(), "the module raised this chip from one of her active orders: " + current);

		JsonNode notStated = proposed.get(REFERENT);
		assertNotNull(notStated, "the key is on every chip, not only on the ones that answer true: " + proposed);
		assertTrue(notStated.isBoolean(), "as a JSON boolean, not a string: " + proposed);
		assertEquals(false, notStated.asBoolean(),
			"a chip about a drug put in play is not raised from her active orders, and the same sentence must "
					+ "not read as one that is: " + proposed);
	}

	private static List<String> fieldNames(JsonNode chip) {
		List<String> names = new ArrayList<String>();
		chip.fieldNames().forEachRemaining(names::add);
		return names;
	}

	/** Returns this class's chips on every path the controller can take. */
	private static class ReferentChipStubService implements ChartSearchService {

		private ChartAnswer answer() {
			return new ChartAnswer("Yes — an allergy is recorded: Ibuprofen (drug allergen) [1].",
					Collections.<RecordReference> emptyList(), 0, 0, 0, chips(), null, null, null);
		}

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			return searchStreaming(patient, question, tokenConsumer, r -> { }, c -> { }, a -> { });
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			tokenConsumer.accept(answer().getAnswer());
			citationsConsumer.accept(answer().getReferences());
			// Production's own early-done shape: built before validation runs, so it carries no chips.
			ungroundedAnswerConsumer.accept(
					new ChartAnswer(answer().getAnswer(), Collections.<RecordReference> emptyList()));
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
