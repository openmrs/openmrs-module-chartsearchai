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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Issue #339: ONE response names ONE active order ONE way, and the same pair is named the same way
 * whichever question reached it.
 *
 * <p>Issue #292 gave a folded chip one name for its partner; what it did not give is agreement
 * BETWEEN chips. {@code DrugSafetyValidator.reconciledPartnerName} had exactly one call site, inside the
 * {@code classRelationships} loop, so which name an order got was decided by whether the class arm
 * happened to have a sentence about it: a rule whose partner shared a class was reconciled to the
 * ladder's clinician-facing name, and every other rule chip kept {@code partnerLabel}, the knowledge
 * base's own match token. Measured live on the 3.7.1 standalone at {@code 09717dc7}, one payload with
 * subject {@code Hydrocortisone} carried {@code active order celecoxib} / {@code diclofenac} /
 * {@code ibuprofen} beside {@code active order Dexamethasone} / {@code Prednisone} /
 * {@code Budesonide} / {@code Methylprednisolone}.
 *
 * <p>Driven through the real {@link DrugSafetyValidator#validate} over the pinned DDInter excerpt read
 * by the real {@link DdiDrugReferenceSource}, plus the real curated cross-reactivity groups — the
 * aspirin/ibuprofen pair is the one pair that excerpt trips on BOTH arms, so it is the pair that folds
 * and therefore the one whose name moved.
 */
public class OneOrderNameAcrossOneResponseTest {

	/** The two partners this class puts a patient on: one whose pair with ibuprofen also shares the
	 *  curated NSAID group (so its chip folds) and one whose pair does not (so its chip does not). */
	private static final String ASPIRIN_ORDER = "Acetylsalicylic acid";

	private static final String WARFARIN_ORDER = "Warfarin";

	private static final String IBUPROFEN_QUESTION = "Can I give her ibuprofen?";

	/** The {@code ddi-fold-ambiguous-token.json} collision with the third row moved out of the shared
	 *  subgroup, so the two arms cannot fold and the refusal is reached on the unfolded path. */
	private static final String UNFOLDED_AMBIGUOUS_TOKEN_FIXTURE =
			"chartsearchai-test/ddi-unfolded-ambiguous-token.json";

	private static final String COMBINATION_ORDER_FIXTURE =
			"chartsearchai-test/drug-reference-combination-order-two-rules.json";

	/** ADR Decision 39's own live example, verbatim. */
	private static final String COMBINATION_DISPLAY = "Isoniazid / Rifapentine";

	/** What the excerpt's aspirin ROW is called — {@code DrugReference.displayLabel()}, which appends
	 *  the diverging generic the {@code ddinter} parser read off {@code rxnorm_name}. */
	private static final String ASPIRIN_ENTRY_NAME = "Acetylsalicylic acid (aspirin)";

	private static DrugReferenceService service() {
		return DrugReferenceTestSupport.ddinterServiceWithGroups();
	}

	/**
	 * A chart carrying one active order per name, each with the ATC codes its own reference entry
	 * publishes and the chart-wide code set the dictionary would have contributed — the shape
	 * {@code PatientClinicalContextBuilder} produces for a MAPPED concept, which is what
	 * {@code orderPartners}' code walk reads.
	 */
	private static PatientClinicalContext chart(DrugReferenceService service, String... orders) {
		java.util.List<PatientClinicalContext.ActiveDrugOrder> active =
				new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		java.util.Set<String> codes = new java.util.LinkedHashSet<String>();
		java.util.Set<String> names = new java.util.LinkedHashSet<String>();
		for (String order : orders) {
			PatientClinicalContext.ActiveDrugOrder one =
					DrugReferenceTestSupport.activeOrderFor(service, order);
			active.add(one);
			codes.addAll(one.getAtcCodes());
			names.add(order);
		}
		return service.withReferenceNames(
			DrugReferenceTestSupport.ctx(60, null, names, codes, null, null, active));
	}

	/** @return every {@code active order <label>} this response printed, in chip order. */
	private static List<String> orderNames(List<SafetyWarning> warnings) {
		List<String> names = new ArrayList<String>();
		for (SafetyWarning warning : warnings) {
			String detail = warning.getDetail();
			int at = detail.indexOf("active order ");
			while (at >= 0) {
				int from = at + "active order ".length();
				int end = detail.indexOf(" — ", from);
				names.add(end < 0 ? detail.substring(from) : detail.substring(from, end));
				at = detail.indexOf("active order ", from);
			}
		}
		return names;
	}

	@Test
	public void oneResponseNamesEveryPartnerTheDatasetCoversByTheDatasetsOwnName() {
		// The ticket's shape (a), in the smallest arrangement the excerpt can make: one question, one
		// subject, two active orders. Ibuprofen and aspirin share the curated NSAID group as well as a
		// rule, so that chip FOLDS and has always been reconciled to the ladder's name; ibuprofen and
		// warfarin share only a rule, so that chip does not fold and kept the rule's own token. Two
		// conventions, one response, nothing in the text explaining why.
		DrugReferenceService service = service();
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", IBUPROFEN_QUESTION, chart(service, ASPIRIN_ORDER, WARFARIN_ORDER));

		assertEquals(2, warnings.size(), "the arrangement must raise one chip per order, was: "
				+ warnings);
		assertEquals(java.util.Arrays.asList("Warfarin", ASPIRIN_ENTRY_NAME, ASPIRIN_ENTRY_NAME),
			orderNames(warnings),
			"every chip of one response must name its active order the way the dataset names it, and "
					+ "not the knowledge base's own match token on whichever chips no class sentence "
					+ "folded onto (issue #339), was: " + warnings);
	}

	@Test
	public void theSamePairIsNamedTheSameWayWhicheverQuestionReachedIt() {
		// The ticket's shape (b), on ONE prescription: the aspirin order. The drug-in-play arm folds the
		// ibuprofen/aspirin pair — the one pair this excerpt trips on both arms — and so reconciled that
		// order to the ladder's name; the screening arm cannot fold at all, because classRelationships
		// runs per in-play substance and a screening question names none, so it kept the rule's token.
		// One prescription, two names, decided by what was asked about it.
		DrugReferenceService service = service();
		List<SafetyWarning> inPlay = DrugReferenceTestSupport.validator(service)
				.validate("", IBUPROFEN_QUESTION, chart(service, ASPIRIN_ORDER, WARFARIN_ORDER));
		List<SafetyWarning> screened = DrugReferenceTestSupport.validator(service).validate("",
				DrugReferenceTestSupport.SCREENING_QUESTION, chart(service, ASPIRIN_ORDER,
						WARFARIN_ORDER));

		assertTrue(orderNames(inPlay).contains(ASPIRIN_ENTRY_NAME),
			"precondition: the drug-in-play arm reconciles this order, was: " + inPlay);
		assertTrue(orderNames(screened).contains(ASPIRIN_ENTRY_NAME),
			"the screening arm must call one prescription what the drug-in-play arm calls it — the "
					+ "same patient, the same order, a different question (issue #339), was: "
					+ screened);
	}

	/**
	 * The gate is unchanged, so it refuses on an UNFOLDED chip exactly as it refuses on a folded one.
	 *
	 * <p>This is the safety half of issue #339 and the reason the change is a widening of WHERE the
	 * question is asked rather than of what it permits. The fixture's rule token {@code esomeprazole}
	 * is named by TWO substances — the {@code ddinter} parser writes each row's aliases from its name
	 * AND its {@code rxnorm_name}, and one row named {@code Omeprazole} carries
	 * {@code rxnorm_name: esomeprazole}, which is the shipped knowledge base's own shape — so nothing
	 * can say which of them the rule is about. Displacing the token would print one substance's rated
	 * mechanism under the other's name, the #161/#187/#194 failure. Pantoprazole sits in
	 * {@code A02BA} here rather than {@code A02BC}, so the two arms share no subgroup, nothing folds,
	 * and the refusal is reached down the path this issue opened.
	 */
	@Test
	public void aRuleWhoseTokenNamesTwoSubstancesKeepsItsOwnTokenOnAnUnfoldedChipToo() throws IOException {
		DrugSafetyValidator validator = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.serviceWith(
					DrugReferenceTestSupport.ddiFixtureEntries(UNFOLDED_AMBIGUOUS_TOKEN_FIXTURE)));

		List<SafetyWarning> warnings = validator.validate("", "Is pantoprazole safe here?",
			DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("omeprazole 20mg"),
				DrugReferenceTestSupport.set("A02BC05"), null, null));

		assertEquals(1, warnings.size(), "one chip, was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertFalse(detail.contains("is in the same"),
			"precondition: nothing may have folded, or this repeats the folded case, was: " + detail);
		assertEquals(java.util.Arrays.asList("esomeprazole"), orderNames(warnings),
			"a token two substances name cannot tell the chip which of them the rule is about, so the"
					+ " rule keeps its own token — on an unfolded chip as on a folded one, was: "
					+ detail);
	}

	/**
	 * ONE combination prescription, TWO rule chips, one name — the shape the ticket quotes as the
	 * non-cosmetic one, {@code active order Isoniazid / Rifapentine} beside {@code active order
	 * isoniazid} in a single payload.
	 *
	 * <p>What makes it the hard case is which substance the ladder named the co-medication after. The
	 * order's own combination code is not in the dataset, so {@code orderPartners} falls to
	 * {@code soleSubstanceOf}, resolves the covered code to Rifapentine and then renames that partner
	 * after the ORDER. A rule about the other half resolves the Isoniazid entry, whose substance key is
	 * not the partner's — so an index keyed on the ladder's {@code labelEntry} alone would miss it and
	 * that chip would go on printing {@code isoniazid} beside the other's
	 * {@code Isoniazid / Rifapentine}. {@code OrderPartner.substances}, what the order's own names
	 * imply, is the key that reaches it, and this case is what exercises that leg.
	 */
	@Test
	public void twoRulesAboutOneCombinationPrescriptionNameItOnce() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.fixtureEntries(COMBINATION_ORDER_FIXTURE));
		java.util.Set<String> codes = DrugReferenceTestSupport.set("J04AC51", "J04AB05");
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set(COMBINATION_DISPLAY), codes, null, null,
			java.util.Arrays.asList(DrugReferenceTestSupport.activeOrder("order-combination",
				COMBINATION_DISPLAY, DrugReferenceTestSupport.set("isoniazid / rifapentine"), codes)));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give her carbamazepine?", chart);

		assertEquals(2, warnings.size(),
			"precondition: two rules about the two halves of one prescription must chip separately —"
					+ " they key on different partner ENTRIES — or there are not two names to"
					+ " reconcile, was: " + warnings);
		assertEquals(java.util.Arrays.asList(COMBINATION_DISPLAY, COMBINATION_DISPLAY),
			orderNames(warnings),
			"one prescription, one name: a clinician scanning this list must not have to tell two"
					+ " names for one order from two orders (issue #339), was: " + warnings);
	}

	/**
	 * The ticket's own measured arrangement, over the dataset the module SHIPS.
	 *
	 * <p>Seven chips, one subject, eight active orders — the payload issue #339 opens with, in which
	 * three partners were lower-cased and four were not. The fixtures above say the change is right in
	 * the small; this says it reaches the thing that was reported, on the data an operator actually
	 * runs. Measured both ways on this arrangement: with the reconciliation disabled the three rule
	 * chips read {@code celecoxib} / {@code diclofenac} / {@code ibuprofen}, which is the ticket's
	 * payload byte for byte.
	 *
	 * <p>The class chips are asserted beside them deliberately. They have always been named this way,
	 * so they are not what moved — and that is the point: what the response now has is ONE convention,
	 * which cannot be stated by looking at the chips that changed alone.
	 */
	@Test
	public void theTicketsOwnArrangementOverTheShippedKnowledgeBaseNamesOneWay() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.shippedEntries());

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
			"Can I give her hydrocortisone?",
			chart(service, "Celecoxib", "Diclofenac", "Ibuprofen", "Dexamethasone", "Prednisone",
				"Budesonide", "Methylprednisolone"));

		assertEquals(java.util.Arrays.asList("Celecoxib", "Diclofenac", "Ibuprofen", "Dexamethasone",
			"Prednisone", "Budesonide", "Methylprednisolone"), orderNames(warnings),
			"the ticket's own seven chips must name their seven prescriptions by one convention — the"
					+ " rule arm's three were the knowledge base's own match tokens and the class arm's"
					+ " four the dataset's names, in one response, with nothing in the text explaining"
					+ " why (issue #339), was: " + DrugReferenceTestSupport.details(warnings));
	}

	/**
	 * The residue on the FLATTENED context of issue #118, pinned as current behaviour so that closing
	 * it reddens rather than passing in silence.
	 *
	 * <p>Such a context carries the chart's codes with no per-order structure, and the chip layer can
	 * still reconcile from it — {@code orderPartners} reads the flattened code set for its entry rung.
	 * The injected {@code drug_reference} note cannot: {@code DrugReferenceInjector}'s own accessor is
	 * conditioned on the context carrying orders, deliberately, because dropping that condition makes
	 * the RECORD's text depend on whether a dictionary published a prescription's ATC code or only its
	 * name — which {@code OrderDrivenInjectionResolutionTest.oneOrderInjectsOneRecordSetWhicheverWayItResolves}
	 * forbids. So on this shape the chip says {@code Warfarin} and the note says {@code warfarin}.
	 *
	 * <p>Issue #297 already accepted exactly this for a FOLDED chip on this same shape; issue #339
	 * widens the reach and not the kind. The two surfaces still name one SUBSTANCE, each in its own
	 * vocabulary, which is what {@code SafetyWarning.reconciledPartnerNoteName} says they share — and
	 * what a real patient gets is the other branch, since {@code PatientClinicalContextBuilder}
	 * attaches per-order structure for every chart it can read.
	 */
	@Test
	public void onAFlattenedChartTheChipIsReconciledAndTheNoteIsNot() {
		DrugReferenceService service = DrugReferenceTestSupport.ddinterServiceWithGroups();
		PatientClinicalContext flat = service.withReferenceNames(DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set("Warfarin"), DrugReferenceTestSupport.set("B01AA03"), null,
			null));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Is methotrexate safe here?", flat);

		assertTrue(flat.getActiveDrugOrders().isEmpty(),
			"precondition: the flattened shape carries no per-order structure");
		assertEquals(java.util.Arrays.asList("Warfarin"), orderNames(warnings),
			"the chip reconciles from the flattened code set alone, was: " + warnings);
	}

	/**
	 * The two index passes are ORDERED, and this is what says so: the substance a combination order
	 * merely CONTAINS must not take a chip away from the single-substance order of that same drug.
	 *
	 * <p>{@code CoMedications.partnerNaming} lays down a key for each partner's {@code labelEntry}
	 * substance first and lets no {@code substances} key displace one. Break that — last writer wins,
	 * or the two loops swapped — and the whole api suite stays green while BOTH chips below read
	 * {@code active order Isoniazid / Rifapentine}: the isoniazid rule is printed as being about the
	 * combination product, and the patient's actual standalone isoniazid prescription vanishes from the
	 * response. That is the #161/#187/#194 mis-attribution, in text {@code renderFinding} copies
	 * verbatim into the prompt as a citable {@code safety_finding}.
	 *
	 * <p>The case above reaches the {@code substances} pass and cannot see this: with only the
	 * combination order on the chart there is no second partner for a key to be taken from.
	 */
	@Test
	public void aCombinationOrderDoesNotTakeAChipFromTheSingleSubstanceOrderOfTheSameDrug()
			throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.fixtureEntries(COMBINATION_ORDER_FIXTURE));
		java.util.Set<String> combinationCodes = DrugReferenceTestSupport.set("J04AC51", "J04AB05");
		java.util.Set<String> isoniazidCodes = DrugReferenceTestSupport.set("J04AC01");
		java.util.Set<String> all = new java.util.LinkedHashSet<String>(combinationCodes);
		all.addAll(isoniazidCodes);
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set(COMBINATION_DISPLAY, "Isoniazid 300mg"), all, null, null,
			java.util.Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-combination", COMBINATION_DISPLAY,
					DrugReferenceTestSupport.set("isoniazid / rifapentine"), combinationCodes),
				DrugReferenceTestSupport.activeOrder("order-isoniazid", "Isoniazid 300mg",
					DrugReferenceTestSupport.set("isoniazid"), isoniazidCodes)));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give her carbamazepine?", chart);

		assertEquals(java.util.Arrays.asList("Isoniazid", COMBINATION_DISPLAY), orderNames(warnings),
			"the isoniazid rule must name the isoniazid PRESCRIPTION and the rifapentine rule the "
					+ "combination one — a partner that merely contains a substance may not speak for "
					+ "the order that IS it, was: " + warnings);
	}
}
