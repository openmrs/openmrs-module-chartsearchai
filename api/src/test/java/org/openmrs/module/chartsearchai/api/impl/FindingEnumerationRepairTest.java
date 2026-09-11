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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.FindingCitationExtent;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.ChartReadStatus;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;

/**
 * Issue #398. {@code SafetyFindingCitationExtentCheck} made the shortfall MEASURABLE
 * ({@code findingCitations}) and ADR Decision 83 refused to close it, on the grounds that the only
 * lever was prompt wording; ADR Decision 84 then took the one prompt lever that worked and recorded
 * the result as <em>"an improvement and not a fix"</em> — six of twelve corpus cells still state one
 * finding fewer than the prompt carried. Decision 83's own rejected-alternatives section named what
 * would make a further remedy attemptable: <em>"whoever takes it later has a gate to take it
 * against, which is what this key is"</em>. This is that remedy, and the gate is that key.
 *
 * <p><b>Why a repair rather than another wording.</b> Decision 84's ledger is five arms over one
 * corpus and its finding is that POSITION beat wording; the clause's bytes are pinned as a literal
 * by {@code LlmProviderUserMessageTest} precisely so completeness cannot be bought by rewording it,
 * and every remaining prompt-shaped alternative — an ordinal in the record, compacting the slice,
 * reordering it, a general format rule — is refused there with its own reason. What none of them
 * refuses is asking again: the module already knows, deterministically and before it publishes
 * anything, WHICH findings the answer left uncited.
 *
 * <p><b>Why it appends rather than replaces.</b> {@code searchStreaming} hands the answer to the
 * caller token by token, so by the time the shortfall is knowable the user has already watched the
 * short answer being written; replacing it is available on {@code search} and on that path only, and
 * two answer paths that differ in what they do with a repair is the divergence this package's rules
 * warn about throughout. A continuation is the one shape both paths can carry, and it keeps the
 * answer wholly model-authored — this module never writes clinical prose into an answer, which is
 * what {@code safetyWarnings} exists to do instead.
 *
 * <p><b>What this class does NOT establish.</b> That the repair improves the corpus. It pins the
 * mechanism — that the omitted findings are named, that a second call is made only when one is
 * owed, that a continuation buying nothing is discarded — and the corpus question is
 * {@code eval/drift-metric/capture_probe_safety.sh}'s, read beside {@code unstatedFindingSeverities}
 * and the verdict-lead cell, which are the two columns Decision 84 records regressions in.
 */
public class FindingEnumerationRepairTest {

	/** One question that puts one drug in play against four of the patient's active orders, so the
	 *  real screen raises several findings about one subject — the arrangement the measured corpus
	 *  is made of, and the premise every case here asserts off the injected chart. */
	private static final String QUESTION = "Is it safe to start her on clarithromycin?";

	private static final String[] PARTNERS = { "Simvastatin", "Digoxin", "Sertraline", "Omeprazole" };

	private static final String[] PARTNER_ATC = { "C10AA01", "C01AA05", "N06AB06", "A02BC05" };

	private PatientChart chart;

	private List<Integer> findings;

	@BeforeEach
	public void setUp() {
		chart = DrugReferenceTestSupport.injectedFindingsOver(baseChart(), QUESTION,
				setOf(PARTNERS), setOf(PARTNER_ATC));
		findings = new ArrayList<Integer>();
		for (RecordMapping mapping : DrugReferenceTestSupport.injectedFindings(chart)) {
			findings.add(Integer.valueOf(mapping.getIndex()));
		}
		assertTrue(findings.size() > 2,
				"the premise every case below rests on: the real pipeline injects at least three "
						+ "findings, so an answer citing all but one can be told from one citing none. "
						+ "Injected: " + findings);
	}

	@Test
	public void theTicketsOwnAnswerShapeIsRepairedIntoOneThatStatesEveryFinding() {
		// The reported shape: every injected finding cited but the last. With the repair on, the
		// answer the caller receives must state that finding too, and the extent published beside it
		// must be measured over the REPAIRED prose — an extent still reading "one short" would mean
		// the repair changed the answer and left the key describing the answer it replaced.
		List<Integer> allButLast = findings.subList(0, findings.size() - 1);
		Integer dropped = findings.get(findings.size() - 1);
		StubProvider provider = answeringInTurn(enumerationCiting(allButLast),
				continuationCiting(dropped));
		TestableService service = newService(chart, provider, true);

		ChartAnswer answer = service.search(patient(), QUESTION);

		assertEquals(2, provider.calls(),
				"the repair must ask again exactly once for a shortfall of one; asking per missing "
						+ "finding is a cost this module cannot spend and asking twice is a loop");
		assertTrue(answer.getAnswer().contains("[" + dropped + "]"),
				"the finding the first answer never cited must be stated in the answer the caller "
						+ "receives. Answer: " + answer.getAnswer());
		assertTrue(answer.getAnswer().startsWith("No — Clarithromycin should not be started"),
				"and the original answer's own opening must survive the repair: the verdict lead is "
						+ "the property ADR Decision 84 measured a completeness arm LOSING, so a "
						+ "repair that rewrites the lead trades the defect for that one. Answer: "
						+ answer.getAnswer());
		FindingCitationExtent extent = answer.getFindingCitationExtent();
		assertNotNull(extent, "the check ran, so it must state a measurement");
		assertEquals(findings.size(), extent.getCarried(), "the prompt carried every injected finding");
		assertEquals(findings.size(), extent.getCited(),
				"and the published extent must be of the REPAIRED answer, which cites them all");
	}

