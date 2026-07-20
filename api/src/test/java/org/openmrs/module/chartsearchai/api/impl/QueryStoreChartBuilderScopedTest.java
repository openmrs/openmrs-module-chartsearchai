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
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
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
	public void buildScoped_shouldSurfaceTruncatedRepeatedMeasure_viaConceptExpansion() {
		// A repeated-measure series (6 systolic BP) that a fixed top-K truncates: similarity surfaces
		// only 4 of them. Concept expansion must add the 2 the ranking dropped, so a trend/enumeration
		// answer sees the whole series. Exercised through the composed buildScoped path (not the
		// dominantConceptExpansion unit), per the "test the composed pipeline" rule.
		queryStore.stubChart = new ArrayList<QueryDocument>(Arrays.asList(
				doc("patient", "p-1", "Patient: Jane Doe", LocalDate.of(2026, 7, 1)),
				doc("obs", "bp-1", "Systolic blood pressure: 159 mmHg", LocalDate.of(2026, 6, 30)),
				doc("obs", "bp-2", "Systolic blood pressure: 145 mmHg", LocalDate.of(2026, 6, 20)),
				doc("obs", "bp-3", "Systolic blood pressure: 138 mmHg", LocalDate.of(2026, 6, 10)),
				doc("obs", "bp-4", "Systolic blood pressure: 152 mmHg", LocalDate.of(2026, 5, 30)),
				doc("obs", "bp-5", "Systolic blood pressure: 141 mmHg", LocalDate.of(2026, 5, 20)),
				doc("obs", "bp-6", "Systolic blood pressure: 156 mmHg", LocalDate.of(2026, 5, 10)),
				doc("obs", "w-1", "Weight: 70 kg", LocalDate.of(2026, 4, 1))));
		// top-K surfaced only 4 of the 6 (the truncation this feature fixes).
		queryStore.stubHits = new ArrayList<QueryDocument>(Arrays.asList(
				queryStore.stubChart.get(1), queryStore.stubChart.get(2),
				queryStore.stubChart.get(3), queryStore.stubChart.get(4)));

		List<String> withExpansion = mappedUuids(
				builder.buildScoped(patient(1), "Does the patient have high blood pressure?"));
		assertTrue(withExpansion.containsAll(Arrays.asList("bp-1", "bp-2", "bp-3", "bp-4", "bp-5", "bp-6")),
				"expansion must surface every systolic-BP record, including the 2 the top-K dropped; got "
						+ withExpansion);

		// Control: with expansion off, the two dropped readings stay out — proving expansion (not a
		// typed scope or the recency anchor) is what surfaced bp-5/bp-6 above.
		builder.conceptExpansion = false;
		List<String> withoutExpansion = mappedUuids(
				builder.buildScoped(patient(1), "Does the patient have high blood pressure?"));
		assertFalse(withoutExpansion.contains("bp-5") || withoutExpansion.contains("bp-6"),
				"without expansion the top-K-dropped readings must not appear; got " + withoutExpansion);
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

	private static final class TestableScopedBuilder extends QueryStoreChartBuilder {

		private final QueryStoreService stub;

		int recencyAnchor = 0;

		boolean conceptExpansion = true;

		TestableScopedBuilder(QueryStoreService stub) {
			this.stub = stub;
		}

		@Override
		protected boolean resolveConceptExpansionEnabled() {
			return conceptExpansion;
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
