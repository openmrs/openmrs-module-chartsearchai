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

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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
 * the focus-hint path) handles the no-match case. A misroute to TOPICAL only costs typed
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

	private static final Pattern VISITS_CUES = cues("visits?", "appointments?", "encounters?", "admissions?");

	private static final Pattern ORDERS_CUES = cues("orders?", "ordered");

	private static Pattern cues(String... words) {
		return Pattern.compile("\\b(?:" + String.join("|", words) + ")\\b", Pattern.CASE_INSENSITIVE);
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

	/** Enumeration/aggregate/extreme cues: the question asks for the WHOLE series of a concept or an
	 *  aggregate/extreme over it ("list all X", "highest X", "X trend", "how many X"). These are the
	 *  questions a fixed similarity top-K truncates, so the slice builder gives them a larger K.
	 *  Deliberately matches only EXPLICIT series phrasing: a yes/no question that implicitly needs the
	 *  series ("is she hypertensive?") carries no such cue and is NOT matched — recognizing it would
	 *  require clinical knowledge (hypertensive → blood pressure), which this router never encodes.
	 *
	 *  <p>The cue set is PRECISION-biased, NOT liberal. A false negative merely keeps the default K
	 *  (today's behaviour, safe); a false POSITIVE on an ABSENT-topic question bumps K and feeds the
	 *  small model off-topic nearest-neighbours — the exact abstention/drift regression a GLOBAL bump
	 *  caused. So a cue must be a phrasing that essentially never occurs in a "does the patient have
	 *  X?" yes/no question (see the exclusion list below). Two residuals are ACCEPTED, not solved:
	 *  (1) enumeration phrasing over an absent condition ("list all her heart problems" on a patient
	 *  who has none) still bumps — concept absence is unknowable before retrieval, so it is not
	 *  lexically fixable; it is far narrower than the global bump and the answer still abstains.
	 *  (2) some genuine enumeration phrasings are missed ("lab results" — "results" is not a cue): an
	 *  accepted safe false-negative, not risked as a new cue without its own leak analysis. */
	private static final Pattern ENUMERATION_CUES = cues(
			"list", "listing", "trend", "trends", "over time", "highest", "lowest", "maximum",
			"minimum", "average", "averages", "readings", "values", "how many", "how often");
	// Only phrasings that essentially never occur in "does the patient have X?" yes/no questions are
	// cues (see the precision rationale above). Deliberately EXCLUDED because they collide with such
	// phrasings and their enumeration intent is already carried by a kept cue:
	//   "history"  — "history of kidney problems" (condition yes/no); "trend"/"over time" carry the
	//                genuine measure-history intent.
	//   "all"      — "any allergies at all?" ("\ball\b" fires inside "at all"); "list" carries
	//                "list all X".
	//   "every"/"each" — "every day"/"each week" (frequency yes/no, e.g. "seizures every day?").
	//   "min"/"max"    — "every 30 min" (minutes), "max dose"; covered by minimum/maximum/lowest/highest.
	//   "count"        — "platelet count"/"blood count" (lab names); covered by "how many"/"how often".
	//   "series"       — radiology order names ("rib series", "obstruction series", "acute abdominal
	//                    series"); its rare enumeration sense is covered by the other cues.

	/** True when the question asks for a concept's complete series or an aggregate/extreme over it
	 *  (see {@link #ENUMERATION_CUES}) — the class a fixed top-K truncates. Purely lexical, no
	 *  clinical vocabulary. */
	static boolean wantsCompleteSeries(String question) {
		return question != null && ENUMERATION_CUES.matcher(question).find();
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
		if (CONDITIONS_CUES.matcher(q).find()) {
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
				return setOf("condition", "diagnosis");
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
