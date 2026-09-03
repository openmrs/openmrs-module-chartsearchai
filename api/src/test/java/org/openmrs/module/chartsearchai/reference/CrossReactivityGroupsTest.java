/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Curated cross-reactivity groups: the data mechanism that closes ADR Decision 24's
 * documented boundary — ATC's tree cannot link cross-<em>branch</em> pharmacological
 * cross-reactivity (aspirin {@code N02BA01} vs ibuprofen {@code M01AE01}), so that
 * linkage is carried as data in {@code cross-reactivity-groups.json} and consumed
 * alongside <em>any</em> drug-reference source.
 *
 * <p>The validator and injector tests below run the real {@link DrugSafetyValidator}/{@link
 * DrugReferenceInjector} over the real WHO ATC sample (parsed by the real
 * {@link AtcDrugReferenceSource}) with the real bundled groups file (loaded by the real
 * {@link CrossReactivityGroupsLoader} production path). The dataset and membership-mechanics tests
 * above them sit one level lower on purpose — the real loader and the real
 * {@link CrossReactivityGroup} entry points, with no validator in the loop — because what they
 * assert is a property of the data mechanism itself. The existing ADR-24 boundary
 * tests in {@link DrugSafetyValidatorTest} stay true because {@code setEntries} pins a
 * hermetic dataset with NO groups — this class asserts both sides: without the groups
 * data the branches stay unlinked; with it, they link.
 */
public class CrossReactivityGroupsTest {

	private List<CrossReactivityGroup> bundledGroups() {
		return DrugReferenceTestSupport.bundledGroups();
	}

	private DrugReferenceService atcService(boolean withGroups) throws IOException {
		return DrugReferenceTestSupport.atcService(withGroups);
	}

	private DrugSafetyValidator validator(DrugReferenceService svc) {
		return DrugReferenceTestSupport.validator(svc);
	}

	private DrugReferenceInjector injector(DrugReferenceService svc) {
		return DrugReferenceTestSupport.injector(svc);
	}

	private PatientClinicalContext ctx(Integer age, Set<String> activeAtc, Set<String> allergies) {
		return DrugReferenceTestSupport.ctx(age, null, null, activeAtc, allergies, null);
	}

	private Set<String> set(String... values) {
		return DrugReferenceTestSupport.set(values);
	}

	private boolean has(List<SafetyWarning> warnings, String type, String drugContains) {
		return DrugReferenceTestSupport.has(warnings, type, drugContains);
	}

	private boolean detailContains(List<SafetyWarning> warnings, String type, String drug, String... needles) {
		return DrugReferenceTestSupport.detailContains(warnings, type, drug, needles);
	}

	private PatientChart oneRecordChart() {
		return DrugReferenceTestSupport.oneRecordChart();
	}

	// --- The groups dataset itself ---

	@Test
	public void bundledGroupsFileLoadsWithTheNsaidSeedGroup() {
		// Production load path: no OpenMRS context -> the GP read fails safe -> classpath fallback.
		List<CrossReactivityGroup> groups = bundledGroups();
		assertFalse(groups.isEmpty(), "the bundled cross-reactivity groups file should load");
		CrossReactivityGroup nsaid = null;
		for (CrossReactivityGroup g : groups) {
			if ("NSAID".equalsIgnoreCase(g.getName())) {
				nsaid = g;
			}
		}
		assertNotNull(nsaid, "the seed NSAID group should be present");
		assertTrue(nsaid.normalizedAtcPrefixes().contains("M01AE"),
				"the NSAID group should span the propionic-acid branch (M01AE)");
		assertTrue(nsaid.normalizedAtcPrefixes().contains("N02BA"),
				"the NSAID group should span the salicylate branch (N02BA)");
	}

	@Test
	public void emptyOrMissingGroupsSectionParsesToNoGroups() throws IOException {
		assertTrue(CrossReactivityGroupsLoader.parse(stream("{\"groups\":[]}")).isEmpty());
		assertTrue(CrossReactivityGroupsLoader.parse(stream("{}")).isEmpty());
	}

