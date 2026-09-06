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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ModuleSourceRoot;

/**
 * {@link DrugSafetyValidator#ACTIVE_ORDER_INTERACTION_PHRASE} has two readers since issue #377 — the
 * renderer that writes an interaction chip's detail, and {@code ActiveOrderCitationFidelityCheck},
 * which recognises the same claim in an ANSWER in order to ask what chart record was cited for it.
 * They must be one string or the check silently stops matching what the renderer writes.
 *
 * <p><b>This reads the SOURCE, and that is the point.</b> A behavioural case asserting that a chip's
 * detail contains the constant compares a constant to itself: re-inlining the literal at the render
 * site leaves it green, leaves the whole api suite green, and breaks nothing until an answer that
 * would have been reported quietly is not. This module has pinned that class of rule structurally
 * before, for the same reason — {@code OrderPartnerNameSourceWritePathTest} scans for a write-path
 * SHAPE because an {@code &&}/{@code ||} slip was green under the whole behavioural suite.
 *
 * <p><b>Two channels, because one of them was defeated in review.</b> The first asserts the literal
 * is written out once. Round 1 of this PR's review defeated it in a form that is semantically the
 * defect and textually not the obvious edit — splitting the render site into
 * {@code " interacts with active order" + " "}, which reads the constant nowhere, renders the same
 * string, and left this test green. So the second channel asserts the SHAPE the render site must
 * have: the detail is built by reading the constant. Neither subsumes the other — the first catches a
 * second copy of the literal, the second catches a render site that stopped reading the constant
 * however it spells the string.
 *
 * <p><b>What neither can see.</b> A second spelling that differs from this one — a lost space, a
 * capital — is a different string and is invisible here, exactly as it is to the renderer's own
 * tests. Both read code lines only, so the
 * phrase inside a javadoc example or a line comment is not a violation, and a maintainer who moves
 * the literal into a comment has not defeated anything: the render site would then have no phrase at
 * all and {@code DrugSafetyChipLabelTest} and its neighbours would redden.
 */
public class ActiveOrderInteractionPhraseTest {

	@Test
	public void theRenderedPhraseIsSpelledOutInExactlyOnePlaceInProduction() throws IOException {
		String literal = "\"" + DrugSafetyValidator.ACTIVE_ORDER_INTERACTION_PHRASE + "\"";
		List<String> sites = new ArrayList<String>();
		int scanned = 0;
		Path main = ModuleSourceRoot.repoRoot().resolve("api/src/main/java");
		try (Stream<Path> files = Files.walk(main)) {
			for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))
					::iterator) {
				scanned++;
				int line = 0;
				for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					line++;
					String trimmed = text.trim();
					if (trimmed.startsWith("*") || trimmed.startsWith("//")
							|| trimmed.startsWith("/*")) {
						continue;
					}
					if (text.contains(literal)) {
						sites.add(main.relativize(file) + ":" + line);
					}
				}
			}
		}
		assertTrue(scanned > 1,
				"the scan must reach production sources, or this passes vacuously; scanned "
						+ scanned + " file(s) under " + main);
		assertEquals(1, sites.size(),
				"the phrase must be spelled out once — the constant's own declaration — and read from "
						+ "there everywhere else, or the renderer and ActiveOrderCitationFidelityCheck "
						+ "can come apart with the whole suite green. Found: " + sites);
		assertTrue(sites.get(0).startsWith("org/openmrs/module/chartsearchai/reference/"
				+ "DrugSafetyValidator.java"),
				"and the one spelling must be the constant's own declaration. Found: " + sites);
	}

	/**
	 * The second channel: the render site READS the constant. Asserted as the shape of the expression
	 * that builds the detail rather than as a mention of the identifier anywhere in the file, because
	 * the identifier appears in that class's own javadoc too.
	 */
	@Test
	public void theChipDetailIsBuiltByReadingTheConstant() throws IOException {
		Path validator = ModuleSourceRoot.repoRoot().resolve("api/src/main/java/org/openmrs/module/"
				+ "chartsearchai/reference/DrugSafetyValidator.java");
		List<String> reads = new ArrayList<String>();
		int line = 0;
		for (String text : Files.readAllLines(validator, StandardCharsets.UTF_8)) {
			line++;
			String trimmed = text.trim();
			if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
				continue;
			}
			if (text.contains("+ ACTIVE_ORDER_INTERACTION_PHRASE +")) {
				reads.add("line " + line);
			}
		}
		assertEquals(1, reads.size(),
				"the interaction chip's detail must be built by concatenating the constant, so a render "
						+ "site rewritten to spell the string itself — as two adjacent literals, say — "
						+ "cannot pass while reading the constant nowhere. Found: " + reads);
	}
}
