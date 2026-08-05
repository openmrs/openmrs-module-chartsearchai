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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ServiceContext;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Wire contract of {@code GET /chartsearchai/drugreferencestatus} — the endpoint issue #149 adds so
 * that "which drug-reference dataset is this module actually using?" can be answered from the
 * running module instead of from a log line that, because the load is lazy, may belong to an earlier
 * load or an earlier process.
 *
 * <p>It is pinned here rather than only live because the endpoint contributes one field of its own
 * ({@code enabled}) that no API-level test can see, and because its privilege gate is the only thing
 * standing between an unprivileged caller and both a filesystem path and a 19 MB parse. The
 * controller is driven as a POJO with its {@link DrugReferenceService} set through the same
 * package-private test seam the file already uses for its three other autowired collaborators.
 */
public class ChartSearchAiDrugReferenceStatusTest {

	/** Every key the endpoint documents, in the order it serializes them. */
	private static final List<String> DOCUMENTED_FIELDS = Arrays.asList("enabled", "loaded", "inert",
			"entryCount", "sourceFormat", "configuredSourceFormat", "configuredDataFilePath", "origin");

	private AdministrationService priorAdministrationService;

	private boolean administrationServiceReplaced;

	@AfterEach
	public void restoreContext() {
		if (administrationServiceReplaced) {
			ServiceContext.getInstance().setAdministrationService(priorAdministrationService);
			administrationServiceReplaced = false;
		}
		Context.clearUserContext();
	}

	/**
	 * A reader that answers every global-property read with the caller's own default — an
	 * installation that has configured nothing. {@code drugReference.enabled} then reads
	 * {@code false}, so the feature is off and nothing is loaded: the shape an operator sees before
	 * configuring anything. Installed rather than left to ambient state so the expected values do not
	 * depend on what an earlier test class in this JVM left in the {@link ServiceContext}.
	 */
	@Test
	public void drugReferenceStatus_reportsEveryDocumentedFieldForADefaultInstallation() {
		grantPrivileges(true);
		installAdministrationService(new HashMap<String, String>());

		Map<?, ?> body = statusBody(controllerWith(new DrugReferenceService()));

		assertEquals(DOCUMENTED_FIELDS, new ArrayList<Object>(body.keySet()),
				"the endpoint's fields are what an operator (and any source-flip check) reads; a "
						+ "renamed or dropped key leaves that check silently reading null");
		assertEquals(Boolean.FALSE, body.get("enabled"));
		assertEquals(Boolean.FALSE, body.get("loaded"), "a disabled feature must report no load");
		assertEquals(Boolean.FALSE, body.get("inert"),
				"'nothing configured at all' is not 'a configured source yielded nothing'");
		assertEquals(Integer.valueOf(0), body.get("entryCount"));
	}

