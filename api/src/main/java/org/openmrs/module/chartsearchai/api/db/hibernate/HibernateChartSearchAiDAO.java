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
import org.openmrs.module.chartsearchai.model.AiQueryLog;
import org.openmrs.module.chartsearchai.model.EmbeddingChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

@Repository("chartsearchai.ChartSearchAiDAO")
public class HibernateChartSearchAiDAO implements ChartSearchAiDAO {

	@Autowired
	@Qualifier("sessionFactory")
	private SessionFactory sessionFactory;

	@Override
	public EmbeddingChunk saveEmbeddingChunk(EmbeddingChunk chunk) {
		sessionFactory.getCurrentSession().saveOrUpdate(chunk);
		return chunk;
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<EmbeddingChunk> getEmbeddingChunksByPatient(Patient patient) {
		return sessionFactory.getCurrentSession()
				.createQuery("FROM EmbeddingChunk WHERE patient = :patient ORDER BY sourceDate DESC")
				.setParameter("patient", patient)
				.list();
	}

	@Override
	public void deleteEmbeddingChunksByPatient(Patient patient) {
		sessionFactory.getCurrentSession()
				.createQuery("DELETE FROM EmbeddingChunk WHERE patient = :patient")
				.setParameter("patient", patient)
				.executeUpdate();
	}

	@Override
	public AiQueryLog saveQueryLog(AiQueryLog queryLog) {
		sessionFactory.getCurrentSession().saveOrUpdate(queryLog);
		return queryLog;
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<AiQueryLog> getQueryLogsByPatient(Patient patient) {
		return sessionFactory.getCurrentSession()
				.createQuery("FROM AiQueryLog WHERE patient = :patient ORDER BY dateQueried DESC")
				.setParameter("patient", patient)
				.list();
	}
}
