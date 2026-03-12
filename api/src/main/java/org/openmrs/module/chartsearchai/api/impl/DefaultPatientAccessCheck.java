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

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.module.chartsearchai.api.PatientAccessCheck;
import org.springframework.stereotype.Component;

/**
 * Default implementation that permits access to all patients. Access control is
 * limited to the global "AI Query Patient Data" privilege check in the REST controller.
 *
 * <p>Deployments requiring patient-level restrictions should override this bean
 * with a custom implementation. See {@link PatientAccessCheck} for details.</p>
 */
@Component("chartSearchAi.patientAccessCheck")
public class DefaultPatientAccessCheck implements PatientAccessCheck {

	@Override
	public boolean canAccess(User user, Patient patient) {
		return true;
	}
}
