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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.module.chartsearchai.api.impl.DrugClassMembersStatement;

/**
 * Which drug CLASS a question names, when it names one — the vocabulary behind
 * {@link DrugReferenceService#namedDrugClass}, and nothing else (issue #354) — save that it also reads which groups of
 * HER medications a question names and which of her orders' codes are in one ({@link #herMedicationGroupsNamed}, {@link
 * #membersOf}; ADR Decisions 275, 630, 631): her orders sorted by their own codes, never substances put in play.
 *
 * <p>It classifies a STRING, not a substance: the answer is a class NAME and never a member set.
 * That boundary is the whole of the issue's design decision and is argued at
 * {@link DrugReferenceService#namedDrugClass}; read it there rather than here.
 *
 * <p><b>Two sources, and each is here because the other cannot serve its case.</b> A class the
 * curated cross-reactivity groups already name is read from THAT data — it is a curated,
 * operator-extensible class-name table with its own load-validity channel
 * ({@code CrossReactivityGroupsLoad.getFindings()}, issue #266), and the shipped seed's one group is
 * named {@code NSAID}, which is one of the two classes this issue reports. The table below carries
 * what that file cannot: a class it does not name at all, and a further SPELLING of one it does.
 *
 * <p><b>Why a class it does not name cannot simply be added to it.</b> A group is not a label. Its
 * {@code atcPrefixes} drive clinical claims — cross-reactivity contraindication chips and
 * duplicate-class-therapy chips, through {@link CrossReactivityGroup#groupsOf} and
 * {@link CrossReactivityGroup#sharedGroup} — so adding a group to make a word recognisable asserts
 * pharmacological cross-reactivity across those prefixes. And for the issue's headline class there
 * is no honest prefix set to add at all; the measurement that establishes it is at
 * {@link DrugReferenceService#namedDrugClass}, and is not repeated here.
 *
 * <p><b>The two sources are independent, and neither is subordinate to the other.</b> Each names the
 * classes it names; the answer is the union. In particular the table below names {@code NSAID}
 * OUTRIGHT rather than as a spelling of the shipped group, and that is a decision with a measured
 * reason: {@code chartsearchai.drugReference.crossReactivityGroupsFilePath} is a supported
 * configuration, so making the class conditional on some loaded group publishing that exact name
 * returned the issue's own {@code an NSAID} case to pre-#354 silence — with nothing logged — on any
 * deployment that curates its own groups file. The groups leg is there to honour a vocabulary an
 * operator has already curated, not to license this one.
 *
 * <p>What the boundary rule forces either way is that a class needs its SPELLINGS enumerated:
 * {@link DrugReference#containsWord}'s prose allowance is zero trailing letters, so {@code NSAIDs} is
 * not the term {@code NSAID}, and a group publishes one {@code name}. A class an operator's file
 * names is therefore recognised in that one spelling; one named here is recognised in as many as are
 * written out.
 *
 * <p><b>What may be admitted, stated so it can be re-applied.</b> A term is admitted only when
 * <ol>
 * <li>it designates a drug CLASS — a pharmacological, chemical or therapeutic grouping — rather than
 * the name of any single substance;</li>
 * <li>it resolves to no entry of the shipped knowledge base, which is a DATA GUARD
 * ({@code DrugClassQuestionNoteTest.everyCuratedClassTermResolvesToNoSubstanceInTheShippedKnowledgeBase},
 * through {@link DrugReferenceService#findImpliedByQuery}) and not a claim made here. It binds BOTH
 * columns: the term is what a question is matched against, and the class name beside it is what the
 * record PRINTS, so the second is guarded by
 * {@code DrugClassQuestionNoteTest.everyClassNameTheTableCanReportNamesNoSubstanceOfTheShippedKnowledgeBase},
 * which drives a question per term through the injector and puts the rendered note back through the
 * same accessor. Guarding the keys alone left the value column unguarded: measured 2026-09-02 with
 * that case deleted, a term whose value is a drug name ships with the whole build green;</li>
 * <li>where a curated cross-reactivity group already publishes the same class, the duplication is
 * DELIBERATE rather than forbidden — see the paragraph above on why {@code NSAID} is named here
 * outright.</li>
 * </ol>
 * Incompleteness is MONOTONE and that is what makes a partial vocabulary safe: an unrecognised class
 * term leaves the module exactly as silent as it is today, while a wrongly admitted one would have
 * the module call a drug a class — which is what (2) exists to prevent.
 *
 * <p><b>Residue, stated rather than claimed away.</b> {@link DrugReference#containsWord} does not
 * collapse whitespace ({@code boundedTokenIndex} is an {@code indexOf} over folded operands), so a
 * question spelling {@code oral  contraceptive} with two spaces, or breaking the phrase across a
 * line, carries no term. That is the same rule every drug alias is matched under, and giving this
 * one its own would be a caller choosing an allowance of its own.
 */
final class DrugClassTerms {

	/**
	 * Term → the class name a note reports it as, which is the class the QUESTION named and never a
	 * wider or narrower one: {@code hormonal contraceptive} is not a spelling of {@code oral
	 * contraceptive} but a different class, so it reports itself. The value column exists for the
	 * genuine spellings beside it. Insertion-ordered; where two terms of the same length are both
	 * carried the order does decide, which is the residue {@link #namedIn} states.
	 *
	 * <p>Six classes. Two are the issue's — {@code oral contraceptive}, which no ATC subtree
	 * expresses, and {@code NSAID}, which the shipped curated group also names, deliberately both for
	 * the reason the class javadoc gives. The third, {@code hormonal contraceptive}, is not the
	 * issue's: it is here because reporting a hormonal question as the narrower oral class would state
	 * something false about the question in a record the answer may cite. {@code statin}, {@code ACE
	 * inhibitor} and {@code proton pump inhibitor} are ADR Decision 262's, classes a clinician names in
	 * place of a drug ("Is it safe to give her a statin?").
	 *
	 * <p>Lower-cased at the source rather than at the scan: {@link DrugReference#containsWord} folds
	 * and lower-cases both operands for itself, so these are written in the form a reader compares
	 * against the question, not in a form this class has to normalise.
	 */
	private static final Map<String, String> TERMS;

