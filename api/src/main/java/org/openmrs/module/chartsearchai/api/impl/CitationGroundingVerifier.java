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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.embedding.EmbeddingProvider;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Checks that each cited record actually supports the answer sentence(s) that
 * cite it, rather than trusting the LLM's citations blindly.
 *
 * <p>{@link LlmInferenceService} already validates that a {@code [N]} marker
 * maps to a real retrieved record. That catches a model citing a record number
 * that does not exist, but not the more dangerous case of a real record cited
 * for a claim it does not support (e.g. "patient has diabetes [5]" where record
 * 5 is a blood-pressure reading). This verifier closes that gap with a Tier-1
 * semantic-overlap check: embed each cited record and the answer sentence(s)
 * that reference it, and require their cosine similarity to clear a tunable
 * floor ({@code chartsearchai.grounding.minCosine}). Below the floor, the
 * citation is annotated {@code grounded=false} so the UI can flag it.
 *
 * <p><strong>Scope and limits.</strong> This is intentionally an annotate-only,
 * non-destructive pass: it never rewrites the answer prose or drops citations,
 * it only attaches a verdict. Cosine overlap reliably separates off-topic
 * citations from on-topic ones, but it does <em>not</em> separate subtle
 * subject/polarity flips ("patient has X" vs "mother had X", "denies X" vs
 * "has X") — those embed nearly identically.
 *
 * <p><strong>Tier-2 (optional).</strong> When
 * {@code chartsearchai.grounding.entailment.enabled} is set, each reference
 * Tier-1 could evaluate is confirmed by a short yes/no LLM entailment call
 * ({@link LlmProvider#entails}) whose verdict is authoritative. This is what
 * catches the subject/polarity flips cosine cannot. It runs on Tier-1 passes
 * <em>and</em> failures — the dangerous case (a high-overlap but unsupported
 * citation) is a Tier-1 pass, so confirming only failures would miss it — and
 * costs one LLM call per cited reference, capped at
 * {@link ChartSearchAiConstants#GROUNDING_ENTAILMENT_MAX_CHECKS} per answer.
 *
 * <p>The verifier never throws into the search path: any failure (embedding
 * error, missing text) degrades to a {@code null} verdict — "could not verify"
 * — which renders as unverified, exactly as if grounding were disabled.
 */
@Service("chartSearchAi.citationGroundingVerifier")
public class CitationGroundingVerifier {

	private static final Logger log = LoggerFactory.getLogger(CitationGroundingVerifier.class);

	/**
	 * Splits an answer into claim units, on terminal punctuation followed by
	 * whitespace OR on line breaks. The line-break case matters: the system
	 * prompt instructs the model to "use numbered lines or simple newlines to
	 * structure lists", so a multi-item answer often has no sentence-ending
	 * punctuation — without splitting on newlines every citation would be scored
	 * against the whole answer instead of its own line.
	 */
	private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+|[\\r\\n]+");

	@Autowired
	private EmbeddingProvider embeddingProvider;

	@Autowired
	private LlmProvider llmProvider;

	/** Test seam: production wires {@link EmbeddingProvider} via {@link Autowired}. */
	void setEmbeddingProvider(EmbeddingProvider embeddingProvider) {
		this.embeddingProvider = embeddingProvider;
	}

	/** Test seam: production wires {@link LlmProvider} via {@link Autowired}. */
	void setLlmProvider(LlmProvider llmProvider) {
		this.llmProvider = llmProvider;
	}

	/** Embeds text to a vector — abstracts over which module's provider is used. */
	interface TextEmbedder {

		float[] embed(String text);
	}

	/**
	 * Resolves the embedding provider for a grounding run. When querystore is the
	 * retrieval backend ({@code chartsearchai.querystore.enabled=true}), grounding
	 * reuses querystore's configured provider (the same e5/ONNX model that built
	 * the index) so the verifier embeds with the same model as retrieval — and so
	 * no separate chartsearchai embedding model has to be installed. Falls back to
	 * chartsearchai's own {@link EmbeddingProvider} when querystore is absent,
	 * disabled, or its provider can't be resolved. Never throws.
	 *
	 * <p>querystore is a {@code provided}-scope (optional) dependency, so its
	 * {@code EmbeddingProvider} type may be absent at runtime — the
	 * {@code LinkageError} catch covers {@code NoClassDefFoundError}, mirroring
	 * {@code QueryStoreChartBuilder}'s guard.
	 */
	TextEmbedder resolveEmbedder() {
		try {
			if (ChartSearchAiUtils.isQueryStoreEnabled()) {
				org.openmrs.module.querystore.embedding.EmbeddingProvider qs =
						org.openmrs.api.context.Context.getRegisteredComponent(
								"querystore.embedding.dispatcher",
								org.openmrs.module.querystore.embedding.EmbeddingProvider.class);
				if (qs != null) {
					return qs::embed;
				}
			}
		}
		catch (RuntimeException | LinkageError e) {
			log.warn("Grounding: querystore embedding provider unavailable ({}); "
					+ "falling back to chartsearchai's own embedding model", e.toString());
		}
		return embeddingProvider == null ? null : embeddingProvider::embed;
	}

	/** Accumulates embedding failures across a run so they are logged once, not per citation. */
	private static final class GroundingStats {

		int embedFailures;

		String firstError;

		void recordFailure(Throwable t) {
			embedFailures++;
			if (firstError == null) {
				firstError = t.getClass().getSimpleName() + ": " + t.getMessage();
			}
		}
	}

	/**
	 * Returns a copy of {@code references} with each entry's grounding verdict
	 * set. A reference is grounded when its record's text is at least
	 * {@link ChartSearchAiUtils#getGroundingMinCosine()} cosine-similar to the
	 * best-matching answer sentence that cites it (or, when no sentence cites it
	 * inline — e.g. it appeared only in the structured citations array — to the
	 * best-matching sentence anywhere in the answer). References whose record
	 * carries no text, or that cannot be embedded, are returned with a
	 * {@code null} verdict ("could not verify").
	 *
	 * @param answer the full answer prose, with inline {@code [N]} markers
	 * @param references the index-validated references to annotate
	 * @param mappings the record mappings carrying each index's source text
	 * @return a new list, same order, with grounding verdicts attached
	 */
	public List<RecordReference> verify(String answer, List<RecordReference> references,
			List<RecordMapping> mappings) {
		return verify(answer, references, mappings, ChartSearchAiUtils.getGroundingMinCosine(),
				ChartSearchAiUtils.isGroundingEntailmentEnabled());
	}

	/**
	 * Flag-explicit overload — the seam the public {@link #verify} delegates to
	 * after reading {@code chartsearchai.grounding.minCosine} and
	 * {@code chartsearchai.grounding.entailment.enabled}. Package-private so unit
	 * tests can exercise the grounding logic without an OpenMRS context.
	 *
	 * <p>When {@code entailmentEnabled}, every reference Tier-1 could evaluate is
	 * confirmed by a Tier-2 LLM entailment call whose verdict is authoritative
	 * (cosine errs in both directions, and the dangerous error — a high-overlap
	 * but unsupported citation — is exactly the case Tier-1 cannot self-detect,
	 * so the LLM must see Tier-1 passes too, not only failures). Tier-2 is
	 * capped at {@link ChartSearchAiConstants#GROUNDING_ENTAILMENT_MAX_CHECKS}
	 * calls per answer; references beyond the cap keep their Tier-1 verdict.
	 */
	List<RecordReference> verify(String answer, List<RecordReference> references,
			List<RecordMapping> mappings, double floor, boolean entailmentEnabled) {
		if (references == null || references.isEmpty()) {
			return references;
		}

		Map<Integer, String> textByIndex = new HashMap<Integer, String>();
		if (mappings != null) {
			for (RecordMapping mapping : mappings) {
				textByIndex.put(mapping.getIndex(), mapping.getText());
			}
		}

		List<Sentence> sentences = splitIntoCitedSentences(answer);
		TextEmbedder embedder = resolveEmbedder();

		// Embedding caches: each record and each sentence is embedded at most
		// once per call, even when an index is cited by several sentences.
		Map<Integer, float[]> recordVectors = new HashMap<Integer, float[]>();
		Map<Integer, float[]> sentenceVectors = new HashMap<Integer, float[]>();
		GroundingStats stats = new GroundingStats();

		int entailmentBudget = ChartSearchAiConstants.GROUNDING_ENTAILMENT_MAX_CHECKS;
		int cappedCount = 0;

		List<RecordReference> annotated = new ArrayList<RecordReference>(references.size());
		for (RecordReference reference : references) {
			Tier1Result tier1 = verdictTier1(reference.getIndex(), textByIndex, sentences,
					floor, recordVectors, sentenceVectors, embedder, stats);
			Boolean verdict = tier1.verdict;

			// Tier-2: only meaningful when Tier-1 reached a definite verdict and
			// we have a concrete claim sentence to fact-check against the record.
			if (entailmentEnabled && tier1.verdict != null && tier1.bestSentence != null
					&& tier1.recordText != null) {
				if (entailmentBudget > 0) {
					entailmentBudget--;
					Boolean llmVerdict = safeEntails(tier1.recordText, tier1.bestSentence,
							reference.getIndex());
					if (llmVerdict != null) {
						verdict = llmVerdict; // authoritative; null -> keep Tier-1
					}
				} else {
					cappedCount++;
				}
			}
			annotated.add(reference.withGrounded(verdict));
		}
		if (cappedCount > 0) {
			log.info("Tier-2 entailment cap ({}) reached; {} citation(s) kept their Tier-1 verdict only",
					ChartSearchAiConstants.GROUNDING_ENTAILMENT_MAX_CHECKS, cappedCount);
		}
		// One summary line instead of a per-citation stacktrace: the usual cause is a
		// misconfigured/absent embedding model, which would otherwise spam the log once
		// per citation and bury the root cause.
		if (stats.embedFailures > 0) {
			log.warn("Citation grounding: could not verify {} of {} citation(s) — embedding provider "
					+ "failed ({}); those citations are left unverified. If querystore is the backend, "
					+ "ensure its embedding model is configured; otherwise set "
					+ "chartsearchai.embedding.modelFilePath.",
					stats.embedFailures, references.size(), stats.firstError);
		}
		return annotated;
	}

	/**
	 * Computes the Tier-1 cosine verdict for one cited index and identifies the
	 * single best-matching claim sentence (used as the Tier-2 entailment target).
	 * Never throws: an embedding failure yields a {@code null} verdict.
	 */
	private Tier1Result verdictTier1(int index, Map<Integer, String> textByIndex,
			List<Sentence> sentences, double floor,
			Map<Integer, float[]> recordVectors, Map<Integer, float[]> sentenceVectors,
			TextEmbedder embedder, GroundingStats stats) {
		String recordText = textByIndex.get(index);
		if (recordText == null || recordText.trim().isEmpty()) {
			return new Tier1Result(null, null, null); // nothing to compare against
		}
		try {
			float[] recordVector = embedRecord(index, recordText, recordVectors, embedder);

			// Track whether ANY comparison happened separately from the best
			// score: a negative cosine is the strongest "not grounded" signal,
			// so it must produce FALSE, not be mistaken for "nothing to compare".
			double best = -Double.MAX_VALUE;
			String bestSentence = null;
			boolean compared = false;
			boolean anyInlineCite = false;
			for (int s = 0; s < sentences.size(); s++) {
				if (sentences.get(s).cites(index)) {
					anyInlineCite = true;
					compared = true;
					double sim = similarity(recordVector, s, sentences, sentenceVectors, embedder);
					if (sim > best) {
						best = sim;
						bestSentence = sentences.get(s).text;
					}
				}
			}
			// No sentence cited this index inline (citations-array-only): fall
			// back to the best match against any sentence, so a record wholly
			// unrelated to the answer is still flagged without over-flagging a
			// record the model legitimately listed but did not inline-cite.
			if (!anyInlineCite) {
				for (int s = 0; s < sentences.size(); s++) {
					compared = true;
					double sim = similarity(recordVector, s, sentences, sentenceVectors, embedder);
					if (sim > best) {
						best = sim;
						bestSentence = sentences.get(s).text;
					}
				}
			}

			if (!compared) {
				return new Tier1Result(null, null, recordText); // no sentences (empty answer)
			}
			return new Tier1Result(Boolean.valueOf(best >= floor), bestSentence, recordText);
		}
		catch (RuntimeException e) {
			// Never break the search path on a verification failure; count it for the
			// single summary log in verify() rather than spamming per citation.
			stats.recordFailure(e);
			return new Tier1Result(null, null, recordText);
		}
	}

	/** Wraps the Tier-2 LLM call so a failure degrades to "could not verify" (null). */
	private Boolean safeEntails(String recordText, String claim, int index) {
		try {
			return llmProvider.entails(recordText, stripCitationMarkers(claim));
		}
		catch (RuntimeException e) {
			log.warn("Tier-2 entailment check failed for citation [{}]; keeping Tier-1 verdict", index, e);
			return null;
		}
	}

	/**
	 * Removes inline {@code [N]} citation markers so the entailment STATEMENT is
	 * the clinical claim alone — the markers are UI metadata, not part of what
	 * the record must support, and leaving them in only adds noise the fact-check
	 * LLM has to ignore.
	 */
	static String stripCitationMarkers(String text) {
		return ChartSearchAiUtils.INLINE_CITATION.matcher(text).replaceAll("").trim();
	}

	/** Tier-1 outcome plus the claim sentence and record text Tier-2 needs. */
	private static class Tier1Result {

		final Boolean verdict;

		final String bestSentence;

		final String recordText;

		Tier1Result(Boolean verdict, String bestSentence, String recordText) {
			this.verdict = verdict;
			this.bestSentence = bestSentence;
			this.recordText = recordText;
		}
	}

	private double similarity(float[] recordVector, int sentenceIdx,
			List<Sentence> sentences, Map<Integer, float[]> sentenceVectors, TextEmbedder embedder) {
		float[] sentenceVector = sentenceVectors.get(sentenceIdx);
		if (sentenceVector == null) {
			sentenceVector = embedder.embed(sentences.get(sentenceIdx).text);
			sentenceVectors.put(sentenceIdx, sentenceVector);
		}
		return ChartSearchAiUtils.cosineSimilarity(recordVector, sentenceVector);
	}

	private float[] embedRecord(int index, String recordText, Map<Integer, float[]> recordVectors,
			TextEmbedder embedder) {
		float[] vector = recordVectors.get(index);
		if (vector == null) {
			vector = embedder.embed(recordText);
			recordVectors.put(index, vector);
		}
		return vector;
	}

	/**
	 * Splits the answer into sentences, recording for each the set of {@code [N]}
	 * indices it cites inline. Returns an empty list for null/blank answers.
	 */
	static List<Sentence> splitIntoCitedSentences(String answer) {
		List<Sentence> sentences = new ArrayList<Sentence>();
		if (answer == null || answer.trim().isEmpty()) {
			return sentences;
		}
		for (String raw : SENTENCE_SPLIT.split(answer)) {
			if (raw.trim().isEmpty()) {
				continue;
			}
			Sentence sentence = new Sentence(raw);
			Matcher marker = ChartSearchAiUtils.INLINE_CITATION.matcher(raw);
			while (marker.find()) {
				sentence.citedIndexes.add(Integer.valueOf(marker.group(1)));
			}
			sentences.add(sentence);
		}
		return sentences;
	}

	/** An answer sentence and the citation indices it references inline. */
	static class Sentence {

		final String text;

		final java.util.Set<Integer> citedIndexes = new java.util.HashSet<Integer>();

		Sentence(String text) {
			this.text = text;
		}

		boolean cites(int index) {
			return citedIndexes.contains(Integer.valueOf(index));
		}
	}
}
