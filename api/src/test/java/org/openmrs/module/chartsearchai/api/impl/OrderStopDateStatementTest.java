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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.DrugOrder;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.OrderStopDate;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.serialization.DrugOrderRecordSerializer;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue #315: the module states when a cited prescription stopped being in force, because the
 * answer's prose does not.
 *
 * <p>Since #321 an answer naming a drug from an ended order says the order is no longer in force —
 * and states no DATE. ADR Decision 47's residue 1 is why: asked for the date positively, the prompt
 * replaces two live drug names with a lab measurement on a chart carrying a lapsed order beside two
 * live ones. Nothing a {@code /search} consumer reads carried the date either — a citation publishes
 * its record's clinical date and never the order's end, and no record text reaches the wire at all.
 * So the module states it.
 *
 * <p><strong>What is real here.</strong> The orders, {@code OrderService} and
 * {@code Order.isActive()} are real — the standard test dataset plus the shapes it lacks
 * ({@code DrugOrderCurrencyTestData.xml}). The chart documents are the output of querystore's real
 * {@link DrugOrderRecordSerializer}. The chart is assembled by the real
 * {@code QueryStoreChartBuilder}, the citations are resolved by the real
 * {@code LlmInferenceService.extractCitedReferences}, and the statement is produced by the real
 * {@code ChartSearchAiUtils.orderStopDates} — the same three calls {@code LlmInferenceService} makes
 * in that order. querystore's INDEX is stubbed because it does not run under
 * {@link BaseModuleContextSensitiveTest}, the seam every chart test in this package uses.
 *
 * <p>The ANSWER TEXT is supplied, because it is the model's output and no deterministic test can
 * produce one; every other input is the production path's own. That is the same boundary
 * {@code LlmInferenceServiceTest}'s citation cases draw.
 */
public class OrderStopDateStatementTest extends BaseModuleContextSensitiveTest {

	/** Lapsed by its duration: {@code auto_expire_date} 2008-01-08, {@code date_stopped} NULL. The
	 *  shape whose end is in no rendered text, so the date can only come from the order. */
	private static final int LAPSED_ORDER_ID = 9317;

	/** Standard test dataset order 2: {@code date_stopped} 2007-12-10. */
	private static final int STOPPED_ORDER_ID = 2;

	/** Standard test dataset order 3: live, never stopped. */
	private static final int LIVE_ORDER_ID = 3;

	/** A DISCONTINUE order carrying neither end date — out of force, and core publishes no date. */
	private static final int DISCONTINUED_ORDER_WITH_NO_STOP_DATE_ID = 9320;

	private static final String QUESTION = "what medications is the patient taking?";

	private CountingQueryStoreStub queryStore;

	private StatementBuilder builder;

	private Patient patient;

	@BeforeEach
	public void setUp() throws Exception {
		executeDataSet("DrugOrderCurrencyTestData.xml");
		queryStore = new CountingQueryStoreStub();
		builder = new StatementBuilder(queryStore.asService());
		builder.setChartSerializer(new PatientChartSerializer());
		patient = Context.getPatientService().getPatient(2);
	}

	private QueryDocument drugOrderDoc(int orderId) {
		return new DrugOrderRecordSerializer()
				.serialize((DrugOrder) Context.getOrderService().getOrder(orderId));
	}

	private PatientChart chartOf(int... orderIds) {
		List<QueryDocument> docs = new ArrayList<QueryDocument>();
		for (int orderId : orderIds) {
			docs.add(drugOrderDoc(orderId));
		}
		queryStore.stubChart = docs;
		return builder.build(patient, QUESTION);
	}

	/** The three production calls {@code LlmInferenceService} makes, in its own order: resolve the
	 *  answer's citations against the chart, then project the statement off both. */
	private List<OrderStopDate> statementFor(String answer, PatientChart chart) {
		List<RecordReference> cited = LlmInferenceService.extractCitedReferences(answer, null,
				chart.getMappings());
		return ChartSearchAiUtils.orderStopDates(answer, cited, chart.getMappings());
	}

	private static Date on(String yyyyMmDd) throws Exception {
		return new SimpleDateFormat("yyyy-MM-dd").parse(yyyyMmDd);
	}

	private int indexOf(PatientChart chart, int orderId) {
		String uuid = Context.getOrderService().getOrder(orderId).getUuid();
		for (RecordMapping mapping : chart.getMappings()) {
			if (uuid.equals(mapping.getResourceUuid())) {
				return mapping.getIndex();
			}
		}
		throw new AssertionError("no chart record for order " + orderId);
	}

