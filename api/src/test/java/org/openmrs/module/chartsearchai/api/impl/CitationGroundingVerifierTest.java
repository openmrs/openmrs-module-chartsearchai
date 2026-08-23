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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
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
		setUp();
		llm.verdict = Boolean.TRUE;
		String sentence = "The record supports this claim [4].";
		String record = "record text for " + resourceType;
		embeddings.register(sentence, AXIS_A);
		embeddings.register(record, AXIS_A);
		return verifier.verify(sentence,
				new ArrayList<RecordReference>(Arrays.asList(reference(4))),
				Arrays.asList(new RecordMapping(4, resourceType, "uuid-4", null, record)),
				FLOOR, entailmentEnabled).get(0).getGrounded();
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
	 * re-hardcoding it as a list of type names still passes today — today's list and today's groups
	 * agree — and fails the moment a fourth injected type is added, which is exactly when the omission
	 * would otherwise ship again. Its counterpart in {@code ChartSearchAiReferenceGroupTest} asserts
	 * the same rule against the group each constant is RECORDED as, so the two registries cannot drift
	 * together either.
	 *
	 * <p>Asserts carve-out MEMBERSHIP — through the verdict the real verifier publishes and its Tier-2
	 * entry — not the live instability that motivated the issue: those flips are embedding-driven, so
	 * no unit test reproduces them. The chart-group half of each assertion is the positive control that
	 * the machinery under it is working.
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
	public void compoundClaim_anOffTopicCitationIsStillFlagged() {
		// The demotion must not become a rubber stamp. Only a cosine PASS is demoted; record 2 is
		// orthogonal to the answer, so the surviving Tier-1 verdict still flags it. That FALSE is the
		// specified sentence-scope behaviour for a compound sentence — see
		// clauseScoped_groundsFirstCitationAgainstItsClauseNotTheCompoundSentence — which demonstrates
		// that pre-existing behaviour but does NOT guard this branch, since it runs Tier-1-only where
		// the demotion is gated off. This case is the compound arm's guard: mutate the demotion to
		// swallow FALSE as well as TRUE and this one reddens, alongside the reference-group arm's own
		// off-topic cases.
		ConjunctionAwareJudge judge = new ConjunctionAwareJudge("salicylic acid", "methotrexate");
		verifier.setLlmProvider(judge);
		embeddings.register(COLON_LESS_LIST, AXIS_A);
		embeddings.register("Drug order: Salicylic acid", AXIS_A);
		embeddings.register("BP 150/95", AXIS_B);

		List<RecordReference> result = verifier.verify(COLON_LESS_LIST, twoRefs(),
				Arrays.asList(mapping(1, "Drug order: Salicylic acid"), mapping(2, "BP 150/95")),
				FLOOR, TIER2_ON, false);

		assertNull(result.get(0).getGrounded(), "the on-topic citation is demoted, not verified");
		assertEquals(Boolean.FALSE, result.get(1).getGrounded(),
				"an off-topic citation of a compound claim must still be flagged");
	}

	@Test
	public void compoundClaim_leavesTheTier1OnlyPathUntouched() {
		// The demotion is deliberately NOT mode-uniform, unlike the reference-group one beside it.
		// #302's defect is Tier-2's refusal of a conjunction, which does not exist when entailment is
		// off: there every verdict is cosine against the claim text, a compound unit's is no different
		// in kind, and sentence scope has always compared against the whole compound sentence
		// (clauseScoped is this module's remedy for that, and #302 does not change it). Demoting here
		// would suppress the PASS of a comparison whose FAIL the module still publishes — see
		// compoundClaim_anOffTopicCitationIsStillFlagged — and cost a correct citation its verdict for
		// no defect removed.
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
		assertNull(result.get(0).getGrounded(), "and Tier-1 cannot answer with no embedder");
		assertNull(result.get(1).getGrounded());
	}

	@Test
	public void anArrayOnlyCitationAttributedToACompoundClaimIsDemotedWithIt() {
		// A citation the model put only in the structured citations array has no marker of its own, so
		// selectClaim attributes it to whichever sentence matches best. Where that is a compound claim
		// unit, its statement asserts more than this record is responsible for exactly as it does for
		// the inline citations, so the judge's refusal is as uninformative here — and it is demoted
		// with them. Gating the rule on "does this claim unit cite ME" instead would send an array-only
		// citation back to the judge against the whole conjunction, which is the shape #284 exists to
		// complain about. Nothing is lost that Tier-1 was carrying: a record that does not resemble the
		// sentence is still flagged, as compoundClaim_anOffTopicCitationIsStillFlagged shows.
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
				"[3], cited only in the array: demoted, not published unsupported");
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
		// Stubbed TRUE, not FALSE: a FALSE is untouched by the demotion either way, so only a TRUE
		// actually puts "co-citation is not demoted" under test. This is the COLON-LESS register —
		// splitEnumeration never looks at this sentence, where the sub-shape case below reaches its
		// no-own-text guard — so the two pin the same property on the two different paths that can
		// leave adjacent markers in one claim unit.
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
}
