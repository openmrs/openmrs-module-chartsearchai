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

import java.util.Date;
import java.util.List;

import org.hibernate.query.Query;
import org.hibernate.SessionFactory;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class HibernateChartSearchAiDAO implements ChartSearchAiDAO {

	@Autowired
	private SessionFactory sessionFactory;

	@Override
	public ChartEmbedding saveChartEmbedding(ChartEmbedding chartEmbedding) {
		sessionFactory.getCurrentSession().saveOrUpdate(chartEmbedding);
		return chartEmbedding;
	}

	@Override
	@SuppressWarnings("unchecked")
	public ChartEmbedding getByResource(String resourceType, Integer resourceId) {
		List<ChartEmbedding> results = sessionFactory.getCurrentSession()
				.createQuery("from ChartEmbedding where resourceType = :type and resourceId = :id")
				.setParameter("type", resourceType)
				.setParameter("id", resourceId)
				.list();
		return results.isEmpty() ? null : results.get(0);
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<ChartEmbedding> getByPatient(Patient patient) {
		return sessionFactory.getCurrentSession()
				.createQuery("from ChartEmbedding where patient = :patient order by dateCreated desc")
				.setParameter("patient", patient)
				.list();
	}

	@Override
	public void deleteByPatient(Patient patient) {
		sessionFactory.getCurrentSession()
				.createQuery("delete from ChartEmbedding where patient = :patient")
				.setParameter("patient", patient)
				.executeUpdate();
	}

	@Override
	public ChartSearchAuditLog saveAuditLog(ChartSearchAuditLog auditLog) {
		sessionFactory.getCurrentSession().save(auditLog);
		return auditLog;
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<Integer> getIndexedPatientIds() {
		return sessionFactory.getCurrentSession()
				.createQuery("select distinct patient.patientId from ChartEmbedding")
				.list();
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<ChartSearchAuditLog> getAuditLogs(Patient patient, User user, Date fromDate, Date toDate,
			Integer startIndex, Integer limit) {
		Query query = buildAuditLogQuery("from ChartSearchAuditLog", patient, user, fromDate, toDate);
		query.setFirstResult(startIndex != null && startIndex > 0 ? startIndex : 0);
		query.setMaxResults(limit != null && limit > 0 ? limit : 50);
		return query.list();
	}

	@Override
	public Long getAuditLogCount(Patient patient, User user, Date fromDate, Date toDate) {
		Query query = buildAuditLogQuery("select count(*) from ChartSearchAuditLog",
				patient, user, fromDate, toDate);
		return (Long) query.uniqueResult();
	}

	@Override
	public long getQueryCountByUserSince(User user, Date since) {
		Long count = (Long) sessionFactory.getCurrentSession()
				.createQuery("select count(*) from ChartSearchAuditLog where user = :user and dateCreated >= :since")
				.setParameter("user", user)
				.setParameter("since", since)
				.uniqueResult();
		return count != null ? count : 0;
	}

	@Override
	public int deleteAuditLogsBefore(Date before) {
		return sessionFactory.getCurrentSession()
				.createQuery("delete from ChartSearchAuditLog where dateCreated < :before")
				.setParameter("before", before)
				.executeUpdate();
	}

	private Query buildAuditLogQuery(String select, Patient patient, User user, Date fromDate, Date toDate) {
		StringBuilder hql = new StringBuilder(select);
		hql.append(" where 1=1");

		if (patient != null) {
			hql.append(" and patient = :patient");
		}
		if (user != null) {
			hql.append(" and user = :user");
		}
		if (fromDate != null) {
			hql.append(" and dateCreated >= :fromDate");
		}
		if (toDate != null) {
			hql.append(" and dateCreated <= :toDate");
		}

		if (!select.startsWith("select count")) {
			hql.append(" order by dateCreated desc");
		}

		Query query = sessionFactory.getCurrentSession().createQuery(hql.toString());

		if (patient != null) {
			query.setParameter("patient", patient);
		}
		if (user != null) {
			query.setParameter("user", user);
		}
		if (fromDate != null) {
			query.setParameter("fromDate", fromDate);
		}
		if (toDate != null) {
			query.setParameter("toDate", toDate);
		}

		return query;
	}
}
