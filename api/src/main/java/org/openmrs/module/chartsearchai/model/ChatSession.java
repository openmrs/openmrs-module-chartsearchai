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

import org.openmrs.Patient;
import org.openmrs.User;

/**
 * Header row for one multi-turn chat conversation between a user and the assistant
 * about a specific patient chart. Conversation content lives in {@link ChatMessage}
 * rows; this row carries the (patient, user) scope plus thread-level lifecycle.
 */
public class ChatSession implements Serializable {

	public static final String STATUS_ACTIVE = "active";

	public static final String STATUS_CLOSED = "closed";

	public static final String STATUS_EXPIRED = "expired";

	private static final long serialVersionUID = 1L;

	private Integer sessionId;

	private String uuid = UUID.randomUUID().toString();

	private Patient patient;

	private User user;

	private Date startedAt;

	private Date lastActivityAt;

	private Date endedAt;

	private String title;

	private String status = STATUS_ACTIVE;

	/**
	 * Patient chart text snapshot, frozen at session create. Replayed verbatim
	 * as the first user message on every turn of this session so the LLM's
	 * prompt cache hits on a stable system+chart prefix. Null only on rows
	 * created before changeset chartsearchai-008 or when the chart-building
	 * pipeline returned an empty chart (deferred to lazy build on first chat).
	 */
	private String chartSnapshot;

	/**
	 * JSON-serialized list of {@link org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping}
	 * captured alongside {@link #chartSnapshot}. Holds index → (resourceType,
	 * resourceUuid, date) so citations like [57] in the LLM's answer can be
	 * resolved without rebuilding the chart. Null on legacy rows.
	 */
	private String chartMappingsJson;

	private Date chartBuiltAt;

	public Integer getSessionId() {
		return sessionId;
	}

	public void setSessionId(Integer sessionId) {
		this.sessionId = sessionId;
	}

	public String getUuid() {
		return uuid;
	}

	public void setUuid(String uuid) {
		this.uuid = uuid;
	}

	public Patient getPatient() {
		return patient;
	}

	public void setPatient(Patient patient) {
		this.patient = patient;
	}

	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}

	public Date getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(Date startedAt) {
		this.startedAt = startedAt;
	}

	public Date getLastActivityAt() {
		return lastActivityAt;
	}

	public void setLastActivityAt(Date lastActivityAt) {
		this.lastActivityAt = lastActivityAt;
	}

	public Date getEndedAt() {
		return endedAt;
	}

	public void setEndedAt(Date endedAt) {
		this.endedAt = endedAt;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getChartSnapshot() {
		return chartSnapshot;
	}

	public void setChartSnapshot(String chartSnapshot) {
		this.chartSnapshot = chartSnapshot;
	}

	public String getChartMappingsJson() {
		return chartMappingsJson;
	}

	public void setChartMappingsJson(String chartMappingsJson) {
		this.chartMappingsJson = chartMappingsJson;
	}

	public Date getChartBuiltAt() {
		return chartBuiltAt;
	}

	public void setChartBuiltAt(Date chartBuiltAt) {
		this.chartBuiltAt = chartBuiltAt;
	}
}
