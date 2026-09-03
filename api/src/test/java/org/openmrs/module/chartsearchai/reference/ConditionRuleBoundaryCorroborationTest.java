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

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #309 — a CONDITION rule matched by bare containment, stated as this patient's chart's own
 * reading with nothing corroborating it.
 *
 * <p><b>The defect.</b> {@code DrugSafetyValidator.corroboratedByTheChart} answered {@code true}
 * unconditionally for every rule that is not a self-named ALLERGY rule, so a condition rule reached
 * {@code Recorded for this patient:} on the strength of {@code PatientClinicalContext.hasConditionToken}
 * alone — which is bare containment, deliberately, because a curated condition token is MEANT to match
 * a fragment ({@code peptic ulcer} inside "history of peptic ulcer disease"). That is issue #269's
 * defect one rule type along: for a patient whose recorded condition is {@code Status Post Cesarean
 * Delivery}, an entry ruling on the condition token {@code liver} read
 * <pre>
 * Recorded for this patient: acute hepatitis or liver failure
 * </pre>
 * and said it of a chart that records a caesarean delivery.
 *
 * <p><b>Why the fix is a boundary witness and not the allergy union.</b> The ticket says the
 * corroboration test has no condition analogue, because both of its legs resolve a drug NAME and no arm
 * resolves a recorded condition to a reference substance. That is right about the legs and wrong about
 * the conclusion: what redeems a condition match is not a resolution but a BOUNDARY — whether some
 * matched record carries the token as a whole word rather than inside a longer one.
 * {@code DrugSafetyValidator.aMatchedConditionCarriesTheToken} is that question, and it reuses
 * {@link DrugReference#containsWord}, the PROSE rule, because that is the shape
 * {@code PatientClinicalContext.containsToken}'s own javadoc describes the condition haystack by ("a
 * condition in the clinician's own wording"). No matching behaviour moves: {@code hasConditionToken} is
 * byte-identical and every chip this arm raises is unchanged.
 *
 * <p><b>What the measurement decided</b> (issue #309, over the OpenMRS 3.7.1 reference-application demo
 * dictionary; the figures and both corpora are recorded on
 * {@link PatientClinicalContext#containsToken}). The hazard is real and its false matches are clinical:
 * {@code liver} matches 30 of that dictionary's 2581 condition candidates and 20 of the 30 carry it only
 * inside a deliver/delivery form. The LOSS is zero over the only real curated condition population that
 * exists — all four condition tokens the shipped seed publishes match every value they match as whole
 * words, over both corpora — which is the proxy issue #223 used for the allergy side, run for
 * conditions. {@link #aShippedSeedConditionTokenIsStillStatedAsRecorded} is that population's guard.
 *
 * <p><b>Prompt-facing only</b>, exactly as issues #269 and #308 scoped themselves: this is
 * {@code corroboratedByTheChart}, which both injected channels ask and which no chip reads. The chip's
 * own demotion ({@code contraindicationRank}, issue #223) is allergy-typed and stays so.
 *
 * <p><b>The residue, deliberately given up.</b> A prefix or suffix compound that is clinically the same
 * finding is hedged: {@code Lymphedema} and {@code Angioedema} for a rule on {@code edema}, pinned by
 * {@link #aClinicallyRightCompoundIsHedgedToo} so the cost is a case rather than a sentence.
 */
public class ConditionRuleBoundaryCorroborationTest {

	private static final String FIXTURE =
			"chartsearchai-test/drug-reference-condition-token-nesting.json";

	private static final String RECORDED = DrugReferenceInjector.RECORDED_READING_LEAD;

	private static final String UNCORROBORATED = DrugReferenceInjector.UNCORROBORATED_READING_LEAD;

	/** The injected {@code drug_reference} record for {@code question}, for a patient whose recorded
	 *  conditions are {@code conditions} — the real injector, the real validator, the real fixture
	 *  parsed by the real {@link JsonDrugReferenceSource}. */
	private static String record(String question, String... conditions) throws IOException {
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(FIXTURE));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null, null,
								DrugReferenceTestSupport.set(conditions)),
						question);
		RecordMapping reference = DrugReferenceTestSupport.injectedReference(chart);
		return reference == null ? "" : reference.getText();
	}

	/** The injected {@code safety_finding} texts for the same arrangement — the second channel, which
	 *  since issue #308 states the same answer and must not disagree with the record. */
	private static List<String> findings(String question, String... conditions) throws IOException {
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(FIXTURE));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null, null,
								DrugReferenceTestSupport.set(conditions)),
						question);
		List<String> texts = new ArrayList<String>();
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			texts.add(finding.getText());
		}
		return texts;
	}

	private static String clauseSection(String record, String clause) {
		int at = record.indexOf(clause);
		assertTrue(at >= 0, "the record does not carry the clause at all: " + record);
		int recorded = record.lastIndexOf(RECORDED, at);
		int uncorroborated = record.lastIndexOf(UNCORROBORATED, at);
		if (recorded < 0 && uncorroborated < 0) {
			return "no patient-specific section";
		}
		return recorded > uncorroborated ? RECORDED : UNCORROBORATED;
	}

	@Test
	public void aConditionTokenNestingInsideARecordedConditionIsNotStatedAsTheChartsOwnReading()
			throws IOException {
		// 'liver' inside 'Status Post Cesarean Delivery' — the condition analogue of opium/Tiotropium,
		// and a real concept name from the dictionary the measurement was run over.
		String record = record("Can I give her naltrexone?", "Status Post Cesarean Delivery");
		assertEquals(UNCORROBORATED,
				clauseSection(record, "acute hepatitis or liver failure"),
				"a condition token the recorded condition carries only mid-word must not be stated as "
						+ "this chart's own reading: " + record);
	}

	@Test
	public void aConditionRecordingTheTokenAsAWholeWordIsStillStatedAsRecorded() throws IOException {
		// The other direction, and the one a boundary rule is at risk of breaking: the fragment match
		// the bare containment exists FOR still reaches the recorded section.
		String record = record("Can I give her naltrexone?", "Chronic liver disease");
		assertEquals(RECORDED, clauseSection(record, "acute hepatitis or liver failure"),
				"a recorded condition carrying the token as a whole word still states it: " + record);
	}

	@Test
	public void aShippedSeedConditionTokenIsStillStatedAsRecorded() throws IOException {
		// The LOSS side, over the only real curated condition population that exists. Measured over both
		// corpora: every value the shipped seed's four condition tokens match carries the token as a
		// whole word, so this rule costs that population nothing. 'peptic ulcer' inside 'Peptic Ulcer of
		// Stomach' is one of those five matches, and it is exactly the multi-word fragment case that
		// tightening hasConditionToken itself was declined for.
		String record = record("Can I give her ibuprofen?", "Peptic Ulcer of Stomach");
		assertEquals(RECORDED, clauseSection(record, "active peptic ulcer disease"),
				"the shipped seed's own condition token must keep stating its clause: " + record);
	}

	@Test
	public void aTokenNamingADifferentOrganIsNotStatedAsTheChartsOwnReading() throws IOException {
		// 'renal' inside 'adrenal' — a tumour of a different organ, and the second hazard shape the
		// measurement found. Here so the case does not rest on one witness.
		String record = record("Can I give her metformin?", "Malignant tumor of adrenal gland");
		assertEquals(UNCORROBORATED,
				clauseSection(record, "significant renal impairment"),
				"'renal' inside 'adrenal' is not a record of renal impairment: " + record);
	}

	@Test
	public void theFindingChannelStatesTheSameAnswerAsTheRecord() throws IOException {
		// Issue #308: two citable records of one chart must not come apart, and the model was measured
		// answering from the finding when only the record was qualified.
		List<String> findings = findings("Can I give her naltrexone?", "Status Post Cesarean Delivery");
		assertEquals(1, findings.size(), "one fact is one citable record, was: " + findings);
		assertTrue(findings.get(0).contains(DrugReferenceInjector.FINDING_UNCORROBORATED_MATCH),
				"the finding must carry the same provenance clause the record does: " + findings.get(0));
	}

	@Test
	public void aClinicallyRightCompoundIsHedgedToo() throws IOException {
		// The residue, kept as a case rather than a sentence: 'Lymphedema' IS oedema, and the boundary
		// rule hedges it. Measured over the dictionary's 2581 condition candidates, 'edema' matches 13
		// values and 7 carry it only mid-word, most of them compounds of exactly this kind. This is the
		// cost a future change would have to weigh, and it is asserted so that change reads it.
		String record = record("Can I give her paracetamol?", "Lymphedema");
		assertEquals(UNCORROBORATED, clauseSection(record, "fluid retention"),
				"the residue this rule gives up, pinned rather than described: " + record);
	}

	@Test
	public void anUnmatchedConditionRuleIsUnaffected() throws IOException {
		// The boundary question is asked only of a rule that has already matched, which both call sites
		// gate on. A rule the chart does not record at all keeps stating its own section, so this change
		// cannot have moved a clause into the reading that was never in it.
		String record = record("Can I give her naltrexone?", "Malaria");
		assertFalse(record.contains(UNCORROBORATED + "acute hepatitis or liver failure"),
				"a rule the chart never matched must not be hedged as a matched one: " + record);
	}
}
