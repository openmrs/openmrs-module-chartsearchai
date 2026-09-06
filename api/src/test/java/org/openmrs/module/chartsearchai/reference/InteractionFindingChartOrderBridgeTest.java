package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

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
 *
 * <p><b>Context-sensitive because the record NUMBER issue #379 appends to each item is gated, and
 * OFF on a stock install</b> — {@code chartsearchai.drugSafety.citeOrderRecords}, ADR Decision 76,
 * whose "What is NOT measured" section is the reason. A contextless case runs with the property
 * absent, which fails safe to the default, so it could not tell a rendering that honours the flag
 * from one that ignores it. {@link #setUp} turns it on for every case here; the one case that turns
 * it back off, {@link #aStockInstallStatesNoRecordNumberAtAll}, is what pins the gate — flip the
 * shipped default to true and it is the case that reddens.
 */
public class InteractionFindingChartOrderBridgeTest extends BaseModuleContextSensitiveTest {

	/** Every case below asserts the clause a model reads WITH the numbers, so every case needs the
	 *  property on. Set here rather than per case so a new case cannot silently assert the gated
	 *  rendering against the ungated default and pass for the wrong reason. */
	@BeforeEach
	public void setUp() {
		citeOrderRecords(true);
	}

	private static void citeOrderRecords(boolean on) {
		Context.getAdministrationService().setGlobalProperty(
			ChartSearchAiConstants.GP_DRUG_SAFETY_CITE_ORDER_RECORDS, String.valueOf(on));
	}

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
		assertEquals("Simvastatin from Zolvimix [1]; Clarithromycin from Klarizom [2].",
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

		assertEquals("Simvastatin from Zolvimix [1]; Simvastatin from Statibrand [2]; "
				+ "Clarithromycin from Klarizom [3].", bridgeOf(finding),
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

		assertEquals("Simvastatin from Zolvimix; Clarithromycin from Klarizom [3].", bridgeOf(finding),
			"one display is one item however many orders carry it — and since issue #379 that item carries "
					+ "no record number, the two orders sharing the display being two different records; the "
					+ "unambiguous item beside it still carries one, so this is the rule and not a "
					+ "clause-wide silence, was: " + finding);
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

		assertEquals("Clarithromycin from Klarizom [1].", bridgeOf(finding),
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

		assertEquals("Warfarin from Coagubrand [2].", bridgeOf(finding),
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

		assertEquals("Clarithromycin from Zolvimix [1]; Simvastatin from Zolvimix [1].", bridgeOf(finding),
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
		assertEquals("Clarithromycin from Klarizom [1].", bridgeOf(finding),
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

		assertEquals("Simvastatin from Zolvimix [1]; Clarithromycin from Klarizom [2].", bridgeOf(finding),
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
		assertEquals("Modafinil from Modabrand [1].", bridgeOf(finding),
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
				+ "Simvastatin from Zolvimix [1]; Clarithromycin from Klarizom [2]." + CHANGE_CURRENT,
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

	/**
	 * Issue #379: the numbers the clause writes are readable by the ONE pattern every consumer
	 * decodes a marker with.
	 *
	 * <p>What the clause SAYS is pinned by {@link #eachSubstanceIsAttributedOnlyToTheOrderThePassResolvedItFrom}
	 * and {@link #theClauseIsTheWordsAModelReads}, so this case does not assert the literal a third
	 * time. It asserts the coupling those cannot: the module writes {@code [N]} as a record-line
	 * PREFIX in several places, and this is the first it writes inside a record's own prose, where
	 * being parseable by {@link ChartSearchAiUtils#INLINE_CITATION} is what makes the number reach a
	 * model's citation at all. The literal cases redden on a reworded spelling too; what they cannot
	 * say is that the spelling is the one the shared decode step reads.
	 */
	@Test
	public void theNumbersTheClauseWritesAreReadableByTheSharedCitationPattern() throws Exception {
		assertEquals(new LinkedHashSet<Integer>(Arrays.asList(Integer.valueOf(1), Integer.valueOf(2))),
			ChartSearchAiUtils.citedIndexes(bridgeOf(ticketFinding())),
			"the shared decode step must find the two record numbers the clause states (issue #379)");
	}

	/**
	 * Issue #379: an order the chart carried no record for is cited by the {@code active_drug_order}
	 * record the reconciliation injected FOR it, and not left unnumbered.
	 *
	 * <p>That is why the numbers are resolved after that injection rather than off the chart as it
	 * arrived — resolve them earlier and this is the case that reddens. The obs record is here to move
	 * the injected record off index 2, so the assertion cannot pass against an off-by-one.
	 */
	@Test
	public void anOrderTheChartHadNoRecordForIsCitedByTheOneInjectedForIt() throws Exception {
		String finding = onlyFinding(
			DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.drugOrderRecord(1, "order-klarizom", "Klarizom"),
				DrugReferenceTestSupport.obsRecord(2, "Clinical observation: (2026-03-18) pulse 72")),
			ticketChart(), SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix [3]; Clarithromycin from Klarizom [1].",
			bridgeOf(finding),
			"the Zolvimix order has no chart record, so the reconciliation injects one at [3] and the "
					+ "attribution cites that (issue #379), was: " + finding);
	}

	/**
	 * Issue #379 over issue #118's drifted-uuid shape: where the chart's record does not carry the
	 * order's uuid, the attribution is numbered by the record whose text NAMES the order — the same
	 * fallback that decides the order is substantiated at all.
	 *
	 * <p>Written as a record whose resource uuid is not any order's, which is what a querystore index
	 * behind a re-indexed order looks like. The uuid leg cannot answer here, so the name leg is the
	 * only thing that can, and a mutation dropping it leaves this case with no number.
	 */
	@Test
	public void anOrderTheChartRecordsUnderAnotherUuidIsCitedByTheRecordThatNamesIt() throws Exception {
		String finding = onlyFinding(
			DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.drugOrderRecord(1, "order-klarizom", "Klarizom"),
				DrugReferenceTestSupport.drugOrderRecord(2, "stale-index-uuid", "Zolvimix")),
			ticketChart(), SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix [2]; Clarithromycin from Klarizom [1].",
			bridgeOf(finding),
			"the record naming the order is the one to cite where its uuid drifted (issues #118, "
					+ "#379), was: " + finding);
	}

	/**
	 * Issue #379: an order TWO live records name is cited by neither. The drifted-uuid fallback is
	 * the only leg that can answer more than once, and where it does the module cannot say which
	 * record the attribution means — so it says nothing, which is the clause as it read before this
	 * issue. Its unambiguous neighbour still carries a number, so the silence is per item.
	 */
	@Test
	public void anOrderTwoRecordsNameIsCitedByNeither() throws Exception {
		String finding = onlyFinding(
			DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.drugOrderRecord(1, "order-klarizom", "Klarizom"),
				DrugReferenceTestSupport.drugOrderRecord(2, "stale-index-uuid", "Zolvimix"),
				DrugReferenceTestSupport.drugOrderRecord(3, "another-stale-uuid", "Zolvimix 20mg")),
			ticketChart(), SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix; Clarithromycin from Klarizom [1].",
			bridgeOf(finding),
			"two records name the Zolvimix order, so no number is stated for it rather than one of "
					+ "the two being guessed at (issue #379), was: " + finding);
	}

	/**
	 * Issue #379: a record ANOTHER active order is, is not this order's citation.
	 *
	 * <p>Two doses of one brand — the aspirin 81mg / 325mg shape — where the chart carries a record
	 * for the first and none for the second, and both orders record the DRUG's name while displaying
	 * their own dose, which is what {@code PatientClinicalContextBuilder} produces. Issue #118's name
	 * fallback then matches the 40mg order against the 20mg order's record, which is deliberately
	 * fail-open for the question it was written for (whether to WARN and inject) and a false claim for
	 * this one: the finding would tell the model that record IS the 40mg prescription, in text closing
	 * with a change-of-therapy call. Because the fallback answers, the reconciliation also injects no
	 * record of its own for the 40mg order, so there is nothing else it could have cited.
	 *
	 * <p>The 20mg item keeps its number, so the refusal is per item and does not fall back to
	 * silencing the clause. Found by a clean-context review agent driving the real injector.
	 */
	@Test
	public void aRecordAnotherOrderIsCannotBeCitedForThisOne() throws Exception {
		String finding = onlyFinding(
			chartNaming("order-zolvimix-20", "Zolvimix 20mg", "order-klarizom", "Klarizom"),
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Zolvimix", "Klarizom"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null,
				Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-zolvimix-20", "Zolvimix 20mg",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-zolvimix-40", "Zolvimix 40mg",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom",
						DrugReferenceTestSupport.set("Klarizom"),
						DrugReferenceTestSupport.set("J01FA09")))),
			SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix 20mg [1]; Simvastatin from Zolvimix 40mg; "
				+ "Clarithromycin from Klarizom [2].", bridgeOf(finding),
			"record [1] is the 20mg order's own record, so it cannot also be cited as the 40mg "
					+ "prescription (issue #379), was: " + finding);
	}

	/**
	 * Issue #379, the same false claim arriving from the other side: one record TWO displays would
	 * both cite is cited by neither.
	 *
	 * <p>The sibling case above is decided by the uuid leg — one of the two orders owns the record. Here
	 * the chart's record carries neither order's uuid (issue #118's drifted index), so nothing ranks
	 * the two prescriptions and the module has no basis to hand the record to either. Klarizom keeps
	 * its number, so the refusal is per item.
	 */
	@Test
	public void oneRecordTwoPrescriptionsWouldBothCiteIsCitedByNeither() throws Exception {
		String finding = onlyFinding(
			DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.drugOrderRecord(1, "stale-index-uuid", "Zolvimix"),
				DrugReferenceTestSupport.drugOrderRecord(2, "order-klarizom", "Klarizom")),
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Zolvimix", "Klarizom"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null,
				Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-zolvimix-20", "Zolvimix 20mg",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-zolvimix-40", "Zolvimix 40mg",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom",
						DrugReferenceTestSupport.set("Klarizom"),
						DrugReferenceTestSupport.set("J01FA09")))),
			SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix 20mg; Simvastatin from Zolvimix 40mg; "
				+ "Clarithromycin from Klarizom [2].", bridgeOf(finding),
			"one record cannot be two prescriptions, and nothing here ranks them, so neither item "
					+ "states a number (issue #379), was: " + finding);
	}

	/**
	 * Issue #379: the "one record, one prescription" refusal counts ORDERS and not displays.
	 *
	 * <p>Two prescriptions that also SHARE a display, both reaching one drifted-uuid record. Keyed on
	 * the display the refusal cannot see this — both claimants spell the same string — so the module
	 * asserted the record IS the order while holding two prescriptions with that display and one
	 * record: the very claim the sibling case refuses, emitted in the arrangement that carries LESS
	 * information than the one it refuses. Found by a clean-context review agent.
	 */
	@Test
	public void twoPrescriptionsSharingADisplayAndOneRecordCiteNeither() throws Exception {
		String finding = onlyFinding(
			DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.drugOrderRecord(1, "stale-index-uuid", "Zolvimix"),
				DrugReferenceTestSupport.drugOrderRecord(2, "order-klarizom", "Klarizom")),
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Zolvimix", "Klarizom"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null,
				Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-zolvimix-a", "Zolvimix",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-zolvimix-b", "Zolvimix",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom",
						DrugReferenceTestSupport.set("Klarizom"),
						DrugReferenceTestSupport.set("J01FA09")))),
			SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix; Clarithromycin from Klarizom [2].", bridgeOf(finding),
			"two orders reaching one record state no number for it, whether or not they spell the "
					+ "same display (issue #379), was: " + finding);
	}

	/**
	 * Issue #379 round two: the display-collision rule's other half — one order of a shared display
	 * resolves and its SIBLING resolves to nothing, so the item they share states no number.
	 *
	 * <p>{@link #twoOrdersOfTheSameDisplayAreNamedOnce} is the half where both orders resolve, to
	 * DIFFERENT records, and it is the only half the equality branch of that rule can see. Here only
	 * one order resolves: the chart carries record [1] under {@code order-zolvimix-a}'s own uuid, and
	 * {@code order-zolvimix-b} — a second prescription spelling the same display — reaches that same
	 * record by issue #118's name leg alone, where {@code claimedByUuid} strikes it out as order-a's
	 * own. So order-b comes back with null while order-a comes back with [1], and the clause item
	 * keyed on {@code Zolvimix} covers both prescriptions. Stating [1] on it would tell the model that
	 * record IS the Zolvimix prescription while the patient holds two of them and only one is that
	 * record — the same false claim the two cases above refuse, inside text closing with a
	 * change-of-therapy call.
	 *
	 * <p>Delete the {@code ambiguous.add(display)} on {@code orderRecordNumbers}' null branch, leaving
	 * the {@code continue}, and this is the case that reddens. Klarizom keeps its number, so the
	 * refusal is per item. Found by a clean-context review agent.
	 */
	@Test
	public void aDisplayWhoseSecondOrderCanCiteNothingStatesNoNumberEither() throws Exception {
		String finding = onlyFinding(
			chartNaming("order-zolvimix-a", "Zolvimix", "order-klarizom", "Klarizom"),
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Zolvimix", "Klarizom"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null,
				Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-zolvimix-a", "Zolvimix",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-zolvimix-b", "Zolvimix",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom",
						DrugReferenceTestSupport.set("Klarizom"),
						DrugReferenceTestSupport.set("J01FA09")))),
			SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix; Clarithromycin from Klarizom [2].", bridgeOf(finding),
			"one of the two prescriptions spelling this display is record [1] and the other is not, "
					+ "so the item they share states no number (issue #379), was: " + finding);
	}

	/**
	 * Issue #379's rendering is OFF on a stock install, and this is the case that says so.
	 *
	 * <p>The ticket's own chart, where both numbers ARE resolvable — {@link #ticketFinding} asserts
	 * exactly this arrangement WITH them — so what separates the two expectations is the property and
	 * nothing else. Set the shipped default to true and this reddens; remove the gate from
	 * {@code injectRecords} and it reddens too.
	 *
	 * <p>The clause it states is the one Decision 64 shipped, character for character. That is the
	 * additive claim the gate rests on: the withheld number takes the same per-item path an
	 * unresolvable attribution already took, so no second rendering branch exists for it to drift
	 * from.
	 */
	@Test
	public void aStockInstallStatesNoRecordNumberAtAll() throws Exception {
		citeOrderRecords(ChartSearchAiConstants.DEFAULT_DRUG_SAFETY_CITE_ORDER_RECORDS);

		String finding = onlyFinding(
			chartNaming("order-zolvimix", "Zolvimix", "order-klarizom", "Klarizom"),
			ticketChart(), SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix; Clarithromycin from Klarizom.", bridgeOf(finding),
			"the record numbers are withheld until an install asks for them, and the clause is then "
					+ "the one issue #349 shipped (issue #379), was: " + finding);
	}

	/**
	 * Issue #379 round two, the other side of the same rule: an order whose uuid the chart DOES carry
	 * contests nothing, so a sibling prescription whose own record drifted still cites it.
	 *
	 * <p>The two-doses shape again, but with a record for BOTH orders — the 20mg order's under its own
	 * uuid, the 40mg order's under a drifted one. Both orders record the brand name, so each names both
	 * records; the 20mg order is nonetheless [1] and nothing else, so it is not a rival claimant to [2]
	 * and counting its name matches into the contested set would take the 40mg order's correct number
	 * away. Drop the uuid-resolved skip in {@code DrugOrderRecords.recordsSeveralOrdersName} and this is
	 * the case that reddens.
	 */
	@Test
	public void anOrderTheChartHoldsTheUuidRecordOfDoesNotContestItsSiblingsDriftedRecord()
			throws Exception {
		String finding = onlyFinding(
			DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.drugOrderRecord(1, "order-zolvimix-20", "Zolvimix 20mg"),
				DrugReferenceTestSupport.drugOrderRecord(2, "stale-index-uuid", "Zolvimix 40mg"),
				DrugReferenceTestSupport.drugOrderRecord(3, "order-klarizom", "Klarizom")),
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Zolvimix", "Klarizom"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null,
				Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-zolvimix-20", "Zolvimix 20mg",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-zolvimix-40", "Zolvimix 40mg",
						DrugReferenceTestSupport.set("Zolvimix"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom",
						DrugReferenceTestSupport.set("Klarizom"),
						DrugReferenceTestSupport.set("J01FA09")))),
			SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix 20mg [1]; Simvastatin from Zolvimix 40mg [2]; "
				+ "Clarithromycin from Klarizom [3].", bridgeOf(finding),
			"an order the uuid leg answers for is not a claimant to any other record, so it must not "
					+ "take its sibling's drifted record away (issue #379), was: " + finding);
	}

	/**
	 * Issue #379 round two: a record two active orders NAME is cited by neither, even where only one of
	 * them resolved to it — the refusal is over the records orders are CANDIDATES for, not over the
	 * numbers they happened to resolve TO.
	 *
	 * <p>Issue #118's own drifted-uuid population, with the two prescriptions sharing a concept name
	 * rather than a brand: {@code PatientClinicalContextBuilder} records every name an order's concept
	 * publishes, so two brands of one substance both record {@code Simvastatin}. Record [1] is named by
	 * BOTH orders; record [2] by the Statibrand order alone. The Statibrand order therefore resolves to
	 * nothing (two candidates), which used to leave the Zolvimix order the only claimant to put a
	 * number in the map — so the finding stated, inside text closing with a change-of-therapy call,
	 * that record [1] IS the Zolvimix prescription, when it is equally the Statibrand one. Found by a
	 * clean-context review agent driving the real injector.
	 *
	 * <p>Klarizom keeps its number, so the refusal is still per item.
	 */
	@Test
	public void aRecordTwoOrdersNameIsCitedByNeitherEvenWhereOnlyOneOfThemResolvedIt() throws Exception {
		String finding = onlyFinding(
			DrugReferenceTestSupport.chartOf(
				DrugReferenceTestSupport.drugOrderRecord(1, "stale-index-uuid", "Simvastatin"),
				DrugReferenceTestSupport.drugOrderRecord(2, "another-stale-uuid", "Statibrand"),
				DrugReferenceTestSupport.drugOrderRecord(3, "order-klarizom", "Klarizom")),
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Zolvimix", "Statibrand", "Klarizom"),
				DrugReferenceTestSupport.set("C10AA01", "J01FA09"), null, null,
				Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-zolvimix", "Zolvimix 40mg",
						DrugReferenceTestSupport.set("Zolvimix", "Simvastatin"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-statibrand", "Statibrand 20mg",
						DrugReferenceTestSupport.set("Statibrand", "Simvastatin"),
						DrugReferenceTestSupport.set("C10AA01")),
					DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom",
						DrugReferenceTestSupport.set("Klarizom"),
						DrugReferenceTestSupport.set("J01FA09")))),
			SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix 40mg; Simvastatin from Statibrand 20mg; "
				+ "Clarithromycin from Klarizom [3].", bridgeOf(finding),
			"record [1] is reached by both simvastatin orders on the same name evidence, so neither "
					+ "may cite it however the other order's own resolution came out (issue #379), was: "
					+ finding);
	}

	/**
	 * Issue #379: the numbering is not gated on the chart claiming completeness, and an order the chart
	 * holds no record for simply has no number.
	 *
	 * <p>A query-scoped slice — the shipped default chart mode. It carries the Klarizom record but
	 * declares no resource type complete, so {@code unrepresentedActiveOrders} stands down and injects
	 * nothing for the Zolvimix order. Both halves are the point: Klarizom keeps its number even though
	 * the reconciliation did not run, which is what reddens if the completeness gate is copied onto the
	 * numbering; and Zolvimix has no candidate at all, which is not a fourth ambiguity rule.
	 */
	@Test
	public void aQueryScopedSliceStillNumbersTheRecordsItDoesCarry() throws Exception {
		PatientChart scoped = DrugReferenceTestSupport.chartOf(
			DrugReferenceTestSupport.drugOrderRecord(1, "order-klarizom", "Klarizom"));
		scoped.markQueryScoped();

		String finding = onlyFinding(scoped, ticketChart(), SCREENING_QUESTION);

		assertEquals("Simvastatin from Zolvimix; Clarithromycin from Klarizom [1].", bridgeOf(finding),
			"a record the scoped chart carries is still citable, and an order it carries none for "
					+ "states no number (issue #379), was: " + finding);
	}
}
