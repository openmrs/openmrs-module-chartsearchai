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

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * WHICH NAME the identity chip may call a recorded allergy (issue #268).
 *
 * <p><b>The defect.</b> The chip quotes a RECORD — "The patient has a recorded allergy to X." — and
 * used to put in X whichever row of the subject's substance the recorded name resolved to. But
 * {@link DrugReferenceService#findImpliedSubstances} deliberately returns every substance a recorded
 * name IMPLIES (issues #193/#195), and its equal-claimant leg admits a row on a rank TIE, which is
 * satisfied both by a combination the KB spells without a separator — that leg's reason for existing
 * — and by two substances sharing one name that is neither's display name. In the second case the
 * chip asserted an allergy the chart does not record.
 *
 * <p>Both fixtures here are VERBATIM shipped-KB slices read by the real {@code DdiDrugReferenceSource},
 * so the shape is the shipped data's rather than a fixture's invention, and no curated rule exists to
 * fold the identity chip away ({@code DdiDrugReferenceSource} emits no contraindications at all —
 * {@link SelfNamedAllergyRuleRankTest#aDdinterLoadCannotReachThisRankAtAll}).
 *
 * <p><b>The rule.</b> A row may be named only where the recorded name NAMES it: it is the unique
 * strongest claimant of the whole recorded name, or a name the printed label is built from occurs in
 * the recorded string, or the recorded name's combination constituent / parent moiety names it.
 * Otherwise the chip states the allergy in the chart's own name.
 */
public class RecordedAllergenChipNameTest {

	/** The three trastuzumab rows share one CIEL list although they are three DrugBank substances with
	 *  three ATC codes — so `ado-trastuzumab emtansine` is one of all three's own names. */
	private static final String SHARED_CIEL_LIST = "chartsearchai-test/ddi-alias-names-another-substance.json";

	/** Two families of shipped rows, each sharing one rxnorm_name that is no row's display name — so a
	 *  recorded allergy spelled that way is claimed EQUALLY by several substances and nothing but
	 *  dataset order separates them. They differ in whether {@link DrugReference#displayLabel()} appends
	 *  that shared name as a synonym, which is what decides whether the chip may print the label. */
	private static final String TIED_ON_ONE_NAME = "chartsearchai-test/ddi-tied-alias-allergen.json";

	private static final String KADCYLA = "ado-trastuzumab emtansine";

	@Test
	public void aRowTheRecordedNameDoesNotNameIsNotAnnouncedAsTheRecordedAllergy() throws IOException {
		// THE case, and the one the fixture's own metadata records as having produced a false LIVE label.
		// The patient is allergic to Kadcyla; the question is about Enhertu, a different drug. Both rows
		// publish the other's name, so the allergen arm reaches both substances — which is right, because
		// the class comparisons must see both — but only ONE of them is something the chart says.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(SHARED_CIEL_LIST);
		assertEquals("[Trastuzumab, Trastuzumab deruxtecan, Trastuzumab emtansine]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances(KADCYLA)).toString(),
				"precondition: the shared CIEL list must make one recorded name imply three substances, "
						+ "or there is no wrong row to name");

		List<String> details = DrugReferenceTestSupport.contraindicationDetails(
				DrugReferenceTestSupport.validator(service).validate("",
						"Is it safe to give her trastuzumab deruxtecan?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set(KADCYLA), null)));

		// TWO chips, because the question's own prose names trastuzumab as well. The first is the
		// case-internal control: `Trastuzumab` occurs in the recorded name, so the chart does say it and
		// that chip is untouched. The second is the defect — `Trastuzumab deruxtecan` occurs nowhere in
		// `ado-trastuzumab emtansine`, so the chip may not announce it as what the chart records.
		assertEquals("[The patient has a recorded allergy to Trastuzumab., "
				+ "The patient has a recorded allergy to ado-trastuzumab emtansine.]", details.toString(),
				"the row the recorded name does not name must be quoted in the chart's own words, was: "
						+ details);
	}

	@Test
	public void aRowTheRecordedNameDoesNameKeepsIt() throws IOException {
		// The control that stops the fix being a rename of the arm: the same recorded allergy, asked
		// about trastuzumab EMTANSINE, whose display name the recorded string carries under the drug-NAME
		// boundary rule (`ado-` is not a letter run on its left). Kadcyla IS trastuzumab emtansine, so the
		// chip must go on saying so.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(SHARED_CIEL_LIST);

		List<String> details = DrugReferenceTestSupport.contraindicationDetails(
				DrugReferenceTestSupport.validator(service).validate("",
						"Is it safe to give her trastuzumab emtansine?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set(KADCYLA), null)));

		assertEquals("[The patient has a recorded allergy to Trastuzumab., "
				+ "The patient has a recorded allergy to Trastuzumab emtansine.]", details.toString(),
				"a row the recorded name carries keeps its own name, was: " + details);
	}

	@Test
	public void noRowIsPrivilegedByDatasetOrderWhenTheClaimsTIE() throws IOException {
		// The case that decides this is a rule about the RECORD and not an exemption for whichever row
		// resolution answered first. All three rows carry the rxnorm_name `gallium`, so all three claim a
		// `gallium` allergy equally and DrugReferenceService.lookupByToken breaks the tie by earliest
		// dataset entry — which carries no clinical meaning. Exempting that first row would have fixed one
		// arm of one tie and not the other two, inside a single payload: the chart records `gallium`, and
		// `Gallium citrate ga-67` is a radiodiagnostic it never mentions.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(TIED_ON_ONE_NAME);
		assertEquals("[Gallium citrate ga-67, Gallium chloride Ga-67, Gallium nitrate]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances("gallium")).toString(),
				"precondition: one recorded name, three substances, the earliest entry answering first");

		List<String> details = DrugReferenceTestSupport.contraindicationDetails(
				DrugReferenceTestSupport.validator(service).validate("",
						"Is it safe to give her gallium nitrate?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("gallium"), null)));

		assertEquals("[The patient has a recorded allergy to gallium., "
				+ "The patient has a recorded allergy to gallium., "
				+ "The patient has a recorded allergy to gallium.]", details.toString(),
				"every tied row is quoted in the chart's own words — including the one dataset order put "
						+ "first, was: " + details);
	}

	@Test
	public void aLabelThatSpellsOutTheRecordedNameKeepsIt() throws IOException {
		// The other side of the tie, and the case that decides the question is asked of what the chip
		// PRINTS rather than of the display name alone. `Benzylpenicillin` and `Procaine benzylpenicillin`
		// share the rxnorm_name `penicillin G` exactly as the gallium rows share `gallium`, so both
		// families reach the arm the same way — but these two display names diverge from it, so
		// displayLabel appends it and the label reads `Benzylpenicillin (penicillin g)`. That label quotes
		// the chart, so it must survive; gating on getName() alone would have replaced it with the raw
		// token and lost the substance for no gain. Measured through the real parse of the shipped KB, 20
		// rows are named by that appended synonym and by nothing else.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(TIED_ON_ONE_NAME);
		assertEquals("[Benzylpenicillin, Procaine benzylpenicillin]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances("penicillin g")).toString(),
				"precondition: one recorded name, two substances");
		assertEquals("Benzylpenicillin (penicillin g)",
				service.findImpliedSubstances("penicillin g").get(0).displayLabel(),
				"precondition: and the label — not the display name — is what spells the recorded name "
						+ "out, which is the whole difference from the gallium family above");

		List<String> details = DrugReferenceTestSupport.contraindicationDetails(
				DrugReferenceTestSupport.validator(service).validate("",
						"Is it safe to give her benzylpenicillin?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("penicillin g"), null)));

		// One chip: only Benzylpenicillin is in play, the other row's names being absent from the
		// question. Which is the point — the tie is in the RECORD's resolution, not in what was asked.
		assertEquals("[The patient has a recorded allergy to Benzylpenicillin (penicillin g).]",
				details.toString(),
				"a label that carries the recorded name is what the chart says, tie or no tie, was: "
						+ details);
	}

	@Test
	public void thePromptCarriesTheCorrectedSentenceToo() throws IOException {
		// The other half of a chip (issue #110): every chip is injected as a numbered, citable
		// safety_finding, so a corrected chip and an uncorrected record would put the false sentence into
		// the context window with nothing on screen to contradict it.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(SHARED_CIEL_LIST);
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		List<RecordMapping> findings = DrugReferenceTestSupport.injectedFindings(
				injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set(KADCYLA), null),
						"Is it safe to give her trastuzumab deruxtecan?"));

		assertEquals(2, findings.size(), "one citable record per chip, was: " + findings);
		assertEquals(DrugReferenceInjector.FINDING_PREFIX
				+ "Trastuzumab deruxtecan: The patient has a recorded allergy to ado-trastuzumab "
				+ "emtansine." + DrugReferenceInjector.STRENGTH_WITHHOLD, findings.get(1).getText(),
				"the record is ABOUT the subject and QUOTES the chart, was: " + findings);
	}

	@Test
	public void aFreeTextAllergenCannotReachTheChartsOwnWording() throws IOException {
		// The bound on what can be printed. Reaching the chart-wording branch needs the WHOLE recorded
		// string to be claimed by more than one substance at DrugReference.NAME_IS_ANOTHER_NAME or
		// better — the equal-claimant leg's own gate — which on this parser means the string is a name
		// the reference data itself publishes. A non-coded allergen, which PatientClinicalContextBuilder
		// files verbatim and which PatientClinicalContext.containsToken's javadoc calls genuinely free
		// text, resolves only by CONTAINMENT, a rank that leg never runs at — so however the chart words
		// it, the chip goes on naming the row.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(SHARED_CIEL_LIST);
		String freeText = "trastuzumab infusion \u2014 rash and fever";
		assertEquals("[Trastuzumab]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances(freeText)).toString(),
				"precondition: free text resolves by containment, so one substance and no tie");

		List<String> details = DrugReferenceTestSupport.contraindicationDetails(
				DrugReferenceTestSupport.validator(service).validate("",
						"Is it safe to give her trastuzumab?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set(freeText), null)));

		assertEquals("[The patient has a recorded allergy to Trastuzumab.]", details.toString(),
				"the row keeps its own name and the free text stays out of the chip, was: " + details);
	}
}
