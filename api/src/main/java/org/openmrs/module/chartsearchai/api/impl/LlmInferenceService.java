/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartTooLargeException;
import org.openmrs.module.chartsearchai.api.provider.CancellationSignal;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceLoad;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Answers natural language questions about a patient's chart using direct
 * LLM inference. Delegates chart assembly (querystore retrieval + serialization)
 * to {@link ChartBuildingStrategy} and focuses on the LLM call and citation
 * handling. The static helpers on this class are thin {@link QueryPreprocessor}
 * delegates kept for backward-compatible test access; new code should call the
 * underlying class directly.
 *
 * <p>The {@code protected resolve*} methods and package-private setters exposed
 * here are test seams, not an extension point. Subclassing this bean outside the
 * test package is not a supported integration; Spring wiring assumes the singleton
 * is this concrete class.
 */
@Service("chartSearchAi.llmInferenceService")
public class LlmInferenceService implements ChartSearchService {

	private static final Logger log = LoggerFactory.getLogger(LlmInferenceService.class);

	/** Sink for the progressive-reasoning preview pass's answer tokens: the preview's answer is never
	 *  shown — only its reasoning surfaces, and only the full-chart pass is committed. */
	private static final Consumer<String> DISCARD_TOKENS = token -> { };

	@Autowired
	private LlmProvider llmProvider;

	@Autowired
	private ChartBuildingStrategy chartBuildingStrategy;

	@Autowired
	private CitationGroundingVerifier citationGroundingVerifier;

	@Autowired
	private DrugReferenceInjector drugReferenceInjector;

	@Autowired
	private DrugSafetyValidator drugSafetyValidator;

	@Autowired
	private TokenCounter tokenCounter;

	/** Test seam: production wires {@link CitationGroundingVerifier} via {@link Autowired}. */
	void setCitationGroundingVerifier(CitationGroundingVerifier citationGroundingVerifier) {
		this.citationGroundingVerifier = citationGroundingVerifier;
	}

	/** Test seam: production wires {@link DrugReferenceInjector} via {@link Autowired}. */
	void setDrugReferenceInjector(DrugReferenceInjector drugReferenceInjector) {
		this.drugReferenceInjector = drugReferenceInjector;
	}

	/** Test seam: production wires {@link DrugSafetyValidator} via {@link Autowired}. */
	void setDrugSafetyValidator(DrugSafetyValidator drugSafetyValidator) {
		this.drugSafetyValidator = drugSafetyValidator;
	}

	/** Test seam: production wires the engine-specific exact counter via Spring. */
	void setTokenCounter(TokenCounter tokenCounter) {
		this.tokenCounter = tokenCounter;
	}

	/** Test seam: production wires {@link LlmProvider} via {@link Autowired}.
	 *  Package-private to allow {@code LlmInferenceServiceWarmupIntegrationTest} to
	 *  inject a stub without bringing up Spring; matches the seam pattern established
	 *  in {@code QueryStoreChartBuilder}. */
	void setLlmProvider(LlmProvider llmProvider) {
		this.llmProvider = llmProvider;
	}

	/** Test seam: production wires {@link ChartBuildingStrategy} via {@link Autowired}. */
	void setChartBuildingStrategy(ChartBuildingStrategy chartBuildingStrategy) {
		this.chartBuildingStrategy = chartBuildingStrategy;
	}

