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

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;

/**
 * Maps a clinician's question to the record types whose records belong <em>complete</em> in a
 * query-scoped slice chart ({@code chartsearchai.chartMode=queryScoped}). For an enumeration
 * intent ("what medications…", "any allergies?") the answer's completeness contract ("include
 * ALL relevant records — never omit any") is only satisfiable if every record of the intent's
 * type is in context — similarity top-K alone can truncate a long medication list. The typed
 * scope guarantees that by construction; the similarity top-K is still unioned in by the
 * builder as the semantic catch-all for same-topic records living in other types (e.g. a
 * medication mentioned in an obs note).
 *
 * <p>Routing is deliberately conservative: only unambiguous intent keywords map to a typed
 * scope, matched on word boundaries so substrings ("re<b>program</b>ming") never trigger.
 * A question matching SEVERAL cue sets ("any drug allergies?") carries every matched intent
 * and the builder unions their typed slices: completeness must hold for whichever intent the
 * user meant, and a union can only over-include — first-match routing instead silently
 * dropped the runner-up's completeness on exactly the type being enumerated (measured on the
 * cue patterns: "any drug allergies?" routed MEDICATIONS-only, so an allergy outside the
 * similarity top-K was invisible to an allergy enumeration). Everything cue-free — topical
 * questions like "any eye problems?" — matches nothing ({@link Intent#TOPICAL}), where the
 * slice is the similarity top-K alone and the prompt's abstention language (same contract as
 * the focus-hint path) handles the no-match case. Cue-free is no longer the ONLY route to
 * TOPICAL: a conditions cue narrowed by a clinical domain ("any psychiatric conditions?") is
 * also sent there, because the complete problem list is the wrong context for it — see
 * {@link #isDomainQualified}. A misroute to TOPICAL only costs typed
 * completeness; a wrong or partial typed scope would bias the slice — hence conservative
 * cues, unioned when they co-occur.
 *
 * <p>The type strings are querystore's {@code QueryDocument.resourceType} values (its
 * {@code *RecordSerializer.getResourceType()} contract): {@code drug_order},
 * {@code medication_dispense}, {@code test_order}, {@code referral_order}, {@code allergy},
 * {@code program}, {@code condition}, {@code diagnosis}, {@code visit}, {@code encounter} —
 * plus {@code patient} and {@code obs}, which this router never scopes ({@code patient} is
 * always included by the builder; {@code obs} is the similarity path's domain).
 */
final class QueryScopeRouter {

	private QueryScopeRouter() {
	}

	/** A slice intent: a typed enumeration scope. TOPICAL (similarity only) is the label for the
	 *  no-cue case — {@link #matchedIntents} returns an EMPTY set for it, never the constant, so
	 *  it exists for {@link #typedSlice(Intent)} callers and the builder's log label. */
	enum Intent {
		MEDICATIONS, ALLERGIES, PROGRAMS, CONDITIONS, VISITS, ORDERS, TOPICAL
	}

	private static final Pattern MEDICATIONS_CUES = cues(
			"medications?", "medicines?", "meds", "drugs?", "prescriptions?", "prescribed");

	/** Beyond the literal allergy words: "adverse", "reaction(s)" and "intolerance" are the
	 *  allergy-table's own vocabulary (records read "Allergy: X. Reaction: rash"; the OpenMRS
	 *  allergy UI captures adverse reactions and intolerances), so "any adverse drug reactions?"
	 *  must keep that table typed-complete. Over-inclusion cost is negligible — the allergy
	 *  table is small and unioning it never biases the slice the way a wrong single scope did. */
	private static final Pattern ALLERGIES_CUES = cues("allerg(?:y|ies|ic|en|ens)", "adverse",
			"reactions?", "intoleran(?:t|ce|ces)");

	private static final Pattern PROGRAMS_CUES = cues("programs?", "enrolled", "enrollments?");

	private static final Pattern CONDITIONS_CUES = cues("conditions?", "diagnos(?:is|es|ed)", "problem list");

	/**
	 * The two querystore resource types that record the same clinical problem — an OpenMRS
	 * {@code conditions} row and its {@code encounter_diagnosis}. Shared with
	 * {@code LlmInferenceService}'s twin co-citation, which pairs one against the other, so the
	 * CONDITIONS scope and the pairing rule cannot drift: were a third problem table to appear (or
	 * one be renamed) and only one of the two lists updated, routing would keep enumerating a table
	 * whose rows had silently stopped pairing.
	 */
	static final Set<String> PROBLEM_TABLES = setOf(
			ChartSearchAiConstants.RESOURCE_TYPE_CONDITION,
			ChartSearchAiConstants.RESOURCE_TYPE_DIAGNOSIS);

