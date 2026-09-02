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
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.classNoteIn;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.ctx;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.ddinterService;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.ddinterServiceWithGroups;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.injector;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.injectorWithSafety;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.injectedClassNotes;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.injectedReferences;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.oneRecordChart;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.serviceWith;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.set;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.shippedEntries;

import java.util.Arrays;
import java.util.List;
import java.util.Collections;

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

	/**
	 * The issue's headline case. The class term resolves to no substance, so nothing is injected
	 * today and the chart comes back unmodified; what must be there instead is a record naming the
	 * class the question used.
	 */
	@Test
	public void aQuestionNamingADrugClassTheReferenceDataCannotResolveInjectsANoteNamingTheClass() {
		PatientChart chart = inject(ddinterService(),
				"Can I start this patient on an oral contraceptive?");

		RecordMapping note = classNoteIn(chart);
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
		assertTrue(injectedClassNotes(chart).isEmpty(),
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
		assertTrue(injectedClassNotes(chart).isEmpty(),
				"a question the reference data resolved a substance for must raise no class note: "
						+ chart.getText());
	}

	/**
	 * Every question in the issue's own reproduction table, each on a service that can reach the
	 * term's source.
	 *
	 * <p>Its prose also names {@code the pill} as how the question gets asked, and that is a stated
	 * omission rather than an oversight: admission criterion (1) is that a term designates a CLASS,
	 * and {@code the pill} names any tablet at least as readily as it names a contraceptive, so
	 * reporting it as the class {@code oral contraceptive} would be the same false statement about
	 * the question that {@code hormonal contraceptive} was fixed for. The module stays silent on it,
	 * exactly as before.
	 */
	@Test
	public void everyQuestionTheIssuesReproductionTableListsIsRecognised() {
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
		RecordMapping note = classNoteIn(inject(service, question));
		assertTrue(note.getText().contains(named),
				"the note must name the class the service recognised (" + named + "): "
						+ note.getText());
		return named;
	}

	/**
	 * The class the note reports is the class the QUESTION named, never a wider or narrower one.
	 * Hormonal contraception is strictly wider than oral contraception — the shipped knowledge base
	 * files {@code Etonogestrel}, an implant moiety, and {@code Medroxyprogesterone acetate} — and
	 * route is exactly what the issue's nevirapine scenario turns on, so reporting a hormonal question
	 * as {@code oral contraceptive} would put a false statement about the question into a record the
	 * answer may cite. The value column of the term table exists for genuine SPELLINGS, not for
	 * collapsing two classes into one name.
	 */
	@Test
	public void theClassReportedIsTheClassTheQuestionNamedAndNotANeighbouringOne() {
		DrugReferenceService service = ddinterService();

		assertEquals("hormonal contraceptive",
				service.namedDrugClass("Can she use a hormonal contraceptive implant?"));
		assertEquals("oral contraceptive",
				service.namedDrugClass("Can I start this patient on an oral contraceptive?"));

		RecordMapping note = classNoteIn(inject(service, "Is a hormonal contraceptive safe for her?"));
		assertTrue(note.getText().contains("hormonal contraceptive"),
				"the note must name the class asked about: " + note.getText());
		assertFalse(note.getText().contains("\"oral contraceptive\""),
				"and must not report it as the narrower class: " + note.getText());

		// And the other direction, which the name promises and an earlier version did not assert: an
		// oral question must not be widened either.
		RecordMapping oral = classNoteIn(inject(service,
				"Can I start this patient on an oral contraceptive?"));
		assertTrue(oral.getText().contains("\"oral contraceptive\""),
				"the oral question must be reported as the oral class: " + oral.getText());
		assertFalse(oral.getText().contains("hormonal contraceptive"),
				"and must not be widened to the hormonal one: " + oral.getText());
	}

	/**
	 * A screening question that also names a class fires BOTH the note and the pairwise screening arm
	 * — they are gated on the same emptiness — so the note must not deny a screen. Reproduced on the
	 * real injector: the screen runs, relates two of the patient's own orders, and renders a finding
	 * the note would otherwise contradict one record later, inside citable reference prose.
	 */
	@Test
	public void theNoteDeniesNoScreenWhereTheScreeningArmRanBesideIt() {
		PatientClinicalContext context = ctx(60, null, set("warfarin 5mg", "ibuprofen 400mg"),
				set("B01AA03", "M01AE01"), null, null);
		PatientChart chart = injectorWithSafety(ddinterServiceWithGroups()).injectRecords(
				oneRecordChart(), context, "Do any of her medications interact with an NSAID?");

		assertFalse(DrugReferenceTestSupport.injectedFindings(chart).isEmpty(),
				"the premise: this question does run the screening arm and render its finding: "
						+ chart.getText());
		RecordMapping note = classNoteIn(chart);
		assertFalse(note.getText().contains("no interaction screen was run"),
				"the note must not deny a screen that ran one record earlier: " + note.getText());
		// The finding beside it IS reference material by referenceGroup, and its prose names the
		// class, so a note claiming the response carries none for the class is false here too. Both
		// wordings were written and both were measured against this arrangement.
		assertFalse(note.getText().contains("no reference material"),
				"nor may it deny reference material the response is carrying: " + note.getText());
		assertTrue(note.getText().contains("not resolved to any substance"),
				"it states what is true in every arrangement — the class resolved to nothing: "
						+ note.getText());
	}

	/**
	 * The class arm screens on a CLASS and names it, so the note may not say screening happens only
	 * against a named substance — nor that the interaction reference DATA has no class index, since
	 * the curated groups file is exactly that. Those were the third and fourth wider sentences
	 * written into this record and the third and fourth measured false; the case is here so a fifth
	 * is not written.
	 */
	@Test
	public void theNoteMakesNoClaimAboutHowTheModuleScreens() {
		PatientClinicalContext context = ctx(60, null, set("aspirin 81mg"), set("N02BA01"), null, null);
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(ddinterServiceWithGroups());
		List<SafetyWarning> chips = validator.validate("Ibuprofen would be an option.",
				"Can I give her an NSAID?", context);

		boolean screenedOnTheClass = false;
		for (SafetyWarning chip : chips) {
			screenedOnTheClass |= chip.getDetail() != null
					&& chip.getDetail().contains("cross-reactivity group (NSAID)");
		}
		assertTrue(screenedOnTheClass,
				"the premise: this response really does raise a chip that screened on the NSAID class "
						+ "and names it. Chips: " + chips);

		RecordMapping note = classNoteIn(inject(ddinterServiceWithGroups(),
				"Can I give her an NSAID?"));
		assertFalse(note.getText().contains("not a class"),
				"so the note must not say a screen runs only against a named substance: "
						+ note.getText());
		// Nor may it widen from the SCREEN to the DATA. cross-reactivity-groups.json is reference data
		// this module loads, keyed by ATC prefix under a class NAME, and in the shipped configuration
		// it is where the class name printed above was read from — so "the interaction reference data
		// is indexed by individual substance" is false one field away from the chip asserted here.
		// That was the fourth wider sentence written into this record and the fourth refuted.
		assertFalse(note.getText().contains("interaction reference data"),
				"nor may it claim the interaction reference data has no class index: " + note.getText());
		assertTrue(note.getText().contains("Reference entries are indexed by individual substance name"),
				"what survives is a claim about ENTRIES, which is what DrugReference is: "
						+ note.getText());
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
		RecordMapping note = classNoteIn(inject(ddinterService(),
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
		RecordMapping note = classNoteIn(chart);

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
		RecordMapping note = classNoteIn(chart);

		ChartSearchAiUtils.ReferenceSlice slice = ChartSearchAiUtils.referenceSlice(chart.getMappings());
		assertEquals(1, slice.getRecords(),
				"the class note is the only reference-group record this question produces");
		assertEquals(note.getText().length(), slice.getCharacters(),
				"and its characters are what the prompt spent on it");
	}

	/**
	 * The note carries a NEW resource type but the OLD prompt-facing lead, deliberately: the system
	 * prompt's record-type rule keys on the text a record begins with, and reusing that lead is what
	 * lets a further injected kind read under the right framing without touching a prompt span another
	 * test pins.
	 */
	@Test
	public void theNoteReadsUnderThePromptsReferenceMaterialRule() {
		RecordMapping note = classNoteIn(inject(ddinterService(),
				"Can I start this patient on an oral contraceptive?"));

		assertTrue(note.getText().startsWith(DrugReferenceInjector.REFERENCE_PREFIX),
				"the note must begin with the lead the system prompt names, was: " + note.getText());
		// The other half — that the lead and the prompt still name the SAME token — is asserted in
		// ReferenceRecordPromptLeadTest, which lives in the prompt's own package because
		// LlmProvider.DEFAULT_SYSTEM_PROMPT is package-private there and widening it for a test would
		// be a production change made for a test's convenience.

	}

	/**
	 * A class the CURATED cross-reactivity groups name is recognised from that data alone. Asserted on
	 * a class the code table does NOT carry, because a class both sources name cannot tell them apart:
	 * the shipped group is {@code NSAID}, which this module's own table also names, so a case built on
	 * it would pass with the groups leg deleted.
	 */
	@Test
	public void aClassOnlyTheCuratedGroupsNameIsStillRecognised() {
		assertEquals("Cephalosporin",
				serviceWithCuratedGroups(group("Cephalosporin"))
						.namedDrugClass("Can I give her a cephalosporin?"),
				"an operator's own group name must be a recognised class term");
		assertEquals(null, ddinterService().namedDrugClass("Can I give her a cephalosporin?"),
				"and with no curated groups loaded, nothing in code names that class");
	}

	/**
	 * The class the issue reports is named by BOTH sources, and it survives a deployment that curates
	 * its own groups file. Making it conditional on some loaded group publishing that exact name
	 * returned this case to pre-#354 silence — with nothing logged — on any install that sets
	 * {@code crossReactivityGroupsFilePath}, which is a supported configuration.
	 */
	@Test
	public void theClassTheIssueReportsSurvivesAnOperatorsOwnGroupsFile() {
		assertEquals("NSAID", ddinterServiceWithGroups().namedDrugClass("Can I give her an NSAID?"),
				"the premise: the shipped group names it");

		assertEquals("NSAID",
				serviceWithCuratedGroups(group("Cephalosporin")).namedDrugClass(
						"Can I give her an NSAID?"),
				"a groups file that does not name NSAID must not lose the class");
		assertEquals("NSAID", ddinterService().namedDrugClass("Any NSAIDs on her list?"),
				"and neither must a deployment with no curated groups at all");
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

	/**
	 * An operator-supplied group name is TRIMMED before it is matched. The loader rejects only a
	 * BLANK name, so a padded one loads and drives cross-reactivity and duplicate-therapy chips
	 * everywhere else — while the prose rule's left boundary makes it unmatchable here, so the class
	 * would go unrecognised with nothing logged.
	 */
	@Test
	public void aPaddedGroupNameStillNamesItsClass() {
		// On a class only the GROUPS name, for the reason the sibling case above gives: NSAID is named
		// by the code table too, so a fixture built on it answers whether or not the name was trimmed
		// — measured, the whole tree stays green with the trim reverted.
		assertEquals("Cephalosporin",
				serviceWithCuratedGroups(group("  Cephalosporin  "))
						.namedDrugClass("Can I give her a cephalosporin?"),
				"a padded curated group name must still be recognised, and reported trimmed");
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
	 * Criterion (2) of what may be admitted to the code table, asked of the shipped knowledge base
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
