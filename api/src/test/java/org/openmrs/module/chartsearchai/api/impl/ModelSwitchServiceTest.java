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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

public class ModelSwitchServiceTest {

	@Test
	public void deriveModelsUrl_stripsChatCompletionsSuffix() {
		assertEquals("http://host:1234/v1/models",
				ModelSwitchService.deriveModelsUrl("http://host:1234/v1/chat/completions"));
	}

	@Test
	public void deriveModelsUrl_tolerantOfTrailingSlash() {
		assertEquals("http://host:1234/v1/models",
				ModelSwitchService.deriveModelsUrl("http://host:1234/v1/"));
	}

	@Test
	public void deriveModelsUrl_appendsModelsWhenNotChatCompletions() {
		// Anthropic / OpenAI / custom paths that don't end in /chat/completions
		// just get /models appended — operator's responsibility to verify the
		// endpoint supports OpenAI's /v1/models shape.
		assertEquals("https://api.anthropic.com/v1/models",
				ModelSwitchService.deriveModelsUrl("https://api.anthropic.com/v1"));
	}

	@Test
	public void parseModelIds_extractsIdsFromOpenAiCompatList() {
		// Mirrors what LM Studio returns from GET /v1/models — id is what we
		// pass back as the model name to /v1/chat/completions later.
		String body = "{\"data\":["
				+ "{\"id\":\"gemma-4-e2b-it\",\"object\":\"model\"},"
				+ "{\"id\":\"google/gemma-4-31b\",\"object\":\"model\"},"
				+ "{\"id\":\"text-embedding-nomic-embed-text-v1.5\",\"object\":\"model\"}"
				+ "],\"object\":\"list\"}";

		List<String> ids = ModelSwitchService.parseModelIds(body);

		assertEquals(3, ids.size());
		assertTrue(ids.contains("gemma-4-e2b-it"));
		assertTrue(ids.contains("google/gemma-4-31b"));
		assertTrue(ids.contains("text-embedding-nomic-embed-text-v1.5"));
	}

	@Test
	public void parseModelIds_returnsEmptyListWhenDataMissing() {
		// Some endpoints (or error responses) return an object without `data`.
		// The parser must not throw — caller treats empty list as "no models".
		assertEquals(0, ModelSwitchService.parseModelIds("{\"object\":\"list\"}").size());
	}

	@Test
	public void parseModelIds_skipsEntriesMissingId() {
		// Defensive: any object without a textual id is ignored, not exploded.
		String body = "{\"data\":["
				+ "{\"id\":\"good\"},"
				+ "{\"object\":\"model\"},"
				+ "{\"id\":42}"
				+ "]}";
		assertEquals(List.of("good"), ModelSwitchService.parseModelIds(body));
	}
}
