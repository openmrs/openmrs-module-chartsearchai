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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * which the check above cannot see. It reads the corpus as well as the POM, because
 * {@code -Xdoclint} has a PACKAGE qualifier and a probe answers only for the package it declares:
 * the probe is written once per package the reactor's sources declare ({@link #corpusPackages}), and
 * the answer is the set of packages the declared arguments leave unchecked. See
 * {@link #DEAD_REFERENCE_BODY}.
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
 * {@link #noOtherCompilerConfigurationDropsTheCheck}, which is where the world is CLOSED: every
 * compiler-plugin parameter these POMs set is either read as an argument channel, refused outright,
 * or has been judged unable to carry a javac argument, and anything else reddens. That is deliberately
 * not a longer list of parameter names. Three review rounds of this change each found one more name
 * that silences the gate — the {@code maven.compiler.failOnError} user property, the
 * {@code -Xdoclint/package} qualifier, and four sibling argument parameters beside
 * {@code compilerArgs} — and each was fixed by reading one more name, which is what left the next one
 * reachable. See {@link #PARAMETERS_CARRYING_NO_JAVAC_ARGUMENT}.</li>
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
 * once, on that method, and the evidence is in docs/adr.md Decision 75.</li>
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
 * <p>Its blind spots, stated because none of the three is small. <strong>The boundary below is
 * between what these POMs say and what Maven reads elsewhere, and until round 7 that boundary was
 * claimed rather than held</strong>: four of maven-compiler-plugin's five argument parameters were
 * unread, so one line INSIDE the very {@code <configuration>} this gate lives in dropped it with
 * every check here green ({@link #LIST_ARGUMENT_CHANNELS} carries the measurement). What holds it now
 * is the closed world described above and not a longer list of names. The POM checks read THESE POMs —
 * the ones {@link #poms} names — so anything ELSE Maven reads is invisible to them, in both
 * directions: a {@code settings.xml} profile, the command line, {@code MAVEN_OPTS}, and a committed
 * {@code .mvn/maven.config}, which Maven 3.3.1 and later read automatically. The boundary is the
 * FILE and not the repository, and this paragraph drew it at the repository until round 6:
 * {@code .mvn/maven.config} would sit alongside these POMs, be committed like them and still be
 * unread here, so three words in it drop the gate exactly as three words in a {@code <properties>}
 * once did. A {@code -Dmaven.compiler.failOnError=false} arriving by any of those routes is
 * included; its in-POM form IS read ({@link #compilerUserPropertyOverrides}). A METHOD BODY is
 * unreadable to doclint at any configuration and so to every check here — a local declaration, a
 * member of a local class, a member of an anonymous class declared inside a method; the repository
 * carries no {@code @link} in any of them today and nothing detects one arriving, since
 * {@link #noJavadocBlockIsOrphaned} is about attachment rather than scope. An anonymous class in a
 * FIELD initialiser is read, which is what an earlier version of this paragraph got wrong. And nothing
 * here, nor anything #262 proposed, can tell a pointer that resolves from the pointer the sentence
 * meant: one retargeted to a member that exists but is the wrong one stays silent.
 */
public class JavadocReferenceGuardTest {

	private static final Path REPO_ROOT = ModuleSourceRoot.repoRoot();

	/**
	 * The doclint group this change is about, and only this one. Used as the argument
	 * {@link #everyJavadocReferenceInTheApiModuleResolves} passes itself, and to NAME the check in
	 * failure messages — never to recognise it in a POM. Nothing here matches an argument as a
	 * string: what a declared argument list does is asked of the compiler, for
	 * {@link #packagesLeftUnchecked}'s reason.
	 */
	private static final String REFERENCE_CHECK = "-Xdoclint:reference";

	/**
	 * The module this suite runs in, whose two source roots
	 * {@link #everyJavadocReferenceInTheApiModuleResolves} compiles. A name and not a list, because
	 * {@link #apiRoots} derives the roots from the reactor: what is api-specific here is the CLASSPATH
	 * this suite runs on, not which directories that module keeps its sources in.
	 */
	private static final String API_MODULE = "api";

	/**
	 * The one directory carrying a {@code pom.xml} that {@link #poms} deliberately leaves out. It is a
	 * {@code <parent>} child the root pom's {@code <modules>} does not declare, so it sits outside the
	 * reactor {@code mvn install} drives, and it carries no java source — so its compiler
	 * configuration gates nothing this guard is about. A literal rather than a rule ("has no java
	 * source", say) because the exemption is a JUDGEMENT recorded in docs/adr.md Decision 75: were it
	 * promoted to a real module its sources WOULD fall behind the gate, and this line is where that
	 * has to be said again.
	 */
	private static final String NON_REACTOR_POM_DIRECTORY = "llama-server-natives";

	private static final String COMPILER_PLUGIN = "maven-compiler-plugin";

	/**
	 * The earliest maven-compiler-plugin release declaring a {@code compilerArgs} parameter, and so
	 * the earliest one that can honour the argument list the root pom manages. Read out of the
	 * descriptors in the local repository rather than from release notes: 2.5.1's
	 * {@code META-INF/maven/plugin.xml} declares no parameter of that name at all, and 3.1's declares
	 * it on both the {@code compile} and the {@code testCompile} mojo.
	 *
	 * <p>It is a floor rather than a name because Maven IGNORES an unknown plugin parameter in
	 * silence — no warning, no diagnostic — so a POM pinning an older version keeps the
	 * {@code <compilerArgs>} element, keeps every check here green and hands javac nothing. Measured
	 * on this branch: {@code <version>2.5.1</version>} added to that same entry, a dead pointer
	 * planted in {@code omod/src/main/java}, {@code mvn -o clean install} exit 0 with not one
	 * {@code reference not found} printed and this suite running seven checks with no failures.
	 *
	 * <p>The floor is the whole of what a POM reader can say here. Whether the version actually
	 * PASSES the managed arguments to javac is a fact about Maven's execution and not about these
	 * files; {@link #theArgumentsTheBuildDeclaresRefuseADeadJavadocReference} asks javac what the
	 * arguments DO, which is a different question and does not cover this one.
	 */
	private static final int[] MINIMUM_COMPILER_PLUGIN_VERSION = { 3, 1 };

	/**
	 * Every maven-compiler-plugin parameter that can put an argument on javac's command line, read
	 * together as ONE list per configuration position. Names taken from the descriptor of the version
	 * this build resolves, 3.13.0: {@code compilerArgs} (a {@code List} of {@code <arg>}),
	 * {@code compilerArgument} (a {@code String}), {@code compilerArguments} (a deprecated
	 * {@code Map}), and on {@code testCompile} the two {@code testCompiler*} counterparts of the last
	 * two. None of the five is bound to a user property, so a POM element is the only position any of
	 * them can be set from — which is what lets {@link #unreadCompilerParametersAt} close the world
	 * over these files.
	 *
	 * <p><strong>Reading one of them is not reading the channel.</strong> Until this was written the
	 * guard read {@code compilerArgs} alone, and {@code compilerArgument} does not REPLACE it — the
	 * plugin APPENDS it — so one extra line inside the very {@code <configuration>} this gate lives in
	 * handed javac an argument nothing here looked at. Measured: with
	 * {@code <compilerArgument>-Xdoclint/package:-org.openmrs.*</compilerArgument>} added after
	 * {@code </compilerArgs>}, a dead pointer in {@code omod/src/main/java} compiled, the build
	 * reported success at exit 0 with no {@code reference not found} printed, and this suite ran seven
	 * checks with no failures. The {@code testCompilerArgument} form does the same to both modules'
	 * TEST roots, which is where two of the three dead references this change repaired actually lived.
	 *
	 * <p><strong>The union is deliberate and is safe in one direction only.</strong> A position's
	 * channels are composed into a single argument list and put to the compiler together, rather than
	 * modelled as the two mojos see them — {@code testCompilerArgument} REPLACES
	 * {@code compilerArgument} for {@code testCompile}, so the union is a list neither mojo receives
	 * exactly. It cannot under-report: every one of the five is honoured by at least one of the two
	 * mojos, and doclint's options accumulate rather than last-wins (measured on JDK 21:
	 * {@code -Xdoclint:reference -Xdoclint:none} still reports the dead reference, in either order,
	 * while {@code -Xdoclint/package:-org.openmrs.*} silences it from either position), so a silencer
	 * anywhere in the union really does silence the mojo whose channel carries it. What it can do is
	 * over-report, on a POM that silences the check for one mojo and not the other — which is a POM
	 * this guard exists to refuse anyway.
	 */
	private static final List<String> LIST_ARGUMENT_CHANNELS = Arrays.asList("compilerArgs");

	/** See {@link #LIST_ARGUMENT_CHANNELS}. Each is one whole javac argument, as the plugin adds it. */
	private static final List<String> STRING_ARGUMENT_CHANNELS =
			Arrays.asList("compilerArgument", "testCompilerArgument");

	/**
	 * See {@link #LIST_ARGUMENT_CHANNELS}. Each entry becomes {@code -<key>} followed by its value,
	 * or {@code -A<key>=<value>} where the key names an annotation-processor option, which is how the
	 * plugin renders the map.
	 */
	private static final List<String> MAP_ARGUMENT_CHANNELS =
			Arrays.asList("compilerArguments", "testCompilerArguments");

	/**
	 * The maven-compiler-plugin parameters this guard knows how to READ — the five argument channels
	 * above, plus the two it refuses outright ({@code failOnError}, {@code compilerId}).
	 * {@link #unreadCompilerParametersAt} refuses anything else, so this list and the inert one below
	 * are together a closed world over the compiler configuration in these POMs.
	 */
	private static final List<String> INTERPRETED_COMPILER_PARAMETERS = interpretedCompilerParameters();

	private static List<String> interpretedCompilerParameters() {
		List<String> names = new ArrayList<String>();
		names.addAll(LIST_ARGUMENT_CHANNELS);
		names.addAll(STRING_ARGUMENT_CHANNELS);
		names.addAll(MAP_ARGUMENT_CHANNELS);
		names.add("failOnError");
		names.add("compilerId");
		return names;
	}

	/**
	 * The maven-compiler-plugin parameters these POMs set that can carry no javac argument and select
	 * no compiler backend, so that a configuration made of them alone cannot drop the gate. Each entry
	 * is a JUDGEMENT about one parameter and not a category: {@code source}, {@code target} and
	 * {@code release} take a version, {@code encoding} a charset, and none of the four is a list.
	 *
	 * <p>Deliberately the four these POMs actually use and no more. Adding a fifth parameter to a
	 * compiler {@code <configuration>} here is meant to redden {@link #unreadCompilerParametersAt}
	 * until someone says which of the two lists it belongs in — that refusal IS the fix for this
	 * family of defect, and a generous list is how the family stays alive. Rounds 5, 6 and 7 of this
	 * change's review each found a further named way to silence the gate — the
	 * {@code maven.compiler.failOnError} property, the {@code -Xdoclint/package} qualifier, and four
	 * sibling argument parameters — and each was fixed by reading one more name; a closed world is what makes the next one visible
	 * without knowing its name in advance. {@code fork} plus {@code executable} is the shape that
	 * makes this more than tidiness: neither is an argument channel, and together they hand the whole
	 * compilation to a binary of the POM's choosing.
	 */
	private static final List<String> PARAMETERS_CARRYING_NO_JAVAC_ARGUMENT =
			Arrays.asList("source", "target", "release", "encoding");

	/**
	 * The user property maven-compiler-plugin binds its {@code failOnError} parameter to, which is a
	 * second position the parameter can be set from and one that needs no plugin element at all.
	 * Read because three words in the root pom's own {@code <properties>} dropped the gate
	 * ENTIRELY while every check here stayed green: measured on this branch, JDK 21, with a dead
	 * pointer planted in {@code omod/src/main/java} — {@code mvn -o clean install} printed
	 * {@code [ERROR] ... reference not found} and then reported BUILD SUCCESS, exit 0, and
	 * {@code JavadocReferenceGuardTest} ran 7 checks with 0 failures. That is #262's headline defect
	 * reinstated, identical in effect to the {@code <failOnError>false</failOnError>} element
	 * {@link #disabledFailOnErrorAt} already refuses and reachable by an edit that touches no plugin
	 * block. NOT one of the blind spots this class discloses: those are about settings arriving from
	 * somewhere OTHER THAN these POMs — a {@code settings.xml} profile, the command line,
	 * {@code MAVEN_OPTS}, a committed {@code .mvn/maven.config} — while this one is set in a POM
	 * {@link #poms} names and this reader parses. The line is the FILE and not the repository, which
	 * is what an earlier wording of it got wrong. <strong>Read that exclusion as a claim about what
	 * this guard now enforces and not as one it has always earned</strong>: round 7 measured four
	 * in-POM argument parameters it did not read, so the sentence above was false when it was written.
	 * {@link #unreadCompilerParametersAt} is what makes it true, by refusing an in-POM compiler
	 * parameter this guard cannot account for rather than assuming the list of names is complete.
	 *
	 * <p>Taken from the plugin's own descriptor rather than guessed from the parameter name: the
	 * version this build resolves, 3.13.0, declares the {@code failOnError} parameter as a boolean
	 * defaulting to true and expressed by this property, on BOTH the {@code compile} and
	 * {@code testCompile} mojos, and 3.15.0 declares it identically. No plugin VERSION is read
	 * anywhere here, and none needs to be — a POM that pins a version whose descriptor binds neither
	 * property would make this reader silent rather than wrong.
	 */
	private static final String FAIL_ON_ERROR_PROPERTY = "maven.compiler.failOnError";

	/**
	 * The user property the same plugin binds {@code compilerId} to, declared on both mojos beside
	 * the one above. The weaker half of the pair — set to a backend with no plexus-compiler
	 * implementation on the classpath the build fails loudly rather than silently — and reported
	 * anyway, because "it fails loudly today" is a claim about the plugin's dependencies and not
	 * about this gate.
	 */
	private static final String COMPILER_ID_PROPERTY = "maven.compiler.compilerId";

	/**
	 * The two user properties that together hand the whole compilation to a binary of the POM's
	 * choosing: {@code fork} true makes the plugin run an external compiler, and {@code executable}
	 * says which. The element form of either is refused by {@link #unreadCompilerParametersAt} as a
	 * parameter this guard does not interpret; these are the SAME question at the other position
	 * Maven answers it from, asked here so the element and the property do not disagree — which is
	 * the disagreement {@link #FAIL_ON_ERROR_PROPERTY} records for its own parameter.
	 *
	 * <p>Refused only TOGETHER, because {@code executable} alone is inert: the plugin reads it only
	 * when forking, so refusing it on its own would redden a POM that compiles exactly as this one
	 * does. Measured on this branch, JDK 21, plugin 3.13.0: both set in the root pom's own
	 * {@code <properties>}, {@code mvn -o -pl api clean compile} failed with
	 * {@code Error while executing the external compiler /no/such/javac} — so the pair really does
	 * decide which binary sees {@code -Xdoclint:reference}, from three lines that name no plugin.
	 */
	private static final String FORK_PROPERTY = "maven.compiler.fork";

	/** The other half of the pair. See {@link #FORK_PROPERTY}. */
	private static final String EXECUTABLE_PROPERTY = "maven.compiler.executable";

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
	 * <p>Matched after the text is lowercased AND after any run of {@code u} following a backslash is
	 * collapsed to one, because the JLS UnicodeMarker is {@code u} followed by any number of further
	 * {@code u}s. The multi-{@code u} spelling is the SAME escape and javac reads it as one —
	 * measured: a javadoc block opened with it produces the reference error — while a literal search
	 * for the single-{@code u} spelling matched neither, so such a file was silently lexed as ordinary
	 * text instead of being refused. Until round 6 the sentence above was false of it.
	 *
	 * <p>Assembled from pieces rather than written out, because THIS file is inside the corpus the
	 * scan walks: spelled as literals, the constant makes its own source unscannable and the check
	 * refuses the guard that owns it. Which is at least a demonstration that it works.
	 */
	private static final List<String> COMMENT_DELIMITER_ESCAPES = Arrays.asList("\\" + "u002a",
			"\\" + "u002f");

	/**
	 * The probe every arguments-refuse-it check compiles, written into EVERY package
	 * {@link #corpusPackages} finds. {@code -Xdoclint} has TWO qualifiers and a probe answers only for
	 * what it is itself qualified by, so both of them decide where this source has to live.
	 *
	 * <p><strong>The ACCESS qualifier.</strong> Its dead pointer sits on a {@code private} member
	 * deliberately, and that is load-bearing rather than incidental:
	 * {@code -Xdoclint:reference/public} silences the check for everything but public API. On a public
	 * probe that argument satisfied every check that reads a declared argument list, while dropping the
	 * gate for most of the corpus — this module's design record lives overwhelmingly on non-public
	 * members.
	 *
	 * <p><strong>The PACKAGE qualifier.</strong> javac takes a separate
	 * {@code -Xdoclint/package:[-]<packages>} option, which leaves {@code -Xdoclint:reference} in the
	 * list and silences the group for the packages it names. In the unnamed package this probe answered
	 * for a corpus it was not in: measured on JDK 21, an unnamed-package probe still reports
	 * {@code reference not found} under {@code -Xdoclint/package:-org.openmrs.*}, so one added
	 * {@code <arg>} dropped the gate for both reactor modules at compile time with every check here
	 * green. One package is not enough either, and the reason is the option's own grammar: a trailing
	 * {@code .*} expands to the SUB-packages of the package named and NOT to that package itself
	 * (measured, same JDK), so a probe in {@code org.openmrs.module.chartsearchai} alone stays checked
	 * under {@code -Xdoclint/package:-org.openmrs.module.chartsearchai.*} — which silences every
	 * other package this build compiles. One probe per package needs no judgement about which spelling
	 * a maintainer reaches for, and {@link #theArgumentsTheBuildDeclaresRefuseADeadJavadocReference}
	 * pins that placement rather than leaving it to this paragraph.
	 *
	 * <p>The counterpart live probe is public and is written into the same packages, so between them
	 * the pair also refuses an argument list that cannot compile anything.
	 */
	private static final String DEAD_REFERENCE_CLASS = "DeadReference";

	private static final String DEAD_REFERENCE_BODY =
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

	private static final String LIVE_REFERENCE_CLASS = "LiveReference";

	private static final String LIVE_REFERENCE_BODY =
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
	 * Every {@code <module>} one POM declares, wherever the declaring {@code <modules>} sits — the
	 * project's own or a {@code <profile>}'s. The wrapper comes from
	 * {@link #declaredUnderProjectOrProfile} and the {@code <module>} is then read as a DIRECT CHILD
	 * of it, which is exactly the reach the profile case needs and no more: it was read as a direct
	 * child of {@code <project>} alone, so a module Maven builds under {@code -P} was outside
	 * {@link #reactorSourceRoots}, outside {@link #poms} and so outside both corpus walks and every
	 * POM check, silently — the same shape as the hand-written root list {@link #SOURCE_ROOTS} warns
	 * about, arrived at by moving the declaration instead of deleting a line.
	 *
	 * <p>NEITHER element is read document-wide, for {@link #customSourceDirectoriesIn}' reason and
	 * with the same consequence: a document-wide read of either turns a legal POM into a red build
	 * with a message naming a "module" that was never one, from two checks at once and with no hint
	 * that a plugin parameter caused it. {@code <module>} is a real plugin parameter — moditect's
	 * {@code add-module-info} takes {@code <configuration><module><moduleInfoSource>} — and keying on
	 * THAT element's parent alone was half the fix, because the WRAPPER name is reported to be a
	 * parameter of the same goal: {@code <configuration><modules>}, a list of those configurations for
	 * artifacts other than the project's own. That much is a report and not a reading of the plugin's
	 * descriptor, which this guard has not opened; what was MEASURED is the consequence — a
	 * never-activated {@code <profile>} carrying that plural form reddened
	 * {@link #theCorpusCoversEveryModuleTheBuildCompiles} and
	 * {@link #noOtherCompilerConfigurationDropsTheCheck} on a POM Maven builds without complaint,
	 * naming the {@code <moduleInfoSource>} text as a module. So what puts both shapes out of reach is
	 * the WRAPPER's parent and not the two names being spelled differently. Both directions are
	 * pinned by {@link #theCorpusCoversEveryModuleTheBuildCompiles}.
	 */
	private static List<String> modulesIn(Element pom) {
		List<String> modules = new ArrayList<String>();
		for (Element wrapper : declaredUnderProjectOrProfile(pom, "modules")) {
			for (Element declared : directChildren(wrapper, "module")) {
				String name = declared.getTextContent().trim();
				if (!name.isEmpty() && !modules.contains(name)) {
					modules.add(name);
				}
			}
		}
		return modules;
	}

	/**
	 * The elements of one name that a POM declares directly under its own {@code <project>} or under
	 * one of its {@code <profile>}s — never a plugin parameter that happens to share the name. Both
	 * callers need exactly that reach and for the same reason: {@code <modules>} and
	 * {@code <properties>} are legal in those two positions and nowhere else in the POM model, while
	 * a plugin's own {@code <configuration>} may carry an element of either name for its own purposes
	 * — maven-surefire-plugin's descriptor declares a {@code <properties>} parameter of type
	 * {@code java.util.Properties} for configuring its provider, and {@link #modulesIn} carries the
	 * measured case for {@code <modules>}. Reading either document-wide reddens a legal POM, which is
	 * the one failure direction {@link #customSourceDirectoriesIn} refused for its own element and
	 * the one this refuses for both of these.
	 */
	private static List<Element> declaredUnderProjectOrProfile(Element pom, String name) {
		List<Element> declared = new ArrayList<Element>();
		NodeList all = pom.getElementsByTagName(name);
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
	 *
	 * <p>Its coverage is then asked of the FILESYSTEM and not of the loop above, for
	 * {@link #apiRoots}' reason and with the same failure direction: narrowing this list is invisible
	 * on a clean tree, because every check reading a POM reads only the ones it hands out — so
	 * deleting the module loop leaves the whole suite green while a child {@code <compilerArgs>}
	 * override, which REPLACES the managed argument list, drops the gate for a whole module. Mutate
	 * the loop and read the failures. {@link #NON_REACTOR_POM_DIRECTORY} is the one exemption and
	 * carries its own reason.
	 *
	 * <p>What the cross-check does NOT reach is a POM nested deeper than one level, which
	 * {@link #directoriesCarryingAPom} does not look for: a declared {@code <module>} with a path in
	 * it is covered by the loop above either way, but an UNDECLARED one under a subdirectory is
	 * invisible here. That is a limit of this check and not a claim about the repository, which
	 * carries no such POM.
	 */
	private static List<String> poms() throws Exception {
		List<String> poms = new ArrayList<String>();
		poms.add("pom.xml");
		for (String module : reactorModules()) {
			poms.add(module + "/pom.xml");
		}
		for (String directory : directoriesCarryingAPom()) {
			if (!poms.contains(directory + "/pom.xml") && !NON_REACTOR_POM_DIRECTORY.equals(directory)) {
				fail(directory + "/pom.xml exists under " + REPO_ROOT + " and is in neither the root pom's\n"
						+ "<modules> nor this guard's one recorded exclusion (" + NON_REACTOR_POM_DIRECTORY
						+ "), so noOtherCompilerConfigurationDropsTheCheck would never read its\n"
						+ "<compilerArgs> — and a child override REPLACES the managed argument list, dropping\n"
						+ "the javadoc-reference gate for that module with the whole suite green. Declare it as a\n"
						+ "module, or record here why its compiler configuration gates nothing.");
			}
		}
		return poms;
	}

	/**
	 * Every directory one level under the repository root that carries a {@code pom.xml}, which is
	 * where every module of this reactor keeps one. Read off the filesystem so that {@link #poms}'
	 * coverage is asked of something the root pom cannot narrow, and sorted so that which directory a
	 * failure names does not depend on the order the filesystem hands them back.
	 */
	private static List<String> directoriesCarryingAPom() throws IOException {
		List<String> directories = new ArrayList<String>();
		DirectoryStream<Path> children = Files.newDirectoryStream(REPO_ROOT);
		try {
			for (Path child : children) {
				if (Files.isRegularFile(child.resolve("pom.xml"))) {
					directories.add(child.getFileName().toString());
				}
			}
		}
		finally {
			children.close();
		}
		Collections.sort(directories);
		return directories;
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
				+ "\nThe javadoc is this module's design record (docs/adr.md, Decision 75), so a pointer\n"
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
					for (String where : javacArgumentBlocks(plugin).keySet()) {
						elsewhere.add(pom + " " + where);
					}
				}
			}
			fail("The root pom's <build>/<pluginManagement> entry for " + COMPILER_PLUGIN + " declares no\n"
					+ "plugin-level javac arguments. That is the one position api and omod both inherit, and\n"
					+ "the one both compile and testCompile receive.\n\n"
					+ (elsewhere.isEmpty() ? "  No argument channel was found anywhere in " + poms + ".\n"
							: "  Javac arguments were found instead at:\n" + join(elsewhere))
					+ "\nMeasured: declared in api/pom.xml alone, a dead javadoc reference in omod/src/main/java\n"
					+ "compiles and the whole build reports success — issue #262. See docs/adr.md, Decision 75.");
		}

		List<Integer> pinned = rootManagedCompilerPluginVersion();
		assertTrue(!pinned.isEmpty(),
				"The root pom's <build>/<pluginManagement> entry for " + COMPILER_PLUGIN + " pins no\n"
						+ "<version>, so the version that has to honour its <compilerArgs> is whatever Maven's\n"
						+ "super-POM supplies and moves with the developer's Maven install. Maven ignores an\n"
						+ "unknown plugin parameter in SILENCE, so a version predating that parameter leaves this\n"
						+ "whole gate off with no diagnostic and every check here green — see\n"
						+ "MINIMUM_COMPILER_PLUGIN_VERSION for the measurement.");
		assertTrue(atLeast(pinned, MINIMUM_COMPILER_PLUGIN_VERSION),
				"The root pom pins " + COMPILER_PLUGIN + " " + pinned + ", which is older than "
						+ MINIMUM_COMPILER_PLUGIN_VERSION[0] + "." + MINIMUM_COMPILER_PLUGIN_VERSION[1]
						+ " — the earliest release declaring a compilerArgs parameter at all. Maven ignores an\n"
						+ "unknown parameter in silence, so the managed <compilerArgs> below reaches javac in no\n"
						+ "form. See MINIMUM_COMPILER_PLUGIN_VERSION.");

		List<String> unchecked = packagesLeftUnchecked("the root pom's managed javac arguments", managed);
		// Still a Supplier, though the message no longer recompiles anything: the packages are listed
		// only where the assertion fails.
		assertTrue(unchecked.isEmpty(),
				() -> "the arguments the root pom manages " + managed + " do not make a dead javadoc "
						+ "reference on a non-public member an error in " + unchecked.size() + " of the "
						+ "package(s) this build compiles, so a dead pointer is silent in them — issue #262: "
						+ unchecked);

		// And the probes themselves are held to the corpus, because nothing in this repository's POMs
		// exercises the package qualifier: with the probes in the unnamed package, or in one package,
		// each of these argument lists silenced the module and left this check green. Derived from the
		// corpus rather than spelled, so a package rename does not redden a correct guard.
		List<String> packages = corpusPackages();
		assertSilencedPackagesAreSeen(packages.get(0));
		assertSilencedPackagesAreSeen(packages.get(packages.size() - 1));
		String withASubPackage = null;
		for (String parent : packages) {
			for (String child : packages) {
				if (child.startsWith(parent + ".")) {
					withASubPackage = parent;
					break;
				}
			}
			if (withASubPackage != null) {
				break;
			}
		}
		assertTrue(withASubPackage != null,
				"no package of this corpus " + packages + " contains another, so the trailing-.* spelling of "
						+ "-Xdoclint/package cannot be pinned against it. That spelling is the one a maintainer "
						+ "silencing this module reaches for, so say here how it is covered instead of dropping "
						+ "the pin.");
		assertSilencedPackagesAreSeen(withASubPackage + ".*");
	}

	/**
	 * One synthetic plugin element carrying one {@code <configuration>}, for the pins that ask this
	 * guard's readers about shapes these POMs do not contain.
	 */
	private static String namedPlugin(String configuration) {
		return "<plugin><artifactId>" + COMPILER_PLUGIN + "</artifactId><configuration>" + configuration
				+ "</configuration></plugin>";
	}

	/**
	 * Every argument channel {@link #LIST_ARGUMENT_CHANNELS} names that this guard is not seen to read,
	 * described. Synthetic, because these POMs use exactly one of the five — so deleting any of the
	 * other four from {@link #javacArguments} leaves the whole suite green while an edit inside the very
	 * {@code <configuration>} the gate lives in hands javac an argument nothing looks at. That is not a
	 * hypothetical: it is what round 7 of this change's review measured, twice.
	 *
	 * <p><strong>Three of them are pinned end to end and two at the reader.</strong> A channel that can
	 * carry the {@code -Xdoclint/package} qualifier is pinned by putting {@link #REFERENCE_CHECK} in
	 * {@code <compilerArgs>} and the qualifier in the channel under test, then asking the real compiler:
	 * read, the composed list leaves a package unchecked; unread, it does not, and the pin reddens. The
	 * two {@code Map} channels cannot carry that qualifier at all — an XML element name may not contain
	 * {@code /}, and the map's key IS an element name — so there is no end-to-end silencer to plant, and
	 * inventing one that javac rejects would pin the live probe's failure rather than the reader. They
	 * are pinned on the rendering instead, which is the whole of what this guard does with them.
	 */
	private static List<String> unreadArgumentChannels() throws Exception {
		List<String> violations = new ArrayList<String>();
		String silencer = "-Xdoclint/package:-" + corpusPackages().get(0);
		for (String channel : LIST_ARGUMENT_CHANNELS) {
			violations.addAll(channelIsSeenToSilence(channel, "<" + channel + "><arg>" + REFERENCE_CHECK
					+ "</arg><arg>" + silencer + "</arg></" + channel + ">"));
		}
		for (String channel : STRING_ARGUMENT_CHANNELS) {
			violations.addAll(channelIsSeenToSilence(channel, "<compilerArgs><arg>" + REFERENCE_CHECK
					+ "</arg></compilerArgs><" + channel + ">" + silencer + "</" + channel + ">"));
		}
		for (String channel : MAP_ARGUMENT_CHANNELS) {
			List<String> read = javacArguments(directChild(
					parseXml(namedPlugin("<" + channel + "><Xlint>none</Xlint><Averbose>true</Averbose></"
							+ channel + ">")),
					"configuration"));
			if (!read.equals(Arrays.asList("-Xlint", "none", "-Averbose=true"))) {
				violations.add("the deprecated <" + channel + "> map is not rendered onto javac's command "
						+ "line the way " + COMPILER_PLUGIN + " renders it (it read " + read + ", expected the "
						+ "entry name as a flag with its text as the following argument, and an annotation-"
						+ "processor option joined with =). A channel read as empty is a channel nothing here "
						+ "would notice being used");
			}
		}
		return violations;
	}

	/**
	 * One argument channel, put to the real compiler through {@link #javacArgumentBlocks} — the
	 * production reader — rather than asked about as a string. Empty where the channel is seen to carry
	 * a silencer into the composed list.
	 */
	private static List<String> channelIsSeenToSilence(String channel, String configuration)
			throws Exception {
		List<String> violations = new ArrayList<String>();
		Map<String, List<String>> blocks = javacArgumentBlocks(parseXml(namedPlugin(configuration)));
		List<String> composed = blocks.get("plugin-level <configuration>");
		if (composed == null) {
			violations.add("<" + channel + "> contributes nothing to the javac arguments this guard reads "
					+ "out of a <configuration> (it read " + blocks + "), so an argument set through it is "
					+ "invisible here — issue #262, reinstated by one line inside the gate's own element");
			return violations;
		}
		if (packagesLeftUnchecked("the <" + channel + "> pin", composed).isEmpty()) {
			violations.add("<" + channel + "> carrying " + composed + " was seen to leave no package of this "
					+ "corpus unchecked, so this guard does not read what that channel hands javac");
		}
		return violations;
	}

	/**
	 * One package-qualifier pin: the reference check plus {@code -Xdoclint/package:-<silenced>} must be
	 * SEEN to leave part of this corpus unchecked, {@code silenced} naming either one package of it or
	 * one package's sub-packages.
	 *
	 * <p>"At least one package" and not an exact set, because what is pinned is that the probes REACH
	 * the packages an argument silences. Which packages a given spelling covers is javac's rule to
	 * define and this guard's to observe, and a guard restating it would go green on the reading it
	 * restated rather than on the one the compiler applies — which is how the unnamed-package probe
	 * survived five review rounds.
	 */
	private static void assertSilencedPackagesAreSeen(String silenced) throws Exception {
		List<String> arguments = Arrays.asList(REFERENCE_CHECK, "-Xdoclint/package:-" + silenced);
		List<String> unchecked = packagesLeftUnchecked("the package-qualifier pin", arguments);
		assertTrue(!unchecked.isEmpty(),
				"an argument list silencing " + silenced + " " + arguments + " was seen to leave no package "
						+ "of this corpus unchecked, so the probes no longer sit where this build's sources do. "
						+ "One added <arg> then drops the gate for the whole repository with every check here "
						+ "green — issue #262. See DEAD_REFERENCE_BODY.");
	}

	/**
	 * No compiler configuration anywhere in these POMs drops the check. Maven does not merge a
	 * child's {@code <compilerArgs>} with the managed one — it REPLACES it — and an
	 * execution-scoped {@code <configuration>} replaces it for that execution, so either is a way to
	 * lose the check while the root pom still appears to declare it. Plugin elements are collected
	 * document-wide, so a declaration moved into a {@code <profile>} is read here too.
	 *
	 * <p>Each block is put to the real compiler rather than matched as a string, for
	 * {@link #packagesLeftUnchecked}'s reason: a widened doclint list is a correct configuration and
	 * a prefix match calls it a removal.
	 *
	 * <p><strong>A block is a POSITION and not a parameter.</strong> maven-compiler-plugin has FIVE
	 * parameters that put arguments on javac's command line and {@link #javacArgumentBlocks} composes
	 * all of them per position, because four of them APPEND to the managed list rather than replacing
	 * it — see {@link #LIST_ARGUMENT_CHANNELS} for what reading one of them alone cost.
	 *
	 * <p><strong>And the world over those positions is CLOSED.</strong>
	 * {@link #unreadCompilerParametersAt} refuses any compiler parameter these POMs set that is
	 * neither read nor judged inert, so a parameter nobody here has heard of — the next argument
	 * channel, a future {@code <compilerArgs>} successor, {@code <fork>} plus {@code <executable>} —
	 * reddens on the edit that introduces it instead of after someone thinks to add its name. The
	 * name-by-name fix is what this check kept receiving and it is not what closes this family: three
	 * rounds of review each named one more way to silence the gate, each fix read one more name, and
	 * each time the next name was still reachable.
	 *
	 * <p>{@code <failOnError>} and {@code <compilerId>} are asked about for the same reason and not
	 * as second subjects. Turning the first off leaves every doclint error printed and none of them
	 * fatal, which is the green build #262 reports. The second selects the compiler backend, and
	 * {@code -Xdoclint} is a javac option — so anything but {@code javac} is refused here rather
	 * than judged, because this guard cannot know whether another backend honours the argument. A
	 * wrapper that does honour it is refused too; say so in the same commit that changes the id.
	 *
	 * <p><strong>Each of those two is asked at BOTH positions Maven answers it from</strong> — the
	 * element under a plugin {@code <configuration>} ({@link #disabledFailOnErrorAt},
	 * {@link #nonJavacCompilerIdAt}) and the user property the plugin binds the parameter to
	 * ({@link #compilerUserPropertyOverrides}). The property form was unread, and it is not a smaller
	 * hole than the element: this whole check hung off {@link #compilerPlugins}, so three words in the
	 * root pom's own {@code <properties>} dropped the gate with all seven checks green — see
	 * {@link #FAIL_ON_ERROR_PROPERTY} for the measurement. The property pins are synthetic because
	 * this repository sets neither, which is exactly why the omission was invisible — and they pin the
	 * READER and not the call site: with no POM here setting either property, deleting the loop that
	 * asks about them leaves this check green, exactly as deleting either element-form loop beside it
	 * does. There is nothing to cross-check that against, the way {@link #poms} cross-checks its list
	 * against the filesystem, so it is stated instead.
	 */
	@Test
	public void noOtherCompilerConfigurationDropsTheCheck() throws Exception {
		List<String> violations = new ArrayList<String>();
		for (String pom : poms()) {
			for (String where : compilerUserPropertyOverrides(pomRoot(pom))) {
				violations.add(pom + " " + where);
			}
			for (Element plugin : compilerPlugins(pom)) {
				for (Map.Entry<String, List<String>> block : javacArgumentBlocks(plugin).entrySet()) {
					List<String> unchecked = packagesLeftUnchecked(pom + " " + block.getKey(),
							block.getValue());
					if (!unchecked.isEmpty()) {
						violations.add(pom + " " + block.getKey() + " declares javac arguments "
								+ block.getValue()
								+ ", which the compiler does not refuse a dead javadoc reference under in "
								+ unchecked + " — a <compilerArgs> block REPLACES the managed one and the four "
								+ "other argument parameters APPEND to it, so either way this drops the check");
					}
				}
				for (String where : unreadCompilerParametersAt(plugin)) {
					violations.add(pom + " " + where + ", which is a " + COMPILER_PLUGIN + " parameter this "
							+ "guard neither reads as an argument channel nor has judged unable to carry a javac "
							+ "argument. Refused rather than assumed harmless: reading a fixed list of parameter "
							+ "NAMES is what left compilerArgument, compilerArguments, testCompilerArgument and "
							+ "testCompilerArguments unread while the gate looked green. Say which it is — add it "
							+ "to PARAMETERS_CARRYING_NO_JAVAC_ARGUMENT, or teach javacArguments to read it");
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
		List<String> asProperties = compilerUserPropertyOverrides(parseXml("<project><properties><"
				+ FAIL_ON_ERROR_PROPERTY + ">false</" + FAIL_ON_ERROR_PROPERTY + "></properties>"
				+ "<profiles><profile><properties><" + COMPILER_ID_PROPERTY + ">eclipse</"
				+ COMPILER_ID_PROPERTY + "></properties></profile></profiles></project>"));
		String bothProperties = join(asProperties);
		if (asProperties.size() != 2 || !bothProperties.contains(FAIL_ON_ERROR_PROPERTY)
				|| !bothProperties.contains(COMPILER_ID_PROPERTY)) {
			violations.add("the two user properties maven-compiler-plugin binds failOnError and compilerId "
					+ "to are not both read out of a POM's <properties> — the project's own and a <profile>'s, "
					+ "and each named in what it reports "
					+ "(it read " + asProperties + "). Measured on this branch: three words in the root pom's "
					+ "own <properties> left a doclint reference error printed, the build reporting BUILD "
					+ "SUCCESS and all seven checks here green, which is #262's headline defect reinstated by "
					+ "an edit that touches no plugin block. This repository sets neither property, so only "
					+ "this synthetic POM can say so");
		}
		violations.addAll(unreadArgumentChannels());
		String unreadAtBothPositions = "<plugin><configuration>"
				+ "<aParameterNoOneHasHeardOf>x</aParameterNoOneHasHeardOf></configuration>"
				+ "<executions><execution><id>e</id><configuration><fork>true</fork></configuration>"
				+ "</execution></executions></plugin>";
		String unreadParameters = join(unreadCompilerParametersAt(parseXml(unreadAtBothPositions)));
		if (!unreadParameters.contains("aParameterNoOneHasHeardOf") || !unreadParameters.contains("<fork>")) {
			violations.add("a " + COMPILER_PLUGIN + " parameter this guard does not interpret is not refused "
					+ "at both configuration positions (it read " + unreadParameters.trim() + "). Closing the "
					+ "world over these configurations is what makes the NEXT unread argument channel visible "
					+ "without knowing its name, and <fork> in a named execution is there because an execution's "
					+ "own <configuration> replaces the managed one for that execution");
		}
		List<String> interpretedParameters = unreadCompilerParametersAt(parseXml(namedPlugin(
				"<compilerArgs><arg>" + REFERENCE_CHECK + "</arg></compilerArgs><source>11</source>"
						+ "<compilerArgument>-nowarn</compilerArgument><compilerId>javac</compilerId>")));
		if (!interpretedParameters.isEmpty()) {
			violations.add("parameters this guard DOES read are refused as unknown (it read "
					+ interpretedParameters + "), so a correct configuration reddens — the one failure "
					+ "direction this class refuses");
		}
		List<String> forkedToAnotherBinary = compilerUserPropertyOverrides(parseXml("<project><properties><"
				+ FORK_PROPERTY + ">true</" + FORK_PROPERTY + "><" + EXECUTABLE_PROPERTY + ">/no/such/javac</"
				+ EXECUTABLE_PROPERTY + "></properties></project>"));
		if (forkedToAnotherBinary.size() != 1) {
			violations.add("a POM handing the whole compilation to another binary through <" + FORK_PROPERTY
					+ "> and <" + EXECUTABLE_PROPERTY + "> is not reported (it read " + forkedToAnotherBinary
					+ "). The ELEMENT form of either is refused by unreadCompilerParametersAt, so leaving the "
					+ "property form unread would make the two positions disagree — which is the disagreement "
					+ "FAIL_ON_ERROR_PROPERTY records for its own parameter");
		}
		List<String> executableWithoutForking = compilerUserPropertyOverrides(parseXml("<project><properties><"
				+ EXECUTABLE_PROPERTY + ">/no/such/javac</" + EXECUTABLE_PROPERTY + "></properties></project>"));
		if (!executableWithoutForking.isEmpty()) {
			violations.add("<" + EXECUTABLE_PROPERTY + "> is reported without <" + FORK_PROPERTY + "> (it read "
					+ executableWithoutForking + "), which the plugin reads only when forking — so this refuses "
					+ "a POM that compiles exactly as this one does");
		}
		List<String> asAPluginParameter = compilerUserPropertyOverrides(parseXml("<project><build>"
				+ "<plugins><plugin><configuration><properties><" + FAIL_ON_ERROR_PROPERTY + ">false</"
				+ FAIL_ON_ERROR_PROPERTY + "></properties></configuration></plugin></plugins></build>"
				+ "</project>"));
		if (!asAPluginParameter.isEmpty()) {
			violations.add("a <properties> inside a plugin's own <configuration> is read as setting the "
					+ "compiler's user property (it read " + asAPluginParameter + "), which it is not — "
					+ "maven-surefire-plugin's descriptor declares a <properties> parameter of type "
					+ "java.util.Properties for its provider configuration. Read the element as a declaration "
					+ "of the project or a <profile> and never document-wide, or this refusal reddens a clean "
					+ "build");
		}
		assertNoViolations(violations);
	}

	/**
	 * The corpus both walks take is every module MAVEN builds, and every module keeps its sources
	 * where those walks look. Two ways of losing a module from the scope without deleting anything,
	 * both silent and both fail-OPEN, which is what {@link #SOURCE_ROOTS} exists to have stopped
	 * happening by hand.
	 *
	 * <p><strong>Declared in a {@code <profile>}.</strong> {@link #modulesIn} reads a
	 * {@code <modules>} wrapper wherever the POM model allows one — under {@code <project>} or under a
	 * {@code <profile>} ({@link #declaredUnderProjectOrProfile}) — and {@code <module>} as a DIRECT
	 * CHILD of that wrapper, so a module built under {@code -P} is in the scope. Neither element was:
	 * {@code <module>} was read as a direct child of {@code <project>} alone, which put such a module
	 * outside both corpus walks and outside every POM check at once. Asked of a synthetic POM and not
	 * of this repository's, which carries no {@code <profile>} at all — so there is nothing here for a
	 * direct-child read to get wrong, and that is exactly why the narrower version was invisible.
	 * <strong>The other direction is pinned beside it, in BOTH plugin-parameter shapes</strong>: read
	 * document-wide, {@code <module>} takes
	 * in moditect's {@code <configuration><module>} parameter and the {@code <modules>} WRAPPER takes
	 * in its plural counterpart, and either reddens THIS check and
	 * {@link #noOtherCompilerConfigurationDropsTheCheck} on a legal POM — a false positive is the one
	 * failure {@link #customSourceDirectoriesIn} refused for the sibling element, so it is refused
	 * here too. Keying on {@code <module>}'s parent alone left the plural form read, which was
	 * measured on a never-activated {@code <profile>}: two checks red on a POM Maven builds without
	 * complaint. Mutate {@link #modulesIn} or {@link #declaredUnderProjectOrProfile} in either
	 * direction and read which half goes red.
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
		List<String> inAPluginParameter = modulesIn(parseXml("<project><build><plugins><plugin>"
				+ "<configuration><module><moduleInfoSource>module org.example {}</moduleInfoSource>"
				+ "</module></configuration></plugin></plugins></build></project>"));
		if (!inAPluginParameter.isEmpty()) {
			violations.add("a <module> inside a plugin's own <configuration> is read as a declared reactor "
					+ "module (it read " + inAPluginParameter + "), which it is not — moditect's "
					+ "add-module-info takes a parameter of exactly that name. Both this check and "
					+ "noOtherCompilerConfigurationDropsTheCheck would then fail a legal POM, naming a "
					+ "\"module\" that was never one and giving no hint that a plugin parameter caused it. "
					+ "Read <module> as a direct child of a <modules>, which is all the profile case needs");
		}
		List<String> inAPluralPluginParameter = modulesIn(parseXml("<project><build><plugins><plugin>"
				+ "<configuration><modules><module><artifact><artifactId>example-core</artifactId>"
				+ "</artifact><moduleInfoSource>module org.example {}</moduleInfoSource></module>"
				+ "</modules></configuration></plugin></plugins></build></project>"));
		if (!inAPluralPluginParameter.isEmpty()) {
			violations.add("a <modules> WRAPPER inside a plugin's own <configuration> is read as declaring "
					+ "reactor modules (it read " + inAPluralPluginParameter + "), which it is not — the same "
					+ "moditect goal is reported to take a plural parameter of that name for artifacts other "
					+ "than the project's own, and keying on <module>'s parent alone leaves this shape read. "
					+ "Measured: "
					+ "a never-activated <profile> carrying it reddened this check and "
					+ "noOtherCompilerConfigurationDropsTheCheck on a POM Maven builds without complaint. "
					+ "Read the WRAPPER as a declaration of the project or a <profile> too");
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
	 * <p><strong>A row of {@link #SHAPES} per residue the test has to see, on both sides of it, and one
	 * per deletable clause of {@link #annotationResidue}'s own walk.</strong>
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
	 * <p>The walk's own three clauses have rows too, and had none until round 6:
	 * {@code AnnotationNameIsFullyQualified} for the {@code .} the identifier scan admits,
	 * {@code AnnotationSpacedBeforeItsArgumentList} for the whitespace skip before the argument list
	 * and {@code TwoAnnotationsOnOneLineThenBlock} for the one after it. All three fail CONSERVATIVELY
	 * — residue is left, the arm goes quiet, a real orphan is missed and no build reddens — which is
	 * the same direction as the character exclusions this rule replaced, and is why they were
	 * deletable with every check here green.
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
	 *
	 * <p><strong>Three clauses of this walk are separately deletable and each has a row of
	 * {@link #SHAPES}</strong>, listed on {@link #isAnnotationAlone}: the {@code .} the identifier scan
	 * admits, without which a fully-qualified annotation name leaves a residue, and the whitespace
	 * skips before and after the argument list, without which {@code @Name ("x")} and
	 * {@code @Name("x") @Other} do. None of the three has an instance in this repository, so all three
	 * were deletable with every check here green.
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
	 * {@code AnnotationArgumentNestsAParen}; the three separately deletable clauses of the literal
	 * skip are {@code AnnotationArgumentQuotesACloseParen},
	 * {@code AnnotationArgumentIsACharCloseParen} and, for the backslash clause,
	 * {@code AnnotationArgumentEscapesItsQuote}; and the {@code -1}, which the caller turns into the
	 * conservative answer, is
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
		String normalised = text.toLowerCase().replaceAll("\\\\u+", "\\\\u");
		for (String escape : COMMENT_DELIMITER_ESCAPES) {
			if (normalised.contains(escape)) {
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
	 * in the same change that enabled the check. The count is recorded once, in docs/adr.md Decision 75
	 * (this change's own decision), with the tree it was measured on and the command that measured it —
	 * not copied here, because a count of sources tracks the code: the figure this sentence used to
	 * carry was wrong in both its numerator and its denominator while the ADR beside it was right.
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
		 * {@code wholeFile} is for a shape the class-body wrapper cannot express, and it is not
		 * decoration: every other row is a class BODY the harness wraps, and that wrapper made the
		 * block-after-the-closing-brace arm of {@link #unattachedJavadocBlocks} inexpressible — so that
		 * arm was deletable with the whole suite green. No count of the rows that set it is given, here
		 * or on any of them: see {@link #SHAPES}. What the wrapper cannot express is a POSITION:
		 * nothing goes above a {@code package} statement or an {@code import}, and nothing after the
		 * top-level class's own closing brace, which by construction is the last thing it writes. Unset
		 * the flag on a row of the first kind and the shape stops compiling, which this check reports
		 * as a guard that could not run.
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
		// Needs the whole file: a block after the top-level class, where nothing follows it at all —
		// the class-body wrapper has no position for that.
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
		// And the backslash clause of that same skip, which had no row: a CHAR literal that IS an
		// escaped quote. Without it the walk ends the literal on the quote the backslash escapes, opens
		// another on the real closing one, runs off the end of the line and answers -1 — so the arm
		// goes quiet on this orphan. Replacing the clause with `i += 1` left every check green before
		// this row existed.
		shape(shapes, "AnnotationArgumentEscapesItsQuote", Attachment.UNATTACHED,
				"\t@interface Sep {\n\t\tchar value();\n\t}\n\t@Sep('\\'')\n\t/** " + dead
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
		// And one row per deletable clause of annotationResidue's OWN walk, which had none of its own
		// while endOfArgumentList beside it had one apiece. Each of these is an annotation line that
		// annotates the DECLARATION below it, so javac discards a block stranded in between and the arm
		// has to fire; under the mutation named the walk leaves a residue, isAnnotationAlone answers no
		// and the arm goes quiet on a real orphan with no build reddening.
		// The dot in the identifier scan, which is the only reason a fully-qualified annotation name is
		// consumed rather than read as `.lang.Deprecated` left over.
		shape(shapes, "AnnotationNameIsFullyQualified", Attachment.UNATTACHED,
				"\t@java.lang.Deprecated\n\t/** " + dead
						+ ". */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		// The whitespace skip BEFORE the argument list: java allows a space between the annotation name
		// and its `(`, and without the skip the list itself is the residue.
		shape(shapes, "AnnotationSpacedBeforeItsArgumentList", Attachment.UNATTACHED,
				"\t@SuppressWarnings (\"unused\")\n\t/** " + dead
						+ ". */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
		// The whitespace skip AFTER it, which is the only reason a SECOND annotation on the same line is
		// consumed rather than read as a declaration sharing the line.
		shape(shapes, "TwoAnnotationsOnOneLineThenBlock", Attachment.UNATTACHED,
				"\t@SuppressWarnings(\"unused\") @Deprecated\n\t/** " + dead
						+ ". */\n\tprivate int a = 1;\n\tint r() { return a; }\n");
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

	/**
	 * One compile's diagnostics. The compiler's own boolean verdict from {@code call()} is
	 * deliberately not kept: what makes an error doclint's here is a DIFFERENCE between two runs over
	 * the same sources ({@link #everyJavadocReferenceInTheApiModuleResolves}, and
	 * {@link #theScannerAgreesWithTheCompilerAboutWhatIsAttached} for the shapes), and one boolean per
	 * run cannot carry that — a caller trusting it would call a broken classpath a javadoc defect,
	 * which is the direction the difference rule exists to refuse. So the verdict is read off
	 * {@link #errors()} instead, which is where this class decides what an error IS.
	 */
	private static final class Compilation {

		private final List<Diagnostic<? extends JavaFileObject>> diagnostics;

		private Compilation(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
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

		/**
		 * The absolute path of every source an ERROR was attributed to, which is what lets ONE compile
		 * of many probes say which of them the arguments checked. A diagnostic carrying no source is
		 * reported as {@code (no source)} rather than dropped, so that
		 * {@link ProbeRun#packagesWhereItCompiledClean} can refuse it instead of crediting or debiting
		 * a package for it.
		 */
		private Set<String> errorSources() {
			Set<String> sources = new LinkedHashSet<String>();
			for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
				if (d.getKind() == Diagnostic.Kind.ERROR) {
					JavaFileObject source = d.getSource();
					sources.add(source == null ? "(no source)"
							: new File(source.getName()).getAbsolutePath());
				}
			}
			return sources;
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
	 * Compiles one copy of a synthetic probe in every package {@link #corpusPackages} finds, with
	 * exactly the arguments given plus this suite's classpath — no {@code -source}/{@code -target}, so
	 * the running JDK's defaults apply and the only variable is the argument list the POMs declare.
	 * The synthetic sources reference nothing but {@code java.lang}; the classpath is there so that an
	 * argument needing one cannot fail the live half for an unrelated reason.
	 *
	 * <p>One compile of many files rather than one compile per package, so that the cost of covering
	 * the corpus is a file count and not a process count. Which of them the arguments actually checked
	 * is read back per file by {@link ProbeRun#packagesWhereItCompiledClean}, which is the whole reason
	 * {@link Compilation#errorSources} exists.
	 */
	private static ProbeRun runProbes(List<String> arguments, String className, String body)
			throws Exception {
		Path dir = Files.createTempDirectory("javadoc-reference-guard-probes");
		try {
			List<String> packages = corpusPackages();
			List<Path> files = new ArrayList<Path>();
			Map<String, String> packageByPath = new LinkedHashMap<String, String>();
			for (String packageName : packages) {
				Path file = dir.resolve(packageName.replace('.', File.separatorChar))
						.resolve(className + ".java");
				Files.createDirectories(file.getParent());
				Files.write(file,
						("package " + packageName + ";\n\n" + body).getBytes(StandardCharsets.UTF_8));
				files.add(file);
				packageByPath.put(file.toAbsolutePath().toString(), packageName);
			}
			return new ProbeRun(compile(arguments, files, System.getProperty("java.class.path")),
					packages, packageByPath);
		}
		finally {
			deleteRecursively(dir);
		}
	}

	/** One compile of the per-package probes, and which package each diagnostic belongs to. */
	private static final class ProbeRun {

		private final Compilation compilation;

		private final List<String> packages;

		private final Map<String, String> packageByPath;

		private ProbeRun(Compilation compilation, List<String> packages,
				Map<String, String> packageByPath) {
			this.compilation = compilation;
			this.packages = packages;
			this.packageByPath = packageByPath;
		}

		/**
		 * The packages whose probe the compiler raised no error for, in corpus order — for the dead
		 * probe, exactly the packages the arguments leave unchecked.
		 *
		 * <p>An error attributed to no probe file, a diagnostic with no source at all included, fails
		 * LOUDLY instead of being counted either way. Counted as coverage it would report an unrelated
		 * failure as a gate in force; counted as a gap it would report one as #262 reinstated. Neither
		 * is a verdict this run can support.
		 */
		private List<String> packagesWhereItCompiledClean(String where, List<String> arguments) {
			Set<String> refused = new LinkedHashSet<String>();
			List<String> unattributed = new ArrayList<String>();
			for (String source : compilation.errorSources()) {
				String packageName = packageByPath.get(source);
				if (packageName == null) {
					unattributed.add(source);
				}
				else {
					refused.add(packageName);
				}
			}
			if (!unattributed.isEmpty()) {
				fail(where + " declares <compilerArgs> " + arguments + ", under which the probes produced an "
						+ "error attributed to no probe of any package " + unattributed + " — so this run cannot "
						+ "say which packages those arguments cover:\n\n" + compilation.report());
			}
			List<String> clean = new ArrayList<String>();
			for (String packageName : packages) {
				if (!refused.contains(packageName)) {
					clean.add(packageName);
				}
			}
			return clean;
		}
	}

	/**
	 * Every package the reactor's own sources declare — the scope a {@code -Xdoclint/package} argument
	 * is written against, and so the scope the probes have to answer for. Derived from the corpus and
	 * never a hand-written list, for {@link #SOURCE_ROOTS}' reason.
	 *
	 * <p>Read off each source's DIRECTORY under its root and then cross-checked against that file's own
	 * {@code package} statement, because it is the DECLARED package the option filters on: a file whose
	 * directory and declaration disagree would put a probe in a package nothing compiles while leaving
	 * the compiled one unprobed. A source sitting directly on a root is in the unnamed package, which no
	 * {@code -Xdoclint/package} argument can name and which is therefore always checked — it is REFUSED
	 * rather than probed, because this repository has never had such a file and the first one is worth a
	 * reader.
	 */
	private static List<String> corpusPackages() throws Exception {
		List<String> packages = new ArrayList<String>();
		for (String root : reactorSourceRoots()) {
			Path directory = REPO_ROOT.resolve(root);
			for (Path source : javaSourcesUnder(Collections.singletonList(root))) {
				Path relative = directory.relativize(source).getParent();
				if (relative == null) {
					fail(source + " sits directly on " + root + ", so it declares no package. No "
							+ "-Xdoclint/package argument can name the unnamed package, so such a file needs no "
							+ "probe — but this repository has never had one, and the first is worth reading "
							+ "before this guard is taught to skip it.");
					return packages;
				}
				String declared = relative.toString().replace(File.separatorChar, '.');
				if (!declaresPackage(source, declared)) {
					fail(source + " does not declare package " + declared + ", which is what its directory "
							+ "under " + root + " says it is in. -Xdoclint/package filters on the DECLARED "
							+ "package, so this guard would probe a package nothing compiles and leave the "
							+ "compiled one unprobed.");
					return packages;
				}
				if (!packages.contains(declared)) {
					packages.add(declared);
				}
			}
		}
		if (packages.isEmpty()) {
			fail("No package was derived from the reactor's source roots, so the probes would answer for "
					+ "nothing at all.");
		}
		return packages;
	}

	/** Whether the file carries {@code package <packageName>;} as a line of its own. */
	private static boolean declaresPackage(Path source, String packageName) throws IOException {
		String statement = "package " + packageName + ";";
		for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
			if (line.trim().equals(statement)) {
				return true;
			}
		}
		return false;
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
				try {
					compiler.getTask(null, manager, collector, arguments, null, units).call();
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
				return new Compilation(collector.getDiagnostics());
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
	 * The packages of this corpus in which one argument list, given to the real compiler, does NOT make
	 * a dead javadoc reference an ERROR — empty where the arguments cover every package the build
	 * compiles, which is the only answer a caller may treat as the gate being in force.
	 *
	 * <p>The compiler is the oracle rather than a string match on {@link #REFERENCE_CHECK} because
	 * {@code -Xdoclint} is an option GRAMMAR: {@code -Xdoclint:all,-missing,-html,-syntax} enables the
	 * reference group perfectly well and a prefix match calls it missing, so a maintainer WIDENING the
	 * check would be told they had removed it. It answers per PACKAGE for the same reason one step
	 * further on: {@code -Xdoclint/package} is part of that grammar too, and a single probe answers
	 * only for wherever it happens to sit — see {@link #DEAD_REFERENCE_BODY}.
	 *
	 * <p>The LIVE probe is checked here rather than at one call site, because both callers need it: a
	 * dead reference failing alone is also satisfied by an argument list that refuses to compile
	 * anything at all, and a child {@code <compilerArgs>} block can carry such a list as easily as the
	 * managed one. That, and a dirty baseline, fail LOUDLY with the compiler's own output rather than
	 * returning a gap — a gap would report an environmental failure as "a dead pointer is silent
	 * again", the right colour with the wrong cause.
	 *
	 * <p><strong>Its answer is about the JDK running this suite, not about the CI matrix.</strong> An
	 * argument valid on a newer JDK and rejected by an older one passes here and reddens every older
	 * leg — measured with {@code -Xlint:-dangling-doc-comments}, which JDK 24 accepts and JDK 11 calls
	 * an invalid flag.
	 */
	private static List<String> packagesLeftUnchecked(String where, List<String> arguments)
			throws Exception {
		ProbeRun baseline = runProbes(BASELINE_ARGUMENTS, DEAD_REFERENCE_CLASS, DEAD_REFERENCE_BODY);
		if (baseline.compilation.failedWithAnError()) {
			fail("This guard could not run: the dead-reference probes do not compile even without "
					+ REFERENCE_CHECK + ", so nothing can be attributed to the arguments under test.\n\n"
					+ join(baseline.compilation.errors()));
		}
		ProbeRun live = runProbes(arguments, LIVE_REFERENCE_CLASS, LIVE_REFERENCE_BODY);
		if (live.compilation.failedWithAnError()) {
			fail(where + " declares <compilerArgs> " + arguments + ", which refuse a source whose javadoc\n"
					+ "reference RESOLVES — so every build would fail for a reason unrelated to any pointer:\n\n"
					+ join(live.compilation.errors()));
		}
		return runProbes(arguments, DEAD_REFERENCE_CLASS, DEAD_REFERENCE_BODY)
				.packagesWhereItCompiledClean(where, arguments);
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
		Element plugin = rootManagedCompilerPlugin();
		return plugin == null ? new ArrayList<String>()
				: javacArguments(directChild(plugin, "configuration"));
	}

	/**
	 * The root pom's {@code <build>/<pluginManagement>} entry for the compiler plugin, or null where
	 * it declares none. The one position both modules inherit and both mojos receive; navigated by
	 * path for {@link #rootManagedCompilerArgs}' reason, and shared with
	 * {@link #rootManagedCompilerPluginVersion} so the arguments and the version that has to honour
	 * them are read off the same element.
	 */
	private static Element rootManagedCompilerPlugin() throws Exception {
		Element build = directChild(pomRoot("pom.xml"), "build");
		Element management = directChild(build, "pluginManagement");
		for (Element plugin : directChildren(directChild(management, "plugins"), "plugin")) {
			Element artifactId = directChild(plugin, "artifactId");
			if (artifactId != null && COMPILER_PLUGIN.equals(artifactId.getTextContent().trim())) {
				return plugin;
			}
		}
		return null;
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
	 * The javac arguments each configuration position under one plugin element declares, keyed by
	 * where it sits — the plugin's own {@code <configuration>}, or a named {@code <execution>}. The
	 * key is what the failure message needs; the map is what makes the check over them universal.
	 *
	 * <p>One entry per POSITION and not per parameter, because the parameters at a position are
	 * composed: see {@link #LIST_ARGUMENT_CHANNELS} for why all five are read together and what that
	 * union can and cannot get wrong.
	 */
	private static Map<String, List<String>> javacArgumentBlocks(Element plugin) {
		Map<String, List<String>> blocks = new LinkedHashMap<String, List<String>>();
		List<String> pluginLevel = javacArguments(directChild(plugin, "configuration"));
		if (!pluginLevel.isEmpty()) {
			blocks.put("plugin-level <configuration>", pluginLevel);
		}
		for (Element execution : executions(plugin)) {
			List<String> declared = javacArguments(directChild(execution, "configuration"));
			if (!declared.isEmpty()) {
				blocks.put(executionLabel(execution), declared);
			}
		}
		return blocks;
	}

	/**
	 * One {@code <configuration>}'s javac arguments, composed across every channel
	 * {@link #LIST_ARGUMENT_CHANNELS} names.
	 *
	 * <p>A string channel contributes its text as ONE argument, which is what the plugin does with
	 * it — so two flags written into one element reach javac as a single invalid option, and the live
	 * probe in {@link #packagesLeftUnchecked} reports that loudly rather than this reader quietly
	 * splitting it into something the build never passes.
	 */
	private static List<String> javacArguments(Element configuration) {
		List<String> arguments = new ArrayList<String>();
		for (String channel : LIST_ARGUMENT_CHANNELS) {
			for (Element arg : directChildren(directChild(configuration, channel), "arg")) {
				arguments.add(arg.getTextContent().trim());
			}
		}
		for (String channel : STRING_ARGUMENT_CHANNELS) {
			Element declared = directChild(configuration, channel);
			if (declared != null && !declared.getTextContent().trim().isEmpty()) {
				arguments.add(declared.getTextContent().trim());
			}
		}
		for (String channel : MAP_ARGUMENT_CHANNELS) {
			arguments.addAll(mapChannelArguments(directChild(configuration, channel)));
		}
		return arguments;
	}

	/**
	 * The arguments one {@code Map} channel contributes, rendered as maven-compiler-plugin renders
	 * them: the entry's element NAME is the flag, prefixed with {@code -} where it does not already
	 * carry one, and its text follows as a separate argument — except for an annotation-processor
	 * option, where the plugin joins the two with {@code =}.
	 *
	 * <p>Read even though an XML element name cannot contain {@code /}, so the package qualifier of
	 * round 6 is not expressible through it. That is an observation about one silencer and not about
	 * the channel: the map is a general argument channel, this reader asks the compiler what the
	 * arguments DO rather than matching any particular one, and a channel read as empty is a channel
	 * nothing here would notice being used.
	 */
	private static List<String> mapChannelArguments(Element channel) {
		List<String> arguments = new ArrayList<String>();
		if (channel == null) {
			return arguments;
		}
		NodeList nodes = channel.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (node.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			String key = node.getNodeName();
			if (!key.startsWith("-")) {
				key = "-" + key;
			}
			String value = node.getTextContent().trim();
			if (key.startsWith("-A") && !value.isEmpty()) {
				arguments.add(key + "=" + value);
			}
			else {
				arguments.add(key);
				if (!value.isEmpty()) {
					arguments.add(value);
				}
			}
		}
		return arguments;
	}

	/**
	 * Every parameter one plugin element's configurations declare that this guard neither interprets
	 * nor has judged unable to carry a javac argument, described by where it sits. The answer to the
	 * family of defect that produced rounds 5, 6 and 7 of this change's review — see
	 * {@link #PARAMETERS_CARRYING_NO_JAVAC_ARGUMENT}.
	 *
	 * <p><strong>Why closing the world here is enough for the ARGUMENT family, and where it stops.</strong>
	 * None of maven-compiler-plugin's five argument parameters is bound to a user property (read off
	 * the 3.13.0 descriptor: {@code compilerArgs}, {@code compilerArgument}, {@code compilerArguments},
	 * {@code testCompilerArgument} and {@code testCompilerArguments} declare no expression, unlike
	 * {@code failOnError} and {@code compilerId} which declare one each). So an argument channel can
	 * only act from an element inside a compiler {@code <configuration>}, and a name this reader has
	 * never heard of is exactly what that element looks like. That is NOT true of the two refused
	 * parameters or of {@code fork}/{@code executable}, which is why each of those is also asked of
	 * {@code <properties>} ({@link #compilerUserPropertyOverrides}).
	 */
	private static List<String> unreadCompilerParametersAt(Element plugin) {
		List<String> where = new ArrayList<String>();
		collectUnreadParameters(where, "plugin-level <configuration>",
				directChild(plugin, "configuration"));
		for (Element execution : executions(plugin)) {
			collectUnreadParameters(where, executionLabel(execution),
					directChild(execution, "configuration"));
		}
		return where;
	}

	private static void collectUnreadParameters(List<String> where, String position,
			Element configuration) {
		if (configuration == null) {
			return;
		}
		NodeList nodes = configuration.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (node.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			String name = node.getNodeName();
			if (!INTERPRETED_COMPILER_PARAMETERS.contains(name)
					&& !PARAMETERS_CARRYING_NO_JAVAC_ARGUMENT.contains(name)) {
				where.add(position + " sets <" + name + ">");
			}
		}
	}

	/**
	 * The version the root pom's managed compiler-plugin entry pins, split into its numeric segments —
	 * empty where the entry declares none, which is itself a violation. Only the leading digits of each
	 * dot-separated segment are read, so a qualifier such as {@code 4.0.0-beta-5} compares as
	 * {@code 4.0.0}.
	 */
	private static List<Integer> rootManagedCompilerPluginVersion() throws Exception {
		List<Integer> segments = new ArrayList<Integer>();
		Element plugin = rootManagedCompilerPlugin();
		Element version = directChild(plugin, "version");
		if (version == null) {
			return segments;
		}
		for (String segment : version.getTextContent().trim().split("\\.")) {
			int digits = 0;
			while (digits < segment.length() && Character.isDigit(segment.charAt(digits))) {
				digits++;
			}
			segments.add(digits == 0 ? -1 : Integer.parseInt(segment.substring(0, digits)));
		}
		return segments;
	}

	/** Whether a version read by {@link #rootManagedCompilerPluginVersion} is at or above a floor. */
	private static boolean atLeast(List<Integer> version, int[] floor) {
		for (int i = 0; i < floor.length; i++) {
			int segment = i < version.size() ? version.get(i) : 0;
			if (segment != floor[i]) {
				return segment > floor[i];
			}
		}
		return true;
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

	/**
	 * Every {@code <properties>} entry in one POM that sets {@code failOnError}, {@code compilerId}, or
	 * the {@code fork}/{@code executable} pair through the user property maven-compiler-plugin binds it
	 * to, described. The element form of each is {@link #disabledFailOnErrorAt}'s,
	 * {@link #nonJavacCompilerIdAt}'s and {@link #unreadCompilerParametersAt}'s question; this asks the
	 * SAME questions of the other position Maven answers them from.
	 *
	 * <p>Three questions and not two since round 7, because the closed world over configuration
	 * elements newly refuses {@code <fork>} and {@code <executable>} — and refusing an element while
	 * ignoring the property that sets the same parameter is exactly the disagreement
	 * {@link #FAIL_ON_ERROR_PROPERTY} was written for. The pair is read TOGETHER, per POM rather than
	 * per {@code <properties>} block, so the two halves may be declared apart; see
	 * {@link #FORK_PROPERTY} for why neither half alone is a violation. The five ARGUMENT channels
	 * need no counterpart here: none of them is bound to a user property at all
	 * ({@link #unreadCompilerParametersAt}).
	 *
	 * <p>A second reader rather than a widening of those two, because a user property needs no plugin
	 * element: a POM declaring no maven-compiler-plugin block anywhere still sets both parameters from
	 * three words of {@code <properties>}, so anything hanging off {@link #compilerPlugins} cannot see
	 * it — and the whole of {@link #noOtherCompilerConfigurationDropsTheCheck} hung off that loop.
	 * {@link #FAIL_ON_ERROR_PROPERTY} carries the measurement.
	 *
	 * <p>Read through {@link #declaredUnderProjectOrProfile}, so a plugin's own {@code <properties>}
	 * parameter is not mistaken for the project's. What is still invisible is the same blind spot the
	 * element form has and this class discloses: the property set anywhere Maven reads that is not one
	 * of {@link #poms} — a {@code settings.xml} profile, the command line, {@code MAVEN_OPTS}, a
	 * committed {@code .mvn/maven.config} — is not in a POM here to be read. The last of those is
	 * inside the repository, which is why the line is drawn at the FILE.
	 */
	private static List<String> compilerUserPropertyOverrides(Element pom) {
		List<String> where = new ArrayList<String>();
		boolean forking = false;
		String binary = null;
		for (Element properties : declaredUnderProjectOrProfile(pom, "properties")) {
			if (isFalse(directChild(properties, FAIL_ON_ERROR_PROPERTY))) {
				where.add("<properties> sets <" + FAIL_ON_ERROR_PROPERTY + ">false</"
						+ FAIL_ON_ERROR_PROPERTY + "> — that is the user property maven-compiler-plugin binds "
						+ "failOnError to, on compile and testCompile alike, so a doclint reference error is "
						+ "printed and not fatal, which is the green build #262 reports. No plugin block is "
						+ "involved: this drops the gate from a <properties> entry");
			}
			Element id = directChild(properties, COMPILER_ID_PROPERTY);
			if (isNonJavac(id)) {
				where.add("<properties> sets <" + COMPILER_ID_PROPERTY + ">" + id.getTextContent().trim()
						+ "</" + COMPILER_ID_PROPERTY + "> — that is the user property maven-compiler-plugin "
						+ "binds compilerId to, and " + REFERENCE_CHECK + " is a javac option. Refused rather "
						+ "than judged: if that backend does honour it, say so in the commit that sets it");
			}
			if (isTrue(directChild(properties, FORK_PROPERTY))) {
				forking = true;
			}
			Element executable = directChild(properties, EXECUTABLE_PROPERTY);
			if (executable != null && !executable.getTextContent().trim().isEmpty()) {
				binary = executable.getTextContent().trim();
			}
		}
		if (forking && binary != null) {
			where.add("<properties> sets <" + FORK_PROPERTY + ">true</" + FORK_PROPERTY + "> and <"
					+ EXECUTABLE_PROPERTY + ">" + binary + "</" + EXECUTABLE_PROPERTY + "> — the pair hands "
					+ "the whole compilation to that binary, and this guard cannot tell whether it honours "
					+ REFERENCE_CHECK + ". Refused rather than judged, exactly as a non-javac <compilerId> is; "
					+ "the ELEMENT form of either is refused by unreadCompilerParametersAt");
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

	private static boolean isTrue(Element element) {
		return element != null && "true".equalsIgnoreCase(element.getTextContent().trim());
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
			sb.append("\nSee docs/adr.md, Decision 75: the javadoc IS this module's design record, so a ")
					.append("pointer that no longer resolves has to be a build failure rather than plain text.");
			fail(sb.toString());
		}
	}
}
