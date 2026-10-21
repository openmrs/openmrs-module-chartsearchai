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

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * A question reaches the service as the clinician TYPED it, not as OpenMRS core re-encoded it
 * (ADR Decision 375). Core HTML-escapes the strings of a request body before this controller reads
 * them, so "eucalyptus &amp; diphenhydramine" arrives spelled {@code &amp;amp;} — and every
 * reading of the question downstream (the router's shapes, a product's joined names) then reads an
 * entity instead of the character the clinician typed.
 *
 * <p>The chat endpoint's case runs through the real conversation service and a real Hub transport, so it
 * is {@code ProviderStreamPersistenceTest.theChatQuestionIsReadAsTypedEverywhereItIsRead}.
 */
public class ChartSearchAiHtmlEscapedQuestionTest {

	private ChartSearchAiRestController controller;

	private final RestControllerContext openmrsContext = new RestControllerContext();

	private String received;

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setAuditLogService(new StubAuditLogService());
		controller.setChartSearchService(new CapturingStubService());
		controller.setPatientAccessCheck((user, patient) -> true);
		openmrsContext.install();
	}

	@AfterEach
	public void restoreContext() {
		openmrsContext.restore();
	}

	private String asked(String posted) {
		ResponseEntity<Object> response = controller.search(RestControllerContext.searchBody(posted));
		assertEquals(HttpStatus.OK, response.getStatusCode(), "the handler must have reached the service");
		return received;
	}

	@Test
	public void anAmpersandCoreEscapedReachesTheServiceAsTheAmpersandTyped() {
		assertEquals("Is her eucalyptus & diphenhydramine & phenylephrine safe?",
				asked("Is her eucalyptus &amp; diphenhydramine &amp; phenylephrine safe?"));
	}

	@Test
	public void everyCharacterCoreEscapesIsRestored() {
		assertEquals("Is her \"Co-trimoxazole\" safe if her CD4 < 200 and it's > 5 days?",
				asked("Is her &quot;Co-trimoxazole&quot; safe if her CD4 &lt; 200 and it&#39;s &gt; 5 days?"));
	}

	@Test
	public void aQuestionCoreLeftAloneIsUnchanged() {
		assertEquals("Can I give her fluconazole with her lisinopril?",
				asked("Can I give her fluconazole with her lisinopril?"));
	}

	private ChartSearchService.ChartAnswer answer() {
		return new ChartSearchService.ChartAnswer("No answer.",
				Collections.<ChartSearchService.RecordReference> emptyList(), 0, 0, 0,
				Collections.<SafetyWarning> emptyList(), null, null, null, null, null);
	}

	/** Records the question the controller hands the service. */
	private class CapturingStubService implements ChartSearchService {

		@Override
		public ChartAnswer search(Patient patient, String question) {
			received = question;
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer) {
			received = question;
			return answer();
		}

		@Override
		public ChartAnswer searchStreaming(Patient patient, String question,
				Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				Consumer<List<RecordReference>> citationsConsumer,
				Consumer<ChartAnswer> ungroundedAnswerConsumer) {
			received = question;
			return answer();
		}

		@Override
		public void warmup(Patient patient) {
		}
	}
}