	@Test
	public void theDateALapsedPrescriptionStoppedIsStatedForTheCitationTheAnswerPrinted()
			throws Exception {
		// The ticket's own arrangement: one prescription, ended, and an answer that names it without
		// saying when it ended. The date is in no text the model or a client reads.
		PatientChart chart = chartOf(LAPSED_ORDER_ID);
		int index = indexOf(chart, LAPSED_ORDER_ID);
		String answer = "Triomune-30 was prescribed, but its order is no longer in force ["
				+ index + "].";

		List<OrderStopDate> stated = statementFor(answer, chart);

		assertEquals(1, stated.size(), "exactly one cited ended order: " + stated);
		assertEquals(index, stated.get(0).getCitation(),
				"the statement names the citation the answer printed");
		assertEquals(on("2008-01-08"), stated.get(0).getStopDate(),
				"and the date core publishes for that order");
	}

	@Test
	public void theStatedDateIsTheOrdersEndAndNotTheRecordsOwnDate() throws Exception {
		// The two dates a citation can carry are different facts, and conflating them would publish
		// the activation date as an ending — the fabrication ADR Decision 47 records the prompt
		// committing on a same-drug renewal history.
		PatientChart chart = chartOf(STOPPED_ORDER_ID);
		int index = indexOf(chart, STOPPED_ORDER_ID);

		List<OrderStopDate> stated = statementFor("It was stopped [" + index + "].", chart);

		assertEquals(1, stated.size(), "one cited ended order: " + stated);
		assertEquals(on("2007-12-10"), stated.get(0).getStopDate(), "the order's own stop date");
		RecordMapping mapping = chart.getMappings().get(index - 1);
		assertFalse(on("2007-12-10").equals(mapping.getDate()),
				"precondition: the record's own date must differ from the order's end, or this case "
						+ "cannot tell the two apart. Record date: " + mapping.getDate());
	}

	@Test
	public void aCitedDiscontinuationCarryingNoDateIsStatedNowhere() throws Exception {
		// The cell the contract turns on. The order IS out of force, so the answer is entitled to
		// say so — and there is no date to publish, so nothing is published. An entry here carrying a
		// null date, or a date taken from anywhere else on the order, would be the module inventing
		// a clinical fact.
		Order order = Context.getOrderService().getOrder(DISCONTINUED_ORDER_WITH_NO_STOP_DATE_ID);
		assertNull(order.getEffectiveStopDate(),
				"precondition: core must publish no effective stop date for this discontinuation");
		PatientChart chart = chartOf(DISCONTINUED_ORDER_WITH_NO_STOP_DATE_ID);
		int index = indexOf(chart, DISCONTINUED_ORDER_WITH_NO_STOP_DATE_ID);

		assertEquals(Boolean.FALSE, chart.getMappings().get(index - 1).getOrderActive(),
				"precondition: the record still says the order is not in force");

		assertTrue(statementFor("It is no longer in force [" + index + "].", chart).isEmpty(),
				"an ended order with no date published states nothing, rather than an empty date");
	}

	@Test
	public void aCitedLivePrescriptionIsStatedNowhere() throws Exception {
		PatientChart chart = chartOf(LIVE_ORDER_ID);
		int index = indexOf(chart, LIVE_ORDER_ID);

		assertTrue(statementFor("He is taking Triomune-30 [" + index + "].", chart).isEmpty(),
				"an order still in force has not stopped, so no date is stated for it");
	}

	@Test
	public void anEndedPrescriptionTheAnswerDidNotCiteIsStatedNowhere() throws Exception {
		// Scoped to the answer's own evidence. A chart-wide statement would publish every ended
		// prescription in the retrieved slice on every question, which is the over-reach #143
		// records for the safety layer's own scoping.
		PatientChart chart = chartOf(LAPSED_ORDER_ID);

		assertTrue(statementFor("No current medications are recorded.", chart).isEmpty(),
				"an answer citing nothing states no stop date, however many ended orders the chart "
						+ "carried");
	}

