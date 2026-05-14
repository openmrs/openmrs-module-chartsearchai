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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
	public void loadAll_shouldNotReturnDuplicateResourceKeys() {
		List<SerializedRecord> records = recordLoader.loadAll(patient);

		Set<String> seenKeys = new HashSet<String>();
		for (SerializedRecord record : records) {
			String key = record.getResourceType() + ":" + record.getResourceUuid();
			assertTrue(seenKeys.add(key),
					"Duplicate resource key found: " + key);
		}
		assertEquals(records.size(), seenKeys.size());
	}

	@Test
	public void loadAll_shouldReturnDiagnoses() {
		List<SerializedRecord> records = recordLoader.loadAll(patient);

		boolean hasDiagnosis = false;
		for (SerializedRecord record : records) {
			if ("diagnosis".equals(record.getResourceType())) {
				hasDiagnosis = true;
				break;
			}
		}
		assertTrue(hasDiagnosis, "Should load diagnosis records");
	}

	@Test
	public void loadAll_shouldIncludeResourceUuids() {
		List<SerializedRecord> records = recordLoader.loadAll(patient);

		assertFalse(records.isEmpty(), "Test fixture should produce at least one record");
		// UUID-shape check: 36 chars, 8-4-4-4-12 segments separated by dashes.
		// Each segment must be non-empty and contain only alphanumerics — we
		// allow non-hex chars in segments because the openmrs-core fixture
		// dataset (standardTestDataset.xml) has a long-standing typo on one
		// order uuid ("...d808fbc226dh", ending in 'h'). The loader's contract
		// is to pass through what the persisted entity's getUuid() returns,
		// not to validate hex-purity. This is the UUID-equivalent of the old
		// "resourceId > 0" check: ensures the loader populated a real persisted
		// identifier (canonical 36-char UUID literal) rather than a default
		// like null, "", "0", or a stray integer string.
		final java.util.regex.Pattern UUID_SHAPE = java.util.regex.Pattern.compile(
				"^[0-9a-zA-Z]{8}-[0-9a-zA-Z]{4}-[0-9a-zA-Z]{4}-[0-9a-zA-Z]{4}-[0-9a-zA-Z]{12}$");
		for (SerializedRecord record : records) {
			String uuid = record.getResourceUuid();
			assertTrue(uuid != null && !uuid.isEmpty(),
					"Each record should have a non-empty resource UUID, got: " + uuid
							+ " for " + record.getResourceType());
			assertTrue(UUID_SHAPE.matcher(uuid).matches(),
					"Resource UUID does not match the 8-4-4-4-12 UUID shape: "
							+ uuid + " for " + record.getResourceType());
		}
	}
}