	/**
	 * {@code enabled} is a LIVE read of the master switch, while every other field describes the load
	 * that is in force. They are therefore allowed to disagree, and the endpoint must keep them
	 * separate: flipping the switch off after a load leaves the loaded entries in memory, and
	 * reporting {@code loaded:false} there would claim the safety layer had no data when it has.
	 *
	 * <p>This is also the only automated coverage that the GET <em>performs</em> the lazy load: the
	 * service handed in has loaded nothing, and the first call comes back {@code loaded:true}.
	 */
	@Test
	public void drugReferenceStatus_reportsTheLiveSwitchAndTheRetainedLoadSeparately() {
		grantPrivileges(true);
		Map<String, String> globalProperties = new HashMap<String, String>();
		globalProperties.put(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		installAdministrationService(globalProperties);
		ChartSearchAiRestController controller = controllerWith(new DrugReferenceService());

		Map<?, ?> enabledBody = statusBody(controller);
		assertEquals(Boolean.TRUE, enabledBody.get("enabled"));
		assertEquals(Boolean.TRUE, enabledBody.get("loaded"),
				"reading the status must perform the lazy load, not report 'not yet'");
		assertEquals(Boolean.FALSE, enabledBody.get("inert"));
		assertTrue(((Integer) enabledBody.get("entryCount")).intValue() > 0,
				"the bundled curated dataset loads when no dataFilePath is configured");
		assertTrue(String.valueOf(enabledBody.get("origin")).startsWith("classpath:"),
				"origin must name the BUNDLED dataset when no operator file was read, since a "
						+ "non-zero count alone cannot distinguish the two. Origin was: "
						+ enabledBody.get("origin"));

		globalProperties.put(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "false");
		Map<?, ?> switchedOffBody = statusBody(controller);

		assertEquals(Boolean.FALSE, switchedOffBody.get("enabled"), "the switch is read live");
		assertEquals(Boolean.TRUE, switchedOffBody.get("loaded"),
				"the load that happened is still reported after the switch is turned off — the "
						+ "entries are still in memory");
		assertEquals(enabledBody.get("entryCount"), switchedOffBody.get("entryCount"),
				"and the retained outcome does not change, so repeated calls do no further work");
	}

	/**
	 * The status names the dataset file and the drug-reference global properties, and reading it can
	 * perform a 19 MB parse. Both are reasons the gate must actually run: this call must be refused,
	 * not answered.
	 *
	 * <p>Discriminating because the service IS set here — with the {@code requirePrivilege} gate
	 * removed the call would succeed and return 200 rather than fail.
	 */
	@Test
	public void drugReferenceStatus_refusesACallerWithoutTheGlobalPropertyPrivilege() {
		grantPrivileges(false);
		ChartSearchAiRestController controller = controllerWith(new DrugReferenceService());

		assertThrows(RuntimeException.class, () -> controller.drugReferenceStatus(),
				"an unprivileged caller must be refused before the drug-reference service is consulted");
	}

	private static ChartSearchAiRestController controllerWith(DrugReferenceService service) {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		controller.setDrugReferenceService(service);
		return controller;
	}

	private static Map<?, ?> statusBody(ChartSearchAiRestController controller) {
		ResponseEntity<Object> response = controller.drugReferenceStatus();
		assertEquals(HttpStatus.OK, response.getStatusCode());
		Map<?, ?> body = (Map<?, ?>) response.getBody();
		assertNotNull(body, "the status endpoint returned no body");
		return body;
	}

	private static void grantPrivileges(final boolean granted) {
		Context.setUserContext(new UserContext(null) {

			@Override
			public boolean hasPrivilege(String privilege) {
				return granted;
			}
		});
	}

	/**
	 * Installs a global-property reader backed by {@code globalProperties}, so a test can change a
	 * switch between two calls the way an operator does. Whatever was installed before is restored in
	 * {@link #restoreContext()}: surefire runs this module in one reused JVM, so a leaked service
	 * would silently alter the other test classes in this package rather than failing here.
	 */
	private void installAdministrationService(final Map<String, String> globalProperties) {
		priorAdministrationService = currentAdministrationService();
		administrationServiceReplaced = true;
		AdministrationService reader = AdministrationService.class
				.cast(Proxy.newProxyInstance(AdministrationService.class.getClassLoader(),
						new Class<?>[] { AdministrationService.class }, new InvocationHandler() {

							@Override
							public Object invoke(Object proxy, Method method, Object[] args) {
								if ("getGlobalProperty".equals(method.getName()) && args != null
										&& args.length >= 1) {
									String value = globalProperties.get(args[0]);
									if (value != null) {
										return value;
									}
									return args.length == 2 ? args[1] : null;
								}
								return null;
							}
						}));
		ServiceContext.getInstance().setAdministrationService(reader);
	}

	/** Whatever reader was installed before this test, or null if none/unavailable. */
	private static AdministrationService currentAdministrationService() {
		try {
			return AdministrationService.class
					.cast(ServiceContext.getInstance().getService(AdministrationService.class));
		}
		catch (RuntimeException e) {
			return null;
		}
	}
}
