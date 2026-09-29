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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.CitedFindingPartnerCompletionTest.OverShippedData;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/555">#555</a> through the
 * CONDITION-MEDIATED finding (ADR Decision 111): it names an order by the knowledge base's label for the
 * row that order resolved to — her <em>Lactic acid</em> as <em>Lactic acid (lactate)</em> — so an answer
 * naming the drug read as leaving the order out, and the module appended it under "not named above".
 *
 * <p>Context-sensitive because that finding is gated on {@code chartsearchai.drugSafety.derivedFindings},
 * which ships off and which only a context can turn on; {@link #setUp} does. Everything else is
 * {@link CitedFindingPartnerCompletionTest}'s arrangement: the real {@link LlmInferenceService#search}
 * over findings the real injector wrote from the shipped knowledge base, only the model stubbed.
 */
public class ConditionMediatedFindingPartnerCompletionContextTest extends BaseModuleContextSensitiveTest {

	/** The knowledge base's label for her lactic acid order, which the finding names it by. */
	private static final String LACTIC_ACID_LABEL = "Lactic acid (lactate)";

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService().setGlobalProperty(ChartSearchAiConstants.GP_DRUG_SAFETY_DERIVED_FINDINGS,
			ChartSearchAiConstants.DERIVED_FINDINGS_MAJOR);
	}

	@Test
	public void aCoMemberTheAnswerNamedWithoutItsLabelsParentheticalIsNotListedAgain() {
		// Metformin is rated Major in lactic acidosis, as her lactic acid is, and her stavudine's note names
		// it: lactic acid is the finding's CO-MEMBER, stavudine its partner.
		OverShippedData arrangement = metforminOverStavudineAndLacticAcid();
		RecordMapping finding = arrangement.findingNaming(Arrays.asList("Stavudine", LACTIC_ACID_LABEL));
		String modelAnswer = "Metformin should be used with caution: her Stavudine and Lactic acid orders are "
				+ "linked to lactic acidosis [" + finding.getIndex() + "].";
		assertWritesNoLabel(modelAnswer);

		ChartAnswer answer = arrangement.service(modelAnswer).search(CitedFindingPartnerCompletionTest.patient(),
			arrangement.question);

		assertEquals(modelAnswer, answer.getAnswer(),
			"an order the answer named without its label's parenthetical is not named again as \"not named above\"");
		CitedFindingPartnerCompletionTest.assertCoverage(2, 2, answer);
	}

	@Test
	public void aPartnerTheAnswerNamedWithoutItsLabelsParentheticalIsNotListedAgain() {
		// The other direction: stavudine's note names lactic acidosis and her lactic acid is rated Major in
		// it, so lactic acid is the finding's PARTNER.
		OverShippedData arrangement = new OverShippedData("Can I give stavudine?",
				new String[][] { { "Lactic acid", null } });
		RecordMapping finding = arrangement.findingNaming(Collections.singletonList(LACTIC_ACID_LABEL));
		String modelAnswer = "Stavudine should be used with caution: it and her Lactic acid are linked to lactic "
				+ "acidosis [" + finding.getIndex() + "].";
		assertWritesNoLabel(modelAnswer);

		ChartAnswer answer = arrangement.service(modelAnswer).search(CitedFindingPartnerCompletionTest.patient(),
			arrangement.question);

		assertEquals(modelAnswer, answer.getAnswer(), "was: " + answer.getAnswer());
		CitedFindingPartnerCompletionTest.assertCoverage(1, 1, answer);
	}

	@Test
	public void anOrderTheAnswerDidNotNameIsStillListed() {
		// The control: the rows credit the order the prose names by its drug, and no other.
		OverShippedData arrangement = metforminOverStavudineAndLacticAcid();
		RecordMapping finding = arrangement.findingNaming(Arrays.asList("Stavudine", LACTIC_ACID_LABEL));
		String modelAnswer = "Metformin should be used with caution: her Stavudine order is linked to lactic "
				+ "acidosis [" + finding.getIndex() + "].";

		ChartAnswer answer = arrangement.service(modelAnswer).search(CitedFindingPartnerCompletionTest.patient(),
			arrangement.question);

		assertEquals(modelAnswer + " Also covered by those findings and not named above: active order "
				+ LACTIC_ACID_LABEL + ".", answer.getAnswer());
		CitedFindingPartnerCompletionTest.assertCoverage(2, 1, answer);
	}

	@Test
	public void theConditionsMonitoringLabIsNotHerOrderEvenWhereTheKnowledgeBaseFilesItAsThatDrugsSynonym() {
		// The knowledge base files "lactate" as a name of Lactic acid — its rxnorm_name, and the label's own
		// parenthetical — and lactate is also the lab a clinician monitors for lactic acidosis, the condition
		// this finding is about. An answer telling her to monitor her lactate has not named her Lactic acid
		// order, so the module still names it (PR #556, review round 3).
		OverShippedData arrangement = metforminOverStavudineAndLacticAcid();
		RecordMapping finding = arrangement.findingNaming(Arrays.asList("Stavudine", LACTIC_ACID_LABEL));
		String modelAnswer = "Metformin should be used with caution: her Stavudine order is linked to lactic "
				+ "acidosis [" + finding.getIndex() + "]. Monitor her lactate.";

		ChartAnswer answer = arrangement.service(modelAnswer).search(CitedFindingPartnerCompletionTest.patient(),
			arrangement.question);

		assertEquals(modelAnswer + " Also covered by those findings and not named above: active order "
				+ LACTIC_ACID_LABEL + ".", answer.getAnswer());
		CitedFindingPartnerCompletionTest.assertCoverage(2, 1, answer);
	}

	private static OverShippedData metforminOverStavudineAndLacticAcid() {
		return new OverShippedData("Can I give metformin?",
				new String[][] { { "Stavudine", null }, { "Lactic acid", null } });
	}

	private static void assertWritesNoLabel(String modelAnswer) {
		assertFalse(modelAnswer.toLowerCase(Locale.ROOT).contains(LACTIC_ACID_LABEL.toLowerCase(Locale.ROOT)),
			"the premise: the answer does not write the label");
	}
}
