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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A test fixture that calls itself a verbatim slice of the shipped knowledge base still is one.
 *
 * <p><b>Why a case rather than trust.</b> The cases that read such a slice build their answers out of
 * the record the injector renders from it and compare them against that same record, so they are
 * self-relative: edit a row and they stay green while the record they were written about no longer
 * exists. That matters because a decision can quote the rendered record — ADR Decision 59 quotes the
 * one this file's slice produces, character for character — so an edit here can make a published
 * measurement false on a green build. Measured: changing {@code H02AB09} to {@code H03AB09} in the
 * slice leaves every case in {@code ReferenceProseFidelityTest} green.
 *
 * <p><b>Whole rows, not projections.</b> An earlier version of this compared four accessors and let
 * seven kinds of edit through — {@code rxcui}, {@code drugbank_id}, a {@code ciel} entry, the
 * interaction row's severity, the interaction array emptied, a fabricated mechanism text, and the
 * metadata note rewritten to say the file is hand-authored. It compares the JSON now: every field of
 * every row, the interaction rows, and the mechanism entries those reference. What it deliberately
 * does NOT compare is the metadata note, which is prose about the slice rather than data from it.
 *
 * <p>It reads the shipped file directly rather than through {@code DdiDrugReferenceSource}, because
 * the question is whether the BYTES were copied and a parse is exactly what would hide an edit the
 * parser normalises away. It is not the parsed-entry accessors' rule either: {@code getId()} and
 * {@code substanceKey()} are resolved per substance-name FAMILY over the whole dataset
 * ({@code DdiDrugReferenceSource.substanceIds} is canonical), so a slice and the 2283-row file
 * legitimately disagree about them and asserting one would fail on a faithful slice.
 */
public class SlicedReferenceRowProvenanceTest {

	/** The shipped dataset, on the main classpath. */
	private static final String SHIPPED = "chartsearchai/ddi-knowledge-base.json";

	/** Every slice claiming to be cut from it. One today; a second belongs on this list rather than
	 *  in a file of its own. */
	private static final List<String> SLICES = new ArrayList<String>(java.util.Arrays.asList(
			"chartsearchai-test/ddi-issue338-allergy-cross-reactivity.json"));

	@Test
	public void everySlicedFixtureIsFieldForFieldTheShippedKnowledgeBases() throws Exception {
		JsonNode shipped = read(SHIPPED);
		for (String slice : SLICES) {
			JsonNode cut = read(slice);
			Set<String> ids = new LinkedHashSet<String>();
			assertTrue(cut.path("drugs").size() > 0, slice + " must carry at least one row");
			for (JsonNode row : cut.path("drugs")) {
				String id = row.path("id").asText(null);
				assertNotNull(id, slice + " carries a row with no id");
				ids.add(id);
				assertEquals(drugRow(shipped, id), row,
						slice + "'s row " + id + " must be the shipped dataset's own, field for field");
			}
			assertEquals(interactionsWithin(shipped, ids), interactionsWithin(cut, ids),
					slice + " must carry every shipped interaction row falling wholly inside it, and no "
							+ "other — a severity or a mechanism id edited here changes the rendered "
							+ "record without changing any drug row");
			for (JsonNode interaction : interactionsWithin(cut, ids)) {
				String group = interaction.get(3).asText();
				assertEquals(shipped.path("mechanisms").path(group), cut.path("mechanisms").path(group),
						slice + "'s mechanism group " + group + " must be the shipped dataset's own");
			}
			assertFalse(cut.path("metadata").path("note").asText("").isEmpty(),
					slice + " must say in its own metadata what it is a slice of and when it was cut");
		}
	}

	/** @return the shipped row filed under {@code id}, failing rather than returning null so an id the
	 *          dataset does not carry is a named failure and not a silent pass. */
	private static JsonNode drugRow(JsonNode shipped, String id) {
		for (JsonNode row : shipped.path("drugs")) {
			if (id.equals(row.path("id").asText(null))) {
				return row;
			}
		}
		throw new AssertionError("the shipped knowledge base carries no row with id " + id);
	}

	/** @return the interaction rows of {@code dataset} whose BOTH partners are in {@code ids}, in the
	 *          dataset's own order — which is what "falling wholly inside the slice" means. */
	private static List<JsonNode> interactionsWithin(JsonNode dataset, Set<String> ids) {
		List<JsonNode> out = new ArrayList<JsonNode>();
		for (JsonNode row : dataset.path("interactions")) {
			if (ids.contains(row.get(0).asText()) && ids.contains(row.get(1).asText())) {
				out.add(row);
			}
		}
		return out;
	}

	private static JsonNode read(String classpathResource) throws Exception {
		try (InputStream in = SlicedReferenceRowProvenanceTest.class.getClassLoader()
				.getResourceAsStream(classpathResource)) {
			assertNotNull(in, classpathResource + " should be on the classpath");
			return new ObjectMapper().readTree(in);
		}
	}
}
