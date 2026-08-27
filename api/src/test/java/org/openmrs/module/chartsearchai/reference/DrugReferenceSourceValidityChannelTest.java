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
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

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
 * <p><b>Discovery from the source, assertion by reflection</b>, and each half is doing something the
 * other cannot. Reflection alone cannot enumerate an interface's implementations, so a fourth source
 * format would simply not be looked at — the vacuous pass this guard is written against. A source scan
 * alone would have to parse Java to decide whether a method is really an override of the right
 * signature, which {@link Class#getDeclaredMethod} answers exactly.
 *
 * <p><b>What it does NOT cover</b>, stated rather than left to be discovered. A source that declares
 * both methods and returns a constant empty list from them passes here — declaring the channel is not
 * the same as using it, and no structural check can tell the two apart. What that leaves is a mistake
 * someone has to make deliberately, in a method whose javadoc says what it is for, rather than one made
 * by not writing anything at all. Nor does it reach the groups loader, which is not a
 * {@link DrugReferenceSource}: its two accessors are ordinary public methods
 * {@link DrugReferenceService} calls, so removing one breaks the build.
 */
public class DrugReferenceSourceValidityChannelTest {

	private static final String SOURCE_DIR =
			"src/main/java/org/openmrs/module/chartsearchai/reference";

	/** The interface's own defaulted methods, which a production source must not be allowed to inherit. */
	private static final List<String> CHANNEL_METHODS =
			Collections.unmodifiableList(java.util.Arrays.asList("lastLoadFindings", "lastLoadOrigin"));

	@Test
	public void everyProductionSourceDeclaresItsOwnFindingsAndOriginAccessors() throws Exception {
		List<String> implementations = sourceImplementations();

		// The enumeration has to find them, or every check inside the loop is vacuous. Three today
		// (json, atc, ddinter); asserted as a floor rather than an exact count, because a fourth format
		// is precisely the case this guard exists for and must not have to edit a number to be added.
		assertTrue(implementations.size() >= 3,
				"the scan must find the DrugReferenceSource implementations under " + SOURCE_DIR
						+ ", or this guard passes on a directory it never read — found " + implementations);

		List<String> undeclared = new ArrayList<String>();
		for (String simpleName : implementations) {
			Class<?> type = Class.forName(DrugReferenceSource.class.getPackage().getName() + "."
					+ simpleName);
			for (String method : CHANNEL_METHODS) {
				try {
					type.getDeclaredMethod(method);
				}
				catch (NoSuchMethodException missing) {
					undeclared.add(simpleName + "." + method + "()");
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
	 * @return the simple names of every class in the reference package's source directory declaring
	 *         {@code implements DrugReferenceSource}. The source rather than the classpath because the
	 *         test seams are lambdas — {@link DrugReferenceSource} is a functional interface — and a
	 *         lambda has no source file, no name to report and no obligation to declare anything, so
	 *         scanning what is WRITTEN is what separates the production sources from them.
	 */
	private static List<String> sourceImplementations() throws IOException {
		Path dir = sourceDirectory();
		List<String> found = new ArrayList<String>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.java")) {
			for (Path file : files) {
				String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				if (source.contains("implements " + DrugReferenceSource.class.getSimpleName())) {
					String name = file.getFileName().toString();
					found.add(name.substring(0, name.length() - ".java".length()));
				}
			}
		}
		Collections.sort(found);
		return found;
	}

	/**
	 * The reference package's main source directory, found by walking up from the working directory —
	 * surefire sets it to the module directory, so the first candidate normally hits. Missing is a hard
	 * failure, never a skip: a guard that cannot find what it reads must fail rather than pass.
	 */
	private static Path sourceDirectory() {
		Path current = Paths.get("").toAbsolutePath();
		while (current != null) {
			Path direct = current.resolve(SOURCE_DIR);
			if (Files.isDirectory(direct)) {
				return direct;
			}
			Path api = current.resolve("api").resolve(SOURCE_DIR);
			if (Files.isDirectory(api)) {
				return api;
			}
			current = current.getParent();
		}
		throw new AssertionError("could not locate " + SOURCE_DIR + " from "
				+ Paths.get("").toAbsolutePath() + "; this guard must fail rather than pass on a "
				+ "directory it never read");
	}
}
