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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.AuditLogService;
import org.openmrs.module.chartsearchai.api.ChatService;
import org.openmrs.module.chartsearchai.api.ChatService.ChatTurnResult;
import org.openmrs.module.chartsearchai.api.PatientAccessCheck;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.module.chartsearchai.model.ChatMessage;
import org.openmrs.module.chartsearchai.model.ChatSession;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * REST endpoints for persisted, patient-scoped clinical chat relayed through
 * med-agent-hub.
 *
 * <pre>
 * POST /ws/rest/v1/chartsearchai/chat
 * {
 *   "patient": "patient-uuid-here",
 *   "question": "What medications is this patient on?"
 * }
 * </pre>
 */
@Controller
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/chartsearchai")
public class ChartSearchAiRestController {

	private static final Logger log = LoggerFactory.getLogger(ChartSearchAiRestController.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final int MAX_QUESTION_LENGTH = 1000;

	private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

	// Defense-in-depth: catches common prompt injection phrases. This is a blocklist
	// and can be bypassed with paraphrasing. The hub's product profile owns the primary
	// structured-output constraint and returns the fixed clinical-answer envelope; this relay
	// deliberately does not construct model prompts or schemas.
	private static final Pattern PROMPT_INJECTION = Pattern.compile(
			"(?i)(ignore\\s+(previous|above|all)\\s+(instructions|prompts|rules)"
			+ "|disregard\\s+(your|the|all)\\s+(instructions|rules|prompt)"
			+ "|override\\s+(your|the|all)\\s+(instructions|rules|prompt)"
			+ "|bypass\\s+(your|the|all)\\s+(instructions|rules|prompt)"
			+ "|you\\s+are\\s+now|new\\s+instructions:|system\\s+prompt:"
			+ "|forget\\s+(your|the|all|previous)\\s+(instructions|rules|prompt))");

	private static final String DISCLAIMER = "This response is AI-generated and may not be "
			+ "accurate. It is not a substitute for clinical judgment. Always verify against "
			+ "the patient's medical records.";

	@Autowired
	@Qualifier("chartSearchAi.patientAccessCheck")
	private PatientAccessCheck patientAccessCheck;

	@Autowired
	@Qualifier("chartSearchAi.auditLogService")
	private AuditLogService auditLogService;

	@Autowired
	@Qualifier("chartSearchAi.chatService")
	private ChatService chatService;

	@Autowired
	@Qualifier("chartSearchAi.hubProfileService")
	private org.openmrs.module.chartsearchai.api.impl.HubProfileService hubProfileService;

	/**
	 * Relay med-agent-hub's authoritative product-profile metadata. ChartSearchAI does not merge,
	 * curate, or reinterpret the list; profile labels, availability, capabilities, and the default
	 * marker all come from the hub.
	 */
	@RequestMapping(value = "/models", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> listModels() {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);
		try {
			return new ResponseEntity<Object>(hubProfileService.listProfiles(), HttpStatus.OK);
		}
		catch (Exception e) {
			log.warn("Failed to list hub profiles: {}", e.getMessage());
			return new ResponseEntity<Object>(
					errorResponse("Hub profile discovery is unavailable."),
					HttpStatus.SERVICE_UNAVAILABLE);
		}
	}

	@RequestMapping(value = "/auditlog", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> getAuditLogs(
			@RequestParam(value = "patient", required = false) String patientUuid,
			@RequestParam(value = "user", required = false) String userUuid,
			@RequestParam(value = "fromDate", required = false) Long fromDateMs,
			@RequestParam(value = "toDate", required = false) Long toDateMs,
			@RequestParam(value = "startIndex", required = false) Integer startIndex,
			@RequestParam(value = "limit", required = false) Integer limit) {

		Context.requirePrivilege(ChartSearchAiConstants.PRIV_VIEW_AUDIT_LOGS);

		Patient patient = null;
		if (patientUuid != null && !patientUuid.trim().isEmpty()) {
			patient = Context.getPatientService().getPatientByUuid(patientUuid);
			if (patient == null) {
				return new ResponseEntity<Object>(
						errorResponse("Patient not found"), HttpStatus.NOT_FOUND);
			}
		}

		User user = null;
		if (userUuid != null && !userUuid.trim().isEmpty()) {
			user = Context.getUserService().getUserByUuid(userUuid);
			if (user == null) {
				return new ResponseEntity<Object>(
						errorResponse("User not found"), HttpStatus.NOT_FOUND);
			}
		}

		Date fromDate = fromDateMs != null ? new Date(fromDateMs) : null;
		Date toDate = toDateMs != null ? new Date(toDateMs) : null;

		List<ChartSearchAuditLog> logs = auditLogService.getAuditLogs(patient, user, fromDate, toDate,
				startIndex, limit);
		Long totalCount = auditLogService.getAuditLogCount(patient, user, fromDate, toDate);

		List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
		for (ChartSearchAuditLog auditLog : logs) {
			Map<String, Object> entry = new HashMap<String, Object>();
			entry.put("auditLogId", auditLog.getAuditLogId());
			entry.put("user", auditLog.getUser() != null ? auditLog.getUser().getUuid() : null);
			entry.put("username", auditLog.getUser() != null ? auditLog.getUser().getUsername() : null);
			entry.put("patient", auditLog.getPatient() != null ? auditLog.getPatient().getUuid() : null);
			entry.put("question", auditLog.getQuestion());
			entry.put("answer", auditLog.getAnswer());
			entry.put("referenceCount", auditLog.getReferenceCount());
			entry.put("searchMode", auditLog.getSearchMode());
			entry.put("responseTimeMs", auditLog.getResponseTimeMs());
			entry.put("inputTokens", auditLog.getInputTokens());
			entry.put("outputTokens", auditLog.getOutputTokens());
			entry.put("dateCreated", auditLog.getDateCreated() != null
					? auditLog.getDateCreated().getTime() : null);
			entry.put("rating", auditLog.getRating());
			entry.put("feedbackComment", auditLog.getFeedbackComment());
			results.add(entry);
		}

		Map<String, Object> response = new HashMap<String, Object>();
		response.put("results", results);
		response.put("totalCount", totalCount);

		return new ResponseEntity<Object>(response, HttpStatus.OK);
	}

	@RequestMapping(value = "/feedback", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> submitFeedback(@RequestBody Map<String, Object> body) {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);

		String validationError = validateFeedbackInput(body);
		if (validationError != null) {
			return new ResponseEntity<Object>(
					errorResponse(validationError), HttpStatus.BAD_REQUEST);
		}

		Integer auditLogId = Integer.valueOf(body.get("auditLogId").toString());
		String rating = body.get("rating").toString();
		String comment = sanitizeFeedbackComment(
				body.get("comment") != null ? body.get("comment").toString() : null);

		ChartSearchAuditLog auditLog = auditLogService.getAuditLog(auditLogId);
		User user = Context.getAuthenticatedUser();
		if (auditLog == null || auditLog.getUser() == null || !auditLog.getUser().equals(user)) {
			return new ResponseEntity<Object>(
					errorResponse("Question not found"), HttpStatus.NOT_FOUND);
		}

		auditLog.setRating(rating);
		auditLog.setFeedbackComment(comment);
		auditLogService.saveAuditLog(auditLog);

		Map<String, Object> response = new HashMap<String, Object>();
		response.put("success", true);
		return new ResponseEntity<Object>(response, HttpStatus.OK);
	}

	// ============================================================================
	// Patient-scoped chat endpoints. These maintain a per-(patient, user)
	// ChatSession that carries prior turns into each hub request. Persistence is
	// handled by {@link ChatService}.
	// ============================================================================

	/**
	 * Streaming chat turn. Reuses the (patient, user) session if {@code session}
	 * is provided AND resolves to an active session; otherwise opens-or-loads the
	 * latest active session for the user. Surfaces the session uuid via the
	 * {@code X-ChartSearchAi-Session} response header before the SSE stream opens
	 * so the client can pin subsequent posts to the same conversation.
	 *
	 * <pre>
	 * POST /ws/rest/v1/chartsearchai/chat/stream
	 * { "patient": "uuid", "session": "uuid?" , "question": "..." }
	 * </pre>
	 */
	@RequestMapping(value = "/chat/stream", method = RequestMethod.POST)
	public void chatStream(@RequestBody Map<String, String> body,
			HttpServletResponse response) throws IOException {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);

		String patientUuid = body.get("patient");
		String sessionUuid = body.get("session");
		String question = body.get("question");

		if (patientUuid == null || patientUuid.trim().isEmpty()) {
			writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "patient is required");
			return;
		}
		if (question == null || question.trim().isEmpty()) {
			writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "question is required");
			return;
		}
		if (question.length() > MAX_QUESTION_LENGTH) {
			writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
					"question exceeds maximum length of " + MAX_QUESTION_LENGTH + " characters");
			return;
		}

		String sanitizedQuestion = CONTROL_CHARS.matcher(question).replaceAll("");
		if (sanitizedQuestion.trim().isEmpty()) {
			writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "question is required");
			return;
		}
		String sanitizationError = validateQuestion(sanitizedQuestion);
		if (sanitizationError != null) {
			writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST, sanitizationError);
			return;
		}

		Patient patient = Context.getPatientService().getPatientByUuid(patientUuid);
		if (patient == null) {
			writeJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Patient not found");
			return;
		}

		User user = Context.getAuthenticatedUser();
		if (!patientAccessCheck.canAccess(user, patient)) {
			writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
					"You do not have access to this patient's chart");
			return;
		}

		ResponseEntity<Object> rateLimitError = checkRateLimit(user);
		if (rateLimitError != null) {
			writeJsonError(response, 429, "Rate limit exceeded");
			return;
		}

		// Resolve the requested hub profile before opening the stream.
		HubRequest hubRequest;
		try {
			hubRequest = resolveHubRequest(body);
		}
		catch (IllegalArgumentException e) {
			writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
			return;
		}

		try {
			ChatSession session = resolveOrOpenSession(patient, sessionUuid);

			// Unwrap any response wrappers that buffer the body (kills SSE liveness).
			HttpServletResponse unwrapped = response;
			while (unwrapped instanceof HttpServletResponseWrapper) {
				javax.servlet.ServletResponse inner =
						((HttpServletResponseWrapper) unwrapped).getResponse();
				if (inner instanceof HttpServletResponse) {
					unwrapped = (HttpServletResponse) inner;
				}
				else {
					break;
				}
			}

			response.setContentType("text/event-stream");
			response.setCharacterEncoding("UTF-8");
			response.setHeader("Cache-Control", "no-cache");
			response.setHeader("X-Accel-Buffering", "no");
			response.setHeader("Connection", "keep-alive");
			// Surface the session uuid before the stream opens so the client can pin
			// subsequent posts to this conversation.
			response.setHeader("X-ChartSearchAi-Session", session.getUuid());
			unwrapped.setBufferSize(0);

			final OutputStream out = unwrapped.getOutputStream();
			unwrapped.flushBuffer();

			try {
				streamHubStagedChat(out, session, patientUuid, sanitizedQuestion, hubRequest);
				return;
			}
			catch (IllegalStateException e) {
				log.error("Chat configuration error during streaming", e);
				try {
					writeSseEvent(out, "error",
							"Chart search is not properly configured. Contact your administrator.");
				}
				catch (IOException ioe) {
					log.debug("Could not send config error event, client likely disconnected");
				}
			}
			catch (Exception e) {
				if (e.getCause() instanceof IOException) {
					log.debug("Chat streaming ended due to client disconnect");
				}
				else {
					log.error("Chat streaming failed for patient [id={}]", patient.getPatientId(), e);
					try {
						writeSseEvent(out, "error",
								"Chart search failed. Please try again or contact your administrator.");
					}
					catch (IOException ioe) {
						log.debug("Could not send error event, client likely disconnected");
					}
				}
			}

			try {
				out.flush();
			}
			catch (IOException e) {
				log.debug("Could not flush chat SSE stream, client likely disconnected");
			}
		}
		catch (Exception e) {
			// Failure before the SSE stream opened. Return JSON rather than a servlet HTML page.
			if (!response.isCommitted()) {
				log.error("Chat stream setup failed for patient [id={}]", patient.getPatientId(), e);
				writeJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
						"Chart search failed. Please try again or contact your administrator.");
			}
			else {
				log.error("Chat stream failed after response commit for patient [id={}]", patient.getPatientId(), e);
			}
		}
	}

	/**
	 * Synchronous chat (non-streaming) — convenience for callers that don't need
	 * SSE. Same persistence semantics as {@link #chatStream}; the session uuid is
	 * returned in the JSON body (no SSE header).
	 */
	@RequestMapping(value = "/chat", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> chat(@RequestBody Map<String, String> body) {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);

		String patientUuid = body.get("patient");
		String sessionUuid = body.get("session");
		String question = body.get("question");

		PatientResolution resolved = resolvePatient(patientUuid);
		if (resolved.hasError()) {
			return new ResponseEntity<Object>(
					errorResponse(resolved.errorMessage), resolved.errorStatus);
		}
		Patient patient = resolved.patient;

		if (question == null || question.trim().isEmpty()) {
			return new ResponseEntity<Object>(
					errorResponse("question is required"), HttpStatus.BAD_REQUEST);
		}
		if (question.length() > MAX_QUESTION_LENGTH) {
			return new ResponseEntity<Object>(
					errorResponse("question exceeds maximum length of "
							+ MAX_QUESTION_LENGTH + " characters"),
					HttpStatus.BAD_REQUEST);
		}
		question = CONTROL_CHARS.matcher(question).replaceAll("");
		if (question.trim().isEmpty()) {
			return new ResponseEntity<Object>(
					errorResponse("question is required"), HttpStatus.BAD_REQUEST);
		}
		String sanitizationError = validateQuestion(question);
		if (sanitizationError != null) {
			return new ResponseEntity<Object>(
					errorResponse(sanitizationError), HttpStatus.BAD_REQUEST);
		}

		User user = Context.getAuthenticatedUser();
		ResponseEntity<Object> rateLimitError = checkRateLimit(user);
		if (rateLimitError != null) {
			return rateLimitError;
		}

		ChatSession session;
		try {
			session = resolveOrOpenSession(patient, sessionUuid);
		}
		catch (Exception e) {
			log.error("Chat session/chart resolution failed for patient [id={}]", patient.getPatientId(), e);
			return new ResponseEntity<Object>(
					errorResponse("Chart search failed. Please try again or contact your administrator."),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}

		// A caller may select a profile for this request; the configured hub endpoint is fixed.
		String answeredModel;
		HubRequest hubRequest;
		try {
			hubRequest = resolveHubRequest(body);
			answeredModel = hubRequest.profileId;
		}
		catch (IllegalArgumentException e) {
			return new ResponseEntity<Object>(errorResponse(e.getMessage()), HttpStatus.BAD_REQUEST);
		}

		ChatTurnResult result;
		Map<String, Object> wire;
		try {
			// Synchronous callers drain the same hub engine the product streams.
			long hubCallStart = System.nanoTime();
			wire = hubRelayCompletionWire(session, patientUuid, question, hubRequest);
			long responseTimeMs = (System.nanoTime() - hubCallStart) / 1_000_000;
			result = chatService.persistHubStagedAnswer(session, question, wire, responseTimeMs);
		}
		catch (HubRelayException e) {
			log.warn("Hub rejected chat for patient [id={}] with {}: {}",
					patient.getPatientId(), e.code, e.getMessage());
			return new ResponseEntity<Object>(errorResponse(e.userMessage), e.status);
		}
		catch (Exception e) {
			log.error("Chat failed for patient [id={}]", patient.getPatientId(), e);
			return new ResponseEntity<Object>(
					errorResponse("Chart search failed. Please try again or contact your administrator."),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}

		Map<String, Object> response = new LinkedHashMap<String, Object>(wire);
		response.put("disclaimer", DISCLAIMER);
		response.put("session", result.getSessionUuid());
		response.put("messageId", result.getAssistantMessageUuid());
		response.put("auditLogId", result.getAuditLogId());
		response.put("model", answeredModel);

		return new ResponseEntity<Object>(response, HttpStatus.OK);
	}

	/**
	 * Close the current active session for the (patient, user) pair and open
	 * a fresh one. Returns the new session uuid + empty messages.
	 */
	@RequestMapping(value = "/chat/new", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> chatNew(@RequestBody Map<String, String> body) {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);

		PatientResolution resolved = resolvePatient(body.get("patient"));
		if (resolved.hasError()) {
			return new ResponseEntity<Object>(
					errorResponse(resolved.errorMessage), resolved.errorStatus);
		}

		ChatSession session = chatService.closeAndStartNew(resolved.patient);
		Map<String, Object> response = new LinkedHashMap<String, Object>();
		response.put("session", session.getUuid());
		response.put("messages", new ArrayList<Map<String, Object>>());
		return new ResponseEntity<Object>(response, HttpStatus.OK);
	}

	/**
	 * Hydrate the SPA on mount: returns the current (patient, user) session
	 * (creating one if none exists) and its prior messages in chronological
	 * order. Empty {@code messages[]} on a freshly-created session.
	 */
	@RequestMapping(value = "/chat", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> chatHistory(
			@RequestParam(value = "patient") String patientUuid) {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);

		PatientResolution resolved = resolvePatient(patientUuid);
		if (resolved.hasError()) {
			return new ResponseEntity<Object>(
					errorResponse(resolved.errorMessage), resolved.errorStatus);
		}

		ChatSession session = chatService.openOrLoadActiveSession(resolved.patient);
		List<ChatMessage> messages = chatService.getMessages(session);

		List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
		ObjectMapper hydrateMapper = new ObjectMapper();
		for (ChatMessage m : messages) {
			Map<String, Object> entry = new LinkedHashMap<String, Object>();
			entry.put("messageId", m.getUuid());
			entry.put("auditLogId", m.getAuditLog() == null ? null : m.getAuditLog().getAuditLogId());
			entry.put("role", m.getRole());

			// Assistant rows persist a JSON envelope ({answer, blocks}); user
			// rows are plaintext. Parse JSON when present, surface prose +
			// blocks separately so the SPA can rehydrate the same view it
			// had during streaming. Legacy plaintext rows fall through
			// with blocks=[].
			if (ChatMessage.ROLE_ASSISTANT.equals(m.getRole())) {
					String stored = m.getContent();
					String prose = stored;
					List<Object> blocks = new ArrayList<Object>();
					List<Object> references = new ArrayList<Object>();
					List<Object> safetyWarnings = new ArrayList<Object>();
					Map<String, Object> confidence = null;
					Map<String, Object> answerValidation = null;
					Map<String, Object> inDepth = null;
					if (stored != null && stored.trim().startsWith("{")) {
						try {
							com.fasterxml.jackson.databind.JsonNode root =
									hydrateMapper.readTree(stored);
						com.fasterxml.jackson.databind.JsonNode answerNode = root.get("answer");
						if (answerNode != null && answerNode.isTextual()) {
							prose = answerNode.asText();
						}
						com.fasterxml.jackson.databind.JsonNode blocksNode = root.get("blocks");
						if (blocksNode != null && blocksNode.isArray()) {
							blocks = hydrateMapper.convertValue(blocksNode, List.class);
						}
						com.fasterxml.jackson.databind.JsonNode refsNode = root.get("references");
						if (refsNode != null && refsNode.isArray()) {
							references = hydrateMapper.convertValue(refsNode, List.class);
						}
						com.fasterxml.jackson.databind.JsonNode safetyWarningsNode = root.get("safetyWarnings");
						if (safetyWarningsNode != null && safetyWarningsNode.isArray()) {
							safetyWarnings = hydrateMapper.convertValue(safetyWarningsNode, List.class);
						}
						com.fasterxml.jackson.databind.JsonNode confNode = root.get("confidence");
							if (confNode != null && confNode.isObject()) {
								confidence = hydrateMapper.convertValue(confNode, Map.class);
							}
							com.fasterxml.jackson.databind.JsonNode answerValidationNode = root.get("answerValidation");
							if (answerValidationNode != null && answerValidationNode.isObject()) {
								answerValidation = hydrateMapper.convertValue(answerValidationNode, Map.class);
							}
							com.fasterxml.jackson.databind.JsonNode inDepthNode = root.get("inDepth");
							if (inDepthNode != null && inDepthNode.isObject()) {
								inDepth = hydrateMapper.convertValue(inDepthNode, Map.class);
							}
						}
						catch (IOException ignored) {
							// Treat as plaintext.
					}
				}
				entry.put("content", prose);
				entry.put("blocks", blocks);
				entry.put("references", references);
					if (!safetyWarnings.isEmpty()) {
						entry.put("safetyWarnings", safetyWarnings);
					}
					if (confidence != null) {
						entry.put("confidence", confidence);
					}
					if (answerValidation != null) {
						entry.put("answerValidation", answerValidation);
					}
					if (inDepth != null) {
						entry.put("inDepth", inDepth);
					}
				} else {
				entry.put("content", m.getContent());
			}

			entry.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().getTime() : null);
			out.add(entry);
		}

		Map<String, Object> response = new LinkedHashMap<String, Object>();
		response.put("session", session.getUuid());
		response.put("messages", out);
		return new ResponseEntity<Object>(response, HttpStatus.OK);
	}

	/**
	 * Look up an existing session by uuid (loadByUuid); fall back to
	 * openOrLoadActive when the uuid is missing or stale (e.g. expired).
	 * Always returns a non-null session.
	 */
	private ChatSession resolveOrOpenSession(Patient patient, String sessionUuid) {
		if (sessionUuid != null && !sessionUuid.trim().isEmpty()) {
			ChatSession existing = chatService.loadByUuid(sessionUuid.trim());
			if (existing != null && patient.equals(existing.getPatient())
					&& ChatSession.STATUS_ACTIVE.equals(existing.getStatus())) {
				return existing;
			}
		}
		return chatService.openOrLoadActiveSession(patient);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	@ResponseBody
	public ResponseEntity<Object> handleBadRequest(HttpMessageNotReadableException ex) {
		return new ResponseEntity<Object>(
				errorResponse("Invalid request body. Expected JSON with 'patient' and 'question' fields."),
				HttpStatus.BAD_REQUEST);
	}

	/**
	 * Maps an authorization failure to a proper status with a clean body. Without this, the framework
	 * serializes the thrown exception as an HTTP 200 carrying a full stack trace — both a misleading
	 * status and an information leak. 401 when the caller is unauthenticated; 403 when authenticated
	 * but lacking the privilege.
	 *
	 * <p>Catches both auth-failure types because they are siblings under {@code APIException}, not one
	 * hierarchy: every endpoint's up-front {@link Context#requirePrivilege} gate throws
	 * {@link ContextAuthenticationException} (the active path here, since this controller authorizes
	 * programmatically rather than with {@code @Authorized}), while the {@code @Authorized} AOP throws
	 * {@link APIAuthenticationException}. The second arm is defense-in-depth so an authorization failure
	 * raised by any downstream {@code @Authorized} service call still surfaces as 401/403 rather than
	 * falling to the catch-all as a 500.
	 */
	@ExceptionHandler({ ContextAuthenticationException.class, APIAuthenticationException.class })
	@ResponseBody
	public ResponseEntity<Object> handleAuthFailure(APIException ex) {
		HttpStatus status = Context.isAuthenticated() ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
		return new ResponseEntity<Object>(
				errorResponse(status == HttpStatus.FORBIDDEN ? "Insufficient privileges" : "Authentication required"),
				status);
	}

	/**
	 * Last-resort handler so an otherwise-unhandled exception surfaces as a clean 500 instead of the
	 * framework's default HTTP 200 + stack trace. The more specific handlers above (auth, malformed
	 * body) take precedence. The streaming endpoint handles its own post-commit errors internally, so
	 * this only sees pre-stream failures there.
	 */
	@ExceptionHandler(Exception.class)
	@ResponseBody
	public ResponseEntity<Object> handleUnexpected(Exception ex) {
		log.error("Unhandled exception in chartsearchai REST controller", ex);
		return new ResponseEntity<Object>(errorResponse("Internal error"),
				HttpStatus.INTERNAL_SERVER_ERROR);
	}

	/**
	 * Result of {@link #resolvePatient}. Either {@code patient} is non-null (success),
	 * or {@code errorStatus} + {@code errorMessage} describe the failure.
	 */
	private static final class PatientResolution {

		final Patient patient;

		final HttpStatus errorStatus;

		final String errorMessage;

		private PatientResolution(Patient patient, HttpStatus errorStatus, String errorMessage) {
			this.patient = patient;
			this.errorStatus = errorStatus;
			this.errorMessage = errorMessage;
		}

		static PatientResolution ok(Patient patient) {
			return new PatientResolution(patient, null, null);
		}

		static PatientResolution error(HttpStatus status, String message) {
			return new PatientResolution(null, status, message);
		}

		boolean hasError() {
			return patient == null;
		}
	}

	private PatientResolution resolvePatient(String patientUuid) {
		if (patientUuid == null || patientUuid.trim().isEmpty()) {
			return PatientResolution.error(HttpStatus.BAD_REQUEST, "patient is required");
		}
		Patient patient = Context.getPatientService().getPatientByUuid(patientUuid);
		if (patient == null) {
			return PatientResolution.error(HttpStatus.NOT_FOUND, "Patient not found");
		}
		User user = Context.getAuthenticatedUser();
		if (!patientAccessCheck.canAccess(user, patient)) {
			return PatientResolution.error(HttpStatus.FORBIDDEN,
					"You do not have access to this patient's chart");
		}
		return PatientResolution.ok(patient);
	}

	private ResponseEntity<Object> checkRateLimit(User user) {
		int maxPerMinute = ChartSearchAiConstants.DEFAULT_RATE_LIMIT_PER_MINUTE;
		String configured = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_RATE_LIMIT_PER_MINUTE);
		if (configured != null && !configured.trim().isEmpty()) {
			try {
				maxPerMinute = Integer.parseInt(configured.trim());
			}
			catch (NumberFormatException e) {
				log.warn("Invalid rate limit value '{}', using default", configured);
			}
		}

		if (maxPerMinute <= 0) {
			return null; // rate limiting disabled
		}

		Date oneMinuteAgo = new Date(System.currentTimeMillis() - 60000);
		long recentCount = auditLogService.getQueryCountByUserSince(user, oneMinuteAgo);

		if (recentCount >= maxPerMinute) {
			log.warn("Rate limit exceeded for user {} ({} queries in last minute)",
					user.getUserId(), recentCount);
			return new ResponseEntity<Object>(
					errorResponse("Rate limit exceeded. Maximum " + maxPerMinute
							+ " queries per minute."),
					HttpStatus.TOO_MANY_REQUESTS);
		}
		return null;
	}

	/**
	 * Validates feedback input fields (auditLogId and rating). Returns an error
	 * message if validation fails, or null if the input is valid.
	 */
	static String validateFeedbackInput(Map<String, Object> body) {
		Object auditLogIdObj = body.get("auditLogId");
		if (auditLogIdObj == null) {
			return "auditLogId is required";
		}
		try {
			Integer.valueOf(auditLogIdObj.toString());
		}
		catch (NumberFormatException e) {
			return "Invalid auditLogId";
		}
		String rating = body.get("rating") != null ? body.get("rating").toString() : null;
		if (rating == null || (!"positive".equals(rating) && !"negative".equals(rating))) {
			return "rating must be 'positive' or 'negative'";
		}
		return null;
	}

	/**
	 * Sanitizes a feedback comment: strips control characters and truncates
	 * to 500 characters. Returns null if the input is null.
	 */
	static String sanitizeFeedbackComment(String comment) {
		if (comment == null) {
			return null;
		}
		comment = CONTROL_CHARS.matcher(comment).replaceAll("");
		if (comment.length() > 500) {
			comment = comment.substring(0, 500);
		}
		return comment;
	}

	/**
	 * Validates and sanitizes the question input. Returns an error message if the question
	 * is rejected, or null if it passes validation.
	 */
	static String validateQuestion(String question) {
		if (PROMPT_INJECTION.matcher(question).find()) {
			log.warn("Rejected question containing prompt injection pattern");
			return "Question contains disallowed content";
		}
		return null;
	}

	private void streamHubStagedChat(OutputStream out, ChatSession session, String patientUuid,
			String question, HubRequest hubRequest) throws IOException {
		List<ChatMessage> priorTurns = chatService.priorTurnsForRelay(session);
		String requestJson = hubRelayRequestJson(hubRequest.profileId, patientUuid, priorTurns, question, true);
		HttpRequest request = hubRelayHttpRequest(hubRequest, requestJson, true);
		long hubCallStart = System.nanoTime();
		HttpResponse<InputStream> hubResponse;
		try {
			hubResponse = HttpClient.newHttpClient().send(request,
					HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Hub staged stream interrupted", e);
		}
		if (hubResponse.statusCode() < 200 || hubResponse.statusCode() >= 300) {
			String body = new String(hubResponse.body().readAllBytes(), StandardCharsets.UTF_8);
			log.warn("Hub staged stream returned HTTP {}: {}", hubResponse.statusCode(), body);
			HubRelayException failure = hubRelayFailure(hubResponse.statusCode(), body);
			writeSseEvent(out, "error", failure.userMessage);
			return;
		}

		final String[] assistantMessageUuid = new String[1];
		final boolean[] doneSeen = new boolean[1];
		final boolean[] inDepthTerminalSeen = new boolean[1];
		final boolean[] answerValidationSettled = new boolean[1];
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				hubResponse.body(), StandardCharsets.UTF_8))) {
			String event = "";
			StringBuilder data = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					handleHubStagedEvent(out, session, question, hubRequest.profileId,
								assistantMessageUuid, doneSeen, inDepthTerminalSeen,
								answerValidationSettled,
								event, data.toString(), hubCallStart);
					event = "";
					data.setLength(0);
				} else if (line.startsWith("event:")) {
					event = line.substring("event:".length()).trim();
				} else if (line.startsWith("data:")) {
					if (data.length() > 0) {
						data.append('\n');
					}
					String raw = line.substring("data:".length());
					data.append(raw.startsWith(" ") ? raw.substring(1) : raw);
				} else if (line.startsWith(":")) {
					// Hub heartbeat during a stalled leg — forward it so a browser disconnect is
					// detected here (Client disconnected -> propagates -> closes the hub connection
					// -> frees its router slot) instead of only on the next real event.
					writeSseCommentOrThrow(out);
				}
			}
			if (data.length() > 0) {
				handleHubStagedEvent(out, session, question, hubRequest.profileId,
						assistantMessageUuid, doneSeen, inDepthTerminalSeen,
						answerValidationSettled,
						event, data.toString(), hubCallStart);
			}
		}
		finally {
			if (!doneSeen[0] && !inDepthTerminalSeen[0]) {
				persistInterruptedState(session, assistantMessageUuid[0],
						!answerValidationSettled[0]);
			}
		}
		if (!doneSeen[0]) {
			writeSseEvent(out, "error", "Hub staged stream ended before final response.");
		}
	}

	/**
	 * One blocking hub call for the sync {@code POST /chat} adapter. Returns the hub's answer wire;
	 * the caller owns persistence.
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> hubRelayCompletionWire(ChatSession session, String patientUuid, String question,
			HubRequest hubRequest) throws IOException {
		List<ChatMessage> priorTurns = chatService.priorTurnsForRelay(session);
		String requestJson = hubRelayRequestJson(hubRequest.profileId, patientUuid, priorTurns, question, false);
		HttpRequest request = hubRelayHttpRequest(hubRequest, requestJson, false);
		HttpResponse<String> hubResponse;
		try {
			hubResponse = HttpClient.newHttpClient().send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Hub relay interrupted", e);
		}
		if (hubResponse.statusCode() < 200 || hubResponse.statusCode() >= 300) {
			throw hubRelayFailure(hubResponse.statusCode(), hubResponse.body());
		}
		Map<String, Object> completion = MAPPER.readValue(hubResponse.body(),
				new TypeReference<Map<String, Object>>() {});
		List<Object> choices = (List<Object>) completion.get("choices");
		if (choices == null || choices.isEmpty()) {
			throw new IOException("Hub relay returned no choices.");
		}
		Map<String, Object> message = (Map<String, Object>) ((Map<String, Object>) choices.get(0)).get("message");
		String content = message == null ? null : (String) message.get("content");
		if (content == null || content.isEmpty()) {
			throw new IOException("Hub relay returned an empty answer.");
		}
		return MAPPER.readValue(content, new TypeReference<Map<String, Object>>() {});
	}

	@SuppressWarnings("unchecked")
	private void handleHubStagedEvent(OutputStream out, ChatSession session, String question,
			String model, String[] assistantMessageUuid, boolean[] doneSeen,
			boolean[] inDepthTerminalSeen, boolean[] answerValidationSettled, String event,
			String data, long hubCallStart) throws IOException {
		if (event == null || event.isEmpty() || data == null || data.isEmpty()) {
			return;
		}
		if ("error".equals(event)) {
			persistInterruptedState(session, assistantMessageUuid[0],
					!answerValidationSettled[0]);
			doneSeen[0] = true;
			writeSseEvent(out, event, data);
			return;
		}
		Map<String, Object> payload = MAPPER.readValue(data,
				new TypeReference<Map<String, Object>>() {});
		if ("answer_done".equals(event)) {
			long responseTimeMs = (System.nanoTime() - hubCallStart) / 1_000_000;
			ChatTurnResult result = chatService.persistHubStagedAnswer(session, question, payload, responseTimeMs);
			assistantMessageUuid[0] = result.getAssistantMessageUuid();
			Object validation = payload.get("answerValidation");
			if (validation instanceof Map
					&& !"validating".equals(((Map<?, ?>) validation).get("status"))) {
				answerValidationSettled[0] = true;
			}
			writeHubPayload(out, event, payload, result, model);
			return;
		}
		if ("answer_validation".equals(event)) {
			ChatTurnResult result = chatService.updateHubStagedMessage(
					session, assistantMessageUuid[0], payload);
			answerValidationSettled[0] = true;
			writeHubPayload(out, event, payload, result, model);
			return;
		}
		if ("indepth_pending".equals(event)) {
			Object validation = payload.get("answerValidation");
			if (validation instanceof Map
					&& !"validating".equals(((Map<?, ?>) validation).get("status"))) {
				answerValidationSettled[0] = true;
			}
			if (assistantMessageUuid[0] != null) {
				ChatTurnResult result = chatService.updateHubStagedMessage(
						session, assistantMessageUuid[0], payload);
				writeHubPayload(out, event, payload, result, model);
			}
			else {
				payload.put("messageId", null);
				writeSseEvent(out, event, MAPPER.writeValueAsString(payload));
			}
			return;
		}
		if ("indepth_done".equals(event) || "indepth_error".equals(event)) {
			inDepthTerminalSeen[0] = true;
			Map<String, Object> update;
			if (payload.get("inDepth") instanceof Map) {
				update = payload;
			}
			else {
				update = new LinkedHashMap<String, Object>();
				update.put("inDepth", payload);
			}
			if (assistantMessageUuid[0] != null) {
				ChatTurnResult result = chatService.updateHubStagedMessage(
						session, assistantMessageUuid[0], update);
				writeHubPayload(out, event, payload, result, model);
				return;
			}
			payload.put("messageId", assistantMessageUuid[0]);
			writeSseEvent(out, event, MAPPER.writeValueAsString(payload));
			return;
		}
		if ("done".equals(event)) {
			doneSeen[0] = true;
			ChatTurnResult result;
			if (assistantMessageUuid[0] == null) {
				long responseTimeMs = (System.nanoTime() - hubCallStart) / 1_000_000;
				result = chatService.persistHubStagedAnswer(session, question, payload, responseTimeMs);
				assistantMessageUuid[0] = result.getAssistantMessageUuid();
			} else {
				result = chatService.updateHubStagedMessage(session, assistantMessageUuid[0], payload);
			}
			answerValidationSettled[0] = true;
			writeHubPayload(out, event, payload, result, model);
			return;
		}
		writeSseEvent(out, event, data);
	}

	private void persistInterruptedState(ChatSession session, String assistantMessageUuid,
			boolean answerValidationPending) {
		if (assistantMessageUuid == null) {
			return;
		}
		Map<String, Object> inDepth = new LinkedHashMap<String, Object>();
		inDepth.put("status", "failed");
		inDepth.put("answer", "");
		inDepth.put("error", "In-Depth was interrupted.");
		Map<String, Object> update = new LinkedHashMap<String, Object>();
		update.put("inDepth", inDepth);
		if (answerValidationPending) {
			Map<String, Object> validation = new LinkedHashMap<String, Object>();
			validation.put("status", "unavailable");
			validation.put("label", "Check unavailable");
			validation.put("summary", "The answer check was interrupted before completion.");
			update.put("answerValidation", validation);
		}
		try {
			chatService.updateHubStagedMessage(session, assistantMessageUuid, update);
		}
		catch (RuntimeException persistenceFailure) {
			log.warn("Could not persist interrupted In-Depth state for assistant message {}",
					assistantMessageUuid, persistenceFailure);
		}
	}

	private void writeHubPayload(OutputStream out, String event, Map<String, Object> payload,
			ChatTurnResult result, String model) throws IOException {
		payload.put("session", result.getSessionUuid());
		payload.put("messageId", result.getAssistantMessageUuid());
		payload.put("auditLogId", result.getAuditLogId());
		payload.put("model", model);
		payload.put("disclaimer", DISCLAIMER);
		writeSseEvent(out, event, MAPPER.writeValueAsString(payload));
	}

	private String hubRelayRequestJson(String model, String patientUuid, List<ChatMessage> priorTurns,
			String question, boolean stream) throws IOException {
		Map<String, Object> root = new LinkedHashMap<String, Object>();
		root.put("model", model);
		root.put("stream", stream);
		root.put("patient", patientUuid);
		List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
		// Prior turns: prose-only (priorTurnsForRelay's contract — never the raw stored JSON
		// envelope), chronological, excluding the current turn. The hub owns the chart and the
		// system prompt; it inserts both itself, so the relay sends conversation content only.
		if (priorTurns != null) {
			for (ChatMessage prior : priorTurns) {
				Map<String, Object> turn = new LinkedHashMap<String, Object>();
				turn.put("role", prior.getRole());
				turn.put("content", prior.getContent());
				messages.add(turn);
			}
		}
		Map<String, Object> user = new LinkedHashMap<String, Object>();
		user.put("role", "user");
		user.put("content", question);
		messages.add(user);
		root.put("messages", messages);
		Map<String, Object> context = new LinkedHashMap<String, Object>();
		context.put("require_product_profile", true);
		root.put("context", context);
		return MAPPER.writeValueAsString(root);
	}

	private String runtimeApiKey() {
		Properties props = Context.getRuntimeProperties();
		return props == null ? null : props.getProperty(ChartSearchAiConstants.RP_HUB_API_KEY);
	}

	private HttpRequest hubRelayHttpRequest(HubRequest hubRequest, String requestJson,
			boolean stream) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(hubRequest.endpointUrl))
				.version(HttpClient.Version.HTTP_1_1)
				.timeout(Duration.ofSeconds(300))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(
						requestJson.getBytes(StandardCharsets.UTF_8)));
		if (stream) {
			builder.header("Accept", "text/event-stream");
		}
		String apiKey = runtimeApiKey();
		if (apiKey != null && !apiKey.trim().isEmpty()) {
			builder.header("Authorization", "Bearer " + apiKey.trim());
		}
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	private HubRelayException hubRelayFailure(int upstreamStatus, String body) {
		String code = "hub_rejected_request";
		String detailMessage = null;
		try {
			Map<String, Object> envelope = MAPPER.readValue(body,
					new TypeReference<Map<String, Object>>() {});
			Object detail = envelope.get("detail");
			if (detail instanceof Map) {
				Map<String, Object> fields = (Map<String, Object>) detail;
				if (fields.get("code") != null) {
					code = fields.get("code").toString();
				}
				if (fields.get("message") != null) {
					detailMessage = fields.get("message").toString();
				}
			}
		}
		catch (IOException malformedErrorBody) {
			log.debug("Hub returned a non-JSON error body", malformedErrorBody);
		}
		if ("insufficient_context".equals(code)) {
			return new HubRelayException(HttpStatus.UNPROCESSABLE_ENTITY, code,
					detailMessage == null ? "The chart cannot fit safely in the selected model's context."
							: detailMessage,
					"This chart cannot fit safely in the selected model's context. "
							+ "Choose a profile with a larger context window or review the chart directly.");
		}
		return new HubRelayException(HttpStatus.BAD_GATEWAY, code,
				"Hub relay failed: HTTP " + upstreamStatus,
				"The clinical answer service could not process this request.");
	}

	private void writeSseEvent(OutputStream out, String event, String data) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("event: ").append(event).append('\n');
		for (String line : data.split("\n", -1)) {
			sb.append("data: ").append(line).append('\n');
		}
		sb.append('\n');
		out.write(sb.toString().getBytes("UTF-8"));
		out.flush();
	}

	/**
	 * Forwards a hub SSE heartbeat/comment line to the browser and converts a write failure into
	 * the {@link RuntimeException} the streaming loop unwinds on. A stalled leg (long answer/
	 * review/in-depth call) otherwise gives the relay NO opportunity to notice a browser disconnect
	 * until the hub's next real event — this write on every heartbeat is what makes a mid-leg abort
	 * actually free the router slot promptly instead of blocking for the rest of the leg.
	 */
	private void writeSseCommentOrThrow(OutputStream out) {
		try {
			out.write(": hb\n\n".getBytes(StandardCharsets.UTF_8));
			out.flush();
		}
		catch (IOException e) {
			log.debug("Client disconnected during a hub staged heartbeat");
			throw new RuntimeException("Client disconnected", e);
		}
	}

	private void writeJsonError(HttpServletResponse response, int status, String message)
			throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		new ObjectMapper().writeValue(response.getOutputStream(), errorResponse(message));
	}

	private Map<String, String> errorResponse(String message) {
		Map<String, String> error = new HashMap<String, String>();
		error.put("error", message);
		return error;
	}

	/**
	 * The configured hub endpoint and profile that will answer a chat request.
	 */
	private static final class HubRequest {

		private final String endpointUrl;

		private final String profileId;

		HubRequest(String endpointUrl, String profileId) {
			this.endpointUrl = endpointUrl;
			this.profileId = profileId;
		}
	}

	private static final class HubRelayException extends IOException {

		private static final long serialVersionUID = 1L;

		private final HttpStatus status;

		private final String code;

		private final String userMessage;

		HubRelayException(HttpStatus status, String code, String message, String userMessage) {
			super(message);
			this.status = status;
			this.code = code;
			this.userMessage = userMessage;
		}
	}

	/**
	 * Resolve one fixed hub endpoint plus a request-selected profile. Clients cannot override the
	 * endpoint or compose stages; they may only choose a hub-advertised profile id.
	 */
	private HubRequest resolveHubRequest(Map<String, String> body) {
		String endpointUrl = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_HUB_ENDPOINT_URL);
		String requestedProfile = body == null ? null : body.get("profile");
		if (requestedProfile == null || requestedProfile.trim().isEmpty()) {
			requestedProfile = Context.getAdministrationService()
					.getGlobalProperty(ChartSearchAiConstants.GP_HUB_PROFILE_ID);
		}
		if (endpointUrl == null || endpointUrl.trim().isEmpty()) {
			throw new IllegalArgumentException("med-agent-hub endpoint is not configured.");
		}
		if (requestedProfile == null || requestedProfile.trim().isEmpty()) {
			throw new IllegalArgumentException("med-agent-hub profile is required.");
		}
		return new HubRequest(endpointUrl.trim(), requestedProfile.trim());
	}
}
