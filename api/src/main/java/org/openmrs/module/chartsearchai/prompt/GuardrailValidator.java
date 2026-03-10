/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.openmrs.module.chartsearchai.adapter.LlmResponse;

/**
 * Post-processing validator that checks LLM responses for safety violations. Flags prescriptive
 * language, missing citations, and other patterns that violate clinical decision support boundaries.
 */
public class GuardrailValidator {

	private static final List<Pattern> PROHIBITED_PATTERNS = new ArrayList<>();

	private static final Pattern CITATION_PATTERN = Pattern.compile("\\[Source:.*?\\]");

	private static final Pattern CLINICAL_TERM_PATTERN = Pattern.compile(
			"(?i)\\b(patient|lab|medication|condition|allergy|diagnosis)\\b");

	static {
		PROHIBITED_PATTERNS.add(Pattern.compile(
				"(?i)\\b(I recommend|I suggest|you should|prescribe|administer)\\b"));
		PROHIBITED_PATTERNS.add(Pattern.compile(
				"(?i)\\b(prognosis is|life expectancy|will likely die)\\b"));
		PROHIBITED_PATTERNS.add(Pattern.compile(
				"(?i)\\b(I diagnose|diagnosis:|my assessment is)\\b"));
	}

	private GuardrailValidator() {
	}

	/**
	 * Validates an LLM response and adds warnings for any detected safety violations.
	 *
	 * @param response the LLM response to validate (warnings are added in place)
	 */
	public static void validate(LlmResponse response) {
		String text = response.getText();
		if (text == null || text.isEmpty()) {
			return;
		}

		checkProhibitedLanguage(response, text);
		checkCitationCoverage(response, text);
	}

	private static void checkProhibitedLanguage(LlmResponse response, String text) {
		for (Pattern pattern : PROHIBITED_PATTERNS) {
			if (pattern.matcher(text).find()) {
				response.addWarning(
						"Response may contain prescriptive language. "
						+ "This is decision SUPPORT only - verify all information.");
				return;
			}
		}
	}

	private static void checkCitationCoverage(LlmResponse response, String text) {
		String[] sentences = text.split("(?<=[.!?])\\s+");
		int clinicalSentences = 0;
		int citedSentences = 0;

		for (String sentence : sentences) {
			if (CLINICAL_TERM_PATTERN.matcher(sentence).find()) {
				clinicalSentences++;
				if (CITATION_PATTERN.matcher(sentence).find()) {
					citedSentences++;
				}
			}
		}

		if (clinicalSentences > 0 && citedSentences < clinicalSentences / 2) {
			response.addWarning(
					"Some clinical claims may lack source citations. "
					+ "Cross-reference with the patient chart.");
		}
	}
}
