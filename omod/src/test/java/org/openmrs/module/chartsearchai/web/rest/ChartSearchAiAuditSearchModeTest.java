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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
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

	private ChartSearchAiRestController controller;

	private CapturingAuditLogService audit;

	private ByteArrayOutputStream out;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		audit = new CapturingAuditLogService();
		controller.setAuditLogService(audit);
		controller.setPatientAccessCheck((user, patient) -> true);
		out = new ByteArrayOutputStream();

		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
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

		controller.streamAnswer(out, RestControllerContext.patient(), "any infections?",
				RestControllerContext.user(), false);

		assertEquals(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED, savedMode(),
				"the streaming site must file the same mode the blocking one does");
	}

	@Test
	public void asyncGroundingSearch_recordsTheModeOnTheUngroundedAnswerItAudits() {
		// The async shape audits the UNGROUNDED answer handed to the consumer mid-call, not the
		// returned one — a different object, which is why the mode has to be set on both.
		//
		// The two answers are deliberately given DIFFERENT labels here, which no real pipeline
		// produces: with the same label on both, this case passes whichever object the controller
		// reads, and could not fail for the reason its name gives. The value asserted is the
		// ungrounded one; its mirror below asserts the returned one for the classic shape, and the
		// pair is what pins which object each shape audits.
		controller.setChartSearchService(new StubService(
				ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED, ChartSearchAiConstants.SEARCH_MODE_FULL_CHART));

		controller.streamAnswer(out, RestControllerContext.patient(), "any infections?",
				RestControllerContext.user(), true);

		assertEquals(1, audit.saved.size(), "the async shape must still write exactly one row");
		assertEquals(ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED, savedMode(),
				"the early-done row must carry the mode of the answer it was built from");
	}

	@Test
	public void classicStreamingSearch_recordsTheModeOnTheAnswerItAudits() {
		// The mirror: with async grounding off the row comes from the RETURNED answer, so the same
		// divergent stub must file the other label. Together these two pin which object each shape
		// reads, rather than only that some object was read.
		controller.setChartSearchService(new StubService(
				ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED, ChartSearchAiConstants.SEARCH_MODE_FULL_CHART));

		controller.streamAnswer(out, RestControllerContext.patient(), "any infections?",
				RestControllerContext.user(), false);

		assertEquals(1, audit.saved.size());
		assertEquals(ChartSearchAiConstants.SEARCH_MODE_FULL_CHART, savedMode(),
				"the classic row must carry the mode of the answer the service returned");
	}

	@Test
	public void theColumnsVocabularyIsAWireContract_soItsSpellingsArePinnedAsLiterals() {
		// Every other assertion in this PR compares a constant to a constant, which cannot notice a
		// RENAME. That matters twice over: the constants' javadoc makes an external-contract claim
		// ("these rows are read outside this module, so #178 ADDS a third value rather than
		// re-spelling two"), and SEARCH_MODE_QUERY_SCOPED is defined AS the chartMode GP token — so
		// renaming that token, a config-surface change that looks unrelated to auditing, would
		// silently rewrite every subsequent row for those readers with a fully green suite.
		//
		// Literals, deliberately. This is the one assertion here that is allowed to be brittle: it
		// should fail the moment a spelling moves, and its failure is the notification that a wire
		// contract is being changed rather than a constant renamed.
		assertEquals("pre-filter", ChartSearchAiConstants.SEARCH_MODE_PRE_FILTER);
		assertEquals("full-chart", ChartSearchAiConstants.SEARCH_MODE_FULL_CHART);
		assertEquals("queryScoped", ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED);
		assertEquals("unknown", ChartSearchAiConstants.SEARCH_MODE_UNKNOWN);
		assertEquals(ChartSearchAiConstants.CHART_MODE_QUERY_SCOPED,
				ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED,
				"the row must keep naming the scoped mode with the token an operator sets");
		for (String mode : new String[] { ChartSearchAiConstants.SEARCH_MODE_PRE_FILTER,
				ChartSearchAiConstants.SEARCH_MODE_FULL_CHART,
				ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED,
				ChartSearchAiConstants.SEARCH_MODE_UNKNOWN }) {
			assertTrue(mode.length() <= 20,
					"search_mode is varchar(20) NOT NULL; '" + mode + "' would be truncated or rejected");
		}
	}

	@Test
	public void everySite_writesAValueTheNotNullColumnCanTake() {
		// search_mode is NOT NULL, so an answer that states no mode must still produce a row — and
		// one that says so rather than one that claims a mode nobody resolved.
		controller.setChartSearchService(new StubService(null));

		controller.search(searchBody());
		controller.streamAnswer(out, RestControllerContext.patient(), "any infections?",
				RestControllerContext.user(), false);

		assertEquals(2, audit.saved.size());
		for (ChartSearchAuditLog row : audit.saved) {
			assertEquals(ChartSearchAiConstants.SEARCH_MODE_UNKNOWN, row.getSearchMode(),
					"an unlabelled answer must file as unknown, never as one of the real modes");
		}
	}

	private String savedMode() {
		assertFalse(audit.saved.isEmpty(), "no audit row was written");
		return audit.saved.get(0).getSearchMode();
	}

	private static Map<String, String> searchBody() {
		Map<String, String> body = new HashMap<String, String>();
		body.put("patient", RestControllerContext.PATIENT_UUID);
		body.put("question", "any infections?");
		return body;
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
	 * The service the controller is driven against. It hands the ungrounded-answer consumer a SEPARATE
	 * answer object from the one it returns, exactly as {@code LlmInferenceService} does — the async
	 * audit site reads that one, so a stub that reused a single object could not tell the two sites
	 * apart at all.
	 *
	 * <p>The two objects normally carry the same mode, which is what a real pipeline produces. The
	 * two-argument constructor lets a case give them DIFFERENT modes, which no pipeline produces and
	 * which is the only way to assert WHICH of the two a given audit shape read.
	 */
	private static final class StubService implements ChartSearchService {

		private final String ungroundedMode;

		private final String returnedMode;

		/** Both answers carry {@code searchMode} — the shape a real pipeline produces. */
		StubService(String searchMode) {
			this(searchMode, searchMode);
		}

		/** The two answers carry DIFFERENT modes, so a case can pin WHICH of them a row came from.
		 *  No real pipeline does this: {@code LlmInferenceService} resolves one label and sets it on
		 *  both, which is what {@code LlmInferenceServiceQueryScopedTest} pins. */
		StubService(String ungroundedMode, String returnedMode) {
			this.ungroundedMode = ungroundedMode;
			this.returnedMode = returnedMode;
		}

		private ChartAnswer answer(String searchMode) {
			return new ChartAnswer("Has TB [8].",
					Arrays.asList(new RecordReference(8, "condition", "u8", null, Boolean.TRUE)),
					0, 0, 0, Collections.emptyList(), searchMode);
		}

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return answer(returnedMode);
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			return answer(returnedMode);
		}

		// The 7-arg overload is `default` and delegates to this one, and the 7-arg is what the
		// controller calls — so not overriding it keeps that delegation on the path under test. (The
		// 4- and 5-arg defaults delegate DOWNWARD to the 3-arg instead and never reach a consumer;
		// nothing here drives them.)
		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			ungroundedAnswerConsumer.accept(answer(ungroundedMode));
			return answer(returnedMode);
		}
	}
}
