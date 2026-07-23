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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.openmrs.module.chartsearchai.api.ChartSearchService.RecordReference;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Arbitrates the yes/no verdict lead of an answer with a Tier-2 entailment check, instead of
 * trusting the small local model's free-form chart synthesis to decide it.
 *
 * <p><strong>The failure it fixes.</strong> Asked "any kidney problems?", Gemma&nbsp;E4B tends to
 * answer "Yes" whenever a topically-related record exists — a creatinine lab, a urine test, a
 * symptom like "difficulty in urination" — even though none of those is a recorded <em>diagnosis</em>
 * of kidney disease. Measured on the yes/no gold set this over-affirmation is the dominant verdict
 * error, and it is a <em>confident</em> error: resampling the same free-generation task does not fix
 * it (self-consistency N=7 agreed with the greedy miss). But the error is task-specific, not
 * model-wide: posed the narrow, verdict-only question "does THIS record explicitly state a diagnosis
 * of X?", the same model answers correctly. This arbiter routes the verdict through that narrow
 * task.
 *
 * <p><strong>How.</strong> When an answer leads "Yes", the arbiter first checks — via {@link
 * LlmProvider#isConditionPresenceQuestion} — that the question actually asks whether the patient HAS
 * a diagnosed condition/disease. Only then does it entail each cited record (in one batched call,
 * {@link LlmProvider#entailsBatch}) against a diagnosis-presence STATEMENT derived from the question
 * ("The patient has a recorded diagnosis, condition, or problem of &lt;topic&gt;."). If NO cited
 * record entails it — with positive evidence (at least one definitive NO and zero YES) — the "Yes"
 * is unsupported and the leading verdict clause is rewritten to a NO-family lead. Every inline
 * {@code [N]} citation marker is preserved, so the reference set — and therefore the recall / drift /
 * abstention the metrics measure — is unchanged by construction. This is why the correction cannot
 * regress recall the way answer-regenerating approaches (two-pass, self-critique) do.
 *
 * <p><strong>Why the intent gate.</strong> An intent-blind first cut applied the diagnosis-presence
 * standard to <em>every</em> yes/no question. It fixed the condition over-affirmations but wrongly
 * negated program-enrollment answers ("Yes — enrolled in the PMTCT program" &rarr; "No diagnosis
 * …"), because a "diagnosis of X" yardstick is simply wrong for a non-diagnosis question — a net
 * wash on the gate. The gate ({@link LlmProvider#CONDITION_INTENT_SYSTEM_PROMPT}) restricts the
 * downgrade to condition/disease-presence questions, so enrollment, allergy, medication,
 * test-performed, and value questions are left to the model. It is written generally (any body-part
 * / organ-system problem counts) and validated on held-out systems, not fitted to the eval topics.
 *
 * <p><strong>Why it supersedes the {@code resourceType} guard.</strong> The earlier
 * {@code applyVerdictGuard} fired only when every citation was an {@code obs}/{@code test_order} — a
 * blind type whitelist. It therefore missed a "Yes" cited by a non-matching <em>condition</em>
 * ("peripheral vascular disease" for a heart-problems question) and, being type-only, could not
 * distinguish a diagnosis-presence question from an existence/order one. The entailment keys off the
 * clinical meaning of each record against the actual question, catching the condition-typed
 * over-affirmations the guard could not while leaving a genuinely-named diagnosis ("essential
 * hypertension" cited for "is the patient hypertensive?") untouched.
 *
 * <p><strong>Scope (v1).</strong> Only the over-affirmation direction ("Yes" &rarr; "No"). The
 * mirror direction ("No" &rarr; "Yes" for an under-recognised diagnosis) must surface previously
 * uncited evidence and change the citation set, so it is deliberately out of scope here. Gated by
 * {@code chartsearchai.verdictEntailment.enabled} (default off) and certified by the yes/no verdict
 * gate in {@code eval/drift-metric/} before enabling.
 */
@Component("chartSearchAi.verdictEntailmentArbiter")
public class VerdictEntailmentArbiter {

	private static final Logger log = LoggerFactory.getLogger(VerdictEntailmentArbiter.class);

	/** Leading "Yes" verdict clause, ported verbatim from the resourceType guard: the trailing
	 *  class (em dash, colon, comma, etc.) is zero-or-more so a bare "Yes" matches, and it stops
	 *  before "[" so it can never consume an inline citation marker. */
	static final Pattern YES_CLAUSE =
			Pattern.compile("^\\s*yes\\b[\\s\\u2014:,.;-]*", Pattern.CASE_INSENSITIVE);

	/** A standalone re-affirming opener the model sometimes places after "Yes" ("there are records
	 *  of kidney issues."). Removed so the rewritten NO lead is not immediately contradicted. The
	 *  {@code [^.:\[]} class refuses to span a "[", so a clause carrying a citation never matches and
	 *  no inline marker is dropped — the rewrite stays citation-neutral. */
	static final Pattern REAFFIRM =
			Pattern.compile("^there (is|are)\\b[^.:\\[]*[.:]\\s*", Pattern.CASE_INSENSITIVE);

	/** Yes/no interrogative framings stripped to recover the clinical topic. Longest/most-specific
	 *  first so "any history of" wins over "any". Kept general (not a per-topic table) so the
	 *  statement derivation does not encode the eval's specific question set. */
	private static final String[] QUESTION_PREFIXES = {
			"does the patient have any ", "does the patient have ", "is the patient ",
			"has the patient ", "do they have ", "are there any ", "is there any ",
			"any known history of ", "any history of ", "history of ", "any known ", "any ",
	};

	static final String NO_LEAD = "No diagnosis explicitly naming this is recorded.";

	@Autowired
	private LlmProvider llmProvider;

	/** Test seam: production wires {@link LlmProvider} via {@link Autowired}. */
	void setLlmProvider(LlmProvider llmProvider) {
		this.llmProvider = llmProvider;
	}

	/** The batched (source, statement) &rarr; verdict primitive, isolated as a seam so the decision
	 *  logic is unit-testable without a live llama-server. Production binds {@code entailsBatch}. */
	interface BatchEntailer {
		List<Boolean> entails(List<String> sources, List<String> statements);
	}

	/** Question-intent gate: does this yes/no question ask about the presence of a diagnosed
	 *  condition/disease? Isolated as a seam for the same reason as {@link BatchEntailer}. Production
	 *  binds {@code isConditionPresenceQuestion}. */
	interface IntentClassifier {
		Boolean isConditionPresenceQuestion(String question);
	}

	/**
	 * Production entry point: arbitrates the verdict lead using the real batched entailment call.
	 *
	 * @param answer the model's answer text
	 * @param question the clinician's yes/no question
	 * @param cited the records the answer cites
	 * @param mappings the record mappings carrying each cited index's source text
	 * @return the answer with a corrected verdict lead, or the original answer when the arbiter does
	 *         not apply (not a "Yes" lead, no citations, unresolved text, or a cited record entails
	 *         the diagnosis)
	 */
	public String arbitrate(String answer, String question, List<RecordReference> cited,
			List<RecordMapping> mappings) {
		return arbitrate(answer, question, cited, mappings,
				llmProvider::isConditionPresenceQuestion, llmProvider::entailsBatch);
	}

	/**
	 * Seam overload: decision logic against injected {@link IntentClassifier} and {@link
	 * BatchEntailer}. Package-private so unit tests exercise the full transform (trigger, intent
	 * gate, statement derivation, verdict aggregation, citation-neutral rewrite) without an OpenMRS
	 * context or a llama-server.
	 */
	String arbitrate(String answer, String question, List<RecordReference> cited,
			List<RecordMapping> mappings, IntentClassifier classifier, BatchEntailer entailer) {
		// V1 acts only on an over-affirming "Yes" lead. A NO/NONE lead is left untouched.
		if (answer == null || cited == null || cited.isEmpty() || !YES_CLAUSE.matcher(answer).find()) {
			return answer;
		}

		// Intent gate: the finding-vs-diagnosis downgrade is only sound for a "does the patient HAVE a
		// condition/disease" question. For an enrollment ("any programs"), allergy, medication,
		// test-performed, or value question, a "Yes" is affirmed by a non-diagnosis record and must
		// not be negated by a diagnosis-presence standard — that was the wash that sank the
		// intent-blind v1 (it fixed condition over-affirmations but broke program-enrollment cells).
		// A null/unknown classification degrades to "do not act".
		if (!Boolean.TRUE.equals(classifier.isConditionPresenceQuestion(question))) {
			return answer;
		}

		Map<Integer, String> textByIndex = new HashMap<Integer, String>();
		if (mappings != null) {
			for (RecordMapping mapping : mappings) {
				textByIndex.put(mapping.getIndex(), mapping.getText());
			}
		}
		List<String> sources = new ArrayList<String>();
		for (RecordReference ref : cited) {
			String text = textByIndex.get(ref.getIndex());
			if (text != null && !text.trim().isEmpty()) {
				sources.add(text);
			}
		}
		// No resolvable cited text — cannot check; leave the model's verdict alone.
		if (sources.isEmpty()) {
			return answer;
		}

		String statement = buildStatement(question);
		List<Boolean> verdicts = entailer.entails(sources,
				Collections.nCopies(sources.size(), statement));
		if (verdicts == null) {
			return answer;
		}

		boolean anyDiagnosis = false;
		boolean anyDefiniteNo = false;
		for (Boolean v : verdicts) {
			if (Boolean.TRUE.equals(v)) {
				anyDiagnosis = true;
			} else if (Boolean.FALSE.equals(v)) {
				anyDefiniteNo = true;
			}
		}
		// Keep the "Yes" when any cited record entails the diagnosis. Downgrade ONLY on positive
		// evidence of absence: at least one definitive NO and zero YES. An all-null verdict
		// (unparseable/unavailable) is treated as "could not verify" and leaves the answer intact —
		// the arbiter never negates a verdict it could not check.
		if (anyDiagnosis || !anyDefiniteNo) {
			return answer;
		}

		String corrected = downgradeToNoLead(answer);
		if (log.isDebugEnabled()) {
			log.debug("Verdict entailment downgraded a 'Yes' lead: {} cited record(s), none entailed \"{}\"",
					sources.size(), statement);
		}
		return corrected;
	}

	/**
	 * Rewrites only the leading verdict clause of a "Yes" answer to a NO-family lead, preserving the
	 * evidence and every inline {@code [N]} citation marker. Identical rewrite to the retired
	 * resourceType guard, so its citation-neutrality carries over unchanged.
	 */
	static String downgradeToNoLead(String answer) {
		String rest = YES_CLAUSE.matcher(answer).replaceFirst("");
		rest = REAFFIRM.matcher(rest).replaceFirst("").trim();
		if (!rest.isEmpty()) {
			rest = Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
		}
		return rest.isEmpty() ? NO_LEAD : NO_LEAD + " " + rest;
	}

	/** Builds the diagnosis-presence statement entailed against each cited record. */
	static String buildStatement(String question) {
		return "The patient has a recorded diagnosis, condition, or problem of " + deriveTopic(question)
				+ ".";
	}

	/**
	 * Recovers the clinical topic from a yes/no question by stripping its interrogative framing.
	 * General (not a per-topic lookup): "any kidney problems?" &rarr; "kidney problems"; "is the
	 * patient hypertensive" &rarr; "hypertensive". A question with no recognised framing is used
	 * verbatim (minus trailing punctuation), which the entailment tolerates.
	 */
	static String deriveTopic(String question) {
		if (question == null) {
			return "";
		}
		String t = question.trim();
		while (t.endsWith("?") || t.endsWith(".") || t.endsWith("!")) {
			t = t.substring(0, t.length() - 1).trim();
		}
		String lower = t.toLowerCase();
		for (String prefix : QUESTION_PREFIXES) {
			if (lower.startsWith(prefix)) {
				return t.substring(prefix.length()).trim();
			}
		}
		return t;
	}
}
