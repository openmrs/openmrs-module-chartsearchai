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
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the answer cites a safety finding whose record says the interaction's clinical significance is unknown,
 * while saying nothing of the kind itself — ADR Decision 136, issue #566 option 3, published as
 * {@code unstatedSignificanceQualifiers}. Asked <em>"Is aspirin safe for her?"</em>, the answer reproduced the
 * aspirin/metoclopramide finding's detail and dropped its last sentence, <em>"The clinical significance of this
 * interaction is unknown."</em>, and no fidelity check reported it.
 *
 * <p>The findings are the answer's CITED ones, {@link SafetyFindingCitationExtentCheck#citedFindingIndexes}, the one
 * reading of them. A qualifier is {@link ChartSearchAiUtils#UNKNOWN_SIGNIFICANCE}. The answer states it where any of its text matches
 * that same pattern, anywhere: one statement is read as covering every cited finding, so an answer qualifying one
 * finding and dropping another's reports neither, the conservative direction for a report.
 */
final class SignificanceQualifierCheck {

	private static final Logger log = LoggerFactory.getLogger(SignificanceQualifierCheck.class);


	private SignificanceQualifierCheck() {
	}

	/**
	 * @return the cited findings' citation indexes, ascending, whose record carries the qualifier the answer never
	 *         states; empty where there are none; {@code null} — no measurement — where the check throws
	 */
	static List<Integer> report(Patient patient, String answer, List<RecordReference> cited,
			List<RecordMapping> mappings) {
		try {
			List<Integer> unstated = new ArrayList<Integer>();
			if (ChartSearchAiUtils.isBlank(answer) || ChartSearchAiUtils.UNKNOWN_SIGNIFICANCE.matcher(answer).find()) {
				return unstated;
			}
			Set<Integer> citedFindings = SafetyFindingCitationExtentCheck.citedFindingIndexes(answer, cited, mappings);
			for (RecordMapping finding : ChartSearchAiUtils.safetyFindingMappings(mappings)) {
				if (citedFindings.contains(finding.getIndex()) && finding.getText() != null
						&& ChartSearchAiUtils.UNKNOWN_SIGNIFICANCE.matcher(finding.getText()).find()) {
					unstated.add(finding.getIndex());
				}
			}
			Collections.sort(unstated);
			if (!unstated.isEmpty()) {
				log.warn("Answer drops a cited finding's unknown-significance qualifier (ADR Decision 136) "
						+ "patient={} findings={}", patient == null ? null : patient.getPatientId(), unstated.size());
			}
			return unstated;
		}
		catch (RuntimeException e) {
			log.warn("Significance-qualifier check failed; stating no measurement", e);
			return null;
		}
	}
}
