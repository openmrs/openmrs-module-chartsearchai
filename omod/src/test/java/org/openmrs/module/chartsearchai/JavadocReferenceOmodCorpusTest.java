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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * Every javadoc reference in {@code omod/src/main/java} and {@code omod/src/test/java} resolves,
 * asked of the real compiler over the real files with arguments THIS class chooses, as literals: what
 * this check looks for is decided in this file and not in a build file.
 *
 * <p><strong>An earlier version of that sentence said no POM edit could silence it, and round 9 of
 * #262's review falsified it.</strong> The arguments are literals here, so no POM decides them; but
 * this check lives in the very test root it guards, so four lines in {@code omod/pom.xml} — an
 * {@code <executions>} entry binding {@code default-testCompile} to {@code <phase>none</phase>} —
 * take that root out of javac AND take this class out of the build, together. Measured, JDK 21,
 * plugin 3.13.0: exit 0, BUILD SUCCESS, zero {@code reference not found}, omod's surefire logging
 * "No tests to run", the api module green. Round 10 then found two further ways to stop a module's
 * checks that neither arm read — surefire's own {@code test} FILTER property and
 * {@code maven.test.failure.ignore} — and the first of them leaves BOTH modules printing test counts
 * while one module's checks are gone, which is nothing like "No tests to run". What answers all of
 * that, as far as it is answered, is {@link #noPomEditTakesAModuleOutOfTheTestBuild} below, whose
 * javadoc states what is covered, what is not, and on which channel each remaining shape shows.
 * Round 12 then found five surefire SELECTION properties refused in their element form and read
 * from no property at all — this arm's own ten-against-five asymmetry, which
 * {@link #everySurefireParameterIsRefusedInBothFormsMavenReadsIt} now asserts against. Round 14
 * found a fourth instance of that same shape and a NEW kind of error with it: {@code excludedGroups}
 * was in neither arm's list on a stated GROUND, and the ground is false for the provider this
 * reactor uses. It is the one member of the family whose cross-module cover did not fire — this arm
 * ran, was green, and reported nothing. {@link #SUREFIRE_USER_PROPERTY_PREFIX} is what changed in
 * answer to it, four rounds of one-more-name being the pattern that needed breaking rather than
 * extending.
 * <strong>Do not write another absolute here.</strong> Every round so far has found a position the
 * round before had not read, and every absolute published about this change has been falsified by a
 * later round — the api-side class javadoc's QUIET enumeration, which round 12 falsified, and then
 * a REASON given for an omission, which round 14 falsified in four places at once. Where a sentence
 * of that shape suggests itself, name the edit that was actually checked and stop there.
 *
 * <p><strong>Why it exists, which is the part worth reading.</strong> {@code JavadocReferenceGuardTest}
 * in the api module holds the same line two ways: it compiles the api corpus with its own arguments,
 * and it reads these POMs to check that the argument the build declares is really in force. The first
 * of those cannot reach {@code omod} — an api-side test runs on the api classpath — so until this
 * class existed omod's two source roots were held by the POM readers ALONE. Rounds 5 through 8 of
 * #262's review each found one more position from which the effective javac argument list is set that
 * those readers did not read: the {@code maven.compiler.failOnError} user property, the
 * {@code -Xdoclint/package} qualifier, four sibling argument parameters beside {@code compilerArgs},
 * and then three more at once — a non-{@code <arg>} child INSIDE {@code <compilerArgs>}, a child pom
 * pinning a compiler-plugin version predating that parameter, and Maven's {@code combine.self}
 * merge-control attribute. Every one of them landed on omod, for that structural reason and not by
 * chance. Each was fixed in the reader, and each fix left the next one reachable.
 *
 * <p>So this class is not a fifth reader. It bounds the CONSEQUENCE of one of those readers missing a
 * position: with it in place, a POM that defeats the FLAG makes the POM checks disagree with the
 * build — which is a defect worth reporting — rather than leaving omod's pointers unresolved with
 * everything green. <strong>It is deliberately not a claim that the POM readers are complete, and
 * nothing here should be read as one.</strong> Nor does it bound a POM edit that stops this class
 * RUNNING, which is a different defect and a different arm: see
 * {@link #noPomEditTakesAModuleOutOfTheTestBuild}.
 *
 * <p><strong>It duplicates a little of that class rather than sharing it, and that is a choice.</strong>
 * The two modules share no test classpath — {@code omod} depends on the api JAR and not its test-jar —
 * so sharing would mean publishing this scaffolding as a build dependency. The duplication buys
 * something as well as costing something: one edit cannot defeat both, which is the whole shape of the
 * defence. What it costs is drift, and the pieces that could drift are the ones with a rule of their
 * own — the classify-by-DIFFERENCE rule below, the per-root anchor, and the POM arm's own literals.
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

	/** The plugin that compiles a module's test sources. See {@link #PLUGINS_THAT_RUN_THESE_CHECKS}. */
	private static final String COMPILER_PLUGIN = "maven-compiler-plugin";

	/** The plugin that executes them. See {@link #PLUGINS_THAT_RUN_THESE_CHECKS}. */
	private static final String SUREFIRE_PLUGIN = "maven-surefire-plugin";

	/**
	 * The two plugins that decide whether ANY check in this repository runs — the one that compiles a
	 * module's test sources and the one that executes them. Named here because
	 * {@link #noPomEditTakesAModuleOutOfTheTestBuild} reads them out of every POM, which is the arm the
	 * api-side guard cannot hold on its own: an api-side test does not run when api's own tests are
	 * taken out of the build.
	 */
	private static final List<String> PLUGINS_THAT_RUN_THESE_CHECKS =
			Arrays.asList(COMPILER_PLUGIN, SUREFIRE_PLUGIN);

	/**
	 * The direct children the root pom's managed entry for either of those plugins may declare. Anything
	 * else is refused, which is how {@code <executions>} — round 9's finding, and the position no
	 * existing reader could see — is refused without a reader for {@code <phase>}. This list is the
	 * shape of this repository's own two managed entries and nothing more.
	 */
	private static final List<String> MANAGED_PLUGIN_CHILDREN_READ_HERE =
			Arrays.asList("groupId", "artifactId", "version", "configuration");

	/**
	 * Every surefire parameter that takes a module's checks out of the build, out of the run or out of
	 * the verdict, mapped from the {@code <configuration>} ELEMENT name to the user PROPERTY
	 * maven-surefire-plugin binds it to. One map and not two lists, because that is what
	 * {@link #TEST_DEFEATING_PROPERTIES} is DERIVED from: a parameter added here brings its property
	 * reader with it, in the same line, rather than in a second list somebody has to remember.
	 *
	 * <p>The element side matters here and not only in the api-side guard because of WHERE the two
	 * checks live: a surefire {@code <excludes>} naming the api-side guard, or a {@code <test>} naming
	 * something else, stops that guard running — and then it is not there to report the edit. This arm
	 * is. It is refused INSIDE a surefire {@code <configuration>}, which
	 * {@link #MANAGED_PLUGIN_CHILDREN_READ_HERE} permits, so without this nothing reads what is in it.
	 *
	 * <p><strong>Why the map exists.</strong> Rounds 5, 10 and 12 of this change's review were one
	 * defect three times — an element refused while its property went unread — and this arm carried
	 * the third: ten elements refused against five properties read, so
	 * {@code <surefire.excludes>**}{@code /JavadocReference*Test.java}{@code </surefire.excludes>} in
	 * the ROOT pom's {@code <properties>} took BOTH guards out of the build. Measured on this branch,
	 * JDK 21: {@code mvn -o clean install} exit 0, BUILD SUCCESS,
	 * {@code grep -c JavadocReference} over the whole log ZERO, and the only trace the reactor's test
	 * total falling by the guards' own tests. Round 10's own measurement, the shape that motivated the
	 * property side at all: {@code <test>DateFormatUtilTest</test>} in {@code api/pom.xml} gave exit 0
	 * with api running 5 tests, the api-side guard not among them, and this module's whole suite green.
	 *
	 * <p>The bindings are read off the shipped descriptor rather than off documentation:
	 * {@code META-INF/maven/plugin.xml} in the resolved {@code maven-surefire-plugin-3.5.5.jar},
	 * {@code test} mojo. {@code skip} carries {@code maven.test.skip} and {@code skipExec}
	 * {@code maven.test.skip.exec}, while {@code skipTests}, {@code test}, {@code groups} and
	 * {@code excludedGroups} are their own property names and the remaining selections take a
	 * {@code surefire.} prefix — which is the sort of detail a list written from memory gets wrong.
	 *
	 * <p><strong>{@code excludedGroups} was absent on a stated ground and round 14 measured that
	 * ground false.</strong> The ground was that a group EXCLUSION cannot reach a test in no group,
	 * and neither guard declares a JUnit tag. It holds for a plain tag NAME. It does not hold for a
	 * tag EXPRESSION, and surefire auto-selects its JUnit Platform provider for these suites, where
	 * {@code excludedGroups} is an expression and {@code none()} is defined to match exactly the
	 * tests carrying no tag. Measured, JDK 21, in {@code api/pom.xml}'s {@code <properties>} — a
	 * file that has none, so the reproduction adds one:
	 * {@code <excludedGroups>none()</excludedGroups>} gave {@code mvn -o clean install} exit 0,
	 * BUILD SUCCESS, api {@code Tests run: 0}, THIS module's suite green, and {@code excludedGroups}
	 * named ZERO times in the whole log. Of the members measured so far that is the one whose
	 * cross-module cover did not fire: this arm ran, was green, and said nothing.
	 * {@code <excludedGroups>nosuchtag</excludedGroups>} in the same position left api's whole suite
	 * running, which is why the false ground survived four rounds.
	 * {@code excludeJUnit5Engines} and {@code includeJUnit5Engines} are here from the same round —
	 * {@code <surefire.excludeJUnit5Engines>junit-jupiter} in {@code api/pom.xml} gave exit 0 with
	 * api {@code Tests run: 0} as well — and their PROPERTY side needs no entry, since
	 * {@link #SUREFIRE_USER_PROPERTY_PREFIX} answers for a prefixed name without looking it up. They
	 * are named here for the ELEMENT side, which has no prefix to read.
	 * {@link #everySurefireParameterIsRefusedInBothFormsMavenReadsIt} drives both readers over the
	 * pairs written out as literals.
	 */
	private static final Map<String, String> SUREFIRE_PARAMETERS_DEFEATING_CHECKS =
			surefireParametersToUserProperties();

	private static Map<String, String> surefireParametersToUserProperties() {
		Map<String, String> pairs = new LinkedHashMap<String, String>();
		pairs.put("skip", "maven.test.skip");
		pairs.put("skipTests", "skipTests");
		pairs.put("skipExec", "maven.test.skip.exec");
		pairs.put("testFailureIgnore", "maven.test.failure.ignore");
		pairs.put("test", "test");
		pairs.put("includes", "surefire.includes");
		pairs.put("includesFile", "surefire.includesFile");
		pairs.put("excludes", "surefire.excludes");
		pairs.put("excludesFile", "surefire.excludesFile");
		pairs.put("groups", "groups");
		pairs.put("excludedGroups", "excludedGroups");
		pairs.put("excludeJUnit5Engines", "surefire.excludeJUnit5Engines");
		pairs.put("includeJUnit5Engines", "surefire.includeJUnit5Engines");
		return Collections.unmodifiableMap(pairs);
	}

	/**
	 * The prefix that makes a {@code <properties>} entry maven-surefire-plugin's own. This arm
	 * refuses a value-carrying entry under it whatever the value and whichever parameter it belongs
	 * to, and {@link #TEST_DEFEATING_PROPERTIES} is left to answer for the un-prefixed names.
	 *
	 * <p><strong>Why the shape changed.</strong> Rounds 5, 10, 12 and 14 of this change's review each
	 * supplied one more surefire name that takes a module's checks out of the build, and round 14
	 * verified the following one in the same breath: {@code surefire.excludeJUnit5Engines} with the
	 * value {@code junit-jupiter}, in {@code api/pom.xml}, exit 0 with api {@code Tests run: 0} and
	 * this module's suite green. Two more names would have shipped a list whose next hole was already
	 * known. Read off {@code META-INF/maven/plugin.xml} in the resolved
	 * {@code maven-surefire-plugin-3.5.5} jar, {@code test} mojo: 81 children of the
	 * {@code <configuration>} block, 70 of them binding a user property through a bare
	 * {@code ${...}} expression, 31 of those 70 beginning {@code surefire.}. One prefix reaches those
	 * 31 without naming them and reaches a knob added under it later; the figures are a measurement
	 * of that descriptor at that version.
	 *
	 * <p><strong>The un-prefixed remainder is BOUNDED and not complete, and it is what
	 * {@link #SUREFIRE_PARAMETERS_DEFEATING_CHECKS} spells out</strong> — the legacy spellings
	 * {@code maven.test.skip}, {@code maven.test.skip.exec}, {@code skipTests},
	 * {@code maven.test.failure.ignore}, {@code test}, {@code groups} and {@code excludedGroups}.
	 * What was read of the descriptor's other un-prefixed bindings is that they are values Maven
	 * injects which a POM cannot usefully set, and knobs deciding HOW tests run rather than WHETHER;
	 * that is a characterisation of the read and not a proof about each of them, which is what
	 * BOUNDED means here. {@code failIfNoTests} was
	 * proposed for the list and the descriptor declines it: its {@code default-value} is
	 * {@code false}, so {@code false} is what this build already does and {@code true} makes an empty
	 * run FAIL.
	 *
	 * <p><strong>The value rule is this arm's own and it is not {@link #defeatsAModulesChecks}.</strong>
	 * That method exempts the word {@code false} because {@code false} is the default of the flags it
	 * reads; across 31 prefixed properties the harmful value is not uniformly {@code true}, so this
	 * leg refuses a non-blank value and permits a blank one. <strong>The cost is friction on tuning
	 * done through a property</strong> — {@code <surefire.runOrder>alphabetical</surefire.runOrder>}
	 * reddens and takes nothing away — and it falls on a shape this reactor does not use, which sets
	 * no {@code surefire.} property anywhere. It does not close the position:
	 * {@link #noPomEditTakesAModuleOutOfTheTestBuild} discloses that a guard cannot report an edit
	 * that stops it running, and the prefix narrows how easily that is reached rather than removing
	 * it.
	 */
	private static final String SUREFIRE_USER_PROPERTY_PREFIX = "surefire.";

	/**
	 * Two {@code surefire.} properties {@link #SUREFIRE_PARAMETERS_DEFEATING_CHECKS} does not name,
	 * put to the real reader by
	 * {@link #everySurefireParameterIsRefusedInBothFormsMavenReadsIt} — a PREFIX rule earns its keep
	 * on a name nobody wrote down. One is a real parameter of the pinned plugin
	 * ({@code runOrder → surefire.runOrder}), which is also where the cost of the rule shows: it
	 * takes no test away and is refused all the same. The other is invented, and stands for whatever
	 * arrives under the prefix next.
	 *
	 * <p><strong>A witness has to stay outside {@link #TEST_DEFEATING_PROPERTIES} or it stops
	 * witnessing</strong>, the prefix leg skipping a name that list already reports: moved into the
	 * map, one of these would leave the assertions below passing with the prefix rule deleted.
	 * {@link #everySurefireParameterIsRefusedInBothFormsMavenReadsIt} reports that first.
	 */
	private static final List<String> PREFIXED_PROPERTIES_NO_MAP_ENTRY_NAMES =
			Arrays.asList("surefire.runOrder", "surefire.aParameterNoReviewRoundHasSeenYet");

	/**
	 * The same parameters {@link #SUREFIRE_PARAMETERS_DEFEATING_CHECKS} maps, written out as
	 * {@code {parameter, user property, element value, property value}} — an INDEPENDENT statement of
	 * the pairing, for the reason
	 * {@link #everySurefireParameterIsRefusedInBothFormsMavenReadsIt} gives. Two value columns because
	 * the two forms take different syntax: {@code <excludes>} takes {@code <exclude>} children while
	 * {@code surefire.excludes} takes a comma-separated string. Every value is non-blank, which is what
	 * makes the pair refusable; the blank case is asserted in the same check and must NOT be refused.
	 * The exclusion names the API-side guard deliberately — that is the file this arm exists to keep
	 * in the build.
	 */
	private static final List<String[]> SUREFIRE_PARAMETER_FORMS_AS_LITERALS = Arrays.asList(
			new String[] { "skip", "maven.test.skip", "true", "true" },
			new String[] { "skipTests", "skipTests", "true", "true" },
			new String[] { "skipExec", "maven.test.skip.exec", "true", "true" },
			new String[] { "testFailureIgnore", "maven.test.failure.ignore", "true", "true" },
			new String[] { "test", "test", "DateFormatUtilTest", "DateFormatUtilTest" },
			new String[] { "includes", "surefire.includes",
					"<include>**/DateFormatUtilTest.java</include>", "**/DateFormatUtilTest.java" },
			new String[] { "includesFile", "surefire.includesFile", "inclusions.txt", "inclusions.txt" },
			new String[] { "excludes", "surefire.excludes",
					"<exclude>**/JavadocReferenceGuardTest.java</exclude>",
					"**/JavadocReference*Test.java" },
			new String[] { "excludesFile", "surefire.excludesFile", "exclusions.txt", "exclusions.txt" },
			new String[] { "groups", "groups", "eval", "eval" },
			new String[] { "excludedGroups", "excludedGroups", "none()", "none()" },
			new String[] { "excludeJUnit5Engines", "surefire.excludeJUnit5Engines",
					"<excludeJUnit5Engine>junit-jupiter</excludeJUnit5Engine>", "junit-jupiter" },
			new String[] { "includeJUnit5Engines", "surefire.includeJUnit5Engines",
					"<includeJUnit5Engine>junit-vintage</includeJUnit5Engine>", "junit-vintage" });

	/**
	 * The user properties that stop a module's checks asserting anything, whatever the mechanism.
	 * {@code maven.test.skip} is read by maven-compiler-plugin's {@code testCompile} and by surefire,
	 * {@code skipTests} and {@code maven.test.skip.exec} are surefire's own,
	 * {@code maven.test.failure.ignore} leaves every check running and makes its failure non-fatal, and
	 * the rest are surefire's SELECTION properties, any value of which narrows the run to what it
	 * names. Set in a CHILD pom each does what round 9's {@code <executions>} element did, from a line
	 * naming no plugin.
	 *
	 * <p><strong>Derived from {@link #SUREFIRE_PARAMETERS_DEFEATING_CHECKS} and never written by
	 * hand.</strong> Written by hand it was five names against that constant's ten, which is round 12
	 * — the measurement is on that constant. Read as ONE list here, with
	 * {@link #defeatsAModulesChecks} deciding what a value means, while the api-side guard splits the
	 * same parameters into families by mechanism: the two arms state the rule differently on purpose,
	 * see {@link #noPomEditTakesAModuleOutOfTheTestBuild}.
	 */
	private static final List<String> TEST_DEFEATING_PROPERTIES =
			Collections.unmodifiableList(new ArrayList<String>(
					SUREFIRE_PARAMETERS_DEFEATING_CHECKS.values()));

	/**
	 * Every reactor POM, spelled as literals for the same reason {@link #SOURCE_ROOTS} is: this suite
	 * runs in {@code omod} and does not read the root pom's {@code <modules>} to find out what the
	 * reactor is. What stops the list drifting behind the reactor is on the other side —
	 * {@code JavadocReferenceGuardTest.pomsNoCrossModuleReaderNames} requires every reactor POM to be
	 * named as a literal in THIS file, so a third module reddens there.
	 *
	 * <p>Required to be FOUND by {@link #pomsInTheTree}, which walks the filesystem instead. Two
	 * derivations rather than one, deliberately: a POM arm whose list of POMs comes from a POM is an
	 * arm one edit narrows, and a walk that resolves nowhere reads nothing and reports nothing.
	 */
	private static final List<String> REQUIRED_POMS =
			Arrays.asList("pom.xml", "api/pom.xml", "omod/pom.xml");

	/**
	 * The api-side guard, required to exist and to still compile its own module's sources with an
	 * argument it chooses itself. The mirror of {@code COMPILER_CHECKS_OUTSIDE_API} over there: each
	 * module's check requires the other's, so deleting either is reported by the one that remains.
	 * Read as source text, and that is the whole of what it can say — the file exists and carries a
	 * {@code @Test} passing the literal. It cannot say the check inside is right.
	 */
	private static final String API_SIDE_GUARD =
			"api/src/test/java/org/openmrs/module/chartsearchai/JavadocReferenceGuardTest.java";

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
	 * No POM in this tree takes a module's tests out of the build, asked from THIS module so that an
	 * edit taking the api module's tests out is still reported. The api-side guard holds the same line
	 * over the same POMs, so whichever module still runs its tests is the one that reports the edit.
	 *
	 * <p><strong>The defect.</strong> Each module's corpus check lives in the very test root it guards.
	 * So four lines in that module's POM — an {@code <executions>} entry binding
	 * {@code default-testCompile} to {@code <phase>none</phase>} — remove the root from javac and the
	 * check from the build at once, and every existing POM reader is blind to it: it carries no
	 * {@code <configuration>}, no {@code <version>} and no attribute. Measured before this arm existed,
	 * on this branch, JDK 21, plugin 3.13.0, with that element in {@code omod/pom.xml} and a dead
	 * pointer planted in {@code omod/src/test/java}: {@code mvn -o clean install} exit 0, BUILD SUCCESS,
	 * zero {@code reference not found} printed, omod's surefire logging "No tests to run", the api
	 * module's whole suite green, and the {@code .omod} artifact still installed. The mirror edit in
	 * {@code api/pom.xml} is the same defect with the modules swapped, and is the one THIS arm exists
	 * for: with it in place that edit reddens here, api's own tests never having run.
	 *
	 * <p><strong>What this arm asserts, and it is not the api side's closure written twice.</strong>
	 * Over there the rule is a closed world over a compiler or surefire {@code <plugin>} element's
	 * direct children, asked at every declaration. Here it is POSITIONAL: neither of
	 * {@link #PLUGINS_THAT_RUN_THESE_CHECKS} is declared anywhere except the root pom's
	 * {@code <build><pluginManagement>}, and there its direct children are within
	 * {@link #MANAGED_PLUGIN_CHILDREN_READ_HERE}. Two different statements about the same edits, so a
	 * maintainer weakening one does not weaken the other by the same edit. Both sides also read
	 * {@link #TEST_DEFEATING_PROPERTIES}, because a child pom's {@code <properties>} does the same
	 * thing from a line naming no plugin, and both read what is INSIDE a surefire
	 * {@code <configuration>} ({@link #SUREFIRE_PARAMETERS_DEFEATING_CHECKS}) — permitted since round
	 * 10, since refusing the element wholesale reddened an ordinary {@code argLine}, which left an
	 * {@code <excludes>} naming either check read by nothing.
	 *
	 * <p><strong>What the positional rule COSTS, and the api arm does not agree with it.</strong>
	 * Neither of {@link #PLUGINS_THAT_RUN_THESE_CHECKS} can be declared in a child pom's
	 * {@code <build><plugins>} at all — not even in a declaration taking no test out of anything, such
	 * as a {@code <version>} resolving from the root's properties or a {@code <release>} for a module
	 * on a different source level. That is ordinary multi-module Maven, and the api-side arm PERMITS
	 * that element: it closes a world over the element's children and reads its {@code <version>}
	 * against the floor. Round 11 KEPT the refusal rather than narrowing it. Narrowing means deciding
	 * whether a given element removes a test, which is reading what the element SAYS — the approach
	 * rounds 7 and 8 each took and each had falsified by the next position, and round 9's defeating
	 * element carried no {@code <configuration>}, no {@code <version>} and no attribute for exactly
	 * that reason. The cost is friction on a shape this repository does not use; the friction is
	 * self-explaining (the violation message names the position, why the plugin is read, and the
	 * remedy) and it reddens no POM that is legal in this reactor today. The disagreement with the api
	 * arm is not an oversight: the two arms stating the rule DIFFERENTLY is what makes one edit
	 * unlikely to defeat both, and making them agree would collapse them into one reader written
	 * twice. Whoever does need such a declaration changes this rule and records in docs/adr.md
	 * Decision 75 what replaced it and how round 9's element stays refused.
	 *
	 * <p><strong>The residue, and it has more than one cost — the sentence that stood here named
	 * one.</strong> A POM can still remove test execution from EVERY module at once, through an
	 * {@code <executions>} entry in the ROOT pom's {@code <build><plugins>} which children inherit, or
	 * a test-skip property in the root {@code <properties>}. Nothing written in a test survives that,
	 * because no test runs, and what it costs is every {@code Tests run:} line in the reactor —
	 * measured on both spellings: exit 0, BUILD SUCCESS, the reactor's test total zero.
	 * <strong>They do not print the same thing in place of those counts, and a sentence here said
	 * they did: that api's surefire printed no banner and omod's printed "No tests to run".</strong>
	 * Re-measured on this branch, JDK 21: the test-skip PROPERTY prints each module's surefire banner
	 * with {@code Tests are skipped.} under it, twice in the reactor and never "No tests to run"; the
	 * {@code <executions>} spelling prints no surefire output whatever, {@code grep surefire} over
	 * the whole log matching nothing because the goal is never invoked. "No tests to run" is what
	 * round 9's CHILD-pom shape printed — that shape is refused now, so it is the wrong string to
	 * check a log for. <strong>Round 10
	 * measured a shape that costs nothing of the kind</strong>: surefire's {@code test} filter in
	 * {@code api/pom.xml} left api printing {@code Tests run: 5} and this module's whole suite
	 * green, at exit 0, with the api-side guard simply absent from the five. It is refused now, by this
	 * arm — the api-side guard could not report it, not being among the tests that ran — and
	 * {@code maven.test.failure.ignore} is refused beside it, which cannot be turned into a red build
	 * at all, since it is what makes a guard's failure non-fatal; it is loud instead, on the
	 * {@code Tests run: N, Failures: M} line it cannot suppress — in the ROOT pom on BOTH modules'
	 * such lines, this arm being one of the checks that reports it. Do not publish the failure count:
	 * {@code JavadocReferenceGuardTest.TEST_FAILURE_IGNORED_PROPERTY} carries what was measured, and
	 * a tally of it went stale inside the commit that published it.
	 *
	 * <p><strong>Round 12 measured a shape that removes BOTH arms at once and is not loud in any of
	 * those ways.</strong> A surefire SELECTION in the ROOT pom whose value removes both guards from
	 * the run is refused by nothing that runs, the refusal being written in the very tests the
	 * selection drops: measured on this branch, JDK 21,
	 * {@code <surefire.excludes>**}{@code /JavadocReference*Test.java</surefire.excludes>} in the root
	 * {@code <properties>} gives {@code mvn -o clean install} exit 0, BUILD SUCCESS,
	 * {@code grep -c JavadocReference} over the whole log ZERO, and the reactor's test total short by
	 * only the guards' own tests — which reads as an ordinary green build rather than as a total that
	 * dropped. {@code <groups>eval</groups>} there gives exit 0 with {@code Tests run: 0} for both
	 * modules instead. Neither reinstates #262's defect on its own: the flag stays on javac, and that
	 * same exclusion with a dead pointer planted in {@code api/src/main/java} gives exit 1 with
	 * {@code reference not found} printed. What it removes is the guard on the flag's CONTENTS, so a
	 * second edit — one added {@code <arg>} in the managed {@code <compilerArgs>} — is what silences
	 * doclint. What the property refusal DOES reach is the same selection in a CHILD pom, reported by
	 * the module whose tests still run: {@code <groups>eval</groups>} in {@code api/pom.xml} gave exit
	 * 1 from THIS arm with api running no tests at all.
	 *
	 * <p><strong>Round 14's shape is a fifth and it sits with round 12's rather than beside it.</strong>
	 * {@code <excludedGroups>none()</excludedGroups>} in {@code api/pom.xml}'s {@code <properties>}
	 * gave {@code mvn -o clean install} exit 0, BUILD SUCCESS, api {@code Tests run: 0}, this
	 * module's suite green, and {@code excludedGroups} named ZERO times in the whole log — so unlike
	 * round 10's and round 12's child-pom cases, the module whose tests still ran did not report it,
	 * that property being in neither arm's list. Adding one {@code <arg>} to the managed
	 * {@code <compilerArgs>} beside it, with a dead pointer in {@code api/src/main/java}, gave exit 0
	 * with no {@code reference not found} printed: #262's defect reinstated by two POM edits nothing
	 * reported. Both are refused now — {@code excludedGroups} by name, since its element form needs
	 * a map entry anyway, and {@code surefire.excludeJUnit5Engines} (verified in the same round, same
	 * exit 0, same api {@code Tests run: 0}) by {@link #SUREFIRE_USER_PROPERTY_PREFIX}. That prefix
	 * is the change of shape: four rounds each supplied one more name. <strong>Two shapes round 14
	 * measured that are NOT holes</strong>, recorded so nobody re-measures them:
	 * {@code surefire.suiteXmlFiles} pointing at a suite file gave exit 1 with
	 * {@code [ERROR] ... suiteXmlFiles is configured, but there is no TestNG dependency}, so it fails
	 * loudly; and {@code failIfNoTests} carries {@code default-value="false"} in the pinned
	 * descriptor, so neither of its values takes a test away.
	 *
	 * <p>So the honest bound is not "no POM edit can silence this" and not one sentence about cost
	 * either: an edit taking ONE module's checks out is refused by the module whose checks still run,
	 * the two arms state that rule differently so one edit does not weaken both, and what is left is
	 * loud on the printed doclint error, on a {@code Failures:} line beside exit 0, on a reactor test
	 * total that drops, or on whatever a module's surefire prints in place of its counts
	 * ({@code Tests are skipped.}, or nothing at all) — and never on a {@code No tests to run}, which
	 * belongs to the CHILD-pom shape this arm refuses. Except where it is not loud at all, which the
	 * paragraph above and the api-side class javadoc state as far as anyone has measured it.
	 */
	@Test
	public void noPomEditTakesAModuleOutOfTheTestBuild() throws Exception {
		List<String> violations = new ArrayList<String>();
		List<String> poms = pomsInTheTree();
		for (String required : REQUIRED_POMS) {
			if (!poms.contains(required)) {
				fail("The POM walk under " + repoRoot() + " did not find " + required + " (it found " + poms
						+ "), so this arm is reading the wrong tree or no tree at all and would report no "
						+ "violation whatever the POMs say. REQUIRED_POMS is its anchor, for the reason "
						+ "SOURCE_ROOTS is the corpus walk's.");
			}
		}
		int managedEntriesRead = 0;
		for (String pom : poms) {
			Element root = pomRoot(pom);
			for (String named : PLUGINS_THAT_RUN_THESE_CHECKS) {
				for (Element plugin : pluginsNamed(root, named)) {
					if (!"pom.xml".equals(pom) || !sitsAtTheRootsManagedEntry(plugin)) {
						violations.add(pom + " declares " + named + " somewhere other than the root pom's "
								+ "<build><pluginManagement>. That plugin decides whether a module's tests are "
								+ "compiled and run at all, and a module whose tests do not run is a module whose "
								+ "javadoc pointers are held by the api-side POM readers alone — the state round 8 "
								+ "was written to end. Measured: an <executions> entry binding default-testCompile "
								+ "to <phase>none</phase> in omod/pom.xml gave BUILD SUCCESS at exit 0 with a dead "
								+ "pointer standing in omod/src/test/java. Refused by POSITION rather than by "
								+ "reading what the element says, so the next element nobody has heard of is "
								+ "refused too");
						continue;
					}
					managedEntriesRead++;
					for (Element child : elementChildren(plugin)) {
						if (!MANAGED_PLUGIN_CHILDREN_READ_HERE.contains(child.getNodeName())) {
							violations.add(pom + " declares " + named + " at the managed entry with a <"
									+ child.getNodeName() + "> child, which this arm does not account for. An "
									+ "<executions> element there unbinds the goal for BOTH modules; see this "
									+ "method's javadoc for what is covered and what is not");
						}
					}
					if (SUREFIRE_PLUGIN.equals(named)) {
						for (String where : surefireParametersDefeatingChecksIn(plugin)) {
							violations.add(pom + " " + named + " at the managed entry " + where + ", which "
									+ "stops a module's checks running or discards their verdict. The "
									+ "<configuration> element itself is permitted — refusing it wholesale "
									+ "reddened an ordinary argLine — so what is inside it is read instead. An "
									+ "<excludes> or a <test> there can name the api-side guard, which is then "
									+ "not running to report it; this arm is. See "
									+ "SUREFIRE_PARAMETERS_DEFEATING_CHECKS");
						}
					}
				}
			}
			for (String where : testDefeatingPropertiesIn(root)) {
				violations.add(pom + " " + where);
			}
		}
		if (managedEntriesRead == 0) {
			fail("This arm read " + poms.size() + " POM(s) and found no managed declaration of any of "
					+ PLUGINS_THAT_RUN_THESE_CHECKS + " at the root pom's <build><pluginManagement>, so it is "
					+ "asserting nothing about the one position it exists to hold. The root pom is required to "
					+ "manage maven-compiler-plugin there — JavadocReferenceGuardTest."
					+ "theArgumentsTheBuildDeclaresRefuseADeadJavadocReference reads the argument list off that "
					+ "very element — so finding none means this walk, its parse, or that declaration is gone.");
		}
		List<String> skipping = testDefeatingPropertiesIn(parseXml("<project><properties>"
				+ "<maven.test.skip>true</maven.test.skip></properties><profiles><profile><properties>"
				+ "<skipTests>true</skipTests></properties></profile></profiles></project>"));
		if (skipping.size() != 2) {
			violations.add("the properties that take a module's tests out of the build are not both read out "
					+ "of a POM's <properties> — the project's own and a <profile>'s (it read " + skipping
					+ "). This arm is the ONLY one that can see such an entry in api/pom.xml, because the "
					+ "api-side guard does not run when api's own tests are skipped. This repository sets "
					+ "none of them, so only this synthetic POM can say so");
		}
		List<String> notSkipping = testDefeatingPropertiesIn(parseXml("<project><properties>"
				+ "<maven.test.skip>false</maven.test.skip><test></test></properties><build><plugins><plugin>"
				+ "<configuration><properties><skipTests>true</skipTests></properties></configuration>"
				+ "</plugin></plugins></build></project>"));
		if (!notSkipping.isEmpty()) {
			violations.add("a test-skip property set to FALSE, an EMPTY test filter, or a <properties> inside "
					+ "a plugin's own <configuration>, is reported as taking tests out of the build (it read "
					+ notSkipping + "). None is: false is the default, an empty filter selects nothing, and "
					+ "maven-surefire-plugin's descriptor declares a <properties> parameter of type "
					+ "java.util.Properties for its provider configuration. Any of those refusals reddens a "
					+ "POM that builds exactly as this one does");
		}
		List<String> filteredAndIgnored = testDefeatingPropertiesIn(parseXml("<project><properties>"
				+ "<test>DateFormatUtilTest</test><maven.test.failure.ignore>true"
				+ "</maven.test.failure.ignore></properties></project>"));
		if (filteredAndIgnored.size() != 2) {
			violations.add("the two properties that defeat a module's checks without SKIPPING anything — "
					+ "surefire's <test> filter and <maven.test.failure.ignore> — are not both read (it read "
					+ filteredAndIgnored + "). Measured BEFORE it was refused: <test>DateFormatUtilTest</test> "
					+ "in api/pom.xml gave exit 0 with api running 5 tests, the api-side guard not among them, "
					+ "and this module's whole suite green. With it refused, this arm reddens instead. Both "
					+ "were missing from these lists until round 10");
		}
		String tuned = "<plugin><artifactId>" + SUREFIRE_PLUGIN + "</artifactId><configuration>"
				+ "<argLine>-Xmx1024m</argLine><skipTests>false</skipTests></configuration></plugin>";
		List<String> tuningRefused = surefireParametersDefeatingChecksIn(parseXml(tuned));
		if (!tuningRefused.isEmpty()) {
			violations.add("an ordinary surefire <configuration> — an argLine, skipTests explicitly false — is "
					+ "reported as defeating a module's checks (it read " + tuningRefused + "). It takes "
					+ "nothing away, and refusing it reddens a legal build: measured, refusing the whole "
					+ "<configuration> element gave exit 1 on exactly that POM. The one failure direction both "
					+ "arms refuse");
		}
		// The ELEMENT form of every parameter in SUREFIRE_PARAMETERS_DEFEATING_CHECKS, and the user
		// PROPERTY form beside it, are asserted together by
		// everySurefireParameterIsRefusedInBothFormsMavenReadsIt. Kept apart, this arm refused ten
		// elements and read five properties, which is round 12.
		Path apiGuard = repoRoot().resolve(API_SIDE_GUARD);
		if (!Files.isRegularFile(apiGuard)) {
			violations.add(API_SIDE_GUARD + " does not exist. That file compiles the api module's two source "
					+ "roots with an argument it chooses itself, and holds the POM readers this arm mirrors; "
					+ "without it the api module's pointers are held by nothing. Each module's check requires "
					+ "the other's, so deleting either is reported by the one that remains");
		}
		else {
			String source = new String(Files.readAllBytes(apiGuard), StandardCharsets.UTF_8);
			if (!source.contains("@Test") || !source.contains("\"" + REFERENCE_CHECK + "\"")) {
				violations.add(API_SIDE_GUARD + " no longer declares a @Test passing " + REFERENCE_CHECK
						+ " as its own literal, so it can no longer be what compiles the api module's sources "
						+ "with arguments no build file decides");
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * Every surefire parameter this arm refuses as a {@code <configuration>} ELEMENT is read in the
	 * user-PROPERTY form Maven binds it to as well, and neither form is refused when it is empty —
	 * asked of the two real readers over a POM per form, with the pairs spelled out as LITERALS in
	 * {@link #SUREFIRE_PARAMETER_FORMS_AS_LITERALS} and not iterated off the map they exist to hold.
	 * Spelled out for exactly that reason: derived from
	 * {@link #SUREFIRE_PARAMETERS_DEFEATING_CHECKS}, this check could not redden on an entry deleted
	 * from it, which is the mutation it exists for.
	 *
	 * <p><strong>What it is for.</strong> Rounds 5, 10 and 12 of this change's review had one shape
	 * three times: a parameter refused at one of the two positions Maven answers it from and unread at
	 * the other. This arm carried round 12 — ten elements refused against five properties read — and
	 * {@code <surefire.excludes>} naming both guards in the ROOT pom's {@code <properties>} took them
	 * both out of the reactor at exit 0 with {@code grep -c JavadocReference} over the whole log zero.
	 * Since neither guard ran, neither reported it, and the managed {@code <compilerArgs>} the api arm
	 * reads was then held by nothing.
	 *
	 * <p><strong>What reading the property buys here, and what it does not.</strong> Measured on this
	 * branch, JDK 21: a selection property in a CHILD pom is reported by the module whose tests still
	 * run, which is this arm's whole reason —
	 * {@code <groups>eval</groups>} in {@code api/pom.xml} gave {@code mvn -o clean install} exit 1
	 * with api running no tests and this arm naming the entry. A selection in the ROOT pom is reported
	 * where its value leaves the guards running, and reported by NOTHING where the value removes them
	 * both; {@link #noPomEditTakesAModuleOutOfTheTestBuild} discloses that rather than covering it.
	 *
	 * <p><strong>Round 14 was the fourth instance and the answer is a rule, not two further
	 * names.</strong> {@code excludedGroups} had been left out on a ground that is false for the
	 * provider this reactor uses, and {@code surefire.excludeJUnit5Engines} was verified alongside
	 * it. So this check also drives {@link #testDefeatingPropertiesIn} over
	 * {@link #PREFIXED_PROPERTIES_NO_MAP_ENTRY_NAMES}, two properties the map does not name, which is
	 * the assertion that the property leg answers by {@link #SUREFIRE_USER_PROPERTY_PREFIX}. The
	 * ELEMENT side gets no prefix rule because an element name carries none, which is why
	 * {@code excludedGroups}, {@code excludeJUnit5Engines} and {@code includeJUnit5Engines} are map
	 * entries.
	 *
	 * <p><strong>Mutate it rather than trust it.</strong> Drop the {@code excludes} entry from
	 * {@link #SUREFIRE_PARAMETERS_DEFEATING_CHECKS} and this check names that pair on BOTH legs — the
	 * element reader iterates that map's keys and {@link #TEST_DEFEATING_PROPERTIES} is derived from
	 * its values, so one deletion reopens both positions, which is why the literals are the
	 * independent statement. Remove the prefix leg from {@link #testDefeatingPropertiesIn} and this
	 * check names both of {@link #PREFIXED_PROPERTIES_NO_MAP_ENTRY_NAMES}, twice each; docs/adr.md
	 * Decision 75 carries that mutation's measurement.
	 *
	 * <p><strong>What it does not assert.</strong> That a pair is the binding surefire really
	 * declares — those were read off the pinned plugin's own descriptor and recorded in
	 * {@link #SUREFIRE_PARAMETERS_DEFEATING_CHECKS}, and nothing re-reads it at build time. A value
	 * mistyped on ONE side reddens here; one mistyped in the map AND in
	 * {@link #SUREFIRE_PARAMETER_FORMS_AS_LITERALS} is green in both modules, which is r14-2 and
	 * stands. {@link #SUREFIRE_USER_PROPERTY_PREFIX} reduces its reach without closing it, measured
	 * on the api arm: {@code surefire.excludeJUnit5Engines} mistyped in both places there left that
	 * arm's pairing check green while the real property, planted in {@code api/pom.xml}, was still
	 * refused at exit 1 by the prefix leg, which looks no name up. What is left is the un-prefixed
	 * pairs, which have no second leg behind them. Nor does it say anything about an element name the map
	 * does not carry; {@link #noPomEditTakesAModuleOutOfTheTestBuild} discloses what that leaves
	 * open.
	 */
	@Test
	public void everySurefireParameterIsRefusedInBothFormsMavenReadsIt() throws Exception {
		List<String> violations = new ArrayList<String>();
		for (String[] forms : SUREFIRE_PARAMETER_FORMS_AS_LITERALS) {
			String parameter = forms[0];
			String property = forms[1];
			String element = "<" + parameter + ">" + forms[2] + "</" + parameter + ">";
			String atThePlugin = "<plugin><artifactId>" + SUREFIRE_PLUGIN + "</artifactId><configuration>"
					+ element + "</configuration></plugin>";
			String atAnExecution = "<plugin><artifactId>" + SUREFIRE_PLUGIN + "</artifactId><executions>"
					+ "<execution><id>default-test</id><configuration>" + element
					+ "</configuration></execution></executions></plugin>";
			for (String refusable : Arrays.asList(atThePlugin, atAnExecution)) {
				if (surefireParametersDefeatingChecksIn(parseXml(refusable)).isEmpty()) {
					violations.add("a surefire configuration carrying " + element + " is not refused ("
							+ refusable + "). The exclusion naming the API-side guard is the case this arm "
							+ "exists for: that guard is not running to report it. This repository configures "
							+ "surefire nowhere, so only these synthetic POMs can say so");
				}
			}
			String own = "<project><properties><" + property + ">" + forms[3] + "</" + property
					+ "></properties></project>";
			String inAProfile = "<project><profiles><profile><properties><" + property + ">" + forms[3]
					+ "</" + property + "></properties></profile></profiles></project>";
			for (String readable : Arrays.asList(own, inAProfile)) {
				if (testDefeatingPropertiesIn(parseXml(readable)).isEmpty()) {
					violations.add("<" + parameter + "> is refused as a surefire ELEMENT while <" + property
							+ ">, the user property maven-surefire-plugin binds it to, is read by nothing ("
							+ readable + "). That asymmetry is the shape of rounds 5, 10 and 12: three words "
							+ "in a <properties> walk round the element refusal and no check reports them. "
							+ "Measured on this branch, JDK 21 — <groups>eval</groups> in api/pom.xml gave mvn "
							+ "-o clean install exit 1 with api running no tests and THIS arm naming it, which "
							+ "is the position the api-side guard cannot hold. Put the pair in "
							+ "SUREFIRE_PARAMETERS_DEFEATING_CHECKS, whose keys the element reader iterates and "
							+ "whose values TEST_DEFEATING_PROPERTIES is derived from");
				}
			}
			String emptyElement = "<plugin><artifactId>" + SUREFIRE_PLUGIN + "</artifactId><configuration>"
					+ "<" + parameter + "></" + parameter + "></configuration></plugin>";
			List<String> elementRefused = surefireParametersDefeatingChecksIn(parseXml(emptyElement));
			List<String> propertyRefused = testDefeatingPropertiesIn(parseXml("<project><properties><"
					+ property + "></" + property + "></properties></project>"));
			if (!elementRefused.isEmpty() || !propertyRefused.isEmpty()) {
				violations.add("an EMPTY <" + parameter + "> element or <" + property + "> property is "
						+ "reported as defeating a module's checks (it read " + elementRefused + " and "
						+ propertyRefused + "). Neither takes anything away — an empty selection selects "
						+ "nothing and an empty flag is not true — so refusing either reddens a POM that "
						+ "builds exactly as this one does, the one failure direction both arms refuse. See "
						+ "defeatsAModulesChecks");
			}
		}
		for (String property : PREFIXED_PROPERTIES_NO_MAP_ENTRY_NAMES) {
			if (TEST_DEFEATING_PROPERTIES.contains(property)) {
				violations.add("<" + property + "> is now one of TEST_DEFEATING_PROPERTIES, so it no longer "
						+ "witnesses that the property reader answers by the " + SUREFIRE_USER_PROPERTY_PREFIX
						+ " prefix — the prefix leg skips a name that list already reports, so the two checks "
						+ "below would pass with the prefix rule deleted. Pick a prefixed property the map "
						+ "does not carry, which is what PREFIXED_PROPERTIES_NO_MAP_ENTRY_NAMES is for");
				continue;
			}
			String own = "<project><properties><" + property + ">something</" + property
					+ "></properties></project>";
			String inAProfile = "<project><profiles><profile><properties><" + property + ">something</"
					+ property + "></properties></profile></profiles></project>";
			for (String readable : Arrays.asList(own, inAProfile)) {
				if (testDefeatingPropertiesIn(parseXml(readable)).isEmpty()) {
					violations.add("<" + property + "> is read by nothing (" + readable + "), so this arm "
							+ "answers for maven-surefire-plugin's user properties by NAME and not by the "
							+ SUREFIRE_USER_PROPERTY_PREFIX + " prefix. Rounds 5, 10, 12 and 14 each supplied "
							+ "one more name, and round 14 verified the following one in the same breath — "
							+ "<surefire.excludeJUnit5Engines>junit-jupiter in api/pom.xml, exit 0 with api "
							+ "Tests run: 0 and this module's suite green. A name per round is a list whose "
							+ "next hole is already known. These two are outside the map deliberately, and the "
							+ "guard above keeps them there: one is a real parameter of the pinned plugin, one "
							+ "is invented. See SUREFIRE_USER_PROPERTY_PREFIX");
				}
			}
			String blank = "<project><properties><" + property + "></" + property
					+ "></properties></project>";
			if (!testDefeatingPropertiesIn(parseXml(blank)).isEmpty()) {
				violations.add("an EMPTY <" + property + "> is refused (" + blank + "). A blank value takes "
						+ "nothing away, so refusing it reddens a POM that builds exactly as this one does, "
						+ "the one failure direction both arms refuse");
			}
		}
		assertNoViolations(violations);
	}

	/**
	 * Every {@code <properties>} entry in one POM that stops that module's checks asserting anything,
	 * described. Two legs: {@link #TEST_DEFEATING_PROPERTIES} by NAME, judged by
	 * {@link #defeatsAModulesChecks}, and {@link #SUREFIRE_USER_PROPERTY_PREFIX} by PREFIX, judged on
	 * blankness alone. The prefix leg skips a name the first leg already reported, so a prefixed
	 * selection this arm also refuses as an element is described once, by the leg that can name both
	 * forms. See {@link #propertiesDeclaredUnderProjectOrProfile} for why the element is not read
	 * document-wide.
	 */
	private static List<String> testDefeatingPropertiesIn(Element pom) {
		List<String> where = new ArrayList<String>();
		for (Element properties : propertiesDeclaredUnderProjectOrProfile(pom)) {
			for (String property : TEST_DEFEATING_PROPERTIES) {
				Element declared = directChild(properties, property);
				if (defeatsAModulesChecks(declared)) {
					where.add("<properties> sets <" + property + ">" + declared.getTextContent().trim()
							+ "</" + property + ">, which stops that module's checks asserting anything — by "
							+ "skipping them, by narrowing the run to something else, or by discarding their "
							+ "verdict. One line, naming no plugin. See TEST_DEFEATING_PROPERTIES");
				}
			}
			for (Element entry : elementChildren(properties)) {
				String name = entry.getNodeName();
				String value = entry.getTextContent().trim();
				if (!name.startsWith(SUREFIRE_USER_PROPERTY_PREFIX) || value.isEmpty()
						|| TEST_DEFEATING_PROPERTIES.contains(name)) {
					continue;
				}
				where.add("<properties> sets <" + name + ">" + value + "</" + name + ">, one of "
						+ "maven-surefire-plugin's own user properties. The rule at this leg is the "
						+ SUREFIRE_USER_PROPERTY_PREFIX + " PREFIX and not the parameter behind the name, "
						+ "because four review rounds each supplied one more name and round 14 verified the "
						+ "following one while it was at it — <surefire.excludeJUnit5Engines>junit-jupiter in "
						+ "api/pom.xml, exit 0 with api Tests run: 0 and this module's suite green. Unlike "
						+ "defeatsAModulesChecks this leg does not exempt the word false, since the harmful "
						+ "value is not uniformly true across the prefixed properties; a blank value is "
						+ "permitted. It refuses tuning too, which is its cost. See "
						+ "SUREFIRE_USER_PROPERTY_PREFIX");
			}
		}
		return where;
	}

	/**
	 * Every surefire parameter inside the managed entry's {@code <configuration>} — the plugin's own or
	 * an execution's — that stops a module's checks asserting anything, described. See
	 * {@link #SUREFIRE_PARAMETERS_DEFEATING_CHECKS}.
	 */
	private static List<String> surefireParametersDefeatingChecksIn(Element plugin) {
		List<String> where = new ArrayList<String>();
		List<Element> configurations = new ArrayList<Element>();
		Element pluginLevel = directChild(plugin, "configuration");
		if (pluginLevel != null) {
			configurations.add(pluginLevel);
		}
		Element executions = directChild(plugin, "executions");
		if (executions != null) {
			for (Element execution : elementChildren(executions)) {
				Element configuration = directChild(execution, "configuration");
				if (configuration != null) {
					configurations.add(configuration);
				}
			}
		}
		for (Element configuration : configurations) {
			for (String parameter : SUREFIRE_PARAMETERS_DEFEATING_CHECKS.keySet()) {
				Element declared = directChild(configuration, parameter);
				if (defeatsAModulesChecks(declared)) {
					where.add("<configuration> sets <" + parameter + ">" + declared.getTextContent().trim()
							+ "</" + parameter + ">");
				}
			}
		}
		return where;
	}

	/**
	 * Whether one such element actually takes something away: ABSENT does not, and neither does a
	 * blank value (an empty filter selects nothing) or the word {@code false} (which is the default of
	 * every flag in these lists). Anything else does — a {@code true}, a class-name pattern, a list of
	 * exclusions. One rule over both lists, deliberately coarser than the api-side guard's split by
	 * value shape: refusing a POM that builds exactly as this one does is the failure direction both
	 * arms refuse, and the three cases above are the whole of what a legal declaration looks like here.
	 */
	private static boolean defeatsAModulesChecks(Element declared) {
		if (declared == null) {
			return false;
		}
		String value = declared.getTextContent().trim();
		return !value.isEmpty() && !"false".equalsIgnoreCase(value);
	}

	/**
	 * One XML document parsed from a string, for {@link #noPomEditTakesAModuleOutOfTheTestBuild} to ask
	 * its readers about shapes this repository does not contain. Namespace-unaware, as
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
	 * Every {@code pom.xml} at the repository root or one directory below it, sorted. A filesystem walk
	 * and deliberately not a read of the root pom's {@code <modules>}: the list of POMs a POM arm reads
	 * must not itself come from a POM. {@link #REQUIRED_POMS} is what stops a walk that resolves
	 * nowhere passing in silence, and this walk is what catches a POM that no {@code <modules>}
	 * declares.
	 *
	 * <p>It does not look deeper than one level, which is a limit of this arm and not a claim about the
	 * repository — it carries no nested POM today.
	 */
	private static List<String> pomsInTheTree() throws IOException {
		Path repoRoot = repoRoot();
		List<String> poms = new ArrayList<String>();
		if (Files.isRegularFile(repoRoot.resolve("pom.xml"))) {
			poms.add("pom.xml");
		}
		DirectoryStream<Path> children = Files.newDirectoryStream(repoRoot);
		try {
			for (Path child : children) {
				if (Files.isRegularFile(child.resolve("pom.xml"))) {
					poms.add(child.getFileName().toString() + "/pom.xml");
				}
			}
		}
		finally {
			children.close();
		}
		Collections.sort(poms);
		return poms;
	}

	/**
	 * One POM's {@code <project>} element, namespace-unaware so element names match without a prefix. A
	 * file this walk named and cannot parse fails loudly, because a POM read as nothing is a POM this
	 * arm checks nothing in.
	 */
	private static Element pomRoot(String pom) {
		Path path = repoRoot().resolve(pom);
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(false);
			return factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
		}
		catch (Exception unreadable) {
			fail("Could not parse " + path + " (" + unreadable + "), so this arm would check nothing in it.");
			return null;
		}
	}

	/**
	 * Every {@code <plugin>} element in one POM declaring the given artifactId, wherever it sits —
	 * {@code <plugins>}, {@code <pluginManagement>} or inside a {@code <profile>}. Read document-wide
	 * on purpose: WHERE it sits is the question {@link #sitsAtTheRootsManagedEntry} answers, and an
	 * element this reader skipped would be one the position rule never judged.
	 */
	private static List<Element> pluginsNamed(Element pom, String named) {
		List<Element> plugins = new ArrayList<Element>();
		NodeList all = pom.getElementsByTagName("plugin");
		for (int i = 0; i < all.getLength(); i++) {
			Element plugin = (Element) all.item(i);
			Element artifactId = directChild(plugin, "artifactId");
			if (artifactId != null && named.equals(artifactId.getTextContent().trim())) {
				plugins.add(plugin);
			}
		}
		return plugins;
	}

	/**
	 * Whether one {@code <plugin>} element sits at {@code project/build/pluginManagement/plugins}, the
	 * one position this repository declares either of {@link #PLUGINS_THAT_RUN_THESE_CHECKS} at.
	 * Checked by walking the ancestor chain rather than by searching down from the root, so a
	 * {@code <plugin>} nested inside another plugin's own {@code <configuration>} — a shape
	 * maven-dependency-plugin and moditect both make possible — cannot pass for the managed entry.
	 */
	private static boolean sitsAtTheRootsManagedEntry(Element plugin) {
		Node node = plugin.getParentNode();
		for (String enclosing : Arrays.asList("plugins", "pluginManagement", "build", "project")) {
			if (!(node instanceof Element) || !enclosing.equals(node.getNodeName())) {
				return false;
			}
			node = node.getParentNode();
		}
		return true;
	}

	/**
	 * Every {@code <properties>} a POM declares directly under its own {@code <project>} or under one
	 * of its {@code <profile>}s — never a plugin parameter of the same name. maven-surefire-plugin's
	 * descriptor declares a {@code <properties>} parameter of type {@code java.util.Properties} for its
	 * provider configuration, so reading the element document-wide would redden a legal POM, which is
	 * the one failure direction this class refuses.
	 */
	private static List<Element> propertiesDeclaredUnderProjectOrProfile(Element pom) {
		List<Element> declared = new ArrayList<Element>();
		NodeList all = pom.getElementsByTagName("properties");
		for (int i = 0; i < all.getLength(); i++) {
			Element element = (Element) all.item(i);
			Node parent = element.getParentNode();
			String enclosing = parent == null ? "" : parent.getNodeName();
			if ("project".equals(enclosing) || "profile".equals(enclosing)) {
				declared.add(element);
			}
		}
		return declared;
	}

	/** Every ELEMENT child of one element, whatever it is named. */
	private static List<Element> elementChildren(Element parent) {
		List<Element> children = new ArrayList<Element>();
		if (parent == null) {
			return children;
		}
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE) {
				children.add((Element) node);
			}
		}
		return children;
	}

	private static Element directChild(Element parent, String name) {
		for (Element child : elementChildren(parent)) {
			if (name.equals(child.getNodeName())) {
				return child;
			}
		}
		return null;
	}

	private static void assertNoViolations(List<String> violations) {
		if (!violations.isEmpty()) {
			fail(violations.size() + " violation(s) — the checks holding #262's gate can be taken out of "
					+ "this build, and a module whose tests do not run is a module whose javadoc pointers\n"
					+ "are held by POM readers alone:\n\n" + join(violations)
					+ "\nSee docs/adr.md, Decision 75, and this method's javadoc for what is covered and what\n"
					+ "a POM can still do.");
		}
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
