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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue #293: an active drug order the clinician recorded as FREE TEXT is named by its concept
 * rather than by the text they typed.
 *
 * <p>{@code PatientClinicalContextBuilder.addDrugName} read exactly two sources — the coded
 * {@code Drug}'s name and the order concept's name — and {@code DrugOrder.getDrugNonCoded()} had no
 * production caller anywhere in the module. A non-coded order is not nameless, so it never reached
 * issue #290's code-only rung; it arrived carrying the wrong name.
 *
 * <p><b>What a non-coded order's concept actually is, read off the platform rather than assumed.</b>
 * {@code OrderServiceImpl.ensureConceptIsSet} assigns {@code OrderService.getNonCodedDrugConcept()} —
 * the concept named by the {@code drugOrder.drugOther} global property, which {@code OpenmrsConstants}
 * describes as "the concept which represents drug other non coded" — whenever a non-coded drug order
 * reaches {@code saveOrder} with no concept of its own, and {@code DrugOrderValidator} treats exactly
 * that concept as the non-coded shape. So the placeholder is the platform's, by construction. A client
 * MAY supply a concept instead and keep it: {@code validateForRequireDrug} only objects when the
 * {@code drugOrder.requireDrug} global property is true, and its default is false. Both shapes appear
 * below — the placeholder in the first three cases, a client-supplied concept in the last.
 *
 * <p>Context-sensitive and driven through the real
 * {@link PatientClinicalContextBuilder#build(Patient)} and then the real
 * {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)} or
 * {@link DrugReferenceInjector#injectRecords(PatientChart, PatientClinicalContext, String)}: the defect
 * is in what the builder puts into the context, so a hand-built {@code ActiveDrugOrder} would bypass the
 * whole change. Patient 7's single active drug order (order 111, drug "ASPIRIN", concept 88) is the
 * arrangement, made non-coded the way the platform records one — the coded drug cleared and
 * {@code drug_non_coded} carrying the clinician's text.
 */
public class NonCodedDrugOrderNameTest extends BaseModuleContextSensitiveTest {

	/** Concept 88 (ASPIRIN) — the concept behind patient 7's single active drug order, order 111. */
	private static final int ORDERED_CONCEPT = 88;

	/** What a non-coded order's concept is named when it is the platform's own {@code drugOrder.drugOther}
	 *  placeholder: a generic label, and not the drug. Measured through the production accessor
	 *  {@code DrugReferenceService.findImpliedByDrugName} over the shipped 2283-row knowledge base
	 *  ({@code DrugReferenceTestSupport.shippedEntries()}), eighteen placeholder spellings of this shape
	 *  — "Other", "Other non-coded", "Drug other non coded", "Unknown drug", "Medication" and the rest —
	 *  put ZERO reference entries in play. The name is inert, which is why keeping it beside the
	 *  clinician's text costs nothing; it is the ABSENCE of the drug's name that this issue is about. */
	private static final String PLACEHOLDER_CONCEPT_NAME = "Other non-coded drug";

	/** Naproxen's ATC code, carried by neither the curated seed nor the DDInter excerpt, so the class
	 *  arm reaches the rung where an order names its own partner. Shares subgroup {@code M01AE} with the
	 *  seed's ibuprofen entry ({@code M01AE01}). */
	private static final String NAPROXEN_ATC = "M01AE02";

	private static final String QUESTION = "Can I give ibuprofen?";

	private Patient patient;

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		patient = Context.getPatientService().getPatient(7);
	}

	/**
	 * Makes order 111 the shape a clinician's free-text prescription has: no coded {@code Drug}, and
	 * {@code drug_non_coded} carrying what they typed. The concept is untouched here — it is the second
	 * half of the arrangement and each case says which concept it wants.
	 */
	private void recordTheOrderAsFreeText(String typed) {
		Context.getAdministrationService().executeSQL("update drug_order set drug_inventory_id = null,"
				+ " drug_non_coded = '" + typed + "' where order_id = 111", false);
		Context.flushSession();
		Context.clearSession();
	}

	/**
	 * Renames the ordered concept's FULLY SPECIFIED name, which is what {@code Concept.getName()}
	 * yields here — concept 88 carries the FSN "ASPIRIN" and the synonym "ASA", and only the first is
	 * read by {@code addConceptName}. Scoped to that one row rather than applied to every name of the
	 * concept: renaming both makes them duplicates in one locale, and {@code ConceptValidator} then
	 * rejects the concept the moment anything saves it — which {@code mapConceptToAtc} does.
	 */
	private void nameTheConcept(String name) {
		Context.getAdministrationService().executeSQL("update concept_name set name = '" + name
				+ "' where concept_id = " + ORDERED_CONCEPT
				+ " and concept_name_type = 'FULLY_SPECIFIED'", false);
		Context.flushSession();
		Context.clearSession();
	}

	private static List<String> details(List<SafetyWarning> warnings) {
		List<String> out = new ArrayList<String>();
		for (SafetyWarning w : warnings) {
			out.add(w.getDetail());
		}
		return out;
	}

	private static List<String> activeOrderRecordTexts(PatientChart chart) {
		List<String> out = new ArrayList<String>();
		for (RecordMapping mapping : chart.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER.equals(mapping.getResourceType())) {
				out.add(mapping.getText());
			}
		}
		return out;
	}

	/**
	 * The screening consequence, and the one that withholds a real interaction: the drug asked about is
	 * screened against {@code PatientClinicalContext.hasActiveDrug}, which reads the order's names, and
	 * the clinician's text was not among them.
	 *
	 * <p>Warfarin x Ibuprofen is a <b>Major</b> row of the bundled 16-drug DDInter excerpt
	 * ({@code DrugReferenceTestSupport.ddinterEntries()}), read from the same dataset the validator under
	 * test is built over. Before this change the order carried only the placeholder concept name, so the
	 * rule's {@code warfarin} token matched nothing and the chip was withheld entirely — a Major
	 * interaction the module holds the data for, silently unreported because the drug was typed rather
	 * than picked.
	 */
	@Test
	public void theTextTheClinicianTypedIsWhatTheDrugInPlayIsScreenedAgainst() {
		nameTheConcept(PLACEHOLDER_CONCEPT_NAME);
		recordTheOrderAsFreeText("Warfarin");
		DrugSafetyValidator validator =
				DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService());

		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		List<SafetyWarning> warnings = validator.validate("", QUESTION, context);

		assertEquals(1, details(warnings).size(),
				"the free-text order must be screened like any other — before this change the only name"
						+ " it carried was its placeholder concept and the Major row was withheld, was: "
						+ details(warnings));
		assertTrue(details(warnings).get(0).startsWith("Ibuprofen interacts with active order warfarin"
				+ " — Major."),
				"and the interaction must be the one the dataset rates Major, was: " + details(warnings));
	}

	/**
	 * The prompt-facing consequence of the misnaming: {@code DrugReferenceInjector} renders an active
	 * order the chart cannot substantiate as a citable {@code active_drug_order} record (issue #118), and
	 * that record is the order's DISPLAY. So the placeholder did not merely label a chip — it reached the
	 * model as the name of a drug this patient is on, with nothing in front of it saying otherwise.
	 *
	 * <p>Reachable with no ATC mapping at all, which is why it rather than a chip is the primary pin for
	 * the naming half of this issue.
	 */
	@Test
	public void theInjectedOrderRecordNamesTheTextTheClinicianTypedAndNotItsPlaceholderConcept() {
		nameTheConcept(PLACEHOLDER_CONCEPT_NAME);
		recordTheOrderAsFreeText("Warfarin 5mg");
		DrugReferenceInjector injector =
				DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.ddinterService());

		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		PatientChart result = injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context,
				"what are her active medications?");

		assertEquals(java.util.Arrays.asList("Active drug order: Warfarin 5mg."),
				activeOrderRecordTexts(result),
				"the record the model reads must name the drug the clinician recorded, not the"
						+ " placeholder concept the platform files a free-text order under, was: "
						+ activeOrderRecordTexts(result));
	}

	/**
	 * The chip consequence, on the clinician-visible surface. The class arm labels a co-medication from
	 * the order's own display wherever the loaded dataset cannot name its codes
	 * ({@code OrderPartner.nameByOrder}), so the placeholder was printed to the clinician as the drug
	 * their patient is taking.
	 */
	@Test
	public void theClassChipNamesTheOrderByThatTextAndNotByItsPlaceholderConcept() {
		nameTheConcept(PLACEHOLDER_CONCEPT_NAME);
		DrugReferenceTestSupport.mapConceptToAtc(ORDERED_CONCEPT, NAPROXEN_ATC);
		recordTheOrderAsFreeText("Naproxen 500mg");
		DrugSafetyValidator validator =
				DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());

		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		List<SafetyWarning> warnings = validator.validate("", QUESTION, context);

		assertEquals(java.util.Arrays.asList("Ibuprofen is in the same ATC class (M01AE) as active order"
				+ " Naproxen 500mg — possible duplicate therapy"), details(warnings),
				"one chip for one prescription, naming it by the text the clinician typed, was: "
						+ details(warnings));
	}

	/**
	 * The property that makes this change ADDITIVE, pinned so a later change cannot narrow it silently.
	 *
	 * <p>The concept name is kept beside the clinician's text: it is what every match against this order
	 * rests on today, and dropping it would be a subtractive change with its own blast radius that this
	 * issue does not ask for. What the text buys is the DISPLAY and one more match token, never the loss
	 * of one.
	 *
	 * <p>Here the concept is a client-supplied one rather than the platform placeholder — the shape
	 * {@code validateForRequireDrug} permits under the default {@code drugOrder.requireDrug=false} — so
	 * that the two names are distinguishable and the ordering is visible: the clinician's text leads,
	 * because for a non-coded order the concept is by construction not the drug.
	 */
	@Test
	public void theClinicianSTextLeadsAndTheConceptNameSurvivesBesideIt() {
		recordTheOrderAsFreeText("Warfarin 5mg");

		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);

		assertEquals(1, context.getActiveDrugOrders().size(),
				"precondition: patient 7's one active drug order must reach the per-order list, was: "
						+ context.getActiveDrugOrders());
		PatientClinicalContext.ActiveDrugOrder order = context.getActiveDrugOrders().get(0);
		assertEquals("Warfarin 5mg", order.getDisplay(),
				"the display is the first name collected, and the text the clinician typed must lead it,"
						+ " was: " + order.getDisplay());
		assertTrue(order.getNames().contains("warfarin 5mg"),
				"the text must become a match token, was: " + order.getNames());
		assertTrue(order.getNames().contains("aspirin"),
				"and the concept name must survive beside it — this change adds a name, it never removes"
						+ " one, was: " + order.getNames());
		assertFalse(order.getNames().isEmpty(), "sanity: the order is not on the nameless rung");
	}
}
