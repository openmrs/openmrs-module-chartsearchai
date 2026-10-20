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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.api.conversation.ConversationService;
import org.openmrs.module.chartsearchai.api.provider.ProviderMode;
import org.openmrs.module.chartsearchai.model.ClinicalConversation;
import org.openmrs.web.test.jupiter.BaseModuleWebContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code ChartSearchAiRestController#resolveConversation} over the real {@link ConversationService} and its
 * Hibernate store: the REST-layer half of "changing providerId creates a new conversation" (the service-layer
 * half, {@code ConversationServiceImpl.openOrCreate}, is covered by
 * {@code ConversationServicePersistenceTest#reusesOnlyAnActiveConversationWithTheSameProviderAndMode}). A turn
 * submitted with a provider or mode different from its client-supplied session, or naming a session that is not
 * the caller's active one, must not be appended to that session.
 */
public class ResolveConversationTest extends BaseModuleWebContextSensitiveTest {

	@Autowired
	private ConversationService conversations;

	private ChartSearchAiRestController controller;

	private Patient patient;

	@BeforeEach
	public void setUp() {
		controller = new ChartSearchAiRestController();
		controller.setConversationService(conversations);
		patient = Context.getPatientService().getPatient(2);
	}

	@Test
	public void reusesTheClientSuppliedSessionWhenProviderAndModeMatch() {
		ClinicalConversation existing = conversations.startNew(patient, "bundled", ProviderMode.QUERY_SCOPED);

		ClinicalConversation resolved = controller.resolveConversation(patient, "bundled",
				ProviderMode.QUERY_SCOPED, existing.getUuid());

		assertEquals(existing.getUuid(), resolved.getUuid());
		assertEquals(ClinicalConversation.STATUS_ACTIVE, conversations.getByUuid(existing.getUuid()).getStatus());
	}

	@Test
	public void aProviderMismatchNeverReusesTheClientSuppliedSession() {
		// A session bound to the bundled provider must not receive a hub-answered turn.
		ClinicalConversation staleBundled = conversations.startNew(patient, "bundled", ProviderMode.QUERY_SCOPED);

		ClinicalConversation resolved = controller.resolveConversation(patient, "hub", ProviderMode.QUERY_SCOPED,
				staleBundled.getUuid());

		assertNotEquals(staleBundled.getUuid(), resolved.getUuid(),
				"a provider-mismatched session must never be reused for the new provider's turn");
		assertEquals("hub", resolved.getProviderId());
	}

	@Test
	public void aModeMismatchAlsoFallsThroughToOpenOrCreate() {
		ClinicalConversation queryScoped = conversations.startNew(patient, "bundled", ProviderMode.QUERY_SCOPED);

		ClinicalConversation resolved = controller.resolveConversation(patient, "bundled",
				ProviderMode.FULL_CHART_STABLE, queryScoped.getUuid());

		assertNotEquals(queryScoped.getUuid(), resolved.getUuid());
		assertEquals(ProviderMode.FULL_CHART_STABLE.getWireName(), resolved.getProviderMode());
	}

	@Test
	public void aClosedConversationIsNeverReusedEvenWithAMatchingProviderAndMode() {
		ClinicalConversation closed = conversations.startNew(patient, "bundled", ProviderMode.QUERY_SCOPED);
		ClinicalConversation current = conversations.startNew(patient, "bundled", ProviderMode.QUERY_SCOPED);
		assertEquals(ClinicalConversation.STATUS_CLOSED, conversations.getByUuid(closed.getUuid()).getStatus(),
				"precondition: starting a new conversation closed the earlier one");

		ClinicalConversation resolved = controller.resolveConversation(patient, "bundled",
				ProviderMode.QUERY_SCOPED, closed.getUuid());

		assertNotEquals(closed.getUuid(), resolved.getUuid());
		assertEquals(current.getUuid(), resolved.getUuid(), "the caller's active conversation is used instead");
	}

	@Test
	public void anUnknownConversationUuidFallsThroughToOpenOrCreate() {
		ClinicalConversation resolved = controller.resolveConversation(patient, "bundled",
				ProviderMode.QUERY_SCOPED, "does-not-exist");

		assertNotNull(resolved.getUuid());
		assertEquals(resolved.getUuid(), conversations.getLatestActiveConversation(patient).getUuid());
	}

	@Test
	public void aBlankConversationUuidGoesStraightToOpenOrCreate() {
		ClinicalConversation active = conversations.openOrCreate(patient, "bundled", ProviderMode.QUERY_SCOPED);

		ClinicalConversation resolved = controller.resolveConversation(patient, "bundled", ProviderMode.QUERY_SCOPED,
				null);

		assertEquals(active.getUuid(), resolved.getUuid());
	}

	@Test
	public void anotherUsersSessionIsNeverReused() {
		ClinicalConversation theirs = conversations.startNew(patient, "bundled", ProviderMode.QUERY_SCOPED);
		Context.flushSession();
		Context.getAdministrationService().executeSQL("update chartsearchai_conversation set user_id = 501 where uuid = '"
				+ theirs.getUuid() + "'", false);
		Context.flushSession();
		Context.clearSession();

		ClinicalConversation resolved = controller.resolveConversation(patient, "bundled",
				ProviderMode.QUERY_SCOPED, theirs.getUuid());

		assertNotEquals(theirs.getUuid(), resolved.getUuid());
		assertEquals(Context.getAuthenticatedUser(), resolved.getUser());
	}
}
