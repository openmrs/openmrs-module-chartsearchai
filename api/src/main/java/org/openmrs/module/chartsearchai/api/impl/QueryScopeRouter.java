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
	 * whether a screen runs. The PLURAL in any case: no drug is named "ddIs", and <em>"any ddis"</em> typed in lower case
	 * ran no screen, so the model told a patient whose orders relate in seven interactions "No records of ddis are
	 * recorded." (ADR Decision 340).
	 */
	private static final Pattern DDI_CUES = Pattern.compile("\\bDDI[sS]?\\b|(?i:\\bddis\\b)");

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
				|| asksWhichOfHerMedicationsAreHighRisk(question) || asksWhetherHerMedicationsNeedChanging(question)
				// And which drugs to avoid in her (ADR Decision 324).
				|| asksWhichDrugsToAvoid(question)
				// And about a drug named as hers, by whatever name — "Is his clarithromycine safe?", a brand the cues do not
				// know (ADR Decision 374).
				|| asksAboutADrugNamedAsHersByAnyName(question)
				// And any question a screen shape reads, whatever cue it lacks — "Any contraindicated combinations?" (ADR
				// Decision 412).
				|| fitsAShape(words(question), SCREEN_SHAPES));
	}

	/** Whether {@code question} asks about one word named as hers as about her drug beside her medications — {@link
	 *  #BESIDE_HER_MEDICATIONS_SHAPES} with the word after "her" marked a drug (ADR Decision 374). */
	public static boolean asksAboutADrugNamedAsHersByAnyName(String question) {
		List<String> words = words(question);
		for (int i = 0; i + 1 < words.size(); i++) {
			if (!words.get(i).matches("her|his|their") || namesAMedicationGroup(words.get(i + 1))) {
				continue;
			}
			List<String> marked = new ArrayList<String>(words);
			marked.set(i + 1, DRUG_NAME);
			if (asksAboutHerNamedDrug(marked)) {
				return true;
			}
		}
		return false;
	}

	/** Whether {@code wordsWithTheDrugMarked} asks whether a drug named as hers is safe, or what interacts with it ({@link
	 *  #HER_NAMED_DRUG_SHAPES}, ADR Decision 374). Fail-CLOSED. */
	public static boolean asksAboutHerNamedDrug(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, HER_NAMED_DRUG_SHAPES);
	}

	/**
	 * A question asking whether her medications DUPLICATE one another (ADR Decision 232): a duplication word, beside a
	 * word saying the duplicates are drugs — a medication word, "therapy", a drug-class noun, or "is she on". On the 3.7.1
	 * standalone Sarah Taylor, on ibuprofen, diclofenac and celecoxib, asked "Is she on any duplicate NSAIDs?", was
	 * answered "No duplicate NSAIDs are recorded.": no screen ran, so its finding that the three share a drug group never
	 * reached the prompt. The second word keeps a duplicate RECORD — "a duplicate encounter" — from screening her drugs.
	 */
	private static final Pattern DUPLICATION_CUES = cues("duplicat(?:e|es|ed|ion|ions)", "same (?:drug )?class(?:es)?",
			"doubled? up",
			// "Is she on two drugs that do the same thing?" (ADR Decision 412)
			"do(?:es)? the same (?:thing|job)");

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
		// "+" joins two names as "plus" does (ADR Decision 424): "rifampicin + efavirenz".
		// "w/" is "with" and "w/o" "without", as a clinician abbreviates them (ADR Decision 431): "fluconazole w/ nevirapine ok?"
		String[] parts = text.toLowerCase(Locale.ROOT).replace("+", " plus ")
				.replaceAll("(?<![\\p{L}\\p{N}])w/o(?![\\p{L}\\p{N}])", " without ")
				.replaceAll("(?<![\\p{L}\\p{N}])w/", " with ").split(Pattern.quote(DRUG_NAME), -1);
		for (int p = 0; p < parts.length; p++) {
			if (p > 0) {
				words.add(DRUG_NAME);
			}
			Matcher matcher = WORD.matcher(parts[p]);
			while (matcher.find()) {
				String word = matcher.group().replace('\u2019', '\'');
				// "What's" is "what is", which the shapes spell (ADR Decision 423).
				if ("what's".equals(word)) {
					words.add("what");
					words.add("is");
				} else if ("pt".equals(word) && !words.isEmpty()
						&& ("the".equals(words.get(words.size() - 1)) || "this".equals(words.get(words.size() - 1)))) {
					// "the pt", "this pt" — the patient, never "her PT", the test (ADR Decision 428)
					words.add("patient");
				} else if ("pls".equals(word) || "plz".equals(word)) {
					// "Pls check if fluconazole is ok with her ARVs" (ADR Decision 431)
					words.add("please");
				} else {
					words.add(word);
				}
			}
		}
		return words;
	}

	private static final String PATIENT = "(?:her|him|them|the patient|this patient)";

	private static final String POSSESSIVE = "(?:her|his|their|the patient's|this patient's)";

	private static final String MEDICATIONS = "(?:current |active )?(?:medications|meds|medicines|prescriptions|drugs|regimen"
			// "her medication list", "her current list" (ADR Decision 429)
			+ "|(?:med |medication |medicine |drug )?list)";

	/**
	 * A group of her OWN medications as a clinician words it, closing on a noun of medication — "her TB treatment", "her
	 * ARVs", "her blood pressure pills" (ADR Decision 221). Never resolved to drugs: a question asking whether two such
	 * groups interact is a screen of her medications, which states every pair, those between the two among them.
	 */
	private static final String MEDICATION_GROUP_NOUN = "(?:treatments?|therapy|therapies|"
			+ "regimens?|medications?|meds|medicines?|drugs?|pills|tablets|arvs?|art|antiretrovirals?|antacids?|painkillers?|analgesics?|"
			+ "steroids?|supplements?|vitamins?|contraceptives?|antibiotics?|antimalarials?|"
			+ "antihypertensives?|antiepileptics?|anticonvulsants?|antidepressants?)";

	// and two groups joined, "her TB and HIV meds" (ADR Decision 429)
	private static final String HER_MEDICATION_GROUP = POSSESSIVE + " (?:\\p{L}+ ){0,2}(?:and (?:\\p{L}+ ){1,2})?"
			+ MEDICATION_GROUP_NOUN;

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
	/** "… two hours after her other medicines" — when a proposal says the drug would be given, which no finding weighs (ADR
	 *  Decision 394). */
	private static final String TIMING = "(?:\\p{N}+|one|two|three|four|five|six|eight|twelve|a few|several) (?:hours?|minutes?"
			+ "|days?) (?:after|before|apart from) (?:" + POSSESSIVE + " )?(?:other )?\\p{L}+(?: \\p{L}+)?";

	private static final String A_TIMING = "(?: " + TIMING + ")?";

	/** How a proposal names a condition: "given her kidney disease", "if she has kidney disease" (ADR Decisions 394, 402). */
	private static final String GIVEN_A_CONDITION = "(?:given (?:" + POSSESSIVE + " )?|if (?:she|he|they) (?:has|have) (?:a |an )?)";

	private static final List<Pattern> PROPOSAL_SHAPES = besideHerMedications(
			// "Can I give her ibuprofen?", "Should I start her on clarithromycin?", "Can I give
			// ibuprofen to her?", and "… two hours after her other medicines" (ADR Decision 394)
			"(?:can|could|may|should) (?:i|we) (?:also )?(?:safely )?(?:give|start|prescribe|administer|add) (?:"
					+ PATIENT + " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT + ")?(?: now| today)?" + A_TIMING,
			// "Is ibuprofen safe given her kidney disease?" — a condition the question names, which the check weighs only as her
			// chart records it (ADR Decision 394)
			"is " + D + " (?:safe|ok|okay|appropriate|suitable)(?: (?:for|in) " + PATIENT + ")? " + GIVEN_A_CONDITION
					+ "(?!.*allerg)\\p{L}+(?: \\p{L}+){0,3}",
			// "Planning to start fluconazole - safe?", "Is fluconazole a good idea for her?", "Can I treat her with fluconazole
			// for thrush?" (ADR Decision 427)
			// and "I'd like to give fluconazole - safe?" (ADR Decision 433)
			"(?:(?:(?:i am|i'm|we are|we're) )?(?:planning|going|about|wanting)|(?:i'd|i would|we'd|we would) like) to (?:start|give|"
					+ "prescribe|add) (?:" + PATIENT
					+ " )?(?:on )?" + D + " (?:is (?:it|that|this) )?(?:safe|ok|okay)",
			"(?:is|would) " + D + " (?:be )?a (?:good|reasonable|sensible|safe) (?:idea|choice|option)(?: (?:for|in) " + PATIENT
					+ ")?",
			"(?:can|could|may|should) (?:i|we) (?:safely )?treat " + PATIENT + " with " + D + "(?: now| today)?",
			// "Can I restart her simvastatin?", "Is it safe to resume her simvastatin?" — giving it again (ADR Decision 342)
			"(?:can|could|may|should) (?:i|we) (?:safely )?(?:restart|resume) (?:" + PATIENT + " (?:on )?|" + POSSESSIVE + " )?"
					+ D + "(?: now| today)?",
			"is it (?:safe|ok|okay|appropriate) to (?:restart|resume) (?:" + PATIENT + " (?:on )?|" + POSSESSIVE + " )?" + D
					+ "(?: now| today)?",
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
					// and "Can she get fluconazole?" (ADR Decision 431), and "Can she eat ginger?", answered by the model "Ginger
					// can be eaten, with one caution" (ADR Decision 445)
					+ "|have|use|drink|get|receive|eat|consume) " + D + "(?: now| today)?",
			// "Ibuprofen - yes or no?" (ADR Decision 431)
			// and "Fluconazole - go or no go?" (ADR Decision 446)
			// and "Fluconazole: yes/no?" (ADR Decision 454)
			D + "(?: for " + PATIENT + ")? (?:yes or no|yes no|ok or not|okay or not|safe or not|go or no go)",
			// "Would it be safe to start ibuprofen?", "OK to give ibuprofen?", "Is it safe to add ibuprofen to her regimen?"
			// (ADR Decision 180)
			"would it be (?:safe|ok|okay|appropriate) (?:for " + PATIENT + " )?to (?:(?:give|start|prescribe|administer|add) (?:"
					+ PATIENT + " )?(?:on )?|take )" + D + "(?: for " + PATIENT + ")?(?: now| today)?",
			// and "Safe to give ceftriaxone?" (ADR Decision 350)
			"(?:is it )?(?:ok|okay|safe) to (?:give|start|prescribe|administer|add) (?:" + PATIENT + " )?(?:on )?" + D
					+ "(?: (?:to|for) " + PATIENT + ")?(?: now| today)?",
			"is it (?:safe|ok|okay|appropriate) to add " + D + " to " + POSSESSIVE + " " + MEDICATIONS + "(?: now| today)?",
			// "Is it safe to give her ibuprofen?", "Is it safe to start her on clarithromycin?", "Is it
			// safe to add prednisone for her?" — the patient after the drug too (issue #548), the
			// allowance the first and fourth shapes make, as a reordering of "Is it safe for her to add
			// prednisone?", which this shape already admits.
			// "Is it fine (alright) to give him paracetamol?" too (ADR Decision 357), and "advisable" (wise) (ADR Decision 361)
			"is it (?:safe|ok|okay|appropriate|fine|alright|advisable|wise|acceptable) (?:for " + PATIENT + " )?to (?:(?:give|start|"
					+ "prescribe|administer|add) (?:" + PATIENT + " )?(?:on )?|take )" + D + "(?: for " + PATIENT + ")?(?: now| today)?",
			// "Is ibuprofen safe for her?", "Is ibuprofen appropriate for this patient?", "suitable" (ADR Decision 362), and "Is
			// the fluconazole-nevirapine combination safe?" (ADR Decision 430)
			"is (?:the )?" + D + " (?:(?:combination|combo) )?(?:safe|ok|okay|appropriate|suitable)(?: (?:for|in) " + PATIENT
					+ ")?(?: now| today)?",
			// "Fluconazol safe for her?" — no verb, which the residual reading answers of a name the data carries but not of a
			// misspelling (ADR Decision 448)
			D + " (?:safe|ok|okay|fine|appropriate|suitable) (?:for|in) " + PATIENT + "(?: now| today)?",
			// "Would ibuprofen be appropriate for her?"
			"(?:would|will) " + D + " be (?:safe|ok|okay|appropriate|suitable)(?: for " + PATIENT + ")?",
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
			// "How safe is fluconazole for her?", "How risky is fluconazole for her?", "Rate the risk of giving her fluconazole"
			// — what the check found, with no "Yes" or "No" (ADR Decision 413)
			"how (?:safe|risky|dangerous) is " + D + "(?: (?:for|in) " + PATIENT + ")?",
			// "How safe is clarithromycin with her medicines?", "What if I give her clarithromycin?" (ADR Decision 440)
			"how (?:safe|risky|dangerous) is " + D + " (?:with|alongside) (?:any of )?" + HER_MEDICATION_GROUP,
			"what if (?:i|we) (?:give|start|add|prescribe) (?:" + PATIENT + " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT + ")?",
			"how (?:safe|risky|dangerous) (?:is it )?to (?:give|start|prescribe|add) (?:" + PATIENT + " )?(?:on )?" + D,
			"rate the risk of (?:giving|starting|prescribing|adding) (?:" + PATIENT + " )?(?:on )?" + D,
			// "Can she continue her meds if I add fluconazole?" (ADR Decision 422)
			"(?:can|could|may|should) (?:she|he|they|the patient|this patient) (?:continue|keep taking|stay on) (?:all )?"
					+ POSSESSIVE + " (?:other )?" + MEDICATIONS + " (?:if|when) (?:i|we) (?:add|give|start|prescribe) (?:" + PATIENT
					+ " )?(?:on )?" + D,
			// "What's the risk of giving her fluconazole?", "What's her risk with clarithromycin?", "Explain why fluconazole is a
			// problem for her", "Why can't I give her fluconazole?", "What's wrong with giving her clarithromycin?" (ADR Decision
			// 424)
			"what (?:is|are) the risks? of (?:giving|starting|prescribing|adding|using) (?:" + PATIENT + " )?(?:on )?" + D
					+ "(?: (?:to|for) " + PATIENT + ")?",
			"what (?:is|are) (?:" + POSSESSIVE + " )?risks? (?:with|of|from|on) " + D + "(?: (?:for|in) " + PATIENT + ")?",
			"(?:please )?explain why " + D + " is (?:a problem|dangerous|risky|unsafe|contraindicated|not safe)(?: (?:for|in) "
					+ PATIENT + ")?",
			"why (?:can't|can’t|cant|cannot|can not|shouldn't|shouldn’t|shouldnt|should not) (?:i|we) (?:give|start|prescribe|use"
					+ "|add) (?:" + PATIENT + " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT + ")?",
			"what is wrong with (?:giving|starting|prescribing|using|adding) (?:" + PATIENT + " )?(?:on )?" + D
					+ "(?: (?:to|for) " + PATIENT + ")?",
			// "I want to prescribe clarithromycin. Thoughts?", "Do her current meds make fluconazole unsafe?", "Could
			// clarithromycin cause QT problems with her meds?", "Will ibuprofen affect her kidneys with her current meds?" (ADR
			// Decision 426)
			"(?:i|we) (?:want|would like|plan|am planning|intend|am thinking|am considering) (?:to )?(?:of )?(?:give|start|"
					+ "prescribe|add|giving|starting|prescribing|adding) (?:" + PATIENT + " )?(?:on )?" + D + "(?: (?:to|for) "
					+ PATIENT + ")? (?:any )?(?:thoughts|what do you think)",
			"(?:do|does|would|will|could) (?:any of )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS + " make " + D
					+ " (?:unsafe|dangerous|risky|contraindicated|a problem)",
			"(?:could|would|will|might|can|does) " + D + " cause (?:any )?(?:\\p{L}+ ){0,2}(?:problems?|issues?|prolongation|toxicity"
					+ "|damage) (?:with|alongside) (?:any of )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS,
			// and "Can ibuprofen worsen her kidney function with her other drugs?" (ADR Decision 433)
			"(?:could|would|will|might|does|can) " + D + " (?:affect|harm|damage|worsen) " + POSSESSIVE + " (?:\\p{L}+ ){1,2}(?:with|alongside|"
					+ "given) (?:any of )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS,
			// "What about fluconazole?", "How about starting her on fluconazole?" — a proposal that is no yes-or-no question
			// (ADR Decision 420)
			"(?:what|how) about (?:(?:giving|starting|prescribing|adding|using) (?:" + PATIENT + " )?(?:on )?)?" + D
					+ "(?: (?:for|in) " + PATIENT + ")?",
			// "Is there a risk of QT prolongation if I give her fluconazole?", "Will fluconazole increase bleeding risk?", "Is
			// fluconazole hepatotoxic with her other meds?" — an effect the findings do not weigh one by one, answered with what
			// they found and no "Yes" or "No" (ADR Decision 414)
			"is there (?:a |any )?(?:increased )?risk of (?:\\p{L}+ ){1,3}(?:if|when) (?:i|we) (?:give|add|start|prescribe) (?:"
					+ PATIENT + " )?(?:on )?" + D,
			"(?:will|would|could|does|can) " + D + " (?:increase|raise|worsen) (?:" + POSSESSIVE + " |the )?(?:\\p{L}+ ){1,2}risk",
			// "Can fluconazole cause liver damage?", answered by the model "Fluconazole can be given, with one caution" (ADR
			// Decision 448)
			"(?:could|would|will|might|can|does) " + D + " cause (?:\\p{L}+ ){1,2}(?:damage|toxicity|injury|failure|problems?)"
					+ "(?: (?:for|in) " + PATIENT + ")?",
			"is " + D + " (?:hepatotoxic|nephrotoxic|cardiotoxic|ototoxic|myelotoxic) (?:with|alongside) " + POSSESSIVE
					+ " (?:other )?" + MEDICATIONS,
			// "Fluconazole for her thrush - any issues?", answered by the model "Fluconazole can be given, with one caution" (ADR
			// Decision 441)
			D + "(?: for \\p{L}+(?: \\p{L}+){0,2})? (?:(?:are there )?any )?(?:issues|concerns|problems|risks)",
			// and "Fluconazole in this patient - concerns?" (ADR Decision 445)
			D + " (?:in|for) " + PATIENT + " (?:(?:are there )?any )?(?:issues|concerns|problems|risks|thoughts)",
			// "Is fluconazole bad for her?", "Is it bad to give fluconazole?", "Any reason to worry about fluconazole?" (ADR
			// Decision 453)
			"is " + D + " (?:bad|not good|wrong) (?:for|in) " + PATIENT,
			"(?:is|would) it (?:be )?(?:bad|wrong) to (?:give|start|prescribe|add|use) (?:" + PATIENT + " )?(?:on )?" + D
					+ "(?: (?:to|for) " + PATIENT + ")?",
			"(?:is there )?(?:any |a )?reason to (?:worry|be worried|be concerned) about (?:giving )?" + D + "(?: (?:for|in) "
					+ PATIENT + ")?",
			// "Is fluconazole not safe for her?", "Can't I give her fluconazole?" — asked in the negative, answered with no "Yes"
			// or "No" (ADR Decision 406)
			"is " + D + " (?:not (?:safe|ok|okay|appropriate|suitable)|unsafe)(?: (?:for|in) " + PATIENT + ")?",
			"(?:can't|can’t|cant|cannot|can not|shouldn't|shouldn’t|shouldnt|should not) (?:i|we) (?:give|start|prescribe|"
					+ "administer|add) (?:" + PATIENT + " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT + ")?",
			// "Is there any reason I shouldn't give her ciprofloxacin?" (ADR Decision 394)
			"is there (?:any )?reason (?:i|we) (?:shouldn't|shouldn’t|should not|shouldnt) (?:give|start|prescribe|administer|use|add) (?:"
					+ PATIENT + " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT + ")?",
			// "Any reason she can't have fluconazole?" (ADR Decision 431)
			"(?:is there )?(?:any |a )?reason (?:why )?(?:she|he|they|the patient|this patient) (?:can't|cant|cannot|can not|"
					+ "shouldn't|shouldnt|should not) (?:have|take|get|receive|be given|use) " + D + "(?: now| today)?",
			// "Do I need to monitor her LFTs if I give fluconazole?" (ADR Decision 431)
			"(?:do|should|must) (?:i|we) (?:need to )?monitor " + POSSESSIVE + " (?:\\p{L}+ ){1,2}(?:if|when) (?:i|we) (?:add|give|"
					+ "start|prescribe) (?:" + PATIENT + " )?(?:on )?" + D,
			// "Thinking of fluconazole for her - concerns?" (ADR Decision 432)
			"(?:(?:i am|i'm|we are|we're) )?(?:thinking|considering) (?:of |about )?(?:giving |starting |prescribing |adding )?(?:"
					+ PATIENT + " )?(?:on )?" + D + "(?: (?:for|to) " + PATIENT + ")? (?:any )?(?:concerns|thoughts|problems|issues|"
					+ "risks)",
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
			// "Should fluconazole be used cautiously in her?" (ADR Decision 446)
			"(?:should|must) " + D + " be (?:used|given|prescribed) (?:cautiously|with caution|carefully)(?: (?:for|in) " + PATIENT + ")?",
			"do (?:i|we) (?:need|have) to avoid (?:giving )?(?:" + PATIENT + " )?" + D + "(?: (?:for|in) " + PATIENT + ")?",
			// "Should she avoid ibuprofen?", "Does she need INR monitoring if I add ibuprofen?", "Is she at risk of bleeding if
			// I add ibuprofen?", "What happens if I give her ibuprofen?" (ADR Decision 251)
			"(?:should|must) (?:she|he|they|the patient|this patient) avoid (?:taking )?" + D + "(?: now| today)?",
			// "Should she avoid fluconazole while on ART?" (ADR Decision 444)
			"(?:should|must) (?:she|he|they|the patient|this patient) avoid (?:taking )?" + D + " (?:while (?:on|taking)|with|"
					+ "alongside) (?:" + HER_MEDICATION_GROUP + "|" + MEDICATION_GROUP_NOUN + ")",
			"(?:does|do|will|would) (?:she|he|they|the patient|this patient) need (?:any )?(?:[a-z]+ )?monitoring (?:if|when) (?:i|we)"
					+ " (?:add|give|start|prescribe) (?:" + PATIENT + " )?(?:on )?" + D,
			"(?:is|are|will|would) (?:she|he|they|the patient|this patient) (?:be )?at (?:any )?risk(?: of [a-z]+)? (?:if|when) (?:i|we)"
					+ " (?:add|give|start|prescribe) (?:" + PATIENT + " )?(?:on )?" + D,
			"what (?:happens|would happen|will happen) if (?:i|we) (?:give|add|start|prescribe) (?:" + PATIENT + " )?(?:on )?" + D
					// and "… to her regimen" (ADR Decision 449)
					+ "(?: (?:to|for) " + PATIENT + "| to (?:" + POSSESSIVE + " )?(?:other )?" + MEDICATIONS + ")?",
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
			// and "Is the fluconazole-nevirapine combination dangerous?", a combination named as one (ADR Decision 430)
			"is (?:the )?" + D + " (?:(?:combination|combo) )?(?:dangerous|risky|unsafe|harmful|a problem|problematic)(?: (?:for|in) "
					+ PATIENT + ")?(?: now| today)?",
			"(?:could|would|will|might|can) " + D + " cause (?:any )?(?:problems?|issues?|harm)(?: (?:for|in) " + PATIENT + ")?",
			"(?:could|would|will|might) giving (?:" + PATIENT + " )?" + D + " cause (?:any )?(?:problems?|issues?|harm)",
			// and with no preposition, or "of" — "Is there a risk giving her erythromycin?" (ADR Decision 348)
			"is there (?:any |a )?(?:harm|risk|danger|problem|issue|contraindication) (?:(?:in|with|to|of) )?(?:giving|starting|"
					+ "prescribing|adding) (?:" + PATIENT + " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT + ")?",
			"(?:is there )?any reason to avoid " + D + "(?: (?:for|in) " + PATIENT + ")?",
			// "Any contraindications to giving her metformin?" — "Is there a contraindication to giving her X?" (ADR Decision
			// 350)
			"(?:are there )?any contraindications? (?:to|for|against) (?:giving|starting|prescribing|adding) (?:" + PATIENT
					+ " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT + ")?",
			// "Is there any contraindication to warfarin for her?" (ADR Decision 391)
			"(?:(?:is|are) there )?(?:any |a )?contraindications? (?:to|for|against|with) " + D + "(?: (?:for|in) " + PATIENT
					+ ")?",
			// "Should fluconazole be avoided with her ARVs?", "Does fluconazole have to be avoided?", "Is fluconazole unsafe with
			// her meds?", "Is it a bad idea to give her fluconazole?", "Should I not give fluconazole?", "Is there no problem
			// giving her fluconazole?", "Does giving fluconazole put her at risk?", "Would you avoid clarithromycin in this
			// patient?", "Should we hold off on fluconazole?", "Do her medications rule out fluconazole?", "Is there a reason to
			// be careful with fluconazole?", "Any red flags with fluconazole for her?" — each answered by the model "Fluconazole
			// can be given" (ADR Decision 434)
			"(?:should|must) " + D + " be avoided (?:with|alongside|in combination with|together with) (?:any of )?"
					+ HER_MEDICATION_GROUP,
			"(?:does|do) " + D + " (?:have|need) to be avoided(?: (?:for|in) " + PATIENT + "| (?:with|alongside) (?:any of )?"
					+ HER_MEDICATION_GROUP + ")?",
			// and of a drug named as hers, "Is her aspirin a problem with her other meds?" (ADR Decision 449)
			"is (?:" + POSSESSIVE + " )?" + D + " (?:unsafe|not safe|dangerous|risky|harmful|a problem|problematic) (?:with|alongside|"
					+ "in combination with) (?:any of )?" + HER_MEDICATION_GROUP,
			"(?:is|would) it (?:be )?(?:a bad idea|a mistake|unwise|inadvisable|not advisable|risky|dangerous|unsafe|harmful|wrong) to "
					+ "(?:give|start|prescribe|administer|add|use) (?:" + PATIENT + " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT
					+ ")?",
			"(?:should|must) (?:i|we) not (?:give|start|prescribe|administer|add|use) (?:" + PATIENT + " )?(?:on )?" + D
					+ "(?: (?:to|for|in) " + PATIENT + ")?",
			"is there no (?:problems?|issues?|risks?|harm|danger|concerns?|contraindications?) (?:(?:in|with) )?(?:giving|starting|"
					+ "prescribing|adding) (?:" + PATIENT + " )?(?:on )?" + D + "(?: (?:to|for) " + PATIENT + ")?",
			"(?:does|do|would|will|could|might) (?:giving|starting|prescribing|adding) (?:" + PATIENT + " )?(?:on )?" + D + " put "
					+ PATIENT + " at (?:any )?risk",
			"(?:is|are|will|would) (?:she|he|they|the patient|this patient) (?:be )?at (?:any )?risk(?: of \\p{L}+)? (?:if|when) "
					+ "(?:she|he|they) (?:takes|take|gets|get|starts|start|is given|are given) " + D + "(?: (?:with|alongside) "
					+ "(?:any of )?" + HER_MEDICATION_GROUP + ")?",
			"(?:would|should|do) you avoid (?:giving )?" + D + "(?: (?:in|for) " + PATIENT + ")?",
			"is it (?:better|best|wiser|safer) to avoid (?:giving )?(?:" + PATIENT + " )?" + D + "(?: (?:in|for) " + PATIENT + ")?",
			"(?:should|must) (?:i|we) hold (?:off|back) (?:on )?(?:giving |starting )?(?:" + PATIENT + " )?(?:on )?" + D,
			"(?:do|does|would|will) (?:any of )?" + HER_MEDICATION_GROUP + " rule out " + D,
			"(?:does|do) (?:she|he|they|the patient|this patient) have anything that (?:rules out|contraindicates) " + D,
			"is there (?:a |any )?reason to be (?:careful|cautious) (?:with|about) (?:giving )?" + D + "(?: (?:for|in) " + PATIENT
					+ ")?",
			"(?:are there )?any (?:red flags|warnings|cautions|precautions) (?:with|for|about) (?:giving )?" + D + "(?: (?:for|in) "
					+ PATIENT + ")?",
			// "Is there a serious interaction between fluconazole and her ARVs?" — a grade a "Yes" would claim of every finding
			"(?:is|are) there (?:a |an |any )?(?:clinically )?(?:dangerous|serious|major|significant|severe|important|relevant) "
					+ "(?:drug )?interactions? between " + D + " and (?:any of )?" + HER_MEDICATION_GROUP);

	/**
	 * Whether a question asks whether NOT to give one drug, and nothing else — one of {@link #NEGATED_PROPOSAL_SHAPES}
	 * (ADR Decision 181). Every such question is also one {@link #asksWhetherToGiveADrug} admits; this says only that a
	 * "No" would answer it backwards.
	 */
	public static boolean asksWhetherNotToGiveADrug(List<String> wordsWithTheDrugMarked) {
		// and the residual words asking whether it would harm her, "Will fluconazole harm her?", "Is fluconazole a risk?",
		// which "No — … found a reason to withhold" answered backwards (ADR Decision 454)
		return fitsANegatedShape(wordsWithTheDrugMarked) || residualReading(wordsWithTheDrugMarked) == Residual.HARMS;
	}

	/** {@link #asksWhetherNotToGiveADrug} by its shapes alone, which {@link #residualReading} reads before it reads the words
	 *  (ADR Decision 454). */
	private static boolean fitsANegatedShape(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, NEGATED_PROPOSAL_SHAPES);
	}

	/**
	 * The question shapes asking whether ONE drug interacts with her medications — ADR Decision 181. Not a proposal: it
	 * asks whether there is an interaction, which "Yes" answers where the check relates one. The model answered it
	 * <em>"No — X should not be given: it interacts with …"</em> on the 3.7.1 standalone.
	 */
	private static final List<Pattern> INTERACTS_WITH_HER_MEDICATIONS_SHAPES = shapes(
			// "Check fluconazole against her meds" (ADR Decision 391)
			"(?:please )?check " + D + " (?:against|with) (?:all of )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS,
			// "Does ibuprofen interact with her medications?", "Will ibuprofen interact with any of her current meds?", and the
			// drug named as hers: "Does her lopinavir interact with her other medications?" (ADR Decision 292)
			// and "react with" (ADR Decision 431)
			"(?:does|will|would|could|can) (?:" + POSSESSIVE + " )?" + D + " (?:interact|react) with (?:any of )?" + POSSESSIVE
					+ " (?:other )?" + MEDICATIONS,
			// "Any interactions if I give her ibuprofen?", "Are there any interactions if I start her on ibuprofen?" — the
			// model answered it "No — X should not be given: it interacts with …" (ADR Decision 181)
			"(?:are there )?any (?:drug )?interactions if (?:i|we) (?:give|start|prescribe|add) (?:" + PATIENT + " )?(?:on )?"
					+ D + "(?: to " + PATIENT + ")?",
			// "Is there an interaction if I give her ibuprofen?" — answered "No — X should not be given: it interacts with …"
			// (ADR Decision 256)
			"is there (?:an |any )?(?:drug )?interactions? if (?:i|we) (?:give|start|prescribe|add) (?:" + PATIENT + " )?(?:on )?"
					+ D + "(?: to " + PATIENT + ")?",
			// "Is there anything in her med list that interacts with warfarin?", "Is she on any drugs that interact with
			// dexamethasone?", "Does clarithromycin interact with anything she takes?", "Does she have any interactions with
			// clarithromycin?" (ADR Decision 319)
			"is there anything (?:in|on) " + POSSESSIVE + " (?:med |medication |drug )?(?:list|medications|meds|medicines|drugs)"
					+ " that (?:interacts|interferes) with " + D,
			"is (?:she|he|they|the patient|this patient) (?:on|taking) any (?:drugs|medications|medicines|meds) that "
					+ "(?:interact|interfere) with " + D,
			"(?:does|will|would|could|can) " + D + " interact with anything (?:she|he|they|the patient|this patient) "
					+ "(?:takes|is taking|is on)",
			// "Does her simvastatin interact with anything?" (ADR Decision 449)
			"(?:does|will|would|could|can) (?:" + POSSESSIVE + " )?" + D + " interact with anything(?: else)?",
			// "Will fluconazole interact with what she is taking?" (ADR Decision 445)
			"(?:does|will|would|could|can) " + D + " interact with what (?:(?:she|he|they|the patient|this patient) (?:takes|is taking"
					+ "|is on|are taking|are on)|she's (?:on|taking)|he's (?:on|taking)|they're (?:on|taking))(?: already| currently)?",
			// "Is fluconazole going to interact with anything she's on?", "Tell me about fluconazole and her medications" (ADR
			// Decision 427)
			"is " + D + " going to interact with anything (?:(?:she|he|they|the patient|this patient) (?:is|are) |she's |he's "
					+ "|they're )(?:on|taking)",
			"(?:please )?tell me about " + D + " (?:and|with) " + POSSESSIVE + " (?:other )?" + MEDICATIONS,
			"(?:does|do) (?:she|he|they|the patient|this patient) have any (?:drug )?interactions? with " + D,
			// "Any interactions with his lisinopril?", "Are there any drug interactions with rifampicin?" — the model told a
			// patient on no medication "interactions with Lisinopril are recorded: ketoconazole (Major) …" (ADR Decision 338);
			// and "Any problems with her metronidazole?", answered from the same check (ADR Decision 339)
			"(?:(?:are|is) there )?any (?:drug )?(?:interactions?|problems?|issues?|concerns?) with (?:" + POSSESSIVE + " )?"
					+ D,
			// "Interactions for fluconazole?" (ADR Decision 440)
			"(?:(?:are|is) there )?(?:any )?(?:drug )?interactions? (?:for|of) " + D,
			// "Fluconazole interactions with her meds" (ADR Decision 441)
			D + " (?:drug )?interactions? with (?:any of )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS,
			// "Fluconazole?" — a drug's name asked alone; the model told a patient "Fluconazole can be given, with one
			// caution" (ADR Decision 349)
			D,
			// "Check interactions for amoxicillin", "Check for drug interactions with amoxicillin" (ADR Decision 350)
			// and "Check interactions: amoxicillin" (ADR Decision 429)
			"(?:please )?check (?:for )?(?:any )?(?:drug )?interactions (?:(?:for|with|of|on) )?" + D + "(?: please)?",
			// "DDI check fluconazole", "Check DDI for ciprofloxacin", "fluconazole with her meds" (ADR Decision 413)
			"(?:please )?(?:ddis?|(?:drug )?interactions?) check (?:for |on |of )?" + D + "(?: please)?",
			// "Fluconazole for thrush - any drug interactions with her current list?" — what it is for, which no finding weighs
			// (ADR Decision 429)
			D + " for \\p{L}+(?: \\p{L}+){0,2} (?:(?:are there )?any )?(?:drug )?interactions? with (?:any of )?" + POSSESSIVE
					+ " (?:other )?" + MEDICATIONS,
			"(?:please )?check (?:for )?(?:any )?ddis? (?:for|with|of|on) " + D,
			D + " (?:with|and) " + POSSESSIVE + " " + MEDICATIONS);

	/** An alternative to one drug, asked for (ADR Decision 418): no finding chooses one, and the answer says so. */
	private static final String AN_ALTERNATIVE_TO = "(?:(?:is|are) there (?:a |an |any )?(?:safer |better )?alternatives? (?:to|for) (?:"
			+ POSSESSIVE + " )?" + D + "(?: (?:for|in) " + PATIENT + ")?"
			// "Is there an alternative to rifampicin that doesn't interact with her ARVs?" (ADR Decision 427)
			+ "(?: (?:that|which) (?:does not|doesn't|doesnt|won't|will not|wouldn't|would not) interact with (?:any of )?" + POSSESSIVE
			+ " (?:\\p{L}+ ){0,2}(?:medications|meds|medicines|drugs|regimen|arvs|art|antiretrovirals|treatment|therapy))?"
			+ "|what (?:else )?(?:can|could|should|may) (?:i|we) (?:use|give|prescribe) instead of (?:" + POSSESSIVE + " )?" + D
			+ "|(?:can|could|should|may) (?:i|we) replace (?:" + POSSESSIVE + " )?" + D + " with something (?:safer|else|better))";

	/** Whether {@code words} ask for an alternative to their one drug (ADR Decision 418). */
	public static boolean asksForAnAlternativeTo(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, shapes(AN_ALTERNATIVE_TO));
	}

	/**
	 * Which of her medications interact with ONE drug — "Which of her medications interact with rifampicin?", "Which of her
	 * drugs is the problem with warfarin?" — answered with the findings relating it to them and neither "Yes" nor "No"
	 * (ADR Decision 319): "Yes" does not answer "which".
	 */
	private static final List<Pattern> WHICH_OF_HER_MEDICATIONS_INTERACT_SHAPES = shapes(
			// "Is there a safer alternative to fluconazole?", "What can I use instead of fluconazole?" — what was found about it,
			// under a lead choosing no alternative (ADR Decision 418)
			AN_ALTERNATIVE_TO,
			// "Before I give fluconazole, does she have any interactions?", "Prior to starting rifampicin, should I check
			// anything?" (ADR Decision 419)
			"before (?:i|we) (?:give|start|add|prescribe) (?:" + PATIENT + " )?(?:on )?" + D + " (?:does|do) (?:she|he|they|the patient)"
					+ " have any (?:drug )?interactions",
			"(?:prior to|before) (?:starting|giving|adding|prescribing) (?:" + PATIENT + " )?(?:on )?" + D + " (?:should|do|must) (?:i|we)"
					+ " (?:check|know|consider) anything",
			// "If I start fluconazole, what interactions should I expect?" (ADR Decision 414)
			"if (?:i|we) (?:start|give|add|prescribe) (?:" + PATIENT + " )?(?:on )?" + D + " what (?:drug )?interactions (?:should|"
					+ "would|might|could|do) (?:i|we) expect",
			// "Why is rifampicin flagged?" — what the check found about it (ADR Decision 424)
			"why is (?:" + POSSESSIVE + " )?" + D + " (?:flagged|highlighted|marked|showing a warning|a warning)",
			// "Tell me about fluconazole" — what it does among her medications, with no "Yes" or "No" (ADR Decision 413)
			"(?:please )?tell me about " + D,
			// "Does rifampicin require dose adjustment with her meds?" (ADR Decision 422)
			"(?:does|will|would|could|might) " + D + " (?:require|need|mean) (?:a |any )?(?:dose|dosage) (?:adjustments?|changes?) "
					+ "(?:with|of|for|to|in) (?:any of )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS,
			// "Do I need to adjust any doses if I start rifampicin?" (ADR Decision 411)
			"(?:do|should|must) (?:i|we) (?:need to )?(?:adjust|change|reduce|increase|lower) any (?:doses?|of " + POSSESSIVE
					+ " " + MEDICATIONS + "(?: doses?)?) if (?:i|we) (?:start|give|add|prescribe) (?:" + PATIENT + " )?(?:on )?" + D,
			// "Is there no interaction between fluconazole and her meds?" — which interact, with no "Yes" or "No" (ADR Decision
			// 406)
			"(?:is|are) there no (?:drug )?interactions? (?:between " + D + " and " + POSSESSIVE + " " + MEDICATIONS + "|with " + D
					+ ")",
			// "Which of her medications should I stop if I start rifampicin?" — which interact with it, the module choosing
			// none to stop (ADR Decision 400)
			"which of " + POSSESSIVE + " (?:medications|meds|medicines|drugs) should (?:i|we) stop if (?:i|we) (?:start|give|add|"
					+ "prescribe) (?:" + PATIENT + " )?(?:on )?" + D,
			// "What drugs should she avoid with fluconazole?" (ADR Decision 444)
			"(?:what|which) (?:drugs|medications|medicines|meds) should (?:she|he|they|the patient|this patient|i|we) avoid "
					+ "(?:taking |giving |combining |using )?(?:with|alongside|while on|while taking) " + D,
			// "If I add fluconazole, which of her drugs are affected?" (ADR Decision 449)
			"if (?:i|we) (?:add|give|start|prescribe) (?:" + PATIENT + " )?(?:on )?" + D + " (?:which|what) (?:of " + POSSESSIVE
					+ " )?(?:medications|meds|medicines|drugs) (?:are|is|would be|will be) (?:affected|impacted|at risk)",
			// "Which of her drugs should I avoid giving with fluconazole?" (ADR Decision 429)
			"which of " + POSSESSIVE + " (?:medications|meds|medicines|drugs) should (?:i|we) avoid (?:giving |combining |using )?"
					+ "(?:with|alongside) " + D,
			// "Should she stop any medication before starting rifampicin?" — which of hers the drug interacts with, the
			// module choosing none to stop (ADR Decision 397)
			"(?:should|must|do|does) (?:i|we|she|he|they|the patient|this patient) (?:need to )?stop any (?:of " + POSSESSIVE
					+ " )?(?:current |active |other )?(?:medication|medications|meds|medicines|drugs)(?: first)? before "
					+ "(?:starting|giving|adding|prescribing|taking) (?:" + PATIENT + " )?(?:on )?" + D,
			// "How should I monitor her if I add fluconazole?" (ADR Decision 426)
			"how (?:should|do|would|must) (?:i|we) (?:need to )?monitor " + PATIENT + " (?:if|when) (?:i|we) (?:add|give|start|"
					+ "prescribe) (?:" + PATIENT + " )?(?:on )?" + D,
			// "What should I watch for if I add clarithromycin?" (ADR Decision 391)
			"what (?:should|do|would|must) (?:i|we) (?:need to )?(?:watch|look out|monitor|be careful)(?: for)? if (?:i|we) (?:add|give|"
					+ "start|prescribe) (?:" + PATIENT + " )?(?:on )?" + D,
			"(?:which|what) (?:of )?" + POSSESSIVE + " (?:other |current |active )?(?:medications|meds|medicines|drugs|"
					+ "prescriptions) (?:interact|interacts|interfere|interferes) with " + D,
			"which of " + POSSESSIVE + " (?:medications|meds|medicines|drugs) (?:is|are) the problems? with " + D,
			// "Fluconazole: what interactions does she have?" (ADR Decision 446)
			D + " what (?:drug )?interactions (?:does|do|would|will) (?:she|he|they|the patient|this patient) have",
			"what (?:drug )?interactions (?:does|do|would|will) (?:she|he|they|the patient|this patient) have with " + D,
			// "What are the interactions of fluconazole?" (ADR Decision 427)
			"(?:what|which) are (?:the )?(?:drug )?interactions (?:of|for|with) " + D,
			// "What interacts with her lamivudine?" — which of hers interact with it, of a drug named as hers (ADR Decision
			// 369)
			"what (?:drugs |medications |medicines |meds )?(?:interacts?|interferes?) with " + POSSESSIVE + " " + D);

	/** Whether a question asks which of her medications interact with its one drug — {@link
	 *  #WHICH_OF_HER_MEDICATIONS_INTERACT_SHAPES} (ADR Decision 319). Fail-CLOSED as {@link #asksWhetherToGiveADrug} is. */
	public static boolean asksWhichOfHerMedicationsInteractWith(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, WHICH_OF_HER_MEDICATIONS_INTERACT_SHAPES)
				|| fitsAShape(wordsWithTheDrugMarked, WHICH_OF_A_GROUP_INTERACT_SHAPES);
	}

	/**
	 * Whether a question asks whether ONE drug interacts with her medications, and nothing else — one of {@link
	 * #INTERACTS_WITH_HER_MEDICATIONS_SHAPES} (ADR Decision 181). Fail-CLOSED as {@link #asksWhetherToGiveADrug} is.
	 */
	public static boolean asksWhetherADrugInteractsWithHerMedications(List<String> wordsWithTheDrugMarked) {
		return fitsAnInteractsShape(wordsWithTheDrugMarked)
				|| residualReading(wordsWithTheDrugMarked) == Residual.INTERACTS;
	}

	/**
	 * {@link #asksWhetherADrugInteractsWithHerMedications} by its shapes alone, without {@link #residualReading} — for a
	 * caller marking a word that may be no drug at all, where the residual's open vocabulary would read "Which of her
	 * medications interact?", "which" marked, as a drug asked about (ADR Decision 396).
	 */
	public static boolean fitsAnInteractsShape(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, INTERACTS_WITH_HER_MEDICATIONS_SHAPES)
				|| fitsAShape(wordsWithTheDrugMarked, BESIDE_A_GROUP_OF_HER_MEDICATIONS_SHAPES);
	}

	/** A drug slot of the two-drug shapes below, which may name the drug as hers — "her methotrexate" (ADR Decision 233):
	 *  a "her" fitted none of them, and the model answered "No — Methotrexate and Salicylic acid interact". The together
	 *  shapes' slots too (ADR Decision 246): "Is fluconazole safe with his warfarin?" fitted none of them. */
	private static final String HER_D = "(?:" + POSSESSIVE + " )?" + D;

	/** Several drug slots after "with": "with her nevirapine and lopinavir" (ADR Decision 263). */
	private static final String HER_DS = HER_D + "(?:(?: and)? " + HER_D + ")*";

	/** "together", "at the same time" — two drugs given at once (ADR Decision 432). */
	private static final String AT_ONCE = "(?: together| at the same time| concurrently| simultaneously)?";

	/**
	 * The question shapes asking whether TWO drugs may be given together, over {@link #words} with each drug's name
	 * marked {@link #DRUG_NAME} — ADR Decision 169. A grammar for the reason {@link #PROPOSAL_SHAPES} is, and kept apart
	 * from it, since that one's readers answer for ONE drug proposed: its one reader states the findings about either
	 * drug against her own orders. "Does warfarin interact with ibuprofen?" asks nothing about giving, and is not
	 * admitted.
	 */
	private static final List<Pattern> TOGETHER_SHAPES = shapes(
			// "Is it OK to start rifampicin while she's on efavirenz?", "Can I give clarithromycin while she's on efavirenz?"
			// (ADR Decision 440)
			"is it (?:ok|okay|safe|fine|appropriate) to (?:start|give|add|use|prescribe) (?:" + PATIENT + " )?(?:on )?" + HER_D
					+ " (?:while|when) (?:she's|he's|they're|(?:she|he|the patient|this patient) is|they are) (?:on|taking) " + HER_D,
			// and "Can I prescribe rifampicin if she's on nevirapine?" (ADR Decision 444)
			"(?:can|could|may) (?:i|we) (?:give|start|add|prescribe|use) (?:" + PATIENT + " )?(?:on )?" + HER_D + " (?:while|when|if) "
					+ "(?:she's|he's|they're|(?:she|he|the patient|this patient) is|they are) (?:on|taking) " + HER_D,
			// "Is concomitant use of fluconazole and nevirapine safe?" (ADR Decision 428)
			// and "Is the combination of rifampicin and efavirenz safe?" (ADR Decision 433)
			"is (?:the )?(?:(?:concomitant|concurrent|combined|simultaneous) use|co administration|coadministration|combination|combo)"
					+ " of " + HER_D
					+ " (?:and|with) " + HER_D + " (?:safe|ok|okay|appropriate)(?: (?:for|in) " + PATIENT + ")?",
			// "Can warfarin and ibuprofen be given together?", "Can warfarin be given with ibuprofen to her?"
			// and "co-administered", "co-prescribed", "combined" (ADR Decision 422)
			// and "go together" (ADR Decision 429)
			"(?:can|could|may|should) " + HER_D + " (?:and|with) " + HER_D
					+ " (?:go|be (?:safely )?(?:given|started|prescribed|administered|taken|used|co administered|coadministered|co prescribed"
					+ "|coprescribed|combined))(?: together| at the same time| concurrently| simultaneously)?(?: safely)?(?: (?:to|for) "
					+ PATIENT + ")?(?: now| today)?",
			// and "co-administered" (ADR Decision 428)
			"(?:can|could|may|should) " + HER_D + " be (?:safely )?(?:given|started|prescribed|administered|taken|used|co administered"
					+ "|coadministered|co prescribed|coprescribed|combined) (?:with|alongside) " + HER_DS + "(?: (?:to|for) " + PATIENT + ")?(?: now| today)?",
			// "Can I give her warfarin with ibuprofen?", "Can I give her warfarin and ibuprofen together?", and the first joining
			// word optional where the list ends in "and", a list's commas being no words — "ibuprofen, naproxen and aspirin"
			// (ADR Decision 356); never two names with nothing between, which an unrecognised word beside a drug would make
			// and "Can I co-prescribe X and Y?" (ADR Decision 432)
			// and "Can I use clarithromycin with her efavirenz?" (ADR Decision 444)
			"(?:can|could|may|should) (?:i|we) (?:safely )?(?:give|start|prescribe|administer|co prescribe|coprescribe|co administer"
					+ "|coadminister|use) (?:" + PATIENT
					+ " )?(?:on )?" + HER_D + " (?:(?:and|with|alongside|as well as) " + HER_DS + "|(?:" + HER_D + " )+and " + HER_D
					+ ")" + AT_ONCE + "(?: to " + PATIENT + ")?(?: now| today)?",
			// "Can she take warfarin and ibuprofen together?", "Can she drink alcohol while on metronidazole?", "Can she have
			// alcohol with her metronidazole?" (ADR Decision 268), "Can he have ibuprofen as well as his warfarin?" (ADR
			// Decision 355)
			// and "Can she get heparin while on aspirin?" (ADR Decision 447)
			// and "Can she take both rifampicin and nevirapine?" (ADR Decision 448)
			"(?:can|could|may|should) (?:she|he|they|the patient|this patient) (?:safely )?(?:take|be given|drink|have|get|receive|use) "
					+ "(?:both )?" + HER_D
					// and "at the same time" (ADR Decision 441)
					+ " (?:and|with|alongside|as well as|while on|while taking) " + HER_DS + AT_ONCE + "(?: now| today)?",
			// "Rifampicin and nevirapine together - yes or no?" (ADR Decision 441)
			HER_D + " (?:and|with|plus) " + HER_D + "(?: together)? (?:yes or no|ok or not|okay or not|safe or not)",
			// "Is it safe to give her warfarin and ibuprofen together?", "Is it safe to take warfarin with ibuprofen?", "Is it OK
			// to co-prescribe warfarin with her ibuprofen?" (ADR Decision 250)
			// and "acceptable" (ADR Decision 445)
			"is it (?:safe|ok|okay|appropriate|fine|alright|advisable|wise|acceptable) (?:for " + PATIENT + " )?to (?:(?:give|start|"
					+ "prescribe|co prescribe|administer) (?:"
					// and "Is it safe to combine X and Y?" of a drug she is not on (ADR Decision 429)
					+ PATIENT + " )?(?:on )?|take |combine |use |mix )" + HER_D + " (?:(?:and|with|alongside) " + HER_DS + "|(?:" + HER_D
					+ " )+and " + HER_D + ")" + AT_ONCE + "(?: (?:for|to) " + PATIENT + ")?(?: now| today)?",
			// "Are warfarin and ibuprofen safe together?", "Is warfarin safe with ibuprofen?"
			"(?:are|is) " + HER_D + " and " + HER_D + " (?:safe|ok|okay|appropriate|fine)(?: to (?:give|take|use))?(?: together)?(?: for "
					+ PATIENT + ")?",
			// "Can I give her fluconazole if she drinks alcohol?", answered by the model "Fluconazole can be given … Ethanol can
			// be given" (ADR Decision 451)
			"(?:can|could|may|should) (?:i|we) (?:safely )?(?:give|start|prescribe|add|use) (?:" + PATIENT + " )?(?:on )?" + HER_D
					+ " (?:if|when|while|since) (?:she|he|they|the patient) (?:drinks|drink|takes|take|uses|use|is on|are on|is taking"
					+ "|are taking|has|have) " + HER_D,
			"(?:can|could|may|should) (?:she|he|they|the patient|this patient) (?:safely )?(?:take|have|be given|use|get) " + HER_D
					+ " (?:if|when|while|since) (?:she|he|they) (?:drinks|drink|takes|take|uses|use|is on|are on|is taking|are taking) "
					+ HER_D,
			// "Rifampicin while on nevirapine?", "Rifampicin and nevirapine at the same time?", "Ibuprofen and aspirin together?"
			// (ADR Decision 444)
			HER_D + " (?:while|when) (?:on|taking) " + HER_D,
			HER_D + " (?:and|with|plus) " + HER_D + " (?:together|at the same time|concurrently|simultaneously)",
			// "Fluconazole and rifampicin, can they be given together?" (ADR Decision 415)
			HER_D + " and " + HER_D + " (?:can|could|may|should) they be (?:safely )?(?:given|taken|used|combined|mixed)"
					+ "(?: together)?(?: (?:to|for) " + PATIENT + ")?",
			"is " + HER_D + " (?:safe|ok|okay|appropriate) (?:with|alongside) " + HER_DS + "(?: for " + PATIENT + ")?",
			// "Is fluconazole ok to use with nevirapine?", "fluconazole with nevirapine ok?" (ADR Decision 424)
			"is " + HER_D + " (?:safe|ok|okay|appropriate|fine) to (?:use|give|take|combine) (?:with|alongside) " + HER_DS
					+ "(?: for " + PATIENT + ")?",
			// and "Rifampicin together with nevirapine okay?" (ADR Decision 433)
			HER_D + " (?:with|and|plus|together with) " + HER_D + "(?: together)? (?:safe|ok|okay|fine)",
			// "Is co-prescribing rifampicin and nevirapine safe?" (ADR Decision 446)
			"is (?:co prescribing|coprescribing|co administering|coadministering|combining|giving|using|mixing) " + HER_D
					+ " (?:and|with) " + HER_D + "(?: together)? (?:safe|ok|okay|appropriate|fine)(?: (?:for|in) " + PATIENT + ")?",
			// "Fluconazole for oral thrush - safe with efavirenz?" (ADR Decision 452)
			HER_D + " for \\p{L}+(?: \\p{L}+){0,2} (?:safe|ok|okay|fine) (?:with|alongside) " + HER_D,
			// "Rifampicin and nevirapine ok together?", with no verb (ADR Decision 453)
			HER_D + " and " + HER_D + " (?:ok|okay|fine|safe) together",
			// "Clarithromycin safe with efavirenz or not?" (ADR Decision 448)
			"(?:is )?" + HER_D + " (?:safe|ok|okay|fine) (?:with|alongside) " + HER_D + " or not",
			// "Ibuprofen on top of aspirin - ok?" (ADR Decision 445)
			HER_D + " on top of " + HER_D + " (?:safe|ok|okay|fine)",
			// "Safe combo? fluconazole + rifampicin" (ADR Decision 429)
			"(?:is (?:it|this|that) a )?(?:safe|ok|okay) (?:combo|combination)(?: of)? " + HER_D + " (?:and|with|plus) " + HER_D,
			// "Is warfarin compatible with his amiodarone?", "Are warfarin and amiodarone compatible?", "Can metronidazole be
			// combined with his warfarin?", "Can I combine X with Y?" — asked as whether one is safe with the other (ADR
			// Decision 332)
			"is " + HER_D + " compatible with " + HER_DS + "(?: for " + PATIENT + ")?",
			"are " + HER_D + " and " + HER_D + " compatible(?: (?:together|with each other|for " + PATIENT + "))?",
			"(?:can|could|may|should) " + HER_D + " be (?:safely )?combined with " + HER_DS + "(?: (?:in|for) " + PATIENT + ")?",
			"(?:can|could|may|should) (?:i|we) (?:safely )?(?:combine|mix) " + HER_D + " (?:and|with) " + HER_DS + "(?: (?:in|for) "
					+ PATIENT + ")?",
			// "Can I give clarithromycin to a patient on simvastatin?" — given with it (ADR Decision 352)
			// and "to someone on methotrexate" (ADR Decision 447)
			"(?:can|could|may|should) (?:i|we) (?:safely )?(?:give|start|prescribe|administer) " + HER_D + " to (?:(?:a|the|this) "
					+ "patient|someone|somebody|anyone|a person) (?:on|taking|who takes|who is (?:on|taking)) " + HER_DS
					+ "(?: now| today)?",
			// "Can I add fluconazole to her cotrimoxazole?", "Is it safe to add fluconazole to her cotrimoxazole?" — given
			// with it (ADR Decision 343)
			"(?:can|could|may|should) (?:i|we) (?:safely )?add " + HER_D + " to " + HER_DS + "(?: for " + PATIENT
					+ ")?(?: now| today)?",
			"is it (?:safe|ok|okay|appropriate|fine|alright|advisable|wise) to add " + HER_D + " to " + HER_DS + "(?: for " + PATIENT + ")?(?: now| today)?");

	/**
	 * Whether a question asks whether two drugs may be given together and nothing else — one of
	 * {@link #TOGETHER_SHAPES}, asked of its {@link #words} with each drug's name marked {@link #DRUG_NAME}. ADR
	 * Decision 169. Fail-CLOSED as {@link #asksWhetherToGiveADrug} is: a phrasing it misses keeps the answer as the
	 * model wrote it.
	 */
	public static boolean asksWhetherToGiveDrugsTogether(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, TOGETHER_SHAPES);
	}

	/** "Can I give her ibuprofen or paracetamol?", "Is ibuprofen or paracetamol safe for her?" — one drug or the other, each
	 *  proposed alone (ADR Decision 393). */
	private static final List<Pattern> EITHER_SHAPES = shapes(
			"(?:can|could|may|should) (?:i|we) (?:safely )?(?:give|start|prescribe|administer|use) (?:" + PATIENT + " )?(?:on )?"
					+ D + " or " + D + "(?: (?:to|for) " + PATIENT + ")?(?: now| today)?",
			"(?:can|could|may|should) (?:she|he|they|the patient|this patient) (?:safely )?(?:take|have|use) " + D + " or " + D
					+ "(?: now| today)?",
			"(?:is|would) " + D + " or " + D + " (?:be )?(?:safe|ok|okay|appropriate|suitable)(?: (?:for|in) " + PATIENT + ")?");

	/** "Is diclofenac safer than ibuprofen with her aspirin?", "Is diclofenac or ibuprofen better with her aspirin?" — the
	 *  choice of two drugs made beside a third, hers, named last (ADR Decision 442). */
	private static final String BESIDE_ONE_OF_HERS_WHICH_OF_TWO = "(?:(?:is|would) " + D + " (?:be )?(?:a (?:safer|better) "
			+ "(?:choice|option|alternative|drug)|safer|better|preferable) (?:than|over) " + D + "|(?:is|would) " + D + " or " + D
			+ " (?:be )?(?:safer|better|preferable|preferred))(?: (?:for|in) " + PATIENT + ")? (?:with|alongside|given|on top of) "
			+ POSSESSIVE + " " + D;

	/** "Which is safer for her, ibuprofen or paracetamol?", "Is ibuprofen or paracetamol better for her?" — a choice between
	 *  two drugs, which no finding makes (ADR Decision 393). */
	private static final List<Pattern> WHICH_OF_TWO_SHAPES = shapes(
			"(?:which|what) (?:is|would be|will be) (?:the )?(?:safer|safest|better|best|preferable|preferred)(?: (?:option|choice|"
					+ "drug|one))?(?: (?:for|in) " + PATIENT + ")? " + D + " or " + D,
			"(?:is|would) " + D + " or " + D + " (?:be )?(?:safer|better|preferable|preferred)(?: (?:for|in) " + PATIENT + ")?",
			// "Is paracetamol safer than ibuprofen for her?" (ADR Decision 431)
			// and "Is azithromycin a safer choice than clarithromycin for her?" (ADR Decision 432)
			"(?:is|would) " + D + " (?:be )?(?:a (?:safer|better) (?:choice|option|alternative|drug)|safer|better|preferable) (?:than"
					+ "|over) " + D + "(?: (?:for|in) " + PATIENT + ")?",
			"(?:should|can|could) (?:i|we) (?:give|use|prescribe|choose) " + D + " or " + D + "(?: (?:for|in) " + PATIENT + ")?",
			"(?:which|what) (?:should|can|could) (?:i|we) (?:give|use|prescribe|choose)(?: (?:for|to) " + PATIENT + ")? " + D + " or "
					+ D,
			// "Paracetamol or ibuprofen for her fever?", answered by the model "Ibuprofen can be given" on 5 of 15 patients, and
			// "Is rifabutin better than rifampicin with her ARVs?" — the choice made beside a group of hers (ADR Decision 452)
			D + " or " + D + " for (?:" + POSSESSIVE + " )?\\p{L}+(?: \\p{L}+)?",
			"(?:is|would) " + D + " (?:be )?(?:a (?:safer|better) (?:choice|option|alternative|drug)|safer|better|preferable) (?:than"
					+ "|over) " + D + " (?:with|alongside|given|on top of) (?:any of )?" + HER_MEDICATION_GROUP,
			BESIDE_ONE_OF_HERS_WHICH_OF_TWO);

	/** Whether a question asks about either of two drugs, each proposed alone — or which of the two to choose, {@link
	 *  #asksWhichOfTwoDrugs} (ADR Decision 393). Fail-CLOSED. */
	public static boolean asksAboutEitherOfTwoDrugs(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, EITHER_SHAPES) || asksWhichOfTwoDrugs(wordsWithTheDrugsMarked);
	}

	/** Whether a question asks which of two drugs to choose beside a third, hers, named last — {@link
	 *  #BESIDE_ONE_OF_HERS_WHICH_OF_TWO} (ADR Decision 442). Fail-CLOSED. */
	public static boolean asksWhichOfTwoDrugsBesideOneOfHers(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, shapes(BESIDE_ONE_OF_HERS_WHICH_OF_TWO));
	}

	/** Whether a question asks which of two drugs to choose, or which is safer (ADR Decision 393). Fail-CLOSED. */
	public static boolean asksWhichOfTwoDrugs(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, WHICH_OF_TWO_SHAPES);
	}

	/**
	 * The question shapes a request to screen the patient's OWN medications against each other may
	 * take — issue #469. A grammar for the reason {@link #PROPOSAL_SHAPES} is: the word list it
	 * replaced admitted "Does this drug interact with her medications?", every word of which was on
	 * it, and answered it with her own pairs.
	 */

	/** "Which of her ARVs interact with rifampicin?" — which of a group of hers interact with one drug (ADR Decision 432). */
	private static final List<Pattern> WHICH_OF_A_GROUP_INTERACT_SHAPES = shapes("(?:which|what) of " + HER_MEDICATION_GROUP
			+ " (?:interact|interacts|interfere|interferes) with " + D);

	private static final Pattern ENDS_ON_A_MEDICATION_GROUP_NOUN = Pattern.compile("(?:^| )" + MEDICATION_GROUP_NOUN + "$");

	/**
	 * Whether {@code words} close on a noun of medication — "ARVs", "TB treatment", "blood pressure pills" — the group of her
	 * own medications {@link #HER_MEDICATION_GROUP} reads, never one drug's name (ADR Decisions 221, 308).
	 */
	public static boolean namesAMedicationGroup(String words) {
		return words != null && ENDS_ON_A_MEDICATION_GROUP_NOUN.matcher(words.toLowerCase(Locale.ROOT).trim()).find();
	}

	private static final Pattern A_GROUP_OF_HER_MEDICATIONS = Pattern.compile("(?:^| )" + POSSESSIVE + " ((?:\\p{L}+ ){0,2}"
			+ "(?:and (?:\\p{L}+ ){1,2})?" + MEDICATION_GROUP_NOUN + ")(?= |$)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	/** "Her regimen", "his current medications": every medication she takes, no group within them. */
	private static final Pattern EVERY_MEDICATION_SHE_TAKES = Pattern.compile("(?:(?:other|current|active|usual|regular|daily"
			+ "|all) ){0,2}(?:treatments?|therapy|therapies|regimens?|medications?|meds|medicines?|drugs?|pills|tablets)",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	/**
	 * The group of her medications {@code question} names, as written — "TB preventive therapy" of "Is dolutegravir safe
	 * with her TB preventive therapy?" — or {@code null} where it names none, or names every medication she takes ("her
	 * current regimen"). The words name no drug, so the check cannot tell which of her orders they mean (ADR Decision 313).
	 */
	public static String groupOfHerMedicationsNamed(String question) {
		if (question == null) {
			return null;
		}
		Matcher group = A_GROUP_OF_HER_MEDICATIONS.matcher(question.replaceAll("[^\\p{L}\\p{N}']+", " ").trim());
		while (group.find()) {
			if (!EVERY_MEDICATION_SHE_TAKES.matcher(group.group(1)).matches()) {
				return group.group(1);
			}
		}
		return null;
	}

	/** "Does her TB treatment interact with her ARVs?", "Do her TB drugs interact with her antiretrovirals?" (ADR Decision 221). */
	private static final String TWO_OF_HER_MEDICATION_GROUPS_INTERACT = "(?:(?:does|do|will|would) " + HER_MEDICATION_GROUP
			// and "clash with" (ADR Decision 449)
			+ " (?:interact|clash) with " + HER_MEDICATION_GROUP
			// "Do her ARVs and her TB treatment clash?", "Can she take her TB drugs with her ARVs?" (ADR Decision 449)
			+ "|(?:do|does|will|would) " + HER_MEDICATION_GROUP + " and " + HER_MEDICATION_GROUP + " (?:interact|clash)(?: with "
			+ "(?:each other|one another))?"
			+ "|(?:can|could|may|should) (?:she|he|they|the patient|this patient) (?:safely )?(?:take|have|continue|stay on) "
			+ HER_MEDICATION_GROUP + " (?:with|and|alongside) " + HER_MEDICATION_GROUP + "(?: together)?)";

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
			// and "Would fluconazole interfere with her ARVs?" (ADR Decision 429)
			"(?:does|will|would|could|can) " + D + " (?:interact|interfere) with (?:any of )?" + HER_MEDICATION_GROUP,
			// "Will his TB preventive therapy interact with efavirenz?" — the group named first (ADR Decision 313)
			"(?:does|do|will|would|could|can) " + HER_MEDICATION_GROUP + " interact with " + D,
			// "Fluconazole vs her ARVs - interaction?" (ADR Decision 433)
			D + " (?:vs|versus|and|with) " + HER_MEDICATION_GROUP + " (?:any )?(?:drug )?interactions?",
			"(?:(?:is|are) there )?any (?:problems?|issues?|concerns?|interactions?) (?:with |in )?(?:combining|mixing) "
					+ HER_MEDICATION_GROUP + " (?:with|and) " + D,
			"(?:(?:is|are) there )?any (?:problems?|issues?|concerns?|interactions?) (?:with |in )?(?:combining|mixing|giving|"
					+ "starting|adding|taking) " + D + " (?:with|to|and|alongside) " + HER_MEDICATION_GROUP,
			// "Is there a problem combining fluconazole with what she's already taking?" (ADR Decision 429)
			"(?:(?:is|are) there )?(?:a|any) (?:problems?|issues?|concerns?|interactions?) (?:with |in )?(?:combining|mixing|giving|"
					+ "starting|adding) " + D + " (?:with|to|alongside) what (?:she's|he's|they're|(?:she|he|the patient) is|they are) "
					+ "(?:already |currently )?(?:taking|on)");

	/** The screen shapes naming no medication word the trigger's own cues know (ADR Decisions 221, 254). */
	private static final List<Pattern> MEDICATION_GROUPS_SHAPES = shapes(TWO_OF_HER_MEDICATION_GROUPS_INTERACT,
			HER_MEDICATION_GROUP_SAFE_TOGETHER);

	private static final List<Pattern> SCREEN_SHAPES = shapes(
			TWO_OF_HER_MEDICATION_GROUPS_INTERACT, HER_MEDICATION_GROUP_SAFE_TOGETHER,
			// "Are there any drug interactions with her current medications?"
			"(?:are|is) there (?:any )?(?:drug )?interactions? (?:with|between|among) " + POSSESSIVE + " " + MEDICATIONS,
			// "What is the most serious interaction she has?", "What is the severity of her interactions?", "Any contraindicated
			// combinations?" — the screen, which states each interaction's rating (ADR Decision 412)
			"what (?:is|are) the (?:most (?:serious|severe|dangerous|important)|worst) (?:drug )?interactions? (?:she|he|they|the "
					+ "patient|this patient) (?:has|have)",
			"what (?:is|are) the severit(?:y|ies) of " + POSSESSIVE + " (?:drug )?interactions?",
			"(?:(?:are|is) there )?any contraindicated (?:drug )?combinations?",
			// "Are her current medications ok?", "Any red flags in her medications?", "Review her medications", "Which of her drugs
			// is causing the interaction?", "Why does she have a drug warning?" — whether her medications as a whole are all
			// right is the screen of them (ADR Decision 416)
			"(?:are|is) " + POSSESSIVE + " (?:current |active )?(?:medications|meds|medicines|drugs|prescriptions|regimen|treatment)"
					+ " (?:ok|okay|fine|alright|all right|appropriate|safe)",
			"is (?:everything|it all|all) (?:ok|okay|fine|alright|all right) with " + POSSESSIVE + " (?:current )?(?:medications|"
					+ "meds|medicines|drugs|prescriptions|regimen|treatment)",
			"(?:(?:are|is) there )?any (?:red flags?|warnings?|safety (?:issues?|concerns?|problems?)|issues?|problems?|concerns?)"
					+ " (?:in|with) " + POSSESSIVE + " (?:current |active )?(?:medications|meds|medicines|drugs|prescriptions|regimen|"
					// "her medication list" (ADR Decision 429)
					+ "treatment|(?:med |medication |medicine |drug )?list)",
			"(?:please )?(?:review|check) " + POSSESSIVE + " (?:current |active )?(?:medications|meds|medicines|drugs|prescriptions|"
					+ "regimen|treatment)",
			// "Do a medication review" (ADR Decision 418)
			"(?:please )?(?:do|perform|run) a (?:medication|medicine|drug|med) review",
			"which of " + POSSESSIVE + " (?:medications|meds|medicines|drugs) (?:is|are) causing (?:the|this|an?) interactions?",
			"why (?:does|do) (?:she|he|they|the patient|this patient) have (?:a |any )?(?:drug |safety |interaction )?warnings?",
			"what (?:does|do) (?:the|this|that) (?:drug |safety |interaction )?warnings? mean",
			// "Any drug interactions or allergy conflicts with her current medications?" — the safety screen (ADR Decision
			// 318)
			"(?:(?:are|is) there )?any (?:drug )?interactions? (?:or|and) (?:drug )?(?:allergy|allergic|allergies) (?:conflicts?|"
					+ "issues?|problems?|concerns?|reactions?) (?:with|between|among|in) " + POSSESSIVE + " " + MEDICATIONS,
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
			// "Can she safely take all her medicines together?", "Are there any QT prolonging drugs in her list?" — the screen,
			// as "Is there a risk of QT prolongation with her meds?" is (ADR Decision 427)
			"(?:can|could|should) (?:she|he|they|the patient|this patient) (?:safely )?(?:take|continue|have) (?:all )?(?:of )?"
					+ POSSESSIVE + " " + MEDICATIONS + " together",
			"(?:(?:are|is) there )?any (?:qt prolonging|interacting|nephrotoxic|hepatotoxic|serotonergic) (?:drugs|medications|meds"
					+ "|medicines) (?:in|on) " + POSSESSIVE + " (?:med |medication |drug )?list",
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
			"(?:is|are) " + SUBJECT + " (?:currently |still |now |already )?(?:on|taking|using|receiving|prescribed) " + D
					// and "Is she on fluconazole already?" (ADR Decision 432)
					+ "(?: now| currently| at the moment| at present| already)?",
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

	/** "Is his klarizom safe?", "What interacts with his klarizom?" — a drug named as hers, asked whether it is safe or what
	 *  interacts with it, the possessive right before the drug (ADR Decision 374). */
	private static final List<Pattern> HER_NAMED_DRUG_SHAPES = shapes(
			"is " + POSSESSIVE + " " + D + " (?:safe|ok|okay|appropriate)(?: for " + PATIENT + ")?(?: to (?:continue|keep"
					+ " taking|take|use))?(?: now)?",
			"what (?:drugs |medications |medicines |meds )?(?:interacts?|interferes?) with " + POSSESSIVE + " " + D);

	/** "Is lidocaine safe with her other medications?", "Is ibuprofen okay with her current meds?" */
	private static final String SAFE_WITH_HER_MEDICATIONS = "is " + HER_D + " (?:safe|ok|okay|appropriate) (?:with|alongside|"
			// "in combination with her current regimen" (ADR Decision 353)
			+ "in combination with|together with) "
			+ "(?:any of )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS;

	/** "Is dolutegravir safe with her TB preventive therapy?" — one drug beside a group of her medications (ADR Decision
	 *  313), answered as beside her medications under a lead naming the group. */
	private static final String SAFE_WITH_A_GROUP_OF_HER_MEDICATIONS = "(?:is " + D + " (?:safe|ok|okay|appropriate)"
			+ "(?: to (?:give|take|use|start))?"
			// "Is it ok to combine ciprofloxacin with her ART?" (ADR Decision 422)
			+ "|is it (?:safe|ok|okay|appropriate) to combine " + D + ")"
			// "in combination with her ARV regimen" (ADR Decision 428)
			+ " (?:with|alongside|in combination with|together with) (?:any of )?" + HER_MEDICATION_GROUP;

	private static final List<Pattern> SAFE_WITH_HER_MEDICATIONS_SHAPES = shapes(SAFE_WITH_HER_MEDICATIONS,
			// "Will fluconazole be okay with what she's on?", "Rifampicin on top of her ARVs - safe?" (ADR Decision 440)
			"(?:will|would|is) " + D + " (?:be )?(?:ok|okay|safe|fine) with what (?:she's|he's|they're|(?:she|he|the patient) is"
					+ "|they are) (?:already |currently )?(?:on|taking)",
			D + " on top of " + HER_MEDICATION_GROUP + " (?:safe|ok|okay|fine)",
			// "Can I give fluconazole to a pregnant patient on ARVs?" (ADR Decision 452)
			"(?:can|could|may|should) (?:i|we) (?:safely )?(?:give|start|prescribe|add) " + D + " to (?:a|the|this) (?:\\p{L}+ )?"
					+ "(?:patient|woman|man|person|child) (?:on|taking|who is on|who is taking) (?:" + HER_MEDICATION_GROUP + "|"
					+ MEDICATION_GROUP_NOUN + ")",
			// "Fluconazole vs her ARVs?" (ADR Decision 448)
			D + " (?:vs|versus) " + HER_MEDICATION_GROUP,
			// "Fluconazole and her ARVs - ok?" (ADR Decision 444)
			D + " (?:and|with|plus) " + HER_MEDICATION_GROUP + " (?:safe|ok|okay|fine)",
			// "What dose of ibuprofen is safe with her medications?" — a dose asked for, which no finding weighs (ADR Decision
			// 394)
			"(?:what|which) (?:dose|dosage|amount) of " + D + " (?:is|would be) (?:safe|ok|okay|appropriate) (?:with|alongside) (?:any "
					+ "of )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS,
			// "Would amiodarone be a problem with her current meds?" (ADR Decision 391)
			"(?:(?:would|will|could|might|is) " + D + " (?:be )?|is " + D + " going to be )(?:a problem|an issue|a concern|problematic)"
					+ " (?:with|for) (?:any of )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS,
			// "fluconazole - safe w/ her current meds?", "Fluconazole ok? She's on ARVs" (ADR Decision 432)
			D + " (?:safe|ok|okay|fine) (?:with|alongside) (?:any of )?(?:" + POSSESSIVE + " (?:other )?" + MEDICATIONS + "|"
					+ HER_MEDICATION_GROUP + ")",
			D + " (?:safe|ok|okay|fine) (?:she's|she is|he's|he is|they're|they are) (?:on|taking) (?:\\p{L}+ ){0,2}(?:"
					+ MEDICATION_GROUP_NOUN + "|medications|meds|medicines|drugs)",
			SAFE_WITH_A_GROUP_OF_HER_MEDICATIONS,
			// "Is her methotrexate safe?" of a drug she is not on: what a proposal asks (ADR Decision 315)
			"is " + POSSESSIVE + " " + D + " (?:safe|ok|okay|appropriate)(?: for " + PATIENT + ")?",
			// "Can he take dolutegravir and his TB preventive therapy together?", "Can I give her dolutegravir with her TB
			// preventive therapy?" (ADR Decision 313)
			"(?:can|could|may) (?:i give " + PATIENT + "|(?:he|she|they|the patient|this patient) (?:take|have|use)) " + D
					+ " (?:and|with|alongside) " + HER_MEDICATION_GROUP + "(?: together)?",
			// "Can fluconazole be given with her HIV medicines?" (ADR Decision 426)
			// and "Can fluconazole be taken by her with her other drugs?" (ADR Decision 429)
			"(?:can|could|may|should) " + D + " be (?:safely )?(?:given|taken|used|started|combined)(?: (?:by|to|for) " + PATIENT
					+ ")? (?:with|alongside) (?:any of )?" + HER_MEDICATION_GROUP);

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

	/** "If I stop her rifampicin, which interactions go away?" — which interactions stopping her drug would end (ADR
	 *  Decision 333): of her own drug, what interacts with it. */
	private static final String GO_AWAY = "(?:go away|would go away|will go away|resolve|would resolve|end|would end|stop|"
			+ "disappear|are removed|would be removed)";

	private static final List<Pattern> STOPPING_IT_ENDS_SHAPES = shapes(
			"if (?:i|we) (?:stop|discontinue|remove|hold) " + POSSESSIVE + " " + D + " (?:which|what) (?:drug )?interactions? "
					+ GO_AWAY,
			"(?:which|what) (?:drug )?interactions? " + GO_AWAY + " if (?:i|we) (?:stop|discontinue|remove|hold) " + POSSESSIVE
					+ " " + D,
			"(?:which|what) (?:drug )?interactions? would (?:stopping|discontinuing|removing) " + POSSESSIVE + " " + D
					+ " (?:remove|end|resolve)");

	/** "Should one of his metronidazole orders be stopped?" — whether to stop his drug (ADR Decision 345). */
	private static final String ONE_OF_HER_D_ORDERS_STOPPED = "(?:should|must) one of " + POSSESSIVE + " " + D
			+ " orders be (?:stopped|discontinued|held)(?: now)?";

	/** "Is it safe to stop her nevirapine?", "Can I stop her nevirapine?" — whether to stop her drug (ADR Decision 335). */
	/** "… before surgery", "… ahead of the procedure" — the occasion a question whether to stop her drug names, which asks
	 *  nothing else of it (ADR Decision 391). */
	private static final String BEFORE_AN_OCCASION = "(?: (?:before|ahead of) (?:the |her |his |their )?\\p{L}+)?";

	private static final List<Pattern> STOP_HER_DRUG_SHAPES = shapes(
			"is it (?:safe|ok|okay|alright|appropriate) to (?:stop|discontinue|hold) " + POSSESSIVE + " " + D
					+ "(?: now)?" + BEFORE_AN_OCCASION,
			"(?:can|could|may) (?:i|we) (?:safely )?(?:stop|discontinue|hold) " + POSSESSIVE + " " + D
					+ "(?: now)?" + BEFORE_AN_OCCASION,
			"(?:should|must|do) (?:i|we) (?:need to )?(?:stop|discontinue|hold) " + POSSESSIVE + " " + D
					+ "(?: now)?" + BEFORE_AN_OCCASION,
			ONE_OF_HER_D_ORDERS_STOPPED);

	/** Whether it asks whether stopping it is safe or allowed — not whether it should be stopped, which ADR Decision 259
	 *  answers of her own drug as her medication with no lead (ADR Decision 335). */
	public static boolean asksWhetherItIsSafeToStopHerDrug(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, STOP_HER_DRUG_SHAPES.subList(0, 2));
	}

	/** Whether a question asks whether to stop its one drug, named as hers (ADR Decision 335). Fail-CLOSED. */
	public static boolean asksWhetherToStopHerDrug(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, STOP_HER_DRUG_SHAPES);
	}

	/** Whether a question asks which interactions stopping its one drug would end (ADR Decision 333). Fail-CLOSED. */
	public static boolean asksWhichInteractionsStoppingItEnds(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, STOPPING_IT_ENDS_SHAPES);
	}

	/** "Is it okay to continue her aspirin?" (ADR Decision 341). */
	private static final String CONTINUE_HER_D = "is it (?:safe|ok|okay|alright|appropriate|fine) to (?:continue|keep (?:giving"
			+ "|taking)) " + POSSESSIVE + " " + D
			// "Is it ok to continue her rifampicin with her ARVs?" (ADR Decision 426)
			+ "(?: (?:with|alongside) (?:all )?" + HER_MEDICATION_GROUP + ")?(?: now)?";

	/** "Can I continue her Advil?", "Can I keep giving her aspirin?" (ADR Decision 341). */
	private static final String CONTINUE_HER_D_AS_I = "(?:can|could|may|should) (?:i|we) (?:safely )?(?:continue|keep giving)"
			+ " " + POSSESSIVE + " " + D + "(?: now)?";

	/** "Can she keep taking her aspirin?" (ADR Decision 341). */
	private static final String CONTINUE_HER_D_AS_SHE = "(?:can|could|may|should) (?:she|he|they|the patient|this patient) "
			+ "(?:continue|keep taking) " + POSSESSIVE + " " + D + "(?: now)?";

	/** Whether to continue her drug, "Is her aspirin safe to continue?" among them (ADR Decisions 301, 341). */
	private static final List<Pattern> CONTINUE_HER_DRUG_SHAPES = shapes(CONTINUE_HER_D, CONTINUE_HER_D_AS_I,
			CONTINUE_HER_D_AS_SHE, "is " + POSSESSIVE + " " + D + " (?:safe|ok|okay|appropriate)(?: for " + PATIENT + ")? to "
					+ "(?:continue|keep taking)(?: now)?");

	/** "Should she keep taking both aspirin and Advil?", "Can I continue her aspirin and her Advil?", "Is it safe to keep
	 *  giving her aspirin and Advil?" — whether to continue TWO drugs (ADR Decision 380). */
	private static final List<Pattern> CONTINUE_BOTH_SHAPES = shapes(
			"(?:can|could|may|should) " + SUBJECT + " (?:continue|keep taking|keep on) (?:both )?" + HER_D + " (?:and|with|alongside) "
					+ HER_D
					+ "(?: together)?(?: now)?",
			"(?:can|could|may|should) (?:i|we) (?:safely )?(?:continue|keep giving) (?:" + PATIENT + " )?(?:both )?" + HER_D
					+ " (?:and|with|alongside) " + HER_D + "(?: together)?(?: now)?",
			"is it (?:safe|ok|okay|alright|appropriate|fine) (?:for " + PATIENT + " )?to (?:continue|keep (?:giving|taking)) "
					+ "(?:" + PATIENT + " )?(?:both )?" + HER_D + " and " + HER_D + "(?: together)?(?: now)?");

	/** Whether a question asks whether to continue its TWO drugs, and nothing else (ADR Decision 380). Fail-CLOSED. */
	public static boolean asksWhetherToContinueBothDrugs(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, CONTINUE_BOTH_SHAPES);
	}

	/** Whether a question asks whether to continue its one drug, named as hers (ADR Decision 341). Fail-CLOSED. */
	public static boolean asksWhetherToContinueHerDrug(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, CONTINUE_HER_DRUG_SHAPES);
	}

	private static final List<Pattern> BESIDE_HER_MEDICATIONS_SHAPES = shapes(
			SAFE_WITH_HER_MEDICATIONS,
			// "Is it safe to stop her nevirapine?" (ADR Decision 335)
			"is it (?:safe|ok|okay|alright|appropriate) to (?:stop|discontinue|hold) " + POSSESSIVE + " " + D
					+ "(?: now)?" + BEFORE_AN_OCCASION,
			"(?:can|could|may) (?:i|we) (?:safely )?(?:stop|discontinue|hold) " + POSSESSIVE + " " + D
					+ "(?: now)?" + BEFORE_AN_OCCASION,
			"(?:should|must|do) (?:i|we) (?:need to )?(?:stop|discontinue|hold) " + POSSESSIVE + " " + D
					+ "(?: now)?" + BEFORE_AN_OCCASION,
			ONE_OF_HER_D_ORDERS_STOPPED,
			// "If I stop her rifampicin, which interactions go away?" (ADR Decision 333)
			"if (?:i|we) (?:stop|discontinue|remove|hold) " + POSSESSIVE + " " + D + " (?:which|what) (?:drug )?interactions? "
					+ GO_AWAY,
			"(?:which|what) (?:drug )?interactions? " + GO_AWAY + " if (?:i|we) (?:stop|discontinue|remove|hold) " + POSSESSIVE
					+ " " + D,
			"(?:which|what) (?:drug )?interactions? would (?:stopping|discontinuing|removing) " + POSSESSIVE + " " + D
					+ " (?:remove|end|resolve)",
			// "Is her amiodarone safe?", "Is her ibuprofen safe to continue?" — her drug named as hers, whether it is safe,
			// and "What interacts with her ibuprofen?" (ADR Decision 301)
			"is " + POSSESSIVE + " " + D + " (?:safe|ok|okay|appropriate)(?: for " + PATIENT + ")?(?: to (?:continue|keep"
					+ " taking|take|use))?(?: now)?",
			// "Can I continue her Advil?", "Is it okay to continue her aspirin?", "Can she keep taking her aspirin?" — what "Is
			// her aspirin safe to continue?" asks (ADR Decision 341)
			CONTINUE_HER_D, CONTINUE_HER_D_AS_I, CONTINUE_HER_D_AS_SHE,
			"what (?:drugs |medications |medicines |meds )?(?:interacts?|interferes?) with " + POSSESSIVE + " " + D,
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
			// "Can ibuprofen be taken by her with her other drugs?" of her own drug (ADR Decision 430)
			"(?:can|could|may|should) " + D + " be (?:safely )?(?:given|taken|used|continued|combined)(?: (?:by|to|for) " + PATIENT
					+ ")? (?:with|alongside) (?:any of )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS,
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
		return fitsAShape(wordsWithTheDrugMarked, BESIDE_HER_MEDICATIONS_SHAPES)
				|| fitsAShape(wordsWithTheDrugMarked, CHANGE_HER_DRUGS_DOSE_SHAPES);
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
			// and "Does fluconazole mix well with nevirapine?" (ADR Decision 429)
			+ " (?:interact with|affect|clash with|mix (?:well )?with|go (?:well )?with) " + HER_D + "|(?:do|will|would|can) " + HER_D + " and " + HER_D
			+ " (?:interact|clash)(?: with (?:each other|one another))?"
			// "Do fluconazole, rifampicin and efavirenz interact?" — a list, its commas no words (ADR Decision 408)
			+ "|(?:do|will|would|can) (?:" + HER_D + " )+and " + HER_D + " (?:all )?(?:interact|clash)(?: with (?:each other|one "
			+ "another))?)";

	/** What a drug may change of another: its levels, its effect, its absorption (ADR Decisions 249, 251). */
	private static final String MEASURE = "(?:levels?|concentrations?|effects?|effectiveness|efficacy|toxicity|exposure"
			+ "|absorption|bioavailability|clearance|metabolism)";

	/** What a drug may make of another, asked as an effect: "make her ARVs less effective" (ADR Decision 431). */
	private static final String LESS_EFFECTIVE = "(?:less effective|ineffective|not work|stop working|less potent|more toxic|fail)";

	/**
	 * "Will fluconazole raise his warfarin levels?", "Does rifampicin reduce the effect of her nevirapine?", "Does
	 * metronidazole increase the bleeding risk with his warfarin?" — a question whether one drug CHANGES a particular effect
	 * of the other, which the data never states: it relates the pair, never the effect asked about (ADR Decision 249).
	 */
	private static final String DOES_ONE_CHANGE_THE_OTHER = "(?:does|do|will|would|can|could|might) " + HER_D
			+ " (?:increase|raise|elevate|boost|decrease|lower|reduce|change|alter|potentiate) (?:(?:the |" + POSSESSIVE + " )?"
			+ D + " " + MEASURE + "|(?:the )?(?:[a-z]+ )?(?:risk|" + MEASURE + ")(?: of [a-z]+)? (?:of|with|from|on|for) " + HER_D
			+ ")"
			// "Does rifampicin potentiate nevirapine?", answered "No — Rifampicin should not be given" (ADR Decision 440)
			// and "Does rifampicin decrease nevirapine?" (ADR Decision 446)
			+ "|(?:does|do|will|would|can|could|might) " + HER_D + " (?:potentiate|antagonize|antagonise|inhibit|induce|increase"
			+ "|decrease|reduce|lower|raise|boost|weaken) " + HER_D;

	/**
	 * What a drug does beside another, asked otherwise than as an effect (ADR Decision 251): whether to avoid the two
	 * together, what happens or what the risk is if they are combined, whether her drug's dose needs changing because of
	 * the other. Each answered as {@link #DOES_ONE_CHANGE_THE_OTHER} is, with neither "Yes" nor "No" — the model answered
	 * "Should he avoid codeine with his tramadol?" and "Should his warfarin dose be adjusted if I start fluconazole?" "No —".
	 */
	private static final String AVOID_ONE_WITH_THE_OTHER = "(?:(?:should|must) (?:i|we|she|he|they|the patient|this patient)"
			+ "|do (?:i|we) (?:need|have) to) avoid (?:giving |taking )?(?:" + PATIENT + " )?" + HER_D + " (?:with|alongside|and) "
			+ HER_D + "(?: together)?"
			// "Should rifampicin be avoided with her nevirapine?", whose "No" was "No — rifampicin should be avoided" (ADR
			// Decision 433)
			+ "|(?:should|must) " + HER_D + " be avoided (?:with|alongside|in combination with|together with) " + HER_D
			// "Does fluconazole have to be avoided with nevirapine?" (ADR Decision 434)
			+ "|(?:does|do) " + HER_D + " (?:have|need) to be avoided (?:with|alongside|in combination with|together with) " + HER_D;

	private static final String WHAT_COMBINING_THEM_DOES = "(?:what (?:happens|would happen|will happen) if (?:i|we) (?:give|add"
			+ "|start|combine|prescribe) (?:" + PATIENT + " )?(?:on )?" + HER_D + " (?:to|with|and|alongside) " + HER_D
			+ "|what (?:is|are) the (?:risks?|dangers?) (?:of|in) (?:combining|giving|using|taking|adding) " + HER_D
			+ " (?:and|with|to) " + HER_D
			// "What is the risk of giving clarithromycin to someone on efavirenz?" (ADR Decision 429)
			+ "|what (?:is|are) the (?:risks?|dangers?) (?:of|in) (?:giving|prescribing|adding|starting) " + HER_D + " (?:to|for|in) "
			+ "(?:someone|somebody|anyone|a patient|a person|this patient|her|him|them) (?:on|taking|who is on|who is taking|who "
			+ "takes) " + HER_D
			// "If she starts rifampicin, what happens to her nevirapine?" (ADR Decision 431)
			+ "|if (?:i|we|she|he|they|the patient) (?:start|starts|give|add|take|takes|begin|begins) (?:" + PATIENT + " )?(?:on )?"
			+ HER_D + " what (?:happens|will happen|would happen) (?:to|with) " + HER_D
			// "Rifampicin with nevirapine - what's the risk?", "What happens to efavirenz levels with rifampicin?" (ADR Decision
			// 441)
			+ "|" + HER_D + " (?:and|with|plus) " + HER_D + " (?:what's|what’s|what is|what are) the (?:risks?|dangers?|problems?)"
			+ "|what (?:happens|will happen|would happen) to (?:the |" + POSSESSIVE + " )?" + D + "(?: " + MEASURE + ")? (?:with|on|"
			+ "alongside|if (?:i|we) (?:add|give|start|prescribe)) " + HER_D + ")(?: together)?";

	/** "Is clarithromycin dangerous with her simvastatin?", "Is it risky to combine X and Y?", "Could metronidazole cause
	 *  problems with his warfarin?", "Is the combination of warfarin and amiodarone dangerous?" — whether the two are
	 *  dangerous together, whose "No" would say they are not (ADR Decision 255). */
	private static final String DANGEROUS_TOGETHER = "(?:is " + HER_D + " (?:dangerous|risky|unsafe|harmful|contraindicated"
			+ "|a problem|problematic) (?:with|alongside|in combination with) " + HER_D + "|is it (?:dangerous|risky|unsafe|harmful"
			+ "|a problem) to (?:combine|give|take|use) " + HER_D + " (?:and|with) " + HER_D + "(?: together)?|(?:could|would|will"
			+ "|might|can) " + HER_D + " cause (?:any )?(?:problems?|issues?|harm) (?:with|for|alongside) " + HER_D + "|(?:would"
			+ "|will|could) " + HER_D + " be (?:harmful|dangerous|risky|unsafe|a problem) (?:with|alongside) " + HER_D
			+ "|(?:is|are) (?:the combination of )?" + HER_D + " (?:and|with) " + HER_D + " (?:a )?(?:dangerous|risky|unsafe"
			+ "|harmful)(?: combination)?"
			// "Is ibuprofen and aspirin together a problem?", answered "No — … should not be given together" (ADR Decision 433)
			+ "|(?:is|are) " + HER_D + " (?:and|with|plus) " + HER_D + " (?:together )?(?:a problem|an issue|a concern|problematic|"
			+ "dangerous|risky|harmful|unsafe)(?: together)?"
			// "Is the fluconazole-nevirapine combination dangerous?" (ADR Decision 429)
			+ "|is the " + HER_D + " (?:and |plus |with )?" + HER_D + " (?:combination|combo) (?:dangerous|risky|unsafe|harmful)"
			// "Is fluconazole going to be a problem with her nevirapine?", "Fluconazole with rifampicin - is that a problem?" (ADR
			// Decision 432)
			+ "|is " + HER_D + " going to be (?:a problem|an issue|dangerous|risky|harmful|unsafe) (?:with|alongside|for) " + HER_D
			+ "|" + HER_D + " (?:and|with|plus) " + HER_D + " (?:is|would be|will be) (?:that|this|it) (?:a problem|an issue|"
			+ "dangerous|risky|harmful|unsafe)"
			// "Is it contraindicated to give rifampicin with nevirapine?", "Are rifampicin and nevirapine contraindicated
			// together?", each answered "No — … should not be given" (ADR Decision 434)
			+ "|is it contraindicated to (?:give|combine|use|take|prescribe) " + HER_D + " (?:and|with|alongside) " + HER_D
			+ "(?: together)?"
			+ "|(?:is|are) " + HER_D + " (?:and|with|plus) " + HER_D + " contraindicated(?: together| with each other| in combination)?"
			// "Are rifampicin and nevirapine a bad combination?", "Is rifampicin bad with nevirapine?" (ADR Decision 453)
			+ "|(?:is|are) " + HER_D + " (?:and|with|plus) " + HER_D + " (?:a )?bad (?:combination|combo|mix|idea)"
			+ "|is " + HER_D + " bad (?:with|alongside|for|in combination with) " + HER_D
			// "Is there a risk of myopathy with clarithromycin and simvastatin?", "Heparin and aspirin together - bleeding risk?"
			// (ADR Decision 447)
			+ "|is there (?:a |any )?(?:increased |higher )?risk of (?:\\p{L}+ ){1,3}(?:with|when|if|from) (?:(?:combining|giving|"
			+ "taking|using) )?" + HER_D + " (?:and|with|plus) " + HER_D + "(?: together)?"
			+ "|" + HER_D + " (?:and|with|plus) " + HER_D + "(?: together)? (?!any )\\p{L}+ (?:risks?|problems?|concerns?)"
			// "Should rifampicin and nevirapine not be given together?" (ADR Decision 446)
			+ "|(?:should|must) " + HER_D + " and " + HER_D + " not be (?:given|taken|used|combined|prescribed)(?: together| at the "
			+ "same time)?"
			+ "|(?:should|must) " + HER_D + " not be (?:given|taken|used|combined|prescribed) (?:with|alongside) " + HER_D
			// "Is there a problem combining rifampicin with nevirapine?" (ADR Decision 445)
			+ "|is there (?:a |any )?(?:problems?|issues?|risks?|dangers?|harm|concerns?) (?:(?:in|with) )?(?:combining|giving|mixing|"
			+ "using|taking) " + HER_D + " (?:and|with|alongside) " + HER_D + "(?: together)?"
			// "How bad is rifampicin with nevirapine?", "Clarithromycin while on nevirapine - risky?" (ADR Decision 444)
			+ "|how (?:bad|dangerous|risky|harmful|serious|safe) is " + HER_D + " (?:with|alongside|and|plus|while on|while taking) "
			+ HER_D
			+ "|" + HER_D + " (?:with|and|plus|while on|while taking) " + HER_D + " (?:risky|dangerous|harmful|unsafe|a problem)"
			// "Any issue mixing rifampicin and efavirenz?", "Rifampicin + efavirenz combination?" (ADR Decision 440)
			+ "|(?:(?:is|are) there )?any (?:problems?|issues?|concerns?|risks?) (?:with |in )?(?:mixing|combining|giving|using|taking) "
			+ HER_D + " (?:and|with) " + HER_D + "(?: together)?"
			+ "|" + HER_D + " (?:and |plus |with )?" + HER_D + " (?:combination|combo)"
			// "Is it risky to give clarithromycin to someone on efavirenz?", answered "Clarithromycin can be given" (ADR Decision
			// 441)
			+ "|is it (?:dangerous|risky|unsafe|harmful|a problem) to (?:give|prescribe|start|add) " + HER_D + " to (?:someone|"
			+ "somebody|anyone|a patient|a person|this patient|her|him|them) (?:on|taking|who is on|who is taking|who takes) " + HER_D
			+ ")";

	/** "Do I need to stop her simvastatin before starting clarithromycin?", "Can she continue her simvastatin if I start
	 *  clarithromycin?" — whether to stop or keep her drug for another (ADR Decision 252). */
	private static final String STOP_OR_KEEP_ONE_FOR_THE_OTHER = "(?:(?:(?:do|will) (?:i|we) (?:need|have) to|should (?:i|we))"
			+ " (?:stop|hold|pause|suspend|discontinue) " + HER_D + " (?:before|when|while|if) (?:starting|giving|adding|prescribing"
			// "… while she is on fluconazole" (ADR Decision 428)
			+ "|(?:i|we) (?:start|give|add|prescribe)|(?:she|he|they) (?:is|are) (?:on|taking)) " + D
			// "Should she stop aspirin if she takes ibuprofen?" (ADR Decision 444)
			+ "|(?:should|must) (?:she|he|they|the patient|this patient) stop (?:taking )?" + HER_D + " (?:if|when|while|before) "
			+ "(?:she|he|they) (?:takes|take|starts|start|is on|are on|is given|are given|gets|get) " + HER_D
			// "Do I need to stop nevirapine for rifampicin?", answered "No — Nevirapine should not be given" (ADR Decision 441)
			+ "|(?:(?:(?:do|will) (?:i|we) (?:need|have) to|should (?:i|we)) (?:stop|hold|pause|suspend|discontinue) " + HER_D
			+ " (?:for|because of|due to|with) " + HER_D + ")"
			+ "|(?:can|could|should) (?:she|he|they|the patient|this patient)"
			+ " (?:continue|keep taking|stay on|remain on) " + HER_D + " (?:if|when|while) (?:(?:i|we) (?:start|give|add|prescribe)"
			// and "Can she continue efavirenz if she starts rifampicin?" (ADR Decision 445)
			+ "|(?:she|he|they) (?:starts|start|takes|take|begins|begin|is given|are given|gets|get)) " + D + ")";

	/** "Does she need potassium monitoring on lisinopril and spironolactone?", "Does she need monitoring if I add
	 *  spironolactone to her lisinopril?" — whether the two need monitoring (ADR Decision 252). */
	private static final String MONITORING_ON_BOTH = "(?:does|do|will|would) (?:she|he|they|the patient|this patient) need"
			+ " (?:any )?(?:[a-z]+ )?monitoring (?:(?:on|with|for|while on) " + HER_D + " and " + HER_D + "(?: together)?|(?:if|when)"
			+ " (?:i|we) (?:add|give|start|prescribe) " + D + " (?:to|with) " + HER_D + ")"
			// "Fluconazole and nevirapine - should I monitor anything?", "What should I monitor if she takes fluconazole with
			// nevirapine?" (ADR Decision 431)
			+ "|" + HER_D + " (?:and|with|plus) " + HER_D + " (?:should|do|must) (?:i|we) (?:need to )?monitor (?:for )?(?:anything|"
			+ "something)"
			+ "|what (?:should|do|must) (?:i|we) (?:need to )?monitor(?: for)? (?:if|when) (?:(?:she|he|they|the patient) (?:takes|"
			+ "take|is on|are on|is taking)|(?:i|we) (?:give|add|start|prescribe) (?:" + PATIENT + " )?) " + HER_D + " (?:with|and|"
			+ "alongside) " + HER_D;

	/** "Is efavirenz OK with rifampicin or do I need to switch?" — safe together, or whether to change hers: one or the other,
	 *  which neither "Yes" nor "No" answers, and no finding chooses a drug to change (ADR Decision 431). */
	private static final String SAFE_TOGETHER_OR_SWITCH = "is " + HER_D + " (?:ok|okay|safe|fine) (?:with|alongside) " + HER_D
			+ " or (?:do|should|must) (?:i|we) (?:need to )?(?:switch|change|stop|swap)(?: (?:it|" + HER_D + "))?";

	/** "Rifampicin and efavirenz: dose adjustment needed?" — whether either dose changes, which no finding decides (ADR
	 *  Decision 432). */
	private static final String DOSE_ADJUSTMENT_FOR_THE_PAIR = HER_D + " (?:and|with|plus) " + HER_D + " (?:any )?(?:dose|"
			// and "Rifampicin with efavirenz - any dose change?" (ADR Decision 444)
			+ "dosage) (?:adjustments?|changes?)(?: (?:needed|required|necessary))?"
			// "What dose of efavirenz with rifampicin?" (ADR Decision 433)
			+ "|(?:what|which) (?:dose|dosage) of " + HER_D + " (?:should (?:i|we) (?:give|use) |(?:is|would be) (?:needed|required|"
			+ "safe) )?(?:with|alongside|on) " + HER_D
			// "Efavirenz dose with rifampicin?", "Does efavirenz need adjusting with rifampicin?" (ADR Decision 446)
			+ "|(?:the )?" + HER_D + " (?:dose|dosage|dosing) (?:with|alongside|on) " + HER_D
			+ "|(?:does|do|will|would) " + HER_D + " (?:dose )?(?:need|require) (?:adjusting|adjustment|a dose adjustment|dose adjustment|"
			+ "changing|to be adjusted|to be changed) (?:with|alongside|on|because of|due to) " + HER_D
			// "Should I double the efavirenz dose with rifampicin?", answered "Efavirenz can be given" (ADR Decision 441)
			+ "|(?:should|must|do) (?:i|we) (?:need to )?(?:double|halve|increase|raise|reduce|lower|decrease|adjust|change) (?:the |"
			+ POSSESSIVE + " )?(?:" + D + " (?:dose|dosage)|(?:dose|dosage) of " + HER_D + ") (?:with|because of|due to|on|alongside|"
			+ "for) " + HER_D;

	/** "Is it safe to continue efavirenz while she is on rifampicin?", "Can she stay on nevirapine with rifampicin?" — whether
	 *  to keep her drug beside the other (ADR Decision 432). */
	private static final String KEEP_HERS_BESIDE_THE_OTHER = "(?:is it (?:safe|ok|okay|fine) to (?:continue |keep (?:giving |"
			+ "taking )?)(?:" + PATIENT + " on )?" + HER_D + " (?:while|when|if) (?:she|he|they|the patient) (?:is|are) (?:on|taking) "
			+ HER_D + "|(?:can|could|should) (?:she|he|they|the patient|this patient) (?:stay on|continue|keep taking|remain on) "
			+ HER_D + " (?:with|alongside|while on|while taking) " + HER_D
			// "Can nevirapine be continued with rifampicin?" (ADR Decision 441)
			+ "|(?:can|could|should|may) " + HER_D + " be (?:safely )?(?:continued|kept|maintained) (?:with|alongside|while on|while "
			+ "taking|if (?:i|we) (?:start|give|add|prescribe)) " + HER_D + ")";

	/** "Is his warfarin dose safe with his amiodarone?" — whether her drug's dose is safe beside another (ADR Decision 258). */
	private static final String DOSE_SAFE_WITH_THE_OTHER = "is " + POSSESSIVE + " " + D + " dose (?:still )?(?:safe|ok|okay"
			+ "|appropriate|fine) (?:with|alongside|beside|given) (?:the |" + POSSESSIVE + " )?" + D;

	private static final String DOSE_BECAUSE_OF_THE_OTHER = "should " + POSSESSIVE + " " + D + " dose be (?:reduced|lowered"
			+ "|decreased|increased|raised|adjusted|changed)(?: (?:because of|due to|with|for) (?:the |" + POSSESSIVE + " )?" + D
			+ "| if (?:i|we) (?:add|give|start|prescribe) " + D + ")";

	/** "Any interactions between ibuprofen and celecoxib?", "Is there an interaction between X and Y?" (ADR Decisions 203, 218). */
	private static final String ANY_INTERACTION_BETWEEN = "(?:are there |is there )?(?:any |an )?(?:drug )?interactions? between "
			+ HER_D + " and " + HER_D;

	/** How to manage the interaction of two drugs (ADR Decision 418): no finding advises it, and the answer says so. */
	private static final String MANAGE_THE_INTERACTION = "(?:(?:how (?:do|should|can|would) (?:i|we) manage|what (?:should|do|can"
			+ "|would) (?:i|we) do about) (?:the|this|that) (?:interactions? between " + HER_D + " and " + HER_D
			// "What should I do about the rifampicin nevirapine interaction?" (ADR Decision 427)
			+ "|" + HER_D + " (?:and |plus |with )?" + HER_D + " (?:drug )?interactions?)"
			// "What should I do if she needs rifampicin and is on nevirapine?" (ADR Decision 444)
			+ "|what (?:should|do|can|would) (?:i|we) do if (?:she|he|they|the patient) (?:needs?|requires?) " + HER_D + " and (?:is|are) "
			+ "(?:on|taking) " + HER_D + ")";

	/** Whether {@code words} ask how to manage the interaction of two drugs (ADR Decision 418). */
	public static boolean asksHowToManageAnInteraction(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, shapes(MANAGE_THE_INTERACTION));
	}

	/** Which of two drugs to stop, or which causes their interaction (ADR Decision 411). */
	private static final String WHICH_OF_TWO_TO_STOP = "(?:which (?:drug |one |medication |medicine |of them |of the two )?"
			+ "(?:should|do|must) (?:i|we) (?:stop|change|discontinue|hold|switch) " + HER_D + " or " + HER_D
			+ "|(?:is|are) " + HER_D + " or " + HER_D + " (?:causing|responsible for) (?:the|this|that) interactions?)";

	/** Whether {@code words} ask which of two drugs to stop, or which causes their interaction (ADR Decision 411): no finding
	 *  chooses one, and the answer says so. */
	public static boolean asksWhichOfTwoToStop(List<String> wordsWithTheDrugsMarked) {
		return fitsAShape(wordsWithTheDrugsMarked, shapes(WHICH_OF_TWO_TO_STOP));
	}

	/** "Should I change her efavirenz if I start rifampicin?" — asked as whether the two interact, the module choosing no drug
	 *  to change (ADR Decision 402); of two of hers too (ADR Decision 404). */
	private static final String CHANGE_HERS_IF_STARTING = "(?:should|must|do) (?:i|we) (?:need to )?(?:change|stop|switch|adjust|"
			+ "modify|reduce|lower|decrease|increase|hold|pause) " + HER_D + "(?: dose)? if (?:i|we) (?:start|give|add|prescribe) (?:"
			+ PATIENT + " )?(?:on )?" + HER_D
			// "Should I change her nevirapine because of rifampicin?", "Does she need a dose change of efavirenz with
			// rifampicin?" (ADR Decision 427)
			+ "|(?:should|must|do) (?:i|we) (?:need to )?(?:change|stop|switch|adjust|modify|reduce|lower|decrease|increase|hold"
			+ "|pause) " + HER_D + "(?: dose)? (?:because of|due to) " + HER_D
			+ "|(?:does|do|will|would) (?:she|he|they|the patient|this patient) need (?:a |any )?(?:dose|dosage) (?:change|adjustment"
			+ "|increase|reduction|modification) (?:of|for|to) " + HER_D + " (?:with|because of|due to|on|if (?:i|we) (?:start|give|"
			+ "add|prescribe)) " + HER_D
			// "Which drug should I stop, aspirin or ibuprofen?", "Is aspirin or ibuprofen causing the interaction?" — what relates
			// them, under a lead choosing neither (ADR Decision 411)
			+ "|" + WHICH_OF_TWO_TO_STOP
			// "Explain the interaction between X and Y", "How do I manage the interaction between X and Y?" (ADR Decision 418)
			+ "|(?:please )?explain the interactions? between " + HER_D + " and " + HER_D
			+ "|" + MANAGE_THE_INTERACTION;

	private static final List<Pattern> TWO_DRUGS_SHAPES = shapes(
			// "Is it safe to combine ibuprofen and celecoxib?", "Is it ok to give ibuprofen with celecoxib together?"
			"is it (?:safe|ok|okay|appropriate) to (?:combine|give|take|use) " + HER_D + " (?:and|with) " + HER_D
					+ "(?: together)?",
			DOES_ONE_INTERACT_WITH_THE_OTHER, CHANGE_HERS_IF_STARTING,
			DOES_ONE_CHANGE_THE_OTHER, AVOID_ONE_WITH_THE_OTHER, WHAT_COMBINING_THEM_DOES, DOSE_BECAUSE_OF_THE_OTHER,
			STOP_OR_KEEP_ONE_FOR_THE_OTHER, MONITORING_ON_BOTH, DANGEROUS_TOGETHER, DOSE_SAFE_WITH_THE_OTHER, SAFE_TOGETHER_OR_SWITCH,
			DOSE_ADJUSTMENT_FOR_THE_PAIR, KEEP_HERS_BESIDE_THE_OTHER,
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

	private static final List<Pattern> TWO_DRUGS_INTERACT_SHAPES = shapes(DOES_ONE_INTERACT_WITH_THE_OTHER, ANY_INTERACTION_BETWEEN,
			CHANGE_HERS_IF_STARTING,
			// "Fluconazole and nevirapine", "Fluconazole plus nevirapine" — two names and a joining word alone (ADR Decision 413)
			HER_D + " (?:and|plus|with) " + HER_D,
			// "Any interaction: ibuprofen, aspirin?", "Interaction check: rifampicin and efavirenz" (ADR Decision 453)
			"(?:(?:are|is) there )?(?:any )?(?:drug )?interactions? (?:check )?(?:for |on |of |between )?" + HER_D + " (?:and )?" + HER_D,
			// and "fluconazole nevirapine", the two names alone (ADR Decision 428)
			D + " " + D,
			// "Can I give fluconazole after she finishes rifampicin?", "Is it safe to give fluconazole and then rifampicin later?"
			// — what relates the two; when each is given is a timing no finding weighs (ADR Decision 414)
			"(?:can|could|may) (?:i|we) (?:give|start) (?:" + PATIENT + " )?(?:on )?" + HER_D + " after (?:she|he|they) (?:finishes|"
					+ "stops|completes|has (?:finished|stopped|completed)|is done with) " + HER_D,
			"is it (?:safe|ok|okay|fine) to (?:give|start) (?:" + PATIENT + " )?(?:on )?" + HER_D + " and then " + HER_D
					+ "(?: later)?");

	/** "What is the mechanism of the interaction between simvastatin and clarithromycin?" — what relates the two, asked of
	 *  the interaction itself (ADR Decision 264). */
	private static final List<Pattern> ABOUT_THE_PAIRS_INTERACTION_SHAPES = shapes("(?:what|which) (?:is|are) the (?:mechanisms?"
			+ "|cause|reason|basis) (?:of|for|behind) (?:the )?(?:drug )?interactions? between " + HER_D + " and " + HER_D,
			// "What is the interaction between rifampicin and her lopinavir?", "What interactions are there between X and Y?",
			// "How do X and Y interact?", "How does X interact with her Y?" — what it is, asked without the word "mechanism"
			// (ADR Decision 289)
			"(?:what|which) (?:is|are) the (?:drug )?interactions? between " + HER_D + " and " + HER_D,
			"(?:what|which) (?:drug )?interactions? (?:is|are) there between " + HER_D + " and " + HER_D,
			// "Why can't rifampicin be given with nevirapine?" (ADR Decision 441)
			"why (?:can't|can’t|cant|cannot|can not|shouldn't|shouldn’t|shouldnt|should not) " + HER_D + " be (?:given|taken|used|"
					+ "combined|prescribed) (?:with|alongside|and) " + HER_D + "(?: together)?",
			// "Is there a dangerous interaction between rifampicin and nevirapine?", answered "No — rifampicin should not be
			// given" (ADR Decision 433)
			"(?:is|are) there (?:a |an |any )?(?:clinically )?(?:dangerous|serious|major|significant|severe|important|relevant) "
					+ "(?:drug )?interactions? between " + HER_D + " and " + HER_D,
			// "Any concerns with rifampicin + efavirenz?", "Is there an interaction if I add fluconazole to nevirapine?", "How
			// serious is the fluconazole nevirapine interaction?", "Is the interaction between rifampicin and nevirapine major?"
			// (ADR Decision 424)
			"(?:(?:are|is) there )?any (?:concerns?|problems?|issues?|risks?) (?:with|combining|giving) " + HER_D
					+ " (?:and|plus|with) " + HER_D + "(?: together)?",
			// "Rifampicin and nevirapine - any issue?" (ADR Decision 426)
			HER_D + " (?:and|with|plus) " + HER_D + " (?:(?:is|are) there )?any (?:concerns?|problems?|issues?|risks?)",
			"(?:is|are) there (?:an |any )?(?:drug )?interactions? if (?:i|we) (?:add|give|start) " + HER_D + " (?:to|with) " + HER_D,
			"how (?:severe|serious|bad|significant|dangerous|major|important) is the " + HER_D + " (?:and |plus |with )?" + HER_D
					+ " (?:drug )?interaction",
			"is the (?:drug )?interaction between " + HER_D + " and " + HER_D + " (?:major|serious|severe|significant|dangerous|"
					+ "moderate|minor|important)",
			// "Is fluconazole + nevirapine a major interaction?" (ADR Decision 431)
			"is (?:the )?" + HER_D + " (?:and |plus |with )?" + HER_D + " (?:a |an )?(?:major|serious|severe|significant|dangerous|"
					+ "moderate|minor|important) (?:drug )?interaction",
			// "rifampicin vs nevirapine interaction", "Interaction between rifampicin and nevirapine?" (ADR Decision 423)
			// and "Rifampicin, nevirapine - interaction?", the comma no word (ADR Decision 448)
			HER_D + " (?:(?:vs|versus|and|with|plus) )?" + HER_D + " (?:drug )?interactions?",
			"(?:the )?(?:drug )?interactions? between " + HER_D + " and " + HER_D,
			"how (?:do|does|would|will) " + HER_D + " and " + HER_D + " interact(?: with each other)?",
			"how (?:do|does|would|will) " + HER_D + " interact with " + HER_D,
			// "How severe is the interaction between her rifampicin and nevirapine?", "What is the severity of the interaction
			// between X and Y?" — the findings carry the rating (ADR Decision 331)
			"how (?:severe|serious|bad|significant|dangerous|major|important) is the (?:drug )?interaction between " + HER_D
					+ " and " + HER_D,
			"(?:what|which) is the (?:severity|seriousness|significance|rating) of the (?:drug )?interaction between " + HER_D
					+ " and " + HER_D,
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

	/** "Will clarithromycin interfere with her simvastatin?" — what {@link #DOES_ONE_CHANGE_THE_OTHER} asks, of no named
	 *  effect; the model answered it "No — Simvastatin should not be given" (ADR Decision 360). */
	private static final String DOES_ONE_INTERFERE_WITH_THE_OTHER = "(?:does|do|will|would|can|could|might) " + HER_D
			+ " interfere with " + HER_D;

	private static final List<Pattern> ONE_DRUG_CHANGES_THE_OTHER_SHAPES = shapes(DOES_ONE_CHANGE_THE_OTHER,
			// "Will rifampicin affect her efavirenz levels?" (ADR Decision 391)
			"(?:does|do|will|would|can|could|might) " + HER_D + " affect (?:the )?(?:" + POSSESSIVE + " )?" + D + "(?: " + MEASURE
					+ ")?",
			DOES_ONE_INTERFERE_WITH_THE_OTHER,
			// "Will rifampicin make her nevirapine less effective?" (ADR Decision 431)
			"(?:does|do|will|would|can|could|might) " + HER_D + " make " + HER_D + " " + LESS_EFFECTIVE,
			// "Will giving rifampicin harm her nevirapine treatment?" (ADR Decision 448)
			"(?:does|do|will|would|could|might|can) (?:giving|starting|adding|prescribing) " + HER_D + " (?:harm|affect|damage|"
					+ "interfere with|compromise|undermine|weaken) " + HER_D + "(?: (?:treatment|therapy|regimen))?",
			// "Is efavirenz affected by rifampicin?" (ADR Decision 446)
			"(?:is|are|will|would) " + HER_D + " (?:be )?(?:affected|changed|reduced|weakened|altered|influenced) by " + HER_D,
			// "Will nevirapine levels drop with rifampicin?" (ADR Decision 445)
			"(?:does|do|will|would|can|could|might) (?:the |" + POSSESSIVE + " )?" + D + " " + MEASURE + " (?:drop|fall|decrease|go down|"
					+ "rise|increase|go up|change) (?:with|on|alongside|if (?:i|we) (?:add|give|start|prescribe)) " + HER_D,
			// "Will rifampicin cause her nevirapine to fail?", "Is efavirenz still effective with rifampicin?" (ADR Decision 444)
			"(?:does|do|will|would|can|could|might) " + HER_D + " cause " + HER_D + " to (?:fail|stop working|not work|be less "
					+ "effective|work less)",
			// and "Does rifampicin work with her nevirapine?" (ADR Decision 453)
			"(?:does|do) " + HER_D + " (?:still )?work (?:with|alongside|on) " + HER_D,
			"(?:is|are|will|would) " + HER_D + " (?:still )?(?:be )?(?:as )?(?:effective|work|working) (?:with|alongside|on|if (?:i|we) "
					+ "(?:add|give|start|prescribe)) " + HER_D,
			AVOID_ONE_WITH_THE_OTHER, WHAT_COMBINING_THEM_DOES, DOSE_BECAUSE_OF_THE_OTHER, STOP_OR_KEEP_ONE_FOR_THE_OTHER,
			MONITORING_ON_BOTH, DANGEROUS_TOGETHER, DOSE_SAFE_WITH_THE_OTHER, SAFE_TOGETHER_OR_SWITCH, DOSE_ADJUSTMENT_FOR_THE_PAIR,
			KEEP_HERS_BESIDE_THE_OTHER);

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
			// and "Will starting rifampicin affect her ARVs?" (ADR Decision 428)
			"(?:does|do|will|would|can|could|might) (?:starting |giving |adding )?" + D + " (?:increase|raise|elevate|boost|decrease"
					+ "|lower|reduce|change|alter|potentiate|affect) (?:the " + MEASURE + " of )?(?:any of )?" + HER_MEDICATION_GROUP
					+ "(?: " + MEASURE + ")?",
			// "Will fluconazole cause problems with her HIV drugs?" — whose "No" would say it will not (ADR Decision 433)
			"(?:does|do|will|would|can|could|might) " + D + " cause (?:any )?(?:problems?|issues?|harm) (?:with|for|alongside) "
					+ "(?:any of )?" + HER_MEDICATION_GROUP,
			// "Will rifampicin make her ARVs less effective?" (ADR Decision 431)
			"(?:does|do|will|would|can|could|might) (?:starting |giving |adding )?" + D + " make (?:any of )?" + HER_MEDICATION_GROUP
					+ " " + LESS_EFFECTIVE);

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
	private static final String FREQUENCY = "(?:(?:once|twice|thrice|(?:one|two|three|four|\\d+) times?) (?:a|per|each|every) "
			// "once a week", "weekly" (ADR Decision 428)
			+ "(?:day|week|month)|(?:once |twice )?weekly"
			+ "|(?:once |twice )?daily|bd|bid|tds|tid|qds|qid|od|nocte|stat|prn|(?:every|each) (?:\\d+ )?(?:hours?|hrs?)"
			+ "|\\d+ hourly|q\\d+h|at night|in the morning)";

	/** A dose stated before the drug, "1 g of paracetamol", and after it, "amoxicillin 500 mg three times a day". */
	private static final Pattern DOSE_BEFORE_THE_DRUG = Pattern.compile("(?<=^| )" + AMOUNT + "(?: of)? (" + D + ")");

	private static final Pattern DOSE_AFTER_THE_DRUG = Pattern.compile("(" + D + ") (?:" + AMOUNT + "(?: " + FREQUENCY + ")?|"
			// how often alone, "fluconazole once a week" (ADR Decision 428)
			+ FREQUENCY + ")(?= |$)");

	/**
	 * "Is it safe to increase her simvastatin dose?", "Is it safe to increase her simvastatin to 40 mg?", "Should I reduce
	 * her simvastatin dose?" — whether to change the dose of her drug (ADR Decision 346): asked about her drug beside her
	 * medications, and a dose the findings do not weigh.
	 */
	private static final List<Pattern> CHANGE_HER_DRUGS_DOSE_SHAPES = shapes("(?:is it (?:safe|ok|okay|alright|appropriate|"
			+ "fine) to|(?:can|could|may|should) (?:i|we)(?: safely)?) (?:increase|raise|double|reduce|lower|decrease|halve) "
			+ POSSESSIVE + " " + D + "(?: (?:dose|dosage))?(?: to " + AMOUNT + ")?(?: now)?");

	/** Whether a question asks whether to change the dose of its one drug, named as hers (ADR Decision 346). Fail-CLOSED. */
	public static boolean asksWhetherToChangeHerDrugsDose(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, CHANGE_HER_DRUGS_DOSE_SHAPES);
	}

	/** An amount stated in words before the drug — "more lidocaine", "a second dose of neomycin" (ADR Decision 326). */
	private static final Pattern AMOUNT_IN_WORDS_BEFORE_THE_DRUG = Pattern.compile("(?<=^| )(?:more|extra|additional|"
			// and "a stat dose of", "a loading dose of" (ADR Decision 429)
			+ "(?:another|a second|a further|an extra|an additional|a repeat|a single|one|a one off|a stat|a loading) dose of) (" + D
			+ ")");

	/** A form named right after the drug — "ketoconazole cream", "chloramphenicol eye drops" (ADR Decision 334) — and the
	 *  article before it, which the form carried: "a lidocaine injection" (ADR Decision 447). */
	private static final Pattern FORM_AFTER_THE_DRUG = Pattern.compile("(?:(?<=^| )(?:a|an|some) )?(" + D + ") (cream|ointment|gel"
			+ "|lotion|eye drops|ear "
			+ "drops|drops|spray|nasal spray|inhaler|patch|patches|suppository|suppositories|pessary|pessaries|injection|"
			+ "syrup|suspension|tablets?|capsules?"
			// and what a herb or a fruit is taken as — "chamomile tea", "cranberry juice", "garlic supplements": "Can she have
			// chamomile tea?" went to the model, which answered "Chamomile can be given, with one caution" (ADR Decision 438)
			+ "|tea|teas|juice|supplements?|extract|powder"
			// a route or a use after it — "ciprofloxacin IV", "isoniazid preventive therapy" (ADR Decision 419)
			+ "|iv|im|intravenously|intramuscularly|orally|topically|prophylaxis|preventive therapy|preventative therapy)(?= |$)");

	/** A route named right before the drug — "oral fluconazole", "topical clotrimazole" (ADR Decision 419). */
	private static final Pattern FORM_BEFORE_THE_DRUG = Pattern.compile("(?<=^| )(oral|topical|intravenous|iv|im|intramuscular|"
			+ "inhaled|vaginal|rectal) (" + D + ")");

	/** A course of the drug named before it — "a short course of ciprofloxacin" (ADR Decision 419): how long it is given. */
	private static final Pattern COURSE_BEFORE_THE_DRUG = Pattern.compile("(?<=^| )(?:a )?(?:short |long |full |\\p{N}+ day )?"
			+ "course of (" + D + ")");

	/** {@code joined} without the form named beside its drug, or {@code null} where it names none (ADR Decision 334). */
	private static String withoutAStatedForm(String joined) {
		String without = COURSE_BEFORE_THE_DRUG.matcher(FORM_BEFORE_THE_DRUG.matcher(FORM_AFTER_THE_DRUG.matcher(joined)
				.replaceAll("$1")).replaceAll("$2")).replaceAll("$1");
		return without.equals(joined) ? null : without;
	}

	/**
	 * The form the question names beside its drug — "cream" of "Can I give her ketoconazole cream?" — or {@code null}
	 * (ADR Decision 334). The shapes read past it; the findings are the drug's and weigh no form, which the answer says.
	 */
	public static String statedForm(List<String> wordsWithTheDrugMarked) {
		if (wordsWithTheDrugMarked == null) {
			return null;
		}
		Matcher form = FORM_AFTER_THE_DRUG.matcher(String.join(" ", wordsWithTheDrugMarked));
		if (form.find()) {
			return form.group(2);
		}
		Matcher before = FORM_BEFORE_THE_DRUG.matcher(String.join(" ", wordsWithTheDrugMarked));
		return before.find() ? before.group(1) : null;
	}

	/** {@code joined} without the dose stated beside its drug, or {@code null} where it states none (ADR Decisions 269, 326). */
	private static String withoutAStatedDose(String joined) {
		String without = DOSE_AFTER_THE_DRUG.matcher(DOSE_BEFORE_THE_DRUG.matcher(
				AMOUNT_IN_WORDS_BEFORE_THE_DRUG.matcher(joined).replaceAll("$1")).replaceAll("$1")).replaceAll("$1");
		return without.equals(joined) ? null : without;
	}

	/**
	 * Whether the question, its drug marked {@link #DRUG_NAME}, states a dose beside it — "1 g of paracetamol",
	 * "amoxicillin 500 mg three times a day" (ADR Decision 269). The shapes read past it, so the findings the drug raises
	 * are raised; the findings weigh no dose, so the model answers such a question.
	 */
	public static boolean statesADose(List<String> wordsWithTheDrugMarked) {
		// Or asks for one — "What dose of ibuprofen is safe …?" (ADR Decision 394)
		return wordsWithTheDrugMarked != null && (withoutAStatedDose(String.join(" ", wordsWithTheDrugMarked)) != null
				|| asksWhetherToChangeHerDrugsDose(wordsWithTheDrugMarked)
				|| Pattern.compile("(?:^| )(?:what|which) (?:dose|dosage|amount) of " + D + "(?: |$)")
						.matcher(String.join(" ", wordsWithTheDrugMarked)).find());
	}

	/** Whether {@code question} says when the drug would be given — "two hours after her other medicines" — which no
	 *  finding weighs (ADR Decision 394). */
	public static boolean statesATiming(String question) {
		return question != null && Pattern.compile("(?:^| )(?:" + TIMING + "|" + SEQUENCE + ")(?: |$)")
				.matcher(String.join(" ", words(question))).find();
	}

	/** How long a drug would be given — "2 weeks", "a few days" (ADR Decision 419). */
	private static final String A_DURATION = "(?:\\p{N}+|one|two|three|four|five|six|seven|ten|fourteen|a|a few|several) "
			+ "(?:days?|weeks?|months?)";

	/** Whether {@code question} says how long the drug would be given — "for 2 weeks", "a short course of" — which no finding
	 *  weighs (ADR Decision 419). */
	public static boolean statesADuration(String question) {
		return question != null && Pattern.compile("(?:^| )(?:for " + A_DURATION + "|(?:a )?(?:short |long |full |\\p{N}+ day )?"
				+ "course of)(?: |$)").matcher(String.join(" ", words(question))).find();
	}

	/** One drug given after another — "after she finishes rifampicin", "and then rifampicin later" — a timing no finding
	 *  weighs (ADR Decision 414). */
	private static final String SEQUENCE = "(?:after (?:she|he|they) (?:finishes|stops|completes|has (?:finished|stopped|"
			+ "completed)|is done with)|and then \\p{L}+(?: \\p{L}+)? later)";

	/** The condition a proposal names — "kidney disease" of "Is ibuprofen safe given her kidney disease?" — as the
	 *  question writes it, or {@code null} (ADR Decision 394). */
	public static String statedConditionAsWritten(String question, List<String> wordsWithTheDrugMarked) {
		Matcher m = Pattern.compile("^is " + D + " (?:safe|ok|okay|appropriate|suitable)(?: (?:for|in) " + PATIENT
				+ ")? " + GIVEN_A_CONDITION + "(?!.*allerg)(\\p{L}+(?: \\p{L}+){0,3})$")
				.matcher(String.join(" ", wordsWithTheDrugMarked));
		return m.find() ? asWritten(question, m.group(1)) : null;
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

	/** "… She is on both." closing a question about two drugs: what her chart already says, or does not (ADR Decision 429). */
	private static final Pattern ON_BOTH = Pattern.compile(" (?:she|he|they|the patient) (?:is|are) (?:on|taking) (?:both|both of "
			+ "them|them both|both drugs)$");

	private static boolean fitsAsWritten(List<String> words, List<Pattern> shapes) {
		String joined = ON_BOTH.matcher(String.join(" ", words)).replaceFirst("");
		// A dose stated beside the drug is no part of the question's shape (ADR Decision 269): "Is 1 g of paracetamol safe
		// for him?" asks what "Is paracetamol safe for him?" asks, of a dose — which the answer then says it did not check.
		String withoutADose = withoutAStatedDose(joined);
		// And a form named beside it (ADR Decision 334): "Can I give her ketoconazole cream?".
		String withoutAForm = withoutAStatedForm(withoutADose != null ? withoutADose : joined);
		for (Pattern shape : shapes) {
			if (shape.matcher(joined).matches() || withoutADose != null && shape.matcher(withoutADose).matches()
					|| withoutAForm != null && shape.matcher(withoutAForm).matches()) {
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
	/** What may stand before a drug class named in a drug's place — "a PPI", "an NSAID", "any statin". */
	private static final Set<String> CLASS_ARTICLES = new HashSet<String>(Arrays.asList("a", "an", "any", "some", "another"));

	/** The words naming a further one of the class, before its article (ADR Decision 402). */
	private static final Set<String> CLASS_ORDINALS = new HashSet<String>(Arrays.asList("second", "third", "additional",
		"further", "other", "more"));

	/**
	 * Whether {@code question} proposes giving her a drug of a class, named by one of {@code classTerms} where a drug's name
	 * would stand — "Can I give her a PPI?", "Can I give him an NSAID for his back pain?" (ADR Decision 347): with the
	 * term, and any article before it, marked as the drug, the question is one {@link #asksWhetherToGiveADrug} admits.
	 */
	public static boolean proposesADrugClass(String question, List<String> classTerms) {
		return classProposalWords(question, classTerms) != null;
	}

	/** "Is he allergic to penicillin?", "Does she have a penicillin allergy?" — a drug class where a drug's name would
	 *  stand (ADR Decision 367). */
	private static final List<Pattern> ALLERGIC_TO_A_CLASS_SHAPES = shapes(
			"(?:is|are) " + SUBJECT + " allergic to " + D,
			"(?:does|do) " + SUBJECT + " have (?:an |any )?(?:allergy|allergies) to " + D,
			"(?:does|do) " + SUBJECT + " have (?:an |any )?" + D + " allerg(?:y|ies)");

	/** "Is she allergic to oranges?", "Does she have an allergy to latex?", "Does she have a peanut allergy?" — whether she
	 *  is allergic to one thing the question names, a named thing in the group (ADR Decision 386). */
	private static final List<Pattern> ALLERGIC_TO_ONE_NAMED_THING_SHAPES = shapes(
			"(?:is|are) " + SUBJECT + " (?:known to be )?allergic to (?<thing>[\\p{L}\\p{N}][\\p{L}\\p{N}'-]*(?: [\\p{L}\\p{N}'-]+){0,3})",
			"(?:does|do) " + SUBJECT + " have (?:an |any )?(?:known )?(?:allergy|allergies) to (?<thing>[\\p{L}\\p{N}][\\p{L}\\p{N}'-]*"
					+ "(?: [\\p{L}\\p{N}'-]+){0,3})",
			"(?:does|do) " + SUBJECT + " have (?:an |any )?(?<thing>[\\p{L}\\p{N}][\\p{L}\\p{N}'-]*(?: [\\p{L}\\p{N}'-]+){0,2}) "
					+ "allerg(?:y|ies)");

	/** What a question names in an allergen's place without naming one: her allergies or her medications as a whole. */
	private static final Pattern ALLERGIC_TO_NOTHING_NAMED = Pattern.compile("(?:any|anything|something|any of )?"
			+ "(?:(?:her|his|their|the patient's|this patient's) )?(?:other )?(?:medications?|meds|medicines?|drugs?|"
			+ "current medications?|prescriptions?)?|any|anything|something|anything else|known");

	/**
	 * Whether {@code question} asks whether she is allergic to ONE thing it names — a food, a substance, a drug or a
	 * class — and nothing else (ADR Decision 386): such a question is about that thing, so neither the orders her other
	 * allergies conflict with nor a chip about an allergy it does not name answers it. Not <em>"Is she allergic to
	 * anything?"</em> nor <em>"Is she allergic to any of her medications?"</em>, which ask about her allergies or her
	 * medications as a whole. A closed grammar, fail-CLOSED.
	 */
	public static boolean asksWhetherAllergicToOneNamedThing(String question) {
		if (question == null || question.indexOf('?') != question.lastIndexOf('?')) {
			return false;
		}
		String joined = String.join(" ", words(question));
		for (Pattern shape : ALLERGIC_TO_ONE_NAMED_THING_SHAPES) {
			Matcher m = shape.matcher(joined);
			if (m.matches()) {
				return !ALLERGIC_TO_NOTHING_NAMED.matcher(m.group("thing")).matches();
			}
		}
		return false;
	}

	/** Whether {@code question} asks whether she is allergic to a drug class named by one of {@code classTerms} (ADR
	 *  Decision 367), the term marked as {@link #proposesADrugClass} marks it. Fail-CLOSED. */
	public static boolean asksWhetherAllergicToADrugClass(String question, List<String> classTerms) {
		return classWordsFitting(question, classTerms, ALLERGIC_TO_A_CLASS_SHAPES) != null;
	}

	/** Whether a question {@link #proposesADrugClass} states a dose beside the class — "a statin 20 mg" — which no finding
	 *  weighs, as {@link #statesADose} says of one beside a drug (ADR Decision 347). */
	public static boolean statesADoseOfADrugClass(String question, List<String> classTerms) {
		List<String> marked = classProposalWords(question, classTerms);
		return marked != null && statesADose(marked);
	}

	/** {@code question}'s words with the class term marked as the drug, where so marked it proposes the drug; else null. */
	private static List<String> classProposalWords(String question, List<String> classTerms) {
		return classWordsFitting(question, classTerms, null);
	}

	/** {@code question}'s words with the class term (and any article before it) marked as the drug, where so marked they
	 *  fit {@code shapes} — or, {@code shapes} null, propose the drug ({@link #asksWhetherToGiveADrug}); else null. */
	private static List<String> classWordsFitting(String question, List<String> classTerms, List<Pattern> shapes) {
		List<String> words = words(question);
		for (String term : classTerms) {
			List<String> named = words(term);
			for (int i = 0; !named.isEmpty() && i + named.size() <= words.size(); i++) {
				if (!words.subList(i, i + named.size()).equals(named)) {
					continue;
				}
				List<String> marked = new ArrayList<String>(words.subList(0, i));
				// "a second NSAID", "another additional NSAID" — a further one of the class (ADR Decision 402).
				while (!marked.isEmpty() && CLASS_ORDINALS.contains(marked.get(marked.size() - 1))) {
					marked.remove(marked.size() - 1);
				}
				if (!marked.isEmpty() && CLASS_ARTICLES.contains(marked.get(marked.size() - 1))) {
					marked.remove(marked.size() - 1);
				}
				marked.add(DRUG_NAME);
				marked.addAll(words.subList(i + named.size(), words.size()));
				if (shapes == null ? asksWhetherToGiveADrug(marked) : fitsAShape(marked, shapes)) {
					return marked;
				}
			}
		}
		return null;
	}

	public static boolean asksWhetherToGiveADrug(List<String> wordsWithTheDrugMarked) {
		Residual residual = residualReading(wordsWithTheDrugMarked);
		return fitsAProposalShape(wordsWithTheDrugMarked) || residual == Residual.PROPOSES || residual == Residual.HARMS;
	}

	/** {@link #asksWhetherToGiveADrug} by its shapes alone, as {@link #fitsAnInteractsShape} is (ADR Decision 396). */
	public static boolean fitsAProposalShape(List<String> wordsWithTheDrugMarked) {
		if (fitsAShape(wordsWithTheDrugMarked, PROPOSAL_SHAPES) || fitsANegatedShape(wordsWithTheDrugMarked)) {
			return true;
		}
		List<String> proposal = withoutAPurpose(wordsWithTheDrugMarked);
		return proposal != null
				&& (fitsAShape(proposal, PROPOSAL_SHAPES) || fitsANegatedShape(proposal));
	}

	/** How {@link #residualReading} reads a question about one drug that no shape fits. */
	enum Residual {
		/** Not a question it reads: the model answers. */
		NONE,
		/** Whether the drug interacts with her medications. */
		INTERACTS,
		/** Whether to give the drug. */
		PROPOSES,
		/** Whether the drug would harm her — a proposal "No" would answer backwards (ADR Decision 454). */
		HARMS
	}

	/**
	 * The words a question about one drug may be made of and still be read without a shape (ADR Decision 396): asking
	 * words, the patient, her medications as a whole, and filler. A word outside them — a wh-word, a time, an organ, a dose,
	 * an outcome — makes it a question of its own, which the model answers.
	 */
	private static final Set<String> RESIDUAL_FILLER = new HashSet<String>(Arrays.asList("i", "we", "can", "could", "would",
		"will", "should", "may", "might", "is", "are", "it", "there", "a", "an", "the", "to", "on", "be", "that", "this", "any",
		"anything", "something", "with", "if", "her", "his", "him", "she", "he", "they", "them", "their", "patient", "do",
		"does", "now", "today", "together", "alongside", "current", "currently", "other", "existing", "all", "of", "in",
		"for", "want", "wanted", "planning", "plan", "going", "need", "needs", "many", "several", "drug", "drugs", "meds",
		"medication", "medications", "medicine", "medicines", "treatment", "treatments", "regimen", "therapy",
		"prescriptions", "tablets", "pills", "arvs", "arv", "antiretrovirals", "antibiotic", "painkiller", "take", "takes",
		"taking", "co", "please", "so", "and", "also", "already", "between", "about", "while", "tb", "have",
		// a greeting or a preamble before the proposal (ADR Decision 405)
		"hi", "hello", "hey", "doctor", "here", "check", "if", "whether", "am", "i'm", "thinking", "considering", "wondering",
		"you", "choice", "option",
		// technical and conditional wordings (ADR Decision 414)
		"verify", "concomitant", "patient's",
		// "Does fluconazole pose a risk?" (ADR Decision 454)
		"pose", "poses",
		// "Any drug interactions I should be aware of before giving her clarithromycin?" (ADR Decision 426)
		"aware",
		// colloquial and non-native wordings (ADR Decision 415)
		"client", "my", "on", "we",
		// when, how often, and what it would cause (ADR Decision 422)
		"tomorrow", "tonight", "as", "needed", "prn", "top", "cause", "know", "before"));

	/** Words saying the question is about the drug's interactions. */
	private static final Set<String> RESIDUAL_INTERACTS = new HashSet<String>(Arrays.asList("interact", "interacts",
		"interacting", "interaction", "interactions", "ddi", "ddis", "clash", "clashes", "clashing", "disturb", "disturbs"));

	/** Words saying the question is about whether the drug is safe to give. */
	private static final Set<String> RESIDUAL_SAFE = new HashSet<String>(Arrays.asList("safe", "safely", "ok", "okay",
		"fine", "alright", "compatible", "appropriate", "safety", "good", "suitable", "reasonable", "able", "recommended",
		"allowed", "proper", "well",
		// "Fluconazole permitted?" (ADR Decision 454)
		"permitted", "permissible"));

	/** Words saying the question is about whether the drug would harm her, which "No" answers backwards (ADR Decision 454):
	 *  "Will fluconazole harm her?", "Is fluconazole a risk?", "Is fluconazole contraindicated here?". */
	private static final Set<String> RESIDUAL_HARMS = new HashSet<String>(Arrays.asList("issue", "issues", "problem",
		"problems", "problematic", "concern", "concerns", "risk", "risks", "risky", "contraindicated", "contraindication",
		"contraindications", "worried", "worry", "worrying", "harm", "harmful", "hurt", "toxic", "dangerous", "danger",
		"unsafe"));

	/** Words proposing to give the drug, where an asking word opens the question. */
	private static final Set<String> RESIDUAL_GIVE = new HashSet<String>(Arrays.asList("give", "giving", "given", "start",
		"starting", "started", "prescribe", "prescribing", "prescribed", "add", "adding", "added", "administer", "use",
		"taken", "recommend", "gave", "receive", "coadminister", "coadministered", "administered", "put", "go", "mix", "mixed",
		"combine", "combined"));

	/** The asking words a proposal may open with. */
	private static final Set<String> RESIDUAL_ASKING = new HashSet<String>(Arrays.asList("can", "could", "may", "should",
		"would"));

	/** Whether one of the one-drug shapes, any of them, reads {@code words}. */
	private static boolean fitsAOneDrugShape(List<String> words) {
		return fitsAProposalShape(words) || fitsAnInteractsShape(words) || asksWhichOfHerMedicationsInteractWith(words)
				|| asksWhetherADrugIsSafeWithHerMedications(words) || asksWhetherSheIsOnADrug(words)
				|| asksWhetherItIsSafeToStopHerDrug(words) || asksWhetherToStopHerDrug(words)
				|| asksWhichInteractionsStoppingItEnds(words) || asksWhetherToContinueHerDrug(words)
				|| asksAboutADrugNamedAsHers(words) || asksAboutADrugBesideHerMedications(words)
				|| asksWhetherADrugChangesHerMedications(words) || asksWhetherToChangeHerDrugsDose(words)
				|| asksWhetherSheHasTakenADrug(words) || asksAboutADrugAndHerAllergies(words) || asksAboutHerNamedDrug(words);
	}

	/** The words a question about a drug's record in her chart opens with (ADR Decision 399), "why" among them only where
	 *  it asks why she is on the drug ({@link #ENDED_STEMS}; ADR Decision 403). */
	private static final Set<String> RECORD_OPENERS = new HashSet<String>(Arrays.asList("when", "who", "why", "how", "what",
		"which", "did", "was", "were", "has", "have", "had", "is", "are", "does", "do"));

	/**
	 * Any word saying a question about a drug asks something of its safety, of giving it, stopping it or changing it — or
	 * advice, which an asking modal asks — so it is no question about its record alone (ADR Decision 399). Stems: a word
	 * opening with one is such a word.
	 */
	private static final List<String> SAFETY_STEMS = Arrays.asList("interact", "safe", "risk", "danger", "contraindicat",
		"allerg", "avoid", "withhold", "combin", "together", "problem", "concern", "issue", "clash", "caution", "warn", "worr",
		"harm", "toxic", "compatib", "appropriat", "switch", "replac", "instead", "continu", "better", "prefer", "choos",
		"versus", "monitor", "watch", "check", "screen", "ddi", "alright", "affect", "level", "reduc", "increas", "decreas",
		"dose", "dosage", "dosing", "good", "choice", "option", "suitab", "reasonab", "recommend", "flag");

	/** Words of a drug's ending: why it was stopped, held or changed may be the very allergy or interaction a finding states,
	 *  which the model is handed (ADR Decision 403). */
	private static final List<String> ENDED_STEMS = Arrays.asList("stop", "discontinu", "held", "hold", "chang", "end",
		"ceas", "withdr", "interrupt", "paus", "swap");

	/** The openers asking of her past, under which "she start" is her having started a drug (ADR Decision 432). */
	private static final Set<String> PAST_RECORD_OPENERS = new HashSet<String>(Arrays.asList("when", "did", "how"));

	/** The {@link #SAFETY_STEMS} of a dose, which a question about the dose she is on carries (ADR Decision 401). */
	private static final Set<String> DOSE_STEMS = new HashSet<String>(Arrays.asList("dose", "dosage", "dosing"));

	/** Such words as whole words: short ones, which as stems would open words of fact — "prescribed", "address". */
	private static final Set<String> SAFETY_WORDS = new HashSet<String>(Arrays.asList("ok", "okay", "fine", "hold", "give",
		"giving", "add", "adding", "prescribe", "prescribing", "start", "starting", "stop", "stopping", "best", "vs", "can", "able",
		"allowed", "harm", "proper", "put", "mix", "mixed", "combine",
		// "Is fluconazole bad for her?" was read as a question of its record and handed no finding (ADR Decision 453)
		"bad", "worse", "wrong",
		"could", "should", "may", "might", "would", "will", "must", "need", "needs"));

	/**
	 * Whether a question names ONE drug only to ask of its record in her chart — when it was started, who prescribed it,
	 * whether it was stopped, what it is for, how long she has taken it (ADR Decision 399): it opens as a question of fact
	 * ({@link #RECORD_OPENERS}), no one-drug shape or residual reading reads it, and it carries no word of safety, giving,
	 * stopping, changing or advice ({@link #SAFETY_STEMS}, {@link #SAFETY_WORDS}). Such a question is handed the model with no drug-safety
	 * material, which "Was aspirin stopped?" was answered from as a proposal — "No, aspirin should not be given". The dose
	 * she is on is a fact of her record, but a dose is also how a proposal is asked (Decision 394), so it is left to the
	 * findings: the list is what a question must lack, and errs toward keeping them.
	 */
	public static boolean asksOnlyAboutADrugsRecord(List<String> wordsWithTheDrugMarked) {
		// Nor "What about fluconazole?", which says nothing of its record (ADR Decision 405).
		if (wordsWithTheDrugMarked == null || Collections.frequency(wordsWithTheDrugMarked, DRUG_NAME) != 1
				|| wordsWithTheDrugMarked.size() < 3) {
			return false;
		}
		// How or when she takes it asks nothing of its safety either, whatever modal it opens with: "When should she take her
		// efavirenz?" was handed the drug's findings for "should", and the model answered "Efavirenz can be given, with two
		// cautions" (ADR Decision 450).
		if (fitsAShape(wordsWithTheDrugMarked, HOW_TO_TAKE_A_DRUG_SHAPES)) {
			return true;
		}
		if (!RECORD_OPENERS.contains(wordsWithTheDrugMarked.get(0)) || "about".equals(wordsWithTheDrugMarked.get(1))) {
			return false;
		}
		// The dose she is on, or her drug's dose, is a fact of her record: a dose asked of a drug proposed is not (ADR
		// Decision 401).
		boolean ofHers = String.join(" ", wordsWithTheDrugMarked).matches(".*\\b(?:(?:she|he|they) (?:is |are )?(?:on|taking|"
				+ "receiving|getting)\\b|(?:is|are) (?:she|he|they) (?:on|taking|receiving|getting)\\b|"
				+ "(?:her|his|their) " + Pattern.quote(DRUG_NAME) + ").*");
		// Nor one naming her medications, which asks of the drug among them (ADR Decision 414).
		if (String.join(" ", wordsWithTheDrugMarked).matches(".*\\b" + POSSESSIVE + " (?:other )?" + MEDICATIONS + "\\b.*")) {
			return false;
		}
		if ("why".equals(wordsWithTheDrugMarked.get(0))) {
			for (String word : wordsWithTheDrugMarked) {
				for (String stem : ENDED_STEMS) {
					if (word.startsWith(stem)) {
						return false;
					}
				}
			}
		}
		for (int w = 0; w < wordsWithTheDrugMarked.size(); w++) {
			String word = wordsWithTheDrugMarked.get(w);
			// "When did she start nevirapine?" — her starting it, a fact of her record, never "Can I start …" (ADR Decision
			// 432)
			boolean herStarting = ("start".equals(word) || "starting".equals(word)) && w > 0
					&& RESIDUAL_SUBJECTS.contains(wordsWithTheDrugMarked.get(w - 1))
					&& PAST_RECORD_OPENERS.contains(wordsWithTheDrugMarked.get(0));
			if (SAFETY_WORDS.contains(word) && !herStarting) {
				return false;
			}
			for (String stem : SAFETY_STEMS) {
				if (word.startsWith(stem) && !(ofHers && DOSE_STEMS.contains(stem))) {
					return false;
				}
			}
		}
		return !fitsAOneDrugShape(wordsWithTheDrugMarked) && residualReading(wordsWithTheDrugMarked) == Residual.NONE;
	}

	/** When to take a drug, with food or without, and how — "When should she take her efavirenz?", "Should rifampicin be taken
	 *  on an empty stomach?", "Can she crush her tablets of X?" (ADR Decision 450): how it is taken, which no finding weighs. */
	private static final String MEALS = "(?:food|meals?|a meal|eating|breakfast|lunch|dinner|supper|water)";

	private static final String A_TIME_OF_TAKING = "(?:(?:with|without|before|after) " + MEALS + "|on an empty stomach|at night"
			+ "|at bedtime|in the morning|in the evening|at the same time (?:each|every) day)";

	private static final List<Pattern> HOW_TO_TAKE_A_DRUG_SHAPES = shapes(
			"(?:when|what time|how) (?:should|do|does|can|must|would) (?:she|he|they|the patient|this patient) take (?:"
					+ POSSESSIVE + " )?" + D + "(?: tablets?| pills?| doses?)?",
			"(?:can|should|must|does|do|could) (?:she|he|they|the patient|this patient) (?:need to )?take (?:" + POSSESSIVE + " )?"
					+ D + " " + A_TIME_OF_TAKING,
			"(?:should|must|can|could|does|do) (?:" + POSSESSIVE + " )?" + D + " (?:need to )?be taken " + A_TIME_OF_TAKING,
			"(?:does|do|will|would|can) (?:food|eating|a meal|meals) (?:affect|change|reduce|increase|alter) (?:the absorption of )?(?:"
					+ POSSESSIVE + " )?" + D,
			"(?:can|should|could|may) (?:i|we|she|he|they|the patient) (?:crush|split|chew|dissolve|break) (?:" + POSSESSIVE + " )?"
					+ D + "(?: tablets?| pills?)?");

	/** The possessives naming the drug as hers. */
	private static final Set<String> RESIDUAL_POSSESSIVES = new HashSet<String>(Arrays.asList("her", "his", "their",
		"patient's"));

	/** The subjects of "take" a residual proposal reads as her taking it. */
	private static final Set<String> RESIDUAL_SUBJECTS = new HashSet<String>(Arrays.asList("she", "he", "they", "patient",
		"client"));

	/** The words that make the residual words a question. */
	private static final Set<String> RESIDUAL_QUESTION = new HashSet<String>(Arrays.asList("can", "could", "may", "should",
		"would", "will", "is", "are", "does", "do", "any", "am"));

	/**
	 * How a question about ONE drug that no shape fits is read (ADR Decision 396): {@link Residual#INTERACTS} where it says
	 * "interact", {@link Residual#PROPOSES} where it says the drug is safe, a problem, or proposes giving it after an
	 * asking word — provided it names the drug once and every other word is one {@link #RESIDUAL_FILLER} admits. The shapes
	 * are a grammar for what the module answers; this reads the wordings around them a clinician types — "Can I
	 * co-prescribe fluconazole?", "Planning to give her metronidazole, is that safe?" — which went to the model, whose
	 * answers cleared drugs over interactions the data never rated. Fail-closed still: any other word, and the model answers.
	 */
	static Residual residualReading(List<String> wordsWithTheDrugMarked) {
		if (wordsWithTheDrugMarked == null || Collections.frequency(wordsWithTheDrugMarked, DRUG_NAME) != 1) {
			return Residual.NONE;
		}
		// A dose stated beside the drug is no part of what it asks, as for the shapes (ADR Decisions 269, 422): "Fluconazole
		// 200mg daily - ok?".
		String withoutADose = withoutAStatedDose(String.join(" ", wordsWithTheDrugMarked));
		if (withoutADose != null) {
			wordsWithTheDrugMarked = Arrays.asList(withoutADose.split(" "));
		}
		// A drug named as hers — not "give her X", where she is who it is given to — is her own medication's question,
		// which its own shapes answer (ADR Decisions 335, 374).
		int at = wordsWithTheDrugMarked.indexOf(DRUG_NAME);
		if (at > 0 && RESIDUAL_POSSESSIVES.contains(wordsWithTheDrugMarked.get(at - 1))
				&& (at < 2 || !RESIDUAL_GIVE.contains(wordsWithTheDrugMarked.get(at - 2)))) {
			return Residual.NONE;
		}
		// Only where no shape reads it: a question a shape answers is that shape's.
		if (fitsAOneDrugShape(wordsWithTheDrugMarked)) {
			return Residual.NONE;
		}
		// The drug beside her medications and nothing else, "fluconazole + her meds", asks how they interact (ADR Decision
		// 422): read here, of a drug the data resolved, and never as a shape, which would read "Review her medications" as one.
		if (Pattern.compile(D + " (?:and |with |plus |vs |versus )?" + POSSESSIVE + " (?:other )?" + MEDICATIONS)
				.matcher(String.join(" ", wordsWithTheDrugMarked)).matches()) {
			return Residual.INTERACTS;
		}
		boolean interacts = false;
		boolean safe = false;
		boolean harms = false;
		boolean gives = false;
		for (String word : wordsWithTheDrugMarked) {
			if (DRUG_NAME.equals(word) || RESIDUAL_FILLER.contains(word)) {
				continue;
			}
			if (RESIDUAL_INTERACTS.contains(word)) {
				interacts = true;
			} else if (RESIDUAL_SAFE.contains(word)) {
				safe = true;
			} else if (RESIDUAL_HARMS.contains(word)) {
				harms = true;
			} else if (RESIDUAL_GIVE.contains(word)) {
				gives = true;
			} else {
				return Residual.NONE;
			}
		}
		// "Can she take X?" — she takes it, given her; never "Can I take X?", the speaker's (ADR Decision 402).
		for (int i = 1; i < wordsWithTheDrugMarked.size(); i++) {
			gives |= ("take".equals(wordsWithTheDrugMarked.get(i)) || "have".equals(wordsWithTheDrugMarked.get(i)))
					&& RESIDUAL_SUBJECTS.contains(wordsWithTheDrugMarked.get(i - 1));
		}
		boolean asked = false;
		// A drug, the patient and a word saying it is safe — "Warfarin for her - safe?" — is asked whole (ADR Decision 405).
		boolean aQuestion = wordsWithTheDrugMarked.size() <= 2 || (safe || harms) && wordsWithTheDrugMarked.size() <= 4;
		for (String word : wordsWithTheDrugMarked) {
			asked |= RESIDUAL_ASKING.contains(word);
			aQuestion |= RESIDUAL_QUESTION.contains(word);
		}
		// A question it must be: "it safe to give her mebendazole?" lost its first word, which is not guessed at (ADR Decision
		// 152); a drug and a word saying it is safe, "ciprofloxacin ok?", is asked whole.
		if (!aQuestion) {
			return Residual.NONE;
		}
		// A word asking whether it would harm her makes the question one "No" answers backwards, though it also asks whether it
		// is safe: "Is fluconazole safe or risky?" (ADR Decision 454).
		return interacts ? Residual.INTERACTS : harms ? Residual.HARMS : safe || gives && asked ? Residual.PROPOSES
				: Residual.NONE;
	}

	/**
	 * An allergy a question SUPPOSES beside its proposal, and the proposal without it (ADR Decision 410): <em>"Is ibuprofen ok
	 * if she is allergic to aspirin?"</em> is the proposal <em>"Is ibuprofen ok?"</em> beside the supposed allergy <em>"aspirin"</em>.
	 */
	public static final class SupposedAllergy {

		/** The question without the clause supposing the allergy. */
		public final String proposal;

		/** The allergy as the question wrote it. */
		public final String allergen;

		SupposedAllergy(String proposal, String allergen) {
			this.proposal = proposal;
			this.allergen = allergen;
		}
	}

	/** A statement about her that opens the question, and the question after it (ADR Decision 419). */
	public static final class LeadingStatement {

		/** The question after the statement. */
		public final String question;

		/** The statement as the question wrote it. */
		public final String statement;

		LeadingStatement(String question, String statement) {
			this.question = question;
			this.statement = statement;
		}
	}

	/** A statement about her opening the question, then the question: "She has HIV and TB. Can I give her fluconazole?", "She
	 *  is pregnant, can I give her fluconazole?", "Patient is on ART. Is fluconazole safe?", "I need to treat her UTI. Can I use
	 *  ciprofloxacin?" (ADR Decision 419). */
	private static final Pattern LEADING_STATEMENT = Pattern.compile("(?i)^\\s*((?:(?:she|he|they|the patient|this patient|patient)"
			+ "\\s+(?:is|are|was|has|have|had|takes|gets|needs|need)|(?:her|his|their)\\s+[\\p{L} ]{1,30}?\\s+(?:is|are)|i\\s+(?:need|want|have)"
			+ "\\s+to\\s+treat\\s+(?:her|his|their))\\b[^.?!,;]{1,60}?)(?:\\s*[.,;:]|\\s+[-\u2013\u2014])\\s+((?:can|could|may|should|is|are|would|will|"
			+ "what|which|does|do|any|before)\\b.+)$");

	/** The statement opening {@code question} and the question after it, or {@code null} (ADR Decision 419). The caller decides
	 *  whether it names a drug, in which case it is a list of drugs and no statement. */
	public static LeadingStatement leadingStatement(String question) {
		if (question == null) {
			return null;
		}
		Matcher m = LEADING_STATEMENT.matcher(question.trim());
		if (!m.matches()) {
			return null;
		}
		// A statement of filler alone says nothing the check could fail to weigh: "She needs an antibiotic, is ciprofloxacin
		// okay?" asks what "Is ciprofloxacin okay?" asks (ADR Decisions 396, 429).
		if (RESIDUAL_FILLER.containsAll(words(m.group(1)))) {
			return null;
		}
		String rest = m.group(2).trim();
		return new LeadingStatement(Character.toUpperCase(rest.charAt(0)) + rest.substring(1), m.group(1).trim());
	}

	/** The clauses supposing an allergy, each closing the question: the proposal before it, the allergen in it. */
	private static final List<Pattern> SUPPOSED_ALLERGY_CLAUSES = Arrays.asList(
		Pattern.compile("(?i)^(.+?),?\\s+(?:if|when|given that)\\s+(?:she|he|they|the patient)\\s+(?:is|are|was|were)\\s+"
				+ "allergic\\s+to\\s+(.+?)[?.!\\s]*$"),
		Pattern.compile("(?i)^(.+?),?\\s+(?:if|when|given that)\\s+(?:she|he|they|the patient)\\s+(?:has|have)\\s+(?:a\\s+|an\\s+)?"
				+ "(.+?)\\s+allergy[?.!\\s]*$"),
		Pattern.compile("(?i)^(.+?)\\s+(?:to|for|in)\\s+(?:someone|somebody|anyone|a patient|a person|patients|people)\\s+"
				+ "(?:who\\s+(?:is|are)\\s+|that\\s+(?:is|are)\\s+)?allergic\\s+to\\s+(.+?)[?.!\\s]*$"),
		Pattern.compile("(?i)^(.+?)\\s+(?:to|for|in)\\s+(?:a|an)\\s+(.+?)[-\\s]allergic\\s+(?:patient|person|woman|man)[?.!\\s]*$"));

	/** The allergy {@code question} supposes beside its proposal and the proposal without it, or {@code null} (ADR Decision
	 *  410). Never her own: "if she is allergic" asks of an allergy her chart may not record, and the answer says it weighs
	 *  only those it does. */
	public static SupposedAllergy supposedAllergy(String question) {
		if (question == null) {
			return null;
		}
		for (Pattern clause : SUPPOSED_ALLERGY_CLAUSES) {
			Matcher m = clause.matcher(question.trim());
			if (m.matches()) {
				return new SupposedAllergy(m.group(1).trim() + "?", m.group(2).trim());
			}
		}
		return null;
	}

	/**
	 * The purpose a proposal closes with — <em>"pain"</em>, <em>"her knee pain"</em> — where {@link #asksWhetherToGiveADrug}
	 * admits it only once that purpose is set aside, or {@code null}. The findings do not weigh what a drug is for, so an
	 * answer to such a proposal says so (ADR Decision 213).
	 */
	public static String statedPurpose(List<String> wordsWithTheDrugMarked) {
		// Nor a question about the drug and her allergies, whose closing words are those allergies (ADR Decision 344).
		if (fitsAShape(wordsWithTheDrugMarked, PROPOSAL_SHAPES) || fitsANegatedShape(wordsWithTheDrugMarked)
				|| fitsAShape(wordsWithTheDrugMarked, DRUG_AND_HER_ALLERGIES_SHAPES)) {
			return null;
		}
		List<String> proposal = withoutAPurpose(wordsWithTheDrugMarked);
		if (proposal == null || !fitsAShape(proposal, PROPOSAL_SHAPES) && !fitsANegatedShape(proposal)) {
			return null;
		}
		// After "for", or the whole closing statement of what she has (ADR Decision 325).
		int from = "for".equals(wordsWithTheDrugMarked.get(proposal.size())) ? proposal.size() + 1 : proposal.size();
		String purpose = String.join(" ", wordsWithTheDrugMarked.subList(from, wordsWithTheDrugMarked.size()));
		// Not how long it would be given — "for 2 weeks" — which is no purpose, and says so itself (ADR Decision 419).
		return purpose.matches(A_DURATION) ? null : purpose;
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
		List<String> withoutAStatement = withoutAStatedCondition(words);
		if (withoutAStatement != null) {
			return withoutAStatement;
		}
		int at = words.lastIndexOf("for");
		int length = words.size() - at - 1;
		if (at < 1 || length < 1 || length > 4) {
			return null;
		}
		List<String> purpose = words.subList(at + 1, words.size());
		if (String.join(" ", purpose).matches(PATIENT)) {
			return null;
		}
		// A condition named by a substance — "alcohol withdrawal" — is what the drug is for, not a factor beside it (ADR
		// Decision 364).
		String named = CONDITION_NAMED_BY_A_SUBSTANCE.matcher(String.join(" ", purpose)).replaceAll("condition");
		for (String word : named.split(" ")) {
			if (DRUG_NAME.equals(word) || NOT_A_PURPOSE.contains(word) || OTHER_FACTOR.matcher(word).find()) {
				return null;
			}
		}
		return new ArrayList<String>(words.subList(0, at));
	}

	/** A condition a substance names — "alcohol withdrawal", "alcohol dependence" — which a purpose may state though the
	 *  substance alone is a factor the findings do not weigh ({@link #OTHER_FACTOR}; ADR Decision 364). */
	private static final Pattern CONDITION_NAMED_BY_A_SUBSTANCE = Pattern.compile("\\balcohol (?:withdrawal|dependence|"
			+ "use disorder|abuse|detox|detoxification)\\b");

	/** The subjects a closing statement of what she has opens with. */
	private static final Set<String> STATEMENT_SUBJECTS = new HashSet<String>(Arrays.asList("she", "he", "they"));

	/**
	 * {@code words} with a closing statement of what she HAS taken off — <em>"Can I give her ciprofloxacin? She has a
	 * UTI."</em> — or {@code null} (ADR Decision 325): a purpose stated as its own sentence, set aside as
	 * {@link #withoutAPurpose} sets aside one after "for", on the same conditions — at most four words after "has", none the
	 * drug, a word joining a second question ({@link #NOT_A_PURPOSE}) or a factor the findings do not weigh
	 * ({@link #OTHER_FACTOR}).
	 */
	private static List<String> withoutAStatedCondition(List<String> words) {
		for (int at = words.size() - 3; at >= 1; at--) {
			if (!STATEMENT_SUBJECTS.contains(words.get(at)) || !"has".equals(words.get(at + 1))
					&& !"have".equals(words.get(at + 1))) {
				continue;
			}
			int length = words.size() - at - 2;
			if (length < 1 || length > 4) {
				return null;
			}
			for (String word : words.subList(at + 2, words.size())) {
				if (DRUG_NAME.equals(word) || NOT_A_PURPOSE.contains(word) || OTHER_FACTOR.matcher(word).find()) {
					return null;
				}
			}
			return new ArrayList<String>(words.subList(0, at));
		}
		return null;
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
				|| asksWhetherHerMedicationsNeedChanging(question) || asksWhichDrugsToAvoid(question)
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

	/**
	 * Whether a question asks whether she takes MORE THAN ONE drug of a class, and nothing else — <em>"Is she on more than
	 * one steroid?"</em> (ADR Decision 379): one of the {@link #asksWhetherSheIsOnADrugClass} shapes, whose answer says
	 * "Yes" only for two or more.
	 */
	public static boolean asksWhetherSheIsOnMoreThanOneOfADrugClass(String question) {
		return question != null && question.indexOf('?') == question.lastIndexOf('?')
				&& fitsAShape(words(question), MORE_THAN_ONE_OF_A_DRUG_CLASS_SHAPES);
	}

	private static final List<Pattern> MORE_THAN_ONE_OF_A_DRUG_CLASS_SHAPES = shapes(
			// "Is she on more than one steroid?"
			"(?:is|are) " + SUBJECT + " (?:currently |still )?(?:on|taking|receiving|using) (?:more than one|two|2|multiple|several)(?: \\p{L}+){1,3}",
			// "Does she take more than one steroid?"
			"(?:does|do) " + SUBJECT + " (?:currently |still )?(?:take|use|receive) (?:more than one|two|2|multiple|several)(?: \\p{L}+){1,3}");

	private static final List<Pattern> ON_A_DRUG_CLASS_SHAPES = shapes(
			// "Is she on any NSAIDs?", "Is she currently taking an NSAID?"
			"(?:is|are) " + SUBJECT + " (?:currently |still )?(?:on|taking|receiving|using) (?:any|an?|some)(?: \\p{L}+){1,3}",
			// "Does she take any NSAIDs?"
			"(?:does|do) " + SUBJECT + " (?:currently |still )?(?:take|use|receive) (?:any|an?|some)(?: \\p{L}+){1,3}",
			// "Is she on more than one steroid?" (ADR Decision 379)
			"(?:is|are) " + SUBJECT + " (?:currently |still )?(?:on|taking|receiving|using) (?:more than one|two|2|multiple|several)(?: \\p{L}+){1,3}",
			"(?:does|do) " + SUBJECT + " (?:currently |still )?(?:take|use|receive) (?:more than one|two|2|multiple|several)(?: \\p{L}+){1,3}");

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
			"(?:do|does) any of " + HER_MEDICATIONS + " need (?:to be )?" + CHANGED,
			// "Any reason not to continue her current medications?", "Should I stop any of her medications?" (ADR Decision
			// 397)
			"(?:is there )?any reason not to continue " + HER_MEDICATIONS,
			// "Do any of her drugs need dose adjustment?", "Should any of her medicines be stopped?" (ADR Decision 416)
			"(?:do|does) any of " + HER_MEDICATIONS + " need (?:a )?dos(?:e|ing) (?:adjustments?|changes?)",
			"should any of " + HER_MEDICATIONS + " be " + CHANGED,
			"should (?:i|we) stop any of " + HER_MEDICATIONS);

	/**
	 * Whether a question asks which drugs to avoid in her, and nothing else — <em>"What drugs should I avoid in this
	 * patient?"</em>, <em>"What should I avoid prescribing for her?"</em> (ADR Decision 324). No finding lists drugs to
	 * avoid, so the module answers with her recorded drug allergies and its screen of her own medications, under a lead
	 * saying so. A closed grammar, fail-CLOSED.
	 */
	public static boolean asksWhichDrugsToAvoid(String question) {
		return question != null && question.indexOf('?') == question.lastIndexOf('?')
				&& fitsAShape(words(question), AVOID_SHAPES);
	}

	private static final String DRUGS_NOUN = "(?:drugs|medications|medicines|meds)";

	private static final List<Pattern> AVOID_SHAPES = shapes(
			// "What drugs should I avoid in this patient?", "Which medications should we avoid for her?"
			"(?:what|which) " + DRUGS_NOUN + " should (?:i|we) avoid(?: (?:in|for|with|giving) (?:" + PATIENT + "|"
					+ HER_MEDICATIONS + "))?",
			// "What should I avoid prescribing for her?", "What should I avoid giving him?"
			"what should (?:i|we) avoid (?:prescribing|giving)(?: (?:for|to) " + PATIENT + "| " + PATIENT + ")?",
			// "What drugs should she avoid?"
			"(?:what|which) " + DRUGS_NOUN + " should " + SUBJECT + " avoid",
			// "Which drugs are contraindicated for her?" (ADR Decision 424)
			"(?:what|which) " + DRUGS_NOUN + " (?:are|would be) contraindicated(?: (?:for|in) " + PATIENT + ")?",
			// "Is there anything I should not give her?", "What should I not give her?" (ADR Decision 397)
			"is there (?:anything|any (?:drug|medication|medicine)) (?:that )?(?:i|we) (?:should not|shouldn't|shouldnt|"
					+ "must not|mustn't) (?:give|prescribe)(?: to)? " + PATIENT,
			"what should (?:i|we) not (?:give|prescribe)(?: to)? " + PATIENT);

	/**
	 * Whether a question asks about ONE drug and her allergies — <em>"Is his clarithromycin safe with his allergies?"</em>,
	 * <em>"Is he allergic to clarithromycin?"</em> — over {@link #words} with the drug marked (ADR Decision 327). The module
	 * answers with what its check found between the drug and her recorded allergies. Fail-CLOSED.
	 */
	public static boolean asksAboutADrugAndHerAllergies(List<String> wordsWithTheDrugMarked) {
		return fitsAShape(wordsWithTheDrugMarked, DRUG_AND_HER_ALLERGIES_SHAPES);
	}

	/** Her allergies, or one named by its kind — "his NSAID allergy" (ADR Decision 344). */
	private static final String HER_ALLERGIES = "(?:" + POSSESSIVE + "|the patient's) (?:recorded |known |drug )?(?:nsaid |"
			+ "sulfa |sulpha )?allerg(?:y|ies)";

	private static final List<Pattern> DRUG_AND_HER_ALLERGIES_SHAPES = shapes(
			"is " + HER_D + " (?:safe|ok|okay|appropriate)(?: for " + PATIENT + ")? (?:with|given|for|despite) " + HER_ALLERGIES,
			"(?:is|are) " + SUBJECT + " allergic to " + HER_D,
			"(?:does|do) " + SUBJECT + " have (?:an |any )?allerg(?:y|ies) to " + HER_D,
			"(?:can|could|may) (?:i give " + PATIENT + "|" + SUBJECT + " (?:take|have)) " + D + " (?:with|given|despite) "
					+ HER_ALLERGIES);

	private static final List<Pattern> MONITORING_SHAPES = shapes(
			// "What should I monitor with her current medications?", "What do I need to monitor with her meds?"
			"what (?:should|do|must) (?:i|we) (?:need to )?monitor (?:with|for|on|in) " + HER_MEDICATIONS,
			// "What monitoring does she need for her medications?"
			"what monitoring (?:does|do|should) " + SUBJECT + " (?:need|have|get) (?:for|with|on) " + HER_MEDICATIONS,
			// "What needs to be monitored with her medications?"
			"what (?:needs|should|has) (?:to )?be monitored (?:with|for|on) " + HER_MEDICATIONS);

	/** Her DRUG allergies, asked for — "What are her drug allergies?", "Does she have any drug allergies?", "List her
	 *  medication allergies" (ADR Decision 366). Never her allergies unqualified, which her food and environmental
	 *  allergies answer too and the drug-allergy line does not state. */
	private static final List<Pattern> HER_DRUG_ALLERGIES_SHAPES = shapes(
			"what (?:are|is) " + POSSESSIVE + " (?:recorded |known )?(?:drug|medication) allerg(?:y|ies)",
			"(?:does|do) " + SUBJECT + " have (?:any )?(?:recorded |known )?(?:drug|medication) allerg(?:y|ies)",
			"(?:please )?list " + POSSESSIVE + " (?:recorded |known )?(?:drug|medication) allerg(?:y|ies)",
			"what (?:drugs|medications|medicines) (?:is|are) " + SUBJECT + " allergic to",
			// "Any drug allergies I should know about before prescribing?" (ADR Decision 402)
			"(?:(?:are|is) there )?any (?:drug|medication) allerg(?:y|ies) (?:i|we) should know(?: about)?(?: before (?:prescribing|"
					+ "giving|treating)(?: " + PATIENT + ")?)?");

	/** Whether a question asks for her drug allergies, and nothing else ({@link #HER_DRUG_ALLERGIES_SHAPES}). Fail-CLOSED. */
	public static boolean asksForHerDrugAllergies(String question) {
		return fitsAShape(words(question), HER_DRUG_ALLERGIES_SHAPES);
	}

	private static final String ALLERGIC = "(?:allergic|(?:she|he|they)'s allergic|(?:she|he|they) (?:is|are) allergic)";

	private static final List<Pattern> ALLERGIC_TO_HER_MEDICATIONS_SHAPES = shapes(
			// "Is she allergic to any of her medications?", "Is she allergic to any of her current meds?"
			"(?:is|are) " + SUBJECT + " allergic to any of " + POSSESSIVE + " " + MEDICATIONS,
			// "Does she have any allergy to her current medicines?", "Which of her medications is she allergic to?", "Does she
			// have any drug allergies that conflict with her medications?", "Any cross-reactivity with her allergies?" (ADR
			// Decision 412)
			"(?:does|do) " + SUBJECT + " have (?:an |any )?allerg(?:y|ies) to " + POSSESSIVE + " " + MEDICATIONS,
			"which of " + POSSESSIVE + " " + MEDICATIONS + " (?:is|are) " + SUBJECT + " allergic to",
			"(?:does|do) " + SUBJECT + " have (?:any )?(?:drug )?allerg(?:y|ies) that (?:conflicts?|clash(?:es)?) with "
					+ POSSESSIVE + " " + MEDICATIONS,
			"(?:(?:is|are) there )?any cross reactivity (?:with|between) " + POSSESSIVE + " (?:drug )?allerg(?:y|ies)(?: and "
					+ POSSESSIVE + " " + MEDICATIONS + ")?",
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
			"(?:which|what) (?:is|are|would be) (?:the |a |an )?(?:most )?" + SAFE_WORD + " (?:\\p{L}+ ){0,2}" + DRUG_NOUN + FOR_HER,
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
