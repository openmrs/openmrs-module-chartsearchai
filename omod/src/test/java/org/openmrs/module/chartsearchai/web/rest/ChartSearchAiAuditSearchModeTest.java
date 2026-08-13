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

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Issue #178 — what the {@code chartsearchai_audit_log} row actually records as its search mode.
 *
 * <p>Both audit-write sites used to branch on the {@code chartsearchai.embedding.preFilter} global
 * property alone, so {@code queryScoped} — the shipped {@code chartsearchai.chartMode} default —
 * could not appear in the column at all: every row on a default install said {@code full-chart}
 * while the prompt carried a query-scoped slice. A maintainer reading these rows to reconstruct
 * what a clinician was shown was reading a constant, and an A/B between the two chart modes could
 * not be told apart in the log at all.
 *
 * <p>These assertions are on the row the controller PERSISTS, not on any projection of it, and they
 * cover both write sites: the blocking {@code /search} handler and the streaming orchestration in
 * each of its two shapes (the classic single {@code done}, and the async-grounding early
 * {@code done} that audits the ungrounded answer instead of the returned one). Two sites deriving
 * the mode separately is how they came to disagree, so the fix is that neither derives it — the
 * answer states it and the controller writes what it is told.
 */
public class ChartSearchAiAuditSearchModeTest {

	private static final String PATIENT_UUID = "uuid-7";

	private ChartSearchAiRestController controller;

	private CapturingAuditLogService audit;

	private ByteArrayOutputStream out;

	private PatientService priorPatientService;

	private AdministrationService priorAdministrationService;

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		audit = new CapturingAuditLogService();
		controller.setAuditLogService(audit);
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();

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

	@Test
	public void blockingSearch_recordsTheModeTheAnswerWasBuiltIn() {
		// The headline case: a query-scoped answer, which is what a default install produces on
		// every request, and which the column could not express before this.
		controller.setChartSearchService(
				new StubService(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED));

		ResponseEntity<Object> response = controller.search(searchBody());

		assertEquals(HttpStatus.OK, response.getStatusCode(), "the handler must have reached the audit write");
		assertEquals(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED, savedMode(),
				"a queryScoped answer must not be filed as full-chart");
	}

	@Test
	public void blockingSearch_stillRecordsTheTwoModesItAlwaysCould() {
		// The two pre-existing values keep their exact spellings: these rows are read outside this
		// module, so #178 adds a third value rather than re-spelling two.
		for (String mode : new String[] { ChartSearchAiConstants.SEARCH_MODE_FULL_CHART,
				ChartSearchAiConstants.SEARCH_MODE_PRE_FILTER }) {
			audit.saved.clear();
			controller.setChartSearchService(new StubService(mode));

			controller.search(searchBody());

			assertEquals(mode, savedMode());
		}
	}

	@Test
	public void streamingSearch_recordsTheModeTheAnswerWasBuiltIn() {
		controller.setChartSearchService(
				new StubService(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED));

		controller.streamAnswer(out, patient(), "any infections?", user(), false);

		assertEquals(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED, savedMode(),
				"the streaming site must file the same mode the blocking one does");
	}

	@Test
	public void asyncGroundingSearch_recordsTheModeOnTheUngroundedAnswerItAudits() {
		// The async shape audits the UNGROUNDED answer handed to the consumer mid-call, not the
		// returned one — a different object, and the reason a mode carried per-answer has to be set
		// on both. This is the site that would silently file 'unknown' if only the returned answer
		// carried a mode.
		controller.setChartSearchService(
				new StubService(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED));

		controller.streamAnswer(out, patient(), "any infections?", user(), true);

		assertEquals(1, audit.saved.size(), "the async shape must still write exactly one row");
		assertEquals(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED, savedMode());
	}

	@Test
	public void everySite_writesAValueTheNotNullColumnCanTake() {
		// search_mode is NOT NULL, so an answer that states no mode must still produce a row — and
		// one that says so rather than one that claims a mode nobody resolved.
		controller.setChartSearchService(new StubService(null));

		controller.search(searchBody());
		controller.streamAnswer(out, patient(), "any infections?", user(), false);

		assertEquals(2, audit.saved.size());
		for (ChartSearchAuditLog row : audit.saved) {
			assertEquals(ChartSearchAiConstants.SEARCH_MODE_UNKNOWN, row.getSearchMode(),
					"an unlabelled answer must file as unknown, never as one of the real modes");
		}
	}

	private String savedMode() {
		assertNotNull(audit.saved.isEmpty() ? null : audit.saved.get(0), "no audit row was written");
		return audit.saved.get(0).getSearchMode();
	}

	private static Map<String, String> searchBody() {
		Map<String, String> body = new HashMap<String, String>();
		body.put("patient", PATIENT_UUID);
		body.put("question", "any infections?");
		return body;
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(7);
		p.setUuid(PATIENT_UUID);
		return p;
	}

	private static User user() {
		return new User(3);
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
	 * Global properties as an unconfigured installation answers them: the two-arg form returns the
	 * caller's own default and the one-arg form null. That matters here — it is the configuration on
	 * which the old two-way branch resolved to {@code full-chart}, which is what a default install
	 * has and what made every row on one wrong.
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

	/** Retains the rows the controller saved, which is the whole point of this class. */
	private static final class CapturingAuditLogService extends StubAuditLogService {

		final List<ChartSearchAuditLog> saved = new ArrayList<ChartSearchAuditLog>();

		@Override
		public ChartSearchAuditLog saveAuditLog(ChartSearchAuditLog auditLog) {
			saved.add(auditLog);
			return super.saveAuditLog(auditLog);
		}
	}

	/**
	 * Returns answers labelled with one mode, on every path. It fires the ungrounded-answer consumer
	 * with a SEPARATE answer object carrying the same label, exactly as {@code LlmInferenceService}
	 * does — the async audit site reads that one, so a stub that reused the returned object would
	 * hide the very divergence this class is about.
	 */
	private static final class StubService implements ChartSearchService {

		private final String searchMode;

		StubService(String searchMode) {
			this.searchMode = searchMode;
		}

		private ChartAnswer answer() {
			return new ChartAnswer("Has TB [8].",
					Arrays.asList(new RecordReference(8, "condition", "u8", null, Boolean.TRUE)),
					0, 0, 0, Collections.emptyList(), searchMode);
		}

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer) {
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer) {
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			ungroundedAnswerConsumer.accept(answer());
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer,
				Consumer<String> preliminaryReasoningConsumer) {
			ungroundedAnswerConsumer.accept(answer());
			return answer();
		}
	}
}
