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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;

/**
 * The module's own statement of which of her orders are in the drug class a question names, where the class's WHO ATC
 * group IS that class — ADR Decision 275. <em>"Is it safe to give her a statin?"</em> was told the class was not resolved
 * to any substance, of a patient already on simvastatin. Which orders is
 * {@code PatientChart.getOrdersInTheNamedClass()}'s, stamped by the pre-answer pass; this class decides nothing about them.
 * Like {@link ListedDrugStatement} it APPENDS: no marker, no chip, no prompt change, and never a word about whether the
 * class may be given. It is not the class note, whose citable words name no member (ADR Decision 67).
 */
public final class DrugClassMembersStatement {

	/** What separates the name of a class WHO files under several ATC groups from those groups, in its group key (ADR
	 *  Decision 379). */
	private static final String NAMED_GROUPS = ": ";

	private DrugClassMembersStatement() {
	}

	/**
	 * The ATC prefixes a class's group key covers: the one group a class IS — {@code C10AA} — or, for a class WHO files under
	 * several, each group its key lists after the class's name — {@code corticosteroid: H02, D07A, …} (ADR Decision 379).
	 * The one reading of that key, so no caller splits it its own way.
	 */
	public static List<String> atcPrefixes(String group) {
		int named = group.indexOf(NAMED_GROUPS);
		return Arrays.asList((named < 0 ? group : group.substring(named + NAMED_GROUPS.length())).split(", "));
	}

	/** Whether any of {@code codes} is filed under the group {@code group} keys ({@link #atcPrefixes}). */
	public static boolean filedUnder(Collection<String> codes, String group) {
		for (String code : codes) {
			for (String prefix : atcPrefixes(group)) {
				if (code.startsWith(prefix)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Whether the group key names a class WHO files under several groups rather than one group (ADR Decision 379). */
	public static boolean namesSeveralGroups(String group) {
		return group.indexOf(NAMED_GROUPS) >= 0;
	}

	/** How a sentence names the group {@code group} keys: <em>"ATC group C10AA"</em>, or for several, <em>"WHO ATC
	 *  corticosteroid groups"</em> — the class WHO files under them, never every code (ADR Decision 379). */
	public static String groupPhrase(String group) {
		int named = group.indexOf(NAMED_GROUPS);
		return named < 0 ? "ATC group " + group : "WHO ATC " + group.substring(0, named) + " groups";
	}

	/**
	 * {@code answer} with one sentence naming {@code orders} under {@code group} appended — or, for an empty list, that none
	 * of her medications is there — else {@code answer} unchanged where there is no statement ({@code null}).
	 */
	static String withMembersStated(String answer, String group, List<String> orders) {
		if (answer == null || group == null || orders == null) {
			return answer;
		}
		return (DrugSafetyValidator.endSentence(answer.trim()) + " " + statement(group, orders)).trim();
	}

	/** The sentence naming {@code orders} under the ATC {@code group}, or that none of her orders is there — also the
	 *  module's own answer to a class proposed (ADR Decision 347). */
	public static String statement(String group, List<String> orders) {
		if (orders.isEmpty()) {
			return "None of this patient's active medication orders is in that class (" + groupPhrase(group) + ").";
		}
		StringBuilder sb = new StringBuilder("Already among this patient's active medication orders in that class (")
				.append(groupPhrase(group)).append("): ");
		for (int i = 0; i < orders.size(); i++) {
			if (i > 0) {
				sb.append(i == orders.size() - 1 ? " and " : ", ");
			}
			sb.append(orders.get(i));
		}
		return sb.append('.').toString();
	}
}
