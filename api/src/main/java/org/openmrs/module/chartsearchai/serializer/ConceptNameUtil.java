/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.serializer;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

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
		Set<String> synonyms = new LinkedHashSet<>();

		for (ConceptName cn : concept.getNames()) {
			if (synonyms.size() >= MAX_SYNONYMS) {
				break;
			}
			if (cn.getLocale() != null && !cn.getLocale().getLanguage().equals(localeLanguage)) {
				continue;
			}
			String name = cn.getName();
			if (name != null && !name.equals(preferredText)) {
				synonyms.add(name);
			}
		}

		if (synonyms.isEmpty()) {
			return preferredText;
		}
		return preferredText + " (" + String.join(", ", synonyms) + ")";
	}
}
