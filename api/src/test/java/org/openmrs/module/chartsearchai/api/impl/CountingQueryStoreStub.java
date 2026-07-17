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
