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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.util.OpenmrsUtil;

/**
 * The one set of drug-reference test helpers, shared by the reference test classes (and, via
 * the public accessors, the grounding and inference tests in {@code api.impl}) so the
 * arrangement/matcher bodies cannot drift between files (the same rule CLAUDE.md states for
 * {@code TestDatasetHelper}). Everything here constructs REAL production objects and calls
 * real production paths — no mocks, no pipeline reimplementation; the individual test files
 * keep thin, file-shaped delegates so their call sites read naturally.
 */
public final class DrugReferenceTestSupport {

	/**
	 * The real rendered text of the drug-reference record the REAL injector injects for
	 * {@code question} (bundled DDInter sample, real load → parse → injectRecords → render
	 * chain). The one cross-package accessor for tests that need genuine injected record text
	 * without reimplementing the renderer.
	 */
	public static String injectedDdinterReferenceText(String question) {
		return injectedReference(injectedDdinterChart(question)).getText();
	}

	/** The one arrangement behind both public DDInter accessors, so they cannot drift apart. */
	private static PatientChart injectedDdinterChart(String question) {
		return injector(ddinterService()).injectRecords(oneRecordChart(),
				ctx(60, null, null, null, null, null), question);
	}

	/**
	 * The injected drug-reference record's mapping in {@code chart} — the mapping rather than only
	 * its text, because it is the carrier of the citation metadata (source, withheld count) that is
	 * deliberately absent from the record text (issue #117).
	 *
	 * <p>The one matcher for "the injected reference", so the reference-shaped filter cannot drift
	 * between the test files that need it. {@code DrugReferenceInjectorTest.referenceMappingFor} is
	 * deliberately separate: it selects by the drug the rendering names, which is a different
	 * question once more than one entry is injected — the loose, mapping-returning form of
	 * {@link #namesDrug} below.
	 */
	static RecordMapping injectedReference(PatientChart chart) {
		return injectedReferences(chart).stream().findFirst().orElseThrow(() -> new IllegalStateException(
				"no drug-reference record was injected into the chart: " + chart.getText()));
	}

	/**
	 * Every injected {@code drug_reference} mapping in {@code chart}, in injection order — the
	 * reference-shaped counterpart of {@link #injectedFindings}, and the one matcher for it, so the
	 * filter cannot drift between the test files that assert HOW MANY records one question injects
	 * (issue #163: the prompt-budget cost is a count and a character total, and neither is visible
	 * from {@link #injectedReference}'s first-only view).
	 */
	static List<RecordMapping> injectedReferences(PatientChart chart) {
		return chart.getMappings().stream()
				.filter(m -> ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(m.getResourceType()))
				.collect(Collectors.toList());
	}

	/**
	 * The injected {@code drug_reference} records' full rendered text, in injection order — the
	 * text-shaped view of {@link #injectedReferences}, owned here for the same reason that filter is:
	 * two files had grown their own copy of it.
	 *
	 * <p>Whole texts rather than extracted names, deliberately. A rendered record is
	 * {@code "Drug reference — <name>"} followed by {@code " (<class>; ATC …)"} only when the entry
	 * publishes one of those, then a full stop — so a name-extraction rule that splits on {@code " ("}
	 * both loses an unclassified entry's name entirely AND merges a bare name with a route-qualified
	 * sibling of it ({@code Iron} with {@code Iron (bisglycinate)}). Comparing whole texts has neither
	 * problem and is the stricter assertion.
	 */
	static List<String> referenceTexts(PatientChart chart) {
		List<String> out = new ArrayList<String>();
		for (RecordMapping mapping : injectedReferences(chart)) {
			out.add(mapping.getText());
		}
		return out;
	}

	/**
	 * @return whether one of {@code texts} is the record rendered for the entry NAMED {@code name} —
	 *         the strict counterpart of {@code DrugReferenceInjectorTest.referenceMappingFor}, which
	 *         asks the looser {@code getText().contains(drug)} and answers with the mapping.
	 *
	 *         <p>BOTH terminators have to be accepted, and that is the whole reason this rule lives in
	 *         one place: an entry the loaded dataset classifies nowhere renders as
	 *         {@code "Drug reference — Iron."} with no parenthesis at all, so a check written against
	 *         {@code " ("} alone is blind to exactly the entries an ATC-keyed candidate set could never
	 *         have contained (found by mutation while hardening issue #151 — the assertion stayed green
	 *         with the gate deliberately broken).
	 *
	 *         <p>Residual bound: it cannot tell a bare name from a route-qualified sibling, because the
	 *         qualifier and the class parenthesis open with the same two characters. Pin the record
	 *         COUNT beside it wherever an ABSENCE is the claim.
	 */
	static boolean namesDrug(List<String> texts, String name) {
		for (String text : texts) {
			if (text.startsWith("Drug reference — " + name + " (")
					|| text.startsWith("Drug reference — " + name + ".")) {
				return true;
			}
		}
		return false;
	}