	static {
		Map<String, String> terms = new LinkedHashMap<String, String>();
		terms.put("oral contraceptive", "oral contraceptive");
		terms.put("oral contraceptives", "oral contraceptive");
		terms.put("contraceptive pill", "oral contraceptive");
		terms.put("contraceptive pills", "oral contraceptive");
		terms.put("birth control pill", "oral contraceptive");
		terms.put("birth control pills", "oral contraceptive");
		terms.put("hormonal contraceptive", "hormonal contraceptive");
		terms.put("hormonal contraceptives", "hormonal contraceptive");
		terms.put("nsaid", "NSAID");
		terms.put("nsaids", "NSAID");
		terms.put("non-steroidal anti-inflammatory", "NSAID");
		terms.put("nonsteroidal anti-inflammatory", "NSAID");
		// ADR Decision 262: "Is it safe to give her a statin?" was told only "The records do not address …".
		terms.put("statin", "statin");
		terms.put("statins", "statin");
		terms.put("ace inhibitor", "ACE inhibitor");
		terms.put("ace inhibitors", "ACE inhibitor");
		terms.put("ppi", "proton pump inhibitor");
		terms.put("ppis", "proton pump inhibitor");
		terms.put("proton pump inhibitor", "proton pump inhibitor");
		terms.put("proton pump inhibitors", "proton pump inhibitor");
		// ADR Decision 368: "Is she on any blood thinners?" told a patient on heparin that none is recorded.
		terms.put("blood thinner", "blood thinner");
		terms.put("blood thinners", "blood thinner");
		terms.put("antithrombotic", "blood thinner");
		terms.put("antithrombotics", "blood thinner");
		// ADR Decision 379: "Can I give her a fluoroquinolone?" and "Is she on more than one steroid?" went to the model.
		terms.put("fluoroquinolone", "fluoroquinolone");
		terms.put("fluoroquinolones", "fluoroquinolone");
		terms.put("steroid", "steroid");
		terms.put("steroids", "steroid");
		terms.put("corticosteroid", "corticosteroid");
		terms.put("corticosteroids", "corticosteroid");
		// ADR Decision 385: "Can I give her a live vaccine?" went to the model. The data carries some live vaccines by name and
		// under no ATC code, so the class has no group: the answer asks for the vaccine by name, which is checked.
		terms.put("live vaccine", "live vaccine");
		terms.put("live vaccines", "live vaccine");
		// ADR Decision 407: "Can I give her a macrolide?", "… an opioid?", "… a beta blocker?" went to the model. Not "antibiotic",
		// which a question asking the module to choose one names (ADR Decision 293).
		terms.put("macrolide", "macrolide");
		terms.put("macrolides", "macrolide");
		terms.put("benzodiazepine", "benzodiazepine");
		terms.put("benzodiazepines", "benzodiazepine");
		terms.put("opioid", "opioid");
		terms.put("opioids", "opioid");
		terms.put("beta blocker", "beta blocker");
		terms.put("beta blockers", "beta blocker");
		terms.put("beta-blocker", "beta blocker");
		terms.put("beta-blockers", "beta blocker");
		terms.put("antifungal", "antifungal");
		terms.put("antifungals", "antifungal");
		TERMS = Collections.unmodifiableMap(terms);
	}

	/**
	 * The WHO ATC group a class named here IS, by its own name — the one exception to the rule that a class is never
	 * resolved to an ATC subtree, and only to say which of HER orders the data files in that group (ADR Decision 275).
	 * Read off the shipped knowledge base's {@code atc} arrays on 2026-10-15, each group holds that class's members and no
	 * other substance: {@code C10AA} the eight statins (not nystatin or pentostatin, which only sound like one),
	 * {@code C09AA} ten ACE inhibitors (enalaprilat carries no ATC code at all, the residue), {@code A02BC} the proton pump
	 * inhibitors with vonoprazan, which WHO files there. Never for a class whose group was measured not to be the class —
	 * {@code oral contraceptive}, ADR Decision 67 — nor for {@code NSAID}, whose members span groups.
	 */
	private static final Map<String, String> ATC_GROUP_OF_CLASS;

	static {
		Map<String, String> groups = new LinkedHashMap<String, String>();
		groups.put("statin", "C10AA");
		groups.put("ACE inhibitor", "C09AA");
		groups.put("proton pump inhibitor", "A02BC");
		// B01A, the antithrombotic agents: the anticoagulants and the platelet inhibitors, low-dose aspirin among them,
		// with the thrombolytics and the prostacyclin analogues WHO files there (ADR Decision 368).
		groups.put("blood thinner", "B01A");
		// J01MA, the fluoroquinolones (ADR Decision 379).
		groups.put("fluoroquinolone", "J01MA");
		// The corticosteroids WHO files under no one group: every subgroup its index NAMES corticosteroids — systemic,
		// dermatological, inhaled, nasal, ophthalmic, otological, intestinal, rectal, for acne and for oral use — read with
		// the shipped knowledge base's atc arrays on 2026-10-20, where budesonide, beclometasone and fluticasone carry no H02
		// code at all (ADR Decision 379). A steroid question is answered over the corticosteroids, and says so.
		String corticosteroids = "corticosteroid: H02, D07A, R03BA, R01AD, S01BA, S02BA, S03BA, A07EA, C05AA, D10AA, A01AC";
		groups.put("corticosteroid", corticosteroids);
		groups.put("steroid", corticosteroids);
		// Read with the shipped knowledge base's atc arrays on 2026-10-22, each group holding that class's members and no other
		// substance (ADR Decision 407): J01FA the macrolides; N05BA, N05CD and N03AE (clonazepam) the benzodiazepines; N02A the
		// opioids — not N07BC, which files lofexidine beside methadone, so methadone, filed there alone, is the residue; C07 the
		// beta blockers; J02A and D01B the antifungals — not D01A, which files salicylic acid and selenium sulfide.
		groups.put("macrolide", "J01FA");
		groups.put("benzodiazepine", "benzodiazepine: N05BA, N05CD, N03AE");
		groups.put("opioid", "N02A");
		groups.put("beta blocker", "C07");
		groups.put("antifungal", "antifungal: J02A, D01B");
		ATC_GROUP_OF_CLASS = Collections.unmodifiableMap(groups);
	}

