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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
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
 * When they state none, there was nothing to copy and the check says nothing. There is deliberately
 * no roll-up from a cited substance code to its class: correct as such an answer usually is,
 * accepting it silences this issue's own headline capture, and `generalisingACitedSubstanceCodeToIts
 * ClassIsReported` pins that with the reason. The answer prose
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

	/** #142's live shape, reproducible from the bundled sample: ciprofloxacin against a patient
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
	 *  {@code LlmInferenceService}'s own [timing] INFO line, so the capture can be shown live. */
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

			// the mappings-carrying overload production actually calls (issue #105)
			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings) {
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
			assertTrue(warnStating(capture, "[" + MISCOPIED_CODE + "]", TRUE_CODE),
					"the WARN has to carry BOTH the code the answer states and the codes its cited "
							+ "records state, or a maintainer reading logs cannot reconstruct the "
							+ "miscopy. Captured: " + capture.describeAll());
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

	/** An answer sentence of the shape the model really produces, citing the finding record. */
	private String sentenceStating(String code) {
		return "Ciprofloxacin is in the same ATC class (" + code + ") as the patient's active order"
				+ " — possible duplicate therapy [" + finding.getIndex() + "].";
	}

	/** @return whether one DEBUG line carries {@code needle} — the two gates and the abstention all
	 *  produce silence, and a case that claims one of them has to say which. */
	private static boolean debugStating(LogCapture capture, String needle) {
		for (String message : capture.messagesAt(Level.DEBUG)) {
			if (message.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	/** @return whether one WARN carries every one of {@code required} */
	private static boolean warnStating(LogCapture capture, String... required) {
		for (String message : capture.messagesAt(Level.WARN)) {
			boolean all = true;
			for (String needle : required) {
				all = all && message.contains(needle);
			}
			if (all) {
				return true;
			}
		}
		return false;
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