	/**
	 * Copies a dataset from the test classpath into {@code <appdata>/chartsearchai/<asName>} — the
	 * arrangement every context-sensitive case needs to drive the OPERATOR-FILE branch of the real load,
	 * as against the classpath fallback.
	 *
	 * <p>One body for what had become three, and they had already drifted: two stripped a leading slash
	 * from the resource name and one did not, so handing a source class's own {@code CLASSPATH_DEFAULT}
	 * (which carries the slash) to that one produced a null stream and an assertion message naming the
	 * resource but not the reason. Shared for the reason this class exists.
	 *
	 * @param created the caller's cleanup list, which the copied file is added to — the deletion is
	 *        per-test {@code @AfterEach} work and this helper has no lifecycle of its own
	 * @return the value to set {@code dataFilePath} (or the groups path) to: relative to the application
	 *         data directory, which is the form the global property holds
	 */
	static String copyDatasetToAppData(String classpathResource, String asName, List<File> created)
			throws IOException {
		File dir = new File(OpenmrsUtil.getApplicationDataDirectory(), "chartsearchai");
		dir.mkdirs();
		File target = new File(dir, asName);
		created.add(target);
		String resource = classpathResource.startsWith("/") ? classpathResource.substring(1)
				: classpathResource;
		try (InputStream in = DrugReferenceTestSupport.class.getClassLoader()
				.getResourceAsStream(resource)) {
			assertNotNull(in, "dataset " + classpathResource + " should be on the test classpath");
			Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		return "chartsearchai/" + asName;
	}

	/**
	 * The raw text of a test-classpath dataset, for the assertions that have to read the FILE rather
	 * than the parsed model — a row the parser is expected to drop is invisible in its output, so the
	 * only way to show the fixture really carries it is to read the resource the parser reads.
	 */
	static String fixtureText(String classpathResource) throws IOException {
		try (InputStream in = DrugReferenceTestSupport.class.getClassLoader()
				.getResourceAsStream(classpathResource)) {
			assertNotNull(in, classpathResource + " should be on the test classpath");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) >= 0) {
				out.write(buffer, 0, read);
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * The real record mappings the REAL injector produces for {@code question} (bundled DDInter
	 * sample, real load → parse → injectRecords chain), for tests outside this package that need
	 * genuine injected mappings — including their citation metadata (source, withheld count) —
	 * rather than hand-built stand-ins.
	 */
	public static List<RecordMapping> injectedDdinterMappings(String question) {
		return injectedDdinterChart(question).getMappings();
	}

	/**
	 * The real rendered text of the active-order record the REAL injector injects for an active
	 * order the chart cannot substantiate (issue #118) — the real reconciliation → render chain,
	 * not a hand-assembled imitation of the format. The second cross-package accessor, for the
	 * grounding tests: how this record text embeds against an answer sentence is exactly what
	 * decides whether treating it as ordinary chart evidence is right, so a test asserting that
	 * must read the text production actually produces.
	 */
	public static String injectedActiveOrderText(String orderUuid, String display) {
		PatientChart chart = injector(ddinterService()).injectRecords(oneRecordChart(),
				ctx(60, null, null, null, null, null,
						Collections.singletonList(activeOrder(orderUuid, display))),
				"what are the patient's active medications?");
		return chart.getMappings().stream()
				.filter(m -> ChartSearchAiConstants.RESOURCE_TYPE_ACTIVE_DRUG_ORDER.equals(m.getResourceType()))
				.map(RecordMapping::getText).findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"no active-order record was injected for order: " + display));
	}

	/**
	 * The real safety-finding record the REAL pipeline injects for {@code question} asked about a
	 * patient on {@code activeDrug} (with ATC code {@code atcCode}) — the whole production chain,
	 * bundled DDInter sample through {@code DrugSafetyValidator.validate} and
	 * {@code injectRecords}/{@code renderFinding}, with the real validator behind the real injector
	 * (through the same {@code set*} seams the other helpers here use, in place of production's
	 * autowiring). The third cross-package accessor, for the grounding tests.
	 *
	 * <p>Returns the {@link RecordMapping} rather than only its text because a grounding test needs
	 * the resource type and the citation index too, and because the argument for treating this record
	 * as module-supplied material is an argument about what THIS prose does under a cosine pass
	 * (issue #122) — a hand-assembled imitation of the finding line would not be testing it.
	 *
	 * @throws IllegalStateException when the question raises no deterministic finding, so a test
	 *         cannot silently assert nothing
	 */
	public static RecordMapping injectedSafetyFinding(String question, String activeDrug, String atcCode) {
		PatientChart chart = injectedSafetyFindingChart(question, activeDrug, atcCode);
		return injectedFindings(chart).stream().findFirst().orElseThrow(() -> new IllegalStateException(
				"no safety-finding record was injected for question: " + question));
	}

	/**
	 * The whole chart {@link #injectedSafetyFinding} reads its record out of — the one arrangement
	 * behind both, so the finding's citation index means the same thing in a test that takes the
	 * record and a test that takes the chart it sits in.
	 *
	 * <p>For the inference tests rather than the grounding ones: they need what production hands the model rather than one record of it,
	 * because the class-code fidelity check (issue #142) compares an answer against EVERY cited
	 * record, and a test served only the finding could not fail if the check ignored the rest of the
	 * chart. Pair it with {@link #safetyFindingIn} rather than with {@link #injectedSafetyFinding},
	 * which builds its own chart: two runs of one arrangement agree, but only the pair gives the
	 * test a record that IS an element of the chart it serves.
	 */
	public static PatientChart injectedSafetyFindingChart(String question, String activeDrug,
			String atcCode) {
		DrugReferenceService service = ddinterServiceWithGroups();
		return injectorWithSafety(service).injectRecords(oneRecordChart(),
				ctx(60, null, set(activeDrug), set(atcCode), null, null), question);
	}

	/**
	 * The first injected {@code safety_finding} in a chart a caller already holds — {@link
	 * #injectedFindings}' single-record form, public for the same cross-package reason
	 * {@link #injectedSafetyFindingChart} is: a test that serves a chart and cites a record out of
	 * it needs the record to BE an element of that chart, not an equal one from a second run.
	 */
	public static RecordMapping safetyFindingIn(PatientChart chart) {
		return injectedFindings(chart).stream().findFirst().orElseThrow(() -> new IllegalStateException(
				"no safety-finding record in the chart: " + chart.getText()));
	}

