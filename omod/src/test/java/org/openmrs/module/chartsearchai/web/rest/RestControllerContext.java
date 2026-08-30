/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.web.rest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ServiceContext;
import org.openmrs.api.context.UserContext;

/**
 * The static OpenMRS context the BLOCKING {@code /search} handler needs, installed and torn down as
 * one unit. Driving that handler needs more than the SSE path does: a privileged {@link UserContext},
 * plus a {@link PatientService} and an {@link AdministrationService} in the {@link ServiceContext}.
 *
 * <p>Shared rather than copied per test class, for the reason {@link StubAuditLogService}'s javadoc
 * gives about itself: a copy per file lets two files quietly pin different answers to the same
 * question. Here that question is <em>what an unconfigured installation replies to a global-property
 * read</em> — which is the premise of the tests that use this, so a stale copy would keep passing
 * while describing an install nobody has.
 *
 * <p>Restoring matters as much as installing: surefire runs this module in a single reused JVM with
 * no configured {@code runOrder}, so a leaked service silently alters whichever classes happen to
 * run afterwards. What it would cost here is specific — several classes in this package
 * ({@code ChartSearchAiStreamEventOrderTest}, {@code ChartSearchAiReferenceGroupingTest},
 * {@code ChartSearchAiReferenceProvenanceTest}, and the contextless tests of
 * {@code ChartSearchAiReferenceGroundingWithholdingTest}) drive {@code streamAnswer} with NO
 * context at all, and on the REQUEST thread that is the only thing enforcing its "free of
 * {@code Context} reads" contract: a re-added global-property read throws because nothing is
 * installed. A leaked stub answers instead of throwing, and they go green over exactly the drift
 * issue #178 removed. That last class installs this fixture for its one blocking-endpoint test and
 * restores it in a {@code finally} rather than an {@code @AfterEach}, precisely so its own SSE
 * tests keep running contextless.
 *
 * <p>"On the REQUEST thread" is a limit rather than a hedge. It does not reach the SSE keep-alive's
 * own thread, whose two catches swallow whatever a {@code Context} read throws there. Measured
 * 2026-08-19: a {@code Context.getAuthenticatedUser()} placed AFTER the write in
 * {@code SseKeepAlive.write} leaves the whole omod suite green and is caught only by
 * {@code ChartSearchAiStreamingTest}'s scan for {@code Context.} inside that class, while the same
 * read placed BEFORE the write reddens six behavioural cases instead, because then no comment is ever
 * written at all and six cases there assert that one was. So on that thread the source scan is the
 * enforcement and this absence is not.
 *
 * <p>{@link #restore()} cannot simply put back "nothing", because {@code ServiceContext} has no
 * removal API and its {@code setService} returns silently when handed a null. So when there was no
 * prior service, restore installs a {@link #refusing} stand-in whose every method throws — which is
 * what an absent service behaves like, and keeps that contract enforced rather than merely claimed.
 */
final class RestControllerContext {

	/** The uuid {@link #patient()} carries and {@link #install()} teaches PatientService to resolve. */
	static final String PATIENT_UUID = "uuid-7";

	private PatientService priorPatientService;

	private AdministrationService priorAdministrationService;

