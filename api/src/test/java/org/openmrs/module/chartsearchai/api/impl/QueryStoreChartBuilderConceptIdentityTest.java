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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.api.scope.QueryScopeContributor;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.querystore.QueryStoreConstants;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Concept identity, end to end: querystore document metadata &rarr; {@code SerializedRecord}
 * &rarr; {@code RecordMapping} &rarr; twin co-citation.
 *
 * <p>{@code LlmInferenceServiceTwinCitationTest} pins the pairing rules, but it constructs
 * {@link RecordMapping}s by hand — so on its own it proves the rule and not the plumbing that
 * feeds it. Nothing would have failed if {@code toSerializedRecords} had read the wrong metadata
 * key, or the serializer had dropped the value on its way into the mapping: the unit test would
 * still pass and every citation would silently lose its twin. This drives the real build path
 * with a real {@link QueryDocument} instead.
 */
public class QueryStoreChartBuilderConceptIdentityTest {

	private static final String HORDEOLUM = "concept-hordeolum-uuid";

	private CountingQueryStoreStub queryStore;

	private TestableBuilder builder;

	@BeforeEach
	public void setUp() {
		queryStore = new CountingQueryStoreStub();
		builder = new TestableBuilder(queryStore);
		builder.setChartSerializer(new PatientChartSerializer());
		// The shape querystore really produces: one problem in two tables, both carrying the same
		// concept_uuid in metadata and NEVER in the stored text (ADR Decision 6).
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(
				coded("condition", "c-1", "Condition: Hordeolum. Status: ACTIVE",
						LocalDate.of(2026, 3, 21), HORDEOLUM),
				coded("diagnosis", "dx-1", "Diagnosis: Hordeolum. Certainty: PROVISIONAL",
						LocalDate.of(2026, 3, 21), HORDEOLUM),
				// A non-coded record: querystore attaches no concept_uuid at all.
				coded("obs", "o-1", "Text of encounter note: seen in clinic",
						LocalDate.of(2026, 3, 20), null)));
		queryStore.stubHits = new ArrayList<QueryDocument>(queryStore.stubChart);
	}

	private static QueryDocument coded(String type, String uuid, String text, LocalDate date,
			String conceptUuid) {
		QueryDocument d = new QueryDocument();
		d.setResourceType(type);
		d.setResourceUuid(uuid);
		d.setText(text);
		d.setDate(date);
		if (conceptUuid != null) {
			d.putMetadata(QueryStoreConstants.FIELD_CONCEPT_UUID, conceptUuid);
		}
		return d;
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	private static RecordMapping byUuid(PatientChart chart, String resourceUuid) {
		for (RecordMapping m : chart.getMappings()) {
			if (resourceUuid.equals(m.getResourceUuid())) {
				return m;
			}
		}
		throw new AssertionError("no mapping for " + resourceUuid);
	}

	@Test
	public void theBuildPathCarriesConceptUuidFromQuerystoreMetadataIntoTheMapping() {
		PatientChart chart = builder.buildScoped(patient(), "Does the patient have any eye problems?");
		assertEquals(HORDEOLUM, byUuid(chart, "c-1").getConceptUuid(),
				"the condition's concept_uuid must reach the citation layer");
		assertEquals(HORDEOLUM, byUuid(chart, "dx-1").getConceptUuid(),
				"and so must its diagnosis twin's — that shared value IS the pairing key");
		assertNull(byUuid(chart, "o-1").getConceptUuid(),
				"a record querystore attached no concept to must stay null, not empty-string");
	}

	@Test
	public void conceptUuidIsNotLeakedIntoThePromptText() {
		// It is an identity for the citation layer, not content for the model. Leaking a raw uuid
		// into the chart line would spend tokens and invite the model to cite it as a value.
		PatientChart chart = builder.buildScoped(patient(), "Does the patient have any eye problems?");
		assertTrue(!chart.getText().contains(HORDEOLUM),
				"concept uuids must not appear in the prompt: " + chart.getText());
	}

	@Test
	public void aCitedProblemBuiltByTheRealPathPairsWithItsTwin() {
		// The end-to-end effect: build a real chart, cite ONE row the way the model does, and the
		// other row must come back as a reference without anything hand-assembling the mapping.
		PatientChart chart = builder.buildScoped(patient(), "Does the patient have any eye problems?");
		int conditionIndex = byUuid(chart, "c-1").getIndex();
		int diagnosisIndex = byUuid(chart, "dx-1").getIndex();

		List<RecordReference> refs = LlmInferenceService.extractCitedReferences(
				"Yes — Hordeolum is recorded [" + conditionIndex + "].",
				Arrays.asList(conditionIndex), chart.getMappings());

		List<Integer> cited = new ArrayList<Integer>();
		for (RecordReference r : refs) {
			cited.add(r.getIndex());
		}
		assertTrue(cited.contains(conditionIndex) && cited.contains(diagnosisIndex),
				"citing the condition must surface the identical diagnosis row; got " + cited);
		assertEquals(2, refs.size(), "and nothing else — the uncoded obs must not be dragged in");
	}

	private static final class TestableBuilder extends QueryStoreChartBuilder {

		private final QueryStoreService stub;

		TestableBuilder(QueryStoreService stub) {
			this.stub = stub;
		}

		@Override
		protected QueryStoreService resolveQueryStoreService() {
			return stub;
		}

		@Override
		protected int resolveQueryStoreTopK() {
			return 10;
		}

		@Override
		protected int resolveScopedRecencyAnchor() {
			return 0;
		}

		@Override
		protected boolean resolveUsePreFilter() {
			return false;
		}

		@Override
		protected boolean resolveDedupGroupLabels() {
			return false;
		}

		@Override
		protected List<QueryScopeContributor> resolveScopeContributors() {
			return new ArrayList<QueryScopeContributor>();
		}
	}
}