	/**
	 * Every injected {@code safety_finding} mapping in {@code chart}, in injection order — the
	 * finding-shaped counterpart of {@link #injectedReference}, and the one matcher for it, so the
	 * filter cannot drift between the test files that assert HOW MANY records a chip yields. Returns
	 * the list rather than the first, because that count is the assertion in every caller but
	 * {@link #injectedSafetyFinding}, which layers its own throw-on-empty contract on top.
	 */
	static List<RecordMapping> injectedFindings(PatientChart chart) {
		return chart.getMappings().stream()
				.filter(m -> ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(m.getResourceType()))
				.collect(Collectors.toList());
	}

	/** The real WHO ATC sample fixture (parsed by the real {@link AtcDrugReferenceSource#parse}). */
	static final String ATC_SAMPLE = "atc/atc-sample.tsv";

	/**
	 * The logger name everything the drug-reference load logs sits under, for the tests that assert
	 * the LEVEL an outcome is reported at (issue #149). Owned here for the same reason
	 * {@link #ATC_SAMPLE} is, and with a sharper consequence: renaming the package leaves a stale
	 * string literal that no refactor touches, the capture then receives nothing, and every
	 * "no WARN was logged" assertion passes VACUOUSLY.
	 */
	static final String REFERENCE_LOGGER = "org.openmrs.module.chartsearchai.reference";

	/**
	 * DDInter fixture paths used by MORE THAN ONE test file, owned here for the same reason
	 * {@link #ATC_SAMPLE} is: a fixture that moves or is renamed must break in one place, naming
	 * itself, rather than break in one file and silently keep passing in another. Single-file
	 * fixtures keep their constant in the file that uses them.
	 *
	 * <p>{@code ddi-route-variants.json}: one substance filed as several rows sharing an
	 * {@code rxnorm_name} — the shape behind issue #115's chip collapse, and (its two ATC-less
	 * {@code Iron} rows) behind issue #135's multi-entry case. Its rows are field-for-field identical
	 * to their KB rows but NOT in KB order: the two Iron rows are transposed, which the #135 case
	 * depends on — see
	 * {@code DirectAllergyContraindicationTest.anUnclassifiedAllergenWithASiblingRouteVariantStillWarnsOnce}
	 * before regenerating this slice.
	 */
	static final String DDI_ROUTE_VARIANTS = "chartsearchai-test/ddi-route-variants.json";

	/** {@code Ledipasvir} and {@code Leucovorin}, two of the DDInter entries carrying no ATC code at all,
	 *  plus {@code Ciprofloxacin}/{@code Levofloxacin} as a real classified pair — issue #135's slice.
	 *  Shared with {@code RecordedAllergenMemoScopeTest}, which needs only that it names no drug
	 *  {@link #DDI_ROUTE_VARIANTS} names. */
	static final String DDI_UNCLASSIFIED_ALLERGEN = "chartsearchai-test/ddi-unclassified-allergen.json";

	/** Several route variants of one drug sharing a RxCUI — the id/label collision slice. */
	static final String DDI_RXCUI_COLLISION = "chartsearchai-test/ddi-rxcui-collision.json";

	/** The botulinum pair, the enalapril/enalaprilat pair and the typhoid pair — the slices where two rows
	 *  are or are not one substance (issues #164/#176/#187). */
	static final String DDI_SUBSTANCE_IDENTITY = "chartsearchai-test/ddi-substance-identity.json";

	/** The two PPIs filed under one substance name plus the four hydrocortisone rows — the
	 *  contraindication arm's route-variant and must-not-collapse slice. */
	static final String DDI_CONTRA_ROUTE_VARIANTS = "chartsearchai-test/ddi-contra-route-variants.json";

	/** Combination-product names and their constituents — the co-trimoxazole and omeprazole/bicarbonate
	 *  rows whose sulfa and PPI moieties a one-substance resolution never reaches, plus the
	 *  abacavir/lamivudine pair that claims one name equally (issue #193). */
	static final String DDI_COMBINATION_ALLERGEN = "chartsearchai-test/ddi-combination-allergen.json";

	/** Eleven verbatim rows whose ALIAS SETS carry the shapes issues #136/#147 turn on — the entry named
	 *  {@code Acetylsalicylic acid} whose every rule token is {@code aspirin} — plus the nesting pairs
	 *  issue #86 removed ({@code opium} inside {@code tiotropium}). See the fixture's own
	 *  {@code metadata.note}, which is the authority on what it carries. */
	static final String DDI_ALIAS_DRUG_NAMES = "chartsearchai-test/ddi-alias-drug-names.json";

	/** Presentations filed as their own substance beside the parent moiety they contain, with the
	 *  sibling pairs that share a display stem and must NOT be merged with them (issue #195). */
	static final String DDI_PRESENTATION_MOIETY = "chartsearchai-test/ddi-presentation-moiety.json";

	/**
	 * Issue #242's pair: three drug rows without an {@code interactions} table, and the same three rows
	 * with one declared empty. The only fixture here that is DELIBERATELY mis-shaped and the only one
	 * that must not be "fixed" — it is the subject of the rule rather than a setting for one.
	 *
	 * <p>Owned here for a reason sharper than the shared-fixture rule above. The corpus sweep in
	 * {@code DrugReferenceValidityContextTest} exempts exactly one file by NAME, and that exemption has
	 * to denote the same file the rule cases load; spelled twice, the two are under no compiler
	 * obligation to agree, and the sweep's one hole could drift onto a healthy fixture while the rule
	 * case still passed. So the sweep derives both its directory and its exempt name from this constant.
	 */
	static final String DDI_NO_INTERACTIONS_TABLE = "chartsearchai-test/ddi-no-interactions-table.json";

