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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.FindingCitationExtent;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
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
 * Issue #395: the prompt carried seven screened interaction findings and the answer stated six,
 * dropping one of them entirely. Measured live on a RefApp 3.7.1 standalone against the bundled
 * knowledge base with the drug-reference layer enabled, two runs byte-identical — a
 * <em>"should i give Amlodipine?"</em> answer that opened <em>"No — Amlodipine should not be
 * given"</em> and enumerated six active orders, each with its own citation and its own rating,
 * while the seventh finding (Amlodipine &harr; Ibuprofen, Moderate, bridged to her active
 * {@code Advil 400mg}) was in the prompt at its own index and reached the prose nowhere.
 *
 * <p><b>Nothing on that response could see it, and each for its own correct reason.</b>
 * {@code unstatedFindingSeverities} read {@code []} because it asks of the WHOLE answer whether a
 * finding's rating word appears anywhere and <em>Moderate</em> appeared six times;
 * {@code unfaithfullyRenderedCitations} read {@code []} because the answer reproduced nothing to
 * substitute inside; {@code misattributedOrderCitations} read {@code []} because it judges the
 * citations that ARE offered; {@code interactionPairs} read {@code {found: 7, reported: 7}}, which
 * is true of the screen and says nothing about the prose. The residue was already named — in
 * {@link SafetyFindingSeverityFidelityCheck}'s own javadoc, quoting the prose check's
 * <em>"a hazard dropped by stopping early"</em> — and this file pins the half of it that citation
 * makes deterministic.
 *
 * <p><b>Everything here runs the real orchestration.</b> The chart is the real
 * {@link PatientChartSerializer}'s, the findings are the real
 * {@code DrugSafetyValidator} &rarr; {@code injectRecords} &rarr; {@code renderFinding} chain's
 * over the bundled knowledge base, and the extent is read off the {@link ChartAnswer} that the real
 * {@link LlmInferenceService#search}/{@code searchStreaming} returns. Only the model is stubbed:
 * answer prose is not reproducible on a live engine, and the answer is the one variable this
 * measurement is about.
 */
public class SafetyFindingCitationExtentTest {

	/** The sibling's question and partner list, reused deliberately: this check does not care what a
	 *  finding's rating is, only whether the answer cited it, so the arrangement it needs is
	 *  "several findings" and not "several ratings". */
	private static final String QUESTION = "Is it safe to start her on clarithromycin?";

	private static final String[] PARTNERS = { "Simvastatin", "Digoxin", "Sertraline", "Omeprazole" };

	private static final String[] PARTNER_ATC = { "C10AA01", "C01AA05", "N06AB06", "A02BC05" };

	/** The check's own logger: the narrowest capture that can satisfy an "it was reported"
	 *  assertion, so no other class's WARN can stand in for this check's. */
	private static final String CHECK = SafetyFindingCitationExtentCheck.class.getName();

	/** The package, for every assertion whose claim is SILENCE — a class-scoped capture of a silent
	 *  class receives nothing, which makes "no WARN was logged" pass vacuously
	 *  ({@link LogCapture}'s javadoc). Here {@code LlmInferenceService}'s own [timing] INFO proves
	 *  the capture is live. */
	private static final String PACKAGE = "org.openmrs.module.chartsearchai.api.impl";

	private PatientChart chart;

	private TestableService service;

	/** Every injected finding's citation index, in injection order — read off what production wrote,
	 *  never off a partner name this file chose. */
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
		service = newService(chart);
	}

	@Test
	public void theTicketsOwnAnswerShapeStatesTheFindingItNeverCited() {
		// The reported shape: every injected finding cited but the last, which reaches the prose
		// nowhere. This is the case the whole issue is, and it is red before the extent exists.
		List<Integer> allButLast = findings.subList(0, findings.size() - 1);
		Integer dropped = findings.get(findings.size() - 1);
		service.setLlmProvider(answering(enumerationCiting(allButLast)));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			FindingCitationExtent extent = answer.getFindingCitationExtent();
			assertNotNull(extent, "the check ran, so it must state a measurement");
			assertEquals(findings.size(), extent.getCarried(),
					"the prompt carried every injected finding");
			assertEquals(findings.size() - 1, extent.getCited(),
					"and the answer cited all but one of them, which is the whole defect");
			assertTrue(warnStating(capture, "[" + dropped + "]", "patient=1"),
					"the finding the answer never cited must be reported in one line, carrying the "
							+ "patient so a maintainer reading a log with concurrent requests in it "
							+ "can reconstruct it. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void anAnswerCitingEveryInjectedFindingStatesAFullExtentAndIsSilent() {
		// The other half of the pair: the case above fails if the extent measures nothing, this one
		// fails if a faithful answer is reported. Neither alone discriminates.
		service.setLlmProvider(answering(enumerationCiting(findings)));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(warnedByThisCheck(capture),
					"an answer that cites every finding is the shape this check exists to leave "
							+ "alone. Captured: " + capture.describeAll());
			FindingCitationExtent extent = answer.getFindingCitationExtent();
			assertEquals(findings.size(), extent.getCarried(), "every finding was carried");
			assertEquals(findings.size(), extent.getCited(), "and every one of them was cited");
		}
	}

	@Test
	public void anAnswerCitingNoFindingAtAllStatesZeroCitedRatherThanNoMeasurement() {
		// The extreme of the reported shape, and the one a client most needs told apart from a
		// failed check: the answer cites a chart record and no finding at all. Zero cited is a
		// measurement; null would say the module never looked.
		int order = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER);
		service.setLlmProvider(answering("She is on several medications [" + order + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			FindingCitationExtent extent = answer.getFindingCitationExtent();
			assertNotNull(extent, "zero cited is a measurement, not the absence of one");
			assertEquals(findings.size(), extent.getCarried(), "the prompt still carried them all");
			assertEquals(0, extent.getCited(),
					"and the answer cited none of them. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aCitedChartRecordIsNotCountedAsACitedFinding() {
		// The population is the injected findings and never every citation. An answer citing one
		// finding and three chart records has cited ONE finding — a check counting citations rather
		// than findings reads four here and calls the answer complete.
		int order = indexOfType(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER);
		Integer finding = findings.get(0);
		service.setLlmProvider(answering("Clarithromycin interacts with active order Simvastatin ["
				+ order + "] [" + finding + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertEquals(1, answer.getFindingCitationExtent().getCited(),
					"the chart record beside the finding is not a cited finding. Captured: "
							+ capture.describeAll());
			assertEquals(findings.size(), answer.getFindingCitationExtent().getCarried(),
					"and it is not a carried one either");
		}
	}

	@Test
	public void aChartCarryingNoInjectedFindingStatesAMeasurementOfNone() {
		// The shipped default: `chartsearchai.drugReference.enabled` is false, so the injector never
		// runs and no chart record is a finding. A zeroed extent says the prompt carried none, which
		// is what a client reads to know the question was asked at all.
		TestableService onBareChart = newService(baseChart());
		onBareChart.setLlmProvider(answering("She is on Simvastatin and Digoxin [1] [2]."));
		ChartAnswer answer = onBareChart.search(patient(), QUESTION);
		FindingCitationExtent extent = answer.getFindingCitationExtent();
		assertNotNull(extent, "a chart with no findings is a measurement of none, never a null");
		assertEquals(0, extent.getCarried(), "the prompt carried no finding");
		assertEquals(0, extent.getCited(), "so the answer cited none");
	}

	@Test
	public void aBlankAnswerCarryingResolvedCitationsStatesWhatItCited() {
		// A degenerate output, and a REACHABLE one: extractCitedReferences resolves the structured
		// citations array for a blank answer deliberately. Unlike its siblings this measurement has
		// no reason to fall silent there — it counts citations rather than judging prose, and the
		// citations really did resolve. What it must NOT do is report a defect: a blank answer is
		// not a dropped hazard, so nothing is logged.
		Integer finding = findings.get(0);
		service.setLlmProvider(new StubProvider("   ", Collections.singletonList(finding)));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			ChartAnswer answer = service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(warnedByThisCheck(capture),
					"a blank answer is not a dropped finding. Captured: " + capture.describeAll());
			assertFalse(answer.getReferences().isEmpty(),
					"the premise: the structured array really did resolve for this blank answer");
			assertEquals(1, answer.getFindingCitationExtent().getCited(),
					"and the citation it resolved is counted, because that is what happened");
		}
	}

	@Test
	public void oneFindingCitedTwiceIsOneCitedFinding() {
		// The count is of FINDINGS and not of markers. An answer that cites one finding in two
		// sentences has stated one, and a check adding up markers reads two — which on a chart
		// carrying two findings would call that answer complete.
		//
		// It does NOT pin the check's own set, and is here saying so rather than looking as though
		// it does: extractCitedReferences already collects into a LinkedHashSet and emits one
		// reference per index, so swapping the check's set for a list that always adds leaves this
		// green. What this case holds is the PROPERTY at the production boundary — that a repeated
		// marker cannot inflate the published count — wherever the de-duplication lives.
		Integer finding = findings.get(0);
		service.setLlmProvider(answering("Clarithromycin interacts with an active order ["
				+ finding + "]. That interaction [" + finding + "] is the reason."));
		ChartAnswer answer = service.search(patient(), QUESTION);
		assertEquals(1, answer.getFindingCitationExtent().getCited(),
				"one finding cited twice is one cited finding");
	}

	@Test
	public void searchStreaming_statesItOnThePrimaryProductionPathToo() {
		// /search/stream is the path users hit: a measurement wired only into search() would be
		// absent from production traffic while every non-streaming case here stayed green.
		List<Integer> allButLast = findings.subList(0, findings.size() - 1);
		service.setLlmProvider(answering(enumerationCiting(allButLast)));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.searchStreaming(patient(), QUESTION, token -> { });
			assertEquals(findings.size() - 1, answer.getFindingCitationExtent().getCited(),
					"the streaming path must state the same measurement. Captured: "
							+ capture.describeAll());
			assertEquals(findings.size(), answer.getFindingCitationExtent().getCarried(),
					"over the same carried population");
		}
	}

	@Test
	public void searchStreaming_statesNoMeasurementOnTheEarlyAnswerAndOneOnTheAnswerItReturns() {
		// The early `done` of the async-grounding path is built BEFORE this check runs, so it has no
		// measurement to state — and null says exactly that, where a zeroed extent would tell a
		// client the findings had been counted and none cited.
		//
		// The null assertion alone does NOT establish it: production hands the early answer an
		// explicit null in that argument, so it states null wherever the check runs. The log
		// snapshot at handoff is what makes moving the check above the handoff redden this case.
		List<Integer> allButLast = findings.subList(0, findings.size() - 1);
		service.setLlmProvider(answering(enumerationCiting(allButLast)));
		final List<FindingCitationExtent> early = new ArrayList<FindingCitationExtent>();
		final List<List<String>> loggedByHandoff = new ArrayList<List<String>>();

		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = service.searchStreaming(patient(), QUESTION, token -> { },
					reasoning -> { }, citations -> { },
					ungrounded -> {
						early.add(ungrounded.getFindingCitationExtent());
						loggedByHandoff.add(capture.describeAll());
					});

			assertEquals(1, early.size(), "the early-done consumer must have fired");
			assertNull(early.get(0),
					"the check runs after the user-visible handoff, so the early answer states no "
							+ "measurement");
			assertTrue(capture.describeAll().toString().contains(WARN_PHRASE),
					"the capture must have received the check's own WARN by the end, or the negative "
							+ "below is satisfied by a capture that was never attached — the vacuity "
							+ "this file's PACKAGE constant names. Captured: " + capture.describeAll());
			assertFalse(loggedByHandoff.get(0).toString().contains(WARN_PHRASE),
					"and the check must not have RUN by then — moving it ahead of the handoff puts a "
							+ "walk in front of the event a user sees. Captured at handoff: "
							+ loggedByHandoff.get(0));
			assertEquals(findings.size() - 1, answer.getFindingCitationExtent().getCited(),
					"and the answer this method RETURNS carries the measurement");
		}
	}

	/** The wording the WARN is recognised by, in one place: two cases assert on it and three assert
	 *  its ABSENCE, so a reworded log line must move all five together. Chosen as the part of the
	 *  line that is present whatever the counts are — the line states "cites N of M" and N is not
	 *  fixed — and distinctive to this check rather than to the family. */
	private static final String WARN_PHRASE = "injected safety finding";

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

	private int indexOfType(String resourceType) {
		for (RecordMapping mapping : chart.getMappings()) {
			if (resourceType.equals(mapping.getResourceType())) {
				return mapping.getIndex();
			}
		}
		throw new IllegalStateException("no " + resourceType + " record in: " + chart.getText());
	}

	private static Set<String> setOf(String... values) {
		return new LinkedHashSet<String>(Arrays.asList(values));
	}

	/** @return whether one WARN carries every one of {@code required} */
	private static boolean warnStating(LogCapture capture, String... required) {
		return capture.hasMessageAt(Level.WARN, required);
	}

	/** @return whether THIS check warned, for a case that captures the package — so the pipeline's
	 *          own INFO line proves the capture is live — but claims silence only of this check.
	 *          It asks by this check's own wording rather than by composing two package-wide
	 *          questions, for the reason the sibling's helper of this name states: "something warned
	 *          AND nothing but this check warned" is false whenever a NEIGHBOUR warns too. */
	private static boolean warnedByThisCheck(LogCapture capture) {
		return capture.hasMessageAt(Level.WARN, WARN_PHRASE);
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	private static StubProvider answering(String answer) {
		return new StubProvider(answer);
	}

	/** The base chart, rendered by the REAL serializer: the patient's own drug orders, so a citation
	 *  of one can be shown not to count as a finding. */
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
	private TestableService newService(PatientChart served) {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(served));
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question) {
				return chart;
			}
		});
		created.setDrugSafetyValidator(new DrugSafetyValidator() {

			// The overload production actually calls: mappings-carrying for echo scoping (issue
			// #105) and sink-carrying since issue #336. Stubbing a shorter one instead leaves this
			// stub INERT — production would not reach it — which is why this names both parameters.
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

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
			return false;
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

	private static final class StubProvider extends LlmProvider {

		private final String answer;

		private final List<Integer> citations;

		private StubProvider(String answer) {
			this(answer, Collections.<Integer> emptyList());
		}

		/** The structured-citations arity: the one arrangement that reaches this check with cited
		 *  records and no prose to read them in. */
		private StubProvider(String answer, List<Integer> citations) {
			this.answer = answer;
			this.citations = citations;
		}

		private LlmResponse canned() {
			return new LlmResponse(answer, citations);
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question) {
			return canned();
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope) {
			return canned();
		}
	}
}
