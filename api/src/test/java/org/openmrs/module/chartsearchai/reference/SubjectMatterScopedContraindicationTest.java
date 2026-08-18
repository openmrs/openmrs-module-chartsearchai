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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * The active-order contraindication arm is scoped to what the RESPONSE IS ABOUT.
 *
 * <p><b>Why this exists.</b> chartsearchai is a question-answering module — its README opens "lets
 * clinicians ask natural language questions about a patient's chart and get answers with source
 * citations" — and {@link DrugSafetyValidator}'s own contract is a check that "runs after the answer
 * is generated and <em>annotates</em> it". Issue #143 widened one arm past that contract: it walked
 * every active order against every recorded allergy and condition <em>whatever the question and the
 * answer named</em>, so the identical chips rode every response. Measured live on the 3.7.1
 * standalone against patient {@code a7090f70}: "any allergies?", "are there any drug interactions
 * with her current medications?", "does she have cancer?" and "what is her date of birth?" all
 * returned the same two contraindication chips, byte for byte. An alert with no acknowledgement
 * state, repeated on a channel that only opens when someone asks something else, is the shape that
 * trains a clinician to stop reading it.
 *
 * <p><b>What replaces it.</b> A chip is raised when either side of it is in the response's subject
 * matter — the question, the answer, or a record the answer cited. Both sides count because a
 * contraindication relates two things and either can be what was asked about: the DRUG side keeps
 * the case issue #143 was really built for (a prescribed drug named only by a cited
 * {@code drug_order} record, which issue #105's echo rule removes from the in-play set), and the
 * FINDING side keeps a chip whose drug is never mentioned but whose allergy or condition is what the
 * response is about. A question in the medication or allergy domain widens its own side wholesale,
 * because there the patient's list IS the topic even when no individual name is written out.
 *
 * <p><b>What this deliberately gives up</b>, recorded so it is not rediscovered as a bug: a
 * prescribing error nobody ever asks a drug-shaped question about is no longer announced. That is
 * not a safety net this module can honestly carry — it has no subscription, no acknowledgement and
 * no delivery path that opens unprompted — and the finding belongs on a surface that has them
 * (order entry, a chart banner, CDS hooks). See the rewritten case in
 * {@link ActiveOrderContraindicationTest}, which is where this reverses a documented decision.
 *
 * <p>Every case drives the real {@code DrugSafetyValidator.validate} over the real bundled curated
 * dataset ({@code sourceFormat=json}, whose ibuprofen entry carries both an identity-resolvable name
 * and curated allergy and condition rules) with real querystore-shaped chart records.
 */
public class SubjectMatterScopedContraindicationTest {

	private static final String IBUPROFEN_ORDER = "Ibuprofen 400mg";

	/** The order record the patient's chart carries, and the one an answer about her medications cites. */
	private static final RecordMapping ORDER_RECORD =
			DrugReferenceTestSupport.drugOrderRecord(2, "order-uuid-1", IBUPROFEN_ORDER);

	/** A chart record about something else entirely — the shape of the cancer question that prompted this. */
	private static final RecordMapping TUMOUR_RECORD = new RecordMapping(1,
			ChartSearchAiConstants.RESOURCE_TYPE_CONDITION, "condition-uuid-1", null,
			"Condition: Malignant tumor of base of tongue");

	/** The condition one of ibuprofen's curated rules fires on, as a chart record an answer can cite. */
	private static final RecordMapping ULCER_RECORD = new RecordMapping(3,
			ChartSearchAiConstants.RESOURCE_TYPE_CONDITION, "condition-uuid-3", null,
			"Condition: peptic ulcer disease");