	/** The well-shaped twin of {@link #DDI_NO_INTERACTIONS_TABLE} — same three drug rows, plus an empty
	 *  {@code interactions} table. Also the curated parser's "handed a document of another format"
	 *  slice, which is why it is shared rather than local. */
	static final String DDI_EMPTY_INTERACTIONS_TABLE =
			"chartsearchai-test/ddi-empty-interactions-table.json";

	/**
	 * The 16-drug DDInter excerpt behind {@link #ddinterService} — 16 substances, 60 mechanisms, 120
	 * interaction rows of real DDInter data, and the module's bundled default until ADR Decision 36
	 * replaced it with the whole knowledge base (which is why the file's own {@code metadata.note} still
	 * reads as a bundled default: it is kept byte-identical to what shipped, so the cases that pin
	 * rendered text are pinned to data that has not changed). Unlike its siblings above it is not a slice
	 * built to pose one shape — it is a general-purpose bounded dataset, which is exactly what a case
	 * asserting "these partners, in this order" needs. {@link #ddinterService} says why the shipped
	 * default cannot serve that purpose.
	 */
	static final String DDI_EXCERPT = "chartsearchai-test/ddi-knowledge-base-sample.json";

	/**
	 * Issues #152/#164's corpus: six drug rows carrying three interaction rows that pair a substance with
	 * itself, which {@link DdiDrugReferenceSource#isSelfPair} drops and
	 * {@link DrugReferenceValidity#SELF_PAIRED_INTERACTION_ROWS} counts. Shared rather than spelled in each
	 * file that wants it, for the reason {@code DrugReferenceValidityContextTest.FIXTURE_DIR} gives about
	 * its own pair: two literals naming one fixture are under no compiler obligation to agree, and here one
	 * of the two dependants pins the dropped COUNT.
	 */
	static final String DDI_SELF_INTERACTION = "chartsearchai-test/ddi-self-interaction.json";

	/**
	 * Issue #196's corpus: DDInter-shaped rows where one entry publishes, among its own names, a name a
	 * different substance is called — the rule the shipped knowledge base trips on 18 of its rows, which is
	 * why two files want this same slice (the rule's own case, and the one that shows the same rule reported
	 * at two different LEVELS depending on whose dataset it is).
	 */
	static final String DDI_ALIAS_NAMES_ANOTHER_SUBSTANCE =
			"chartsearchai-test/ddi-alias-names-another-substance.json";

	/** Three nitroimidazoles, curated ({@link JsonDrugReferenceSource}) rather than DDInter-shaped: the
	 *  class arm's co-medication GROUPING slice, where one order's codes are covered only in part
	 *  (issue #186) and the same order shapes are asked of the name rung (issue #228). */
	static final String PARTIAL_ORDER_COVERAGE =
			"chartsearchai-test/drug-reference-partial-order-coverage.json";

	/**
	 * The canonical interaction-screening question, verbatim from issue #113 — it names no drug, which
	 * is what leaves the active-order screening arm as the only arm that can chip, so an assertion
	 * about that arm cannot be satisfied by a question-driven one.
	 *
	 * <p>Owned here for the same reason {@link #REFERENCE_LOGGER} is, and for the consequence issue #153
	 * names: it was copy-pasted into ten test files (six when the issue was filed), and which arm it
	 * reaches is decided by
	 * {@link org.openmrs.module.chartsearchai.api.impl.QueryScopeRouter#isInteractionScreening}, so an
	 * edit to ONE copy could move that file onto a different arm while the rest kept passing.
	 *
	 * <p>How big that risk actually was, measured on {@code ae09928} rather than asserted. Nine of the
	 * ten copies were reworded one file at a time to "any medication conflicts with her current
	 * medications?" — a paraphrase the classifier deliberately does not match, since "conflict" carries
	 * an everyday non-drug sense — and that file's suite re-run. <b>Eight of the nine went red;
	 * {@code DuplicateInteractionChipTest} stayed GREEN</b>, because its assertion is satisfied by a chip
	 * the drug-in-play arm also raises. ({@code ActiveOrderAtcContextTest}, the tenth, was not measured —
	 * it is context-sensitive and slow.) So the divergence the issue names is real but narrow, and what
	 * removes it is not an assertion: it is that there is no longer a per-file copy to edit. The
	 * screening-classification case in {@code DrugSafetyInteractionScreeningTest} covers the remaining
	 * direction — a change to the classifier itself, which moves all ten at once — and makes that failure
	 * name its cause in one place rather than arriving as eight files' worth of chip-count mismatches.
	 *
	 * <p>The pronoun is NOT load-bearing and one string therefore serves every patient's test: the
	 * classifier reads an {@code interact*} cue and its own MEDICATIONS classification, neither of
	 * which is gendered. Two constants differing only in pronoun would re-create exactly the
	 * divergence this one removes.
	 *
	 * <p>Deliberately NOT the only screening phrasing under test. {@code DrugSafetyInteractionScreening
	 * Test} also drives "Do any of her meds interact?" and {@code DrugSafetyDiacriticOrderNameTest}
	 * "Are there any interactions between her medications?" — those are separate phrasings covering the
	 * classifier's breadth, not copies of this one, and collapsing them into this constant would delete
	 * that coverage.
	 */
	static final String SCREENING_QUESTION = "Are there any drug interactions with her current medications?";

