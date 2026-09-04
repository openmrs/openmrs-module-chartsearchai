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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The row-attribution clause AND the other-rows dosing section (issues #237, #259) are <b>NOT</b> gated
 * on the {@code drugSafety.*} switches, and that is deliberately the opposite of what
 * {@link InjectedContraindicationReadingToggleContextTest} pins one section along. The two look alike —
 * both are patient-scoped sentences in a record — so the difference has to be pinned rather than
 * explained, or the next reader "fixes" the inconsistency and silently reopens #259 under a
 * non-default configuration.
 *
 * <p><b>Why they differ.</b> Issue #208's reading ADDS a claim about the patient ("Recorded for this
 * patient: documented ibuprofen allergy"), which is the record's half of a chip — so with the chips off
 * it would be prose with no chip, and it stands down with them. The two sentences watched here do the
 * opposite: they NARROW a claim the record makes anyway. The ceiling, the interaction notes and the
 * drug's own name are rendered whatever the safety switches say; the clause stops them reading as the
 * whole substance's when they are one row's, and the section supplies the sibling ceiling that reading
 * leaves out. Gating a correction on a switch ships the UNCORRECTED sentence whenever the switch is off —
 * which is exactly #259, reachable by configuration.
 *
 * <p>Context-sensitive because the point IS the global property: a contextless case runs with both GPs
 * absent, which fails safe to {@code true}, so it cannot tell a clause that ignores the switch from one
 * that honours it.
 *
 * <p>The arrangement is non-vacuous by construction: the same record carries ALL THREE sentences, so the
 * contraindication reading disappearing is what proves the GP write took effect and that the other two
 * surviving beside it are a real difference rather than a switch that never moved. That is why the
 * fixture's Ceftriaxone pair carries both a contraindication rule and two differing ceilings.
 */
public class ReferenceRecordRowAttributionToggleContextTest extends BaseModuleContextSensitiveTest {

	private static final String CEILINGS =
			"chartsearchai-test/drug-reference-substance-dosing-ceilings.json";

	private static final String QUESTION = "Is it safe to give her ceftriaxone?";

	/** The record for the unqualified {@code Ceftriaxone} row, for a patient whose chart names the
	 *  intramuscular sibling AND records the allergy the entry's own rule is about — so the record
	 *  carries the attribution clause and the patient-specific contraindication reading together. */
	private String ceftriaxoneRecord() throws IOException {
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(CEILINGS));
		String charted = "Ceftriaxone (intramuscular)";
		PatientClinicalContext raw = DrugReferenceTestSupport.ctx(40, 70.0,
				DrugReferenceTestSupport.set(charted), null, DrugReferenceTestSupport.set("ceftriaxone"),
				null,
				Collections.singletonList(DrugReferenceTestSupport.activeOrder("o1", charted, charted)));
		PatientClinicalContext context =
				service.withReferenceNames(raw, service.findForActiveOrders(raw));
		return DrugReferenceTestSupport.referenceTextNaming(
				DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
						DrugReferenceTestSupport.oneRecordChart(), context, QUESTION),
				"Ceftriaxone");
	}

	private static final String CLAUSE = DrugReferenceTestSupport.ROW_ATTRIBUTION_LEAD + " Ceftriaxone, "
			+ "not for Ceftriaxone (intramuscular) — the row this patient's record names, filed separately "
			+ "for the same substance.";

	/** The other-rows dosing section (issue #259's numeric half), watched across the same writes for the
	 *  same reason and with a sharper one of its own: it is DOSE content, so "tidy the dose sentence
	 *  behind the dose switch" is a plausible later edit, and it would drop the stricter ceiling out of
	 *  citable evidence for every deployment running with the chips off — where the record is the only
	 *  thing a clinician has, so there is not even a warning left to disagree with it. */
	private static final String SECTION = DrugReferenceTestSupport.OTHER_ROW_DOSING_LEAD
			+ "Ceftriaxone (intramuscular) 20-50 mg/kg per dose, maximum 2000 mg/day (ages 0-120).";

	@Test
	public void theClauseSurvivesContraindicationReportingBeingSwitchedOff() throws IOException {
		// Both switches on (the shipped default, written explicitly so the case does not rest on it):
		// the record carries the clause AND the patient reading.
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "true");
		String reporting = ceftriaxoneRecord();
		assertNotNull(reporting, "precondition: the record must be injected");
		assertTrue(reporting.contains("Recorded for this patient: documented cephalosporin allergy"),
				"precondition: with contraindication reporting ON the record reads the chart, was: "
						+ reporting);
		assertTrue(reporting.contains(CLAUSE),
				"precondition: and carries the attribution clause, was: " + reporting);
		assertTrue(reporting.contains(SECTION),
				"precondition: and the other-rows dosing section, was: " + reporting);

		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "false");
		String silent = ceftriaxoneRecord();

		assertNotNull(silent, "the record is still injected — it is gated on drugReference.enabled");
		assertFalse(silent.contains("Recorded for this patient"),
				"the issue #208 reading stands down with the chips, which is what proves this GP write "
						+ "took effect, was: " + silent);
		assertTrue(silent.contains(CLAUSE),
				"but the attribution clause does NOT: it narrows a claim the record makes either way, and "
						+ "a correction that switches off ships the over-claiming sentence instead, which "
						+ "is issue #259 reachable by configuration, was: " + silent);
		assertTrue(silent.contains(SECTION),
				"nor does the other-rows dosing section — see its field, was: " + silent);
	}

	@Test
	public void theClauseSurvivesAnswerValidationBeingSwitchedOff() throws IOException {
		// The other switch, and the wider one: validateAnswers gates the whole chip pass, so with it off
		// there is no safety_finding record either. The reference record is still injected — the injector
		// reads drugReference.enabled for itself — so its ceiling still reaches the model, and the
		// sentence that scopes that ceiling to one row must still be beside it.
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS, "false");
		String silent = ceftriaxoneRecord();

		assertNotNull(silent, "the record is still injected");
		assertFalse(silent.contains("Recorded for this patient"),
				"the patient reading stands down, which proves the GP write took effect, was: " + silent);
		assertTrue(silent.contains(CLAUSE),
				"and the attribution clause survives, for the same reason as above, was: " + silent);
		assertTrue(silent.contains(SECTION),
				"and so does the other-rows dosing section, which with the whole chip pass off is the ONLY "
						+ "place the stricter ceiling appears at all, was: " + silent);
	}
}
