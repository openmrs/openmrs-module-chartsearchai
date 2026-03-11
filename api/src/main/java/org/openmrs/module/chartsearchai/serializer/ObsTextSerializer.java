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

import java.text.SimpleDateFormat;
import java.util.Date;

import org.openmrs.ConceptNumeric;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.springframework.stereotype.Component;

/**
 * Serializes an {@link Obs} into embedding-friendly text. Enriches with encounter context
 * (type and date) so embeddings capture when and where the observation was recorded.
 *
 * <p>Example output: {@code "Outpatient Visit (2024-01-15) - Systolic Blood Pressure: 120 mmHg
 * (ABNORMAL). Note: Taken after exercise"}</p>
 */
@Component
public class ObsTextSerializer implements ClinicalTextSerializer<Obs> {

	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

	@Override
	public String toText(Obs obs) {
		StringBuilder sb = new StringBuilder();

		// Enrich with encounter context
		Encounter enc = obs.getEncounter();
		if (enc != null) {
			if (enc.getEncounterType() != null) {
				sb.append(enc.getEncounterType().getName());
			} else {
				sb.append("Encounter");
			}
			sb.append(" (").append(formatDate(enc.getEncounterDatetime())).append(") - ");
		}

		String conceptName = ConceptNameUtil.getName(obs.getConcept());
		if (!conceptName.isEmpty()) {
			sb.append(conceptName).append(": ");
		}
		sb.append(formatValue(obs));

		if (obs.getInterpretation() != null) {
			sb.append(" (").append(obs.getInterpretation()).append(")");
		}
		if (obs.getComment() != null && !obs.getComment().trim().isEmpty()) {
			sb.append(". Note: ").append(obs.getComment().trim());
		}

		// Flatten group members into parent text
		if (obs.hasGroupMembers()) {
			for (Obs member : obs.getGroupMembers()) {
				sb.append("; ");
				String memberName = ConceptNameUtil.getName(member.getConcept());
				if (!memberName.isEmpty()) {
					sb.append(memberName).append(": ");
				}
				sb.append(formatValue(member));
				if (member.getInterpretation() != null) {
					sb.append(" (").append(member.getInterpretation()).append(")");
				}
			}
		}

		return sb.toString();
	}

	private String formatValue(Obs obs) {
		if (obs.getValueCoded() != null) {
			return ConceptNameUtil.getName(obs.getValueCoded());
		}
		if (obs.getValueNumeric() != null) {
			String units = getUnits(obs.getConcept());
			return obs.getValueNumeric() + (units != null ? " " + units : "");
		}
		if (obs.getValueText() != null) {
			return obs.getValueText();
		}
		if (obs.getValueDatetime() != null) {
			return formatDate(obs.getValueDatetime());
		}
		if (obs.getValueDrug() != null) {
			return obs.getValueDrug().getName();
		}
		return "";
	}

	private String getUnits(org.openmrs.Concept concept) {
		if (concept instanceof ConceptNumeric) {
			return ((ConceptNumeric) concept).getUnits();
		}
		return null;
	}

	private String formatDate(Date date) {
		if (date == null) {
			return "unknown";
		}
		synchronized (DATE_FORMAT) {
			return DATE_FORMAT.format(date);
		}
	}
}
