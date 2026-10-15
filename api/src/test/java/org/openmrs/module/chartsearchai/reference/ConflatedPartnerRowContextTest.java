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
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Two rules of one drug about one of her orders are decided by the rule ABOUT that order's own row where they tie on
 * severity (ADR Decision 238). The shipped knowledge base files Omeprazole under esomeprazole's rxnorm_name and ATC code, so
 * diazepam carries two Moderate rules that both reach an omeprazole order — one about the Omeprazole row, one about the
 * Esomeprazole row — and the longer note won: on the 3.7.1 standalone Brian Lopez, on diazepam and omeprazole, read
 * "Diazepam interacts with active order esomeprazole — Moderate", a drug he does not take. Over the real load of a verbatim
 * slice, so the load's check names the Omeprazole row and its rules print its own name (ADR Decision 234).
 */
public class ConflatedPartnerRowContextTest extends BaseModuleContextSensitiveTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-omeprazole-filed-as-esomeprazole.json";

	private final List<File> created = new ArrayList<File>();

	@AfterEach
	public void deleteCopiedDatasets() {
		for (File file : created) {
			file.delete();
		}
		created.clear();
	}

	@Test
	public void aScreenNamesHisOmeprazoleByItsOwnName() throws IOException {
		assertNamesOmeprazoleAndNeverEsomeprazole(DrugReferenceTestSupport.details(loaded().validate("",
			"Does he have any drug interactions?", context(
				DrugReferenceTestSupport.activeOrder("order-diazepam", "Diazepam 5mg", "Diazepam"),
				DrugReferenceTestSupport.activeOrder("order-omeprazole", "Omeprazole 20mg", "Omeprazole")))));
	}

	@Test
	public void aProposalNamesHisOmeprazoleByItsOwnName() throws IOException {
		assertNamesOmeprazoleAndNeverEsomeprazole(DrugReferenceTestSupport.details(loaded().validate("",
			"Can I give him diazepam?", context(
				DrugReferenceTestSupport.activeOrder("order-omeprazole", "Omeprazole 20mg", "Omeprazole")))));
	}

	/** The record the model reads keeps the same rule as the chip, so its note names the drug he takes as well. */
	@Test
	public void theRecordBesideTheChipNamesHisOmeprazoleByItsOwnName() throws IOException {
		loaded();
		String prompt = DrugReferenceTestSupport.injectorWithSafety(new DrugReferenceService()).injectRecords(
			DrugReferenceTestSupport.oneRecordChart(), context(
				DrugReferenceTestSupport.activeOrder("order-omeprazole", "Omeprazole 20mg", "Omeprazole")),
			"Can I give him diazepam?").getText();

		assertTrue(prompt.contains(" Interactions: Omeprazole ("), "the prompt was:\n" + prompt);
		assertFalse(prompt.toLowerCase(Locale.ROOT).contains("esomeprazole"), "the prompt was:\n" + prompt);
	}

	private static void assertNamesOmeprazoleAndNeverEsomeprazole(List<String> details) {
		assertTrue(details.stream().anyMatch(d -> d.startsWith("Diazepam interacts with active order Omeprazole — Moderate.")),
			"was: " + details);
		assertFalse(details.stream().anyMatch(d -> d.toLowerCase(Locale.ROOT).contains("esomeprazole")), "was: " + details);
	}

	private DrugSafetyValidator loaded() throws IOException {
		String path = DrugReferenceTestSupport.copyDatasetToAppData(FIXTURE, "omeprazole-conflated.json", created);
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_SOURCE_FORMAT,
			ChartSearchAiConstants.DRUG_REFERENCE_SOURCE_DDINTER);
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, path);
		return DrugReferenceTestSupport.validator(new DrugReferenceService());
	}

	private static PatientClinicalContext context(PatientClinicalContext.ActiveDrugOrder... orders) {
		LinkedHashSet<String> names = new LinkedHashSet<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.ctx(44, null, names, null, null, null, Arrays.asList(orders));
	}
}
