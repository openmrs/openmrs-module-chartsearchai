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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.Allergy;
import org.openmrs.Condition;
import org.openmrs.Diagnosis;
import org.openmrs.Encounter;
import org.openmrs.Location;
import org.openmrs.MedicationDispense;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Person;
import org.openmrs.person.PersonMergeLog;

/**
 * Parity coverage for {@link ChartSearchEventListener#patientsOf} — the event-keyed replacement for
 * the former advice classes' {@code extractPatient}/{@code getPatientFromArgs}. Each clinical entity
 * type must resolve to its patient on any operation; a bare {@link Patient} and a {@link PersonMergeLog}
 * resolve only on save (the void/unvoid/purge-of-patient scope the old advice deliberately omitted);
 * unknown types resolve to nothing. The {@code resolvePatient} seam is overridden for the loaded-obs
 * path (where {@code obs.getPerson()} is a plain Person) so the test needs no OpenMRS context.
 */
public class ChartSearchEventListenerTest {

	private final ChartSearchEventListener listener = new ChartSearchEventListener();

	private static Patient patient(int id) {
		Patient p = new Patient();
		p.setPatientId(id);
		p.setUuid("uuid-" + id);
		return p;
	}

	private static List<Patient> nonNull(List<Patient> in) {
		in.removeIf(p -> p == null);
		return in;
	}

	@Test
	public void patientsOf_shouldResolveEncounterPatient() {
		Patient p = patient(1);
		Encounter e = new Encounter();
		e.setPatient(p);
		assertEquals(java.util.Collections.singletonList(p), listener.patientsOf(e, false),
				"encounter writes (any op) resolve to the encounter's patient");
	}

	@Test
	public void patientsOf_shouldResolveConditionDiagnosisAllergyOrderProgramDispensePatients() {
		Patient p = patient(2);

		Condition condition = new Condition();
		condition.setPatient(p);
		Diagnosis diagnosis = new Diagnosis();
		diagnosis.setPatient(p);
		Allergy allergy = new Allergy();
		allergy.setPatient(p);
		Order order = new Order();
		order.setPatient(p);
		PatientProgram program = new PatientProgram();
		program.setPatient(p);
		MedicationDispense dispense = new MedicationDispense();
		dispense.setPatient(p);

		for (Object entity : new Object[] { condition, diagnosis, allergy, order, program, dispense }) {
			assertEquals(java.util.Collections.singletonList(p), nonNull(listener.patientsOf(entity, false)),
					entity.getClass().getSimpleName() + " must resolve to its patient on any op");
		}
	}

	@Test
	public void patientsOf_shouldResolveObsPersonWhenItIsAlreadyAPatient() {
		Patient p = patient(3);
		Obs obs = new Obs();
		obs.setPerson(p);
		assertEquals(java.util.Collections.singletonList(p), listener.patientsOf(obs, false),
				"an obs whose person is already a Patient resolves directly (no id lookup)");
	}

	@Test
	public void patientsOf_shouldResolveObsViaPatientServiceWhenPersonIsPlain() {
		final Patient resolved = patient(4);
		// A real loaded obs carries a plain Person, not a Patient — the resolvePatient seam does the
		// id lookup. Override it so the test runs without an OpenMRS context.
		ChartSearchEventListener seamed = new ChartSearchEventListener() {

			@Override
			Patient resolvePatient(Person person) {
				return resolved;
			}
		};
		Obs obs = new Obs();
		Person plainPerson = new Person();
		plainPerson.setPersonId(4);
		obs.setPerson(plainPerson);

		assertEquals(java.util.Collections.singletonList(resolved), seamed.patientsOf(obs, false),
				"a plain-Person obs must resolve the Patient by id");
	}

	@Test
	public void patientsOf_shouldHandleBarePatientOnSaveOnly() {
		Patient p = patient(5);
		assertEquals(java.util.Collections.singletonList(p), listener.patientsOf(p, true),
				"savePatient (demographics) must invalidate the patient");
		assertTrue(listener.patientsOf(p, false).isEmpty(),
				"void/unvoid/purge of a patient is out of scope (TTL backstop), matching the old advice");
	}

	@Test
	public void patientsOf_shouldDispatchBothMergePartiesOnSaveOnly() {
		Patient winner = patient(6);
		Patient loser = patient(7);
		PersonMergeLog merge = new PersonMergeLog();
		merge.setWinner(winner);
		merge.setLoser(loser);

		List<Patient> both = listener.patientsOf(merge, true);
		assertEquals(2, both.size(), "a merge dispatches both winner and loser");
		assertSame(winner, both.get(0));
		assertSame(loser, both.get(1));
		assertTrue(listener.patientsOf(merge, false).isEmpty(), "the merge log only ever arrives on save");
	}

	@Test
	public void patientsOf_shouldReturnNothingForUntrackedEntityTypes() {
		// The event stream is global; a type chartsearchai does not track must resolve to nothing.
		assertTrue(listener.patientsOf(new Location(), true).isEmpty(),
				"an untracked entity type yields no patient");
		assertTrue(listener.patientsOf(null, true).isEmpty(), "null entity yields no patient");
	}
}
