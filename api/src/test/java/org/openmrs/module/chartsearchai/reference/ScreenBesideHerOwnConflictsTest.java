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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A screen that related no pair, beside her own records conflicting with her orders, is answered by the module (ADR
 * Decision 236). On the 3.7.1 standalone Kevin Brown, on clarithromycin alone and recorded allergic to it, asked "Does
 * he have any drug interactions?", was answered by the model "No — Clarithromycin should not be given": a drug he takes,
 * refused as though proposed. Richard Jones, on dexamethasone and topical hydrocortisone and allergic to dexamethasone,
 * got "Dexamethasone is related to a recorded allergy, a reason to change a medication this patient is already taking".
 * Each screen's own answer needed no finding beyond the pair it did not find, so none was composed. A safety question
 * is answered by the module now; one asking only about interactions keeps the model's answer, which states the conflict.
 */
public class ScreenBesideHerOwnConflictsTest extends BaseModuleContextSensitiveTest {

	/** What the screen of dexamethasone and prednisone found: DDInter rates the pair Unknown, below the floor, so the screen
	 *  counts it rather than saying no interactions were found (ADR Decision 467). */
	private static final String DEXAMETHASONE_PREDNISONE_SCREEN = "No interaction at or above the configured severity level "
			+ "was found among the 2 substances in this patient's active medications; the data lists one pair of them only "
			+ "below that level, with no severity or mechanism on file. Drug-class relationships were not checked. [";

