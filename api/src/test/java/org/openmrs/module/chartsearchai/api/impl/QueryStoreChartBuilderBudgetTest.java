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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.InsufficientContextException;
import org.openmrs.module.chartsearchai.api.scope.QueryScopeContributor;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.querystore.QueryStoreConstants;
import org.openmrs.module.querystore.model.QueryDocument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code context.mandatory-overflow-abstains}: mandatory clinical evidence (demographics,
 * allergies, active conditions) is never droppable (ADR Decision 17), so when it alone exceeds
 * the model's input budget the turn must abstain with {@link InsufficientContextException}
 * rather than silently sending a truncated or oversized prompt. When mandatory content fits but
 * the full slice does not, non-mandatory records are trimmed (a ceiling, not a target) while
 * mandatory records and chart order are always preserved — mirrors med-agent-hub's
 * {@code select_context}. Uses a fake {@link TokenCounter} (word count as token count, matching
 * the fixture-driven hub test's own {@code ExactWordCounter}) — no live llama-server needed.
 */
public class QueryStoreChartBuilderBudgetTest {

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("patient-uuid-1");
		return p;
	}

	private static QueryDocument doc(String type, String uuid, String text, LocalDate date) {
		QueryDocument d = new QueryDocument();
		d.setResourceType(type);
		d.setResourceUuid(uuid);
		d.setText(text);
		d.setDate(date);
		return d;
	}

	private static String words(int count) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < count; i++) {
			sb.append("word ");
		}
		return sb.toString().trim();
	}

	/** Word-count-as-token-count, matching the fixture-driven Python/Java adapters already in
	 *  this codebase (test_dual_provider_conformance_adapter.py's _ExactWordCounter). */
	private static final class FakeTokenCounter implements TokenCounter {

		boolean available = true;

		int budget = 100;

		@Override
		public boolean isAvailable() {
			return available;
		}

		@Override
		public int count(String text) {
			return text == null || text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
		}

		@Override
		public int inputBudget() {
			return budget;
		}
	}

	private CountingQueryStoreStub queryStore;

	private TestableScopedBuilder builder;

	private FakeTokenCounter tokenCounter;

	@BeforeEach
	public void setUp() {
		queryStore = new CountingQueryStoreStub();
		builder = new TestableScopedBuilder(queryStore);
		builder.setChartSerializer(new PatientChartSerializer());
		tokenCounter = new FakeTokenCounter();
		builder.setTokenCounter(tokenCounter);
	}

	private static List<String> mappedUuids(PatientChart chart) {
		List<String> uuids = new ArrayList<String>();
		for (RecordMapping mapping : chart.getMappings()) {
			uuids.add(mapping.getResourceUuid());
		}
		return uuids;
	}

	@Test
	public void mandatoryOverflowAbstainsPerFixture() throws IOException {
		JsonNode fixtureCase = fixtureCase("context.mandatory-overflow-abstains");
		int budgetTokens = fixtureCase.get("budget_tokens").asInt();
		int mandatoryTokens = fixtureCase.get("mandatory_tokens").asInt();
		tokenCounter.budget = budgetTokens;

		queryStore.stubChart = new ArrayList<QueryDocument>(Collections.singletonList(
				condition("cond-active", words(mandatoryTokens), LocalDate.of(2026, 1, 1), "ACTIVE")));

		InsufficientContextException thrown = assertThrows(InsufficientContextException.class,
				() -> builder.buildScoped(patient(), "anything?"));

		assertTrue(thrown.getMandatoryRecordIds().contains("cond-active"),
				"exceeding mandatory record must be named; got " + thrown.getMandatoryRecordIds());
	}

	@Test
	public void mandatoryContentAloneFittingIsNeverAbstained() {
		tokenCounter.budget = 100;
		queryStore.stubChart = new ArrayList<QueryDocument>(Collections.singletonList(
				condition("cond-active", words(10), LocalDate.of(2026, 1, 1), "ACTIVE")));

		PatientChart chart = builder.buildScoped(patient(), "anything?");

		assertTrue(mappedUuids(chart).contains("cond-active"));
	}

	@Test
	public void excessOptionalContentIsTrimmedNotHardFailed() {
		tokenCounter.budget = 50;
		List<QueryDocument> chart = new ArrayList<QueryDocument>();
		chart.add(condition("cond-active", words(5), LocalDate.of(2026, 6, 1), "ACTIVE"));
		// Ten similarity-only records, 20 words each: fits none of them within budget alongside
		// the mandatory record, individually or in combination once several are already selected.
		for (int i = 0; i < 10; i++) {
			chart.add(doc("obs", "sim-" + i, words(20), LocalDate.of(2026, 5, 1).minusDays(i)));
		}
		queryStore.stubChart = new ArrayList<QueryDocument>(chart);
		queryStore.stubHits = new ArrayList<QueryDocument>(chart.subList(1, chart.size()));

		PatientChart result = builder.buildScoped(patient(), "shoes?");

		List<String> uuids = mappedUuids(result);
		assertTrue(uuids.contains("cond-active"), "mandatory record must always survive trimming");
		assertFalse(uuids.containsAll(similarityIds(10)),
				"excess optional content must be trimmed, not all included; got " + uuids);
	}

	@Test
	public void everythingFittingIsUnchangedFromTodaysBehavior() {
		tokenCounter.budget = 10_000;
		queryStore.stubChart = new ArrayList<QueryDocument>(Collections.singletonList(
				condition("cond-active", words(5), LocalDate.of(2026, 1, 1), "ACTIVE")));

		PatientChart chart = builder.buildScoped(patient(), "anything?");

		assertTrue(mappedUuids(chart).contains("cond-active"));
	}

	@Test
	public void counterUnavailableSkipsEnforcementEntirely() {
		tokenCounter.available = false;
		tokenCounter.budget = 1;
		queryStore.stubChart = new ArrayList<QueryDocument>(Collections.singletonList(
				condition("cond-active", words(500), LocalDate.of(2026, 1, 1), "ACTIVE")));

		PatientChart chart = builder.buildScoped(patient(), "anything?");

		assertTrue(mappedUuids(chart).contains("cond-active"),
				"with no counter available, behavior must be unchanged from before this feature");
	}

	private static List<String> similarityIds(int count) {
		List<String> ids = new ArrayList<String>();
		for (int i = 0; i < count; i++) {
			ids.add("sim-" + i);
		}
		return ids;
	}

	private static QueryDocument condition(String uuid, String text, LocalDate date, String status) {
		QueryDocument d = doc("condition", uuid, text, date);
		d.putMetadata(QueryStoreConstants.FIELD_CLINICAL_STATUS, status);
		return d;
	}

	private static JsonNode fixtureCase(String id) throws IOException {
		try (java.io.InputStream is = QueryStoreChartBuilderBudgetTest.class
				.getResourceAsStream("/conformance/dual-provider-conformance.v1.json")) {
			JsonNode root = new ObjectMapper().readTree(is);
			for (JsonNode candidate : root.get("context_policy")) {
				if (id.equals(candidate.get("id").asText())) {
					return candidate;
				}
			}
			throw new IllegalArgumentException("No context_policy fixture case '" + id + "'");
		}
	}

	private static final class TestableScopedBuilder extends QueryStoreChartBuilder {

		private final org.openmrs.module.querystore.api.QueryStoreService stub;

		TestableScopedBuilder(org.openmrs.module.querystore.api.QueryStoreService stub) {
			this.stub = stub;
		}

		@Override
		protected List<QueryScopeContributor> resolveScopeContributors() {
			return new ArrayList<QueryScopeContributor>();
		}

		@Override
		protected org.openmrs.module.querystore.api.QueryStoreService resolveQueryStoreService() {
			return stub;
		}

		@Override
		protected int resolveQueryStoreTopK() {
			return 20;
		}

		@Override
		protected int resolveScopedRecencyAnchor() {
			return 0;
		}

		@Override
		protected boolean resolveUsePreFilter() {
			return false;
		}

		@Override
		protected boolean resolveDedupGroupLabels() {
			return false;
		}
	}
}
