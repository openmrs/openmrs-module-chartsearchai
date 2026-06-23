/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api;

import java.util.ArrayList;
import java.util.List;

import org.openmrs.Allergy;
import org.openmrs.Condition;
import org.openmrs.Diagnosis;
import org.openmrs.Encounter;
import org.openmrs.MedicationDispense;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Person;
import org.openmrs.aop.event.PurgeServiceEvent;
import org.openmrs.aop.event.SaveServiceEvent;
import org.openmrs.aop.event.UnvoidServiceEvent;
import org.openmrs.aop.event.VoidServiceEvent;
import org.openmrs.api.context.Context;
import org.openmrs.person.PersonMergeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Detects chart writes by subscribing to core's #6084 {@code *ServiceEvent}s — the same mechanism
 * querystore uses for its index sync — and dispatches each affected patient to
 * {@link IndexingHelper#onChartWrite} (answer-cache invalidation + prewarm re-pin). This replaces the
 * former per-service AOP advice classes ({@code Obs/Encounter/PatientDataIndexingAdvice}); the events
 * carry the saved/voided/purged entity directly, so there is no service+method-name matching to drift.
 *
 * <p><b>Operations.</b> Subscribes to Save/Void/Unvoid/Purge (the operations the old advice covered).
 * Core publishes these only for actual writes, not for getters, so — unlike the old {@code afterReturning}
 * advice that fired on every service method — there is no read traffic to filter.
 *
 * <p><b>Behaviour parity with the advice it replaces.</b> Clinical types (Obs, Encounter, Condition,
 * Diagnosis, Allergy, Order, PatientProgram, MedicationDispense) are handled on every operation. A bare
 * {@link Patient} entity is handled on <em>save only</em> (demographics edits) — void/unvoid/purge of a
 * patient is a rare admin op on a patient that is not normally queried, left to the cache TTL backstop,
 * exactly as before. Patient merge emits no event, but {@code mergePatients} ends by saving a
 * {@link PersonMergeLog}, which arrives as {@code SaveServiceEvent<PersonMergeLog>} and dispatches both
 * the winner and loser.
 *
 * <p><b>Transaction semantics.</b> The handlers run synchronously inside the originating transaction
 * (the session is open, so {@code getPatient()} navigations resolve). Unlike querystore — which must
 * defer its heavy embed+index after commit to avoid indexing rolled-back data — chartsearchai's work
 * here is cheap and idempotent (an in-memory answer-cache eviction, and <em>scheduling</em> a debounced
 * re-pin whose actual prefill already runs later on a daemon thread). A rolled-back write therefore
 * costs at most a harmless cache miss or one redundant, debounced re-pin — so no after-commit dispatch
 * is needed.
 *
 * <p>Gated cheaply: {@link IndexingHelper#shouldHandleChartWrite} is checked before any entity
 * inspection, so the default (answer cache off + refresh off) path returns immediately.
 */
@Component("chartSearchAi.chartSearchEventListener")
public class ChartSearchEventListener {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchEventListener.class);

	@EventListener
	public void onSave(SaveServiceEvent<?> event) {
		handle(event.getEntity(), true);
	}

	@EventListener
	public void onVoid(VoidServiceEvent<?> event) {
		handle(event.getEntity(), false);
	}

	@EventListener
	public void onUnvoid(UnvoidServiceEvent<?> event) {
		handle(event.getEntity(), false);
	}

	@EventListener
	public void onPurge(PurgeServiceEvent<?> event) {
		handle(event.getEntity(), false);
	}

	/**
	 * Resolves the affected patient(s) for a written entity and dispatches each to the chart-write
	 * hook. {@code isSave} distinguishes the save-only entities (a bare Patient, and the merge log).
	 * Best-effort: a failure must never propagate back into the clinical transaction that published
	 * the event.
	 */
	void handle(Object entity, boolean isSave) {
		if (entity == null || !IndexingHelper.shouldHandleChartWrite()) {
			return;
		}
		try {
			for (Patient patient : patientsOf(entity, isSave)) {
				if (patient != null) {
					IndexingHelper.onChartWrite(patient);
				}
			}
		}
		catch (RuntimeException e) {
			log.warn("Chart-write event handling failed for {}; swallowing so the clinical "
					+ "transaction is unaffected", entity.getClass().getSimpleName(), e);
		}
	}

	/**
	 * The patient(s) whose chart a written entity belongs to — the event-keyed equivalent of the old
	 * advice's per-service {@code extractPatient}. Returns empty for entity types chartsearchai does
	 * not track (the global event stream carries every {@code OpenmrsService} write).
	 */
	List<Patient> patientsOf(Object entity, boolean isSave) {
		List<Patient> out = new ArrayList<Patient>(2);
		if (entity instanceof Obs) {
			out.add(resolvePatient(((Obs) entity).getPerson()));
		} else if (entity instanceof Encounter) {
			out.add(((Encounter) entity).getPatient());
		} else if (entity instanceof Condition) {
			out.add(((Condition) entity).getPatient());
		} else if (entity instanceof Diagnosis) {
			out.add(((Diagnosis) entity).getPatient());
		} else if (entity instanceof Allergy) {
			out.add(((Allergy) entity).getPatient());
		} else if (entity instanceof Order) {
			out.add(((Order) entity).getPatient());
		} else if (entity instanceof PatientProgram) {
			out.add(((PatientProgram) entity).getPatient());
		} else if (entity instanceof MedicationDispense) {
			out.add(((MedicationDispense) entity).getPatient());
		} else if (isSave && entity instanceof PersonMergeLog) {
			// Merge changes the winner's chart and retires the loser; both may be in the corpus/cache.
			PersonMergeLog merge = (PersonMergeLog) entity;
			out.add(resolvePatient(merge.getWinner()));
			out.add(resolvePatient(merge.getLoser()));
		} else if (isSave && entity instanceof Patient) {
			// Demographics (age/sex) are serialized into the chart; void/unvoid/purge of a patient is
			// left to the TTL backstop (parity with the former savePatient-only advice scope).
			out.add((Patient) entity);
		}
		return out;
	}

	/**
	 * Resolves the {@link Patient} for a person carrying a patient id, or null when the person is
	 * unsaved or not a patient. A real obs loaded by the service/REST layer carries a plain
	 * {@link Person} (Hibernate maps {@code obs.person} to Person), so an {@code instanceof} alone
	 * would drop every obs write; resolve by id when it is not already a Patient. Package-private seam
	 * so unit tests can exercise the loaded-obs path without an OpenMRS context.
	 */
	Patient resolvePatient(Person person) {
		if (person == null) {
			return null;
		}
		if (person instanceof Patient) {
			return (Patient) person;
		}
		if (person.getPersonId() == null) {
			return null;
		}
		return Context.getPatientService().getPatient(person.getPersonId());
	}
}
