/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.embedding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.module.chartsearchai.util.ConceptNameUtil;

/**
 * Characterizes an embedding model's noise baseline from a patient's
 * own embedding matrix. Cross-concept pairwise cosine similarities
 * define "noise" (what unrelated records look like), while intra-concept
 * similarities define "signal" (what same-topic records look like).
 * All pipeline thresholds derive from these two distributions, making
 * the filtering pipeline fully generic across embedding models.
 *
 * <p>Computed once per {@code findSimilar} call from the patient's
 * embeddings. The cost is O(C²) cosine similarity computations where
 * C is the number of unique concepts (typically 20-45), negligible
 * compared to the N ONNX embedding inferences.</p>
 */
public final class ModelNoiseProfile {

	/** Minimum cross-concept pairs required for robust statistics. */
	private static final int MIN_CROSS_PAIRS = 10;

	/** Mean cosine similarity between records of different concepts. */
	public final double noiseMean;

	/** Standard deviation of cross-concept cosine similarities. */
	public final double noiseStd;

	/** Median of cross-concept cosine similarities. */
	public final double noiseMedian;

	/** First quartile of cross-concept cosine similarities. */
	public final double noiseQ1;

	/** 95th percentile of cross-concept cosine similarities. */
	public final double noiseP95;

	/** Mean cosine similarity between records of the same concept. */
	public final double intraConceptMean;

	/** Standard deviation of intra-concept cosine similarities. */
	public final double intraConceptStd;

	public ModelNoiseProfile(double noiseMean, double noiseStd,
			double noiseMedian, double noiseQ1, double noiseP95,
			double intraConceptMean, double intraConceptStd) {
		this.noiseMean = noiseMean;
		this.noiseStd = noiseStd;
		this.noiseMedian = noiseMedian;
		this.noiseQ1 = noiseQ1;
		this.noiseP95 = noiseP95;
		this.intraConceptMean = intraConceptMean;
		this.intraConceptStd = intraConceptStd;
	}

	/**
	 * Computes a noise profile from a patient's embeddings. Groups
	 * records by concept name, then computes cross-concept and
	 * intra-concept pairwise cosine similarities using one
	 * representative vector per concept for cross-concept pairs.
	 *
	 * @param embeddings the patient's chart embeddings
	 * @return the noise profile, or a conservative default if the
	 *         patient has too few distinct concepts
	 */
	public static ModelNoiseProfile compute(
			ChartEmbedding[] embeddings) {
		return compute(embeddings, null);
	}