	@Override
	public ChartAnswer search(Patient patient, String question) {
		// LOG FORMAT — stable contract: operators grep these fields for SLO dashboards
		// and latency triage. Renaming a field is a breaking change. Field set:
		// patient, chartBuildMs, llmMs, totalMs, inputTokens, cachedTokens, outcome={ok,error}.
		// cachedTokens is meaningful only on engines that report KV-cache reuse in their
		// usage metadata (LocalLlmEngine populates it; remote engines may report 0 always).
		// try/finally so an exception from buildChart or LLM still emits a timing line —
		// otherwise the exact queries operators most need to diagnose would be invisible.
		long buildStart = System.currentTimeMillis();
		long buildMs = 0;
		long llmMs = 0;
		long inputTokens = 0;
		long cachedTokens = 0;
		String outcome = "error";
		try {
			PatientChart chart = chartBuildingStrategy.buildChart(patient, question);
			chart = drugReferenceInjector.inject(chart, patient, question);
			ensurePromptFits(chart, question);
			// Resolved once, off the chart that was actually assembled, and carried on the answer —
			// so the audit row the REST layer writes states the mode instead of re-deriving it
			// (issue #178). After inject() deliberately: that is the chart the LLM sees.
			String searchMode = chartBuildingStrategy.searchModeLabel(chart);
			// And, off the same chart and for the same reason, how much of it is the module's own
			// reference material (issue #229). After inject() is not incidental: that is the chart the
			// LLM sees, and the injector is what appends the records being measured. Carried on the
			// answer because by audit-write time this chart is gone.
			ChartSearchAiUtils.ReferenceSlice referenceSlice =
					ChartSearchAiUtils.referenceSlice(chart.getMappings());
			// And, off the same chart, the drug class the module reports as named-but-unresolved
			// (issue #354). Read off the injected chart rather than by asking the question again, so
			// the wire statement and the prompt record cannot disagree — the reason is at
			// ChartSearchAiUtils.unresolvedDrugClass. It is carried because a prompt record only
			// reaches a client if the model cites it, which on the issue's own reproduction it did
			// not.
			String unresolvedDrugClass = ChartSearchAiUtils.unresolvedDrugClass(chart.getMappings());
			// And what this install's contraindication screen had to ask the patient's recorded
			// conditions WITH (issue #378). A load-time verdict rather than a reading of this chart,
			// so it is resolved here only to keep every module statement in one place; it is carried
			// for the reason the three above are — nothing a /search consumer reads could otherwise
			// tell a screen that cannot fire from one that asked and found nothing.
			DrugReferenceLoad.Coverage conditionRuleCoverage =
					drugSafetyValidator.conditionRuleCoverage();
			buildMs = System.currentTimeMillis() - buildStart;

			long llmStart = System.currentTimeMillis();
			LlmResponse response = llmProvider.search(chartTextOrPlaceholder(chart),
					chart.getFocusIndices(), question);
			llmMs = System.currentTimeMillis() - llmStart;
			inputTokens = response.getInputTokens();
			cachedTokens = response.getCachedTokens();

			List<RecordReference> cited = extractCitedReferences(response.getAnswer(),
					response.getCitations(), chart.getMappings());
			ClassCodeFidelityCheck.reportClassCodeDefects(patient, question, response.getAnswer(),
					cited, chart.getMappings());
			// The prose check's own answer, carried rather than re-derived (issue #337 round two): a
			// consumer could not re-ask it if it wanted to, the chart being gone by REST time, and a
			// second walk would be the two-resolutions-that-agree shape #151 forbids.
			List<Integer> unfaithfullyRenderedCitations =
					ReferenceProseFidelityCheck.reportUnfaithfulReferenceProse(patient,
							response.getAnswer(), cited, chart.getMappings());
			List<RecordReference> references = groundReferences(response.getAnswer(), cited,
					chart.getMappings());
			// A per-call sink, never a field: the validator is a Spring singleton, so a field would be
			// one slot shared by every concurrent request (issue #172). What it hears is how bounded
			// the interaction list behind these chips is — the statement issue #336 exists for, and one
			// no consumer can re-derive from the chips themselves. Which arm states it, and when none
			// does, is PairChipExtent's and ChartAnswer.getPairChipExtent()'s to say, not a sink site's.
			PairChipExtent.Sink pairExtent = new PairChipExtent.Sink();
			DrugSafetyValidator.SafetyCheckResult safetyResult =
					drugSafetyValidator.validateWithStatus(response.getAnswer(), question,
							patient, chart.getMappings(), pairExtent);
			ChartAnswer answer = new ChartAnswer(response.getAnswer(), references,
					response.getInputTokens(), response.getOutputTokens(),
					response.getCachedTokens(), safetyResult.getWarnings(), searchMode, referenceSlice,
					pairExtent.stated(), unresolvedDrugClass, unfaithfullyRenderedCitations,
					conditionRuleCoverage, safetyResult.getStatus());
			outcome = "ok";
			return answer;
		}
		finally {
			log.info("[timing] search patient={} chartBuildMs={} llmMs={} totalMs={} inputTokens={} cachedTokens={} outcome={}",
					patient == null ? null : patient.getPatientId(),
					buildMs, llmMs, buildMs + llmMs,
					inputTokens, cachedTokens, outcome);
		}
	}

