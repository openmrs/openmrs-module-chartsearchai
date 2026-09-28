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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * A drug in play that is one of the patient's OWN active orders states the current-medication call,
 * not the proposal call — issue #402, ADR Decision 123.
 *
 * <p><b>The defect.</b> Asked <em>"Is it safe to add prednisone for her?"</em> about a patient holding
 * an active {@code Prednisone Co 5mg} order, the answer opened <em>"No — Prednisone should not be
 * added"</em>. The drug-in-play arm stated the proposal referent for every drug the question or the
 * answer put in play, so nothing in the finding told the model the drug was already hers. The arm now
 * states the current-medication referent where the drug's substance is one her active orders establish
 * she takes, and it does so at every site the arm builds a finding at: one referent per drug in play, so
 * the prompt's ranking sentence cannot hand the lead to a finding stating the other one.
 *
 * <p><b>One case per site.</b> Each site takes the referent as its own argument, so a case per site is
 * what a mutation of one of them back to {@code false} can redden — the reason CLAUDE.md gives for the
 * sibling {@code chartOrderBridges} parameter. Mutate a site and read the failures; no mapping of site
 * to case is published here. The derived tier's site is held in {@code ConditionMediatedFindingTest},
 * which switches that tier on, and the several-orders finding's in {@code SubstanceInSeveralActiveOrdersTest}.
 *
 * <p>Each case drives the real {@code injectRecords} with the real validator behind it and reads the
 * clause the injected finding ends with, which is what the model reads. The dose check is the one site
 * read off the CHIP instead: an overdose finding never reaches the prompt's records, so its referent is
 * what the wire publishes and nothing else. A drug only the ANSWER names is read off the chips too,
 * because only the post-answer pass puts it in play.
 *
 * <p><b>Hers is the SUBSTANCE, and not the row the question named</b>: a case over a fixture whose
 * question row her order does not resolve holds that. <b>And a drug every order of which is coded only
 * as a locally applied presentation keeps the proposal call where the data also files it outside those
 * groups</b>, because the question may be proposing that other presentation: review round 1 of PR #544
 * measured a Major bleeding finding about <em>"Can I start her on oral diclofenac?"</em> over a
 * {@code Voltaren gel} order losing its refusal. Review round 2 measured the gate firing where the data
 * files the drug under no other group, and two cases hold that it does not. The presentation is read off
 * each order's OWN ATC codes; a case with a systemic order of the same drug beside the gel, in both
 * orders, holds the gate's "every order", and one whose order mixes a locally applied code with systemic
 * ones its "every code". Mutate the gate and read the failures.
 *
 * <p><b>Established, and not merely resolved</b> (review round 3 of PR #544). Her orders' resolution holds
 * substances she need not take, and the cases over the shipped knowledge base that reach one — through a
 * brand two substances' rows share, a code the data files under two substances, and a bridged concept
 * filed on several — each sit beside a case where that same leg does establish the substance, so both
 * values of each leg are built. The gate asks the establishing orders alone, a drug the question lists as
 * current is not asked it, and the referent never widens the resolution: a case each.
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

	/** One substance as two rows with disjoint aliases, the question naming one and her order the other
	 *  (the fixture's own description says how). */
	private static final String ROW_APART_FIXTURE =
			"chartsearchai-test/drug-reference-question-row-apart-from-order-row.json";

	/** A dose the curated seed's adult ibuprofen band trips on its DAILY ceiling: 3200 mg/day against
	 *  2400. */
	private static final String DAILY_EXCESS = "Ibuprofen 800 mg four times a day.";

	/** A dose its paediatric band trips on the PER-DOSE ceiling alone at 20 kg: 400 mg against 10 mg/kg,
	 *  while 1200 mg/day does not exceed that band's 1200 ({@code WeightAwareOverdoseTest}'s arrangement). */
	private static final String PER_DOSE_EXCESS = "Ibuprofen 400 mg every 8 hours.";

	/** The uuid the shipped bridge records for CIEL 75876, {@code Esomeprazole magnesium}: filed on
	 *  Omeprazole AND Esomeprazole, two substances, under a name that names Esomeprazole alone
	 *  ({@code BridgedConceptOrderResolutionTest} carries the same concept). */
	private static final String ESOMEPRAZOLE_MAGNESIUM_CONCEPT = "75876AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	/** The uuid the shipped bridge records for {@code Trastuzumab-dkst}, a trastuzumab biosimilar: filed on
	 *  Trastuzumab, Trastuzumab deruxtecan and Trastuzumab emtansine, under a name that names Trastuzumab
	 *  alone. */
	private static final String TRASTUZUMAB_DKST_CONCEPT = "7d2c2d7a-a8ab-4add-997c-b27c2e759bb8";

	/** A drug the question proposes that the shipped knowledge base files a PPI brand, a PPI code and the
	 *  CIEL 75876 bridge under beside esomeprazole — the three ways review round 3 of PR #544 found her
	 *  orders resolving to more substances than she takes. */
	private static final String OMEPRAZOLE_QUESTION = "Can I give her omeprazole?";

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
		// Beside issue #548's finding that the drug proposed is already in her order, which states the one
		// referent every finding about the drug states — asserted here, since it is set aside. Recognised by
		// its sentence because this reads the injected records, which carry no flag. One order, so the
		// caution in that column: only the proposal would duplicate it (ADR Decision 125).
		List<String> findings = new ArrayList<String>();
		for (String finding : findings(service, context, question)) {
			if (finding.contains(" is already in active order ")) {
				assertTrue(finding.endsWith(CAUTION_CURRENT), "one referent per drug in play: " + finding);
			} else {
				findings.add(finding);
			}
		}
		assertEquals(1, findings.size(),
				"the arrangement under test is ONE finding, or the assertions are about the wrong one: "
						+ findings);
		return findings.get(0);
	}

	/** One active order carrying the ATC codes a concept dictionary mapped its concept to — the per-order
	 *  codes {@code PatientClinicalContextBuilder} reads (issue #132). */
	private static PatientClinicalContext.ActiveDrugOrder coded(String display, String... codes) {
		return DrugReferenceTestSupport.activeOrder("order-" + display, display,
			DrugReferenceTestSupport.set(display), DrugReferenceTestSupport.set(codes));
	}

	/** One active order written against {@code conceptUuid}, as {@code PatientClinicalContextBuilder} records
	 *  it, recording {@code names} (its display first) and no ATC code. */
	private static PatientClinicalContext.ActiveDrugOrder writtenAgainst(String conceptUuid, String display,
			String... names) {
		Set<String> recorded = DrugReferenceTestSupport.set(display);
		recorded.addAll(Arrays.asList(names));
		return PatientClinicalContext.ActiveDrugOrder.named("order-" + display, display, recorded, null, null,
			conceptUuid);
	}

	/** A chart of {@code orders}, their names and codes unioned into the flattened sets the way the builder
	 *  unions them, resolved as {@code DrugReferenceTestSupport.contextNaming} resolves its orders. */
	private static PatientClinicalContext chartOf(DrugReferenceService service,
			PatientClinicalContext.ActiveDrugOrder... orders) {
		Set<String> names = new LinkedHashSet<String>();
		Set<String> codes = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
			codes.addAll(order.getAtcCodes());
		}
		return service.withReferenceNames(DrugReferenceTestSupport.ctx(40, null, names, codes, null, null,
			Arrays.asList(orders)));
	}

	/** Whether the question puts in play only substances her resolved orders are of — the precondition
	 *  without which a case is about the data rather than the arm. */
	private static void assertTheQuestionsDrugIsHers(DrugReferenceService service, PatientClinicalContext context,
			String question) {
		assertHerOrdersResolveTheQuestionsDrug(service, context, question);
	}

	/** Whether {@code findForActiveOrders} resolves her orders to every substance the question puts in play —
	 *  what a case about a drug she is on needs, and what a case about her orders resolving to MORE than she
	 *  takes needs as its premise, so that its drug is one the resolution does reach. */
	private static void assertHerOrdersResolveTheQuestionsDrug(DrugReferenceService service,
			PatientClinicalContext context, String question) {
		Set<Object> hers = DrugSafetyValidator.substancesOf(service.findForActiveOrders(context));
		Set<Object> asked = substancesAskedAbout(service, question);
		assertFalse(asked.isEmpty(), "precondition: the question puts a drug in play");
		assertTrue(hers.containsAll(asked), "precondition: her orders resolve to the substance the question "
				+ "names: asked " + asked + ", hers " + hers);
	}

	/** The substances the ranked accessor reads {@code name} to put in play, as an order's name leg reads it. */
	private static Set<Object> substancesImpliedBy(DrugReferenceService service, String name) {
		Set<Object> implied = new HashSet<Object>();
		for (DrugReference entry : service.findImpliedByDrugName(name)) {
			implied.add(entry.substanceGroupKey());
		}
		return implied;
	}

	/** The substances the question puts in play. */
	private static Set<Object> substancesAskedAbout(DrugReferenceService service, String question) {
		Set<Object> asked = new HashSet<Object>();
		for (DrugReference entry : service.findImpliedByQuery(question)) {
			asked.add(entry.substanceGroupKey());
		}
		return asked;
	}

	private static void assertStatesTheProposalCall(String finding, String where) {
		assertTrue(finding.endsWith(WITHHOLD) || finding.endsWith(CAUTION),
				"the drug in play is not one her orders establish she takes, so its finding states the call about "
						+ "a proposal (" + where + "): " + finding);
		assertFalse(finding.contains(CHANGE_CURRENT) || finding.contains(CAUTION_CURRENT),
				"and not the call about a medication she is already taking, which tells the answer never to "
						+ "open by refusing it (" + where + "): " + finding);
	}

	/** Whether the question's pre-answer chips are all proposals, and there are some. */
	private static void assertNoChipIsAboutACurrentMedication(DrugReferenceService service,
			PatientClinicalContext context, String question) {
		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(service).validate("", question, context);
		assertFalse(chips.isEmpty(), "precondition: the question raises chips");
		for (SafetyWarning chip : chips) {
			assertFalse(chip.isAboutACurrentMedication(),
					"no chip calls the drug in play her medication: " + chip.getDetail());
		}
	}

	/** The one dose warning the real validator raises for {@code answer} over the curated seed, asked
	 *  whether ibuprofen is safe for her; {@code ceiling} is the words that say which of the dose check's
	 *  two sentences it is. */
	private static SafetyWarning overdoseChip(String answer, PatientClinicalContext context, String ceiling) {
		List<SafetyWarning> out = new ArrayList<SafetyWarning>();
		for (SafetyWarning chip : DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService())
				.validate(answer, "Is ibuprofen safe for her?", context)) {
			if (SafetyWarning.TYPE_OVERDOSE.equals(chip.getType())) {
				out.add(chip);
			}
		}
		assertEquals(1, out.size(), "precondition: the stated dose trips the curated band once: " + out);
		assertTrue(out.get(0).getDetail().contains(ceiling),
				"precondition: the " + ceiling + " sentence is the one raised: " + out.get(0).getDetail());
		return out.get(0);
	}

	/** A chart of age {@code age} and weight {@code weightKg} whose one active order, where {@code order}
	 *  is not null, is that ibuprofen prescription. */
	private static PatientClinicalContext ibuprofenChart(int age, Double weightKg, String order) {
		return DrugReferenceTestSupport.ctx(age, weightKg,
			order == null ? null : DrugReferenceTestSupport.set(order), null, null, null);
	}

	private static void assertStatesTheCurrentMedicationCall(String finding) {
		assertStatesTheCurrentMedicationCall(finding, "the arrangement");
	}

	/** As above, {@code where} naming which of a case's arrangements the finding came from. */
	private static void assertStatesTheCurrentMedicationCall(String finding, String where) {
		assertTrue(finding.endsWith(CHANGE_CURRENT) || finding.endsWith(CAUTION_CURRENT),
				"the drug in play is one of her own active orders, so its finding states the call about "
						+ "that medication (" + where + "): " + finding);
		assertFalse(finding.contains(WITHHOLD) || finding.contains(CAUTION),
				"and not the proposal call, which is what made the answer refuse to give a drug she is "
						+ "already on (" + where + "): " + finding);
	}

	/** The ATC codes the data files the question's drug under, over every row of its substance the
	 *  question or her orders resolved — what a case's premise about which groups those are is asked of. */
	private static Set<String> codesOfTheQuestionsDrug(DrugReferenceService service, PatientClinicalContext context,
			String question) {
		Set<Object> asked = new HashSet<Object>();
		List<DrugReference> rows = new ArrayList<DrugReference>(service.findImpliedByQuery(question));
		for (DrugReference row : rows) {
			asked.add(row.substanceGroupKey());
		}
		rows.addAll(service.findForActiveOrders(context));
		Set<String> codes = new LinkedHashSet<String>();
		for (DrugReference row : rows) {
			if (asked.contains(row.substanceGroupKey())) {
				codes.addAll(row.normalizedAtcCodes());
			}
		}
		return codes;
	}

	/** The premise of a case about a drug the data files under no other group: it files the question's
	 *  drug under ATC codes, and {@link DrugReference#isLocallyAppliedAtcCode} answers true for each. */
	private static void assertEveryCodeOfTheQuestionsDrugIsLocallyApplied(DrugReferenceService service,
			PatientClinicalContext context, String question) {
		Set<String> codes = codesOfTheQuestionsDrug(service, context, question);
		assertFalse(codes.isEmpty(), "precondition: the data files the question's drug under ATC codes");
		for (String code : codes) {
			assertTrue(DrugReference.isLocallyAppliedAtcCode(code),
					"precondition: the data files the question's drug under locally applied groups alone: " + codes);
		}
	}

	/** Whether the question's pre-answer chips are all about one of her medications, and there are some. */
	private static void assertEveryChipIsAboutACurrentMedication(DrugReferenceService service,
			PatientClinicalContext context, String question) {
		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(service).validate("", question, context);
		assertFalse(chips.isEmpty(), "precondition: the question raises chips");
		for (SafetyWarning chip : chips) {
			assertTrue(chip.isAboutACurrentMedication(), "every chip about her own drug says so: "
					+ chip.getDetail());
		}
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

	/**
	 * The referent is the SUBSTANCE's and not the ROW's: the question's own row is not among the rows her
	 * order resolved, and the finding still states the call about her medication. Keyed on the row, the
	 * arm would state a proposal for a drug she is on wherever one substance's rows carry different
	 * names, and a sibling row's chip about the same drug would state the other referent.
	 */
	@Test
	public void theReferentIsTheSubstancesAndNotTheRowTheQuestionNamed() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.fixtureEntries(ROW_APART_FIXTURE));
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 40, null,
			"Tirosint 50mcg/ml", "Warfarin 5mg");
		String question = "Is it safe to give her eltroxin?";

		List<DrugReference> herRows = service.findForActiveOrders(context);
		List<DrugReference> askedRows = service.findImpliedByQuery(question);
		assertFalse(askedRows.isEmpty(), "precondition: the question puts a row in play");
		for (DrugReference asked : askedRows) {
			assertFalse(herRows.contains(asked), "precondition: the question's row is not one her order "
					+ "resolved, or this case cannot tell the substance from the row: " + asked.getName());
		}
		assertTheQuestionsDrugIsHers(service, context, question);

		String finding = onlyFinding(service, context, question);
		assertTrue(finding.contains("Warfarin") && finding.toLowerCase().contains("moderate"),
				"precondition: the question row's Moderate rule is the finding: " + finding);
		assertTrue(finding.endsWith(CAUTION_CURRENT), finding);
		assertStatesTheCurrentMedicationCall(finding);
	}

	/**
	 * A drug her chart holds ONLY as a locally applied presentation keeps the PROPOSAL call where the data
	 * also files it outside those groups. Her one diclofenac order is a gel the dictionary classified {@code M02AA15} ("Topical products for joint and
	 * muscular pain"), and the question proposes an oral course: the Major bleeding finding about that
	 * course is not a reason to change her gel, and stated as one the prompt forbids the answer to open
	 * by refusing it. Over the shipped knowledge base, which files every diclofenac row under the
	 * systemic {@code M01AB05} as well: the presentation outside those groups the question proposes.
	 */
	@Test
	public void aDrugSheHoldsOnlyAsALocallyAppliedPresentationStillStatesTheProposalCall() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = chartOf(service, coded("Voltaren gel", "M02AA15"),
			coded("Warfarin 5mg", "B01AA03"));
		String question = "Can I start her on oral diclofenac?";
		assertTheQuestionsDrugIsHers(service, context, question);
		boolean filedOutsideThoseGroups = false;
		for (String code : codesOfTheQuestionsDrug(service, context, question)) {
			filedOutsideThoseGroups |= !DrugReference.isLocallyAppliedAtcCode(code);
		}
		assertTrue(filedOutsideThoseGroups, "precondition: the data files diclofenac under a code outside the "
				+ "locally applied groups, or there is no other presentation for the question to propose");

		String finding = onlyFinding(service, context, question);
		assertTrue(finding.contains("Warfarin") && finding.contains("Major"),
				"precondition: the Major diclofenac-warfarin rule is the finding: " + finding);
		assertTrue(finding.endsWith(WITHHOLD),
				"a gel is all she takes of it, so an oral course is a proposal and withholding is the act: "
						+ finding);
		for (SafetyWarning chip : DrugReferenceTestSupport.validator(service).validate("", question, context)) {
			assertFalse(chip.isAboutACurrentMedication(), "no chip calls the oral course her medication: "
					+ chip.getDetail());
		}
	}

	/**
	 * The gate asks EVERY order of the substance: beside the gel she also takes diclofenac tablets, which
	 * the dictionary classified {@code M01AB05}, so the drug is hers in the presentation an oral question
	 * names and every finding about it states the current-medication call. Asked with the gel listed first
	 * and with it listed last, so a gate reading only the first order of the substance fails on one
	 * arrangement and a gate reading only the last fails on the other (review round 2 of PR #544).
	 */
	@Test
	public void aLocallyAppliedOrderBesideASystemicOrderOfTheSameDrugStatesTheCurrentMedicationCall() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		String question = "Can I start her on oral diclofenac?";
		List<List<PatientClinicalContext.ActiveDrugOrder>> arrangements = Arrays.asList(
			Arrays.asList(coded("Voltaren gel", "M02AA15"), coded("Diclofenac 50mg tablets", "M01AB05"),
				coded("Warfarin 5mg", "B01AA03")),
			Arrays.asList(coded("Diclofenac 50mg tablets", "M01AB05"), coded("Voltaren gel", "M02AA15"),
				coded("Warfarin 5mg", "B01AA03")));
		for (List<PatientClinicalContext.ActiveDrugOrder> orders : arrangements) {
			PatientClinicalContext context = chartOf(service,
				orders.toArray(new PatientClinicalContext.ActiveDrugOrder[0]));
			assertTheQuestionsDrugIsHers(service, context, question);

			boolean sawTheMajor = false;
			for (String finding : findings(service, context, question)) {
				sawTheMajor |= finding.contains("Warfarin") && finding.contains("Major");
				assertStatesTheCurrentMedicationCall(finding, orders.get(0).getDisplay() + " listed first");
			}
			assertTrue(sawTheMajor, "precondition: the Major diclofenac-warfarin rule is among the findings, "
					+ orders.get(0).getDisplay() + " first");
		}
	}

	/**
	 * A drug the reference data files under NO code outside the locally applied groups takes the
	 * current-medication call, although every code of her order is one of those groups': no presentation
	 * outside them exists to be proposed, so her order's codes are the substance's own and say nothing
	 * about which presentation she was given. Review round 2 of PR #544's case, over the shipped knowledge
	 * base: the pool rig's Helen Roberts holds a {@code Salicylic acid} order mapped to {@code D01AE12} and
	 * {@code S01BC08}, and <em>"Can I give her salicylic acid?"</em> stated the Major methotrexate finding as a
	 * reason to withhold it while her own screen called the same chip her medication.
	 */
	@Test
	public void aDrugTheDataFilesOnlyUnderLocallyAppliedGroupsStatesTheCurrentMedicationCall() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = chartOf(service, coded("Salicylic acid", "D01AE12", "S01BC08"),
			coded("Methotrexate 2.5mg", "L01BA01", "L04AX03"));
		String question = "Can I give her salicylic acid?";
		assertTheQuestionsDrugIsHers(service, context, question);
		assertEveryCodeOfTheQuestionsDrugIsLocallyApplied(service, context, question);

		String finding = onlyFinding(service, context, question);
		assertTrue(finding.contains("Methotrexate") && finding.contains("Major"),
				"precondition: the Major salicylic acid-methotrexate rule is the finding: " + finding);
		assertTrue(finding.endsWith(CHANGE_CURRENT), finding);
		assertStatesTheCurrentMedicationCall(finding);
		assertEveryChipIsAboutACurrentMedication(service, context, question);
	}

	/**
	 * The same for a SYSTEMIC drug that ATC itself files under a locally applied group alone: her
	 * sulfasalazine tablets carry {@code A07EC01}, "Intestinal antiinflammatory agents", which is all the
	 * data files sulfasalazine under, so she is on the drug the question names. Asked in the issue's own
	 * wording.
	 */
	@Test
	public void aSystemicDrugTheDataFilesOnlyUnderALocallyAppliedGroupStatesTheCurrentMedicationCall() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = chartOf(service, coded("Sulfasalazine 500mg tablets", "A07EC01"),
			coded("Warfarin 5mg", "B01AA03"));
		String question = "Is it safe to add sulfasalazine for her?";
		assertTheQuestionsDrugIsHers(service, context, question);
		assertEveryCodeOfTheQuestionsDrugIsLocallyApplied(service, context, question);

		String finding = onlyFinding(service, context, question);
		assertTrue(finding.contains("Warfarin") && finding.contains("Major"),
				"precondition: the Major sulfasalazine-warfarin rule is the finding: " + finding);
		assertTrue(finding.endsWith(CHANGE_CURRENT), finding);
		assertStatesTheCurrentMedicationCall(finding);
		assertEveryChipIsAboutACurrentMedication(service, context, question);
	}

	/**
	 * The gate asks whether EVERY code of the order is locally applied, not whether one is: her aspirin
	 * order carries the three codes the 3.7.1 demo dictionary maps an aspirin concept to, one of them the
	 * stomatological {@code A01AD05}, and she is on the systemic drug the other two classify.
	 */
	@Test
	public void anOrderCarryingALocallyAppliedCodeBesideSystemicOnesStatesTheCurrentMedicationCall() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		String[] aspirinCodes = DrugReferenceTestSupport.ASPIRIN_ORDER_CODES.toArray(new String[0]);
		PatientClinicalContext context = chartOf(service, coded("Aspirin 81mg", aspirinCodes),
			coded("Warfarin 5mg", "B01AA03"));
		String question = "Is aspirin safe for her?";
		assertTheQuestionsDrugIsHers(service, context, question);
		boolean anyLocallyApplied = false;
		boolean everyLocallyApplied = true;
		for (String code : aspirinCodes) {
			anyLocallyApplied |= DrugReference.isLocallyAppliedAtcCode(code);
			everyLocallyApplied &= DrugReference.isLocallyAppliedAtcCode(code);
		}
		assertTrue(anyLocallyApplied && !everyLocallyApplied,
				"precondition: the order mixes a locally applied code with systemic ones, or this case cannot "
						+ "tell every code from any: " + Arrays.asList(aspirinCodes));

		boolean sawTheMajor = false;
		for (String finding : findings(service, context, question)) {
			sawTheMajor |= finding.contains("Warfarin") && finding.contains("Major");
			assertStatesTheCurrentMedicationCall(finding);
		}
		assertTrue(sawTheMajor, "precondition: the Major aspirin-warfarin rule is among the findings");
		assertEveryChipIsAboutACurrentMedication(service, context, question);
	}

	/**
	 * The ANSWER half of the referent, on the pass the wire publishes: a drug of hers that only the answer
	 * names states the current-medication referent on its chips, beside the question's own drug, which she
	 * does not take and which stays a proposal. The question alone raises no chip about her two drugs, so
	 * every chip about them here was raised because the answer named them.
	 */
	@Test
	public void aDrugOfHersOnlyTheAnswerNamesIsAboutACurrentMedicationOnTheChipsPass() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(ALIAS_FIXTURE);
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 40, null, "Simvastatin",
			"Clarithromycin");
		String question = "Is it safe to give her warfarin?";
		String answer = "Warfarin can be given. She also takes simvastatin and clarithromycin.";
		Set<String> hers = new HashSet<String>(Arrays.asList("simvastatin", "clarithromycin"));
		for (SafetyWarning chip : DrugReferenceTestSupport.validator(service).validate("", question, context)) {
			assertFalse(hers.contains(chip.getDrug().toLowerCase()),
					"precondition: the question alone raises no chip about her drugs: " + chip.getDetail());
		}

		Set<String> subjects = new HashSet<String>();
		for (SafetyWarning chip : DrugReferenceTestSupport.validator(service).validate(answer, question, context)) {
			String drug = chip.getDrug().toLowerCase();
			subjects.add(drug);
			if (hers.contains(drug)) {
				assertTrue(chip.isAboutACurrentMedication(),
						"only the answer named this drug, and it is one of her orders: " + chip.getDetail());
			}
			else {
				assertEquals("warfarin", drug, "the arrangement's third subject is the question's: " + chip.getDetail());
				assertFalse(chip.isAboutACurrentMedication(),
						"the question proposed warfarin and she does not take it: " + chip.getDetail());
			}
		}
		assertTrue(subjects.containsAll(hers) && subjects.contains("warfarin"),
				"precondition: the answer pass raised chips about both of her drugs and the question's: " + subjects);
	}

	/**
	 * The DOSE check runs in the same loop as every other site, so both of its sentences state the same
	 * referent: her own ibuprofen order, and a stated dose over one ceiling of the curated seed's band.
	 * Read off the chip, since an overdose finding never reaches the prompt's records.
	 */
	@Test
	public void aDoseWarningAboutADrugInPlayThatIsHerOwnOrderIsAboutACurrentMedication() {
		SafetyWarning daily = overdoseChip(DAILY_EXCESS,
			ibuprofenChart(60, null, DrugReferenceTestSupport.IBUPROFEN_ORDER), "mg/day maximum");
		SafetyWarning perDose = overdoseChip(PER_DOSE_EXCESS,
			ibuprofenChart(5, 20.0, DrugReferenceTestSupport.IBUPROFEN_ORDER), "per-dose maximum");

		assertTrue(daily.isAboutACurrentMedication(), "the dose is of her own ibuprofen order: " + daily.getDetail());
		assertTrue(perDose.isAboutACurrentMedication(),
				"the dose is of her own ibuprofen order: " + perDose.getDetail());
	}

	/** And the dose check's negative control: the same doses of a drug she does not take are proposals. */
	@Test
	public void aDoseWarningAboutADrugSheDoesNotTakeIsNotAboutACurrentMedication() {
		SafetyWarning daily = overdoseChip(DAILY_EXCESS, ibuprofenChart(60, null, null), "mg/day maximum");
		SafetyWarning perDose = overdoseChip(PER_DOSE_EXCESS, ibuprofenChart(5, 20.0, null), "per-dose maximum");

		assertFalse(daily.isAboutACurrentMedication(), "she is on no ibuprofen: " + daily.getDetail());
		assertFalse(perDose.isAboutACurrentMedication(), "she is on no ibuprofen: " + perDose.getDetail());
	}

	/**
	 * Her orders resolving to a substance is not her taking it, where the resolution is one of several
	 * readings of one name: review round 3 of PR #544. The shipped knowledge base files the brand
	 * {@code Nexium} under Omeprazole AND Esomeprazole, so her one {@code Nexium 40mg} order resolves to both
	 * (the shape {@code AmbiguousBrandNamedOrderTest} records), and <em>"Can I give her omeprazole?"</em>
	 * stated the Major clopidogrel finding as a reason to change her medication — the refusal of a drug she
	 * does not take, instructed away. The name names neither substance, so it establishes neither. Asked
	 * twice: over the brand alone, and over the order an {@code en} session records, whose concept name
	 * {@code Esomeprazole magnesium} names Esomeprazole and not Omeprazole.
	 */
	@Test
	public void aDrugHerOrdersNameResolvesWithoutNamingItStatesTheProposalCall() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		Set<Object> asked = substancesAskedAbout(service, OMEPRAZOLE_QUESTION);
		Set<Object> readings = substancesImpliedBy(service, "Nexium 40mg");
		assertEquals(2, readings.size(), "precondition: the brand resolves two substances: " + readings);
		assertTrue(readings.containsAll(asked), "precondition: omeprazole is one of them: " + readings);
		List<PatientClinicalContext.ActiveDrugOrder> arrangements = Arrays.asList(coded("Nexium 40mg"),
			writtenAgainst(null, "Nexium 40mg", "Esomeprazole magnesium"));
		for (PatientClinicalContext.ActiveDrugOrder nexium : arrangements) {
			PatientClinicalContext context = chartOf(service, nexium, coded("Clopidogrel 75mg"));
			String where = "her order records " + nexium.getNames();
			assertHerOrdersResolveTheQuestionsDrug(service, context, OMEPRAZOLE_QUESTION);

			boolean sawTheMajor = false;
			for (String finding : findings(service, context, OMEPRAZOLE_QUESTION)) {
				if (finding.contains("Clopidogrel") && finding.contains("Major")) {
					sawTheMajor = true;
					assertTrue(finding.endsWith(WITHHOLD), where + ": " + finding);
				}
				assertStatesTheProposalCall(finding, where);
			}
			assertTrue(sawTheMajor, "precondition: the Major omeprazole-clopidogrel rule is among the findings ("
					+ where + ")");
			assertNoChipIsAboutACurrentMedication(service, context, OMEPRAZOLE_QUESTION);
		}
	}

	/** The other value of that name leg, on the {@code en} order above: its concept name names Esomeprazole,
	 *  so a question about esomeprazole is about her medication. */
	@Test
	public void aDrugANameOfHerOrderNamesStatesTheCurrentMedicationCall() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		String question = "Can I give her esomeprazole?";
		assertEquals(2, substancesImpliedBy(service, "Esomeprazole magnesium").size(),
				"precondition: the concept name resolves two substances, so naming is what tells them apart");
		PatientClinicalContext context = chartOf(service,
			writtenAgainst(null, "Nexium 40mg", "Esomeprazole magnesium"), coded("Clopidogrel 75mg"));
		assertTheQuestionsDrugIsHers(service, context, question);

		boolean sawTheMajor = false;
		for (String finding : findings(service, context, question)) {
			sawTheMajor |= finding.contains("Clopidogrel") && finding.contains("Major");
			assertStatesTheCurrentMedicationCall(finding);
		}
		assertTrue(sawTheMajor, "precondition: the Major esomeprazole-clopidogrel rule is among the findings");
		assertEveryChipIsAboutACurrentMedication(service, context, question);
	}

	/**
	 * The CODE leg's counterpart: the shipped knowledge base files {@code A02BC05}, esomeprazole's code, on
	 * its Omeprazole row too (issue #185's premise), so an {@code Esomeprazole 40mg} order the dictionary
	 * mapped to it resolves to omeprazole as well. A code the loaded data files under two substances names
	 * neither, and this order's name reaches esomeprazole alone.
	 */
	@Test
	public void aDrugHerOrdersCodeIsFiledUnderBesideAnotherSubstanceStatesTheProposalCall() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = chartOf(service, coded("Esomeprazole 40mg", "A02BC05"),
			coded("Clopidogrel 75mg"));
		Set<Object> asked = substancesAskedAbout(service, OMEPRAZOLE_QUESTION);
		assertTrue(Collections.disjoint(substancesImpliedBy(service, "Esomeprazole 40mg"), asked),
				"precondition: the order's name does not reach omeprazole, so only its code can");
		Set<Object> filedUnder = new HashSet<Object>();
		for (DrugReference entry : service.findForActiveOrders(context)) {
			if (entry.normalizedAtcCodes().contains("A02BC05")) {
				filedUnder.add(entry.substanceGroupKey());
			}
		}
		assertTrue(filedUnder.size() > 1 && filedUnder.containsAll(asked),
				"precondition: her orders' resolution files the code under omeprazole and another substance: "
						+ filedUnder);
		assertHerOrdersResolveTheQuestionsDrug(service, context, OMEPRAZOLE_QUESTION);

		String finding = onlyFinding(service, context, OMEPRAZOLE_QUESTION);
		assertTrue(finding.contains("Clopidogrel") && finding.contains("Major"),
				"precondition: the Major omeprazole-clopidogrel rule is the finding: " + finding);
		assertTrue(finding.endsWith(WITHHOLD), finding);
		assertStatesTheProposalCall(finding, "an esomeprazole order coded A02BC05");
		assertNoChipIsAboutACurrentMedication(service, context, OMEPRAZOLE_QUESTION);
	}

	/** The other value of the code leg: an order the data knows only by a code it files under ONE substance
	 *  is that substance's, so a question about it is about her medication. */
	@Test
	public void aDrugHerOrdersCodeIsFiledUnderAloneStatesTheCurrentMedicationCall() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		String question = "Can I give her clopidogrel?";
		PatientClinicalContext context = chartOf(service, coded("Tab 75mg", "B01AC04"),
			coded("Esomeprazole 40mg"));
		assertTrue(substancesImpliedBy(service, "Tab 75mg").isEmpty(),
				"precondition: the order's name reaches nothing, so only its code can");
		Set<Object> filedUnder = new HashSet<Object>();
		for (DrugReference entry : service.findForActiveOrders(context)) {
			if (entry.normalizedAtcCodes().contains("B01AC04")) {
				filedUnder.add(entry.substanceGroupKey());
			}
		}
		assertEquals(substancesAskedAbout(service, question), filedUnder,
				"precondition: her orders' resolution files the code under clopidogrel alone");
		assertTheQuestionsDrugIsHers(service, context, question);

		String finding = onlyFinding(service, context, question);
		assertTrue(finding.contains("Esomeprazole") && finding.contains("Major"),
				"precondition: the Major clopidogrel-esomeprazole rule is the finding: " + finding);
		assertTrue(finding.endsWith(CHANGE_CURRENT), finding);
		assertStatesTheCurrentMedicationCall(finding);
		assertEveryChipIsAboutACurrentMedication(service, context, question);
	}

	/**
	 * The BRIDGE leg's counterpart: an {@code Inexium 40mg} order written against CIEL 75876, which the shipped
	 * bridge files on Omeprazole and Esomeprazole under the name {@code Esomeprazole magnesium}. That name
	 * names Esomeprazole alone, which is the refusal issue #353 already makes before a finding prints
	 * {@code Omeprazole from Inexium 40mg}; a question about omeprazole is still a proposal.
	 */
	@Test
	public void aDrugHerOrdersConceptIsFiledOnWithoutItsBridgeNamingItStatesTheProposalCall() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = chartOf(service,
			writtenAgainst(ESOMEPRAZOLE_MAGNESIUM_CONCEPT, "Inexium 40mg"), coded("Clopidogrel 75mg"));
		assertTrue(substancesImpliedBy(service, "Inexium 40mg").isEmpty(),
				"precondition: the order's name reaches nothing and it carries no code, so only its concept can");
		assertHerOrdersResolveTheQuestionsDrug(service, context, OMEPRAZOLE_QUESTION);

		String finding = onlyFinding(service, context, OMEPRAZOLE_QUESTION);
		assertTrue(finding.contains("Clopidogrel") && finding.contains("Major"),
				"precondition: the Major omeprazole-clopidogrel rule is the finding: " + finding);
		assertTrue(finding.endsWith(WITHHOLD), finding);
		assertStatesTheProposalCall(finding, "an order written against CIEL 75876");
		assertNoChipIsAboutACurrentMedication(service, context, OMEPRAZOLE_QUESTION);
	}

	/** The other value of the bridge leg, on the same chart: the bridge's name names Esomeprazole. */
	@Test
	public void aDrugHerOrdersBridgeNamesStatesTheCurrentMedicationCall() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		String question = "Can I give her esomeprazole?";
		PatientClinicalContext context = chartOf(service,
			writtenAgainst(ESOMEPRAZOLE_MAGNESIUM_CONCEPT, "Inexium 40mg"), coded("Clopidogrel 75mg"));
		assertTheQuestionsDrugIsHers(service, context, question);

		String finding = onlyFinding(service, context, question);
		assertTrue(finding.contains("Clopidogrel") && finding.contains("Major"),
				"precondition: the Major esomeprazole-clopidogrel rule is the finding: " + finding);
		assertTrue(finding.endsWith(CHANGE_CURRENT), finding);
		assertStatesTheCurrentMedicationCall(finding);
		assertEveryChipIsAboutACurrentMedication(service, context, question);
	}

	/**
	 * A biosimilar's concept filed on three substances: review round 3's starker case. Her {@code Ogivri 150mg}
	 * order is written against the {@code Trastuzumab-dkst} concept, which the shipped bridge files on
	 * Trastuzumab, Trastuzumab deruxtecan and Trastuzumab emtansine, and whose name names Trastuzumab alone.
	 * <em>"Can I give her trastuzumab deruxtecan?"</em> puts trastuzumab in play beside it: every finding about
	 * the conjugate she is not on states the proposal, and every finding about the trastuzumab she is on
	 * states her medication.
	 */
	@Test
	public void aBiosimilarsConceptMakesHersOnlyTheSubstanceItsBridgeNames() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		String question = "Can I give her trastuzumab deruxtecan?";
		PatientClinicalContext context = chartOf(service, writtenAgainst(TRASTUZUMAB_DKST_CONCEPT, "Ogivri 150mg"),
			coded("Clozapine 100mg"));
		assertHerOrdersResolveTheQuestionsDrug(service, context, question);
		Set<Object> byItsName = substancesImpliedBy(service, "Ogivri 150mg");
		Set<String> conjugate = new HashSet<String>();
		Set<String> parent = new HashSet<String>();
		for (DrugReference entry : service.findImpliedByQuery(question)) {
			(byItsName.contains(entry.substanceGroupKey()) ? parent : conjugate).add(entry.getName());
		}
		assertEquals(new HashSet<String>(Arrays.asList("Trastuzumab deruxtecan")), conjugate,
				"precondition: the order's name reaches trastuzumab and not the conjugate, so only the concept "
						+ "reaches the conjugate");
		assertEquals(new HashSet<String>(Arrays.asList("Trastuzumab")), parent,
				"precondition: the question puts trastuzumab in play too");

		boolean sawTheConjugate = false;
		boolean sawTheParent = false;
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(DrugReferenceTestSupport
				.injectorWithSafety(service).injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question))) {
			if (finding.getText().startsWith("Safety finding — Trastuzumab deruxtecan:")) {
				sawTheConjugate = true;
				assertStatesTheProposalCall(finding.getText(), "the conjugate");
			} else {
				sawTheParent |= finding.getText().startsWith("Safety finding — Trastuzumab:");
				assertStatesTheCurrentMedicationCall(finding.getText(), "trastuzumab");
			}
		}
		assertTrue(sawTheConjugate && sawTheParent,
				"precondition: findings about both drugs in play reached the prompt");
		for (SafetyWarning chip : DrugReferenceTestSupport.validator(service).validate("", question, context)) {
			assertEquals(conjugate.contains(chip.getDrug()) ? Boolean.FALSE : Boolean.TRUE,
				Boolean.valueOf(chip.isAboutACurrentMedication()),
				"only the chips about trastuzumab are about her medication: " + chip.getDetail());
		}
	}

	/**
	 * The referent narrows {@code findForActiveOrders}' answer and never widens it: a drug that resolution
	 * leaves out is not hers, whatever an order's own names establish. The resolution reads a context's
	 * FLATTENED names, which the builder unions from its orders; a caller-built context can leave an order's
	 * name out of them, and there every other consumer of her orders — the screen, the class arm, the
	 * ended-order holder — reads her as not on that drug, so a referent saying she is would be the two
	 * disagreeing resolutions issue #151 forbids.
	 */
	@Test
	public void aDrugTheResolutionOfHerOrdersLeavesOutIsNotHersWhateverAnOrderRecords() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(ALIAS_FIXTURE);
		String question = "Is it safe to give simvastatin?";
		PatientClinicalContext context = service.withReferenceNames(DrugReferenceTestSupport.ctx(40, null,
			DrugReferenceTestSupport.set("Clarithromycin"), null, null, null,
			Arrays.asList(coded("Simvastatin"), coded("Clarithromycin"))));
		assertFalse(substancesOf(service, context).containsAll(substancesAskedAbout(service, question)),
				"precondition: the resolution of her orders leaves simvastatin out");

		String finding = onlyFinding(service, context, question);
		assertTrue(finding.toLowerCase().contains("major"),
				"precondition: the Major simvastatin-clarithromycin rule is the finding: " + finding);
		assertTrue(finding.endsWith(WITHHOLD), finding);
		assertStatesTheProposalCall(finding, "a drug the resolution of her orders leaves out");
	}

	/**
	 * The presentation gate asks the orders that ESTABLISH she takes the drug, and no other: an order her
	 * resolution reaches the drug through without establishing it is not her presentation of it. Beside her
	 * {@code Hydrocortisone cream 1%}, filed {@code D07AA02}, she holds an order known only by
	 * {@code H02AB09}, which the shipped knowledge base files under hydrocortisone and under its butyrate
	 * ester alike, so the module cannot say that order is hydrocortisone; its systemic code does not stop an
	 * oral question from being a proposal. That is the conservative direction, and the one the referent
	 * takes of the order itself: the same arrangement asked with the tablet NAMED is her medication, the
	 * case {@code aLocallyAppliedOrderBesideASystemicOrderOfTheSameDrugStatesTheCurrentMedicationCall} holds
	 * for diclofenac.
	 */
	@Test
	public void anOrderThatDoesNotEstablishTheDrugIsNotAskedWhichPresentationSheTakes() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		String question = "Can I start her on oral hydrocortisone?";
		PatientClinicalContext context = chartOf(service, coded("Hydrocortisone cream 1%", "D07AA02"),
			coded("Tab 20mg", "H02AB09"), coded("Warfarin 5mg", "B01AA03"));
		assertTheQuestionsDrugIsHers(service, context, question);
		assertTrue(substancesImpliedBy(service, "Tab 20mg").isEmpty(),
				"precondition: the tablet's name reaches nothing, so only its code can");
		Set<Object> filedUnder = new HashSet<Object>();
		for (DrugReference entry : service.findForActiveOrders(context)) {
			if (entry.normalizedAtcCodes().contains("H02AB09")) {
				filedUnder.add(entry.substanceGroupKey());
			}
		}
		assertTrue(filedUnder.size() > 1 && filedUnder.containsAll(substancesAskedAbout(service, question)),
				"precondition: her orders' resolution files the systemic code under hydrocortisone and another "
						+ "substance: " + filedUnder);
		assertFalse(DrugReference.isLocallyAppliedAtcCode("H02AB09"),
				"precondition: the tablet's code is a systemic one, so it would keep the drug hers if it counted");

		String finding = onlyFinding(service, context, question);
		assertTrue(finding.contains("Warfarin"), "precondition: the hydrocortisone-warfarin rule is the finding: "
				+ finding);
		assertStatesTheProposalCall(finding, "a cream beside an order the module cannot say is hydrocortisone");
		assertNoChipIsAboutACurrentMedication(service, context, question);
	}

	/**
	 * A drug the question LISTS as current is not held to the presentation gate: issue #513 item 1, which the
	 * gate reopened for a drug she holds only as a gel (review round 3 of PR #544). The question says she is
	 * on diclofenac and proposes amoxicillin, so no presentation of diclofenac is being proposed; stated as a
	 * proposal, the Major warfarin-diclofenac pair was a reason to withhold diclofenac on one chip and a
	 * reason to change her medication on the other, and the prompt's ranking sentence hands the lead to the
	 * withholding one.
	 */
	@Test
	public void aDrugTheQuestionListsAsCurrentIsNotHeldToThePresentationGate() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		String question = "The patient is currently on diclofenac and warfarin, is it safe to give amoxicillin?";
		PatientClinicalContext context = chartOf(service, coded("Voltaren gel", "M02AA15"),
			coded("Warfarin 5mg", "B01AA03"));
		Set<String> listed = new HashSet<String>();
		for (DrugReference entry : DrugReferenceInjector.listedBeforeTheProposal(question,
			service.findImpliedByQuery(question))) {
			listed.add(entry.getName());
		}
		assertTrue(listed.contains("Diclofenac") && listed.contains("Warfarin"),
				"precondition: the question lists diclofenac and warfarin as current: " + listed);
		Set<Object> diclofenac = substancesAskedAbout(service, "Can I start her on oral diclofenac?");
		assertTrue(substancesOf(service, context).containsAll(diclofenac),
				"precondition: her gel resolves to diclofenac");
		assertTrue(aDrugSheHoldsOnlyAsAGelIsAProposalOnAnOralQuestion(service, context),
				"precondition: the same chart states the proposal for an oral course, so the gate fires there");

		boolean sawThePair = false;
		for (String finding : findings(service, context, question)) {
			if (finding.startsWith("Safety finding — Amoxicillin:")) {
				assertStatesTheProposalCall(finding, "the proposed amoxicillin");
				continue;
			}
			sawThePair |= finding.contains("Major");
			assertStatesTheCurrentMedicationCall(finding, "a listed drug");
		}
		assertTrue(sawThePair, "precondition: the Major warfarin-diclofenac pair is among the findings");
		for (SafetyWarning chip : DrugReferenceTestSupport.validator(service).validate("", question, context)) {
			assertEquals(Boolean.valueOf(!"Amoxicillin".equals(chip.getDrug())),
				Boolean.valueOf(chip.isAboutACurrentMedication()),
				"the listed drugs' chips are about her medication and the proposed drug's is not: " + chip.getDetail());
		}
	}

	/**
	 * The same listing, with a proposal clause in the drug-then-patient order issue #548 admitted to the
	 * proposal grammar (ADR Decision 125): <em>"… is it safe to add amoxicillin for her?"</em> is a proposal
	 * clause too, so the drugs before it are LISTED and her gel diclofenac keeps the current-medication
	 * call. Before that widening the question listed nothing, and the gel was held to the presentation gate.
	 */
	@Test
	public void aListingBeforeAProposalNamingThePatientAfterTheDrugIsStillAListing() {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
			DrugReferenceTestSupport.shippedEntries());
		String question = "The patient is currently on diclofenac and warfarin, is it safe to add amoxicillin for her?";
		PatientClinicalContext context = chartOf(service, coded("Voltaren gel", "M02AA15"),
			coded("Warfarin 5mg", "B01AA03"));

		boolean sawThePair = false;
		for (String finding : findings(service, context, question)) {
			if (finding.startsWith("Safety finding — Amoxicillin:")) {
				assertStatesTheProposalCall(finding, "the proposed amoxicillin");
				continue;
			}
			sawThePair |= finding.contains("Major");
			assertStatesTheCurrentMedicationCall(finding, "a listed drug");
		}
		assertTrue(sawThePair, "precondition: the Major warfarin-diclofenac pair is among the findings");
		for (SafetyWarning chip : DrugReferenceTestSupport.validator(service).validate("", question, context)) {
			assertEquals(Boolean.valueOf(!"Amoxicillin".equals(chip.getDrug())),
				Boolean.valueOf(chip.isAboutACurrentMedication()),
				"the listed drugs' chips are about her medication and the proposed drug's is not: " + chip.getDetail());
		}
	}

	private static Set<Object> substancesOf(DrugReferenceService service, PatientClinicalContext context) {
		return DrugSafetyValidator.substancesOf(service.findForActiveOrders(context));
	}

	/** Whether the gel chart raises findings on an oral-diclofenac question and states the proposal call on
	 *  every one of them — the gate firing. */
	private static boolean aDrugSheHoldsOnlyAsAGelIsAProposalOnAnOralQuestion(DrugReferenceService service,
			PatientClinicalContext context) {
		List<String> findings = findings(service, context, "Can I start her on oral diclofenac?");
		for (String finding : findings) {
			if (!finding.endsWith(WITHHOLD) && !finding.endsWith(CAUTION)) {
				return false;
			}
		}
		return !findings.isEmpty();
	}
}
