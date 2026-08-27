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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * How loudly a validity finding is reported, which since ADR Decision 36 depends on WHOSE dataset it
 * is about — because the module now ships a third-party knowledge base as its default, and a finding
 * about that dataset names something no operator can fix.
 *
 * <p><b>The rule.</b> A finding about the DATA scales with who owns the dataset: read from the
 * application data directory it is the operator's file and stays at WARN, read from the module's own
 * classpath it is the dataset we ship and drops to INFO, whose remedy is the upstream handoff issue
 * #196 defines. A finding about the CONFIGURATION never scales — it is a choice the operator made and
 * can unmake, and it is loud even on a classpath-origin load, which is exactly when it fires.
 *
 * <p><b>What does not change is the other channel.</b> Both cases put the identical finding —
 * rule, remedy, occurrences, detail — on {@link DrugReferenceService#getLoadStatus()} and so on
 * {@code GET /chartsearchai/drugreferencestatus}. Nothing is muted: the log level says who can act,
 * the status says what is true. That is the distinction the alternative got wrong, and it is why this
 * is not the "observable but not loud" position issues #149 and #154 ruled out — those are about a
 * misconfiguration and a wrong load, both of which are still WARN here.
 *
 * <p>Every case drives the real {@link DrugReferenceService} load over a real dataset, so the origin
 * is the one the production resolution produced rather than one a test asserted.
 */
public class DrugReferenceFindingLoudnessTest extends BaseModuleContextSensitiveTest {

	/** A DDInter-shaped fixture that trips {@link DrugReferenceValidity#ALIAS_NAMES_ANOTHER_SUBSTANCE} —
	 *  the same rule the shipped knowledge base trips, so the only variable between the two cases below
	 *  is where the dataset was read from. Shared with the rule's own case rather than spelled again. */
	private static final String DEFECTIVE_FIXTURE = DrugReferenceTestSupport.DDI_ALIAS_NAMES_ANOTHER_SUBSTANCE;

	private final List<File> created = new ArrayList<File>();

	@AfterEach
	public void deleteCopiedDatasets() {
		for (File file : created) {
			file.delete();
		}
		created.clear();
	}

	private String copyToAppData(String classpathResource, String asName) throws IOException {
		return DrugReferenceTestSupport.copyDatasetToAppData(classpathResource, asName, created);
	}

	private void enable() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	private static List<String> rulesOf(DrugReferenceLoad status) {
		return DrugReferenceTestSupport.rulesOf(status.getFindings());
	}

	private static DrugReferenceValidity.Finding finding(DrugReferenceLoad status, String rule) {
		return DrugReferenceTestSupport.finding(status.getFindings(), rule);
	}

	/**
	 * Every rule is classified DELIBERATELY, one way or the other. An unclassified rule is loud
	 * everywhere, which is the safe direction and still the wrong register for a defect in a dataset the
	 * module ships — and nothing about it would otherwise fail, so the next content rule added would
	 * quietly restore the WARN-on-every-install noise this scoping removed. Reflection over the rule
	 * constants rather than a second list, because a second list is a thing to forget twice.
	 */
	@Test
	public void everyRuleIsClassifiedAsDataOrAsConfiguration() throws Exception {
		List<String> rules = new ArrayList<String>();
		for (Field field : DrugReferenceValidity.class.getDeclaredFields()) {
			if (field.getType() == String.class && Modifier.isPublic(field.getModifiers())
					&& Modifier.isStatic(field.getModifiers())) {
				rules.add((String) field.get(null));
			}
		}
		assertTrue(rules.size() >= 9,
				"the reflection has to find the rule constants, or this check is vacuous — found " + rules);

		List<String> configuration = new ArrayList<String>();
		for (String rule : rules) {
			if (!DrugReferenceValidity.scopedToWhoOwnsTheDataset(rule)) {
				configuration.add(rule);
			}
		}
		assertEquals("[" + DrugReferenceValidity.CONFIGURED_DATA_FILE_NOT_READ + ", "
				+ DrugReferenceValidity.CONFIGURED_SOURCE_FORMAT_NOT_USED + "]", configuration.toString(),
				"exactly the two configuration rules are loud whatever dataset was read. A new rule "
						+ "showing up here has been left out of DATA_RULES: classify it — if its subject is "
						+ "the dataset's content, it belongs in that list, or it will WARN on every install "
						+ "of every deployment about rows only we can fix");
	}

	@Test
	public void aDataFindingAboutTheOperatorsOwnFileIsLoud() throws IOException {
		enable();
		String path = copyToAppData(DEFECTIVE_FIXTURE, "loudness-operator.json");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT,
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER);
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, path);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = new DrugReferenceService().getLoadStatus();

			assertTrue(capture.messagesAt(Level.WARN).toString()
					.contains(DrugReferenceValidity.ALIAS_NAMES_ANOTHER_SUBSTANCE),
					"the operator can fix their own file, so the finding must reach them at WARN. "
							+ "Captured: " + capture.describeAll());
		}
		assertTrue(status.getOrigin().startsWith(ReferenceDataFiles.APPDATA_ORIGIN_PREFIX),
				"precondition: the operator's file is what was read. Origin was: " + status.getOrigin());
		assertTrue(rulesOf(status).contains(DrugReferenceValidity.ALIAS_NAMES_ANOTHER_SUBSTANCE));
	}

	@Test
	public void theSameDataFindingAboutTheDatasetWeShipIsReportedWithoutBeingLoud() {
		enable();

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = new DrugReferenceService().getLoadStatus();

			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a defect in the dataset the MODULE ships is not something an operator can act on, "
							+ "and this is every install's normal state. Captured: " + capture.describeAll());
			assertTrue(capture.messagesAt(Level.INFO).toString()
					.contains(DrugReferenceValidity.ALIAS_NAMES_ANOTHER_SUBSTANCE),
					"but it is still reported, in the register of the party that can fix it. Captured: "
							+ capture.describeAll());
		}
		assertTrue(status.getOrigin().startsWith(ReferenceDataFiles.CLASSPATH_ORIGIN_PREFIX),
				"precondition: the bundled dataset is what was read. Origin was: " + status.getOrigin());
	}

	/**
	 * The half that makes the softening safe: the status endpoint is not softened with the log. A caller
	 * polling it after a lazy load gets the same rule, remedy, occurrence count and detail whichever
	 * dataset was read — which is what separates this from muting the finding.
	 */
	@Test
	public void theStatusChannelIsIdenticalWhicheverDatasetTheFindingIsAbout() throws IOException {
		enable();
		// The bundled dataset, with nothing configured — the softened side.
		DrugReferenceLoad bundled = new DrugReferenceService().getLoadStatus();

		// The SAME file, as the operator's own — the loud side. One dataset read twice is what makes
		// this a comparison rather than two unrelated readings, and each service instance performs its
		// own lazy load, so the second is a real second resolution.
		String path = copyToAppData(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "loudness-same-file.json");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, path);
		DrugReferenceLoad operators = new DrugReferenceService().getLoadStatus();

		assertTrue(bundled.getOrigin().startsWith(ReferenceDataFiles.CLASSPATH_ORIGIN_PREFIX)
				&& operators.getOrigin().startsWith(ReferenceDataFiles.APPDATA_ORIGIN_PREFIX),
				"precondition: the two loads must differ in ORIGIN and nothing else. Were: "
						+ bundled.getOrigin() + " and " + operators.getOrigin());
		assertEquals(bundled.getEntryCount(), operators.getEntryCount(),
				"precondition: the same dataset, so the same entries");

		assertEquals(publishedFindings(bundled).toString(), publishedFindings(operators).toString(),
				"the status channel is not scoped with the log: every finding must reach it with the same "
						+ "rule, remedy, occurrence count and detail whoever owns the dataset. This is the "
						+ "assertion that fails if toMap() is ever scoped by origin, which the log level's "
						+ "existence makes a tempting thing to 'fix'");
	}

	/** Each finding as {@code rule/remedy/occurrences/detail}, which is the whole of what the status
	 *  publishes — compared as a list so an extra or missing finding fails too, not only a changed one. */
	private static List<String> publishedFindings(DrugReferenceLoad status) {
		List<String> out = new ArrayList<String>();
		for (DrugReferenceValidity.Finding found : status.getFindings()) {
			out.add(found.getRule() + "/" + found.getRemedy() + "/" + found.getOccurrences() + "/"
					+ found.getDetail());
		}
		return out;
	}

	/**
	 * The regression this rule could otherwise introduce, and the reason the softening is keyed on the
	 * rule's subject rather than on the origin alone: issue #156's finding fires exactly when the
	 * operator's file was NOT read and the bundled dataset was, so an origin-only rule would silence the
	 * one case both #149 and #154 exist for.
	 */
	@Test
	public void aConfigurationFindingIsLoudEvenThoughTheBundledDatasetWasRead() {
		enable();
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH,
				"chartsearchai/loudness-no-such-file.json");

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = new DrugReferenceService().getLoadStatus();

			assertTrue(capture.messagesAt(Level.WARN).toString()
					.contains(DrugReferenceValidity.CONFIGURED_DATA_FILE_NOT_READ),
					"the operator named a file and a different dataset is in force; that is their choice "
							+ "to unmake, so it stays loud. Captured: " + capture.describeAll());
		}
		assertTrue(status.getOrigin().startsWith(ReferenceDataFiles.CLASSPATH_ORIGIN_PREFIX),
				"precondition: the fallback means the bundled dataset was read. Origin was: "
						+ status.getOrigin());
	}
}
