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
 * <p>Always fetches the full patient chart via
 * {@link QueryStoreService#getPatientChart(String)} so the chart bytes sent to
 * the LLM are a function of the patient only — that's the property
 * llama-server's KV-cache reuse needs in order to skip ~99% of the prefill on
 * subsequent queries for the same patient. When
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

		QueryStoreService queryStore;
		try {
			queryStore = resolveQueryStoreService();
		}
		catch (APIException | LinkageError e) {
			// LinkageError covers NoClassDefFoundError when the querystore-api jar
			// is absent at runtime — the QueryStoreService.class literal forces
			// JVM linkage, which APIException doesn't catch.
			// WARN (not INFO): default org.openmrs.* log level is WARN, and an unavailable
			// QueryStoreService silently produces empty-chart LLM responses if this fires.
			// Operators need this to surface, with an actionable next step.
			log.warn("QueryStoreService is unavailable — querystore is a required module, so this "
					+ "indicates a querystore startup failure; check the querystore module. "
					+ "Returning empty chart.");
			log.info("[timing] querystoreBuild patient={} mode={} hits=0 focusHits=0 rpcMs=0 serializeMs=0 totalMs={} outcome=unavailable",
					patient.getPatientId(), mode, System.currentTimeMillis() - buildStart);
			return chartSerializer.serialize(patient, Collections.<SerializedRecord>emptyList());
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
			log.error("QueryStore.getPatientChart failed for patient [uuid={}]", patient.getUuid(), e);
			long failMs = System.currentTimeMillis() - rpcStart;
			log.info("[timing] querystoreBuild patient={} mode={} hits=0 focusHits=0 rpcMs={} serializeMs=0 totalMs={} outcome=error errorClass={}",
					patient.getPatientId(), mode, failMs, System.currentTimeMillis() - buildStart,
					e.getClass().getSimpleName());
			return chartSerializer.serialize(patient, Collections.<SerializedRecord>emptyList());
		}

		// Focus hint: only in preFilter mode, only with a non-blank question (searchByPatient
		// with a blank query is spurious — no ranking signal). The hint is a tiny payload
		// (UUIDs collected here, rendered as 1-based indices in the chart serializer).
		Set<String> focusUuids = Collections.<String>emptySet();
		int focusHits = 0;
		if (usePreFilter && question != null && !question.trim().isEmpty()) {
			try {
				String preprocessedQuestion = QueryPreprocessor.stripQueryStopwords(question);
				int topK = resolveQueryStoreTopK();
				List<QueryDocument> hits = queryStore.searchByPatient(patient.getUuid(),
						preprocessedQuestion, topK);
				focusUuids = collectFocusUuids(hits);
				focusHits = focusUuids.size();
			}
			catch (RuntimeException e) {
				// Focus-hint failure must not block the LLM call — the full chart is already
				// fetched and is usable on its own (equivalent to fullChart mode). Log + fall
				// through with empty focus.
				log.warn("QueryStore.searchByPatient failed for patient [uuid={}] — proceeding without focus hint",
						patient.getUuid(), e);
			}
		}
		long rpcMs = System.currentTimeMillis() - rpcStart;

		List<SerializedRecord> records = toSerializedRecords(chartDocs);
		long serializeStart = System.currentTimeMillis();
		PatientChart chart = chartSerializer.serialize(patient, records, focusUuids);
		long serializeMs = System.currentTimeMillis() - serializeStart;
		long totalMs = System.currentTimeMillis() - buildStart;
		log.info("[timing] querystoreBuild patient={} mode={} hits={} focusHits={} rpcMs={} serializeMs={} totalMs={} outcome=ok",
				patient.getPatientId(), mode, records.size(), focusHits, rpcMs, serializeMs, totalMs);
		return chart;
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
			out.add(new SerializedRecord(doc.getResourceType(), doc.getResourceUuid(),
					text, DateFormatUtil.toLegacyDate(doc.getDate()), Collections.<String>emptyList(),
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

	/** Seam for tests: production reads the global property. */
	protected boolean resolveUsePreFilter() {
		return PipelineSettings.usePreFilter();
	}
}
