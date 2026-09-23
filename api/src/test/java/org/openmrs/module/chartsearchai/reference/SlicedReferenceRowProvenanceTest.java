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
 * <p><b>Why a case rather than trust.</b> The cases that read such a slice build their answers out
 * of the record the injector renders from it and compare them against that same record, so they are
 * self-relative: edit a row and they stay green while the record they were written about no longer
 * exists. That matters because a decision can quote the rendered record — ADR Decision 59 quotes
 * the one {@code ddi-issue338-allergy-cross-reactivity.json} produces, character for character — so
 * an edit to a slice can make a published measurement false on a green build. Measured: editing a
 * row's {@code rxcui} leaves every case in {@code ReferenceProseFidelityTest} green, because it
 * changes nothing the injector renders. Some edits those cases DO catch — an ATC code, because it
 * changes the sentence they are built around — which is why the claim is about the file being a
 * slice and not about the cases being blind.
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
 * <p><b>Derived chains, where a slice carries them.</b> Where a slice carries
 * {@code derived_interactions}, the rows of it falling wholly inside the slice are compared too
 * (issue #503). A slice that does not carry that table is not held to the shipped rows falling inside
 * it, and loads with no condition-mediated chain where the shipped dataset may attach some.
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

	/** The table of derived (condition-mediated) chains, which the loader reads. */
	private static final String DERIVED = "derived_interactions";

	/** The slices this guard covers, which is NOT every fixture whose metadata calls itself one —
	 *  {@code chartsearchai-test} holds dozens, several of which declare a deliberate deviation and
	 *  would fail here correctly. The list is the coverage: a new slice is added to it rather than
	 *  given a guard of its own, and an existing fixture is added only after someone has read its own
	 *  note for a declared deviation. The three slices the guard was written for come first and every
	 *  other entry follows in alphabetical order, which {@link #theListIsTheFirstThreeThenAlphabetical}
	 *  holds it to, so a new entry goes where that order puts it. */
	private static final List<String> SLICES = java.util.Arrays.asList(
			"chartsearchai-test/ddi-issue338-allergy-cross-reactivity.json",
			"chartsearchai-test/ddi-brand-name-aliases.json",
			"chartsearchai-test/ddi-class-only-and-rule-one-partner.json",
			"chartsearchai-test/ddi-alias-drug-names.json",
			"chartsearchai-test/ddi-alias-names-another-substance.json",
			"chartsearchai-test/ddi-allergen-name-claim.json",
			"chartsearchai-test/ddi-canonical-subject-label.json",
			"chartsearchai-test/ddi-class-partner-canonical-row.json",
			"chartsearchai-test/ddi-combination-allergen.json",
			"chartsearchai-test/ddi-combination-two-rules-one-note.json",
			"chartsearchai-test/ddi-contraindication-subject-label.json",
			"chartsearchai-test/ddi-crossarm-canonical-duplicate.json",
			"chartsearchai-test/ddi-duplicate-therapy-self.json",
			"chartsearchai-test/ddi-folded-minor-class-pair.json",
			"chartsearchai-test/ddi-folded-moderate-class-pair.json",
			"chartsearchai-test/ddi-interaction-route-variants.json",
			"chartsearchai-test/ddi-multicode-class-chip.json",
			"chartsearchai-test/ddi-one-order-two-order-entries.json",
			"chartsearchai-test/ddi-presentation-alias-gap.json",
			"chartsearchai-test/ddi-presentation-moiety.json",
			"chartsearchai-test/ddi-question-pair-subject.json",
			"chartsearchai-test/ddi-residual-atc-bucket.json",
			"chartsearchai-test/ddi-self-interaction.json",
			"chartsearchai-test/ddi-substance-in-several-orders.json",
			"chartsearchai-test/ddi-substance-name-contradicted-by-the-bridge.json",
			"chartsearchai-test/ddi-substance-name-row.json",
			"chartsearchai-test/ddi-substance-rule-asymmetry.json");

	@Test
	public void everySliceOnTheListIsFieldForFieldTheShippedDatasets() throws Exception {
		assertFalse(SLICES.isEmpty(),
				"the list is the coverage, so an empty one is this case passing over nothing");
		JsonNode shipped = read(SHIPPED);
		for (String slice : SLICES) {
			JsonNode cut = read(slice);
			Set<String> ids = new LinkedHashSet<String>();
			assertTrue(cut.path("drugs").size() > 0, slice + " must carry at least one row");
			int previous = -1;
			for (JsonNode row : cut.path("drugs")) {
				String id = row.path("id").asText(null);
				assertNotNull(id, slice + " carries a row with no id");
				ids.add(id);
				assertEquals(drugRow(shipped, id), row,
						slice + "'s row " + id + " must be the shipped dataset's own, field for field");
				int at = shippedPosition(shipped, id);
				assertTrue(at > previous, slice + "'s rows must be in the shipped dataset's own order, "
						+ "which its metadata note is free to state; " + id + " is out of it");
				previous = at;
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
			if (cut.has(DERIVED)) {
				assertEquals(derivedWithin(shipped, ids), derivedWithin(cut, ids),
						slice + " carries " + DERIVED + ", so the rows of it falling wholly inside the slice must "
								+ "be the shipped rows falling wholly inside it and no other — the loader may attach "
								+ "such a row to the rated drug's entry (issue #503)");
			}
			assertFalse(cut.path("metadata").path("note").asText("").isEmpty(),
					slice + " must carry a metadata note — what it says is for a reader, and only that "
							+ "it says something is checkable here");
		}
	}

	@Test
	public void theListIsTheFirstThreeThenAlphabetical() {
		List<String> rest = new ArrayList<String>(SLICES.subList(3, SLICES.size()));
		Collections.sort(rest);
		assertEquals(rest, SLICES.subList(3, SLICES.size()),
				"after the first three, SLICES is kept in alphabetical order, so a new entry has one place to go");
	}

	/** @return where {@code id} sits among the shipped dataset's rows, so a slice can be held to their
	 *          order as well as to their content — its metadata note is free to state that order, and
	 *          nothing else here would notice two rows swapped. */
	private static int shippedPosition(JsonNode shipped, String id) {
		int at = 0;
		for (JsonNode row : shipped.path("drugs")) {
			if (id.equals(row.path("id").asText(null))) {
				return at;
			}
			at++;
		}
		throw new AssertionError("the shipped knowledge base carries no row with id " + id);
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

	/** @return the {@value #DERIVED} rows of {@code dataset} whose cause drug and rated drug are BOTH in
	 *          {@code ids}, in the dataset's own order — the two ids
	 *          {@code DdiDrugReferenceSource.attachConditionMediatedRisks} resolves each row through, and
	 *          drops the row where the file carries either not. */
	private static List<JsonNode> derivedWithin(JsonNode dataset, Set<String> ids) {
		return rowsWithin(dataset, DERIVED, 0, 4, ids);
	}

	/** @return the interaction rows of {@code dataset} whose BOTH partners are in {@code ids}, in the
	 *          dataset's own order — which is what "falling wholly inside the slice" means. */
	private static List<JsonNode> interactionsWithin(JsonNode dataset, Set<String> ids) {
		return rowsWithin(dataset, "interactions", 0, 1, ids);
	}

	/** @return the rows of {@code dataset}'s {@code table} whose two id columns are BOTH in {@code ids},
	 *          in the dataset's own order. */
	private static List<JsonNode> rowsWithin(JsonNode dataset, String table, int first, int second,
			Set<String> ids) {
		List<JsonNode> out = new ArrayList<JsonNode>();
		for (JsonNode row : dataset.path(table)) {
			if (ids.contains(row.get(first).asText()) && ids.contains(row.get(second).asText())) {
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