	@Override
	public void warmup(Patient patient) {
		warmup(patient, false);
	}

	@Override
	public void warmup(Patient patient, boolean pin) {
		// Two operational kill switches first, each as its own early-return so the downstream
		// usePreFilter() GP read is not evaluated when warmup is fundamentally impossible.
		if (!resolveWarmupEnabled()) {
			return;
		}
		if (!llmProvider.supportsWarmup()) {
			return;
		}
		// Chart-byte-stability gate — the single warmup-viability decision point. queryScoped mode
		// produces question-DEPENDENT slice prompts, so there is no stable full-chart prefix to
		// prime and warmup must not run (it would prefill bytes no real query reuses).
		if (!shouldRunWarmup(chartBuildingStrategy.usePreFilter(), resolveQueryScopedMode())) {
			return;
		}
		PatientChart chart = chartBuildingStrategy.buildChart(patient, "");
		// Race guard: trust the CHART, not a re-read of the chartMode GP. If the mode read that
		// gated this warmup disagreed with the read that built the chart (transient GP-read
		// failure, or an operator flip in between), persisting a question-dependent slice under
		// the patient's KV scope would purge their real full-chart entry — pinned entries
		// included. A skipped warmup is always safe; a mis-scoped persist is not.
		if (chart.isQueryScoped()) {
			return;
		}
		// Pass the patient UUID as the KV-cache scope so the local engine can replace this patient's
		// stale on-disk entry when their chart changes, instead of leaving an orphan per chart version.
		// pin=true (prewarm bootstrap) exempts the saved entry from the LRU cap so it joins the durable
		// warm corpus; the chart-open path passes pin=false.
		llmProvider.warmup(chartTextOrPlaceholder(chart),
				patient == null ? null : patient.getUuid(), pin);
	}

	/** Test seam wrapping the static {@link #isWarmupEnabled()}; production delegates,
	 *  tests override to control the gate without an OpenMRS context. */
	protected boolean resolveWarmupEnabled() {
		return isWarmupEnabled();
	}

	/** Test seam wrapping {@link ChartSearchAiUtils#isGroundingEnabled()}; production
	 *  delegates, tests override to exercise the grounding path without an OpenMRS context. */
	protected boolean resolveGroundingEnabled() {
		return ChartSearchAiUtils.isGroundingEnabled();
	}

	/**
	 * Pure-logic decision for whether the current retrieval mode produces a
	 * question-independent chart prefix that warmup can usefully prime. Warmup
	 * primes the cache with one specific prompt prefix; that only pays off if
	 * real queries will reuse those same bytes. Operational kill switches
	 * (warmup disabled, provider doesn't support warmup) are checked at the
	 * {@link #warmup(Patient)} call site instead, so this helper focuses
	 * narrowly on chart-byte-stability semantics.
	 *
	 * <p>Within the fullChart mode (this overload's scope), every configuration since the
	 * querystore migration (#51) produces a question-independent chart prefix, so warmup is viable:
	 * <ul>
	 *   <li>{@code preFilter=false} — {@link QueryStoreChartBuilder} returns the patient's full
	 *       chart via {@code getPatientChart}; bytes do not vary with the question, which is what
	 *       makes warmup viable. They are not, however, permanent: since issue #317 a drug-order
	 *       record also states whether its order is in force, so an order lapsing moves the bytes
	 *       from that record onward and warmup's primed prefix is reusable only up to it. See
	 *       {@code QueryStoreChartBuilder}'s class javadoc for what that costs.</li>
	 *   <li>{@code preFilter=true} — full chart plus a small trailing "Records ranked by
	 *       similarity to the query: ..." focus hint. The records section (the bulk of the prompt)
	 *       is byte-identical across queries; the hint and the question vary only at the very end,
	 *       where they don't break llama-server's prefix-cache match.</li>
	 * </ul>
	 *
	 * <p>The per-query chart prefix this decision point was reserved for now exists:
	 * {@code chartsearchai.chartMode=queryScoped} builds question-dependent slice prompts, gated by
	 * the {@link #shouldRunWarmup(boolean, boolean)} overload. This one-arg form remains the
	 * fullChart-mode contract (and its tests remain the fullChart spec).
	 */
	static boolean shouldRunWarmup(boolean preFilterEnabled) {
		return true;
	}

