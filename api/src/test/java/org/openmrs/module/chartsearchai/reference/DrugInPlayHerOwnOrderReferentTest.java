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

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * A drug in play that is one of the patient's OWN active orders states the current-medication call,
 * not the proposal call — issue #402, ADR Decision 121.
 *
 * <p><b>The defect.</b> Asked <em>"Is it safe to add prednisone for her?"</em> about a patient holding
 * an active {@code Prednisone Co 5mg} order, the answer opened <em>"No — Prednisone should not be
 * added"</em>. The drug-in-play arm stated the proposal referent for every drug the question or the
 * answer put in play, so nothing in the finding told the model the drug was already hers. The arm now
 * states the current-medication referent where the drug's substance is one her active orders resolve
 * to, and it does so at every site the arm builds a finding at: one referent per drug in play, so the
 * prompt's ranking sentence cannot hand the lead to a finding stating the other one.
 *
 * <p><b>One case per site.</b> Each site takes the referent as its own argument, so a case per site is
 * what a mutation of one of them back to {@code false} can redden — the reason CLAUDE.md gives for the
 * sibling {@code chartOrderBridges} parameter. Mutate a site and read the failures; no mapping of site
 * to case is published here. The derived tier's site is held in {@code ConditionMediatedFindingTest},
 * which switches that tier on, and the several-orders finding's in {@code SubstanceInSeveralActiveOrdersTest}.
 *
 * <p>Each case drives the real {@code injectRecords} with the real validator behind it and reads the
 * clause the injected finding ends with, which is what the model reads.
 */
public class DrugInPlayHerOwnOrderReferentTest {

	/** Verbatim DDInter excerpt: Simvastatin × Clarithromycin is Major. */
	private static final String ALIAS_FIXTURE = DrugReferenceTestSupport.DDI_ALIAS_DRUG_NAMES;

	/** Methylphenidate × Modafinil, rated Minor, both filed under N06BA — the fold of a rule and a class
	 *  sentence onto one chip ({@code FoldedFindingStrengthTest}). */
	private static final String FOLDED_FIXTURE = "chartsearchai-test/ddi-folded-minor-class-pair.json";

	/** Prednisolone and Methylprednisolone share H02AB with no above-floor rule between them
	 *  ({@code ClassOnlyFindingStrengthTest}). */
	private static final String CLASS_ONLY_FIXTURE = "chartsearchai-test/ddi-class-only-and-rule-one-partner.json";

	/** Pinned as literals rather than read off {@code DrugReferenceInjector}'s constants: the clause is
	 *  what the model reads, and a test comparing a constant to itself stays green through a reword. */
	private static final String CHANGE_CURRENT =
			"This finding is a reason to change a medication this patient is already taking.";

	private static final String CAUTION_CURRENT = "This finding is a caution about a medication this "
			+ "patient is already taking, not a reason to change it.";

	private static final String WITHHOLD = "This finding is a reason to withhold it.";

	private static final String CAUTION = "This finding is a caution to note, not a reason to withhold it.";

