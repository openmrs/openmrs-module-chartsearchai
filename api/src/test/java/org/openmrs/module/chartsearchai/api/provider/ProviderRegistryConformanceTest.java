/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Conformance adapter for the {@code provider_capabilities} family of
 * {@code conformance/dual-provider-conformance.v1.json} plus the configuration rules the roadmap
 * fixes: bundled is the fresh-install default, the picker appears only with more than one
 * configured provider, an unavailable configured provider stays visible but disabled, and there
 * is never a silent fallback to a different provider.
 */
public class ProviderRegistryConformanceTest {

	private static final String FIXTURE = "/conformance/dual-provider-conformance.v1.json";

	/** Provider whose identity and readiness are scripted for registry tests. */
	private static class FakeProvider implements ClinicalAnswerProvider {

		private final String id;

		private final boolean ready;

		FakeProvider(String id, boolean ready) {
			this.id = id;
			this.ready = ready;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public ProviderDescriptor descriptor() {
			return new ProviderDescriptor(id, id + " provider", true, ready, false,
					Collections.singletonList(ProviderMode.QUERY_SCOPED),
					EnumSet.of(ProviderCapability.ANSWER), ready ? null : "backend unreachable");
		}

		@Override
		public CompletionStage<TurnResult> execute(TurnRequest request, TurnEventSink events,
				CancellationSignal cancellation) {
			throw new UnsupportedOperationException("registry tests never execute turns");
		}
	}

	/** Registry whose global-property reads come from an in-memory map. */
	private static class StubRegistry extends ClinicalAnswerProviderRegistry {

		final Map<String, String> gps = new HashMap<>();

		StubRegistry(List<ClinicalAnswerProvider> providers) {
			super(providers);
		}

		@Override
		protected String gp(String property, String defaultValue) {
			return gps.containsKey(property) ? gps.get(property) : defaultValue;
		}
	}

	private static StubRegistry registry(String enabledGp, ClinicalAnswerProvider... providers) {
		StubRegistry registry = new StubRegistry(Arrays.asList(providers));
		if (enabledGp != null) {
			registry.gps.put(ClinicalAnswerProviderRegistry.GP_PROVIDERS_ENABLED, enabledGp);
		}
		return registry;
	}

	@Test
	public void everyProviderCapabilitiesFixtureCaseHolds() throws Exception {
		JsonNode cases;
		try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
			cases = new ObjectMapper().readTree(in).get("provider_capabilities");
		}
		assertTrue(cases != null && cases.isArray() && cases.size() > 0);

		for (JsonNode fixtureCase : cases) {
			String id = fixtureCase.get("id").asText();
			if (fixtureCase.has("expected_picker")) {
				boolean hubReady = !fixtureCase.has("hub_ready") || fixtureCase.get("hub_ready").asBoolean();
				StringBuilder enabled = new StringBuilder();
				for (JsonNode provider : fixtureCase.get("providers")) {
					if (enabled.length() > 0) {
						enabled.append(',');
					}
					enabled.append(provider.asText());
				}
				StubRegistry registry = registry(enabled.toString(),
						new FakeProvider("bundled", true), new FakeProvider("hub", hubReady));

				assertEquals(fixtureCase.get("expected_picker").asBoolean(), registry.isPickerVisible(), id);
				assertEquals(fixtureCase.get("default_provider").asText(),
						registry.getDefaultProviderId(), id);
				if (fixtureCase.has("expected_hub_state")) {
					ProviderDescriptor hub = registry.descriptors().stream()
							.filter(d -> "hub".equals(d.getId())).findFirst().orElse(null);
					assertNotNull(hub, id + ": configured hub must stay visible");
					assertEquals("disabled", fixtureCase.get("expected_hub_state").asText(), id);
					assertTrue(hub.isEnabled(), id + ": configured hub remains enabled in config");
					assertFalse(hub.isReady(), id + ": unready hub must not claim readiness");
					assertNotNull(hub.getUnavailableReason(), id);
				}
			} else if (fixtureCase.has("switch_to")) {
				assertEquals("new_conversation", fixtureCase.get("expected").asText());
				assertTrue(ClinicalAnswerProviderRegistry.switchRequiresNewConversation(
						fixtureCase.get("switch_from").asText(), fixtureCase.get("switch_to").asText()), id);
			}
		}
	}

	@Test
	public void bundledIsTheFreshInstallDefaultWithNoConfiguration() {
		StubRegistry registry = registry(null, new FakeProvider("bundled", true),
				new FakeProvider("hub", true));

		assertEquals(Collections.singletonList("bundled"),
				registry.descriptors().stream().map(ProviderDescriptor::getId)
						.collect(java.util.stream.Collectors.toList()),
				"an unconfigured install exposes only bundled");
		assertEquals("bundled", registry.getDefaultProviderId());
		assertFalse(registry.isPickerVisible());
	}

	@Test
	public void theDefaultDescriptorIsMarkedDefaultAndOthersAreNot() {
		StubRegistry registry = registry("bundled,hub", new FakeProvider("bundled", true),
				new FakeProvider("hub", true));

		for (ProviderDescriptor descriptor : registry.descriptors()) {
			assertEquals("bundled".equals(descriptor.getId()), descriptor.isDefault());
		}
		assertTrue(registry.isPickerVisible());
	}

	@Test
	public void aConfiguredProviderWithNoImplementationStaysVisibleButUnavailable() {
		StubRegistry registry = registry("bundled,hub", new FakeProvider("bundled", true));

		ProviderDescriptor hub = registry.descriptors().stream().filter(d -> "hub".equals(d.getId()))
				.findFirst().orElse(null);
		assertNotNull(hub, "a configured provider must not silently disappear");
		assertFalse(hub.isReady());
		assertNotNull(hub.getUnavailableReason());
	}

	@Test
	public void requireRejectsUnknownDisabledAndUnreadyProvidersInsteadOfFallingBack() {
		StubRegistry registry = registry("bundled,hub", new FakeProvider("bundled", true),
				new FakeProvider("hub", false));

		assertEquals("bundled", registry.require("bundled").descriptor().getId());
		ProviderUnavailableException unknown = assertThrows(ProviderUnavailableException.class,
				() -> registry.require("nonsense"));
		assertEquals("unknown_provider", unknown.getProblemCode());
		ProviderUnavailableException unready = assertThrows(ProviderUnavailableException.class,
				() -> registry.require("hub"));
		assertEquals("provider_not_ready", unready.getProblemCode());

		StubRegistry bundledOnly = registry("bundled", new FakeProvider("bundled", true),
				new FakeProvider("hub", true));
		ProviderUnavailableException disabled = assertThrows(ProviderUnavailableException.class,
				() -> bundledOnly.require("hub"));
		assertEquals("provider_not_enabled", disabled.getProblemCode());
	}

	@Test
	public void theConfiguredDefaultMustBeAnEnabledProvider() {
		StubRegistry registry = registry("bundled,hub", new FakeProvider("bundled", true),
				new FakeProvider("hub", true));
		registry.gps.put(ClinicalAnswerProviderRegistry.GP_DEFAULT_PROVIDER, "hub");
		assertEquals("hub", registry.getDefaultProviderId());

		// A default pointing at a provider that is not enabled falls back to bundled — the
		// fresh-install default — rather than exposing a dead default.
		registry.gps.put(ClinicalAnswerProviderRegistry.GP_DEFAULT_PROVIDER, "nonsense");
		assertEquals("bundled", registry.getDefaultProviderId());
	}
}
