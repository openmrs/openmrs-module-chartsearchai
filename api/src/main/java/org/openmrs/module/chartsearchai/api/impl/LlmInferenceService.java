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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Answers natural language questions about a patient's chart using direct LLM inference.
 * Serializes the full patient chart and sends all records to the LLM with the question.
 */
@Service("chartSearchAi.llmInferenceService")
public class LlmInferenceService implements ChartSearchService {

	private static final Logger log = LoggerFactory.getLogger(LlmInferenceService.class);

	@Autowired
	private PatientChartSerializer chartSerializer;

	@Autowired
	private LlmProvider llmProvider;

	private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)\\]");

	@Override
	public ChartAnswer ask(Patient patient, String question) {
		PatientChart chart = chartSerializer.serialize(patient);
		log.debug("Sending full chart to LLM ({} records)", chart.getReferences().size());

		String response = llmProvider.ask(chart.getText(), question);

		List<RecordReference> citedReferences = filterCitedReferences(
				response, chart.getReferences());

		return new ChartAnswer(response, citedReferences);
	}

	@Override
	public ChartAnswer askStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer) {
		PatientChart chart = chartSerializer.serialize(patient);
		log.debug("Streaming full chart to LLM ({} records)", chart.getReferences().size());

		String response = llmProvider.askStreaming(chart.getText(), question, tokenConsumer);

		List<RecordReference> citedReferences = filterCitedReferences(
				response, chart.getReferences());

		return new ChartAnswer(response, citedReferences);
	}

	static List<RecordReference> filterCitedReferences(String answer,
			List<RecordReference> allReferences) {
		Set<Integer> citedIndices = new HashSet<Integer>();
		Matcher matcher = CITATION_PATTERN.matcher(answer);
		while (matcher.find()) {
			citedIndices.add(Integer.parseInt(matcher.group(1)));
		}

		List<RecordReference> cited = new ArrayList<RecordReference>();
		for (RecordReference ref : allReferences) {
			if (citedIndices.contains(ref.getIndex())) {
				cited.add(ref);
			}
		}
		return cited;
	}
}
