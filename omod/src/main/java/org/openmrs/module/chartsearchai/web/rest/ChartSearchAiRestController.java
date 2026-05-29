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

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.util.DateFormatUtil;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.ChartTooLargeException;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.AuditLogService;
import org.openmrs.module.chartsearchai.api.ChatService;
import org.openmrs.module.chartsearchai.api.ChatService.ChatTurnResult;
import org.openmrs.module.chartsearchai.api.PatientAccessCheck;
import org.openmrs.module.chartsearchai.api.impl.ResponseBlock;
import org.openmrs.module.chartsearchai.api.impl.WarmupExecutor;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.module.chartsearchai.model.ChatMessage;
import org.openmrs.module.chartsearchai.model.ChatSession;
import org.openmrs.module.webservices.rest.web.RestConstants;
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
 * REST endpoint for AI-powered chart search.
 *
 * <pre>
 * POST /ws/rest/v1/chartsearchai/search
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

	private static final int MAX_QUESTION_LENGTH = 1000;

	private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

	private static String formatDate(Date date) {
		return date != null ? DateFormatUtil.formatDate(date) : null;
	}

	/**
	 * Serialize a list of {@link ResponseBlock} into the JSON-Map shape used
	 * by both the {@code /chat} sync response and the SSE {@code done} event.
	 * Keeps wire format identical across the two surfaces so the SPA only
	 * implements one parser.
	 */
	private static List<Map<String, Object>> blocksToJson(List<ResponseBlock> blocks) {
		List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
		if (blocks == null) {
			return out;
		}
		for (ResponseBlock block : blocks) {
			Map<String, Object> blockMap = new LinkedHashMap<String, Object>();
			blockMap.put("kind", block.getKind());
			if (block.getTitle() != null) {
				blockMap.put("title", block.getTitle());
			}
			List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
			for (ResponseBlock.Column c : block.getColumns()) {
				Map<String, Object> col = new LinkedHashMap<String, Object>();
				col.put("key", c.getKey());
				col.put("label", c.getLabel());
				columns.add(col);
			}
			blockMap.put("columns", columns);
			List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
			for (ResponseBlock.Row row : block.getRows()) {
				Map<String, Object> cellsMap = new LinkedHashMap<String, Object>();
				for (Map.Entry<String, ResponseBlock.Cell> entry : row.getCells().entrySet()) {
					Map<String, Object> cellMap = new LinkedHashMap<String, Object>();
					cellMap.put("text", entry.getValue().getText());
					cellMap.put("refs", entry.getValue().getRefs());
					cellsMap.put(entry.getKey(), cellMap);
				}
				Map<String, Object> rowMap = new LinkedHashMap<String, Object>();
				rowMap.put("cells", cellsMap);
				rows.add(rowMap);
			}
			blockMap.put("rows", rows);
			out.add(blockMap);
		}
		return out;
	}

	// Defense-in-depth: catches common prompt injection phrases. This is a blocklist
	// and can be bypassed with paraphrasing. The primary defense is the structured-output
	// constraint (response_format: json_schema, used by both engines) which forces LLM
	// output into a fixed {answer, citations} shape regardless of prompt content.
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
	@Qualifier("chartSearchAi.chartSearchServiceRouter")
	private ChartSearchService chartSearchService;

	@Autowired
	@Qualifier("chartSearchAi.patientAccessCheck")
	private PatientAccessCheck patientAccessCheck;

	@Autowired
	@Qualifier("chartSearchAi.auditLogService")
	private AuditLogService auditLogService;

	@Autowired
	@Qualifier("chartSearchAi.warmupExecutor")
	private WarmupExecutor warmupExecutor;

	@Autowired
	@Qualifier("chartSearchAi.chatService")
	private ChatService chatService;

	@Autowired
	@Qualifier("chartSearchAi.modelSwitchService")
	private org.openmrs.module.chartsearchai.api.impl.ModelSwitchService modelSwitchService;

	@RequestMapping(value = "/search", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> search(@RequestBody Map<String, String> body) {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);

		String patientUuid = body.get("patient");
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

		String preFilter = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_EMBEDDING_PRE_FILTER, "false");
		boolean preFilterEnabled = !"false".equalsIgnoreCase(preFilter.trim());

		ChartAnswer chartAnswer;
		long responseTimeMs;
		try {
			long startTime = System.currentTimeMillis();
			chartAnswer = chartSearchService.search(patient, question);
			responseTimeMs = System.currentTimeMillis() - startTime;
		}
		catch (ChartTooLargeException e) {
			log.warn("Chart too large for LLM context for patient [id={}]: {}",
					patient.getPatientId(), e.getMessage());
			return new ResponseEntity<Object>(
					errorResponse("This patient's chart is too large to process. "
							+ "Contact your administrator to increase the LLM context size."),
					HttpStatus.PAYLOAD_TOO_LARGE);
		}
		catch (IllegalStateException e) {
			log.error("Chart search configuration error", e);
			return new ResponseEntity<Object>(
					errorResponse("Chart search is not properly configured. Contact your administrator."),
					HttpStatus.SERVICE_UNAVAILABLE);
		}
		catch (Exception e) {
			log.error("Chart search failed for patient [id={}]", patient.getPatientId(), e);
			return new ResponseEntity<Object>(
					errorResponse("Chart search failed. Please try again or contact your administrator."),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}

		ChartSearchAuditLog auditLog = new ChartSearchAuditLog();
		auditLog.setUser(user);
		auditLog.setPatient(patient);
		auditLog.setQuestion(question);
		auditLog.setAnswer(chartAnswer.getAnswer());
		auditLog.setReferenceCount(chartAnswer.getReferences().size());
		auditLog.setSearchMode(preFilterEnabled ? "pre-filter" : "full-chart");
		auditLog.setResponseTimeMs(responseTimeMs);
		auditLog.setInputTokens(chartAnswer.getInputTokens() > 0 ? chartAnswer.getInputTokens() : null);
		auditLog.setOutputTokens(chartAnswer.getOutputTokens() > 0 ? chartAnswer.getOutputTokens() : null);
		auditLog.setDateCreated(new Date());
		try {
			auditLogService.saveAuditLog(auditLog);
		}
		catch (Exception e) {
			log.warn("Failed to save audit log for search query", e);
		}

		Map<String, Object> response = new HashMap<String, Object>();
		response.put("answer", chartAnswer.getAnswer());
		response.put("disclaimer", DISCLAIMER);

		List<Map<String, Object>> refs = new ArrayList<Map<String, Object>>();
		for (RecordReference ref : chartAnswer.getReferences()) {
			Map<String, Object> refMap = new LinkedHashMap<String, Object>();
			refMap.put("index", ref.getIndex());
			refMap.put("resourceType", ref.getResourceType());
			refMap.put("resourceUuid", ref.getResourceUuid());
			refMap.put("date", formatDate(ref.getDate()));
			refs.add(refMap);
		}
		response.put("references", refs);
		if (auditLog.getAuditLogId() != null) {
			response.put("questionId", String.valueOf(auditLog.getAuditLogId()));
		}

		return new ResponseEntity<Object>(response, HttpStatus.OK);
	}

	/**
	 * Pre-warm the LLM prompt cache for a patient's chart. Called by the frontend
	 * when a patient chart is opened, so the first AI query on that patient does
	 * not pay full prefill cost. Returns 202 Accepted immediately; the warmup runs
	 * on a background daemon thread.
	 */
	@RequestMapping(value = "/warmup", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> warmup(@RequestBody Map<String, String> body) {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);

		PatientResolution resolved = resolvePatient(body.get("patient"));
		if (resolved.hasError()) {
			return new ResponseEntity<Object>(
					errorResponse(resolved.errorMessage), resolved.errorStatus);
		}

		warmupExecutor.submit(resolved.patient);
		return new ResponseEntity<Object>(HttpStatus.ACCEPTED);
	}

	/**
	 * List the models the active remote endpoint reports via {@code /v1/models},
	 * alongside the currently-selected model name and active engine.
	 *
	 * <p>Returns {@code 503 Service Unavailable} when {@code engine=local}: model
	 * switching is only meaningful against the remote engine.
	 *
	 * <p>Gated by {@code AI Query Patient Data} — same gate as the chat endpoints
	 * so the picker visibility matches the chat panel visibility.
	 */
	@RequestMapping(value = "/models", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> listModels() {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);
		org.openmrs.module.chartsearchai.api.impl.ModelSwitchService.ModelListResponse snapshot;
		try {
			snapshot = modelSwitchService.listAvailable();
		}
		catch (Exception e) {
			log.warn("Failed to list models: {}", e.getMessage());
			return new ResponseEntity<Object>(
					errorResponse("Failed to list models: " + e.getMessage()),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}
		if (!ChartSearchAiConstants.LLM_ENGINE_REMOTE.equalsIgnoreCase(snapshot.getEngine())) {
			Map<String, Object> body = new LinkedHashMap<String, Object>();
			body.put("engine", snapshot.getEngine());
			body.put("error", "Model listing requires the remote engine; active engine is '"
					+ snapshot.getEngine() + "'.");
			return new ResponseEntity<Object>(body, HttpStatus.SERVICE_UNAVAILABLE);
		}
		Map<String, Object> response = new LinkedHashMap<String, Object>();
		response.put("engine", snapshot.getEngine());
		response.put("current", snapshot.getCurrent());
		response.put("available", snapshot.getAvailable());
		response.put("endpointUrl", snapshot.getEndpointUrl());
		// Enriched fields — populated when LM Studio /api/v1/models probe
		// succeeded; null/empty otherwise. The picker reads `provider` for
		// sub-category grouping and `entries[].loaded` for the per-entry
		// state affix. Older SPA builds ignore these fields.
		if (snapshot.getProvider() != null) {
			response.put("provider", snapshot.getProvider());
		}
		if (snapshot.getEntries() != null && !snapshot.getEntries().isEmpty()) {
			List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
			for (org.openmrs.module.chartsearchai.api.impl.ModelSwitchService.ModelEntry e
					: snapshot.getEntries()) {
				Map<String, Object> row = new LinkedHashMap<String, Object>();
				row.put("id", e.getId());
				row.put("displayName", e.getDisplayName());
				row.put("type", e.getType());
				row.put("loaded", e.isLoaded());
				if (e.getMaxContextLength() != null) {
					row.put("maxContextLength", e.getMaxContextLength());
				}
				entries.add(row);
			}
			response.put("entries", entries);
		}
		return new ResponseEntity<Object>(response, HttpStatus.OK);
	}

	/**
	 * Request that the upstream LM Studio server pre-load the named model
	 * into memory. Returns 200 on success (model loaded; subsequent chat
	 * completion will be fast), 400 for blank input, 503 when the active
	 * engine is local or no LM Studio v1 endpoint is reachable.
	 *
	 * <p>Body: {@code {"modelName": "<id>"}}. Same gate as {@code /model}.
	 */
	@RequestMapping(value = "/model/load", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> loadModel(@RequestBody Map<String, String> body) {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);
		String requested = body == null ? null : body.get("modelName");
		try {
			modelSwitchService.requestModelLoad(requested);
			Map<String, Object> response = new LinkedHashMap<String, Object>();
			response.put("loaded", requested == null ? "" : requested.trim());
			return new ResponseEntity<Object>(response, HttpStatus.OK);
		}
		catch (IllegalArgumentException e) {
			return new ResponseEntity<Object>(errorResponse(e.getMessage()),
					HttpStatus.BAD_REQUEST);
		}
		catch (org.openmrs.api.APIException e) {
			return new ResponseEntity<Object>(errorResponse(e.getMessage()),
					HttpStatus.SERVICE_UNAVAILABLE);
		}
	}

	/**
	 * Switch the active remote model. Validates the requested name is in the
	 * live {@code /v1/models} list before writing the GP, so the next chat
	 * request can't route to a model the endpoint doesn't actually serve.
	 *
	 * <p>Body: {@code {"modelName": "<id>"}}. Returns {@code {current}} on
	 * success, {@code 400} for invalid input, {@code 503} for local engine.
	 */
	@RequestMapping(value = "/model", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> setModel(@RequestBody Map<String, String> body) {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);
		String requested = body == null ? null : body.get("modelName");
		try {
			String now = modelSwitchService.setCurrent(requested);
			Map<String, Object> response = new LinkedHashMap<String, Object>();
			response.put("current", now);
			return new ResponseEntity<Object>(response, HttpStatus.OK);
		}
		catch (IllegalArgumentException e) {
			return new ResponseEntity<Object>(errorResponse(e.getMessage()),
					HttpStatus.BAD_REQUEST);
		}
		catch (org.openmrs.api.APIException e) {
			// listAvailable throws APIException for local-engine or misconfig.
			// Surface as 503 so the SPA picker can hide itself cleanly.
			return new ResponseEntity<Object>(errorResponse(e.getMessage()),
					HttpStatus.SERVICE_UNAVAILABLE);
		}
	}

	/**
	 * Streaming search endpoint using Server-Sent Events. Streams tokens as they are
	 * generated by the LLM, then sends references and disclaimer as a final "done" event.
	 *
	 * <p>Writes SSE events directly to the response output stream in the request
	 * thread. This avoids the need for {@code SseEmitter}, background threads,
	 * async servlet support, and proxy privileges — the authenticated user's
	 * session is naturally available throughout the request.</p>
	 *
	 * <p>SSE event types:</p>
	 * <ul>
	 *   <li>{@code token} — a chunk of the answer text</li>
	 *   <li>{@code done} — final JSON with answer, references, and disclaimer</li>
	 *   <li>{@code error} — an error message if something goes wrong</li>
	 * </ul>
	 */
	@RequestMapping(value = "/search/stream", method = RequestMethod.POST)
	public void searchStream(@RequestBody Map<String, String> body,
			HttpServletResponse response) throws IOException {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_QUERY_PATIENT_DATA);

		String patientUuid = body.get("patient");
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

		// All validation passed — start SSE streaming.
		// Unwrap any response wrappers (e.g. Spring's ContentCachingResponseWrapper
		// from ShallowEtagHeaderFilter) that buffer the entire body, which would
		// prevent SSE tokens from streaming to the client in real time.
		HttpServletResponse unwrapped = response;
		while (unwrapped instanceof HttpServletResponseWrapper) {
			javax.servlet.ServletResponse inner =
					((HttpServletResponseWrapper) unwrapped).getResponse();
			if (inner instanceof HttpServletResponse) {
				unwrapped = (HttpServletResponse) inner;
			} else {
				break;
			}
		}

		response.setContentType("text/event-stream");
		response.setCharacterEncoding("UTF-8");
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("X-Accel-Buffering", "no");
		response.setHeader("Connection", "keep-alive");
		// Disable Tomcat's response buffer so tokens stream immediately
		// instead of accumulating in the default 8KB buffer.
		unwrapped.setBufferSize(0);

		final OutputStream out = unwrapped.getOutputStream();
		// Commit the response headers now so chunked transfer starts
		unwrapped.flushBuffer();

		String preFilterProp = Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_EMBEDDING_PRE_FILTER, "false");
		String searchMode = !"false".equalsIgnoreCase(preFilterProp.trim())
				? "pre-filter" : "full-chart";

		try {
			long startTime = System.currentTimeMillis();

			ChartAnswer chartAnswer = chartSearchService.searchStreaming(
					patient, sanitizedQuestion, new java.util.function.Consumer<String>() {
						@Override
						public void accept(String token) {
							try {
								writeSseEvent(out, "token", token);
							}
							catch (IOException e) {
								log.debug("Client disconnected during streaming");
								throw new RuntimeException("Client disconnected", e);
							}
						}
					});

			long responseTimeMs = System.currentTimeMillis() - startTime;

			ChartSearchAuditLog auditLog = new ChartSearchAuditLog();
			auditLog.setUser(user);
			auditLog.setPatient(patient);
			auditLog.setQuestion(sanitizedQuestion);
			auditLog.setAnswer(chartAnswer.getAnswer());
			auditLog.setReferenceCount(chartAnswer.getReferences().size());
			auditLog.setSearchMode(searchMode);
			auditLog.setResponseTimeMs(responseTimeMs);
			auditLog.setInputTokens(chartAnswer.getInputTokens() > 0 ? chartAnswer.getInputTokens() : null);
			auditLog.setOutputTokens(chartAnswer.getOutputTokens() > 0 ? chartAnswer.getOutputTokens() : null);
			auditLog.setDateCreated(new Date());
			try {
				auditLogService.saveAuditLog(auditLog);
			}
			catch (Exception e2) {
				log.warn("Failed to save audit log for streaming query", e2);
			}

			Map<String, Object> doneData = new HashMap<String, Object>();
			doneData.put("answer", chartAnswer.getAnswer());
			doneData.put("disclaimer", DISCLAIMER);

			List<Map<String, Object>> refs = new ArrayList<Map<String, Object>>();
			for (RecordReference ref : chartAnswer.getReferences()) {
				Map<String, Object> refMap = new LinkedHashMap<String, Object>();
				refMap.put("index", ref.getIndex());
				refMap.put("resourceType", ref.getResourceType());
				refMap.put("resourceUuid", ref.getResourceUuid());
				refMap.put("date", formatDate(ref.getDate()));
				refs.add(refMap);
			}
			doneData.put("references", refs);
			if (auditLog.getAuditLogId() != null) {
				doneData.put("questionId", String.valueOf(auditLog.getAuditLogId()));
			}

			writeSseEvent(out, "done", new ObjectMapper().writeValueAsString(doneData));
		}
		catch (ChartTooLargeException e) {
			log.warn("Chart too large for LLM context during streaming for patient [id={}]: {}",
					patient.getPatientId(), e.getMessage());
			try {
				writeSseEvent(out, "error",
						"This patient's chart is too large to process. "
								+ "Contact your administrator to increase the LLM context size.");
			}
			catch (IOException ioe) {
				log.debug("Could not send too-large error event, client likely disconnected");
			}
		}
		catch (IllegalStateException e) {
			log.error("Chart search configuration error during streaming", e);
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
				log.debug("Streaming ended due to client disconnect");
			} else {
				log.error("Chart search streaming failed for patient [id={}]",
						patient.getPatientId(), e);
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
			log.debug("Could not flush SSE stream, client likely disconnected");
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

		Integer auditLogId = Integer.valueOf(body.get("questionId").toString());
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
	// Multi-turn chat endpoints. The {@code /search} family above is single-shot;
	// these maintain a per-(patient, user) ChatSession that carries prior turns
	// into each LLM call. Persistence is handled by {@link ChatService}.
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

		ChatSession session = resolveOrOpenSession(patient, sessionUuid);

		// Unwrap any response wrappers that buffer the body (kills SSE liveness).
		HttpServletResponse unwrapped = response;
		while (unwrapped instanceof HttpServletResponseWrapper) {
			javax.servlet.ServletResponse inner =
					((HttpServletResponseWrapper) unwrapped).getResponse();
			if (inner instanceof HttpServletResponse) {
				unwrapped = (HttpServletResponse) inner;
			} else {
				break;
			}
		}

		response.setContentType("text/event-stream");
		response.setCharacterEncoding("UTF-8");
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("X-Accel-Buffering", "no");
		response.setHeader("Connection", "keep-alive");
		// Surface the session uuid BEFORE the stream opens — the client uses
		// it to pin all subsequent posts in this conversation.
		response.setHeader("X-ChartSearchAi-Session", session.getUuid());
		unwrapped.setBufferSize(0);

		final OutputStream out = unwrapped.getOutputStream();
		unwrapped.flushBuffer();

		try {
			ChatTurnResult result = chatService.chatStreaming(
					session, sanitizedQuestion, new java.util.function.Consumer<String>() {
						@Override
						public void accept(String token) {
							try {
								writeSseEvent(out, "token", token);
							}
							catch (IOException e) {
								log.debug("Client disconnected during chat streaming");
								throw new RuntimeException("Client disconnected", e);
							}
						}
					});

			ChartAnswer answer = result.getAnswer();
			Map<String, Object> doneData = new LinkedHashMap<String, Object>();
			doneData.put("answer", answer.getAnswer());
			doneData.put("disclaimer", DISCLAIMER);

			List<Map<String, Object>> refs = new ArrayList<Map<String, Object>>();
			for (RecordReference ref : answer.getReferences()) {
				Map<String, Object> refMap = new LinkedHashMap<String, Object>();
				refMap.put("index", ref.getIndex());
				refMap.put("resourceType", ref.getResourceType());
				refMap.put("resourceUuid", ref.getResourceUuid());
				refMap.put("date", formatDate(ref.getDate()));
				refs.add(refMap);
			}
			doneData.put("references", refs);
			doneData.put("blocks", blocksToJson(answer.getBlocks()));
			doneData.put("session", result.getSessionUuid());
			doneData.put("messageId", result.getAssistantMessageUuid());

			writeSseEvent(out, "done", new ObjectMapper().writeValueAsString(doneData));
		}
		catch (ChartTooLargeException e) {
			log.warn("Chart too large for chat streaming for patient [id={}]: {}",
					patient.getPatientId(), e.getMessage());
			try {
				writeSseEvent(out, "error",
						"This patient's chart is too large to process. "
								+ "Contact your administrator to increase the LLM context size.");
			}
			catch (IOException ioe) {
				log.debug("Could not send too-large error event, client likely disconnected");
			}
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
			} else {
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

		ChatSession session = resolveOrOpenSession(patient, sessionUuid);

		ChatTurnResult result;
		try {
			result = chatService.chat(session, question);
		}
		catch (ChartTooLargeException e) {
			log.warn("Chart too large for chat for patient [id={}]: {}",
					patient.getPatientId(), e.getMessage());
			return new ResponseEntity<Object>(
					errorResponse("This patient's chart is too large to process. "
							+ "Contact your administrator to increase the LLM context size."),
					HttpStatus.PAYLOAD_TOO_LARGE);
		}
		catch (Exception e) {
			log.error("Chat failed for patient [id={}]", patient.getPatientId(), e);
			return new ResponseEntity<Object>(
					errorResponse("Chart search failed. Please try again or contact your administrator."),
					HttpStatus.INTERNAL_SERVER_ERROR);
		}

		ChartAnswer answer = result.getAnswer();
		Map<String, Object> response = new LinkedHashMap<String, Object>();
		response.put("answer", answer.getAnswer());
		response.put("disclaimer", DISCLAIMER);

		List<Map<String, Object>> refs = new ArrayList<Map<String, Object>>();
		for (RecordReference ref : answer.getReferences()) {
			Map<String, Object> refMap = new LinkedHashMap<String, Object>();
			refMap.put("index", ref.getIndex());
			refMap.put("resourceType", ref.getResourceType());
			refMap.put("resourceUuid", ref.getResourceUuid());
			refMap.put("date", formatDate(ref.getDate()));
			refs.add(refMap);
		}
		response.put("references", refs);
		response.put("blocks", blocksToJson(answer.getBlocks()));
		response.put("session", result.getSessionUuid());
		response.put("messageId", result.getAssistantMessageUuid());

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
			entry.put("role", m.getRole());

			// Assistant rows persist a JSON envelope ({answer, blocks}); user
			// rows are plaintext. Parse JSON when present, surface prose +
			// blocks separately so the SPA can rehydrate the same view it
			// had during streaming. Legacy plaintext rows from before the
			// tabular iteration fall through with blocks=[].
			if (ChatMessage.ROLE_ASSISTANT.equals(m.getRole())) {
				String stored = m.getContent();
				String prose = stored;
				List<Object> blocks = new ArrayList<Object>();
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
					}
					catch (IOException ignored) {
						// Treat as plaintext.
					}
				}
				entry.put("content", prose);
				entry.put("blocks", blocks);
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
	 * Validates feedback input fields (questionId and rating). Returns an error
	 * message if validation fails, or null if the input is valid.
	 */
	static String validateFeedbackInput(Map<String, Object> body) {
		Object questionIdObj = body.get("questionId");
		if (questionIdObj == null) {
			return "questionId is required";
		}
		try {
			Integer.valueOf(questionIdObj.toString());
		}
		catch (NumberFormatException e) {
			return "Invalid questionId";
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
}