	private DrugReferenceTestSupport() {
	}

	static Set<String> set(String... values) {
		return new LinkedHashSet<String>(Arrays.asList(values));
	}

	/** Canonical context builder: any null set means empty; weight null means unknown. */
	static PatientClinicalContext ctx(Integer age, Double weightKg, Set<String> drugs, Set<String> atc,
			Set<String> allergies, Set<String> conditions) {
		return ctx(age, weightKg, drugs, atc, allergies, conditions, null);
	}

	/**
	 * As {@link #ctx}, but for a context whose allergy and condition reads FAILED — the shape
	 * {@link PatientClinicalContextBuilder} produces when {@code getAllergies} or
	 * {@code getActiveConditions} throws and it degrades that dimension to an empty set. The token sets
	 * are empty for exactly that reason, which is why they are not arguments: a caller cannot both have
	 * read the chart and not have read it.
	 */
	static PatientClinicalContext unreadableRecordsCtx(Integer age, Double weightKg) {
		return new PatientClinicalContext(age, weightKg, Collections.<String> emptySet(),
				Collections.<String> emptySet(), Collections.<String> emptySet(),
				Collections.<String> emptySet(), null, null, false);
	}

	/** As {@link #ctx}, additionally carrying the identified active drug orders the
	 *  chart/service reconciliation reads (null means none). */
	static PatientClinicalContext ctx(Integer age, Double weightKg, Set<String> drugs, Set<String> atc,
			Set<String> allergies, Set<String> conditions,
			List<PatientClinicalContext.ActiveDrugOrder> orders) {
		return new PatientClinicalContext(age, weightKg,
				drugs == null ? Collections.<String> emptySet() : drugs,
				atc == null ? Collections.<String> emptySet() : atc,
				allergies == null ? Collections.<String> emptySet() : allergies,
				conditions == null ? Collections.<String> emptySet() : conditions,
				orders);
	}

	/** One active drug order whose concept carries no ATC map — the majority shape, and what
	 *  {@link PatientClinicalContextBuilder} builds for such an order: the Order uuid, the display name,
	 *  and the names that identify it in record text (drug and/or concept name). For a mapped concept
	 *  the builder also attaches the order's own codes — use the four-argument overload below. */
	static PatientClinicalContext.ActiveDrugOrder activeOrder(String uuid, String display, String... names) {
		Set<String> all = new LinkedHashSet<String>();
		all.add(display);
		Collections.addAll(all, names);
		return new PatientClinicalContext.ActiveDrugOrder(uuid, display, all);
	}

	/** As {@link #activeOrder}, additionally carrying the ATC codes the order's own concept maps to —
	 *  the association {@link PatientClinicalContextBuilder} keeps so a code can be attributed to the
	 *  order it came from (issue #132). Names are an explicit set rather than varargs so that a code
	 *  set can follow them unambiguously, and so a test can give an order NO names at all — the shape
	 *  of an order the dataset knows only by its code. */
	static PatientClinicalContext.ActiveDrugOrder activeOrder(String uuid, String display,
			Set<String> names, Set<String> atcCodes) {
		return new PatientClinicalContext.ActiveDrugOrder(uuid, display, names, atcCodes);
	}

	/**
	 * One active drug order for the named entry of {@code service}, carrying the ATC codes that entry
	 * publishes, read off the loaded dataset through the production accessor rather than copied into
	 * the case so the two cannot come to disagree when a fixture is edited.
	 *
	 * <p><b>Which leg of the duplicate-therapy arm this reaches, which is not the obvious one.</b> The
	 * canonical {@link #ctx} overload does not union an order's codes into
	 * {@code PatientClinicalContext.getActiveDrugAtcCodes()}, so {@code orderPartners}' walk over those
	 * codes finds none and the partner is resolved by {@code addPartnersForUnmappedOrders} — issue
	 * #228's by-NAME leg, which sets the partner's codes from the reference ROW it resolves and
	 * discards the order's own. Chips are byte-identical either way (measured over all five call
	 * sites), so this is a statement about what is being exercised, not about the answer: a case that
	 * needs the MAPPED-concept leg must also pass those codes as {@code ctx}'s {@code atc} argument.
	 */
	static PatientClinicalContext.ActiveDrugOrder activeOrderFor(DrugReferenceService service,
			String name) {
		DrugReference entry = service.lookupByToken(name);
		assertNotNull(entry, name + " must resolve before it can be an active order");
		return activeOrder("order-" + name, name, set(name), entry.normalizedAtcCodes());
	}

	/**
	 * The opening words of the two sentences a record adds when a substance is filed as several rows —
	 * the row-attribution clause (issue #237) and the other-rows dosing section (issue #259).
	 *
	 * <p>Defined ONCE for every file that asserts on them, positive expectations and silence guards
	 * alike, because a copy per file is how the two come apart: a wording change reddens the file holding
	 * the positive expectation while an {@code assertFalse} in the next file goes on passing against a
	 * string production no longer emits. That is not hypothetical — {@code
	 * ReferenceRecordRowAttributionTest.ATTRIBUTION_LEAD}'s own note records seven such guards having
	 * shipped green, and its constant fixed that within one file while leaving a second copy free to
	 * appear. It did.
	 */
	static final String ROW_ATTRIBUTION_LEAD = "Published by this dataset for";

