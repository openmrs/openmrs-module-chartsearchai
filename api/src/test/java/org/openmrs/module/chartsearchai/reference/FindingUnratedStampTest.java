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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;

/**
 * {@code RecordMapping.getFindingUnrated()} — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/560">#560</a> — says of an
 * injected finding exactly what its record says to the model: {@code TRUE} where the record states
 * {@link DrugReferenceInjector#FINDING_NO_SEVERITY}, {@code FALSE} on every other finding, and
 * {@code null} on every record that is not a finding. {@code getFindingSeverity() == null} cannot say
 * it: that also answers for a rating {@code statableRating} declines and for a rating the record's
 * text does not state, and an answer stating either of those is not wrong.
 *
 * <p>Over the knowledge base the module SHIPS and Sarah Taylor's arrangement (issue #560): a prednisone
 * order, allergies to dexamethasone and hydrocortisone, and a clarithromycin order, so one chart
 * carries unrated cross-reactivity findings and a rated interaction finding together.
 */
public class FindingUnratedStampTest {

	@Test
	public void theStampSaysWhatTheRecordSaysOnEveryRecordOfTheChart() {
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOverWithRecordedAllergies(
				DrugReferenceTestSupport.shippedServiceWithGroups(), baseChart(),
				"Can I give her prednisone?", set("Prednisone Co 5mg", "Clarithromycin 500mg"),
				set("H02AB07", "J01FA09"), set("dexamethasone", "hydrocortisone"));
		int unrated = 0;
		int rated = 0;
		for (RecordMapping mapping : chart.getMappings()) {
			if (!ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(mapping.getResourceType())) {
				assertNull(mapping.getFindingUnrated(),
						"a record that is not a finding carries no answer at all: " + mapping.getText());
				continue;
			}
			boolean statesNoSeverity = mapping.getText().contains(DrugReferenceInjector.FINDING_NO_SEVERITY.trim());
			assertEquals(Boolean.valueOf(statesNoSeverity), mapping.getFindingUnrated(),
					"the stamp and the sentence the model reads must agree: " + mapping.getText());
			if (statesNoSeverity) {
				unrated++;
			}
			else {
				rated++;
			}
		}
		assertEquals(2, unrated, "precondition: her two corticosteroid allergies each raise an unrated finding");
		assertTrue(rated > 0, "precondition: the clarithromycin order raises a rated finding beside them");
	}

	private static PatientChart baseChart() {
		return new PatientChartSerializer().serialize(null, Arrays.asList(
				new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER, "order-uuid-1",
						"Prednisone Co 5mg tablet, 1 daily", null),
				new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER, "order-uuid-2",
						"Clarithromycin 500mg tablet, 1 daily", null)),
				Collections.<String> emptySet());
	}

	private static Set<String> set(String... values) {
		return new LinkedHashSet<String>(Arrays.asList(values));
	}
}
