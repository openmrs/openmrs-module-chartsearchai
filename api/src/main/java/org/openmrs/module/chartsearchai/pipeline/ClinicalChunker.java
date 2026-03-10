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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.Allergy;
import org.openmrs.AllergyReaction;
import org.openmrs.Concept;
import org.openmrs.Condition;
import org.openmrs.DrugOrder;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.model.ChunkMetadata;
import org.openmrs.module.chartsearchai.model.TextChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Converts structured OpenMRS clinical data into semantically meaningful text chunks. Each chunk is
 * self-contained and represents a single clinical event or concept group, making it suitable for
 * vector embedding and retrieval.
 *
 * <p>Chunking strategy: one chunk per clinical event (encounter, medication change) rather than
 * fixed-size text splitting, because clinical meaning is tied to events, not character counts.</p>
 */
@Component("chartsearchai.clinicalChunker")
public class ClinicalChunker {

	private static final Logger log = LoggerFactory.getLogger(ClinicalChunker.class);

	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

	@Autowired
	private PatientDataExtractor extractor;

	/**
	 * Generate all text chunks for a patient's clinical record.
	 *
	 * @param patient the patient whose data should be chunked
	 * @return list of text chunks ready for embedding
	 */
	public List<TextChunk> chunkPatientData(Patient patient) {
		String patientUuid = patient.getUuid();
		List<TextChunk> chunks = new ArrayList<>();

		chunks.addAll(chunkEncounters(patient, patientUuid));
		chunks.addAll(chunkConditions(patient, patientUuid));
		chunks.addAll(chunkAllergies(patient, patientUuid));
		chunks.addAll(chunkMedications(patient, patientUuid));
		chunks.addAll(chunkLabTrends(patient, patientUuid));

		log.info("Generated {} chunks for patient {}", chunks.size(), patientUuid);
		return chunks;
	}

	private List<TextChunk> chunkEncounters(Patient patient, String patientUuid) {
		List<TextChunk> chunks = new ArrayList<>();
		List<Encounter> encounters = extractor.getEncounters(patient);

		for (Encounter encounter : encounters) {
			StringBuilder text = new StringBuilder();
			text.append("Encounter on ").append(formatDate(encounter.getEncounterDatetime()));

			if (encounter.getEncounterType() != null) {
				text.append(" | Type: ").append(encounter.getEncounterType().getName());
			}
			if (encounter.getLocation() != null) {
				text.append(" | Location: ").append(encounter.getLocation().getName());
			}
			text.append("\n");

			for (Obs obs : encounter.getAllObs()) {
				text.append("  - ").append(getConceptName(obs.getConcept()));
				text.append(": ").append(formatObsValue(obs));
				text.append("\n");
			}

			chunks.add(new TextChunk(text.toString(),
					new ChunkMetadata("encounter", encounter.getUuid(),
							encounter.getEncounterDatetime(), patientUuid)));
		}
		return chunks;
	}

	private List<TextChunk> chunkConditions(Patient patient, String patientUuid) {
		List<TextChunk> chunks = new ArrayList<>();
		List<Condition> conditions = extractor.getActiveConditions(patient);

		if (conditions.isEmpty()) {
			return chunks;
		}

		StringBuilder text = new StringBuilder("Active Conditions:\n");
		for (Condition condition : conditions) {
			text.append("  - ").append(getConceptName(condition.getCondition().getCoded()));
			if (condition.getOnsetDate() != null) {
				text.append(" (onset: ").append(formatDate(condition.getOnsetDate())).append(")");
			}
			text.append("\n");
		}

		chunks.add(new TextChunk(text.toString(),
				new ChunkMetadata("conditions", null, null, patientUuid)));
		return chunks;
	}

	private List<TextChunk> chunkAllergies(Patient patient, String patientUuid) {
		List<TextChunk> chunks = new ArrayList<>();
		List<Allergy> allergies = extractor.getAllergies(patient);

		if (allergies.isEmpty()) {
			return chunks;
		}

		StringBuilder text = new StringBuilder("Allergies:\n");
		for (Allergy allergy : allergies) {
			text.append("  - ").append(allergy.getAllergen().toString());

			List<AllergyReaction> reactions = allergy.getReactions();
			if (reactions != null && !reactions.isEmpty()) {
				text.append(" | Reactions: ");
				for (int i = 0; i < reactions.size(); i++) {
					if (i > 0) {
						text.append(", ");
					}
					text.append(reactions.get(i).getReaction().getName().getName());
				}
			}
			if (allergy.getSeverity() != null) {
				text.append(" | Severity: ").append(allergy.getSeverity().getName().getName());
			}
			text.append("\n");
		}

		chunks.add(new TextChunk(text.toString(),
				new ChunkMetadata("allergies", null, null, patientUuid)));
		return chunks;
	}

