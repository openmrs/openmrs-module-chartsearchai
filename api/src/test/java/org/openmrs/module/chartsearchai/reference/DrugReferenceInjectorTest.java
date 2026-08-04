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
		return DrugReferenceTestSupport.injector(DrugReferenceTestSupport.bundledService());
	}

	/** Injector backed by the real WHO ATC sample (parsed by the real source), which — unlike the
	 *  bundled JSON — contains two drugs in the same ATC subgroup (ibuprofen/naproxen, both M01AE),
	 *  needed to exercise the "related active order" path. */
	private DrugReferenceInjector atcInjector() throws IOException {
		return DrugReferenceTestSupport.injector(DrugReferenceTestSupport.atcService(false));
	}

	/** Injector over the real bundled DDInter sample — the only bundled dataset whose entries carry
	 *  enough interaction partners (Lisinopril: 15) to exercise the render cap. */
	private DrugReferenceInjector ddinterInjector() {
		return DrugReferenceTestSupport.injector(DrugReferenceTestSupport.ddinterService());
	}

	/** Injector wired with the validator, so the deterministic findings can be injected pre-answer.
	 *  Groups are wired back because {@link DrugReferenceService#setEntries} deliberately clears them. */
	private DrugReferenceInjector ddinterInjectorWithSafety() {
		DrugReferenceService service = DrugReferenceTestSupport.ddinterService();
		service.setCrossReactivityGroups(DrugReferenceTestSupport.bundledGroups());
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));
		return injector;
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
		// the dataset's ordering rather than by the patient. In the real bundled DDInter sample
		// Lisinopril carries 15 partners and the 1500-char cut falls after seven of them, so
		// Ibuprofen — the LAST one, and a Moderate NSAID x ACE-inhibitor interaction that
		// attenuates the antihypertensive effect — was truncated out entirely.
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
		// keeps its mechanism prose to the dataset's ordering. On the bundled sample a patient on
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
		// what the default `minor` floor exists to keep out of the clinician's way — and it is the
		// first partner to fall PAST the render cap in dataset order (index 7; the cumulative
		// rendered length reaches 1546 there against a 1500-char budget), so its presence can only
		// come from promotion. Promoting on relevance alone pulled rows like it to the front of the
		// prompt, and measured on the 3.7.1 standalone the model then answered from them: two probe
		// cells that correctly abstained on the baseline began reporting "an Unknown severity
		// interaction between Erythromycin and Lisinopril", so the render path was bypassing a
		// safety decision the chip path enforces. Above-floor promotion still works
		// (renderedInteractionsMustNameThePartnerThePatientIsActuallyOn covers the Moderate case).
		String section = interactionsSectionFor("Lisinopril", "warfarin");
		assertFalse(section.contains("warfarin"),
				"an Unknown-severity rule must not be promoted past the render cap: " + section);
		assertTrue(section.contains("metformin"),
				"precondition: the section still renders its above-floor dataset-order partners: " + section);

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
	public void withNoPatientRelevantPartnerTheDatasetTailStillRendersFullNotesToTheBudget() {
		// The other side of the branch the test above pins, and the reason segment 2 is a branch at
		// all rather than one rule. A compact representative is the right cut only when a promoted
		// partner already carries the patient-specific content; with nothing promoted the general
		// material IS the record's content, so the budget is spent on full notes exactly as it was
		// before #117 — same entry, same question, and the ONLY difference is whether the patient is
		// on one of the partners.
		//
		// Nothing else distinguishes the two sides: renderCapBoundsBroadInteractionSets and
		// aSubFloorInteractionIsNotPromotedEvenWhenThePatientIsOnThatDrug both still pass if the
		// branch is collapsed to the single compact representative, and collapsing it is the obvious
		// simplification to reach for. It would strip every entry the patient has no overlap with —
		// the common case, since a question naming a drug the patient is not on is the ordinary
		// question — down to one bare partner name, with no failing test to say so.
		String section = interactionsSectionFor("Lisinopril");
		assertTrue(section.contains("metformin (moderate. limited data suggest"),
				"with nothing promoted the first dataset-order partner keeps its mechanism note, "
						+ "not the compact form the promoted case uses: " + section);
		assertTrue(section.contains("methotrexate"),
				"and the budget keeps admitting further partners rather than stopping at one: " + section);
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
	 * patient on {@code activeDrugs} asking about {@code entry}. The seven interaction-rendering
	 * tests differ only in the drug set and which claim they make about the section, so the
	 * inject-then-locate-then-slice plumbing lives here rather than in each of them.
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
