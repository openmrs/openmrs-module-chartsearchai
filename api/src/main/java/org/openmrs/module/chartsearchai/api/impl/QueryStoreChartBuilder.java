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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.InsufficientContextException;
import org.openmrs.module.chartsearchai.api.ChartTooLargeException;
import org.openmrs.module.chartsearchai.api.scope.QueryScopeContributor;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;
import org.openmrs.module.chartsearchai.util.DateFormatUtil;
import org.openmrs.module.querystore.QueryStoreConstants;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.backend.PatientChartRead;
import org.openmrs.module.querystore.model.ContextSlice;
import org.openmrs.module.querystore.model.ContextSliceRecord;
import org.openmrs.module.querystore.model.ContextSliceRequest;
import org.openmrs.module.querystore.model.QueryDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Bridge to the querystore module's read API. querystore is a required module, so it is present at
 * runtime; the service is still resolved lazily via {@link Context#getService(Class)} rather than
 * injected. A resolution or completeness failure is explicit because an unavailable chart cannot
 * safely be represented as a clinically meaningful empty chart.
 *
 * <p>{@link #build} (the fullChart mode) always fetches the full patient chart via
 * {@link QueryStoreService#getPatientChartRead(String)} so the chart bytes sent to
 * the LLM do not vary with the question — that's the property
 * llama-server's KV-cache reuse needs in order to skip ~99% of the prefill on
 * subsequent queries for the same patient. Since issue #317 they are a function of the patient AND
 * of their order status as read at assembly time, which is a narrowing of that property rather than
 * a loss of it: the bytes are still question-independent, but an order lapsing by its
 * {@code auto_expire_date} — or a transient failure of the order read, which drops every mark at
 * once — changes them with no underlying data change and no index change. The reused prefix then
 * ends at the record whose mark moved, and everything after it is prefilled again. How much that
 * costs depends on where that record sits, and a chart is ordered most-recent-first across ALL record
 * types. On the 3.7.1 demo database's most-prescribed patient — the one with the most drug orders,
 * which is not the one with the most records — all 8 of their drug orders are newer than every one of
 * their observations, conditions, diagnoses, encounters and visits, and older than 8 of their 9
 * allergies, so the first drug-order record is around the tenth line of several hundred and
 * "everything after it" is most of the chart. Do not carry that shape to another patient, and do not
 * read it as a worst case: what decides the position is every record type's dates, and this is one
 * patient's. It is the correct outcome — the
 * cached prefix asserted something that is no longer true —
 * and {@code appendLiveAge} already made the bytes clock-dependent in the same way, though not at
 * the same cadence: a birthday is once a year, and a finite-duration prescription ending is
 * routine. It does not arise in the shipped {@code queryScoped} default, where nothing persists a
 * KV prefix at all. {@link #buildScoped}
 * ({@code chartsearchai.chartMode=queryScoped}) deliberately trades that property away: it
 * assembles a small question-dependent slice whose prefill is cheap enough to pay fresh on
 * every query, so cold patients need no warmup at all. When
 * {@code chartsearchai.embedding.preFilter=true} and the question is non-blank,
 * additionally calls {@link QueryStoreService#searchByPatient(String, String, int)}
 * to obtain a relevance ranking, then renders those hits as a short
 * "Records ranked by similarity to the query: ..." focus-hint line in the LLM prompt
 * (handled in {@code LlmProvider.buildUserMessage} via the
 * {@link PatientChart#getFocusIndices()} payload). The hint biases the LLM's
 * attention without removing records the LLM needs for negative reasoning.
 *
 * <p>The same mark also makes those bytes PRIVILEGE-dependent, which the clock never did. The order
 * read needs core's {@code Get Orders}; {@code WarmupExecutor} and the prewarm sweep run through
 * {@code Daemon.runInDaemonThread}, and {@code Context.hasPrivilege} answers true unconditionally on
 * a daemon thread, so the prefix they prime and pin is always assembled WITH the mark. A request
 * thread on a role that lacks the privilege assembles the same patient's chart WITHOUT it — the read
 * throws, {@link #readOrderCurrency} catches, every mark is dropped — and under {@code fullChart} the
 * KV entry is keyed on a hash of the chart text ({@code LocalLlmEngine.kvCacheKey}), so that role's
 * prompt can never match the warmed prefix at all: not a shortened reuse, none. Warmup and the whole
 * durable corpus buy it nothing and every query pays a full prefill. Recorded, not fixed: making the
 * two threads assemble the same bytes would mean either dropping the mark for everyone or escalating
 * the request thread's privileges to match the daemon's, and the second is not this module's call to
 * make. The remedy is the operator's — grant the role {@code Get Orders}; the README's privileges
 * section says who already holds it on a stock install.
 *
 * <p>The {@code protected resolve*} methods and the package-private
 * {@link #setChartSerializer} are test seams, not an extension point.
 * Subclassing this bean outside the test package is not supported.
 */
@Component("chartSearchAi.queryStoreChartBuilder")
class QueryStoreChartBuilder {

	private static final Logger log = LoggerFactory.getLogger(QueryStoreChartBuilder.class);

	// Mode labels emitted in the [timing] querystoreBuild log lines so ops dashboards can
	// distinguish the two dispatch shapes. preFilter mode does the extra searchByPatient
	// call for the focus hint; fullChart skips it.
	//
	// These VALUES are a consumer contract: anything grepping mode=fullChart out of the logs — a
	// dashboard, a saved log query, an eval instrument — breaks silently on a re-spelling, showing
	// up as a metric that quietly goes to zero rather than as an error. Being constants does
	// not protect that, and issue #232 is this comment having claimed it did: a misspelled IDENTIFIER
	// on a future log line fails to compile, but the value is what the consumer reads and changing it
	// compiles fine. Measured by mutation: renaming MODE_FULL_CHART's value to "TYPO_fullChart" fails
	// exactly one test, the one that exists to notice —
	// QueryStoreChartBuilderTest#theTimingModeLabelsAreAnOpsContract_soTheirSpellingsArePinnedAsLiterals.
	//
	// Deliberately NOT the audit column's vocabulary (ChartSearchAiConstants.SEARCH_MODE_*, which
	// spells the same two shapes full-chart/pre-filter). Two contracts, two audiences; unifying
	// them was considered and declined during #178.
	//
	// Spellings do collide across those contracts, and each collision is a coincidence rather than
	// one definition: "fullChart" here is also CHART_MODE_FULL_CHART, the chartsearchai.chartMode GP
	// token an operator sets, and "unknown" below is also SEARCH_MODE_UNKNOWN in the audit column.
	// Both are what a grep lands on and what a "unify these" pass would trip over. Do not make one
	// constant of any of them; the literals are pinned separately on each side on purpose.
	static final String MODE_PRE_FILTER = "preFilter";

	static final String MODE_FULL_CHART = "fullChart";

	// Mode label for the input-error path (null patient / null uuid), which fires BEFORE
	// resolveUsePreFilter() and so cannot honestly label the dispatch. Kept distinct from
	// the two real modes so dashboards bucket input errors separately.
	static final String MODE_UNKNOWN = "unknown";

	/** querystore's resource type for the patient demographics document (its
	 *  PatientRecordSerializer contract) — the one record every slice carries and one of the
	 *  {@link #ADMIN_DATED_TYPES}. Single constant so the two uses cannot drift. */
	private static final String PATIENT_RESOURCE_TYPE = "patient";

	/** querystore's resource type for a prescription. The order-currency mark is scoped to it:
	 *  {@link #resolveAllOrders} returns every order type, so the uuid sets cover test and referral
	 *  orders too, and marking those is a deliberate widening nobody has asked for rather than
	 *  something the data forces. */
	private static final String DRUG_ORDER_RESOURCE_TYPE =
			ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER;

	/** Operator remediation for an unresolvable QueryStoreService, WARNed identically by
	 *  {@link #build} and {@link #buildScoped} (buildFocused stays silent — build() has already
	 *  warned on the same request). One constant so the degradation message cannot drift
	 *  between modes that never run side by side on one deployment. */
	private static final String QUERYSTORE_UNAVAILABLE_MSG =
			"QueryStoreService is unavailable — querystore is a required module, so this "
					+ "indicates a querystore startup failure; check the querystore module. "
					+ "The turn cannot establish a complete patient chart.";

	@Autowired
	private PatientChartSerializer chartSerializer;

	@Autowired(required = false)
	@Qualifier("chartSearchAi.localLlamaTokenCounter")
	private TokenCounter tokenCounter;

	/** Test seam: production wires {@link PatientChartSerializer} via {@link Autowired}.
	 *  Package-private so {@code QueryStoreChartBuilderTest} can inject a real serializer
	 *  without bringing up Spring; matches the {@code resolveX()} method-override seam
	 *  pattern used for {@link QueryStoreService} and topK. */
	void setChartSerializer(PatientChartSerializer chartSerializer) {
		this.chartSerializer = chartSerializer;
	}

	/** Test seam: production wires {@link TokenCounter} via {@link Autowired}. */
	void setTokenCounter(TokenCounter tokenCounter) {
		this.tokenCounter = tokenCounter;
	}

	PatientChart build(Patient patient, String question) {
		long buildStart = System.currentTimeMillis();
		// Hard-error guards apply in both modes: null patient or null uuid can't reach either
		// querystore method without NPE.
		if (patient == null || patient.getUuid() == null) {
			// mode=unknown — the dispatch isn't determined yet (resolveUsePreFilter() runs
			// below). Emitting an explicit label keeps the timing log shape uniform so a
			// dashboard grepping for mode= doesn't undercount input-error events.
			log.info("[timing] querystoreBuild patient={} mode={} hits=0 focusHits=0 rpcMs=0 serializeMs=0 totalMs={} outcome=skipped",
					patient == null ? null : patient.getPatientId(),
					MODE_UNKNOWN, System.currentTimeMillis() - buildStart);
			return chartSerializer.serialize(patient, Collections.<SerializedRecord>emptyList());
		}

		boolean usePreFilter = resolveUsePreFilter();
		// `mode` labels each [timing] log line so operators can tell focus-hint preFilter
		// dispatch (extra searchByPatient call) from plain fullChart dispatch.
		String mode = usePreFilter ? MODE_PRE_FILTER : MODE_FULL_CHART;

		// WARN (not INFO): default org.openmrs.* log level is WARN, and an unavailable
		// QueryStoreService silently produces empty-chart LLM responses if this fires.
		// Operators need this to surface, with an actionable next step.
		QueryStoreService queryStore = resolveQueryStoreOrNull();
		if (queryStore == null) {
			log.warn(QUERYSTORE_UNAVAILABLE_MSG);
			log.info("[timing] querystoreBuild patient={} mode={} hits=0 focusHits=0 rpcMs=0 serializeMs=0 totalMs={} outcome=unavailable",
					patient.getPatientId(), mode, System.currentTimeMillis() - buildStart);
			throw new IllegalStateException(QUERYSTORE_UNAVAILABLE_MSG);
		}

		// Full chart first — this is what the LLM sees and what determines the KV-cache
		// prefix. Always called regardless of mode so the chart bytes do not vary with
		// the question (see the class javadoc for what they DO vary with).
		long rpcStart = System.currentTimeMillis();
		PatientChartRead chartRead;
		try {
			chartRead = queryStore.getPatientChartRead(patient.getUuid());
		}
		catch (RuntimeException e) {
			String failure = getPatientChartFailedMessage(patient.getUuid());
			log.error(failure, e);
			long failMs = System.currentTimeMillis() - rpcStart;
			log.info("[timing] querystoreBuild patient={} mode={} hits=0 focusHits=0 rpcMs={} serializeMs=0 totalMs={} outcome=error errorClass={}",
					patient.getPatientId(), mode, failMs, System.currentTimeMillis() - buildStart,
					e.getClass().getSimpleName());
			throw new IllegalStateException(failure, e);
		}
		if (chartRead.isTruncated()) {
			throw new ChartTooLargeException("QueryStore returned an incomplete chart for patient "
					+ patient.getUuid() + "; the answer was withheld rather than treating it as complete.");
		}
		List<QueryDocument> chartDocs = chartRead.getDocuments();

		// Focus hint: only in preFilter mode, only with a non-blank question (searchByPatient
		// with a blank query is spurious — no ranking signal). The hint is a tiny payload
		// (UUIDs collected here, rendered as 1-based indices in the chart serializer). A search
		// failure must not block the LLM call — the full chart is already fetched and is usable
		// on its own (equivalent to fullChart mode), so it degrades to an empty focus set.
		Set<String> focusUuids = Collections.<String>emptySet();
		if (usePreFilter && question != null && !question.trim().isEmpty()) {
			focusUuids = searchSimilarityUuids(queryStore, patient,
					QueryPreprocessor.stripQueryStopwords(question),
					"QueryStore.searchByPatient failed for patient [uuid={}] — proceeding without focus hint");
		}
		int focusHits = focusUuids.size();
		long rpcMs = System.currentTimeMillis() - rpcStart;

		List<SerializedRecord> records = toSerializedRecords(patient, chartDocs);
		long serializeStart = System.currentTimeMillis();
		PatientChart chart = chartSerializer.serialize(patient, records, focusUuids, resolveDedupGroupLabels());
		long serializeMs = System.currentTimeMillis() - serializeStart;
		long totalMs = System.currentTimeMillis() - buildStart;
		log.info("[timing] querystoreBuild patient={} mode={} hits={} focusHits={} rpcMs={} serializeMs={} totalMs={} outcome=ok",
				patient.getPatientId(), mode, records.size(), focusHits, rpcMs, serializeMs, totalMs);
		return markPreFilter(chart, usePreFilter);
	}

	/**
	 * Stamps a full chart with the preFilter dispatch that produced it — every {@link #build} return
	 * that got as far as resolving it, degraded empties included, so the audit row names the mode
	 * that was in force even when the chart came back empty.
	 *
	 * <p>Taken from the {@code usePreFilter} {@link #build} already dispatched on, not a second read,
	 * so the stamp and the {@code mode=} label above can only ever say the same thing.
	 *
	 * <p>The null-patient guard returns before that resolution — the case this class labels
	 * {@link #MODE_UNKNOWN} — so its chart is unstamped and would be named as a plain full chart.
	 * That is not reachable from a row: the REST layer resolves the patient (404 otherwise) before
	 * it can call the pipeline, and the other caller of a null-patient build, warmup, writes no
	 * audit row.
	 */
	private static PatientChart markPreFilter(PatientChart chart, boolean usePreFilter) {
		if (usePreFilter) {
			chart.markPreFiltered();
		}
		return chart;
	}

	/**
	 * Builds the query-scoped slice chart for {@code chartsearchai.chartMode=queryScoped} as a
	 * THIN ADAPTER over querystore's shared context-selection contract (querystore ADR
	 * Decision 17): this builder contributes module-owned resource scopes and QueryStore derives
	 * question intent, temporal phrasing, and retrieval preprocessing before performing selection
	 * (mandatory clinical core, temporal recency anchor, typed-complete types, similarity union,
	 * obs-group panel completion) exactly once for every consumer. Records come back in the
	 * CHART's most-recent-first order, which the system prompt asserts. The slice renders no
	 * focus hint: the slice IS the scope. A selection or completeness failure is explicit; only a
	 * null patient degrades to an empty chart, exactly like {@link #build}.
	 *
	 * <p>The slice is question-dependent, so callers must not attach a KV-cache scope to it (see
	 * {@code LlmInferenceService.kvCacheScopeFor}); its latency contract is the opposite of
	 * {@link #build}'s — a small fresh prefill every query instead of a big amortized one.
	 *
	 * <p>Querystore surfaces any backend cap through {@link ContextSlice#isChartTruncated()}.
	 * Scoped and full-chart paths both withhold the answer when that signal is true.
	 */
	PatientChart buildScoped(Patient patient, String question) {
		long buildStart = System.currentTimeMillis();
		if (patient == null || patient.getUuid() == null) {
			log.info("[timing] querystoreScopedBuild patient={} types=unresolved chartDocs=0 simHits=0 slice=0 rpcMs=0 serializeMs=0 totalMs={} outcome=skipped",
					patient == null ? null : patient.getPatientId(), System.currentTimeMillis() - buildStart);
			return markScoped(emptyChart(patient));
		}

		QueryStoreService queryStore = resolveQueryStoreOrNull();
		if (queryStore == null) {
			log.warn(QUERYSTORE_UNAVAILABLE_MSG);
			log.info("[timing] querystoreScopedBuild patient={} types=unresolved chartDocs=0 simHits=0 slice=0 rpcMs=0 serializeMs=0 totalMs={} outcome=unavailable",
					patient.getPatientId(), System.currentTimeMillis() - buildStart);
			throw new IllegalStateException(QUERYSTORE_UNAVAILABLE_MSG);
		}

		// Question interpretation and retrieval preprocessing are querystore's now (its ADR
		// Decision 18): the RAW question goes over with interpretQuestion set, so the typed
		// scope, the temporal gate, panel expansion, and stopword stripping run once, at the
		// data owner, identically for every consumer. This builder contributes only what is
		// genuinely its own: module-contributed scopes (QueryScopeContributor SPI, unioned
		// server-side with the derived types) and its recency-anchor deployment knob.
		ContextSliceRequest request = new ContextSliceRequest(contributedResourceTypes(question), false);
		request.setInterpretQuestion(true);
		request.setRecencyAnchorSize(resolveScopedRecencyAnchor());

		long rpcStart = System.currentTimeMillis();
		ContextSlice slice;
		try {
			slice = queryStore.getContextSlice(patient.getUuid(), question, request);
		}
		catch (RuntimeException e) {
			log.error("QueryStore.getContextSlice failed for patient [uuid={}]", patient.getUuid(), e);
			log.info("[timing] querystoreScopedBuild patient={} types=unresolved chartDocs=0 simHits=0 slice=0 rpcMs={} serializeMs=0 totalMs={} outcome=error errorClass={}",
					patient.getPatientId(), System.currentTimeMillis() - rpcStart,
					System.currentTimeMillis() - buildStart, e.getClass().getSimpleName());
			throw new IllegalStateException("QueryStore.getContextSlice failed for patient "
					+ patient.getUuid(), e);
		}
		long rpcMs = System.currentTimeMillis() - rpcStart;

		if (slice.isChartTruncated()) {
			throw new ChartTooLargeException("QueryStore built the context slice from an incomplete chart for patient "
					+ patient.getUuid() + " (reported chart size " + slice.getChartSize()
					+ "); the answer was withheld rather than treating typed evidence as complete.");
		}

		List<ContextSliceRecord> budgeted = applyContextBudget(patient, question, slice.getRecords());

		List<QueryDocument> sliceDocs = new ArrayList<QueryDocument>(budgeted.size());
		int simHits = 0;
		for (ContextSliceRecord record : budgeted) {
			sliceDocs.add(record.getDocument());
			if (QueryStoreConstants.TIER_SIMILARITY.equals(record.getTier())) {
				simHits++;
			}
		}

		List<SerializedRecord> records = toSerializedRecords(patient, sliceDocs);
		long serializeStart = System.currentTimeMillis();
		// compressDateRuns=false: the slice is small enough to date every record, and temporal
		// questions need the date on the record itself (see the serializer overload's javadoc).
		PatientChart chart = chartSerializer.serialize(patient, records,
				Collections.<String>emptySet(), false, false);
		long serializeMs = System.currentTimeMillis() - serializeStart;
		log.info("[timing] querystoreScopedBuild patient={} types={} temporal={} chartDocs={} simHits={} slice={} rpcMs={} serializeMs={} totalMs={} outcome=ok",
				patient.getPatientId(), typesLabel(slice.getEffectiveTypes()), slice.isTemporalApplied(),
				slice.getChartSize(), simHits, records.size(),
				rpcMs, serializeMs, System.currentTimeMillis() - buildStart);
		// The typed scope IS the set of types this slice carries completely (every doc of those
		// types the chart fetch returned survived the filter above). Stamped so a consumer can tell
		// a record that is absent because the retrieved chart lacks it from one absent because the
		// slice never asked for its type — only the former is a discrepancy worth reporting.
		// Stamped only here, on the path that actually applied the filter: the degraded returns
		// above carry no records at all, so declaring completeness for them would assert a
		// guarantee no filter enforced.
		//
		chart.markCompleteFor(slice.getEffectiveTypes());
		return markScoped(chart);
	}

	/** Stamps a chart as query-scoped (every {@code buildScoped} return, including degraded
	 *  empties) so downstream KV decisions read the chart that was BUILT, not a re-read of the
	 *  chartMode GP that can disagree with it — see {@code PatientChart#isQueryScoped()}. */
	private static PatientChart markScoped(PatientChart chart) {
		chart.markQueryScoped();
		return chart;
	}

	/**
	 * Mandatory, exact-match, typed-complete, and panel-family evidence is never droppable, so it
	 * either fits the model's input budget or the turn abstains. Optional recency and similarity
	 * records are packed in policy order, then restored to chart order for rendering. Mirrors
	 * med-agent-hub's {@code select_context}: a fast accept when everything fits, otherwise a
	 * greedy priority prefix of optional records (a ceiling, never a target) up to
	 * the budget. Prompt counts include the system prompt, question, and model chat template.
	 * Returns {@code records} unchanged when no {@link TokenCounter} is configured or
	 * available (e.g. a remote engine with no assumed tokenizer route) — this feature can only
	 * ever tighten behavior, never introduce a new failure mode where none existed.
	 */
	private List<ContextSliceRecord> applyContextBudget(Patient patient, String question,
			List<ContextSliceRecord> records) {
		TokenCounter counter = tokenCounter;
		if (counter == null || !counter.isAvailable()) {
			return records;
		}
		int budget = counter.inputBudget();

		List<ContextSliceRecord> protectedRecords = new ArrayList<ContextSliceRecord>();
		for (ContextSliceRecord record : records) {
			if (isProtectedTier(record.getTier())) {
				protectedRecords.add(record);
			}
		}
		int protectedTokens = counter.countPrompt(renderedTextOf(patient, protectedRecords), question);
		if (protectedTokens > budget) {
			List<String> protectedIds = new ArrayList<String>();
			for (ContextSliceRecord record : protectedRecords) {
				protectedIds.add(record.getDocument().getResourceUuid());
			}
			throw new InsufficientContextException(
					"Required mandatory, exact, typed-complete, or panel evidence (" + protectedTokens
							+ " tokens) exceeds the "
							+ budget + "-token model input budget for patient " + patient.getUuid() + ".",
					protectedIds);
		}

		if (counter.countPrompt(renderedTextOf(patient, records), question) <= budget) {
			return records;
		}
		Map<ContextSliceRecord, Integer> positions = new IdentityHashMap<ContextSliceRecord, Integer>();
		for (int index = 0; index < records.size(); index++) {
			positions.put(records.get(index), Integer.valueOf(index));
		}

		List<ContextSliceRecord> optional = new ArrayList<ContextSliceRecord>();
		for (ContextSliceRecord record : records) {
			if (!isProtectedTier(record.getTier())) {
				optional.add(record);
			}
		}
		Collections.sort(optional, (left, right) -> {
			int tier = Integer.compare(optionalTierPriority(left), optionalTierPriority(right));
			if (tier != 0) {
				return tier;
			}
			int rank = Integer.compare(optionalRank(left), optionalRank(right));
			return rank != 0 ? rank : Integer.compare(positions.get(left), positions.get(right));
		});

		int low = 0;
		int high = optional.size();
		while (low < high) {
			int size = low + (high - low + 1) / 2;
			List<ContextSliceRecord> candidate = new ArrayList<ContextSliceRecord>(protectedRecords);
			candidate.addAll(optional.subList(0, size));
			Collections.sort(candidate,
					(left, right) -> Integer.compare(positions.get(left), positions.get(right)));
			if (counter.countPrompt(renderedTextOf(patient, candidate), question) <= budget) {
				low = size;
			} else {
				high = size - 1;
			}
		}
		List<ContextSliceRecord> selected = new ArrayList<ContextSliceRecord>(protectedRecords);
		selected.addAll(optional.subList(0, low));
		Collections.sort(selected,
				(left, right) -> Integer.compare(positions.get(left), positions.get(right)));
		return selected;
	}

	private static boolean isProtectedTier(String tier) {
		return QueryStoreConstants.TIER_MANDATORY.equals(tier)
				|| QueryStoreConstants.TIER_EXACT.equals(tier)
				|| QueryStoreConstants.TIER_TYPED.equals(tier)
				|| QueryStoreConstants.TIER_PANEL.equals(tier);
	}

	private static int optionalTierPriority(ContextSliceRecord record) {
		if (QueryStoreConstants.TIER_RECENCY_ANCHOR.equals(record.getTier())) {
			return 0;
		}
		if (QueryStoreConstants.TIER_SIMILARITY.equals(record.getTier())) {
			return 1;
		}
		return 2;
	}

	private static int optionalRank(ContextSliceRecord record) {
		return record.getRank() == null ? Integer.MAX_VALUE : record.getRank().intValue();
	}

	private String renderedTextOf(Patient patient, List<ContextSliceRecord> records) {
		List<QueryDocument> docs = new ArrayList<QueryDocument>(records.size());
		for (ContextSliceRecord record : records) {
			docs.add(record.getDocument());
		}
		return chartSerializer.serialize(patient, toSerializedRecords(patient, docs),
				Collections.<String>emptySet(), false, false).getText();
	}


	/** The [timing] log label for the slice's effective typed scope (querystore's derived
	 *  interpretation unioned with contributed types): {@code TOPICAL} when empty, else the
	 *  sorted types joined with {@code +} — one greppable token per line either way. */
	private static String typesLabel(Set<String> effectiveTypes) {
		if (effectiveTypes == null || effectiveTypes.isEmpty()) {
			return "TOPICAL";
		}
		List<String> sorted = new ArrayList<String>(effectiveTypes);
		Collections.sort(sorted);
		return String.join("+", sorted);
	}


	/**
	 * Builds a focused chart of only the top-K querystore records most relevant to {@code question},
	 * for the progressive-reasoning preview pass. Uses the SAME relevance ranking the preFilter focus
	 * hint uses ({@link QueryStoreService#searchByPatient}), but here the K records ARE the chart (a
	 * few hundred tokens) rather than a hint over the full chart — so the preview's prefill is small.
	 * Returns an empty chart on a null/blank question, missing patient, unavailable querystore, or an
	 * empty hit list; the caller treats an empty focused chart as "no preview".
	 */
	PatientChart buildFocused(Patient patient, String question) {
		long buildStart = System.currentTimeMillis();
		// Every exit emits a [timing] querystoreFocusedBuild line (outcome=skipped|unavailable|error|ok),
		// matching build()'s contract so a dashboard grepping outcome= doesn't undercount focused-build
		// failures.
		if (patient == null || patient.getUuid() == null || question == null || question.trim().isEmpty()) {
			log.info("[timing] querystoreFocusedBuild patient={} hits=0 rpcMs=0 serializeMs=0 totalMs={} outcome=skipped",
					patient == null ? null : patient.getPatientId(), System.currentTimeMillis() - buildStart);
			return chartSerializer.serialize(patient, Collections.<SerializedRecord>emptyList());
		}

		// The full-chart build() runs first on the same request and logs the actionable
		// "check the querystore module" remediation; here we only record the outcome for parity.
		QueryStoreService queryStore = resolveQueryStoreOrNull();
		if (queryStore == null) {
			log.info("[timing] querystoreFocusedBuild patient={} hits=0 rpcMs=0 serializeMs=0 totalMs={} outcome=unavailable",
					patient.getPatientId(), System.currentTimeMillis() - buildStart);
			return emptyChart(patient);
		}

		long rpcStart = System.currentTimeMillis();
		List<QueryDocument> hits;
		try {
			String preprocessedQuestion = QueryPreprocessor.stripQueryStopwords(question);
			hits = queryStore.searchByPatient(patient.getUuid(), preprocessedQuestion,
					resolveProgressiveReasoningTopK());
		}
		catch (RuntimeException e) {
			log.warn("QueryStore.searchByPatient failed for focused build [uuid={}] — returning empty focused chart",
					patient.getUuid(), e);
			log.info("[timing] querystoreFocusedBuild patient={} hits=0 rpcMs={} serializeMs=0 totalMs={} outcome=error errorClass={}",
					patient.getPatientId(), System.currentTimeMillis() - rpcStart,
					System.currentTimeMillis() - buildStart, e.getClass().getSimpleName());
			return chartSerializer.serialize(patient, Collections.<SerializedRecord>emptyList());
		}
		long rpcMs = System.currentTimeMillis() - rpcStart;

		List<SerializedRecord> records = toSerializedRecords(patient, hits);
		long serializeStart = System.currentTimeMillis();
		PatientChart chart = chartSerializer.serialize(patient, records);
		long serializeMs = System.currentTimeMillis() - serializeStart;
		log.info("[timing] querystoreFocusedBuild patient={} hits={} rpcMs={} serializeMs={} totalMs={} outcome=ok",
				patient.getPatientId(), records.size(), rpcMs, serializeMs,
				System.currentTimeMillis() - buildStart);
		return chart;
	}

	/** The empty chart every degraded path returns — one helper so empty-chart semantics cannot
	 *  drift between the three build paths. */
	private PatientChart emptyChart(Patient patient) {
		return chartSerializer.serialize(patient, Collections.<SerializedRecord>emptyList());
	}

	/**
	 * Resolves {@link QueryStoreService}, or returns null on failure. {@code LinkageError} covers
	 * {@code NoClassDefFoundError} when the querystore-api jar is absent at runtime — the
	 * {@code QueryStoreService.class} literal forces JVM linkage, which {@code APIException}
	 * doesn't catch. Logging is the CALLER's job (build/buildScoped WARN the shared
	 * {@link #QUERYSTORE_UNAVAILABLE_MSG}; buildFocused stays silent because build() has already
	 * warned on the same request) so the shared catch cannot change any path's log behavior.
	 */
	private QueryStoreService resolveQueryStoreOrNull() {
		try {
			return resolveQueryStoreService();
		}
		catch (APIException | LinkageError e) {
			return null;
		}
	}

	private static String getPatientChartFailedMessage(String patientUuid) {
		return "QueryStore.getPatientChart failed for patient [uuid=" + patientUuid + "]";
	}

	/**
	 * Runs the similarity search and collects hit uuids, degrading to an empty set on failure with
	 * the caller-supplied WARN (each mode words its own degradation: focus hint vs typed slice).
	 * Shared by {@link #build}'s focus-hint pass and {@link #buildScoped}'s catch-all pass so the
	 * search→collect→degrade shape stays identical across modes.
	 */
	private Set<String> searchSimilarityUuids(QueryStoreService queryStore, Patient patient,
			String preprocessedQuestion, String degradeWarning) {
		try {
			List<QueryDocument> hits = queryStore.searchByPatient(patient.getUuid(),
					preprocessedQuestion, resolveQueryStoreTopK());
			return collectFocusUuids(hits);
		}
		catch (RuntimeException e) {
			log.warn(degradeWarning, patient.getUuid(), e);
			return Collections.<String>emptySet();
		}
	}

	/** Collects {@code resource_uuid}s from a hit list, skipping nulls and malformed docs.
	 *  These uuids are mapped to 1-based chart indices in {@code PatientChartSerializer.serialize}
	 *  to render the LLM-facing focus hint. */
	private Set<String> collectFocusUuids(List<QueryDocument> hits) {
		if (hits == null || hits.isEmpty()) {
			return Collections.<String>emptySet();
		}
		Set<String> uuids = new HashSet<String>(hits.size() * 2);
		for (QueryDocument doc : hits) {
			if (doc != null && doc.getResourceUuid() != null) {
				uuids.add(doc.getResourceUuid());
			}
		}
		return uuids;
	}

	/**
	 * Record types rendered UNDATED because their querystore {@code record_date} is
	 * ADMINISTRATIVE ({@code dateCreated} — see querystore's {@code PatientRecordSerializer} /
	 * {@code AllergyRecordSerializer}), not a clinical event date. Rendering it as a "(date)"
	 * label misleads temporal reasoning: measured on the rc.2 standalone, "when was the last
	 * visit?" was answered from the allergy record's creation date in one mode and the patient
	 * record's in the other. The date still drives querystore's chart ordering.
	 *
	 * <p>Deliberately NOT the complete set of administratively-dated types. querystore's
	 * {@code condition} and {@code diagnosis} documents also carry {@code dateCreated}
	 * unconditionally (their serializers have no clinical-date fallback), and {@code program} /
	 * {@code medication_dispense} fall back to it when {@code dateEnrolled} /
	 * {@code dateHandedOver} is null — all of those still render their date, so a
	 * "when was X diagnosed?" answer can quote record-keeping time. They are excluded here
	 * because blanket-undating them is not obviously an improvement: an undated condition list
	 * loses chronology the model may need, and condition's clinical {@code onset_date} sits in
	 * querystore doc metadata this module does not render yet. Extending this set (or rendering
	 * onset instead) changes slice bytes in both modes and is reserved for the two mandatory
	 * evaluation gates (ADR Decision 28).
	 */
	private static final Set<String> ADMIN_DATED_TYPES = Collections.unmodifiableSet(
			new HashSet<String>(Arrays.asList(PATIENT_RESOURCE_TYPE, "allergy")));

	/** Converts a querystore hit list into the chartsearchai serializer's input shape,
	 *  dropping null and malformed docs with a WARN so operators can spot upstream
	 *  serialization regressions without losing the rest of the chart. */
	private List<SerializedRecord> toSerializedRecords(Patient patient, List<QueryDocument> docs) {
		if (docs == null || docs.isEmpty()) {
			return Collections.<SerializedRecord>emptyList();
		}
		OrderCurrency orderCurrency = readOrderCurrency(patient, docs);
		List<SerializedRecord> out = new ArrayList<SerializedRecord>(docs.size());
		for (QueryDocument doc : docs) {
			if (doc == null) {
				log.warn("Skipping null QueryDocument");
				continue;
			}
			if (doc.getResourceType() == null || doc.getResourceUuid() == null) {
				log.warn("Skipping malformed QueryDocument: type={} uuid={}",
						doc.getResourceType(), doc.getResourceUuid());
				continue;
			}
			String text = doc.getText() == null ? "" : doc.getText();
			// Carry the obs-group metadata (a lab panel, a vital-signs set, etc.) through so the
			// serializer can surface group membership to the LLM. querystore stores the group identity
			// ONLY in metadata, never in the doc text (ADR Decision 6: keeps citations clean, no sibling
			// duplication), and makes clustering the consumer's responsibility. Dropping it here is
			// exactly what made group membership invisible to the LLM.
			// Administrative dates (patient/allergy dateCreated) are not clinical event dates —
			// suppress them so the LLM never reads record-keeping time as encounter time.
			Date recordDate = ADMIN_DATED_TYPES.contains(doc.getResourceType())
					? null : DateFormatUtil.toLegacyDate(doc.getDate());
			out.add(new SerializedRecord(doc.getResourceType(), doc.getResourceUuid(),
					text, recordDate, Collections.<String>emptyList(),
					metadataString(doc, QueryStoreConstants.FIELD_OBS_GROUP_UUID),
					metadataString(doc, QueryStoreConstants.FIELD_OBS_GROUP_CONCEPT_NAME),
					orderCurrency.forRecord(doc.getResourceType(), doc.getResourceUuid())));
		}
		return out;
	}

	/**
	 * The patient's order-currency reading for one chart assembly — which of their orders are in
	 * force right now, and which orders are theirs at all — or the explicit statement that the module
	 * could not read them (issue #317).
	 *
	 * <p><strong>Two sets, and the second one is the guard.</strong> The obvious implementation reads
	 * only the active set and treats "absent from it" as "ended". That is wrong twice. It cannot tell
	 * an order that ended from a record whose order the module cannot identify at all — and the mark
	 * is keyed on querystore's {@code resourceUuid} contract, so were that contract ever to change,
	 * "absent from the active set" would become true of EVERY record and the chart would tell a
	 * clinician that every one of this patient's prescriptions had ended. Requiring the uuid to be one
	 * of the patient's own orders turns the mark into a positive fact — "this order is theirs and it
	 * is not in force" — instead of an inference from absence, and it leaves a record the module
	 * cannot identify unmarked, which is also what preserves the active-order reconciliation's name
	 * fallback for exactly that record.
	 *
	 * <p>An EMPTY active set is a perfectly good reading, not a failure: a patient whose only
	 * prescription has ended has one, and that is the arrangement issue #315 reported. So emptiness
	 * must never stand in for "could not read", which is why {@link #UNREAD} is its own state rather
	 * than an empty set. {@code PatientClinicalContext.contraindicationRecordsRead()} is the same
	 * distinction one layer along.
	 */
	private static final class OrderCurrency {

		/** The reading that answers nothing about anything, and the only one a failed read produces. */
		private static final OrderCurrency UNREAD = new OrderCurrency(null, null);

		private final Set<String> activeOrderUuids;

		private final Set<String> allOrderUuids;

		private OrderCurrency(Set<String> activeOrderUuids, Set<String> allOrderUuids) {
			this.activeOrderUuids = activeOrderUuids;
			this.allOrderUuids = allOrderUuids;
		}

		static OrderCurrency unread() {
			return UNREAD;
		}

		static OrderCurrency of(Set<String> activeOrderUuids, Set<String> allOrderUuids) {
			return new OrderCurrency(activeOrderUuids, allOrderUuids);
		}

		/**
		 * What this reading says about one chart record: {@code TRUE} in force, {@code FALSE} this
		 * patient's order and not in force, {@code null} nothing known. The single place the answer is
		 * decided, so the chart line and the grounding mapping cannot be given different ones.
		 */
		Boolean forRecord(String resourceType, String resourceUuid) {
			if (activeOrderUuids == null || resourceUuid == null
					|| !DRUG_ORDER_RESOURCE_TYPE.equals(resourceType)) {
				return null;
			}
			if (activeOrderUuids.contains(resourceUuid)) {
				return Boolean.TRUE;
			}
			return allOrderUuids.contains(resourceUuid) ? Boolean.FALSE : null;
		}
	}

	/**
	 * Reads the patient's orders once for this chart assembly, or reports that it could not.
	 *
	 * <p>Skipped entirely when the retrieved documents carry no prescription: the read is an
	 * {@code OrderService} call on a path that runs for every query for every patient, sized by the
	 * patient's whole order history, and a chart with nothing to mark has nothing to spend it on.
	 *
	 * <p>How often "every query" is depends on the mode, and it is not always once. Under
	 * {@code chartMode=fullChart} with {@code chartsearchai.progressiveReasoning.enabled=true} the
	 * preview pass ({@link #buildFocused}) and the committed {@link #build} each assemble their own
	 * chart, so this read happens TWICE per request; where {@code chartsearchai.drugReference.enabled}
	 * is on, that layer's own {@code getActiveOrders} is a third order query on that shape, against
	 * the one the module made before issue #317. The shipped {@code queryScoped} default reads once
	 * ({@code buildFocused} returns early in that mode), plus the safety layer's where it is on.
	 * Deliberately not hoisted to one reading per request: the two builds are separate calls through
	 * {@code ChartBuildingStrategy}, so sharing one would mean either a new parameter on that
	 * interface or per-request state held on a Spring singleton, which is what CLAUDE.md's
	 * memoisation rule refuses. What that costs is a residue
	 * rather than a contradiction the clinician sees: the two charts of one request read the orders
	 * at two instants, so an order lapsing between them is marked one way in the preview and the
	 * other way in the committed answer. Nothing here pins that, and no case discriminates it.
	 *
	 * <p>WARN rather than DEBUG on failure. The chart degrades silently — every drug-order record
	 * simply loses its mark and reads exactly as it did before this feature existed — so nothing else
	 * anywhere says the module has stopped answering a question it normally answers.
	 * {@link #readingOf} logs its own per-order failure at WARN resting on that same argument; what
	 * is particular to THIS one is its scope, which is a whole chart's marks rather than one record's.
	 * {@code PatientClinicalContextBuilder}'s own active-order catch logs at DEBUG and sets no flag
	 * at all; that is the shape issue #317 names as the hazard, and it is deliberately not copied
	 * here.
	 */
	private OrderCurrency readOrderCurrency(Patient patient, List<QueryDocument> docs) {
		if (patient == null || !carriesADrugOrderRecord(docs)) {
			return OrderCurrency.unread();
		}
		try {
			return readingOf(resolveAllOrders(patient));
		}
		catch (RuntimeException e) {
			log.warn("Could not read orders for patient [uuid={}] — this chart's drug-order records "
					+ "will not say whether each prescription is still in force, so the answer may "
					+ "infer it from the record's dates. Chart assembly is otherwise unaffected.",
					patient.getUuid(), e);
			return OrderCurrency.unread();
		}
	}

	private static boolean carriesADrugOrderRecord(List<QueryDocument> docs) {
		for (QueryDocument doc : docs) {
			if (doc != null && DRUG_ORDER_RESOURCE_TYPE.equals(doc.getResourceType())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Splits the patient's orders into the two sets a reading needs, in ONE pass over ONE service
	 * read.
	 *
	 * <p>The obvious implementation asks {@code OrderService} twice — once for the active orders and
	 * once for all of them — and that is what this did first. {@code getAllOrdersByPatient} returns
	 * every order the patient has, so the second call is answerable from the first, and asking once
	 * also makes the mark's contract literally true rather than true by proxy:
	 * {@code PatientChartSerializer.ACTIVE_ORDER_LABEL} says the mark reports {@code Order.isActive()}
	 * and nothing more, and now it does.
	 *
	 * <p><strong>{@code Order.isActive()} is not simply that SQL predicate in Java, and the difference
	 * is why each order is evaluated on its own.</strong> On every leg checked they agree — voided, a
	 * {@code DISCONTINUE} action, a null or future {@code dateActivated}, a future
	 * {@code dateStopped}, an {@code autoExpireDate} exactly equal to now, and
	 * {@code scheduledDate}/{@code ON_SCHEDULED_DATE}, which neither of them reads. But
	 * {@code Order.isDiscontinued} and {@code Order.isExpired} both THROW, before any other test, when
	 * {@code dateStopped} is after {@code autoExpireDate}, where the SQL simply answers. Core does not
	 * prevent that row: {@code OrderValidator} compares {@code dateActivated} against each of those
	 * dates and never compares them against each other, and {@code OrderServiceImpl.stopOrder} writes
	 * it through the public API when {@code order.allowSettingStopDateOnInactiveOrders} is on.
	 *
	 * <p>Evaluated in one try around the whole walk — which is how this was first written — a single
	 * such row anywhere in the patient's history takes the mark off EVERY drug-order record on every
	 * chart for that patient, which is the silent reversion to pre-#317 behaviour the feature exists
	 * to remove. Per order, it costs exactly the one record that order stands behind.
	 *
	 * <p><strong>The uuid must not reach EITHER set, and the statement order is what does that.</strong>
	 * {@code isActive()} is called first and its result held; only then is the uuid recorded as known.
	 * Wrapping the two statements the other way round — the natural edit, and what "each order on its
	 * own" reads as — leaves a throwing order in {@code known} and out of {@code active}, which is the
	 * combination {@code forRecord} answers {@code FALSE} to: the module would tell a clinician a
	 * prescription it could not evaluate had ended. Silence for what cannot be evaluated, never a
	 * denial.
	 *
	 * <p>The all-orders set carries VOIDED orders too — a consequence of {@code getAllOrdersByPatient}
	 * rather than a choice made here, and left alone: such a record is that patient's either way, and
	 * marking it not-active is true of it. querystore does not index voided rows, so it is unlikely to
	 * arise at all. Nothing pins this, and no case here discriminates it.
	 */
	private static OrderCurrency readingOf(List<Order> allOrders) {
		Set<String> active = new HashSet<String>();
		Set<String> known = new HashSet<String>();
		List<String> unevaluable = new ArrayList<String>();
		for (Order order : allOrders == null ? Collections.<Order>emptyList() : allOrders) {
			if (order == null || order.getUuid() == null) {
				continue;
			}
			try {
				boolean isActive = order.isActive();
				known.add(order.getUuid());
				if (isActive) {
					active.add(order.getUuid());
				}
			}
			catch (RuntimeException e) {
				// Collected and reported ONCE below rather than logged here. A bad row is a permanent
				// property of the patient's chart, so BOTH forms repeat on every query for that
				// patient for as long as the row stands — that is not what separates them, and this
				// comment used to rest on it. What a per-order log adds on top is one line per bad
				// row for a patient carrying several. The clause this comment used to end with —
				// "and a privilege failure would repeat once per order" — named a failure that cannot
				// reach here: a missing Get Orders raises APIAuthenticationException out of
				// resolveAllOrders, one level up and outside this loop, where readOrderCurrency's own
				// catch reports it once; Order.isActive() reads the entity's own fields and consults
				// no privilege at all.
				unevaluable.add(order.getUuid());
			}
		}
		if (!unevaluable.isEmpty()) {
			log.warn("Could not decide whether {} of this patient's order(s) are in force, so a chart "
					+ "record for any of them will not say either way; every other order is unaffected. "
					+ "The usual cause is an order whose stop date is after its auto-expire date, which "
					+ "core neither validates nor refuses to save. Orders: {}",
					unevaluable.size(), unevaluable);
		}
		return OrderCurrency.of(active, known);
	}

	/** Reads a metadata value as a trimmed String, or {@code null} when absent or blank.
	 *  querystore stores these values as Strings (see ObsRecordSerializer.putGroupFields); the
	 *  defensive toString()/blank handling keeps a malformed upstream value from leaking an empty
	 *  or non-string token into the LLM prompt. Relies on {@link QueryDocument#getMetadata()} being
	 *  contractually non-null (it returns an unmodifiable view of a field initialized to an empty
	 *  map) — a regression making it nullable would NPE here and degrade the whole chart to empty. */
	private static String metadataString(QueryDocument doc, String key) {
		Object value = doc.getMetadata().get(key);
		if (value == null) {
			return null;
		}
		String s = value.toString().trim();
		return s.isEmpty() ? null : s;
	}

	/** Seam for tests: production reads every order this patient has, of any type, and decides
	 *  which are in force with {@link Order#isActive()} rather than asking the service twice. */
	protected List<Order> resolveAllOrders(Patient patient) {
		return Context.getOrderService().getAllOrdersByPatient(patient);
	}

	/** Seam for tests: production resolves via the OpenMRS context. */
	protected QueryStoreService resolveQueryStoreService() {
		return Context.getService(QueryStoreService.class);
	}

	/** Seam for tests: production reads the global property. */
	protected int resolveQueryStoreTopK() {
		return PipelineSettings.getQueryStoreTopK();
	}

	/** Seam for tests: production resolves the registered {@link QueryScopeContributor} beans LIVE
	 *  via the OpenMRS context on each call — the same lazy-resolution posture this class uses for
	 *  {@link QueryStoreService} (see the class javadoc), NOT a cached {@code @Autowired} snapshot
	 *  that would silently miss a contributor module started after this singleton was wired.
	 *  {@link Context#getRegisteredComponents} returns an empty list when none are registered. */
	protected List<QueryScopeContributor> resolveScopeContributors() {
		return Context.getRegisteredComponents(QueryScopeContributor.class);
	}

	/**
	 * Union of every registered {@link QueryScopeContributor}'s claimed resourceTypes for this
	 * question — the module-extension point unioned into {@link #buildScoped}'s typed scope.
	 * Fail-safe throughout: resolving the contributor beans, and each contributor's own claim, are
	 * both guarded — a failure (or a null return) is logged and skipped, never breaking chart
	 * assembly (the same defense-in-depth as querystore resolution). Returns an empty set when no
	 * contributor claims anything.
	 */
	private Set<String> contributedResourceTypes(String question) {
		List<QueryScopeContributor> contributors;
		try {
			contributors = resolveScopeContributors();
		}
		catch (RuntimeException e) {
			log.warn("Resolving QueryScopeContributor beans failed; proceeding with the built-in scope only", e);
			return Collections.<String> emptySet();
		}
		if (contributors == null || contributors.isEmpty()) {
			return Collections.<String> emptySet();
		}
		Set<String> types = new HashSet<String>();
		for (QueryScopeContributor contributor : contributors) {
			if (contributor == null) {
				continue;
			}
			try {
				Set<String> claimed = contributor.scopedResourceTypes(question);
				if (claimed != null && !claimed.isEmpty()) {
					types.addAll(claimed);
					if (log.isDebugEnabled()) {
						log.debug("QueryScopeContributor [{}] claimed {} for this query",
								domainName(contributor), claimed);
					}
				}
			}
			catch (RuntimeException e) {
				// A misbehaving contributor must never break the answer path.
				log.warn("QueryScopeContributor [{}] failed; ignoring its scope claim for this query",
						contributor.getClass().getName(), e);
			}
		}
		return types;
	}

	/** The contributor's self-reported domain name for logging, falling back to the class name if the
	 *  contributor's own {@code getDomainName()} misbehaves — so a diagnostic log can never corrupt
	 *  the (already-applied) claim or emit a misleading "claim ignored" message. */
	private static String domainName(QueryScopeContributor contributor) {
		try {
			return contributor.getDomainName();
		}
		catch (RuntimeException e) {
			return contributor.getClass().getName();
		}
	}

	/** The query-scoped slice's recency anchor: the chart's N most recent records are always in
	 *  the slice regardless of similarity rank (see {@link #buildScoped}). Fixed for now — ~15
	 *  records is a few hundred tokens, enough to cover the latest encounter's readings. */
	protected int resolveScopedRecencyAnchor() {
		return 15;
	}

	/** Seam for tests: production reads the global property. */
	protected int resolveProgressiveReasoningTopK() {
		return PipelineSettings.getProgressiveReasoningTopK();
	}

	/** Seam for tests: production reads the global property. */
	protected boolean resolveUsePreFilter() {
		return PipelineSettings.usePreFilter();
	}

	protected boolean resolveDedupGroupLabels() {
		return PipelineSettings.dedupGroupLabels();
	}
}
