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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.serializer.PatientChartSerializer.PatientChart;

/**
 * Matching an interaction rule's token against a drug the patient is on, when the chart names that
 * drug something the rule does not (issue #136).
 *
 * <p><b>The defect.</b> {@link PatientClinicalContext#hasActiveDrug} only ever saw the rule's own
 * single match token, so a drug ordered under a name the reference data carries as an ALIAS rather
 * than as the token had no interaction coverage at all. The load-bearing case is aspirin under its
 * chemical name: every DDInter rule about it carries the token {@code aspirin} — the parser writes
 * each rule's token from the partner row's {@code rxnorm_name} — while the row's own {@code name} is
 * {@code Acetylsalicylic acid}, which is also a real drug-concept name in the 3.7.1 demo dictionary
 * and does not contain the string {@code aspirin}. Before issue #128 that order matched the token
 * {@code salicylic acid} — the WRONG drug, inheriting an unrelated entry's rules; after it, nothing.
 * A Major warfarin-versus-aspirin bleeding interaction was silently absent.
 *
 * <p><b>The fix, and the shape it must not take.</b> The entry's names are consulted by resolving the
 * patient's orders to their reference entries and asking whether the rule's token IS one of those
 * entries' own names — {@link DrugReference#isNamed}, the same exact-identity test
 * {@code DrugSafetyValidator.identifies} already uses on the reference side. It is deliberately NOT a
 * boundary scan of the token against those names: an entry's alias list carries COMBINATION-PRODUCT
 * names ({@code salicylic acid / urea} is an alias of the Urea entry as well as the Salicylic acid
 * one), and a scan would let a urea order inherit salicylic acid's rules — a drug the patient is on
 * in no form, which is issue #86's defect arriving by a new route. The third case below is that
 * hazard, and it fails on a scanning implementation while every other case here passes.
 *
 * <p><b>Both directions, measured 2026-08-05</b> over the 3.7.1 demo dictionary's 2533 drug and
 * drug-concept names against the full 19MB KB (2093 distinct rule tokens, 5169 distinct aliases):
 * matching pairs go from <b>907 to 1001 — 94 added, 0 removed</b>, over 77 distinct order names and
 * 27 distinct rule tokens. Every added pair was enumerated and classified by token: in all 94 the
 * patient IS on the drug the matched entry describes, so none is a fabricated drug. In <b>81</b> the
 * token is that drug under another vocabulary ({@code rifampin}/{@code Rifampicin},
 * {@code albuterol}/{@code Salbutamol}, {@code torsemide}/{@code Torasemide}, and the vaccine
 * antigens). In the remaining <b>13</b> the token names a DIFFERENT substance from the entry's own
 * name, so the chip's partner label is wrong while its finding is right: 9 because DDInter's
 * {@code rxnorm_name} for that row is a mis-normalisation ({@code Chlorpheniramine}/{@code chlorine}
 * ×3, {@code Sulfamethoxazole}/{@code sulfamethazine} ×6) and 4 because it is a close congener
 * ({@code Omeprazole}/{@code esomeprazole} ×2, {@code Isosorbide}/{@code isosorbide mononitrate} ×2).
 * That mislabel is the dataset's, is the class {@link DrugReference#displayLabel} exists for, and
 * already reached any patient whose order name happened to carry the {@code rxnorm_name} spelling.
 * The whole #86/#128/#129 kill set was re-scored through this arm and through the allergen resolver:
 * 0 of 21 nesting pairs leak.
 *
 * <p>One consequence of the widening is pinned elsewhere rather than duplicated here: matching every
 * name of a drug means two rules whose tokens are two names of the SAME partner both match, so
 * {@code DrugSafetyValidator.bestRulePerPartner} had to start grouping on the partner ENTRY instead of
 * on the rule's label. {@code DrugSafetyQuestionPairInteractionTest
 * .aPairAlsoJoinedByARuleNamingAnActiveOrderStaysWithTheActiveOrderArm} already owns that fixture — a
 * {@code coumadin} row and a {@code warfarin} row against one {@code Warfarin 5mg} order — and it
 * failed on this change until the grouping moved, which is what makes it the pin.
 *
 * <p>Every case runs the real pipeline — a verbatim KB slice through the real
 * {@link DdiDrugReferenceSource}, then the real
 * {@link DrugSafetyValidator#validate(String, String, PatientClinicalContext)} or the real
 * {@link DrugReferenceInjector#injectRecords} — with GP reads on their no-context defaults (severity
 * floor {@code minor}).
 */
