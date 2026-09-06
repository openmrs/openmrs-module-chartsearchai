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
 * <p>So the rule is pinned by the arrangement that actually separates the two designs, and there is
 * exactly one: <b>the arms switched OFF over a dataset that DID load</b>. A gated accessor states
 * {@code null} there; this one states what the dataset publishes. That is the case below, and it is
 * the one where the statement is most worth having — an operator who turned the contraindication
 * arms off has certainly not screened anyone's conditions.
 */
public class ConditionRuleCoverageGateContextTest extends BaseModuleContextSensitiveTest {

	private static void set(String property, String value) {
		Context.getAdministrationService().setGlobalProperty(property, value);
	}

	/**
	 * The discriminating arrangement. Mutate {@code conditionRuleCoverage()} to return {@code null}
	 * where {@code reportsContraindications()} is false — the rejected design, verbatim — and this
	 * case is the one that reddens; the rest of the suite does not.
	 */
	@Test
	public void theVerdictIsStatedEvenWhereTheContraindicationArmsAreSwitchedOff() {
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_WARN_ON_CONTRAINDICATIONS, "false");
		set(ChartSearchAiConstants.GP_DRUG_SAFETY_VALIDATE_ANSWERS, "false");

		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(new DrugReferenceService());

		assertTrue(new DrugReferenceService().getLoadStatus().isLoaded(),
				"the premise: a dataset really did load, so the verdict below is a reading of one "
						+ "rather than of an empty cache — which is what makes this arrangement, and "
						+ "not the stock install, the one that separates the two designs");
		assertEquals(DrugReferenceLoad.Coverage.ABSENT, validator.conditionRuleCoverage(),
				"the arms are off, so this patient's conditions are certainly not screened — and that "
						+ "is exactly when a client most needs the dataset's own verdict. A gate on "
						+ "reportsContraindications() would state null here, withholding a fact the "
						+ "module holds (issue #378)");
	}

	/**
	 * And the shipped default still answers {@code UNLOADED}, which is what the false argument
	 * claimed a gate would take away. Kept beside the case above so the pair reads as the
	 * measurement: the gate changes the line above and nothing here.
	 */
	@Test
	public void theStockInstallStillStatesThatNobodyLooked() {
		set(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "false");

		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(new DrugReferenceService());

		assertEquals(DrugReferenceLoad.Coverage.UNLOADED, validator.conditionRuleCoverage(),
				"nothing was read, so nothing is known — and reading this must not be what triggers a "
						+ "load on an install that does not use the feature");
	}
}
