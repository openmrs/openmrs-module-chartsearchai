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
 * One message within a {@link ChatSession}. Ordinal gives linear-history sort;
 * parent_message_id is reserved for future branching (regenerate-answer UX).
 * The audit_log_id link on assistant messages bridges back to the existing
 * {@link ChartSearchAuditLog} regulatory-audit row.
 */
public class ChatMessage implements Serializable {

	public static final String ROLE_SYSTEM = "system";

	public static final String ROLE_USER = "user";

	public static final String ROLE_ASSISTANT = "assistant";

	public static final String FINISH_STOP = "stop";

	public static final String FINISH_LENGTH = "length";

	public static final String FINISH_ABORTED = "aborted";

	private static final long serialVersionUID = 1L;

	private Integer messageId;

	private String uuid = UUID.randomUUID().toString();

	private ChatSession session;

	private ChatMessage parentMessage;

	private Integer ordinal;

	private String role;

	private String content;

	private Date createdAt;

	private ChartSearchAuditLog auditLog;

	private Integer inputTokens;

	private Integer outputTokens;

	private boolean summary;

	private String finishReason;

	public Integer getMessageId() {
		return messageId;
	}

	public void setMessageId(Integer messageId) {
		this.messageId = messageId;
	}

	public String getUuid() {
		return uuid;
	}

	public void setUuid(String uuid) {
		this.uuid = uuid;
	}

	public ChatSession getSession() {
		return session;
	}

	public void setSession(ChatSession session) {
		this.session = session;
	}

	public ChatMessage getParentMessage() {
		return parentMessage;
	}

	public void setParentMessage(ChatMessage parentMessage) {
		this.parentMessage = parentMessage;
	}

	public Integer getOrdinal() {
		return ordinal;
	}

	public void setOrdinal(Integer ordinal) {
		this.ordinal = ordinal;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public Date getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Date createdAt) {
		this.createdAt = createdAt;
	}

	public ChartSearchAuditLog getAuditLog() {
		return auditLog;
	}

	public void setAuditLog(ChartSearchAuditLog auditLog) {
		this.auditLog = auditLog;
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

	public boolean isSummary() {
		return summary;
	}

	public void setSummary(boolean summary) {
		this.summary = summary;
	}

	public String getFinishReason() {
		return finishReason;
	}

	public void setFinishReason(String finishReason) {
		this.finishReason = finishReason;
	}
}
