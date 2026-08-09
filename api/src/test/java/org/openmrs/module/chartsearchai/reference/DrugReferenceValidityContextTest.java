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
import java.util.List;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsUtil;

/**
 * The load-time validity check: what the loader does when the dataset it was pointed at violates an
 * assumption the loader's own code makes (issues #150, #156, #211, and the check decided on #196).
 *
 * <p>Every case here drives the REAL load — a file in the application data directory, the real global
 * properties, {@link DrugReferenceService#getAll()} / {@link DrugReferenceService#getLoadStatus()} — for
 * the reason CLAUDE.md gives and for one specific to this feature: the validity check runs where the
 * dataset is loaded, and the {@code setEntries} test seam deliberately bypasses all dataset loading, so
 * a case built through that seam would assert nothing about it.
 *
 * <p>Each case asserts THREE things, and needs all three: what the loader did to the data (or declined
 * to do), that it said so at WARN, and that {@link DrugReferenceLoad#getFindings()} names the rule after
 * the lazy load — which is the only way to ask "what was wrong with what I loaded?" without reading a log
 * line that, the load being lazy, may belong to a previous load (issue #149).
 */
public class DrugReferenceValidityContextTest extends BaseModuleContextSensitiveTest {

	private static final String BLANK_ALIAS_FIXTURE = "chartsearchai-test/drug-reference-blank-alias.json";

	private static final String NAME_NOT_ITS_OWN_ALIAS_FIXTURE =
			"chartsearchai-test/drug-reference-name-not-its-own-alias.json";

	private static final String SUBSTANCE_DECLARED_FIXTURE =
			"chartsearchai-test/drug-reference-substance-name-declared.json";

	private static final String ALIAS_NAMES_ANOTHER_SUBSTANCE_FIXTURE =
			"chartsearchai-test/ddi-alias-names-another-substance.json";

	private final List<File> created = new ArrayList<File>();

	@AfterEach
	public void deleteCopiedDatasets() {
		for (File file : created) {
			file.delete();
		}
		created.clear();
	}

