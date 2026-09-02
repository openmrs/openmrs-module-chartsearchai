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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.activeOrder;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.ctx;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.ddinterService;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.ddinterServiceWithGroups;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.injector;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.injectedReferences;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.oneRecordChart;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.serviceWith;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.set;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.shippedEntries;

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
 * A question that names a drug CLASS rather than a substance — issue #354.
 *
 * <p>Question-driven resolution is substance-name matching end to end, so {@code an oral
 * contraceptive} and {@code an NSAID} resolve to nothing: no reference record is injected, no arm
 * has a subject, and the module falls SILENT — indistinguishable, to a reader of the prompt, from a
 * question that is not about drugs at all. The answer then states that the records do not address
 * the question, while the knowledge base holds a rated pair against the patient's own order for
 * every member of the class.
 *
 * <p>What this pins is that the module stops being silent and says what it did not do. It does NOT
 * pin a resolved class — the issue names that as one of two outcomes and this change takes the
 * other, for the reason recorded on {@link DrugReferenceService#namedDrugClass}: the ATC hierarchy
 * does not express these classes, and no membership list this module could author would be sound.
 *
 * <p>So every case here asserts the deterministic half — the record the injector appends. Whether
 * an ANSWER relays it is the model's, and nothing in this module can assert it.
 */
public class DrugClassQuestionNoteTest {

	/** The patient of the issue's reproduction, reduced to what the excerpt can express: one active
	 *  order, so the response has something a screen would have been run against. */
	private static PatientClinicalContext oneActiveOrder() {
		return ctx(34, null, set("warfarin 5mg"), set("B01AA03"), null, null);
	}

	private static PatientChart inject(DrugReferenceService service, String question) {
		return injector(service).injectRecords(oneRecordChart(), oneActiveOrder(), question);
	}

	private static List<RecordMapping> classNotes(PatientChart chart) {
		List<RecordMapping> out = new ArrayList<RecordMapping>();
		for (RecordMapping mapping : chart.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_DRUG_CLASS_NOTE.equals(mapping.getResourceType())) {
				out.add(mapping);
			}
		}
		return out;
	}

	private static RecordMapping theClassNote(PatientChart chart) {
		List<RecordMapping> notes = classNotes(chart);
		assertEquals(1, notes.size(),
				"exactly one drug-class note was expected in: " + chart.getText());
		return notes.get(0);
	}

	/**
	 * The issue's headline case. The class term resolves to no substance, so nothing is injected
	 * today and the chart comes back unmodified; what must be there instead is a record naming the
	 * class the question used.
	 */
	@Test
	public void aQuestionNamingADrugClassTheReferenceDataCannotResolveInjectsANoteNamingTheClass() {
		PatientChart chart = inject(ddinterService(),
				"Can I start this patient on an oral contraceptive?");

		RecordMapping note = theClassNote(chart);
		assertTrue(note.getText().contains("oral contraceptive"),
				"the note must name the class the question used, was: " + note.getText());
		assertTrue(chart.getText().contains(note.getText()),
				"the note must be in the chart the model reads, not only in the mappings: "
						+ chart.getText());
		assertTrue(injectedReferences(chart).isEmpty(),
				"and no drug-reference entry is invented for a class: " + chart.getText());
	}

	/**
	 * The issue's control, on the excerpt's own substances: a question naming a DRUG still resolves
	 * one, and says nothing about classes. Both halves matter — a note raised beside a resolved
	 * substance would be noise on every question the module already answers.
	 */
	@Test
	public void aQuestionNamingASubstanceInjectsNoClassNote() {
		PatientChart chart = inject(ddinterService(), "Can I start this patient on ibuprofen?");

		assertFalse(injectedReferences(chart).isEmpty(),
				"the substance control must still inject its reference record: " + chart.getText());
		assertTrue(classNotes(chart).isEmpty(),
				"a question the reference data resolves must raise no class note: " + chart.getText());
	}

	/**
	 * The gate is that the question resolved NO substance, and this is the only arrangement that can
	 * tell it from "the question named no class": one question naming both a class term and a drug
	 * the dataset carries. The module screened the drug, so a record announcing that no screen was
	 * run would describe the response wrongly — and the class term is still there, so a gate keyed on
	 * the term alone would raise one.
	 */
	@Test
	public void aQuestionNamingAClassAndAResolvableDrugRaisesNoNote() {
		DrugReferenceService service = ddinterServiceWithGroups();
		assertEquals("NSAID", service.namedDrugClass("Can I add ibuprofen, or another NSAID?"),
				"the premise: the class term is in this question and is recognised");

		PatientChart chart = inject(service, "Can I add ibuprofen, or another NSAID?");

		assertFalse(injectedReferences(chart).isEmpty(),
				"the premise: the drug in that same question does resolve: " + chart.getText());
		assertTrue(classNotes(chart).isEmpty(),
				"a question the reference data resolved a substance for must raise no class note: "
						+ chart.getText());
	}

	/** Every phrasing the issue reports, each on a service that can reach the term's source. */
	@Test
	public void everyPhrasingTheIssueReportsIsRecognised() {
		assertEquals("oral contraceptive", theClassNoteClass(ddinterService(),
				"Can I start this patient on an oral contraceptive?"));
		assertEquals("oral contraceptive", theClassNoteClass(ddinterService(),
				"Can I start this patient on a combined oral contraceptive pill?"));
		assertEquals("NSAID", theClassNoteClass(ddinterServiceWithGroups(),
				"Can I start this patient on an NSAID?"));
	}

	private static String theClassNoteClass(DrugReferenceService service, String question) {
		String named = service.namedDrugClass(question);
		assertNotNull(named, "no drug class was recognised in: " + question);
		RecordMapping note = theClassNote(inject(service, question));
		assertTrue(note.getText().contains(named),
				"the note must name the class the service recognised (" + named + "): "
						+ note.getText());
		return named;
	}

	/**
	 * The false-claim guard, and the reason the note lists no members: a record the answer may cite
	 * verbatim must not name a drug. Put back through the production accessor over the SHIPPED
	 * knowledge base, because a note naming the issue's own {@code Levonorgestrel} would pass
	 * unnoticed against the 16-substance excerpt.
	 */
	@Test
	public void theNoteNamesNoSubstanceTheReferenceDataCarries() {
		DrugReferenceService shipped = serviceWith(shippedEntries());
		RecordMapping note = theClassNote(inject(ddinterService(),
				"Can I start this patient on an oral contraceptive?"));

		assertTrue(shipped.findImpliedByQuery(note.getText()).isEmpty(),
				"the class note must name no substance of the shipped knowledge base, was: "
						+ note.getText());
	}

	/**
	 * Where the note appears among the other injected records. It is appended LAST — after the
	 * patient's own active-order records — so no citation index an existing record holds moves, and
	 * the reader reaches what the response could not screen after everything it did.
	 *
	 * <p>Asserted on a chart that carries both, because the note's own cases carry only the note:
	 * with no substance resolved there is no {@code drug_reference} record to sit beside, but an
	 * active order the retrieved chart does not substantiate still produces one (issue #118).
	 */
	@Test
	public void theNoteIsAppendedAfterTheRecordsTheResponseDidProduce() {
		PatientClinicalContext context = ctx(34, null, set("warfarin 5mg"), set("B01AA03"), null, null,
				Collections.singletonList(activeOrder("order-warfarin", "Warfarin 5mg")));
		PatientChart chart = injector(ddinterService()).injectRecords(oneRecordChart(), context,
				"Can I start this patient on an oral contraceptive?");

		RecordMapping order = chart.getMappings().stream()
				.filter(m -> ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER
						.equals(m.getResourceType()))
				.findFirst().orElseThrow(() -> new IllegalStateException(
						"the premise: this chart must carry an unsubstantiated active order: "
								+ chart.getText()));
		RecordMapping note = theClassNote(chart);

		assertEquals(2, order.getIndex(), "the chart's own record is [1], so the order is [2]");
		assertEquals(3, note.getIndex(), "and the note follows it, taking the next index");
		assertTrue(chart.getText().indexOf(order.getText()) < chart.getText().indexOf(note.getText()),
				"and reads after it in the chart the model sees: " + chart.getText());
	}

	/**
	 * The note is module-supplied reference material, so the prompt-cost slice the audit row carries
	 * must count it — the property issue #229 built that measurement for, and the one a third
	 * injected kind is most likely to be omitted from.
	 */
	@Test
	public void aClassNoteIsCountedIntoThePromptCostSlice() {
		PatientChart chart = inject(ddinterService(),
				"Can I start this patient on an oral contraceptive?");
		RecordMapping note = theClassNote(chart);

		ChartSearchAiUtils.ReferenceSlice slice = ChartSearchAiUtils.referenceSlice(chart.getMappings());
		assertEquals(1, slice.getRecords(),
				"the class note is the only reference-group record this question produces");
		assertEquals(note.getText().length(), slice.getCharacters(),
				"and its characters are what the prompt spent on it");
	}

	/**
	 * The note carries a NEW resource type but the OLD prompt-facing lead, deliberately: the system
	 * prompt's record-type rule keys on the text a record begins with, and reusing that lead is what
	 * lets a third injected kind read under the right framing without touching a prompt span another
	 * test pins.
	 */
	@Test
	public void theNoteReadsUnderThePromptsReferenceMaterialRule() {
		RecordMapping note = theClassNote(inject(ddinterService(),
				"Can I start this patient on an oral contraceptive?"));

		assertTrue(note.getText().startsWith(DrugReferenceInjector.REFERENCE_PREFIX),
				"the note must begin with the lead the system prompt names, was: " + note.getText());
	}

	/**
	 * A class the CURATED cross-reactivity groups name is recognised from that data rather than from
	 * any table in this module's code — the property that keeps one registry of class names. The
	 * groups are pinned empty by the {@code setEntries} seam, so the same question raises nothing on
	 * a service built without them, and that difference IS the assertion.
	 */
	@Test
	public void aClassTheCuratedGroupsNameIsRecognisedFromThatDataAndNotFromCode() {
		assertEquals("NSAID", ddinterServiceWithGroups().namedDrugClass("Can I give her an NSAID?"),
				"the shipped curated group's own name must be a recognised class term");
		assertEquals(null, ddinterService().namedDrugClass("Can I give her an NSAID?"),
				"and with no curated groups loaded there is no code table naming that class");
	}

	/**
	 * The rule that keeps the answer independent of the order an operator listed their groups in: the
	 * LONGEST term a question carries decides, not the first source consulted. Asserted from both
	 * file orders, because a first-match rule passes one of them by luck.
	 */
	@Test
	public void theLongestClassTermAQuestionCarriesDecidesWhicheverOrderTheGroupsAreListedIn() {
		CrossReactivityGroup shorter = group("Sartan");
		CrossReactivityGroup longer = group("Angiotensin receptor blocker sartan");
		String question = "Can I add an angiotensin receptor blocker sartan?";

		assertEquals("Angiotensin receptor blocker sartan",
				serviceWithCuratedGroups(shorter, longer).namedDrugClass(question));
		assertEquals("Angiotensin receptor blocker sartan",
				serviceWithCuratedGroups(longer, shorter).namedDrugClass(question));
	}

	private static CrossReactivityGroup group(String name) {
		CrossReactivityGroup group = new CrossReactivityGroup();
		group.setName(name);
		group.setAtcPrefixes(Arrays.asList("C09CA"));
		return group;
	}

	/** A service over the excerpt carrying exactly {@code groups} — the real production pairing, in
	 *  the order {@code DrugReferenceTestSupport.withEntriesAndGroups} records as load-bearing. */
	private static DrugReferenceService serviceWithCuratedGroups(CrossReactivityGroup... groups) {
		DrugReferenceService service = ddinterService();
		service.setCrossReactivityGroups(Arrays.asList(groups));
		return service;
	}

	/**
	 * Criterion (b) of what may be admitted to the code table, asked of the shipped knowledge base
	 * through the production accessor: a term that resolved to a substance would make the module
	 * call a drug a class. It reaches the code table only — a group name comes from operator data
	 * this test cannot speak for.
	 */
	@Test
	public void everyCuratedClassTermResolvesToNoSubstanceInTheShippedKnowledgeBase() {
		DrugReferenceService shipped = serviceWith(shippedEntries());

		for (String term : DrugClassTerms.terms()) {
			assertTrue(shipped.findImpliedByQuery(term).isEmpty(),
					"the curated class term \"" + term + "\" names a substance of the shipped "
							+ "knowledge base, so it is a drug name and not a class term");
		}
	}
}
