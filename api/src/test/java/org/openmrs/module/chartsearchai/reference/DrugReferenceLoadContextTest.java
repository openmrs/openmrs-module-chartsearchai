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

	private final List<File> created = new ArrayList<File>();

	@AfterEach
	public void deleteCopiedDatasets() {
		for (File file : created) {
			file.delete();
		}
		created.clear();
	}

	/**
	 * Copies a real dataset from the classpath into {@code <appdata>/chartsearchai/} so the
	 * operator-configured branch of the load reads a real file of a known format — the module's own
	 * bundled datasets for the mismatch cases, the real WHO ATC sample for the {@code atc} one.
	 *
	 * @return the path to set {@code dataFilePath} to (relative to the application data directory)
	 */
	private String copyToAppData(String classpathResource, String asName) throws IOException {
		File dir = new File(OpenmrsUtil.getApplicationDataDirectory(), "chartsearchai");
		dir.mkdirs();
		File target = new File(dir, asName);
		created.add(target);
		String resource = classpathResource.startsWith("/") ? classpathResource.substring(1)
				: classpathResource;
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
			assertNotNull(in, "dataset " + classpathResource + " should be on the classpath");
			Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		return "chartsearchai/" + asName;
	}

	/**
	 * Sets the three global properties a load reads, including turning the feature ON — every case
	 * here needs the master switch on, so it is not a separate step a case can forget. The one case
	 * that wants it off sets it directly.
	 */
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
		String path = copyToAppData(JsonDrugReferenceSource.CLASSPATH_DEFAULT, "mismatch-curated.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, path);

		List<DrugReference> entries;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
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
		String path = copyToAppData(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "mismatch-ddi.json");
		configure(ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT, path);

		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			assertTrue(new DrugReferenceService().getAll().isEmpty(),
					"the curated parser finds no 'entries' key in the DDInter knowledge base");
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"the mismatch is symmetric and must be loud in this direction too. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void healthyLoadIsNotReportedAtWarnOrError() throws IOException {
		String path = copyToAppData(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "healthy-ddi.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, path);

		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
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

		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
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
		String path = copyToAppData(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "inforce-ddi.json");
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
		String path = copyToAppData(JsonDrugReferenceSource.CLASSPATH_DEFAULT, "inert-curated.json");
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
	 * The origin says WHICH dataset was read; it must not additionally disclose where on the server
	 * it lives. Both forms are marked with the space they name — {@code appdata:} for an operator file
	 * (always relative: the resolver rejects {@code ..} and confirms the file is inside the
	 * application data directory) and {@code classpath:} for the bundled one — so the distinction the
	 * status exists to make is unaffected.
	 *
	 * <p>The gate on this status is the core {@code Get Global Properties} privilege, which the
	 * {@code Authenticated} role holds by default on a Reference Application install, so every logged-in
	 * user can read it. That is right for the two configured global properties, which such a user can
	 * already read through {@code GET /systemsetting}, and wrong for an absolute path: core keeps its
	 * own disclosure of the application data directory behind {@code View Administration Functions}
	 * ({@code GET /systeminformation}). Measured on the 3.7.1 standalone, 2026-08-05: a user whose only
	 * role carried no privileges got 200 from both this status and {@code /systemsetting}, and 403 from
	 * {@code /systeminformation}.
	 */
	@Test
	public void loadStatusNamesTheFileReadWithoutDisclosingItsAbsolutePath() throws IOException {
		String path = copyToAppData(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "relative-ddi.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, path);

		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

			assertEquals("appdata:" + path, status.getOrigin(),
					"the origin must name the operator file relative to the application data directory, "
							+ "marked with the space it names");
			assertFalse(status.getOrigin().contains(OpenmrsUtil.getApplicationDataDirectory()),
					"the status is readable by any authenticated user, so it must not hand out the "
							+ "server's absolute application-data path. Origin was: " + status.getOrigin());
			// The other half of that trade-off, and the reason nothing is lost by it: the log still
			// carries the absolute path, where the audience is already an administrator. Asserted
			// because it is the compensating control — logging the relative path here instead would
			// leave no route to it at all, and would do so with a green build.
			assertTrue(
					capture.messagesAt(Level.INFO).stream()
							.anyMatch(m -> m.contains(OpenmrsUtil.getApplicationDataDirectory())),
					"the load must still report the absolute path at INFO. Captured: "
							+ capture.describeAll());
		}
	}

	/**
	 * A {@code sourceFormat} matching no adapter falls back to the curated {@code json} parser, which
	 * is one of the ways a deployment ends up parsing a dataset with the wrong parser. The status is
	 * what makes that visible, and only because it reports the configured value and the effective one
	 * SEPARATELY — this is the divergence {@link DrugReferenceLoad#getSourceFormat()}'s javadoc
	 * describes, and the only thing that reads the configured value back.
	 *
	 * <p>Note what is NOT loud here: this typo happens to point at a dataset the curated parser can
	 * read, so it loads entries and warns about nothing. The two fields differing is the only signal.
	 */
	@Test
	public void loadStatusReportsAMistypedSourceFormatSeparatelyFromTheOneInForce() throws IOException {
		String path = copyToAppData(JsonDrugReferenceSource.CLASSPATH_DEFAULT, "typo-curated.json");
		configure("ddintr", path);

		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

			assertEquals("ddintr", status.getConfiguredSourceFormat(),
					"the raw global-property value is reported as configured, typo and all");
			assertEquals(ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT,
					status.getSourceFormat(),
					"an unrecognised format silently falls back to the curated parser, and the status "
							+ "is where that becomes visible rather than silent");
			assertTrue(status.getEntryCount() > 0, "the curated parser reads the curated dataset");
			assertFalse(status.isInert(), "it loaded entries, so the safety layer is not inert");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a typo that still yields entries is NOT loud — the two format fields differing is "
							+ "the only signal, which is why both are reported. Captured: "
							+ capture.describeAll());
		}
	}

	/**
	 * The {@code atc} format is the one source that does NOT go through the shared
	 * {@code ReferenceDataFiles} loader — it has no bundled fallback, so it resolves and tracks its own
	 * file. Its origin is therefore separate code, and this is the only test that executes it. Uses the
	 * real WHO ATC sample through the real {@link AtcDrugReferenceSource}.
	 */
	@Test
	public void loadStatusNamesTheAtcExportItRead() throws IOException {
		String path = copyToAppData(DrugReferenceTestSupport.ATC_SAMPLE, "h154-atc-sample.tsv");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_ATC, path);

		DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

		assertEquals(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_ATC, status.getSourceFormat());
		assertTrue(status.getEntryCount() > 0, "the WHO ATC sample parses to classification entries");
		assertFalse(status.isInert());
		assertEquals("appdata:" + path, status.getOrigin(),
				"the atc source tracks its own origin rather than going through the shared loader, so "
						+ "it has to report the same form. Origin was: " + status.getOrigin());
	}

	/**
	 * The atc format has no bundled fallback, so selecting it without a dataset path loads nothing —
	 * one of the states the inert verdict has to cover, and the only one that is not a
	 * format/path mismatch.
	 */
	@Test
	public void atcFormatWithNoConfiguredPathIsInertAndNamesNoOrigin() {
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_ATC, "");

		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

			assertTrue(status.isInert(), "an ATC source with nothing to read leaves safety checking off");
			assertEquals(0, status.getEntryCount());
			assertEquals("none", status.getOrigin(), "nothing was read, and the origin says so");
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"the feature is inert, so it must be loud here too. Captured: " + capture.describeAll());
		}
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

		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
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
	@SuppressWarnings("unchecked")
	public void loadStatusSerializesTheFieldsTheStatusEndpointReturns() throws IOException {
		String path = copyToAppData(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "wire-ddi.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, path);

		DrugReferenceService service = new DrugReferenceService();
		Map<String, Object> map = service.getLoadStatus().toMap();

		assertEquals(new LinkedHashSet<String>(Arrays.asList("loaded", "inert", "entryCount",
				"sourceFormat", "configuredSourceFormat", "configuredDataFilePath", "origin", "package")),
				map.keySet());
		assertEquals(Boolean.TRUE, map.get("loaded"));
		assertEquals(Boolean.FALSE, map.get("inert"));
		assertEquals(service.getAll().size(), map.get("entryCount"));
		assertEquals(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, map.get("sourceFormat"));
		assertEquals(path, map.get("configuredDataFilePath"));
		Map<String, Object> sourcePackage = (Map<String, Object>) map.get("package");
		assertEquals("openmrs-ddi-knowledge-base-unreviewed", sourcePackage.get("id"));
		assertEquals(DrugReferencePackage.REVIEW_PROPOSED, sourcePackage.get("review_state"));
		assertEquals("appdata:" + path,
				((Map<String, Object>) sourcePackage.get("provenance")).get("origin"));
	}

	/**
	 * The status describes the load that is IN FORCE, so it must not drift from the cached entries:
	 * changing the GPs after the load changes neither, until the module restarts. This is the
	 * property that makes the status trustworthy where the log line is not.
	 */
	@Test
	public void loadStatusDoesNotDriftFromTheCachedEntriesWhenTheGlobalPropertiesChange()
			throws IOException {
		String path = copyToAppData(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "stable-ddi.json");
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
