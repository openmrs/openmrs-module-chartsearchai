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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.ChartSearchService.UnstatedDosingCeiling;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The dosing ceiling an answer quoted, and the stricter one it left unstated, reach the wire (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/276">#276</a>).
 *
 * <p>On the reported response the answer led with a substance's canonical row ceiling — forty times
 * the one published for the presentation the patient was charted on — cited the record that stated
 * both numbers, and nothing a client could read said so. Issue #274 had already put the stricter
 * number into that record; what it did not settle is which one the answer leads with, and the chips
 * cannot say either: the overdose arm compares a DOSE against a ceiling and
 * {@code DrugSafetyValidator.LIMIT_CUE} exists precisely so a recited ceiling is invisible to it.
 * That is the shape #354 answered for the drug-class note, #336 for the interaction extent, #377 for
 * a misattributed citation and #337 for a dropped rating, and this key is the same remedy.
 *
 * <p>What the value MEANS — that it is not a claim the answer is WRONG, why an empty list is not a
 * certificate, and which residues the check cannot see — is pinned one layer down by
 * {@code DosingCeilingFidelityTest} and is canonical at
 * {@code ChartSearchService.UnstatedDosingCeiling}. Here the subject is the wire: that the key
 * reaches every surface, that its three fields are spelled {@code citation}, {@code statedCeiling}
 * and {@code unstatedCeiling}, that {@code null} and empty survive as themselves, and that it
 * marshals for an XML client, which is the one shape a list-valued key is already known to break
 * (issue #347).
 */
public class ChartSearchAiUnstatedDosingCeilingTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String QUESTION = "What is the maximum daily dose of aspirin for this patient?";

	/** The reported answer's shape: the canonical row's ceiling, attributed to that row and its band,
	 *  citing the record that states both numbers. Nothing here is parsed — the check runs one layer
	 *  down — but a canned answer that looked nothing like the defect would make this file's premise
	 *  unreadable. */
	private static final String MODEL_ANSWER = "The maximum daily dose of Acetylsalicylic acid for "
			+ "ages 0-120 is 4000 mg/day [113].";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** What the module states per case, reset per case. TWO entries whose numbers all differ, so a
	 *  serializer writing the first entry's ceilings onto every entry — or swapping the two fields —
	 *  cannot stay green. */
	private List<UnstatedDosingCeiling> stated;

	@BeforeEach
	public void setUp() {
		stated = Collections.unmodifiableList(Arrays.asList(
				new UnstatedDosingCeiling(113, "4000 mg/day", "300 mg/day"),
				new UnstatedDosingCeiling(114, "2000 mg/day", "500 mg/day")));
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new UnstatedCeilingAnswerStubService());
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();
		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
	}

	private ChartSearchService.ChartAnswer answer() {
		return new ChartSearchService.ChartAnswer(MODEL_ANSWER,
				Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
				Collections.<SafetyWarning> emptyList(), null, null, null, null, null, null, null,
				stated, null, null, null, null, null);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> searchPayload() {
		ResponseEntity<Object> response = controller.search(RestControllerContext.searchBody(QUESTION));
		assertEquals(HttpStatus.OK, response.getStatusCode(),
				"the handler must have reached serialization");
		Map<String, Object> payload = (Map<String, Object>) response.getBody();
		assertNotNull(payload, "no response body");
		return payload;
	}

	private JsonNode eventData(String eventType) throws Exception {
		return SseEvents.dataOfType(out, eventType, MAPPER);
	}

	/**
	 * The defect, on the wire: an answer that quoted one of a substance's ceilings and left the
	 * stricter one unstated now says which number it left out, where before this key nothing on the
	 * response distinguished it from an answer that had stated both.
	 */
	@Test
	public void theSearchResponseNamesTheCeilingTheAnswerLeftUnstated() {
		Map<String, Object> payload = searchPayload();

		assertTrue(payload.containsKey("unstatedDosingCeilings"),
				"the blocking /search response must state which cited records it found a stricter "
						+ "ceiling unstated for: " + payload);
		assertEquals(
				Arrays.asList(entry(113, "4000 mg/day", "300 mg/day"),
						entry(114, "2000 mg/day", "500 mg/day")),
				payload.get("unstatedDosingCeilings"),
				"each entry pairs the citation with the ceiling the answer quoted AND the stricter "
						+ "one from the same record it did not — the citation alone would leave a "
						+ "client unable to say what the number was: " + payload);
		// What made the defect invisible: the overdose chip reads a DOSE, and a recited ceiling is
		// deliberately not one, so no chip field carries this news however the arms are configured.
		assertEquals(0, ((List<?>) payload.get("safetyWarnings")).size(),
				"precondition: this stub raises no chip, so no chip field carries the news either");
	}

	/**
	 * Empty and null are different statements and both have to survive serialization as themselves.
	 * Empty says the check ran and named no record; null says this producer made no measurement —
	 * which is what the early {@code done} of the async path carries, the check running after that
	 * handoff.
	 */
	@Test
	public void anEmptyStatementAndNoStatementAreDifferentOnTheWire() {
		stated = Collections.emptyList();
		Map<String, Object> empty = searchPayload();
		assertTrue(empty.containsKey("unstatedDosingCeilings"),
				"the key must be present for a measurement of none: " + empty);
		assertEquals(Collections.emptyList(), empty.get("unstatedDosingCeilings"),
				"a check that ran and named no record states an empty list, not null");

		stated = null;
		Map<String, Object> none = searchPayload();
		assertTrue(none.containsKey("unstatedDosingCeilings"),
				"the key must be present even where the module states nothing: " + none);
		assertEquals(null, none.get("unstatedDosingCeilings"),
				"no measurement is null, and must not be flattened to an empty list");
	}

	@Test
	public void theDoneEventNamesItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), false);

		JsonNode done = eventData("done");
		assertTrue(done.has("unstatedDosingCeilings"),
				"the done event carried no unstatedDosingCeilings key");
		assertEquals(2, done.get("unstatedDosingCeilings").size());
		assertEquals(113, done.get("unstatedDosingCeilings").get(0).get("citation").asInt());
		assertEquals("4000 mg/day",
				done.get("unstatedDosingCeilings").get(0).get("statedCeiling").asText());
		assertEquals("300 mg/day",
				done.get("unstatedDosingCeilings").get(0).get("unstatedCeiling").asText());
		assertEquals("500 mg/day",
				done.get("unstatedDosingCeilings").get(1).get("unstatedCeiling").asText(),
				"and the SECOND entry carries its own ceilings, not the first one's");
	}

	/**
	 * With async grounding the early {@code done} states NOTHING and the trailing {@code grounded}
	 * carries the measurement. What this case pins is the CONTROLLER half of that — that an answer
	 * stating nothing is serialized as {@code null} on the early event and not flattened to an empty
	 * list, which would tell a client the answer's ceilings had been compared and found complete.
	 * That PRODUCTION states nothing there is a different claim and is pinned one layer down, by
	 * {@code DosingCeilingFidelityTest.theStreamingPathCarriesTheSameStatement}, which drives the
	 * real orchestration; the stub below only reproduces its shape.
	 */
	@Test
	public void theEarlyDoneStatesNothingAndTheGroundedEventCarriesTheMeasurement() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), QUESTION, new User(3), true);

		JsonNode done = eventData("done");
		assertTrue(done.has("unstatedDosingCeilings"),
				"the early done must still carry the key, so a client reads one field unconditionally");
		assertTrue(done.get("unstatedDosingCeilings").isNull(),
				"the check has not run when this event is emitted, and an empty list here would tell a "
						+ "client the answer's ceilings had been compared and found complete");

		JsonNode grounded = eventData("grounded");
		assertEquals(2, grounded.get("unstatedDosingCeilings").size(),
				"the trailing event is where the measurement lands");
		assertEquals("300 mg/day",
				grounded.get("unstatedDosingCeilings").get(0).get("unstatedCeiling").asText());
	}

	/**
	 * XStreamMarshaller is the converter openmrs-core selects for {@code Accept: application/xml},
	 * and it refuses {@code Collections}' immutable wrappers — publishing an accessor's own list
	 * turned every chip-carrying XML response into a 500 in issue #347, the empty case included. All
	 * three shapes this key can take have to marshal.
	 */
	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(searchPayload(), "a stated set of unstated ceilings");
		stated = Collections.emptyList();
		XmlPayloads.assertMarshals(searchPayload(), "a measurement of none");
		stated = null;
		XmlPayloads.assertMarshals(searchPayload(), "no measurement at all");
	}

	/**
	 * Structural: exactly one write of the key, so a second and divergent one cannot be added. Stated
	 * as what it holds rather than as what would be useful. It reddens on the literal being wrapped
	 * across a line or moved into a quoted comment, and it does NOT see a fourth payload-building
	 * method that never calls {@code putModuleStatements}, which is the residue every sibling of this
	 * case records.
	 */
	@Test
	public void theKeyIsWrittenInExactlyOnePlace() throws Exception {
		String source = ChartSearchAiStreamingTest.controllerSource();

		int keys = ChartSearchAiStreamingTest.occurrences(source, "\"unstatedDosingCeilings\"");
		assertEquals(1, keys,
				"the unstatedDosingCeilings key must be written in exactly one place, beside the "
						+ "chips and the module's other statements (issue #276). Found " + keys
						+ " writes of it.");
	}

	/** The wire shape of one entry, spelled out as a map rather than compared through the value
	 *  type — the subject here is what a JSON client receives, so the KEYS are part of the claim and
	 *  a renamed one must redden. */
	private static Map<String, Object> entry(int citation, String statedCeiling,
			String unstatedCeiling) {
		Map<String, Object> expected = new LinkedHashMap<String, Object>();
		expected.put("citation", Integer.valueOf(citation));
		expected.put("statedCeiling", statedCeiling);
		expected.put("unstatedCeiling", unstatedCeiling);
		return expected;
	}

	/** An answer that quotes one ceiling of a substance filed as several rows, on both the classic
	 *  and the async shapes. */
	private class UnstatedCeilingAnswerStubService implements ChartSearchService {

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
			tokenConsumer.accept(MODEL_ANSWER);
			citationsConsumer.accept(answer().getReferences());
			// Production's own shape: the early answer is built BEFORE this check runs, so it states
			// no measurement whatever the final one says.
			ungroundedAnswerConsumer.accept(new ChartSearchService.ChartAnswer(MODEL_ANSWER,
					Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
					Collections.<SafetyWarning> emptyList(), null, null, null, null, null));
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
