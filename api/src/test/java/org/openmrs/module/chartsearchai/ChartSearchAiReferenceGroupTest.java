/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Contract of {@link ChartSearchAiUtils#referenceGroup}, the single entry point deciding
 * whether a cited record renders as chart evidence or as module-supplied reference prose —
 * and, since issue #122, of {@link ChartSearchAiUtils#isGroundingDemoteOnly}, the grounding
 * rule derived from it. Both registries are swept off one enumeration of the declared
 * {@code RESOURCE_TYPE_*} constants and one recorded set of decisions, because a new type
 * satisfying one registry and silently missing the other is the defect #122 reported.
 *
 * <p>Deliberately a plain test rather than a {@code BaseModuleContextSensitiveTest}: the
 * classification is a pure function of the resource type and needs no OpenMRS context, so
 * it should not be coupled to Spring context startup.
 */
public class ChartSearchAiReferenceGroupTest {

	@Test
	public void referenceGroup_drugReference_shouldBeReferenceMaterial() {
		assertEquals(ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE,
				ChartSearchAiUtils.referenceGroup(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE));
	}

	@Test
	public void referenceGroup_chartResourceTypes_shouldAllBeChartEvidence() {
		// A representative sample of what PatientChartSerializer passes through from querystore —
		// including the order sub-types and the types that exist only as string literals upstream,
		// not as constants here. All of it is chart evidence; only the module's own injected record
		// is not. (Exhaustiveness over the declared constants is a separate test below.)
		for (String resourceType : Arrays.asList(ChartSearchAiConstants.RESOURCE_TYPE_ALLERGY,
				ChartSearchAiConstants.RESOURCE_TYPE_OBS, ChartSearchAiConstants.RESOURCE_TYPE_CONDITION,
				ChartSearchAiConstants.RESOURCE_TYPE_DIAGNOSIS, ChartSearchAiConstants.RESOURCE_TYPE_ORDER,
				ChartSearchAiConstants.RESOURCE_TYPE_PROGRAM,
				ChartSearchAiConstants.RESOURCE_TYPE_MEDICATION_DISPENSE, "patient", "visit", "encounter",
				"drug_order", "test_order", "referral_order")) {
			assertEquals(ChartSearchAiConstants.REFERENCE_GROUP_CHART,
					ChartSearchAiUtils.referenceGroup(resourceType),
					resourceType + " is retrieved from the chart, so it must group as chart evidence");
		}
	}

	@Test
	public void referenceGroup_unknownOrNullResourceType_shouldFailSafeToChartEvidence() {
		// An unrecognised type must never be labelled module-supplied reference material:
		// that would assert a provenance we cannot demonstrate. Chart evidence is the
		// conservative fallback — it keeps the citation in the main list, where a
		// clinician evaluates it against the record it points at.
		assertEquals(ChartSearchAiConstants.REFERENCE_GROUP_CHART,
				ChartSearchAiUtils.referenceGroup(null));
		assertEquals(ChartSearchAiConstants.REFERENCE_GROUP_CHART,
				ChartSearchAiUtils.referenceGroup("some_future_type"));
	}

	@Test
	public void referenceGroup_shouldBeCaseSensitiveOnTheWireValue() {
		// The wire value is the exact constant the injector writes; a differently-cased
		// string is an unknown type, not a drug reference.
		assertEquals(ChartSearchAiConstants.REFERENCE_GROUP_CHART,
				ChartSearchAiUtils.referenceGroup("Drug_Reference"));
	}

	/**
	 * Forcing function for the exhaustiveness assumption {@link ChartSearchAiUtils#referenceGroup}
	 * documents. The unknown-type fallback is {@code chart}, which is right for a chart type but
	 * WRONG for a module-injected one that carries module-supplied prose (a guideline, a formulary
	 * note): it would be published as chart evidence about the patient, with no error anywhere —
	 * the provenance disclosure silently inverts.
	 *
	 * <p>The compiler cannot check this, so this test pins it: every declared
	 * {@code RESOURCE_TYPE_*} constant must have an explicitly recorded expected group. Adding a
	 * constant without deciding its group fails here.
	 *
	 * <p>If that is you, decide on PROVENANCE, not on whether the module injects it — "injected"
	 * is not the test, and using it as one is how this classification gets inverted. Ask whose
	 * record it is. {@code chart} when it is evidence about this patient, whether querystore
	 * retrieved it or the module read it from a patient-data service and injected it — that is why
	 * {@code RESOURCE_TYPE_ACTIVE_DRUG_ORDER} is {@code chart} below despite being injected.
	 * {@code reference} only when the content is module-supplied material rather than the
	 * patient's record, in which case {@code referenceGroup} needs updating too, since its fallback
	 * will not get you there.
	 *
	 * <p>Recording {@code reference} has a second consequence since issue #122, so decide knowing it:
	 * the type becomes demote-only for citation grounding, i.e. its citations can be flagged but never
	 * verified. That follows from the same provenance judgement — a cosine pass cannot check
	 * module-rendered prose against itself — and it is asserted by the guard below.
	 */
	@Test
	public void referenceGroup_everyDeclaredResourceTypeConstant_shouldHaveADecidedGroup() {
		Map<String, String> expected = recordedGroups();
		Map<String, String> declared = declaredResourceTypeConstants();

		List<String> undecided = new ArrayList<String>();
		for (Map.Entry<String, String> constant : declared.entrySet()) {
			if (!expected.containsKey(constant.getKey())) {
				undecided.add(constant.getKey());
				continue;
			}
			assertEquals(expected.get(constant.getKey()),
					ChartSearchAiUtils.referenceGroup(constant.getValue()),
					constant.getKey() + " (\"" + constant.getValue()
							+ "\") is grouped differently than this test records");
		}
		assertTrue(undecided.isEmpty(),
				"new resource-type constant(s) " + undecided + " have no recorded reference group. "
						+ "Decide: chart evidence, or module-injected reference material? If injected, "
						+ "ChartSearchAiUtils.referenceGroup must be updated too — otherwise the new type "
						+ "is silently published as chart evidence about the patient.");

		// Also assert the reverse direction, so a removed or renamed constant cannot leave a dead
		// row here quietly claiming to guard a type that no longer exists.
		List<String> stale = new ArrayList<String>(expected.keySet());
		stale.removeAll(declared.keySet());
		assertTrue(stale.isEmpty(),
				"this test records group(s) for " + stale + ", which are no longer declared in "
						+ "ChartSearchAiConstants — drop the stale row(s).");
	}

	/**
	 * The SECOND registry a resource type is decided in, swept off the same enumeration and the same
	 * recorded decisions as the group guard above — issue #122.
	 *
	 * <p>Why this exists. Grounding treats module-supplied material as demote-only: a cosine pass
	 * renders {@code null} (unverified), never {@code true}, because an answer sentence citing
	 * module-rendered reference prose is typically a recitation of it and embeds near-identically to
	 * its source whether or not it swaps subject roles (#106, measured). That carve-out named
	 * {@code drug_reference} alone, so when #110 added {@code safety_finding} — duly recorded above as
	 * reference material — the module's own deterministic findings were graded as if they were
	 * retrieved chart evidence, and published unstable {@code grounded} verdicts to clinicians for two
	 * releases. Nothing failed, because only the group registry was guarded.
	 *
	 * <p>So the rule is now derived from the group ({@link ChartSearchAiUtils#isGroundingDemoteOnly}),
	 * and this test asserts it against the group each constant is RECORDED as rather than against
	 * {@code referenceGroup}'s answer — otherwise a classifier bug would satisfy both registries at
	 * once and this guard would agree with it.
	 *
	 * <p>That the verifier actually honours the rule — no {@code true} verdict, no Tier-2 call, no
	 * entailment-cap slot — is asserted through the real verifier in
	 * {@code CitationGroundingVerifierTest.everyDeclaredResourceTypeConstant_isGradedAccordingToItsReferenceGroup};
	 * it lives there because the verifier's embedder seam is package-private to {@code api.impl}.
	 */
	@Test
	public void everyDeclaredResourceTypeConstant_shouldBeDemoteOnlyForGroundingExactlyWhenItIsReferenceMaterial() {
		Map<String, String> expected = recordedGroups();
		for (Map.Entry<String, String> constant : declaredResourceTypeConstants().entrySet()) {
			String recorded = expected.get(constant.getKey());
			if (recorded == null) {
				// An undecided constant is the group guard's failure to report, not this one's.
				continue;
			}
			boolean referenceMaterial = ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE.equals(recorded);
			assertEquals(referenceMaterial,
					ChartSearchAiUtils.isGroundingDemoteOnly(constant.getValue()),
					constant.getKey() + " (\"" + constant.getValue() + "\") is recorded as " + recorded
							+ " material, so grounding must " + (referenceMaterial ? "" : "NOT ")
							+ "treat it as demote-only. Module-supplied material cannot be verified by a "
							+ "cosine pass (#106); the patient's own records must be, however they reached "
							+ "the chart (#118). Keep the two registries derived from one classification "
							+ "rather than re-listing type names in either.");
		}
	}

	/**
	 * Every declared {@code RESOURCE_TYPE_*} constant as name → wire value. The ONE enumeration both
	 * registry guards above sweep, so a newly declared constant reaches both of them or neither —
	 * the property whose absence let #110's missing grounding registration ship (issue #122).
	 */
	private static Map<String, String> declaredResourceTypeConstants() {
		Map<String, String> constants = new LinkedHashMap<String, String>();
		for (Field field : ChartSearchAiConstants.class.getDeclaredFields()) {
			if (!field.getName().startsWith("RESOURCE_TYPE_") || field.getType() != String.class
					|| !Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			try {
				constants.put(field.getName(), (String) field.get(null));
			}
			catch (IllegalAccessException e) {
				throw new AssertionError("could not read " + field.getName(), e);
			}
		}
		return constants;
	}

	/**
	 * The group each declared resource type is RECORDED as — the single place the decision lives, read
	 * by both registry guards above. A constant missing from here is undecided and fails the group
	 * guard; see its javadoc for how to decide.
	 */
	private static Map<String, String> recordedGroups() {
		Map<String, String> expected = new HashMap<String, String>();
		expected.put("RESOURCE_TYPE_OBS", ChartSearchAiConstants.REFERENCE_GROUP_CHART);
		expected.put("RESOURCE_TYPE_CONDITION", ChartSearchAiConstants.REFERENCE_GROUP_CHART);
		expected.put("RESOURCE_TYPE_ALLERGY", ChartSearchAiConstants.REFERENCE_GROUP_CHART);
		expected.put("RESOURCE_TYPE_DIAGNOSIS", ChartSearchAiConstants.REFERENCE_GROUP_CHART);
		expected.put("RESOURCE_TYPE_ORDER", ChartSearchAiConstants.REFERENCE_GROUP_CHART);
		expected.put("RESOURCE_TYPE_PROGRAM", ChartSearchAiConstants.REFERENCE_GROUP_CHART);
		expected.put("RESOURCE_TYPE_MEDICATION_DISPENSE", ChartSearchAiConstants.REFERENCE_GROUP_CHART);
		expected.put("RESOURCE_TYPE_DRUG_REFERENCE", ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE);
		// Module-derived, not chart evidence: a safety finding is computed from the patient's records
		// plus the drug KB, so there is no chart row for a client to navigate to. It is patient-specific
		// (which is why it is not a drug_reference record — the system prompt tells the model those are
		// NOT the patient's data), but it is still module-supplied material, so it presents as reference.
		expected.put("RESOURCE_TYPE_SAFETY_FINDING", ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE);
		// Module-INJECTED but NOT module-supplied, the one combination this classification has to get
		// right in both directions: an active_drug_order record is the patient's own active order,
		// read from OrderService when the retrieved chart carries no drug-order record for it
		// (issue #118). Grouping it as reference material would tell a clinician a live prescription
		// is not their patient's data — the dangerous inversion, since it invites discounting the very
		// order a safety chip is raised about. It also carries the real Order uuid, so it stays
		// navigable like any other chart citation. Chart evidence is what referenceGroup's fallback
		// yields; the row is here because the decision must be recorded, not inherited by omission.
		expected.put("RESOURCE_TYPE_ACTIVE_DRUG_ORDER", ChartSearchAiConstants.REFERENCE_GROUP_CHART);
		return expected;
	}
}
