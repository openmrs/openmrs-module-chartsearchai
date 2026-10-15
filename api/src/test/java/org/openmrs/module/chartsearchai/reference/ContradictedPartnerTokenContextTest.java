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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A partner a rule names by a synonym the load's check reports as another substance's is named by its own name (ADR
 * Decision 234). The shipped knowledge base files Sulfamethoxazole under "sulfamethazine", a different sulfonamide, and
 * every rule about it carries that token; Decision 216 kept it out of the LABEL, and the chips naming the partner still
 * printed it — on the 3.7.1 standalone every co-trimoxazole patient read "Methotrexate interacts with active order
 * sulfamethazine". Over the real load of the #476 slice, so the check itself runs.
 */
public class ContradictedPartnerTokenContextTest extends BaseModuleContextSensitiveTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-substance-name-contradicted-by-the-bridge.json";

	private final List<File> created = new ArrayList<File>();

	@AfterEach
	public void deleteCopiedDatasets() {
		for (File file : created) {
			file.delete();
		}
		created.clear();
	}

	@Test
	public void aCoTrimoxazoleOrderIsNamedSulfamethoxazoleAndNeverSulfamethazine() throws IOException {
		List<String> details = DrugReferenceTestSupport.details(loaded().validate("", "Can I give her benzocaine?",
			onCoTrimoxazole()));

		assertTrue(details.stream().anyMatch(d -> d.startsWith(
			"Benzocaine (topical) interacts with active order Sulfamethoxazole — Moderate.")), "was: " + details);
		assertFalse(details.stream().anyMatch(d -> d.toLowerCase().contains("sulfamethazine")), "was: " + details);
	}

	private DrugSafetyValidator loaded() throws IOException {
		String path = DrugReferenceTestSupport.copyDatasetToAppData(FIXTURE, "h476-partner.json", created);
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT,
			ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER);
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, path);
		return DrugReferenceTestSupport.validator(new DrugReferenceService());
	}

	/**
	 * A patient on co-trimoxazole as Amina Wekesa is on the standalone: "Cotrimoxazole 960mg", a name carrying neither
	 * constituent, its concept named "Sulfamethoxazole / trimethoprim" and mapped to J01EE01 — a code no row of the data
	 * carries, so the partner is named by the ORDER, whose display the rule's token does not name.
	 */
	private static PatientClinicalContext onCoTrimoxazole() {
		List<String> names = Arrays.asList("Cotrimoxazole 960mg", "Sulfamethoxazole / trimethoprim");
		List<PatientClinicalContext.ActiveDrugOrder> orders = Arrays.asList(DrugReferenceTestSupport.activeOrder("order-ctx",
			"Cotrimoxazole 960mg", new LinkedHashSet<String>(names), DrugReferenceTestSupport.set("J01EE01")));
		return DrugReferenceTestSupport.ctx(34, null, new LinkedHashSet<String>(names), DrugReferenceTestSupport.set("J01EE01"),
			null, null, orders);
	}
}
