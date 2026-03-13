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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.springframework.test.util.ReflectionTestUtils;

public class PatientChartSerializerTest {

	@Test
	public void serialize_shouldNumberRecordsStartingAtOne() {
		PatientChartSerializer serializer = createSerializer(
				new SerializedRecord("obs", 101, "Blood pressure 120/80"),
				new SerializedRecord("obs", 102, "Weight 72 kg"));

		PatientChart chart = serializer.serialize(new Patient());

		assertTrue(chart.getText().startsWith("[1] Blood pressure 120/80\n"));
		assertTrue(chart.getText().contains("[2] Weight 72 kg\n"));
	}

	@Test
	public void serialize_shouldBuildMatchingReferences() {
		PatientChartSerializer serializer = createSerializer(
				new SerializedRecord("obs", 101, "Blood pressure"),
				new SerializedRecord("order", 201, "Metformin"));

		PatientChart chart = serializer.serialize(new Patient());
		List<RecordReference> refs = chart.getReferences();

		assertEquals(2, refs.size());
		assertEquals(1, refs.get(0).getIndex());
		assertEquals("obs", refs.get(0).getResourceType());
		assertEquals(101, refs.get(0).getResourceId());
		assertEquals(2, refs.get(1).getIndex());
		assertEquals("order", refs.get(1).getResourceType());
		assertEquals(201, refs.get(1).getResourceId());
	}

	@Test
	public void serialize_shouldReturnEmptyChartForPatientWithNoRecords() {
		PatientChartSerializer serializer = createSerializer();

		PatientChart chart = serializer.serialize(new Patient());

		assertEquals("", chart.getText());
		assertTrue(chart.getReferences().isEmpty());
	}

	private PatientChartSerializer createSerializer(final SerializedRecord... records) {
		PatientRecordLoader stubLoader = new PatientRecordLoader() {
			@Override
			public List<SerializedRecord> loadAll(Patient patient) {
				List<SerializedRecord> list = new ArrayList<SerializedRecord>();
				for (SerializedRecord r : records) {
					list.add(r);
				}
				return list;
			}
		};

		PatientChartSerializer serializer = new PatientChartSerializer();
		ReflectionTestUtils.setField(serializer, "recordLoader", stubLoader);
		return serializer;
	}
}
