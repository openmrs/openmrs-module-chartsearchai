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
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.conversation.ConversationService;
import org.openmrs.module.chartsearchai.api.conversation.PriorClinicalTurn;
import org.openmrs.module.chartsearchai.api.provider.ProviderMode;
import org.openmrs.module.chartsearchai.api.provider.TurnResult;
import org.openmrs.module.chartsearchai.model.ClinicalConversation;
import org.openmrs.module.chartsearchai.model.ClinicalConversationTurn;

/**
 * {@code ChartSearchAiRestController#resolveConversation} — the REST-layer half of "changing
 * providerId creates a new conversation" (the service-layer half,
 * {@code ConversationServiceImpl.openOrCreate}, is covered by
 * {@code ConversationServicePersistenceTest#reusesOnlyAnActiveConversationWithTheSameProviderAndMode}).
 * Live-observed gap this closes: a client that submits a turn with a provider different from the
 * one bound to its client-supplied session must never have that turn silently written into the
 * old conversation — the caller (openOrCreate, delegated to on any mismatch) closes the old one
 * and opens a new one, and the caller must see a DIFFERENT conversation uuid back so it can stop
 * displaying the old conversation's turns as part of the same thread.
 */
public class ResolveConversationTest {

	private static Patient patient() {
		Patient patient = new Patient();
		patient.setUuid("patient-1");
		patient.setPatientId(1);
		return patient;
	}

	private static ClinicalConversation conversation(String uuid, Patient patient, String providerId,
			String mode, String status) {
		ClinicalConversation conversation = new ClinicalConversation();
		conversation.setUuid(uuid);
		conversation.setPatient(patient);
		conversation.setProviderId(providerId);
		conversation.setProviderMode(mode);
		conversation.setStatus(status);
		return conversation;
	}

	@Test
	public void reusesTheClientSuppliedSessionWhenProviderAndModeMatch() {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		StubConversationService conversations = new StubConversationService();
		Patient patient = patient();
		ClinicalConversation existing = conversation("conversation-1", patient, "bundled", "query_scoped",
				ClinicalConversation.STATUS_ACTIVE);
		conversations.byUuid.put("conversation-1", existing);
		controller.setConversationService(conversations);

		ClinicalConversation resolved = controller.resolveConversation(patient, "bundled",
				ProviderMode.QUERY_SCOPED, "conversation-1");

		assertSame(existing, resolved);
		assertEquals(0, conversations.openOrCreateCalls, "a matching session never falls through to openOrCreate");
	}

	@Test
	public void aProviderMismatchNeverReusesTheClientSuppliedSession() {
		// The exact live-observed scenario: a stale client session bound to "bundled" (e.g. from a
		// picker that didn't sync to a restored conversation's real provider) is submitted
		// alongside provider=hub. The old conversation must never receive a hub-answered turn.
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		StubConversationService conversations = new StubConversationService();
		Patient patient = patient();
		ClinicalConversation staleBundled = conversation("conversation-old-bundled", patient, "bundled",
				"query_scoped", ClinicalConversation.STATUS_ACTIVE);
		conversations.byUuid.put("conversation-old-bundled", staleBundled);
		ClinicalConversation freshHub = conversation("conversation-new-hub", patient, "hub", "query_scoped",
				ClinicalConversation.STATUS_ACTIVE);
		conversations.openOrCreateResult = freshHub;
		controller.setConversationService(conversations);

		ClinicalConversation resolved = controller.resolveConversation(patient, "hub", ProviderMode.QUERY_SCOPED,
				"conversation-old-bundled");

		assertSame(freshHub, resolved);
		assertNotEquals(staleBundled.getUuid(), resolved.getUuid(),
				"a provider-mismatched session must never be reused for the new provider's turn");
		assertEquals(1, conversations.openOrCreateCalls);
		assertEquals("hub", conversations.lastOpenOrCreateProviderId);
	}

