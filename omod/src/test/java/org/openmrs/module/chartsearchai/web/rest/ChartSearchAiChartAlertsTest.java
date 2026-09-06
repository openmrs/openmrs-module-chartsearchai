/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.web.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The wire contract of {@code GET /chartsearchai/chartalerts} — the standing chart-alert surface
 * issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/280">#280</a> asks
 * for: a patient's own prescriptions checked against her own allergy and condition records, outside
 * the answer thread.
 *
 * <p><b>What this class covers and what it does not.</b> It covers the payload — that a finding
 * reaches a client shaped exactly as the {@code /search} chips are, that {@code screened} travels
 * beside it, and that the patient-resolution failures answer as {@code /search}'s do. What it leaves
 * to the api suite is whether the real arms compute the right findings at all, which
 * {@code StandingChartAlertsTest} drives through the real validator over a real parsed dataset. The
 * seam is the same one {@link ChartSearchAiSafetyWarningSeverityWireTest} uses and for the reason its
 * javadoc gives: {@code omod/pom.xml} declares no {@code chartsearchai-api} test-jar, so the fixtures
 * that drive the real {@code DrugSafetyValidator} are not reachable from here.
 *
 * <p>The stub overrides both public methods the handler calls, so a case can arrange the two states
 * that matter independently — a screen that ran and found something, and a screen that did not run.
 * {@link #anUnscreenedInstallSaysSoRatherThanReportingAnEmptyChart} is the one that would be missing
 * if {@code screened} were dropped, and it is the reason the key exists.
 */
public class ChartSearchAiChartAlertsTest {

	/**
	 * The standing findings a client receives. Drawn from issue #280's own reproduction — patient
	 * {@code a7090f70}, an active Lidocaine order and a recorded Lidocaine allergy — plus the
	 * cross-reactive partner that measurement raised beside it, so the list is a real one rather than
	 * a single row. Contraindications carry no rating, which is what makes
	 * {@link #everyFindingIsShapedExactlyAsASearchChipIs} able to assert that the key is present and
	 * null rather than absent.
	 */
	private static List<SafetyWarning> fixtureAlerts() {
		return Arrays.asList(
				new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, "Lidocaine",
						"Lidocaine is contraindicated by a documented lidocaine allergy."),
				new SafetyWarning(SafetyWarning.TYPE_CONTRAINDICATION, "Bupivacaine",
						"Bupivacaine is contraindicated by a documented lidocaine allergy "
								+ "(cross-reactivity: amide local anaesthetics)."));
	}

	private ChartSearchAiRestController controller;

	private StandingAlertStubValidator validator;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		// resolvePatient consults this exactly as the /search handler does; production autowires it.
		controller.setPatientAccessCheck((user, patient) -> true);
		validator = new StandingAlertStubValidator();
		controller.setDrugSafetyValidator(validator);
	}

	/** Drives the handler with the OpenMRS static context installed and torn down whatever happens —
	 *  the shape {@link RestControllerContext}'s javadoc requires of every class in this package that
	 *  installs it, so a leaked service cannot silently alter the contextless SSE cases elsewhere. */
	private ResponseEntity<Object> alertsFor(String patientUuid) {
		openmrsContext.install();
		try {
			return controller.chartAlerts(patientUuid);
		}
		finally {
			openmrsContext.restore();
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> okBody(String patientUuid) {
		ResponseEntity<Object> response = alertsFor(patientUuid);
		assertEquals(HttpStatus.OK, response.getStatusCode(),
				"the handler must have reached serialization, was: " + response);
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertNotNull(body, "no response body");
		return body;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> alertsOf(Map<String, Object> body) {
		List<Map<String, Object>> alerts = (List<Map<String, Object>>) body.get("alerts");
		assertNotNull(alerts, "the response carried no alerts array: " + body);
		return alerts;
	}

	/**
	 * A finding reaches a client shaped exactly as a {@code /search} chip is — through the one
	 * serializer, so the same finding cannot be rendered two ways on two surfaces.
	 *
	 * <p>Asserted as "every key a chip carries", read off a {@code /search} chip built by the same
	 * controller rather than listed here, because a list would go stale the next time a chip gains a
	 * field and would go stale SILENTLY: this surface would simply stop carrying it, and a client
	 * reusing its chip renderer would find the field missing on one surface and present on the other.
	 */
	@Test
	public void everyFindingIsShapedExactlyAsASearchChipIs() {
		List<Map<String, Object>> alerts = alertsOf(okBody(RestControllerContext.PATIENT_UUID));

		assertEquals(fixtureAlerts().size(), alerts.size(),
				"every standing finding must survive serialization, was: " + alerts);
		for (String key : Arrays.asList("type", "drug", "detail", "severity", "chartOrderBridges")) {
			for (Map<String, Object> alert : alerts) {
				assertTrue(alert.containsKey(key),
						"a standing alert must carry the chip key '" + key + "' a /search chip "
								+ "carries, so a client reads one shape on both surfaces: " + alert);
			}
		}
		Map<String, Object> first = alerts.get(0);
		assertEquals(SafetyWarning.TYPE_CONTRAINDICATION, first.get("type"), "was: " + first);
		assertEquals("Lidocaine", first.get("drug"), "was: " + first);
		assertEquals("Lidocaine is contraindicated by a documented lidocaine allergy.",
				first.get("detail"), "was: " + first);
		assertEquals(null, first.get("severity"),
				"a contraindication carries no rating, and null is that statement rather than a "
						+ "missing value: " + first);
	}

	@Test
	public void aScreenThatRanAndFoundSomethingSaysSo() {
		Map<String, Object> body = okBody(RestControllerContext.PATIENT_UUID);

		assertEquals(Boolean.TRUE, body.get("screened"),
				"the screen ran, so the alerts beside it are a measurement: " + body);
		assertFalse(alertsOf(body).isEmpty(), "precondition: it found something");
	}

	/**
	 * The case {@code screened} exists for, and the one a client cannot get right without it: an
	 * install whose drug-safety validator is switched off returns an EMPTY alerts array, which is
	 * byte-identical to a chart that holds no such finding.
	 *
	 * <p>It also pins that the handler does not ask the validator for findings it has just been told
	 * are not screened for — one read of the toggle decides both keys, so the array and the flag
	 * cannot describe different states of the install.
	 */
	@Test
	public void anUnscreenedInstallSaysSoRatherThanReportingAnEmptyChart() {
		validator.screens = false;

		Map<String, Object> body = okBody(RestControllerContext.PATIENT_UUID);

		assertEquals(Boolean.FALSE, body.get("screened"),
				"an install that does not run the standing screen must say so: " + body);
		assertTrue(alertsOf(body).isEmpty(),
				"and it must report nothing rather than a finding it did not screen for: " + body);
		assertEquals(0, validator.standingCalls,
				"the handler must not ask for findings on an install it has just read as unscreened");
	}

	@Test
	public void anUnknownPatientIsNotFoundAndAMissingOneIsABadRequest() {
		assertEquals(HttpStatus.NOT_FOUND, alertsFor("no-such-patient").getStatusCode(),
				"an unknown patient must answer as /search's own resolution does");
		assertEquals(HttpStatus.BAD_REQUEST, alertsFor(null).getStatusCode(),
				"and a missing patient parameter must be a bad request rather than a 500");
	}

	/**
	 * The whole payload marshals for an XML client.
	 *
	 * <p>Not decoration: {@code serializeSafetyWarnings} copies each chip's {@code chartOrderBridges}
	 * into an {@code ArrayList} precisely because {@code XStreamMarshaller} refuses
	 * {@code java.util.Collections}' immutable wrappers — the empty case included — and this surface
	 * additionally hands the serializer {@code Collections.emptyList()} on the unscreened path, which
	 * is a second wrapper on the same route.
	 */
	@Test
	public void theWholePayloadMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(okBody(RestControllerContext.PATIENT_UUID), "a screened chart");

		validator.screens = false;
		XmlPayloads.assertMarshals(okBody(RestControllerContext.PATIENT_UUID), "an unscreened install");
	}

	/** Returns the fixture findings, and records what the handler asked it for. */
	private static class StandingAlertStubValidator extends DrugSafetyValidator {

		private boolean screens = true;

		private int standingCalls;

		@Override
		public boolean reportsStandingChartAlerts() {
			return screens;
		}

		@Override
		public List<SafetyWarning> standingChartAlerts(Patient patient) {
			standingCalls++;
			return new ArrayList<SafetyWarning>(fixtureAlerts());
		}
	}
}