	/**
	 * As {@link #shouldRunWarmup(boolean)} but aware of the {@code chartsearchai.chartMode} GP:
	 * queryScoped mode assembles a question-dependent slice per query, so no warmup prefix can
	 * ever be reused — warmup (and the per-patient KV scope, see {@link #kvCacheScopeFor}) must
	 * disengage. This is exactly the "future per-query mode" the one-arg overload's contract
	 * reserved this decision point for.
	 */
	static boolean shouldRunWarmup(boolean preFilterEnabled, boolean queryScopedMode) {
		return shouldRunWarmup(preFilterEnabled) && !queryScopedMode;
	}

	/**
	 * The KV-cache scope (patient UUID) to pass on the streaming query path so the local engine can
	 * restore this patient's prefilled chart from disk when the prompt cache is cold and persist a
	 * fresh cold prefill — or {@code null} when the engine must do no disk KV work. Gated by the SAME
	 * chart-byte-stability condition as {@link #shouldRunWarmup}: only when the chart prefix is
	 * question-independent does a per-patient KV entry match the next query. (Whether to PROACTIVELY
	 * warm is a separate toggle; query-path restore is a pure latency win whenever the chart is
	 * stable, so it is intentionally NOT gated on {@code chartsearchai.warmup.enabled} — operators
	 * disable on-disk KV entirely via {@code chartsearchai.llm.kvCacheDir=off}.)
	 */
	String kvCacheScopeFor(Patient patient) {
		if (patient == null || patient.getUuid() == null) {
			return null;
		}
		if (!shouldRunWarmup(chartBuildingStrategy.usePreFilter(), resolveQueryScopedMode())) {
			return null;
		}
		return patient.getUuid();
	}

	/** Test seam wrapping {@link ChartBuildingStrategy#queryScopedMode()}; production delegates,
	 *  tests override to exercise the queryScoped gating without an OpenMRS context. */
	protected boolean resolveQueryScopedMode() {
		return chartBuildingStrategy.queryScopedMode();
	}

	/** Test seam wrapping {@link PipelineSettings#progressiveReasoningEnabled()}; production
	 *  delegates, tests override to exercise the preview path without an OpenMRS context. */
	protected boolean resolveProgressiveReasoningEnabled() {
		return PipelineSettings.progressiveReasoningEnabled();
	}

