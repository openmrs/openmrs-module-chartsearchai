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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.AuditLogService;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;

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
 * STABLE — it must not disturb the date ordering {@code extractCitedReferences} already
 * established within a group.
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

	/** The {@code references} array of the named SSE event, in emitted order. */
	private List<JsonNode> referencesOf(String eventType) throws Exception {
		String body = new String(out.toByteArray(), StandardCharsets.UTF_8);
		StringBuilder data = new StringBuilder();
		String current = null;
		boolean capturing = false;
		for (String line : body.split("\n")) {
			if (line.startsWith("event:")) {
				if (capturing) {
					break;
				}
				current = line.substring("event:".length()).trim();
				capturing = eventType.equals(current);
				data.setLength(0);
			} else if (capturing && line.startsWith("data:")) {
				data.append(line.substring("data:".length()));
			}
		}
		assertTrue(capturing || data.length() > 0, "no '" + eventType + "' event was emitted");
		JsonNode refs = MAPPER.readTree(data.toString()).get("references");
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
				"full-chart", false);

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
				"full-chart", false);

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
				"full-chart", false);

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
				"full-chart", false);

		List<JsonNode> refs = referencesOf("references");
		List<String> groups = new ArrayList<String>();
		for (JsonNode ref : refs) {
			groups.add(ref.get("group").asText());
		}
		assertEquals(Arrays.asList("chart", "chart", "reference"), groups,
				"the early references event must group identically to done — a client renders it first");
	}

	@Test
	public void groupingDoesNotDisturbTheExistingReferenceFields() throws Exception {
		controller.streamAnswer(out, patient(), "is it safe to give her aspirin?", new User(3),
				"full-chart", false);

		JsonNode drugRef = referencesOf("done").get(2);
		assertEquals(231, drugRef.get("index").asInt());
		assertEquals("drug_reference", drugRef.get("resourceType").asText());
		assertEquals("1191", drugRef.get("resourceUuid").asText());
		assertTrue(drugRef.get("grounded").isNull(),
				"a drug_reference citation stays demote-only: a pass renders null, never true");
	}

	/** Streams a token, fires citations, then returns the mixed-reference answer. */
	private static class MixedReferenceStubService implements ChartSearchService {

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
			citationsConsumer.accept(mixedAnswer().getReferences());
			ungroundedAnswerConsumer.accept(mixedAnswer());
			return mixedAnswer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}

	private static class StubAuditLogService implements AuditLogService {

		@Override
		public ChartSearchAuditLog saveAuditLog(ChartSearchAuditLog auditLog) {
			auditLog.setAuditLogId(42);
			return auditLog;
		}

		@Override
		public ChartSearchAuditLog getAuditLog(Integer auditLogId) {
			return null;
		}

		@Override
		public List<ChartSearchAuditLog> getAuditLogs(Patient patient, User user,
				java.util.Date fromDate, java.util.Date toDate, Integer startIndex, Integer limit) {
			return java.util.Collections.emptyList();
		}

		@Override
		public Long getAuditLogCount(Patient patient, User user, java.util.Date fromDate,
				java.util.Date toDate) {
			return 0L;
		}

		@Override
		public long getQueryCountByUserSince(User user, java.util.Date since) {
			return 0L;
		}

		@Override
		public int deleteAuditLogsBefore(java.util.Date before) {
			return 0;
		}
	}
}
