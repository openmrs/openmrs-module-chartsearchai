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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsUtil;

/**
 * Issue #149: a {@code sourceFormat}/{@code dataFilePath} mismatch loads ZERO drug-reference
 * entries, which turns the whole drug-safety feature off, and used to be reported at INFO exactly
 * as a successful load ({@code ReferenceDataFiles} logged {@code "Loaded 0 …"} as cheerfully as
 * {@code "Loaded 2283 …"}). Every safety question then answers as though the patient had no
 * interactions, allergies or contraindications to find.
 *
 * <p>Both halves of that defect are pinned here, through the production load path
 * ({@link DrugReferenceService#getAll()} / {@link DrugReferenceService#getLoadStatus()} over the
 * real GPs and the real datasets the module ships):
 *
 * <ol>
 * <li><b>Loud on empty.</b> A configured source that yields nothing is reported at WARN, and a
 * healthy load stays quiet. Asserted on the LEVEL, never the message text — an empty list is the
 * correct fail-safe return in both cases, so the level is the only observable difference, and a
 * text assertion would let a re-wording silently drop the guard.</li>
 * <li><b>Observable after the load.</b> The load is lazy, so reading the log line right after
 * flipping the GPs reads the PREVIOUS source's line. {@link DrugReferenceService#getLoadStatus()}
 * reports the load that is in force instead, triggering it if it has not happened yet.</li>
 * </ol>
 *
 * <p>The mismatch cases use the module's OWN two bundled datasets, copied into the application data
 * directory and cross-wired: each source format parses only its own shape and returns nothing —
 * without failing — for the other's. That is the real production defect, not a synthetic file.
 */
public class DrugReferenceLoadContextTest extends BaseModuleContextSensitiveTest {

	/** Everything the drug-reference load logs lives under this package. */
	private static final String REFERENCE_LOGGER = "org.openmrs.module.chartsearchai.reference";

	private final List<File> created = new ArrayList<File>();

	@AfterEach
	public void deleteCopiedDatasets() {
		for (File file : created) {
			file.delete();
		}
		created.clear();
	}

