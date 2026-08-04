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

	// ---- injected active-order citations (issue #118): graded normally, NOT demote-only ----

	/** A mapping typed as an injected active-order record, carrying the real {@code Order} uuid the
	 *  production injector puts there. */
	private static RecordMapping activeDrugOrderMapping(int index, String uuid, String text) {
		return new RecordMapping(index, ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER,
				uuid, null, text);
	}

	/** Real injected active-order record text off the real production chain (reconciliation →
	 *  render), the counterpart of {@link #realReferenceRecordText}. */
	private static String realActiveOrderRecordText(String uuid, String display) {
		return org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport
				.injectedActiveOrderText(uuid, display);
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
		String record = realActiveOrderRecordText("order-uuid-7", "Simvastatin Co 20mg");
		String sentence = "The patient has an active order for Simvastatin Co 20mg [7].";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_A);

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(7))),
				Arrays.asList(activeDrugOrderMapping(7, "order-uuid-7", record)), FLOOR, TIER1_ONLY);

		assertEquals(Boolean.TRUE, result.get(0).getGrounded(),
				"an active-order citation is chart evidence, so a cosine pass must VERIFY it — "
						+ "demote-only is scoped to drug-reference prose (#106), not to everything injected");
	}

	@Test
	public void activeDrugOrder_isVerifiedByTier2Entailment() {
		// The other half: it must also reach the entailment LLM. A type excluded from Tier-2 keeps a
		// Tier-1 verdict only, so an off-claim citation that cosine happens to like would never be
		// caught — and no client suppresses this type's verdict (it groups as chart evidence), so the
		// verdict rendered here is the one the clinician sees. Inverts
		// drugReference_neverEntersTier2Entailment.
		String record = realActiveOrderRecordText("order-uuid-7", "Simvastatin Co 20mg");
		String sentence = "The patient has an active order for Simvastatin Co 20mg [7].";
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_A);
		llm.verdict = Boolean.FALSE;

		List<RecordReference> result = verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(7))),
				Arrays.asList(activeDrugOrderMapping(7, "order-uuid-7", record)), FLOOR, TIER2_ON);

		assertEquals(1, llm.calls, "an active-order citation must be judged by the entailment LLM");
		assertEquals(Boolean.FALSE, result.get(0).getGrounded(),
				"and Tier-2's verdict must be authoritative for it, overriding the Tier-1 cosine pass");
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
}
