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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Allergen;
import org.openmrs.AllergenType;
import org.openmrs.Allergy;
import org.openmrs.CodedOrFreeText;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.Condition;
import org.openmrs.ConditionClinicalStatus;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue #305: an injected {@code safety_finding} names the chart record it fired on, so the
 * clinician can reach the source record whether or not the model cited it.
 *
 * <p><b>The defect.</b> An answer can assert a fact about the patient's chart — "the patient's
 * allergy to Acetylsalicylic acid" — and cite no chart record for it, leaving only the module's own
 * {@code safety_finding} cited. Measured on the 3.7.1 standalone: {@code Can I give ibuprofen?}
 * returned references {@code [11]} (the allergy) and {@code [239]} (the finding) on 13 runs, while
 * {@code Can i give ibuprofen?} — one character apart — returned {@code [239]} alone on 14. Nothing
 * upstream of the model differed (identical {@code inputTokens}, identical chips), so the
 * click-through to the source record was decided by the wording of the question.
 *
 * <p><b>Why it was unfixable downstream.</b> The provenance was discarded at read time:
 * {@link PatientClinicalContextBuilder} read the patient's allergies and conditions into flat token
 * SETS, dropping each record's uuid, so {@link SafetyWarning} had no record to name and the injected
 * finding carried no link to the chart record behind it.
 *
 * <p><b>Context-sensitive and driven through the real builder</b>, because the defect is in what the
 * builder puts into the context: a hand-built {@link PatientClinicalContext} would bypass the whole
 * change. A real {@link Allergy} is saved through {@code PatientService} and a real {@link Condition}
 * through {@code ConditionService}, and the chart carries the querystore record shape for each — the
 * resource uuid being the {@code Allergy}/{@code Condition} uuid, which is
 * {@code DrugReferenceTestSupport.allergyRecord}'s measured contract.
 *
 * <p>Patient 7 is the arrangement, and its single active drug order (order 111, concept 88 "ASPIRIN")
 * is left in place rather than voided: it raises an INTERACTION finding beside the contraindication
 * under test, through the curated NSAID cross-reactivity group, and that second finding is what
 * {@link #anInteractionFindingNamesNoChartRecord} asserts about. So every case here selects the
 * finding it is about rather than asserting over a set.
 */
public class FindingChartRecordProvenanceContextTest extends BaseModuleContextSensitiveTest {

	/** Concept 88 (ASPIRIN) — nominated as the {@code allergy.concept.otherNonCoded} placeholder so a
	 *  free-text allergen can be saved, which is what {@code AllergyValidator} requires. */
	private static final int OTHER_NON_CODED_CONCEPT = 88;

	private static final String QUESTION = "Can I give ibuprofen?";

	private Patient patient;

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		patient = Context.getPatientService().getPatient(7);
	}

	/**
	 * Saves a recorded allergy to {@code allergen}, as free text — the shape a clinician types, and
	 * the one whose token {@code PatientClinicalContextBuilder.addRaw} collects. A free-text allergen
	 * still needs a coded allergen (the column is not-null and {@code AllergyValidator} requires it to
	 * BE the {@code allergy.concept.otherNonCoded} concept), so one is nominated here exactly as
	 * {@code NonCodedDrugOrderNameTest} does.
	 *
	 * @return the saved {@code Allergy}'s uuid, which is what a querystore {@code allergy} chart
	 *         record carries as its resource uuid
	 */
	private String recordAllergyTo(String allergen) {
		Concept otherNonCoded = Context.getConceptService().getConcept(OTHER_NON_CODED_CONCEPT);
		Context.getAdministrationService()
				.setGlobalProperty("allergy.concept.otherNonCoded", otherNonCoded.getUuid());
		Allergy allergy = new Allergy(patient,
				new Allergen(AllergenType.DRUG, otherNonCoded, allergen), null, null, null);
		Context.getPatientService().saveAllergy(allergy);
		Context.flushSession();
		Context.clearSession();
		return allergy.getUuid();
	}

	/**
	 * A SECOND recorded allergy, to a CODED allergen of its own — a concept created here and named
	 * {@code conceptName}.
	 *
	 * <p>A distinct concept rather than a second free-text spelling of the placeholder, because
	 * {@code AllergyValidator} rejects that: {@code Allergen.equals} compares the coded allergen when
	 * one is set, so two free-text allergens sharing the {@code otherNonCoded} placeholder are
	 * duplicates to the validator however different the clinician's words are. What the pair stands
	 * for is unchanged and real — two chart rows spelling one allergy, which is what
	 * {@code RecordedAllergen.alsoNames} exists to merge.
	 *
	 * @return the saved {@code Allergy}'s uuid
	 */
	private String recordCodedAllergyTo(String conceptName) {
		Concept allergen = new Concept();
		allergen.addName(new ConceptName(conceptName, Locale.ENGLISH));
		allergen.setDatatype(Context.getConceptService().getConceptDatatypeByName("N/A"));
		allergen.setConceptClass(Context.getConceptService().getConceptClassByName("Drug"));
		Context.getConceptService().saveConcept(allergen);
		Allergy allergy = new Allergy(patient,
				new Allergen(AllergenType.DRUG, allergen, null), null, null, null);
		Context.getPatientService().saveAllergy(allergy);
		Context.flushSession();
		Context.clearSession();
		return allergy.getUuid();
	}

	/** Saves an active condition recorded as free text, and returns its uuid. */
	private String recordConditionOf(String condition) {
		Condition c = new Condition();
		c.setPatient(patient);
		c.setClinicalStatus(ConditionClinicalStatus.ACTIVE);
		CodedOrFreeText coded = new CodedOrFreeText();
		coded.setNonCoded(condition);
		c.setCondition(coded);
		Context.getConditionService().saveCondition(c);
		Context.flushSession();
		Context.clearSession();
		return c.getUuid();
	}

	/**
	 * The findings this arrangement raised, split by whether their sentence states an INTERACTION.
	 * Selected on the rendered claim rather than counted, because the active order raises a second
	 * finding of its own — see the class javadoc.
	 */
	private static List<RecordMapping> findings(PatientChart chart, boolean interaction) {
		List<RecordMapping> out = new ArrayList<RecordMapping>();
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			if (finding.getText().contains("interacts with") == interaction) {
				out.add(finding);
			}
		}
		return out;
	}

	/** The one contraindication finding the arrangement raised. */
	private static RecordMapping contraindicationFinding(PatientChart chart) {
		List<RecordMapping> found = findings(chart, false);
		assertEquals(1, found.size(), "exactly one contraindication finding was expected, of the "
				+ DrugReferenceTestSupport.injectedFindings(chart).size() + " raised: "
				+ DrugReferenceTestSupport.injectedFindings(chart));
		return found.get(0);
	}

	private PatientChart inject(PatientChart chart) {
		return inject(chart, QUESTION);
	}

	private PatientChart inject(PatientChart chart, String question) {
		return DrugReferenceTestSupport.injectorWithSafety(DrugReferenceTestSupport.curatedService())
				.injectRecords(chart, PatientClinicalContextBuilder.build(patient), question);
	}

	/**
	 * THE case. The finding fired on the patient's recorded allergy, so it names the number of the
	 * chart record that allergy IS — the record the answer in issue #305's second form asserted and
	 * cited nothing for.
	 */
	@Test
	public void aFindingRaisedByARecordedAllergyNamesTheChartRecordThatAllergyIs() {
		String allergyUuid = recordAllergyTo("Ibuprofen");

		PatientChart injected = inject(DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
				DrugReferenceTestSupport.allergyRecord(2, allergyUuid, "Allergy: Ibuprofen (drug)")));

		RecordMapping finding = contraindicationFinding(injected);
		assertEquals(Collections.singletonList(Integer.valueOf(2)), finding.getDerivedFrom(),
				"the finding fired on the recorded allergy, and record [2] IS that allergy — so the "
						+ "module must say so rather than leaving the clinician's click-through to "
						+ "whether the model happened to cite it (issue #305). Finding was: "
						+ finding.getText());
	}

	/**
	 * The condition leg, through the curated-rule arm. The shipped DDInter source publishes no
	 * contraindication rules at all, so this arm is reachable only over a curated file — which is why
	 * this case uses the curated seed's {@code peptic ulcer} rule on its Ibuprofen entry rather than
	 * the default dataset.
	 */
	@Test
	public void aFindingRaisedByARecordedConditionNamesTheChartRecordThatConditionIs() {
		String conditionUuid = recordConditionOf("Peptic ulcer disease");

		PatientChart injected = inject(DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.obsRecord(1, "BP 120/80"),
				DrugReferenceTestSupport.conditionRecord(2, conditionUuid,
						"Condition: Peptic ulcer disease (active)")));

		RecordMapping finding = contraindicationFinding(injected);
		assertEquals(Collections.singletonList(Integer.valueOf(2)), finding.getDerivedFrom(),
				"a condition rule's finding names the condition record it matched, for the reason the "
						+ "allergy case above gives: both lists are one rule everywhere else in this "
						+ "subsystem (recordedContraindicationKind's two type-exclusive legs). Finding "
						+ "was: " + finding.getText());
	}

	/**
	 * An INTERACTION finding names nothing, and that is the scope line rather than an omission: its
	 * provenance is the patient's active ORDERS, which issue #379 already resolves to record numbers
	 * on a separate, flag-gated path. Two findings of one response, and only the one whose evidence is
	 * a chart record of the contraindication family carries a derivation.
	 */
	@Test
	public void anInteractionFindingNamesNoChartRecord() {
		String allergyUuid = recordAllergyTo("Ibuprofen");

		PatientChart injected = inject(DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.allergyRecord(1, allergyUuid, "Allergy: Ibuprofen (drug)")));

		List<RecordMapping> interactions = findings(injected, true);
		assertEquals(1, interactions.size(), "the active aspirin order must raise one interaction "
				+ "finding through the curated NSAID group, was: " + interactions);
		assertTrue(interactions.get(0).getDerivedFrom().isEmpty(),
				"an interaction finding's evidence is an ORDER, not a contraindication record, so it "
						+ "names no derivation here. Was: " + interactions.get(0).getDerivedFrom());
	}

	/**
	 * One {@code Allergy} contributes up to TWO tokens — {@code addConceptName} on the coded allergen
	 * and {@code addRaw} on the free text — so the token the rule matched need not be the one the
	 * clinician typed. Either way the record behind it is the same one, which is what keying the
	 * provenance on the record's uuid rather than on a name buys.
	 */
	@Test
	public void theRecordIsNamedThroughWhicheverOfItsTwoTokensTheFindingMatched() {
		DrugReferenceTestSupport.nameTheConcept(OTHER_NON_CODED_CONCEPT, "Amoxicillin");
		String allergyUuid = recordAllergyTo("Ibuprofen");

		PatientChart injected = inject(DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.allergyRecord(1, allergyUuid, "Allergy: Amoxicillin (drug)")),
				"Can I give amoxicillin?");

		RecordMapping finding = contraindicationFinding(injected);
		assertEquals(Collections.singletonList(Integer.valueOf(1)), finding.getDerivedFrom(),
				"the finding matched the CODED allergen's token while the same record also carries the "
						+ "free text, and the provenance is the record either way. Finding was: "
						+ finding.getText());
	}

	/**
	 * The refusal that keeps this additive: a chart carrying no record for the allergy the finding
	 * fired on names nothing, and renders exactly as it did before issue #305. A query-scoped slice
	 * need not carry the patient's allergies at all, so this is the ordinary case rather than a
	 * corner one.
	 */
	@Test
	public void aFindingWhoseRecordThisChartDoesNotCarryNamesNothing() {
		recordAllergyTo("Ibuprofen");

		PatientChart injected = inject(DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.obsRecord(1, "BP 120/80")));

		RecordMapping finding = contraindicationFinding(injected);
		assertTrue(finding.getDerivedFrom().isEmpty(),
				"this chart carries no allergy record, so there is nothing to name — and emptiness "
						+ "must not be read as 'this claim rests on nothing'. Was: "
						+ finding.getDerivedFrom());
	}

	/**
	 * The second refusal, and the one ADR Decision 77 is the precedent for: citing is an affirmative
	 * claim about WHICH record, so a uuid two records of this chart both carry names neither. The uuid
	 * index the injector reads is LAST-wins by construction — a fail-open reading that is right for
	 * issue #118's substantiation boolean and wrong here, which is why the citing reading refuses.
	 */
	@Test
	public void aUuidTwoRecordsOfThisChartBothCarryNamesNeither() {
		String allergyUuid = recordAllergyTo("Ibuprofen");

		PatientChart injected = inject(DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.allergyRecord(1, allergyUuid, "Allergy: Ibuprofen (drug)"),
				DrugReferenceTestSupport.allergyRecord(2, allergyUuid, "Allergy: Ibuprofen (drug)")));

		RecordMapping finding = contraindicationFinding(injected);
		assertTrue(finding.getDerivedFrom().isEmpty(),
				"two records carry the allergy's uuid, so the module cannot say which one the finding "
						+ "is evidence of, and must name neither rather than the last one indexed. Was: "
						+ finding.getDerivedFrom());
	}

	/**
	 * Two recorded allergies whose spellings resolve alike are ONE {@code RecordedAllergen} — the
	 * de-duplication {@code DrugSafetyValidator.resolvedAlike} performs, and the merge
	 * {@code RecordedAllergen.alsoNames} carries evidence across. So the one finding they raise names
	 * BOTH records: each is a record of the fact it states, and keeping only the surviving spelling's
	 * would make the click-through depend on the order {@code PatientService} returned them in — the
	 * dependence that merge exists to remove.
	 *
	 * <p>Paracetamol rather than the Ibuprofen entry the other cases use, and the reason is the fold:
	 * the seed files a self-named {@code ibuprofen} allergy rule, which #146 keys onto the same
	 * SUBSTANCE as the allergen arm's chip and which wins that key — so an ibuprofen arrangement would
	 * assert about the curated-rule arm's sentence rather than the allergen arm's merge. The seed's
	 * {@code paracetamol} rule token reaches neither {@code acetaminophen} nor {@code panadol} by
	 * containment, so here the allergen arm's chip is the one that survives.
	 */
	@Test
	public void oneFindingRaisedByTwoRecordsOfOneAllergyNamesBoth() {
		String first = recordAllergyTo("Acetaminophen");
		String second = recordCodedAllergyTo("Panadol");

		PatientChart injected = inject(DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.allergyRecord(1, first, "Allergy: Acetaminophen (drug)"),
				DrugReferenceTestSupport.allergyRecord(2, second, "Allergy: Panadol (drug)")),
				"Can I give paracetamol?");

		RecordMapping finding = contraindicationFinding(injected);
		assertTrue(finding.getText().contains("recorded allergy to Paracetamol"),
				"the premise: the ALLERGEN arm's sentence is the one that survived the fold, so this "
						+ "case is about its merge and not about the curated-rule arm. Was: "
						+ finding.getText());
		assertEquals(Arrays.asList(Integer.valueOf(1), Integer.valueOf(2)), finding.getDerivedFrom(),
				"both records are records of the allergy this finding states, so both are named. Was: "
						+ finding.getDerivedFrom());
	}

	/** The chart's own records name no provenance: an allergy record IS the allergy, so there is
	 *  nothing behind it, and only the records this module injects can carry a derivation. */
	@Test
	public void aChartRecordNamesNoProvenanceOfItsOwn() {
		String allergyUuid = recordAllergyTo("Ibuprofen");

		PatientChart injected = inject(DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.allergyRecord(1, allergyUuid, "Allergy: Ibuprofen (drug)")));

		for (RecordMapping mapping : injected.getMappings()) {
			assertNotNull(mapping.getDerivedFrom(), mapping.getResourceType()
					+ ": the list is never null, so no reader branches on absence");
			if (!ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(mapping.getResourceType())) {
				assertTrue(mapping.getDerivedFrom().isEmpty(), mapping.getResourceType()
						+ " must name no provenance — only an injected finding is derived from a chart "
						+ "record. Was: " + mapping.getDerivedFrom());
			}
		}
	}
}
