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
 *
 * <p>Public only so the drug-safety layer can reuse this router's classification of a question —
 * {@link #isInteractionScreening}, and the three domain predicates {@link #asksAboutMedications},
 * {@link #asksAboutAllergies} and {@link #asksAboutConditions} that scope its contraindication arm.
 * Question intent is classified here and must not be classified a second time elsewhere, which is the
 * whole of what earns a member its {@code public}: every member that is not read outside this package
 * stays package-private, and a new one becomes public only by being a classification a caller would
 * otherwise re-derive.
 */
public final class QueryScopeRouter {

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

	/** Drug-interaction cues. Word-boundary anchored via {@link #cues}, so "interactive" — whose
	 *  suffix matches none of the alternatives — never triggers. Deliberately only the {@code
	 *  interact*} family: looser near-synonyms ("conflict", "interfere") carry everyday non-drug
	 *  senses, and this predicate gates a clinician-facing safety output where firing on an
	 *  unrelated question is worse than missing a phrasing. */
	private static final Pattern INTERACTION_CUES = cues("interact(?:s|ed|ing|ion|ions)?");

	/**
	 * True when the question asks to be SCREENED for drug interactions — "are there any drug
	 * interactions with her current medications?", "do any of her meds interact?" — as opposed to
	 * merely mentioning medications. Consumed by {@code DrugSafetyValidator}, which has no way to
	 * anchor such a question on a named drug and instead screens the patient's own active orders
	 * against each other (issue #113).
	 *
	 * <p>Two cues must BOTH hold: an {@code interact*} word, and the router's own
	 * {@link Intent#MEDICATIONS} classification. Reusing that classification rather than writing a
	 * second drug vocabulary is the point — "medication-domain question" keeps one definition — and
	 * it is what makes the trigger conservative: a question about how a patient interacts with their
	 * care team carries no medication cue and screens nothing. The accepted cost is that a bare
	 * "any interactions?" does not trigger; in practice a clinician names what might interact
	 * ("drug", "meds", "medications", "prescriptions"), all of which the MEDICATIONS cues cover.
	 *
	 * <p>Note this is a cue predicate, NOT an {@link Intent}: interaction screening changes what the
	 * safety layer checks, not which record types a slice must contain, so — like
	 * {@link #isTemporal} — it deliberately stays out of the enumeration-scope mapping.
	 */
	public static boolean isInteractionScreening(String question) {
		return question != null && INTERACTION_CUES.matcher(question).find()
				&& matchedIntents(question).contains(Intent.MEDICATIONS);
	}

	/**
	 * Whether {@code question} is in the MEDICATION domain, by the router's own classification.
	 *
	 * <p>Public for the same reason {@link #isInteractionScreening} is, and reusing
	 * {@link #matchedIntents} for the same reason it does: one definition of "medication-domain
	 * question", never a second drug vocabulary. The drug-safety layer asks it as a WIDENING signal
	 * on the drug side of a contraindication — a question about what the patient is taking makes her
	 * whole active-order list the response's subject matter even when the prose writes no individual
	 * drug name, which a gate reading only the words would miss. It never narrows anything: a
	 * question naming a drug the dataset recognises carries no cue word here ("Can I give her
	 * bupivacaine?" matches none of the cues) and is already handled by the drug-in-play arm.
	 */
	public static boolean asksAboutMedications(String question) {
		return matchedIntents(question).contains(Intent.MEDICATIONS);
	}

	/**
	 * As {@link #asksAboutMedications}, for the ALLERGY domain — one of the two widening signals on the
	 * FINDING side. A question about her allergies makes her recorded allergies the subject matter, so a
	 * drug one of them contraindicates is worth a chip even where the answer names neither.
	 */
	public static boolean asksAboutAllergies(String question) {
		return matchedIntents(question).contains(Intent.ALLERGIES);
	}

	/**
	 * As {@link #asksAboutAllergies}, for the CONDITION domain — the other widening signal on the
	 * finding side, because the finding a contraindication rule fires on is an allergy OR a condition
	 * and the two lists have equal claim to being what was asked about. Kept a separate predicate rather
	 * than folded into one "asks about her records": the drug-safety layer widens per LIST, so a
	 * question about her problem list must not put her allergy records in scope as well.
	 */
	public static boolean asksAboutConditions(String question) {
		return matchedIntents(question).contains(Intent.CONDITIONS);
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

	/** The querystore resource types included complete for {@code intent}; empty for TOPICAL.
	 *  {@code drug_order} reads {@link ChartSearchAiConstants#RESOURCE_TYPE_DRUG_ORDER} because this
	 *  module declares that type and three other places read it; the rest are querystore contract
	 *  strings this module has never declared, and declaring one is a reference-group decision
	 *  ({@code ChartSearchAiReferenceGroupTest} sweeps every declared type) rather than a rename. */
	static Set<String> typedSlice(Intent intent) {
		switch (intent) {
			case MEDICATIONS:
				return setOf(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER, "medication_dispense");
			case ALLERGIES:
				return setOf("allergy");
			case PROGRAMS:
				return setOf("program");
			case CONDITIONS:
				return setOf("condition", "diagnosis");
			case VISITS:
				return setOf("visit", "encounter");
			case ORDERS:
				return setOf(ChartSearchAiConstants.RESOURCE_TYPE_DRUG_ORDER, "test_order", "referral_order");
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
