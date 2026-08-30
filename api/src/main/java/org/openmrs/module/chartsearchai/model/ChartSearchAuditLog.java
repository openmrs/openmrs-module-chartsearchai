/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.model;

import java.io.Serializable;
import java.util.Date;

import org.openmrs.Patient;
import org.openmrs.User;

/**
 * Records each AI chart search query for audit and compliance purposes.
 */
public class ChartSearchAuditLog implements Serializable {

	private static final long serialVersionUID = 1L;

	private Integer auditLogId;

	private User user;

	private Patient patient;

	private String question;

	private String answer;

	private Integer referenceCount;

	/**
	 * How many module-supplied reference records the prompt behind this answer carried, or null when
	 * the producer stated none (issue #229). Null is not zero: zero is a real measurement — a
	 * question that matched no reference entry — while null says nothing was measured at all.
	 *
	 * <p>A different population from {@link #referenceCount}, which counts the citations IN the
	 * answer. Most injected reference material is never cited, so this is the prompt COST and that
	 * one is the answer's use of it.
	 */
	private Integer referenceSliceRecords;

	/**
	 * How many characters of module-supplied reference-record text that prompt carried, or null when
	 * the producer stated none. Beside the count rather than instead of it: what crowds a chart
	 * record out of the context window is characters, and what bounds how many citations the model
	 * is offered is the count.
	 */
	private Integer referenceSliceChars;

	private String searchMode;

	private Long responseTimeMs;

	private Integer inputTokens;

	private Integer outputTokens;

	private String rating;

	private String feedbackComment;

	private Date dateCreated;

	public Integer getAuditLogId() {
		return auditLogId;
	}

	public void setAuditLogId(Integer auditLogId) {
		this.auditLogId = auditLogId;
	}

	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}

	public Patient getPatient() {
		return patient;
	}

	public void setPatient(Patient patient) {
		this.patient = patient;
	}

	public String getQuestion() {
		return question;
	}

	public void setQuestion(String question) {
		this.question = question;
	}

	public String getAnswer() {
		return answer;
	}

	public void setAnswer(String answer) {
		this.answer = answer;
	}

	public Integer getReferenceCount() {
		return referenceCount;
	}

	public void setReferenceCount(Integer referenceCount) {
		this.referenceCount = referenceCount;
	}

	public Integer getReferenceSliceRecords() {
		return referenceSliceRecords;
	}

	public void setReferenceSliceRecords(Integer referenceSliceRecords) {
		this.referenceSliceRecords = referenceSliceRecords;
	}

	public Integer getReferenceSliceChars() {
		return referenceSliceChars;
	}

	public void setReferenceSliceChars(Integer referenceSliceChars) {
		this.referenceSliceChars = referenceSliceChars;
	}

	public String getSearchMode() {
		return searchMode;
	}

	public void setSearchMode(String searchMode) {
		this.searchMode = searchMode;
	}

	public Long getResponseTimeMs() {
		return responseTimeMs;
	}

	public void setResponseTimeMs(Long responseTimeMs) {
		this.responseTimeMs = responseTimeMs;
	}

	public Integer getInputTokens() {
		return inputTokens;
	}

	public void setInputTokens(Integer inputTokens) {
		this.inputTokens = inputTokens;
	}

	public Integer getOutputTokens() {
		return outputTokens;
	}

	public void setOutputTokens(Integer outputTokens) {
		this.outputTokens = outputTokens;
	}

	public Date getDateCreated() {
		return dateCreated;
	}

	public void setDateCreated(Date dateCreated) {
		this.dateCreated = dateCreated;
	}

	public String getRating() {
		return rating;
	}

	public void setRating(String rating) {
		this.rating = rating;
	}

	public String getFeedbackComment() {
		return feedbackComment;
	}

	public void setFeedbackComment(String feedbackComment) {
		this.feedbackComment = feedbackComment;
	}
}
