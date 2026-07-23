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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.conversation.ConversationService;
import org.openmrs.module.chartsearchai.api.conversation.PriorClinicalTurn;
import org.openmrs.module.chartsearchai.api.provider.AnswerEnvelope;
import org.openmrs.module.chartsearchai.api.provider.CancellationSignal;
import org.openmrs.module.chartsearchai.api.provider.ClinicalAnswerProvider;
import org.openmrs.module.chartsearchai.api.provider.ClinicalAnswerProviderRegistry;
import org.openmrs.module.chartsearchai.api.provider.ProviderCapability;
import org.openmrs.module.chartsearchai.api.provider.ProviderDescriptor;
import org.openmrs.module.chartsearchai.api.provider.ProviderMode;
import org.openmrs.module.chartsearchai.api.provider.TurnEvent;
import org.openmrs.module.chartsearchai.api.provider.TurnEventSink;
import org.openmrs.module.chartsearchai.api.provider.TurnEventType;
import org.openmrs.module.chartsearchai.api.provider.TurnRequest;
import org.openmrs.module.chartsearchai.api.provider.TurnResult;
import org.openmrs.module.chartsearchai.model.ClinicalConversation;
import org.openmrs.module.chartsearchai.model.ClinicalConversationTurn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST contract for provider discovery and provider-neutral chat streaming. Driven through the
 * controller's package-private orchestration seams with stubbed registry/conversation services —
 * same style as {@link ChartSearchAiStreamEventOrderTest}.
 */
