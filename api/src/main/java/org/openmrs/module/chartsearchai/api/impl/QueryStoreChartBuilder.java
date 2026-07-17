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
import java.util.List;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;
import org.openmrs.module.chartsearchai.util.DateFormatUtil;
import org.openmrs.module.querystore.QueryStoreConstants;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.model.QueryDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bridge to the querystore module's read API. querystore is a required module, so it is present at
 * runtime; the service is still resolved lazily via {@link Context#getService(Class)} (rather than
 * injected) as defense-in-depth — a resolution failure degrades to an empty chart, the same outcome
 * as a search returning no hits, instead of breaking chart assembly.
 *
 * <p>{@link #build} (the fullChart mode) always fetches the full patient chart via
 * {@link QueryStoreService#getPatientChart(String)} so the chart bytes sent to
 * the LLM are a function of the patient only — that's the property
 * llama-server's KV-cache reuse needs in order to skip ~99% of the prefill on
 * subsequent queries for the same patient. {@link #buildScoped}
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
 * <p>The {@code protected resolve*} methods and the package-private
 * {@link #setChartSerializer} are test seams, not an extension point.
 * Subclassing this bean outside the test package is not supported.
 */
@Component("chartSearchAi.queryStoreChartBuilder")
class QueryStoreChartBuilder {

	private static final Logger log = LoggerFactory.getLogger(QueryStoreChartBuilder.class);

	// Mode labels emitted in the [timing] querystoreBuild log lines so ops dashboards can
	// distinguish the two dispatch shapes. preFilter mode does the extra searchByPatient
	// call for the focus hint; fullChart skips it. Kept as compile-time constants so a typo
	// on any future log line surfaces at compile time rather than as a silently-dropped grep.
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

	/** Operator remediation for an unresolvable QueryStoreService, WARNed identically by
	 *  {@link #build} and {@link #buildScoped} (buildFocused stays silent — build() has already
	 *  warned on the same request). One constant so the degradation message cannot drift
	 *  between modes that never run side by side on one deployment. */
	private static final String QUERYSTORE_UNAVAILABLE_MSG =
			"QueryStoreService is unavailable — querystore is a required module, so this "
					+ "indicates a querystore startup failure; check the querystore module. "
					+ "Returning empty chart.";

	@Autowired
	private PatientChartSerializer chartSerializer;

	/** Test seam: production wires {@link PatientChartSerializer} via {@link Autowired}.
	 *  Package-private so {@code QueryStoreChartBuilderTest} can inject a real serializer
	 *  without bringing up Spring; matches the {@code resolveX()} method-override seam
	 *  pattern used for {@link QueryStoreService} and topK. */
	void setChartSerializer(PatientChartSerializer chartSerializer) {
		this.chartSerializer = chartSerializer;
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
			return emptyChart(patient);
		}

		// Full chart first — this is what the LLM sees and what determines the KV-cache
		// prefix. Always called regardless of mode so the chart bytes are a function of
		// the patient only.
		long rpcStart = System.currentTimeMillis();
		List<QueryDocument> chartDocs;
		try {
			chartDocs = queryStore.getPatientChart(patient.getUuid());
		}
		catch (RuntimeException e) {
			log.error(GET_PATIENT_CHART_FAILED_MSG, patient.getUuid(), e);
			long failMs = System.currentTimeMillis() - rpcStart;
			log.info("[timing] querystoreBuild patient={} mode={} hits=0 focusHits=0 rpcMs={} serializeMs=0 totalMs={} outcome=error errorClass={}",
					patient.getPatientId(), mode, failMs, System.currentTimeMillis() - buildStart,
					e.getClass().getSimpleName());
			return emptyChart(patient);
		}

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

		List<SerializedRecord> records = toSerializedRecords(chartDocs);
		long serializeStart = System.currentTimeMillis();
		PatientChart chart = chartSerializer.serialize(patient, records, focusUuids, resolveDedupGroupLabels());
		long serializeMs = System.currentTimeMillis() - serializeStart;
		long totalMs = System.currentTimeMillis() - buildStart;
		log.info("[timing] querystoreBuild patient={} mode={} hits={} focusHits={} rpcMs={} serializeMs={} totalMs={} outcome=ok",
				patient.getPatientId(), mode, records.size(), focusHits, rpcMs, serializeMs, totalMs);
		return chart;
	}

	/**
	 * Builds the query-scoped slice chart for {@code chartsearchai.chartMode=queryScoped}: every
	 * record of the question's typed scope (complete by construction — see {@link QueryScopeRouter}),
	 * unioned with the querystore similarity top-K (the semantic catch-all), plus the demographics
	 * {@code patient} record — all in the CHART's most-recent-first order, never the similarity
	 * ranking's, because the system prompt asserts "sorted most recent first". The slice renders no
	 * focus hint: the slice IS the scope. A similarity failure degrades to the typed slice alone; a
	 * null patient degrades to an empty chart, exactly like {@link #build}.
	 *
	 * <p>The slice is question-dependent, so callers must not attach a KV-cache scope to it (see
	 * {@code LlmInferenceService.kvCacheScopeFor}); its latency contract is the opposite of
	 * {@link #build}'s — a small fresh prefill every query instead of a big amortized one.
	 *
	 * <p>Completeness caveat: querystore's Elasticsearch tier caps {@code getPatientChart} at its
	 * 10&nbsp;000 most recent documents (silently dropping the older tail). fullChart mode fails
	 * loud on such patients ({@code ChartTooLargeException} — the chart overflows the context
	 * window long before 10&nbsp;000 docs); a scoped slice keeps working, so a typed enumeration
	 * could silently omit pre-cutoff records. WARNed below when the fetch lands on the cap.
	 */
	PatientChart buildScoped(Patient patient, String question) {
		long buildStart = System.currentTimeMillis();
		if (patient == null || patient.getUuid() == null) {
			log.info("[timing] querystoreScopedBuild patient={} intent=unknown chartDocs=0 simHits=0 slice=0 rpcMs=0 serializeMs=0 totalMs={} outcome=skipped",
					patient == null ? null : patient.getPatientId(), System.currentTimeMillis() - buildStart);
			return markScoped(emptyChart(patient));
		}

		QueryScopeRouter.Intent intent = QueryScopeRouter.route(question);
		Set<String> typedScope = QueryScopeRouter.typedSlice(intent);

		QueryStoreService queryStore = resolveQueryStoreOrNull();
		if (queryStore == null) {
			log.warn(QUERYSTORE_UNAVAILABLE_MSG);
			log.info("[timing] querystoreScopedBuild patient={} intent={} chartDocs=0 simHits=0 slice=0 rpcMs=0 serializeMs=0 totalMs={} outcome=unavailable",
					patient.getPatientId(), intent, System.currentTimeMillis() - buildStart);
			return markScoped(emptyChart(patient));
		}

		long rpcStart = System.currentTimeMillis();
		List<QueryDocument> chartDocs;
		try {
			chartDocs = queryStore.getPatientChart(patient.getUuid());
		}
		catch (RuntimeException e) {
			log.error(GET_PATIENT_CHART_FAILED_MSG, patient.getUuid(), e);
			log.info("[timing] querystoreScopedBuild patient={} intent={} chartDocs=0 simHits=0 slice=0 rpcMs={} serializeMs=0 totalMs={} outcome=error errorClass={}",
					patient.getPatientId(), intent, System.currentTimeMillis() - rpcStart,
					System.currentTimeMillis() - buildStart, e.getClass().getSimpleName());
			return markScoped(emptyChart(patient));
		}

		// querystore's ES tier returns at most its 10 000 most recent docs (older tail silently
		// dropped) — at the cap, "complete by construction" typed slices may be missing
		// pre-cutoff records, and unlike fullChart (which fails loud on context overflow) the
		// slice keeps working. Surface it for operators.
		if (chartDocs.size() >= QUERYSTORE_ES_CHART_CAP) {
			log.warn("getPatientChart returned {} docs for patient [uuid={}] — at querystore's ES "
					+ "tier cap; typed slices may silently omit records older than the cutoff.",
					chartDocs.size(), patient.getUuid());
		}

		// Similarity top-K uuids — the semantic catch-all beyond the typed scope. Lab-panel
		// abbreviations are expanded first ("BMP" → "+ basic metabolic panel") so the retrieval
		// text carries the full concept name querystore indexed. Failure degrades to the typed
		// slice alone (never blocks the answer), mirroring build()'s focus-hint contract.
		Set<String> similarityUuids = Collections.<String>emptySet();
		if (question != null && !question.trim().isEmpty()) {
			similarityUuids = searchSimilarityUuids(queryStore, patient,
					QueryPreprocessor.stripQueryStopwords(
							QueryPreprocessor.expandLabPanelAbbreviations(question)),
					"QueryStore.searchByPatient failed for scoped build [uuid={}] — proceeding with the typed slice only");
		}
		long rpcMs = System.currentTimeMillis() - rpcStart;

		// Filter the chart (already most-recent-first) down to the slice, preserving its order.
		// TEMPORAL questions additionally carry the recency anchor — the first N chart records:
		// similarity ranks by meaning, not date, so without it "most recent X" can lose the newest
		// reading to older, better-matching records (measured: a stale systolic quoted). The anchor
		// is gated purely on temporal phrasing (QueryScopeRouter.isTemporal), independent of typed
		// scope; non-temporal questions get none, which is what keeps an absent-topic slice from
		// baiting enumeration.
		int recencyAnchor = QueryScopeRouter.isTemporal(question) ? resolveScopedRecencyAnchor() : 0;
		List<QueryDocument> sliceDocs = new ArrayList<QueryDocument>();
		Set<String> sliceUuids = new HashSet<String>();
		for (int i = 0; i < chartDocs.size(); i++) {
			QueryDocument doc = chartDocs.get(i);
			if (doc == null) {
				continue;
			}
			boolean isAnchor = i < recencyAnchor;
			boolean isPatientRecord = PATIENT_RESOURCE_TYPE.equals(doc.getResourceType());
			boolean inTypedScope = doc.getResourceType() != null && typedScope.contains(doc.getResourceType());
			boolean isSimilarityHit = doc.getResourceUuid() != null && similarityUuids.contains(doc.getResourceUuid());
			if (isAnchor || isPatientRecord || inTypedScope || isSimilarityHit) {
				sliceDocs.add(doc);
				if (doc.getResourceUuid() != null) {
					sliceUuids.add(doc.getResourceUuid());
				}
			}
		}
		sliceDocs = completeObsGroupFamilies(chartDocs, sliceDocs, sliceUuids);

		List<SerializedRecord> records = toSerializedRecords(sliceDocs);
		long serializeStart = System.currentTimeMillis();
		// compressDateRuns=false: the slice is small enough to date every record, and temporal
		// questions need the date on the record itself (see the serializer overload's javadoc).
		PatientChart chart = chartSerializer.serialize(patient, records,
				Collections.<String>emptySet(), false, false);
		long serializeMs = System.currentTimeMillis() - serializeStart;
		log.info("[timing] querystoreScopedBuild patient={} intent={} chartDocs={} simHits={} slice={} rpcMs={} serializeMs={} totalMs={} outcome=ok",
				patient.getPatientId(), intent, chartDocs.size(), similarityUuids.size(), records.size(),
				rpcMs, serializeMs, System.currentTimeMillis() - buildStart);
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
	 * Obs-group family completion for the scoped slice: if a panel PARENT (e.g. "Basic metabolic
	 * panel") or any MEMBER made the slice, the whole family joins it. The panel's VALUES live in
	 * member obs whose indexed text carries no panel name ("Serum sodium: 146"), so similarity can
	 * match the parent and miss every value — measured: "results of the last BMP" answered "no
	 * records" while the panel existed. One level only (obs groups don't nest in this chart).
	 *
	 * <p>Returns a REBUILT list scanned from {@code chartDocs} rather than appending the missing
	 * family members to {@code sliceDocs}: appending would place them after unrelated newer
	 * records and violate the most-recent-first chart order {@link #buildScoped}'s contract (and
	 * the system prompt) asserts. Returns {@code sliceDocs} unchanged when no family is touched.
	 */
	private List<QueryDocument> completeObsGroupFamilies(List<QueryDocument> chartDocs,
			List<QueryDocument> sliceDocs, Set<String> sliceUuids) {
		Set<String> familyGroups = new HashSet<String>();
		for (QueryDocument doc : chartDocs) {
			if (doc == null || doc.getResourceUuid() == null) {
				continue;
			}
			String groupUuid = metadataString(doc, QueryStoreConstants.FIELD_OBS_GROUP_UUID);
			if (groupUuid == null) {
				continue;
			}
			// doc is a MEMBER of groupUuid: the family joins the slice when the member itself is
			// in it, or when its parent (the group record) is.
			if (sliceUuids.contains(doc.getResourceUuid()) || sliceUuids.contains(groupUuid)) {
				familyGroups.add(groupUuid);
			}
		}
		if (familyGroups.isEmpty()) {
			return sliceDocs;
		}
		List<QueryDocument> completed = new ArrayList<QueryDocument>();
		for (QueryDocument doc : chartDocs) {
			if (doc == null) {
				continue;
			}
			String uuid = doc.getResourceUuid();
			String groupUuid = metadataString(doc, QueryStoreConstants.FIELD_OBS_GROUP_UUID);
			boolean inFamily = (groupUuid != null && familyGroups.contains(groupUuid))
					|| (uuid != null && familyGroups.contains(uuid));
			if ((uuid != null && sliceUuids.contains(uuid)) || inFamily) {
				completed.add(doc);
			}
		}
		return completed;
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

		List<SerializedRecord> records = toSerializedRecords(hits);
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

	/** The {@code log.error} format both modes emit when {@code getPatientChart} fails — a shared
	 *  constant (the try/catch stays inline per mode so each [timing] line keeps the REAL
	 *  {@code errorClass=}, which a shared catch-and-null helper would erase). */
	private static final String GET_PATIENT_CHART_FAILED_MSG =
			"QueryStore.getPatientChart failed for patient [uuid={}]";

	/** querystore's Elasticsearch tier caps {@code getPatientChart} at its most-recent N documents
	 *  (older tail silently dropped) — mirrors {@code ElasticsearchBackendStore.FULL_CHART_MAX_HITS}
	 *  in the querystore module. Kept in sync manually: querystore-api exposes no constant for it.
	 *  A returned size at this value means a scoped typed slice may be missing pre-cutoff records
	 *  (see {@link #buildScoped}). */
	private static final int QUERYSTORE_ES_CHART_CAP = 10_000;

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
	 * Record types whose querystore {@code record_date} is ADMINISTRATIVE ({@code dateCreated} —
	 * see querystore's {@code PatientRecordSerializer}/{@code AllergyRecordSerializer}), not a
	 * clinical event date. Rendering it as a "(date)" label misleads temporal reasoning: measured
	 * on the rc.2 standalone, "when was the last visit?" was answered from the allergy record's
	 * creation date in one mode and the patient record's in the other. These types render undated;
	 * the date still drives querystore's chart ordering.
	 */
	private static final Set<String> ADMIN_DATED_TYPES = Collections.unmodifiableSet(
			new HashSet<String>(Arrays.asList(PATIENT_RESOURCE_TYPE, "allergy")));

	/** Converts a querystore hit list into the chartsearchai serializer's input shape,
	 *  dropping null and malformed docs with a WARN so operators can spot upstream
	 *  serialization regressions without losing the rest of the chart. */
	private List<SerializedRecord> toSerializedRecords(List<QueryDocument> docs) {
		if (docs == null || docs.isEmpty()) {
			return Collections.<SerializedRecord>emptyList();
		}
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
					metadataString(doc, QueryStoreConstants.FIELD_OBS_GROUP_CONCEPT_NAME)));
		}
		return out;
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

	/** Seam for tests: production resolves via the OpenMRS context. */
	protected QueryStoreService resolveQueryStoreService() {
		return Context.getService(QueryStoreService.class);
	}

	/** Seam for tests: production reads the global property. */
	protected int resolveQueryStoreTopK() {
		return PipelineSettings.getQueryStoreTopK();
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
