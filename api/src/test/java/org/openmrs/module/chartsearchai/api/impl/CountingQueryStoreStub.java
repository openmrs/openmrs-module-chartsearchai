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

import java.util.ArrayList;
import java.util.List;

import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.backend.WriteResult;
import org.openmrs.module.querystore.backend.PatientChartRead;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Shared counting {@link QueryStoreService} stub for the chart-builder tests
 * ({@code QueryStoreChartBuilderTest}, {@code QueryStoreChartBuilderScopedTest}) — one
 * implementation of the interface's read surface plus write-method stubs, so an interface
 * change is absorbed once and the two files cannot quietly pin different builder contracts.
 * Same consolidation rationale as {@code TestDatasetHelper}.
 */
final class CountingQueryStoreStub implements QueryStoreService {

	int searchByPatientCalls = 0;

	int getPatientChartCalls = 0;

	int lastSearchTopK = -1;

	String lastSearchQuery;

	List<QueryDocument> stubHits = new ArrayList<QueryDocument>();

	List<QueryDocument> stubChart = new ArrayList<QueryDocument>();

	boolean throwOnSearch = false;

	boolean chartTruncated = false;

	/** Aggregate counter — pre-focus-hint tests assert on total querystore calls
	 *  without caring which method. */
	int getCallCount() {
		return searchByPatientCalls + getPatientChartCalls;
	}

	@Override
	public List<QueryDocument> searchByPatient(String patientUuid, String question, int topK) {
		searchByPatientCalls++;
		lastSearchTopK = topK;
		lastSearchQuery = question;
		if (throwOnSearch) {
			throw new RuntimeException("simulated similarity RPC failure");
		}
		return stubHits;
	}

	@Override
	public List<QueryDocument> getPatientChart(String patientUuid) {
		getPatientChartCalls++;
		return stubChart;
	}

	@Override
	public PatientChartRead getPatientChartRead(String patientUuid) {
		getPatientChartCalls++;
		return new PatientChartRead(stubChart, chartTruncated);
	}

	int getContextSliceCalls = 0;

	org.openmrs.module.querystore.model.ContextSliceRequest lastSliceRequest;

	String lastSliceQuestion;

	/**
	 * Delegates to the REAL {@code QueryStoreServiceImpl} slice policy over {@code stubChart} /
	 * {@code stubHits} — the builder tests then exercise querystore's actual shared selection
	 * (ADR Decision 17), not a re-implementation of it in test code. Counters and the captured
	 * request stay chartsearchai-side so tests can pin the caller's question interpretation.
	 */
	@Override
	public org.openmrs.module.querystore.model.ContextSlice getContextSlice(String patientUuid,
			String question, org.openmrs.module.querystore.model.ContextSliceRequest request) {
		getContextSliceCalls++;
		lastSliceQuestion = question;
		lastSliceRequest = request;
		org.openmrs.module.querystore.api.impl.QueryStoreServiceImpl real =
				new org.openmrs.module.querystore.api.impl.QueryStoreServiceImpl();
		real.setBackend(new BridgeBackend());
		org.openmrs.module.querystore.model.ContextSlice slice =
				real.getContextSlice(patientUuid, question, request);
		if (!chartTruncated) {
			return slice;
		}
		return new org.openmrs.module.querystore.model.ContextSlice(slice.getRecords(),
				slice.getChartSize(), true, slice.getEffectiveTypes(), slice.isTemporalApplied(),
				slice.getChartSnapshotId());
	}

	/** Serves {@code stubChart}/{@code stubHits} to the real slice impl, keeping the outer
	 *  similarity counters/captures coherent with the direct {@code searchByPatient} path. */
	private final class BridgeBackend implements org.openmrs.module.querystore.backend.BackendStore {

		@Override
		public boolean existsByPatient(String patientUuid) {
			return true;
		}

		@Override
		public List<QueryDocument> findAllByPatient(String patientUuid) {
			return stubChart;
		}

