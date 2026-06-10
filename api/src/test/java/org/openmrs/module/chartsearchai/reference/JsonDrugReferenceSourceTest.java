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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Exercises the real {@link JsonDrugReferenceSource#load()} path. With no OpenMRS
 * context available it falls back to the bundled {@code /chartsearchai/drug-reference.json}
 * — the production default — so this runs the real load path against the real dataset.
 */
public class JsonDrugReferenceSourceTest {

	@Test
	public void loadsBundledDatasetViaClasspathFallback() {
		List<DrugReference> all = new JsonDrugReferenceSource().load();
		assertFalse(all.isEmpty(), "bundled dataset should load via the classpath fallback");
		assertTrue(all.stream().anyMatch(r -> "ibuprofen".equals(r.getId())),
				"dataset should contain the ibuprofen entry");
	}

	@Test
	public void curatedEntriesCarrySafetyRules() {
		// Unlike the ATC classification source, the curated JSON carries the actual
		// contraindication/dosing rules the validator fires on.
		DrugReference ibuprofen = new JsonDrugReferenceSource().load().stream()
				.filter(r -> "ibuprofen".equals(r.getId())).findFirst().orElse(null);
		assertTrue(ibuprofen != null && !ibuprofen.getContraindications().isEmpty(),
				"the curated ibuprofen entry should carry contraindication rules");
	}
}
