/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ModelManifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The derived tier's precision figure (issue #480, ADR Decision 111) was measured over ONE knowledge
 * base, and is only true of it. This fails the build on the three changes below, so a refresh that makes
 * any of them re-opens the measurement instead of silently leaving a stale figure in the decision.
 *
 * <p>What each check reads:
 * <ul>
 * <li>the POPULATION — the chains the real loader keeps, counted through
 * {@link DrugReferenceTestSupport#shippedEntries()};</li>
 * <li>each adjudicated LINK still being one — its {@code (cause_note_id, condition)} pair still occurring in
 * the raw {@code derived_interactions} table, which the loader reads the chain from but does not keep the
 * note id of;</li>
 * <li>the TEXT each verdict was given on — the SHA-256 of every adjudicated note, read from the raw
 * {@code disease_notes} table, which the module does not load. A rewritten note leaves every count
 * unchanged, which is why this is here.</li>
 * </ul>
 * A refresh that moves chains between links while keeping the total, or that changes which links are the
 * heaviest, passes all three and would still change the figure; that is not checked.
 *
 * <p>It re-derives nothing: the verdicts are data, recorded in
 * {@code api/src/test/resources/eval/derived-tier-precision-sample.json}, and nothing here judges a note.
 */
public class DerivedTierPrecisionSampleTest {

	private static final String SAMPLE = "/eval/derived-tier-precision-sample.json";

	@Test
	public void theShippedKnowledgeBaseIsTheOneThePrecisionFigureWasMeasuredOver() throws Exception {
		JsonNode sample = read(SAMPLE);

		// Counted before the raw tree is read, so the loader's own tree of the same file is garbage by then.
		int keptChains = 0;
		for (DrugReference rated : DrugReferenceTestSupport.shippedEntries()) {
			keptChains += rated.getConditionMediatedRisks().size();
		}
		assertEquals(sample.path("population").path("keptChains").asInt(), keptChains,
			"the loader keeps a different set of derived chains than the precision figure was measured over;"
					+ " re-measure it (ADR Decision 111) before changing the recorded population");

		// The file the loader reads, by the loader's own name for it.
		JsonNode kb = read(DdiDrugReferenceSource.CLASSPATH_DEFAULT);
		// The adjudicated links, each removed as the raw table is walked; what is left no longer occurs there.
		Set<String> missing = new LinkedHashSet<String>();
		for (String group : new String[] { "items", "controls" }) {
			for (JsonNode item : sample.path(group)) {
				missing.add(item.path("noteId").asText() + "\t" + item.path("condition").asText());
			}
		}
		for (JsonNode row : kb.path("derived_interactions")) {
			missing.remove(row.get(3).asText() + "\t" + row.get(5).asText());
		}
		assertTrue(missing.isEmpty(),
			"adjudicated links no longer occurring in the raw derived_interactions table: " + missing);
		JsonNode notes = kb.path("disease_notes");
		int checked = 0;
		for (String group : new String[] { "items", "controls" }) {
			for (JsonNode item : sample.path(group)) {
				String noteId = item.path("noteId").asText();
				String condition = item.path("condition").asText();
				JsonNode note = notes.path(noteId);
				assertTrue(note.has("text"), "adjudicated note " + noteId + " is gone");
				assertEquals(item.path("noteSha256").asText(),
					ModelManifest.sha256(note.path("text").asText().getBytes(StandardCharsets.UTF_8)),
					"note " + noteId + " was rewritten after it was adjudicated for " + condition);
				checked++;
			}
		}
		assertTrue(checked > 0, "precondition: the recorded sample carries adjudicated items");
	}

	private static JsonNode read(String resource) throws Exception {
		try (InputStream in = DerivedTierPrecisionSampleTest.class.getResourceAsStream(resource)) {
			assertNotNull(in, resource + " is not on the test classpath");
			return new ObjectMapper().readTree(in);
		}
	}
}
