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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;

/**
 * Issue #229 — what the {@code chartsearchai_audit_log} row records about the size of the reference
 * material the module injected into the prompt.
 *
 * <p>Before this, that size existed only as a DEBUG line inside {@code DrugReferenceInjector}, and
 * the {@code log.level} global property is not applied at OpenMRS startup — so a clinician's answer
 * could be built from a slice nobody could measure after the fact. The row is the durable channel
 * the ticket asks for; these cases pin the write at all three sites the controller audits from.
 *
 * <p>The row is a projection of the ANSWER, never re-derived here — the same discipline issue #178
 * imposed on {@code search_mode} after two write sites derived it separately and disagreed. So the
 * assertions are that the controller writes what it is told, including telling it nothing: an answer
 * that states no slice must file nulls rather than zeros, because zero is a real measurement (the
 * prompt carried no reference material) and must not be indistinguishable from "nobody looked".
 */
public class ChartSearchAiAuditReferenceSliceTest {

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
	public void blockingSearch_recordsTheSliceTheAnswerStates() {
		controller.setChartSearchService(new StubService(slice(4, 5183)));

		controller.search(RestControllerContext.searchBody("any infections?"));

		assertFalse(audit.saved.isEmpty(), "the handler must have reached the audit write");
		assertEquals(Integer.valueOf(4), audit.saved.get(0).getReferenceSliceRecords());
		assertEquals(Integer.valueOf(5183), audit.saved.get(0).getReferenceSliceChars());
	}

	@Test
	public void asyncGroundingSearch_recordsTheSliceOfTheAnswerItAudits() {
		// The async shape audits the UNGROUNDED answer handed to the consumer mid-call, not the
		// returned one. The two are given DIFFERENT slices here, which no real pipeline produces —
		// with the same slice on both, this case would pass whichever object the controller read.
		controller.setChartSearchService(new StubService(slice(4, 5183), slice(1, 903)));

		controller.streamAnswer(out, RestControllerContext.patient(), "any infections?",
				RestControllerContext.user(), true);

		assertEquals(1, audit.saved.size(), "the async shape must still write exactly one row");
		assertEquals(Integer.valueOf(4), audit.saved.get(0).getReferenceSliceRecords(),
				"the early-done row must carry the slice of the answer it was built from");
	}

	@Test
	public void classicStreamingSearch_recordsTheSliceOfTheAnswerItAudits() {
		// The mirror, so the pair pins WHICH object each shape reads rather than only that some
		// object was read.
		controller.setChartSearchService(new StubService(slice(4, 5183), slice(1, 903)));

		controller.streamAnswer(out, RestControllerContext.patient(), "any infections?",
				RestControllerContext.user(), false);

		assertEquals(1, audit.saved.size());
		assertEquals(Integer.valueOf(1), audit.saved.get(0).getReferenceSliceRecords(),
				"the classic row must carry the slice of the answer the service returned");
	}

	@Test
	public void everySite_filesNullRatherThanZeroWhenTheAnswerStatesNoSlice() {
		// Zero is a real reading and the commonest one; "not stated" is not a measurement at all.
		// Collapsing them would make an unmeasured row look like a prompt that carried nothing,
		// which is the wrong-signal-indistinguishable-from-a-right-one shape #178 was.
		controller.setChartSearchService(new StubService(null));

		controller.search(RestControllerContext.searchBody("any infections?"));
		controller.streamAnswer(out, RestControllerContext.patient(), "any infections?",
				RestControllerContext.user(), false);

		assertEquals(2, audit.saved.size());
		for (ChartSearchAuditLog row : audit.saved) {
			assertNull(row.getReferenceSliceRecords(),
					"an answer stating no slice must file null, never 0");
			assertNull(row.getReferenceSliceChars());
		}
	}

	@Test
	public void aRowThatMeasuredAnEmptySliceStillFilesZero() {
		// The other side of the same distinction: a request that injected nothing DID measure, and
		// zero is what it measured. Filing null there would lose the only evidence that the slice
		// was looked at. (Why it injected nothing — no match, the feature off, or a caught injection
		// failure — is not something this number separates; see ChartAnswer.getReferenceSlice().)
		controller.setChartSearchService(new StubService(slice(0, 0)));

		controller.search(RestControllerContext.searchBody("any infections?"));

		assertEquals(Integer.valueOf(0), audit.saved.get(0).getReferenceSliceRecords());
		assertEquals(Integer.valueOf(0), audit.saved.get(0).getReferenceSliceChars());
	}

	@Test
	public void theAuditListingPublishesTheSliceBesideTheCitationCount() {
		// The row is only durable if it can be read back without SQL. The listing already exposes
		// referenceCount — the citations IN the answer — and these two are a different population:
		// the reference material put IN FRONT of the model, most of which is never cited.
		ChartSearchAuditLog row = new ChartSearchAuditLog();
		row.setReferenceCount(1);
		row.setReferenceSliceRecords(4);
		row.setReferenceSliceChars(5183);
		audit.listed.add(row);

		Object body = controller.getAuditLogs(null, null, null, null, 0, 50).getBody();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> results =
				(List<Map<String, Object>>) ((Map<String, Object>) body).get("results");

		assertEquals(1, results.size());
		assertEquals(4, results.get(0).get("referenceSliceRecords"));
		assertEquals(5183, results.get(0).get("referenceSliceChars"));
		assertTrue(results.get(0).containsKey("referenceCount"),
				"the citation count stays beside it; the two answer different questions");
	}

	private static ChartSearchAiUtils.ReferenceSlice slice(int records, int characters) {
		return new ChartSearchAiUtils.ReferenceSlice(records, characters);
	}

	/**
	 * The service the controller is driven against, in the shape
	 * {@code ChartSearchAiAuditSearchModeTest} established: the ungrounded-answer consumer is handed
	 * a SEPARATE object from the returned one, exactly as {@code LlmInferenceService} does, so a
	 * case can pin which of the two a given audit shape reads.
	 */
	private static final class StubService implements ChartSearchService {

		private final ChartSearchAiUtils.ReferenceSlice ungrounded;

		private final ChartSearchAiUtils.ReferenceSlice returned;

		StubService(ChartSearchAiUtils.ReferenceSlice slice) {
			this(slice, slice);
		}

		StubService(ChartSearchAiUtils.ReferenceSlice ungrounded,
				ChartSearchAiUtils.ReferenceSlice returned) {
			this.ungrounded = ungrounded;
			this.returned = returned;
		}

		private ChartAnswer answer(ChartSearchAiUtils.ReferenceSlice slice) {
			return new ChartAnswer("Has TB [8].",
					Arrays.asList(new RecordReference(8, "condition", "u8", null, Boolean.TRUE)),
					0, 0, 0, Collections.emptyList(), ChartSearchAiConstants.SEARCH_MODE_FULL_CHART,
					slice);
		}

		@Override
		public ChartAnswer search(Patient patient, String question) {
			return answer(returned);
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			return answer(returned);
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			ungroundedAnswerConsumer.accept(answer(ungrounded));
			return answer(returned);
		}
	}
}
