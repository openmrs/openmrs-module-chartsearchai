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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.DrugOrder;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.TestOrder;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.ModuleSourceRoot;
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
 * run on those orders, so no imitation of another module's format appears in this file. querystore's
 * INDEX is stubbed because it does not run under {@link BaseModuleContextSensitiveTest} — the same
 * seam {@code QueryStoreChartBuilderTest} uses — and {@code TestableBuilder} additionally overrides
 * the builder's global-property seams so the mode under test is chosen rather than read, and the
 * order-read seam so a read FAILURE can be reached at all. What is not stubbed is the path the
 * assertions are about: {@code build}/{@code buildScoped}/{@code buildFocused} →
 * {@code toSerializedRecords} → {@code readOrderCurrency} → {@link PatientChartSerializer#serialize},
 * every line of it production code, over real orders and real {@code OrderService}.
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

	/** This file's dataset: an order core cannot evaluate — {@code date_stopped} after
	 *  {@code auto_expire_date}, both past — so {@code Order.isActive()} throws on it. */
	private static final int UNEVALUABLE_ORDER_ID = 9319;

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
		// does not: replacing the failure path's OrderCurrency.unread() with a reading of two empty
		// sets leaves THIS case green, and every behavioural case in the suite with it. What reddens
		// on that mutation is theFailedReadPathReturnsTheNotReadState, which reads the source rather
		// than the behaviour, and is the only thing that does. A DIFFERENT mutation — swallowing the
		// failure one level in, so this catch never runs — is invisible to THIS case too, and the two
		// cases that see it are aFailedOrderReadIsReportedAtWarn below (the WARN never happens) and
		// the source scan (two try blocks where the method may have one).
	}

	@Test
	public void aFailedOrderReadIsReportedAtWarn() {
		// The one OBSERVABLE consequence of the failure reaching readOrderCurrency's OWN catch, and
		// why it is worth a case beside the fail-closed one above. The chart degrades silently — every
		// drug-order record simply loses its mark and reads exactly as it did before this feature
		// existed — so a failure swallowed one level in produces a chart no rendered-output assertion
		// in this file can tell from a healthy one.
		//
		// Three mutations, measured, and they are what says this case and the source scan are not one
		// guard written twice. Dropping the log.warn from that catch reddens THIS and nothing else.
		// Returning readingOf(emptyList()) from it reddens the SCAN and nothing else. Wrapping the
		// resolveAllOrders call in an inner try that returns Collections.emptyList() — the swallow
		// this case exists for — reddens both.
		//
		// A fourth is invisible to this case and is NOT covered by asserting the WARN: a swallow
		// inside resolveAllOrders itself throws nothing, so nothing is logged and nothing this file
		// renders changes. An earlier version of this comment claimed the WARN was asserted because
		// of it, which cannot be true of a mutation that raises no failure at all. What covers it is
		// the scan, which since then reads resolveAllOrders' own body as well as this method's.
		//
		// Asserted on the WARN carrying the patient's uuid — a value the production call passes as a
		// parameter — rather than on any wording, so a re-worded message still satisfies it and an
		// unrelated WARN from chart assembly cannot.
		builder.failOrderRead = true;
		chartOf(drugOrderDoc(LAPSED_ORDER_ID), drugOrderDoc(LIVE_ORDER_ID));

		try (LogCapture capture = LogCapture.on(QueryStoreChartBuilder.class.getName())) {
			builder.build(patient, MEDICATIONS_QUESTION);

			boolean namesThePatient = false;
			for (String warning : capture.messagesAt(Level.WARN)) {
				if (warning.contains(patient.getUuid())) {
					namesThePatient = true;
				}
			}
			assertTrue(namesThePatient,
					"a failed order read must be reported at WARN, naming the patient it silently "
							+ "cost the mark; captured: " + capture.describeAll());
		}
	}

	@Test
	public void anEmptyActiveOrderSetStillMarksEveryRecordNotActive() {
		// The case an isEmpty() shortcut for "could not read" would silently break, and it is not a
		// corner: it is the arrangement issue #315 reported — one ended order and nothing active.
		Patient patientSix = Context.getPatientService().getPatient(6);
		chartOf(drugOrderDoc(ONLY_ORDER_OF_A_PATIENT_WITH_NONE_ACTIVE));

		PatientChart chart = builder.build(patientSix, MEDICATIONS_QUESTION);

		assertFalse(Context.getOrderService()
						.getOrder(ONLY_ORDER_OF_A_PATIENT_WITH_NONE_ACTIVE).isActive(),
				"precondition: this patient's only order is not in force, so nothing of theirs is — "
						+ "asked with the predicate production uses, not with getActiveOrders");
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
	public void anOrderCoreCannotEvaluateDoesNotCostTheRestOfTheChartItsMarks() {
		// The reading walks every order the patient has, and Order.isDiscontinued/isExpired THROW —
		// before any other test — when date_stopped is after auto_expire_date. Core's own validator
		// never compares those two fields, and OrderServiceImpl.stopOrder writes that row through the
		// public API when order.allowSettingStopDateOnInactiveOrders is enabled, so the shape is
		// reachable rather than merely malformed.
		//
		// Evaluated inside one chart-wide try, a single such row anywhere in the patient's history
		// takes the mark off EVERY drug-order record on every chart for that patient — silently
		// reverting to the pre-#317 behaviour this whole change exists to remove, and leaving a WARN
		// that misdescribes what happened. Order 9319 is never charted here; only order 3 is. It does
		// not have to be charted to do the damage.
		chartOf(drugOrderDoc(LIVE_ORDER_ID));

		PatientChart chart = builder.build(patient, MEDICATIONS_QUESTION);

		assertEquals(Boolean.TRUE, mappingFor(chart, uuidOf(LIVE_ORDER_ID)).getOrderActive(),
				"one unevaluable order in the patient's history must not silence a healthy record's "
						+ "mark: " + chart.getText());
		assertTrue(lineFor(chart, uuidOf(LIVE_ORDER_ID)).endsWith(PatientChartSerializer.ACTIVE_ORDER_LABEL),
				"and the chart line must still carry it: " + lineFor(chart, uuidOf(LIVE_ORDER_ID)));
	}

	@Test
	public void theRecordOfAnOrderCoreCannotEvaluateSaysNothingRatherThanNo() {
		// The other half of the per-order guard, and the one that decides how it is written. Not
		// marking the rest of the chart wrongly is one claim; not marking THIS record wrongly is
		// another, and only this case can see it — the sibling case never charts order 9319, so the
		// statement order inside the try is invisible to it.
		//
		// The uuid must reach NEITHER set. Recording it as known before asking isActive() — the
		// natural way to write the loop — leaves it known and not active, which is exactly the
		// combination forRecord answers FALSE to, and the module would tell a clinician that a
		// prescription it could not evaluate had ended. Silence is the only honest answer here.
		chartOf(drugOrderDoc(UNEVALUABLE_ORDER_ID));

		PatientChart chart = builder.build(patient, MEDICATIONS_QUESTION);

		String uuid = uuidOf(UNEVALUABLE_ORDER_ID);
		assertNull(mappingFor(chart, uuid).getOrderActive(),
				"an order the module cannot evaluate must carry no answer at all");
		assertFalse(lineFor(chart, uuid).endsWith(PatientChartSerializer.INACTIVE_ORDER_LABEL),
				"and above all must not be reported as ended: " + lineFor(chart, uuid));
		assertFalse(lineFor(chart, uuid).endsWith(PatientChartSerializer.ACTIVE_ORDER_LABEL),
				"nor as in force: " + lineFor(chart, uuid));
	}

	@Test
	public void aRecordThatIsNotADrugOrderIsNeverMarked() {
		// Scope: the ticket is about drug orders. The order read returns every order type, so the
		// uuid sets cover a test order too — this is a deliberate scope boundary, not a limit of the
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
		// The read is not free — it is an OrderService call on every chart assembly, sized by the
		// patient's whole order history — so it is not made for a chart that has nothing to mark.
		// Asserted rather than assumed, because the guard is invisible in the rendered output.
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
		//
		// These two are the ones the A/B chose. Measured on the host standalone (fullChart, drug
		// reference on), n=3 per cell, interleaved with a question on another patient between
		// samples, patient a7090f70 (Simvastatin lapsed by auto_expire_date beside a live Bupivacaine
		// and Lidocaine) asked "what medications is the patient taking?": ". Active order: yes/no",
		// ". Active: yes/no", ". Status: active/inactive" and ". Order status: active/not active" all
		// answered "No active medications are recorded" — denying two live prescriptions — and these
		// answered "The patient is currently taking Bupivacaine [3] and Lidocaine [4]". The last pair
		// differs from these only in the value token, which is what makes it the mark's own word
		// being recited rather than a lucky string. PatientChartSerializer.ACTIVE_ORDER_LABEL's
		// javadoc carries the arrangement, the base arm and the residue.
		assertEquals(". Order status: in force", PatientChartSerializer.ACTIVE_ORDER_LABEL,
				"changing this changes what every chart says to the model; re-measure before editing");
		assertEquals(". Order status: not in force", PatientChartSerializer.INACTIVE_ORDER_LABEL,
				"changing this changes what every chart says to the model; re-measure before editing");
	}

	@Test
	public void theTwoPredicatesTheModuleAsksAgreeOnEveryOrderEitherCanEvaluate() throws Exception {
		// Since issue #317 the module holds TWO answers to "is this order in force" and they are not
		// one answer by construction. Chart assembly asks core's Java Order.isActive() over
		// getAllOrdersByPatient; every safety chip asks core's Criteria predicate inside
		// getActiveOrders (PatientClinicalContextBuilder). They agree on every leg constructible
		// today — DrugReferenceInjector.describesEndedOrder's javadoc says so in as many words, and
		// says it is agreement between two predicates rather than agreement by construction. Nothing
		// pinned it, and a claim of that shape with no guard is what CLAUDE.md refuses elsewhere: the
		// chip and the record came apart the first time a key was copied, silently.
		//
		// So this drives BOTH over the whole of one patient's real drug orders. The chart side is the
		// production path end to end (build -> toSerializedRecords -> readOrderCurrency), read off
		// RecordMapping.getOrderActive(). The chip side is core's own getActiveOrders, called with
		// the arguments PatientClinicalContextBuilder calls it with — pinned as those arguments at
		// the end of this case, because a test that quietly asked a different question would prove
		// agreement with nothing the module does.
		//
		// Order 9319 is excluded and its exclusion is asserted rather than assumed: Order.isActive()
		// THROWS on it while the SQL simply answers, which is the one divergence that exists by
		// construction and is why readingOf evaluates each order on its own.
		//
		// It discriminates, and on a row nothing else in this file charts: mutating readingOf to
		// admit a DISCONTINUE order (order.isActive() || Action.DISCONTINUE.equals(getAction()))
		// reddens this case, on standard-dataset order 22, and nothing else in the 1464-test api
		// suite. Every other case here pins one named order; this is the only one that walks the
		// patient's whole list, which is what makes it the guard for a change in CORE rather than
		// in this module.
		List<Integer> drugOrderIds = new ArrayList<Integer>();
		List<QueryDocument> docs = new ArrayList<QueryDocument>();
		for (Order order : Context.getOrderService().getAllOrdersByPatient(patient)) {
			if (order instanceof DrugOrder && order.getOrderId() != UNEVALUABLE_ORDER_ID) {
				drugOrderIds.add(order.getOrderId());
				docs.add(drugOrderDoc(order.getOrderId()));
			}
		}
		assertTrue(drugOrderIds.size() >= 3,
				"precondition: this patient must carry enough drug orders for the comparison to mean "
						+ "something; found " + drugOrderIds);
		chartOf(docs.toArray(new QueryDocument[docs.size()]));

		PatientChart chart = builder.build(patient, MEDICATIONS_QUESTION);

		// The safety layer's predicate, asked exactly as PatientClinicalContextBuilder asks it.
		Set<String> activeBySqlPredicate = new HashSet<String>();
		for (Order order : Context.getOrderService().getActiveOrders(patient, null, null, null)) {
			activeBySqlPredicate.add(order.getUuid());
		}

		int active = 0;
		int inactive = 0;
		for (Integer orderId : drugOrderIds) {
			String uuid = uuidOf(orderId);
			RecordMapping mapping = mappingFor(chart, uuid);
			assertNotNull(mapping, "precondition: order " + orderId + " must be in the chart");
			Boolean chartSays = mapping.getOrderActive();
			assertNotNull(chartSays,
					"precondition: the chart must have an answer for order " + orderId + ", or this "
							+ "order contributes nothing to the comparison");
			assertEquals(activeBySqlPredicate.contains(uuid), chartSays.booleanValue(),
					"the chart's Order.isActive() and the safety layer's getActiveOrders must classify "
							+ "order " + orderId + " alike — a core change that splits them splits the "
							+ "chart's prose from the chips built beside it");
			if (chartSays.booleanValue()) {
				active++;
			} else {
				inactive++;
			}
		}
		assertTrue(active > 0 && inactive > 0,
				"precondition: the comparison must span both answers, or two predicates that always "
						+ "said the same word would pass it; active=" + active + " inactive=" + inactive);

		// The one order excluded above, and why — stated as an assertion so that the exclusion cannot
		// quietly become a divergence nobody notices.
		String unevaluable = uuidOf(UNEVALUABLE_ORDER_ID);
		assertNull(mappingFor(chart, unevaluable),
				"precondition: order " + UNEVALUABLE_ORDER_ID + " is not charted here");
		assertFalse(activeBySqlPredicate.contains(unevaluable),
				"the SQL predicate answers for the row Order.isActive() throws on, which is the one "
						+ "divergence excluded from the comparison above");

		// And the chip side really is the module's own call. Read from the source rather than
		// asserted in prose: the comparison above proves the two predicates agree, and this is what
		// stops it proving that about a call the module does not make. The scan is the shape
		// theFailedReadPathReturnsTheNotReadState uses, and the same limits apply — it sees this
		// spelling and nothing else.
		Path contextBuilder = ModuleSourceRoot.apiRoot().resolve(
				"src/main/java/org/openmrs/module/chartsearchai/reference/PatientClinicalContextBuilder.java");
		assertTrue(Files.exists(contextBuilder), "cannot find the context builder at " + contextBuilder);
		String contextBuilderText = new String(Files.readAllBytes(contextBuilder), StandardCharsets.UTF_8);
		assertTrue(contextBuilderText.contains("getActiveOrders(patient, null, null, null)"),
				"the safety layer's active-order read must be the call this case compares against; if "
						+ "it has changed, change this case deliberately rather than letting the "
						+ "comparison drift onto a question the module no longer asks");
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
		// state, so building a reading there fails this even though it would change no output — and
		// the method must be the one that catches the read, so a failure swallowed inside it fails
		// this too rather than passing a scan that only looks at the catch it left untouched. Since
		// review round 2 it reads the SEAM's body as well, for the swallow one method further in;
		// see the block at the end, which says why nothing behavioural can see that one.
		Path source = ModuleSourceRoot.apiRoot().resolve(
				"src/main/java/org/openmrs/module/chartsearchai/api/impl/QueryStoreChartBuilder.java");
		assertTrue(Files.exists(source), "cannot find the builder's source at " + source);
		String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

		int methodAt = text.indexOf("private OrderCurrency readOrderCurrency(");
		assertTrue(methodAt > 0, "cannot find readOrderCurrency in " + source);
		int catchAt = text.indexOf("catch (RuntimeException e) {", methodAt);
		assertTrue(catchAt > 0, "readOrderCurrency must still catch a failed order read");
		int methodEnd = text.indexOf("\n\t}", catchAt);
		String method = text.substring(methodAt, methodEnd);
		String catchBlock = text.substring(catchAt, methodEnd);
		assertTrue(catchBlock.contains("return OrderCurrency.unread();"),
				"a failed order read must return the explicit not-read state, never a constructed "
						+ "reading — an empty reading answers the same today only because nothing can "
						+ "be attributed under it. Found: " + catchBlock);
		// The scan has to be about the method's SHAPE and not only about words appearing in it,
		// because the sentence above is satisfied by a method that never reaches that catch. An inner
		// try around the read returning Collections.emptyList() leaves this catch untouched and
		// produces a constructed empty reading anyway — the exact state the assertion above refuses —
		// while changing nothing any rendered-output assertion here can see. The assertion above alone
		// cannot refuse it — that mutation leaves this catch untouched, words and all — so it is these
		// three lines that do, and aFailedOrderReadIsReportedAtWarn that sees it from the other side.
		// So: one try, one catch, and the read is what the try returns.
		assertEquals(1, occurrencesOf("try {", method),
				"readOrderCurrency must catch the order read ITSELF, not delegate to an inner try "
						+ "whose failure never reaches this catch: " + method);
		assertEquals(1, occurrencesOf("catch (", method),
				"and it must have exactly the one catch this case is about: " + method);
		assertTrue(method.contains("return readingOf(resolveAllOrders(patient));"),
				"the guarded statement is the read itself: " + method);

		// And the method it delegates the read to must not swallow one either. This is the gap the
		// three mutations above leave open and that aFailedOrderReadIsReportedAtWarn cannot see: a
		// try/catch inside resolveAllOrders returning an empty list raises nothing, so no WARN is
		// logged, no mark changes for any order that IS readable, and a chart whose orders could not
		// be read renders as a chart with no orders — the "empty set is an answer" state, which is
		// exactly the fail-open the catch above refuses. Measured both ways over the api suite: that
		// mutation was green on all 1464 tests before these assertions existed, and with them it
		// reddens this case and only this case.
		int seamAt = text.indexOf("protected List<Order> resolveAllOrders(Patient patient) {");
		assertTrue(seamAt > 0, "cannot find the order-read seam in " + source);
		String seam = text.substring(seamAt, text.indexOf("\n\t}", seamAt));
		assertEquals(0, occurrencesOf("try {", seam),
				"the order read must propagate its failure to readOrderCurrency, not swallow it here: "
						+ seam);
		assertEquals(0, occurrencesOf("catch (", seam),
				"and must catch nothing of its own: " + seam);
	}

	/** Non-overlapping occurrences of {@code needle} in {@code haystack}. */
	private static int occurrencesOf(String needle, String haystack) {
		int count = 0;
		for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
			count++;
		}
		return count;
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
