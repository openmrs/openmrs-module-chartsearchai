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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openmrs.DrugOrder;
import org.openmrs.Order;
import org.openmrs.api.context.Context;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.serialization.DrugOrderRecordSerializer;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Contract pin for the ONE place the active-order reconciliation reads querystore's rendered prose
 * rather than a structural field: {@link DrugReferenceInjector#describesEndedOrder}, which decides
 * whether a {@code drug_order} chart record may substantiate a currently-active order.
 *
 * <p>Why this test exists. Keying a safety decision on another module's display text is fragile in
 * the worst way — a wording change there would silently reopen issue #118 (a stopped order's record
 * substantiating the live order that replaced it) with every other test still green. So the markers
 * are asserted against the output of querystore's
 * REAL {@link DrugOrderRecordSerializer}, driven through its public
 * {@code AbstractRecordSerializer.serialize} entry point on a real {@link DrugOrder} from the
 * standard test dataset. No hand-typed imitation of the format appears here: if querystore renames
 * or restructures these markers, this test fails and names what broke.
 *
 * <p><b>This is no longer the only reader of that text.</b> Since issue #315,
 * {@code LlmProvider.DEFAULT_SYSTEM_PROMPT} shows the model the same two markers — as
 * {@link DrugReferenceInjector#ORDER_STOPPED_MARKER} and
 * {@link DrugReferenceInjector#ORDER_DISCONTINUED_MARKER}, the display-cased constants — so that an
 * answer naming a drug from an ended order has to say the order ended. A querystore rewording
 * therefore breaks two things, not one, and {@code EndedOrderMarkerContractTest} is the companion
 * pin for the second: it asserts those constants against this same real serializer's RAW output,
 * casing included, which this class cannot do because it matches on a lowercased copy.
 *
 * <p>Both directions are pinned. An ended order's text must be RECOGNISED as ended, and an ordinary
 * live order's text must NOT be — the second matters just as much, because over-matching would treat
 * every agreeing chart as drifted and fire the reconciliation's WARN on every query, which is the
 * failure the completeness gate exists to avoid.
 *
 * <p>The order is mutated in memory and never saved: {@code Order.setDateStopped} is not public
 * (core forces {@code OrderService.discontinueOrder}), so the ended states are reached through the
 * public setters {@code setAction} and {@code setAutoExpireDate}, which is enough to exercise the
 * serializer's rendering of them.
 */
public class QuerystoreOrderTextMarkerTest extends BaseModuleContextSensitiveTest {

	/** Order 3 of the standard test dataset: a real, fully-populated Triomune-30 DrugOrder. */
	private static final int TRIOMUNE_ORDER_ID = 3;

	/** The real querystore serializer's rendered text for {@code order}, lowercased exactly as
	 *  {@link DrugReferenceInjector} lowercases a chart record before testing it. */
	private String renderedLowerText(DrugOrder order) {
		QueryDocument doc = new DrugOrderRecordSerializer().serialize(order);
		return doc.getText() == null ? "" : doc.getText().toLowerCase(Locale.ROOT);
	}

	private DrugOrder triomuneOrder() {
		return (DrugOrder) Context.getOrderService().getOrder(TRIOMUNE_ORDER_ID);
	}

	@Test
	public void aDiscontinueOrdersRenderedTextIsRecognisedAsEnded() {
		DrugOrder order = triomuneOrder();
		order.setAction(Order.Action.DISCONTINUE);

		String text = renderedLowerText(order);

		assertTrue(DrugReferenceInjector.describesEndedOrder(text),
				"querystore renders Order.action, and a DISCONTINUE order must be recognised as "
						+ "ended or it will substantiate the live order that replaced it. Rendered: " + text);
	}

	@Test
	public void aGenuinelyDiscontinuedOrdersRenderedTextIsRecognisedAsEnded() {
		// The renewal shape reached through the real production path: OrderService.discontinueOrder
		// stamps dateStopped on the ORIGINAL order, which is the order still sitting in querystore's
		// index when its replacement's document is missing. dateStopped has no public setter (core
		// forces this route), so this is the only way to pin the ". Stopped: " marker against real
		// serializer output rather than against a literal I typed.
		DrugOrder original = triomuneOrder();
		Context.getOrderService().discontinueOrder(original, "renewed at a higher dose", null,
				original.getOrderer(), original.getEncounter());

		String text = renderedLowerText((DrugOrder) Context.getOrderService()
				.getOrder(TRIOMUNE_ORDER_ID));

		assertTrue(DrugReferenceInjector.describesEndedOrder(text),
				"a discontinued order carries querystore's stop marker and must be recognised as "
						+ "ended, or it substantiates the live order that replaced it. Rendered: " + text);
	}

	@Test
	public void anAutoExpireDateAloneIsNotVisibleInTheRenderedText() {
		// Recorded because it is a REAL LIMITATION of keying on rendered prose, found by running this
		// serializer rather than by reading it: querystore puts auto_expire_date in the QueryDocument
		// METADATA but does not render it into the text, so an order that lapsed by auto-expiry
		// carries no end marker and can still substantiate a live order. This test exists to state
		// that plainly and to fail if querystore ever starts rendering it — at which point
		// describesEndedOrder covers auto-expiry for free and this test's expectation flips.
		//
		// Since #315 this gap is one the prompt has an opinion about, and the opinion is
		// deliberately weak: the clause's counterpart says a drug-order record carrying neither
		// marker "records no end" and stops there. It said "is CURRENT" for one review round, and
		// that was measured to assert the lapsed drug was current AND to drop the patient's two
		// live orders from the same answer — ADR Decision 45's residue bullet carries the verbatim
		// cells. The residue that remains is an INFERENCE the model may still make from a record
		// with no end marker, not a claim the module makes; nothing in the rendered text can close
		// it. Whoever flips this expectation closes it properly, and should re-read that bullet
		// rather than only describesEndedOrder's javadoc.
		//
		// It is the strongest argument for the structural fix noted on describesEndedOrder: the
		// metadata carries auto_expire_date, the rendered text cannot.
		DrugOrder order = triomuneOrder();
		order.setAction(Order.Action.NEW);
		Calendar past = Calendar.getInstance();
		past.add(Calendar.YEAR, -1);
		order.setAutoExpireDate(past.getTime());

		String text = renderedLowerText(order);

		assertFalse(DrugReferenceInjector.describesEndedOrder(text),
				"if this now FAILS, querystore has started rendering auto-expire into the text: "
						+ "auto-expired orders are then detected too, so update describesEndedOrder's "
						+ "javadoc and flip this expectation. Rendered: " + text);
	}

	@Test
	public void anOrdinaryLiveOrdersRenderedTextIsNotRecognisedAsEnded() {
		// The over-matching guard, and the reason describesEndedOrder keys on the Action VALUE
		// rather than on the ". Action: " label: querystore renders that label on every drug-order
		// record and Order.action defaults to NEW. Keying on the label would make every agreeing
		// chart look drifted.
		DrugOrder order = triomuneOrder();
		order.setAction(Order.Action.NEW);
		order.setAutoExpireDate(null);

		String text = renderedLowerText(order);

		assertFalse(DrugReferenceInjector.describesEndedOrder(text),
				"a live order's rendered text must not read as ended, or the reconciliation WARNs "
						+ "and injects on every query. Rendered: " + text);
	}

	@Test
	public void aRevisedButLiveOrdersRenderedTextIsNotRecognisedAsEnded() {
		// REVISE is the action a renewal's NEW order often carries; it is live.
		DrugOrder order = triomuneOrder();
		order.setAction(Order.Action.REVISE);
		order.setAutoExpireDate(null);

		String text = renderedLowerText(order);

		assertFalse(DrugReferenceInjector.describesEndedOrder(text),
				"a REVISE order is live and must still substantiate. Rendered: " + text);
	}

	@Test
	public void theStandardDatasetOrderCarriesTheDrugNameTheReconciliationMatchesOn() {
		// Precondition for every test above and for the reconciliation itself: querystore's rendered
		// text actually names the drug. If it stopped doing so, the name fallback would report every
		// order as unrepresented rather than under-reporting, and the tests above would pass while
		// the feature misfired in the opposite direction.
		String text = renderedLowerText(triomuneOrder());

		assertTrue(text.contains("triomune"),
				"querystore's drug-order text must name the drug for the name fallback to work: " + text);
	}

	@Test
	public void anEndedOrderStillCarriesTheDrugName_soExclusionIsWhatMakesTheDifference() {
		// Pins that the two markers are doing the work. The ended order's text still names the drug,
		// so plain name matching WOULD have matched it — which is exactly the #118 renewal bug — and
		// only describesEndedOrder separates the two cases.
		DrugOrder order = triomuneOrder();
		order.setAction(Order.Action.DISCONTINUE);

		String text = renderedLowerText(order);

		assertTrue(text.contains("triomune"),
				"precondition: the ended order's text still names the drug, so exclusion (not the "
						+ "absence of the name) is what stops it substantiating: " + text);
		assertTrue(DrugReferenceInjector.describesEndedOrder(text),
				"and it must be excluded");
	}
}
