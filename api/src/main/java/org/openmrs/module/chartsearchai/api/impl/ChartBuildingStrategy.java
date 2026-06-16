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
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Assembles the {@link PatientChart} for a patient query. Retrieval is delegated
 * to the querystore module via {@link QueryStoreChartBuilder}; the legacy
 * embedding/Lucene/Elasticsearch/hybrid pipelines were removed in the querystore
 * migration (issue #51). When {@code chartsearchai.querystore.enabled=false} the
 * builder degrades to a plain full-chart serialize (no relevance ranking) — there
 * is no longer an in-process retrieval fallback. The containing
 * {@link LlmInferenceService} delegates here for chart assembly and otherwise
 * focuses on the LLM call and citation handling.
 */
@Service("chartSearchAi.chartBuildingStrategy")
class ChartBuildingStrategy {

	@Autowired
	private PatientChartSerializer chartSerializer;

	@Autowired
	@Qualifier("chartSearchAi.queryStoreChartBuilder")
	private QueryStoreChartBuilder queryStoreChartBuilder;

	PatientChart buildChart(Patient patient, String question) {
		if (ChartSearchAiUtils.isQueryStoreEnabled()) {
			return queryStoreChartBuilder.build(patient, question);
		}

		// querystore disabled: no in-process retrieval pipeline remains, so serialize the
		// full patient chart unranked. The pre-Decision-15 ChartCache that amortized this
		// per-request serialize was removed once querystore became the supported full-chart
		// path; the AOP-driven answer-cache invalidation still covers freshness.
		return chartSerializer.serialize(patient);
	}

	boolean usePreFilter() {
		return PipelineSettings.usePreFilter();
	}
}
