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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.FindingPartnerCoverage;
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
 * The orders the module appends to an answer, and the {@code findingPartners} count beside them, are
 * those of the findings the answer CITED — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/516">#516</a>, ADR Decision
 * 100.
 *
 * <p><b>The defect.</b> The appended sentence reads <em>"Also covered by those findings and not named
 * above: …"</em>, and it named the orders of every chip the response raised — ADR Decision 100's
 * amendment records what that measured on the demo seed.
 *
 * <p>Everything here runs the real {@link LlmInferenceService#search}/{@code searchStreaming} over a
 * chart whose {@code safety_finding} records the REAL injector wrote, and — in every case but the dedup
 * one, whose failure on that code is that it appended nothing — the post-answer validator hands back the
 * REAL chips the same arrangement raises: every chip, which is the population the defect read from, so
 * each case fails on the code that read it. Only the model is stubbed:
 * answer prose is not reproducible on a live engine, and the answer is the input these cases vary —
 * with, in the two structured-array cases, the citations array the model emits beside it.
 * Every order name an answer carries is read off the chips or the records, except the spacing case's,
 * whose two displays are spelled here and asserted against the chip before the answer is built.
 */
public class CitedFindingPartnerCompletionTest {

	/** What tells the merged finding's record and chip from the other one. */
	private static final String CORTICOSTEROID_MECHANISM = DrugReferenceTestSupport.SHARED_MECHANISM_TEXT;

	/** What tells issue #477's finding — a drug already carried by several of her orders — from the
	 *  interaction findings beside it. */
	private static final String ALREADY_IN_ORDERS = "is already in active orders";

	@Test
	public void theOrdersOfTheCitedFindingTheAnswerLeftUnnamedAreNamedByTheModuleItself()
			throws IOException {
		// ADR Decision 100, measured live on the 3.7.1 standalone (2026-09-15): the finding named five
		// orders and the prose named four. The module composed the finding, so it can finish the
		// sentence without asking the model again. Rewritten for issue #516 so the answer CITES the
		// finding it leaves incomplete — through the record the real injector wrote.
		SharedMechanism arrangement = new SharedMechanism();
		String firstOrder = arrangement.mergedOrders.get(0);
		String modelAnswer = "No — aspirin should not be given: it interacts with her " + firstOrder
				+ " [" + arrangement.merged.getIndex() + "].";

		ChartAnswer answer = arrangement.service(modelAnswer).search(patient(),
				DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION);

		String completed = answer.getAnswer().toLowerCase(Locale.ROOT);
		for (String order : arrangement.mergedOrders) {
			assertTrue(completed.contains(order.toLowerCase(Locale.ROOT)),
					"every order of the finding the answer cited must reach the answer a client is "
							+ "handed, whether the model named it or the module did. Missing " + order
							+ " from: " + answer.getAnswer());
		}
		assertTrue(answer.getAnswer().startsWith(modelAnswer),
				"and it APPENDS, so the verdict lead the model wrote is never re-decided, was: "
						+ answer.getAnswer());
		assertNotNull(answer.getFindingPartnerCoverage(), "the answer cited a finding, so it is measured");
		assertEquals(arrangement.mergedOrders.size(), answer.getFindingPartnerCoverage().getNamed(),
				"named counts the orders of the finding the answer CITED, was: "
						+ answer.getFindingPartnerCoverage());
		assertEquals(1, answer.getFindingPartnerCoverage().getStated(),
				"and stated the one of them the model's prose named, was: "
						+ answer.getFindingPartnerCoverage());
	}

	@Test
	public void anOrderOfAFindingTheAnswerDidNotCiteIsNotCreditedToTheOneItDid() throws IOException {
		// The issue's second case: two chips, the answer cites one, and the sentence ended with the
		// other chip's order under "those findings".
		SharedMechanism arrangement = new SharedMechanism();
		String modelAnswer = "No — aspirin should not be given: it interacts with her "
				+ arrangement.mergedOrders.get(0) + " [" + arrangement.merged.getIndex() + "].";

		ChartAnswer answer = arrangement.service(modelAnswer).search(patient(),
				DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION);

		assertFalse(answer.getAnswer().toLowerCase(Locale.ROOT)
				.contains(arrangement.otherOrder.toLowerCase(Locale.ROOT)),
				"the order of a finding the answer never cited is not \"covered by those findings\", was: "
						+ answer.getAnswer());
	}

	@Test
	public void anAnswerCitingNoFindingIsReturnedByteForByteAndMeasuresNothing() throws IOException {
		// The issue's first case: an abstention citing nothing was handed a list of orders "covered by
		// those findings" when it contained no finding for the phrase to refer to.
		SharedMechanism arrangement = new SharedMechanism();
		String modelAnswer = "The records do not address whether aspirin is safe for her.";

		ChartAnswer answer = arrangement.service(modelAnswer).search(patient(),
				DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION);

		assertEquals(modelAnswer, answer.getAnswer(),
				"an answer citing no finding has nothing appended to it");
		assertNull(answer.getFindingPartnerCoverage(),
				"and states no findingPartners measurement: there is no cited finding whose orders it "
						+ "could have left out, and an uncited finding is findingCitations' to count");
	}

	@Test
	public void anAnswerNamingEveryOrderOfTheFindingItCitedIsReturnedByteForByte() throws IOException {
		// The control that makes the first case about the shortfall and not about the append — and it
		// cites the merged finding ALONE, leaving the other finding's order unnamed, so the code that
		// read every chip appends that order here.
		SharedMechanism arrangement = new SharedMechanism();
		StringBuilder sb = new StringBuilder("No — aspirin should not be given: it interacts with");
		for (String order : arrangement.mergedOrders) {
			sb.append(" ").append(order).append(",");
		}
		String modelAnswer = sb.append(" both of them [").append(arrangement.merged.getIndex()).append("].")
				.toString();

		ChartAnswer answer = arrangement.service(modelAnswer).search(patient(),
				DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION);

		assertEquals(modelAnswer, answer.getAnswer(),
				"an answer that named every order of the finding it cited is returned unchanged");
		FindingPartnerCoverage coverage = answer.getFindingPartnerCoverage();
		assertNotNull(coverage, "the answer cited a finding, so it is measured");
		assertEquals(arrangement.mergedOrders.size(), coverage.getNamed(), "was: " + coverage);
		assertEquals(arrangement.mergedOrders.size(), coverage.getStated(), "was: " + coverage);
	}

	@Test
	public void searchStreaming_anOrderOfAFindingTheAnswerDidNotCiteIsNotAppended() throws IOException {
		// /search/stream is the path users hit, and it completes the answer at its own call site.
		SharedMechanism arrangement = new SharedMechanism();
		String modelAnswer = "No — aspirin should not be given: it interacts with her "
				+ arrangement.mergedOrders.get(0) + " [" + arrangement.merged.getIndex() + "].";

		ChartAnswer answer = arrangement.service(modelAnswer).searchStreaming(patient(),
				DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION, token -> { });

		String completed = answer.getAnswer().toLowerCase(Locale.ROOT);
		assertFalse(completed.contains(arrangement.otherOrder.toLowerCase(Locale.ROOT)),
				"was: " + answer.getAnswer());
		for (String order : arrangement.mergedOrders) {
			assertTrue(completed.contains(order.toLowerCase(Locale.ROOT)),
					"and the cited finding's own orders still reach it. Missing " + order + " from: "
							+ answer.getAnswer());
		}
	}

	@Test
	public void aFindingOnlyTheStructuredCitationsArrayListsHasNoOrderAppended() throws IOException {
		// A real model also emits a structured citations array, and extractCitedReferences unions it with
		// the prose markers (issue #409). A finding the array lists and no sentence marks is in that
		// resolution and is not a finding the answer cited, so a completion reading the union — measured
		// by handing citedFindingIndexes a null answer — names its order under "those findings". The
		// other cases here stub an empty array, and stayed green on that.
		SharedMechanism arrangement = new SharedMechanism();
		String modelAnswer = "No — aspirin should not be given: it interacts with her "
				+ arrangement.mergedOrders.get(0) + " [" + arrangement.merged.getIndex() + "].";

		ChartAnswer answer = arrangement.service(modelAnswer, arrangement.bothFindings()).search(patient(),
				DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION);

		assertOnlyTheMarkedFindingIsCompleted(arrangement, answer);
	}

	@Test
	public void searchStreaming_aFindingOnlyTheStructuredCitationsArrayListsHasNoOrderAppended()
			throws IOException {
		// The streaming path completes the answer at its own call site, so it is asked the same.
		SharedMechanism arrangement = new SharedMechanism();
		String modelAnswer = "No — aspirin should not be given: it interacts with her "
				+ arrangement.mergedOrders.get(0) + " [" + arrangement.merged.getIndex() + "].";

		ChartAnswer answer = arrangement.service(modelAnswer, arrangement.bothFindings()).searchStreaming(
				patient(), DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION, token -> { });

		assertOnlyTheMarkedFindingIsCompleted(arrangement, answer);
	}

	/** The answer both structured-array cases assert: the array's other finding reached the
	 *  resolution, and neither the appended sentence nor {@code findingPartners} covers it. */
	private static void assertOnlyTheMarkedFindingIsCompleted(SharedMechanism arrangement,
			ChartAnswer answer) {
		boolean resolved = false;
		for (RecordReference ref : answer.getReferences()) {
			if (ref.getIndex() == arrangement.other.getIndex()) {
				resolved = true;
			}
		}
		assertTrue(resolved, "the premise: the finding only the structured array lists is in the answer's "
				+ "resolution, was: " + answer.getReferences());
		assertNotNull(answer.getFindingCitationExtent(), "the premise: findingCitations is measured");
		assertEquals(1, answer.getFindingCitationExtent().getCited(),
				"and findingCitations counts the one finding the prose marked, was: "
						+ answer.getFindingCitationExtent());
		String completed = answer.getAnswer().toLowerCase(Locale.ROOT);
		assertFalse(completed.contains(arrangement.otherOrder.toLowerCase(Locale.ROOT)),
				"the order of a finding only the structured array lists is not \"covered by those "
						+ "findings\", was: " + answer.getAnswer());
		for (String order : arrangement.mergedOrders) {
			assertTrue(completed.contains(order.toLowerCase(Locale.ROOT)),
					"and the marked finding's own orders still reach it. Missing " + order + " from: "
							+ answer.getAnswer());
		}
		FindingPartnerCoverage coverage = answer.getFindingPartnerCoverage();
		assertNotNull(coverage, "the answer cited a finding, so it is measured");
		assertEquals(arrangement.mergedOrders.size(), coverage.getNamed(),
				"named counts the orders of the one finding findingCitations says was cited, was: "
						+ coverage);
		assertEquals(1, coverage.getStated(), "and stated the one the prose named, was: " + coverage);
	}

	@Test
	public void anOrderTheAnswerSpelledWithoutTheSpacesAroundASlashIsNotListedAgain() throws IOException {
		// The issue's fourth case: the answer wrote an order with one slash unspaced, and a case-folded
		// containment test read it as unstated and appended it under "not named above" — a false
		// statement inside the clinician's answer. Issue #477's finding names her orders by their
		// displays, and these two displays carry slashes, so it is the arrangement that can show it.
		String fixture = "chartsearchai-test/ddi-substance-in-several-orders.json";
		String question = "Is it safe to give rifampicin?";
		String rhz = "Isoniazid / pyrazinamide / rifampin";
		String rhze = "Rifampicin isoniazid pyrazinamide and ethambutol 150/75/400/275mg";
		List<SafetyWarning> chips = DrugReferenceTestSupport.chipsOverOrders(fixture, question, rhz, rhze);
		PatientChart chart = DrugReferenceTestSupport.findingsOverOrders(baseChart(), fixture, question,
				rhz, rhze);
		RecordMapping alreadyIn = null;
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			if (finding.getText().contains(ALREADY_IN_ORDERS)) {
				alreadyIn = finding;
			}
		}
		assertNotNull(alreadyIn, "the premise: the injector wrote the finding that rifampicin is already in "
				+ "her orders, was: " + chart.getText());
		List<String> named = null;
		for (SafetyWarning chip : chips) {
			if (chip.getDetail().contains(ALREADY_IN_ORDERS)) {
				named = chip.namedPartners();
			}
		}
		assertEquals(java.util.Arrays.asList(rhz, rhze), named,
				"and that finding names both orders by their displays, was: " + chips);
		String unspaced = rhz.substring(0, rhz.lastIndexOf(" / ")) + "/"
				+ rhz.substring(rhz.lastIndexOf(" / ") + 3);
		assertFalse(unspaced.equals(rhz), "the premise: the spelling differs from the display");
		String modelAnswer = "Rifampicin is already in her active orders " + unspaced + " and " + rhze + " ["
				+ alreadyIn.getIndex() + "].";

		ChartAnswer answer = service(chart, chips, modelAnswer).search(patient(), question);

		assertEquals(modelAnswer, answer.getAnswer(),
				"an order the answer named, spaced differently around a slash, is not named again as "
						+ "\"not named above\"");
		FindingPartnerCoverage coverage = answer.getFindingPartnerCoverage();
		assertNotNull(coverage, "the answer cited a finding, so it is measured");
		assertEquals(2, coverage.getNamed(), "was: " + coverage);
		assertEquals(2, coverage.getStated(), "and both orders count as stated, was: " + coverage);
	}

	@Test
	public void anOrderSeveralCitedFindingsNameIsListedOnce() throws IOException {
		// The sentence lists an order once however many of the cited findings name it: two drugs the
		// question proposes, each related to her one simvastatin order, raise two findings naming it.
		// The answer cites both and names no order.
		String question = "Is it safe to give clarithromycin or fluconazole?";
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOver(baseChart(), question,
				new java.util.LinkedHashSet<String>(Collections.singletonList("Simvastatin")),
				new java.util.LinkedHashSet<String>(Collections.singletonList("C10AA01")));
		List<SafetyWarning> chips = Collections.<SafetyWarning> emptyList();
		StringBuilder sb = new StringBuilder("Neither should be started here");
		List<String> distinct = new ArrayList<String>();
		int named = 0;
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			sb.append(" [").append(finding.getIndex()).append("]");
			for (String order : finding.getFindingPartners()) {
				named++;
				if (!distinct.contains(order)) {
					distinct.add(order);
				}
			}
		}
		String modelAnswer = sb.append(".").toString();
		assertTrue(named > distinct.size(),
				"the premise: the cited findings name some order more than once, was: " + chart.getText());

		ChartAnswer answer = service(chart, chips, modelAnswer).search(patient(), question);

		String appended = answer.getAnswer().substring(modelAnswer.length());
		for (String order : distinct) {
			int from = appended.indexOf(order);
			assertTrue(from >= 0, "every order of a cited finding is named, missing " + order + " from: "
					+ answer.getAnswer());
			assertEquals(-1, appended.indexOf(order, from + 1),
					"and named once, however many cited findings name it: " + answer.getAnswer());
		}
		assertNotNull(answer.getFindingPartnerCoverage(), "the answer cited findings, so it is measured");
		assertEquals(named, answer.getFindingPartnerCoverage().getNamed(),
				"while named counts one per cited finding naming the order — the residue ADR Decision "
						+ "100's amendment records, was: " + answer.getFindingPartnerCoverage());
	}

	/**
	 * The shared-mechanism arrangement: the chart with the findings the real injector wrote, the chips
	 * the real validator raised over the same patient and question, and which of each is the merged
	 * finding and which the other.
	 */
	private static final class SharedMechanism {

		private final PatientChart chart;

		private final List<SafetyWarning> chips;

		/** The record of the finding naming two of her orders under one mechanism. */
		private final RecordMapping merged;

		/** The record of the other finding. */
		private final RecordMapping other;

		/** The orders that finding names, as its chip names them. */
		private final List<String> mergedOrders;

		/** The one order the OTHER finding names. */
		private final String otherOrder;

		private SharedMechanism() throws IOException {
			chips = DrugReferenceTestSupport.sharedMechanismInteractionChips(new PairChipExtent.Sink());
			chart = DrugReferenceTestSupport.sharedMechanismFindingsOver(baseChart());
			RecordMapping mergedRecord = null;
			RecordMapping otherRecord = null;
			int others = 0;
			for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
				if (finding.getText().contains(CORTICOSTEROID_MECHANISM)) {
					mergedRecord = finding;
				}
				else {
					otherRecord = finding;
					others++;
				}
			}
			assertNotNull(mergedRecord, "the premise: the injector wrote the merged finding, was: "
					+ chart.getText());
			assertEquals(1, others, "and exactly one other finding, was: " + chart.getText());
			merged = mergedRecord;
			other = otherRecord;
			List<String> mergedNames = null;
			List<String> otherNames = null;
			for (SafetyWarning chip : chips) {
				if (chip.getDetail().contains(CORTICOSTEROID_MECHANISM)) {
					mergedNames = chip.namedPartners();
				}
				else {
					otherNames = chip.namedPartners();
				}
			}
			assertNotNull(mergedNames, "was: " + chips);
			assertTrue(mergedNames.size() >= 2,
					"the premise: the merged finding names more than one order, was: " + mergedNames);
			assertNotNull(otherNames, "was: " + chips);
			assertEquals(1, otherNames.size(), "and the other names one, was: " + otherNames);
			mergedOrders = mergedNames;
			otherOrder = otherNames.get(0);
			for (String order : mergedOrders) {
				assertTrue(merged.getText().toLowerCase(Locale.ROOT).contains(order.toLowerCase(Locale.ROOT)),
						"and the merged record the answers cite is about the orders its chip names: "
								+ order + " in " + merged.getText());
			}
		}

		private LlmInferenceService service(String modelAnswer) {
			return service(modelAnswer, Collections.<Integer> emptyList());
		}

		private LlmInferenceService service(String modelAnswer, List<Integer> structuredCitations) {
			return CitedFindingPartnerCompletionTest.service(chart, chips, modelAnswer, structuredCitations);
		}

		/** A structured citations array listing both findings, merged first. */
		private List<Integer> bothFindings() {
			return java.util.Arrays.asList(Integer.valueOf(merged.getIndex()),
					Integer.valueOf(other.getIndex()));
		}
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	/** This patient's own drug orders, rendered by the REAL serializer — the chart the injector appends
	 *  its findings to. */
	private static PatientChart baseChart() {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-1", "Prednisone 5mg tablet, 1 daily", null));
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-2", "Heparin 5000 units, subcutaneous", null));
		return new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
	}

	private static LlmInferenceService service(PatientChart chart, List<SafetyWarning> chips,
			String modelAnswer) {
		return service(chart, chips, modelAnswer, Collections.<Integer> emptyList());
	}

	/** The real inference service over {@code chart}, the chart the real injector already produced —
	 *  handed back from {@code inject} as the very object the answers' markers were numbered against —
	 *  with {@code chips} as the post-answer pass's chips, {@code modelAnswer} as the model's prose and
	 *  {@code structuredCitations} as its structured citations array. */
	private static LlmInferenceService service(PatientChart chart, List<SafetyWarning> chips,
			String modelAnswer, List<Integer> structuredCitations) {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(chart));
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart built, Patient patient, String question,
					ChartReadStatus readStatus) {
				return built;
			}
		});
		created.setDrugSafetyValidator(new DrugSafetyValidator() {

			// The overload production calls: mappings-carrying (issue #105) and sink-carrying (issue
			// #336). Stubbing a shorter one would leave this stub inert.
			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<PatientChartSerializer.RecordMapping> mappings,
					PairChipExtent.Sink pairExtentSink) {
				return chips;
			}
		});
		created.setLlmProvider(new StubProvider(modelAnswer, structuredCitations));
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

		private StubProvider(String answer, List<Integer> citations) {
			this.answer = answer;
			this.citations = citations;
		}

		private LlmResponse canned() {
			return new LlmResponse(answer, citations);
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question,
				boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords) {
			return canned();
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings, LlmEngine.ReferenceRecords referenceRecords) {
			return canned();
		}
	}
}
