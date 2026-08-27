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

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.reference.DrugReferenceValidity.Finding;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

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
	 * A dataset the test AUTHORS, for the one arrangement no classpath fixture can supply: a document the
	 * ATC parser reads and finds no content line in. It is not a classpath fixture deliberately —
	 * {@code DrugReferenceValidityContextTest}'s corpus sweep requires every {@code .tsv} in the ATC
	 * fixture directory to parse to at least one entry, which is the guard that stops a mis-shaped
	 * fixture quietly disarming the tests written against it, and a deliberately empty one placed there
	 * would either redden that sweep or have to be exempted from it.
	 */
	private String writeToAppData(String asName, String content) throws IOException {
		return DrugReferenceTestSupport.writeDatasetToAppData(asName, content, created);
	}

	private static List<String> rulesOf(DrugReferenceLoad status) {
		return DrugReferenceTestSupport.rulesOf(status.getFindings());
	}

	private static Finding finding(DrugReferenceLoad status, String rule) {
		return DrugReferenceTestSupport.finding(status.getFindings(), rule);
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
	 * The log line the shared resolution writes for a format that bundles NOTHING says so. The two entry
	 * points hand {@code readOperatorFile} their own tail for that sentence, and swapping the two
	 * literals left the whole suite green — so this format could have told an operator the module was
	 * "using bundled default" for a dataset it does not ship, which is the opposite of the diagnosis and
	 * would send them looking for a file that does not exist.
	 */
	@Test
	public void theLogForAFormatThatBundlesNothingDoesNotOfferABundledDefault() {
		configure("chartsearchai/h266-no-such-atc-export.tsv");

		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

			assertEquals(0, status.getEntryCount(), "precondition: nothing loaded");
			String logged = capture.describeAll().toString();
			assertTrue(logged.contains("running empty"),
					"the atc format has no fallback, so the line says what actually follows. Captured: "
							+ logged);
			assertFalse(logged.contains("using bundled default"),
					"and never offers one it does not ship. Captured: " + logged);
		}
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

		assertTrue(found.getDetail().contains("no dataset is in force at all"),
				"nothing was read in place of the named file, so there is no plausible count to warn "
						+ "about. Detail was: " + found.getDetail());
		assertFalse(found.getDetail().contains("Whatever count you see"),
				"and not the fallback branch's wording, which describes a dataset taken in its place. "
						+ "Asserted on that branch's own distinguishing phrase rather than on a word both "
						+ "branches could share, so removing the branch reddens this. Detail was: "
						+ found.getDetail());
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
		Finding discarded = finding(status, DrugReferenceValidity.NO_LINE_YIELDED_AN_ENTRY);
		assertEquals(DrugReferenceValidity.Remedy.REPORTED, discarded.getRemedy(),
				"nothing can be repaired here: a line this parser cannot read is not a line it can "
						+ "guess at, and no rule in this loader refuses a file");
		assertEquals(1, discarded.getOccurrences(),
				"one document, one verdict — the line count belongs in the detail, which is the shape "
						+ "the table rule's rowsCarried already takes. Mutating this to 7 survived the "
						+ "whole suite before this line");
	}

	/**
	 * What the number in that detail MEANS, which the rule's guard does not pin. The parser counts
	 * CONTENT lines — non-blank, non-comment — and it counts them before deciding whether a line splits
	 * into a code and a name, because a line it cannot split is still a line it read and discarded.
	 * Moving the increment below that split guard survived the whole suite, so the figure an operator
	 * reads could silently have become a count of SPLITTABLE lines instead.
	 *
	 * <p>Three content lines, one of them a bare token with no name, none of them an ATC substance code:
	 * the detail must say three.
	 */
	@Test
	public void theDiscardedCountIsOfContentLinesAndNotOfSplittableOnes() throws IOException {
		String path = writeToAppData("h266-atc-unsplittable.tsv",
				"# a comment, which is not a content line\n\nNOTACODE a name\nBARETOKEN\nZZZZ another\n");
		configure(path);

		DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

		assertEquals(0, status.getEntryCount(), "precondition: no line is an ATC substance code");
		String detail = finding(status, DrugReferenceValidity.NO_LINE_YIELDED_AN_ENTRY).getDetail();
		assertTrue(detail.contains("3 content line(s)"),
				"the comment and the blank line are not content; the bare token IS, because the parser "
						+ "read it and discarded it. Detail was: " + detail);
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
	 * The untouched install, on the newly-reachable path — and the one case that would turn this channel
	 * into noise if it were got wrong. {@code dataFilePath} defaults to a file the module never creates,
	 * so an install that selects {@code atc} and configures no path of its own falls straight through the
	 * resolution; a rule keyed on "a path is configured and nothing was read" would then fire on every
	 * such install and be filtered within a week, which is what {@code CLAUDE.md}'s loader bullet is
	 * written against. Both spellings of "untouched" are silent — this one, the DECLARED DEFAULT, and the
	 * blank that a context without that row reads, which
	 * {@code DrugReferenceLoadContextTest.atcFormatWithNoConfiguredPathIsInertAndNamesNoOrigin} covers.
	 *
	 * <p>Asserted as the absence of that ONE rule rather than of every finding, deliberately: should a
	 * file happen to sit at the default path, it would be READ, and whatever the ATC parser then made of
	 * it is a different rule's business. The claim here is only that a default nobody chose is never
	 * reported as a choice.
	 */
	@Test
	public void anUntouchedDataFilePathIsNotReportedAsAFileTheOperatorNamed() {
		configure(ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_DATA_FILE_PATH);

		DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();

		assertFalse(rulesOf(status).contains(DrugReferenceValidity.CONFIGURED_DATA_FILE_NOT_READ),
				"the global property's own declared default is not a file anybody chose, so routing atc "
						+ "through the shared resolution must not start reporting it as one. Findings "
						+ "were: " + status.getFindings());
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
