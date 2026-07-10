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
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.api.db.ChatDAO;
import org.openmrs.module.chartsearchai.model.ChatMessage;
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

		Map<String, Object> warning = new LinkedHashMap<>();
		warning.put("type", "overdose");
		warning.put("drug", "Ibuprofen");
		warning.put("detail", "Dose exceeds the weight-based limit.");
		Map<String, Object> wire = new LinkedHashMap<>();
		wire.put("answer", "Use caution.");
		wire.put("references", Collections.emptyList());
		wire.put("blocks", Collections.emptyList());
		wire.put("safetyWarnings", Collections.singletonList(warning));

		service.persistHubStagedAnswer(new ChatSession(), "Can ibuprofen be used?", wire, 12L);

		ArgumentCaptor<ChatMessage> messages = ArgumentCaptor.forClass(ChatMessage.class);
		org.mockito.Mockito.verify(chatDAO, org.mockito.Mockito.times(2)).saveMessage(messages.capture());
		JsonNode stored = MAPPER.readTree(messages.getAllValues().get(1).getContent());
		assertEquals("Ibuprofen", stored.get("safetyWarnings").get(0).get("drug").asText());
	}
}
