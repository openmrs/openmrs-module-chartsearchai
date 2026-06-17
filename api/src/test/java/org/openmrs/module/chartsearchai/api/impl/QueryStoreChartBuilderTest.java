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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.backend.WriteResult;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Pure unit tests for {@link QueryStoreChartBuilder}.
 *
 * <p>Focus-hint mode contract: the builder always calls
 * {@code QueryStoreService.getPatientChart} so the chart bytes are a function of the
 * patient only (the property llama-server's KV-cache reuse needs). When
 * {@code preFilter=true} with a non-blank question, it additionally calls
 * {@code QueryStoreService.searchByPatient} to get a relevance ranking; the matching
 * record UUIDs flow through {@code PatientChart.getFocusIndices()} for rendering as a
 * small "Records ranked by similarity to the query: ..." line in the LLM prompt. These tests
 * pin the dispatch correctness, the input-guard contract (null patient / null uuid),
 * and the record-conversion loop.
 */
public class QueryStoreChartBuilderTest {

	private static Patient patient(int id) {
		Patient p = new Patient();
		p.setPatientId(id);
		p.setUuid("uuid-" + id);
		return p;
	}

	private CountingQueryStore queryStore;
	private TestableQueryStoreChartBuilder builder;

	@BeforeEach
	public void setUp() {
		queryStore = new CountingQueryStore();
		builder = new TestableQueryStoreChartBuilder(queryStore);
		builder.setChartSerializer(new PatientChartSerializer());
	}

	@Test
	public void build_shouldStillCallGetPatientChartAndSkipSearch_whenQuestionIsBlank() {
		// Focus-hint contract: the chart bytes are a function of the patient only, so a blank
		// question still produces the full chart — that's exactly what warmup needs (warmup
		// calls buildChart(patient, "") to prime the prefix llama-server will reuse on real
		// queries). The blank-question short-circuit is now scoped to the focus-hint side
		// only: searchByPatient with an empty query is spurious (no ranking signal) so it's
		// skipped, but getPatientChart still runs.
		PatientChart chart = builder.build(patient(1), "   ");

		assertEquals(0, chart.getMappings().size(),
				"empty stubChart produces an empty PatientChart; the test's claim is about dispatch");
		assertEquals(1, queryStore.getPatientChartCalls,
				"blank question must still reach getPatientChart — warmup depends on this");
		assertEquals(0, queryStore.searchByPatientCalls,
				"blank question must NOT reach searchByPatient — no ranking signal to spend RPC on");
		assertEquals(0, chart.getFocusIndices().size(),
				"blank question produces no focus hint");
	}

	@Test
	public void build_shouldStillCallGetPatientChartAndSkipSearch_whenQuestionIsNull() {
		// Mirrors the blank-question test: null question is treated the same as blank for the
		// focus-hint short-circuit, but the chart fetch still runs.
		PatientChart chart = builder.build(patient(1), null);

		assertEquals(0, chart.getMappings().size(),
				"null question must not NPE — caller contract is empty chart from empty stub");
		assertEquals(1, queryStore.getPatientChartCalls,
				"null question must still reach getPatientChart");
		assertEquals(0, queryStore.searchByPatientCalls,
				"null question must NOT reach searchByPatient");
	}

	@Test
	public void build_shouldReturnEmptyChart_whenPatientIsNull() {
		PatientChart chart = builder.build(null, "any allergies?");

		assertEquals(0, chart.getMappings().size(),
				"null patient must not NPE — caller contract is empty chart");
		assertEquals(0, queryStore.getCallCount());
	}

	@Test
	public void build_shouldReturnEmptyChart_whenBothPatientAndQuestionAreNull() {
		// Belt-and-braces: locks the guard against a future refactor that swaps the order
		// of clauses in the OR-chain and accidentally dereferences a null patient before
		// short-circuiting (e.g. `question == null || patient.getUuid() == null` would NPE).
		PatientChart chart = builder.build(null, null);

		assertEquals(0, chart.getMappings().size(),
				"both-null must short-circuit cleanly without NPE — the guard's OR-chain "
				+ "must check patient==null BEFORE dereferencing patient.getUuid()");
		assertEquals(0, queryStore.getCallCount());
	}

	@Test
	public void build_shouldReturnEmptyChartAndSkipQueryStore_whenPatientUuidIsNull() {
		// OpenMRS BaseOpenmrsObject auto-generates a UUID in the no-arg constructor, so
		// new Patient() already has a non-null uuid. Explicit setUuid(null) is what reaches
		// the guard — defensive coverage for deserialization paths or buggy callers.
		Patient unidentifiedPatient = new Patient();
		unidentifiedPatient.setPatientId(99);
		unidentifiedPatient.setUuid(null);

		PatientChart chart = builder.build(unidentifiedPatient, "any allergies?");

		assertEquals(0, chart.getMappings().size(),
				"patient without uuid must short-circuit — passing a null uuid to querystore "
				+ "would either NPE or return spurious results depending on the backend");
		assertEquals(0, queryStore.getCallCount());
	}

	@Test
	public void build_shouldCallGetPatientChartOnly_whenPreFilterIsFalse() {
		// preFilter=false is the plain full-chart path: no relevance ranking needed.
		builder.usePreFilter = false;

		builder.build(patient(1), "any allergies?");

		assertEquals(1, queryStore.getPatientChartCalls,
				"preFilter=false must call getPatientChart for the full chart");
		assertEquals(0, queryStore.searchByPatientCalls,
				"preFilter=false must NOT call searchByPatient — no focus hint requested");
	}

	@Test
	public void build_shouldCallBothGetPatientChartAndSearchByPatient_whenPreFilterIsTrue() {
		// Focus-hint contract: preFilter=true calls BOTH endpoints — getPatientChart for the
		// stable chart bytes (KV-cache-friendly) and searchByPatient for the relevance signal
		// that becomes the focus hint. A regression that called only searchByPatient (the old
		// pre-focus-hint contract) would silently break warmup and shred the KV cache.
		builder.usePreFilter = true;

		builder.build(patient(1), "any allergies?");

		assertEquals(1, queryStore.getPatientChartCalls,
				"preFilter=true must call getPatientChart for the stable chart bytes");
		assertEquals(1, queryStore.searchByPatientCalls,
				"preFilter=true must also call searchByPatient for the focus-hint ranking");
	}

	@Test
	public void build_shouldExposeFocusIndicesMatchingSearchHits_whenPreFilterIsTrue() {
		// End-to-end focus-hint wiring: searchByPatient hits → resource UUIDs collected →
		// matched against chart record UUIDs → exposed as 1-based PatientChart focus indices
		// for LlmProvider.buildUserMessage to render as the trailing focus-hint line.
		QueryDocument chartDoc1 = new QueryDocument();
		chartDoc1.setResourceType("Condition");
		chartDoc1.setResourceUuid("cond-uuid-1");
		chartDoc1.setText("Hypertension");
		QueryDocument chartDoc2 = new QueryDocument();
		chartDoc2.setResourceType("Obs");
		chartDoc2.setResourceUuid("obs-uuid-2");
		chartDoc2.setText("BP 140/90");
		QueryDocument chartDoc3 = new QueryDocument();
		chartDoc3.setResourceType("Obs");
		chartDoc3.setResourceUuid("obs-uuid-3");
		chartDoc3.setText("Heart rate 72");
		queryStore.stubChart = new ArrayList<>();
		queryStore.stubChart.add(chartDoc1);
		queryStore.stubChart.add(chartDoc2);
		queryStore.stubChart.add(chartDoc3);

		QueryDocument focusHit = new QueryDocument();
		focusHit.setResourceType("Obs");
		focusHit.setResourceUuid("obs-uuid-2");
		queryStore.stubHits = new ArrayList<>();
		queryStore.stubHits.add(focusHit);

		builder.usePreFilter = true;
		PatientChart chart = builder.build(patient(1), "what is the blood pressure?");

		assertEquals(3, chart.getMappings().size(),
				"chart must include all 3 records from getPatientChart — full-chart bytes for cache reuse");
		assertEquals(1, chart.getFocusIndices().size(),
				"exactly the searchByPatient hit must be flagged as focus");
		assertEquals(Integer.valueOf(2), chart.getFocusIndices().get(0),
				"the focus index is the 1-based position of obs-uuid-2 in the chart");
	}

	@Test
	public void build_shouldExposeEmptyFocus_whenPreFilterFalseEvenWithSearchHitsStub() {
		// preFilter=false must skip the searchByPatient call entirely. The stub holds hits
		// but the builder must not consult them — focus indices on the chart must be empty.
		QueryDocument focusHit = new QueryDocument();
		focusHit.setResourceType("Obs");
		focusHit.setResourceUuid("obs-uuid-1");
		queryStore.stubHits.add(focusHit);
		QueryDocument chartDoc = new QueryDocument();
		chartDoc.setResourceType("Obs");
		chartDoc.setResourceUuid("obs-uuid-1");
		chartDoc.setText("BP");
		queryStore.stubChart.add(chartDoc);

		builder.usePreFilter = false;
		PatientChart chart = builder.build(patient(1), "blood pressure");

		assertEquals(0, chart.getFocusIndices().size(),
				"preFilter=false must not consult searchByPatient — focus indices must be empty "
				+ "even when the stub would have returned hits");
	}

	@Test
	public void build_shouldStillSkipNullPatient_whenPreFilterIsFalse() {
		// Hard-error guards apply in both modes — null patient can't reach getPatientChart
		// without NPEing on getUuid(). Mirrors the preFilter=true null-patient guard test;
		// this is the symmetric coverage for the new branch.
		builder.usePreFilter = false;

		PatientChart chart = builder.build(null, "any allergies?");

		assertEquals(0, chart.getMappings().size());
		assertEquals(0, queryStore.getPatientChartCalls,
				"null patient must short-circuit in preFilter=false mode too");
	}

	@Test
	public void build_shouldStillSkipNullUuid_whenPreFilterIsFalse() {
		// Symmetric coverage for the null-uuid guard in the preFilter=false branch. A future
		// refactor that pulled the null-uuid guard inside the per-branch arms (e.g. as part of
		// "tidy this up") would only be caught by the preFilter=true null-uuid test —
		// build_shouldReturnEmptyChartAndSkipQueryStore_whenPatientUuidIsNull above. Locks
		// both branches against that refactor.
		builder.usePreFilter = false;
		Patient unidentifiedPatient = new Patient();
		unidentifiedPatient.setPatientId(99);
		unidentifiedPatient.setUuid(null);

		PatientChart chart = builder.build(unidentifiedPatient, "any allergies?");

		assertEquals(0, chart.getMappings().size(),
				"null uuid must short-circuit in preFilter=false mode — passing null to "
				+ "getPatientChart would either NPE or surface backend-specific behaviour");
		assertEquals(0, queryStore.getPatientChartCalls,
				"null uuid must not reach getPatientChart");
	}

	@Test
	public void build_shouldStillReturnFullChart_whenSearchByPatientThrows() {
		// Focus-hint failure must not block the LLM call — the full chart is already fetched
		// at the point searchByPatient runs, and is usable on its own (equivalent to
		// fullChart mode). A regression that propagated the throw would turn a focus-hint
		// outage into a chart outage, which is much worse.
		QueryDocument chartDoc = new QueryDocument();
		chartDoc.setResourceType("Obs");
		chartDoc.setResourceUuid("obs-uuid-1");
		chartDoc.setText("BP");
		queryStore.stubChart.add(chartDoc);
		queryStore.throwOnSearch = true;

		builder.usePreFilter = true;
		PatientChart chart = builder.build(patient(1), "any allergies?");

		assertEquals(1, chart.getMappings().size(),
				"full chart must still be returned when the focus-hint RPC throws");
		assertEquals(0, chart.getFocusIndices().size(),
				"focus indices must be empty when searchByPatient fails — no hint rendered");
	}

	@Test
	public void build_shouldRenderPanelMembership_whenChartDocCarriesObsGroupMetadata() {
		// End-to-end panel-grouping wiring (issue #51, ADR Decision 6): querystore indexes each
		// group-obs member as an atomic doc carrying obs_group_uuid + obs_group_concept_name in
		// METADATA (never in the stored text, to keep citations clean). The consumer is responsible
		// for clustering. This locks the full production path — getPatientChart docs ->
		// toSerializedRecords (must read the metadata) -> PatientChartSerializer (must render the
		// panel label) — so the LLM can see which atomic obs belong to the same panel. Before this
		// fix toSerializedRecords dropped doc.getMetadata() entirely and the membership was invisible.
		QueryDocument sodium = new QueryDocument();
		sodium.setResourceType("Obs");
		sodium.setResourceUuid("obs-na");
		sodium.setText("Sodium: 140 mmol/L");
		sodium.putMetadata("obs_group_uuid", "grp-bmp-1");
		sodium.putMetadata("obs_group_concept_name", "Basic metabolic panel");
		QueryDocument potassium = new QueryDocument();
		potassium.setResourceType("Obs");
		potassium.setResourceUuid("obs-k");
		potassium.setText("Potassium: 4.0 mmol/L");
		potassium.putMetadata("obs_group_uuid", "grp-bmp-1");
		potassium.putMetadata("obs_group_concept_name", "Basic metabolic panel");
		QueryDocument standalone = new QueryDocument();
		standalone.setResourceType("Obs");
		standalone.setResourceUuid("obs-temp");
		standalone.setText("Temperature: 36.7 C");
		// no obs_group metadata -> not a panel member
		queryStore.stubChart = new ArrayList<>();
		queryStore.stubChart.add(sodium);
		queryStore.stubChart.add(potassium);
		queryStore.stubChart.add(standalone);

		builder.usePreFilter = false;
		PatientChart chart = builder.build(patient(1), "what were the metabolic panel results?");

		String text = chart.getText();
		assertTrue(text.contains("Sodium: 140 mmol/L (part of: Basic metabolic panel)"),
				"a group-obs member must render its group label so the LLM can cluster it; chart was:\n" + text);
		assertTrue(text.contains("Potassium: 4.0 mmol/L (part of: Basic metabolic panel)"),
				"every member of the group must carry the same label; chart was:\n" + text);
		assertFalse(text.contains("Temperature: 36.7 C (part of:"),
				"a non-grouped obs must NOT get a group label; chart was:\n" + text);
	}

	@Test
	public void build_shouldNotRenderPanelLabel_whenGroupUuidPresentButConceptNameAbsent() {
		// ObsRecordSerializer.putGroupFields always sets obs_group_uuid for a member but only sets
		// obs_group_concept_name when the parent concept has a non-empty preferred name. With a uuid
		// but no name there is no human/LLM-meaningful label to show, so the suffix must be omitted
		// rather than leaking a raw uuid or an empty "(part of panel: )" into the prompt.
		QueryDocument member = new QueryDocument();
		member.setResourceType("Obs");
		member.setResourceUuid("obs-x");
		member.setText("Glucose: 90 mg/dL");
		member.putMetadata("obs_group_uuid", "grp-unnamed");
		queryStore.stubChart = new ArrayList<>();
		queryStore.stubChart.add(member);

		builder.usePreFilter = false;
		PatientChart chart = builder.build(patient(1), "glucose?");

		assertTrue(chart.getText().contains("Glucose: 90 mg/dL"),
				"the obs body must still render; chart was:\n" + chart.getText());
		assertFalse(chart.getText().contains("part of:"),
				"no concept name -> no group suffix; chart was:\n" + chart.getText());
	}

	@Test
	public void build_shouldNotRenderPanelLabel_whenConceptNameIsBlank() {
		// Defensive: metadataString() must treat a blank/whitespace metadata value as absent, so a
		// malformed upstream obs_group_concept_name can't leak an empty "(part of: )" into the prompt.
		// querystore itself never writes a blank name (putGroupFields guards on !name.isEmpty()), so
		// this exercises the guard on a value only a malformed producer could emit.
		QueryDocument member = new QueryDocument();
		member.setResourceType("Obs");
		member.setResourceUuid("obs-y");
		member.setText("Creatinine: 1.0 mg/dL");
		member.putMetadata("obs_group_uuid", "grp-blank-name");
		member.putMetadata("obs_group_concept_name", "   ");
		queryStore.stubChart = new ArrayList<>();
		queryStore.stubChart.add(member);

		builder.usePreFilter = false;
		PatientChart chart = builder.build(patient(1), "creatinine?");

		assertTrue(chart.getText().contains("Creatinine: 1.0 mg/dL"),
				"the obs body must still render; chart was:\n" + chart.getText());
		assertFalse(chart.getText().contains("part of:"),
				"a blank concept name must be treated as absent — no group suffix; chart was:\n" + chart.getText());
	}

	@Test
	public void build_shouldSkipNullAndMalformedHitsAndKeepValidOnes() {
		// Locks the record-conversion loop: a refactor that breaks SerializedRecord field
		// order, drops the null-text guard, or removes a skip clause would silently corrupt
		// the chart sent to the LLM without any test failing.
		QueryDocument valid1 = new QueryDocument();
		valid1.setResourceType("Condition");
		valid1.setResourceUuid("cond-1");
		valid1.setText("Condition: Hypertension. Status: ACTIVE");
		QueryDocument valid2 = new QueryDocument();
		valid2.setResourceType("Obs");
		valid2.setResourceUuid("obs-1");
		valid2.setText("Systolic blood pressure: 140 mmHg");
		QueryDocument malformedNoType = new QueryDocument();
		malformedNoType.setResourceUuid("dropped-no-type");
		// resourceType deliberately left null
		malformedNoType.setText("should be dropped");
		QueryDocument malformedNoUuid = new QueryDocument();
		malformedNoUuid.setResourceType("Condition");
		// resourceUuid deliberately left null
		malformedNoUuid.setText("should be dropped");
		List<QueryDocument> mixed = new ArrayList<>();
		mixed.add(valid1);
		mixed.add(null);                // null doc → skip
		mixed.add(malformedNoType);     // missing type → skip
		mixed.add(valid2);
		mixed.add(malformedNoUuid);     // missing uuid → skip
		// Focus-hint mode: the chart records come from getPatientChart (stubChart), not
		// from searchByPatient (stubHits). The malformed-record loop is what converts
		// stubChart docs into SerializedRecords.
		queryStore.stubChart = mixed;

		PatientChart chart = builder.build(patient(1), "any allergies?");

		assertEquals(2, chart.getMappings().size(),
				"only the two well-formed QueryDocuments must reach the chart — null/missing-type/"
				+ "missing-uuid docs are logged at WARN and dropped from the conversion loop");
		// Pin the resource uuids that survived so a future serializer change can't silently
		// reshuffle which doc maps to which mapping index.
		assertEquals("cond-1", chart.getMappings().get(0).getResourceUuid(),
				"first surviving record should be valid1 (Condition cond-1) — order preserved from the stub");
		assertEquals("obs-1", chart.getMappings().get(1).getResourceUuid(),
				"second surviving record should be valid2 (Obs obs-1)");
	}

	/**
	 * Subclass that bypasses {@code Context.getService} so this test runs without
	 * a live OpenMRS context. The {@code resolve*} overrides are the seam — every
	 * other code path goes through the real builder.
	 *
	 * <p>Defaults {@code resolveUsePreFilter()} to {@code true} so the legacy tests
	 * (written when {@code searchByPatient} was the only dispatch target) continue to
	 * exercise the preFilter path without explicit per-test wiring. Tests that need
	 * the full-chart branch flip it via {@link #usePreFilter}.
	 */
	private static final class TestableQueryStoreChartBuilder extends QueryStoreChartBuilder {

		private final QueryStoreService stub;

		boolean usePreFilter = true;

		TestableQueryStoreChartBuilder(QueryStoreService stub) {
			this.stub = stub;
		}

		@Override
		protected QueryStoreService resolveQueryStoreService() {
			return stub;
		}

		@Override
		protected int resolveQueryStoreTopK() {
			return 10;
		}

		@Override
		protected boolean resolveUsePreFilter() {
			return usePreFilter;
		}
	}

	private static final class CountingQueryStore implements QueryStoreService {

		int searchByPatientCalls = 0;

		int getPatientChartCalls = 0;

		List<QueryDocument> stubHits = new ArrayList<QueryDocument>();

		List<QueryDocument> stubChart = new ArrayList<QueryDocument>();

		boolean throwOnSearch = false;

		/** Backwards-compatible aggregate counter — pre-focus-hint tests assert on
		 *  total querystore calls without caring which method. */
		int getCallCount() {
			return searchByPatientCalls + getPatientChartCalls;
		}

		@Override
		public List<QueryDocument> searchByPatient(String patientUuid, String question, int topK) {
			searchByPatientCalls++;
			if (throwOnSearch) {
				throw new RuntimeException("simulated focus-hint RPC failure");
			}
			return stubHits;
		}

		@Override
		public List<QueryDocument> getPatientChart(String patientUuid) {
			getPatientChartCalls++;
			return stubChart;
		}

		@Override
		public List<QueryDocument> search(String question, int topK) {
			throw new UnsupportedOperationException("not used by chartsearchai");
		}

		@Override
		public WriteResult index(QueryDocument doc) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void delete(String resourceType, String resourceUuid) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void bulkDeleteByPatient(String patientUuid) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void onStartup() {
		}

		@Override
		public void onShutdown() {
		}
	}
}
