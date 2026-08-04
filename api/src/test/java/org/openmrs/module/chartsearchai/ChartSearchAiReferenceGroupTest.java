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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Contract of {@link ChartSearchAiUtils#referenceGroup}, the single entry point deciding
 * whether a cited record renders as chart evidence or as module-supplied reference prose.
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
	 * WRONG for a future module-injected one: a second kind of injected record (a guideline, a
	 * formulary note) would be published as chart evidence about the patient, with no error
	 * anywhere — the provenance disclosure silently inverts.
	 *
	 * <p>The compiler cannot check this, so this test pins it: every declared
	 * {@code RESOURCE_TYPE_*} constant must have an explicitly recorded expected group. Adding a
	 * constant without deciding its group fails here. If that is you: add your type below —
	 * {@code chart} if querystore retrieves it from the patient's chart, {@code reference} if the
	 * module injects it, in which case {@code referenceGroup} needs updating too.
	 */
	@Test
	public void referenceGroup_everyDeclaredResourceTypeConstant_shouldHaveADecidedGroup() {
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

		List<String> undecided = new ArrayList<String>();
		List<String> seen = new ArrayList<String>();
		for (Field field : ChartSearchAiConstants.class.getDeclaredFields()) {
			if (!field.getName().startsWith("RESOURCE_TYPE_") || field.getType() != String.class
					|| !Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			seen.add(field.getName());
			if (!expected.containsKey(field.getName())) {
				undecided.add(field.getName());
				continue;
			}
			String value;
			try {
				value = (String) field.get(null);
			}
			catch (IllegalAccessException e) {
				throw new AssertionError("could not read " + field.getName(), e);
			}
			assertEquals(expected.get(field.getName()), ChartSearchAiUtils.referenceGroup(value),
					field.getName() + " (\"" + value + "\") is grouped differently than this test records");
		}
		assertTrue(undecided.isEmpty(),
				"new resource-type constant(s) " + undecided + " have no recorded reference group. "
						+ "Decide: chart evidence, or module-injected reference material? If injected, "
						+ "ChartSearchAiUtils.referenceGroup must be updated too — otherwise the new type "
						+ "is silently published as chart evidence about the patient.");

		// Also assert the reverse direction, so a removed or renamed constant cannot leave a dead
		// row here quietly claiming to guard a type that no longer exists.
		List<String> stale = new ArrayList<String>(expected.keySet());
		stale.removeAll(seen);
		assertTrue(stale.isEmpty(),
				"this test records group(s) for " + stale + ", which are no longer declared in "
						+ "ChartSearchAiConstants — drop the stale row(s).");
	}
}
