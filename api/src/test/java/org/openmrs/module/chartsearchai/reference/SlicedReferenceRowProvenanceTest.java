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

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ModuleSourceRoot;

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
 * {@value #DERIVED}, the rows of it falling wholly inside the slice are compared too
 * (issue #503). A slice that does not carry that table is not held to the shipped rows falling inside
 * it, and loads with no condition-mediated chain where the shipped dataset may attach some — so a test
 * that turns the derived tier on may not load it, which
 * {@link #aTestThatTurnsTheDerivedTierOnLoadsOnlyListedSlicesThatCarryTheDerivedTable} holds (issue #505).
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

	/** The table of pairwise interaction rows. */
	private static final String INTERACTIONS = "interactions";

	/** The table of derived (condition-mediated) chains, which the loader reads. */
	private static final String DERIVED = "derived_interactions";

	/** The classpath directory the test fixtures live in, and the prefix every {@link #SLICES} entry carries. */
	private static final String FIXTURE_DIR = "chartsearchai-test/";

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

	/**
	 * A test that turns the derived tier on reads condition-mediated chains only from fixtures that are
	 * held to the shipped ones (issue #505). A slice that does not carry {@value #DERIVED} loads with no
	 * chain where the shipped dataset would attach some — driving {@code DdiDrugReferenceSource.parse}
	 * over each listed slice with and without the shipped rows falling inside it spliced in, five of the
	 * list as it stood on 2026-09-24 gain chains — so a derived-on case over one of them measures a tier
	 * the slice has silently emptied.
	 * The listed slices that DO carry the table are compared row for row by
	 * {@link #everySliceOnTheListIsFieldForFieldTheShippedDatasets}, which is why the rule is "listed
	 * AND carrying it" rather than "carrying it".
	 *
	 * <p>Structural, because what it pins is a pairing of two things a test does — sets a global
	 * property and loads a fixture. A test source "turns the tier on" when it names the property's
	 * constant or its value, a mention in a comment included, so this fails closed. The fixtures it
	 * counts as loaded are those {@code FixtureReach} finds its text naming: a string literal, a test
	 * class's compile-time {@code String} constant, or a test class's method whose body reaches one,
	 * transitively — and those of any test class its {@code extends} or {@code implements} clause names.
	 * Among what it cannot see: a constant of a type other than {@code String}, a fixture
	 * name assembled at runtime, a member inherited from a class the file never names, a method called
	 * on an object whose class the file never names, a walk over the fixture directory, the property set
	 * by an XML dataset, an omod test class's constants beyond what omod's last build compiled, and
	 * the callers of a helper that sets the property on their behalf — it is the helper's own class that
	 * is flagged, with the fixtures that class names.
	 */
	@Test
	public void aTestThatTurnsTheDerivedTierOnLoadsOnlyListedSlicesThatCarryTheDerivedTable() throws Exception {
		Path api = ModuleSourceRoot.apiRoot();
		Set<String> fixtures = new TreeSet<String>();
		try (DirectoryStream<Path> dir = Files.newDirectoryStream(api.resolve("src/test/resources/" + FIXTURE_DIR),
				"*.json")) {
			for (Path fixture : dir) {
				fixtures.add(fixture.getFileName().toString());
			}
		}
		Map<Path, String> sources = new TreeMap<Path, String>();
		Map<String, String> byClass = new HashMap<String, String>();
		for (Path root : Arrays.asList(api.resolve("src/test/java"), ModuleSourceRoot.omodRoot().resolve("src/test/java"))) {
			try (Stream<Path> walk = Files.walk(root)) {
				for (Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
					String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
					sources.put(file, text);
					// Resolved by simple name; two classes sharing one are merged, which can only over-report.
					byClass.merge(simpleName(file), text, (a, b) -> a + "\n" + b);
				}
			}
		}
		List<Path> classRoots = new ArrayList<Path>();
		for (Path root : Arrays.asList(api.resolve("target/test-classes"),
				ModuleSourceRoot.omodRoot().resolve("target/test-classes"))) {
			if (Files.isDirectory(root)) {
				classRoots.add(root);
			}
		}
		FixtureReach reach = new FixtureReach(byClass, classRoots, fixtures);
		assertEquals(Collections.singleton("ddi-knowledge-base-sample.json"),
				reach.of("DrugReferenceTestSupport", "ddinterServiceWithGroups"),
				"a helper method must resolve to the fixture its body reaches through another method, or the scan "
						+ "passes over the commonest way a case loads data");
		assertEquals(Collections.singleton("ddi-bridged-concept-tied-token.json"),
				reach.of("DrugReferenceTestSupport", "DDI_BRIDGED_CONCEPT_TIED_TOKEN"),
				"a constant must resolve from the compiled class, or the scan reads no constant at all");
		assertEquals(Collections.singleton("ddi-self-interaction.json"), reach.of("SelfInteractionTest", "FIXTURE"),
				"a constant initialised from another class's constant must resolve through it");
		assertTrue(reach.named(byClass.get("ReferenceRecordRowAttributionTest")).contains("ddi-route-variants.json"),
				"a class that reaches another class's constant only by naming that class must resolve to its fixture");
		assertTrue(reach.named("class Probe extends ReferenceRecordRowAttributionTest {}").contains("ddi-route-variants.json"),
				"a class must inherit what the class it extends loads, which it runs without naming it");
		assertTrue(reach.named("class Probe implements ReferenceRecordRowAttributionTest.Nested {}")
				.contains("ddi-route-variants.json"),
				"a class must inherit what a nested supertype's outer class loads, through implements as well as extends");
		assertFalse(reach.named("class Probe { java.util.List<? extends Object> x = DrugReferenceTestSupport.set(); }")
				.contains("ddi-knowledge-base-sample.json"),
				"a wildcard bound is not a supertype, so it must not pull in the whole file of a class named after it");

		Set<String> derivedOn = new TreeSet<String>();
		Map<String, Set<String>> refused = new TreeMap<String, Set<String>>();
		for (Map.Entry<Path, String> source : sources.entrySet()) {
			String text = source.getValue();
			String name = simpleName(source.getKey());
			if (name.equals(getClass().getSimpleName())
					|| !(text.contains("GP_DRUG_SAFETY_DERIVED_FINDINGS")
							|| text.contains(ChartSearchAiConstants.GP_DRUG_SAFETY_DERIVED_FINDINGS))) {
				continue;
			}
			derivedOn.add(name);
			for (String fixture : reach.named(text)) {
				String slice = FIXTURE_DIR + fixture;
				if (!SLICES.contains(slice) || !read(slice).has(DERIVED)) {
					refused.computeIfAbsent(name, k -> new TreeSet<String>()).add(fixture);
				}
			}
		}
		assertTrue(derivedOn.contains("ConditionMediatedFindingTest"),
				"the scan must find the class that turns the tier on, or it passed over nothing: " + derivedOn);
		assertEquals(Collections.emptyMap(), refused,
				"each of these test classes turns " + ChartSearchAiConstants.GP_DRUG_SAFETY_DERIVED_FINDINGS
						+ " on and loads a fixture that is not a listed slice carrying " + DERIVED + " — list the "
						+ "slice with the shipped rows falling inside it, or load the shipped dataset");
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
		return rowsWithin(dataset, INTERACTIONS, 0, 1, ids);
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

	private static String simpleName(Path source) {
		String name = source.getFileName().toString();
		return name.substring(0, name.length() - ".java".length());
	}

	/**
	 * Which fixtures a test source names. A member is a test class's compile-time {@code String} constant
	 * whose value names a fixture — read from the compiled class's {@code ConstantValue} attributes, so
	 * the declaration's spelling does not matter — or a test class's method, whose fixtures are those its
	 * body names, closed transitively. A text names a member when the member's name occurs in it as a
	 * word and the member's top-level class is the text's own or is named anywhere in the file the text
	 * belongs to — every way of qualifying, importing or extending a class, a nested one's included,
	 * spells that name — so the way it is reached does not matter either. A nested class's own name is
	 * not enough: helper names such as {@code StubStrategy} recur across many test classes.
	 * Overloads share a name and so share one answer, the union.
	 */
	private static final class FixtureReach {

		private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

		private static final Pattern METHOD = Pattern.compile(
				"(?<![\\w.])(?<!new\\s)(\\w+)\\s*\\((?:[^(){};]|\\([^(){};]*\\))*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");

		/** A type declaration's header after its name — type parameters, {@code extends}, {@code implements} — up to its body; a wildcard or bound elsewhere is not one. */
		private static final Pattern SUPERTYPES = Pattern.compile("\\b(?:class|interface|enum)\\s+\\w+([^{;]*)\\{");

		private static final Pattern WORD = Pattern.compile("[A-Za-z_$][\\w$]*");

		private static final Set<String> NOT_A_METHOD = new HashSet<String>(Arrays.asList("if", "for", "while",
				"switch", "catch", "synchronized", "try", "return"));

		private final Set<String> fixtures;

		/** top-level class → the comment-stripped text of its file. */
		private final Map<String, String> code = new HashMap<String, String>();

		/** top-level class, its nested classes' included → constant name → the fixtures values under it name. */
		private final Map<String, Map<String, Set<String>>> constants = new HashMap<String, Map<String, Set<String>>>();

		/** top-level class → method name → every body declared under that name. */
		private final Map<String, Map<String, List<String>>> methods = new HashMap<String, Map<String, List<String>>>();

		/** top-level class → the classes its file names; filled as asked. */
		private final Map<String, Set<String>> named = new HashMap<String, Set<String>>();

		FixtureReach(Map<String, String> sources, List<Path> classRoots, Set<String> fixtures) throws IOException {
			this.fixtures = fixtures;
			for (Map.Entry<String, String> source : sources.entrySet()) {
				String top = source.getKey();
				String text = withoutComments(source.getValue());
				code.put(top, text);
				Map<String, List<String>> bodies = new HashMap<String, List<String>>();
				String blanked = withoutLiterals(text);
				Matcher method = METHOD.matcher(blanked);
				while (method.find()) {
					if (NOT_A_METHOD.contains(method.group(1))) {
						continue;
					}
					int open = method.end() - 1;
					bodies.computeIfAbsent(method.group(1), k -> new ArrayList<String>())
							.add(text.substring(open, matchingBrace(blanked, open) + 1));
				}
				methods.put(top, bodies);
			}
			for (Path root : classRoots) {
				try (Stream<Path> walk = Files.walk(root)) {
					for (Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".class"))::iterator) {
						String binary = file.getFileName().toString();
						String top = binary.substring(0, binary.length() - ".class".length()).split("\\$")[0];
						for (Map.Entry<String, String> constant : constantStrings(file).entrySet()) {
							String fixture = fixtureIn(constant.getValue());
							if (fixture != null) {
								constants.computeIfAbsent(top, k -> new HashMap<String, Set<String>>())
										.computeIfAbsent(constant.getKey(), k -> new TreeSet<String>()).add(fixture);
							}
						}
					}
				}
			}
		}

		/**
		 * @return the fixtures a whole test source names, and those named by every test class its
		 *         {@code extends} and {@code implements} clauses name, a nested type's outer class
		 *         included — a lifecycle method it inherits runs without the source ever naming it
		 */
		Set<String> named(String source) {
			return named(source, new HashSet<String>());
		}

		private Set<String> named(String source, Set<String> extended) {
			String text = withoutComments(source);
			Set<String> out = new TreeSet<String>();
			closure(references(text, classesNamedIn(text, null), out), out);
			Matcher clause = SUPERTYPES.matcher(withoutLiterals(text));
			while (clause.find()) {
				for (String type : words(clause.group(1))) {
					if (code.containsKey(type) && extended.add(type)) {
						out.addAll(named(code.get(type), extended));
					}
				}
			}
			return out;
		}

		/** @return the fixtures {@code owner.member} reaches; empty for a member that names none. */
		Set<String> of(String owner, String member) {
			return closure(Collections.singletonList(new String[] { owner, member }), new TreeSet<String>());
		}

		/**
		 * Walks from {@code start} through every member a reached body names, visiting each member once,
		 * so mutual recursion between helpers terminates without caching a partial answer.
		 *
		 * @return {@code out}, holding every fixture a visited constant or body names
		 */
		private Set<String> closure(List<String[]> start, Set<String> out) {
			java.util.Deque<String[]> pending = new java.util.ArrayDeque<String[]>(start);
			Set<String> visited = new HashSet<String>();
			while (!pending.isEmpty()) {
				String[] next = pending.pop();
				if (!visited.add(next[0] + "." + next[1])) {
					continue;
				}
				out.addAll(constants.getOrDefault(next[0], Collections.<String, Set<String>> emptyMap())
						.getOrDefault(next[1], Collections.<String> emptySet()));
				for (String body : methods.getOrDefault(next[0], Collections.<String, List<String>> emptyMap())
						.getOrDefault(next[1], Collections.<String> emptyList())) {
					Set<String> classes = named.computeIfAbsent(next[0],
							k -> classesNamedIn(code.getOrDefault(k, ""), k));
					pending.addAll(references(body, classes, out));
				}
			}
			return out;
		}

		/** @return the top-level test classes {@code text} names, plus {@code own}. */
		private Set<String> classesNamedIn(String text, String own) {
			Set<String> out = new HashSet<String>(words(text));
			out.retainAll(code.keySet());
			if (own != null) {
				out.add(own);
			}
			return out;
		}

		/**
		 * Adds to {@code out} the fixtures {@code text} names by literal or by a constant of one of
		 * {@code classes}, and returns the methods of those classes it names, for the caller to walk.
		 */
		private List<String[]> references(String text, Set<String> classes, Set<String> out) {
			Matcher literal = LITERAL.matcher(text);
			while (literal.find()) {
				String fixture = fixtureIn(literal.group(1));
				if (fixture != null) {
					out.add(fixture);
				}
			}
			Set<String> words = words(text);
			List<String[]> members = new ArrayList<String[]>();
			for (String owner : classes) {
				for (Map.Entry<String, Set<String>> constant : constants.getOrDefault(owner,
						Collections.<String, Set<String>> emptyMap()).entrySet()) {
					if (words.contains(constant.getKey())) {
						out.addAll(constant.getValue());
					}
				}
				for (String method : methods.getOrDefault(owner, Collections.<String, List<String>> emptyMap())
						.keySet()) {
					if (words.contains(method)) {
						members.add(new String[] { owner, method });
					}
				}
			}
			return members;
		}

		/** @return the identifiers in {@code text} outside its string and character literals. */
		private static Set<String> words(String text) {
			Set<String> out = new HashSet<String>();
			Matcher word = WORD.matcher(withoutLiterals(text));
			while (word.find()) {
				out.add(word.group());
			}
			return out;
		}

		/** @return the fixture file {@code literal} names, by its last path segment, or null. */
		private String fixtureIn(String literal) {
			String name = literal.substring(literal.lastIndexOf('/') + 1);
			return fixtures.contains(name) ? name : null;
		}

		/**
		 * @return the {@code String}-valued {@code ConstantValue} attributes of one class file, field name
		 *         to value — what the compiler folded every compile-time constant field to, however it was
		 *         declared
		 */
		private static Map<String, String> constantStrings(Path classFile) throws IOException {
			try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(classFile)))) {
				in.readInt();
				in.readUnsignedShort();
				in.readUnsignedShort();
				int count = in.readUnsignedShort();
				String[] utf8 = new String[count];
				int[] string = new int[count];
				for (int i = 1; i < count; i++) {
					int tag = in.readUnsignedByte();
					switch (tag) {
						case 1:
							utf8[i] = in.readUTF();
							break;
						case 8:
							string[i] = in.readUnsignedShort();
							break;
						case 7:
						case 16:
						case 19:
						case 20:
							in.readUnsignedShort();
							break;
						case 15:
							in.readUnsignedByte();
							in.readUnsignedShort();
							break;
						case 3:
						case 4:
						case 9:
						case 10:
						case 11:
						case 12:
						case 17:
						case 18:
							in.readInt();
							break;
						case 5:
						case 6:
							in.readLong();
							i++;
							break;
						default:
							throw new AssertionError("constant-pool tag " + tag + " in " + classFile + " is not one this reads");
					}
				}
				in.readUnsignedShort();
				in.readUnsignedShort();
				in.readUnsignedShort();
				in.readFully(new byte[2 * in.readUnsignedShort()]);
				Map<String, String> out = new HashMap<String, String>();
				int fields = in.readUnsignedShort();
				for (int f = 0; f < fields; f++) {
					in.readUnsignedShort();
					String name = utf8[in.readUnsignedShort()];
					in.readUnsignedShort();
					int attributes = in.readUnsignedShort();
					for (int a = 0; a < attributes; a++) {
						String attribute = utf8[in.readUnsignedShort()];
						int length = in.readInt();
						if ("ConstantValue".equals(attribute) && length == 2) {
							int value = in.readUnsignedShort();
							if (string[value] != 0) {
								out.put(name, utf8[string[value]]);
							}
						} else {
							in.readFully(new byte[length]);
						}
					}
				}
				return out;
			}
		}

		private static String withoutComments(String source) {
			StringBuilder out = new StringBuilder(source.length());
			int i = 0;
			while (i < source.length()) {
				char c = source.charAt(i);
				if (c == '"' || c == '\'') {
					int end = endOfQuoted(source, i);
					out.append(source, i, end);
					i = end;
				} else if (source.startsWith("//", i)) {
					while (i < source.length() && source.charAt(i) != '\n') {
						i++;
					}
				} else if (source.startsWith("/*", i)) {
					int end = source.indexOf("*/", i + 2);
					i = end < 0 ? source.length() : end + 2;
					out.append(' ');
				} else {
					out.append(c);
					i++;
				}
			}
			return out.toString();
		}

		/** @return {@code code} with every string and character literal's contents blanked, offsets kept. */
		private static String withoutLiterals(String code) {
			StringBuilder out = new StringBuilder(code);
			int i = 0;
			while (i < code.length()) {
				char c = code.charAt(i);
				if (c == '"' || c == '\'') {
					int end = endOfQuoted(code, i);
					for (int j = i + 1; j < end - 1; j++) {
						out.setCharAt(j, ' ');
					}
					i = end;
				} else {
					i++;
				}
			}
			return out.toString();
		}

		private static int endOfQuoted(String text, int open) {
			char quote = text.charAt(open);
			int i = open + 1;
			while (i < text.length() && text.charAt(i) != quote && text.charAt(i) != '\n') {
				i += text.charAt(i) == '\\' ? 2 : 1;
			}
			return Math.min(i + 1, text.length());
		}

		private static int matchingBrace(String code, int open) {
			int depth = 0;
			for (int i = open; i < code.length(); i++) {
				if (code.charAt(i) == '{') {
					depth++;
				} else if (code.charAt(i) == '}' && --depth == 0) {
					return i;
				}
			}
			throw new AssertionError("unbalanced braces after offset " + open);
		}
	}
}
