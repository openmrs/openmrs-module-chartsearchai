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

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Exercises the real {@link DdiDrugReferenceSource} and its behaviour through the real injector
 * and validator (via {@link DrugReferenceTestSupport}). With no OpenMRS context available the
 * source falls back to the bundled {@code /chartsearchai/ddi-knowledge-base.json} sample — the
 * production default — so these run the real load/parse/inject/validate paths against real data.
 */
public class DdiDrugReferenceSourceTest {

	private static final String SEVERITY = "Major Moderate Minor Unknown";

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
		// "Aspirin" — the token is the generic "aspirin", matched against the order name by word
		// start (DrugReference.matchesOrderName, issue #86), not as a bare substring.
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService());
		List<SafetyWarning> warnings = validator.validate(
				"Warfarin is a reasonable anticoagulant choice.",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin"), null, null, null));
		assertTrue(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "warfarin"),
				"warfarin's aspirin interaction should fire against an active order named Aspirin");
	}

	@Test
	public void sharedRxcuiDoesNotCollapseEntryIds() throws Exception {
		// Real slice: three Lidocaine route variants all map to RxCUI 6387. The injector dedups
		// citations by id, so the rxcui is used only when unique — else the DDInter id — keeping
		// the three entries distinct rather than collapsing to one.
		try (InputStream in = DdiDrugReferenceSourceTest.class.getClassLoader()
				.getResourceAsStream("chartsearchai-test/ddi-rxcui-collision.json")) {
			assertNotNull(in, "collision fixture should be on the test classpath");
			List<DrugReference> entries = DdiDrugReferenceSource.parse(in);
			assertEquals(3, entries.size(), "fixture has three Lidocaine variants");
			long distinctIds = entries.stream().map(DrugReference::getId).distinct().count();
			assertEquals(3, distinctIds, "variants sharing a RxCUI must not collapse to one id");
		}
	}
}
