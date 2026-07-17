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
 * Everything else — topical questions like "any eye problems?" — is {@link Intent#TOPICAL},
 * where the slice is the similarity top-K alone and the prompt's abstention language (same
 * contract as the focus-hint path) handles the no-match case. A misroute to TOPICAL only
 * costs typed completeness; a wrong typed scope would bias the slice — hence conservative.
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

	/** The question's slice intent: a typed enumeration scope, or TOPICAL (similarity only). */
	enum Intent {
		MEDICATIONS, ALLERGIES, PROGRAMS, CONDITIONS, VISITS, ORDERS, TOPICAL
	}

	private static final Pattern MEDICATIONS_CUES = cues(
			"medications?", "medicines?", "meds", "drugs?", "prescriptions?", "prescribed");

	private static final Pattern ALLERGIES_CUES = cues("allerg(?:y|ies|ic|en|ens)");

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

	/** Routes a question to its slice intent; null/blank questions are TOPICAL. */
	static Intent route(String question) {
		if (question == null || question.trim().isEmpty()) {
			return Intent.TOPICAL;
		}
		String q = question.toLowerCase(Locale.ROOT);
		if (MEDICATIONS_CUES.matcher(q).find()) {
			return Intent.MEDICATIONS;
		}
		if (ALLERGIES_CUES.matcher(q).find()) {
			return Intent.ALLERGIES;
		}
		if (PROGRAMS_CUES.matcher(q).find()) {
			return Intent.PROGRAMS;
		}
		if (CONDITIONS_CUES.matcher(q).find()) {
			return Intent.CONDITIONS;
		}
		if (VISITS_CUES.matcher(q).find()) {
			return Intent.VISITS;
		}
		// After MEDICATIONS: "what prescriptions were ordered" is a medications question first —
		// the med scope carries the drug orders anyway, with the stronger completeness contract.
		if (ORDERS_CUES.matcher(q).find()) {
			return Intent.ORDERS;
		}
		return Intent.TOPICAL;
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
