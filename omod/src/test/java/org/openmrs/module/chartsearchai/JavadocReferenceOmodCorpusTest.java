/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

/**
 * Every javadoc reference in {@code omod/src/main/java} and {@code omod/src/test/java} resolves,
 * asked of the real compiler over the real files with arguments THIS class chooses — so it reads no
 * build configuration at all and no POM edit can silence it.
 *
 * <p><strong>Why it exists, which is the part worth reading.</strong> {@code JavadocReferenceGuardTest}
 * in the api module holds the same line two ways: it compiles the api corpus with its own arguments,
 * and it reads these POMs to check that the argument the build declares is really in force. The first
 * of those cannot reach {@code omod} — an api-side test runs on the api classpath — so until this
 * class existed omod's two source roots were held by the POM readers ALONE. Four consecutive review
 * rounds of #262 each found one more position from which the effective javac argument list is set that
 * those readers did not read: the {@code maven.compiler.failOnError} user property, the
 * {@code -Xdoclint/package} qualifier, four sibling argument parameters beside {@code compilerArgs},
 * and then three more at once — a non-{@code <arg>} child INSIDE {@code <compilerArgs>}, a child pom
 * pinning a compiler-plugin version predating that parameter, and Maven's {@code combine.self}
 * merge-control attribute. Every one of them landed on omod, for that structural reason and not by
 * chance. Each was fixed in the reader, and each fix left the next one reachable.
 *
 * <p>So this class is not a fifth reader. It bounds the CONSEQUENCE: with it in place, a POM that
 * defeats the flag makes the POM checks disagree with the build — which is a defect worth reporting —
 * rather than leaving omod's pointers unresolved with everything green. <strong>It is deliberately not
 * a claim that the POM readers are complete, and nothing here should be read as one.</strong>
 *
 * <p><strong>It duplicates a little of that class rather than sharing it, and that is a choice.</strong>
 * The two modules share no test classpath — {@code omod} depends on the api JAR and not its test-jar —
 * so sharing would mean publishing this scaffolding as a build dependency. The duplication buys
 * something as well as costing something: one edit cannot defeat both, which is the whole shape of the
 * defence. What it costs is drift, and the pieces that could drift are the ones with a rule of their
 * own — the classify-by-DIFFERENCE rule below, and the per-root anchor.
 *
 * <p><strong>What it does not cover.</strong> Only what {@code javac} reads: an orphaned javadoc block
 * is invisible to doclint by construction, and {@code JavadocReferenceGuardTest}'s scanner is what
 * covers those, over these roots as well as api's. A method BODY is unreadable to doclint at any
 * configuration. And a pointer that resolves to the wrong member resolves.
 */
public class JavadocReferenceOmodCorpusTest {

	/**
	 * The doclint group, passed by this class rather than read from anywhere. The whole point of the
	 * literal is that no build file decides it.
	 */
	private static final String REFERENCE_CHECK = "-Xdoclint:reference";

	/**
	 * The roots this check compiles, each with one file its walk must find. The anchor is per ROOT
	 * because a walk over several roots one of which resolves nowhere still finds files, still
	 * compiles clean and reports nothing for the root it never read — a structural guard passing for
	 * exactly the reason it exists to catch.
	 *
	 * <p>Spelled here rather than derived from the reactor, unlike the api-side corpus: this suite
	 * runs in {@code omod} and these are that module's own roots. What stops the list drifting behind
	 * the reactor is on the other side —
	 * {@code JavadocReferenceGuardTest.theCorpusCoversEveryModuleTheBuildCompiles} requires every
	 * reactor source root outside api to be named as a literal in THIS file, so a third module, or a
	 * root moved off the convention, reddens there.
	 */
	private static final Map<String, String> SOURCE_ROOTS = sourceRoots();

	private static Map<String, String> sourceRoots() {
		Map<String, String> roots = new LinkedHashMap<String, String>();
		roots.put("omod/src/main/java", "ChartSearchAiRestController.java");
		roots.put("omod/src/test/java", "ChartSearchAiAuditSearchModeTest.java");
		return roots;
	}

	/**
	 * The arguments both compiles add to whatever is being tested: quiet, no annotation processing,
	 * and a cap high enough that a broken classpath's thousands of errors are all counted. One
	 * constant because the classify-by-DIFFERENCE rule compares a flagged run against a run of exactly
	 * these — written out separately, the two sides drift and the difference stops meaning anything.
	 */
	private static final List<String> BASELINE_ARGUMENTS = Arrays.asList(
			"-nowarn", "-proc:none", "-Xlint:none", "-Xmaxerrs", "10000");

