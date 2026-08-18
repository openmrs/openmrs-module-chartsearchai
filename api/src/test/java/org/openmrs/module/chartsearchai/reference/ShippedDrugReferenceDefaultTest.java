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
