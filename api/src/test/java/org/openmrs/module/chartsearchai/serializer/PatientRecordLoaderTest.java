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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class PatientRecordLoaderTest extends BaseModuleContextSensitiveTest {

	private static final String TEST_DATA = "ChartSearchAiTestData.xml";

	@Autowired
	private PatientRecordLoader recordLoader;

	private Patient patient;

	@BeforeEach
	public void setUp() throws Exception {
		executeDataSet(TEST_DATA);
		patient = Context.getPatientService().getPatient(2);
	}

	@Test
	public void loadAll_shouldReturnObservations() {
		List<SerializedRecord> records = recordLoader.loadAll(patient);

		boolean hasObs = false;
		for (SerializedRecord record : records) {
			if ("obs".equals(record.getResourceType())) {
				hasObs = true;
				break;
			}
		}
		assertTrue(hasObs, "Should load obs records");
	}

	@Test
	public void loadAll_shouldSerializeObsText() {
		List<SerializedRecord> records = recordLoader.loadAll(patient);

		boolean foundWeight = false;
		for (SerializedRecord record : records) {
			if (record.getText().contains("WEIGHT")) {
				foundWeight = true;
				break;
			}
		}
		assertTrue(foundWeight, "Should serialize obs with concept name");
	}

	@Test
	public void loadAll_shouldNotReturnEmptyRecords() {
		List<SerializedRecord> records = recordLoader.loadAll(patient);

		for (SerializedRecord record : records) {
			assertFalse(record.getText().trim().isEmpty(),
					"No record should have empty text");
		}
	}

	@Test
	public void loadAll_shouldIncludeResourceIds() {
		List<SerializedRecord> records = recordLoader.loadAll(patient);

		for (SerializedRecord record : records) {
			assertTrue(record.getResourceId() != null && record.getResourceId() > 0,
					"Each record should have a resource ID");
		}
	}
}
