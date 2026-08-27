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
 * Issue #266, first half: {@code sourceFormat=atc} had no validity channel at all, so
 * {@code findings} on {@code GET /chartsearchai/drugreferencestatus} was empty for that format
 * whatever its file did.
 *
 * <p>Two structural causes, and neither is a rule that failed to fire — there was nothing for a rule
 * to fire INTO. {@link AtcDrugReferenceSource} resolved its own file rather than through
 * {@link ReferenceDataFiles}, so no {@link DrugReferenceValidity} collector existed; and it inherited
 * {@link DrugReferenceSource#lastLoadFindings()}'s empty default, so nothing collected could have
 * reached {@link DrugReferenceService#getLoadStatus()}. That is the strongest form of the rule
 * {@code CLAUDE.md} states — silence is the ABSENCE of a finding, never a muted one, and every
 * finding reaches both the log and {@code toMap()}.
 *
 * <p>Consequence the ticket names first: issue #156's <em>"the file you configured was not read"</em>
 * was unreachable on the ONE format with no bundled fallback — the format most dependent on the
 * operator's own file was the one whose file-not-read condition was invisible.
 *
 * <p>Every case here drives the real load — {@link DrugReferenceService#getLoadStatus()} over the
 * real global properties, with a real file in the application data directory — so what is asserted is
 * what an operator polling the endpoint would read, not what a parser returns to a test.
 */
public class AtcLoadValidityChannelTest extends BaseModuleContextSensitiveTest {

	private final List<File> created = new ArrayList<File>();

	@AfterEach
	public void deleteCreatedDatasets() {
		for (File file : created) {
			file.delete();
		}
		created.clear();
	}

	private void configure(String dataFilePath) {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT,
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_ATC);
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, dataFilePath);
	}

	private String copyToAppData(String classpathResource, String asName) throws IOException {
		return DrugReferenceTestSupport.copyDatasetToAppData(classpathResource, asName, created);
	}

	/**
	 * Writes a dataset the test AUTHORS into the application data directory, for the one arrangement no
	 * classpath fixture can supply: a document the ATC parser reads and finds no content line in. It is
	 * not a classpath fixture deliberately — {@code DrugReferenceValidityContextTest}'s corpus sweep
	 * requires every {@code .tsv} in the ATC fixture directory to parse to at least one entry, which is
	 * the guard that stops a mis-shaped fixture quietly disarming the tests written against it, and a
	 * deliberately empty one placed there would either redden that sweep or have to be exempted from it.
	 * The same arrangement {@code CrossReactivityGroupsContextTest} uses for its operator file.
	 */
	private String writeToAppData(String asName, String content) throws IOException {
		File dir = new File(OpenmrsUtil.getApplicationDataDirectory(), "chartsearchai");
		dir.mkdirs();
		File target = new File(dir, asName);
		created.add(target);
		Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
		return "chartsearchai/" + asName;
	}

	private static List<String> rulesOf(DrugReferenceLoad status) {
		List<String> rules = new ArrayList<String>();
		for (Finding found : status.getFindings()) {
			rules.add(found.getRule());
		}
		return rules;
	}

	private static Finding finding(DrugReferenceLoad status, String rule) {
		for (Finding candidate : status.getFindings()) {
			if (rule.equals(candidate.getRule())) {
				return candidate;
			}
		}
		throw new AssertionError("expected a " + rule + " finding, had: " + status.getFindings());
	}

	/**
	 * Issue #156's rule, on the format it could never reach. The operator named a file, it could not be
	 * read, and — because this format has no bundled fallback — nothing was read in its place.
	 */
	@Test
	public void theAtcFileAnOperatorNamedAndCouldNotBeReadIsReportedOnTheStatus() {
		configure("chartsearchai/h266-no-such-atc-export.tsv");

		DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

		assertTrue(rulesOf(status).contains(DrugReferenceValidity.CONFIGURED_DATA_FILE_NOT_READ),
				"the atc format is the one most dependent on the operator's own file, so the file it "
						+ "could not read has to reach the endpoint. Findings were: "
						+ status.getFindings());
		assertEquals(ReferenceDataFiles.ORIGIN_NONE, status.getOrigin(),
				"precondition: nothing was read in place of the operator's file");
	}

	/**
	 * The same finding, read for what it SAYS. Issue #156's detail was written for the two formats that
	 * have a bundled fallback and ends <em>"The entry count is therefore a count of a dataset nobody
	 * configured, and looks healthy"</em>. On a format with no fallback that clause is false: the count
	 * is zero and the load is inert. Making a rule reachable from a new path includes making its detail
	 * true there — a finding that misdescribes what happened is the channel carrying a wrong answer
	 * rather than no answer.
	 */
	@Test
	public void thatFindingDoesNotClaimAPlausibleCountWhereNothingWasReadInstead() {
		configure("chartsearchai/h266-no-such-atc-export.tsv");

		DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();
		Finding found = finding(status, DrugReferenceValidity.CONFIGURED_DATA_FILE_NOT_READ);

		assertFalse(found.getDetail().contains("looks healthy"),
				"nothing was read in place of the named file, so there is no plausible count to warn "
						+ "about. Detail was: " + found.getDetail());
		assertTrue(found.getDetail().contains("h266-no-such-atc-export.tsv"),
				"the detail names the file the operator has to look at. Detail was: "
						+ found.getDetail());
		assertEquals(0, status.getEntryCount(), "precondition: nothing loaded");
	}

	/**
	 * Issue #242's shape one parser over, and the rule this change adds. A document of another format is
	 * read line by line, every line fails the level-5 ATC test, and the parser emits nothing — so before
	 * this rule the only evidence was an entry count of zero, which cannot tell a discarded document
	 * from an empty file.
	 *
	 * <p>Driven with a real DDInter export (the module's own 16-drug sample), which is the mismatch an
	 * operator actually makes, rather than a file authored to pose the shape.
	 */
	@Test
	public void anAtcLoadOfADocumentOfAnotherFormatSaysItsContentWasDiscarded() throws IOException {
		String path = copyToAppData(DrugReferenceTestSupport.DDI_EXCERPT, "h266-not-an-atc-export.json");
		configure(path);

		DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

		assertTrue(rulesOf(status).contains(DrugReferenceValidity.NO_LINE_YIELDED_AN_ENTRY),
				"the parser read a document and discarded all of it, which an entry count of 0 cannot "
						+ "say. Findings were: " + status.getFindings());
		assertEquals(0, status.getEntryCount());
		assertTrue(status.isInert(), "precondition: this is the state the diagnosis is about");
		assertEquals(DrugReferenceValidity.Remedy.REPORTED,
				finding(status, DrugReferenceValidity.NO_LINE_YIELDED_AN_ENTRY).getRemedy(),
				"nothing can be repaired here: a line this parser cannot read is not a line it can "
						+ "guess at, and no rule in this loader refuses a file");
	}

	/**
	 * The distinction that makes the rule mean anything, and the case a naive implementation fails: an
	 * EMPTY document reports nothing. A rule keyed on "the load produced no entries" would say only what
	 * {@link DrugReferenceLoad#isInert()}'s WARN already says, and would say it about a file whose
	 * content was never discarded because there was none.
	 */
	@Test
	public void anEmptyAtcDocumentIsNotReportedAsADiscardedOne() throws IOException {
		String path = writeToAppData("h266-empty-atc-export.tsv",
				"# an ATC export carrying no lines at all\n\n");
		configure(path);

		DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

		assertFalse(rulesOf(status).contains(DrugReferenceValidity.NO_LINE_YIELDED_AN_ENTRY),
				"an empty document discarded nothing, and saying otherwise would make this rule a "
						+ "second spelling of the inert warning. Findings were: " + status.getFindings());
		assertEquals(0, status.getEntryCount(), "precondition: an empty document still loads nothing");
		assertTrue(status.isInert(), "and is still loud, through the inert verdict");
	}

	/**
	 * A healthy operator-named ATC export raises nothing.
	 *
	 * <p>What this alone pins is not that the new rule can be quiet — the empty-document case above
	 * already reddens on a rule that always fires. It is that routing {@code atc} through the shared
	 * resolution did not make issue #156's newly-reachable rule fire on a file that WAS read, which is
	 * the regression a new call site of that rule can introduce.
	 */
	@Test
	public void aHealthyAtcExportRaisesNoFinding() throws IOException {
		String path = copyToAppData(DrugReferenceTestSupport.ATC_SAMPLE, "h266-atc-sample.tsv");
		configure(path);

		DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

		assertTrue(status.getEntryCount() > 0, "precondition: the WHO ATC sample parses to entries");
		assertEquals("[]", rulesOf(status).toString(),
				"a healthy load is silent, and in particular does not report the file it just read as "
						+ "one it could not read. Findings were: " + status.getFindings());
		assertEquals(ReferenceDataFiles.APPDATA_ORIGIN_PREFIX + path, status.getOrigin(),
				"and the origin still names the operator file in the relative form");
	}
}
