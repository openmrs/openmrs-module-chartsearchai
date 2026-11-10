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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Scores {@link LlmProvider#findingsBearingOn} — its prompt, response format and parsing, all production's — against a
 * real model over findings the demo replica injected for questions asking what one drug does to her, each labelled by
 * whether what it states bears on the effect, reaction or reason the question names (ADR Decision 613).
 *
 * <p>Two gates, matching what the judgement costs. A related finding it drops is lost from the answer, so it must keep
 * at least {@link #MIN_RELATED_KEPT_PERCENT}; an unrelated finding it keeps is shown beside an answer it does not bear
 * on, which is what the judgement exists to stop, so it must remove at least {@link #MIN_UNRELATED_REMOVED_PERCENT}.
 * Borderline findings are printed and not scored. Opt-in: {@code -Dchartsearchai.finding.relevance.test=true}, against the
 * server {@code -Dchartsearchai.finding.relevance.endpoint} names.
 */
public class FindingRelevanceEvalTest {

	private static final String ENABLE_PROPERTY = "chartsearchai.finding.relevance.test";

	private static final String ENDPOINT_PROPERTY = "chartsearchai.finding.relevance.endpoint";

	private static final String CASES = "chartsearchai-eval/finding-relevance-eval.json";

	/** The share of related findings the judgement must keep. */
	private static final int MIN_RELATED_KEPT_PERCENT = 75;

	/** The share of unrelated findings the judgement must remove. */
	private static final int MIN_UNRELATED_REMOVED_PERCENT = 60;

	@Test
	public void theJudgementKeepsWhatBearsOnTheQuestionAndRemovesWhatDoesNot() throws Exception {
		LlmEndpointTestSupport.assumeOptedIn(ENABLE_PROPERTY);
		String endpoint = LlmEndpointTestSupport.endpoint(ENDPOINT_PROPERTY);
		Assumptions.assumeTrue(LlmEndpointTestSupport.isReachable(endpoint), "Skipping: no LLM at " + endpoint);
		LlmProvider provider = new EndpointProvider(endpoint);
		JsonNode cases;
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(CASES)) {
			assertNotNull(in, CASES);
			cases = new ObjectMapper().readTree(in).get("cases");
		}
		int related = 0;
		int relatedKept = 0;
		int unrelated = 0;
		int unrelatedRemoved = 0;
		List<String> dropped = new ArrayList<String>();
		for (JsonNode c : cases) {
			String question = c.get("question").textValue();
			List<String> findings = new ArrayList<String>();
			for (JsonNode f : c.get("findings")) {
				findings.add(f.get("finding").textValue());
			}
			Set<Integer> kept = provider.findingsBearingOn(question, findings);
			assertNotNull(kept, "the model judged nothing for: " + question);
			for (int i = 0; i < findings.size(); i++) {
				String label = c.get("findings").get(i).get("label").textValue();
				boolean keeps = kept.contains(Integer.valueOf(i));
				System.out.println("FINDING-RELEVANCE-EVAL " + label + " | kept=" + keeps + " | " + question + " | "
						+ findings.get(i).substring(0, Math.min(120, findings.get(i).length())));
				if ("related".equals(label)) {
					related++;
					relatedKept += keeps ? 1 : 0;
					if (!keeps) {
						dropped.add(question + " | " + findings.get(i));
					}
				}
				else if ("unrelated".equals(label)) {
					unrelated++;
					unrelatedRemoved += keeps ? 0 : 1;
				}
			}
		}
		System.out.println("FINDING-RELEVANCE-EVAL related kept " + relatedKept + " of " + related + "; unrelated removed "
				+ unrelatedRemoved + " of " + unrelated);
		assertTrue(relatedKept * 100 >= related * MIN_RELATED_KEPT_PERCENT, "related findings dropped: " + dropped);
		assertTrue(unrelatedRemoved * 100 >= unrelated * MIN_UNRELATED_REMOVED_PERCENT,
			"unrelated findings removed: " + unrelatedRemoved + " of " + unrelated);
	}

	/** Production's provider over an engine posting to the tester's server, so only the transport is the test's. */
	private static final class EndpointProvider extends LlmProvider {

		private final LlmEngine engine;

		private EndpointProvider(String endpoint) {
			this.engine = new EndpointEngine(endpoint);
		}

		@Override
		LlmEngine getActiveEngine() {
			return engine;
		}

		@Override
		protected int getTimeoutSeconds() {
			return FINDING_RELEVANCE_TIMEOUT_SECONDS;
		}
	}

	private static final class EndpointEngine implements LlmEngine {

		private final String endpoint;

		private EndpointEngine(String endpoint) {
			this.endpoint = endpoint;
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds,
				ObjectNode responseFormat) {
			try {
				return new InferenceResult(
						LlmEndpointTestSupport.complete(endpoint, systemPrompt, userMessage, 200, responseFormat), 0, 0, 0);
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds) {
			throw new AssertionError("only the structured judgement is scored here");
		}

		@Override
		public InferenceResult infer(String systemPrompt, String userMessage, int timeoutSeconds,
				ReferenceRecords referenceRecords) {
			throw new AssertionError("only the structured judgement is scored here");
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage, int timeoutSeconds,
				Consumer<String> tokenConsumer) {
			throw new AssertionError("only the structured judgement is scored here");
		}

		@Override
		public InferenceResult inferStreaming(String systemPrompt, String userMessage, int timeoutSeconds,
				Consumer<String> tokenConsumer, String cacheScope, String cacheSeed, ReferenceRecords referenceRecords) {
			throw new AssertionError("only the structured judgement is scored here");
		}

		@Override
		public void warmup(String systemPrompt, String userMessage, int timeoutSeconds) {
		}

		@Override
		public void close() {
		}

		@Override
		public void shutdown() {
		}
	}
}
