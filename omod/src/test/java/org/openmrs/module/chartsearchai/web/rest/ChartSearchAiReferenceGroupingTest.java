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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wire-contract tests for reference grouping. Every serialized reference carries a
 * {@code group} discriminator derived from its {@code resourceType}, so a client can
 * separate chart evidence from module-supplied reference prose without hardcoding
 * resource-type names, and the flat {@code references} array is ordered so the groups
 * are contiguous (chart evidence first).
 *
 * <p>Driven through the real controller and its real SSE serialization with a stubbed
 * {@link ChartSearchService}, matching this test package's conventions — the assertions
 * are made against the actual emitted JSON, not a reimplementation of the wire shape.
 *
 * <p>The fixture is deliberately adversarial: the injected {@code drug_reference} is
 * FIRST in the service's reference list, so a passing test proves the serializer really
 * reorders rather than accidentally agreeing with input order. The two chart records are
 * given in non-index order (230 before 8) so the test also pins that regrouping is
 * STABLE — whatever order {@code extractCitedReferences} established within a group has to
 * survive.
 *
 * <p>The allergy and the drug reference are null-dated because that is genuinely their shape:
 * an allergy's querystore date is administrative and deliberately unrendered, and an injected
 * drug-reference record never carries one. That pairing is the only situation in which the
 * group sort changes anything at all, since upstream already sorts undated records last. The
 * condition is null-dated purely to give the chart group a second member, so the stability
 * assertion has something to be tight about — a real condition would carry a date. What is
 * pinned here is therefore insertion-order stability, not date ordering.
 */
public class ChartSearchAiReferenceGroupingTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new MixedReferenceStubService());
		out = new ByteArrayOutputStream();
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(7);
		p.setUuid("uuid-7");
		return p;
	}

	/**
	 * An answer whose citations mix chart records with an injected drug-reference record,
	 * mirroring the live shape seen for a "is it safe to give her aspirin?" query: an
	 * allergy record plus the DDInter entry the module injected.
	 */
	private static ChartSearchService.ChartAnswer mixedAnswer() {
		return new ChartSearchService.ChartAnswer("Severe allergy to Aspirin [230].",
				Arrays.asList(
						new ChartSearchService.RecordReference(231, "drug_reference", "1191", null, null),
						new ChartSearchService.RecordReference(230, "allergy", "u230", null, Boolean.TRUE),
						new ChartSearchService.RecordReference(8, "condition", "u8", null, Boolean.TRUE)));
	}

	/**
	 * The {@code references} array of the named SSE event, in emitted order. Decoding is delegated
	 * to {@link SseEvents} so this class and the sibling streaming tests cannot disagree about the
	 * wire format — they previously each had their own decoder, differing over whether the space
	 * after {@code data:} was payload.
	 */
	private List<JsonNode> referencesOf(String eventType) throws Exception {
		SseEvent event = SseEvents.ofType(out, eventType);
		assertNotNull(event, "no '" + eventType + "' event was emitted");
		JsonNode refs = MAPPER.readTree(event.data).get("references");
		assertNotNull(refs, "'" + eventType + "' event carried no references array");
		List<JsonNode> list = new ArrayList<JsonNode>();
		for (JsonNode r : refs) {
			list.add(r);
		}
		return list;
	}

	@Test
	public void doneEvent_tagsEveryReferenceWithItsGroup() throws Exception {
		controller.streamAnswer(out, patient(), "is it safe to give her aspirin?", new User(3),
				false);

		List<JsonNode> refs = referencesOf("done");
		assertEquals(3, refs.size(), "all three citations must survive serialization");
		for (JsonNode ref : refs) {
			assertNotNull(ref.get("group"),
					"reference [" + ref.get("index") + "] carries no group discriminator");
			String expected = "drug_reference".equals(ref.get("resourceType").asText())
					? "reference"
					: "chart";
			assertEquals(expected, ref.get("group").asText(),
					"wrong group for resourceType " + ref.get("resourceType").asText());
		}
	}

	@Test
	public void doneEvent_ordersChartEvidenceBeforeReferenceMaterial() throws Exception {
		controller.streamAnswer(out, patient(), "is it safe to give her aspirin?", new User(3),
				false);

		List<JsonNode> refs = referencesOf("done");
		List<String> groups = new ArrayList<String>();
		for (JsonNode ref : refs) {
			groups.add(ref.get("group").asText());
		}
		assertEquals(Arrays.asList("chart", "chart", "reference"), groups,
				"chart evidence must precede module-supplied reference material");
	}

	@Test
	public void doneEvent_regroupingPreservesWithinGroupOrder() throws Exception {
		controller.streamAnswer(out, patient(), "is it safe to give her aspirin?", new User(3),
				false);

		List<JsonNode> refs = referencesOf("done");
		List<Integer> indexes = new ArrayList<Integer>();
		for (JsonNode ref : refs) {
			indexes.add(ref.get("index").asInt());
		}
		assertEquals(Arrays.asList(230, 8, 231), indexes,
				"regrouping must be stable: the chart records keep their incoming relative order");
	}

	@Test
	public void referencesEvent_carriesTheSameGroupingAsDone() throws Exception {
		controller.streamAnswer(out, patient(), "is it safe to give her aspirin?", new User(3),
				false);

		// Compared against the done event itself rather than a repeated literal: a client renders the
		// early event and then replaces it wholesale from done, so what matters is that the two agree.
		assertEquals(groupsAndIndexesOf("done"), groupsAndIndexesOf("references"),
				"the early references event must group and order identically to done — a client "
						+ "renders it first and would otherwise see citations jump on replacement");
	}

	/** The {@code group}/{@code index} pairs of an event's references, in emitted order. */
	private List<String> groupsAndIndexesOf(String eventType) throws Exception {
		List<String> pairs = new ArrayList<String>();
		for (JsonNode ref : referencesOf(eventType)) {
			pairs.add(ref.get("group").asText() + ":" + ref.get("index").asInt());
		}
		return pairs;
	}

	@Test
	public void groundedEvent_carriesGroupsToo_whenAsyncGroundingIsOn() throws Exception {
		// Async mode emits done early (no verdicts) and the verdicts afterwards on a trailing
		// grounded event. That trailing event is its own serializeReferences call site, so it needs
		// its own assertion — a client that only consumes grounded must still get the grouping.
		controller.streamAnswer(out, patient(), "is it safe to give her aspirin?", new User(3),
				true);

		assertEquals(Arrays.asList("chart:230", "chart:8", "reference:231"),
				groupsAndIndexesOf("grounded"),
				"the trailing grounded event must carry the same groups and order as every other site");
	}

	@Test
	public void groupingDoesNotDisturbTheExistingReferenceFields() throws Exception {
		controller.streamAnswer(out, patient(), "is it safe to give her aspirin?", new User(3),
				false);

		JsonNode drugRef = referencesOf("done").get(2);
		assertEquals(231, drugRef.get("index").asInt());
		assertEquals("drug_reference", drugRef.get("resourceType").asText());
		assertEquals("1191", drugRef.get("resourceUuid").asText());
		// The key must still be emitted even when the record is undated — a client reads it
		// unconditionally. Presence rather than value, because every fixture record is null-dated
		// (see the class javadoc for why that is the realistic shape here).
		assertTrue(drugRef.has("date"), "the date key must survive grouping, null value included");
		// The fixture supplies a null verdict, so this pins that grouping did not disturb the
		// `grounded` field it serializes alongside. It is no longer a passthrough for THIS record:
		// since issue #201 the serializer withholds the verdict of every reference-group citation,
		// so a drug_reference reads null whatever the fixture attached. That withholding, and the
		// chart-group passthrough that must survive it, are pinned in
		// ChartSearchAiReferenceGroundingWithholdingTest; the demote-only GRADING rule upstream of
		// it lives in CitationGroundingVerifier and is tested there.
		assertTrue(drugRef.get("grounded").isNull(),
				"grouping must not disturb the tri-state grounded verdict it serializes alongside");
	}

	/**
	 * The render order must cover every declared group. {@code groupRank} ranks by position in
	 * {@link ChartSearchAiRestController#REFERENCE_GROUP_ORDER}, so a group constant added without
	 * being placed in that list would rank as unknown and its entries would clump at the end
	 * regardless of intent — the contiguity contract would hold but the declared order would not.
	 * Adding a {@code REFERENCE_GROUP_*} constant fails here until it is given a position.
	 */
	@Test
	public void referenceGroupOrder_shouldRankEveryDeclaredGroupConstant() throws Exception {
		List<String> declared = new ArrayList<String>();
		for (Field field : ChartSearchAiConstants.class.getDeclaredFields()) {
			// isPublic matters: this test is in a different package from the constants, so reading a
			// non-public field would throw IllegalAccessException and bury the actionable assertion
			// below under a reflection stack trace.
			if (field.getName().startsWith("REFERENCE_GROUP_") && field.getType() == String.class
					&& Modifier.isStatic(field.getModifiers())
					&& Modifier.isPublic(field.getModifiers())) {
				declared.add((String) field.get(null));
			}
		}
		assertTrue(declared.size() >= 2, "expected the REFERENCE_GROUP_* constants to be discovered");
		for (String group : declared) {
			assertTrue(ChartSearchAiRestController.REFERENCE_GROUP_ORDER.contains(group),
					"group \"" + group + "\" has no position in REFERENCE_GROUP_ORDER, so its "
							+ "references would sort as an unknown group rather than where intended");
		}
	}

	/**
	 * Regrouping must not reorder the CALLER's list. The early {@code references} event is handed
	 * the very list object {@code LlmInferenceService} still owns and reuses for its grounding
	 * pass, so sorting in place mutates live service state across a module boundary. Today that
	 * mutation is benign in effect — a stable group-only partition leaves chart citations in
	 * relative order, and reference-group citations never consume Tier-2's budget — which is
	 * exactly why it needs a test rather than trust: the damage would only appear once the
	 * comparator gained a second key, long after the copy had been dropped as redundant.
	 */
	@Test
	public void serializingReferences_mustNotReorderTheCallersList() throws Exception {
		MixedReferenceStubService service = new MixedReferenceStubService();
		controller.setChartSearchService(service);

		controller.streamAnswer(out, patient(), "is it safe to give her aspirin?", new User(3),
				false);

		// Assert the event actually fired first: without this the guard below would pass vacuously
		// if the references event ever stopped being emitted, since an untouched list is also an
		// unread one.
		assertEquals(3, referencesOf("references").size(),
				"the references event must have been emitted for this guard to mean anything");

		List<Integer> indexesAfter = new ArrayList<Integer>();
		for (ChartSearchService.RecordReference ref : service.listHandedToCitationsConsumer) {
			indexesAfter.add(ref.getIndex());
		}
		assertEquals(Arrays.asList(231, 230, 8), indexesAfter,
				"the list handed to the citations consumer was reordered — grounding downstream "
						+ "would see a different citation order than the service built");
	}

	/** Streams a token, fires citations, then returns the mixed-reference answer. */
	private static class MixedReferenceStubService implements ChartSearchService {

		/**
		 * The exact list instance passed to the citations consumer, mirroring how
		 * {@code LlmInferenceService} hands its {@code cited} list to the consumer and then reuses
		 * that same instance for grounding. Held so a test can assert it came back untouched.
		 */
		final List<RecordReference> listHandedToCitationsConsumer =
				new ArrayList<RecordReference>(mixedAnswer().getReferences());

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return mixedAnswer();
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
			tokenConsumer.accept("Severe allergy to Aspirin [230].");
			citationsConsumer.accept(listHandedToCitationsConsumer);
			ungroundedAnswerConsumer.accept(mixedAnswer());
			return mixedAnswer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}

}
