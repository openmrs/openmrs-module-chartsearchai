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

import java.util.Date;
import java.util.List;

import org.openmrs.Diagnosis;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.embedding.EmbeddingProvider;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.module.chartsearchai.serializer.DiagnosisTextSerializer;
import org.openmrs.module.chartsearchai.serializer.ObsTextSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Indexes patient clinical data by converting records to text via serializers, computing
 * vector embeddings, and persisting them to the {@code chartsearchai_embedding} table.
 * Supports both full patient re-indexing and incremental encounter indexing.
 */
@Component
@Transactional
public class EmbeddingIndexer {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexer.class);

	@Autowired
	private PatientRecordLoader recordLoader;

	@Autowired
	private ObsTextSerializer obsSerializer;

	@Autowired
	private DiagnosisTextSerializer diagnosisSerializer;

	@Autowired
	@Qualifier("chartSearchAi.embeddingProvider")
	private EmbeddingProvider embeddingProvider;

	@Autowired
	private ChartSearchAiDAO dao;

	/**
	 * Full index of a patient's chart. Deletes existing embeddings and re-indexes all
	 * clinical data. Used on first query or as a nightly batch job.
	 *
	 * @param patient the patient to index
	 */
	public void indexPatient(Patient patient) {
		log.info("Indexing patient [id={}]", patient.getPatientId());
		dao.deleteByPatient(patient);
		Date now = new Date();

		List<SerializedRecord> records = recordLoader.loadAll(patient);
		for (SerializedRecord record : records) {
			saveEmbedding(patient, record.getResourceType(), record.getResourceId(),
					record.getText(), now);
		}

		log.info("Finished indexing patient [id={}] ({} records)", patient.getPatientId(), records.size());
	}

	/**
	 * Incremental index of a single encounter. Upserts embeddings for the encounter's
	 * observations and diagnoses without re-indexing the entire patient.
	 *
	 * @param encounter the encounter to index
	 */
	public void indexEncounter(Encounter encounter) {
		Patient patient = encounter.getPatient();
		log.info("Incrementally indexing encounter [id={}] for patient [id={}]",
				encounter.getEncounterId(), patient.getPatientId());
		Date now = new Date();

		for (Obs obs : encounter.getAllObs()) {
			if (obs.getObsGroup() != null) {
				continue;
			}
			upsertEmbedding(patient, "obs", obs.getObsId(),
					obsSerializer.toText(obs), now);
		}

		if (encounter.getDiagnoses() != null) {
			for (Diagnosis diagnosis : encounter.getDiagnoses()) {
				upsertEmbedding(patient, "diagnosis", diagnosis.getDiagnosisId(),
						diagnosisSerializer.toText(diagnosis), now);
			}
		}

		log.info("Finished indexing encounter [id={}]", encounter.getEncounterId());
	}

	private void saveEmbedding(Patient patient, String resourceType, Integer resourceId,
			String text, Date now) {
		ChartEmbedding ce = new ChartEmbedding();
		ce.setPatient(patient);
		ce.setResourceType(resourceType);
		ce.setResourceId(resourceId);
		ce.setTextContent(text);
		ce.setEmbeddingVector(embeddingProvider.embed(text));
		ce.setDateCreated(now);
		dao.saveChartEmbedding(ce);
	}

	private void upsertEmbedding(Patient patient, String resourceType, Integer resourceId,
			String text, Date now) {
		ChartEmbedding existing = dao.getByResource(resourceType, resourceId);
		if (existing != null) {
			existing.setTextContent(text);
			existing.setEmbeddingVector(embeddingProvider.embed(text));
			existing.setDateCreated(now);
			dao.saveChartEmbedding(existing);
		} else {
			saveEmbedding(patient, resourceType, resourceId, text, now);
		}
	}
}
