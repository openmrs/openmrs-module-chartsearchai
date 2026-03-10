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
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.EmbeddingService;
import org.openmrs.module.chartsearchai.api.db.ChartSearchAiDAO;
import org.openmrs.module.chartsearchai.embedding.EmbeddingProvider;
import org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider;
import org.openmrs.module.chartsearchai.embedding.TermFrequencyEmbeddingProvider;
import org.openmrs.module.chartsearchai.model.EmbeddingChunk;
import org.openmrs.module.chartsearchai.model.TextChunk;
import org.openmrs.module.chartsearchai.pipeline.ClinicalChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of the embedding service. Uses ONNX Runtime with the all-MiniLM-L6-v2 model
 * for semantic embeddings when available, falling back to term-frequency hashing otherwise.
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

	private volatile EmbeddingProvider embeddingProvider;

	@Override
	public void indexPatient(Patient patient) {
		log.info("Indexing patient {}", patient.getUuid());

		dao.deleteEmbeddingChunksByPatient(patient);

		List<TextChunk> textChunks = clinicalChunker.chunkPatientData(patient);
		Date now = new Date();
		EmbeddingProvider provider = getEmbeddingProvider();

		for (TextChunk textChunk : textChunks) {
			EmbeddingChunk chunk = new EmbeddingChunk();
			chunk.setPatient(patient);
			chunk.setChunkType(textChunk.getMetadata().getChunkType());
			chunk.setSourceUuid(textChunk.getMetadata().getSourceUuid());
			chunk.setSourceDate(textChunk.getMetadata().getSourceDate());
			chunk.setChunkText(textChunk.getText());
			chunk.setEmbeddingVector(provider.embed(textChunk.getText()));
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

		List<TextChunk> encounterChunks = clinicalChunker.chunkEncounter(encounter, patient.getUuid());
		Date now = new Date();
		EmbeddingProvider provider = getEmbeddingProvider();

		for (TextChunk textChunk : encounterChunks) {
			EmbeddingChunk chunk = new EmbeddingChunk();
			chunk.setPatient(patient);
			chunk.setChunkType(textChunk.getMetadata().getChunkType());
			chunk.setSourceUuid(textChunk.getMetadata().getSourceUuid());
			chunk.setSourceDate(textChunk.getMetadata().getSourceDate());
			chunk.setChunkText(textChunk.getText());
			chunk.setEmbeddingVector(provider.embed(textChunk.getText()));
			chunk.setDateIndexed(now);

			dao.saveEmbeddingChunk(chunk);
		}

		log.info("Indexed {} chunks for encounter {}", encounterChunks.size(), encounter.getUuid());
	}

	@Override
	@Transactional(readOnly = true)
	public List<EmbeddingChunk> retrieveRelevantChunks(Patient patient, String query, int topK) {
		List<EmbeddingChunk> allChunks = dao.getEmbeddingChunksByPatient(patient);
		if (allChunks.isEmpty()) {
			return Collections.emptyList();
		}

		EmbeddingProvider provider = getEmbeddingProvider();
		float[] queryEmbedding = provider.embed(query);

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
	 * Lazily initialize the embedding provider. Attempts to load the ONNX model first; if
	 * unavailable, falls back to the term-frequency provider with a warning.
	 */
	private EmbeddingProvider getEmbeddingProvider() {
		if (embeddingProvider == null) {
			synchronized (this) {
				if (embeddingProvider == null) {
					String providerType = null;
					try {
						providerType = Context.getAdministrationService()
								.getGlobalProperty(ChartSearchAiConstants.GP_EMBEDDING_PROVIDER);
					}
					catch (Exception e) {
						log.debug("Could not read embedding provider setting");
					}

					if ("termfrequency".equals(providerType)) {
						log.info("Using term-frequency embedding provider (configured)");
						embeddingProvider = new TermFrequencyEmbeddingProvider();
					} else {
						try {
							embeddingProvider = new OnnxEmbeddingProvider();
							log.info("Using ONNX embedding provider (all-MiniLM-L6-v2)");
						}
						catch (Exception e) {
							log.warn("Failed to load ONNX embedding model: {}. "
									+ "Falling back to term-frequency embeddings. "
									+ "Search quality will be degraded. "
									+ "To fix this, download the all-MiniLM-L6-v2 ONNX model "
									+ "and place it in {}/chartsearchai/",
									e.getMessage(),
									org.openmrs.util.OpenmrsUtil.getApplicationDataDirectory());
							embeddingProvider = new TermFrequencyEmbeddingProvider();
						}
					}
				}
			}
		}
		return embeddingProvider;
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