	/**
	 * The groups of her own medications a question may ask about by name — "his ARVs", "her TB drugs", "his antibiotics" —
	 * each the WHO ATC codes it covers, read with the shipped knowledge base's {@code atc} arrays on 2026-11-12 (ADR Decision
	 * 630, the owner's direction that an answer carries nothing beyond what was asked). The antiretrovirals: J05AE, J05AG,
	 * J05AJ and J05AR whole, every member HIV; of J05AF the HIV members only, not entecavir or telbivudine; of J05AX
	 * enfuvirtide, maraviroc, ibalizumab, fostemsavir and lenacapavir, not maribavir, letermovir, tecovirimat or baloxavir.
	 * The TB drugs: J04A, which also files rifamycin; streptomycin, filed under J01GA, is the residue. The antibiotics: J01,
	 * the antibacterials for systemic use, which does not file the TB drugs.
	 */
	private static final Map<String, String> ATC_GROUP_OF_HER_MEDICATIONS;

	/** The words naming each of {@link #ATC_GROUP_OF_HER_MEDICATIONS}' groups. */
	private static final Map<String, Pattern> WORDS_OF_HER_MEDICATION_GROUP;

	static {
		Map<String, String> groups = new LinkedHashMap<String, String>();
		groups.put("antiretroviral", "antiretroviral: J05AE, J05AF01, J05AF02, J05AF03, J05AF04, J05AF05, J05AF06, J05AF07, "
				+ "J05AF09, J05AF13, J05AG, J05AJ, J05AR, J05AX07, J05AX09, J05AX23, J05AX29, J05AX31");
		groups.put("TB drug", "J04A");
		groups.put("antibiotic", "J01");
		ATC_GROUP_OF_HER_MEDICATIONS = Collections.unmodifiableMap(groups);
		String noun = "(?:drugs?|meds?|medications?|medicines?|treatments?|therapy|therapies|regimens?|pills|tablets)";
		Map<String, Pattern> words = new LinkedHashMap<String, Pattern>();
		// A noun two groups share: "her TB and HIV meds", "his HIV and TB drugs".
		String hiv = "(?:hiv|arv|antiretroviral)";
		String tb = "(?:tb|tuberculosis|anti-?tb|anti-?tuberculosis|antitubercular)";
		words.put("antiretroviral", Pattern.compile("\\b(?:arvs?|art|anti-?retrovirals?|haart|" + hiv + " " + noun + "|" + hiv
				+ "(?= and " + tb + " " + noun + "))\\b", Pattern.CASE_INSENSITIVE));
		words.put("TB drug", Pattern.compile("\\b(?:" + tb + " " + noun + "|anti-?tbs?|" + tb + "(?= and (?:" + hiv + " " + noun
				+ "|(?:his |her |their )?(?:arts?|arvs?|haart|anti-?retrovirals?)\\b)))\\b", Pattern.CASE_INSENSITIVE));
		words.put("antibiotic", Pattern.compile("\\b(?:antibiotics?|antibacterials?)\\b", Pattern.CASE_INSENSITIVE));
		WORDS_OF_HER_MEDICATION_GROUP = Collections.unmodifiableMap(words);
	}

	/** A possessive and where the words after it start: "her diet", "his other meds", "her TB treatment". */
	private static final Pattern A_THING_OF_HERS = Pattern.compile("\\b(?:(?i:his|her|their|the patient's|this patient's)|"
		// and her own name's: "Do Mary's ARVs interact with her TB drugs?"
		+ "\\p{Lu}\\p{Ll}+'s) (?=[\\p{L}])");

	/** A verb giving her something, before which "her" and a drug class is the class proposed for her and never one of her
	 *  groups: "give her statins with her ARVs" proposes statins (ADR Decision 631). Her ARVs and TB drugs given her stay
	 *  hers, "Is it safe to give her ARVs and TB drugs together?". */
	private static final Pattern GIVEN_TO_HER = Pattern.compile("\\b(?:give|giving|gave|start|starting|prescribe|prescribing|"
			+ "put|putting|offer|offering|recommend|recommending) $", Pattern.CASE_INSENSITIVE);

	/** A verb putting her back on something, before which a class of hers is one she is not taking now, proposed again —
	 *  "restart his statins with his ARVs" — whoever's it is (ADR Decision 631). */
	private static final Pattern RESTARTED = Pattern.compile("\\b(?:restart|restarting|re-start|resume|resuming|reintroduce|"
			+ "reintroducing|back on) $", Pattern.CASE_INSENSITIVE);

	/** The words saying her medications as a whole, however a question qualifies them: "his own medications", "his home
	 *  meds", "his prescribed drugs" (ADR Decision 631). */
	private static final Pattern ALL_OF_THEM = Pattern.compile("(?i)(?:(?:own|home|prescribed|outpatient|discharge|listed|"
			+ "ordered|recorded|other|current|active|usual|regular|daily|present|existing|all) ){1,2}(?:medications?|meds|"
			+ "medicines?|drugs?|pills|tablets|prescriptions|treatments?|regimens?)");

	/** An aspect of a group, after its words, that a question asks of rather than the group: "his statin dose", "his ARV
	 *  levels", "his TB drug side effects" (ADR Decision 631). */
	private static final Pattern AN_ASPECT = Pattern.compile("(?i) (?:doses?|dosages?|dosing|levels?|side|adherence|refills?|"
			+ "schedules?|timing|costs?|supply|stock|duration|course)\\b");

