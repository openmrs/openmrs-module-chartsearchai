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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ModuleSourceRoot;

/**
 * Every production {@link DrugReferenceSource} declares its own validity channel.
 *
 * <p><b>Why this is a structural guard and not a behavioural one.</b> Issue #266's defect was that
 * {@code sourceFormat=atc} had no validity channel at all, and the reason it went unnoticed for as long
 * as it did is that {@link DrugReferenceSource#lastLoadFindings()} and
 * {@link DrugReferenceSource#lastLoadOrigin()} are DEFAULTED interface methods. A source that does not
 * override them compiles, loads, answers questions, and reports an empty {@code findings} list on
 * {@code GET /chartsearchai/drugreferencestatus} — no exception, no log line, no failing test. It fails
 * closed and silently, which is the failure mode this whole check exists to prevent: {@code CLAUDE.md}'s
 * rule is that silence is the ABSENCE of a finding, never a muted one, and a channel that cannot carry
 * one is the strongest form of that violation.
 *
 * <p>So the thing to pin is not a behaviour but the presence of a declaration, and this repo's
 * established mechanism for that is a guard that reads its own source or compiled classes —
 * {@code DrugReferenceFindingLoudnessTest.everyRuleIsClassifiedAsDataOrAsConfiguration} by reflection
 * over the rule constants, {@code OrderPartnerNameSourceWritePathTest} by source scan,
 * {@code ChartSearchAiReferenceGroundingWithholdingTest} by compiled-class scan.
 *
 * <p><b>Discovery from the source FILE NAMES, membership by reflection</b>, and each half does something
 * the other cannot. Reflection alone cannot enumerate an interface's implementations, so a fourth source
 * format would simply not be looked at — the vacuous pass this guard is written against. But the source
 * must not be asked whether a class implements the interface: the first version of this test matched the
 * literal {@code "implements DrugReferenceSource"}, and a review agent refuted it in one move with a
 * probe class declaring {@code implements Serializable, DrugReferenceSource} — never enumerated, no
 * override, and the guard still green because the floor below was met by the three real sources. A
 * declaration can also be wrapped across a line, or reach the interface through a superclass. So the
 * source tree answers only "which classes live in this package", which is a question about file names
 * that no syntax can hide, and {@link Class#isAssignableFrom} answers membership.
 *
 * <p><b>What it does NOT cover</b>, stated rather than left to be discovered. A source that declares
 * both methods and returns a constant empty list from them passes here — declaring the channel is not
 * the same as using it, and no structural check can tell the two apart. What that leaves is a mistake
 * someone has to make deliberately, in a method whose javadoc says what it is for, rather than one made
 * by not writing anything at all; {@code CLAUDE.md}'s loader bullet carries the rule that closes it
 * (resolve through {@link ReferenceDataFiles}, never open your own stream). Nor does it reach a source
 * declared OUTSIDE this package, or the groups loader, which is not a {@link DrugReferenceSource} — its
 * accessors are ordinary public methods {@link DrugReferenceService} calls, so removing one breaks the
 * build.
 *
 * <p>The scan asserts its own findings are non-empty, which the walking caller of
 * {@link ModuleSourceRoot#apiRoot()} owes itself: that method falls back to the working directory rather
 * than failing, so a walking caller that does not check can scan nothing and report no violations.
 */
public class DrugReferenceSourceValidityChannelTest {

	private static final String SOURCE_DIR = "src/main/java/org/openmrs/module/chartsearchai/reference";

	/** The interface's own defaulted methods, which a production source must not be allowed to inherit. */
	private static final List<String> CHANNEL_METHODS =
			Collections.unmodifiableList(Arrays.asList("lastLoadFindings", "lastLoadOrigin"));

	@Test
	public void everyProductionSourceDeclaresItsOwnFindingsAndOriginAccessors() throws Exception {
		List<Class<?>> implementations = sourceImplementations();

		// The enumeration has to find them, or every check inside the loop is vacuous. Three today
		// (json, atc, ddinter); asserted as a floor rather than an exact count, because a fourth format
		// is precisely the case this guard exists for and must not have to edit a number to be added.
		assertTrue(implementations.size() >= 3,
				"the scan must find the DrugReferenceSource implementations under " + SOURCE_DIR
						+ ", or this guard passes on a directory it never read — found " + implementations);

		List<String> undeclared = new ArrayList<String>();
		for (Class<?> type : implementations) {
			for (String method : CHANNEL_METHODS) {
				try {
					type.getDeclaredMethod(method);
				}
				catch (NoSuchMethodException missing) {
					undeclared.add(type.getSimpleName() + "." + method + "()");
				}
			}
		}

		assertEquals("[]", undeclared.toString(),
				"a DrugReferenceSource that inherits these defaults has no validity channel: whatever "
						+ "its dataset does, findings on GET /chartsearchai/drugreferencestatus stays "
						+ "empty for that format, and nothing errors. That was issue #266 on the atc "
						+ "format. Override both, resolving the file through ReferenceDataFiles so a "
						+ "collector exists to report into");
	}

	/**
	 * @return every concrete {@link DrugReferenceSource} implementation with a source file in the
	 *         reference package. The source tree supplies only the class NAMES — the test seams are
	 *         lambdas ({@link DrugReferenceSource} is a functional interface) and a lambda has no source
	 *         file, no name to report and no obligation to declare anything, so scanning what is WRITTEN
	 *         is what separates the production sources from them. Whether each named class is a source is
	 *         then asked of the loaded class, never of the text: see this class's javadoc for the probe
	 *         that refuted the textual form.
	 */
	private static List<Class<?>> sourceImplementations() throws IOException, ClassNotFoundException {
		Path dir = ModuleSourceRoot.apiRoot().resolve(SOURCE_DIR);
		assertTrue(Files.isDirectory(dir),
				"the reference package's source directory has to be found, or this guard reads nothing "
						+ "and passes: " + dir);
		String pkg = DrugReferenceSource.class.getPackage().getName();
		List<Class<?>> found = new ArrayList<Class<?>>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.java")) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				Class<?> type = Class.forName(pkg + "."
						+ name.substring(0, name.length() - ".java".length()));
				if (DrugReferenceSource.class.isAssignableFrom(type) && !type.isInterface()) {
					found.add(type);
				}
			}
		}
		return found;
	}
}
