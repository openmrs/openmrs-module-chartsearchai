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

import java.util.List;

import org.hibernate.SessionFactory;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
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
}
