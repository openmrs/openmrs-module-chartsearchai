/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.api.context.Context;

/**
 * Resolves a concept's preferred name enriched with same-locale synonyms so that embeddings
 * capture alternative terms clinicians may search for (e.g. "Hypertension (HTN, High Blood
 * Pressure)"). Limits synonyms to three to keep serialized text concise.
 */
public final class ConceptNameUtil {

	private static final int MAX_SYNONYMS = 3;

	private ConceptNameUtil() {
	}

	// Matches synonym parentheses added by getName(), e.g. " (syn. HTN, High Blood Pressure)"
	// The "syn. " prefix distinguishes these from structural parens like "(food allergen)".
	private static final Pattern SYNONYM_PARENS = Pattern.compile(" \\(syn\\. [^)]*\\)");

	/**
	 * Condenses synonym parentheses into a compact slash format so the LLM receives
	 * concise but synonym-aware records. Keeps only the first synonym to minimize noise.
	 *
	 * <p>For example, {@code "Diarrhea (syn. Diarrhoea, Loose bowels)"} becomes
	 * {@code "Diarrhea/Diarrhoea"}. Structural parentheses like "(food allergen)" are
	 * preserved.</p>
	 *
	 * @param text the serialized record text
	 * @return text with synonym parentheses condensed to slash format
	 */
	public static String condenseSynonyms(String text) {
		if (text == null) {
			return null;
		}
		Matcher matcher = SYNONYM_PARENS.matcher(text);
		StringBuffer sb = new StringBuffer();
		while (matcher.find()) {
			// Extract content after "syn. ", take only the first synonym
			String content = matcher.group();
			String inner = content.substring(" (syn. ".length(), content.length() - 1);
			String firstSynonym = inner.split(",")[0].trim();
			matcher.appendReplacement(sb, Matcher.quoteReplacement("/" + firstSynonym));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	/**
	 * Strips synonym parentheses from serialized text entirely.
	 *
	 * @param text the serialized record text
	 * @return text with synonym parentheses removed
	 */
	public static String stripSynonyms(String text) {
		if (text == null) {
			return null;
		}
		return SYNONYM_PARENS.matcher(text).replaceAll("");
	}

	/**
	 * Extracts the concept name from a record's text content (after prefix
	 * and date have been stripped). Handles the common serialization formats:
	 * <ul>
	 *   <li>Obs: {@code "Type — Concept Name: value"} → {@code "Concept Name"}</li>
	 *   <li>Condition: {@code "Condition: Name. Status:"} → {@code "Name"}</li>
	 *   <li>Diagnosis: {@code "Diagnosis: Name. Certainty:"} → {@code "Name"}</li>
	 *   <li>Drug order: {@code "Drug order: Name. Dose:"} → {@code "Name"}</li>
	 *   <li>Allergy: {@code "Allergy: Name (type)"} → {@code "Name"}</li>
	 *   <li>Program: {@code "Program: Name. Enrolled:"} → {@code "Name"}</li>
	 * </ul>
	 * Synonym parentheses are stripped before extraction.
	 *
	 * @param textContent the record text content (prefix/date already removed)
	 * @return the concept name, or null if it cannot be extracted
	 */
	public static String extractConceptName(String textContent) {
		if (textContent == null || textContent.isEmpty()) {
			return null;
		}
		String text = stripSynonyms(textContent);

		// Obs pattern: "TYPE — CONCEPT: value" (em-dash U+2014)
		int dashIdx = text.indexOf(" \u2014 ");
		if (dashIdx >= 0) {
			String afterDash = text.substring(dashIdx + 3);
			int colonIdx = afterDash.indexOf(':');
			if (colonIdx > 0) {
				return afterDash.substring(0, colonIdx).trim();
			}
		}

		// Condition: "Condition: NAME. Status:" or "Condition: NAME, SEVERITY. Status:"
		// Uses indexOf instead of startsWith so hint-enriched text like
		// "Sexually transmitted disease / Condition: HIV disease. Status: ACTIVE"
		// still finds the concept name correctly.
		int condIdx = text.indexOf("Condition: ");
		if (condIdx >= 0) {
			String rest = text.substring(condIdx + "Condition: ".length());
			int dotIdx = rest.indexOf(". ");
			int commaIdx = rest.indexOf(", ");
			int endIdx = minPositive(dotIdx, commaIdx);
			return endIdx > 0 ? rest.substring(0, endIdx).trim() : rest.trim();
		}

		// Diagnosis: "Diagnosis: NAME. Certainty:"
		int diagIdx = text.indexOf("Diagnosis: ");
		if (diagIdx >= 0) {
			String rest = text.substring(diagIdx + "Diagnosis: ".length());
			int dotIdx = rest.indexOf(". ");
			return dotIdx > 0 ? rest.substring(0, dotIdx).trim() : rest.trim();
		}

		// Drug order: "Drug order: NAME. Dose:"
		int drugIdx = text.indexOf("Drug order: ");
		if (drugIdx >= 0) {
			String rest = text.substring(drugIdx + "Drug order: ".length());
			int dotIdx = rest.indexOf(". ");
			return dotIdx > 0 ? rest.substring(0, dotIdx).trim() : rest.trim();
		}

		// Allergy: "Allergy: NAME (type). Severity:"
		int allergyIdx = text.indexOf("Allergy: ");
		if (allergyIdx >= 0) {
			String rest = text.substring(allergyIdx + "Allergy: ".length());
			int parenIdx = rest.indexOf(" (");
			int dotIdx = rest.indexOf(". ");
			int endIdx = minPositive(parenIdx, dotIdx);
			return endIdx > 0 ? rest.substring(0, endIdx).trim() : rest.trim();
		}

		// Program: "Program: NAME. Enrolled:"
		int progIdx = text.indexOf("Program: ");
		if (progIdx >= 0) {
			String rest = text.substring(progIdx + "Program: ".length());
			int dotIdx = rest.indexOf(". ");
			return dotIdx > 0 ? rest.substring(0, dotIdx).trim() : rest.trim();
		}

		// Test order: "Test order: NAME. Action:"
		int testIdx = text.indexOf("Test order: ");
		if (testIdx >= 0) {
			String rest = text.substring(testIdx + "Test order: ".length());
			int dotIdx = rest.indexOf(". ");
			return dotIdx > 0 ? rest.substring(0, dotIdx).trim() : rest.trim();
		}

		// Referral order: "Referral order: NAME. Action:"
		int refIdx = text.indexOf("Referral order: ");
		if (refIdx >= 0) {
			String rest = text.substring(refIdx + "Referral order: ".length());
			int dotIdx = rest.indexOf(". ");
			return dotIdx > 0 ? rest.substring(0, dotIdx).trim() : rest.trim();
		}

		// Medication dispense: "Dispensed: NAME. Status:"
		int dispIdx = text.indexOf("Dispensed: ");
		if (dispIdx >= 0) {
			String rest = text.substring(dispIdx + "Dispensed: ".length());
			int dotIdx = rest.indexOf(". ");
			return dotIdx > 0 ? rest.substring(0, dotIdx).trim() : rest.trim();
		}

		return null;
	}

	/** Returns the smaller of two indices, ignoring non-positive values. */
	private static int minPositive(int a, int b) {
		if (a <= 0) return b;
		if (b <= 0) return a;
		return Math.min(a, b);
	}

	/**
	 * Returns just the preferred concept name without synonyms. Useful when concise text
	 * is needed, such as in allergy reaction and severity fields where synonyms would add
	 * noise that hurts small-LLM citation accuracy.
	 *
	 * @param concept the concept to resolve
	 * @return the preferred name, or empty string if unavailable
	 */
	public static String getPreferredName(Concept concept) {
		if (concept == null) {
			return "";
		}
		ConceptName preferred = concept.getName();
		return preferred != null ? preferred.getName() : "";
	}

	/**
	 * Returns the preferred concept name followed by up to three same-locale synonyms in
	 * parentheses.
	 *
	 * @param concept the concept to resolve
	 * @return a name string such as {@code "Hypertension (HTN, High Blood Pressure)"}
	 */
	public static String getName(Concept concept) {
		if (concept == null) {
			return "";
		}
		ConceptName preferred = concept.getName();
		if (preferred == null) {
			return "";
		}

		String preferredText = preferred.getName();
		Locale locale = Context.getLocale();
		if (locale == null) {
			locale = Locale.ENGLISH;
		}
		String localeLanguage = locale.getLanguage();
		// Sort candidate names alphabetically for deterministic synonym ordering
		// across JVM restarts (concept.getNames() returns a HashSet).
		List<String> candidateNames = new ArrayList<>();
		for (ConceptName cn : concept.getNames()) {
			if (cn.getLocale() != null && !cn.getLocale().getLanguage().equals(localeLanguage)) {
				continue;
			}
			String name = cn.getName();
			if (name != null && !name.equals(preferredText)) {
				candidateNames.add(name);
			}
		}
		Collections.sort(candidateNames);

		Set<String> synonyms = new LinkedHashSet<>();
		for (String name : candidateNames) {
			if (synonyms.size() >= MAX_SYNONYMS) {
				break;
			}
			synonyms.add(name);
		}

		if (synonyms.isEmpty()) {
			return preferredText;
		}
		return preferredText + " (syn. " + String.join(", ", synonyms) + ")";
	}
}
