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

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Assembles the {@link PatientChart} for a patient query by delegating to the querystore
 * module via {@link QueryStoreChartBuilder}. querystore is a required module and the only
 * retrieval path — the legacy embedding/Lucene/Elasticsearch pipelines and the in-process
 * full-chart fallback were removed in the querystore migration (issue #51). If querystore is
 * unavailable at runtime, {@link QueryStoreChartBuilder} degrades to an empty chart rather
 * than failing chart assembly. The containing {@link LlmInferenceService} delegates here for
 * chart assembly and otherwise focuses on the LLM call and citation handling.
 */
@Service("chartSearchAi.chartBuildingStrategy")
class ChartBuildingStrategy {

	@Autowired
	@Qualifier("chartSearchAi.queryStoreChartBuilder")
	private QueryStoreChartBuilder queryStoreChartBuilder;

	PatientChart buildChart(Patient patient, String question) {
		return queryStoreChartBuilder.build(patient, question);
	}

	boolean usePreFilter() {
		return PipelineSettings.usePreFilter();
	}
}
