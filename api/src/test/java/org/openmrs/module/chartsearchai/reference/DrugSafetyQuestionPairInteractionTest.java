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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
 * <p>All scenarios run the real pipeline: real datasets — the bundled DDInter sample and the curated
 * seed, plus {@link #PAIR_FIXTURE} for shapes neither carries — parsed by the real sources, real
 * {@code validate}/{@code injectRecords} overloads, GP reads on their no-context defaults.
 */
public class DrugSafetyQuestionPairInteractionTest {

	private static final String PAIR_QUESTION = "Does warfarin interact with aspirin?";

	/** Shapes the bundled DDInter sample cannot express — route variants sharing a match token, a
	 *  pair joined by two differently-tokened rules, an ATC-only rule — each a miniature of a shape
	 *  the full KB carries; see the fixture's own description. */
	private static final String PAIR_FIXTURE = "chartsearchai-test/drug-reference-question-pairs.json";

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

	/** A service over {@link #PAIR_FIXTURE}, parsed by the real {@link JsonDrugReferenceSource}. */
	private static DrugReferenceService pairFixtureService() throws IOException {
		return DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(PAIR_FIXTURE));
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

	@Test
	public void routeVariantEntriesSharingAMatchTokenAreReportedAsOnePair() throws IOException {
		// One chip per clinical PAIR, not per pair of ENTRIES. DDInter carries a separate entry per
		// route variant and every variant publishes the same rxnorm_name — which is the token its
		// rules match on — so ONE question word resolves several entries that all pair off the same
		// rule. Ungrouped that is N near-identical chips, and N near-identical injected findings, for
		// a single clinical fact: issue #115's shape arriving on the question side.
		DrugReferenceService fixture = pairFixtureService();
		String question = "Does voxelotor interact with dexamethasone?";
		List<String> resolved = fixture.findByQuery(question).stream().map(DrugReference::getName)
				.collect(Collectors.toList());
		assertEquals(Arrays.asList("Dexamethasone", "Dexamethasone (nasal)", "Voxelotor"), resolved,
				"precondition: the one word 'dexamethasone' must resolve BOTH variant entries alongside"
						+ " voxelotor — that is what multiplies the pairs");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(fixture).validate(
				"The records do not address this combination.", question, patientOnNeitherDrug());

		assertEquals(1, interactionChips(warnings),
				"route variants of one drug are one drug for this arm, so the pair must be reported"
						+ " once, not once per variant entry, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Dexamethasone", "Voxelotor", "named in the question"),
				"the surviving chip is the dataset's first variant, naming the pair asked about, was: "
						+ warnings);
	}

	@Test
	public void aMultiWordRuleTokenDoesNotNameEveryDrugCalledAfterOneOfItsWords() throws IOException {
		// A rule's token and an entry's alias are both canonical reference names, so "does this rule
		// name that entry?" is a question about NAME IDENTITY. Asking it with the prose matcher — the
		// token as haystack, the entry's aliases as needles — makes "ethinyl estradiol" name the
		// separate Estradiol entry, because word boundaries stop a name nested inside a WORD
		// (chlorothiazide in hydrochlorothiazide) but not one nested inside a PHRASE. The chip then
		// states an interaction the knowledge base does not contain, against a drug whose row it read
		// from a different drug — and preAnswerFindings injects that same string as a citable record,
		// so the model is handed a fabricated interaction as evidence it is designed to report.
		DrugReferenceService fixture = pairFixtureService();
		DrugReference levofloxacin = fixture.findByQuery("levofloxacin").get(0);
		assertEquals("ethinyl estradiol", levofloxacin.getInteractions().get(0).getToken(),
				"precondition: the only rule must be against ethinyl estradiol, a different drug");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(fixture).validate(
				"The records do not address this combination.",
				"Does levofloxacin interact with estradiol?", patientOnNeitherDrug());

		assertTrue(warnings.isEmpty(),
				"no rule joins levofloxacin and estradiol, so nothing may be raised — a chip here names"
						+ " an interaction the knowledge base does not carry, was: " + warnings);
	}

	@Test
	public void theRealMultiWordPairIsStillReportedWhenTheQuestionNamesIt() throws IOException {
		// The other edge of the previous test: tightening name identity must not stop a genuine
		// multi-word token from naming its own partner. The same fixture, the same rule, the question
		// naming ethinyl estradiol itself — which also resolves the Estradiol entry, since "estradiol"
		// is a whole word of the question too, so this pins that exactly one of the two is reported.
		DrugReferenceService fixture = pairFixtureService();
		String question = "Does levofloxacin interact with ethinyl estradiol?";
		assertTrue(fixture.findByQuery(question).stream().map(DrugReference::getName)
				.collect(Collectors.toList()).containsAll(Arrays.asList("Ethinyl estradiol", "Estradiol")),
				"precondition: the question must resolve BOTH the real partner and the similarly named"
						+ " drug, else this proves nothing");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(fixture).validate(
				"The records do not address this combination.", question, patientOnNeitherDrug());

		assertEquals(1, interactionChips(warnings),
				"the pair the rule really carries must still be reported, exactly once, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Levofloxacin", "Ethinyl estradiol", "Moderate"),
				"and must name the partner the rule actually points at, was: " + warnings);
	}

	@Test
	public void aQuestionNamingOneDrugRaisesNoPairChipForItsOwnRouteVariant() throws IOException {
		// One drug is not a pair, however many entries it resolves to. The size(inPlay) < 2 guard
		// counts ENTRIES, so a single question word that the KB carries route variants of clears it,
		// and the token-keyed grouping cannot collapse the result either — a self-pair's key has the
		// same name on both sides, which is unique. The full KB has 33 above-floor rows joining two
		// entries that share one match token, so a question naming one of those 29 drugs gets a chip
		// about a combination the clinician never proposed (issue #105's over-reach), and for a drug
		// whose variants are named after DIFFERENT substances the chip names two drugs the question
		// never mentioned at all — the #86 failure mode this arm's own wording exists to avoid.
		DrugReferenceService fixture = pairFixtureService();
		List<String> resolved = fixture.findByQuery("Is minoxidil safe for this patient?").stream()
				.map(DrugReference::getName).collect(Collectors.toList());
		assertEquals(Arrays.asList("Minoxidil", "Minoxidil (topical)"), resolved,
				"precondition: one drug word must resolve two entries, and each must carry the row that"
						+ " joins them — that is what clears the two-drugs guard");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(fixture).validate(
				"Minoxidil is used for hypertension and for androgenetic alopecia.",
				"Is minoxidil safe for this patient?", patientOnNeitherDrug());

		assertTrue(warnings.isEmpty(),
				"a question naming ONE drug must raise no pair chip, was: " + warnings);
	}

	/** The variant family whose sibling row is sub-floor, so the two entry pairs of ONE clinical pair
	 *  reach the arm carrying different rule sets — the shape both findings below turn on. */
	private static final String VARIANT_PAIR_QUESTION = "Does diclofenac interact with aspirin?";

	@Test
	public void aPairIsKeyedInTheReferenceDataVocabularyOnBothSides() throws IOException {
		// A drug must key the same way in every pair it appears in, or one clinical pair gets two keys
		// and escapes the grouping. It did: the key name came from the OTHER side's rule token when
		// there was one and from displayLabel() when there was not — two vocabularies, so the same
		// entry keyed as "aspirin" against Diclofenac (whose row names it) and as "Acetylsalicylic acid
		// (aspirin)" against Diclofenac (topical) (whose row is sub-floor, leaving nothing to name it
		// with). Two chips, no patient data needed. And an extra chip is an extra injected pre-answer
		// finding, which is the outcome the keying exists to prevent.
		DrugReferenceService fixture = pairFixtureService();
		DrugReference asa = fixture.findByQuery("acetylsalicylic acid").get(0);
		assertEquals("Acetylsalicylic acid (aspirin)", asa.displayLabel(),
				"precondition: this entry's display label must diverge from the token the rules use for"
						+ " it, else the two vocabularies cannot be told apart");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(fixture).validate(
				"The records do not address this combination.", VARIANT_PAIR_QUESTION,
				patientOnNeitherDrug());

		assertEquals(1, interactionChips(warnings),
				"one clinical pair, one chip, however the reference data names each side, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Acetylsalicylic acid (aspirin)", "Diclofenac", "named in the question"),
				"and it must still name the pair asked about, was: " + warnings);
	}

	@Test
	public void aChartOwnedPairSuppressesItsRouteSiblingToo() throws IOException {
		// Chart precedence is a verdict about the PAIR, so it has to be reached over every entry pair
		// that is that pair. It was reached per entry pair and taken as an early return, so the
		// chart-owned pair (Acetylsalicylic acid, Diclofenac) left no trace and its sibling
		// (Acetylsalicylic acid, Diclofenac (topical)) — whose own row is sub-floor, so nothing on that
		// side matched the active order — concluded the chart did not own it and chipped. One clinical
		// pair in two voices, which #435 forbids: the patient's own medication became the subject of a
		// sentence that reads as a reference lookup, carrying the systemic row's Major mechanism
		// attributed to the topical variant whose own row is Unknown.
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(pairFixtureService()).validate(
				"Aspirin and diclofenac together raise bleeding risk.", VARIANT_PAIR_QUESTION,
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin 81mg"),
						null, null, null));

		assertEquals(1, interactionChips(warnings),
				"the pair the chart already owns must be reported exactly once, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Diclofenac", "active order aspirin"),
				"and the surviving chip must be the patient-specific one, was: " + warnings);
		for (SafetyWarning warning : warnings) {
			assertFalse(warning.getDetail().contains("named in the question"),
					"no route sibling of a chart-owned pair may be reported as a reference lookup: "
							+ warning);
		}
	}

	@Test
	public void everyDistinctPairAmongThreeNamedDrugsIsReported() {
		// The other edge of the one-chip-per-pair grouping: it must collapse only what IS one pair.
		// Keying pairs on the drugs' match tokens is a de-duplication inside a safety net, so its
		// failure direction is the dangerous one — a key too coarse drops a real interaction silently
		// — and this pins it against real data: three drugs named in one question, three above-floor
		// pairs among them in the bundled sample, three chips.
		List<SafetyWarning> warnings = ddinterValidator().validate(
				"These are commonly co-prescribed.",
				"Is it safe to combine lisinopril, spironolactone and ibuprofen?", patientOnNeitherDrug());

		assertEquals(3, interactionChips(warnings),
				"each of the three pairs among the named drugs must raise its own chip, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Lisinopril", "Spironolactone", "Major"), "lisinopril x spironolactone: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Lisinopril", "Ibuprofen", "Moderate"), "lisinopril x ibuprofen: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Spironolactone", "Ibuprofen", "Moderate"), "spironolactone x ibuprofen: " + warnings);
	}

	@Test
	public void aPairAlsoJoinedByARuleNamingAnActiveOrderStaysWithTheActiveOrderArm() throws IOException {
		// Chart precedence has to be decided over EVERY rule joining the pair, not just the one this
		// arm would chip: addInteractions walks all of an entry's rules, so when two rules join one
		// pair under different tokens — a brand-name row and an INN row — and only the second names
		// the patient's order, consulting the first alone concludes "the chart does not own this" and
		// the pair is reported twice, once as a fact about the patient and once as a reference lookup.
		DrugReferenceService fixture = pairFixtureService();
		DrugReference ibuprofen = fixture.findByQuery("ibuprofen").get(0);
		assertEquals(Arrays.asList("coumadin", "warfarin"), ibuprofen.getInteractions().stream()
				.map(DrugReference.Interaction::getToken).collect(Collectors.toList()),
				"precondition: two rules must join this pair, and the FIRST must name the partner by a"
						+ " token the active order's name does NOT carry");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(fixture).validate(
				"Both are commonly prescribed.", "Can we give ibuprofen with warfarin?",
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Warfarin 5mg"),
						null, null, null));

		assertEquals(1, interactionChips(warnings),
				"the pair must be reported exactly once however many rules join it, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Ibuprofen", "active order warfarin"),
				"the surviving chip must be the patient-specific active-order one, was: " + warnings);
		for (SafetyWarning warning : warnings) {
			assertFalse(warning.getDetail().contains("named in the question"),
					"a pair the chart arm already owns must not also be a reference lookup: " + warning);
		}
	}

	@Test
	public void aQuestionNamedPairIdentifiedOnlyByAtcCodeIsReported() throws IOException {
		// The ATC arm of the rule-names-that-entry check, which the ddinter source never exercises
		// because it always writes a name token: a curated rule may carry only the partner's ATC
		// code, and the pair must still be found — otherwise the arm silently covers one of the two
		// ways the reference data names a partner.
		DrugReferenceService fixture = pairFixtureService();
		DrugReference miconazole = fixture.findByQuery("miconazole").get(0);
		assertNotNull(miconazole.getInteractions().get(0).getAtc(),
				"precondition: the rule must identify its partner by ATC code");
		assertNull(miconazole.getInteractions().get(0).getToken(),
				"precondition: and carry no name token, so only the ATC arm can match");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(fixture).validate(
				"The records do not address this combination.",
				"Is miconazole safe with phenprocoumon?", patientOnNeitherDrug());

		assertEquals(1, interactionChips(warnings),
				"a pair joined only by an ATC code must raise its chip, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Miconazole", "Phenprocoumon", "named in the question", "Major"),
				"and must name the partner ENTRY, not the bare code, was: " + warnings);
	}

	@Test
	public void patientSpecificChipsLeadThePairChip() {
		// Mary Smith's measured shape (active order Simvastatin 20mg, asked about warfarin x aspirin):
		// the chip list must open with the fact about HER — the arm runs last precisely so a reference
		// lookup about a pair she may be on neither of never outranks her own chart.
		List<SafetyWarning> warnings = ddinterValidator().validate(ABSTAINING_ANSWER, PAIR_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Simvastatin 20mg"), null, null, null));

		assertEquals(2, warnings.size(),
				"precondition: her active order and the question's pair must both be raised, was: "
						+ warnings);
		assertTrue(warnings.get(0).getDetail().contains("active order simvastatin"),
				"the patient-specific chip must lead, was: " + warnings);
		assertTrue(warnings.get(1).getDetail().contains("named in the question"),
				"and the question-pair lookup must follow it, was: " + warnings);
	}
}
