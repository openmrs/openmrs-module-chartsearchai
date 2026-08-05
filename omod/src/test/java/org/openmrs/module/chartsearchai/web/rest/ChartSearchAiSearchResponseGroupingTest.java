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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ServiceContext;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Wire-contract test for the BLOCKING {@code /search} response, the one
 * {@code serializeReferences} call site the SSE tests cannot reach.
 *
 * <p>It exists because {@code /search} is where the README documents the {@code group} field
 * first, and because the handler was changed from an inline copy of the serialization loop to a
 * call to the shared helper — a rewiring that no automated test covered, only manual checks
 * against a running server. The helper being well covered at three SSE sites proves the helper,
 * not that this endpoint calls it.
 *
 * <p>Driving {@code search()} needs more of the OpenMRS static context than the SSE path: a
 * privileged {@link UserContext}, plus {@link PatientService} and {@link AdministrationService}
 * in the {@link ServiceContext}. Those are installed here as reflective stand-ins answering only
 * the two methods this path actually calls, and every one of them — including whatever was
 * installed before — is restored in {@link #restoreContext()}. That matters: surefire runs this
 * module in a single reused JVM, so a leaked service or user context would silently alter the
 * other test classes in this package rather than failing here.
 */
public class ChartSearchAiSearchResponseGroupingTest {

	private static final String PATIENT_UUID = "uuid-7";

	private ChartSearchAiRestController controller;

	private PatientService priorPatientService;

	private AdministrationService priorAdministrationService;

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new MixedReferenceStubService());
		// resolvePatient consults this; production autowires it. Allow the access so the handler
		// reaches the serialization step under test.
		controller.setPatientAccessCheck((user, patient) -> true);

		priorPatientService = currentService(PatientService.class);
		priorAdministrationService = currentService(AdministrationService.class);
		ServiceContext.getInstance().setPatientService(patientServiceReturning(patient()));
		ServiceContext.getInstance().setAdministrationService(administrationServiceWithNoOverrides());

		Context.setUserContext(new UserContext(null) {

			@Override
			public boolean hasPrivilege(String privilege) {
				return true;
			}

			@Override
			public User getAuthenticatedUser() {
				return new User(3);
			}

			@Override
			public boolean isAuthenticated() {
				return true;
			}
		});
	}

	@AfterEach
	public void restoreContext() {
		ServiceContext.getInstance().setPatientService(priorPatientService);
		ServiceContext.getInstance().setAdministrationService(priorAdministrationService);
		Context.clearUserContext();
	}

	/** Whatever service was installed before this test, or null if none/unavailable. */
	private static <T> T currentService(Class<T> type) {
		try {
			return type.cast(ServiceContext.getInstance().getService(type));
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(7);
		p.setUuid(PATIENT_UUID);
		return p;
	}

	/** Answers {@code getPatientByUuid} for the fixture patient; every other call returns null. */
	private static PatientService patientServiceReturning(final Patient patient) {
		return proxy(PatientService.class, new InvocationHandler() {

			@Override
			public Object invoke(Object p, Method method, Object[] args) {
				if ("getPatientByUuid".equals(method.getName())
						&& args != null && PATIENT_UUID.equals(args[0])) {
					return patient;
				}
				return null;
			}
		});
	}

	/**
	 * Answers global-property reads the way an unconfigured installation does: the two-arg form
	 * returns the caller's own default (returning null there would break {@code preFilter.trim()}),
	 * and the one-arg form returns null so the rate limiter falls back to its compiled default —
	 * which the stub audit log's zero recent-query count then passes.
	 */
	private static AdministrationService administrationServiceWithNoOverrides() {
		return proxy(AdministrationService.class, new InvocationHandler() {

			@Override
			public Object invoke(Object p, Method method, Object[] args) {
				if ("getGlobalProperty".equals(method.getName()) && args != null && args.length == 2) {
					return args[1];
				}
				return null;
			}
		});
	}

	private static <T> T proxy(Class<T> type, InvocationHandler handler) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
	}

	private List<Map<String, Object>> searchReferences() {
		Map<String, String> body = new HashMap<String, String>();
		body.put("patient", PATIENT_UUID);
		body.put("question", "Is it safe to give her aspirin?");

		ResponseEntity<Object> response = controller.search(body);
		assertEquals(HttpStatus.OK, response.getStatusCode(), "the handler must have reached serialization");

		@SuppressWarnings("unchecked")
		Map<String, Object> payload = (Map<String, Object>) response.getBody();
		assertNotNull(payload, "no response body");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> refs = (List<Map<String, Object>>) payload.get("references");
		assertNotNull(refs, "the /search response carried no references array");
		return refs;
	}

	@Test
	public void searchResponse_tagsEveryReferenceWithItsGroup() {
		for (Map<String, Object> ref : searchReferences()) {
			Object group = ref.get("group");
			assertNotNull(group, "reference [" + ref.get("index") + "] carries no group discriminator");
			String expected = "drug_reference".equals(ref.get("resourceType")) ? "reference" : "chart";
			assertEquals(expected, group, "wrong group for resourceType " + ref.get("resourceType"));
		}
	}

	@Test
	public void searchResponse_ordersChartEvidenceBeforeReferenceMaterial() {
		List<Object> groups = new ArrayList<Object>();
		List<Object> indexes = new ArrayList<Object>();
		for (Map<String, Object> ref : searchReferences()) {
			groups.add(ref.get("group"));
			indexes.add(ref.get("index"));
		}
		assertEquals(Arrays.asList("chart", "chart", "reference"), groups,
				"the blocking /search response must group chart evidence first, same as the SSE events");
		assertEquals(Arrays.asList(230, 8, 231), indexes,
				"and must regroup stably, leaving the chart records in their incoming order");
	}

	@Test
	public void searchResponse_keepsTheOtherReferenceFields() {
		Map<String, Object> drugRef = searchReferences().get(2);
		assertEquals(231, drugRef.get("index"));
		assertEquals("drug_reference", drugRef.get("resourceType"));
		assertEquals("1191", drugRef.get("resourceUuid"));
		assertTrue(drugRef.containsKey("date"), "the date key must be present even when null");
		assertTrue(drugRef.containsKey("grounded"), "the grounded key must be present even when null");
	}

	@Test
	public void searchResponse_alsoPublishesTheCitationMetadataKeys() {
		// Issue #117 moved a drug-reference record's dataset attribution and its withheld-partner
		// count off the citable record text — where the model recited them into clinician-facing
		// answers — and onto the reference, for a client to render beside the citation chip. That
		// relocation only holds where the keys actually reach a client, and this endpoint is the one
		// emission site the three SSE assertions cannot speak for: per this class's own reason for
		// existing, covering the shared helper proves the helper, not that /search calls it.
		//
		// Presence rather than values, because this fixture's reference carries neither (it is built
		// with the short constructor, so null / 0 — a real shape, and the one a chart record always
		// has). That the populated values survive the trip is pinned where a populated fixture
		// exists: ChartSearchAiReferenceProvenanceTest for the SSE sites, and
		// LlmInferenceServiceTest for the mapping -> reference hop. What is unique to this site, and
		// all that is missing without this test, is whether the blocking endpoint goes through the
		// shared serialization at all.
		for (Map<String, Object> ref : searchReferences()) {
			assertTrue(ref.containsKey("source"),
					"reference [" + ref.get("index") + "] carries no source key: " + ref);
			assertTrue(ref.containsKey("withheldInteractions"),
					"reference [" + ref.get("index") + "] carries no withheldInteractions key: " + ref);
			assertEquals(null, ref.get("source"),
					"this fixture declares no attribution, so the key must be present and null "
							+ "rather than absent — a client reads it unconditionally: " + ref);
			assertEquals(0, ref.get("withheldInteractions"),
					"and nothing is withheld from it: " + ref);
		}
	}

	/** Mirrors the SSE fixture: an injected drug reference listed FIRST, so ordering is proved. */
	private static class MixedReferenceStubService implements ChartSearchService {

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return new ChartAnswer("Severe allergy to Aspirin [230].", Arrays.asList(
					new RecordReference(231, "drug_reference", "1191", null, null),
					new RecordReference(230, "allergy", "u230", null, Boolean.TRUE),
					new RecordReference(8, "condition", "u8", null, Boolean.TRUE)));
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
}
