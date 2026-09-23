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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ModelManifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The derived tier's precision figure (issue #480, ADR Decision 111) was measured over ONE knowledge
 * base, and is only true of it. This fails the build when the shipped knowledge base stops being that
 * one, so a refresh re-opens the measurement instead of silently leaving a stale figure in the decision.
 *
 * <p>Every weight the figure rests on is re-derived here from the knowledge base rather than read from
 * the sample file (issue #485): the chains the real loader keeps
 * ({@link DrugReferenceTestSupport#shippedEntries()}), each joined back to the one raw
 * {@code derived_interactions} row it was read from, because the loader does not keep the
 * {@code cause_note_id} a LINK is keyed on. The join is on everything a
 * {@link DrugReference.ConditionMediatedRisk} carries plus the rated entry, and is itself checked —
 * each kept chain must match exactly one raw row. It filters no raw row on severity: which rows count
 * is decided by what the loader kept, so its {@code Major} gate is not restated. What it does have to
 * know of the loader is where an entry's id comes from (a drug row's own id or its rxcui); a drug row two
 * entries carrying its name could be is reported rather than guessed, and one no entry carries joins
 * nothing, so a chain the loader kept through it is reported as unjoined.
 *
 * <p>Over that enumeration it checks:
 * <ul>
 * <li>the POPULATION — its kept chains, its links and its (link, rated substance) pairs, and that the
 * census and the sample partition it as the sample file says;</li>
 * <li>each adjudicated LINK's own weights — its kept chains and the distinct rated substances among
 * them ({@link DrugReference#substanceGroupKey()}), carried by every item and by no control, each item
 * filed in the {@code census} or the {@code sample} stratum and no control in either, and for a control
 * that its link is still kept;</li>
 * <li>the CENSUS — still the heaviest links, by the rule the sample file records;</li>
 * <li>the TEXT each verdict was given on — the SHA-256 of every adjudicated note, read from the raw
 * {@code disease_notes} table, which the module does not load. A rewritten note leaves every count
 * unchanged, which is why this is here.</li>
 * </ul>
 * Not checked: whether the sampled links are still the ones {@code random.Random(480)} would draw from
 * the rest (Python's generator is not re-derived here), and a move of chains among links no item
 * adjudicates that keeps every count above.
 *
 * <p>It re-derives no verdict: those are data, recorded in
 * {@code api/src/test/resources/eval/derived-tier-precision-sample.json}, and nothing here judges a note.
 */
public class DerivedTierPrecisionSampleTest {

	private static final String SAMPLE = "/eval/derived-tier-precision-sample.json";

	private static JsonNode sample;

	/** Kept chains per link, {@code cause_note_id + "\t" + condition}, in no particular order. */
	private static Map<String, Integer> chainsByLink;

	/** The distinct rated substances per link. */
	private static Map<String, Set<Object>> ratedSubstancesByLink;

	/** Kept chains that matched no raw row, or more than one, with how many they matched. */
	private static Map<String, Integer> unjoinedChains;

	/** Drug rows that more than one built entry carrying their name could be. */
	private static Set<String> unresolvedDrugs;

	private static int keptChains;

	/** The raw text of every adjudicated note, by note id; absent where the note is gone. */
	private static Map<String, String> adjudicatedNotes;

	@BeforeAll
	public static void enumerateTheLinksTheLoaderKeeps() throws Exception {
		sample = read(SAMPLE);

		// Read off the entries before the raw tree is parsed, keeping only ids, names and substance keys.
		// Where an entry publishes no substance, substanceGroupKey() is the entry itself, so its id stands in
		// for it: ids are unique, and a String cannot equal the List a substance key is, so the distinct
		// count is unchanged and no loaded entry is retained.
		Map<String, String> entryNames = new HashMap<String, String>();
		List<String> chainKeys = new ArrayList<String>();
		List<Object> chainSubstances = new ArrayList<Object>();
		for (DrugReference rated : DrugReferenceTestSupport.shippedEntries()) {
			entryNames.put(rated.getId(), rated.getName());
			for (DrugReference.ConditionMediatedRisk risk : rated.getConditionMediatedRisks()) {
				chainKeys.add(chainKey(rated.getId(), risk.getCause().getId(), risk.getCauseCondition(),
					risk.getCauseSeverity(), risk.getCondition(), risk.getSeverity()));
				Object substance = rated.substanceGroupKey();
				chainSubstances.add(substance instanceof DrugReference ? rated.getId() : substance);
			}
		}
		keptChains = chainKeys.size();

		// The file the loader reads, by the loader's own name for it.
		JsonNode kb = read(DdiDrugReferenceSource.CLASSPATH_DEFAULT);
		Map<String, String> entryIdByRawId = new HashMap<String, String>();
		unresolvedDrugs = new LinkedHashSet<String>();
		for (JsonNode drug : kb.path("drugs")) {
			String rawId = drug.path("id").asText();
			String name = drug.path("name").asText();
			List<String> carrying = new ArrayList<String>(2);
			for (String candidate : new String[] { rawId, drug.path("rxcui").asText() }) {
				if (name.equals(entryNames.get(candidate)) && !carrying.contains(candidate)) {
					carrying.add(candidate);
				}
			}
			if (carrying.size() == 1) {
				entryIdByRawId.put(rawId, carrying.get(0));
			} else if (carrying.size() > 1) {
				unresolvedDrugs.add(rawId + " (" + name + ") -> " + carrying);
			}
		}

		Set<String> kept = new HashSet<String>(chainKeys);
		Map<String, List<String>> notesByChainKey = new HashMap<String, List<String>>();
		for (JsonNode row : kb.path("derived_interactions")) {
			String cause = entryIdByRawId.get(row.path(0).asText());
			String rated = entryIdByRawId.get(row.path(4).asText());
			if (cause == null || rated == null) {
				continue;
			}
			String key = chainKey(rated, cause, row.path(1).asText(), row.path(2).asText(), row.path(5).asText(),
				row.path(6).asText());
			if (kept.contains(key)) {
				notesByChainKey.computeIfAbsent(key, k -> new ArrayList<String>())
						.add(row.path(3).asText() + "\t" + row.path(5).asText());
			}
		}

		chainsByLink = new HashMap<String, Integer>();
		ratedSubstancesByLink = new HashMap<String, Set<Object>>();
		unjoinedChains = new LinkedHashMap<String, Integer>();
		for (int i = 0; i < chainKeys.size(); i++) {
			List<String> links = notesByChainKey.get(chainKeys.get(i));
			if (links == null || links.size() != 1) {
				unjoinedChains.put(chainKeys.get(i), links == null ? 0 : links.size());
				continue;
			}
			String link = links.get(0);
			chainsByLink.merge(link, 1, Integer::sum);
			ratedSubstancesByLink.computeIfAbsent(link, k -> new HashSet<Object>()).add(chainSubstances.get(i));
		}

		adjudicatedNotes = new HashMap<String, String>();
		JsonNode notes = kb.path("disease_notes");
		for (JsonNode item : adjudicated()) {
			JsonNode note = notes.path(item.path("noteId").asText());
			if (note.has("text")) {
				adjudicatedNotes.put(item.path("noteId").asText(), note.path("text").asText());
			}
		}
	}

	@Test
	public void everyKeptChainJoinsTheOneRawRowItWasReadFrom() {
		assertTrue(keptChains > 0, "precondition: the shipped knowledge base carries derived chains");
		assertTrue(unresolvedDrugs.isEmpty(),
			"drug rows that several built entries carrying their name could be: " + unresolvedDrugs);
		assertTrue(unjoinedChains.isEmpty(), "kept chains not matching exactly one raw derived_interactions row"
				+ " (chain -> rows matched), so their link cannot be recovered; re-measure (ADR Decision 111): "
				+ unjoinedChains);
	}

	@Test
	public void theLoaderKeepsThePopulationThePrecisionFigureWasMeasuredOver() {
		JsonNode population = sample.path("population");
		String remeasure = "; re-measure it (ADR Decision 111) before changing the recorded population";
		assertEquals(population.path("keptChains").asInt(), keptChains,
			"the loader keeps a different number of derived chains than the precision figure was measured over"
					+ remeasure);
		assertEquals(population.path("links").asInt(), chainsByLink.size(),
			"the kept chains fall on a different number of links" + remeasure);
		int ratedSubstances = 0;
		for (Set<Object> substances : ratedSubstancesByLink.values()) {
			ratedSubstances += substances.size();
		}
		assertEquals(population.path("ratedSubstances").asInt(), ratedSubstances,
			"the links carry a different number of (link, rated substance) pairs" + remeasure);

		JsonNode census = population.path("census");
		JsonNode drawn = population.path("sample");
		int censusChains = 0;
		int censusLinks = 0;
		int sampledLinks = 0;
		for (JsonNode item : sample.path("items")) {
			if ("census".equals(item.path("stratum").asText())) {
				censusChains += item.path("keptChains").asInt();
				censusLinks++;
			} else if ("sample".equals(item.path("stratum").asText())) {
				sampledLinks++;
			}
		}
		assertEquals(census.path("links").asInt(), censusLinks, "census items against the recorded census");
		assertEquals(census.path("keptChains").asInt(), censusChains,
			"the census items' kept chains against the recorded census total");
		assertEquals(drawn.path("links").asInt(), sampledLinks, "sampled items against the recorded sample");
		assertEquals(drawn.path("ofLinks").asInt(), chainsByLink.size() - censusLinks,
			"the sample was drawn from every link outside the census");
		assertEquals(drawn.path("ofKeptChains").asInt(), keptChains - censusChains,
			"the sampled stratum's chains are every kept chain outside the census");
	}

	@Test
	public void everyAdjudicatedLinkCarriesTheWeightsItWasRecordedWith() {
		List<String> wrong = new ArrayList<String>();
		Set<String> seen = new HashSet<String>();
		for (String group : new String[] { "items", "controls" }) {
			// Decided by the group an entry is filed in, never by which fields it happens to carry, so a
			// weight deleted from an item is reported rather than skipped.
			boolean weighted = "items".equals(group);
			for (JsonNode item : sample.path(group)) {
				String link = link(item);
				if (!seen.add(link)) {
					wrong.add(link + ": adjudicated twice");
				}
				String stratum = item.path("stratum").asText(null);
				if (weighted ? !"census".equals(stratum) && !"sample".equals(stratum) : stratum != null) {
					wrong.add(link + ": stratum " + stratum);
				}
				if (weighted ? !item.path("keptChains").isInt() || !item.path("ratedSubstances").isInt()
						: item.has("keptChains") || item.has("ratedSubstances")) {
					wrong.add(link + (weighted ? ": an item without both weights" : ": a control carrying a weight"));
				}
				Integer chains = chainsByLink.get(link);
				if (chains == null) {
					wrong.add(link + ": no longer a link the loader keeps a chain for");
				} else if (weighted && item.path("keptChains").isInt() && item.path("ratedSubstances").isInt()) {
					int substances = ratedSubstancesByLink.get(link).size();
					if (chains != item.path("keptChains").asInt()
							|| substances != item.path("ratedSubstances").asInt()) {
						wrong.add(link + ": recorded " + item.path("keptChains").asInt() + " chains / "
								+ item.path("ratedSubstances").asInt() + " rated substances, the loader keeps " + chains
								+ " / " + substances);
					}
				}
			}
		}
		assertTrue(wrong.isEmpty(), "adjudicated links whose weights no longer describe the shipped knowledge"
				+ " base; re-measure (ADR Decision 111): " + wrong);
	}

	@Test
	public void theCensusIsStillTheHeaviestLinks() {
		// The file's own rule: most kept chains, ties by numeric note id then condition.
		List<String> ranked = new ArrayList<String>(chainsByLink.keySet());
		List<String> unordered = new ArrayList<String>();
		for (String link : ranked) {
			if (!link.substring(0, link.indexOf('\t')).matches("\\d+")) {
				unordered.add(link);
			}
		}
		assertTrue(unordered.isEmpty(), "links whose note id the census rule cannot order numerically;"
				+ " re-measure (ADR Decision 111): " + unordered);
		ranked.sort(Comparator.<String> comparingInt(l -> -chainsByLink.get(l))
				.thenComparingLong(l -> Long.parseLong(l.substring(0, l.indexOf('\t'))))
				.thenComparing(l -> l.substring(l.indexOf('\t') + 1)));
		Set<String> census = new LinkedHashSet<String>();
		for (JsonNode item : sample.path("items")) {
			if ("census".equals(item.path("stratum").asText())) {
				census.add(link(item));
			}
		}
		assertTrue(ranked.size() >= census.size(), "precondition: at least as many links as census items");
		Set<String> entered = new LinkedHashSet<String>(ranked.subList(0, census.size()));
		entered.removeAll(census);
		Set<String> left = new LinkedHashSet<String>(census);
		left.removeAll(ranked.subList(0, census.size()));
		assertTrue(entered.isEmpty() && left.isEmpty(), "the census is no longer the " + census.size()
				+ " heaviest links; re-measure (ADR Decision 111). Now among them: " + entered + "; no longer: " + left);
	}

	@Test
	public void everyAdjudicatedNoteIsTheTextItWasJudgedOn() throws Exception {
		int checked = 0;
		for (JsonNode item : adjudicated()) {
			String noteId = item.path("noteId").asText();
			String text = adjudicatedNotes.get(noteId);
			assertNotNull(text, "adjudicated note " + noteId + " is gone");
			assertEquals(item.path("noteSha256").asText(),
				ModelManifest.sha256(text.getBytes(StandardCharsets.UTF_8)),
				"note " + noteId + " was rewritten after it was adjudicated for " + item.path("condition").asText());
			checked++;
		}
		assertTrue(checked > 0, "precondition: the recorded sample carries adjudicated items");
	}

	private static List<JsonNode> adjudicated() {
		List<JsonNode> all = new ArrayList<JsonNode>();
		for (String group : new String[] { "items", "controls" }) {
			for (JsonNode item : sample.path(group)) {
				all.add(item);
			}
		}
		return all;
	}

	private static String link(JsonNode item) {
		return item.path("noteId").asText() + "\t" + item.path("condition").asText();
	}

	private static String chainKey(String rated, String cause, String causeCondition, String causeSeverity,
			String condition, String severity) {
		return rated + "\t" + cause + "\t" + causeCondition + "\t" + causeSeverity + "\t" + condition + "\t"
				+ severity;
	}

	private static JsonNode read(String resource) throws Exception {
		try (InputStream in = DerivedTierPrecisionSampleTest.class.getResourceAsStream(resource)) {
			assertNotNull(in, resource + " is not on the test classpath");
			return new ObjectMapper().readTree(in);
		}
	}
}
