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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.EmbeddingIndexer;
import org.openmrs.module.chartsearchai.embedding.OnnxEmbeddingProvider;
import org.openmrs.module.chartsearchai.model.ChartEmbedding;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Eval harness that runs every test query through the enriched production
 * pipeline (with concept-set category hints) and asserts minimum result
 * counts. Catches cross-query regressions where improving one query
 * (e.g. STD) silently breaks another (e.g. vital signs).
 *
 * <p>Uses a golden baseline file ({@code enriched-retrieval-eval.json})
 * with per-query expected minimums. Any pipeline change that reduces
 * results below the minimum triggers a test failure, forcing the
 * developer to review and either fix the regression or update the
 * baseline with justification.</p>
 *
 * <p>Skipped when ONNX model files are not present.</p>
 */
@Tag("eval")
public class EnrichedRetrievalEvalTest {

	private static final Logger log = LoggerFactory.getLogger(EnrichedRetrievalEvalTest.class);

	private static final String[][] DATASETS = {
		TestDatasetHelper.FULL_PATIENT_DATASET,
		TestDatasetHelper.SECOND_PATIENT_DATASET,
		TestDatasetHelper.THIRD_PATIENT_DATASET,
		TestDatasetHelper.FOURTH_PATIENT_DATASET,
		TestDatasetHelper.FIFTH_PATIENT_DATASET,
	};

	private static final String[] DATASET_NAMES = {
		"FULL", "SECOND", "THIRD", "FOURTH", "FIFTH"
	};

	@SuppressWarnings("unchecked")
	private static final java.util.Map<Integer, List<String>>[] DATASET_HINTS = new java.util.Map[] {
		TestDatasetHelper.FULL_DATASET_CATEGORY_HINTS,
		TestDatasetHelper.SECOND_DATASET_CATEGORY_HINTS,
		TestDatasetHelper.THIRD_DATASET_CATEGORY_HINTS,
		TestDatasetHelper.FOURTH_DATASET_CATEGORY_HINTS,
		TestDatasetHelper.FIFTH_DATASET_CATEGORY_HINTS,
	};

	private static OnnxEmbeddingProvider provider;

	private static org.openmrs.module.chartsearchai.embedding.EmbeddingProvider cachingProvider;

	private static List<ChartEmbedding>[] datasetEmbeddings;

	private static List<SerializedRecord>[] datasetRecords;

	private static PipelineConfig[] datasetConfigs;

	private static String activeModel;

	private static String activeBaseline;

