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

import org.openmrs.Diagnosis;
import org.springframework.stereotype.Component;

/**
 * Serializes a {@link Diagnosis} into embedding-friendly text.
 *
 * <p>Example output: {@code "Diagnosis: Malaria. Certainty: CONFIRMED. Rank: Primary.
 * Date: 2024-01-15"}</p>
 */
@Component
public class DiagnosisTextSerializer implements ClinicalTextSerializer<Diagnosis> {

	@Override
	public String toText(Diagnosis diagnosis) {
		StringBuilder sb = new StringBuilder();
		String name = getDiagnosisName(diagnosis);
		if (!name.isEmpty()) {
			sb.append("Diagnosis: ").append(name);
		}

		if (diagnosis.getCertainty() != null) {
			sb.append(". Certainty: ").append(diagnosis.getCertainty());
		}
		sb.append(". Rank: ").append(diagnosis.getRank() == 1 ? "Primary" : "Secondary");

		if (diagnosis.getEncounter() != null && diagnosis.getEncounter().getEncounterDatetime() != null) {
			sb.append(". Date: ").append(DateFormatUtil.formatDate(diagnosis.getEncounter().getEncounterDatetime()));
		}

		return sb.toString();
	}

	private String getDiagnosisName(Diagnosis diagnosis) {
		if (diagnosis.getDiagnosis() == null) {
			return "";
		}
		if (diagnosis.getDiagnosis().getCoded() != null) {
			return ConceptNameUtil.getName(diagnosis.getDiagnosis().getCoded());
		}
		if (diagnosis.getDiagnosis().getNonCoded() != null
				&& !diagnosis.getDiagnosis().getNonCoded().trim().isEmpty()) {
			return diagnosis.getDiagnosis().getNonCoded().trim();
		}
		return "";
	}

}
