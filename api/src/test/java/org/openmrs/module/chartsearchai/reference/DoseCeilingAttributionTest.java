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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Issue #208 item 1 — the dose warning NAMES the row {@link DrugSafetyValidator}'s shared subject
 * chooser picks (issue #206) while READING the age band of whichever row supplied the ceiling that the
 * stated dose exceeded (issue #174 site 4, "every row is still tried", so that a band published only by
 * a sibling is never a lost warning). Where those are different rows the sentence quoted a ceiling that
 * is not the named row's, as if it were: a patient on {@code Amoxicillin (suspension)} — whose own
 * published ceiling is 2000 mg/day — was told a stated 4000 mg/day "exceeds the 3000 mg/day maximum",
 * 3000 being the unqualified sibling row's number.
 *
 * <p><b>What was decided, and what deliberately was not.</b> Preferring the subject row's OWN band
 * would drop the warning entirely wherever that row publishes none, and under-warning is the failure
 * mode this layer exists to prevent — {@code OverdoseSubstanceCollapseTest
 * .aBandOnlyASiblingRowPublishesStillWarns} pins that direction. So this issue changed no row's turn to
 * supply the ceiling; what it changed is that the sentence SAYS which row published it, and says it by
 * contrast ("for X, not for Y") so that neither the clinician nor a model reading the injected
 * {@code safety_finding} record can take the number for the named row's own.
 *
 * <p><b>Which row supplies it did move later, and this file is where that shows.</b> Issue #245 made the
 * stated dose be read for the SUBSTANCE rather than for whichever row's alias the answer's wording used,
 * so a subject row that publishes a band now genuinely has a dose to compare and its own ceiling is
 * quoted whenever that ceiling is the one exceeded. The clause below is therefore what the FALLBACK
 * narrates — the case where the named row publishes no usable band and a sibling's is the only one there
 * is — which is why the daily case is posed on {@code Cefalexin}, whose route-unspecified row publishes
 * no band at all, rather than on the two {@code Amoxicillin} rows it used to use, where the named row's
 * own 2000 mg/day is now correctly the number quoted and there is nothing left to attribute.
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

	/** Shared with {@code OverdoseSubstanceCollapseTest}, and carrying both shapes this file needs: two
	 *  {@code Amoxicillin} rows publishing the SAME ceiling, which is the control — the named row's own
	 *  band is the one quoted, so there is nothing to attribute — and a {@code Cefalexin} whose
	 *  route-unspecified row publishes no band at all, so only its paediatric sibling can supply one and
	 *  the daily arm has to say whose it is. */
	private static final String DOSING_ROWS_FIXTURE =
			"chartsearchai-test/drug-reference-substance-dosing-rows.json";

	/** Issue #208's own fixture: the only published band sits on a sibling AND publishes no daily
	 *  ceiling, so the WEIGHT arm is the one that trips. Both arms word the provenance, and only this
	 *  reaches the second copy. */
	private static final String PER_KG_SIBLING_FIXTURE =
			"chartsearchai-test/drug-reference-sibling-per-kg-ceiling.json";

	/**
	 * The sentence of the pass's one dose warning for {@code drug}, through the shared accessors the
	 * sibling overdose tests use — with the substance's own chip count asserted first, because
	 * {@code overdoseDetail} answers with the FIRST match and would otherwise let a case assert the
	 * wording of whichever of two warnings arrived first (one substance and one stated dose is one
	 * warning; {@code OverdoseSubstanceCollapseTest} is where that is settled).
	 */
	private static String overdoseSentence(List<SafetyWarning> warnings, String drug) {
		assertEquals(1, DrugReferenceTestSupport.overdoseCount(warnings, drug),
				"precondition: exactly one dose warning for " + drug + ", was: " + warnings);
		return DrugReferenceTestSupport.overdoseDetail(warnings, drug);
	}

	@Test
	public void theDailyCeilingSaysWhichRowPublishedItAndWhichRowItIsNot() throws Exception {
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(DOSING_ROWS_FIXTURE);
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(entries);
		DrugReference named = DrugReferenceTestSupport.row(entries, "Cefalexin");
		DrugReference paediatric = DrugReferenceTestSupport.row(entries, "Cefalexin (paediatric)");

		// The premise, through the production accessors: the row the warning is NAMED after publishes no
		// band this patient's age reaches, so the ceiling can only come from its sibling and "the maximum"
		// is a number about a row the sentence is not otherwise about. Without this the case could pass on
		// a fixture where the named row's own band is the one quoted, and the attribution would be true
		// but vacuous.
		assertEquals(named.substanceGroupKey(), paediatric.substanceGroupKey(),
				"precondition: the two rows must be ONE substance");
		assertNull(named.bandForAge(6),
				"precondition: the row the warning is named after publishes no band");
		assertEquals(1000.0, paediatric.bandForAge(6).getMaxDailyDoseMg(), 0.0,
				"precondition: while its sibling publishes the daily ceiling this sentence will quote");

		// 400 mg three times daily is 1200 mg/day. No weight is given, so the weight arm cannot run and
		// the DAILY arm is unambiguously the one that trips — which is what this case is for; the weight
		// arm's own copy of the clause is thePerKilogramCeilingSaysItToo below.
		String overdose = overdoseSentence(DrugReferenceTestSupport.validator(service).validate(
				"Give cefalexin 400 mg three times daily.", "What dose of cefalexin?",
				DrugReferenceTestSupport.ctx(6, null, null, null, null, null)), "Cefalexin");

		assertEquals("The stated Cefalexin dose ~1200 mg/day exceeds the 1000 mg/day maximum for ages "
				+ "0-11 — a ceiling this dataset publishes for Cefalexin (paediatric), not for Cefalexin",
				overdose, "the quoted ceiling must be attributed to the row that published it");
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

		String overdose = overdoseSentence(DrugReferenceTestSupport.validator(service).validate(
				"Give ceftriaxone 1500 mg once daily.", "What dose of ceftriaxone?",
				DrugReferenceTestSupport.ctx(6, 20.0, null, null, null, null)), "Ceftriaxone");

		assertEquals("The stated Ceftriaxone dose ~1500 mg exceeds the 50 mg/kg per-dose maximum "
				+ "(~1000 mg) for the patient's weight 20 kg (ages 0-11) — a ceiling this dataset "
				+ "publishes for Ceftriaxone (paediatric injection), not for Ceftriaxone", overdose,
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
		// Asserted on the KEY, not on object identity: `row` selects by exact name, so two lookups under
		// two names can never answer the same object and an assertNotSame between them cannot fail. This
		// is the premise that matters — delete either row's substanceName and the case stops exercising a
		// multi-row substance at all, reaching `ref == subject` through the ungrouped fallback instead.
		assertEquals(DrugReferenceTestSupport.row(entries, "Amoxicillin").substanceGroupKey(),
				DrugReferenceTestSupport.row(entries, "Amoxicillin (suspension)").substanceGroupKey(),
				"precondition: the substance really is filed as two rows of ONE substance");

		String overdose = overdoseSentence(DrugReferenceTestSupport.validator(service).validate(
				"Give amoxicillin 2000 mg twice daily.", "what dose of amoxicillin?",
				DrugReferenceTestSupport.ctx(30, 70.0, null, null, null, null)), "Amoxicillin");

		assertEquals("The stated Amoxicillin dose ~4000 mg/day exceeds the 3000 mg/day maximum for "
				+ "ages 0-120", overdose,
				"an unattributed ceiling is the row's own, and says nothing further");
	}

	@Test
	public void twoRowsPublishingOneNameAreAttributedToNobodyEither() throws Exception {
		// The operator-editable boundary this module guards everywhere else: a curated file may file two
		// rows under one display name — the parse only drops an entry whose name is BLANK — and the rows
		// then differ in what they publish while agreeing on what they are called. The provenance clause
		// would read "publishes for Ranitidine, not for Ranitidine", which is not a provenance but a
		// contradiction, shown to a clinician beside a real dose warning. So the warning still fires and
		// says nothing further.
		List<DrugReference> entries = DrugReferenceTestSupport.fixtureEntries(PER_KG_SIBLING_FIXTURE);
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(entries);
		DrugReference first = DrugReferenceTestSupport.row(entries, "Ranitidine");
		DrugReference banded = DrugReferenceTestSupport.row(entries, "ranitidine");
		// Every premise pinned through a production predicate, because the two rows' labels fold to ONE
		// name and the warning therefore cannot say which row it was named after: one substance, the
		// subject is the bandless row, and only its sibling publishes a ceiling. Without the canonicalRow
		// assertion a later change to subject selection would silently turn this into a duplicate of the
		// same-row control above, and the guard it exists for would go untested with nothing reddening.
		assertEquals(first.substanceGroupKey(), banded.substanceGroupKey(),
				"precondition: one substance");
		assertSame(first, DrugReference.canonicalRow(Arrays.asList(first, banded)),
				"precondition: the bandless row is the one the warning is named after");
		assertNull(first.bandForAge(40), "precondition: which publishes no band for this age");
		assertNotNull(banded.bandForAge(40), "precondition: while its sibling does");

		String overdose = overdoseSentence(DrugReferenceTestSupport.validator(service).validate(
				"Give ranitidine 400 mg twice daily.", "What dose of ranitidine?",
				DrugReferenceTestSupport.ctx(40, 70.0, null, null, null, null)), "Ranitidine");

		assertEquals("The stated Ranitidine dose ~800 mg/day exceeds the 300 mg/day maximum for ages "
				+ "0-120", overdose,
				"the sibling's ceiling still warns, and says nothing it cannot say");
	}

	@Test
	public void noShippedConfigurationCanReachTheAttributionAtAll() throws Exception {
		// The bound on what this changes in the field, asserted over the SHIPPED curated seed rather than
		// argued: no bundled dataset sets substanceName on a curated entry, so every substance is one row,
		// so the row the warning names is always the row that published the ceiling. A deployment editing
		// drug-reference.json reaches the attribution immediately, which is why the fixtures above exist
		// — but nothing shipped moves, and a change to the wording of the common case would redden here.
		DrugReferenceService service = DrugReferenceTestSupport.curatedService();
		DrugReference ibuprofen = service.lookupByToken("ibuprofen");
		assertNotNull(ibuprofen, "precondition: the shipped seed must carry ibuprofen");
		assertNull(ibuprofen.getSubstanceName(),
				"precondition: the seed publishes no substance name, which is WHY every substance in it is "
						+ "one row — substanceGroupKey then keys on the row itself");

		String overdose = overdoseSentence(DrugReferenceTestSupport.validator(service).validate(
				"Give ibuprofen 800 mg four times daily.", "What dose of ibuprofen?",
				DrugReferenceTestSupport.ctx(30, 70.0, null, null, null, null)), "Ibuprofen");

		assertEquals("The stated Ibuprofen dose ~3200 mg/day exceeds the 2400 mg/day maximum for ages "
				+ "12-120", overdose, "the shipped wording is unchanged — no attribution clause, and "
						+ "nothing else moved either");
	}
}
