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
 * <p>Restoring matters as much as installing: surefire runs this module in a single reused JVM, so a
 * leaked service or user context silently alters the other classes in this package rather than
 * failing where it was left. {@link #restore()} puts back whatever was installed before, including
 * nothing.
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

	/** Puts back every service this fixture replaced, and clears the user context. */
	void restore() {
		ServiceContext.getInstance().setPatientService(priorPatientService);
		ServiceContext.getInstance().setAdministrationService(priorAdministrationService);
		Context.clearUserContext();
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
	 * <p>This is the configuration on which issue #178's two-way branch resolved to
	 * {@code full-chart}, which is what a default install has and what made every row on one wrong —
	 * so {@code ChartSearchAiAuditSearchModeTest} depends on this answering as an unconfigured
	 * install and not merely as some install.
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