	/**
	 * Progressive reasoning (stage 1): when {@code chartsearchai.progressiveReasoning.enabled}, run a
	 * fast LLM pass over ONLY the querystore top-K focused chart and stream its reasoning to
	 * {@code previewReasoningConsumer} — the dedicated preliminary channel on the 7-arg path (or the
	 * reasoning channel via the 6-arg overload) — ahead of the unchanged full-chart answer. The
	 * focused chart is a few hundred tokens vs the full chart's several thousand, so on a GPU-less
	 * host its prefill — and thus time-to-first-reasoning — is far smaller. Quality is unaffected:
	 * the preview's answer tokens are discarded ({@link #DISCARD_TOKENS}); only the full-chart pass
	 * (run by the caller after this) is committed; and the preview uses a {@code null} KV scope so it
	 * does no on-disk KV I/O and never writes the patient's persisted full-chart KV entry. (It does
	 * occupy llama-server's single slot, so the full pass that follows restores the full-chart KV
	 * from disk rather than reusing warm RAM — keep {@code chartsearchai.llm.kvCacheDir} enabled.)
	 * A preview failure is
	 * swallowed — the full-chart answer is authoritative and must never be blocked by this optional
	 * speed-up. Returns the elapsed wall time (the {@code previewMs} timing field), or 0 when the
	 * gate is off or the focused chart has no records.
	 */
	private long maybeEmitPreliminaryReasoning(Patient patient, String question,
			Consumer<String> previewReasoningConsumer) {
		long start = System.currentTimeMillis();
		try {
			if (!resolveProgressiveReasoningEnabled()) {
				return 0L;
			}
			// queryScoped mode: the committed answer itself starts after a small slice prefill, so
			// a preview pass would only occupy llama-server's single slot and DELAY that answer.
			if (resolveQueryScopedMode()) {
				return 0L;
			}
			PatientChart focused = chartBuildingStrategy.buildFocusedChart(patient, question);
			if (focused != null && !focused.getMappings().isEmpty()) {
				llmProvider.searchStreaming(focused.getText(), focused.getFocusIndices(), question,
						DISCARD_TOKENS, previewReasoningConsumer, null);
			}
		}
		catch (RuntimeException e) {
			// The preview is an optional speed-up; if anything fails (including reading the gate GP
			// when no OpenMRS context is available) skip it. The full-chart answer is authoritative
			// and must never be blocked by it.
			log.warn("Preliminary reasoning skipped for patient [id={}]: {}",
					patient == null ? null : patient.getPatientId(), e.getMessage());
			return 0L;
		}
		return System.currentTimeMillis() - start;
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer) {
		return searchStreaming(patient, question, tokenConsumer, chunk -> { });
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer) {
		return searchStreaming(patient, question, tokenConsumer, reasoningConsumer, refs -> { });
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
			Consumer<List<RecordReference>> citationsConsumer) {
		return searchStreaming(patient, question, tokenConsumer, reasoningConsumer,
				citationsConsumer, ungrounded -> { });
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
			Consumer<List<RecordReference>> citationsConsumer,
			Consumer<ChartAnswer> ungroundedAnswerConsumer) {
		// No separate preliminary channel requested: route the progressive-reasoning preview (if any)
		// to the reasoning channel, exactly as before the preliminary channel existed.
		return searchStreaming(patient, question, tokenConsumer, reasoningConsumer, citationsConsumer,
				ungroundedAnswerConsumer, reasoningConsumer);
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
			Consumer<List<RecordReference>> citationsConsumer,
			Consumer<ChartAnswer> ungroundedAnswerConsumer, Consumer<String> preliminaryReasoningConsumer) {
		return searchStreaming(patient, question, tokenConsumer, reasoningConsumer, citationsConsumer,
				ungroundedAnswerConsumer, preliminaryReasoningConsumer, CancellationSignal.NONE);
	}

