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
 * <p>All scenarios run the real pipeline: real datasets — the DDInter excerpt and the curated
 * seed, plus {@link #PAIR_FIXTURE} for shapes neither carries — parsed by the real sources, real
 * {@code validate}/{@code injectRecords} overloads, GP reads on their no-context defaults.
 */
public class DrugSafetyQuestionPairInteractionTest {

	private static final String PAIR_QUESTION = "Does warfarin interact with aspirin?";

	/** Shapes the DDInter excerpt cannot express — route variants sharing a match token, a
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

	/** The real parsed entry for {@code name}, from the same DDInter excerpt this class's validator runs
	 *  over (real parser, real data) — never the bundled default, which since ADR Decision 36 is the whole
	 *  knowledge base and would make every premise here a statement about data the assertions never see. */
	private static DrugReference ddinterEntry(String name) {
		return DrugReferenceTestSupport.row(DrugReferenceTestSupport.ddinterEntries(), name);
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
				"precondition: the excerpt rates simvastatin x aspirin Unknown, i.e. below the default floor");

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
		DrugReferenceService curated = DrugReferenceTestSupport.curatedService();
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

		// The chip's severity word comes from the NOTE, which the ddinter parser prefixes with the
		// rating; the rating FIELD is what the floor and the most-severe-first ordering read. Both are
		// asserted, so deleting either from the fixture fails here rather than passing quietly.
		assertEquals("Major", fixture.findByQuery("voxelotor").get(0).getInteractions().get(0)
				.getSeverity(), "precondition: the surviving row's rating field must be Major");
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

	/** Every drug in the DDInter excerpt, named in one question — the polypharmacy-review shape
	 *  a clinician can type in one line, and the arm's worst case, since pairs grow as N²/2. */
	private static final String POLYPHARMACY_QUESTION = "Reviewing polypharmacy: lisinopril, metformin,"
			+ " methotrexate, omeprazole, sertraline, simvastatin, spironolactone, tramadol, warfarin,"
			+ " aspirin, ciprofloxacin, clarithromycin, digoxin, fluconazole, amiodarone and ibuprofen"
			+ " — any interactions?";

	/**
	 * The rank the arm ordered this chip on — {@link SafetyWarning#getSeverity()} put through the one
	 * definition of that ordering, {@code severityPriority}.
	 *
	 * <p>Issue #207: this used to read the severity WORD out of the rendered detail, locating it by
	 * {@code indexOf("also named in the question — ")}, and returned {@code -1} when it could not find
	 * that clause — a sentinel the ordering loop skipped. So rewording one clause of clinician-facing
	 * prose made the helper answer "no opinion" for EVERY chip and
	 * {@link #thePairChipsAreOrderedBySeverityAndBounded} passed while asserting nothing. Measured on
	 * {@code ae09928}: with the sort removed the case fails; with the clause reworded to "also mentioned
	 * in the question" it passes, and it passes with BOTH mutations applied at once. The clause is chip
	 * text and chip text is reworded routinely (#182, #188, #192, #198, #205, #210 all changed
	 * neighbouring strings), so the trigger was a normal action rather than a hypothetical.
	 *
	 * <p>Now it reads the structured rating the chip carries, so no prose is parsed, and it FAILS rather
	 * than returning a sentinel when it cannot classify one: a pair chip built from a rated rule that
	 * arrives with no rating means the ordering key was not carried, which is precisely the state in
	 * which the ordering assertion below would otherwise silently stop asserting. Every chip this arm
	 * raises from the DDInter excerpt is rule-rated — the sample assigns every row one of the
	 * four ratings — so an absent rating here is never the legitimate unrated case.
	 */
	private static int chipSeverityRank(SafetyWarning warning) {
		if (warning.getSeverity() == null) {
			throw new AssertionError("this chip carries no severity, so the ordering it was sorted on is "
					+ "not observable and the assertion below would skip it (issue #207): " + warning);
		}
		if (!Arrays.asList("Major", "Moderate", "Minor", "Unknown").contains(warning.getSeverity())) {
			throw new AssertionError("unrecognized severity '" + warning.getSeverity() + "' — severityPriority "
					+ "ranks it above Major as an unrated rule, which for a DDInter-rated chip means the "
					+ "rating was mangled rather than absent: " + warning);
		}
		return DrugSafetyValidator.severityPriority(warning.getSeverity());
	}

	@Test
	public void twoDrugsThatNoRuleNamesStayTwoDrugs() throws IOException {
		// The key-name fallback, which is what a drug keys on when no rule on any other named drug's
		// entry names it. Reachable only in one-directional data — the bundled curated seed's exact
		// shape, where Ibuprofen and Paracetamol each carry a rule against warfarin and warfarin is not
		// an entry at all — and only for the unnamed side of a pair, since a pair needs a joining rule
		// and that rule names the OTHER side. Two such drugs therefore never pair with each other, but
		// they do both pair with the drug they name, and if the fallback did not distinguish them those
		// two pairs would collapse into one and a real interaction would be dropped silently. Nothing
		// pinned it: replacing the fallback with a constant left the whole package green.
		DrugReferenceService fixture = pairFixtureService();
		String question = "Can ibuprofen or paracetamol be given with warfarin?";
		DrugReference warfarin = fixture.findByQuery("warfarin").get(0);
		assertTrue(warfarin.getInteractions().isEmpty(),
				"precondition: the named drug must carry no rules of its own, so both others fall back");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(fixture).validate(
				"Both raise bleeding risk with anticoagulants.", question, patientOnNeitherDrug());

		assertEquals(2, interactionChips(warnings),
				"two different drugs interacting with the same third drug are two pairs, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Ibuprofen", "Warfarin"), "ibuprofen x warfarin must be reported: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Paracetamol", "Warfarin"), "paracetamol x warfarin must be reported: " + warnings);
		// Two chips from one question also exercise the ordering, and here the RATING FIELD is what
		// orders them — the fixture's Major row is second in dataset order, so dataset order alone would
		// put the Moderate first.
		assertEquals("Ibuprofen", warnings.get(0).getDrug(),
				"the more severe pair must lead, was: " + warnings);
	}

	@Test
	public void aDrugTheANSWERNamesIsNotPairedWithTheQuestionsDrug() {
		// The arm's scoping decision, and its most safety-relevant one: it covers the drugs the
		// QUESTION resolved to, never the wider inPlay set the other arms use. Answer-named additions
		// are the model's word choice, and two drugs it names are as often alternatives ("ibuprofen, or
		// warfarin if …") as a proposed combination, so pairing them asserts a co-administration risk
		// nobody proposed. It would also make the chip's own words false: ", also named in the
		// question" would be printed about a drug the question never mentioned. Nothing pinned this —
		// widening the arm to inPlay left the whole package green — so it is pinned here.
		String answer = "Ibuprofen is usually avoided in this situation; warfarin is often preferred.";
		List<DrugReference> named = DrugReferenceTestSupport.ddinterService().findByQuery(answer);
		assertTrue(named.stream().map(DrugReference::getName).collect(Collectors.toList())
				.containsAll(Arrays.asList("Warfarin", "Ibuprofen")),
				"precondition: the ANSWER must name both drugs of a pair the source rates above the"
						+ " floor (warfarin x ibuprofen is Major in the DDInter excerpt), else this proves"
						+ " nothing: " + named);

		List<SafetyWarning> warnings = ddinterValidator().validate(answer, "Is ibuprofen safe here?",
				patientOnNeitherDrug());

		assertTrue(warnings.isEmpty(),
				"only the QUESTION's drugs may be paired: the answer naming a second drug must not"
						+ " produce a pair chip about a combination nobody proposed, was: " + warnings);
	}

	@Test
	public void anInteractionChipCarriesTheRatingItWasOrderedOn() {
		// The property the ordering case above rests on (issue #207). Both arms sort their chips by the
		// source's rating and then dropped it, leaving the rendered prose as the only trace — so a chip
		// that stopped carrying it would make the ordering assertion untestable except by parsing
		// clinician-facing text. Asserted per arm, because they build their chips at different sites: the
		// question-pair arm and the active-order arm.
		SafetyWarning pairChip = ddinterValidator()
				.validate(ABSTAINING_ANSWER, PAIR_QUESTION, patientOnNeitherDrug()).get(0);
		assertTrue(pairChip.getDetail().contains("named in the question"),
				"precondition: the pair arm's chip, was: " + pairChip);
		assertEquals("Major", pairChip.getSeverity(),
				"the pair chip must carry the source's rating for warfarin x aspirin, was: " + pairChip);

		SafetyWarning orderChip = ddinterValidator().validate(
				"Warfarin and aspirin together increase bleeding risk.", PAIR_QUESTION,
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Aspirin 81mg"),
						null, null, null)).get(0);
		assertTrue(orderChip.getDetail().contains("active order"),
				"precondition: the active-order arm's chip, was: " + orderChip);
		assertEquals("Major", orderChip.getSeverity(),
				"and so must the active-order chip for the same pair, was: " + orderChip);
	}

	@Test
	public void thePairChipsAreOrderedBySeverityAndBounded() {
		// The arm's output is question-controlled and grows as N²/2, while every chip is also an
		// injected pre-answer finding. Before this bound, the question below raised 72 chips carrying
		// 42,708 characters of finding text into the prompt — for a path whose per-entry reference
		// record is capped at 1500 characters for exactly this reason. Unbounded is one half; unordered
		// is the other, since appending in dataset order let a Minor lead several Majors, the inverse
		// of the severity-first rule the promoted notes follow over the very same rows.
		List<SafetyWarning> warnings = ddinterValidator().validate("", POLYPHARMACY_QUESTION,
				patientOnNeitherDrug());

		assertTrue(interactionChips(warnings) <= 10,
				"the pair arm must bound what one question can raise, was " + interactionChips(warnings)
						+ " chips: " + warnings);
		// Precondition on the INSTRUMENT, not on the arm: an ordering assertion over fewer than two chips
		// is satisfied by anything, so the case has to know it received a list worth ordering (issue #207 —
		// the same class of vacuity the helper above carried).
		assertTrue(interactionChips(warnings) >= 2,
				"precondition: this question must raise several chips, or there is no ordering to assert,"
						+ " was: " + warnings);
		int previous = Integer.MAX_VALUE;
		for (SafetyWarning warning : warnings) {
			// No skip: chipSeverityRank now throws rather than returning a sentinel, so a chip this case
			// cannot classify fails it instead of passing through it.
			int rank = chipSeverityRank(warning);
			assertTrue(rank <= previous,
					"pair chips must be ordered most-severe first, was: " + warnings);
			previous = rank;
		}
		assertEquals(3, DrugSafetyValidator.severityPriority("Major"),
				"precondition: this test's ordering check reads the very ranking the arm sorts on —"
						+ " severityPriority, which #121 made the one definition of it");
	}

	@Test
	public void theInjectedPairFindingsAreBoundedToo() {
		// The measured harm is on the prompt side, so it is asserted there too, through the real
		// injector: every chip becomes its own citable record, so an unbounded chip list is an
		// unbounded question-controlled prompt expansion.
		DrugReferenceService service = DrugReferenceTestSupport.ddinterService();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		PatientChart result = injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				patientOnNeitherDrug(), POLYPHARMACY_QUESTION);

		int findings = 0;
		int findingChars = 0;
		for (RecordMapping mapping : result.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(mapping.getResourceType())) {
				findings++;
				findingChars += mapping.getText().length();
			}
		}
		assertTrue(findings <= 10,
				"a question naming N drugs must not inject N²/2 findings, was " + findings);
		assertTrue(findingChars <= 12000,
				"nor an unbounded number of characters, was " + findingChars + " over " + findings
						+ " finding record(s)");
	}

	@Test
	public void everyDistinctPairAmongThreeNamedDrugsIsReported() {
		// The other edge of the one-chip-per-pair grouping: it must collapse only what IS one pair.
		// Keying pairs on the drugs' match tokens is a de-duplication inside a safety net, so its
		// failure direction is the dangerous one — a key too coarse drops a real interaction silently
		// — and this pins it against real data: three drugs named in one question, three above-floor
		// pairs among them in the DDInter excerpt, three chips.
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
