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

import java.util.List;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * What an install that enables the feature and configures NOTHING ELSE actually gets. Every other
 * test in this package arranges its own dataset; this one asserts the shipped default, which no
 * arrangement can observe and which is the only dataset most deployments will ever run.
 *
 * <p>It is the full DDInter 2.0 knowledge base, bundled in the omod — not the 16-drug excerpt that
 * used to be bundled, and no longer the four-drug curated seed. Both halves are asserted, because
 * either one alone passes for the wrong reason: the FORMAT alone would pass with an excerpt in the
 * jar, and the SIZE alone would pass on an operator's file. The floors are floors and not equalities
 * so a knowledge-base refresh does not have to edit this test; they are set where only the whole
 * knowledge base can meet them (it ships 2283 entries and 590,312 interaction links, against the
 * excerpt's 16 and 240).
 *
 * <p><b>The dosing bound is pinned here too, and deliberately.</b> DDInter publishes drug-drug
 * interactions only, so the shipped default carries no age band and no hand-authored
 * allergy/condition rule: {@code chartsearchai.drugSafety.warnOnDoseExcess} defaults to true and,
 * under this dataset, has nothing it can ever fire on. That is the trade the default makes — 5
 * hand-authored interaction rules over 4 drugs, exchanged for ~295k rated pairs over 2283 — and it
 * is asserted rather than left to be discovered, because a safety arm that cannot fire looks
 * exactly like one that found nothing. An install that needs dose ceilings selects
 * {@code sourceFormat=json} or points {@code dataFilePath} at a dataset carrying them.
 */
public class ShippedDrugReferenceDefaultTest extends BaseModuleContextSensitiveTest {

	/**
	 * Entries an excerpt cannot reach. The shipped knowledge base has 2283; the excerpt that used to be
	 * bundled had 16, and the curated seed has 4.
	 */
	private static final int WHOLE_KNOWLEDGE_BASE_ENTRIES = 2000;

	/** Interaction links an excerpt cannot reach either — the shipped one carries 590,312. */
	private static final int WHOLE_KNOWLEDGE_BASE_LINKS = 400000;

	/**
	 * Enables the feature and spells BOTH dataset global properties as untouched, which is the state
	 * this test is about. Blank is how a fresh context spells unset, and
	 * {@code ChartSearchAiUtils.getStringGlobalProperty} resolves it to the declared default — so
	 * setting them blank exercises the shipped defaults rather than bypassing them.
	 */
	private void enableWithNothingElseConfigured() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT, "");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, "");
	}

	@Test
	public void theShippedDefaultIsTheWholeDdinterKnowledgeBaseBundledInTheModule() {
		enableWithNothingElseConfigured();

		DrugReferenceService service = new DrugReferenceService();
		DrugReferenceLoad status = service.getLoadStatus();

		assertEquals(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, status.getSourceFormat(),
				"an install that configures nothing must run the DDInter parser");
		assertEquals(ReferenceDataFiles.CLASSPATH_ORIGIN_PREFIX + DdiDrugReferenceSource.CLASSPATH_DEFAULT,
				status.getOrigin(),
				"and read the dataset bundled in the module, since the default path names a file the "
						+ "module never creates. Origin was: " + status.getOrigin());
		assertFalse(status.isInert(), "the shipped default must not leave safety checking off");
		assertTrue(status.getEntryCount() >= WHOLE_KNOWLEDGE_BASE_ENTRIES,
				"the bundled dataset must be the WHOLE knowledge base, not an excerpt of it: expected at "
						+ "least " + WHOLE_KNOWLEDGE_BASE_ENTRIES + " entries, was " + status.getEntryCount());

		int links = 0;
		for (DrugReference entry : service.getAll()) {
			links += entry.getInteractions().size();
		}
		assertTrue(links >= WHOLE_KNOWLEDGE_BASE_LINKS,
				"and the interaction rules are what this source carries, so an excerpt is visible in the "
						+ "link count too: expected at least " + WHOLE_KNOWLEDGE_BASE_LINKS + ", was " + links);
	}

	/**
	 * The bound the class javadoc pins is stated in prose in several places — {@code config.xml}'s
	 * property description, {@link ChartSearchAiConstants#GP_DRUG_SAFETY_WARN_ON_DOSE_EXCESS}'s javadoc,
	 * the validator's, the README, ADR Decision 36 — and none of them can speak about a dataset an
	 * operator configured for themselves (issue #285). This asserts that the load status answers it per
	 * arm, from the entries actually loaded.
	 *
	 * <p>Per arm and not per dataset, because {@link DrugReferenceLoad#isInert()} is already the
	 * whole-dataset verdict and this dataset is not inert by it — 2283 entries load and the interaction
	 * arms work. What no existing field can say is that two of the arms have nothing to act on while the
	 * rest are fine.
	 *
	 * <p>{@code ABSENT} and not a count of zero, because {@link DrugReferenceLoad#notLoaded()} zeroes
	 * every field: a bare zero cannot separate "the feature is off and nothing was read" from "a dataset
	 * was read and publishes none", which is the {@code count of 0 printed as cheerfully as 2283} failure
	 * ADR Decision 32 was written against. The count is reported beside the verdict, never instead of it.
	 */
	@Test
	public void theShippedDefaultReportsWhichSafetyArmsItsDatasetCanServe() {
		enableWithNothingElseConfigured();

		DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

		assertTrue(status.getEntryCount() >= WHOLE_KNOWLEDGE_BASE_ENTRIES,
				"precondition: the whole knowledge base loaded. Asserted as a count rather than through "
						+ "isInert(), which is loaded && entryCount == 0 and so is also false for a load "
						+ "that never happened — it cannot establish that a dataset was read");
		assertFalse(status.isInert(),
				"and it is not inert at whole-dataset scale, which is exactly why a per-arm verdict is "
						+ "needed to say anything about it");
		assertEquals(DrugReferenceLoad.Coverage.ABSENT,
				status.coverageOf(DrugReferenceLoad.Arm.DOSE_CEILINGS),
				"DDInter publishes no age band, so warnOnDoseExcess has nothing it can ever fire on and "
						+ "the status says so rather than leaving it to be discovered");
		assertEquals(0, status.entriesPublishing(DrugReferenceLoad.Arm.DOSE_CEILINGS));
		assertEquals(DrugReferenceLoad.Coverage.ABSENT,
				status.coverageOf(DrugReferenceLoad.Arm.HAND_AUTHORED_RULES),
				"and no hand-authored allergy/condition rule either");
		assertEquals(0, status.entriesPublishing(DrugReferenceLoad.Arm.HAND_AUTHORED_RULES));
		assertEquals(DrugReferenceLoad.Coverage.PUBLISHED,
				status.coverageOf(DrugReferenceLoad.Arm.ATC_CODES),
				"while the arms that need only a class code do have data — reporting only what is "
						+ "missing would make this a defect list rather than an answer to 'what is this "
						+ "install checking?'");
		assertTrue(status.entriesPublishing(DrugReferenceLoad.Arm.ATC_CODES) > 0);
	}

	/**
	 * The same verdicts, in the channel that arrives without being asked for. The status endpoint answers
	 * this after the fact and issue #154's rule is that an operator cannot be expected to poll one — so an
	 * install running the shipped default, whose {@code warnOnDoseExcess} reads {@code true} over a
	 * dataset publishing no ceiling, must be able to see that from its log.
	 *
	 * <p>Asserted on the shipped default rather than on a fixture because it is the only case where the
	 * line is MIXED: two arms served and two not, from one dataset, which is what makes it a report rather
	 * than a constant. At INFO, and the WARN half is asserted too: an arm with nothing behind it is a
	 * capability the dataset does not have, not a defect in it, so nothing here may reach the register ADR
	 * Decision 36 reserves for what an operator can act on.
	 */
	@Test
	public void theShippedDefaultSaysWhichArmsItCanServeInTheLogAsWellAsOnTheEndpoint() {
		enableWithNothingElseConfigured();

		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

			assertTrue(status.getEntryCount() > 0, "precondition: a load happened");
			String logged = capture.messagesAt(Level.INFO).toString();
			assertTrue(logged.contains("doseCeilings=absent (0)"),
					"the load must say the dose arm has nothing to fire on, in the channel that does not "
							+ "have to be polled. Captured: " + capture.describeAll());
			assertTrue(logged.contains("handAuthoredRules=absent (0)"),
					"and the hand-authored rule arm likewise. Captured: " + capture.describeAll());
			assertTrue(logged.contains("atcCodes=published ("
					+ status.entriesPublishing(DrugReferenceLoad.Arm.ATC_CODES) + ")"),
					"and it must say what IS served, with the same count the endpoint reports — one "
							+ "rendering, or the two channels can disagree. Captured: "
							+ capture.describeAll());
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"an unserved arm is a capability the dataset lacks, not a defect an operator can fix. "
							+ "Captured: " + capture.describeAll());
		}
	}

	/**
	 * The shipped default must not be LOUD, which is the same rule the untouched path already has: this is
	 * the normal state of every install, so a WARN here is a WARN nobody can act on.
	 *
	 * <p>Not the same as producing no finding, and the difference is the whole of ADR Decision 36's
	 * settlement. This knowledge base is redistributed rather than authored here, and it trips two content
	 * rules on 19 of its 2283 rows whose remedy issue #196 records as an upstream handoff — so the
	 * findings exist, reach {@code GET /chartsearchai/drugreferencestatus} in full, and are reported in
	 * the log at INFO rather than WARN. {@link DrugReferenceFindingLoudnessTest} is where that rule is
	 * specified; this asserts the consequence for the shipped default, which is the case every install
	 * actually runs.
	 */
	@Test
	public void theShippedDefaultLoadsWithoutAWarning() {
		enableWithNothingElseConfigured();

		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

			assertTrue(status.getEntryCount() > 0);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the dataset the module ships and selects by default is not a misconfiguration. "
							+ "Captured: " + capture.describeAll());
			assertFalse(status.getFindings().isEmpty(),
					"and what it does report is not suppressed: the status still carries it, or this "
							+ "default would be quiet by having lost the report rather than by having "
							+ "aimed it at the party who can act on it");
		}
	}

	/**
	 * The bound the default accepts, stated as a property of the data rather than of one entry: no age
	 * band anywhere, so the dose-excess arm cannot fire, and no hand-authored allergy/condition rule, so
	 * contraindications reach the clinician only through the checks that need none of them — a recorded
	 * allergy to the drug itself, a shared ATC subgroup, or a curated cross-reactivity group.
	 */
	@Test
	public void theShippedDefaultCarriesInteractionsOnlyAndNoDosing() {
		enableWithNothingElseConfigured();

		List<DrugReference> entries = new DrugReferenceService().getAll();

		assertTrue(entries.size() >= WHOLE_KNOWLEDGE_BASE_ENTRIES, "precondition: the whole KB is loaded");

		int carryingInteractions = 0;
		for (DrugReference entry : entries) {
			if (!entry.getInteractions().isEmpty()) {
				carryingInteractions++;
			}
		}
		// The ONLY half, asserted rather than assumed: without this the case says nothing about what the
		// dataset does carry, and would pass on one carrying nothing at all — which is the shape a name
		// like this one invites a reader to stop checking. The VOLUME is
		// theShippedDefaultIsTheWholeDdinterKnowledgeBaseBundledInTheModule's link floor; this is the
		// scope claim.
		assertTrue(carryingInteractions > 0,
				"interactions are the one thing this source publishes, so entries must carry them");

		for (DrugReference entry : entries) {
			assertTrue(entry.getAgeBands().isEmpty(),
					"DDInter publishes no dosing, so no entry may carry an age band — a dose ceiling from "
							+ "this source would be invented. " + entry.getName() + " carries "
							+ entry.getAgeBands().size());
			assertTrue(entry.getContraindications().isEmpty(),
					"and no hand-authored contraindication rule either. " + entry.getName() + " carries "
							+ entry.getContraindications().size());
		}
	}
}