	@Test
	public void theSecondCallIsAskedOnlyForTheFindingsTheFirstAnswerLeftUncited() {
		// The repair's own subject. Asking about findings the answer already stated is how a second
		// pass restates what is there instead of adding what is not, and the question it is asked is
		// the only place that scoping is expressed.
		List<Integer> allButLast = findings.subList(0, findings.size() - 1);
		Integer dropped = findings.get(findings.size() - 1);
		StubProvider provider = answeringInTurn(enumerationCiting(allButLast),
				continuationCiting(dropped));

		newService(chart, provider, true).search(patient(), QUESTION);

		String repairQuestion = provider.questionAt(1);
		assertTrue(repairQuestion.contains(String.valueOf(dropped)),
				"the repair must name the record it is asking for. Asked: " + repairQuestion);
		for (Integer cited : allButLast) {
			assertTrue(!repairQuestion.contains(String.valueOf(cited)),
					"and must not name a finding the answer already stated — record " + cited
							+ " was cited. Asked: " + repairQuestion);
		}
	}

	@Test
	public void anAnswerThatAlreadyStatesEveryFindingIsNotAskedAgain() {
		// The other half of the pair: the case above fails if nothing repairs, this one fails if the
		// repair fires on a complete answer. Neither alone discriminates, and a second call here is
		// a doubled inference bought for nothing on every complete safety answer.
		StubProvider provider = answeringInTurn(enumerationCiting(findings));
		TestableService service = newService(chart, provider, true);

		ChartAnswer answer = service.search(patient(), QUESTION);

		assertEquals(1, provider.calls(),
				"a complete answer owes no repair, so the model must be asked exactly once");
		assertEquals(findings.size(), answer.getFindingCitationExtent().getCited(),
				"and the extent must still read complete");
	}

	@Test
	public void aContinuationThatCitesNothingNewIsDiscardedAndTheAnswerStandsUnchanged() {
		// The repair may only ADD. A model that answers the follow-up with prose citing nothing —
		// or citing only what was already stated — must leave the answer exactly as it was, because
		// appending it would spend the caller's screen on text that carries no finding while making
		// the answer look longer for it.
		List<Integer> allButLast = findings.subList(0, findings.size() - 1);
		String original = enumerationCiting(allButLast);
		StubProvider provider = answeringInTurn(original, "There is nothing further to add.");
		TestableService service = newService(chart, provider, true);

		ChartAnswer answer = service.search(patient(), QUESTION);

		assertEquals(2, provider.calls(), "the premise: the repair did run");
		assertEquals(original, answer.getAnswer(),
				"a continuation citing no uncited finding must be discarded whole, leaving the "
						+ "original answer byte for byte. Answer: " + answer.getAnswer());
		assertEquals(findings.size() - 1, answer.getFindingCitationExtent().getCited(),
				"and the extent must report the shortfall that still stands, not hide it");
	}

	@Test
	public void withTheRepairOffTheAnswerAndItsExtentAreExactlyWhatTheyWereBefore() {
		// The gate. This is the shipped default and it is the whole of ADR Decision 84's measured
		// behaviour, so this case is what says the repair is an addition to that arrangement rather
		// than a change of it.
		List<Integer> allButLast = findings.subList(0, findings.size() - 1);
		String original = enumerationCiting(allButLast);
		StubProvider provider = answeringInTurn(original, continuationCiting(
				findings.get(findings.size() - 1)));
		TestableService service = newService(chart, provider, false);

		ChartAnswer answer = service.search(patient(), QUESTION);

		assertEquals(1, provider.calls(),
				"with the repair off the model must be asked exactly once, whatever the answer left "
						+ "uncited");
		assertEquals(original, answer.getAnswer(), "and the answer must be untouched");
		assertEquals(findings.size() - 1, answer.getFindingCitationExtent().getCited(),
				"and the extent must report the shortfall, which is issue #395's whole payload");
	}

	@Test
	public void searchStreaming_repairsOnThePrimaryProductionPathToo() {
		// The path the module actually serves. A repair that reaches only `search` would fix the
		// arrangement nobody runs: `/search/stream` is what the frontend calls, and every key on
		// this response has a case saying it reaches both paths.
		List<Integer> allButLast = findings.subList(0, findings.size() - 1);
		Integer dropped = findings.get(findings.size() - 1);
		StubProvider provider = answeringInTurn(enumerationCiting(allButLast),
				continuationCiting(dropped));
		TestableService service = newService(chart, provider, true);

		ChartAnswer answer = service.searchStreaming(patient(), QUESTION, noop(), noop(), noopCitations(),
				noopAnswer());

		assertEquals(2, provider.calls(), "the streaming path must repair as `search` does");
		assertTrue(answer.getAnswer().contains("[" + dropped + "]"),
				"and the answer it returns must state the finding the first answer dropped. Answer: "
						+ answer.getAnswer());
		assertEquals(findings.size(), answer.getFindingCitationExtent().getCited(),
				"with the extent measured over the repaired prose");
	}

