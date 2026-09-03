/*
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
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
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/336">#336</a> — how
 * bounded the interaction list is travelling from the validator that measured it to the answer the
 * REST layer serializes.
 *
 * <p><b>What these cases pin, stated plainly, because a reader will otherwise expect more of them.</b>
 * They pin the WIRING and not the counts: that the sink {@code LlmInferenceService} creates is the
 * one it hands to {@code validate}, that whatever the validator states into it reaches the answer,
 * and that an answer whose producer stated nothing carries nothing. The counts themselves are a fact
 * about the arms that state them — the two pairwise ones and, since issue #356, the drug-in-play arm
 * where neither of those stated one — and are pinned through the real {@code validate} over the
 * real DDInter excerpt by {@code PairChipExtentContextTest}; a stub here could only restate them.
 *
 * <p>The validator seam is therefore deliberately a stub that states a fixed extent — it is
 * answering for the arms, not imitating them, and the arms have their own tests. What no stub could
 * fake is the sink's IDENTITY: the case below asserts the extent on the answer is the very object
 * the validator was handed, so a second sink resolved anywhere on the path would redden rather than
 * pass on equal numbers.
 *
 * <p>The streaming path's EARLY-DONE answer states nothing, and that is not an omission: it is
 * constructed before validation runs and carries an empty chip list for the same reason. A
 * completeness statement beside no chips would assert a screen that has not happened.
 */
public class LlmInferenceServicePairChipExtentTest {

	/** The extent the stubbed validator states — the ticket's own measured numbers. */
	private static final int FOUND = 18;

	private static final int REPORTED = 10;

	/** Every sink the stubbed validator was handed, in call order. */
	private List<PairChipExtent.Sink> sinks;

	private TestableService service;

	@BeforeEach
	public void setUp() {
		sinks = new ArrayList<PairChipExtent.Sink>();
		service = new TestableService();
		service.setChartBuildingStrategy(new StubStrategy());
		service.setLlmProvider(new StubProvider());
		// Pass-through injector, the seam every other test in this package installs: this class is
		// about the extent's transport and not about injection.
		service.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question) {
				return chart;
			}
		});
		service.setDrugSafetyValidator(new DrugSafetyValidator() {

			// The overload production actually calls once a caller publishes the extent (issue #336).
			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
				sinks.add(pairExtentSink);
				if (pairExtentSink != null) {
					pairExtentSink.record(FOUND, REPORTED);
				}
				return Collections.emptyList();
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
	public void search_handsTheValidatorASinkAndCarriesWhatItStates() {
		ChartAnswer answer = service.search(patient(), "screen her medications for interactions");

		assertEquals(1, sinks.size(), "the validator must be called through the sink-carrying overload");
		assertNotNull(sinks.get(0), "and it must be given a sink, not null");
		assertNotNull(answer.getPairChipExtent(), "what the validator stated must reach the answer");
		assertEquals(FOUND, answer.getPairChipExtent().getFound());
		assertEquals(REPORTED, answer.getPairChipExtent().getReported());
		assertSame(sinks.get(0).stated(), answer.getPairChipExtent(),
				"the answer must carry the very statement this pass's sink heard; equal numbers from a "
						+ "second sink would pass an equality check and leave the wiring unpinned");
	}

	@Test
	public void searchStreaming_carriesTheStatementOnTheFinalAnswerAndNoneOnTheEarlyOne() {
		final List<ChartAnswer> ungrounded = new ArrayList<ChartAnswer>();

		ChartAnswer answer = service.searchStreaming(patient(), "screen her medications for interactions",
				token -> { }, reasoning -> { }, citations -> { }, ungrounded::add);

		assertEquals(1, ungrounded.size(), "the early-done consumer must have fired");
		assertNotNull(answer.getPairChipExtent(), "the streaming path must carry it too");
		assertEquals(FOUND, answer.getPairChipExtent().getFound());
		assertEquals(REPORTED, answer.getPairChipExtent().getReported());
		assertSame(sinks.get(0).stated(), answer.getPairChipExtent());
		assertNull(ungrounded.get(0).getPairChipExtent(),
				"the early-done answer runs before validation and carries no chips, so it may not "
						+ "assert anything about a screen that has not happened");
		assertEquals(0, ungrounded.get(0).getSafetyWarnings().size(),
				"precondition: it is the empty-chip answer, which is why it may state no extent");
	}

	@Test
	public void anAnswerBuiltWithoutAProducerStatesNothingRatherThanACompleteScreen() {
		// Absence is "nobody measured", never "the screen found everything" — the distinction
		// getReferenceSlice() makes for its own field, and the one PairChipExtent's javadoc enumerates.
		// NOT getSearchMode(), which does the opposite: it collapses absence into a SEARCH_MODE_UNKNOWN
		// sentinel and is never null, which is the fail-open this type refuses.
		ChartAnswer unstated = new ChartAnswer("A [1].", Collections.<RecordReference> emptyList());

		assertNull(unstated.getPairChipExtent());
	}

	/** Context-free service: GP-backed resolvers overridden so no OpenMRS Context is needed. */
	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
			return false;
		}

		@Override
		protected boolean resolveProgressiveReasoningEnabled() {
			return false;
		}

		@Override
		protected boolean resolveQueryScopedMode() {
			return false;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		@Override
		PatientChart buildChart(Patient patient, String question) {
			List<RecordMapping> mappings = Arrays.asList(
					new RecordMapping(1, "obs", "00000000-0000-0000-0000-000000000001", null, "BP 120/80"));
			return new PatientChart("[1] BP 120/80", mappings, Collections.<Integer> emptyList());
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	private static final class StubProvider extends LlmProvider {

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question) {
			return new LlmResponse("No interaction is expected.", Collections.<Integer> emptyList());
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope) {
			return new LlmResponse("No interaction is expected.", Collections.<Integer> emptyList());
		}
	}
}
