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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Issue #208 item 1 — the dose warning NAMES the row {@link DrugSafetyValidator}'s shared subject
 * chooser picks (issue #206) while READING the age band of whichever row the answer's own wording
 * attributed the dose to (issue #174 site 4, "every row is still tried", so that a band published only
 * by a sibling is never a lost warning). Where those are different rows the sentence quoted a ceiling
 * that is not the named row's, as if it were: a patient on {@code Amoxicillin (suspension)} — whose own
 * published ceiling is 2000 mg/day — was told a stated 4000 mg/day "exceeds the 3000 mg/day maximum",
 * 3000 being the unqualified sibling row's number.
 *
 * <p><b>What was decided, and what deliberately was not.</b> Preferring the subject row's OWN band
 * would drop the warning entirely wherever that row publishes none, and under-warning is the failure
 * mode this layer exists to prevent — {@code OverdoseSubstanceCollapseTest
 * .aBandOnlyASiblingRowPublishesStillWarns} pins that direction. So which row supplies the ceiling is
 * unchanged; what changes is that the sentence SAYS which row published it, and says it by contrast
 * ("for X, not for Y") so that neither the clinician nor a model reading the injected
 * {@code safety_finding} record can take the number for the named row's own.
 *
 * <p><b>Why the contrast rather than the sibling's name alone.</b> A bare second name in this sentence
 * is a formulation the chart does not record, standing in a chip whose whole point since issue #194 is
 * that it names the row the chart DOES record. Naming both rows and saying which claim belongs to which
 * is what keeps the attribution a fact about the dataset rather than a second claim about the patient.
 * (This arm's sentence is clinician-facing only: {@code DrugReferenceInjector.preAnswerFindings} calls
 * {@code validate} with an EMPTY answer, and a dose is read out of the answer, so no dose warning is
 * ever injected as a citable record — unlike the interaction and contraindication chips.)
 *
 * <p>Hand-authored fixtures only, and not for convenience: a dose warning needs {@code ageBands}, which
 * only the curated {@code json} schema carries, and the grouping needs {@code substanceName}, which no
 * bundled dataset sets on a curated entry — so no shipped configuration can pose this shape at all.
 *
 * <p>Every case runs the REAL production path: the fixture parsed by the real
 * {@link JsonDrugReferenceSource}, the real {@code validate} entry point, the real
 * {@code injectRecords}, GP reads on their no-context defaults.
 */
public class DoseCeilingAttributionTest {

	/** Shared with {@code OrderedSubjectRowTest}: two {@code Amoxicillin} rows that are ONE substance
	 *  and publish DIFFERENT daily ceilings (3000 against 2000), only the unqualified one of which the
	 *  bare word resolves — so the row the chart names and the row the answer's dose is attributed to
	 *  are genuinely different, which is what makes the quoted ceiling a sibling's. */
	private static final String CHARTED_ROW_FIXTURE =
			"chartsearchai-test/drug-reference-charted-substance-row.json";

	/** Shared with {@code OverdoseSubstanceCollapseTest}: two {@code Amoxicillin} rows publishing the
	 *  SAME ceiling, which is the control below — the named row's own band is the one quoted, so there
	 *  is nothing to attribute. */
	private static final String DOSING_ROWS_FIXTURE =
			"chartsearchai-test/drug-reference-substance-dosing-rows.json";

	/** Issue #208's own fixture: the only published band sits on a sibling AND publishes no daily
	 *  ceiling, so the WEIGHT arm is the one that trips. Both arms word the provenance, and only this
	 *  reaches the second copy. */
	private static final String PER_KG_SIBLING_FIXTURE =
			"chartsearchai-test/drug-reference-sibling-per-kg-ceiling.json";

	/** The pass's one dose warning. Exactly one, asserted rather than assumed: one substance and one
	 *  stated dose is one warning ({@code OverdoseSubstanceCollapseTest}), and a second would mean this
	 *  case is asserting the wording of whichever arrived first. */
	private static SafetyWarning onlyOverdose(List<SafetyWarning> warnings) {
		List<SafetyWarning> found = new ArrayList<SafetyWarning>();
		for (SafetyWarning warning : warnings) {
			if (SafetyWarning.TYPE_OVERDOSE.equals(warning.getType())) {
				found.add(warning);
			}
		}
		assertEquals(1, found.size(), "precondition: exactly one dose warning, was: " + warnings);
		return found.get(0);
	}

	@Test
	public void theDailyCeilingSaysWhichRowPublishedItAndWhichRowItIsNot() throws Exception {
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(CHARTED_ROW_FIXTURE);
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(entries);
		DrugReference suspension = DrugReferenceTestSupport.row(entries, "Amoxicillin (suspension)");
		DrugReference unqualified = DrugReferenceTestSupport.row(entries, "Amoxicillin");

		// The premise, through the production accessors: the two rows are one substance publishing two
		// different ceilings, so "the maximum" is ambiguous by construction and the sentence has to say
		// which one it means. Without this the case could pass on a fixture whose rows agree, where the
		// attribution would be true but vacuous.
		assertEquals(suspension.substanceGroupKey(), unqualified.substanceGroupKey(),
				"precondition: the two rows must be ONE substance");
		assertEquals(2000.0, suspension.bandForAge(30).getMaxDailyDoseMg(), 0.0,
				"precondition: the charted row publishes its own ceiling");
		assertEquals(3000.0, unqualified.bandForAge(30).getMaxDailyDoseMg(), 0.0,
				"precondition: and the sibling a different one");

		PatientClinicalContext context = DrugReferenceTestSupport.ctx(30, 70.0,
				DrugReferenceTestSupport.set("Amoxicillin (suspension)", "Warfarin 5mg"), null, null,
				null);
		SafetyWarning overdose = onlyOverdose(DrugReferenceTestSupport.validator(service).validate(
				"Give amoxicillin 2000 mg twice daily.", "Is amoxicillin safe for her?", context));

		assertEquals("Amoxicillin (suspension)", overdose.getDrug(),
				"precondition: the warning is named after the row the chart records (issue #206)");
		assertEquals("The stated Amoxicillin (suspension) dose ~4000 mg/day exceeds the 3000 mg/day "
				+ "maximum for ages 0-120 — a ceiling this dataset publishes for Amoxicillin, not for "
				+ "Amoxicillin (suspension)", overdose.getDetail(),
				"the quoted ceiling must be attributed to the row that published it");
	}

	@Test
	public void thePerKilogramCeilingSaysItToo() throws Exception {
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(PER_KG_SIBLING_FIXTURE);
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(entries);
		DrugReference named = DrugReferenceTestSupport.row(entries, "Ceftriaxone");
		DrugReference paediatric =
				DrugReferenceTestSupport.row(entries, "Ceftriaxone (paediatric injection)");

		// The premise for the OTHER arm: the row the warning is named after publishes no band at all, so
		// the weight ceiling can only be the sibling's, and the sibling publishes no daily maximum so the
		// daily arm cannot return first and hide the weight arm's own wording.
		assertEquals(named.substanceGroupKey(), paediatric.substanceGroupKey(),
				"precondition: one substance");
		assertNotNull(paediatric.bandForAge(6), "precondition: the sibling publishes the band");
		assertEquals(0.0, paediatric.bandForAge(6).getMaxDailyDoseMg(), 0.0,
				"precondition: and no daily ceiling, so the weight arm is the arm that trips");

		SafetyWarning overdose = onlyOverdose(DrugReferenceTestSupport.validator(service).validate(
				"Give ceftriaxone 1500 mg once daily.", "What dose of ceftriaxone?",
				DrugReferenceTestSupport.ctx(6, 20.0, null, null, null, null)));

		assertEquals("Ceftriaxone", overdose.getDrug(),
				"precondition: named after the route-unspecified row");
		assertEquals("The stated Ceftriaxone dose ~1500 mg exceeds the 50 mg/kg per-dose maximum "
				+ "(~1000 mg) for the patient's weight 20 kg (ages 0-11) — a ceiling this dataset "
				+ "publishes for Ceftriaxone (paediatric injection), not for Ceftriaxone",
				overdose.getDetail(),
				"the weight arm must attribute its ceiling as well");
	}

	@Test
	public void aCeilingTheNamedRowPublishesItselfIsAttributedToNobody() throws Exception {
		// The control, and the half that bounds the cost: where the named row's OWN band is the one the
		// sentence quotes there is nothing to attribute, so the wording must not move at all. Both
		// Amoxicillin rows of this fixture publish 3000, and the subject is the unqualified row, so the
		// first row tried is the row quoted. Without this a fix could append the clause unconditionally
		// and read "publishes for Amoxicillin, not for Amoxicillin".
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(DOSING_ROWS_FIXTURE);
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(entries);
		assertNotSame(DrugReferenceTestSupport.row(entries, "Amoxicillin"),
				DrugReferenceTestSupport.row(entries, "Amoxicillin (suspension)"),
				"precondition: the substance really is filed as two rows");

		SafetyWarning overdose = onlyOverdose(DrugReferenceTestSupport.validator(service).validate(
				"Give amoxicillin 2000 mg twice daily.", "what dose of amoxicillin?",
				DrugReferenceTestSupport.ctx(30, 70.0, null, null, null, null)));

		assertEquals("The stated Amoxicillin dose ~4000 mg/day exceeds the 3000 mg/day maximum for "
				+ "ages 0-120", overdose.getDetail(),
				"an unattributed ceiling is the row's own, and says nothing further");
	}

	@Test
	public void noShippedConfigurationCanReachTheAttributionAtAll() throws Exception {
		// The bound on what this changes in the field, asserted over the SHIPPED curated seed rather than
		// argued: no bundled dataset sets substanceName on a curated entry, so every substance is one row,
		// so the row the warning names is always the row that published the ceiling. A deployment editing
		// drug-reference.json reaches the attribution immediately, which is why the fixtures above exist
		// — but nothing shipped moves, and a change to the wording of the common case would redden here.
		DrugReferenceService service = DrugReferenceTestSupport.bundledService();
		DrugReference ibuprofen = service.lookupByToken("ibuprofen");
		assertNotNull(ibuprofen, "precondition: the shipped seed must carry ibuprofen");
		assertNull(ibuprofen.getSubstanceName(),
				"precondition: the seed publishes no substance name, which is WHY every substance in it is "
						+ "one row — substanceGroupKey then keys on the row itself");

		SafetyWarning overdose = onlyOverdose(DrugReferenceTestSupport.validator(service).validate(
				"Give ibuprofen 800 mg four times daily.", "What dose of ibuprofen?",
				DrugReferenceTestSupport.ctx(30, 70.0, null, null, null, null)));

		assertEquals("The stated Ibuprofen dose ~3200 mg/day exceeds the 2400 mg/day maximum for ages "
				+ "12-120", overdose.getDetail(), "the shipped wording is unchanged");
		assertFalse(overdose.getDetail().contains("this dataset publishes"),
				"and carries no attribution clause, was: " + overdose.getDetail());
	}
}
