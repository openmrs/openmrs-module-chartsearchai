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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * Issue #309 — a CONDITION rule matched by bare containment, stated as this patient's chart's own
 * reading with nothing corroborating it.
 *
 * <p><b>The defect.</b> {@code DrugSafetyValidator.corroboratedByTheChart} answered {@code true}
 * unconditionally for every rule that is not a self-named ALLERGY rule, so a condition rule reached
 * {@code Recorded for this patient:} on the strength of {@code PatientClinicalContext.hasConditionToken}
 * alone — which is bare containment, deliberately, because a curated condition token is MEANT to match
 * a fragment ({@code peptic ulcer} inside "history of peptic ulcer disease"). That is issue #269's
 * defect one rule type along: for a patient whose recorded condition is {@code Status Post Cesarean
 * Delivery}, an entry ruling on the condition token {@code liver} read
 * <pre>
 * Recorded for this patient: acute hepatitis or liver failure
 * </pre>
 * and said it of a chart that records a caesarean delivery.
 *
 * <p><b>Why the fix is a boundary witness and not the allergy union.</b> The ticket says the
 * corroboration test has no condition analogue, because both of its legs resolve a drug NAME and no arm
 * resolves a recorded condition to a reference substance. That is right about the legs and wrong about
 * the conclusion: what redeems a condition match is not a resolution but a BOUNDARY — whether some
 * matched record carries the token as a whole word rather than inside a longer one.
 * {@code DrugSafetyValidator.aMatchedConditionCarriesTheToken} is that question, and it reuses
 * {@link DrugReference#containsWord}, the PROSE rule, because that is the shape
 * {@code PatientClinicalContext.containsToken}'s own javadoc describes the condition haystack by ("a
 * condition in the clinician's own wording"). No matching behaviour moves: {@code hasConditionToken} is
 * byte-identical, and no chip's wire shape, detail, rank or severity moves — the {@code SafetyWarning}'s
 * own {@code uncorroboratedChartMatch} flag does flip for a condition rule, which is what the injected
 * finding reads.
 *
 * <p><b>What the measurement decided</b> (issue #309, over the OpenMRS 3.7.1 reference-application demo
 * dictionary; the figures and both corpora are recorded on
 * {@link PatientClinicalContext#containsToken}). The hazard is real and its false matches are clinical:
 * {@code liver} matches 30 of that dictionary's 2581 condition candidates and 20 of the 30 carry it only
 * inside a deliver/delivery form. The LOSS is zero over the two CODED corpora it ran on — all four
 * condition tokens the shipped seed publishes match every value they match there as whole words —
 * which is the proxy issue #223 used for the allergy side, run for conditions. That bound is
 * load-bearing: the free-text half is unmeasured and is where the rule DOES cost, on the seed's own
 * tokens ({@link #anInflectionOfAShippedTokenIsHedged}). What is pinned here is the token set the
 * claim is about ({@link #theShippedSeedPublishesExactlyTheFourConditionTokensTheMeasurementWasOver});
 * {@link #aShippedSeedConditionTokenIsStillStatedAsRecorded} is one worked case of it, not a guard
 * over the corpus, which this repo does not carry and which still escapes.
 *
 * <p><b>Prompt-facing only</b>, exactly as issues #269 and #308 scoped themselves: this is
 * {@code corroboratedByTheChart}, which both injected channels ask and which no chip reads. The chip's
 * own demotion ({@code contraindicationRank}, issue #223) is allergy-typed and stays so.
 *
 * <p><b>The residue, deliberately given up.</b> A prefix or suffix compound that is clinically the same
 * finding is hedged: {@code Lymphedema} and {@code Angioedema} for a rule on {@code edema}, pinned by
 * {@link #aClinicallyRightCompoundIsHedgedToo} so the cost is a case rather than a sentence.
 */
public class ConditionRuleBoundaryCorroborationTest {

	private static final String FIXTURE =
			"chartsearchai-test/drug-reference-condition-token-nesting.json";

	private static final String RECORDED = DrugReferenceInjector.RECORDED_READING_LEAD;

	private static final String UNCORROBORATED = DrugReferenceInjector.UNCORROBORATED_READING_LEAD;

	private static final String NOT_RECORDED = DrugReferenceInjector.NOT_RECORDED_READING_LEAD;

	/** The one arrangement every case here drives: the real injector, the real validator, and the
	 *  service named by {@code fixture} — this file's own fixture, or the SHIPPED curated seed for the
	 *  two cases that are about the shipped population. Allergens and conditions are separate slots
	 *  because one case's witness is an allergy arrangement. */
	private static PatientChart chart(DrugReferenceService service, String question,
			Set<String> allergens, Set<String> conditions) {
		return DrugReferenceTestSupport.injectorWithSafety(service)
				.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null, allergens, conditions),
						question);
	}

	private static DrugReferenceService fixtureService() throws IOException {
		return DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.fixtureEntries(FIXTURE));
	}

	/** The injected {@code drug_reference} record for {@code question}, for a patient whose recorded
	 *  conditions are {@code conditions}. */
	private static String record(String question, String... conditions) throws IOException {
		return recordFrom(fixtureService(), question, conditions);
	}

	private static String recordFrom(DrugReferenceService service, String question,
			String... conditions) {
		RecordMapping reference = DrugReferenceTestSupport.injectedReference(
				chart(service, question, null, DrugReferenceTestSupport.set(conditions)));
		return reference == null ? "" : reference.getText();
	}

	/** The injected {@code safety_finding} texts for the same arrangement — the second channel, which
	 *  since issue #308 states the same answer and must not disagree with the record. Through
	 *  {@link DrugReferenceTestSupport#findingTexts}, whose javadoc asks callers to stop writing this
	 *  loop out; it is not written again here. */
	private static List<String> findings(String question, String... conditions) throws IOException {
		return DrugReferenceTestSupport.findingTexts(
				chart(fixtureService(), question, null, DrugReferenceTestSupport.set(conditions)));
	}

	/** Which patient-specific section {@code clause} sits in — ALL THREE leads, not two. An earlier
	 *  version read only {@link #RECORDED} and {@link #UNCORROBORATED}, so a clause in the
	 *  not-recorded section was attributed to whichever of those happened to precede it; measured on
	 *  the shipped seed, where a record carries a recorded section and a not-recorded one at once, it
	 *  named the recorded section for a clause in the not-recorded one. Sound because no lead is a
	 *  substring of another, which
	 *  {@code InjectedContraindicationCorroborationTest.theThreeSectionLeadsAreTheWordsAModelReads}
	 *  asserts. */
	private static String clauseSection(String record, String clause) {
		int at = record.indexOf(clause);
		assertTrue(at >= 0, "the record does not carry the clause at all: " + record);
		String section = "no patient-specific section";
		int nearest = -1;
		for (String lead : new String[] { RECORDED, NOT_RECORDED, UNCORROBORATED }) {
			int start = record.lastIndexOf(lead, at);
			if (start > nearest) {
				nearest = start;
				section = lead;
			}
		}
		return nearest < 0 ? "no patient-specific section" : section;
	}

	@Test
	public void aConditionTokenNestingInsideARecordedConditionIsNotStatedAsTheChartsOwnReading()
			throws IOException {
		// 'liver' inside 'Status Post Cesarean Delivery' — the condition analogue of opium/Tiotropium,
		// and a real concept name from the dictionary the measurement was run over.
		String record = record("Can I give her naltrexone?", "Status Post Cesarean Delivery");
		assertEquals(UNCORROBORATED,
				clauseSection(record, "acute hepatitis or liver failure"),
				"a condition token the recorded condition carries only mid-word must not be stated as "
						+ "this chart's own reading: " + record);
	}

	@Test
	public void aConditionRecordingTheTokenAsAWholeWordIsStillStatedAsRecorded() throws IOException {
		// The other direction, and the one a boundary rule is at risk of breaking: the fragment match
		// the bare containment exists FOR still reaches the recorded section.
		String record = record("Can I give her naltrexone?", "Chronic liver disease");
		assertEquals(RECORDED, clauseSection(record, "acute hepatitis or liver failure"),
				"a recorded condition carrying the token as a whole word still states it: " + record);
	}

	@Test
	public void aShippedSeedConditionTokenIsStillStatedAsRecorded() throws IOException {
		// The LOSS side, over the only real curated condition population that exists. Measured over both
		// corpora: every value the shipped seed's four condition tokens match carries the token as a
		// whole word, so this rule costs that population nothing. 'peptic ulcer' inside 'Peptic Ulcer of
		// Stomach' is one of those five matches, and it is exactly the multi-word fragment case that
		// tightening hasConditionToken itself was declined for.
		// Through curatedService(), the SHIPPED seed — not this file's fixture, whose ibuprofen entry
		// merely happens to spell the token and note the same way. Read from the fixture, a reworded
		// note or a changed rule in the shipped file would redden nothing here.
		String record = recordFrom(DrugReferenceTestSupport.curatedService(),
				"Can I give her ibuprofen?", "Peptic Ulcer of Stomach");
		assertEquals(RECORDED, clauseSection(record, "active peptic ulcer disease"),
				"the shipped seed's own condition token must keep stating its clause: " + record);
	}

	@Test
	public void aTokenNamingADifferentOrganIsNotStatedAsTheChartsOwnReading() throws IOException {
		// 'renal' inside 'adrenal' — a tumour of a different organ, and the second hazard shape the
		// measurement found. Here so the case does not rest on one witness.
		String record = record("Can I give her metformin?", "Malignant tumor of adrenal gland");
		assertEquals(UNCORROBORATED,
				clauseSection(record, "significant renal impairment"),
				"'renal' inside 'adrenal' is not a record of renal impairment: " + record);
	}

	@Test
	public void theFindingChannelStatesTheSameAnswerAsTheRecord() throws IOException {
		// Issue #308: two citable records of one chart must not come apart, and the model was measured
		// answering from the finding when only the record was qualified.
		List<String> findings = findings("Can I give her naltrexone?", "Status Post Cesarean Delivery");
		assertEquals(1, findings.size(), "one fact is one citable record, was: " + findings);
		assertTrue(findings.get(0).contains(DrugReferenceInjector.FINDING_UNCORROBORATED_MATCH),
				"the finding must carry the same provenance clause the record does: " + findings.get(0));
		// The COMPLEMENT, without which this case says only that the two channels agree when both
		// hedge — a regression hedging every condition finding would have left it green.
		List<String> corroborated = findings("Can I give her naltrexone?", "Chronic liver disease");
		assertEquals(1, corroborated.size(), "one fact is one citable record, was: " + corroborated);
		assertFalse(corroborated.get(0).contains(DrugReferenceInjector.FINDING_UNCORROBORATED_MATCH),
				"a corroborated condition rule's finding must go bare: " + corroborated.get(0));
	}

	@Test
	public void theShippedSeedPublishesExactlyTheFourConditionTokensTheMeasurementWasOver() {
		// A DATA guard over the file itself, and the reason it exists is that several documents state a
		// zero-cost claim whose subject is exactly this token set; the assertion message below names
		// them, so the list has one home rather than two that can disagree. The measurement
		// behind it cannot be re-run in this repo (it needs the 3.7.1 dictionary, which the repo does
		// not carry), so the one thing that CAN be pinned is the population it was over. Add a fifth
		// condition rule to the shipped seed and this reddens, which is the prompt to re-measure rather
		// than to edit the claim. It is not a guard over the CORPUS, which still escapes.
		List<String> tokens = new ArrayList<String>();
		for (DrugReference entry : DrugReferenceTestSupport.curatedService().getAll()) {
			if (entry.getContraindications() == null) {
				continue;
			}
			for (DrugReference.Contraindication c : entry.getContraindications()) {
				// Production's own predicate, never a second spelling of it: isConditionRule's javadoc
				// says the readers of a rule's TYPE are enumerated once, and a guard that re-expressed
				// it stayed green when that vocabulary was mutated — measured.
				if (DrugSafetyValidator.isConditionRule(c)) {
					tokens.add(c.getToken());
				}
			}
		}
		// A SET, because the claim is about which tokens exist and not about their order — nothing
		// contracts the order Jackson hands them back in, and asserting it made a pure reordering of the
		// JSON demand a corpus re-measurement it does not need. The size is asserted beside it so a
		// duplicate or a fifth rule still reddens.
		assertEquals(new TreeSet<String>(Arrays.asList(
				"gi bleed", "peptic ulcer", "severe hepatic", "renal impairment")),
				new TreeSet<String>(tokens),
				"the zero-cost claim is ABOUT this token set — it is stated on "
						+ "PatientClinicalContext.containsToken (which carries the token literals and "
						+ "the per-corpus counts), DrugSafetyValidator.aMatchedConditionCarriesTheToken, "
						+ "this class's javadoc, ADR Decision 70 and README; if the set changed, "
						+ "re-measure rather than re-word");
		assertEquals(4, tokens.size(), "a duplicate token would pass the set comparison above");
	}

	@Test
	public void anInflectionOfAShippedTokenIsHedged() throws IOException {
		// THE RESIDUE THAT REACHES THE SHIPPED SEED, and the one the first draft of this change did not
		// disclose. "Zero cost over the shipped seed" is true of the two CODED corpora the measurement
		// ran over — no concept in either is spelled this way — and the free-text half of the condition
		// list is unmeasured, because the demo database's condition_non_coded column holds one
		// placeholder across all 853 rows. A clinician typing the condition themselves is exactly where
		// an inflection arises, and `GI bleeding` for the seed's own `gi bleed` is hedged.
		//
		// Not fixable by choosing a different boundary rule: the tail here is `ing`, three letters, so
		// the order-name rule's two-letter inflection allowance does not reach it either — and choosing
		// an allowance at a call site is what CLAUDE.md forbids (#260). What keeps this conservative is
		// that the section asserts nothing and denies nothing: the contraindication is still listed and
		// the chip still fires, so the safety net is intact and only the attribution weakens.
		String hedged = recordFrom(DrugReferenceTestSupport.curatedService(),
				"Can I give her ibuprofen?", "GI bleeding");
		assertEquals(UNCORROBORATED, clauseSection(hedged, "active gastrointestinal bleeding"),
				"an inflection of the shipped seed's own token is hedged: " + hedged);
		// The control that keeps this from reading as the rule being broadly destructive — the
		// multi-word fragment the bare match exists FOR still states as recorded — is
		// InjectedContraindicationPatientReadingTest
		// .aConditionOnRecordReadsFromTheConditionListAndOnlyItsOwnRule, over the same shipped seed and
		// the same recorded condition, asserting the whole recorded AND not-recorded sections by
		// equality rather than one clause's section. Not copied here; that case is stricter.
	}

	@Test
	public void aPaddedTokenIsPutToTheSameStringItsWitnessFilterTrimmed() throws IOException {
		// The two sides of this leg normalize differently, and until #309's cycle-2 review they did not
		// agree: PatientClinicalContext.recordsMatching filters witnesses on foldedLower(token.trim()),
		// while the boundary question was put to the RAW token — so a curated rule whose token carries
		// stray whitespace found its witness and then failed to match it, and every such rule lost the
		// recorded attribution it had before #309. Nothing in DrugReferenceValidity trims or reports a
		// padded token, so an operator's own file reaches this silently.
		//
		// Acamprosate files Naltrexone's rule with the token padded; the two must answer alike.
		String padded = record("Can I give her acamprosate?", "Chronic liver disease");
		assertEquals(RECORDED, clauseSection(padded, "hepatic impairment"),
				"a padded token must be put to the string its own witness filter trimmed: " + padded);
		// And the padding must not buy a match the trimmed token would not get — the hedge still holds
		// on the nesting case, so this is a normalization fix and not a widening.
		String hedged = record("Can I give her acamprosate?", "Status Post Cesarean Delivery");
		assertEquals(UNCORROBORATED, clauseSection(hedged, "hepatic impairment"),
				"trimming must not widen the match: " + hedged);
	}

	@Test
	public void aClinicallyRightCompoundIsHedgedToo() throws IOException {
		// The residue, kept as a case rather than a sentence: 'Lymphedema' IS oedema, and the boundary
		// rule hedges it. Measured over the dictionary's 2581 condition candidates, 'edema' matches 13
		// values and 7 carry it only mid-word, most of them compounds of exactly this kind. This is the
		// cost a future change would have to weigh, and it is asserted so that change reads it.
		String record = record("Can I give her paracetamol?", "Lymphedema");
		assertEquals(UNCORROBORATED, clauseSection(record, "fluid retention"),
				"the residue this rule gives up, pinned rather than described: " + record);
	}

	/** The {@code allergens} counterpart of {@link #findings}: this case's witness is an ALLERGY
	 *  arrangement, because the guard it re-pins is one the condition leg cannot reach. */
	private static List<String> allergyFindings(String question, String... allergens)
			throws IOException {
		return DrugReferenceTestSupport.findingTexts(
				chart(fixtureService(), question, DrugReferenceTestSupport.set(allergens), null));
	}

	@Test
	public void anUnmatchedRuleStillCannotSeedItsKeyAsRecorded() throws IOException {
		// NOT a case about conditions, and it is here because of what #309's change COSTS rather than
		// what it adds. DrugSafetyValidator.addContraindications opens its pre-pass with
		// `if (recordedContraindicationKind(c, context) == null) continue;`, and that guard is what stops
		// an UNMATCHED rule seeding its key corroborated — which would clear a hedge on a clause a
		// sibling key renders identically. Its only witness was
		// UncorroboratedFindingProvenanceTest.aRuleTheChartDoesNotRecordCannotStateItsClauseAsRecorded,
		// whose unmatched rule is a CONDITION rule; before #309 that rule corroborated TRUE
		// unconditionally, and after #309 it corroborates FALSE, so deleting the guard stopped reddening
		// anything. Measured: with the guard deleted the whole api suite was green on the post-#309 code
		// and that case FAILED on the pre-#309 code.
		//
		// This restores a witness the condition leg cannot silence, because its unmatched rule is an
		// allergy rule that is not self-named ('nsaid' is no name of Nefopam), which corroborates TRUE
		// unconditionally exactly as before. Delete the `continue` and read this failure.
		List<String> findings = allergyFindings("Is it safe to give her nefopam?", "Nefotenone");
		assertEquals(1, findings.size(), "one fact is one citable record, was: " + findings);
		assertTrue(findings.get(0).contains(DrugReferenceInjector.FINDING_UNCORROBORATED_MATCH),
				"a rule the chart does not record must not clear the hedge on a clause its own key "
						+ "renders identically: " + findings.get(0));
	}

	@Test
	public void anUnmatchedConditionRuleIsUnaffected() throws IOException {
		// The boundary question is asked only of a rule that has already matched, which both call sites
		// gate on. A rule the chart does not record at all keeps stating its own section, so this change
		// cannot have moved a clause into the reading that was never in it.
		String record = record("Can I give her naltrexone?", "Malaria");
		assertFalse(record.contains(UNCORROBORATED + "acute hepatitis or liver failure"),
				"a rule the chart never matched must not be hedged as a matched one: " + record);
		// And not in the RECORDED section either — the assertion above alone is satisfied by the rule
		// simply not matching, so on its own it discriminates nothing. Both together say the clause is
		// in NO patient-specific section.
		assertFalse(record.contains(RECORDED + "acute hepatitis or liver failure"),
				"a rule the chart never matched must not be stated as recorded either: " + record);
		// The POSITIVE CONTROL, without which the two assertions above could both hold because the
		// record names no clause at all: the SAME entry and question, with a recorded condition that
		// does match, puts that very string in a patient-specific section.
		assertTrue(record("Can I give her naltrexone?", "Chronic liver disease")
				.contains(RECORDED + "acute hepatitis or liver failure"),
				"the control must show this arrangement CAN state the clause");
	}
}
