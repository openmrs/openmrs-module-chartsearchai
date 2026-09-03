/*
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
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
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
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.oxm.xstream.XStreamMarshaller;

import javax.xml.transform.stream.StreamResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A chip says which of the patient's own prescriptions its substance came from, ON THE WIRE (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/347">#347</a>).
 *
 * <p>Measured on the 3.7.1 standalone before this change: one response named one active order two
 * ways. The answer prose called it {@code active order Advil} — the brand its cited
 * {@code drug_order} record renders — while every chip called it {@code active order Ibuprofen},
 * the knowledge base's substance. Neither name is false, and the chip side must not be re-decided
 * (#339's reverted rounds 5-6, and CLAUDE.md), so what the response was missing is the
 * CORRESPONDENCE. A clinician reading the chip list beside the answer had to decide whether
 * {@code Advil} and {@code Ibuprofen} were one prescription or two, and on that chart the wrong
 * answer was available — she was also on {@code Aspirin 81mg}, so "three NSAIDs" read as plausible.
 *
 * <p>The module states the correspondence in the prompt too, as a clause inside the injected
 * {@code safety_finding} ({@code DrugReferenceInjector.FINDING_CHART_ORDER_LEAD}, and
 * {@code OneOrderNameAcrossAnswerAndChipTest} is that half). That is not enough on its own and the
 * repo has already measured why: a prompt record reaches a client only if the MODEL cites it, and on
 * #354's own reproduction it did not, so nothing a {@code /search} consumer read reported the fact
 * at all. Hence this key — the same settlement {@code unresolvedDrugClass} reached, one issue along.
 *
 * <p>It rides INSIDE each chip rather than beside the chip array, which is what makes "it reaches
 * every emission surface" true by construction: {@code putSafetyChips} is already the one writer of
 * {@code safetyWarnings} and {@code ChartSearchAiInteractionPairExtentTest} fails the build on a
 * second one. The last case here holds the other half of that — that the key is written from the
 * chip's own bridges at one place, so a site building the chip array for itself cannot omit it.
 */
public class ChartSearchAiChartOrderBridgeTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The ticket's own pair, in its own words: the chip names the substance, the chart names the
	 *  prescription, and neither string appears where the other one does. */
	private static final String SUBSTANCE = "Ibuprofen";

	private static final String ORDER_DISPLAY = "Advil 400mg";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	/** The chip's attributions; emptied by the cases that ask what a chip with none publishes. */
	private List<SafetyWarning.ChartOrderBridge> bridges;

	@BeforeEach
	public void setUp() {
		bridges = Arrays.asList(new SafetyWarning.ChartOrderBridge(SUBSTANCE, ORDER_DISPLAY));
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new BridgedChipStubService());
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();
		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
	}

	/** The ticket's chip: it names the KB substance, and the answer beside it names the brand. */
	private ChartSearchService.ChartAnswer answer() {
		return new ChartSearchService.ChartAnswer(
				"Acetylsalicylic acid (aspirin) interacts with active order Advil [2].",
				Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
				Arrays.asList(new SafetyWarning("interaction", "Acetylsalicylic acid (aspirin)",
						"Acetylsalicylic acid (aspirin) interacts with active order Ibuprofen — Major.",
						"Major", bridges)),
				null, null, null);
	}

	/** The {@code /search} response body. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> searchPayload() {
		ResponseEntity<Object> response =
				controller.search(RestControllerContext.searchBody("Is aspirin safe for her?"));
		assertEquals(HttpStatus.OK, response.getStatusCode(), "the handler must have reached serialization");
		Map<String, Object> payload = (Map<String, Object>) response.getBody();
		assertNotNull(payload, "no response body");
		return payload;
	}

	/** The attributions of the payload's one chip, AS JSON — the payload map carries the module's own
	 *  {@code ChartOrderBridge} objects (in a copy, see {@code serializeSafetyWarnings}), so the field
	 *  names a client actually reads come from the MAPPER's view of that class and must be asserted
	 *  through a mapper rather than off the map. */
	private JsonNode chartOrderBridgesOfOnlyChip(Map<String, Object> payload) {
		JsonNode chips = MAPPER.valueToTree(payload).get("safetyWarnings");
		assertNotNull(chips, "no safetyWarnings key");
		assertEquals(1, chips.size(), "one chip is what this arrangement raises, was: " + chips);
		JsonNode bridges = chips.get(0).get("chartOrderBridges");
		assertNotNull(bridges,
				"every chip states which of the patient's orders its substances came from: " + chips);
		return bridges;
	}

	/** The one chip's attributions as the named SSE event actually serialized them — asserted
	 *  non-empty here rather than dereferenced blind, so a serializer that publishes the key with an
	 *  empty placeholder under it (the issue #340 shape) fails with a message rather than an NPE. The
	 *  accessor-versus-key guard next door reaches that shape too, since its fixture gained a bridged
	 *  chip; what this one adds is the SSE surfaces, which that guard reads only for the keys it
	 *  reflects. */
	private JsonNode streamedChartOrderBridges(String eventType) throws Exception {
		JsonNode chips = eventData(eventType).get("safetyWarnings");
		assertNotNull(chips, "the " + eventType + " event carried no safetyWarnings key");
		JsonNode bridges = chips.get(0).get("chartOrderBridges");
		assertNotNull(bridges, "the " + eventType + " event's chip stated no attributions key");
		assertEquals(1, bridges.size(),
				"the one attribution this chip carries must reach the " + eventType + " event, was: "
						+ bridges);
		return bridges;
	}

	private JsonNode eventData(String eventType) throws Exception {
		return SseEvents.dataOfType(out, eventType, MAPPER);
	}

	@Test
	public void theChipSaysWhichPrescriptionItsSubstanceCameFrom() {
		Map<String, Object> payload = searchPayload();

		JsonNode bridges = chartOrderBridgesOfOnlyChip(payload);
		assertEquals(1, bridges.size(), "one attribution was resolved, was: " + bridges);
		assertEquals(SUBSTANCE, bridges.get(0).get("substance").asText());
		assertEquals(ORDER_DISPLAY, bridges.get(0).get("orderDisplay").asText());

		// The defect itself: the two names the response carries appear nowhere near each other, and
		// before this key nothing on the wire said they were one prescription.
		assertTrue(payload.get("answer").toString().contains("Advil"),
				"precondition: the answer names the prescription by the chart's brand");
		assertTrue(payload.get("answer").toString().indexOf(SUBSTANCE) < 0,
				"precondition: and never by the substance the chip names");
	}

	@Test
	public void theTwoHalvesAreSeparateFieldsAndNotASentenceToParse() throws Exception {
		// The reason issue #340 publishes `severity` rather than leaving a client to substring-match
		// the detail. A rendered "Ibuprofen from Advil 400mg" would put this module's prose in a slot
		// a client has to take apart, and this module rewords its prose freely.
		//
		// Read off the STREAM rather than the /search payload, because this is the one case about the
		// JSON a client receives: the payload map carries the module's own ChartOrderBridge objects, so
		// its field names come from whatever mapper serializes it later, while the SSE path writes real
		// bytes through the controller's own ObjectMapper. The /search cases assert what the payload
		// carries; this one asserts what comes out. It pins the JSON field set only — XStream marshals
		// FIELDS, not getters, so a field added to ChartOrderBridge reaches an XML client without
		// reddening this, and theWholePayloadStillMarshalsForAnXmlClient catches only a field XStream
		// REFUSES. That gap is closed structurally next door, by
		// everyFieldAnXmlClientReceivesIsAFieldAJsonClientReceives.
		controller.streamAnswer(out, RestControllerContext.patient(), "Is aspirin safe for her?", new User(3), false);
		JsonNode bridge = streamedChartOrderBridges("done").get(0);

		assertEquals(2, bridge.size(),
				"exactly the two fields, so neither side needs parsing out of the other, and a public "
						+ "getter added to ChartOrderBridge does not silently become a third: " + bridge);
		assertTrue(bridge.has("substance") && bridge.has("orderDisplay"), "was: " + bridge);
	}

	/**
	 * The two mappers read {@link SafetyWarning.ChartOrderBridge} through different views — Jackson
	 * takes its GETTERS, XStream takes its FIELDS — so the JSON contract above and the XML a client
	 * receives are two field sets that can drift apart in silence. This makes them one.
	 *
	 * <p>Structural rather than behavioural, and that is the point: the drift it catches has no
	 * observable value to assert. A field added here with no getter is published to an XML client and
	 * to nobody else, with {@link #theTwoHalvesAreSeparateFieldsAndNotASentenceToParse} (which reads
	 * JSON) and {@link #theWholePayloadStillMarshalsForAnXmlClient} (which only catches a field
	 * XStream REFUSES) both green. This repo pins that shape off {@code getDeclaredFields} elsewhere
	 * — CLAUDE.md cites {@code CoMedicationResolutionPerPassTest} for a field budget read the same
	 * way.
	 *
	 * <p>It is ONE-directional, deliberately: a field implies a getter, never the converse. A derived
	 * getter over no field of its own would become a JSON field an XML client does not receive, which
	 * is a divergence in the other direction and one this class's JSON case already sees, since it
	 * asserts the field COUNT.
	 *
	 * <p>Static and synthetic members are excluded — {@code static} because XStream does not marshal
	 * one, and synthetic because a compiler writes {@code this$0} and jacoco writes
	 * {@code $jacocoData} into classes that never declared them.
	 */
	@Test
	public void everyFieldAnXmlClientReceivesIsAFieldAJsonClientReceives() throws Exception {
		List<String> problems = new ArrayList<String>();
		for (Field f : SafetyWarning.ChartOrderBridge.class.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
				continue;
			}
			String getter = "get" + Character.toUpperCase(f.getName().charAt(0)) + f.getName().substring(1);
			Method m = null;
			try {
				m = SafetyWarning.ChartOrderBridge.class.getDeclaredMethod(getter);
			}
			catch (NoSuchMethodException absent) {
				problems.add("field '" + f.getName() + "' has no " + getter + "()");
				continue;
			}
			if (!Modifier.isPublic(m.getModifiers())) {
				problems.add("field '" + f.getName() + "' has " + getter + "() but it is not public");
			}
		}
		assertEquals(Collections.<String> emptyList(), problems,
				"XStream marshals this class's FIELDS and Jackson reads its GETTERS, so every field "
						+ "needs a public getter of its own name or an XML client receives something a "
						+ "JSON client never sees (issue #347)");
	}

	@Test
	public void anEmptyAttributionListIsStatedRatherThanOmitted() {
		// Empty says there was no attribution to show. It is NOT a statement that the chart records
		// those substances — SafetyWarning.chartOrderBridges() is canonical for what empty covers, and
		// this comment said the refuted thing until it was swept. What this case pins is that empty is
		// present rather than omitted, so a client reads one field unconditionally.
		bridges = Collections.<SafetyWarning.ChartOrderBridge> emptyList();

		JsonNode published = chartOrderBridgesOfOnlyChip(searchPayload());
		assertTrue(published.isArray(), "an empty statement is still an array, was: " + published);
		assertEquals(0, published.size(), "was: " + published);
	}

	@Test
	public void theDoneEventSaysItToo() throws Exception {
		controller.streamAnswer(out, RestControllerContext.patient(), "Is aspirin safe for her?", new User(3), false);

		assertEquals(ORDER_DISPLAY,
				streamedChartOrderBridges("done").get(0).get("orderDisplay").asText());
	}

	@Test
	public void theTrailingGroundedEventSaysItToo() throws Exception {
		// With async grounding the chips arrive on `grounded`, so that is the event a client rendering
		// chips has to read — the same reason ChartSearchAiInteractionPairExtentTest checks it.
		controller.streamAnswer(out, RestControllerContext.patient(), "Is aspirin safe for her?", new User(3), true);

		assertEquals(SUBSTANCE,
				streamedChartOrderBridges("grounded").get(0).get("substance").asText());
	}

	@Test
	public void theWholePayloadStillMarshalsForAnXmlClient() throws Exception {
		// The one break this change introduced, and the only path no other test in this repo covers.
		// The blocking /search response is a ResponseEntity<Object> served by the converters
		// openmrs-core registers (webservices.rest leaves <mvc:annotation-driven/> commented out), so
		// for `Accept: application/xml` the one that serves a Map body is an XStreamMarshaller behind
		// a MarshallingHttpMessageConverter — read off openmrs-web's openmrs-servlet.xml rather than
		// inferred, and NOT pinned by this test, which drives the marshaller directly. What this case
		// pins is that the marshaller refuses the value; that it is the marshaller in the request path
		// is the platform's configuration and would go stale silently — though it WAS confirmed on a
		// live request during this change's verification, whose XML body came back as XStream's own
		// <map><entry><string>… . XStream refuses java.util.Collections' immutable wrappers:
		// measured on JDK 21.0.6 with xstream 1.4.21, both Collections$UnmodifiableRandomAccessList
		// and Collections$EmptyList raise ConversionException("No converter available") while an
		// ArrayList marshals. (An earlier draft quoted "module java.base does not opens java.util";
		// no such text appears.) chartOrderBridges() returns an unmodifiableList, or
		// Collections$EmptyList in the empty case, so publishing it AS HANDED turned every
		// chip-carrying XML response into a 500 — the empty case included.
		//
		// Driven through the REAL marshaller rather than an imitation of it, for the reason
		// QuerystoreOrderTextMarkerTest gives about querystore's serializer: keying a wire contract on
		// another component's behaviour is fragile in the worst way if nothing here exercises it.
		// Mutate the controller back to publishing the accessor's list and this reddens; nothing else
		// does.
		assertMarshals(searchPayload(), "a populated chip");

		bridges = Collections.<SafetyWarning.ChartOrderBridge> emptyList();
		assertMarshals(searchPayload(), "a chip with no attributions");
	}

	private static void assertMarshals(Map<String, Object> payload, String what) throws Exception {
		XStreamMarshaller marshaller = new XStreamMarshaller();
		marshaller.afterPropertiesSet();
		try {
			marshaller.marshal(payload, new StreamResult(new StringWriter()));
		}
		catch (Exception e) {
			throw new AssertionError("the /search payload must marshal to XML for " + what
					+ " — an XStreamMarshaller is the converter openmrs-core selects for "
					+ "Accept: application/xml, and it cannot marshal Collections' immutable wrappers "
					+ "(issue #347). Publish a copy, not the accessor's list. Cause: " + e, e);
		}
	}

	@Test
	public void noEmissionSiteCanPublishAChipWithoutSayingWhereItsSubstanceCameFrom() throws Exception {
		// Structural, and it is what makes the cases above hold for a site nobody has written yet. The
		// chips array is already written in exactly one place (issue #336, pinned next door); this
		// asserts the attribution is written from the chip's own bridges in exactly one place too, so
		// a site that built the per-chip map for itself would be caught rather than silently dropping
		// the correspondence for whichever surface it serves.
		//
		// It counts LITERALS in ONE class's source, so name what that leaves out rather than reading a
		// green run as coverage: a second writer in a sibling class is outside the source it reads
		// (the same scope CLAUDE.md records for the grounding-withholding guard), and inside it a key
		// assembled from a constant or a concatenation is not this needle. No list of evasions is
		// offered as closed. What it does catch is the shape that has actually happened here — a new
		// emission surface copying the chip-map builder — and that is what it is for.
		String source = ChartSearchAiStreamingTest.controllerSource();

		int keys = ChartSearchAiStreamingTest.occurrences(source, "\"chartOrderBridges\"");
		assertEquals(1, keys,
				"the chartOrderBridges key must be written in exactly one place, inside the one method "
						+ "that builds a chip's wire map (issue #347). Found " + keys + " writes of it.");
		int reads = ChartSearchAiStreamingTest.occurrences(source, "warning.chartOrderBridges()");
		assertEquals(1, reads,
				"and it must be read straight off the chip, once — a second reader is a site reshaping "
						+ "or re-deriving the attributions. Found " + reads + ". (The needle carries its "
						+ "receiver so that a javadoc {@link} to the accessor does not count as a read.)");
	}

	private class BridgedChipStubService implements ChartSearchService {

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
			tokenConsumer.accept("Acetylsalicylic acid (aspirin) interacts with active order Advil [2].");
			citationsConsumer.accept(answer().getReferences());
			// Production's own early-done shape: built before validation runs, so it carries no chips.
			ungroundedAnswerConsumer.accept(new ChartSearchService.ChartAnswer(
					"Acetylsalicylic acid (aspirin) interacts with active order Advil [2].",
					Collections.<ChartSearchService.RecordReference> emptyList()));
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
