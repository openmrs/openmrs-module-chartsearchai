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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * POJO for deserializing eval dataset JSON files. Each dataset contains a
 * description, optional patient records, and a list of eval cases.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalDataset {

	private String dataset;

	private String description;

	private List<EvalRecord> records;

	private List<EvalCase> cases;

	public String getDataset() {
		return dataset;
	}

	public void setDataset(String dataset) {
		this.dataset = dataset;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public List<EvalRecord> getRecords() {
		return records;
	}

	public void setRecords(List<EvalRecord> records) {
		this.records = records;
	}

	public List<EvalCase> getCases() {
		return cases;
	}

	public void setCases(List<EvalCase> cases) {
		this.cases = cases;
	}

	/**
	 * Loads an eval dataset from the classpath.
	 */
	public static EvalDataset load(String resourcePath) throws IOException {
		try (InputStream is = EvalDataset.class.getClassLoader().getResourceAsStream(resourcePath)) {
			if (is == null) {
				throw new IOException("Eval dataset not found: " + resourcePath);
			}
			return new ObjectMapper().readValue(is, EvalDataset.class);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class EvalRecord {

		private int index;

		private String resourceType;

		private String resourceUuid;

		private String text;

		public int getIndex() {
			return index;
		}

		public void setIndex(int index) {
			this.index = index;
		}

		public String getResourceType() {
			return resourceType;
		}

		public void setResourceType(String resourceType) {
			this.resourceType = resourceType;
		}

		public String getResourceUuid() {
			return resourceUuid;
		}

		public void setResourceUuid(String resourceUuid) {
			this.resourceUuid = resourceUuid;
		}

		public String getText() {
			return text;
		}

		public void setText(String text) {
			this.text = text;
		}
	}
}
