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
 * Wire-contract test for citation provenance (issue #117).
 *
 * <p>An injected drug-reference record used to carry its dataset attribution and its
 * withheld-partner count inside the record text — the string the model is instructed to cite — and
 * the model duly recited both into clinician-facing answers ("…and 824 more interactions on file.
 * Source: DDInter 2.0 (via openmrs-ddi-knowledge-base)."). They are now fields on the reference
 * instead, so the model has nothing to quote and a client can render the same facts beside the
 * citation chip.
 *
 * <p>That only holds if they actually reach the client, which is what this pins: without the two
 * keys on the wire the fields are unreachable and the information is lost rather than relocated.
 * Asserted against the real controller's real serialization, not a restatement of the wire shape.
 */
public class ChartSearchAiReferenceProvenanceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String DDINTER = "DDInter 2.0 (via openmrs-ddi-knowledge-base)";

	private ChartSearchAiRestController controller;

	private ByteArrayOutputStream out;

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new ProvenanceStubService());
		out = new ByteArrayOutputStream();
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(7);
		p.setUuid("uuid-7");
		return p;
	}

	/** The {@code references} array of the given SSE event, decoded by the shared decoder. */
	private List<JsonNode> referencesOf(String eventType) throws Exception {
		SseEvent event = SseEvents.ofType(out, eventType);
		assertNotNull(event, "no '" + eventType + "' event was emitted");
		JsonNode refs = MAPPER.readTree(event.data).get("references");
		assertNotNull(refs, "'" + eventType + "' event carried no references array");
		return Arrays.asList(refs.get(0), refs.get(1));
	}

	@Test
	public void doneEvent_carriesProvenanceAndWithheldCountForAnInjectedReference() throws Exception {
		controller.streamAnswer(out, patient(), "can I prescribe erythromycin for this patient?",
				new User(3), "full-chart", false);

		// Chart evidence sorts first, so the reference record is last (see serializeReferences).
		JsonNode drugRef = referencesOf("done").get(1);
		assertEquals("drug_reference", drugRef.get("resourceType").asText(),
				"precondition: the second reference must be the injected drug-reference record");
		assertEquals(DDINTER, drugRef.get("source").asText(),
				"the citation must carry the dataset it came from, so a client can show provenance "
						+ "without the record text having to name it");
		assertEquals(824, drugRef.get("withheldInteractions").asInt(),
				"and how many interaction partners the render budget left out, so truncation stays "
						+ "honest without a text tail the model recites");
	}

	@Test
	public void doneEvent_reportsNoProvenanceForAChartRecord() throws Exception {
		// The other half of the contract: a chart record's provenance is the patient's own record, so
		// both keys must be present and empty rather than absent — a client reads them unconditionally.
		controller.streamAnswer(out, patient(), "can I prescribe erythromycin for this patient?",
				new User(3), "full-chart", false);

		JsonNode chartRef = referencesOf("done").get(0);
		assertEquals("allergy", chartRef.get("resourceType").asText(),
				"precondition: the first reference must be the chart record");
		assertTrue(chartRef.has("source") && chartRef.get("source").isNull(),
				"the source key must be present and null for a chart record");
		assertEquals(0, chartRef.get("withheldInteractions").asInt(),
				"and nothing is withheld from a chart record");
	}

	/** An answer citing one chart record and one injected drug-reference record carrying provenance. */
	private static ChartSearchService.ChartAnswer answer() {
		return new ChartSearchService.ChartAnswer("Erythromycin interacts with simvastatin [75].",
				Arrays.asList(
						new ChartSearchService.RecordReference(75, "drug_reference", "erythromycin", null,
								null, DDINTER, 824),
						new ChartSearchService.RecordReference(12, "allergy", "u12", null, Boolean.TRUE)));
	}

	private static class ProvenanceStubService implements ChartSearchService {

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
			tokenConsumer.accept("Erythromycin interacts with simvastatin [75].");
			citationsConsumer.accept(answer().getReferences());
			ungroundedAnswerConsumer.accept(answer());
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
