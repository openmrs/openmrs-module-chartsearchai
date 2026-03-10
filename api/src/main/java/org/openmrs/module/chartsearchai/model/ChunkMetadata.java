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

import java.util.Date;

/**
 * Metadata about the clinical source of an embedding chunk.
 */
public class ChunkMetadata {

	private String chunkType;

	private String sourceUuid;

	private Date sourceDate;

	private String patientUuid;

	public ChunkMetadata() {
	}

	public ChunkMetadata(String chunkType, String sourceUuid, Date sourceDate, String patientUuid) {
		this.chunkType = chunkType;
		this.sourceUuid = sourceUuid;
		this.sourceDate = sourceDate;
		this.patientUuid = patientUuid;
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

	public String getPatientUuid() {
		return patientUuid;
	}

	public void setPatientUuid(String patientUuid) {
		this.patientUuid = patientUuid;
	}
}
