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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A bounded pairwise interaction list says so ON THE WIRE (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/336">#336</a>).
 *
 * <p>Measured on the 3.7.1 standalone before this change: a screen that found 18 above-floor pairs
 * and reported 10 produced a response whose answer, whose 10 {@code interaction} chips and whose
 * every {@code references[].withheldInteractions} were indistinguishable from a complete screen's.
 * The withheld eight existed in a server-side WARN and nowhere a client could read. So the contract
 * these cases hold is that {@code interactionPairs} reaches EVERY surface that carries chips —
 * there are three — and that the key is present even when nothing was stated, since a client reads
 * it unconditionally.
 *
 * <p>The last case is the one that survives a refactor: the three sites are three because someone
 * counted them today, and a fourth added later would publish chips with no statement beside them
 * unless it goes through the one helper. That is asserted structurally, on the controller's own
 * source, rather than by keeping a list of sites here — the list is what goes stale.
 *
 * <p>The counts themselves are not this class's business: they are pinned through the real
 * {@code validate} by {@code PairChipExtentContextTest}, and their transport to the answer by
 * {@code LlmInferenceServicePairChipExtentTest}. The fixture here states them through
 * {@link PairChipExtent.Sink}, the producer's own channel, because that is the only way to make one.
 */
public class ChartSearchAiInteractionPairExtentTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The ticket's own measurement: 18 pairs found, 10 reported, 8 withheld in silence. */
	private static final int FOUND = 18;

	private static final int REPORTED = 10;

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** Null for the answer that states nothing; set per case before the handler runs. */
	private PairChipExtent stated;

	@BeforeEach
	public void setUp() {
		stated = extent(FOUND, REPORTED);
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new CappedScreenStubService());
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();
		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
	}

	/** Built through the producer's own channel — {@code of} is not public, deliberately. */
	private static PairChipExtent extent(int found, int reported) {
		PairChipExtent.Sink sink = new PairChipExtent.Sink();
		sink.record(found, reported);
		return sink.stated();
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(7);
		p.setUuid("uuid-7");
		return p;
	}

	private ChartSearchService.ChartAnswer answer() {
		return new ChartSearchService.ChartAnswer("Ten interactions were found [1].",
				Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
				Arrays.asList(new SafetyWarning("interaction", "Warfarin",
						"Warfarin interacts with active order Amiodarone — Major.")),
				null, null, stated);
	}

	/** The {@code /search} response body. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> searchPayload() {
		Map<String, String> body = new HashMap<String, String>();
		body.put("patient", RestControllerContext.PATIENT_UUID);
		body.put("question", "Please screen her current medications for drug interactions.");

		ResponseEntity<Object> response = controller.search(body);
		assertEquals(HttpStatus.OK, response.getStatusCode(), "the handler must have reached serialization");
		Map<String, Object> payload = (Map<String, Object>) response.getBody();
		assertNotNull(payload, "no response body");
		return payload;
	}

	private JsonNode eventData(String eventType) throws Exception {
		SseEvent event = SseEvents.ofType(out, eventType);
		assertNotNull(event, "no '" + eventType + "' event was emitted");
		return MAPPER.readTree(event.data);
	}

	@Test
	public void theSearchResponseSaysHowManyPairsWereFoundBesideHowManyItReported() {
		Map<String, Object> payload = searchPayload();

		assertTrue(payload.containsKey("interactionPairs"),
				"the blocking /search response must state how bounded its chip list is: " + payload);
		@SuppressWarnings("unchecked")
		Map<String, Object> pairs = (Map<String, Object>) payload.get("interactionPairs");
		assertNotNull(pairs, "this answer states an extent, so the key must carry it");
		assertEquals(FOUND, pairs.get("found"));
		assertEquals(REPORTED, pairs.get("reported"));
		// The defect itself: the chips alone could not tell this response from a complete screen.
		assertEquals(1, ((List<?>) payload.get("safetyWarnings")).size(),
				"precondition: the chips beside it say nothing about the eight that were withheld");
	}

	@Test
	public void theKeyIsPresentAndNullWhenNothingWasStated() {
		// Absence of a measurement is itself something a client must be able to read, and it is not
		// "the screen was complete". Present-and-null rather than omitted, like `source` and
		// `grounded` beside it, so a client reads one field unconditionally.
		stated = null;
		Map<String, Object> payload = searchPayload();

		assertTrue(payload.containsKey("interactionPairs"),
				"the key must be present even when the producer stated no measurement: " + payload);
		assertEquals(null, payload.get("interactionPairs"));
	}

	@Test
	public void theDoneEventSaysItToo() throws Exception {
		controller.streamAnswer(out, patient(), "Please screen her current medications.", new User(3),
				false);

		JsonNode pairs = eventData("done").get("interactionPairs");
		assertNotNull(pairs, "the done event carried no interactionPairs key");
		assertEquals(FOUND, pairs.get("found").asInt());
		assertEquals(REPORTED, pairs.get("reported").asInt());
	}

	@Test
	public void theTrailingGroundedEventSaysItToo() throws Exception {
		// With async grounding the chips arrive on `grounded`, not on `done` — so that event is the
		// one a client consuming safety chips has to read, and a statement missing there would leave
		// exactly the clients that render chips unable to tell a capped list from a complete one.
		controller.streamAnswer(out, patient(), "Please screen her current medications.", new User(3),
				true);

		JsonNode grounded = eventData("grounded");
		assertTrue(grounded.has("safetyWarnings"),
				"precondition: the trailing event is where the chips arrive in async mode");
		JsonNode pairs = grounded.get("interactionPairs");
		assertNotNull(pairs, "the grounded event carried no interactionPairs key");
		assertEquals(FOUND, pairs.get("found").asInt());
		assertEquals(REPORTED, pairs.get("reported").asInt());
	}

	@Test
	public void noEmissionSiteCanPublishChipsWithoutSayingHowBoundedTheyAre() throws IOException {
		// Structural, and it is what makes the three cases above hold for a site nobody has written
		// yet: the chips and the statement about them are written by ONE method, so a fourth payload
		// added later cannot carry one and forget the other. Two payload sites kept in step by hand
		// is the condition the search_mode column's own comment records as having held one value for
		// 6036 rows.
		String source = new String(Files.readAllBytes(Paths.get(
				"src/main/java/org/openmrs/module/chartsearchai/web/rest/ChartSearchAiRestController.java")),
				StandardCharsets.UTF_8);

		// The KEY, not the helper, is what a payload actually carries — so this is the assertion that
		// also catches a site inlining the serialization loop instead of calling the helper.
		int keys = occurrences(source, "\"safetyWarnings\"");
		assertEquals(1, keys,
				"the safetyWarnings key must be written in exactly one place, beside the statement of "
						+ "how bounded those chips are (issue #336). Found " + keys + " writes of it.");
		int calls = occurrences(source, "serializeSafetyWarnings(");
		assertEquals(2, calls,
				"serializeSafetyWarnings must be named exactly twice — its own declaration and the one "
						+ "call inside putSafetyChips. A third naming is an emission site building the "
						+ "chip array for itself. Found " + calls + ".");
		assertTrue(source.contains("target.put(\"safetyWarnings\", serializeSafetyWarnings(answer.getSafetyWarnings()));")
				&& source.contains("target.put(\"interactionPairs\", serializePairChipExtent(answer.getPairChipExtent()));"),
				"putSafetyChips must still write BOTH keys; splitting them re-opens the defect");
		// What this does NOT reach: a payload that publishes the chips under some OTHER key name. No
		// source scan can, and nothing in the module does it today — mutate a site and read which of
		// the three assertions above answers, rather than trusting this one to answer for all of them.
	}

	private static int occurrences(String haystack, String needle) {
		int count = 0;
		for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
			count++;
		}
		return count;
	}

	private class CappedScreenStubService implements ChartSearchService {

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
			tokenConsumer.accept("Ten interactions were found [1].");
			citationsConsumer.accept(answer().getReferences());
			ungroundedAnswerConsumer.accept(answer());
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
