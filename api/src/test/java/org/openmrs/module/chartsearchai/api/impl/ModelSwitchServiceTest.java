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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.api.impl.ModelSwitchService.AvailableModels;
import org.openmrs.module.chartsearchai.api.impl.ModelSwitchService.ModelEntry;

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

	// --- LM Studio /api/v1/models support ---------------------------------
	// These tests pin the shape of LM Studio's v1 REST API per
	// specs/artifacts/planning/lm-studio-api-reference.md. The picker uses
	// this shape to render an "LM Studio" sub-category with per-entry
	// loaded-vs-not-loaded state, and to filter out embedding-only models
	// that would 400 from /v1/chat/completions.

	@Test
	public void parseLmStudioV1Models_extractsKeyAndLoadedFromV1Shape() {
		// Real-shape sample from GET /api/v1/models on LM Studio 0.4.x:
		//   models[i].key             = identifier used in /v1/chat/completions
		//   models[i].loaded_instances = empty array if not loaded; populated if loaded
		//   models[i].type            = "llm" | "embedding"
		String body = "{\"models\":[{"
				+ "\"key\":\"google/gemma-3-12b\","
				+ "\"type\":\"llm\","
				+ "\"publisher\":\"google\","
				+ "\"display_name\":\"Gemma 3 12B\","
				+ "\"params_string\":\"12B\","
				+ "\"max_context_length\":131072,"
				+ "\"loaded_instances\":[{\"instance_id\":\"abc\"}]"
				+ "}]}";
		List<ModelEntry> entries = ModelSwitchService.parseLmStudioV1Models(body);
		assertEquals(1, entries.size());
		ModelEntry e = entries.get(0);
		assertEquals("google/gemma-3-12b", e.getId());
		assertEquals("Gemma 3 12B", e.getDisplayName());
		assertEquals("llm", e.getType());
		assertTrue(e.isLoaded(), "loaded_instances non-empty → loaded=true");
		assertEquals(Long.valueOf(131072L), e.getMaxContextLength());
	}

	@Test
	public void parseLmStudioV1Models_emptyLoadedInstancesMeansNotLoaded() {
		String body = "{\"models\":[{"
				+ "\"key\":\"foo\",\"type\":\"llm\","
				+ "\"display_name\":\"Foo\","
				+ "\"loaded_instances\":[]"
				+ "}]}";
		assertFalse(ModelSwitchService.parseLmStudioV1Models(body).get(0).isLoaded(),
				"empty loaded_instances → loaded=false");
	}

	@Test
	public void parseLmStudioV1Models_filtersOutEmbeddingType() {
		// The picker is for chat completion. Embedding models surface in
		// /api/v1/models too; selecting one returns 400 from
		// /v1/chat/completions. Filter at parse time.
		String body = "{\"models\":["
				+ "{\"key\":\"chat-model\",\"type\":\"llm\",\"display_name\":\"Chat\",\"loaded_instances\":[]},"
				+ "{\"key\":\"emb-model\",\"type\":\"embedding\",\"display_name\":\"Emb\",\"loaded_instances\":[]}"
				+ "]}";
		List<ModelEntry> entries = ModelSwitchService.parseLmStudioV1Models(body);
		assertEquals(1, entries.size(), "embedding entry must be filtered out");
		assertEquals("chat-model", entries.get(0).getId());
	}

	@Test
	public void parseLmStudioV1Models_skipsEntriesMissingKey() {
		// Defensive: any entry without a textual key is ignored.
		String body = "{\"models\":["
				+ "{\"key\":\"good\",\"type\":\"llm\",\"loaded_instances\":[]},"
				+ "{\"type\":\"llm\",\"loaded_instances\":[]},"
				+ "{\"key\":42,\"type\":\"llm\"}"
				+ "]}";
		List<ModelEntry> entries = ModelSwitchService.parseLmStudioV1Models(body);
		assertEquals(1, entries.size());
		assertEquals("good", entries.get(0).getId());
	}

	@Test
	public void parseLmStudioV1Models_returnsEmptyOnMissingModelsArray() {
		// Some error responses or older versions may not include `models`.
		// Caller treats empty list as "no models available via v1".
		assertEquals(0, ModelSwitchService.parseLmStudioV1Models("{}").size());
		assertEquals(0, ModelSwitchService.parseLmStudioV1Models("{\"models\":null}").size());
	}

	@Test
	public void deriveLmStudioV1ModelsUrl_replacesV1ChatCompletionsWithApiV1Models() {
		// chartsearchai's standard LM Studio chat URL → corresponding v1
		// models endpoint at /api/v1/models on the same host.
		assertEquals(
				"http://host.docker.internal:1234/api/v1/models",
				ModelSwitchService.deriveLmStudioV1ModelsUrl(
						"http://host.docker.internal:1234/v1/chat/completions"));
	}

	@Test
	public void deriveLmStudioV1ModelsUrl_handlesRootHostUrl() {
		// Operator pointing at a bare host (no /v1 prefix in the endpoint URL).
		assertEquals(
				"http://localhost:1234/api/v1/models",
				ModelSwitchService.deriveLmStudioV1ModelsUrl("http://localhost:1234"));
	}

	@Test
	public void deriveLmStudioV1ModelsUrl_tolerantOfTrailingSlash() {
		assertEquals(
				"http://host:1234/api/v1/models",
				ModelSwitchService.deriveLmStudioV1ModelsUrl("http://host:1234/v1/"));
	}

	@Test
	public void looksLikeLmStudioV1Response_detectsModelsArrayKey() {
		assertTrue(ModelSwitchService.looksLikeLmStudioV1Response("{\"models\":[]}"));
		assertTrue(ModelSwitchService.looksLikeLmStudioV1Response("{\"models\":[{\"key\":\"x\"}]}"));
	}

	@Test
	public void looksLikeLmStudioV1Response_rejectsOpenAiDataShape() {
		// OpenAI-compat /v1/models uses `data` at the root, not `models`.
		assertFalse(ModelSwitchService.looksLikeLmStudioV1Response("{\"data\":[],\"object\":\"list\"}"));
		assertFalse(ModelSwitchService.looksLikeLmStudioV1Response("{}"));
		assertFalse(ModelSwitchService.looksLikeLmStudioV1Response("not json"));
	}

	@Test
	public void fetchAvailable_usesLmStudioV1WhenProbeSucceeds() {
		// Probe-and-fallback dispatch: when /api/v1/models returns a recognizable
		// v1 shape, use it and tag provider="lm-studio".
		ModelSwitchService svc = new ModelSwitchService() {
			@Override
			protected String httpGet(String url, String apiKey) {
				if (url.endsWith("/api/v1/models")) {
					return "{\"models\":[{"
							+ "\"key\":\"foo\",\"type\":\"llm\","
							+ "\"display_name\":\"Foo\","
							+ "\"loaded_instances\":[{\"instance_id\":\"i\"}]"
							+ "}]}";
				}
				throw new AssertionError("Should not call OpenAI-compat fallback: " + url);
			}
		};
		AvailableModels result = svc.fetchAvailable(
				"http://host.docker.internal:1234/v1/chat/completions");
		assertEquals("lm-studio", result.getProvider());
		assertEquals(1, result.getEntries().size());
		assertEquals("foo", result.getEntries().get(0).getId());
		assertTrue(result.getEntries().get(0).isLoaded());
	}

	@Test
	public void fetchAvailable_fallsBackToOpenAiCompatWhenV1ProbeFails() {
		// When the v1 probe returns non-recognizable content (404, generic
		// OpenAI-compat endpoint, Anthropic API), fall back to /v1/models.
		// provider remains null (or "generic-openai-compat") since we can't
		// distinguish loaded state.
		ModelSwitchService svc = new ModelSwitchService() {
			@Override
			protected String httpGet(String url, String apiKey) {
				if (url.endsWith("/api/v1/models")) {
					throw new RuntimeException("simulated 404");
				}
				// OpenAI-compat /v1/models
				return "{\"data\":[{\"id\":\"bar\"}],\"object\":\"list\"}";
			}
		};
		AvailableModels result = svc.fetchAvailable(
				"https://api.example.com/v1/chat/completions");
		assertEquals("generic-openai-compat", result.getProvider());
		assertEquals(1, result.getEntries().size());
		ModelEntry only = result.getEntries().get(0);
		assertEquals("bar", only.getId());
		assertFalse(only.isLoaded(),
				"OpenAI-compat /v1/models doesn't expose load state; default false");
	}

	@Test
	public void fetchAvailable_fallsBackOnV1NotJson() {
		// Some servers return HTML/plain-text for unknown paths. The probe
		// must not crash on non-JSON bodies.
		ModelSwitchService svc = new ModelSwitchService() {
			@Override
			protected String httpGet(String url, String apiKey) {
				if (url.endsWith("/api/v1/models")) {
					return "<html>Not Found</html>";
				}
				return "{\"data\":[{\"id\":\"only\"}]}";
			}
		};
		AvailableModels result = svc.fetchAvailable("http://host:1234/v1/chat/completions");
		assertEquals("generic-openai-compat", result.getProvider());
		assertEquals(List.of("only"),
				result.getEntries().stream().map(ModelEntry::getId).toList());
	}

	@Test
	public void modelEntry_fromOpenAiId_setsSensibleDefaults() {
		// When the only signal we have is the model ID from /v1/models,
		// ModelEntry.fromOpenAiId hydrates the rest with safe defaults.
		ModelEntry entry = ModelEntry.fromOpenAiId("some-model");
		assertEquals("some-model", entry.getId());
		assertEquals("some-model", entry.getDisplayName());
		assertEquals("llm", entry.getType());
		assertFalse(entry.isLoaded());
		assertNull(entry.getMaxContextLength());
	}

	@Test
	public void deriveLmStudioLoadUrl_appendsLoadSuffix() {
		// The /load endpoint sits directly under /api/v1/models. Pinned here
		// so requestModelLoad doesn't drift if deriveLmStudioV1ModelsUrl changes.
		assertEquals(
				"http://host:1234/api/v1/models/load",
				ModelSwitchService.deriveLmStudioV1ModelsUrl("http://host:1234/v1/chat/completions")
						+ "/load");
	}

	@Test
	public void modelListResponse_carriesProviderAndEntriesAlongsideAvailable() {
		// Back-compat: existing consumers read `available` (List<String>).
		// New consumers read `entries` (List<ModelEntry>) + `provider`.
		// Constructor MUST accept both so the REST controller can serialize
		// both views in the same JSON response.
		ModelEntry e = new ModelEntry("k1", "Display 1", "llm", true, 8192L);
		ModelSwitchService.ModelListResponse r = new ModelSwitchService.ModelListResponse(
				"remote", "k1", List.of("k1"),
				"http://lm:1234/v1/chat/completions",
				"lm-studio", List.of(e));
		assertEquals("lm-studio", r.getProvider());
		assertNotNull(r.getEntries());
		assertEquals(1, r.getEntries().size());
		assertEquals("k1", r.getEntries().get(0).getId());
		assertEquals(List.of("k1"), r.getAvailable());
	}
}
