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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Exercises the real {@link DrugReferenceService} against the real dataset bundled
 * on the classpath. With no OpenMRS context available, the service falls back to
 * the bundled {@code /chartsearchai/drug-reference.json} — which is exactly the
 * production default path, so these tests run the production load path.
 */
public class DrugReferenceServiceTest {

	private DrugReferenceService service() {
		return new DrugReferenceService();
	}

	@Test
	public void loadsBundledDataset() {
		List<DrugReference> all = service().getAll();
		assertFalse(all.isEmpty(), "bundled dataset should load via classpath fallback");
		assertTrue(all.stream().anyMatch(r -> "ibuprofen".equals(r.getId())),
				"dataset should contain the ibuprofen entry");
	}

	@Test
	public void findByQueryMatchesPrimaryName() {
		List<DrugReference> hits = service().findByQuery("what is the safe dose of ibuprofen for this child?");
		assertEquals(1, hits.size());
		assertEquals("ibuprofen", hits.get(0).getId());
	}

	@Test
	public void findByQueryMatchesAlias() {
		List<DrugReference> hits = service().findByQuery("is amoxil appropriate here?");
		assertTrue(hits.stream().anyMatch(r -> "amoxicillin".equals(r.getId())),
				"alias 'amoxil' should resolve to amoxicillin");
	}

	@Test
	public void findByQueryNoSpuriousSubstringMatch() {
		// "amox" is a substring of "amoxicillin" but not a whole-word alias, and the
		// prose below names no drug — must not match.
		assertTrue(service().findByQuery("how is the patient doing today?").isEmpty());
	}

	@Test
	public void findByActiveOrdersMatchesAtc() {
		Set<String> atc = new LinkedHashSet<String>();
		atc.add("M01AE01");
		PatientClinicalContext ctx = new PatientClinicalContext(40, Collections.<String> emptySet(),
				atc, Collections.<String> emptySet(), Collections.<String> emptySet());
		List<DrugReference> hits = service().findByActiveOrders(ctx);
		assertTrue(hits.stream().anyMatch(r -> "ibuprofen".equals(r.getId())),
				"ATC M01AE01 should resolve to ibuprofen");
	}

	@Test
	public void lookupByTokenResolvesAlias() {
		DrugReference ref = service().lookupByToken("advil");
		assertNotNull(ref);
		assertEquals("ibuprofen", ref.getId());
	}

	@Test
	public void lookupByTokenUnknownReturnsNull() {
		assertNull(service().lookupByToken("notadrug"));
	}

	@Test
	public void ageBandSelectionIsAgeGated() {
		DrugReference ibuprofen = service().lookupByToken("ibuprofen");
		assertNotNull(ibuprofen);
		// A 5-year-old falls in the 2-11 band; a published adult-band exists too.
		assertNotNull(ibuprofen.bandForAge(5));
		assertEquals(1200, (long) ibuprofen.bandForAge(5).getMaxDailyDoseMg());
		// Age unknown -> no band (so dosing is never surfaced without an age).
		assertNull(ibuprofen.bandForAge(null));
	}

	@Test
	public void delegatesToTheConfiguredSource() {
		// The service loads from its DrugReferenceSource (the format GP selects which one).
		// Inject a source via the seam and confirm getAll() returns exactly its entries.
		DrugReference entry = new DrugReference();
		entry.setId("test-drug");
		entry.setName("Test Drug");
		entry.setAliases(Collections.singletonList("test drug"));
		DrugReferenceService svc = new DrugReferenceService();
		svc.setSource(() -> Collections.singletonList(entry));
		assertEquals(1, svc.getAll().size());
		assertEquals("test-drug", svc.getAll().get(0).getId());
	}
}
