/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.db.ChatDAO;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.module.chartsearchai.model.ChatMessage;
import org.openmrs.module.chartsearchai.model.ChatSession;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises the real {@link AuditLogPurgeTask#execute()} path against a chat
 * message that links to an audit-log row via {@code audit_log_id}, both aged
 * past their retention horizons.
 *
 * <p>The purge deletes the audit row (regulatory grain, audit-retention) before
 * the chat row (clinical-utility grain, chat-retention). With a plain RESTRICT
 * foreign key on {@code chartsearchai_chat_message.audit_log_id}, deleting the
 * still-referenced audit row throws a constraint violation and the purge fails.
 * The contract this test pins: the audit log purge must complete cleanly and
 * actually remove both rows even when an assistant message points at the audit
 * entry being purged.
 */
public class AuditLogPurgeTaskExecuteTest extends BaseModuleContextSensitiveTest {

	@Autowired
	private ChatService chatService;

	@Autowired
	private ChatDAO chatDAO;

	@Autowired
	private AuditLogService auditLogService;

	private Patient patient;

	private static final long DAYS = 24L * 60L * 60L * 1000L;

	@BeforeEach
	public void setUp() {
		patient = Context.getPatientService().getPatient(2);
		assertNotNull(patient, "standard test patient 2 must exist");
	}

	@Test
	public void execute_purgesAuditRowReferencedByChatMessageWithoutFkViolation() {
		// A session + an assistant turn that links to a regulatory audit row.
		ChatSession session = chatService.openOrLoadActiveSession(patient);

		Date aged = new Date(System.currentTimeMillis() - 200L * DAYS);

		ChartSearchAuditLog audit = new ChartSearchAuditLog();
		audit.setUser(Context.getAuthenticatedUser());
		audit.setPatient(patient);
		audit.setQuestion("What medications is this patient on?");
		audit.setAnswer("Lisinopril 10mg [1]");
		audit.setSearchMode("chat");
		audit.setDateCreated(aged);
		audit = auditLogService.saveAuditLog(audit);
		assertNotNull(audit.getAuditLogId(), "audit row persisted");

		// An aged assistant turn that points at the audit row. It will be purged
		// by chat retention regardless, so on its own it can't distinguish a
		// SET-NULL fix from a cascade-delete fix.
		ChatMessage agedMsg = new ChatMessage();
		agedMsg.setSession(session);
		agedMsg.setOrdinal(0);
		agedMsg.setRole(ChatMessage.ROLE_ASSISTANT);
		agedMsg.setContent("Lisinopril 10mg [1]");
		agedMsg.setCreatedAt(aged);
		agedMsg.setAuditLog(audit);
		chatDAO.saveMessage(agedMsg);

		// A still-young assistant turn that ALSO cites the same aged audit row.
		// This is the discriminating row: it is younger than chat retention, so
		// it must SURVIVE the purge — and its citation link must be nulled, not
		// the message deleted. That is the SET NULL contract.
		ChatMessage youngMsg = new ChatMessage();
		youngMsg.setSession(session);
		youngMsg.setOrdinal(1);
		youngMsg.setRole(ChatMessage.ROLE_ASSISTANT);
		youngMsg.setContent("Still citing the old audit row [1]");
		youngMsg.setCreatedAt(new Date());
		youngMsg.setAuditLog(audit);
		chatDAO.saveMessage(youngMsg);

		// Force inserts to the DB so the referencing rows physically exist before
		// the bulk audit-delete runs (auto-flush would not flush the chat_message
		// inserts ahead of a delete that targets a different table).
		Context.flushSession();

		long auditBefore = auditLogService.getAuditLogCount(patient, null, null, null);
		assertEquals(1L, auditBefore, "precondition: one aged audit row to purge");
		assertEquals(2, chatService.getMessages(session).size(),
				"precondition: two chat messages referencing it");

		// Real production path. With a RESTRICT FK this throws a constraint
		// violation when it deletes the still-referenced audit row.
		new AuditLogPurgeTask().execute();

		Context.flushSession();
		Context.clearSession();

		assertEquals(0L, auditLogService.getAuditLogCount(patient, null, null, null),
				"audit row aged past retention must be purged");

		ChatSession reloaded = chatDAO.getSession(session.getSessionId());
		assertNotNull(reloaded, "the recently-active session itself survives the purge");

		List<ChatMessage> survivors = chatService.getMessages(reloaded);
		assertEquals(1, survivors.size(),
				"only the aged message is purged; the young message survives");
		ChatMessage survivor = survivors.get(0);
		assertEquals(youngMsg.getUuid(), survivor.getUuid(),
				"the surviving message is the young one, not the aged one");
		assertNull(survivor.getAuditLog(),
				"the survivor's audit citation must be nulled (SET NULL), not the message deleted");
	}
}
