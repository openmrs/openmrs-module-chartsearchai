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

import java.util.List;

import org.openmrs.Encounter;
import org.openmrs.Patient;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.model.EmbeddingChunk;

/**
 * Service for managing the vector embedding index of patient clinical data. Handles full and
 * incremental indexing, as well as similarity-based retrieval of relevant chunks for a query.
 */
public interface EmbeddingService extends OpenmrsService {

	/**
	 * Index (or re-index) all clinical data for a patient. Deletes any existing chunks and creates
	 * new embeddings from the current state of the patient's chart.
	 *
	 * @param patient the patient to index
	 */
	@Authorized(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)
	void indexPatient(Patient patient);

	/**
	 * Incrementally index a single encounter. Creates embedding chunks for the encounter's data
	 * without re-indexing the entire patient record.
	 *
	 * @param encounter the encounter to index
	 */
	@Authorized(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)
	void indexEncounter(Encounter encounter);

	/**
	 * Retrieve the most relevant embedding chunks for a natural-language query against a patient's
	 * indexed data. Uses cosine similarity between the query embedding and stored chunk embeddings.
	 *
	 * @param patient the patient whose chunks to search
	 * @param query the natural-language query to match against
	 * @param topK the maximum number of chunks to return
	 * @return the most relevant chunks, ordered by similarity (highest first)
	 */
	@Authorized(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA)
	List<EmbeddingChunk> retrieveRelevantChunks(Patient patient, String query, int topK);

	/**
	 * Check whether a patient's data has been indexed.
	 *
	 * @param patient the patient to check
	 * @return true if the patient has embedding chunks in the index
	 */
	boolean isPatientIndexed(Patient patient);
}
