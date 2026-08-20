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
		return DrugReferenceTestSupport.copyDatasetToAppData(classpathResource, asName, created);
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
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON, path);

		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			assertTrue(new DrugReferenceService().getAll().isEmpty(),
					"the curated parser finds no 'entries' key in the DDInter knowledge base");
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"the mismatch is symmetric and must be loud in this direction too. Captured: "
							+ capture.describeAll());
		}
	}

	/**
	 * A HEALTHY operator file, which means one with nothing for a rule to report: the 16-drug DDInter
	 * excerpt rather than the shipped knowledge base, because that knowledge base is not healthy in this
	 * sense — it trips two content rules on 19 of its rows (ADR Decision 36), and read from the
	 * application data directory it is an operator's file, so those findings are correctly LOUD. Using it
	 * here would have this case assert that a dataset with known defects is quiet, which is the opposite
	 * of what the rule says and would have to be weakened again the next time a rule was added.
	 */
	@Test
	public void healthyLoadIsNotReportedAtWarnOrError() throws IOException {
		String path = copyToAppData(DrugReferenceTestSupport.DDI_EXCERPT, "healthy-ddi.json");
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
	 * <p>And it IS loud, since issue #156. This assertion used to require the opposite — that a typo
	 * still yielding entries needed no WARN, because the two format fields differing was signal enough.
	 * That ground was the confusion issues #149 and #154 settled: <b>observable is not the same as
	 * loud.</b> #154 built this status precisely because an operator cannot be expected to poll it, and
	 * #149 exists because a wrong load reported at INFO is indistinguishable from a right one. The
	 * property the old assertion protected — that the load is NOT inert, so a typo pointing at a readable
	 * dataset is not the #149 failure — is still asserted here, on {@code isInert()} and the entry count,
	 * which is where it belongs; the log level was never what carried it.
	 */
	@Test
	public void loadStatusReportsAMistypedSourceFormatSeparatelyFromTheOneInForce() throws IOException {
		String path = copyToAppData(JsonDrugReferenceSource.CLASSPATH_DEFAULT, "typo-curated.json");
		configure("ddintr", path);

		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

			assertEquals("ddintr", status.getConfiguredSourceFormat(),
					"the raw global-property value is reported as configured, typo and all");
			assertEquals(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON,
					status.getSourceFormat(),
					"an unrecognised format silently falls back to the curated parser, and the status "
							+ "is where that becomes visible rather than silent");
			assertTrue(status.getEntryCount() > 0, "the curated parser reads the curated dataset");
			assertFalse(status.isInert(), "it loaded entries, so the safety layer is not inert");
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"the operator named a parser and a different one is in force, which is issue #156 "
							+ "case 2: a format that matches no adapter must be reported, not left to be "
							+ "noticed by comparing two fields on a status nobody polls. Captured: "
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
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON,
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
	 * A rule the module cannot put to a chart is not capability. The arms report counts entries through
	 * {@link DrugSafetyValidator#evaluatesAgainstTheChart}, not through a non-empty contraindication
	 * list, and this is the case that tells the two apart: both entries here publish a rule, and neither
	 * rule is askable — one typed {@code diagnosis}, which is neither of the two chart lists the module
	 * has, and one typed {@code condition} whose token is whitespace, which
	 * {@code PatientClinicalContext.matchableToken} refuses.
	 *
	 * <p>Without this case the weaker predicate passes every test in the suite while publishing
	 * capability the arm does not have — an operator reading {@code handAuthoredRules: published} would
	 * conclude their rules are being evaluated when nothing can ever match. That is the "looks healthy,
	 * checks nothing" state the arms report exists to remove, reintroduced by the report itself.
	 *
	 * <p>{@code atcCodes} is asserted alongside deliberately: it shows the dataset DID load and its other
	 * arm is served, so the {@code ABSENT} above is a measurement rather than a fixture that could not
	 * have produced anything.
	 */
	@Test
	public void anUnaskableRuleIsNotReportedAsHandAuthoredCapability() throws IOException {
		String path = copyToAppData("/chartsearchai-test/drug-reference-unevaluable-rules-only.json",
				"unevaluable-rules.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON, path);

		DrugReferenceService service = new DrugReferenceService();
		DrugReferenceLoad status = service.getLoadStatus();

		assertEquals(2, status.getEntryCount(), "precondition: both entries load");
		for (DrugReference entry : service.getAll()) {
			assertFalse(entry.getContraindications().isEmpty(),
					entry.getName() + " publishes a rule, which is what makes this case discriminating: "
							+ "a count of non-empty lists would report capability here");
		}
		assertEquals(DrugReferenceLoad.Coverage.ABSENT,
				status.coverageOf(DrugReferenceLoad.Arm.HAND_AUTHORED_RULES),
				"neither rule can be evaluated against a chart, so the arm has nothing to act on");
		assertEquals(0, status.entriesPublishing(DrugReferenceLoad.Arm.HAND_AUTHORED_RULES));
		assertEquals(DrugReferenceLoad.Coverage.PUBLISHED,
				status.coverageOf(DrugReferenceLoad.Arm.ATC_CODES),
				"while the class arms are served by the same entries, so the dataset did load");
	}

	/**
	 * The other direction, and the only form an operator ever actually reads: a dataset that DOES serve
	 * its arms, serialized. The curated four-drug seed the module ships carries a ceiling, an askable
	 * rule, a level-5 class code and an interaction on every entry, so all four arms report
	 * {@code published} — and this is the case that pins the {@code toMap} rendering of a LOADED report.
	 *
	 * <p>It is the only case in the suite that reddens when the {@code PUBLISHED} rendering breaks.
	 * Measured by mutating {@code toMap}: rendering a {@code PUBLISHED} arm as {@code "unloaded"} fails
	 * this case alone, while hardcoding BOTH puts to {@code "unloaded"} and {@code 0} fails this case and
	 * {@link #aFieldWithNothingActionableInItIsNotReportedAsCapability} together — that sibling reads the
	 * map too, and asserts {@code "absent"} on it. Only these two do; the other arm cases call
	 * {@code coverageOf} directly, and the endpoint's own case
	 * ({@code ChartSearchAiDrugReferenceStatusTest.drugReferenceStatus_reportsEveryArmAsUnloadedWhenNothingIsLoaded})
	 * asserts the unloaded shape, which no mutation of a loaded verdict can reach.
	 */
	@Test
	public void aDatasetThatServesItsArmsSaysSoInTheSerializedStatus() {
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON, "");

		DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

		assertEquals(4, status.getEntryCount(), "precondition: the curated seed loaded");
		for (DrugReferenceLoad.Arm arm : DrugReferenceLoad.Arm.values()) {
			assertEquals(DrugReferenceLoad.Coverage.PUBLISHED, status.coverageOf(arm),
					arm + ": every entry of the curated seed carries what each arm needs");
			assertEquals(4, status.entriesPublishing(arm), arm + ": on all four entries");
		}

		Map<?, ?> arms = (Map<?, ?>) status.toMap().get("arms");
		Map<?, ?> dosing = (Map<?, ?>) arms.get(DrugReferenceLoad.Arm.DOSE_CEILINGS.getWireKey());
		assertEquals("published", dosing.get("coverage"),
				"the verdict reaches the map, rather than the map being able to say only 'unloaded'");
		assertEquals(Integer.valueOf(4), dosing.get("entriesPublishing"),
				"and so does the count");
	}

	/**
	 * Presence of a field is not capability, on every arm that can tell the two apart. This entry
	 * publishes an age band with no ceiling at all, an ATC code one character too short to reduce to the
	 * level-4 subgroup the class arms compare, and no interactions — so each arm has data in its field
	 * and nothing it can act on.
	 *
	 * <p>Each assertion here fails against the obvious weaker predicate: counting non-empty
	 * {@code getAgeBands()} reports the dosing arm over bands no patient can be measured against
	 * ({@code AgeBand}'s ceilings are primitives defaulting to 0, so such a band parses perfectly well),
	 * and counting {@code normalizedAtcCodes()} reports the class arms over a code
	 * {@code atcSubgroups()} discards.
	 */
	@Test
	public void aFieldWithNothingActionableInItIsNotReportedAsCapability() throws IOException {
		String path = copyToAppData("/chartsearchai-test/drug-reference-fields-without-capability.json",
				"fields-without-capability.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON, path);

		DrugReferenceService service = new DrugReferenceService();
		DrugReferenceLoad status = service.getLoadStatus();

		assertEquals(1, status.getEntryCount(), "precondition: the entry loaded");
		DrugReference entry = service.getAll().get(0);
		assertFalse(entry.getAgeBands().isEmpty(),
				"precondition: a band IS published, which is what makes the dosing assertion below "
						+ "discriminating rather than trivially true");
		assertFalse(entry.normalizedAtcCodes().isEmpty(),
				"precondition: a code IS published, likewise");

		assertEquals(DrugReferenceLoad.Coverage.ABSENT,
				status.coverageOf(DrugReferenceLoad.Arm.DOSE_CEILINGS),
				"a band with neither a daily maximum nor a per-kg maximum can never fire for any patient");
		assertEquals(DrugReferenceLoad.Coverage.ABSENT,
				status.coverageOf(DrugReferenceLoad.Arm.ATC_CODES),
				"and a code shorter than a level-4 subgroup is one no class arm can match on");
		assertEquals(DrugReferenceLoad.Coverage.ABSENT,
				status.coverageOf(DrugReferenceLoad.Arm.INTERACTIONS),
				"and this dataset publishes no interaction at all");

		// On the WIRE as well, and not only through the accessor: mapping ABSENT onto "unloaded" inside
		// toMap would otherwise pass the whole suite, which is the "we looked and there is none" versus
		// "nobody looked" conflation this field exists to remove, reintroduced on the only channel an
		// operator reads.
		Map<?, ?> arms = (Map<?, ?>) status.toMap().get("arms");
		Map<?, ?> dosing = (Map<?, ?>) arms.get(DrugReferenceLoad.Arm.DOSE_CEILINGS.getWireKey());
		assertEquals("absent", dosing.get("coverage"),
				"a dataset WAS read and publishes no ceiling — 'unloaded' here would say nobody looked");
		assertEquals(Integer.valueOf(0), dosing.get("entriesPublishing"));
	}

	/**
	 * A null element inside a rule or band list must not take the loader down. The parsers drop null
	 * ENTRIES and nothing else, so {@code "contraindications": [null]} in an operator's file would reach
	 * this report — the first thing at load time to dereference either element — if the loader did not
	 * drop it first.
	 *
	 * <p>What keeps it from throwing is not a skip here: it is
	 * {@link DrugReferenceValidity#NULL_LIST_ELEMENT}, which drops the null at the load boundary so no
	 * consumer of the loaded model sees one — the whole reason it belongs there being that this report is
	 * not the only consumer that dereferences an element, and the others fail behind
	 * {@code DrugSafetyValidator.validate}'s catch. That rule's own case
	 * ({@code DrugReferenceValidityContextTest.aNullElementInAnEntrysOwnListIsDroppedSoNoConsumerCanThrowOnIt})
	 * asserts the drop, the finding and the surviving safety pass; this asserts what the ARMS then report
	 * over such a file, and it is what fails — with a thrown NPE inside {@code ensureLoaded}, which has
	 * no catch — if the drop is removed.
	 */
	@Test
	public void aNullRuleOrBandDoesNotBringTheLoadDown() throws IOException {
		String path = copyToAppData("/chartsearchai-test/drug-reference-null-rule-and-band.json",
				"null-rule-and-band.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON, path);

		DrugReferenceService service = new DrugReferenceService();
		DrugReferenceLoad status = service.getLoadStatus();

		assertEquals(1, status.getEntryCount(), "the entry loads; the nulls inside it are not fatal");
		DrugReference entry = service.getAll().get(0);
		assertTrue(entry.getContraindications().isEmpty(),
				"the null rule is dropped at the load boundary, not carried into the model and skipped "
						+ "by each reader of it");
		assertTrue(entry.getAgeBands().isEmpty(), "and so is the null band");
		assertEquals(DrugReferenceLoad.Coverage.ABSENT,
				status.coverageOf(DrugReferenceLoad.Arm.HAND_AUTHORED_RULES),
				"so the arm has no rule it can ask");
		assertEquals(DrugReferenceLoad.Coverage.ABSENT,
				status.coverageOf(DrugReferenceLoad.Arm.DOSE_CEILINGS),
				"and no band publishing a ceiling");
	}

	/**
	 * The map the {@code GET /chartsearchai/drugreferencestatus} endpoint serializes. Pinned here
	 * because it is what an operator (or a source-flip check) actually reads, and because a
	 * disappearing key would leave that check silently reading {@code null}.
	 *
	 * <p>{@code findings} joined the set for the load-time validity check (issues #150/#156/#196/#211):
	 * the endpoint exists to answer "what is actually loaded?" after a lazy load, and a load that dropped
	 * an alias, appended a display name or fell back to the bundled file is exactly that question.
	 *
	 * <p>{@code arms} joined it afterwards, for the same reasons (issue #285): it answers the endpoint's
	 * own question one level down — {@code inert} says whether the dataset yielded anything, {@code arms}
	 * says which safety arms what it yielded can actually serve.
	 *
	 * <p><b>A new key is APPENDED, never inserted</b> — {@code findings} was last until {@code arms}
	 * went after it, and the rule is the append rather than which key happens to be last today. The
	 * assertion below is a {@code Set} and so cannot see order at all; the ordered assertion the append
	 * protects is {@code ChartSearchAiDrugReferenceStatusTest.DOCUMENTED_FIELDS}, compared as a
	 * {@code List}. Insert a key in the middle and this case stays green while that one reddens.
	 */
	@Test
	public void loadStatusSerializesTheFieldsTheStatusEndpointReturns() throws IOException {
		String path = copyToAppData(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "wire-ddi.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, path);

		DrugReferenceService service = new DrugReferenceService();
		Map<String, Object> map = service.getLoadStatus().toMap();

		assertEquals(new LinkedHashSet<String>(Arrays.asList("loaded", "inert", "entryCount",
				"sourceFormat", "configuredSourceFormat", "configuredDataFilePath", "origin",
				"findings", "arms")),
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
		String path = copyToAppData(DdiDrugReferenceSource.CLASSPATH_DEFAULT, "stable-ddi.json");
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER, path);

		DrugReferenceService service = new DrugReferenceService();
		DrugReferenceLoad first = service.getLoadStatus();

		// Flip both GPs to a mismatched pair AFTER the load, exactly as an operator or a
		// verification pass would.
		configure(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON, path);

		DrugReferenceLoad second = service.getLoadStatus();
		assertEquals(first.getSourceFormat(), second.getSourceFormat(),
				"the status reports the load in force, not the GPs as they read now");
		assertEquals(first.getEntryCount(), second.getEntryCount());
		assertEquals(service.getAll().size(), second.getEntryCount(),
				"and it still agrees with the entries the safety layer is using");
	}
}
