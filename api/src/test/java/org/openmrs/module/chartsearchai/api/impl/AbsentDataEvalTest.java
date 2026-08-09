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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.eval.EvalCase;
import org.openmrs.module.chartsearchai.eval.EvalDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Eval suite for absent-data detection: asked about a topic the chart carries nothing on, the system
 * must produce a clear "no records" answer that names what was asked about, and must not answer about
 * some other topic instead.
 *
 * <p><b>What this file used to do (issue #203).</b> Its {@code @MethodSource} filtered on
 * {@code !isExpectedAbsent()}, so all 19 {@code expectedAbsent} cases of the 29-case dataset were
 * dropped — silently, since a filtered case is not a skipped case: the build reported <b>10 tests, 0
 * skipped</b> and nothing looked wrong. The 10 that ran asserted only that a stopword-stripped
 * question was non-empty, i.e. a property of {@link QueryPreprocessor#stripQueryStopwords} (which
 * {@code LlmInferenceServiceTest} owns, with six cases including the all-stopwords one), under a class
 * name and javadoc claiming to test absent-data behaviour. The dataset's
 * {@code expectedAnswerContains}/{@code expectedAnswerNotContains} held exactly the claimed
 * expectations, {@link EvalCase} exposed them, and nothing read them.
 *
 * <p><b>How it is split now, and why.</b> Naming an absent topic requires a real answer, and a real
 * answer requires the LLM — there is no deterministic path in this module that writes "no records of
 * cancer"; the module's part is to put the chart and the question in front of the model, and the model
 * writes the sentence. Retrieval's part — which records an absent-topic query retrieves — belongs to
 * openmrs-module-querystore (issue #51) and is not assertable here at all. So:
 *
 * <ul>
 *   <li>{@link #absentTopicAnswerNamesWhatWasAskedAbout} runs all 19 absent cases against a real
 *       endpoint and asserts the dataset's expectations, joining the repo's opt-in convention
 *       ({@code PromptInjectionEvalTest}, {@code LlmAnswerQualityTest}) — <b>skipped, but visibly
 *       skipped</b>, which is the one thing the old filter was not.</li>
 *   <li>{@link #everyAbsentCaseIsRunAndEveryExpectationDiscriminates} runs unconditionally and is the
 *       guard against this defect recurring: it proves no absent case is filtered out of the run, and
 *       proves every single expectation of every case is one the assertion above would FAIL on. The
 *       same role the prompt-variation guard plays in {@code LlmAnswerQualityTest} — "the instrument
 *       has to assert it did something", and it has to do so in CI, where the LLM is not
 *       available.</li>
 *   <li>{@link #theEmptyChartPromptAsksTheModelToNameWhatIsMissing} runs unconditionally and asserts
 *       the deterministic half against real production code: the exact bytes
 *       {@link LlmProvider#buildUserMessage} sends when the chart yields no records.</li>
 *   <li>{@link #anAnswerNamingNoTopicFailsEveryCaseThatNamesOne} runs unconditionally and holds the
 *       oracle to the one thing it must never do: pass an answer that names nothing. It is what keeps
 *       {@code expectedAnswerContainsAny}'s OR lists (issue #216) from drifting into a grab-bag.</li>
 * </ul>
 *
 * <p>The 10 {@code expectedAbsent: false} cases are deliberately not run here — see
 * {@link #everyAbsentCaseIsRunAndEveryExpectationDiscriminates}, which asserts that they carry no
 * answer expectations, so their being unused is a checked property rather than a silent one.
 *
 * <p>Run the LLM-gated case with {@code -Dchartsearchai.absent.data.test=true} and optionally
 * {@code -Dchartsearchai.absent.data.endpoint=http://localhost:18085/v1/chat/completions}.
 */
public class AbsentDataEvalTest {

	private static final Logger log = LoggerFactory.getLogger(AbsentDataEvalTest.class);

	private static final String ENABLE_PROPERTY = "chartsearchai.absent.data.test";

	private static final String ENDPOINT_PROPERTY = "chartsearchai.absent.data.endpoint";

	/**
	 * Production's own ceiling, not a number chosen here. An answer cut off by the INSTRUMENT's token
	 * limit arrives as an empty string — {@link LlmProvider#extractResponse} cannot parse truncated JSON
	 * — so a ceiling below production's manufactures failures that read exactly like abstention defects.
	 * Both wrong values were measured against the bundled Gemma before this line said
	 * {@code DEFAULT_LLM_MAX_OUTPUT_TOKENS}: at 512, 2 of 19 answers came back empty; at 2048 against a
	 * full chart, 14 of 19 did.
	 */
	private static final int MAX_TOKENS = ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS;

	/**
	 * Answers that name no topic at all, verbatim as the shipped configuration produced them — issue
	 * #214's defect. Negative fixtures for
	 * {@link #anAnswerNamingNoTopicFailsEveryCaseThatNamesOne}: which cases they land on varies run to
	 * run, so they are pinned as text rather than tied to any case.
	 */
	private static final List<String> ANSWERS_NAMING_NO_TOPIC = Arrays.asList(
			"No patient records were provided.", "No records are provided.");

	private static EvalDataset dataset;

	/**
	 * The chart the gated case sends: EMPTY, which
	 * {@code LlmInferenceService.chartTextOrPlaceholder} and {@code LlmProvider.normalizeRecords} turn
	 * into the no-records placeholder. That is a real production shape, and it is the one under which
	 * every case's "this topic is absent" premise holds by construction.
	 *
	 * <p><b>A full, irrelevant chart would be the better shape and was tried and rejected — measured,
	 * so the next person does not repeat it.</b> It is the shape that actually fails in production (the
	 * querystore focus path has no relevance gate, so the K nearest neighbours are non-empty even when
	 * nothing in the chart is about the query — see {@link LlmProvider}'s focus-hint note), and it is
	 * the only shape in which an {@code expectedAnswerNotContains} can bite at all, since a chart with
	 * no records cannot bait a drift. {@link TestDatasetHelper#FULL_PATIENT_DATASET} looked like the
	 * chart this dataset was authored against: {@code absent-cancer} forbids the answer to mention CD4
	 * or tuberculosis and both are records in it.
	 *
	 * <p>It is not. That dataset's records 12 and 89 are {@code "Diagnosis — Kaposi sarcoma oral"}, so
	 * the patient HAS a cancer, and the bundled Gemma answered — correctly — "Yes, the patient has a
	 * diagnosis of Kaposi sarcoma oral [12] and [89]", which fails {@code absent-cancer}'s
	 * {@code expectedAnswerContains: ["cancer"]}. The one case that shape exists to serve is the one
	 * case it makes unpassable, and {@code absent-nutrition-diet} goes the same way against the Beef
	 * food-allergen record. Sending the full chart therefore reported dataset-premise failures as if
	 * they were abstention defects. Until a chart exists that the 19 topics really are absent from, this
	 * suite measures the easy shape and says so.
	 */
	private static String chartText() {
		return "";
	}

	private static EvalDataset getDataset() {
		if (dataset == null) {
			try {
				dataset = EvalDataset.load("eval/absent-data-eval-dataset.json");
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return dataset;
	}

	/** Every case the dataset marks {@code expectedAbsent}, in dataset order. */
	private static List<EvalCase> absentCases() {
		List<EvalCase> cases = new ArrayList<>();
		for (EvalCase evalCase : getDataset().getCases()) {
			if (evalCase.isExpectedAbsent()) {
				cases.add(evalCase);
			}
		}
		return cases;
	}

	static Stream<Arguments> absentDataCases() {
		List<Arguments> args = new ArrayList<>();
		for (EvalCase evalCase : absentCases()) {
			args.add(Arguments.of(evalCase.getId(), evalCase));
		}
		return args.stream();
	}

	/**
	 * Asked about a topic the chart carries nothing on, the answer must name what was asked about and
	 * must not answer about a different topic the chart DOES carry.
	 *
	 * <p>Every piece below the transport is production code: {@link LlmProvider#DEFAULT_SYSTEM_PROMPT},
	 * {@link LlmProvider#buildUserMessage} (the same method {@code search}, {@code searchStreaming} and
	 * {@code warmup} build their bytes with, and the method that turns the empty chart into the
	 * no-records placeholder), and {@link LlmProvider#extractResponse} for the reply. See
	 * {@link #chartText()} for which chart and why that one.
	 */
	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("absentDataCases")
	public void absentTopicAnswerNamesWhatWasAskedAbout(String caseId, EvalCase evalCase) throws Exception {
		Assumptions.assumeTrue("true".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY)),
				"Skipping: set -D" + ENABLE_PROPERTY + "=true to run");
		String endpoint = LlmEndpointTestSupport.endpoint(ENDPOINT_PROPERTY);
		Assumptions.assumeTrue(LlmEndpointTestSupport.isReachable(endpoint),
				"Skipping: LLM endpoint not reachable at " + endpoint);

		String raw = LlmEndpointTestSupport.complete(endpoint, LlmProvider.DEFAULT_SYSTEM_PROMPT,
				LlmProvider.buildUserMessage(chartText(), evalCase.getQuestion()), MAX_TOKENS);
		String answer = LlmProvider.extractResponse(raw).getAnswer();

		log.info("[{}] question='{}' answer='{}'", caseId, evalCase.getQuestion(), answer);

		assertAnswerMatchesExpectations(caseId, evalCase, answer);
	}

	/**
	 * The oracle, factored out so
	 * {@link #everyAbsentCaseIsRunAndEveryExpectationDiscriminates} can prove — without an LLM — that
	 * it rejects what it is supposed to reject, for every expectation of every case.
	 *
	 * <p>Case-insensitive on purpose: the expectations are topic words ({@code cancer},
	 * {@code CBC}, {@code ray}) and which case the model writes them in is its own word choice, not a
	 * property under test. Everything else is exact containment — a looser match (stemming, synonyms)
	 * would be this file re-deciding what the dataset means.
	 *
	 * <p>Two expectation lists, because one relation cannot carry both meanings (issue #216).
	 * {@code expectedAnswerContains} is AND: every element must appear, which is right when the topic
	 * has one name. {@code expectedAnswerContainsAny} is OR: one element suffices, which is the only
	 * way to say "names the topic by any of its names" when the correct names share no substring —
	 * asked "Are there any X-ray or radiology reports?", both <i>"No x-ray reports are recorded."</i>
	 * and <i>"No radiology reports are recorded."</i> name it, and a second AND element would only
	 * make the case stricter. An OR element is weaker than an AND element, so the bound on how loose
	 * the OR lists may get is asserted, not trusted — see
	 * {@link #anAnswerNamingNoTopicFailsEveryCaseThatNamesOne}.
	 */
	private static void assertAnswerMatchesExpectations(String caseId, EvalCase evalCase, String answer) {
		assertNotNull(answer, caseId + ": no answer was produced");
		String lower = answer.toLowerCase(Locale.ROOT);
		for (String expected : expectedContains(evalCase)) {
			assertTrue(lower.contains(expected.toLowerCase(Locale.ROOT)),
					caseId + ": the answer must name what was asked about ('" + expected + "'), was: "
							+ answer);
		}
		List<String> anyOf = expectedContainsAny(evalCase);
		if (!anyOf.isEmpty()) {
			boolean named = false;
			for (String alternative : anyOf) {
				if (lower.contains(alternative.toLowerCase(Locale.ROOT))) {
					named = true;
					break;
				}
			}
			assertTrue(named, caseId + ": the answer must name what was asked about by one of its "
					+ "names " + anyOf + ", was: " + answer);
		}
		if (evalCase.getExpectedAnswerNotContains() != null) {
			for (String forbidden : evalCase.getExpectedAnswerNotContains()) {
				assertFalse(lower.contains(forbidden.toLowerCase(Locale.ROOT)),
						caseId + ": the answer must not answer about a different topic ('" + forbidden
								+ "'), was: " + answer);
			}
		}
	}

	private static List<String> expectedContains(EvalCase evalCase) {
		return evalCase.getExpectedAnswerContains() == null ? new ArrayList<String>()
				: evalCase.getExpectedAnswerContains();
	}

	private static List<String> expectedContainsAny(EvalCase evalCase) {
		return evalCase.getExpectedAnswerContainsAny() == null ? new ArrayList<String>()
				: evalCase.getExpectedAnswerContainsAny();
	}

	/**
	 * The guard against issue #203 recurring, and it runs in CI where the LLM does not.
	 *
	 * <p>Two things it establishes. First, that the run covers every {@code expectedAbsent} case the
	 * dataset carries — the old provider dropped all 19 by predicate, and because a filtered case is
	 * not a skipped one, no count anywhere reported it. Second, that each of those cases' expectations
	 * actually <em>bites</em>: for every term, an answer missing it must fail the oracle, and for every
	 * forbidden term, an answer containing it must fail. That is §11 of the working brief applied to an
	 * assertion rather than a probe — a check offered as evidence has to be shown failing, and the
	 * showing belongs where it cannot be skipped.
	 *
	 * <p>This exercises the ORACLE, not the pipeline: the answers it feeds in are constructed from the
	 * dataset's own expectations, and no production behaviour is imitated. The pipeline half is
	 * {@link #absentTopicAnswerNamesWhatWasAskedAbout}, which is skipped without an endpoint, and
	 * {@link #theEmptyChartPromptAsksTheModelToNameWhatIsMissing}, which is not.
	 */
	@Test
	public void everyAbsentCaseIsRunAndEveryExpectationDiscriminates() {
		long absentInDataset = getDataset().getCases().stream().filter(EvalCase::isExpectedAbsent).count();
		assertTrue(absentInDataset > 0, "the dataset must carry absent-data cases at all");
		assertEquals(absentInDataset, absentDataCases().count(),
				"every expectedAbsent case must reach the run: filtering one out is invisible, because a "
						+ "case a @MethodSource never yields is not reported as skipped (issue #203)");

		for (EvalCase evalCase : absentCases()) {
			String caseId = evalCase.getId();
			List<String> contains = expectedContains(evalCase);
			List<String> anyOf = expectedContainsAny(evalCase);
			assertFalse(contains.isEmpty() && anyOf.isEmpty(),
					caseId + ": an absent case with neither expectedAnswerContains nor "
							+ "expectedAnswerContainsAny asserts nothing about the answer, so running it "
							+ "would be the same vacuity one level down");
			String compliant = compliantAnswerFor(evalCase);
			assertAnswerMatchesExpectations(caseId, evalCase, compliant);

			for (String expected : contains) {
				String missingOne = removeAll(compliant, expected);
				assertThrows(AssertionError.class,
						() -> assertAnswerMatchesExpectations(caseId, evalCase, missingOne),
						caseId + ": an answer that never names '" + expected + "' must fail this case, or "
								+ "that expectation is not being asserted: " + missingOne);
			}
			if (!anyOf.isEmpty()) {
				// An OR group bites as a group: only an answer carrying NONE of its alternatives may
				// fail it. Both halves are asserted, because each catches a different way to get the
				// group wrong — a dead alternative nothing could ever satisfy, and a group so loose
				// that dropping all of it still passes.
				String namingNone = compliant;
				for (String alternative : anyOf) {
					namingNone = removeAll(namingNone, alternative);
				}
				String withoutAnyAlternative = namingNone;
				assertThrows(AssertionError.class,
						() -> assertAnswerMatchesExpectations(caseId, evalCase, withoutAnyAlternative),
						caseId + ": an answer naming none of " + anyOf + " must fail this case, or the "
								+ "OR group is not being asserted: " + withoutAnyAlternative);
				for (String alternative : anyOf) {
					assertAnswerMatchesExpectations(caseId, evalCase,
							compliantAnswerNaming(evalCase, alternative));
				}
			}
			if (evalCase.getExpectedAnswerNotContains() != null) {
				for (String forbidden : evalCase.getExpectedAnswerNotContains()) {
					String withForbidden = compliant + " " + forbidden;
					assertThrows(AssertionError.class,
							() -> assertAnswerMatchesExpectations(caseId, evalCase, withForbidden),
							caseId + ": an answer that does mention '" + forbidden + "' must fail this "
									+ "case: " + withForbidden);
				}
			}
		}

		for (EvalCase evalCase : getDataset().getCases()) {
			if (!evalCase.isExpectedAbsent()) {
				// The present cases are inputs to a RETRIEVAL eval — "this topic must not read as absent" —
				// and retrieval is querystore's (issue #51), so this module cannot assert anything about
				// them. Asserted rather than assumed, so that adding an answer expectation to one of them
				// fails here instead of joining the dataset unread, which is how issue #203 started.
				assertTrue(evalCase.getExpectedAnswerContains() == null
						&& evalCase.getExpectedAnswerContainsAny() == null
						&& evalCase.getExpectedAnswerNotContains() == null,
						evalCase.getId() + ": a present case carries answer expectations, but nothing here "
								+ "runs them — either drive it from a suite that can retrieve records, or "
								+ "drop the expectations");
			}
		}
	}

	/**
	 * The bound on how loose an expectation may get, asserted against the answer text this suite
	 * exists to reject.
	 *
	 * <p>{@link #ANSWERS_NAMING_NO_TOPIC} are answers measured coming out of the shipped
	 * configuration (issue #214) that name <em>nothing</em> — they describe the record slice the model
	 * received rather than the patient, which is the failure a clinician cannot act on: "no imaging is
	 * recorded" and "the chart did not load" are different situations and this wording cannot tell
	 * them apart. Every case whose question names a topic must reject them.
	 *
	 * <p>This is what stops {@code expectedAnswerContainsAny} from becoming a grab-bag. Its elements
	 * are alternatives, so each one added is a new way to pass; an element generic enough to appear in
	 * a topic-less answer ({@code record}, {@code provided}, {@code no}) would silently retire the
	 * case, and {@link #everyAbsentCaseIsRunAndEveryExpectationDiscriminates} would not notice — an
	 * OR group still discriminates against an answer carrying none of its elements even when one
	 * element matches everything.
	 *
	 * <p>The exemption is asserted rather than skipped, so it cannot quietly grow.
	 * {@code absent-all-stopwords} asks "does the patient have any?", which names no topic at all, so
	 * there is nothing for its answer to name and its only expectation is that the answer is a
	 * negation. Any OTHER id appearing in this set is an expectation that has stopped testing what
	 * this file's name says.
	 */
	@Test
	public void anAnswerNamingNoTopicFailsEveryCaseThatNamesOne() {
		for (String topicLess : ANSWERS_NAMING_NO_TOPIC) {
			List<String> accepted = new ArrayList<>();
			for (EvalCase evalCase : absentCases()) {
				try {
					assertAnswerMatchesExpectations(evalCase.getId(), evalCase, topicLess);
					accepted.add(evalCase.getId());
				}
				catch (AssertionError rejected) {
					// Rejecting a topic-less answer is the property under test.
				}
			}
			assertEquals(Collections.singletonList("absent-all-stopwords"), accepted,
					"only absent-all-stopwords may accept \"" + topicLess + "\" — its question names no "
							+ "topic, so a negation is all its answer can be held to. Any other id here "
							+ "carries an expectation loose enough to be satisfied by an answer that names "
							+ "nothing, which is the defect this suite exists to catch (issue #214)");
		}
	}

	/**
	 * The deterministic half, in CI: what this module actually does for an absent topic is put the
	 * empty-records placeholder and the clinician's question in front of the model, and instruct it to
	 * name what is missing. Asserted on the exact bytes the real builder produces, because those bytes
	 * are also the KV-cache prefix contract ({@link LlmProvider#buildUserMessage}) — so an assertion
	 * looser than equality would not notice the placeholder being dropped, which is the regression that
	 * makes the model answer from demographics alone
	 * ({@code LlmInferenceService.chartTextOrPlaceholder}).
	 *
	 * <p>This is the only assertion about absent-data BEHAVIOUR that runs without an endpoint — the
	 * sibling CI case above is about the instrument, not the behaviour — which is why it also pins the
	 * system prompt's instruction: with the 19 answer-level cases skipped in CI, nothing else would
	 * notice that instruction leaving the prompt.
	 */
	@Test
	public void theEmptyChartPromptAsksTheModelToNameWhatIsMissing() {
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("If no records are relevant, name what is "
				+ "missing."),
				"the system prompt must still instruct the model to name what is missing — every case in "
						+ "this file depends on it, and they are all skipped without an endpoint");
		assertTrue(LlmProvider.DEFAULT_SYSTEM_PROMPT.contains("noun phrase of your own when the query "
				+ "states it as a verb"),
				"the system prompt must still tell the model to nominalise a verb-shaped query — without "
						+ "it, \"Does the patient smoke?\" has no phrase to lift and the answer falls back "
						+ "to describing the record slice (issue #214)");

		List<EvalCase> absent = absentCases();
		assertFalse(absent.isEmpty(), "precondition: there must be absent cases to build prompts for");
		for (EvalCase evalCase : absent) {
			assertEquals("Patient records (most recent first):\n"
					+ "This patient has no records matching this query.\n\n"
					+ "Clinician's query: " + evalCase.getQuestion(),
					LlmProvider.buildUserMessage("", evalCase.getQuestion()),
					evalCase.getId() + ": an empty chart must reach the model as the no-records placeholder "
							+ "followed by the question, or the answer is written from demographics alone");
		}
	}

	/** An answer that satisfies {@code evalCase} — built from its own expectations, so it names exactly
	 *  what the dataset says the real answer must name and nothing the dataset forbids. */
	private static String compliantAnswerFor(EvalCase evalCase) {
		List<String> anyOf = expectedContainsAny(evalCase);
		return compliantAnswerNaming(evalCase, anyOf.isEmpty() ? null : anyOf.get(0));
	}

	/** As {@link #compliantAnswerFor}, but naming {@code oneAlternative} as the case's single OR
	 *  alternative, so each alternative can be shown to satisfy the case on its own. */
	private static String compliantAnswerNaming(EvalCase evalCase, String oneAlternative) {
		StringBuilder sb = new StringBuilder("There are no records in this chart about");
		for (String expected : expectedContains(evalCase)) {
			sb.append(' ').append(expected);
		}
		if (oneAlternative != null) {
			sb.append(' ').append(oneAlternative);
		}
		return sb.append('.').toString();
	}

	/** {@code answer} with every case-insensitive occurrence of {@code term} removed, so the result
	 *  cannot satisfy that expectation however the scaffolding above happens to be worded. */
	private static String removeAll(String answer, String term) {
		return answer.replaceAll("(?i)" + Pattern.quote(term), "");
	}
}