	@Override
	public ChartAnswer searchStreaming(Patient patient, String question,
			Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
			Consumer<List<RecordReference>> citationsConsumer,
			Consumer<ChartAnswer> ungroundedAnswerConsumer, Consumer<String> preliminaryReasoningConsumer,
			CancellationSignal cancellation) {
		// LOG FORMAT — stable contract: same field set as search() with op=searchStreaming
		// in the log tag, plus previewMs (progressive-reasoning preview pass) and groundMs (Tier-2
		// grounding, timed separately so the tail is visible). Streaming is the path the frontend
		// actually uses by default, so this is what demo operators see in their logs. try/finally
		// so exceptions still emit a timing line.
		long buildStart = System.currentTimeMillis();
		long buildMs = 0;
		long previewMs = 0;
		long llmMs = 0;
		long groundMs = 0;
		long inputTokens = 0;
		long cachedTokens = 0;
		String outcome = "error";
		try {
			PatientChart chart = chartBuildingStrategy.buildChart(patient, question);
			chart = drugReferenceInjector.inject(chart, patient, question);
			ensurePromptFits(chart, question);
			// One resolution for BOTH answers this method produces (issue #178). The early-done path
			// audits the ungrounded answer and the classic path audits the returned one, so a mode
			// each of them derived separately is two audit-write sites that can disagree — which is
			// half of what #178 was, one layer up.
			String searchMode = chartBuildingStrategy.searchModeLabel(chart);
			// The slice too, and for the reason just given about the mode: one resolution for both
			// answers this method produces (issue #229). Off the post-inject chart, which is the whole
			// point of the number — see the same pair in search() above.
			ChartSearchAiUtils.ReferenceSlice referenceSlice =
					ChartSearchAiUtils.referenceSlice(chart.getMappings());
			// The class statement too, one resolution for both answers this method produces and for
			// the same reason (issue #354). Both need it, and the ungrounded one especially: with
			// async grounding the early "done" is emitted from THAT answer, so a statement set only
			// on the returned one would be absent from the event the user actually sees.
			String unresolvedDrugClass = ChartSearchAiUtils.unresolvedDrugClass(chart.getMappings());
			// The condition-rule coverage too, one resolution for both answers this method produces
			// and for the same reason (issue #378). The ungrounded one especially: with async
			// grounding the early "done" is emitted from THAT answer, and this statement is known
			// before the model is called, so there is no reason for that event to carry less.
			DrugReferenceLoad.Coverage conditionRuleCoverage =
					drugSafetyValidator.conditionRuleCoverage();
			buildMs = System.currentTimeMillis() - buildStart;

			// Progressive reasoning: stream a fast preview reasoning from the focused top-K chart to
			// the preliminary channel before the full-chart answer prefills. No-op (returns 0) when the
			// gate is off. Runs after the full chart is built so the patient's querystore index is
			// already warm when the preview's searchByPatient runs (a cold patient pays it once).
			previewMs = maybeEmitPreliminaryReasoning(patient, question, preliminaryReasoningConsumer);

			long llmStart = System.currentTimeMillis();
			// KV scope: decided against the CHART that was built, not a re-read of the chartMode
			// GP. A query-scoped slice must never carry a patient KV scope — persisting its
			// question-dependent prompt under that scope would purge the patient's real
			// full-chart entry (pin included) with a file no future request can hit. The GP
			// re-read inside kvCacheScopeFor can disagree with the build (transient read failure
			// or an operator flip mid-request); chart.isQueryScoped() cannot.
			String kvCacheScope = chart.isQueryScoped() ? null : kvCacheScopeFor(patient);
			LlmResponse response = llmProvider.searchStreaming(
					chartTextOrPlaceholder(chart), chart.getFocusIndices(), question, tokenConsumer,
					reasoningConsumer, kvCacheScope, cancellation);
			llmMs = System.currentTimeMillis() - llmStart;
			inputTokens = response.getInputTokens();
			cachedTokens = response.getCachedTokens();

			// Citations are known as soon as the answer is generated. Hand them to the caller
			// BEFORE the grounding pass (which can add a tail of Tier-2 entailment calls) so the UI
			// can render the answer and its clickable citations immediately; the returned answer
			// carries the grounded references once verification completes.
			List<RecordReference> cited = extractCitedReferences(response.getAnswer(),
					response.getCitations(), chart.getMappings());
			citationsConsumer.accept(cited);

			// The answer is complete: hand the whole (not yet grounding-verified) result to the
			// caller before the grounding pass, so the REST layer can finish the user-visible
			// response (emit "done", persist the audit row) without waiting out the Tier-2 tail.
			// Fires regardless of whether grounding is enabled — see the interface contract.
			ungroundedAnswerConsumer.accept(new ChartAnswer(response.getAnswer(), cited,
					response.getInputTokens(), response.getOutputTokens(),
					response.getCachedTokens(), Collections.<SafetyWarning> emptyList(), searchMode,
					referenceSlice, null, unresolvedDrugClass, null, conditionRuleCoverage,
					DrugSafetyValidator.STATUS_UNAVAILABLE));

			// After the user-visible handoff, before grounding: two exact comparisons over what the
			// answer states about the records it cites — the class-code defects a set-membership
			// comparison can and cannot see (issues #142 and #338), and prose reproduced from a cited
			// reference record and then rewritten inside the sentence it was copying (issue #337).
			// Neither blocks: the class-code check reports only to the log, and the prose check's own
			// answer is carried onto the ChartAnswer this method RETURNS, so no consumer above waits
			// on either. Not "microseconds", which this comment said and which is true only of the first:
			// the second is a word-level dynamic program, measured at ~0.7 ms on a realistic chart and
			// ~1.2 ms at the largest injected record set anyone has swept (ADR Decision 61).
			ClassCodeFidelityCheck.reportClassCodeDefects(patient, question, response.getAnswer(),
					cited, chart.getMappings());
			// Its answer is carried onto the ChartAnswer this method returns (issue #337 round two).
			// The early one above cannot have it and states null: the check runs HERE, after the
			// user-visible handoff, and moving it ahead would put a word-level dynamic program in
			// front of the "done" event for a statement that is not needed to render the answer.
			List<Integer> unfaithfullyRenderedCitations =
					ReferenceProseFidelityCheck.reportUnfaithfulReferenceProse(patient,
							response.getAnswer(), cited, chart.getMappings());

			long groundStart = System.currentTimeMillis();
			List<RecordReference> references = groundReferences(response.getAnswer(), cited,
					chart.getMappings());
			groundMs = System.currentTimeMillis() - groundStart;

			// A per-call sink, never a field: the validator is a Spring singleton, so a field would be
			// one slot shared by every concurrent request (issue #172). What it hears is how bounded
			// the interaction list behind these chips is — the statement issue #336 exists for, and one
			// no consumer can re-derive from the chips themselves. Which arm states it, and when none
			// does, is PairChipExtent's and ChartAnswer.getPairChipExtent()'s to say, not a sink site's.
			PairChipExtent.Sink pairExtent = new PairChipExtent.Sink();
			DrugSafetyValidator.SafetyCheckResult safetyResult =
					drugSafetyValidator.validateWithStatus(response.getAnswer(), question,
							patient, chart.getMappings(), pairExtent);
			ChartAnswer answer = new ChartAnswer(response.getAnswer(), references,
					response.getInputTokens(), response.getOutputTokens(),
					response.getCachedTokens(), safetyResult.getWarnings(), searchMode, referenceSlice,
					pairExtent.stated(), unresolvedDrugClass, unfaithfullyRenderedCitations,
					conditionRuleCoverage, safetyResult.getStatus());
			outcome = "ok";
			return answer;
		}
		finally {
			log.info("[timing] searchStreaming patient={} chartBuildMs={} previewMs={} llmMs={} groundMs={} totalMs={} inputTokens={} cachedTokens={} outcome={}",
					patient == null ? null : patient.getPatientId(),
					buildMs, previewMs, llmMs, groundMs, buildMs + previewMs + llmMs + groundMs,
					inputTokens, cachedTokens, outcome);
		}
	}

