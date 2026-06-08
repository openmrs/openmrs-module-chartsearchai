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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.embedding.EmbeddingProvider;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Unit tests for {@link CitationGroundingVerifier}. Uses a deterministic stub
 * {@link EmbeddingProvider} (each registered phrase maps to a fixed unit
 * vector) and a stub {@link LlmProvider} (programmed yes/no verdicts) so both
 * tiers of grounding are exercised without ONNX, an LLM, or an OpenMRS context.
 */
public class CitationGroundingVerifierTest {

	private static final double FLOOR = 0.40;

	private static final boolean TIER1_ONLY = false;

	private static final boolean TIER2_ON = true;

	/**
	 * Maps exact strings to fixed vectors. Cosine between two registered strings
	 * is just the dot product of their (unit) vectors. Unregistered text gets a
	 * zero vector, which yields cosine 0 against everything — i.e. "no overlap".
	 */
	private static class StubEmbeddingProvider implements EmbeddingProvider {

		private final Map<String, float[]> vectors = new HashMap<String, float[]>();

		void register(String text, float[] vector) {
			vectors.put(text, vector);
		}

		@Override
		public float[] embed(String text) {
			float[] v = vectors.get(text);
			return v != null ? v : new float[] { 0f, 0f };
		}

		@Override
		public int getDimensions() {
			return 2;
		}
	}

	/** A LlmProvider whose {@link #entails} returns a programmed verdict and counts calls. */
	private static class StubLlmProvider extends LlmProvider {

		Boolean verdict;

		int calls;

		StubLlmProvider(Boolean verdict) {
			this.verdict = verdict;
		}

		@Override
		public Boolean entails(String source, String statement) {
			calls++;
			return verdict;
		}
	}

	private StubEmbeddingProvider embeddings;

	private StubLlmProvider llm;

	private CitationGroundingVerifier verifier;

	private static final float[] AXIS_A = { 1f, 0f };

	private static final float[] AXIS_B = { 0f, 1f };

	@BeforeEach
	public void setUp() {
		embeddings = new StubEmbeddingProvider();
		llm = new StubLlmProvider(null);
		verifier = new CitationGroundingVerifier();
		verifier.setEmbeddingProvider(embeddings);
		verifier.setLlmProvider(llm);
	}

	private static RecordMapping mapping(int index, String text) {
		return new RecordMapping(index, "obs", "uuid-" + index, new Date(), text);
	}

	private static RecordReference reference(int index) {
		return new RecordReference(index, "obs", "uuid-" + index, new Date());
	}

	// ---- Tier-1 (cosine) ----

