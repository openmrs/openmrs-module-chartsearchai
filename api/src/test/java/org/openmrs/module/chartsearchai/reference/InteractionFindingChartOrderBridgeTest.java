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
 * each substance it names was resolved from, where no name that order records names it.
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

	/** Simvastatin ({@code C10AA01}) and Clarithromycin ({@code J01FA09}) related Major, plus
	 *  Acetylsalicylic acid, whose {@code rxnorm_name} diverges from its name so that an order named
	 *  {@code Aspirin 81mg} reaches it through an ALIAS. */
	private static final String BRAND_NAMED_ORDERS = "chartsearchai-test/ddi-brand-named-orders.json";

	private static final String SCREENING_QUESTION =
			"Are any of his current medications interacting with each other?";

	/** Read off production, so no case below can pass against a clause no record carries. What pins
	 *  the WORDS is {@link #theClauseIsTheWordsAModelReads}, and only that. */
	private static final String LEAD = DrugReferenceInjector.FINDING_CHART_ORDER_LEAD;

	private static final String WITHHOLD = DrugReferenceInjector.STRENGTH_WITHHOLD;

	/** Everything the Simvastatin x Clarithromycin chip's detail says after its em dash: the rule's
	 *  rating and the fixture's mechanism prose. Spelled out rather than read off the fixture, because
	 *  two cases assert the whole string a model reads and a helper that derived it from the same source
	 *  the production code reads could not fail. */
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

	private static List<String> findings(PatientChart chart, PatientClinicalContext context,
			String question) throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(BRAND_NAMED_ORDERS);
		return DrugReferenceTestSupport.findingTexts(DrugReferenceTestSupport
				.injectorWithSafety(service).injectRecords(chart, context, question));
	}

	/** The one finding of an arrangement that must raise exactly one — asserted rather than assumed,
	 *  because every case below turns on a single record's text and a second finding would let the
	 *  wrong one answer for it. */
	private static String onlyFinding(PatientChart chart, PatientClinicalContext context,
			String question) throws IOException {
		List<String> findings = findings(chart, context, question);
		assertEquals(1, findings.size(), "one pair is one citable record, was: " + findings);
		return findings.get(0);
	}

	private static String ticketFinding() throws IOException {
		return onlyFinding(
			chartNaming("order-zolvimix", "Zolvimix", "order-klarizom", "Klarizom"),
			ticketChart(), SCREENING_QUESTION);
	}

	/** @return the bridge clause of {@code finding}, without its lead — or null where it carries none. */
	private static String bridgeOf(String finding) {
		int at = finding.indexOf(LEAD);
		if (at < 0) {
			return null;
		}
		int end = finding.indexOf(WITHHOLD, at);
		return finding.substring(at + LEAD.length(), end < 0 ? finding.length() : end);
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
		// DO carry a name of it. The guard is the order's recorded names against the substance, never
		// the string the finding prints: printed, this substance is "Acetylsalicylic acid (aspirin)",
		// which no order display contains.
		String finding = onlyFinding(
			chartNaming("order-aspirin", "Aspirin 81mg", "order-klarizom", "Klarizom"),
			DrugReferenceTestSupport.ctx(60, null,
				DrugReferenceTestSupport.set("Aspirin 81mg", "Klarizom"),
				DrugReferenceTestSupport.set("N02BA01", "J01FA09"), null, null,
				Arrays.asList(
					DrugReferenceTestSupport.activeOrder("order-aspirin", "Aspirin 81mg",
						DrugReferenceTestSupport.set("Aspirin 81mg"),
						DrugReferenceTestSupport.set("N02BA01")),
					DrugReferenceTestSupport.activeOrder("order-klarizom", "Klarizom",
						DrugReferenceTestSupport.set("Klarizom"),
						DrugReferenceTestSupport.set("J01FA09")))),
			SCREENING_QUESTION);

		assertEquals("Clarithromycin from Klarizom.", bridgeOf(finding),
			"only the brand-named order is bridged; the aspirin order's own name reaches its "
					+ "substance, was: " + finding);
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
	public void theStrengthClauseStaysSentenceFinal() throws Exception {
		String finding = ticketFinding();

		assertTrue(finding.contains(LEAD),
			"the arrangement must carry a bridge for this case to say anything, was: " + finding);
		assertTrue(finding.endsWith(WITHHOLD),
			"the call the finding states is the last word, was: " + finding);
		assertTrue(finding.indexOf(LEAD) < finding.indexOf(WITHHOLD),
			"the bridge qualifies the evidence and precedes the call, was: " + finding);
	}

	@Test
	public void theClauseIsTheWordsAModelReads() throws Exception {
		assertEquals(" This module resolved the substances named here from this patient's own active "
				+ "orders: ", LEAD, "the lead is prompt text; a reword is a behaviour change");
		assertEquals("Safety finding — Simvastatin: Simvastatin interacts with active order "
				+ "Clarithromycin — " + RATING_AND_MECHANISM + LEAD
				+ "Simvastatin from Zolvimix; Clarithromycin from Klarizom." + WITHHOLD,
			ticketFinding(), "the whole record a model reads");
	}

	@Test
	public void theChipDetailIsTheWordsItAlwaysWas() throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(BRAND_NAMED_ORDERS);
		List<SafetyWarning> chips = DrugReferenceTestSupport.validator(service)
				.validate("", SCREENING_QUESTION, ticketChart());

		assertEquals(1, chips.size(), "one pair is one chip, was: " + chips);
		assertEquals("Simvastatin interacts with active order Clarithromycin — " + RATING_AND_MECHANISM,
			chips.get(0).getDetail(),
			"the clause is prompt-facing only: the chip a clinician reads is unchanged");
	}
}
