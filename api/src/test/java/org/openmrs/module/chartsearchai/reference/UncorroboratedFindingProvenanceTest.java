package org.openmrs.module.chartsearchai.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #308: an injected {@code safety_finding} says how its rule was matched, where nothing
 * corroborates that match as a record of the drug.
 *
 * <p>Issue #269 gave the injected {@code drug_reference} record a third section for such a clause —
 * {@code Matched in this patient's chart but not corroborated as a record of this drug:} — and left
 * the {@code safety_finding} beside it alone. Measured live afterwards (issue #308, on a real
 * standalone with the real model): the model answers from the finding and never surfaces the hedge,
 * because the finding is the unqualified one of the two. So the prompt carried two citable records of
 * one fact, one qualified and one bare, and the bare one won.
 *
 * <p><b>What this change does NOT do, and the measurement that decides it.</b> The finding keeps
 * {@code STRENGTH_WITHHOLD}. The tempting fix — a third strength class between withholding and a
 * caution — was refuted at plan time on two recorded measurements. ADR Decision 42 deferred
 * "whether the finding itself should state its PROVENANCE", provenance and not strength; and it
 * records that the corroboration union can be WRONG in the false-negative direction, on a clause
 * "both legs miss" while the chart really does hold the allergy. ADR Decision 37 measured what a
 * contraindication finding stating no withholding clause produces on this very drug and question
 * shape: <em>"No — ibuprofen should not be taken"</em> became <em>"Ibuprofen can be given, with one
 * caution"</em>, 3 of 3, with the chip byte-identical. Weakening the call on a gate that can be wrong
 * that way is fail-open in a safety net, so this change is MONOTONE: it adds words and moves no call.
 * {@link #theFindingStillStatesTheStrongestCallItStatedBefore} is that invariant, and it is the case
 * to read first if a future change wants the strength to move.
 *
 * <p>Prompt-facing only, exactly as issue #283 scoped its own clauses: the chip's detail, the chip's
 * rank and the {@code safetyWarnings} wire shape are untouched, so issues #146 and #223 — which twice
 * refused to GATE this chip on corroboration — are not reopened. The chip is still raised; it now
 * says how it was matched when it reaches the model.
 *
 * <p>Every case here drives the real {@code DrugReferenceInjector.injectRecords} wired to the real
 * {@code DrugSafetyValidator} over a fixture parsed by the real production parser, and reads the
 * record a model would read.
 */
public class UncorroboratedFindingProvenanceTest {

	/** Issue #223's own fixture: {@code Opium} rules on its own name and publishes a SECOND name
	 *  ({@code papaveretum}) that does not contain that token, and {@code Levothyroxine} rules on
	 *  {@code thyroxine}, which an allergy recorded as {@code Levothyroxine} reaches only mid-word
	 *  while naming the entry outright. */
	private static final String MID_WORD_TOKEN =
			"chartsearchai-test/drug-reference-mid-word-allergy-token.json";

	/** Issue #269's own: {@code Tramadol} files a self-named rule, a class-token rule and a condition
	 *  rule at once, and {@code Levoketoconazole} files TWO self-named rules that collapse onto one
	 *  ledger key and disagree about corroboration. */
	private static final String BORROWED_ALIAS =
			"chartsearchai-test/drug-reference-borrowed-alias-corroboration.json";

	/** Read off production, so no case below can pass against a clause no record carries. What pins
	 *  the WORDS is {@link #theClauseIsTheWordsAModelReads}, and only that. */
	/** Issue #308's own: two rule-bearing ROWS of one substance, which the chip ledger folds and the
	 *  injected record does not. */
	private static final String RULE_ROWS_ONE_SUBSTANCE =
			"chartsearchai-test/drug-reference-rule-rows-one-substance.json";

	/** Issue #308's own: one entry, two self-named rules that collapse onto one key and DISAGREE about
	 *  corroboration while tying on rank. */
	private static final String COLLAPSED_KEY =
			"chartsearchai-test/drug-reference-collapsed-key-corroboration.json";

	private static final String CLAUSE = DrugReferenceInjector.FINDING_UNCORROBORATED_MATCH;

	private static final String WITHHOLD = DrugReferenceInjector.STRENGTH_WITHHOLD;

	private static List<String> findings(String fixture, String question, String... allergens)
			throws IOException {
		DrugReferenceService service =
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(fixture));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set(allergens), null),
						question);
		List<String> texts = new ArrayList<String>();
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			texts.add(finding.getText());
		}
		return texts;
	}

	/** The one finding of an arrangement that must raise exactly one — asserted rather than assumed,
	 *  because every case below turns on a single record's text and a second finding would let the
	 *  wrong one answer for it. */
	private static String onlyFinding(String fixture, String question, String... allergens)
			throws IOException {
		List<String> findings = findings(fixture, question, allergens);
		assertEquals(1, findings.size(), "one fact is one citable record, was: " + findings);
		return findings.get(0);
	}

	@Test
	public void theClauseIsTheWordsAModelReads() {
		// The one case here that asserts the LITERAL; every other assertion reads the constant and so
		// compares it to itself. Its counterpart for the drug_reference channel is
		// InjectedContraindicationCorroborationTest.theThreeSectionLeadsAreTheWordsAModelReads, whose
		// own javadoc records that rewording an unpinned lead left the whole api suite green.
		//
		// The two channels are deliberately NOT byte-shared, though they report one fact. That lead is
		// a colon-terminated section HEAD whose object is supplied by the clause after it, so a
		// well-formed sentence cannot be a substring of it; deriving one from the other would also put
		// that single case silently in charge of prompt text in a second channel it was never written
		// for. What binds them is substance, and this pairing of javadocs.
		//
		// Three properties of the wording, each load-bearing:
		//   * it NAMES ITS SUBJECT ("This module"), so it is not a dangling participle whose implied
		//     subject is the previous sentence's object;
		//   * it OPENS by asserting that a record was matched. That is the negation of the antecedent
		//     of the prompt's opposite branch — LlmProvider's "when no record addresses the drug or
		//     intervention asked about, the whole answer is one sentence stating that the records do
		//     not address it" — which a clause reading only "not a record of this drug", inside a
		//     record type the same prompt says IS about this patient, sits close to. A flip to that
		//     branch would be fail-open, which is why the assertion is on the whole sentence;
		//   * it says what the MODULE established and not a categorical about the chart, ADR Decision
		//     42's own measured constraint: both corroborating legs can miss an allergy the chart
		//     really holds, so a clause claiming the chart holds none is one the chart can contradict.
		assertEquals(" This module matched that record in this patient's chart by its wording alone "
				+ "and could not corroborate it as a record of this drug.", CLAUSE);
	}

	@Test
	public void aRuleOnlyABareContainmentMatchSupportsSaysSoInTheFindingItself() throws IOException {
		// THE case, and the ticket's own shape one fixture over: the patient's only recorded allergy is
		// `Tiotropium`, which CONTAINS the rule's token `opium` and which neither corroborating question
		// can reach — allergensMatching("opium") yields [tiotropium], which does not NAME Opium, and
		// findImpliedSubstances("Tiotropium") is [Tiotropium], which is not Opium's substance. Before
		// this change the same arrangement rendered the first sentence and the strength clause with
		// nothing between them, while the drug_reference record beside it hedged the identical clause.
		assertEquals(DrugReferenceInjector.FINDING_PREFIX
				+ "Opium: Opium is contraindicated by an active allergy: documented opium allergy."
				+ CLAUSE + WITHHOLD,
				onlyFinding(MID_WORD_TOKEN, "Is it safe to give her opium?", "Tiotropium"));
	}

	@Test
	public void theFindingStillStatesTheStrongestCallItStatedBefore() throws IOException {
		// The invariant the whole change is shaped around, asserted on its own so it is greppable: the
		// clause is ADDITIVE. A future change that wants to move the call has to redden this, and ADR
		// Decisions 37 and 42 are what it has to answer.
		String finding = onlyFinding(MID_WORD_TOKEN, "Is it safe to give her opium?", "Tiotropium");

		assertTrue(finding.endsWith(WITHHOLD),
				"the strength clause must stay sentence-final, where the prompt's own demonstrations "
						+ "put it, was: " + finding);
		assertFalse(finding.contains(DrugReferenceInjector.STRENGTH_CAUTION),
				"and a contraindication is never a caution at any rating, was: " + finding);
	}

	@Test
	public void theChipTheClinicianSeesIsTheStringItWas() throws IOException {
		// Prompt-facing ONLY, the scope issue #283 set for its own clauses and the reason this change
		// does not reopen issues #146 and #223, which twice refused to gate this chip on corroboration.
		// The chip is still raised, its rank is what it was, and its DETAIL — the string that reaches
		// the clinician and the `safetyWarnings` wire — carries none of this. Asserted directly rather
		// than left to the suite: every other case here reads the injected record, so a change that put
		// the clause on the warning's detail instead of on the rendered line would satisfy all of them.
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(MID_WORD_TOKEN));
		List<String> chips = DrugReferenceTestSupport.contraindicationDetails(
				DrugReferenceTestSupport.validator(service).validate("", "Is it safe to give her opium?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("Tiotropium"), null)));

		assertEquals(java.util.Collections.singletonList(
				"Opium is contraindicated by an active allergy: documented opium allergy"), chips,
				"the clinician-facing chip is byte-identical, clause and strength clause alike");
	}

	@Test
	public void aRuleTheChartsOwnRecordNamesCarriesNoClause() throws IOException {
		// The control that separates this from appending the clause unconditionally: the same entry, the
		// same rule, and an allergy recorded under the very name the token is. Leg 1 corroborates.
		assertEquals(DrugReferenceInjector.FINDING_PREFIX
				+ "Opium: Opium is contraindicated by an active allergy: documented opium allergy."
				+ WITHHOLD, onlyFinding(MID_WORD_TOKEN, "Is it safe to give her opium?", "Opium"));
	}

	@Test
	public void aRuleWhoseEntryTheChartNamedCarriesNoClauseThoughItsTokenSitsMidWord()
			throws IOException {
		// The half of leg 1 that is asked of the ENTRY rather than of the token, and the shape
		// contraindicationRank's javadoc says a token-scoped question demotes wrongly: Levothyroxine
		// publishes `thyroxine` among its own names and rules on THAT name, so an allergy recorded as
		// `Levothyroxine` reaches the rule only mid-word while naming the entry outright. The operator's
		// note is the one thing in the response that says what the reaction was, and it must not be
		// qualified.
		assertEquals(DrugReferenceInjector.FINDING_PREFIX
				+ "Levothyroxine: Levothyroxine is contraindicated by an active allergy: documented "
				+ "thyroxine allergy — anaphylaxis." + WITHHOLD,
				onlyFinding(MID_WORD_TOKEN, "Is it safe to give her levothyroxine?", "Levothyroxine"));
	}

	@Test
	public void aRuleThatIsNotSelfNamedCarriesNoClauseThoughTheAllergenArmResolvesNothing()
			throws IOException {
		// The scope, and it is load-bearing rather than incidental — the same scope corroborated() and
		// the chip's own demotion take. A rule whose token is not one of its entry's names is asking
		// about a CLASS or about a fragment of free text, which is what the bare match exists for, and
		// neither corroborating question can speak to it: findImpliedSubstances resolves nothing at all
		// from an allergy recorded as `NSAIDs`. Unscoped, this clause would qualify a correct finding.
		assertEquals(DrugReferenceInjector.FINDING_PREFIX
				+ "Tramadol: Tramadol is contraindicated by an active allergy: NSAID hypersensitivity."
				+ WITHHOLD,
				onlyFinding(BORROWED_ALIAS, "Is it safe to give her tramadol?", "NSAIDs"));
	}

	@Test
	public void twoRulesOfOneEntryAreEachAnsweredOnTheirOwnMatch() throws IOException {
		// The clause is per RULE and not per entry or per drug. Tramadol files a self-named rule on
		// `trama`, which an allergen recorded as `Tramazoline` reaches only mid-word, and a class-token
		// rule on `nsaid`, which an allergen recorded as `NSAIDs` matches and which is corroborated by
		// construction. They key differently — contraindicationFinding keys a self-named allergy rule on
		// the SUBSTANCE (issue #146) and every other rule on its own (type, token) — so both survive as
		// findings, and one of the two carries the clause.
		List<String> findings =
				findings(BORROWED_ALIAS, "Is it safe to give her tramadol?", "Tramazoline", "NSAIDs");

		assertEquals(2, findings.size(), "two rules on two keys are two citable records, was: "
				+ findings);
		assertTrue(findings.contains(DrugReferenceInjector.FINDING_PREFIX
				+ "Tramadol: Tramadol is contraindicated by an active allergy: documented tramadol "
				+ "allergy." + CLAUSE + WITHHOLD),
				"the self-named rule nothing corroborates must say so, was: " + findings);
		assertTrue(findings.contains(DrugReferenceInjector.FINDING_PREFIX
				+ "Tramadol: Tramadol is contraindicated by an active allergy: NSAID hypersensitivity."
				+ WITHHOLD),
				"and the class-token rule beside it must not, was: " + findings);
	}

	@Test
	public void theClauseFollowsTheRuleThatWonTheCollapsedKey() throws IOException {
		// Two self-named rules of ONE entry collapse onto one ledger key (issue #146 keys both on the
		// substance), so only the warning that WON the key is ever rendered — and the clause has to be
		// the winner's, not that of whichever rule the walk reached last. Levoketoconazole rules on
		// `ketoconazole`, which an allergy recorded as `Ketoconazole` names outright, and on `levo`,
		// which an allergy recorded as `Levocetirizine` reaches only mid-word.
		//
		// Both together: the corroborated rule outranks (SELF_NAMED_RULE over
		// SELF_NAMED_RULE_MATCHED_BY_CONTAINMENT_ALONE), so the finding is its sentence and carries no
		// clause.
		assertEquals(DrugReferenceInjector.FINDING_PREFIX
				+ "Levoketoconazole: Levoketoconazole is contraindicated by an active allergy: "
				+ "documented ketoconazole allergy." + WITHHOLD,
				onlyFinding(BORROWED_ALIAS, "Is it safe to give her levoketoconazole?",
						"Ketoconazole", "Levocetirizine"));

		// The mid-word one alone: it holds the key unopposed, and says so.
		assertEquals(DrugReferenceInjector.FINDING_PREFIX
				+ "Levoketoconazole: Levoketoconazole is contraindicated by an active allergy: "
				+ "documented levo allergy." + CLAUSE + WITHHOLD,
				onlyFinding(BORROWED_ALIAS, "Is it safe to give her levoketoconazole?",
						"Levocetirizine"));
	}

	@Test
	public void aRuleWithNoNoteOfItsOwnIsAnsweredOnItsMatchAndNotOnItsWording() throws IOException {
		// The clause is decided by corroboratedByTheChart, which is handed the rule and the chart and
		// never the NOTE — so a rule with nothing of its own to say is answered exactly like one that
		// has. That is a combination the chip's RANK cannot express: contraindicationRank returns
		// SELF_NAMED_RULE_WITHOUT_A_NOTE for a blank note WITHOUT asking whether the match was
		// corroborated, and both disqualifications share the value 0, so nothing about the chip could
		// tell these apart. It is what aMatchedRecordNamesTheEntry's extraction was for — "a blank note
		// changes what a clause SAYS, not what its match rests on".
		//
		// The arrangement is issue #308's own reported one, to the drug: this fixture's Ibuprofen files
		// a blank-note rule on its own name, and `Dexibuprofen` is a real drug in which `ibuprofen` sits
		// mid-word. firstNonBlank then renders the token back, which is the sentence that says strictly
		// less than the allergen arm's — and it now says how it was matched.
		assertEquals(DrugReferenceInjector.FINDING_PREFIX
				+ "Ibuprofen: Ibuprofen is contraindicated by an active allergy: ibuprofen."
				+ CLAUSE + WITHHOLD,
				onlyFinding("chartsearchai-test/drug-reference-self-named-rule-shapes.json",
						"Can I give him ibuprofen?", "Dexibuprofen"));
	}

	@Test
	public void theOrderDrivenArmAsksTheWholeAllergyListAndNotTheOneTheQuestionIsAbout()
			throws IOException {
		// The order-driven arm (issue #143) holds a SECOND, narrowed allergy list beside the one the
		// corroboration union reads — allergensAskedAbout, the records this response is about — and hands
		// that one to the allergen arm on its subject-matter-gated branch. Leg 2 must not take it: it is
		// the allergen arm's own identity question asked over the WHOLE list (ADR Decision 42), so
		// narrowing it would report a finding as uncorroborated on the strength of the question's
		// wording.
		//
		// It is also the only arrangement in which leg 2 changes a FINDING at all, and that is mechanism
		// rather than an accident of this fixture: leg 2 is true exactly when some recorded allergy
		// resolves to a row of this substance, which is the same fact that makes the allergen arm raise
		// its IDENTITY chip — rank 3, on this very key, over the demoted rule's 0. So wherever that arm
		// is handed the whole list, its own sentence replaces the rule's before the clause could be
		// read. Here it is handed the NARROWED one, so the rule's sentence survives while the union
		// still reads the whole chart. Measured by mutation: dropping leg 2 reddens this case and two in
		// InjectedContraindicationCorroborationTest, and nothing else.
		//
		// The arrangement separates the two lists. Opium is an active ORDER and the question does not
		// name it, so the gated branch is what runs; the question names `tiotropium`, which contains the
		// rule's token `opium`, so the rule is in subject matter while the drug is not. Two allergies:
		// `Tiotropium`, which fires the rule mid-word and does not NAME Opium, and `Papaveretum`, which
		// is Opium's own second name and which the response is NOT about. Only the whole list reaches it,
		// and reaching it is what makes this finding corroborated.
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(MID_WORD_TOKEN));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set("Opium"),
								null, DrugReferenceTestSupport.set("Tiotropium", "Papaveretum"), null),
						"Is tiotropium suitable for her?");

		List<String> opium = new ArrayList<String>();
		for (RecordMapping finding : DrugReferenceTestSupport.injectedFindings(chart)) {
			if (finding.getText().contains("Opium is contraindicated")) {
				opium.add(finding.getText());
			}
		}
		List<String> all = new ArrayList<String>();
		for (RecordMapping f : DrugReferenceTestSupport.injectedFindings(chart)) {
			all.add(f.getText());
		}
		assertEquals(1, opium.size(), "the gated order-driven branch must raise this rule once, was: "
				+ all);
		assertFalse(opium.get(0).contains(CLAUSE),
				"a recorded allergy the question is not about still corroborates the match, was: "
						+ opium.get(0));
	}

	@Test
	public void oneCorroboratedRuleOfACollapsedKeyClearsTheClauseForTheWholeKey() throws IOException {
		// The two injected channels must not disagree about ONE key, which is the whole of what #308 is
		// for — and the rank cannot carry that answer. contraindicationFinding keys both of this entry's
		// self-named rules on the SUBSTANCE (issue #146), so they are one chip and one rendered clause,
		// while each rule is put to the chart on its own token: `levo` reaches an allergy recorded as
		// `Levocetirizine` only mid-word, and `ketoconazole` is named outright by one recorded as
		// `Ketoconazole`. They TIE at rank 0 — contraindicationRank answers
		// SELF_NAMED_RULE_MATCHED_BY_CONTAINMENT_ALONE for the first and, WITHOUT asking corroboration at
		// all, SELF_NAMED_RULE_WITHOUT_A_NOTE for the second — so the ledger's incumbent-keeps tiebreak
		// leaves the uncorroborated rule's sentence standing.
		//
		// The record resolves this as a MAX ("one corroborated rule of the key is enough for the key",
		// DrugReferenceInjector.contraindicationSections) and marks the clause RECORDED. Before the
		// ledger did the same, the finding beside it said the module could not corroborate the match:
		// two citable records of one chart, in one injection, contradicting each other. Mutate
		// ContraindicationChips.add's AND to take the rank winner's own answer and read the failure.
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(COLLAPSED_KEY));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("Ketoconazole", "Levocetirizine"), null),
						"Is it safe to give her levoketoconazole?");
		String record = DrugReferenceTestSupport.referenceTextNaming(chart, "Levoketoconazole");
		List<String> findings = new ArrayList<String>();
		for (RecordMapping f : DrugReferenceTestSupport.injectedFindings(chart)) {
			findings.add(f.getText());
		}

		// Precondition: the record really does state the collapsed clause as this chart's own reading,
		// which is what the finding must not contradict. Asserted rather than assumed — if the record
		// ever stopped saying it, the assertion below would pass while testing nothing.
		assertTrue(record.contains(DrugReferenceInjector.RECORDED_READING_LEAD),
				"precondition: the record must state the key as recorded, was: " + record);
		assertEquals(1, findings.size(), "one collapsed key is one citable finding, was: " + findings);
		assertFalse(findings.get(0).contains(CLAUSE),
				"and the finding beside it must not deny what that record states, was: " + findings);
	}

	@Test
	public void aCorroboratedRuleOnANEIGHBOURRowDoesNotClearTheClauseThatRowsOwnRecordStates()
			throws IOException {
		// The bound on the MAX above, and the direction it was first got wrong in. This ledger's key is
		// the SUBSTANCE, so it spans every ROW of it — while DrugReferenceInjector renders one record per
		// ENTRY and resolves its own corroboration MAX over that entry's rules alone. Folding the two
		// across rows therefore re-created the contradiction from the other side: a corroborated rule on
		// the gel row cleared the flag while the tablets row's own record went on hedging the tablets
		// rule's clause, in the same injection.
		//
		// So the AND is scoped to the chip's ORIGIN, and a sentence from another entry brings its own
		// answer with it. Mutate RaisedChip's origin comparison to always fold and read the failure.
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(RULE_ROWS_ONE_SUBSTANCE));
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("Ketoconazole", "Levocetirizine"), null),
						"Is it safe to give her levoketoconazole?");
		String tablets = DrugReferenceTestSupport.referenceTextNaming(chart, "Levoketoconazole (tablets)");
		List<String> findings = new ArrayList<String>();
		for (RecordMapping f : DrugReferenceTestSupport.injectedFindings(chart)) {
			findings.add(f.getText());
		}

		// Precondition: the tablets record really does hedge its own clause, which is what the finding
		// must agree with. Without this the assertion below could pass against a record that states it.
		assertTrue(tablets.contains(DrugReferenceInjector.UNCORROBORATED_READING_LEAD),
				"precondition: the tablets record must hedge its own rule, was: " + tablets);
		assertEquals(1, findings.size(), "one substance is one chip and one finding, was: " + findings);
		assertTrue(findings.get(0).contains("documented levo allergy"),
				"precondition: the incumbent tablets sentence must be the one that survived the tie, "
						+ "was: " + findings);
		assertTrue(findings.get(0).contains(CLAUSE),
				"and the finding must say what that row's own record says, was: " + findings);
	}

	@Test
	public void anInteractionFindingIsUntouched() {
		// The other type that reaches renderFinding today. It states the strength its rating licenses
		// and nothing else; no interaction is matched against the chart's allergy list at all, so there
		// is no match for this clause to be about.
		String finding = DrugReferenceTestSupport
				.injectedSafetyFinding("Is it safe to give warfarin?", "Aspirin", "B01AC06").getText();

		assertFalse(finding.contains(CLAUSE),
				"an interaction finding has no chart match to qualify, was: " + finding);
	}
}
