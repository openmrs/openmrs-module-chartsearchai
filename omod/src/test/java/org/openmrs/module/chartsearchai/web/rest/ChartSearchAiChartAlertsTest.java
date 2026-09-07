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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
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
 * <p>The stub overrides the ONE method the handler calls, and returns all three of its states through
 * it — see {@link StandingScreen}, whose middle state is the only arrangement on which {@code screened}
 * and the emptiness of {@code alerts} disagree, and so the only one that can tell a handler PUBLISHING
 * the verdict from one deriving it from the list. That the handler makes one call is itself the
 * contract: the flag and the list come back together, so neither can answer for a different pass than
 * the other. {@link #anUnscreenedInstallSaysSoRatherThanReportingAnEmptyChart} is the case that would
 * be missing if {@code screened} were dropped, and it is the reason the key exists.
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
	 * A finding reaches a client shaped exactly as a {@code /search} chip is — so the same finding
	 * cannot be rendered two ways on two surfaces.
	 *
	 * <p><b>The key set is read off a real {@code /search} chip built by this same controller, never
	 * listed here.</b> A literal list would go stale the next time a chip gains a field, and it would
	 * go stale SILENTLY: this surface would simply stop carrying the new key, and a client reusing its
	 * chip renderer would find the field present on one surface and missing on the other. Reading it
	 * from the other surface is also what makes this a comparison rather than a restatement — the two
	 * share {@code serializeSafetyWarnings} today, and this is what would notice if one of them
	 * stopped.
	 *
	 * <p>Both payloads are built from the SAME fixture warnings, so a key difference is a difference
	 * in the surfaces and not in what they were given.
	 */
	@Test
	public void everyFindingIsShapedExactlyAsASearchChipIs() {
		List<Map<String, Object>> alerts = alertsOf(okBody(RestControllerContext.PATIENT_UUID));
		Set<String> searchChipKeys = aSearchChipsKeys();

		assertEquals(fixtureAlerts().size(), alerts.size(),
				"every standing finding must survive serialization, was: " + alerts);
		assertFalse(searchChipKeys.isEmpty(),
				"precondition: the /search chip must have carried keys, or this compares against nothing");
		for (Map<String, Object> alert : alerts) {
			assertEquals(searchChipKeys, alert.keySet(),
					"a standing alert must carry exactly the keys a /search chip carries, so a client "
							+ "reads one shape on both surfaces: " + alert);
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
	 * The payload that will dominate production, and the ONE arrangement on which the two keys
	 * disagree: a chart that WAS screened and holds no such finding. Most patients are prescribed
	 * nothing their own chart contraindicates, so this is the ordinary response rather than an edge.
	 *
	 * <p>Without it, every case here has {@code screened} agreeing with the emptiness of
	 * {@code alerts}, so nothing can tell the handler reading {@code standing.isScreened()} from one
	 * deriving the flag off the list. Measured by a review agent: substituting
	 * {@code !standing.getAlerts().isEmpty()} for the published verdict left the whole build green
	 * while inverting the key exactly here — the module looks, finds nothing, and tells a banner that
	 * nobody looked. It throws nothing and answers 200, so the cost lands on the clinician reading the
	 * banner and never on CI.
	 *
	 * <p>The api side already pins the validator's half of this ({@code StandingChartAlertsTest}'s
	 * {@code alertsOf} asserts {@code isScreened()} before any case reads an empty list); this is the
	 * wire's, which that class cannot reach.
	 */
	@Test
	public void aScreenThatRanAndFoundNothingIsNotAChartNobodyLookedAt() {
		validator.screen = StandingScreen.RAN_AND_FOUND_NOTHING;

		Map<String, Object> body = okBody(RestControllerContext.PATIENT_UUID);

		assertTrue(alertsOf(body).isEmpty(), "precondition: this screen found nothing: " + body);
		assertEquals(Boolean.TRUE, body.get("screened"),
				"a screen that ran and found nothing must still say it ran: an empty alerts array is a "
						+ "measurement of none HERE, and the absence of a pass in the unscreened case, and "
						+ "this key is the only thing that tells a client which it is holding: " + body);
	}

	/**
	 * This payload states nothing about how bounded an interaction list is, because it built none.
	 *
	 * <p>Asserted on the BODY, not on the controller's source. {@code ChartSearchAiInteractionPairExtentTest}
	 * counts the {@code "interactionPairs"} literal, and a review agent defeated that by writing the
	 * key as {@code "interaction" + "Pairs"} — it compiled and the whole omod suite stayed green while
	 * this surface published {@code {found: 0, reported: 0}}, a complete screen of zero pairs from a
	 * pass that ran no screen. A count of a literal cannot see an assembled key; a read of the payload
	 * can, and neither replaces the other — the count reaches sites this class does not drive.
	 */
	@Test
	public void thePayloadStatesNoInteractionExtent() {
		for (Map<String, Object> body : Arrays.asList(okBody(RestControllerContext.PATIENT_UUID),
				unscreenedBody())) {
			assertFalse(body.containsKey("interactionPairs"),
					"a surface that raises no interaction chip has no extent to state, and a zeroed one "
							+ "asserts a screen it never ran: " + body);
		}
	}

	/** The payload of an install whose standing screen does not run. */
	private Map<String, Object> unscreenedBody() {
		StandingScreen was = validator.screen;
		validator.screen = StandingScreen.DID_NOT_RUN;
		try {
			return okBody(RestControllerContext.PATIENT_UUID);
		}
		finally {
			validator.screen = was;
		}
	}

	/**
	 * The case {@code screened} exists for, and the one a client cannot get right without it: an
	 * install whose drug-safety validator is switched off returns an EMPTY alerts array, which is
	 * byte-identical to a chart that holds no such finding.
	 *
	 * <p>It also pins that the two keys come back from ONE call, which is what makes them incapable of
	 * describing different passes — an earlier shape asked a predicate and then asked for the
	 * findings, and a toggle flipped between the two reads (or a chart whose records could not be
	 * read) made the flag answer for a pass that was not the published one.
	 */
	@Test
	public void anUnscreenedInstallSaysSoRatherThanReportingAnEmptyChart() {
		validator.screen = StandingScreen.DID_NOT_RUN;

		Map<String, Object> body = okBody(RestControllerContext.PATIENT_UUID);

		assertEquals(Boolean.FALSE, body.get("screened"),
				"an install that does not run the standing screen must say so: " + body);
		assertTrue(alertsOf(body).isEmpty(),
				"and it must report nothing rather than a finding it did not screen for: " + body);
		assertEquals(1, validator.standingCalls,
				"and it must have asked ONCE — the flag and the list come back from one call, so "
						+ "neither can answer for a different pass than the other");
	}

	/**
	 * A patient this user may not read answers 403 and reports nothing about her.
	 *
	 * <p>Asserted rather than left to "it calls the same {@code resolvePatient} as {@code /search}":
	 * every other case here installs an access check that says yes, so a handler that resolved the
	 * patient and never consulted the check would pass all of them — and this is the surface where
	 * that would leak a patient's prescriptions and recorded allergies to a user with no claim on her
	 * chart. It also pins that the refusal happens BEFORE the findings are computed, which is what
	 * makes it a refusal rather than a filtered answer.
	 */
	@Test
	public void aPatientThisUserMayNotReadIsRefusedBeforeAnythingIsComputed() {
		controller.setPatientAccessCheck((user, patient) -> false);

		ResponseEntity<Object> response = alertsFor(RestControllerContext.PATIENT_UUID);

		assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
				"a patient the user may not read must be refused, was: " + response);
		assertEquals(0, validator.standingCalls,
				"and refused before her chart is screened, so nothing about her is computed");
	}

	/**
	 * A caller without the clinical privilege is refused before the patient is even resolved.
	 *
	 * <p><b>Nothing else pins this, and the shipped access check does not stand in for it.</b>
	 * {@code PatientAccessCheck}'s default allows every patient to every holder of the privilege — the
	 * README says so — so on a stock install {@code Context.requirePrivilege} is the whole gate on this
	 * endpoint, and it is one line. Measured by a review agent: deleting that line left the entire omod
	 * suite green, on the surface that returns a patient's prescriptions, allergies and conditions.
	 *
	 * <p>Shaped after {@code ChartSearchAiDrugReferenceStatusTest}'s own case for
	 * {@code /drugreferencestatus}, which pins a privilege guarding configuration rather than a chart.
	 * The user context is installed WITHOUT this fixture's own, which grants everything, and restored
	 * whatever happens — surefire reuses one JVM for this module, so a leaked context would silently
	 * alter the classes that run after it.
	 */
	@Test
	public void aCallerWithoutTheClinicalPrivilegeIsRefusedBeforeThePatientIsResolved() {
		openmrsContext.install();
		Context.setUserContext(new UserContext(null) {

			@Override
			public boolean hasPrivilege(String privilege) {
				return false;
			}
		});
		try {
			assertThrows(RuntimeException.class,
					() -> controller.chartAlerts(RestControllerContext.PATIENT_UUID),
					"a caller without " + ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA
							+ " must be refused, on the surface that returns a patient's prescriptions "
							+ "and recorded allergies");
			assertEquals(0, validator.standingCalls,
					"and refused before anything about her is computed");
		}
		finally {
			openmrsContext.restore();
		}
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
	 * <p>Not decoration, and the wrapper it exercises is not the outer list. {@code serializeSafetyWarnings}
	 * copies each chip's {@code chartOrderBridges} into an {@code ArrayList} precisely because
	 * {@code XStreamMarshaller} refuses {@code java.util.Collections}' immutable wrappers — the EMPTY
	 * case included — and every standing alert is a contraindication, whose three-argument constructor
	 * sets that field to {@code Collections.emptyList()}. So this surface hands the marshaller the
	 * refused shape on its ordinary path, not an exotic one. The unscreened half is here because that
	 * payload's {@code alerts} is empty, which is the arrangement an XML client sees on a disabled
	 * install.
	 */
	@Test
	public void theWholePayloadMarshalsForAnXmlClient() throws Exception {
		XmlPayloads.assertMarshals(okBody(RestControllerContext.PATIENT_UUID), "a screened chart");

		validator.screen = StandingScreen.DID_NOT_RUN;
		XmlPayloads.assertMarshals(okBody(RestControllerContext.PATIENT_UUID), "an unscreened install");
	}

	/**
	 * @return the keys of a {@code safetyWarnings} chip on the blocking {@code /search} response,
	 *         built by this same controller from the same fixture warnings.
	 */
	@SuppressWarnings("unchecked")
	private Set<String> aSearchChipsKeys() {
		controller.setChartSearchService(new SearchStubService());
		openmrsContext.install();
		try {
			ResponseEntity<Object> response = controller.search(
					RestControllerContext.searchBody("Any interactions with her current medications?"));
			assertEquals(HttpStatus.OK, response.getStatusCode(), "the /search handler must have "
					+ "reached serialization, or there is no chip to compare against: " + response);
			Map<String, Object> payload = (Map<String, Object>) response.getBody();
			List<Map<String, Object>> chips = (List<Map<String, Object>>) payload.get("safetyWarnings");
			assertNotNull(chips, "the /search response carried no safetyWarnings array: " + payload);
			assertEquals(fixtureAlerts().size(), chips.size(),
					"precondition: /search must have serialized this fixture, was: " + chips);
			return chips.get(0).keySet();
		}
		finally {
			openmrsContext.restore();
		}
	}

	/** Serves the same fixture findings as an ANSWER's chips, so the two surfaces are compared over
	 *  one input. */
	private static class SearchStubService implements ChartSearchService {

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return new ChartAnswer("Two contraindications were found.",
					new ArrayList<RecordReference>(), 0, 0, 0,
					new ArrayList<SafetyWarning>(fixtureAlerts()));
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			return search(patient, question);
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			return search(patient, question);
		}

		@Override
		public void warmup(Patient patient) {
		}
	}

	/**
	 * The three states a standing pass can hand the handler. Three and not two: a screen that RAN and
	 * found nothing is the state whose {@code screened} does not follow from its {@code alerts}, and a
	 * suite carrying only the outer two grades a handler that derives one key from the other as
	 * correct.
	 */
	private enum StandingScreen {
		RAN_AND_FOUND_SOMETHING, RAN_AND_FOUND_NOTHING, DID_NOT_RUN
	}

	/** Returns the fixture findings, and records what the handler asked it for. */
	private static class StandingAlertStubValidator extends DrugSafetyValidator {

		private StandingScreen screen = StandingScreen.RAN_AND_FOUND_SOMETHING;

		private int standingCalls;

		@Override
		public StandingChartAlerts standingChartAlerts(Patient patient) {
			standingCalls++;
			switch (screen) {
				case RAN_AND_FOUND_SOMETHING:
					return StandingChartAlerts.screened(new ArrayList<SafetyWarning>(fixtureAlerts()));
				case RAN_AND_FOUND_NOTHING:
					return StandingChartAlerts.screened(new ArrayList<SafetyWarning>());
				default:
					return StandingChartAlerts.notScreened();
			}
		}
	}
}