	/** A form of a drug after a group's words, which narrows the group to that form: "his steroid inhaler", "his antifungal
	 *  cream", "his antibiotic eye drops" — a group nothing here resolves (ADR Decision 631). */
	private static final Pattern A_FORM = Pattern.compile("(?i) (?:inhalers?|puffers?|pumps?|creams?|ointments?|gels?|lotions?|"
			+ "sprays?|patch(?:es)?|drops|eye|ear|nasal|topical|injections?|shots?|suppositor\\w+|pessar\\w+)\\b");

	/** Her vaccines, a group of her medications nothing here resolves: "Do his vaccines interact with his other medications?"
	 *  was given his whole screen (ADR Decision 631). Read here, not among the router's nouns, which other rules share. */
	private static final Pattern HER_VACCINES = Pattern.compile("(?i)(?:[\\p{L}-]+ )?(?:vaccines|vaccinations|immuni[sz]ations)");

	/** The words a group of hers may open with that leave it the group it names, her orders as they stand: "his current
	 *  ARVs". Never one that narrows or moves it — "his previous ARVs", "his inhaled steroids", "his TB antibiotics" — which
	 *  is then a group nothing here resolves (ADR Decision 631). */
	private static final Pattern AS_THEY_STAND = Pattern.compile("(?i)current|active|usual|regular|daily|present|ongoing|"
			+ "existing|all");

	/** "TB" or "HIV" alone, joined to another group of hers that shares its noun: "his ARVs and TB", "his ART and TB". */
	private static final Map<String, Pattern> BARE_GROUP_WORD;

	static {
		Map<String, Pattern> bare = new LinkedHashMap<String, Pattern>();
		bare.put("antiretroviral", Pattern.compile("(?i)hiv\\b"));
		bare.put("TB drug", Pattern.compile("(?i)(?:tb|tuberculosis)\\b"));
		BARE_GROUP_WORD = Collections.unmodifiableMap(bare);
	}

	/** Words joining a second thing to one of hers under its possessive: "his ARVs and TB drugs", "his statins and his BP
	 *  meds". Never a bare comma: "His TB drugs, do they interact with each other?". */
	private static final Pattern JOINED = Pattern.compile("^(?:,? and|,? or| &|,) (?:(?:his|her|their) )?(?=[\\p{L}])",
		Pattern.CASE_INSENSITIVE);

	/** What makes a group's word a nutrient or a food rather than her medications: "vitamin K rich foods", "vitamin B12
	 *  levels" (ADR Decision 631). */
	private static final Pattern A_NUTRIENT_AFTER = Pattern.compile("(?: \\p{Lu}\\d*\\b| (?i:rich|foods?|containing|levels?|"
			+ "deficien\\w*|intake)\\b)");

	/** Words naming medicines not ordered for her, before which a group's words are no group of hers: "his over-the-counter
	 *  drugs", "his herbal supplements" (ADR Decision 631). */
	private static final Pattern NOT_ORDERED = Pattern.compile("(?i)over-the-counter|otc|herbal|traditional|natural|"
			+ "alternative|homeopathic|homoeopathic|recreational|street|illicit|non-prescription|nonprescription");

	/** A word opening a phrase that asks or chooses rather than names a group of hers: "any other meds", "which ARV". */
	private static final Pattern ASKING_OR_CHOOSING = Pattern.compile("(?i)(?:any|which|what|whichever|some|these|those|other|"
			+ "another|each|every|no|more|safer|alternative|alternatives|new)\\b");

	/** A word opening a possessive, never one of a thing's words: "his statins and his BP meds" is two things. */
	private static final Pattern A_POSSESSIVE_WORD = Pattern.compile("(?i)his|her|their|the|this|and|or|with|to|for|of|in|on");

	/**
	 * What each thing a question names as hers is, in the order it names them (ADR Decisions 630, 631) — after a possessive,
	 * or joined to one by "and": one of {@link #ATC_GROUP_OF_HER_MEDICATIONS}' groups by its words; a drug class this module
	 * resolves to the drug data's codes, by a term of {@link #TERMS} or a curated group's name — "his antifungals", "his
	 * statins", "his NSAIDs"; her medications as a whole; a group of her medications by a noun of medication that nothing
	 * here resolves — "his BP meds", "his antihypertensives", or a class whose members no code names, "her oral
	 * contraceptives"; or something else, "her diet". Never after "her" given something: "give her statins".
	 */
	private static List<ThingOfHers> thingsOfHers(String question, List<CrossReactivityGroup> groups) {
		List<ThingOfHers> things = new ArrayList<ThingOfHers>();
		if (question == null) {
			return things;
		}
		Matcher possessive = A_THING_OF_HERS.matcher(question);
		int from = 0;
		while (possessive.find(from)) {
			from = possessive.end();
			String before = question.substring(0, possessive.start());
			boolean givenHer = possessive.group().toLowerCase(Locale.ROOT).startsWith("her") && GIVEN_TO_HER.matcher(before).find()
					|| RESTARTED.matcher(before).find();
			int at = possessive.start();
			int start = possessive.end();
			String joinedTo = null;
			boolean own = true;
			while (start >= 0) {
				ThingOfHers thing = thingAt(question, at, start, groups, joinedTo);
				thing.ownPossessive = own;
				// After a bare comma, only a group: "His TB drugs, do they interact with each other?" asks of them. Nor a joined
				// phrase naming nothing of hers at all: "his ARVs and the rest of his meds".
				if (joinedTo != null && thing.kind == ThingOfHers.Kind.SOMETHING_ELSE
						&& (question.charAt(start - 2) == ',' || thing.name.isEmpty())) {
					break;
				}
				// A class after "give her" is proposed for her, not hers: never one of her groups.
				// Her ARVs and TB drugs stay hers (ADR Decision 630's "give her ARVs and TB drugs together").
				boolean proposed = givenHer && (thing.kind == ThingOfHers.Kind.UNRESOLVED
						|| thing.kind == ThingOfHers.Kind.GROUP && !"antiretroviral".equals(thing.name)
								&& !"TB drug".equals(thing.name));
				if (!proposed) {
					things.add(thing);
				}
				from = Math.max(from, thing.end);
				Matcher joined = JOINED.matcher(question.substring(thing.end));
				start = thing.kind != ThingOfHers.Kind.SOMETHING_ELSE && joined.find() ? thing.end + joined.end() : -1;
				own = start >= 0 && joined.group().matches("(?is).*\\b(?:his|her|their) $");
				// Never a phrase joined without its own possessive that opens on a word asking or choosing — "his ARVs and
				// any other meds", "… and which TB drugs are safer?" — which is no group of hers.
				if (start >= 0 && !joined.group().matches("(?i).*\\b(?:his|her|their) $")
						&& ASKING_OR_CHOOSING.matcher(question.substring(start)).lookingAt()) {
					start = -1;
				}
				joinedTo = thing.kind == ThingOfHers.Kind.GROUP ? thing.name : null;
			}
		}
		return things;
	}

