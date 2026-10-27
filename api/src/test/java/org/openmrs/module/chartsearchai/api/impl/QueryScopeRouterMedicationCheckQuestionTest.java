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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The question the medication check runs as where the model reads a question as asking about her drugs (ADR Decision
 * 488) is one the router reads as a screen of her medications for interactions AND contraindications.
 */
public class QueryScopeRouterMedicationCheckQuestionTest {

	private static final String Q = QueryScopeRouter.MEDICATION_CHECK_QUESTION;

	@Test
	public void itIsAScreenOfHerMedications() {
		assertTrue(QueryScopeRouter.isInteractionScreening(Q));
		assertTrue(QueryScopeRouter.asksOnlyToScreenHerMedications(Q), "so the module answers it");
	}

	@Test
	public void itAsksAboutMoreThanInteractions() {
		assertFalse(QueryScopeRouter.asksOnlyAboutInteractions(Q), "her contraindications stay in the answer");
	}
}
