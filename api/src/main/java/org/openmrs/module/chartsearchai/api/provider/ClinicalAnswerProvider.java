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

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * The provider-neutral boundary between OpenMRS and a clinical answering implementation. The
 * bundled ChartSearchAI pipeline and the med-agent-hub relay both implement this one contract, so
 * the common layer (authorization, sessions, persistence, audit, REST/SSE) never branches on the
 * provider.
 *
 * <p>A provider never silently falls back to another provider: it either completes the turn or
 * ends it with one normalized problem code. Emitted events must satisfy
 * {@link TurnLifecycleValidator} for the provider's advertised capabilities.</p>
 */
public interface ClinicalAnswerProvider {

	/**
	 * Stable provider identity used for registry keys and persistence. Must not read OpenMRS
	 * configuration — Spring constructs the registry before the OpenMRS Context is available.
	 */
	String id();

	/** The provider's truthful self-description: identity, readiness, modes, and capabilities. */
	ProviderDescriptor descriptor();

	default Set<ProviderCapability> capabilities() {
		return descriptor().getCapabilities();
	}

	default List<ProviderMode> modes() {
		return descriptor().getModes();
	}

	/**
	 * Executes one turn, delivering lifecycle events to {@code events} as they occur and
	 * completing with the turn's final outcome. Implementations honor {@code cancellation}
	 * at their natural checkpoints. The returned stage completes normally even for failed
	 * turns (with a {@code turn_error} result); it completes exceptionally only for
	 * infrastructure-level faults outside the turn contract.
	 */
	CompletionStage<TurnResult> execute(TurnRequest request, TurnEventSink events,
			CancellationSignal cancellation);
}
