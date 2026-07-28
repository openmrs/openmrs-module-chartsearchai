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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Echo scoping for the answer side of {@link DrugSafetyValidator} (issue #105): a drug the
 * ANSWER names while a record the answer cites already names it (a recited reference partner,
 * an allergy the answer reports off the chart) is a mention, not a proposal — it must not be
 * validated against the patient as if someone suggested prescribing it. A drug the QUESTION
 * names, or that the answer introduces on its own authority (no citation anywhere, or cited
 * records that do not contain it), keeps the full safety check.
 *
 * <p>All scenarios run the real pipeline: entries parsed from the real bundled DDInter sample,
 * charts built by the real injector, and the real {@code validate} overload that production
 * calls with the chart's mappings.
 */
public class DrugSafetyValidatorEchoScopingTest {

	private static final String QUESTION_IBUPROFEN = "Can she take ibuprofen for pain?";

	/** Context: active order Aspirin (level-5 code), nothing else. */
	private static PatientClinicalContext aspirinOrderCtx() {
		return DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin"),
				DrugReferenceTestSupport.set("B01AC06"), null, null);
	}

	/** Context: active order Simvastatin plus a recorded aspirin allergy (the panadol shape). */
	private static PatientClinicalContext simvastatinWithAspirinAllergyCtx() {
		return DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Simvastatin"),
				DrugReferenceTestSupport.set("C10AA01"), DrugReferenceTestSupport.set("aspirin"), null);
	}

	/** The thin file-shaped delegates DrugReferenceTestSupport's javadoc prescribes. */
	private static DrugSafetyValidator validator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService());
	}

	/** Real chart with the ibuprofen reference injected by the REAL injector for the shared question. */
	private static PatientChart injectedIbuprofenChart() {
		return DrugReferenceTestSupport.injector(DrugReferenceTestSupport.ddinterService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), aspirinOrderCtx(), QUESTION_IBUPROFEN);
	}

	private static RecordMapping firstDrugReferenceMapping(PatientChart chart) {
		return chart.getMappings().stream()
				.filter(m -> ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(m.getResourceType()))
				.findFirst().orElseThrow(() -> new IllegalStateException(
						"no drug-reference record was injected into the chart"));
	}

	@Test
	public void echoedReferencePartnerIsNotValidatedAsAProposal() {
		// The erythromycin-cascade shape: the answer recites a partner (lisinopril) out of the
		// cited drug-reference record. Lisinopril x aspirin is a real row (Moderate), so before
		// echo scoping the recitation raised a Lisinopril chip against the aspirin order even
		// though nobody proposed lisinopril.
		PatientChart chart = injectedIbuprofenChart();
		RecordMapping refMapping = firstDrugReferenceMapping(chart);
		assertTrue(refMapping.getText().toLowerCase().contains("lisinopril"),
				"precondition: the real rendered ibuprofen record names lisinopril");

		String answer = "Ibuprofen interacts with lisinopril (Moderate. NSAIDs may attenuate the "
				+ "antihypertensive effects of ACE inhibitors) [" + refMapping.getIndex() + "].";
		List<SafetyWarning> warnings = validator().validate(answer, QUESTION_IBUPROFEN,
				aspirinOrderCtx(), chart.getMappings());

		assertTrue(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "ibuprofen"),
				"the question-named drug keeps its safety check against the aspirin order");
		assertFalse(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "lisinopril"),
				"a partner recited out of the cited reference record must not chip as a proposal");
	}

	@Test
	public void allergyEchoedOffTheChartDoesNotChipItself() {
		// The panadol shape: the question's drug resolves to nothing, and the answer reports the
		// patient's aspirin allergy from a cited chart record. Before echo scoping that mention
		// made aspirin "in play", raising a circular contraindication chip ("the patient has a
		// recorded allergy to the drug she is allergic to") plus an interaction chip against her
		// own simvastatin order — warnings about a drug nobody proposed.
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(230, "allergy", "allergy-uuid-1", null, "Allergy: Aspirin (severity unknown)"));
		String answer = "The patient has a recorded allergy to Aspirin [230]. "
				+ "No information regarding an allergy or contraindication to Panadol is recorded.";
		List<SafetyWarning> warnings = validator().validate(answer, "Is it safe to give her panadol?",
				simvastatinWithAspirinAllergyCtx(), mappings);

		assertTrue(warnings.isEmpty(),
				"an allergy the answer reports off the chart must raise no chips, was: " + warnings);
	}

	@Test
	public void uncitedAnswerProposalIsStillValidated() {
		// The net this scoping must not weaken: a question that names no drug, answered by a
		// bare recommendation with no citation. The proposal keeps the full check and still
		// chips against the active aspirin order.
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(3, "obs", "obs-uuid-3", null, "Pain score 7/10"));
		List<SafetyWarning> warnings = validator().validate("Ibuprofen would be a reasonable choice.",
				"What can we give for pain?", aspirinOrderCtx(), mappings);

		assertTrue(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "ibuprofen"),
				"an answer-proposed drug must keep its safety check");
	}

	@Test
	public void citedRecordThatDoesNotContainTheDrugDoesNotExemptIt() {
		// A citation next to the mention only exempts the drug when the cited record itself
		// names it — citing a pain score while recommending ibuprofen is a proposal.
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(3, "obs", "obs-uuid-3", null, "Pain score 7/10"));
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen is appropriate given her pain score [3].",
				"What can we give for pain?", aspirinOrderCtx(), mappings);

		assertTrue(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "ibuprofen"),
				"a cited record that does not name the drug must not exempt the proposal");
	}

	@Test
	public void questionNamedDrugIsValidatedEvenWhenTheAnswerOnlyEchoesIt() {
		// Question-side naming always wins: even when every answer mention of the drug is an
		// echo of the cited reference record, the clinician asked about it, so it is checked.
		PatientChart chart = injectedIbuprofenChart();
		RecordMapping refMapping = firstDrugReferenceMapping(chart);

		String answer = "Record [" + refMapping.getIndex() + "] lists interactions for Ibuprofen.";
		List<SafetyWarning> warnings = validator().validate(answer, QUESTION_IBUPROFEN,
				aspirinOrderCtx(), chart.getMappings());

		assertTrue(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "ibuprofen"),
				"a question-named drug is always validated, echo or not");
	}

	@Test
	public void publicMappingsEntryPointFailsSafeWithoutOpenmrsContext() {
		// Executes the real public 4-arg entry point (the one production calls): with no OpenMRS
		// context the feature GP reads fail-safe to disabled, so it must return empty and never
		// throw — the same never-break-the-answer-path contract as the 3-arg entry.
		List<SafetyWarning> warnings = validator().validate(
				"Ibuprofen would be a reasonable choice.", "What can we give for pain?",
				(org.openmrs.Patient) null, Collections.<RecordMapping> emptyList());

		assertTrue(warnings.isEmpty(), "no OpenMRS context -> feature reads disabled -> empty, no throw");
	}

	@Test
	public void withoutMappingsEveryAnswerMentionIsValidated() {
		// The mappings-less overloads keep the pre-scoping behavior: with no citation targets to
		// attribute an echo to, every answer-named drug is validated (conservative direction).
		List<SafetyWarning> warnings = validator().validate(
				"The patient has a recorded allergy to Aspirin [230].", "Is it safe to give her panadol?",
				simvastatinWithAspirinAllergyCtx(), Collections.<RecordMapping> emptyList());

		assertFalse(warnings.isEmpty(),
				"no mappings -> no echo attribution possible -> answer mentions stay validated");
	}
}
