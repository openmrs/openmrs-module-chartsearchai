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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ModuleSourceRoot;

/**
 * The caution-lead tail {@link DrugSafetyValidator#CAUTION_LEAD_TAIL} reads an answer's lead by is the
 * one {@code eval/drift-metric/score_probe_safety.py} scores it by — issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/515">#515</a>, ADR Decision 119.
 * Two copies of one anchor drift silently: an edit to either would make the {@code cautionLedOverWithholding}
 * key and the eval's {@code caution_led} class read different leads with every other test green. So the
 * Python tail's raw-string pieces are read out of the script and compared, as text, with the Java pattern.
 */
public class CautionLeadTailParityTest {

	private static final Pattern PYTHON_TAIL = Pattern.compile("(?s)\\nCAUTION_LEAD_TAIL = \\((.*?)\\n\\)");

	private static final Pattern RAW_PIECE = Pattern.compile("(?m)^\\s*r\"(.*)\"\\s*$");

	@Test
	public void theJavaTailIsThePythonScorersTail() throws IOException {
		String script = new String(Files.readAllBytes(
				ModuleSourceRoot.repoRoot().resolve("eval/drift-metric/score_probe_safety.py")), StandardCharsets.UTF_8);
		Matcher tail = PYTHON_TAIL.matcher(script);
		assertTrue(tail.find(), "score_probe_safety.py no longer declares CAUTION_LEAD_TAIL as this case reads it");
		StringBuilder python = new StringBuilder();
		Matcher piece = RAW_PIECE.matcher(tail.group(1));
		while (piece.find()) {
			python.append(piece.group(1));
		}
		assertTrue(python.length() > 0, "no raw-string piece was read out of CAUTION_LEAD_TAIL");
		// Python's raw string spells the two dashes as escapes the regex engine reads; Java's source hands the
		// compiled pattern the characters themselves.
		String java = DrugSafetyValidator.CAUTION_LEAD_TAIL.pattern().replace("–", "\\u2013")
				.replace("—", "\\u2014");
		assertEquals(python.toString(), java);
	}
}
