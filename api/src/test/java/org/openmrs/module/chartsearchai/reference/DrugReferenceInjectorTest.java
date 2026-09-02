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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Exercises the real {@link DrugReferenceInjector} over the real bundled dataset
 * via {@link DrugReferenceInjector#injectRecords}, the pure (no OpenMRS context)
 * seam. The injectFromQuery/injectFromOrders toggles fall back to their {@code true}
 * defaults when no context is available, matching production defaults.
 */
public class DrugReferenceInjectorTest {

	private DrugReferenceInjector injector() {
		return DrugReferenceTestSupport.injector(DrugReferenceTestSupport.curatedService());
	}

	/** Injector backed by the real WHO ATC sample (parsed by the real source), which — unlike the
	 *  bundled JSON — contains two drugs in the same ATC subgroup (ibuprofen/naproxen, both M01AE),
	 *  needed to exercise the "related active order" path. */
	private DrugReferenceInjector atcInjector() throws IOException {
		return DrugReferenceTestSupport.injector(DrugReferenceTestSupport.atcService(false));
	}

	/** Injector over the real DDInter excerpt — the only bundled dataset whose entries carry
	 *  enough interaction partners (Lisinopril: 15) to exercise the render cap. */
	private DrugReferenceInjector ddinterInjector() {
		return DrugReferenceTestSupport.injector(DrugReferenceTestSupport.ddinterService());
	}

	/** Injector wired with the validator, so the deterministic findings can be injected pre-answer. */
	private DrugReferenceInjector ddinterInjectorWithSafety() {
		return DrugReferenceTestSupport
				.injectorWithSafety(DrugReferenceTestSupport.ddinterServiceWithGroups());
	}

	private Set<String> set(String... values) {
		return DrugReferenceTestSupport.set(values);
	}

	private PatientChart oneRecordChart() {
		return DrugReferenceTestSupport.oneRecordChart();
	}

	private PatientClinicalContext context(Integer age, Set<String> atc) {
		return DrugReferenceTestSupport.ctx(age, null, null, atc, null, null);
	}

	@Test
	public void questionDrivenInjectionAppendsCitableRecord() {
		PatientChart result = injector().injectRecords(oneRecordChart(),
				context(5, null), "what is the safe dose of ibuprofen?");

		assertEquals(2, result.getMappings().size(), "one reference record should be appended");
		RecordMapping injected = result.getMappings().get(1);
		assertEquals(2, injected.getIndex(), "numbering continues from the chart records");
		assertEquals(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE, injected.getResourceType());
		assertEquals("ibuprofen", injected.getResourceUuid());
		assertTrue(result.getText().contains("[2] Drug reference — Ibuprofen"),
				"injected record should be a numbered, citable chart line");
	}

	@Test
	public void injectionPreservesQueryScopedStamp() {
		// A query-scoped slice that gains a drug-reference record MUST stay stamped query-scoped:
		// LlmInferenceService.searchStreaming derives the KV-cache decision from
		// PatientChart.isQueryScoped() (not a re-read of the chartMode GP, deliberately). If
		// injection drops the stamp, a question-dependent slice can be persisted under the
		// patient's KV scope during a mode-flip/GP-read race, evicting their real full-chart
		// (pinned) entry. Regression: injectRecords rebuilt the chart via a fresh PatientChart,
		// which reset the flag to false.
		PatientChart scoped = oneRecordChart();
		scoped.markQueryScoped();

		PatientChart result = injector().injectRecords(scoped,
				context(5, null), "what is the safe dose of ibuprofen?");

		assertTrue(result.getMappings().size() > scoped.getMappings().size(),
				"precondition: a reference record must actually be injected, else the rebuild path is not exercised");
		assertTrue(result.isQueryScoped(),
				"the injected chart must carry forward the query-scoped stamp");
	}

	@Test
	public void injectionLeavesFullChartUnstamped() {
		// The mirror guard: injection must never ADD the stamp to a full chart, which would wrongly
		// suppress the patient KV scope for the mode whose whole design depends on it.
		PatientChart full = oneRecordChart();

		PatientChart result = injector().injectRecords(full,
				context(5, null), "what is the safe dose of ibuprofen?");

		assertTrue(result.getMappings().size() > full.getMappings().size(),
				"precondition: a reference record must actually be injected");
		assertFalse(result.isQueryScoped(),
				"a full chart must never acquire the query-scoped stamp through injection");
	}

	@Test
	public void injectionPreservesThePreFilterStamp() {
		// Same rebuild, the other stamp (issue #178). ChartBuildingStrategy.searchModeLabel reads
		// this flag to tell the two full-chart shapes apart in the audit log, so an injection that
		// dropped it would file a focus-hinted prompt as a plain full chart — a wrong signal, which
		// is the failure class #178 is about rather than a missing one.
		PatientChart preFiltered = oneRecordChart();
		preFiltered.markPreFiltered();

		PatientChart result = injector().injectRecords(preFiltered,
				context(5, null), "what is the safe dose of ibuprofen?");

		assertTrue(result.getMappings().size() > preFiltered.getMappings().size(),
				"precondition: a reference record must actually be injected, else the rebuild path is not exercised");
		assertTrue(result.isPreFiltered(),
				"the injected chart must carry forward the preFilter stamp");
	}

	@Test
	public void injectionLeavesAPlainFullChartUnPreFiltered() {
		// The mirror guard: injection must never ADD the stamp, which would file a plain full chart
		// as a focus-hinted one.
		PatientChart full = oneRecordChart();

		PatientChart result = injector().injectRecords(full,
				context(5, null), "what is the safe dose of ibuprofen?");

		assertTrue(result.getMappings().size() > full.getMappings().size(),
				"precondition: a reference record must actually be injected");
		assertFalse(result.isPreFiltered(),
				"a plain full chart must never acquire the preFilter stamp through injection");
	}

	@Test
	public void aDeterministicSafetyFindingIsInjectedAsItsOwnCitableRecord() {
		// The module computes the safety join correctly and deterministically — DrugSafetyValidator
		// raises the right chip every time — but it runs AFTER the answer, so the LLM is asked to
		// re-derive a conclusion the code already holds. It does not: the eval README records 0 joins
		// in 21 baseline cells, and on 2026-07-30 two live cases abstained with the evidence rendered,
		// cited, and demonstrably quotable (mary/clarithromycin with simvastatin at 0/6, and betty's
		// NSAID cross-reactivity, where the model recited the family list verbatim on request and
		// still answered "the records do not address"). Supplying more evidence is measurably not the
		// lever; three prompt variants regressed as well.
		//
		// So the finding itself becomes a record. The model's job drops from deriving a join to
		// reporting a line in front of it — which it does reliably — and the abstention rule stops
		// misfiring because a record now explicitly addresses the drug.
		PatientChart result = ddinterInjectorWithSafety().injectRecords(oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, set("simvastatin"), set("C10AA01"), null, null),
				"is it safe to give clarithromycin?");

		RecordMapping finding = null;
		for (RecordMapping m : result.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(m.getResourceType())) {
				finding = m;
			}
		}
		assertNotNull(finding, "a deterministic finding must be injected as its own record: "
				+ result.getText());
		assertTrue(finding.getText().toLowerCase().contains("simvastatin"),
				"the finding must name the interacting drug the patient is on: " + finding.getText());
		assertTrue(result.getText().contains("[" + finding.getIndex() + "] "),
				"it must be a numbered, citable chart line so the answer can cite it: " + result.getText());
		assertEquals(ChartSearchAiConstants.REFERENCE_GROUP_REFERENCE,
				ChartSearchAiUtils.referenceGroup(finding.getResourceType()),
				"a module-derived finding is not navigable chart evidence, and referenceGroup fails "
						+ "SAFE to chart — an unclassified type would be published as the patient's own record");
	}

	@Test
	public void findingInjectionIsGatedOnTheSameToggleAsTheChips() {
		// The chips and the injected findings must switch on and off together. DrugSafetyValidator
		// gates on drugSafety.validateAnswers in its public Patient-taking entry only; the
		// package-private overload preAnswerFindings uses does not, so an operator setting that GP
		// false would silence the chips while findings kept reaching the prompt — the answer asserting
		// a Major interaction with no chip beside it, which is the divergence this change removes.
		//
		// With no OpenMRS context the GP read fails safe to the default (true), so this asserts the
		// enabled direction: the toggle is consulted and findings flow. The disabled direction needs a
		// live GP layer — contract: with validateAnswers=false, injectRecords must emit no
		// safety_finding record even when the validator would have found one.
		PatientChart result = ddinterInjectorWithSafety().injectRecords(oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, set("simvastatin"), set("C10AA01"), null, null),
				"is it safe to give clarithromycin?");
		boolean found = false;
		for (RecordMapping m : result.getMappings()) {
			found = found || ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(m.getResourceType());
		}
		assertTrue(found, "with the toggle at its default the finding must still be injected: "
				+ result.getText());
	}

	@Test
	public void noSafetyFindingRecordIsInjectedWhenTheDeterministicLayerFindsNothing() {
		// The property that makes this safe: the record exists only when the validator has a finding,
		// so a question nothing bears on gains nothing and its abstention is preserved by
		// construction rather than by prompt wording. This is the direction #107 guards, and the
		// direction two of the three reverted prompt variants broke.
		PatientChart result = ddinterInjectorWithSafety().injectRecords(oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, set("simvastatin"), set("C10AA01"), null, null),
				"is it safe to give paracetamol?");

		for (RecordMapping m : result.getMappings()) {
			assertFalse(ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(m.getResourceType()),
					"nothing connects paracetamol to this patient, so no finding may be injected: "
							+ m.getText());
		}
	}

	@Test
	public void renderedInteractionsMustNameThePartnerThePatientIsActuallyOn() {
		// The rendered Interactions: section is capped at MAX_INTERACTION_RENDER_CHARS and was
		// filled in DATASET order, so which partners a clinician's model can cite was decided by
		// the dataset's ordering rather than by the patient. In the real DDInter excerpt
		// Lisinopril carries 15 partners and, before issue #117, the 1500-char cut fell after seven of
		// them, so Ibuprofen — the LAST one, and a Moderate NSAID x ACE-inhibitor interaction that
		// attenuates the antihypertensive effect — was truncated out entirely. (No branch cuts there
		// any more: since #355 an unpromoted tail stops at the partner cap, and a promoted one at its
		// single representative. The figure is the record of what #84 and #117 were about.)
		//
		// Measured on the 3.7.1 standalone (2026-07-30, full 19MB KB): Clarithromycin has 898
		// partners with Simvastatin (Major) at index 324 and Ivosidenib at index 0, so asked
		// "can this patient take clarithromycin?" about a patient on simvastatin, every answer
		// recited ivosidenib/kanamycin/ketoprofen — the partners that happened to render — and
		// none of 6 runs named simvastatin. DrugSafetyValidator raised the correct simvastatin
		// chip regardless, because it reads every interaction off the entry and never consults
		// this text: the chip and the prose disagreed by construction. Three prompt variants
		// were measured trying to fix that from the prompt and all three regressed
		// (eval/drift-metric/README.md); one of them instructed the model to cite only
		// patient-relevant partners, which was impossible to obey.
		String section = interactionsSectionFor("Lisinopril", "ibuprofen");
		assertTrue(section.startsWith("interactions:"),
				"precondition: the Lisinopril entry must render an Interactions section: " + section);
		assertTrue(section.contains("ibuprofen"),
				"the capped Interactions section must name the partner this patient is actually on, "
						+ "not whichever partners the dataset happened to list first: " + section);
	}

	@Test
	public void interactionRenderCapStillBoundsTheRenderedSection() {
		// The cap is load-bearing — Warfarin carries ~934 partners in the full KB — so the
		// prioritisation must reorder what renders, never widen it without bound. The invariant is
		// the cap plus at most ONE note: the pre-existing "at least one interaction is always
		// shown" rule already overshoots by one, and promoting the patient's partners extends that
		// to one-per-segment (a single promoted note can be long enough to consume the whole
		// budget — the bundled aspirin x ibuprofen Major note is ~1200 of the 1500 chars — and
		// dropping the entire dataset tail would leave the model unable to say anything about the
		// drug beyond this patient's one overlap). Expressed as 2x the cap rather than a magic
		// margin, so a fixture whose notes get longer cannot make this pass by luck.
		String section = interactionsSectionFor("Lisinopril", "ibuprofen");
		assertTrue(section.length() <= 2 * DrugReferenceInjector.MAX_INTERACTION_RENDER_CHARS,
				"the rendered interactions section must stay bounded by cap + one note: " + section.length());
		// Truncation must still be reported — the bound above is worthless if the cap never bit. The
		// report moved from a text tail to the mapping in issue #117 (the model recited the tail into
		// answers); this asserts the same fact on its new carrier.
		assertTrue(injectedMappingFor("Lisinopril", "ibuprofen").getWithheldInteractions() > 0,
				"partners dropped by the cap must still be reported as withheld: " + section);
	}

	@Test
	public void promotingThePatientsPartnerStillRendersSomeOfTheDatasetTail() {
		// The guarantee that the extended cap exists to keep. A promoted note that fills the budget
		// by itself must not silently reduce the record to "this patient's one overlap": the entry
		// is also the only reference material the model has about the drug in general. Ibuprofen's
		// aspirin note is ~1200 of 1500 chars, so before the per-segment guarantee this record
		// rendered aspirin alone — which broke DrugSafetyValidatorEchoScopingTest's premise that a
		// non-patient partner (lisinopril) is recitable out of the cited record.
		PatientChart result = ddinterInjector().injectRecords(oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, set("Aspirin"), set("B01AC06"), null, null),
				"is it safe to give ibuprofen?");

		String ibuprofen = referenceRecordContaining(result, "Ibuprofen");
		String section = ibuprofen.substring(ibuprofen.indexOf("Interactions:")).toLowerCase();
		assertTrue(section.contains("aspirin"),
				"the patient's own partner must lead the section: " + section);
		assertTrue(section.contains("lisinopril"),
				"at least one dataset-order partner must still render alongside it: " + section);
	}

	@Test
	public void everyPartnerThePatientIsOnIsRepresentedEvenWhenTheirNotesExceedTheBudget() {
		// Polypharmacy, and the case that makes promotion alone insufficient. On the real bundled
		// sample a patient on methotrexate AND aspirin asking about ibuprofen has two above-floor
		// relevant partners whose rendered notes are 783 and 809 chars — 1594 against a 1500-char
		// budget. Promoting them both to the front is not enough: the second one falls off the cap,
		// and the dataset-tail guarantee then spends the overshoot on lisinopril, a drug this
		// patient does not take. The aspirin x ibuprofen Major chip still fires, so the omission
		// recreates precisely the chip-says-one-thing-prose-says-another split this whole change
		// exists to remove — silently, and on the more dangerous of the two interactions.
		//
		// So a relevant partner is never invisible: it renders its full note when the budget allows
		// and a compact "name (Severity)" form when it does not. That stays bounded — the count is
		// the patient's own active-drug list, not the dataset's 898 partners.
		String section = interactionsSectionFor("Ibuprofen", "methotrexate", "aspirin");
		assertTrue(section.contains("methotrexate (major."),
				"the first relevant partner renders its full note, budget permitting: " + section);
		// Pin the compact FORM, not merely the name's presence: severity survives because it is what
		// a clinician needs when the mechanism prose cannot fit, and the prose itself is what got
		// dropped. Asserting only contains("aspirin") would pass on the full note too and so would
		// not distinguish the fallback from having simply had room.
		assertTrue(section.contains("aspirin (major)"),
				"the partner whose note does not fit must still render as name + severity: " + section);
		assertFalse(section.contains("antiplatelet and cardioprotective"),
				"the compact form must drop the mechanism prose that did not fit: " + section);
	}

	@Test
	public void everyPartnerThePatientIsOnPrecedesTheDatasetTail() {
		// The priority half of the same defect. Before the compact fallback, the budget ran out
		// mid-relevant and the dataset-tail guarantee then rendered lisinopril while aspirin — this
		// patient's own Major interaction — was withheld entirely: an unrelated partner shown
		// INSTEAD of a relevant one. The fix is not to suppress the tail (the entry is still the
		// only reference material about the drug in general, so one tail partner is deliberate) but
		// to stop the relevant segment from being the thing that loses. Both guarantees hold at
		// once, and the invariant that distinguishes them is ORDER: no relevant partner may sit
		// behind, or be missing while, a tail partner renders.
		String section = interactionsSectionFor("Ibuprofen", "methotrexate", "aspirin");
		int methotrexate = section.indexOf("methotrexate");
		int aspirin = section.indexOf("aspirin");
		int tail = section.indexOf("lisinopril");
		assertTrue(aspirin > 0 && methotrexate > 0,
				"both of the patient's own partners must be represented: " + section);
		assertTrue(tail > 0,
				"precondition: the dataset-tail representative renders too, else this proves nothing: "
						+ section);
		assertTrue(methotrexate < tail && aspirin < tail,
				"the patient's own partners must precede the dataset tail, not lose the budget to it: "
						+ section);
	}

	@Test
	public void promotedPartnersAreOrderedMostSevereFirstEvenWhenAllOfThemFit() {
		// Isolates the severity ordering from the budget. A patient on metformin (Moderate x
		// ibuprofen, 427 chars) and warfarin (MAJOR, 164) has both promoted and both fit inside the
		// 1500 budget, so nothing is compacted and the only observable effect is the ORDER the model
		// reads top-down. Dataset order would put the Moderate one first; severity order leads with
		// the Major. Without this, the sort is only pinned by the truncating case, so an edit that
		// applied it exclusively when the budget bites would keep every existing test green while
		// presenting a Moderate interaction ahead of a Major one on every roomy entry.
		String section = interactionsSectionFor("Ibuprofen", "metformin", "warfarin");
		int warfarin = section.indexOf("warfarin");
		int metformin = section.indexOf("metformin");
		assertTrue(warfarin > 0 && metformin > 0,
				"precondition: both promoted partners must render in full, else this proves nothing: "
						+ section);
		assertTrue(warfarin < metformin,
				"the Major interaction must be presented before the Moderate one: " + section);
	}

	@Test
	public void whenTheBudgetForcesAChoiceTheMoreSevereInteractionKeepsItsMechanism() {
		// Ordering the patient's own partners first fixed WHICH partners render; it left WHICH ONE
		// keeps its mechanism prose to the dataset's ordering. On the DDInter excerpt a patient on
		// lisinopril (Moderate x ibuprofen, 910 chars) and aspirin (MAJOR x ibuprofen, 809) has both
		// promoted, but 1721 chars do not fit the 1500 budget — and because lisinopril sits earlier
		// in the dataset it took the full note, abbreviating the Major interaction. Both severities
		// are still visible, so this is not a silent omission; what is lost is the actionable half
		// (the mechanism text) for the more dangerous of the two, decided by dataset accident.
		// Severity, not dataset position, must decide who gets the prose when only one can.
		String section = interactionsSectionFor("Ibuprofen", "lisinopril", "aspirin");
		assertTrue(section.contains("antiplatelet and cardioprotective"),
				"the Major interaction must keep its mechanism text: " + section);
		assertTrue(section.contains("lisinopril (moderate)"),
				"the Moderate interaction is the one that yields to the compact form: " + section);
	}

	@Test
	public void aSubFloorInteractionIsNotPromotedEvenWhenThePatientIsOnThatDrug() {
		// Promotion must honour the interaction-severity floor the chips honour (issue #84).
		// Lisinopril x warfarin is an Unknown-severity DDInter row with no mechanism text — exactly
		// what the default `minor` floor exists to keep out of the clinician's way — and nothing but
		// promotion can put it in this section: an Unknown rating ranks LAST, so since issue #355 the
		// tail names five better-rated partners before it would reach this one, and a promoted
		// warfarin would render in segment 1, which overrides the budget. (Before that issue the
		// budget excluded it instead, as the first partner to fall past the render cap in dataset
		// order.) So its presence can only come from promotion. Promoting on relevance alone pulled rows
		// like it to the front of the prompt, and measured on the 3.7.1 standalone the model then
		// answered from them: two probe cells that correctly abstained on the baseline began
		// reporting "an Unknown severity
		// interaction between Erythromycin and Lisinopril", so the render path was bypassing a
		// safety decision the chip path enforces. Above-floor promotion still works
		// (renderedInteractionsMustNameThePartnerThePatientIsActuallyOn covers the Moderate case).
		String section = interactionsSectionFor("Lisinopril", "warfarin");
		assertFalse(section.contains("warfarin"),
				"an Unknown-severity rule must not be promoted past the render cap: " + section);
		assertTrue(section.contains("metformin"),
				"precondition: the section still renders its above-floor partners, which since issue "
						+ "#355 it names most severe first rather than in dataset order: " + section);

		// The other half of the contract, on the same row. The floor's whole point is that the chips
		// and the rendered prose agree about which rules count, and that now rests on both paths
		// resolving it through DrugSafetyValidator.configuredSeverityFloor. Asserting only the render
		// side would leave the agreement itself unpinned — a re-inlined second GP read would keep
		// every other test green while letting the two drift, producing a chip with no supporting
		// prose or prose with no chip.
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(
				DrugReferenceTestSupport.ddinterService()).validate(
						"Lisinopril may be given.", "is it safe to give lisinopril?",
						DrugReferenceTestSupport.ctx(50, null, set("warfarin"), null, null, null));
		assertFalse(DrugReferenceTestSupport.has(warnings, SafetyWarning.TYPE_INTERACTION, "lisinopril"),
				"the same sub-floor rule must not raise a chip either — the render path and the chip "
						+ "path must agree on the floor: " + warnings);
	}

	@Test
	public void theRenderersOwnBookkeepingIsStructuralNotCitableText() {
		// Issue #117. The withheld-partner count and the dataset attribution were appended to the
		// same string the model is told to cite, so there was no boundary marking them as metadata
		// and the model recited them. Live on the 3.7.1 standalone (full 19MB KB): a patient on
		// simvastatin asked "can I prescribe erythromycin?" got a 1492-char answer whose first
		// sentence was the answer and whose tail read "...and 824 mor e interactions on file.
		// Source: DDInter 2.0 (via openmrs-ddi-knowledge-base). [75]" — the module's own truncation
		// counter, mangled by the quantised model, presented to a clinician as clinical content.
		//
		// Both facts are worth keeping, so they move to the mapping as fields: the client can render
		// provenance and honest truncation on the citation chip, and the model has nothing to quote.
		PatientChart result = ddinterInjector().injectRecords(oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, set("ibuprofen"), null, null, null),
				"is it safe to give lisinopril?");
		RecordMapping ref = referenceMappingFor(result, "Lisinopril");

		// Both the mapping text and the chart line, because they are the same string and BOTH are
		// what the model reads: the chart text is the prompt, the mapping text is what the grounding
		// verifier compares against, and a divergence here would mean grounding a claim against
		// words the model never saw.
		assertFalse(ref.getText().contains("more interactions on file"),
				"the withheld count must not be inside the citable record: " + ref.getText());
		assertFalse(ref.getText().contains("Source:"),
				"the dataset attribution must not be inside the citable record: " + ref.getText());
		assertFalse(result.getText().contains("more interactions on file"),
				"nor anywhere in the prompt chart text: " + result.getText());
		assertFalse(result.getText().contains("Source:"),
				"nor anywhere in the prompt chart text: " + result.getText());
		// The two pairs above only differ if the mapping text and the chart line can differ, so pin
		// that they cannot: the chart line is "[N] " + the mapping text, byte for byte. This is the
		// invariant the grounding verifier rests on — it compares an answer sentence against
		// mapping.getText() to judge a claim the model formed from the chart text — so a divergence
		// would ground claims against words the model never read. Nothing else asserts it.
		assertTrue(result.getText().contains("[" + ref.getIndex() + "] " + ref.getText() + "\n"),
				"the chart line must be the mapping text verbatim: " + result.getText());

		// And still exposed — removing them from the text must not lose them. The bundled Lisinopril
		// entry carries 15 interaction partners and this record renders two (the promoted ibuprofen
		// plus one tail representative), so 13 are withheld.
		assertEquals(13, ref.getWithheldInteractions(),
				"the withheld count must survive structurally: " + ref.getText());
		assertEquals("DDInter 2.0 (via openmrs-ddi-knowledge-base)", ref.getSource(),
				"the dataset attribution must survive structurally");
	}

	@Test
	public void whenThePatientIsOnEveryPartnerThereIsNoDatasetTailLeftAndNothingIsWithheld() {
		// Segment 2's third case: the patient is on ALL of this entry's above-floor partners, so the
		// dataset tail is empty and the representative must simply not render. It is the only arm of
		// that branch nothing else reaches — the two tests either side of this one cover "a tail
		// exists alongside a promoted partner" and "nothing was promoted".
		//
		// It is worth its own test because the guard protecting it is the kind that reads redundant:
		// `restStart < ordered.size()` looks like a bound check on a list you just measured, and
		// relaxing it to <= throws IndexOutOfBoundsException out of render. DrugReferenceInjector.inject
		// catches every RuntimeException and returns the chart unmodified, so the failure would not
		// surface as an error — the entire drug-reference feature, including the deterministic
		// safety_finding records #110 added to stop safety abstentions, would silently vanish behind one
		// log.warn, for exactly the polypharmacy patients it matters most for.
		//
		// The bundled curated entry Paracetamol carries exactly one interaction (warfarin, unrated —
		// and unrated is floor-exempt, so it promotes), which makes promotedCount == ordered.size().
		PatientChart result = injector().injectRecords(oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, set("warfarin"), null, null, null),
				"is it safe to give paracetamol?");
		RecordMapping ref = referenceMappingFor(result, "Paracetamol");
		String section = ref.getText().substring(ref.getText().indexOf("Interactions:")).toLowerCase();

		assertTrue(section.contains("warfarin"),
				"precondition: the patient's own partner must be promoted and rendered: " + section);
		assertEquals(0, ref.getWithheldInteractions(),
				"with every partner rendered the count must be 0 — reporting a withheld partner that "
						+ "does not exist would make the citation claim a subset of itself: " + section);
	}

	@Test
	public void aModuleDerivedFindingIsAReferenceGroupRecordThatCarriesNoAttribution() {
		// The pair that makes the README's "branch on the value, not the group" warning true, and the
		// reason it is a warning at all. referenceGroup puts a safety finding in the SAME `reference`
		// group as a drug-reference record (pinned by
		// aDeterministicSafetyFindingIsInjectedAsItsOwnCitableRecord), so a client that keys "show
		// provenance" off the group renders a source for a record that has none.
		//
		// It has none because it is the module's own conclusion, computed from the entry rather than
		// quoted out of a dataset — and because #110 made that finding a CITABLE record, which is
		// precisely the carrier #117 proved a source string must never ride on: whatever is inside a
		// citable record is quotable, and the model quotes what it cites. Nothing is withheld from a
		// finding either: it is about one specific interaction, not a truncated set.
		PatientChart result = ddinterInjectorWithSafety().injectRecords(oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, set("simvastatin"), set("C10AA01"), null, null),
				"is it safe to give clarithromycin?");

		RecordMapping finding = null;
		for (RecordMapping m : result.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_SAFETY_FINDING.equals(m.getResourceType())) {
				finding = m;
				// First, not last — so this test and its sibling above describe the SAME record if a
				// question ever yields more than one finding.
				break;
			}
		}
		assertNotNull(finding, "precondition: a deterministic finding must be injected: " + result.getText());
		assertNull(finding.getSource(),
				"a module-derived finding is computed, not quoted from a dataset, so it must declare "
						+ "no attribution however its group is classified: " + finding.getText());
		assertEquals(0, finding.getWithheldInteractions(),
				"and nothing is withheld from a single-interaction finding: " + finding.getText());
	}

	@Test
	public void theDatasetTailRepresentativeDropsItsProseWhenAPatientRelevantPartnerIsRendered() {
		// The other half of #117: the answer's bulk was two full interaction notes for drugs the
		// patient has nothing to do with (ivosidenib, ixabepilone), which the model reported
		// alongside the real finding as though equally actionable. They were there because segment 2
		// spends whatever the budget has left on dataset-order partners in FULL — so a short
		// promoted note buys several irrelevant mechanism paragraphs.
		//
		// The tail's purpose (see render) is that the record is also the only general reference
		// material about the drug, i.e. that it must not read as if the patient's own overlap were
		// the drug's only interaction. One partner named with its severity says exactly that. The
		// mechanism prose is the part that is only useful for a partner the patient is actually on —
		// and, per #117's corruption observation, the part this model degrades while reciting.
		//
		// The bundled Lisinopril entry with a patient on digoxin (Moderate, 313 chars) leaves ~1200
		// of the 1500-char budget, which previously rendered five full dataset-order notes.
		String section = interactionsSectionFor("Lisinopril", "digoxin");
		assertTrue(section.contains("digoxin (moderate. some ace inhibitors"),
				"precondition: the patient's own partner keeps its full mechanism note: " + section);
		assertTrue(section.contains("metformin (moderate)"),
				"the one dataset-tail representative renders as name + severity: " + section);
		assertFalse(section.contains("limited data suggest"),
				"its mechanism prose — useful only for a partner the patient is on — must go: " + section);
		assertFalse(section.contains("methotrexate"),
				"and no second dataset-order partner may render: the budget must not be spent on "
						+ "partners this patient has nothing to do with: " + section);
	}

	@Test
	public void withNoPatientRelevantPartnerTheDatasetTailStatesBreadthCompactlyMostSevereFirst() {
		// Issue #355, the residue of #117 on the other side of segment 2's branch. Until it, this
		// branch spent the whole MAX_INTERACTION_RENDER_CHARS budget on FULL mechanism paragraphs for
		// whichever partners sat at the head of the entry's dataset order, on the rationale that with
		// nothing promoted "the general material IS its content". The live reproduction that falsified
		// it — the 1512-character Metformin record whose whole content was mechanism prose about the
		// head of an alphabetical list — is recorded once, on MAX_TAIL_PARTNERS_WHEN_NONE_PROMOTED,
		// rather than restated here where the two copies would drift apart. The question it was asked
		// was patient-specific; what the record answered with was dataset position.
		//
		// The tail's job is breadth either way (see render), and breadth is stated by naming partners
		// with their severities. What must not survive is the mechanism text: it is actionable only
		// for a partner the patient is on, and #117 records this model garbling long verbatim copies
		// while reciting them.
		//
		// Still a branch rather than one rule, and the COUNT is what distinguishes the two sides: the
		// promoted case renders ONE representative because segment 1 already carried the
		// patient-specific content, while here the tail is the whole record and a single name would
		// read as this drug's only interaction — which is what the version of this test before #355
		// warned the obvious simplification would do to every entry the patient has no overlap with,
		// the common case.
		String section = interactionsSectionFor("Lisinopril");
		assertTrue(section.contains("spironolactone (major)"),
				"the entry's Major partner leads the tail, in the compact name (severity) form: "
						+ section);
		assertTrue(section.indexOf("spironolactone") < section.indexOf("metformin"),
				"severity and not dataset position decides which partners a capped tail spends its "
						+ "room on — the same decision SEVERITY_DESCENDING already makes for the "
						+ "promoted segment, for the same measured reason: " + section);
		assertTrue(section.contains("metformin (moderate)"),
				"and every other named partner is compact too, not a mechanism paragraph: " + section);
		assertFalse(section.contains("limited data suggest"),
				"the mechanism prose for a partner this patient has nothing to do with must go: "
						+ section);
		assertFalse(section.contains("sertraline"),
				"and the tail is a handful, not a budget's worth: sertraline is one of the Unknown "
						+ "rows that rendered before #355 purely because they sit early in the "
						+ "dataset: " + section);
		assertTrue(4 * section.length() <= DrugReferenceInjector.MAX_INTERACTION_RENDER_CHARS,
				"the section now costs a fraction of the budget this branch used to spend, was "
						+ section.length() + " chars: " + section);
	}

	@Test
	public void theUnpromotedTailStillPaysTheCharacterBudgetForRulesThatHaveNoNameToShortenTo()
			throws Exception {
		// The clause issue #355 kept rather than added, and the one shape that still discriminates it.
		// InteractionNote's compact form is `label (Severity)`, so for almost every row the cap on
		// partners is what bounds the tail and the character budget never bites. It bites for a rule
		// carrying no token and no ATC: partnerLabel returns null, there is no name to shorten to, and
		// the compact form IS the mechanism paragraph. Five of those are five paragraphs, which is the
		// cost #355 exists to remove, so the budget stays in the loop beside the cap.
		//
		// The clause was left undiscriminated by #355 until this case: before it, deleting the condition
		// kept the whole api suite green. Not because no other fixture carries a nameless rule —
		// drug-reference-malformed.json's `mangled` entry carries two nameless ROWS — but because
		// neither can spend the budget: one is blank, which orderedInteractionNotes drops outright
		// before an InteractionNote is built for it, and the other's note is 21 characters. Mutate
		// the condition and read the failures rather than trusting a count of them: this case was
		// the sole witness when it was written and is not any more, the nameless-tail fixture beside
		// it having since grown a note long enough to reach the budget too.
		//
		// Every row is unrated, so severityPriority ties them and the stable sort leaves dataset order
		// — which keeps this case about the budget rather than about the ordering beside it.
		RecordMapping record = tailRecordOf(
				"chartsearchai-test/drug-reference-unpromoted-tail-budget.json",
				"is it safe to give budgetstub?");
		String section = tailSectionOf(record);

		assertTrue(section.contains("ALPHA"),
				"precondition: the first nameless rule always renders, however long it is: " + section);
		assertTrue(section.contains("BRAVO"),
				"precondition: and a second one fits the budget, so the cut below is the budget's and "
						+ "not the first row's: " + section);
		assertFalse(section.contains("CHARLIE"),
				"a third does not fit, and the partner cap alone would have admitted it — five nameless "
						+ "rules are five mechanism paragraphs, which is the cost #355 removes: " + section);
		assertEquals(3, record.getWithheldInteractions(),
				"and the rows the budget cut must be reported as withheld: " + section);
		assertTrue(section.length() <= DrugReferenceInjector.MAX_INTERACTION_RENDER_CHARS,
				"and what the budget bounds here is the section itself: the first note is inside the "
						+ "budget on this fixture, so nothing overshoots and the whole section must fit, "
						+ "was " + section.length() + " chars: " + section);
	}

	@Test
	public void aTailRuleWithNoPartnerToNameDoesNotDisplaceOneThatDoes() throws Exception {
		// Issue #355's own regression, found by measuring the change rather than reading it. Ordering
		// the tail by severity alone interacts with two facts that are individually fine: an UNRATED
		// rule ranks above Major (severityPriority — every curated hand-authored rule is unrated), and
		// a rule carrying no token and no ATC has no name to shorten to, so its compact form IS its
		// mechanism paragraph. Together they hoist a nameless paragraph into the one slot the
		// character budget cannot refuse — the first — wherever the dataset put it, and it then
		// crowds out the row that actually names a partner.
		//
		// The measurement lives with the rule, in SEVERITY_DESCENDING's javadoc, rather than being
		// restated here where the two copies would drift apart. What this case pins is the rule
		// itself: the tail asks first whether a note NAMES its partner, because the tail's job is
		// breadth, a rule that names nobody states none, and it may not outrank one that does.
		String section = tailSectionOf(tailRecordOf(
				"chartsearchai-test/drug-reference-unpromoted-tail-nameless.json",
				"is it safe to give namelessmix?"));

		assertTrue(section.toLowerCase().contains("metformin (moderate)"),
				"the row that names a partner leads the tail, whatever the nameless row is rated: "
						+ section);
		assertFalse(section.contains("LONGSTUB"),
				"and the nameless paragraph does not take the slot the budget cannot refuse: " + section);
	}

	@Test
	public void aRatedRowWithNoMechanismTextStillCountsAsNamingItsPartner() throws Exception {
		// Issue #355. InteractionNote.namesItsPartner is asked of the RULE in the constructor — it is
		// DrugSafetyValidator.partnerLabel's answer about it — rather than re-derived by comparing
		// the compact form to the full one. This case is why it cannot be re-derived: the two
		// coincide for a SECOND reason. A row carrying a token and a severity but no mechanism text
		// renders full as just the token, and `token (Severity)` is longer than that, so
		// orderedInteractionNotes' own never-grow guard resets compact to the full text — while the
		// row plainly does name its partner.
		//
		// So a derived flag reports false for it, the nameless unrated paragraphs beside it outrank
		// it under severityPriority (unrated sorts above Major), and the character budget then drops
		// the named Major partner out of the citable record altogether — issue #355's own cost,
		// reinstated. Write the derived form into the constructor and read the failures.
		RecordMapping record = tailRecordOf(
				"chartsearchai-test/drug-reference-unpromoted-tail-rated-noteless.json",
				"is it safe to give notelessstub?");
		String section = tailSectionOf(record);

		assertTrue(section.contains("warfarin"),
				"the row that names a partner leads the tail even though its severity-bearing short "
						+ "form would be LONGER than the name it renders, so its compact and full "
						+ "texts are the same string: " + section);
		assertTrue(section.indexOf("warfarin") < section.indexOf("ALPHA"),
				"and it leads it rather than merely surviving: a nameless unrated paragraph ranks "
						+ "above Major on severity alone, so nothing but the naming key puts this row "
						+ "in front of one: " + section);
		assertEquals(1, record.getWithheldInteractions(),
				"precondition: the budget cuts exactly one of the four rows here, so a row losing its "
						+ "place is a row LOST from the record rather than one merely reordered: "
						+ section);
	}

	@Test
	public void anAtcNamedRowWithNoMechanismTextStillCountsAsNamingItsPartner() throws Exception {
		// Issue #355, the other arm of the same key. DrugSafetyValidator.partnerLabel is
		// firstNonBlank(token, atc), so a rule the dataset identifies by an ATC code and nothing else
		// names its partner exactly as a tokened one does — and the flag InteractionNote records is
		// that method's answer, not its first argument's. The case beside this one contrasts a
		// TOKENED row with a row carrying neither, which both a token-only reading and the real
		// predicate answer alike; measured 2026-09-02, deriving the flag as
		// firstNonBlank(i.getToken()) != null left the whole api suite green.
		//
		// What that reading costs is this fixture's record: the nameless unrated paragraphs beside
		// the ATC-named row outrank it under severityPriority (unrated sorts above Major), and the
		// character budget then drops the Major partner out of the citable record — reported only as
		// a withheld count. Write the token-only form into the constructor and read the
		// failures.
		RecordMapping record = tailRecordOf(
				"chartsearchai-test/drug-reference-unpromoted-tail-atc-named.json",
				"is it safe to give atcnamedstub?");
		String section = tailSectionOf(record);

		assertTrue(section.contains("B01AA03"),
				"the row the dataset identifies by an ATC code and no token still names its partner, "
						+ "so it leads the tail: " + section);
		assertTrue(section.indexOf("B01AA03") < section.indexOf("ALPHA"),
				"and it leads it rather than merely surviving: a nameless unrated paragraph ranks "
						+ "above Major on severity alone, so nothing but the naming key puts this row "
						+ "in front of one: " + section);
		assertEquals(1, record.getWithheldInteractions(),
				"precondition: the budget cuts one of the four rows here, so a row losing its place "
						+ "is a row LOST from the record rather than one merely reordered: " + section);
	}

	@Test
	public void aNamelessRuleCarryingASeverityStillDoesNotDisplaceARowThatNamesItsPartner()
			throws Exception {
		// Issue #355. The naming key is independent of the rating, and this is the arrangement in
		// which that is observable: the nameless paragraph here carries Major while the row that
		// names a partner carries Moderate. Every other tail fixture leaves the nameless rows
		// unrated, so on them "names a partner" and "carries a severity" answer alike for every row
		// and reading the flag off the severity is indistinguishable from reading it off the name.
		// Measured 2026-09-02, deriving the flag as severity != null — which was the local two lines
		// above the construction site while the flag was passed in — left the whole api suite green.
		//
		// Under that reading the paragraph is promoted to the head of the tail on a rating that
		// names nobody, taking the one slot the character budget cannot refuse, and metformin leaves
		// the record. Write the severity reading into the constructor and read the failures.
		RecordMapping record = tailRecordOf(
				"chartsearchai-test/drug-reference-unpromoted-tail-rated-nameless.json",
				"is it safe to give ratednamelessmix?");
		String section = tailSectionOf(record);

		assertTrue(section.toLowerCase().contains("metformin (moderate)"),
				"the row that names a partner leads the tail even though the nameless row beside it "
						+ "is rated HIGHER: a rule that names nobody states no interaction, whatever "
						+ "its rating: " + section);
		assertFalse(section.contains("LONGSTUB"),
				"and the nameless paragraph does not take the slot the budget cannot refuse: " + section);
		assertEquals(1, record.getWithheldInteractions(),
				"precondition: the budget cuts one of the two rows here, so a row losing its place is "
						+ "a row LOST from the record rather than one merely reordered: " + section);
	}

	@Test
	public void aBlankButPresentTokenStillDoesNotDisplaceARowThatNamesItsPartner() throws Exception {
		// Issue #355, the fourth arm of the same key and the one review found last. The three cases
		// above discriminate InteractionNote.namesItsPartner from !compact.equals(rendered), from
		// firstNonBlank(i.getToken()) != null and from severity != null. This one discriminates it
		// from the obvious inlining of DrugSafetyValidator.partnerLabel MINUS firstNonBlank's blank
		// handling — i.getToken() != null || i.getAtc() != null — which, measured 2026-09-02 before
		// this case existed, left the whole build green.
		//
		// partnerLabel is firstNonBlank(token, atc), so a token that is nothing but a space gives the
		// rule NO name: compact falls back to the whole mechanism paragraph, exactly as for a row
		// carrying no token field at all. A presence test on the raw fields answers true for it, so
		// the paragraph is not demoted, and being unrated it outranks Major under severityPriority —
		// it then takes the one slot the character budget cannot refuse and metformin leaves the
		// record.
		//
		// Blank-but-present is a shape this module treats as real rather than as a typo:
		// DrugReference.Interaction.setToken normalises nothing and drug-reference-malformed.json
		// ships nameless rows, so a JSON "token": " " reaches partnerLabel as written. Write the
		// presence test into the constructor and read the failures.
		RecordMapping record = tailRecordOf(
				"chartsearchai-test/drug-reference-unpromoted-tail-blank-token.json",
				"is it safe to give blanktokenmix?");
		String section = tailSectionOf(record);

		assertTrue(section.toLowerCase().contains("metformin (moderate)"),
				"the row that names a partner leads the tail: a token that is only whitespace names "
						+ "nobody, and partnerLabel says so: " + section);
		assertFalse(section.contains("LONGSTUB"),
				"and the blank-tokened paragraph does not take the slot the budget cannot refuse: "
						+ section);
		assertEquals(1, record.getWithheldInteractions(),
				"precondition: the budget cuts one of the two rows here, so a row losing its place is "
						+ "a row LOST from the record rather than one merely reordered: " + section);
	}

	@Test
	public void anUnratedRowNamingItsPartnerStillLeadsANamelessRowAheadOfItInTheDataset()
			throws Exception {
		// Issue #355, the re-derivation of the same key that review round 4 found: a row that NAMES
		// its partner and is UNRATED, with a nameless unrated paragraph AHEAD of it in dataset order.
		// Every case above gives its naming row a RATING, so on each of them "names a partner" and
		// "carries a severity" answer alike for that row, and the conjunction of the two — label !=
		// null && severity != null — answers exactly as the real predicate does. Here it does not: it
		// reports FALSE for the metformin row, which then ties the nameless paragraph on the naming
		// key AND on severityPriority (both unrated), so the stable sort leaves the dataset order
		// that puts the paragraph first. It takes the one slot the character budget cannot refuse and
		// metformin leaves the record.
		//
		// That is not an exotic shape: every interaction row of the curated dataset this module
		// itself ships carries a token, an ATC code and no severity, and an operator-authored rule
		// normally does too — DDInter rates every row, so unrated arises from curated JSON, which is
		// where a token-bearing unrated row comes from.
		//
		// Since that round the flag is no longer a constructor argument at all — InteractionNote asks
		// DrugSafetyValidator.partnerLabel about the rule it is given — so this substitution and the
		// ones the cases above name can only be written inside that constructor. Write it there and
		// read the failures.
		RecordMapping record = tailRecordOf(
				"chartsearchai-test/drug-reference-unpromoted-tail-unrated-named.json",
				"is it safe to give unratednamedmix?");
		String section = tailSectionOf(record);

		assertTrue(section.contains("metformin"),
				"the row that names a partner leads the tail even though NEITHER row carries a "
						+ "rating, so nothing but the naming key can separate them: " + section);
		assertFalse(section.contains("LONGSTUB"),
				"and the nameless paragraph the dataset lists FIRST does not take the slot the budget "
						+ "cannot refuse: " + section);
		assertEquals(1, record.getWithheldInteractions(),
				"precondition: the budget cuts one of the two rows here, so a row losing its place is "
						+ "a row LOST from the record rather than one merely reordered: " + section);
	}

	/** The only record a curated {@code fixture} injects for {@code question}, for a patient on
	 *  nothing — the arrangement in which nothing is promoted and the interactions section is entirely
	 *  the dataset tail. The record rather than its text, because a case about the tail's bounds also
	 *  asks it for {@code getWithheldInteractions()}. */
	private RecordMapping tailRecordOf(String fixture, String question) throws Exception {
		return DrugReferenceTestSupport.injectedReferences(DrugReferenceTestSupport
				.injector(DrugReferenceTestSupport
						.serviceWith(DrugReferenceTestSupport.fixtureEntries(fixture)))
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null, null, null), question))
				.get(0);
	}

	/** {@code record}'s {@code Interactions:} section, case PRESERVED so a caller may assert on a
	 *  marker the record prints verbatim — which is what separates this from
	 *  {@link #interactionsSectionFor}, and why the two are not one method. */
	private String tailSectionOf(RecordMapping record) {
		int start = record.getText().indexOf("Interactions:");
		assertTrue(start >= 0,
				"precondition: the record must render an Interactions section, else the slice below "
						+ "dies on a negative index and names neither the premise nor the record: "
						+ record.getText());
		return record.getText().substring(start);
	}

	/** The injected drug-reference mapping (not just its text) whose rendering names {@code drug}. */
	private RecordMapping referenceMappingFor(PatientChart chart, String drug) {
		for (RecordMapping m : chart.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(m.getResourceType())
					&& m.getText() != null && m.getText().contains(drug)) {
				return m;
			}
		}
		throw new AssertionError("no injected drug-reference record mentions " + drug
				+ "; mappings=" + chart.getMappings().size());
	}

	/**
	 * The lowercased {@code Interactions:} section of the injected {@code entry} record, for a
	 * patient on {@code activeDrugs} asking about {@code entry}. The interaction-rendering tests that
	 * use it differ only in the drug set and which claim they make about the section, so the
	 * inject-then-locate-then-slice plumbing lives here rather than in each of them. It lowercases,
	 * so a case asserting on a marker the record prints verbatim uses {@code tailSectionOf} instead.
	 */
	private String interactionsSectionFor(String entry, String... activeDrugs) {
		String record = injectedMappingFor(entry, activeDrugs).getText();
		return record.substring(record.indexOf("Interactions:")).toLowerCase();
	}

	/** The injected {@code entry} record itself, for a patient on {@code activeDrugs} asking about
	 *  it — the mapping rather than only its text, for the assertions about the citation metadata
	 *  that deliberately is not in the text (issue #117). */
	private RecordMapping injectedMappingFor(String entry, String... activeDrugs) {
		PatientChart result = ddinterInjector().injectRecords(oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, set(activeDrugs), null, null, null),
				"is it safe to give " + entry.toLowerCase() + "?");
		return referenceMappingFor(result, entry);
	}

	/** The text of the injected drug-reference record whose rendering names {@code drug}. */
	private String referenceRecordContaining(PatientChart chart, String drug) {
		return referenceMappingFor(chart, drug).getText();
	}

	@Test
	public void dosingIsRenderedForMatchingAgeBand() {
		PatientChart result = injector().injectRecords(oneRecordChart(),
				context(5, null), "ibuprofen dose?");
		String injected = result.getMappings().get(1).getText();
		assertTrue(injected.contains("ages 2-11"), "should render the matching pediatric band");
		assertTrue(injected.contains("1200 mg/day"), "should render the band's daily maximum");
	}

	@Test
	public void dosingIsOmittedWhenAgeUnknown() {
		PatientChart result = injector().injectRecords(oneRecordChart(),
				context(null, null), "ibuprofen dose?");
		String injected = result.getMappings().get(1).getText();
		assertFalse(injected.contains("Dosing for ages"),
				"no numeric dosing when no age band matches; contraindication/interaction facts still render");
		assertTrue(injected.contains("Contraindicated with:"));
	}

	@Test
	public void noMatchReturnsChartUnchanged() {
		PatientChart chart = oneRecordChart();
		PatientChart result = injector().injectRecords(chart, context(5, null),
				"how is the patient doing?");
		assertSame(chart, result, "no reference match -> the same chart instance is returned");
	}

	@Test
	public void silentQuestionDoesNotInjectActiveOrders() {
		// A question that names no specific drug has no relevance anchor, so active-order references are
		// NOT injected — an active medication is noise for such a question. (The model still sees the
		// active-order records in the chart, and the safety validator reads active orders directly.)
		PatientChart chart = oneRecordChart();
		PatientChart result = injector().injectRecords(chart, context(5, set("M01AE01")), "summarise the plan");
		assertSame(chart, result,
				"a question naming no specific drug must not inject active-order references");
	}

	@Test
	public void unrelatedActiveOrderIsNotInjectedForADrugSpecificQuestion() {
		// The question is about gentamicin (J01GB); the active order is ibuprofen (M01AE) — a different
		// ATC class. The unrelated active-order reference must NOT be injected: it is noise for this
		// question and helps the clinician in no way.
		PatientChart result = injector().injectRecords(oneRecordChart(),
				context(40, set("M01AE01")), "is gentamicin safe to prescribe?");
		assertTrue(result.getText().contains("Drug reference — Gentamicin"),
				"the question's own drug should still be injected");
		assertFalse(result.getText().contains("Drug reference — Ibuprofen"),
				"an active order unrelated to the question's drug must not be injected");
	}

	@Test
	public void relatedActiveOrderIsStillInjectedForADrugSpecificQuestion() throws IOException {
		// The question is about naproxen (M01AE02); the active order is ibuprofen (M01AE01) — the same
		// ATC subgroup M01AE. That active order IS relevant (duplicate-therapy concern), so its
		// reference is still injected.
		PatientChart result = atcInjector().injectRecords(oneRecordChart(),
				context(40, set("M01AE01")), "is naproxen safe to prescribe?");
		assertTrue(result.getText().contains("Drug reference — Naproxen"),
				"the question's own drug should be injected");
		assertTrue(result.getText().contains("Drug reference — Ibuprofen"),
				"an active order in the same ATC subgroup as the question's drug should be injected");
	}

	@Test
	public void rendersAtcClassificationEntryWithNoRuleSections() {
		// An ATC-sourced entry carries class + ATC code but no dosing/interaction/contraindication
		// rules; the injected line must render cleanly (class + ATC) with none of the rule sections.
		DrugReference atc = new DrugReference();
		atc.setId("M01AE01");
		atc.setName("Ibuprofen");
		atc.setAliases(Collections.singletonList("ibuprofen"));
		atc.setAtcCodes(Collections.singletonList("M01AE01"));
		atc.setDrugClass("Propionic acid derivatives");
		DrugReferenceService svc = new DrugReferenceService();
		svc.setEntries(Collections.singletonList(atc));
		DrugReferenceInjector inj = new DrugReferenceInjector();
		inj.setDrugReferenceService(svc);

		PatientChart result = inj.injectRecords(oneRecordChart(), context(5, null), "what is the ibuprofen dose?");
		String injected = result.getMappings().get(1).getText();
		assertTrue(injected.contains("Drug reference — Ibuprofen"));
		assertTrue(injected.contains("Propionic acid derivatives"));
		assertTrue(injected.contains("ATC M01AE01"));
		assertFalse(injected.contains("Dosing for ages"), "ATC entry has no age bands -> no dosing line");
		assertFalse(injected.contains("Contraindicated with:"), "ATC entry has no contraindication rules");
		assertFalse(injected.contains("Interactions:"), "ATC entry has no interaction rules");
	}
}