public class RuleTokenAliasOrderMatchingTest {

	private static final String WARFARIN_QUESTION = "Is it safe to start warfarin?";

	private static final String WARFARIN_ANSWER = "Warfarin could be started with INR monitoring.";

	private DrugReferenceService service() throws IOException {
		return DrugReferenceTestSupport
				.serviceWith(DrugReferenceTestSupport.ddiFixtureEntries(AllergenNameResolutionTest.FIXTURE));
	}

	private DrugSafetyValidator validator() throws IOException {
		return DrugReferenceTestSupport.validator(service());
	}

	private PatientClinicalContext onOrders(String... orderNames) {
		return DrugReferenceTestSupport.ctx(60, null, DrugReferenceTestSupport.set(orderNames), null,
				null, null);
	}

	@Test
	public void anOrderUnderTheDrugsChemicalNameIsStillCheckedAgainstTheRule() throws IOException {
		// Issue #136's headline. Both order names are real rows in the 3.7.1 demo dictionary and both
		// name the same substance; the second is the one whose spelling the rule's token does not
		// contain, so the pair isolates exactly that difference.
		DrugSafetyValidator validator = validator();

		List<SafetyWarning> onAspirin = validator.validate(WARFARIN_ANSWER, WARFARIN_QUESTION,
				onOrders("Aspirin 81mg"));
		assertTrue(DrugReferenceTestSupport.detailContains(onAspirin, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "active order aspirin", "Major"),
				"precondition: the rule must be live for the spelling that carries the token, else the "
						+ "case below proves nothing, was: " + onAspirin);

		List<SafetyWarning> onChemicalName = validator.validate(WARFARIN_ANSWER, WARFARIN_QUESTION,
				onOrders("Acetylsalicylic acid"));
		assertTrue(DrugReferenceTestSupport.detailContains(onChemicalName, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "active order aspirin", "Major"),
				"an order named \"Acetylsalicylic acid\" must be checked against aspirin's rules — the "
						+ "reference data's own entry carries that name, was: " + onChemicalName);
	}

