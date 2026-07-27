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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.scope.QueryScopeContributor;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * What the three build paths actually SEND to {@code QueryStoreService.searchByPatient}.
 *
 * <p>{@code ArchitectureGuardTest.noHandChainedRetrievalPreprocessing} proves no call site chains
 * the preprocessing steps by hand, but it is a source scan — it cannot prove the composed
 * {@link QueryPreprocessor#forRetrieval} is wired into each path, only that nothing else is. That
 * distinction is exactly where the bug this fixture guards lived: the scoped builder expanded lab
 * abbreviations while the fullChart focus-hint and progressive-reasoning paths expanded nothing,
 * so the same question retrieved different records depending on the mode, and every existing
 * assertion still passed.
 *
 * <p>So these assert the observed runtime effect on each path, via the stub's record of the query
 * string it received.
 */
public class QueryStoreChartBuilderRetrievalTextTest {

	private CountingQueryStoreStub queryStore;

	private TestableBuilder builder;

	@BeforeEach
	public void setUp() {
		queryStore = new CountingQueryStoreStub();
		builder = new TestableBuilder(queryStore);
		builder.setChartSerializer(new PatientChartSerializer());
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(
				doc("patient", "p-1", "Patient: Jane Doe", LocalDate.of(2026, 7, 1)),
				doc("condition", "c-1", "Condition: Chronic kidney disease, stage IIIA (moderate). Status: ACTIVE",
						LocalDate.of(2026, 6, 27))));
		queryStore.stubHits = new ArrayList<QueryDocument>();
	}

	private static QueryDocument doc(String type, String uuid, String text, LocalDate date) {
		QueryDocument d = new QueryDocument();
		d.setResourceType(type);
		d.setResourceUuid(uuid);
		d.setText(text);
		d.setDate(date);
		return d;
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	private void assertExpanded(String path) {
		assertTrue(queryStore.lastSearchQuery != null
				&& queryStore.lastSearchQuery.toLowerCase().contains("chronic kidney disease"),
				path + " must send the EXPANDED initialism to querystore — the record reads "
						+ "\"Chronic kidney disease, stage IIIA (moderate)\" and a bare \"CKD\" embeds "
						+ "far from it. Sent: " + queryStore.lastSearchQuery);
		assertTrue(queryStore.lastSearchQuery.toLowerCase().contains("ckd"),
				path + " must keep the clinician's own token alongside the expansion: "
						+ queryStore.lastSearchQuery);
	}

	@Test
	public void scopedSliceSendsExpandedRetrievalText() {
		builder.buildScoped(patient(), "Does the patient have CKD?");
		assertExpanded("buildScoped");
	}

	@Test
	public void fullChartFocusHintSendsExpandedRetrievalText() {
		// The path that expanded NOTHING before: fullChart + preFilter=true asks querystore for a
		// similarity ranking to render the focus hint, and used to strip stopwords only.
		builder.preFilter = true;
		builder.build(patient(), "Does the patient have CKD?");
		assertExpanded("build (fullChart + preFilter)");
	}

	@Test
	public void progressiveReasoningPreviewSendsExpandedRetrievalText() {
		// Third path, same drift: the preview's focused chart is retrieved with its own call.
		builder.buildFocused(patient(), "Does the patient have CKD?");
		assertExpanded("buildFocused");
	}

	@Test
	public void whatReachesQuerystoreIsExactlyWhatTheComposedEntryPointProduces() {
		// Not just "contains the expansion" — byte-equal to forRetrieval's output, so a path that
		// applied only half the pipeline (the drift this fixture exists for) fails here even if the
		// half it applied happened to include the expansion.
		//
		// Deliberately compared against forRetrieval rather than against stripQueryStopwords: that
		// a cue-free question survives the composed pipeline unchanged is pinned in
		// QueryPreprocessorRetrievalTextTest, and reaching for the raw step here would both
		// duplicate it and trip ArchitectureGuardTest's no-hand-chaining rule.
		String question = "Does the patient have any kidney problems?";
		builder.buildScoped(patient(), question);
		assertEquals(QueryPreprocessor.forRetrieval(question), queryStore.lastSearchQuery,
				"the slice builder must send querystore exactly the composed retrieval text");
	}

	private static final class TestableBuilder extends QueryStoreChartBuilder {

		private final QueryStoreService stub;

		boolean preFilter = false;

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
		protected int resolveProgressiveReasoningTopK() {
			return 10;
		}

		@Override
		protected int resolveScopedRecencyAnchor() {
			return 0;
		}

		@Override
		protected boolean resolveUsePreFilter() {
			return preFilter;
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
