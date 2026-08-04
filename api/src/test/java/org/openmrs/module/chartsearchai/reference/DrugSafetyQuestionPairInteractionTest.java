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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * The question-named PAIR arm (issue #114). Measured on the 3.7.1 standalone at HEAD
 * {@code 13690b1}: Mary Smith, active order Simvastatin 20mg, asked "Does warfarin interact with
 * aspirin?" got the answer "The records do not address the interaction between warfarin and
 * aspirin.", {@code references: []}, and exactly one chip — "Warfarin interacts with active order
 * simvastatin — <b>Minor</b>" — about a pair nobody asked about, while the pair she DID ask about
 * is rated Major in the loaded KB ({@code ["DDInter1951","DDInter20","Major","749"]}). The same
 * question fires correctly for Agnes Adams, whose chart carries Aspirin 81mg.
 *
 * <p>Cause: both drugs resolve from the question and both are validated, but each was only ever
 * checked against the CHART ({@code hasActiveDrug}), never against the other drug the question
 * named — so a two-drug question was silently reduced to two independent one-drug questions.
 *
 * <p>All scenarios run the real pipeline: real bundled datasets parsed by the real sources, real
 * {@code validate}/{@code injectRecords} overloads, GP reads on their no-context defaults.
 */
public class DrugSafetyQuestionPairInteractionTest {

	private static final String PAIR_QUESTION = "Does warfarin interact with aspirin?";

	/** The abstention the standalone actually produced for {@link #PAIR_QUESTION} — the chip must
	 *  not depend on the answer naming anything, so the defect's own answer text is used. */
	private static final String ABSTAINING_ANSWER = "The records do not address the interaction between warfarin and aspirin.";

	private static DrugSafetyValidator ddinterValidator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService());
	}

	/** A patient on nothing at all: no active order can contribute, so only the pair arm can. */
	private static PatientClinicalContext patientOnNeitherDrug() {
		return DrugReferenceTestSupport.ctx(60, null, null, null, null, null);
	}

	/** The real parsed entry for {@code name} (real bundled DDInter sample, real parser). */
	private static DrugReference ddinterEntry(String name) {
		return new DdiDrugReferenceSource().load().stream()
				.filter(r -> name.equalsIgnoreCase(r.getName())).findFirst().orElseThrow();
	}

	private static long interactionChips(List<SafetyWarning> warnings) {
		return warnings.stream().filter(w -> SafetyWarning.TYPE_INTERACTION.equals(w.getType())).count();
	}

	@Test
	public void questionNamedPairInteractionIsReportedForAPatientOnNeitherDrug() {
		List<SafetyWarning> warnings = ddinterValidator().validate(ABSTAINING_ANSWER, PAIR_QUESTION,
				patientOnNeitherDrug());

		assertEquals(1, warnings.size(),
				"the pair the question named must raise exactly one chip even though the patient takes"
						+ " neither drug, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "Acetylsalicylic acid (aspirin)", "Major"),
				"the chip must name the pair asked about and carry the source's severity, was: " + warnings);
	}

	@Test
	public void theQuestionPairChipNeverClaimsAnActiveOrder() {
		// The provenance distinction is the whole safety of this arm: an active-order interaction is
		// a fact about THIS patient, a question-pair interaction is a reference lookup that may
		// involve no drug they take. Wording the second like the first asserts a medication the
		// patient is not on — the defect in #86, and worse than the bug being fixed here.
		List<SafetyWarning> warnings = ddinterValidator().validate(ABSTAINING_ANSWER, PAIR_QUESTION,
				patientOnNeitherDrug());

		assertEquals(1, interactionChips(warnings), "precondition: the pair chip must have been raised");
		for (SafetyWarning warning : warnings) {
			assertFalse(warning.getDetail().toLowerCase().contains("active order"),
					"this patient has no active orders at all, so no chip may claim one: " + warning);
		}
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "named in the question"),
				"the chip must attribute the pair to the question, not to the chart, was: " + warnings);
	}

	@Test
	public void aSymmetricPairIsReportedOnlyOnce() {
		// DDInter rows are symmetric and the parser writes each pair into BOTH drugs' entries
		// (DdiDrugReferenceSource: "each pair contributes to both drugs' entries"), so an arm that
		// walks ordered pairs reports one interaction twice — once from each side.
		DrugReference warfarin = ddinterEntry("Warfarin");
		DrugReference aspirin = ddinterEntry("Acetylsalicylic acid");
		assertTrue(warfarin.getInteractions().stream().anyMatch(i -> "aspirin".equals(i.getToken())),
				"precondition: warfarin's entry carries the pair");
		assertTrue(aspirin.getInteractions().stream().anyMatch(i -> "warfarin".equals(i.getToken())),
				"precondition: aspirin's entry carries the same pair from the other side");

		List<SafetyWarning> warnings = ddinterValidator().validate(ABSTAINING_ANSWER, PAIR_QUESTION,
				patientOnNeitherDrug());

		assertEquals(1, interactionChips(warnings),
				"a pair present on both entries must be reported once, not once per side, was: " + warnings);
	}

	@Test
	public void aSubFloorQuestionNamedPairRaisesNothing() {
		// The pair arm must not become a route around the decision the chip path enforces: DDInter's
		// Unknown-severity rows carry no mechanism text and are suppressed by
		// chartsearchai.drugSafety.minInteractionSeverity (issue #84). Simvastatin x aspirin is
		// exactly that shape.
		DrugReference simvastatin = ddinterEntry("Simvastatin");
		assertTrue(simvastatin.getInteractions().stream()
				.anyMatch(i -> "aspirin".equals(i.getToken()) && "Unknown".equals(i.getSeverity())),
				"precondition: the sample rates simvastatin x aspirin Unknown, i.e. below the default floor");

		List<SafetyWarning> warnings = ddinterValidator().validate(
				"The records do not address the interaction between simvastatin and aspirin.",
				"Does simvastatin interact with aspirin?", patientOnNeitherDrug());

		assertTrue(warnings.isEmpty(),
				"a question-named pair the source rates below the floor must raise nothing, was: " + warnings);
	}

	@Test
	public void aQuestionNamedPairWithNoInteractionRowRaisesNothing() {
		// The no-false-positive case, on the curated seed (source-independent arm): its Ibuprofen
		// entry's rules name warfarin and aspirin, and its Paracetamol entry's rule names warfarin —
		// neither names the other, so naming both in one question must produce nothing.
		DrugReferenceService curated = DrugReferenceTestSupport.bundledService();
		String question = "Can we give ibuprofen together with paracetamol?";
		List<DrugReference> resolved = curated.findByQuery(question);
		assertTrue(resolved.stream().anyMatch(r -> "Ibuprofen".equals(r.getName()))
				&& resolved.stream().anyMatch(r -> "Paracetamol".equals(r.getName())),
				"precondition: the question must resolve BOTH drugs, else the pair arm is never reached: "
						+ resolved);

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(curated).validate(
				"Both are commonly used for pain and fever.", question, patientOnNeitherDrug());

		assertTrue(warnings.isEmpty(),
				"two question-named drugs with no interaction row between them must raise nothing —"
						+ " the pair arm must not become a chip generator, was: " + warnings);
	}

	@Test
	public void theActiveOrderChipWinsWhenThePatientIsOnOneOfTheNamedDrugs() {
		// Agnes Adams' shape on the standalone (active order Aspirin 81mg), asked about the same
		// pair. The interaction is then a fact about THIS patient — the stronger statement — so the
		// active-order arm owns it, and the pair must not also be reported as a reference lookup:
		// one finding said in two voices reads as two findings.
		List<SafetyWarning> warnings = ddinterValidator().validate(
				"Warfarin and aspirin together increase bleeding risk.", PAIR_QUESTION,
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin 81mg"),
						DrugReferenceTestSupport.set("N02BA01"), null, null));

		assertEquals(1, warnings.size(), "the pair must be reported exactly once, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "active order", "Major"),
				"the surviving chip must be the patient-specific active-order one, was: " + warnings);
		for (SafetyWarning warning : warnings) {
			assertFalse(warning.getDetail().contains("named in the question"),
					"a pair covered by the chart must not also be reported as a reference lookup: " + warning);
		}
	}

	@Test
	public void theQuestionPairFindingIsInjectedAsACitableRecord() {
		// How the prose gets grounding for the pair without touching the capped Interactions:
		// rendering (#110's mechanism): preAnswerFindings runs the same validate() with an empty
		// answer, so the pair finding becomes its own numbered, citable record. That keeps
		// orderedInteractionNotes' invariant intact — its promotion predicate still mirrors the
		// active-order chip decision exactly — while the model gets a line it can report.
		DrugReferenceService service = DrugReferenceTestSupport.ddinterService();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		PatientChart result = injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				patientOnNeitherDrug(), PAIR_QUESTION);

		RecordMapping finding = null;
		for (RecordMapping mapping : result.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(mapping.getResourceType())) {
				finding = mapping;
			}
		}
		assertNotNull(finding, "the pair finding must be injected as its own record: " + result.getText());
		assertTrue(finding.getText().contains("Acetylsalicylic acid (aspirin)")
				&& finding.getText().contains("Major"),
				"the finding must name the pair and its severity: " + finding.getText());
		assertTrue(result.getText().contains("[" + finding.getIndex() + "] "),
				"it must be a numbered, citable chart line so the answer can cite it: " + result.getText());
	}
}
