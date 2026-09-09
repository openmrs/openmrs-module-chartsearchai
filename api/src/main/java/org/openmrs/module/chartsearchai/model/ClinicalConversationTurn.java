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
import java.util.UUID;

/**
 * One user-question/provider-result pair in a {@link ClinicalConversation}. The canonical answer
 * text is stored separately for display, audit, and history replay. {@code providerPayload} stores
 * the complete serialized answer envelope without taking ownership of validation, evidence,
 * safety, In-Depth, structured blocks, or provider extensions.
 */
public class ClinicalConversationTurn implements Serializable {

	public static final String MEDIA_TYPE_JSON = "application/json";

	private static final long serialVersionUID = 1L;

	private Integer turnId;

	private String uuid = UUID.randomUUID().toString();

	private ClinicalConversation conversation;

	private Integer ordinal;

	private String requestId;

	private String question;

	private String answerText;

	private String providerPayload;

	private String payloadMediaType;

	private String terminalState;

	private String problemCode;

	private Date startedAt;

	private Date completedAt;

	private ChartSearchAuditLog auditLog;

	public Integer getTurnId() {
		return turnId;
	}

	public void setTurnId(Integer turnId) {
		this.turnId = turnId;
	}

	public String getUuid() {
		return uuid;
	}

	public void setUuid(String uuid) {
		this.uuid = uuid;
	}

	public ClinicalConversation getConversation() {
		return conversation;
	}

	public void setConversation(ClinicalConversation conversation) {
		this.conversation = conversation;
	}

	public Integer getOrdinal() {
		return ordinal;
	}

	public void setOrdinal(Integer ordinal) {
		this.ordinal = ordinal;
	}

	public String getRequestId() {
		return requestId;
	}

	public void setRequestId(String requestId) {
		this.requestId = requestId;
	}

	public String getQuestion() {
		return question;
	}

	public void setQuestion(String question) {
		this.question = question;
	}

	public String getAnswerText() {
		return answerText;
	}

	public void setAnswerText(String answerText) {
		this.answerText = answerText;
	}

	public String getProviderPayload() {
		return providerPayload;
	}

	public void setProviderPayload(String providerPayload) {
		this.providerPayload = providerPayload;
	}

	public String getPayloadMediaType() {
		return payloadMediaType;
	}

	public void setPayloadMediaType(String payloadMediaType) {
		this.payloadMediaType = payloadMediaType;
	}

	public String getTerminalState() {
		return terminalState;
	}

	public void setTerminalState(String terminalState) {
		this.terminalState = terminalState;
	}

	public String getProblemCode() {
		return problemCode;
	}

	public void setProblemCode(String problemCode) {
		this.problemCode = problemCode;
	}

	public Date getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(Date startedAt) {
		this.startedAt = startedAt;
	}

	public Date getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(Date completedAt) {
		this.completedAt = completedAt;
	}

	public ChartSearchAuditLog getAuditLog() {
		return auditLog;
	}

	public void setAuditLog(ChartSearchAuditLog auditLog) {
		this.auditLog = auditLog;
	}
}
