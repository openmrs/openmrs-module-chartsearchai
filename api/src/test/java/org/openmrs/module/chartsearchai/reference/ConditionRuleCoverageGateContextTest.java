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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * {@code DrugSafetyValidator.conditionRuleCoverage()} is NOT gated on the {@code drugSafety}
 * toggles, and this is the case that holds that rule (issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/378">#378</a>).
 *
 * <p><b>It exists because the rule was written down with a false reason behind it.</b> The javadoc
 * and ADR Decision 75 first argued that gating the accessor on
 * {@code DrugSafetyValidator.reportsContraindications()} would make
 * {@link DrugReferenceLoad.Coverage#UNLOADED} unreachable on the {@code /search} surface. It would
 * not: that gate reads {@code drugSafety.validateAnswers} and {@code warnOnContraindications},
 * which default TRUE, while whether the dataset loads at all is decided by
 * {@code drugReference.enabled}, which defaults FALSE — so on a stock install the gate is open and
 * the answer is {@code UNLOADED} with or without it. Two review agents each implemented the
 * rejected gate and watched the whole suite stay green. A rule whose only support is a paragraph,
 * and a refutable one, is a rule the next session removes for free.
 *
 * <p>So the rule is pinned by an arrangement that actually separates the two designs: <b>the arms
 * switched OFF over a dataset that DID load</b>. A gated accessor states {@code null} there; this
 * one states what the dataset publishes. It is the case where the statement is most worth having —
 * an operator who turned the contraindication arms off has certainly not screened anyone's
 * conditions. It is not the only separating arrangement, and an earlier draft of this sentence
 * claimed it was: the designs also differ with the arms off and the feature off, where a gate states
 * {@code null} and this states {@code UNLOADED}. That one is the same rule with less at stake, so it
 * is named here rather than given a case.
 */
public class ConditionRuleCoverageGateContextTest extends BaseModuleContextSensitiveTest {

	private static void set(String property, String value) {
		Context.getAdministrationService().setGlobalProperty(property, value);
	}

	/**
	 * The discriminating arrangement. Mutate {@code conditionRuleCoverage()} to return {@code null}
	 * where {@code reportsContraindications()} is false — the rejected design, verbatim — and read
	 * the failures: measured on the commit that added this, that mutation reddens this case and
	 * leaves the rest of the suite green.
	 */
	@Test
	public void theVerdictIsStatedEvenWhereTheContraindicationArmsAreSwitchedOff() {
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "false");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS, "false");

		// ONE service, and the premise asserted off THAT one: `dataset` is a per-instance field, so a
		// second service parses the file again and the premise would be about a different load than
		// the verdict. DrugReferenceTestSupport's own javadoc warns against that shape.
		DrugReferenceService service = new DrugReferenceService();
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(service);

		assertFalse(DrugSafetyValidator.reportsContraindications(),
				"the OTHER premise, and the one that makes this case discriminating: the two toggles "
						+ "above really did take effect, so the rejected gate would be CLOSED here. "
						+ "Without this the case could go vacuous — still green, and no longer "
						+ "separating the two designs — if those global properties stopped reaching "
						+ "reportsContraindications()");
		assertTrue(service.getLoadStatus().isLoaded(),
				"the premise: a dataset really did load, so the verdict below is a reading of one "
						+ "rather than of an empty cache — which is what makes this arrangement, and not "
						+ "the stock install, separate the two designs at all");
		assertEquals(DrugReferenceLoad.Coverage.ABSENT, validator.conditionRuleCoverage(),
				"the arms are off, so this patient's conditions are certainly not screened — and that "
						+ "is exactly when a client most needs the dataset's own verdict. A gate on "
						+ "reportsContraindications() would state null here, withholding a fact the "
						+ "module holds (issue #378)");
	}

	/**
	 * The second separating arrangement, and the one the class javadoc names: the arms off AND the
	 * feature off. A gated accessor states {@code null} here; this one still says nobody looked.
	 *
	 * <p><b>It sets the toggles rather than leaving them at their defaults, and that is the whole
	 * difference between a guard and a restatement.</b> With them unset the gate is OPEN, so the case
	 * would assert a value identical under both designs — which is what an earlier version of this
	 * method did, while its javadoc claimed it was the half the gate leaves untouched. A review agent
	 * found that, and also that
	 * {@code LlmInferenceServiceConditionRuleCoverageContextTest.search_statesThatNobodyLookedWhereTheFeatureIsOff}
	 * already carries the stock install more strongly, off the answer rather than off the accessor.
	 */
	@Test
	public void nobodyLookedIsStatedEvenWithTheArmsOffAsWell() {
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "false");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "false");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS, "false");

		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(new DrugReferenceService());

		assertFalse(DrugSafetyValidator.reportsContraindications(),
				"the premise: the arms really are off, so the rejected gate would be closed here too");
		assertEquals(DrugReferenceLoad.Coverage.UNLOADED, validator.conditionRuleCoverage(),
				"nothing was read, so nothing is known — and reading this must not be what triggers a "
						+ "load on an install that does not use the feature");
	}
}
