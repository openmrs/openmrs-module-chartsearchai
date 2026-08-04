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

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * How {@link DrugSafetyValidator} decides that two interaction rules name the <em>same</em>
 * partner, and which of them keeps the chip — the two questions the one-chip-per-(drug, active
 * order) collapse of issue #115 turns on, exercised on the source that can actually pose them.
 *
 * <p>The DDInter arm of #115 is covered by {@code DdiDrugReferenceSourceTest} against real KB
 * slices. These cases need the <em>curated</em> source instead, because it is the operator-editable
 * one: {@link JsonDrugReferenceSource} is plain Jackson over a hand-authored file and sanitizes
 * nothing, where {@link DdiDrugReferenceSource} lower-cases every match token it derives (taking it
 * from the trimmed RxNorm generic whenever the partner row has one) and rates every row it builds.
 * So the three shapes below are the operator's to produce, not the KB's: a partner token padded with
 * whitespace, two notes whose raw and trimmed lengths rank the rows oppositely, and a rule with no
 * severity at all competing with a rated one. Each decides something the clinician sees.
 *
 * <p>Tests parse the fixture with the real {@link JsonDrugReferenceSource#parse} and drive the real
 * {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)} — the exact
 * production path an operator's extended dataset takes.
 */
public class InteractionPartnerGroupingTest {

	private static final String FIXTURE = "chartsearchai-test/drug-reference-partner-label-variants.json";

	private DrugSafetyValidator validator() throws IOException {
		return DrugReferenceTestSupport.validator(
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(FIXTURE)));
	}

	@Test
	public void rowsWhoseTokensDifferOnlyInCaseOrWhitespaceNameOnePartnerAndRaiseOneChip() throws IOException {
		// The fixture's two Fluconazole rows carry the tokens "Warfarin" and "  warfarin  ".
		// PatientClinicalContext.hasActiveDrug folds a rule token with trim().toLowerCase() before
		// matching it, so BOTH rows match the one Warfarin order — they are the same partner by the
		// only predicate that decides an interaction concerns this patient. The grouping key must
		// fold the same way, or #115's duplicate chip comes straight back for a hand-authored
		// dataset, silently and with two labels a clinician cannot tell apart. Asserted verbatim,
		// so it also pins that the winning row's padding never reaches the chip text.
		List<SafetyWarning> warnings = validator().validate("Fluconazole could be started.",
				"Is it safe to give fluconazole?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Warfarin 5mg"),
						null, null, null));

		assertEquals(1, warnings.size(),
				"two rows naming one partner in different case/whitespace must raise one chip, was: " + warnings);
		assertEquals("Fluconazole interacts with active order warfarin — Major. Fluconazole markedly"
				+ " increases warfarin exposure; monitor INR and adjust the warfarin dose.",
				warnings.get(0).getDetail(),
				"the surviving chip must carry the most severe row's note under a clean partner label");
	}

	@Test
	public void aHandAuthoredUnratedRowOutranksASourceRatedMajorRowForTheSamePartner() throws IOException {
		// DrugSafetyValidator.severityPriority ranks an UNRATED rule above Major, deliberately:
		// clearsSeverityFloor already exempts unrated rules from the floor because every curated
		// hand-authored rule is unrated, and unrated is not low-rated. Since #115 that ordering also
		// decides which chip a clinician sees, so an operator's own rule must not be dropped in
		// favour of a source rating for the same partner. The fixture's rated row comes FIRST, so
		// "keep the incumbent" fails here too.
		List<SafetyWarning> warnings = validator().validate("Phenobarbital could be added.",
				"Is it safe to add phenobarbital?",
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Sodium valproate 500mg"), null, null, null));

		assertEquals(1, warnings.size(),
				"two rows naming one partner must raise one chip, was: " + warnings);
		assertEquals("Phenobarbital interacts with active order valproate — Locally curated rule: do"
				+ " not co-prescribe without measuring the phenobarbital level first.",
				warnings.get(0).getDetail(),
				"the operator's unrated rule must keep the chip, not the source-rated Major row");
	}

	@Test
	public void theFullerNoteTieBreakMeasuresProseNotPadding() throws IOException {
		// Severity cannot separate the fixture's two Linezolid rows, so the tie-break falls to the
		// note — "the only informativeness signal a row carries". Whitespace is not that signal: the
		// first row's note is a 79-character referral to a policy document with eleven newlines
		// pasted onto the end (90 raw), the second is the 89-character mechanism. Measured raw, the
		// referral wins and the clinician gets a chip that says nothing about serotonin syndrome
		// while the row explaining it is discarded — decided by padding, which is exactly what the
		// tie-break exists NOT to do.
		List<SafetyWarning> warnings = validator().validate("Linezolid could be started.",
				"Is it safe to give linezolid?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Citalopram 20mg"),
						null, null, null));

		assertEquals(1, warnings.size(),
				"two rows naming one partner must raise one chip, was: " + warnings);
		assertEquals("Linezolid interacts with active order citalopram — Major. Linezolid is a"
				+ " reversible MAO inhibitor and risks serotonin syndrome with an SSRI.",
				warnings.get(0).getDetail(),
				"the fuller note is the one carrying more PROSE, not the one carrying more whitespace");
	}
}
