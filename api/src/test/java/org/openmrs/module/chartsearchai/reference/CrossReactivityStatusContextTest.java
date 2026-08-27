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
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.reference.DrugReferenceValidity.Finding;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsUtil;

/**
 * Issue #266, second half: the curated cross-reactivity groups load raised findings that reached
 * ONLY the log.
 *
 * <p>{@link CrossReactivityGroupsLoader} already went through {@link ReferenceDataFiles}, so issue
 * #156's {@code configured-data-file-not-read} really fired for that file — and then went into
 * {@code logTo} and stopped there, because nothing retained it. Its own javadoc called that "a gap
 * rather than a design". {@code CLAUDE.md}'s rule is that every finding reaches BOTH channels: the
 * WARN at the moment it happens and {@code toMap()} afterwards, which is the only one that can answer
 * after a lazy load (issue #154). A groups document declaring no {@code groups} table was worse still —
 * it parsed empty with no rule firing at all, which is issue #242's shape on a third dataset.
 *
 * <p>Every case drives the real load through
 * {@link DrugReferenceService#getCrossReactivityLoadStatus()} over the real global property, with a
 * real file in the application data directory.
 *
 * <p><b>A section of its own, not a row in the entry load's {@code findings}.</b> The two datasets have
 * independent lazy loads, independent global properties and independent origins, so one flat list could
 * not tell an operator which file a finding describes — see ADR Decision 48.
 */
public class CrossReactivityStatusContextTest extends BaseModuleContextSensitiveTest {

	private final List<File> created = new ArrayList<File>();

	@AfterEach
	public void deleteCreatedDatasets() {
		for (File file : created) {
			file.delete();
		}
		created.clear();
	}