	/** @see #ROW_ATTRIBUTION_LEAD */
	static final String OTHER_ROW_DOSING_LEAD = "Also published for other rows of this substance: ";

	/**
	 * A context whose ACTIVE ORDERS carry {@code displays}, resolved through the same production
	 * reconciliation the injector and the validator each perform on it
	 * ({@link DrugReferenceService#withReferenceNames} over
	 * {@link DrugReferenceService#findForActiveOrders}) — so a case that drives both surfaces drives them
	 * over ONE context rather than over two that could disagree about what the chart records.
	 *
	 * <p>Shared rather than written per test file: which row a response NAMES a substance by is ranked
	 * off these display names (issues #187, #194, #206), so every case about a record and a chip naming
	 * one substance depends on this being built one way.
	 */
	static PatientClinicalContext contextNaming(DrugReferenceService service, Integer age,
			Double weightKg, String... displays) {
		List<PatientClinicalContext.ActiveDrugOrder> orders =
				new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		Set<String> names = new LinkedHashSet<String>();
		for (String display : displays) {
			orders.add(activeOrder("order-" + display, display, display));
			names.add(display);
		}
		PatientClinicalContext raw = ctx(age, weightKg, names, null, null, null, orders);
		return service.withReferenceNames(raw, service.findForActiveOrders(raw));
	}

	/**
	 * A service over the bundled CURATED dataset — the real {@link DrugReferenceService} load path
	 * (so the load-time validity repairs run and the real curated cross-reactivity groups are loaded,
	 * unlike the {@code setEntries} seam behind {@link #serviceWith}), with the adapter pinned through
	 * the {@code setSource} seam instead of the format global property, which a non-context test cannot
	 * set.
	 *
	 * <p>Pinned rather than left to the default because the default MOVED: since ADR Decision 36 it is
	 * the DDInter knowledge base, which publishes no age band and no hand-authored allergy/condition
	 * rule, and every case behind this accessor is about exactly those — a dose ceiling, a curated
	 * contraindication note, a self-naming allergy rule. Named for the dataset it wants for the same
	 * reason {@code DRUG_REFERENCE_SOURCE_JSON} exists: "the bundled one" and "the default one" were the
	 * same dataset and are not the same fact.
	 *
	 * <p>{@link DrugReferenceService#getLoadStatus()} on the returned service describes the format the
	 * GP selects rather than the injected adapter (the seam says so), so no case here may assert it.
	 */
	static DrugReferenceService curatedService() {
		DrugReferenceService service = new DrugReferenceService();
		service.setSource(new JsonDrugReferenceSource());
		return service;
	}

	/**
	 * A service over the 16-drug DDInter EXCERPT, parsed by the real {@link DdiDrugReferenceSource}.
	 * Cross-reactivity groups are pinned EMPTY by the {@code setEntries} seam underneath — use
	 * {@link #ddinterServiceWithGroups} when a case depends on a curated group.
	 *
	 * <p>The excerpt is a pinned fixture and no longer the module's bundled default: since ADR
	 * Decision 36 the bundled dataset is the WHOLE knowledge base, and the cases behind this accessor
	 * need a dataset whose partner lists they can state — "this record renders exactly these partners",
	 * "this entry has one partner", "13 were withheld". Against 2283 substances and 590,312 links those
	 * premises are all false (lisinopril alone has 730 partners), so pointing them at the shipped default
	 * would test the prompt budget's truncation rather than the behaviour each case is about. The excerpt
	 * is still real DDInter data read by the real parser; only its size is chosen.
	 * {@link ShippedDrugReferenceDefaultTest} and {@link DdiDrugReferenceSourceTest} are what cover the
	 * shipped default itself.
	 */
	static DrugReferenceService ddinterService() {
		return serviceWith(ddinterEntries());
	}

	/**
	 * The excerpt's entries, parsed by the real {@link DdiDrugReferenceSource} — what
	 * {@link #ddinterService} is built over, exposed so a case that states a PREMISE about one row
	 * ("warfarin's ibuprofen interaction is Major") reads it from the same dataset the validator under
	 * test is using. Straddling two is how a premise comes to describe data the assertion never sees,
	 * which is what happened when the bundled default stopped being this excerpt.
	 */
	static List<DrugReference> ddinterEntries() {
		try {
			return ddiFixtureEntries(DDI_EXCERPT);
		}
		catch (IOException e) {
			throw new IllegalStateException("the pinned DDInter excerpt " + DDI_EXCERPT
					+ " must be readable from the test classpath", e);
		}
	}

	/**
	 * As {@link #ddinterService}, carrying the real curated cross-reactivity groups — the excerpt
	 * counterpart of {@link #ddiFixtureService}, and here for the same reason that one is: the two steps
	 * have to stay together, because {@link #serviceWith} pins the groups EMPTY through its
	 * {@code setEntries} seam and a service built without the second call silently cannot raise a
	 * curated-group chip or admit a group-related active order. Silently: nothing fails, the case just
	 * stops testing what it says it tests.
	 */
	static DrugReferenceService ddinterServiceWithGroups() {
		DrugReferenceService service = ddinterService();
		service.setCrossReactivityGroups(bundledGroups());
		return service;
	}

	/** A service pinned to the given entries (groups pinned empty by the {@code setEntries} seam). */
	static DrugReferenceService serviceWith(List<DrugReference> entries) {
		DrugReferenceService svc = new DrugReferenceService();
		svc.setEntries(entries);
		return svc;
	}

