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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;

/**
 * Issue #290, the builder half: an active drug order the module cannot NAME still has to reach the
 * safety layer as one order.
 *
 * <p>Before the fix {@code PatientClinicalContextBuilder} contributed such an order's ATC codes to the
 * flattened union and then skipped the order itself, so {@code DrugSafetyValidator.orderPartners} keyed
 * each of its codes on the raw code string ({@code identity = order != null ? order : (Object) orderCode})
 * and ONE prescription became one duplicate-therapy chip per code — each named by whatever the reference
 * data could resolve that code to alone, which is an unlabelled code only where the dataset carries none
 * of them. That is this file's arrangement, since the curated seed carries neither M01AE code; on the
 * shipped knowledge base a covered code took the entry rung and the chip named the substance.
 *
 * <p>Context-sensitive on purpose, and driven through the real
 * {@link PatientClinicalContextBuilder#build(Patient)} then the real
 * {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)}: the defect is in what
 * the builder puts into the context, so a hand-built {@code ActiveDrugOrder} would bypass the entire
 * change. Patient 7's single active drug order (order 111, drug "ASPIRIN", concept 88) is the
 * arrangement, made nameless the only way the platform allows.
 *
 * <p><b>Why the names are voided by SQL and the ATC map is not.</b> {@code Concept.getName()} returns
 * null only for a concept none of whose non-voided names is a preferred name, a fully specified name or
 * a synonym. No count is given: the builder's own comment declines to enumerate the shapes, and a
 * SHORT-typed name is one this file's earlier "two shapes" wording missed. No such concept can be
 * SAVED: {@code ConceptValidator} rejects a concept with no fully specified name, and
 * {@code Concept.addName} coerces the first name added to {@code FULLY_SPECIFIED}, so the index-term
 * route cannot even be built. The order is therefore made nameless after the fact. The ATC mapping is
 * applied FIRST, through the real {@code ConceptService}, while the concept still has its name and
 * validation passes; only then are the names voided. Concept 88 carries TWO names — the FSN "ASPIRIN"
 * and the synonym "ASA" — and both must go, because {@code getName()} falls back to any synonym in any
 * locale before it returns null.
 */
public class NamelessActiveOrderPartnerTest extends BaseModuleContextSensitiveTest {

	/** Concept 88 (ASPIRIN) — the concept behind patient 7's single active drug order, order 111. */
	private static final int ORDERED_CONCEPT = 88;

	/** Two codes in ONE ATC subgroup, neither carried by the curated seed, so both are unnameable and
	 *  both share subgroup {@code M01AE} with the seed's ibuprofen entry ({@code M01AE01}). */
	private static final String NAPROXEN_ATC = "M01AE02";

	private static final String KETOPROFEN_ATC = "M01AE04";

	/** Aspirin's {@code N02BA01} — covered by the bundled DDInter sample — beside an uncovered code in
	 *  the same subgroup, which is the partly-covered shape. Both fall under the curated NSAID group's
	 *  {@code N02BA} prefix, and the subject ibuprofen under its {@code M01AE}. */
	private static final String ASPIRIN_ATC = "N02BA01";

	private static final String UNCOVERED_NSAID_ATC = "N02BA99";

	private static final String QUESTION = "Can I give ibuprofen?";

	private DrugSafetyValidator validator;

	private Patient patient;

	@BeforeEach
	public void setUp() {
		Context.getAdministrationService()
				.setGlobalProperty(ChartSearchAiConstants.GP_DRUG_REFERENCE_ENABLED, "true");
		validator = DrugReferenceTestSupport.validator(DrugReferenceTestSupport.curatedService());
		patient = Context.getPatientService().getPatient(7);
	}

	private void mapOrderedConceptToAtc(String... codes) {
		DrugReferenceTestSupport.mapConceptToAtc(ORDERED_CONCEPT, codes);
	}

