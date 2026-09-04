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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A listed test fixture that calls itself a verbatim slice of the shipped knowledge base still is one.
 *
 * <p><b>Why a case rather than trust.</b> The cases that read such a slice build their answers out of
 * the record the injector renders from it and compare them against that same record, so they are
 * self-relative: edit a row and they stay green while the record they were written about no longer
 * exists. That matters because a decision can quote the rendered record — ADR Decision 59 quotes the
 * one this file's slice produces, character for character — so an edit here can make a published
 * measurement false on a green build. Measured: editing a row's {@code rxcui} leaves every case in
 * {@code ReferenceProseFidelityTest} green, because it changes nothing the injector renders. Some
 * edits those cases DO catch — an ATC code, because it changes the sentence they are built around —
 * which is why the claim is about the file being a slice and not about the cases being blind.
 *
 * <p><b>Whole rows, not projections.</b> An earlier version of this compared four accessors and let
 * seven kinds of edit through — {@code rxcui}, {@code drugbank_id}, a {@code ciel} entry, the
 * interaction row's severity, the interaction array emptied, a fabricated mechanism text, and the
 * metadata note rewritten to say the file is hand-authored. It compares the JSON now: every field of
 * every row, the interaction rows falling wholly inside it, and the mechanism entries those
 * reference — six of those seven. The seventh is the metadata note, which stays uncompared
 * deliberately: it is prose about the slice rather than data from it, so a note rewritten to say the
 * file is hand-authored still passes, and only its presence is checked.
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

	/** The slices this guard covers, which is NOT every fixture whose metadata calls itself one —
	 *  {@code chartsearchai-test} holds dozens, several of which declare a deliberate deviation and
	 *  would fail here correctly. The list is the coverage: a new slice is added to it rather than
	 *  given a guard of its own, and an existing fixture is added only after someone has read its own
	 *  note for a declared deviation. */
	private static final List<String> SLICES = Collections.singletonList(
			"chartsearchai-test/ddi-issue338-allergy-cross-reactivity.json");

	@Test
	public void everySliceOnTheListIsFieldForFieldTheShippedDatasets() throws Exception {
		assertFalse(SLICES.isEmpty(),
				"the list is the coverage, so an empty one is this case passing over nothing");
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
					slice + " must carry the shipped interaction rows falling wholly inside it, and no "
							+ "other such row — a severity or a mechanism id edited here changes the "
							+ "rendered record without changing any drug row. A row naming a partner "
							+ "OUTSIDE the slice is not compared and the parser drops it");
			for (JsonNode interaction : interactionsWithin(cut, ids)) {
				String group = interaction.get(3).asText();
				assertEquals(shipped.path("mechanisms").path(group), cut.path("mechanisms").path(group),
						slice + "'s mechanism group " + group + " must be the shipped dataset's own");
			}
			assertFalse(cut.path("metadata").path("note").asText("").isEmpty(),
					slice + " must carry a metadata note — what it says is for a reader, and only that "
							+ "it says something is checkable here");
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
