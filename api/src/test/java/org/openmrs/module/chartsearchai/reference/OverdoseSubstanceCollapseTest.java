/*
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Issue #174 site 4 — {@code DrugSafetyValidator.addOverdose} ran once per reference ROW, so a
 * substance the data files as several rows produced one dose warning per row, each named after its
 * own row.
 *
 * <p><b>Latent on shipped data, and that is the whole reason it is swept here rather than waited
 * for.</b> An overdose warning needs {@code ageBands}, which only the curated {@code json} schema
 * carries, and the grouping needs a {@code substanceName}, which no bundled dataset sets on a
 * curated entry — the {@code ddinter} source publishes substance names but no dosing, and the
 * shipped curated seed publishes dosing but no substance names. It becomes reachable the moment an
 * operator authors a file that does both, which the curated schema explicitly permits (see
 * {@link DrugReference#getSubstanceName()}: "a hand-authored file that sets this field opts into
 * the grouping"). The fixture here is that file.
 *
 * <p>Every scenario runs the REAL production path: the fixture parsed by the real
 * {@link JsonDrugReferenceSource}, the real {@code validate} entry point, GP reads on their
 * no-context defaults.
 */
public class OverdoseSubstanceCollapseTest {

	/**
	 * A curated file filing two substances as two rows each. {@code Amoxicillin} publishes the same
	 * band on BOTH its rows and lists the ROUTE-QUALIFIED one first, so the row count and the label
	 * are both observable; {@code Cefalexin} publishes a band on its qualified row ONLY, which is the
	 * case a collapse must not turn into a lost warning.
	 */
	private static final String FIXTURE = "chartsearchai-test/drug-reference-substance-dosing-rows.json";

	private static DrugSafetyValidator validator() throws Exception {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(FIXTURE)));
	}

	@Test
	public void oneSubstanceRaisesOneDoseWarningHoweverManyRowsItIsFiledAs() throws Exception {
		// 2000 mg twice daily is 4000 mg/day against a published 3000 mg/day ceiling, so both
		// amoxicillin rows trip. Before this fix that was two chips for one dose — and, since issue
		// #110, two near-identical citable safety-finding records in the prompt as well.
		List<SafetyWarning> warnings = validator().validate(
				"Give amoxicillin 2000 mg twice daily.", "what dose of amoxicillin?",
				DrugReferenceTestSupport.ctx(30, 70.0, null, null, null, null));

		assertEquals(1, warnings.size(),
				"one substance and one stated dose is one warning, was: " + warnings);
		assertEquals(SafetyWarning.TYPE_OVERDOSE, warnings.get(0).getType(),
				"precondition: the warning must be the dose-excess one, was: " + warnings);
	}

	@Test
	public void theDoseWarningNamesTheSubstanceNotTheRowTheFileListedFirst() throws Exception {
		// The label half, which the collapse alone would not settle: the fixture lists
		// "Amoxicillin (suspension)" first, so a first-row survivor names a formulation nobody asked
		// about. Nothing here can know the formulation — the same reason issue #162 gave for the
		// interaction subject — so the honest subject is the substance.
		List<SafetyWarning> warnings = validator().validate(
				"Give amoxicillin 2000 mg twice daily.", "what dose of amoxicillin?",
				DrugReferenceTestSupport.ctx(30, 70.0, null, null, null, null));

		assertEquals("Amoxicillin", warnings.get(0).getDrug(),
				"the dose warning must name the substance, was: " + warnings);
		// The same property in the SENTENCE, asserted on its opening rather than on the absence of the
		// string "(suspension)" anywhere in it. That blanket form was a fair companion while a formulation
		// in the detail could only mean the subject had leaked; since issue #208 a formulation appears
		// there for a different and legitimate reason — the clause naming the row whose published ceiling
		// the sentence quoted, when that is not the row the warning names
		// ({@code DoseCeilingAttributionTest}) — so the blanket form forbade what the module now correctly
		// does. Narrowed, not dropped: a first-row survivor still reddens here, because the sentence would
		// then open "The stated Amoxicillin (suspension) dose" (mutation-verified — subjectOf replaced by
		// rows.get(0) fails this assertion and line 78 together).
		assertTrue(warnings.get(0).getDetail().startsWith("The stated Amoxicillin dose "),
				"and its sentence must lead with that same subject, not with the row the file listed "
						+ "first, was: " + warnings.get(0).getDetail());
	}

	@Test
	public void aBandOnlyASiblingRowPublishesStillWarns() throws Exception {
		// The direction a collapse must not take. Cefalexin's route-unspecified row — the one the
		// substance is named after — publishes NO age band at all; only its paediatric row does. A
		// collapse that simply evaluated the canonical row would drop a real overdose warning, which
		// is the one direction this module never takes. Every row is still evaluated; only the number
		// of chips and the name on them changed.
		//
		// 800 mg twice daily is 1600 mg/day against the paediatric row's 1000 mg/day ceiling for a
		// 6-year-old.
		List<SafetyWarning> warnings = validator().validate(
				"Give cefalexin 800 mg twice daily.", "what dose of cefalexin?",
				DrugReferenceTestSupport.ctx(6, 20.0, null, null, null, null));

		assertEquals(1, warnings.size(),
				"the warning must survive a collapse whose canonical row publishes no band, was: "
						+ warnings);
		assertEquals("Cefalexin", warnings.get(0).getDrug(),
				"and it must still be named after the substance, was: " + warnings);
		assertTrue(warnings.get(0).getDetail().contains("1000 mg/day maximum for ages 0-11"),
				"and it must quote the band that actually applies, was: "
						+ warnings.get(0).getDetail());
	}
}
