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

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue #553: an order scheduled to start in the future is still screened, but no finding calls it an
 * active order she is already taking. Driven through the public {@code validate(answer, question, Patient)},
 * so the patient's orders are read by the real {@link PatientClinicalContextBuilder} from the database —
 * {@code ScheduledDrugOrderTestData.xml}'s started Nevirapine and scheduled Rifampicin — and screened over
 * the verbatim DDInter slice that relates the two.
 */
public class ScheduledOrderInteractionContextTest extends BaseModuleContextSensitiveTest {

	private static final String SLICE = "chartsearchai-test/ddi-listed-medications-proposal.json";

	private static final String STARTS = "scheduled to start 2099-01-01";

	private DrugSafetyValidator validator;

	private Patient patient;

	@BeforeEach
	public void setUp() throws IOException {
		executeDataSet("ScheduledDrugOrderTestData.xml");
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		validator = DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(SLICE));
		patient = Context.getPatientService().getPatient(7);
	}

	private List<SafetyWarning> chips(String question) {
		return validator.validate("", question, patient);
	}

	private static boolean namesRifampicin(String text) {
		return text != null && text.toLowerCase(Locale.ROOT).contains("rifamp");
	}

	/** The one chip about {@code drug} whose detail names Rifampicin, or an assertion error listing all. */
	private static SafetyWarning chipAbout(List<SafetyWarning> chips, String drug) {
		SafetyWarning found = null;
		for (SafetyWarning chip : chips) {
			if (drug.equals(chip.getDrug()) && namesRifampicin(chip.getDetail())) {
				assertTrue(found == null, "precondition: one chip about " + drug + " naming Rifampicin, were: "
						+ DrugReferenceTestSupport.details(chips));
				found = chip;
			}
		}
		assertTrue(found != null, "precondition: a chip about " + drug + " naming Rifampicin, chips were: "
				+ DrugReferenceTestSupport.details(chips));
		return found;
	}

	private static void assertNoChipCallsRifampicinActive(List<SafetyWarning> chips) {
		for (SafetyWarning chip : chips) {
			assertFalse(chip.getDetail().toLowerCase(Locale.ROOT).contains("active order rifamp"),
					"a scheduled order is never an active order she is taking: " + chip.getDetail());
		}
	}

	@Test
	public void aDrugInPlayNamesHerScheduledOrderAsScheduledWithItsDate() {
		// The ticket's second row: "Dabigatran etexilate interacts with active order Rifampicin".
		List<SafetyWarning> chips = chips("Can I give her amlodipine?");

		SafetyWarning chip = chipAbout(chips, "Amlodipine");
		assertTrue(chip.getDetail().startsWith("Amlodipine interacts with scheduled order Rifampicin (rifampin), "
				+ STARTS + " — "), "the chip names the order as scheduled, with its start date: " + chip.getDetail());
		assertNoChipCallsRifampicinActive(chips);
		assertTrue(DrugReferenceTestSupport.details(chips).toString().contains("interacts with active order Nevirapine"),
				"the started order beside it is still an active order: " + DrugReferenceTestSupport.details(chips));
	}

	@Test
	public void theScreenStatesThePairButNeverCallsTheScheduledOrderAMedicationSheIsTaking() {
		// The ticket's first row: "Nevirapine interacts with active order Rifampicin (rifampin)", Major.
		List<SafetyWarning> chips = chips("Are there any drug interactions among her current medications?");

		boolean pairStated = false;
		for (SafetyWarning chip : chips) {
			String drug = chip.getDrug();
			if (namesRifampicin(drug)) {
				assertFalse(chip.isAboutACurrentMedication(),
						"a finding about her scheduled Rifampicin is not about a medication she is already taking: "
								+ chip.getDetail());
				assertTrue(chip.getDetail().startsWith(drug + ", " + STARTS + ", interacts with "),
						"and says when it starts: " + chip.getDetail());
				pairStated |= chip.getDetail().contains("interacts with active order Nevirapine");
			} else if (namesRifampicin(chip.getDetail())) {
				assertTrue(chip.getDetail().contains(" interacts with scheduled order Rifampicin (rifampin), " + STARTS),
						"a finding naming it as the partner says it is scheduled: " + chip.getDetail());
				pairStated |= "Nevirapine".equals(drug);
			}
		}
		assertTrue(pairStated, "precondition: the screen states the Nevirapine x Rifampicin pair, chips were: "
				+ DrugReferenceTestSupport.details(chips));
		assertNoChipCallsRifampicinActive(chips);
	}

	@Test
	public void aQuestionAboutHerScheduledDrugDoesNotCallItOneSheIsTaking() {
		List<SafetyWarning> chips = chips("Can I give her rifampicin?");

		boolean any = false;
		for (SafetyWarning chip : chips) {
			if (namesRifampicin(chip.getDrug())) {
				any = true;
				assertFalse(chip.isAboutACurrentMedication(),
						"her Rifampicin has not started, so a finding about it is not about a current medication: "
								+ chip.getDetail());
			}
		}
		assertTrue(any, "precondition: a finding about Rifampicin, chips were: " + DrugReferenceTestSupport.details(chips));
	}

	@Test
	public void aQuestionAboutHerStartedDrugStillStatesItAsHerMedicationAndNamesTheScheduledPartnerAsScheduled() {
		List<SafetyWarning> chips = chips("Can I give her nevirapine?");

		SafetyWarning chip = chipAbout(chips, "Nevirapine");
		assertTrue(chip.isAboutACurrentMedication(), "Nevirapine has started: it is her medication");
		assertTrue(chip.getDetail().startsWith("Nevirapine interacts with scheduled order Rifampicin (rifampin), "
				+ STARTS + " — "), chip.getDetail());
		assertNoChipCallsRifampicinActive(chips);
	}

	@Test
	public void theClassArmNamesAScheduledCoMedicationAsScheduledWithItsDate() {
		// The class arm's own sentence ("… is in the same ATC class as active order X"), which a chip
		// states alone or folded onto a rule chip about the same order.
		executeDataSet("ScheduledStavudineOrderTestData.xml");
		List<SafetyWarning> chips = chips("Can I give her lamivudine?");

		boolean classSentence = false;
		for (SafetyWarning chip : chips) {
			String detail = chip.getDetail();
			assertFalse(detail.toLowerCase(Locale.ROOT).contains("active order stavudine"),
					"a scheduled order is never an active order she is taking: " + detail);
			classSentence |= detail.contains(" as scheduled order Stavudine, " + STARTS + " — ");
		}
		assertTrue(classSentence, "the class sentence names the order as scheduled, with its date, chips were: "
				+ DrugReferenceTestSupport.details(chips));
	}

	@Test
	public void aScheduledCoMedicationReachedByItsAtcCodeIsNamedAsScheduled() {
		// The class arm's code walk, which reads a co-medication off the orders carrying a CHART-recorded
		// ATC code rather than off an order's name.
		executeDataSet("ScheduledStavudineOrderTestData.xml");
		DrugReferenceTestSupport.mapConceptToAtc(9555, "J05AF04");
		List<SafetyWarning> chips = chips("Can I give her lamivudine?");

		boolean classSentence = false;
		for (SafetyWarning chip : chips) {
			assertFalse(chip.getDetail().toLowerCase(Locale.ROOT).contains("active order stavudine"),
					"a scheduled order is never an active order she is taking: " + chip.getDetail());
			classSentence |= chip.getDetail().contains(" as scheduled order Stavudine, " + STARTS + " — ");
		}
		assertTrue(classSentence, "the class sentence names the order as scheduled, with its date, chips were: "
				+ DrugReferenceTestSupport.details(chips));
	}

	@Test
	public void aFoldedChipNamesItsScheduledPartnerAsScheduledInBothSentences() {
		// The rule chip and the class arm's sentence about the same order share one detail (issue #88), so
		// both halves must name it one way.
		executeDataSet("ScheduledAspirinOrderTestData.xml");
		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterServiceWithGroups())
				.validate("", "Can I give ibuprofen?", Context.getPatientService().getPatient(6));

		boolean folded = false;
		for (SafetyWarning chip : chips) {
			String detail = chip.getDetail();
			assertFalse(detail.toLowerCase(Locale.ROOT).contains("active order"),
					"a scheduled order is never an active order she is taking: " + detail);
			if (detail.contains(" interacts with scheduled order ") && detail.contains(" is in the same ")) {
				folded = true;
				assertTrue(detail.matches("(?s).* interacts with scheduled order [^—]*, " + STARTS + " — .*"
						+ " as scheduled order [^—]*, " + STARTS + " — .*"),
						"both sentences name the order as scheduled, with its date: " + detail);
			}
		}
		assertTrue(folded, "precondition: a folded chip, chips were: " + DrugReferenceTestSupport.details(chips));
	}

	@Test
	public void aCoMedicationAStartedOrderAlsoCarriesIsStillAnActiveOrder() {
		// One co-medication read off two orders: the scheduled one by its concept's ATC code, a started one
		// by its name. She is taking it, so the class sentence must not call it scheduled.
		executeDataSet("ScheduledStavudineOrderTestData.xml");
		executeDataSet("StartedStavudineOrderTestData.xml");
		DrugReferenceTestSupport.mapConceptToAtc(9555, "J05AF04");
		List<SafetyWarning> chips = chips("Can I give her lamivudine?");

		boolean classSentence = false;
		for (SafetyWarning chip : chips) {
			assertFalse(chip.getDetail().contains("scheduled order Stavudine"),
					"a co-medication a started order carries is not a scheduled one: " + chip.getDetail());
			classSentence |= chip.getDetail().contains(" as active order Stavudine");
		}
		assertTrue(classSentence, "precondition: the class sentence names Stavudine, chips were: "
				+ DrugReferenceTestSupport.details(chips));
	}

	@Test
	public void aScreenedPairWhoseSubjectIsHerScheduledOrderSaysSoAndIsNotAboutACurrentMedication() {
		// The screening arm states a pair once, from whichever order the dataset files first; the slice
		// files Rifampicin ahead of Amlodipine, so this pair's subject is the order that has not started.
		executeDataSet("StartedAmlodipineOrderTestData.xml");
		List<SafetyWarning> chips = chips("Are there any drug interactions among her current medications?");

		SafetyWarning chip = null;
		for (SafetyWarning candidate : chips) {
			if (namesRifampicin(candidate.getDrug()) && candidate.getDetail().contains("Amlodipine")) {
				assertTrue(chip == null, "precondition: one chip about Rifampicin naming Amlodipine, were: "
						+ DrugReferenceTestSupport.details(chips));
				chip = candidate;
			}
		}
		assertTrue(chip != null, "precondition: the screen states the pair with Rifampicin as its subject, chips were: "
				+ DrugReferenceTestSupport.details(chips));
		assertTrue(chip.getDetail().startsWith("Rifampicin (rifampin), " + STARTS + ", interacts with active order "
				+ "Amlodipine — "), "the subject is named as scheduled, with its date: " + chip.getDetail());
		assertFalse(chip.isAboutACurrentMedication(),
				"and the finding is not about a medication she is already taking: " + chip.getDetail());
		for (SafetyWarning other : chips) {
			if ("Nevirapine".equals(other.getDrug()) && namesRifampicin(other.getDetail())) {
				assertTrue(other.isAboutACurrentMedication(),
						"a pair whose subject has started is still about her current medication: " + other.getDetail());
			}
		}
	}

	@Test
	public void aScheduledPartnerIsNotMergedIntoAMechanismItSharesWithAnActiveOrder() throws IOException {
		// collapseSharedMechanisms states one mechanism once, naming every order it covers in ONE phrase —
		// which would call the scheduled Methylprednisolone an active order beside the started Prednisone.
		executeDataSet("ScheduledCorticosteroidOrderTestData.xml");
		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddiFixtureService(
				DrugReferenceTestSupport.DDI_SHARED_MECHANISM_PARTNERS))
				.validate("", DrugReferenceTestSupport.SHARED_MECHANISM_QUESTION, patient);

		boolean scheduled = false;
		boolean active = false;
		for (SafetyWarning chip : chips) {
			String lower = chip.getDetail().toLowerCase(Locale.ROOT);
			if (!chip.getDetail().contains(DrugReferenceTestSupport.SHARED_MECHANISM_TEXT)) {
				continue;
			}
			if (lower.contains("methylprednisolone")) {
				assertFalse(lower.contains("prednisone 5mg") || lower.contains("prednisone and")
						|| lower.contains("and prednisone"), "the scheduled order keeps a chip of its own: " + chip.getDetail());
				assertTrue(chip.getDetail().contains(" interacts with scheduled order ")
						&& chip.getDetail().contains(STARTS), "named as scheduled, with its date: " + chip.getDetail());
				scheduled = true;
			} else if (lower.contains("prednisone")) {
				assertTrue(chip.getDetail().contains(" interacts with active order "), chip.getDetail());
				active = true;
			}
		}
		assertTrue(scheduled && active, "precondition: the mechanism is stated about both orders, chips were: "
				+ DrugReferenceTestSupport.details(chips));
	}
}
