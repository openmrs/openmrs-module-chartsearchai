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
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.ctx;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.ddinterService;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.injectedClassNotes;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.injector;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.oneRecordChart;
import static org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport.set;

import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * The drug-class note follows the QUESTION-driven injection toggle (issue #354).
 *
 * <p>It is that leg's own material — it reports what the question leg found no substance for — so an
 * operator who has turned question-driven injection off must not get it. That is a branch of
 * {@code DrugReferenceInjector.injectRecords} and nothing else in the suite flips this global
 * property, so without this case the branch is never executed with the toggle off.
 *
 * <p>Context-sensitive because the toggle is only readable through {@code Context}: with none,
 * {@code ChartSearchAiUtils.getBooleanGlobalProperty} fails safe to the declared default and a plain
 * unit test cannot express the off state at all.
 */
public class DrugClassNoteInjectionToggleTest extends BaseModuleContextSensitiveTest {

	private static final String CLASS_QUESTION = "Can I start this patient on an oral contraceptive?";

	private PatientChart injectWithQueryLeg(String enabled) {
		Context.getAdministrationService().setGlobalProperty(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_INJECT_FROM_QUERY, enabled);
		return injector(ddinterService()).injectRecords(oneRecordChart(),
				ctx(34, null, set("warfarin 5mg"), set("B01AA03"), null, null), CLASS_QUESTION);
	}


	@Test
	public void theNoteIsRaisedWhileTheQuestionDrivenLegIsOn() {
		assertFalse(injectedClassNotes(injectWithQueryLeg("true")).isEmpty(),
				"the premise: with the question leg on, this question raises the note");
	}

	@Test
	public void theNoteIsNotRaisedWhereTheQuestionDrivenLegIsOff() {
		assertTrue(injectedClassNotes(injectWithQueryLeg("false")).isEmpty(),
				"the note is the question leg's own material, so it must stand down with that leg");
	}
}
