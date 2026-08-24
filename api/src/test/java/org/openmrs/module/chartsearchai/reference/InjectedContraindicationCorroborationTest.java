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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #269 — WHAT CORROBORATES a self-named allergy rule's match, on the injected record.
 *
 * <p><b>The defect.</b> Issue #267 stopped a bare-containment match from outranking the identity chip:
 * a self-named allergy rule no matched record names now chips at
 * {@code ContraindicationChips.SELF_NAMED_RULE_MATCHED_BY_CONTAINMENT_ALONE}, below {@code IDENTITY}.
 * The injected {@code drug_reference} record got no such treatment — it decided which half of the
 * patient-specific reading a clause went in from {@link DrugSafetyValidator#recordedContraindicationKind}
 * alone, which is the bare-containment MATCH — so for a patient whose only recorded allergy is
 * {@code Tiotropium} the record read
 * <pre>
 * Recorded for this patient: documented opium allergy
 * </pre>
 * and said it of a chart that records a tiotropium allergy. That reading is citable evidence (issue
 * #110), so the answer is <em>invited</em> to assert it, which is why the record is the sharper half of
 * the two: a demoted chip no longer displaces a true sentence, while a false clause in a record has
 * nothing displacing it at all. {@code SelfNamedAllergyRuleRankTest} recorded this as the deferral it
 * was, and {@code docs/adr.md} Decision 30's tail says the same.
 *
 * <p><b>The fix is a UNION of two corroborating questions, and neither half will do.</b> A clause stays
 * in the recorded half when EITHER an allergy record the rule matched NAMES the entry
 * ({@link DrugSafetyValidator#aMatchedRecordNamesTheEntry}, the chip rank's own predicate) OR the
 * allergen arm reads some recorded allergy as an allergy to the entry's substance
 * ({@link DrugSafetyValidator#allergicSubstanceKeys}, that arm's own identity question asked over the
 * whole allergy list). Each half alone is wrong, in opposite directions, and both are exercised below:
 * <ul>
 * <li>the rank's predicate ALONE understates — {@code allergensMatching("opium")} is
 * {@code [tiotropium]} even when {@code Papaveretum} is also on record, because
 * {@code papaveretum} does not contain {@code opium}, so a genuine opium allergy the allergen arm
 * chips would be hedged ({@link #aRecordedAllergyToTheSubstanceCorroboratesARuleItsOwnWitnessCannot});
 * <li>the allergen arm's set ALONE overstates — {@link DrugReferenceService#findImpliedSubstances}
 * admits equal claimants only at the strongest claimant's rank, so an allergy recorded as
 * {@code Ketoconazole} reaches the entry CALLED that and not one merely aliasing it, and a
 * self-named rule on the aliasing entry would be hedged while its chip stands at the full
 * {@code SELF_NAMED_RULE} rank ({@link #aRecordNamingTheEntryCorroboratesARuleTheAllergenArmCannot}).
 * </ul>
 * The union is monotone, which is the whole reason for it: it can hedge nothing either half admits,
 * so it can neither understate a recorded allergy nor disagree with a chip at full rank.
 *
 * <p><b>Marked, not denied and not dropped.</b> The clause goes to a third named section rather than to
 * the negative half, because the same injection still carries the demoted chip as a
 * {@code safety_finding} asserting the contraindication with {@code STRENGTH_WITHHOLD} — deliberately,
 * per {@code SELF_NAMED_RULE_MATCHED_BY_CONTAINMENT_ALONE}'s own javadoc — and a flat "Not recorded for
 * this patient" beside it would be two citable records of one chart contradicting each other. Nor is it
 * dropped: {@code DrugReferenceInjector.render} records two live-measured failures of naming some
 * clauses and leaving the rest to inference.
 *
 * <p>Every case that reads a RECORD drives the real {@code injectRecords} wired to the real
 * {@code DrugSafetyValidator} over a dataset parsed by the real production parser, and reads the record
 * a model would read. {@link #theThreeSectionLeadsAreTheWordsAModelReads} reads no record — it is the
 * one case here that pins the READING's three section leads, whose words every other assertion takes
 * from production and therefore cannot see. {@code RULE_LIST_LEAD} is deliberately NOT among them: it
 * is a local literal, and rewording it in production reddens every case below that reads a record,
 * through {@link #record}'s own precondition — mutate it and read the failures rather than trusting a
 * tally, which the case added for the imply-vs-name leg had already made stale once.
 */
public class InjectedContraindicationCorroborationTest {

	/** Issue #223's own fixture, unedited — it already carries every shape the mid-word match needs. */
	private static final String MID_WORD_TOKEN =
			"chartsearchai-test/drug-reference-mid-word-allergy-token.json";

	/** Issue #269's own: an entry CALLED {@code Ketoconazole} beside one that merely aliases it. */
	private static final String BORROWED_ALIAS =
			"chartsearchai-test/drug-reference-borrowed-alias-corroboration.json";

	/** All three read off production, so no case here can pass against a lead no record carries — which
	 *  matters most for the {@code assertNull} guards below, since a stale literal makes those vacuous
	 *  rather than red. What pins the WORDS is
	 *  {@link #theThreeSectionLeadsAreTheWordsAModelReads}, and only that: every other assertion in this
	 *  file compares a constant to itself. */
	private static final String RECORDED_LEAD = DrugReferenceInjector.RECORDED_READING_LEAD;

	private static final String NOT_RECORDED_LEAD = DrugReferenceInjector.NOT_RECORDED_READING_LEAD;

	private static final String UNCORROBORATED_LEAD = DrugReferenceInjector.UNCORROBORATED_READING_LEAD;

	private static final String RULE_LIST_LEAD = " Contraindicated with: ";

	/** The text between {@code lead} and that sentence's own full stop, or null when the record carries
	 *  no such section — what a model reads, read where a model reads it. */
	private static String sectionAfter(String record, String lead) {
		int start = record.indexOf(lead);
		if (start < 0) {
			return null;
		}
		int end = record.indexOf(".", start + lead.length());
		assertTrue(end > start, "an unterminated sentence, was: " + record);
		return record.substring(start + lead.length(), end);
	}

	/** A service over {@code fixture}, parsed by the real production parser — and with cross-reactivity
	 *  groups pinned EMPTY, which is {@code serviceWith}'s own seam rather than a choice made here.
	 *  Said because the DDInter counterpart in {@code DrugReferenceTestSupport} attaches the bundled
	 *  groups instead, and a case here that needed one would otherwise find it silently absent. Every
	 *  case below is about the two allergy arms, which read no group. */
	private static DrugReferenceService fixtureService(String fixture) throws IOException {
		return DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(fixture));
	}

	/** The rendered {@code drug_reference} record for the entry named {@code drug}, through the real
	 *  injector and the real validator over {@code service}.
	 *
	 *  <p>The service is the CALLER's, not one built here: a case that asserts a precondition through
	 *  {@code findImpliedSubstances} must assert it of the very instance the record is rendered from,
	 *  or any instance-scoped behaviour — a memo, a lazily loaded group file, an entry-count guard —
	 *  lets the precondition pass while describing another object. That is how
	 *  {@code SelfNamedAllergyRuleRankTest} does it, and it is also what lets the shipped curated seed
	 *  reach this helper instead of re-inlining it. */
	private static String record(DrugReferenceService service, String drug, String question,
			PatientClinicalContext context) {
		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(), context, question);
		String record = DrugReferenceTestSupport.referenceTextNaming(chart, drug);
		assertNotNull(record, "no " + drug + " reference record was injected: "
				+ DrugReferenceTestSupport.referenceTexts(chart));
		// Every case below turns on which SECTION a clause is in, so a record that lists no clause at
		// all would make each of them pass vacuously.
		assertNotNull(sectionAfter(record, RULE_LIST_LEAD),
				"precondition: the record must list the drug's own rules, was: " + record);
		return record;
	}

	@Test
	public void theThreeSectionLeadsAreTheWordsAModelReads() {
		// The one case in this file that asserts the LITERALS, and the only thing here a reword can
		// redden: every other assertion reads the constant and so compares it to itself. Measured — with
		// the third lead read from production everywhere and pinned nowhere, rewording it left the whole
		// api suite green. Same arrangement, and the same reason, as
		// ChartSearchAiAuditSearchModeTest's four search-mode spellings.
		//
		// The third lead states what the MODULE established, deliberately not a categorical about the
		// chart: both of corroborated()'s legs can miss a recorded allergy that really does name the drug
		// (its first sees only this rule's own witnesses, its second is narrowed by
		// findImpliedSubstances), so a lead reading "not by a recorded allergy to this drug" is one the
		// chart can contradict. Reword it only with that in mind.
		assertEquals(" Recorded for this patient: ", RECORDED_LEAD);
		assertEquals(" Not recorded for this patient: ", NOT_RECORDED_LEAD);
		assertEquals(" Matched in this patient's chart but not corroborated as a record of this drug: ",
				UNCORROBORATED_LEAD);

		// And no lead may be a substring of another, because sectionAfter finds a section by indexOf: the
		// pairs to keep apart went from one to three with this change, and the pre-existing pair is one
		// capital letter from colliding.
		String[] leads = { RECORDED_LEAD, NOT_RECORDED_LEAD, UNCORROBORATED_LEAD };
		for (int i = 0; i < leads.length; i++) {
			for (int j = 0; j < leads.length; j++) {
				assertTrue(i == j || !leads[i].contains(leads[j]),
						"lead " + j + " must not sit inside lead " + i + ", or sectionAfter reads the "
								+ "wrong section: " + leads[i] + " / " + leads[j]);
			}
		}
	}

	@Test
	public void aRuleOnlyABareContainmentMatchSupportsIsNotStatedAsTheChartsOwnReading()
			throws IOException {
		// THE case, and the ticket's own concrete shape: one recorded allergy, `Tiotropium`, which
		// contains the rule's token `opium` and which neither corroborating question can reach —
		// allergensMatching("opium") yields [tiotropium], which does not NAME Opium, and
		// findImpliedSubstances("Tiotropium") is [Tiotropium], which is not Opium's substance.
		String record = record(fixtureService(MID_WORD_TOKEN), "Opium", "Is it safe to give her opium?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Tiotropium"), null));

		assertEquals("documented opium allergy", sectionAfter(record, UNCORROBORATED_LEAD),
				"the clause must be named on the side it is actually on, was: " + record);
		assertNull(sectionAfter(record, RECORDED_LEAD),
				"and must NOT be stated as something this patient's chart records, was: " + record);
		assertNull(sectionAfter(record, NOT_RECORDED_LEAD),
				"nor denied, which the safety_finding beside it contradicts, was: " + record);
		assertEquals("documented opium allergy", sectionAfter(record, RULE_LIST_LEAD),
				"and the drug's own list is untouched — a drug's contraindications are the drug's, "
						+ "was: " + record);
		assertTrue(record.indexOf(UNCORROBORATED_LEAD) < record.indexOf(RULE_LIST_LEAD),
				"stated before the list it qualifies, was: " + record);
	}

	@Test
	public void theSafetyFindingBesideItStillCarriesTheRulesOwnSentence() throws IOException {
		// The bound, and why the section above is a qualification rather than a denial. The demoted chip
		// is still RAISED — the arms fire on different evidence and this one was never gated on the other
		// (SELF_NAMED_RULE_MATCHED_BY_CONTAINMENT_ALONE's javadoc) — so the same injection carries it as a
		// citable finding. This change is the record's and only the record's; the finding channel is a
		// separate decision on separate evidence.
		DrugReferenceService service = fixtureService(MID_WORD_TOKEN);
		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(
				DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
						DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("Tiotropium"), null),
						"Is it safe to give her opium?"));

		assertEquals(1, findings.size(), "one fact is one citable record, was: " + findings);
		assertEquals(DrugReferenceInjector.FINDING_PREFIX
				+ "Opium: Opium is contraindicated by an active allergy: documented opium allergy."
				+ DrugReferenceInjector.STRENGTH_WITHHOLD, findings.get(0).getText(),
				"unchanged by this fix, was: " + findings);
	}

	@Test
	public void aRuleTheAllergenRecordNamesIsStillTheChartsOwnReading() throws IOException {
		// The control that separates this from deleting the reading: the same entry, the same rule, and
		// an allergy recorded under the very name the token is.
		String record = record(fixtureService(MID_WORD_TOKEN), "Opium", "Is it safe to give her opium?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Opium"), null));

		assertEquals("documented opium allergy", sectionAfter(record, RECORDED_LEAD),
				"a corroborated rule is unchanged, was: " + record);
		assertNull(sectionAfter(record, UNCORROBORATED_LEAD),
				"and nothing is hedged, was: " + record);
	}

	@Test
	public void aRecordedAllergyToTheSubstanceCorroboratesARuleItsOwnWitnessCannot()
			throws IOException {
		// The discriminator against the chip RANK's predicate used alone. `Papaveretum` is one of Opium's
		// own names, so the allergen arm resolves it to Opium and chips the identity sentence — but it
		// does not CONTAIN the token `opium`, so it is not among the rule's witnesses at all and the
		// rank's per-witness question answers no. Hedging here would understate a real allergy in citable
		// evidence, which is the opposite of the defect this class exists for.
		DrugReferenceService service = fixtureService(MID_WORD_TOKEN);
		assertEquals("[Opium]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances("Papaveretum")).toString(),
				"precondition: the allergen arm must genuinely resolve that record to Opium");

		String record = record(service, "Opium", "Is it safe to give her opium?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Papaveretum", "Tiotropium"), null));

		assertEquals("documented opium allergy", sectionAfter(record, RECORDED_LEAD),
				"the chart does record an opium allergy, so the record must say so, was: " + record);
		assertNull(sectionAfter(record, UNCORROBORATED_LEAD),
				"and must not hedge it, was: " + record);
	}

	@Test
	public void aRuleWhoseEntryTheChartNamedIsStillTheChartsOwnReading() throws IOException {
		// The other side of the boundary, and why the rank's question is asked of the ENTRY rather than of
		// the token: Levothyroxine publishes `thyroxine` among its own names and rules on THAT name, so an
		// allergy recorded as `Levothyroxine` reaches the token only mid-word while the ENTRY is exactly
		// what the chart named.
		String record = record(fixtureService(MID_WORD_TOKEN), "Levothyroxine",
				"Is it safe to give her levothyroxine?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Levothyroxine"), null));

		assertEquals("documented thyroxine allergy — anaphylaxis", sectionAfter(record, RECORDED_LEAD),
				"the entry the chart named keeps its reading, was: " + record);
		assertNull(sectionAfter(record, UNCORROBORATED_LEAD), "nothing hedged, was: " + record);
	}

	@Test
	public void aRecordNamingTheEntryCorroboratesARuleTheAllergenArmCannot() throws IOException {
		// The discriminator against the allergen arm's SET used alone. `Ketoconazole` is one entry's own
		// display name, so findImpliedSubstances stops at that entry and the equal-claimant leg admits
		// only entries claiming the whole recorded name as strongly — which Levoketoconazole, merely
		// aliasing `ketoconazole`, does not. So the arm's set does not hold its substance, while the
		// recorded name IS one of its names: the chip rank keeps the full SELF_NAMED_RULE here, and a
		// set-only reading would hedge the record beside an undemoted chip.
		DrugReferenceService service = fixtureService(BORROWED_ALIAS);
		assertEquals("[Ketoconazole]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances("Ketoconazole")).toString(),
				"precondition: the allergen arm must NOT reach the aliasing entry, or there is nothing "
						+ "for the naming question to add");

		String record = record(service, "Levoketoconazole", "Is it safe to give her levoketoconazole?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Ketoconazole"), null));

		assertEquals("documented ketoconazole allergy — documented levo allergy",
				sectionAfter(record, RECORDED_LEAD),
				"a record NAMING the entry corroborates the rule, was: " + record);
		assertNull(sectionAfter(record, UNCORROBORATED_LEAD), "nothing hedged, was: " + record);
	}

	@Test
	public void oneCollapsedKeyWithOneCorroboratedRuleIsTheChartsOwnReading() throws IOException {
		// The max per collapsed key, and it is not vacuous: two self-named allergy rules of ONE entry
		// share the key contraindicationFinding gives them (the substance, issue #146) and render as ONE
		// clause, while the NAMING question reads each rule's own token — so they can disagree.
		// `Levocetirizine` reaches the token `levo` only mid-word, with a six-letter tail no inflection
		// rule allows, and resolves to no entry of this fixture at all; `Ketoconazole` names the entry.
		// One clause, and the corroborated rule decides it.
		DrugReferenceService service = fixtureService(BORROWED_ALIAS);
		assertTrue(service.findImpliedSubstances("Levocetirizine").isEmpty(),
				"precondition: the uncorroborated witness must reach no substance of this fixture");

		String record = record(service, "Levoketoconazole", "Is it safe to give her levoketoconazole?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Ketoconazole", "Levocetirizine"), null));

		assertEquals("documented ketoconazole allergy — documented levo allergy",
				sectionAfter(record, RECORDED_LEAD),
				"one clause, and a corroborated rule of the key carries it, was: " + record);
		assertNull(sectionAfter(record, UNCORROBORATED_LEAD),
				"so the key is not hedged by its other rule, was: " + record);
	}

	@Test
	public void anAllergenThatImpliesTheSubstanceWithoutNamingItStillCorroborates() throws IOException {
		// The IMPLIED reading, and the only arrangement that separates it from the NAMED one at this gate.
		// Both Amphotericin B rows alias `amphotericin b` and neither is CALLED it, so an allergy recorded
		// under that bare name ties them: findImpliedSubstances reaches both, findNamedSubstances admits
		// neither. The deoxycholate row rules on another of its own names, `deoxy`, which that allergen
		// does not contain — so the naming record is not one of the rule's witnesses and leg 1 cannot see
		// it, while `Deoxyribose` is a witness that does not name the entry.
		//
		// The chip beside it is the allergen arm's IDENTITY chip, raised off the same implied list, and
		// that is what this case is really about: the record must not hedge a clause whose chip stands at
		// full rank, which is the union's monotonicity. Narrow the gate to findNamedSubstances and this
		// reddens — nothing else in the suite does.
		DrugReferenceService service = fixtureService(BORROWED_ALIAS);
		assertEquals("[Amphotericin B (liposomal), Amphotericin B deoxycholate]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances("amphotericin b")).toString(),
				"precondition: the recorded name must imply BOTH rows, by a tie");
		assertEquals("[]", DrugReferenceTestSupport.names(service.findNamedSubstances("amphotericin b",
				service.findImpliedSubstances("amphotericin b"))).toString(),
				"precondition: and must NAME neither, or the two readings do not differ here");

		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null, null, null,
				DrugReferenceTestSupport.set("amphotericin b", "Deoxyribose"), null);
		// The allergen arm raises its IDENTITY chip for this substance — in issue #268's SECOND sentence
		// form, because the recorded name implies the row without naming it, which is the same fact that
		// makes findNamedSubstances empty above. So the two questions compose rather than compete: that
		// one narrows what a sentence may SAY, and this gate has to take the wider implied set to agree
		// with the chip being raised at all.
		assertTrue(DrugReferenceTestSupport
				.contraindicationDetails(DrugReferenceTestSupport.validator(service)
						.validate("", "Is it safe to give her amphotericin B deoxycholate?", context))
				.contains("Amphotericin B deoxycholate is contraindicated by a recorded allergy to "
						+ "\"amphotericin b\"."),
				"precondition: the allergen arm must raise its identity-rank chip for this very substance, "
						+ "or there is no chip for the record to agree with");

		String record = record(service, "Amphotericin B deoxycholate",
				"Is it safe to give her amphotericin B deoxycholate?", context);

		assertEquals("documented amphotericin reaction", sectionAfter(record, RECORDED_LEAD),
				"an allergen that IMPLIES the substance corroborates the rule, was: " + record);
		assertNull(sectionAfter(record, UNCORROBORATED_LEAD),
				"so nothing is hedged beside a chip at full rank, was: " + record);
	}

	@Test
	public void aClauseTheDenialHalfWouldAlsoClaimIsLeftToTheUncorroboratedSection()
			throws IOException {
		// The cross-key precedence. Codeine files an allergy rule and a CONDITION rule under the same
		// note, which is a natural way to author "recorded either way" — two collapsed keys, one clause
		// STRING. The allergy rule matches `Dihydrocodeine` by containment and nothing corroborates it;
		// the condition rule matches nothing at all. So one key wants the uncorroborated section and the
		// other the denial, and printing both would have the record say the module both could and could
		// not answer for those words. The denial yields, because of the two it is the only one that can
		// be false of the string.
		//
		// The LIST that follows reads "opioid reaction; opioid reaction" — two rules of two keys carrying
		// one note, which byRule renders twice: its keys are per rule and nothing de-dups across them
		// (the em-dash join and its contains() check are both WITHIN a key). Pre-existing — issue #190
		// item 1 collapses per rule and these are two rules, and the same fixture renders the same
		// doubling on origin/main — and this fixture is simply the first thing to author the shape; the
		// sections are what this case is about.
		String record = record(fixtureService(BORROWED_ALIAS), "Codeine",
				"Is it safe to give her codeine?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Dihydrocodeine"), null));

		assertEquals("opioid reaction", sectionAfter(record, UNCORROBORATED_LEAD),
				"the section that claims nothing keeps the string, was: " + record);
		assertNull(sectionAfter(record, NOT_RECORDED_LEAD),
				"and the denial of the same words is withheld, was: " + record);
		assertNull(sectionAfter(record, RECORDED_LEAD),
				"nothing is asserted either, was: " + record);
	}

	@Test
	public void aClauseAnotherRuleOfTheSameEntryDoesRecordIsStatedAsRecorded() throws IOException {
		// The other leg of the cross-key precedence, over the same two Codeine rules. Here the CONDITION
		// is on record, so its key is recorded while the allergy rule's key is uncorroborated — one clause
		// string wanted by both. The recorded section keeps it: it is the one that can be true of the
		// words, which is the rule this file's third section was slotted into rather than a new one.
		String record = record(fixtureService(BORROWED_ALIAS), "Codeine",
				"Is it safe to give her codeine?", DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("Dihydrocodeine"),
						DrugReferenceTestSupport.set("Respiratory depression")));

		assertEquals("opioid reaction", sectionAfter(record, RECORDED_LEAD),
				"the section that can be true of the words keeps them, was: " + record);
		assertNull(sectionAfter(record, UNCORROBORATED_LEAD),
				"and the weaker one yields, was: " + record);
	}

	@Test
	public void aClassTokenRuleIsUntouchedThoughTheAllergenArmResolvesNothing() {
		// THE SCOPE GUARD, over the SHIPPED curated seed. `nsaid` is not one of Ibuprofen's own names, so
		// the rule is not selfNamedAllergyRule and neither corroborating question is asked of it — which
		// it must not be: the token names a CLASS, the bare containment match is what it is for, and
		// tightening that match was measured and declined (5 real allergen names lost, every one on
		// `penicillin`; see PatientClinicalContext.hasAllergyToken). The allergen arm resolves NOTHING
		// from `NSAIDs`, so an unscoped reading would hedge a correct clause.
		DrugReferenceService service = DrugReferenceTestSupport.curatedService();
		assertTrue(service.findImpliedSubstances("NSAIDs").isEmpty(),
				"precondition: the allergen arm must resolve no substance from a class name, or this "
						+ "case cannot separate the scope from the corroboration");

		String record = record(service, "Ibuprofen", "Is ibuprofen safe for her?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("NSAIDs"), null));

		assertEquals("NSAID hypersensitivity", sectionAfter(record, RECORDED_LEAD),
				"a class-token rule is stated as the chart's own reading, unchanged, was: " + record);
		assertNull(sectionAfter(record, UNCORROBORATED_LEAD),
				"and nothing is hedged, was: " + record);
	}
}
