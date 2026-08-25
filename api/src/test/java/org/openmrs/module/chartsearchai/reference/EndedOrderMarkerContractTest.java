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
 * The DATA half of issue #315: that the two ended-order markers
 * {@code LlmProvider.DEFAULT_SYSTEM_PROMPT} shows the model are verbatim in what querystore really
 * renders, and are recognised by the module's own matcher.
 *
 * <p>Split from {@code EndedOrderAnswerRuleTest}, which holds the PROMPT half, because
 * {@link DrugReferenceInjector#describesEndedOrder} and {@code DEFAULT_SYSTEM_PROMPT} are
 * package-private in different packages. Neither is widened for a test: the two halves are joined by
 * the PUBLIC constants {@link DrugReferenceInjector#ORDER_STOPPED_MARKER} and
 * {@link DrugReferenceInjector#ORDER_DISCONTINUED_MARKER}, which both classes assert against — the
 * same way {@code LlmProviderTest} already couples the prompt's format demonstration to
 * {@code DrugReferenceInjector.FINDING_PREFIX}.
 *
 * <p><b>Why the serializer is in the loop.</b> An assertion that only reads a constant back out of
 * the prompt that concatenated it is true by construction and pins nothing — it cannot tell a cue
 * the chart carries from one it does not. So the load-bearing operand comes from OUTSIDE this
 * module: querystore's real {@link DrugOrderRecordSerializer}, driven through its public
 * {@code AbstractRecordSerializer.serialize} on a real {@link DrugOrder} from the standard test
 * dataset, exactly as {@link QuerystoreOrderTextMarkerTest} does for the #118 reconciliation.
 *
 * <p><b>The text is read RAW here, not lowercased.</b> {@link QuerystoreOrderTextMarkerTest}
 * lowercases before matching because the predicate it pins is defined on lowercased text. The
 * constants asserted here make a CASING claim the lowercase match constants never made — the prompt
 * shows the model {@code ". Stopped: "}, not {@code ". stopped:"} — so lowercasing first would leave
 * the one new data claim this change introduces unpinned.
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
	public void theStopMarkerShownToTheModelIsVerbatimInWhatQuerystoreRenders() {
		// discontinueOrder stamps dateStopped on the ORIGINAL order; dateStopped has no public
		// setter (core forces this route), so this is the only way to reach the rendered stop
		// marker through real production code rather than a literal typed here.
		DrugOrder original = triomuneOrder();
		Context.getOrderService().discontinueOrder(original, "renewed at a higher dose", null,
				original.getOrderer(), original.getEncounter());

		String rendered = renderedText((DrugOrder) Context.getOrderService().getOrder(TRIOMUNE_ORDER_ID));

		assertTrue(rendered.contains(DrugReferenceInjector.ORDER_STOPPED_MARKER),
				"the stop marker the prompt shows the model must be verbatim — casing and spacing "
						+ "included — in what querystore actually renders, or the prompt teaches a "
						+ "cue no chart record carries. Rendered: " + rendered);
		assertTrue(DrugReferenceInjector.describesEndedOrder(rendered.toLowerCase(Locale.ROOT)),
				"and the module's own matcher must recognise that same rendered text, so the #315 "
						+ "answer rule and the #118 reconciliation cannot come to disagree about "
						+ "which records are ended. Rendered: " + rendered);
	}

	@Test
	public void theDiscontinueMarkerShownToTheModelIsVerbatimInWhatQuerystoreRenders() {
		DrugOrder order = triomuneOrder();
		order.setAction(Order.Action.DISCONTINUE);

		String rendered = renderedText(order);

		assertTrue(rendered.contains(DrugReferenceInjector.ORDER_DISCONTINUED_MARKER),
				"the discontinue marker the prompt shows must be verbatim in querystore's output. "
						+ "Rendered: " + rendered);
		assertTrue(DrugReferenceInjector.describesEndedOrder(rendered.toLowerCase(Locale.ROOT)),
				"and the matcher must recognise it. Rendered: " + rendered);
	}

	@Test
	public void aDiscontinuedOrderCarriesNoStopDate_soTheRuleMayNotDemandOne() {
		// The reason the prompt asks for the stop date "when the record carries one" rather than
		// demanding one. querystore appends ". Stopped: " only for a non-null dateStopped and
		// ". Action: " unconditionally, so this record reads as ended and has no date to give. A
		// rule demanding a date here would be unsatisfiable, and the model would have to invent it.
		DrugOrder order = triomuneOrder();
		order.setAction(Order.Action.DISCONTINUE);

		String rendered = renderedText(order);

		assertFalse(rendered.contains(DrugReferenceInjector.ORDER_STOPPED_MARKER),
				"precondition: this ended record carries no stop date, which is what the rule's "
						+ "conditional date clause exists for. Rendered: " + rendered);
		assertTrue(DrugReferenceInjector.describesEndedOrder(rendered.toLowerCase(Locale.ROOT)),
				"and it is still an ended order despite carrying no date");
	}

	@Test
	public void theRecordPrefixTheClauseIdentifiesTheClassBySurvivesToo() {
		// The clause names THREE cues, and this is the third: it identifies the record class by the
		// text beginning "Drug order:" before it looks for either end marker. The two markers are
		// shared constants pinned above; this one is a literal in the prompt, so without this case a
		// querystore rename of the record prefix leaves every test green while the prompt teaches a
		// class no record belongs to — the exact failure the constant-sharing exists to prevent.
		//
		// The conjunct is also a GUARD, not redundant labelling, which is the reason not to "simplify"
		// it away: querystore's AbstractServiceOrderRecordSerializer emits the SAME ". Stopped: " and
		// ". Action: " markers, and it backs "Referral order:" and "Test order:". Without the prefix
		// cue the clause would have the model report an ended lab test as an ended prescription.
		String rendered = renderedText(triomuneOrder());

		assertTrue(rendered.startsWith(DrugReferenceInjector.QUERYSTORE_DRUG_ORDER_PREFIX),
				"the prompt identifies a drug-order record by this prefix; querystore must still "
						+ "render it. Asserted against the CONSTANT, not a literal: a literal here "
						+ "leaves the constant free to drift away from querystore while the prompt "
						+ "faithfully teaches the drifted value, which is green on both sides. "
						+ "Rendered: " + rendered);
	}

	@Test
	public void theMarkersShownToTheModelAreTheOnesTheMatcherKeysOn() {
		// The display constants and the match constants are INDEPENDENT literals, deliberately not
		// derived one from the other — and this case is the reason that matters, because it is the
		// case that stops working if they ever are. ". Stopped: ".toLowerCase() carries a trailing
		// space the match constant ". stopped:" does not, so deriving one from the other would make
		// this assertion x.contains(x): trivially true for every possible value, including a value
		// querystore never renders. It would keep passing while pinning nothing. Kept independent,
		// it is a real assertion that the cue shown to the model is one the matcher accepts.
		assertTrue(DrugReferenceInjector.describesEndedOrder(
				DrugReferenceInjector.ORDER_STOPPED_MARKER.toLowerCase(Locale.ROOT)),
				"the stop marker shown to the model must be one the matcher recognises");
		assertTrue(DrugReferenceInjector.describesEndedOrder(
				DrugReferenceInjector.ORDER_DISCONTINUED_MARKER.toLowerCase(Locale.ROOT)),
				"the discontinue marker shown to the model must be one the matcher recognises");
	}
}
