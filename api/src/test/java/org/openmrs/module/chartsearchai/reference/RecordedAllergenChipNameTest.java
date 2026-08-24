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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.RecordMapping;

/**
 * WHICH NAME the allergen arm may call a recorded allergy (issue #268).
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
 * <p><b>The rule.</b> A row may be named as the recorded allergy only where the recorded name NAMES
 * it: it is the unique strongest NAME claimant among the substances in play, or a name the printed
 * label is built from occurs in the recorded string, or a combination constituent of the recorded
 * name resolves to its SUBSTANCE. Where it does, the chip keeps saying "The patient has a recorded
 * allergy to X." Where it does not, that sentence would be false, so the chip states the
 * relationship instead — "X is contraindicated by a recorded allergy to Y" — in the curated rule
 * arm's own shape, which is also what keeps the wire contract that every {@code detail} names its
 * own drug and tells one finding from another.
 *
 * <p>The same rule binds the arm's two CLASS sentences, which say "as the patient's allergy to Y"
 * and so assert the allergy as flatly — only the allergen half of those moves, since they already
 * name their own subject.
 *
 * <p>Each case below names, in its own comment, the clause or sentence it discriminates — mutate
 * that one thing and the case named on it is what reddens.
 * Removing the unique clause altogether also reddens five cases that were already in the suite, Opium
 * under a {@code papaveretum} allergy among them.
 */
public class RecordedAllergenChipNameTest {

	/** The three trastuzumab rows share one CIEL list although they are three DrugBank substances with
	 *  three ATC codes — so `ado-trastuzumab emtansine` is one of all three's own names. */
	private static final String SHARED_CIEL_LIST = "chartsearchai-test/ddi-alias-names-another-substance.json";

	/** Three shipped shapes of a recorded allergy reaching several substances — two families sharing
	 *  one rxnorm_name that is no row's display name, differing in whether
	 *  {@link DrugReference#displayLabel()} appends it as a synonym, and one combination whose third
	 *  substance is reached only through a CONSTITUENT. The fixture's own {@code note} describes each. */
	private static final String TIED_ON_ONE_NAME = "chartsearchai-test/ddi-tied-alias-allergen.json";

	/** Four shipped rows publishing one combination name, of which the name NAMES three — plus the
	 *  class partner that makes the fourth reachable through the CLASS arm rather than the identity
	 *  one. The fixture's own {@code note} describes it. */
	private static final String CLASS_ARM = "chartsearchai-test/ddi-class-arm-unnamed-allergen.json";

	private static final String KADCYLA = "ado-trastuzumab emtansine";

	@Test
	public void aRowTheRecordedNameDoesNotNameIsNotAnnouncedAsTheRecordedAllergy() throws IOException {
		// THE case, and the one the fixture's own metadata records as having produced a false LIVE label.
		// The patient is allergic to Kadcyla; the question is about Enhertu, a different drug. All three
		// trastuzumab rows share one CIEL list, so the allergen arm reaches all three substances — which
		// is right, because the class comparisons must see them — but of the two this question puts in
		// play, only Trastuzumab is something the chart's own string says.
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
				+ "Trastuzumab deruxtecan is contraindicated by a recorded allergy to "
				+ "\"ado-trastuzumab emtansine\".]", details.toString(),
				"the row the recorded name does not name may not be announced as the recorded allergy — "
						+ "it states the relationship instead, and still names itself, was: " + details);
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