	/**
	 * A service over the real WHO ATC sample (parsed by the real source), optionally with the
	 * real bundled cross-reactivity groups; without them the dataset is hermetically
	 * classification-only (what the ADR-24 boundary tests assert).
	 */
	static DrugReferenceService atcService(boolean withGroups) throws IOException {
		DrugReferenceService svc = new DrugReferenceService();
		try (InputStream in = DrugReferenceTestSupport.class.getClassLoader().getResourceAsStream(ATC_SAMPLE)) {
			assertNotNull(in, "ATC sample resource should be on the test classpath");
			svc.setEntries(AtcDrugReferenceSource.parse(in));
		}
		if (withGroups) {
			svc.setCrossReactivityGroups(bundledGroups());
		}
		return svc;
	}

	/** The real bundled cross-reactivity groups via the production loader (classpath fallback). */
	static List<CrossReactivityGroup> bundledGroups() {
		return new CrossReactivityGroupsLoader().load();
	}

	/** Entries parsed from a test-classpath dataset by the real production parser. */
	static List<DrugReference> fixtureEntries(String classpathResource) throws IOException {
		try (InputStream in = DrugReferenceTestSupport.class.getClassLoader()
				.getResourceAsStream(classpathResource)) {
			assertNotNull(in, classpathResource + " should be on the test classpath");
			return JsonDrugReferenceSource.parse(in);
		}
	}

	/**
	 * Entries parsed from a DDInter-shaped test-classpath dataset by the real
	 * {@link DdiDrugReferenceSource#parse} — the DDInter counterpart of {@link #fixtureEntries},
	 * which is bound to {@link JsonDrugReferenceSource#parse}, a different parser over a different
	 * schema. Named for its parser rather than sharing that name for exactly that reason.
	 *
	 * <p>The one place the missing-resource guard lives, which is why it is shared rather than
	 * re-opened per test file: three of the hand-rolled copies this replaced fed
	 * {@code getResourceAsStream}'s result straight into the parser, so a fixture absent from the
	 * test classpath failed with Jackson's {@code IllegalArgumentException: argument "in" is null}
	 * — a message that names neither the resource nor the test that wanted it.
	 */
	static List<DrugReference> ddiFixtureEntries(String classpathResource) throws IOException {
		try (InputStream in = DrugReferenceTestSupport.class.getClassLoader()
				.getResourceAsStream(classpathResource)) {
			assertNotNull(in, classpathResource + " should be on the test classpath");
			return DdiDrugReferenceSource.parse(in);
		}
	}

	/**
	 * A service over a DDInter-shaped test fixture, carrying the real curated cross-reactivity groups —
	 * so the class comparisons run against the shipped curated data rather than groups a test pinned
	 * empty. The arrangement lives here rather than in each test file because the two steps have to stay
	 * together: {@link #serviceWith} pins the groups EMPTY through its {@code setEntries} seam, so a
	 * fixture service built without the second call silently cannot raise a curated-group chip.
	 */
	static DrugReferenceService ddiFixtureService(String classpathResource) throws IOException {
		DrugReferenceService service = serviceWith(ddiFixtureEntries(classpathResource));
		service.setCrossReactivityGroups(bundledGroups());
		return service;
	}

	/**
	 * @return the parsed entry whose own {@code name} is {@code name}, failing with the whole slice's
	 *         names when it is absent — the "reach into a fixture slice for one row" arrangement, which
	 *         is fixture-independent and so belongs here rather than being re-opened per file (the same
	 *         rule CLAUDE.md states for {@code TestDatasetHelper}). Selecting by {@code getName()} and
	 *         not through a resolver on purpose: every caller is stating a PREMISE about which row the
	 *         slice carries, and resolving it would make the premise depend on the very ranking the
	 *         case is about.
	 */
	static DrugReference row(List<DrugReference> entries, String name) {
		for (DrugReference entry : entries) {
			if (name.equals(entry.getName())) {
				return entry;
			}
		}
		throw new AssertionError("the fixture must carry the " + name + " row, was: " + names(entries));
	}

	/** @return the chips' {@code detail} sentences, for asserting membership or the whole set exactly.
	 *          The counterpart of {@link #detailContains} for the cases that compare a WHOLE detail
	 *          rather than a substring, which is the stricter of the two. */
	static List<String> details(List<SafetyWarning> warnings) {
		List<String> out = new ArrayList<String>();
		for (SafetyWarning warning : warnings) {
			out.add(warning.getDetail());
		}
		return out;
	}

	/**
	 * @return the {@code detail} sentences of the CONTRAINDICATION chips alone — the counterpart of
	 *         {@link #classChipDetails} for the other chip type a rule-driven dataset raises, and here
	 *         for the same reason: two other files had already grown their own copy of this three-line
	 *         filter ({@code AllergenNameResolutionTest}, {@code ActiveOrderContraindicationTest}), and a
	 *         shared one cannot drift into two answers about which chips a case is counting. Those two
	 *         are deliberately not migrated here — they are outside this change — so the drift they can
	 *         still make is theirs, not this helper's.
	 */
	static List<String> contraindicationDetails(List<SafetyWarning> warnings) {
		List<String> out = new ArrayList<String>();
		for (SafetyWarning warning : warnings) {
			if (SafetyWarning.TYPE_CONTRAINDICATION.equals(warning.getType())) {
				out.add(warning.getDetail());
			}
		}
		return out;
	}

	/**
	 * @return the injected drug-reference record rendered for the entry NAMED {@code name}, or null when
	 *         no record names it — the text-returning form of {@link #namesDrug}, sharing its terminator
	 *         rule rather than restating it. A selector written as a bare {@code startsWith(name)} also
	 *         accepts a route-qualified SIBLING, which is the whole reason that rule lives in one place.
	 */
	static String referenceTextNaming(PatientChart chart, String name) {
		for (String text : referenceTexts(chart)) {
			if (namesDrug(Collections.singletonList(text), name)) {
				return text;
			}
		}
		return null;
	}

