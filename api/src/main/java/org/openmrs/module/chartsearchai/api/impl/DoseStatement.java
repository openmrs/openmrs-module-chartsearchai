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

import org.openmrs.module.chartsearchai.reference.DrugReferenceLoad;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;

/**
 * The module's own statement that a dose the question asked about was not checked — ADR Decision 274. <em>"Is 1 g of
 * paracetamol safe for him?"</em> and <em>"Can I give him amoxicillin 500 mg three times a day?"</em> are answered by the
 * model from the findings, which weigh no dose, and the knowledge base the module ships publishes no dose ceiling: an answer
 * that does not say so reads as a word on that dose.
 *
 * <p>Whether the question states a dose is {@code PatientChart.statesADose()}, stamped by the pre-answer pass; whether the
 * dataset could have checked one is {@code DrugSafetyValidator.doseCeilingCoverage()}. Like {@link ListedDrugStatement} it
 * APPENDS: no marker, no chip, no prompt change, and never a word about whether the drug may be given.
 */
final class DoseStatement {

	/** The sentence, spelled once. */
	static final String NOT_CHECKED = "The dose asked about was not checked: the drug data this module carries has no "
			+ "dosing.";

	private DoseStatement() {
	}

	/**
	 * {@code answer} with {@link #NOT_CHECKED} appended where the question states a dose and the dataset publishes no dose
	 * ceiling to check it against — {@code ABSENT}, or {@code UNLOADED}, where nothing is known — else {@code answer}
	 * unchanged.
	 */
	static String withDoseNotChecked(String answer, boolean questionStatesADose, DrugReferenceLoad.Coverage doseCeilings) {
		if (!questionStatesADose || answer == null || doseCeilings == null
				|| doseCeilings == DrugReferenceLoad.Coverage.PUBLISHED || answer.contains(NOT_CHECKED)) {
			return answer;
		}
		return (DrugSafetyValidator.endSentence(answer.trim()) + " " + NOT_CHECKED).trim();
	}

	/** As {@link #withDoseNotChecked}, on a line of its own: the module's composed lines end in markers (ADR Decision 322). */
	static String withDoseNotCheckedOnALine(String answer, boolean questionStatesADose, DrugReferenceLoad.Coverage doseCeilings) {
		if (!questionStatesADose || answer == null || doseCeilings == null
				|| doseCeilings == DrugReferenceLoad.Coverage.PUBLISHED || answer.contains(NOT_CHECKED)) {
			return answer;
		}
		return answer.trim() + "\n" + NOT_CHECKED;
	}
}
