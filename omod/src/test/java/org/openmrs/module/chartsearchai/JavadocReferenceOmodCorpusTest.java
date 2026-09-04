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
 * asked of the real compiler over the real files with arguments THIS class chooses — so no POM edit
 * can change what this check LOOKS FOR.
 *
 * <p><strong>An earlier version of that sentence said no POM edit could silence it, and round 9 of
 * #262's review falsified it.</strong> The arguments are literals here, so no POM decides them; but
 * this check lives in the very test root it guards, so four lines in {@code omod/pom.xml} — an
 * {@code <executions>} entry binding {@code default-testCompile} to {@code <phase>none</phase>} —
 * take that root out of javac AND take this class out of the build, together. Measured, JDK 21,
 * plugin 3.13.0: exit 0, BUILD SUCCESS, zero {@code reference not found}, omod's surefire logging
 * "No tests to run", the api module green. What answers that is
 * {@link #noPomEditTakesAModuleOutOfTheTestBuild} below, whose javadoc states what is covered, what
 * is not, and what a POM can still do. <strong>Do not write a third absolute here.</strong>
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

	/**
	 * The two plugins that decide whether ANY check in this repository runs — the one that compiles a
	 * module's test sources and the one that executes them. Named here because
	 * {@link #noPomEditTakesAModuleOutOfTheTestBuild} reads them out of every POM, which is the arm the
	 * api-side guard cannot hold on its own: an api-side test does not run when api's own tests are
	 * taken out of the build.
	 */
	private static final List<String> PLUGINS_THAT_RUN_THESE_CHECKS =
			Arrays.asList("maven-compiler-plugin", "maven-surefire-plugin");

	/**
	 * The direct children the root pom's managed entry for either of those plugins may declare. Anything
	 * else is refused, which is how {@code <executions>} — round 9's finding, and the position no
	 * existing reader could see — is refused without a reader for {@code <phase>}. This list is the
	 * shape of this repository's own two managed entries and nothing more.
	 */
	private static final List<String> MANAGED_PLUGIN_CHILDREN_READ_HERE =
			Arrays.asList("groupId", "artifactId", "version", "configuration");

	/**
	 * The user properties that take a module's tests out of the build — {@code maven.test.skip} is read
	 * by maven-compiler-plugin's {@code testCompile} and by surefire, the other two are surefire's own.
	 * Set in a CHILD pom each does what round 9's {@code <executions>} element did, from three words
	 * naming no plugin.
	 */
	private static final List<String> TEST_SKIP_PROPERTIES =
			Arrays.asList("maven.test.skip", "maven.test.skip.exec", "skipTests");

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
	 * {@link #MANAGED_PLUGIN_CHILDREN_READ_HERE}. Two different statements, each sufficient on its own,
	 * so a maintainer weakening one does not weaken the other by the same edit. Both sides also read
	 * {@link #TEST_SKIP_PROPERTIES}, because a child pom's {@code <properties>} does the same thing
	 * from three words naming no plugin.
	 *
	 * <p><strong>The residue, stated because two absolutes have already been falsified here.</strong> A
	 * POM can still remove test execution from EVERY module at once — an {@code <executions>} entry in
	 * the ROOT pom's {@code <build><plugins>}, which children inherit, or a test-skip property in the
	 * root {@code <properties>}. Nothing written in a test survives that, because no test runs. What it
	 * costs the person doing it is that the build then runs no tests at all, in any module. Measured on
	 * both spellings of that edit: exit 0 and BUILD SUCCESS, but api's surefire printed no test banner
	 * and no counts, omod's printed "No tests to run", and not one {@code Tests run:} line was emitted
	 * for either module — so the reactor's test total was zero, rather than green with a dead pointer
	 * hidden inside it. That is the honest bound, and it is not "no POM edit can silence this".
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
				}
			}
			for (String where : testSkipPropertiesIn(root)) {
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
		List<String> skipping = testSkipPropertiesIn(parseXml("<project><properties>"
				+ "<maven.test.skip>true</maven.test.skip></properties><profiles><profile><properties>"
				+ "<skipTests>true</skipTests></properties></profile></profiles></project>"));
		if (skipping.size() != 2) {
			violations.add("the properties that take a module's tests out of the build are not both read out "
					+ "of a POM's <properties> — the project's own and a <profile>'s (it read " + skipping
					+ "). This arm is the ONLY one that can see such an entry in api/pom.xml, because the "
					+ "api-side guard does not run when api's own tests are skipped. This repository sets "
					+ "none of them, so only this synthetic POM can say so");
		}
		List<String> notSkipping = testSkipPropertiesIn(parseXml("<project><properties>"
				+ "<maven.test.skip>false</maven.test.skip></properties><build><plugins><plugin>"
				+ "<configuration><properties><skipTests>true</skipTests></properties></configuration>"
				+ "</plugin></plugins></build></project>"));
		if (!notSkipping.isEmpty()) {
			violations.add("a test-skip property set to FALSE, or a <properties> inside a plugin's own "
					+ "<configuration>, is reported as taking tests out of the build (it read " + notSkipping
					+ "). Neither is: false is the default, and maven-surefire-plugin's descriptor declares a "
					+ "<properties> parameter of type java.util.Properties for its provider configuration. "
					+ "Either refusal reddens a POM that builds exactly as this one does");
		}
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
	 * Every {@code <properties>} entry in one POM that takes that module's tests out of the build,
	 * described. See {@link #TEST_SKIP_PROPERTIES}, and {@link #propertiesDeclaredUnderProjectOrProfile}
	 * for why the element is not read document-wide.
	 */
	private static List<String> testSkipPropertiesIn(Element pom) {
		List<String> where = new ArrayList<String>();
		for (Element properties : propertiesDeclaredUnderProjectOrProfile(pom)) {
			for (String property : TEST_SKIP_PROPERTIES) {
				Element declared = directChild(properties, property);
				if (declared != null && "true".equalsIgnoreCase(declared.getTextContent().trim())) {
					where.add("<properties> sets <" + property + ">true</" + property + ">, which takes that "
							+ "module's tests out of the build — so its corpus check asserts nothing. Three "
							+ "words, naming no plugin. See TEST_SKIP_PROPERTIES");
				}
			}
		}
		return where;
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