	/**
	 * @return the {@code detail} sentences of the INTERACTION chips alone — which over a rule-less
	 *         dataset is the class arm's output, so a count of them is a count of co-medications that
	 *         arm decided about.
	 *
	 *         <p>Here rather than in each file for the reason {@link #row} records: it was copied
	 *         verbatim between two of them, javadoc included, and a shared filter cannot drift into two
	 *         answers about which chips a case is counting.
	 */
	static List<String> classChipDetails(List<SafetyWarning> warnings) {
		List<String> out = new ArrayList<String>();
		for (SafetyWarning warning : warnings) {
			if (SafetyWarning.TYPE_INTERACTION.equals(warning.getType())) {
				out.add(warning.getDetail());
			}
		}
		return out;
	}

	/** @return the entries' own {@code name}s. {@link DrugReference} defines no {@code toString}, so a
	 *          failure message built from the list itself prints identity hashes. */
	static List<String> names(List<DrugReference> entries) {
		List<String> out = new ArrayList<String>();
		for (DrugReference entry : entries) {
			out.add(entry.getName());
		}
		return out;
	}

	static DrugSafetyValidator validator(DrugReferenceService service) {
		DrugSafetyValidator validator = new DrugSafetyValidator();
		validator.setDrugReferenceService(service);
		return validator;
	}

	static DrugReferenceInjector injector(DrugReferenceService service) {
		DrugReferenceInjector injector = new DrugReferenceInjector();
		injector.setDrugReferenceService(service);
		return injector;
	}

	/** The real injector with the real validator behind it over ONE service — the arrangement
	 *  {@code preAnswerFindings} needs before a chip can become a citable safety-finding record, and the
	 *  one production wires by autowiring. Both halves must share the service: two services would PARSE
	 *  the dataset twice, so the injector and the validator would hold different DrugReference objects
	 *  for the same row and the safety arms' identity comparisons would miss. That is about two services,
	 *  NOT about a reload — there is none; see DrugReferenceService's class javadoc, which retires the
	 *  reload reading of this same sentence at nine other sites. */
	static DrugReferenceInjector injectorWithSafety(DrugReferenceService service) {
		DrugReferenceInjector injector = injector(service);
		injector.setDrugSafetyValidator(validator(service));
		return injector;
	}

	/** A one-record chart to inject into; the injected reference must append as record [2]. */
	static PatientChart oneRecordChart() {
		return chartOf(new RecordMapping(1, ChartSearchAiConstants.RESOURCE_TYPE_OBS,
				"obs-uuid-1", null, "BP 120/80"));
	}

	/** A chart of {@code records}, rendered as the numbered "[N] text" lines
	 *  {@link org.openmrs.module.chartsearchai.serializer.PatientChartSerializer} produces — so a
	 *  test can place a real drug-order record in the chart, or leave it out. */
	static PatientChart chartOf(RecordMapping... records) {
		StringBuilder text = new StringBuilder("Patient\n\n");
		for (RecordMapping record : records) {
			text.append("[").append(record.getIndex()).append("] ").append(record.getText()).append("\n");
		}
		return new PatientChart(text.toString(),
				Collections.unmodifiableList(new ArrayList<RecordMapping>(Arrays.asList(records))),
				Collections.<Integer> emptyList());
	}

	/** A querystore drug-order chart record: its resource type is querystore's {@code drug_order}
	 *  and its resourceUuid is the Order uuid (its {@code DrugOrderRecordSerializer} contract). */
	static RecordMapping drugOrderRecord(int index, String orderUuid, String drugText) {
		return new RecordMapping(index, "drug_order", orderUuid, null, "Drug order: " + drugText);
	}

	/** An obs chart record, for filling a chart with records that are not drug orders. */
	static RecordMapping obsRecord(int index, String text) {
		return new RecordMapping(index, ChartSearchAiConstants.RESOURCE_TYPE_OBS, "obs-uuid-" + index,
				null, text);
	}

	static boolean has(List<SafetyWarning> warnings, String type, String drugContains) {
		for (SafetyWarning w : warnings) {
			if (w.getType().equals(type) && w.getDrug().toLowerCase().contains(drugContains.toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	static boolean detailContains(List<SafetyWarning> warnings, String type, String drug, String... needles) {
		for (SafetyWarning w : warnings) {
			if (!w.getType().equals(type) || !w.getDrug().equalsIgnoreCase(drug)) {
				continue;
			}
			boolean all = true;
			for (String needle : needles) {
				if (!w.getDetail().toLowerCase().contains(needle.toLowerCase())) {
					all = false;
					break;
				}
			}
			if (all) {
				return true;
			}
		}
		return false;
	}

	static long overdoseCount(List<SafetyWarning> warnings, String drug) {
		return warnings.stream()
				.filter(w -> w.getType().equals(SafetyWarning.TYPE_OVERDOSE)
						&& w.getDrug().equalsIgnoreCase(drug))
				.count();
	}

	static String overdoseDetail(List<SafetyWarning> warnings, String drug) {
		return warnings.stream()
				.filter(w -> w.getType().equals(SafetyWarning.TYPE_OVERDOSE)
						&& w.getDrug().equalsIgnoreCase(drug))
				.map(SafetyWarning::getDetail).findFirst().orElse("");
	}
}
