/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.pipeline;

import java.util.List;

import org.openmrs.Allergy;
import org.openmrs.Condition;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Extracts clinical data for a patient from the OpenMRS data model. This is the first stage of the
 * RAG pipeline: pulling structured data that will be converted into text chunks for embedding.
 */
@Component("chartsearchai.patientDataExtractor")
public class PatientDataExtractor {

	private static final Logger log = LoggerFactory.getLogger(PatientDataExtractor.class);

	/**
	 * Extract all encounters for a patient, ordered by date.
	 */
	public List<Encounter> getEncounters(Patient patient) {
		log.debug("Extracting encounters for patient {}", patient.getPatientId());
		return Context.getEncounterService().getEncountersByPatient(patient);
	}

	/**
	 * Extract all observations for a patient.
	 */
	public List<Obs> getObservations(Patient patient) {
		log.debug("Extracting observations for patient {}", patient.getPatientId());
		return Context.getObsService().getObservationsByPerson(patient);
	}

	/**
	 * Extract active conditions for a patient.
	 */
	public List<Condition> getActiveConditions(Patient patient) {
		log.debug("Extracting active conditions for patient {}", patient.getPatientId());
		return Context.getConditionService().getActiveConditions(patient);
	}

	/**
	 * Extract all allergies for a patient.
	 */
	public List<Allergy> getAllergies(Patient patient) {
		log.debug("Extracting allergies for patient {}", patient.getPatientId());
		return Context.getPatientService().getAllergies(patient);
	}

	/**
	 * Extract all orders (medications, labs, etc.) for a patient.
	 */
	public List<Order> getOrders(Patient patient) {
		log.debug("Extracting orders for patient {}", patient.getPatientId());
		return Context.getOrderService().getAllOrdersByPatient(patient);
	}
}
