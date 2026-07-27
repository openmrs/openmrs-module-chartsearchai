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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.api.scope.QueryScopeContributor;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Unit tests for {@link QueryStoreChartBuilder#buildScoped} — the query-scoped slice chart
 * that replaces the full-chart prompt when {@code chartsearchai.chartMode=queryScoped}.
 *
 * <p>Contract under test:
 * <ul>
 *   <li>The slice = ALL records of the intent's typed scope (complete by construction)
 *       ∪ the similarity top-K ∪ the patient demographics record.</li>
 *   <li>Slice records keep the CHART's date-desc order (the "most recent first" contract the
 *       system prompt asserts), never the similarity ranking's order.</li>
 *   <li>A similarity failure degrades to the typed slice alone (never blocks the answer).</li>
 *   <li>The slice never renders a focus hint (no focus indices) — the slice IS the scope.</li>
 * </ul>
 */
public class QueryStoreChartBuilderScopedTest {

	private static Patient patient(int id) {
		Patient p = new Patient();
		p.setPatientId(id);
		p.setUuid("uuid-" + id);
		return p;
	}

	private static QueryDocument doc(String type, String uuid, String text, LocalDate date) {
		QueryDocument d = new QueryDocument();
		d.setResourceType(type);
		d.setResourceUuid(uuid);
		d.setText(text);
		d.setDate(date);
		return d;
	}

	private CountingQueryStoreStub queryStore;
	private TestableScopedBuilder builder;