public class ProviderRestContractTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	@SuppressWarnings("unchecked")
	public void providersPayloadExposesPickerVisibilityDefaultAndUnavailableHub() {
		ProviderDescriptor bundled = new ProviderDescriptor("bundled", "ChartSearchAI (bundled)", true,
				true, true, Collections.singletonList(ProviderMode.QUERY_SCOPED),
				EnumSet.of(ProviderCapability.ANSWER, ProviderCapability.TOKEN_STREAMING), null);
		ProviderDescriptor hub = new ProviderDescriptor("hub", "med-agent-hub", true, false, false,
				Collections.singletonList(ProviderMode.QUERY_SCOPED),
				EnumSet.of(ProviderCapability.ANSWER, ProviderCapability.INDEPTH),
				"chartsearchai.hub.endpointUrl is not set");

		Map<String, Object> payload = ChartSearchAiRestController.providersResponse(
				Arrays.asList(bundled, hub), true, "bundled");

		assertEquals("bundled", payload.get("defaultProvider"));
		assertEquals(Boolean.TRUE, payload.get("pickerVisible"));
		List<Map<String, Object>> providers = (List<Map<String, Object>>) payload.get("providers");
		assertEquals(2, providers.size());
		assertEquals("bundled", providers.get(0).get("id"));
		assertEquals(Boolean.TRUE, providers.get(0).get("default"));
		assertEquals(Boolean.TRUE, providers.get(0).get("ready"));
		assertEquals(Arrays.asList("query_scoped"), providers.get(0).get("modes"));
		assertTrue(((List<?>) providers.get(0).get("capabilities")).contains("answer"));
		assertEquals("hub", providers.get(1).get("id"));
		assertEquals(Boolean.FALSE, providers.get(1).get("ready"));
		assertEquals("chartsearchai.hub.endpointUrl is not set",
				providers.get(1).get("unavailableReason"));
		assertFalse((Boolean) providers.get(1).get("default"));
	}

	@Test
	public void chatStreamMapsCanonicalTurnEventsAndPersistsThroughConversationService()
			throws Exception {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		RecordingConversationService conversations = new RecordingConversationService();
		ScriptedProvider provider = new ScriptedProvider("bundled", true);
		provider.events = Arrays.asList(
				TurnEvent.of(TurnEventType.TURN_STARTED, 0, "bundled"),
				TurnEvent.delta(TurnEventType.ANSWER_DELTA, 1, "bundled", "Aspirin "),
				TurnEvent.withAnswer(TurnEventType.ANSWER_DONE, 2, "bundled",
						AnswerEnvelope.fromPayload(answerPayload("Aspirin 81mg."))),
				TurnEvent.of(TurnEventType.TURN_DONE, 3, "bundled"));
		provider.result = TurnResult.done("bundled", ProviderMode.QUERY_SCOPED,
				AnswerEnvelope.fromPayload(answerPayload("Aspirin 81mg.")));
		controller.setConversationService(conversations);
		controller.setProviderRegistry(stubRegistry(provider));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		controller.streamProviderTurn(out, patient(), "What meds?", "bundled",
				ProviderMode.QUERY_SCOPED, null, null);

		List<String> types = sseTypes(out);
		assertEquals(Arrays.asList("turn_started", "answer_delta", "answer_done", "turn_done"),
				types);
		assertEquals(1, conversations.started);
		assertEquals(1, conversations.finished);
		assertEquals("Aspirin 81mg.", conversations.lastFinishedAnswer);
		assertEquals("conversation-uuid-1", conversations.openConversation.getUuid());
		JsonNode answerDone = ssePayload(out, "answer_done");
		assertEquals("Aspirin 81mg.", answerDone.get("answer").asText());
		assertEquals("turn-uuid-1", answerDone.get("messageId").asText());
		assertEquals("conversation-uuid-1", answerDone.get("session").asText());
	}

	@Test
	public void chatStreamFailsWithTurnErrorWhenProviderIsUnavailableWithoutFallback()
			throws Exception {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		RecordingConversationService conversations = new RecordingConversationService();
		ScriptedProvider bundled = new ScriptedProvider("bundled", true);
		controller.setConversationService(conversations);
		controller.setProviderRegistry(stubRegistry(bundled));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		controller.streamProviderTurn(out, patient(), "What meds?", "hub",
				ProviderMode.QUERY_SCOPED, null, null);

		List<String> types = sseTypes(out);
		assertEquals(Arrays.asList("turn_started", "turn_error"), types);
		assertEquals(0, conversations.started);
		assertEquals(0, conversations.finished);
		JsonNode error = ssePayload(out, "turn_error");
		assertEquals("unknown_provider", error.get("problemCode").asText());
	}

	@Test
	public void chatStreamRequiresHubProfileAndNeverCallsAnUnreadyProvider() throws Exception {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		RecordingConversationService conversations = new RecordingConversationService();
		ScriptedProvider hub = new ScriptedProvider("hub", true);
		controller.setConversationService(conversations);
		controller.setProviderRegistry(stubRegistry(hub));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		controller.streamProviderTurn(out, patient(), "What meds?", "hub",
				ProviderMode.QUERY_SCOPED, null, null);

		assertEquals(0, hub.calls.get());
		assertEquals(Arrays.asList("turn_started", "turn_error"), sseTypes(out));
		assertEquals("profile_required", ssePayload(out, "turn_error").get("problemCode").asText());
	}

	@Test
	public void resolveModeReturnsNullWhenTheRequestOmitsMode() {
		// Mode is a DEPLOYMENT setting (chartsearchai.chartMode), never something callers are
		// expected to send. resolveMode must return null (not a hardcoded default) so
		// streamProviderTurn's own fallback — provider.modes().get(0), sourced from the
		// provider's LIVE configured mode — decides. A hardcoded default here silently
		// overrides whatever chartMode is actually configured to.
		ChartSearchAiRestController controller = new ChartSearchAiRestController();

		assertEquals(null, controller.resolveMode(new HashMap<String, String>()));
		assertEquals(null, controller.resolveMode(null));
		Map<String, String> blank = new HashMap<String, String>();
		blank.put("mode", "   ");
		assertEquals(null, controller.resolveMode(blank));
	}

	@Test
	public void resolveModeHonorsAnExplicitOverride() {
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		Map<String, String> body = new HashMap<String, String>();
		body.put("mode", "full_chart_stable");

		assertEquals(ProviderMode.FULL_CHART_STABLE, controller.resolveMode(body));
	}

	@Test
	public void chatStreamWithNoExplicitModeUsesTheProvidersLiveConfiguredMode() throws Exception {
		// Regression: chartsearchai.chartMode=fullChart previously failed EVERY turn with
		// unsupported_mode, because resolveMode's hardcoded query_scoped default never matched
		// the provider's actual configured mode (full_chart_stable) — a mismatch the provider's
		// own no-silent-fallback guard correctly rejects. With mode UNSPECIFIED (the normal,
		// only-ever-used-in-practice case — no caller sends "mode"), the turn must succeed using
		// whatever mode the provider is actually configured for.
		ChartSearchAiRestController controller = new ChartSearchAiRestController();
		RecordingConversationService conversations = new RecordingConversationService();
		ScriptedProvider bundled = new ScriptedProvider("bundled", true);
		bundled.mode = ProviderMode.FULL_CHART_STABLE;
		bundled.events = Arrays.asList(
				TurnEvent.of(TurnEventType.TURN_STARTED, 0, "bundled"),
				TurnEvent.withAnswer(TurnEventType.ANSWER_DONE, 1, "bundled",
						AnswerEnvelope.fromPayload(answerPayload("Full chart summary."))),
				TurnEvent.of(TurnEventType.TURN_DONE, 2, "bundled"));
		bundled.result = TurnResult.done("bundled", ProviderMode.FULL_CHART_STABLE,
				AnswerEnvelope.fromPayload(answerPayload("Full chart summary.")));
		controller.setConversationService(conversations);
		controller.setProviderRegistry(stubRegistry(bundled));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Map<String, String> body = new HashMap<String, String>();
		// No "mode" key — the only shape any real caller sends.
		ProviderMode resolved = controller.resolveMode(body);
		controller.streamProviderTurn(out, patient(), "Summarize the chart", "bundled",
				resolved, null, null);

		assertEquals(Arrays.asList("turn_started", "answer_done", "turn_done"), sseTypes(out));
		assertEquals("Full chart summary.", ssePayload(out, "answer_done").get("answer").asText());
	}

	private static Patient patient() {
		Patient patient = new Patient();
		patient.setUuid("patient-1");
		patient.setPatientId(1);
		return patient;
	}

	/** Registry that enables every constructed provider without reading OpenMRS GPs. */
	private static ClinicalAnswerProviderRegistry stubRegistry(ClinicalAnswerProvider... providers) {
		List<ClinicalAnswerProvider> list = Arrays.asList(providers);
		String enabled = list.stream().map(ClinicalAnswerProvider::id)
				.collect(Collectors.joining(","));
		return new ClinicalAnswerProviderRegistry(list) {
			private final Map<String, String> gps = new HashMap<>();
			{
				gps.put(ClinicalAnswerProviderRegistry.GP_PROVIDERS_ENABLED, enabled);
				gps.put(ClinicalAnswerProviderRegistry.GP_DEFAULT_PROVIDER, list.get(0).id());
			}

			@Override
			protected String gp(String property, String defaultValue) {
				return gps.containsKey(property) ? gps.get(property) : defaultValue;
			}
		};
	}

	private static Map<String, Object> answerPayload(String answer) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("answer", answer);
		payload.put("references", Collections.emptyList());
		return payload;
	}

	private static List<String> sseTypes(ByteArrayOutputStream out) {
		List<String> types = new ArrayList<>();
		for (String block : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n\n")) {
			for (String line : block.split("\n")) {
				if (line.startsWith("event: ")) {
					types.add(line.substring(7).trim());
				}
			}
		}
		return types;
	}

	private static JsonNode ssePayload(ByteArrayOutputStream out, String type) throws Exception {
		String current = null;
		StringBuilder data = new StringBuilder();
		for (String block : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n\n")) {
			current = null;
			data.setLength(0);
			for (String line : block.split("\n")) {
				if (line.startsWith("event: ")) {
					current = line.substring(7).trim();
				} else if (line.startsWith("data: ")) {
					data.append(line.substring(6));
				}
			}
			if (type.equals(current)) {
				return MAPPER.readTree(data.toString());
			}
		}
		return null;
	}

	private static final class ScriptedProvider implements ClinicalAnswerProvider {

		private final String id;

		private final boolean ready;

		ProviderMode mode = ProviderMode.QUERY_SCOPED;

		private final AtomicInteger calls = new AtomicInteger();

		List<TurnEvent> events = Collections.emptyList();

		TurnResult result;

		ScriptedProvider(String id, boolean ready) {
			this.id = id;
			this.ready = ready;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public ProviderDescriptor descriptor() {
			return new ProviderDescriptor(id, id, true, ready, "bundled".equals(id),
					Collections.singletonList(mode),
					EnumSet.of(ProviderCapability.ANSWER, ProviderCapability.TOKEN_STREAMING),
					ready ? null : "not ready");
		}

		@Override
		public CompletionStage<TurnResult> execute(TurnRequest request, TurnEventSink sink,
				CancellationSignal cancellation) {
			calls.incrementAndGet();
			if (request.getProfileId() == null && "hub".equals(id)) {
				sink.accept(TurnEvent.of(TurnEventType.TURN_STARTED, 0, id));
				sink.accept(TurnEvent.error(1, id, "profile_required"));
				return CompletableFuture.completedFuture(
						TurnResult.error(id, request.getMode(), "profile_required"));
			}
			for (TurnEvent event : events) {
				sink.accept(event);
			}
			return CompletableFuture.completedFuture(result);
		}
	}

	private static final class RecordingConversationService implements ConversationService {

		ClinicalConversation openConversation;

		int started;

		int finished;

		String lastFinishedAnswer;

		RecordingConversationService() {
			openConversation = new ClinicalConversation();
			openConversation.setUuid("conversation-uuid-1");
			openConversation.setProviderId("bundled");
			openConversation.setProviderMode(ProviderMode.QUERY_SCOPED.getWireName());
			openConversation.setStatus(ClinicalConversation.STATUS_ACTIVE);
		}

		@Override
		public ClinicalConversation openOrCreate(Patient patient, String providerId,
				ProviderMode mode) {
			openConversation.setPatient(patient);
			openConversation.setProviderId(providerId);
			openConversation.setProviderMode(mode == null ? null : mode.getWireName());
			return openConversation;
		}

		@Override
		public ClinicalConversation startNew(Patient patient, String providerId,
				ProviderMode mode) {
			return openOrCreate(patient, providerId, mode);
		}

		@Override
		public ClinicalConversation getByUuid(String uuid) {
			return openConversation.getUuid().equals(uuid) ? openConversation : null;
		}

		@Override
		public List<ClinicalConversationTurn> getTurns(ClinicalConversation conversation) {
			return Collections.emptyList();
		}

		@Override
		public ClinicalConversationTurn startTurn(ClinicalConversation conversation,
				String requestId, String question) {
			started++;
			ClinicalConversationTurn turn = new ClinicalConversationTurn();
			turn.setUuid("turn-uuid-1");
			turn.setConversation(conversation);
			turn.setQuestion(question);
			turn.setRequestId(requestId);
			return turn;
		}

		@Override
		public ClinicalConversationTurn finishTurn(ClinicalConversationTurn turn,
				TurnResult result, long responseTimeMs) {
			finished++;
			lastFinishedAnswer = result.getAnswer() == null ? null : result.getAnswer().getText();
			return turn;
		}

		@Override
		public List<PriorClinicalTurn> priorClinicalTurns(ClinicalConversation conversation) {
			return Collections.emptyList();
		}
	}
}
