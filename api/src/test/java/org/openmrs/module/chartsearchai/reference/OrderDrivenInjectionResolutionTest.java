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
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Order-driven reference injection resolves the patient's active orders the SAME way the chip layer
 * does — {@code DrugReferenceService.findForActiveOrders}, ATC ∪ name (issue #151).
 *
 * <p><b>The defect.</b> {@code DrugReferenceInjector.matchingEntries} resolved its order-driven leg
 * through {@code findByActiveOrders} — the ATC-only primitive — while
 * {@code DrugSafetyValidator.validate} has screened the union since issue #148 gave order names a
 * matcher. So the injector decided an order's RELEVANCE from the reference entry's own ATC codes
 * ({@code relatedToAny}) but its MEMBERSHIP from the ORDER's concept mappings, and only the second of
 * those is sparse: an order whose concept carries no {@code WHOATC} map was invisible to the leg even
 * where the knowledge base publishes that drug's ATC codes perfectly well and the two drugs are in
 * one family.
 *
 * <p><b>What that costs.</b> The chip still fires — it is computed off the union — and since issue
 * #110 it is itself injected as a citable {@code safety_finding} record, so the finding is never
 * unsupported prose. What is missing is the active order's own {@code drug_reference} monograph: the
 * record the injector's relevance rule says a duplicate-therapy / cross-reactivity question is
 * entitled to. The clinician's question is about a drug in the same family as one the patient is
 * already on, and the reference material for the one they are on is absent from the prompt.
 *
 * <p><b>What this does NOT change.</b> {@code relatedToAny} is untouched: order-driven injection stays
 * relevance-scoped, so an active order that is in no shared family with the question's drug is still
 * not injected however it resolved ({@link #anUnrelatedActiveOrderResolvedByNameIsStillNotInjected}),
 * and a question naming no drug still injects nothing from this leg. The fix is to the candidate set
 * alone.
 *
 * <p>Everything here drives the real {@link DrugReferenceInjector#injectRecords} over the real bundled
 * datasets parsed by the real sources, with the real {@link DrugSafetyValidator} behind the injector,
 * so a chip assertion and a record assertion in one case describe one production pass.
 */
public class OrderDrivenInjectionResolutionTest {

	/** The patient's own order, recorded the way a chart records one: a display name with a strength
	 *  appended. The bundled DDInter sample's entry is named {@code Acetylsalicylic acid} and every one
	 *  of its rules is tokenized {@code aspirin} (issue #136), which is why this name resolves an entry
	 *  at all and why the chip below can name a partner the order name does not spell. */
	private static final String ASPIRIN_ORDER = "Acetylsalicylic acid 81mg";

	/** A question about a drug in the same curated cross-reactivity family (NSAID) as that order —
	 *  salicylates ({@code N02BA}) and propionic-acid derivatives ({@code M01AE}) sit in different ATC
	 *  branches, which is exactly what the bundled groups file exists to bridge. */
	private static final String IBUPROFEN_QUESTION = "Is it safe to give ibuprofen?";

	/** The real bundled DDInter sample carrying the real curated cross-reactivity groups — without the
	 *  second half {@code serviceWith} pins the groups empty and no group-related order can be relevant
	 *  to any question. */
	private static DrugReferenceService service() {
		DrugReferenceService service = DrugReferenceTestSupport.ddinterService();
		service.setCrossReactivityGroups(DrugReferenceTestSupport.bundledGroups());
		return service;
	}

	/** The real injector with the real validator behind it, so {@code preAnswerFindings} runs the same
	 *  deterministic pass the chips come from. */
	private static PatientChart inject(PatientClinicalContext context, String question) {
		DrugReferenceService service = service();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));
		return injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
	}

	/** An order known only by its display NAME — the majority shape on the 3.7.1 demo dictionary, where
	 *  the concept carries no {@code WHOATC} map and {@link PatientClinicalContextBuilder} therefore
	 *  contributes no ATC code for it. */
	private static PatientClinicalContext byName(String orderName) {
		return DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(orderName), null,
				null, null);
	}

	/** The same order known only by the ATC code its concept maps to — the shape the order-driven leg
	 *  has always seen. */
	private static PatientClinicalContext byAtc(String code) {
		return DrugReferenceTestSupport.ctx(60, null, null, DrugReferenceTestSupport.set(code), null,
				null);
	}

	/** The injected {@code drug_reference} records' full rendered text, in injection order — the record
	 *  set is what this issue is about, and the REST response cannot show it (only CITED references come
	 *  back). Whole texts rather than extracted names: a name is a prefix of a route-qualified sibling's
	 *  ({@code Lidocaine} of {@code Lidocaine (topical)}), so any name-extraction rule silently merges
	 *  the two, and comparing whole texts is both unambiguous and the stricter assertion. */
	private static List<String> referenceTexts(PatientChart chart) {
		List<String> out = new ArrayList<String>();
		for (RecordMapping mapping : DrugReferenceTestSupport.injectedReferences(chart)) {
			out.add(mapping.getText());
		}
		return out;
	}

	/** @return whether one of {@code texts} is the record rendered for the entry NAMED {@code name}.
	 *          {@code render} writes {@code "Drug reference — <name>"} then, only when the entry
	 *          publishes a class or an ATC code, {@code " (<class>; ATC …)"}, then a full stop — so
	 *          BOTH terminators have to be accepted. Accepting only the parenthesis is a blind check
	 *          rather than a strict one: an entry the knowledge base classifies nowhere renders as
	 *          {@code "Drug reference — Iron."} and would then never be found, which is exactly the
	 *          entry {@link #anOrderWhoseEntryTheKnowledgeBaseClassifiesNowhereIsStillNotInjected} is
	 *          about (caught by mutating {@code relatedToAny} to admit it — the assertion stayed green).
	 *
	 *          <p>It cannot tell a bare name from a route-qualified sibling when neither publishes a
	 *          class or a code ({@code Iron} against {@code Iron (bisglycinate)}), because the
	 *          qualifier and the class parenthesis are the same two characters. Every case below
	 *          therefore pins the record COUNT as well, so no absence rests on this helper alone. */
	private static boolean namesDrug(List<String> texts, String name) {
		for (String text : texts) {
			if (text.startsWith("Drug reference — " + name + " (")
					|| text.startsWith("Drug reference — " + name + ".")) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void theOrderResolvesByNameAloneAndTheEntryItResolvesToCarriesAtcCodesOfItsOwn() {
		// The premise, through the production accessors, so no case below can pass for a reason other
		// than the one it names. The two halves are the whole defect: the order contributes NO ATC code,
		// so the ATC-only primitive cannot see it, while the entry it resolves to publishes the codes
		// that decide relevance.
		DrugReferenceService service = service();
		PatientClinicalContext context = byName(ASPIRIN_ORDER);

		assertTrue(context.getActiveDrugAtcCodes().isEmpty(),
				"the premise: this order contributes no ATC code at all");
		assertTrue(service.findByActiveOrders(context).isEmpty(),
				"so the ATC-only primitive resolves nothing for it");
		List<DrugReference> union = service.findForActiveOrders(context);
		assertEquals(1, union.size(), "while the union the chip layer screens resolves it, was: "
				+ DrugReferenceTestSupport.names(union));
		assertEquals("Acetylsalicylic acid", union.get(0).getName());
		Set<String> codes = union.get(0).normalizedAtcCodes();
		assertFalse(codes.isEmpty(),
				"and that entry publishes ATC codes of its own — which is what makes relatedToAny able "
						+ "to answer at all, and why the ATC-only membership test is the thing that was "
						+ "wrong: " + codes);
	}

	@Test
	public void aChipAboutAnOrderResolvedByNameHasThatOrdersReferenceRecordInjected() {
		PatientChart chart = inject(byName(ASPIRIN_ORDER), IBUPROFEN_QUESTION);

		// The chip is the premise of the complaint: the deterministic layer already knows this patient is
		// on the partner, because it screens the union.
		assertFalse(DrugReferenceTestSupport.injectedFindings(chart).isEmpty(),
				"precondition: the deterministic layer must raise a finding about this pair");
		List<String> injected = referenceTexts(chart);
		assertEquals(2, injected.size(), "exactly the question's drug and the order, was: " + injected);
		assertTrue(namesDrug(injected, "Ibuprofen"),
				"the question's own drug is injected, as it always was, was: " + injected);
		assertTrue(namesDrug(injected, "Acetylsalicylic acid"),
				"and so is the active order the question's drug shares a cross-reactivity family with — "
						+ "the record the injector's own relevance rule entitles this question to, and "
						+ "which an ATC-only candidate set could never supply for an order whose concept "
						+ "carries no WHOATC map, was: " + injected);
	}

	@Test
	public void oneOrderInjectsOneRecordSetWhicheverWayItResolves() {
		// The parity statement, and the sharpest form of the fix: which of the two keys a deployment's
		// dictionary happens to supply for an order must not decide whether the reference material for
		// that order reaches the prompt. Same drug, same question, two contexts differing only in the key.
		List<String> byName = referenceTexts(inject(byName(ASPIRIN_ORDER), IBUPROFEN_QUESTION));
		List<String> byAtc = referenceTexts(inject(byAtc("N02BA01"), IBUPROFEN_QUESTION));

		assertTrue(namesDrug(byAtc, "Acetylsalicylic acid"),
				"precondition: the ATC-keyed context has always injected it, was: " + byAtc);
		assertEquals(byAtc, byName,
				"and the name-keyed context must inject the same records — the chip layer resolves both "
						+ "the same way (findForActiveOrders), so the prompt behind those chips cannot "
						+ "depend on which key the dictionary carried");
	}

	@Test
	public void anUnrelatedActiveOrderResolvedByNameIsStillNotInjected() {
		// The gate is untouched. Warfarin x ibuprofen is Major in the bundled sample, so this patient DOES
		// get a chip — but warfarin (B01AA) is in no ATC subgroup and no curated group the question's drug
		// shares, so its monograph is not what this question needs and is not injected. Order-driven
		// injection is relevance-scoped; issue #151 widens which orders are CANDIDATES, not which are
		// relevant. Without this case the fix could not be told from "inject every active order".
		PatientChart chart = inject(byName("Warfarin 5mg"), IBUPROFEN_QUESTION);

		assertFalse(DrugReferenceTestSupport.injectedFindings(chart).isEmpty(),
				"precondition: this pair really does raise a finding, so the absence below is the gate's "
						+ "doing and not an empty pass");
		List<String> injected = referenceTexts(chart);
		assertEquals(1, injected.size(),
				"the question's drug and nothing else — a count, so the absence below cannot rest on a "
						+ "name match alone, was: " + injected);
		assertTrue(namesDrug(injected, "Ibuprofen"), "was: " + injected);
		assertFalse(namesDrug(injected, "Warfarin"),
				"an active order sharing no family with the question's drug stays out of the prompt "
						+ "however it resolved, was: " + injected);
	}

	@Test
	public void anOrderWhoseEntryTheKnowledgeBaseClassifiesNowhereIsStillNotInjected()
			throws IOException {
		// The branch of relatedToAny this fix makes REACHABLE for the first time, so it is the one that
		// needed a case. An ATC-keyed candidate set could only ever hold entries carrying ATC codes; a
		// name-keyed one routinely resolves entries the KB classifies nowhere, and the shipped 19 MB KB
		// has 444 of 2283 (measured 2026-08-13 through DrugReference.normalizedAtcCodes over the real
		// DdiDrugReferenceSource). Such an entry is in no ATC subgroup and no curated group, so it is
		// related to nothing and injects nothing — the same answer an unrelated order gets. Live
		// counterpart: a patient on Tiotropium asked about ipratropium, where the KB publishes no ATC
		// code for either.
		DrugReferenceService service = DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));
		PatientClinicalContext context = byName("Iron 65mg");

		// Premises: the order really does resolve an entry, and that entry really does publish no ATC
		// code — without both, the absence below would prove nothing about the gate.
		List<DrugReference> resolved = service.findForActiveOrders(context);
		assertFalse(resolved.isEmpty(), "the order must resolve an entry by name");
		for (DrugReference ref : resolved) {
			assertTrue(ref.normalizedAtcCodes().isEmpty(),
					"the fixture's iron rows must publish no ATC code, was: " + ref.getName() + " "
							+ ref.normalizedAtcCodes());
		}

		PatientChart chart = injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context,
				"Is it safe to give dolutegravir?");

		assertFalse(DrugReferenceTestSupport.injectedFindings(chart).isEmpty(),
				"precondition: iron x dolutegravir is Major in this fixture, so a finding IS raised — the "
						+ "absence below is the relevance gate's answer, not an empty pass");
		List<String> injected = referenceTexts(chart);
		assertEquals(1, injected.size(),
				"the question's drug and nothing else — the count is what makes this case able to fail, "
						+ "since an ATC-less entry renders with no parenthesis at all, was: " + injected);
		assertTrue(namesDrug(injected, "Dolutegravir"), "was: " + injected);
		assertFalse(namesDrug(injected, "Iron"),
				"an entry the knowledge base places in no class and no curated group is relevant to "
						+ "nothing, so widening the candidate set cannot admit it: " + injected);
	}

	@Test
	public void aDrugTheQuestionNamesAndThePatientIsAlreadyOnStaysOneRecord() throws IOException {
		// The regression the widening could plausibly introduce: both legs now reach the drug a patient
		// is ON and a question NAMES, and if the collapse missed them the prompt would carry two
		// monographs of one drug — issue #163's defect returning by a new route, and invisible from the
		// REST response, which returns only CITED references. The name-keyed counterpart of
		// ReferenceRecordSubstanceCollapseTest.theQuestionLegAndTheOrderLegShareOneRecordForOneSubstance,
		// which makes the same statement for an ATC-keyed order.
		DrugReferenceService service = DrugReferenceTestSupport
				.ddiFixtureService(DrugReferenceTestSupport.DDI_ROUTE_VARIANTS);
		List<String> injected = referenceTexts(DrugReferenceTestSupport.injector(service).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(), byName("Dexamethasone 4mg"),
				"Is it safe to give dexamethasone?"));

		assertEquals(1, injected.size(),
				"one substance is one record however many legs reach it — and this family is four rows, "
						+ "so a per-row leak would show as four: " + injected);
		assertTrue(injected.get(0).startsWith("Drug reference — Dexamethasone (ATC"),
				"and it is the route-unspecified row, was: " + injected.get(0));
	}

	@Test
	public void aNullClinicalContextStillInjectsTheQuestionsOwnDrug() {
		// The guard this fix MOVED. The order leg used to stand down on `context != null`; it now stands
		// down on an empty candidate list, and the two are the same condition only because
		// findForActiveOrders answers an empty list for a null context rather than null. If that ever
		// stopped being true the leg would throw, and the throw is caught by `inject` — so the whole
		// injection, question-driven records included, would degrade to nothing with only a WARN. A
		// silent loss of every record, from a null the compiler cannot see.
		PatientChart chart = inject(null, IBUPROFEN_QUESTION);

		List<String> injected = referenceTexts(chart);
		assertEquals(1, injected.size(), "the question's own drug, and no order leg to add to it: "
				+ injected);
		assertTrue(namesDrug(injected, "Ibuprofen"), "was: " + injected);
	}

	@Test
	public void aQuestionThatNamesNoDrugStillInjectsNothingFromTheOrderLeg() {
		// The other half of the gate, over the widened candidate set: an empty question-drug list has no
		// relevance anchor, so the leg contributes nothing no matter how many orders now reach it. The
		// screening question is the one that most obviously must not turn into a medication-list dump.
		PatientChart chart = inject(byName(ASPIRIN_ORDER), DrugReferenceTestSupport.SCREENING_QUESTION);

		assertEquals(0, DrugReferenceTestSupport.injectedReferences(chart).size(),
				"a question naming no drug injects no reference record from the order leg, was: "
						+ referenceTexts(chart));
	}
}
