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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openmrs.DrugOrder;
import org.openmrs.api.context.Context;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.serialization.DrugOrderRecordSerializer;
import org.openmrs.module.querystore.util.ConceptNameUtil;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Contract pin for the measurement issue #347's fix rests on: a {@code drug_order} chart record
 * carries exactly ONE name for the prescription it describes.
 *
 * <p><b>Why it matters here.</b> {@code DrugSafetyValidator.displaysANameOfAny} silences the
 * chart-order bridge clause where the order's own DISPLAYED name reaches the substance a chip names.
 * Until #347 the test was every name the order RECORDS, and
 * {@code PatientClinicalContextBuilder.addDrugName} records the order's CONCEPT name beside its
 * drug-row name — so an order displayed {@code Advil 400mg} on concept {@code Ibuprofen} silenced
 * the clause through {@code Ibuprofen}, a name that reaches no prompt text at all. That is only a
 * defect if the concept name really is invisible to the model, which is a claim about ANOTHER
 * module's rendering; so it is asserted here against the output of querystore's REAL
 * {@link DrugOrderRecordSerializer}, driven through its public {@code serialize} entry point on a
 * real {@link DrugOrder} from the standard test dataset. No hand-typed imitation of the format
 * appears here: if querystore starts rendering both names, this test fails and names what broke — at
 * which point #347's shape no longer exists and the silence test can widen back.
 *
 * <p><b>What it does not claim.</b> That the module's own {@code ActiveDrugOrder.getDisplay()} is
 * always the string rendered below. It is wherever the order has a drug row with a name, which is
 * this fixture's shape and the ticket's; the one divergence — an order with no drug-row name and
 * free text written in its place, where the module displays the free text and querystore renders the
 * concept's preferred name — is recorded as a residue on {@code displaysANameOfAny} and is not
 * pinned anywhere. The last case below is why that residue exists rather than being a second
 * rendering rule to mirror.
 *
 * <p>The order is mutated in memory and never saved, exactly as
 * {@link QuerystoreOrderTextMarkerTest} does for the end-marker states.
 */
public class QuerystoreDrugOrderDisplayedNameTest extends BaseModuleContextSensitiveTest {

	/** Order 3 of the standard test dataset: a real, fully-populated Triomune-30 DrugOrder. */
	private static final int TRIOMUNE_ORDER_ID = 3;

	/** A brand no concept in the dictionary carries, standing for the ticket's {@code Advil 400mg}. */
	private static final String BRAND = "Advilbrand 400mg";

	private DrugOrder triomuneOrder() {
		return (DrugOrder) Context.getOrderService().getOrder(TRIOMUNE_ORDER_ID);
	}

	/** The real querystore serializer's rendered text for {@code order}. */
	private String renderedText(DrugOrder order) {
		QueryDocument doc = new DrugOrderRecordSerializer().serialize(order);
		return doc.getText() == null ? "" : doc.getText();
	}

	/** querystore's own answer for what the order's concept is called, read through the util its
	 *  serializer reads it through rather than through {@code Concept.getName()}, which resolves
	 *  locales differently. */
	private String conceptPreferredName(DrugOrder order) {
		return ConceptNameUtil.getPreferredName(order.getConcept());
	}

	@Test
	public void whereTheOrderHasADrugRowNameThatIsTheOneNameTheRecordCarries() {
		DrugOrder order = triomuneOrder();
		assertNotNull(order.getDrug(), "precondition: this fixture order has a drug row");
		order.getDrug().setName(BRAND);
		String conceptName = conceptPreferredName(order);
		assertNotEquals(BRAND, conceptName,
				"precondition: the brand and the concept name must differ, or this case asserts nothing");

		String text = renderedText(order);

		assertTrue(text.startsWith("Drug order: " + BRAND),
				"querystore renders the drug row's name as the record's subject. Rendered: " + text);
		assertFalse(text.toLowerCase(Locale.ROOT).contains(conceptName.toLowerCase(Locale.ROOT)),
				"and NOT the concept's name beside it — which is why a concept name in the module's "
						+ "recorded-name set is invisible to the model, and why the bridge's silence "
						+ "test is the display (issue #347). Concept name '" + conceptName
						+ "' in rendered: " + text);
	}

	@Test
	public void withNoDrugRowNameTheConceptsPreferredNameIsTheOneTheRecordCarries() {
		// The other half of querystore's choice: it falls back to the concept, so "one name" is not
		// "the drug row's name" — it is whichever of the two the serializer picked. A silence test
		// keyed on the UNION of both would therefore be satisfied by a name the record did not render,
		// which is the defect, and one keyed on the drug row alone would miss this shape entirely.
		DrugOrder order = triomuneOrder();
		order.getDrug().setName(null);
		String conceptName = conceptPreferredName(order);

		String text = renderedText(order);

		assertTrue(text.startsWith("Drug order: " + conceptName),
				"with no drug-row name the concept's preferred name is the subject. Rendered: " + text);
	}

	@Test
	public void theFreeTextADrugOrderMayCarryIsNotRenderedAtAll() {
		// The residue on displaysANameOfAny is exactly this: drugNonCoded is a name the MODULE records
		// and can display, and querystore renders none of it. Pinned so that the residue is a measured
		// property of querystore's format rather than an assumption in a javadoc.
		DrugOrder order = triomuneOrder();
		order.setDrugNonCoded("clinic-supplied warfarin tablets");

		String text = renderedText(order);

		assertFalse(text.contains("clinic-supplied"),
				"querystore renders no free-text drug name, so an order displayed by its free text is "
						+ "displayed by a string its own record does not show. Rendered: " + text);
	}
}