	/**
	 * The thing of hers whose words start at {@code start} of {@code question}, its possessive at {@code at}, joined to the
	 * group {@code joinedTo} where it follows one: its group's words where they open it or follow a word of its own ("his
	 * current ARVs", "his oral antibiotics"), else what its first one to three words, each one space from the last, name.
	 */
	private static ThingOfHers thingAt(String question, int at, int start, List<CrossReactivityGroup> groups,
			String joinedTo) {
		String from = question.substring(start);
		Matcher word = Pattern.compile("[\\p{L}-]+").matcher(from);
		List<String> each = new ArrayList<String>();
		List<Integer> starts = new ArrayList<Integer>();
		List<Integer> ends = new ArrayList<Integer>();
		Matcher notOrdered = Pattern.compile("[\\p{L}-]+").matcher(from);
		if (notOrdered.lookingAt() && NOT_ORDERED.matcher(notOrdered.group()).matches()) {
			return new ThingOfHers(ThingOfHers.Kind.SOMETHING_ELSE, notOrdered.group(), at, start, start + notOrdered.end());
		}
		while (each.size() < 3 && word.find() && (each.isEmpty() ? word.start() == 0
				: word.start() == ends.get(ends.size() - 1) + 1 && from.charAt(word.start() - 1) == ' ')
				&& !A_POSSESSIVE_WORD.matcher(word.group()).matches()) {
			each.add(word.group());
			starts.add(word.start());
			ends.add(word.end());
		}
		for (int k = 0; k < Math.max(1, each.size()); k++) {
			if (k > 0 && !AS_THEY_STAND.matcher(each.get(k - 1)).matches()) {
				break;
			}
			String rest = from.substring(each.isEmpty() ? 0 : starts.get(k));
			for (Map.Entry<String, Pattern> group : WORDS_OF_HER_MEDICATION_GROUP.entrySet()) {
				Matcher m = group.getValue().matcher(rest);
				if (m.lookingAt()) {
					int end = start + (each.isEmpty() ? 0 : starts.get(k)) + m.end();
					return withItsForm(question, new ThingOfHers(ThingOfHers.Kind.GROUP, group.getKey(), at, start, end));
				}
			}
			for (int n = k + 1; n <= each.size(); n++) {
				String words = String.join(" ", each.subList(k, n));
				String named = namedIn(words, groups);
				if (named != null && termsOf(named, groups).contains(words.toLowerCase(Locale.ROOT))) {
					int end = start + ends.get(n - 1);
					return withItsForm(question, membersOf(named, groups) != null
							? new ThingOfHers(ThingOfHers.Kind.GROUP, named, at, start, end)
							: new ThingOfHers(ThingOfHers.Kind.UNRESOLVED, String.join(" ", each.subList(0, n)), at, start, end));
				}
			}
		}
		// Alone, or before what is asked of the two: never "his ARVs and TB infection".
		if (joinedTo != null && !each.isEmpty() && (each.size() == 1
				|| each.get(1).matches("(?i)interact\\w*|clash\\w*|together|safe|ok|okay|fine"))) {
			for (Map.Entry<String, Pattern> bare : BARE_GROUP_WORD.entrySet()) {
				if (!bare.getKey().equals(joinedTo) && bare.getValue().matcher(each.get(0)).matches()) {
					return new ThingOfHers(ThingOfHers.Kind.GROUP, bare.getKey(), at, start, start + ends.get(0));
				}
			}
		}
		for (int n = 1; n <= each.size(); n++) {
			String words = String.join(" ", each.subList(0, n));
			int end = start + ends.get(n - 1);
			if (org.openmrs.module.chartsearchai.api.impl.QueryScopeRouter.namesEveryMedicationSheTakes(words)
					|| ALL_OF_THEM.matcher(words).matches()) {
				return new ThingOfHers(ThingOfHers.Kind.EVERY_MEDICATION, words, at, start, end);
			}
			if ((org.openmrs.module.chartsearchai.api.impl.QueryScopeRouter.namesAMedicationGroup(words)
					|| HER_VACCINES.matcher(words).matches()) && !A_NUTRIENT_AFTER.matcher(question.substring(end)).lookingAt()) {
				return new ThingOfHers(ThingOfHers.Kind.UNRESOLVED, words, at, start, end);
			}
		}
		return new ThingOfHers(ThingOfHers.Kind.SOMETHING_ELSE, String.join(" ", each), at, start,
			each.isEmpty() ? start : start + ends.get(ends.size() - 1));
	}

