/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * One injected {@code drug_reference} record per substance, not one per reference row (issue #163).
 *
 * <p><b>The defect.</b> {@code DrugReferenceInjector.matchingEntries} de-duplicated on
 * {@code ref.getId()}, and route variants of one substance deliberately carry DISTINCT ids — the
 * parser falls back to the DDInter id when the RxCUI is shared, precisely so citations stay
 * unambiguous. So a question about one drug injected one near-duplicate record per variant, each
 * rendering up to {@code MAX_INTERACTION_RENDER_CHARS} of interaction prose.
 *
 * <p><b>Why it matters, and why it is invisible from outside.</b> This is prompt budget: several
 * thousand characters of near-duplicate reference text crowd out chart records the answer needs
 * (issues #95, #99), and handing the model several differently-worded copies of one fact is the setup
 * for the miscopying #142 records. The REST response returns only CITED references, so the cost is
 * only visible in what gets injected — which is why these cases measure the injected slice itself
 * ({@code DrugReferenceTestSupport.injectedReferences}) rather than inferring it from chip counts.
 *
 * <p>Driven through the real {@link DrugReferenceInjector#injectRecords} over verbatim KB slices.
 */
public class ReferenceRecordSubstanceCollapseTest {

	/** The route-variant slice: four Dexamethasone rows, two Sirolimus rows, two Iron rows, and four
	 *  single-row drugs (Phenytoin, Voxelotor, Lapatinib, Dolutegravir) as controls. */
	private static final String FIXTURE = DrugReferenceTestSupport.DDI_ROUTE_VARIANTS;

	/** The subject-side slice issue #162's cases use — reused here for the two families this one has and
	 *  that one does not: two distinct substances under one {@code rxnorm_name}, and a family the KB
	 *  lists route-qualified row first. */
	private static final String INTERACTION_FIXTURE =
			"chartsearchai-test/ddi-interaction-route-variants.json";

	private static PatientChart inject(String question) throws IOException {
		return DrugReferenceTestSupport.injector(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null, null, null), question);
	}

	private static List<String> referenceTexts(PatientChart chart) {
		List<String> out = new ArrayList<String>();
		for (RecordMapping mapping : DrugReferenceTestSupport.injectedReferences(chart)) {
			out.add(mapping.getText());
		}
		return out;
	}

	private static int characters(List<String> texts) {
		int total = 0;
		for (String text : texts) {
			total += text.length();
		}
		return total;
	}

	@Test
	public void theFixtureReallyResolvesFourRowsForOneQuestionWord() throws IOException {
		// The premise, through the production resolver: without it a one-record assertion below could pass
		// because the question resolved one row all along.
		List<DrugReference> rows = DrugReferenceTestSupport.ddiFixtureService(FIXTURE)
				.findByQuery("Is it safe to give dexamethasone?");

		assertEquals(4, rows.size(), "one question word must resolve all four dexamethasone rows, was: "
				+ DrugReferenceTestSupport.names(rows));
		assertEquals(4, rows.stream().map(DrugReference::getId).distinct().count(),
				"and they must carry four distinct ids — which is exactly why de-duplicating on the id "
						+ "could not see them");
	}

	@Test
	public void aSubstanceFiledAsFourRowsInjectsOneRecord() throws IOException {
		List<String> texts = referenceTexts(inject("Is it safe to give dexamethasone?"));

		assertEquals(1, texts.size(),
				"four route variants of one substance are one reference record, was: " + texts);
		assertTrue(texts.get(0).startsWith("Drug reference — Dexamethasone (ATC"),
				"and it is the route-unspecified row — the systemic profile is what a question naming the "
						+ "bare substance asked about, and a variant would render as \"Dexamethasone "
						+ "(nasal) (ATC …\", was: " + texts.get(0));
	}

	@Test
	public void aSubstanceFiledAsFourRowsCostsNoMoreRecordsThanASingleRowDrug() throws IOException {
		// The budget statement, as a comparison rather than as a number, so it cannot rot: what the
		// collapse buys is that the number of rows a KB happens to file a substance under stops being
		// something a clinician's question pays for.
		List<String> variants = referenceTexts(inject("Is it safe to give dexamethasone?"));
		List<String> singleRow = referenceTexts(inject("Is it safe to give phenytoin?"));

		assertEquals(1, singleRow.size(), "the control drug is one row, was: " + singleRow);
		assertEquals(singleRow.size(), variants.size(),
				"a four-row substance must cost the same number of records as a one-row drug, was: "
						+ variants);
		assertTrue(characters(variants) < 2 * characters(singleRow),
				"and its characters must be of that order rather than a multiple of it — was "
						+ characters(variants) + " against " + characters(singleRow));
	}

	@Test
	public void theQuestionLegAndTheOrderLegShareOneRecordForOneSubstance() throws IOException {
		// The two legs of matchingEntries reach the same substance by different routes — the question's
		// words and the patient's own orders — and a collapse in one leg alone would let the other inject
		// the siblings. The order leg is relevance-scoped to a question drug sharing an ATC subgroup, so
		// this arrangement makes both legs resolve the dexamethasone family.
		PatientChart chart = DrugReferenceTestSupport
				.injector(DrugReferenceTestSupport.ddiFixtureService(FIXTURE))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null,
								DrugReferenceTestSupport.set("Dexamethasone 4mg"),
								DrugReferenceTestSupport.set("H02AB02"), null, null),
						"Is it safe to give dexamethasone?");

		assertEquals(1, referenceTexts(chart).size(),
				"one substance is one record however many legs reach it, was: " + referenceTexts(chart));
	}

	@Test
	public void twoDistinctSubstancesTheKbFilesUnderOneSubstanceNameKeepTheirOwnRecords()
			throws IOException {
		// The must-NOT-collapse case on this layer. Omeprazole and Esomeprazole share one rxnorm_name,
		// one RxCUI and one ATC code and are two substances (issue #121), so they are two records — a
		// reference record is the drug's own profile, and merging two drugs' profiles into one would hand
		// the model one entry's interactions under another entry's name.
		//
		// The question NAMES BOTH, since issue #209. It used to name only esomeprazole and rely on that one
		// word resolving both, which put a record for a substance nobody had named into the prompt — the
		// injector leg of that issue. The vehicle changed; the property did not, and this is still the case
		// that fails if `substanceKey` ever merges the two PPIs into one record.
		List<String> texts = referenceTexts(DrugReferenceTestSupport
				.injector(DrugReferenceTestSupport.ddiFixtureService(INTERACTION_FIXTURE))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null, null, null),
						"Is it safe to give omeprazole or esomeprazole?"));

		assertEquals(2, texts.size(), "two substances keep two records, was: " + texts);
		assertTrue(texts.get(0).startsWith("Drug reference — Omeprazole"), "was: " + texts.get(0));
		assertTrue(texts.get(1).startsWith("Drug reference — Esomeprazole"), "was: " + texts.get(1));
	}

	@Test
	public void theDebugLineReportsTheReferenceSliceCharacterTotalAndOnlyThat() throws IOException {
		// The instrument, tested rather than trusted. Issue #163's cost is INVISIBLE from the REST
		// response (only cited references come back), so this DEBUG line is the only place an operator or
		// a verification pass can read it — and this PR's own live evidence is quoted off it. A count
		// alone did not settle #163 either, since what crowds out chart records is characters. Asserted
		// on the real formatted line, not through a helper that recomputes the number: that would test
		// the helper, not the production formatting (the trap PairChipCapContextTest records). Through the
		// shared LogCapture, which needed a level parameter to see below INFO — this is the first output
		// in the module whose only surface is a DEBUG line.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(INTERACTION_FIXTURE);
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		PatientChart chart;
		List<String> logged;
		try (LogCapture capture = LogCapture.on(DrugReferenceTestSupport.REFERENCE_LOGGER, Level.DEBUG)) {
			chart = injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
					DrugReferenceTestSupport.ctx(60, null,
							DrugReferenceTestSupport.set("Diclofenac 50mg"), null, null, null),
					"Is it safe to give hydrocortisone?");
			logged = capture.messagesAt(Level.DEBUG);
		}

		// Preconditions: the chart must carry records of the OTHER two kinds with text of their own, or
		// the "only those" half of this assertion is vacuous — a total that counted everything would pass.
		int references = characters(referenceTexts(chart));
		assertFalse(DrugReferenceTestSupport.injectedFindings(chart).isEmpty(),
				"precondition: a safety-finding record must be injected beside the reference records");
		assertTrue(references > 0, "precondition: and reference records with text");
		int everything = 0;
		for (RecordMapping mapping : chart.getMappings()) {
			everything += mapping.getText() == null ? 0 : mapping.getText().length();
		}

		Matcher line = Pattern.compile("drug-reference \\((\\d+) chars\\)").matcher(String.join("\n", logged));
		assertTrue(line.find(), "the DEBUG line must report the reference slice's character total, was: "
				+ logged);
		int reported = Integer.parseInt(line.group(1));
		assertEquals(references, reported,
				"and it must be the injected drug_reference records' own characters");
		assertTrue(reported < everything,
				"counting ONLY those — a total that also counted the chart's obs record and the safety "
						+ "findings would say the reference slice costs more budget than it does, was "
						+ reported + " against " + everything + " for every record in the chart");
	}

	@Test
	public void theSurvivingRecordIsTheRouteUnspecifiedRowEvenWhenItIsNotTheFamilysFirst()
			throws IOException {
		// 7 of the shipped KB's 121 multi-row families list a qualified row BEFORE the unqualified one
		// (measured 2026-08-06) — Chloroprocaine is one of them, and the slice keeps the KB's order. A
		// first-wins collapse injects "Chloroprocaine (ophthalmic)" for a question about chloroprocaine,
		// i.e. an ophthalmic monograph as the profile of a drug asked about by its bare name.
		List<String> texts = referenceTexts(DrugReferenceTestSupport
				.injector(DrugReferenceTestSupport.ddiFixtureService(INTERACTION_FIXTURE))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null, null, null),
						"Is it safe to give chloroprocaine?"));

		assertEquals(1, texts.size(), "the two chloroprocaine rows are one record, was: " + texts);
		assertTrue(texts.get(0).startsWith("Drug reference — Chloroprocaine (ATC"),
				"and it is the unqualified row, not the family's first — the ophthalmic row renders as "
						+ "\"Chloroprocaine (ophthalmic) (ATC …\", was: " + texts.get(0));
	}
}
