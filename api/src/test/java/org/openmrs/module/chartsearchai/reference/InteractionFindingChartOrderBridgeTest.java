package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #349: an injected {@code safety_finding} states which of this patient's own active orders
 * each substance it names was resolved from, where the name that order DISPLAYS does not name it —
 * the silence test #349 shipped was every name the order RECORDS, and issue #347 narrowed it; see
 * {@code OneOrderNameAcrossAnswerAndChipTest}.
 *
 * <p>The reported shape: two active orders whose only chart names are the local brands
 * {@code Zolvimix} and {@code Klarizom}, resolved to Simvastatin and Clarithromycin through their WHO
 * ATC maps alone. The screening arm raised a correct Major chip and the answer read <em>"The records
 * do not address interactions between the patient's current medications."</em> — because the finding
 * named two substances the chart spells nowhere, so it was unciteable by construction and the model
 * resolved that by disclaiming. Nothing else in that prompt could supply the connection: a screening
 * question names no drug, so no {@code drug_reference} record is relevance-scoped in, and the chart
 * already substantiated both orders so no {@code active_drug_order} record was injected either
 * ({@code 0 active-order, 0 drug-reference, 1 safety-finding} in the ticket's own DEBUG line).
 *
 * <p><b>Additive, and never a renaming.</b> Which name the chip and the finding PRINT for a substance
 * is #339's settlement and is untouched here — {@link #theChipDetailIsTheWordsItAlwaysWas} is that
 * invariant. The clause states a RESOLUTION this module performed, in the module's own voice, and
 * asserts no class membership and no identity of the prescription, which is what #339's reverted
 * rounds 5–6 could not say of naming a constituent.
 *
 * <p><b>Scoped to the orders the PASS itself used, not to every order carrying the code.</b>
 * {@code DrugSafetyValidator.activeOrdersOtherThan} drops the subject's own orders before the partner
 * is witnessed, precisely so one prescription cannot report the two halves of one tablet as an
 * interacting pair; attributing the partner to an order that reduction refused would put that
 * suppressed witness back, in citable text carrying {@code STRENGTH_WITHHOLD}.
 * {@link #eachSubstanceIsAttributedOnlyToTheOrderThePassResolvedItFrom} is that property, on a
 * combination brand that carries BOTH codes.
 *
 * <p>Every case here drives the real {@code DrugReferenceInjector.injectRecords} wired to the real
 * {@code DrugSafetyValidator} over a fixture parsed by the real production parser, and reads the
 * record a model would read.
 */
public class InteractionFindingChartOrderBridgeTest {

	/** The pre-existing VERBATIM DDInter slice, which already carries every row these cases need:
	 *  Simvastatin ({@code C10AA01}) x Clarithromycin ({@code J01FA09}) Major, Acetylsalicylic acid
	 *  ({@code N02BA01}) x Warfarin ({@code B01AA03}) Major, and — the reason the alias case can be
	 *  written at all — an {@code Acetylsalicylic acid} row whose {@code rxnorm_name} is {@code aspirin},
	 *  so an order named {@code Aspirin 81mg} reaches it through an ALIAS and not through its display
	 *  label. A fixture of this issue's own would have had to hand-author a severity for a real DDInter
	 *  pair, and the one it needed (ASA x Clarithromycin) the dataset rates {@code Unknown}. */
	private static final String BRAND_NAMED_ORDERS = "chartsearchai-test/ddi-alias-drug-names.json";

	/** Issue #283's own verbatim slice: Methylphenidate and Modafinil are Minor-related AND both publish
	 *  {@code N06BA}, so the drug-in-play arm FOLDS the class arm's duplicate-therapy sentence onto the
	 *  rated rule's chip. The one call site a mutation could neuter with the whole suite green until
	 *  {@link #aFoldedChipsPartnerIsBridgedToo} arrived. */
	private static final String FOLDED_CLASS_PAIR =
			"chartsearchai-test/ddi-folded-minor-class-pair.json";

	private static final String SCREENING_QUESTION =
			"Are any of his current medications interacting with each other?";

	/** Read off production, so no case below can pass against a clause no record carries. What pins
	 *  the WORDS is {@link #theClauseIsTheWordsAModelReads}, and only that. */
	private static final String LEAD = DrugReferenceInjector.FINDING_CHART_ORDER_LEAD;

	private static final String WITHHOLD = DrugReferenceInjector.STRENGTH_WITHHOLD;

	/** The call the SCREENING arrangements below state since issue #348: both drugs of a screened pair
	 *  are the patient's own prescriptions, so the finding licenses a change of therapy rather than a
	 *  refusal of a proposal nobody made. The drug-in-play arrangements still state {@link #WITHHOLD},
	 *  which is why {@link #callOf} asks the record rather than assuming either. */
	private static final String CHANGE_CURRENT =
			DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION;

	/** Everything the Simvastatin x Clarithromycin chip's detail says after its em dash: the rule's
	 *  rating and the dataset's own mechanism prose (DDInter mechanism 2085). Spelled out rather than
	 *  read off the fixture, because two cases assert the whole string a model reads and a helper that
	 *  derived it from the same source the production code reads could not fail. */
	private static final String RATING_AND_MECHANISM =
			"Major. Coadministration with potent inhibitors of CYP450 3A4 may significantly increase "
					+ "the plasma concentrations of simvastatin and lovastatin and their active acid "
					+ "metabolites, all of which are primarily metabolized by the isoenzyme.";

	/**
	 * The ticket's own chart: a combination brand mapped to BOTH substances' codes beside a
	 * single-substance brand mapped to one, and a real querystore {@code drug_order} record for each —
	 * so the reconciliation injects no {@code active_drug_order} record and the finding is the only
	 * module-authored record in the prompt, exactly as the ticket measured.
	 */
	private static PatientClinicalContext ticketChart() {
		return DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set("Zolvimix", "Klarizom"),
			DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null,
			Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-zolvimix", "Zolvimix",
					DrugReferenceTestSupport.set("Zolvimix"),
					DrugReferenceTestSupport.set("C10AA01", "J01FA09")),
				DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom",
					DrugReferenceTestSupport.set("Klarizom"),
					DrugReferenceTestSupport.set("J01FA09"))));
	}

	private static PatientChart chartNaming(String... orderUuidAndText) {
		RecordMapping[] records = new RecordMapping[orderUuidAndText.length / 2];
		for (int n = 0; n < records.length; n++) {
			records[n] = DrugReferenceTestSupport.drugOrderRecord(n + 1, orderUuidAndText[2 * n],
				orderUuidAndText[2 * n + 1]);
		}
		return DrugReferenceTestSupport.chartOf(records);
	}

	private static List<String> findings(String fixture, PatientChart chart,
			PatientClinicalContext context, String question) throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(fixture);
		return DrugReferenceTestSupport.findingTexts(DrugReferenceTestSupport
				.injectorWithSafety(service).injectRecords(chart, context, question));
	}

	/** The one finding of an arrangement that must raise exactly one — asserted rather than assumed,
	 *  because every case below turns on a single record's text and a second finding would let the
	 *  wrong one answer for it. */
	private static String onlyFinding(PatientChart chart, PatientClinicalContext context,
			String question) throws IOException {
		return onlyFinding(BRAND_NAMED_ORDERS, chart, context, question);
	}

	private static String onlyFinding(String fixture, PatientChart chart,
			PatientClinicalContext context, String question) throws IOException {
		List<String> findings = findings(fixture, chart, context, question);
		assertEquals(1, findings.size(), "one pair is one citable record, was: " + findings);
		return findings.get(0);
	}

	private static String ticketFinding() throws IOException {
		return onlyFinding(
			chartNaming("order-zolvimix", "Zolvimix", "order-klarizom", "Klarizom"),
			ticketChart(), SCREENING_QUESTION);
	}

	/**
	 * @return the strength call {@code finding} ends with — {@link #WITHHOLD} for the drug-in-play
	 *         arrangements below and {@link #CHANGE_CURRENT} for the screening ones (issue #348).
	 *
	 *         <p>Asserted rather than assumed. This class mixes both question shapes, and
	 *         {@link DrugReferenceTestSupport#bridgeOf} stops at whichever strength clause the record
	 *         states: a record ending in neither call would make that helper return the whole tail, so
	 *         every case here would compare a bridge against a bridge plus a clause and the diff would
	 *         read as a bridge defect. Asserting the call here is what turns that into a named failure
	 *         rather than a puzzling one, and it is this class's question — the shared extractor
	 *         serves callers with only the one arrangement too.
	 */
	private static String callOf(String finding) {
		if (finding.endsWith(CHANGE_CURRENT)) {
			return CHANGE_CURRENT;
		}
		assertTrue(finding.endsWith(WITHHOLD),
			"every finding here states one of the two calls its arm can state, was: " + finding);
		return WITHHOLD;
	}

	/**
	 * @return the bridge clause of {@code finding}, without its lead — or null where it carries none.
	 *
	 *         <p>Issue #347 moved the extraction itself into {@link DrugReferenceTestSupport#bridgeOf},
	 *         so where the clause sits inside a finding is decided once; this wrapper stays for
	 *         {@link #callOf}, which is this class's own assertion and not the extractor's job.
	 */
	private static String bridgeOf(String finding) {
		callOf(finding);
		return DrugReferenceTestSupport.bridgeOf(finding);
	}

	@Test
	public void theFindingNamesTheChartsOwnWordsForBothSubstancesItRelates() throws Exception {
		String finding = ticketFinding();

		assertTrue(finding.contains("Zolvimix"),
			"the finding names Simvastatin, which this chart spells only as Zolvimix, was: " + finding);
		assertTrue(finding.contains("Klarizom"),
			"the finding names Clarithromycin, which this chart spells only as Klarizom, was: "
					+ finding);
	}

	@Test
	public void eachSubstanceIsAttributedOnlyToTheOrderThePassResolvedItFrom() throws Exception {
		// Zolvimix carries Clarithromycin's code too, so an attribution over every carrier would add
		// "Clarithromycin from Zolvimix" — the self-witness activeOrdersOtherThan exists to refuse,
		// which would state that one prescription holds both halves of the interacting pair.
		assertEquals("Simvastatin from Zolvimix; Clarithromycin from Klarizom.",
			bridgeOf(ticketFinding()),
			"the subject is attributed to its own order and the partner only to the order that "
					+ "witnessed it");
	}

	@Test
	public void aSubstanceTwoOrdersCarryIsAttributedToBothOfThem() throws Exception {
		// One item per ORDER, which is why the clause is a flat "; "-joined list and not a conjunction:
		// two brands both mapped to simvastatin's code are two chart records the model must be able to
		// find, and naming only one of them would leave the other unreachable.
		String finding = onlyFinding(
			chartNaming("order-a", "Zolvimix", "order-b", "Statibrand", "order-klarizom", "Klarizom"),
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Zolvimix", "Statibrand", "Klarizom"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null,
				Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-a", "Zolvimix",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-b", "Statibrand",
						DrugReferenceTestSupport.set("Statibrand"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom",
						DrugReferenceTestSupport.set("Klarizom"),
						DrugReferenceTestSupport.set("J01FA09")))),
			SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix; Simvastatin from Statibrand; "
				+ "Clarithromycin from Klarizom.", bridgeOf(finding),
			"every order the pass resolved a substance from is named, was: " + finding);
	}

	@Test
	public void twoOrdersOfTheSameDisplayAreNamedOnce() throws Exception {
		// Two prescriptions of one brand are two Order rows and one string a model can look up, so the
		// clause states it once — the de-duplication is by VALUE, which is what
		// SafetyWarning.ChartOrderBridge.equals is for.
		String finding = onlyFinding(
			chartNaming("order-a", "Zolvimix", "order-b", "Zolvimix", "order-klarizom", "Klarizom"),
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Zolvimix", "Klarizom"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null,
				Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-a", "Zolvimix",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-b", "Zolvimix",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom",
						DrugReferenceTestSupport.set("Klarizom"),
						DrugReferenceTestSupport.set("J01FA09")))),
			SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix; Clarithromycin from Klarizom.", bridgeOf(finding),
			"one display is one item however many orders carry it, was: " + finding);
	}

	@Test
	public void theDrugInPlayArmsPartnerIsBridgedToo() throws Exception {
		// Not the screening arm: the question names the drug, so addInteractionWarnings raises this
		// chip. The scope is the finding and not one arm.
		String finding = onlyFinding(chartNaming("order-klarizom", "Klarizom"),
			DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Klarizom"),
				DrugReferenceTestSupport.set("J01FA09"), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom",
					DrugReferenceTestSupport.set("Klarizom"),
					DrugReferenceTestSupport.set("J01FA09")))),
			"Can I give him simvastatin?");

		assertEquals("Clarithromycin from Klarizom.", bridgeOf(finding),
			"the partner the question's drug interacts with is the patient's own brand-named order, "
					+ "was: " + finding);
	}

	@Test
	public void anOrderWhoseDisplayAlreadyNamesTheSubstanceIsNotBridged() throws Exception {
		// The chart's own words already carry both names, so a clause here would be noise in a record
		// whose whole prompt budget is spent on evidence.
		String finding = onlyFinding(
			chartNaming("order-simva", "Simvastatin 20mg", "order-clari", "Clarithromycin 500mg"),
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Simvastatin 20mg", "Clarithromycin 500mg"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null,
				Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-simva", "Simvastatin 20mg",
						DrugReferenceTestSupport.set("Simvastatin 20mg"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-clari", "Clarithromycin 500mg",
						DrugReferenceTestSupport.set("Clarithromycin 500mg"),
						DrugReferenceTestSupport.set("J01FA09")))),
			SCREENING_QUESTION);

		assertFalse(finding.contains(LEAD),
			"a chart that already names both substances needs no bridge, was: " + finding);
	}

	@Test
	public void anOrderNamingTheSubstanceOnlyByAnAliasIsNotBridged() throws Exception {
		// Acetylsalicylic acid's rxnorm_name is aspirin, so the order named "Aspirin 81mg" resolves it
		// through an ALIAS rather than through its display label — and the chart's own words therefore
		// DO carry a name of it. The guard is the one name a chart record DISPLAYS for the order (#347;
		// it was every name the order RECORDS until then, and here the display is one of them, which is
		// why this case is unmoved) — never the string the finding prints: printed, this substance is
		// "Acetylsalicylic acid (aspirin)",
		// which no order display contains, so a printed-name guard would bridge it as if the chart
		// named nothing. Its partner here is a brand-named warfarin order, which IS bridged, so the
		// case reads a clause rather than the absence of one.
		String finding = onlyFinding(
			chartNaming("order-aspirin", "Aspirin 81mg", "order-warf", "Coagubrand"),
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Aspirin 81mg", "Coagubrand"),
				DrugReferenceTestSupport.set("N02BA01", "B01AA03"), null, null,
				Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-aspirin", "Aspirin 81mg",
						DrugReferenceTestSupport.set("Aspirin 81mg"),
						DrugReferenceTestSupport.set("N02BA01")),
					DrugReferenceTestSupport.activeOrder("order-warf", "Coagubrand",
						DrugReferenceTestSupport.set("Coagubrand"),
						DrugReferenceTestSupport.set("B01AA03")))),
			SCREENING_QUESTION);

		assertEquals("Warfarin from Coagubrand.", bridgeOf(finding),
			"only the brand-named order is bridged; the aspirin order's own name reaches its "
					+ "substance, was: " + finding);
	}

	@Test
	public void aCombinationOrderCarryingBOTHSubstancesBridgesBothSides() throws Exception {
		// The shape three of the review's blocking findings turned on. ONE prescription carries both
		// substances' codes, and the drug-in-play arm applies no activeOrdersOtherThan reduction — so
		// the order that resolves the SUBJECT is also the only witness the PARTNER has. Attributing the
		// partner only to orders that do NOT resolve the subject left "active order Simvastatin"
		// unbridged here, which is #349's own defect surviving inside the clause meant to close it.
		String finding = onlyFinding(chartNaming("order-zolvimix", "Zolvimix"),
			DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Zolvimix"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-zolvimix", "Zolvimix",
					DrugReferenceTestSupport.set("Zolvimix"),
					DrugReferenceTestSupport.set("C10AA01", "J01FA09")))),
			"Can I give him clarithromycin?");

		assertEquals("Clarithromycin from Zolvimix; Simvastatin from Zolvimix.", bridgeOf(finding),
			"both sides of the pair are attributed to the one prescription that holds them, was: "
					+ finding);
	}

	@Test
	public void anOrderKnownOnlyByItsCodesIsNotBridged() throws Exception {
		// [ATC …] is the ABSENCE of a name (#290), so it bridges nothing — handing it over would put a
		// bare code where the record had a substance name.
		String finding = onlyFinding(chartNaming("order-klarizom", "Klarizom"),
			DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Klarizom"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null,
				Arrays.asList(
					PatientClinicalContext.ActiveDrugOrder.namedByCodesOnly("order-coded",
						"[ATC C10AA01]", DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom",
						DrugReferenceTestSupport.set("Klarizom"),
						DrugReferenceTestSupport.set("J01FA09")))),
			SCREENING_QUESTION);

		assertFalse(finding.contains("[ATC"),
			"an order the module could read no name for is not a name to bridge to, was: " + finding);
		assertEquals("Clarithromycin from Klarizom.", bridgeOf(finding),
			"the named order is still bridged, was: " + finding);
	}

	@Test
	public void anOrderThatResolvedNEITHERSubstanceIsNotNamedAtAll() throws Exception {
		// The conjunct that stops a FABRICATED statement about the patient's record. Both walks put
		// every active order to addChartOrderBridge, so its own resolvesFromAny refusal is the only
		// thing keeping an unrelated prescription out of a clause the model reads as this chart's own
		// resolutions — inside citable evidence carrying STRENGTH_WITHHOLD. Nothing pinned it until
		// this case: drop that conjunct and the third order below is named as one of the two
		// substances' sources.
		String finding = onlyFinding(
			chartNaming("order-zolvimix", "Zolvimix", "order-klarizom", "Klarizom",
				"order-para", "Paracetamex"),
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Zolvimix", "Klarizom", "Paracetamex"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09", "N02BE01"), null, null,
				Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-zolvimix", "Zolvimix",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom",
						DrugReferenceTestSupport.set("Klarizom"),
						DrugReferenceTestSupport.set("J01FA09")),
					// A real prescription this dataset relates to neither side.
					DrugReferenceTestSupport.activeOrder("order-para", "Paracetamex",
						DrugReferenceTestSupport.set("Paracetamex"),
						DrugReferenceTestSupport.set("N02BE01")))),
			SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix; Clarithromycin from Klarizom.", bridgeOf(finding),
			"an order neither substance resolved from is not this chart's source for either, was: "
					+ finding);
	}

	@Test
	public void aFoldedChipsPartnerIsBridgedToo() throws Exception {
		// The FOLDED chip is the highest-consequence record here — a rated rule carrying the class
		// arm's duplicate-therapy sentence and STRENGTH_WITHHOLD — and it is worded at its own call
		// site. Until this case that site could be neutered to an empty list with the whole build
		// green, which is the blind spot this module's own probe cells already have for folded chips.
		String finding = onlyFinding(FOLDED_CLASS_PAIR, chartNaming("order-moda", "Modabrand"),
			DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Modabrand"),
				DrugReferenceTestSupport.set("N06BA07"), null, null,
				Arrays.asList(DrugReferenceTestSupport.activeOrder("order-moda", "Modabrand",
					DrugReferenceTestSupport.set("Modabrand"),
					DrugReferenceTestSupport.set("N06BA07")))),
			"Can I give him methylphenidate?");

		assertTrue(finding.contains("is in the same ATC class (N06BA)"),
			"the arrangement must actually FOLD or this case pins the unfolded site, was: " + finding);
		assertEquals("Modafinil from Modabrand.", bridgeOf(finding),
			"a folded chip's partner is bridged like any other, was: " + finding);
	}

	@Test
	public void theStrengthClauseStaysSentenceFinal() throws Exception {
		String finding = ticketFinding();

		assertTrue(finding.contains(LEAD),
			"the arrangement must carry a bridge for this case to say anything, was: " + finding);
		// Sentence-finality is established inside callOf, which reaches both of its return paths only
		// through an endsWith on the call it returns — so re-asserting finding.endsWith(call) here
		// could not fail, and a line that cannot fail claims a property it does not test. What this
		// case adds is the ORDERING below; the call itself is pinned as a literal, on this very
		// arrangement, by theClauseIsTheWordsAModelReads.
		String call = callOf(finding);
		assertTrue(finding.indexOf(LEAD) < finding.indexOf(call),
			"the bridge qualifies the evidence and precedes the call, was: " + finding);
	}

	@Test
	public void theClauseIsTheWordsAModelReads() throws Exception {
		assertEquals(" This module resolved the substances named here from this patient's own active "
				+ "orders. ", LEAD,
			"the lead is prompt text, and it ENDS a sentence so ReferenceProseFidelityCheck's "
					+ "sentence-whole exit covers its invariant half; a reword is a behaviour change");
		assertEquals("Safety finding — Simvastatin: Simvastatin interacts with active order "
				+ "Clarithromycin — " + RATING_AND_MECHANISM + LEAD
				+ "Simvastatin from Zolvimix; Clarithromycin from Klarizom." + CHANGE_CURRENT,
			ticketFinding(), "the whole record a model reads. Its call is the CURRENT-MEDICATION one "
					+ "since issue #348: this arrangement is a screening question, so both drugs are "
					+ "the patient's own prescriptions and nothing proposed either of them");
	}

	@Test
	public void theChipDetailIsTheWordsItAlwaysWas() throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(BRAND_NAMED_ORDERS);
		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(service)
				.validate("", SCREENING_QUESTION, ticketChart());

		assertEquals(1, chips.size(), "one pair is one chip, was: " + chips);
		assertEquals("Simvastatin interacts with active order Clarithromycin — " + RATING_AND_MECHANISM,
			chips.get(0).getDetail(),
			"the chip's own detail is unchanged by the clause — since #347 the attributions reach a "
					+ "client as the chip's chartOrderBridges key, and its detail still must not");
	}
}