	/** {@code thing}, or where a form of a drug follows its words, the group of that form, which nothing here resolves. */
	private static ThingOfHers withItsForm(String question, ThingOfHers thing) {
		// Its dose, level or schedule is no group: "Is his statin dose safe?"
		Matcher aspect = AN_ASPECT.matcher(question.substring(thing.end));
		if (aspect.lookingAt() && !ATC_GROUP_OF_HER_MEDICATIONS.containsKey(thing.name)) {
			return new ThingOfHers(ThingOfHers.Kind.SOMETHING_ELSE, question.substring(thing.start, thing.end + aspect.end()),
				thing.at, thing.start, thing.end + aspect.end());
		}
		Matcher form = A_FORM.matcher(question.substring(thing.end));
		if (!form.lookingAt()) {
			return thing;
		}
		int end = thing.end + form.end();
		// and a second word of the form: "eye drops"
		Matcher more = A_FORM.matcher(question.substring(end));
		end += more.lookingAt() ? more.end() : 0;
		return new ThingOfHers(ThingOfHers.Kind.UNRESOLVED, question.substring(thing.start, end), thing.at, thing.start, end);
	}

	/** Her other medications, however joined: "her ARVs and other drugs", "the rest of her meds" (ADR Decision 632). */
	private static final Pattern HER_OTHER_MEDICATIONS = Pattern.compile("(?i)\\b(?:(?:any |all |the )?other|rest of (?:his|her|"
			+ "their)) (?:(?:current|active) )?(?:medications?|meds|drugs?|medicines?|orders|prescriptions|tablets|pills)\\b");

	/** Whether every thing {@code question} names as hers is a group this module resolves — never her medications as a
	 *  whole beside it, "her ARVs and her other medications", nor a group it does not resolve, "her ARVs and BP meds" (ADR
	 *  Decision 632). */
	static boolean namesOnlyGroupsOfHers(String question, List<CrossReactivityGroup> groups,
			java.util.function.Predicate<String> namesTheDrugAsked) {
		if (question == null || HER_OTHER_MEDICATIONS.matcher(question).find()) {
			return false;
		}
		boolean aGroup = false;
		for (ThingOfHers thing : thingsOfHers(question, groups)) {
			aGroup |= thing.kind == ThingOfHers.Kind.GROUP;
			// The drug asked about, named as hers, is no other thing: "Is her warfarin safe with her ARVs?"
			if (thing.kind != ThingOfHers.Kind.GROUP && !namesTheDrugAsked.test(thing.name)) {
				return false;
			}
		}
		return aGroup;
	}

	/** Whether {@code groups} are all of {@link #ATC_GROUP_OF_HER_MEDICATIONS}' — Decision 630's own — and so read as that
	 *  decision reads them (ADR Decision 631). */
	static boolean onlyGroupsOfDecision630(List<String> groups) {
		return ATC_GROUP_OF_HER_MEDICATIONS.keySet().containsAll(groups);
	}

	/** One thing a question names as hers, and what {@link #thingsOfHers} found it to be. */
	private static final class ThingOfHers {

		enum Kind { GROUP, EVERY_MEDICATION, UNRESOLVED, SOMETHING_ELSE }

		final Kind kind;

		/** The group's key, or the question's own words for an unresolved group or something else. */
		final String name;

		/** Where its possessive starts, and where its own words start and end, in the question. */
		final int at;

		final int start;

		final int end;

		/** Whether its own possessive opens it, rather than "and" joining it to one. */
		boolean ownPossessive = true;

		ThingOfHers(Kind kind, String name, int at, int start, int end) {
			this.kind = kind;
			this.name = name;
			this.at = at;
			this.start = start;
			this.end = end;
		}
	}

	/**
	 * The groups of her own medications {@code question} names and this module resolves — each a key {@link #membersOf}
	 * answers — in one fixed order whatever order it names them in, so two wordings of one question are answered alike (ADR
	 * Decision 449): {@link #ATC_GROUP_OF_HER_MEDICATIONS}' groups first, then the classes, by name, a class once however
	 * many of its names the question uses ("his steroids and corticosteroids"). Each is named as hers, after a possessive or
	 * joined to one ("his ARVs and TB drugs"); empty where it names none (ADR Decisions 630, 631). "Can I give him an
	 * antibiotic?" names no group of hers.
	 */
	static List<String> herMedicationGroupsNamed(String question, List<CrossReactivityGroup> groups) {
		List<String> named = new ArrayList<String>();
		List<ThingOfHers> things = thingsOfHers(question, groups);
		for (String group : ATC_GROUP_OF_HER_MEDICATIONS.keySet()) {
			for (ThingOfHers thing : things) {
				if (thing.kind == ThingOfHers.Kind.GROUP && thing.name.equals(group) && !named.contains(group)) {
					named.add(group);
				}
			}
		}
		java.util.TreeMap<String, String> classes = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
		for (ThingOfHers thing : things) {
			if (thing.kind == ThingOfHers.Kind.GROUP && !named.contains(thing.name)) {
				String sameAs = atcGroupOf(thing.name) == null ? thing.name : atcGroupOf(thing.name);
				// One name per class whichever the question uses first: the first by name.
				String already = null;
				for (Map.Entry<String, String> named2 : classes.entrySet()) {
					already = named2.getValue().equals(sameAs) ? named2.getKey() : already;
				}
				if (already == null) {
					classes.put(thing.name, sameAs);
				} else if (String.CASE_INSENSITIVE_ORDER.compare(thing.name, already) < 0) {
					classes.remove(already);
					classes.put(thing.name, sameAs);
				}
			}
		}
		named.addAll(classes.keySet());
		return named;
	}

	/**
	 * The first group of her medications {@code question} names by words nothing here resolves to the drug data's codes —
	 * "BP meds" of "Any interactions with his BP meds?", "antihypertensives", "painkillers" — as the question writes it; or
	 * {@code null} where it names none (ADR Decision 631). Which of her orders such words mean is medical knowledge the
	 * module does not hold.
	 */
	static String unresolvedGroupOfHers(String question, List<CrossReactivityGroup> groups) {
		List<ThingOfHers> things = thingsOfHers(question, groups);
		boolean onlyDecision630s = true;
		for (ThingOfHers thing : things) {
			onlyDecision630s &= thing.kind != ThingOfHers.Kind.GROUP || ATC_GROUP_OF_HER_MEDICATIONS.containsKey(thing.name);
		}
		for (ThingOfHers thing : things) {
			// One joined to Decision 630's groups alone, with no possessive of its own, is read as that decision reads it —
			// "his ARVs and vitamin supplements" is about his ARVs — so nothing it answered changes.
			if (thing.kind == ThingOfHers.Kind.UNRESOLVED && (thing.ownPossessive || !onlyDecision630s)) {
				return thing.name;
			}
		}
		return null;
	}

