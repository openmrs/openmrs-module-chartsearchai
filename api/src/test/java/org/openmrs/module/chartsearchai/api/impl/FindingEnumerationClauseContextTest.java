/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;

/**
 * The #397 clause reaches the prompt of a chart the real injector gave several safety findings ABOUT
 * ONE DRUG, and does not reach one it gave none, one it gave a single finding, or one whose findings
 * name several drugs. Issue
 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a>.
 *
 * <p><b>Over the real pipeline and its own data</b>, which is what makes it worth having beside
 * {@code LlmProviderUserMessageTest}: that class puts a literal chart string to
 * {@code buildUserMessage} and so pins the RENDERING, while nothing there can tell whether the flag
 * production computes is ever true. This drives
 * {@code DrugReferenceTestSupport.injectedFindingsOver} — {@code validate} then
 * {@code injectRecords} over the bundled knowledge base — and puts the resulting chart to the
 * predicate the two answer paths call, so the two halves of the gate are checked against each other
 * rather than each against a fixture. Every arrangement below asserts its own PREMISE off the
 * injected chart first, so a case cannot start passing because the shipped data stopped raising the
 * findings it is about.
 *
 * <p><b>One case here is not about the clause at all</b>, and its own javadoc says so:
 * {@link #theCarriedIndexesReadInTheOrderTheInjectorWroteTheFindings} pins the ORDER contract of the
 * shared walk both halves of the gate project off, which is the property of it a real injected chart
 * can show and a hand-crafted mapping list cannot.
 *
 * <p>Neuter {@code LlmInferenceService.severalFindingsAboutOneDrug} to a constant and read the
 * failures — every clause case here asserts that predicate directly, so either constant reddens
 * this class. Neither reddens anything in {@code LlmProviderUserMessageTest}, which passes the flag as a
 * literal and never asks the predicate at all. The two cases that drive the real {@code search}
 * assert the flag the CALL SITES hand the provider, in both directions, which is a different
 * mutation: a literal at a call site leaves the predicate itself untouched.
 */
public class FindingEnumerationClauseContextTest {

	/** One question that puts one drug in play against four of the patient's active orders, so the
	 *  real screen raises several findings about one subject — the arrangement every case here needs
	 *  and the one each asserts as its own premise off the injected chart. */
	private static final String QUESTION = "Is it safe to start her on clarithromycin?";

	private static Set<String> setOf(String... values) {
		// LinkedHashSet and not a HashSet: the premise assertions below count the findings one
		// partner list raises, and the partner list's ORDER decides which rules the screen reaches
		// first, so a hash order would let two runs of this class count differently.
		return new LinkedHashSet<String>(Arrays.asList(values));
	}

	/** The two-order chart every case here injects over, through the real serializer. Private to
	 *  this file, per the convention this package states of its harnesses: nothing may rest on
	 *  another file building the same records, since a third order added to one copy would falsify
	 *  that silently, and every case below asserts what the screen raised off the chart in hand
	 *  rather than off an agreement between files. */
	private static PatientChart baseChart() {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-1", "Simvastatin 20mg tablet, 1 daily", null));
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-2", "Digoxin 125mcg tablet, 1 daily", null));
		return new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
	}

	private static PatientChart chartWithSeveralFindings() {
		return DrugReferenceTestSupport.injectedFindingsOver(baseChart(), QUESTION,
			setOf("Simvastatin", "Digoxin", "Sertraline", "Omeprazole"),
			setOf("C10AA01", "C01AA05", "N06AB06", "A02BC05"));
	}

	/** The question issue #113's screening arm runs on: an {@code interact*} cue and a MEDICATIONS
	 *  intent, naming no drug at all. */
	private static final String SCREENING_QUESTION = "do any of her meds interact?";

	/** The screen across her own orders, whose findings name several drugs — shared by the case that
	 *  asserts the predicate is false of it and the case that asserts the CALL SITES withhold the
	 *  flag for it, so the two cannot come to be about different arrangements. */
	private static PatientChart chartWithFindingsNamingSeveralDrugs() {
		return DrugReferenceTestSupport.injectedFindingsOver(baseChart(), SCREENING_QUESTION,
			setOf("Simvastatin", "Digoxin", "Sertraline", "Omeprazole", "Clarithromycin", "Amiodarone"),
			setOf("C10AA01", "C01AA05", "N06AB06", "A02BC05", "J01FA09", "C01BD01"));
	}

