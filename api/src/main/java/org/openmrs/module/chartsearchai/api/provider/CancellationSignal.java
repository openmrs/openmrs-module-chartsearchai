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

import java.io.Closeable;

/**
 * Cooperative cancellation for a provider turn. Providers poll this at their natural
 * checkpoints (before starting expensive work, between stages) and end the turn with a
 * {@code cancelled} {@code turn_error} when it reports {@code true}.
 */
@FunctionalInterface
public interface CancellationSignal {

	/** A signal that never cancels. */
	CancellationSignal NONE = () -> false;

	boolean isCancelled();

	/** Register blocking work that should be closed when this turn is cancelled. */
	default void bindCloseable(Closeable closeable) {
	}

	/** Remove a completed resource from cancellation tracking. */
	default void unbindCloseable(Closeable closeable) {
	}
}