	/**
	 * Whether a set of normalized codes is in {@code group}, a key {@link #herMedicationGroupsNamed} returns: an {@link
	 * #ATC_GROUP_OF_HER_MEDICATIONS} group, a curated group this module carries, or a class whose ATC group IS the class
	 * ({@link #ATC_GROUP_OF_CLASS}) — the one use of a class's group, to say which of HER orders are in it (ADR Decisions
	 * 275, 298, 631); or {@code null} for a name none of them resolves.
	 */
	static java.util.function.Predicate<Set<String>> membersOf(String group, List<CrossReactivityGroup> groups) {
		if (group == null) {
			return null;
		}
		String herGroup = ATC_GROUP_OF_HER_MEDICATIONS.get(group);
		if (herGroup != null) {
			return codes -> DrugClassMembersStatement.filedUnder(codes, herGroup);
		}
		CrossReactivityGroup carried = groupNamedBy(group, groups);
		if (carried != null) {
			return carried::containsAnyCodeForDuplicateTherapy;
		}
		String atc = atcGroupOf(group);
		return atc == null ? null : codes -> DrugClassMembersStatement.filedUnder(codes, atc);
	}

	/**
	 * Whether {@code question} names, after a possessive, something of hers that is neither a group of her medications —
	 * resolved or not — nor her medications as a whole: "her diet" of "Does her diet interact with her ARVs?", which an
	 * answer over the group would leave unsaid (ADR Decisions 630, 631).
	 */
	static boolean namesSomethingElseOfHers(String question, List<CrossReactivityGroup> groups) {
		return namesSomethingElseOfHers(question, groups, true);
	}

