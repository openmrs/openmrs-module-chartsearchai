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

import java.util.function.Consumer;

/**
 * Outbound med-agent-hub staged stream. Production uses HTTP SSE; tests inject a scripted
 * implementation. Exactly one call is made per {@link HubClinicalAnswerProvider#execute}.
 */
public interface HubStreamTransport {

	/**
	 * Opens one hub product-profile stream and delivers parsed wire events in order. Implementations
	 * that hold a genuinely closeable resource (e.g. an open HTTP response body) should bind it to
	 * {@code cancellation} via {@link TurnCancellation#bindCloseable} so a preempted turn's blocking
	 * read gets forcibly unblocked instead of running to the hub's own natural completion.
	 *
	 * @throws HubTransportException for non-2xx hub HTTP responses
	 */
	void stream(HubCallRequest request, Consumer<HubWireEvent> sink, CancellationSignal cancellation);
}