	/**
	 * Copies one of the module's real bundled datasets into {@code <appdata>/chartsearchai/} so the
	 * operator-configured branch of the load reads a real file of a known format.
	 *
	 * @return the path to set {@code dataFilePath} to (relative to the application data directory)
	 */
	private String copyBundledDataset(String classpathResource, String asName) throws IOException {
		File dir = new File(OpenmrsUtil.getApplicationDataDirectory(), "chartsearchai");
		dir.mkdirs();
		File target = new File(dir, asName);
		created.add(target);
		try (InputStream in = DrugReferenceService.class.getResourceAsStream(classpathResource)) {
			assertNotNull(in, "bundled dataset " + classpathResource + " should be on the classpath");
			Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		return "chartsearchai/" + asName;
	}

	private void configure(String sourceFormat, String dataFilePath) {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT, sourceFormat);
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, dataFilePath);
	}

	// ------------------------------------------------------------------
	// 1. Loud on empty
	// ------------------------------------------------------------------

	@Test
	public void ddinterFormatPointedAtTheCuratedDatasetLoadsNothingAndIsReportedAtWarn() throws IOException {
		String path = copyBundledDataset(JsonDrugReferenceSource.CLASSPATH_DEFAULT, "mismatch-curated.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, path);

		List<DrugReference> entries;
		try (LogCapture capture = LogCapture.on(REFERENCE_LOGGER)) {
			entries = new DrugReferenceService().getAll();

			assertTrue(entries.isEmpty(),
					"the DDInter parser finds none of its keys in the curated dataset, so the load is empty");
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"a configured source that resolves to 0 entries must NOT be reported like a "
							+ "successful load — the whole drug-safety feature is inert. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void curatedFormatPointedAtTheDdiKnowledgeBaseLoadsNothingAndIsReportedAtWarn() throws IOException {
		String path = copyBundledDataset(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "mismatch-ddi.json");
		configure(ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT, path);

		try (LogCapture capture = LogCapture.on(REFERENCE_LOGGER)) {
			assertTrue(new DrugReferenceService().getAll().isEmpty(),
					"the curated parser finds no 'entries' key in the DDInter knowledge base");
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"the mismatch is symmetric and must be loud in this direction too. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void healthyLoadIsNotReportedAtWarnOrError() throws IOException {
		String path = copyBundledDataset(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "healthy-ddi.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, path);

		try (LogCapture capture = LogCapture.on(REFERENCE_LOGGER)) {
			assertFalse(new DrugReferenceService().getAll().isEmpty(),
					"the DDInter parser reads its own dataset");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a matched format/path pair must stay quiet, or the warning is noise every "
							+ "install learns to ignore. Captured: " + capture.describeAll());
			assertFalse(capture.messagesAt(Level.INFO).isEmpty(),
					"the healthy load is still reported at INFO");
		}
	}

	@Test
	public void bundledDefaultLoadIsNotReportedAtWarnOrError() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");

		try (LogCapture capture = LogCapture.on(REFERENCE_LOGGER)) {
			assertFalse(new DrugReferenceService().getAll().isEmpty(),
					"with no dataFilePath configured the bundled curated dataset loads");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"shipping defaults is the normal state and must be silent. Captured: "
							+ capture.describeAll());
		}
	}

	// ------------------------------------------------------------------
	// 2. Observable after the load
	// ------------------------------------------------------------------

	@Test
	public void loadStatusReportsTheFormatCountAndFileActuallyInForce() throws IOException {
		String path = copyBundledDataset(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "inforce-ddi.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, path);

		DrugReferenceService service = new DrugReferenceService();
		DrugReferenceLoad status = service.getLoadStatus();

		assertTrue(status.isLoaded(), "reading the status must trigger the lazy load, not report 'not yet'");
		assertEquals(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, status.getSourceFormat());
		assertEquals(service.getAll().size(), status.getEntryCount(),
				"the reported count must be the count of the entries actually in force");
		assertTrue(status.getEntryCount() > 0);
		assertFalse(status.isInert());
		assertTrue(status.getOrigin().endsWith("inforce-ddi.json"),
				"the status must name the file the entries were read from, not merely the configured "
						+ "path — a configured path that could not be read falls back to the bundled "
						+ "dataset, and that is the state a source-flip check misreads. Origin was: "
						+ status.getOrigin());
	}

	@Test
	public void loadStatusOfAMismatchedPairIsInertAndNamesBothGlobalProperties() throws IOException {
		String path = copyBundledDataset(JsonDrugReferenceSource.CLASSPATH_DEFAULT, "inert-curated.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, path);

		DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

		assertTrue(status.isLoaded());
		assertTrue(status.isInert(), "0 entries from a source that WAS configured is the inert state");
		assertEquals(0, status.getEntryCount());
		// Both GPs, so the mismatch is attributable without reading a log at all.
		assertEquals(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, status.getSourceFormat());
		assertEquals(path, status.getConfiguredDataFilePath());
	}

	/**
	 * A configured path that cannot be read falls back to the bundled dataset and produces a
	 * perfectly plausible entry count — the state in which "the count is non-zero, so my source is
	 * loaded" is FALSE. The origin is what separates the two, which is why it is reported.
	 */
	@Test
	public void loadStatusNamesTheBundledOriginWhenTheConfiguredFileIsAbsent() {
		configure(ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT,
				"chartsearchai/no-such-drug-reference.json");

		DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

		assertTrue(status.getEntryCount() > 0, "the absent file falls back to the bundled dataset");
		assertFalse(status.isInert());
		assertEquals("chartsearchai/no-such-drug-reference.json", status.getConfiguredDataFilePath(),
				"the configured path is reported as configured");
		assertTrue(status.getOrigin().contains(JsonDrugReferenceSource.CLASSPATH_DEFAULT),
				"the origin must say the entries came from the BUNDLED dataset, not the configured "
						+ "file that could not be read. Origin was: " + status.getOrigin());
	}

	/**
	 * The feature being switched off is a legitimate state, not the defect: nothing is configured to
	 * load, so nothing must be loaded and nothing must be warned about. Reading the status must not
	 * manufacture the load (and therefore the WARN) on an install that does not use the feature.
	 */
	@Test
	public void loadStatusOfADisabledFeatureReportsNoLoadAndTriggersNone() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "false");

		try (LogCapture capture = LogCapture.on(REFERENCE_LOGGER)) {
			DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

			assertFalse(status.isLoaded(), "a disabled feature must not be loaded to report its status");
			assertFalse(status.isInert(),
					"'nothing configured at all' is not 'a configured source yielded nothing'");
			assertEquals(0, status.getEntryCount());
			assertTrue(capture.describeAll().isEmpty(),
					"reading the status of a disabled feature must log nothing at all. Captured: "
							+ capture.describeAll());
		}
	}

	/**
	 * The map the {@code GET /chartsearchai/drugreferencestatus} endpoint serializes. Pinned here
	 * because it is what an operator (or a source-flip check) actually reads, and because a
	 * disappearing key would leave that check silently reading {@code null}.
	 */
	@Test
	public void loadStatusSerializesTheFieldsTheStatusEndpointReturns() throws IOException {
		String path = copyBundledDataset(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "wire-ddi.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, path);

		DrugReferenceService service = new DrugReferenceService();
		Map<String, Object> map = service.getLoadStatus().toMap();

		assertEquals(new LinkedHashSet<String>(Arrays.asList("loaded", "inert", "entryCount",
				"sourceFormat", "configuredSourceFormat", "configuredDataFilePath", "origin")),
				map.keySet());
		assertEquals(Boolean.TRUE, map.get("loaded"));
		assertEquals(Boolean.FALSE, map.get("inert"));
		assertEquals(service.getAll().size(), map.get("entryCount"));
		assertEquals(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, map.get("sourceFormat"));
		assertEquals(path, map.get("configuredDataFilePath"));
	}

	/**
	 * The status describes the load that is IN FORCE, so it must not drift from the cached entries:
	 * changing the GPs after the load changes neither, until the module restarts. This is the
	 * property that makes the status trustworthy where the log line is not.
	 */
	@Test
	public void loadStatusDoesNotDriftFromTheCachedEntriesWhenTheGlobalPropertiesChange()
			throws IOException {
		String path = copyBundledDataset(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "stable-ddi.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, path);

		DrugReferenceService service = new DrugReferenceService();
		DrugReferenceLoad first = service.getLoadStatus();

		// Flip both GPs to a mismatched pair AFTER the load, exactly as an operator or a
		// verification pass would.
		configure(ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT, path);

		DrugReferenceLoad second = service.getLoadStatus();
		assertEquals(first.getSourceFormat(), second.getSourceFormat(),
				"the status reports the load in force, not the GPs as they read now");
		assertEquals(first.getEntryCount(), second.getEntryCount());
		assertEquals(service.getAll().size(), second.getEntryCount(),
				"and it still agrees with the entries the safety layer is using");
	}
}
