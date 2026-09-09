/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.conversation;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.openmrs.module.chartsearchai.api.conversation.impl.ConversationServiceImpl;
import org.openmrs.module.chartsearchai.model.ClinicalConversation;
import org.openmrs.module.chartsearchai.model.ClinicalConversationTurn;

/** Pins the serialization boundary used to allocate per-conversation turn numbers. */
public class ConversationTurnAllocationTest {

	@Test
	public void locksTheConversationBeforeReadingAndSavingTheNextOrdinal() throws Exception {
		ConversationDAO dao = mock(ConversationDAO.class);
		ConversationServiceImpl service = new ConversationServiceImpl();
		Field daoField = ConversationServiceImpl.class.getDeclaredField("conversationDAO");
		daoField.setAccessible(true);
		daoField.set(service, dao);

		ClinicalConversation requested = new ClinicalConversation();
		requested.setConversationId(17);
		requested.setStatus(ClinicalConversation.STATUS_ACTIVE);
		ClinicalConversation locked = new ClinicalConversation();
		locked.setConversationId(17);
		locked.setStatus(ClinicalConversation.STATUS_ACTIVE);
		when(dao.getConversationForUpdate(17)).thenReturn(locked);
		when(dao.getLastOrdinal(locked)).thenReturn(4);
		when(dao.saveTurn(org.mockito.ArgumentMatchers.any(ClinicalConversationTurn.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		ClinicalConversationTurn turn = service.startTurn(requested, "request-5", "Question five");

		assertSame(locked, turn.getConversation());
		InOrder order = inOrder(dao);
		order.verify(dao).getConversationForUpdate(17);
		order.verify(dao).getLastOrdinal(locked);
		order.verify(dao).saveConversation(locked);
		order.verify(dao).saveTurn(turn);
	}
}
