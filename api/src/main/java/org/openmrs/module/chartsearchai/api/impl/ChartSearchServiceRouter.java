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

import java.util.function.Consumer;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Routes chart search queries to either the direct LLM inference service or the
 * embedding-based search service based on the {@code chartsearchai.searchMode} global
 * property. Defaults to {@code llm} if the property is not set.
 */
@Service("chartSearchAi.chartSearchServiceRouter")
public class ChartSearchServiceRouter implements ChartSearchService {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchServiceRouter.class);

	@Autowired
	@Qualifier("chartSearchAi.llmInferenceService")
	private ChartSearchService llmService;

	@Autowired
	@Qualifier("chartSearchAi.embeddingSearchService")
	private ChartSearchService embeddingService;

	@Override
	public ChartAnswer ask(Patient patient, String question) {
		String mode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SEARCH_MODE);

		if (ChartSearchAiConstants.SEARCH_MODE_EMBEDDING.equalsIgnoreCase(mode)) {
			log.debug("Using embedding search mode");
			return embeddingService.ask(patient, question);
		}

		log.debug("Using LLM inference search mode");
		return llmService.ask(patient, question);
	}

	@Override
	public ChartAnswer askStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer) {
		String mode = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_SEARCH_MODE);

		if (ChartSearchAiConstants.SEARCH_MODE_EMBEDDING.equalsIgnoreCase(mode)) {
			log.debug("Using embedding search mode (streaming)");
			return embeddingService.askStreaming(patient, question, tokenConsumer);
		}

		log.debug("Using LLM inference search mode (streaming)");
		return llmService.askStreaming(patient, question, tokenConsumer);
	}
}
