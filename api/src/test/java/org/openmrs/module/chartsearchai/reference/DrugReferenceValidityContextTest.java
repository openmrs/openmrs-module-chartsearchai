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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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

	private static final String DERIVATIVE_MERGED_FIXTURE =
			"chartsearchai-test/ddi-derivative-merged-into-one-substance.json";

	private static final String DERIVATIVE_RULE_EDGES_FIXTURE =
			"chartsearchai-test/ddi-derivative-rule-edges.json";

	private static final String FIXTURE_DIR = "chartsearchai-test";

	private static final String NO_INTERACTIONS_TABLE_FIXTURE =
			FIXTURE_DIR + "/ddi-no-interactions-table.json";

	private static final String EMPTY_INTERACTIONS_TABLE_FIXTURE =
			FIXTURE_DIR + "/ddi-empty-interactions-table.json";

	/** The one fixture on the test classpath that is deliberately in the shape issue #242 reports,
	 *  because it is the SUBJECT of the rule rather than a setting for one — see its metadata note. */
	private static final String DELIBERATELY_MIS_SHAPED = "ddi-no-interactions-table.json";

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

	/**
	 * Issue #196 item 4, over a verbatim slice of the shipped 19 MB knowledge base. The rule above
	 * cannot see this one and never could: it reports a published name denoting a DIFFERENT substance,
	 * and here the two rows are the SAME substance to {@link DrugReference#substanceGroupKey()}, so its
	 * first exclusion removes the case by construction. Measured on the shipped file 2026-08-13 by
	 * driving {@link DrugReferenceService#getLoadStatus()} over it: {@code alias-names-another-substance}
	 * fires 18 times and {@code Fluoroestradiol f-18} is in none of them.
	 *
	 * <p>The six controls are the point of the case. A rule keyed on "one substance, unlike names"
	 * would report {@code Daxibotulinumtoxina} — a merge issue #164 measured as CORRECT — so this one is
	 * keyed on the derivative relationship the row's OWN name states, and
	 * {@code Beclomethasone dipropionate (nasal)} is the control that separates the two: same inherited
	 * identity as the tracer, name extending the substance's by a word rather than embedding it.
	 *
	 * <p>The loaded-row count is asserted rather than assumed: a {@code ddi} document that parses to
	 * nothing does so in silence (issue #242), and a rule-count assertion over an empty load passes
	 * vacuously.
	 */
	@Test
	public void aDerivativeMergedIntoItsParentSubstanceIsReportedAndTheDataIsLeftAlone()
			throws IOException {
		DrugReferenceService service = loading(DERIVATIVE_MERGED_FIXTURE, "h196-item4-slice.json",
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			// Asked of THIS rule's line, not of any WARN: the slice deliberately fires the sibling rule
			// too (asserted below), and logTo logs every finding, so hasEventAtOrAbove(WARN) is
			// satisfied by that line alone and stays green with this rule deleted outright.
			assertTrue(capture.messagesAt(Level.WARN).stream().anyMatch(
					m -> m.contains(DrugReferenceValidity.DERIVATIVE_MERGED_WITH_ITS_PARENT_SUBSTANCE)),
					"a PET tracer and a therapeutic oestrogen keyed as one substance is exactly the "
							+ "content defect this check exists to be loud about. Captured: "
							+ capture.describeAll());
		}

		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.DERIVATIVE_MERGED_WITH_ITS_PARENT_SUBSTANCE);
		assertEquals(DrugReferenceValidity.Remedy.REPORTED, found.getRemedy());
		assertEquals(1, found.getOccurrences(),
				"exactly the one merge in this slice, and not the six controls beside it. Detail was: "
						+ found.getDetail());
		// One string, not two `contains` calls: "estradiol" is a substring of "Fluoroestradiol f-18", so
		// a second conjunct asking for it separately is true whenever the first is and asserts nothing.
		assertTrue(found.getDetail().contains("Fluoroestradiol f-18 is filed as 'estradiol' beside "
				+ "[Estradiol, Estradiol (topical)]"),
				"item 4: the tracer, the substance it was merged into, and the rows it was merged with. "
						+ "Detail was: " + found.getDetail());
		assertFalse(found.getDetail().contains("Beclomethasone dipropionate (nasal) is filed as"),
				"the closest control there is — a row with no drugbank_id of its own inheriting its "
						+ "family's, exactly as the tracer does, but whose name extends the substance "
						+ "name by a WORD. Detail was: " + found.getDetail());
		// The control that isolates containsWord ON ITS OWN, and the only one that can: the two
		// Beclomethasone rows share a display stem, so they answer the predicate alike and the parent
		// gate silences that family whatever the word test does. Sodium oxybate's family has rows that
		// carry `oxybate` as a word and rows that do not carry it at all, so it has a parent — and
		// weakening containsWord to bare equality reports it.
		assertFalse(found.getDetail().contains("Sodium oxybate is filed as"),
				"a salt whose own name extends the substance name by a word is a salt, not a "
						+ "derivative. Detail was: " + found.getDetail());
		// Asked as "not the SUBJECT of an occurrence" rather than "absent", because this control is a row
		// of the offending row's own family and the detail names those deliberately — an operator needs
		// to know what the derivative was merged WITH. Daxibotulinumtoxina is in a two-row family of its
		// own and can appear the same way; Levoketoconazole and Ospemifene are singletons and cannot.
		assertFalse(found.getDetail().contains("Estradiol (topical) is filed as"),
				"the control a route variant is: its stem IS the substance name, so it is a "
						+ "presentation rather than a derivative. Detail was: " + found.getDetail());
		assertFalse(found.getDetail().contains("Levoketoconazole"),
				"the control the merge gate excludes — the identical prefix-derivative shape, but the "
						+ "family names two DrugBank substances, so the id is withheld and the display "
						+ "stem already separates them. Detail was: " + found.getDetail());
		assertFalse(found.getDetail().contains("Daxibotulinumtoxina"),
				"the control that makes this rule narrower than 'one substance, unlike names': issue "
						+ "#164 measured that merge as correct. Detail was: " + found.getDetail());
		assertEquals(14, service.getAll().size(), "every row is still loaded; nothing was dropped");
		List<DrugReference> all = service.getAll();
		assertEquals(DrugReferenceTestSupport.row(all, "Estradiol").substanceGroupKey(),
				DrugReferenceTestSupport.row(all, "Fluoroestradiol f-18").substanceGroupKey(),
				"and nothing was repaired: the two rows are still one substance, because deciding they "
						+ "are two would be inventing a fact the data does not carry");
		// The two controls that need an identity, asserted rather than narrated: an assertFalse on a
		// detail string cannot tell a control that is present and correctly silent from one that was
		// removed from the fixture.
		assertEquals(DrugReferenceTestSupport.row(all, "Botulinum toxin type A").substanceGroupKey(),
				DrugReferenceTestSupport.row(all, "Daxibotulinumtoxina").substanceGroupKey(),
				"the #164 merge this rule must not report is only a control while it IS a merge");
		assertEquals(
				DrugReferenceTestSupport.row(all, "Beclomethasone dipropionate").substanceGroupKey(),
				DrugReferenceTestSupport.row(all, "Beclomethasone dipropionate (nasal)")
						.substanceGroupKey(),
				"and the ester control only isolates containsWord while the two rows are one substance "
						+ "— the (nasal) row carries no drugbank_id of its own and inherits DB00394");

		assertTrue(rulesOf(status).contains(DrugReferenceValidity.ALIAS_NAMES_ANOTHER_SUBSTANCE),
				"the sibling rule still fires on this slice — Levoketoconazole publishes 'ketoconazole' "
						+ "— which is what shows the two rules answer different questions about the same "
						+ "row. Rules were: " + rulesOf(status));
	}

	/**
	 * The three edges of that rule the shipped file cannot reach, in one hand-authored dataset. Each is
	 * a decision the shipped case leaves untested, and each fails in a direction the other cases would
	 * not show.
	 * <ul>
	 *   <li><b>The fold.</b> Both conditions have to read one alphabet, so the substring test folds
	 *       diacritics exactly as {@link DrugReference#containsWord} already does. Unfolded,
	 *       {@code Fluoroestradiól f-18} carries {@code estradiol} neither as a bounded word nor as a
	 *       raw substring, so a localized dataset would be quieter about a real merge than an ASCII
	 *       one. Measured over the shipped 19 MB KB: none of its 2283 rows carries a non-ASCII
	 *       character in {@code name} or {@code rxnorm_name}, so this decision has no verbatim witness
	 *       and the file says it is hand-authored.</li>
	 *   <li><b>The one input the two halves can disagree about.</b> A substance name with no letter or
	 *       digit is non-blank to {@code normalizeName}, and {@code containsWord} refuses it while
	 *       {@code String.contains} finds it in whichever stems carry the character — so an
	 *       {@code rxnorm_name} of {@code "-"} makes {@code Bupivacaine hcl-2} a "derivative" of
	 *       {@code "-"} beside {@code Marcaine}. It fails OPEN, which is why the gate is a guard and
	 *       not a nicety. A marks-only token is not this witness: it folds to empty, every row of the
	 *       family then derives, and the parent gate silences it first.</li>
	 *   <li><b>What "merged" means.</b> A derivative that the module has correctly kept APART from its
	 *       parent, but which has a route variant of its own, is a family of two derivatives with no
	 *       parent in it. Reporting those states a merge that never happened — so the rule requires a
	 *       row that is not a derivative, and a size check alone is not that.</li>
	 * </ul>
	 */
	@Test
	public void theRuleHoldsAtItsThreeEdges() throws IOException {
		DrugReferenceService service = loading(DERIVATIVE_RULE_EDGES_FIXTURE, "h196-item4-edges.json",
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			assertTrue(capture.messagesAt(Level.WARN).stream().anyMatch(
					m -> m.contains(DrugReferenceValidity.DERIVATIVE_MERGED_WITH_ITS_PARENT_SUBSTANCE)),
					"a localized dataset is not a quieter one. Captured: " + capture.describeAll());
		}
		assertEquals(8, service.getAll().size(), "a ddi document that parses to nothing does so in "
				+ "silence (issue #242), and every assertion below would then pass vacuously");

		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.DERIVATIVE_MERGED_WITH_ITS_PARENT_SUBSTANCE);
		assertEquals(1, found.getOccurrences(), "the localized derivative, and only it — not the row "
				+ "whose substance name names nothing, and not the two route variants of a derivative "
				+ "that has no parent in its family. Detail was: " + found.getDetail());
		assertTrue(found.getDetail().contains("Fluoroestradiól f-18 is filed as '"),
				"the accented display name is the row reported. Detail was: " + found.getDetail());
		assertFalse(found.getDetail().contains("Bupivacaine hcl-2"),
				"a substance name with no letter or digit names nothing, so no row derives from it. "
						+ "Detail was: " + found.getDetail());
		assertFalse(found.getDetail().contains("Levoketoconazole"),
				"the module kept this derivative apart from Ketoconazole exactly as designed; its two "
						+ "rows are route variants of the derivative itself, and nothing was merged. "
						+ "Detail was: " + found.getDetail());
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
	 * <p>Loud, and on the wire. Both channels, because they answer different questions: the log says it
	 * once at the moment it happened, and the status answers it afterwards — which is the question a lazy
	 * load makes a log line unable to answer (#149). This case is where that distinction was settled:
	 * reporting the configured and effective formats separately made the mistake OBSERVABLE, and #156
	 * asked for it to be LOUD, which is not the same thing.
	 */
	@Test
	public void aMistypedSourceFormatIsReportedLoudlyAndOnTheWire() throws IOException {
		DrugReferenceService service = loading(SUBSTANCE_DECLARED_FIXTURE, "h156-typo.json", "jsonn");

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"the operator named a parser and a different one is in force. Captured: "
							+ capture.describeAll());
		}

		assertEquals(ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT,
				status.getSourceFormat(), "the curated parser is what actually ran");
		assertTrue(status.getEntryCount() > 0, "and it could read the file, so the load looks healthy");
		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.CONFIGURED_SOURCE_FORMAT_NOT_USED);
		assertEquals(DrugReferenceValidity.Remedy.REPORTED, found.getRemedy());
		assertTrue(found.getDetail().contains("jsonn"),
				"the finding quotes the configured value, typo and all. Detail was: " + found.getDetail());

		// The serialized form an operator reads. A bare count here would recreate at this level the defect
		// issue #149 fixed one level down — a load of 0 and a load of 2283 logging identically — so each
		// finding has to carry which rule fired and what the loader did about it.
		Object serialized = status.toMap().get("findings");
		assertEquals("[{rule=configured-source-format-not-used, remedy=reported, occurrences=1}]",
				serialized == null ? "null" : keyedSummary(status),
				"the status must carry the rule, the remedy and the count for every finding");
		assertTrue(String.valueOf(serialized).contains("jsonn"),
				"and the detail, which is what names the value to fix. Was: " + serialized);
	}

	/** @return the findings as {@code rule/remedy/occurrences} triples, detail omitted, so the wire
	 *          contract can be asserted without pinning prose. */
	private static String keyedSummary(DrugReferenceLoad status) {
		List<String> shown = new ArrayList<String>();
		for (Map<String, Object> found : serializedFindings(status)) {
			shown.add("{rule=" + found.get("rule") + ", remedy=" + found.get("remedy")
					+ ", occurrences=" + found.get("occurrences") + "}");
		}
		return shown.toString();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> serializedFindings(DrugReferenceLoad status) {
		return (List<Map<String, Object>>) status.toMap().get("findings");
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
	// #242 — a document omits a table its own parser requires
	// ------------------------------------------------------------------

	/**
	 * Issue #242, the headline case. A {@code ddinter} document carrying {@code drugs} and no top-level
	 * {@code interactions} produced {@code Collections.emptyList()} and said nothing about it, so an
	 * operator's file with real content in it loaded as {@code loaded=true, entryCount=0} — reaching
	 * {@link DrugReferenceLoad#isInert()} and, through it, issue #149's WARN, which can only GUESS at the
	 * cause ("the usual cause is a format/path mismatch"). The findings channel — the one an operator can
	 * poll after a lazy load — was empty.
	 *
	 * <p>So the assertions here are about which channel says WHAT. The inert WARN is not the fix and was
	 * never missing; a finding naming the table, the parser and the rows that were discarded is.
	 */
	@Test
	public void aDdinterDocumentWithNoInteractionsTableIsReportedRatherThanParsedToNothingInSilence()
			throws IOException {
		DrugReferenceService service = loading(NO_INTERACTIONS_TABLE_FIXTURE, "h242-no-table.json",
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"a document whose content the parser discarded must be reported, not accepted in "
							+ "silence. Captured: " + capture.describeAll());
		}

		assertEquals(0, status.getEntryCount(),
				"REPORTED, not repaired: the loader does not synthesize the table the file omits, so the "
						+ "count still says plainly that nothing loaded");
		assertTrue(status.isInert(), "and the load is inert, which is what #149's WARN already said");

		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.DATASET_MISSING_A_REQUIRED_TABLE);
		assertEquals(DrugReferenceValidity.Remedy.REPORTED, found.getRemedy());
		assertEquals(1, found.getOccurrences(), "one table is missing, so the rule fires once");
		assertTrue(found.getDetail().contains("interactions"),
				"the finding must name the table an operator has to add. Detail was: " + found.getDetail());
		assertTrue(found.getDetail().contains("3 row"),
				"and how much content was discarded — three drug rows were read and thrown away, which is "
						+ "what separates this from an empty file. Detail was: " + found.getDetail());

		// The wire form, which is the only channel an operator can ask after a lazy load. A finding that
		// reached the log and not this would be the mirror of the state issues #149 and #154 settled.
		assertEquals("[{rule=dataset-missing-a-required-table, remedy=reported, occurrences=1}]",
				keyedSummary(status),
				"the status must carry the rule, the remedy and the count");
		assertTrue(String.valueOf(status.toMap().get("findings")).contains("interactions"),
				"and the detail, which is what names the table to add. Was: "
						+ status.toMap().get("findings"));
	}

	/**
	 * The twin, and the reason the remedy is REPORTED rather than a refusal: a drug catalogue declaring
	 * no interactions is a coherent document, and it loads. The fixture is byte-identical to the one
	 * above except for a trailing {@code "interactions": []}, so this isolates the presence of the key.
	 *
	 * <p>The entry count is asserted EQUAL to the number of rows the case above says were discarded, and
	 * that is deliberate rather than tidy. An absence assertion over a mis-shaped fixture is exactly the
	 * blind check issue #242 records — two of #183's ten new tests could not fail because their fixture
	 * parsed to nothing — so the silence asserted here is anchored to a load that demonstrably produced
	 * the rows. Break either fixture and this reddens instead of passing vacuously.
	 */
	@Test
	public void theSameDocumentDeclaringAnEmptyInteractionsTableLoadsItsDrugsAndSaysNothing()
			throws IOException {
		DrugReferenceService service = loading(EMPTY_INTERACTIONS_TABLE_FIXTURE, "h242-empty-table.json",
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a declared-but-empty table is a coherent document and must be silent. Captured: "
							+ capture.describeAll());
		}

		assertEquals(3, status.getEntryCount(),
				"the three rows the twin discards load here, which is what makes the silence above a "
						+ "measurement rather than a fixture that could not have produced anything");
		assertEquals("[Warfarin, Aspirin, Ibuprofen]",
				DrugReferenceTestSupport.names(service.getAll()).toString(),
				"and they are the rows themselves, not merely a count");
		assertTrue(status.getFindings().isEmpty(),
				"no rule fires on it. Findings were: " + status.getFindings());
	}

	/**
	 * The same rule from the other side, and the more reachable misconfiguration of the two: the
	 * operator's file is fine and the parser reading it is not. A curated document handed to the
	 * {@code ddinter} parser omits BOTH tables that parser requires, so the rule counts what it found
	 * rather than stopping at the first.
	 *
	 * <p>Issue #156 already reports a {@code sourceFormat} matching no adapter. This is the case that one
	 * cannot see: {@code ddinter} IS an adapter, so nothing was overridden and #156 is correctly silent —
	 * the mismatch is between the format and the FILE, which only the parser can observe.
	 */
	@Test
	public void aCuratedDocumentReadByTheDdinterParserNamesBothTablesItOmits() throws IOException {
		DrugReferenceService service = loading(SUBSTANCE_DECLARED_FIXTURE, "h242-wrong-parser.json",
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER);

		DrugReferenceLoad status = service.getLoadStatus();
		assertEquals(0, status.getEntryCount(), "the curated file is unreadable to the DDInter parser");
		assertFalse(rulesOf(status).contains(DrugReferenceValidity.CONFIGURED_SOURCE_FORMAT_NOT_USED),
				"and #156 is silent, correctly: 'ddinter' names a real adapter, so nothing was "
						+ "overridden. Findings were: " + status.getFindings());

		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.DATASET_MISSING_A_REQUIRED_TABLE);
		assertEquals(2, found.getOccurrences(), "both required tables are missing, and both are counted");
		assertTrue(found.getDetail().contains("drugs") && found.getDetail().contains("interactions"),
				"the finding names both. Detail was: " + found.getDetail());
	}

	/**
	 * And the mirror, which is the likeliest of all: {@code sourceFormat} left at its default while
	 * {@code dataFilePath} points at a DDInter export. The curated parser requires {@code entries}, finds
	 * none, and used to return empty in the same silence — one loader, one answer, so the rule is stated
	 * over "a table this parser requires" rather than over the DDInter schema.
	 */
	@Test
	public void aDdinterDocumentReadByTheCuratedParserIsReportedTheSameWay() throws IOException {
		DrugReferenceService service = loading(EMPTY_INTERACTIONS_TABLE_FIXTURE, "h242-curated-parser.json",
				ChartSearchAiConstants.DEFAULT_DRUG_REFERENCE_SOURCE_FORMAT);

		DrugReferenceLoad status = service.getLoadStatus();
		assertEquals(0, status.getEntryCount(), "the DDInter file is unreadable to the curated parser");

		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.DATASET_MISSING_A_REQUIRED_TABLE);
		assertEquals(DrugReferenceValidity.Remedy.REPORTED, found.getRemedy());
		assertEquals(1, found.getOccurrences());
		assertTrue(found.getDetail().contains("entries"),
				"named for what THIS parser requires. Detail was: " + found.getDetail());
	}

	/**
	 * The blind check issue #242 is really about, closed for every fixture rather than for the two it was
	 * found on. A {@code ddinter} fixture omitting {@code interactions} parses to nothing, so every
	 * absence assertion built on it passes whatever the production code does — two of issue #183's ten
	 * new tests were in exactly that state, and nothing said so. This drives the real parser over every
	 * dataset fixture on the test classpath and requires each to declare the tables its own parser needs
	 * and to produce at least one entry, so a fixture authored into that shape reddens here instead of
	 * quietly disarming whatever test is written against it.
	 *
	 * <p>The one deliberate exception is asserted rather than skipped, so the exception cannot rot into a
	 * hole: {@link #DELIBERATELY_MIS_SHAPED} is the subject of the rule and MUST fire it.
	 *
	 * <p>The fixture count is asserted too. An enumeration that finds nothing passes every assertion
	 * inside its own loop, which is the same failure shape one level up.
	 */
	@Test
	public void everyDatasetFixtureOnTheTestClasspathParsesToEntriesUnderItsOwnParser() throws Exception {
		File dir = new File(getClass().getClassLoader().getResource(FIXTURE_DIR).toURI());
		File[] fixtures = dir.listFiles();
		assertNotNull(fixtures, "the fixture directory should be on the test classpath: " + dir);

		List<String> checked = new ArrayList<String>();
		List<String> wrong = new ArrayList<String>();
		for (File fixture : fixtures) {
			if (!fixture.getName().endsWith(".json")) {
				continue;
			}
			checked.add(fixture.getName());
			DrugReferenceValidity validity = new DrugReferenceValidity();
			List<DrugReference> parsed;
			try (InputStream in = new FileInputStream(fixture)) {
				// The parser its NAME selects, which is the parser every test using it reaches through
				// DrugReferenceTestSupport — so this asks the question those tests silently assume.
				parsed = fixture.getName().startsWith("ddi-")
						? DdiDrugReferenceSource.parse(in, validity)
						: JsonDrugReferenceSource.parse(in, validity);
			}
			boolean unusable = parsed.isEmpty() || !validity.getFindings().isEmpty();
			if (unusable != DELIBERATELY_MIS_SHAPED.equals(fixture.getName())) {
				wrong.add(fixture.getName() + " -> " + parsed.size() + " entries " + validity.getFindings());
			}
		}

		assertTrue(checked.size() > 40,
				"the enumeration has to find the fixtures, or every check inside it is vacuous — found "
						+ checked.size() + ": " + checked);
		assertEquals("[]", wrong.toString(),
				"every fixture must parse to entries under the parser its name selects, and only "
						+ DELIBERATELY_MIS_SHAPED + " must not");
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
