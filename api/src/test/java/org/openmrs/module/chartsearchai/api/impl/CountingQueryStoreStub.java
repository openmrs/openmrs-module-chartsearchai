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

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.backend.PatientChartRead;
import org.openmrs.module.querystore.backend.WriteResult;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Shared counting {@link QueryStoreService} double for the chart-builder tests
 * ({@code QueryStoreChartBuilderTest}, {@code QueryStoreChartBuilderScopedTest}) — one
 * implementation of the interface's read surface, so an interface change is absorbed once and
 * the two files cannot quietly pin different builder contracts. Same consolidation rationale as
 * {@code TestDatasetHelper}.
 *
 * <p><strong>Why a dynamic proxy and not {@code implements QueryStoreService}</strong> (#165):
 * retrieval belongs to openmrs-module-querystore (#51) and its API is expected to keep moving.
 * A class that implements the interface must override every abstract method it declares, so each
 * method querystore gained stopped chartsearchai's tests <em>compiling</em> — and only for whoever
 * had built querystore locally, since CI resolves an older remote snapshot. The error named this
 * test class rather than the dependency that changed. A proxy is bound to the interface at
 * runtime, so it names no method set at compile time and interface growth cannot break the build.
 *
 * <p>An abstract adapter with no-op defaults — the other obvious shape — does not fix this:
 * a concrete subclass must still override every abstract method inherited from anywhere, so the
 * identical "is not abstract and does not override abstract method" error just moves to the
 * subclass. That shape only helps when upstream adds methods as {@code default}, which is not
 * what happened.
 *
 * <p><strong>Tolerating growth must not mean answering silently.</strong> Every method this
 * double does not deliberately serve is refused with an {@link UnsupportedOperationException}
 * naming it. A default-returning double is a worse failure than a compile break: a test would
 * pass while exercising nothing, and nobody would be told. Note the direction of the safety —
 * refusing loudly can only fail a test that has started depending on a new querystore call,
 * which is a decision a human should make; returning an empty list where the real service
 * returns data would silently weaken every downstream assertion.
 *
 * <p>Growth is absorbed; a counted method being <em>removed or resignatured</em> is not, and
 * deliberately so — the counts those tests assert would no longer mean anything. That case fails
 * when the double is constructed, with a message naming querystore, which is where the cause is.
 */
final class CountingQueryStoreStub {

	/** Resolved per instance rather than in a static initialiser on purpose: a failed static
	 *  initialiser reports the real cause once and then {@code NoClassDefFoundError} for every
	 *  later test in the class, which buries exactly the message #165 wants surfaced. */
	private final Method searchByPatientMethod =
			interfaceMethod("searchByPatient", String.class, String.class, int.class);

	private final Method getPatientChartMethod = interfaceMethod("getPatientChart", String.class);

	private final Method getPatientChartReadMethod = interfaceMethod("getPatientChartRead", String.class);

	private final Method getContextSliceMethod = interfaceMethod("getContextSlice", String.class,
			String.class, org.openmrs.module.querystore.model.ContextSliceRequest.class);

	private final Method onStartupMethod = interfaceMethod("onStartup");

	private final Method onShutdownMethod = interfaceMethod("onShutdown");

	int searchByPatientCalls = 0;

	int getPatientChartCalls = 0;

	int lastSearchTopK = -1;

	String lastSearchQuery;

	List<QueryDocument> stubHits = new ArrayList<QueryDocument>();

	List<QueryDocument> stubChart = new ArrayList<QueryDocument>();

	boolean throwOnSearch = false;

	boolean chartTruncated = false;

	/** One proxy per double, so its identity and hash are stable across {@link #asService()} calls. */
	private final QueryStoreService service = (QueryStoreService) Proxy.newProxyInstance(
			CountingQueryStoreStub.class.getClassLoader(),
			new Class<?>[] { QueryStoreService.class }, this::answer);

	/** Aggregate counter — pre-focus-hint tests assert on total querystore calls
	 *  without caring which method. */
	int getCallCount() {
		return searchByPatientCalls + getPatientChartCalls;
	}

	/** The double as the interface the builder consumes. Counters stay readable on the stub. */
	QueryStoreService asService() {
		return service;
	}

	/**
	 * Matches on the resolved {@link Method}, not on its name: an overload of a counted method
	 * ({@code searchByPatient} gaining a filter argument, say) is a method this double has never
	 * been taught, and name matching would answer it with the wrong semantics instead of refusing.
	 */
	private Object answer(Object proxy, Method method, Object[] arguments) {
		if (searchByPatientMethod.equals(method)) {
			return searchByPatient((String) arguments[0], (String) arguments[1],
					((Integer) arguments[2]).intValue());
		}
		if (getPatientChartMethod.equals(method)) {
			return getPatientChart((String) arguments[0]);
		}
		if (getPatientChartReadMethod.equals(method)) {
			return getPatientChartRead((String) arguments[0]);
		}
		if (getContextSliceMethod.equals(method)) {
			return getContextSlice((String) arguments[0], (String) arguments[1],
					(org.openmrs.module.querystore.model.ContextSliceRequest) arguments[2]);
		}
		if (onStartupMethod.equals(method) || onShutdownMethod.equals(method)) {
			return null;
		}
		if (Object.class.equals(method.getDeclaringClass())) {
			return answerObjectMethod(proxy, method, arguments);
		}
		throw refusal(method);
	}

	private List<QueryDocument> searchByPatient(String patientUuid, String question, int topK) {
		searchByPatientCalls++;
		lastSearchTopK = topK;
		lastSearchQuery = question;
		if (throwOnSearch) {
			throw new RuntimeException("simulated similarity RPC failure");
		}
		return stubHits;
	}

	private List<QueryDocument> getPatientChart(String patientUuid) {
		getPatientChartCalls++;
		return stubChart;
	}

	/**
	 * {@code toString}, {@code hashCode} and {@code equals} route through the handler too, and a
	 * double that explodes when it is logged or compared fails a long way from its cause. Identity
	 * semantics: a double has no value to compare, and hashCode must agree with equals.
	 */
	private Object answerObjectMethod(Object proxy, Method method, Object[] arguments) {
		if ("toString".equals(method.getName())) {
			return "CountingQueryStoreStub(searchByPatientCalls=" + searchByPatientCalls
					+ ", getPatientChartCalls=" + getPatientChartCalls + ")";
		}
		if ("hashCode".equals(method.getName())) {
			return Integer.valueOf(System.identityHashCode(proxy));
		}
		if ("equals".equals(method.getName())) {
			return Boolean.valueOf(proxy == arguments[0]);
		}
		throw refusal(method);
	}

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
	public org.openmrs.module.querystore.model.ContextSlice getContextSlice(String patientUuid,
			String question, org.openmrs.module.querystore.model.ContextSliceRequest request) {
		getContextSliceCalls++;
		lastSliceQuestion = question;
		lastSliceRequest = request;
		org.openmrs.module.querystore.api.impl.QueryStoreServiceImpl real =
				new org.openmrs.module.querystore.api.impl.QueryStoreServiceImpl() {

					@Override
					public PatientChartRead getPatientChartRead(String ignoredPatientUuid) {
						return new PatientChartRead(stubChart, chartTruncated);
					}
				};
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

	public List<QueryDocument> search(String question, int topK) {
		throw new UnsupportedOperationException("not used by chartsearchai");
	}

	private static UnsupportedOperationException refusal(Method method) {
		return new UnsupportedOperationException("CountingQueryStoreStub does not answer "
				+ method.getDeclaringClass().getSimpleName() + "." + method.getName()
				+ " — chartsearchai did not call it when this double was written. If it does now, "
				+ "teach the double about it, and decide whether it should be counted, rather than "
				+ "letting a default answer stand in.");
	}

	private static Method interfaceMethod(String name, Class<?>... parameterTypes) {
		try {
			return QueryStoreService.class.getMethod(name, parameterTypes);
		}
		catch (NoSuchMethodException e) {
			throw new IllegalStateException("QueryStoreService no longer declares " + name
					+ " with this signature, so the call counts the chart-builder tests assert are "
					+ "no longer meaningful — update this double against the current querystore API",
					e);
		}
	}
}
