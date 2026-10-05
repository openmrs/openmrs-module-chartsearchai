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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * The findings about the drug the question proposes against one of this patient's own orders that a model's answer
 * does not cite, stated after it — ADR Decision 147. Asked <em>"The patient is currently on Abacavir, Lopinavir /
 * ritonavir, Didanosine and Trimethoprim and sulfamethoxazole is it safe to give Fluconazole?"</em>, the model
 * summarised three interactions with drugs her chart does not hold and left out the one Moderate interaction with her
 * own lidocaine order. Decision 90 lets the prose summarise, the client drawing every finding; this keeps the finding
 * about her own chart in the answer itself.
 *
 * <p>The lines are the injector's ({@code PatientChart.getProposalOwnOrderFindingLines()}), written once from the
 * finding; which of them the answer cites is {@link SafetyFindingCitationExtentCheck#citedFindingIndexes}, the one
 * reading of that. It never rewrites what the model wrote.
 */
final class OwnOrderFindingStatement {

	/** What the appended sentence opens with. */
	static final String LEAD = "Not stated above, against this patient's own orders:";

	private OwnOrderFindingStatement() {
	}

	/**
	 * @return {@code answer} with the lines of {@code lines} whose finding {@code answer} does not cite appended after
	 *         {@link #LEAD}, each with its own record number, or {@code answer} unchanged where it cites them all or
	 *         is blank
	 */
	static String withUnstatedOwnOrderFindings(String answer, List<RecordReference> cited, List<RecordMapping> mappings,
			Map<Integer, String> lines) {
		// A blank answer is the model's degenerate output and reaches the caller as it was, as the repair pass leaves
		// it (FindingEnumerationRepairTest.aBlankAnswerIsNotRepairedAtAll): what it cites is read off the structured
		// array, so appending to it would be a lead the module wrote.
		if (answer == null || answer.trim().isEmpty() || lines == null || lines.isEmpty()) {
			return answer;
		}
		Set<Integer> stated = SafetyFindingCitationExtentCheck.citedFindingIndexes(answer, cited, mappings);
		List<String> unstated = new ArrayList<String>();
		for (Map.Entry<Integer, String> line : lines.entrySet()) {
			if (!stated.contains(line.getKey())) {
				// The marker inside the sentence, before its full stop, so a sentence appended after this one does not
				// end it a second time.
				String sentence = line.getValue().trim();
				String body = sentence.endsWith(".") ? sentence.substring(0, sentence.length() - 1) : sentence;
				unstated.add(body + " [" + line.getKey() + "].");
			}
		}
		if (unstated.isEmpty()) {
			return answer;
		}
		return (DrugSafetyValidator.endSentence(answer.trim()) + " " + LEAD + " " + String.join(" ", unstated)).trim();
	}
}
