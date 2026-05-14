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

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openmrs.Diagnosis;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
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
	@Transactional
	public void indexPatient(Patient patient) {
		log.info("Indexing patient [id={}]", patient.getPatientId());
		Date now = new Date();

		// Build all embeddings first so the patient is never without data
		List<SerializedRecord> records = recordLoader.loadAll(patient);
		List<ChartEmbedding> newEmbeddings = buildEmbeddings(records, embeddingProvider);

		// Set patient and timestamp on each embedding (buildEmbeddings
		// intentionally omits these so it stays DAO/context-free).
		for (ChartEmbedding ce : newEmbeddings) {
			ce.setPatient(patient);
			ce.setDateCreated(now);
		}

		int failed = records.size() - newEmbeddings.size();
		if (failed > 0) {
			log.warn("Skipped {} of {} records during indexing of patient [id={}]",
					failed, records.size(), patient.getPatientId());
		}

		// Delete old and insert new atomically within this method's
		// transaction so the patient is never without embeddings.
		dao.replacePatientEmbeddings(patient, newEmbeddings);

		log.info("Finished indexing patient [id={}] ({} of {} records)",
				patient.getPatientId(), newEmbeddings.size(), records.size());
	}

	/**
	 * Deletes all embeddings for a patient. Used after a patient merge to clean up
	 * the non-preferred patient's stale embeddings.
	 *
	 * @param patient the patient whose embeddings to delete
	 */
	@Transactional
	public void deletePatientEmbeddings(Patient patient) {
		log.info("Deleting all embeddings for patient [id={}]", patient.getPatientId());
		dao.deleteByPatient(patient);
	}

	/**
	 * Incremental index of a single encounter. Upserts embeddings for the encounter's
	 * observations and diagnoses without re-indexing the entire patient.
	 *
	 * @param encounter the encounter to index
	 */
	@Transactional
	public void indexEncounter(Encounter encounter) {
		Patient patient = encounter.getPatient();
		if (patient == null) {
			log.warn("Skipping indexing for encounter [id={}] — no patient", encounter.getEncounterId());
			return;
		}
		log.info("Incrementally indexing encounter [id={}] for patient [id={}]",
				encounter.getEncounterId(), patient.getPatientId());
		Date now = new Date();
		Set<String> seenText = new HashSet<String>();

		Set<Obs> allObs = encounter.getAllObs();
		if (allObs != null) {
			for (Obs obs : allObs) {
				if (obs.getObsGroup() != null) {
					continue;
				}
				String text = obsSerializer.toText(obs);
				if (text != null && !text.trim().isEmpty() && seenText.add(text)) {
					try {
						List<String> hints = ChartSearchAiUtils.extractCategoryHints(obs.getConcept());
						upsertEmbedding(patient, ChartSearchAiConstants.RESOURCE_TYPE_OBS,
								obs.getUuid(), text, hints, now);
					}
					catch (Exception e) {
						log.warn("Failed to embed obs [uuid={}] for patient [id={}]: {}",
								obs.getUuid(), patient.getPatientId(), e.getMessage());
					}
				}
			}
		}

		if (encounter.getDiagnoses() != null) {
			for (Diagnosis diagnosis : encounter.getDiagnoses()) {
				String text = diagnosisSerializer.toText(diagnosis);
				if (text != null && !text.trim().isEmpty() && seenText.add(text)) {
					try {
						upsertEmbedding(patient, ChartSearchAiConstants.RESOURCE_TYPE_DIAGNOSIS,
								diagnosis.getUuid(), text,
								Collections.<String>emptyList(), now);
					}
					catch (Exception e) {
						log.warn("Failed to embed diagnosis [uuid={}] for patient [id={}]: {}",
								diagnosis.getUuid(), patient.getPatientId(), e.getMessage());
					}
				}
			}
		}

		log.info("Finished indexing encounter [id={}]", encounter.getEncounterId());
	}

	private void saveEmbedding(Patient patient, String resourceType, String resourceUuid,
			String text, List<String> categoryHints, Date now) {
		String hintInjectedBody = ChartSearchAiUtils.injectCategoryHints(text, categoryHints);
		ChartEmbedding ce = new ChartEmbedding();
		ce.setPatient(patient);
		ce.setResourceType(resourceType);
		ce.setResourceUuid(resourceUuid);
		ce.setTextContent(hintInjectedBody);
		ce.setEmbeddingVector(embeddingProvider.embed(
				ChartSearchAiUtils.buildPrefixedText(resourceType, hintInjectedBody)));
		ce.setDateCreated(now);
		dao.saveChartEmbedding(ce);
	}

	private void upsertEmbedding(Patient patient, String resourceType, String resourceUuid,
			String text, List<String> categoryHints, Date now) {
		ChartEmbedding existing = dao.getByResource(resourceType, resourceUuid);
		if (existing != null) {
			String hintInjectedBody = ChartSearchAiUtils.injectCategoryHints(text, categoryHints);
			existing.setTextContent(hintInjectedBody);
			existing.setEmbeddingVector(embeddingProvider.embed(
					ChartSearchAiUtils.buildPrefixedText(resourceType, hintInjectedBody)));
			existing.setDateCreated(now);
			dao.saveChartEmbedding(existing);
		} else {
			saveEmbedding(patient, resourceType, resourceUuid, text, categoryHints, now);
		}
	}

	/**
	 * Builds {@link ChartEmbedding} objects from serialized records using the
	 * exact same logic as {@link #indexPatient(Patient)}. This static method
	 * exists so integration tests can call the real production embedding code
	 * without needing a DAO, Spring context, or any simulation.
	 *
	 * @param records the serialized patient records
	 * @param provider the embedding provider to compute vectors
	 * @return list of chart embeddings with vectors, resource types, and text content set
	 */
	public static List<ChartEmbedding> buildEmbeddings(
			List<SerializedRecord> records, EmbeddingProvider provider) {
		List<ChartEmbedding> result = new java.util.ArrayList<ChartEmbedding>(records.size());
		for (SerializedRecord record : records) {
			try {
				ChartEmbedding ce = new ChartEmbedding();
				ce.setResourceType(record.getResourceType());
				ce.setResourceUuid(record.getResourceUuid());
				// Inject category hints into the stored body so that downstream
				// keyword scoring and concept-name extraction see the same
				// hint-augmented text the embedding vector was computed from.
				String hintInjectedBody = ChartSearchAiUtils.injectCategoryHints(
						record.getText(), record.getCategoryHints());
				ce.setTextContent(hintInjectedBody);
				ce.setEmbeddingVector(provider.embed(
						ChartSearchAiUtils.buildPrefixedText(
								record.getResourceType(), hintInjectedBody)));
				result.add(ce);
			}
			catch (Exception e) {
				log.warn("Failed to embed {} [uuid={}]: {}",
						record.getResourceType(), record.getResourceUuid(),
						e.getMessage());
			}
		}
		return result;
	}

	/**
	 * Extracts the first sentence from serialized record text (up to the first
	 * ". "). Retained for backward compatibility and testing; embeddings now use
	 * the full serialized text to capture dosing, severity, reactions, and other
	 * details beyond the opening sentence.
	 */
	static String firstSentence(String text) {
		if (text == null) {
			return "";
		}
		int idx = text.indexOf(". ");
		return idx > 0 ? text.substring(0, idx) : text;
	}

}
