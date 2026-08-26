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

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openmrs.DrugOrder;
import org.openmrs.Order;
import org.openmrs.api.context.Context;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.serialization.DrugOrderRecordSerializer;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Contract snapshot of the drug-order text querystore actually renders, at a FINER resolution than
 * {@link QuerystoreOrderTextMarkerTest} takes it: that class matches on a lowercased copy, because
 * the predicate it pins ({@link DrugReferenceInjector#describesEndedOrder}, the #118 active-order
 * reconciliation's one reader of another module's prose) is defined on lowercased text. This class
 * asserts the RAW output — casing, spacing and the record prefix included.
 *
 * <p><b>Nothing in production reads the raw form</b>, and that is the point rather than an omission.
 * The module keys a safety decision on querystore's display prose, so a rewording there is worth
 * failing loudly on even in the cases the lowercased match would go on tolerating: a casing or
 * spacing change reddens this class and leaves the reconciliation green, which is the signal that
 * the producer moved while the module still worked. The expected forms are the package-private
 * constants on {@link DrugReferenceInjector} ({@link DrugReferenceInjector#ORDER_STOPPED_MARKER},
 * {@link DrugReferenceInjector#ORDER_DISCONTINUED_MARKER},
 * {@link DrugReferenceInjector#QUERYSTORE_DRUG_ORDER_PREFIX}) rather than literals typed here, so
 * that the raw form is written down once, beside the matcher that owns the lowercased one.
 *
 * <p><b>Why the serializer is in the loop.</b> An assertion that only reads a constant back out of
 * something that concatenated it is true by construction and pins nothing — it cannot tell a cue
 * the chart carries from one it does not. So the load-bearing operand comes from OUTSIDE this
 * module: querystore's real {@link DrugOrderRecordSerializer}, driven through its public
 * {@code AbstractRecordSerializer.serialize} on a real {@link DrugOrder} from the standard test
 * dataset, exactly as {@link QuerystoreOrderTextMarkerTest} does.
 *
 * <p><b>Written for issue #315 and outliving its change.</b> That ticket tried to make an answer
 * naming a drug from an ended order say the order had ended, by a clause in
 * {@code LlmProvider.DEFAULT_SYSTEM_PROMPT} that showed the model these same cues; the clause was
 * measured and reverted (ADR Decision 45 — the information is not in the record text, so a prompt
 * cannot classify it). These cases were the DATA half of that pair and are kept because what they
 * pin is querystore's format, not the prompt. The prompt half was
 * {@code EndedOrderAnswerRuleTest}, deleted with the clause.
 */
public class EndedOrderMarkerContractTest extends BaseModuleContextSensitiveTest {

	/** Order 3 of the standard test dataset: a real, fully-populated Triomune-30 DrugOrder. */
	private static final int TRIOMUNE_ORDER_ID = 3;

	/** querystore's rendered text for {@code order}, exactly as it reaches the chart — NOT
	 *  lowercased. See the class javadoc: the casing is part of what is being pinned. */
	private String renderedText(DrugOrder order) {
		QueryDocument doc = new DrugOrderRecordSerializer().serialize(order);
		return doc.getText() == null ? "" : doc.getText();
	}

	private DrugOrder triomuneOrder() {
		return (DrugOrder) Context.getOrderService().getOrder(TRIOMUNE_ORDER_ID);
	}

	@Test
	public void theRawStopMarkerIsVerbatimInWhatQuerystoreRenders() {
		// discontinueOrder stamps dateStopped on the ORIGINAL order; dateStopped has no public
		// setter (core forces this route), so this is the only way to reach the rendered stop
		// marker through real production code rather than a literal typed here.
		DrugOrder original = triomuneOrder();
		Context.getOrderService().discontinueOrder(original, "renewed at a higher dose", null,
				original.getOrderer(), original.getEncounter());

		String rendered = renderedText((DrugOrder) Context.getOrderService().getOrder(TRIOMUNE_ORDER_ID));

		assertTrue(rendered.contains(DrugReferenceInjector.ORDER_STOPPED_MARKER),
				"the display-cased stop marker must be verbatim — casing and spacing included — in "
						+ "what querystore actually renders, or this module holds a record of that "
						+ "producer's format that the producer no longer matches. Rendered: " + rendered);
		assertTrue(DrugReferenceInjector.describesEndedOrder(rendered.toLowerCase(Locale.ROOT)),
				"and the module's own matcher must recognise that same rendered text — the raw pin "
						+ "above is a claim about the producer, this one about the #118 "
						+ "reconciliation reading it. Rendered: " + rendered);
	}

	@Test
	public void theRawDiscontinueMarkerIsVerbatimInWhatQuerystoreRenders() {
		DrugOrder order = triomuneOrder();
		order.setAction(Order.Action.DISCONTINUE);

		String rendered = renderedText(order);

		assertTrue(rendered.contains(DrugReferenceInjector.ORDER_DISCONTINUED_MARKER),
				"the display-cased discontinue marker must be verbatim in querystore's output. "
						+ "Rendered: " + rendered);
		assertTrue(DrugReferenceInjector.describesEndedOrder(rendered.toLowerCase(Locale.ROOT)),
				"and the matcher must recognise it. Rendered: " + rendered);
	}

	@Test
	public void aDiscontinuedOrderCarriesNoStopDateAtAll() {
		// An ended order need carry no END DATE in its text: querystore appends ". Stopped: " only
		// for a non-null dateStopped and ". Action: " unconditionally, so a DISCONTINUE record reads
		// as ended with no date to give. Recorded because anything downstream that wants to report
		// WHEN an order ended has to treat the date as optional — the #315 prompt clause did, and
		// the structural remedy ADR Decision 45 names will have to as well.
		DrugOrder order = triomuneOrder();
		order.setAction(Order.Action.DISCONTINUE);

		String rendered = renderedText(order);

		assertFalse(rendered.contains(DrugReferenceInjector.ORDER_STOPPED_MARKER),
				"this ended record carries no stop date at all, so no consumer may demand one. "
						+ "Rendered: " + rendered);
		assertTrue(DrugReferenceInjector.describesEndedOrder(rendered.toLowerCase(Locale.ROOT)),
				"and it is still an ended order despite carrying no date");
	}

	@Test
	public void aDrugOrderRecordsTextBeginsWithThisPrefix() {
		// The one thing in the rendered TEXT that tells a drug order from the other order classes:
		// querystore's AbstractServiceOrderRecordSerializer emits the SAME ". Stopped: " and
		// ". Action: " markers behind "Referral order:" and "Test order:", so describesEndedOrder
		// alone cannot tell an ended prescription from an ended lab test. Its one caller does not
		// need it to — it gates on the record's resource type before reading the text — so this case
		// pins the cue any text-only reader would need, against the producer rather than a literal.
		String rendered = renderedText(triomuneOrder());

		assertTrue(rendered.startsWith(DrugReferenceInjector.QUERYSTORE_DRUG_ORDER_PREFIX),
				"querystore must still render a drug-order record under this prefix. Asserted "
						+ "against the CONSTANT, not a literal, so that the constant cannot drift away "
						+ "from the producer while a literal here keeps this case green. "
						+ "Rendered: " + rendered);
	}

	@Test
	public void theRawMarkersAreOnesTheMatcherAccepts() {
		// The raw constants and the match constants are INDEPENDENT literals, deliberately not
		// derived one from the other — and this case is the reason that matters, because it is the
		// case that stops working if they ever are. ". Stopped: ".toLowerCase() carries a trailing
		// space the match constant ". stopped:" does not, so deriving one from the other would make
		// this assertion x.contains(x): trivially true for every possible value, including a value
		// querystore never renders. It would keep passing while pinning nothing. Kept independent,
		// it is a real assertion that the raw form pinned above is one the matcher accepts, which is
		// what makes the two halves of this class one contract rather than two unrelated snapshots.
		assertTrue(DrugReferenceInjector.describesEndedOrder(
				DrugReferenceInjector.ORDER_STOPPED_MARKER.toLowerCase(Locale.ROOT)),
				"the raw stop marker must be one the matcher recognises");
		assertTrue(DrugReferenceInjector.describesEndedOrder(
				DrugReferenceInjector.ORDER_DISCONTINUED_MARKER.toLowerCase(Locale.ROOT)),
				"the raw discontinue marker must be one the matcher recognises");
	}
}
