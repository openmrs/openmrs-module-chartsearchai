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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Locks the WIRING that both {@link LlmInferenceService#search} and
 * {@link LlmInferenceService#searchStreaming} feed the answer prose into
 * {@code extractCitedReferences}, so an inline {@code [N]} marker the LLM
 * omitted from its structured {@code citations} array still resolves to a
 * clickable reference.
 *
 * <p>The reconciliation logic itself is unit-tested in
 * {@code LlmInferenceServiceTest}; this test guards the two call sites. The
 * streaming endpoint ({@code /search/stream}) is the primary production path,
 * so a refactor that passed {@code null} for the answer there would silently
 * drop the fix on the path users actually hit, and the logic-only unit test
 * would still pass. The stub LLM reproduces the
 * exact demo failure: it cites {@code [8]} inline but lists only {@code [9]}
 * in its structured array.</p>
 */
public class LlmInferenceServiceCitationWiringTest {

	private TestableService service;

	@BeforeEach
	public void setUp() {
		service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(new StubProvider());
		// their searchStreaming injects drug-reference records unconditionally; pass through
		service.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart inject(
					org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart chart,
					org.openmrs.Patient patient, String question) {
				return chart;
			}
		});
		service.setDrugSafetyValidator(new org.openmrs.module.chartsearchai.reference.DrugSafetyValidator() {

			@Override
			public java.util.List<org.openmrs.module.chartsearchai.reference.SafetyWarning> validate(
					String answer, String question, org.openmrs.Patient patient) {
				return java.util.Collections.emptyList();
			}
		});
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	@Test
	public void search_shouldResolveInlineOnlyCitationToReference() {
		ChartAnswer answer = service.search(patient(), "any infections?");
		assertReferencesInclude(answer, 8);
	}

	@Test
	public void searchStreaming_shouldResolveInlineOnlyCitationToReference() {
		ChartAnswer answer = service.searchStreaming(patient(), "any infections?",
				token -> { });
		assertReferencesInclude(answer, 8);
	}

	private static void assertReferencesInclude(ChartAnswer answer, int index) {
		boolean found = false;
		for (RecordReference ref : answer.getReferences()) {
			if (ref.getIndex() == index) {
				found = true;
			}
		}
		assertTrue(found, "Inline-only citation [" + index
				+ "] must resolve to a reference; got " + answer.getReferences());
	}

	/** Subclass that no-ops the Context-backed resolvers so no OpenMRS runtime is needed. */
	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}

		@Override
		protected boolean resolveQueryStoreEnabled() {
			return false;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			List<RecordMapping> mappings = Arrays.asList(
					new RecordMapping(8, "condition", "00000000-0000-0000-0000-000000000008", null),
					new RecordMapping(9, "obs", "00000000-0000-0000-0000-000000000009", null));
			return new PatientChart("8. Tuberculosis\n9. CD4 988.0", mappings,
					Collections.<Integer>emptyList());
		}
	}

	/** Cites [8] inline but lists only [9] in the structured citations array. */
	private static final class StubProvider extends LlmProvider {

		private static LlmResponse canned() {
			return new LlmResponse("Active Tuberculosis [8]. CD4 988.0 [9].", Arrays.asList(9));
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices,
				String question) {
			return canned();
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer) {
			return canned();
		}
	}
}
