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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * A {@code safety_finding} record's {@code resourceUuid} is its chip's {@code type} and {@code drug}
 * joined by a colon, and README publishes that as a client contract: the reference client joins a
 * finding's citation to its chip by it. So it is pinned here as a LITERAL, through the real injector,
 * rather than recomputed with {@code ChartSearchAiUtils.resourceKey}, which would compare that method
 * with itself and stay green through a change of separator or of order.
 */
public class SafetyFindingResourceUuidContractTest {

	@Test
	public void aFindingsRecordIsKeyedOnItsTypeAndDrugJoinedByAColon() {
		PatientChart result = DrugReferenceTestSupport
				.injectorWithSafety(DrugReferenceTestSupport.ddinterServiceWithGroups())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("simvastatin"),
								DrugReferenceTestSupport.set("C10AA01"), null, null),
						"is it safe to give clarithromycin?");

		List<String> findingUuids = new ArrayList<String>();
		for (RecordMapping mapping : result.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(mapping.getResourceType())) {
				findingUuids.add(mapping.getResourceUuid());
			}
		}
		assertFalse(findingUuids.isEmpty(), "precondition: clarithromycin x her simvastatin raises a finding");
		for (String uuid : findingUuids) {
			assertEquals("interaction:Clarithromycin", uuid,
					"a finding's resourceUuid is `<type>:<drug>`, the key README tells a client to join on");
		}
	}
}