	/** How many lines of any one failure listing are printed. */
	private static final int MAX_REPORTED_LINES = 20;

	/**
	 * Every javadoc reference in this module's two source roots resolves.
	 *
	 * <p><strong>What makes an error doclint's is a DIFFERENCE, never its wording.</strong> Where the
	 * flagged compile reports errors the same sources are compiled again WITHOUT the argument: what
	 * only the flagged run reports is doclint's, and anything the baseline reports too is a broken
	 * compile — reported loudly as a guard that could not run, because a guard that cannot run must
	 * not pass. Matching the message text instead fails two ways that were both measured on the api
	 * side: the group's message keys are more numerous than any list of them, and
	 * {@code Diagnostic.getMessage(null)} is DEFAULT-LOCALE, so on a machine running one of doclint's
	 * translations the match fails on a perfectly clean tree.
	 */
	@Test
	public void everyJavadocReferenceInTheOmodModuleResolves() throws Exception {
		List<Path> sources = javaSourcesUnder();
		String classpath = System.getProperty("java.class.path");
		List<String> withTheCheck = compile(withTheReferenceCheck(), sources, classpath);
		if (withTheCheck.isEmpty()) {
			return;
		}
		List<String> withoutIt = compile(BASELINE_ARGUMENTS, sources, classpath);
		if (!withoutIt.isEmpty()) {
			fail("This guard could not run: these " + sources.size() + " omod source(s) do not compile even\n"
					+ "WITHOUT " + REFERENCE_CHECK + " (" + withoutIt.size() + " error(s)), so a clean\n"
					+ "javadoc report would prove nothing. A broken compile classpath looks like this.\n\n"
					+ join(withoutIt));
		}
		fail(withTheCheck.size() + " javadoc reference error(s) in the omod module — every one of these\n"
				+ "appears only when " + REFERENCE_CHECK + " is passed, so every one is doclint's:\n\n"
				+ join(withTheCheck)
				+ "\nThe javadoc is this module's design record (docs/adr.md, Decision 75), so a pointer\n"
				+ "that no longer resolves is a build failure rather than plain text. Fix the pointer — a\n"
				+ "FULLY-QUALIFIED {@link} resolves even where the enclosing-qualified form does not.");
	}

	private static List<String> withTheReferenceCheck() {
		List<String> arguments = new ArrayList<String>(BASELINE_ARGUMENTS);
		arguments.add(REFERENCE_CHECK);
		return arguments;
	}

	/**
	 * The repository root — the directory holding {@code CLAUDE.md} and {@code docs/} — walking up
	 * from the working directory, which surefire sets to the module directory.
	 *
	 * <p>Throws rather than falling back to the working directory, for the reason
	 * {@code ModuleSourceRoot} gives on the api side: a guard that cannot find the tree it guards must
	 * fail loudly, because a walk resolving nowhere scans nothing and reports no violations. A copy of
	 * that walk rather than a use of it, because it lives in the api module's TEST sources and this
	 * suite runs on a classpath that has the api JAR and not its tests. See this class's javadoc on
	 * why the duplication is a choice.
	 */
	private static Path repoRoot() {
		Path current = Paths.get("").toAbsolutePath();
		while (current != null) {
			if (Files.isRegularFile(current.resolve("CLAUDE.md"))
					&& Files.isDirectory(current.resolve("docs"))) {
				return current;
			}
			current = current.getParent();
		}
		throw new IllegalStateException(
				"Could not locate the repository root (a directory holding CLAUDE.md and docs/) walking up "
						+ "from " + Paths.get("").toAbsolutePath());
	}