	private List<TextChunk> chunkMedications(Patient patient, String patientUuid) {
		List<TextChunk> chunks = new ArrayList<>();
		List<Order> orders = extractor.getOrders(patient);

		List<DrugOrder> activeDrugOrders = new ArrayList<>();
		for (Order order : orders) {
			if (order instanceof DrugOrder && order.isActive()) {
				activeDrugOrders.add((DrugOrder) order);
			}
		}

		if (activeDrugOrders.isEmpty()) {
			return chunks;
		}

		StringBuilder text = new StringBuilder("Current Medications:\n");
		for (DrugOrder drugOrder : activeDrugOrders) {
			text.append("  - ");
			if (drugOrder.getDrug() != null) {
				text.append(drugOrder.getDrug().getName());
			} else {
				text.append(getConceptName(drugOrder.getConcept()));
			}
			if (drugOrder.getDose() != null) {
				text.append(" | Dose: ").append(drugOrder.getDose());
				if (drugOrder.getDoseUnits() != null) {
					text.append(" ").append(getConceptName(drugOrder.getDoseUnits()));
				}
			}
			if (drugOrder.getFrequency() != null) {
				text.append(" | Frequency: ").append(drugOrder.getFrequency().toString());
			}
			if (drugOrder.getDateActivated() != null) {
				text.append(" | Since: ").append(formatDate(drugOrder.getDateActivated()));
			}
			text.append("\n");
		}

		chunks.add(new TextChunk(text.toString(),
				new ChunkMetadata("medications", null, null, patientUuid)));
		return chunks;
	}

	private List<TextChunk> chunkLabTrends(Patient patient, String patientUuid) {
		List<TextChunk> chunks = new ArrayList<>();
		List<Obs> allObs = extractor.getObservations(patient);

		// Group numeric observations by concept to form lab trends
		Map<Integer, List<Obs>> labGroups = new HashMap<>();
		for (Obs obs : allObs) {
			if (obs.getValueNumeric() != null && obs.getConcept() != null) {
				Integer conceptId = obs.getConcept().getConceptId();
				if (!labGroups.containsKey(conceptId)) {
					labGroups.put(conceptId, new ArrayList<>());
				}
				labGroups.get(conceptId).add(obs);
			}
		}

		for (Map.Entry<Integer, List<Obs>> entry : labGroups.entrySet()) {
			List<Obs> obsList = entry.getValue();
			if (obsList.size() < 2) {
				continue; // Only create trend chunks for repeated labs
			}

			String conceptName = getConceptName(obsList.get(0).getConcept());
			StringBuilder text = new StringBuilder("Lab Trend - " + conceptName + ":\n");
			for (Obs obs : obsList) {
				text.append("  ").append(formatDate(obs.getObsDatetime()));
				text.append(": ").append(obs.getValueNumeric());
				if (obs.getConcept().getUnits() != null) {
					text.append(" ").append(obs.getConcept().getUnits());
				}
				text.append("\n");
			}

			chunks.add(new TextChunk(text.toString(),
					new ChunkMetadata("lab_trend", conceptName, null, patientUuid)));
		}

		return chunks;
	}

	private String formatDate(Date date) {
		if (date == null) {
			return "unknown";
		}
		synchronized (DATE_FORMAT) {
			return DATE_FORMAT.format(date);
		}
	}

	private String formatObsValue(Obs obs) {
		if (obs.getValueNumeric() != null) {
			String units = obs.getConcept().getUnits();
			return obs.getValueNumeric() + (units != null ? " " + units : "");
		}
		if (obs.getValueCoded() != null) {
			return getConceptName(obs.getValueCoded());
		}
		if (obs.getValueText() != null) {
			return obs.getValueText();
		}
		if (obs.getValueDatetime() != null) {
			return formatDate(obs.getValueDatetime());
		}
		return obs.getValueAsString(null);
	}

	private String getConceptName(Concept concept) {
		if (concept == null) {
			return "Unknown";
		}
		if (concept.getName() != null) {
			return concept.getName().getName();
		}
		return "Concept:" + concept.getConceptId();
	}
}
