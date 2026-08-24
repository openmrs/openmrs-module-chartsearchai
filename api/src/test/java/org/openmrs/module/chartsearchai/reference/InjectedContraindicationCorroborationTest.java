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
 * ({@link DrugSafetyValidator#allergicSubstances}, that arm's own identity question asked over the
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
 * <p>Every case drives the real {@code injectRecords} wired to the real {@code DrugSafetyValidator} over
 * a dataset parsed by the real production parser, and reads the record a model would read.
 */
public class InjectedContraindicationCorroborationTest {

	/** Issue #223's own fixture, unedited — it already carries every shape the mid-word match needs. */
	private static final String MID_WORD_TOKEN =
			"chartsearchai-test/drug-reference-mid-word-allergy-token.json";

	/** Issue #269's own: an entry CALLED {@code Ketoconazole} beside one that merely aliases it. */
	private static final String BORROWED_ALIAS =
			"chartsearchai-test/drug-reference-borrowed-alias-corroboration.json";

	private static final String RECORDED_LEAD = " Recorded for this patient: ";

	private static final String NOT_RECORDED_LEAD = " Not recorded for this patient: ";

	/** Read off production rather than restated, so a reword cannot leave these cases passing against a
	 *  lead no record carries. */
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

	/** The rendered {@code drug_reference} record for the entry named {@code drug}, through the real
	 *  injector and the real validator over {@code fixture}. */
	private static String record(String fixture, String drug, String question,
			PatientClinicalContext context) throws IOException {
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(fixture));
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
	public void aRuleOnlyABareContainmentMatchSupportsIsNotStatedAsTheChartsOwnReading()
			throws IOException {
		// THE case, and the ticket's own concrete shape: one recorded allergy, `Tiotropium`, which
		// contains the rule's token `opium` and which neither corroborating question can reach —
		// allergensMatching("opium") yields [tiotropium], which does not NAME Opium, and
		// findImpliedSubstances("Tiotropium") is [Tiotropium], which is not Opium's substance.
		String record = record(MID_WORD_TOKEN, "Opium", "Is it safe to give her opium?",
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
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(MID_WORD_TOKEN));
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
		String record = record(MID_WORD_TOKEN, "Opium", "Is it safe to give her opium?",
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
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(MID_WORD_TOKEN));
		assertEquals("[Opium]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances("Papaveretum")).toString(),
				"precondition: the allergen arm must genuinely resolve that record to Opium");

		String record = record(MID_WORD_TOKEN, "Opium", "Is it safe to give her opium?",
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
		String record = record(MID_WORD_TOKEN, "Levothyroxine", "Is it safe to give her levothyroxine?",
				DrugReferenceTestSupport.ctx(60, null, null, null,
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
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(BORROWED_ALIAS));
		assertEquals("[Ketoconazole]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances("Ketoconazole")).toString(),
				"precondition: the allergen arm must NOT reach the aliasing entry, or there is nothing "
						+ "for the naming question to add");

		String record = record(BORROWED_ALIAS, "Levoketoconazole",
				"Is it safe to give her levoketoconazole?", DrugReferenceTestSupport.ctx(60, null, null,
						null, DrugReferenceTestSupport.set("Ketoconazole"), null));

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
		DrugReferenceService service = DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.fixtureEntries(BORROWED_ALIAS));
		assertTrue(service.findImpliedSubstances("Levocetirizine").isEmpty(),
				"precondition: the uncorroborated witness must reach no substance of this fixture");

		String record = record(BORROWED_ALIAS, "Levoketoconazole",
				"Is it safe to give her levoketoconazole?", DrugReferenceTestSupport.ctx(60, null, null,
						null, DrugReferenceTestSupport.set("Ketoconazole", "Levocetirizine"), null));

		assertEquals("documented ketoconazole allergy — documented levo allergy",
				sectionAfter(record, RECORDED_LEAD),
				"one clause, and a corroborated rule of the key carries it, was: " + record);
		assertNull(sectionAfter(record, UNCORROBORATED_LEAD),
				"so the key is not hedged by its other rule, was: " + record);
	}

	@Test
	public void aClassTokenRuleIsUntouchedThoughTheAllergenArmResolvesNothing() throws IOException {
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

		PatientChart chart = DrugReferenceTestSupport.injectorWithSafety(service).injectRecords(
				DrugReferenceTestSupport.oneRecordChart(),
				DrugReferenceTestSupport.ctx(60, null, null, null,
						DrugReferenceTestSupport.set("NSAIDs"), null),
				"Is ibuprofen safe for her?");
		String record = DrugReferenceTestSupport.referenceTextNaming(chart, "Ibuprofen");
		assertNotNull(record, "no ibuprofen reference record was injected: "
				+ DrugReferenceTestSupport.referenceTexts(chart));

		assertEquals("NSAID hypersensitivity", sectionAfter(record, RECORDED_LEAD),
				"a class-token rule is stated as the chart's own reading, unchanged, was: " + record);
		assertNull(sectionAfter(record, UNCORROBORATED_LEAD),
				"and nothing is hedged, was: " + record);
	}
}
