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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * A question whether her medications duplicate one another is a screen, answered with the duplicates it found (ADR
 * Decision 232). On the 3.7.1 standalone Sarah Taylor, on ibuprofen, diclofenac and celecoxib, asked "Is she on any
 * duplicate NSAIDs?", was answered by the model "No duplicate NSAIDs are recorded.": no screen ran, so the finding that
 * the three share a drug group was never made. Over the bundled groups file and a verbatim slice of the shipped knowledge
 * base.
 */
public class DuplicateTherapyQuestionTest extends BaseModuleContextSensitiveTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-nsaid-branches.json";

	@Test
	public void aQuestionWhetherSheIsOnDuplicateTherapyIsAnsweredWithTheDuplicatesTheScreenFound() throws IOException {
		for (String question : Arrays.asList("Is she on any duplicate NSAIDs?", "Any duplicate therapy?",
			"Are any of her medications duplicates?", "Is she on more than one drug from the same class?")) {
			assertEquals("Yes — possible duplicate therapy among this patient's active medications:\nIbuprofen, Diclofenac "
					+ "and Celecoxib are in one drug group (NSAID) — possible duplicate therapy. [2] [3] [4]\nThis check "
					+ "compares the substances in her orders and the drug groups this module carries (NSAID and "
					+ "Penicillins); other drug classes are not covered.", ask(question, THREE_NSAIDS).getModuleAnswer(),
				question);
		}
	}

	/** Finding none, the module says nothing: drug classes beyond the groups it carries were never compared. */
	@Test
	public void aDuplicateQuestionThatFoundNoneIsLeftToTheModel() throws IOException {
		assertNull(ask("Is she on any duplicate NSAIDs?", Arrays.asList(
			DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"))).getModuleAnswer());
	}

	@Test
	public void aDuplicateThatIsNotAboutHerMedicationsScreensNothing() throws IOException {
		PatientChart chart = ask("Is there a duplicate encounter on her record?", THREE_NSAIDS);
		assertNull(chart.getModuleAnswer());
		assertFalse(chart.getText().contains("Safety finding"), "the prompt was:\n" + chart.getText());
	}

	private static final List<PatientClinicalContext.ActiveDrugOrder> THREE_NSAIDS = Arrays.asList(
		DrugReferenceTestSupport.activeOrder("order-ibuprofen", "Ibuprofen 400mg"),
		DrugReferenceTestSupport.activeOrder("order-diclofenac", "Diclofenac 50mg"),
		DrugReferenceTestSupport.activeOrder("order-celecoxib", "Celecoxib 200mg"));

	private static PatientChart ask(String question, List<PatientClinicalContext.ActiveDrugOrder> orders)
			throws IOException {
		List<String> names = new ArrayList<String>();
		for (PatientClinicalContext.ActiveDrugOrder order : orders) {
			names.addAll(order.getNames());
		}
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), DrugReferenceTestSupport.ctx(40, null,
					new LinkedHashSet<String>(names), null, null, null, orders), question);
	}
}
