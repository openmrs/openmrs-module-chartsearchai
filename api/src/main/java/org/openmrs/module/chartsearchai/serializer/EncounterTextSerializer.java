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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

import org.openmrs.Diagnosis;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Serializes an {@link Encounter} into embedding-friendly text. Produces a summary of the
 * encounter including type, location, all observations, and diagnoses.
 *
 * <p>Example output: {@code "Outpatient Visit (2024-01-15) | Location: Main Clinic.
 * Observations: Systolic Blood Pressure: 120 mmHg; Weight: 72.5 kg. Diagnoses: Malaria
 * (CONFIRMED) [Primary]"}</p>
 */
@Component
public class EncounterTextSerializer implements ClinicalTextSerializer<Encounter> {

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	@Autowired
	private ObsTextSerializer obsSerializer;

	@Autowired
	private DiagnosisTextSerializer diagnosisSerializer;

	@Override
	public String toText(Encounter encounter) {
		StringBuilder sb = new StringBuilder();

		if (encounter.getEncounterType() != null) {
			sb.append(encounter.getEncounterType().getName());
		} else {
			sb.append("Encounter");
		}
		sb.append(" (").append(formatDate(encounter.getEncounterDatetime())).append(")");

		if (encounter.getLocation() != null) {
			sb.append(" | Location: ").append(encounter.getLocation().getName());
		}

		if (!encounter.getAllObs().isEmpty()) {
			sb.append(". Observations: ");
			boolean first = true;
			for (Obs obs : encounter.getAllObs()) {
				if (obs.getObsGroup() != null) {
					continue;
				}
				if (!first) {
					sb.append("; ");
				}
				sb.append(obsSerializer.toText(obs));
				first = false;
			}
		}

		if (encounter.getDiagnoses() != null && !encounter.getDiagnoses().isEmpty()) {
			sb.append(". Diagnoses: ");
			boolean first = true;
			for (Diagnosis diagnosis : encounter.getDiagnoses()) {
				if (!first) {
					sb.append("; ");
				}
				sb.append(diagnosisSerializer.toText(diagnosis));
				first = false;
			}
		}

		return sb.toString();
	}

	private String formatDate(Date date) {
		if (date == null) {
			return "unknown";
		}
		return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_FORMAT);
	}
}
