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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Date;

import org.openmrs.Patient;

/**
 * Persistent entity representing a vector embedding for a single OpenMRS clinical record.
 * Maps to the {@code chart_embedding} table.
 */
public class ChartEmbedding implements Serializable {

	private static final long serialVersionUID = 1L;

	private Integer embeddingId;

	private Patient patient;

	private String resourceType;

	private Integer resourceId;

	private String textContent;

	private byte[] embedding;

	private Date dateCreated;

	public Integer getEmbeddingId() {
		return embeddingId;
	}

	public void setEmbeddingId(Integer embeddingId) {
		this.embeddingId = embeddingId;
	}

	public Patient getPatient() {
		return patient;
	}

	public void setPatient(Patient patient) {
		this.patient = patient;
	}

	public String getResourceType() {
		return resourceType;
	}

	public void setResourceType(String resourceType) {
		this.resourceType = resourceType;
	}

	public Integer getResourceId() {
		return resourceId;
	}

	public void setResourceId(Integer resourceId) {
		this.resourceId = resourceId;
	}

	public String getTextContent() {
		return textContent;
	}

	public void setTextContent(String textContent) {
		this.textContent = textContent;
	}

	public byte[] getEmbedding() {
		return embedding;
	}

	public void setEmbedding(byte[] embedding) {
		this.embedding = embedding;
	}

	public Date getDateCreated() {
		return dateCreated;
	}

	public void setDateCreated(Date dateCreated) {
		this.dateCreated = dateCreated;
	}

	/**
	 * Converts the stored byte array back to a float array for similarity computation.
	 */
	public float[] getEmbeddingVector() {
		if (embedding == null) {
			return new float[0];
		}
		ByteBuffer buffer = ByteBuffer.wrap(embedding).order(ByteOrder.LITTLE_ENDIAN);
		float[] vector = new float[embedding.length / 4];
		for (int i = 0; i < vector.length; i++) {
			vector[i] = buffer.getFloat();
		}
		return vector;
	}

	/**
	 * Stores a float array as a byte array for persistence.
	 */
	public void setEmbeddingVector(float[] vector) {
		ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float v : vector) {
			buffer.putFloat(v);
		}
		this.embedding = buffer.array();
	}
}
