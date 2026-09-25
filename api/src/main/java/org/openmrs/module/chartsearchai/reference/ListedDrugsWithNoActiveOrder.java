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

import java.util.Collections;
import java.util.List;

/**
 * The drugs a question lists as the patient's before it proposes one, that her chart holds no active order
 * for — issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/515">#515</a>, ADR
 * Decision 119. Decided by {@code DrugSafetyValidator.listedWithNoActiveOrder} on the pre-answer pass,
 * which holds her orders resolved and the pass's one naming of each substance, and stamped by
 * {@link DrugReferenceInjector} on the chart it builds ({@code PatientChart.getListedDrugsWithNoActiveOrder}).
 */
final class ListedDrugsWithNoActiveOrder {

	private ListedDrugsWithNoActiveOrder() {
	}

	/**
	 * The caller's one-slot accumulator for that statement, handed to the pass that fills it, as
	 * {@link PairChipExtent.Sink} is. A per-call object and never a field: the validator is a Spring
	 * singleton (issue #172).
	 */
	static final class Sink {

		private List<String> stated = Collections.emptyList();

		void state(List<String> names) {
			this.stated = names == null ? Collections.<String> emptyList() : Collections.unmodifiableList(names);
		}

		/** @return the names stated, in the order the question lists them; empty where none was */
		List<String> stated() {
			return stated;
		}
	}
}
