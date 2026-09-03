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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.openmrs.util.PrivilegeConstants;

/**
 * Verifies that the proxy privilege pattern used by the streaming endpoint
 * correctly grants data access in a background thread.
 */
public class StreamingContextPropagationTest extends BaseModuleContextSensitiveTest {

	@Test
	public void proxyPrivileges_shouldAllowDataAccessInBackgroundThread()
			throws Exception {
		final boolean[] couldGetPatient = { false };
		final CountDownLatch latch = new CountDownLatch(1);

		Thread bgThread = new Thread(new Runnable() {
			@Override
			public void run() {
				Context.openSession();
				try {
					Context.addProxyPrivilege(PrivilegeConstants.GET_PATIENTS);
					Patient patient = Context.getPatientService().getPatient(2);
					couldGetPatient[0] = (patient != null);
				}
				finally {
					Context.removeProxyPrivilege(PrivilegeConstants.GET_PATIENTS);
					Context.closeSession();
					latch.countDown();
				}
			}
		});
		bgThread.start();

		assertTrue(latch.await(5, TimeUnit.SECONDS),
				"Background thread should complete within 5 seconds");
		assertTrue(couldGetPatient[0],
				"Background thread with GET_PATIENTS proxy privilege should access patient data");
	}

	@Test
	public void backgroundThread_shouldNotAccessDataWithoutProxyPrivilege()
			throws Exception {
		final boolean[] gotAuthError = { false };
		final CountDownLatch latch = new CountDownLatch(1);

		Thread bgThread = new Thread(new Runnable() {
			@Override
			public void run() {
				Context.openSession();
				try {
					// No proxy privileges added — should fail
					Context.getPatientService().getPatient(2);
				}
				catch (org.openmrs.api.APIAuthenticationException e) {
					gotAuthError[0] = true;
				}
				finally {
					Context.closeSession();
					latch.countDown();
				}
			}
		});
		bgThread.start();

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertTrue(gotAuthError[0],
				"Background thread without proxy privileges must NOT access patient data");
	}

	@Test
	public void proxyPrivileges_shouldBeCleanedUpAfterUse() throws Exception {
		final boolean[] accessAfterRemoval = { false };
		final CountDownLatch latch = new CountDownLatch(1);

		Thread bgThread = new Thread(new Runnable() {
			@Override
			public void run() {
				Context.openSession();
				try {
					Context.addProxyPrivilege(PrivilegeConstants.GET_PATIENTS);
					Context.getPatientService().getPatient(2); // should succeed
					Context.removeProxyPrivilege(PrivilegeConstants.GET_PATIENTS);

					// After removal, access should fail
					try {
						Context.getPatientService().getPatient(2);
						accessAfterRemoval[0] = true; // should NOT reach here
					}
					catch (org.openmrs.api.APIAuthenticationException e) {
						accessAfterRemoval[0] = false;
					}
				}
				finally {
					Context.closeSession();
					latch.countDown();
				}
			}
		});
		bgThread.start();

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertTrue(!accessAfterRemoval[0],
				"Proxy privileges must not persist after removal");
	}
}