	@Test
	public void theChipStillNamesTheRulesOwnTokenNotTheAliasItMatched() throws IOException {
		// The label decision issue #136 records: a rule matched THROUGH an alias still says the token,
		// which is what partnerLabel renders, so no client sees a partner's spelling change under it.
		// Which alias the order matched is not part of the chip; the grouping key is a separate
		// decision (bestRulePerPartner keys on the partner ENTRY, see its javadoc).
		List<SafetyWarning> warnings = validator().validate(WARFARIN_ANSWER, WARFARIN_QUESTION,
				onOrders("Acetylsalicylic acid"));
		assertFalse(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "active order acetylsalicylic acid"),
				"the chip names the rule's token, not the alias the order matched through, was: " + warnings);
	}

	@Test
	public void aConstituentOrderDoesNotInheritTheCombinationProductsRules() throws IOException {
		// The hazard the alias set introduces and the token-only rule did not have. `salicylic acid /
		// urea` is a CIEL alias of BOTH the Salicylic acid entry and the Urea entry, so an
		// implementation that SCANS a rule's token across the resolved entry's names finds `salicylic
		// acid` inside the Urea entry's own alias and reports a urea order as a salicylic acid order.
		// Warfarin carries a real Moderate rule against Salicylic acid, so the wrong chip is reachable.
		DrugSafetyValidator validator = validator();
		PatientClinicalContext onUrea = onOrders("Urea 40% cream");

		List<SafetyWarning> lactulose = validator.validate("Lactulose could be given.",
				"Is it safe to give lactulose?", onUrea);
		assertTrue(DrugReferenceTestSupport.detailContains(lactulose, SafetyWarning.TYPE_INTERACTION,
				"Lactulose", "active order urea", "Moderate"),
				"precondition: the urea order must resolve its own entry and raise its own rule, else the "
						+ "absence below proves nothing, was: " + lactulose);

		List<SafetyWarning> warfarin = validator.validate(WARFARIN_ANSWER, WARFARIN_QUESTION, onUrea);
		assertFalse(DrugReferenceTestSupport.detailContains(warfarin, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "active order salicylic acid"),
				"a urea order must not inherit salicylic acid's rules through the combination-product "
						+ "alias they share, was: " + warfarin);

		// The other direction of the same alias, which SHOULD fire: an order of the combination really
		// does contain salicylic acid, so it inherits that constituent's rules.
		List<SafetyWarning> combination = validator.validate(WARFARIN_ANSWER, WARFARIN_QUESTION,
				onOrders("Salicylic acid / urea 6% cream"));
		assertTrue(DrugReferenceTestSupport.detailContains(combination, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "active order salicylic acid", "Moderate"),
				"a combination order does carry its constituents, so it is checked against their rules, "
						+ "was: " + combination);
	}

	@Test
	public void anAliasBroaderThanTheTokenStillNamesTheSameDrug() throws IOException {
		// The second hazard issue #136 names: the Salicylic acid entry carries `salicylate`,
		// `sodium salicylate` and `potassium salicylate` — aliases that name more products than the
		// token does. Reaching them is the point (a sodium salicylate order is a salicylate order and
		// carries the same warfarin bleeding risk); what must not happen is reaching a drug that merely
		// nests one of them, which the case below and the kill set pin.
		List<SafetyWarning> warnings = validator().validate(WARFARIN_ANSWER, WARFARIN_QUESTION,
				onOrders("Sodium salicylate"));
		assertTrue(DrugReferenceTestSupport.detailContains(warnings, SafetyWarning.TYPE_INTERACTION,
				"Warfarin", "active order salicylic acid", "Moderate"),
				"a sodium salicylate order must be checked against salicylic acid's rules, was: " + warnings);
	}

	@Test
	public void theIssue86KillSetStaysDeadThroughTheAliasArm() throws IOException {
		// The kill direction, re-asserted through the NEW arm rather than only through the order-name
		// matcher DrugSafetyDiacriticOrderNameTest already pins: each row's long name must not resolve
		// to the nested drug's entry, so the nested drug's rules must not reach it. Rows are
		// {subject drug, question, order name that CONTAINS the token, the nested token, an order name
		// the token legitimately names}; the last column is the precondition that proves the rule is
		// live in this slice.
		String[][] rows = {
				{ "Spironolactone", "Is it safe to continue spironolactone?", "Tiotropium", "opium",
						"Opium tincture" },
				{ "Lactulose", "Is it safe to give lactulose?", "Hydroxyurea 500mg", "urea",
						"Urea 40% cream" },
				{ "Warfarin", WARFARIN_QUESTION, "P-aminosalicylic acid", "salicylic acid",
						"Salicylic acid 6% ointment" } };
		DrugSafetyValidator validator = validator();
		for (String[] row : rows) {
			String subject = row[0];
			String answer = subject + " could be given.";
			List<SafetyWarning> onNestingName = validator.validate(answer, row[1], onOrders(row[2]));
			assertFalse(DrugReferenceTestSupport.detailContains(onNestingName,
					SafetyWarning.TYPE_INTERACTION, subject, "active order " + row[3]),
					"\"" + row[2] + "\" must not be reported as an active " + row[3] + " order, was: "
							+ onNestingName);

			List<SafetyWarning> onNestedDrug = validator.validate(answer, row[1], onOrders(row[4]));
			assertTrue(DrugReferenceTestSupport.detailContains(onNestedDrug,
					SafetyWarning.TYPE_INTERACTION, subject, "active order " + row[3]),
					"precondition: a patient actually on " + row[3] + " must get the real " + subject
							+ " x " + row[3] + " chip, else the assertion above proves nothing, was: "
							+ onNestedDrug);
		}
	}

	@Test
	public void thePromotedPromptTextAgreesWithTheChip() throws IOException {
		// The join has two callers — the chip decision here and the prompt-promotion predicate in
		// DrugReferenceInjector.orderedInteractionNotes — and issue #136 requires it stay ONE join, or
		// the chip names a partner the rendered reference record never promoted and the model answers
		// from a record that omits the interaction the clinician is being shown. Real injector over the
		// real fixture, with the real validator behind it.
		DrugReferenceService service = service();
		DrugReferenceInjector injector = DrugReferenceTestSupport.injector(service);
		injector.setDrugSafetyValidator(DrugReferenceTestSupport.validator(service));

		PatientChart chart = injector.injectRecords(DrugReferenceTestSupport.oneRecordChart(),
				onOrders("Acetylsalicylic acid"), WARFARIN_QUESTION);

		String reference = DrugReferenceTestSupport.injectedReference(chart).getText();
		assertTrue(reference.contains("Interactions: aspirin (Major"),
				"the promoted segment must lead with the partner the chip names, was: " + reference);
	}
}
