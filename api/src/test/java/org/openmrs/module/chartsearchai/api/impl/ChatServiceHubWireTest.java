/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openmrs.module.chartsearchai.api.ChatService;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.api.db.ChatDAO;
import org.openmrs.module.chartsearchai.model.ChatMessage;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.module.chartsearchai.model.ChatSession;
import org.springframework.test.util.ReflectionTestUtils;

public class ChatServiceHubWireTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	public void persistHubStagedAnswer_preservesSafetyWarningsInAssistantWire() throws Exception {
		ChatDAO chatDAO = mock(ChatDAO.class);
		ChartSearchAiDAO auditDAO = mock(ChartSearchAiDAO.class);
		ChatServiceImpl service = new ChatServiceImpl();
		ReflectionTestUtils.setField(service, "chatDAO", chatDAO);
		ReflectionTestUtils.setField(service, "auditDAO", auditDAO);
		when(chatDAO.getLastOrdinal(any())).thenReturn(-1);
		when(chatDAO.saveMessage(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(chatDAO.saveSession(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(auditDAO.saveAuditLog(any())).thenAnswer(invocation -> {
			ChartSearchAuditLog audit = invocation.getArgument(0);
			audit.setAuditLogId(42);
			return audit;
		});

		Map<String, Object> warning = new LinkedHashMap<>();
		warning.put("type", "overdose");
		warning.put("drug", "Ibuprofen");
		warning.put("detail", "Dose exceeds the weight-based limit.");
		Map<String, Object> wire = new LinkedHashMap<>();
		wire.put("answer", "Use caution.");
		wire.put("references", Collections.emptyList());
		wire.put("blocks", Collections.emptyList());
		wire.put("safetyWarnings", Collections.singletonList(warning));

		ChatService.ChatTurnResult result = service.persistHubStagedAnswer(
				new ChatSession(), "Can ibuprofen be used?", wire, 12L);

		ArgumentCaptor<ChatMessage> messages = ArgumentCaptor.forClass(ChatMessage.class);
		org.mockito.Mockito.verify(chatDAO, org.mockito.Mockito.times(2)).saveMessage(messages.capture());
		JsonNode stored = MAPPER.readTree(messages.getAllValues().get(1).getContent());
		assertEquals("Ibuprofen", stored.get("safetyWarnings").get(0).get("drug").asText());
		assertEquals(42, result.getAuditLogId());
	}

	@Test
	public void updateHubStagedMessage_keepsAuditAnswerAndReferenceCountFinal() {
		ChatDAO chatDAO = mock(ChatDAO.class);
		ChartSearchAiDAO auditDAO = mock(ChartSearchAiDAO.class);
		ChatServiceImpl service = new ChatServiceImpl();
		ReflectionTestUtils.setField(service, "chatDAO", chatDAO);
		ReflectionTestUtils.setField(service, "auditDAO", auditDAO);

		ChatSession session = new ChatSession();
		session.setSessionId(7);
		session.setUuid("session-uuid");
		ChartSearchAuditLog audit = new ChartSearchAuditLog();
		audit.setAnswer("Unchecked answer.");
		audit.setReferenceCount(0);
		ChatMessage assistant = new ChatMessage();
		assistant.setUuid("assistant-uuid");
		assistant.setSession(session);
		assistant.setRole(ChatMessage.ROLE_ASSISTANT);
		assistant.setContent("{\"answer\":\"Unchecked answer.\",\"references\":[],\"blocks\":[]}");
		assistant.setAuditLog(audit);
		when(chatDAO.getMessageByUuid("assistant-uuid")).thenReturn(assistant);
		when(chatDAO.saveMessage(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(chatDAO.saveSession(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Map<String, Object> reference = new LinkedHashMap<>();
		reference.put("index", 1);
		reference.put("resourceUuid", "obs-1");
		Map<String, Object> finalWire = new LinkedHashMap<>();
		finalWire.put("answer", "Checked final answer [1].");
		finalWire.put("references", Collections.singletonList(reference));

		service.updateHubStagedMessage(session, "assistant-uuid", finalWire);

		assertEquals("Checked final answer [1].", audit.getAnswer());
		assertEquals(1, audit.getReferenceCount());
		verify(auditDAO).saveAuditLog(audit);
	}

	@Test
	public void priorTurnsForRelay_projectsPersistedAssistantWireWithoutMutatingRows() {
		ChatDAO chatDAO = mock(ChatDAO.class);
		ChatServiceImpl service = new ChatServiceImpl();
		ReflectionTestUtils.setField(service, "chatDAO", chatDAO);

		ChatSession session = new ChatSession();
		ChatMessage user = new ChatMessage();
		user.setRole(ChatMessage.ROLE_USER);
		user.setOrdinal(0);
		user.setContent("Name the medication you selected.");
		ChatMessage assistant = new ChatMessage();
		assistant.setRole(ChatMessage.ROLE_ASSISTANT);
		assistant.setOrdinal(1);
		assistant.setContent("{\"answer\":\"I selected naproxen.\",\"references\":[{\"index\":3}],"
				+ "\"inDepth\":{\"status\":\"complete\",\"answer\":\"Extra detail\"}}");
		when(chatDAO.getMessages(session)).thenReturn(Arrays.asList(user, assistant));

		List<ChatMessage> projected = service.priorTurnsForRelay(session);

		assertEquals(2, projected.size());
		assertEquals("Name the medication you selected.", projected.get(0).getContent());
		assertEquals("I selected naproxen.", projected.get(1).getContent());
		assertEquals(1, projected.get(1).getOrdinal());
		assertEquals("{\"answer\":\"I selected naproxen.\",\"references\":[{\"index\":3}],"
				+ "\"inDepth\":{\"status\":\"complete\",\"answer\":\"Extra detail\"}}",
				assistant.getContent(), "projection must not mutate the persisted assistant envelope");
	}
}