	/**
	 * Computes a noise profile, optionally using hint-stripped embeddings
	 * for cross-concept similarity. When {@code provider} is non-null,
	 * the representative record for each concept is re-embedded with its
	 * category hints stripped (via {@link ChartSearchAiUtils#stripCategoryHints}).
	 * This makes the noise profile STABLE across hint enrichment —
	 * adding "Vital signs / " to Temperature records no longer inflates
	 * cross-concept similarity because the pairwise comparison uses
	 * the UNHINTED embedding of "Finding — Temperature (C): 36.7".
	 *
	 * <p>Cost: O(C) extra embeddings where C = unique concepts (~30).
	 * At ~1ms per embed, ~30ms total — negligible vs LLM latency.</p>
	 *
	 * @param embeddings the patient's chart embeddings
	 * @param provider embedding provider for re-embedding stripped text,
	 *        or null to fall back to record-embedding-based computation
	 */
	public static ModelNoiseProfile compute(
			ChartEmbedding[] embeddings, EmbeddingProvider provider) {
		// Group by concept name, tracking representative records
		Map<String, List<float[]>> byConcept =
				new HashMap<String, List<float[]>>();
		Map<String, ChartEmbedding> representativeRecord =
				new HashMap<String, ChartEmbedding>();
		for (ChartEmbedding ce : embeddings) {
			if (ce.getEmbeddingVector() == null) {
				continue;
			}
			String name = ConceptNameUtil.extractConceptName(
					ce.getTextContent());
			if (name == null) {
				name = ce.getResourceType() + ":" + ce.getResourceUuid();
			}
			List<float[]> vecs = byConcept.get(name);
			if (vecs == null) {
				vecs = new ArrayList<float[]>();
				byConcept.put(name, vecs);
				representativeRecord.put(name, ce);
			}
			vecs.add(ce.getEmbeddingVector());
		}

		List<String> concepts = new ArrayList<String>(
				byConcept.keySet());

		// Cross-concept similarities: one representative per concept.
		// When a provider is available, re-embed the representative
		// record's text with hints stripped so enrichment doesn't
		// inflate cross-concept similarity.
		Map<String, float[]> crossVectors = new HashMap<String, float[]>();
		for (String name : concepts) {
			if (provider != null) {
				ChartEmbedding rep = representativeRecord.get(name);
				try {
					String stripped = ChartSearchAiUtils.stripCategoryHints(
							rep.getTextContent());
					String prefixed = ChartSearchAiUtils.buildPrefixedText(
							rep.getResourceType(), stripped);
					crossVectors.put(name, provider.embed(prefixed));
				} catch (Exception e) {
					crossVectors.put(name,
							byConcept.get(name).get(0));
				}
			} else {
				crossVectors.put(name, byConcept.get(name).get(0));
			}
		}

		List<Double> crossSims = new ArrayList<Double>();
		for (int i = 0; i < concepts.size(); i++) {
			float[] vi = crossVectors.get(concepts.get(i));
			for (int j = i + 1; j < concepts.size(); j++) {
				float[] vj = crossVectors.get(concepts.get(j));
				crossSims.add(ChartSearchAiUtils.cosineSimilarity(
						vi, vj));
			}
		}

		if (crossSims.size() < MIN_CROSS_PAIRS) {
			return conservativeDefault();
		}

		// Intra-concept similarities
		List<Double> intraSims = new ArrayList<Double>();
		for (List<float[]> vecs : byConcept.values()) {
			for (int i = 0; i < vecs.size(); i++) {
				for (int j = i + 1; j < vecs.size(); j++) {
					intraSims.add(
							ChartSearchAiUtils.cosineSimilarity(
									vecs.get(i), vecs.get(j)));
				}
			}
		}

		// Cross-concept statistics
		double crossSum = 0;
		for (double s : crossSims) {
			crossSum += s;
		}
		double crossMean = crossSum / crossSims.size();
		double crossSqSum = 0;
		for (double s : crossSims) {
			double d = s - crossMean;
			crossSqSum += d * d;
		}
		double crossStd = Math.sqrt(crossSqSum / crossSims.size());
		Collections.sort(crossSims);
		double crossMedian = crossSims.get(crossSims.size() / 2);
		// Linear-interpolation-with-floor percentile rank. Using
		// size() * p caps at the maximum element for any size <= 20,
		// which inflates the noise ceiling on patients with few
		// distinct concepts.
		double crossP95 = crossSims.get(
				(int) ((crossSims.size() - 1) * 0.95));

		// Intra-concept statistics
		double intraMean;
		double intraStd;
		if (intraSims.isEmpty()) {
			// Only one record per concept — use cross-concept stats
			// shifted up as a reasonable estimate
			intraMean = crossP95 + 2 * crossStd;
			intraStd = crossStd;
		} else {
			double intraSum = 0;
			for (double s : intraSims) {
				intraSum += s;
			}
			intraMean = intraSum / intraSims.size();
			double intraSqSum = 0;
			for (double s : intraSims) {
				double d = s - intraMean;
				intraSqSum += d * d;
			}
			intraStd = Math.sqrt(intraSqSum / intraSims.size());
		}

		double crossQ1 = crossSims.get(
				(int) ((crossSims.size() - 1) * 0.25));

		return new ModelNoiseProfile(crossMean, crossStd,
				crossMedian, crossQ1, crossP95,
				intraMean, intraStd);
	}

	/**
	 * Conservative default for patients with too few concepts.
	 * Uses wide margins so the pipeline errs on the side of
	 * including results rather than rejecting them.
	 */
	private static final ModelNoiseProfile CONSERVATIVE_DEFAULT =
			new ModelNoiseProfile(
					0.15,  // noiseMean
					0.08,  // noiseStd
					0.13,  // noiseMedian
					0.08,  // noiseQ1
					0.28,  // noiseP95
					0.90,  // intraConceptMean
					0.05); // intraConceptStd

	public static ModelNoiseProfile conservativeDefault() {
		return CONSERVATIVE_DEFAULT;
	}

	/**
	 * Returns true if this is the placeholder conservative default,
	 * meaning a real noise profile should be computed from the data.
	 */
	public boolean isConservativeDefault() {
		return this == CONSERVATIVE_DEFAULT;
	}

	/**
	 * The similarity floor: average of the lower half of cross-concept
	 * cosine similarities (mean of values below the median). The
	 * cross-concept distribution is bimodal — truly unrelated pairs
	 * form a lower mode and related-but-different concept pairs
	 * (e.g., different vital sign types) form an upper mode. The
	 * lower-half mean captures the center of the lower mode,
	 * representing the typical noise similarity between genuinely
	 * unrelated records. This avoids the inflation from the upper
	 * mode that affects the overall mean and median.
	 */
	public double absoluteSimilarityFloor() {
		// Skew-corrected half of the noise ceiling (P95).
		// The 95th percentile is the model's noise ceiling —
		// the highest cosine that cross-concept (unrelated)
		// pairs reach. Dividing by (1 + median/mean) gives
		// P95/2 for symmetric distributions, but pushes the
		// floor up when mean > median (right-skewed due to
		// the upper mode of related-but-different concepts).
		// This correction ensures the floor tracks the model's
		// effective noise boundary across datasets with
		// different concept-relatedness profiles.
		double denom = noiseMean + noiseMedian;
		if (denom <= 0) {
			return noiseP95 / 2;
		}
		return noiseP95 * noiseMean / denom;
	}

}
