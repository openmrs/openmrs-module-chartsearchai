/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.serializer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;

/**
 * Pure unit tests for {@link PatientChartSerializer}. Patient is passed as
 * {@code null} (demographics are simply skipped) so the serializer runs without
 * an OpenMRS context or a wired record loader.
 */
public class PatientChartSerializerTest {

	@Test
	public void serialize_shouldCarryEachRecordsTextIntoItsMapping() {
		// Pins the prerequisite the citation grounding verifier depends on: the
		// RecordMapping must carry the source text so the verifier can check that
		// a cited record actually supports the answer. If a future edit drops the
		// text argument, grounding silently degrades to "cannot verify" for every
		// citation and no other test would catch it.
		SerializedRecord r1 = new SerializedRecord("obs", "uuid-1", "Temperature: 36.7", new Date());
		SerializedRecord r2 = new SerializedRecord("condition", "uuid-2", "Type 2 diabetes mellitus", new Date());

		PatientChart chart = new PatientChartSerializer().serialize(null, Arrays.asList(r1, r2));

		List<RecordMapping> mappings = chart.getMappings();
		assertEquals(2, mappings.size());
		assertEquals(1, mappings.get(0).getIndex());
		assertEquals("Temperature: 36.7", mappings.get(0).getText());
		assertEquals(2, mappings.get(1).getIndex());
		assertEquals("Type 2 diabetes mellitus", mappings.get(1).getText());
	}
}
