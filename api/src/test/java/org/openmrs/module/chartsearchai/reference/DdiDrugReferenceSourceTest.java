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

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Exercises the real {@link DdiDrugReferenceSource} and its behaviour through the real injector
 * and validator (via {@link DrugReferenceTestSupport}). With no OpenMRS context available the
 * source falls back to the bundled {@code /chartsearchai/ddi-knowledge-base.json} sample — the
 * production default — so these run the real load/parse/inject/validate paths against real data.
 *
 * <p>The tests that need a KB slice the 60-mechanism bundled sample does not contain feed the real
 * {@link DdiDrugReferenceSource#parse} a fixture instead (see {@link #ddiFixtureEntries}); the
 * pipeline exercised is the same, only the dataset is narrowed.
 */
public class DdiDrugReferenceSourceTest {

	private static final String SEVERITY = "Major Moderate Minor Unknown";

	private static final String MARKER_FIXTURE = "chartsearchai-test/ddi-field-marker-mechanism.json";

	/** The real mechanism text of KB group 2248, verbatim minus the {@code INTERVAL:} marker. */
	private static final String DOLUTEGRAVIR_MECHANISM = "Coadministration with medications containing "
			+ "polyvalent cations such as aluminum, calcium, iron, or magnesium may decrease the oral "
			+ "bioavailability of dolutegravir. The mechanism of interaction has not been established.";

	/** The real mechanism text of KB group 222, verbatim minus the {@code RECOMMENDED:} marker. */
	private static final String TAZEMETOSTAT_MECHANISM = "Coadministration with tazemetostat may decrease "
			+ "the plasma concentrations and efficacy of hormonal contraceptives. The mechanism involves "
			+ "induction of CYP450 3A4, the isoenzyme primarily responsible for the metabolic clearance of "
			+ "sex hormones.";

	/**
	 * The real mechanism text of KB group 4945, verbatim and entire: it carries no marker, so the
	 * whole string — including its own colon-terminated opening clause — must reach the note.
	 */
	private static final String THEOPHYLLINE_MECHANISM = "Limited and controversial data suggest the "
			+ "following: either there is no significant interaction between theophylline and "
			+ "dihydropyridine calcium channel blockers, or theophylline serum levels increase after the "
			+ "addition of dihydropyridine calcium channel blockers. Data are available for nifedipine. "
			+ "Theophylline dosage should be reduced if necessary, and plasma levels should be checked "
			+ "when clinically necessary and appropriate. Patients should be advised to report any signs "
			+ "of theophylline toxicity including nausea, vomiting, diarrhea, headache, restlessness, "
			+ "insomnia, or irregular heartbeat to their physician.";

	/**
	 * Synthetic fixture group 9997: a seven-word ALL-CAPS run before its colon, one word past the
	 * pattern's bound. No real KB row supplies this shape (the longest real all-caps run is a single
	 * word), so it is the only way to observe the bound.
	 */
	private static final String BOUND_PROBE_MECHANISM = "PATIENTS SHOULD BE ADVISED TO REPORT SIGNS: "
			+ "coadministration may increase the risk of theophylline toxicity.";

	private DrugReference entry(String name) {
		return new DdiDrugReferenceSource().load().stream()
				.filter(r -> name.equalsIgnoreCase(r.getName())).findFirst().orElse(null);
	}

	@Test
	public void loadsBundledDatasetViaClasspathFallback() {
		List<DrugReference> all = new DdiDrugReferenceSource().load();
		assertFalse(all.isEmpty(), "bundled DDI dataset should load via the classpath fallback");
		assertNotNull(entry("Warfarin"), "dataset should contain the Warfarin entry");
	}

	@Test
	public void entriesCarryInteractionsWithSeverityNotes() {
		DrugReference warfarin = entry("Warfarin");
		assertNotNull(warfarin);
		assertFalse(warfarin.getInteractions().isEmpty(),
				"the DDInter source should carry drug-drug interaction rules");
		DrugReference.Interaction nsaid = warfarin.getInteractions().stream()
				.filter(i -> "ibuprofen".equals(i.getToken())).findFirst().orElse(null);
		assertNotNull(nsaid, "Warfarin should list an interaction with ibuprofen");
		assertNotNull(nsaid.getNote(), "the interaction should carry a note");
		String severityWord = nsaid.getNote().split("[ .]", 2)[0];
		assertTrue(SEVERITY.contains(severityWord),
				"the note should begin with the DDInter severity, was: " + nsaid.getNote());
	}

	@Test
	public void v1ScopeIsInteractionsOnly() {
		DrugReference warfarin = entry("Warfarin");
		assertNotNull(warfarin);
		assertTrue(warfarin.getAgeBands().isEmpty(), "V1 carries no dosing bands");
		assertTrue(warfarin.getContraindications().isEmpty(), "V1 carries no contraindications");
	}

	@Test
	public void aliasesIncludeCielConceptNames() {
		boolean anyCombinationAlias = new DdiDrugReferenceSource().load().stream()
				.flatMap(r -> r.getAliases().stream())
				.anyMatch(a -> a.contains("/"));
		assertTrue(anyCombinationAlias,
				"aliases should include CIEL concept names (e.g. combination products)");
	}

	@Test
	public void interactionTokenIsTheGenericRxNormName() {
		// Match on the RxNorm generic name so a DDInter display name that diverges from the CIEL
		// order name still matches: "Acetylsalicylic acid" must surface as the token "aspirin".
		DrugReference warfarin = entry("Warfarin");
		assertNotNull(warfarin);
		assertTrue(warfarin.getInteractions().stream().anyMatch(i -> "aspirin".equals(i.getToken())),
				"the aspirin interaction token should be the generic name 'aspirin'");
	}

	@Test
	public void interactionAtcCodesAreLevel5() {
		// The validator's same-drug skip and order matcher key on level-5 substance codes; a
		// level-4 subgroup here produced a false duplicate-therapy chip on the patient's own drug.
		DrugReference lisinopril = entry("Lisinopril");
		assertNotNull(lisinopril);
		assertTrue(lisinopril.getAtcCodes().contains("C09AA03"),
				"ATC codes should be level-5 substance codes (C09AA03), not level-4 (C09AA): "
						+ lisinopril.getAtcCodes());
	}

	@Test
	public void renderCapBoundsBroadInteractionSets() {
		// A broad interaction set (Warfarin, many partners) must not write every full note into
		// the injected record — that overruns the LLM context window. The render caps it and
		// summarises the remainder; the validator still sees every interaction (tested below).
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(DrugReferenceTestSupport.ddinterService());
		PatientChart result = injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, null, null, null, null), "is warfarin safe to add?");
		String text = result.getText();
		assertTrue(text.contains("Drug reference — Warfarin"), "the Warfarin reference should be injected");
		assertTrue(text.contains("more interactions on file"),
				"a broad interaction set must be capped, with the remainder summarised");
		int start = text.indexOf("Drug reference — Warfarin");
		int end = text.indexOf('\n', start);
		String line = end > start ? text.substring(start, end) : text.substring(start);
		assertTrue(line.length() < 3000,
				"the capped record must be far smaller than the uncapped full set; was " + line.length());
	}

	@Test
	public void level5AtcSkipsFalseDuplicateTherapyOnTheSameDrug() {
		// Regression: with level-4 codes the same-drug skip missed (entry {C09AA} never contains
		// order C09AA03), firing a false duplicate-therapy chip about the patient's own lisinopril.
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService());
		List<SafetyWarning> warnings = validator.validate(
				"The patient's lisinopril dose is 10 mg once daily.",
				"What is the patient's lisinopril dose?",
				DrugReferenceTestSupport.ctx(60, null, null, DrugReferenceTestSupport.set("C09AA03"), null, null));
		assertFalse(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "lisinopril"),
				"an active order for the patient's own drug must not raise a duplicate-therapy warning");
	}

	@Test
	public void interactionFiresAgainstOrderNamedByGenericName() {
		// Warfarin's DDInter partner "Acetylsalicylic acid" must fire against an order named
		// "Aspirin" — the token is the generic "aspirin", matched as a substring of the order name.
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService());
		List<SafetyWarning> warnings = validator.validate(
				"Warfarin is a reasonable anticoagulant choice.",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin"), null, null, null));
		assertTrue(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "warfarin"),
				"warfarin's aspirin interaction should fire against an active order named Aspirin");
	}

	/**
	 * Entries parsed from a DDInter test fixture by the real {@link DdiDrugReferenceSource#parse}.
	 * Deliberately not named {@code fixtureEntries}: {@link DrugReferenceTestSupport} already owns
	 * that name bound to {@link JsonDrugReferenceSource#parse}, a different parser.
	 */
	private static List<DrugReference> ddiFixtureEntries(String classpathResource) throws Exception {
		try (InputStream in = DdiDrugReferenceSourceTest.class.getClassLoader()
				.getResourceAsStream(classpathResource)) {
			assertNotNull(in, classpathResource + " should be on the test classpath");
			return DdiDrugReferenceSource.parse(in);
		}
	}

	private static List<DrugReference> markerFixtureEntries() throws Exception {
		return ddiFixtureEntries(MARKER_FIXTURE);
	}

	private static DrugReference.Interaction interaction(List<DrugReference> entries, String drug, String token) {
		DrugReference ref = entries.stream().filter(r -> drug.equalsIgnoreCase(r.getName())).findFirst()
				.orElseThrow(() -> new AssertionError("fixture should carry the " + drug + " entry"));
		return ref.getInteractions().stream().filter(i -> token.equals(i.getToken())).findFirst()
				.orElseThrow(() -> new AssertionError(drug + " should list an interaction with " + token));
	}

	@Test
	public void residualFieldMarkersAreStrippedFromInteractionNotes() throws Exception {
		// Issue #116: INTERVAL: and RECOMMENDED: are DDInter field markers left over from the
		// upstream monograph's management tag, not prose — 224 and 50 of the full KB's 8234
		// mechanisms respectively. Passed through, the marker reaches the clinician verbatim at
		// the front of a safety chip. The mechanism text after it must survive byte-for-byte.
		List<DrugReference> entries = markerFixtureEntries();

		assertEquals("Major. " + DOLUTEGRAVIR_MECHANISM, interaction(entries, "Dolutegravir", "iron").getNote(),
				"the INTERVAL: marker must be stripped and the mechanism text kept intact");
		assertEquals("Moderate. " + TAZEMETOSTAT_MECHANISM,
				interaction(entries, "Tazemetostat", "ethinyl estradiol").getNote(),
				"the RECOMMENDED: marker must be stripped and the mechanism text kept intact");
	}

	@Test
	public void mechanismTextOutsideTheMarkerShapeIsPassedThroughUnstripped() throws Exception {
		// The other half of a conditional deletion: what the pattern must NOT eat. Stripping is the
		// only place this source deletes clinician-facing text, and over-matching fails silently —
		// no exception, just a note that opens mid-sentence. KB group 4945 is the real negative
		// control: the one row in all 8234 mechanisms whose own opening clause is a sentence ending
		// in a colon. Beheaded, it would read "Minor. either there is no significant interaction ...",
		// losing the "Limited and controversial data suggest" hedge that qualifies the whole finding.
		// It guards the CONJUNCTION of the pattern's constraints, not any one of them: its clause is
		// both mixed-case and seven words long, so measured against the real KB no single loosening
		// reaches it. The six-word bound on its own is pinned by the next test instead.
		assertEquals("Minor. " + THEOPHYLLINE_MECHANISM,
				interaction(markerFixtureEntries(), "Nifedipine", "theophylline").getNote(),
				"a mechanism outside the marker shape must reach the note byte-for-byte");
	}

	@Test
	public void allCapsRunLongerThanTheMarkerBoundIsNotTreatedAsAMarker() throws Exception {
		// Isolates the six-word bound the class javadoc names as a guard ("a shouted sentence ending
		// in a colon is not mistaken for a marker"). Without this the bound is unobservable: the real
		// KB's longest all-caps run is a single word, so deleting {0,5} leaves the whole suite green
		// and the guard silently gone. Synthetic for that reason, and reachable for the same reason
		// group 9998 is — the KB file is operator-editable. Seven ALL-CAPS words then a colon: the
		// shipped pattern must spare it, and it strips the moment the bound alone is loosened.
		String note = interaction(markerFixtureEntries(), "Theophylline", "bound probe").getNote();

		assertEquals("Minor. " + BOUND_PROBE_MECHANISM, note,
				"an all-caps run past the marker bound is a shouted sentence, not a marker, and must survive");
	}

	@Test
	public void markerOnlyMechanismDegradesToTheNoMechanismNote() throws Exception {
		// Edge case: a mechanism whose whole text is the marker carries no mechanism at all, so
		// stripping must fall through to the documented no-mechanism note rather than leave a
		// dangling "Major. ".
		String note = interaction(markerFixtureEntries(), "Dolutegravir", "marker only").getNote();

		assertFalse(note.contains("INTERVAL"), "the marker must not survive stripping, was: " + note);
		assertTrue(note.contains("no mechanism description on file"),
				"a marker-only mechanism must degrade to the no-mechanism note, was: " + note);
	}

	@Test
	public void interactionChipDetailIsFreeOfTheResidualFieldMarker() throws Exception {
		// The clinician-facing leak, through the real validator: this is the live-observed chip
		// (Melissa Wright, "Can I start dolutegravir?", active order iron) that read
		// "... — Major. INTERVAL: Coadministration with medications containing polyvalent cations".
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(markerFixtureEntries()));

		List<SafetyWarning> warnings = validator.validate(
				"Dolutegravir would be a reasonable option.", "Can I start dolutegravir?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Iron"), null, null, null));

		assertEquals(1, warnings.size(), "the fixture pair must yield exactly one chip, was: " + warnings);
		assertEquals("Dolutegravir interacts with active order iron — Major. " + DOLUTEGRAVIR_MECHANISM,
				warnings.get(0).getDetail(),
				"the chip detail must read as a sentence, with the mechanism text intact");
	}

	@Test
	public void injectedReferenceTextIsFreeOfTheResidualFieldMarker() throws Exception {
		// The other leak path named in the issue: the note is also the grounding text the model
		// cites, so the marker reached the prompt as well. One fix at the parse boundary covers
		// every consumer, because the mechanism text has a single point of consumption; this pins
		// the reference record, and the test below pins the other prompt renderer.
		DrugReferenceInjector injector = DrugReferenceTestSupport
				.injector(DrugReferenceTestSupport.serviceWith(markerFixtureEntries()));

		PatientChart result = injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Iron"), null, null, null),
				"Can I start dolutegravir?");

		String text = result.getText();
		assertTrue(text.contains("Drug reference — Dolutegravir"), "the Dolutegravir reference should be injected");
		assertFalse(text.contains("INTERVAL"),
				"no field marker may reach the grounding text the model cites, was: " + text);
		assertTrue(text.contains(DOLUTEGRAVIR_MECHANISM),
				"the mechanism text must survive in the injected record, was: " + text);
	}

	@Test
	public void injectedSafetyFindingIsFreeOfTheResidualFieldMarker() throws Exception {
		// The prompt carries the note through TWO renderers, not one: the reference record asserted
		// above (DrugReferenceInjector.render) and the pre-answer safety finding (#110,
		// DrugReferenceInjector.renderFinding), which reuses the chip detail verbatim and which the
		// model is steered to report before anything else. The finding record only exists when the
		// validator is wired, so the reference-record test above — which wires none — never sees this
		// line: on 13690b1 it read "Safety finding — Dolutegravir: Dolutegravir interacts with active
		// order iron — Major. INTERVAL: Coadministration ...", the marker in the highest-signal line
		// of the prompt.
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(markerFixtureEntries());
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		PatientChart result = injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Iron"), null, null, null),
				"Can I start dolutegravir?");

		List<RecordMapping> findings = result.getMappings().stream()
				.filter(m -> ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(m.getResourceType()))
				.collect(Collectors.toList());

		assertEquals(1, findings.size(),
				"the fixture pair must yield exactly one citable safety finding, was: " + result.getText());
		assertEquals("Safety finding — Dolutegravir: Dolutegravir interacts with active order iron — Major. "
				+ DOLUTEGRAVIR_MECHANISM, findings.get(0).getText(),
				"the finding line the model reads first must read as a sentence, with no field marker");
		assertFalse(result.getText().contains("INTERVAL"),
				"no field marker may reach the prompt through either renderer, was: " + result.getText());
	}

	@Test
	public void sharedRxcuiDoesNotCollapseEntryIds() throws Exception {
		// Real slice: three Lidocaine route variants all map to RxCUI 6387. The injector dedups
		// citations by id, so the rxcui is used only when unique — else the DDInter id — keeping
		// the three entries distinct rather than collapsing to one.
		List<DrugReference> entries = ddiFixtureEntries("chartsearchai-test/ddi-rxcui-collision.json");
		assertEquals(3, entries.size(), "fixture has three Lidocaine variants");
		long distinctIds = entries.stream().map(DrugReference::getId).distinct().count();
		assertEquals(3, distinctIds, "variants sharing a RxCUI must not collapse to one id");
	}
}