	/**
	 * Copies a fixture into {@code <appdata>/chartsearchai/} and points {@code dataFilePath} at it, with
	 * the feature on — the operator-authored-file path, which is the only path any of these rules can be
	 * reached through.
	 *
	 * @return the service to read, unloaded
	 */
	private DrugReferenceService loading(String classpathFixture, String asName, String sourceFormat)
			throws IOException {
		File dir = new File(OpenmrsUtil.getApplicationDataDirectory(), "chartsearchai");
		dir.mkdirs();
		File target = new File(dir, asName);
		created.add(target);
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpathFixture)) {
			assertNotNull(in, classpathFixture + " should be on the test classpath");
			Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT, sourceFormat);
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, "chartsearchai/" + asName);
		return new DrugReferenceService();
	}

	private static DrugReferenceValidity.Finding finding(DrugReferenceLoad status, String rule) {
		for (DrugReferenceValidity.Finding candidate : status.getFindings()) {
			if (rule.equals(candidate.getRule())) {
				return candidate;
			}
		}
		throw new AssertionError("no finding for rule '" + rule + "' — findings were: "
				+ status.getFindings());
	}

	private static List<String> rulesOf(DrugReferenceLoad status) {
		List<String> rules = new ArrayList<String>();
		for (DrugReferenceValidity.Finding found : status.getFindings()) {
			rules.add(found.getRule());
		}
		return rules;
	}

	// ------------------------------------------------------------------
	// #150 — a blank alias captures every allergen
	// ------------------------------------------------------------------

	/**
	 * Issue #150. A whitespace-only alias is a token the boundary scan finds wherever a space follows a
	 * non-alphanumeric character, so the entry carrying it matches allergen text it has nothing to do
	 * with, and its contraindications then fire for a patient with an unrelated allergy. Dropped at load,
	 * because the token — not the entry — is what fails open.

	 * <p>Narrower than every allergen, and the negative half is asserted below for that reason: a
	 * single-word allergen has no space, and a space preceded by a letter fails the left boundary.
	 */
	@Test
	public void aBlankAliasIsDroppedAtLoadSoTheEntryStopsMatchingEveryAllergen() throws IOException {
		DrugReferenceService service = loading(BLANK_ALIAS_FIXTURE, "h150-blank-alias.json",
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"a file whose alias captures every allergen must be reported, not accepted in "
							+ "silence. Captured: " + capture.describeAll());
		}

		DrugReference warfarin = DrugReferenceTestSupport.row(service.getAll(), "Warfarin");
		assertEquals("[warfarin]", warfarin.getAliases().toString(),
				"the blank alias must be gone from the loaded entry, leaving the usable ones");
		assertFalse(warfarin.matchesDrugName("Vitamin A, B"),
				"the whole point: with the blank alias present this is TRUE (measured through this same "
						+ "matcher), so the warfarin contraindication fires for a patient whose only "
						+ "recorded allergy is to a multivitamin");
		assertFalse(warfarin.matchesText("vitamin a, b"),
				"and the prose matcher is false on the same string with or without the blank alias, which "
						+ "is the asymmetry issue #150 reports: #148 gave allergen resolution the "
						+ "recorded-name rule, whose inflection tail is what opened this");
		assertTrue(warfarin.matchesDrugName("Warfarine Co 5mg"),
				"and the entry must still resolve the drug it is about");

		DrugReference gentamicin = DrugReferenceTestSupport.row(service.getAll(), "Gentamicin");
		assertEquals("[gentamicin]", gentamicin.getAliases().toString(),
				"the healthy entry in the same file is untouched");
		assertFalse(gentamicin.matchesDrugName("Vitamin A, B"),
				"the shape is narrow: an entry with no blank alias never matched this, so the drop is "
						+ "removing a spurious match rather than narrowing a real one");
		assertEquals(2, service.getAll().size(),
				"and the entry itself is KEPT: it carries a real contraindication and a real ATC code, "
						+ "so refusing it would trade a fail-open for a silent fail-closed");

		DrugReferenceValidity.Finding found = finding(status, DrugReferenceValidity.BLANK_ALIAS);
		assertEquals(DrugReferenceValidity.Remedy.DROPPED, found.getRemedy());
		assertEquals(1, found.getOccurrences());
		assertTrue(found.getDetail().contains("Warfarin"),
				"the finding must name the entry an operator has to fix. Detail was: " + found.getDetail());
	}

	// ------------------------------------------------------------------
	// #211 / #210 — a substance's rows disagree
	// ------------------------------------------------------------------

	/**
	 * Issue #210's precondition, repaired. An entry whose {@code aliases} omit its own {@code name} is
	 * the one shape a hand-authored file admits and the {@code ddinter}/{@code atc} parsers cannot
	 * produce, and it is what lets the ranked resolution reach a claimant that is absent from the
	 * candidate set — the shape that empties one. The loader gives the entry its own name, which is what
	 * the other two parsers do as they build their alias lists, so the invariant holds by construction
	 * rather than by a fallback.
	 */
	@Test
	public void anEntryWhoseAliasesOmitItsOwnNameIsGivenItAtLoad() throws IOException {
		DrugReferenceService service = loading(NAME_NOT_ITS_OWN_ALIAS_FIXTURE, "h211-stem-only.json",
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"the repair is reported: the file is still wrong and only its symptom was fixed. "
							+ "Captured: " + capture.describeAll());
		}

		DrugReference stem = DrugReferenceTestSupport.row(service.getAll(), "Ibuprofen");
		assertTrue(stem.isNamed(stem.getName()),
				"every loaded entry must be named by one of its own aliases, was: " + stem.getAliases());
		assertEquals("[ibuprof, ibuprofen]", stem.getAliases().toString(),
				"the display name is appended, lowercased as the other two parsers build theirs, and the "
						+ "authored aliases are kept — the repair adds, it does not replace");
		assertEquals("[Ibuprofen tablets, Ibuprofen suspension, Ibuprofen]", DrugReferenceTestSupport
				.names(service.findByQuery("is ibuprofen 400mg safe?")).toString(),
				"and the consequence: the strongest claimant on the word is now IN the prose candidate "
						+ "set, so the narrowing has a claimant to keep and never reaches the fallback");

		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.ENTRY_NOT_NAMED_BY_ITS_OWN_ALIASES);
		assertEquals(DrugReferenceValidity.Remedy.REPAIRED, found.getRemedy());
		// All THREE rows, not only the stem row the fixture was written for: the two presentations
		// publish `ibuprofen` and not their own longer names either, which is the same violation and was
		// not noticed when the fixture was authored. Measured through this load.
		assertEquals(3, found.getOccurrences());
		assertEquals("[ibuprofen, ibuprofen tablets]",
				DrugReferenceTestSupport.row(service.getAll(), "Ibuprofen tablets").getAliases().toString(),
				"so a presentation is now reachable by its own name, which is what an order for it "
						+ "actually carries");
	}

	/**
	 * Issue #211, and the decision it asked for: the loader reports that the file does not say which of
	 * its rows are one substance, rather than guessing.
	 *
	 * <p>Three rows share the published name {@code ibuprofen} and none declares a {@code substanceName},
	 * so each is its own substance to {@link DrugReference#substanceGroupKey()} and the ranked resolution
	 * keeps only the strongest claimant on that name — the bare row, which is the one carrying no rules.
	 * Asserted as the resolved set, because the count alone does not say WHICH rows went.
	 */
	@Test
	public void rulesOnRowsThatDoNotDeclareTheirSubstanceAreReportedAsUnreachable() throws IOException {
		DrugReferenceService service = loading(NAME_NOT_ITS_OWN_ALIAS_FIXTURE, "h211-undeclared.json",
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT);

		DrugReferenceLoad status = service.getLoadStatus();

		// The order name carries only the SUBSTANCE's name, which is the ordinary shape and the one that
		// still loses rules after the repair above: the presentations are reachable by their own names,
		// and this string carries neither. `Ibuprofen tablets 400mg` would keep the tablets row.
		assertEquals("[Ibuprofen]", DrugReferenceTestSupport
				.names(service.findImpliedByDrugName("Ibuprofen 400mg")).toString(),
				"the defect the finding is about: two rule-bearing rows are dropped and the rule-less "
						+ "one kept, so the candidate set is non-empty and the findings are gone anyway");

		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.RULES_WITHOUT_A_SUBSTANCE_IDENTITY);
		assertEquals(DrugReferenceValidity.Remedy.REPORTED, found.getRemedy(),
				"REPORTED and not repaired: merging rows because their names look alike is the substance "
						+ "judgement issues #164/#192 measured this module must not make from names");
		assertEquals(2, found.getOccurrences(), "one per rule-bearing row at risk");
		assertTrue(found.getDetail().contains("ibuprofen"),
				"the finding must name the shared name, which is what the operator has to disambiguate. "
						+ "Detail was: " + found.getDetail());
	}

	/**
	 * The same three rows authored the way the shipped datasets are — the data fix the finding above asks
	 * for. Pinned beside it so the reported defect is shown to have a remedy in the FILE rather than only
	 * a warning, and so the rule is shown to be quiet once the file answers it.
	 *
	 * <p>It takes both halves of that remedy, which is why the fixture carries both and why the finding's
	 * detail names both: {@code substanceName} is the claim, and a curated file publishes no substance
	 * registry to confirm it, so {@link DrugReference#substanceKey()} falls back to the display STEM —
	 * under which {@code Ibuprofen tablets} is its own substance and {@code Ibuprofen (tablets)} is not.
	 */
	@Test
	public void declaringTheSubstanceKeepsEveryRuleBearingRowAndSilencesTheFinding() throws IOException {
		DrugReferenceService service = loading(SUBSTANCE_DECLARED_FIXTURE, "h211-declared.json",
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a file that answers every rule must load in silence, or the channel becomes noise "
							+ "every install learns to ignore. Captured: " + capture.describeAll());
		}

		assertEquals("[Ibuprofen (tablets), Ibuprofen (suspension), Ibuprofen]", DrugReferenceTestSupport
				.names(service.findImpliedByDrugName("Ibuprofen 400mg")).toString(),
				"once the rows agree they are one substance, the per-substance verdict keeps all of them "
						+ "and no rule is dropped — the same order name that loses two rows above");
		assertTrue(status.getFindings().isEmpty(),
				"and the check reports nothing at all. Findings were: " + status.getFindings());
	}

	// ------------------------------------------------------------------
	// #196 — a published name that denotes a different substance
	// ------------------------------------------------------------------

	/**
	 * The check decided on issue #196, over a verbatim slice of the shipped 19 MB knowledge base. An
	 * entry publishing, among its own names, a name that a DIFFERENT substance is called is a name
	 * collision no ranking can resolve: {@code isNamed} — rule-token identity — does not rank, so the
	 * collision is reachable however carefully the prose legs are ordered (issue #210's own bound).
	 *
	 * <p>Reported and not repaired: the module has no better data to substitute, and dropping the row
	 * would lose its real interaction rules. The remedy is upstream, which is what #196 records.
	 */
	@Test
	public void aPublishedNameDenotingADifferentSubstanceIsReportedAndTheDataIsLeftAlone()
			throws IOException {
		DrugReferenceService service = loading(ALIAS_NAMES_ANOTHER_SUBSTANCE_FIXTURE, "h196-slice.json",
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"eight known-bad rows in a 2283-row file that nothing checks is the same shape as an "
							+ "empty load logged at INFO. Captured: " + capture.describeAll());
		}

		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.ALIAS_NAMES_ANOTHER_SUBSTANCE);
		assertEquals(DrugReferenceValidity.Remedy.REPORTED, found.getRemedy());
		assertEquals(2, found.getOccurrences(),
				"exactly the two collisions in this slice, and not the three controls beside them. "
						+ "Detail was: " + found.getDetail());
		assertTrue(found.getDetail().contains("Pfizer-BioNTech Covid-19 Vaccine")
				&& found.getDetail().contains("moderna covid-19 vaccine"),
				"item 8: a rival manufacturer's product carried as one of this entry's own names. "
						+ "Detail was: " + found.getDetail());
		assertTrue(found.getDetail().contains("Trastuzumab emtansine")
				&& found.getDetail().contains("trastuzumab deruxtecan"),
				"item 6, through the half of it that reaches a clinician: the three trastuzumab rows "
						+ "share one CIEL list. Detail was: " + found.getDetail());
		assertFalse(found.getDetail().contains("Hydrocortisone butyrate"),
				"the control the display-stem half excludes — an ester whose own name carries the "
						+ "substance's. Detail was: " + found.getDetail());
		assertFalse(found.getDetail().contains("Daxibotulinumtoxina"),
				"the control the substance-identity half excludes — one substance under two names, "
						+ "which issue #164 decided is one substance. Detail was: " + found.getDetail());

		assertEquals(9, service.getAll().size(), "every row is still loaded; nothing was dropped");
	}

	// ------------------------------------------------------------------
	// #156 — a configured source that was not the one used
	// ------------------------------------------------------------------

	/**
	 * Issue #156, case 1. A {@code dataFilePath} that cannot be read falls back to the bundled dataset
	 * and yields a plausible non-zero count, so "the count is non-zero, so my file loaded" is false.
	 * Loud, because the operator named a file and a different dataset is in force.
	 */
	@Test
	public void anExplicitDataFilePathThatCouldNotBeReadIsReportedLoudly() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH,
				"chartsearchai/h156-absent-drug-reference.json");

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = new DrugReferenceService().getLoadStatus();
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"the operator named a file and a different dataset is in force; nothing above INFO "
							+ "used to say so. Captured: " + capture.describeAll());
		}

		assertTrue(status.getEntryCount() > 0, "the fallback loaded, which is why this looks healthy");
		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.CONFIGURED_DATA_FILE_NOT_READ);
		assertEquals(DrugReferenceValidity.Remedy.REPORTED, found.getRemedy());
		assertTrue(found.getDetail().contains("h156-absent-drug-reference.json"),
				"the finding names the file that was asked for. Detail was: " + found.getDetail());
		assertTrue(found.getDetail().contains(JsonDrugReferenceSource.CLASSPATH_DEFAULT),
				"and the one that was read instead. Detail was: " + found.getDetail());
	}

	/**
	 * The untouched default must stay quiet, and it is the normal state of every install rather than a
	 * misconfiguration: {@code dataFilePath} defaults to a path inside the application data directory
	 * that the module never creates, so a rule keyed on "a path is configured and we fell back" would
	 * fire on every fresh install and be filtered within a week.
	 *
	 * <p>Both spellings of untouched are asserted, because an install has one and a test context the
	 * other: the declared default value, and unset.
	 */
	@Test
	public void aFallbackFromTheUntouchedDefaultPathIsNotReportedAtAll() {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");

		for (String untouched : new String[] {
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_DATA_FILE_PATH, "" }) {
			Context.getAdministrationService().setGlobalProperty(
					ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, untouched);

			DrugReferenceLoad status;
			try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
				status = new DrugReferenceService().getLoadStatus();
				assertFalse(capture.hasEventAtOrAbove(Level.WARN),
						"the declared default naming a file the module never creates is the shipped "
								+ "state, not an error, for dataFilePath = '" + untouched
								+ "'. Captured: " + capture.describeAll());
			}
			assertTrue(status.getEntryCount() > 0, "the bundled dataset is what such an install runs");
			assertFalse(rulesOf(status).contains(DrugReferenceValidity.CONFIGURED_DATA_FILE_NOT_READ),
					"and it is not a finding either, for dataFilePath = '" + untouched
							+ "'. Findings were: " + status.getFindings());
		}
	}

	/**
	 * Issue #156, case 2. A {@code sourceFormat} matching no adapter routes the file through the curated
	 * parser; where that parser can read it the load looks healthy and the operator believes a format is
	 * in force that is not.
	 *
	 * <p>Recorded as a finding rather than logged at WARN, and that is not the remedy #156 proposes.
	 * {@code DrugReferenceLoadContextTest.loadStatusReportsAMistypedSourceFormatSeparatelyFromTheOneInForce}
	 * asserts this case must NOT be loud; that assertion predates #156 and #156 overturns it, so the
	 * decision belongs to the maintainer rather than to this change, and the test is left as it stands.
	 * The finding is what makes the case first-class in the meantime: one place lists everything wrong
	 * with the load, whatever each rule's log level.
	 */
	@Test
	public void aMistypedSourceFormatIsRecordedAsAFindingEvenWhereItIsNotLoud() throws IOException {
		DrugReferenceService service = loading(SUBSTANCE_DECLARED_FIXTURE, "h156-typo.json", "jsonn");

		DrugReferenceLoad status = service.getLoadStatus();

		assertEquals(ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT,
				status.getSourceFormat(), "the curated parser is what actually ran");
		assertTrue(status.getEntryCount() > 0, "and it could read the file, so the load looks healthy");
		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.CONFIGURED_SOURCE_FORMAT_NOT_USED);
		assertEquals(DrugReferenceValidity.Remedy.REPORTED, found.getRemedy());
		assertFalse(found.isLoud(),
				"the one rule that is deliberately not loud, and the javadoc says whose decision that is");
		assertTrue(found.getDetail().contains("jsonn"),
				"the finding quotes the configured value, typo and all. Detail was: " + found.getDetail());
	}

	/**
	 * The unset and correctly-spelled cases are the untouched default for the format too, and must be
	 * silent in both channels — a finding on every install is as useless as a WARN on every install.
	 */
	@Test
	public void anUnsetOrCorrectSourceFormatIsNotAFinding() throws IOException {
		for (String honoured : new String[] { "", ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT,
				"DDINTER" }) {
			DrugReferenceService service = loading(ALIAS_NAMES_ANOTHER_SUBSTANCE_FIXTURE,
					"h156-honoured.json", honoured);
			assertFalse(
					rulesOf(service.getLoadStatus())
							.contains(DrugReferenceValidity.CONFIGURED_SOURCE_FORMAT_NOT_USED),
					"sourceFormat '" + honoured + "' selects an adapter (case-insensitively) or is the "
							+ "untouched default, so nothing was overridden. Findings were: "
							+ service.getLoadStatus().getFindings());
			deleteCopiedDatasets();
		}
	}

	// ------------------------------------------------------------------
	// The shipped data is what the rules have to stay quiet about
	// ------------------------------------------------------------------

	/**
	 * Every dataset the module ships must satisfy every rule. This is the control that keeps the check
	 * from becoming a channel operators filter: it is not evidence that any rule works — the cases above
	 * are — and it is the thing that breaks if a rule is written loosely enough to fire on good data.
	 *
	 * <p>The full 19 MB knowledge base is not in this repository, so it cannot be asserted here; it is
	 * the bundled sample that ships in the omod, and the full KB is measured on a deployment.
	 */
	@Test
	public void everyShippedDatasetSatisfiesEveryRule() throws IOException {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");

		for (String format : new String[] { ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT,
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER }) {
			Context.getAdministrationService().setGlobalProperty(
					ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT, format);
			Context.getAdministrationService().setGlobalProperty(
					ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, "");

			DrugReferenceLoad status = new DrugReferenceService().getLoadStatus();
			assertTrue(status.getEntryCount() > 0, "the bundled " + format + " dataset loads");
			assertTrue(status.getFindings().isEmpty(),
					"the bundled " + format + " dataset must satisfy every rule the loader applies to an "
							+ "operator's file. Findings were: " + status.getFindings());
		}
	}
}