	/**
	 * Final exact preflight after all deterministic chart and knowledge-reference injection. The
	 * selector budgets its chart view earlier, but only this layer can measure the complete prompt
	 * that will reach the model.
	 */
	void ensurePromptFits(PatientChart chart, String question) {
		if (tokenCounter == null || !tokenCounter.isAvailable()) {
			return;
		}
		int inputTokens = tokenCounter.countPrompt(chartTextOrPlaceholder(chart), question);
		if (inputTokens > tokenCounter.inputBudget()) {
			throw new ChartTooLargeException("The complete chart, reference material, and question "
					+ "exceed the configured model input budget.");
		}
	}

	/**
	 * Substitutes a placeholder when the chart has no records, so the LLM
	 * produces a query-specific "no records" answer instead of one based
	 * on demographics alone.
	 */
	private static String chartTextOrPlaceholder(PatientChart chart) {
		return chart.getMappings().isEmpty() ? "(No relevant records found)" : chart.getText();
	}

	static boolean isWarmupEnabled() {
		String value = org.openmrs.api.context.Context.getAdministrationService()
				.getGlobalProperty(ChartSearchAiConstants.GP_WARMUP_ENABLED, "true");
		return !"false".equalsIgnoreCase(value.trim());
	}

	/**
	 * Annotates each index-validated citation with a grounding verdict when
	 * {@code chartsearchai.grounding.enabled} is set, otherwise returns the
	 * references unchanged. Annotate-only: it never drops or reorders
	 * references, so disabling the flag (or a verifier failure, which degrades
	 * to an unverified verdict) leaves today's behavior intact.
	 */
	private List<RecordReference> groundReferences(String answer, List<RecordReference> references,
			List<RecordMapping> mappings) {
		if (references == null || references.isEmpty() || !resolveGroundingEnabled()) {
			return references;
		}
		return citationGroundingVerifier.verify(answer, references, mappings);
	}

	static List<RecordReference> extractCitedReferences(List<Integer> citations,
			List<RecordMapping> mappings) {
		return extractCitedReferences(null, citations, mappings);
	}

