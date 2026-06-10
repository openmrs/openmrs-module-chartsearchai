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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Exercises the real {@link AtcDrugReferenceSource#parse} over a real WHO ATC sample
 * ({@code atc/atc-sample.tsv}, real codes and group names). Verifies the adapter
 * consumes the authoritative classification — and pins the honest boundary: ATC groups
 * same-class drugs (ibuprofen/naproxen) but does NOT put aspirin in the NSAID class.
 */
public class AtcDrugReferenceSourceTest {

	private List<DrugReference> parseSample() throws IOException {
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("atc/atc-sample.tsv")) {
			assertNotNull(in, "ATC sample resource should be on the test classpath");
			return AtcDrugReferenceSource.parse(in);
		}
	}

	private DrugReference byCode(List<DrugReference> all, String code) {
		return all.stream().filter(r -> code.equals(r.getId())).findFirst().orElse(null);
	}

	@Test
	public void emitsOneEntryPerLevel5SubstanceOnly() throws IOException {
		List<DrugReference> all = parseSample();
		// The sample has 6 level-5 substances; the group rows (M01A, M01AE, …) are NOT entries.
		assertEquals(6, all.size(), "only 7-char level-5 ATC codes become drug entries");
		assertTrue(all.stream().allMatch(r -> r.getId().length() == AtcDrugReferenceSource.SUBSTANCE_CODE_LENGTH));
	}

	@Test
	public void parsesLowercaseAtcCodesByNormalising() throws IOException {
		// RxNorm/ATC crosswalk exports are not always upper case; a lowercase code must be parsed
		// (normalised to upper case), not silently dropped, leaving the whole dataset empty.
		String lower = "m01ae\tPropionic acid derivatives\nm01ae01\tIbuprofen\n";
		List<DrugReference> all = AtcDrugReferenceSource.parse(
				new java.io.ByteArrayInputStream(lower.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		DrugReference ibuprofen = byCode(all, "M01AE01");
		assertNotNull(ibuprofen, "a lowercase ATC code must be parsed (normalised), not dropped");
		assertTrue(ibuprofen.getAtcCodes().contains("M01AE01"));
		assertEquals("Propionic acid derivatives", ibuprofen.getDrugClass());
	}

	@Test
	public void ignoresSevenCharTokensThatAreNotValidAtcCodes() throws IOException {
		// The sample includes "ABCDEFG" — 7 chars but not a valid ATC level-5 code. It must NOT
		// become a drug entry (a non-ATC/malformed file should degrade to clean output, not bogus drugs).
		assertNull(byCode(parseSample(), "ABCDEFG"), "a 7-char non-ATC token must not be emitted as a drug");
	}

	@Test
	public void substanceCarriesNameCodeAndAliasForMatching() throws IOException {
		DrugReference ibuprofen = byCode(parseSample(), "M01AE01");
		assertNotNull(ibuprofen);
		assertEquals("Ibuprofen", ibuprofen.getName());
		assertTrue(ibuprofen.getAtcCodes().contains("M01AE01"));
		assertTrue(ibuprofen.matchesText("is ibuprofen safe?"), "the name should match as an alias");
	}

	@Test
	public void drugClassIsDerivedFromTheNearestParentGroupInTheDataset() throws IOException {
		DrugReference ibuprofen = byCode(parseSample(), "M01AE01");
		// M01AE01 -> nearest parent group M01AE = "Propionic acid derivatives".
		assertEquals("Propionic acid derivatives", ibuprofen.getDrugClass());
	}

	@Test
	public void sameClassDrugsShareADrugClass() throws IOException {
		List<DrugReference> all = parseSample();
		// ibuprofen (M01AE01) and naproxen (M01AE02) are both M01AE -> one class rule covers both.
		assertEquals(byCode(all, "M01AE01").getDrugClass(), byCode(all, "M01AE02").getDrugClass());
	}

	@Test
	public void aspirinIsNotInTheSameAtcClassAsIbuprofen() throws IOException {
		List<DrugReference> all = parseSample();
		// Honest boundary: NSAID cross-reactivity spans ATC branches. Aspirin (N02BA01,
		// salicylates) is a different ATC class than ibuprofen (M01AE01, propionic NSAIDs),
		// so ATC class membership alone does NOT link them.
		assertNotEquals(byCode(all, "N02BA01").getDrugClass(), byCode(all, "M01AE01").getDrugClass());
		assertEquals("Salicylic acid and derivatives", byCode(all, "N02BA01").getDrugClass());
	}

	@Test
	public void atcEntriesCarryNoDosingInteractionOrContraindicationRules() throws IOException {
		// ATC is a classification, not a rulebook — these stay empty (documented in ADR 24).
		DrugReference ibuprofen = byCode(parseSample(), "M01AE01");
		assertTrue(ibuprofen.getAgeBands().isEmpty());
		assertTrue(ibuprofen.getInteractions().isEmpty());
		assertTrue(ibuprofen.getContraindications().isEmpty());
		assertFalse(ibuprofen.getAtcCodes().isEmpty());
	}
}
