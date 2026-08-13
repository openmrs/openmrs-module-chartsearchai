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
 * <p>Nothing in the pipeline could see it. Tier-1 grounding is cosine similarity, and a
 * two-character edit inside an alphanumeric token barely moves an embedding; Tier-2 entailment is
 * paraphrase-tolerant, which is exactly the wrong tolerance for a code substitution; and the
 * citation itself is valid, so index validation passes. The chip is right, the record is right,
 * the citation is right, and the sentence is wrong.
 *
 * <p>What this file pins is the deterministic check that closes it: every ATC-shaped token in the
 * answer must appear in a record the answer CITES, and one that does not is reported at WARN
 * carrying both the code the answer states and the codes its cited records state. The answer prose
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
		finding = DrugReferenceTestSupport.injectedSafetyFinding(QUESTION, ACTIVE_DRUG, ACTIVE_ATC);
		assertTrue(finding.getText().contains("(" + TRUE_CODE + ")"),
				"the premise: the real injected finding states the class code the answer must copy. Was: "
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
			assertTrue(warnStating(capture, MISCOPIED_CODE, TRUE_CODE),
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
			assertTrue(warnStating(capture, MISCOPIED_CODE, TRUE_CODE),
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
			assertTrue(warnStating(capture, MISCOPIED_CODE, TRUE_CODE),
					"the chart record was read: a code it does not state must still be reported. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aCitedRecordWhoseTextIsUnavailableMakesTheCheckAbstain() {
		// A mapping carrying no text is the shape the grounding verifier treats as "cannot verify"
		// (its 4-arg constructor). We cannot know which codes such a record states, so we cannot
		// call anything fabricated: claiming one would be the check crying wolf on a record it
		// never read.
		PatientChart textless = new PatientChart("[1] a record whose text was not carried",
				Arrays.asList(new RecordMapping(1, "obs", "00000000-0000-0000-0000-000000000001", null)),
				Collections.<Integer> emptyList());
		TestableService onTextless = newService(textless);
		onTextless.setLlmProvider(answering("It is the same ATC class " + MISCOPIED_CODE + " [1]."));
		// Captured on the CHECK at DEBUG, not on the package: the abstention has its own line, so
		// this case can assert that the check RAN and declined — "no WARN" alone would also pass if
		// the check were never reached at all.
		try (LogCapture capture = LogCapture.on(CHECK, Level.DEBUG)) {
			onTextless.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"the check must abstain on a cited record it cannot read, not accuse it. Captured: "
							+ capture.describeAll());
			assertFalse(capture.messagesAt(Level.DEBUG).isEmpty(),
					"the abstention must be traceable: the check ran, read a textless cited record and "
							+ "declined. Captured: " + capture.describeAll());
		}
	}

	/** An answer sentence of the shape the model really produces, citing the finding record. */
	private String sentenceStating(String code) {
		return "Ciprofloxacin is in the same ATC class (" + code + ") as the patient's active order"
				+ " — possible duplicate therapy [" + finding.getIndex() + "].";
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