	/**
	 * Qualifiers that may sit next to a conditions cue without narrowing it to a clinical DOMAIN.
	 * "Any active conditions?", "chronic medical conditions", "diagnosed with anything" are all
	 * still asking for the whole problem list; "psychiatric conditions" and "heart conditions" are
	 * not.
	 *
	 * <p>{@link #narrowsToADomain} consults three other vocabularies directly rather than
	 * re-spelling them — the conditions cue itself ({@link #CONDITIONS_CUES}), the temporal cues
	 * ({@link #TEMPORAL_CUES}) and the other intent cues — because an earlier version copied slices
	 * of all three into this list and the copies had holes: "any recent conditions?", "list her
	 * last diagnoses" and "any conditions diagnosed lately?" all lost the problem list because the
	 * literal list happened not to contain the word the clinician chose.
	 *
	 * <p>What is left here is what those patterns cannot supply, and it is not short. The reason is
	 * mechanical: this check tests ONE token with {@code matches()}, so every multi-word
	 * alternative in {@link #TEMPORAL_CUES} ("most recent", "past 3 months", "this year") is
	 * unreachable from it — which is why the bare units ("day", "week", "month", "year") and the
	 * single-word recency adjectives ("past", "recent", "previous") are members here even though
	 * the multi-word forms live there. Do not delete an entry on the strength of "the temporal
	 * pattern covers it": {@code TEMPORAL_CUES.matcher("recent").matches()} is false.
	 */
	private static final Set<String> GENERIC_CONDITION_WORDS = Collections.unmodifiableSet(
			new HashSet<String>(Arrays.asList(
					"problem", "problems", "active", "inactive", "chronic", "acute", "ongoing",
					"resolved", "new", "old", "past", "recent", "previous", "prior", "underlying", "comorbid",
					"comorbidity", "comorbidities", "medical", "clinical", "health", "anything",
					"everything", "main", "major", "minor", "significant", "important", "relevant",
					"outstanding", "full", "complete", "summarise", "summarize", "summary",
					"overview", "treated", "treatment", "recorded", "documented", "suffer",
					"suffers", "suffering", "day", "days", "week", "weeks", "month", "months",
					"year", "years",
					// The stopword file deliberately PRESERVES negation and some temporal words
					// because they carry clinical meaning for retrieval. That makes them arrive
					// here, where they are not domains: "does she have no conditions?" and "what
					// was her first diagnosis?" are problem-list questions. TEMPORAL_CUES covers
					// "latest"/"last"/"recently" but not "first"/"earliest", so those are listed.
					"no", "not", "never", "none", "without", "neither", "first", "earliest",
					"earlier", "longstanding", "longterm", "lifelong",
					// The verification/certainty values this module renders in its own chart lines
					// ("Status: ACTIVE", "Certainty: PROVISIONAL") are the least domain-like words
					// available, and were reading as domains.
					"confirmed", "provisional", "presumed", "suspected", "refuted", "entered",
					"error", "file", "record", "records", "history", "historical")));

	/** A token made only of digits — a year, a range or a count, never a clinical domain. */
	private static final Pattern NUMERIC_ONLY = Pattern.compile("\\d+");

	/** Everything {@link #narrowsToADomain} compares away before matching a token against the
	 *  generic list, so one rule covers "co-morbid", "2024-01" and "01/2024" alike. */
	private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

	private static final Pattern VISITS_CUES = cues("visits?", "appointments?", "encounters?", "admissions?");

	private static final Pattern ORDERS_CUES = cues("orders?", "ordered");

	private static Pattern cues(String... words) {
		return Pattern.compile("\\b(?:" + String.join("|", words) + ")\\b", Pattern.CASE_INSENSITIVE);
	}