	/** As above; with {@code joinedToo} false, only what follows a possessive of its own — Decision 630's reading, by which
	 *  its own groups' questions are read unchanged (ADR Decision 631). */
	static boolean namesSomethingElseOfHers(String question, List<CrossReactivityGroup> groups, boolean joinedToo) {
		for (ThingOfHers thing : thingsOfHers(question, groups)) {
			if (thing.kind == ThingOfHers.Kind.SOMETHING_ELSE && (joinedToo || thing.ownPossessive)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code question} with every group of her medications it names as hers, resolved or not, read as her medications —
	 * "Does grapefruit juice interact with his medications?" of "Does grapefruit juice interact with his statins?" — so
	 * whether it asks of anything beside those groups is read by the rules that read a question about her medications (ADR
	 * Decision 631). A group's words it does not name as hers stay: "Are his ARVs safe with antibiotics?" proposes them.
	 */
	static String withHerGroupsAsHerMedications(String question, List<CrossReactivityGroup> groups) {
		if (question == null) {
			return null;
		}
		List<ThingOfHers> things = thingsOfHers(question, groups);
		StringBuilder out = new StringBuilder(question);
		for (int t = things.size() - 1; t >= 0; t--) {
			ThingOfHers thing = things.get(t);
			if (thing.kind == ThingOfHers.Kind.GROUP || thing.kind == ThingOfHers.Kind.UNRESOLVED) {
				out.replace(thing.start, thing.end, "medications");
			}
		}
		return out.toString();
	}

	/**
	 * The ATC group by which the answer says it read {@code group}, where that is how this module reads the class — " in ATC
	 * group N02A" for the opioids, whose group leaves out methadone — so a statement that none of her orders is in it is
	 * true as said (ADR Decisions 275, 631); empty for her groups and for a curated group.
	 */
	static String readAs(String group, List<CrossReactivityGroup> groups) {
		if (group == null || ATC_GROUP_OF_HER_MEDICATIONS.containsKey(group) || groupNamedBy(group, groups) != null
				|| atcGroupOf(group) == null) {
			return "";
		}
		return " in " + DrugClassMembersStatement.groupPhrase(atcGroupOf(group));
	}

	/** How an answer names {@code group}, a key {@link #herMedicationGroupsNamed} returns, as one of her orders ({@code
	 *  plural} false: "No statin is among …") or as several: "This patient's statins …", "This patient's penicillins …". */
	static String spokenName(String group, boolean plural) {
		String name = group.length() > 1 && Character.isLowerCase(group.charAt(1))
				? Character.toLowerCase(group.charAt(0)) + group.substring(1) : group;
		boolean endsPlural = name.endsWith("s") && !ATC_GROUP_OF_HER_MEDICATIONS.containsKey(group);
		return plural ? (endsPlural ? name : name + "s") : endsPlural ? name.substring(0, name.length() - 1) : name;
	}

	/** Whether {@code question} asks of one group's members among themselves — "with each other", "between his TB drugs" —
	 *  rather than of the group against anything (ADR Decision 630). */
	static boolean asksAmongThemselves(String question) {
		return question != null && Pattern.compile("\\b(?:each other|one another|among (?:them|themselves)|between (?:his|her|"
				+ "their|the patient's|this patient's) |together)\\b", Pattern.CASE_INSENSITIVE).matcher(question).find();
	}

	private DrugClassTerms() {
	}

	/**
	 * The words a question names {@code className} by — the code table's terms for it and, where a curated group carries
	 * that name, the group's own name — lower-cased, for marking the class where a drug's name would stand (ADR Decision
	 * 347). Empty for a class this module does not name.
	 */
	static List<String> termsOf(String className, List<CrossReactivityGroup> groups) {
		List<String> out = new ArrayList<String>();
		if (className == null) {
			return out;
		}
		for (Map.Entry<String, String> term : TERMS.entrySet()) {
			if (term.getValue().equals(className)) {
				out.add(term.getKey());
			}
		}
		if (groups != null) {
			for (CrossReactivityGroup group : groups) {
				if (group != null && className.equalsIgnoreCase(group.getName())) {
					for (String spelled : spellingsOf(group.getName())) {
						if (!out.contains(spelled.toLowerCase(Locale.ROOT))) {
							out.add(spelled.toLowerCase(Locale.ROOT));
						}
					}
				}
			}
		}
		return out;
	}

	/** A group's name as a question may spell it: the name, and it with or without a closing plural "s" — the rule {@link
	 *  #groupNamedBy} reads an allergen by (ADR Decision 363). Empty for no name. */
	private static List<String> spellingsOf(String name) {
		List<String> out = new ArrayList<String>();
		if (name == null || name.isEmpty()) {
			return out;
		}
		out.add(name);
		out.add(name.endsWith("s") ? name.substring(0, name.length() - 1) : name + "s");
		return out;
	}

	/** The ATC group {@code className} IS ({@link #ATC_GROUP_OF_CLASS}), or {@code null} where no group is that class. */
	static String atcGroupOf(String className) {
		return className == null ? null : ATC_GROUP_OF_CLASS.get(className);
	}

	/** The code table's terms, for the two data guards that enforce admission criterion (2) — one
	 *  putting the term itself to the dataset, the other the note the term makes the injector render,
	 *  which is how the class NAME each term reports is reached (every value has a key). It is the
	 *  code table alone: a group's name comes from operator data, which no test in this module can
	 *  speak for. */
	static Set<String> terms() {
		return TERMS.keySet();
	}

	/**
	 * @return the class name of the LONGEST class term {@code prose} carries, or {@code null} where it
	 *         carries none. Longest so that a question spelling a class two ways at once — or a term
	 *         nested in a longer one — reports the more specific of them rather than whichever source
	 *         was consulted first, which would otherwise make the answer depend on the order the
	 *         operator's groups file happens to list its groups in.
	 *
	 *         <p>The residue, named rather than claimed away: the comparison is strict, so two terms
	 *         of EQUAL length that a question carries at once are still decided by order — the curated
	 *         groups ahead of the table below, and within the groups the order the operator's file
	 *         lists them in. Nothing pins that, because nothing distinguishes the two answers on any
	 *         ground this method has: both are class names the question really does carry.
	 *
	 * @param prose the question text; {@code null} carries no term
	 * @param groups the curated cross-reactivity groups, whose {@code name}s are the first source —
	 *        never null in production ({@code DrugReferenceService.getCrossReactivityGroups} answers a
	 *        list), and tolerated as null here so the rule can be read without one. Whitespace is not
	 *        stripped here: {@link CrossReactivityGroup#setName} trims at the writer, so a padded
	 *        operator-authored name — which the loader accepts, rejecting only a blank one — is
	 *        already trimmed by the time any reader, this one or the chip arms, sees it
	 */
	static String namedIn(String prose, List<CrossReactivityGroup> groups) {
		if (prose == null || prose.trim().isEmpty()) {
			return null;
		}
		String bestTerm = null;
		String bestClass = null;
		if (groups != null) {
			for (CrossReactivityGroup group : groups) {
				// Not trimmed here: CrossReactivityGroup.setName trims at the sole WRITER, so every
				// reader — this one and the chip arms that PRINT the name — sees one spelling. A trim
				// at this reader alone named one family two ways in one response.
				String name = group == null ? null : group.getName();
				// Its name with or without a closing plural "s", as groupNamedBy reads an allergen: "penicillin" names the
				// Penicillins group (ADR Decision 363).
				for (String spelled : spellingsOf(name)) {
					if (longerThan(spelled, bestTerm) && DrugReference.containsWord(prose, spelled)) {
						bestTerm = spelled;
						bestClass = name;
					}
				}
			}
		}
		for (Map.Entry<String, String> term : TERMS.entrySet()) {
			if (longerThan(term.getKey(), bestTerm) && DrugReference.containsWord(prose, term.getKey())) {
				bestTerm = term.getKey();
				bestClass = term.getValue();
			}
		}
		return bestClass;
	}

	/**
	 * The curated group a recorded allergen's WHOLE name names, or {@code null} (ADR Decision 231): the group's own name,
	 * that name with or without a closing plural "s" ("Penicillin" for "Penicillins"), or a term of the code table above
	 * whose class is that name ("NSAIDs" for the group "NSAID"). The whole name and never a word inside it, so an allergen
	 * says nothing of a group unless it is that group — what {@link #namedIn} looks for in a question would put "NSAID
	 * eye drops" in the NSAID group. A class the table names that no curated group carries has no members, so names none.
	 *
	 * @param allergen a recorded allergen, as the patient's context holds it; {@code null} names nothing
	 * @param groups the curated cross-reactivity groups; {@code null} names nothing
	 */
	static CrossReactivityGroup groupNamedBy(String allergen, List<CrossReactivityGroup> groups) {
		// DrugReference.normalizeName: the one identity normalisation, which the context's tokens are already in.
		String name = DrugReference.normalizeName(allergen);
		if (name == null || groups == null) {
			return null;
		}
		String tableClass = DrugReference.normalizeName(TERMS.get(name));
		for (CrossReactivityGroup group : groups) {
			String groupName = group == null ? null : DrugReference.normalizeName(group.getName());
			if (groupName == null) {
				continue;
			}
			if (name.equals(groupName) || name.equals(groupName + "s")
					|| groupName.endsWith("s") && name.equals(groupName.substring(0, groupName.length() - 1))
					|| groupName.equals(tableClass)) {
				return group;
			}
		}
		return null;
	}

	/** Whether {@code candidate} is longer than the term already matched, {@code null} counting as no
	 *  match at all — the comparison the longest-match rule is written in terms of, named so the two
	 *  loops above cannot express it differently. */
	private static boolean longerThan(String candidate, String matched) {
		return matched == null || candidate.length() > matched.length();
	}

}
