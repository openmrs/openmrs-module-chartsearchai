/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 */
package org.openmrs.module.chartsearchai.web.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.AuditLogService;
import org.openmrs.module.chartsearchai.api.conversation.ConversationDAO;
import org.openmrs.module.chartsearchai.api.conversation.ConversationService;
import org.openmrs.module.chartsearchai.api.provider.AnswerEnvelope;
import org.openmrs.module.chartsearchai.api.provider.BundledClinicalAnswerProvider;
import org.openmrs.module.chartsearchai.api.provider.CancellationSignal;
import org.openmrs.module.chartsearchai.api.provider.ClinicalAnswerProvider;
import org.openmrs.module.chartsearchai.api.provider.ClinicalAnswerProviderRegistry;
import org.openmrs.module.chartsearchai.api.provider.ProviderCapability;
import org.openmrs.module.chartsearchai.api.provider.ProviderDescriptor;
import org.openmrs.module.chartsearchai.api.provider.ProviderMode;
import org.openmrs.module.chartsearchai.api.provider.TurnEvent;
import org.openmrs.module.chartsearchai.api.provider.TurnEventSink;
import org.openmrs.module.chartsearchai.api.provider.TurnEventType;
import org.openmrs.module.chartsearchai.api.provider.TurnRequest;
import org.openmrs.module.chartsearchai.api.provider.TurnResult;
import org.openmrs.module.chartsearchai.model.ClinicalConversation;
import org.openmrs.module.chartsearchai.model.ClinicalConversationTurn;
import org.openmrs.web.test.jupiter.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

/** Real controller-to-Hibernate/audit join. Inference alone is replaced by deterministic events. */
public class ProviderStreamPersistenceTest extends BaseModuleWebContextSensitiveTest {

	@Autowired
	private ConversationService conversations;

	@Autowired
	private ConversationDAO conversationDAO;

	@Autowired
	private AuditLogService auditLogs;

	@Test
	public void terminalAnswerNamesTheAuditActuallyPersistedForThatTurn() throws Exception {
		BundledClinicalAnswerProvider provider = new BundledClinicalAnswerProvider(null) {
			@Override
			public ProviderDescriptor descriptor() {
				return readyDescriptor();
			}

			@Override
			public CompletionStage<TurnResult> execute(TurnRequest request, TurnEventSink events,
					CancellationSignal cancellation) {
				AnswerEnvelope answer = AnswerEnvelope.fromPayload(Collections.singletonMap("answer", "Persisted answer"));
				events.accept(TurnEvent.of(TurnEventType.TURN_STARTED, 0, "bundled"));
				events.accept(TurnEvent.withAnswer(TurnEventType.ANSWER_DONE, 1, "bundled", answer));
				events.accept(TurnEvent.withAnswer(TurnEventType.TURN_DONE, 2, "bundled", answer));
				return CompletableFuture.completedFuture(TurnResult.done("bundled", request.getMode(), answer));
			}
		};
		ByteArrayOutputStream output = stream(provider, ProviderMode.QUERY_SCOPED);
		List<JsonNode> terminal = terminalPayloads(output);
		assertEquals(1, terminal.size());
		JsonNode payload = terminal.get(0);
		assertTrue(payload.path("auditLogId").isIntegralNumber(), "live feedback needs the persisted audit identifier");
		int auditId = payload.path("auditLogId").asInt();
		Context.flushSession();
		Context.clearSession();
		ClinicalConversationTurn turn = conversationDAO.getTurnByUuid(payload.path("messageId").asText());
		assertNotNull(turn);
		assertEquals("turn_done", turn.getTerminalState());
		assertEquals(auditId, turn.getAuditLog().getAuditLogId());
		assertEquals("Persisted answer", auditLogs.getAuditLog(auditId).getAnswer());
	}

	@Test
	public void actualBundledModeRejectionPersistsOneTerminalError() throws Exception {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_CHART_MODE, "queryScoped");
		// Readiness is not under test; execute is the real bundled rejection, before inference.
		BundledClinicalAnswerProvider provider = new BundledClinicalAnswerProvider(null) {
			@Override
			public ProviderDescriptor descriptor() {
				return readyDescriptor();
			}
		};
		ByteArrayOutputStream output = stream(provider, ProviderMode.FULL_CHART_STABLE);
		List<JsonNode> terminal = terminalPayloads(output);
		assertEquals(1, terminal.size(), "rejection must not be followed by a second persistence failure");
		assertEquals("unsupported_mode", terminal.get(0).path("problemCode").asText());
		Patient patient = Context.getPatientService().getPatient(2);
		ClinicalConversation conversation = conversations.getLatestActiveConversation(patient);
		List<ClinicalConversationTurn> turns = conversations.getTurns(conversation);
		assertEquals(1, turns.size());
		String uuid = turns.get(0).getUuid();
		Context.flushSession();
		Context.clearSession();
		ClinicalConversationTurn reloaded = conversationDAO.getTurnByUuid(uuid);
		assertEquals("turn_error", reloaded.getTerminalState());
		assertEquals("unsupported_mode", reloaded.getProblemCode());
		assertNotNull(reloaded.getAuditLog());
	}

	private ByteArrayOutputStream stream(ClinicalAnswerProvider provider, ProviderMode mode) {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		controller.setConversationService(conversations);
		controller.setProviderRegistry(new ClinicalAnswerProviderRegistry(Collections.singletonList(provider)));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		controller.streamProviderTurn(output, Context.getPatientService().getPatient(2), "Question",
				"bundled", mode, null, null);
		return output;
	}

	private static ProviderDescriptor readyDescriptor() {
		return new ProviderDescriptor("bundled", "Bundled", true, true, true,
				Collections.singletonList(ProviderMode.QUERY_SCOPED),
				Collections.singleton(ProviderCapability.ANSWER), null);
	}

	private static List<JsonNode> terminalPayloads(ByteArrayOutputStream output) throws Exception {
		List<JsonNode> result = new ArrayList<>();
		for (String frame : output.toString(StandardCharsets.UTF_8.name()).split("\n\n")) {
			if (frame.startsWith("event: turn_done\n") || frame.startsWith("event: turn_error\n")) {
				result.add(new ObjectMapper().readTree(frame.substring(frame.indexOf("data: ") + 6).trim()));
			}
		}
		return result;
	}
}