	/**
	 * Every {@code .java} file under {@link #SOURCE_ROOTS}, with each root held to its own anchor. A
	 * root that does not exist, or whose walk does not find its anchor, fails loudly rather than being
	 * skipped: skipping one is how every other root passes for the one nobody read.
	 */
	private static List<Path> javaSourcesUnder() throws IOException {
		Path repoRoot = repoRoot();
		List<Path> sources = new ArrayList<Path>();
		for (Map.Entry<String, String> root : SOURCE_ROOTS.entrySet()) {
			Path directory = repoRoot.resolve(root.getKey());
			Set<String> names = new LinkedHashSet<String>();
			if (Files.isDirectory(directory)) {
				try (Stream<Path> paths = Files.walk(directory)) {
					paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
							.sorted().forEach(path -> {
								sources.add(path);
								names.add(path.getFileName().toString());
							});
				}
			}
			if (!names.contains(root.getValue())) {
				fail("The walk of " + root.getKey() + " under " + repoRoot + " did not find "
						+ root.getValue() + " (it found " + names.size() + " file(s)), so this guard is "
						+ "reading the wrong tree or no tree at all and would report no violations whatever "
						+ "the javadoc says.");
			}
		}
		return sources;
	}

	/**
	 * One compile, returning its ERRORS. Fails loudly where there is no compiler to ask, and where the
	 * compiler rejects the arguments outright — {@code javax.tools} throws on an unrecognised option
	 * rather than reporting a diagnostic, so without that branch a future JDK's rename would redden
	 * with a bare stack trace naming nothing.
	 */
	private static List<String> compile(List<String> arguments, List<Path> files, String classpath)
			throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			fail("No system Java compiler is available (running on a JRE?), so this guard cannot check "
					+ "whether the javadoc references in omod resolve. A guard that cannot run must not pass.");
		}
		Path out = Files.createTempDirectory("javadoc-reference-omod-classes");
		try {
			DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<JavaFileObject>();
			try (StandardJavaFileManager manager = compiler.getStandardFileManager(collector, null,
					StandardCharsets.UTF_8)) {
				manager.setLocation(StandardLocation.CLASS_OUTPUT, Arrays.asList(out.toFile()));
				if (classpath != null) {
					manager.setLocation(StandardLocation.CLASS_PATH, classpathEntries(classpath));
				}
				List<File> asFiles = new ArrayList<File>();
				for (Path file : files) {
					asFiles.add(file.toFile());
				}
				Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromFiles(asFiles);
				try {
					compiler.getTask(null, manager, collector, arguments, null, units).call();
				}
				catch (IllegalArgumentException rejected) {
					fail("The compiler rejected these arguments outright: " + arguments + "\n\n  "
							+ rejected.getMessage());
					return null;
				}
				return errors(collector);
			}
		}
		finally {
			deleteRecursively(out);
		}
	}

	private static List<String> errors(DiagnosticCollector<JavaFileObject> collector) {
		List<String> errors = new ArrayList<String>();
		for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
			if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
				String source = diagnostic.getSource() == null ? "(no source)"
						: diagnostic.getSource().getName();
				errors.add(source + ":" + diagnostic.getLineNumber() + " " + diagnostic.getMessage(null));
			}
		}
		return errors;
	}

	private static List<File> classpathEntries(String classpath) {
		List<File> entries = new ArrayList<File>();
		for (String entry : classpath.split(File.pathSeparator)) {
			if (!entry.trim().isEmpty()) {
				entries.add(new File(entry));
			}
		}
		return entries;
	}

	/**
	 * Removes a temp directory, deepest entry first, best-effort. Best-effort deliberately: this runs
	 * in a {@code finally} after the compile has already produced its verdict, so a throw here would
	 * REPLACE that verdict with a complaint about temp-directory cleanup.
	 */
	private static void deleteRecursively(Path dir) {
		try (Stream<Path> paths = Files.walk(dir)) {
			List<Path> all = new ArrayList<Path>();
			paths.forEach(all::add);
			all.sort((a, b) -> b.getNameCount() - a.getNameCount());
			for (Path path : all) {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException swallowed) {
					// see this method's javadoc: cleanup must not become the test's verdict
				}
			}
		}
		catch (IOException swallowed) {
			// as above
		}
	}

	/**
	 * The lines, one per bullet, capped. The cap is not cosmetic: a compile whose classpath is
	 * unusable reports an error per unresolvable type, and a listing that long buries the sentence
	 * above it saying what went wrong.
	 */
	private static String join(List<String> lines) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.size() && i < MAX_REPORTED_LINES; i++) {
			sb.append("  - ").append(lines.get(i)).append("\n");
		}
		if (lines.size() > MAX_REPORTED_LINES) {
			sb.append("  ... and ").append(lines.size() - MAX_REPORTED_LINES).append(" more\n");
		}
		return sb.toString();
	}
}
