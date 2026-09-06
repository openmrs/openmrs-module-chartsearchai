/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ModuleSourceRoot;

/**
 * Build-time guard that fails if someone reintroduces pipeline logic
 * duplication. Scans Java source files for known anti-patterns and
 * reports violations as test failures.
 *
 * <p>This test exists because the same category of bug — tests or
 * production code reimplementing logic that belongs in a shared method —
 * has caused multiple production incidents. Visibility restrictions
 * (private methods) catch some cases, but hardcoded strings, formula
 * reimplementations, and helper duplication can only be caught by
 * scanning the source.
 */
public class ArchitectureGuardTest {

	private static final Path SRC_ROOT = ModuleSourceRoot.apiRoot();

	/** The internal name of the type whose constructions issue #378's guard is about. */
	private static final String CHART_ANSWER_TYPE =
			"org/openmrs/module/chartsearchai/api/ChartSearchService$ChartAnswer";

	/** The descriptor fragment that tells the widest constructor from every shorter one. */
	private static final String COVERAGE_TYPE =
			"Lorg/openmrs/module/chartsearchai/reference/DrugReferenceLoad$Coverage;";


	// --- Rules ---

	/**
	 * No file outside ChartSearchAiConstants should call getEmbeddingPrefix().
	 * It is private, so the compiler enforces this for production code, but
	 * this test catches reflection hacks or accidental visibility changes.
	 */
	@Test
	public void noDirectGetEmbeddingPrefixCalls() throws IOException {
		List<String> violations = scanForPattern(
				SRC_ROOT,
				Pattern.compile("getEmbeddingPrefix\\s*\\("),
				"ChartSearchAiConstants.java|ChartSearchAiUtils.java|ArchitectureGuardTest.java",
				"Should use buildPrefixedText() instead of getEmbeddingPrefix()");
		assertNoViolations(violations);
	}

	/**
	 * No file should hardcode the embedding prefix strings that
	 * getEmbeddingPrefix() returns. If someone writes
	 * {@code "Clinical observation: " + text} they are bypassing
	 * buildPrefixedText().
	 */
	@Test
	public void noHardcodedEmbeddingPrefixes() throws IOException {
		// Match quoted prefix strings followed by concatenation or variable use.
		// Exclude ChartSearchAiConstants (where prefixes are defined),
		// TestDatasetHelper (where dataset strings naturally contain them),
		// and this test file itself.
		Pattern pattern = Pattern.compile(
				"\"(Clinical observation: |Medical condition: "
				+ "|Patient allergy: |Clinical diagnosis: "
				+ "|Medication prescription: |Lab or diagnostic test: "
				+ "|Clinical referral: |Clinical order: "
				+ "|Program enrollment: |Medication dispensed: )\"");
		List<String> violations = scanForPattern(
				SRC_ROOT, pattern,
				"ChartSearchAiConstants.java|ChartSearchAiUtils.java|TestDatasetHelper.java|ArchitectureGuardTest.java",
				"Should use buildPrefixedText() instead of hardcoded prefix strings");
		assertNoViolations(violations);
	}

	/**
	 * No file should reimplement cosine similarity. The canonical
	 * implementation is in ChartSearchAiUtils.cosineSimilarity().
	 * Reimplementations typically contain {@code dot +=} and
	 * {@code na +=} or {@code normA +=} in the same method.
	 */
	@Test
	public void noReimplementedCosineSimilarity() throws IOException {
		// Detect the common reimplementation pattern: a loop body that
		// computes dot product and norms.
		Pattern pattern = Pattern.compile(
				"dot\\s*\\+=\\s*[ab]\\[");
		List<String> violations = scanForPattern(
				SRC_ROOT, pattern,
				"ChartSearchAiConstants.java|ChartSearchAiUtils.java",
				"Should use ChartSearchAiUtils.cosineSimilarity() "
				+ "instead of reimplementing the formula");
		assertNoViolations(violations);
	}