	@BeforeEach
	public void setUp() {
		queryStore = new CountingQueryStoreStub();
		builder = new TestableScopedBuilder(queryStore);
		builder.setChartSerializer(new PatientChartSerializer());
		// A chart in date-desc order: patient record, then interleaved types.
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(
				doc("patient", "p-1", "Patient: Jane Doe, Female, born 1980-02-02", LocalDate.of(2026, 7, 1)),
				doc("obs", "o-1", "Systolic blood pressure: 142 mmHg", LocalDate.of(2026, 6, 30)),
				doc("drug_order", "d-1", "Drug order: Lisinopril 10 mg daily", LocalDate.of(2026, 6, 29)),
				doc("obs", "o-2", "Serum creatinine: 90 umol/L", LocalDate.of(2026, 6, 28)),
				doc("condition", "c-1", "Condition: Essential hypertension. Status: ACTIVE", LocalDate.of(2026, 6, 27)),
				doc("drug_order", "d-2", "Drug order: Amoxicillin 500 mg twice daily", LocalDate.of(2026, 5, 1)),
				doc("allergy", "a-1", "Allergy: Penicillin. Reaction: rash", LocalDate.of(2026, 4, 2)),
				doc("obs", "o-3", "Weight: 70 kg", LocalDate.of(2026, 3, 3))));
	}

	private static List<String> mappedUuids(PatientChart chart) {
		List<String> uuids = new ArrayList<String>();
		for (RecordMapping m : chart.getMappings()) {
			uuids.add(m.getResourceUuid());
		}
		return uuids;
	}

	@Test
	public void buildScoped_shouldIncludeAllTypedRecordsPlusSimilarityHitsPlusPatient() {
		// Similarity returns one obs (o-1); the medications intent's typed scope is drug_order —
		// BOTH drug orders must be present (complete), plus the sim hit, plus demographics.
		queryStore.stubHits = new ArrayList<QueryDocument>(Arrays.asList(
				doc("obs", "o-1", "Systolic blood pressure: 142 mmHg", LocalDate.of(2026, 6, 30))));

		PatientChart chart = builder.buildScoped(patient(1), "What medications is the patient taking?");

		List<String> uuids = mappedUuids(chart);
		assertTrue(uuids.containsAll(Arrays.asList("p-1", "o-1", "d-1", "d-2")),
				"slice must carry the patient record, the similarity hit, and EVERY drug_order; got " + uuids);
		assertFalse(uuids.contains("c-1"), "conditions are outside the medications scope");
		assertFalse(uuids.contains("o-3"), "records outside typed scope and similarity hits are excluded");
		assertEquals(1, queryStore.getPatientChartCalls, "slice is filtered from the one chart fetch");
	}

	@Test
	public void buildScoped_shouldKeepAllergiesTypedComplete_whenMedicationCuesAlsoMatch() {
		// "any drug allergies?" carries BOTH a medications cue ("drug") and an allergies cue.
		// First-match routing sent it to MEDICATIONS alone, so the allergy list's completeness
		// hung on the similarity top-K — an allergy the embedding missed was silently absent
		// from an allergy enumeration (the highest-stakes omission this mode can make). The
		// union contract: every matched intent's types are complete. Similarity returns NOTHING
		// here, so the allergy record can only arrive via its typed scope.
		queryStore.stubHits = new ArrayList<QueryDocument>();

		PatientChart chart = builder.buildScoped(patient(1), "any drug allergies?");

		List<String> uuids = mappedUuids(chart);
		assertTrue(uuids.contains("a-1"),
				"the allergy list must be typed-complete when an allergy cue matched, even though "
						+ "a medications cue matched too; got " + uuids);
		assertTrue(uuids.containsAll(Arrays.asList("d-1", "d-2")),
				"the medications side of the union stays complete as well; got " + uuids);
		assertFalse(uuids.contains("c-1"), "unmatched types stay outside the union; got " + uuids);
	}

	@Test
	public void buildScoped_shouldKeepAllergiesTypedComplete_forAdverseReactionPhrasings() {
		// "any adverse drug reactions?" has no literal allergy-word, but "adverse"/"reactions" are
		// allergy-table vocabulary (the allergy records themselves read "Reaction: ..."). Without
		// an allergies cue the question rode similarity alone for exactly the records it asks to
		// enumerate.
		queryStore.stubHits = new ArrayList<QueryDocument>();

		PatientChart chart = builder.buildScoped(patient(1), "any adverse drug reactions?");

		assertTrue(mappedUuids(chart).contains("a-1"),
				"adverse-reaction phrasings must keep the allergy table typed-complete; got "
						+ mappedUuids(chart));
	}

	@Test
	public void buildScoped_shouldPreserveChartDateOrder_notSimilarityOrder() {
		// Similarity ranks the OLDEST record first; the slice must still render date-desc
		// (chart order), because the system prompt asserts "sorted most recent first".
		queryStore.stubHits = new ArrayList<QueryDocument>(Arrays.asList(
				doc("obs", "o-3", "Weight: 70 kg", LocalDate.of(2026, 3, 3)),
				doc("obs", "o-1", "Systolic blood pressure: 142 mmHg", LocalDate.of(2026, 6, 30))));

		PatientChart chart = builder.buildScoped(patient(1), "any weight or blood pressure trends?");

		List<String> uuids = mappedUuids(chart);
		assertTrue(uuids.indexOf("o-1") < uuids.indexOf("o-3"),
				"slice must keep chart (date-desc) order regardless of similarity rank; got " + uuids);
	}

	@Test
	public void buildScoped_shouldDegradeToTypedSlice_whenSimilaritySearchFails() {
		queryStore.throwOnSearch = true;

		PatientChart chart = builder.buildScoped(patient(1), "What medications is the patient taking?");

		List<String> uuids = mappedUuids(chart);
		assertTrue(uuids.containsAll(Arrays.asList("p-1", "d-1", "d-2")),
				"similarity failure must not block the typed slice; got " + uuids);
	}

	@Test
	public void buildScoped_shouldIncludeTheRecencyAnchor_forTemporalQuestions() {
		// TEMPORAL questions ("most recent X") always carry the chart's newest records (the
		// recency anchor), so the latest reading cannot lose the slice to similarity ranking —
		// measured failure: the newest systolic obs ranked below 30 older BP records and the
		// answer quoted a stale value. The anchor is cut from the front of the date-desc chart.
		queryStore.stubHits = new ArrayList<QueryDocument>();
		builder.recencyAnchor = 3;

		PatientChart chart = builder.buildScoped(patient(1), "What is the patient's most recent weight?");

		assertEquals(Arrays.asList("p-1", "o-1", "d-1"), mappedUuids(chart),
				"temporal slice must carry the 3 most recent chart records (the demographics "
						+ "record is itself the newest, so it sits inside the anchor)");
	}

	@Test
	public void buildScoped_shouldNotIncludeTheAnchor_forScopeQuestions() {
		// Scope questions must NOT get the anchor: anchored recent vitals in a small slice bait
		// the model into enumerating them as findings on absent-topic questions (measured: an
		// absent "heart" cell drifted to 39 vitals citations with an unconditional anchor).
		queryStore.stubHits = new ArrayList<QueryDocument>();
		builder.recencyAnchor = 3;

		PatientChart chart = builder.buildScoped(patient(1), "Does the patient have any eye problems?");

		assertEquals(Arrays.asList("p-1"), mappedUuids(chart),
				"non-temporal topical question with no similarity hits reduces to demographics");
	}

	@Test
	public void buildScoped_shouldRenderEveryRecordDate_withoutRunCompression() {
		// Date-run compression saves tokens on 400-record charts; on a small slice it HIDES the
		// date of the very records temporal questions need (measured: the anchored latest weight
		// lost its date to the run above it and the model quoted an older, explicitly-dated one).
		// Scoped slices are small enough to afford a date on every dated record.
		builder.recencyAnchor = 4;
		// A same-date run: with run compression, w-2 and w-3 would lose their "(date)" to w-1.
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(
				doc("patient", "p-1", "Patient: Jane Doe", LocalDate.of(2026, 7, 1)),
				doc("obs", "w-1", "Pulse: 80 bpm", LocalDate.of(2026, 6, 30)),
				doc("obs", "w-2", "Weight: 55 kg", LocalDate.of(2026, 6, 30)),
				doc("obs", "w-3", "Systolic blood pressure: 126 mmHg", LocalDate.of(2026, 6, 30))));
		queryStore.stubHits = new ArrayList<QueryDocument>();

		PatientChart chart = builder.buildScoped(patient(1), "most recent weight?");

		String text = chart.getText();
		int dated = 0;
		for (String line : text.split("\n")) {
			if (line.matches("\\[\\d+\\] \\(\\d{4}-\\d{2}-\\d{2}\\) .*")) {
				dated++;
			}
		}
		// 3 same-date obs — all dated (no run compression). The patient record renders undated
		// by design (its querystore date is administrative dateCreated, not a clinical date).
		assertEquals(3, dated,
				"every clinically-dated slice record must render its own date (no run compression):\n" + text);
		assertTrue(text.contains("[1] Patient: Jane Doe"),
				"the patient record renders without an administrative date label:\n" + text);
	}

	@Test
	public void buildScoped_shouldStampEveryReturn_asQueryScoped() {
		// The chart carries its own scoped-ness so downstream KV decisions never depend on a
		// GP re-read that can disagree with the build. Degraded empties must be stamped too —
		// they flow through the same KV decision points.
		assertTrue(builder.buildScoped(patient(1), "any allergies?").isQueryScoped(),
				"a normal scoped slice must be stamped");
		assertTrue(builder.buildScoped(null, "any allergies?").isQueryScoped(),
				"the null-patient degraded empty must be stamped");
	}

	@Test
	public void buildScoped_shouldNeverRenderFocusIndices() {
		queryStore.stubHits = new ArrayList<QueryDocument>(Arrays.asList(
				doc("obs", "o-1", "Systolic blood pressure: 142 mmHg", LocalDate.of(2026, 6, 30))));

		PatientChart chart = builder.buildScoped(patient(1), "What medications is the patient taking?");

		assertEquals(0, chart.getFocusIndices().size(),
				"the slice IS the scope — a focus hint would be redundant and change prompt shape");
	}

	@Test
	public void buildScoped_shouldPullWholeObsGroupFamily_whenAnyMemberOrParentIsInSlice() {
		// "Results of the last BMP" matches the PANEL PARENT record by name, but the VALUES live
		// in the member obs — which similarity may miss entirely (querystore indexes member text
		// like "Serum sodium: 146" with no panel name). Group completion: if a parent or any
		// member makes the slice, the whole family comes along, so panel questions see the values.
		QueryDocument parent = doc("obs", "g-1", "Basic metabolic panel", LocalDate.of(2023, 8, 23));
		QueryDocument m1 = doc("obs", "m-1", "Serum sodium: 146", LocalDate.of(2023, 8, 23));
		m1.putMetadata("obs_group_uuid", "g-1");
		QueryDocument m2 = doc("obs", "m-2", "Serum potassium: 3.9", LocalDate.of(2023, 8, 23));
		m2.putMetadata("obs_group_uuid", "g-1");
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(
				doc("patient", "p-1", "Patient: Jane Doe", LocalDate.of(2026, 7, 1)),
				parent, m1, m2,
				doc("obs", "o-x", "Weight: 70 kg", LocalDate.of(2023, 8, 22))));
		queryStore.stubHits = new ArrayList<QueryDocument>(Arrays.asList(parent));

		PatientChart chart = builder.buildScoped(patient(1), "Give me the results of the BMP panel");

		List<String> uuids = mappedUuids(chart);
		assertTrue(uuids.containsAll(Arrays.asList("g-1", "m-1", "m-2")),
				"a parent hit must pull every member so the panel VALUES are in context; got " + uuids);
		assertFalse(uuids.contains("o-x"), "non-family records are not pulled in");
	}

	@Test
	public void buildScoped_shouldPullParentAndSiblings_whenOnlyAMemberIsInSlice() {
		QueryDocument parent = doc("obs", "g-1", "Basic metabolic panel", LocalDate.of(2023, 8, 23));
		QueryDocument m1 = doc("obs", "m-1", "Serum sodium: 146", LocalDate.of(2023, 8, 23));
		m1.putMetadata("obs_group_uuid", "g-1");
		QueryDocument m2 = doc("obs", "m-2", "Serum potassium: 3.9", LocalDate.of(2023, 8, 23));
		m2.putMetadata("obs_group_uuid", "g-1");
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(
				doc("patient", "p-1", "Patient: Jane Doe", LocalDate.of(2026, 7, 1)),
				parent, m1, m2));
		queryStore.stubHits = new ArrayList<QueryDocument>(Arrays.asList(m1));

		PatientChart chart = builder.buildScoped(patient(1), "what was the serum sodium?");

		List<String> uuids = mappedUuids(chart);
		assertTrue(uuids.containsAll(Arrays.asList("g-1", "m-1", "m-2")),
				"a member hit must pull the parent (panel name) and siblings; got " + uuids);
	}

	@Test
	public void buildScoped_shouldNotRenderAdministrativeDates_onPatientAndAllergyRecords() {
		// querystore stamps dateCreated on patient and allergy documents (they have no clinical
		// event date — see its Patient/AllergyRecordSerializer). Rendering that as a "(date)"
		// label misleads temporal reasoning: measured, "when was the last visit?" was answered
		// from the allergy record's creation date in one mode and the patient record's in the
		// other. Chart assembly drops the date label for these two types ONLY. The condition
		// record's date below is ALSO dateCreated upstream, but it deliberately still renders —
		// undating an unmeasured type is a slice-byte change reserved for the gates (see the
		// ADMIN_DATED_TYPES javadoc); this test pins that deliberate remainder too.
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(
				doc("allergy", "a-9", "Allergy: Bee venom. Severity: Severe", LocalDate.of(2026, 6, 30)),
				doc("patient", "p-1", "Patient: Jane Doe", LocalDate.of(2026, 7, 4)),
				doc("condition", "c-1", "Condition: Malaria. Status: ACTIVE", LocalDate.of(2026, 6, 27))));
		queryStore.stubHits = new ArrayList<QueryDocument>(Arrays.asList(
				queryStore.stubChart.get(0), queryStore.stubChart.get(2)));

		PatientChart chart = builder.buildScoped(patient(1), "any allergies?");

		String text = chart.getText();
		assertFalse(text.contains("(2026-06-30)"),
				"the allergy record's creation date must not render as a clinical date:\n" + text);
		assertFalse(text.contains("(2026-07-04)"),
				"the patient record's creation date must not render as a clinical date:\n" + text);
		assertTrue(text.contains("(2026-06-27) Condition: Malaria"),
				"types outside ADMIN_DATED_TYPES keep their dates (condition's is also dateCreated "
						+ "upstream — kept pending the gated follow-up):\n" + text);
	}

	@Test
	public void buildScoped_shouldSendTheExpandedQueryToSimilaritySearch() {
		// Wiring lock: the lab-abbreviation expansion must actually reach searchByPatient — a pure
		// expandLabPanelAbbreviations unit test cannot catch a builder that forgets to call it.
		builder.buildScoped(patient(1), "Give me the results of the last BMP.");

		assertTrue(queryStore.lastSearchQuery != null
						&& queryStore.lastSearchQuery.toLowerCase().contains("basic metabolic panel"),
				"searchByPatient must receive the abbreviation-expanded question; got: "
						+ queryStore.lastSearchQuery);
	}

	@Test
	public void buildScoped_shouldReturnEmptyChart_whenPatientIsNull() {
		PatientChart chart = builder.buildScoped(null, "any allergies?");

		assertEquals(0, chart.getMappings().size());
		assertEquals(0, queryStore.getPatientChartCalls + queryStore.searchByPatientCalls);
	}

	@Test
	public void buildScoped_shouldSkipSimilarity_whenQuestionIsBlank() {
		builder.recencyAnchor = 0;

		PatientChart chart = builder.buildScoped(patient(1), "   ");

		assertEquals(0, queryStore.searchByPatientCalls,
				"blank question has no ranking signal — no similarity RPC");
		assertEquals(Arrays.asList("p-1"), mappedUuids(chart),
				"blank topical question reduces to the demographics record");
	}

	/** A test contributor claiming a fixed type set when the question contains a cue word. */
	private static QueryScopeContributor contributor(
			final String cue, final String... types) {
		return new QueryScopeContributor() {

			@Override
			public Set<String> scopedResourceTypes(String question) {
				if (question != null && question.toLowerCase().contains(cue)) {
					return new HashSet<String>(Arrays.asList(types));
				}
				return Collections.<String> emptySet();
			}
		};
	}

	@Test
	public void scopeLabel_shouldNameTheContributedTypesThatShapedTheSlice() {
		// The union runs AFTER routing, so a contributor claiming condition/diagnosis overrides the
		// router's decision to withhold the complete problem list from a domain-qualified conditions
		// question — the psychiatric-enumeration failure, back on. An operator triaging that answer
		// must not read the label that says the suppression applied.
		Set<String> none = Collections.<String> emptySet();
		assertEquals("TOPICAL", QueryStoreChartBuilder.scopeLabel(
				QueryScopeRouter.matchedIntents("Does the patient have any psychiatric conditions?"),
				none),
				"a domain-qualified conditions question is TOPICAL with no contributors");
		assertEquals("TOPICAL+contrib[condition, diagnosis]", QueryStoreChartBuilder.scopeLabel(
				QueryScopeRouter.matchedIntents("Does the patient have any psychiatric conditions?"),
				new HashSet<String>(Arrays.asList("diagnosis", "condition"))),
				"…and must say so when a contributor put the problem list back, sorted so the "
						+ "token is stable and greppable");
		assertEquals("MEDICATIONS+contrib[billing]", QueryStoreChartBuilder.scopeLabel(
				QueryScopeRouter.matchedIntents("What medications is the patient taking?"),
				new HashSet<String>(Arrays.asList("billing"))),
				"an ordinary contributor claim is labelled the same way");
		assertEquals("MEDICATIONS", QueryStoreChartBuilder.scopeLabel(
				QueryScopeRouter.matchedIntents("What medications is the patient taking?"), null),
				"a null claim set must not append an empty suffix");
	}

	@Test
	public void buildScoped_shouldIncludeContributorClaimedTypes_complete() {
		// A module (e.g. billing) registers a contributor claiming its resourceType for questions it
		// recognizes. Those records must join the slice COMPLETE, exactly like a built-in typed scope.
		builder.contributors.add(contributor("bill", "billing"));
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(
				doc("patient", "p-1", "Patient: Jane Doe", LocalDate.of(2026, 7, 1)),
				doc("billing", "b-1", "Bill: consultation, 20 USD, UNPAID", LocalDate.of(2026, 6, 30)),
				doc("billing", "b-2", "Bill: lab, 5 USD, PAID", LocalDate.of(2026, 5, 1)),
				doc("obs", "o-1", "Weight: 70 kg", LocalDate.of(2026, 4, 2))));
		queryStore.stubHits = new ArrayList<QueryDocument>();

		PatientChart chart = builder.buildScoped(patient(1), "does the patient have any outstanding bills?");

		List<String> uuids = mappedUuids(chart);
		assertTrue(uuids.containsAll(Arrays.asList("p-1", "b-1", "b-2")),
				"contributor-claimed billing records must be in the slice, complete; got " + uuids);
		assertFalse(uuids.contains("o-1"), "records outside the contributed scope and similarity are excluded");
	}

	@Test
	public void buildScoped_shouldUnionContributorScope_withBuiltInScope() {
		// A contributor's claim is ADDITIVE on top of the built-in typed scope, never a replacement:
		// a medications question still gets every drug_order AND the contributor's billing records.
		builder.contributors.add(contributor("bill", "billing"));
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(
				doc("patient", "p-1", "Patient: Jane Doe", LocalDate.of(2026, 7, 1)),
				doc("drug_order", "d-1", "Drug order: Lisinopril 10 mg", LocalDate.of(2026, 6, 30)),
				doc("billing", "b-1", "Bill: pharmacy, UNPAID", LocalDate.of(2026, 6, 29))));
		queryStore.stubHits = new ArrayList<QueryDocument>();

		PatientChart chart = builder.buildScoped(patient(1),
				"what medications and unpaid bills does the patient have?");

		assertTrue(mappedUuids(chart).containsAll(Arrays.asList("p-1", "d-1", "b-1")),
				"union must carry both the built-in drug_order scope and the contributed billing scope; got "
						+ mappedUuids(chart));
	}

	@Test
	public void buildScoped_shouldExcludeContributorScope_whenNoContributorRegistered() {
		// Negative control: with no contributor, billing records are unknown to routing and only reach
		// the slice via similarity — proving the contributor is what pulls them in above.
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(
				doc("patient", "p-1", "Patient: Jane Doe", LocalDate.of(2026, 7, 1)),
				doc("billing", "b-1", "Bill: consultation, UNPAID", LocalDate.of(2026, 6, 30))));
		queryStore.stubHits = new ArrayList<QueryDocument>();

		PatientChart chart = builder.buildScoped(patient(1), "does the patient have any outstanding bills?");

		assertFalse(mappedUuids(chart).contains("b-1"),
				"without a registered contributor, billing is not a routed scope; got " + mappedUuids(chart));
	}

	@Test
	public void buildScoped_shouldSurviveThrowingContributor() {
		// A misbehaving contributor must never break chart assembly — it forfeits its claim, the rest
		// of the slice (built-in scope + similarity + patient) is built normally.
		builder.contributors.add(new QueryScopeContributor() {

			@Override
			public Set<String> scopedResourceTypes(String question) {
				throw new RuntimeException("contributor boom");
			}
		});
		builder.contributors.add(contributor("bill", "billing"));
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(
				doc("patient", "p-1", "Patient: Jane Doe", LocalDate.of(2026, 7, 1)),
				doc("billing", "b-1", "Bill: consultation, UNPAID", LocalDate.of(2026, 6, 30))));
		queryStore.stubHits = new ArrayList<QueryDocument>();

		PatientChart chart = builder.buildScoped(patient(1), "any outstanding bills?");

		List<String> uuids = mappedUuids(chart);
		assertTrue(uuids.contains("p-1"), "a throwing contributor must not break the slice; got " + uuids);
		assertTrue(uuids.contains("b-1"),
				"the surviving contributor's claim must still apply after another throws; got " + uuids);
	}

	@Test
	public void buildScoped_shouldSurvive_whenContributorResolutionItselfFails() {
		// Beyond a single contributor throwing: resolving the contributor beans can itself fail
		// (e.g. no OpenMRS service context). buildScoped must degrade to the built-in scope, not break.
		builder.throwOnResolveContributors = true;
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(
				doc("patient", "p-1", "Patient: Jane Doe", LocalDate.of(2026, 7, 1)),
				doc("drug_order", "d-1", "Drug order: Lisinopril 10 mg", LocalDate.of(2026, 6, 30))));
		queryStore.stubHits = new ArrayList<QueryDocument>();

		PatientChart chart = builder.buildScoped(patient(1), "What medications is the patient taking?");

		assertTrue(mappedUuids(chart).containsAll(Arrays.asList("p-1", "d-1")),
				"contributor-resolution failure must still yield the built-in typed slice; got " + mappedUuids(chart));
	}

	private static final class TestableScopedBuilder extends QueryStoreChartBuilder {

		private final QueryStoreService stub;

		int recencyAnchor = 0;

		boolean throwOnResolveContributors = false;

		List<QueryScopeContributor> contributors =
				new ArrayList<QueryScopeContributor>();

		TestableScopedBuilder(QueryStoreService stub) {
			this.stub = stub;
		}

		@Override
		protected List<QueryScopeContributor> resolveScopeContributors() {
			if (throwOnResolveContributors) {
				throw new RuntimeException("simulated getRegisteredComponents failure");
			}
			return contributors;
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
			return recencyAnchor;
		}

		@Override
		protected boolean resolveUsePreFilter() {
			return false;
		}

		@Override
		protected boolean resolveDedupGroupLabels() {
			return false;
		}
	}

}