	@Test
	public void verify_marksOnTopicCitationGrounded() {
		String sentence = "Patient has diabetes [1].";
		String record = "Type 2 diabetes mellitus";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_A); // identical direction -> cosine 1.0

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(1))),
				Arrays.asList(mapping(1, record)), FLOOR, TIER1_ONLY);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded());
	}

	@Test
	public void verify_flagsOffTopicCitationUngrounded() {
		String sentence = "Patient has diabetes [1].";
		String record = "Blood pressure 120/80 mmHg";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_B); // orthogonal -> cosine 0.0

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(1))),
				Arrays.asList(mapping(1, record)), FLOOR, TIER1_ONLY);

		assertEquals(Boolean.FALSE, result.get(0).getGrounded());
	}

	@Test
	public void verify_perSentenceCitationIsScoredAgainstItsOwnSentence() {
		String s1 = "Patient has diabetes [1].";
		String s2 = "Blood pressure is elevated [2].";
		String answer = s1 + " " + s2;
		embeddings.register(s1, AXIS_A);
		embeddings.register(s2, AXIS_B);
		embeddings.register("diabetes mellitus", AXIS_A);
		embeddings.register("BP 150/95", AXIS_B);

		List<RecordReference> result = verifier.verify(answer,
				new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(2))),
				Arrays.asList(mapping(1, "diabetes mellitus"), mapping(2, "BP 150/95")),
				FLOOR, TIER1_ONLY);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded());
		assertEquals(Boolean.TRUE, result.get(1).getGrounded());
	}

	@Test
	public void verify_negativeCosineIsUngroundedNotUnverified() {
		// A record pointing the opposite way in embedding space (cosine -1) is the
		// strongest "not grounded" signal — it must be FALSE, never null.
		String sentence = "Patient has diabetes [1].";
		String record = "completely unrelated";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, new float[] { -1f, 0f }); // cosine -1.0

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(1))),
				Arrays.asList(mapping(1, record)), FLOOR, TIER1_ONLY);

		assertEquals(Boolean.FALSE, result.get(0).getGrounded(),
				"negative cosine -> ungrounded (FALSE), not unverified (null)");
	}

	@Test
	public void verify_splitsClaimsOnNewlines() {
		// Newline-structured answer with NO terminal punctuation. If the splitter
		// did not break on newlines, the whole string (unregistered -> zero vector)
		// would score 0 for both records and both would be FALSE. Splitting per
		// line isolates each claim so each matches its own record.
		String answer = "Diabetes [1]\nHypertension [2]";
		embeddings.register("Diabetes [1]", AXIS_A);
		embeddings.register("Hypertension [2]", AXIS_B);
		embeddings.register("diabetes mellitus", AXIS_A);
		embeddings.register("essential hypertension", AXIS_B);

		List<RecordReference> result = verifier.verify(answer,
				new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(2))),
				Arrays.asList(mapping(1, "diabetes mellitus"), mapping(2, "essential hypertension")),
				FLOOR, TIER1_ONLY);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(), "claim on line 1 matched record 1");
		assertEquals(Boolean.TRUE, result.get(1).getGrounded(), "claim on line 2 matched record 2");
	}

	@Test
	public void verify_recordWithNoTextIsLeftUnverified() {
		List<RecordReference> result = verifier.verify("Patient has diabetes [1].",
				new ArrayList<RecordReference>(Arrays.asList(reference(1))),
				Arrays.asList(mapping(1, null)), FLOOR, TIER1_ONLY);

		assertNull(result.get(0).getGrounded(), "no source text -> cannot verify -> null verdict");
	}

	@Test
	public void verify_embeddingFailureDegradesToUnverified() {
		EmbeddingProvider throwing = new EmbeddingProvider() {

			@Override
			public float[] embed(String text) {
				throw new RuntimeException("ONNX session unavailable");
			}

			@Override
			public int getDimensions() {
				return 2;
			}
		};
		verifier.setEmbeddingProvider(throwing);

		List<RecordReference> result = verifier.verify("Patient has diabetes [1].",
				new ArrayList<RecordReference>(Arrays.asList(reference(1))),
				Arrays.asList(mapping(1, "diabetes mellitus")), FLOOR, TIER1_ONLY);

		assertNull(result.get(0).getGrounded(), "verifier must never break the search path");
	}

	@Test
	public void verify_emptyReferencesReturnedUnchanged() {
		List<RecordReference> empty = new ArrayList<RecordReference>();
		assertTrue(verifier.verify("anything", empty,
				new ArrayList<RecordMapping>(), FLOOR, TIER1_ONLY).isEmpty());
	}

	// ---- Tier-2 (LLM entailment) ----

	@Test
	public void tier2_overridesHighCosineFalsePositive() {
		// The motivating danger case: "patient has cancer [5]" where record 5 is
		// "grandmother had cancer". Cosine is high (same words) so Tier-1 passes,
		// but the LLM entailment correctly says NO.
		String sentence = "Patient has cancer [5].";
		String record = "Patient reports grandmother had cancer";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_A); // high cosine -> Tier-1 would pass
		llm.verdict = Boolean.FALSE;

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(5))),
				Arrays.asList(mapping(5, record)), FLOOR, TIER2_ON);

		assertEquals(Boolean.FALSE, result.get(0).getGrounded(), "entailment must override Tier-1 pass");
		assertEquals(1, llm.calls);
	}

	@Test
	public void tier2_rescuesLowCosineButSupportedClaim() {
		// True claim phrased very differently from the record -> low cosine
		// (Tier-1 would flag it), but the LLM confirms support.
		String sentence = "Glucose control is poor [1].";
		String record = "HbA1c 11.2 percent";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_B); // orthogonal -> Tier-1 would fail
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(1))),
				Arrays.asList(mapping(1, record)), FLOOR, TIER2_ON);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(), "entailment must rescue Tier-1 fail");
	}

	@Test
	public void tier2_keepsTier1WhenLlmCannotDecide() {
		String sentence = "Patient has diabetes [1].";
		String record = "Type 2 diabetes mellitus";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_A); // Tier-1 TRUE
		llm.verdict = null; // LLM gave an unparseable answer

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(1))),
				Arrays.asList(mapping(1, record)), FLOOR, TIER2_ON);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(), "null entailment -> fall back to Tier-1");
	}

	@Test
	public void tier2_llmFailureDegradesToTier1() {
		StubLlmProvider throwing = new StubLlmProvider(null) {

			@Override
			public Boolean entails(String source, String statement) {
				throw new RuntimeException("llama-server timed out");
			}
		};
		verifier.setLlmProvider(throwing);
		String sentence = "Patient has diabetes [1].";
		String record = "Type 2 diabetes mellitus";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_A); // Tier-1 TRUE

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(1))),
				Arrays.asList(mapping(1, record)), FLOOR, TIER2_ON);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(), "entailment failure -> keep Tier-1");
	}

	@Test
	public void tier2_disabledMakesNoLlmCalls() {
		String sentence = "Patient has cancer [5].";
		String record = "Patient reports grandmother had cancer";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_A);
		llm.verdict = Boolean.FALSE;

		verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(5))),
				Arrays.asList(mapping(5, record)), FLOOR, TIER1_ONLY);

		assertEquals(0, llm.calls, "Tier-2 must not call the LLM when disabled");
	}

	@Test
	public void tier2_isCappedPerAnswer() {
		// Build more cited references than the cap. With unregistered embeddings
		// every Tier-1 verdict is FALSE (cosine 0); the stub entailment returns
		// TRUE, so references that got a Tier-2 call flip to TRUE while those
		// beyond the cap keep their Tier-1 FALSE.
		int cap = ChartSearchAiConstants.GROUNDING_ENTAILMENT_MAX_CHECKS;
		int total = cap + 2;
		StringBuilder answer = new StringBuilder();
		List<RecordReference> refs = new ArrayList<RecordReference>();
		List<RecordMapping> maps = new ArrayList<RecordMapping>();
		for (int i = 1; i <= total; i++) {
			answer.append("claim ").append(i).append(" [").append(i).append("]. ");
			refs.add(reference(i));
			maps.add(mapping(i, "record " + i));
		}
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(answer.toString(), refs, maps, FLOOR, TIER2_ON);

		assertEquals(cap, llm.calls, "Tier-2 calls must be capped");
		assertEquals(Boolean.TRUE, result.get(0).getGrounded(), "within cap -> entailment applied");
		assertEquals(Boolean.FALSE, result.get(total - 1).getGrounded(),
				"beyond cap -> keeps Tier-1 verdict");
	}

	// ---- helpers ----

	@Test
	public void stripCitationMarkers_removesBracketsLeavingTheClaim() {
		assertEquals("Patient has diabetes .",
				CitationGroundingVerifier.stripCitationMarkers("Patient has diabetes [1]."));
		assertEquals("BP is high",
				CitationGroundingVerifier.stripCitationMarkers("BP is high [2][3]"));
		assertEquals("no markers here",
				CitationGroundingVerifier.stripCitationMarkers("no markers here"));
	}

	@Test
	public void splitIntoCitedSentences_recordsInlineCitations() {
		List<CitationGroundingVerifier.Sentence> sentences =
				CitationGroundingVerifier.splitIntoCitedSentences(
						"Patient has diabetes [1]. BP is high [2][3].");

		assertEquals(2, sentences.size());
		assertTrue(sentences.get(0).cites(1));
		assertTrue(sentences.get(1).cites(2));
		assertTrue(sentences.get(1).cites(3));
	}
}
