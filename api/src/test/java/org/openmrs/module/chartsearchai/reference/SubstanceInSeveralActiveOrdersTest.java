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
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Whether a drug in play that the patient already receives through MORE THAN ONE active order is
 * said to be — issue #477.
 *
 * <p><b>The defect.</b> The class arm's restating-existing-therapy skip
 * ({@code DrugSafetyValidator.classRelationships}) is right for one co-medication and blind to a
 * second: every order of one substance is one co-medication (issue #186), so a patient on two
 * tuberculosis combinations that both contain rifampicin, asked about rifampicin, heard that it
 * interacts with the pyrazinamide IN those combinations — and nothing saying she already receives
 * rifampicin, twice.
 *
 * <p><b>What decides that an order carries the substance</b> is its DISPLAY — the name the chip
 * prints — through {@link DrugReferenceService#findNamedSubstances}, never another of its recorded
 * names (issue #293's rule) and never a shared ATC code alone (this knowledge base files
 * {@code Omeprazole} under esomeprazole's code). Both are pinned below by a case that would name an
 * order which does not contain the substance.
 */
public class SubstanceInSeveralActiveOrdersTest {

	/** Verbatim shipped-KB rows for the four first-line TB substances and every KB pair among them —
	 *  see the fixture's own {@code metadata.note}. */
	private static final String FIXTURE = "chartsearchai-test/ddi-substance-in-several-orders.json";

	/** The #185 fixture, whose Omeprazole row publishes only esomeprazole's code. */
	private static final String PPI_FIXTURE = "chartsearchai-test/ddi-duplicate-therapy-self.json";

	/** The two order displays of the issue's reproduction, verbatim. */
	private static final String RHZ = "Isoniazid / pyrazinamide / rifampin";

	private static final String RHZE = "Rifampicin isoniazid pyrazinamide and ethambutol 150/75/400/275mg";

	private static final String QUESTION = "Is it safe to give rifampicin?";

	/** The finding this issue adds, for the reproduction — the full detail, so a reword is a decision. */
	private static final String RIFAMPICIN_IN_BOTH = "Rifampicin (rifampin) is already in active orders "
			+ RHZ + " and " + RHZE + " — possible duplicate therapy";

	/** The finding that her two orders share a substance, which a question putting a drug in play states
	 *  too, after every other finding (issue #477's decision comment; {@code OrdersSharingASubstanceTest}). */
	private static final String SHARED_BY_BOTH = "Isoniazid, Pyrazinamide and Rifampicin (rifampin) are in"
			+ " active orders " + RHZ + " and " + RHZE + " — possible duplicate therapy";

	/** The phrase the finding is recognised by in the cases asserting its ABSENCE. Taken from the full
	 *  sentence above rather than spelled again, so a reword of the one cannot leave the other asserting
	 *  the absence of a string production no longer emits. */
	private static final String ALREADY_IN = RIFAMPICIN_IN_BOTH.substring(
			"Rifampicin (rifampin) ".length(), "Rifampicin (rifampin) is already in active order".length());

	/** Pinned as literals rather than read off {@code DrugReferenceInjector}'s constants — the clause is
	 *  what the model reads, and a test comparing a constant to itself stays green through a reword. */
	private static final String CHANGE_CURRENT =
			"This finding is a reason to change a medication this patient is already taking.";

	private static final String WITHHOLD = "This finding is a reason to withhold it.";

	private static final String CAUTION = "This finding is a caution to note, not a reason to withhold it.";

	private static final String CAUTION_CURRENT = "This finding is a caution about a medication this patient"
			+ " is already taking, not a reason to change it.";

	@Test
	public void aDrugInPlayThatTwoOfHerOrdersContainIsNamedAsAlreadyTakenInBoth() throws IOException {
		List<SafetyWarning> warnings = chips(FIXTURE, QUESTION, twoTuberculosisCombinations());

		// The whole response, so the finding's POSITION is decided rather than incidental, and so the
		// chips this issue must not move are held too: the Major against pyrazinamide and the Minor
		// against isoniazid are the reference data's relationships with her regimen's OTHER
		// constituents, which the issue leaves standing. Last, the finding that her two orders share
		// those substances, which a question putting a drug in play states too (ADR Decision 116).
		assertEquals(Arrays.asList(
				"Rifampicin (rifampin) interacts with active order Pyrazinamide — Major. A two-month regimen"
						+ " consisting of rifampin (RIF) and pyrazinamide (PZA) for the treatment of latent"
						+ " tuberculosis infection (LTBI) has been associated with liver injury resulting in high"
						+ " rates of hospitalization and death. The exact mechanism of interaction is unknown,"
						+ " although both agents are individually hepatotoxic and may have additive effects on"
						+ " the liver during coadministration.",
				"Rifampicin (rifampin) interacts with active order Isoniazid — Minor. The risk of"
						+ " hepatotoxicity is greater when rifampin and isoniazid are given concomitantly than"
						+ " when either drug is given alone. Rifampin appears to alter the metabolism of isoniazid"
						+ " and increase the amount of toxic metabolites. Theoretically, a similar reaction may"
						+ " occur with rifabutin and isoniazid. Patients who are elderly, have hepatic impairment,"
						+ " are slow acetylators of isoniazid, drink alcohol daily, are female, or are taking"
						+ " other strong CYP450-inducing agents may be at greater risk of hepatotoxicity.",
				RIFAMPICIN_IN_BOTH, SHARED_BY_BOTH),
				DrugReferenceTestSupport.details(warnings));
		SafetyWarning finding = warnings.get(2);
		assertEquals(Arrays.asList(RHZ, RHZE), finding.namedPartners(),
				"the orders the finding names, structurally — every interaction chip states them");
		assertEquals(SafetyWarning.TYPE_INTERACTION, finding.getType());
		assertEquals(null, finding.getSeverity(), "nothing rates this relationship");
		assertTrue(finding.isAboutACurrentMedication(),
				"the drug-in-play arm states ONE referent for the drug in play at every site, and rifampicin is "
						+ "in her own orders: one finding stating another call beside the rule chips is issue "
						+ "#402's reverted one-site shape (ADR Decisions 112, 121)");
	}

	@Test
	public void theFindingIsNotCountedAsAnInteractionPairFound() throws IOException {
		// interactionPairs counts rated rule PAIRS, and this finding relates the drug to no partner
		// substance: counted, it would publish three pairs found beside two rule chips.
		PairChipExtent.Sink sink = new PairChipExtent.Sink();
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(
				DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.validate("", QUESTION, twoTuberculosisCombinations(), null, null, sink);

		assertEquals(1, alreadyIn(warnings).size(),
				"precondition: the finding was raised in this pass: " + warnings);
		assertEquals(2, sink.stated().getFound(), "the two rule pairs and nothing else");
		assertEquals(2, sink.stated().getReported());
	}

	@Test
	public void oneOrderContainingItIsStillRestatingExistingTherapy() throws IOException {
		// The skip's own case, issue #185: one order carrying the substance is the drug itself, and
		// saying it duplicates itself is the defect that issue removed.
		PatientClinicalContext context = contextOf(DrugReferenceTestSupport.activeOrder("order-rhz", RHZ));

		assertNoneAlreadyIn(chips(FIXTURE, QUESTION, context));
	}

	@Test
	public void twoOrdersReachedThroughChartCodesTheDataCannotNameAreStillBothNamed() throws IOException {
		// The other rung of the co-medication walk: combination codes the knowledge base carries no row
		// for, so each order is its own co-medication keyed on the ORDER, and each is skipped. What
		// the finding reads is still the orders' displays, so the rung does not change the sentence.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null,
				DrugReferenceTestSupport.set(RHZ, RHZE), DrugReferenceTestSupport.set("J04AM05", "J04AM06"),
				null, null,
				Arrays.asList(
						DrugReferenceTestSupport.activeOrder("order-rhz", RHZ, DrugReferenceTestSupport.set(RHZ),
								DrugReferenceTestSupport.set("J04AM05")),
						DrugReferenceTestSupport.activeOrder("order-rhze", RHZE,
								DrugReferenceTestSupport.set(RHZE), DrugReferenceTestSupport.set("J04AM06"))));

		assertTrue(DrugReferenceTestSupport.details(chips(FIXTURE, QUESTION, context)).contains(RIFAMPICIN_IN_BOTH),
				"was: " + DrugReferenceTestSupport.details(chips(FIXTURE, QUESTION, context)));
	}

	@Test
	public void anOrderWhoseOtherRecordedNameNamesTheDrugButWhoseDisplayDoesNotIsNotCounted()
			throws IOException {
		// Issue #293's shape: a recorded name the chip does not print — a clinician's free text — names
		// rifampicin, the display names isoniazid. Counting it would print "Rifampicin is already in
		// active orders … Isoniazid 300mg", a claim about a prescription the display says is something
		// else.
		PatientClinicalContext context = contextOf(
				DrugReferenceTestSupport.activeOrder("order-rhz", RHZ),
				DrugReferenceTestSupport.activeOrder("order-inh", "Isoniazid 300mg", "Rifampicin 150mg"));

		assertNoneAlreadyIn(chips(FIXTURE, QUESTION, context));
	}

	@Test
	public void anOrderTheModuleCouldReadNoNameForIsNotCounted() throws IOException {
		// An order the module read no name for (issue #290) is asked hasKnownName and refused, whatever
		// string sits in its display. The builder labels such an order by its codes; a display that
		// happens to spell a drug is what makes the refusal, and not the display's emptiness,
		// observable here.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(40, null,
				DrugReferenceTestSupport.set(RHZ), DrugReferenceTestSupport.set("J04AB02"), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-rhz", RHZ),
						PatientClinicalContext.ActiveDrugOrder.namedByCodesOnly("order-codes", "Rifampicin 150mg",
								DrugReferenceTestSupport.set("J04AB02"))));

		assertNoneAlreadyIn(chips(FIXTURE, QUESTION, context));
	}

	@Test
	public void aSharedAtcCodeAloneDoesNotPutTheSubstanceInAnOrder() throws IOException {
		// This knowledge base files Omeprazole under A02BC05, which is esomeprazole's code, so an
		// Esomeprazole order carrying it resolves by CODE to the Omeprazole row. Counting that would
		// tell a patient on omeprazole and esomeprazole that omeprazole is in both.
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Omeprazole 20mg", "Esomeprazole 40mg"),
				DrugReferenceTestSupport.set("A02BC01", "A02BC05"), null, null,
				Arrays.asList(
						DrugReferenceTestSupport.activeOrder("order-ome", "Omeprazole 20mg",
								DrugReferenceTestSupport.set("Omeprazole 20mg"), DrugReferenceTestSupport.set("A02BC01")),
						DrugReferenceTestSupport.activeOrder("order-eso", "Esomeprazole 40mg",
								DrugReferenceTestSupport.set("Esomeprazole 40mg"),
								DrugReferenceTestSupport.set("A02BC05"))));

		assertNoneAlreadyIn(chips(PPI_FIXTURE, "Is it safe to give omeprazole?", context));
	}

	@Test
	public void aBrandTheDataFilesUnderTwoSubstancesNamesNeither() {
		// Over the shipped knowledge base, where Nexium is an alias of BOTH the Omeprazole and the
		// Esomeprazole rows: the name IMPLIES omeprazole and NAMES neither, so "Omeprazole is already in
		// active orders Nexium 40mg and Omeprazole 20mg" would assert a constituent the prescription's
		// own name cannot tell apart from another (issue #392's shape).
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
				DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 60, null,
				"Nexium 40mg", "Omeprazole 20mg");

		assertNoneAlreadyIn(DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give omeprazole?", context));
	}

	@Test
	public void aBrandTheDataFilesUnderSeveralRowsOfOneSubstanceStillNamesIt() {
		// Acticlate is a brand of doxycycline only, and the shipped knowledge base files doxycycline as
		// several rows. Handed every row, findNamedSubstances sees the rows tie with EACH OTHER and names
		// nothing; handed one row per substance — its own contract — it names doxycycline.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
				DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 60, null,
				"Acticlate", "Doxycycline 100mg");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"Is it safe to give doxycycline?", context);

		List<SafetyWarning> found = alreadyIn(warnings);
		assertEquals(1, found.size(), "was: " + DrugReferenceTestSupport.details(warnings));
		assertEquals(Arrays.asList("Acticlate", "Doxycycline 100mg"), found.get(0).namedPartners());
	}

	@Test
	public void twoOrdersRecordedUnderOneNameAreNamedOnceWithTheirCount() throws IOException {
		// Printed twice, one name reads as a paste error rather than as two prescriptions — and would be
		// counted twice into findingPartners, whose unstated list counts it once.
		PatientClinicalContext context = contextOf(
				DrugReferenceTestSupport.activeOrder("order-rif-1", "Rifampicin 150mg"),
				DrugReferenceTestSupport.activeOrder("order-rif-2", "Rifampicin 150mg"));

		List<SafetyWarning> found = alreadyIn(chips(FIXTURE, QUESTION, context));

		assertEquals(1, found.size());
		assertEquals("Rifampicin (rifampin) is already in active orders Rifampicin 150mg (2 orders)"
				+ " — possible duplicate therapy", found.get(0).getDetail());
		// That the sentence is ONE claim to the citation-fidelity check is pinned through that check:
		// ActiveOrderCitationFidelityTest.theModulesOwnAlreadyInSeveralOrdersSentenceIsOneClaim.
		assertEquals(Arrays.asList("Rifampicin 150mg"), found.get(0).namedPartners());
	}

	@Test
	public void theReproductionOverTheShippedKnowledgeBaseNamesBothCombinations() {
		// The issue's six orders over the whole knowledge base, where every competing claimant
		// findImpliedByDrugName and nameMatchStrength rank against is present.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWithGroups(
				DrugReferenceTestSupport.shippedEntries());
		PatientClinicalContext context = DrugReferenceTestSupport.contextNaming(service, 40, 60.0,
				"Lamivudine / zidovudine", "Efavirenz", "Cotrimoxazole 960mg", RHZ, RHZE, "Stavudine");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
				"The patient is currently on Lamivudine / zidovudine, Efavirenz, Trimethoprim and"
						+ " sulfamethoxazole is it safe to give Rifampicin?",
				context);
		List<String> details = DrugReferenceTestSupport.details(warnings);

		assertTrue(details.contains(RIFAMPICIN_IN_BOTH), "was: " + details);
		assertEquals(1, alreadyIn(warnings).size(),
				"and only rifampicin is carried by two orders among the drugs this question names: "
						+ details);
	}

	@Test
	public void everyFindingAboutTheDrugInPlayReachesTheModelInOneReferent() throws IOException {
		// Through the real injector, the whole finding list in order: the Major, the Minor and this
		// issue's finding each state ONE column, and since issue #402 it is the CURRENT-MEDICATION one,
		// because rifampicin is in her own orders (ADR Decision 121) — the Major and the new finding the
		// change call, the Minor the current-medication caution. Review round 1 of #477 found the new
		// finding alone in the other column, so one response refused rifampicin as a proposal and, two
		// findings later, called it a medication to change (ADR Decision 112). Unrated, the new finding
		// keeps the default an unrated relationship has (Decision 86 graded down only shared
		// classification), so it states the withholding class and is never the caution. The fourth is
		// the finding that her two orders share a substance, about her own therapy (ADR Decision 116).
		assertEquals(Arrays.asList(CHANGE_CURRENT, CAUTION_CURRENT, CHANGE_CURRENT, CHANGE_CURRENT),
				clauses(QUESTION));
	}

	@Test
	public void theIssuesOwnQuestionWhichTheGrammarDoesNotReadAsAProposalStatesTheSameReferent()
			throws IOException {
		// The ticket's question names four drugs before rifampicin, and QueryScopeRouter's closed
		// proposal grammar does not admit it — so issue #472's gate would treat it as proposing nothing.
		// The referent here is not that gate's: the arm states the current-medication column for a drug in
		// play her orders resolve to on any question (issue #402), and this finding states what its
		// siblings state. The fourth is her two orders sharing a substance, as on the question above.
		assertEquals(Arrays.asList(CHANGE_CURRENT, CAUTION_CURRENT, CHANGE_CURRENT, CHANGE_CURRENT), clauses(
				"The patient is currently on Lamivudine / zidovudine, Efavirenz, Trimethoprim and"
						+ " sulfamethoxazole is it safe to give Rifampicin?"));
	}

	/** The strength clause each injected finding ENDS with, in injection order — or the finding's tail
	 *  where it ends with none of the four, so a fifth clause fails the comparison by name. */
	private static List<String> clauses(String question) throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(), twoTuberculosisCombinations(), question);

		List<String> clauses = new ArrayList<String>();
		boolean sawTheFinding = false;
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			String text = finding.getText().trim();
			sawTheFinding |= text.contains(ALREADY_IN);
			String clause = text.substring(Math.max(0, text.length() - 120));
			for (String known : Arrays.asList(WITHHOLD, CAUTION, CHANGE_CURRENT, CAUTION_CURRENT)) {
				if (text.endsWith(known)) {
					clause = known;
				}
			}
			clauses.add(clause);
		}
		assertTrue(sawTheFinding, "precondition: this issue's finding reached the prompt: " + chart.getText());
		return clauses;
	}

	private static PatientClinicalContext twoTuberculosisCombinations() {
		return contextOf(DrugReferenceTestSupport.activeOrder("order-rhz", RHZ),
				DrugReferenceTestSupport.activeOrder("order-rhze", RHZE));
	}

	private static PatientClinicalContext contextOf(PatientClinicalContext.ActiveDrugOrder... orders) {
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.ctx(40, null,
				new LinkedHashSet<String>(names), null, null, null, Arrays.asList(orders));
	}

	private static List<SafetyWarning> chips(String fixture, String question, PatientClinicalContext context)
			throws IOException {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(fixture))
				.validate("", question, context);
	}

	private static void assertNoneAlreadyIn(List<SafetyWarning> warnings) {
		// The phrase is production's own: aDrugInPlayThatTwoOfHerOrdersContainIsNamedAsAlreadyTakenInBoth
		// asserts the full sentence it is cut from.
		assertEquals(0, alreadyIn(warnings).size(), "was: " + DrugReferenceTestSupport.details(warnings));
	}

	private static List<SafetyWarning> alreadyIn(List<SafetyWarning> warnings) {
		List<SafetyWarning> found = new ArrayList<SafetyWarning>();
		for (SafetyWarning warning : warnings) {
			if (warning.getDetail().contains(ALREADY_IN)) {
				found.add(warning);
			}
		}
		return found;
	}
}
