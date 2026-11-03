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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A proposal whose cautions include a condition-mediated finding (ADR Decision 111) is answered by the module, as one
 * whose cautions are interactions alone is (ADR Decision 140), with the chain on a line of its own and counted apart
 * (ADR Decision 559). An external evaluation sheet asked the public demo's ARV patients whether metformin is safe, and
 * graded the answers a miss for leaving out the lactic-acidosis risk metformin shares with their nucleoside analogues.
 * With {@code chartsearchai.drugSafety.derivedFindings} on, the module raises exactly that chain, and then handed the
 * question to the model, which answered <em>"The records do not address the safety of giving metformin"</em> and
 * <em>"No Metformin should not be given"</em> over the same findings. The charts are the sheet's own
 * ({@link ListedRegimenAnswerTest}'s), the knowledge base the shipped one.
 */
public class ConditionMediatedComposedAnswerTest extends BaseModuleContextSensitiveTest {

	/** The sentence every condition-mediated finding closes on: what the chain is and is not. */
	private static final String PROVENANCE = "Derived from DDInter's drug-disease rows by a text match; not a DDInter "
			+ "pairwise rating, and this finding has no severity of its own.";

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_DERIVED_FINDINGS,
			ChartSearchAiConstants.DERIVED_FINDINGS_MAJOR);
	}

	/** dd79194e asked alone: two Moderate interactions and the lactic-acidosis chain through four of her ARVs. */
	@Test
	public void aDrugAskedAloneWhoseCautionsIncludeAChainIsAnsweredWithTheChainCountedApart() {
		String answer = ListedRegimenAnswerTest.ask(ListedRegimenAnswerTest.LOPINAVIR_RITONAVIR,
			"Is it safe to give Metformin?");

		assertNotNull(answer, "the module answers");
		assertEquals("2 interaction cautions for Metformin — 2 Moderate; and 1 condition-mediated caution, with no severity "
				+ "of its own.", answer.split("\n")[0]);
		assertChainLine(answer);
		assertEquals(answer.split("\n")[0], ListedRegimenAnswerTest.injected(ListedRegimenAnswerTest.LOPINAVIR_RITONAVIR,
			"Is it safe to give Metformin?").getModuleAnswerSummary(), "the lead is the sentence a client shows alone");
	}

	/** Row 9 of the sheet: the same question after a list of her own medications reads as the drug asked alone. */
	@Test
	public void aListOfHerMedicationsBeforeTheProposalStatesTheChainAsTheDrugAskedAloneDoes() {
		String alone = ListedRegimenAnswerTest.ask(ListedRegimenAnswerTest.LOPINAVIR_RITONAVIR,
			"Is it safe to give Metformin?");
		String listed = ListedRegimenAnswerTest.ask(ListedRegimenAnswerTest.LOPINAVIR_RITONAVIR,
			ListedRegimenAnswerTest.LISTING_HER_PROTEASE_INHIBITOR + "Metformin?");

		assertNotNull(listed, "the module answers");
		assertChainLine(listed);
		// Its one sentence counts the chain as the lead does, and never as a caution per order it names.
		assertEquals("2 interaction cautions for Metformin — 2 Moderate; and 1 condition-mediated caution, with no severity of "
			+ "its own.",
			ListedRegimenAnswerTest.injected(ListedRegimenAnswerTest.LOPINAVIR_RITONAVIR,
				ListedRegimenAnswerTest.LISTING_HER_PROTEASE_INHIBITOR + "Metformin?").getModuleAnswerSummary());
		// The finding's own record is numbered after what each question injected; its words and her orders' records are not.
		assertEquals(withoutTheFindingsNumber(chainLine(alone)), withoutTheFindingsNumber(chainLine(listed)));
	}

	private static String withoutTheFindingsNumber(String line) {
		return line.replaceFirst(java.util.regex.Pattern.quote(PROVENANCE) + " \\[\\d+\\]", PROVENANCE);
	}

	/** Row 1 of the sheet: a list of drugs she is not on, answered by the list's composer. */
	@Test
	public void aListOfDrugsSheIsNotOnStatesTheChainUnderALeadCountingItApart() {
		String answer = ListedRegimenAnswerTest.ask(ListedRegimenAnswerTest.ONE_TB_COMBINATION,
			ListedRegimenAnswerTest.LISTING_DRUGS_SHE_IS_NOT_ON + "metformin?");

		assertNotNull(answer, "the module answers");
		assertEquals("3 interaction cautions for Metformin — 3 Moderate; and 1 condition-mediated caution, with no severity of "
			+ "its own.", answer.split("\n")[0]);
		assertChainLine(answer);
	}

	/** A chain and nothing else: metformin on stavudine, a pair DDInter rates Unknown. */
	@Test
	public void aProposalWhoseOnlyFindingIsAChainIsAnsweredWithIt() {
		String answer = ListedRegimenAnswerTest.ask(ListedRegimenAnswerTest.orders("Stavudine", "J05AF04", 1),
			"Is it safe to give Metformin?");

		assertNotNull(answer, "the module answers");
		assertEquals("1 condition-mediated caution for Metformin — no severity of its own, with Stavudine.",
			answer.split("\n")[0]);
		assertChainLine(answer);
		assertEquals(answer.split("\n")[0], ListedRegimenAnswerTest.injected(ListedRegimenAnswerTest.orders("Stavudine",
			"J05AF04", 1), "Is it safe to give Metformin?").getModuleAnswerSummary(), "the lead is the sentence a client shows alone");
	}

	/**
	 * Whether it INTERACTS, where the only finding is a chain: no "Yes", since the interaction check raised no interaction
	 * finding, and the chain follows under what the check found (ADR Decisions 398, 559).
	 */
	@Test
	public void whetherItInteractsWhereTheOnlyFindingIsAChainSaysNoInteractionWasRaisedThenStatesTheChain() {
		String answer = ListedRegimenAnswerTest.ask(ListedRegimenAnswerTest.orders("Stavudine", "J05AF04", 1),
			"Does metformin interact with her medications?");

		assertNotNull(answer, "the module answers");
		String[] lines = answer.split("\n");
		assertEquals("The interaction check raised no interaction finding about Metformin against this patient's active "
				+ "medications.", lines[0]);
		assertTrue(!answer.startsWith("Yes"), answer);
		assertChainLine(answer);
	}

	/** The tier off, a stock install: no chain, so the answer is the interactions alone, as before. */
	@Test
	public void withTheTierOffTheAnswerCountsInteractionCautionsAlone() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_DERIVED_FINDINGS, "off");

		String answer = ListedRegimenAnswerTest.ask(ListedRegimenAnswerTest.LOPINAVIR_RITONAVIR,
			"Is it safe to give Metformin?");

		assertEquals("2 interaction cautions for Metformin — 2 Moderate.", answer.split("\n")[0]);
		assertNull(chainLine(answer), answer);
	}

	private static void assertChainLine(String answer) {
		String line = chainLine(answer);
		assertNotNull(line, "a line states the chain: " + answer);
		assertTrue(line.startsWith("Metformin is rated Major in Acidosis, Lactic"), line);
		assertTrue(line.contains(PROVENANCE + " ["), "the chain's line keeps what it is not, then cites it: " + line);
		List<String> others = new ArrayList<String>();
		for (String each : answer.split("\n")) {
			if (each.contains("not a DDInter pairwise rating") && !each.equals(line)) {
				others.add(each);
			}
		}
		assertTrue(others.isEmpty(), "the chain is stated once: " + answer);
	}

	private static String chainLine(String answer) {
		for (String line : answer.split("\n")) {
			if (line.contains("Acidosis, Lactic")) {
				return line;
			}
		}
		return null;
	}
}
