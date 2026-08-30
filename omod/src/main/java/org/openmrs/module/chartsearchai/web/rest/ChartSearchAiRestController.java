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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
import org.openmrs.module.chartsearchai.api.impl.PrewarmBootstrapService;
import org.openmrs.module.chartsearchai.api.impl.PrewarmStatus;
import org.openmrs.module.chartsearchai.api.impl.WarmupExecutor;
import org.openmrs.module.chartsearchai.model.ChartSearchAuditLog;
import org.openmrs.module.chartsearchai.reference.DrugReferenceService;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.util.PrivilegeConstants;
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

	/**
	 * The keep-alive frame written while the answer is still being generated: an SSE comment, which
	 * the spec requires a client to ignore, so this needs no client change and can raise no phantom
	 * event in the UI.
	 */
	private static final String SSE_KEEP_ALIVE_COMMENT = ": keep-alive\n\n";

	/**
	 * How often {@link #streamAnswer} writes {@link #SSE_KEEP_ALIVE_COMMENT} until the answer is
	 * finished.
	 *
	 * <p>Fifteen seconds, to sit well inside the read timeouts this module gets deployed behind:
	 * nginx's {@code proxy_read_timeout} defaults to 60s and Cloudflare closes a silent origin
	 * connection at ~120s. Deliberately not a global property — the only requirement on this number
	 * is that it be smaller than every such default, and one large enough to need tuning would
	 * already be broken.</p>
	 *
	 * <p>Measured on the chartsearchai.openmrs.org demo 2026-08-19, which is why this exists: with
	 * Gemma 4 E4B served on that 4-core box, first output arrived after the edge's window and every
	 * query was closed at ~125s having delivered ZERO bytes — non-streaming {@code /search} as a
	 * Cloudflare 524 and the stream as a dropped connection carrying nothing. E2B, whose first
	 * {@code thinking} lands at 27-38s, completed the same question at 149-154s. What decides
	 * whether a long answer survives is therefore whether something is written EARLY, not whether
	 * the answer finishes inside the window — so this keep-alive, not a faster model, is the fix.</p>
	 */
	private static final long KEEP_ALIVE_INTERVAL_MS = 15000L;

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

	@Autowired
	@Qualifier("chartSearchAi.prewarmBootstrapService")
	private PrewarmBootstrapService prewarmBootstrapService;

	@Autowired
	@Qualifier("chartSearchAi.drugReferenceService")
	private DrugReferenceService drugReferenceService;

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

		// One row builder for both request paths. Issue #178 removed the last thing that made this
		// site and the streaming one differ — each derived the search mode for itself — and what was
		// left was the same eleven setters written twice. Two sites that must agree about a row's
		// contents, and agree only by being kept in step by hand, is the structural condition that
		// let search_mode hold one value for 6036 rows; the next column added would land in one of
		// them and be silently absent from the other, exactly as cachedTokens is absent from both.
		String questionId = saveAuditLog(user, patient, question, chartAnswer, responseTimeMs);

		Map<String, Object> response = new HashMap<String, Object>();
		response.put("answer", chartAnswer.getAnswer());
		response.put("disclaimer", DISCLAIMER);

		// Shared with the SSE emission sites so all four stay in step — carries the `grounded`
		// verdict (withheld for reference material, see groundedForWire) and the `group`
		// discriminator; see serializeReferences.
		response.put("references", serializeReferences(chartAnswer.getReferences()));
		response.put("safetyWarnings", serializeSafetyWarnings(chartAnswer.getSafetyWarnings()));
		if (questionId != null) {
			response.put("questionId", questionId);
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
	 * Trigger (or steer) the background prewarm bootstrap: a resumable sweep that pre-fills and pins
	 * every patient's chart KV cache, so a first query on a patient never opened this process is still
	 * warm. Body (all optional): {@code {"scope":"all","action":"start|restart|stop"}}. Returns
	 * 202 Accepted with the current status snapshot; the sweep runs on a background daemon thread.
	 * Requires the {@code Manage AI Prewarm} privilege (a system operation, not a clinical one), and
	 * is gated by {@code chartsearchai.prewarm.enabled}.
	 */
	@RequestMapping(value = "/prewarm", method = RequestMethod.POST)
	@ResponseBody
	public ResponseEntity<Object> prewarm(@RequestBody(required = false) Map<String, String> body) {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_MANAGE_PREWARM);
		Map<String, String> b = body == null ? Collections.<String, String> emptyMap() : body;
		String scope = b.get("scope");
		// Only the full-database sweep is implemented. Reject any other scope explicitly rather than
		// silently running an "all" sweep — passing e.g. scope=queue must not surprise the caller with
		// a full-DB prefill. ("all" and blank both mean all.)
		if (scope != null && !scope.trim().isEmpty()
				&& !PrewarmBootstrapService.SCOPE_ALL.equalsIgnoreCase(scope.trim())) {
			return new ResponseEntity<Object>(
					errorResponse("Unsupported scope '" + scope + "'. Only 'all' is supported."),
					HttpStatus.BAD_REQUEST);
		}
		PrewarmStatus status = prewarmBootstrapService.trigger(scope, b.get("action"));
		return new ResponseEntity<Object>(status.toMap(), HttpStatus.ACCEPTED);
	}

	/**
	 * Current prewarm-bootstrap status: run state, scope, totals, the resume cursor, and the on-disk
	 * pinned-entry count. Requires the {@code Manage AI Prewarm} privilege.
	 */
	@RequestMapping(value = "/prewarmstatus", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> prewarmStatus() {
		Context.requirePrivilege(ChartSearchAiConstants.PRIV_MANAGE_PREWARM);
		return new ResponseEntity<Object>(prewarmBootstrapService.getStatus().toMap(), HttpStatus.OK);
	}

	/**
	 * Which drug-reference dataset this module is <em>actually</em> using: {@code
	 * {enabled, loaded, inert, entryCount, sourceFormat, configuredSourceFormat,
	 * configuredDataFilePath, origin, findings, arms, crossReactivity}}.
	 *
	 * <p>Exists because the answer cannot be got from the log (issue #149). The dataset load is lazy
	 * and cached for the life of the module, so the most recent {@code "Loaded N …"} line may belong
	 * to a load performed before the global properties were last edited, or to a previous process
	 * that a failed restart left running — which is how a verification pass concluded it had switched
	 * {@code sourceFormat} when it had not. Reading this endpoint performs the load if it has not
	 * happened yet and otherwise reports the cached one, so what it returns is what the drug-safety
	 * layer is using at the moment of the call. {@code origin} distinguishes the operator file from
	 * the bundled fallback, which an entry count alone cannot.
	 *
	 * <p>When {@code chartsearchai.drugReference.enabled} is off, reports {@code enabled:false} and
	 * loads nothing — the feature being switched off is a legitimate state, and polling a status
	 * endpoint must not be what triggers a 19 MB parse (or the inert warning) on an install that does
	 * not use it. Both datasets honour that: the {@code crossReactivity} section reports
	 * {@code loaded:false} without parsing the groups file either.
	 *
	 * <p><b>{@code crossReactivity} is the SECOND dataset</b>, added by issue #266. The curated
	 * cross-reactivity groups load from a global property of their own, alongside whatever
	 * {@code sourceFormat} is in force, and until that issue their validity findings reached only the
	 * log — so {@code configured-data-file-not-read} for that file was invisible here, on the one channel
	 * that can answer after a lazy load. Its own subsection ({@code loaded, groupCount,
	 * configuredFilePath, origin, findings}) rather than rows in the top-level {@code findings}, because a
	 * finding naming a file has to be read beside the file it is about; see ADR Decision 48. Everything
	 * the top-level {@code findings} says about the two channels applies to it unchanged.
	 *
	 * <p>Gated on the core {@code Get Global Properties} privilege rather than a clinical one: this
	 * reports what the drug-reference global properties actually produced and carries no patient data,
	 * so it needed no new privilege for an operator (or an administrator diagnosing a silent safety
	 * layer) to read it. Note what that admits: the {@code Authenticated} role holds that privilege by
	 * default on a Reference Application install, so in practice any logged-in user can read this —
	 * which is why the body is confined to configuration metadata such a user can already read through
	 * {@code GET /systemsetting}, and why {@code origin} names the operator file relative to the
	 * application data directory rather than absolutely (core keeps the absolute path behind
	 * {@code View Administration Functions}). Anything patient-derived, or the absolute layout of the
	 * server, does not belong in this response.
	 */
	@RequestMapping(value = "/drugreferencestatus", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<Object> drugReferenceStatus() {
		Context.requirePrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("enabled", ChartSearchAiUtils.isDrugReferenceEnabled());
		body.putAll(drugReferenceService.getLoadStatus().toMap());
		// APPENDED after the entry load's own keys, never inserted among them: the endpoint's field list
		// is asserted as an ORDERED list, and appending is what keeps that assertion order-sensitive.
		body.put("crossReactivity", drugReferenceService.getCrossReactivityLoadStatus().toMap());
		return new ResponseEntity<Object>(body, HttpStatus.OK);
	}

	/**
	 * Streaming search endpoint using Server-Sent Events. Streams tokens as they are
	 * generated by the LLM, then sends references and disclaimer as a final "done" event.
	 *
	 * <p>Writes SSE events directly to the response output stream in the request thread, which is
	 * where OpenMRS binds authentication. This avoids the need for {@code SseEmitter}, async servlet
	 * support, and proxy privileges — the authenticated user's session is naturally available
	 * throughout the request. It is NOT "no background threads": {@code SseKeepAlive} runs a timer
	 * that writes comment frames so a reverse proxy never sees a read-idle connection. What holds is
	 * the narrower thing — no thread but the request thread does OpenMRS WORK, and that timer reads
	 * no {@code Context} — which is the scope
	 * {@code ChartSearchAiStreamingTest.streamingEndpoint_shouldNotRunOpenmrsWorkOnBackgroundThreads}
	 * states and enforces.</p>
	 *
	 * <p>SSE event types:</p>
	 * <ul>
	 *   <li>{@code preliminary} — only with {@code chartsearchai.progressiveReasoning.enabled}: a
	 *       chunk of the fast PREVIEW reasoning over the focused top-K chart, streamed before the
	 *       full-chart answer. Render as provisional (clearly an in-progress preview, not the answer)
	 *       and REPLACE it when the first {@code thinking} (or {@code token}) event arrives — the
	 *       preview can be wrong until the committed full-chart pass corrects it</li>
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
	 *
	 * <p>Between those events the stream also carries SSE <em>comments</em> — lines opening with
	 * {@code :}, one written before generation begins and one every {@code KEEP_ALIVE_INTERVAL_MS}
	 * until the answer is finished. They are not events and carry no data, so a client must skip any
	 * line beginning with {@code :} rather than read it as a frame, with whatever parser it uses:
	 * {@code EventSource} would do that for it, but it issues a GET and sends no body, so it cannot
	 * reach this POST endpoint. Their only job is to stop a reverse proxy closing a connection it has
	 * read nothing on; README's Streaming search (SSE) section carries the read timeouts and the demo
	 * measurements behind them.</p>
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

		streamAnswer(out, patient, sanitizedQuestion, user, isAsyncGroundingActive());
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
	 * configuration before delegating here. The audit row's search mode is NOT among that
	 * configuration: it is stated by the answer the pipeline returns (issue #178), so there is no
	 * parameter for a caller to get wrong and no second derivation to drift from the first.</p>
	 */
	void streamAnswer(final OutputStream out, Patient patient, String sanitizedQuestion, User user,
			boolean asyncGrounding) {
		streamAnswer(out, patient, sanitizedQuestion, user, asyncGrounding, KEEP_ALIVE_INTERVAL_MS);
	}

	/**
	 * As {@link #streamAnswer(OutputStream, Patient, String, User, boolean)}, with the keep-alive
	 * interval given rather than taken from {@link #KEEP_ALIVE_INTERVAL_MS}.
	 *
	 * <p>The interval is a parameter so the periodic writes can be OBSERVED in a test without
	 * waiting a production interval out — the alternative, a mutable field, would put the value on a
	 * Spring singleton where one request could change another's. Production callers use the five-arg
	 * form; nothing but a test should pass this.</p>
	 *
	 * @param keepAliveIntervalMillis how often to write {@link #SSE_KEEP_ALIVE_COMMENT} until the
	 *        answer is finished
	 */
	void streamAnswer(final OutputStream out, Patient patient, String sanitizedQuestion, User user,
			boolean asyncGrounding, long keepAliveIntervalMillis) {
		final SseKeepAlive keepAlive = SseKeepAlive.start(out, keepAliveIntervalMillis);
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
								ungrounded, System.currentTimeMillis() - startTime);
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

			// Five channels: "token" carries the answer; "thinking" carries the committed full-chart
			// reasoning (chain-of-thought), emitted first so the UI can show live progress and the
			// rationale instead of a dead spinner; "preliminary" carries the optional progressive
			// preview reasoning (only when progressiveReasoning.enabled) — streamed ahead of, and to
			// be REPLACED by, "thinking"; "references" carries the answer's citations the moment the
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
					ungroundedConsumer,
					preliminary -> writeSseEventOrThrow(out, "preliminary", preliminary));

			if (!earlyDoneSent[0]) {
				// Classic shape: async off, or the service returned an already-final answer (cache
				// hit) without surfacing an ungrounded stage — audit and emit the single done.
				String questionId = saveAuditLog(user, patient, sanitizedQuestion, chartAnswer,
						System.currentTimeMillis() - startTime);
				writeSseEvent(out, "done", doneEventJson(chartAnswer, questionId));
			} else {
				// done already went out before grounding; deliver the verdicts in the trailing
				// "grounded" event. Same reference serialization as done, so the client can
				// replace its reference list wholesale; questionId correlates the two events.
				Map<String, Object> groundedData = new HashMap<String, Object>();
				groundedData.put("references", serializeReferences(chartAnswer.getReferences()));
				groundedData.put("safetyWarnings", serializeSafetyWarnings(chartAnswer.getSafetyWarnings()));
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
		finally {
			// Every exit stops the timer, including a client disconnect, which unwinds through the
			// catch above rather than returning — the case ChartSearchAiStreamKeepAliveTest's
			// aClientDisconnectStopsTheTimerToo holds, since the happy-path test beside it cannot
			// tell this finally from a statement at the tail of the try block and stayed green when
			// the two were swapped. Once stop() returns, no keep-alive can be in flight or begin (see
			// SseKeepAlive.stop) — which is what lets the flush below take no lock. A comment can
			// still land after the terminal event, because a task parked on the monitor during the
			// final write may take it before stop() does. That is harmless: a comment carries no
			// data, and with async grounding a client already has to keep reading past done. What
			// must not happen is a write after this method returns, which is the window
			// SseKeepAlive's stopped flag closes — shutdownNow alone cannot, since interrupting a
			// thread parked on a monitor does nothing.
			keepAlive.stop();
		}

		try {
			out.flush();
		}
		catch (IOException e) {
			log.debug("Could not flush SSE stream, client likely disconnected");
		}
	}

	/**
	 * Persists the audit row for one answer and returns its id as the client-facing
	 * {@code questionId}, or {@code null} when the save failed — audit failures are logged and never
	 * break the response, exactly as before the async-grounding split.
	 *
	 * <p><b>The only place a row is built.</b> All three write sites go through here: the blocking
	 * {@code /search} handler, the streaming classic post-return path, and the streaming async
	 * early-{@code done} path. Before issue #178 the blocking site had its own copy of these
	 * setters, and the one expression that differed between the copies — how each derived
	 * {@code searchMode} — is the whole of that issue. The mode now travels on the answer, which left
	 * two identical copies; keeping them as copies would leave the next column added to this table
	 * present in one row shape and silently absent from the other, which is the same defect wearing
	 * a different field's name.
	 *
	 * <p>The mode is read off {@code answer}, never re-derived here, so the row states what the
	 * pipeline actually did rather than what a global property says at write time.
	 */
	private String saveAuditLog(User user, Patient patient, String question, ChartAnswer answer,
			long responseTimeMs) {
		ChartSearchAuditLog auditLog = new ChartSearchAuditLog();
		auditLog.setUser(user);
		auditLog.setPatient(patient);
		auditLog.setQuestion(question);
		auditLog.setAnswer(answer.getAnswer());
		auditLog.setReferenceCount(answer.getReferences().size());
		// Written from the answer, never re-derived here, for the reason the mode is: by the time this
		// runs the chart the slice was measured on is gone. A null slice files two nulls rather than
		// two zeros — see ChartAnswer.getReferenceSlice() for what the distinction is and why the
		// columns are nullable (issue #229). Note that setInputTokens/setOutputTokens below do NOT
		// follow that rule — they collapse a measured 0 to null — so this table is not uniform about
		// it and a reader must not generalise either convention to the other. Named rather than
		// located: a line count is a claim about layout that the next insertion falsifies, and this
		// one already had.
		ChartSearchAiUtils.ReferenceSlice referenceSlice = answer.getReferenceSlice();
		auditLog.setReferenceSliceRecords(referenceSlice == null ? null : referenceSlice.getRecords());
		auditLog.setReferenceSliceChars(referenceSlice == null ? null : referenceSlice.getCharacters());
		auditLog.setSearchMode(answer.getSearchMode());
		auditLog.setResponseTimeMs(responseTimeMs);
		auditLog.setInputTokens(answer.getInputTokens() > 0 ? answer.getInputTokens() : null);
		auditLog.setOutputTokens(answer.getOutputTokens() > 0 ? answer.getOutputTokens() : null);
		auditLog.setDateCreated(new Date());
		try {
			auditLogService.saveAuditLog(auditLog);
		}
		catch (Exception e) {
			log.warn("Failed to save audit log", e);
		}
		return auditLog.getAuditLogId() != null ? String.valueOf(auditLog.getAuditLogId()) : null;
	}

	/** Serializes the {@code done} event payload: answer, disclaimer, references, questionId. */
	private String doneEventJson(ChartAnswer answer, String questionId) throws IOException {
		Map<String, Object> doneData = new HashMap<String, Object>();
		doneData.put("answer", answer.getAnswer());
		doneData.put("disclaimer", DISCLAIMER);
		doneData.put("references", serializeReferences(answer.getReferences()));
		doneData.put("safetyWarnings", serializeSafetyWarnings(answer.getSafetyWarnings()));
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

	/** Test seam: production wires {@link PatientAccessCheck} via {@code Autowired}. */
	void setPatientAccessCheck(PatientAccessCheck patientAccessCheck) {
		this.patientAccessCheck = patientAccessCheck;
	}

	/** Test seam: production wires {@link DrugReferenceService} via {@code Autowired}. */
	void setDrugReferenceService(DrugReferenceService drugReferenceService) {
		this.drugReferenceService = drugReferenceService;
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
			// The prompt COST beside the answer's USE of it: referenceCount is the citations in the
			// answer, these two are the reference material put in front of the model, most of which
			// is never cited. Published here because the point of issue #229 is that the size was
			// unreadable without a log level nobody can durably set.
			entry.put("referenceSliceRecords", auditLog.getReferenceSliceRecords());
			entry.put("referenceSliceChars", auditLog.getReferenceSliceChars());
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
	 * Serializes references to the wire shape shared by every emission site: the {@code /search}
	 * response, the early {@code references} SSE event (grounding verdicts not yet attached), the
	 * final {@code done} event (grounded) and the trailing {@code grounded} event of the async
	 * path. One implementation so a field added here cannot reach some clients and not others.
	 *
	 * <p>{@code grounded} is null when grounding is disabled, could not run, or ran and could not
	 * certify the citation (a compound claim unit under entailment, issue #302; or the judge's
	 * negative on a composite claim, issue #284) — clients must render
	 * null as "unverified", never as "verified". It is ALSO null, unconditionally, for a
	 * {@code reference}-group citation: see {@link #groundedForWire}.
	 *
	 * <p>{@code group} classifies each reference as chart evidence or module-supplied reference
	 * prose (see {@link ChartSearchAiUtils#referenceGroup}), and the list is ordered so the groups
	 * are contiguous with chart evidence first — a client that simply renders the array in order
	 * gets the grouping for free, and one that buckets by {@code group} gets stable buckets. The
	 * sort is stable, so within a group the order established upstream is preserved. It reorders
	 * only the serialized view; {@code index} remains each record's citation number, so inline
	 * {@code [N]} markers in the answer prose keep resolving.
	 *
	 * <p>The sort is narrower in effect than it looks, which is worth knowing before anyone
	 * removes it as redundant: {@code LlmInferenceService.extractCitedReferences} already sorts
	 * date-descending with nulls LAST, and an injected drug-reference record always carries a null
	 * date — so it usually lands at the tail unaided. What this sort actually fixes is the case
	 * where chart records are ALSO null-dated and can therefore sort after it: an allergy, whose
	 * querystore date is administrative and deliberately not rendered. That is not a corner case —
	 * it is exactly the shape of a drug-safety answer, the one place drug-reference records get
	 * cited at all.
	 */
	private List<Map<String, Object>> serializeReferences(List<RecordReference> references) {
		// The copy is load-bearing — do NOT sort `references` in place.
		//
		// Every ChartAnswer-derived path would throw outright: ChartAnswer wraps its references in
		// an unmodifiableList, and that includes each cache hit, which replays the cached answer's
		// own list.
		//
		// On the streaming path the failure would instead be silent. The early "references" event
		// is handed the very list object LlmInferenceService still owns and reuses for its
		// grounding pass, where Tier-2's per-answer cap is allocated walking the list in order.
		// Today's permutation happens not to shift which citations get verified — it is a stable
		// partition on group alone, so chart citations keep their relative order, and
		// reference-group citations never consume the entailment budget anyway (the #106
		// demote-only carve-out). A comparator that later gained a second key would shift cap
		// membership, and nothing would report it.
		List<RecordReference> ordered = new ArrayList<RecordReference>(references);
		// Stable sort on the group rank alone: chart evidence before reference material, with the
		// upstream order untouched inside each group.
		Collections.sort(ordered, Comparator.comparingInt(
			(RecordReference ref) -> groupRank(ref.getResourceType())));

		List<Map<String, Object>> refs = new ArrayList<Map<String, Object>>();
		for (RecordReference ref : ordered) {
			Map<String, Object> refMap = new LinkedHashMap<String, Object>();
			refMap.put("index", ref.getIndex());
			refMap.put("resourceType", ref.getResourceType());
			refMap.put("resourceUuid", ref.getResourceUuid());
			refMap.put("date", formatDate(ref.getDate()));
			refMap.put("grounded", groundedForWire(ref));
			refMap.put("group", ChartSearchAiUtils.referenceGroup(ref.getResourceType()));
			// Citation metadata, for rendering beside the chip: where the cited record came from,
			// and how many of its interaction partners the cited record does not name (usually because
			// they are not relevant to this patient, not because they did not fit). Both used to be
			// appended to the record text itself, where the model recited them into the answer
			// (issue #117) — they are fields so the model has nothing to quote. `source` is null and
			// `withheldInteractions` 0 for a chart record, which is that record's real shape.
			refMap.put("source", ref.getSource());
			refMap.put("withheldInteractions", ref.getWithheldInteractions());
			refs.add(refMap);
		}
		return refs;
	}

	/**
	 * The grounding verdict this citation may publish: the pipeline's verdict for chart evidence,
	 * and nothing at all — always {@code null} — for module-supplied reference material.
	 *
	 * <p>Grounding treats reference material as DEMOTE-ONLY: a pass is withheld as {@code null}
	 * because a recitation of module-rendered prose embeds near-identically to its source even when
	 * it swaps subject roles (#106), while a Tier-1 off-topic citation still yields {@code false}.
	 * That surviving {@code false} was kept on the wire because it carries information — it says the
	 * citation is not about the record — and it is that value issue #201 removes.
	 *
	 * <p>The reason is that no client had a correct reading of it. The verdict's meaning here is
	 * "off-topic citation", not "unsupported claim", and distinguishing the two requires reading
	 * {@code group}. The reference frontend classified by {@code resourceType} instead (measured on
	 * #201 against {@code openmrs-esm-chartsearchai} at {@code 3003cd2}, which does not declare
	 * {@code group} on its reference type at all), so a {@code safety_finding} fell through to its
	 * grounding branch and rendered <em>"Unsupported — The cited record may not support this
	 * statement"</em>, in red, on this module's own deterministic Major-interaction finding. Both
	 * settlements offered on #201 would fix that one client; the one taken is this, because a field
	 * that must not be interpreted is a trap and withholding it holds for every client rather than
	 * for the one that is patched. Nothing about the grounding pass changes: the verifier still
	 * computes the verdict and {@code RecordReference.getGrounded()} still carries it. Only its
	 * publication stops here.
	 *
	 * <p>{@code null} rather than an omitted key: {@code null} is already this field's documented
	 * value for "grounding disabled or could not run", clients are already instructed to render it
	 * as unverified and never as verified, and the key's unconditional presence is a property
	 * clients rely on. Omitting it would invent a third state and break more for no gain.
	 *
	 * <p>Derived through {@link ChartSearchAiUtils#isGroundingDemoteOnly}, never from a list of
	 * type names — the enumerated form is what left {@code safety_finding} out of the grounding
	 * carve-out for two releases (#122), and it is the same mistake the client above made. So the
	 * group this withholding is keyed on is the same one the {@code group} field publishes, and a
	 * reference type added later is withheld without anyone remembering this method. A hardcoded
	 * pair would agree with the classifier on every type that exists today, so it is
	 * {@code ChartSearchAiReferenceGroundingWithholdingTest}'s compiled-class guard, not its
	 * behavioural sweep, that would catch it.
	 */
	private static Boolean groundedForWire(RecordReference ref) {
		return ChartSearchAiUtils.isGroundingDemoteOnly(ref.getResourceType())
				? null
				: ref.getGrounded();
	}

	/**
	 * Render order of the reference groups: chart evidence first, module-supplied reference
	 * material last. Adding a group constant without adding it here would leave its entries
	 * ranked as unknown, so {@code ChartSearchAiReferenceGroupingTest} asserts this list covers
	 * every declared {@code REFERENCE_GROUP_*} constant. Package-private for that test only.
	 */
	static final List<String> REFERENCE_GROUP_ORDER = Collections.unmodifiableList(Arrays.asList(
			ChartSearchAiConstants.REFERENCE_GROUP_CHART,
			ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE));

	/**
	 * Sort rank of a resource type's reference group, as its position in
	 * {@link #REFERENCE_GROUP_ORDER}. Derived via {@link ChartSearchAiUtils#referenceGroup} rather
	 * than from {@code resourceType} directly, so ordering and labelling can never disagree.
	 *
	 * <p>Ranks off the ordered list rather than testing for one group, so a third group added
	 * later slots in at its declared position instead of silently tying with chart evidence and
	 * interleaving — which would quietly falsify the contiguous-groups contract this method
	 * exists to uphold. A group missing from the list sorts last rather than first, so an
	 * unranked group is visibly grouped at the end instead of being mistaken for chart evidence.
	 */
	private static int groupRank(String resourceType) {
		int rank = REFERENCE_GROUP_ORDER.indexOf(ChartSearchAiUtils.referenceGroup(resourceType));
		return rank < 0 ? REFERENCE_GROUP_ORDER.size() : rank;
	}

	/**
	 * Serializes the post-answer drug-safety advisories to the wire shape rendered as chips below the
	 * answer. Empty list when the drug-reference feature is off or nothing was flagged. The key is
	 * always present (possibly empty) so the frontend can branch on length without a null check.
	 *
	 * <p><b>{@code severity} is published because the alternative is a client parsing English</b>
	 * (issue #340). The rating is what the two pair comparators order the chips by and what the cap
	 * then drops the least severe of, and until #340 it stopped here — so the only way to badge a
	 * Major differently from a Minor was to substring-match the middle of {@link
	 * SafetyWarning#getDetail()}, a clinician-facing sentence this module rewords freely. That is not
	 * hypothetical: {@code eval/drift-metric/score_probe_safety.py} carries such a parse and its own
	 * comment calls it "the fault issue #207 exists to have removed". Publishing the rating asserts
	 * nothing the chip's own prose does not already assert to the clinician — it is the SOURCE
	 * dataset's rating, not this module's judgment about what may be done, which is the separate
	 * thing issue #283 deliberately keeps off the wire ({@code DrugSafetyValidator.licensesWithholding}
	 * and the {@code STRENGTH_*} clauses stay prompt-facing).
	 *
	 * <p>Written verbatim and unnormalized, and NOT coerced into a closed vocabulary. See
	 * {@link SafetyWarning#getSeverity()} for what a reader may and may not conclude from it; the
	 * short form is that {@code null} says the rule carries no rating and a non-null value is
	 * whatever the loaded dataset wrote, which the module's own reader
	 * ({@code DrugSafetyValidator.severityRank}) trims, case-folds, and treats as unrated when it does
	 * not recognise it. Null rather than an omitted key, for the reason {@link #groundedForWire}
	 * gives about its own field: the key's unconditional presence is what lets a client read it
	 * without first asking whether it is there.
	 */
	private List<Map<String, Object>> serializeSafetyWarnings(List<SafetyWarning> warnings) {
		List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
		if (warnings == null) {
			return out;
		}
		for (SafetyWarning warning : warnings) {
			Map<String, Object> map = new LinkedHashMap<String, Object>();
			map.put("type", warning.getType());
			map.put("drug", warning.getDrug());
			map.put("detail", warning.getDetail());
			map.put("severity", warning.getSeverity());
			out.add(map);
		}
		return out;
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

	/**
	 * Writes one SSE event frame.
	 *
	 * <p>The frame is serialized first and then written while holding the {@code out} monitor — the
	 * same monitor {@link SseKeepAlive} takes. The keep-alive writes from its own thread, and two
	 * unsynchronized writers on one servlet output stream can interleave: a comment landing between
	 * an event's {@code event:} line and its {@code data:} lines would split one event into two
	 * malformed ones for every client. Only the write is inside the lock, never the serialization.</p>
	 */
	private void writeSseEvent(OutputStream out, String event, String data) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("event: ").append(event).append('\n');
		for (String line : data.split("\n", -1)) {
			sb.append("data: ").append(line).append('\n');
		}
		sb.append('\n');
		byte[] frame = sb.toString().getBytes("UTF-8");
		synchronized (out) {
			out.write(frame);
			out.flush();
		}
	}

	/**
	 * The keep-alive for one streaming response: one comment written immediately, the rest on a
	 * daemon timer, and a {@link #stop()} that a write already in flight cannot race.
	 *
	 * <p>One timer per response rather than one shared by the module: a single shared thread would let
	 * one request's blocked write stall every other request's keep-alive, which is the failure this
	 * class exists to prevent. The cost is one daemon thread per in-flight streaming response, each
	 * writing 14 bytes every {@link #KEEP_ALIVE_INTERVAL_MS}.</p>
	 */
	private static final class SseKeepAlive {

		private final OutputStream out;

		private final ScheduledExecutorService timer;

		/**
		 * Guarded by {@code out}: set by {@link #stop()} so a task that wakes after the answer is
		 * finished writes nothing.
		 */
		private boolean stopped;

		private SseKeepAlive(OutputStream out, ScheduledExecutorService timer) {
			this.out = out;
			this.timer = timer;
		}

		/**
		 * Writes the first comment and schedules the rest.
		 *
		 * <p>That first write is on the CALLING thread deliberately. The property that matters is
		 * that a byte has left before generation begins; scheduling it would make that a race
		 * against the model's own first token, which is the race this whole mechanism is about.</p>
		 */
		static SseKeepAlive start(OutputStream out, long intervalMillis) {
			ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "chartsearchai-sse-keepalive");
				// Daemon so a timer that somehow outlives its request cannot hold up JVM shutdown.
				thread.setDaemon(true);
				return thread;
			});
			SseKeepAlive keepAlive = new SseKeepAlive(out, timer);
			keepAlive.write();
			// Fixed DELAY, not fixed rate: the question a keep-alive answers is "has anything been
			// written lately", so the clock should start when a write finishes. A write to a slow
			// client can block for longer than the interval, and at a fixed rate the executor then
			// owes several runs and fires them back to back into the same congested socket, growing
			// its queue for as long as the congestion lasts.
			timer.scheduleWithFixedDelay(keepAlive::write, intervalMillis, intervalMillis,
					TimeUnit.MILLISECONDS);
			return keepAlive;
		}

		private void write() {
			try {
				// Encoded outside the lock, for the reason writeSseEvent states: the critical section
				// holds the write and nothing else.
				byte[] frame = SSE_KEEP_ALIVE_COMMENT.getBytes("UTF-8");
				synchronized (out) {
					if (stopped) {
						return;
					}
					out.write(frame);
					out.flush();
				}
			}
			catch (IOException e) {
				// The client is gone. The generation loop discovers that on its own next write and
				// unwinds through writeSseEventOrThrow — a keep-alive is never the reason a request
				// fails, and throwing from here would only cancel the schedule in silence.
				log.debug("Could not write SSE keep-alive, client likely disconnected");
			}
			catch (RuntimeException e) {
				// scheduleWithFixedDelay silently unschedules a task that throws — the documented
				// behaviour of both periodic schedule methods — so without this the rest of a long
				// answer would run with no keep-alive and nothing to say why.
				log.warn("SSE keep-alive failed; the schedule continues", e);
			}
		}

		/**
		 * Stops the timer. Once this returns no keep-alive can be in flight or begin: a task already
		 * holding the monitor completes its write before this can take it, and one that takes it
		 * afterwards sees {@code stopped} and returns.
		 */
		void stop() {
			synchronized (out) {
				stopped = true;
			}
			timer.shutdownNow();
		}
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
