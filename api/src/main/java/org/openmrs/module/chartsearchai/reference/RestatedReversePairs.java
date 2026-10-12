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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One pair of her own orders, raised from each side, shown once — issue #513 item 4, ADR Decision 184.
 *
 * <p><b>The defect.</b> A question naming two drugs she already takes puts both in play, and the drug-in-play arm
 * screens each against her orders, so the one pair between them is raised twice: <em>"Is it safe to continue her
 * nevirapine and rifampicin together?"</em> showed <em>"Nevirapine interacts with active order Rifampicin (rifampin) —
 * Major."</em> and <em>"Rifampicin (rifampin) interacts with active order Nevirapine — Major."</em> over one mechanism
 * note.
 *
 * <p><b>Why not at the arm.</b> Raising one side only was measured to break what is asked of EACH side: the
 * scheduled-start date (#553) and the current-medication referent (#515) are stated per subject, and checks of the
 * answer ask which findings are about a drug. So both findings stay in the checks and the prompt, and this folds only
 * what a clinician is SHOWN — the published chip list ({@code ChartSearchAiRestController}, the one place it is
 * written).
 *
 * <p><b>What is folded, and nothing else.</b> Two INTERACTION chips, both about her own current medications
 * ({@link SafetyWarning#isAboutACurrentMedication()}), each naming one partner, whose subject and partner are the
 * other's partner and subject by SUBSTANCE ({@link SafetyWarning#subjectRows()}, {@link SafetyWarning#rowsOfPartner}),
 * of one severity, and whose mechanism notes are one note — equal, or one beginning the other (the knowledge base can
 * file a fuller note on one side: methotrexate and salicylic acid). The chip kept is the one carrying the fuller note,
 * in the place of the first. A chip without substance rows is never folded: nothing proves it is the same pair.
 */
public final class RestatedReversePairs {

	private RestatedReversePairs() {
	}

	/** @return {@code chips} with each pair of her own orders raised from both sides shown once; a new list. */
	public static List<SafetyWarning> folded(List<SafetyWarning> chips) {
		if (chips == null || chips.size() < 2) {
			return chips;
		}
		List<SafetyWarning> shown = new ArrayList<SafetyWarning>(chips);
		Set<Integer> dropped = new HashSet<Integer>();
		for (int i = 0; i < shown.size(); i++) {
			if (dropped.contains(i)) {
				continue;
			}
			for (int j = i + 1; j < shown.size(); j++) {
				if (dropped.contains(j) || !restateOnePair(shown.get(i), shown.get(j))) {
					continue;
				}
				if (note(shown.get(j)).length() > note(shown.get(i)).length()) {
					shown.set(i, shown.get(j));
				}
				dropped.add(j);
			}
		}
		if (dropped.isEmpty()) {
			return chips;
		}
		List<SafetyWarning> result = new ArrayList<SafetyWarning>(shown.size() - dropped.size());
		for (int i = 0; i < shown.size(); i++) {
			if (!dropped.contains(i)) {
				result.add(shown.get(i));
			}
		}
		return Collections.unmodifiableList(result);
	}

	private static boolean restateOnePair(SafetyWarning a, SafetyWarning b) {
		if (!SafetyWarning.TYPE_INTERACTION.equals(a.getType()) || !SafetyWarning.TYPE_INTERACTION.equals(b.getType())
				|| !a.isAboutACurrentMedication() || !b.isAboutACurrentMedication() || a.getSeverity() == null
				|| !a.getSeverity().equals(b.getSeverity()) || a.namedPartners().size() != 1
				|| b.namedPartners().size() != 1) {
			return false;
		}
		Set<Object> subjectA = substances(a.subjectRows());
		Set<Object> subjectB = substances(b.subjectRows());
		Set<Object> partnerA = substances(a.rowsOfPartner(a.namedPartners().get(0)));
		Set<Object> partnerB = substances(b.rowsOfPartner(b.namedPartners().get(0)));
		if (subjectA.isEmpty() || subjectB.isEmpty() || partnerA.isEmpty() || partnerB.isEmpty()
				|| Collections.disjoint(subjectA, partnerB) || Collections.disjoint(subjectB, partnerA)) {
			return false;
		}
		String noteA = note(a);
		String noteB = note(b);
		return !noteA.isEmpty() && (noteA.startsWith(noteB) || noteB.startsWith(noteA)) && !noteB.isEmpty();
	}

	private static Set<Object> substances(List<DrugReference> rows) {
		Set<Object> keys = new HashSet<Object>();
		for (DrugReference row : rows) {
			keys.add(row.substanceGroupKey());
		}
		return keys;
	}

	/** The mechanism note: the detail after its first sentence — "X interacts with active order Y — Major." */
	private static String note(SafetyWarning chip) {
		String detail = chip.getDetail() == null ? "" : chip.getDetail().trim();
		String marker = " — " + chip.getSeverity() + ".";
		int at = detail.indexOf(marker);
		return at < 0 ? "" : detail.substring(at + marker.length()).trim();
	}

}