	/**
	 * The dataset helpers (inferResourceType, stripDatasetPrefixAndDate,
	 * DATASET_PREFIXES) should only exist in TestDatasetHelper. Any other
	 * test file defining these is duplicating shared logic.
	 */
	@Test
	public void noDuplicatedDatasetHelpers() throws IOException {
		Pattern pattern = Pattern.compile(
				"(private|static).*(inferResourceType|stripDatasetPrefixAndDate"
				+ "|DATASET_PREFIXES|DATE_PREFIX_PATTERN)");
		List<String> violations = scanForPattern(
				SRC_ROOT, pattern,
				"TestDatasetHelper.java|ArchitectureGuardTest.java",
				"Should use TestDatasetHelper instead of duplicating dataset helpers");
		// Allow the thin delegates in LlmInferenceServiceTest
		List<String> filtered = new ArrayList<>();
		for (String v : violations) {
			// Thin delegate pattern: "return TestDatasetHelper.xxx"
			if (v.contains("return TestDatasetHelper.")) {
				continue;
			}
			filtered.add(v);
		}
		assertNoViolations(filtered);
	}

	/**
	 * The FULL_PATIENT_DATASET and SECOND_PATIENT_DATASET arrays should
	 * only be defined in TestDatasetHelper. Other test files should
	 * reference TestDatasetHelper's copy, not define their own.
	 */
	@Test
	public void noDuplicatedDatasetArrays() throws IOException {
		// Match array declarations containing dataset record literals
		// (lines starting with /* [ and containing "Clinical observation:"
		// or similar). A file defining 10+ such lines is duplicating the
		// dataset.
		// This rule walks its own directory instead of getSourceCache(), so the preconditions there do
		// not cover it — and a silent `return` on a missing directory is the same fail-open one rule
		// along: a wrong or moved source root leaves it reporting no violations forever. Measured
		// under a forced-wrong apiRoot(), this was the ONE rule of the five that stayed green.
		Path testDir = SRC_ROOT.resolve(
				"src/test/java/org/openmrs/module/chartsearchai");
		org.junit.jupiter.api.Assertions.assertTrue(Files.exists(testDir),
				"precondition: the test source directory was not found under " + SRC_ROOT + ", so this "
						+ "rule would scan nothing and report no violations — it fails instead");
		List<String> violations = new ArrayList<>();
		// The right-tree canary, matching the second precondition in getSourceCache(). Existence alone
		// is NOT equivalent to it: the sibling omod module carries the same package path, so a root
		// pointed there passes the existence check and this rule scans the wrong tree and reports no
		// violations — measured, that mutation reddens the four cache-reading rules and left this one
		// green.
		final List<String> scanned = new ArrayList<>();
		Files.walkFileTree(testDir, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
					throws IOException {
				if (!file.toString().endsWith(".java")) {
					return FileVisitResult.CONTINUE;
				}
				String name = file.getFileName().toString();
				scanned.add(name);
				if ("TestDatasetHelper.java".equals(name)
						|| "ArchitectureGuardTest.java".equals(name)) {
					return FileVisitResult.CONTINUE;
				}
				List<String> lines = Files.readAllLines(file);
				int datasetLines = 0;
				for (String line : lines) {
					if (line.contains("/* [") && (
							line.contains("Clinical observation:")
							|| line.contains("Medication prescription:")
							|| line.contains("Medical condition:")
							|| line.contains("Patient allergy:")
							|| line.contains("Program enrollment:"))) {
						datasetLines++;
					}
				}
				if (datasetLines > 5) {
					violations.add(file.getFileName() + ": contains "
							+ datasetLines + " inline dataset records. "
							+ "Use TestDatasetHelper.FULL_PATIENT_DATASET instead.");
				}
				return FileVisitResult.CONTINUE;
			}
		});
		org.junit.jupiter.api.Assertions.assertTrue(scanned.contains("TestDatasetHelper.java"),
				"precondition: the scan under " + testDir + " did not see TestDatasetHelper.java, so it "
						+ "is reading the wrong tree — a wrong root that happens to carry this package "
						+ "path scans SOMETHING and this rule then reports no violations");
		assertNoViolations(violations);
	}

	/**
	 * {@code ClassCodeFidelityCheck} must reach citation markers through
	 * {@code ChartSearchAiUtils.citedIndexes} and carry no marker dialect of its own. Since issue
	 * #338 it asks whether a marker sits inside a class-code parenthetical, and CLAUDE.md's rule for
	 * that question is that {@code ChartSearchAiUtils.INLINE_CITATION} is the single parsing pattern,
	 * reached through that shared decode step; the one production site that keeps its own matcher
	 * does so because it needs each marker's text OFFSET, which this check does not.
	 *
	 * <p>Nothing behavioural can pin it: a private bracket pattern, a hand-rolled {@code charAt}
	 * walk, or {@code INLINE_CITATION.matcher(...)} used directly all answer identically on every
	 * case in {@code ClassCodeFidelityTest}, so the whole suite stays green on exactly the regression
	 * the rule exists to prevent — the same reason {@link #noDirectGetEmbeddingPrefixCalls} exists
	 * for a visibility the compiler already enforces.
	 *
	 * <p><b>Stated POSITIVELY, because forbidding spellings was measured not to work.</b> The first
	 * version asked only for one compiled {@code Pattern} and no {@code \\[} literal, and three of
	 * four ordinary relocations walked through it with the build green — a nested class assembling
	 * the brackets by concatenation, a {@code charAt}/{@code isDigit} scan with no regex at all, and
	 * {@code INLINE_CITATION.matcher} used directly (only a plain single-line
	 * {@code Pattern.compile} was caught). What closes the other three is the first assertion below:
	 * the decode step must be CALLED. The two negatives stay as defence in depth.
	 *
	 * <p>It reads SOURCE TEXT, so it asks that the call be present and not that its result be used:
	 * a dialect written BESIDE a retained {@code citedIndexes} call is out of its reach, and no
	 * behavioural case sees that either. Named rather than papered over — the residue is what a
	 * later rule would have to close.
	 *
	 * <p>It reads the file itself rather than going through {@link #scanForPattern}, which reports
	 * per-line matches across the whole tree: this rule needs a COUNT, one file, and a positive
	 * assertion, none of which that helper expresses. It borrows the helper's comment skip, so a
	 * maintainer may record the rejected alternative in this class's own javadoc — which ADR
	 * Decision 59 spells character for character — without breaking the build.
	 */
	@Test
	public void classCodeFidelityCheckReachesMarkersOnlyThroughTheSharedDecodeStep() throws IOException {
		List<String> lines = getSourceCache().get("ClassCodeFidelityCheck.java");
		org.junit.jupiter.api.Assertions.assertNotNull(lines,
				"precondition: ClassCodeFidelityCheck.java was not found by the source scan, so this "
						+ "rule would pass vacuously");
		int compiles = 0;
		boolean callsDecodeStep = false;
		List<String> ownDialect = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			String trimmed = line.trim();
			if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
				continue;
			}
			if (line.contains("ChartSearchAiUtils.citedIndexes(")) {
				callsDecodeStep = true;
			}
			if (line.contains("Pattern.compile(")) {
				compiles++;
			}
			// A bracketed-digit regex of its own, and the shared pattern read directly instead of
			// through its decode step. Both are marker dialects; neither is caught by the count.
			if (line.contains("\\[") || line.contains("INLINE_CITATION")) {
				ownDialect.add("line " + (i + 1) + ": " + trimmed);
			}
		}
		org.junit.jupiter.api.Assertions.assertTrue(callsDecodeStep,
				"ClassCodeFidelityCheck must read citation markers through "
						+ "ChartSearchAiUtils.citedIndexes. If that call is gone, the marker rule has "
						+ "grown a dialect of its own — a regex, a hand-rolled scan, or the shared "
						+ "pattern matched directly — and no behavioural case can see it.");
		org.junit.jupiter.api.Assertions.assertEquals(1, compiles,
				"ClassCodeFidelityCheck must compile exactly one pattern — ATC_CLASS_CODE. A second "
						+ "one is either a citation-marker dialect (use ChartSearchAiUtils.citedIndexes) "
						+ "or a second compiled reading of the code shape (reuse ATC_CLASS_CODE).");
		org.junit.jupiter.api.Assertions.assertTrue(ownDialect.isEmpty(),
				"ClassCodeFidelityCheck must not spell a bracketed regex of its own nor name "
						+ "INLINE_CITATION; markers are decoded by ChartSearchAiUtils.citedIndexes. "
						+ "Found: " + ownDialect);
	}

	/**
	 * Every answer this module builds carries the condition-rule coverage (issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/378">#378</a>) — asked
	 * of the CLASS FILES rather than of the source.
	 *
	 * <p><b>The source form of this guard kept being walked past, and what ended it was changing the
	 * question rather than widening the needle again.</b> Fresh reviewers got a coverage-less answer
	 * past successive versions of it with a qualified {@code new ChartSearchService.ChartAnswer(},
	 * with a COMMENT naming the construction (whose unbalanced bracket made a raw-text depth walk
	 * swallow every real construction after it), with a line wrap inside the qualifier, with a
	 * unicode escape ({@code new \u0043hartAnswer(}), and with a generic witness
	 * ({@code new <String> ChartAnswer(}). Do not read that as the list. Every one of them is a fact
	 * about TYPING, and none survives compilation: javac writes the same constructor descriptor into
	 * the calling class's constant pool for all of them, which is why the question moved here rather
	 * than the pattern getting another alternative.
	 *
	 * <p>So this reads {@code ChartAnswer}'s own constructor descriptors out of its class file, and
	 * then asserts that no other production class references any of them except the widest — the one
	 * ending in {@code DrugReferenceLoad$Coverage}. The descriptors come from the type's own METHOD
	 * TABLE, so every constructor it declares is in the forbidden set whatever its signature: a
	 * review agent added an arity opening on different parameter types and got a coverage-less answer
	 * past an earlier version of this that picked constructors out of the pool by a hardcoded
	 * descriptor PREFIX. It also closes what the source form conceded, that it could only see answers
	 * built in one FILE; this sees every class under {@code api/target/classes}.
	 *
	 * <p><b>The residue, named rather than claimed away.</b> It reads api's output only, because omod
	 * is not compiled when api's tests run — no {@code omod/src/main} class constructs an answer
	 * today, and one that did would be invisible here. It excludes {@code ChartAnswer} itself, whose
	 * telescoping constructors legitimately name every arity. And a class that builds an answer
	 * through a factory rather than a constructor is outside it, and so is one built REFLECTIVELY —
	 * {@code ChartAnswer.class.getConstructor(...).newInstance(...)} names no descriptor in the
	 * caller's pool, and a review agent confirmed it passes. No such factory or reflective
	 * construction exists.
	 *
	 * <p>And it reads BUILD OUTPUT, so it describes what was last compiled — which is why the module's
	 * own rule to measure with {@code mvn -o clean install} from the root binds this guard as much as
	 * any test. Reading compiled output is this repo's own idiom for a rule no behavioural case can
	 * see, rather than a departure from it: {@code ChartSearchAiReferenceGroundingWithholdingTest}
	 * reads every class file the controller compiles to and fails the build on a hardcoded
	 * resource-type name, and ADR Decision 40 cites it as the precedent for pinning a behaviour-neutral
	 * rule structurally. What that one needs from the pool is a NAME; what this one needs is a
	 * DESCRIPTOR. Neither needs a bytecode parser.
	 */
	@Test
	public void everyAnswerThisModuleBuildsCarriesTheConditionRuleCoverage() throws IOException {
		Path classes = ModuleSourceRoot.apiRoot().resolve("target/classes");
		org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.isDirectory(classes),
				"no " + classes + "; a guard that discovers nothing forbids nothing");
		Path holder = classes.resolve(
				"org/openmrs/module/chartsearchai/api/ChartSearchService$ChartAnswer.class");
		org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(holder),
				"no ChartAnswer class file at " + holder + ", so this guard would forbid nothing");

		List<String> constructors = constructorDescriptors(holder);
		org.junit.jupiter.api.Assertions.assertTrue(constructors.size() > 1,
				"expected ChartAnswer to publish several constructor arities and found "
						+ constructors.size() + "; with one there is nothing for a coverage-less answer "
						+ "to be built through and this guard is vacuous");
		List<String> widest = new ArrayList<>();
		for (String descriptor : constructors) {
			if (descriptor.contains(COVERAGE_TYPE)) {
				widest.add(descriptor);
			}
		}
		org.junit.jupiter.api.Assertions.assertEquals(1, widest.size(),
				"exactly one ChartAnswer constructor may take the condition-rule coverage — it is the "
						+ "widest, and every shorter one states null. Found " + widest.size() + ".");

		List<String> violations = new ArrayList<>();
		int callers = 0;
		try (java.util.stream.Stream<Path> tree = java.nio.file.Files.walk(classes)) {
			for (Path file : tree.filter(f -> f.toString().endsWith(".class"))
					.filter(f -> !f.equals(holder)).collect(java.util.stream.Collectors.toList())) {
				List<String> pool = constantPoolStrings(file);
				if (!pool.contains(CHART_ANSWER_TYPE)) {
					continue;
				}
				callers++;
				for (String entry : pool) {
					if (constructors.contains(entry) && !entry.contains(COVERAGE_TYPE)) {
						violations.add(classes.relativize(file) + " builds " + entry);
					}
				}
			}
		}
		org.junit.jupiter.api.Assertions.assertTrue(callers > 0,
				"no production class outside ChartAnswer even NAMES it, so this guard just passed by "
						+ "finding nothing to check — read " + classes + " before trusting it");
		org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty(),
				"every answer this module builds must carry the condition-rule coverage (issue #378); "
						+ "one that does not states null on a key the README documents as always "
						+ "present, and the ungrounded answer is the one a streaming user sees. "
						+ "Found: " + violations);
	}

	/**
	 * @return the descriptor of every constructor {@code classFile} DECLARES, read from its method
	 *         table rather than picked out of the constant pool by shape.
	 *
	 *         <p>The distinction is the guard's whole correctness. Selecting pool strings by a
	 *         descriptor prefix hardcodes the leading parameter types, so a constructor added with a
	 *         different signature never joins the forbidden set and its callers are never checked —
	 *         measured by a review agent, which added such an arity plus a caller and left the guard
	 *         green. The method table has no such blind spot, and it costs one more walk: past the
	 *         pool, the access flags, this/super, the interfaces and the fields, skipping each
	 *         attribute by its own declared length.
	 */
	private static List<String> constructorDescriptors(Path classFile) throws IOException {
		java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(classFile));
		List<String> pool = readConstantPool(in);
		in.position(in.position() + 6);
		// Read the count into a local FIRST: getShort() advances the buffer, and the argument to
		// position(...) evaluates in.position() before it does — so the inline form silently loses the
		// two bytes the count itself occupies. That is what this walk got wrong on its first run, and
		// it surfaced as an attribute length read out of the middle of a method body.
		int interfaces = in.getShort() & 0xFFFF;
		in.position(in.position() + 2 * interfaces);
		skipFields(in);
		List<String> descriptors = new ArrayList<>();
		int methods = in.getShort() & 0xFFFF;
		for (int i = 0; i < methods; i++) {
			in.getShort();
			String name = pool.get(in.getShort() & 0xFFFF);
			String descriptor = pool.get(in.getShort() & 0xFFFF);
			skipAttributes(in);
			if ("<init>".equals(name)) {
				descriptors.add(descriptor);
			}
		}
		return descriptors;
	}

	/** Skips the field table, whose entries have a method's shape: access, name, descriptor, attributes. */
	private static void skipFields(java.nio.ByteBuffer in) {
		int fields = in.getShort() & 0xFFFF;
		for (int i = 0; i < fields; i++) {
			in.position(in.position() + 6);
			skipAttributes(in);
		}
	}

	/** Skips an attribute table by each attribute's own declared length. */
	private static void skipAttributes(java.nio.ByteBuffer in) {
		int attributes = in.getShort() & 0xFFFF;
		for (int i = 0; i < attributes; i++) {
			in.getShort();
			int length = in.getInt();
			in.position(in.position() + length);
		}
	}

	/**
	 * @return every {@code CONSTANT_Utf8} entry in {@code classFile}'s constant pool, walked by the
	 *         class-file format's own lengths rather than scanned for. A regex over the raw bytes runs
	 *         past the end of a descriptor into whatever follows it in the pool — measured while
	 *         writing this, where it turned three unrelated method descriptors into one 629-character
	 *         string. Long-and-double entries take two pool slots, which is the one thing a walk like
	 *         this gets wrong if it does not know it.
	 */
	private static List<String> constantPoolStrings(Path classFile) throws IOException {
		List<String> slots = readConstantPool(
				java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(classFile)));
		List<String> present = new ArrayList<>();
		for (String slot : slots) {
			if (slot != null) {
				present.add(slot);
			}
		}
		return present;
	}

	/**
	 * Reads the constant pool and leaves {@code in} positioned immediately after it, so a caller that
	 * needs the method table can carry on from there.
	 *
	 * @return the pool BY SLOT, with a null wherever the entry is not a {@code CONSTANT_Utf8}. Slot
	 *         indexes are 1-based in the class file and 0-based here, so slot {@code n} is element
	 *         {@code n - 1}. Returning only the strings, in encounter order, would be the obvious
	 *         shape and is wrong for the method table, whose name and descriptor indexes are SLOT
	 *         numbers — every non-Utf8 entry between them would shift the answer.
	 */
	private static List<String> readConstantPool(java.nio.ByteBuffer in) {
		in.position(8);
		int count = in.getShort() & 0xFFFF;
		List<String> slots = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			slots.add(null);
		}
		for (int slot = 1; slot < count; slot++) {
			int tag = in.get() & 0xFF;
			switch (tag) {
				case 1:
					byte[] utf = new byte[in.getShort() & 0xFFFF];
					in.get(utf);
					slots.set(slot, new String(utf, java.nio.charset.StandardCharsets.UTF_8));
					break;
				case 7: case 8: case 16: case 19: case 20:
					in.position(in.position() + 2);
					break;
				case 15:
					in.position(in.position() + 3);
					break;
				case 5: case 6:
					in.position(in.position() + 8);
					slot++;
					break;
				default:
					in.position(in.position() + 4);
					break;
			}
		}
		return slots;
	}

	// --- Infrastructure ---

	/** Cache of file name → lines, populated once by {@link #loadAllSources}. */
	private static java.util.Map<String, List<String>> sourceCache;

	/**
	 * Every rule in this class but one scans this map, so an EMPTY or WRONG map made all of those
	 * pass vacuously — a structural guard that reads nothing reports no violations. That was not
	 * hypothetical: forcing {@link ModuleSourceRoot#apiRoot()} to an unrelated directory USED TO
	 * leave this class entirely green. It no longer does; the cache asserts its own sanity before
	 * any rule reads it, and the same mutation now reddens the rules that read it.
	 *
	 * <p>The exception is {@code noDuplicatedDatasetArrays}, which walks the TEST tree itself rather
	 * than this cache, so these assertions cannot reach it — it carries both of them inline, and it
	 * needs both: existence alone is not enough, because the sibling {@code omod} module has the
	 * same package path, so a root pointed there exists and scans the wrong tree. A new rule that
	 * walks its own directory owes itself the same pair.
	 */
	private static java.util.Map<String, List<String>> getSourceCache()
			throws IOException {
		if (sourceCache == null) {
			sourceCache = new java.util.LinkedHashMap<>();
			Files.walkFileTree(SRC_ROOT, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file,
						BasicFileAttributes attrs) throws IOException {
					if (file.toString().endsWith(".java")) {
						sourceCache.put(file.getFileName().toString(),
								Files.readAllLines(file));
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
		org.junit.jupiter.api.Assertions.assertFalse(sourceCache.isEmpty(),
				"precondition: the source scan found no .java files under " + SRC_ROOT + " — every rule "
						+ "in this class would pass vacuously, so this fails instead of reporting no "
						+ "violations");
		org.junit.jupiter.api.Assertions.assertTrue(sourceCache.containsKey("LlmProvider.java"),
				"precondition: the source scan did not find LlmProvider.java under " + SRC_ROOT + ", so "
						+ "it is reading the wrong tree — a wrong root scans SOMETHING and every rule "
						+ "then passes on files these rules were never written about");
		return sourceCache;
	}

	private static List<String> scanForPattern(Path root, Pattern pattern,
			String excludeFiles, String message) throws IOException {
		List<String> violations = new ArrayList<>();
		Pattern excludePattern = Pattern.compile(excludeFiles);

		for (java.util.Map.Entry<String, List<String>> entry
				: getSourceCache().entrySet()) {
			String fileName = entry.getKey();
			if (excludePattern.matcher(fileName).find()) {
				continue;
			}
			List<String> lines = entry.getValue();
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				// Skip comments and Javadoc
				String trimmed = line.trim();
				if (trimmed.startsWith("//") || trimmed.startsWith("*")
						|| trimmed.startsWith("/*")) {
					continue;
				}
				if (pattern.matcher(line).find()) {
					violations.add(fileName + ":" + (i + 1)
							+ " — " + message + "\n    " + trimmed);
				}
			}
		}
		return violations;
	}

	private static void assertNoViolations(List<String> violations) {
		if (!violations.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			sb.append(violations.size())
					.append(" architecture violation(s) found:\n\n");
			for (String v : violations) {
				sb.append("  - ").append(v).append("\n");
			}
			sb.append("\nSee the 'API surface rules' of CLAUDE.md, and of "
					+ "api/src/main/java/org/openmrs/module/chartsearchai/reference/CLAUDE.md for the "
					+ "drug-safety ones, for the correct methods to use.");
			fail(sb.toString());
		}
	}

}
