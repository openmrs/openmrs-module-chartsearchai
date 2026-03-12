/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.embedding;

import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Routes embedding requests to either the ONNX model or the term-frequency hashing
 * provider based on the {@code chartsearchai.embedding.provider} global property.
 * Defaults to {@code term-frequency} if the property is not set.
 */
@Component("chartSearchAi.embeddingProvider")
public class EmbeddingProviderRouter implements EmbeddingProvider {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingProviderRouter.class);

	@Autowired
	@Qualifier("chartSearchAi.onnxEmbeddingProvider")
	private EmbeddingProvider onnxProvider;

	@Autowired
	@Qualifier("chartSearchAi.termFrequencyEmbeddingProvider")
	private EmbeddingProvider termFrequencyProvider;

	@Override
	public float[] embed(String text) {
		return getProvider().embed(text);
	}

	@Override
	public int getDimensions() {
		return getProvider().getDimensions();
	}

	private EmbeddingProvider getProvider() {
		String provider = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_EMBEDDING_PROVIDER);

		if (ChartSearchAiConstants.EMBEDDING_PROVIDER_ONNX.equalsIgnoreCase(provider)) {
			log.debug("Using ONNX embedding provider");
			return onnxProvider;
		}

		log.debug("Using term-frequency embedding provider");
		return termFrequencyProvider;
	}
}