	@Test
	public void unusableGroupsAreDroppedAtParse() throws IOException {
		// A nameless group would render "… is in the same cross-reactivity group (null) …" into a warning,
		// and a group with no usable prefixes can never match — both are dropped at the parse boundary.
		List<CrossReactivityGroup> groups = CrossReactivityGroupsLoader.parse(stream(
				"{\"groups\":[{\"note\":\"nameless\",\"atcPrefixes\":[\"M01AE\"]},"
						+ "{\"name\":\"NoPrefixes\"},"
						+ "{\"name\":\"NullPrefixes\",\"atcPrefixes\":[null,\" \"]},"
						+ "{\"name\":\"Good\",\"atcPrefixes\":[\"M01AE\"]}]}"));
		assertEquals(1, groups.size(), "nameless/prefixless groups must be dropped: " + groups.size());
		assertEquals("Good", groups.get(0).getName());
	}

	private InputStream stream(String json) {
		return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
	}

	// --- Membership questions: what one costs, and what it re-reads ---

	@Test
	public void aMembershipQuestionNormalizesEachGroupsPrefixesOnce_notOncePerCode() {
		// Issue #230. containsCode() rebuilds the group's normalized prefix set every time it is
		// asked, and containsAnyCode() asked it once per code — so one membership question cost one
		// normalization per (group, code) pair, and it runs inside DrugSafetyValidator's per-pair
		// screening across the candidate set.
		//
		// Driven through CrossReactivityGroup.sharedGroupForCodes, the production order-code entry
		// point — the one that takes a SET because one order's concept can map to several codes
		// (issue #171) — over the real bundled groups' own prefixes and three real WHO level-5 codes
		// from the ATC sample: paracetamol, amoxicillin and gentamicin, none of them an NSAID. A
		// non-matching set is deliberately the case chosen, because it is the one that scans every
		// code instead of returning on the first match.
		//
		// CountingGroup only counts; the membership decision is entirely production code.
		List<CrossReactivityGroup> counted = new ArrayList<CrossReactivityGroup>();
		for (CrossReactivityGroup bundled : bundledGroups()) {
			counted.add(new CountingGroup(bundled));
		}
		assertFalse(counted.isEmpty(), "the bundled groups file must load, or this counts nothing");

		Set<String> orderCodes = set("N02BE01", "J01CA04", "J01GB03");
		assertNull(CrossReactivityGroup.sharedGroupForCodes(counted, orderCodes),
				"paracetamol/amoxicillin/gentamicin must be in no curated group — that is what makes "
						+ "every code get scanned. If a curated group is ever added that claims one of "
						+ "them, replace them with three the shipped file does not claim rather than "
						+ "relaxing this: a matching set returns early and counts nothing.");

		for (CrossReactivityGroup group : counted) {
			assertEquals(1, ((CountingGroup) group).normalizations,
					"group '" + group.getName() + "' must normalize its prefixes once for the whole "
							+ orderCodes.size() + "-code question, not once per code");
		}
	}

	@Test
	public void replacedPrefixesAreSeenOnTheNextQuestion_soTheNormalizationIsNeverCachedOnTheInstance()
			throws IOException {
		// The one thing the hoist above must not be "improved" into: a field. setAtcPrefixes is the
		// write path a group's prefixes arrive by — Jackson calls it, which is how
		// CrossReactivityGroupsLoader.parse builds a deployment's own file — so it has to stay
		// authoritative AFTER a membership question has been asked. A normalized set cached on the
		// instance would go on answering with the prefixes the group used to carry, and nothing would
		// fail: no other test here REPLACES a prefix list after asking. (Being asked twice is not the
		// trigger and is entirely ordinary — the loader asks every group it keeps for its normalized
		// prefixes once, as its own drop filter, before any caller does.) Same rule as issue #172's
		// "in a local, never in a field".
		List<CrossReactivityGroup> groups = CrossReactivityGroupsLoader.parse(stream(
				"{\"groups\":[{\"name\":\"NSAID\",\"atcPrefixes\":[\"M01AE\"]}]}"));
		assertNotNull(CrossReactivityGroup.sharedGroupForCodes(groups, set("M01AE01")),
				"the loaded prefix must match before anything is replaced");

		groups.get(0).setAtcPrefixes(Collections.singletonList("N02BA"));

		assertNull(CrossReactivityGroup.sharedGroupForCodes(groups, set("M01AE01")),
				"the replaced-away prefix must stop matching — a cached set would still say it does");
		assertNotNull(CrossReactivityGroup.sharedGroupForCodes(groups, set("N02BA01")),
				"the prefix written last must be the one that matches");
	}

