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

import java.util.Arrays;

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
		// Every type produced by PatientChartSerializer (i.e. everything querystore
		// retrieves) is chart evidence — only the module's own injected record is not.
		for (String resourceType : Arrays.asList(ChartSearchAiConstants.RESOURCE_TYPE_ALLERGY,
				ChartSearchAiConstants.RESOURCE_TYPE_OBS, ChartSearchAiConstants.RESOURCE_TYPE_CONDITION,
				ChartSearchAiConstants.RESOURCE_TYPE_DIAGNOSIS, ChartSearchAiConstants.RESOURCE_TYPE_ORDER,
				ChartSearchAiConstants.RESOURCE_TYPE_PROGRAM,
				ChartSearchAiConstants.RESOURCE_TYPE_MEDICATION_DISPENSE, "patient", "visit", "encounter",
				"drug_order", "test_order")) {
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
}
