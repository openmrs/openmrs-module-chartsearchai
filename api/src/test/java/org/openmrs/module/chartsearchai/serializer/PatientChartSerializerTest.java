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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;
import org.openmrs.module.chartsearchai.util.DateFormatUtil;

/**
 * Pure unit tests for {@link PatientChartSerializer}. Patient is passed as
 * {@code null} (demographics are simply skipped) so the serializer runs without
 * an OpenMRS context or a wired record loader.
 */
public class PatientChartSerializerTest {

	@Test
	public void serialize_mappingTextMatchesWhatTheLlmSaw() {
		// Pins the contract the citation grounding verifier depends on: the RecordMapping
		// must carry the SAME per-record content the LLM saw in the chart line (date
		// parenthetical + body), not the bare record body. Otherwise Tier-2 entailment
		// flags an otherwise-valid citation as unsupported when the model cites the date
		// (the date lives in the prefix, not in record.getText()). If a future edit drops
		// the rendered text, grounding silently degrades and no other test would catch it.
		Date d = new Date(1700000000000L); // fixed instant for determinism
		String datePrefix = "(" + DateFormatUtil.formatDate(d) + ") ";
		SerializedRecord dated = new SerializedRecord("obs", "uuid-1", "Temperature: 36.7", d);
		SerializedRecord undated = new SerializedRecord("condition", "uuid-2", "Type 2 diabetes mellitus", null);

		PatientChart chart = new PatientChartSerializer().serialize(null, Arrays.asList(dated, undated));
		List<RecordMapping> mappings = chart.getMappings();

		assertEquals(2, mappings.size());
		// Dated record: mapping text includes the date prefix the model can cite.
		assertEquals(datePrefix + "Temperature: 36.7", mappings.get(0).getText());
		// Undated record: just the body, no parenthetical.
		assertEquals("Type 2 diabetes mellitus", mappings.get(1).getText());
		// The mapping text always carries the date (for grounding); the chart line shows it only on the
		// first record of a same-date run, so in general the mapping is a superset of the chart line.
		// Here the dated record IS first of its run, so its chart line carries the date inline too.
		assertTrue(chart.getText().contains("[1] " + datePrefix + "Temperature: 36.7"),
				"first-of-run dated record shows its date inline; chart was:\n" + chart.getText());
	}

	@Test
	public void serialize_omitsComputedDemographicsHeader_whenPatientRecordPresent() {
		// querystore indexes the patient as a citable "patient" record carrying sex/name/identifiers.
		// When one is present the serializer must NOT also prepend the un-numbered "Patient: ..." header:
		// it duplicates the demographics and sits above [1], where small models misattribute it to that
		// record (e.g. answering "What gender?" with "Female [1]" pointing at an allergy).
		Patient patient = new Patient();
		patient.setGender("F");
		// Birthday two days ago, 30 years back -> deterministically age 30 (no birthday-boundary flake).
		Calendar c = Calendar.getInstance();
		c.add(Calendar.YEAR, -30);
		c.add(Calendar.DAY_OF_MONTH, -2);
		patient.setBirthdate(c.getTime());
		SerializedRecord patientRecord = new SerializedRecord("patient", "p-uuid",
				"Berryl Ojienda; Female; born 1996-01-01", null);
		SerializedRecord allergy = new SerializedRecord("allergy", "a-uuid", "Allergy to penicillin", null);

		PatientChart chart = new PatientChartSerializer().serialize(patient, Arrays.asList(patientRecord, allergy));

		assertFalse(chart.getText().startsWith("Patient:"),
				"computed demographics header must be omitted when a patient record is present; chart was:\n"
						+ chart.getText());
		assertTrue(chart.getText().startsWith("[1] "),
				"chart should begin with the first numbered record; chart was:\n" + chart.getText());
		// The live age is merged into the patient record's own line so age questions answer directly
		// (the indexed record carries only birthdate); a regression here makes "how old?" echo the DOB.
		assertTrue(chart.getText().contains("(30 years old)"),
				"live age must be merged into the patient record line; chart was:\n" + chart.getText());
		assertTrue(chart.getMappings().get(0).getText().contains("(30 years old)"),
				"mapping text must match what the LLM saw (incl. age) for grounding; mapping was:\n"
						+ chart.getMappings().get(0).getText());
	}

	@Test
	public void serialize_rendersPanelLabel_forGroupObsMemberAndMatchesMappingText() {
		// A group-obs member (obs_group_uuid + obs_group_concept_name carried on the SerializedRecord)
		// must render its panel label inline so the LLM can cluster members of the same panel. The
		// label must also be part of the RecordMapping text, because the grounding verifier compares
		// cited records against exactly what the LLM saw — if the label were in the chart line but not
		// the mapping text, a citation that mentions the panel would look unsupported.
		SerializedRecord member = new SerializedRecord("obs", "obs-na", "Sodium: 140 mmol/L", null,
				java.util.Collections.<String>emptyList(), "grp-bmp-1", "Basic metabolic panel");

		PatientChart chart = new PatientChartSerializer().serialize(null, Arrays.asList(member));

		assertTrue(chart.getText().contains("[1] Sodium: 140 mmol/L (part of: Basic metabolic panel)"),
				"group-obs member must render the group label inline; chart was:\n" + chart.getText());
		assertEquals("Sodium: 140 mmol/L (part of: Basic metabolic panel)",
				chart.getMappings().get(0).getText(),
				"mapping text must include the group label so the grounding verifier's view matches the LLM's");
	}

