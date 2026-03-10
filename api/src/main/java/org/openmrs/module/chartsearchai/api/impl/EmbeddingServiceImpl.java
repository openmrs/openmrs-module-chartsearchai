/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.openmrs.Encounter;
import org.openmrs.Patient;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.chartsearchai.api.EmbeddingService;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.model.EmbeddingChunk;
import org.openmrs.module.chartsearchai.model.TextChunk;
import org.openmrs.module.chartsearchai.pipeline.ClinicalChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of the embedding service. Currently uses a simple term-frequency based embedding
 * as a placeholder. In production, this should be replaced with a proper embedding model (e.g.,
 * ONNX Runtime with all-MiniLM-L6-v2 or an external embedding API).
 *
 * <p>For single-patient retrieval (typically &lt;500 chunks), brute-force cosine similarity in Java
 * is fast enough (&lt;10ms), so no vector database is needed.</p>
 */
@Service("chartsearchai.EmbeddingService")
@Transactional
public class EmbeddingServiceImpl extends BaseOpenmrsService implements EmbeddingService {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingServiceImpl.class);

	@Autowired
	private ChartSearchAiDAO dao;

	@Autowired
	private ClinicalChunker clinicalChunker;

	@Override
	public void indexPatient(Patient patient) {
		log.info("Indexing patient {}", patient.getUuid());

		dao.deleteEmbeddingChunksByPatient(patient);

		List<TextChunk> textChunks = clinicalChunker.chunkPatientData(patient);
		Date now = new Date();

		for (TextChunk textChunk : textChunks) {
			EmbeddingChunk chunk = new EmbeddingChunk();
			chunk.setPatient(patient);
			chunk.setChunkType(textChunk.getMetadata().getChunkType());
			chunk.setSourceUuid(textChunk.getMetadata().getSourceUuid());
			chunk.setSourceDate(textChunk.getMetadata().getSourceDate());
			chunk.setChunkText(textChunk.getText());
			chunk.setEmbeddingVector(computeEmbedding(textChunk.getText()));
			chunk.setDateIndexed(now);

			dao.saveEmbeddingChunk(chunk);
		}

		log.info("Indexed {} chunks for patient {}", textChunks.size(), patient.getUuid());
	}

	@Override
	public void indexEncounter(Encounter encounter) {
		Patient patient = encounter.getPatient();
		log.info("Incrementally indexing encounter {} for patient {}",
				encounter.getUuid(), patient.getUuid());

		// For incremental indexing, we re-index the entire patient.
		// A more sophisticated implementation could index just the encounter's chunks.
		indexPatient(patient);
	}

	@Override
	@Transactional(readOnly = true)
	public List<EmbeddingChunk> retrieveRelevantChunks(Patient patient, String query, int topK) {
		List<EmbeddingChunk> allChunks = dao.getEmbeddingChunksByPatient(patient);
		if (allChunks.isEmpty()) {
			return Collections.emptyList();
		}

		float[] queryEmbedding = computeEmbedding(query);

		// Compute cosine similarity for each chunk and sort by score
		List<Map.Entry<EmbeddingChunk, Double>> scored = new ArrayList<>();
		for (EmbeddingChunk chunk : allChunks) {
			double similarity = cosineSimilarity(queryEmbedding, chunk.getEmbeddingVector());
			scored.add(new AbstractMap.SimpleEntry<>(chunk, similarity));
		}

		Collections.sort(scored, new Comparator<Map.Entry<EmbeddingChunk, Double>>() {
			@Override
			public int compare(Map.Entry<EmbeddingChunk, Double> a, Map.Entry<EmbeddingChunk, Double> b) {
				return Double.compare(b.getValue(), a.getValue());
			}
		});

		List<EmbeddingChunk> results = new ArrayList<>();
		int limit = Math.min(topK, scored.size());
		for (int i = 0; i < limit; i++) {
			results.add(scored.get(i).getKey());
		}
		return results;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isPatientIndexed(Patient patient) {
		return !dao.getEmbeddingChunksByPatient(patient).isEmpty();
	}

	/**
	 * Placeholder embedding function using simple term-frequency hashing. This produces a
	 * deterministic vector that enables basic keyword-overlap retrieval.
	 *
	 * <p>TODO: Replace with a proper embedding model (ONNX Runtime + all-MiniLM-L6-v2 or an
	 * external embedding API) for semantic similarity.</p>
	 */
	private float[] computeEmbedding(String text) {
		int dimensions = 384;
		float[] embedding = new float[dimensions];

		String[] tokens = text.toLowerCase().split("\\W+");
		for (String token : tokens) {
			if (token.isEmpty()) {
				continue;
			}
			// Hash each token to a dimension and increment
			int index = Math.abs(token.hashCode() % dimensions);
			embedding[index] += 1.0f;
		}

		// L2 normalize
		double norm = 0;
		for (float v : embedding) {
			norm += v * v;
		}
		norm = Math.sqrt(norm);
		if (norm > 0) {
			for (int i = 0; i < embedding.length; i++) {
				embedding[i] /= (float) norm;
			}
		}

		return embedding;
	}

	private double cosineSimilarity(float[] a, float[] b) {
		if (a.length != b.length) {
			return 0;
		}
		double dot = 0;
		double normA = 0;
		double normB = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			normA += a[i] * a[i];
			normB += b[i] * b[i];
		}
		double denominator = Math.sqrt(normA) * Math.sqrt(normB);
		if (denominator == 0) {
			return 0;
		}
		return dot / denominator;
	}
}