	private static List<String> findings(DrugReferenceService service, PatientClinicalContext context,
			String question) {
		return DrugReferenceTestSupport.findingTexts(DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question));
	}

	private static String onlyFinding(DrugReferenceService service, PatientClinicalContext context,
			String question) {
		List<String> findings = findings(service, context, question);
		assertEquals(1, findings.size(),
				"the arrangement under test is ONE finding, or the assertions are about the wrong one: "
						+ findings);
		return findings.get(0);
	}

	private static void assertStatesTheCurrentMedicationCall(String finding) {
		assertTrue(finding.endsWith(CHANGE_CURRENT) || finding.endsWith(CAUTION_CURRENT),
				"the drug in play is one of her own active orders, so its finding states the call about "
						+ "that medication: " + finding);
		assertFalse(finding.contains(WITHHOLD) || finding.contains(CAUTION),
				"and not the proposal call, which is what made the answer refuse to give a drug she is "
						+ "already on: " + finding);
	}

	/** The interaction chip built through the plain {@code partnerLabel} overload: a flattened
	 *  context carries no per-order structure, so there is no reconciled partner name. */
	@Test
	public void anInteractionAboutADrugInPlayThatIsHerOwnOrderStatesTheCurrentMedicationCall()
			throws IOException {
		String finding = onlyFinding(DrugReferenceTestSupport.ddiFixtureService(ALIAS_FIXTURE),
			DrugReferenceTestSupport.ctx(40, null,
				DrugReferenceTestSupport.set("Simvastatin", "Clarithromycin"), null, null, null),
			"Is it safe to give simvastatin?");

		assertTrue(finding.toLowerCase().contains("major"),
				"precondition: the drug-in-play arm's Major rule chip is the finding: " + finding);
		assertTrue(finding.endsWith(CHANGE_CURRENT), finding);
		assertStatesTheCurrentMedicationCall(finding);
	}

	/** The same pair over a context carrying one order per prescription, which is where the rule
	 *  chip's partner name is reconciled against the order (issue #339) — the second construction. */
	@Test
	public void aReconciledInteractionAboutHerOwnOrderStatesTheCurrentMedicationCall() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(ALIAS_FIXTURE);
		String finding = onlyFinding(service,
			DrugReferenceTestSupport.contextNaming(service, 40, null, "Simvastatin", "Clarithromycin"),
			"Is it safe to give simvastatin?");

		assertTrue(finding.toLowerCase().contains("major"),
				"precondition: the drug-in-play arm's Major rule chip is the finding: " + finding);
		assertStatesTheCurrentMedicationCall(finding);
	}

	/** The negative control: the same question with the drug NOT on her list stays a proposal. */
	@Test
	public void anInteractionAboutADrugSheDoesNotTakeStillStatesTheProposalCall() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(ALIAS_FIXTURE);
		String finding = onlyFinding(service,
			DrugReferenceTestSupport.contextNaming(service, 40, null, "Clarithromycin"),
			"Is it safe to give simvastatin?");

		assertTrue(finding.endsWith(WITHHOLD),
				"a drug the question proposed and she does not take is a proposal, so withholding is the "
						+ "act: " + finding);
		for (SafetyWarning chip : DrugReferenceTestSupport.validator(service).validate("",
			"Is it safe to give simvastatin?",
			DrugReferenceTestSupport.contextNaming(service, 40, null, "Clarithromycin"))) {
			assertFalse(chip.isAboutACurrentMedication(), "no chip is about her own medication: "
					+ chip.getDetail());
		}
	}

	/** The FOLDED construction: a rule and a class sentence about one co-medication on one chip. */
	@Test
	public void aFoldedInteractionAboutHerOwnOrderStatesTheCurrentMedicationCall() throws IOException {
		String finding = onlyFinding(DrugReferenceTestSupport.ddiFixtureService(FOLDED_FIXTURE),
			DrugReferenceTestSupport.ctx(40, null,
				DrugReferenceTestSupport.set("Methylphenidate", "Modafinil"),
				DrugReferenceTestSupport.set("N06BA04", "N06BA07"), null, null),
			"Is it safe to give methylphenidate?");

		assertTrue(finding.contains("same ATC class (N06BA)") && finding.toLowerCase().contains("minor"),
				"precondition: the finding is the fold of the Minor rule and the class sentence: " + finding);
		assertTrue(finding.endsWith(CHANGE_CURRENT),
				"a fold states the stronger claim, and about her own order that is the change call: "
						+ finding);
		assertStatesTheCurrentMedicationCall(finding);
	}

	/** {@code collapseSharedMechanisms}' merged chip: one mechanism naming two of her orders. */
	@Test
	public void aCollapsedMechanismAboutHerOwnOrderStatesTheCurrentMedicationCall() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.ddiFixtureEntries(DrugReferenceTestSupport.DDI_SHARED_MECHANISM_PARTNERS));
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set("Aspirin 81mg", "Prednisone 5mg", "Methylprednisolone 4mg",
				"Heparin 5000 units"), null, null, null);

		List<SafetyWarning> merged = new java.util.ArrayList<SafetyWarning>();
		for (SafetyWarning chip : DrugReferenceTestSupport.validator(service).validate("",
			DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION, context)) {
			if (chip.namedPartners() != null && chip.namedPartners().size() > 1) {
				merged.add(chip);
			}
		}
		assertEquals(1, merged.size(), "precondition: the arrangement's merged chip was raised: " + merged);
		assertTrue(merged.get(0).isAboutACurrentMedication(),
				"the merged chip is about aspirin, which is one of her own orders: " + merged.get(0).getDetail());

		boolean sawTheMergedFinding = false;
		for (String finding : findings(service, context, DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION)) {
			sawTheMergedFinding |= finding.contains(DrugReferenceTestSupport.SHARED_MECHANISM_TEXT);
			assertStatesTheCurrentMedicationCall(finding);
		}
		assertTrue(sawTheMergedFinding, "precondition: the merged finding reached the prompt");
	}

	/** The class-only chip: a shared subgroup with no rule to fold into. */
	@Test
	public void aClassOnlyRelationshipAboutHerOwnOrderStatesTheCurrentMedicationCaution()
			throws IOException {
		String finding = onlyFinding(DrugReferenceTestSupport.ddiFixtureService(CLASS_ONLY_FIXTURE),
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Prednisolone", "Methylprednisolone"),
				DrugReferenceTestSupport.set("H02AB06", "H02AB04"), null, null),
			"Is it safe to give prednisolone?");

		assertTrue(finding.contains("same ATC class (H02AB)"),
				"precondition: the class arm's own sentence, with no rated rule folded in: " + finding);
		assertTrue(finding.endsWith(CAUTION_CURRENT),
				"shared classification alone is a caution (issue #400), and about her own order it is "
						+ "the current-medication caution: " + finding);
		assertStatesTheCurrentMedicationCall(finding);
	}

	/** {@code addContraindications}: a curated CONDITION rule, which the allergen arm cannot raise. */
	@Test
	public void aCuratedContraindicationAboutADrugInPlayThatIsHerOwnOrderStatesTheCurrentMedicationCall() {
		String finding = onlyFinding(DrugReferenceTestSupport.curatedService(),
			DrugReferenceTestSupport.prescribedIbuprofenChart(null, DrugReferenceTestSupport.set("peptic ulcer")),
			"Is ibuprofen safe for her?");

		assertTrue(finding.contains("active peptic ulcer disease"),
				"precondition: the curated condition rule is the finding: " + finding);
		assertTrue(finding.endsWith(CHANGE_CURRENT), finding);
		assertStatesTheCurrentMedicationCall(finding);
	}

	/** {@code addAllergyContraindications}: a recorded allergy to the drug itself, over a dataset
	 *  carrying no curated rule, so the allergen arm's identity chip is the only finding. */
	@Test
	public void anAllergyToADrugInPlayThatIsHerOwnOrderStatesTheCurrentMedicationCall() throws IOException {
		String finding = onlyFinding(DrugReferenceTestSupport.ddiFixtureService(ALIAS_FIXTURE),
			DrugReferenceTestSupport.ctx(40, null, DrugReferenceTestSupport.set("Simvastatin"), null,
				DrugReferenceTestSupport.set("simvastatin"), null),
			"Is it safe to give simvastatin?");

		assertTrue(finding.toLowerCase().contains("allerg"),
				"precondition: the allergen arm is what raised this: " + finding);
		assertTrue(finding.endsWith(CHANGE_CURRENT), finding);
		assertStatesTheCurrentMedicationCall(finding);
	}

	/**
	 * Issue #477's constituent case, carried onto this issue: rifampicin proposed to a patient on ONE
	 * {@code Isoniazid / pyrazinamide / rifampin} order, over the shipped knowledge base. The
	 * combination order resolves to the rifampicin substance, so every finding about rifampicin states
	 * the current-medication call.
	 */
	@Test
	public void aDrugInPlayThatIsAConstituentOfHerCombinationOrderStatesTheCurrentMedicationCall() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 40, null,
			"Isoniazid / pyrazinamide / rifampin");
		String question = "Is it safe to give rifampicin?";

		Set<Object> herSubstances = DrugSafetyValidator.substancesOf(service.findForActiveOrders(context));
		Set<Object> asked = new HashSet<Object>();
		for (DrugReference entry : service.findImpliedByQuery(question)) {
			asked.add(entry.substanceGroupKey());
		}
		assertFalse(asked.isEmpty(), "precondition: the question puts rifampicin in play");
		assertTrue(herSubstances.containsAll(asked),
				"precondition: her combination order resolves to the substance the question names, or this "
						+ "case is about the data rather than the arm: asked " + asked + ", hers " + herSubstances);

		List<String> findings = findings(service, context, question);
		assertFalse(findings.isEmpty(), "precondition: the arm raised findings about rifampicin");
		for (String finding : findings) {
			assertStatesTheCurrentMedicationCall(finding);
		}
		for (SafetyWarning chip : DrugReferenceTestSupport.validator(service).validate("", question, context)) {
			assertTrue(chip.isAboutACurrentMedication(),
					"every chip about rifampicin says it is about one of her medications: " + chip.getDetail());
		}
	}
}
