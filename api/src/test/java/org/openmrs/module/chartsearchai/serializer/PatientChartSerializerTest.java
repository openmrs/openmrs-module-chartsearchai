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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.openmrs.module.chartsearchai.util.DateFormatUtil;

/**
 * Pure unit tests for {@link PatientChartSerializer}. Patient is passed as
 * {@code null} (demographics are simply skipped) so the serializer runs without
 * an OpenMRS context or a wired record loader.
 */
public class PatientChartSerializerTest {

	@Test
	public void serialize_mappingTextMatchesWhatTheLlmSaw() {
		// Pins the contract the citation grounding verifier depends on: the RecordMapping
		// must carry the SAME per-record content the LLM saw in the chart line (date
		// parenthetical + body), not the bare record body. Otherwise Tier-2 entailment
		// flags an otherwise-valid citation as unsupported when the model cites the date
		// (the date lives in the prefix, not in record.getText()). If a future edit drops
		// the rendered text, grounding silently degrades and no other test would catch it.
		Date d = new Date(1700000000000L); // fixed instant for determinism
		String datePrefix = "(" + DateFormatUtil.formatDate(d) + ") ";
		SerializedRecord dated = new SerializedRecord("obs", "uuid-1", "Temperature: 36.7", d);
		SerializedRecord undated = new SerializedRecord("condition", "uuid-2", "Type 2 diabetes mellitus", null);

		PatientChart chart = new PatientChartSerializer().serialize(null, Arrays.asList(dated, undated));
		List<RecordMapping> mappings = chart.getMappings();

		assertEquals(2, mappings.size());
		// Dated record: mapping text includes the date prefix the model can cite.
		assertEquals(datePrefix + "Temperature: 36.7", mappings.get(0).getText());
		// Undated record: just the body, no parenthetical.
		assertEquals("Type 2 diabetes mellitus", mappings.get(1).getText());
		// And the mapping text is exactly the chart line content after the "[N] " prefix.
		assertTrue(chart.getText().contains("[1] " + datePrefix + "Temperature: 36.7"),
				"chart line should equal '[N] ' + the mapping text; chart was:\n" + chart.getText());
	}
}
