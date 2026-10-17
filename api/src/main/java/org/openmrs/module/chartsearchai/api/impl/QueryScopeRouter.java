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
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
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
			"medications?", "medicines?", "meds", "drugs?", "prescriptions?", "prescribed", "regimens?",
			// What she takes, as a clinician words it: "Is anything she takes interacting?" (ADR Decision 180)
			"(?:she|he|they|the patient|this patient) (?:takes|is taking|are taking)");

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
	 * The abbreviation for a DRUG-drug interaction, which names the medication domain and the interaction at once.
	 * <em>"Any DDIs?"</em> matched neither family above, so on a patient whose orders the module relates in thirteen
	 * pairs no screen ran and the model answered <em>"No drug-drug interactions are recorded."</em> CASE-SENSITIVE,
	 * and that is load-bearing: "ddI" is didanosine's own abbreviation, and a question about her ddI is not a request
	 * to screen. {@link #words} lower-cases, so the screen shapes below spell it {@code ddis?}; only this cue decides
	 * whether a screen runs.
	 */
	private static final Pattern DDI_CUES = Pattern.compile("\\bDDI[sS]?\\b");

	/**
	 * The NOUN, which in a question put to a patient's chart asks after her drugs' interactions whether or not the
	 * question also names her medications (ADR Decision 177). <em>"What interactions does she have?"</em> and
	 * <em>"Any clinically significant interactions?"</em>, on a patient whose orders relate in thirteen pairs, two
	 * Major, ran no screen and were answered <em>"No clinically significant interactions are recorded."</em> The noun
	 * and not the verb: <em>"Does the patient interact well with the care team?"</em> is not one, and neither is a
	 * question carrying a {@link #SOCIAL_CUES} word.
	 */
	private static final Pattern INTERACTION_NOUN_CUES = cues("interactions?");

	/** What makes the noun's interactions a person's rather than her drugs': {@link #INTERACTION_NOUN_CUES}. */
	private static final Pattern SOCIAL_CUES = cues("social(?:ly)?", "behaviou?rs?", "staff", "family", "families",
			"caregivers?", "carers?", "team", "peers?", "people", "others", "friends", "relatives", "children", "partner",
			"nurses?", "doctors?", "providers?", "clinicians?", "communication");

	/**
	 * The SECOND way a question asks for an interaction screen: it asks after the SAFETY of the
	 * medications the patient is already on, or after CHANGING them, without using the word
	 * "interact" at all. Word-boundary anchored via {@link #cues} like every other family here.
	 *
	 * <p>Measured live on the :8081 3.7.1 standalone on 2026-09-10, over sixteen DDI questions on
	 * eight patients: with {@link #isInteractionScreening} requiring an {@code interact*} word,
	 * <em>"Should I stop any of the medications he is on?"</em> screened NOTHING on a patient whose
	 * <em>"Are any of his current medications interacting with each other?"</em> reported a
	 * <b>Major</b> pair — same patient, same chart, same request path. A clinician asking the most
	 * natural review question was the one who saw no hazard.
	 *
	 * <p><b>Why this does not re-open #143's over-reach.</b> The bar is not "mentions medications" —
	 * that is the widening `DrugSafetyValidator.SubjectMatter`'s rules forbid, and it would put
	 * interaction chips on <em>"What medications is the patient taking?"</em>. Every member here
	 * asks about a medication's SAFETY or about STOPPING or CHANGING one, so a chip it
	 * raises is still tied to what was asked. An ENUMERATION request carries none of them, which is
	 * what keeps {@code DrugSafetyInteractionScreeningTest}'s two eager-firing guards green — and
	 * those guards, not this list, are what a new member has to be measured against.
	 *
	 * <p>Deliberately NOT here: "review" unqualified (a "records review" is not a drug question) and
	 * bare "check". Both carry everyday non-medication senses in a chart question, which is the
	 * reason {@link #INTERACTION_CUES} gives for excluding "conflict" and "interfere".
	 */
	private static final Pattern MEDICATION_SAFETY_CUES = cues(
			"safe", "unsafe", "safety", "danger(?:ous)?", "harmful", "risk(?:s|y)?",
			"worry", "worried", "worrying", "concern(?:s|ed|ing)?",
			"problem(?:s|atic)?", "wrong",
			"stop(?:ped|ping)?", "discontinue(?:d)?", "deprescribe(?:d)?",
			"change(?:d|s)?", "adjust(?:ed|ment|ments)?",
			// "Are her medications compatible with each other?", "Are her medicines okay together?" (ADR Decision 177)
			"compatib(?:le|ility)", "(?:ok|okay|fine|alright) (?:together|with each other)",
			// "Are there any contraindications among her medicines?", "Do her meds clash?", "Can her medications be taken
			// together?" (ADR Decision 180)
			"contraindicat(?:ed|ion|ions)", "clash(?:es|ing)?", "(?:taken|given|used) together",
			// "Do any of her current prescriptions conflict?" — conjoined with a medication word like every cue here, so
			// the everyday senses INTERACTION_CUES' javadoc keeps out of the bare family do not screen (ADR Decision 182)
			"conflict(?:s|ing)?",
			// "What should I monitor given her medications?", "What should I watch for with his meds?" — what the CLINICIAN
			// should monitor or watch for, never the bare word: "Is her medication adherence monitored?" asks nothing of
			// the drugs' safety (ADR Decision 205)
			"(?:should|must|do|would) (?:i|we) (?:monitor|watch)", "monitor(?:ing)? for", "watch (?:out )?for");

	/**
	 * True when the question asks to be SCREENED for drug interactions — "are there any drug
	 * interactions with her current medications?", "do any of her meds interact?" — as opposed to
	 * merely mentioning medications. Consumed by {@code DrugSafetyValidator}, which has no way to
	 * anchor such a question on a named drug and instead screens the patient's own active orders
	 * against each other (issue #113).
	 *
	 * <p>Two things must BOTH hold: the question asks for a reading of the drugs themselves
	 * ({@link #asksForADrugSafetyReading} — an {@code interact*} word, OR a safety-or-change cue),
	 * and the router's own {@link Intent#MEDICATIONS} classification. Reusing that classification
	 * rather than writing a second drug vocabulary is the point — "medication-domain question" keeps
	 * one definition — and it is what makes the trigger conservative: a question about how a patient
	 * interacts with their care team carries no medication cue and screens nothing. The accepted cost
	 * is that a bare "any interactions?" does not trigger; in practice a clinician names what might
	 * interact ("drug", "meds", "medications", "prescriptions"), all of which the MEDICATIONS cues
	 * cover.
	 *
	 * <p><b>The second cue family is not a loosening of the first.</b> Until 2026-09-10 an
	 * {@code interact*} word was NECESSARY, and a live sixteen-question measurement found that a
	 * <b>Major</b> pair reported for "are any of his current medications interacting?" was invisible
	 * to "should I stop any of the medications he is on?" on the same chart. What licenses a screen
	 * is a question about the drugs' SAFETY or about CHANGING them, of which naming an interaction is
	 * one case; {@link #MEDICATION_SAFETY_CUES} carries the rest, and why an ENUMERATION request is
	 * still not one of them.
	 *
	 * <p>Note this is a cue predicate, NOT an {@link Intent}: interaction screening changes what the
	 * safety layer checks, not which record types a slice must contain, so — like
	 * {@link #isTemporal} — it deliberately stays out of the enumeration-scope mapping.
	 */
	public static boolean isInteractionScreening(String question) {
		return question != null && (DDI_CUES.matcher(question).find() || asksForADrugSafetyReading(question)
				&& matchedIntents(question).contains(Intent.MEDICATIONS)
				|| INTERACTION_NOUN_CUES.matcher(question).find() && !SOCIAL_CUES.matcher(question).find()
				// Two groups of her own medications, named by words the MEDICATIONS cues need not know — "her TB treatment",
				// "her ARVs" — and asked whether they interact: the grammar says what the cues cannot (ADR Decision 221).
				|| fitsAShape(words(question), MEDICATION_GROUPS_SHAPES)
				|| asksAboutDuplicateTherapy(question)
				// A choice of drug asked of the module is answered with the screen of what she takes (ADR Decision 293), and so
				// is what to monitor with her medications (ADR Decision 297).
				|| asksWhichDrugToGiveHer(question) || asksWhatToMonitorWithHerMedications(question)
				// And which of her medications are high risk, and whether any need changing (ADR Decision 300).
				|| asksWhichOfHerMedicationsAreHighRisk(question) || asksWhetherHerMedicationsNeedChanging(question));
	}

	/**
	 * A question asking whether her medications DUPLICATE one another (ADR Decision 232): a duplication word, beside a
	 * word saying the duplicates are drugs — a medication word, "therapy", a drug-class noun, or "is she on". On the 3.7.1
	 * standalone Sarah Taylor, on ibuprofen, diclofenac and celecoxib, asked "Is she on any duplicate NSAIDs?", was
	 * answered "No duplicate NSAIDs are recorded.": no screen ran, so its finding that the three share a drug group never
	 * reached the prompt. The second word keeps a duplicate RECORD — "a duplicate encounter" — from screening her drugs.
	 */
	private static final Pattern DUPLICATION_CUES = cues("duplicat(?:e|es|ed|ion|ions)", "same (?:drug )?class(?:es)?",
			"doubled? up");

	/** What says the duplicates a {@link #DUPLICATION_CUES} word asks after are drugs, beyond the MEDICATIONS cues. */
	private static final Pattern DUPLICATED_DRUG_CUES = cues("therap(?:y|ies)", "treatments?",
			"(?:is|are) (?:she|he|they|the patient|this patient) (?:currently )?(?:on|taking)", "nsaids?",
			"(?:cortico)?steroids?", "opioids?", "statins?", "anticoagulants?", "antiplatelets?", "antibiotics?", "arvs?",
			"antiretrovirals?", "antihypertensives?", "antidepressants?", "antipsychotics?", "benzodiazepines?",
			"painkillers?", "analgesics?", "diuretics?", "antihistamines?", "antiepileptics?", "anticonvulsants?",
			"penicillins?", "ppis?");

	/** Whether {@code question} asks whether her medications duplicate one another — {@link #DUPLICATION_CUES}. A screen,
	 *  and one {@code DrugReferenceInjector} answers with the duplicates it found (ADR Decision 232). */
	public static boolean asksAboutDuplicateTherapy(String question) {
		return question != null && DUPLICATION_CUES.matcher(question).find() && (matchedIntents(question).contains(Intent.MEDICATIONS)
				|| DUPLICATED_DRUG_CUES.matcher(question).find());
	}

	/**
	 * Whether the question asks for a reading of the drugs themselves — either naming an
	 * interaction ({@link #INTERACTION_CUES}) or asking after their safety or a change to them
	 * ({@link #MEDICATION_SAFETY_CUES}). The disjunction is what {@link #isInteractionScreening}
	 * conjoins with the MEDICATIONS intent, and it is a separate method so each family can be
	 * mutated on its own: neuter either alternative and read which cases redden. Public for
	 * {@code ConflictingOrderStatement}, which asks it on its own: an allergy question asking whether
	 * something is safe is a drug-safety question, whatever intent it matched.
	 */
	public static boolean asksForADrugSafetyReading(String question) {
		return INTERACTION_CUES.matcher(question).find()
				|| MEDICATION_SAFETY_CUES.matcher(question).find();
	}

	/** One word of a question, as {@link #words} splits it: letters and digits, with a trailing
	 *  apostrophe suffix kept attached so "can't" and "patient's" are words of their own. */
	private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?");

	/**
	 * The word {@code DrugReferenceInjector} puts where a question names the drug it proposes, so
	 * {@link #asksWhetherToGiveADrug} can read WHERE the name stood — issue #469. A question that
	 * spells it literally yields it as a word too, and is then refused: every shape has exactly one
	 * mark, and the drug the injector resolved adds its own.
	 */
	public static final String DRUG_NAME = "_drug_";

	/**
	 * The words of {@code text}, lower-cased, in order — the ONE tokenizer both question grammars
	 * below read. {@link #DRUG_NAME} is kept as a word. Issue #469.
	 */
	public static List<String> words(String text) {
		List<String> words = new ArrayList<String>();
		if (text == null) {
			return words;
		}
		String[] parts = text.toLowerCase(Locale.ROOT).split(Pattern.quote(DRUG_NAME), -1);
		for (int p = 0; p < parts.length; p++) {
			if (p > 0) {
				words.add(DRUG_NAME);
			}
			Matcher matcher = WORD.matcher(parts[p]);
			while (matcher.find()) {
				words.add(matcher.group().replace('\u2019', '\''));
			}
		}
		return words;
	}

	private static final String PATIENT = "(?:her|him|them|the patient|this patient)";

	private static final String POSSESSIVE = "(?:her|his|their|the patient's|this patient's)";

	private static final String MEDICATIONS = "(?:current |active )?(?:medications|meds|medicines|prescriptions|drugs|regimen)";

	private static final String D = Pattern.quote(DRUG_NAME);

	/** The tail a screen question may close with: what the clinician should know, or worry, about. */
	private static final String KNOW_ABOUT = "(?: i (?:should|need to) (?:know|worry) about)?";

	/** How a screen question may grade the interactions it asks for: "clinically significant", "serious". */
	private static final String QUALIFIER =
			"(?:(?:clinically )?(?:significant|important|relevant|serious|major|dangerous|adverse) )?";

	/** Who takes the drugs a screen question asks about, as its words read: "she is on", "he takes". */
	private static final String SUBJECT_TAKES = "(?:she|he|they|the patient|this patient) (?:is on|are on|takes|take|is taking)";

	/**
	 * The question shapes a proposal of ONE drug may take, over {@link #words} joined by single spaces
	 * with the drug's name marked {@link #DRUG_NAME} — issue #469. A GRAMMAR and not a word list: two
	 * forms of this predicate were bags of words, and each was defeated by words it admitted in an
	 * order it did not mean — a second question joined to the proposal, a purpose or a first-person
	 * question built from admitted words. A shape states word ORDER, so a question carrying anything
	 * a shape does not name is not admitted.
	 */
	private static final List<Pattern> PROPOSAL_SHAPES = besideHerMedications(
			// "Can I give her ibuprofen?", "Should I start her on clarithromycin?", "Can I give
			// ibuprofen to her?"
			"(?:can|could|may|should) (?:i|we) (?:also )?(?:safely )?(?:give|start|prescribe|administer|add) (?:"
					+ PATIENT + " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT + ")?(?: now| today)?",
			// "Can I add ibuprofen to her medications?" (ADR Decision 180)
			"(?:can|could|may|should) (?:i|we) (?:also )?(?:safely )?add " + D + " to " + POSSESSIVE + " " + MEDICATIONS
					+ "(?: now| today)?",
			// "Can she drink alcohol with her medications?", "Is it safe for her to drink alcohol with her meds?" (ADR Decision
			// 300) — a substance she would take herself, beside what she takes,
			// proposed as "Can she drink alcohol?" is (ADR Decision 268)
			"(?:can|could|may|should) (?:she|he|they|the patient|this patient) (?:safely )?(?:drink|have|take|consume) " + D
					+ " (?:with|alongside|while (?:on|taking)) " + POSSESSIVE + " " + MEDICATIONS,
			"is it (?:safe|ok|okay) for (?:her|him|them|the patient|this patient) to (?:drink|have|take|consume) " + D
					+ " (?:with|alongside|while (?:on|taking)) " + POSSESSIVE + " " + MEDICATIONS,
			// "Can this patient take warfarin?", "Can she take ibuprofen?", "Can she drink alcohol?" (ADR Decision 268)
			"(?:can|could|may|should) (?:she|he|they|the patient|this patient) (?:also )?(?:safely )?(?:take|start|be given|be started on"
					+ "|have|use|drink) " + D + "(?: now| today)?",
			// "Would it be safe to start ibuprofen?", "OK to give ibuprofen?", "Is it safe to add ibuprofen to her regimen?"
			// (ADR Decision 180)
			"would it be (?:safe|ok|okay|appropriate) (?:for " + PATIENT + " )?to (?:(?:give|start|prescribe|administer|add) (?:"
					+ PATIENT + " )?(?:on )?|take )" + D + "(?: for " + PATIENT + ")?(?: now| today)?",
			"(?:is it )?(?:ok|okay) to (?:give|start|prescribe|administer|add) (?:" + PATIENT + " )?(?:on )?" + D
					+ "(?: (?:to|for) " + PATIENT + ")?(?: now| today)?",
			"is it (?:safe|ok|okay|appropriate) to add " + D + " to " + POSSESSIVE + " " + MEDICATIONS + "(?: now| today)?",
			// "Is it safe to give her ibuprofen?", "Is it safe to start her on clarithromycin?", "Is it
			// safe to add prednisone for her?" — the patient after the drug too (issue #548), the
			// allowance the first and fourth shapes make, as a reordering of "Is it safe for her to add
			// prednisone?", which this shape already admits.
			"is it (?:safe|ok|okay|appropriate) (?:for " + PATIENT + " )?to (?:(?:give|start|prescribe|administer|add) (?:"
					+ PATIENT + " )?(?:on )?|take )" + D + "(?: for " + PATIENT + ")?(?: now| today)?",
			// "Is ibuprofen safe for her?", "Is ibuprofen appropriate for this patient?"
			"is " + D + " (?:safe|ok|okay|appropriate)(?: for " + PATIENT + ")?(?: now| today)?",
			// "Would ibuprofen be appropriate for her?"
			"(?:would|will) " + D + " be (?:safe|ok|okay|appropriate)(?: for " + PATIENT + ")?",
			// "Is ibuprofen safe to add?", "Is ibuprofen safe to give her?" — the drug before "safe to", the
			// fourth shape's word order with the third's verbs (ADR Decision 134). No purpose, no second drug.
			"is " + D + " (?:safe|ok|okay|appropriate) to (?:give|start|prescribe|administer|add|use)(?: (?:to |for )?"
					+ PATIENT + ")?(?: now| today)?",
			// "Can ibuprofen be started?", "Can ibuprofen be given to her?" — the drug as the passive subject
			// (ADR Decision 134). Only verbs of giving: "be stopped" or "be avoided" would read a "No" backwards.
			"(?:can|could|may|should) " + D + " be (?:safely )?(?:given|started|prescribed|administered|added)(?: (?:to|for) "
					+ PATIENT + ")?(?: now| today)?");

	/**
	 * The question shapes asking whether NOT to give one drug — ADR Decision 181. Their polarity is the proposal shapes'
	 * inverted: "No" answers <em>"Is there any reason not to give her ibuprofen?"</em> by saying there is none. They were
	 * left to the model so that the module's "No" lead could not answer them backwards, and on the 3.7.1 standalone the
	 * model answered them backwards itself (<em>"No — Rifampicin should not be given …"</em>, five of six patients). So
	 * {@link #asksWhetherToGiveADrug} admits them — each still proposes the drug — and {@link #asksWhetherNotToGiveADrug}
	 * tells the composer to open with neither "Yes" nor "No".
	 */
	private static final List<Pattern> NEGATED_PROPOSAL_SHAPES = shapes(
			// "Is there any reason not to give her ibuprofen?"
			"is there (?:any )?reason not to (?:give|start|prescribe|administer|add|use) (?:" + PATIENT + " )?(?:on )?" + D
					+ "(?: (?:to|for) " + PATIENT + ")?",
			// "Is ibuprofen contraindicated?", "Is ibuprofen contraindicated for her?"
			"is " + D + " contraindicated(?: (?:for|in) " + PATIENT + ")?(?: now| today)?",
			// "Would ibuprofen be contraindicated for her?" (ADR Decision 243)
			"(?:would|will) " + D + " be contraindicated(?: (?:for|in) " + PATIENT + ")?(?: now| today)?",
			// "Should I avoid ibuprofen?", "Should ibuprofen be avoided?", "Do I need to avoid ibuprofen in her?" (ADR Decision
			// 247)
			"(?:should|must) (?:i|we) avoid (?:giving )?(?:" + PATIENT + " )?" + D + "(?: (?:for|in) " + PATIENT + ")?",
			"(?:should|must) " + D + " be avoided(?: (?:for|in) " + PATIENT + ")?",
			"do (?:i|we) (?:need|have) to avoid (?:giving )?(?:" + PATIENT + " )?" + D + "(?: (?:for|in) " + PATIENT + ")?",
			// "Should she avoid ibuprofen?", "Does she need INR monitoring if I add ibuprofen?", "Is she at risk of bleeding if
			// I add ibuprofen?", "What happens if I give her ibuprofen?" (ADR Decision 251)
			"(?:should|must) (?:she|he|they|the patient|this patient) avoid (?:taking )?" + D + "(?: now| today)?",
			"(?:does|do|will|would) (?:she|he|they|the patient|this patient) need (?:any )?(?:[a-z]+ )?monitoring (?:if|when) (?:i|we)"
					+ " (?:add|give|start|prescribe) (?:" + PATIENT + " )?(?:on )?" + D,
			"(?:is|are|will|would) (?:she|he|they|the patient|this patient) (?:be )?at (?:any )?risk(?: of [a-z]+)? (?:if|when) (?:i|we)"
					+ " (?:add|give|start|prescribe) (?:" + PATIENT + " )?(?:on )?" + D,
			"what (?:happens|would happen|will happen) if (?:i|we) (?:give|add|start|prescribe) (?:" + PATIENT + " )?(?:on )?" + D
					+ "(?: (?:to|for) " + PATIENT + ")?",
			// "Any problems if I start her on ibuprofen?", "Are there any issues if I give her ibuprofen?" (ADR Decision 299)
			"(?:(?:is|are) there )?any (?:problems?|issues?|concerns?|risks?) if (?:i|we) (?:give|start|prescribe|add) (?:"
					+ PATIENT + " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT + ")?",
			// "Any problem giving her ibuprofen?", "Are there any concerns with starting ibuprofen?"
			"(?:(?:is|are) there )?any (?:problems?|issues?|concerns?) (?:with )?(?:giving|starting|prescribing|adding) (?:"
					+ PATIENT + " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT + ")?",
			// "Is it risky to give her ibuprofen?", "Is it wrong to give her ibuprofen?"
			"is it (?:risky|dangerous|unsafe|wrong|harmful|a problem) to (?:give|start|prescribe|administer|add) (?:" + PATIENT
					+ " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT + ")?(?: now| today)?",
			// "Is ibuprofen dangerous for her?", "Could ibuprofen cause problems?", "Will giving her ibuprofen cause problems?",
			// "Is there any harm in giving her ibuprofen?", "Any reason to avoid ibuprofen?" (ADR Decision 255)
			"is " + D + " (?:dangerous|risky|unsafe|harmful|a problem|problematic)(?: (?:for|in) " + PATIENT + ")?(?: now| today)?",
			"(?:could|would|will|might|can) " + D + " cause (?:any )?(?:problems?|issues?|harm)(?: (?:for|in) " + PATIENT + ")?",
			"(?:could|would|will|might) giving (?:" + PATIENT + " )?" + D + " cause (?:any )?(?:problems?|issues?|harm)",
			"is there (?:any |a )?(?:harm|risk|danger|problem|issue|contraindication) (?:in|with|to) (?:giving|starting|prescribing"
					+ "|adding) (?:" + PATIENT + " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT + ")?",
			"(?:is there )?any reason to avoid " + D + "(?: (?:for|in) " + PATIENT + ")?");

	/**
	 * Whether a question asks whether NOT to give one drug, and nothing else — one of {@link #NEGATED_PROPOSAL_SHAPES}
	 * (ADR Decision 181). Every such question is also one {@link #asksWhetherToGiveADrug} admits; this says only that a
	 * "No" would answer it backwards.
	 */
	public static boolean asksWhetherNotToGiveADrug(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, NEGATED_PROPOSAL_SHAPES);
	}

	/**
	 * The question shapes asking whether ONE drug interacts with her medications — ADR Decision 181. Not a proposal: it
	 * asks whether there is an interaction, which "Yes" answers where the check relates one. The model answered it
	 * <em>"No — X should not be given: it interacts with …"</em> on the 3.7.1 standalone.
	 */
	private static final List<Pattern> INTERACTS_WITH_HER_MEDICATIONS_SHAPES = shapes(
			// "Does ibuprofen interact with her medications?", "Will ibuprofen interact with any of her current meds?", and the
			// drug named as hers: "Does her lopinavir interact with her other medications?" (ADR Decision 292)
			"(?:does|will|would|could|can) (?:" + POSSESSIVE + " )?" + D + " interact with (?:any of )?" + POSSESSIVE
					+ " (?:other )?" + MEDICATIONS,
			// "Any interactions if I give her ibuprofen?", "Are there any interactions if I start her on ibuprofen?" — the
			// model answered it "No — X should not be given: it interacts with …" (ADR Decision 181)
			"(?:are there )?any (?:drug )?interactions if (?:i|we) (?:give|start|prescribe|add) (?:" + PATIENT + " )?(?:on )?"
					+ D + "(?: to " + PATIENT + ")?",
			// "Is there an interaction if I give her ibuprofen?" — answered "No — X should not be given: it interacts with …"
			// (ADR Decision 256)
			"is there (?:an |any )?(?:drug )?interactions? if (?:i|we) (?:give|start|prescribe|add) (?:" + PATIENT + " )?(?:on )?"
					+ D + "(?: to " + PATIENT + ")?");

	/**
	 * Whether a question asks whether ONE drug interacts with her medications, and nothing else — one of {@link
	 * #INTERACTS_WITH_HER_MEDICATIONS_SHAPES} (ADR Decision 181). Fail-CLOSED as {@link #asksWhetherToGiveADrug} is.
	 */
	public static boolean asksWhetherADrugInteractsWithHerMedications(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, INTERACTS_WITH_HER_MEDICATIONS_SHAPES)
				|| fitsAShape(wordsWithTheDrugMarked, BESIDE_A_GROUP_OF_HER_MEDICATIONS_SHAPES);
	}

	/** A drug slot of the two-drug shapes below, which may name the drug as hers — "her methotrexate" (ADR Decision 233):
	 *  a "her" fitted none of them, and the model answered "No — Methotrexate and Salicylic acid interact". The together
	 *  shapes' slots too (ADR Decision 246): "Is fluconazole safe with his warfarin?" fitted none of them. */
	private static final String HER_D = "(?:" + POSSESSIVE + " )?" + D;

	/** Several drug slots after "with": "with her nevirapine and lopinavir" (ADR Decision 263). */
	private static final String HER_DS = HER_D + "(?:(?: and)? " + HER_D + ")*";

	/**
	 * The question shapes asking whether TWO drugs may be given together, over {@link #words} with each drug's name
	 * marked {@link #DRUG_NAME} — ADR Decision 169. A grammar for the reason {@link #PROPOSAL_SHAPES} is, and kept apart
	 * from it, since that one's readers answer for ONE drug proposed: its one reader states the findings about either
	 * drug against her own orders. "Does warfarin interact with ibuprofen?" asks nothing about giving, and is not
	 * admitted.
	 */
	private static final List<Pattern> TOGETHER_SHAPES = shapes(
			// "Can warfarin and ibuprofen be given together?", "Can warfarin be given with ibuprofen to her?"
			"(?:can|could|may|should) " + HER_D + " (?:and|with) " + HER_D
					+ " be (?:safely )?(?:given|started|prescribed|administered|taken|used)(?: together)?(?: (?:to|for) "
					+ PATIENT + ")?(?: now| today)?",
			"(?:can|could|may|should) " + HER_D + " be (?:safely )?(?:given|started|prescribed|administered|taken|used) (?:with|alongside) "
					+ HER_DS + "(?: (?:to|for) " + PATIENT + ")?(?: now| today)?",
			// "Can I give her warfarin with ibuprofen?", "Can I give her warfarin and ibuprofen together?"
			"(?:can|could|may|should) (?:i|we) (?:safely )?(?:give|start|prescribe|administer) (?:" + PATIENT
					+ " )?(?:on )?" + HER_D + " (?:and|with|alongside) " + HER_DS + "(?: together)?(?: to " + PATIENT
					+ ")?(?: now| today)?",
			// "Can she take warfarin and ibuprofen together?", "Can she drink alcohol while on metronidazole?", "Can she have
			// alcohol with her metronidazole?" (ADR Decision 268)
			"(?:can|could|may|should) (?:she|he|they|the patient|this patient) (?:safely )?(?:take|be given|drink|have) " + HER_D
					+ " (?:and|with|alongside|while on|while taking) " + HER_DS + "(?: together)?(?: now| today)?",
			// "Is it safe to give her warfarin and ibuprofen together?", "Is it safe to take warfarin with ibuprofen?", "Is it OK
			// to co-prescribe warfarin with her ibuprofen?" (ADR Decision 250)
			"is it (?:safe|ok|okay|appropriate) (?:for " + PATIENT + " )?to (?:(?:give|start|prescribe|co prescribe|administer) (?:"
					+ PATIENT + " )?(?:on )?|take )" + HER_D + " (?:and|with|alongside) " + HER_DS + "(?: together)?(?: for "
					+ PATIENT + ")?(?: now| today)?",
			// "Are warfarin and ibuprofen safe together?", "Is warfarin safe with ibuprofen?"
			"are " + HER_D + " and " + HER_D + " (?:safe|ok|okay|appropriate)(?: to (?:give|take|use))?(?: together)?(?: for "
					+ PATIENT + ")?",
			"is " + HER_D + " (?:safe|ok|okay|appropriate) (?:with|alongside) " + HER_DS + "(?: for " + PATIENT + ")?");

	/**
	 * Whether a question asks whether two drugs may be given together and nothing else — one of
	 * {@link #TOGETHER_SHAPES}, asked of its {@link #words} with each drug's name marked {@link #DRUG_NAME}. ADR
	 * Decision 169. Fail-CLOSED as {@link #asksWhetherToGiveADrug} is: a phrasing it misses keeps the answer as the
	 * model wrote it.
	 */
	public static boolean asksWhetherToGiveDrugsTogether(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, TOGETHER_SHAPES);
	}

	/**
	 * The question shapes a request to screen the patient's OWN medications against each other may
	 * take — issue #469. A grammar for the reason {@link #PROPOSAL_SHAPES} is: the word list it
	 * replaced admitted "Does this drug interact with her medications?", every word of which was on
	 * it, and answered it with her own pairs.
	 */
	/**
	 * A group of her OWN medications as a clinician words it, closing on a noun of medication — "her TB treatment", "her
	 * ARVs", "her blood pressure pills" (ADR Decision 221). Never resolved to drugs: a question asking whether two such
	 * groups interact is a screen of her medications, which states every pair, those between the two among them.
	 */
	private static final String HER_MEDICATION_GROUP = POSSESSIVE + " (?:\\p{L}+ ){0,2}(?:treatments?|therapy|therapies|"
			+ "regimens?|medications?|meds|medicines?|drugs?|pills|tablets|arvs?|antiretrovirals?|antibiotics?|antimalarials?|"
			+ "antihypertensives?|antiepileptics?|anticonvulsants?|antidepressants?)";

	/** "Does her TB treatment interact with her ARVs?", "Do her TB drugs interact with her antiretrovirals?" (ADR Decision 221). */
	private static final String TWO_OF_HER_MEDICATION_GROUPS_INTERACT = "(?:does|do|will|would) " + HER_MEDICATION_GROUP
			+ " interact with " + HER_MEDICATION_GROUP;

	/** "Is it safe to continue all her ARVs together?", "Are her ARVs safe together?" — whether a group of her own
	 *  medications is safe together, a screen of them (ADR Decision 254). */
	private static final String HER_MEDICATION_GROUP_SAFE_TOGETHER = "(?:is it (?:safe|ok|okay|fine|appropriate) to (?:continue"
			+ "|give|take|keep|use|combine) (?:all )?(?:of )?" + HER_MEDICATION_GROUP + "|are (?:all )?(?:of )?" + HER_MEDICATION_GROUP
			+ " (?:safe|ok|okay|fine)(?: to (?:take|give|continue))?) together";

	/**
	 * One drug asked about beside a GROUP of her medications — "Does fluconazole interact with her TB drugs?", "Is there any
	 * problem combining her TB drugs with fluconazole?" — asked as whether it interacts with her medications (ADR Decision
	 * 271), whose answer states every finding about it against her orders, the group's among them. The group names no drug,
	 * so no shape held such a question and the model's "No —" lead read it backwards.
	 */
	private static final List<Pattern> BESIDE_A_GROUP_OF_HER_MEDICATIONS_SHAPES = shapes(
			"(?:does|will|would|could|can) " + D + " interact with (?:any of )?" + HER_MEDICATION_GROUP,
			"(?:(?:is|are) there )?any (?:problems?|issues?|concerns?|interactions?) (?:with |in )?(?:combining|mixing) "
					+ HER_MEDICATION_GROUP + " (?:with|and) " + D,
			"(?:(?:is|are) there )?any (?:problems?|issues?|concerns?|interactions?) (?:with |in )?(?:combining|mixing|giving|"
					+ "starting|adding|taking) " + D + " (?:with|to|and|alongside) " + HER_MEDICATION_GROUP);

	/** The screen shapes naming no medication word the trigger's own cues know (ADR Decisions 221, 254). */
	private static final List<Pattern> MEDICATION_GROUPS_SHAPES = shapes(TWO_OF_HER_MEDICATION_GROUPS_INTERACT,
			HER_MEDICATION_GROUP_SAFE_TOGETHER);

	private static final List<Pattern> SCREEN_SHAPES = shapes(
			TWO_OF_HER_MEDICATION_GROUPS_INTERACT, HER_MEDICATION_GROUP_SAFE_TOGETHER,
			// "Are there any drug interactions with her current medications?"
			"(?:are|is) there (?:any )?(?:drug )?interactions? (?:with|between|among) " + POSSESSIVE + " " + MEDICATIONS,
			// "Are any of her current medications interacting with each other?", "Do any of her meds
			// interact?", "Do her medications interact with each other?"
			"(?:are|do) (?:any of )?" + POSSESSIVE + " " + MEDICATIONS + " (?:interact|interacting)"
					+ "(?: with (?:each other|one another))?",
			// "Does she have any drug interactions I should know about?"
			"(?:does|do) (?:she|he|they|the patient|this patient) have any (?:drug )?(?:interactions?|ddis?)"
					+ "(?: (?:between|among) " + POSSESSIVE + " " + MEDICATIONS + ")?" + KNOW_ABOUT,
			// "Any DDIs?", "Any drug interactions I should worry about?", "Any DDIs with her current medications?", "Any
			// clinically significant interactions?", "drug interactions?"
			"(?:are there )?(?:any )?" + QUALIFIER + "(?:drug drug |drug |medication )?(?:interactions|interaction alerts|ddis?)"
					+ "(?: (?:with|between|among) (?:" + POSSESSIVE + " " + MEDICATIONS + "|" + POSSESSIVE + " current treatment"
					+ "|the (?:drugs|medicines|medications) " + SUBJECT_TAKES + "))?" + KNOW_ABOUT,
			// "Is she on any interacting drugs?", "Is anything she takes interacting?", "What are her drug interactions?"
			"is (?:she|he|they|the patient|this patient) on any interacting (?:drugs|medications|medicines|meds)",
			"is anything " + SUBJECT_TAKES + " interacting",
			"what are " + POSSESSIVE + " (?:drug )?interactions",
			// "Are any of her drugs contraindicated together?", "Can her medications be taken together?", "Do her meds clash?"
			"are any of " + POSSESSIVE + " " + MEDICATIONS + " contraindicated (?:together|with each other)",
			"can " + POSSESSIVE + " " + MEDICATIONS + " be (?:taken|given|used) together",
			"do " + POSSESSIVE + " " + MEDICATIONS + " clash(?: with (?:each other|one another))?",
			// "Check for drug interactions", "Screen for DDIs", "Interaction check" (ADR Decision 180)
			"(?:please )?(?:check|screen) for (?:any )?" + QUALIFIER + "(?:drug )?(?:interactions|ddis)(?: please)?",
			"(?:drug |medication )?interactions? (?:check|review|screen)",
			// "Any harmful drug combinations?", "Is there a problem with her medication combination?"
			"(?:are there )?any (?:harmful|dangerous|unsafe|bad) (?:drug |medication )?combinations",
			"is there (?:a|any) problem with " + POSSESSIVE + " (?:drug|medication) combination",
			// "What drug interactions should I watch for?"
			"what (?:drug )?interactions should i (?:watch|look out|look) for",
			// "Are there any contraindications among her medicines?", "Is her regimen safe?", "Is it safe to continue
			// all her current medications?"
			"(?:are|is) there (?:any )?contraindications? (?:with|between|among) " + POSSESSIVE + " " + MEDICATIONS,
			"is " + POSSESSIVE + " (?:\\p{L}+ )?regimen (?:safe|ok|okay)",
			"is it safe to continue (?:all )?" + POSSESSIVE + " " + MEDICATIONS,
			// "What interactions does she have?", "What drug interactions does the patient have?"
			"what " + QUALIFIER + "(?:drug )?(?:interactions|ddis) (?:does|do) (?:she|he|they|the patient|this patient) have",
			// "List her drug interactions"
			"(?:please )?list " + POSSESSIVE + " " + QUALIFIER + "(?:drug )?(?:interactions|ddis)",
			// "Check her medications for interactions", "Review her meds for drug interactions", "Please screen her current
			// medications for drug interactions." (issue #337's own question) — an imperative may open with "please"
			"(?:please )?(?:check|review|screen) " + POSSESSIVE + " " + MEDICATIONS + " for (?:any )?" + QUALIFIER
					+ "(?:drug )?(?:interactions|ddis)(?: please)?",
			// "Are her medicines okay together?", "Are her medications compatible with each other?"
			"are " + POSSESSIVE + " " + MEDICATIONS + " (?:safe|ok|okay|fine|alright|compatible) (?:together|with each other)");

	private static final String SUBJECT = "(?:she|he|they|the patient|this patient)";

	private static final String BEFORE = "(?: before| in the past| previously)?";

	/**
	 * The question shapes asking whether the patient has EVER taken one drug, over {@link #words} with the drug's name
	 * marked {@link #DRUG_NAME} — ADR Decision 151. A grammar for the reason {@link #PROPOSAL_SHAPES} is, and
	 * past-tense only: "Does she take fluconazole?" asks about now, and is not admitted.
	 */
	private static final List<Pattern> HISTORY_SHAPES = shapes(
			// "Has she ever taken fluconazole?", "Has the patient been on warfarin before?"
			"(?:has|have) " + SUBJECT + " (?:ever )?(?:taken|used|had|received|been on|been given|been prescribed|been "
					+ "started on) " + D + BEFORE,
			// "Did she ever take fluconazole?", "Did the patient receive warfarin in the past?"
			"did " + SUBJECT + " (?:ever )?(?:take|use|have|receive|get) " + D + BEFORE,
			// "Was she ever on fluconazole?", "Was the patient ever prescribed warfarin?"
			"(?:was|were) " + SUBJECT + " (?:ever )?(?:on|given|prescribed|started on) " + D + BEFORE,
			// "Has fluconazole ever been prescribed for her?", "Was warfarin ever given to the patient?"
			"(?:has|was) " + D + " (?:ever )?(?:been )?(?:given|prescribed|ordered|administered|used)(?: (?:to|for) "
					+ PATIENT + ")?" + BEFORE);

	/**
	 * The question shapes asking whether the patient is ON one drug now, over {@link #words} with the drug's name marked
	 * {@link #DRUG_NAME} — ADR Decision 202. The present tense {@link #HISTORY_SHAPES} leaves out: "Is she on
	 * prednisone?" asks whether the drug is one of her active medications, which her orders answer. A grammar, and
	 * fail-CLOSED, for the reason {@link #PROPOSAL_SHAPES} is: a phrasing it misses keeps the model call it always had.
	 */
	private static final List<Pattern> PRESENCE_SHAPES = shapes(
			// "Is she on prednisone?", "Is the patient currently taking ibuprofen?", "Is he still on warfarin?"
			"(?:is|are) " + SUBJECT + " (?:currently |still |now )?(?:on|taking|using|receiving|prescribed) " + D
					+ "(?: now| currently| at the moment| at present)?",
			// "Does she take prednisone?", "Does the patient still use ibuprofen?"
			"(?:does|do) " + SUBJECT + " (?:currently |still )?(?:take|use|receive) " + D + "(?: now| currently)?",
			// "Is prednisone one of her medications?", "Is ibuprofen in her current meds?", "Is ibuprofen on her medication list?"
			"is " + D + " (?:one of " + POSSESSIVE + " " + MEDICATIONS + "|in " + POSSESSIVE + " " + MEDICATIONS + "|on "
					+ POSSESSIVE + " (?:medication|med|drug) list)");

	/**
	 * Whether a question asks whether the patient is on ONE drug now, and nothing else — one of {@link #PRESENCE_SHAPES}
	 * (ADR Decision 202). Fail-CLOSED as {@link #asksWhetherToGiveADrug} is.
	 */
	public static boolean asksWhetherSheIsOnADrug(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, PRESENCE_SHAPES);
	}

	/**
	 * The question shapes asking about ONE drug beside her other medications — whether it is safe with them, which of
	 * them it interacts with — over {@link #words} with the drug's name marked {@link #DRUG_NAME} (ADR Decision 202).
	 * Kept out of {@link #PROPOSAL_SHAPES} and {@link #INTERACTS_WITH_HER_MEDICATIONS_SHAPES} deliberately: the first
	 * also finds the drug SLOT an unrecognised word sits in (ADR Decision 183), and "Is grapefruit juice safe with her
	 * medications?" is the model's to answer; the second's answer opens "Yes —", which does not answer "which". Its one
	 * reader asks it of a drug her own orders carry.
	 */
	/** "Any interactions with her metronidazole?" — the interactions or problems of a drug the question names as hers (ADR
	 *  Decision 237). */
	private static final String ANY_INTERACTIONS_WITH_HER_D = "(?:(?:are|is) there )?any (?:drug )?(?:interactions?|problems?"
			+ "|issues?|concerns?) with " + POSSESSIVE + " " + D;

	private static final List<Pattern> ANY_INTERACTIONS_WITH_HER_D_SHAPES = shapes(ANY_INTERACTIONS_WITH_HER_D);

	/** "Is lidocaine safe with her other medications?", "Is ibuprofen okay with her current meds?" */
	private static final String SAFE_WITH_HER_MEDICATIONS = "is " + D + " (?:safe|ok|okay|appropriate) (?:with|alongside) "
			+ "(?:any of )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS;

	private static final List<Pattern> SAFE_WITH_HER_MEDICATIONS_SHAPES = shapes(SAFE_WITH_HER_MEDICATIONS);

	/**
	 * Whether a question asks whether its one drug is safe with her medications — <em>"Is ibuprofen safe with her current
	 * medications?"</em> — over {@link #words} with the drug marked (ADR Decision 299). Asked by
	 * {@code DrugReferenceInjector.questionProposes} of a drug the data RESOLVED, so a drug she is not on is answered as
	 * the proposal it is; kept out of {@link #PROPOSAL_SHAPES} itself for Decision 202's reason, that grammar also finding
	 * the slot an unrecognised word sits in.
	 */
	public static boolean asksWhetherADrugIsSafeWithHerMedications(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, SAFE_WITH_HER_MEDICATIONS_SHAPES);
	}

	private static final List<Pattern> BESIDE_HER_MEDICATIONS_SHAPES = shapes(
			SAFE_WITH_HER_MEDICATIONS,
			// "Which of her medications interact with lopinavir/ritonavir?", "What other meds of hers interact with X?"
			"(?:which|what) (?:of )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS + " (?:interact|interacts) with " + D,
			// "Is rifampicin causing any interactions with her other drugs?", "Is X interacting with any of her medications?",
			// "Does X cause interactions with her other meds?" (ADR Decision 209)
			"is " + D + " (?:causing (?:any )?(?:drug )?interactions? with|interacting with) (?:any of )?" + POSSESSIVE
					+ " (?:other )?" + MEDICATIONS,
			"does " + D + " cause (?:any )?(?:drug )?interactions? with (?:any of )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS,
			// "Any interactions with her metronidazole?", "Are there any problems with his lisinopril?" — her drug named as
			// hers, the interactions or problems of it asked after (ADR Decision 237)
			ANY_INTERACTIONS_WITH_HER_D,
			// "Should I hold her metformin?", "Do I need to stop her ibuprofen?" (ADR Decision 259)
			"(?:should|must|do|can) (?:i|we) (?:need to )?(?:hold|stop|pause|suspend|discontinue|withhold) " + POSSESSIVE + " " + D
					+ "(?: now| today| for now)?");

	/**
	 * Whether a question asks after the interactions or problems of a drug it names as hers — {@link
	 * #ANY_INTERACTIONS_WITH_HER_D} (ADR Decisions 237, 260). Asked where the drug is NOT hers, so the answer can say she is
	 * not on it before it answers. Fail-CLOSED as {@link #asksWhetherToGiveADrug} is.
	 */
	public static boolean asksAboutADrugNamedAsHers(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, ANY_INTERACTIONS_WITH_HER_D_SHAPES);
	}

	/**
	 * Whether a question asks about ONE drug beside her other medications, and nothing else — one of {@link
	 * #BESIDE_HER_MEDICATIONS_SHAPES} (ADR Decision 202). Fail-CLOSED as {@link #asksWhetherToGiveADrug} is.
	 */
	public static boolean asksAboutADrugBesideHerMedications(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, BESIDE_HER_MEDICATIONS_SHAPES);
	}

	/**
	 * The question shapes asking about TWO drugs together beyond {@link #TOGETHER_SHAPES} — whether they interact,
	 * whether it is safe to combine them — over {@link #words} with each drug's name marked {@link #DRUG_NAME} (ADR
	 * Decision 203). Kept apart from {@link #TOGETHER_SHAPES} for that grammar's reason: its readers answer for drugs
	 * proposed, and this one's single reader asks it of two drugs her own orders BOTH carry.
	 */
	/** "Does ibuprofen interact with celecoxib?", "Do her ibuprofen and celecoxib interact?" — a question whether two drugs
	 *  INTERACT (ADR Decisions 203, 218, 233); and "Will fluconazole affect her warfarin?", which asks the same (ADR
	 *  Decision 248), as does "Do clarithromycin and simvastatin clash?" (ADR Decision 256). */
	private static final String DOES_ONE_INTERACT_WITH_THE_OTHER = "(?:(?:does|do|will|would|can) " + HER_D
			+ " (?:interact with|affect|clash with) " + HER_D + "|(?:do|will|would|can) " + HER_D + " and " + HER_D
			+ " (?:interact|clash)(?: with (?:each other|one another))?)";

	/** What a drug may change of another: its levels, its effect, its absorption (ADR Decisions 249, 251). */
	private static final String MEASURE = "(?:levels?|concentrations?|effects?|effectiveness|efficacy|toxicity|exposure"
			+ "|absorption|bioavailability|clearance|metabolism)";

	/**
	 * "Will fluconazole raise his warfarin levels?", "Does rifampicin reduce the effect of her nevirapine?", "Does
	 * metronidazole increase the bleeding risk with his warfarin?" — a question whether one drug CHANGES a particular effect
	 * of the other, which the data never states: it relates the pair, never the effect asked about (ADR Decision 249).
	 */
	private static final String DOES_ONE_CHANGE_THE_OTHER = "(?:does|do|will|would|can|could|might) " + HER_D
			+ " (?:increase|raise|elevate|boost|decrease|lower|reduce|change|alter|potentiate) (?:(?:the |" + POSSESSIVE + " )?"
			+ D + " " + MEASURE + "|(?:the )?(?:[a-z]+ )?(?:risk|" + MEASURE + ")(?: of [a-z]+)? (?:of|with|from|on|for) " + HER_D
			+ ")";

	/**
	 * What a drug does beside another, asked otherwise than as an effect (ADR Decision 251): whether to avoid the two
	 * together, what happens or what the risk is if they are combined, whether her drug's dose needs changing because of
	 * the other. Each answered as {@link #DOES_ONE_CHANGE_THE_OTHER} is, with neither "Yes" nor "No" — the model answered
	 * "Should he avoid codeine with his tramadol?" and "Should his warfarin dose be adjusted if I start fluconazole?" "No —".
	 */
	private static final String AVOID_ONE_WITH_THE_OTHER = "(?:(?:should|must) (?:i|we|she|he|they|the patient|this patient)"
			+ "|do (?:i|we) (?:need|have) to) avoid (?:giving |taking )?(?:" + PATIENT + " )?" + HER_D + " (?:with|alongside|and) "
			+ HER_D + "(?: together)?";

	private static final String WHAT_COMBINING_THEM_DOES = "(?:what (?:happens|would happen|will happen) if (?:i|we) (?:give|add"
			+ "|start|combine|prescribe) (?:" + PATIENT + " )?(?:on )?" + HER_D + " (?:to|with|and|alongside) " + HER_D
			+ "|what (?:is|are) the (?:risks?|dangers?) (?:of|in) (?:combining|giving|using|taking|adding) " + HER_D
			+ " (?:and|with|to) " + HER_D + ")(?: together)?";

	/** "Is clarithromycin dangerous with her simvastatin?", "Is it risky to combine X and Y?", "Could metronidazole cause
	 *  problems with his warfarin?", "Is the combination of warfarin and amiodarone dangerous?" — whether the two are
	 *  dangerous together, whose "No" would say they are not (ADR Decision 255). */
	private static final String DANGEROUS_TOGETHER = "(?:is " + HER_D + " (?:dangerous|risky|unsafe|harmful|contraindicated"
			+ "|a problem|problematic) (?:with|alongside|in combination with) " + HER_D + "|is it (?:dangerous|risky|unsafe|harmful"
			+ "|a problem) to (?:combine|give|take|use) " + HER_D + " (?:and|with) " + HER_D + "(?: together)?|(?:could|would|will"
			+ "|might|can) " + HER_D + " cause (?:any )?(?:problems?|issues?|harm) (?:with|for|alongside) " + HER_D + "|(?:would"
			+ "|will|could) " + HER_D + " be (?:harmful|dangerous|risky|unsafe|a problem) (?:with|alongside) " + HER_D
			+ "|(?:is|are) (?:the combination of )?" + HER_D + " (?:and|with) " + HER_D + " (?:a )?(?:dangerous|risky|unsafe"
			+ "|harmful)(?: combination)?)";

	/** "Do I need to stop her simvastatin before starting clarithromycin?", "Can she continue her simvastatin if I start
	 *  clarithromycin?" — whether to stop or keep her drug for another (ADR Decision 252). */
	private static final String STOP_OR_KEEP_ONE_FOR_THE_OTHER = "(?:(?:(?:do|will) (?:i|we) (?:need|have) to|should (?:i|we))"
			+ " (?:stop|hold|pause|suspend|discontinue) " + HER_D + " (?:before|when|while|if) (?:starting|giving|adding|prescribing"
			+ "|(?:i|we) (?:start|give|add|prescribe)) " + D + "|(?:can|could|should) (?:she|he|they|the patient|this patient)"
			+ " (?:continue|keep taking|stay on|remain on) " + HER_D + " (?:if|when|while) (?:i|we) (?:start|give|add|prescribe) "
			+ D + ")";

	/** "Does she need potassium monitoring on lisinopril and spironolactone?", "Does she need monitoring if I add
	 *  spironolactone to her lisinopril?" — whether the two need monitoring (ADR Decision 252). */
	private static final String MONITORING_ON_BOTH = "(?:does|do|will|would) (?:she|he|they|the patient|this patient) need"
			+ " (?:any )?(?:[a-z]+ )?monitoring (?:(?:on|with|for|while on) " + HER_D + " and " + HER_D + "(?: together)?|(?:if|when)"
			+ " (?:i|we) (?:add|give|start|prescribe) " + D + " (?:to|with) " + HER_D + ")";

	/** "Is his warfarin dose safe with his amiodarone?" — whether her drug's dose is safe beside another (ADR Decision 258). */
	private static final String DOSE_SAFE_WITH_THE_OTHER = "is " + POSSESSIVE + " " + D + " dose (?:still )?(?:safe|ok|okay"
			+ "|appropriate|fine) (?:with|alongside|beside|given) (?:the |" + POSSESSIVE + " )?" + D;

	private static final String DOSE_BECAUSE_OF_THE_OTHER = "should " + POSSESSIVE + " " + D + " dose be (?:reduced|lowered"
			+ "|decreased|increased|raised|adjusted|changed)(?: (?:because of|due to|with|for) (?:the |" + POSSESSIVE + " )?" + D
			+ "| if (?:i|we) (?:add|give|start|prescribe) " + D + ")";

	/** "Any interactions between ibuprofen and celecoxib?", "Is there an interaction between X and Y?" (ADR Decisions 203, 218). */
	private static final String ANY_INTERACTION_BETWEEN = "(?:are there |is there )?(?:any |an )?(?:drug )?interactions? between "
			+ HER_D + " and " + HER_D;

	private static final List<Pattern> TWO_DRUGS_SHAPES = shapes(
			// "Is it safe to combine ibuprofen and celecoxib?", "Is it ok to give ibuprofen with celecoxib together?"
			"is it (?:safe|ok|okay|appropriate) to (?:combine|give|take|use) " + HER_D + " (?:and|with) " + HER_D
					+ "(?: together)?",
			DOES_ONE_INTERACT_WITH_THE_OTHER,
			DOES_ONE_CHANGE_THE_OTHER, AVOID_ONE_WITH_THE_OTHER, WHAT_COMBINING_THEM_DOES, DOSE_BECAUSE_OF_THE_OTHER,
			STOP_OR_KEEP_ONE_FOR_THE_OTHER, MONITORING_ON_BOTH, DANGEROUS_TOGETHER, DOSE_SAFE_WITH_THE_OTHER,
			// "Is ibuprofen safe with celecoxib?", "Is simvastatin ok with clopidogrel?", "Is her ibuprofen safe with her
			// celecoxib?"
			"is " + HER_D + " (?:safe|ok|okay|compatible) (?:with|alongside) " + HER_D,
			ANY_INTERACTION_BETWEEN,
			// "Can she take ibuprofen and celecoxib together?", "Can she have prednisone and ibuprofen together?" (ADR
			// Decision 209), "Can the patient be on X and Y together?"
			"(?:can|could|may|should) " + SUBJECT + " (?:safely )?(?:take|have|use|receive|be on|be given) " + D
					+ " (?:and|with) " + D + "(?: together)?");

	/**
	 * Whether a question asks about TWO drugs together, and nothing else — one of {@link #TWO_DRUGS_SHAPES} or {@link
	 * #TOGETHER_SHAPES} (ADR Decision 203). Fail-CLOSED as {@link #asksWhetherToGiveADrug} is.
	 */
	public static boolean asksAboutTwoDrugsTogether(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, TWO_DRUGS_SHAPES) || asksWhetherToGiveDrugsTogether(wordsWithTheDrugsMarked);
	}

	private static final List<Pattern> TWO_DRUGS_INTERACT_SHAPES = shapes(DOES_ONE_INTERACT_WITH_THE_OTHER, ANY_INTERACTION_BETWEEN);

	/** "What is the mechanism of the interaction between simvastatin and clarithromycin?" — what relates the two, asked of
	 *  the interaction itself (ADR Decision 264). */
	private static final List<Pattern> ABOUT_THE_PAIRS_INTERACTION_SHAPES = shapes("(?:what|which) (?:is|are) the (?:mechanisms?"
			+ "|cause|reason|basis) (?:of|for|behind) (?:the )?(?:drug )?interactions? between " + HER_D + " and " + HER_D,
			// "What is the interaction between rifampicin and her lopinavir?", "What interactions are there between X and Y?",
			// "How do X and Y interact?", "How does X interact with her Y?" — what it is, asked without the word "mechanism"
			// (ADR Decision 289)
			"(?:what|which) (?:is|are) the (?:drug )?interactions? between " + HER_D + " and " + HER_D,
			"(?:what|which) (?:drug )?interactions? (?:is|are) there between " + HER_D + " and " + HER_D,
			"how (?:do|does|would|will) " + HER_D + " and " + HER_D + " interact(?: with each other)?",
			"how (?:do|does|would|will) " + HER_D + " interact with " + HER_D,
			// "Should I separate the doses of ciprofloxacin and her didanosine?" — the mechanism says whether spacing them
			// helps (ADR Decision 270).
			"(?:should|do|must|can) (?:i|we) (?:need to )?(?:separate|space|space out|stagger) (?:the )?(?:doses?|dosing"
					+ "|administration|timing) of " + HER_D + " (?:and|from) " + HER_D,
			"(?:should|must|do) " + HER_D + " and " + HER_D + " (?:need to )?be (?:given|taken) (?:apart|separately|at different times)");

	/**
	 * Whether a question asks what the interaction between two drugs IS — its mechanism, its cause, or simply what it is (ADR
	 * Decision 289) — and nothing else (ADR Decision 264). Its answer states the findings relating the two, each whole, and
	 * neither "Yes" nor "No". Fail-CLOSED as {@link #asksWhetherToGiveADrug} is.
	 */
	public static boolean asksAboutThePairsInteraction(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, ABOUT_THE_PAIRS_INTERACTION_SHAPES);
	}

	private static final List<Pattern> ONE_DRUG_CHANGES_THE_OTHER_SHAPES = shapes(DOES_ONE_CHANGE_THE_OTHER,
			AVOID_ONE_WITH_THE_OTHER, WHAT_COMBINING_THEM_DOES, DOSE_BECAUSE_OF_THE_OTHER, STOP_OR_KEEP_ONE_FOR_THE_OTHER,
			MONITORING_ON_BOTH, DANGEROUS_TOGETHER, DOSE_SAFE_WITH_THE_OTHER);

	/**
	 * Whether a question asks whether one of two drugs changes a particular effect of the other — {@link
	 * #DOES_ONE_CHANGE_THE_OTHER} (ADR Decision 249) — or what else it does beside it, whose "No" would read backwards
	 * ({@link #AVOID_ONE_WITH_THE_OTHER}, {@link #WHAT_COMBINING_THEM_DOES}, {@link #DOSE_BECAUSE_OF_THE_OTHER}; ADR
	 * Decision 251; {@link #STOP_OR_KEEP_ONE_FOR_THE_OTHER}, {@link #MONITORING_ON_BOTH}; ADR Decision 252; {@link
	 * #DANGEROUS_TOGETHER}, ADR Decision 255; {@link #DOSE_SAFE_WITH_THE_OTHER}, ADR Decision 258), and nothing else. Its answer states what relates the pair and neither "Yes" nor "No". Fail-CLOSED as
	 * {@link #asksWhetherToGiveADrug} is.
	 */
	public static boolean asksWhetherOneDrugChangesTheOther(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, ONE_DRUG_CHANGES_THE_OTHER_SHAPES);
	}

	/**
	 * One drug asked whether it changes an effect of a GROUP of her medications — "Will rifampicin reduce the effect of her
	 * ARVs?", "Does rifampicin affect her ARVs?", "Will rifampicin lower her ARV levels?" — answered as ADR Decision 249
	 * answers it of one of her drugs, with the findings and neither "Yes" nor "No" (ADR Decision 281): the data relates
	 * the drug to her orders and never states the effect asked about.
	 */
	private static final List<Pattern> CHANGES_A_GROUP_OF_HER_MEDICATIONS_SHAPES = shapes(
			"(?:does|do|will|would|can|could|might) " + D + " (?:increase|raise|elevate|boost|decrease|lower|reduce|change|alter"
					+ "|potentiate|affect) (?:the " + MEASURE + " of )?" + HER_MEDICATION_GROUP + "(?: " + MEASURE + ")?");

	/**
	 * Whether a question asks whether ONE drug changes an effect of a group of her medications, and nothing else — one of
	 * {@link #CHANGES_A_GROUP_OF_HER_MEDICATIONS_SHAPES} (ADR Decision 281). Fail-CLOSED as {@link #asksWhetherToGiveADrug}
	 * is.
	 */
	public static boolean asksWhetherADrugChangesHerMedications(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, CHANGES_A_GROUP_OF_HER_MEDICATIONS_SHAPES);
	}

	/**
	 * The question shapes asking whether to SWITCH one drug to another, the drug switched FROM named first (ADR Decision
	 * 219): "Can I switch her nevirapine to efavirenz?", "Can I switch her from nevirapine to efavirenz?", "Can I replace
	 * her nevirapine with efavirenz?", "Is it safe to switch nevirapine to efavirenz?"; and "swap" (ADR Decision 253).
	 */
	private static final List<Pattern> SWITCH_SHAPES = shapes(
			"(?:can|could|may|should) (?:i|we) (?:switch|change|swap) (?:" + PATIENT + " (?:from )?|" + POSSESSIVE + " |from )?" + D
					+ " (?:to|for) " + D + "(?: now| today)?",
			"(?:can|could|may|should) (?:i|we) replace (?:" + POSSESSIVE + " )?" + D + " (?:with|by) " + D + "(?: now| today)?",
			"is it (?:safe|ok|okay|appropriate) to (?:switch|change|swap) (?:" + PATIENT + " (?:from )?|" + POSSESSIVE + " |from )?" + D
					+ " (?:to|for) " + D,
			"is it (?:safe|ok|okay|appropriate) to replace (?:" + POSSESSIVE + " )?" + D + " (?:with|by) " + D);

	/**
	 * Whether a question asks whether to switch one drug to another, and nothing else — one of {@link #SWITCH_SHAPES}, the
	 * drug switched from named first (ADR Decision 219). Fail-CLOSED as {@link #asksWhetherToGiveADrug} is.
	 */
	public static boolean asksWhetherToSwitchADrug(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, SWITCH_SHAPES);
	}

	/** The tail naming the drug a switch replaces: "instead of nevirapine", "in place of her nevirapine". */
	private static final String INSTEAD_OF = " (?:instead of|in place of) (?:" + POSSESSIVE + " )?" + D + "(?: now| today)?";

	/**
	 * The question shapes asking whether to give one drug INSTEAD of another — the drug switched TO named first (ADR
	 * Decision 230): "Can I give her efavirenz instead of nevirapine?", "Can she take efavirenz instead of her
	 * nevirapine?", "Can I use efavirenz in place of nevirapine?", "Is it safe to give efavirenz instead of nevirapine?".
	 */
	private static final List<Pattern> SWITCH_TO_FIRST_SHAPES = shapes(
			"(?:can|could|may|should) (?:i|we) (?:give|use|start|prescribe|try) (?:" + PATIENT + " )?(?:on )?" + D + INSTEAD_OF,
			"(?:can|could|may|should) " + SUBJECT + " (?:take|have|use|receive|start|be on|be given|be started on) " + D
					+ INSTEAD_OF,
			"is it (?:safe|ok|okay|appropriate) to (?:give|use|start|prescribe) (?:" + PATIENT + " )?(?:on )?" + D + INSTEAD_OF);

	/**
	 * Whether a question asks whether to give one drug instead of another, and nothing else — one of {@link
	 * #SWITCH_TO_FIRST_SHAPES}, the drug switched TO named first (ADR Decision 230): {@link #asksWhetherToSwitchADrug}'s
	 * question with its two drugs the other way round. Fail-CLOSED as {@link #asksWhetherToGiveADrug} is.
	 */
	public static boolean asksWhetherToGiveADrugInsteadOfAnother(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, SWITCH_TO_FIRST_SHAPES);
	}

	/**
	 * Whether a question asks whether TWO drugs interact, and nothing else (ADR Decision 218) — a yes-or-no question about
	 * the pair, which a proposal's "No" lead answers backwards. Fail-CLOSED as {@link #asksWhetherToGiveADrug} is.
	 */
	public static boolean asksWhetherTwoDrugsInteract(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, TWO_DRUGS_INTERACT_SHAPES);
	}

	/**
	 * {@link #shapes}, each also admitting the drug proposed "as well" or "too" — beside her medications, which is what
	 * every proposal is screened against (ADR Decision 244). "Also" before the verb is in the shapes themselves.
	 */
	private static List<Pattern> besideHerMedications(String... shapes) {
		String[] besides = new String[shapes.length];
		for (int i = 0; i < shapes.length; i++) {
			besides[i] = "(?:" + shapes[i] + ")(?: as well| too)?";
		}
		return shapes(besides);
	}

	private static List<Pattern> shapes(String... shapes) {
		List<Pattern> patterns = new ArrayList<Pattern>(shapes.length);
		for (String shape : shapes) {
			patterns.add(Pattern.compile(shape));
		}
		return Collections.unmodifiableList(patterns);
	}

	/**
	 * The words a shape of {@link #PROPOSAL_SHAPES}, {@link #SCREEN_SHAPES} or {@link #HISTORY_SHAPES} can open with —
	 * the ones {@link #fitsAShape} restores a clipped first word to (ADR Decision 152).
	 */
	private static final List<String> LEADING_WORDS = Collections.unmodifiableList(Arrays.asList("are", "can", "could",
			"did", "do", "does", "has", "have", "is", "may", "should", "was", "were", "will", "would"));

	/**
	 * Whether {@code words} fit one of {@code shapes} — as written, or with a first word that lost its leading letters
	 * read as the {@link #LEADING_WORDS} word it is the end of (ADR Decision 152). <em>"s it safe to give
	 * metformin?"</em> fitted no shape and was left to the model, which answered "The records do not address the safety
	 * of giving Metformin." while the question with its "I" was the module's. The rest of the question must still fit a
	 * shape exactly, so the restored word adds only the word a shape already names: a word mistyped any other way —
	 * "Ts", "Cna" — is not guessed at, and every grammar stays fail-closed.
	 */
	/** An amount, as {@link #words} split it: "500 mg", "500mg", "1 g", "1 5 g" (1.5 g), "10 mg kg" (10 mg/kg), "2 tablets". */
	private static final String AMOUNT = "(?:\\d+ )*(?:\\d+ (?:mg|milligrams?|g|grams?|gm|mcg|micrograms?|ug|ml|mls|millilitres?|milliliters?"
			+ "|iu|units?|tablets?|tabs?|capsules?|caps?|drops?|puffs?)|\\d+(?:mg|g|gm|mcg|ug|ml|mls|iu))(?: (?:per )?kg)?";

	/** How often: "three times a day", "twice daily", "tds", "every 6 hours", "at night". */
	private static final String FREQUENCY = "(?:(?:once|twice|thrice|(?:one|two|three|four|\\d+) times?) (?:a|per|each|every) day"
			+ "|(?:once |twice )?daily|bd|bid|tds|tid|qds|qid|od|nocte|stat|prn|(?:every|each) (?:\\d+ )?(?:hours?|hrs?)"
			+ "|\\d+ hourly|q\\d+h|at night|in the morning)";

	/** A dose stated before the drug, "1 g of paracetamol", and after it, "amoxicillin 500 mg three times a day". */
	private static final Pattern DOSE_BEFORE_THE_DRUG = Pattern.compile("(?<=^| )" + AMOUNT + " of (" + D + ")");

	private static final Pattern DOSE_AFTER_THE_DRUG = Pattern.compile("(" + D + ") " + AMOUNT + "(?: " + FREQUENCY + ")?(?= |$)");

	/** {@code joined} without the dose stated beside its drug, or {@code null} where it states none (ADR Decision 269). */
	private static String withoutAStatedDose(String joined) {
		String without = DOSE_AFTER_THE_DRUG.matcher(DOSE_BEFORE_THE_DRUG.matcher(joined).replaceAll("$1")).replaceAll("$1");
		return without.equals(joined) ? null : without;
	}

	/**
	 * Whether the question, its drug marked {@link #DRUG_NAME}, states a dose beside it — "1 g of paracetamol",
	 * "amoxicillin 500 mg three times a day" (ADR Decision 269). The shapes read past it, so the findings the drug raises
	 * are raised; the findings weigh no dose, so the model answers such a question.
	 */
	public static boolean statesADose(List<String> wordsWithTheDrugMarked) {
		return wordsWithTheDrugMarked != null && withoutAStatedDose(String.join(" ", wordsWithTheDrugMarked)) != null;
	}

	private static boolean fitsAShape(List<String> words, List<Pattern> shapes) {
		if (words == null || words.isEmpty()) {
			return false;
		}
		if (fitsAsWritten(words, shapes)) {
			return true;
		}
		String first = words.get(0);
		for (String leading : LEADING_WORDS) {
			if (leading.length() > first.length() && leading.endsWith(first)) {
				List<String> restored = new ArrayList<String>(words);
				restored.set(0, leading);
				if (fitsAsWritten(restored, shapes)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean fitsAsWritten(List<String> words, List<Pattern> shapes) {
		String joined = String.join(" ", words);
		// A dose stated beside the drug is no part of the question's shape (ADR Decision 269): "Is 1 g of paracetamol safe
		// for him?" asks what "Is paracetamol safe for him?" asks, of a dose — which the answer then says it did not check.
		String withoutADose = withoutAStatedDose(joined);
		for (Pattern shape : shapes) {
			if (shape.matcher(joined).matches() || withoutADose != null && shape.matcher(withoutADose).matches()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a question proposes giving ONE drug and asks nothing else — asked of its {@link #words}
	 * with the drug's own name already marked {@link #DRUG_NAME}. Issue
	 * <a href="https://github.com/openmrs/openmrs-module-chartsearchai/issues/469">#469</a>: it bounds
	 * which proposals {@code DrugReferenceInjector} answers with a withholding "No", which is the
	 * polarity of every shape in {@link #PROPOSAL_SHAPES}.
	 *
	 * <p><b>A closed grammar, and fail-CLOSED, and the direction is the whole design.</b> A question
	 * fitting no shape keeps the model call it has always had, so a phrasing it misses costs nothing it
	 * did not cost before; only an ADMISSION can go wrong. That is why this may be a closed list where
	 * {@link #isInteractionScreening} had to be widened after a list MISSED screens (ADR Decision 89):
	 * there a miss hid a hazard, here a miss hides nothing.
	 *
	 * <p><b>Except to one reader</b> (issue #548): {@code DrugSafetyValidator.validate} states that a drug
	 * the question proposes is already in her orders only where this admits the question, so a phrasing
	 * it misses withholds that fact — which is how <em>"Is it safe to add prednisone for her?"</em> went
	 * untold until its shape was admitted. A miss there costs the statement, never a refusal. ADR Decision
	 * 129.
	 */
	public static boolean asksWhetherToGiveADrug(List<String> wordsWithTheDrugMarked) {
		if (fitsAShape(wordsWithTheDrugMarked, PROPOSAL_SHAPES) || asksWhetherNotToGiveADrug(wordsWithTheDrugMarked)) {
			return true;
		}
		List<String> proposal = withoutAPurpose(wordsWithTheDrugMarked);
		return proposal != null
				&& (fitsAShape(proposal, PROPOSAL_SHAPES) || asksWhetherNotToGiveADrug(proposal));
	}

	/**
	 * The purpose a proposal closes with — <em>"pain"</em>, <em>"her knee pain"</em> — where {@link #asksWhetherToGiveADrug}
	 * admits it only once that purpose is set aside, or {@code null}. The findings do not weigh what a drug is for, so an
	 * answer to such a proposal says so (ADR Decision 213).
	 */
	public static String statedPurpose(List<String> wordsWithTheDrugMarked) {
		if (fitsAShape(wordsWithTheDrugMarked, PROPOSAL_SHAPES) || asksWhetherNotToGiveADrug(wordsWithTheDrugMarked)) {
			return null;
		}
		List<String> proposal = withoutAPurpose(wordsWithTheDrugMarked);
		if (proposal == null || !fitsAShape(proposal, PROPOSAL_SHAPES) && !asksWhetherNotToGiveADrug(proposal)) {
			return null;
		}
		return String.join(" ", wordsWithTheDrugMarked.subList(proposal.size() + 1, wordsWithTheDrugMarked.size()));
	}

	/**
	 * {@link #statedPurpose} as {@code question} wrote it — "her UTI", where the shapes read "her uti" (ADR Decision 235):
	 * the same closing words, taken off the question's own text. Where those words cannot be lined up with the question,
	 * the purpose as the shapes read it.
	 */
	public static String statedPurposeAsWritten(String question, List<String> wordsWithTheDrugMarked) {
		String purpose = statedPurpose(wordsWithTheDrugMarked);
		if (purpose == null || question == null) {
			return purpose;
		}
		return asWritten(question, purpose);
	}

	/** {@code purpose}, the closing words of {@code question} as the shapes read them, as the question wrote them — or
	 *  {@code purpose} itself where the two cannot be lined up. */
	private static String asWritten(String question, String purpose) {
		List<String> written = new ArrayList<String>();
		Matcher matcher = WORD.matcher(question);
		while (matcher.find()) {
			written.add(matcher.group().replace('\u2019', '\''));
		}
		int k = purpose.split(" ").length;
		if (written.size() < k) {
			return purpose;
		}
		String asWritten = String.join(" ", written.subList(written.size() - k, written.size()));
		return asWritten.toLowerCase(Locale.ROOT).equals(purpose) ? asWritten : purpose;
	}

	/** Words a closing purpose may not carry: a second question joined to it, or a question of its own. */
	private static final Set<String> NOT_A_PURPOSE = new HashSet<String>(Arrays.asList("and", "or", "but", "also", "plus",
		"with", "while", "during", "if", "how", "what", "which", "when", "why", "who", "whether", "long", "much", "many"));

	/**
	 * {@code words} with a closing PURPOSE taken off — <em>"Can I prescribe ibuprofen for pain?"</em>, <em>"Can I give her
	 * mebendazole for her worms?"</em> — or {@code null} where they close with none the proposal grammar may set aside (ADR
	 * Decision 213). What a drug is for changes nothing the findings answer, and the module never answers it: its answer
	 * states what the check found and what it does not cover. So a purpose of at most four words after the last "for" is
	 * set aside, unless it is the patient alone (the shapes' own "for her"), or carries the drug, a word joining a second
	 * question or asking one of its own ({@link #NOT_A_PURPOSE}), or a factor the findings do not weigh
	 * ({@link #OTHER_FACTOR}) — each of which keeps the model call, as the grammar's fail-closed design has it.
	 */
	static List<String> withoutAPurpose(List<String> words) {
		int at = words.lastIndexOf("for");
		int length = words.size() - at - 1;
		if (at < 1 || length < 1 || length > 4) {
			return null;
		}
		List<String> purpose = words.subList(at + 1, words.size());
		if (String.join(" ", purpose).matches(PATIENT)) {
			return null;
		}
		for (String word : purpose) {
			if (DRUG_NAME.equals(word) || NOT_A_PURPOSE.contains(word) || OTHER_FACTOR.matcher(word).find()) {
				return null;
			}
		}
		return new ArrayList<String>(words.subList(0, at));
	}

	/**
	 * Whether a question asks whether the patient has EVER taken one drug, and nothing else — one of
	 * {@link #HISTORY_SHAPES}, asked of its {@link #words} with the drug's own name marked {@link #DRUG_NAME}. ADR
	 * Decision 151: such a response publishes no interaction chip about giving that drug. Fail-CLOSED as
	 * {@link #asksWhetherToGiveADrug} is: a phrasing it misses keeps the chips it always had.
	 */
	public static boolean asksWhetherSheHasTakenADrug(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, HISTORY_SHAPES);
	}

	/**
	 * Whether a question naming no drug asks to screen the patient's OWN medications against each other
	 * and nothing else — one of {@link #SCREEN_SHAPES}, for the reason {@link #asksWhetherToGiveADrug}
	 * gives. Issue #469. It does not ask {@link #isInteractionScreening} as well, and the reason is its
	 * one caller: {@code DrugReferenceInjector} admits a screen only where an interaction finding was
	 * raised for a question naming no drug, and only the screening arm, gated on that very predicate,
	 * raises one.
	 */
	public static boolean asksOnlyToScreenHerMedications(String question) {
		return fitsAShape(words(question), SCREEN_SHAPES) || asksWhichDrugToGiveHer(question)
				|| asksWhatToMonitorWithHerMedications(question) || asksWhichOfHerMedicationsAreHighRisk(question)
				|| asksWhetherHerMedicationsNeedChanging(question)
				|| question != null && isInteractionScreening(question)
						&& (MEDICATIONS_CUES.matcher(question).find() || DDI_CUES.matcher(question).find())
						&& (asksForAReadingBesidesAChange(question) || CHANGE_REQUEST.matcher(question).find())
						&& !asksForSomethingElseToo(question);
	}

	/**
	 * Whether a question naming no drug asks the module to CHOOSE one for her — <em>"Which pain medication is safe for
	 * her?"</em>, <em>"What painkiller can I safely give her?"</em>, <em>"What can I give her for pain?"</em> (ADR Decision
	 * 293). No finding chooses a drug or clears one, so the module answers with what its screen of her own medications
	 * found, under a lead saying it chooses none. A closed grammar, fail-CLOSED: a phrasing it misses keeps the model, and
	 * a question carrying a factor the findings do not weigh ({@link #OTHER_FACTOR}) or a second question does too. The
	 * thing chosen must be named a drug ({@link #DRUG_NOUN}), so "Which diet is best for her?" is not one.
	 */
	public static boolean asksWhichDrugToGiveHer(String question) {
		if (question == null || question.indexOf('?') != question.lastIndexOf('?')
				|| OTHER_FACTOR.matcher(question.toLowerCase(Locale.ROOT)).find()
				|| SECOND_QUESTION.matcher(question.toLowerCase(Locale.ROOT)).find()) {
			return false;
		}
		List<String> words = words(question);
		if (fitsAShape(words, CHOICE_SHAPES)) {
			return true;
		}
		List<String> choice = withoutAPurpose(words);
		return choice != null && fitsAShape(choice, CHOICE_SHAPES);
	}

	/**
	 * The purpose a question {@link #asksWhichDrugToGiveHer} closes with — "pain" in <em>"What can I give her for
	 * pain?"</em> — as the question wrote it, or {@code null} (ADR Decision 293, as Decision 213 for a proposal).
	 */
	public static String statedPurposeOfAChoice(String question) {
		List<String> words = words(question);
		if (!asksWhichDrugToGiveHer(question) || fitsAShape(words, CHOICE_SHAPES)) {
			return null;
		}
		List<String> choice = withoutAPurpose(words);
		if (choice == null) {
			return null;
		}
		return asWritten(question, String.join(" ", words.subList(choice.size() + 1, words.size())));
	}

	/**
	 * Whether a question asks whether she is allergic to any of the medications she takes, and nothing else — <em>"Is she
	 * allergic to any of her medications?"</em>, <em>"Is she on anything she is allergic to?"</em> (ADR Decision 295). Not
	 * <em>"Does she have any allergies?"</em>, which asks for her allergies. A closed grammar, fail-CLOSED.
	 */
	public static boolean asksWhetherAllergicToHerMedications(String question) {
		return question != null && question.indexOf('?') == question.lastIndexOf('?')
				&& fitsAShape(words(question), ALLERGIC_TO_HER_MEDICATIONS_SHAPES);
	}

	/**
	 * Whether a question asks what to monitor with the medications she takes, and nothing else — <em>"What should I
	 * monitor with her current medications?"</em> (ADR Decision 297). No finding sets monitoring, so the module answers
	 * with what its screen found, under a lead saying so. A closed grammar, fail-CLOSED: "What should I monitor for her
	 * blood pressure?" asks about something else.
	 */
	public static boolean asksWhatToMonitorWithHerMedications(String question) {
		return question != null && question.indexOf('?') == question.lastIndexOf('?')
				&& fitsAShape(words(question), MONITORING_SHAPES);
	}

	/**
	 * Whether a question asks whether she takes any drug of a CLASS, and nothing else — <em>"Is she on any NSAIDs?"</em>,
	 * <em>"Does she take any NSAIDs?"</em> (ADR Decision 298). The class is not read here: the caller asks
	 * {@code DrugReferenceService.namedDrugClass} which class the question names, and answers only for a group it carries.
	 */
	public static boolean asksWhetherSheIsOnADrugClass(String question) {
		return question != null && question.indexOf('?') == question.lastIndexOf('?')
				&& fitsAShape(words(question), ON_A_DRUG_CLASS_SHAPES);
	}

	private static final List<Pattern> ON_A_DRUG_CLASS_SHAPES = shapes(
			// "Is she on any NSAIDs?", "Is she currently taking an NSAID?"
			"(?:is|are) " + SUBJECT + " (?:currently |still )?(?:on|taking|receiving|using) (?:any|an?|some)(?: \\p{L}+){1,3}",
			// "Does she take any NSAIDs?"
			"(?:does|do) " + SUBJECT + " (?:currently |still )?(?:take|use|receive) (?:any|an?|some)(?: \\p{L}+){1,3}");

	private static final String HER_MEDICATIONS = POSSESSIVE + " " + MEDICATIONS;

	/**
	 * Whether a question asks which of her medications are high risk, and nothing else — <em>"Which of her medications are
	 * high risk?"</em> (ADR Decision 300). No finding rates a medication's risk, so the module answers with its screen under
	 * a lead saying so. A closed grammar, fail-CLOSED.
	 */
	public static boolean asksWhichOfHerMedicationsAreHighRisk(String question) {
		return question != null && question.indexOf('?') == question.lastIndexOf('?')
				&& fitsAShape(words(question), HIGH_RISK_SHAPES);
	}

	private static final String RISKY = "(?:high risk|higher risk|dangerous|risky|unsafe|concerning|a concern)";

	private static final List<Pattern> HIGH_RISK_SHAPES = shapes(
			// "Which of her medications are high risk?", "Which of her meds are dangerous?"
			"(?:which|what) (?:of )?" + HER_MEDICATIONS + " (?:are|is) " + RISKY,
			// "Are any of her medications high risk?"
			"(?:are|is) any of " + HER_MEDICATIONS + " " + RISKY);

	/**
	 * Whether a question asks whether any of her medications need changing — <em>"Does she need any of her medications
	 * adjusted?"</em> — the change {@link #CHANGE_REQUEST} asks in other words, which a screen informs (ADR Decision 300).
	 */
	public static boolean asksWhetherHerMedicationsNeedChanging(String question) {
		return question != null && question.indexOf('?') == question.lastIndexOf('?')
				&& fitsAShape(words(question), NEED_CHANGING_SHAPES);
	}

	private static final String CHANGED = "(?:adjusted|changed|stopped|modified|reviewed|adjusting|changing|stopping)";

	private static final List<Pattern> NEED_CHANGING_SHAPES = shapes(
			// "Does she need any of her medications adjusted?"
			"(?:does|do) " + SUBJECT + " need any of " + HER_MEDICATIONS + " " + CHANGED,
			// "Do any of her medications need to be changed?"
			"(?:do|does) any of " + HER_MEDICATIONS + " need (?:to be )?" + CHANGED);

	private static final List<Pattern> MONITORING_SHAPES = shapes(
			// "What should I monitor with her current medications?", "What do I need to monitor with her meds?"
			"what (?:should|do|must) (?:i|we) (?:need to )?monitor (?:with|for|on|in) " + HER_MEDICATIONS,
			// "What monitoring does she need for her medications?"
			"what monitoring (?:does|do|should) " + SUBJECT + " (?:need|have|get) (?:for|with|on) " + HER_MEDICATIONS,
			// "What needs to be monitored with her medications?"
			"what (?:needs|should|has) (?:to )?be monitored (?:with|for|on) " + HER_MEDICATIONS);

	private static final String ALLERGIC = "(?:allergic|(?:she|he|they)'s allergic|(?:she|he|they) (?:is|are) allergic)";

	private static final List<Pattern> ALLERGIC_TO_HER_MEDICATIONS_SHAPES = shapes(
			// "Is she allergic to any of her medications?", "Is she allergic to any of her current meds?"
			"(?:is|are) " + SUBJECT + " allergic to any of " + POSSESSIVE + " " + MEDICATIONS,
			// "Does she have an allergy to any of her medications?"
			"(?:does|do) " + SUBJECT + " have (?:an |any )?allerg(?:y|ies) to any of " + POSSESSIVE + " " + MEDICATIONS,
			// "Is she on anything she is allergic to?", "Is she taking any medication she's allergic to?"
			"(?:is|are) " + SUBJECT + " (?:on|taking|receiving) (?:anything|any (?:medications?|meds|medicines?|drugs?))"
					+ " (?:that )?" + ALLERGIC + " to",
			// "Are any of her medications contraindicated with her allergies?", "Do any of her medications conflict with her
			// allergies?" (ADR Decision 300)
			"(?:are|is) any of " + POSSESSIVE + " " + MEDICATIONS + " contraindicated (?:with|by|because of|due to|given) "
					+ POSSESSIVE + " (?:drug )?allerg(?:y|ies)",
			"(?:do|does) any of " + POSSESSIVE + " " + MEDICATIONS + " (?:conflict|clash) with " + POSSESSIVE
					+ " (?:drug )?allerg(?:y|ies)",
			// "Are any of her medications ones she is allergic to?"
			"(?:are|is) any of " + POSSESSIVE + " " + MEDICATIONS + " (?:ones? |something )?(?:that )?" + ALLERGIC + " to");

	/** A noun naming what is chosen as a drug: a medication word, or a class of drugs a clinician asks a choice of. */
	private static final String DRUG_NOUN = "(?:medications?|meds?|medicines?|drugs?|prescriptions?|painkillers?|pain killers?"
			+ "|pain relievers?|pain relief|analgesics?|antibiotics?|antibacterials?|antifungals?|antivirals?|nsaids?"
			+ "|opioids?|antihypertensives?|antidepressants?|antipsychotics?|anticoagulants?|antiplatelets?|statins?"
			+ "|antiemetics?|antihistamines?|antiepileptics?|anticonvulsants?|diuretics?|sedatives?|steroids?)";

	/** What is chosen: up to two words before a {@link #DRUG_NOUN}, or her own medications. */
	private static final String CHOSEN = "(?:of " + POSSESSIVE + " (?:current |active )?|(?:\\p{L}+ ){0,2})" + DRUG_NOUN;

	private static final String SAFE_WORD = "(?:safe|safest|safer|best|ok|okay|appropriate|suitable|preferred)";

	private static final String GIVE_WORD = "(?:give|prescribe|use|start|offer|recommend|add)";

	/** Who it is for, after the safety word: "for her", "to give her", "to use". */
	private static final String FOR_HER = "(?: (?:for " + PATIENT + "|to " + GIVE_WORD + "(?: (?:to )?" + PATIENT + ")?"
			+ "|(?:for|to) (?:her|his|their) (?:use|patient)))?";

	private static final List<Pattern> CHOICE_SHAPES = shapes(
			// "Which pain medication is safe for her?", "Which of her medications is safest?", "Which antibiotic would be
			// best to give her?"
			"(?:which|what) " + CHOSEN + " (?:is|are|would be|will be|might be|could be) (?:the )?(?:most )?" + SAFE_WORD
					+ "(?: (?:option|choice))?" + FOR_HER,
			// "What is the safest painkiller for her?", "Which is the best antibiotic to give her?"
			"(?:which|what) (?:is|are|would be) (?:the )?(?:most )?" + SAFE_WORD + " (?:\\p{L}+ ){0,2}" + DRUG_NOUN + FOR_HER,
			// "What painkiller can I safely give her?", "What can I give her?", "Which antibiotic should I use for her?"
			"(?:which|what) (?:" + CHOSEN + " )?(?:can|could|should|may) (?:i|we) (?:safely )?" + GIVE_WORD
					+ "(?: (?:to )?" + PATIENT + ")?(?: on)?(?: safely)?(?: (?:for|to) " + PATIENT + ")?",
			// "Is there a safer alternative to her current medications?", "Are there any better options for her meds?" (ADR
			// Decision 299)
			"(?:is|are) there (?:a |any )?(?:safer|better|safe) (?:alternatives?|options?) (?:to|for) " + POSSESSIVE + " "
					+ MEDICATIONS,
			// "What can she safely take?", "Which painkillers can he take?"
			"(?:which|what) (?:" + CHOSEN + " )?(?:can|could|should|may) (?:she|he|they|the patient|this patient)"
					+ " (?:safely )?(?:take|have|use|be given|be started on)(?: safely)?");

	/**
	 * Whether a question that asks for a screen of her medications ALSO asks for something the screen does not answer —
	 * so it keeps the model (ADR Decision 182). The screen grammar above admitted only the phrasings it listed, and two
	 * sweeps on the 3.7.1 standalone kept finding screens it had not listed, each left to the model; now a screen request
	 * ({@link #isInteractionScreening}) naming her medications is the module's unless one of these holds. Fail-closed:
	 * anything here keeps the model call the question always had.
	 * <ul>
	 * <li>A second question: two question marks, or one joined by "and"/"also" to a question word.</li>
	 * <li>A list of her medications asked for — "what/which/list/show … medications" not followed by "interact".</li>
	 * <li>Something OUTSIDE her chart interacting with her medications — "with her medications", "with another drug",
	 *     "Which drugs interact with her medications?" — whose answer is not a screen of her own orders.</li>
	 * <li>A dose, a food, alcohol, or a patient factor the findings do not weigh (pregnancy, kidneys, age …).</li>
	 * <li>Another record domain asked about besides her medications: allergies, conditions, visits, programs.</li>
	 * </ul>
	 */
	static boolean asksForSomethingElseToo(String question) {
		String q = question.toLowerCase(Locale.ROOT);
		if (q.indexOf('?') != q.lastIndexOf('?') || SECOND_QUESTION.matcher(q).find() || ENUMERATION.matcher(q).find()
				|| OUTSIDE_AGENT.matcher(q).find() || OTHER_FACTOR.matcher(q).find()) {
			return true;
		}
		Set<Intent> intents = matchedIntents(question);
		return intents.contains(Intent.ALLERGIES) || intents.contains(Intent.CONDITIONS) || intents.contains(Intent.VISITS)
				|| intents.contains(Intent.PROGRAMS);
	}

	/**
	 * Whether the question's drug-safety reading rests on more than a word of change — an interaction word, "DDI", or a
	 * safety or concern word. A change word alone asks for a screen only as {@link #CHANGE_REQUEST}: <em>"Should I stop any
	 * of her medications?"</em> does, <em>"Is there a change in her medications?"</em> and <em>"Should I stop all her
	 * medications?"</em> do not (ADR Decision 182).
	 */
	private static boolean asksForAReadingBesidesAChange(String question) {
		return INTERACTION_CUES.matcher(question).find() || INTERACTION_NOUN_CUES.matcher(question).find()
				|| DDI_CUES.matcher(question).find()
				|| MEDICATION_SAFETY_CUES.matcher(CHANGE_CUES.matcher(question).replaceAll(" ")).find();
	}

	/** The change family of {@link #MEDICATION_SAFETY_CUES}. */
	private static final Pattern CHANGE_CUES = cues("stop(?:ped|ping)?", "discontinue(?:d)?", "deprescribe(?:d)?",
		"change(?:d|s)?", "adjust(?:ed|ment|ments)?");

	private static final String MEDICATION_NOUN = "(?:medications?|meds|medicines?|drugs?|prescriptions?|regimens?)";

	/**
	 * "Should I stop any of her medications?" — a change asked about ANY of them, which is what a screen informs; and
	 * "Which of her medications should I stop?" or "… should be stopped?", the same change asked of WHICH of them (ADR
	 * Decision 241), which the model answered by choosing a drug the data does not choose.
	 */
	private static final Pattern CHANGE_REQUEST = Pattern.compile("\\b(?:should|do|must|can) (?:i|we) (?:need to )?(?:stop|"
			+ "change|discontinue|adjust|deprescribe|modify) (?:any|some)\\b|\\bwhich (?:of (?:her|his|their|the patient's|"
			+ "this patient's) )?(?:current |active )?" + MEDICATION_NOUN + " (?:(?:should|do|must|can) (?:i|we) (?:need to )?"
			+ "(?:stop|change|discontinue|adjust|deprescribe|modify)|(?:should|must|need to|ought to) (?:be )?(?:stopped|changed"
			+ "|discontinued|adjusted|deprescribed))\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern SECOND_QUESTION = Pattern.compile("\\b(?:and|also|plus)\\s+(?:what|which|when|where|who|how|is"
			+ "|are|does|do|did|has|have|can|could|should|list|show|tell)\\b");

	// "Which of her medications should be stopped because of interactions?" asks what to CHANGE, which a screen informs,
	// and is no request for her list (ADR Decision 207): a change asked of the medications the noun names ends it, in
	// either voice — "should I stop" as well (ADR Decision 241).
	private static final Pattern ENUMERATION = Pattern.compile("\\b(?:what|which|list|show|name|tell me)\\b(?:(?!\\binteract)"
			+ "[^?.])*\\b" + MEDICATION_NOUN + "\\b(?!\\s+(?:interact|interacting|interactions?|have any interactions|clash"
			+ "|(?:should|must|need to|ought to) (?:be )?(?:stopped|changed|discontinued|adjusted|deprescribed|avoided)"
			+ "|(?:should|must|do|can) (?:i|we) (?:need to )?(?:stop|change|discontinue|adjust|deprescribe|modify|avoid)))");

	private static final Pattern OUTSIDE_AGENT = Pattern.compile("\\b(?:interact\\w*|safe|ok|okay|fine|compatible|combined|"
			+ "mixed|taken|given) with (?:(?:any of )?(?:her|his|their|the patient's|this patient's) (?:current |active )?"
			+ MEDICATION_NOUN + "|(?!each other|one another|the patient|this patient)(?:another|other|an?|any|some|this|that|the)"
			+ " \\w+)");

	private static final Pattern OTHER_FACTOR = Pattern.compile("\\b(?:safe (?:medications|meds|medicines|drugs)|doses?|dosage|dosing|how much|mg|levels?|food|foods|"
			+ "grapefruit|alcohol|pregnan\\w*|breastfeed\\w*|lactat\\w*|kidneys?|renal|liver|hepatic|heart|cardiac|elderly|"
			+ "age|weight|surgery|anaesthe\\w*|anesthe\\w*)\\b");

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
	/**
	 * Whether the question asks about drug INTERACTIONS and nothing a contraindication answers: an interaction cue
	 * ({@link #DDI_CUES}, {@link #INTERACTION_CUES}), no safety-or-change cue ({@link #MEDICATION_SAFETY_CUES} —
	 * "safe", "contraindicated", "problems", "stop" and the rest), and no allergy or condition domain. <em>"Does she
	 * have any drug interactions?"</em> is one; <em>"Are her medications safe to take together?"</em> and <em>"Do her
	 * medicines interact with her allergies?"</em> are not. A screen answering one states her interactions and draws her
	 * contraindications apart (ADR Decision 190).
	 */
	public static boolean asksOnlyAboutInteractions(String question) {
		return question != null && (DDI_CUES.matcher(question).find() || INTERACTION_CUES.matcher(question).find())
				&& !MEDICATION_SAFETY_CUES.matcher(question).find() && !asksAboutAllergies(question)
				&& !asksAboutConditions(question);
	}

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
	 *  module declares that type and other production sites read it — mutate the constant and read
	 *  the compiler, rather than trusting a count here, which has already gone stale once. The rest
	 *  are querystore contract strings written as literals, whatever this module declares elsewhere;
	 *  declaring one is a reference-group decision
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