	private static DrugSafetyValidator validator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.bundledService());
	}

	private static PatientClinicalContext ctx(java.util.Set<String> allergies,
			java.util.Set<String> conditions) {
		return DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(IBUPROFEN_ORDER),
				null, allergies, conditions);
	}

	private static PatientChart chart(RecordMapping... records) {
		return DrugReferenceTestSupport.chartOf(records);
	}

	private static List<SafetyWarning> contraindications(List<SafetyWarning> warnings) {
		List<SafetyWarning> out = new ArrayList<SafetyWarning>();
		for (SafetyWarning warning : warnings) {
			if (SafetyWarning.TYPE_CONTRAINDICATION.equals(warning.getType())) {
				out.add(warning);
			}
		}
		return out;
	}

	@Test
	public void aResponseAboutSomethingElseRaisesNoChipAboutHerPrescriptions() {
		// The reported case, reduced: she is on ibuprofen and allergic to ibuprofen, and the clinician
		// asked about her cancer. The order record is IN the chart and simply not what the answer is
		// about — nothing here is a drug question, an allergy question, or a citation naming either.
		List<SafetyWarning> warnings = validator().validate(
				"Yes — the patient has a Malignant tumor of base of tongue [1].", "Does she have cancer?",
				ctx(DrugReferenceTestSupport.set("ibuprofen"), null),
				chart(TUMOUR_RECORD, ORDER_RECORD).getMappings());

		assertEquals(0, contraindications(warnings).size(),
				"a question about her cancer must not carry chips about her prescriptions, was: "
						+ warnings);
	}

	@Test
	public void theDrugSideStillFiresWhenTheAnswerCitesTheOrderRecord() {
		// Issue #143's real case, preserved: the answer names the drug only by reciting the cited
		// drug_order record, so #105's echo rule keeps it out of the in-play set and this arm is the
		// only one that can check it. Here the drug IS the subject matter.
		List<SafetyWarning> warnings = validator().validate(
				"Her only active medication is " + IBUPROFEN_ORDER + " [2].", "What is she taking?",
				ctx(DrugReferenceTestSupport.set("ibuprofen"), null),
				chart(TUMOUR_RECORD, ORDER_RECORD).getMappings());

		assertEquals(1, contraindications(warnings).size(),
				"the prescribed drug the answer is about must still be checked, was: " + warnings);
		// The curated rule outranks the identity check where it carries the deployment's own note
		// (issue #146), which is unchanged by this scoping and pinned here so a later change to the
		// FOLD cannot pass by silently renaming the chip this case is about.
		assertEquals("Ibuprofen is contraindicated by an active allergy: documented ibuprofen allergy",
				contraindications(warnings).get(0).getDetail(),
				"in the curated rule's own wording, was: " + warnings);
	}

	@Test
	public void theFindingSideFiresForADrugTheResponseNeverMentions() {
		// The other half of "either side": the answer is about her peptic ulcer and never writes the
		// word ibuprofen, but the drug that ulcer contraindicates is one she is on. Scoping the arm to
		// the drug side alone would lose this, which is why the rule is two-sided rather than an echo test.
		List<SafetyWarning> warnings = validator().validate(
				"Her active problems include peptic ulcer disease [3].", "What is on her problem list?",
				ctx(null, DrugReferenceTestSupport.set("peptic ulcer disease")),
				chart(TUMOUR_RECORD, ORDER_RECORD, ULCER_RECORD).getMappings());

		assertEquals(1, contraindications(warnings).size(),
				"the finding the response is about must still reach the drug it contraindicates, was: "
						+ warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings,
				SafetyWarning.TYPE_CONTRAINDICATION, "Ibuprofen", "active condition"),
				"worded as the in-play arm words it, was: " + warnings);
	}

	@Test
	public void aFindingTheResponseIsNotAboutStaysSilent() {
		// The finding side is per-finding and not "she has some condition somewhere". The answer cites a
		// condition record, so a gate reading "is any recorded finding in subject matter" would pass —
		// and the finding it passes on is not the one the rule fired on. This is the cancer case again
		// at token granularity, and the reason the gate is asked of the rule's own token.
		List<SafetyWarning> warnings = validator().validate(
				"Yes — the patient has a Malignant tumor of base of tongue [1].", "Does she have cancer?",
				ctx(null, DrugReferenceTestSupport.set("peptic ulcer disease")),
				chart(TUMOUR_RECORD, ORDER_RECORD, ULCER_RECORD).getMappings());

		assertEquals(0, contraindications(warnings).size(),
				"a rule may only speak for the finding the response is actually about, was: " + warnings);
	}

	@Test
	public void aMedicationQuestionPutsHerWholeActiveListInSubjectMatter() {
		// The widening, drug side: "what is she on" makes her medication list the topic even though no
		// individual drug name is written anywhere. Without it the arm would depend on the LLM happening
		// to write a name, which is the sort of prose-shaped gate issue #143 was right to distrust.
		List<SafetyWarning> warnings = validator().validate("", "What are her current medications?",
				ctx(DrugReferenceTestSupport.set("ibuprofen"), null), null);

		assertEquals(1, contraindications(warnings).size(),
				"a medication question keeps her own prescriptions in scope, was: " + warnings);
	}

	@Test
	public void anAllergyQuestionPutsHerRecordedAllergiesInSubjectMatter() {
		// The widening, finding side, and the shape the reported "any allergies?" case takes: the
		// question is about her allergies, so a drug one of them contraindicates is worth a chip even
		// when the answer names neither.
		List<SafetyWarning> warnings = validator().validate("", "Does she have any allergies?",
				ctx(DrugReferenceTestSupport.set("ibuprofen"), null), null);

		assertEquals(1, contraindications(warnings).size(),
				"an allergy question keeps her own allergy records in scope, was: " + warnings);
	}
}
