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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Unit tests for {@link LlmInferenceService#applyVerdictGuard}. The guard is a deterministic
 * post-processing rule, so — like {@code extractCitedReferences} — it is exercised directly with
 * constructed references (the codebase convention for the pure citation/answer helpers).
 *
 * <p>The load-bearing contract is <strong>citation neutrality</strong>: rewriting the verdict lead
 * must never add or drop an inline {@code [N]} marker, because the downstream reference extraction
 * and the drift/recall metrics are computed from exactly those markers.
 */
public class LlmInferenceServiceVerdictGuardTest {

	private static RecordReference ref(int index, String type) {
		return new RecordReference(index, type, "uuid-" + index, null);
	}

	private static final Pattern CITATION = Pattern.compile("\\[(\\d+)\\]");

	private static Set<String> citations(String text) {
		Set<String> out = new LinkedHashSet<String>();
		Matcher m = CITATION.matcher(text);
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}

	@Test
	public void applyVerdictGuard_rewritesLabOnlyYesToNoFamily() {
		String answer = "Yes — kidney function labs are recorded: Serum creatinine 78.7 [9], "
				+ "Blood urea nitrogen 98.7 [11].";
		List<RecordReference> cited = Arrays.asList(ref(9, "obs"), ref(11, "obs"));

		String result = LlmInferenceService.applyVerdictGuard(answer, cited);

		assertTrue(result.matches("(?is)^\\s*no\\b.*"), "verdict must lead with No: " + result);
		assertEquals(citations(answer), citations(result), "citations must be unchanged");
		assertTrue(result.contains("[9]") && result.contains("[11]"), "evidence markers preserved");
	}

	@Test
	public void applyVerdictGuard_leavesYesWhenANamingRecordIsCited() {
		String answer = "Yes — kidney issues are recorded: Chronic kidney disease [30], "
				+ "Serum creatinine 78.7 [9].";
		// [30] is a condition — a record that explicitly NAMES the problem, so the Yes is legitimate.
		List<RecordReference> cited = Arrays.asList(ref(30, "condition"), ref(9, "obs"));

		String result = LlmInferenceService.applyVerdictGuard(answer, cited);

		assertSame(answer, result, "a Yes backed by a naming record must be left unchanged");
	}

	@Test
	public void applyVerdictGuard_leavesYesWhenCitationIsInjectedDrugReference() {
		// A drug_reference is injected reference data, not a patient measurement; the guard must NOT
		// fire on a "Yes" citing one (e.g. a drug-interaction answer), or it would negate a valid
		// verdict AND stamp a nonsensical "No diagnosis…" lead on it.
		String answer = "Yes — drug A and drug B interact [1].";
		List<RecordReference> cited = Arrays.asList(ref(1, "drug_reference"));

		assertSame(answer, LlmInferenceService.applyVerdictGuard(answer, cited));
	}

	@Test
	public void applyVerdictGuard_leavesYesWhenAnyCitationIsNonMeasurement() {
		// The guard fires only when EVERY citation is a measurement (obs/test_order). A referral
		// order mixed in with labs is a non-measurement, so the "Yes" is left untouched.
		String answer = "Yes — creatinine 78.7 [1] and a nephrology referral [2].";
		List<RecordReference> cited = Arrays.asList(ref(1, "obs"), ref(2, "referral_order"));

		assertSame(answer, LlmInferenceService.applyVerdictGuard(answer, cited));
	}

	@Test
	public void applyVerdictGuard_leavesNonYesAnswer() {
		String answer = "No hypertension diagnosis is recorded. Blood pressure 145 mmHg [2].";
		List<RecordReference> cited = Arrays.asList(ref(2, "obs"));

		assertSame(answer, LlmInferenceService.applyVerdictGuard(answer, cited));
	}

	@Test
	public void applyVerdictGuard_leavesYesWithNoCitations() {
		String answer = "Yes.";
		assertSame(answer, LlmInferenceService.applyVerdictGuard(answer, Collections.<RecordReference>emptyList()));
	}

	@Test
	public void applyVerdictGuard_isCitationNeutralWhenReaffirmClauseCarriesAMarker() {
		// The re-affirming opener the guard strips to avoid a "No … there are records" contradiction
		// MUST NOT be stripped when it carries a citation, or an inline [7] would be silently dropped.
		String answer = "Yes, there are records of kidney issues [7]. Urine test [3].";
		List<RecordReference> cited = Arrays.asList(ref(7, "obs"), ref(3, "obs"));

		String result = LlmInferenceService.applyVerdictGuard(answer, cited);

		assertTrue(result.matches("(?is)^\\s*no\\b.*"), "verdict must lead with No: " + result);
		assertEquals(citations(answer), citations(result),
		    "no inline citation may be dropped by the rewrite: " + result);
	}

	@Test
	public void applyVerdictGuard_stripsReaffirmContradictionWhenNoMarker() {
		String answer = "Yes, there are records of kidney issues. Urine test [3].";
		List<RecordReference> cited = Arrays.asList(ref(3, "obs"));

		String result = LlmInferenceService.applyVerdictGuard(answer, cited);

		assertTrue(result.matches("(?is)^\\s*no\\b.*"));
		assertTrue(!result.toLowerCase().contains("there are records of kidney issues"),
		    "the re-affirming clause that contradicts the No lead should be removed: " + result);
		assertEquals(citations(answer), citations(result));
	}

	/**
	 * Builds a {@link LlmInferenceService} with all collaborators stubbed and the LLM returning a
	 * fixed lab-only "Yes" citing one {@code obs} record, so the {@code search} path can be exercised
	 * without an OpenMRS context. {@code guardEnabled} drives the {@code resolveVerdictGuardEnabled}
	 * seam; grounding is forced off to skip its context read.
	 */
	private static LlmInferenceService stubbedServiceReturningLabOnlyYes(boolean guardEnabled) {
		java.util.List<RecordMapping> mappings = Arrays.asList(new RecordMapping(1, "obs", "uuid-1", null));
		PatientChart chart = new PatientChart("records", mappings);

		LlmInferenceService service = new LlmInferenceService() {

			@Override
			protected boolean resolveVerdictGuardEnabled() {
				return guardEnabled;
			}

			@Override
			protected boolean resolveGroundingEnabled() {
				return false;
			}
		};
		service.setChartBuildingStrategy(new ChartBuildingStrategy() {

			@Override
			PatientChart buildChart(Patient patient, String question) {
				return chart;
			}
		});
		service.setDrugReferenceInjector(new org.openmrs.module.chartsearchai.reference.DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question) {
				return chart;
			}
		});
		service.setDrugSafetyValidator(new org.openmrs.module.chartsearchai.reference.DrugSafetyValidator() {

			@Override
			public java.util.List<org.openmrs.module.chartsearchai.reference.SafetyWarning> validate(
					String answer, String question, Patient patient) {
				return java.util.Collections.emptyList();
			}
		});
		service.setLlmProvider(new LlmProvider() {

			@Override
			public LlmResponse search(String numberedRecords, java.util.List<Integer> focusIndices,
					String question) {
				return new LlmResponse("Yes — kidney function labs are recorded: creatinine 78.7 [1].",
						Arrays.asList(1));
			}
		});
		return service;
	}

	/**
	 * Wiring test: proves the production {@code search} path actually applies the guard (the
	 * pure-function tests prove the transform; this proves it is invoked). The lab-only "Yes" must
	 * come back as a NO-family verdict, with the evidence citation preserved.
	 */
	@Test
	public void search_appliesVerdictGuardToLabOnlyYesAnswer() {
		ChartAnswer answer = stubbedServiceReturningLabOnlyYes(true).search(new Patient(), "any kidney issues");

		assertTrue(answer.getAnswer().matches("(?is)^\\s*no\\b.*"),
		    "search must apply the guard and lead with No: " + answer.getAnswer());
		assertTrue(answer.getAnswer().contains("[1]"), "the evidence citation must be preserved");
	}

	/** With the GP off, the guard is skipped and the raw model verdict is returned unchanged. */
	@Test
	public void search_leavesAnswerUnchangedWhenGuardDisabled() {
		ChartAnswer answer = stubbedServiceReturningLabOnlyYes(false).search(new Patient(), "any kidney issues");

		assertTrue(answer.getAnswer().matches("(?is)^\\s*yes\\b.*"),
		    "with the guard disabled the raw Yes must be preserved: " + answer.getAnswer());
	}
}
