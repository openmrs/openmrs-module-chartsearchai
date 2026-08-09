/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.eval;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single eval test case. Fields are optional depending on the eval type.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalCase {

	private String id;

	private String question;

	private List<Integer> expectedRecordIndices;

	private boolean expectedAbsent;

	private List<String> expectedAnswerContains;

	private List<String> expectedAnswerContainsAny;

	private List<String> expectedAnswerNotContains;

	private String simulatedLlmResponse;

	private String payload;

	private List<String> tags;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getQuestion() {
		return question;
	}

	public void setQuestion(String question) {
		this.question = question;
	}

	public List<Integer> getExpectedRecordIndices() {
		return expectedRecordIndices;
	}

	public void setExpectedRecordIndices(List<Integer> expectedRecordIndices) {
		this.expectedRecordIndices = expectedRecordIndices;
	}

	public boolean isExpectedAbsent() {
		return expectedAbsent;
	}

	public void setExpectedAbsent(boolean expectedAbsent) {
		this.expectedAbsent = expectedAbsent;
	}

	public List<String> getExpectedAnswerContains() {
		return expectedAnswerContains;
	}

	public void setExpectedAnswerContains(List<String> expectedAnswerContains) {
		this.expectedAnswerContains = expectedAnswerContains;
	}

	/**
	 * Alternative names for the one thing the answer must name, of which <b>any one</b> suffices —
	 * the OR counterpart to {@link #getExpectedAnswerContains()}, whose elements are all required.
	 *
	 * <p>Exists because a topic can have more than one correct name that share no substring, so no
	 * single literal accepts every correct answer and a second AND element only makes it stricter
	 * (issue #216): asked "Are there any X-ray or radiology reports?", both <i>"No x-ray reports are
	 * recorded."</i> and <i>"No radiology reports are recorded."</i> name the topic.
	 *
	 * <p>An OR element is weaker than an AND element, so a case should carry the narrowest set that
	 * covers the genuinely-correct wordings. {@code AbsentDataEvalTest} asserts that bound rather
	 * than trusting it: an answer naming no topic at all must still fail every case.
	 */
	public List<String> getExpectedAnswerContainsAny() {
		return expectedAnswerContainsAny;
	}

	public void setExpectedAnswerContainsAny(List<String> expectedAnswerContainsAny) {
		this.expectedAnswerContainsAny = expectedAnswerContainsAny;
	}

	public List<String> getExpectedAnswerNotContains() {
		return expectedAnswerNotContains;
	}

	public void setExpectedAnswerNotContains(List<String> expectedAnswerNotContains) {
		this.expectedAnswerNotContains = expectedAnswerNotContains;
	}

	/**
	 * The raw text a simulated LLM returned, parsed by the production extractor exactly as a real
	 * reply is.
	 *
	 * <p>There is deliberately no companion field for the citations that response carries. One
	 * existed ({@code simulatedCitations}) and {@code CitationEvalTest} used it INSTEAD of the parsed
	 * value, which made the one case where the two differed assert against a value production never
	 * produced — issue #219. A case states its input here and its expectation in
	 * {@link #getExpectedRecordIndices()}; anything in between is production's to compute.
	 */
	public String getSimulatedLlmResponse() {
		return simulatedLlmResponse;
	}

	public void setSimulatedLlmResponse(String simulatedLlmResponse) {
		this.simulatedLlmResponse = simulatedLlmResponse;
	}

	public String getPayload() {
		return payload;
	}

	public void setPayload(String payload) {
		this.payload = payload;
	}

	public List<String> getTags() {
		return tags;
	}

	public void setTags(List<String> tags) {
		this.tags = tags;
	}
}
