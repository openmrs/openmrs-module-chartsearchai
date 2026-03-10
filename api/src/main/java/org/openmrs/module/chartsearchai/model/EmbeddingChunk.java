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

import java.io.ByteBuffer;
import java.io.Serializable;
import java.nio.ByteOrder;
import java.util.Date;

import org.openmrs.Patient;

/**
 * Persistent entity representing a single embedded text chunk derived from a patient's clinical
 * data. Each chunk contains the original text, its vector embedding, and metadata linking it back
 * to the source clinical record.
 */
public class EmbeddingChunk implements Serializable {

	private static final long serialVersionUID = 1L;

	private Integer chunkId;

	private Patient patient;

	private String chunkType;

	private String sourceUuid;

	private Date sourceDate;

	private String chunkText;

	private byte[] embedding;

	private Date dateIndexed;

	public Integer getChunkId() {
		return chunkId;
	}

	public void setChunkId(Integer chunkId) {
		this.chunkId = chunkId;
	}

	public Patient getPatient() {
		return patient;
	}

	public void setPatient(Patient patient) {
		this.patient = patient;
	}

	public String getChunkType() {
		return chunkType;
	}

	public void setChunkType(String chunkType) {
		this.chunkType = chunkType;
	}

	public String getSourceUuid() {
		return sourceUuid;
	}

	public void setSourceUuid(String sourceUuid) {
		this.sourceUuid = sourceUuid;
	}

	public Date getSourceDate() {
		return sourceDate;
	}

	public void setSourceDate(Date sourceDate) {
		this.sourceDate = sourceDate;
	}

	public String getChunkText() {
		return chunkText;
	}

	public void setChunkText(String chunkText) {
		this.chunkText = chunkText;
	}

	public byte[] getEmbedding() {
		return embedding;
	}

	public void setEmbedding(byte[] embedding) {
		this.embedding = embedding;
	}

	public Date getDateIndexed() {
		return dateIndexed;
	}

	public void setDateIndexed(Date dateIndexed) {
		this.dateIndexed = dateIndexed;
	}

	/**
	 * Converts the stored byte array embedding back to a float array for similarity computation.
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
	 * Stores a float array embedding as a byte array for persistence.
	 */
	public void setEmbeddingVector(float[] vector) {
		ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float v : vector) {
			buffer.putFloat(v);
		}
		this.embedding = buffer.array();
	}
}
