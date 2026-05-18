/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.db.hibernate;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.hibernate.SessionFactory;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.db.ChatDAO;
import org.openmrs.module.chartsearchai.model.ChatMessage;
import org.openmrs.module.chartsearchai.model.ChatSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository("chartSearchAi.chatDAO")
public class HibernateChatDAO implements ChatDAO {

	@Autowired
	private SessionFactory sessionFactory;

	@Override
	public ChatSession saveSession(ChatSession session) {
		sessionFactory.getCurrentSession().saveOrUpdate(session);
		return session;
	}

	@Override
	public ChatSession getSession(Integer sessionId) {
		return (ChatSession) sessionFactory.getCurrentSession().get(ChatSession.class, sessionId);
	}

	@Override
	@SuppressWarnings("unchecked")
	public ChatSession getSessionByUuid(String uuid) {
		List<ChatSession> results = sessionFactory.getCurrentSession()
				.createQuery("from ChatSession where uuid = :uuid")
				.setParameter("uuid", uuid)
				.list();
		return results.isEmpty() ? null : results.get(0);
	}

	@Override
	@SuppressWarnings("unchecked")
	public ChatSession getLatestSession(Patient patient, User user) {
		List<ChatSession> results = sessionFactory.getCurrentSession()
				.createQuery("from ChatSession where patient = :patient and user = :user "
						+ "and status = '" + ChatSession.STATUS_ACTIVE + "' "
						+ "order by lastActivityAt desc")
				.setParameter("patient", patient)
				.setParameter("user", user)
				.setMaxResults(1)
				.list();
		return results.isEmpty() ? null : results.get(0);
	}

	@Override
	public ChatMessage saveMessage(ChatMessage message) {
		sessionFactory.getCurrentSession().saveOrUpdate(message);
		return message;
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<ChatMessage> getMessages(ChatSession session) {
		if (session == null) {
			return Collections.emptyList();
		}
		return sessionFactory.getCurrentSession()
				.createQuery("from ChatMessage where session = :session and summary = false order by ordinal asc")
				.setParameter("session", session)
				.list();
	}

	@Override
	public int getLastOrdinal(ChatSession session) {
		if (session == null) {
			return -1;
		}
		Integer max = (Integer) sessionFactory.getCurrentSession()
				.createQuery("select max(ordinal) from ChatMessage where session = :session")
				.setParameter("session", session)
				.uniqueResult();
		return max == null ? -1 : max;
	}

	@Override
	public int purgeBefore(Date before) {
		int messages = sessionFactory.getCurrentSession()
				.createQuery("delete from ChatMessage where createdAt < :before")
				.setParameter("before", before)
				.executeUpdate();
		// Orphaned headers: any session whose lastActivityAt predates the
		// retention horizon AND has no surviving messages. With the message
		// rows already gone above, "no surviving messages" is implicit.
		int sessions = sessionFactory.getCurrentSession()
				.createQuery("delete from ChatSession where lastActivityAt < :before")
				.setParameter("before", before)
				.executeUpdate();
		return messages + sessions;
	}
}
