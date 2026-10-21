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

/**
 * Receives a provider turn's events in emission order. Implementations relay them to the wire
 * (e.g. SSE) or record them; providers call {@link #accept} synchronously and must emit a
 * sequence {@link TurnLifecycleValidator} accepts for their advertised capabilities.
 */
public interface TurnEventSink {

	void accept(TurnEvent event);
}
