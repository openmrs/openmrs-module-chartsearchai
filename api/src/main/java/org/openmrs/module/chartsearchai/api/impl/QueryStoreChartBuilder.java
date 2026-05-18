/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientRecordLoader.SerializedRecord;
import org.openmrs.module.chartsearchai.util.DateFormatUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bridge to the querystore module's {@code searchByPatient} API. Resolved
 * reflectively so chartsearchai compiles and starts without the
 * querystore-api jar on the classpath; absence surfaces as an empty chart,
 * same outcome as a search returning no hits.
 */
@Component("chartSearchAi.queryStoreChartBuilder")
class QueryStoreChartBuilder {

	private static final Logger log = LoggerFactory.getLogger(QueryStoreChartBuilder.class);

	private static final String QUERY_STORE_SERVICE_CLASS = "org.openmrs.module.querystore.api.QueryStoreService";

	private static final String QUERY_DOCUMENT_CLASS = "org.openmrs.module.querystore.model.QueryDocument";

	@Autowired
	private PatientChartSerializer chartSerializer;

	PatientChart build(Patient patient, String question) {
		if (patient == null || patient.getUuid() == null
				|| question == null || question.trim().isEmpty()) {
			return emptyChart(patient);
		}

		Class<?> serviceClass;
		try {
			serviceClass = Class.forName(QUERY_STORE_SERVICE_CLASS);
		}
		catch (ClassNotFoundException e) {
			log.info("QueryStoreService class not on classpath (querystore omod not installed)");
			return emptyChart(patient);
		}

		Object queryStore;
		try {
			queryStore = Context.getService(serviceClass);
		}
		catch (APIException | LinkageError e) {
			log.info("QueryStoreService not registered with the Context (querystore omod installed?)");
			return emptyChart(patient);
		}

		String preprocessedQuestion = QueryPreprocessor.stripQueryStopwords(question);
		List<?> hits;
		try {
			Method searchByPatient = serviceClass.getMethod(
					"searchByPatient", String.class, String.class, int.class);
			hits = (List<?>) searchByPatient.invoke(queryStore,
					patient.getUuid(), preprocessedQuestion,
					PipelineSettings.getQueryStoreTopK());
		}
		catch (ReflectiveOperationException | RuntimeException e) {
			log.error("QueryStore search failed for patient [uuid={}]", patient.getUuid(), e);
			return emptyChart(patient);
		}

		if (hits == null || hits.isEmpty()) {
			return emptyChart(patient);
		}

		QueryDocumentAccess access;
		try {
			access = QueryDocumentAccess.resolve();
		}
		catch (ReflectiveOperationException e) {
			log.error("QueryDocument accessors not resolvable (querystore-api shape changed?)", e);
			return emptyChart(patient);
		}

		List<SerializedRecord> records = new ArrayList<SerializedRecord>(hits.size());
		for (Object doc : hits) {
			if (doc == null) {
				log.warn("Skipping null QueryDocument");
				continue;
			}
			SerializedRecord record = access.toSerializedRecord(doc, log);
			if (record != null) {
				records.add(record);
			}
		}
		return chartSerializer.serialize(patient, records);
	}

	private PatientChart emptyChart(Patient patient) {
		return chartSerializer.serialize(patient, Collections.<SerializedRecord>emptyList());
	}

	/**
	 * Cached reflective accessors over {@code QueryDocument} methods. Looked
	 * up once per build invocation (the resolution result isn't cached at
	 * class level since the querystore-api jar may not be on the classpath
	 * at chartsearchai start-up).
	 */
	private static final class QueryDocumentAccess {

		private final Method getResourceType;

		private final Method getResourceUuid;

		private final Method getText;

		private final Method getDate;

		private QueryDocumentAccess(Method getResourceType, Method getResourceUuid,
				Method getText, Method getDate) {
			this.getResourceType = getResourceType;
			this.getResourceUuid = getResourceUuid;
			this.getText = getText;
			this.getDate = getDate;
		}

		static QueryDocumentAccess resolve() throws ReflectiveOperationException {
			Class<?> docClass = Class.forName(QUERY_DOCUMENT_CLASS);
			return new QueryDocumentAccess(
					docClass.getMethod("getResourceType"),
					docClass.getMethod("getResourceUuid"),
					docClass.getMethod("getText"),
					docClass.getMethod("getDate"));
		}

		SerializedRecord toSerializedRecord(Object doc, Logger log) {
			try {
				String resourceType = (String) getResourceType.invoke(doc);
				String resourceUuid = (String) getResourceUuid.invoke(doc);
				if (resourceType == null || resourceUuid == null) {
					log.warn("Skipping malformed QueryDocument: type={} uuid={}",
							resourceType, resourceUuid);
					return null;
				}
				Object textObj = getText.invoke(doc);
				String text = textObj == null ? "" : (String) textObj;
				LocalDate date = (LocalDate) getDate.invoke(doc);
				return new SerializedRecord(resourceType, resourceUuid, text,
						DateFormatUtil.toLegacyDate(date));
			}
			catch (ReflectiveOperationException e) {
				log.error("Failed to read QueryDocument fields", e);
				return null;
			}
		}
	}
}
