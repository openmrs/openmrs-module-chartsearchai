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

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable, truthful self-description of a clinical answer provider: identity, availability,
 * offered modes, and advertised capabilities. The UI derives everything it shows — picker
 * presence, disabled states, feature availability — from descriptors; it never infers an
 * unadvertised capability, and an unavailable configured provider stays visible with its
 * {@link #getUnavailableReason() reason} rather than silently disappearing.
 */
public final class ProviderDescriptor {

	private final String id;

	private final String label;

	private final boolean enabled;

	private final boolean ready;

	private final boolean isDefault;

	private final List<ProviderMode> modes;

	private final Set<ProviderCapability> capabilities;

	private final String unavailableReason;

	public ProviderDescriptor(String id, String label, boolean enabled, boolean ready, boolean isDefault,
			List<ProviderMode> modes, Set<ProviderCapability> capabilities, String unavailableReason) {
		this.id = id;
		this.label = label;
		this.enabled = enabled;
		this.ready = ready;
		this.isDefault = isDefault;
		this.modes = Collections.unmodifiableList(new java.util.ArrayList<>(modes));
		this.capabilities = Collections.unmodifiableSet(capabilities.isEmpty()
				? EnumSet.noneOf(ProviderCapability.class) : EnumSet.copyOf(capabilities));
		this.unavailableReason = unavailableReason;
	}

	/** Stable provider identifier persisted on conversations (e.g. {@code "bundled"}, {@code "hub"}). */
	public String getId() {
		return id;
	}

	/** Human-readable provider name for the picker. */
	public String getLabel() {
		return label;
	}

	/** Whether configuration enables this provider at all. */
	public boolean isEnabled() {
		return enabled;
	}

	/** Whether the provider can currently serve a turn (e.g. its backend is reachable). */
	public boolean isReady() {
		return ready;
	}

	/** Whether this provider is the configured default. */
	public boolean isDefault() {
		return isDefault;
	}

	public List<ProviderMode> getModes() {
		return modes;
	}

	public Set<ProviderCapability> getCapabilities() {
		return capabilities;
	}

	/** Why the provider is not usable right now; {@code null} when enabled and ready. */
	public String getUnavailableReason() {
		return unavailableReason;
	}
}
