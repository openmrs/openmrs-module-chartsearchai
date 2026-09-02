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
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Echo scoping for the answer side of {@link DrugSafetyValidator} (issues #105 and #360): a drug the
 * ANSWER names while a record the mention is ATTRIBUTABLE to already names it (a recited reference
 * partner, an allergy the answer reports off the chart) is a mention, not a proposal — it must not be
 * validated against the patient as if someone suggested prescribing it. A drug the QUESTION names, or
 * that the answer introduces on its own authority (no attributable record contains it), keeps the
 * full safety check.
 *
 * <p>Attributable is the union of two things, and which half a case exercises is the point of the
 * case. A chart record must be CITED inline — it is the patient's own data, so the marker is the
 * evidence that the answer was reporting it rather than proposing. A reference-group record needs no
 * citation: this module put it in the prompt and does not need the model to say so. Requiring one was
 * issue #360 — an answer that emitted no bracket had the whole of #105's scoping skipped, and recited
 * partners raised Major chips about drugs neither charted nor asked about.
 *
 * <p>All scenarios run the real pipeline: entries parsed from the real DDInter excerpt,
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

	/** Context: one active Simvastatin order and nothing else — the typo control's patient. */
	private static PatientClinicalContext simvastatinOrderCtx() {
		return DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Simvastatin"),
				DrugReferenceTestSupport.set("C10AA01"), null, null);
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
	public void uncitedRecitationOfAnInjectedRecordIsNotValidatedAsAProposal() {
		// Issue #360, the reported shape: the same recitation as the case above with the citation
		// marker taken off. Live, one Metformin-interactions answer that emitted no bracket recited six
		// partner names out of the injected record and raised four chips — a Moderate and three Majors
		// — about drugs neither charted nor asked about, while the same question about another drug
		// happened to end in a marker and raised none. Whether a clinician saw three spurious Major
		// warnings was decided by whether the model wrote "[7]".
		PatientChart chart = injectedIbuprofenChart();
		RecordMapping refMapping = firstDrugReferenceMapping(chart);
		assertTrue(refMapping.getText().toLowerCase().contains("lisinopril"),
				"precondition: the real rendered ibuprofen record names lisinopril");

		String answer = "Ibuprofen interacts with lisinopril (Moderate. NSAIDs may attenuate the "
				+ "antihypertensive effects of ACE inhibitors).";
		assertTrue(ChartSearchAiUtils.citedIndexes(answer).isEmpty(),
				"precondition: this answer must carry no inline citation marker at all");
		List<SafetyWarning> warnings = validator().validate(answer, QUESTION_IBUPROFEN,
				aspirinOrderCtx(), chart.getMappings());

		assertTrue(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "ibuprofen"),
				"the question-named drug keeps its safety check against the aspirin order");
		assertFalse(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "lisinopril"),
				"a partner recited out of an injected reference record must not chip as a proposal "
						+ "merely because the answer emitted no bracket, was: " + warnings);
	}

	@Test
	public void aDrugNoInjectedRecordNamesIsStillValidatedWhenTheAnswerProposesItUncited() {
		// The net issue #360's widening must not swallow, and the case that makes the assertion above
		// non-vacuous: the chart carries a REAL injected reference record, the answer cites nothing,
		// and the drug it proposes is one that record does not name. Sertraline x aspirin is a Moderate
		// row, so the chip is available; only attribution can withhold it, and nothing attributes this
		// mention to anything.
		PatientChart chart = injectedIbuprofenChart();
		RecordMapping refMapping = firstDrugReferenceMapping(chart);
		assertFalse(refMapping.getText().toLowerCase().contains("sertraline"),
				"precondition: the rendered ibuprofen record must NOT name sertraline, or this case "
						+ "asserts nothing — it was: " + refMapping.getText());

		List<SafetyWarning> warnings = validator().validate("Sertraline would be a reasonable choice.",
				QUESTION_IBUPROFEN, aspirinOrderCtx(), chart.getMappings());

		assertTrue(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "sertraline"),
				"a drug no attributable record names is the answer's own proposal and keeps its "
						+ "safety check, was: " + warnings);
	}

	@Test
	public void aQuestionMisspellingItsDrugStillValidatesTheSpellingTheAnswerGetsRight() {
		// Issue #105's own typo control, which issue #360 requires unchanged: the question misspells
		// the drug so it resolves to nothing and NOTHING reference-group is injected for it, the answer
		// spells it right and cites nothing, and the answer-side match is the only thing that can raise
		// the chip. A fix that widened attribution far enough to reach this would trade the false
		// positive above for a real miss, which is what the issue forbids.
		//
		// Clarithromycin x Simvastatin (Major) is the pinned excerpt's macrolide analogue of #105's own
		// erythromycin x simvastatin. Run through the REAL injector so whatever the pre-answer pass
		// injects for a drug-less question is in the corpus this asserts against, rather than assumed
		// to be nothing.
		String question = "what other drugs are contraindicated with clarithromicin?";
		PatientChart chart = DrugReferenceTestSupport.injector(DrugReferenceTestSupport.ddinterService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), simvastatinOrderCtx(), question);
		String answer = "Clarithromycin should be avoided.";
		assertTrue(ChartSearchAiUtils.citedIndexes(answer).isEmpty(),
				"precondition: this answer must carry no inline citation marker at all");

		List<SafetyWarning> warnings = validator().validate(answer, question, simvastatinOrderCtx(),
				chart.getMappings());

		assertTrue(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION,
				"clarithromycin"), "the spelling the ANSWER got right must still be checked against "
						+ "her simvastatin order, was: " + warnings);
	}

	@Test
	public void aPartnerTheAnswerProposesRatherThanRecitesIsWithheldToo() {
		// The residue issue #360 accepts, pinned so it reads as a decision rather than a discovery.
		// Attribution is by RECORD and not by how the answer uses the name, so this answer — which
		// proposes lisinopril on its own authority rather than reciting it — is withheld exactly as the
		// recitation above is. It is issue #105's own accepted trade with the bracket removed: #105
		// already exempted an answer that BOTH cited a record naming the drug AND proposed it.
		//
		// What is given up is systematic rather than incidental: an injected drug_reference record
		// renders the question drug's own partner list, so the drugs this cannot chip from the answer
		// side are that drug's KB partners — which is also the set an answer draws alternatives from.
		// The bound is NOT the order-driven contraindication arm, which restores contraindications and
		// only for a drug the patient is already on; what stays withheld here is an INTERACTION finding
		// about a drug she is not on. Deciding it any other way needs evidence the answer RECITED the
		// name, and the one such signal available — text the answer reproduces from the record — is
		// refuted by measurement on the real chart, where the model paraphrases and misspells the very
		// prose it is copying.
		PatientChart chart = injectedIbuprofenChart();
		RecordMapping refMapping = firstDrugReferenceMapping(chart);
		assertTrue(refMapping.getText().toLowerCase().contains("lisinopril"),
				"precondition: the real rendered ibuprofen record names lisinopril");

		List<SafetyWarning> warnings = validator().validate(
				"Her hypertension is untreated; consider starting lisinopril.", QUESTION_IBUPROFEN,
				aspirinOrderCtx(), chart.getMappings());

		assertFalse(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "lisinopril"),
				"a proposal of a drug an injected record names is withheld too — the accepted residue, "
						+ "was: " + warnings);
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
	public void anUncitedChartRecordNamingTheDrugDoesNotExemptIt() {
		// The boundary issue #360 stops at, and the only case that discriminates it: the chart half of
		// the attribution corpus still requires an inline marker. Same fixture as the panadol case
		// below with the [230] taken off — the record still names Aspirin, and the answer still says
		// it, but nothing attributes the mention to the record, so aspirin stays in play and chips.
		//
		// A chart record is the patient's own data: a drug named in one is by construction about this
		// patient, so the marker is doing real work there — it is the evidence the answer was REPORTING
		// the record rather than proposing on its own authority. Widening this half as well would
		// exempt a genuine proposal whenever her own notes happen to name the drug. Neuter
		// isModuleSuppliedReferenceRecord to a constant true and read this failure.
		List<RecordMapping> mappings = Arrays.asList(
				new RecordMapping(230, "allergy", "allergy-uuid-1", null, "Allergy: Aspirin (severity unknown)"));
		String answer = "The patient has a recorded allergy to Aspirin.";
		assertTrue(ChartSearchAiUtils.citedIndexes(answer).isEmpty(),
				"precondition: this answer must carry no inline citation marker at all");

		List<SafetyWarning> warnings = validator().validate(answer, "Is it safe to give her panadol?",
				simvastatinWithAspirinAllergyCtx(), mappings);

		assertFalse(warnings.isEmpty(),
				"an UNCITED chart record must not attribute the answer's mention to itself, was: "
						+ warnings);
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
