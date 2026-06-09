/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import java.util.LinkedHashSet;
import java.util.Set;

import org.openmrs.Allergy;
import org.openmrs.Concept;
import org.openmrs.ConceptMap;
import org.openmrs.ConceptName;
import org.openmrs.Condition;
import org.openmrs.DrugOrder;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a {@link PatientClinicalContext} from a live {@code Patient} by reading
 * the OpenMRS service layer. Isolated from {@link PatientClinicalContext} (a pure
 * value object) so the matching/validation logic can be unit-tested without a
 * running OpenMRS context.
 *
 * <p>Every read is best-effort and individually guarded: a missing or failing
 * service degrades that one dimension to empty rather than failing the whole
 * query. The drug-reference feature is an additive safety net — its inputs being
 * incomplete must never break the answer path.
 */
final class PatientClinicalContextBuilder {

	private static final Logger log = LoggerFactory.getLogger(PatientClinicalContextBuilder.class);

	private PatientClinicalContextBuilder() {
	}

	static PatientClinicalContext build(Patient patient) {
		Integer age = null;
		Set<String> drugNames = new LinkedHashSet<String>();
		Set<String> atcCodes = new LinkedHashSet<String>();
		Set<String> allergyTokens = new LinkedHashSet<String>();
		Set<String> conditionTokens = new LinkedHashSet<String>();

		if (patient == null) {
			return new PatientClinicalContext(null, drugNames, atcCodes, allergyTokens, conditionTokens);
		}

		try {
			age = patient.getAge();
		}
		catch (RuntimeException e) {
			log.debug("Could not read patient age for drug-reference context", e);
		}

		// Active drug orders -> names + ATC codes (for interaction checks and order-driven injection).
		try {
			for (Order order : Context.getOrderService().getActiveOrders(patient, null, null, null)) {
				if (!(order instanceof DrugOrder)) {
					continue;
				}
				DrugOrder drugOrder = (DrugOrder) order;
				addDrugName(drugNames, drugOrder);
				Concept concept = drugOrder.getConcept();
				if (drugOrder.getDrug() != null && drugOrder.getDrug().getConcept() != null) {
					concept = drugOrder.getDrug().getConcept();
				}
				addAtcCodes(atcCodes, concept);
			}
		}
		catch (RuntimeException e) {
			log.debug("Could not read active orders for drug-reference context", e);
		}

		// Active allergies -> allergen tokens (for contraindication checks).
		try {
			for (Allergy allergy : Context.getPatientService().getAllergies(patient)) {
				if (allergy.getAllergen() != null) {
					addConceptName(allergyTokens, allergy.getAllergen().getCodedAllergen());
					addRaw(allergyTokens, allergy.getAllergen().getNonCodedAllergen());
				}
			}
		}
		catch (RuntimeException e) {
			log.debug("Could not read allergies for drug-reference context", e);
		}

		// Active conditions -> condition tokens (for contraindication checks).
		try {
			for (Condition condition : Context.getConditionService().getActiveConditions(patient)) {
				if (condition.getCondition() == null) {
					continue;
				}
				addConceptName(conditionTokens, condition.getCondition().getCoded());
				addRaw(conditionTokens, condition.getCondition().getNonCoded());
			}
		}
		catch (RuntimeException e) {
			log.debug("Could not read conditions for drug-reference context", e);
		}

		return new PatientClinicalContext(age, drugNames, atcCodes, allergyTokens, conditionTokens);
	}

	private static void addDrugName(Set<String> names, DrugOrder drugOrder) {
		if (drugOrder.getDrug() != null && drugOrder.getDrug().getName() != null) {
			addRaw(names, drugOrder.getDrug().getName());
		}
		addConceptName(names, drugOrder.getConcept());
	}

	private static void addConceptName(Set<String> tokens, Concept concept) {
		if (concept == null) {
			return;
		}
		try {
			ConceptName name = concept.getName();
			if (name != null) {
				addRaw(tokens, name.getName());
			}
		}
		catch (RuntimeException e) {
			log.debug("Could not read concept name", e);
		}
	}

	private static void addAtcCodes(Set<String> atcCodes, Concept concept) {
		if (concept == null) {
			return;
		}
		try {
			for (ConceptMap map : concept.getConceptMappings()) {
				if (map.getConceptReferenceTerm() == null
						|| map.getConceptReferenceTerm().getConceptSource() == null) {
					continue;
				}
				String source = map.getConceptReferenceTerm().getConceptSource().getName();
				if (source != null && source.toUpperCase(java.util.Locale.ROOT).contains("ATC")) {
					addRaw(atcCodes, map.getConceptReferenceTerm().getCode());
				}
			}
		}
		catch (RuntimeException e) {
			log.debug("Could not read concept mappings for ATC codes", e);
		}
	}

	private static void addRaw(Set<String> set, String value) {
		if (value != null && !value.trim().isEmpty()) {
			set.add(value.trim());
		}
	}
}