	@Test
	public void aModeMismatchAlsoFallsThroughToOpenOrCreate() {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		StubConversationService conversations = new StubConversationService();
		Patient patient = patient();
		ClinicalConversation queryScoped = conversation("conversation-1", patient, "bundled", "query_scoped",
				ClinicalConversation.STATUS_ACTIVE);
		conversations.byUuid.put("conversation-1", queryScoped);
		ClinicalConversation fullChart = conversation("conversation-2", patient, "bundled", "full_chart_stable",
				ClinicalConversation.STATUS_ACTIVE);
		conversations.openOrCreateResult = fullChart;
		controller.setConversationService(conversations);

		ClinicalConversation resolved = controller.resolveConversation(patient, "bundled",
				ProviderMode.FULL_CHART_STABLE, "conversation-1");

		assertSame(fullChart, resolved);
		assertEquals(1, conversations.openOrCreateCalls);
	}

	@Test
	public void aClosedConversationIsNeverReusedEvenWithAMatchingProviderAndMode() {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		StubConversationService conversations = new StubConversationService();
		Patient patient = patient();
		ClinicalConversation closed = conversation("conversation-1", patient, "bundled", "query_scoped",
				ClinicalConversation.STATUS_CLOSED);
		conversations.byUuid.put("conversation-1", closed);
		ClinicalConversation fresh = conversation("conversation-2", patient, "bundled", "query_scoped",
				ClinicalConversation.STATUS_ACTIVE);
		conversations.openOrCreateResult = fresh;
		controller.setConversationService(conversations);

		ClinicalConversation resolved = controller.resolveConversation(patient, "bundled",
				ProviderMode.QUERY_SCOPED, "conversation-1");

		assertSame(fresh, resolved);
		assertEquals(1, conversations.openOrCreateCalls);
	}

	@Test
	public void anUnknownConversationUuidFallsThroughToOpenOrCreate() {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		StubConversationService conversations = new StubConversationService();
		Patient patient = patient();
		ClinicalConversation fresh = conversation("conversation-new", patient, "bundled", "query_scoped",
				ClinicalConversation.STATUS_ACTIVE);
		conversations.openOrCreateResult = fresh;
		controller.setConversationService(conversations);

		ClinicalConversation resolved = controller.resolveConversation(patient, "bundled",
				ProviderMode.QUERY_SCOPED, "does-not-exist");

		assertSame(fresh, resolved);
		assertEquals(1, conversations.openOrCreateCalls);
	}

	@Test
	public void aBlankConversationUuidGoesStraightToOpenOrCreate() {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		StubConversationService conversations = new StubConversationService();
		Patient patient = patient();
		ClinicalConversation fresh = conversation("conversation-new", patient, "bundled", "query_scoped",
				ClinicalConversation.STATUS_ACTIVE);
		conversations.openOrCreateResult = fresh;
		controller.setConversationService(conversations);

		ClinicalConversation resolved = controller.resolveConversation(patient, "bundled", ProviderMode.QUERY_SCOPED,
				null);

		assertSame(fresh, resolved);
		assertEquals(1, conversations.openOrCreateCalls);
	}

	private static final class StubConversationService implements ConversationService {

		final Map<String, ClinicalConversation> byUuid = new HashMap<>();

		ClinicalConversation openOrCreateResult;

		int openOrCreateCalls;

		String lastOpenOrCreateProviderId;

		@Override
		public ClinicalConversation openOrCreate(Patient patient, String providerId, ProviderMode mode) {
			openOrCreateCalls++;
			lastOpenOrCreateProviderId = providerId;
			return openOrCreateResult;
		}

		@Override
		public ClinicalConversation startNew(Patient patient, String providerId, ProviderMode mode) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ClinicalConversation getByUuid(String uuid) {
			return byUuid.get(uuid);
		}

		@Override
		public ClinicalConversation getLatestActiveConversation(Patient patient) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<ClinicalConversationTurn> getTurns(ClinicalConversation conversation) {
			return new ArrayList<ClinicalConversationTurn>();
		}

		@Override
		public ClinicalConversationTurn startTurn(ClinicalConversation conversation, String requestId,
				String question) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ClinicalConversationTurn finishTurn(ClinicalConversationTurn turn, TurnResult result,
				long responseTimeMs) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<PriorClinicalTurn> priorClinicalTurns(ClinicalConversation conversation) {
			throw new UnsupportedOperationException();
		}
	}
}
