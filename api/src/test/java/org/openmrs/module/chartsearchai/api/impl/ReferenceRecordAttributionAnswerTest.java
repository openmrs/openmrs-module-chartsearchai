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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.reference.DrugReferenceTestSupport;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issue #246: an answer about a drug must not take the drug's own contraindication list, carried by
 * the injected {@code drug_reference} record, as this patient's chart.
 *
 * <p>The record is right and says so per clause — the reading sections name each clause on the side
 * it is on, and {@code " Contraindicated with: "} is the drug's own rule list. The issue's 2026-08-25
 * measurement is an answer that read that list as her chart anyway: <em>"No — Amoxicillin should not
 * be given: documented amoxicillin allergy"</em>, with no finding behind it and a record stating that
 * very clause under {@code "Not recorded for this patient"}. What these cases hold is the prompt's
 * record-type rule for that record — {@link LlmProvider#DEFAULT_SYSTEM_PROMPT} — and not the record.
 *
 * <p><strong>These cases do NOT reproduce that refusal, and are not its regression test.</strong> On the
 * shipped prompt the first case abstained 3 of 3 while Betty's standalone chart refused 6 of 6 on the
 * same question; neither her three allergies nor the production request body moved it. What they pin
 * is the pair of branches any re-wording of the rule must keep: no verdict where the record states no
 * clause as recorded, and "No" where it states one. ADR Decision 121 measured three re-wordings on the
 * standalone, where the refusal does reproduce, shipped none, and found the result inconclusive. The
 * first case does tell some prompts apart: with the compiled sentence mutated to each of that
 * decision's arms, the one deleting "not this patient's data" failed it with the issue's own measured
 * refusal, and the shipped sentence and the two narrowings passed.
 *
 * <p><strong>The oracle is the VERDICT the prompt itself defines, not a phrasing.</strong> The prompt
 * answers a safety question with no finding naming the drug in one sentence stating that the records
 * do not address it, "never \"Yes\" or \"No\"", and one naming a finding that withholds the drug with
 * "No". So the property "the answer attributes to the patient only what the record marks as recorded"
 * is read here as the lead token those two branches choose between, which is a statement about which
 * branch the model took rather than about its words: a "No" on the first chart is the issue's refusal,
 * a "Yes" there a clearance the module cannot give, and anything but a "No" on the second is the
 * converse failure a prompt that stopped reading the recorded half would produce. On the first chart
 * the answer is also held to name no clause of the drug's list, since that branch is one sentence
 * saying the records do not address the drug; that catches a refusal that does not open on the verdict
 * word (<em>"Amoxicillin should not be given: documented amoxicillin allergy"</em>). What it leaves
 * unread there is an attribution that paraphrases a clause rather than naming it (<em>"she is allergic
 * to penicillin"</em>); ADR Decision 121 quotes the live answers whole for that reason.
 *
 * <p><strong>Real, and not.</strong> Real: the test patient's whole chart
 * ({@link TestDatasetHelper#FULL_PATIENT_DATASET}, whose recorded allergies are Beef and
 * Fomepizole, no penicillin of any kind) through the real {@link PatientChartSerializer}, the bundled
 * curated drug-reference dataset behind the real injector and validator, the real prompt, the real
 * user message and response parsing, and the model. Not the production REQUEST: this goes through
 * {@link LlmEndpointTestSupport#complete}, which sends {@code json_object} rather than the answer
 * schema and no sampler chain — the gap every suite behind that class shares. The live A/B ADR
 * Decision 121 records ran on the production path.
 *
 * <p>The two answer cases are <b>opt-in</b>, skipped without an endpoint, and repeat with a DECOY
 * completion over ANOTHER patient's chart between samples, so each one re-prefills rather than reusing
 * the KV prefix (ADR Decision 45's methodology finding).
 */
public class ReferenceRecordAttributionAnswerTest {

	private static final Logger log = LoggerFactory.getLogger(ReferenceRecordAttributionAnswerTest.class);

	private static final String ENABLE_PROPERTY = "chartsearchai.reference.attribution.test";

	private static final String ENDPOINT_PROPERTY = "chartsearchai.reference.attribution.endpoint";

	private static final int MAX_TOKENS = ChartSearchAiConstants.DEFAULT_LLM_MAX_OUTPUT_TOKENS;

	private static final int SAMPLES = 3;

	/** The safety probe's own phrasing ({@code capture_probe_safety.sh}'s default), and not the issue
	 *  comment's "Is amoxicillin safe for this patient?": on Betty's standalone chart the comment's
	 *  phrasing abstained 3 of 3 while this one refused — ADR Decision 121. */
	private static final String QUESTION = "Can this patient take amoxicillin?";

	private static final String DECOY_QUESTION = "what is the patient's most recent weight?";

	/** The test patient's recorded allergens, as {@code PatientClinicalContextBuilder} would read them
	 *  off the two allergy records {@link TestDatasetHelper#FULL_PATIENT_DATASET} carries. Typed by hand,
	 *  because that builder reads OpenMRS {@code Allergy} objects and this chart is text, so each case
	 *  first holds the set to the chart it injects over ({@link #assertAllergyRecordsNameExactly}): a
	 *  dataset that gains, loses or renames an allergy fails there, in the ordinary build, rather than
	 *  injecting over a context its own chart contradicts. */
	private static final Set<String> RECORDED_ALLERGENS = new LinkedHashSet<String>(
			Arrays.asList("Beef", "Fomepizole"));

	/** The converse chart's one added allergen: one the curated amoxicillin entry self-names. */
	private static final String AMOXICILLIN_ALLERGEN = "Amoxicillin";

	/** The converse chart's one added record, naming {@link #AMOXICILLIN_ALLERGEN}. */
	private static final String AMOXICILLIN_ALLERGY_RECORD = "Patient allergy: (2026-03-19) Allergy: "
			+ AMOXICILLIN_ALLERGEN + " (drug allergen). Severity: Severe";

	/** The two clauses the bundled curated amoxicillin entry publishes. */
	private static final List<String> AMOXICILLIN_CLAUSES = Arrays.asList(
			"penicillin-class hypersensitivity", "documented amoxicillin allergy");

	/** The answer's lead verdict token, where it has one: the first word, past any opening quote or
	 *  markdown emphasis. */
	private static final Pattern LEAD_WORD = Pattern.compile("^[\\s\"'“‘(*_]*([A-Za-z]+)");

	@Test
	public void aDrugWhoseRulesNameNothingHerChartRecordsIsNotAnsweredWithAVerdict() throws Exception {
		PatientChart base = fullChart(false);
		assertAllergyRecordsNameExactly(base, RECORDED_ALLERGENS);
		PatientChart chart = DrugReferenceTestSupport.curatedReferenceOver(base, QUESTION, RECORDED_ALLERGENS);
		String record = amoxicillinRecord(chart);
		for (String clause : AMOXICILLIN_CLAUSES) {
			assertTrue(section(record, " Not recorded for this patient: ").contains(clause),
					"precondition: the record must state '" + clause + "' as NOT recorded for this "
							+ "patient, or this case is not the issue's arrangement. Record was: " + record);
		}
		assertTrue(record.contains(" Contraindicated with: "),
				"precondition: the record must carry the drug's own rule list. Record was: " + record);
		assertEquals(0, DrugReferenceTestSupport.injectedFindings(chart).size(),
				"precondition: nothing on this chart relates to amoxicillin, so no finding is injected");

		String endpoint = optedInEndpoint();
		List<String> answers = answers(endpoint, chart, "no-recorded-clause");
		for (int sample = 1; sample <= answers.size(); sample++) {
			String answer = answers.get(sample - 1);
			String lead = verdictLead(answer);
			assertFalse("NO".equals(lead),
					"sample " + sample + ": the answer refuses amoxicillin on a chart that records no "
							+ "clause of its rules — the record lists both under \"Not recorded for this "
							+ "patient\" and no finding names the drug. That is issue #246's refusal: the "
							+ "drug's own contraindication list read as her chart. Was: " + answer);
			assertFalse("YES".equals(lead),
					"sample " + sample + ": the answer clears amoxicillin. With no finding naming the drug "
							+ "the prompt's own branch is one sentence saying the records do not address it, "
							+ "never \"Yes\" or \"No\" — a clearance reads the NOT-recorded half as a "
							+ "certificate, which the module cannot give. Was: " + answer);
			assertEquals(null, clauseNamed(answer),
					"sample " + sample + ": the answer names a clause of amoxicillin's contraindication list "
							+ "on a chart that records none of them. The prompt's branch for a drug no finding "
							+ "names is one sentence saying the records do not address it, so a clause in the "
							+ "answer is the drug's rule list read into it — a refusal that does not open on "
							+ "\"No\" included. Was: " + answer);
		}
	}

	@Test
	public void aDrugWhoseRuleHerChartRecordsIsStillRefused() throws Exception {
		Set<String> allergens = new LinkedHashSet<String>(RECORDED_ALLERGENS);
		allergens.add(AMOXICILLIN_ALLERGEN);
		PatientChart base = fullChart(true);
		assertAllergyRecordsNameExactly(base, allergens);
		PatientChart chart = DrugReferenceTestSupport.curatedReferenceOver(base, QUESTION, allergens);
		String record = amoxicillinRecord(chart);
		assertTrue(section(record, " Recorded for this patient: ").contains("documented amoxicillin allergy"),
				"precondition: the record must state the self-named clause as recorded for this patient. "
						+ "Record was: " + record);
		assertFalse(DrugReferenceTestSupport.injectedFindings(chart).isEmpty(),
				"precondition: the recorded amoxicillin allergy must raise a finding");

		String endpoint = optedInEndpoint();
		List<String> answers = answers(endpoint, chart, "recorded-clause");
		for (int sample = 1; sample <= answers.size(); sample++) {
			String answer = answers.get(sample - 1);
			assertEquals("NO", verdictLead(answer),
					"sample " + sample + ": the chart records an amoxicillin allergy, the record says so "
							+ "under \"Recorded for this patient\", and a finding withholds the drug — the "
							+ "answer must open with \"No\". A rule that stopped the model reading the "
							+ "drug's list as her chart must not stop it reading what her chart records. "
							+ "Was: " + answer);
		}
	}

	/**
	 * The two oracles reject what they are supposed to reject, and run without an endpoint, so the two
	 * answer cases being skipped in CI does not leave them unchecked.
	 */
	@Test
	public void theOraclesReadTheLeadTokenAndANamedClause() {
		assertEquals("NO", verdictLead("No — Amoxicillin should not be given: documented amoxicillin allergy [238]."));
		assertEquals("NO", verdictLead("\"No, do not give it."));
		assertEquals("NO", verdictLead("**No** — Amoxicillin should not be given."));
		assertEquals("YES", verdictLead("Yes, amoxicillin can be given."));
		assertEquals(null, verdictLead("The records do not address Amoxicillin safety for this patient."));
		assertEquals(null, verdictLead("Nothing in the records addresses amoxicillin."));
		assertEquals(null, verdictLead("None of the records address amoxicillin."));
		assertEquals(null, verdictLead(""));

		assertEquals("documented amoxicillin allergy",
				clauseNamed("Amoxicillin should not be given: documented amoxicillin allergy [238]."));
		assertEquals("penicillin-class hypersensitivity",
				clauseNamed("No — it is contraindicated with Penicillin-class hypersensitivity [238]."));
		assertEquals(null, clauseNamed("The records do not address Amoxicillin safety for this patient."));
		assertEquals(null, clauseNamed(null));
	}

	/** The first of {@link #AMOXICILLIN_CLAUSES} the answer names, case-insensitively, else {@code null}. */
	static String clauseNamed(String answer) {
		String lower = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
		for (String clause : AMOXICILLIN_CLAUSES) {
			if (lower.contains(clause)) {
				return clause;
			}
		}
		return null;
	}

	/** {@code "YES"} or {@code "NO"} where the answer opens with that verdict word, else {@code null}. */
	static String verdictLead(String answer) {
		Matcher m = LEAD_WORD.matcher(answer == null ? "" : answer);
		if (!m.find()) {
			return null;
		}
		String word = m.group(1).toUpperCase(Locale.ROOT);
		return "YES".equals(word) || "NO".equals(word) ? word : null;
	}

	private static String optedInEndpoint() {
		LlmEndpointTestSupport.assumeOptedIn(ENABLE_PROPERTY);
		String endpoint = LlmEndpointTestSupport.endpoint(ENDPOINT_PROPERTY);
		Assumptions.assumeTrue(LlmEndpointTestSupport.isReachable(endpoint),
				"Skipping: LLM endpoint not reachable at " + endpoint);
		return endpoint;
	}

	/** The test patient's whole chart through the real serializer, optionally with the converse
	 *  chart's amoxicillin allergy as its most recent record. */
	private static PatientChart fullChart(boolean withAmoxicillinAllergy) {
		List<String> dataset = new ArrayList<String>();
		if (withAmoxicillinAllergy) {
			dataset.add(AMOXICILLIN_ALLERGY_RECORD);
		}
		dataset.addAll(Arrays.asList(TestDatasetHelper.FULL_PATIENT_DATASET));
		return new PatientChartSerializer().serialize(null,
				TestDatasetHelper.toSerializedRecords(dataset.toArray(new String[0])),
				Collections.<String> emptySet());
	}

	/**
	 * Holds the hand-typed allergen set a case injects over to the chart it injects over: one allergy
	 * record per allergen, each naming it as {@code "Allergy: <allergen> ("}, and no other allergy record.
	 */
	private static void assertAllergyRecordsNameExactly(PatientChart chart, Set<String> allergens) {
		List<String> allergyTexts = new ArrayList<String>();
		for (PatientChartSerializer.RecordMapping mapping : chart.getMappings()) {
			if (ChartSearchAiConstants.RESOURCE_TYPE_ALLERGY.equals(mapping.getResourceType())) {
				allergyTexts.add(mapping.getText());
			}
		}
		assertEquals(allergens.size(), allergyTexts.size(),
				"precondition: the chart must carry one allergy record per allergen the context is given "
						+ allergens + ". Allergy records were: " + allergyTexts);
		for (String allergen : allergens) {
			int naming = 0;
			for (String text : allergyTexts) {
				if (text != null && text.contains("Allergy: " + allergen + " (")) {
					naming++;
				}
			}
			assertEquals(1, naming, "precondition: exactly one allergy record must name '" + allergen
					+ "', or the context and the chart disagree. Allergy records were: " + allergyTexts);
		}
	}

	private static String amoxicillinRecord(PatientChart chart) {
		String record = DrugReferenceTestSupport.injectedReference(chart).getText();
		assertTrue(record.contains("Amoxicillin"),
				"precondition: the question must inject the curated amoxicillin record, was: " + record);
		return record;
	}

	/** The record's section after {@code lead}, or a failure naming the record where it has none. */
	private static String section(String record, String lead) {
		String section = DrugReferenceTestSupport.sectionAfter(record, lead);
		assertNotNull(section, "precondition: the record must carry a section led by '" + lead.trim()
				+ "'. Record was: " + record);
		return section;
	}

	private static List<String> answers(String endpoint, PatientChart chart, String label) throws Exception {
		// ANOTHER patient's chart, so the sample after it shares no chart prefix with it and re-prefills:
		// the records come first in the user message, so a decoy on the same chart would leave the
		// whole chart's KV reusable.
		PatientChart decoy = new PatientChartSerializer().serialize(null,
				TestDatasetHelper.toSerializedRecords(TestDatasetHelper.SECOND_PATIENT_DATASET),
				Collections.<String> emptySet());
		List<String> answers = new ArrayList<String>();
		for (int sample = 1; sample <= SAMPLES; sample++) {
			LlmEndpointTestSupport.complete(endpoint, LlmProvider.DEFAULT_SYSTEM_PROMPT,
					LlmProvider.buildUserMessage(decoy.getText(), DECOY_QUESTION), MAX_TOKENS);
			String raw = LlmEndpointTestSupport.complete(endpoint, LlmProvider.DEFAULT_SYSTEM_PROMPT,
					LlmProvider.buildUserMessage(chart.getText(), QUESTION), MAX_TOKENS);
			String answer = LlmProvider.extractResponse(raw).getAnswer();
			log.info("[{} sample {}] {}", label, sample, answer);
			assertNotNull(answer, label + " sample " + sample + ": no answer was produced");
			answers.add(answer);
		}
		return answers;
	}
}
