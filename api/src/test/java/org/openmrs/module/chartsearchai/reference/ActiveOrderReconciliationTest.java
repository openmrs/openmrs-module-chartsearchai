/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Reconciliation between the drug-safety layer's active-order read and the serialized chart
 * (issue #118), exercised through the real {@link DrugReferenceInjector#injectRecords} seam over
 * the real bundled dataset.
 *
 * <p>The defect: the safety layer reads active orders straight from {@code OrderService} while the
 * answer is grounded only in the serialized querystore chart, and nothing compared the two. When
 * they disagreed the clinician got both — a chip naming "active order simvastatin" beside an
 * answer stating "No active medications are recorded." (observed on the 3.7.1 standalone, HEAD
 * {@code 13690b1}), with nothing logged. The trigger there was querystore index drift, which is
 * querystore's defect to fix; what belongs here is that the divergence was silent and degraded
 * into a contradiction instead of into a missing record.
 *
 * <p>The chip is deliberately NOT suppressed when the chart disagrees: it comes from the
 * authoritative service read and was right in every observed case, so silencing it would trade a
 * visible contradiction for a missing safety warning. These tests therefore only assert what the
 * chart gains.
 */
public class ActiveOrderReconciliationTest {

	/** querystore's drug-order resource type, as its {@code DrugOrderRecordSerializer} reports it. */
	private static final String DRUG_ORDER = "drug_order";

	private static final String SIMVASTATIN_ORDER_UUID = "11111111-2222-3333-4444-555555555555";

	private static final String ASPIRIN_ORDER_UUID = "66666666-7777-8888-9999-000000000000";

	/** The injector with the validator wired — so the safety findings of #110 flow too and these tests
	 *  see the whole injected record set, not a reconciliation-only subset. Through the shared
	 *  arrangement rather than a copy of it, so "exactly as the other files build it" is a fact instead
	 *  of a promise. */
	private DrugReferenceInjector injector() {
		return DrugReferenceTestSupport
				.injectorWithSafety(DrugReferenceTestSupport.ddinterServiceWithGroups());
	}

	/** A context holding one active simvastatin order — the shape of the observed case. */
	private PatientClinicalContext oneActiveOrder() {
		return DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("simvastatin"), DrugReferenceTestSupport.set("C10AA01"), null, null,
				Collections.singletonList(DrugReferenceTestSupport.activeOrder(SIMVASTATIN_ORDER_UUID,
						"Simvastatin Co 20mg", "simvastatin")));
	}

	/** Every injected active-order record in {@code chart}. */
	private List<RecordMapping> activeOrderRecords(PatientChart chart) {
		List<RecordMapping> found = new ArrayList<RecordMapping>();
		for (RecordMapping mapping : chart.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER.equals(mapping.getResourceType())) {
				found.add(mapping);
			}
		}
		return found;
	}

	@Test
	public void anActiveOrderTheChartCannotSubstantiateIsInjectedAsACitableRecord() {
		// The observed case, reduced: the safety layer holds an active simvastatin order and the
		// chart carries no drug-order record for it (its querystore document was missing —
		// drug_order core=7 indexed=6). Before this, the two reads were never compared, so the
		// module answered "No active medications are recorded." to three different phrasings while
		// a chip named that same order. The medication list must reach the prompt as a record: the
		// model reporting a line in front of it is the mechanism #110 established, and it cannot
		// deny a medication it can read.
		PatientChart result = injector().injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				oneActiveOrder(), "what are her active medications?");

		List<RecordMapping> injected = activeOrderRecords(result);
		assertEquals(1, injected.size(),
				"the unrepresented active order must be injected as its own record: " + result.getText());
		RecordMapping record = injected.get(0);
		assertEquals(SIMVASTATIN_ORDER_UUID, record.getResourceUuid(),
				"the record must carry the real Order uuid so the citation stays navigable");
		// The rendering is pinned exactly, not just "contains simvastatin". Two decisions live in this
		// one string. It is shaped like querystore's own drug-order text ("Drug order: <drug>. …") so
		// the model reads it as the chart record it stands in for. And it is a plain positive
		// assertion: a hedge inside the record ("no matching record was retrieved for it") is the
		// exact shape that made the model put an abstention clause in front of its own evidence in
		// #110, so the provenance is carried by the resource type and the WARN, not by prose in front
		// of a clinician.
		assertEquals("Active drug order: Simvastatin Co 20mg.", record.getText());
		assertTrue(result.getText().contains("[" + record.getIndex() + "] "),
				"it must be a numbered, citable chart line so the answer can cite it: " + result.getText());
	}

	@Test
	public void aNonOrderRecordMentioningTheDrugDoesNotSubstantiateTheOrder() {
		// What substantiates an active order is a DRUG-ORDER record, not the drug's name appearing
		// anywhere in the chart. An obs note that happens to mention simvastatin is not evidence that
		// the patient has an order for it, so an answer grounded in that chart can still deny the
		// medication while a chip names it — the defect. Scoping the text fallback to drug-order
		// records is what keeps the check about orders; widening it to all record text would silently
		// mask the discrepancy on any patient whose notes mention their own drugs, which is most of
		// them.
		PatientChart chart = DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.obsRecord(1, "Note: patient reports taking simvastatin at night"));

		PatientChart result = injector().injectRecords(chart, oneActiveOrder(),
				"what are her active medications?");

		assertEquals(1, activeOrderRecords(result).size(),
				"an obs mentioning the drug is not a drug order: " + result.getText());
	}

	@Test
	public void anInjectedActiveOrderPresentsAsChartEvidenceNotReferenceMaterial() {
		// The group decision, and the one that is dangerous to get wrong in EITHER direction.
		// referenceGroup fails safe to chart for unrecognised types, which is the WRONG default for
		// the module's other injected records (a drug-reference entry published as the patient's own
		// data) — but it is the RIGHT answer here, and not by accident: an active drug order is the
		// patient's own record, read from the authoritative service, carrying the real Order uuid.
		// Grouping it as module-supplied reference material would tell a clinician that a live
		// prescription is not their patient's data, which is the more dangerous inversion: it invites
		// them to discount the very order the safety chip is raised about.
		PatientChart result = injector().injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				oneActiveOrder(), "what are her active medications?");

		RecordMapping record = activeOrderRecords(result).get(0);
		assertEquals(ChartSearchAiConstants.REFERENCE_GROUP_CHART,
				ChartSearchAiUtils.referenceGroup(record.getResourceType()),
				"an active drug order is evidence about this patient, so it must group as chart evidence");
	}

	@Test
	public void anActiveOrderTheChartAlreadyCarriesIsNotInjected() {
		// The negative that matters most: a detector that fires when the two reads AGREE is worse
		// than no detector — it would log a WARN on every query for every medicated patient, and
		// inject a second record for a prescription the chart already carries, so the clinician
		// gets two citations for one order. Nothing is reconcilable here, so nothing may change.
		PatientChart chart = DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
				DrugReferenceTestSupport.drugOrderRecord(2, SIMVASTATIN_ORDER_UUID,
						"Simvastatin Co 20mg. Dose: 20 Milligram Oral Once daily"));

		PatientChart result = injector().injectRecords(chart, oneActiveOrder(),
				"what are her active medications?");

		assertSame(chart, result,
				"the chart substantiates every active order, so it must be returned untouched");
	}

	@Test
	public void anActiveOrderIsMatchedByNameWhenTheUuidsDoNotLineUp() {
		// The uuid match is exact (querystore indexes a drug_order document under the Order uuid),
		// but it must not be the ONLY match: were that contract to change, every order would look
		// unrepresented and the reconciliation would fire on every query — the loudest possible
		// failure. A drug-order record naming the drug already tells the model the patient has an
		// order for it, so there is nothing for the answer to deny and nothing to repair.
		PatientChart chart = DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.drugOrderRecord(1, "some-other-uuid",
						"Simvastatin Co 20mg. Dose: 20 Milligram Oral Once daily"));

		PatientChart result = injector().injectRecords(chart, oneActiveOrder(),
				"what are her active medications?");

		assertTrue(activeOrderRecords(result).isEmpty(),
				"a drug-order record naming the drug substantiates the order: " + result.getText());
	}

	@Test
	public void aStoppedOrdersRecordDoesNotSubstantiateALiveOrder() {
		// The renewal shape, and the reason the name fallback needed narrowing: stop Simvastatin
		// 20mg, start Simvastatin 40mg. querystore indexes the stopped order too (it is not voided)
		// and renders it with ". Stopped: <date>", so under drift — the replacement's document
		// missing — the OLD record's text names the drug and the new order looked substantiated.
		// Nothing was injected and nothing WARNed, while the answer, reading "Stopped:", correctly
		// reported no active medication beside a chip naming one. That is issue #118 verbatim,
		// reachable through the commonest order-lifecycle event there is.
		PatientChart chart = DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.drugOrderRecord(1, "stopped-order-uuid",
						"Simvastatin Co 20mg. Dose: 20 Milligram Oral Once daily. Stopped: 02 Nov 2025"));

		PatientChart result = injector().injectRecords(chart, oneActiveOrder(),
				"what are her active medications?");

		assertEquals(1, activeOrderRecords(result).size(),
				"a record describing a STOPPED order says the patient is not on the drug, so it "
						+ "cannot substantiate a live order for it: " + result.getText());
	}

	@Test
	public void aDiscontinuationRecordDoesNotSubstantiateALiveOrder() {
		// The other end-of-order shape querystore renders: the DISCONTINUE order itself. ". Action: "
		// alone is not a stop signal — every record carries it and the default is NEW — so only the
		// DISCONTINUE value may exclude a record from substantiating.
		PatientChart chart = DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.drugOrderRecord(1, "discontinue-order-uuid",
						"Simvastatin Co 20mg. Action: DISCONTINUE"));

		PatientChart result = injector().injectRecords(chart, oneActiveOrder(),
				"what are her active medications?");

		assertEquals(1, activeOrderRecords(result).size(),
				"a discontinuation record is the record of the drug ENDING, so it cannot "
						+ "substantiate a live order: " + result.getText());
	}

	@Test
	public void aLiveOrderRecordCarryingAnActionStillSubstantiates() {
		// The guard against over-correcting: ". Action: " is on every record (default NEW), and a
		// REVISE is a live order. Excluding on the marker rather than the DISCONTINUE value would
		// make every agreeing chart look drifted and fire the WARN on every query — the failure the
		// whole gate exists to avoid.
		//
		// The record deliberately carries a DIFFERENT uuid from the active order, so substantiation
		// can only come through the NAME fallback. With the order's own uuid the uuid match would
		// short-circuit first and this would pass however broken the corpus filter was.
		PatientChart chart = DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.drugOrderRecord(1, "revised-from-order-uuid",
						"Simvastatin Co 20mg. Dose: 20 Milligram Oral Once daily. Action: REVISE"));

		assertSame(chart, injector().injectRecords(chart, oneActiveOrder(),
				"what are her active medications?"),
				"a live REVISE order still substantiates, so the chart must come back untouched");
	}

	@Test
	public void anOrderNameIsNotSubstantiatedByAWordThatMerelyContainsIt() {
		// The substring half. The corpus was scanned with a plain String.contains, so a short order
		// name matched inside an unrelated word: an active ASA order read as substantiated by a
		// record saying "Nasal spray". That suppresses the injection AND the WARN, so the
		// discrepancy becomes invisible rather than merely unrepaired.
		PatientChart chart = DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.drugOrderRecord(1, "nasal-order-uuid", "Nasal spray"));
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("asa"), null, null, null,
				Collections.singletonList(DrugReferenceTestSupport.activeOrder("asa-order-uuid", "ASA")));

		PatientChart result = injector().injectRecords(chart, context, "what are her active medications?");

		assertEquals(1, activeOrderRecords(result).size(),
				"\"asa\" inside \"nasal\" is not a mention of the drug, so the order is "
						+ "unsubstantiated and must be injected: " + result.getText());
	}

	@Test
	public void onlyTheOrdersTheChartIsMissingAreInjected() {
		// Partial drift is the common shape (the observed instance was drug_order core=7 indexed=6),
		// and an all-or-nothing check would miss it entirely: the chart HAS drug-order records, just
		// not this one. Under-reporting a medication list while a chip can name the missing drug is
		// the same defect as denying the list outright.
		PatientChart chart = DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.drugOrderRecord(1, ASPIRIN_ORDER_UUID, "Aspirin 81mg"));
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("simvastatin", "aspirin"),
				DrugReferenceTestSupport.set("C10AA01", "B01AC06"), null, null,
				Arrays.asList(
						DrugReferenceTestSupport.activeOrder(ASPIRIN_ORDER_UUID, "Aspirin 81mg", "aspirin"),
						DrugReferenceTestSupport.activeOrder(SIMVASTATIN_ORDER_UUID, "Simvastatin Co 20mg",
								"simvastatin")));

		PatientChart result = injector().injectRecords(chart, context, "what are her active medications?");

		List<RecordMapping> injected = activeOrderRecords(result);
		assertEquals(1, injected.size(),
				"only the order the chart cannot substantiate may be injected: " + result.getText());
		assertEquals(SIMVASTATIN_ORDER_UUID, injected.get(0).getResourceUuid(),
				"the injected record must be the MISSING order, not the one already in the chart");
	}

	@Test
	public void aQueryScopedSliceThatNeverAskedForDrugOrdersIsNotReconciled() {
		// chartMode=queryScoped (the DEFAULT mode) builds a question-dependent slice: a vitals
		// question's slice legitimately carries no drug-order record at all, so absence there says
		// nothing about the index. Reconciling it anyway would WARN and inject the medication list
		// on essentially every non-medication query — destroying the WARN's value as a drift signal
		// and pushing an unasked-for medication list into every answer.
		PatientChart slice = DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.obsRecord(1, "BP 120/80"));
		slice.markQueryScoped();

		PatientChart result = injector().injectRecords(slice, oneActiveOrder(), "what is her blood pressure?");

		assertSame(slice, result,
				"a slice that never scoped drug orders carries no information about their absence");
	}

	@Test
	public void aQueryScopedSliceScopedToDrugOrdersIsReconciled() {
		// The other half: a medications question DOES scope the slice to drug_order, and every
		// drug-order document querystore holds is then in the slice by construction. So absence
		// there IS drift, and this is the exact question that produced "No active medications are
		// recorded." — it must be reconciled in the default chart mode, not only in fullChart.
		PatientChart slice = DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.obsRecord(1, "BP 120/80"));
		slice.markQueryScoped();
		slice.markCompleteFor(Collections.singleton(DRUG_ORDER));

		PatientChart result = injector().injectRecords(slice, oneActiveOrder(),
				"what medications is this patient currently taking?");

		assertEquals(1, activeOrderRecords(result).size(),
				"a slice complete for drug orders makes their absence meaningful: " + result.getText());
		assertTrue(result.isQueryScoped(),
				"the rebuilt chart must keep the query-scoped stamp");
		assertTrue(result.isCompleteFor(DRUG_ORDER),
				"the rebuilt chart must keep the completeness stamp, or a later consumer re-reads "
						+ "absence as meaningless");
	}

	@Test
	public void aPatientWithNoActiveOrdersIsNotReconciled() {
		// Nothing held, nothing to reconcile: the injected record exists only when the module is
		// holding an order the chart cannot substantiate, so an abstention about medications
		// survives by construction rather than by prompt wording.
		PatientChart chart = DrugReferenceTestSupport.oneRecordChart();

		PatientChart result = injector().injectRecords(chart,
				DrugReferenceTestSupport.ctx(60, null, null, null, null, null),
				"what are her active medications?");

		assertSame(chart, result, "no active orders means no discrepancy and no injection");
	}

	@Test
	public void theInjectedActiveOrderPrecedesTheDrugReferenceAndSafetyRecords() {
		// Reading order in the prompt, and the same order the REST layer renders references in
		// (chart evidence before reference material). The patient's own data comes first; the
		// module's reference material and the findings derived from it follow, so the clinician
		// reads evidence then conclusion rather than the reverse.
		PatientChart result = injector().injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				oneActiveOrder(), "is it safe to give her clarithromycin?");

		int activeOrder = -1;
		int reference = -1;
		int finding = -1;
		for (RecordMapping mapping : result.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER.equals(mapping.getResourceType())) {
				activeOrder = mapping.getIndex();
			} else if (ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(mapping.getResourceType())) {
				reference = mapping.getIndex();
			} else if (ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(mapping.getResourceType())) {
				finding = mapping.getIndex();
			}
		}
		assertTrue(activeOrder > 0 && reference > 0 && finding > 0,
				"precondition: this question must inject all three of the kinds whose ordering this "
						+ "asserts, else the ordering "
						+ "claim is untested (activeOrder=" + activeOrder + " reference=" + reference
						+ " finding=" + finding + ")");
		assertTrue(activeOrder < reference && reference < finding,
				"the patient's own active order must precede the reference material and the finding "
						+ "derived from it");
	}

	@Test
	public void everyInjectedRecordKeepsAContiguousCitationNumbering() {
		// The citation contract: indices are 1-based, contiguous and unique across chart records and
		// every kind of injected record. A gap or a repeat resolves an inline [N] marker in the
		// answer to the wrong record — or to nothing.
		PatientChart result = injector().injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				oneActiveOrder(), "is it safe to give her clarithromycin?");

		List<RecordMapping> mappings = result.getMappings();
		assertTrue(mappings.size() > 3, "precondition: chart record plus the injected ones");
		for (int i = 0; i < mappings.size(); i++) {
			assertEquals(i + 1, mappings.get(i).getIndex(), "citation numbering must stay contiguous");
			assertNotNull(mappings.get(i).getText(), "every record must carry text for grounding");
		}
		assertFalse(result.getText().contains("] null"),
				"no record may render a literal null: " + result.getText());
	}
}
