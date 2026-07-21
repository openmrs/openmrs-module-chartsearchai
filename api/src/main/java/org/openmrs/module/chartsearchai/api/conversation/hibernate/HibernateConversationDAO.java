/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.conversation.hibernate;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.hibernate.SessionFactory;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.conversation.ConversationDAO;
import org.openmrs.module.chartsearchai.model.ClinicalConversation;
import org.openmrs.module.chartsearchai.model.ClinicalConversationTurn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** Hibernate persistence for provider-neutral conversations and turns. */
@Repository("chartSearchAi.conversationDAO")
public class HibernateConversationDAO implements ConversationDAO {

	@Autowired
	private SessionFactory sessionFactory;

	@Override
	public ClinicalConversation saveConversation(ClinicalConversation conversation) {
		sessionFactory.getCurrentSession().saveOrUpdate(conversation);
		return conversation;
	}

	@Override
	public ClinicalConversation getConversation(Integer conversationId) {
		return (ClinicalConversation) sessionFactory.getCurrentSession()
				.get(ClinicalConversation.class, conversationId);
	}

	@Override
	@SuppressWarnings("unchecked")
	public ClinicalConversation getConversationByUuid(String uuid) {
		List<ClinicalConversation> results = sessionFactory.getCurrentSession()
				.createQuery("from ClinicalConversation where uuid = :uuid")
				.setParameter("uuid", uuid)
				.list();
		return results.isEmpty() ? null : results.get(0);
	}

	@Override
	@SuppressWarnings("unchecked")
	public ClinicalConversation getLatestActiveConversation(Patient patient, User user) {
		List<ClinicalConversation> results = sessionFactory.getCurrentSession()
				.createQuery("from ClinicalConversation where patient = :patient and user = :user "
						+ "and status = :status order by lastActivityAt desc")
				.setParameter("patient", patient)
				.setParameter("user", user)
				.setParameter("status", ClinicalConversation.STATUS_ACTIVE)
				.setMaxResults(1)
				.list();
		return results.isEmpty() ? null : results.get(0);
	}

	@Override
	public ClinicalConversationTurn saveTurn(ClinicalConversationTurn turn) {
		sessionFactory.getCurrentSession().saveOrUpdate(turn);
		return turn;
	}

	@Override
	@SuppressWarnings("unchecked")
	public ClinicalConversationTurn getTurnByUuid(String uuid) {
		List<ClinicalConversationTurn> results = sessionFactory.getCurrentSession()
				.createQuery("from ClinicalConversationTurn where uuid = :uuid")
				.setParameter("uuid", uuid)
				.list();
		return results.isEmpty() ? null : results.get(0);
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<ClinicalConversationTurn> getTurns(ClinicalConversation conversation) {
		if (conversation == null) {
			return Collections.emptyList();
		}
		return sessionFactory.getCurrentSession()
				.createQuery("from ClinicalConversationTurn where conversation = :conversation "
						+ "order by ordinal asc")
				.setParameter("conversation", conversation)
				.list();
	}

	@Override
	public int getLastOrdinal(ClinicalConversation conversation) {
		if (conversation == null) {
			return -1;
		}
		Integer max = (Integer) sessionFactory.getCurrentSession()
				.createQuery("select max(ordinal) from ClinicalConversationTurn "
						+ "where conversation = :conversation")
				.setParameter("conversation", conversation)
				.uniqueResult();
		return max == null ? -1 : max;
	}

	@Override
	public int purgeBefore(Date before) {
		int turns = sessionFactory.getCurrentSession()
				.createQuery("delete from ClinicalConversationTurn "
						+ "where completedAt is not null and completedAt < :before")
				.setParameter("before", before)
				.executeUpdate();
		int conversations = sessionFactory.getCurrentSession()
				.createQuery("delete from ClinicalConversation c where c.lastActivityAt < :before "
						+ "and not exists (select 1 from ClinicalConversationTurn t "
						+ "where t.conversation = c)")
				.setParameter("before", before)
				.executeUpdate();
		return turns + conversations;
	}
}
