/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

/**
 * A non-blocking advisory raised by {@link DrugSafetyValidator} after the LLM
 * answers. A warning <em>annotates</em> the answer — it never rewrites or
 * suppresses it. The clinician decides. Carried on
 * {@code ChartSearchService.ChartAnswer} and rendered as a chip below the
 * answer in the frontend.
 */
public class SafetyWarning {

	/** Overdose: a daily dose parsed from the answer exceeds the reference maximum for the patient's
	 *  age band — or, when a fresh weight is on record, a per-administration dose exceeds the band's
	 *  {@code mgPerKgMax} × weight. One warning per drug; the daily ceiling wins when both trip. */
	public static final String TYPE_OVERDOSE = "overdose";

	/**
	 * Interaction: two drugs the reference data relates — by a dataset rule (one the source RATED has
	 * to clear {@code chartsearchai.drugSafety.minInteractionSeverity}; an unrated one is exempt, so
	 * every hand-authored rule shows), or by a shared ATC level-4 subgroup / curated cross-reactivity
	 * group, which carry no severity and are never floor-filtered.
	 *
	 * <p>Either side may come from the question, from the answer's own proposal, or from the patient's
	 * chart, so there are three joins — a drug in play against an active order (the patient-specific
	 * one); several drugs the QUESTION names against each other (a reference lookup that may involve
	 * no drug the patient takes, so it is the one join whose detail does NOT claim an active order —
	 * issue #114); and, for a question that asks to be screened but names no drug, the patient's own
	 * active orders against each other (a chart drug on BOTH sides, so its detail names an active
	 * order exactly as the first join's does — issue #113). See {@code DrugSafetyValidator}.
	 */
	public static final String TYPE_INTERACTION = "interaction";

	/**
	 * Contraindication: a drug is contraindicated by an active allergy or condition. Two joins — a drug
	 * IN PLAY (asked about in the question, or named by the answer on its own authority), and the
	 * patient's OWN ACTIVE ORDERS whatever the question and the answer name. (Enumerated for the same
	 * reason {@link #TYPE_INTERACTION} enumerates its three: which joins a chip type can come from is
	 * what a renderer needs to know about it.)
	 *
	 * <p>The first is keyed off the question, not ONLY the answer: the headline case is a recorded
	 * allergy to the very drug the clinician asked about, where the answer may never write the drug's
	 * name at all (issue #135). The second exists because the in-play framing could not ask "is the
	 * patient allergic to something they are TAKING?" — a prescribing error the chart already contains,
	 * which no wording of a question or an answer should be able to hide (issue #143). So a
	 * contraindication chip does NOT imply that anything proposed the drug; it may be reporting a
	 * medication the patient is already on. See {@code DrugSafetyValidator}.
	 */
	public static final String TYPE_CONTRAINDICATION = "contraindication";

	private final String type;

	private final String drug;

	private final String detail;

	public SafetyWarning(String type, String drug, String detail) {
		this.type = type;
		this.drug = drug;
		this.detail = detail;
	}

	/** One of {@link #TYPE_OVERDOSE}, {@link #TYPE_INTERACTION}, {@link #TYPE_CONTRAINDICATION}. */
	public String getType() {
		return type;
	}

	/** The reference drug the warning is about — its display label, which may carry a
	 *  parenthesized generic synonym when the dataset's display name diverges from it,
	 *  e.g. {@code "Acetylsalicylic acid (aspirin)"} (see {@link DrugReference#displayLabel()}). */
	public String getDrug() {
		return drug;
	}

	/** The warning as one complete, standalone sentence naming the drug — e.g. "The stated
	 *  Ibuprofen dose ~2400 mg/day exceeds the 1200 mg/day maximum for ages 2-11" or
	 *  "Warfarin interacts with active order aspirin — Major. …". <b>Renderers should display
	 *  this alone</b>; prefixing {@link #getDrug()} duplicates the subject, because every
	 *  detail already leads with it. The drug field exists for grouping/sorting/deduping, not
	 *  as a display prefix. */
	public String getDetail() {
		return detail;
	}

	@Override
	public String toString() {
		return type + ":" + drug + ":" + detail;
	}
}
