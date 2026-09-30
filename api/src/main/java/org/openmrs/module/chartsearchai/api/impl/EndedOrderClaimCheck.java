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

import java.util.List;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the MODEL's answer says a drug's order is no longer in force where no chart record the prompt carried
 * marks an order of that drug as not in force — ADR Decision 135, published as {@code unsupportedEndedOrderClaims}.
 * {@link DrugSafetyValidator#endedOrderClaimsNoRecordStates} is the reading, over {@link EndedOrderStatement}'s own
 * words, so the sentence the module appends and the claim this reports are one phrase.
 *
 * <p>Asked of the model's answer before the module appends anything, so the module's own ended-order sentence is
 * never read as a claim. It reports and changes nothing. Its residue is the phrase: an answer saying the order
 * "was stopped" or "has ended" states the same claim in words this does not read.
 */
final class EndedOrderClaimCheck {

	private static final Logger log = LoggerFactory.getLogger(EndedOrderClaimCheck.class);

	private EndedOrderClaimCheck() {
	}

	/**
	 * @return the drugs claimed, in the order the answer first claims them; empty where it claims none the records
	 *         do not state; {@code null} — no measurement — where no validator or dataset is wired or the check throws
	 */
	static List<String> report(Patient patient, String answer, List<RecordMapping> mappings,
			DrugSafetyValidator validator) {
		if (validator == null) {
			return null;
		}
		try {
			List<String> claimed = validator.endedOrderClaimsNoRecordStates(answer,
					EndedOrderStatement.NO_LONGER_IN_FORCE, mappings);
			if (claimed != null && !claimed.isEmpty()) {
				// Counts and never the drugs' names: a diagnostic line names none of her medications (#439).
				log.warn("Answer says an order is no longer in force where no record does (ADR Decision 135) "
						+ "patient={} drugs={}", patient == null ? null : patient.getPatientId(), claimed.size());
			}
			return claimed;
		}
		catch (RuntimeException e) {
			log.warn("Ended-order claim check failed; stating no measurement", e);
			return null;
		}
	}
}
