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

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.openmrs.Allergy;
import org.openmrs.Concept;
import org.openmrs.ConceptMap;
import org.openmrs.ConceptName;
import org.openmrs.Condition;
import org.openmrs.DrugOrder;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
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

	private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;

	private PatientClinicalContextBuilder() {
	}

	static PatientClinicalContext build(Patient patient) {
		Integer age = null;
		Double weightKg = null;
		Set<String> drugNames = new LinkedHashSet<String>();
		Set<String> atcCodes = new LinkedHashSet<String>();
		Set<String> allergyTokens = new LinkedHashSet<String>();
		Set<String> conditionTokens = new LinkedHashSet<String>();
		List<PatientClinicalContext.ActiveDrugOrder> activeOrders =
				new ArrayList<PatientClinicalContext.ActiveDrugOrder>();

		if (patient == null) {
			return new PatientClinicalContext(null, null, drugNames, atcCodes, allergyTokens, conditionTokens);
		}

		try {
			age = patient.getAge();
		}
		catch (RuntimeException e) {
			log.debug("Could not read patient age for drug-reference context", e);
		}

		// Most recent (fresh) weight in kg -> weight-aware per-dose overdose check.
		try {
			weightKg = latestWeightKg(patient);
		}
		catch (RuntimeException e) {
			log.debug("Could not read patient weight for drug-reference context", e);
		}

		// Active drug orders -> names + ATC codes (for interaction checks and order-driven injection),
		// plus the orders themselves — names AND codes attributed per order, for reconciling this read
		// against the serialized chart (#118) and so the interaction screen can exclude a subject's own
		// order from witnessing it (#132).
		try {
			for (Order order : Context.getOrderService().getActiveOrders(patient, null, null, null)) {
				if (!(order instanceof DrugOrder)) {
					continue;
				}
				DrugOrder drugOrder = (DrugOrder) order;
				// Per-order names, collected BEFORE they are folded into the flattened set: the
				// reconciliation must be able to tell one order's names from another's, which the
				// flattened set (drug name AND concept name, all orders together) cannot.
				Set<String> orderNames = new LinkedHashSet<String>();
				addDrugName(orderNames, drugOrder);
				drugNames.addAll(orderNames);
				Concept concept = drugOrder.getConcept();
				if (drugOrder.getDrug() != null && drugOrder.getDrug().getConcept() != null) {
					concept = drugOrder.getDrug().getConcept();
				}
				// Per-order codes for the same reason as the per-order names above, read once off the
				// same concept: flattened, a code cannot be attributed to the order carrying it, so ONE
				// order's two codes read as two orders and the order witnesses its own interaction
				// (issue #132). The flattened union is still assembled here — the class arms and
				// findByActiveOrders want exactly that, and the nameless-order gap below contributes to
				// it without contributing an ActiveDrugOrder.
				Set<String> orderAtcCodes = new LinkedHashSet<String>();
				addAtcCodes(orderAtcCodes, concept);
				atcCodes.addAll(orderAtcCodes);
				// An order with no readable name at all is skipped: it can be neither rendered as a
				// record nor matched against chart text, and injecting it would put a nameless line
				// ("Active drug order: null") in front of a clinician.
				//
				// KNOWN GAP, to follow with the reconciliation's other corpus issue: skipping is the
				// one outcome that reproduces issue #118 rather than repairing it. addAtcCodes above
				// needs no name, and a safety chip's drug name comes from the KB entry the ATC code
				// resolves to (DrugSafetyValidator's orderPartners), not from the order — so a
				// nameless order can still raise a chip reading "as active order simvastatin" while
				// being invisible to the reconciliation that exists to substantiate it. Issue #155
				// gave that resolution a fallback to the ORDER's display name, which does not reach
				// this shape: an order skipped here never enters getActiveDrugOrders(), so there is no
				// display name to fall back to and the chip still speaks only for the KB entry. Reachable, not
				// theoretical: addConceptName swallows a RuntimeException from concept.getName() (a
				// detached/lazy-init proxy) in its own try, and addAtcCodes then runs in a separate
				// one and can still succeed; likewise a concept named only outside the current locale
				// yields a null name with its ATC mappings intact. The fix is a fallback display
				// rather than a skip, so the record can be injected with the order's real uuid.
				if (!orderNames.isEmpty()) {
					activeOrders.add(new PatientClinicalContext.ActiveDrugOrder(drugOrder.getUuid(),
							orderNames.iterator().next(), orderNames, orderAtcCodes));
				}
			}
		}
		catch (RuntimeException e) {
			log.debug("Could not read active orders for drug-reference context", e);
		}

		// Whether the two contraindication reads below actually happened. Each catch degrades its
		// dimension to an empty set, which is right for a chip and wrong for a record that would report
		// that emptiness as a fact about the patient (issue #208 item 2) — so the failure is recorded
		// rather than only logged.
		boolean contraindicationRecordsRead = true;

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
			contraindicationRecordsRead = false;
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
			contraindicationRecordsRead = false;
		}

		return new PatientClinicalContext(age, weightKg, drugNames, atcCodes, allergyTokens, conditionTokens,
				activeOrders, null, contraindicationRecordsRead);
	}

	/** The most recent positive-numeric, non-stale obs for {@code concept}, or {@code null}. Shared by
	 *  the weight and renal lookups so both apply one freshness rule and one validity rule. */
	private static Obs latestNumericObs(Patient patient, Concept concept) {
		Date cutoff = new Date(System.currentTimeMillis() - maxWeightAgeDays() * MILLIS_PER_DAY);
		Obs latest = null;
		for (Obs obs : Context.getObsService().getObservationsByPersonAndConcept(patient, concept)) {
			if (obs.getValueNumeric() == null || obs.getValueNumeric() <= 0 || obs.getObsDatetime() == null
					|| obs.getObsDatetime().before(cutoff)) {
				continue;
			}
			if (latest == null || obs.getObsDatetime().after(latest.getObsDatetime())) {
				latest = obs;
			}
		}
		return latest;
	}

	/**
	 * @return the patient's most recent weight in kg, or {@code null} when none is recorded, the
	 *         newest one is older than {@code chartsearchai.drugSafety.weightMaxAgeDays} (a stale —
	 *         typically lower — pediatric weight would over-report mg/kg, the false-positive
	 *         direction this feature never takes), the weight concept GP is set to the
	 *         {@code none} sentinel (the operator opt-out — a blanked GP reads back as null and so
	 *         falls back to the default, like every other GP), or the configured concept does not
	 *         exist in this dictionary.
	 *
	 * <p>Fetch-all-then-scan is a MEASURED decision, not an oversight: on a real MariaDB the full
	 * fetch costs ~2 ms per query even at 500 weight obs (~0.2 ms at a realistic 50) — noise
	 * against a multi-second answer — so the {@code getObservations(..., mostRecentN=1, ...)}
	 * 12-arg overload is not worth its API-surface risk (measured 2026-07-10, threshold 50 ms).
	 */
	private static Double latestWeightKg(Patient patient) {
		String conceptUuid = ChartSearchAiUtils.getStringGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_WEIGHT_CONCEPT_UUID,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WEIGHT_CONCEPT_UUID).trim();
		if (ChartSearchAiConstants.DRUG_SAFETY_WEIGHT_CONCEPT_DISABLED.equalsIgnoreCase(conceptUuid)) {
			return null;
		}
		Concept weightConcept = Context.getConceptService().getConceptByUuid(conceptUuid);
		if (weightConcept == null) {
			log.debug("Weight concept {} not found; skipping weight for drug-reference context", conceptUuid);
			return null;
		}
		Obs latest = latestNumericObs(patient, weightConcept);
		return latest == null ? null : latest.getValueNumeric();
	}

	/** @return the weight-freshness window in days; an unparseable or non-positive GP value falls
	 *          back to the default rather than silently admitting stale weights. */
	private static long maxWeightAgeDays() {
		int parsed = ChartSearchAiUtils.getIntGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_SAFETY_WEIGHT_MAX_AGE_DAYS,
				ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WEIGHT_MAX_AGE_DAYS);
		return parsed > 0 ? parsed : ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_WEIGHT_MAX_AGE_DAYS;
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
