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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.api.context.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Resolves which {@link ClinicalAnswerProvider}s OpenMRS configuration enables and which one is
 * the default. The configuration rules the dual-provider roadmap fixes:
 *
 * <ul>
 *   <li>A fresh install enables only the bundled provider, which is the default.</li>
 *   <li>The provider picker exists only when more than one provider is enabled.</li>
 *   <li>An enabled provider that is unavailable (no implementation deployed, or not ready)
 *       stays visible in descriptors — disabled with a reason — rather than disappearing.</li>
 *   <li>{@link #require} fails with one normalized problem code; it never substitutes another
 *       provider.</li>
 *   <li>Switching provider starts a new conversation; existing conversations keep the provider
 *       that produced them.</li>
 * </ul>
 */
@Service("chartSearchAi.clinicalAnswerProviderRegistry")
public class ClinicalAnswerProviderRegistry {

	/** Comma-separated ordered provider ids configuration enables. Fresh-install default: bundled only. */
	public static final String GP_PROVIDERS_ENABLED = "chartsearchai.providers.enabled";

	/** The provider preselected for new conversations. Must be an enabled provider. */
	public static final String GP_DEFAULT_PROVIDER = "chartsearchai.providers.default";

	public static final String FRESH_INSTALL_DEFAULT = BundledClinicalAnswerProvider.PROVIDER_ID;

	private final Map<String, ClinicalAnswerProvider> providersById = new LinkedHashMap<>();

	@Autowired
	public ClinicalAnswerProviderRegistry(List<ClinicalAnswerProvider> providers) {
		for (ClinicalAnswerProvider provider : providers) {
			// Use id(), not descriptor(): descriptor() may read global properties, and Spring
			// constructs this registry before the OpenMRS Context is available.
			providersById.put(provider.id(), provider);
		}
	}

	/** Global-property read seam, overridable in tests (same pattern as the caching router). */
	protected String gp(String property, String defaultValue) {
		return Context.getAdministrationService().getGlobalProperty(property, defaultValue);
	}

	/** The configured enabled provider ids, in configuration order. */
	public List<String> enabledProviderIds() {
		List<String> ids = new ArrayList<>();
		for (String id : gp(GP_PROVIDERS_ENABLED, FRESH_INSTALL_DEFAULT).split(",")) {
			String trimmed = id.trim();
			if (!trimmed.isEmpty() && !ids.contains(trimmed)) {
				ids.add(trimmed);
			}
		}
		return ids;
	}

	/**
	 * One descriptor per enabled provider, in configuration order, with {@code isDefault}
	 * recomputed from configuration. An enabled provider with no deployed implementation is
	 * represented as visible-but-unavailable instead of being dropped.
	 */
	public List<ProviderDescriptor> descriptors() {
		String defaultId = getDefaultProviderId();
		List<ProviderDescriptor> descriptors = new ArrayList<>();
		for (String id : enabledProviderIds()) {
			ClinicalAnswerProvider provider = providersById.get(id);
			if (provider == null) {
				descriptors.add(new ProviderDescriptor(id, id, true, false, id.equals(defaultId),
						java.util.Collections.emptyList(),
						java.util.Collections.emptySet(),
						"no implementation for provider '" + id + "' is deployed"));
				continue;
			}
			ProviderDescriptor descriptor = provider.descriptor();
			descriptors.add(new ProviderDescriptor(descriptor.getId(), descriptor.getLabel(), true,
					descriptor.isReady(), id.equals(defaultId), descriptor.getModes(),
					descriptor.getCapabilities(), descriptor.getUnavailableReason()));
		}
		return descriptors;
	}

	/**
	 * The provider preselected for new conversations. A configured default that is not enabled
	 * falls back to the fresh-install default when it is enabled, otherwise to the first enabled
	 * provider. Discovery never advertises a disabled provider as the default.
	 */
	public String getDefaultProviderId() {
		List<String> enabled = enabledProviderIds();
		String configured = gp(GP_DEFAULT_PROVIDER, FRESH_INSTALL_DEFAULT);
		if (enabled.contains(configured)) {
			return configured;
		}
		if (enabled.contains(FRESH_INSTALL_DEFAULT)) {
			return FRESH_INSTALL_DEFAULT;
		}
		return enabled.isEmpty() ? FRESH_INSTALL_DEFAULT : enabled.get(0);
	}

	/** The picker only exists when there is an actual choice. */
	public boolean isPickerVisible() {
		return enabledProviderIds().size() > 1;
	}

	/**
	 * Resolves the provider that must serve a turn, or fails with one normalized problem code
	 * ({@code unknown_provider}, {@code provider_not_enabled}, {@code provider_not_ready}).
	 * Never substitutes a different provider.
	 */
	public ClinicalAnswerProvider require(String providerId) {
		ClinicalAnswerProvider provider = providersById.get(providerId);
		if (provider == null) {
			throw new ProviderUnavailableException("unknown_provider",
					"No provider with id '" + providerId + "' is deployed");
		}
		if (!enabledProviderIds().contains(providerId)) {
			throw new ProviderUnavailableException("provider_not_enabled",
					"Provider '" + providerId + "' is not enabled by configuration");
		}
		if (!provider.descriptor().isReady()) {
			throw new ProviderUnavailableException("provider_not_ready",
					"Provider '" + providerId + "' is not ready: "
							+ provider.descriptor().getUnavailableReason());
		}
		return provider;
	}

	/** A provider change always starts a new conversation; the old one stays in history. */
	public static boolean switchRequiresNewConversation(String fromProviderId, String toProviderId) {
		return !fromProviderId.equals(toProviderId);
	}
}