	/**
	 * True when a clinical-domain word narrows a conditions question — "any mental health or
	 * <b>psychiatric</b> conditions?", "any <b>heart</b> conditions?", "diagnosed with
	 * <b>depression</b>?".
	 *
	 * <p>"conditions" is the only enumeration cue that is also a generic noun clinicians attach to
	 * a domain; "medications" and "allergies" name their own. Routing a domain-qualified question
	 * to the CONDITIONS scope hands the small model the whole problem list and asks it to filter,
	 * and on a long list it enumerates instead — measured on the 3.7.1 demo set (30 patients × 9
	 * topics), the mental-health cell answered "Yes … psychiatric conditions recorded: Lumbago
	 * with sciatica, Cardiogenic shock, Bacterial gastroenteritis …", and that one topic produced
	 * 54 of the 92 off-topic citations in the whole eval while genuinely TOPICAL topics (eye,
	 * fractures) drifted 1 each. A domain-qualified question is therefore answered from the
	 * similarity slice, like every other topical question — both more accurate and a smaller
	 * prompt.
	 *
	 * <p>Implemented over the same stopword vocabulary retrieval uses
	 * ({@link QueryPreprocessor#contentWords}) rather than a second word list: anything the
	 * clinician supplied that is not a cue word or a generic problem-list qualifier is a clinical
	 * domain.
	 */
	private static boolean isDomainQualified(String question) {
		for (String word : QueryPreprocessor.contentWords(question)) {
			if (narrowsToADomain(word)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * True when one content word is a clinical domain rather than a way of asking about the
	 * problem list. Everything this class or the stopword file already recognises as
	 * question-shaping vocabulary is consulted rather than re-listed: any intent cue (including the
	 * conditions cue itself — "conditions", "diagnoses" and "diagnosed" describe the list, they do
	 * not narrow it) and any single-word temporal cue.
	 *
	 * <p>Note the deliberate asymmetry in how this fails. Reading a problem-list question as
	 * domain-qualified costs it the completeness guarantee — the answer still comes from the
	 * similarity slice, so it is merely less exhaustive. Reading a DOMAIN question as a
	 * problem-list one produces the measured enumeration failure ("psychiatric conditions:
	 * Cardiogenic shock, Bacterial gastroenteritis …"), which is a clinically wrong answer. When an
	 * unrecognised word forces a guess, this guesses toward the survivable error.
	 */
	private static boolean narrowsToADomain(String word) {
		// contentWords trims only the EDGES of a token, so anything with interior punctuation
		// arrives whole — and interior punctuation is TWO different phenomena that need opposite
		// treatment. As a JOINER it glues one word together ("co-morbid", "long-term", "2024-01"),
		// so the recognisable form is the concatenation. As a SEPARATOR it packs several words into
		// one token ("conditions/diagnoses", "acute/chronic conditions"), where the concatenation is
		// recognisable by nothing — CONDITIONS_CUES needs the \b boundaries that concatenating
		// destroys — and the parts are what must be checked. Handling only the joiner is what cost
		// "any conditions/diagnoses?" its completeness guarantee.
		String joined = NON_ALPHANUMERIC.matcher(word).replaceAll("");
		if (joined.isEmpty() || isProblemListVocabulary(joined) || isProblemListVocabulary(word)) {
			return false;
		}
		String[] parts = NON_ALPHANUMERIC.split(word);
		if (parts.length < 2) {
			return true;
		}
		for (String part : parts) {
			if (!part.isEmpty() && !isProblemListVocabulary(part)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * True when a single token is a way of asking about the problem list rather than a clinical
	 * domain. Everything this class or the stopword file already recognises as question-shaping
	 * vocabulary is consulted rather than re-listed: the generic qualifiers, any intent cue
	 * (including the conditions cue itself — "conditions", "diagnoses" and "diagnosed" describe the
	 * list, they do not narrow it), any single-word temporal cue, and any number (a year, a range or
	 * a count can never name a body system, and TEMPORAL_CUES matches "2 years" but not "2024").
	 */
	private static boolean isProblemListVocabulary(String token) {
		return NUMERIC_ONLY.matcher(token).matches()
				|| GENERIC_CONDITION_WORDS.contains(token)
				|| CONDITIONS_CUES.matcher(token).matches()
				|| TEMPORAL_CUES.matcher(token).matches()
				|| isOtherIntentCue(token);
	}

	/** True when {@code word} is a cue of some OTHER typed intent — "any drug allergies or skin
	 *  conditions?" must not read "drug"/"allergies" as the clinical domain narrowing
	 *  "conditions"; "skin" is what does that. */
	private static boolean isOtherIntentCue(String word) {
		return MEDICATIONS_CUES.matcher(word).matches() || ALLERGIES_CUES.matcher(word).matches()
				|| PROGRAMS_CUES.matcher(word).matches() || VISITS_CUES.matcher(word).matches()
				|| ORDERS_CUES.matcher(word).matches();
	}

	/** Recency cues: questions about the newest value/event or the recent past, which need the
	 *  recency anchor. Includes vague-recency phrasings ("lately", "recently", "past 6 months",
	 *  "this year", "since ...") — measured without them, "What's happened lately?" got no anchor
	 *  and answered from whatever similarity surfaced. */
	private static final Pattern TEMPORAL_CUES = cues(
			"most recent", "latest", "newest", "last", "current", "currently", "now", "today",
			"when was", "when did", "lately", "recently", "since",
			"(?:over |in |during )?(?:the )?past (?:few )?(?:\\d+ )?(?:days?|weeks?|months?|years?)",
			"this (?:week|month|year)");

	/**
	 * True when the question asks about the newest value/event ("most recent weight", "when was
	 * the last visit"). Only temporal questions carry the recency anchor — the chart's newest
	 * records — because similarity ranks by meaning, not date, and can exclude the latest reading
	 * (measured: a stale systolic quoted). The gate is temporal phrasing alone, independent of
	 * typed scope: NON-temporal questions get no anchor, which is what keeps recent vitals out of
	 * an absent-topic slice where they bait enumeration (measured: a non-temporal "any heart
	 * problems?" cell drifting to 39 vitals citations back when the anchor was unconditional). A
	 * typed-scope question phrased temporally ("current medications") does receive the anchor; its
	 * typed scope is already complete, so the newest records are additive rather than misleading.
	 */
	static boolean isTemporal(String question) {
		return question != null && TEMPORAL_CUES.matcher(question).find();
	}

	/**
	 * Every enumeration intent whose cues match {@code question}, in {@link Intent} declaration
	 * order; empty for null/blank/cue-free questions (the TOPICAL, similarity-only case). The
	 * result never contains {@link Intent#TOPICAL}. Multi-cue questions return every matched
	 * intent so the builder can union their typed slices — see the class javadoc for why
	 * first-match-wins was the collision that silently dropped allergy completeness.
	 */
	static Set<Intent> matchedIntents(String question) {
		if (question == null || question.trim().isEmpty()) {
			return Collections.emptySet();
		}
		Set<Intent> matched = EnumSet.noneOf(Intent.class);
		String q = question.toLowerCase(Locale.ROOT);
		if (MEDICATIONS_CUES.matcher(q).find()) {
			matched.add(Intent.MEDICATIONS);
		}
		if (ALLERGIES_CUES.matcher(q).find()) {
			matched.add(Intent.ALLERGIES);
		}
		if (PROGRAMS_CUES.matcher(q).find()) {
			matched.add(Intent.PROGRAMS);
		}
		if (CONDITIONS_CUES.matcher(q).find() && !isDomainQualified(q)) {
			matched.add(Intent.CONDITIONS);
		}
		if (VISITS_CUES.matcher(q).find()) {
			matched.add(Intent.VISITS);
		}
		if (ORDERS_CUES.matcher(q).find()) {
			matched.add(Intent.ORDERS);
		}
		return matched;
	}

	/** The union of {@link #typedSlice(Intent)} across {@code intents}; empty when none matched
	 *  (TOPICAL: the slice is similarity-only). */
	static Set<String> typedSlice(Set<Intent> intents) {
		if (intents.isEmpty()) {
			return Collections.emptySet();
		}
		Set<String> union = new HashSet<String>();
		for (Intent intent : intents) {
			union.addAll(typedSlice(intent));
		}
		return Collections.unmodifiableSet(union);
	}

	/** The querystore resource types included complete for {@code intent}; empty for TOPICAL. */
	static Set<String> typedSlice(Intent intent) {
		switch (intent) {
			case MEDICATIONS:
				return setOf("drug_order", "medication_dispense");
			case ALLERGIES:
				return setOf("allergy");
			case PROGRAMS:
				return setOf("program");
			case CONDITIONS:
				return PROBLEM_TABLES;
			case VISITS:
				return setOf("visit", "encounter");
			case ORDERS:
				return setOf("drug_order", "test_order", "referral_order");
			default:
				return Collections.emptySet();
		}
	}

	private static Set<String> setOf(String... types) {
		Set<String> set = new HashSet<String>();
		Collections.addAll(set, types);
		return Collections.unmodifiableSet(set);
	}
}
