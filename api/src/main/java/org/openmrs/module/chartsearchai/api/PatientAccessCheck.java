/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api;

import org.openmrs.Patient;
import org.openmrs.User;

/**
 * Strategy interface for checking whether a user is allowed to query a specific
 * patient's chart via the AI search. The default implementation permits all access
 * (relying on the global "AI Query Patient Data" privilege). Deployments that need
 * patient-level restrictions (e.g. location-based, care-team-based, or program-based)
 * should provide a custom bean named {@code chartSearchAi.patientAccessCheck} that
 * overrides the default.
 *
 * <p>Example Spring override in a downstream module's {@code moduleApplicationContext.xml}:</p>
 * <pre>
 * &lt;bean id="chartSearchAi.patientAccessCheck"
 *       class="com.example.LocationBasedPatientAccessCheck"/&gt;
 * </pre>
 */
public interface PatientAccessCheck {

	/**
	 * Checks whether the given user is allowed to query the given patient's chart.
	 *
	 * @param user the authenticated user making the query
	 * @param patient the patient whose chart would be queried
	 * @return {@code true} if access is allowed, {@code false} otherwise
	 */
	boolean canAccess(User user, Patient patient);
}
