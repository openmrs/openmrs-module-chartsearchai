/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.ChartSearchService.ChartAnswer;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.PairChipExtent;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #142: the model can transcribe an ATC class code wrongly while citing the deterministic
 * record that carries the right one. Observed live, twice on one probe: the chip said
 * {@code J01MA} (fluoroquinolones) and the answer, citing that finding's record number, said
 * {@code J01CA} (penicillins) — two drug families, one character apart, in a sentence a clinician
 * reads as a classification claim.
 *
 * <p>Nothing in the pipeline could see it. A citation of an injected finding never enters Tier-2
 * entailment at all (reference-group citations are demote-only), so the only pass that sees it is
 * Tier-1 cosine, which a two-character edit inside an alphanumeric token barely moves; where Tier-2
 * does run, on a chart record, it is paraphrase-tolerant, which is the wrong tolerance for a code
 * substitution. The citation itself is valid, so index validation passes. The chip is right, the
 * record is right, the citation is right, and the sentence is wrong.
 *
 * <p>What this file pins is the deterministic check that closes it: when the records an answer
 * cites state class codes, every ATC-shaped token in the answer must be one of them — or one the
 * QUESTION states, which is the reader's own word and not a fabrication — and one that is not is
 * reported at WARN carrying both the code the answer states and the codes its cited records state.
 * When they state none, there was nothing to copy and the check says nothing. Since issue #338 it
 * also pins what the answer does with a code it copied FAITHFULLY: a code stated more than once
 * inside one parenthetical, and a citation marker placed inside one, are reported from the shape of
 * the prose alone, each parenthetical read at its own level — see the block above
 * {@link #aClassCodeRepeatedInsideOneParentheticalIsReported()} for what that level buys and
 * what it gives up. There is deliberately
 * no roll-up from a cited substance code to its class: correct as such an answer usually is,
 * accepting it silences this issue's own headline capture, and
 * {@link #generalisingACitedSubstanceCodeToItsClassIsReported()} pins that with the reason. The answer prose
 * is never rewritten — a silent edit of a clinician-facing sentence is a larger decision than this
 * check, and a visible flag is worth more than a quiet repair.
 *
 * <p>Everything here runs through the real {@link LlmInferenceService#search}/
 * {@code searchStreaming} orchestration over REAL production-rendered records: the cited finding is
 * the one the real validate → injectRecords → renderFinding chain produces off the bundled DDInter
 * sample for a patient on a fluoroquinolone asked about ciprofloxacin, so the code the check
 * compares against is the code production actually writes. Only the model is stubbed — answer prose
 * is not reproducible on a live engine, so a canned answer is the only way to fix the one variable
 * this check is about.
 */
public class ClassCodeFidelityTest {

	/** #142's live shape, reproducible from the DDInter excerpt: ciprofloxacin against a patient
	 *  already on a fluoroquinolone raises the duplicate-therapy chip whose class is J01MA. */
	private static final String QUESTION = "is it safe to give ciprofloxacin?";

	private static final String ACTIVE_DRUG = "levofloxacin";

	private static final String ACTIVE_ATC = "J01MA12";

	/** The class the injected finding really states — asserted below, not assumed. */
	private static final String TRUE_CODE = "J01MA";

	/** #142's observed miscopy: penicillins, one character from the fluoroquinolone subgroup. */
	private static final String MISCOPIED_CODE = "J01CA";

	/** The check's own logger: the narrowest capture that can satisfy a "it was reported"
	 *  assertion, so no other class's WARN can stand in for the check's. */
	private static final String CHECK = ClassCodeFidelityCheck.class.getName();

	/** The package, for the assertions whose claim is SILENCE. A class-scoped capture of a silent
	 *  class receives nothing, which is exactly the state that makes "no WARN was logged" pass
	 *  vacuously (LogCapture's javadoc); the package capture also receives
	 *  {@code LlmInferenceService}'s own [timing] INFO line, so the capture can be shown live.
	 *
	 *  <p><b>Since issue #337 those assertions span a second check.</b> {@code
	 *  ReferenceProseFidelityCheck} logs into this same package from this same {@code search()} call,
	 *  and the canned answers below restate the module-composed headline the cited finding record
	 *  itself carries — so the PACKAGE-scoped silence cases in this file also pin that check's
	 *  {@code MIN_REPRODUCED_WORDS}, and lowering it drops some of them to red with a failure message
	 *  about class codes (measured at a floor of nine). That coupling is deliberate on that side —
	 *  its constant's javadoc names this file as what pins it from below — and it is recorded here so
	 *  a maintainer who reddens them is not left reading the wrong file.
	 *
	 *  <p><b>WHICH of them redden is deliberately not enumerated, here or there.</b> This note named
	 *  a count, and so did that javadoc and ADR Decision 61 beside it; the merge that brought issue
	 *  #338's cases into this file falsified all three in one commit, and a count that replaces a
	 *  stale count goes stale on the next merge. Mutate the constant and read the failures. */
	private static final String PACKAGE = "org.openmrs.module.chartsearchai.api.impl";

	private TestableService service;

	private PatientChart chart;

	private RecordMapping finding;

	@BeforeEach
	public void setUp() {
		// One arrangement, read twice: the chart production would hand the model, and the
		// safety-finding record inside it whose citation index the canned answers cite.
		chart = DrugReferenceTestSupport.injectedSafetyFindingChart(QUESTION, ACTIVE_DRUG, ACTIVE_ATC);
		finding = DrugReferenceTestSupport.safetyFindingIn(chart);
		assertTrue(ClassCodeFidelityCheck.classCodesIn(finding.getText()).contains(TRUE_CODE),
				"the premise: the real injected finding states the class code the answer must copy — "
						+ "read by the production predicate, not by a rendering detail. Was: "
						+ finding.getText());
		service = newService(chart);
	}

	private TestableService newService(PatientChart served) {
		TestableService created = new TestableService();
		created.setChartBuildingStrategy(new StubStrategy(served));
		created.setDrugReferenceInjector(new DrugReferenceInjector() {

			@Override
			public PatientChart inject(PatientChart chart, Patient patient, String question) {
				return chart;
			}
		});
		created.setDrugSafetyValidator(new DrugSafetyValidator() {

			// The overload production actually calls: mappings-carrying for echo scoping (issue #105)
			// and sink-carrying since issue #336. Stubbing the four-argument one instead leaves this
			// stub INERT — production would not reach it — which is why it names both parameters.
			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings, PairChipExtent.Sink pairExtentSink) {
				return Collections.emptyList();
			}
		});
		return created;
	}

	@Test
	public void search_shouldReportAClassCodeNoCitedRecordStates() {
		service.setLlmProvider(answering(sentenceStating(MISCOPIED_CODE)));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"an ATC class the cited record does not state is a fabricated classification claim "
							+ "and must be reported. Captured: " + capture.describeAll());
			assertTrue(warnStating(capture, "[" + MISCOPIED_CODE + "]", TRUE_CODE, "patient=1"),
					"the WARN has to carry the code the answer states, the codes its cited records "
							+ "state AND the patient, or a maintainer reading a log with concurrent "
							+ "requests in it cannot reconstruct the miscopy. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void search_shouldStaySilentWhenTheAnswerCopiesTheCodeFaithfully() {
		// The other half of the pair above, on the same arrangement: that one fails if the check
		// never runs, this one fails if it accuses a faithful answer. Neither alone discriminates.
		service.setLlmProvider(answering(sentenceStating(TRUE_CODE)));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a faithful recitation must stay quiet, or the check is noise every install learns "
							+ "to ignore. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void searchStreaming_shouldReportItOnThePrimaryProductionPathToo() {
		// /search/stream is the path users hit: a check wired only into search() would be absent
		// from production traffic while every non-streaming test stayed green.
		service.setLlmProvider(answering(sentenceStating(MISCOPIED_CODE)));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.searchStreaming(patient(), QUESTION, token -> { });
			assertTrue(warnStating(capture, "[" + MISCOPIED_CODE + "]", TRUE_CODE),
					"the streaming path must run the same check. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void anInventedSecondCodeIsReportedEvenBesideTheTrueOne() {
		// The live capture on Sarah Taylor: chip (H02AB), answer "(H02AB, S01BA02)". The true code
		// is present, so a containment check over the answer passes; the invented one is still a
		// classification claim no record supports.
		service.setLlmProvider(answering(sentenceStating(TRUE_CODE + ", S01BA02")));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "[S01BA02]", TRUE_CODE),
					"only the code no cited record states may be reported, and it must be reported "
							+ "even when the true code is stated beside it. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aTruncatedCodeIsReportedBecauseTheComparisonIsWholeToken() {
		// The live capture on George Anderson: chip (A02BC), answer "(A02B)" — the parent group, a
		// true prefix of the code the record states and a different, broader claim. A substring
		// comparison against the record text would pass it.
		service.setLlmProvider(answering(sentenceStating("J01M")));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "[J01M]", TRUE_CODE),
					"a code that is a prefix of the cited record's own is a different claim, not a "
							+ "copy of it. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aCodeStatedByAnyCitedRecordIsAccepted() {
		// The check is against every cited record, chart evidence and reference material alike — a
		// code can legitimately be read off a chart record, so an implementation that only inspects
		// the module's own injected findings would flag a faithful answer. This chart record is
		// rendered by the real PatientChartSerializer.
		PatientChart charted = chartRecordStating("Antibiotic history: prior course of a "
				+ "fluoroquinolone (" + TRUE_CODE + ") documented");
		TestableService onCharted = newService(charted);
		onCharted.setLlmProvider(answering("The chart documents ATC class " + TRUE_CODE + " [1]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			onCharted.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a code the cited CHART record states is supported. Captured: "
							+ capture.describeAll());
		}
		// The second half is what makes the first half readable: silence is also what a check that
		// never ran produces. Same chart, same citation, one character different in the answer.
		onCharted.setLlmProvider(answering("The chart documents ATC class " + MISCOPIED_CODE + " [1]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			onCharted.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "[" + MISCOPIED_CODE + "]", TRUE_CODE),
					"the chart record was read: a code it does not state must still be reported. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aCitedRecordThatStatesNoClassCodeLeavesNothingToHaveCopied() {
		// The gate on its own terms: a real citation of a real record that simply carries no class
		// code — the shape behind "Ceftriaxone 1g IV Q12H [2]". Distinct from the no-citation case
		// below, which reaches the same branch through a different production rule (#76's
		// abstention-dump guard) that this change does not own.
		PatientChart codeless = chartRecordStating("Antibiotic course: ceftriaxone 1 g IV completed");
		TestableService onCodeless = newService(codeless);
		onCodeless.setLlmProvider(answering("She had ceftriaxone, ATC class " + MISCOPIED_CODE
				+ " [1]."));
		try (LogCapture capture = LogCapture.on(CHECK, Level.DEBUG)) {
			onCodeless.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"with no code in what it cites, nothing was copied. Captured: "
							+ capture.describeAll());
			assertTrue(debugStating(capture, "no cited record states a class code"),
					"the gate that declined has to be identifiable — two different DEBUG lines can "
							+ "produce this silence. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void anAnswerThatCitesNothingIsNotChecked() {
		// The first gate. This answer anchors no citation at all, so the pipeline surfaces no
		// reference (the abstention-dump guard) and no cited record states a code — there was
		// nothing to copy, so there is no copy to be unfaithful to. Reporting here would mean
		// reporting on a resemblance: it is what turns every Q12H-shaped token in ordinary prose
		// into a WARN. Asserted at DEBUG on the check's own logger, so the case pins that the check
		// RAN and declined rather than that it was never reached.
		service.setLlmProvider(answering("Ciprofloxacin is in the same ATC class (" + MISCOPIED_CODE
				+ ") as the patient's active order."));
		try (LogCapture capture = LogCapture.on(CHECK, Level.DEBUG)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"with nothing cited there is nothing to have copied. Captured: "
							+ capture.describeAll());
			assertTrue(debugStating(capture, "no cited record states a class code"),
					"the check ran and declined, and which gate declined has to be identifiable. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aDosingFrequencyIsNotAClassCode() {
		// Q12H has exactly the level-4 shape and is ordinary prescribing prose. Q is not one of
		// ATC's fourteen main groups, so it is not a code; the answer beside it is faithful, and the
		// check must be silent about both. Without the main-group restriction this is a WARN on
		// "Ceftriaxone 1g IV Q12H".
		service.setLlmProvider(answering("Ciprofloxacin is in the same ATC class (" + TRUE_CODE
				+ ") as the patient's active order; give 500 mg PO Q12H ["
				+ finding.getIndex() + "]."));
		// On the PACKAGE, because on this path the check logs nothing at all — not even the DEBUG
		// line the two gates emit — so a capture scoped to the check alone would be empty and the
		// assertion would pass whether or not the check ran.
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a dosing frequency is not an ATC code. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void generalisingACitedSubstanceCodeToItsClassIsReported() {
		// The accepted false alarm, pinned so it cannot be "fixed" without reading why. The injected
		// drug-reference record states ciprofloxacin's level-5 codes (ATC J01MA02, …); an answer
		// citing it and naming the level-4 class is usually right, and the module has the reduction
		// to prove it (DrugReference.atcSubgroups). Accepting it was written and then removed:
		// because support is pooled across the cited records, a roll-up silences #142's own headline
		// capture on any chart citing a reference record for a drug in the wrongly named class —
		// records stating J01MA and J01CA04, and "same ATC class (J01CA)" becomes supported. A log
		// line a maintainer dismisses is the cheaper error.
		RecordMapping reference = referenceRecord();
		assertFalse(ClassCodeFidelityCheck.classCodesIn(reference.getText()).contains(TRUE_CODE),
				"the premise: the reference record states the SUBSTANCE codes, not the class. Was: "
						+ reference.getText());
		assertTrue(ClassCodeFidelityCheck.classCodesIn(reference.getText()).contains(TRUE_CODE + "02"),
				"the premise: it states a level-5 code under that class. Was: " + reference.getText());
		service.setLlmProvider(answering("Ciprofloxacin belongs to ATC class " + TRUE_CODE + " ["
				+ reference.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "[" + TRUE_CODE + "]"),
					"a class no cited record states as a token is reported, correct or not. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aCodeTheQUESTIONStatesIsNotAFabrication() {
		// A clinician who types a code and gets it back has been echoed, not misled — the same
		// reading this module already applies to a question-named drug. Without it the check accuses
		// the answer of inventing the reader's own words.
		String questionNamingACode = "is ciprofloxacin (" + MISCOPIED_CODE + ") safe here?";
		service.setLlmProvider(answering("You asked about " + MISCOPIED_CODE + "; the patient is on a "
				+ "drug in the same ATC class (" + TRUE_CODE + ") [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), questionNamingACode);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a code the question itself states is not one the model invented. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aCitedRecordWhoseTextIsUnavailableMakesTheCheckAbstain() {
		// A mapping carrying no text is the shape the grounding verifier treats as "cannot verify"
		// (its 4-arg constructor). We cannot know which codes such a record states, so we cannot
		// call anything fabricated: claiming one would be the check crying wolf on a record it
		// never read.
		//
		// The arrangement has to pair the unreadable record with a readable one that DOES state a
		// code — the real injected finding — or the case is blind: with only the unreadable record
		// cited, no cited record states a code and the "nothing to copy" gate would produce the same
		// silence for a different reason. (Measured by mutation: with a lone textless record,
		// deleting the abstention reddened nothing.)
		PatientChart withUnreadable = new PatientChart(chart.getText(),
				Arrays.asList(finding,
						new RecordMapping(99, ChartSearchAiConstants.RESOURCE_TYPE_OBS,
								"00000000-0000-0000-0000-000000000099", null)),
				Collections.<Integer> emptyList());
		TestableService onUnreadable = newService(withUnreadable);
		onUnreadable.setLlmProvider(answering("It is the same ATC class " + MISCOPIED_CODE + " ["
				+ finding.getIndex() + "], [99]."));
		// Captured on the CHECK at DEBUG, not on the package: the abstention has its own line, so
		// this case can assert that the check RAN and declined — "no WARN" alone would also pass if
		// the check were never reached at all.
		try (LogCapture capture = LogCapture.on(CHECK, Level.DEBUG)) {
			onUnreadable.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the check must abstain on a cited record it cannot read, not accuse it. Captured: "
							+ capture.describeAll());
			assertTrue(debugStating(capture, "carries no text"),
					"the abstention must be traceable, and WHICH abstention: this arrangement can fall "
							+ "silent for the other gate's reason too. Captured: " + capture.describeAll());
		}
	}

	/** The injected drug-reference record in the same chart — the one carrying ciprofloxacin's own
	 *  level-5 codes, as the real renderer writes them. */
	private RecordMapping referenceRecord() {
		for (RecordMapping mapping : chart.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(mapping.getResourceType())) {
				return mapping;
			}
		}
		throw new IllegalStateException("no drug-reference record was injected: " + chart.getText());
	}

	@Test
	public void aCheckThatThrowsIsReportedAndTheAnswerStillReturns() {
		// The guard exists so a diagnostic can never break a clinical answer — the promise
		// CitationGroundingVerifier makes in its javadoc, made structurally here. Nothing in the
		// check does I/O, so the only way to reach it is a record that throws when read: this one is
		// a RecordMapping that does, served through the real chart path. Nothing else in search()
		// reads the text (extractCitedReferences works off indices; the validator is stubbed;
		// grounding is off), so the throw lands in the check and nowhere else.
		PatientChart throwing = new PatientChart(chart.getText(),
				Arrays.asList(new RecordMapping(1, ChartSearchAiConstants.RESOURCE_TYPE_OBS,
						"00000000-0000-0000-0000-000000000001", null) {

					@Override
					public String getText() {
						throw new IllegalStateException("record text unavailable");
					}
				}),
				Collections.<Integer> emptyList());
		TestableService onThrowing = newService(throwing);
		onThrowing.setLlmProvider(answering("It is the same ATC class " + MISCOPIED_CODE + " [1]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			ChartAnswer answer = onThrowing.search(patient(), QUESTION);
			assertTrue(answer.getAnswer().contains(MISCOPIED_CODE),
					"the answer must come back whatever the check does; got: " + answer.getAnswer());
			assertTrue(warnStating(capture, "Class-code check failed"),
					"and the check's own failure must not be silent. Captured: "
							+ capture.describeAll());
		}
	}


	// ---------------------------------------------------------------------------------------------
	// Issue #338: the two malformations of a class-code parenthetical that set membership cannot see.
	// Both are answer-LOCAL — they are decided by the shape of the prose, not by comparing it against
	// the records — which is why they need no comparison against the chips the answer was expected to
	// report, the comparison ADR Decision 35 rejects.
	// ---------------------------------------------------------------------------------------------

	@Test
	public void aClassCodeRepeatedInsideOneParentheticalIsReported() {
		// Issue #338's headline capture, on Sarah Taylor: four cited findings each state H02AB and
		// the answer fused them into one clause reading "(H02AB, H02AB, H02AB, H02AB)". Every token
		// is supported, so the membership report stays silent — and a MULTISET reading would call
		// all four occurrences supported too. What no record licenses is the SHAPE: a LIST has a
		// source (the injected reference record renders one) and a REPETITION has none — see
		// ClassCodeFidelityCheck.reportMalformedParentheticals for what was measured and its residue.
		service.setLlmProvider(answering(sentenceStating(TRUE_CODE + ", " + TRUE_CODE)));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "more than once inside one parenthetical",
					"[" + TRUE_CODE + "]", "patient=1"),
					"a code repeated inside one parenthetical has to be reported, and the WARN has to "
							+ "carry the code and the patient or a maintainer reading a log with "
							+ "concurrent requests in it cannot reconstruct it. Captured: "
							+ capture.describeAll());
			assertFalse(warnStating(capture, "no cited record"),
					"and the premise this case rests on: the membership report has nothing to say "
							+ "about it, so the repetition is the only thing being reported. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void searchStreaming_shouldReportARepeatedCodeOnThePrimaryProductionPathToo() {
		// /search/stream is the path users hit — the same reason
		// searchStreaming_shouldReportItOnThePrimaryProductionPathToo() exists for the membership
		// report. A rule wired into search() alone is absent from production traffic.
		service.setLlmProvider(answering(sentenceStating(TRUE_CODE + ", " + TRUE_CODE)));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.searchStreaming(patient(), QUESTION, token -> { });
			assertTrue(warnStating(capture, "more than once inside one parenthetical",
					"[" + TRUE_CODE + "]"),
					"the streaming path must run the same rule. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void twoDifferentCodesInOneParentheticalAreNotReported() {
		// The control that discriminates the repetition rule from a rule against parenthesised
		// lists as such. Both codes come from ONE record — the injected drug-reference record, which
		// renders ciprofloxacin's own level-5 codes as a round-parenthesised list — so the
		// membership report has nothing to say either, and the silence belongs to this rule.
		RecordMapping reference = referenceRecord();
		List<String> codes = new ArrayList<String>(
				ClassCodeFidelityCheck.classCodesIn(reference.getText()));
		assertTrue(codes.size() >= 2,
				"the premise: the real injected reference record states at least two distinct class "
						+ "codes, so a two-code parenthetical can be built from one cited record. Was: "
						+ reference.getText());
		service.setLlmProvider(answering("Ciprofloxacin is filed under (" + codes.get(0) + ", "
				+ codes.get(1) + ") [" + reference.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"two DIFFERENT codes in one parenthetical are a list the records do license — one "
							+ "record states both — and reporting it would make this rule noise. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aCitationMarkerInsideACodeParentheticalIsReported() {
		// Issue #338's third defect, verbatim from the capture: "(H02AB [12])". The marker belongs
		// after the clause it attributes; inside the parenthetical it reads as part of the code.
		service.setLlmProvider(answering("Ciprofloxacin is in the same ATC class (" + TRUE_CODE + " ["
				+ finding.getIndex() + "]) as the patient's active order."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "inside a parenthetical", "[" + TRUE_CODE + "]",
					"patient=1"),
					"a citation marker inside the code's own parenthetical has to be reported, "
							+ "carrying the code and the patient. Captured: " + capture.describeAll());
			assertFalse(warnStating(capture, "no cited record"),
					"and the premise: the code itself is faithful, so the membership report has "
							+ "nothing to say and the placement is the only thing being reported. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aCitationMarkerImmediatelyAfterTheParentheticalIsNotReported() {
		// The other half of the pair, and the shape the module's own sentence takes: the marker sits
		// outside the brackets, attributing the clause. The file's faithful-recitation case puts it
		// at the end of the sentence; this one puts it as close to the parenthetical as it can go
		// without being inside it, which is the boundary the rule is drawn at.
		service.setLlmProvider(answering("Ciprofloxacin is in the same ATC class (" + TRUE_CODE
				+ ") [" + finding.getIndex() + "] as the patient's active order."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a marker outside the parenthetical is the well-formed shape and must stay quiet. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void anAsideStatingACodeAndCarryingItsOwnMarkerIsReported() {
		// Pins a residue rather than a defect, at the shape review found it in: an aside that
		// legitimately states a class code AND carries the marker attributing its own clause. The
		// marker IS after the clause it attributes — it is only inside the aside's brackets — and the
		// rule reports it, because the rule is about where a marker sits relative to a code and the
		// discriminator here would be prose structure, which no shape test can read. Recorded so the
		// first maintainer to triage one of these lines reads the analysis instead of re-deriving it;
		// ADR Decision 59 carries it in "what it cannot see". If a corpus ever justifies narrowing the
		// rule, this case is what has to change, deliberately.
		service.setLlmProvider(answering("She is already on two fluoroquinolones (levofloxacin and "
				+ "moxifloxacin, both " + TRUE_CODE + " [" + finding.getIndex() + "]), so "
				+ "ciprofloxacin would be duplicate therapy."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "inside a parenthetical", "[" + TRUE_CODE + "]"),
					"today this is reported; the case exists so that stops being a surprise and starts "
							+ "being a decision. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aMarkerInANestedAsideBelongsToTheAsideAndNotToTheCode() {
		// REPLACES a case added earlier in this same change, which asserted the opposite. That case
		// read a parenthetical's WHOLE text, nested groups included, and clean-context review
		// measured what it costs: "(ATC class (J01MA) [3])" — a marker correctly placed after the
		// clause it attributes — was reported as misplaced, and "(levofloxacin (J01MA) and
		// moxifloxacin (J01MA))" as a repetition, both on correct prose. Each parenthetical is now
		// read at its own level, so a nested aside's marker is the aside's. The cost is this shape:
		// a marker the model really did bury inside the code's brackets, one level down, is not
		// reported. Silence is the direction this check must fail in.
		service.setLlmProvider(answering("Ciprofloxacin is in the same ATC class (" + TRUE_CODE
				+ " (see [" + finding.getIndex() + "])) as the patient's active order."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a marker one level deeper is the aside's, not the code's. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void removingANestedSpanDoesNotWeldTheTextEitherSideOfIt() {
		// The level walk leaves a SPACE where a nested group was, not nothing. Deleting it welds the
		// text either side into one run, and both rules read tokens off the result: measured, this
		// answer states J01MA exactly once and was reported as repeating it, the second "occurrence"
		// manufactured by joining "J01M" to the "A" on the far side of the aside.
		service.setLlmProvider(answering("Ciprofloxacin is in the same ATC class (" + TRUE_CODE
				.substring(0, TRUE_CODE.length() - 1) + "(sic)" + TRUE_CODE.substring(TRUE_CODE.length() - 1)
				+ ", " + TRUE_CODE + ") as the order [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertFalse(warnStating(capture, "more than once inside one parenthetical"),
					"the code is stated once; a repetition here is manufactured by the walk. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aMarkerManufacturedByRemovingANestedSpanIsNotReported() {
		// The same weld, on the marker rule and in its worst direction: "[0(sic)3]" carries no
		// citation marker at all, and joining across the aside makes it "[03]" — an index that
		// resolves to a real record, so the validated-citation intersection cannot see it either.
		service.setLlmProvider(answering("Ciprofloxacin is in the same ATC class (" + TRUE_CODE
				+ " [0(sic)" + finding.getIndex() + "]) as the order [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertFalse(warnStating(capture, "inside a parenthetical"),
					"there is no marker inside those brackets; one welded out of two fragments is not "
							+ "a citation the model placed. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aRepetitionSeparatedOnlyByAnEmptyNestedGroupIsStillReported() {
		// The other direction of the same weld, which the separator also fixes: without it the empty
		// group's removal joins the two codes into one alphanumeric run and the token boundaries
		// reject both, so a real repetition went silently unreported.
		service.setLlmProvider(answering("Ciprofloxacin is in the same ATC class (" + TRUE_CODE + "()"
				+ TRUE_CODE + ") as the order [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "more than once inside one parenthetical",
					"[" + TRUE_CODE + "]"),
					"the codes are two, separated by an empty aside. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void twoClausesWrappedInOneAsideAreStillTwoClauses() {
		// The false alarm that decided the level semantics. Read whole, the enclosing aside states
		// the class twice — once from each child — and a correct answer about two partners of one
		// class is accused of the defect this check exists to report. At its own level the aside
		// states no code at all.
		service.setLlmProvider(answering("Two of her orders (levofloxacin (" + TRUE_CODE
				+ ") and moxifloxacin (" + TRUE_CODE + ")) are fluoroquinolones ["
				+ finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"two clauses inside one aside are two clauses, not a repetition. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aRepetitionNestedInsideAnotherParentheticalIsStillReported() {
		// The other side of the same rule, and what an outermost-or-maximal-only walk loses: the
		// offending brackets are the INNER ones, and the group enclosing them states no code of its
		// own. Reading only the outermost group reports nothing here.
		service.setLlmProvider(answering("Her chart notes (she is in the same ATC class (" + TRUE_CODE
				+ ", " + TRUE_CODE + ") as the order) throughout [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "more than once inside one parenthetical",
					"[" + TRUE_CODE + "]"),
					"the inner brackets are the offending ones and must be read on their own. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aBracketedNumberTheChartHasNoRecordForIsNotAMisplacedMarker() {
		// The marker rule reads the answer's own VALIDATED citations rather than every bracketed
		// integer in the prose. Named for what it actually pins, because a wider claim was written
		// here first: extractCitedReferences promotes every IN-RANGE bracket to a citation, so what
		// this excludes is a number the chart has no record for — [97] on a chart of a few records —
		// and NOT bracketed clinical values as such. An eGFR of 45 beside a 45-record chart is still
		// reported; that residue is recorded on ADR Decision 59.
		service.setLlmProvider(answering("Ciprofloxacin is in the same ATC class (" + TRUE_CODE
				+ ", dose [97] mg) as the patient's active order — possible duplicate therapy ["
				+ finding.getIndex() + "]."));
		// On the PACKAGE so the assertion is not vacuous — the check reports nothing here and a
		// class-scoped capture would be empty — but asserted on the check's OWN message rather than
		// on WARN level, because the pipeline legitimately warns about the out-of-range index
		// itself ("LLM cited record [97] which does not exist"), which is that guard doing its job.
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(warnStating(capture, "inside a parenthetical"),
					"[97] is a dose, not a citation of record 97. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void theSameCodeStatedOnceInEachOfTwoParentheticalsIsNotRepetition() {
		// The rule is "more than once inside ONE parenthetical", and this is the shape that makes the
		// qualifier load-bearing: two clauses about two partners each state the class once, which is
		// #142's own (A01AD) (A01AD) capture and is what correct prose about two partners looks like.
		// Hoisting the per-group set out of the walk turns this into a WARN and nothing else notices.
		service.setLlmProvider(answering("Ciprofloxacin is in the same ATC class (" + TRUE_CODE
				+ ") as one active order and in the same ATC class (" + TRUE_CODE + ") as another ["
				+ finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a code stated once in each of two parentheticals is two clauses, not a repetition, "
							+ "and this check does not separate that from correct prose. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void aMarkerInsideAParentheticalThatStatesNoCodeIsNotReported() {
		// The other qualifier: "inside a parenthetical THAT STATES A CLASS CODE". Without it this
		// stops being a rule about codes and becomes a rule about brackets — every aside a model
		// writes with a citation in it would be reported, on an answer that is otherwise faithful.
		service.setLlmProvider(answering("Ciprofloxacin is in the same ATC class (" + TRUE_CODE
				+ ") as the patient's active order (see [" + finding.getIndex() + "])."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or the assertion below "
							+ "passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"an aside carrying a marker and no code is outside this rule's subject matter. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void anUnmatchedClosingBracketDoesNotSwallowTheGroupAfterIt() {
		// An unmatched ')' is ignored rather than treated as closing something. Treated as a close it
		// would consume the walk's state and the real parenthetical after it would go unexamined —
		// a silent miss, which is the direction this check must not fail in when it has evidence.
		service.setLlmProvider(answering("Levofloxacin) is on the chart. Ciprofloxacin is in the same "
				+ "ATC class (" + TRUE_CODE + ", " + TRUE_CODE + ") as it [" + finding.getIndex()
				+ "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "more than once inside one parenthetical",
					"[" + TRUE_CODE + "]"),
					"the stray ')' must not consume the walk. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void anUnclosedBracketEarlierDoesNotSilenceTheParentheticalAfterIt() {
		// The failure an outermost-only walk had: with an unclosed '(' still open, no group is ever
		// at depth zero again, so every malformed parenthetical in the rest of the answer was
		// invisible — silently, fail-open, on exactly the malformed model prose this rule is about.
		// Reading every BALANCED group is what closes it; the unclosed bracket itself still yields
		// nothing, so a paragraph is never pooled into one "parenthetical".
		service.setLlmProvider(answering("The dose (was raised. Ciprofloxacin is in the same ATC "
				+ "class (" + TRUE_CODE + ", " + TRUE_CODE + ") as the order [" + finding.getIndex()
				+ "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "more than once inside one parenthetical",
					"[" + TRUE_CODE + "]"),
					"a stray '(' earlier in the answer must not blind the rule to what follows. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void twoOffendingParentheticalsWithDifferentCodesAreTwoEntries() {
		// Why the marker report is one entry per PAIRING rather than two pooled lists: with two
		// offending groups, pooled lists say which codes and which markers occurred and no longer say
		// which sat with which. Nothing else in this file has two offending groups in one answer.
		// The line reports distinct placements and not a count of brackets — two groups stating the
		// same codes with the same markers are one entry, which is what the WARN's wording says and
		// what aTwiceRepeatedIdenticalPlacementIsOneEntry pins.
		RecordMapping reference = referenceRecord();
		List<String> codes = new ArrayList<String>(
				ClassCodeFidelityCheck.classCodesIn(reference.getText()));
		assertTrue(codes.size() >= 2,
				"the premise: the injected reference record states two distinct class codes, so the "
						+ "two groups can carry different ones. Was: " + reference.getText());
		service.setLlmProvider(answering("Ciprofloxacin is filed under (" + codes.get(0) + " ["
				+ reference.getIndex() + "]) and under (" + codes.get(1) + " [" + finding.getIndex()
				+ "])."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "one entry per distinct code-and-marker pairing",
					"[" + codes.get(0) + "] with [" + reference.getIndex() + "]",
					"[" + codes.get(1) + "] with [" + finding.getIndex() + "]"),
					"each offending parenthetical has to be its own entry, pairing the codes it "
							+ "states with the markers inside it. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aRepeatedCodeIsSilentWhereNoCitedRecordStatesAClassCode() {
		// The shape rules inherit the membership report's gates rather than re-arguing them, and
		// this is where that costs something: the repetition is wrong on its own terms, but with no
		// code-bearing record cited the whole check declines and says nothing about it. ADR Decision
		// 59 records that as a place a real defect can hide; this pins it so a later change that
		// moves the rules out from behind the gates has to say so rather than drift there.
		PatientChart codeless = chartRecordStating("Antibiotic course: ceftriaxone 1 g IV completed");
		TestableService onCodeless = newService(codeless);
		onCodeless.setLlmProvider(answering("She had ceftriaxone, ATC class (" + TRUE_CODE + ", "
				+ TRUE_CODE + ") [1]."));
		try (LogCapture capture = LogCapture.on(CHECK, Level.DEBUG)) {
			onCodeless.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the shape rules run behind the membership gates, so this declines. Captured: "
							+ capture.describeAll());
			assertTrue(debugStating(capture, "no cited record states a class code"),
					"and which gate declined has to be identifiable, or the silence could be the "
							+ "rule never having run. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aTwiceRepeatedIdenticalPlacementIsOneEntry() {
		// The other half of the wording: the entries are distinct PLACEMENTS, not offending brackets.
		// Two parentheticals stating the same code with the same marker inside collapse to one entry,
		// so a maintainer counting clauses off this line would undercount — which is why the line
		// does not offer itself as a count.
		service.setLlmProvider(answering("First (" + TRUE_CODE + " [" + finding.getIndex()
				+ "]) and second (" + TRUE_CODE + " [" + finding.getIndex() + "])."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "one entry per distinct code-and-marker pairing",
					"[[" + TRUE_CODE + "] with [" + finding.getIndex() + "]]"),
					"two identical placements are one entry, and the line says pairing rather than "
							+ "parenthetical for exactly that reason. Captured: " + capture.describeAll());
		}
	}

	/** An answer sentence of the shape the model really produces, citing the finding record. */
	private String sentenceStating(String code) {
		return "Ciprofloxacin is in the same ATC class (" + code + ") as the patient's active order"
				+ " — possible duplicate therapy [" + finding.getIndex() + "].";
	}

	/** @return whether one DEBUG line carries {@code needle} — the two gates and the abstention all
	 *  produce silence, and a case that claims one of them has to say which. {@link LogCapture}'s
	 *  own, not a copy: the sibling {@code ReferenceProseFidelityTest} asserts over the same log
	 *  lines and the two must be able to redden together. */
	private static boolean debugStating(LogCapture capture, String needle) {
		return capture.hasMessageAt(Level.DEBUG, needle);
	}

	/** @return whether one WARN carries every one of {@code required} */
	private static boolean warnStating(LogCapture capture, String... required) {
		return capture.hasMessageAt(Level.WARN, required);
	}

	/** A one-record chart rendered by the REAL serializer over the real test-dataset helper, so the
	 *  record the check reads is a genuine chart line rather than a hand-assembled imitation. */
	private static PatientChart chartRecordStating(String text) {
		return new PatientChartSerializer().serialize(null,
				TestDatasetHelper.toSerializedRecords(
						new String[] { "Clinical observation: (2026-03-18) " + text }),
				Collections.<String> emptySet());
	}

	private static Patient patient() {
		Patient p = new Patient();
		p.setPatientId(1);
		p.setUuid("uuid-1");
		return p;
	}

	private static StubProvider answering(String answer) {
		return new StubProvider(answer);
	}

	/** Subclass that no-ops the Context-backed resolvers so no OpenMRS runtime is needed. */
	private static final class TestableService extends LlmInferenceService {

		@Override
		protected boolean resolveWarmupEnabled() {
			return false;
		}

		@Override
		protected boolean resolveGroundingEnabled() {
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

		@Override
		boolean usePreFilter() {
			return false;
		}
	}

	/**
	 * The canned answer. The engine is the one thing that cannot be held still on a live box
	 * (cache_prompt KV reuse makes prose irreproducible), so it is the one thing stubbed: the check
	 * under test is a pure function of the answer and the records, and this fixes the answer.
	 */
	private static final class StubProvider extends LlmProvider {

		private final String answer;

		private StubProvider(String answer) {
			this.answer = answer;
		}

		private LlmResponse canned() {
			return new LlmResponse(answer, Collections.<Integer> emptyList());
		}

		@Override
		public LlmResponse search(String numberedRecords, List<Integer> focusIndices, String question) {
			return canned();
		}

		@Override
		public LlmResponse searchStreaming(String numberedRecords, List<Integer> focusIndices,
				String question, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer,
				String cacheScope) {
			return canned();
		}
	}
}
