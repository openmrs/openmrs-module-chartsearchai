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

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.api.impl.QueryScopeRouter;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * The generic DDI screening question — "are there any drug interactions with her current
 * medications?" — screened against the patient's OWN active orders (issue #113).
 *
 * <p>Both existing arms key off drugs the QUESTION names, and a screening question names none:
 * {@code DrugReferenceInjector.matchingEntries} scopes order-driven injection through
 * {@code relatedToAny}, which is false for an empty question-drug list, and
 * {@link DrugSafetyValidator#validate}'s {@code inPlay} then starts empty. So the module produced
 * 0 chips, {@code references: []} and "the records do not address drug interactions with current
 * medications" for the one question a DDI knowledge base is chiefly wanted for. Nothing needed
 * retrieving — the active orders are in {@link PatientClinicalContext} and each order's
 * {@link DrugReference} carries its full partner list; the two only needed cross-referencing.
 *
 * <p>Every scenario runs the REAL production path: the real bundled datasets parsed by the real
 * sources, the real {@code validate}/{@code injectRecords} entry points, real question strings, GP
 * reads on their no-context defaults (severity floor {@code minor}).
 */
public class DrugSafetyInteractionScreeningTest {

	/** The canonical screening question from the issue, verbatim. */
	private static final String SCREENING_QUESTION = DrugReferenceTestSupport.SCREENING_QUESTION;

	private static DrugSafetyValidator ddinterValidator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService());
	}

	private static DrugSafetyValidator curatedValidator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());
	}

	/** Validator over the substring-shape fixture, parsed by the real {@link DdiDrugReferenceSource}. */
	private static DrugSafetyValidator selfWitnessValidator() throws Exception {
		return fixtureValidator("ddi-screening-self-witness.json");
	}

	/** Validator over a test-classpath DDInter-shaped fixture, parsed by the real
	 *  {@link DdiDrugReferenceSource} through the shared loader — no hand-built entries anywhere. */
	private static DrugSafetyValidator fixtureValidator(String fixture) throws Exception {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.ddiFixtureEntries("chartsearchai-test/" + fixture)));
	}

	/**
	 * The real injector wired to the real validator over the same service, composed from the shared
	 * factories — the arrangement {@code preAnswerFindings} needs to turn a screening finding into a
	 * citable record.
	 */
	private static DrugReferenceInjector screeningInjector() {
		DrugReferenceService service = DrugReferenceTestSupport.ddinterService();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));
		return injector;
	}

	/** Two mutually interacting active orders: Simvastatin x Clarithromycin is Major in the real
	 *  bundled DDInter sample, and the two share no ATC subgroup, so the rule arm is the only thing
	 *  that can produce a warning here. */
	private static PatientClinicalContext interactingPairContext() {
		return DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Simvastatin", "Clarithromycin"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null);
	}

	/** The empty answer is the PRE-answer production shape: it is exactly how
	 *  {@code DrugReferenceInjector.preAnswerFindings} calls the validator, so the drugs in play are
	 *  only those the question names (here: none) and no answer-named drug can contribute a chip. */
	private static List<SafetyWarning> screen(DrugSafetyValidator validator, String question,
			PatientClinicalContext context) {
		return validator.validate("", question, context);
	}

	@Test
	public void theSharedScreeningQuestionIsStillClassifiedAsScreening() {
		// Issue #153, asserted where it can fail. The string above is shared by ten test files, and which
		// arm it reaches is decided by isInteractionScreening — so a change to that classifier decides
		// whether ten files are testing the screening arm or something else.
		//
		// What this adds over what those ten files already give, measured rather than assumed: rewording
		// nine of the ten copies one file at a time (see DrugReferenceTestSupport.SCREENING_QUESTION for
		// the exact mutation and why the tenth was not measured) turned eight of the nine red, so the
		// condition was NOT unprotected. Two things it adds. It names the cause — eight files failing on
		// chip counts do not say "the classifier changed" — and it covers the one file that stayed GREEN,
		// DuplicateInteractionChipTest, whose assertion the drug-in-play arm satisfies too. Asked of the
		// production predicate directly, so there is no chip count in between.
		assertTrue(QueryScopeRouter.isInteractionScreening(DrugReferenceTestSupport.SCREENING_QUESTION),
				"the shared screening question must still be classified as interaction screening, or the "
						+ "ten files that use it are no longer testing the screening arm: "
						+ DrugReferenceTestSupport.SCREENING_QUESTION);
		// The other half of the same contract: it must name no reference drug. Both halves are what makes
		// the arm reachable — a question that names a drug is handled by the drug-in-play arm instead
		// (DrugSafetyValidator.validate stands the screen down as soon as inPlay is non-empty), so a
		// screening question that started resolving an entry would silently move every one of those files
		// onto the other arm without changing the classifier at all.
		assertTrue(DrugReferenceTestSupport.ddinterService()
				.findImpliedByQuery(DrugReferenceTestSupport.SCREENING_QUESTION).isEmpty(),
				"and it must still name no reference drug, or the screening arm never runs for it: "
						+ DrugReferenceTestSupport.names(DrugReferenceTestSupport.ddinterService()
								.findImpliedByQuery(DrugReferenceTestSupport.SCREENING_QUESTION)));
	}

	@Test
	public void screeningQuestionNamingNoDrugChipsTheInteractingActiveOrderPair() {
		// The gap itself. Measured on the 3.7.1 standalone at HEAD 13690b1: this question produced
		// 0 chips and references: [] while "is it safe to give her clarithromycin?" — same patient,
		// same KB, same request path — produced the Major chip. Naming a candidate drug worked;
		// asking to be screened did not.
		List<SafetyWarning> warnings = screen(ddinterValidator(), SCREENING_QUESTION,
				interactingPairContext());

		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Simvastatin", "clarithromycin", "Major"),
				"a screening question must raise the real Major pair among the patient's own active "
						+ "orders, was: " + warnings);
	}

	@Test
	public void aPairIsChippedOnceEvenThoughBothEntriesCarryTheRow() {
		// DDInter's interaction rows are symmetric — DdiDrugReferenceSource expands each row onto
		// BOTH drugs' entries, which is what the validator's from-either-side matching relies on.
		// Run pairwise across the order list that means one pair is reached twice (simvastatin's row
		// against clarithromycin, and clarithromycin's row against simvastatin), so without an
		// unordered-pair key the screening arm emits two chips saying the same thing in opposite
		// directions. That doubling is created by this arm, so it is this arm's to collapse (it is
		// not the cross-arm duplication of #88 or the route-variant duplication of #115).
		List<SafetyWarning> warnings = screen(ddinterValidator(), SCREENING_QUESTION,
				interactingPairContext());

		assertEquals(1, warnings.size(),
				"one interacting pair must yield exactly one chip, not one per direction, was: "
						+ warnings);
	}

	@Test
	public void thePostAnswerChipsPassScreensToo() {
		// The chips the clinician actually sees come from the POST-answer pass, and its gate must be
		// the same as the pre-answer findings pass — a gate that could differ between them is how
		// prose asserting an interaction ends up with no chip beside it. The answer text here names no
		// drug on purpose, so the screening arm is the only thing that can chip and the assertion
		// cannot be satisfied by the answer-driven arm that already worked.
		List<SafetyWarning> warnings = ddinterValidator().validate(
				"Two of the current medications interact at a major level; review the statin and the "
						+ "macrolide.",
				SCREENING_QUESTION, interactingPairContext());

		assertEquals(1, warnings.size(), "the post-answer pass must screen as well, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Simvastatin", "clarithromycin", "Major"),
				"and it must be the same pair the pre-answer pass found, was: " + warnings);
	}

	@Test
	public void aShorterScreeningPhrasingAlsoTriggers() {
		// The trigger is a cue pair — an interact* word plus the router's MEDICATIONS intent — not a
		// fixed sentence, so the everyday short form works too. "meds" is one of the router's own
		// medication cues, which is the reason this predicate reuses that classification instead of
		// carrying a second drug vocabulary.
		List<SafetyWarning> warnings = screen(ddinterValidator(), "Do any of her meds interact?",
				interactingPairContext());

		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Simvastatin", "clarithromycin", "Major"),
				"a short screening phrasing must screen too, was: " + warnings);
	}

	@Test
	public void activeOrdersThatDoNotInteractRaiseNothing() {
		// THE critical negative: this must not become a chip generator. Both active orders are real
		// entries in the bundled curated dataset (Paracetamol N02BE01, Amoxicillin J01CA04) and both
		// carry interaction rules — paracetamol x warfarin, amoxicillin x methotrexate — so the
		// partner lists are non-empty and are genuinely searched. Neither names the other, and the
		// patient is on nothing else, so the correct output is silence.
		List<SafetyWarning> warnings = screen(curatedValidator(), SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Paracetamol", "Amoxicillin"),
						DrugReferenceTestSupport.set("N02BE01", "J01CA04"), null, null));

		assertTrue(warnings.isEmpty(),
				"a screening question on active orders with no interaction between them must raise "
						+ "nothing, was: " + warnings);
	}

	@Test
	public void aSubFloorActiveOrderPairRaisesNothing() {
		// The severity floor applies exactly as it does to the question-driven arm — no second
		// definition of the floor, and no route around a decision the chip path already enforces.
		// Simvastatin x Spironolactone is a real Unknown-severity row with no mechanism text (14% of
		// the full KB is that shape), so the join matches and only the floor stops it.
		List<SafetyWarning> warnings = screen(ddinterValidator(), SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Simvastatin", "Spironolactone"),
						DrugReferenceTestSupport.set("C10AA01", "C03DA01"), null, null));

		assertTrue(warnings.isEmpty(),
				"an Unknown-severity pair sits below the default floor and must raise nothing, was: "
						+ warnings);
	}

	@Test
	public void anUnratedCuratedRuleIsScreenedAndSurvivesAPartnerWithNoEntryOfItsOwn() {
		// Two properties in one real arrangement. (1) A hand-authored curated rule carries no
		// severity, and unrated is not low-rated — it is exempt from the floor here exactly as it is
		// in the question-driven arm. (2) The partner need not have a reference entry of its own:
		// warfarin is an active order but is absent from the curated dataset, so only one side of
		// the pair resolves to an entry. The join is hasActiveDrug, which reads the order NAME, so
		// the finding still stands and the chip still names the partner.
		List<SafetyWarning> warnings = screen(curatedValidator(), SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Paracetamol", "Warfarin"),
						DrugReferenceTestSupport.set("N02BE01", "B01AA03"), null, null));

		assertEquals(1, warnings.size(), "exactly one pair is on this chart, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Paracetamol", "warfarin"),
				"an unrated curated rule must be screened and must name the partner, was: " + warnings);
	}

	@Test
	public void aSingleActiveOrderHasNoPairToScreen() {
		// The issue's own repro patient (Mary Smith, one active order: Simvastatin 20mg). Honesty
		// pin: pairwise screening cannot help a patient with one medication — there is no pair — so
		// 0 chips stays the right answer for her, and the capability this adds needs at least two
		// interacting orders to show. Simvastatin carries 15 partners in the DDInter excerpt, none of
		// which this patient is on.
		List<SafetyWarning> warnings = screen(ddinterValidator(), SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Simvastatin"),
						DrugReferenceTestSupport.set("C10AA01"), null, null));

		assertTrue(warnings.isEmpty(),
				"one active order has no pair, so nothing may be raised, was: " + warnings);
	}

	@Test
	public void oneActiveOrderCannotPairWithItself() throws Exception {
		// The live shape from #115/#86, on a fixture parsed by the real DDInter parser: the rule token
		// "iron" sits inside "spironolactone", so hasActiveDrug's substring matching reports a patient
		// on spironolactone ALONE as also being on iron. The question-driven arm gets away with that —
		// a clinician who asked about a specific drug can see the chip is odd — but a screening
		// question has no anchor, so an unguarded pairwise pass would tell a clinician that a patient
		// on ONE medication has a Major interaction with a drug they are not taking. A pair needs two
		// orders, and screening must establish the second one itself.
		DrugSafetyValidator validator = selfWitnessValidator();
		List<SafetyWarning> warnings = screen(validator, SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Spironolactone 25mg", "Spironolactone"),
						DrugReferenceTestSupport.set("C03DA01"), null, null));

		assertTrue(warnings.isEmpty(),
				"a partner token matching only the subject's OWN order is not a pair, was: " + warnings);
	}

	@Test
	public void theSecondOrderBeingRealMakesItAPairAgain() throws Exception {
		// The other side of that guard, same fixture: once iron really is on the chart the pair is
		// genuine and must chip. Without this the guard could be satisfied by suppressing everything.
		List<SafetyWarning> warnings = screen(selfWitnessValidator(), SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Spironolactone 25mg", "Ferrous sulfate (iron) 200mg"),
						DrugReferenceTestSupport.set("C03DA01", "B03AA07"), null, null));

		assertEquals(1, warnings.size(), "exactly one real pair is on this chart, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Spironolactone", "iron", "Major"),
				"the real pair must still be screened, was: " + warnings);
	}

	@Test
	public void aCoFormulationOrderIsStillOneOrderAndNotAPair() {
		// The same "a pair needs two orders" property, reached through the ATC leg of the join rather
		// than the name leg above — the half the name filter cannot see. ONE active order for a real
		// fixed-dose combination ("Aspirin and omeprazole", Yosprala) resolves to BOTH constituents'
		// entries, because the order name whole-word-matches an alias of each; the order's own concept
		// carries the single mapped code A02BC05. Aspirin's rule against omeprazole is a real Minor row
		// in the DDInter excerpt, and it names omeprazole by that very ATC code — so a subject set that
		// only drops the SUBJECT's own codes leaves the code its co-formulated other half contributed
		// standing as if it were a second order, and the two halves of one tablet get reported as an
		// interacting pair (naming "esomeprazole", which the patient is on in no form at all).
		List<SafetyWarning> warnings = screen(ddinterValidator(), SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Aspirin and omeprazole 325/40mg"),
						DrugReferenceTestSupport.set("A02BC05"), null, null));

		assertTrue(warnings.isEmpty(),
				"one combination order is one order, so its constituents are not a pair, was: "
						+ warnings);
	}

	@Test
	public void anOrderTheDatasetOnlyKNOWSByItsAtcCodeCannotPairWithItself() throws Exception {
		// The ATC-resolved half of "a pair needs two orders", and the half a whole-word name filter
		// structurally cannot see. This order reaches the subject set through its ATC code ALONE — its
		// display name is a spelling the dataset's aliases do not cover, which is exactly the case the
		// ATC leg exists to serve (measured on the demo dictionary: the mapped concept `Torasemide`
		// against the KB's `Torsemide`). Because no order name resolves to the subject, no name can be
		// attributed to it, and hasActiveDrug's SUBSTRING matching (#86) then lets the subject's own
		// order name witness a partner sitting inside it: "iron" within "spironolactona". A patient on
		// ONE medication would be told it has a Major interaction with a drug they are not taking.
		List<SafetyWarning> warnings = screen(selfWitnessValidator(), SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Spironolactona 25mg"),
						DrugReferenceTestSupport.set("C03DA01"), null, null));

		assertTrue(warnings.isEmpty(),
				"an order the dataset knows only by ATC code cannot witness its own pair, was: "
						+ warnings);
	}

	@Test
	public void aCoFormulationOrderWithNoAtcCodesIsStillOneOrder() {
		// The NAME leg of the same one-order case, and the shape that still needs the guard after
		// #128. That change matches a rule token by WORD START rather than as a raw substring, which
		// closes the `iron` ⊂ `spironolactona` shape at the matcher — but a combination order's name
		// contains its second constituent AT a word start, so "Aspirin and omeprazole 325/40mg" still
		// answers `hasActiveDrug("aspirin")` for the omeprazole subject. With no ATC codes on the
		// order there is nothing else to witness the pair, so this is the guard on its own: remove it
		// and one tablet is reported as interacting with itself.
		List<SafetyWarning> warnings = screen(ddinterValidator(), SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Aspirin and omeprazole 325/40mg"), null, null, null));

		assertTrue(warnings.isEmpty(),
				"one combination order is one order however its pair is witnessed, was: " + warnings);
	}

	@Test
	public void perOrderStructureRecoversTheNameWitnessTheFlatFallbackHasToDiscard() throws Exception {
		// The same chart as the case above, plus a SECOND, genuine order — and the per-order structure
		// #124 put on the context (issue #118). Flattened, this pair is unreportable: nothing names the
		// spironolactone subject, so no name in the set can be told apart from its own order's and the
		// guard has to distrust them all. With the orders themselves on the context, the iron order is
		// attributable, stays whole, and witnesses the pair — while the subject's own order is still
		// removed, so the `iron` ⊂ `spironolactona` self-witness stays closed. Strictly more found for
		// strictly the same safety property; production always carries this structure
		// (PatientClinicalContextBuilder), so the flat path is the fallback, not the normal case.
		List<SafetyWarning> warnings = screen(selfWitnessValidator(), SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Spironolactona 25mg", "Ferrous sulfate (iron) 200mg"),
						DrugReferenceTestSupport.set("C03DA01"), null, null,
						java.util.Arrays.asList(
								new PatientClinicalContext.ActiveDrugOrder("order-1", "Spironolactona 25mg",
										DrugReferenceTestSupport.set("Spironolactona 25mg")),
								new PatientClinicalContext.ActiveDrugOrder("order-2",
										"Ferrous sulfate (iron) 200mg",
										DrugReferenceTestSupport.set("Ferrous sulfate (iron) 200mg")))));

		assertEquals(1, warnings.size(), "exactly one real pair is on this chart, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Spironolactone", "iron", "Major"),
				"an attributable second order must witness the pair, was: " + warnings);
	}

	@Test
	public void activeOrdersWithNoAtcCodesAtAllAreStillScreened() {
		// The shape production actually runs. On the 3.7.1 demo dictionary every order of every probe
		// patient carries no ATC map at all, so the name leg is the ONLY one that fires live and the
		// whole code half of the derived context is a no-op. Every other case here passes both a name
		// set and an ATC set, which would let a change to the subject-set union — or to the code
		// reduction — kill the live-firing leg with the suite still green.
		List<SafetyWarning> warnings = screen(ddinterValidator(), SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Simvastatin 20mg", "Clarithromycin 500mg"),
						null, null, null));

		assertEquals(1, warnings.size(), "exactly one pair is on this chart, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Simvastatin", "clarithromycin", "Major"),
				"a chart with names but no ATC codes must still be screened, was: " + warnings);
	}

	@Test
	public void aMultiWordPartnerTokenStillKeysThePairOnce() throws Exception {
		// "Each pair once" depends on both directions of a symmetric row resolving the partner to the
		// SAME entry. The rule token here is the multi-word generic "insulin glargine", and the
		// shorter entry "Insulin" carries the alias "insulin", which is a whole word of that token —
		// so a first-match scan hands back the wrong entry, the reverse direction keys differently,
		// and the one interaction is chipped twice in opposite directions (the withheld-pair log line
		// would name the wrong drug too). One order name resolves to both insulin entries, so no
		// second insulin order is needed to reach this. The most specific match has to win.
		List<SafetyWarning> warnings = screen(fixtureValidator("ddi-screening-multiword-partner.json"),
				SCREENING_QUESTION, DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Insulin glargine 20U", "Metoprolol 50mg"),
						null, null, null));

		assertEquals(1, warnings.size(),
				"one interacting pair must yield one chip however the partner token is spelled, was: "
						+ warnings);
		// Which direction survives is dataset order — the subject reached first keeps the chip — and
		// the chip text is built from the rule, not from the resolved partner, so the count above is
		// what pins the resolution. This asserts the surviving chip is still the real pair.
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Insulin glargine", "metoprolol", "Major"),
				"and it must still be the real pair, naming both sides, was: " + warnings);
	}

	@Test
	public void screeningNeverRepeatsAChipTheDrugInPlayArmAlreadyRaised() {
		// The screening gate is on the QUESTION alone (deliberately — the pre-answer findings pass and
		// the post-answer chips pass must gate identically), so the ANSWER can still put a drug in
		// play beside it. When the answer names a subject the screen also reaches, both arms run the
		// same rule join over the same active orders and produce a byte-identical chip: measured, this
		// arrangement raised the "Simvastatin interacts with active order clarithromycin — Major" chip
		// TWICE in one safetyWarnings array. The answer here cites nothing, which is the shape that
		// reaches this — echo scoping (#105) exempts an answer-named drug only when a CITED record
		// already names it, so an uncited answer keeps every drug it names in play.
		List<SafetyWarning> warnings = ddinterValidator().validate(
				"Yes — simvastatin and clarithromycin interact at a major level.", SCREENING_QUESTION,
				interactingPairContext());

		for (int a = 0; a < warnings.size(); a++) {
			for (int b = a + 1; b < warnings.size(); b++) {
				// Every field a client renders, compared here rather than through toString, so the
				// assertion cannot be weakened by a change to that method's format.
				SafetyWarning one = warnings.get(a);
				SafetyWarning other = warnings.get(b);
				assertFalse(one.getType().equals(other.getType())
						&& one.getDrug().equals(other.getDrug())
						&& one.getDetail().equals(other.getDetail()),
						"no chip may be raised twice, was: " + warnings);
			}
		}
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Simvastatin", "clarithromycin", "Major"),
				"and the pair must still be reported, was: " + warnings);
	}

	/**
	 * Verbatim KB slice in which the two arms name one substance DIFFERENTLY — see the fixture's own
	 * {@code metadata.note} for the three properties the shape needs at once.
	 */
	private static final String CROSS_ARM_FIXTURE = "chartsearchai-test/ddi-crossarm-canonical-duplicate.json";

	@Test
	public void theScreenStandsDownFromAPairTheSubstanceArmReportedUnderItsCanonicalName() throws Exception {
		// The same invariant as the test above, on the one shape a TEXT-keyed suppression cannot see. Since
		// issue #162 the drug-in-play arm names its chip after the substance's CANONICAL row while this arm
		// names its own after whichever row findForActiveOrders returned first, so for a family whose
		// route-unspecified row is not its first row the two arms word one finding differently and the
		// text key stops recognising the repeat. Chloroprocaine is such a family and lidocaine's rule sits
		// on its OPHTHALMIC row alone, so:
		//
		//   drug-in-play arm:  "Chloroprocaine interacts with active order lidocaine — Moderate. …"
		//   screening arm:     "Chloroprocaine (ophthalmic) interacts with active order lidocaine — …"
		//
		// One clinical fact, two chips, and — since issue #110 — two citable safety-finding records. The
		// suppression is therefore keyed on the PAIR's identity rather than on the rendered strings.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(CROSS_ARM_FIXTURE);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Chloroprocaine 20mg/mL", "Lidocaine 2%"), null, null, null);

		// Preconditions through the production resolvers, without which every count below could pass while
		// the arms never met.
		assertTrue(service.findByQuery(SCREENING_QUESTION).isEmpty(),
				"precondition: the screening question must name no drug, or the screen never runs");
		List<DrugReference> orderEntries = service.findForActiveOrders(context);
		assertEquals(Arrays.asList("Chloroprocaine (ophthalmic)", "Chloroprocaine", "Lidocaine"),
				DrugReferenceTestSupport.names(orderEntries),
				"precondition: both orders must resolve, and the ROUTE-QUALIFIED chloroprocaine row must "
						+ "come first — that is what makes the two arms disagree");

		// The answer names chloroprocaine and cites nothing, which is what puts the substance in play
		// beside the screen (echo scoping exempts an answer-named drug only when a CITED record already
		// names it). It must not name lidocaine: that would put the reverse direction in play too, which
		// is a different subject and a legitimately separate chip.
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate(
				"Yes — she is on chloroprocaine, and the reference data flags a methemoglobinemia risk.",
				SCREENING_QUESTION, context);

		assertEquals(1, warnings.size(),
				"one (substance, active order) pair is one chip however many arms reach it, was: "
						+ warnings);
		assertEquals("Chloroprocaine", warnings.get(0).getDrug(),
				"and the surviving chip names the SUBSTANCE, was: " + warnings);
		for (SafetyWarning warning : warnings) {
			assertFalse(warning.getDetail().contains("(ophthalmic)")
					|| warning.getDrug().contains("(ophthalmic)"),
					"no chip may assert an ophthalmic preparation the chart does not record, was: "
							+ warning.getDetail());
		}
	}

	@Test
	public void theScreenStandsDownFromAPairTheSubstanceArmReportedInTheOtherDirection() throws Exception {
		// The same suppression asked of the OTHER direction, which a text key could never answer: "B
		// interacts with active order A" is not the string "A interacts with active order B". Nothing but
		// the order the patient's own order names happen to be listed in decides which direction this arm
		// reaches a pair from — findForActiveOrders walks the order names and resolves each — so with the
		// two names transposed the screen reaches the pair as Lidocaine x chloroprocaine while the
		// drug-in-play arm reported it as Chloroprocaine x lidocaine. The pair is one clinical fact either
		// way, so the ledger's key is unordered; a directional key leaves the repeat in for exactly half
		// the possible chart orderings.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(CROSS_ARM_FIXTURE);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Lidocaine 2%", "Chloroprocaine 20mg/mL"), null, null, null);

		assertEquals(Arrays.asList("Lidocaine", "Chloroprocaine (ophthalmic)", "Chloroprocaine"),
				DrugReferenceTestSupport.names(service.findForActiveOrders(context)),
				"precondition: the PARTNER's entry must come first, so the screen reaches the pair from the "
						+ "opposite side to the arm that reported it");

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate(
				"Yes — she is on chloroprocaine, and the reference data flags a methemoglobinemia risk.",
				SCREENING_QUESTION, context);

		assertEquals(1, warnings.size(),
				"one pair is one chip whichever direction the screen reaches it from, was: " + warnings);
		assertEquals("Chloroprocaine", warnings.get(0).getDrug(),
				"and the chip that survives is the drug-in-play arm's, which named the substance, was: "
						+ warnings);
	}

	@Test
	public void aNonScreeningMedicationQuestionIsUnaffected() {
		// An enumeration question about the same medications on the same chart. It carries the
		// MEDICATIONS intent but asks nothing about interactions, so the screening arm must not fire
		// — the chips a clinician sees must stay tied to what they asked.
		List<SafetyWarning> warnings = screen(ddinterValidator(),
				"What medications is the patient taking?", interactingPairContext());

		assertTrue(warnings.isEmpty(),
				"a plain medication-list question must not be screened for interactions, was: "
						+ warnings);
	}

	@Test
	public void aQuestionAboutInteractingWithPeopleIsNotAnInteractionScreen() {
		// The eager-firing guard. "interact" alone is not enough: the trigger also requires the
		// question to carry the router's MEDICATIONS intent, so a question about how the patient
		// interacts with their care team screens nothing. Firing on unrelated questions would be
		// worse than the gap this closes.
		List<SafetyWarning> warnings = screen(ddinterValidator(),
				"Does the patient interact well with the care team?", interactingPairContext());

		assertTrue(warnings.isEmpty(),
				"a non-medication question that merely contains 'interact' must screen nothing, was: "
						+ warnings);
	}

	@Test
	public void aMedicationQuestionSayingInteractiveScreensNothing() {
		// The other half of the eager-firing guard: the interaction cue is word-boundary anchored, so a
		// word that merely STARTS with "interact" is not the cue. This question carries the MEDICATIONS
		// intent, so the boundary is the only thing standing between it and a screen it never asked
		// for — and an unasked-for safety screen is the failure this predicate is deliberately
		// conservative about. Mutation-checked: dropping the anchoring makes this question screen.
		List<SafetyWarning> warnings = screen(ddinterValidator(),
				"Show me an interactive list of her medications.", interactingPairContext());

		assertTrue(warnings.isEmpty(),
				"\"interactive\" is not the interaction cue, so nothing may be screened, was: "
						+ warnings);
	}

	@Test
	public void aScreeningQuestionThatNAMESADrugKeepsTheExistingSingleChip() {
		// The boundary with the question-driven arm: once the question names a drug, that arm has an
		// anchor and already answers the question, so the screening arm stands down. Asserting the
		// count pins that the two arms cannot both chip the same pair.
		List<SafetyWarning> warnings = screen(ddinterValidator(),
				"Does simvastatin interact with any of her current medications?",
				interactingPairContext());

		assertEquals(1, warnings.size(),
				"a question naming a drug keeps exactly the question-driven chip, was: " + warnings);
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Simvastatin", "clarithromycin", "Major"),
				"and it must still be the Major pair, was: " + warnings);
	}

	@Test
	public void aQuestionNamingTwoDrugsGoesToThePairArmAndNotToTheScreen() {
		// The boundary with issue #114/#120's question-pair arm, pinned rather than reasoned. Both arms
		// now group chips and both cap at the same configured limit (#131's maxPairChips, ten by
		// default here since no GP is set), so "can one arm report a pair the other suppresses?"
		// has to have an answer: no, because the gates are mutually exclusive and at most one of them
		// runs per question. That arm needs the question to resolve >= 2 drugs; this one needs it to
		// resolve none. This question does both jobs at once — it names warfarin and aspirin (a real
		// Major row in the DDInter excerpt, neither of them on this chart) AND carries the screening
		// cues — so it is the single input where a shared pair could be double-reported or lost.
		// Exclusivity means the pair arm answers it and the patient's OWN pair is not raised.
		List<SafetyWarning> warnings = screen(ddinterValidator(),
				"Does warfarin interact with aspirin, and any of her current medications?",
				interactingPairContext());

		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "aspirin", "Major"),
				"the question-pair arm must own a question naming two drugs, was: " + warnings);
		// Every chip must be SUBJECTED on a drug the question named. That is the precise statement of
		// "the screen stood down": a screening chip's subject is one of the patient's own orders, which
		// here are simvastatin and clarithromycin — neither named. Chips subjected on warfarin or
		// aspirin are the two question-driven arms doing their own jobs, including warfarin against the
		// chart, which is why a looser "no chip mentions clarithromycin" assertion is wrong: the
		// drug-in-play arm legitimately reports "Warfarin interacts with active order clarithromycin".
		for (SafetyWarning warning : warnings) {
			assertTrue(warning.getDrug().toLowerCase().contains("warfarin")
					|| warning.getDrug().toLowerCase().contains("aspirin"),
					"no chip may be subjected on an active order the question never named, was: "
							+ warning);
		}
	}

	@Test
	public void screeningIsCappedAndKeepsTheMostSeverePairs() {
		// Blast radius. Pairs grow quadratically with the medication list — 10 active orders is 45
		// pairs — and each surviving chip is also injected into the prompt as a citable finding, so
		// an uncapped arm would both bury the clinician and write tens of thousands of characters
		// into the context window. These six real bundled-sample drugs interact pairwise 15 ways,
		// exactly 10 of them Major, so the cap and the severity ordering are both observable: the
		// arm must report 10, and all 10 must be the Major ones. Dataset order would instead keep
		// simvastatin x warfarin (Minor) and three Moderates.
		List<SafetyWarning> warnings = screen(ddinterValidator(), SCREENING_QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Simvastatin", "Warfarin", "Ciprofloxacin",
								"Clarithromycin", "Fluconazole", "Amiodarone"),
						DrugReferenceTestSupport.set("C10AA01", "B01AA03", "J01MA02", "J01FA09",
								"J02AC01", "C01BD01"),
						null, null));

		assertEquals(ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_MAX_PAIR_CHIPS, warnings.size(),
				"the screening arm must cap the chips it raises, was: " + warnings.size());
		for (SafetyWarning warning : warnings) {
			assertTrue(warning.getDetail().contains("— Major."),
					"the cap must keep the most severe pairs, not the dataset's first ones, was: "
							+ warning.getDetail());
		}
	}

	@Test
	public void theScreeningFindingIsInjectedAsACitableRecord() {
		// The other half of the issue: references: []. The finding becomes a record through the
		// established #110 mechanism (preAnswerFindings -> injectRecords), so the model has the
		// pair, its severity and its mechanism in front of it as a numbered line it can cite,
		// instead of being asked to derive a join from nothing.
		PatientChart result = screeningInjector().injectRecords(
				DrugReferenceTestSupport.oneRecordChart(), interactingPairContext(),
				SCREENING_QUESTION);

		RecordMapping finding = null;
		for (RecordMapping m : result.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(m.getResourceType())) {
				finding = m;
			}
		}
		assertNotNull(finding, "the screening finding must be injected as its own record: "
				+ result.getText());
		assertTrue(finding.getText().toLowerCase().contains("clarithromycin")
				&& finding.getText().toLowerCase().contains("simvastatin"),
				"the record must name both sides of the pair: " + finding.getText());
		assertTrue(result.getText().contains("[" + finding.getIndex() + "] "),
				"it must be a numbered, citable chart line: " + result.getText());
	}

	@Test
	public void screeningInjectsNoDrugReferenceRecordsForTheActiveOrders() {
		// A deliberate scope decision, pinned. Order-driven reference injection stays
		// relevance-scoped: unscoping it for screening questions would append one full reference
		// record per active order — each up to MAX_INTERACTION_RENDER_CHARS of partner prose the
		// patient has nothing to do with, which #117 measures the model reciting verbatim into the
		// answer — and that cost grows with the medication list. The deterministic finding already
		// carries the pair, the severity and the mechanism in one bounded line, so the screening
		// answer is grounded without it.
		PatientChart result = screeningInjector().injectRecords(
				DrugReferenceTestSupport.oneRecordChart(), interactingPairContext(),
				SCREENING_QUESTION);

		for (RecordMapping m : result.getMappings()) {
			assertFalse(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(m.getResourceType()),
					"screening must not inject whole reference entries for the active orders: "
							+ m.getText());
		}
	}
}
