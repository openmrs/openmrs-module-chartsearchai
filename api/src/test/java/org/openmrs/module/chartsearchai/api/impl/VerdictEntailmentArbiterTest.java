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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Unit tests for {@link VerdictEntailmentArbiter}: the trigger, statement derivation, verdict
 * aggregation, and citation-neutral rewrite are exercised against a stubbed {@link
 * VerdictEntailmentArbiter.BatchEntailer} so no llama-server is needed. The end-to-end behaviour on
 * real patient data is covered by the yes/no verdict gate in {@code eval/drift-metric/}.
 */
public class VerdictEntailmentArbiterTest {

	private static final String KIDNEY_Q = "any kidney problems";

	/** A condition-presence question (kidney/heart/…): the intent gate opens, the arbiter runs. */
	private static final VerdictEntailmentArbiter.IntentClassifier CONDITION = q -> Boolean.TRUE;

	/** A non-condition question (programs/allergies/value/…): the intent gate keeps it shut. */
	private static final VerdictEntailmentArbiter.IntentClassifier NOT_CONDITION = q -> Boolean.FALSE;

	/** Classifier could not decide — the arbiter must degrade to leaving the verdict untouched. */
	private static final VerdictEntailmentArbiter.IntentClassifier UNKNOWN_INTENT = q -> null;

	private final VerdictEntailmentArbiter arbiter = new VerdictEntailmentArbiter();

	/** Records what the arbiter asked, and replies with a preset verdict list. */
	private static final class CapturingEntailer implements VerdictEntailmentArbiter.BatchEntailer {

		List<String> lastSources;

		List<String> lastStatements;

		int calls;

		private final List<Boolean> reply;

		CapturingEntailer(List<Boolean> reply) {
			this.reply = reply;
		}

		@Override
		public List<Boolean> entails(List<String> sources, List<String> statements) {
			this.calls++;
			this.lastSources = sources;
			this.lastStatements = statements;
			return reply;
		}
	}

	private static RecordReference ref(int index) {
		return new RecordReference(index, "obs", "uuid-" + index, null);
	}

	private static RecordMapping mapping(int index, String text) {
		return new RecordMapping(index, "obs", "uuid-" + index, null, text);
	}

	// ---- trigger ----

	@Test
	public void nonYesLead_isLeftUntouched_andSkipsEntailment() {
		String answer = "No fractures are recorded.";
		CapturingEntailer entailer = new CapturingEntailer(Collections.<Boolean> emptyList());
		String out = arbiter.arbitrate(answer, "any fractures", Arrays.asList(ref(1)),
				Arrays.asList(mapping(1, "Traumatic amputation")), CONDITION, entailer);
		assertSame(answer, out, "a NO-lead answer must be returned unchanged");
		assertEquals(0, entailer.calls, "no entailment call for a non-Yes lead");
	}

	@Test
	public void emptyCitations_areLeftUntouched() {
		String answer = "Yes — kidney problems are recorded.";
		CapturingEntailer entailer = new CapturingEntailer(Collections.<Boolean> emptyList());
		String out = arbiter.arbitrate(answer, KIDNEY_Q, Collections.<RecordReference> emptyList(),
				Collections.<RecordMapping> emptyList(), CONDITION, entailer);
		assertSame(answer, out);
		assertEquals(0, entailer.calls);
	}

	// ---- downgrade path ----

	@Test
	public void labOnlyYes_withNoEntailedDiagnosis_isDowngraded() {
		String answer = "Yes — kidney function labs are recorded: Serum creatinine 93.1 [3].";
		CapturingEntailer entailer = new CapturingEntailer(Arrays.asList(Boolean.FALSE));
		String out = arbiter.arbitrate(answer, KIDNEY_Q, Arrays.asList(ref(3)),
				Arrays.asList(mapping(3, "Serum creatinine (umol/L): 93.1 umol/L")), CONDITION, entailer);
		assertFalse(out.toLowerCase().startsWith("yes"), "the 'Yes' lead must be removed");
		assertTrue(out.startsWith(VerdictEntailmentArbiter.NO_LEAD), "a NO-family lead is prepended");
		assertTrue(out.contains("[3]"), "the inline citation marker must be preserved");
	}

