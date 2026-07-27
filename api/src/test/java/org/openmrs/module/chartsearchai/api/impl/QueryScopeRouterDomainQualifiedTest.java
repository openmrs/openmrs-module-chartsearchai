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

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * "conditions" is the one enumeration cue that is also a generic noun clinicians attach to a
 * domain — "heart conditions", "skin conditions", "any mental health or psychiatric conditions".
 * "medications" and "allergies" name their own domain; "conditions" does not.
 *
 * <p>Routing a domain-qualified question to the CONDITIONS scope hands the small model the
 * patient's ENTIRE problem list and asks it to filter — and on a patient with a long list it
 * enumerates instead. Measured on the 3.7.1 demo set (30 patients × 9 topics): the mental-health
 * cell answered <em>"Yes, the patient has several mental health or psychiatric conditions
 * recorded: Lumbago with sciatica, Cardiogenic shock, Bacterial gastroenteritis, Pulmonary
 * atelectasis, Chronic gingivitis …"</em> — a clinically wrong answer, and 54 of the 92 off-topic
 * citations in the whole eval came from that single topic, while genuinely TOPICAL topics (eye,
 * fractures) drifted 1 citation each.
 *
 * <p>So a conditions cue only earns the complete problem list when nothing narrows it. Generic
 * qualifiers that do not name a clinical domain ("active", "chronic", "medical", "anything")
 * still enumerate the list — those questions ARE problem-list questions.
 */
public class QueryScopeRouterDomainQualifiedTest {

	private static Set<QueryScopeRouter.Intent> intents(String question) {
		return QueryScopeRouter.matchedIntents(question);
	}

	@Test
	public void domainQualifiedConditionsQuestionsFallThroughToTopical() {
		assertFalse(intents("Does the patient have any mental health or psychiatric conditions?")
				.contains(QueryScopeRouter.Intent.CONDITIONS),
				"a psychiatric-domain question must not pull the whole problem list");
		assertFalse(intents("Any heart conditions?").contains(QueryScopeRouter.Intent.CONDITIONS));
		assertFalse(intents("Was she diagnosed with depression?")
				.contains(QueryScopeRouter.Intent.CONDITIONS));
	}

	@Test
	public void unqualifiedProblemListQuestionsKeepTheCompleteScope() {
		// These ARE problem-list enumerations — the completeness guarantee is exactly what they need.
		//
		// The list is long on purpose. An earlier version of this rule tested every content word
		// against a single hand-written allow-list, and passed this fixture while silently failing
		// on any phrasing whose wording happened not to be on it — "any recent conditions?",
		// "list her last diagnoses", "summarise her diagnoses" and "give me the full problem list"
		// all lost the problem list. The six phrasings originally asserted here all happened to use
		// allow-listed words, so the suite stayed green. Every phrasing below was verified broken
		// before the fix and working after; keep adding real clinician wordings rather than
		// trusting one list.
		for (String question : new String[] {
				"What conditions does the patient have?",
				"What is on her problem list?",
				"Any active conditions?",
				"List all chronic medical conditions",
				"Has the patient been diagnosed with anything?",
				"What are the past diagnoses?",
				"Any recent conditions?",
				"Any new diagnoses?",
				"Any conditions diagnosed lately?",
				"List her last diagnoses",
				"Any conditions in the past year?",
				"Summarise her diagnoses",
				"Give me the full problem list",
				"What conditions is she being treated for?" }) {
			assertTrue(intents(question).contains(QueryScopeRouter.Intent.CONDITIONS),
					"must keep the complete problem list: " + question);
		}
	}

	@Test
	public void theModulesOwnCertaintyVocabularyIsNotAClinicalDomain() {
		// The words this module prints in its own chart lines — "Status: ACTIVE", "Certainty:
		// CONFIRMED" — are the ones a clinician most naturally reuses when asking about the problem
		// list, and every one of them read as a narrowing domain, silently costing these questions
		// the completeness guarantee.
		for (String question : new String[] {
				"Any confirmed diagnoses?",
				"Any provisional diagnoses?",
				"Any presumed or suspected conditions?",
				"Any documented conditions?",
				"Any conditions on file?",
				"What conditions are in her medical history?" }) {
			assertTrue(intents(question).contains(QueryScopeRouter.Intent.CONDITIONS),
					"a certainty/record qualifier does not narrow to a domain: " + question);
		}
	}

	@Test
	public void aBareYearIsNotAClinicalDomain() {
		// TEMPORAL_CUES covers "in the past 2 years" but not a standalone year, so "2024" was read
		// as a domain name. A number cannot name a body system.
		assertTrue(intents("Any conditions diagnosed in 2024?")
				.contains(QueryScopeRouter.Intent.CONDITIONS));
		assertTrue(intents("What diagnoses does she have from 2023?")
				.contains(QueryScopeRouter.Intent.CONDITIONS));
	}

	@Test
	public void aNumberWithInteriorPunctuationIsStillNotAClinicalDomain() {
		// contentWords trims only the EDGES of a token, so a range or a year-month arrives whole
		// and a digits-only test on the raw token misses it. These are the ordinary ways a
		// clinician writes a date window.
		for (String question : new String[] {
				"Any conditions in the last 2-3 years?",
				"Any conditions diagnosed since 2024-01?",
				"Any conditions diagnosed in 01/2024?" }) {
			assertTrue(intents(question).contains(QueryScopeRouter.Intent.CONDITIONS),
					"a date window is not a clinical domain: " + question);
		}
	}

