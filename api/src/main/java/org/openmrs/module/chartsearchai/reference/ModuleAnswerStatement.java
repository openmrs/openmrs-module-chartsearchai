/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which chips an answer the MODULE composed from its own findings already states (ADR Decisions 108
 * and 131). That answer is written line by line from {@code DrugReferenceInjector.findingBody}, which
 * opens with the finding's own {@link SafetyWarning#getDetail()} verbatim, so a chip whose detail the
 * composed text carries is a finding the clinician has just read, and it is
 * {@link SafetyWarning#asStatedInTheAnswer() marked} so a client does not draw it again in full
 * beneath the answer. The chip is still published, as {@link ConflictingOrderStatement}'s are.
 *
 * <p>Asked of text the module itself wrote, never of a model's prose: a model may paraphrase, and a
 * chip it paraphrased is not stated in its words. A chip whose detail the composed text does not carry
 * — one the chips pass raised that the composer did not write — stays unmarked.
 */
public final class ModuleAnswerStatement {

	private ModuleAnswerStatement() {
	}

	/**
	 * @return {@code warnings}, each one whose detail {@code composed} carries marked as stated and the
	 *         rest as handed; {@code warnings} itself where there is no composed text to state anything
	 */
	public static List<SafetyWarning> markStated(String composed, List<SafetyWarning> warnings) {
		if (composed == null || composed.isEmpty() || warnings == null || warnings.isEmpty()) {
			return warnings;
		}
		List<SafetyWarning> marked = new ArrayList<SafetyWarning>(warnings.size());
		for (SafetyWarning chip : warnings) {
			String detail = chip.getDetail() == null ? "" : chip.getDetail().trim();
			marked.add(!detail.isEmpty() && composed.contains(detail) ? chip.asStatedInTheAnswer() : chip);
		}
		return Collections.unmodifiableList(marked);
	}
}
