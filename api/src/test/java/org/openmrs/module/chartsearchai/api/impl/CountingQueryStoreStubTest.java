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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Specification for {@link CountingQueryStoreStub} — the shared chart-builder double.
 *
 * <p>Issue #165: the double used to {@code implement QueryStoreService} directly, so every
 * method querystore added to that interface broke chartsearchai's <em>test compile</em>. That
 * is a build property, and this test cannot assert it: proving it needs a second querystore jar
 * whose interface carries a method this source does not name, which no single build can supply.
 * The PR demonstrates it by building against such a jar instead. What IS asserted here is the
 * mechanism the demonstration rests on, plus the two properties that make interface tolerance
 * safe rather than merely convenient:
 *
 * <ul>
 *   <li>{@link #stub_shouldNotImplementTheQuerystoreInterfaceAtCompileTime()} — the regression
 *       guard. Re-adding {@code implements QueryStoreService} reinstates the defect and nothing
 *       else in the suite would notice.</li>
 *   <li>{@link #asService_shouldAnswerOnlyTheMethodsItDeclares_andThrowLoudlyForEveryOther()} —
 *       tolerating interface growth must not mean answering silently. A double that returns a
 *       plausible empty value for a method it has never heard of lets a test pass while
 *       exercising nothing, which is a worse failure than the compile break.</li>
 *   <li>{@link #asService_shouldRouteCountedCallsToTheCounters()} and
 *       {@link #asService_shouldPropagateTheSimulatedRpcFailureUnwrapped()} — the counting and
 *       failure-simulation the double exists to do still work through the indirection.</li>
 * </ul>
 */
public class CountingQueryStoreStubTest {

	/**
	 * The {@code QueryStoreService} methods the double answers on purpose — the two it counts
	 * plus the {@code OpenmrsService} lifecycle pair, which a double has no work to do for.
	 *
	 * <p>Deliberately spelled out here rather than read from the double: this list is the
	 * specification. Asserting against a constant the double itself exports would pass no matter
	 * which methods it decided to answer, which is exactly the change that needs a human to look
	 * at it.
	 */
	private static final Set<String> ANSWERED_ON_PURPOSE = new TreeSet<>(
			Arrays.asList("searchByPatient", "getPatientChart", "onStartup", "onShutdown"));

	@Test
	public void stub_shouldNotImplementTheQuerystoreInterfaceAtCompileTime() {
		// #165's regression guard, and the closest an in-process test gets to the build property:
		// a class that names the interface's method set in its own source is a class that stops
		// compiling when the set changes. Whether the double survives a GROWN interface can only
		// be shown by compiling against one; that it cannot be broken THIS way can be shown here.
		assertFalse(QueryStoreService.class.isAssignableFrom(CountingQueryStoreStub.class),
				"CountingQueryStoreStub must not implement QueryStoreService at compile time — a "
						+ "direct implementor must override every abstract method the interface "
						+ "declares, so #165 (a querystore API addition breaking chartsearchai's "
						+ "test compile) returns the moment this holds again");
	}

	@Test
	public void asService_shouldAnswerOnlyTheMethodsItDeclares_andThrowLoudlyForEveryOther() throws Exception {
		CountingQueryStoreStub stub = new CountingQueryStoreStub();
		QueryStoreService service = stub.asService();

		Set<String> answeredWithoutThrowing = new TreeSet<>();
		for (Method method : QueryStoreService.class.getMethods()) {
			if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) {
				continue;
			}
			try {
				method.invoke(service, zeroArguments(method));
				answeredWithoutThrowing.add(method.getName());
			}
			catch (InvocationTargetException e) {
				if (!(e.getCause() instanceof UnsupportedOperationException)) {
					fail("an unanswered QueryStoreService method must refuse with "
							+ "UnsupportedOperationException, but " + method.getName() + " threw "
							+ e.getCause());
				}
				assertTrue(e.getCause().getMessage() != null
						&& e.getCause().getMessage().contains(method.getName()),
						"the refusal must name the method so the failure points at the querystore "
								+ "API that moved rather than at this test class; message was: "
								+ e.getCause().getMessage());
			}
		}

		assertEquals(ANSWERED_ON_PURPOSE, answeredWithoutThrowing,
				"the double must answer exactly the methods it deliberately serves and refuse every "
						+ "other one. A method that returns a plausible default instead — an "
						+ "interface `default` the double inherits, or a no-op nobody chose — lets a "
						+ "test pass while exercising nothing");
	}

	@Test
	public void asService_shouldRouteCountedCallsToTheCounters() {
		CountingQueryStoreStub stub = new CountingQueryStoreStub();
		stub.stubHits.add(doc("Obs", "obs-1"));
		stub.stubChart.add(doc("Condition", "cond-1"));
		QueryStoreService service = stub.asService();

		assertSame(stub.stubHits, service.searchByPatient("patient-uuid", "blood pressure?", 7),
				"searchByPatient must return the stub's hit list itself — a copy would let a test "
						+ "mutate stubHits after the call and see stale content");
		assertSame(stub.stubChart, service.getPatientChart("patient-uuid"),
				"getPatientChart must return the stub's chart list itself");

		assertEquals(1, stub.searchByPatientCalls, "searchByPatient must increment its counter");
		assertEquals(1, stub.getPatientChartCalls, "getPatientChart must increment its counter");
		assertEquals(2, stub.getCallCount(), "the aggregate counter must see both calls");
		assertEquals(7, stub.lastSearchTopK, "the requested topK must be recorded");
		assertEquals("blood pressure?", stub.lastSearchQuery, "the question must be recorded");
	}

	@Test
	public void asService_shouldPropagateTheSimulatedRpcFailureUnwrapped() {
		// throwOnSearch exists so the builder's degradation paths can be exercised. The builder
		// catches RuntimeException broadly, so a wrapped throw would still degrade and those tests
		// would still pass — but the double would no longer resemble a querystore RPC failure,
		// and the next caller to inspect the cause would be misled.
		CountingQueryStoreStub stub = new CountingQueryStoreStub();
		stub.throwOnSearch = true;
		QueryStoreService service = stub.asService();

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> service.searchByPatient("patient-uuid", "any allergies?", 5));

		assertFalse(thrown instanceof UndeclaredThrowableException,
				"the simulated RPC failure must reach the caller as itself, not wrapped");
		assertEquals("simulated similarity RPC failure", thrown.getMessage(),
				"the simulated failure must arrive unchanged");
		assertEquals(1, stub.searchByPatientCalls,
				"the call must be counted before it fails — the builder tests assert it was reached");
	}

	@Test
	public void asService_shouldReturnAStableProxyThatSupportsObjectMethods() {
		// Object's methods route through the same handler as the interface's, so an over-eager
		// refusal arm would make the double explode when it is logged, compared, or put in a
		// collection — surprising failures a long way from the cause.
		CountingQueryStoreStub stub = new CountingQueryStoreStub();
		QueryStoreService service = stub.asService();

		assertSame(service, stub.asService(),
				"asService must hand out one stable instance — two proxies over the same counters "
						+ "would compare unequal and hash differently");
		assertNotNull(service.toString(), "toString must work; the double gets logged");
		assertEquals(service.hashCode(), stub.asService().hashCode(), "hashCode must be stable");
		assertTrue(service.equals(stub.asService()), "equals must be reflexive");
		assertFalse(service.equals(new Object()), "equals must reject a foreign object");
	}

	private static QueryDocument doc(String resourceType, String resourceUuid) {
		QueryDocument document = new QueryDocument();
		document.setResourceType(resourceType);
		document.setResourceUuid(resourceUuid);
		return document;
	}

	/**
	 * Zero/null arguments for a reflective sweep of the interface.
	 *
	 * <p>These are also the inputs under which an inherited {@code default} is likeliest to
	 * return a plausible empty result rather than fail — {@code bulkIndex(null)} short-circuits
	 * to an empty {@code BulkWriteResult} upstream — so they are the values that actually
	 * discriminate a refusal from a silent answer. Every primitive is handled so the sweep keeps
	 * working as the interface grows, which is the point of the change under test.
	 */
	private static Object[] zeroArguments(Method method) {
		Class<?>[] parameterTypes = method.getParameterTypes();
		Object[] arguments = new Object[parameterTypes.length];
		for (int i = 0; i < parameterTypes.length; i++) {
			arguments[i] = zeroValue(parameterTypes[i]);
		}
		return arguments;
	}

	private static Object zeroValue(Class<?> type) {
		if (!type.isPrimitive()) {
			return null;
		}
		if (type == boolean.class) {
			return Boolean.FALSE;
		}
		if (type == char.class) {
			return Character.valueOf('\0');
		}
		if (type == byte.class) {
			return Byte.valueOf((byte) 0);
		}
		if (type == short.class) {
			return Short.valueOf((short) 0);
		}
		if (type == int.class) {
			return Integer.valueOf(0);
		}
		if (type == long.class) {
			return Long.valueOf(0L);
		}
		if (type == float.class) {
			return Float.valueOf(0f);
		}
		if (type == double.class) {
			return Double.valueOf(0d);
		}
		throw new IllegalStateException("no zero value for parameter type " + type);
	}
}
