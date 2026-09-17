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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService;

/**
 * The service the audit cases of issue #450 drive the controller against: it streams its answer in
 * several fragments, then the citations, then hands over the ungrounded answer and returns the final
 * one — the shape {@code LlmInferenceService.searchStreaming} produces, with the two answers as
 * separate objects as they are there.
 *
 * <p>Shared rather than nested per test class, for the reason {@code StubAuditLogService}'s javadoc
 * gives about itself: this interface's abstract surface is what a copy per file makes expensive to
 * change, and this one exists so issue #450's second case did not become the package's next private
 * copy of it. Subclasses override one overload to model a particular failure.
 *
 * <p><b>Override the SEVEN-arg overload to reach the preliminary channel.</b> The seven-arg form is
 * {@code default} and delegates to the six-arg one, dropping {@code preliminaryReasoningConsumer} on
 * the way — so a subclass that overrides only the six-arg can never exercise it, whatever the
 * controller does with it.
 */
class StreamingChartSearchStub implements ChartSearchService {

	/** The fixture patient, shared with {@code RestControllerContext} so the ids agree. */
	static final Patient PATIENT = RestControllerContext.patient();

	static final String QUESTION = "is she still on isoniazid?";

	/**
	 * The answer as the model emits it — SEVERAL fragments, which is what makes an accumulation of
	 * them observable. With one fragment "the answer produced so far" and "the last fragment
	 * written" are the same string, and a fallback that recorded only the latest would pass.
	 */
	static final String[] FRAGMENTS = { "Yes, ", "isoniazid ", "since 3 March [8]." };

	static final String ANSWER = "Yes, isoniazid since 3 March [8].";

	static ChartAnswer answer() {
		return new ChartAnswer(ANSWER,
				Arrays.asList(new RecordReference(8, "drug_order", "u8", null, Boolean.TRUE)),
				0, 0, 0, Collections.emptyList(),
				ChartSearchAiConstants.SEARCH_MODE_QUERY_SCOPED);
	}

	@Override
	public ChartAnswer search(Patient patient, String question) {
		return answer();
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer) {
		return searchStreaming(patient, question, tokenConsumer, r -> { }, c -> { }, a -> { });
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
			Consumer<List<RecordReference>> citationsConsumer,
			Consumer<ChartAnswer> ungroundedAnswerConsumer) {
		for (String fragment : FRAGMENTS) {
			tokenConsumer.accept(fragment);
		}
		citationsConsumer.accept(answer().getReferences());
		ungroundedAnswerConsumer.accept(answer());
		return answer();
	}

	@Override
	public void warmup(Patient patient) {
	}
}