	/** The bottom line over that screen (ADR Decision 512): his two conflicts with his own orders, counted. */
	private static final String BOTTOM_LINE = "No rated interactions; 2 contraindications.";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
	}

	@Test
	public void aSafetyScreenStatesHerOwnConflictsAfterWhatTheScreenFound() {
		String answer = screen("Are his medications safe to take together?", "dexamethasone",
			DrugReferenceTestSupport.activeOrder("order-dex", "Dexamethasone", "Dexamethasone"),
			DrugReferenceTestSupport.activeOrder("order-pred", "Prednisone", "Prednisone"));

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		// No count introduces them (ADR Decision 506): each line says for itself that its drug is one he already takes.
		assertEquals(5, lines.length, "was: " + answer);
		assertEquals(BOTTOM_LINE, lines[0], "the bottom line (ADR Decision 512): " + answer);
		assertEquals(true, lines[1].startsWith(DEXAMETHASONE_PREDNISONE_SCREEN),
			"was: " + answer);
		assertEquals(true, lines[2].startsWith("The patient has a recorded allergy to Dexamethasone. This finding is about a medication this patient is already taking. ["),
			"was: " + answer);
		assertEquals(true, lines[3].startsWith("Prednisone is in the same ATC class (H02AB) as the patient's allergy to "
				+ "Dexamethasone — possible cross-reactivity.") && lines[3].contains(" This finding is about a medication this patient is already taking. ["), "was: " + answer);
		assertEquals(DrugReferenceInjector.SCREEN_SCOPE, lines[4], "was: " + answer);
	}

	/** A screen asked in words of no medication cue — "his treatment" — states his own conflicts as any screen does (ADR
	 *  Decision 417): on the 3.7.1 standalone "Are there any issues with her treatment?" left out Agnes Adams's NSAID allergy
	 *  beside her aspirin order, which "Are her current medications ok?" stated. */
	@Test
	public void aScreenInWordsOfNoMedicationCueStatesHisOwnConflictsToo() {
		String answer = screen("Are there any issues with his treatment?", "dexamethasone",
			DrugReferenceTestSupport.activeOrder("order-dex", "Dexamethasone", "Dexamethasone"),
			DrugReferenceTestSupport.activeOrder("order-pred", "Prednisone", "Prednisone"));
		assertEquals(screen("Are his current medications ok?", "dexamethasone",
			DrugReferenceTestSupport.activeOrder("order-dex", "Dexamethasone", "Dexamethasone"),
			DrugReferenceTestSupport.activeOrder("order-pred", "Prednisone", "Prednisone")), answer);
		assertEquals(true, answer != null && answer.contains("The patient has a recorded allergy to Dexamethasone."),
			"was: " + answer);
	}

	/** A question asking the module to choose a drug for him states his own conflicts too, though it names no medication
	 *  word the router's medication cues know (ADR Decision 293): on the 3.7.1 standalone "What painkiller can I safely
	 *  give her?" left out the ten allergy findings about Sarah Taylor's own corticosteroids that "Which pain medication is
	 *  safe for her?" stated. */
	@Test
	public void aChoiceOfDrugStatesHisOwnConflictsWhateverWordNamesTheDrugs() {
		for (String question : new String[] { "What painkiller can I safely give him?", "What can I give him for pain?",
				"Which antibiotic is safest for him?" }) {
			String answer = screen(question, "dexamethasone",
				DrugReferenceTestSupport.activeOrder("order-dex", "Dexamethasone", "Dexamethasone"),
				DrugReferenceTestSupport.activeOrder("order-pred", "Prednisone", "Prednisone"));

			assertNotNull(answer, question + " — the module answers");
			String[] lines = answer.split("\n");
			// The lead's limit folded into the one sentence in view, the lead whole under it (ADR Decision 517).
			assertEquals("No rated interactions; 2 contraindications (this check does not choose a medication to give this "
					+ "patient, and states none as safe).", lines[0], question + " — was: " + answer);
			assertEquals(DrugReferenceInjector.CHOOSES_NO_DRUG_LEAD, lines[1], question + " — was: " + answer);
			assertEquals(true, lines[2].startsWith(DEXAMETHASONE_PREDNISONE_SCREEN),
				question + " — was: " + answer);
			assertEquals(true, lines[3].startsWith("The patient has a recorded allergy to Dexamethasone. This finding is about a medication this patient is already taking. ["),
				question + " — was: " + answer);
		}
	}

	/**
	 * A question asking only about interactions states what the screen found, then his own conflicts under their own lead
	 * (ADR Decision 303, superseding Decision 236's hand-off to the model): on the 3.7.1 standalone the model answered
	 * Dorothy Odinga's "Any interactions with her current medications?" with "No — Aspirine Co 81mg should be changed".
	 */
	@Test
	public void aScreenAskingOnlyAboutInteractionsStatesHisOwnConflictsApart() {
		for (String question : Arrays.asList("Does he have any drug interactions?",
				"Any interactions with his current medications?")) {
			String answer = screen(question, "dexamethasone",
				DrugReferenceTestSupport.activeOrder("order-dex", "Dexamethasone", "Dexamethasone"),
				DrugReferenceTestSupport.activeOrder("order-pred", "Prednisone", "Prednisone"));
			assertNotNull(answer, question + " — the module answers");
			String[] lines = answer.split("\n");
			assertEquals(true, lines[1].startsWith(DEXAMETHASONE_PREDNISONE_SCREEN),
				question + " — was: " + answer);
			assertEquals(DrugReferenceInjector.BEYOND_INTERACTIONS_LEAD, lines[2], question + " — was: " + answer);
			assertEquals(true, lines[3].startsWith("The patient has a recorded allergy to Dexamethasone."),
				question + " — was: " + answer);
		}
		String one = screen("Any interactions with his current medications?", "clarithromycin",
			DrugReferenceTestSupport.activeOrder("order-clari", "Clarithromycin 500mg", "Clarithromycin"));
		assertNotNull(one, "the module answers a screen of one order too");
		String[] lines = one.split("\n");
		assertEquals("This patient has one active medication order, Clarithromycin 500mg, so there is no other active "
				+ "medication to check it against.", lines[0], "was: " + one);
		assertEquals(DrugReferenceInjector.BEYOND_INTERACTIONS_LEAD, lines[1], "was: " + one);
		assertEquals(true, lines[2].startsWith("The patient has a recorded allergy to Clarithromycin."), "was: " + one);
	}

	/** With the interaction checks off no screen ran, so the module states none. */
	@Test
	public void aScreenWithTheInteractionChecksOffKeepsTheModelCall() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_INTERACTIONS,
			"false");
		assertNull(screen("Are his medications safe to take together?", "dexamethasone",
			DrugReferenceTestSupport.activeOrder("order-dex", "Dexamethasone", "Dexamethasone"),
			DrugReferenceTestSupport.activeOrder("order-pred", "Prednisone", "Prednisone")));
	}

	@Test
	public void aScreenOfOneOrderHerRecordsConflictWithSaysSo() {
		String answer = screen("Are his medications safe?", "clarithromycin",
			DrugReferenceTestSupport.activeOrder("order-clari", "Clarithromycin 500mg", "Clarithromycin"));

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("This patient has one active medication order, Clarithromycin 500mg, so there is no other active "
				+ "medication to check it against.", lines[0], "was: " + answer);
		assertEquals(true, lines[1].startsWith("The patient has a recorded allergy to Clarithromycin. This finding is about a medication this patient is already taking. ["),
			"was: " + answer);
		assertEquals(DrugReferenceInjector.SCREEN_SCOPE, lines[lines.length - 1], "was: " + answer);
	}

	private static String screen(String question, String allergen, PatientClinicalContext.ActiveDrugOrder... orders) {
		Set<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		List<PatientClinicalContext.ActiveDrugOrder> list = new ArrayList<PatientClinicalContext.ActiveDrugOrder>(
				Arrays.asList(orders));
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.shippedServiceWithGroups()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(70, null, names, null,
				DrugReferenceTestSupport.set(allergen), null, list), question).getModuleAnswer();
	}
}