	/**
	 * A real {@link CrossReactivityGroup} carrying a real loaded group's own name, note and
	 * prefixes, which counts how many times production asks it to normalize them. It overrides
	 * nothing else and delegates to the production {@link CrossReactivityGroup#normalizedAtcPrefixes()},
	 * so every membership decision under test is made by production code — the counter only observes.
	 */
	private static final class CountingGroup extends CrossReactivityGroup {

		int normalizations;

		CountingGroup(CrossReactivityGroup source) {
			setName(source.getName());
			setNote(source.getNote());
			setAtcPrefixes(source.getAtcPrefixes());
		}

		@Override
		public Set<String> normalizedAtcPrefixes() {
			normalizations++;
			return super.normalizedAtcPrefixes();
		}
	}

	// --- Validator: contraindication (allergy) reasoning across ATC branches ---

	@Test
	public void crossBranchAllergyContraindicationFiresViaCuratedGroup() throws IOException {
		// THE case ADR Decision 24 documented as out of ATC's reach: aspirin (N02BA01, salicylates)
		// recommended to a patient with an ibuprofen (M01AE01) allergy. The curated NSAID group links
		// the two branches, so the cross-reactivity contraindication now fires.
		List<SafetyWarning> warnings = validator(atcService(true)).validate(
				"Acetylsalicylic acid is a reasonable option here.",
				ctx(40, null, set("ibuprofen")));
		assertTrue(has(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "acetylsalicylic"),
				"the curated NSAID group must link aspirin to an ibuprofen allergy across ATC branches");
		assertTrue(detailContains(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "Acetylsalicylic acid",
				"ibuprofen", "NSAID"),
				"the warning should name the cross-reacting allergen and the curated group");
	}

	@Test
	public void withoutGroupsDataCrossBranchStaysUnlinked() throws IOException {
		// The other half of the contract: the linkage comes from the DATA, not from code. With no
		// groups loaded, ATC classification alone must still not cross branches (ADR 24's boundary).
		List<SafetyWarning> warnings = validator(atcService(false)).validate(
				"Acetylsalicylic acid is a reasonable option here.",
				ctx(40, null, set("ibuprofen")));
		assertFalse(has(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "acetylsalicylic"),
				"without the curated groups data, ATC class matching must not link across branches");
	}

	@Test
	public void crossBranchQuestionDrivenContraindicationFiresViaCuratedGroup() throws IOException {
		// The question-driven path must gain the same reach: the clinician asks about aspirin, the
		// answer never names it, the patient has an ibuprofen allergy -> still fires.
		List<SafetyWarning> warnings = validator(atcService(true)).validate(
				"The patient has an allergy to NSAID (drug allergen).",
				"Is acetylsalicylic acid a good option for her?",
				ctx(40, null, set("ibuprofen")));
		assertTrue(has(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "acetylsalicylic"),
				"the question-driven check must apply curated groups too");
	}

	@Test
	public void sameSubgroupAllergenStillWarnsExactlyOnceWithGroupsLoaded() throws IOException {
		// Naproxen (M01AE02) vs an ibuprofen (M01AE01) allergy matches BOTH the ATC subgroup and the
		// curated NSAID group. One clinical fact -> exactly one warning, and the more specific ATC
		// subgroup message wins.
		List<SafetyWarning> warnings = validator(atcService(true)).validate(
				"Naproxen could be considered for this patient.",
				ctx(40, null, set("ibuprofen")));
		long count = warnings.stream()
				.filter(w -> w.getType().equals(SafetyWarning.TYPE_CONTRAINDICATION)
						&& w.getDrug().equalsIgnoreCase("naproxen"))
				.count();
		assertEquals(1, count, "a subgroup + group double-match must warn once, not twice");
		assertTrue(detailContains(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "Naproxen", "M01AE"),
				"the more specific ATC-subgroup message should win over the group message");
	}

