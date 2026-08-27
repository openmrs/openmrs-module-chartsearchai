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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The load-time validity check: what the loader does when the dataset it was pointed at violates an
 * assumption the loader's own code makes (issues #150, #156, #211, and the check decided on #196).
 *
 * <p>Every RULE case here drives the REAL load — a file in the application data directory, the real
 * global properties, {@link DrugReferenceService#getAll()} / {@link DrugReferenceService#getLoadStatus()}
 * — for the reason CLAUDE.md gives and for one specific to this feature: the validity check runs where
 * the dataset is loaded, and the {@code setEntries} test seam deliberately bypasses all dataset loading,
 * so a case built through that seam would assert nothing about it.
 *
 * <p>One case is not about a rule and so does not:
 * {@link #everyDatasetFixtureOnTheTestClasspathParsesToEntriesUnderItsOwnParser} sweeps the FIXTURE
 * CORPUS through the real parsers directly, because what it asks — can a test written against this
 * fixture fail at all? — is a question about the files rather than about a load, and copying every one
 * of them into the application data directory to ask it would answer the same question more slowly.
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
			DrugReferenceTestSupport.DDI_ALIAS_NAMES_ANOTHER_SUBSTANCE;

	private static final String NULL_LIST_ELEMENT_FIXTURE =
			"chartsearchai-test/drug-reference-null-rule-and-band.json";

	private static final String NULL_INTERACTION_AND_ALIAS_FIXTURE =
			"chartsearchai-test/drug-reference-null-interaction-and-alias.json";

	private static final String DERIVATIVE_MERGED_FIXTURE =
			"chartsearchai-test/ddi-derivative-merged-into-one-substance.json";

	private static final String DERIVATIVE_RULE_EDGES_FIXTURE =
			"chartsearchai-test/ddi-derivative-rule-edges.json";

	/**
	 * The corpus the sweep enumerates, and the one file in it that is deliberately in the shape issue
	 * #242 reports — both DERIVED from the shared constant rather than spelled again.
	 *
	 * <p>Not tidiness. The sweep exempts exactly one file by name, and that exemption has to denote the
	 * same file the rule cases load; as two literals they are under no compiler obligation to agree, so
	 * the sweep's single hole could drift onto a healthy fixture while the rule cases went on passing —
	 * which is the "exception rots into a hole" failure the sweep's own javadoc claims to have avoided.
	 */
	private static final String FIXTURE_DIR = DrugReferenceTestSupport.DDI_NO_INTERACTIONS_TABLE
			.substring(0, DrugReferenceTestSupport.DDI_NO_INTERACTIONS_TABLE.lastIndexOf('/'));

	private static final String DELIBERATELY_MIS_SHAPED = DrugReferenceTestSupport.DDI_NO_INTERACTIONS_TABLE
			.substring(DrugReferenceTestSupport.DDI_NO_INTERACTIONS_TABLE.lastIndexOf('/') + 1);

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
		String path = DrugReferenceTestSupport.copyDatasetToAppData(classpathFixture, asName, created);
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT, sourceFormat);
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, path);
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
		return rulesOf(status.getFindings());
	}

	/** The findings-list form, for the parse-level sweep, which has a collector rather than a load. One
	 *  definition so the two call shapes cannot come to mean different things. */
	private static List<String> rulesOf(List<DrugReferenceValidity.Finding> findings) {
		List<String> rules = new ArrayList<String>();
		for (DrugReferenceValidity.Finding found : findings) {
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
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON);

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
	// A null where a value should be (found reviewing #285)
	// ------------------------------------------------------------------

	/**
	 * A {@code null} element inside one of an entry's own lists. The parsers drop null ENTRIES and
	 * nothing inside them, so unless the loader drops it, {@code "contraindications": [null]} in an
	 * operator's file reaches every consumer of the loaded model — and the consumers dereference their
	 * elements: a null rule throws in {@code DrugSafetyValidator.isAllergyRule}, a null band in
	 * {@link DrugReference#bandForAge}.
	 *
	 * <p>So the value is DROPPED at load, which is this loader's remedy for a bad value whose entry is
	 * otherwise usable, and the finding tells the operator which entry to fix. What makes it a rule
	 * rather than a null check at each of those call sites is what those call sites are: the safety
	 * arms, whose throw lands behind {@code validate}'s own catch and answers the request with NO chips
	 * at all — so a guard at one site leaves the others dropping every chip on the request, behind a
	 * WARN naming an NPE rather than the dataset, while the status endpoint reports the dataset as
	 * healthy.
	 */
	@Test
	public void aNullElementInAnEntrysOwnListIsDroppedSoNoConsumerCanThrowOnIt() throws IOException {
		DrugReferenceService service = loading(NULL_LIST_ELEMENT_FIXTURE, "h288-null-element.json",
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"the operator's own file carries a value that is not one, and they can fix it. "
							+ "Captured: " + capture.describeAll());
		}

		DrugReference entry = DrugReferenceTestSupport.row(service.getAll(), "Ibuprofen");
		assertTrue(entry.getContraindications().isEmpty(),
				"the null rule is gone from the loaded entry, so nothing downstream can dereference it");
		assertTrue(entry.getAgeBands().isEmpty(), "and so is the null band");
		assertEquals(1, service.getAll().size(),
				"and the entry itself is KEPT: its name, aliases and ATC code are usable, so refusing it "
						+ "would trade a bad value for a silent fail-closed");

		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.NULL_LIST_ELEMENT);
		assertEquals(DrugReferenceValidity.Remedy.DROPPED, found.getRemedy());
		assertEquals(2, found.getOccurrences(), "one per null element: the rule and the band");
		assertTrue(found.getDetail().contains("Ibuprofen"),
				"the finding must name the entry an operator has to fix. Detail was: " + found.getDetail());

		// The runtime consequence, through the real validator on the real load. Not the assertion that
		// fires FIRST when the drop is removed: the per-arm load report (issue #285) dereferences the
		// same elements as the load happens, so getLoadStatus above throws before this line is reached
		// — DrugReferenceLoadContextTest.aNullRuleOrBandDoesNotBringTheLoadDown is that path's own
		// case. This is the arm the rule is FOR, and what still fires if that report is ever removed:
		// the (answer, question, context) overload below has no catch, unlike the public Patient one.
		assertNotNull(DrugReferenceTestSupport.validator(service).validate(
				"Ibuprofen 4000 mg daily could be given.", "Is ibuprofen safe?",
				DrugReferenceTestSupport.ctx(60, 70.0, null, null, null, null)),
				"every safety arm must survive the dataset: the throw is caught by validate's own "
						+ "public entry point, which answers a request with NO chips at all");
	}

	/**
	 * The other four lists the same rule covers. {@link DrugReferenceValidity#NULL_LIST_ELEMENT} is
	 * stated over EVERY list rather than the two that throw today, and the fixture above witnesses two of
	 * them, so this is the case for {@code aliases}, {@code atcCodes}, {@code warnings} and
	 * {@code interactions}.
	 *
	 * <p>The interactions leg has consumers that dereference an element with no null check —
	 * {@code DrugSafetyValidator.clearsSeverityFloor} reads {@code getSeverity()} and
	 * {@code DrugReferenceInjector.partnerLabel} reads {@code getToken()} — so a null surviving the load
	 * reproduces what this rule exists to prevent: the throw landing behind {@code validate}'s catch, the
	 * request losing every chip, and the status endpoint still reporting the dataset healthy. Measured by
	 * removing that leg from the drop: the real {@code validate} at the end of this case throws at
	 * {@code clearsSeverityFloor}, reached from {@code addInteractionWarnings} through
	 * {@code bestRulePerPartner}.
	 */
	@Test
	public void aNullInTheOtherFourListsIsDroppedAsWell() throws IOException {
		DrugReferenceService service = loading(NULL_INTERACTION_AND_ALIAS_FIXTURE,
				"h288-null-interaction.json", ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"the operator's own file carries four values that are not values, and they can fix "
							+ "them. Captured: " + capture.describeAll());
		}

		DrugReference presentation = DrugReferenceTestSupport.row(service.getAll(),
				"Ibuprofen tablets 400mg");
		assertTrue(presentation.getInteractions().isEmpty(),
				"the null interaction is gone from the loaded entry: clearsSeverityFloor and "
						+ "partnerLabel both dereference an element of this list");
		assertTrue(presentation.getWarnings().isEmpty(), "and so is the null warning");
		DrugReference substance = DrugReferenceTestSupport.row(service.getAll(), "Ibuprofen");
		assertEquals(Arrays.asList("ibuprofen"), substance.getAliases(),
				"the null alias is gone and the real one is kept — a resolution key list is not the "
						+ "place to trade a bad value for a fail-closed entry");
		assertEquals(Arrays.asList("M01AE01"), substance.getAtcCodes(), "and so is the null code");

		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.NULL_LIST_ELEMENT);
		assertEquals(DrugReferenceValidity.Remedy.DROPPED, found.getRemedy());
		assertEquals(4, found.getOccurrences(),
				"one per null element: the interaction, the warning, the alias and the code");
		assertEquals(2, found.getDetail().split("Ibuprofen", -1).length - 1,
				"the finding must name BOTH entries an operator has to fix — and one of the two names "
						+ "nests inside the other, so a containment check on the shorter is satisfied by "
						+ "the longer alone. Detail was: " + found.getDetail());

		// The runtime consequence, through the real validator on the real load: the interaction arms
		// walk this entry's own rule list against the patient's active order.
		assertNotNull(DrugReferenceTestSupport.validator(service).validate(
				"Ibuprofen tablets 400mg could be given.", "Are there any interactions with ibuprofen?",
				DrugReferenceTestSupport.ctx(60, 70.0, DrugReferenceTestSupport.set("Ibuprofen"),
						null, null, null)),
				"every safety arm must survive the dataset: the throw is caught by validate's own "
						+ "public entry point, which answers a request with NO chips at all");
	}

	/**
	 * The DROP runs before every rule that reads the lists it cleans, which {@code dropNullElements}'s
	 * own javadoc declares load-bearing in two places. Both consequences are asserted as ABSENCES,
	 * because both are a rule below staying silent:
	 *
	 * <ul>
	 * <li>{@code carriesRules} reads only whether those lists are EMPTY, so the presentation row — whose
	 * only rule is a null — must not count as rule-bearing. It shares the published name
	 * {@code ibuprofen} with the substance row and declares no {@code substanceName}, which is the whole
	 * of {@code RULES_WITHOUT_A_SUBSTANCE_IDENTITY}'s shape apart from that gate, so with the drop moved
	 * after it the finding fires — reporting rules at risk for a row that carries none.</li>
	 * <li>a null ALIAS is reported as what it is rather than by {@code BLANK_ALIAS} as a token naming
	 * nothing, which is only true while the drop is the first to see the list.</li>
	 * </ul>
	 *
	 * <p>Measured by moving the call to the end of {@code checkEntries}: both findings appear.
	 */
	@Test
	public void theNullDropRunsBeforeTheRulesThatReadTheListsItCleans() throws IOException {
		DrugReferenceService service = loading(NULL_INTERACTION_AND_ALIAS_FIXTURE,
				"h288-null-drop-order.json", ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON);

		DrugReferenceLoad status = service.getLoadStatus();

		assertTrue(rulesOf(status).contains(DrugReferenceValidity.NULL_LIST_ELEMENT),
				"the case has to reach the rule it is about. Rules were: " + rulesOf(status));
		assertFalse(rulesOf(status).contains(DrugReferenceValidity.RULES_WITHOUT_A_SUBSTANCE_IDENTITY),
				"a row whose only rule is a null carries no rules, so it loses none by keying as its "
						+ "own substance. Rules were: " + rulesOf(status));
		assertFalse(rulesOf(status).contains(DrugReferenceValidity.BLANK_ALIAS),
				"a null alias is not a token naming nothing; it is reported as the null it is. Rules "
						+ "were: " + rulesOf(status));
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
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON);

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
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON);

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
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON);

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
	// #152/#164 — rows the parser dropped as self-paired
	// ------------------------------------------------------------------

	/**
	 * Issues #152 and #164, reported rather than only logged. A row pairing a substance with itself
	 * carries no clinical claim, so {@code DdiDrugReferenceSource.isSelfPair} drops it — and the COUNT is
	 * what a maintainer compares across knowledge-base refreshes.
	 *
	 * <p>It was a bare {@code log.warn} inside the parser until ADR Decision 36, which cost it the channel
	 * that can still answer after a lazy load (#154): it was the one data verdict in this loader that never
	 * appeared on {@code GET /chartsearchai/drugreferencestatus}, so an operator polling the status saw a
	 * healthy count and no sign that rows had been discarded. Asserted on all three of the things every
	 * rule here is asserted on — what the loader did, that it said so, and that the status names it
	 * afterwards — plus the count, because a rule reporting "some rows" would be the bare log line again.
	 */
	@Test
	public void rowsPairingASubstanceWithItselfAreDroppedAndReportedWithTheirCount() throws IOException {
		DrugReferenceService service = loading(DrugReferenceTestSupport.DDI_SELF_INTERACTION,
				"h152-self-paired.json", ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"the fixture is an operator file, so its dropped rows are theirs to fix and must be "
							+ "loud. Captured: " + capture.describeAll());
		}

		assertTrue(status.getEntryCount() > 0, "the rest of the dataset still loads");
		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.SELF_PAIRED_INTERACTION_ROWS);
		assertEquals(DrugReferenceValidity.Remedy.DROPPED, found.getRemedy(),
				"the row is the offending value and the rest of the dataset is usable");
		assertEquals(3, found.getOccurrences(),
				"the fixture carries three such rows, and the count is the reportable fact");
		assertTrue(keyedSummary(status)
				.contains("{rule=" + DrugReferenceValidity.SELF_PAIRED_INTERACTION_ROWS
						+ ", remedy=dropped, occurrences=3}"),
				"and it reaches the wire, which is what it never did as a log line. Was: "
						+ keyedSummary(status));
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
		// Named rather than left to the default, so the classpath fallback this case asserts on is the
		// curated parser's own and stays that whatever the default format is (it moved once, in ADR Decision 36).
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT,
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON);
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

		assertEquals(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON,
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
		for (String honoured : new String[] { "", ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON,
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
		DrugReferenceService service = loading(DrugReferenceTestSupport.DDI_NO_INTERACTIONS_TABLE,
				"h242-no-table.json",
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			// Naming the RULE, not merely "some WARN was logged". That weaker form passes on main: this
			// load is inert, so issue #149's WARN satisfies it on its own, and it would go on passing if
			// this rule never reached the log channel at all.
			assertTrue(
					capture.messagesAt(Level.WARN).toString()
							.contains(DrugReferenceValidity.DATASET_MISSING_A_REQUIRED_TABLE),
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
		Object serialized = status.toMap().get("findings");
		assertEquals("[{rule=dataset-missing-a-required-table, remedy=reported, occurrences=1}]",
				keyedSummary(status),
				"the status must carry the rule, the remedy and the count");
		assertTrue(String.valueOf(serialized).contains("interactions"),
				"and the detail, which is what names the table to add. Was: " + serialized);
	}

	/**
	 * The twin, and the reason the remedy is REPORTED rather than a refusal: a drug catalogue declaring
	 * no interactions is a coherent document, and it loads. The fixture carries the same three drug rows
	 * as the one above and declares the table those rows were discarded for want of; nothing else about
	 * the two differs, so what this isolates is the presence of the key rather than the content.
	 *
	 * <p>The entry count is asserted EQUAL to the number of rows the case above says were discarded, and
	 * that is deliberate rather than tidy. An absence assertion over a mis-shaped fixture is exactly the
	 * blind check issue #242 records, so the silence asserted here is anchored to a load that
	 * demonstrably produced the rows. Break either fixture and this reddens instead of passing vacuously
	 * — measured, by removing the {@code interactions} key from the twin.
	 */
	@Test
	public void theSameDocumentDeclaringAnEmptyInteractionsTableLoadsItsDrugsAndSaysNothing()
			throws IOException {
		DrugReferenceService service = loading(DrugReferenceTestSupport.DDI_EMPTY_INTERACTIONS_TABLE,
				"h242-empty-table.json",
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

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			assertTrue(
					capture.messagesAt(Level.WARN).toString()
							.contains(DrugReferenceValidity.DATASET_MISSING_A_REQUIRED_TABLE),
					"the log leg, named rather than merely present: this load is inert, so issue #149's "
							+ "WARN would satisfy a bare 'something warned'. Captured: "
							+ capture.describeAll());
		}

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
	 * And the mirror, which was the likeliest of all while {@code json} was the default: an untouched
	 * {@code sourceFormat} beside a {@code dataFilePath} pointing at a DDInter export. Since ADR Decision
	 * 36 the untouched case is {@code ddinter}, so this direction now takes an explicit
	 * {@code sourceFormat=json} — which is why the case names the format rather than leaving it unset, and
	 * why the likelier mismatch today is the one asserted above. The curated parser requires {@code entries}, finds
	 * none, and used to return empty in the same silence — one loader, one answer, so the rule is stated
	 * over "a table this parser requires" rather than over the DDInter schema.
	 */
	@Test
	public void aDdinterDocumentReadByTheCuratedParserIsReportedTheSameWay() throws IOException {
		DrugReferenceService service = loading(DrugReferenceTestSupport.DDI_EMPTY_INTERACTIONS_TABLE,
				"h242-curated-parser.json",
				ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON);

		DrugReferenceLoad status;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER)) {
			status = service.getLoadStatus();
			assertTrue(
					capture.messagesAt(Level.WARN).toString()
							.contains(DrugReferenceValidity.DATASET_MISSING_A_REQUIRED_TABLE),
					"same log leg, and it matters more here: this is the default format, so an operator "
							+ "hitting it has changed nothing but the path. Captured: "
							+ capture.describeAll());
		}

		assertEquals(0, status.getEntryCount(), "the DDInter file is unreadable to the curated parser");

		DrugReferenceValidity.Finding found = finding(status,
				DrugReferenceValidity.DATASET_MISSING_A_REQUIRED_TABLE);
		assertEquals(DrugReferenceValidity.Remedy.REPORTED, found.getRemedy());
		assertEquals(1, found.getOccurrences());
		// Bracketed, for the reason JsonDrugReferenceSourceTest gives: the bare word is in the shared
		// boilerplate, so it would pass on a finding that named the wrong table.
		assertTrue(found.getDetail().contains("[entries]"),
				"named for what THIS parser requires. Detail was: " + found.getDetail());
		// And the ITEM noun, which is a parameter since issue #266 gave the cross-reactivity groups file
		// this same rule: a groups document that produced nothing produced no GROUPS. The two call sites
		// pass different literals, so this is what stops them being crossed — nothing else in the suite
		// reads this clause on the entry side, and a finding telling an operator their drug-reference
		// document "parsed to no groups at all" names the wrong dataset in citable evidence.
		assertTrue(found.getDetail().contains("parsed to no entries at all"),
				"an ENTRY dataset produced no entries. Detail was: " + found.getDetail());
	}

	/**
	 * The blind check issue #242 is really about, closed for the whole fixture corpus. A {@code ddinter}
	 * fixture omitting {@code interactions} parses to nothing, so every absence assertion built on it
	 * passes whatever the production code does. Issue #242 records two tests of issue #183's measurement
	 * pass as having been in that state, and says plainly that it relayed the finding rather than
	 * re-deriving it.
	 *
	 * <p><b>This sweep is a guard, not a repair</b>, and that is measured rather than assumed: of every
	 * version of every {@code ddi-} fixture in this repository's history, exactly one is mis-shaped, and
	 * it is {@link #DELIBERATELY_MIS_SHAPED} — added by this change. So no committed fixture was ever in
	 * that state, and #183's was never merged. Worth stating because a guard that has never fired reads
	 * like one that cannot: the mutation that shows this one can is removing the twin fixture's
	 * {@code interactions} key, which reddens it.
	 *
	 * <p>It drives the real parser over every dataset fixture on the test classpath and requires each to
	 * declare the tables its own parser needs and to produce at least one entry, so a fixture authored
	 * into that shape reddens here instead of quietly disarming whatever test is written against it.
	 * <b>All THREE parsers</b>, which is why the ATC sample is swept from its own directory rather than
	 * left out as the corpus's quiet second exception: it is a dataset fixture with a real parser and
	 * real dependants ({@code DrugReferenceTestSupport.atcService}), and one that parsed to nothing
	 * would disarm them in exactly the way this sweep exists to prevent. Its parser has a collector form
	 * since issue #266, but the rule it can report there ({@code no-line-yielded-an-entry}) fires exactly
	 * when it emits nothing, so for this leg the emptiness is still the whole check and the single-argument
	 * form is what it takes.
	 *
	 * <p>The one deliberate exception is asserted rather than skipped, so the exception cannot rot into a
	 * hole: {@link #DELIBERATELY_MIS_SHAPED} is the subject of the rule and MUST fire it.
	 *
	 * <p>The JSON fixture count is asserted too. An enumeration that finds nothing passes every
	 * assertion inside its own loop, which is the same failure shape one level up.
	 */
	@Test
	public void everyDatasetFixtureOnTheTestClasspathParsesToEntriesUnderItsOwnParser() throws Exception {
		File dir = new File(getClass().getClassLoader().getResource(FIXTURE_DIR).toURI());
		File[] fixtures = dir.listFiles();
		assertNotNull(fixtures, "the fixture directory should be on the test classpath: " + dir);

		List<String> checked = new ArrayList<String>();
		List<String> unrecognized = new ArrayList<String>();
		List<String> wrong = new ArrayList<String>();
		for (File fixture : fixtures) {
			if (!fixture.getName().endsWith(".json")) {
				continue;
			}
			// Which parser reads it has to be DECIDED rather than defaulted: a fixture of a third kind
			// (a cross-reactivity groups file, say) would otherwise be handed to the curated parser and
			// reported below as mis-shaped, which is a false alarm and the sort that gets a sweep
			// weakened rather than taught.
			boolean ddi = fixture.getName().startsWith("ddi-");
			if (!ddi && !fixture.getName().startsWith("drug-reference-")) {
				unrecognized.add(fixture.getName());
				continue;
			}
			checked.add(fixture.getName());
			DrugReferenceValidity validity = new DrugReferenceValidity();
			List<DrugReference> parsed;
			try (InputStream in = new FileInputStream(fixture)) {
				// The parser its NAME selects, which is the parser every test using it reaches through
				// DrugReferenceTestSupport — so this asks the question those tests silently assume.
				parsed = ddi ? DdiDrugReferenceSource.parse(in, validity)
						: JsonDrugReferenceSource.parse(in, validity);
			}
			// The DOCUMENT rule, not "any finding". Those were the same thing while a table this parser
			// requires was the only thing a parse could report; since ADR Decision 36 a parse also reports the rows
			// it dropped as self-paired, and TEN fixtures here carry such rows deliberately — they are the
			// #152/#164 corpus. A row a parser dropped does not make the file unusable, which is what this
			// sweep is about and what its javadoc says: the tables its parser needs, and at least one entry.
			boolean unusable = parsed.isEmpty() || rulesOf(validity.getFindings())
					.contains(DrugReferenceValidity.DATASET_MISSING_A_REQUIRED_TABLE);
			if (unusable != DELIBERATELY_MIS_SHAPED.equals(fixture.getName())) {
				wrong.add(fixture.getName() + " -> " + parsed.size() + " entries " + validity.getFindings());
			}
		}

		// The third parser's corpus, which lives in its own directory and takes no collector.
		File atc = new File(getClass().getClassLoader()
				.getResource(DrugReferenceTestSupport.ATC_SAMPLE).toURI()).getParentFile();
		File[] atcFixtures = atc.listFiles();
		assertNotNull(atcFixtures, "the ATC fixture directory should be on the test classpath: " + atc);
		List<String> atcChecked = new ArrayList<String>();
		for (File fixture : atcFixtures) {
			// Extension-filtered like the leg above: a subdirectory here would reach FileInputStream and
			// throw rather than fail as a finding, and a file of another format placed here would be
			// reported by a message that names the DDInter exemption and so points away from the cause.
			if (!fixture.getName().endsWith(".tsv")) {
				continue;
			}
			atcChecked.add(fixture.getName());
			try (InputStream in = new FileInputStream(fixture)) {
				if (AtcDrugReferenceSource.parse(in).isEmpty()) {
					wrong.add(fixture.getName() + " -> 0 entries (ATC)");
				}
			}
		}

		assertEquals("[]", unrecognized.toString(),
				"a fixture whose name selects no parser is not swept, so teach this sweep which parser "
						+ "reads it rather than leaving it unchecked");
		assertTrue(checked.size() > 40,
				"the enumeration has to find the fixtures, or every check inside it is vacuous — found "
						+ checked.size() + ": " + checked);
		// Weaker than its JSON counterpart and worth saying so: `atc` is derived from ATC_SAMPLE's own
		// URL, so that file is in the listing by construction. What can actually fall here is the
		// extension filter above drifting off the corpus, which is the whole of what this buys.
		assertFalse(atcChecked.isEmpty(),
				"the .tsv filter no longer matches anything in " + atc);
		assertEquals("[]", wrong.toString(),
				"every fixture must parse to entries under the parser its name selects, and only "
						+ DELIBERATELY_MIS_SHAPED + " must not");
	}

	// ------------------------------------------------------------------
	// The shipped data is what the rules have to stay quiet about
	// ------------------------------------------------------------------

	/**
	 * Every dataset the module AUTHORS must satisfy every rule. This is the control that keeps the check
	 * from becoming a channel operators filter: it is not evidence that any rule works — the cases above
	 * are — and it is the thing that breaks if a rule is written loosely enough to fire on good data.
	 *
	 * <p>The curated dataset is one the module authors, so a finding on it is a defect a commit here can
	 * fix, and the bar stays "none".
	 */
	@Test
	public void everyDatasetTheModuleAuthorsSatisfiesEveryRule() throws IOException {
		DrugReferenceLoad status = shippedLoadOf(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_JSON);

		assertTrue(status.getEntryCount() > 0, "the bundled curated dataset loads");
		assertTrue(status.getFindings().isEmpty(),
				"a dataset this repository authors must satisfy every rule the loader applies to an "
						+ "operator's file. Findings were: " + status.getFindings());
	}

	/**
	 * The dataset the module REDISTRIBUTES cannot be held to that bar, and this is the honest statement of
	 * what replaces it. The shipped DDInter knowledge base trips two content rules on 19 of its 2283 rows
	 * — {@code Omeprazole} publishing {@code esomeprazole}, four more {@code rxnorm_name}s naming an
	 * enantiomer or a prodrug's parent, ten stray CIEL cross-walk links, and {@code Fluoroestradiol f-18}
	 * keyed as {@code estradiol} — and issue #196 records the remedy for exactly these as an upstream
	 * handoff. Nine of them sit in {@code rxnorm_name}, which is the field
	 * {@link DrugReference#substanceKey()} is built from, so correcting them here would re-partition
	 * substances on our own authority; that is why ADR Decision 36 ships the file byte-identical and
	 * scopes the log level instead.
	 *
	 * <p>So the property asserted is the one that LICENSES that scoping: every finding this dataset
	 * produces must be one {@link DrugReferenceValidity#logTo(org.slf4j.Logger, String)} may report
	 * without being loud. A CONFIGURATION finding here would mean the softening had swallowed something
	 * that names an operator's own choice, which is the regression that would otherwise be invisible.
	 *
	 * <p><b>What this deliberately does not pin is the COUNT.</b> Pinning 18/1/28 would break the build on
	 * any knowledge-base refresh, including one that FIXES these rows, and Decision 36 chose not to couple
	 * the suite to a third party's data. The counts are reported on
	 * {@code GET /chartsearchai/drugreferencestatus}, which is where a maintainer reads them; what is
	 * pinned here is that the load still works ({@link ShippedDrugReferenceDefaultTest} holds the floors)
	 * and that nothing it reports is of a kind that must stay loud.
	 */
	@Test
	public void theDatasetTheModuleRedistributesReportsOnlyFindingsItsOwnProvenanceExplains()
			throws IOException {
		DrugReferenceLoad status = shippedLoadOf(ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER);

		assertTrue(status.getEntryCount() > 0, "the bundled DDInter knowledge base loads");
		assertFalse(status.isInert());
		for (DrugReferenceValidity.Finding found : status.getFindings()) {
			// Asked of the production classification rather than of a list of the two configuration rules
			// spelled here: a THIRD one added later would slip past an enumeration while this dataset went
			// on reporting it. Which rules are configuration is pinned separately, by
			// DrugReferenceFindingLoudnessTest.everyRuleIsClassifiedAsDataOrAsConfiguration.
			assertTrue(DrugReferenceValidity.scopedToWhoOwnsTheDataset(found.getRule()),
					"a finding naming the operator's own configuration must never be among what the "
							+ "shipped dataset reports, because those are the findings that stay loud. Was: "
							+ found);
		}
	}

	/** The shipped dataset of {@code format}: the feature on, nothing else configured, so the classpath
	 *  fallback is what the real load resolves to. */
	private DrugReferenceLoad shippedLoadOf(String format) {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT, format);
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, "");
		return new DrugReferenceService().getLoadStatus();
	}
}
