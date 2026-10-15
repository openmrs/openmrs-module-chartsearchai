/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.util.List;
import java.util.Locale;

import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.NamedAsAsked;

/**
 * The module's own statement of which drug the data's name in an answer is, where the question named it otherwise — ADR
 * Decision 277. <em>"Is it safe to start glibenclamide?"</em> was answered <em>"3 interaction cautions for Glyburide:"</em>,
 * the knowledge base's US name, which a clinician writing the international one need not know. Which drugs is
 * {@code PatientChart.getNamedAsAsked()}'s, stamped by the pre-answer pass; this class decides only whether the answer uses
 * the data's name and not the question's. Like {@link ListedDrugStatement} it APPENDS: no marker, no chip, no prompt change.
 */
final class NameAsAskedStatement {

	private NameAsAskedStatement() {
	}

	/** {@code answer} with one sentence per drug the answer names only by the data's name appended after it. */
	static String withNamesAsAskedStated(String answer, List<NamedAsAsked> named) {
		String stated = statements(answer, named);
		return stated.isEmpty() ? answer : (DrugSafetyValidator.endSentence(answer.trim()) + " " + stated).trim();
	}

	/** As {@link #withNamesAsAskedStated}, on a line of its own — the module's composed answer, whose lines end in
	 *  markers. */
	static String withNamesAsAskedStatedOnALine(String answer, List<NamedAsAsked> named) {
		String stated = statements(answer, named);
		return stated.isEmpty() ? answer : answer + "\n" + stated;
	}

	private static String statements(String answer, List<NamedAsAsked> named) {
		if (answer == null || named == null || named.isEmpty()) {
			return "";
		}
		String folded = answer.toLowerCase(Locale.ROOT);
		StringBuilder sb = new StringBuilder();
		for (NamedAsAsked drug : named) {
			String asAsked = drug.getAsAsked();
			if (!folded.contains(drug.getDataName().toLowerCase(Locale.ROOT))
					|| folded.contains(asAsked.toLowerCase(Locale.ROOT))) {
				continue;
			}
			sb.append(sb.length() == 0 ? "" : " ").append(Character.toUpperCase(asAsked.charAt(0)))
					.append(asAsked.substring(1)).append(", as the question names it, is named ").append(drug.getDataLabel())
					.append(" in this module's drug data.");
		}
		return sb.toString();
	}
}