		assertEquals("[Gallium citrate ga-67 is contraindicated by a recorded allergy to \"gallium\"., "
				+ "Gallium chloride Ga-67 is contraindicated by a recorded allergy to \"gallium\"., "
				+ "Gallium nitrate is contraindicated by a recorded allergy to \"gallium\".]",
				details.toString(),
				"every tied row states the relationship instead of claiming the allergy — including the "
						+ "one dataset order put first — and each still names its own drug, so the three "
						+ "details stay distinct, was: " + details);
	}

	@Test
	public void aLabelThatSpellsOutTheRecordedNameKeepsIt() throws IOException {
		// The other side of the tie, and the case that decides the question is asked of what the chip
		// PRINTS rather than of the display name alone. `Benzylpenicillin` and `Procaine benzylpenicillin`
		// share the rxnorm_name `penicillin G` exactly as the gallium rows share `gallium`, so both
		// families reach the arm the same way — but these two display names diverge from it, so
		// displayLabel appends it and the label reads `Benzylpenicillin (penicillin g)`. That label quotes
		// the chart, so it must survive; gating on getName() alone would have replaced it with the raw
		// token and lost the substance for no gain. Mutate the clause away and this case is the one that
		// reddens.
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
	public void aSubstanceReachedONLYThroughAConstituentIsStillNamed() throws IOException {
		// The third clause, isolated. `Ubidecarenone` appears nowhere in the recorded string and the row
		// publishes no diverging synonym, so neither of the two clauses above can name it — it is in play
		// only because findImpliedSubstances' constituent leg resolved `coenzyme q10` to it. The chip may
		// print that name for exactly the reason the leg admits the substance, so the support test asks
		// the leg's own question through the leg's own resolver rather than approximating it.
		//
		// Without this case the clause was unpinned: disabling it entirely left the whole suite green,
		// because every other combination in it names its constituent's row in the recorded string and
		// so is carried by the clause above. Mutate the clause away and this case is what reddens.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(TIED_ON_ONE_NAME);
		String combination = "coenzyme q10 / levocarnitine / vitamin e";
		assertEquals("[Levocarnitine, Vitamin E, Ubidecarenone]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances(combination)).toString(),
				"precondition: the combination must reach a substance it does not spell out");

		List<String> details = DrugReferenceTestSupport.contraindicationDetails(
				DrugReferenceTestSupport.validator(service).validate("",
						"Is it safe to give her ubidecarenone?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set(combination), null)));

		assertEquals("[The patient has a recorded allergy to Ubidecarenone.]", details.toString(),
				"the constituent's own substance keeps its name, was: " + details);
	}

	@Test
	public void aRowThatMerelyALIASESAConstituentIsNotWhatThatConstituentNames() throws IOException {
		// The derivation clause asks the question its LEG asks — which substance does this constituent
		// RESOLVE to — and not the looser "does this row claim the constituent among its own names". The
		// two differ exactly here: `hydrocortisone` resolves to Hydrocortisone, while Hydrocortisone
		// butyrate merely publishes it (the ester carries the moiety's name, which is legitimate data and
		// the shape issues #198/#209 narrow at resolution time). Reading the row's claim instead would
		// announce an ester the chart never mentions — the same falsehood this class exists to remove,
		// re-entering through the clause meant to prevent it.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(SHARED_CIEL_LIST);
		String combination = "hydrocortisone / neomycin";
		assertEquals("[Hydrocortisone, Hydrocortisone butyrate]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances(combination)).toString(),
				"precondition: the combination must reach the ester as well as the moiety");

		List<String> details = DrugReferenceTestSupport.contraindicationDetails(
				DrugReferenceTestSupport.validator(service).validate("",
						"Is it safe to give her hydrocortisone butyrate?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set(combination), null)));

		assertEquals("[The patient has a recorded allergy to Hydrocortisone., "
				+ "Hydrocortisone butyrate is contraindicated by a recorded allergy to "
				+ "\"hydrocortisone / neomycin\".]", details.toString(),
				"the moiety the constituent resolves to keeps its name; the ester, which the constituent "
						+ "does not name, is quoted in the chart's words, was: " + details);
	}

	@Test
	public void theClassSentenceCannotAssertAnAllergyEither() throws IOException {
		// The same rule on the arm's OTHER two sentences. "X is in the same ATC class (C) as the
		// patient's allergy to Y" asserts the allergy as flatly as the identity chip does, so a Y the
		// recorded name does not name is the same falsehood — and it was reachable in the SAME payload
		// as the chip that had just declined to state it. Only the allergen half moves: that sentence
		// already names its own subject and already states a relationship, so it needs no second form.
		//
		// The arm walks the implied substances in order and stops at the first that shares the class,
		// which here is the one the record does not name. Preferring a named implied substance where
		// both share the class would give a more specific sentence, but it would change WHICH chip is
		// raised rather than only its wording, so it is left alone.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(CLASS_ARM);
		String kit = "amoxicillin / esomeprazole / levofloxacin combination kit";
		assertEquals("[Levofloxacin, Omeprazole, Esomeprazole, Amoxicillin]",
				DrugReferenceTestSupport.names(service.findImpliedSubstances(kit)).toString(),
				"precondition: the kit must reach both proton-pump rows, in an order that puts the one "
						+ "it does not name first");

		List<String> details = DrugReferenceTestSupport.contraindicationDetails(
				DrugReferenceTestSupport.validator(service).validate("",
						"Is it safe to give her lansoprazole?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set(kit), null)));

		assertEquals("[Lansoprazole is in the same ATC class (A02BC) as the patient's allergy to "
				+ "\"amoxicillin / esomeprazole / levofloxacin combination kit\" — possible "
				+ "cross-reactivity]", details.toString(),
				"the class sentence names the allergen the CHART records, not the row the kit merely "
						+ "reached, was: " + details);
	}

	@Test
	public void twoSpellingsOfOneAllergyDoNotLetRowOrderDecideTheWording() throws IOException {
		// The de-duplication must not throw away EVIDENCE. Two records naming one substance are one
		// clinical fact and collapse to one chip — but they are not equally good evidence: the bare
		// rxnorm_name names the row, while the dose-suffixed spelling reaches it only by containment and
		// cannot, the display name's trailing qualifier keeping it out of the recorded string. Keeping
		// whichever arrived first made the sentence depend on the order PatientService.getAllergies
		// returned the rows, which is nothing a clinician should be able to see. Naming survives the
		// merge instead, so both orders read alike — and it is the STRONGER sentence that survives.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(TIED_ON_ONE_NAME);
		assertEquals("[Latanoprostene bunod (ophthalmic)]", DrugReferenceTestSupport
				.names(service.findImpliedSubstances("latanoprostene bunod 5mg")).toString(),
				"precondition: the dose-suffixed spelling must resolve to the same single substance as "
						+ "the bare one, or the two are not de-duplicated at all");

		String question = "Is it safe to give her latanoprostene bunod?";
		String named = "[The patient has a recorded allergy to Latanoprostene bunod (ophthalmic).]";
		assertEquals(named, DrugReferenceTestSupport.contraindicationDetails(
				DrugReferenceTestSupport.validator(service).validate("", question,
						DrugReferenceTestSupport.ctx(60, null, null, null, DrugReferenceTestSupport
								.set("latanoprostene bunod", "latanoprostene bunod 5mg"), null))).toString(),
				"the naming record first");
		assertEquals(named, DrugReferenceTestSupport.contraindicationDetails(
				DrugReferenceTestSupport.validator(service).validate("", question,
						DrugReferenceTestSupport.ctx(60, null, null, null, DrugReferenceTestSupport
								.set("latanoprostene bunod 5mg", "latanoprostene bunod"), null))).toString(),
				"and the same the other way round, which is the whole point");
	}

	@Test
	public void aNamingRecordOutranksANonNamingOneOnTheSameSubstance() throws IOException {
		// The same rule one layer down, where the two records are genuinely DIFFERENT findings rather
		// than two spellings: `gallium` reaches three substances and names none of them, `gallium
		// nitrate` reaches one and names it. Both raise an identity chip for that one substance, so they
		// meet on the ledger's key at equal rank — and the ledger kept whichever came first, so a chart
		// recording `Gallium nitrate` verbatim was reported as merely contraindicated by `gallium`. The
		// tiebreak can only promote a naming chip, never demote one.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(TIED_ON_ONE_NAME);
		String question = "Is it safe to give her gallium nitrate?";
		String surviving = "The patient has a recorded allergy to Gallium nitrate.";

		for (java.util.List<String> order : java.util.Arrays.asList(
				java.util.Arrays.asList("gallium", "gallium nitrate"),
				java.util.Arrays.asList("gallium nitrate", "gallium"))) {
			List<String> details = DrugReferenceTestSupport.contraindicationDetails(
					DrugReferenceTestSupport.validator(service).validate("", question,
							DrugReferenceTestSupport.ctx(60, null, null, null,
									DrugReferenceTestSupport.set(order.get(0), order.get(1)), null)));
			assertTrue(details.contains(surviving),
					"the record that NAMES the drug must speak whatever order it arrived in " + order
							+ ", was: " + details);
		}
	}

	@Test
	public void aGenericTheLabelNeverPrintsCannotNameItsRow() throws IOException {
		// The half of labelNameOccursIn that reads the APPENDED generic, isolated — and it was the half
		// nothing pinned: before this case, removing the appendsGenericName guard left the whole suite
		// green while changing naming decisions over the shipped KB (measured on this branch, it
		// refuses 7 rows nothing else names).
		//
		// Amphetamine's rxnorm_name is `dextroamphetamine`, which CONTAINS its display name, so
		// displayLabel prints no synonym and the generic is not part of what a chip would show. A chart
		// recording `dextroamphetamine sulfate` therefore carries a name this row never prints — while
		// naming Dextroamphetamine, a different substance, outright. Reading the generic regardless
		// would let one string claim both.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(TIED_ON_ONE_NAME);
		DrugReference amphetamine = DrugReferenceTestSupport
				.row(DrugReferenceTestSupport.ddiFixtureEntries(TIED_ON_ONE_NAME), "Amphetamine");
		assertEquals("Amphetamine", amphetamine.displayLabel(),
				"precondition: the label must print no synonym, or this case tests the other half");

		List<String> details = DrugReferenceTestSupport.contraindicationDetails(
				DrugReferenceTestSupport.validator(service).validate("",
						"Is it safe to give her amphetamine or dextroamphetamine?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set("dextroamphetamine sulfate"), null)));

		assertEquals("[The patient has a recorded allergy to Dextroamphetamine., "
				+ "Amphetamine is contraindicated by a recorded allergy to "
				+ "\"dextroamphetamine sulfate\".]", details.toString(),
				"the substance the chart's string names keeps its name; the one whose generic the label "
						+ "never prints does not, was: " + details);
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
				+ "Trastuzumab deruxtecan: Trastuzumab deruxtecan is contraindicated by a recorded "
				+ "allergy to \"ado-trastuzumab emtansine\"." + DrugReferenceInjector.STRENGTH_WITHHOLD,
				findings.get(1).getText(),
				"the record carries the chip's sentence verbatim, was: " + findings);
	}

	@Test
	public void anAllergenRecordedWithAReactionStillCannotNameARivalSubstance() throws IOException {
		// The commoner shape of the very defect this class exists for, and the one the first version of
		// the rule missed. Four characters of reaction text after the drug name drop the resolution to
		// the CONTAINMENT rank, where findImpliedSubstances' equal-claimant leg does not run — so the
		// two rival gallium rows never enter the implied set and the survivor looks "unique" for a
		// reason that is an artefact of the resolution rather than evidence about the record. Naming it
		// then prints `Gallium citrate ga-67`, a radiodiagnostic the chart never mentions, off a chart
		// that says `Gallium`: byte for byte the sentence the case above exists to remove.
		//
		// So the unique clause requires a NAME claim, not merely the strongest one available. Measured
		// through the real parse of the shipped KB, that costs nothing on a recorded name the reference
		// data publishes: 145 (name, row) pairs are claimed only at the containment rank, 143 named by
		// their own label anyway and the other two by the derivation clause. None is lost. Free text is
		// where a containment match does not carry the row's name.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(TIED_ON_ONE_NAME);
		String recordedWithReaction = "gallium \u2014 hives";
		assertEquals("[Gallium citrate ga-67]", DrugReferenceTestSupport
				.names(service.findImpliedSubstances(recordedWithReaction)).toString(),
				"precondition: the reaction text drops the claim to containment, so the two rivals are "
						+ "not in play and nothing else competes with the survivor");

		List<String> details = DrugReferenceTestSupport.contraindicationDetails(
				DrugReferenceTestSupport.validator(service).validate("",
						"Is it safe to give her gallium nitrate?",
						DrugReferenceTestSupport.ctx(60, null, null, null,
								DrugReferenceTestSupport.set(recordedWithReaction), null)));

		assertEquals("[Gallium citrate ga-67 is contraindicated by a recorded allergy to "
				+ "\"gallium — hives\".]", details.toString(),
				"an uncontested row claimed only by containment still may not be announced as the "
						+ "recorded allergy, was: " + details);
	}

	@Test
	public void aFreeTextAllergenResolvingToOneSubstanceKeepsTheRowsName() throws IOException {
		// The occurrence clause carrying a free-text allergen on its own. A non-coded allergen — which
		// PatientClinicalContextBuilder files verbatim and PatientClinicalContext.containsToken's
		// javadoc calls genuinely free text — resolves only by CONTAINMENT, which is not a NAME claim,
		// so the unique clause cannot name it (which is what
		// anAllergenRecordedWithAReactionStillCannotNameARivalSubstance turns on, where nothing else
		// names it either). Here the row's own display name is right there in the recorded string, so the chip
		// goes on saying the patient is allergic to Trastuzumab, which the chart does say.
		//
		// Mutating the display-name half of labelNameOccursIn away is what reddens this.
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