	@Test
	public void downgrade_firesOnAnyDefiniteNo_withZeroYes() {
		// one definite NO, one unverifiable (null) → positive evidence of absence, still downgrades.
		String answer = "Yes — kidney issues: creatinine [3], urine test [4].";
		CapturingEntailer entailer = new CapturingEntailer(Arrays.asList(Boolean.FALSE, null));
		String out = arbiter.arbitrate(answer, KIDNEY_Q, Arrays.asList(ref(3), ref(4)),
				Arrays.asList(mapping(3, "Serum creatinine 93.1"), mapping(4, "Urine glucose test")),
				CONDITION, entailer);
		assertTrue(out.startsWith(VerdictEntailmentArbiter.NO_LEAD));
		assertTrue(out.contains("[3]") && out.contains("[4]"), "both markers preserved");
	}

	// ---- keep path ----

	@Test
	public void yes_backedByAnEntailedDiagnosis_isKept() {
		String answer = "Yes — chronic kidney disease is recorded [2].";
		CapturingEntailer entailer = new CapturingEntailer(Arrays.asList(Boolean.TRUE));
		String out = arbiter.arbitrate(answer, KIDNEY_Q, Arrays.asList(ref(2)),
				Arrays.asList(mapping(2, "Chronic kidney disease")), CONDITION, entailer);
		assertSame(answer, out, "a Yes whose cited record entails the diagnosis is untouched");
	}

	@Test
	public void yes_withMixedVerdicts_isKept_whenAnyRecordEntails() {
		String answer = "Yes — CKD [2] and creatinine [3].";
		CapturingEntailer entailer = new CapturingEntailer(Arrays.asList(Boolean.TRUE, Boolean.FALSE));
		String out = arbiter.arbitrate(answer, KIDNEY_Q, Arrays.asList(ref(2), ref(3)),
				Arrays.asList(mapping(2, "Chronic kidney disease"), mapping(3, "Serum creatinine 93.1")),
				CONDITION, entailer);
		assertSame(answer, out, "any single entailed diagnosis keeps the Yes");
	}

	@Test
	public void allNullVerdicts_neverNegate() {
		String answer = "Yes — kidney problems [3].";
		CapturingEntailer entailer = new CapturingEntailer(Arrays.asList((Boolean) null, (Boolean) null));
		String out = arbiter.arbitrate(answer, KIDNEY_Q, Arrays.asList(ref(3), ref(4)),
				Arrays.asList(mapping(3, "Serum creatinine 93.1"), mapping(4, "Urine test")), CONDITION, entailer);
		assertSame(answer, out, "an unverifiable (all-null) result must never negate the verdict");
	}

	@Test
	public void nullVerdictList_neverNegate() {
		String answer = "Yes — kidney problems [3].";
		VerdictEntailmentArbiter.BatchEntailer entailer = (s, st) -> null;
		String out = arbiter.arbitrate(answer, KIDNEY_Q, Arrays.asList(ref(3)),
				Arrays.asList(mapping(3, "Serum creatinine 93.1")), CONDITION, entailer);
		assertSame(answer, out);
	}

	@Test
	public void citedRecordWithNoResolvableText_isLeftUntouched() {
		String answer = "Yes — kidney problems [9].";
		CapturingEntailer entailer = new CapturingEntailer(Arrays.asList(Boolean.FALSE));
		// cited index 9 has no mapping (and thus no text) — nothing to entail.
		String out = arbiter.arbitrate(answer, KIDNEY_Q, Arrays.asList(ref(9)),
				Arrays.asList(mapping(3, "unrelated")), CONDITION, entailer);
		assertSame(answer, out);
		assertEquals(0, entailer.calls, "no entailment call when no cited text resolves");
	}

	// ---- what the entailer is asked ----

	@Test
	public void entailer_receivesCitedRecordText_andOneStatementPerSource() {
		String answer = "Yes — kidney issues: creatinine [3], BUN [4].";
		CapturingEntailer entailer = new CapturingEntailer(Arrays.asList(Boolean.FALSE, Boolean.FALSE));
		arbiter.arbitrate(answer, KIDNEY_Q, Arrays.asList(ref(3), ref(4)),
				Arrays.asList(mapping(3, "Serum creatinine 93.1"), mapping(4, "Blood urea nitrogen 82.9")),
				CONDITION, entailer);
		assertEquals(Arrays.asList("Serum creatinine 93.1", "Blood urea nitrogen 82.9"),
				entailer.lastSources, "sources are the cited records' text, by index");
		assertEquals(2, entailer.lastStatements.size(), "one statement per source");
		assertEquals(entailer.lastStatements.get(0), entailer.lastStatements.get(1),
				"the same diagnosis-presence statement is used for every pair");
		assertTrue(entailer.lastStatements.get(0).contains("kidney problems"),
				"the statement carries the question topic: " + entailer.lastStatements.get(0));
	}