	@Test
	public void twoCitedEndedPrescriptionsAreStatedInCitationOrder() throws Exception {
		PatientChart chart = chartOf(LAPSED_ORDER_ID, STOPPED_ORDER_ID);
		int lapsed = indexOf(chart, LAPSED_ORDER_ID);
		int stopped = indexOf(chart, STOPPED_ORDER_ID);
		// Cited in the opposite order to the one they must be stated in, so the ordering is the
		// writer's and not an echo of the answer's or the resolution's.
		String answer = "Two orders have ended [" + Math.max(lapsed, stopped) + "] ["
				+ Math.min(lapsed, stopped) + "].";

		List<OrderStopDate> stated = statementFor(answer, chart);

		assertEquals(2, stated.size(), "both cited ended orders are stated: " + stated);
		assertTrue(stated.get(0).getCitation() < stated.get(1).getCitation(),
				"strictest-first by citation, whatever order the answer printed them in: " + stated);
	}

	@Test
	public void aMarkerTheAnswersOwnResolutionRefusedIsStatedNowhere() throws Exception {
		// An index is not a citation until the answer's resolution admits it. A bracket naming no
		// record of this chart resolves to nothing, so there is nothing to state.
		PatientChart chart = chartOf(LAPSED_ORDER_ID);

		assertTrue(statementFor("It ended [99].", chart).isEmpty(),
				"a marker no chart record answers to states no stop date");
	}

	@Test
	public void aCitationTheModuleAttachedIsStatedNowhere() throws Exception {
		// The other half of the population rule, and the half `cited` alone cannot give. A chart
		// record the model never printed a marker for still reaches `cited` when a finding the model
		// DID cite was derived from it (issue #305) — and the published `citation` is documented as
		// the number the answer printed in brackets, so stating that record here would name a number
		// appearing nowhere in the answer.
		//
		// The finding mapping is appended to the real chart's own mappings rather than produced by
		// the injector: no arrangement of this fixture raises a safety finding, and this is the idiom
		// LlmInferenceServiceTest's own provenance cases use. Everything it is put through —
		// extractCitedReferences and orderStopDates — is production.
		PatientChart chart = chartOf(LAPSED_ORDER_ID);
		int lapsed = indexOf(chart, LAPSED_ORDER_ID);
		List<RecordMapping> withFinding = new ArrayList<RecordMapping>(chart.getMappings());
		int findingIndex = withFinding.size() + 1;
		withFinding.add(new RecordMapping(findingIndex,
				ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING, "contraindication:Triomune-30",
				null, "Safety finding", null, 0, null, null, null,
				Arrays.asList(Integer.valueOf(lapsed))));
		String answer = "There is a contraindication [" + findingIndex + "].";

		List<RecordReference> cited =
				LlmInferenceService.extractCitedReferences(answer, null, withFinding);
		boolean attachedTheLapsedRecord = false;
		for (RecordReference ref : cited) {
			if (ref.getIndex() == lapsed && ref.isAttachedByTheModule()) {
				attachedTheLapsedRecord = true;
			}
		}
		assertTrue(attachedTheLapsedRecord,
				"precondition: the resolution must have attached the ended order's record, or this "
						+ "case exercises nothing. Cited: " + cited);

		assertTrue(ChartSearchAiUtils.orderStopDates(answer, cited, withFinding).isEmpty(),
				"a citation the module attached states no stop date: the answer printed no marker "
						+ "for it, and `citation` is documented as a number the answer printed");
	}

	@Test
	public void aNullChartOrResolutionStatesAnEmptyListRatherThanThrowing() {
		// The producer never states the absence of a measurement — a caller that has none passes
		// null on to the answer itself. So a missing operand here is an empty statement, not a null
		// one and not an exception.
		assertTrue(ChartSearchAiUtils.orderStopDates("It ended [1].", null, null).isEmpty(),
				"no resolution and no mappings states nothing");
	}

	/**
	 * Subclass supplying the two seams this package's chart tests use — querystore's service and the
	 * global-property reads. Everything else is the real builder; one seam subclass per test class is
	 * this repo's convention.
	 */
	private static final class StatementBuilder extends QueryStoreChartBuilder {

		private final QueryStoreService stub;

		StatementBuilder(QueryStoreService stub) {
			this.stub = stub;
		}

		@Override
		protected QueryStoreService resolveQueryStoreService() {
			return stub;
		}

		@Override
		protected int resolveQueryStoreTopK() {
			return 100;
		}

		@Override
		protected boolean resolveUsePreFilter() {
			return false;
		}

		@Override
		protected boolean resolveDedupGroupLabels() {
			return false;
		}

		@Override
		protected int resolveProgressiveReasoningTopK() {
			return 10;
		}
	}
}
