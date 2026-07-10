/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.ChatService;
import org.openmrs.module.chartsearchai.api.db.ChatDAO;
import org.openmrs.module.chartsearchai.model.ChatMessage;
import org.openmrs.module.chartsearchai.model.ChatSession;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Lifecycle contract for chat sessions, exercised through the real
 * {@link ChatService} + {@link ChatDAO} against a real patient (no mocks —
 * module rule).
 */
public class ChatServiceRefreshTest extends BaseModuleContextSensitiveTest {

	@Autowired
	private ChatService chatService;

	@Autowired
	private ChatDAO chatDAO;

	private Patient patient;

	@BeforeEach
	public void setUp() {
		patient = Context.getPatientService().getPatient(2);
		assertNotNull(patient, "standard test patient 2 must exist");
	}

	private void addTurn(ChatSession session, int ordinal, String role, String content) {
		ChatMessage m = new ChatMessage();
		m.setSession(session);
		m.setOrdinal(ordinal);
		m.setRole(role);
		m.setContent(content);
		m.setCreatedAt(new Date());
		chatDAO.saveMessage(m);
	}

	@Test
	public void closeAndStartNew_clearsTranscriptAndOpensNewSession() {
		ChatSession session = chatService.openOrLoadActiveSession(patient);
		String originalUuid = session.getUuid();
		addTurn(session, 0, ChatMessage.ROLE_USER, "q");
		addTurn(session, 1, ChatMessage.ROLE_ASSISTANT, "a");
		Context.flushSession();
		assertEquals(2, chatService.getMessages(session).size(), "precondition: 2 turns persisted");

		ChatSession fresh = chatService.closeAndStartNew(patient);

		assertNotEquals(originalUuid, fresh.getUuid(), "new chat opens a different session");
		assertEquals(0, chatService.getMessages(fresh).size(),
				"new chat starts with an empty transcript");
	}
}
