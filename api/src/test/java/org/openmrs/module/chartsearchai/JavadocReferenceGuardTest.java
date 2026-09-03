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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Build-time guard on the javadoc pointers this module's design record is made of, and on the one
 * line of build configuration that resolves them.
 *
 * <p>It exists because the javadoc IS the design record here. {@code CLAUDE.md} is largely a list of
 * pointers into it, and #243 deliberately replaced copied figures with pointers to whichever member
 * owns each one, on the argument that a copied number rots visibly while a dead pointer renders as
 * plain text and reads as authoritative. Until #262 nothing resolved any of them: a member could be
 * renamed and every pointer at it went dead with a green build, on three JDKs. Three had already
 * gone dead when the check was switched on — one in {@code api/src/main/java} and TWO in
 * {@code api/src/test/java}, one of those the ticket's own scenario, a member renamed out from under
 * a pointer that still named it.
 *
 * <p>The checks answer these questions, and no one of them subsumes another:
 *
 * <ul>
 * <li><strong>Do this module's own pointers resolve?</strong>
 * {@link #everyJavadocReferenceInTheApiModuleResolves} asks the real compiler, over the real source
 * roots, with the arguments IT chooses rather than the ones the build declares — so it keeps working
 * if the flag below is lost, relocated into a profile, or defeated by {@code <failOnError>} or a
 * {@code <compilerId>} swap. It is the only check that reads this module's OWN pointers.
 *
 * <p>It is not otherwise independent of the build, and an earlier version of this bullet said it was.
 * {@link #apiRoots} derives its corpus from the root pom's {@code <modules>}, so this check needs
 * that file to parse, to declare {@link #EXPECTED_ROOT_ARTIFACT} and to declare a module named
 * {@link #API_MODULE}: empty the {@code <modules>} and it fails along with the rest. Exactly ONE
 * check here reads no POM, {@link #theScannerAgreesWithTheCompilerAboutWhatIsAttached}, and what it
 * reads is shapes this class wrote rather than the corpus.</li>
 * <li><strong>Do the arguments the build declares actually refuse one?</strong>
 * {@link #theArgumentsTheBuildDeclaresRefuseADeadJavadocReference} takes the root pom's MANAGED
 * argument list and asks the real compiler, rather than matching a string. The compiler is the
 * oracle deliberately: {@code -Xdoclint} is an option grammar, and a check that re-implements it
 * reddens on {@code -Xdoclint:all,-missing,-html,-syntax}, which enables the reference group
 * perfectly well. This is what extends the gate to {@code omod} and to failing at compile time,
 * which the check above cannot see.
 *
 * <p>It reads the arguments through {@link #rootManagedCompilerArgs}, which navigates to ONE
 * position — the root pom's {@code <build>/<pluginManagement>} entry, at plugin level — so the
 * position is asserted here rather than in a check of its own. Placement is the decision and not a
 * detail: moved into {@code api/pom.xml}'s own {@code <plugins>} the flag gates the api module and
 * nothing else, and a dead reference in {@code omod} then compiles with the whole build reporting
 * success. A separate placement check was written first and then removed — it early-returned
 * whenever the arguments were present, so on a green tree it asserted nothing, and on the mutation
 * it existed for this one already failed.</li>
 * <li><strong>Can it be silently overridden?</strong>
 * {@link #noOtherCompilerConfigurationDropsTheCheck}.</li>
 * <li><strong>Is the corpus every module the build compiles?</strong>
 * {@link #theCorpusCoversEveryModuleTheBuildCompiles}. Both walks
 * and every POM check derive their scope from the root pom's {@code <modules>}, so a module declared
 * in a {@code <profile>} or keeping its sources off {@code src/main/java} leaves the scope without
 * anything being deleted. Asked of synthetic POMs, because this repository contains neither shape —
 * which is why the narrower readings were invisible.</li>
 * <li><strong>Does the compiler even SEE every comment?</strong> {@link #noJavadocBlockIsOrphaned}.
 * A javadoc block that attaches to no declaration is discarded, and doclint says nothing at all about
 * the pointers inside it — so the gate has a hole exactly the size of that block. Three such blocks
 * existed when this was written, and this repository has had that failure twice before, both times
 * found by a human reader rather than by anything mechanical. The blocks and their pointers are named
 * once, on that method, and the evidence is in docs/adr.md Decision 72.</li>
 * <li><strong>And is anything that documents nothing left where the compiler will complain about
 * it?</strong> {@link #noFileOpensWithAJavadocBlockBeforeItsPackageStatement}, which keeps the licence
 * headers this change normalised from drifting back one file at a time.</li>
 * <li><strong>And does the heuristic behind that one still match javac?</strong>
 * {@link #theScannerAgreesWithTheCompilerAboutWhatIsAttached} — a table of shapes, each declaring
 * what it IS, against which both the compiler and the scanner are held. It exists because the
 * attachment heuristic was got wrong repeatedly, every time green under the whole suite, and arguing
 * the rule in prose is what kept failing.</li>
 * </ul>
 *
 * <p>Scope is doclint's {@code reference} group, which is slightly wider than {@code @link}: it also
 * errors on {@code @param} naming a parameter that does not exist and on {@code @return} used on a
 * void method. Stated because a maintainer meeting a red build on a mistyped {@code @param} should
 * find that written down. It is NOT {@code missing} (a doc-coverage mandate nobody asked for) and
 * NOT {@code html}/{@code syntax}.
 *
 * <p>Its blind spots, stated because none of the three is small. The POM checks read the
 * repository's own POMs, so an argument added from a {@code settings.xml} profile or a command line
 * is invisible to them, in both directions. A METHOD BODY is unreadable to doclint at any
 * configuration and so to every check here — a local declaration, a member of a local class, a member
 * of an anonymous class declared inside a method; the repository carries no {@code @link} in any of
 * them today and nothing detects one arriving, since {@link #noJavadocBlockIsOrphaned} is about
 * attachment rather than scope. An anonymous class in a FIELD initialiser is read, which is what an
 * earlier version of this paragraph got wrong. And nothing here, nor anything #262 proposed, can tell a pointer
 * that resolves from the pointer the sentence meant: one retargeted to a member that exists but is
 * the wrong one stays silent.
 */
public class JavadocReferenceGuardTest {

	private static final Path REPO_ROOT = ModuleSourceRoot.repoRoot();

	/**
	 * The doclint group this change is about, and only this one. Used as the argument
	 * {@link #everyJavadocReferenceInTheApiModuleResolves} passes itself, and to NAME the check in
	 * failure messages — never to recognise it in a POM. Nothing here matches an argument as a
	 * string: what a declared argument list does is asked of the compiler, for
	 * {@link #refusesADeadReference}'s reason.
	 */
	private static final String REFERENCE_CHECK = "-Xdoclint:reference";

	/**
	 * The module this suite runs in, whose two source roots
	 * {@link #everyJavadocReferenceInTheApiModuleResolves} compiles. A name and not a list, because
	 * {@link #apiRoots} derives the roots from the reactor: what is api-specific here is the CLASSPATH
	 * this suite runs on, not which directories that module keeps its sources in.
	 */
	private static final String API_MODULE = "api";

	private static final String COMPILER_PLUGIN = "maven-compiler-plugin";

	/**
	 * The artifactId the root POM must declare. The POM side of this guard has no anchor of the kind
	 * {@link #SOURCE_ROOTS} gives the source walks, and it needs one for the same reason:
	 * {@link ModuleSourceRoot#repoRoot()} walks up for {@code CLAUDE.md} and {@code docs/}, so
	 * a wrapper checkout or a worktree without its own copy resolves a DIFFERENT project's root — and
	 * "the root pom declares no plugin-level compilerArgs" would then be true, of a file that is not
	 * this module's.
	 */
	private static final String EXPECTED_ROOT_ARTIFACT = "chartsearchai";

	/**
	 * Unicode escapes for {@code *} and {@code /}. javac translates these before lexing, so either can
	 * open or close a comment for the compiler while the scan, reading raw text, sees neither. A file
	 * containing one is REFUSED rather than mis-read, which the scan does whatever the corpus holds and
	 * which reddens loudly rather than skipping the file. No tally of the repository's escapes is given
	 * — an earlier version gave one and it was wrong under every reading of what an escape is. The
	 * claim that costs nothing is about the RANGE: refusing is cheap for as long as no source carries
	 * an escape in it, and answering could be wrong for every line after one.
	 *
	 * <p>Assembled from pieces rather than written out, because THIS file is inside the corpus the
	 * scan walks: spelled as literals, the constant makes its own source unscannable and the check
	 * refuses the guard that owns it. Which is at least a demonstration that it works.
	 */
	private static final List<String> COMMENT_DELIMITER_ESCAPES = Arrays.asList("\\" + "u002a",
			"\\" + "u002f");

	/**
	 * The probe every arguments-refuse-it check compiles. Its dead pointer sits on a {@code private}
	 * member deliberately, and that is load-bearing rather than incidental: {@code -Xdoclint} takes an
	 * ACCESS qualifier, and {@code -Xdoclint:reference/public} silences the check for everything but
	 * public API. On a public probe that argument satisfied every check that reads a declared argument
	 * list, while dropping the gate for most of the corpus — this module's design record lives
	 * overwhelmingly on non-public members. The counterpart live probe is public, so between them the pair also refuses an argument
	 * list that cannot compile anything.
	 */
	private static final String DEAD_REFERENCE_SOURCE =
			"public class DeadReference {\n"
					+ "\t/** Points at {@link java.lang.String#noSuchMemberAnywhere()}, which does not exist. */\n"
					+ "\tprivate int onANonPublicMember = 1;\n"
					+ "\n\tint read() {\n\t\treturn onANonPublicMember;\n\t}\n}\n";

	/**
	 * The arguments every compile here adds to whatever it is testing: quiet, no annotation processing,
	 * and a cap high enough that a broken classpath's thousands of errors are all counted. One
	 * constant because the classify-by-DIFFERENCE rule compares a flagged run against a run of exactly
	 * these — written out separately, the two sides drift and the difference stops meaning anything.
	 */
	private static final List<String> BASELINE_ARGUMENTS = Arrays.asList(
			"-nowarn", "-proc:none", "-Xlint:none", "-Xmaxerrs", "10000");

	private static final String LIVE_REFERENCE_SOURCE =
			"/** Points at {@link java.lang.String#length()}, which exists. */\n"
					+ "public class LiveReference {\n}\n";

	/** How many lines of any one failure listing are printed. See {@link #join(java.util.List)}. */
	private static final int MAX_REPORTED_LINES = 20;

	/**
	 * One file each java source root's walk must find. The anchor is per ROOT and not per walk,
	 * deliberately: a walk over several roots one of which resolves nowhere finds files, passes a
	 * non-empty check, and reports no violations for the root it never read — a structural guard
	 * passing for the reason it exists to catch, and the shape {@link ModuleSourceRoot}'s own javadoc
	 * warns walking callers about. Each anchor is a file {@code CLAUDE.md} itself cites, so none of
	 * them is likely to be renamed quietly.
	 *
	 * <p><strong>Anchors only. WHICH roots are walked is {@link #reactorSourceRoots}' answer, derived
	 * from the root pom's {@code <modules>}, and this map is not the scope.</strong> It was: the roots
	 * were a hand-written list nothing cross-checked, so deleting one line here — a plausible "let me
	 * scope this to api" edit — took a whole reactor module out of {@link #noJavadocBlockIsOrphaned}
	 * and {@link #noFileOpensWithAJavadocBlockBeforeItsPackageStatement} with every check here green and
	 * a planted orphan unreported. The anchor mechanism could not see it, because it protects only the
	 * roots that are IN the list, and the compiler cannot compensate: an orphaned block is invisible to
	 * doclint by construction, which is the whole reason the scanner exists. Now a root the reactor has
	 * and this map does not is a failure ({@link #javaSourcesUnder}), and an entry naming a root the
	 * reactor does NOT have is one too ({@link #reactorSourceRoots}).
	 */
	private static final Map<String, String> SOURCE_ROOTS = sourceRoots();

	private static Map<String, String> sourceRoots() {
		Map<String, String> roots = new LinkedHashMap<String, String>();
		roots.put("api/src/main/java", "ChartSearchAiUtils.java");
		roots.put("api/src/test/java", "ProjectInstructionsGuardTest.java");
		roots.put("omod/src/main/java", "ChartSearchAiRestController.java");
		roots.put("omod/src/test/java", "ChartSearchAiAuditSearchModeTest.java");
		return roots;
	}

	/**
	 * Every java source root of every module the ROOT POM declares, which is the scope both corpus
	 * walks take — never a hand-written list, for the reason {@link #SOURCE_ROOTS} gives.
	 *
	 * <p>A module contributes {@code src/main/java} and {@code src/test/java} where the directory
	 * exists, so a module with no tests costs nothing; a reactor that yields no root at all fails
	 * rather than walking nothing. And an anchor naming a root no module has fails too — that is the
	 * converse drift, an entry left behind by a rename, which would otherwise sit here looking like
	 * coverage of something.
	 */
	private static List<String> reactorSourceRoots() throws Exception {
		List<String> roots = new ArrayList<String>();
		for (String module : reactorModules()) {
			for (String kind : Arrays.asList("main", "test")) {
				String root = module + "/src/" + kind + "/java";
				if (Files.isDirectory(REPO_ROOT.resolve(root))) {
					roots.add(root);
				}
			}
		}
		if (roots.isEmpty()) {
			fail("None of the modules the root pom declares " + reactorModules() + " has a java source "
					+ "root under " + REPO_ROOT + ", so both corpus walks would read nothing and report no "
					+ "violations whatever the javadoc says.");
		}
		for (String anchored : SOURCE_ROOTS.keySet()) {
			if (!roots.contains(anchored)) {
				fail("SOURCE_ROOTS anchors " + anchored + ", which is not a java source root of any module\n"
						+ "the root pom declares (those are " + roots + "). An anchor for a root nothing walks\n"
						+ "checks nothing, and reads as coverage of it.");
			}
		}
		return roots;
	}

	/**
	 * The modules the root pom declares. A pom declaring none fails loudly: everything derived from
	 * this list would then be empty, and every walk and every POM check would pass by reading nothing.
	 */
	private static List<String> reactorModules() throws Exception {
		List<String> modules = modulesIn(pomRoot("pom.xml"));
		if (modules.isEmpty()) {
			fail("The root pom at " + REPO_ROOT.resolve("pom.xml") + " declares no <modules>, so the source "
					+ "roots and the POM list this guard derives from it would both be empty and every check "
					+ "over them would pass by reading nothing.");
		}
		return modules;
	}

	/**
	 * Every {@code <module>} one POM declares, wherever it sits — the project's own
	 * {@code <modules>} or a {@code <profile>}'s. Read document-wide for {@link #compilerPlugins}'
	 * reason, and it was read as a direct child of {@code <project>} alone: a module Maven builds
	 * under {@code -P} was then outside {@link #reactorSourceRoots}, outside {@link #poms} and so
	 * outside both corpus walks and every POM check, silently — the same shape as the hand-written
	 * root list {@link #SOURCE_ROOTS} warns about, arrived at by moving the declaration instead of
	 * deleting a line. The widening's own failure direction is loud: were some plugin's
	 * {@code <configuration>} to carry a stray {@code <module>}, {@link #pomRoot} fails on the POM it
	 * names rather than quietly widening anything.
	 */
	private static List<String> modulesIn(Element pom) {
		List<String> modules = new ArrayList<String>();
		NodeList declared = pom.getElementsByTagName("module");
		for (int i = 0; i < declared.getLength(); i++) {
			String name = declared.item(i).getTextContent().trim();
			if (!name.isEmpty() && !modules.contains(name)) {
				modules.add(name);
			}
		}
		return modules;
	}

	/**
	 * Every {@code <sourceDirectory>} or {@code <testSourceDirectory>} one POM declares, under any
	 * {@code <build>} — the project's own or a {@code <profile>}'s. Read as a direct child of a
	 * {@code <build>} and not document-wide, because a codegen plugin's {@code <configuration>} may
	 * legitimately carry an element of either name and that is not a module moving its sources.
	 *
	 * <p>{@link #reactorSourceRoots} probes {@code src/main/java} and {@code src/test/java} by
	 * CONVENTION, so a module that moves either contributes no root and is skipped — and skipped
	 * silently, since that walk only fails where EVERY module yields nothing. Its caller refuses such
	 * a POM rather than judging it, which is {@link #noOtherCompilerConfigurationDropsTheCheck}'s
	 * answer to a {@code <compilerId>} it cannot reason about.
	 */
	private static List<String> customSourceDirectoriesIn(Element pom) {
		List<String> declared = new ArrayList<String>();
		NodeList builds = pom.getElementsByTagName("build");
		for (int i = 0; i < builds.getLength(); i++) {
			for (String tag : Arrays.asList("sourceDirectory", "testSourceDirectory")) {
				Element directory = directChild((Element) builds.item(i), tag);
				if (directory != null) {
					declared.add("<" + tag + "> " + directory.getTextContent().trim());
				}
			}
		}
		return declared;
	}

	/**
	 * Every POM in the reactor: the root and one per declared module. Derived rather than listed for
	 * {@link #SOURCE_ROOTS}' reason — a module dropped from a hand-written list is a module whose
	 * {@code <compilerArgs>} override {@link #noOtherCompilerConfigurationDropsTheCheck} never reads.
	 * {@link #pomRoot} fails on one that does not exist, so a module without its own POM is reported
	 * rather than skipped.
	 */
	private static List<String> poms() throws Exception {
		List<String> poms = new ArrayList<String>();
		poms.add("pom.xml");
		for (String module : reactorModules()) {
			poms.add(module + "/pom.xml");
		}
		return poms;
	}

	/**
	 * The source roots {@link #everyJavadocReferenceInTheApiModuleResolves} can compile — those of
	 * {@link #API_MODULE}, taken from {@link #reactorSourceRoots} rather than written out. The
	 * restriction is the CLASSPATH's: this suite runs with the api module's, so {@code omod}'s sources
	 * are out of reach and are covered by the flag itself instead.
	 *
	 * <p>Its coverage is then asked of the FILESYSTEM and not of the filter above, which is the only
	 * reason that second loop is not the first one written twice: narrowing this corpus is invisible on
	 * a clean tree — the caller has nothing to find there — so a filter that quietly kept only
	 * {@code src/main/java} would pass every check. Mutate the filter and read this failure.
	 */
	private static List<String> apiRoots() throws Exception {
		List<String> roots = new ArrayList<String>();
		for (String root : reactorSourceRoots()) {
			if (root.startsWith(API_MODULE + "/")) {
				roots.add(root);
			}
		}
		if (roots.isEmpty()) {
			fail("The root pom declares no module named " + API_MODULE + " with a java source root, so this "
					+ "check would compile nothing and pass. This suite runs in that module: rename it here in "
					+ "the same commit that renames it in the reactor.");
		}
		for (String kind : Arrays.asList("main", "test")) {
			String root = API_MODULE + "/src/" + kind + "/java";
			if (Files.isDirectory(REPO_ROOT.resolve(root)) && !roots.contains(root)) {
				fail(root + " exists under " + REPO_ROOT + " and is not in the corpus\n"
						+ "everyJavadocReferenceInTheApiModuleResolves compiles, so every pointer in it would go\n"
						+ "unresolved by that check with the whole suite green.");
			}
		}
		return roots;
	}

	// --- Rules ---

	/**
	 * Every javadoc reference in {@code api/src/main/java} and {@code api/src/test/java} resolves,
	 * asked of the real compiler over the real files with the classpath this suite is running on.
	 *
	 * <p>This is the check that does not take the build's word about the compiler ARGUMENTS — it
	 * chooses its own. It does take it about the CORPUS: {@link #apiRoots} reads the root pom. In a
	 * normal build it is redundant — the compiler has already refused a dead reference before any
	 * test runs — and that redundancy is the point: it is what still fails when the declaration is
	 * present but not applied, which is every way of losing the gate that reading XML cannot see.
	 *
	 * <p>Scoped to the {@code api} module because that is the tree an api-side test can compile: the
	 * classpath here is this suite's own, so {@code omod}'s sources are out of reach. They are
	 * covered by the flag itself, and
	 * {@link #theArgumentsTheBuildDeclaresRefuseADeadJavadocReference} is what says the flag works.
	 *
	 * <p><strong>What makes an error doclint's is a DIFFERENCE, never its wording.</strong> Where the
	 * flagged compile reports errors, the same sources are compiled again WITHOUT the argument: what
	 * only the flagged run reports is doclint's, and anything the baseline reports too is a broken
	 * compile — reported loudly as a guard that could not run, because a guard that cannot run must
	 * not pass. Two earlier attempts matched the message text instead and each was refuted. A list of
	 * the group's messages is incomplete — it has fifteen keys, of which several are errors nobody
	 * lists — so a real javadoc defect was reported as a broken classpath. And
	 * {@code Diagnostic.getMessage(null)} is DEFAULT-LOCALE: doclint ships German, Japanese and
	 * Chinese translations, so on such a machine the match failed on a perfectly clean tree. The
	 * difference is immune to both, and to a future JDK renaming anything.
	 */
	@Test
	public void everyJavadocReferenceInTheApiModuleResolves() throws Exception {
		List<Path> sources = javaSourcesUnder(apiRoots());

		String classpath = System.getProperty("java.class.path");
		List<String> withTheCheck = compile(withTheReferenceCheck(), sources, classpath).errors();
		if (withTheCheck.isEmpty()) {
			return;
		}
		List<String> withoutIt = compile(BASELINE_ARGUMENTS, sources, classpath).errors();
		if (!withoutIt.isEmpty()) {
			fail("This guard could not run: these " + sources.size() + " api source(s) do not compile even\n"
					+ "WITHOUT " + REFERENCE_CHECK + " (" + withoutIt.size() + " error(s)), so a clean\n"
					+ "javadoc report would prove nothing. A broken compile classpath looks like this.\n\n"
					+ join(withoutIt));
		}
		fail(withTheCheck.size() + " javadoc reference error(s) in the api module — every one of these\n"
				+ "appears only when " + REFERENCE_CHECK + " is passed, so every one is doclint's:\n\n"
				+ join(withTheCheck)
				+ "\nThe javadoc is this module's design record (docs/adr.md, Decision 72), so a pointer\n"
				+ "that no longer resolves is a build failure rather than plain text. Fix the pointer — a\n"
				+ "FULLY-QUALIFIED {@link} resolves even where the enclosing-qualified form does not.");
	}

	/**
	 * The arguments the root pom MANAGES really make a dead reference a compile ERROR, on the JDK
	 * running this suite, and leave a live one alone. It reddens if the argument is dropped,
	 * misspelled into a no-op, or demoted to a warning by a future JDK — and the live half reddens if
	 * a future JDK rejects the flag outright, which would otherwise fail every build for a reason
	 * nobody would trace here.
	 *
	 * <p>The pair is what carries it. A dead reference failing alone would also be satisfied by an
	 * argument list that refuses to compile anything at all.
	 *
	 * <p>The whole managed list goes to the compiler rather than the doclint arguments alone, because
	 * what this asks is whether the build's arguments AS A SET refuse a dead pointer. This suite's own
	 * classpath goes with them, so an argument added later that needs one does not fail the live half
	 * for a reason that has nothing to do with javadoc.
	 */
	@Test
	public void theArgumentsTheBuildDeclaresRefuseADeadJavadocReference() throws Exception {
		List<String> managed = rootManagedCompilerArgs();
		if (managed.isEmpty()) {
			List<String> poms = poms();
			List<String> elsewhere = new ArrayList<String>();
			for (String pom : poms) {
				for (Element plugin : compilerPlugins(pom)) {
					for (String where : compilerArgBlocks(plugin).keySet()) {
						elsewhere.add(pom + " " + where);
					}
				}
			}
			fail("The root pom's <build>/<pluginManagement> entry for " + COMPILER_PLUGIN + " declares no\n"
					+ "plugin-level <compilerArgs>. That is the one position api and omod both inherit, and the\n"
					+ "one both compile and testCompile receive.\n\n"
					+ (elsewhere.isEmpty() ? "  No <compilerArgs> block was found anywhere in " + poms + ".\n"
							: "  <compilerArgs> was found instead at:\n" + join(elsewhere))
					+ "\nMeasured: declared in api/pom.xml alone, a dead javadoc reference in omod/src/main/java\n"
					+ "compiles and the whole build reports success — issue #262. See docs/adr.md, Decision 72.");
		}

		// The message is a Supplier deliberately: building it compiles the probe again, and as an
		// eagerly-evaluated String argument that ran on every PASSING run too.
		assertTrue(refusesADeadReference("the root pom's managed <compilerArgs>", managed),
				() -> {
					try {
						return "the arguments the root pom manages " + managed + " do not make a dead javadoc "
								+ "reference on a non-public member an error, so a dead pointer is silent again "
								+ "— issue #262:\n"
								+ compileOne(managed, "DeadReference", DEAD_REFERENCE_SOURCE).report();
					}
					catch (IOException e) {
						return "the arguments the root pom manages " + managed + " do not make a dead javadoc "
								+ "reference on a non-public member an error (issue #262), and the probe could "
								+ "not be recompiled to show why: " + e;
					}
				});
	}

	/**
	 * No compiler configuration anywhere in these POMs drops the check. Maven does not merge a
	 * child's {@code <compilerArgs>} with the managed one — it REPLACES it — and an
	 * execution-scoped {@code <configuration>} replaces it for that execution, so either is a way to
	 * lose the check while the root pom still appears to declare it. Plugin elements are collected
	 * document-wide, so a declaration moved into a {@code <profile>} is read here too.
	 *
	 * <p>Each block is put to the real compiler rather than matched as a string, for
	 * {@link #refusesADeadReference}'s reason: a widened doclint list is a correct configuration and
	 * a prefix match calls it a removal.
	 *
	 * <p>{@code <failOnError>} and {@code <compilerId>} are asked about for the same reason and not
	 * as second subjects. Turning the first off leaves every doclint error printed and none of them
	 * fatal, which is the green build #262 reports. The second selects the compiler backend, and
	 * {@code -Xdoclint} is a javac option — so anything but {@code javac} is refused here rather
	 * than judged, because this guard cannot know whether another backend honours the argument. A
	 * wrapper that does honour it is refused too; say so in the same commit that changes the id.
	 */
	@Test
	public void noOtherCompilerConfigurationDropsTheCheck() throws Exception {
		List<String> violations = new ArrayList<String>();
		for (String pom : poms()) {
			for (Element plugin : compilerPlugins(pom)) {
				for (Map.Entry<String, List<String>> block : compilerArgBlocks(plugin).entrySet()) {
					if (!refusesADeadReference(pom + " " + block.getKey(), block.getValue())) {
						violations.add(pom + " " + block.getKey() + " declares <compilerArgs> "
								+ block.getValue()
								+ ", which the compiler does not refuse a dead javadoc reference under"
								+ " — and a <compilerArgs> block REPLACES the managed one, so this drops the check");
					}
				}
				for (String where : disabledFailOnErrorAt(plugin)) {
					violations.add(pom + " " + where + " sets <failOnError>false</failOnError> — a doclint "
							+ "reference error is then printed and not fatal, which is the green build #262 reports");
				}
				for (String where : nonJavacCompilerIdAt(plugin)) {
					violations.add(pom + " " + where + " — " + REFERENCE_CHECK + " is a javac option, and this "
							+ "guard cannot tell whether that backend honours it. Refused rather than judged: if "
							+ "it does honour it, say so in the commit that changes the id");
				}
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * The corpus both walks take is every module MAVEN builds, and every module keeps its sources
	 * where those walks look. Two ways of losing a module from the scope without deleting anything,
	 * both silent and both fail-OPEN, which is what {@link #SOURCE_ROOTS} exists to have stopped
	 * happening by hand.
	 *
	 * <p><strong>Declared in a {@code <profile>}.</strong> {@link #modulesIn} reads {@code <module>}
	 * document-wide, so a module built under {@code -P} is in the scope. It was read as a direct child
	 * of {@code <project>} alone, which put such a module outside both corpus walks and outside every
	 * POM check at once. Asked of a synthetic POM and not of this repository's, which carries no
	 * {@code <profile>} at all — so there is nothing here for a direct-child read to get wrong, and
	 * that is exactly why the narrower version was invisible.
	 *
	 * <p><strong>Moved off the convention.</strong> {@link #reactorSourceRoots} probes
	 * {@code src/main/java} and {@code src/test/java}, so a module declaring its own
	 * {@code <sourceDirectory>} contributes no root and is skipped — and that walk fails only where
	 * EVERY module yields nothing, so one such module is silence. REFUSED rather than judged, the
	 * same answer {@link #noOtherCompilerConfigurationDropsTheCheck} gives a {@code <compilerId>} it
	 * cannot reason about: whoever moves a source directory teaches these walks about it in the same
	 * commit.
	 */
	@Test
	public void theCorpusCoversEveryModuleTheBuildCompiles() throws Exception {
		List<String> violations = new ArrayList<String>();
		for (String pom : poms()) {
			for (String declared : customSourceDirectoriesIn(pomRoot(pom))) {
				violations.add(pom + " declares " + declared + ", and the source roots this guard walks "
						+ "are derived by CONVENTION — src/main/java and src/test/java of each declared "
						+ "module. That module's sources are outside noJavadocBlockIsOrphaned and "
						+ "noFileOpensWithAJavadocBlockBeforeItsPackageStatement, silently. Refused rather "
						+ "than judged: teach reactorSourceRoots about the directory in the same commit");
			}
		}
		String inAProfile = "moduleBuiltUnderAProfile";
		List<String> modules = modulesIn(parseXml("<project><modules><module>api</module></modules>"
				+ "<profiles><profile><id>extra</id><modules><module>" + inAProfile
				+ "</module></modules></profile></profiles></project>"));
		if (!modules.contains(inAProfile)) {
			violations.add("a <module> declared inside a <profile> is not in the reactor list this guard "
					+ "derives (it read " + modules + "). Maven builds it under -P, so its sources and its "
					+ "POM would be outside both corpus walks and every POM check here, with nothing to "
					+ "notice: this repository has no <profile>, so only this synthetic POM can say so");
		}
		String moved = "src/generated/java";
		List<String> movedAway = customSourceDirectoriesIn(parseXml("<project><build><sourceDirectory>"
				+ moved + "</sourceDirectory></build><profiles><profile><build><testSourceDirectory>"
				+ moved + "</testSourceDirectory></build></profile></profiles></project>"));
		if (movedAway.size() != 2) {
			violations.add("a <sourceDirectory> under <build> and a <testSourceDirectory> under a "
					+ "<profile>'s <build> are not both reported as moving a module off the convention "
					+ "reactorSourceRoots probes (it read " + movedAway + "). Such a module contributes no "
					+ "source root and is skipped in silence, which is what the refusal exists to stop");
		}
		List<String> inPluginConfig = customSourceDirectoriesIn(parseXml("<project><build><plugins>"
				+ "<plugin><configuration><sourceDirectory>" + moved
				+ "</sourceDirectory></configuration></plugin></plugins></build></project>"));
		if (!inPluginConfig.isEmpty()) {
			violations.add("a <sourceDirectory> inside a plugin's own <configuration> is reported as a "
					+ "module moving its sources (it read " + inPluginConfig + "), which it is not — a "
					+ "codegen plugin may legitimately take a parameter of that name. Read the element as a "
					+ "direct child of a <build> and never document-wide, or this refusal reddens a clean "
					+ "build");
		}
		assertNoViolations(violations);
	}

	/**
	 * Every javadoc block attaches to a declaration, so that doclint reads the pointers inside it. The
	 * shapes that fail it are enumerated below, each measured against the real compiler rather than
	 * reasoned about, and each leaving the gate a hole exactly the size of that block:
	 *
	 * <ul>
	 * <li>a block immediately followed by ANOTHER javadoc block — Java attaches only the last, and the
	 * earlier one is discarded;</li>
	 * <li>a block followed by no declaration at all before the enclosing brace or the end of the file;</li>
	 * <li>a block stranded BETWEEN a declaration's annotations and the declaration itself, which javac
	 * ignores because a doc comment has to precede the whole declaration, annotations included — see
	 * {@link #isAnnotationAlone}, which decides what counts as an annotation line and was simply off
	 * for any annotation carrying a brace in its own argument list;</li>
	 * <li>a block followed by an INITIALISER block, static or instance, which is not a declaration —
	 * see {@link #opensAnInitialiserBlock};</li>
	 * <li>a block followed by an {@code import}, which javac attaches nothing to and, unlike the
	 * {@code package} statement, does not even warn about — see {@link #isImportDeclaration}.</li>
	 * </ul>
	 *
	 * <p><strong>No count of them is stated, and the first version of this stated three.</strong> The
	 * last two arrived a review round later, both of them measured holes of exactly the kind above and
	 * neither reported by any check here; the enumeration is the list and {@link #SHAPES} is what
	 * holds ground truth for it. So add a row and a bullet rather than adjusting a number.
	 *
	 * <p>The FIRST shape is not hypothetical. Three blocks were in it when the check went in, and each
	 * arose the same way — a member inserted above the comment written for the one below it:
	 * {@code LlmProvider.parseEntailmentVerdict}'s block (pointers at
	 * {@code ChartAnswerResponseFormat}, {@code parseYesNo} and {@code extractResponse}, none of them
	 * resolved by anything), {@code CitationGroundingVerifier.LEADING_ITEM_SEPARATOR}'s, and
	 * {@link ModuleSourceRoot}'s own block for {@code apiRoot()}. Each was found by a human reader on
	 * the two previous occasions it happened, which is exactly what a mechanical check is for.
	 *
	 * <p>The rest had no instance here and are checked because they are the same defect. A probe
	 * carrying the trailing shape put four dead pointers to the compiler and got three errors back; one
	 * carrying a block on each side of an annotation got an error for the block ABOVE it and none for
	 * the block below; one carrying a dead pointer above a static initialiser, above an instance
	 * initialiser and above an {@code import} got no diagnostic of any kind for any of the three, while
	 * the same pointer on a method in the same file was reported. The initialiser shape is one edit
	 * away from real code: every static initialiser in this repository is immediately preceded by the
	 * field it fills, and most of those fields carry a javadoc block — so an initialiser inserted
	 * above one is the same "member inserted above the comment written for the one below it" defect,
	 * and before this arm it would have taken that javadoc out of the gate in silence.
	 *
	 * <p>The ANNOTATION shape was found by accident — a verification probe of this very change
	 * inserted a comment after a controller's {@code @Controller} and {@code @RequestMapping} lines and
	 * then reported the gate as not reaching that module. The gate was fine and the probe was dangling;
	 * an author can make the same mistake.
	 *
	 * <p><strong>An intervening line comment, plain block comment or annotation does not detach the
	 * block ABOVE it, and does not rescue one either.</strong> That distinction was got wrong once, in
	 * both directions. What decides is the next thing that is neither blank nor a comment: a block, a
	 * one-line note, and then a second block still loses the first — the historical shape here with a
	 * note added by the same edit. So this scans comments properly rather than skipping blank lines,
	 * and a {@code /**}{@code /} or a commented-out doc block (a {@code /**} line inside a plain block
	 * comment) is not a javadoc block at all.
	 *
	 * <p>No shape here is ever intentional — the block documents nothing, and rendered output and IDE
	 * tooltips show only what attached. The remedy is to move it to the member it was written for,
	 * never to delete it.
	 */
	@Test
	public void noJavadocBlockIsOrphaned() throws Exception {
		List<Path> sources = javaSourcesUnder(reactorSourceRoots());
		List<String> violations = new ArrayList<String>();
		for (Path source : sources) {
			for (String orphan : unattachedJavadocBlocks(Files.readAllLines(source, StandardCharsets.UTF_8))) {
				violations.add(REPO_ROOT.relativize(source) + ": " + orphan
						+ ", so it documents nothing and its pointers are never resolved. Move it to the "
						+ "member it was written for.");
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * One sentence per javadoc block in the file that attaches to no declaration, naming its line and
	 * which of the shapes {@link #noJavadocBlockIsOrphaned} enumerates it is.
	 *
	 * <p>Tracked as a state machine over block starts and ends rather than matched with one regex,
	 * because a one-line block and a multi-line one have to be recognised alike, and a plain block
	 * comment opening with a single asterisk must not be recognised at all. Blank lines after a block
	 * do not save it: Java's attachment ignores them, so the next NON-BLANK line is what decides.
	 *
	 * <p>Two shapes it once mis-tracked, neither present in this repository when they were found and
	 * both now handled rather than left latent: {@code /**}{@code /}, which is an EMPTY block comment
	 * and not a javadoc open at all, and a one-line block with code after it on the same line, which
	 * closes without the line ending in the terminator. Under the earlier {@code endsWith} test both
	 * put the scanner into a block it never left until the next terminator, which shifts every line
	 * number it goes on to report.
	 */
	private static List<String> unattachedJavadocBlocks(List<String> lines) {
		List<Item> items = scan(lines);
		List<String> orphans = new ArrayList<String>();
		for (int i = 0; i < items.size(); i++) {
			Item item = items.get(i);
			if (!item.javadoc) {
				continue;
			}
			Item previous = i > 0 ? items.get(i - 1) : null;
			if (previous != null && !previous.javadoc && isAnnotationAlone(previous.text)) {
				orphans.add("the javadoc block closing at line " + item.line
						+ " sits between an annotation at line " + previous.line
						+ " and the declaration it annotates, where javac ignores it — a doc comment has to "
						+ "precede the whole declaration, annotations included");
				continue;
			}
			Item next = i + 1 < items.size() ? items.get(i + 1) : null;
			if (next == null) {
				orphans.add("the javadoc block closing at line " + item.line
						+ " is followed by nothing but comments and whitespace to the end of the file");
			}
			else if (next.javadoc) {
				orphans.add("the javadoc block closing at line " + item.line
						+ " is followed by another opening at line " + next.line);
			}
			else if (next.text.startsWith("}")) {
				orphans.add("the javadoc block closing at line " + item.line
						+ " is followed by a closing brace at line " + next.line
						+ " rather than by a declaration");
			}
			else if (opensAnInitialiserBlock(next, i + 2 < items.size() ? items.get(i + 2) : null)) {
				orphans.add("the javadoc block closing at line " + item.line
						+ " is followed by an initialiser block at line " + next.line
						+ " rather than by a declaration, and an initialiser is not one — javac attaches no "
						+ "doc comment to it");
			}
			else if (isImportDeclaration(next.text)) {
				orphans.add("the javadoc block closing at line " + item.line
						+ " is followed by an import declaration at line " + next.line
						+ ", which javac attaches no doc comment to and says nothing about. A block above "
						+ "the package statement is at least read and warned about; this position is silent");
			}
		}
		return orphans;
	}

	/**
	 * Whether the content line after a javadoc block opens an INITIALISER block — a static initialiser
	 * or a bare instance one — which is not a declaration, so javac attaches the block to nothing and
	 * doclint reads none of its pointers. Measured on JDK 21: a probe carrying a dead pointer above a
	 * static initialiser and a second above an instance initialiser returned no error and no warning
	 * for either, while the same pointer on a method returned one.
	 *
	 * <p>This shape is one edit away from real code here. Every static initialiser in this repository
	 * is immediately preceded by the field it fills, and most of those fields carry a javadoc block —
	 * so an initialiser inserted above such a field is exactly the "member inserted above the comment
	 * written for the one below it" defect the caller exists for, and before this arm it would have
	 * taken that javadoc out of the gate in silence.
	 *
	 * <p>An opening brace at the start of the line is enough on its own: no Java DECLARATION can begin
	 * with one, so there is nothing to false-positive on. The {@code static} form needs the brace
	 * FOUND, because {@code static final int a = 1;} begins with the same word — so the brace is
	 * looked for on the same content line, and, where the line is the bare word, on the NEXT one.
	 * Without that second lookahead a declaration split as {@code static} then
	 * {@code final int a = 1;} would be reported, which is a red build on legal code.
	 */
	private static boolean opensAnInitialiserBlock(Item next, Item after) {
		if (next.text.startsWith("{")) {
			return true;
		}
		if (!next.text.startsWith("static")) {
			return false;
		}
		String rest = next.text.substring("static".length()).trim();
		if (rest.isEmpty()) {
			return after != null && after.text.startsWith("{");
		}
		return rest.startsWith("{");
	}

	/**
	 * Whether the content line after a javadoc block is an {@code import} declaration, which javac
	 * attaches no doc comment to — and, unlike the {@code package} statement, says nothing at all
	 * about. Measured on JDK 21: a dead pointer above an {@code import} produced neither an error nor
	 * a warning, while the same pointer above {@code package} produced the error AND
	 * {@code documentation comment not expected here}. So the pre-{@code package} position is inside
	 * the gate and is guarded for its WARNING by
	 * {@link #noFileOpensWithAJavadocBlockBeforeItsPackageStatement}, while this one is a hole and
	 * belongs here.
	 *
	 * <p>The word has to be followed by whitespace or nothing: {@code importantValue = 1;} begins with
	 * the same six characters.
	 */
	private static boolean isImportDeclaration(String text) {
		return text.equals("import")
				|| (text.startsWith("import") && Character.isWhitespace(text.charAt("import".length())));
	}

	/**
	 * Whether a content line is an annotation and NOTHING else. The annotation arm turns on this and
	 * not on "starts with {@code @}", because one content line is recorded per SOURCE line: a line
	 * that is both the annotation and the declaration
	 * ({@code @SuppressWarnings("unused") private int a = 1;}) starts with {@code @} while annotating
	 * itself, and a javadoc block after it is attached to whatever comes next. Measured — javac reads
	 * that block, and the earlier rule reported a violation telling the author to move documentation
	 * the compiler had read.
	 *
	 * <p><strong>Decided by what is LEFT once the annotations are consumed, and three character
	 * exclusions decided it before.</strong> Those were a terminator, an opening brace, or a trailing
	 * comma anywhere on the line — and an annotation's own argument list carries all three. This
	 * module's single controller has an {@code @ExceptionHandler} taking a braced class array;
	 * {@code @ValueSource(booleans = { false, true })} and every
	 * {@code @ParameterizedTest(name = "[{index}] {0}")} in this suite were all read as carrying a
	 * declaration, so the arm was simply off for them and a block stranded after one was reported by
	 * nothing — the hole this rule exists to close, on the shape the caller documents as the only one
	 * with real instances here. Testing the LAST character instead trades that for a
	 * false positive, since a trailing {@code // note} on an annotated declaration moves the
	 * terminator off the end of the line. So {@link #annotationResidue} consumes {@code @Name} and its
	 * balanced argument list, repeatedly, and this asks whether anything is left.
	 *
	 * <p><strong>A row of {@link #SHAPES} per residue the test has to see, on both sides of it.</strong>
	 * Where javac DOES read the block: {@code AnnotatedDeclarationOnOneLineThenBlock}
	 * leaves a field declaration; {@code AnnotatedTypeOpenThenBlock} leaves an annotated type whose
	 * body brace is on the same line and whose first member's javadoc javac reads;
	 * {@code AnnotatedEnumConstantThenBlock} leaves an annotated enum constant, the same line shape as
	 * this module's {@code @RequestParam} parameter lines, which annotates itself rather than what
	 * follows. A residue test that missed any of those turns a clean build red. Where javac reads
	 * nothing and the arm has to fire: {@code AnnotationArgumentCarriesABrace} and
	 * {@code AnnotationArgumentCarriesATerminator}. And the row that refuses the last-character version
	 * of this fix is {@code AnnotatedDeclarationWithATrailingNoteThenBlock}. Mutate the residue test,
	 * or substitute the character exclusions for it, and read which rows go red.
	 *
	 * <p>What it does not reach: an annotation whose argument list is left UNTERMINATED on the line,
	 * which {@link #annotationResidue} refuses rather than guesses, and an annotation line carrying a
	 * trailing comment, whose residue is that comment. Both fail in the safe direction — a block
	 * stranded there is missed rather than a clean build reddened — and both are the same
	 * one-content-line-per-source-line limit {@link #scan} states for multi-line annotations. The
	 * first is DECLARED rather than left unwritten, as {@code AnnotationArgumentListLeftOpenThenBlock}
	 * ({@link Attachment#UNATTACHED_AND_UNREACHED}), so turning the refusal into a guess reddens it.
	 */
	private static boolean isAnnotationAlone(String text) {
		return text.startsWith("@") && annotationResidue(text).isEmpty();
	}

	/**
	 * What is left of a content line after every leading {@code @Name} and its balanced argument list
	 * has been consumed — the empty string where the line is annotations and nothing else.
	 *
	 * <p>Literals inside the argument list are skipped, so a quoted brace or terminator is part of the
	 * annotation rather than evidence of a declaration. An argument list left OPEN at the end of the
	 * line is refused: the whole line comes back as residue, so {@link #isAnnotationAlone} says no.
	 * That is the conservative answer of the two — the caller's arm then reports nothing where a
	 * multi-line annotation is involved, which is a missed orphan and not a red build on legal code.
	 */
	private static String annotationResidue(String text) {
		int i = 0;
		while (i < text.length() && text.charAt(i) == '@') {
			i++;
			while (i < text.length()
					&& (Character.isJavaIdentifierPart(text.charAt(i)) || text.charAt(i) == '.')) {
				i++;
			}
			while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
				i++;
			}
			if (i < text.length() && text.charAt(i) == '(') {
				int end = endOfArgumentList(text, i);
				if (end < 0) {
					return text;
				}
				i = end;
			}
			while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
				i++;
			}
		}
		return text.substring(i);
	}

	/**
	 * The index just past the {@code )} closing the argument list that opens at {@code open}, or
	 * {@code -1} where the line ends inside it. Nesting is counted and string and character literals
	 * are skipped whole, which is the only reason this is not {@code indexOf(')')}.
	 *
	 * <p><strong>One row of {@link #SHAPES} per property claimed here, because every one of them was
	 * deletable with every check here green.</strong> No shipped annotation argument list carries a
	 * nested paren or a {@code )} inside a literal, so on this corpus the balanced walk and
	 * {@code indexOf(')')} answer alike — and under the cheaper version the stranded-block arm
	 * switches back off for {@code @ParameterizedTest(name = "run(x)")} or {@code @Qualifier("a)b")},
	 * with nothing to notice, which is the defect {@link #annotationResidue} exists to fix. Nesting is
	 * {@code AnnotationArgumentNestsAParen}; the two separately deletable halves of the literal skip
	 * are {@code AnnotationArgumentQuotesACloseParen} and {@code AnnotationArgumentIsACharCloseParen};
	 * and the {@code -1}, which the caller turns into the conservative answer, is
	 * {@code AnnotationArgumentListLeftOpenThenBlock} — the one of these rows the arm must NOT fire
	 * on. Mutate a clause and read which of them reddens.
	 */
	private static int endOfArgumentList(String text, int open) {
		int depth = 0;
		for (int i = open; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"' || c == '\'') {
				i++;
				while (i < text.length() && text.charAt(i) != c) {
					i += text.charAt(i) == '\\' ? 2 : 1;
				}
			}
			else if (c == '(') {
				depth++;
			}
			else if (c == ')' && --depth == 0) {
				return i + 1;
			}
		}
		return -1;
	}

	/** One javadoc block, or one line of real content: what the scan below reduces a file to. */
	private static final class Item {

		private final boolean javadoc;

		private final int line;

		private final String text;

		private Item(boolean javadoc, int line, String text) {
			this.javadoc = javadoc;
			this.line = line;
			this.text = text;
		}
	}

	/**
	 * Reduces a file to its javadoc blocks and its lines of real content, in order — dropping blank
	 * space, line comments, plain block comments and the insides of literals. A javadoc block is
	 * recorded at the line it CLOSES on, which is what the failure messages name; a content line is
	 * recorded once, as the text from its first real character to the end of the line, which is what
	 * the closing-brace and annotation tests read.
	 *
	 * <p><strong>A character lexer over the whole file, and the earlier line-oriented version is why.</strong>
	 * That one decided each line by its trimmed prefix and stopped at the first content, so a comment
	 * OPENED AFTER CODE on a line was invisible and every line after it was read in the wrong state.
	 * Measured: {@code private int a = 1; /*} followed by a {@code /**}-looking line inside that
	 * comment and then a closing brace made this report a violation where javac sees no doc comment at
	 * all — a false positive on legal code, so a clean build fails. That was the last of several ways
	 * the line version disagreed with javac, and it is what settled the shape of this method. No count
	 * of those is given, here or anywhere: see {@link #SHAPES}.
	 *
	 * <p>Literals are tracked because a {@code /**} inside a string literal is reached on every run
	 * TODAY, at source level 11 and with no text block involved: {@link #SHAPES} is a table of string
	 * literals carrying that sequence and the terminator, and this file is inside the corpus the walk
	 * reads. An earlier version of this paragraph called the arm unreachable and offered a text block
	 * as the day it would change — the argument of the retired line-oriented scanner, which decided a
	 * line by its trimmed PREFIX and so could not meet a literal mid-line. Disable the arm and read the
	 * violations this file's own table produces.
	 *
	 * <p>What it still does not do is decide which lines belong to which DECLARATION, so the
	 * annotation rule reads the immediately preceding content line: a multi-line annotation whose last
	 * line is a bare {@code )} leaves that as the preceding content, and a block stranded after THAT is
	 * not detected. Stated rather than fixed — that one is a parser.
	 *
	 * <p>{@code SourceScan.blanked} is not reused, though it lexes comments and literals correctly:
	 * it BLANKS comments, and the comments are exactly what this has to locate.
	 */
	private static List<Item> scan(List<String> lines) {
		String text = String.join("\n", lines);
		for (String escape : COMMENT_DELIMITER_ESCAPES) {
			if (text.toLowerCase().contains(escape)) {
				fail("This guard cannot lex a source containing " + escape + ": javac translates unicode "
						+ "escapes BEFORE it lexes, so that sequence begins or ends a comment for the compiler "
						+ "and not for this scan, and every line number after it would be wrong. Refused "
						+ "rather than answered — measured on JDK 11, 17, 21 and 25.");
			}
		}
		List<Item> items = new ArrayList<Item>();
		int line = 1;
		int recordedContentOn = 0;
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c == '\n') {
				line++;
				i++;
				continue;
			}
			if (Character.isWhitespace(c)) {
				i++;
				continue;
			}
			if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
				while (i < text.length() && text.charAt(i) != '\n') {
					i++;
				}
				continue;
			}
			if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
				boolean javadoc = i + 2 < text.length() && text.charAt(i + 2) == '*'
						&& !(i + 3 < text.length() && text.charAt(i + 3) == '/');
				i += javadoc ? 3 : 2;
				while (i + 1 < text.length() && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
					if (text.charAt(i) == '\n') {
						line++;
					}
					i++;
				}
				i = Math.min(i + 2, text.length());
				if (javadoc) {
					items.add(new Item(true, line, ""));
				}
				continue;
			}
			if (recordedContentOn != line) {
				int endOfLine = text.indexOf('\n', i);
				items.add(new Item(false, line,
						text.substring(i, endOfLine < 0 ? text.length() : endOfLine).trim()));
				recordedContentOn = line;
			}
			if (c == '"' || c == '\'') {
				char quote = c;
				i++;
				while (i < text.length() && text.charAt(i) != quote) {
					if (text.charAt(i) == '\\') {
						i++;
					}
					if (i < text.length() && text.charAt(i) == '\n') {
						line++;
					}
					i++;
				}
				i++;
				continue;
			}
			i++;
		}
		return items;
	}

	/**
	 * No file opens with a javadoc block before its {@code package} statement.
	 *
	 * <p>A comment there documents nothing — javadoc attaches to declarations and a package statement
	 * is not one — so with the check enabled javac reports every such file. Nearly every source in
	 * this module carried an MPL licence header written that way, and they became plain block comments
	 * in the same change that enabled the check. The count is recorded once, in docs/adr.md Decision
	 * 71, with the tree it was measured on and the command that measured it — not copied here, because
	 * a count of sources tracks the code: the figure this sentence used to carry was wrong in both its
	 * numerator and its denominator while the ADR beside it was right.
	 *
	 * <p>This exists because that normalisation would otherwise decay. The form is a WARNING on every
	 * JDK measured (11, 17, 21, 24, 25), never an error, so one file arriving with the old header is
	 * green — and the surrounding convention pulls that way: openmrs-core writes its own headers as
	 * javadoc, so a file copied from there, or an IDE template, reinstates it. Nothing else in this
	 * repository or in the org's shared workflows reads a source header at all.
	 *
	 * <p>It asks about the JAVADOC FORM and not about the header, deliberately: some sources carry no
	 * licence header whatever, which is pre-existing and none of this ticket's business, and they
	 * pass. What fails is a comment that documents nothing while looking like documentation.
	 *
	 * <p><strong>{@code package-info.java} is exempt, and the exemption is not a convenience.</strong>
	 * A javadoc block before the {@code package} statement is the only way to document a package, and
	 * it is the one position where javac ATTACHES it: measured on JDK 21, that file's block produced
	 * {@code error: reference not found} for a dead pointer and no {@code documentation comment not
	 * expected here} warning at all, while the same block in an ordinary source produced both. So this
	 * rule's premise is false there in both directions — the form is legal, and the remedy the message
	 * prints would take a pointer doclint currently resolves out of the gate, which is #262's own
	 * defect reinstated on this guard's instruction. Verified by applying it: with the block opened
	 * {@code /*} instead, the same dead pointer compiles silently.
	 */
	@Test
	public void noFileOpensWithAJavadocBlockBeforeItsPackageStatement() throws Exception {
		List<String> violations = new ArrayList<String>();
		for (Path source : javaSourcesUnder(reactorSourceRoots())) {
			if (opensWithAnUnattachedJavadocBlock(source,
					Files.readAllLines(source, StandardCharsets.UTF_8))) {
				violations.add(REPO_ROOT.relativize(source) + " opens with a javadoc block, before its "
						+ "package statement, where it documents nothing. Open it with /* instead — with "
						+ REFERENCE_CHECK + " in force javac reports every one of these, and the FORM draws "
						+ "only a warning, so nothing else would notice. (A package-info.java is exempt and "
						+ "must stay a javadoc block: see this rule's javadoc.)");
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * Whether one source opens with a javadoc block that documents nothing, which is
	 * {@link #noFileOpensWithAJavadocBlockBeforeItsPackageStatement}'s whole decision — extracted so
	 * that {@link #SHAPES} can declare the answer for a shape and
	 * {@link #theScannerAgreesWithTheCompilerAboutWhatIsAttached} can hold this against it. Without
	 * that the {@code package-info.java} exemption was deletable with the whole suite green, since
	 * this repository carries no such file.
	 *
	 * <p>{@code /**}{@code /} is an EMPTY block comment and not a javadoc open, the same case
	 * {@link #scan} handles.
	 */
	private static boolean opensWithAnUnattachedJavadocBlock(Path source, List<String> lines) {
		if ("package-info.java".equals(source.getFileName().toString())) {
			return false;
		}
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			return trimmed.startsWith("/**") && !trimmed.startsWith("/**/");
		}
		return false;
	}

	/**
	 * The scanner agrees with the real compiler about which javadoc blocks are attached, over a table
	 * of shapes — and so does the rule about a block before the {@code package} statement.
	 *
	 * <p>This is the check {@link #noJavadocBlockIsOrphaned} rests on. That one is a heuristic
	 * standing in for javac's attachment rule, and that heuristic was got wrong repeatedly — false
	 * positives on legal code and false negatives on real orphans, in both directions, each of them
	 * passing the whole suite. So the rule is not asserted in prose here: every shape below declares
	 * what it IS,
	 * and BOTH the compiler and the scanner are held to that declaration. The compiler half is what
	 * makes the declaration ground truth rather than opinion.
	 *
	 * <p>Each shape is compiled twice, and what makes an error doclint's is that it appears only in
	 * the flagged run — {@link #everyJavadocReferenceInTheApiModuleResolves}' rule, for its reasons.
	 * {@link Attachment#NOT_A_JAVADOC_BLOCK} is the case neither side should see anything in, and it
	 * is why this cannot be written as "one flags it exactly when the other does not": a shape with no
	 * doc comment in it is read by neither.
	 *
	 * <p>Each row also declares {@link #noFileOpensWithAJavadocBlockBeforeItsPackageStatement}'s
	 * answer for its file, because that rule had no ground truth of any kind and its
	 * {@code package-info.java} exemption was deletable with the whole suite green — this repository
	 * carries no such file, so the corpus walk can say nothing about it. The two whole-file rows that
	 * put a block before a {@code package} statement are the ones where the answers differ, and the
	 * compiler is held to both of them: doclint READS the pointer either way, which is what makes the
	 * exemption necessary rather than tidy.
	 */
	@Test
	public void theScannerAgreesWithTheCompilerAboutWhatIsAttached() throws Exception {
		Path dir = Files.createTempDirectory("javadoc-attachment-shapes");
		try {
			List<Path> files = new ArrayList<Path>();
			for (Map.Entry<String, Shape> shape : SHAPES.entrySet()) {
				Path file = dir.resolve(shape.getKey() + ".java");
				Files.write(file, shape.getValue().sourceFor(shape.getKey())
						.getBytes(StandardCharsets.UTF_8));
				files.add(file);
			}
			Set<String> baseline = shapesWithErrors(compile(BASELINE_ARGUMENTS, files, null));
			if (!baseline.isEmpty()) {
				fail("These shapes do not compile even without " + REFERENCE_CHECK + ", so this check would "
						+ "prove nothing: " + baseline);
			}
			Set<String> doclintRead = shapesWithErrors(compile(withTheReferenceCheck(), files, null));

			List<String> violations = new ArrayList<String>();
			for (Map.Entry<String, Shape> entry : SHAPES.entrySet()) {
				String shape = entry.getKey();
				Path written = dir.resolve(shape + ".java");
				List<String> lines = Files.readAllLines(written, StandardCharsets.UTF_8);
				Attachment declared = entry.getValue().attachment;
				boolean read = doclintRead.contains(shape);
				boolean flagged = !unattachedJavadocBlocks(lines).isEmpty();
				if (read != declared.doclintReadsIt) {
					violations.add(shape + " is declared " + declared + " but the COMPILER "
							+ (read ? "read" : "did not read") + " its pointer — the declaration is wrong, "
							+ "or this JDK's attachment rule has moved");
				}
				if (flagged != declared.scannerFlagsIt) {
					violations.add(shape + " is declared " + declared + " but the SCANNER "
							+ (flagged ? "flagged" : "did not flag") + " it — unattachedJavadocBlocks and javac "
							+ "no longer agree, so noJavadocBlockIsOrphaned is reporting the wrong blocks");
				}
				boolean dangling = opensWithAnUnattachedJavadocBlock(written, lines);
				if (dangling != entry.getValue().opensWithADanglingBlock) {
					violations.add(shape + " is declared to open with "
							+ (entry.getValue().opensWithADanglingBlock ? "a " : "no ")
							+ "javadoc block that documents nothing, and the header rule "
							+ (dangling ? "says it does" : "says it does not")
							+ " — noFileOpensWithAJavadocBlockBeforeItsPackageStatement is judging the wrong "
							+ "files, and a block javac ATTACHES is the one it must not touch");
				}
			}
			assertNoViolations(violations);
		}
		finally {
			deleteRecursively(dir);
		}
	}

	/** {@link #BASELINE_ARGUMENTS} with the check in front, which is the only other list used here. */
	private static List<String> withTheReferenceCheck() {
		List<String> arguments = new ArrayList<String>(BASELINE_ARGUMENTS);
		arguments.add(0, REFERENCE_CHECK);
		return arguments;
	}

	private static Set<String> shapesWithErrors(Compilation compilation) {
		Set<String> shapes = new LinkedHashSet<String>();
		for (Diagnostic<? extends JavaFileObject> d : compilation.diagnostics) {
			if (d.getKind() != Diagnostic.Kind.ERROR || d.getSource() == null) {
				continue;
			}
			String name = new File(d.getSource().getName()).getName();
			shapes.add(name.endsWith(".java") ? name.substring(0, name.length() - 5) : name);
		}
		return shapes;
	}

	/** What a shape IS, and what each side must therefore say about it. */
	private enum Attachment {

		/** javac attaches the block, so its pointers are inside the gate. */
		ATTACHED(true, false),

		/** javac discards the block, so the scanner has to report it. */
		UNATTACHED(false, true),

		/**
		 * javac discards the block and the scanner is documented not to REACH it — a missed orphan,
		 * which is the conservative half of {@link #annotationResidue}'s refusal to guess at an argument
		 * list left open on the line. Declared rather than left unwritten because the refusal is what
		 * chooses the safe direction: a hole here, instead of a red build on legal code. Turn the refusal
		 * into a guess and the shape declared this way is flagged, so this row reddens.
		 */
		UNATTACHED_AND_UNREACHED(false, false),

		/** There is no doc comment here at all, so neither side sees anything. */
		NOT_A_JAVADOC_BLOCK(false, false);

		private final boolean doclintReadsIt;

		private final boolean scannerFlagsIt;

		Attachment(boolean doclintReadsIt, boolean scannerFlagsIt) {
			this.doclintReadsIt = doclintReadsIt;
			this.scannerFlagsIt = scannerFlagsIt;
		}
	}

	/** One row of {@link #SHAPES}: the class body, and what that body is declared to be. */
	private static final class Shape {

		private final Attachment attachment;

		private final String source;

		private final boolean wholeFile;

		/**
		 * Declared: {@link #opensWithAnUnattachedJavadocBlock} says this file opens with a javadoc block
		 * that documents nothing. False for every class BODY row, which the wrapper opens with the class
		 * declaration — so the rows this field says anything interesting about are the whole-file ones
		 * that put a block before a {@code package} statement.
		 */
		private final boolean opensWithADanglingBlock;

		private Shape(Attachment attachment, String source, boolean wholeFile,
				boolean opensWithADanglingBlock) {
			this.attachment = attachment;
			this.source = source;
			this.wholeFile = wholeFile;
			this.opensWithADanglingBlock = opensWithADanglingBlock;
		}

		/**
		 * {@code wholeFile} exists for one row and is not decoration: every other row is a class BODY
		 * that the harness wraps, and that wrapper made the block-after-the-closing-brace arm of
		 * {@link #unattachedJavadocBlocks} inexpressible — so that arm was deletable with the whole
		 * suite green.
		 */
		private String sourceFor(String name) {
			return wholeFile ? source : "public class " + name + " {\n" + source + "}\n";
		}
	}

	/**
	 * The shapes, each carrying one pointer that resolves nowhere and each declaring what it IS —
	 * class bodies the harness wraps, except where the shape needs the whole file, and those spell the
	 * dead pointer fully qualified where they have no enclosing class for {@code #member} to resolve
	 * against. Add a row whenever a new arrangement turns up — that is cheaper than another round of
	 * arguing about the rule, and it is how every wrong version of the scanner was settled. No count of those
	 * is given, here or in the ADR: each one written during this change went stale in the next round.
	 *
	 * <p><strong>One map and not two.</strong> The source and the declaration were separate maps keyed
	 * alike, and that desynced fail-OPEN in exactly the direction the instruction above invites: a row
	 * added to the sources alone was written, compiled, folded into the baseline gate and then never
	 * asserted about, so the table silently stopped being ground truth for the case it was added for.
	 * Measured. A row cannot exist without its declaration now.
	 */
	private static final Map<String, Shape> SHAPES = shapes();

	private static void shape(Map<String, Shape> shapes, String name, Attachment attachment, String body) {
		shapes.put(name, new Shape(attachment, body, false, false));
	}

	private static void wholeFile(Map<String, Shape> shapes, String name, Attachment attachment, String file) {
		shapes.put(name, new Shape(attachment, file, true, false));
	}

	/**
	 * A whole-file row that ALSO declares
	 * {@link #noFileOpensWithAJavadocBlockBeforeItsPackageStatement}'s answer to be yes. Its own helper
	 * rather than a fourth argument on {@link #wholeFile}, so that declaring NO is what every other row
	 * already does rather than a column of {@code false}s a new row has to remember.
	 */
	private static void openingWithADanglingBlock(Map<String, Shape> shapes, String name,
			Attachment attachment, String file) {
		shapes.put(name, new Shape(attachment, file, true, true));
	}

	private static Map<String, Shape> shapes() {
		String dead = "{@link #noSuchMemberAnywhere()}";
		Map<String, Shape> shapes = new LinkedHashMap<String, Shape>();
		shape(shapes, "Plain", Attachment.ATTACHED,
				"\t/** " + dead + ". */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "BlockThenBlock", Attachment.UNATTACHED,
				"\t/** " + dead + ". */\n\t/** live */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "BeforeClosingBrace", Attachment.UNATTACHED,
				"\tprivate int a = 1;\n\tint r() { return a; }\n\t/** " + dead + ". */\n");
		shape(shapes, "AfterAnnotation", Attachment.UNATTACHED,
				"\t@Deprecated\n\t/** " + dead + ". */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "BeforeAnnotation", Attachment.ATTACHED,
				"\t/** " + dead + ". */\n\t@Deprecated\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "LineCommentThenDeclaration", Attachment.ATTACHED,
				"\t/** " + dead + ". */\n\t// note\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "LineCommentThenBlock", Attachment.UNATTACHED,
				"\t/** " + dead + ". */\n\t// note\n\t/** live */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "BlockCommentThenDeclaration", Attachment.ATTACHED,
				"\t/** " + dead + ". */\n\t/* note */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "OneLinerThenDeclaration", Attachment.ATTACHED,
				"\t/** " + dead + ". */ private int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "CommentedOutDocBlock", Attachment.NOT_A_JAVADOC_BLOCK,
				"\t/*\n\t/** " + dead + ".\n\t */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "EmptyBlockCommentThenBlock", Attachment.ATTACHED,
				"\t/**/\n\t/** " + dead + ". */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "CommentedOutDocBlockBeforeBrace", Attachment.NOT_A_JAVADOC_BLOCK,
				"\t/*\n\t/** " + dead + ".\n\t * Kept rather than deleted.\n\t */\n");
		shape(shapes, "EmptyBlockCommentThenNoteThenBlock", Attachment.ATTACHED,
				"\t/**/\n\tprivate int a = 1; /* note */\n\t/** " + dead + ". */\n\tprivate int b = 2;\n\tint r() { return a + b; }\n");
		// The row the line-oriented scanner failed: a comment OPENED AFTER CODE was invisible to it, so
		// it read the /**-looking line inside that comment as a javadoc block and reported a violation
		// on a file javac finds no doc comment in at all. A false positive, i.e. a clean build failing.
		shape(shapes, "BlockCommentOpenedAfterCode", Attachment.NOT_A_JAVADOC_BLOCK,
				"\tprivate int a = 1; /* opened after code\n\t/** " + dead + " — inside that comment */\n");
		// A line that is BOTH the annotation and the declaration. One content line is recorded per
		// source line, so this starts with @ while annotating itself, and the block below it is
		// attached to the next member — javac reads it. The annotation arm reported a violation here,
		// telling the author to move documentation the compiler had read.
		shape(shapes, "AnnotatedDeclarationOnOneLineThenBlock", Attachment.ATTACHED,
				"\t@SuppressWarnings(\"unused\") private int a = 1;\n\t/** " + dead
						+ ". */\n\tprivate int b = 2;\n\tint r() { return a + b; }\n");
		// The one row the class-body wrapper cannot express: a block after the top-level class, where
		// nothing follows it at all.
		wholeFile(shapes, "TrailingBlockAfterTheClass", Attachment.UNATTACHED,
				"public class TrailingBlockAfterTheClass {\n\tprivate int a = 1;\n"
						+ "\tint r() { return a; }\n}\n\n/** " + dead + ". */\n");
		shape(shapes, "BlankLinesThenDeclaration", Attachment.ATTACHED,
				"\t/** " + dead + ". */\n\n\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		// An INITIALISER is not a declaration, so javac attaches nothing to it and doclint reads
		// nothing inside the block above it. Three arrangements, because the scanner has to tell each
		// of them from a declaration beginning with the same word: `static {`, the bare instance
		// block, and `static` with its brace on the next line.
		shape(shapes, "BeforeStaticInitialiser", Attachment.UNATTACHED,
				"\t/** " + dead + ". */\n\tstatic {\n\t}\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "BeforeInstanceInitialiser", Attachment.UNATTACHED,
				"\t/** " + dead + ". */\n\t{\n\t}\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "BeforeBareStaticThenBrace", Attachment.UNATTACHED,
				"\t/** " + dead + ". */\n\tstatic\n\t{\n\t}\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		// The counterpart the same arm must NOT report: `static` beginning a real declaration, split
		// over two lines. javac reads this block; a scanner that recognised the bare word alone would
		// tell the author to move documentation the compiler had already read.
		shape(shapes, "BareStaticThenDeclaration", Attachment.ATTACHED,
				"\t/** " + dead + ". */\n\tstatic\n\tfinal int a = 1;\n\tint r() { return a; }\n");
		// An `import` is a declaration javac attaches no doc comment to, and — unlike the `package`
		// statement — it warns about nothing either, so this shape is silent on both channels. Needs
		// the whole file: the class-body wrapper cannot put anything above an import.
		wholeFile(shapes, "BeforeImport", Attachment.UNATTACHED,
				"package shapes;\n\n/** " + dead + ". */\nimport java.util.List;\n\n"
						+ "public class BeforeImport {\n\tprivate List<String> a = null;\n"
						+ "\tint r() { return a == null ? 0 : 1; }\n}\n");
		// The two rows that pin isAnnotationAlone's remaining two exclusions. Both were unpinned when
		// this table was first written — each clause could be deleted with the whole suite green — and
		// both are cases where javac DOES read the block, so dropping either turns a clean build red.
		// The comma: an annotated enum constant, the same line shape as this module's @RequestParam
		// parameter lines, which annotates itself rather than what follows.
		shape(shapes, "AnnotatedEnumConstantThenBlock", Attachment.ATTACHED,
				"\tenum E {\n\t\t@Deprecated A,\n\t\t/** " + dead
						+ ". */\n\t\tB;\n\t}\n\tE r() { return E.B; }\n");
		// The brace: an annotated type declaration whose body brace is on the same line. The block
		// below it documents the first member of that type, and javac reads it.
		shape(shapes, "AnnotatedTypeOpenThenBlock", Attachment.ATTACHED,
				"\t@Deprecated static class Inner {\n\t\t/** " + dead
						+ ". */\n\t\tprivate int a = 1;\n\t\tint r() { return a; }\n\t}\n");
		// The other side of the same rule: an annotation whose own ARGUMENT list carries the brace, and
		// one whose string argument carries the terminator. Both annotate the declaration below them,
		// so javac discards a block stranded in between and the arm has to fire. Under the character
		// exclusions it did not: the controller's @ExceptionHandler({ ... }) takes the first shape, and
		// so does @ValueSource(booleans = { ... }); a quoted terminator takes the second.
		shape(shapes, "AnnotationArgumentCarriesABrace", Attachment.UNATTACHED,
				"\t@SuppressWarnings({ \"unused\" })\n\t/** " + dead
						+ ". */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "AnnotationArgumentCarriesATerminator", Attachment.UNATTACHED,
				"\t@SuppressWarnings(\"a;b\")\n\t/** " + dead
						+ ". */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		// And the counterpart that refuses the cheap version of the same fix: an annotated declaration
		// on one line with a trailing note, which moves the terminator off the END of the line. javac
		// reads the block below it, so a last-character test would tell the author to move
		// documentation the compiler had already read.
		shape(shapes, "AnnotatedDeclarationWithATrailingNoteThenBlock", Attachment.ATTACHED,
				"\t@SuppressWarnings(\"unused\") private int a = 1; // note\n\t/** " + dead
						+ ". */\n\tprivate int b = 2;\n\tint r() { return a + b; }\n");
		// One row per property endOfArgumentList claims, because every one of them was deletable with
		// the whole suite green: no shipped annotation argument list carries a nested paren or a `)`
		// inside a literal, so the balanced walk was indistinguishable from indexOf(')'). The first
		// three are stranded blocks the arm has to FIRE on and the walk is the only reason it can.
		// Nesting: an annotation argument that groups a constant expression.
		shape(shapes, "AnnotationArgumentNestsAParen", Attachment.UNATTACHED,
				"\t@interface Sized {\n\t\tint value();\n\t}\n\t@Sized((1 + 2))\n\t/** " + dead
						+ ". */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		// Literal skipping, whose two halves are separately deletable: a `)` inside a STRING literal,
		// and one inside a CHAR literal. Each is the whole reason the walk skips literals whole, and
		// under indexOf(')') both close the list early and leave a residue that reads as a declaration.
		shape(shapes, "AnnotationArgumentQuotesACloseParen", Attachment.UNATTACHED,
				"\t@SuppressWarnings(\"unused)\")\n\t/** " + dead
						+ ". */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		shape(shapes, "AnnotationArgumentIsACharCloseParen", Attachment.UNATTACHED,
				"\t@interface Sep {\n\t\tchar value();\n\t}\n\t@Sep(')')\n\t/** " + dead
						+ ". */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		// And the refusal, the one row of this group the arm must NOT fire on: an argument list
		// left OPEN at the end of the line, where annotationResidue hands back the whole line rather
		// than guessing. Declared UNATTACHED_AND_UNREACHED — javac reads nothing inside the list and
		// the scanner is documented not to reach it. Turn the refusal into a guess (annotationResidue
		// returning "" for it, or endOfArgumentList answering the end of the line instead of -1) and
		// the arm reports a block on legal code, so this row reddens.
		shape(shapes, "AnnotationArgumentListLeftOpenThenBlock", Attachment.UNATTACHED_AND_UNREACHED,
				"\t@SuppressWarnings({\n\t/** " + dead
						+ ". */\n\t\t\t\"unused\" })\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		// The counterpart isImportDeclaration's whitespace clause exists for: a declaration whose
		// identifier begins with the same six characters. An enum constant is the one place such a
		// name can START a content line, and javac attaches the block above it — so a prefix test
		// would report documentation the compiler had read.
		shape(shapes, "EnumConstantNamedLikeAnImport", Attachment.ATTACHED,
				"\tenum E {\n\t\t/** " + dead
						+ ". */\n\t\timportantValue,\n\t\tOTHER;\n\t}\n\tE r() { return E.OTHER; }\n");
		// The clause that keeps the header rule off an EMPTY block comment, which is not a javadoc
		// open at all and which javac finds no doc comment in. Unpinned before this row — no file in
		// the repository opens with one — so dropping it reddened a clean build and told the author to
		// "Open it with /* instead" about a file already opened with one.
		wholeFile(shapes, "EmptyBlockCommentBeforePackage", Attachment.ATTACHED,
				"/**/\npackage shapes;\n\npublic class EmptyBlockCommentBeforePackage {\n\t/** " + dead
						+ ". */\n\tprivate int a = 1;\n\tint r() { return a; }\n}\n");
		// The two rows that pin the header rule rather than the orphan scanner, which is why they carry
		// a FULLY-QUALIFIED dead pointer: neither block has an enclosing class for `#member` to resolve
		// against. A block before the `package` statement is read by doclint in both, and the orphan
		// scanner reports neither — what separates them is that only package-info.java is a legal place
		// to write one, so the header rule must fire on one and not the other.
		String qualifiedDead = "{@link java.lang.String#noSuchMemberAnywhere()}";
		wholeFile(shapes, "package-info", Attachment.ATTACHED,
				"/** " + qualifiedDead + ". */\npackage shapes;\n");
		openingWithADanglingBlock(shapes, "HeaderBlockBeforePackage", Attachment.ATTACHED,
				"/** " + qualifiedDead + ". */\npackage shapes;\n\n"
						+ "public class HeaderBlockBeforePackage {\n\tprivate int a = 1;\n"
						+ "\tint r() { return a; }\n}\n");
		return shapes;
	}

	// --- The compiler ---

	private static final class Compilation {

		private final boolean succeeded;

		private final List<Diagnostic<? extends JavaFileObject>> diagnostics;

		private Compilation(boolean succeeded, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
			this.succeeded = succeeded;
			this.diagnostics = diagnostics;
		}

		/** Every ERROR diagnostic, described. The one place this class decides what an error IS. */
		private List<String> errors() {
			List<String> errors = new ArrayList<String>();
			for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
				if (d.getKind() == Diagnostic.Kind.ERROR) {
					errors.add(describe(d));
				}
			}
			return errors;
		}

		private boolean failedWithAnError() {
			return !errors().isEmpty();
		}

		private String report() {
			if (diagnostics.isEmpty()) {
				return "  (the compiler reported nothing at all)";
			}
			List<String> lines = new ArrayList<String>();
			for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
				lines.add(describe(d));
			}
			return join(lines);
		}
	}

	private static String describe(Diagnostic<? extends JavaFileObject> diagnostic) {
		JavaFileObject source = diagnostic.getSource();
		String where = source == null ? "(no source)" : source.getName();
		return diagnostic.getKind() + " " + where + ":" + diagnostic.getLineNumber() + " — "
				+ diagnostic.getMessage(null).replace('\n', ' ');
	}

	/**
	 * Compiles one synthetic source with exactly the arguments given, plus this suite's classpath —
	 * no {@code -source}/{@code -target}, so the running JDK's defaults apply and the only variable
	 * is the argument list the POMs declare. The synthetic sources reference nothing but
	 * {@code java.lang}; the classpath is there so that an argument needing one cannot fail the live
	 * half for an unrelated reason.
	 */
	private static Compilation compileOne(List<String> arguments, String className, String source)
			throws IOException {
		Path dir = Files.createTempDirectory("javadoc-reference-guard-source");
		try {
			Path file = dir.resolve(className + ".java");
			Files.write(file, source.getBytes(StandardCharsets.UTF_8));
			return compile(arguments, Arrays.asList(file), System.getProperty("java.class.path"));
		}
		finally {
			deleteRecursively(dir);
		}
	}

	/**
	 * Compiles the given files with the given arguments and classpath, collecting every diagnostic.
	 *
	 * <p>A missing system compiler fails loudly rather than skipping. A guard that cannot run must
	 * not pass — the rule {@link ModuleSourceRoot}'s javadoc states for its walking callers, and the
	 * same one here: a skip would report success for a suite running on a JRE.
	 */
	private static Compilation compile(List<String> arguments, List<Path> files, String classpath)
			throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			fail("No system Java compiler is available (running on a JRE?), so this guard cannot check "
					+ "whether " + REFERENCE_CHECK + " is in force. A guard that cannot run must not pass.");
		}
		Path out = Files.createTempDirectory("javadoc-reference-guard-classes");
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
				boolean ok;
				try {
					ok = compiler.getTask(null, manager, collector, arguments, null, units).call();
				}
				catch (IllegalArgumentException rejected) {
					// javax.tools THROWS on an unrecognised option rather than reporting a diagnostic, so
					// without this a POM carrying one reddens with a bare stack trace and no mention of the
					// arguments that caused it. Measured with an invalid flag in an inactive profile, which
					// the POM walk reads and Maven never applies.
					fail("The compiler rejected these arguments outright: " + arguments + "\n\n  "
							+ rejected.getMessage()
							+ "\n\nThat list came from a POM this guard reads. Note that an option can be valid\n"
							+ "on the JDK running this suite and invalid on an older one in the CI matrix.");
					return null;
				}
				return new Compilation(ok, collector.getDiagnostics());
			}
		}
		finally {
			deleteRecursively(out);
		}
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
	 * Every {@code .java} file under the named roots, resolved from the repository root — which
	 * {@link ModuleSourceRoot#repoRoot()} THROWS rather than guesses, so a walk cannot quietly read
	 * the working directory instead.
	 *
	 * <p>Each root is checked against its own anchor in {@link #SOURCE_ROOTS} and a root that fails
	 * that fails the caller — including a root with no anchor at all, which is what stops
	 * {@link #SOURCE_ROOTS} drifting behind {@link #reactorSourceRoots}. A root that simply does not
	 * exist fails it too: silently skipping one is how every other root passes for the one nobody read.
	 */
	private static List<Path> javaSourcesUnder(List<String> roots) throws IOException {
		List<Path> sources = new ArrayList<Path>();
		for (String root : roots) {
			Path directory = REPO_ROOT.resolve(root);
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
			String anchor = SOURCE_ROOTS.get(root);
			if (anchor == null) {
				fail("Root " + root + " has no anchor file in SOURCE_ROOTS, so a walk of it could scan "
						+ "nothing and report no violations. Give it one, or take it off the caller's list.");
			}
			if (!names.contains(anchor)) {
				fail("The walk of " + root + " under " + REPO_ROOT + " did not find " + anchor
						+ " (it found " + names.size() + " file(s)), so this guard is reading the wrong tree "
						+ "or no tree at all and would report no violations whatever the javadoc says. See "
						+ "ModuleSourceRoot's javadoc on walking callers.");
			}
		}
		return sources;
	}

	/**
	 * Removes a temp directory, deepest entry first, best-effort. Best-effort deliberately: this runs
	 * in a {@code finally} after the compile has already produced its verdict, so a throw here would
	 * REPLACE that verdict with a complaint about temp-directory cleanup — a red build naming the
	 * wrong thing. What is left behind is bounded and lives where the OS reaps it.
	 */
	private static void deleteRecursively(Path dir) {
		try (Stream<Path> paths = Files.walk(dir)) {
			List<Path> all = new ArrayList<Path>();
			paths.forEach(all::add);
			all.sort(Comparator.comparingInt(Path::getNameCount).reversed());
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

	// --- The POMs ---

	/**
	 * Whether one argument list, given to the real compiler, makes a dead javadoc reference an
	 * ERROR. The compiler is the oracle rather than a string match on {@link #REFERENCE_CHECK}
	 * because {@code -Xdoclint} is an option GRAMMAR: {@code -Xdoclint:all,-missing,-html,-syntax}
	 * enables the reference group perfectly well and a prefix match calls it missing, so a
	 * maintainer WIDENING the check would be told they had removed it.
	 *
	 * <p>The LIVE probe is checked here rather than at one call site, because both callers need it: a
	 * dead reference failing alone is also satisfied by an argument list that refuses to compile
	 * anything at all, and a child {@code <compilerArgs>} block can carry such a list as easily as the
	 * managed one. That, and a dirty baseline, fail LOUDLY with the compiler's own output rather than
	 * returning false — false would report an environmental failure as "a dead pointer is silent
	 * again", the right colour with the wrong cause.
	 *
	 * <p><strong>Its answer is about the JDK running this suite, not about the CI matrix.</strong> An
	 * argument valid on a newer JDK and rejected by an older one passes here and reddens every older
	 * leg — measured with {@code -Xlint:-dangling-doc-comments}, which JDK 24 accepts and JDK 11 calls
	 * an invalid flag.
	 */
	private static boolean refusesADeadReference(String where, List<String> arguments) throws IOException {
		Compilation baseline = compileOne(BASELINE_ARGUMENTS, "DeadReference", DEAD_REFERENCE_SOURCE);
		if (baseline.failedWithAnError()) {
			fail("This guard could not run: the dead-reference probe does not compile even without "
					+ REFERENCE_CHECK + ", so nothing can be attributed to the arguments under test.\n\n"
					+ join(baseline.errors()));
		}
		Compilation live = compileOne(arguments, "LiveReference", LIVE_REFERENCE_SOURCE);
		if (live.failedWithAnError()) {
			fail(where + " declares <compilerArgs> " + arguments + ", which refuse a source whose javadoc\n"
					+ "reference RESOLVES — so every build would fail for a reason unrelated to any pointer:\n\n"
					+ join(live.errors()));
		}
		return compileOne(arguments, "DeadReference", DEAD_REFERENCE_SOURCE).failedWithAnError();
	}

	/**
	 * The compiler arguments the ROOT pom manages — its {@code <build>/<pluginManagement>} entry for
	 * the compiler plugin, at plugin level. Navigated by path rather than by
	 * {@code getElementsByTagName}, because the position is the thing being asserted.
	 *
	 * <p>Never a union across POMs. That was this guard's own defect: the union of three POMs'
	 * plugin-level arguments is the effective configuration of no module, so the flag declared in
	 * {@code api/pom.xml} alone satisfied it while {@code omod} compiled ungated.
	 */
	private static List<String> rootManagedCompilerArgs() throws Exception {
		Element build = directChild(pomRoot("pom.xml"), "build");
		Element management = directChild(build, "pluginManagement");
		for (Element plugin : directChildren(directChild(management, "plugins"), "plugin")) {
			Element artifactId = directChild(plugin, "artifactId");
			if (artifactId != null && COMPILER_PLUGIN.equals(artifactId.getTextContent().trim())) {
				return compilerArgs(directChild(plugin, "configuration"));
			}
		}
		return new ArrayList<String>();
	}

	/**
	 * Every {@code maven-compiler-plugin} element in one POM, wherever it sits — {@code <plugins>},
	 * {@code <pluginManagement>} or inside a {@code <profile>}. All three are read because any of
	 * them can carry the arguments and any of them can drop them; this repository uses the second.
	 */
	private static List<Element> compilerPlugins(String pom) throws Exception {
		List<Element> plugins = new ArrayList<Element>();
		NodeList all = pomRoot(pom).getElementsByTagName("plugin");
		for (int i = 0; i < all.getLength(); i++) {
			Element plugin = (Element) all.item(i);
			Element artifactId = directChild(plugin, "artifactId");
			if (artifactId != null && COMPILER_PLUGIN.equals(artifactId.getTextContent().trim())) {
				plugins.add(plugin);
			}
		}
		return plugins;
	}

	/**
	 * One POM's {@code <project>} element, namespace-unaware so element names match without a prefix.
	 * A missing file fails loudly: the POMs it reads come from {@link #poms}, and one it cannot open is
	 * one it checks nothing in.
	 */
	private static Element pomRoot(String pom) throws Exception {
		Path path = REPO_ROOT.resolve(pom);
		if (!Files.isRegularFile(path)) {
			fail("pom " + pom + " does not exist under " + REPO_ROOT + " — this guard would check nothing");
		}
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(false);
		Element root = factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
		if ("pom.xml".equals(pom)) {
			Element artifactId = directChild(root, "artifactId");
			String named = artifactId == null ? null : artifactId.getTextContent().trim();
			if (!EXPECTED_ROOT_ARTIFACT.equals(named)) {
				fail("The pom at " + path + " declares artifactId " + named + " and not "
						+ EXPECTED_ROOT_ARTIFACT + ", so this guard is reading the wrong project: its POM\n"
						+ "checks would report a missing declaration in a file that is not this module's. That\n"
						+ "root came from ModuleSourceRoot.repoRoot(), which walks up for CLAUDE.md and docs/.");
			}
		}
		return root;
	}

	/**
	 * One XML document parsed from a string, for
	 * {@link #theCorpusCoversEveryModuleTheBuildCompiles} to ask
	 * {@link #modulesIn} about a shape this repository does not contain. Namespace-unaware, as
	 * {@link #pomRoot} is, so the two read the same documents the same way.
	 */
	private static Element parseXml(String xml) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(false);
		return factory.newDocumentBuilder()
				.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
				.getDocumentElement();
	}

	/**
	 * Every {@code <compilerArgs>} block under one plugin element, keyed by where it sits — the
	 * plugin's own {@code <configuration>}, or a named {@code <execution>}. The key is what the
	 * failure message needs; the map is what makes the check over them universal.
	 */
	private static Map<String, List<String>> compilerArgBlocks(Element plugin) {
		Map<String, List<String>> blocks = new LinkedHashMap<String, List<String>>();
		Element configuration = directChild(plugin, "configuration");
		if (directChild(configuration, "compilerArgs") != null) {
			blocks.put("plugin-level <configuration>", compilerArgs(configuration));
		}
		for (Element execution : executions(plugin)) {
			Element executionConfig = directChild(execution, "configuration");
			if (directChild(executionConfig, "compilerArgs") != null) {
				blocks.put(executionLabel(execution), compilerArgs(executionConfig));
			}
		}
		return blocks;
	}

	private static List<String> compilerArgs(Element configuration) {
		List<String> args = new ArrayList<String>();
		Element compilerArgs = directChild(configuration, "compilerArgs");
		for (Element arg : directChildren(compilerArgs, "arg")) {
			args.add(arg.getTextContent().trim());
		}
		return args;
	}

	private static List<String> disabledFailOnErrorAt(Element plugin) {
		List<String> where = new ArrayList<String>();
		if (isFalse(directChild(directChild(plugin, "configuration"), "failOnError"))) {
			where.add("plugin-level <configuration>");
		}
		for (Element execution : executions(plugin)) {
			if (isFalse(directChild(directChild(execution, "configuration"), "failOnError"))) {
				where.add(executionLabel(execution));
			}
		}
		return where;
	}

	private static List<String> nonJavacCompilerIdAt(Element plugin) {
		List<String> where = new ArrayList<String>();
		Element pluginLevel = directChild(directChild(plugin, "configuration"), "compilerId");
		if (isNonJavac(pluginLevel)) {
			where.add("plugin-level <configuration> sets <compilerId>" + pluginLevel.getTextContent().trim()
					+ "</compilerId>");
		}
		for (Element execution : executions(plugin)) {
			Element id = directChild(directChild(execution, "configuration"), "compilerId");
			if (isNonJavac(id)) {
				where.add(executionLabel(execution) + " sets <compilerId>" + id.getTextContent().trim()
						+ "</compilerId>");
			}
		}
		return where;
	}

	private static List<Element> executions(Element plugin) {
		return directChildren(directChild(plugin, "executions"), "execution");
	}

	private static String executionLabel(Element execution) {
		Element id = directChild(execution, "id");
		return "execution " + (id == null ? "(unnamed)" : id.getTextContent().trim());
	}

	private static boolean isFalse(Element element) {
		return element != null && "false".equalsIgnoreCase(element.getTextContent().trim());
	}

	/**
	 * Whether a {@code <compilerId>} names a backend other than javac. An ABSENT or BLANK element is
	 * javac — Maven's own default — so neither is a violation; reading blank as "not javac" was a
	 * false positive on a POM that compiles perfectly.
	 */
	private static boolean isNonJavac(Element element) {
		if (element == null) {
			return false;
		}
		String id = element.getTextContent().trim();
		return !id.isEmpty() && !"javac".equalsIgnoreCase(id);
	}

	private static Element directChild(Element parent, String name) {
		List<Element> children = directChildren(parent, name);
		return children.isEmpty() ? null : children.get(0);
	}

	private static List<Element> directChildren(Element parent, String name) {
		List<Element> children = new ArrayList<Element>();
		if (parent == null) {
			return children;
		}
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
				children.add((Element) node);
			}
		}
		return children;
	}

	/**
	 * The lines, one per bullet, capped. The cap is not cosmetic: a compile whose classpath is
	 * unusable reports an error per unresolvable type, which over this module runs to thousands —
	 * measured by emptying the classpath — and a listing that long buries the sentence above it
	 * saying what went wrong.
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

	private static void assertNoViolations(List<String> violations) {
		if (!violations.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			sb.append(violations.size()).append(" javadoc-reference-check violation(s) found:\n\n");
			sb.append(join(violations));
			sb.append("\nSee docs/adr.md, Decision 72: the javadoc IS this module's design record, so a ")
					.append("pointer that no longer resolves has to be a build failure rather than plain text.");
			fail(sb.toString());
		}
	}
}
