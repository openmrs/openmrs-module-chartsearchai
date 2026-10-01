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
 * Header for one provider-specific clinical conversation about a patient chart. Provider and mode
 * are immutable conversation identity: selecting a different provider or changing context mode
 * closes this conversation and starts another, preserving the original in history.
 */
public class ClinicalConversation implements Serializable {

	public static final String STATUS_ACTIVE = "active";

	public static final String STATUS_CLOSED = "closed";

	public static final String STATUS_EXPIRED = "expired";

	private static final long serialVersionUID = 1L;

	private Integer conversationId;

	private String uuid = UUID.randomUUID().toString();

	private Patient patient;

	private User user;

	private String providerId;

	private String providerMode;

	private Date startedAt;

	private Date lastActivityAt;

	private Date endedAt;

	private String title;

	private String status = STATUS_ACTIVE;

	public Integer getConversationId() {
		return conversationId;
	}

	public void setConversationId(Integer conversationId) {
		this.conversationId = conversationId;
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

	public String getProviderId() {
		return providerId;
	}

	public void setProviderId(String providerId) {
		this.providerId = providerId;
	}

	public String getProviderMode() {
		return providerMode;
	}

	public void setProviderMode(String providerMode) {
		this.providerMode = providerMode;
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
}
