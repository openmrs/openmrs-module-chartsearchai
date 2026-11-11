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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A drug asked about is no interaction with a substance its own combination order carries beside it (ADR Decision 624).
 * On the demo replica patient dd5558ed, whose only rifampicin is three orders of "Rifampicin isoniazid pyrazinamide and
 * ethambutol", asked "Can I give him rifampicin?", was told first "Rifampicin (rifampin) interacts with active order
 * Rifampicin isoniazid pyrazinamide and ethambutol — Major. The interacting substance is Pyrazinamide." — the tablet's
 * own constituents, counted among "interactions with this patient's other active medications". Decision 622 had stopped
 * the screen relating them; the drug-in-play arm still did.
 */
public class OwnCombinationConstituentTest extends BaseModuleContextSensitiveTest {

	private static final String RHZE = "Rifampicin isoniazid pyrazinamide and ethambutol";

	private static final String RHZ = "Isoniazid / pyrazinamide / rifampin";

	@BeforeEach
	public void answerFromFindings() {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_ANSWER_FROM_FINDINGS, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
	}

	@Test
	public void herDrugAskedAboutIsNoInteractionWithItsOwnTabletsOtherSubstances() {
		for (String question : Arrays.asList("Can I give him rifampicin?", "Can I stop his isoniazid?",
			"Does rifampicin interact with his medications?")) {
			List<SafetyWarning> chips = chips(question, order("order-rhze-1", RHZE), order("order-rhze-2", RHZE),
				order("order-rhze-3", RHZE), order("order-efv", "Efavirenz"));

			// Each interaction is with her efavirenz, the one order that is not the tablet; the tablet repeated is the
			// duplicate-therapy finding.
			for (SafetyWarning chip : chips) {
				assertTrue(!SafetyWarning.TYPE_INTERACTION.equals(chip.getType()) || chip.getDetail().contains(" — possible "
						+ "duplicate therapy") || chip.getDetail().contains("interacts with active order Efavirenz"),
					question + " — a chip pairs the tablet with itself: " + details(chips));
			}
			String answer = answer(question, order("order-rhze-1", RHZE), order("order-rhze-2", RHZE),
				order("order-rhze-3", RHZE), order("order-efv", "Efavirenz"));
			assertNotNull(answer, question + " — the module answers");
			for (String own : Arrays.asList("Rifampicin (rifampin)", "Isoniazid", "Pyrazinamide", "Ethambutol", RHZE)) {
				assertFalse(answer.contains("interacts with active order " + own), question + " — was: " + answer);
			}
		}
	}

	/** Two DIFFERENT products both carrying the pair still relate it, as Decision 486 keeps for the screen. */
	@Test
	public void twoDifferentProductsCarryingThePairStillRelateIt() {
		List<SafetyWarning> chips = chips("Can I give him rifampicin?", order("order-rhze", RHZE), order("order-rhz", RHZ));

		assertTrue(chips.stream().anyMatch(c -> SafetyWarning.TYPE_INTERACTION.equals(c.getType())
				&& "Major".equals(c.getSeverity())),
			"pyrazinamide from another product than the asked drug's own order: " + details(chips));
	}

	/** A partner in an order of its own is no constituent of hers: rifampicin proposed beside her pyrazinamide. */
	@Test
	public void aPartnerInAnOrderOfItsOwnIsStillRelated() {
		List<SafetyWarning> chips = chips("Can I give him rifampicin?", order("order-pza", "Pyrazinamide"));

		assertTrue(chips.stream().anyMatch(c -> SafetyWarning.TYPE_INTERACTION.equals(c.getType())
				&& "Major".equals(c.getSeverity())), details(chips).toString());
	}

	private static List<String> details(List<SafetyWarning> chips) {
		List<String> details = new ArrayList<String>();
		for (SafetyWarning chip : chips) {
			details.add(chip.getSeverity() + ": " + chip.getDetail());
		}
		return details;
	}

	private static PatientClinicalContext.ActiveDrugOrder order(String uuid, String display) {
		return DrugReferenceTestSupport.activeOrder(uuid, display);
	}

	private static PatientClinicalContext context(PatientClinicalContext.ActiveDrugOrder... orders) {
		Set<String> names = new LinkedHashSet<String>();
		Set<String> codes = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
			codes.addAll(order.getAtcCodes());
		}
		return DrugReferenceTestSupport.ctx(40, null, names, codes, null, null, Arrays.asList(orders));
	}

	private static List<SafetyWarning> chips(String question, PatientClinicalContext.ActiveDrugOrder... orders) {
		return DrugReferenceTestSupport.validator(new DrugReferenceService()).validate("", question, context(orders));
	}

	private static String answer(String question, PatientClinicalContext.ActiveDrugOrder... orders) {
		return DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context(orders), question).getModuleAnswer();
	}
}