	/** The chart the mixed-type case below injects over: one prescription and the recorded allergy its context
	 *  carries, so the arrangement's two finding arms each have a chart record behind them. */
	private static PatientChart prescriptionAndAllergyChart() {
		List<SerializedRecord> records = new ArrayList<SerializedRecord>();
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER,
				"order-uuid-1", "Warfarin 5mg tablet, 1 daily", null));
		records.add(new SerializedRecord(ChartSearchAiConstants.RESOURCE_TYPE_ALLERGY,
				"allergy-uuid-1", "Allergy to acetylsalicylic acid", null));
		return new PatientChartSerializer().serialize(null, records, Collections.<String> emptySet());
	}

	@Test
	public void aChartTheScreenGaveSeveralFindingsAsksForOneLinePerFinding() {
		PatientChart chart = chartWithSeveralFindings();
		int findings = DrugReferenceTestSupport.injectedFindings(chart).size();
		Set<String> subjects = ChartSearchAiUtils.findingSubjects(chart.getMappings());
		assertTrue(findings > 1,
				"the premise: the real pipeline must inject more than one finding here, or the "
						+ "predicate below is satisfied by an arrangement that cannot show the defect. "
						+ "Injected: " + findings);
		assertEquals(1, subjects.size(),
				"and its other half: those findings must all name ONE drug, which is what makes this "
						+ "the arrangement the measured corpus is made of. Named: " + subjects);
		assertTrue(LlmInferenceService.severalFindingsAboutOneDrug(chart),
				"the predicate the two answer paths hand LlmProvider must be true of a chart the "
						+ "screen gave " + findings + " findings");
		String message = LlmProvider.buildUserMessage(chart.getText(), chart.getFocusIndices(),
			QUESTION, LlmInferenceService.severalFindingsAboutOneDrug(chart));
		assertTrue(message.contains("put every one of them on a line of its own"),
				"so the prompt this chart produces must carry the clause");
		assertTrue(message.indexOf("Clinician's query: ") < message.indexOf("put every one of them"),
				"after the question, which is the position that was measured");
	}

	/**
	 * THE SHARED WALK HANDS THE FINDINGS BACK IN THE ORDER THE INJECTOR WROTE THEM, which is a
	 * contract {@code ChartSearchAiUtils.safetyFindingMappings} STATES and nothing observed:
	 * re-collecting it in reverse — {@code findings.add(0, mapping)} — left the whole build green.
	 * Issue <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a>.
	 *
	 * <p><b>What rests on it.</b> The uncited indexes {@code measureFindingCitations} names at WARN
	 * are the one thing a maintainer has for WHICH finding an answer dropped, and
	 * {@code carriedFindingIndexes}' javadoc promises they read in the order the prompt carried
	 * them. Reversed, that line reads backwards against the prompt with every column of
	 * {@code findingCitations} unchanged — the gate reads {@code size()} and the subject cases
	 * compare against a Set, both of which ignore order. And the extraction's own argument is that
	 * further projections will be added off this one walk, so without this each would inherit an
	 * order premise nothing could see.
	 *
	 * <p><b>Two independent witnesses, which is what makes this a pin rather than a tautology.</b>
	 * {@code DrugReferenceTestSupport.injectedFindings} filters {@code chart.getMappings()} itself
	 * and never through the shared walk, so it reports the order the real injector wrote; the
	 * ascending assertion beside it is the premise that those indexes are the injector's own
	 * sequential numbering, without which a reversal would be unobservable in the values.
	 *
	 * <p><b>What it does NOT reach:</b> the COLLECTION type at either end, and the reason is
	 * structural rather than a property of this chart. The premise asserted above is that the
	 * injector numbers its findings ASCENDING, so {@code injectionOrder} — the expected value of the
	 * assertion below — IS a sorted list, and any collection that normalises to that order satisfies
	 * it on any chart: a {@code TreeSet} or a sort in place of the {@code LinkedHashSet} cannot fail
	 * this at all, and a {@code HashSet} escapes here too (measured). So that half of
	 * {@code carriedFindingIndexes}' javadoc claim is unobservable rather than pinned, and is said so
	 * rather than left to look guarded. Nothing follows about the WARN it protects, whose order is
	 * the prompt's and therefore the ascending one.
	 */
	@Test
	public void theCarriedIndexesReadInTheOrderTheInjectorWroteTheFindings() {
		PatientChart chart = chartWithSeveralFindings();
		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(chart);
		assertTrue(findings.size() > 1,
				"the premise: the real pipeline must inject more than one finding here, or no order "
						+ "is observable at all. Injected: " + findings.size());
		List<Integer> injectionOrder = new ArrayList<Integer>();
		for (RecordMapping finding : findings) {
			injectionOrder.add(Integer.valueOf(finding.getIndex()));
		}
		List<Integer> ascending = new ArrayList<Integer>(injectionOrder);
		Collections.sort(ascending);
		assertEquals(ascending, injectionOrder,
				"and its other half: the injector must number the findings it appends ASCENDING, or "
						+ "a reversal of the shared walk would not show in these values. Written: "
						+ injectionOrder);
		assertEquals(injectionOrder, new ArrayList<Integer>(
			SafetyFindingCitationExtentCheck.carriedFindingIndexes(chart.getMappings())),
				"the shared walk must hand the findings back in injection order, which is what "
						+ "carriedFindingIndexes' javadoc promises of the uncited-index WARN and what "
						+ "ChartSearchAiUtils.safetyFindingMappings states of every projection off it");
	}

	/**
	 * The FLAG ITSELF REACHES THE PROVIDER, AND IT IS READ OFF THE POST-INJECT CHART — the link the
	 * cases that pin the predicate and the renderer cannot see, in the TRUE direction. The case
	 * below is the same link in the FALSE direction, and is a separate case because a widened call
	 * site and a neutered one are two edits with two different consequences.
	 *
	 * <p>They pin the predicate and they pin the renderer; nothing between them was pinned, and that
	 * gap is not theoretical — replacing {@code severalFindingsAboutOneDrug(chart)} with a literal
	 * {@code false} at BOTH of {@code LlmInferenceService}'s answer call sites left the entire build
	 * green, which is issue #397's whole payload reverted in silence. This case drives the real
	 * {@code search} over a chart the real injector gave several findings and asserts what the
	 * provider was handed.
	 *
	 * <p><b>The harness serves the UN-injected chart and lets its stub injector be the thing that
	 * adds the findings</b>, which is what makes the WHERE checkable as well as the WHAT. An earlier
	 * version served the already-injected chart from the strategy and had the injector return it
	 * unchanged, so pre- and post-{@code inject()} were the same object and the natural maintainer
	 * mutation — hoisting the flag's local above the {@code inject()} line, beside
	 * {@code searchMode}, {@code referenceSlice} and {@code unresolvedDrugClass} — was invisible:
	 * {@code DrugReferenceInjector} is the sole producer of {@code safety_finding} mappings, so that
	 * hoist makes the flag unconditionally false. Move either assignment above its
	 * {@code inject()} call and read the failures.
	 *
	 * <p>Recorded rather than asserted inside the stub: an assertion thrown from a consumer the
	 * service calls inside its own try/catch would be swallowed into the fail-safe and read as a
	 * pass. Issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a>.
	 */
	@Test
	public void theFlagTheGateComputesIsWhatTheProviderIsHandedAndItIsReadAfterInjection() {
		PatientChart base = baseChart();
		PatientChart injected = chartWithSeveralFindings();
		assertFalse(LlmInferenceService.severalFindingsAboutOneDrug(base),
				"the premise this case rests on: the chart the strategy serves must carry no finding, "
						+ "so a flag read before inject() is false and the assertions below can tell "
						+ "the two positions apart");
		RecordingProvider provider = new RecordingProvider();
		TestableService service = newService(base, injected, provider);

		service.search(new Patient(), QUESTION);
		assertEquals(Boolean.TRUE, provider.lastFlag,
				"search must hand the provider the flag the gate computed for the chart the INJECTOR "
						+ "returned, which the case above proves is true of it. A literal false here, "
						+ "or a read of the pre-inject chart, reverts #397 with every test green");

		provider.lastFlag = null;
		service.searchStreaming(new Patient(), QUESTION, token -> { });
		assertEquals(Boolean.TRUE, provider.lastFlag,
				"and so must searchStreaming, which is the path the frontend uses by default");
	}

	/**
	 * AND THE CALL SITES HAND THE PROVIDER {@code false} FOR THE TWO POPULATIONS THE GATE EXISTS TO
	 * KEEP THE CLAUSE OFF — the other direction of the case above, and the one with the safety
	 * consequence.
	 *
	 * <p>The case above observes one value, {@code TRUE}, at both legs, and before this case nothing
	 * in the suite observed a call site handing {@code false} — so the gate could be WIDENED there,
	 * by a second condition OR-ed into the flag or by a literal {@code true}. Measured:
	 * {@code = true} at both of {@code LlmInferenceService}'s answer call sites reddened nothing in
	 * the whole build. The two populations below are the ones such an edit sends the 126-character
	 * sentence to, and the failure is silent — it surfaces only as changed model prose.
	 *
	 * <p><b>The first arm also closes the flag's READ POSITION in the opposite direction to the case
	 * above.</b> The strategy serves the chart with the findings and the injector hands back the
	 * finding-free one, so a flag read BEFORE {@code inject()} is TRUE here and FALSE after — the
	 * mirror of that case's arrangement. Between the two, a hoist of either local above its
	 * {@code inject()} line is caught whichever way the injection moves the answer.
	 *
	 * <p>The second arm is the interaction-screening population, whose findings name several drugs
	 * so the clause's {@code it} has no referent. A call site re-expressing the record-count half
	 * alone — {@code carriedFindingIndexes(mappings).size() > 1} without the subject conjunct — is
	 * false on the first arm's chart and true on this one, which is why both are here. Issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a>.
	 */
	@Test
	public void theCallSitesHandTheProviderFalseForThePopulationsTheGateWithholdsFrom() {
		PatientChart findingFree = baseChart();
		assertFalse(LlmInferenceService.severalFindingsAboutOneDrug(findingFree),
				"the premise of the first arm: the chart the injector hands back must carry no "
						+ "finding, which is the absent-data population's own shape");
		RecordingProvider provider = new RecordingProvider();
		TestableService service = newService(chartWithSeveralFindings(), findingFree, provider);

		service.search(new Patient(), QUESTION);
		assertEquals(Boolean.FALSE, provider.lastFlag,
				"search must hand the provider false for a chart carrying no finding — a literal "
						+ "true, or a second condition OR-ed into the flag, sends the clause to the "
						+ "empty-chart message AbsentDataEvalTest pins to exact bytes");
		provider.lastFlag = null;
		service.searchStreaming(new Patient(), QUESTION, token -> { });
		assertEquals(Boolean.FALSE, provider.lastFlag,
				"and so must searchStreaming, which is the path the frontend uses by default");

		PatientChart severalDrugs = chartWithFindingsNamingSeveralDrugs();
		Set<String> screeningSubjects = ChartSearchAiUtils.findingSubjects(severalDrugs.getMappings());
		assertTrue(DrugReferenceTestSupport.injectedFindings(severalDrugs).size() > 1,
				"the premise of the second arm: the screen must raise more than one finding, or the "
						+ "record-count conjunct is what withholds the flag and this arm says nothing");
		assertTrue(screeningSubjects.size() > 1,
				"and the half this arm is about: those findings must name several drugs, or it is the "
						+ "first arm again. Named: " + screeningSubjects);
		RecordingProvider screening = new RecordingProvider();
		TestableService screeningService = newService(baseChart(), severalDrugs, screening);

		screeningService.search(new Patient(), SCREENING_QUESTION);
		assertEquals(Boolean.FALSE, screening.lastFlag,
				"and search must hand it false for the interaction screen, whose findings name "
						+ "several drugs — the sentence asks for every finding naming ONE of them");
		screening.lastFlag = null;
		screeningService.searchStreaming(new Patient(), SCREENING_QUESTION, token -> { });
		assertEquals(Boolean.FALSE, screening.lastFlag,
				"and so must searchStreaming on it");
	}

	/**
	 * A CHART WHOSE FINDINGS MIX TYPES ABOUT ONE DRUG STILL ASKS FOR ONE LINE PER FINDING — the
	 * population that reaches {@code ChartSearchAiUtils.findingSubjects}' key SPLIT, which every
	 * other arrangement in this class leaves a no-op.
	 *
	 * <p>Those all inject interaction findings only, so the finding's TYPE and its drug are in
	 * one-to-one correspondence and comparing whole {@code resourceKey} composites gives the same
	 * answer as comparing the drug halves. This arrangement separates them: a recorded allergy and
	 * an active order that interacts both fire on the one drug the question puts in play, so the
	 * chart carries {@code contraindication:<drug>} beside {@code interaction:<drug>} — two keys,
	 * one subject. Simplify the split to {@code subjects.add(key)} and this case is the one that
	 * reddens; the clause silently stops reaching a population the real injector produces over the
	 * bundled knowledge base, which is what this case's own premises assert of it.
	 *
	 * <p><b>The premise is the KEY COUNT and the assertion is the SUBJECT set</b>, which is what
	 * makes the case say what it means without splitting anything itself: two distinct composites
	 * that yield one subject can only be two types about one drug. Issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a>.
	 */
	@Test
	public void aChartWhoseFindingsMixTypesAboutOneDrugAsksForOneLinePerFinding() {
		String question = "Should I give her ibuprofen?";
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOverWithRecordedAllergies(
			prescriptionAndAllergyChart(), question, setOf("Warfarin"), setOf("B01AA03"),
			setOf("acetylsalicylic acid"));
		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(chart);
		Set<String> keys = new LinkedHashSet<String>();
		for (RecordMapping finding : findings) {
			keys.add(finding.getResourceUuid());
		}
		assertTrue(findings.size() > 1,
				"the premise: the real pipeline must inject more than one finding here. Injected: "
						+ findings.size());
		assertEquals(2, keys.size(),
				"and the premise this case is about: those findings must carry TWO distinct composite "
						+ "keys, or the split under test is a no-op here as it is everywhere else in "
						+ "this class. Keys: " + keys);
		assertEquals(setOf("Ibuprofen"), ChartSearchAiUtils.findingSubjects(chart.getMappings()),
				"so the subjects must be the one DRUG those two keys are about — not the composites, "
						+ "and not the types. Keys: " + keys);
		assertTrue(LlmInferenceService.severalFindingsAboutOneDrug(chart),
				"and the predicate must be true, or this population loses the clause");
		assertTrue(LlmProvider.buildUserMessage(chart.getText(), chart.getFocusIndices(),
			question, LlmInferenceService.severalFindingsAboutOneDrug(chart))
				.contains("put every one of them on a line of its own"),
				"and its prompt must carry the clause");
	}

	@Test
	public void aChartTheScreenGaveExactlyOneFindingAsksForNothingEither() {
		// The THRESHOLD, and it is pinned because nothing else reaches it: mutating `> 1` to `> 0`
		// leaves both cases either side of this one green (measured). One finding is not an
		// enumeration, so the clause has nothing to shape and its own antecedent is false — the cost
		// of sending it anyway is only the sentence, which is why this is a judgement rather than a
		// correctness property, and why it is pinned here rather than argued in a comment.
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOver(baseChart(), QUESTION,
			setOf("Simvastatin"), setOf("C10AA01"));
		int findings = DrugReferenceTestSupport.injectedFindings(chart).size();
		assertTrue(findings == 1,
				"the premise: this arrangement must raise exactly one finding, or the threshold is "
						+ "not what is being tested. Injected: " + findings);
		assertFalse(LlmInferenceService.severalFindingsAboutOneDrug(chart),
				"one finding is not several, so the predicate must be false");
	}

	@Test
	public void aChartTheScreenGaveNoFindingAsksForNothing() {
		// The base chart with no injection over it: the screen raised nothing, so there is no
		// enumeration to shape and the message must be what it was before #397.
		PatientChart chart = baseChart();
		assertTrue(DrugReferenceTestSupport.injectedFindings(chart).isEmpty(),
				"the premise: an un-injected chart carries no safety finding");
		assertFalse(LlmInferenceService.severalFindingsAboutOneDrug(chart),
				"and the predicate must be false of it");
		assertFalse(LlmProvider.buildUserMessage(chart.getText(), Collections.<Integer>emptyList(),
			QUESTION, LlmInferenceService.severalFindingsAboutOneDrug(chart))
				.contains("put every one of them"),
				"so its prompt carries no clause — which is what keeps the sentence off the "
						+ "absent-data message AbsentDataEvalTest pins to exact bytes");
	}

	/**
	 * A SCREEN ACROSS HER OWN ORDERS ASKS FOR NOTHING, because its findings name several drugs and
	 * the clause's {@code it} then has no single referent. Issue #113's own population, and issue
	 * #397's first review round found the clause reaching it:
	 * {@code QueryScopeRouter.isInteractionScreening} needs an {@code interact*} cue and a
	 * MEDICATIONS intent and never a named drug, so the question this case asks names no drug at
	 * all.
	 *
	 * <p>The premise is asserted two ways — more than one finding, and more than one SUBJECT —
	 * because the count alone would let this case pass on an arrangement that says nothing about the
	 * subject conjunct. Delete {@code ChartSearchAiUtils.findingSubjects(...).size() == 1} from the
	 * predicate and this case is the one that reddens.
	 */
	@Test
	public void aScreenAcrossHerOwnOrdersAsksForNothing() {
		assertTrue(QueryScopeRouter.isInteractionScreening(SCREENING_QUESTION),
				"the premise: this must be the question class issue #113's screening arm runs on, or "
						+ "the case is not about that population");
		PatientChart chart = chartWithFindingsNamingSeveralDrugs();
		int findings = DrugReferenceTestSupport.injectedFindings(chart).size();
		Set<String> subjects = ChartSearchAiUtils.findingSubjects(chart.getMappings());
		assertTrue(findings > 1,
				"the premise: the screen must raise more than one finding, or the record-count "
						+ "conjunct is what withholds the clause and this case says nothing. Injected: "
						+ findings);
		assertTrue(subjects.size() > 1,
				"and the premise this case is actually about: those findings must name several drugs. "
						+ "Named: " + subjects);
		assertFalse(LlmInferenceService.severalFindingsAboutOneDrug(chart),
				"so the predicate must be false — the sentence asks for every finding that names ONE "
						+ "drug, and this chart offers " + subjects.size() + " candidates for it");
		assertFalse(LlmProvider.buildUserMessage(chart.getText(), chart.getFocusIndices(),
			SCREENING_QUESTION, LlmInferenceService.severalFindingsAboutOneDrug(chart))
				.contains("put every one of them"),
				"and its prompt must carry no clause");
	}

	/**
	 * A SCREENING PHRASING THAT NAMES ITS DRUG STILL ASKS FOR ONE LINE PER FINDING — the other side
	 * of the case above, and what stops the conjunct being replaced by the cheaper-looking
	 * {@code !QueryScopeRouter.isInteractionScreening(question)}.
	 *
	 * <p>Measured over the bundled knowledge base: this question carries the screening cue AND names
	 * a drug, so the screening arm stands down (its gate needs the question to resolve no drug), the
	 * drug-in-play arm runs, and every finding names the one drug the question names. A phrasing gate
	 * withholds the clause here for no reason; the subject gate sends it. The two premises this case
	 * asserts are what make that entailed rather than argued: the phrasing predicate is true here and
	 * the subject count is one, so a predicate resting on the phrasing must fail the assertion below.
	 */
	@Test
	public void aScreeningPhrasingThatNamesItsDrugStillAsksForOneLinePerFinding() {
		String question = "Does clarithromycin interact with any of her current medications?";
		assertTrue(QueryScopeRouter.isInteractionScreening(question),
				"the premise: this phrasing must carry the screening cue, or the case cannot show "
						+ "that the gate is not a phrasing carve-out");
		PatientChart chart = DrugReferenceTestSupport.injectedFindingsOver(baseChart(), question,
			setOf("Simvastatin", "Digoxin", "Sertraline", "Omeprazole"),
			setOf("C10AA01", "C01AA05", "N06AB06", "A02BC05"));
		Set<String> subjects = ChartSearchAiUtils.findingSubjects(chart.getMappings());
		assertTrue(DrugReferenceTestSupport.injectedFindings(chart).size() > 1,
				"the premise: more than one finding");
		assertEquals(1, subjects.size(),
				"and the premise this case is about: they must all name one drug. Named: " + subjects);
		assertTrue(LlmInferenceService.severalFindingsAboutOneDrug(chart),
				"so the predicate must be TRUE — the drug the clause's `it` refers to is the one the "
						+ "question names, whatever cue words sit around it");
		assertTrue(LlmProvider.buildUserMessage(chart.getText(), chart.getFocusIndices(),
			question, LlmInferenceService.severalFindingsAboutOneDrug(chart))
				.contains("put every one of them on a line of its own"),
				"and its prompt must carry the clause");
	}

	/**
	 * AND THE PROGRESSIVE-REASONING PREVIEW IS HANDED {@code false} EVEN WHERE THE COMMITTED ANSWER
	 * IS HANDED {@code true} — the third call site, and the one nothing in the suite observed in
	 * either direction.
	 *
	 * <p>The two cases above assert the flag the two ANSWER call sites hand the provider. The
	 * preview pass in {@code LlmInferenceService.maybeEmitPreliminaryReasoning} hands a literal
	 * {@code false}, and the edit that matters there is not flipping that literal: it is threading
	 * {@code searchStreaming}'s own {@code enumerateFindings} down into the method, which is the
	 * natural edit because that flag is a live local at the call three lines above. Neither edit can
	 * be seen by a case whose chart carries no finding — both values are {@code false} there — so
	 * this case is the one arrangement that tells them apart: the committed answer's chart is the
	 * real injector's several-findings-one-drug chart, so its flag is {@code true}, while the
	 * focused slice the preview reads carries no finding at all.
	 *
	 * <p><b>The focused slice cannot carry one, and that is the load-bearing reason for the
	 * literal.</b> {@code ChartBuildingStrategy.buildFocusedChart} goes to
	 * {@code QueryStoreChartBuilder.buildFocused}, which never calls the injector — the sole
	 * producer of {@code safety_finding} mappings — so the preview prompt has nothing to enumerate
	 * and the sentence would be spent on the one pass that shares llama-server's single slot with
	 * the committed answer. The harness serves the un-injected base chart there for exactly that
	 * shape.
	 *
	 * <p>Both premises are asserted: two streaming passes reached the provider (the preview is
	 * inside its own try/catch, so a skipped one would otherwise leave this case passing on a
	 * single call), and the null KV scope identifies which of them is the preview. Issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/397">#397</a>.
	 */
	@Test
	public void theProgressiveReasoningPreviewIsHandedFalseWhereTheCommittedAnswerIsHandedTrue() {
		PatientChart injected = chartWithSeveralFindings();
		assertTrue(LlmInferenceService.severalFindingsAboutOneDrug(injected),
				"the premise: the committed answer's chart must ask for the clause, or the two passes "
						+ "are handed the same flag and this case cannot tell the literal from a "
						+ "forwarded one");
		assertFalse(LlmInferenceService.severalFindingsAboutOneDrug(baseChart()),
				"and its other half: the focused slice the preview reads must carry no finding, which "
						+ "is what buildFocused produces by construction");
		RecordingProvider provider = new RecordingProvider();
		TestableService service = newService(baseChart(), injected, provider);
		service.progressiveEnabled = true;
		Patient patient = new Patient();
		patient.setUuid("uuid-1");

		service.searchStreaming(patient, QUESTION, token -> { });

		assertEquals(2, provider.streamingFlags.size(),
				"the premise: both the preview pass and the committed pass must have reached the "
						+ "provider, or this case says nothing about the preview. Scopes: "
						+ provider.streamingScopes);
		assertEquals(Arrays.asList(null, "uuid-1"), provider.streamingScopes,
				"and the preview is the null-scoped pass, which is what identifies it — it must never "
						+ "read or overwrite the patient's full-chart KV entry");
		assertEquals(Arrays.asList(Boolean.FALSE, Boolean.TRUE), provider.streamingFlags,
				"so the preview must be handed false and the committed answer true. A literal true at "
						+ "the preview call, or searchStreaming's own flag threaded into "
						+ "maybeEmitPreliminaryReasoning, makes both true and sends the clause to a "
						+ "prompt carrying no finding to enumerate");
	}

	/** A private harness, per the convention this package states — not a shared one. The strategy
	 *  serves {@code built} and the stub injector RETURNS {@code injected} rather than its argument,
	 *  which is how the two positions of the flag's read are told apart — see
	 *  {@link #theFlagTheGateComputesIsWhatTheProviderIsHandedAndItIsReadAfterInjection}. */
	private TestableService newService(PatientChart built, final PatientChart injected,
			RecordingProvider provider) {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(built));
		created.setLlmProvider(provider);
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question) {
				return injected;
			}
		});
		created.setDrugSafetyValidator(new DrugSafetyValidator() {

			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
				return Collections.emptyList();
			}
		});
		return created;
	}

	/** No-ops the Context-backed resolvers so no OpenMRS runtime is needed. */
	private static final class TestableService extends LlmInferenceService {

		/** Off for every case but the preview one, which is the only path with a second LLM pass. */
		private boolean progressiveEnabled;

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
			return false;
		}

		@Override
		protected boolean resolveProgressiveReasoningEnabled() {
			return progressiveEnabled;
		}

		// The preview is a fullChart-mode feature and disengages in queryScoped, which is the
		// shipped default and what the unstubbed resolver would return with no Context — so without
		// this the preview case would skip the pass it is about and pass on one recorded call.
		@Override
		protected boolean resolveQueryScopedMode() {
			return false;
		}
	}

	private static final class StubStrategy extends ChartBuildingStrategy {

		private final PatientChart chart;

		private StubStrategy(PatientChart chart) {
			this.chart = chart;
		}

		@Override
		PatientChart buildChart(Patient patient, String question) {
			return chart;
		}

		/** The focused slice the preview pass reads. It is NOT the injected chart and cannot be:
		 *  {@code QueryStoreChartBuilder.buildFocused} never runs the injector, so no focused slice
		 *  that production builds carries a {@code safety_finding} record. Serving the un-injected base
		 *  chart is that shape, and it is what makes the preview's flag false on its own merits. */
		@Override
		PatientChart buildFocusedChart(Patient patient, String question) {
			return baseChart();
		}

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	/** Records the flag rather than asserting on it — see the case's javadoc for why.
	 *
	 *  <p>Every streaming pass in arrival order as well as the last one, because with progressive
	 *  reasoning on there are TWO and they are handed different flags: {@code lastFlag} alone cannot
	 *  say what the preview was given. The scope is recorded beside it for the same reason
	 *  {@code LlmInferenceServiceProgressiveReasoningTest} records it — a null KV scope is what
	 *  identifies the preview pass. */
	private static final class RecordingProvider extends LlmProvider {

		private Boolean lastFlag;

		/** The flag each streaming pass was handed, in order. */
		private final List<Boolean> streamingFlags = new ArrayList<Boolean>();

		/** The KV-cache scope each streaming pass was handed, in order — null is the preview. */
		private final List<String> streamingScopes = new ArrayList<String>();

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices,
				String question, boolean enumerateFindings) {
			lastFlag = Boolean.valueOf(enumerateFindings);
			return new LlmResponse("No.", Collections.<Integer> emptyList());
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope, boolean enumerateFindings) {
			lastFlag = Boolean.valueOf(enumerateFindings);
			streamingFlags.add(Boolean.valueOf(enumerateFindings));
			streamingScopes.add(cacheScope);
			return new LlmResponse("No.", Collections.<Integer> emptyList());
		}
	}
}