	/** The fixture patient: id 7, {@link #PATIENT_UUID}. */
	static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(7);
		p.setUuid(PATIENT_UUID);
		return p;
	}

	/** The authenticated user the handler audits against. */
	static User user() {
		return new User(3);
	}

	/** Installs the context, remembering what was there so {@link #restore()} can put it back. */
	/**
	 * The {@code /search} request body the controller tests post — this fixture's own patient uuid and
	 * a question. Here rather than copied per test file for the reason {@code StubAuditLogService}'s
	 * javadoc gives about itself: both of its inputs already belong to this class, so a per-file copy
	 * is a second place that has to change when either does.
	 */
	static java.util.Map<String, String> searchBody(String question) {
		java.util.Map<String, String> body = new java.util.HashMap<String, String>();
		body.put("patient", PATIENT_UUID);
		body.put("question", question);
		return body;
	}

	void install() {
		priorPatientService = currentService(PatientService.class);
		priorAdministrationService = currentService(AdministrationService.class);
		ServiceContext.getInstance().setPatientService(patientServiceReturning(patient()));
		ServiceContext.getInstance().setAdministrationService(administrationServiceWithNoOverrides());

		Context.setUserContext(new UserContext(null) {

			@Override
			public boolean hasPrivilege(String privilege) {
				return true;
			}

			@Override
			public User getAuthenticatedUser() {
				return user();
			}

			@Override
			public boolean isAuthenticated() {
				return true;
			}
		});
	}

	/**
	 * Puts back every service this fixture replaced, and clears the user context. Where there was no
	 * prior service — the usual case, since these are the only classes in the package that install
	 * any — a {@link #refusing} stand-in goes back instead of null, because null would leave this
	 * fixture's own stubs in place for the rest of the JVM. See the class javadoc for what that
	 * costs.
	 */
	void restore() {
		ServiceContext.getInstance().setPatientService(
				priorPatientService != null ? priorPatientService : refusing(PatientService.class));
		ServiceContext.getInstance().setAdministrationService(
				priorAdministrationService != null ? priorAdministrationService
						: refusing(AdministrationService.class));
		Context.clearUserContext();
	}

	/**
	 * A stand-in for "no service is installed": every method throws. {@code ServiceContext} has no
	 * way to un-set a service, so this is how the absence is put back — and it has to behave like an
	 * absence, not like an empty implementation, or a test that reads a service it never installed
	 * passes on a null instead of failing.
	 */
	private static <T> T refusing(final Class<T> type) {
		return proxy(type, new InvocationHandler() {

			@Override
			public Object invoke(Object p, Method method, Object[] args) {
				throw new IllegalStateException("No " + type.getSimpleName()
						+ " is installed. A test that reaches one must install it itself — this "
						+ "stand-in exists so that reaching for it fails here rather than silently "
						+ "answering with another test's fixture.");
			}
		});
	}

	/** Whatever service was installed before this fixture, or null if none/unavailable. */
	private static <T> T currentService(Class<T> type) {
		try {
			return type.cast(ServiceContext.getInstance().getService(type));
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	/** Answers {@code getPatientByUuid} for the fixture patient; every other call returns null. */
	private static PatientService patientServiceReturning(final Patient patient) {
		return proxy(PatientService.class, new InvocationHandler() {

			@Override
			public Object invoke(Object p, Method method, Object[] args) {
				if ("getPatientByUuid".equals(method.getName())
						&& args != null && PATIENT_UUID.equals(args[0])) {
					return patient;
				}
				return null;
			}
		});
	}

	/**
	 * Answers global-property reads the way an unconfigured installation does: the two-arg form
	 * returns the caller's own default, and the one-arg form returns null so the rate limiter falls
	 * back to its compiled default — which the stub audit log's zero recent-query count then passes.
	 *
	 * <p>Only the ONE-arg form is reached on the paths these classes drive: it is the rate limiter's
	 * read, and the null keeps it on its compiled default. The two-arg branch is a leftover of what
	 * this fixture had to answer BEFORE issue #178 — the controller's preFilter read, which the fix
	 * deleted — and is kept because a handler that starts reading a global property again should get
	 * an unconfigured install's answer rather than an NPE. Nothing in
	 * {@code ChartSearchAiAuditSearchModeTest} depends on it: every mode it asserts comes from the
	 * answer the stub service returns, which is the point of that change.
	 */
	private static AdministrationService administrationServiceWithNoOverrides() {
		return proxy(AdministrationService.class, new InvocationHandler() {

			@Override
			public Object invoke(Object p, Method method, Object[] args) {
				if ("getGlobalProperty".equals(method.getName()) && args != null && args.length == 2) {
					return args[1];
				}
				return null;
			}
		});
	}

	private static <T> T proxy(Class<T> type, InvocationHandler handler) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
	}
}
