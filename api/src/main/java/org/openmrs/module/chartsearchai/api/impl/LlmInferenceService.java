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
import java.util.regex.Matcher;

import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.api.ChartSearchService;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
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
			buildMs = System.currentTimeMillis() - buildStart;

			long llmStart = System.currentTimeMillis();
			LlmResponse response = llmProvider.search(chartTextOrPlaceholder(chart),
					chart.getFocusIndices(), question);
			llmMs = System.currentTimeMillis() - llmStart;
			inputTokens = response.getInputTokens();
			cachedTokens = response.getCachedTokens();

			List<RecordReference> references = groundReferences(response.getAnswer(),
					extractCitedReferences(response.getAnswer(), response.getCitations(),
							chart.getMappings()),
					chart.getMappings());
			List<SafetyWarning> safetyWarnings = drugSafetyValidator.validate(response.getAnswer(), question, patient);
			ChartAnswer answer = new ChartAnswer(response.getAnswer(), references,
					response.getInputTokens(), response.getOutputTokens(),
					response.getCachedTokens(), safetyWarnings);
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
		// Two operational kill switches first, each as its own early-return so neither
		// downstream call (usePreFilter — GP read; resolveQueryStoreEnabled — GP read) is
		// evaluated when warmup is fundamentally impossible. Pre-slice these were implicit
		// in the if-chain; the extracted helper would otherwise evaluate them eagerly.
		if (!resolveWarmupEnabled()) {
			return;
		}
		if (!llmProvider.supportsWarmup()) {
			return;
		}
		// Chart-byte-stability gate — the single warmup-viability decision point. Post-#51 every
		// retrieval mode yields a question-independent chart prefix, so this currently always
		// proceeds; it is retained (and fed the GP reads) so a future per-query mode can re-gate
		// here rather than at this call site. See shouldRunWarmup.
		if (!shouldRunWarmup(
				chartBuildingStrategy.usePreFilter(),
				resolveQueryStoreEnabled())) {
			return;
		}
		PatientChart chart = chartBuildingStrategy.buildChart(patient, "");
		// Pass the patient UUID as the KV-cache scope so the local engine can replace this patient's
		// stale on-disk entry when their chart changes, instead of leaving an orphan per chart version.
		llmProvider.warmup(chartTextOrPlaceholder(chart),
				patient == null ? null : patient.getUuid());
	}

	/** Test seam wrapping the static {@link #isWarmupEnabled()}; production delegates,
	 *  tests override to control the gate without an OpenMRS context. */
	protected boolean resolveWarmupEnabled() {
		return isWarmupEnabled();
	}

	/** Test seam wrapping {@link ChartSearchAiUtils#isQueryStoreEnabled()}; production
	 *  delegates, tests override to control the gate without an OpenMRS context. */
	protected boolean resolveQueryStoreEnabled() {
		return ChartSearchAiUtils.isQueryStoreEnabled();
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
	 * <p>Since the querystore migration (#51) removed the legacy embedding/Lucene/
	 * Elasticsearch pipelines, every retrieval mode now produces a question-independent
	 * chart prefix, so warmup is viable in all of them:
	 * <ul>
	 *   <li>querystore enabled, {@code preFilter=false} — {@link QueryStoreChartBuilder}
	 *       returns the patient's full chart via {@code getPatientChart}; bytes are a
	 *       function of the patient only.</li>
	 *   <li>querystore enabled, {@code preFilter=true} — full chart plus a small trailing
	 *       "Records ranked by similarity to the query: ..." focus hint. The records
	 *       section (the bulk of the prompt) is byte-identical across queries; the hint
	 *       and the question vary only at the very end, where they don't break
	 *       llama-server's prefix-cache match.</li>
	 *   <li>querystore disabled (any {@code preFilter}) — {@link ChartBuildingStrategy}
	 *       serializes the full patient chart unranked; no per-query variation at all
	 *       (the legacy inline-filtering path that once varied here was removed in #51).</li>
	 * </ul>
	 *
	 * <p>The parameters are retained as the contract for this single decision point: if a
	 * future retrieval mode reintroduces a per-query chart prefix, gate it here (and in
	 * {@link LlmInferenceServiceWarmupTest}) rather than at the {@code warmup()} call site,
	 * which would split the decision across two places.
	 */
	static boolean shouldRunWarmup(boolean preFilterEnabled, boolean queryStoreEnabled) {
		return true;
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
		if (!shouldRunWarmup(chartBuildingStrategy.usePreFilter(), resolveQueryStoreEnabled())) {
			return null;
		}
		return patient.getUuid();
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
		// LOG FORMAT — stable contract: same field set as search() with op=searchStreaming
		// in the log tag, plus groundMs (Tier-2 grounding is now timed separately so the tail is
		// visible). Streaming is the path the frontend actually uses by default, so this is what
		// demo operators see in their logs. try/finally so exceptions still emit a timing line.
		long buildStart = System.currentTimeMillis();
		long buildMs = 0;
		long llmMs = 0;
		long groundMs = 0;
		long inputTokens = 0;
		long cachedTokens = 0;
		String outcome = "error";
		try {
			PatientChart chart = chartBuildingStrategy.buildChart(patient, question);
			chart = drugReferenceInjector.inject(chart, patient, question);
			buildMs = System.currentTimeMillis() - buildStart;

			long llmStart = System.currentTimeMillis();
			LlmResponse response = llmProvider.searchStreaming(
					chartTextOrPlaceholder(chart), chart.getFocusIndices(), question, tokenConsumer,
					reasoningConsumer, kvCacheScopeFor(patient));
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
					response.getCachedTokens()));

			long groundStart = System.currentTimeMillis();
			List<RecordReference> references = groundReferences(response.getAnswer(), cited,
					chart.getMappings());
			groundMs = System.currentTimeMillis() - groundStart;

			List<SafetyWarning> safetyWarnings = drugSafetyValidator.validate(response.getAnswer(), question, patient);
			ChartAnswer answer = new ChartAnswer(response.getAnswer(), references,
					response.getInputTokens(), response.getOutputTokens(),
					response.getCachedTokens(), safetyWarnings);
			outcome = "ok";
			return answer;
		}
		finally {
			log.info("[timing] searchStreaming patient={} chartBuildMs={} llmMs={} groundMs={} totalMs={} inputTokens={} cachedTokens={} outcome={}",
					patient == null ? null : patient.getPatientId(),
					buildMs, llmMs, groundMs, buildMs + llmMs + groundMs,
					inputTokens, cachedTokens, outcome);
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
	 * retrieved record), so a record the model cited inline but forgot to add to
	 * the array — or listed in the array but only referenced inline — still
	 * resolves to a reference. Indices with no matching record are dropped and
	 * logged, exactly as the array path already drops unmapped indices; the
	 * prose itself is never rewritten.
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
			Matcher marker = ChartSearchAiUtils.INLINE_CITATION.matcher(answer);
			while (marker.find()) {
				seen.add(Integer.valueOf(marker.group(1)));
			}
		}

		List<RecordReference> references = new ArrayList<RecordReference>();
		for (Integer index : seen) {
			RecordMapping mapping = indexMap.get(index);
			if (mapping != null) {
				references.add(new RecordReference(index, mapping.getResourceType(),
						mapping.getResourceUuid(), mapping.getDate()));
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
