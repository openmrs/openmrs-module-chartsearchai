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
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.util.DateFormatUtil;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.ChartTooLargeException;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.AuditLogService;
import org.openmrs.module.chartsearchai.api.PatientAccessCheck;
import org.openmrs.module.chartsearchai.api.impl.WarmupExecutor;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
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

	// Defense-in-depth: catches common prompt injection phrases. This is a blocklist
	// and can be bypassed with paraphrasing. The primary defense is the structured-output
	// constraint (response_format: json_schema, used by both engines) which forces LLM
	// output into a fixed {reasoning, answer, citations} shape regardless of prompt content.
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
			// null when grounding is disabled or could not run — clients must
			// render null as "unverified", never as "verified".
			refMap.put("grounded", ref.getGrounded());
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
	 *   <li>{@code thinking} — a chunk of the model's reasoning (chain-of-thought), emitted
	 *       before the answer; render distinctly (e.g. a collapsible panel), not as the answer</li>
	 *   <li>{@code token} — a chunk of the answer text</li>
	 *   <li>{@code references} — the answer's citations the moment the answer is complete,
	 *       before grounding verdicts exist; render as unverified until verdicts arrive</li>
	 *   <li>{@code done} — final JSON with answer, references, questionId, and disclaimer.
	 *       With async grounding off (the default) the references carry their grounding
	 *       verdicts; with {@code chartsearchai.grounding.async=true} they do not yet</li>
	 *   <li>{@code grounded} — only with async grounding: the references re-sent with their
	 *       grounding verdicts attached, after the Tier-2 verification tail completes; carries
	 *       the same {@code questionId} as {@code done}. Clients must keep consuming the stream
	 *       after {@code done} to receive it</li>
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

		streamAnswer(out, patient, sanitizedQuestion, user, searchMode, isAsyncGroundingActive());
	}

	/**
	 * Whether the streaming endpoint should emit {@code done} before the grounding pass and
	 * deliver verdicts in a trailing {@code grounded} event. Only meaningful when grounding
	 * itself is on — with grounding disabled there is no tail to move off the response path.
	 * Resolved here (not in {@link #streamAnswer}) so the orchestration stays free of
	 * {@code Context} reads and unit-testable.
	 */
	private static boolean isAsyncGroundingActive() {
		return ChartSearchAiUtils.isGroundingAsyncEnabled() && ChartSearchAiUtils.isGroundingEnabled();
	}

	/**
	 * The SSE orchestration for one streaming search: runs the service call with the token /
	 * thinking / references channels wired to the output stream, then emits the terminal events.
	 *
	 * <p>With {@code asyncGrounding} off, the classic shape: one {@code done} event after the
	 * service returns, carrying the grounded references. With it on, {@code done} is emitted the
	 * moment the answer is complete (references without verdicts, audit row already saved so
	 * {@code questionId} is present) and a trailing {@code grounded} event delivers the
	 * verdict-annotated references once verification finishes — the user's perceived completion
	 * no longer waits out the grounding tail. If the service returns without ever surfacing an
	 * ungrounded answer (a cache hit returns an already-final answer), the classic {@code done}
	 * is emitted instead and no {@code grounded} event follows.</p>
	 *
	 * <p>Package-private and free of {@code Context} reads so event-order behavior is unit-tested
	 * directly (see {@code ChartSearchAiStreamEventOrderTest}); {@code searchStream} resolves all
	 * configuration before delegating here.</p>
	 */
	void streamAnswer(final OutputStream out, Patient patient, String sanitizedQuestion, User user,
			String searchMode, boolean asyncGrounding) {
		try {
			long startTime = System.currentTimeMillis();

			// Carries the early-done state from the consumer (fired mid-call) to the post-return
			// code: [0] = the saved questionId (null if audit failed), and whether done was sent
			// is tracked by earlyDoneSent. Single-element arrays because the consumer lambda needs
			// effectively-final capture.
			final String[] earlyQuestionId = new String[1];
			final boolean[] earlyDoneSent = new boolean[1];

			// Async grounding: the moment the (not yet grounding-verified) answer exists, persist
			// the audit row and emit "done" — the user's perceived completion no longer waits out
			// the grounding tail. The audit's responseTimeMs deliberately measures to THIS point
			// (what the user experienced); the [timing] service log still carries groundMs.
			// Serialization + write failures unwind like any mid-stream disconnect, via the same
			// RuntimeException(IOException) shape writeSseEventOrThrow uses.
			Consumer<ChartAnswer> ungroundedConsumer = !asyncGrounding ? ungrounded -> { }
					: ungrounded -> {
						if (earlyDoneSent[0]) {
							// Interface contract is at-most-once; stay idempotent anyway — a
							// duplicate done would corrupt every client's completion handling.
							log.warn("Ungrounded-answer consumer fired more than once; ignoring");
							return;
						}
						earlyQuestionId[0] = saveAuditLog(user, patient, sanitizedQuestion,
								ungrounded, searchMode, System.currentTimeMillis() - startTime);
						try {
							writeSseEvent(out, "done",
									doneEventJson(ungrounded, earlyQuestionId[0]));
						}
						catch (IOException e) {
							log.debug("Client disconnected during streaming (done)");
							throw new RuntimeException("Client disconnected", e);
						}
						earlyDoneSent[0] = true;
					};

			// Four channels: "token" carries the answer; "thinking" carries the model's reasoning
			// (chain-of-thought), emitted first so the UI can show live progress and the rationale
			// instead of a dead spinner; "references" carries the answer's citations the moment the
			// answer is done — BEFORE the grounding pass — so the UI can render clickable citations
			// immediately and not wait on Tier-2 verification. The terminal events re-send the
			// references with grounding verdicts attached: in the classic shape on "done", or — when
			// async grounding is active — on a trailing "grounded" event after an early "done". The
			// frontend must render "thinking" distinctly (e.g. a collapsible panel), never as the
			// answer; citations must show as unverified until verdicts arrive. All unwind on client
			// disconnect via writeSseEventOrThrow.
			ChartAnswer chartAnswer = chartSearchService.searchStreaming(
					patient, sanitizedQuestion,
					token -> writeSseEventOrThrow(out, "token", token),
					reasoning -> writeSseEventOrThrow(out, "thinking", reasoning),
					citations -> sendReferencesEvent(out, citations),
					ungroundedConsumer);

			if (!earlyDoneSent[0]) {
				// Classic shape: async off, or the service returned an already-final answer (cache
				// hit) without surfacing an ungrounded stage — audit and emit the single done.
				String questionId = saveAuditLog(user, patient, sanitizedQuestion, chartAnswer,
						searchMode, System.currentTimeMillis() - startTime);
				writeSseEvent(out, "done", doneEventJson(chartAnswer, questionId));
			} else {
				// done already went out before grounding; deliver the verdicts in the trailing
				// "grounded" event. Same reference serialization as done, so the client can
				// replace its reference list wholesale; questionId correlates the two events.
				Map<String, Object> groundedData = new HashMap<String, Object>();
				groundedData.put("references", serializeReferences(chartAnswer.getReferences()));
				if (earlyQuestionId[0] != null) {
					groundedData.put("questionId", earlyQuestionId[0]);
				}
				writeSseEvent(out, "grounded", new ObjectMapper().writeValueAsString(groundedData));
			}
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

	/**
	 * Persists the audit row for a streaming answer and returns its id as the client-facing
	 * {@code questionId}, or {@code null} when the save failed — audit failures are logged and
	 * never break the response, exactly as before the async-grounding split. Shared by the
	 * classic post-return path and the async early-{@code done} path so both emit identical
	 * audit rows and {@code done} payloads.
	 */
	private String saveAuditLog(User user, Patient patient, String question, ChartAnswer answer,
			String searchMode, long responseTimeMs) {
		ChartSearchAuditLog auditLog = new ChartSearchAuditLog();
		auditLog.setUser(user);
		auditLog.setPatient(patient);
		auditLog.setQuestion(question);
		auditLog.setAnswer(answer.getAnswer());
		auditLog.setReferenceCount(answer.getReferences().size());
		auditLog.setSearchMode(searchMode);
		auditLog.setResponseTimeMs(responseTimeMs);
		auditLog.setInputTokens(answer.getInputTokens() > 0 ? answer.getInputTokens() : null);
		auditLog.setOutputTokens(answer.getOutputTokens() > 0 ? answer.getOutputTokens() : null);
		auditLog.setDateCreated(new Date());
		try {
			auditLogService.saveAuditLog(auditLog);
		}
		catch (Exception e) {
			log.warn("Failed to save audit log for streaming query", e);
		}
		return auditLog.getAuditLogId() != null ? String.valueOf(auditLog.getAuditLogId()) : null;
	}

	/** Serializes the {@code done} event payload: answer, disclaimer, references, questionId. */
	private String doneEventJson(ChartAnswer answer, String questionId) throws IOException {
		Map<String, Object> doneData = new HashMap<String, Object>();
		doneData.put("answer", answer.getAnswer());
		doneData.put("disclaimer", DISCLAIMER);
		doneData.put("references", serializeReferences(answer.getReferences()));
		if (questionId != null) {
			doneData.put("questionId", questionId);
		}
		return new ObjectMapper().writeValueAsString(doneData);
	}

	/** Test seam: production wires {@link ChartSearchService} via {@code Autowired}. */
	void setChartSearchService(ChartSearchService chartSearchService) {
		this.chartSearchService = chartSearchService;
	}

	/** Test seam: production wires {@link AuditLogService} via {@code Autowired}. */
	void setAuditLogService(AuditLogService auditLogService) {
		this.auditLogService = auditLogService;
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

	/**
	 * Serializes references to the SSE wire shape shared by the early {@code references} event
	 * (grounding verdicts not yet attached) and the final {@code done} event (grounded).
	 * {@code grounded} is null when grounding is disabled or could not run — clients must render
	 * null as "unverified", never as "verified".
	 */
	private List<Map<String, Object>> serializeReferences(List<RecordReference> references) {
		List<Map<String, Object>> refs = new ArrayList<Map<String, Object>>();
		for (RecordReference ref : references) {
			Map<String, Object> refMap = new LinkedHashMap<String, Object>();
			refMap.put("index", ref.getIndex());
			refMap.put("resourceType", ref.getResourceType());
			refMap.put("resourceUuid", ref.getResourceUuid());
			refMap.put("date", formatDate(ref.getDate()));
			refMap.put("grounded", ref.getGrounded());
			refs.add(refMap);
		}
		return refs;
	}

	/**
	 * Emits the {@code references} SSE event carrying the answer's citations before the grounding
	 * pass completes, so the UI can render clickable citations without waiting on Tier-2
	 * verification. A serialization failure is non-fatal — the final {@code done} event re-sends the
	 * references with verdicts — but a client disconnect during the write unwinds the stream like the
	 * other channels (via {@link #writeSseEventOrThrow}).
	 */
	private void sendReferencesEvent(OutputStream out, List<RecordReference> references) {
		String json;
		try {
			Map<String, Object> data = new HashMap<String, Object>();
			data.put("references", serializeReferences(references));
			json = new ObjectMapper().writeValueAsString(data);
		}
		catch (IOException e) {
			log.warn("Could not serialize early references event; the final done event still carries them", e);
			return;
		}
		writeSseEventOrThrow(out, "references", json);
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
	 * Writes an SSE event, converting a client-disconnect {@link IOException} into the
	 * {@link RuntimeException} the streaming loop unwinds on. Shared by the answer ({@code token})
	 * and reasoning ({@code thinking}) channels so both handle a mid-stream disconnect identically.
	 */
	private void writeSseEventOrThrow(OutputStream out, String event, String data) {
		try {
			writeSseEvent(out, event, data);
		}
		catch (IOException e) {
			log.debug("Client disconnected during streaming ({})", event);
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
}
