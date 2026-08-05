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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The two directions {@link DrugReference#matchesOrderName} has to satisfy at once, on the real
 * data that produced each of them. {@link HasActiveDrugWholeWordTest} pins the nested-name case
 * this fix started from ("chlorothiazide" inside "hydrochlorothiazide"); this class pins the two
 * live-reproduced fabrications that same nesting caused, and the localized order names that a
 * symmetric word boundary would have silently stopped checking.
 *
 * <ul>
 *   <li><b>Must not fire.</b> On the 3.7.1 standalone a patient on <b>Tiotropium</b> asked about
 *       linezolid was told of an active <b>opium</b> order at Major severity, mechanism text and
 *       all ("tiotr|opium"); a patient on <b>Spironolactone</b> asked about dolutegravir was told
 *       of an active <b>iron</b> order, twice ("sp|iron|olactone"). Neither concept carries an ATC
 *       mapping there, so the name arm alone produced both.</li>
 *   <li><b>Must still fire.</b> This deployment's dictionary is multilingual —
 *       {@code Aspirine Co 81mg}, {@code Aspirina}, {@code Clarithromycine Co 500mg},
 *       {@code Simvastatine}, {@code Multivitamines et fer} are all real rows in it. Requiring a
 *       symmetric boundary drops 135 of containment's 896 matches over that dictionary, and 68 of
 *       the dropped are localized spellings of the very token being matched, so those patients
 *       would silently stop being checked for interactions — a false negative dressed as noise
 *       removal.</li>
 *   <li><b>Must not fire, at the far edge.</b> The trailing allowance those localized names need is
 *       bounded, and the bound is pinned from both sides here: one letter short and
 *       {@code Multivitamines et fer} stops being checked, two letters long and
 *       {@code Heparinoids / salicylic acid} starts being read as heparin. Without the upper-edge
 *       case a later widening of the constant reinstates exactly the fabricated Major chips this
 *       class exists to prevent, and nothing fails.</li>
 *   <li><b>Must not fire in prose.</b> The allowance belongs to order names alone;
 *       {@link DrugReference#containsWord} keeps the symmetric boundary for prose. Collapsing the
 *       two matchers into one is what made #87 unsafe, and it can be done in either direction —
 *       the localized cases above catch a prose rule applied to order names, and this one catches
 *       the order-name rule applied to prose, where "aspiring" would be read as a proposal of
 *       aspirin.</li>
 * </ul>
 *
 * <p>Every scenario runs the real pipeline: real datasets parsed by the real sources, the real
 * {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)}, GP reads on their
 * no-context defaults (so the severity floor is the production {@code minor}). Each negative is
 * paired with the positive that proves the rule it must not fire on is live, so nothing here can
 * pass by resolving nothing.
 */
public class DrugSafetyOrderNameMatchingTest {

	/**
	 * A verbatim slice of the full DDInter KB (2283 drugs / 295,184 rows) carrying the rows behind
	 * the live-reproduced collisions — linezolid x opium, dolutegravir x iron — the
	 * multivitamin x warfarin row the localized plural must still match, and the warfarin x heparin
	 * row the far-edge case needs. The bundled 16-drug sample contains none of those drugs, so it
	 * cannot express any of this (same reason {@code ddi-severity-floor-pair.json} exists).
	 */
	private static final String COLLISION_SLICE = "chartsearchai-test/ddi-order-name-collisions.json";

	private DrugSafetyValidator collisionValidator() throws IOException {
		return DrugReferenceTestSupport.validator(
				DrugReferenceTestSupport.serviceWith(DrugReferenceTestSupport.ddiFixtureEntries(COLLISION_SLICE)));
	}

	/** A validator over the real bundled DDInter sample, parsed by the real source. */
	private DrugSafetyValidator bundledValidator() {
		return DrugReferenceTestSupport.validator(DrugReferenceTestSupport.ddinterService());
	}

	/**
	 * The context shape the production builder assembles for the live probe patients: active-order
	 * display names, no ATC codes. Deliberately no ATC — the collisions reproduced on concepts with
	 * no ATC mapping, so the name arm is the only thing that can raise a chip here; an ATC-fed
	 * context would let the class arm mask what is being measured.
	 */
	private PatientClinicalContext onOrders(String... orderNames) {
		return DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(orderNames), null,
				null, null);
	}

	@Test
	public void anInhaledTiotropiumOrderIsNotReportedAsAnActiveOpiumOrder() throws IOException {
		DrugSafetyValidator validator = collisionValidator();
		String question = "Is it safe to give linezolid?";
		String answer = "Linezolid could be considered for this infection.";

		// "Opium tincture" is one of this KB row's own CIEL concept names.
		List<SafetyWarning> onOpium = validator.validate(answer, question, onOrders("Opium tincture"));
		assertTrue(DrugReferenceTestSupport.detailContains(onOpium, SafetyWarning.TYPE_INTERACTION,
				"Linezolid", "active order opium", "Major"),
				"precondition: a patient actually on opium must get the real linezolid x opium Major "
						+ "chip, else this proves nothing, was: " + onOpium);

		// Susan Young's live context: the order's concept name is the bare "Tiotropium" (no drug
		// row on that order), and linezolid x tiotropium is an Unknown-severity row the floor
		// filters — so ANY warning here is about a drug she is not on.
		List<SafetyWarning> onTiotropium = validator.validate(answer, question, onOrders("Tiotropium"));
		assertTrue(onTiotropium.isEmpty(),
				"an inhaled tiotropium order must not be reported as an active opium order "
						+ "(\"tiotr|opium\"), was: " + onTiotropium);
	}

	@Test
	public void aSpironolactoneOrderIsNotReportedAsAnActiveIronOrder() throws IOException {
		DrugSafetyValidator validator = collisionValidator();
		String question = "Can I start dolutegravir?";
		String answer = "Dolutegravir could be started.";

		// "Iron IR 325mg" is a real drug row in this deployment's dictionary.
		List<SafetyWarning> onIron = validator.validate(answer, question, onOrders("Iron IR 325mg"));
		assertTrue(DrugReferenceTestSupport.detailContains(onIron, SafetyWarning.TYPE_INTERACTION,
				"Dolutegravir", "active order iron", "Major"),
				"precondition: a patient actually on iron must get the real dolutegravir x iron Major "
						+ "chip, else this proves nothing, was: " + onIron);

		// Melissa Wright's live context: the bare concept name "Spironolactone", which contains
		// "iron". The KB has no dolutegravir x spironolactone row at all, so nothing may fire.
		List<SafetyWarning> onSpironolactone = validator.validate(answer, question,
				onOrders("Spironolactone"));
		assertTrue(onSpironolactone.isEmpty(),
				"a spironolactone order must not be reported as an active iron order "
						+ "(\"sp|iron|olactone\"), was: " + onSpironolactone);
	}

	@Test
	public void localizedOrderNamesKeepBeingCheckedForInteractions() {
		// Why this cannot be symmetric word matching. Every order name below is a real row in the
		// 3.7.1 demo dictionary, which carries French and Spanish drug names throughout; under a
		// symmetric boundary a patient on "Aspirine Co 81mg" stops being checked for aspirin
		// interactions entirely, and the chip that disappears looks exactly like the noise this
		// issue set out to remove.
		DrugSafetyValidator validator = bundledValidator();
		String warfarinQuestion = "Is it safe to start warfarin?";
		String warfarinAnswer = "Warfarin could be started with monitoring.";
		for (String orderName : new String[] { "Aspirine Co 81mg", "Aspirine", "Aspirina" }) {
			List<SafetyWarning> warnings = validator.validate(warfarinAnswer, warfarinQuestion,
					onOrders(orderName));
			assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
					"Warfarin", "active order aspirin", "Major"),
					"the Major warfarin x aspirin rule must still fire for the localized order name \""
							+ orderName + "\", was: " + warnings);
		}

		List<SafetyWarning> onClarithromycine = validator.validate(
				"Simvastatin could be continued.", "Is it safe to give her simvastatin?",
				onOrders("Clarithromycine Co 500mg"));
		assertTrue(DrugReferenceTestSupport.detailContains(onClarithromycine,
				SafetyWarning.TYPE_INTERACTION, "Simvastatin", "active order clarithromycin", "Major"),
				"the Major simvastatin x clarithromycin rule must still fire for the French order name, "
						+ "was: " + onClarithromycine);

		List<SafetyWarning> onSimvastatine = validator.validate(
				"Clarithromycin could be prescribed.", "Is it safe to give her clarithromycin?",
				onOrders("Simvastatine"));
		assertTrue(DrugReferenceTestSupport.detailContains(onSimvastatine,
				SafetyWarning.TYPE_INTERACTION, "Clarithromycin", "active order simvastatin", "Major"),
				"the Major clarithromycin x simvastatin rule must still fire for the French order name, "
						+ "was: " + onSimvastatine);
	}

	@Test
	public void aLocalizedPluralOrderNameKeepsBeingCheckedForInteractions() throws IOException {
		// The one legitimate name a single-letter tail allowance misses, so the bound is pinned at
		// the value that matches it. "Multivitamines et fer" and "Multivitamin & Iron" are both real
		// names of the same product in this dictionary (the French one as a concept name, the
		// English one as a drug row), and the reference entry they name carries 2 Major and 8
		// Moderate rules — including the vitamin-K versus oral-anticoagulant one below — that would
		// otherwise stop being checked for a patient whose order shows the French spelling.
		DrugSafetyValidator validator = collisionValidator();
		String question = "Is it safe to start warfarin?";
		String answer = "Warfarin could be started with monitoring.";

		List<SafetyWarning> onEnglishName = validator.validate(answer, question,
				onOrders("Multivitamin & Iron"));
		assertTrue(DrugReferenceTestSupport.detailContains(onEnglishName, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "active order multivitamin", "Moderate"),
				"precondition: the English spelling of the same product must raise the real "
						+ "multivitamin x warfarin rule, else this proves nothing, was: " + onEnglishName);

		List<SafetyWarning> onFrenchPlural = validator.validate(answer, question,
				onOrders("Multivitamines et fer"));
		assertTrue(DrugReferenceTestSupport.detailContains(onFrenchPlural, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "active order multivitamin", "Moderate"),
				"the same rule must fire for the localized plural of the same product, was: "
						+ onFrenchPlural);
	}

	@Test
	public void aLongerTailThanAnInflectionIsADifferentSubstance() throws IOException {
		// The far edge of the same bound, so the constant is pinned from ABOVE as well as below.
		// "Heparinoids / salicylic acid" is a real concept name in this deployment's dictionary
		// (CIEL 104813; in the full KB it hangs off the three Salicylic acid rows and off nothing
		// else, never off Heparin — the KB carries no heparinoid row at all, its class members are
		// there under their own names, DDInter471 Danaparoid and DDInter1424 Pentosan
		// polysulfate). A heparinoid
		// is not heparin, so reading that order as an active heparin order fabricates a drug the
		// patient is not on, which is this issue's defect. Its tail past the token is
		// four letters, which is exactly where the measured curve turns: widening the allowance to 4
		// re-admits this name (and "Multi-Vitamin Adult" ~ "vitamin a") and starts fabricating Major
		// chips again, which is the defect issue #86 is about. Together with the localized-plural
		// test above, this holds MAX_ORDER_NAME_INFLECTION_LETTERS in [2, 3] — no name in the
		// measured corpus has a three-letter tail, so 2 versus 3 is not observable from real data
		// and 2 is the value shipped. The assertion below can be "no warning at all" because the
		// slice carries no salicylic-acid row, which makes it exactly "no heparin warning" — read it
		// as fixture-scoped: against the full KB that same order legitimately matches the token
		// "salicylic acid" (Moderate x warfarin, past the "/" word boundary). Add that row and the
		// fix is a narrower assertion here, never a wider tail allowance.
		DrugSafetyValidator validator = collisionValidator();
		String question = "Is it safe to start warfarin?";
		String answer = "Warfarin could be started with monitoring.";

		List<SafetyWarning> onHeparin = validator.validate(answer, question,
				onOrders("Heparin sodium 5000 units/mL"));
		assertTrue(DrugReferenceTestSupport.detailContains(onHeparin, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "active order heparin", "Major"),
				"precondition: a patient actually on heparin must get the real warfarin x heparin Major "
						+ "chip, else this proves nothing, was: " + onHeparin);

		List<SafetyWarning> onHeparinoids = validator.validate(answer, question,
				onOrders("Heparinoids / salicylic acid"));
		assertTrue(onHeparinoids.isEmpty(),
				"a heparinoid order must not be reported as an active heparin order — four trailing "
						+ "letters is a different substance, not an inflection, was: " + onHeparinoids);
	}

	@Test
	public void anEnglishWordStartingWithADrugNameIsNotAProposalOfThatDrug() {
		// The other way the two matchers can be collapsed into one: giving PROSE the order-name
		// tail allowance. Prose is words, and an ordinary word that merely starts with a drug name
		// is not that drug — measured over the full KB's 5169 aliases against the system word list
		// (/usr/share/dict/words, 235,976 entries), 22 of the single-word ones are one or two letters
		// short of an English word (aspirin ~ aspiring, warfarin ~ warfaring, urea ~ urease, iron ~
		// irony, clove ~ clover). The drugs a question or answer names are what the validator checks
		// at all (DrugSafetyValidator.validate -> findByQuery -> matchesText), so a lenient prose
		// rule does not mislabel an order — it invents the proposal the whole chip rests on. Widening
		// containsWord to the order-name allowance passes every other test in this module, which is
		// why this case exists.
		DrugSafetyValidator validator = bundledValidator();
		PatientClinicalContext onWarfarin = onOrders("Warfarin 5mg");

		List<SafetyWarning> namingAspirin = validator.validate(
				"Aspirin could be added for her cardiac risk.", "Is it safe to add aspirin?", onWarfarin);
		assertTrue(DrugReferenceTestSupport.detailContains(namingAspirin, SafetyWarning.TYPE_INTERACTION,
				"Acetylsalicylic acid (aspirin)", "active order warfarin", "Major"),
				"precondition: naming aspirin must raise the real aspirin x warfarin Major chip for a "
						+ "patient on warfarin, else this proves nothing, was: " + namingAspirin);

		List<SafetyWarning> merelyAspiring = validator.validate(
				"Encourage the training plan and keep her INR checks on schedule.",
				"She is an aspiring marathon runner — any concerns with her current medicines?",
				onWarfarin);
		assertTrue(merelyAspiring.isEmpty(),
				"\"aspiring\" must not put aspirin in play and fabricate a Major bleeding interaction "
						+ "for a patient on warfarin, was: " + merelyAspiring);
	}

	@Test
	public void plainOrderNamesWithADoseKeepBeingCheckedForInteractions() {
		// The live regression controls, in the exact context shape the production builder assembles
		// for those patients (drug row name plus order concept name).
		DrugSafetyValidator validator = bundledValidator();

		List<SafetyWarning> agnes = validator.validate("Warfarin could be started with monitoring.",
				"Is it safe to start warfarin?", onOrders("Aspirin 81mg", "Acetylsalicylate sodium"));
		assertTrue(DrugReferenceTestSupport.detailContains(agnes, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "active order aspirin", "Major"),
				"a dose-suffixed order name must still match its token, was: " + agnes);

		List<SafetyWarning> mary = validator.validate("Clarithromycin could be prescribed.",
				"Is it safe to give her clarithromycin?",
				onOrders("Simvastatin Co 20mg", "Simvastatin"));
		assertTrue(DrugReferenceTestSupport.detailContains(mary, SafetyWarning.TYPE_INTERACTION,
				"Clarithromycin", "active order simvastatin", "Major"),
				"a form-and-dose order name must still match its token, was: " + mary);

		List<SafetyWarning> joshua = validator.validate("Ibuprofen could be considered for pain.",
				"Can I give ibuprofen?", onOrders("Lisinopril 10 mg", "Lisinopril"));
		assertTrue(DrugReferenceTestSupport.detailContains(joshua, SafetyWarning.TYPE_INTERACTION,
				"Ibuprofen", "active order lisinopril", "Moderate"),
				"the Moderate ibuprofen x lisinopril rule must still fire, was: " + joshua);
	}
}