	/**
	 * Makes order 111 unnameable: its drug reference is cleared, the free text a clinician would have
	 * typed for a non-coded order is cleared, and every name of its concept is voided — which is exactly
	 * the state {@code addDrugName} finds nothing in, since those three are every source it reads.
	 *
	 * <p>The {@code drug_non_coded} clear is not redundant even though the standard test dataset leaves
	 * that column null on order 111: since issue #293 that column is a name source, so leaving it to the
	 * dataset would make this arrangement CONTINGENT on data this file does not control — a later
	 * dataset carrying free text there would retire the whole arrangement silently, the same way this
	 * file's own javadoc records the concept-name synonym fallback nearly doing.
	 */
	private void makeTheOrderNameless() {
		Context.getAdministrationService().executeSQL("update drug_order set drug_inventory_id = null,"
				+ " drug_non_coded = null where order_id = 111", false);
		Context.getAdministrationService()
				.executeSQL("update concept_name set voided = 1 where concept_id = " + ORDERED_CONCEPT,
					false);
		Context.flushSession();
		Context.clearSession();
	}

	private static List<String> atcClassChipDetails(List<SafetyWarning> warnings) {
		List<String> out = new ArrayList<String>();
		for (SafetyWarning w : warnings) {
			if (w.getDetail() != null && w.getDetail().contains("is in the same ATC class")) {
				out.add(w.getDetail());
			}
		}
		return out;
	}

	@Test
	public void aNamelessOrderIsOneCoMedicationAndNotOnePerCode() {
		mapOrderedConceptToAtc(NAPROXEN_ATC, KETOPROFEN_ATC);
		makeTheOrderNameless();

		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		List<SafetyWarning> warnings = validator.validate("", QUESTION, context);

		assertEquals(1, context.getActiveDrugOrders().size(),
				"the nameless order must still reach the per-order list — its codes reach the flattened"
						+ " union either way, and a code with no order behind it is the defect, was: "
						+ context.getActiveDrugOrders());
		assertEquals(
				java.util.Arrays.asList("Ibuprofen is in the same ATC class (M01AE) as active order"
						+ " [ATC M01AE02, M01AE04] — possible duplicate therapy"),
				atcClassChipDetails(warnings),
				"ONE chip for ONE prescription, naming the order by its codes labelled as codes, was: "
						+ atcClassChipDetails(warnings));
	}

	@Test
	public void thePlaceholderDisplayNeverBecomesAMatchableName() {
		mapOrderedConceptToAtc(NAPROXEN_ATC, KETOPROFEN_ATC);
		makeTheOrderNameless();

		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);