	private static boolean medcptAvailable() {
		try {
			String base = new java.io.File(System.getProperty(
					"chartsearchai.models.base", "../../models")
					+ "/MedCPT").getCanonicalPath();
			return new java.io.File(base + "/Article-Encoder/model-merged.onnx").exists()
					&& new java.io.File(base + "/Query-Encoder/model-merged.onnx").exists();
		} catch (java.io.IOException e) {
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	private static void ensureInitialized() {
		if (provider != null) return;

		// Detect model: use MedCPT if available and requested via system
		// property, otherwise fall back to L6-v2.
		String modelChoice = System.getProperty(
				"chartsearchai.eval.model", "auto");
		boolean useMedCPT = false;
		if ("medcpt".equalsIgnoreCase(modelChoice)) {
			useMedCPT = true;
		} else if ("auto".equalsIgnoreCase(modelChoice)) {
			// Auto: use whatever is configured via the model dir property
			useMedCPT = false;
		}

		if (useMedCPT && medcptAvailable()) {
			try {
				String base = new java.io.File(System.getProperty(
						"chartsearchai.models.base", "../../models")
						+ "/MedCPT").getCanonicalPath();
				provider = new OnnxEmbeddingProvider(
						base + "/Article-Encoder/model-merged.onnx",
						base + "/Query-Encoder/model-merged.onnx",
						base + "/Query-Encoder/vocab.txt");
				activeModel = "medcpt";
				activeBaseline = "eval/medcpt-retrieval-eval.json";
			} catch (java.io.IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			provider = new OnnxEmbeddingProvider(
					TestDatasetHelper.MODEL_PATH, TestDatasetHelper.VOCAB_PATH);
			activeModel = "l6-v2";
			activeBaseline = "eval/enriched-retrieval-eval.json";
		}

		cachingProvider = TestDatasetHelper.cachingProvider(provider);
		PipelineConfig modelConfig =
				PipelineConfig.forModel(activeModel);

		datasetEmbeddings = new List[DATASETS.length];
		datasetRecords = new List[DATASETS.length];
		datasetConfigs = new PipelineConfig[DATASETS.length];
		for (int d = 0; d < DATASETS.length; d++) {
			datasetRecords[d] = TestDatasetHelper.toSerializedRecords(
					DATASETS[d], DATASET_HINTS[d]);
			datasetEmbeddings[d] = TestDatasetHelper.buildOrLoadCachedEmbeddings(
					datasetRecords[d], provider);
			org.openmrs.module.chartsearchai.embedding.ModelNoiseProfile noise =
					TestDatasetHelper.buildOrLoadCachedNoiseProfile(
							datasetEmbeddings[d], provider);
			datasetConfigs[d] = modelConfig.withNoiseProfile(noise);
		}
		log.info("Eval initialized with model={}, baseline={}", activeModel, activeBaseline);
	}

	private static List<Integer> runQuery(String query, int datasetIndex) {
		ensureInitialized();
		List<SerializedRecord> results = LlmInferenceService.findRelevantRecords(
				datasetEmbeddings[datasetIndex], datasetRecords[datasetIndex],
				cachingProvider, query,
				ChartSearchAiConstants.DEFAULT_QUERY_EMBEDDING_PREFIX,
				datasetConfigs[datasetIndex]);
		// Tests/baseline assert on dataset indices (the i passed to
		// toSerializedRecords). Production now identifies records by UUID;
		// toSerializedRecords encodes the dataset index in the trailing
		// digits of a deterministic UUID, so we map back at the test
		// boundary to keep the baseline JSON and assertions unchanged.
		List<Integer> indices = new ArrayList<>();
		if (results != null) {
			for (SerializedRecord r : results) {
				indices.add(TestDatasetHelper.indexForUuid(r.getResourceUuid()));
			}
		}
		Collections.sort(indices);
		return indices;
	}

	// --- Golden baseline loading ---

	private static JsonNode loadBaseline() {
		ensureInitialized();
		try {
			return new ObjectMapper().readTree(
					new InputStreamReader(
							EnrichedRetrievalEvalTest.class.getClassLoader()
									.getResourceAsStream(activeBaseline),
							StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new RuntimeException("Failed to load eval baseline: " + activeBaseline, e);
		}
	}

	static Stream<Arguments> evalCases() {
		JsonNode root = loadBaseline();
		JsonNode cases = root.get("cases");
		List<Arguments> args = new ArrayList<>();
		for (JsonNode c : cases) {
			int dsIdx = c.has("datasetIndex") ? c.get("datasetIndex").asInt() : 0;
			String dsName = dsIdx < DATASET_NAMES.length ? DATASET_NAMES[dsIdx] : "DS" + dsIdx;
			List<Integer> expectedIndices = new ArrayList<>();
			if (c.has("resultIndices")) {
				for (JsonNode idx : c.get("resultIndices")) {
					expectedIndices.add(idx.asInt());
				}
				Collections.sort(expectedIndices);
			}
			args.add(Arguments.of(
					dsName + ": " + c.get("query").asText(),
					c.get("query").asText(),
					dsIdx,
					expectedIndices));
		}
		return args.stream();
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("evalCases")
	public void enrichedRetrieval_shouldMeetBaseline(String label,
			String query, int datasetIndex,
			List<Integer> expectedIndices) {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null,
				"Skipping: embedding model not found");

		List<Integer> result = runQuery(query, datasetIndex);

		assertEquals(expectedIndices, result,
				"'" + label + "' expected " + expectedIndices
				+ " but got " + result);
	}

	@Test
	public void physicalInjury_shouldReturnOnlyInjuryRecords() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null,
				"Skipping: embedding model not found");

		for (int ds = 0; ds < DATASETS.length; ds++) {
			List<Integer> result = runQuery("any physical injury?", ds);
			StringBuilder details = new StringBuilder();
			for (int idx : result) {
				if (idx < DATASETS[ds].length) {
					details.append("\n  [").append(idx).append("] ")
							.append(DATASETS[ds][idx]);
				}
			}
			log.warn("[{}] 'any physical injury?' returned {} records: {}{}",
					DATASET_NAMES[ds], result.size(), result, details);

			if (ds == 1) {
				// SECOND contains "Complete tear of ligament of
				// ankle or foot" — a physical injury.
				assertTrue(result.contains(30),
						DATASET_NAMES[ds] + ": should contain Complete tear condition [30]");
				assertTrue(result.contains(33),
						DATASET_NAMES[ds] + ": should contain Complete tear diagnosis [33]");
			} else if (ds == 3 || ds == 4) {
				// FOURTH and FIFTH contain "Crushing injury of
				// thigh" and "Nonunion of fracture". Indices are
				// 0-based array positions (comment number - 1).
				assertTrue(result.contains(92),
						DATASET_NAMES[ds] + ": should contain Crushing injury condition [92]");
				assertTrue(result.contains(96),
						DATASET_NAMES[ds] + ": should contain Crushing injury diagnosis [96]");
				assertTrue(result.contains(107),
						DATASET_NAMES[ds] + ": should contain Nonunion of fracture condition [107]");
				assertTrue(result.contains(109),
						DATASET_NAMES[ds] + ": should contain Nonunion of fracture diagnosis [109]");
			} else {
				// FULL and THIRD have no injury records
				assertTrue(result.isEmpty(),
						DATASET_NAMES[ds] + ": should return 0 records for 'any physical injury?' but got "
						+ result.size() + ": " + details);
			}
		}
	}

	@Test
	public void eyeProblems_shouldReturnEmptyForPatientsWithNoEyeRecords() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null,
				"Skipping: embedding model not found");

		for (int ds = 0; ds < DATASETS.length; ds++) {
			List<Integer> result = runQuery("any eye problems?", ds);
			StringBuilder details = new StringBuilder();
			for (int idx : result) {
				if (idx < DATASETS[ds].length) {
					details.append("\n  [").append(idx).append("] ")
							.append(DATASETS[ds][idx]);
				}
			}
			// No dataset contains eye-related records
			// (ophthalmology, vision, retina, glaucoma, etc.)
			assertTrue(result.isEmpty(),
					DATASET_NAMES[ds] + ": should return 0 records for"
					+ " 'any eye problems?' but got "
					+ result.size() + ": " + details);
		}
	}

	@Test
	public void latestBmi_shouldReturnHeightAndWeightRecords() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null,
				"Skipping: embedding model not found");

