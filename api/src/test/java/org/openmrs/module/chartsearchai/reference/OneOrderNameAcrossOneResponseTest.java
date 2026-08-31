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

	/** As above, with the rule moved onto the {@code Isoniazid} entry, so the prescription that would
	 *  supply the partner's name also names the chip's own SUBJECT — see the fixture's note. */
	private static final String SELF_NAMED_COMBINATION_FIXTURE =
			"chartsearchai-test/drug-reference-combination-order-self-named.json";

	/** As above, with the rule's subject moved INTO the partner's ATC subgroup and the second chip
	 *  subject moved out of it, so the one chip the arrangement raises is FOLDED — see the fixture's
	 *  note. */
	private static final String FOLDED_COMBINATION_FIXTURE =
			"chartsearchai-test/drug-reference-combination-order-folded.json";

	/** The fixture's combination display, which names the partner AND a second drug the question puts
	 *  in play. */
	private static final String FOLDED_COMBINATION_DISPLAY = "Paracetamol / Rifapentine";

	/** The shipped-KB counterpart of {@link #FOLDED_COMBINATION_DISPLAY}: an ordinary fixed-dose
	 *  statin/calcium-channel-blocker product, the reviewer's own arrangement at issue #339 review
	 *  round 5. */
	private static final String STATIN_COMBINATION_DISPLAY = "Amlodipine / Atorvastatin";

	/** As above, with a THIRD drug that rules on the same half, so ONE response raises two rule chips
	 *  about ONE co-medication from two different subjects — see the fixture's note. */
	private static final String TWO_SUBJECTS_COMBINATION_FIXTURE =
			"chartsearchai-test/drug-reference-combination-order-two-subjects.json";

	/** A verbatim shipped-KB slice in which ONE prescription is reached by the class arm from one
	 *  subject and by the rule arm from another — see the fixture's own note. */
	private static final String CLASS_ONLY_AND_RULE_FIXTURE =
			"chartsearchai-test/ddi-class-only-and-rule-one-partner.json";

	/** A verbatim shipped-KB slice in which ONE prescription resolves to TWO active-order reference
	 *  entries, only one of which the co-medication ladder keys a partner on — see the fixture's own
	 *  note. */
	private static final String ONE_ORDER_TWO_ORDER_ENTRIES_FIXTURE =
			"chartsearchai-test/ddi-one-order-two-order-entries.json";

	/** The presentation that slice's chart records, which is NOT the row {@code canonicalRow} elects
	 *  for that substance. */
	private static final String TOPICAL_STEROID_ORDER = "Methylprednisolone (topical)";

	/** ADR Decision 39's own live example, verbatim. */
	private static final String COMBINATION_DISPLAY = "Isoniazid / Rifapentine";

	/** A fixed-dose combination the SHIPPED knowledge base rules on from both sides, and the codes a
	 *  dictionary maps it to: the combination's own {@code C09BA03} which that data does not cover,
	 *  and the covered {@code C03AA03} its diuretic half is filed under. */
	private static final String COMBINATION_ORDER_ON_SHIPPED_KB = "Lisinopril / Hydrochlorothiazide";

	/** {@code OrderedSubjectRowTest}'s trap arrangement: a multi-row substance whose CHARTED
	 *  presentation is not the row {@code canonicalRow} elects. */
	private static final String COVID_ORDER = "Pfizer-BioNTech Covid-19 Vaccine";

	private static final String TYPHOID_ORDER = "Typhoid vaccine (live)";

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
	 * A prescription may not name a chip's partner where that same prescription names a SUBJECT this
	 * response raises chips about — or the finding reads as a drug interacting with itself and the
	 * interacting agent is gone from the lead.
	 *
	 * <p><b>Issue #339, review round 3.</b> Widening the ORDER rung to every rule chip made this
	 * reachable everywhere the rung is, and ADR Decision 61 recorded it as a fixture-only trade-off.
	 * It is not: measured through the real {@code validate} over
	 * {@code DrugReferenceTestSupport.shippedEntries()}, a patient on one
	 * {@code Lisinopril / Hydrochlorothiazide} order (codes {@code C09BA03}, {@code C03AA03}, the first
	 * of which that data covers no entry for) asked {@code "Can I give her lisinopril?"} was shown
	 * {@code Lisinopril interacts with active order Lisinopril / Hydrochlorothiazide — Moderate}, where
	 * before issue #339 it read {@code active order hydrochlorothiazide}. Fixed-dose combinations are
	 * ordinary prescriptions and the shipped knowledge base rules on the constituents of several
	 * (Isoniazid/Rifampicin, Amlodipine/Atorvastatin, Budesonide/Formoterol).
	 *
	 * <p>The chip reaches the prompt verbatim through {@code DrugReferenceInjector.renderFinding} as a
	 * citable {@code safety_finding} carrying {@code STRENGTH_WITHHOLD}, which is why it is refused
	 * rather than merely noted. Refusing returns the chip to {@code partnerLabel} — the rule's own
	 * token — which is what it printed before this issue. Since review round 5 it does so as the
	 * rung's own ANSWER rather than as a decline, so a FOLDED chip's class sentence takes that token
	 * too instead of keeping {@code classPartnerName}'s label — see
	 * {@link #aFoldedChipRefusingACombinationDisplayNamesThePrescriptionOnce}. This case is unfolded
	 * and reads the same either way.
	 *
	 * <p>It costs the ticket's own combination case nothing:
	 * {@link #twoRulesAboutOneCombinationPrescriptionNameItOnce} has subject Carbamazepine, which the
	 * order's display does not name, so both of its chips still take the display.
	 *
	 * <p><b>The refusal is asked of the RESPONSE and not of this chip</b>, which is review round 4 and
	 * is why this case no longer stands alone: asked per chip it answered differently for two chips
	 * about one co-medication, so one response named one prescription two ways — see
	 * {@link #twoSubjectsNameOneCombinationPrescriptionTheSameWay}. Mutating
	 * {@code CoMedications.displayNamesAnotherChipSubject} to always permit reddens exactly three cases
	 * — those two and {@link #aScreeningQuestionRefusesACombinationDisplayThatNamesAnotherOrdersDrug},
	 * which is the same shape reached through the screening arm — measured at this head.
	 */
	@Test
	public void aPrescriptionThatNamesTheSubjectDoesNotNameThePartner() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.fixtureEntries(SELF_NAMED_COMBINATION_FIXTURE));
		java.util.Set<String> codes = DrugReferenceTestSupport.set("J04AC51", "J04AB05");
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set(COMBINATION_DISPLAY), codes, null, null,
			java.util.Arrays.asList(DrugReferenceTestSupport.activeOrder("order-combination",
				COMBINATION_DISPLAY, DrugReferenceTestSupport.set("isoniazid / rifapentine"), codes)));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give her isoniazid?", chart);

		assertEquals(1, warnings.size(), "one chip, was: " + warnings);
		assertEquals(java.util.Arrays.asList("rifapentine"), orderNames(warnings),
			"the chip must name the drug its mechanism is about: a prescription naming the subject as"
					+ " well as the partner cannot stand in for the partner, or the finding reads as"
					+ " Isoniazid interacting with itself, was: " + warnings);
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

	/**
	 * The name a chip gives its PARTNER is the row this response names that substance by — never a
	 * sibling row the chart does not record.
	 *
	 * <p>The ladder elects its own label with {@code canonicalRow} alone
	 * ({@code entryForAtcCode}), while every other name slot in a response is elected by
	 * {@code interactionSubject}: the row the patient's own record claims most strongly, THEN
	 * {@code canonicalRow} among the rows tied on that (issue #194). Those two disagree on a
	 * multi-row substance whose charted presentation is not the canonical one — which is issue #187 —
	 * and before this case the partner slot took the ladder's answer, so a charted
	 * {@code Pfizer-BioNTech Covid-19 Vaccine} order was named {@code Tozinameran (…)} while the
	 * SCREENING arm named that same prescription by the charted row.
	 * {@code OrderedSubjectRowTest.theOrderNamedRowIsNamedWhereTheFoldCannotReachIt} pins the same
	 * property on the SUBJECT side of a chip over this same fixture; this is the partner side, which
	 * issue #339 made reachable for every rule chip.
	 *
	 * <p>Reading {@code partner.labelEntry} instead of the row {@code SubstanceSubjects} elects reddens
	 * exactly here — the whole api suite is otherwise green on that mutation.
	 */
	@Test
	public void aPartnerIsNamedByTheRowThisResponseNamesItsSubstanceBy() throws Exception {
		List<DrugReference> entries = DrugReferenceTestSupport
				.ddiFixtureEntries(DrugReferenceTestSupport.DDI_SUBSTANCE_IDENTITY);
		DrugReference charted = DrugReferenceTestSupport.row(entries, COVID_ORDER);
		DrugReferenceService service = DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_SUBSTANCE_IDENTITY);
		java.util.Set<String> codes = charted.normalizedAtcCodes();
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set(COVID_ORDER), codes, null, null,
			java.util.Arrays.asList(DrugReferenceTestSupport.activeOrder("order-covid", COVID_ORDER,
				DrugReferenceTestSupport.set(COVID_ORDER), codes)));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Is " + TYPHOID_ORDER + " safe here?", service.withReferenceNames(chart));

		assertTrue(!warnings.isEmpty(), "precondition: the pair must chip at all, was: " + warnings);
		assertEquals(java.util.Arrays.asList(charted.displayLabel()), orderNames(warnings),
			"a chip must name its partner by the row the patient's own order names — the ladder's own"
					+ " canonicalRow answer is a sibling this chart does not record, was: "
					+ DrugReferenceTestSupport.details(warnings));
	}

	/**
	 * A CLASS-ONLY chip names one prescription by the same row a RULE chip about it does.
	 *
	 * <p>Issue #339 moved every rule chip onto {@code reconciledPartnerName}, and that method elects
	 * the row it prints with {@code SubstanceSubjects.subjectOf} — the row this response names the
	 * substance by — because the ladder elects with {@code canonicalRow} alone and taking the ladder's
	 * answer at a chip site is issue #187. The class arm's own sentence went on electing the ladder's
	 * way, so on a multi-row substance whose CHARTED presentation is not the canonical row the two
	 * arms named one prescription two ways again — and in two visibly different strings rather than
	 * the case difference the ticket opens with, which is the form it calls the non-cosmetic one.
	 *
	 * <p>Measured before the fix through the real {@code validate} over the SHIPPED knowledge base on
	 * this very arrangement: {@code Prednisolone is in the same ATC class (H02AB) as active order
	 * Methylprednisolone} beside {@code Warfarin interacts with active order Methylprednisolone
	 * (topical)}, one prescription, in text {@code DrugReferenceInjector.renderFinding} copies verbatim
	 * into the prompt as a citable {@code safety_finding}. The slice reproduces it field for field.
	 *
	 * <p>Why the two arms split here rather than folding: the KB rates the prednisolone pair
	 * {@code Unknown}, which the shipped {@code minInteractionSeverity} default filters, so
	 * {@code ruleAbout} finds no rule for that partner and the class sentence stands alone; warfarin
	 * shares no subgroup with the steroid, so its Moderate rule chips with no class sentence to fold.
	 *
	 * <p>Reading {@code OrderPartner.label} at {@code classPartnerName} reddens exactly here.
	 */
	@Test
	public void aClassOnlyChipNamesAPartnerByTheSameRowARuleChipDoes() throws IOException {
		List<DrugReference> entries =
				DrugReferenceTestSupport.ddiFixtureEntries(CLASS_ONLY_AND_RULE_FIXTURE);
		DrugReference charted = DrugReferenceTestSupport.row(entries, TOPICAL_STEROID_ORDER);
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(entries);
		java.util.Set<String> codes = charted.normalizedAtcCodes();
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set(TOPICAL_STEROID_ORDER), codes, null, null,
			java.util.Arrays.asList(DrugReferenceTestSupport.activeOrder("order-steroid",
				TOPICAL_STEROID_ORDER, DrugReferenceTestSupport.set(TOPICAL_STEROID_ORDER), codes)));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service).validate("",
			"Can I give her prednisolone and warfarin?", service.withReferenceNames(chart));

		boolean classOnly = false;
		boolean rule = false;
		for (SafetyWarning warning : warnings) {
			String detail = warning.getDetail();
			if (detail.contains("is in the same") && !detail.contains("interacts with")) {
				classOnly = true;
			}
			if (detail.contains("interacts with active order")) {
				rule = true;
			}
		}
		assertTrue(classOnly, "precondition: one chip must be a class sentence standing alone, or this"
				+ " case is about the fold instead, was: " + DrugReferenceTestSupport.details(warnings));
		assertTrue(rule, "precondition: one chip must be a rule chip about that same order, was: "
				+ DrugReferenceTestSupport.details(warnings));
		assertEquals(java.util.Arrays.asList(charted.displayLabel(), charted.displayLabel()),
			orderNames(warnings),
			"both arms must name one prescription by the row this response names its substance by —"
					+ " the class arm electing with canonicalRow while the rule arm elects with"
					+ " interactionSubject names one order two ways in one response (issue #339), was: "
					+ DrugReferenceTestSupport.details(warnings));
	}

	/**
	 * A co-medication the ladder reached from a CODE with no order behind it carries no entry, and the
	 * index must skip it rather than ask it for a substance.
	 *
	 * <p>{@code orderPartners}' last rung builds {@code new OrderPartner(null, orderCode)} for a
	 * chart-wide ATC code the loaded dataset cannot name and no order carries — the flattened shape of
	 * issue #118, and an ordinary one for a dictionary code this knowledge base lacks. Its
	 * {@code labelEntry} is null. Before issue #339 no reader walked the partner list looking for a
	 * substance, so nothing dereferenced it; {@code CoMedications.partnerNaming} does, and its
	 * {@code labelEntry != null} guard is what stands between that walk and an NPE.
	 *
	 * <p>Nothing else in the suite reaches that branch — measured, 0 hits over the whole api suite —
	 * and losing the guard is not a loud failure: the pre-answer path swallows a
	 * {@code RuntimeException} into no records and no chips, while the post-answer path has no catch
	 * at all. So this case exists to make the mutation red rather than silent.
	 */
	@Test
	public void aChartCodeWithNoOrderAndNoEntryBehindItDoesNotStopTheChips() {
		DrugReferenceService service = DrugReferenceTestSupport.ddinterService();
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set("Warfarin 5mg tablet"),
			DrugReferenceTestSupport.set("ZZ99XX99"), null, null,
			java.util.Arrays.asList(DrugReferenceTestSupport.activeOrder("order-warfarin",
				"Warfarin 5mg tablet", "warfarin")));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give ibuprofen?", service.withReferenceNames(chart));

		assertEquals(1, warnings.size(),
			"the covered order must still chip beside a code the dataset cannot name and no order "
					+ "carries, was: " + warnings);
		assertEquals(java.util.Arrays.asList("Warfarin"), orderNames(warnings),
			"and it must still be named, was: " + DrugReferenceTestSupport.details(warnings));
	}

	/**
	 * One prescription, TWO active-order reference entries, one name.
	 *
	 * <p><b>Issue #339, review round 2.</b> {@code SubjectRule.partner} is
	 * {@code activeOrderEntryFor}'s answer over {@code DrugReferenceService.findForActiveOrders} — ATC
	 * ∪ NAME, and deliberately additive — while the co-medication index this chip looks that partner up
	 * in is built from {@code orderPartners}, which walks the chart's ATC CODES and then resolves the
	 * orders no code reached by name. The two sets are not the same, and where they differ the
	 * difference is one PRESCRIPTION resolving to two substances: the shipped knowledge base files
	 * {@code Ketoconazole} and {@code Levoketoconazole} as two substances (two {@code drugbank_id}s)
	 * publishing one {@code rxnorm_name} and one identical ATC list, so a chart carrying a single
	 * mapped {@code Ketoconazole} order resolves both entries while the code walk keys exactly one
	 * co-medication. A rule whose partner is the second entry found no partner in the index and kept
	 * {@code partnerLabel} — beside another chip about that same prescription which reconciled. That
	 * is the ticket's own shape (a), and it did not exist before this change: with the reconciliation
	 * disabled both chips print the token.
	 *
	 * <p>What closes it is the third rung of {@code CoMedications.partnerNaming} — the co-medication
	 * this pass attributed the CHART-recorded ATC code that admitted the entry to. Mutate
	 * {@code partnerNaming} to return the substance lookup alone and this case is the one that
	 * reddens, printing {@code active order ketoconazole} beside {@code active order Ketoconazole}.
	 *
	 * <p>The chips are asserted whole rather than counted, because what is being pinned is that the two
	 * of them agree: the first is subject Abacavir naming the entry the ladder named the co-medication
	 * after, the second subject Ketoconazole naming the entry it did not.
	 */
	@Test
	public void aPartnerEntryTheLadderKeyedNoCoMedicationOnStillNamesItsPrescriptionOnce()
			throws IOException {
		List<DrugReference> entries =
				DrugReferenceTestSupport.ddiFixtureEntries(ONE_ORDER_TWO_ORDER_ENTRIES_FIXTURE);
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(entries);
		DrugReference charted = DrugReferenceTestSupport.row(entries, "Ketoconazole");
		java.util.Set<String> codes = charted.normalizedAtcCodes();
		PatientClinicalContext chart = service.withReferenceNames(DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set("Ketoconazole"), codes, null, null,
			java.util.Arrays.asList(DrugReferenceTestSupport.activeOrder("order-ketoconazole",
				"Ketoconazole", DrugReferenceTestSupport.set("Ketoconazole"), codes))));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give her abacavir and ketoconazole?", chart);

		assertEquals(2, warnings.size(),
			"precondition: two rule chips about the one prescription, or there are not two names to"
					+ " reconcile, was: " + DrugReferenceTestSupport.details(warnings));
		assertEquals(java.util.Arrays.asList("Ketoconazole", "Ketoconazole"), orderNames(warnings),
			"one prescription resolving to two active-order entries must still be named once: a rule"
					+ " whose partner entry the co-medication ladder keyed no partner on may not keep"
					+ " the knowledge base's own match token beside a chip that reconciled (issue"
					+ " #339), was: " + DrugReferenceTestSupport.details(warnings));
	}

	/**
	 * The ORDER rung's refusal is a property of the PRESCRIPTION and this RESPONSE, never of which
	 * chip is being worded.
	 *
	 * <p><b>Issue #339, review round 4.</b> Round 3 gave that rung a second conjunct that read the
	 * chip's own SUBJECT: a prescription whose display names the subject may not stand in for the
	 * partner, or the chip reads {@code Isoniazid interacts with active order Isoniazid /
	 * Rifapentine} — a drug interacting with itself. Asked per chip, it answered differently for two
	 * chips about ONE co-medication: the subject the display happens to name fell back to
	 * {@link DrugSafetyValidator#partnerLabel} while every other subject kept the display. One
	 * response then named one prescription two ways, which is the whole of what issue #339 exists to
	 * remove, and the merge base named it one way.
	 *
	 * <p>So the question is asked of the RESPONSE's subjects — the substances the QUESTION put in
	 * play, plus the active-order substances where the screening arm will run — minus the partner's
	 * own, and the answer is memoised per co-medication for the pass
	 * ({@code CoMedications.displayNamesAnotherChipSubject}). The chip's subject is no longer an
	 * operand of {@code reconciledPartnerName} at all, which is what makes this structural rather than
	 * a second rule to keep in step.
	 *
	 * <p>Reachable on the shipped knowledge base with any fixed-dose combination whose constituents
	 * that data rules on — measured at this head on one {@code Lisinopril / Hydrochlorothiazide} order
	 * (codes {@code C09BA03}, uncovered, and {@code C03AA03}) asked
	 * {@code Can I give her lisinopril and amiodarone?}, which before the fix printed
	 * {@code active order hydrochlorothiazide} beside
	 * {@code active order Lisinopril / Hydrochlorothiazide}. The fixture reproduces it field for
	 * field so the case does not depend on a knowledge-base refresh.
	 *
	 * <p>Both chips take the TOKEN here rather than the display, which is what the merge base printed:
	 * the display names a subject of this response, so it stands in for no partner in it. The
	 * combination case that keeps its display is {@link #twoRulesAboutOneCombinationPrescriptionNameItOnce},
	 * whose subject is a third drug.
	 */
	@Test
	public void twoSubjectsNameOneCombinationPrescriptionTheSameWay() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.fixtureEntries(TWO_SUBJECTS_COMBINATION_FIXTURE));
		java.util.Set<String> codes = DrugReferenceTestSupport.set("J04AC51", "J04AB05");
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set(COMBINATION_DISPLAY), codes, null, null,
			java.util.Arrays.asList(DrugReferenceTestSupport.activeOrder("order-combination",
				COMBINATION_DISPLAY, DrugReferenceTestSupport.set("isoniazid / rifapentine"), codes)));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give her isoniazid and carbamazepine?", chart);

		assertEquals(2, warnings.size(),
			"precondition: two subjects must chip about the one co-medication, or there are not two"
					+ " names to reconcile, was: " + DrugReferenceTestSupport.details(warnings));
		assertEquals(java.util.Arrays.asList("rifapentine", "rifapentine"), orderNames(warnings),
			"one prescription, one name: a refusal that reads the chip's own subject names it one way"
					+ " for the subject the display names and another for every other subject in the"
					+ " same response (issue #339), was: "
					+ DrugReferenceTestSupport.details(warnings));
	}

	/**
	 * The same arrangement over the dataset the module SHIPS, so the case does not rest on a fixture
	 * alone.
	 *
	 * <p>ADR Decision 61 first recorded the self-interaction shape as fixture-only, which review round
	 * 3 corrected; this pins the correction. One {@code Lisinopril / Hydrochlorothiazide} order,
	 * codes {@code C09BA03} (which the shipped data does not cover, so {@code soleSubstanceOf} falls
	 * through to the covered one) and {@code C03AA03}, asked about one of its own constituents beside
	 * a third drug. Measured at this head before the fix, the two chips read
	 * {@code Lisinopril interacts with active order hydrochlorothiazide} and
	 * {@code Amiodarone interacts with active order Lisinopril / Hydrochlorothiazide} — one
	 * prescription, two names, in text {@code DrugReferenceInjector.renderFinding} copies verbatim
	 * into the prompt as a citable {@code safety_finding}.
	 *
	 * <p>Asserted as agreement rather than against a literal, because which of the two names survives
	 * is the fixture case's business ({@link #twoSubjectsNameOneCombinationPrescriptionTheSameWay})
	 * and a knowledge-base refresh may move the row. What may not move is that the response uses one
	 * name for one prescription.
	 */
	@Test
	public void aCombinationOrderOnTheShippedKnowledgeBaseIsNamedOneWay() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.shippedEntries());
		java.util.Set<String> codes = DrugReferenceTestSupport.set("C09BA03", "C03AA03");
		PatientClinicalContext chart = service.withReferenceNames(DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set(COMBINATION_ORDER_ON_SHIPPED_KB), codes, null, null,
			java.util.Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-combination",
					COMBINATION_ORDER_ON_SHIPPED_KB,
					DrugReferenceTestSupport.set(COMBINATION_ORDER_ON_SHIPPED_KB), codes))));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give her lisinopril and amiodarone?", chart);

		assertEquals(2, warnings.size(),
			"precondition: the shipped data must rule on this prescription from both subjects, or"
					+ " there are not two names to reconcile, was: "
					+ DrugReferenceTestSupport.details(warnings));
		List<String> names = orderNames(warnings);
		assertEquals(2, names.size(), "precondition: both chips must name the active order, was: "
				+ DrugReferenceTestSupport.details(warnings));
		assertEquals(names.get(0), names.get(1),
			"one prescription, one name, on the data an operator actually runs (issue #339), was: "
					+ DrugReferenceTestSupport.details(warnings));
	}

	/**
	 * The SCREENING arm keeps issue #339's name for an ordinary prescription, which is what the
	 * response-level refusal's own-substance exclusion is for.
	 *
	 * <p>{@code CoMedications.displayNamesAnotherChipSubject} refuses an order-supplied display that
	 * names a substance this response raises chips about — but a prescription names the very drug it
	 * IS, and in this arm every active-order substance is a chip subject. So the partner's own
	 * substance is excluded from the comparison; without that exclusion this arm would refuse every
	 * order whose display names its own drug, i.e. every order, and issue #339 would reach none of the
	 * screening arm's chips.
	 *
	 * <p>Nothing else in the suite sees that exclusion: mutating it away leaves all 1656 api tests
	 * green, measured at this head. This case exists to make it red rather than silent.
	 *
	 * <p>The rifapentine order is partly covered — one code the fixture does not carry, one it does —
	 * so it reaches the ORDER rung at all; a fully covered order is named by the ENTRY rung, where no
	 * prescription display is in play. The carbamazepine order is covered, so the pair chips from the
	 * subject the rule is filed on.
	 */
	@Test
	public void theScreeningArmStillNamesAnOrderByItsOwnDisplay() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.fixtureEntries(COMBINATION_ORDER_FIXTURE));
		java.util.Set<String> carbamazepineCodes = DrugReferenceTestSupport.set("N03AF01");
		java.util.Set<String> rifapentineCodes = DrugReferenceTestSupport.set("J04AC51", "J04AB05");
		java.util.Set<String> all = new java.util.LinkedHashSet<String>(carbamazepineCodes);
		all.addAll(rifapentineCodes);
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set("Carbamazepine 200mg", "Rifapentine 300mg"), all, null, null,
			java.util.Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-carbamazepine", "Carbamazepine 200mg",
					DrugReferenceTestSupport.set("carbamazepine"), carbamazepineCodes),
				DrugReferenceTestSupport.activeOrder("order-rifapentine", "Rifapentine 300mg",
					DrugReferenceTestSupport.set("rifapentine"), rifapentineCodes)));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", DrugReferenceTestSupport.SCREENING_QUESTION,
					service.withReferenceNames(chart));

		assertEquals(java.util.Arrays.asList("Rifapentine 300mg"), orderNames(warnings),
			"the screening arm must name a prescription by its own display: the refusal that keeps a"
					+ " combination product from standing in for one of its halves may not fire on an"
					+ " order that names the drug it IS (issue #339), was: "
					+ DrugReferenceTestSupport.details(warnings));
	}

	/**
	 * A FOLDED chip whose ORDER rung refuses the prescription's display still names that prescription
	 * ONCE — issue #292's invariant, held against the refusal reason issue #339 added.
	 *
	 * <p><b>Issue #339, review round 5.</b> The round-3/4 conjunct
	 * ({@code CoMedications.displayNamesAnotherChipSubject}) is applied at
	 * {@code DrugSafetyValidator.reconciledPartnerName}'s ORDER rung, which the FOLD calls directly.
	 * Answering null there left the rule sentence on {@code partnerLabel} while the class sentence
	 * kept {@code classPartnerName}'s label, so ONE detail read
	 * {@code Rifampicin interacts with active order rifapentine … Rifampicin is in the same ATC class
	 * (J04AB) as active order Paracetamol / Rifapentine} — two names for one prescription, inside one
	 * chip, where the merge base printed the display in both. That is a refusal reason issue #292
	 * never had, so arrangements that reconciled before issue #339 had started to split, and the chip
	 * reaches the prompt verbatim through {@code DrugReferenceInjector.renderFinding} as a citable
	 * {@code safety_finding} carrying {@code STRENGTH_WITHHOLD}.
	 *
	 * <p>The rung therefore reconciles onto the rule's own TOKEN rather than declining — outcome 1's
	 * shape, reached for a different reason — so both sentences take the one name every UNFOLDED chip
	 * about this co-medication already prints. Mutating that branch back to {@code null} reddens this
	 * case and {@link #aFoldedChipOnTheShippedKnowledgeBaseNamesTheCombinationOnce}, and nothing else
	 * in the api suite.
	 *
	 * <p>The fixture is built so the arrangement is exactly ONE chip: {@code Paracetamol} carries no
	 * rule and shares no subgroup with the order's codes, so it is a chip SUBJECT (which is what makes
	 * the refusal fire) without raising a chip whose own naming could be confused with this one's.
	 */
	@Test
	public void aFoldedChipRefusingACombinationDisplayNamesThePrescriptionOnce() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.fixtureEntries(FOLDED_COMBINATION_FIXTURE));
		java.util.Set<String> codes = DrugReferenceTestSupport.set("J04AC51", "J04AB05");
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set(FOLDED_COMBINATION_DISPLAY), codes, null, null,
			java.util.Arrays.asList(DrugReferenceTestSupport.activeOrder("order-combination",
				FOLDED_COMBINATION_DISPLAY, DrugReferenceTestSupport.set("rifapentine"), codes)));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give her rifampicin and paracetamol?", chart);

		assertEquals(1, warnings.size(), "one chip, was: "
				+ DrugReferenceTestSupport.details(warnings));
		assertTrue(warnings.get(0).getDetail().contains("same ATC class"),
			"precondition: the class arm must fold onto this chip, or there is only one sentence to"
					+ " name the order and the case cannot see the defect, was: "
					+ DrugReferenceTestSupport.details(warnings));
		assertEquals(java.util.Arrays.asList("rifapentine", "rifapentine"), orderNames(warnings),
			"one chip must not contradict itself: a refusal reason issue #292 did not have may not"
					+ " leave the rule sentence on the knowledge base's match token while the folded"
					+ " class sentence keeps the prescription's display (issue #339), was: "
					+ DrugReferenceTestSupport.details(warnings));
	}

	/**
	 * The same arrangement over the dataset the module SHIPS, and over the ordinary fixed-dose product
	 * the issue #339 review round 5 finding was measured on.
	 *
	 * <p>One {@code Amlodipine / Atorvastatin} order, codes {@code C10BX03} (which the shipped data
	 * does not cover, so {@code soleSubstanceOf} falls through to the covered one) and {@code C10AA05},
	 * asked about one of its own constituents beside a third statin. Measured at this head before the
	 * fix, the FOLDED chip read {@code Simvastatin interacts with active order atorvastatin — Moderate.
	 * … Simvastatin is in the same ATC class (C10AA) as active order Amlodipine / Atorvastatin}.
	 *
	 * <p>Asserted as agreement rather than against a literal, for the reason
	 * {@link #aCombinationOrderOnTheShippedKnowledgeBaseIsNamedOneWay} gives: which of the two names
	 * survives is the fixture case's business and a knowledge-base refresh may move the row. What may
	 * not move is that one chip uses one name for one prescription — and that the display, which the
	 * response has refused for this co-medication, is not one of them.
	 */
	@Test
	public void aFoldedChipOnTheShippedKnowledgeBaseNamesTheCombinationOnce() throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.shippedEntries());
		java.util.Set<String> codes = DrugReferenceTestSupport.set("C10BX03", "C10AA05");
		PatientClinicalContext chart = service.withReferenceNames(DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set(STATIN_COMBINATION_DISPLAY), codes, null, null,
			java.util.Arrays.asList(DrugReferenceTestSupport.activeOrder("order-combination",
				STATIN_COMBINATION_DISPLAY, DrugReferenceTestSupport.set(STATIN_COMBINATION_DISPLAY),
				codes))));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", "Can I give her amlodipine and simvastatin?", chart);

		SafetyWarning folded = null;
		for (SafetyWarning warning : warnings) {
			if (warning.getDetail().contains("same ATC class")) {
				folded = warning;
			}
		}
		assertTrue(folded != null,
			"precondition: the shipped data must fold a class sentence onto a rule chip about this"
					+ " prescription, or there are not two sentences to disagree, was: "
					+ DrugReferenceTestSupport.details(warnings));
		List<String> names = orderNames(java.util.Arrays.asList(folded));
		assertEquals(2, names.size(),
			"precondition: both sentences of the folded chip must name the active order, was: "
					+ folded.getDetail());
		assertEquals(names.get(0), names.get(1),
			"one chip, one name for one prescription, on the data an operator actually runs (issue"
					+ " #339), was: " + folded.getDetail());
		assertFalse(names.contains(STATIN_COMBINATION_DISPLAY),
			"the display names a substance this response chips about, so it stands in for no partner"
					+ " in it — the refusal must still hold, and hold for BOTH sentences (issue #339),"
					+ " was: " + folded.getDetail());
	}

	/**
	 * The SCREENING arm's own subjects are in the refusal's set, or the arm it cannot fold in is the
	 * one arm that goes on printing a self-interaction.
	 *
	 * <p>{@code chipSubjectRows} is built from the QUESTION's drugs — the set neither validate pass
	 * can disagree about — and a screening question resolves none by that arm's own gate. So the
	 * active-order rows are added where the screen will run, because there the SUBJECTS are the
	 * patient's own prescriptions. Without them the set is empty for every screening question and the
	 * refusal is vacuous: this chip then reads {@code Isoniazid interacts with active order Isoniazid
	 * / Rifapentine}, one drug interacting with itself, which is what review round 3 closed for the
	 * drug-in-play arm. Mutate the {@code screening ?} conjunct to the empty list and this case is the
	 * one that reddens — measured at this head, the rest of the api suite stays green on it.
	 *
	 * <p>The combination order's match tokens name only its covered half, so the isoniazid subject
	 * resolves from the standalone order alone and {@code activeOrdersOtherThan} leaves the
	 * combination one to witness the pair. Its DISPLAY still names both halves, which is the whole
	 * arrangement.
	 */
	@Test
	public void aScreeningQuestionRefusesACombinationDisplayThatNamesAnotherOrdersDrug()
			throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport.serviceWith(
			DrugReferenceTestSupport.fixtureEntries(SELF_NAMED_COMBINATION_FIXTURE));
		java.util.Set<String> isoniazidCodes = DrugReferenceTestSupport.set("J04AC01");
		java.util.Set<String> combinationCodes = DrugReferenceTestSupport.set("J04AC51", "J04AB05");
		java.util.Set<String> all = new java.util.LinkedHashSet<String>(isoniazidCodes);
		all.addAll(combinationCodes);
		PatientClinicalContext chart = DrugReferenceTestSupport.ctx(60, null,
			DrugReferenceTestSupport.set("Isoniazid 300mg", COMBINATION_DISPLAY), all, null, null,
			java.util.Arrays.asList(
				DrugReferenceTestSupport.activeOrder("order-isoniazid", "Isoniazid 300mg",
					DrugReferenceTestSupport.set("isoniazid"), isoniazidCodes),
				DrugReferenceTestSupport.activeOrder("order-combination", COMBINATION_DISPLAY,
					DrugReferenceTestSupport.set("rifapentine"), combinationCodes)));

		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
				.validate("", DrugReferenceTestSupport.SCREENING_QUESTION,
					service.withReferenceNames(chart));

		assertEquals(java.util.Arrays.asList("rifapentine"), orderNames(warnings),
			"a screened chip must name the drug its mechanism is about: a prescription naming another"
					+ " order's drug as well as the partner cannot stand in for the partner, or the"
					+ " finding reads as Isoniazid interacting with itself (issue #339), was: "
					+ DrugReferenceTestSupport.details(warnings));
	}
}
