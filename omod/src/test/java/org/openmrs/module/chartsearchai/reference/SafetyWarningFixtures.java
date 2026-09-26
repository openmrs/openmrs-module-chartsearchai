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
import java.util.Date;
import java.util.List;

/**
 * Builds a {@link SafetyWarning} through a factory or method {@code SafetyWarning} keeps
 * package-private, for an omod test that cannot reach one from
 * {@code org.openmrs.module.chartsearchai.web.rest}.
 *
 * <p><b>Why it exists, and why it is not a widening of production API.</b> The chip-serialization
 * guards live in {@code web.rest}, and the facts these shapes carry are set only through
 * {@code SafetyWarning}'s package-private factories and methods. This class is declared in
 * {@code SafetyWarning}'s OWN package under {@code omod/src/test}, so it reaches them with no
 * production change at all — a split package across two artifacts being legal on a plain classpath,
 * which is what surefire gives these tests.
 *
 * <p><b>The point is that the chip is one PRODUCTION built.</b> Two alternatives were available — a
 * new public factory taking the flag, and an anonymous subclass overriding the accessor — and both are
 * weaker. ADR Decision 92 records the comparison and is canonical for it.
 *
 * <p>Deliberately NOT a general-purpose chip builder: it exposes only the shapes the wire guards need,
 * so it cannot become a second way to assemble the chips {@code DrugSafetyValidator} assembles.
 */
public final class SafetyWarningFixtures {

	private SafetyWarningFixtures() {
	}

	/**
	 * A contraindication chip whose chart match nothing corroborates — the shape issue #374 is about,
	 * built by {@code SafetyWarning.contraindication}, the curated-rule arm's own factory.
	 *
	 * <p>{@code aboutACurrentMedication} is false and {@code chartRecords} empty because neither is
	 * what this shape is for, and the caller is not offered them: a wire fixture states the fact under
	 * test and takes the arm's defaults for the rest.
	 */
	public static SafetyWarning uncorroboratedContraindication(String drug, String detail) {
		return SafetyWarning.contraindication(drug, detail, true, false,
			Collections.<String> emptySet());
	}

	/**
	 * An interaction chip about a drug the chart records only as an order no longer in force, which
	 * stopped on {@code stopDate} — the shape issue #472 is about — stated through
	 * {@code SafetyWarning.asAboutAnEndedOrder}, the one way the question-driven arms state it.
	 */
	public static SafetyWarning endedOrderInteraction(String drug, String detail, String severity,
			Date stopDate) {
		return new SafetyWarning(SafetyWarning.TYPE_INTERACTION, drug, detail, severity)
				.asAboutAnEndedOrder(stopDate, Collections.<DrugReference> emptyList());
	}

	/**
	 * A curated-rule contraindication chip not flagged as resting on an uncorroborated chart match, built
	 * by {@code SafetyWarning.contraindication}, the curated-rule arm's own factory (issue #527).
	 * {@code aboutACurrentMedication} is that factory's own parameter: {@code true} as
	 * {@code DrugSafetyValidator.addActiveOrderContraindications} passes it for one of her active orders,
	 * {@code false} as the drug-in-play loop passes it. {@code chartRecords} is empty, being on no chip
	 * key.
	 */
	public static SafetyWarning curatedRuleContraindication(String drug, String detail,
			boolean aboutACurrentMedication) {
		return SafetyWarning.contraindication(drug, detail, false, aboutACurrentMedication,
			Collections.<String> emptySet());
	}

	/**
	 * A recorded-allergen contraindication chip — the allergen arm's own sentence, built by
	 * {@code SafetyWarning.recordedAllergenContraindication}, the factory that arm's two callers share
	 * (issue #527). {@code aboutACurrentMedication} is that factory's own parameter: {@code true} as
	 * {@code DrugSafetyValidator.addActiveOrderContraindications} passes it for one of her active orders,
	 * {@code false} as the drug-in-play loop passes it for a drug the question or the answer put in play.
	 * {@code chartRecords} is empty, being on no chip key.
	 */
	public static SafetyWarning recordedAllergenContraindication(String drug, String detail,
			boolean aboutACurrentMedication) {
		return SafetyWarning.recordedAllergenContraindication(drug, detail, aboutACurrentMedication,
			Collections.<String> emptySet());
	}

	/**
	 * An interaction RULE chip naming one active order, {@code partner} — built by
	 * {@code SafetyWarning.interaction}, the factory {@code DrugSafetyValidator.interactionWarning} hands
	 * both active-order arms' rule chips to (issue #527). {@code aboutACurrentMedication} is that
	 * factory's own parameter: {@code true} as the screening arm passes it, {@code false} as the
	 * drug-in-play arm does. So is {@code bridges}, the chip's {@code chartOrderBridges}, which each of
	 * those arms resolves through {@code DrugSafetyValidator.chartOrderBridges} before it builds the chip.
	 * No fold and no reconciled name.
	 */
	public static SafetyWarning ruleInteraction(String drug, String detail, String severity, String partner,
			List<SafetyWarning.ChartOrderBridge> bridges, boolean aboutACurrentMedication) {
		return SafetyWarning.interaction(drug, detail, severity, false, null, null, bridges,
			aboutACurrentMedication, Collections.singletonList(partner));
	}

	/**
	 * The finding that two or more of her own orders carry the same substances — built by
	 * {@code SafetyWarning.ordersSharingASubstance}, whose referent is {@code true} (issue #477): every
	 * order it names is hers.
	 */
	public static SafetyWarning ordersSharingASubstance(String drug, String detail, List<String> orders) {
		return SafetyWarning.ordersSharingASubstance(drug, detail, orders);
	}

	/**
	 * The finding that the drug in play is already in two or more of her orders — built by
	 * {@code SafetyWarning.substanceInSeveralActiveOrders}, the drug-in-play arm's (issue #477), whose
	 * referent is the one that arm states for the drug in play: {@code true} where her orders resolve to
	 * its substance, {@code false} where they do not (issue #402).
	 */
	public static SafetyWarning substanceInSeveralActiveOrders(String drug, String detail,
			List<String> orders, boolean aboutACurrentMedication) {
		return SafetyWarning.substanceInSeveralActiveOrders(drug, detail, orders, aboutACurrentMedication);
	}
}