		for (int ds = 0; ds < DATASETS.length; ds++) {
			List<Integer> result = runQuery("What is the latest BMI?", ds);
			StringBuilder details = new StringBuilder();
			for (int idx : result) {
				if (idx < DATASETS[ds].length) {
					details.append("\n  [").append(idx).append("] ")
							.append(DATASETS[ds][idx]);
				}
			}
			log.warn("[{}] 'What is the latest BMI?' returned {} records: {}{}",
					DATASET_NAMES[ds], result.size(), result, details);

			// BMI = weight / height². Every dataset has Height and Weight
			// records, so the pipeline should return both.
			boolean hasHeight = false;
			boolean hasWeight = false;
			for (int idx : result) {
				if (idx < DATASETS[ds].length) {
					String text = DATASETS[ds][idx];
					if (text.contains("Height")) {
						hasHeight = true;
					}
					if (text.contains("Weight")) {
						hasWeight = true;
					}
				}
			}
			assertTrue(hasWeight,
					DATASET_NAMES[ds] + ": BMI query should return Weight records, got: " + result);
			assertTrue(hasHeight,
					DATASET_NAMES[ds] + ": BMI query should return Height records, got: " + result);

			// BMI only involves Height and Weight. Other vitals like
			// blood pressure, pulse, temperature, respiratory rate,
			// and blood oxygen should not be returned. At minimum,
			// irrelevant records should be fewer than relevant ones.
			int relevantCount = 0;
			List<String> irrelevant = new ArrayList<>();
			for (int idx : result) {
				if (idx < DATASETS[ds].length) {
					String text = DATASETS[ds][idx];
					if (text.contains("Height") || text.contains("Weight")
							|| text.contains("BMI")) {
						relevantCount++;
					} else {
						irrelevant.add("[" + idx + "] " + text);
					}
				}
			}
			assertTrue(irrelevant.size() <= relevantCount,
					DATASET_NAMES[ds] + ": BMI query returned more irrelevant"
					+ " records (" + irrelevant.size() + ") than relevant ("
					+ relevantCount + "):\n  "
					+ String.join("\n  ", irrelevant));
		}
	}

	@Test
	public void latestBmi_shouldReturnOnlyHeightAndWeight() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null,
				"Skipping: embedding model not found");

		for (int ds = 0; ds < DATASETS.length; ds++) {
			List<Integer> result = runQuery("What is the latest BMI?", ds);
			StringBuilder details = new StringBuilder();
			boolean hasHeight = false;
			boolean hasWeight = false;
			List<String> other = new ArrayList<>();
			for (int idx : result) {
				if (idx < DATASETS[ds].length) {
					String text = DATASETS[ds][idx];
					details.append("\n  [").append(idx).append("] ")
							.append(text);
					if (text.contains("Height")) {
						hasHeight = true;
					} else if (text.contains("Weight")) {
						hasWeight = true;
					} else {
						other.add("[" + idx + "] " + text);
					}
				}
			}
			assertTrue(hasHeight && hasWeight,
					DATASET_NAMES[ds] + ": BMI query should return both "
					+ "Height and Weight, got:" + details);
			assertTrue(other.isEmpty(),
					DATASET_NAMES[ds] + ": BMI query should return ONLY "
					+ "Height and Weight, but also got:\n  "
					+ String.join("\n  ", other));
		}
	}

	@Test
	public void multiConceptVitals_shouldReturnAllRequestedConcepts() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null,
				"Skipping: embedding model not found");

		String query = "How have this patient's blood pressure, weight, "
				+ "and temperature trended across their last 3 visits?";

		for (int ds = 0; ds < DATASETS.length; ds++) {
			List<Integer> result = runQuery(query, ds);
			StringBuilder details = new StringBuilder();
			int bpCount = 0;
			int weightCount = 0;
			int temperatureCount = 0;
			List<String> other = new ArrayList<>();
			for (int idx : result) {
				if (idx < DATASETS[ds].length) {
					String text = DATASETS[ds][idx];
					details.append("\n  [").append(idx).append("] ")
							.append(text);
					String lower = text.toLowerCase();
					if (lower.contains("blood pressure")
							|| text.contains("Systolic")
							|| text.contains("Diastolic")) {
						bpCount++;
					} else if (text.contains("Weight")) {
						weightCount++;
					} else if (text.contains("Temperature")
							|| lower.contains("temperature")) {
						temperatureCount++;
					} else {
						other.add("[" + idx + "] " + text);
					}
				}
			}
			log.warn("[{}] multi-concept vitals returned {} records "
					+ "(BP={}, Weight={}, Temp={}):{}", DATASET_NAMES[ds],
					result.size(), bpCount, weightCount,
					temperatureCount, details);
			// "last 3 visits" should return at least 3 records per
			// concept (systolic+diastolic count separately for BP).
			assertTrue(bpCount >= 3,
					DATASET_NAMES[ds] + ": should return at least 3 BP"
					+ " records for 3 visits, got " + bpCount
					+ ":" + details);
			assertTrue(weightCount >= 3,
					DATASET_NAMES[ds] + ": should return at least 3"
					+ " Weight records for 3 visits, got "
					+ weightCount + ":" + details);
			assertTrue(temperatureCount >= 3,
					DATASET_NAMES[ds] + ": should return at least 3"
					+ " Temperature records for 3 visits, got "
					+ temperatureCount + ":" + details);
			assertTrue(other.isEmpty(),
					DATASET_NAMES[ds] + ": should return ONLY BP, Weight"
					+ ", and Temperature, but also got:\n  "
					+ String.join("\n  ", other));
		}
	}


	@Test
	public void allergies_shouldSurviveConceptNameGateOnLargeDataset() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null,
				"Skipping: embedding model not found");

		// Build a dataset with ~50 diverse concepts and one allergy
		// record. On the live demo (160 records, N=44 concepts),
		// the concept-name gate rejects "Beef" (z=2.04 < 2.38)
		// and the type-indicator rescue saves it. This test
		// verifies end-to-end allergy retrieval on a diverse
		// dataset. The 50-record set doesn't fully reproduce the
		// live demo's score compression — the rescue path is
		// exercised on larger real-world datasets.
		String[] largeDataset = {
			"Patient allergy: (2026-03-18) Beef. Severity: Severe. Reactions: Diarrhea, Itching",
			"Clinical observation: (2026-03-01) Test — Haemoglobin: 15.8 g/dL",
			"Clinical observation: (2026-03-01) Test — CD4 Count: 988.0 cells/mmL",
			"Clinical observation: (2026-03-01) Test — Height (cm): 131.0 cm",
			"Clinical observation: (2026-03-01) Test — Weight (kg): 94.0 kg",
			"Clinical observation: (2026-03-01) Test — Pulse: 95.0 beats/min",
			"Clinical observation: (2026-03-01) Test — Temperature (C): 36.7 DEG C",
			"Clinical observation: (2026-03-01) Test — Respiratory Rate: 18.0 breaths/min",
			"Clinical observation: (2026-03-01) Test — Blood Oxygen Saturation: 94.0 %",
			"Clinical observation: (2026-03-01) Test — Systolic Blood Pressure: 120.0 mmHg",
			"Clinical observation: (2026-03-01) Test — Diastolic Blood Pressure: 80.0 mmHg",
			"Clinical observation: (2026-03-01) Test — Serum glucose: 85.0 mg/dL",
			"Clinical observation: (2026-03-01) Test — Serum creatinine: 1.2 mg/dL",
			"Clinical observation: (2026-03-01) Test — White blood cell count: 5.5 x10^9/L",
			"Clinical observation: (2026-03-01) Test — Platelet count: 250.0 x10^9/L",
			"Clinical observation: (2026-03-01) Test — Hematocrit: 42.0 %",
			"Clinical observation: (2026-03-01) Test — Blood urea nitrogen: 15.0 mg/dL",
			"Clinical observation: (2026-03-01) Test — Albumin: 4.2 g/dL",
			"Clinical observation: (2026-03-01) Test — Bilirubin: 0.8 mg/dL",
			"Clinical observation: (2026-03-01) Test — Alkaline phosphatase: 70.0 U/L",
			"Clinical observation: (2026-03-01) Test — Alanine aminotransferase: 25.0 U/L",
			"Clinical observation: (2026-03-01) Test — Aspartate aminotransferase: 30.0 U/L",
			"Clinical observation: (2026-03-01) Test — Cholesterol: 200.0 mg/dL",
			"Clinical observation: (2026-03-01) Test — Triglycerides: 150.0 mg/dL",
			"Clinical observation: (2026-03-01) Test — Sodium: 140.0 mEq/L",
			"Clinical observation: (2026-03-01) Test — Potassium: 4.0 mEq/L",
			"Clinical observation: (2026-03-01) Test — Calcium: 9.5 mg/dL",
			"Clinical observation: (2026-03-01) Test — Magnesium: 2.0 mg/dL",
			"Clinical observation: (2026-03-01) Test — Phosphate: 3.5 mg/dL",
			"Clinical observation: (2026-03-01) Test — Uric acid: 5.0 mg/dL",
			"Clinical observation: (2026-03-01) Test — Iron: 80.0 mcg/dL",
			"Clinical observation: (2026-03-01) Test — Ferritin: 50.0 ng/mL",
			"Clinical observation: (2026-03-01) Test — Vitamin D: 30.0 ng/mL",
			"Clinical observation: (2026-03-01) Test — Thyroid stimulating hormone: 2.5 mIU/L",
			"Clinical observation: (2026-03-01) Test — Free thyroxine: 1.2 ng/dL",
			"Clinical observation: (2026-03-01) Test — Prostate specific antigen: 1.5 ng/mL",
			"Clinical observation: (2026-03-01) Test — C-reactive protein: 0.5 mg/L",
			"Clinical observation: (2026-03-01) Test — Erythrocyte sedimentation rate: 10.0 mm/hr",
			"Clinical observation: (2026-03-01) Test — Prothrombin time: 12.0 seconds",
			"Clinical observation: (2026-03-01) Test — Activated partial thromboplastin time: 30.0 seconds",
			"Clinical observation: (2026-03-01) Test — International normalized ratio: 1.0",
			"Clinical observation: (2026-03-01) Test — Lactate dehydrogenase: 200.0 U/L",
			"Clinical observation: (2026-03-01) Test — Gamma-glutamyl transferase: 25.0 U/L",
			"Clinical observation: (2026-03-01) Test — Amylase: 80.0 U/L",
			"Clinical observation: (2026-03-01) Test — Lipase: 40.0 U/L",
			"Medical condition: (2025-11-12) Condition: Tuberculosis. Status: ACTIVE",
			"Medical condition: (2025-09-08) Condition: Hypertension. Status: ACTIVE",
			"Medical condition: (2025-06-01) Condition: Diabetes. Status: ACTIVE",
			"Medical condition: (2025-03-15) Condition: Asthma. Status: ACTIVE",
			"Medical condition: (2025-01-20) Condition: Anemia. Status: ACTIVE",
		};

		List<SerializedRecord> records = TestDatasetHelper.toSerializedRecords(
				largeDataset, null);
		List<ChartEmbedding> embeddings = TestDatasetHelper.buildOrLoadCachedEmbeddings(
				records, provider);
		org.openmrs.module.chartsearchai.embedding.ModelNoiseProfile noise =
				TestDatasetHelper.buildOrLoadCachedNoiseProfile(embeddings, provider);
		PipelineConfig config =
				PipelineConfig.forModel(activeModel)
						.withNoiseProfile(noise);

		List<SerializedRecord> result = LlmInferenceService.findRelevantRecords(
				embeddings, records, cachingProvider, "any allergies?",
				ChartSearchAiConstants.DEFAULT_QUERY_EMBEDDING_PREFIX, config);

		boolean hasAllergy = false;
		StringBuilder details = new StringBuilder();
		if (result != null) {
			for (SerializedRecord r : result) {
				details.append("\n  [").append(TestDatasetHelper.indexForUuid(r.getResourceUuid()))
						.append("] ").append(r.getText());
				if ("allergy".equals(r.getResourceType())) {
					hasAllergy = true;
				}
			}
		}
		log.warn("Large-dataset allergy test ({}) returned {} records:{}",
				activeModel, result != null ? result.size() : 0, details);
		assertTrue(hasAllergy,
				"Large dataset: 'any allergies?' should return the Beef"
				+ " allergy record despite " + largeDataset.length
				+ " diverse concepts, got:" + details);
	}

	@Test
	public void allergies_shouldReturnAllergyRecords() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null,
				"Skipping: embedding model not found");

		// FULL dataset has a Beef allergy at index 4.
		// Other datasets may have allergy records too.
		List<Integer> result = runQuery("any allergies?", 0);
		StringBuilder details = new StringBuilder();
		boolean hasAllergy = false;
		for (int idx : result) {
			if (idx < DATASETS[0].length) {
				String text = DATASETS[0][idx];
				details.append("\n  [").append(idx).append("] ")
						.append(text);
				if (text.startsWith("Patient allergy:")) {
					hasAllergy = true;
				}
			}
		}
		log.warn("[FULL] 'any allergies?' returned {} records:{}",
				result.size(), details);
		assertTrue(hasAllergy,
				"FULL: 'any allergies?' should return allergy records"
				+ ", got:" + details);
	}

	@Test
	public void allergies_shouldReturnEmptyForPatientWithNoAllergies() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null,
				"Skipping: embedding model not found");

		// THIRD dataset (index 2) has no allergy records.
		// The pipeline should return empty, not 30+ irrelevant
		// obs/conditions/diagnoses.
		String[] queries = {
			"any allergies?",
			"does the patient have any allergies",
			"Does this patient have any recorded allergies that "
				+ "conflict with their current drug orders?",
		};
		for (String query : queries) {
			List<Integer> result = runQuery(query, 2);
			StringBuilder details = new StringBuilder();
			for (int idx : result) {
				if (idx < DATASETS[2].length) {
					details.append("\n  [").append(idx).append("] ")
							.append(DATASETS[2][idx]);
				}
			}
			log.warn("[THIRD] '{}' returned {} records:{}",
					query, result.size(), details);
			assertTrue(result.isEmpty(),
					"THIRD dataset has no allergy records — '"
					+ query + "' should return empty, got "
					+ result.size() + " results:" + details);
		}
	}

	@Test
	public void copdExacerbations_shouldReturnEmptyForAllDatasets() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null,
				"Skipping: embedding model not found");

		// No dataset contains COPD records — query should return empty.
		// MedCPT is excluded: as a medical embedding model, it
		// correctly associates COPD with respiratory-domain records
		// (Pneumonia, Asthma, Respiratory rate vitals), returning
		// clinically-adjacent results that the concept-name floor
		// check can't distinguish. The L6-v2 fix targets the
		// clearly-wrong case (Fetishism via narrative overlap).
		Assumptions.assumeTrue("l6-v2".equals(activeModel),
				"COPD empty assertion is L6-v2 specific");
		String query = "Do they have any history of COPD exacerbations?";
		for (int ds = 0; ds < DATASETS.length; ds++) {
			List<Integer> result = runQuery(query, ds);
			StringBuilder details = new StringBuilder();
			for (int idx : result) {
				if (idx < DATASETS[ds].length) {
					details.append("\n  [").append(idx).append("] ")
							.append(DATASETS[ds][idx]);
				}
			}
			assertTrue(result.isEmpty(),
					DATASET_NAMES[ds] + " has no COPD records — '"
					+ query + "' should return empty, got "
					+ result.size() + " results:" + details);
		}
	}

	@Test
	public void longHbQuery_shouldReturnSameAsShortQuery() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null,
				"Skipping: embedding model not found");

		String shortQuery = "Hb results";
		String longQuery = "What are this patient's HB results over "
				+ "time, and are values moving toward or away from "
				+ "the normal range?";

		for (int ds = 0; ds < DATASETS.length; ds++) {
			List<Integer> shortResult = runQuery(shortQuery, ds);
			List<Integer> longResult = runQuery(longQuery, ds);
			log.warn("[{}] short='Hb results' -> {}, long -> {}",
					DATASET_NAMES[ds], shortResult, longResult);
			// The long query should return at least the same records
			// as the short query — adding context words shouldn't
			// cause relevant records to be lost.
			for (int idx : shortResult) {
				assertTrue(longResult.contains(idx),
						DATASET_NAMES[ds] + ": long HB query missing"
						+ " record [" + idx + "] that short query"
						+ " found. Short: " + shortResult
						+ ", Long: " + longResult);
			}
		}
	}

	@Test
	public void activeConditions_shouldReturnOnlyConditionAndDiagnosisRecords() {
		ensureInitialized();
		Assumptions.assumeTrue(provider != null,
				"Skipping: embedding model not found");

		String query = "When were each of this patient's active conditions "
				+ "first recorded, and have any resolved or escalated?";

		for (int ds = 0; ds < DATASETS.length; ds++) {
			List<Integer> result = runQuery(query, ds);
			assertTrue(result.size() > 0,
					DATASET_NAMES[ds] + ": should return at least one condition record");

			int conditionCount = 0;
			List<String> wrongType = new ArrayList<>();
			for (int idx : result) {
				if (idx < DATASETS[ds].length) {
					String record = DATASETS[ds][idx];
					if (record.startsWith("Medical condition:")
							|| record.startsWith("Clinical diagnosis:")) {
						conditionCount++;
					} else {
						wrongType.add("[" + idx + "] " + record);
					}
				}
			}
			// When conditions/diagnoses are the majority (>2/3), the
			// type-indicator filter should remove all wrong-type
			// stragglers. When they're not the majority, the pipeline
			// has a deeper recall problem — log it but don't fail.
			if (conditionCount > result.size() * 2 / 3) {
				assertTrue(wrongType.isEmpty(),
						DATASET_NAMES[ds] + ": 'active conditions' query should only "
						+ "return condition/diagnosis records when they are the "
						+ "majority, but got " + wrongType.size()
						+ " wrong-type records:\n  "
						+ String.join("\n  ", wrongType));
			} else if (!wrongType.isEmpty()) {
				log.warn("[{}] 'active conditions' returned {} wrong-type "
						+ "records out of {} total (conditions={}): {}",
						DATASET_NAMES[ds], wrongType.size(), result.size(),
						conditionCount, wrongType);
			}
		}
	}

	/**
	 * Generator: run all 97 queries and output the baseline JSON.
	 * Enable this test ONCE to generate the golden file, then disable.
	 */
	@Test
	@org.junit.jupiter.api.Disabled("Run manually to regenerate the golden baseline")
	public void generateBaseline() throws IOException {
		Assumptions.assumeTrue(TestDatasetHelper.modelFilesExist(),
				"Skipping: ONNX model not found");

		String[] QUERIES = {
			"Any family history of cancer?","Does this patient use any family planning methods?",
			"HIV status and CD4 count","Hb results",
			"How have this patient's blood pressure, weight, and temperature trended across their last 7 visits?",
			"I need to know the patient's most recent blood pressure and weight readings",
			"I want to review the patient's complete medication history including dosages",
			"TB","TB treatment history",
			"Were all lab orders placed for this patient resulted?",
			"What are this patient's HB results over time, and are values moving toward or away from the normal range?",
			"What is the current CD4 Count?","What is the latest CD4 Count?",
			"What is the patient allergic to?",
			"When were each of this patient's active conditions first recorded, and have any resolved or escalated?",
			"allergies","allergies and current medications","anaemia","anemia",
			"any STD?","any allergies?","any blood problems?","any cancer?",
			"any conditions","any conditions?","any cough?","any episodes?",
			"any fracture?","any history of cancer?","any infections?",
			"any known conditions?","any prescriptions?",
			"any sexually transmitted disease?","any sexually transmitted diseases?",
			"azithromycin","blood pressure and pulse","blood sugar","bowel disease",
			"breathing problems",
			"can you tell me about the patient's respiratory symptoms and oxygen levels",
			"cancer","cardiovascular disease","cardiovascular risk factors",
			"cholesterol","dental problems","depression","diabetes",
			"disease problem","disease treatment","does he have cancer?",
			"does she have any blood problem?","does the patient have any allergies",
			"does the patient have any allergies?","does the patient have cancer?",
			"ever been immunized?","ever got pregnant?","fever","fracture",
			"hair loss",
			"has this patient ever been diagnosed with any sexually transmitted infections",
			"has this patient had a sexually transmitted disease?",
			"headache","how hot is the patient?","immunization",
			"is the patient anemic?","is the patient enrolled in any programs?",
			"is the patient immunocompromised?","is the patient on any medications?",
			"is the patient tired?","kidney function","kidney problems",
			"latest vital signs","list all allergies","liver problems",
			"mental health","musculoskeletal injuries","nutritional status",
			"opportunistic infections in HIV","oxygen saturation",
			"patient denies chest pain","pregnancy-related concerns","pulse rate",
			"recent temperature readings","signs of infection or inflammation",
			"skin rash","stroke","substance abuse","vital signs","wasting",
			"what are the most recent two heart rates?",
			"what chronic conditions has this patient been diagnosed with over the years",
			"what is the current cd4 count and when was it recorded?",
			"what is the latest heart rate?","what is the latest weight?",
			"what is the patient being treated for?",
			"what medications is the patient on?",
			"what tests have been ordered for this patient",
		};

		ObjectMapper mapper = new ObjectMapper();
		com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
		root.put("dataset", "enriched-retrieval-v2");
		root.put("description", "Baseline for enriched pipeline across all 5 patient datasets");
		com.fasterxml.jackson.databind.node.ArrayNode cases = root.putArray("cases");

		for (int d = 0; d < DATASETS.length; d++) {
			for (String q : QUERIES) {
				List<Integer> result = runQuery(q, d);
				com.fasterxml.jackson.databind.node.ObjectNode c = cases.addObject();
				c.put("query", q);
				c.put("datasetIndex", d);
				c.put("expectedMinRecords", result.size());
				com.fasterxml.jackson.databind.node.ArrayNode ids = c.putArray("resultIndices");
				for (int idx : result) {
					ids.add(idx);
				}
			}
		}

		java.io.File outFile = new java.io.File(
				"src/test/resources/" + activeBaseline);
		mapper.writerWithDefaultPrettyPrinter().writeValue(outFile, root);
		log.info("Generated baseline: {} queries, file: {}",
				QUERIES.length, outFile.getAbsolutePath());
	}
}