	/**
	 * Builds the clickable reference list for an answer, reconciling the two
	 * sources of citation indices that can disagree: the LLM's structured
	 * {@code citations} array and the {@code [N]} markers it writes inline in the
	 * prose. We take the UNION of both (restricted to indices that map to a real
	 * retrieved record), so a record the model cited inline but omitted from the
	 * array — or one it listed in the array while citing at least one record
	 * inline — still resolves to a reference. The one exception is the
	 * abstention-dump carve-out below: an answer whose prose cites nothing inline
	 * discards the array entirely. Indices with no matching record are dropped and
	 * logged, exactly as the array path already drops unmapped indices; the
	 * prose itself is never rewritten.
	 *
	 * <p>An inline {@code [N]} marker in the answer is the authoritative record of
	 * what the model cited: the system prompt instructs it to "Cite EVERY record
	 * you reference by its number in brackets", and its own few-shot demonstrates
	 * that an abstention answer carries {@code "citations": []}. A small local
	 * model breaks that contract by writing an abstention ("no cancer found") with
	 * no inline markers yet dumping its whole reviewed record set into the
	 * structured array. So when the answer is real prose that anchors NO citation
	 * inline, the structured array is treated as unanchored and no references are
	 * surfaced — a "not found" answer must not arrive with the entire chart
	 * attached. This is scoped to non-blank prose: an empty/blank answer is the
	 * absence of an answer (a distinct degenerate output), not an answer that
	 * failed to anchor its citations, so the array still resolves there — as does
	 * the legacy {@code answer == null} entry point.
	 */
	static List<RecordReference> extractCitedReferences(String answer, List<Integer> citations,
			List<RecordMapping> mappings) {
		Map<Integer, RecordMapping> indexMap = new HashMap<Integer, RecordMapping>();
		for (RecordMapping mapping : mappings) {
			indexMap.put(mapping.getIndex(), mapping);
		}

		Set<Integer> seen = new LinkedHashSet<Integer>();
		if (citations != null) {
			for (Integer index : citations) {
				seen.add(index);
			}
		}
		if (answer != null) {
			Set<Integer> inline = ChartSearchAiUtils.citedIndexes(answer);
			// Real answer prose that anchors NO citation inline: the structured
			// array is unanchored (the abstention-dump failure mode), so surface
			// nothing rather than the records the model merely reviewed. The
			// !isBlank guard exempts a blank answer — see the method javadoc.
			if (inline.isEmpty() && !ChartSearchAiUtils.isBlank(answer)) {
				return new ArrayList<RecordReference>();
			}
			seen.addAll(inline);
		}

		List<RecordReference> references = new ArrayList<RecordReference>();
		for (Integer index : seen) {
			RecordMapping mapping = indexMap.get(index);
			if (mapping != null) {
				// Citation metadata travels with the reference, not inside the record text the model
				// reads (issue #117): a client renders provenance and the withheld-partner count on
				// the citation chip, so the record has nothing about itself for the model to recite.
				references.add(new RecordReference(index, mapping.getResourceType(),
						mapping.getResourceUuid(), mapping.getDate(), null, mapping.getSource(),
						mapping.getWithheldInteractions()));
			} else {
				log.warn("LLM cited record [{}] which does not exist in the provided records", index);
			}
		}
		Collections.sort(references, Comparator.comparing(RecordReference::getDate,
				Comparator.nullsLast(Comparator.reverseOrder())));
		return references;
	}

	// =====================================================================
	// Static delegate wrappers to QueryPreprocessor — kept so existing test
	// call sites that use LlmInferenceService.X(...) continue to resolve.
	// New code should call QueryPreprocessor directly. (The embedding/scoring
	// delegators were removed with the legacy retrieval pipeline in issue #51.)
	// =====================================================================

	static int extractRecencyCap(String question) {
		return QueryPreprocessor.extractRecencyCap(question);
	}

	static String stripQueryStopwords(String question) {
		return QueryPreprocessor.stripQueryStopwords(question);
	}

	static String[] extractQueryTerms(String normalizedQuery) {
		return QueryPreprocessor.extractQueryTerms(normalizedQuery);
	}
}
