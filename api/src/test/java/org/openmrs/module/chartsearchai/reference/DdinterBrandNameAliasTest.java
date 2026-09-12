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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * A clinician's question is where brand names live, and until knowledge-base schema 1.3 the
 * {@code ddinter} source knew a drug only by its DDInter name, its RxNorm generic name, and its
 * CIEL concept names. Measured live on the standalone (openmrs-ddi-knowledge-base issue #5): a
 * patient on simvastatin, asked <i>"Is it safe to give her panadol?"</i>, had nothing injected and
 * nothing checked, because {@code panadol} was no alias of the Acetaminophen entry. The asked-about
 * drug escaped evaluation while the answer reported "no information recorded", which is
 * indistinguishable in the UI from evaluated-and-clean.
 *
 * <p>The knowledge base now carries each drug's RxNorm brand-name (BN) concepts as
 * {@code brand_names}, and {@link DdiDrugReferenceSource} reads them into the alias list. These
 * cases drive the real parser, the real {@link DrugReferenceService} resolution, and the real
 * {@link DrugReferenceInjector} over {@code ddi-brand-name-aliases.json}, a verbatim slice of the
 * shipped knowledge base (acetaminophen, simvastatin, ezetimibe, and the rows among them).
 */
public class DdinterBrandNameAliasTest {

	private static final String FIXTURE = "chartsearchai-test/ddi-brand-name-aliases.json";

	private static final String PANADOL_QUESTION = "Is it safe to give her panadol?";

	private static List<String> namesResolvedBy(String fixture, String question) throws IOException {
		List<String> names = new ArrayList<String>();
		for (DrugReference ref : DrugReferenceTestSupport.ddiFixtureService(fixture).findByQuery(question)) {
			names.add(ref.getName());
		}
		Collections.sort(names);
		return names;
	}

	@Test
	public void aBrandNameInTheQuestionResolvesTheIngredient() throws IOException {
		assertEquals(Collections.singletonList("Acetaminophen"), namesResolvedBy(FIXTURE, PANADOL_QUESTION),
				"the live case from issue #5: panadol must resolve to the acetaminophen entry");
	}

	@Test
	public void aCombinationBrandResolvesEveryIngredientItContains() throws IOException {
		// Vytorin is ezetimibe + simvastatin, and RxNorm files the brand under both ingredients. A question
		// about the brand must check both, the same way a bridged CIEL combination name already does.
		List<String> expected = new ArrayList<String>();
		expected.add("Ezetimibe");
		expected.add("Simvastatin");
		assertEquals(expected, namesResolvedBy(FIXTURE, "Can she take Vytorin with her other medicines?"));
	}

	@Test
	public void aBrandNameQuestionInjectsTheIngredientsReferenceRecord() throws IOException {
		// The whole point of resolving the brand: the drug the clinician asked about is injected as a citable
		// record, so the model grounds its answer in it and the safety arms have a subject to check.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(FIXTURE);
		PatientClinicalContext onSimvastatin = DrugReferenceTestSupport.ctx(40, null,
				DrugReferenceTestSupport.set("Simvastatin"), DrugReferenceTestSupport.set("C10AA01"), null, null);
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), onSimvastatin, PANADOL_QUESTION);

		List<RecordMapping> injected = DrugReferenceTestSupport.injectedReferences(chart);
		assertTrue(!injected.isEmpty(), "the panadol question must inject a reference record; before schema 1.3 "
				+ "it injected nothing and the asked-about drug was never evaluated");
		boolean namesAcetaminophen = false;
		for (RecordMapping record : injected) {
			if (record.getText().contains("Acetaminophen")) {
				namesAcetaminophen = true;
			}
		}
		assertTrue(namesAcetaminophen, "the injected record must be acetaminophen's, not a partner's: "
				+ injected);
	}

	@Test
	public void anOlderFileWithoutBrandNamesStillLoadsAndResolvesNothingForABrand() throws IOException {
		// The pinned schema 1.0 excerpt carries no brand_names. It must load exactly as before (the read is
		// a zero-iteration loop over a missing node) and, honestly, resolve nothing for a brand.
		assertTrue(DrugReferenceTestSupport.ddiFixtureService(DrugReferenceTestSupport.DDI_EXCERPT).findByQuery("aspirin")
				.size() > 0, "precondition: the excerpt loads and resolves a generic name");
		assertEquals(Collections.emptyList(), namesResolvedBy(DrugReferenceTestSupport.DDI_EXCERPT, PANADOL_QUESTION));
	}
}