		assertEquals(1, context.getActiveDrugOrders().size(),
				"precondition: the nameless order must reach the per-order list before anything can be"
						+ " asserted about its names — without this the case below fails as an"
						+ " IndexOutOfBoundsException instead of as its own assertion, was: "
						+ context.getActiveDrugOrders());
		PatientClinicalContext.ActiveDrugOrder order = context.getActiveDrugOrders().get(0);
		assertTrue(order.getNames().isEmpty(),
				"the synthesized display must not become a match token — this set is lowercased and"
						+ " matched against chart prose, so a code in it would match free text, was: "
						+ order.getNames());
		assertTrue(context.getActiveDrugNames().isEmpty(),
				"and it must not reach the flattened name set either, was: "
						+ context.getActiveDrugNames());
	}

	/**
	 * The partly-covered nameless order, and the one thing the fallback display must NOT do.
	 *
	 * <p>One ATC code the dataset names (aspirin's {@code N02BA01}) beside one it does not. Both codes
	 * merge onto a single partner through {@code soleSubstanceOf} — so this is still ONE chip for one
	 * prescription, which is the defect issue #290 fixes — but the synthesized {@code [ATC …]} display
	 * must not displace the real drug name, because it is the ABSENCE of a name rather than the order's
	 * own.
	 *
	 * <p>Measured, and the reason the guard in {@code orderPartners} exists: without it the issue #88
	 * fold produced ONE detail reading "interacts with active order aspirin … is in the same
	 * cross-reactivity group (NSAID) as active order [ATC N02BA01, N02BA99]" — the same order named two
	 * ways in one sentence, because the rule arm then named its partner from the RULE's own token
	 * ({@code partnerLabel}) and nothing the builder supplies could reach it. Since issue #292 that is no
	 * longer the mechanism this arrangement sits on: it folds through {@code reconciledPartnerName}'s entry
	 * path, so the rule arm names the partner {@code Acetylsalicylic acid (aspirin)} too. What this case
	 * still pins is unchanged and is the reason it is here — the synthesized {@code [ATC …]} display must
	 * not displace the dataset's name, whichever arm renders it.
	 *
	 * <p>What this deliberately accepts is recorded on the guard: where the shared class is matched
	 * through the unnameable code alone, keeping the covered substance's name gives a chip whose stated
	 * class need not classify the drug it names (issue #161's shape). That fault is narrower than the
	 * self-contradiction above, which was demonstrated rather than reasoned about.
	 */
	@Test
	public void aPartlyCoveredNamelessOrderKeepsTheDatasetNameAndStillRaisesOneChip() {
		DrugSafetyValidator withGroups = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddinterServiceWithGroups());
		mapOrderedConceptToAtc(ASPIRIN_ATC, UNCOVERED_NSAID_ATC);
		makeTheOrderNameless();

		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		List<SafetyWarning> warnings = withGroups.validate("", QUESTION, context);

		assertEquals(1, warnings.size(),
				"ONE chip for one prescription — before issue #290 the covered code and the uncovered one"
						+ " were two partners, was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertTrue(detail.contains("as active order Acetylsalicylic acid (aspirin) — possible additive"),
				"the class arm must keep the name the dataset supplies, was: " + detail);
		assertTrue(!detail.contains("[ATC"),
				"and the synthesized display must never displace it — a code list is the absence of a"
						+ " name, and the fold would then name one order two ways in one sentence, was: "
						+ detail);
	}

	/**
	 * The contract the {@code hasKnownName()} flag exists to state, and the implementation it rules out.
	 *
	 * <p>The guard was first written as {@code !order.getNames().isEmpty()} — a PROXY for "the display
	 * was synthesized". The public constructor lets a caller supply a real display with no match tokens,
	 * a shape the builder never produces but a hand-built context can, and under the proxy such
	 * an order would silently lose its name on the chip: the inverse of issue #155's ladder.
	 *
	 * <p>The arrangement has to be PARTLY covered for that to be visible, which is the whole difficulty.
	 * With one uncovered code the partner is keyed on the order and already labelled from the display at
	 * the construction site, so {@code nameByOrder} is a no-op re-set and both guard expressions agree —
	 * an earlier version of this case was written that way and was measured not to discriminate at all.
	 * Here {@code N02BA01} resolves to the dataset's aspirin row, so the partner is created with the
	 * ENTRY's name and only {@code nameByOrder} can replace it: under {@code hasKnownName()} the chip
	 * reads "Aspirin 81mg", under the proxy it stays "Acetylsalicylic acid (aspirin)".
	 */
	@Test
	public void aRealDisplayWithNoMatchTokensStillOutranksTheDatasetName() {
		DrugSafetyValidator withGroups = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddinterServiceWithGroups());
		PatientClinicalContext.ActiveDrugOrder noTokens = new PatientClinicalContext.ActiveDrugOrder(
				"order-uuid-no-tokens", "Aspirin 81mg", java.util.Collections.<String> emptySet(),
				DrugReferenceTestSupport.set(ASPIRIN_ATC, UNCOVERED_NSAID_ATC));

		List<SafetyWarning> warnings = withGroups.validate("", QUESTION,
				DrugReferenceTestSupport.ctx(60, null, null,
						DrugReferenceTestSupport.set(ASPIRIN_ATC, UNCOVERED_NSAID_ATC), null, null,
						java.util.Arrays.asList(noTokens)));

		assertEquals(1, warnings.size(), "one order, one chip, was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertTrue(detail.contains("as active order Aspirin 81mg — possible additive"),
				"a display the caller supplied is a NAME and must outrank the dataset's, however few"
						+ " match tokens the order carries, was: " + detail);
		assertTrue(!detail.contains("Acetylsalicylic acid"),
				"if the dataset's name survives here the guard has been re-expressed as the names-empty"
						+ " proxy, which is the implementation this case rules out, was: " + detail);
	}

	/**
	 * The consequence that is not a relabel: a nameless order can no longer witness its OWN
	 * interaction.
	 *
	 * <p>Issue #132's screening arm excludes a subject's own order from witnessing a pair, and it does
	 * that per ORDER. A nameless order was absent from the per-order list, so the exclusion could not
	 * see it and only the flattened fallback applied — which, as
	 * {@code DrugSafetyValidator.activeOrdersOtherThan}'s javadoc says, cannot tell "one order carrying
	 * two codes" from "two orders each carrying one". One prescription mapped to two interacting entries
	 * was therefore reported as an interacting pair off a single tablet.
	 *
	 * <p>This is {@code ActiveOrderAtcContextTest.oneOrderMappedToTwoInteractingEntriesRaisesNothingEndToEnd}'s
	 * arrangement — Simvastatin {@code C10AA01} x Clarithromycin {@code J01FA09}, a real Major row in
	 * the bundled DDInter sample — with the order made nameless, which is the case that test could not
	 * reach. A false positive removed, not a wording change: reverting only the builder change makes
	 * this case report "Simvastatin interacts with active order clarithromycin — Major".
	 */
	@Test
	public void aNamelessOrderNoLongerWitnessesItsOwnInteraction() {
		DrugSafetyValidator ddinter = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddinterService());
		mapOrderedConceptToAtc("C10AA01", "J01FA09");
		makeTheOrderNameless();

		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		List<SafetyWarning> warnings = ddinter.validate("",
				DrugReferenceTestSupport.SCREENING_QUESTION, context);

		List<String> interactions = new ArrayList<String>();
		for (SafetyWarning w : warnings) {
			if (SafetyWarning.TYPE_INTERACTION.equals(w.getType())) {
				interactions.add(w.getDetail());
			}
		}
		assertTrue(interactions.isEmpty(),
				"one prescription cannot interact with itself — the per-order exclusion of issue #132"
						+ " needs the order to BE in the per-order list, was: " + interactions);
	}

	/**
	 * The other half of the guard, and the part the old skip was right about: an order with no name AND
	 * no usable ATC code is still left out.
	 *
	 * <p>Nothing could name such an order and no chip can be raised for it, so adding it would only put
	 * an unnameable line in front of a clinician — {@code DrugReferenceInjector.renderActiveOrder} would
	 * render "Active drug order: [ATC ]." into the chart. Patient 7's order with its concept left
	 * unmapped is that shape.
	 *
	 * <p>Like {@code aRealDisplayWithNoMatchTokensStillOutranksTheDatasetName} and unlike the other six,
	 * this case passes on pre-change code as well — it pins behaviour the change KEEPS, not behaviour it
	 * introduces. That is the point of it: the skip was right about this input and had to survive a
	 * change whose whole subject is removing the skip.
	 *
	 * <p>There is no second, blank-code shape for this case to miss: {@code addAtcCodes} appends through
	 * {@code addRaw}, which drops blank and null values, so a blank ATC code never enters the order's
	 * code set in the first place and the normalized set is empty exactly when the raw one is. An
	 * earlier version of this javadoc claimed such an input existed but was unconstructible; it does not
	 * exist.
	 */
	@Test
	public void anOrderWithNeitherNameNorUsableCodeIsStillLeftOut() {
		makeTheOrderNameless();

		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);

		assertTrue(context.getActiveDrugOrders().isEmpty(),
				"with no name and nothing to label it by, the order must not reach the list at all,"
						+ " was: " + context.getActiveDrugOrders());
		assertTrue(context.getActiveDrugAtcCodes().isEmpty(),
				"precondition: the concept was left unmapped, so there are no codes either, was: "
						+ context.getActiveDrugAtcCodes());
	}

	/**
	 * What the MODEL is handed for such an order, which is the half {@code validate} never executes.
	 *
	 * <p>The code-only display is not only a chip label: {@code DrugReferenceInjector.renderActiveOrder}
	 * puts it into the chart as a citable record, so this pins the exact string a clinician and the
	 * model both read. Before issue #290 such an order DID reach the chips — one per code, which is the
	 * defect this class exists for — but never this record, because it was absent
	 * from the list the reconciliation walks. So the model could deny a medication the chart held, which
	 * is the #118 divergence the reconciliation exists to repair.
	 *
	 * <p><b>The exposure this records rather than fixes.</b> {@code RESOURCE_TYPE_ACTIVE_DRUG_ORDER}
	 * groups as {@code REFERENCE_GROUP_CHART}, so unlike {@code drug_reference} and
	 * {@code safety_finding} its grounding verdict IS published on the wire. A record naming no drug
	 * cannot entail a medication claim, so a citation of this record can be graded and published
	 * {@code grounded=false} — reaching a client as "Unsupported". That is a new exposure, and it is
	 * accepted here only because the alternative is the order being invisible: the module denying a
	 * prescription the chart records is worse than substantiating it with a code. Issue #290 carries it
	 * forward.
	 */
	@Test
	public void theCodeOnlyDisplayIsWhatReachesTheChartAsACitableRecord() {
		mapOrderedConceptToAtc(NAPROXEN_ATC, KETOPROFEN_ATC);
		makeTheOrderNameless();

		PatientClinicalContext context = PatientClinicalContextBuilder.build(patient);
		PatientChart result = DrugReferenceTestSupport.injector(DrugReferenceTestSupport.curatedService())
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context,
					"what are her active medications?");

		List<RecordMapping> orders = DrugReferenceTestSupport.injectedActiveOrders(result);
		assertEquals(1, orders.size(),
				"the unrepresented order must be injected as exactly one record: " + result.getText());
		assertEquals("Active drug order: [ATC M01AE02, M01AE04].", orders.get(0).getText());
		assertEquals("e1f95924-697a-11e3-bd76-0800271c1b75", orders.get(0).getResourceUuid(),
				"and it must carry the real Order uuid — with no names, uuid is the ONLY way the"
						+ " reconciliation can substantiate this order");
	}

	/**
	 * The consequence of putting a nameless order into the list that only shows up when the patient has
	 * TWO orders: a code-only order must not win the right to NAME a partner away from a named one.
	 *
	 * <p>{@code orderCarrying} picks one carrier of a dataset-unnameable code and that pick decides the
	 * partner's label and identity. Before issue #290 every carrier had a name, so taking the first was
	 * the presentation choice {@code ordersCarrying}'s javadoc calls it. A code-only order breaks that:
	 * here {@code N02BA99} is carried both by a nameless order and by a named, partly-covered one, and
	 * taking the first would key the partner on the nameless order and label it {@code [ATC N02BA99]} —
	 * losing "Aspirin 81mg", and losing it as a function of nothing but the sequence
	 * {@code OrderService} returned the prescriptions in. So the nameless order is listed FIRST here on
	 * purpose; that is the ordering under which the defect appears.
	 *
	 * <p>Hand-built context deliberately: the defect is in {@code orderPartners}' choice among carriers,
	 * not in the builder, and one patient with two active orders of this exact shape is what the arrangement
	 * needs. The real {@code validate} runs over it.
	 *
	 * <p><b>Observed, not predicted.</b> With the carrier preference absent this case produced TWO chips —
	 * {@code "… as active order Acetylsalicylic acid (aspirin)"} from the covered code and
	 * {@code "… as active order [ATC N02BA99]"} from the uncovered one — because {@code orderCarrying}
	 * returned the nameless carrier, {@code soleSubstanceOf} answered null for it, and the code became its
	 * own partner. That is what this case fails as.
	 */
	@Test
	public void aCodeOnlyOrderDoesNotTakeTheNamingOfAPartnerFromANamedOne() {
		DrugSafetyValidator withGroups = DrugReferenceTestSupport
				.validator(DrugReferenceTestSupport.ddinterServiceWithGroups());
		PatientClinicalContext.ActiveDrugOrder nameless = PatientClinicalContext.ActiveDrugOrder
				.namedByCodesOnly("order-nameless", "[ATC N02BA99]",
					DrugReferenceTestSupport.set(UNCOVERED_NSAID_ATC));
		PatientClinicalContext.ActiveDrugOrder named = DrugReferenceTestSupport.activeOrder(
			"order-named", "Aspirin 81mg", DrugReferenceTestSupport.set("aspirin 81mg"),
			DrugReferenceTestSupport.set(ASPIRIN_ATC, UNCOVERED_NSAID_ATC));

		List<SafetyWarning> warnings = withGroups.validate("", QUESTION,
				DrugReferenceTestSupport.ctx(60, null,
						DrugReferenceTestSupport.set("Aspirin 81mg"),
						DrugReferenceTestSupport.set(ASPIRIN_ATC, UNCOVERED_NSAID_ATC), null, null,
						java.util.Arrays.asList(nameless, named)));

		assertEquals(1, warnings.size(),
				"one co-medication, one chip — the two carriers of N02BA99 must not become two partners,"
						+ " was: " + warnings);
		String detail = warnings.get(0).getDetail();
		assertTrue(detail.contains("as active order Aspirin 81mg — possible additive"),
				"the named carrier must be the one that names the partner, was: " + detail);
		assertTrue(!detail.contains("[ATC"),
				"and a code-only order must never displace a real drug name, however OrderService"
						+ " happened to order the list, was: " + detail);
	}
}