		@Override
		public org.openmrs.module.querystore.backend.SearchResult hybrid(
				org.openmrs.module.querystore.backend.SearchRequest req) {
			searchByPatientCalls++;
			lastSearchTopK = req.getLimit();
			lastSearchQuery = req.getQueryText();
			if (throwOnSearch) {
				throw new RuntimeException("simulated similarity RPC failure");
			}
			List<org.openmrs.module.querystore.backend.Hit> out =
					new ArrayList<org.openmrs.module.querystore.backend.Hit>();
			for (int i = 0; i < stubHits.size(); i++) {
				out.add(new org.openmrs.module.querystore.backend.Hit(stubHits.get(i), 1.0 - i * 0.1, i + 1));
			}
			return new org.openmrs.module.querystore.backend.SearchResult(out);
		}

		@Override
		public org.openmrs.module.querystore.backend.SearchResult bm25(
				org.openmrs.module.querystore.backend.SearchRequest req) {
			return hybrid(req);
		}

		@Override
		public org.openmrs.module.querystore.backend.SearchResult knn(
				org.openmrs.module.querystore.backend.SearchRequest req) {
			return org.openmrs.module.querystore.backend.SearchResult.empty();
		}

		@Override
		public void ensureSchema(String resourceType, org.openmrs.module.querystore.backend.SchemaSpec spec) {
		}

		@Override
		public void deleteSchema(String resourceType) {
		}

		@Override
		public WriteResult upsert(QueryDocument doc) {
			return WriteResult.success();
		}

		@Override
		public WriteResult delete(String resourceType, String resourceUuid) {
			return WriteResult.success();
		}

		@Override
		public org.openmrs.module.querystore.backend.BulkWriteResult bulkUpsert(List<QueryDocument> docs) {
			return new org.openmrs.module.querystore.backend.BulkWriteResult(docs.size(), docs.size(),
					java.util.Collections.<org.openmrs.module.querystore.backend.DocFailure> emptyList());
		}

		@Override
		public org.openmrs.module.querystore.backend.BulkWriteResult bulkDelete(String resourceType,
				List<String> uuids) {
			return new org.openmrs.module.querystore.backend.BulkWriteResult(0, 0,
					java.util.Collections.<org.openmrs.module.querystore.backend.DocFailure> emptyList());
		}

		@Override
		public org.openmrs.module.querystore.backend.BulkWriteResult bulkDeleteByPatient(String patientUuid) {
			return new org.openmrs.module.querystore.backend.BulkWriteResult(0, 0,
					java.util.Collections.<org.openmrs.module.querystore.backend.DocFailure> emptyList());
		}

		@Override
		public long countByType(String resourceType) {
			return stubChart.size();
		}

		@Override
		public org.openmrs.module.querystore.backend.BackendCapabilities capabilities() {
			return new org.openmrs.module.querystore.backend.BackendCapabilities(false, false, false, 1_000_000,
					java.util.EnumSet.allOf(org.openmrs.module.querystore.backend.Filter.Kind.class));
		}

		@Override
		public org.openmrs.module.querystore.backend.HealthStatus health() {
			return new org.openmrs.module.querystore.backend.HealthStatus(
					org.openmrs.module.querystore.backend.HealthStatus.State.HEALTHY, null);
		}
	}

	@Override
	public List<QueryDocument> search(String question, int topK) {
		throw new UnsupportedOperationException("not used by chartsearchai");
	}

	@Override
	public WriteResult index(QueryDocument doc) {
		throw new UnsupportedOperationException("not used by chartsearchai");
	}

	@Override
	public void delete(String resourceType, String resourceUuid) {
		throw new UnsupportedOperationException("not used by chartsearchai");
	}

	@Override
	public void bulkDeleteByPatient(String patientUuid) {
		throw new UnsupportedOperationException("not used by chartsearchai");
	}

	@Override
	public void onStartup() {
	}

	@Override
	public void onShutdown() {
	}
}
