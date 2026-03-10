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
 * Audit log entry for every AI query made against a patient's chart. Records the question asked,
 * the response generated, which data chunks were used, and the model that produced the answer.
 */
public class AiQueryLog implements Serializable {

	private static final long serialVersionUID = 1L;

	private Integer queryId;

	private Patient patient;

	private User user;

	private String queryText;

	private String responseText;

	private String chunksUsed;

	private String modelUsed;

	private Date dateQueried;

	public Integer getQueryId() {
		return queryId;
	}

	public void setQueryId(Integer queryId) {
		this.queryId = queryId;
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

	public String getQueryText() {
		return queryText;
	}

	public void setQueryText(String queryText) {
		this.queryText = queryText;
	}

	public String getResponseText() {
		return responseText;
	}

	public void setResponseText(String responseText) {
		this.responseText = responseText;
	}

	public String getChunksUsed() {
		return chunksUsed;
	}

	public void setChunksUsed(String chunksUsed) {
		this.chunksUsed = chunksUsed;
	}

	public String getModelUsed() {
		return modelUsed;
	}

	public void setModelUsed(String modelUsed) {
		this.modelUsed = modelUsed;
	}

	public Date getDateQueried() {
		return dateQueried;
	}

	public void setDateQueried(Date dateQueried) {
		this.dateQueried = dateQueried;
	}
}