	// ---- statement derivation ----

	@Test
	public void deriveTopic_stripsInterrogativeFraming() {
		assertEquals("kidney problems", VerdictEntailmentArbiter.deriveTopic("any kidney problems?"));
		assertEquals("hypertensive", VerdictEntailmentArbiter.deriveTopic("is the patient hypertensive"));
		assertEquals("allergies",
				VerdictEntailmentArbiter.deriveTopic("Does the patient have any allergies?"));
		assertEquals("psychiatric illness",
				VerdictEntailmentArbiter.deriveTopic("any history of psychiatric illness"));
	}

	@Test
	public void deriveTopic_withNoKnownFraming_usesQuestionVerbatimMinusPunctuation() {
		assertEquals("fractures on the left femur",
				VerdictEntailmentArbiter.deriveTopic("fractures on the left femur?"));
	}

	@Test
	public void buildStatement_isADiagnosisPresenceClaim() {
		String s = VerdictEntailmentArbiter.buildStatement("any kidney problems?");
		assertTrue(s.startsWith("The patient has a recorded diagnosis, condition, or problem of "), s);
		assertTrue(s.endsWith("kidney problems."), s);
	}

	@Test
	public void downgradeToNoLead_isCitationNeutral() {
		String answer = "Yes — there are records of kidney issues: creatinine [3], urine [7].";
		String out = VerdictEntailmentArbiter.downgradeToNoLead(answer);
		assertTrue(out.startsWith(VerdictEntailmentArbiter.NO_LEAD), out);
		assertTrue(out.contains("[3]") && out.contains("[7]"), "all inline markers preserved: " + out);
		assertFalse(out.toLowerCase().contains("there are records of kidney issues"),
				"the contradicting re-affirming clause is removed");
	}

	@Test
	public void nullAnswer_isReturnedUnchanged() {
		assertNull(arbiter.arbitrate(null, KIDNEY_Q, Arrays.asList(ref(1)),
				new ArrayList<RecordMapping>(), CONDITION, new CapturingEntailer(null)));
	}

	// ---- intent gate ----

	@Test
	public void nonConditionQuestion_isSkipped_beforeAnyEntailment() {
		// "any programs?" is a non-diagnosis question: a program-enrollment "Yes" must NOT be
		// negated by a diagnosis-presence standard. The gate keeps it shut; the entailer never runs.
		String answer = "Yes — the patient is enrolled in the PMTCT program [4].";
		CapturingEntailer entailer = new CapturingEntailer(Arrays.asList(Boolean.FALSE));
		String out = arbiter.arbitrate(answer, "any programs", Arrays.asList(ref(4)),
				Arrays.asList(mapping(4, "Enrolled in PMTCT Program")), NOT_CONDITION, entailer);
		assertSame(answer, out, "a non-condition question's Yes must be left untouched");
		assertEquals(0, entailer.calls, "the intent gate must short-circuit before entailment");
	}

	@Test
	public void unknownIntent_isSkipped() {
		String answer = "Yes — kidney problems are recorded: creatinine [3].";
		CapturingEntailer entailer = new CapturingEntailer(Arrays.asList(Boolean.FALSE));
		String out = arbiter.arbitrate(answer, KIDNEY_Q, Arrays.asList(ref(3)),
				Arrays.asList(mapping(3, "Serum creatinine 93.1")), UNKNOWN_INTENT, entailer);
		assertSame(answer, out, "an undecided intent must degrade to leaving the verdict untouched");
		assertEquals(0, entailer.calls);
	}

	@Test
	public void conditionQuestion_opensTheGate_andDowngrades() {
		// Same lab-only Yes as the downgrade test, but routed through the intent gate: a condition
		// question opens it, so the unsupported "Yes" is still corrected.
		String answer = "Yes — kidney function labs are recorded: creatinine 93.1 [3].";
		CapturingEntailer entailer = new CapturingEntailer(Arrays.asList(Boolean.FALSE));
		String out = arbiter.arbitrate(answer, KIDNEY_Q, Arrays.asList(ref(3)),
				Arrays.asList(mapping(3, "Serum creatinine 93.1")), CONDITION, entailer);
		assertTrue(out.startsWith(VerdictEntailmentArbiter.NO_LEAD));
		assertEquals(1, entailer.calls, "a condition question runs the entailment");
	}
}
