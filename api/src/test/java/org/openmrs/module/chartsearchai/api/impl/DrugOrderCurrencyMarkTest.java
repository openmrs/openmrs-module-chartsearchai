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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.DrugOrder;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.TestOrder;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.serialization.DrugOrderRecordSerializer;
import org.openmrs.module.querystore.serialization.TestOrderRecordSerializer;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue #317: the chart text the model reads must say whether a drug order is still in force.
 *
 * <p>querystore renders {@code ". Stopped: <date>"} for {@code getDateStopped()} and
 * {@code ". Action: DISCONTINUE"} for the action, and renders NOTHING for {@code auto_expire_date}
 * (pinned by {@code QuerystoreOrderTextMarkerTest.anAutoExpireDateAloneIsNotVisibleInTheRenderedText}).
 * So a prescription that lapsed by its duration reaches the model byte-shaped exactly like one still
 * being taken, while {@code OrderService} excludes it from {@code getActiveOrders}. The module knows;
 * only the prompt does not.
 *
 * <p><strong>What is real here, and what is not.</strong> {@code OrderService},
 * {@code Order.isActive()} and the orders themselves are real — the standard test dataset plus the one
 * lapsed-by-duration order it lacks ({@code DrugOrderCurrencyTestData.xml}). The chart documents are
 * the output of querystore's REAL {@link DrugOrderRecordSerializer} / {@link TestOrderRecordSerializer}
 * run on those orders, so no imitation of another module's format appears in this file. Only
 * querystore's INDEX is stubbed, because it does not run under
 * {@link BaseModuleContextSensitiveTest} — the same seam {@code QueryStoreChartBuilderTest} uses. Every
 * line of this module's own path is production code: {@code build}/{@code buildScoped} →
 * {@code toSerializedRecords} → {@link PatientChartSerializer#serialize}.
 *
 * <p>Both halves of the rendered answer are asserted, and they are not the same claim: the chart TEXT
 * is what the model reads, and the {@link RecordMapping}'s structural flag is what
 * {@code DrugReferenceInjector}'s active-order reconciliation reads. A change that rendered the label
 * without populating the flag would leave the reconciliation keying on prose, which is the defect this
 * ticket exists to remove.
 */
public class DrugOrderCurrencyMarkTest extends BaseModuleContextSensitiveTest {

	/** The lapsed-by-duration order this file's dataset adds: action NEW, {@code auto_expire_date}
	 *  2008-01-08, {@code date_stopped} NULL — the shape whose end the rendered text cannot carry. */
	private static final int LAPSED_ORDER_ID = 9317;

	/** Standard test dataset order 3: Triomune-30, patient 2, activated 2008-02-08, never stopped. */
	private static final int LIVE_ORDER_ID = 3;

	/** Standard test dataset order 2: patient 2, {@code date_stopped} 2007-12-10 — ended, and its
	 *  rendered text says so. The mark must still be applied: it is authoritative, not a fallback
	 *  for records whose prose happens to carry an end. */
	private static final int STOPPED_ORDER_ID = 2;

	/** Standard test dataset order 6: a TEST order, not a drug order. */
	private static final int TEST_ORDER_ID = 6;

	/** This file's dataset: patient SIX's only drug order, lapsed — so patient 6's active-order set
	 *  is empty. That is the arrangement issue #315 reported (one ended prescription, nothing
	 *  active), and the state an {@code isEmpty()} shortcut for "could not read" would silently
	 *  misreport. */
	private static final int ONLY_ORDER_OF_A_PATIENT_WITH_NONE_ACTIVE = 9318;

	/** Standard test dataset order 1: a drug order belonging to patient SEVEN. Its uuid is a real
	 *  order uuid that is not one of patient 2's, which is how the unattributable case is reached
	 *  with real data rather than a hand-edited uuid. */
	private static final int OTHER_PATIENTS_ORDER_ID = 1;

	private static final String MEDICATIONS_QUESTION = "what medications is the patient taking?";

	private CountingQueryStoreStub queryStore;

	private TestableBuilder builder;

	private Patient patient;

	@BeforeEach
	public void setUp() throws Exception {
		executeDataSet("DrugOrderCurrencyTestData.xml");
		queryStore = new CountingQueryStoreStub();
		builder = new TestableBuilder(queryStore.asService());
		builder.setChartSerializer(new PatientChartSerializer());
		patient = Context.getPatientService().getPatient(2);
	}

	private QueryDocument drugOrderDoc(int orderId) {
		return new DrugOrderRecordSerializer()
				.serialize((DrugOrder) Context.getOrderService().getOrder(orderId));
	}

	private QueryDocument testOrderDoc(int orderId) {
		return new TestOrderRecordSerializer()
				.serialize((TestOrder) Context.getOrderService().getOrder(orderId));
	}

	private void chartOf(QueryDocument... docs) {
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(docs));
	}

	/** The chart line for the record carrying {@code resourceUuid}, or null when the chart has none. */
	private String lineFor(PatientChart chart, String resourceUuid) {
		for (RecordMapping mapping : chart.getMappings()) {
			if (resourceUuid.equals(mapping.getResourceUuid())) {
				for (String line : chart.getText().split("\n")) {
					if (line.startsWith("[" + mapping.getIndex() + "] ")) {
						return line;
					}
				}
			}
		}
		return null;
	}

	private RecordMapping mappingFor(PatientChart chart, String resourceUuid) {
		for (RecordMapping mapping : chart.getMappings()) {
			if (resourceUuid.equals(mapping.getResourceUuid())) {
				return mapping;
			}
		}
		return null;
	}

	private String uuidOf(int orderId) {
		return Context.getOrderService().getOrder(orderId).getUuid();
	}

	@Test
	public void anOrderThatLapsedByItsDurationIsMarkedNotActive() {
		// The decisive case, and the one the whole ticket is about: nothing in this record's text
		// says the prescription ended, because querystore renders no auto-expire date. Before this
		// change the model could only infer, and on the arrangement issue #315's prompt attempt was
		// measured over, what it infers from is the record's relative age.
		chartOf(drugOrderDoc(LAPSED_ORDER_ID));

		PatientChart chart = builder.build(patient, MEDICATIONS_QUESTION);

		String uuid = uuidOf(LAPSED_ORDER_ID);
		assertFalse(Context.getOrderService().getOrder(LAPSED_ORDER_ID).isActive(),
				"precondition: OrderService must consider this order inactive, or the case proves nothing");
		assertTrue(lineFor(chart, uuid).endsWith(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"the lapsed order's chart line must say it is not active: " + lineFor(chart, uuid));
		assertEquals(Boolean.FALSE, mappingFor(chart, uuid).getOrderActive(),
				"and the grounding mapping must carry the same answer structurally");
	}

	@Test
	public void aLiveOrderIsMarkedActive() {
		// The over-marking guard, and the positive half of the two-sided mark. Without it the
		// absence of a mark would mean three different things at once — active, not an order, or
		// the read failed — and nothing downstream could tell them apart.
		chartOf(drugOrderDoc(LIVE_ORDER_ID));

		PatientChart chart = builder.build(patient, MEDICATIONS_QUESTION);

		String uuid = uuidOf(LIVE_ORDER_ID);
		assertTrue(Context.getOrderService().getOrder(LIVE_ORDER_ID).isActive(),
				"precondition: OrderService must consider order 3 active");
		assertTrue(lineFor(chart, uuid).endsWith(PatientChartSerializer.ACTIVE_ORDER_LABEL),
				"a live order's chart line must say so: " + lineFor(chart, uuid));
		assertEquals(Boolean.TRUE, mappingFor(chart, uuid).getOrderActive(),
				"and the grounding mapping must carry the same answer structurally");
	}

	@Test
	public void aStoppedOrderIsMarkedNotActiveEvenThoughItsTextAlreadySaysStopped() {
		// The mark reports OrderService, not what the prose happens to carry. Keying it on the text
		// instead would reproduce exactly the gap this ticket exists to close.
		chartOf(drugOrderDoc(STOPPED_ORDER_ID));

		PatientChart chart = builder.build(patient, MEDICATIONS_QUESTION);

		String line = lineFor(chart, uuidOf(STOPPED_ORDER_ID));
		assertTrue(line.toLowerCase().contains(". stopped:"),
				"precondition: querystore renders this order's stop date, so prose alone could have "
						+ "answered here: " + line);
		assertTrue(line.endsWith(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"and the authoritative mark is applied anyway: " + line);
	}

	@Test
	public void oneChartCarriesBothMarksAndTellsTheTwoOrdersApart() {
		// The renewal shape: the same drug, one live record and one lapsed record. This is what the
		// model has to separate, and before this change the two lines differed only by their dates.
		chartOf(drugOrderDoc(LIVE_ORDER_ID), drugOrderDoc(LAPSED_ORDER_ID));

		PatientChart chart = builder.build(patient, MEDICATIONS_QUESTION);

		assertTrue(lineFor(chart, uuidOf(LIVE_ORDER_ID)).endsWith(PatientChartSerializer.ACTIVE_ORDER_LABEL),
				"the live order is marked active");
		assertTrue(lineFor(chart, uuidOf(LAPSED_ORDER_ID))
						.endsWith(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"the lapsed order of the same drug is marked not active");
	}

	@Test
	public void neitherMarkIsRenderedWhenTheActiveOrderReadFails() {
		// The fail-CLOSED guard the ticket names. "Not in the active set implies ended" would report
		// every prescription this patient has as stopped the moment the order read fails — a chart
		// nobody could read, rendered as a chart of stopped prescriptions. A chart the module could
		// not read is not a chart of ended orders, so it says nothing at all.
		//
		// Reached through the resolveAllOrders seam because there is no other way in: build()
		// resolves the preFilter global property before any document is serialized and outside every
		// try block, so logging out throws there first and no record is ever produced to inspect.
		builder.failOrderRead = true;
		chartOf(drugOrderDoc(LAPSED_ORDER_ID), drugOrderDoc(LIVE_ORDER_ID));

		PatientChart chart = builder.build(patient, MEDICATIONS_QUESTION);

		assertFalse(chart.getText().contains(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"a failed order read must not report a prescription as ended: " + chart.getText());
		assertFalse(chart.getText().contains(PatientChartSerializer.ACTIVE_ORDER_LABEL),
				"nor assert that one is in force: " + chart.getText());
		assertNull(mappingFor(chart, uuidOf(LAPSED_ORDER_ID)).getOrderActive(),
				"and the mapping must say the module cannot answer, not answer wrongly");
		// What this case does NOT separate, said out loud so the guard does not look better defended
		// than it is. Two independent things stop a failed read marking anything: the reading is the
		// explicit "not read" state, AND no record's order can be attributed to the patient. A failed
		// read produces no sets at all, so there is no arrangement in which one holds and the other
		// does not — measured, replacing the failure path's OrderCurrency.unread() with a reading of
		// two empty sets leaves this case and the whole suite green. The write path is pinned instead
		// by theFailedReadPathReturnsTheNotReadState below.
	}

	@Test
	public void anEmptyActiveOrderSetStillMarksEveryRecordNotActive() {
		// The case an isEmpty() shortcut for "could not read" would silently break, and it is not a
		// corner: it is the arrangement issue #315 reported — one ended order and nothing active.
		Patient patientSix = Context.getPatientService().getPatient(6);
		chartOf(drugOrderDoc(ONLY_ORDER_OF_A_PATIENT_WITH_NONE_ACTIVE));

		PatientChart chart = builder.build(patientSix, MEDICATIONS_QUESTION);

		assertTrue(Context.getOrderService().getActiveOrders(patientSix, null, null, null).isEmpty(),
				"precondition: this patient has no active order at all");
		String line = lineFor(chart, uuidOf(ONLY_ORDER_OF_A_PATIENT_WITH_NONE_ACTIVE));
		assertTrue(line.endsWith(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"an empty active-order set is an answer, not a failure: " + line);
	}

	@Test
	public void aRecordWhoseOrderThisPatientDoesNotOwnIsNotMarked() {
		// The uuid the mark is keyed on is querystore's own resourceUuid contract (its
		// DrugOrderRecordSerializer indexes a drug order under Order.getUuid()). If that contract
		// ever drifted, "absent from the active set" would become true of EVERY record and the chart
		// would tell a clinician that every one of this patient's prescriptions had ended. So the
		// mark is only applied to a record whose order the module can actually attribute to this
		// patient; anything else is left unmarked, which is also what preserves the active-order
		// reconciliation's name fallback for a record it cannot identify by uuid.
		chartOf(drugOrderDoc(OTHER_PATIENTS_ORDER_ID));

		PatientChart chart = builder.build(patient, MEDICATIONS_QUESTION);

		String uuid = uuidOf(OTHER_PATIENTS_ORDER_ID);
		assertFalse(Context.getOrderService().getOrder(OTHER_PATIENTS_ORDER_ID).isActive(),
				"precondition: the order really is inactive, so only the attribution guard can be "
						+ "what leaves this record unmarked");
		assertNull(mappingFor(chart, uuid).getOrderActive(),
				"a record the module cannot attribute to this patient gets no mark");
		assertFalse(lineFor(chart, uuid).endsWith(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"and nothing is rendered on its chart line: " + lineFor(chart, uuid));
	}

	@Test
	public void aRecordThatIsNotADrugOrderIsNeverMarked() {
		// Scope: the ticket is about drug orders. getActiveOrders returns every order type, so the
		// uuid set covers a test order too — this is a deliberate scope boundary, not a limit of the
		// data, and it is pinned so that widening it later is a decision someone makes on purpose.
		//
		// The chart must carry a drug order BESIDE the test order, and that is not presentation. On a
		// chart of test orders alone the read is skipped entirely, so nothing is marked whatever the
		// scope says and this case passes without ever exercising it: measured by deleting the
		// resource-type condition from the reading, which leaves a test-order-only chart green and
		// reddens this arrangement.
		chartOf(drugOrderDoc(LIVE_ORDER_ID), testOrderDoc(TEST_ORDER_ID));

		PatientChart chart = builder.build(patient, MEDICATIONS_QUESTION);

		String uuid = uuidOf(TEST_ORDER_ID);
		assertEquals("test_order", mappingFor(chart, uuid).getResourceType(),
				"precondition: querystore types this record as a test order");
		assertFalse(Context.getOrderService().getOrder(TEST_ORDER_ID).isActive(),
				"precondition: this test order is NOT active, so an unscoped reading would have "
						+ "something to say about it");
		assertNull(mappingFor(chart, uuid).getOrderActive(),
				"a non-drug order carries no currency mark");
		assertFalse(lineFor(chart, uuid).endsWith(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"and nothing is rendered on its line: " + lineFor(chart, uuid));
		assertTrue(lineFor(chart, uuidOf(LIVE_ORDER_ID))
						.endsWith(PatientChartSerializer.ACTIVE_ORDER_LABEL),
				"while the drug order beside it is marked, so the read did happen");
	}

	@Test
	public void theDefaultQueryScopedPathMarksToo() {
		// buildScoped is what chartsearchai.chartMode=queryScoped dispatches to, and that is
		// CHART_MODE_DEFAULT and the config.xml default — so a fix that reached only the fullChart
		// path would be a no-op on every default install.
		chartOf(drugOrderDoc(LAPSED_ORDER_ID), drugOrderDoc(LIVE_ORDER_ID));

		PatientChart chart = builder.buildScoped(patient, MEDICATIONS_QUESTION);

		assertTrue(chart.isQueryScoped(), "precondition: this is the scoped path");
		assertTrue(lineFor(chart, uuidOf(LAPSED_ORDER_ID))
						.endsWith(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"the scoped slice marks the lapsed order too");
		assertTrue(lineFor(chart, uuidOf(LIVE_ORDER_ID)).endsWith(PatientChartSerializer.ACTIVE_ORDER_LABEL),
				"and the live one");
	}

	@Test
	public void theProgressiveReasoningPreviewMarksToo() {
		// The third build path. It is not covered by the other two "by construction" — that argument
		// is exactly what a shared funnel stops being true the moment someone inlines one caller. And
		// it matters on its own terms: the preview's answer is shown to the clinician before the
		// committed one, so a preview that called a lapsed prescription current while the committed
		// answer did not would be the module contradicting itself in front of the reader.
		chartOf(drugOrderDoc(LAPSED_ORDER_ID));
		queryStore.stubHits = new ArrayList<QueryDocument>(queryStore.stubChart);

		PatientChart chart = builder.buildFocused(patient, MEDICATIONS_QUESTION);

		assertTrue(lineFor(chart, uuidOf(LAPSED_ORDER_ID))
						.endsWith(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"the preview chart marks the lapsed order too: " + chart.getText());
	}

	@Test
	public void aChartWithNoDrugOrderRecordCostsNoOrderRead() {
		// The read is not free — it is two OrderService calls on every chart assembly — so it is not
		// made for a chart that has nothing to mark. Asserted rather than assumed, because the guard
		// is invisible in the rendered output.
		chartOf(testOrderDoc(TEST_ORDER_ID));

		builder.build(patient, MEDICATIONS_QUESTION);

		assertEquals(0, builder.orderReads,
				"a chart carrying no drug-order record must not read the patient's orders");
	}

	@Test
	public void theTwoMarksAreSpelledExactlyAsMeasured() {
		// The literals, pinned as literals. Every other assertion in this file compares the constant
		// to itself and so cannot see a rename — measured, rewriting both constants to
		// ". Order status: CURRENT" / ". Order status: ENDED" left the whole api suite green. That is
		// the same blind spot CLAUDE.md records for the audit search-mode labels, and it matters more
		// here than for an ops label: this string is in every prompt, and the prompt it joins is
		// phrasing-sensitive at one word (issue #316's ledger). A wording change must be a deliberate
		// act with a fresh interleaved A/B behind it, so it has to redden something first.
		assertEquals(". Active order: yes", PatientChartSerializer.ACTIVE_ORDER_LABEL,
				"changing this changes what every chart says to the model; re-measure before editing");
		assertEquals(". Active order: no", PatientChartSerializer.INACTIVE_ORDER_LABEL,
				"changing this changes what every chart says to the model; re-measure before editing");
	}

	@Test
	public void theFailedReadPathReturnsTheNotReadState() throws Exception {
		// A STRUCTURAL pin, because no behavioural one is available and the neighbouring case says so:
		// a failed order read and a reading of two empty sets are indistinguishable in output, since
		// nothing can be attributed under either. What makes the "not read" state worth keeping anyway
		// is that it is the guard which does not depend on attribution — relax or remove the
		// attribution rule and this is what still stops a chart the module could not read being
		// rendered as a chart of stopped prescriptions, which is the fail-closed hazard issue #317
		// names. A clause nothing discriminates is one the next change removes for free, so this reads
		// the source the way ArchitectureGuardTest and OrderPartnerNameSourceWritePathTest already do
		// in this repo.
		//
		// It asserts the SHAPE, not merely that the words appear: the catch must return the not-read
		// state, so building a reading there fails this even though it would change no output.
		Path source = findApiSourceRoot().resolve(
				"src/main/java/org/openmrs/module/chartsearchai/api/impl/QueryStoreChartBuilder.java");
		assertTrue(Files.exists(source), "cannot find the builder's source at " + source);
		String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

		int catchAt = text.indexOf("catch (RuntimeException e) {", text.indexOf("private OrderCurrency readOrderCurrency("));
		assertTrue(catchAt > 0, "readOrderCurrency must still catch a failed order read");
		String catchBlock = text.substring(catchAt, text.indexOf("\n\t}", catchAt));
		assertTrue(catchBlock.contains("return OrderCurrency.unread();"),
				"a failed order read must return the explicit not-read state, never a constructed "
						+ "reading — an empty reading answers the same today only because nothing can "
						+ "be attributed under it. Found: " + catchBlock);
	}

	/** The api module root, located the way {@code ArchitectureGuardTest} locates it. */
	private static Path findApiSourceRoot() {
		Path current = Paths.get("").toAbsolutePath();
		while (current != null) {
			if (Files.exists(current.resolve("src/main/java"))
					&& Files.exists(current.resolve("src/test/java"))) {
				return current;
			}
			Path api = current.resolve("api");
			if (Files.exists(api.resolve("src/main/java"))) {
				return api;
			}
			current = current.getParent();
		}
		return Paths.get("").toAbsolutePath();
	}

	/**
	 * Subclass supplying the two seams this module's tests already use — querystore's service and the
	 * global-property reads — plus a switch that makes the {@code OrderService} read fail. Everything
	 * else is the real builder.
	 */
	private static final class TestableBuilder extends QueryStoreChartBuilder {

		private final QueryStoreService stub;

		boolean failOrderRead = false;

		int orderReads = 0;

		TestableBuilder(QueryStoreService stub) {
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

		@Override
		protected List<Order> resolveAllOrders(Patient patient) {
			orderReads++;
			if (failOrderRead) {
				throw new IllegalStateException("simulated OrderService failure");
			}
			return super.resolveAllOrders(patient);
		}
	}
}
