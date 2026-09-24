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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.impl.CitationGroundingVerifier.TextEmbedder;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Unit tests for {@link CitationGroundingVerifier}. Injects a deterministic stub
 * {@link TextEmbedder} via an overridden {@link CitationGroundingVerifier#resolveEmbedder()}
 * (each registered phrase maps to a fixed unit vector) and a stub {@link LlmProvider}
 * (programmed yes/no verdicts) so both tiers of grounding are exercised without an
 * embedding model, an LLM, or an OpenMRS context.
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
	private static class StubEmbedder implements TextEmbedder {

		private final Map<String, float[]> vectors = new HashMap<String, float[]>();

		/** Number of embed() invocations — lets tests pin how much Tier-1 embedding work ran. */
		int embedCalls;

		void register(String text, float[] vector) {
			vectors.put(text, vector);
		}

		@Override
		public float[] embed(String text) {
			embedCalls++;
			float[] v = vectors.get(text);
			return v != null ? v : new float[] { 0f, 0f };
		}
	}

	/**
	 * Verifier subclass that injects a test {@link TextEmbedder} through the
	 * {@link CitationGroundingVerifier#resolveEmbedder()} seam (production resolves querystore's
	 * provider via the OpenMRS context). A {@code null} embedder models a deployment with no
	 * Tier-1 embedding model — resolveEmbedder() returns null and Tier-1 cosine checks are skipped.
	 */
	private static class TestableVerifier extends CitationGroundingVerifier {

		private TextEmbedder embedder;

		void setEmbedder(TextEmbedder embedder) {
			this.embedder = embedder;
		}

		@Override
		TextEmbedder resolveEmbedder() {
			return embedder;
		}
	}

	/**
	 * A LlmProvider whose batch entailment returns the programmed verdict for every pair.
	 * {@code calls} counts pairs verified (so the per-citation expectations still read naturally
	 * under batching — N citations verified == {@code calls == N}); {@code batches} counts
	 * {@code entailsBatch} invocations, which must be one per answer.
	 */
	private static class StubLlmProvider extends LlmProvider {

		Boolean verdict;

		int calls;

		int batches;

		/** The statements passed to each {@code entailsBatch} invocation, in call order. Lets a test
		 *  assert how citations are GROUPED into calls (e.g. that two citations of one compound
		 *  sentence are not co-batched, which would let the LLM couple their verdicts). */
		final List<List<String>> statementsPerCall = new ArrayList<List<String>>();

		StubLlmProvider(Boolean verdict) {
			this.verdict = verdict;
		}

		@Override
		public List<Boolean> entailsBatch(List<String> sources, List<String> statements) {
			batches++;
			calls += sources.size();
			statementsPerCall.add(new ArrayList<String>(statements));
			List<Boolean> out = new ArrayList<Boolean>();
			for (int i = 0; i < sources.size(); i++) {
				out.add(verdict);
			}
			return out;
		}
	}

	private StubEmbedder embeddings;

	private StubLlmProvider llm;

	private TestableVerifier verifier;

	private static final float[] AXIS_A = { 1f, 0f };

	private static final float[] AXIS_B = { 0f, 1f };

	@BeforeEach
	public void setUp() {
		embeddings = new StubEmbedder();
		llm = new StubLlmProvider(null);
		verifier = new TestableVerifier();
		verifier.setEmbedder(embeddings);
		verifier.setLlmProvider(llm);
	}

	private static RecordMapping mapping(int index, String text) {
		return new RecordMapping(index, "obs", "uuid-" + index, new Date(), text);
	}

	private static RecordReference reference(int index) {
		return new RecordReference(index, "obs", "uuid-" + index, new Date());
	}

	/** As {@link #reference}, but a citation the MODULE attached rather than one the model emitted —
	 *  issue #305's shape, and the only input that distinguishes the two cases below. */
	private static RecordReference attachedReference(int index) {
		return new RecordReference(index, "obs", "uuid-" + index, new Date(), null, null, 0, true);
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
		TextEmbedder throwing = text -> {
			throw new RuntimeException("ONNX session unavailable");
		};
		verifier.setEmbedder(throwing);

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
			public List<Boolean> entailsBatch(List<String> sources, List<String> statements) {
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

	@Test
	public void tier2_verifiesAllCitationsInOneBatchCall() {
		// The latency fix: every cited reference Tier-2 confirms is checked in ONE entailsBatch
		// call, not one serial LLM call per citation. Three on-topic citations -> a single batch
		// of three pairs, and each reference still gets the batch's verdict.
		String answer = "Diabetes [1]. Hypertension [2]. Asthma [3].";
		embeddings.register("Diabetes [1].", AXIS_A);
		embeddings.register("Hypertension [2].", AXIS_A);
		embeddings.register("Asthma [3].", AXIS_A);
		embeddings.register("type 2 diabetes", AXIS_A);
		embeddings.register("essential hypertension", AXIS_A);
		embeddings.register("mild asthma", AXIS_A);
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(answer,
				new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(2), reference(3))),
				Arrays.asList(mapping(1, "type 2 diabetes"), mapping(2, "essential hypertension"),
						mapping(3, "mild asthma")),
				FLOOR, TIER2_ON);

		assertEquals(1, llm.batches, "all citations must be verified in a single batch call");
		assertEquals(3, llm.calls, "the one batch must carry all three (record, claim) pairs");
		assertEquals(Boolean.TRUE, result.get(0).getGrounded());
		assertEquals(Boolean.TRUE, result.get(1).getGrounded());
		assertEquals(Boolean.TRUE, result.get(2).getGrounded());
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

	@Test
	public void commaBracketValues_areIgnoredBySplitAndPreservedByStrip() {
		// Grounding consumes the POST-normalization answer (LlmAnswerExtractor rewrites
		// corroborated comma shorthand into single-index markers), so a comma bracket that
		// survives to this layer is a clinical value ("[120, 80]") — it must not be scored
		// as citations, and stripCitationMarkers must not delete it from the claim text.
		List<CitationGroundingVerifier.Sentence> sentences =
				CitationGroundingVerifier.splitIntoCitedSentences("BP was [120, 80] at rest [3].");

		assertEquals(1, sentences.size());
		assertTrue(sentences.get(0).cites(3));
		assertFalse(sentences.get(0).cites(120));
		assertFalse(sentences.get(0).cites(80));
		assertEquals("BP was [120, 80] at rest",
				CitationGroundingVerifier.stripCitationMarkers("BP was [120, 80] at rest [3]"));
	}

	// ---- clause-scoped grounding ----

	@Test
	public void splitIntoClauseScopedSentences_cumulativePrefixAttributedToOneCitation() {
		List<CitationGroundingVerifier.Sentence> clauses =
				CitationGroundingVerifier.splitIntoClauseScopedSentences("A condition [1] and a diagnosis [2].");
		assertEquals(2, clauses.size());
		assertEquals("A condition [1]", clauses.get(0).text);
		assertTrue(clauses.get(0).cites(1));
		assertFalse(clauses.get(0).cites(2), "[1]'s clause must not be attributed to [2]");
		// [2]'s clause is the cumulative prefix (keeps the subject) but is attributed to [2] only.
		assertEquals("A condition [1] and a diagnosis [2]", clauses.get(1).text);
		assertTrue(clauses.get(1).cites(2));
		assertFalse(clauses.get(1).cites(1), "[2]'s clause cites only [2] though its text contains [1]");
	}

	@Test
	public void splitIntoClauseScopedSentences_middleMarkerClauseStopsAtItsOwnMarker() {
		// 3+ citations: the MIDDLE marker's clause is the cumulative prefix through ITS OWN marker —
		// it keeps the earlier marker's text (so the subject/first claim is retained for entailment)
		// but must STOP before the later marker, and be attributed to the middle index alone. The
		// 2-citation case cannot catch a "clause runs past its own marker into the next clause" bug
		// because its last marker has no following text to wrongly absorb.
		List<CitationGroundingVerifier.Sentence> clauses =
				CitationGroundingVerifier.splitIntoClauseScopedSentences(
						"Has diabetes [1] and hypertension [2] and cancer [3].");
		assertEquals(3, clauses.size());
		assertEquals("Has diabetes [1]", clauses.get(0).text);
		assertEquals("Has diabetes [1] and hypertension [2]", clauses.get(1).text);
		assertEquals("Has diabetes [1] and hypertension [2] and cancer [3]", clauses.get(2).text);
		// middle clause: cumulative prefix keeps [1]'s text, stops before the later [3], cites only [2].
		assertTrue(clauses.get(1).cites(2));
		assertFalse(clauses.get(1).cites(1), "middle clause keeps [1]'s text but is not attributed to [1]");
		assertFalse(clauses.get(1).cites(3), "middle clause must stop before the later [3]");
	}

	@Test
	public void splitIntoClauseScopedSentences_leavesSingleCitationSentencesUnchanged() {
		List<CitationGroundingVerifier.Sentence> clauses =
				CitationGroundingVerifier.splitIntoClauseScopedSentences("Patient has diabetes [1].");
		assertEquals(1, clauses.size());
		assertTrue(clauses.get(0).cites(1));
	}

	@Test
	public void clauseScoped_groundsFirstCitationAgainstItsClauseNotTheCompoundSentence() {
		// Compound sentence: [1]'s own clause matches its record, but the WHOLE sentence (which also
		// makes a second, different claim cited by [2]) does not — the [89]/[91] scenario. Sentence-
		// scope flags [1] not-grounded; clause-scope grounds it against its clause.
		String answer = "Hearing loss is a condition [1] and a provisional diagnosis [2].";
		embeddings.register(answer, AXIS_B);                       // whole sentence: orthogonal to record 1
		embeddings.register("Hearing loss is a condition [1]", AXIS_A);  // [1]'s clause: aligned to record 1
		embeddings.register("active condition hearing loss", AXIS_A);    // record 1
		embeddings.register("provisional diagnosis hearing loss", AXIS_B);

		List<RecordReference> refs = Arrays.asList(reference(1), reference(2));
		List<RecordMapping> maps = Arrays.asList(
				mapping(1, "active condition hearing loss"), mapping(2, "provisional diagnosis hearing loss"));

		List<RecordReference> sentenceScoped = verifier.verify(answer,
				new ArrayList<RecordReference>(refs), maps, FLOOR, TIER1_ONLY, false);
		assertEquals(Boolean.FALSE, sentenceScoped.get(0).getGrounded(),
				"sentence-scope: [1] vs the whole compound sentence -> not grounded");

		List<RecordReference> clauseScoped = verifier.verify(answer,
				new ArrayList<RecordReference>(refs), maps, FLOOR, TIER1_ONLY, true);
		assertEquals(Boolean.TRUE, clauseScoped.get(0).getGrounded(),
				"clause-scope: [1] vs its own clause -> grounded");
	}

	@Test
	public void clauseScoped_singleCitationSentenceVerdictIsUnchanged() {
		String answer = "Patient has diabetes [1].";
		embeddings.register(answer, AXIS_A);
		embeddings.register("type 2 diabetes mellitus", AXIS_A);
		List<RecordReference> refs = Arrays.asList(reference(1));
		List<RecordMapping> maps = Arrays.asList(mapping(1, "type 2 diabetes mellitus"));

		Boolean sentence = verifier.verify(answer, new ArrayList<RecordReference>(refs), maps,
				FLOOR, TIER1_ONLY, false).get(0).getGrounded();
		Boolean clause = verifier.verify(answer, new ArrayList<RecordReference>(refs), maps,
				FLOOR, TIER1_ONLY, true).get(0).getGrounded();
		assertEquals(Boolean.TRUE, sentence);
		assertEquals(sentence, clause, "single-citation sentence: clause-scope must not change the verdict");
	}

	@Test
	public void clauseScoped_emptyLeadingClauseIsUngroundedNotCrash() {
		// A compound sentence whose FIRST citation has no descriptive text before its marker yields an
		// empty / marker-only clause "[1]". Clause-scope must handle it safely: [1]'s clause embeds to
		// a zero vector -> cosine 0 (< floor) -> NOT grounded, never a NaN/crash and never spurious.
		// (Tier-2's blank-statement skip is pinned separately in LlmProviderTest.entailsBatch_*.)
		String answer = "[1] and hearing loss [2].";
		embeddings.register("active condition", AXIS_A);       // record 1; its clause "[1]" is unregistered -> zero vector
		embeddings.register("provisional diagnosis", AXIS_A);  // record 2
		List<RecordReference> result = verifier.verify(answer,
				new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(2))),
				Arrays.asList(mapping(1, "active condition"), mapping(2, "provisional diagnosis")),
				FLOOR, TIER1_ONLY, true);
		assertEquals(Boolean.FALSE, result.get(0).getGrounded(),
				"empty leading clause -> cosine 0 -> safe under-ground, not a crash or spurious verdict");
	}

	@Test
	public void clauseScoped_doesNotCoBatchTwoCitationsOfTheSameCompoundSentence() {
		// The Tier-2 regression is a batch-COUPLING effect: when [1] and [2] of ONE compound sentence
		// share an entailsBatch call, the LLM's verdict for one bleeds into the other (on the live
		// model, shortening [1]'s clause flipped [2]'s verdict, 3/3). The fix judges each
		// compound-sentence citation in its OWN entailment call, so [1] and [2] must NOT land in the
		// same call; [3], the sole citation of a separate sentence, is unaffected and may stay batched.
		// The real grounding effect needs the live LLM (verified by eval/grounding-scope/); a stub
		// returns a fixed verdict regardless of co-batching, so here we pin the structural contract the
		// stub CAN observe — the call grouping that the coupling depends on.
		String answer = "A condition [1] and a diagnosis [2]. A separate finding [3].";
		embeddings.register("A condition [1]", AXIS_A);
		embeddings.register("A condition [1] and a diagnosis [2]", AXIS_A);
		embeddings.register("A separate finding [3].", AXIS_A);
		embeddings.register("rec1", AXIS_A);
		embeddings.register("rec2", AXIS_A);
		embeddings.register("rec3", AXIS_A);
		llm.verdict = Boolean.TRUE;
		List<RecordReference> refs = Arrays.asList(reference(1), reference(2), reference(3));
		List<RecordMapping> maps = Arrays.asList(mapping(1, "rec1"), mapping(2, "rec2"), mapping(3, "rec3"));

		verifier.verify(answer, new ArrayList<RecordReference>(refs), maps, FLOOR, TIER2_ON, true);

		// Use the production marker-stripper to derive the exact statements the verifier emits.
		String stmt1 = CitationGroundingVerifier.stripCitationMarkers("A condition [1]");
		String stmt2 = CitationGroundingVerifier.stripCitationMarkers("A condition [1] and a diagnosis [2]");
		int call1 = callIndexContaining(stmt1);
		int call2 = callIndexContaining(stmt2);
		assertTrue(call1 >= 0 && call2 >= 0, "both compound-sentence citations must be verified by Tier-2");
		assertFalse(call1 == call2,
				"[1] and [2] from the same compound sentence must be judged in separate entailment calls");
	}

	@Test
	public void clauseScoped_singleCitationSentencesStayInOneBatch() {
		// The other half of the isolate/batch split: sentences that each cite ONE record are left
		// unsplit (isolate=false), so under clause-scope they must all share the SINGLE batched Tier-2
		// call — not fan out into one call apiece. This is the latency win (list-style answers pay no
		// extra calls). A regression that isolated every citation would still ground them correctly but
		// silently do N serial calls; only a call-count assertion catches that.
		String answer = "Has diabetes [1]. Has hypertension [2]. Has asthma [3].";
		embeddings.register("Has diabetes [1].", AXIS_A);
		embeddings.register("Has hypertension [2].", AXIS_A);
		embeddings.register("Has asthma [3].", AXIS_A);
		embeddings.register("rec1", AXIS_A);
		embeddings.register("rec2", AXIS_A);
		embeddings.register("rec3", AXIS_A);
		llm.verdict = Boolean.TRUE;
		verifier.verify(answer,
				new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(2), reference(3))),
				Arrays.asList(mapping(1, "rec1"), mapping(2, "rec2"), mapping(3, "rec3")), FLOOR, TIER2_ON, true);
		assertEquals(1, llm.batches, "single-citation sentences must share ONE batched call, not one per citation");
		assertEquals(3, llm.calls, "all three single-citation pairs belong to that one batch");
	}

	@Test
	public void clauseScoped_isolateCitationAppliesItsSinglePairTier2Verdict() {
		// A compound sentence's citations are Tier-2'd in their own single-pair calls; prove that
		// verdict is actually APPLIED and authoritative. Tier-1 passes (record aligned to its clause),
		// but the single-pair entailment returns NO -> both must come back grounded=false. If the
		// isolate verdict-assembly loop failed to assign, the verdict would wrongly stay Tier-1 (true).
		String answer = "Has diabetes [1] and hypertension [2].";
		embeddings.register("Has diabetes [1]", AXIS_A);                       // [1]'s clause
		embeddings.register("Has diabetes [1] and hypertension [2]", AXIS_A);  // [2]'s clause (cumulative prefix)
		embeddings.register("rec1", AXIS_A);
		embeddings.register("rec2", AXIS_A);
		llm.verdict = Boolean.FALSE; // Tier-2 says NO on each isolate single-pair call
		List<RecordReference> out = verifier.verify(answer,
				new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(2))),
				Arrays.asList(mapping(1, "rec1"), mapping(2, "rec2")), FLOOR, TIER2_ON, true);
		assertEquals(Boolean.FALSE, out.get(0).getGrounded(), "[1]: isolate Tier-2 NO overrides the Tier-1 pass");
		assertEquals(Boolean.FALSE, out.get(1).getGrounded(), "[2]: isolate Tier-2 NO overrides the Tier-1 pass");
		assertEquals(2, llm.batches, "each compound-sentence citation is verified in its own single-pair call");
	}

	// ---- Lazy Tier-1: no embedding work when Tier-2 is authoritative and the claim
	// sentence is unambiguous (CPU-latency fix for the grounding tail) ----

	@Test
	public void tier2_singleCitingSentences_runNoTier1EmbedsWhenTier2Succeeds() {
		// THE grounding-tail latency fix: a list-style answer where every citation has exactly one
		// citing sentence needs no Tier-1 cosine at all — the claim statement is that sentence by
		// definition (argmax over a single candidate), and Tier-2's verdict overrides Tier-1 anyway.
		// On a CPU-only server each embed is a full BERT forward pass (~0.3-1s on e5-base), and a
		// 9-citation answer was paying ~14-18 of them per query for verdicts that were then
		// discarded. Statements must remain byte-identical to the eager path.
		String answer = "Has diabetes [1]. Has hypertension [2].";
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(answer,
				new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(2))),
				Arrays.asList(mapping(1, "type 2 diabetes"), mapping(2, "essential hypertension")),
				FLOOR, TIER2_ON);

		assertEquals(0, embeddings.embedCalls,
				"single-citing-sentence citations must not embed when Tier-2 yields verdicts");
		assertEquals(Boolean.TRUE, result.get(0).getGrounded());
		assertEquals(Boolean.TRUE, result.get(1).getGrounded());
		assertEquals(1, llm.batches, "still one batched Tier-2 call");
		assertEquals(Arrays.asList("Has diabetes .", "Has hypertension ."),
				llm.statementsPerCall.get(0),
				"Tier-2 statements must be the stripped citing sentences, unchanged by laziness");
	}

	@Test
	public void tier2_brokenEmbedder_singleCitingSentenceStillGetsTier2Verdict() {
		// Deliberate behavior improvement pinned as spec: Tier-2 candidacy for an unambiguous
		// claim sentence no longer depends on the Tier-1 embedder being healthy. Previously a
		// broken/absent embedding model silently downgraded ALL grounding to "unverified" even
		// though the authoritative Tier-2 LLM was available; now the LLM verdict still lands.
		TextEmbedder throwing = text -> {
			throw new RuntimeException("ONNX session unavailable");
		};
		verifier.setEmbedder(throwing);
		llm.verdict = Boolean.FALSE;

		List<RecordReference> result = verifier.verify("Patient has cancer [3].",
				new ArrayList<RecordReference>(Arrays.asList(reference(3))),
				Arrays.asList(mapping(3, "grandmother had cancer")), FLOOR, TIER2_ON);

		assertEquals(Boolean.FALSE, result.get(0).getGrounded(),
				"Tier-2 verdict must land even when the Tier-1 embedder is broken");
	}

	@Test
	public void tier2_absentEmbedder_singleCitingSentenceStillGetsTier2Verdict() {
		// The "absent" half of the broken-or-absent claim: a deployment with NO Tier-1 embedding
		// model configured at all (e.g. lucene-only querystore, no ONNX files) must still get
		// authoritative Tier-2 verdicts for unambiguous claim sentences. resolveEmbedder() returns
		// null in that deployment shape; the lazy path must never touch it when Tier-2 succeeds.
		verifier.setEmbedder(null);
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify("Has hypertension [1].",
				new ArrayList<RecordReference>(Arrays.asList(reference(1))),
				Arrays.asList(mapping(1, "essential hypertension")), FLOOR, TIER2_ON);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(),
				"Tier-2 verdict must land even with no Tier-1 embedding model configured");
	}

	@Test
	public void clauseScoped_isolateCitations_fallBackToLazyTier1OnEngineFailure() {
		// The deferred x isolate combination: clause-scoped compound-sentence citations are
		// single-candidate clauses (deferred), verified in isolate single-pair Tier-2 calls. When
		// the engine fails, each must lazily get the Tier-1 cosine verdict of its OWN clause —
		// [1]'s registered clause/record pair -> TRUE, [2]'s unregistered pair -> FALSE.
		StubLlmProvider throwing = new StubLlmProvider(null) {

			@Override
			public List<Boolean> entailsBatch(List<String> sources, List<String> statements) {
				throw new RuntimeException("llama-server timed out");
			}
		};
		verifier.setLlmProvider(throwing);
		String answer = "Has diabetes [1] and hypertension [2].";
		embeddings.register("Has diabetes [1]", AXIS_A);   // [1]'s clause (cumulative prefix)
		embeddings.register("type 2 diabetes", AXIS_A);    // [1]'s record -> cosine 1 -> TRUE
		// [2]'s clause "Has diabetes [1] and hypertension [2]" and record left unregistered -> FALSE

		List<RecordReference> result = verifier.verify(answer,
				new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(2))),
				Arrays.asList(mapping(1, "type 2 diabetes"), mapping(2, "essential hypertension")),
				FLOOR, TIER2_ON, true);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(),
				"[1]: lazy Tier-1 must score the clause's own registered pair");
		assertEquals(Boolean.FALSE, result.get(1).getGrounded(),
				"[2]: lazy Tier-1 must score the clause's own unregistered pair");
	}

	@Test
	public void tier2_multiCitingSentences_bestStatementStillChosenByCosine() {
		// When MORE than one sentence cites the same record, the claim statement is still the
		// best-matching sentence by cosine — the selection embeds must still run so the Tier-2
		// statement is identical to the eager path's choice.
		String answer = "An unrelated remark [1]. Type 2 diabetes is active [1].";
		embeddings.register("An unrelated remark [1].", AXIS_B);
		embeddings.register("Type 2 diabetes is active [1].", AXIS_A);
		embeddings.register("type 2 diabetes", AXIS_A);
		llm.verdict = Boolean.TRUE;

		verifier.verify(answer,
				new ArrayList<RecordReference>(Arrays.asList(reference(1))),
				Arrays.asList(mapping(1, "type 2 diabetes")), FLOOR, TIER2_ON);

		assertTrue(embeddings.embedCalls > 0,
				"ambiguous claim selection still requires Tier-1 embeds");
		assertEquals(Arrays.asList("Type 2 diabetes is active ."), llm.statementsPerCall.get(0),
				"the cosine-best citing sentence must be the Tier-2 statement");
	}

	@Test
	public void tier2_batchFailure_lazyTier1VerdictMatchesEagerCosine() {
		// When Tier-2 cannot verify (engine failure), the Tier-1 cosine verdict must be computed
		// lazily and match what the eager path would have produced: registered on-topic pair ->
		// TRUE, unregistered pair (cosine 0) -> FALSE.
		StubLlmProvider throwing = new StubLlmProvider(null) {

			@Override
			public List<Boolean> entailsBatch(List<String> sources, List<String> statements) {
				throw new RuntimeException("llama-server timed out");
			}
		};
		verifier.setLlmProvider(throwing);
		String answer = "Has diabetes [1]. Has asthma [2].";
		embeddings.register("Has diabetes [1].", AXIS_A);
		embeddings.register("type 2 diabetes", AXIS_A); // [1] on-topic -> TRUE
		// [2]'s sentence and record left unregistered -> cosine 0 -> FALSE

		List<RecordReference> result = verifier.verify(answer,
				new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(2))),
				Arrays.asList(mapping(1, "type 2 diabetes"), mapping(2, "mild asthma")),
				FLOOR, TIER2_ON);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(),
				"lazy Tier-1 fallback must reproduce the eager cosine pass");
		assertEquals(Boolean.FALSE, result.get(1).getGrounded(),
				"lazy Tier-1 fallback must reproduce the eager cosine fail");
	}

	// ---- drug-reference citations (issue #106): verdicts may demote, never verify ----

	/** A mapping typed as an injected drug-reference record. Pass
	 *  {@link #realReferenceRecordText} where the text content matters (the demote logic keys on
	 *  the resource type, so mechanics-only tests may pass synthetic text). */
	private static RecordMapping drugReferenceMapping(int index, String text) {
		return new RecordMapping(index, ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE,
				"ref-" + index, null, text);
	}

	/** Real injected drug-reference record text off the real production chain (bundled DDInter
	 *  sample, load → parse → injectRecords → render) — no hand-assembled imitation of the
	 *  renderer's format. */
	private static String realReferenceRecordText(String drugName) {
		return org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport
				.injectedDdinterReferenceText("Can the patient take " + drugName + "?");
	}

	@Test
	public void drugReference_highCosinePassRendersUnverifiedNotVerified() {
		// The false-assurance case from issue #106: an answer reciting a drug-reference record
		// embeds near-identically to it whether or not the recitation swaps subject roles, so a
		// Tier-1 cosine pass carries no faithfulness signal. It must render null (unverified),
		// never true (verified).
		String record = realReferenceRecordText("Warfarin");
		String sentence = "Warfarin interacts with several drugs [7].";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_A); // recitation overlap -> cosine 1.0 -> Tier-1 would pass

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(7))),
				Arrays.asList(drugReferenceMapping(7, record)), FLOOR, TIER1_ONLY);

		assertNull(result.get(0).getGrounded(),
				"a cosine pass on a drug-reference citation must render unverified, not verified");
	}

	@Test
	public void drugReference_offTopicCitationIsStillFlagged() {
		// The demote direction keeps its signal: a drug-reference record cited for a claim it has
		// no overlap with (an off-topic citation) must still come back grounded=false.
		String record = realReferenceRecordText("Warfarin");
		String sentence = "The patient's blood pressure is well controlled [7].";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_B); // orthogonal -> cosine 0.0 -> off-topic

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(7))),
				Arrays.asList(drugReferenceMapping(7, record)), FLOOR, TIER1_ONLY);

		assertEquals(Boolean.FALSE, result.get(0).getGrounded(),
				"an off-topic drug-reference citation must still be flagged");
	}

	@Test
	public void drugReference_offTopicCitationStillFlaggedUnderEntailmentMode() {
		// The mode-uniform half of the demote-only contract (the class javadoc's accepted embed
		// cost): under entailment the flag comes from the LAZY Tier-1 pass, since Tier-2 is
		// skipped. An implementation that "optimized" the lazy pass away for demote-only
		// citations would render this null and still pass every other drug-reference test.
		String record = realReferenceRecordText("Warfarin");
		String sentence = "The patient's blood pressure is well controlled [7].";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_B); // orthogonal -> off-topic
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(7))),
				Arrays.asList(drugReferenceMapping(7, record)), FLOOR, TIER2_ON);

		assertEquals(0, llm.calls, "drug-reference citations must not reach Tier-2 even when off-topic");
		assertEquals(Boolean.FALSE, result.get(0).getGrounded(),
				"the off-topic flag must survive entailment mode via the lazy Tier-1 pass");
	}

	@Test
	public void drugReference_neverEntersTier2Entailment() {
		// Tier-2's yes on this content type is false assurance (issue #106: 4/4 subject-swapped
		// recitations passed) and its no misfired on the one faithful answer — so drug-reference
		// citations must not be judged by the entailment LLM at all, even when it would say yes.
		String record = realReferenceRecordText("Warfarin");
		String sentence = "Warfarin decreases the plasma concentrations of CYP3A4 substrates [7].";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_A);
		llm.verdict = Boolean.TRUE; // would falsely verify the swapped claim

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(7))),
				Arrays.asList(drugReferenceMapping(7, record)), FLOOR, TIER2_ON);

		assertEquals(0, llm.calls, "drug-reference citations must never reach the entailment LLM");
		assertNull(result.get(0).getGrounded(),
				"with Tier-2 skipped, the Tier-1 pass renders unverified");
	}

	@Test
	public void drugReference_doesNotConsumeTheEntailmentCapOfChartCitations() {
		// Cap-boundary pin: the drug-reference citation comes FIRST, followed by exactly
		// cap-many chart citations. If exclusion happened after the budget decrement (e.g. a
		// refactor nesting the demote check inside the budget branch), the LAST chart citation
		// would overflow the cap and keep its lazy Tier-1 FALSE instead of the Tier-2 TRUE.
		int cap = ChartSearchAiConstants.GROUNDING_ENTAILMENT_MAX_CHECKS;
		StringBuilder answer = new StringBuilder("Reference note [100]. ");
		List<RecordReference> refs = new ArrayList<RecordReference>();
		List<RecordMapping> maps = new ArrayList<RecordMapping>();
		refs.add(reference(100));
		maps.add(drugReferenceMapping(100, "warfarin reference record"));
		for (int i = 1; i <= cap; i++) {
			answer.append("claim ").append(i).append(" [").append(i).append("]. ");
			refs.add(reference(i));
			maps.add(mapping(i, "record " + i));
		}
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(answer.toString(), refs, maps, FLOOR, TIER2_ON);

		assertEquals(cap, llm.calls,
				"chart citations alone fill the cap; the excluded drug-reference pair must not count");
		assertEquals(Boolean.TRUE, result.get(cap).getGrounded(),
				"the last chart citation must still get its Tier-2 verdict — a consumed slot would leave it FALSE");
	}

	@Test
	public void drugReference_isExcludedFromTier2BatchAlongsideChartCitations() {
		// Mixed answer: the chart citation keeps its full Tier-2 treatment (one batched pair) and
		// the drug-reference citation neither joins the batch nor gets verified by it.
		String chartSentence = "Patient has diabetes [1].";
		String refSentence = "Warfarin interacts with several drugs [7].";
		String answer = chartSentence + " " + refSentence;
		String record = realReferenceRecordText("Warfarin");
		embeddings.register(chartSentence, AXIS_A);
		embeddings.register("type 2 diabetes mellitus", AXIS_A);
		embeddings.register(refSentence, AXIS_A);
		embeddings.register(record, AXIS_A);
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(answer,
				new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(7))),
				Arrays.asList(mapping(1, "type 2 diabetes mellitus"), drugReferenceMapping(7, record)),
				FLOOR, TIER2_ON);

		assertEquals(1, llm.calls, "only the chart citation may reach Tier-2");
		assertEquals(Boolean.TRUE, result.get(0).getGrounded(), "chart citation keeps its Tier-2 verdict");
		assertNull(result.get(1).getGrounded(), "drug-reference citation renders unverified");
	}

	@Test
	public void drugReference_noTextStaysUnverified() {
		// The existing no-text contract is unchanged by the demote-only rule.
		List<RecordReference> result = verifier.verify("Warfarin interacts with several drugs [7].",
				new ArrayList<RecordReference>(Arrays.asList(reference(7))),
				Arrays.asList(drugReferenceMapping(7, null)), FLOOR, TIER1_ONLY);

		assertNull(result.get(0).getGrounded());
	}

	// ---- injected safety-finding citations (issue #122): reference material, so demote-only ----

	/**
	 * The real injected safety-finding record the REAL production chain renders for the canonical
	 * case — a patient on simvastatin asked about clarithromycin — off the DDInter excerpt
	 * (load → parse → validate → injectRecords → renderFinding). The whole mapping rather than only
	 * its text, unlike {@link #realReferenceRecordText}: the record's own citation index is what an
	 * answer sentence has to cite, and its real resource type is what the carve-out keys on.
	 */
	private static RecordMapping realSafetyFinding() {
		return org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport
				.injectedSafetyFinding("is it safe to give clarithromycin?", "simvastatin", "C10AA01");
	}

	/** The answer sentence a finding gets cited by — the model reporting the finding line it was
	 *  handed, which is the behaviour #110 injected the record to produce. */
	private static String findingCitingSentence(RecordMapping finding) {
		return "Not safe — clarithromycin interacts with the patient's active simvastatin order ["
				+ finding.getIndex() + "].";
	}

	@Test
	public void safetyFinding_highCosinePassRendersUnverifiedNotVerified() {
		// Issue #122. #110 injects the deterministic drug-safety join as a citable record so the answer
		// reports a conclusion it will not re-derive; #106 had already established that module-supplied
		// injected records are demote-only. safety_finding was never registered with that carve-out, so
		// the module's own arithmetic was graded as if it were retrieved chart evidence — and the grade
		// tracked embedding noise, not the finding: on the 3.7.1 standalone at 13690b1, Margaret King +
		// voxelotor returned the MAJOR finding grounded=false beside two byte-identical Moderate
		// siblings at true, and one finding flipped true->false across two runs of one probe.
		//
		// The record's prose is exactly the shape #106 measured the hazard on — "<Drug> interacts with
		// active order <Partner> — Major. <mechanism>", reference prose whose subject roles can swap
		// while still embedding near-identically — and the citing sentence is a recitation of it. So a
		// cosine pass carries no faithfulness signal, and publishing it as `true` is false assurance.
		RecordMapping finding = realSafetyFinding();
		String sentence = findingCitingSentence(finding);
		embeddings.register(sentence, AXIS_A);
		embeddings.register(finding.getText(), AXIS_A); // recitation overlap -> cosine 1.0 -> would pass

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(finding.getIndex()))),
				Arrays.asList(finding), FLOOR, TIER1_ONLY);

		assertNull(result.get(0).getGrounded(),
				"a cosine pass on the module's own deterministic finding must render unverified, "
						+ "never verified");
	}

	@Test
	public void safetyFinding_offTopicCitationIsStillFlagged() {
		// Demote-only, NOT exempt-from-grounding — the alternative issue #122 asked to decide rather
		// than default. A FALSE verdict here is not the module doubting its own arithmetic, which
		// would indeed be a meaningless claim; it is a statement about the CITATION — the model
		// attached the finding's number to a sentence the finding is not about. That is real,
		// observable and worth flagging, and it is the residual signal #106 deliberately kept when it
		// removed the passing verdict. Exempting entirely would discard it, and (since drug_reference
		// keeps its flag) could only be done for safety_finding alone — a per-type branch in the very
		// registry this issue exists to stop keying off type names.
		//
		// Issue #201 stopped PUBLISHING this verdict — the wire serializes null for every
		// reference-group citation, because no client could tell "this citation is not about that
		// record" from "this claim is unsupported". That did not change the pass, and this assertion
		// is unchanged with it: it is about the verdict the MODULE holds, which is what getGrounded()
		// returns. Choosing "exempt entirely" instead would still be the per-type branch this
		// registry exists to avoid, and it would also drop the drug_reference flag with it.
		RecordMapping finding = realSafetyFinding();
		String sentence = "The patient's blood pressure is well controlled [" + finding.getIndex() + "].";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(finding.getText(), AXIS_B); // orthogonal -> cosine 0.0 -> off-topic

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(finding.getIndex()))),
				Arrays.asList(finding), FLOOR, TIER1_ONLY);

		assertEquals(Boolean.FALSE, result.get(0).getGrounded(),
				"an off-topic safety-finding citation must still be flagged");
	}

	@Test
	public void safetyFinding_neverEntersTier2Entailment() {
		// The judge cannot help here either: its "yes" on a recitation of module-rendered prose is the
		// false assurance #106 measured (4/4 role-swapped recitations judged entailed, the one faithful
		// recitation judged not). So the pair is skipped rather than discounted afterwards.
		RecordMapping finding = realSafetyFinding();
		String sentence = findingCitingSentence(finding);
		embeddings.register(sentence, AXIS_A);
		embeddings.register(finding.getText(), AXIS_A);
		llm.verdict = Boolean.TRUE; // would falsely verify the recitation

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(finding.getIndex()))),
				Arrays.asList(finding), FLOOR, TIER2_ON);

		assertEquals(0, llm.calls, "safety-finding citations must never reach the entailment LLM");
		assertNull(result.get(0).getGrounded(), "with Tier-2 skipped, the Tier-1 pass renders unverified");
	}

	@Test
	public void safetyFinding_doesNotConsumeTheEntailmentCapOfChartCitations() {
		// The second half of the defect, and the one a client cannot see: these records were also
		// spending the per-answer entailment budget that #106's rationale reserves for chart claims,
		// and a polypharmacy answer can carry several findings. Cap-boundary pin, mirroring the
		// drug-reference test above — the finding comes FIRST, followed by exactly cap-many chart
		// citations, so a consumed slot pushes the LAST chart citation past the cap and leaves it on
		// its Tier-1 verdict. Chart indexes are offset past the finding's real index so the two
		// citation numberings cannot collide.
		int cap = ChartSearchAiConstants.GROUNDING_ENTAILMENT_MAX_CHECKS;
		RecordMapping finding = realSafetyFinding();
		int base = finding.getIndex();
		StringBuilder answer = new StringBuilder(findingCitingSentence(finding)).append(" ");
		List<RecordReference> refs = new ArrayList<RecordReference>();
		List<RecordMapping> maps = new ArrayList<RecordMapping>();
		refs.add(reference(base));
		maps.add(finding);
		for (int i = 1; i <= cap; i++) {
			answer.append("claim ").append(i).append(" [").append(base + i).append("]. ");
			refs.add(reference(base + i));
			maps.add(mapping(base + i, "record " + i));
		}
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(answer.toString(), refs, maps, FLOOR, TIER2_ON);

		assertEquals(cap, llm.calls,
				"chart citations alone fill the cap; the excluded safety-finding pair must not count");
		assertEquals(Boolean.TRUE, result.get(cap).getGrounded(),
				"the last chart citation must still get its Tier-2 verdict — a consumed slot would leave it FALSE");
	}

	// ---- injected active-order citations (issue #118): graded normally, NOT demote-only ----

	/** The real active-order {@code RecordMapping} the production injector mints for a NAMED order —
	 *  the whole mapping off the real reconciliation → render chain, never hand-built, because the
	 *  per-record stamps the injector writes are what decide how such a citation is graded (issue
	 *  #294). {@code DrugReferenceTestSupport}'s accessor says the same thing on its own side. */
	private static RecordMapping namedActiveDrugOrderMapping(String uuid, String display) {
		return org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport
				.injectedNamedActiveOrderMapping(uuid, display);
	}

	/** The answer sentence the three named-order cases cite that record with. Shared for the reason
	 *  {@link #NAMED_ORDER_DISPLAY} is: the sentence has to name the record the mapping renders. */
	private static String namedOrderSentence(int index) {
		return "The patient has an active order for " + NAMED_ORDER_DISPLAY + " [" + index + "].";
	}

	@Test
	public void activeDrugOrder_highCosinePassRendersVerifiedNotDemoted() {
		// The deliberate NON-extension of the #106 demote-only carve-out, which until now lived only
		// in a comment beside the carve-out. active_drug_order is a THIRD injected type, so the
		// obvious generalisation — "records this module injects cannot be verified" — is wrong for it
		// and nothing failed if someone made it: the #106 hazard is reference PROSE whose subject
		// roles swap while still embedding near-identically ("A interacts with B"), whereas this
		// record is one drug name asserted of this patient, so a cosine pass is real assurance.
		// Demoting it would strip the faithfulness check from the very record injected to stop the
		// answer contradicting the safety chips (#118) — silently, since a demoted verdict is null,
		// not an error. Exactly inverts drugReference_highCosinePassRendersUnverifiedNotVerified.
		//
		// "One drug name" is the ordinary shape only: a codes-only display asserts none. That did NOT
		// change this rule — such a record is still chart evidence and still graded by the carve-out
		// this case is about — it is held back one step further down, by Disposition.UNVERIFIABLE,
		// which is canonical for it (issue #294). The codesOnlyActiveOrder_* cases below are that.
		RecordMapping mapping = namedActiveDrugOrderMapping(NAMED_ORDER_UUID, NAMED_ORDER_DISPLAY);
		int n = mapping.getIndex();
		String sentence = namedOrderSentence(n);
		embeddings.register(sentence, AXIS_A);
		embeddings.register(mapping.getText(), AXIS_A);

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(n))),
				Arrays.asList(mapping), FLOOR, TIER1_ONLY);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(),
				"an active-order citation is chart evidence, so a cosine pass must VERIFY it — "
						+ "demote-only is scoped to drug-reference prose (#106), not to everything injected");
	}

	@Test
	public void activeDrugOrder_isVerifiedByTier2Entailment() {
		// The other half: it must also reach the entailment LLM. A type excluded from Tier-2 keeps a
		// Tier-1 verdict only, so an off-claim citation that cosine happens to like would never be
		// caught — and nothing at the wire suppresses this type's verdict (it groups as chart
		// evidence), so the verdict rendered here is the one the clinician sees. For a record that
		// names no drug the MODULE suppresses it, one layer earlier and per record rather than per
		// type (issue #294); this case is the NAMED record, where it is published. Inverts
		// drugReference_neverEntersTier2Entailment.
		RecordMapping mapping = namedActiveDrugOrderMapping(NAMED_ORDER_UUID, NAMED_ORDER_DISPLAY);
		int n = mapping.getIndex();
		String sentence = namedOrderSentence(n);
		embeddings.register(sentence, AXIS_A);
		embeddings.register(mapping.getText(), AXIS_A);
		llm.verdict = Boolean.FALSE;

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(n))),
				Arrays.asList(mapping), FLOOR, TIER2_ON);

		assertEquals(1, llm.calls, "an active-order citation must be judged by the entailment LLM");
		assertEquals(Boolean.FALSE, result.get(0).getGrounded(),
				"and Tier-2's verdict must be authoritative for it, overriding the Tier-1 cosine pass");
	}

	// ---- a codes-only active-order citation is ungradeable, in either mode (issue #294) ----

	/** The code-only stand-in's own uuid and codes. Which drug the codes denote is irrelevant to every
	 *  case below and asserting it would be an unverified claim, so nothing here names a substance. */
	private static final String CODES_ONLY_ORDER_UUID = "order-uuid-codes-only";

	/** The codes the stand-in is built from. Its DISPLAY is not spelled here — the helper builds it
	 *  through production's own {@code codeOnlyDisplay}, so a case cannot assert against a label
	 *  production stopped emitting. The exact rendered string is pinned elsewhere, deliberately:
	 *  {@code NamelessActiveOrderPartnerTest} and {@code CodesOnlyActiveOrderGroundingContextTest}
	 *  each assert it byte for byte, and this file asserts nothing about it. */
	private static final java.util.Set<String> CODES_ONLY_ATC =
			new java.util.LinkedHashSet<String>(Arrays.asList("M01AE02", "M01AE04"));

	/** The medication claim the cases below make about a record naming no drug. One constant rather
	 *  than three copies: they must stay the same claim for the three verdict regimes to be
	 *  comparable. */
	private static final String CODES_ONLY_CLAIM = "The patient is taking metformin 500mg twice daily";

	/** The NAMED active order the three non-regressions are about, for the same reason — the third of
	 *  them exists to differ from the codes-only trio in exactly one thing, so a one-word drift in
	 *  this display would leave it comparing a different record with nothing saying so. A one-word
	 *  drift is not hypothetical here: {@code coMedicationRecord} below names a "Simvastatin 20mg",
	 *  which is a DIFFERENT fixture for a different arrangement and deliberately not this one. */
	private static final String NAMED_ORDER_DISPLAY = "Simvastatin Co 20mg";

	private static final String NAMED_ORDER_UUID = "order-uuid-7";

	/**
	 * The real {@code active_drug_order} mapping the real injector produces for an order the module
	 * could read no name for — through {@code ActiveDrugOrder.namedByCodesOnly} and the real render
	 * chain, never hand-built, because the per-record answers the injector stamps on that mapping are
	 * exactly what these cases are about.
	 */
	private static RecordMapping codesOnlyActiveOrderMapping() {
		return org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport
				.injectedCodesOnlyActiveOrderMapping(CODES_ONLY_ORDER_UUID, CODES_ONLY_ATC);
	}

	@Test
	public void codesOnlyActiveOrder_aCosineFailIsWithheldRatherThanPublished() {
		// THE discriminating case for issue #294's remedy, and the reason the disposition is
		// UNVERIFIABLE rather than DEMOTE_ONLY. Demote-only withholds a PASS and keeps a FAIL — so
		// under it this citation would still publish false, which is the exposure #294 is about,
		// reached through Tier-1 instead of through the judge. A record naming no drug gives the
		// cosine nothing but an identifier to compare against, so its fail says no more about the
		// citation than the judge's refusal does.
		RecordMapping record = codesOnlyActiveOrderMapping();
		String sentence = CODES_ONLY_CLAIM + " [" + record.getIndex() + "].";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record.getText(), AXIS_B); // orthogonal -> cosine 0.0, a Tier-1 FAIL

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(record.getIndex()))),
				Arrays.asList(record), FLOOR, TIER1_ONLY);

		assertNull(result.get(0).getGrounded(),
				"a cosine fail on a record that names no drug must publish NOTHING — keeping it is "
						+ "demote-only, and demote-only leaves #294's own false standing via Tier-1");
	}

	@Test
	public void codesOnlyActiveOrder_aCosinePassIsWithheldToo() {
		// The other direction, so the rule is not mistaken for a one-sided demotion: nothing is
		// published either way. A pass here would be assurance drawn from a record that asserts no
		// drug at all, which is the clause isGroundingDemoteOnly's javadoc says does not hold of it.
		RecordMapping record = codesOnlyActiveOrderMapping();
		String sentence = "The patient has an active drug order [" + record.getIndex() + "].";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record.getText(), AXIS_A); // identical direction -> cosine 1.0, a PASS

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(record.getIndex()))),
				Arrays.asList(record), FLOOR, TIER1_ONLY);

		assertNull(result.get(0).getGrounded(),
				"neither tier is asked a question this citation's own, so a pass is withheld with "
						+ "the fail");
	}

	@Test
	public void codesOnlyActiveOrder_neverEntersTier2AndPublishesNothing() {
		// Under entailment the judge is not asked at all, so the pair never reaches the per-answer cap
		// that chart claims rely on — the half of this rule a wire-level carve-out could not buy.
		RecordMapping record = codesOnlyActiveOrderMapping();
		String sentence = CODES_ONLY_CLAIM + " [" + record.getIndex() + "].";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record.getText(), AXIS_A);
		llm.verdict = Boolean.FALSE;

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(record.getIndex()))),
				Arrays.asList(record), FLOOR, TIER2_ON);

		assertEquals(0, llm.calls,
				"the judge must not be asked whether a record naming no drug entails a medication "
						+ "claim: it refuses by construction, so the answer is about the record's "
						+ "silence and not about the citation");
		assertNull(result.get(0).getGrounded(),
				"and nothing is published in either direction (issue #294)");
	}

	@Test
	public void codesOnlyActiveOrder_spendsNoEmbeddingEvenWhereTheClaimWouldHaveToBeChosen() {
		// The claim-selection SKIP, which nothing else in the suite discriminates: drop it and every
		// assertion above stays green, because the disposition alone already withholds the verdict and
		// excludes the pair from Tier-2. What it buys is the work, so the work is what this counts.
		// TWO sentences cite the record, which is selectClaim's ambiguous branch — the one where the
		// cosine argmax runs EAGERLY under entailment to choose between them. That is the arrangement
		// in which skipping is worth anything; with one candidate the selection defers and spends
		// nothing either way. The counterpart for a citation the module attached is
		// aCitationTheModuleAttachedPublishesNoVerdictAndSpendsNothing.
		RecordMapping record = codesOnlyActiveOrderMapping();
		int n = record.getIndex();
		String answer = CODES_ONLY_CLAIM + " [" + n + "]. "
				+ "She also has an unnamed active order [" + n + "].";
		embeddings.register(record.getText(), AXIS_A);
		llm.verdict = Boolean.FALSE;

		List<RecordReference> result = verifier.verify(answer,
				new ArrayList<RecordReference>(Arrays.asList(reference(n))),
				Arrays.asList(record), FLOOR, TIER2_ON);

		assertEquals(0, embeddings.embedCalls,
				"no claim is selected for a citation nothing will be published for, so the argmax "
						+ "between the two citing sentences must not run — on a CPU deployment that "
						+ "argmax is the dominant grounding cost");
		assertEquals(0, llm.calls, "and the judge is not asked either");
		assertNull(result.get(0).getGrounded(), "and nothing is published");
	}

	@Test
	public void codesOnlyActiveOrder_doesNotConsumeTheEntailmentCapOfChartCitations() {
		// The cap-boundary pin, arranged like its three siblings
		// (drugReference_/safetyFinding_/compoundClaim_doesNotConsumeTheEntailmentCap...): the excluded
		// citation comes FIRST, followed by exactly cap-many gradable ones. What it pins is the
		// OUTCOME the ADR cites — the slot this rule does not spend is one a real chart citation
		// takes, which is the difference between an authoritative entailment verdict and a cosine
		// fallback FOR THAT CITATION. Remove the #294 rule and the last chart citation comes back
		// `false`, its Tier-1 verdict; that is measured, and it is what this case exists for.
		//
		// It does NOT discriminate what its siblings discriminate — whether the exclusion sits inside
		// the budget branch — and saying so matters, because the recipe invites that reading. A
		// codes-only citation is kept out of Tier-2 twice over: claim selection is skipped for it, so
		// `tier1.bestSentence` is null and the outer guard already refuses it whatever the budget
		// branch does. Measured: moving the disposition test inside that branch leaves this case green
		// and reddens the three siblings named above — which is where that position IS guarded, and
		// they are the cases to read for it. (An earlier version of this comment said it reddens a
		// #284 case. It does not; none of those moved.)
		int cap = ChartSearchAiConstants.GROUNDING_ENTAILMENT_MAX_CHECKS;
		RecordMapping codesOnly = codesOnlyActiveOrderMapping();
		int n = codesOnly.getIndex();
		StringBuilder answer = new StringBuilder(CODES_ONLY_CLAIM).append(" [").append(n).append("]. ");
		List<RecordReference> refs = new ArrayList<RecordReference>(Arrays.asList(reference(n)));
		List<RecordMapping> maps = new ArrayList<RecordMapping>(Arrays.asList(codesOnly));
		for (int i = 1; i <= cap; i++) {
			int index = n + i;
			answer.append("claim ").append(i).append(" [").append(index).append("]. ");
			refs.add(reference(index));
			maps.add(mapping(index, "record " + i));
		}
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(answer.toString(), refs, maps, FLOOR, TIER2_ON);

		assertEquals(cap, llm.calls,
				"the chart citations alone fill the cap; the excluded codes-only pair must not count");
		assertEquals(Boolean.TRUE, result.get(refs.size() - 1).getGrounded(),
				"the last chart citation must still get its Tier-2 verdict — a consumed slot would "
						+ "leave it on its Tier-1 verdict instead");
		assertNull(result.get(0).getGrounded(), "and the codes-only citation itself publishes nothing");
	}

	@Test
	public void aNamedActiveOrderRecordIsStillGradedThroughTheRealInjector() {
		// The non-regression the type-keyed remedy fails: the rule reaches the record that names no
		// drug and no other. This mapping comes off the same real injector as the three above, so the
		// only difference between them is the one the rule keys on.
		RecordMapping record = namedActiveDrugOrderMapping("order-uuid-named", NAMED_ORDER_DISPLAY);
		assertEquals(Boolean.TRUE, record.getOrderDrugNamed(),
				"precondition, and the TRUE half of the stamp's three-valued contract: the injector "
						+ "STATES that this record names its drug rather than staying silent. Nothing "
						+ "downstream tells TRUE from null today — both grade — so a writer collapsed "
						+ "to \"FALSE or nothing\" is only visible to a case that reads the stamp. "
						+ "DrugReferenceInjectorTest.theInjectedActiveOrderRecordStatesWhetherItNamesItsDrug "
						+ "is the other one, beside the writer");
		String sentence = namedOrderSentence(record.getIndex());
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record.getText(), AXIS_A);
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(record.getIndex()))),
				Arrays.asList(record), FLOOR, TIER2_ON);

		assertEquals(1, llm.calls, "a named active-order citation is still judged (#118)");
		assertEquals(Boolean.TRUE, result.get(0).getGrounded(),
				"and its verdict is still published — #294's rule is about a record that names no "
						+ "drug, not about the type");
	}

	// ---- the grounding registry every resource type must be decided in (issue #122) ----

	/**
	 * Runs the REAL verifier over ONE cited record of {@code resourceType} whose text is aligned with
	 * its citing sentence — a Tier-1 PASS, which is the only verdict the carve-out changes (a cosine
	 * FAIL flags every type alike) — and returns the verdict published for it. Re-arms the stubs
	 * first, so {@code llm.calls} afterwards counts this type's Tier-2 entry alone.
	 *
	 * <p>Synthetic record text, deliberately: the carve-out keys on the resource type, and most of
	 * these types have no injector in this module to render real prose from. What the real prose does
	 * under a cosine pass is asserted by the {@code realSafetyFinding} / {@code realReferenceRecordText}
	 * tests above; this helper's subject is the type registry.
	 */
	private Boolean verdictForAlignedCitation(String resourceType, boolean entailmentEnabled) {
		return alignedCitation(resourceType, entailmentEnabled, reference(4)).get(0).getGrounded();
	}

	/**
	 * ONE arrangement in which a citation of {@code resourceType} would grade {@code TRUE}: a
	 * programmed judge that says yes, and a record whose text embeds onto the same axis as the answer
	 * sentence citing it. The reference is a parameter so a case can vary WHO cited it and nothing
	 * else.
	 *
	 * <p>Shared rather than copied because two cases assert that they are the same arrangement —
	 * {@code aCitationTheModuleAttachedPublishesNoVerdictAndSpendsNothing}'s whole claim is that only
	 * the attachment differs, and a hand-written twin would let that sentence become false with both
	 * cases green. Calls {@link #setUp()} itself, so a caller iterating modes gets a fresh capture
	 * and fresh counters per iteration.
	 */
	private List<RecordReference> alignedCitation(String resourceType, boolean entailmentEnabled,
			RecordReference citation) {
		setUp();
		llm.verdict = Boolean.TRUE;
		String sentence = "The record supports this claim [4].";
		String record = "record text for " + resourceType;
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_A);
		return verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(citation)),
				Arrays.asList(new RecordMapping(4, resourceType, "uuid-4", null, record)),
				FLOOR, entailmentEnabled);
	}

	/**
	 * A citation the MODULE attached publishes no verdict, in either mode, and spends nothing getting
	 * there (issue #305).
	 *
	 * <p>The arrangement is literally the one {@link #verdictForAlignedCitation} uses for its positive
	 * control — {@link #alignedCitation}, shared between them: a chart-group record whose text and the
	 * answer's sentence embed to the same axis, and a judge programmed to say yes, so under either mode
	 * a MODEL-emitted citation of it grades {@code TRUE}. The only difference here is who attached it,
	 * and that is enough to withhold the verdict, because grounding asks whether the claim the model
	 * attached to a citation is supported by the record it pointed at: the module attached no claim,
	 * so there is no such pairing.
	 *
	 * <p>Publishing a Tier-1 {@code FALSE} instead is the failure this refuses. Such a citation is
	 * anchored by no sentence, so its claim would be GUESSED out of the whole answer at whatever
	 * {@code chartsearchai.grounding.minCosine} the operator set — and this module's own global-property
	 * text advises raising that to about 0.82 on an e5 querystore deployment. A red
	 * <em>Unsupported</em> on the module's own deterministic provenance is issue #201's defect one
	 * record over.
	 *
	 * <p>The two counts are the "no embedding is spent" half of {@code Disposition.UNVERIFIABLE}, which
	 * the compound-claim case gets for free from claim DEFERRAL and this one cannot: with nothing
	 * anchoring it, {@code selectClaim}'s candidate set is every sentence, which is the ambiguous
	 * branch where the cosine argmax runs eagerly.
	 *
	 * <p><b>Which mutation reddens which assertion, measured rather than assumed.</b> Removing the
	 * Pass-1 claim-selection SKIP alone reddens the embedding count here (2 passes spent) and moves no
	 * verdict. Removing the disposition ARM alone reddens nothing in this class — the skipped result
	 * then withholds by accident, having no claim sentence to be a judge candidate with. Removing BOTH
	 * publishes {@code true} and reddens the verdict assertion here and in
	 * {@link #theModelsOwnChartCitationIsStillGradedBesideAnAttachedOne}. So the arm is a statement of
	 * intent that no case discriminates; the comment at that site says why it stays.
	 */
	@Test
	public void aCitationTheModuleAttachedPublishesNoVerdictAndSpendsNothing() {
		for (boolean entailment : new boolean[] { TIER1_ONLY, TIER2_ON }) {
			String mode = entailment ? "entailment on" : "Tier-1 only";

			// The SAME arrangement verdictForAlignedCitation uses for its positive control, with the
			// attachment as the only difference — shared rather than copied, so the claim below that
			// it grades TRUE for a model-emitted citation cannot go stale with both cases green.
			List<RecordReference> verdicts = alignedCitation("obs", entailment, attachedReference(4));

			assertNull(verdicts.get(0).getGrounded(), mode + ": a citation the module attached carries "
					+ "no claim of the model's, so nothing may be published about it — and the same "
					+ "arrangement grades TRUE for a model-emitted citation, which is what makes this a "
					+ "statement about the attachment and not about the embeddings");
			assertTrue(verdicts.get(0).isAttachedByTheModule(),
					mode + ": and the reference keeps saying who attached it");
			assertEquals(0, llm.calls, mode + ": it must not reach Tier-2, nor consume the per-answer "
					+ "cap that the model's own chart claims rely on");
			assertEquals(0, embeddings.embedCalls, mode + ": nor spend an embedding pass on a claim "
					+ "selection whose answer is discarded");
		}
	}

	/**
	 * Both kinds of citation in ONE answer, so the withholding cannot be read as a mode-wide effect:
	 * the model's own chart citation is graded in the same call that publishes nothing for the
	 * attached one.
	 */
	@Test
	public void theModelsOwnChartCitationIsStillGradedBesideAnAttachedOne() {
		llm.verdict = Boolean.TRUE;
		String sentence = "The record supports this claim [4].";
		String cited = "record text the model cited";
		String attached = "record text the module attached";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(cited, AXIS_A);
		embeddings.register(attached, AXIS_A);

		List<RecordReference> verdicts = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(4), attachedReference(5))),
				Arrays.asList(new RecordMapping(4, "obs", "uuid-4", null, cited),
						new RecordMapping(5, "obs", "uuid-5", null, attached)),
				FLOOR, TIER1_ONLY);

		assertEquals(Boolean.TRUE, verdicts.get(0).getGrounded(),
				"the model's own chart citation is graded exactly as it was before issue #305");
		assertNull(verdicts.get(1).getGrounded(),
				"and the attached one publishes nothing, in the same call");
	}

	/**
	 * The forcing function issue #122 asked for, and the reason that issue existed at all. An injected
	 * resource type has to be registered in TWO places: {@link ChartSearchAiUtils#referenceGroup},
	 * which a reflective sweep in {@code ChartSearchAiReferenceGroupTest} has always guarded, and the
	 * demote-only grounding carve-out, which nothing guarded. #110 duly did the first and missed the
	 * second, so the module's own deterministic findings were graded as retrieved chart evidence with
	 * no error raised anywhere.
	 *
	 * <p>The carve-out is now DERIVED from the group
	 * ({@link ChartSearchAiUtils#isGroundingDemoteOnly}), and this sweep is what keeps it derived:
	 * re-hardcoding it as a list of type names used to pass, because the list and the groups agreed, and
	 * it was to fail the moment a third reference-GROUP type was added. Issue #354 added one
	 * ({@code drug_class_note}), so that is now live: measured on it, hardcoding
	 * {@code isGroundingDemoteOnly} to the old pair reddens this case. Which OTHER sites it also
	 * reddens differs per site; mutate the one being changed rather than reasoning from this one. A
	 * third reference-group type and not a fourth injected type
	 * of any kind, which is what this said until #229 measured it: hardcode the predicate, add a
	 * fourth declared constant that groups as CHART evidence, and this sweep stays green — the
	 * hardcode and the classification agree that such a type is not demote-only, so nothing here can
	 * tell them apart. Classify that same constant as reference material instead and it reddens.
	 * Its counterpart in {@code ChartSearchAiReferenceGroupTest} asserts
	 * the same rule against the group each constant is RECORDED as, so the two registries cannot drift
	 * together either.
	 *
	 * <p>Asserts carve-out MEMBERSHIP — through the verdict the real verifier publishes and its Tier-2
	 * entry — not the live instability that motivated the issue: those flips are embedding-driven, so
	 * no unit test reproduces them. The chart-group half of each assertion is the positive control that
	 * the machinery under it is working.
	 *
	 * <p><b>Every citation it sweeps is one the MODEL emitted</b>, which is what "chart evidence is
	 * graded normally, however it reached the chart" is a claim about. Since issue #305 a chart-group
	 * citation the MODULE attached publishes nothing, and this sweep cannot see that — it builds its
	 * references through {@link #reference}, which answers false to
	 * {@code isAttachedByTheModule()}. {@link #aCitationTheModuleAttachedPublishesNoVerdictAndSpendsNothing}
	 * is that case, and it is deliberately beside this rather than folded into it: the axes are
	 * independent, and one sweep over their product would assert the same thing per resource type for
	 * a distinction no resource type is part of. There is a THIRD axis since issue #294 — what the
	 * RECORD says — and this sweep cannot see that one either, its mappings answering {@code null} to
	 * {@code getOrderDrugNamed()}. The {@code codesOnlyActiveOrder_*} cases are beside it for the same
	 * reason, and are why "graded normally" is a claim about the type and not about every record of it.
	 */
	@Test
	public void everyDeclaredResourceTypeConstant_isGradedAccordingToItsReferenceGroup() throws Exception {
		int swept = 0;
		for (Field field : ChartSearchAiConstants.class.getDeclaredFields()) {
			if (!field.getName().startsWith("RESOURCE_TYPE_") || field.getType() != String.class
					|| !Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			swept++;
			String type = (String) field.get(null);
			String group = ChartSearchAiUtils.referenceGroup(type);
			boolean referenceMaterial = ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE.equals(group);
			String label = field.getName() + " (\"" + type + "\", group " + group + ")";

			assertEquals(referenceMaterial ? null : Boolean.TRUE,
					verdictForAlignedCitation(type, TIER1_ONLY),
					label + ": a cosine PASS must render " + (referenceMaterial
							? "unverified — module-supplied material is demote-only (#106, #122)"
							: "verified — chart evidence is graded normally, however it reached the chart"));

			Boolean underEntailment = verdictForAlignedCitation(type, TIER2_ON);
			assertEquals(referenceMaterial ? 0 : 1, llm.calls, label + ": " + (referenceMaterial
					? "module-supplied material must not reach Tier-2, nor consume the per-answer cap "
							+ "that chart claims rely on"
					: "chart evidence must be judged by the entailment LLM"));
			assertEquals(referenceMaterial ? null : Boolean.TRUE, underEntailment,
					label + ": the entailment-mode verdict must agree with the Tier-1-only one for "
							+ "this group — the carve-out is mode-uniform");
		}
		assertTrue(swept > 0, "the RESOURCE_TYPE_* sweep matched no constants, so it asserts nothing");
	}

	/** Index of the first {@code entailsBatch} call whose statement list contains {@code statement}
	 *  exactly, or -1 — lets a test assert how citations were grouped into calls. */
	private int callIndexContaining(String statement) {
		for (int i = 0; i < llm.statementsPerCall.size(); i++) {
			if (llm.statementsPerCall.get(i).contains(statement)) {
				return i;
			}
		}
		return -1;
	}

	// ---- enumerating sentences (issue #278) ----

	/**
	 * The real answer the live module produces for "any allergies?" — one sentence ENUMERATING three
	 * chart records. The list-introducing colon is the structural signal that the text after it is a
	 * series of sibling items rather than one compound claim.
	 */
	private static final String ENUMERATION =
			"Yes — the patient has the following recorded allergies: Lidocaine [1], Ketoconazole [2], and Aspirin [3].";

	/**
	 * A judge that models what a correct entailment check does with a CONJUNCTION: the record entails
	 * the statement only if every vocabulary term the statement names is one the record itself names.
	 * A statement naming three allergens is therefore entailed by no single allergy record — which is
	 * exactly why issue #278's answer was graded ungrounded on every citation.
	 */
	private static class ConjunctionAwareJudge extends LlmProvider {

		private final List<String> vocabulary;

		final List<List<String>> statementsPerCall = new ArrayList<List<String>>();

		ConjunctionAwareJudge(String... vocabulary) {
			this.vocabulary = Arrays.asList(vocabulary);
		}

		@Override
		public List<Boolean> entailsBatch(List<String> sources, List<String> statements) {
			statementsPerCall.add(new ArrayList<String>(statements));
			List<Boolean> out = new ArrayList<Boolean>();
			for (int i = 0; i < sources.size(); i++) {
				String source = sources.get(i).toLowerCase();
				String statement = statements.get(i).toLowerCase();
				boolean entailed = true;
				for (String term : vocabulary) {
					if (statement.contains(term) && !source.contains(term)) {
						entailed = false;
					}
				}
				out.add(Boolean.valueOf(entailed));
			}
			return out;
		}
	}

	private List<RecordMapping> allergyMappings(String secondAllergen) {
		return Arrays.asList(mapping(1, "Allergy: Lidocaine (drug allergen)"),
				mapping(2, "Allergy: " + secondAllergen + " (drug allergen)"),
				mapping(3, "Allergy: Aspirin (drug allergen)"));
	}

	private List<RecordReference> threeRefs() {
		return new ArrayList<RecordReference>(
				Arrays.asList(reference(1), reference(2), reference(3)));
	}

	@Test
	public void splitIntoCitedSentences_enumerationGivesEachCitationThePreambleAndItsOwnItem() {
		List<CitationGroundingVerifier.Sentence> clauses =
				CitationGroundingVerifier.splitIntoCitedSentences(ENUMERATION);

		assertEquals(3, clauses.size(), "an enumerating sentence yields one claim per cited record");
		String preamble = "Yes — the patient has the following recorded allergies: ";
		assertEquals(preamble + "Lidocaine [1]", clauses.get(0).text);
		assertEquals(preamble + "Ketoconazole [2]", clauses.get(1).text,
				"[2]'s claim must name ITS allergen only, not the cumulative list");
		assertEquals(preamble + "Aspirin [3]", clauses.get(2).text,
				"the trailing item's separator and conjunction are dropped");

		for (int i = 0; i < 3; i++) {
			assertTrue(clauses.get(i).cites(i + 1));
			assertTrue(clauses.get(i).isolate,
					"enumeration claims share a preamble, so they must not be co-batched");
		}
		assertFalse(clauses.get(1).cites(1), "[2]'s claim is attributed to [2] alone");
		assertFalse(clauses.get(1).cites(3), "[2]'s claim must not reach the later [3]");
	}

	@Test
	public void splitIntoClauseScopedSentences_enumerationIsNotTheCumulativePrefix() {
		List<CitationGroundingVerifier.Sentence> clauses =
				CitationGroundingVerifier.splitIntoClauseScopedSentences(ENUMERATION);

		assertEquals(3, clauses.size());
		String preamble = "Yes — the patient has the following recorded allergies: ";
		assertEquals(preamble + "Ketoconazole [2]", clauses.get(1).text,
				"clause scope must not hand [2] the prefix that still names Lidocaine");
		assertEquals(preamble + "Aspirin [3]", clauses.get(2).text);
	}

	@Test
	public void splitIntoCitedSentences_compoundSentenceWithoutAListColonStaysOneSentence() {
		// Invariant guard: only a sentence that ANNOUNCES a list is split. A qualifier-shaped compound
		// keeps today's behaviour, because its later text re-qualifies one subject rather than naming
		// a sibling, and the preamble/first-item boundary is not findable there.
		List<CitationGroundingVerifier.Sentence> sentences =
				CitationGroundingVerifier.splitIntoCitedSentences("A condition [1] and a diagnosis [2].");

		assertEquals(1, sentences.size());
		assertTrue(sentences.get(0).cites(1));
		assertTrue(sentences.get(0).cites(2));
		assertFalse(sentences.get(0).isolate);
	}

	@Test
	public void enumeration_everyCitationGroundsAgainstItsOwnItem_onTheSentenceScopedDefault() {
		// Issue #278: the SHIPPED default (clauseScoped=false) graded all three false, because each
		// record was asked to entail the whole three-allergen list.
		ConjunctionAwareJudge judge = new ConjunctionAwareJudge("lidocaine", "ketoconazole", "aspirin");
		verifier.setLlmProvider(judge);

		List<RecordReference> result = verifier.verify(ENUMERATION, threeRefs(),
				allergyMappings("Ketoconazole"), FLOOR, TIER2_ON, false);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(), "[1] Lidocaine");
		assertEquals(Boolean.TRUE, result.get(1).getGrounded(), "[2] Ketoconazole");
		assertEquals(Boolean.TRUE, result.get(2).getGrounded(), "[3] Aspirin");
	}

	@Test
	public void enumeration_everyCitationGroundsUnderClauseScopeToo() {
		ConjunctionAwareJudge judge = new ConjunctionAwareJudge("lidocaine", "ketoconazole", "aspirin");
		verifier.setLlmProvider(judge);

		List<RecordReference> result = verifier.verify(ENUMERATION, threeRefs(),
				allergyMappings("Ketoconazole"), FLOOR, TIER2_ON, true);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded());
		assertEquals(Boolean.TRUE, result.get(1).getGrounded(),
				"clause scope previously left the second citation false");
		assertEquals(Boolean.TRUE, result.get(2).getGrounded(),
				"clause scope previously left the third citation false");
	}

	@Test
	public void enumeration_aCitationWhoseRecordDoesNotSupportItsOwnItemStaysUngrounded() {
		// The split must not become a rubber stamp: record 2 is a PENICILLIN allergy while the answer
		// attributes Ketoconazole to it, and that citation must still be flagged.
		ConjunctionAwareJudge judge =
				new ConjunctionAwareJudge("lidocaine", "ketoconazole", "aspirin", "penicillin");
		verifier.setLlmProvider(judge);

		List<RecordReference> result = verifier.verify(ENUMERATION, threeRefs(),
				allergyMappings("Penicillin"), FLOOR, TIER2_ON, false);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded());
		assertEquals(Boolean.FALSE, result.get(1).getGrounded(),
				"a mis-attributed allergen must still be caught after the split");
		assertEquals(Boolean.TRUE, result.get(2).getGrounded());
	}

	@Test
	public void enumeration_citationsAreVerifiedInSeparateCallsBecauseTheyShareAPreamble() {
		ConjunctionAwareJudge judge = new ConjunctionAwareJudge("lidocaine", "ketoconazole", "aspirin");
		verifier.setLlmProvider(judge);

		verifier.verify(ENUMERATION, threeRefs(), allergyMappings("Ketoconazole"),
				FLOOR, TIER2_ON, false);

		assertEquals(3, judge.statementsPerCall.size(),
				"co-batching overlapping enumeration statements lets the LLM couple their verdicts");
		for (List<String> perCall : judge.statementsPerCall) {
			assertEquals(1, perCall.size());
		}

		// Pin the statement text the judge actually RECEIVES, not just the splitter's Sentence.text:
		// the citation markers are stripped between the two, and asserting only the fragment leaves
		// that step unverified on this path.
		List<String> received = new ArrayList<String>();
		for (List<String> perCall : judge.statementsPerCall) {
			received.add(perCall.get(0));
		}
		String preamble = "Yes — the patient has the following recorded allergies: ";
		assertTrue(received.contains(preamble + "Lidocaine"), "judge saw: " + received);
		assertTrue(received.contains(preamble + "Ketoconazole"), "judge saw: " + received);
		assertTrue(received.contains(preamble + "Aspirin"), "judge saw: " + received);
	}

	@Test
	public void splitIntoCitedSentences_aColonFollowedStraightByAMarkerIsNotAnEnumeration() {
		// The guard has to test the MARKER-STRIPPED item: the raw item always ends in "[N]", so a
		// plain isEmpty() check could never fire. Here [1] contributes no name of its own, so the
		// colon is not introducing a list of named items and the sentence must fall back whole
		// rather than hand [1] a preamble-only claim that asserts nothing.
		List<CitationGroundingVerifier.Sentence> sentences = CitationGroundingVerifier
				.splitIntoCitedSentences("Recorded allergies: [1], Ketoconazole [2].");

		assertEquals(1, sentences.size(), "not an enumeration of named items -> no split");
		assertTrue(sentences.get(0).cites(1));
		assertTrue(sentences.get(0).cites(2));
		assertFalse(sentences.get(0).isolate);
	}

	@Test
	public void splitIntoCitedSentences_anItemWhoseNameStartsWithOrKeepsIt() {
		// The \b in LEADING_ITEM_SEPARATOR is load-bearing and was untested: without it the optional
		// "or" alternative eats the first two letters of a name that merely STARTS with them, and the
		// claim then asks about "phenadrine", a drug that does not exist.
		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoCitedSentences("Active drugs: Orphenadrine [1], Oxycodone [2].");

		assertEquals(2, clauses.size());
		assertEquals("Active drugs: Orphenadrine [1]", clauses.get(0).text,
				"a name beginning 'Or' must not be truncated to 'phenadrine'");
		assertEquals("Active drugs: Oxycodone [2]", clauses.get(1).text);
	}

	@Test
	public void splitIntoCitedSentences_theColonNearestTheItemsIsThePreamble() {
		// lastIndexOf, not indexOf: the introducer is the colon closest to the first item, so the
		// earlier one stays inside the preamble rather than truncating it.
		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoCitedSentences("Findings: recorded allergies: Lidocaine [1], Aspirin [2].");

		assertEquals(2, clauses.size());
		assertEquals("Findings: recorded allergies: Lidocaine [1]", clauses.get(0).text);
		assertEquals("Findings: recorded allergies: Aspirin [2]", clauses.get(1).text,
				"the whole preamble is retained, not just the text after the first colon");
	}

	@Test
	public void splitIntoCitedSentences_aColonAfterTheFirstMarkerDoesNotIntroduceAList() {
		// The colon has to precede the FIRST marker to be introducing the items. One that appears
		// later belongs to a subsequent clause and says nothing about how item 1 is bounded, so the
		// sentence keeps today's whole-sentence scoping.
		List<CitationGroundingVerifier.Sentence> sentences = CitationGroundingVerifier
				.splitIntoCitedSentences("Aspirin allergy [1] and note: severity is severe [2].");

		assertEquals(1, sentences.size());
		assertTrue(sentences.get(0).cites(1));
		assertTrue(sentences.get(0).cites(2));
		assertFalse(sentences.get(0).isolate);
	}

	@Test
	public void splitIntoCitedSentences_enumerationHandlesMultiDigitCitationIndices() {
		// The live answer that produced issue #278 cited [11], not [3] — a 4-character marker, so the
		// item boundaries are wider than the single-digit cases above. Offsets come from Matcher.end(),
		// which is width-agnostic; this pins that.
		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoCitedSentences(
						"Recorded allergies: Lidocaine [1], Ketoconazole [2], and Aspirin [11].");

		assertEquals(3, clauses.size());
		assertEquals("Recorded allergies: Aspirin [11]", clauses.get(2).text);
		assertTrue(clauses.get(2).cites(11));
		assertFalse(clauses.get(2).cites(1));
	}

	@Test
	public void splitIntoCitedSentences_enumerationRepeatingOneIndexYieldsAFragmentPerMARKER() {
		// The split walks MARKERS, while the no-split guard counts DISTINCT cited indexes — so a list
		// naming one record twice produces two fragments attributed to the same index. That is the
		// pre-existing multi-candidate shape (selectClaim cosine-picks between them), reached here by a
		// new route, so pin that it is produced rather than crashing or silently dropping a fragment.
		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoCitedSentences("Recorded allergies: Aspirin [1], Ketoconazole [2], and aspirin again [1].");

		assertEquals(3, clauses.size(), "one fragment per marker, not per distinct index");
		assertEquals("Recorded allergies: Aspirin [1]", clauses.get(0).text);
		assertEquals("Recorded allergies: Ketoconazole [2]", clauses.get(1).text);
		assertEquals("Recorded allergies: aspirin again [1]", clauses.get(2).text);
		assertTrue(clauses.get(0).cites(1));
		assertTrue(clauses.get(2).cites(1), "both fragments of the repeated index are attributed to it");
	}

	@Test
	public void enumeration_repeatingOneIndexStillProducesAVerdictForThatCitation() {
		// End-to-end guard on the same shape: two candidate fragments must not leave the citation
		// unverified. selectClaim picks one by cosine and Tier-2 still runs on it.
		ConjunctionAwareJudge judge = new ConjunctionAwareJudge("aspirin", "ketoconazole");
		verifier.setLlmProvider(judge);
		String answer = "Recorded allergies: Aspirin [1], Ketoconazole [2], and aspirin again [1].";

		List<RecordReference> result = verifier.verify(answer,
				new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(2))),
				Arrays.asList(mapping(1, "Allergy: Aspirin (drug allergen)"),
						mapping(2, "Allergy: Ketoconazole (drug allergen)")),
				FLOOR, TIER2_ON, false);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(), "the repeated index still gets a verdict");
		assertEquals(Boolean.TRUE, result.get(1).getGrounded());
	}

	@Test
	public void splitIntoCitedSentences_doesNotSplitWhenTheSubjectSitsInsideTheFirstItem() {
		// The split is only safe while the shared preamble carries the SUBJECT. Here the colon is a
		// bare lead-in and the subject ("the patient has") lives inside item 1, so splitting would hand
		// item 2 the claim "Findings: asthma" -- stripped of the subject. A family-history record for
		// the mother's asthma entails THAT, so a citation the whole-sentence claim correctly refused
		// would be published grounded=true. Fail open in a verification feature, and precisely the flip
		// Tier-2 exists to catch, so this shape must keep whole-sentence scoping.
		List<CitationGroundingVerifier.Sentence> sentences = CitationGroundingVerifier
				.splitIntoCitedSentences("Findings: the patient has diabetes [1] and asthma [2].");

		assertEquals(1, sentences.size(), "a clause-shaped first item means the preamble is not the subject");
		assertTrue(sentences.get(0).cites(1));
		assertTrue(sentences.get(0).cites(2));
		assertFalse(sentences.get(0).isolate);
	}

	@Test
	public void splitIntoCitedSentences_doesNotSplitWhenAnItemCarriesItsOwnSubjectWithinTheWordBound() {
		// CLAUSE_MARKER, not length, is the subject test — and this is the case that establishes why.
		// "he has diabetes" sits comfortably inside MAX_ENUMERATION_ITEM_WORDS (a runaway-text backstop,
		// not a grammar test), so only its grammar can refuse it; without that net item 2's claim loses
		// the subject and becomes "Findings: asthma". Written when the bound was 3 and this cleared it by
		// exactly fitting, which is what showed length could not do this job at any value.
		List<CitationGroundingVerifier.Sentence> sentences = CitationGroundingVerifier
				.splitIntoCitedSentences("Findings: he has diabetes [1] and asthma [2].");

		assertEquals(1, sentences.size(), "a pronoun-and-verb item is a clause, so the preamble is not the subject");
		assertTrue(sentences.get(0).cites(1));
		assertTrue(sentences.get(0).cites(2));
		assertFalse(sentences.get(0).isolate);
	}

	@Test
	public void splitIntoCitedSentences_aQualifiedDrugNameIsStillANameNotAClause() {
		// The clause test must not swallow the parenthetical-qualified item shape the live answers do
		// produce: three words, no pronoun, no verb — a name, so the list still splits.
		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoCitedSentences(
						"Recorded allergies: Aspirin (drug allergen) [1], Ketoconazole (drug allergen) [2].");

		assertEquals(2, clauses.size());
		assertEquals("Recorded allergies: Aspirin (drug allergen) [1]", clauses.get(0).text);
		assertEquals("Recorded allergies: Ketoconazole (drug allergen) [2]", clauses.get(1).text);
	}

	@Test
	public void splitIntoCitedSentences_admitsALongDrugNameCarryingNoClauseMarker() {
		// The coverage the raised bound buys, and the reason it was raised: 1190 of the 7452 names the
		// shipped KB publishes are longer than three words, and refusing them left a real citation
		// mis-scoped for no safety gain — a long noun phrase carries no subject, so splitting is correct.
		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoCitedSentences("Recorded allergies: Belladonna alkaloids with phenobarbital [1], "
						+ "Brompheniramine, phenylephrine and codeine [2].");

		assertEquals(2, clauses.size(), "a multi-word drug name is a name, not a clause");
		assertEquals("Recorded allergies: Belladonna alkaloids with phenobarbital [1]", clauses.get(0).text);
		assertEquals("Recorded allergies: Brompheniramine, phenylephrine and codeine [2]", clauses.get(1).text);
	}

	@Test
	public void splitIntoCitedSentences_refusesAnItemThatRunsPastTheLengthBackstop() {
		// What the bound still does once CLAUSE_MARKER owns the subject test: stop runaway text becoming
		// a "claim". Nine words, deliberately with no pronoun and no finite verb, so only length can
		// refuse it — this is the test that pins MAX_ENUMERATION_ITEM_WORDS at all.
		List<CitationGroundingVerifier.Sentence> sentences = CitationGroundingVerifier
				.splitIntoCitedSentences("Recorded allergies: one two three four five six seven eight nine [1], "
						+ "Aspirin [2].");

		assertEquals(1, sentences.size(), "past the backstop the sentence keeps whole-sentence scoping");
		assertTrue(sentences.get(0).cites(1));
		assertTrue(sentences.get(0).cites(2));
	}

	@Test
	public void splitIntoCitedSentences_anOrNameKeepsItsPrefixInALaterPositionToo() {
		// Position matters to LEADING_ITEM_SEPARATOR: item 1 follows the colon (whitespace only) while a
		// later item follows ", " or ", and ". Only the later position exercises the conjunction
		// alternative against a name that starts with those letters, so pin it there as well as first.
		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoCitedSentences("Active drugs: Aspirin [1], Orphenadrine [2], and Ornidazole [3].");

		assertEquals(3, clauses.size());
		assertEquals("Active drugs: Orphenadrine [2]", clauses.get(1).text,
				"a comma-separated name beginning 'Or' must survive the separator strip");
		assertEquals("Active drugs: Ornidazole [3]", clauses.get(2).text,
				"and so must one that follows ', and '");
	}

	@Test
	public void enumeration_groundsOnTheTier1OnlyPathToo_whichIsTheShippedEntailmentDefault() {
		// chartsearchai.grounding.entailment.enabled defaults to FALSE, so Tier-1 cosine alone decides on
		// the commonest configuration — and the split changes which TEXT is embedded there, not just
		// which statement Tier-2 judges. Every other enumeration test runs with Tier-2 on, so this path
		// was altered untested. The whole sentence is registered ORTHOGONAL to all three records, so
		// these verdicts can only come from each citation being scored against its own item.
		String preamble = "Yes — the patient has the following recorded allergies: ";
		embeddings.register(ENUMERATION, AXIS_B);
		embeddings.register(preamble + "Lidocaine [1]", AXIS_A);
		embeddings.register(preamble + "Ketoconazole [2]", AXIS_A);
		embeddings.register(preamble + "Aspirin [3]", AXIS_A);
		embeddings.register("Allergy: Lidocaine (drug allergen)", AXIS_A);
		embeddings.register("Allergy: Ketoconazole (drug allergen)", AXIS_A);
		embeddings.register("Allergy: Aspirin (drug allergen)", AXIS_A);

		List<RecordReference> result = verifier.verify(ENUMERATION, threeRefs(),
				allergyMappings("Ketoconazole"), FLOOR, TIER1_ONLY, false);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(), "[1] scored against its own item");
		assertEquals(Boolean.TRUE, result.get(1).getGrounded(), "[2] scored against its own item");
		assertEquals(Boolean.TRUE, result.get(2).getGrounded(), "[3] scored against its own item");
	}

	@Test
	public void splitIntoCitedSentences_aNegatedPreamblePropagatesToEveryItem() {
		// The preamble is shared, so a qualifier sitting in it must reach every item's claim. If it did
		// not, "No recorded allergies except: X [1], Y [2]" would hand each citation a bare drug name and
		// a record for an allergy the patient does NOT have could ground true. The preamble is provably
		// marker-free (the colon precedes the first marker), which is what makes this propagation total.
		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoCitedSentences("No recorded allergies except: Lidocaine [1], Aspirin [2].");

		assertEquals(2, clauses.size());
		assertEquals("No recorded allergies except: Lidocaine [1]", clauses.get(0).text);
		assertEquals("No recorded allergies except: Aspirin [2]", clauses.get(1).text,
				"a preamble qualifier must not be dropped from later items");
	}

	// ---- a COMPOUND claim unit: citations attached to different pieces of one statement (#302) ----

	/** The colon-less medication list #302 measured, on the module's most common question. */
	private static final String COLON_LESS_LIST =
			"The patient is currently taking Salicylic acid [1] and Methotrexate [2].";

	private List<RecordMapping> twoOrderMappings() {
		return Arrays.asList(mapping(1, "Drug order: Salicylic acid"),
				mapping(2, "Drug order: Methotrexate"));
	}

	private List<RecordReference> twoRefs() {
		return new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(2)));
	}

	@Test
	public void compoundClaim_aCorrectCitationIsNoLongerPublishedUnsupported() {
		// Issue #302. No colon, so splitEnumeration refuses the split and BOTH citations are handed
		// the whole conjunction: each order record is asked to entail a statement that also names the
		// OTHER drug, a correct judge says no to both, and the wire published grounded=false on two
		// correct, active, unvoided citations. The verdict was right; the question was the wrong size.
		//
		// This case EXERCISES the rebuild path — with Tier-2 skipped the reference falls through to the
		// lazy Tier-1 block, which rebuilds its Tier1Result in cosineVerdict — but do not read it as a
		// guard on that rebuild. verify() decides from a snapshot taken at claim selection, so this
		// stays green whether or not cosineVerdict carries the flag across; the rebuild carries it for
		// a later reader's sake and NOTHING in this suite fails if that is dropped. The hazard the
		// snapshot removes is real (reading the flag back off the rebuild instead would fail OPEN,
		// certifying where the module used to flag) and it is removed structurally, not pinned.
		//
		// Kept for what it IS rather than for what it uniquely catches: a kill-set measurement over
		// the api suite found every mutation this case detects is detected by a sibling too, mostly
		// because one Disposition drives both the Tier-2 exclusion and what Pass 2 publishes, so any
		// mutation of it also moves the judge-call count a sibling asserts on.
		// It is #302's own reported arrangement and its regression case, which is reason enough — but
		// do not cite it as the guard for anything; the exclusive guards are named on the cases that
		// hold them.
		ConjunctionAwareJudge judge = new ConjunctionAwareJudge("salicylic acid", "methotrexate");
		verifier.setLlmProvider(judge);
		embeddings.register(COLON_LESS_LIST, AXIS_A);
		embeddings.register("Drug order: Salicylic acid", AXIS_A);
		embeddings.register("Drug order: Methotrexate", AXIS_A);

		List<RecordReference> result = verifier.verify(COLON_LESS_LIST, twoRefs(), twoOrderMappings(),
				FLOOR, TIER2_ON, false);

		assertNull(result.get(0).getGrounded(),
				"[1] Salicylic acid: the module cannot isolate what this citation claims, so it must "
						+ "say so rather than publish the conjunction's refusal as this citation's verdict");
		assertNull(result.get(1).getGrounded(), "[2] Methotrexate: likewise");
		assertEquals(0, judge.statementsPerCall.size(),
				"a record asked to entail a conjunction it answers for only part of returns no whether "
						+ "the citation is right or wrong, so the pair must not be put to the judge at all");
	}

	@Test
	public void compoundClaim_publishesNothingWhicheverWayTheJudgeWouldHaveAnswered() {
		// The cell that decided this rule's shape, and the one the suite could not see while every
		// compound case used ConjunctionAwareJudge — an idealised stub that always refuses a
		// conjunction, so "a correct judge says no" was assumed rather than exercised. A LENIENT judge
		// is the real risk: #106 measured 4/4 role-swapped recitations judged entailed.
		//
		// Under main, cosine-FAIL + judge-YES published TRUE, the judge rescuing a score diluted by the
		// items this record is not cited for. An earlier draft of #302 skipped Tier-2 and kept the
		// cosine FAIL, which turned that cell into a published FALSE — Unsupported on a correct, active
		// citation, the very harm #302 exists to remove. So a compound claim unit publishes nothing in
		// either direction. It does NOT earn an exclusive kill, and says so rather than implying one: a
		// mutation table over the api suite found it never reddens alone, because its assertions need a
		// published verdict, a verdict needs an embed or a judge call, and the siblings on this same
		// arrangement assert both counts are zero. What it is for is the historical regression — restore
		// the lazy cosine and publish it demote-only and this reddens, with two siblings — and for
		// carrying the lenient-judge stub that showed the premise "a correct judge says no" was never
		// exercised.
		StubLlmProvider lenient = new StubLlmProvider(Boolean.TRUE);
		verifier.setLlmProvider(lenient);
		embeddings.register(COLON_LESS_LIST, AXIS_A);
		embeddings.register("Drug order: Salicylic acid", AXIS_B);
		embeddings.register("Drug order: Methotrexate", AXIS_B);

		List<RecordReference> result = verifier.verify(COLON_LESS_LIST, twoRefs(), twoOrderMappings(),
				FLOOR, TIER2_ON, false);

		assertEquals(0, lenient.calls, "the judge is not asked about a compound claim unit");
		assertNull(result.get(0).getGrounded(), "so the yes that would have rescued it is never given");
		assertNull(result.get(1).getGrounded());
		// Note what this case does NOT pin: here the claim sentence is unambiguous, so the cosine is
		// never computed either and the null comes from the lazy-Tier-1 skip rather than from Pass 2.
		// The eagerly scored path is where Pass 2 does the work — see
		// compoundClaim_anEagerlyScoredCosineFailIsWithheldToo.
	}

	@Test
	public void compoundClaim_anEagerlyScoredCosineFailIsWithheldToo() {
		// The other half of "publishes nothing", and the only case that reaches it. When one record is
		// cited by several sentences, selectClaim scores the cosine EAGERLY to choose between them, so
		// a verdict already exists by the time Pass 2 runs — the lazy-Tier-1 skip cannot produce the
		// null there, and only Pass 2's unverifiable branch can. Weaken that branch back to demoting a
		// TRUE alone and this case reddens while every other compound case stays green, because they
		// all take the deferred path where no cosine is computed at all.
		//
		// Record 1's best candidate is the compound sentence (0.35) over the other (-0.94), and 0.35
		// is under the 0.40 floor — a diluted score of exactly the kind a conjunction produces.
		String answer = "The patient is currently taking Salicylic acid [1] and Methotrexate [2]. "
				+ "Salicylic acid remains active [1].";
		ConjunctionAwareJudge judge = new ConjunctionAwareJudge("salicylic acid", "methotrexate");
		verifier.setLlmProvider(judge);
		embeddings.register("The patient is currently taking Salicylic acid [1] and Methotrexate [2].",
				AXIS_A);
		embeddings.register("Salicylic acid remains active [1].", new float[] { 0f, -1f });
		embeddings.register("Drug order: Salicylic acid", new float[] { 0.35f, 0.9368f });
		embeddings.register("Drug order: Methotrexate", AXIS_A);

		List<RecordReference> result = verifier.verify(answer, twoRefs(), twoOrderMappings(),
				FLOOR, TIER2_ON, false);

		assertNull(result.get(0).getGrounded(),
				"a sub-floor cosine against the conjunction is dilution, not evidence against [1]");
		assertNull(result.get(1).getGrounded());
	}

	@Test
	public void compoundClaim_outranksTheReferenceGroupRuleWhereBothApply() {
		// The precedence between the two dispositions, which nothing pinned. A reference-group citation
		// inside a compound claim unit is UNVERIFIABLE, not merely DEMOTE_ONLY: reference material keeps
		// its cosine FAIL, a compound unit does not, and where both apply the stronger rule wins. Swap
		// the two arms of the Pass-1 ternary and the whole api suite stays green while this citation
		// publishes false and spends an embedding pass — so the ordering was prose, defended only by
		// #201 happening to withhold reference-group verdicts at the wire, which is a coincidence at
		// another layer rather than a guard.
		ConjunctionAwareJudge judge = new ConjunctionAwareJudge("salicylic acid", "methotrexate");
		verifier.setLlmProvider(judge);
		embeddings.register(COLON_LESS_LIST, AXIS_A);
		embeddings.register("Drug order: Salicylic acid", AXIS_A);
		// record 2's text is left unregistered, so its cosine is 0 — a Tier-1 FAIL that the
		// reference-group rule would publish and the compound rule withholds.

		List<RecordReference> result = verifier.verify(COLON_LESS_LIST, twoRefs(),
				Arrays.asList(mapping(1, "Drug order: Salicylic acid"),
						drugReferenceMapping(2, "warfarin reference record")),
				FLOOR, TIER2_ON, false);

		assertNull(result.get(1).getGrounded(),
				"the compound rule outranks demote-only, so even the off-topic FAIL is withheld");
		assertEquals(0, embeddings.embedCalls, "and no cosine is computed to produce it");
		assertEquals(0, judge.statementsPerCall.size());
	}

	@Test
	public void compoundClaim_spendsNoEmbeddingOnAVerdictItWillNotPublish() {
		// The cost half of the same decision. Publishing nothing means the cosine is never needed, so
		// the lazy Tier-1 block skips these references entirely — which is what keeps the rule from
		// adding embedding passes on the module's most common answer shape.
		ConjunctionAwareJudge judge = new ConjunctionAwareJudge("salicylic acid", "methotrexate");
		verifier.setLlmProvider(judge);
		embeddings.register(COLON_LESS_LIST, AXIS_A);
		embeddings.register("Drug order: Salicylic acid", AXIS_A);
		embeddings.register("Drug order: Methotrexate", AXIS_A);

		verifier.verify(COLON_LESS_LIST, twoRefs(), twoOrderMappings(), FLOOR, TIER2_ON, false);

		assertEquals(0, embeddings.embedCalls,
				"no tier is asked, so no embedding is spent on a verdict Pass 2 would discard");
		assertEquals(0, judge.statementsPerCall.size());
	}

	@Test
	public void compoundClaim_leavesTheTier1OnlyPathUntouched() {
		// The withholding is deliberately NOT mode-uniform, unlike the reference-group rule beside it.
		// #302's defect is Tier-2's refusal of a conjunction, which does not exist when entailment is
		// off: there every verdict is cosine against the claim text, a compound unit's is no different
		// in kind, and sentence scope has always compared against the whole compound sentence
		// (clauseScoped is this module's remedy for that, and #302 does not change it). Withholding here
		// would cost a correct citation its verdict for no defect removed: with no judge to refuse the
		// conjunction, there is no wrong verdict to suppress.
		embeddings.register(COLON_LESS_LIST, AXIS_A);
		embeddings.register("Drug order: Salicylic acid", AXIS_A);
		embeddings.register("Drug order: Methotrexate", AXIS_A);

		List<RecordReference> result = verifier.verify(COLON_LESS_LIST, twoRefs(), twoOrderMappings(),
				FLOOR, TIER1_ONLY, false);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(),
				"with no Tier-2 refusal to withhold, the cosine pass still verifies");
		assertEquals(Boolean.TRUE, result.get(1).getGrounded(), "and so does the second citation's");
	}

	@Test
	public void compoundClaim_doesNotConsumeTheEntailmentCapOfSingleClaimCitations() {
		// Cap-boundary pin, mirroring drugReference_doesNotConsumeTheEntailmentCapOfChartCitations: the
		// compound sentence comes FIRST and contributes TWO citations, followed by exactly cap-many
		// single-claim ones. If the exclusion happened inside the budget branch, those two would eat two
		// slots and the last TWO single-claim citations would fall past the cap onto their Tier-1 FALSE.
		int cap = ChartSearchAiConstants.GROUNDING_ENTAILMENT_MAX_CHECKS;
		StringBuilder answer = new StringBuilder(COLON_LESS_LIST).append(" ");
		List<RecordReference> refs = new ArrayList<RecordReference>(twoRefs());
		List<RecordMapping> maps = new ArrayList<RecordMapping>(twoOrderMappings());
		for (int i = 1; i <= cap; i++) {
			answer.append("claim ").append(i).append(" [").append(i + 2).append("]. ");
			refs.add(reference(i + 2));
			maps.add(mapping(i + 2, "record " + i));
		}
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(answer.toString(), refs, maps, FLOOR, TIER2_ON);

		assertEquals(cap, llm.calls,
				"the single-claim citations alone fill the cap; the excluded compound pair must not count");
		assertEquals(Boolean.TRUE, result.get(refs.size() - 1).getGrounded(),
				"the last single-claim citation must still get its Tier-2 verdict — two consumed slots "
						+ "would leave it on its Tier-1 FALSE");
	}

	@Test
	public void compoundClaim_withNoTier1EmbedderGetsNoVerdictFromEitherTier() {
		// The combination nothing pinned. Both this class and the entailment GP's own description say
		// entailment grounding works with no Tier-1 embedding model — true for a single-claim citation
		// (tier2_absentEmbedder_singleCitingSentenceStillGetsTier2Verdict) and NOT for a compound one,
		// which #302 keeps out of Tier-2 and which therefore has only the absent tier left. It renders
		// unverified, which is the honest answer, but it is a real loss on that deployment and it is
		// pinned here so it cannot change unnoticed.
		verifier.setEmbedder(null);
		ConjunctionAwareJudge judge = new ConjunctionAwareJudge("salicylic acid", "methotrexate");
		verifier.setLlmProvider(judge);

		List<RecordReference> result = verifier.verify(COLON_LESS_LIST, twoRefs(), twoOrderMappings(),
				FLOOR, TIER2_ON, false);

		assertEquals(0, judge.statementsPerCall.size(), "Tier-2 is not asked for a compound claim");
		assertNull(result.get(0).getGrounded(), "and no tier is left to answer");
		assertNull(result.get(1).getGrounded());
	}

	@Test
	public void compoundClaim_isDetectedOnTheAmbiguousClaimSelectionBranchToo() {
		// tier1.compoundClaim is produced at TWO sites in selectClaim — the deterministic branch, when
		// exactly one sentence cites the record, and the cosine argmax when several do. Every other
		// case in this class gives each citation a single candidate, so the argmax branch was reachable
		// and unpinned: setting compoundClaim false there left the whole api suite green while
		// reinstating #302's symptom for any answer that cites one record from two sentences.
		//
		// Here [1] is cited by both sentences and the compound one is registered as its cosine-best, so
		// selection goes through the argmax. If the flag were lost there, [1] would be put to the judge
		// against the conjunction and published false — the defect, on a correct citation.
		String answer = "The patient is currently taking Salicylic acid [1] and Methotrexate [2]. "
				+ "Salicylic acid remains active [1].";
		ConjunctionAwareJudge judge = new ConjunctionAwareJudge("salicylic acid", "methotrexate");
		verifier.setLlmProvider(judge);
		embeddings.register("The patient is currently taking Salicylic acid [1] and Methotrexate [2].",
				AXIS_A);
		embeddings.register("Salicylic acid remains active [1].", AXIS_B);
		embeddings.register("Drug order: Salicylic acid", AXIS_A);
		embeddings.register("Drug order: Methotrexate", AXIS_A);

		List<RecordReference> result = verifier.verify(answer, twoRefs(), twoOrderMappings(),
				FLOOR, TIER2_ON, false);

		assertEquals(0, judge.statementsPerCall.size(),
				"[1]'s claim was selected by cosine and is still a compound unit, so it is not asked");
		assertNull(result.get(0).getGrounded(), "[1], selected through the argmax branch");
		assertNull(result.get(1).getGrounded(), "[2], selected deterministically");
	}

	@Test
	public void anArrayOnlyCitationAttributedToACompoundClaimIsWithheldWithIt() {
		// A citation the model put only in the structured citations array has no marker of its own, so
		// selectClaim attributes it to whichever sentence matches best. Where that is a compound claim
		// unit, its statement asserts more than this record is responsible for exactly as it does for
		// the inline citations, so the judge's refusal is as uninformative here — and it is withheld
		// with them. Gating the rule on "does this claim unit cite ME" instead would send an array-only
		// citation back to the judge against the whole conjunction, which is the shape #284 exists to
		// complain about. What IS given up is stated rather than denied: under entailment nothing is
		// published for this citation, so a genuinely off-topic one is no longer flagged either — the
		// cosine that would flag it is measured against the same conjunction and is diluted for a
		// correct record too, which is why it is not better evidence.
		ConjunctionAwareJudge judge = new ConjunctionAwareJudge("salicylic acid", "methotrexate");
		verifier.setLlmProvider(judge);
		embeddings.register(COLON_LESS_LIST, AXIS_A);
		embeddings.register("Drug order: Salicylic acid", AXIS_A);
		embeddings.register("Drug order: Methotrexate", AXIS_A);
		embeddings.register("Condition: Asthma", AXIS_A);

		List<RecordReference> result = verifier.verify(COLON_LESS_LIST,
				new ArrayList<RecordReference>(
						Arrays.asList(reference(1), reference(2), reference(3))),
				Arrays.asList(mapping(1, "Drug order: Salicylic acid"),
						mapping(2, "Drug order: Methotrexate"), mapping(3, "Condition: Asthma")),
				FLOOR, TIER2_ON, false);

		assertEquals(0, judge.statementsPerCall.size(),
				"the array-only citation reaches the judge only through the compound claim unit, so it "
						+ "is excluded with the rest of them");
		assertNull(result.get(2).getGrounded(),
				"[3], cited only in the array: withheld, not published unsupported");
	}

	@Test
	public void coCitationOfThreeRecordsIsStillGraded() {
		// Both other co-citation cases use exactly TWO adjacent markers, which leaves
		// claimTextSeparatesCitations' loop advance unguarded: delete `previousEnd = marker.end()` and
		// the whole reactor stays green while every 3-or-more-marker co-citation reclassifies as a
		// compound claim unit, because the slice for the second pair then runs from the FIRST marker's
		// end and picks up the intervening markers as claim text.
		//
		// That is not a hypothetical shape. It is the one the class javadoc, README and ADR all name
		// (`Infections [5], [12], [15]`), and the module manufactures it itself —
		// LlmAnswerExtractor.normalizeSlashCitations joins a corroborated group of ANY length with
		// ", ". Losing it would withhold Tier-2 for the module's own normalizer output silently.
		String answer = "The patient has recurrent infections [1], [2], [3].";
		embeddings.register(answer, AXIS_A);
		embeddings.register("record one", AXIS_A);
		embeddings.register("record two", AXIS_A);
		embeddings.register("record three", AXIS_A);
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(answer, threeRefs(),
				Arrays.asList(mapping(1, "record one"), mapping(2, "record two"),
						mapping(3, "record three")),
				FLOOR, TIER2_ON, false);

		assertEquals(3, llm.calls, "three records co-cited for one claim are each asked about it");
		assertEquals(1, llm.batches, "one claim unit, so they co-batch");
		assertEquals(Boolean.TRUE, result.get(0).getGrounded());
		assertEquals(Boolean.TRUE, result.get(1).getGrounded());
		assertEquals(Boolean.TRUE, result.get(2).getGrounded(), "the third marker is the point");
	}

	@Test
	public void coCitationJoinedByAConjunctionIsAlsoStillGraded() {
		// The comma register below is the one LlmAnswerExtractor.normalizeSlashCitations manufactures,
		// so it is the one least likely to be edited away — and pinning only it leaves the
		// coordinating-conjunction half of LEADING_ITEM_SEPARATOR unguarded: replacing that strip with
		// a plain punctuation strip reclassifies this sentence as a compound claim and silences its
		// Tier-2 verdict entirely. Mutate itemSlice that way — that is where the separator lives since
		// the boundary was factored out — and read the failures: this case is the one that speaks for
		// the co-citation property, and splitEnumeration's own cases redden beside it because they
		// read the same boundary — which is the whole point of factoring it out.
		String answer = "The patient has recurrent infections [1] and [2].";
		embeddings.register(answer, AXIS_A);
		embeddings.register("record one", AXIS_A);
		embeddings.register("record two", AXIS_A);
		llm.verdict = Boolean.FALSE;

		List<RecordReference> result = verifier.verify(answer, twoRefs(),
				Arrays.asList(mapping(1, "record one"), mapping(2, "record two")),
				FLOOR, TIER2_ON, false);

		assertEquals(2, llm.calls, "markers joined by 'and' with no claim text between are co-citation");
		assertEquals(Boolean.FALSE, result.get(0).getGrounded());
		assertEquals(Boolean.FALSE, result.get(1).getGrounded());
	}

	@Test
	public void coCitationIsNotACompoundClaimAndKeepsItsFullTier2Grading() {
		// Several citations of ONE claim is a shape this module MANUFACTURES: for a corroborated group
		// LlmAnswerExtractor.normalizeSlashCitations rewrites "Infections [5/12/15]" into adjacent
		// markers, "Infections [5], [12], [15]". Nothing but a list separator stands between the
		// markers, so every record is cited for the same whole statement — that statement IS each
		// citation's own claim, the judge's question is well-formed, and both directions of its answer
		// must still be published. A predicate keyed on "more than one citation" would silence it.
		// Stubbed TRUE deliberately, and the sibling above stubs FALSE: since the reversal a compound
		// unit withholds in BOTH directions, so either stub would redden if co-citation were
		// misclassified. TRUE is the direction that also proves the verdict is published rather than
		// merely not-flagged.
		//
		// Honest about what this case is worth: it is DOMINATED by the sub-shape case below, which
		// reddens on every mutation this one does and on one more. compoundClaim() reaches the same
		// answer by the same route for both — the inter-marker text is ", " either way, and
		// splitEnumeration's differing exit (no colon here, the no-own-text guard there) has no
		// bearing on the classification. It is kept for the register rather than the coverage: this
		// is the exact shape LlmAnswerExtractor.normalizeSlashCitations emits, which never carries a
		// colon, so the case that documents the manufactured shape is this one. Do not cite it as a
		// second guard.
		String answer = "The patient has recurrent infections [1], [2].";
		embeddings.register(answer, AXIS_A);
		embeddings.register("record one", AXIS_A);
		embeddings.register("record two", AXIS_A);
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(answer, twoRefs(),
				Arrays.asList(mapping(1, "record one"), mapping(2, "record two")),
				FLOOR, TIER2_ON, false);

		assertEquals(2, llm.calls, "co-cited records are each asked their own well-formed question");
		assertEquals(1, llm.batches, "one claim unit, so its citations co-batch rather than isolate");
		assertEquals(Boolean.TRUE, result.get(0).getGrounded(),
				"and a TRUE is published — co-citation is not demote-only");
		assertEquals(Boolean.TRUE, result.get(1).getGrounded());
	}

	@Test
	public void theSubShapeWhoseSplitIsRefusedForWantOfOwnTextIsCoCitationAndStaysGraded() {
		// #302's closing bullet names this shape and leaves its remedy unsettled: with the colon present
		// the split is attempted and refused by splitEnumeration's no-own-text guard, because [2]
		// contributes nothing of its own beyond its marker. It is left graded
		// here deliberately — it is textually indistinguishable from the normalizer's co-citation shape
		// above, and reading it as a conjunction with an unnamed second item would silence a verdict
		// that is well-formed whenever it is not.
		String answer = "Recorded medications: Salicylic acid [1], [2].";
		embeddings.register(answer, AXIS_A);
		embeddings.register("Drug order: Salicylic acid", AXIS_A);
		embeddings.register("Drug order: Salicylic acid 300mg", AXIS_A);
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(answer, twoRefs(),
				Arrays.asList(mapping(1, "Drug order: Salicylic acid"),
						mapping(2, "Drug order: Salicylic acid 300mg")),
				FLOOR, TIER2_ON, false);

		assertEquals(2, llm.calls, "adjacent markers are co-citation, not a compound claim");
		assertEquals(Boolean.TRUE, result.get(0).getGrounded());
		assertEquals(Boolean.TRUE, result.get(1).getGrounded());
		// Pin the refusal itself, not just its downstream verdict: unsplit, the two citations share
		// ONE claim unit, so they are non-isolate and share one batch carrying one statement twice.
		// A split would make them isolate fragments — two calls, and two different statements, the
		// second of them preamble-only. Delete splitEnumeration's no-own-text guard and this reddens;
		// the verdict assertions above do not, because each fragment would still cite one record and
		// the stub would still say TRUE.
		assertEquals(1, llm.batches, "a refused split leaves one claim unit, so its citations co-batch");
		assertEquals(2, llm.statementsPerCall.get(0).size());
		assertEquals(llm.statementsPerCall.get(0).get(0), llm.statementsPerCall.get(0).get(1),
				"both citations are asked about the same whole sentence");
	}

	@Test
	public void splitIntoCitedSentences_aNegationInsideAnItemStaysWithThatItemOnly() {
		// The mirror case: a qualifier sitting in ONE item must not leak to its siblings and must not be
		// stripped from its own. "not sulfa" keeps its negation, so a sulfa-allergy record cannot ground
		// it; "penicillin" does not inherit the negation, so a penicillin record still can.
		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoCitedSentences("Recorded allergies: penicillin [1], not sulfa [2].");

		assertEquals(2, clauses.size());
		assertEquals("Recorded allergies: penicillin [1]", clauses.get(0).text,
				"item 1 must not inherit item 2's negation");
		assertEquals("Recorded allergies: not sulfa [2]", clauses.get(1).text,
				"the separator strip must not eat the negation");
	}

	// ---- composite claims: a chart citation whose statement also rests on module-supplied
	// ---- reference material (issue #284) ----

	/**
	 * The chart half of a drug-safety answer's claim, as the REAL pipeline renders it: the injected
	 * active-order record for the co-medication the finding names. Numbered just after the finding so
	 * the two citation numberings cannot collide, exactly as the cap-boundary tests above do.
	 */
	private static RecordMapping coMedicationRecord(RecordMapping finding) {
		// The one active-order mapping in this file that is NOT taken whole from the injector, and the
		// reason is the numbering: this record has to sit just after a finding built separately, while
		// the injector numbers from the chart it injects into. So the TEXT comes off the real render
		// chain and the index is chosen here.
		//
		// It states orderDrugNamed TRUE rather than leaving it null, because "Simvastatin 20mg" IS a
		// drug name and a null would say the module cannot tell — which would be a fact about this
		// fixture rather than about the record. Nothing here turns on it: issue #284's branch reads no
		// record text and grades this citation either way. A codes-only co-medication is a different
		// arrangement and is not what these cases are about.
		return new RecordMapping(finding.getIndex() + 1,
				ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER, "order-uuid-simvastatin", null,
				org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport
						.injectedActiveOrderText("order-uuid-simvastatin", "Simvastatin 20mg"),
				null, 0, null, null, null, null, null, null, null, Boolean.TRUE);
	}

	/**
	 * The judge as it behaved on the live cells in issue #284: the co-medication record names its own
	 * drug and not the one being asked about, so it does not entail a statement asserting an
	 * interaction between the two. A correct judge answers "no" here — that is the whole point, and
	 * why the "no" says nothing about the CITATION.
	 */
	private ConjunctionAwareJudge useInteractionJudge() {
		ConjunctionAwareJudge judge = new ConjunctionAwareJudge("clarithromycin", "simvastatin");
		verifier.setLlmProvider(judge);
		return judge;
	}

	/** The enumerating answer of issue #284's shape A, in the wording the current head produces
	 *  (the drug name repeated rather than a pronoun, so {@code CLAUSE_MARKER} does not veto the
	 *  split). */
	private static String compositeEnumeration(RecordMapping order, RecordMapping finding) {
		return "Clarithromycin can be given, with one caution: Clarithromycin interacts with "
				+ "active order Simvastatin [" + order.getIndex() + "], a Major problem ["
				+ finding.getIndex() + "].";
	}

	private static List<RecordReference> refsFor(RecordMapping order, RecordMapping finding) {
		return new ArrayList<RecordReference>(
				Arrays.asList(reference(order.getIndex()), reference(finding.getIndex())));
	}

	@Test
	public void compositeClaim_chartCitationCoCitedWithAFindingRendersUnverifiedNotUnsupported() {
		// Issue #284 shape A, measured live: the answer's claim rests on TWO records — the chart record
		// for the co-medication and the module's own finding for the RELATIONSHIP — so no single record
		// entails it and a correct judge answers "no" for the chart half BY CONSTRUCTION. Publishing
		// that "no" renders the correct citation as "Unsupported", in red. The claim unit here is
		// already the minimal one: splitEnumeration hands [order] its own item, and issue #284's
		// comment measured that narrowing no further removes the reference-supplied half.
		RecordMapping finding = realSafetyFinding();
		RecordMapping order = coMedicationRecord(finding);
		// The finding's own citation recites it, so its lazy Tier-1 pass demotes to null — the live
		// table this case reproduces, where [239] published null and only the chart cite was false.
		embeddings.register("Clarithromycin can be given, with one caution: a Major problem ["
				+ finding.getIndex() + "]", AXIS_A);
		embeddings.register(finding.getText(), AXIS_A);
		useInteractionJudge();

		List<RecordReference> result = verifier.verify(compositeEnumeration(order, finding),
				refsFor(order, finding), Arrays.asList(order, finding), FLOOR, TIER2_ON, false);

		assertNull(result.get(0).getGrounded(),
				"a negative guaranteed by the composition is not a statement about the citation, "
						+ "so it must render unverified rather than unsupported");
		assertNull(result.get(1).getGrounded(), "the finding's own demote-only verdict is unchanged");
	}

	@Test
	public void compositeClaim_aCitationOnlyInTheCitationsArrayRendersUnverifiedNotUnsupported() {
		// Issue #284 shape B, also measured live: the chart record carries no inline marker, so the
		// claim statement is chosen by the whole-answer fallback — and the sentence it lands on is the
		// one citing the finding. Same composition, reached by the other path.
		RecordMapping finding = realSafetyFinding();
		RecordMapping order = coMedicationRecord(finding);
		useInteractionJudge();

		List<RecordReference> result = verifier.verify(findingCitingSentence(finding),
				refsFor(order, finding), Arrays.asList(order, finding), FLOOR, TIER2_ON, false);

		assertNull(result.get(0).getGrounded(),
				"a guessed pairing whose statement rests on the finding cannot publish a denial");
	}

	@Test
	public void compositeClaim_aGuessedPairingLooksAtEveryCitationInTheAnswer() {
		// The fallback picks its statement out of the WHOLE answer, so the sentence it lands on need
		// not be the one citing the finding — here the cosine argmax lands on a sentence citing
		// nothing at all. Reading only that sentence's citations would leave the defect standing on a
		// wording detail, which is the fragility issue #284's comment warns about ("a wording detail,
		// deciding which of two grounding paths runs"). So for a guessed pairing the question is asked
		// of every citation in the answer.
		RecordMapping finding = realSafetyFinding();
		RecordMapping order = coMedicationRecord(finding);
		String verdictSentence = "No — clarithromycin should not be given.";
		String reasonSentence = "It interacts with the patient's active simvastatin order ["
				+ finding.getIndex() + "].";
		embeddings.register(verdictSentence, AXIS_A);
		embeddings.register(reasonSentence, AXIS_B);
		embeddings.register(order.getText(), AXIS_A); // argmax lands on the citation-free sentence
		ConjunctionAwareJudge judge = useInteractionJudge();

		List<RecordReference> result = verifier.verify(verdictSentence + " " + reasonSentence,
				refsFor(order, finding), Arrays.asList(order, finding), FLOOR, TIER2_ON, false);

		assertEquals(Arrays.asList(Arrays.asList(verdictSentence)), judge.statementsPerCall,
				"premise of this case: the guessed statement is the sentence that cites nothing");
		assertNull(result.get(0).getGrounded(),
				"the rule must not turn on which sentence the cosine argmax happened to pick");
	}

	@Test
	public void compositeClaim_aPronounCompoundIsWithheldByTheCompoundRuleInstead() {
		// Where the two rules meet, and the case that pins them disjoint. The wording the module's own
		// safety few-shot demonstrates ("it spoils the oranges already in store [2], a Major problem
		// [4]", in LlmProvider's safety demonstration) trips CLAUSE_MARKER, so splitEnumeration
		// refuses and the whole compound sentence stays ONE claim unit citing both records — which
		// makes it a compound claim unit (#302), not a composite claim this rule can reach: it never
		// enters Tier-2, so it carries no negative to withhold. The citation is still not published
		// as unsupported, which is what matters clinically; it is the OTHER rule that gets it there.
		RecordMapping finding = realSafetyFinding();
		RecordMapping order = coMedicationRecord(finding);
		String answer = "Clarithromycin can be given, with one caution: it interacts with "
				+ "active order Simvastatin [" + order.getIndex() + "], a Major problem ["
				+ finding.getIndex() + "].";
		ConjunctionAwareJudge judge = useInteractionJudge();

		List<RecordReference> result = verifier.verify(answer, refsFor(order, finding),
				Arrays.asList(order, finding), FLOOR, TIER2_ON, false);

		assertEquals(0, judge.statementsPerCall.size(),
				"a compound claim unit is never asked, so this rule has nothing to withhold");
		assertNull(result.get(0).getGrounded(),
				"and the citation is withheld all the same, by the compound-claim rule");
	}

	@Test
	public void compositeClaim_isCoveredUnderClauseScopedGroundingToo() {
		// The other fragment factory: under clause scope the same compound is cut into cumulative
		// prefixes, so the chart citation's unit cites only itself while the SENTENCE it came from
		// cites the finding too. Issue #284's comment measured both modes, so both are pinned.
		RecordMapping finding = realSafetyFinding();
		RecordMapping order = coMedicationRecord(finding);
		String answer = "Clarithromycin can be given, with one caution: it interacts with "
				+ "active order Simvastatin [" + order.getIndex() + "], a Major problem ["
				+ finding.getIndex() + "].";
		useInteractionJudge();

		List<RecordReference> result = verifier.verify(answer, refsFor(order, finding),
				Arrays.asList(order, finding), FLOOR, TIER2_ON, true);

		assertNull(result.get(0).getGrounded(),
				"clause scope splits the sentence differently but does not change what the claim "
						+ "rests on");
	}

	@Test
	public void compositeClaim_stillPublishesTheJudgesPositiveVerdict() {
		// Only the negative is guaranteed by the composition. A "yes" is not, so it still verifies the
		// citation — which is the check issue #118 injected the active-order record to keep, and the
		// reason this rule does not simply make a composite citation demote-only.
		RecordMapping finding = realSafetyFinding();
		RecordMapping order = coMedicationRecord(finding);
		llm.verdict = Boolean.TRUE;

		List<RecordReference> result = verifier.verify(compositeEnumeration(order, finding),
				refsFor(order, finding), Arrays.asList(order, finding), FLOOR, TIER2_ON, false);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(),
				"the rule withholds the guaranteed verdict, it does not blank the citation");
	}

	@Test
	public void compositeClaim_leavesTier1OnlyModeUnchanged() {
		// Scoped to the judge's negative: with no Tier-2 verdict there is nothing guaranteed to
		// withhold, and every measurement on issue #284 was taken with entailment enabled. So Tier-1
		// -only mode keeps both of its verdicts here, including the off-topic FALSE.
		RecordMapping finding = realSafetyFinding();
		RecordMapping order = coMedicationRecord(finding);
		String answer = compositeEnumeration(order, finding);
		String item = "Clarithromycin can be given, with one caution: Clarithromycin interacts with "
				+ "active order Simvastatin [" + order.getIndex() + "]";
		embeddings.register(item, AXIS_A);
		embeddings.register(order.getText(), AXIS_A);

		assertEquals(Boolean.TRUE, verifier.verify(answer, refsFor(order, finding),
				Arrays.asList(order, finding), FLOOR, TIER1_ONLY).get(0).getGrounded(),
				"a cosine pass still verifies a chart citation when no judge has spoken");

		embeddings.register(order.getText(), AXIS_B); // now orthogonal to its own claim item

		assertEquals(Boolean.FALSE, verifier.verify(answer, refsFor(order, finding),
				Arrays.asList(order, finding), FLOOR, TIER1_ONLY).get(0).getGrounded(),
				"and a cosine fail still flags it — this rule never reaches Tier-1-only mode");
	}

	@Test
	public void splitIntoCitedSentences_aFragmentCarriesItsParentsCitations() {
		// The premise the composite-claim rule (issue #284) rests on: an enumeration item's own
		// citedIndexes is a singleton, so the co-citation that makes its claim composite is only
		// visible through the set carried down from the sentence it was split from.
		List<CitationGroundingVerifier.Sentence> items = CitationGroundingVerifier
				.splitIntoCitedSentences("Recorded allergies: Lidocaine [1], Aspirin [2].");

		assertEquals(2, items.size());
		for (CitationGroundingVerifier.Sentence item : items) {
			assertEquals(1, item.citedIndexes.size(), "an item is attributed to one citation");
			assertEquals(new java.util.HashSet<Integer>(Arrays.asList(1, 2)), item.sourceCitedIndexes,
					"and carries what the sentence it came from cited");
		}
	}

	@Test
	public void splitIntoClauseScopedSentences_aClauseCarriesItsParentsCitations() {
		// Same for the other fragment factory, whose text already contains the earlier markers but
		// whose citedIndexes deliberately does not.
		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoClauseScopedSentences("A condition [1] and a diagnosis [2].");

		assertEquals(2, clauses.size());
		for (CitationGroundingVerifier.Sentence clause : clauses) {
			assertEquals(1, clause.citedIndexes.size());
			assertEquals(new java.util.HashSet<Integer>(Arrays.asList(1, 2)),
					clause.sourceCitedIndexes);
		}
	}

	@Test
	public void splitIntoCitedSentences_aWholeSentenceIsItsOwnSource() {
		List<CitationGroundingVerifier.Sentence> sentences = CitationGroundingVerifier
				.splitIntoCitedSentences("A condition [1] and a diagnosis [2].");

		assertEquals(1, sentences.size());
		assertEquals(sentences.get(0).citedIndexes, sentences.get(0).sourceCitedIndexes,
				"an unsplit sentence rests on exactly what it cites");
	}

	@Test
	public void compositeClaim_aChartCitationWhoseOwnClaimRestsOnNoFindingIsStillFlagged() {
		// The scope of the rule, and the case that discriminates it from "the answer mentions a
		// finding somewhere". Here the chart citation has its own sentence and that sentence rests on
		// the chart record alone; the finding is a claim of its own, in a sentence of its own. The
		// judge's "no" is then earned by the record rather than guaranteed by a composition, so it
		// must still publish. Without this case, replacing the whole claimRestsOn mechanism with
		// "does this answer cite reference material anywhere" passes the entire suite.
		RecordMapping finding = realSafetyFinding();
		RecordMapping order = coMedicationRecord(finding);
		String chartSentence = "The patient is on clarithromycin [" + order.getIndex() + "].";
		String findingSentence = "Simvastatin is a Major problem [" + finding.getIndex() + "].";
		useInteractionJudge();

		List<RecordReference> result = verifier.verify(chartSentence + " " + findingSentence,
				refsFor(order, finding), Arrays.asList(order, finding), FLOOR, TIER2_ON, false);

		assertEquals(Boolean.FALSE, result.get(0).getGrounded(),
				"a claim that rests on the chart record alone keeps its denial — the rule is about "
						+ "what the STATEMENT rests on, not about what the answer mentions");
	}

	@Test
	public void compositeClaim_aFindingCitedOnlyInTheCitationsArrayStillMakesTheClaimComposite() {
		// The mirror of shape B, and the half inline markers cannot see: the model marks up the chart
		// record and leaves the finding to the structured citations array. The claim is the same
		// composition, so anchoring the rule on the model's punctuation would publish the same wrong
		// denial for the same sentence written the other way round.
		RecordMapping finding = realSafetyFinding();
		RecordMapping order = coMedicationRecord(finding);
		String answer = "Clarithromycin interacts with active order Simvastatin ["
				+ order.getIndex() + "], a Major problem.";
		useInteractionJudge();

		List<RecordReference> result = verifier.verify(answer, refsFor(order, finding),
				Arrays.asList(order, finding), FLOOR, TIER2_ON, false);

		assertNull(result.get(0).getGrounded(),
				"a finding the model listed without a marker is still what the relationship rests on");
	}

	@Test
	public void compositeClaim_theWithheldNegativeIsReportedOncePerAnswer() {
		// The withheld negative is the one verdict this pass computes and keeps nowhere — unlike the
		// reference-group false, which #201 leaves on the RecordReference and withholds only at the
		// wire. Without a line it is unobservable on a running server, which is how a grading defect
		// on this path would survive the way #122's did.
		RecordMapping finding = realSafetyFinding();
		RecordMapping order = coMedicationRecord(finding);
		useInteractionJudge();

		List<String> lines;
		try (org.openmrs.module.chartsearchai.LogCapture capture = org.openmrs.module.chartsearchai.LogCapture
				.on(CitationGroundingVerifier.class.getName())) {
			verifier.verify(compositeEnumeration(order, finding), refsFor(order, finding),
					Arrays.asList(order, finding), FLOOR, TIER2_ON, false);
			lines = capture.messagesAt(org.apache.logging.log4j.Level.INFO);
		}

		assertEquals(1, lines.size(), "one summary per answer, not one line per citation: " + lines);
		assertTrue(lines.get(0).contains("withheld 1"), "the line names how many: " + lines.get(0));
	}

	@Test
	public void verify_aNonFiniteSimilarityStillFlagsRatherThanReportingAnEmbeddingFailure() {
		// Guard on the claim-selection bookkeeping the composite rule added to the Tier-1-only path:
		// when every comparison is non-finite no candidate ever wins, and indexing the sentence list
		// on the unset best would throw into the embedding-failure catch — turning this branch's
		// FALSE into a null and blaming querystore for an arithmetic edge.
		String sentence = "Patient has diabetes [1].";
		embeddings.register(sentence, new float[] { Float.NaN, 0f });
		embeddings.register("type 2 diabetes mellitus", AXIS_A);

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(1))),
				Arrays.asList(mapping(1, "type 2 diabetes mellitus")), FLOOR, TIER1_ONLY);

		assertEquals(Boolean.FALSE, result.get(0).getGrounded(),
				"nothing cleared the floor, so the citation is not grounded — and no failure is claimed");
	}

	/**
	 * Issue #448: a one-line answer dense with {@code [N]} markers made the clause-scoped splitter
	 * copy the cumulative prefix once per marker, so the characters it materialised grew as the
	 * marker count times the sentence length — quadratic in an answer whose length a remote endpoint
	 * chooses. The property, not a magnitude: what the splitter hands back is bounded by the answer
	 * plus the per-answer allowance, whatever the shape of the answer.
	 *
	 * <p>The markers are DISTINCT deliberately. {@link ChartSearchAiUtils#citedIndexes} returns a
	 * SET, so a repeated index collapses to one element and
	 * {@link CitationGroundingVerifier#splitIntoClauseScopedSentences}'s single-citation guard
	 * returns the sentence whole — which is what this case asserts of the FIXED code, so a repeated
	 * index would make it pass before the change.
	 */
	@Test
	public void clauseScopedSplitOfADenseOneLineAnswerStaysLinearInTheAnswerLength() {
		String answer = denseOneLineAnswer(1000);

		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoClauseScopedSentences(answer);

		assertTrue(totalFragmentChars(clauses) <= answer.length()
				+ CitationGroundingVerifier.MAX_SPLIT_FRAGMENT_CHARS,
				"splitting one answer may materialise at most the answer plus the allowance; it "
						+ "materialised " + totalFragmentChars(clauses) + " characters for an answer "
						+ "of " + answer.length());
		assertEquals(1, clauses.size(),
				"and having refused the split it grades the sentence whole, which is the "
						+ "sentence-scoped unit");
		assertEquals(answer, clauses.get(0).text, "unchanged, not truncated");
	}

	/**
	 * The same defect in the splitter that runs in BOTH scoping modes, so it is reachable with
	 * {@code chartsearchai.grounding.clauseScoped} left at its default false. Issue #448's body says
	 * of this path "the sentence-scoped default splitter (splitIntoCitedSentences /
	 * splitEnumeration) is linear per sentence and does not have this property"; this case is the
	 * measurement that says otherwise. {@link CitationGroundingVerifier#splitEnumeration} gives every
	 * item the whole preamble, and the preamble is the text up to the colon nearest the first marker
	 * — unbounded. Its per-item guards test the marker-stripped ITEM, never the preamble.
	 */
	@Test
	public void enumerationSplitOfALongPreambleStaysLinearInTheAnswerLength() {
		String answer = longPreambleEnumeration(5000, 200);

		List<CitationGroundingVerifier.Sentence> items = CitationGroundingVerifier
				.splitIntoCitedSentences(answer);

		assertTrue(totalFragmentChars(items) <= answer.length()
				+ CitationGroundingVerifier.MAX_SPLIT_FRAGMENT_CHARS,
				"splitting one answer may materialise at most the answer plus the allowance; it "
						+ "materialised " + totalFragmentChars(items) + " characters for an answer of "
						+ answer.length());
		assertEquals(1, items.size(), "the refused enumeration is graded as one sentence");
		assertEquals(answer, items.get(0).text, "unchanged, not truncated");
	}

	/**
	 * The allowance is spent per ANSWER. Thirty sentences, each cheap enough to split on its own,
	 * together cost more than the allowance — so a cap applied per sentence at the same value would
	 * split every one of them and leave the total unbounded by the answer length. Only the aggregate
	 * property is pinned: a per-sentence cap at a SMALLER value would also satisfy this, and the
	 * criterion issue #448 states is the aggregate one.
	 */
	@Test
	public void theSplitAllowanceIsSpentPerANSWERAndNotPerSENTENCE() {
		String answer = manyAffordableSentences(30, 60);

		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoClauseScopedSentences(answer);

		assertTrue(totalFragmentChars(clauses) <= answer.length()
				+ CitationGroundingVerifier.MAX_SPLIT_FRAGMENT_CHARS,
				"thirty individually affordable sentences may not together exceed the allowance; "
						+ "they materialised " + totalFragmentChars(clauses) + " characters for an "
						+ "answer of " + answer.length());
		assertTrue(clauses.size() > 30,
				"the early sentences must still be split — a bound that refuses everything is not "
						+ "the fix, it is the feature turned off");
	}

	/**
	 * A refused split changes how every citation of that sentence is graded — it loses its clause,
	 * and with it the isolation that keeps its Tier-2 verdict out of the shared batch — so it may
	 * not be silent. The level is the observable: the returned list is a legitimate list either way.
	 * The line carries numbers only, because a grounding log line that quoted the answer would write
	 * the patient's own clinical text into the default server log (issue #439).
	 */
	@Test
	public void aRefusedSplitIsReportedAtWARNWithoutQuotingTheAnswer() {
		String answer = denseOneLineAnswer(1000);

		try (LogCapture capture = LogCapture.on(CitationGroundingVerifier.class.getName())) {
			CitationGroundingVerifier.splitIntoClauseScopedSentences(answer);

			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"a refused split must be reported. Captured: " + capture.describeAll());
			for (String message : capture.messagesAt(Level.WARN)) {
				assertFalse(message.contains("[1]") || message.contains("filler"),
						"the line may carry counts and lengths, never the answer's own text: "
								+ message);
			}
		}
	}

	/**
	 * A fragment carries its parent's citations by SHARING that set, never by copying it. The set
	 * holds one entry per distinct marker in the parent sentence, so a copy per fragment costs the
	 * marker count squared in set entries even when the fragments' TEXT is bounded — which would
	 * leave issue #448's exhaustion reachable behind a character allowance that reports itself
	 * satisfied. Sharing is safe because both sets are unmodifiable, which the second assertion
	 * pins.
	 */
	@Test
	public void everyFragmentOfOneSentenceSharesItsParentsCitationSetRatherThanCopyingIt() {
		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoClauseScopedSentences("A condition [1] and a diagnosis [2].");

		assertEquals(2, clauses.size());
		assertSame(clauses.get(0).sourceCitedIndexes, clauses.get(1).sourceCitedIndexes,
				"both clauses rest on the SAME set instance, not on two copies of it");
		assertThrows(UnsupportedOperationException.class,
				() -> clauses.get(0).sourceCitedIndexes.add(Integer.valueOf(9)),
				"a shared set must be unmodifiable, or one fragment could rewrite its siblings");
	}

	/**
	 * Once the allowance is gone every later multi-citation sentence is refused too, and the input
	 * that gets there is by definition long — so the report is ONE line per answer and not one per
	 * sentence. A log flood is the second half of the availability defect issue #448 is about: the
	 * answer is attacker-length, and a line per sentence writes it to disk that many times over.
	 */
	@Test
	public void aRefusedSplitIsReportedOncePerANSWERAndNotOncePerSentence() {
		String answer = manyAffordableSentences(30, 60);

		try (LogCapture capture = LogCapture.on(CitationGroundingVerifier.class.getName())) {
			List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
					.splitIntoClauseScopedSentences(answer);

			int refused = 0;
			for (CitationGroundingVerifier.Sentence clause : clauses) {
				if (clause.citedIndexes.size() > 1) {
					refused++;
				}
			}
			assertTrue(refused > 1, "premise: more than one sentence must have been refused, or "
					+ "one line and one refusal would be the same measurement. Refused: " + refused);
			assertEquals(1, capture.messagesAt(Level.WARN).size(),
					"one line for the answer, whatever it cost. Captured: " + capture.describeAll());
			assertTrue(capture.hasMessageAt(Level.WARN, refused + " sentence(s)"),
					"and it states the ANSWER's total, not the first refusal's own numbers — the "
							+ "figure a maintainer sizes the incident by. Captured: "
							+ capture.describeAll());
		}
	}

	/**
	 * Bounding the SPLITTER left the same quadratic standing in its consumer: {@code AnswerCitations}
	 * unioned each claim unit's source citations into {@code anchored} once per FRAGMENT, and since
	 * issue #448 makes every fragment of a sentence share one set of one entry per distinct marker,
	 * that is the fragment count times the marker count — both of which are the marker count. It is
	 * invisible to an allowance counted in characters, which is why the allowance's own cases cannot
	 * see it: measured through this same {@code verify()} call, 254 ms at 5,000 markers, 879 at
	 * 10,000 and 3,557 at 20,000, four times the work for twice the answer.
	 *
	 * <p><strong>Asserted as a RATIO, not as a wall-clock bound</strong>, so the case says the same
	 * thing on a fast machine and a loaded one: four times the markers is four times the work if the
	 * pass is linear and sixteen if it is quadratic, and the threshold sits between. Each size is
	 * measured twice and the lower reading kept, after a discarded warm-up, because what JIT and a
	 * co-tenant build add is noise in one direction only.
	 *
	 * <p>The premise is asserted rather than assumed: both answers must really be SPLIT. If either
	 * crossed the allowance it would come back as one whole sentence, there would be no fragment
	 * count to multiply, and this case would pass without having run the thing it is about.
	 */
	@Test
	public void theAnswersMarkersAreUnionedOncePerDISTINCTSetAndNotOncePerFragment() {
		String small = sharedPreambleEnumeration(10000);
		String large = sharedPreambleEnumeration(40000);
		assertTrue(CitationGroundingVerifier.splitIntoCitedSentences(small).size() > 9000
				&& CitationGroundingVerifier.splitIntoCitedSentences(large).size() > 36000,
				"premise: both answers must be split per item, or there is no fragment count to "
						+ "multiply and this case measures nothing");

		groundingMillis(small); // warm-up, discarded

		long smallest = Math.min(groundingMillis(small), groundingMillis(small));
		long largest = Math.min(groundingMillis(large), groundingMillis(large));

		assertTrue(largest <= 8 * Math.max(smallest, 1L),
				"four times the markers may cost about four times the work, not sixteen: "
						+ smallest + " ms at 10,000 markers against " + largest + " ms at 40,000");
	}

	/** Grounds {@code answer} against ten cited records through the real verifier, in milliseconds. */
	private long groundingMillis(String answer) {
		List<RecordReference> references = new ArrayList<RecordReference>();
		List<RecordMapping> mappings = new ArrayList<RecordMapping>();
		for (int index = 1; index <= 10; index++) {
			references.add(reference(index));
			mappings.add(mapping(index, "record " + index));
		}
		long start = System.nanoTime();
		verifier.verify(answer, references, mappings, FLOOR, TIER1_ONLY);
		return (System.nanoTime() - start) / 1000000L;
	}

	/** An enumeration of {@code markers} one-word items behind a two-character preamble — the shape
	 *  that buys the most fragments per character of the answer's split allowance. */
	private static String sharedPreambleEnumeration(int markers) {
		StringBuilder answer = new StringBuilder("x:");
		for (int index = 1; index <= markers; index++) {
			answer.append(index == 1 ? " a [" : ", a [").append(index).append(']');
		}
		return answer.toString();
	}

	/**
	 * ONE allowance for the answer, spent across BOTH splitters — not one each. The enumeration pass
	 * runs to completion inside {@code splitIntoCitedSentences} before the first clause is built, so
	 * an answer whose enumerations spend nearly all of it must leave the compound sentence after
	 * them unsplit. Give each pass its own {@code FragmentBudget} and the bound doubles silently,
	 * which is what this case refuses: no other case here mixes the two shapes, so the threading is
	 * otherwise free to be undone with the suite green.
	 */
	@Test
	public void oneAllowanceIsSharedBySplittingBOTHShapesOfOneAnswer() {
		StringBuilder answer = new StringBuilder();
		for (int word = 0; word < 250; word++) {
			answer.append("zzz ");
		}
		answer.append(':');
		for (int index = 1; index <= 950; index++) {
			answer.append(index == 1 ? " a [" : ", a [").append(index).append(']');
		}
		answer.append(". ");
		int compoundStart = answer.length();
		for (int word = 0; word < 12500; word++) {
			answer.append("word ");
		}
		answer.append("[1] and a further finding [2].");
		String compound = answer.substring(compoundStart);

		List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
				.splitIntoClauseScopedSentences(answer.toString());

		assertTrue(clauses.size() > 900,
				"premise: the enumeration itself must have been split, or it spent nothing and the "
						+ "compound after it would be unsplit for the wrong reason. Units: "
						+ clauses.size());
		assertEquals(compound, clauses.get(clauses.size() - 1).text,
				"the enumeration spent the answer's allowance, so the compound sentence after it is "
						+ "graded whole — with an allowance of its own it would have been split");
	}

	/**
	 * What a refused clause split actually costs the ordinary case, asserted rather than reasoned.
	 * A sentence whose markers are separated by claim text is a {@link
	 * CitationGroundingVerifier.Sentence#compoundClaim()} once it is graded whole, so under
	 * entailment its citations publish NO verdict — the #302 withholding, which is a larger loss
	 * than the co-batching the co-citation shape suffers. ADR Decision 103 says so; this is the case
	 * that makes it a measurement.
	 *
	 * <p>Both halves are driven in CLAUSE-SCOPED mode, through the six-argument
	 * {@code verify}: the five-argument one is sentence-scoped, where no clause split is attempted
	 * at all, so the refusal would not be in the causal path and the {@code null} asserted below
	 * would be the plain #302 withholding of an unsplit compound. The affordable half is the
	 * control that says so — the same prose shape, short enough to split, publishes verdicts.
	 */
	@Test
	public void aRefusedClauseSplitWithdrawsTheVerdictWhereClaimTextSeparatesItsMarkers() {
		String affordable = compoundProse(2);
		String refused = compoundProse(1200);

		assertEquals(2, CitationGroundingVerifier.splitIntoClauseScopedSentences(affordable).size(),
				"control premise: the short one is split into its two clauses");
		assertEquals(1, CitationGroundingVerifier.splitIntoClauseScopedSentences(refused).size(),
				"premise: the long one's split is refused, so it is graded whole");

		List<RecordReference> control = verifier.verify(affordable,
				new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(2))),
				Arrays.asList(mapping(1, "a condition"), mapping(2, "a finding")), FLOOR, TIER2_ON,
				true);
		assertNotNull(control.get(0).getGrounded(),
				"control: a clause-scoped fragment is its own claim, so a verdict is PUBLISHED for "
						+ "it — whichever way it falls. That is what the refused case below loses, "
						+ "and without this row the null there could be any other withholding");

		List<RecordReference> result = verifier.verify(refused,
				new ArrayList<RecordReference>(Arrays.asList(reference(1), reference(2))),
				Arrays.asList(mapping(1, "a condition"), mapping(2, "a finding")), FLOOR, TIER2_ON,
				true);
		assertNull(result.get(0).getGrounded(),
				"the refused sentence is a compound claim, so the refusal costs the verdict itself "
						+ "and not merely its isolation");
		assertNull(result.get(1).getGrounded(), "and the same for its co-citation");
	}

	/** One sentence carrying {@code markers} markers with claim text between each pair, so that
	 *  graded whole it is a compound claim. */
	private static String compoundProse(int markers) {
		StringBuilder prose = new StringBuilder("The patient has a condition");
		for (int index = 1; index <= markers; index++) {
			prose.append(" [").append(index).append("] and a further recorded finding");
		}
		return prose.toString();
	}

	/**
	 * One sentence graded whole is ONE sentence in the report, however many splitters refused it.
	 * An enumeration the allowance turns down is handed on whole and then offered to the
	 * clause-scoped rule, which turns it down too — so counting refusals rather than sentences
	 * states twice the truth of exactly the answer a maintainer is triaging.
	 */
	@Test
	public void aSentenceBOTHSplittersRefuseIsOneSentenceInTheReport() {
		StringBuilder answer = new StringBuilder();
		while (answer.length() < CitationGroundingVerifier.MAX_SPLIT_FRAGMENT_CHARS + 100000L) {
			answer.append("zzzzzzzzzz ");
		}
		answer.append(": a [1], a [2]");
		String text = answer.toString();

		try (LogCapture capture = LogCapture.on(CitationGroundingVerifier.class.getName())) {
			List<CitationGroundingVerifier.Sentence> clauses = CitationGroundingVerifier
					.splitIntoClauseScopedSentences(text);

			assertEquals(1, clauses.size(),
					"premise: one sentence, refused by the enumeration rule and then by the "
							+ "clause rule");
			assertTrue(capture.hasMessageAt(Level.WARN, "1 sentence(s)"),
					"one sentence graded whole is one in the report, not two. Captured: "
							+ capture.describeAll());
		}
	}

	/** One line, no sentence terminator and no colon, with {@code markers} DISTINCT markers. */
	private static String denseOneLineAnswer(int markers) {
		StringBuilder answer = new StringBuilder("x");
		for (int index = 1; index <= markers; index++) {
			answer.append(" [").append(index).append(']');
		}
		return answer.toString();
	}

	/** One sentence whose list-introducing colon is preceded by {@code fillerWords} words, so every
	 *  enumerated item is handed the whole preamble. */
	private static String longPreambleEnumeration(int fillerWords, int items) {
		StringBuilder answer = new StringBuilder();
		for (int word = 0; word < fillerWords; word++) {
			answer.append("z ");
		}
		answer.append(':');
		for (int index = 1; index <= items; index++) {
			answer.append(index == 1 ? " a [" : ", a [").append(index).append(']');
		}
		return answer.toString();
	}

	/** {@code sentences} sentences, each with {@code markers} distinct markers and enough filler to
	 *  make its own split cost a small fraction of the allowance. */
	private static String manyAffordableSentences(int sentences, int markers) {
		StringBuilder filler = new StringBuilder();
		for (int word = 0; word < 200; word++) {
			filler.append("filler ");
		}
		StringBuilder answer = new StringBuilder();
		for (int sentence = 0; sentence < sentences; sentence++) {
			answer.append(filler);
			for (int index = 1; index <= markers; index++) {
				answer.append(" [").append(index).append(']');
			}
			answer.append(". ");
		}
		return answer.toString();
	}

	private static long totalFragmentChars(List<CitationGroundingVerifier.Sentence> fragments) {
		long total = 0;
		for (CitationGroundingVerifier.Sentence fragment : fragments) {
			total += fragment.text.length();
		}
		return total;
	}
}
