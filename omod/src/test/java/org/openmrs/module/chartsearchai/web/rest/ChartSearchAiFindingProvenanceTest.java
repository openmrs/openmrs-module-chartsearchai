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
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.ChartSearchService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wire-contract test for issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/305">#305</a>: a citation
 * the MODULE attached says so.
 *
 * <p>The module publishes the chart record an injected {@code safety_finding} fired on whenever the
 * model cites that finding, so the clinician's click-through no longer depends on the model having
 * cited the record. Such a citation is not the model's claim, and two of its properties differ from
 * every citation that existed before: the answer prose carries no {@code [N]} marker for it, so a
 * client that highlights the marker for a clicked chip has nothing to highlight; and its
 * {@code grounded} is always {@code null}, because there is no claim of the model's to check rather
 * than a verdict being withheld.
 *
 * <p>Neither is derivable from the other fields — an unmarked citation is indistinguishable on the
 * wire from one the model listed in its structured array alone, which is a shape that has always
 * existed and IS graded. So the fact is published, and this is what pins that it reaches the client:
 * without the key the module's own citation reads as the model's, and a {@code null} verdict on it
 * reads as one that could not be verified.
 *
 * <p>Asserted against the real controller's real serialization, on every event that carries
 * references, since a client may render any one of them alone.
 */
public class ChartSearchAiFindingProvenanceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The chart record the module attached — the recorded allergy the finding fired on. */
	private static final int ATTACHED = 12;

	/** The finding the model cited, which is what brought the record with it. */
	private static final int CITED_FINDING = 75;

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new AttachedCitationStubService());
		out = new ByteArrayOutputStream();
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(7);
		p.setUuid("uuid-7");
		return p;
	}

	private List<JsonNode> referencesOf(String eventType) throws Exception {
		SseEvent event = SseEvents.ofType(out, eventType);
		assertNotNull(event, "no '" + eventType + "' event was emitted");
		JsonNode refs = MAPPER.readTree(event.data).get("references");
		assertNotNull(refs, "'" + eventType + "' event carried no references array");
		List<JsonNode> list = new ArrayList<JsonNode>();
		for (JsonNode ref : refs) {
			list.add(ref);
		}
		assertEquals(2, list.size(), "'" + eventType + "' event must carry both fixture citations");
		return list;
	}

	/** Each reference's {@code index:attachedByTheModule}, in emitted order — with the key's presence
	 *  asserted first, so a dropped key fails by naming itself rather than by a null read. */
	private List<String> attributionOf(String eventType) throws Exception {
		List<String> out = new ArrayList<String>();
		for (JsonNode ref : referencesOf(eventType)) {
			JsonNode attached = ref.get("attachedByTheModule");
			assertNotNull(attached, "no attachedByTheModule key on reference [" + ref.get("index")
					+ "] of '" + eventType + "': " + ref);
			assertTrue(attached.isBoolean(), "attachedByTheModule must be a boolean, was: " + attached);
			out.add(ref.get("index").asInt() + ":" + attached.asBoolean());
		}
		return out;
	}

	@Test
	public void doneEvent_saysWhichCitationTheModuleAttached() throws Exception {
		controller.streamAnswer(out, patient(), "can I give ibuprofen?", new User(3), false);

		assertEquals(Arrays.asList(ATTACHED + ":true", CITED_FINDING + ":false"),
				attributionOf("done"),
				"the chart record the module attached must say so, and the finding the model cited "
						+ "must not — a client cannot tell them apart from any other field, since an "
						+ "unmarked citation is also what the model produces when it lists a record in "
						+ "its structured array alone");
	}

	@Test
	public void doneEvent_publishesNoVerdictForTheAttachedCitation() throws Exception {
		// The other half of what the key is for. The verifier withholds the verdict because there is no
		// claim of the model's to check; on the wire that is indistinguishable from "could not verify",
		// and the key is what tells a client which it is reading.
		controller.streamAnswer(out, patient(), "can I give ibuprofen?", new User(3), false);

		JsonNode attached = referencesOf("done").get(0);
		assertEquals(ATTACHED, attached.get("index").asInt(), "precondition: the attached record first");
		assertTrue(attached.has("grounded") && attached.get("grounded").isNull(),
				"the grounded key must be present and null: " + attached);
	}

	@Test
	public void referencesEvent_saysTheSameThingAsDone() throws Exception {
		// The early event is what a client renders while Tier-2 verification is still running, so a
		// chip whose attribution only arrives with the answer would relabel itself mid-render.
		controller.streamAnswer(out, patient(), "can I give ibuprofen?", new User(3), false);

		assertEquals(attributionOf("done"), attributionOf("references"),
				"the early references event must carry the same attribution as done");
	}

	@Test
	public void groundedEvent_saysItToo_whenAsyncGroundingIsOn() throws Exception {
		// Its own serializeReferences call site, and the only one a client consuming verdicts has to
		// read. With literals rather than a comparison against done, because "identical to done" is
		// also satisfied by both sites dropping the key together.
		controller.streamAnswer(out, patient(), "can I give ibuprofen?", new User(3), true);

		assertEquals(Arrays.asList(ATTACHED + ":true", CITED_FINDING + ":false"),
				attributionOf("grounded"),
				"the trailing grounded event must publish the attribution as well");
	}

	/**
	 * The issue's second measured form: the answer asserts the patient's recorded allergy, cites the
	 * module's own finding alone, and the module attaches the allergy record the finding fired on. The
	 * attached citation carries no verdict, as the verifier leaves it.
	 */
	private static ChartSearchService.ChartAnswer answer() {
		return new ChartSearchService.ChartAnswer(
				"No — Ibuprofen should not be given: the patient has a recorded allergy to Ibuprofen ["
						+ CITED_FINDING + "].",
				Arrays.asList(
						new ChartSearchService.RecordReference(ATTACHED, "allergy", "u12", null, null,
								null, 0, true),
						new ChartSearchService.RecordReference(CITED_FINDING, "safety_finding",
								"contraindication:Ibuprofen", null, null)));
	}

	private static class AttachedCitationStubService implements ChartSearchService {

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
			ungroundedAnswerConsumer.accept(answer());
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
