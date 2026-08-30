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

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Patient;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.ChartSearchAiUtils;
import org.openmrs.module.chartsearchai.LogCapture;
import org.openmrs.module.chartsearchai.api.impl.LlmProvider.LlmResponse;
import org.openmrs.module.chartsearchai.reference.DrugReferenceInjector;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.reference.DrugSafetyValidator;
import org.openmrs.module.chartsearchai.reference.SafetyWarning;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #337: the model reproduces a deterministic safety finding's mechanism and then writes its
 * own words inside the sentence it was copying, deleting the hazard. Measured live on the 3.7.1
 * standalone: the chip said <em>"…oxidizing agents that can also <b>induce methemoglobinemia</b>
 * such as antimalarials…"</em> and the answer, citing that finding, said <em>"…oxidizing agents
 * that can also <b>increase the risk</b>"</em> — the risk of what is no longer in the sentence a
 * clinician reads. A second capture deleted <em>"aminoglycoside antibiotics"</em> from a botulinum
 * toxin warning whose partner IS an aminoglycoside.
 *
 * <p>Nothing in the pipeline could see it, and the README claimed something could. A
 * reference-group citation skips Tier-2 entailment entirely and Tier-1 cosine barely moves when one
 * phrase inside a long recitation changes; {@link ClassCodeFidelityCheck} compares one token shape
 * and says nothing about prose; the {@code safetyWarnings} chips are a parallel list nothing
 * reconciles against the answer.
 *
 * <p>What this file pins is the deterministic check that closes the mechanism gap: when an answer
 * REPRODUCES a stretch of a cited reference record and then, inside the same sentence on both
 * sides, states different words, that is reported at WARN. Everything else is silence — an answer
 * that reproduces nothing, one that stops copying and ends its sentence, one that reproduces a
 * record sentence whole and moves on, and one whose continuation ANOTHER cited record explains.
 * The answer prose is never rewritten and nothing reaches the wire.
 *
 * <p>Everything here runs through the real {@link LlmInferenceService#search}/
 * {@code searchStreaming} orchestration over REAL production-rendered records: the cited finding
 * and the drug-reference record beside it are what the real validate → injectRecords →
 * renderFinding chain produces off the bundled DDInter excerpt for a patient on tramadol asked
 * about sertraline. Every canned answer is sliced out of that record's own text at run time rather
 * than transcribed, so the arrangement cannot drift from the dataset. Only the model is stubbed —
 * answer prose is not reproducible on a live engine, and the check under test is a pure function of
 * the answer and the records.
 */
public class ReferenceProseFidelityTest {

	/** A Major interaction whose mechanism is five sentences long — the shape the defect needs, and
	 *  the only safety finding this arrangement raises (asserted in {@link #setUp}). */
	private static final String QUESTION = "is it safe to give sertraline?";

	private static final String ACTIVE_DRUG = "tramadol";

	private static final String ACTIVE_ATC = "N02AX02";

	/** The check's own logger: the narrowest capture that can satisfy a "it was reported"
	 *  assertion, so no other class's WARN can stand in for the check's. */
	private static final String CHECK = ReferenceProseFidelityCheck.class.getName();

	/** The package, for the assertions whose claim is SILENCE. A class-scoped capture of a silent
	 *  class receives nothing, which is exactly the state that makes "no WARN was logged" pass
	 *  vacuously (LogCapture's javadoc); the package capture also receives
	 *  {@code LlmInferenceService}'s own [timing] INFO line, so the capture can be shown live. */
	private static final String PACKAGE = "org.openmrs.module.chartsearchai.api.impl";

	private TestableService service;

	private PatientChart chart;

	private RecordMapping finding;

	private RecordMapping reference;

	/** The finding record's own text, split on whitespace — the slicing unit every canned answer
	 *  below is built from. Whitespace splitting is ARRANGEMENT, not a second copy of the check's
	 *  own tokeniser: it keeps the punctuation, which is what carries the sentence boundaries the
	 *  check reads, and a canned answer assembled from a lower-cased word list would carry none. */
	private String[] recordTokens;

	@BeforeEach
	public void setUp() {
		chart = DrugReferenceTestSupport.injectedSafetyFindingChart(QUESTION, ACTIVE_DRUG, ACTIVE_ATC);
		finding = DrugReferenceTestSupport.safetyFindingIn(chart);
		reference = referenceRecord();
		recordTokens = finding.getText().split("\\s+");
		// The premises, asserted rather than assumed — every case below slices this text.
		assertTrue(finding.getText().contains(DrugReferenceInjector.STRENGTH_WITHHOLD),
				"the premise: the real injected finding ends with the appended strength clause, which "
						+ "is what the record-sentence exit has to keep out of the comparison. Was: "
						+ finding.getText());
		assertTrue(reference.getText().contains(mechanismTail()),
				"the premise: the drug-reference record beside it carries the SAME mechanism string, "
						+ "which is what makes the pooling case below reachable. Was: "
						+ reference.getText());
		assertTrue(recordTokens.length > 120,
				"the premise: the mechanism is long enough for two runs of eight words with a "
						+ "substitution between them. Was " + recordTokens.length + " tokens");
		service = newService(chart);
	}

	@Test
	public void search_shouldReportAnAnswerThatSubstitutesItsOwnWordsInsideACopiedSentence() {
		// #337's own shape: the answer reproduces the finding verbatim and then, without ending the
		// sentence, writes something else where the record names the hazard.
		service.setLlmProvider(answering(copiedThrough("may") + " increase the risk of complications ["
				+ finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(capture.hasEventAtOrAbove(Level.WARN),
					"an answer that reproduces a deterministic record and then states different words "
							+ "inside the sentence it was copying has degraded citable safety prose and "
							+ "must be reported. Captured: " + capture.describeAll());
			assertTrue(warnStating(capture, "[" + finding.getIndex() + "]", "patient=1"),
					"the WARN has to carry the record whose prose was degraded AND the patient, or a "
							+ "maintainer reading a log with concurrent requests in it cannot "
							+ "reconstruct it. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void search_shouldStaySilentWhenTheAnswerReproducesTheRecordFaithfully() {
		// The other half of the pair above, on the same arrangement: that one fails if the check
		// never runs, this one fails if it accuses a faithful answer. Neither alone discriminates.
		service.setLlmProvider(answering(copiedThrough("may") + " potentiate the risk of serotonin "
				+ "syndrome [" + finding.getIndex() + "]."));
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
	public void aContinuationAnotherCitedRecordExplainsIsNotADivergence() {
		// The answer reproduces the WHOLE mechanism and welds a clause on where the record ends its
		// sentence. Read against the finding that is faithful — the record's next word opens the
		// appended strength clause, a new sentence. Read against the drug-reference record beside
		// it, the same mechanism is one "; "-joined item of an interaction list, so the record's
		// next word is the NEXT PARTNER's name and no sentence boundary stands between them: on
		// that record alone this looks exactly like a substitution.
		//
		// Both records are cited, as the live capture on #337 cited both. Support is therefore
		// pooled across the cited records, as ADR Decision 35 pools it for a class code: a
		// continuation one cited record explains innocently is not a divergence, whatever a second
		// record's own layout makes of it. Without the pooling this answer — which copied nothing
		// wrongly — is reported.
		service.setLlmProvider(answering(mechanismWithoutItsFinalStop()
				+ ", so avoid coadministration [" + reference.getIndex() + "], ["
				+ finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a continuation the finding record itself explains must not be reported because a "
							+ "second cited record lays the same mechanism out differently. Captured: "
							+ capture.describeAll());
		}
	}

	@Test
	public void anAnswerThatEndsTheSentenceWhereItStoppedCopyingIsNotReported() {
		// Truncation, not substitution. The model stopped reproducing and closed its sentence; it
		// stated nothing the record does not. That under-reports #337's weaker cousin — a hazard
		// dropped by stopping early — and is the safe direction for a check whose failure mode is
		// being ignored. Reporting it would fire on every answer that quotes a clause of a
		// 150-word mechanism, which is most of them.
		service.setLlmProvider(answering(copiedThrough("may") + " [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"stopping is not substituting. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aRecordSentenceReproducedWholeAndFollowedByTheAnswersOwnIsNotReported() {
		// The record-sentence exit, on its own: the copy ends where a sentence of the RECORD ends,
		// and the answer carries on inside its own sentence. The model reproduced a unit of the
		// record faithfully; what it writes next is its own comment, not a rewriting of what it
		// was copying.
		//
		// This is also the exit that keeps the appended strength clause out of the check at the
		// seam: renderFinding runs the detail through DrugSafetyValidator.endSentence whenever a
		// clause is appended, so " This finding is a reason to withhold it." always opens a new
		// record sentence. It covers the SEAM and not the clause's interior — see the check's own
		// javadoc for the residue.
		service.setLlmProvider(answering(copiedThrough("receptors.")
				+ ", though this is rare [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(PACKAGE)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.describeAll().isEmpty(),
					"the capture must receive the pipeline's own INFO lines, or this passes vacuously");
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"a record sentence reproduced whole is a faithful copy however the answer goes on. "
							+ "Captured: " + capture.describeAll());
		}
	}

	@Test
	public void anAnswerThatReproducesNothingOfTheRecordIsNotChecked() {
		// The "nothing to copy" gate — ClassCodeFidelityCheck's, one operand along. An answer that
		// summarises in its own words has reproduced nothing, so there is no reproduction to be
		// unfaithful to, and every phrase it shares with the record by chance would otherwise be a
		// candidate. Asserted at DEBUG on the check's own logger, so the case pins that the check
		// RAN and declined rather than that it was never reached.
		service.setLlmProvider(answering("Sertraline and the patient's tramadol together carry a "
				+ "serotonin risk [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK, Level.DEBUG)) {
			service.search(patient(), QUESTION);
			assertFalse(capture.hasEventAtOrAbove(Level.WARN),
					"with nothing reproduced there is no reproduction to be unfaithful to. Captured: "
							+ capture.describeAll());
			assertTrue(debugStating(capture, "reproduces no cited reference record"),
					"the gate that declined has to be identifiable. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void aDivergenceInAnEarlierRunIsReportedThoughALaterRunIsLonger() {
		// Every reproduced run is its own candidate, not just the longest. The answer substitutes
		// inside a 15-word run and then reproduces a 60-word record sentence faithfully; an
		// implementation that examined only the longest run would look at the faithful one, find it
		// innocent, and report nothing — the degraded sentence would be exactly as invisible as it
		// is today.
		service.setLlmProvider(answering(mechanismFrom("Due") + " increase the risk of a rare "
				+ "condition. " + secondMechanismSentence() + " [" + finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.search(patient(), QUESTION);
			assertTrue(warnStating(capture, "[" + finding.getIndex() + "]"),
					"a substitution in an earlier run must be reported even where a later run is "
							+ "longer and faithful. Captured: " + capture.describeAll());
		}
	}

	@Test
	public void searchStreaming_shouldRunTheSameCheckOnThePrimaryProductionPath() {
		// /search/stream is the path users hit: a check wired only into search() would be absent
		// from production traffic while every non-streaming test stayed green.
		service.setLlmProvider(answering(copiedThrough("may") + " increase the risk of complications ["
				+ finding.getIndex() + "]."));
		try (LogCapture capture = LogCapture.on(CHECK)) {
			service.searchStreaming(patient(), QUESTION, token -> { });
			assertTrue(warnStating(capture, "[" + finding.getIndex() + "]"),
					"the streaming path must run the same check. Captured: " + capture.describeAll());
		}
	}

	/** The finding's own text from its first word up to and including the first token equal to
	 *  {@code lastToken} — a verbatim reproduction of a leading stretch of the record, punctuation
	 *  and all. */
	private String copiedThrough(String lastToken) {
		StringBuilder sb = new StringBuilder();
		for (String token : recordTokens) {
			sb.append(sb.length() == 0 ? "" : " ").append(token);
			if (token.equals(lastToken)) {
				return sb.toString();
			}
		}
		throw new IllegalStateException("no token \"" + lastToken + "\" in " + finding.getText());
	}

	/** The finding's text from the first token equal to {@code firstToken} up to the token before
	 *  the first {@code "potentiate"} — the earlier, shorter run the substitution case needs. */
	private String mechanismFrom(String firstToken) {
		String whole = copiedThrough("may");
		int at = whole.indexOf(firstToken);
		assertTrue(at > 0, "no token \"" + firstToken + "\" inside " + whole);
		return whole.substring(at);
	}

	/** The mechanism's second sentence, reproduced whole — 60-odd words, longer than any other run
	 *  in the answer that carries it. */
	private String secondMechanismSentence() {
		// The production constant, not a second spelling of it: this slice exists partly because two
		// copies of one boundary rule is issue #260's shape, and a test that spells its own is a
		// third copy that agrees with neither by construction.
		String[] sentences = ChartSearchAiUtils.SENTENCE_BOUNDARY.split(mechanism());
		assertTrue(sentences.length > 2, "the premise: the mechanism has a second sentence. Was: "
				+ mechanism());
		return sentences[1];
	}

	/** The mechanism prose the finding carries: its detail with the module's own appended clause
	 *  removed. Test-side slicing, deliberately — the check itself has no such notion and must not
	 *  grow one (the record-sentence exit is what keeps the clause out of the comparison). */
	private String mechanism() {
		String text = finding.getText();
		return text.substring(0, text.indexOf(DrugReferenceInjector.STRENGTH_WITHHOLD));
	}

	/** The mechanism with its closing full stop replaced by nothing, so an answer can weld a clause
	 *  on and keep one sentence — the shape the pooling case needs. */
	private String mechanismWithoutItsFinalStop() {
		String text = mechanism();
		return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
	}

	/** The tail of the mechanism, used only as a premise assertion that the drug-reference record
	 *  carries the same string. */
	private String mechanismTail() {
		String text = mechanismWithoutItsFinalStop();
		return text.substring(Math.max(0, text.length() - 60));
	}

	/** The injected drug-reference record in the same chart, as the real renderer writes it. */
	private RecordMapping referenceRecord() {
		for (RecordMapping mapping : chart.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_DRUG_REFERENCE.equals(mapping.getResourceType())) {
				return mapping;
			}
		}
		throw new IllegalStateException("no drug-reference record was injected: " + chart.getText());
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

			@Override
			public List<SafetyWarning> validate(String answer, String question, Patient patient,
					List<RecordMapping> mappings) {
				return Collections.emptyList();
			}
		});
		return created;
	}

	/** @return whether one DEBUG line carries {@code needle} */
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

	/** The canned answer — the one thing that cannot be held still on a live engine. */
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
