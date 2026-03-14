/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.serializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Serializes an entire patient chart into labeled records for direct LLM inference.
 * Each record is prefixed with a typed label (e.g. [Obs #1], [Allergy #2]) that the
 * LLM can use for citations. The {@link RecordReference} list maps each label back to
 * a resource type and ID so the UI can link citations to the source record in OpenMRS.
 */
@Component
public class PatientChartSerializer {

	@Autowired
	private PatientRecordLoader recordLoader;

	/**
	 * Serialize all clinical records for a patient into labeled text lines.
	 *
	 * @param patient the patient whose chart to serialize
	 * @return the serialized chart with labeled records and their source references
	 */
	public PatientChart serialize(Patient patient) {
		List<SerializedRecord> records = recordLoader.loadAll(patient);
		List<RecordReference> references = new ArrayList<RecordReference>();
		StringBuilder sb = new StringBuilder();
		Map<String, Integer> typeCounters = new HashMap<String, Integer>();

		for (int i = 0; i < records.size(); i++) {
			SerializedRecord record = records.get(i);
			String type = record.getResourceType();
			String displayType = toDisplayType(type);
			int count = typeCounters.containsKey(type) ? typeCounters.get(type) + 1 : 1;
			typeCounters.put(type, count);
			String label = displayType + " #" + count;
			sb.append("[").append(label).append("] ").append(record.getText()).append("\n");
			references.add(new RecordReference(label, type, record.getResourceId()));
		}

		return new PatientChart(sb.toString(), references);
	}

	public static String toDisplayType(String resourceType) {
		if (resourceType == null || resourceType.isEmpty()) {
			return "Record";
		}
		return resourceType.substring(0, 1).toUpperCase() + resourceType.substring(1);
	}

	/**
	 * The serialized patient chart with numbered records and their source references.
	 */
	public static class PatientChart {

		private final String text;

		private final List<RecordReference> references;

		public PatientChart(String text, List<RecordReference> references) {
			this.text = text;
			this.references = references;
		}

		public String getText() {
			return text;
		}

		public List<RecordReference> getReferences() {
			return references;
		}
	}
}
