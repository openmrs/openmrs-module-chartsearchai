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

import org.junit.jupiter.api.Test;
import org.openmrs.DrugOrder;
import org.openmrs.Order;
import org.openmrs.api.context.Context;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.serialization.DrugOrderRecordSerializer;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Two facts about the drug-order text querystore renders that nothing else in this repo pins: the
 * PREFIX that tells a drug order from the other order classes carrying the same end markers, and
 * that an ended order need carry no end DATE at all. {@link QuerystoreOrderTextMarkerTest} pins the
 * matcher's own behaviour, in both directions, over the same real serializer; this class pins the two
 * facts either side of it, and defers to it for everything the matcher itself decides — the
 * DISCONTINUE case below asserts only the missing date, because that this same arrangement is
 * RECOGNISED as ended is that class's {@code aDiscontinueOrdersRenderedTextIsRecognisedAsEnded}, and
 * one fact asserted by two context-sensitive classes is a second place to keep in step for nothing.
 *
 * <p><b>Neither fact has a reader in this module today, and they are kept on different grounds — say
 * which.</b> The missing date is a property anything reporting WHEN an order ended has to tolerate.
 * The prefix is a FORWARD contract, for the structural remedy ADR Decision 45 names: it is what a
 * TEXT-ONLY reader would need to tell a prescription from an ended lab test, and
 * {@link DrugReferenceInjector#describesEndedOrder}'s one caller does not need it because it gates on
 * the record's resource type before reading the text at all. So this case would fail the criterion
 * that deleted three siblings from this class (below) if that criterion were "has a production
 * reader" — it is not: those cases were dropped because the change class they could signal cannot
 * reach a DECISION here whatever querystore does (the match is on lowercased text), while querystore
 * renaming this prefix would really break the remedy the entry recommends, and nothing else in the
 * repo would say so. Do not defend the prefix case by claiming a present-day consumer; it has none.
 *
 * <p><b>The expected strings are literals here, deliberately.</b> No production code reads the raw
 * display-cased form — the matcher lowercases — so a constant holding it in
 * {@link DrugReferenceInjector} would be a constant with no production reader, and its javadoc would
 * have to claim one. An earlier version of this class asserted the raw casing and spacing of both
 * markers against exactly such constants; the cases were dropped because the only change class they
 * could ever signal is one this module is by construction immune to (the match is on lowercased
 * text), and the repair when they fired would be to edit production code for a non-defect.
 *
 * <p><b>Why the serializer is in the loop.</b> An assertion that reads a constant back out of
 * something that concatenated it is true by construction and pins nothing. So the load-bearing
 * operand comes from OUTSIDE this module: querystore's real {@link DrugOrderRecordSerializer},
 * driven through its public {@code AbstractRecordSerializer.serialize} on a real {@link DrugOrder}
 * from the standard test dataset, exactly as {@link QuerystoreOrderTextMarkerTest} does.
 *
 * <p><b>Written for issue #315 and outliving its change.</b> That ticket tried to make an answer
 * naming a drug from an ended order say the order had ended, by a clause in
 * {@code LlmProvider.DEFAULT_SYSTEM_PROMPT} that showed the model these cues; the clause was
 * measured and reverted (ADR Decision 45 — no two-class ENDED/CURRENT clause can classify a record
 * whose text carries neither marker, which is what an auto-expired order's does). These cases are
 * the DATA half of that pair and are kept because what they pin is querystore's format, not the
 * prompt: the structural remedy that entry names has to read both facts. The prompt half was
 * {@code EndedOrderAnswerRuleTest}, deleted with the clause.
 */
public class EndedOrderMarkerContractTest extends BaseModuleContextSensitiveTest {

	/** Order 3 of the standard test dataset: a real, fully-populated Triomune-30 DrugOrder. */
	private static final int TRIOMUNE_ORDER_ID = 3;

	/** querystore's rendered text for {@code order}, exactly as it reaches the chart — NOT
	 *  lowercased, because the two facts pinned here are claims about what that text carries. */
	private String renderedText(DrugOrder order) {
		QueryDocument doc = new DrugOrderRecordSerializer().serialize(order);
		return doc.getText() == null ? "" : doc.getText();
	}

	private DrugOrder triomuneOrder() {
		return (DrugOrder) Context.getOrderService().getOrder(TRIOMUNE_ORDER_ID);
	}

	@Test
	public void aDiscontinuedOrderCarriesNoStopDateAtAll() {
		// An ended order need carry no END DATE in its text: querystore appends ". Stopped: " only
		// for a non-null dateStopped and ". Action: " unconditionally, so a DISCONTINUE record reads
		// as ended with no date to give. Recorded because anything downstream that wants to report
		// WHEN an order ended has to treat the date as optional — the #315 prompt clause did, and
		// the structural remedy ADR Decision 45 names will have to as well.
		//
		// The two halves of that sentence are pinned in two places on purpose. That this record is
		// ENDED is QuerystoreOrderTextMarkerTest.aDiscontinueOrdersRenderedTextIsRecognisedAsEnded,
		// asserted there over the same arrangement and not repeated here. What is asserted here is
		// only the missing date, and it is missing because dateStopped is null on this order rather
		// than because the action is DISCONTINUE — which is the point: the action is what makes the
		// record ended, and it supplies no date to render.
		DrugOrder order = triomuneOrder();
		order.setAction(Order.Action.DISCONTINUE);

		String rendered = renderedText(order);

		assertFalse(rendered.contains(". Stopped: "),
				"this ended record carries no stop date at all, so no consumer may demand one. "
						+ "Rendered: " + rendered);
	}

	@Test
	public void aDrugOrderRecordsTextBeginsWithThisPrefix() {
		// The one thing in the rendered TEXT that tells a drug order from the other order classes:
		// querystore's AbstractServiceOrderRecordSerializer emits the SAME ". Stopped: " and
		// ". Action: " markers behind "Referral order:" and "Test order:", so describesEndedOrder
		// alone cannot tell an ended prescription from an ended lab test. NOTHING IN THIS MODULE
		// READS THIS PREFIX — describesEndedOrder's one caller gates on the record's resource type
		// before touching the text — so this is a forward contract for the structural remedy ADR
		// Decision 45 recommends, which is text-only and would need exactly this cue, pinned against
		// the producer rather than a literal that agrees with itself. See the class javadoc for why
		// that is a different ground from the one three deleted siblings failed.
		String rendered = renderedText(triomuneOrder());

		assertTrue(rendered.startsWith("Drug order:"),
				"querystore must still render a drug-order record under this prefix, which is the only "
						+ "cue in the text that tells a prescription from an ended lab test. "
						+ "Rendered: " + rendered);
	}
}