	@Test
	public void groupsDoNotLinkNonMembers() throws IOException {
		// Amoxicillin (J01CA04) is in no curated group with ibuprofen -> no contraindication.
		List<SafetyWarning> warnings = validator(atcService(true)).validate(
				"Amoxicillin should cover this infection.",
				ctx(40, null, set("ibuprofen")));
		assertFalse(has(warnings, SafetyWarning.TYPE_CONTRAINDICATION, "amoxicillin"),
				"curated groups must only link their own members");
	}

	// --- Validator: interaction (active order) reasoning across ATC branches ---

	@Test
	public void crossBranchActiveOrderInteractionFiresViaCuratedGroup() throws IOException {
		// Ibuprofen recommended while aspirin (N02BA01) is an active order: different ATC branches,
		// same curated NSAID group -> additive/duplicate-class therapy warning, naming the order.
		List<SafetyWarning> warnings = validator(atcService(true)).validate(
				"Ibuprofen could help with the pain.",
				ctx(40, set("N02BA01"), null));
		assertTrue(has(warnings, SafetyWarning.TYPE_INTERACTION, "ibuprofen"),
				"an active order in the same curated group must flag an interaction");
		assertTrue(detailContains(warnings, SafetyWarning.TYPE_INTERACTION, "Ibuprofen",
				"acetylsalicylic acid", "NSAID"),
				"the warning should name the active order it overlaps and the curated group");
	}

	@Test
	public void sameDrugActiveOrderStillNotFlaggedWithGroupsLoaded() throws IOException {
		// Restating existing therapy is not a duplicate: ibuprofen answer + ibuprofen order stays
		// unflagged even though ibuprofen is a member of the NSAID group.
		List<SafetyWarning> warnings = validator(atcService(true)).validate(
				"Ibuprofen 200 mg is already charted.",
				ctx(40, set("M01AE01"), null));
		assertFalse(has(warnings, SafetyWarning.TYPE_INTERACTION, "ibuprofen"),
				"the same drug already on order must not be flagged via its own group");
	}

	@Test
	public void sameSubgroupActiveOrderStillWarnsExactlyOnceWithGroupsLoaded() throws IOException {
		// Naproxen order (M01AE02) vs ibuprofen answer matches both the subgroup and the group ->
		// exactly one interaction, with the more specific ATC-subgroup message.
		List<SafetyWarning> warnings = validator(atcService(true)).validate(
				"Ibuprofen could help with the pain.",
				ctx(40, set("M01AE02"), null));
		long count = warnings.stream()
				.filter(w -> w.getType().equals(SafetyWarning.TYPE_INTERACTION)
						&& w.getDrug().equalsIgnoreCase("ibuprofen"))
				.count();
		assertEquals(1, count, "a subgroup + group double-match must warn once, not twice");
		assertTrue(detailContains(warnings, SafetyWarning.TYPE_INTERACTION, "Ibuprofen", "M01AE"),
				"the more specific ATC-subgroup message should win over the group message");
	}

	// --- Injector: order-relevance scoping gains the same reach ---

	@Test
	public void groupRelatedCrossBranchActiveOrderIsInjected() throws IOException {
		// The question is about aspirin (N02BA01); the active order is ibuprofen (M01AE01). With the
		// curated NSAID group loaded they are clinically related (duplicate-class / cross-reactivity
		// concern), so the active-order reference IS injected alongside the question's drug.
		PatientChart result = injector(atcService(true)).injectRecords(oneRecordChart(),
				ctx(40, set("M01AE01"), null), "is acetylsalicylic acid safe to prescribe?");
		assertTrue(result.getText().contains("Drug reference — Acetylsalicylic acid"),
				"the question's own drug should be injected");
		assertTrue(result.getText().contains("Drug reference — Ibuprofen"),
				"an active order in the same curated group as the question's drug should be injected");
	}

	@Test
	public void withoutGroupsDataCrossBranchActiveOrderIsNotInjected() throws IOException {
		// Without the groups data the same order is unrelated (different ATC class) and stays out.
		PatientChart result = injector(atcService(false)).injectRecords(oneRecordChart(),
				ctx(40, set("M01AE01"), null), "is acetylsalicylic acid safe to prescribe?");
		assertTrue(result.getText().contains("Drug reference — Acetylsalicylic acid"),
				"the question's own drug should still be injected");
		assertFalse(result.getText().contains("Drug reference — Ibuprofen"),
				"without the groups data, a cross-branch order must remain unrelated and uninjected");
	}
}