	@Test
	public void hyphenatedSpellingsOfGenericWordsStayGeneric() {
		// Clinicians write both spellings. Edge-punctuation trimming does not help here — the
		// hyphen is interior — so the de-hyphenated form has to be checked too.
		assertTrue(intents("Any co-morbid conditions?").contains(QueryScopeRouter.Intent.CONDITIONS));
		assertTrue(intents("Any comorbid conditions?").contains(QueryScopeRouter.Intent.CONDITIONS));
		assertTrue(intents("Any long-standing conditions?").contains(QueryScopeRouter.Intent.CONDITIONS));
		assertTrue(intents("Any earlier diagnoses?").contains(QueryScopeRouter.Intent.CONDITIONS),
				"\"earlier\" is in none of the temporal patterns — it has to be a member here");
	}

	@Test
	public void punctuationThatSEPARATESWordsIsNotAClinicalDomain() {
		// Interior punctuation is two opposite phenomena. As a joiner it glues one word together
		// ("co-morbid" -> "comorbid"); as a separator it packs several words into one token, where
		// the concatenation is recognisable by nothing — CONDITIONS_CUES needs the word boundaries
		// that concatenating destroys. Handling only the joiner left the literal example the
		// production comment names ("conditions/diagnoses") losing the problem list.
		for (String question : new String[] {
				"Any conditions/diagnoses?",
				"Any conditions/problems?",
				"Any acute/chronic conditions?",
				"Any conditions (active/inactive)?",
				"Any conditions\u2014active?" }) {
			assertTrue(intents(question).contains(QueryScopeRouter.Intent.CONDITIONS),
					"every part is problem-list vocabulary: " + question);
		}
	}

	@Test
	public void aSeparatorWhosePartsAreDomainsStillNarrows() {
		// The negative control for the rule above: splitting on punctuation must not turn a
		// two-domain question into a problem-list dump. "cardiac/renal" names two body systems.
		assertFalse(intents("Any cardiac/renal conditions?").contains(QueryScopeRouter.Intent.CONDITIONS),
				"a slash between two clinical domains still narrows");
		assertFalse(intents("Any heart/lung conditions?").contains(QueryScopeRouter.Intent.CONDITIONS));
	}

	@Test
	public void edgePunctuationDoesNotReadAsAClinicalDomain() {
		// The retrieval tokenizer leaves brackets and dashes attached, because the embedder does not
		// care. A word-level consumer does: "(active)" read as a distinct word looks exactly like a
		// domain name, and silently cost these questions their completeness guarantee.
		assertTrue(intents("conditions (active)?").contains(QueryScopeRouter.Intent.CONDITIONS));
		assertTrue(intents("Any conditions -- active?").contains(QueryScopeRouter.Intent.CONDITIONS));
		// Java's \p{Punct} is ASCII-only, so these — the spellings macOS, iOS and Word substitute
		// BY DEFAULT for the two above — were the ones still lost. Pinning the ASCII forms alone
		// pinned the phrasings least likely to actually arrive.
		assertTrue(intents("Any conditions \u2014 active?").contains(QueryScopeRouter.Intent.CONDITIONS),
				"an em dash must not read as a clinical domain");
		assertTrue(intents("Any conditions \u2013 active?").contains(QueryScopeRouter.Intent.CONDITIONS),
				"nor an en dash");
		assertTrue(intents("Any \u201cactive\u201d conditions?").contains(QueryScopeRouter.Intent.CONDITIONS),
				"nor curly double quotes");
		assertTrue(intents("Any \u2018active\u2019 conditions?").contains(QueryScopeRouter.Intent.CONDITIONS),
				"nor curly single quotes");
	}

	@Test
	public void theQualifierRuleAppliesOnlyToTheConditionsCue() {
		// "medications" and "allergies" name their own domain, so a qualifier in front of them
		// ("drug allergies", "current medications") does not make the enumeration narrower —
		// the typed scope is still exactly the right answer set.
		assertTrue(intents("Does the patient have any drug allergies?")
				.contains(QueryScopeRouter.Intent.ALLERGIES),
				"drug allergies must keep the allergy table complete");
		assertTrue(intents("Does the patient have any drug allergies?")
				.contains(QueryScopeRouter.Intent.MEDICATIONS));
		assertTrue(intents("What medications is the patient taking?")
				.contains(QueryScopeRouter.Intent.MEDICATIONS));
		assertTrue(intents("Is the patient enrolled in any HIV programs?")
				.contains(QueryScopeRouter.Intent.PROGRAMS),
				"a qualified programs question still needs the complete program list");
	}

	@Test
	public void aDomainQualifiedConditionsQuestionKeepsItsOtherIntents() {
		// Suppressing CONDITIONS must not suppress a co-matched intent: "any drug allergies or
		// skin conditions?" still needs the allergy and medication tables complete.
		Set<QueryScopeRouter.Intent> matched = intents("Any drug allergies or skin conditions?");
		assertFalse(matched.contains(QueryScopeRouter.Intent.CONDITIONS));
		assertTrue(matched.contains(QueryScopeRouter.Intent.ALLERGIES));
		assertTrue(matched.contains(QueryScopeRouter.Intent.MEDICATIONS));
	}
}
