/**
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * The active-order reconciliation (issue #118) reads the module's own authoritative answer about
 * whether a chart record's order has ended, in preference to querystore's display prose.
 *
 * <p>Why this had to change with issue #317. That reconciliation decides which active orders the
 * chart already substantiates, and it decided it by looking for {@code ". Stopped: "} or
 * {@code ". Action: DISCONTINUE"} in the record's text. querystore renders neither for an order that
 * lapsed by its {@code auto_expire_date}, so a lapsed record went on substantiating a live order of
 * the same drug — a known limitation, recorded on {@code DrugReferenceInjector.describesEndedOrder},
 * whose own javadoc calls plumbing the structural field the better fix.
 *
 * <p>It had to change WITH #317 rather than after it, because #317 makes that limitation actively
 * harmful rather than merely unrepaired. Before the mark, the lapsed record reached the model as an
 * unqualified drug-order line and the answer read the patient as being on the drug — accidentally
 * right about the drug, since a live order for it does exist. With the mark, that same record says
 * {@code ". Active order: no"} while the live order it suppressed is still not injected, so the only
 * record naming the drug now denies it. A silent false negative becomes an explicit false statement
 * in citable evidence.
 *
 * <p><strong>Prose is kept as the fallback, not replaced.</strong> The authoritative answer is absent
 * for a record whose order the module could not attribute to this patient and for a chart built when
 * the order read failed — the two cases {@code SerializedRecord.getOrderActive()} returns
 * {@code null} for — and in both of those the text is still the best evidence there is. Which is also
 * what keeps the name fallback intact for the drifted-uuid record it exists for.
 *
 * <p>What this file does not do is prove that the flag it reads is the one the chart builder
 * produces: these mappings are built here, as every case in {@code ActiveOrderReconciliationTest} is,
 * because querystore does not index under a module test. That half is
 * {@code DrugOrderCurrencyMarkTest}, which asserts the same accessor on a mapping the REAL
 * {@code QueryStoreChartBuilder} produced from a REAL order. The two meet on
 * {@code RecordMapping.getOrderActive()} and both assert it.
 */
public class AuthoritativeEndedOrderSubstantiationTest {

	/** querystore's drug-order resource type, as its {@code DrugOrderRecordSerializer} reports it. */
	private static final String DRUG_ORDER = "drug_order";

	private static final String SIMVASTATIN_ORDER_UUID = "11111111-2222-3333-4444-555555555555";

	/** The record text of the order that lapsed by its duration: it names the drug, and — this is the
	 *  whole point — it carries no end marker at all, because querystore renders none for
	 *  {@code auto_expire_date}. Pinned against the real serializer by
	 *  {@code QuerystoreOrderTextMarkerTest.anAutoExpireDateAloneIsNotVisibleInTheRenderedText}. */
	private static final String LAPSED_RECORD_TEXT =
			"Drug order: Simvastatin Co 20mg. Dose: 20 Milligram Oral Once daily";

	private DrugReferenceInjector injector() {
		return DrugReferenceTestSupport
				.injectorWithSafety(DrugReferenceTestSupport.ddinterServiceWithGroups());
	}

	/** One active simvastatin order — the live 40mg prescription whose own document is missing from
	 *  the index, which is the drift shape the reconciliation exists for. */
	private PatientClinicalContext oneActiveOrder() {
		return DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("simvastatin"), DrugReferenceTestSupport.set("C10AA01"), null, null,
				Collections.singletonList(DrugReferenceTestSupport.activeOrder(SIMVASTATIN_ORDER_UUID,
						"Simvastatin Co 20mg", "simvastatin")));
	}

	private List<RecordMapping> activeOrderRecords(PatientChart chart) {
		List<RecordMapping> found = new ArrayList<RecordMapping>();
		for (RecordMapping mapping : chart.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER.equals(mapping.getResourceType())) {
				found.add(mapping);
			}
		}
		return found;
	}

	/** A drug-order record carrying the module's authoritative answer, as the chart builder now
	 *  produces it. {@code null} is the answer for a record the module cannot speak for. */
	private RecordMapping drugOrderRecord(int index, String orderUuid, String text, Boolean orderActive) {
		return new RecordMapping(index, DRUG_ORDER, orderUuid, null, text, null, 0, orderActive);
	}

	@Test
	public void aRecordTheModuleKnowsIsNotActiveDoesNotSubstantiateALiveOrder() {
		// The case prose cannot reach, and the reason this change ships with #317 rather than after
		// it. Nothing in this text says the order ended; only the module knows.
		PatientChart chart = DrugReferenceTestSupport.chartOf(
				drugOrderRecord(1, "lapsed-order-uuid", LAPSED_RECORD_TEXT, Boolean.FALSE));

		PatientChart result = injector().injectRecords(chart, oneActiveOrder(),
				"what are her active medications?");

		assertEquals(1, activeOrderRecords(result).size(),
				"a record the module knows is not in force cannot substantiate a live order, however "
						+ "its text reads: " + result.getText());
	}

	@Test
	public void theSameRecordWithoutTheAnswerStillSubstantiatesByName() {
		// The fallback, and the guard on the change above: identical text, identical uuid, and the
		// ONLY difference is that the module has no answer for this record. The name fallback must
		// still apply, or a chart built when the order read failed — or one carrying a record whose
		// order could not be attributed — would report every active order as unrepresented and the
		// reconciliation would WARN and inject on every query, which is the loudest possible failure
		// and the thing the fallback was added to prevent.
		PatientChart chart = DrugReferenceTestSupport.chartOf(
				drugOrderRecord(1, "some-other-uuid", LAPSED_RECORD_TEXT, null));

		PatientChart result = injector().injectRecords(chart, oneActiveOrder(),
				"what are her active medications?");

		assertTrue(activeOrderRecords(result).isEmpty(),
				"with no authoritative answer the record's name is still evidence: " + result.getText());
	}

	@Test
	public void aRecordTheModuleKnowsIsActiveStillSubstantiates() {
		// The positive half. A live record naming the drug already tells the model the patient has an
		// order for it, so there is nothing to repair — and a change that excluded records by the
		// PRESENCE of the mark rather than by its value would inject a second record for every
		// prescription the chart already carries.
		PatientChart chart = DrugReferenceTestSupport.chartOf(
				drugOrderRecord(1, "some-other-uuid", LAPSED_RECORD_TEXT, Boolean.TRUE));

		PatientChart result = injector().injectRecords(chart, oneActiveOrder(),
				"what are her active medications?");

		assertTrue(activeOrderRecords(result).isEmpty(),
				"a record the module knows IS in force substantiates: " + result.getText());
	}
}
