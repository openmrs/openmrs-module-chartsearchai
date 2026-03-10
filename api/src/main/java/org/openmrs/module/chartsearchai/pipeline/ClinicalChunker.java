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
import org.openmrs.ConceptNumeric;
import org.openmrs.Condition;
import org.openmrs.Diagnosis;
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
 * <p>Chunking strategy: one chunk per clinical event (encounter, condition, diagnosis) rather than
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

	/**
	 * Generate text chunks for a single encounter. Used for incremental indexing when new
	 * encounters are created or updated.
	 *
	 * @param encounter the encounter to chunk
	 * @param patientUuid the patient's UUID for metadata
	 * @return list of text chunks for this encounter
	 */
	public List<TextChunk> chunkEncounter(Encounter encounter, String patientUuid) {
		List<TextChunk> chunks = new ArrayList<>();
		chunks.add(buildEncounterChunk(encounter, patientUuid));
		return chunks;
	}

	private List<TextChunk> chunkEncounters(Patient patient, String patientUuid) {
		List<TextChunk> chunks = new ArrayList<>();
		List<Encounter> encounters = extractor.getEncounters(patient);

		for (Encounter encounter : encounters) {
			chunks.add(buildEncounterChunk(encounter, patientUuid));
		}
		return chunks;
	}

	private TextChunk buildEncounterChunk(Encounter encounter, String patientUuid) {
		StringBuilder text = new StringBuilder();
		text.append("Encounter on ").append(formatDate(encounter.getEncounterDatetime()));

		if (encounter.getEncounterType() != null) {
			text.append(" | Type: ").append(encounter.getEncounterType().getName());
		}
		if (encounter.getLocation() != null) {
			text.append(" | Location: ").append(encounter.getLocation().getName());
		}
		text.append("\n");

		// Observations with richer formatting
		for (Obs obs : encounter.getAllObs()) {
			if (obs.getObsGroup() != null) {
				continue; // Skip grouped obs; they're included under their parent
			}
			text.append("  - ").append(getConceptName(obs.getConcept()));
			text.append(": ").append(formatObsValue(obs));

			if (obs.getInterpretation() != null) {
				text.append(" (").append(obs.getInterpretation()).append(")");
			}
			if (obs.getComment() != null && !obs.getComment().trim().isEmpty()) {
				text.append(". Note: ").append(obs.getComment().trim());
			}
			text.append("\n");

			// Include group members inline
			if (obs.hasGroupMembers()) {
				for (Obs member : obs.getGroupMembers()) {
					text.append("    - ").append(getConceptName(member.getConcept()));
					text.append(": ").append(formatObsValue(member));
					if (member.getInterpretation() != null) {
						text.append(" (").append(member.getInterpretation()).append(")");
					}
					text.append("\n");
				}
			}
		}

		// Diagnoses made during this encounter
		if (encounter.getDiagnoses() != null && !encounter.getDiagnoses().isEmpty()) {
			text.append("  Diagnoses:\n");
			for (Diagnosis diagnosis : encounter.getDiagnoses()) {
				text.append("    - ").append(getDiagnosisName(diagnosis));
				if (diagnosis.getCertainty() != null) {
					text.append(" (").append(diagnosis.getCertainty()).append(")");
				}
				text.append(diagnosis.getRank() == 1 ? " [Primary]" : " [Secondary]");
				text.append("\n");
			}
		}

		return new TextChunk(text.toString(),
				new ChunkMetadata("encounter", encounter.getUuid(),
						encounter.getEncounterDatetime(), patientUuid));
	}

	private List<TextChunk> chunkConditions(Patient patient, String patientUuid) {
		List<TextChunk> chunks = new ArrayList<>();
		List<Condition> conditions = extractor.getActiveConditions(patient);

		if (conditions.isEmpty()) {
			return chunks;
		}

		// Create individual chunks per condition for better retrieval granularity
		for (Condition condition : conditions) {
			StringBuilder text = new StringBuilder();
			text.append("Condition: ").append(getConditionName(condition));
			text.append(". Status: ").append(condition.getClinicalStatus());

			if (condition.getVerificationStatus() != null) {
				text.append(". Verification: ").append(condition.getVerificationStatus());
			}
			if (condition.getOnsetDate() != null) {
				text.append(". Onset: ").append(formatDate(condition.getOnsetDate()));
			}
			if (condition.getAdditionalDetail() != null && !condition.getAdditionalDetail().trim().isEmpty()) {
				text.append(". Detail: ").append(condition.getAdditionalDetail().trim());
			}
			if (condition.getEndDate() != null) {
				text.append(". Resolved: ").append(formatDate(condition.getEndDate()));
				if (condition.getEndReason() != null && !condition.getEndReason().trim().isEmpty()) {
					text.append(" (").append(condition.getEndReason().trim()).append(")");
				}
			}

			chunks.add(new TextChunk(text.toString(),
					new ChunkMetadata("condition", condition.getUuid(),
							condition.getOnsetDate(), patientUuid)));
		}

		return chunks;
	}

	private List<TextChunk> chunkAllergies(Patient patient, String patientUuid) {
		List<TextChunk> chunks = new ArrayList<>();
		List<Allergy> allergies = extractor.getAllergies(patient);

		if (allergies.isEmpty()) {
			return chunks;
		}

		// Create individual chunks per allergy for better retrieval
		for (Allergy allergy : allergies) {
			StringBuilder text = new StringBuilder();
			text.append("Allergy: ").append(allergy.getAllergen().toString());

			if (allergy.getAllergen().getAllergenType() != null) {
				text.append(" (").append(allergy.getAllergen().getAllergenType()).append(")");
			}

			List<AllergyReaction> reactions = allergy.getReactions();
			if (reactions != null && !reactions.isEmpty()) {
				text.append(". Reactions: ");
				for (int i = 0; i < reactions.size(); i++) {
					if (i > 0) {
						text.append(", ");
					}
					AllergyReaction reaction = reactions.get(i);
					if (reaction.getReaction() != null && reaction.getReaction().getName() != null) {
						text.append(reaction.getReaction().getName().getName());
					} else if (reaction.getReactionNonCoded() != null) {
						text.append(reaction.getReactionNonCoded());
					}
				}
			}
			if (allergy.getSeverity() != null && allergy.getSeverity().getName() != null) {
				text.append(". Severity: ").append(allergy.getSeverity().getName().getName());
			}
			if (allergy.getComments() != null && !allergy.getComments().trim().isEmpty()) {
				text.append(". Comments: ").append(allergy.getComments().trim());
			}

			chunks.add(new TextChunk(text.toString(),
					new ChunkMetadata("allergy", allergy.getUuid(), null, patientUuid)));
		}

		return chunks;
	}

	private List<TextChunk> chunkMedications(Patient patient, String patientUuid) {
		List<TextChunk> chunks = new ArrayList<>();
		List<Order> orders = extractor.getOrders(patient);

		for (Order order : orders) {
			if (!(order instanceof DrugOrder)) {
				continue;
			}
			DrugOrder drugOrder = (DrugOrder) order;

			StringBuilder text = new StringBuilder();
			text.append(drugOrder.isActive() ? "Active Medication: " : "Past Medication: ");

			if (drugOrder.getDrug() != null) {
				text.append(drugOrder.getDrug().getName());
			} else {
				text.append(getConceptName(drugOrder.getConcept()));
			}
			if (drugOrder.getDose() != null) {
				text.append(". Dose: ").append(drugOrder.getDose());
				if (drugOrder.getDoseUnits() != null) {
					text.append(" ").append(getConceptName(drugOrder.getDoseUnits()));
				}
			}
			if (drugOrder.getFrequency() != null) {
				text.append(". Frequency: ").append(drugOrder.getFrequency().toString());
			}
			if (drugOrder.getRoute() != null) {
				text.append(". Route: ").append(getConceptName(drugOrder.getRoute()));
			}
			if (drugOrder.getDateActivated() != null) {
				text.append(". Started: ").append(formatDate(drugOrder.getDateActivated()));
			}
			if (drugOrder.getDateStopped() != null) {
				text.append(". Stopped: ").append(formatDate(drugOrder.getDateStopped()));
			}
			if (drugOrder.getOrderReason() != null) {
				text.append(". Reason: ").append(getConceptName(drugOrder.getOrderReason()));
			} else if (drugOrder.getOrderReasonNonCoded() != null
					&& !drugOrder.getOrderReasonNonCoded().trim().isEmpty()) {
				text.append(". Reason: ").append(drugOrder.getOrderReasonNonCoded().trim());
			}
			if (drugOrder.getInstructions() != null && !drugOrder.getInstructions().trim().isEmpty()) {
				text.append(". Instructions: ").append(drugOrder.getInstructions().trim());
			}

			chunks.add(new TextChunk(text.toString(),
					new ChunkMetadata("medication", drugOrder.getUuid(),
							drugOrder.getDateActivated(), patientUuid)));
		}

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
				String units = getConceptUnits(obs.getConcept());
				if (units != null) {
					text.append(" ").append(units);
				}
				if (obs.getInterpretation() != null) {
					text.append(" (").append(obs.getInterpretation()).append(")");
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
			String units = getConceptUnits(obs.getConcept());
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
		if (obs.getValueDrug() != null) {
			return obs.getValueDrug().getName();
		}
		return obs.getValueAsString(null);
	}

	private String getConceptUnits(Concept concept) {
		if (concept instanceof ConceptNumeric) {
			return ((ConceptNumeric) concept).getUnits();
		}
		return null;
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

	private String getDiagnosisName(Diagnosis diagnosis) {
		if (diagnosis.getDiagnosis() == null) {
			return "Unknown";
		}
		if (diagnosis.getDiagnosis().getCoded() != null) {
			return getConceptName(diagnosis.getDiagnosis().getCoded());
		}
		if (diagnosis.getDiagnosis().getNonCoded() != null) {
			return diagnosis.getDiagnosis().getNonCoded();
		}
		return "Unknown";
	}

	private String getConditionName(Condition condition) {
		if (condition.getCondition() == null) {
			return "Unknown";
		}
		if (condition.getCondition().getCoded() != null) {
			return getConceptName(condition.getCondition().getCoded());
		}
		if (condition.getCondition().getNonCoded() != null) {
			return condition.getCondition().getNonCoded();
		}
		return "Unknown";
	}
}