	@Test
	public void theStreamedContinuationReachesTheCallerAsTokensRatherThanOnlyInTheFinalAnswer() {
		// What the user watching the answer being written must see. The first answer streams, the
		// shortfall is only knowable once it has finished, and a repair whose text arrives solely in
		// the returned object leaves the streamed answer short on the one surface a clinician reads.
		List<Integer> allButLast = findings.subList(0, findings.size() - 1);
		Integer dropped = findings.get(findings.size() - 1);
		StubProvider provider = answeringInTurn(enumerationCiting(allButLast),
				continuationCiting(dropped));
		TestableService service = newService(chart, provider, true);
		final StringBuilder streamed = new StringBuilder();

		service.searchStreaming(patient(), QUESTION, new Consumer<String>() {

			@Override
			public void accept(String token) {
				streamed.append(token);
			}
		}, noop(), noopCitations(), noopAnswer());

		assertTrue(streamed.toString().contains("[" + dropped + "]"),
				"the repair's own prose must reach the token consumer, or the streamed answer stays "
						+ "short while the returned one is whole. Streamed: " + streamed);
	}

	/** An answer that names each cited record in one flat clause — the ticket's own shape. */
	private static String enumerationCiting(Iterable<Integer> indexes) {
		StringBuilder prose = new StringBuilder("No — Clarithromycin should not be started");
		String separator = ": ";
		for (Integer index : indexes) {
			prose.append(separator).append("Clarithromycin interacts with an active order [")
					.append(index).append("]");
			separator = ", ";
		}
		return prose.append(".").toString();
	}

	/** The shape a repair answer takes: the omitted finding alone, in the same clause form. */
	private static String continuationCiting(Integer index) {
		return "Clarithromycin interacts with an active order [" + index + "], Moderate.";
	}

	private static Set<String> setOf(String... values) {
		return new LinkedHashSet<String>(Arrays.asList(values));
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	private static Consumer<String> noop() {
		return new Consumer<String>() {

			@Override
			public void accept(String value) {
			}
		};
	}

	private static Consumer<List<RecordReference>> noopCitations() {
		return new Consumer<List<RecordReference>>() {

			@Override
			public void accept(List<RecordReference> value) {
			}
		};
	}

	private static Consumer<ChartAnswer> noopAnswer() {
		return new Consumer<ChartAnswer>() {

			@Override
			public void accept(ChartAnswer value) {
			}
		};
	}

	private static StubProvider answeringInTurn(String... answers) {
		return new StubProvider(answers);
	}

	/** The base chart, rendered by the REAL serializer: the patient's own drug orders, so the
	 *  injector has prescriptions to raise findings against. */
	private static PatientChart baseChart() {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-1", "Simvastatin 20mg tablet, 1 daily", null));
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-2", "Digoxin 125mcg tablet, 1 daily", null));
		return new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
	}

	/** The same seam the sibling fidelity tests use, and defined here for the same reason they each
	 *  define their own: these are private harnesses, not a shared one. */
	private TestableService newService(PatientChart served, StubProvider provider, boolean repair) {
		TestableService created = new TestableService(repair);
		created.setChartBuildingStrategy(new StubStrategy(served));
		created.setLlmProvider(provider);
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question,
					ChartReadStatus readStatus) {
				return chart;
			}
		});
		created.setDrugSafetyValidator(new DrugSafetyValidator() {

			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
				return Collections.emptyList();
			}
		});
		return created;
	}

	/** Subclass that no-ops the Context-backed resolvers so no OpenMRS runtime is needed. */
	private static final class TestableService extends LlmInferenceService {

		private final boolean repair;

		private TestableService(boolean repair) {
			this.repair = repair;
		}

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
			return false;
		}

		@Override
		protected boolean resolveFindingEnumerationRepair() {
			return repair;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		private final PatientChart chart;

		private StubStrategy(PatientChart chart) {
			this.chart = chart;
		}

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return chart;
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	/** Answers in turn, and records what it was asked — the repair's second call is a question this
	 *  module composes, so a case has to be able to read it. */
	private static final class StubProvider extends LlmProvider {

		private final String[] answers;

		private final List<String> questions = new ArrayList<String>();

		private StubProvider(String... answers) {
			this.answers = answers;
		}

		private int calls() {
			return questions.size();
		}

		private String questionAt(int call) {
			return questions.get(call);
		}

		private LlmResponse canned(String question) {
			questions.add(question);
			int at = Math.min(questions.size() - 1, answers.length - 1);
			return new LlmResponse(answers[at], Collections.<Integer> emptyList());
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices,
				String question, boolean enumerateFindings) {
			return canned(question);
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings) {
			LlmResponse response = canned(question);
			if (tokenConsumer != null) {
				tokenConsumer.accept(response.getAnswer());
			}
			return response;
		}
	}
}
