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

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;

/**
 * The drug-class note's own sentence, stated after a model's answer that does not cite the note — ADR Decision 166.
 * <em>"Can I give her an NSAID?"</em> injects the note (issue #354) and publishes the class as
 * {@code unresolvedDrugClass}, but the model did not relay the note and no client draws the key, so on the demo
 * (2026-10-07) the answer read "The records do not address whether an NSAID can be given." and nothing else.
 *
 * <p>It APPENDS, as {@link ListedDrugStatement} does: no marker, since only {@code extractCitedReferences} writes a
 * reference (ADR Decision 80); no chip; never a word about whether any drug may be given. The class is the one
 * {@code ChartSearchAiUtils.unresolvedDrugClass} read off the injected chart, so it is stated exactly where the note
 * was in the prompt.
 */
final class DrugClassStatement {

	private DrugClassStatement() {
	}

	/**
	 * @return {@code answer} with {@link DrugReferenceInjector#drugClassStatement} appended, or {@code answer} unchanged
	 *         where no class was stated, where the answer cites the class note, or where it is blank
	 */
	static String withDrugClassStated(String answer, String unresolvedDrugClass, List<RecordReference> cited) {
		// A blank answer reaches the caller as it was, as OwnOrderFindingStatement leaves one.
		if (unresolvedDrugClass == null || answer == null || answer.trim().isEmpty()) {
			return answer;
		}
		if (cited != null) {
			for (RecordReference reference : cited) {
				if (ChartSearchAiConstants.RESOURCE_TYPE_DRUG_CLASS_NOTE.equals(reference.getResourceType())) {
					return answer;
				}
			}
		}
		return DrugSafetyValidator.endSentence(answer.trim()) + " "
				+ DrugReferenceInjector.drugClassStatement(unresolvedDrugClass);
	}
}
