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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
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

	/** Writes a groups document the test authors into the application data directory — the arrangement
	 *  {@link CrossReactivityGroupsContextTest} already uses for the operator-file branch. */
	private String writeToAppData(String asName, String content) throws IOException {
		File dir = new File(OpenmrsUtil.getApplicationDataDirectory(), "chartsearchai");
		dir.mkdirs();
		File target = new File(dir, asName);
		created.add(target);
		Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
		return "chartsearchai/" + asName;
	}

	private static List<String> rulesOf(CrossReactivityGroupsLoad status) {
		List<String> rules = new ArrayList<String>();
		for (Finding found : status.getFindings()) {
			rules.add(found.getRule());
		}
		return rules;
	}

	private static Finding finding(CrossReactivityGroupsLoad status, String rule) {
		for (Finding candidate : status.getFindings()) {
			if (rule.equals(candidate.getRule())) {
				return candidate;
			}
		}
		throw new AssertionError("expected a " + rule + " finding, had: " + status.getFindings());
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
		assertTrue(finding(status, DrugReferenceValidity.CONFIGURED_DATA_FILE_NOT_READ).getDetail()
				.contains("h266-no-such-groups.json"),
				"the detail names the file the operator has to look at");
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
				"an install that configured nothing must be silent. Findings were: "
						+ status.getFindings());
		assertEquals(ReferenceDataFiles.CLASSPATH_ORIGIN_PREFIX
				+ CrossReactivityGroupsLoader.CLASSPATH_DEFAULT, status.getOrigin());
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