	@Test
	public void serialize_omitsGroupLabel_forNonGroupObs() {
		// A plain obs (no obs_group_uuid) must not get a group suffix.
		SerializedRecord plain = new SerializedRecord("obs", "obs-temp", "Temperature: 36.7 C", null);

		PatientChart chart = new PatientChartSerializer().serialize(null, Arrays.asList(plain));

		assertFalse(chart.getText().contains("part of:"),
				"a non-grouped obs must not render a group label; chart was:\n" + chart.getText());
		assertEquals("Temperature: 36.7 C", chart.getMappings().get(0).getText());
	}

	@Test
	public void serialize_omitsGroupLabel_whenGroupUuidPresentButNameAbsent() {
		// uuid present but concept name absent (parent concept had no preferred name) -> nothing
		// LLM-meaningful to show, so no suffix and no empty "(part of: )".
		SerializedRecord member = new SerializedRecord("obs", "obs-x", "Glucose: 90 mg/dL", null,
				java.util.Collections.<String>emptyList(), "grp-unnamed", null);

		PatientChart chart = new PatientChartSerializer().serialize(null, Arrays.asList(member));

		assertFalse(chart.getText().contains("part of:"),
				"no concept name -> no group suffix; chart was:\n" + chart.getText());
		assertEquals("Glucose: 90 mg/dL", chart.getMappings().get(0).getText());
	}

	@Test
	public void serialize_addsDemographicsHeader_whenNoPatientRecordPresent() {
		// Fallback: PatientRecordSerializer skips a nameless patient, yielding no querystore "patient"
		// document, so the computed header stays the only demographics source and must still be emitted.
		Patient patient = new Patient();
		patient.setGender("F");
		SerializedRecord allergy = new SerializedRecord("allergy", "a-uuid", "Allergy to penicillin", null);

		PatientChart chart = new PatientChartSerializer().serialize(patient, Arrays.asList(allergy));

		assertTrue(chart.getText().startsWith("Patient: Female"),
				"computed header must be the fallback when no patient record is present; chart was:\n"
						+ chart.getText());
	}

	@Test
	public void serialize_dropsRepeatedDateOnConsecutiveSameDateRecords() {
		// Cold-prefill token saving: the "(date)" parenthetical (~7 tokens) is rendered only on the first
		// record of each consecutive same-date run and dropped on the rest. Charts cluster many records
		// per encounter date, so this removes ~30% of prompt tokens with no information loss (the date is
		// still present once per run) and keeps the chart a flat list (no section structure).
		Date dateA = new Date(1700000000000L); // 2023-11-14 UTC
		Date dateB = new Date(1690000000000L); // 2023-07-22 UTC
		String a = DateFormatUtil.formatDate(dateA);
		String b = DateFormatUtil.formatDate(dateB);
		SerializedRecord r1 = new SerializedRecord("obs", "u1", "Pulse: 80 bpm", dateA);
		SerializedRecord r2 = new SerializedRecord("obs", "u2", "Temperature: 36.7 C", dateA);
		SerializedRecord r3 = new SerializedRecord("obs", "u3", "Weight: 70 kg", dateB);

		PatientChart chart = new PatientChartSerializer().serialize(null, Arrays.asList(r1, r2, r3));

		// [1] shows date A (run start); [2] drops it (same date); [3] shows date B (new run).
		assertEquals("[1] (" + a + ") Pulse: 80 bpm\n[2] Temperature: 36.7 C\n[3] (" + b + ") Weight: 70 kg\n",
				chart.getText());
		// Grounding contract intact: every dated record's mapping text still carries its inline date.
		assertEquals("(" + a + ") Pulse: 80 bpm", chart.getMappings().get(0).getText());
		assertEquals("(" + a + ") Temperature: 36.7 C", chart.getMappings().get(1).getText());
		assertEquals("(" + b + ") Weight: 70 kg", chart.getMappings().get(2).getText());
	}

	@Test
	public void serialize_undatedRecordResetsRun_soNextSameDateShowsItsDateAgain() {
		// An undated record renders as a plain "[N] body" line (exactly as in the legacy format) and resets
		// the run, so a following record of the SAME date re-shows its date rather than being silently
		// absorbed into a run the undated record broke.
		Date dateA = new Date(1700000000000L);
		String a = DateFormatUtil.formatDate(dateA);
		SerializedRecord r1 = new SerializedRecord("obs", "u1", "Pulse: 80 bpm", dateA);
		SerializedRecord r2 = new SerializedRecord("condition", "u2", "Hypertension", null);
		SerializedRecord r3 = new SerializedRecord("obs", "u3", "Weight: 70 kg", dateA);

		PatientChart chart = new PatientChartSerializer().serialize(null, Arrays.asList(r1, r2, r3));

		assertEquals("[1] (" + a + ") Pulse: 80 bpm\n[2] Hypertension\n[3] (" + a + ") Weight: 70 kg\n",
				chart.getText());
	}
}