	private void enable() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	private void configureGroupsFile(String path) {
		enable();
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_CROSS_REACTIVITY_FILE_PATH, path);
	}

	/** A groups document the test authors, for an arrangement no committed fixture supplies. */
	private String writeToAppData(String asName, String content) throws IOException {
		return DrugReferenceTestSupport.writeDatasetToAppData(asName, content, created);
	}

	private static List<String> rulesOf(CrossReactivityGroupsLoad status) {
		return DrugReferenceTestSupport.rulesOf(status.getFindings());
	}

	private static Finding finding(CrossReactivityGroupsLoad status, String rule) {
		return DrugReferenceTestSupport.finding(status.getFindings(), rule);
	}

	/**
	 * Issue #156's rule for the groups file, on the channel that can answer after a lazy load. It fired
	 * before this change and reached the log alone, so an operator who could not be expected to be
	 * watching the log at module-start had no way to learn that the family knowledge in force was not
	 * the file they named.
	 */
	@Test
	public void theGroupsFileAnOperatorNamedAndCouldNotBeReadIsReportedOnTheStatus() {
		configureGroupsFile("chartsearchai/h266-no-such-groups.json");

		CrossReactivityGroupsLoad status = new DrugReferenceService().getCrossReactivityLoadStatus();

		assertTrue(rulesOf(status).contains(DrugReferenceValidity.CONFIGURED_DATA_FILE_NOT_READ),
				"the operator named a groups file and the bundled seed is in force instead; that has to "
						+ "reach the endpoint, not only the log. Findings were: " + status.getFindings());
		assertTrue(status.getOrigin().startsWith(ReferenceDataFiles.CLASSPATH_ORIGIN_PREFIX),
				"precondition: the bundled groups were read in its place. Origin was: "
						+ status.getOrigin());
		assertTrue(status.getGroupCount() > 0,
				"and the count is a plausible one, which is exactly why the finding is needed");
		Finding notRead = finding(status, DrugReferenceValidity.CONFIGURED_DATA_FILE_NOT_READ);
		assertTrue(notRead.getDetail().contains("h266-no-such-groups.json"),
				"the detail names the file the operator has to look at");
		assertFalse(notRead.getDetail().contains("entry count"),
				"and names the count NEUTRALLY: this section reports a groupCount and carries no "
						+ "entryCount at all, so telling the operator to read an 'entry count' here "
						+ "points at a field that does not exist — the same defect the sibling rule's "
						+ "item noun exists to prevent, in the rule #266 makes readable first. Detail "
						+ "was: " + notRead.getDetail());

		// The path the operator NAMED, reported beside what was read. Asserted on THIS branch and not
		// only on the operator-file one because this is the branch the field exists for: the whole
		// diagnosis config.xml tells them to perform is that these two disagree, and a blank
		// configuredFilePath beside a classpath origin reads as an install that configured nothing —
		// the wrong conclusion, drawn quietly. Mutation-checked: it survived the whole suite before
		// this line.
		assertEquals("chartsearchai/h266-no-such-groups.json", status.getConfiguredFilePath(),
				"the file the operator named is reported as configured even though it was not read");

		// And the WIRE, not only the retained object. Every api case here reads getFindings(); the one
		// case that reads this section's toMap() drives the DISABLED state, where every value is empty,
		// so a toMap() that always returned the disabled shape passed. Measured: replacing the findings
		// serialization with an empty list left the whole suite green. README promises a caller "the
		// same remedy/occurrences/detail shape" as the entries section, and this is what checks it.
		String serialized = String.valueOf(status.toMap().get("findings"));
		assertTrue(serialized.contains(DrugReferenceValidity.CONFIGURED_DATA_FILE_NOT_READ)
				&& serialized.contains("h266-no-such-groups.json"),
				"the finding has to reach the endpoint's own crossReactivity.findings, carrying its "
						+ "rule and its detail. Serialized: " + serialized);
		assertTrue(serialized.contains(
				DrugReferenceValidity.Remedy.REPORTED.name().toLowerCase(java.util.Locale.ROOT)),
				"with its remedy in the wire spelling. Serialized: " + serialized);
	}

	/**
	 * Issue #242's shape on the groups dataset: a document declaring no {@code groups} table parses to
	 * nothing, and before this change reported nothing — the groups count of zero was the only evidence,
	 * and unlike the entry dataset there is no inert verdict to make even that loud.
	 *
	 * <p>The SAME rule as the entry datasets use, not a second one meaning the same thing. What differs
	 * is the detail's vocabulary: this document would have produced groups, not entries.
	 */
	@Test
	public void aGroupsDocumentMissingItsGroupsTableIsReportedRatherThanParsingEmptySilently()
			throws IOException {
		String path = writeToAppData("h266-groups-no-table.json", "{\"families\":[]}");
		configureGroupsFile(path);

		CrossReactivityGroupsLoad status = new DrugReferenceService().getCrossReactivityLoadStatus();

		assertEquals(0, status.getGroupCount(), "precondition: the document parsed to nothing");
		Finding found = finding(status, DrugReferenceValidity.DATASET_MISSING_A_REQUIRED_TABLE);
		assertTrue(found.getDetail().contains("[groups]"),
				"named for the table THIS reader requires. Detail was: " + found.getDetail());
		assertTrue(found.getDetail().contains("no groups at all"),
				"and for what the document would have produced — a groups file that produced nothing "
						+ "produced no GROUPS, and saying 'entries' would name the wrong dataset's "
						+ "vocabulary in a finding an operator acts on. Detail was: " + found.getDetail());
	}

	/**
	 * The control for the case above, and the boundary the rule is deliberately drawn at: a document
	 * that DECLARES an empty table has said what it has. That is a deployment choosing to carry no
	 * cross-reactivity families, not a file whose content was discarded — the same distinction
	 * {@code DdiDrugReferenceSource}'s {@code "interactions": []} case is drawn at.
	 */
	@Test
	public void aGroupsDocumentDeclaringAnEmptyTableIsNotReported() throws IOException {
		String path = writeToAppData("h266-groups-empty-table.json", "{\"groups\":[]}");
		configureGroupsFile(path);

		CrossReactivityGroupsLoad status = new DrugReferenceService().getCrossReactivityLoadStatus();

		assertEquals(0, status.getGroupCount(), "precondition: an empty table still loads no groups");
		assertFalse(rulesOf(status).contains(DrugReferenceValidity.DATASET_MISSING_A_REQUIRED_TABLE),
				"a declared-empty table is not a missing one. Findings were: " + status.getFindings());
		assertEquals(ReferenceDataFiles.APPDATA_ORIGIN_PREFIX + path, status.getOrigin(),
				"and the operator's file is what was read");
	}

	/**
	 * The untouched install: the bundled seed, nothing configured, no finding. Without this the two
	 * cases above pass on a rule that always fires — and the groups file's declared default names a file
	 * the module never creates, so a rule keyed on "a path is configured and we fell back" would fire on
	 * every install, which is the noise {@code CLAUDE.md}'s loader bullet is written against.
	 */
	@Test
	public void theBundledGroupsFileRaisesNoFinding() {
		enable();

		CrossReactivityGroupsLoad status = new DrugReferenceService().getCrossReactivityLoadStatus();

		assertTrue(status.isLoaded(), "precondition: the groups load happened");
		assertTrue(status.getGroupCount() > 0, "precondition: the bundled seed carries groups");
		assertEquals("[]", rulesOf(status).toString(),
				"an install that configured nothing must be silent. The load was: " + status);
		assertEquals(ReferenceDataFiles.CLASSPATH_ORIGIN_PREFIX
				+ CrossReactivityGroupsLoader.CLASSPATH_DEFAULT, status.getOrigin());
	}

	/**
	 * An untouched install says NOTHING about the file it never had.
	 *
	 * <p>The regression this pins was introduced while hardening this very change and caught by a fresh
	 * reviewer: extracting the shared operator-file read attempt dropped the blank-path skip at one of
	 * its two callers, so a deployment that had configured neither path logged
	 * {@code file '' not available (Model path is not configured: …)} once per dataset. INFO, and
	 * therefore invisible on a stock install — but README and ONBOARDING both instruct an operator to
	 * raise this logger to INFO in order to read the {@code Loaded N …} lines, so it is a line on every
	 * install of every deployment about a dataset nobody configured. That is the noise this module's
	 * whole validity design is written against, arriving through the log rather than through a finding.
	 *
	 * <p>Asserted on the message rather than on the LEVEL, deliberately: the healthy load legitimately
	 * logs at INFO ({@code Loaded 1 cross-reactivity groups from …}), so "no INFO" would be false and
	 * "no WARN" would have been green throughout the regression.
	 */
	@Test
	public void anInstallThatConfiguredNoGroupsFileLogsNothingAboutOne() {
		enable();

		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			CrossReactivityGroupsLoad status = new DrugReferenceService().getCrossReactivityLoadStatus();

			assertTrue(status.getGroupCount() > 0, "precondition: the bundled seed loaded");
			assertEquals("", status.getConfiguredFilePath(),
					"precondition: nothing was configured, which is what an untouched install reads");
			// toString() first, and it is load-bearing: describeAll() returns a LIST, so
			// .contains("not available") on it is List.contains — an exact-element match against a
			// whole formatted line, which is never true. Written that way, this assertion passed while
			// the offending line was being captured; the mutation is what said so.
			assertFalse(capture.describeAll().toString().contains("not available"),
					"a path nobody set is not a file that could not be read, so nothing is reported "
							+ "about it at any level. Captured: " + capture.describeAll());
		}
	}

	/**
	 * The groups status names the operator's file in the same relative form the entry load's origin
	 * uses — the endpoint is served to any caller holding core's {@code Get Global Properties}
	 * privilege, which the {@code Authenticated} role holds by default, so no absolute server path may
	 * appear in it.
	 */
	@Test
	public void theStatusNamesTheGroupsFileItReadWithoutDisclosingItsAbsolutePath() throws IOException {
		String path = writeToAppData("h266-groups-operator.json",
				"{\"groups\":[{\"name\":\"H266FAM\",\"atcPrefixes\":[\"J01CA\"]}]}");
		configureGroupsFile(path);

		CrossReactivityGroupsLoad status = new DrugReferenceService().getCrossReactivityLoadStatus();

		assertEquals(ReferenceDataFiles.APPDATA_ORIGIN_PREFIX + path, status.getOrigin());
		assertEquals(path, status.getConfiguredFilePath(),
				"the configured value is reported beside what was read; a finding saying the named file "
						+ "was not read is only actionable next to both");
		assertFalse(status.getOrigin().contains(OpenmrsUtil.getApplicationDataDirectory()),
				"the absolute application data directory must not be disclosed. Origin was: "
						+ status.getOrigin());
		assertEquals(1, status.getGroupCount());
	}

	/**
	 * F1's parallel on the groups side: the status describes the load that is IN FORCE, and that load is
	 * cached for the life of the bean. Editing the global property afterwards must NOT make the status
	 * describe a file nothing read — which is the whole reason a status exists rather than a live read
	 * (issues #149 and #154), and the property this publishes that nothing pinned before it was
	 * published. The entry dataset's own version of this is
	 * {@code DrugReferenceLoadContextTest.loadStatusDoesNotDriftFromTheCachedEntriesWhenTheGlobalPropertiesChange}.
	 */
	@Test
	public void theGroupsStatusDoesNotDriftFromTheCachedGroupsWhenTheGlobalPropertyChanges()
			throws IOException {
		String first = writeToAppData("h266-groups-first.json",
				"{\"groups\":[{\"name\":\"H266FIRST\",\"atcPrefixes\":[\"J01CA\"]}]}");
		configureGroupsFile(first);

		DrugReferenceService service = new DrugReferenceService();
		CrossReactivityGroupsLoad before = service.getCrossReactivityLoadStatus();
		assertEquals(ReferenceDataFiles.APPDATA_ORIGIN_PREFIX + first, before.getOrigin(),
				"precondition: the first file is what the load read");

		String second = writeToAppData("h266-groups-second.json",
				"{\"groups\":[{\"name\":\"H266SECOND\",\"atcPrefixes\":[\"J01CA\"]},"
						+ "{\"name\":\"H266THIRD\",\"atcPrefixes\":[\"J01GB\"]}]}");
		configureGroupsFile(second);

		CrossReactivityGroupsLoad after = service.getCrossReactivityLoadStatus();

		assertEquals(before.getOrigin(), after.getOrigin(),
				"the load is cached for the bean's life, so the status must keep describing the file it "
						+ "actually read. Reporting the newly-configured one would be the drift issue "
						+ "#149 records being fooled by");
		assertEquals(before.getConfiguredFilePath(), after.getConfiguredFilePath(),
				"and the configured path it reports is the one that produced this load, not a later "
						+ "edit — the two are compared against each other, so a live read of one beside "
						+ "a cached read of the other is the one pairing that cannot be diagnosed");
		assertEquals(1, after.getGroupCount(),
				"the groups in force are still the first file's, which is what makes the status honest");
	}

	/**
	 * F2's parallel: the groups and the outcome describing them are published as ONE reference (issue
	 * #158), so a reader that can see the groups cannot see a status saying nothing was loaded. Like the
	 * entry dataset's {@code DrugReferenceLoadConcurrencyTest} case, this pins the CONSTRUCTION error
	 * rather than the race — no test can demonstrate the absence of a race one volatile write wide, and
	 * pairing a populated cache with the wrong outcome is deterministic. Verified by mutation: publishing
	 * the holder with {@link CrossReactivityGroupsLoad#notLoaded()} reddens this.
	 */
	@Test
	public void groupsAreNeverPublishedWithAStatusThatSaysNothingWasLoaded() {
		enable();
		DrugReferenceService service = new DrugReferenceService();

		List<CrossReactivityGroup> loaded = service.getCrossReactivityGroups();
		CrossReactivityGroupsLoad status = service.getCrossReactivityLoadStatus();

		assertFalse(loaded.isEmpty(), "precondition: the bundled seed carries groups");
		assertTrue(status.isLoaded(),
				"a reader that can see the groups must see a status describing them, not one saying "
						+ "nothing was loaded — that state is what one holder exists to rule out");
		assertEquals(loaded.size(), status.getGroupCount(),
				"and it must describe THOSE groups, not a different load's");
	}

	/**
	 * The feature being switched off is a legitimate state. Polling a status endpoint must not be what
	 * triggers the groups parse on an install that does not use the feature — the same rule
	 * {@link DrugReferenceService#getLoadStatus()} follows for the entry dataset.
	 */
	@Test
	public void aDisabledFeatureReportsNoGroupsLoadAndTriggersNone() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "false");

		DrugReferenceService service = new DrugReferenceService();
		CrossReactivityGroupsLoad status = service.getCrossReactivityLoadStatus();

		assertFalse(status.isLoaded(), "a disabled feature must report no load");
		assertEquals(0, status.getGroupCount());
		assertEquals("[]", rulesOf(status).toString());
		assertEquals(ReferenceDataFiles.ORIGIN_NONE, status.getOrigin(),
				"nothing was read, and the origin says so");
	}
}
